#!/usr/bin/env python3
"""Generates the GUIDE's data for Pokémon Yellow, Red and Blue (the Game Boy Gen 1 games) from pret's decomps:

    scripts/gen_gen1_guide.py            ($DECOMPS defaults to ~/Projects/tests/my-rom-hacks/build)

$DECOMPS/pokeyellow (pokeyellow.gbc + pokeyellow.sym) and $DECOMPS/pokered (pokered.gbc / pokeblue.gbc + their .sym) are
the built, byte-identical-to-retail decomps (each ROM's SHA-1 is checked against the tree's roms.sha1, and Yellow's /
Red's / Blue's against the retail hashes). The tables are read out of each built ROM through its .sym (bank:address
labels, file offset = bank * 0x4000 + (address - 0x4000)), so what differs between the versions (Red's and Blue's
exclusive wild Pokémon, Yellow's rod and trade data) comes out right; the source gives what the bytes mean (constants:
species / item / move / map ids, event flags, toggleable objects, trainer classes) and the map scripts' gifts.

Writes app/src/main/kotlin/com/pokedaisy/app/companion/data/GuideGen1Gen.kt: per build a prefix, G1Y_ (Yellow), G1R_
(Red) and G1B_ (Blue), with the RAM it reads and three raw tables (species are National Dex numbers, items the bag's
own item ids - TMs / HMs 0xC9+ / 0xC4+ -, moves the stored ids).

Companion flag ids (all decimal in the tables):
  event flag n (wEventFlags bit n) = n
  toggleable ("missable") object n (wToggleableObjectFlags bit n; set = hidden = picked up / gone) = 0x10000 + n
  hidden item n (wObtainedHiddenItemsFlags bit n, n = the index in HiddenItemCoords) = 0x20000 + n
  in-game trade n (wCompletedInGameTradeFlags bit n, n = the TradeMons index) = 0x30000 + n
  badge n (wObtainedBadges bit n, 0 = BOULDER .. 7 = EARTH) = 0x40000 + n
  var 0x10000 = the byte the champion's room script picks the rival's team by (G1x_TEAM_VAR), a vars condition is
  written `0x10000=v` (v decimal).

G1x_WILD_RAW, one line each:
  G|map|rate|lv:sp lv:sp ...      grass / cave: the map's encounter rate (of 256) and its 10 slots in slot order
                                 (the slots' chances are WildMonEncounterSlotChances: 51 51 39 25 25 25 13 13 11 3 of 256)
  W|map|rate|lv:sp ...           surfing, the same shape (only maps with any)
  S|map|odds:sp:lv odds:sp:lv    SUPER ROD on that map; odds = percent of a cast, rounded to a whole number (the cast
                                 has a 50% chance of nothing; Red / Blue: a group of 2 / 3 / 4 fish, each 25 / 16.7 / 12.5%
                                 of a cast; Yellow: a per-map table of 4 fish at 102 / 76 / 51 / 27 of 256 of the bites
                                 = 19.9 / 14.8 / 10.0 / 5.3% of a cast); duplicates of one species + level merged
  OLD|odds:sp:lv                 the OLD ROD, the same on every map (always a bite: 100)
  GOOD|odds:sp:lv ...            the GOOD ROD, the same on every map (50% of a cast nothing, 25 + 25)
G1x_BOSSES_RAW, one line each: KEY|TITLE|WHERE|DONE|COND|lv sp item m,m,m,m;lv sp item m,m,m,m;...
  DONE = flag ids joined by `/` (any set = beaten), COND = "" or `0x10000=v`; the champion has a line per team (same KEY).
  Moves are worked out the way ReadTrainer / AddPartyMon do (base moves, then WriteMonMoves' level-up learnset, then the
  class's special moves: Yellow's SpecialTrainerMoves, Red / Blue's gym LoneMoves + the Elite Four's TeamMoves + the
  champion's own).
G1x_AREAS_RAW, one line each: map|KIND|id|flag|qty|wants|where (GuideAreas.kt; map = wCurMap id).
  ITEM (item ball: item id, its toggleable object), HIDDEN (hidden item: item id, 0x20000 + HiddenItemCoords index),
  GIFT / KEY (an NPC hands the item over; KEY = a key item or HM: item id, the EVENT_GOT_* flag), MON (a gift Pokémon:
  species, the event it sets, qty 1), TRADE (id = the species you get, wants = the species they take, 0x30000 + trade id).

The League. The Elite Four's room flags (EVENT_BEAT_*_ROOM_TRAINER_0) are reset by IndigoPlateauLobby on every entry
once a run was started (ResetEventRange INDIGO_PLATEAU_EVENTS_START .. EVENT_LANCES_ROOM_LOCK_DOOR) and ALL of the Indigo
Plateau's events (the champion's EVENT_BEAT_CHAMPION_RIVAL too) by HallOfFameResetEventsAndSaveScript. The one thing the
Hall of Fame leaves is Oak hiding the Cerulean Cave guard (TOGGLE_CERULEAN_CAVE_GUY, a toggleable object shown from the
start of the game): its flag, set once the League is won, is the second DONE flag of the four and the champion. (The
game also sets BIT_UNUSED_BEAT_ELITE_4 in wElite4Flags, a status byte, not an event flag; the title screen clears it.)

The champion's team: ChampionsRoomRivalReadyToBattleScript picks wTrainerNo from wRivalStarter (G1x_TEAM_VAR). Yellow:
wRivalStarter is 1 / 2 / 3 = the Eevee's evolution (Jolteon after Route 22 won, Flareon, Vaporeon) and wTrainerNo is
that number. Red / Blue: wRivalStarter holds the rival's starter species (internal id): SQUIRTLE (177) = team 1
(Blastoise), BULBASAUR (153) = team 2 (Venusaur), CHARMANDER (176) = team 3 (Charizard).

Gifts not in the data: a gift the game remembers in a status bit rather than an event flag (the three rods, Lapras)
has flag 0; the revived fossil Pokémon have none either (the lab resets its events); balls that start hidden until a
trainer drops them (the Silph Scope, the Lift Key) have flag 0 since their hide bit is set from the start. A hidden item
on a map the game never loads (UNUSED_MAP_6F) is left out.
"""
import hashlib
import os
import re
import shutil
import struct
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_gen1_tables as g1  # noqa: E402  (CHARS)

OUT = os.path.join(g1.OUT_DIR, "GuideGen1Gen.kt")
DECOMPS = os.environ.get("DECOMPS") or os.path.expanduser("~/Projects/tests/my-rom-hacks/build")

BUILDS = [
    dict(p="G1Y_", name="Yellow", tree="pokeyellow", base="pokeyellow", sha1="cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1",
         badges=0xD355, curmap=0xD35D, yellow=True),
    dict(p="G1R_", name="Red", tree="pokered", base="pokered", sha1="ea9bcae617fdf159b045185467ae58b2e4a48b9a",
         badges=0xD356, curmap=0xD35E, yellow=False),
    dict(p="G1B_", name="Blue", tree="pokered", base="pokeblue", sha1="d7037c83e1ae5b39bde3c30787637ba1d4c48ce2",
         badges=0xD356, curmap=0xD35E, yellow=False),
    # Yellow Legacy v1.0.10: cRz-Shadows/Pokemon_Yellow_Legacy at tag V1.0.10, its `make` = the patched cart byte for byte.
    dict(p="G1L_", name="Yellow Legacy", tree="pokeyellowlegacy", base="pokeyellow", sha1="2ac87de42afc70ef6016805acb3b206644417f97",
         badges=0xD3A3, curmap=0xD3AB, yellow=True, legacy=True),
]

# Yellow Legacy forked pokeyellow before pret's renames: what its source calls these, as the readers below expect them.
LEGACY_RENAMES = [
    (r"\bHS_", "TOGGLE_"), (r"\bwMissableObject", "wToggleableObject"), (r"\bMissableObjects\b", "ToggleableObjectStates"),
    (r"\bHIDE\b", "OFF"), (r"\bSHOW\b", "ON"),
    (r"(?m)^\s*db (\w+,\s+\w+,\s+TRADE_DIALOGSET_)", r"\tnpctrade \1"),
]
LEGACY_SYMBOLS = {"ToggleableObjectStates": "MissableObjects", "wToggleableObjectFlags": "wMissableObjectFlags",
                  "wToggleableObjectFlagsEnd": "wMissableObjectFlagsEnd"}


def normalized_tree(tree):
    """A copy of a fork's text sources (constants, data, scripts) under pret's current names; the ROM and .sym stay [tree]'s."""
    out = tempfile.mkdtemp(prefix="gen1-guide-")
    for top in ("constants", "data", "scripts"):
        for root, _, files in os.walk(os.path.join(tree, top)):
            for fn in files:
                if not fn.endswith(".asm"):
                    continue
                src = os.path.join(root, fn)
                dst = os.path.join(out, os.path.relpath(src, tree))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                text = open(src, encoding="utf-8", errors="replace").read()
                for a, b in LEGACY_RENAMES:
                    text = re.sub(a, b, text)
                open(dst, "w", encoding="utf-8").write(text)
    shutil.move(os.path.join(out, "constants", "hide_show_constants.asm"), os.path.join(out, "constants", "toggle_constants.asm"))
    # wram.asm's `flag_array 112`; pret names it now.
    with open(os.path.join(out, "constants", "item_constants.asm"), "a", encoding="utf-8") as f:
        f.write("\nDEF MAX_HIDDEN_ITEMS EQU 112\n")
    # hidden_objects.asm's `hidden_object x, y, ITEM, HiddenItems` under a label its map table names, as hidden_events.asm has them.
    text = open(os.path.join(out, "data", "events", "hidden_objects.asm"), encoding="utf-8").read()
    maps = {label: const for const, label in re.findall(r"dbw\s+(\w+),\s+(\w+)", text)}
    rows = []
    for line in text.split("\n"):
        if m := re.match(r"(\w+):\s*$", line):
            if m.group(1) in maps:
                rows.append(f"\thidden_events_for {maps[m.group(1)]}")
        elif m := re.match(r"\s*hidden_object\s+(\d+),\s*(\d+),\s*(\w+),\s*HiddenItems", line):
            rows.append(f"\thidden_event {m.group(1)}, {m.group(2)}, HiddenItems, {m.group(3)}")
    open(os.path.join(out, "data", "events", "hidden_events.asm"), "w", encoding="utf-8").write("\n".join(rows) + "\n")
    return out

EVENT, TOGGLE, HIDDEN, TRADE, BADGE = 0, 0x10000, 0x20000, 0x30000, 0x40000
TEAM_VAR_ID = 0x10000

# Gym leaders: (key, title, map constant, trainer class, wTrainerNo, badge constant, gym number in LoneMoves)
GYMS = [
    ("BROCK", "LEADER BROCK", "PEWTER_GYM", "BROCK", "BOULDERBADGE"),
    ("MISTY", "LEADER MISTY", "CERULEAN_GYM", "MISTY", "CASCADEBADGE"),
    ("LT. SURGE", "LEADER LT. SURGE", "VERMILION_GYM", "LT_SURGE", "THUNDERBADGE"),
    ("ERIKA", "LEADER ERIKA", "CELADON_GYM", "ERIKA", "RAINBOWBADGE"),
    ("KOGA", "LEADER KOGA", "FUCHSIA_GYM", "KOGA", "SOULBADGE"),
    ("SABRINA", "LEADER SABRINA", "SAFFRON_GYM", "SABRINA", "MARSHBADGE"),
    ("BLAINE", "LEADER BLAINE", "CINNABAR_GYM", "BLAINE", "VOLCANOBADGE"),
    ("GIOVANNI", "LEADER GIOVANNI", "VIRIDIAN_GYM", "GIOVANNI", "EARTHBADGE"),
]
GYM_WHERE = {"PEWTER_GYM": "PEWTER CITY GYM", "CERULEAN_GYM": "CERULEAN CITY GYM", "VERMILION_GYM": "VERMILION CITY GYM",
             "CELADON_GYM": "CELADON CITY GYM", "FUCHSIA_GYM": "FUCHSIA CITY GYM", "SAFFRON_GYM": "SAFFRON CITY GYM",
             "CINNABAR_GYM": "CINNABAR ISLAND GYM", "VIRIDIAN_GYM": "VIRIDIAN CITY GYM"}
ELITE4 = [
    ("LORELEI", "ELITE FOUR LORELEI", "LORELEIS_ROOM", "LORELEI", "EVENT_BEAT_LORELEIS_ROOM_TRAINER_0"),
    ("BRUNO", "ELITE FOUR BRUNO", "BRUNOS_ROOM", "BRUNO", "EVENT_BEAT_BRUNOS_ROOM_TRAINER_0"),
    ("AGATHA", "ELITE FOUR AGATHA", "AGATHAS_ROOM", "AGATHA", "EVENT_BEAT_AGATHAS_ROOM_TRAINER_0"),
    ("LANCE", "ELITE FOUR LANCE", "LANCES_ROOM", "LANCE", "EVENT_BEAT_LANCES_ROOM_TRAINER_0"),
]


# ---------------------------------------------------------------------------------------------------------------------
# The built ROM + its symbols

class Build:
    def __init__(self, b):
        self.__dict__.update(b)
        d = os.path.join(DECOMPS, self.tree)
        self.dir = d
        self.rom_path = os.path.join(d, self.base + ".gbc")
        self.data = open(self.rom_path, "rb").read()
        assert hashlib.sha1(self.data).hexdigest() == self.sha1, f"{self.rom_path} isn't retail Pokémon {self.name}"
        if not b.get("legacy"):
            listed = {}
            for line in open(os.path.join(d, "roms.sha1")):
                sha, fn = line.split()
                listed[fn.lstrip("*")] = sha
            assert listed[self.base + ".gbc"] == self.sha1, f"{self.base}.gbc isn't what roms.sha1 lists"
        self.sym = {}
        for line in open(os.path.join(d, self.base + ".sym")):
            m = re.match(r"([0-9a-f]{2}):([0-9a-f]{4}) (\S+)$", line.strip())
            if m and "." not in m.group(3):
                self.sym[m.group(3)] = (int(m.group(1), 16), int(m.group(2), 16))
        if b.get("legacy"):
            self.sym.update({new: self.sym[old] for new, old in LEGACY_SYMBOLS.items()})
            d = self.dir = normalized_tree(d)
        self.c = Consts(d)
        self.headers = map_headers(d)

    # -- bytes
    @staticmethod
    def off(bank, addr):
        return addr if bank == 0 else bank * 0x4000 + (addr - 0x4000)

    def at(self, label, add=0):
        b, a = self.sym[label]
        return b, a + add

    def u8(self, bank, addr):
        return self.data[self.off(bank, addr)]

    def u16(self, bank, addr):
        return struct.unpack_from("<H", self.data, self.off(bank, addr))[0]

    def bytes(self, bank, addr, n):
        o = self.off(bank, addr)
        return self.data[o:o + n]

    def ram(self, label):
        b, a = self.sym[label]
        assert b == 0 and 0xC000 <= a < 0xE000, label
        return a

    # -- species
    def dex_of(self, internal):
        b, a = self.at("PokedexOrder")
        assert 1 <= internal <= 190, internal
        d = self.u8(b, a + internal - 1)
        assert 1 <= d <= 151, f"internal species {internal} has no Dex number"
        return d

    def text(self, bank, addr, maxlen=40):
        out = []
        for byte in self.bytes(bank, addr, maxlen):
            if byte == 0x50:
                return "".join(out)
            out.append(g1.CHARS[byte])
        raise ValueError("unterminated text")

    def species_name(self, internal):
        b, a = self.at("MonsterNames")
        return self.text(b, a + (internal - 1) * 10, 10) if self.u8(b, a + (internal - 1) * 10) != 0x50 else "?"


# ---------------------------------------------------------------------------------------------------------------------
# Constants, evaluated the way rgbds counts them (only what these files use).

class Consts:
    def __init__(self, tree):
        self.dir = tree
        self.v = {}
        self.toggle_maps = {}      # map name -> first toggle id (toggle_consts_for)
        for rel in ("map_constants", "pokemon_constants", "item_constants", "move_constants", "trainer_constants",
                    "event_constants", "toggle_constants", "script_constants", "type_constants"):
            self.load(os.path.join(tree, "constants", rel + ".asm"))
        self.load_trainer_ids()

    def eval(self, expr):
        e = expr.strip()
        e = re.sub(r"\$([0-9A-Fa-f]+)", r"0x\1", e)
        e = re.sub(r"%([01]+)", r"0b\1", e)
        e = e.replace("/", "//")

        def name(m):
            n = m.group(0)
            if re.match(r"0[xb]", n):
                return n
            if n not in self.v:
                raise KeyError(n)
            return str(self.v[n])
        e = re.sub(r"\b[A-Za-z_][A-Za-z0-9_]*\b", name, e)
        return int(eval(e, {"__builtins__": {}}))

    def load(self, path):
        st = {"value": 0, "inc": 1}
        in_macro = False
        for raw in open(path, encoding="utf-8"):
            line = raw.split(";")[0].strip()
            if not line:
                continue
            if re.match(r"MACRO\b", line):
                in_macro = True
                continue
            if re.match(r"ENDM\b", line):
                in_macro = False
                continue
            if in_macro:
                continue
            self.v["const_value"] = st["value"]
            if m := re.match(r"const_def(?:\s+([^,]+))?(?:,\s*(.+))?$", line):
                st["value"] = self.eval(m.group(1)) if m.group(1) else 0
                st["inc"] = self.eval(m.group(2)) if m.group(2) else 1
            elif m := re.match(r"(?:const|map_const|trainer_const|add_hm|add_tm)\s+(\w+)", line):
                kind = line.split()[0]
                nm = m.group(1)
                if kind == "add_hm":
                    nm = "HM_" + nm
                elif kind == "add_tm":
                    nm = "TM_" + nm
                self.v[nm] = st["value"]
                if kind == "trainer_const":
                    self.v["OPP_" + nm] = 200 + st["value"]
                st["value"] += st["inc"]
            elif m := re.match(r"const_skip(?:\s+(.+))?$", line):
                st["value"] += (self.eval(m.group(1)) if m.group(1) else 1) * st["inc"]
            elif m := re.match(r"const_next\s+(.+)$", line):
                st["value"] = self.eval(m.group(1))
            elif m := re.match(r"toggle_consts_for\s+(\w+)", line):
                self.toggle_maps[m.group(1)] = st["value"]
            elif m := re.match(r"(?:DEF|REDEF)\s+(\w+)\s*(?:EQU|=)\s*(.+)$", line):
                try:
                    self.v[m.group(1)] = self.eval(m.group(2))
                except (KeyError, SyntaxError, NameError):
                    pass

    def load_trainer_ids(self):
        pass

    def __getitem__(self, k):
        return self.v[k]

    def get(self, k, d=None):
        return self.v.get(k, d)


# ---------------------------------------------------------------------------------------------------------------------
# Source readers (maps, scripts)

def strip(line):
    return line.split(";")[0].rstrip()


def map_headers(tree):
    """Camel label (the maps/scripts file name) -> map constant."""
    out = {}
    d = os.path.join(tree, "data", "maps", "headers")
    for fn in os.listdir(d):
        for line in open(os.path.join(d, fn)):
            if m := re.match(r"\s*map_header\s+(\w+),\s*(\w+),", line):
                out[m.group(1)] = m.group(2)
    return out


def object_events(tree, label):
    """The map's object_event lines as argument lists, in order (the sprite index is the position, 1-based)."""
    path = os.path.join(tree, "data", "maps", "objects", label + ".asm")
    out = []
    for raw in open(path):
        line = strip(raw).strip()
        if m := re.match(r"object_event\s+(.*)$", line):
            out.append([a.strip() for a in m.group(1).split(",")])
    return out


def humanize(const):
    """A map constant as the player reads it: REDS_HOUSE_2F -> RED'S HOUSE 2F, MT_MOON_B1F -> MT. MOON B1F."""
    poss = {"REDS", "BLUES", "OAKS", "BILLS", "LANCES", "BRUNOS", "AGATHAS", "LORELEIS", "COPYCATS", "WARDENS", "MELANIES",
            "CHAMPIONS", "FUJIS", "PSYCHICS", "DAISYS", "SPEARS"}
    toks = const.split("_")
    out = []
    i = 0
    while i < len(toks):
        t = toks[i]
        if t == "SS":
            out.append("S.S.")
            i += 1
            continue
        if t == "S" and i + 1 < len(toks) and toks[i + 1] == "S":
            out.append("S.S.")
            i += 2
            continue
        if t in ("MT", "MR"):
            out.append(t + ".")
        elif t in poss:
            out.append(t[:-1] + "'S")
        elif t == "POKECENTER":
            out.append("POKéMON CENTER")
        elif t == "POKEMON":
            out.append("POKéMON")
        elif t == "LT":
            out.append("LT.")
        else:
            out.append(t)
        i += 1
    return " ".join(out)


# ---------------------------------------------------------------------------------------------------------------------
# Wild Pokémon

def wild_tables(B):
    """(grass, water): map id -> (rate, [(level, dex)] x 10); fishing: (old, good, super)."""
    nmaps = B.c["NUM_MAPS"]
    b, a = B.at("WildDataPointers")
    grass, water = {}, {}
    for m in range(nmaps):
        p = B.u16(b, a + 2 * m)
        rate = B.u8(b, p)
        o = p + 1
        if rate:
            grass[m] = (rate, [(B.u8(b, o + 2 * i), B.dex_of(B.u8(b, o + 2 * i + 1))) for i in range(10)])
            o += 20
        wrate = B.u8(b, o)
        if wrate:
            water[m] = (wrate, [(B.u8(b, o + 1 + 2 * i), B.dex_of(B.u8(b, o + 2 + 2 * i))) for i in range(10)])
    return grass, water


def pct(x):
    return int(x + 0.5)


def merged(entries):
    """[(odds, dex, lv)] -> merged by species + level, keeping first-seen order."""
    out = {}
    for odds, sp, lv in entries:
        out[(sp, lv)] = out.get((sp, lv), 0) + odds
    return [(o, sp, lv) for (sp, lv), o in out.items()]


def fishing(B):
    """(old, good, super_by_map): lists of (odds percent, dex, level). Reads the rods' own routines from the ROM."""
    # OLD ROD: ItemUseOldRod = call FishingInit / jp c, ItemUseNotTime / lb bc, 5, MAGIKARP / ld a, 1
    if "OldRodMons" in B.sym:
        # Yellow Legacy: the OLD ROD rolls a table like the GOOD ROD's.
        ob, oa = B.at("OldRodMons")
        old = [(25, B.dex_of(B.u8(ob, oa + 2 * i + 1)), B.u8(ob, oa + 2 * i)) for i in range(2)]
    else:
        b, a = B.at("ItemUseOldRod")
        code = B.bytes(b, a, 10)
        assert code[0] == 0xCD and code[3] == 0xDA and code[6] == 0x01 and code[9] == 0x3E, "ItemUseOldRod changed"
        old = [(100, B.dex_of(code[7]), code[8])]
        assert old[0][1] == 129 and old[0][2] == 5   # MAGIKARP lv 5
    # GOOD ROD: srl a (bit 0 set = no bite), then a 2-bit number below 2 picks a row of GoodRodMons (level, species)
    gb, ga = B.at("GoodRodMons")
    good = [(25, B.dex_of(B.u8(gb, ga + 2 * i + 1)), B.u8(gb, ga + 2 * i)) for i in range(2)]
    sup = {}
    if B.yellow:
        # SuperRodFishingSlots: map, then 4 x (species, level); ReadSuperRodData: a bite is a 50% chance, then the
        # cumulative thresholds 0x66 / 0xB2 / 0xE5 / 0x100 of a random byte pick the slot
        sb, sa = B.at("SuperRodFishingSlots")
        thr = [0x66, 0xB2, 0xE5, 0x100]
        # (the thresholds themselves are code: ReadSuperRodData cp $66 / $b2 / $e5, checked below)
        rb, ra = B.at("ReadSuperRodData") if "ReadSuperRodData" in B.sym else (None, None)
        if rb is not None:
            blob = B.bytes(rb, ra, 0x80)
            for t in (0x66, 0xB2, 0xE5):
                assert bytes([0xFE, t]) in blob, "ReadSuperRodData thresholds changed"
        o = 0
        while B.u8(sb, sa + o) != 0xFF:
            m = B.u8(sb, sa + o)
            ents = []
            prev = 0
            for i in range(4):
                sp, lv = B.u8(sb, sa + o + 1 + 2 * i), B.u8(sb, sa + o + 2 + 2 * i)
                share = (thr[i] - prev) / 256
                prev = thr[i]
                ents.append((pct(50 * share), B.dex_of(sp), lv))
            sup[m] = merged(ents)
            o += 9
    else:
        tb, ta = B.at("SuperRodData")
        o = 0
        while B.u8(tb, ta + o) != 0xFF:
            m, p = B.u8(tb, ta + o), B.u16(tb, ta + o + 1)
            n = B.u8(tb, p)
            assert n in (2, 3, 4)
            ents = [(pct(50 / n), B.dex_of(B.u8(tb, p + 2 + 2 * i)), B.u8(tb, p + 1 + 2 * i)) for i in range(n)]
            sup[m] = merged(ents)
            o += 3
    return old, good, sup


def fmt_fish(ents):
    return " ".join(f"{o}:{sp}:{lv}" for o, sp, lv in ents)


def wild_lines(B):
    grass, water = wild_tables(B)
    old, good, sup = fishing(B)
    lines = []
    for m in sorted(grass):
        r, sl = grass[m]
        lines.append(f"G|{m}|{r}|" + " ".join(f"{lv}:{sp}" for lv, sp in sl))
    for m in sorted(water):
        r, sl = water[m]
        lines.append(f"W|{m}|{r}|" + " ".join(f"{lv}:{sp}" for lv, sp in sl))
    for m in sorted(sup):
        lines.append(f"S|{m}|" + fmt_fish(sup[m]))
    lines.append("OLD|" + fmt_fish(old))
    lines.append("GOOD|" + fmt_fish(good))
    # every id a real map (one with a header and not an UNUSED_MAP_xx)
    real = {B.c[v] for v in B.headers.values() if not v.startswith("UNUSED")}
    for m in list(grass) + list(water) + list(sup):
        assert m in real, f"map {m:#x} isn't a real map"
    return lines, (grass, water, sup)


# ---------------------------------------------------------------------------------------------------------------------
# Bosses

class Moves:
    """A species' moves at a level, the way AddPartyMon + WriteMonMoves leave them."""

    def __init__(self, B):
        self.B = B

    def base(self, internal):
        B = self.B
        dex = B.dex_of(internal)
        assert dex != 151, "MEW's base stats sit apart"
        b, a = B.at("BaseStats")
        e = a + (dex - 1) * 28
        assert B.u8(b, e) == dex
        return list(B.bytes(b, e + 15, 4))

    def learnset(self, internal):
        B = self.B
        b, a = B.at("EvosMovesPointerTable")
        p = B.u16(b, a + 2 * (internal - 1))
        while B.u8(b, p) != 0:          # the evolutions: skipped to their 0, as WriteMonMoves does
            p += 1
        p += 1
        out = []
        while B.u8(b, p) != 0:
            out.append((B.u8(b, p), B.u8(b, p + 1)))
            p += 2
        return out

    def at_level(self, internal, level):
        mv = self.base(internal)
        for lv, move in self.learnset(internal):
            if level < lv:
                break
            if move in mv:
                continue
            if 0 in mv:
                mv[mv.index(0)] = move
            else:
                mv = mv[1:] + [move]
        return mv


def trainer_party(B, cls, no):
    """[(level, internal species)] of trainer class `cls` (1-based), team `no` (1-based), from TrainerDataPointers."""
    b, a = B.at("TrainerDataPointers")
    p = B.u16(b, a + 2 * (cls - 1))
    for _ in range(no - 1):
        while B.u8(b, p) != 0:
            p += 1
        p += 1
    out = []
    if B.u8(b, p) == 0xFF:
        p += 1
        while B.u8(b, p) != 0:
            out.append((B.u8(b, p), B.u8(b, p + 1)))
            p += 2
    else:
        lv = B.u8(b, p)
        p += 1
        while B.u8(b, p) != 0:
            out.append((lv, B.u8(b, p)))
            p += 1
    return out


def gym_numbers(B):
    """Red / Blue: each gym script writes its number to wGymLeaderNo (= wLoneAttackNo, the LoneMoves row)."""
    out = {}
    for key, title, mp, cls, badge in GYMS:
        label = mapfile(B, mp)
        txt = open(os.path.join(B.dir, "scripts", label + ".asm")).read()
        m = re.search(r"ld a, \$?(\d+)\n\s*ld \[wGymLeaderNo\], a", txt)
        assert m, f"{label}: no wGymLeaderNo"
        out[cls] = int(m.group(1))
    assert sorted(out.values()) == list(range(1, 9)), out
    return out


def mapfile(B, const):
    for k, v in B.headers.items():
        if v == const:
            return k
    raise KeyError(const)


def special_yellow(B):
    """Yellow's SpecialTrainerMoves: {(class, no): [(mon, slot, move)]}."""
    b, a = B.at("SpecialTrainerMoves")
    out = {}
    o = 0
    while B.u8(b, a + o) != 0xFF:
        cls, no = B.u8(b, a + o), B.u8(b, a + o + 1)
        o += 2
        rows = []
        while B.u8(b, a + o) != 0:
            rows.append((B.u8(b, a + o), B.u8(b, a + o + 1), B.u8(b, a + o + 2)))
            o += 3
        o += 1
        out[(cls, no)] = rows
    return out


def boss_team(B, mv, cls_name, no, var=None):
    cls = B.c[cls_name]
    party = trainer_party(B, cls, no)
    assert party, (cls_name, no)
    moves = [mv.at_level(sp, lv) for lv, sp in party]
    if B.yellow:
        for mon, slot, move in B.special.get((cls, no), []):
            moves[mon - 1][slot - 1] = move
    else:
        if cls_name == "RIVAL3":
            moves[0][2] = B.c["SKY_ATTACK"]     # PIDGEOT
            moves[5][2] = {B.c["STARTER3"]: B.c["MEGA_DRAIN"], B.c["STARTER1"]: B.c["FIRE_BLAST"]}.get(var, B.c["BLIZZARD"])
        elif cls_name in B.gymnum:
            lb, la = B.at("LoneMoves")
            n = B.gymnum[cls_name]
            idx, move = B.u8(lb, la + 2 * (n - 1)), B.u8(lb, la + 2 * (n - 1) + 1)
            moves[idx][2] = move
        else:
            tb, ta = B.at("TeamMoves")
            o = 0
            while B.u8(tb, ta + o) != 0xFF:
                if B.u8(tb, ta + o) == cls:
                    moves[4][2] = B.u8(tb, ta + o + 1)
                o += 2
    team = []
    for (lv, sp), mvs in zip(party, moves):
        team.append(f"{lv} {B.dex_of(sp)} 0 " + ",".join(str(m) for m in mvs if m))
    return ";".join(team), party, moves


def boss_lines(B):
    mv = Moves(B)
    c = B.c
    if B.yellow:
        B.special = special_yellow(B)
    else:
        B.gymnum = gym_numbers(B)
    # the League-won marker that survives: Oak hides the Cerulean Cave guard (a toggleable object) as the Hall of Fame
    # opens, and HallOfFameResetEventsAndSaveScript then wipes every Indigo Plateau event flag
    cave = TOGGLE + c["TOGGLE_CERULEAN_CAVE_GUY"]
    st = toggle_states(B)
    assert [k for k, v in st.items() if v[0] == c["TOGGLE_CERULEAN_CAVE_GUY"]][0][0] == c["CERULEAN_CITY"]
    assert [v[1] for v in st.values()][c["TOGGLE_CERULEAN_CAVE_GUY"]] == c["ON"]
    hof = open(os.path.join(B.dir, "scripts", "HallOfFame.asm")).read()
    assert "ld a, TOGGLE_CERULEAN_CAVE_GUY\n\tld [wToggleableObjectIndex], a\n\tpredef HideObject" in hof
    assert "ResetEventRange INDIGO_PLATEAU_EVENTS_START, INDIGO_PLATEAU_EVENTS_END" in hof
    lines = []
    for key, title, mp, cls, badge in GYMS:
        no = 3 if cls == "GIOVANNI" else 1
        # the leader's object event names class + number
        label = mapfile(B, mp)
        ev = [e for e in object_events(B.dir, label) if len(e) > 2 and e[-2] == f"OPP_{cls}"]
        assert ev and ev[0][-1] == str(no), (mp, ev)
        team, _, _ = boss_team(B, mv, cls, no)
        where = GYM_WHERE[mp]
        lines.append(f"{key}|{title}|{where}|{BADGE + (c[badge] - c['BOULDERBADGE'])}||{team}")
    for key, title, mp, cls, flag in ELITE4:
        label = mapfile(B, mp)
        ev = [e for e in object_events(B.dir, label) if len(e) > 2 and e[-2] == f"OPP_{cls}"]
        assert ev and ev[0][-1] == "1", (mp, ev)
        team, _, _ = boss_team(B, mv, cls, 1)
        lines.append(f"{key}|{title}|INDIGO PLATEAU|{EVENT + c[flag]}/{cave}||{team}")
    # the champion: wTrainerNo is picked from wRivalStarter (Yellow: its value 1..3 directly; Red / Blue: STARTER2 -> 1,
    # STARTER3 -> 2, anything else -> 3)
    done = f"{EVENT + c['EVENT_BEAT_CHAMPION_RIVAL']}/{cave}"
    if B.yellow:
        variants = [(1, c["RIVAL_STARTER_JOLTEON"]), (2, c["RIVAL_STARTER_FLAREON"]), (3, c["RIVAL_STARTER_VAPOREON"])]
        assert [v for _, v in variants] == [1, 2, 3]
        txt = open(os.path.join(B.dir, "scripts", "ChampionsRoom.asm")).read()
        assert re.search(r"ld a, \[wRivalStarter\]\n\s*add \$0 ; Wow GameFreak\n\s*ld \[wTrainerNo\], a", txt)
        for no, val in variants:
            team, _, _ = boss_team(B, mv, "RIVAL3", no, val)
            lines.append(f"CHAMPION|CHAMPION, YOUR RIVAL|INDIGO PLATEAU|{done}|0x10000={val}|{team}")
    else:
        # STARTER2 (SQUIRTLE) -> team 1, STARTER3 (BULBASAUR) -> team 2, else (CHARMANDER) -> team 3
        txt = open(os.path.join(B.dir, "scripts", "ChampionsRoom.asm")).read()
        assert re.search(r"cp STARTER2\n\s*jr nz, \.NotStarter2\n\s*ld a, \$1", txt)
        assert re.search(r"cp STARTER3\n\s*jr nz, \.NotStarter3\n\s*ld a, \$2", txt)
        for no, val in ((1, c["STARTER2"]), (2, c["STARTER3"]), (3, c["STARTER1"])):
            team, _, _ = boss_team(B, mv, "RIVAL3", no, val)
            lines.append(f"CHAMPION|CHAMPION, YOUR RIVAL|INDIGO PLATEAU|{done}|0x10000={val}|{team}")
    return lines


# ---------------------------------------------------------------------------------------------------------------------
# Areas

def toggle_states(B):
    """ToggleableObjectStates from the ROM: {(map, object index): (toggle id, initial state byte)}."""
    b, a = B.at("ToggleableObjectStates")
    out = {}
    n = 0
    while B.u8(b, a + 3 * n) != 0xFF:
        out[(B.u8(b, a + 3 * n), B.u8(b, a + 3 * n + 1))] = (n, B.u8(b, a + 3 * n + 2))
        n += 1
    return out


def hidden_item_indexes(B):
    """HiddenItemCoords from the ROM: [(map, y, x)]; the index is the wObtainedHiddenItemsFlags bit."""
    b, a = B.at("HiddenItemCoords")
    out = []
    while B.u8(b, a + 3 * len(out)) != 0xFF:
        o = 3 * len(out)
        out.append((B.u8(b, a + o), B.u8(b, a + o + 1), B.u8(b, a + o + 2)))
    return out


def hidden_events(tree, c):
    """data/events/hidden_events.asm: [(map const, x, y, item const)] for the HiddenItems rows."""
    out = []
    cur = None
    for raw in open(os.path.join(tree, "data", "events", "hidden_events.asm")):
        line = strip(raw).strip()
        if m := re.match(r"hidden_events_for\s+(\w+)", line):
            cur = m.group(1)
        elif m := re.match(r"hidden_event\s+(\d+),\s*(\d+),\s*HiddenItems,\s*(\w+)", line):
            out.append((cur, int(m.group(1)), int(m.group(2)), m.group(3)))
    return out


def key_item(B, item):
    if B.c["HM_CUT"] <= item <= B.c["HM_FLASH"]:
        return True
    if item >= B.c["NUM_ITEMS"] + 1:
        return False
    b, a = B.at("KeyItemFlags")
    return bool(B.u8(b, a + (item - 1) // 8) >> ((item - 1) % 8) & 1)


def script_labels(B):
    """script file base name -> map constant for every scripts/<Label>.asm and <Label>_2.asm."""
    out = {}
    for fn in os.listdir(os.path.join(B.dir, "scripts")):
        lab = fn[:-4]
        base = lab[:-2] if lab.endswith("_2") else lab
        if base in B.headers:
            out[lab] = B.headers[base]
    return out


def clean(s):
    return strip(s).strip()


def find_flag(B, lines, i, c):
    """What marks a gift at lines[i] as received: ("event", name) for the SetEvent after it (before the next global
    label), else a CheckAndSetEvent / EVENT_GOT_* CheckEvent guarding it above; ("toggle", name) when the script
    hides an object right after (the Eevee ball); None when the game keeps it in a status bit (the rods, Lapras)."""
    for j in range(i + 1, min(len(lines), i + 24)):
        t = clean(lines[j])
        if re.match(r"^[A-Za-z_]\w*::?$", t) or t.startswith("text_"):
            break
        if m := re.match(r"SetEvents?\s+(EVENT_\w+)", t):
            return ("event", m.group(1))
    for j in range(i - 1, max(-1, i - 40), -1):
        t = clean(lines[j])
        if re.match(r"^[A-Za-z_]\w*::?$", t):
            break
        if m := re.match(r"CheckAndSetEvent\s+(EVENT_\w+)", t):
            return ("event", m.group(1))
        if m := re.match(r"CheckEvent\w*\s+(EVENT_GOT_\w+)", t):
            return ("event", m.group(1))
    for j in range(i + 1, min(len(lines), i + 8)):
        t = clean(lines[j])
        if re.match(r"^[A-Za-z_]\w*::?$", t):
            break
        if m := re.match(r"ld a, (TOGGLE_\w+)$", t):
            if clean(lines[j + 2]) == "predef HideObject":
                return ("toggle", m.group(1))
    return None


def gifts(B, labels):
    """[(map const, kind, id, flag name or None, qty)] from the scripts' GiveItem / GivePokemon / Oak's aides."""
    c = B.c
    out = []
    unresolved = []
    for lab, mp in sorted(labels.items()):
        lines = open(os.path.join(B.dir, "scripts", lab + ".asm")).read().split("\n")
        for i, raw in enumerate(lines):
            t = clean(raw)
            if t == "call GiveItem":
                item = qty = None
                for j in range(i - 1, max(-1, i - 6), -1):
                    if m := re.match(r"lb bc,\s*(\w+),\s*(\w+)$", clean(lines[j])):
                        item, qty = c[m.group(1)], c[m.group(2)] if not m.group(2).isdigit() else int(m.group(2))
                        break
                if item is None:
                    unresolved.append((lab, i + 1))
                    continue
                out.append((mp, "ITEM_GIFT", item, find_flag(B, lines, i, c), qty, lab))
            elif t == "ldh [hOaksAideRewardItem], a":
                m = re.match(r"ld a,\s*(\w+)$", clean(lines[i - 1]))
                assert m, (lab, i)
                out.append((mp, "ITEM_GIFT", c[m.group(1)], find_flag(B, lines, i, c), 1, lab))
    return out, unresolved


def flag_id(B, flag, mp, tog):
    """The companion flag id of find_flag's answer (0 = none)."""
    c = B.c
    if flag is None:
        return 0
    if isinstance(flag, str):
        flag = ("event", flag)
    kind, name = flag
    if kind == "event":
        return EVENT + c[name]
    t = c[name]
    states = {v[0]: (k, v[1]) for k, v in tog.items()}
    assert states[t][0][0] == c[mp] and states[t][1] == c["ON"], (name, mp)     # on this map, shown until taken
    return TOGGLE + t


def area_lines(B):
    c = B.c
    tree = B.dir
    labels = script_labels(B)
    tog = toggle_states(B)
    rows = []                          # (map id, kind, id, flag, qty, wants, where)
    notes = {"hidden_start": []}

    def add(mp, kind, ident, flag, qty, wants=0):
        mid = c[mp]
        if mp.startswith("UNUSED_MAP"):        # a map the game never loads (a hidden item left on one)
            notes.setdefault("unused", []).append((mp, kind, ident))
            return
        assert mid < c["NUM_MAPS"] and mp in B.headers.values(), mp
        where = "" if mid < B_FIRST_INDOOR else humanize(mp)
        rows.append((mid, kind, ident, flag, qty, wants, where))

    # item balls
    for label, mp in sorted(B.headers.items(), key=lambda kv: c[kv[1]]):
        path = os.path.join(tree, "data", "maps", "objects", label + ".asm")
        if not os.path.exists(path):
            continue
        for idx, ev in enumerate(object_events(tree, label), 1):
            if len(ev) == 7 and ev[2] == "SPRITE_POKE_BALL":
                item = c[ev[6]]
                t = tog.get((c[mp], idx))
                if t is None:
                    raise AssertionError(f"{mp} object {idx} ({ev[6]}) has no toggle")
                flag = TOGGLE + t[0]
                if t[1] == c["OFF"]:       # starts hidden (a trainer's drop): the flag is set from the start
                    flag = 0
                    notes["hidden_start"].append((mp, ev[6]))
                add(mp, "ITEM", item, flag, 1)

    # hidden items
    coords = hidden_item_indexes(B)
    for mp, x, y, item in hidden_events(tree, c):
        k = (c[mp], y, x)
        assert k in coords, (mp, x, y)
        add(mp, "HIDDEN", c[item], HIDDEN + coords.index(k), 1)
    assert len(coords) <= c["MAX_HIDDEN_ITEMS"]

    # gifts
    g, unresolved = gifts(B, labels)
    assert not unresolved, unresolved
    for mp, kind, item, flagname, qty, lab in g:
        if lab == "CeruleanCity":        # TM28 (the thief): CeruleanHideRocket hides him once it is handed over
            assert "ld a, TOGGLE_CERULEAN_ROCKET\n\tld [wToggleableObjectIndex], a\n\tpredef HideObject" in \
                open(os.path.join(tree, "scripts", "CeruleanCity_2.asm")).read()
            flagname = ("toggle", "TOGGLE_CERULEAN_ROCKET")
        add(mp, "KEY" if key_item(B, item) else "GIFT", item, flag_id(B, flagname, mp, tog), qty)

    # gift Pokémon (a purchase - the Magikarp seller, the Game Corner prizes - isn't one)
    mons = mon_gifts(B, labels)
    for mp, sp, flagname in mons:
        add(mp, "MON", B.dex_of(sp), flag_id(B, flagname, mp, tog), 1)

    # in-game trades
    tb, ta = B.at("TradeMons")
    ntr = c["NUM_NPC_TRADES"]
    used = {}
    for lab, mp in labels.items():
        txt = open(os.path.join(tree, "scripts", lab + ".asm")).read()
        for m in re.finditer(r"(?:ld a,|xor a ;) (TRADE_FOR_\w+)", txt):
            used[c[m.group(1)]] = mp
    for n in range(ntr):
        give, get = B.u8(tb, ta + 14 * n), B.u8(tb, ta + 14 * n + 1)
        if n in used:
            add(used[n], "TRADE", B.dex_of(get), TRADE + n, 1, B.dex_of(give))
    assert used and all(n < ntr for n in used), used
    # the rows trades.asm marks "unused" are exactly the ones no script offers
    rows_src = [l for l in open(os.path.join(tree, "data", "events", "trades.asm")) if l.strip().startswith("npctrade")]
    assert len(rows_src) == ntr
    assert {n for n, l in enumerate(rows_src) if "unused" in l} == set(range(ntr)) - set(used), used

    rows.sort(key=lambda r: (r[0], ["ITEM", "HIDDEN", "GIFT", "KEY", "MON", "TRADE"].index(r[1]), r[2], r[3]))
    return [f"{m}|{k}|{i}|{f}|{q}|{w}|{wh}" for m, k, i, f, q, w, wh in rows], rows, notes


B_FIRST_INDOOR = 0x25


def mon_gifts(B, labels):
    """[(map const, internal species, event flag name or None)]: the gift Pokémon the scripts hand over."""
    c = B.c
    out = []
    # the starter(s) at Oak's Lab
    if B.yellow:
        out.append(("OAKS_LAB", c["STARTER_PIKACHU"], "EVENT_GOT_STARTER"))
    else:
        for s in ("STARTER1", "STARTER2", "STARTER3"):
            out.append(("OAKS_LAB", c[s], "EVENT_GOT_STARTER"))
    for lab, mp in sorted(labels.items()):
        lines = open(os.path.join(B.dir, "scripts", lab + ".asm")).read().split("\n")
        for i, raw in enumerate(lines):
            if clean(raw) != "call GivePokemon":
                continue
            if lab in ("MtMoonPokecenter", "MtMoonPokecenter_2"):
                continue                                   # the Magikarp seller: a purchase
            if lab == "CinnabarLabFossilRoom":
                for mon in ("KABUTO", "OMANYTE", "AERODACTYL"):
                    out.append((mp, c[mon], None))
                continue
            sp = None
            for j in range(i - 1, max(-1, i - 22), -1):
                t = clean(lines[j])
                if m := re.match(r"lb bc,\s*(\w+),\s*\w+$", t):
                    sp = c[m.group(1)]
                    break
                m = re.match(r"ld a,\s*(\w+)$", t)
                if m and clean(lines[j + 1]) == "call DisplayPokedex":
                    sp = c[m.group(1)]
                    break
            assert sp is not None, (lab, i + 1)
            flag = None
            if lab == "FightingDojo":
                flag = "EVENT_GOT_HITMONLEE" if sp == c["HITMONLEE"] else "EVENT_GOT_HITMONCHAN"
            else:
                flag = find_flag(B, lines, i, c)
            out.append((mp, sp, flag))
    return out


# ---------------------------------------------------------------------------------------------------------------------
# Output

def chunk(name, lines, doc):
    parts, cur, size = [], [], 0
    for ln in lines:
        if size + len(ln) + 1 > 19000 and cur:
            parts.append(cur)
            cur, size = [], 0
        cur.append(ln)
        size += len(ln) + 1
    if cur:
        parts.append(cur)
    s = doc + f"internal val {name}: List<String> = listOf(\n"
    for p in parts:
        assert not any("$" in x or '"""' in x for x in p)
        s += '    """\n' + "\n".join(p) + '\n""",\n'
    return s + ")\n"


def main():
    global B_FIRST_INDOOR
    out = ["package com.pokedaisy.app.companion.data\n",
           "// GENERATED by scripts/gen_gen1_guide.py from pret's pokeyellow / pokered builds (byte-identical to the retail",
           "// Yellow, Red and Blue ROMs, read through their .sym files) - do not hand-edit.",
           "// Per build (G1Y_ Yellow, G1R_ Red, G1B_ Blue): the RAM it reads, then WILD / BOSSES / AREAS raw tables.",
           "// Species are National Dex numbers, items the bag's item ids, moves the stored move ids; flag ids: event n = n,",
           "// toggleable object n = 0x10000 + n, hidden item n = 0x20000 + n, in-game trade n = 0x30000 + n, badge n =",
           "// 0x40000 + n (all decimal in the tables); var 0x10000 = the champion team selector (G1x_TEAM_VAR).\n"]
    report = []
    for b in BUILDS:
        B = Build(b)
        B_FIRST_INDOOR = B.c["FIRST_INDOOR_MAP"]
        assert B_FIRST_INDOOR == 0x25
        c = B.c
        # RAM
        ev, mi, hi, tr = (B.ram("wEventFlags"), B.ram("wToggleableObjectFlags"), B.ram("wObtainedHiddenItemsFlags"),
                          B.ram("wCompletedInGameTradeFlags"))
        bd, cm = B.ram("wObtainedBadges"), B.ram("wCurMap")
        assert bd == B.badges and cm == B.curmap, (hex(bd), hex(cm))
        tv = B.ram("wRivalStarter")
        evb = (c["NUM_EVENTS"] + 7) // 8
        mib = (B.ram("wToggleableObjectFlagsEnd") - mi)
        hib = (c["MAX_HIDDEN_ITEMS"] + 7) // 8
        trb = 2
        assert c["NUM_NPC_TRADES"] <= trb * 8 and mib == 32
        assert ev + evb <= 0xE000

        wild, (grass, water, sup) = wild_lines(B)
        bosses = boss_lines(B)
        areas, rows, notes = area_lines(B)

        p = b["p"]
        out.append(f"// {B.name}: wCurMap 0x{cm:04X}, wObtainedBadges 0x{bd:04X}.")
        out.append(f"internal const val {p}EVENT_FLAGS = 0x{ev:04X}")
        out.append(f"internal const val {p}EVENT_FLAG_BYTES = {evb}")
        out.append(f"internal const val {p}MISSABLE_FLAGS = 0x{mi:04X}")
        out.append(f"internal const val {p}MISSABLE_FLAG_BYTES = {mib}")
        out.append(f"internal const val {p}HIDDEN_ITEM_FLAGS = 0x{hi:04X}")
        out.append(f"internal const val {p}HIDDEN_ITEM_FLAG_BYTES = {hib}")
        out.append(f"internal const val {p}TRADE_FLAGS = 0x{tr:04X}")
        out.append(f"internal const val {p}TRADE_FLAG_BYTES = {trb}")
        out.append(f"internal const val {p}BADGES = 0x{bd:04X}")
        out.append(f"internal const val {p}TEAM_VAR = 0x{tv:04X}\n")
        out.append(chunk(f"{p}WILD_RAW", wild,
                         "// Wild: G|map|rate|lv:sp x10 (grass / cave); W|map|rate|lv:sp x10 (surf); S|map|odds:sp:lv ... (super rod,\n"
                         "// percent of a cast); OLD|odds:sp:lv; GOOD|odds:sp:lv. Slot chances 51 51 39 25 25 25 13 13 11 3 of 256.\n"))
        out.append(chunk(f"{p}BOSSES_RAW", bosses,
                         "// Bosses: KEY|TITLE|WHERE|DONE (flag ids, any set)|COND (0x10000=v of G1x_TEAM_VAR)|lv sp item m,m,m,m;...\n"))
        out.append(chunk(f"{p}AREAS_RAW", areas,
                         f"// Areas: {len(areas)} things. map|KIND|id|flag|qty|wants|where (map = wCurMap; a trade's flag is\n"
                         "// 0x30000 + its id; item balls 0x10000 + toggleable object; hidden items 0x20000 + HiddenItemCoords index).\n"))
        kinds = {}
        for r in rows:
            kinds[r[1]] = kinds.get(r[1], 0) + 1
        report.append(f"{B.name}: G {len(grass)}, W {len(water)}, S {len(sup)}, OLD 1, GOOD 1; bosses {len(bosses)}; "
                      f"areas {len(areas)} {kinds}; balls starting hidden (flag 0): {notes['hidden_start']}; "
                      f"on unused maps (dropped): {notes.get('unused')}")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print("wrote", os.path.relpath(OUT))
    print("\n".join(report))


if __name__ == "__main__":
    main()
