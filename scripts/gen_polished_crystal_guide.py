#!/usr/bin/env python3
"""Generates the GUIDE's data for Polished Crystal 3.2.x (Rangi42's open-source Crystal hack) from its release ROMs:

    scripts/gen_polished_crystal_guide.py <releases dir>

<releases dir> is the one gen_polished_crystal_tables.py reads (polishedcrystal[-faithful][-debug]-3.2.N.gbc and the .sym
file each release ships). The source at $DECOMPS/polishedcrystal/src (tag v3.2.3) gives what the bytes mean: the
constants (event flags, items, species, trainer classes, script commands), the map list and the map scripts' gifts.

Everything the guide shows is read out of every one of the 16 builds through its own .sym and compared with 3.2.3's (the
faithful builds with faithful 3.2.3's, which keep a few boss moves of the original games); a build that differs fails
the run. Read from the ROM:
  - the wild Pokémon: JohtoGrassWildMons .. OrangeWaterWildMons (map id, the morning / day / night rates, 7 slots per
    time of day; water: one rate, 3 slots), FishMonMaps + FishGroups (each rod's slots: a cumulative threshold of 256,
    species 0 = CORSOLA by day, STARYU at night), TreeMonMaps + TreeMons (headbutt: a common and a rare table per set),
    RockMonMaps + the rock set (ROCK SMASH). Levels at or past LEVEL_FROM_BADGES - 77 are relative to the badges held
    (AdjustLevelForBadges, BadgeBaseLevels).
  - the bosses' parties: TrainerGroups (a size byte, the name, the TRAINERTYPE_* flags, then per Pokémon level, species,
    form [item] [DVs] [personality] [nickname] [EVs] [4 moves]), and each boss's script holding its `loadtrainer` and
    the `setevent` that marks it beaten (the bytes searched in the script label's span).
  - each map's object and BG events (<Map>_MapScriptHeader): item balls (item / key item / TM-HM, quantity, event flag),
    hidden items (BGEVENT_ITEM + item, event flag) and NPC trades (the `trade` command's id -> NPCTrades' wanted and
    offered species; done = its wTradeFlags bit, the companion's flag 0x20000 + id).
The gifts (verbosegiveitem / giveitem / verbosegivekeyitem / verbosegivetmhm / givepoke / giveegg) come from the map
scripts in the source (normal and FAITHFUL preprocessed alike: they agree; a purchase - checkmoney / checkcoins before it -
isn't a gift), each with the event flag that marks it received ([received_flag]: an EVENT_GOT_* set after it, following
the script's jumps, else one set just before it, else a setevent right after, else the checkevent before it; a flag
naming the gift wins). Each one's command bytes are then looked for in its label's span in every build's ROM (3.2.0 /
3.2.1 laid one script out under other labels: there, the map's whole script section). Sub-map names come from the map
labels ("VioletOnixTradeHouse" in VIOLET CITY -> "ONIX TRADE HOUSE"). A map whose landmark is SPECIAL (0) is left out.

Writes shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/GuidePolishedCrystalGen.kt.
"""
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_polished_crystal_tables as pc  # noqa: E402  (Build, RELEASES, CHARS, NUM_SPECIES, SRC)

SRC = pc.SRC
OUT = os.path.join(pc.OUT_DIR, "GuidePolishedCrystalGen.kt")
TRADE_FLAG_BASE = 0x20000   # the companion's flag id for wTradeFlags bit 0 (GuidePolishedCrystal.kt)


# ---------------------------------------------------------------------------------------------------------------------
# Constants, evaluated the way rgbds counts them (only what these files use).

class Consts:
    def __init__(self, defines=()):
        self.v = {}
        self.section = {}       # name -> (file, const_def count): which const_def list a const belongs to
        self.defines = set(defines)

    def eval(self, expr):
        e = expr.strip()
        e = re.sub(r"\$([0-9A-Fa-f]+)", r"0x\1", e)
        e = re.sub(r"%([01]+)", r"0b\1", e)
        e = re.sub(r"\bHIGH\(", "_HIGH(", e)
        e = re.sub(r"\bLOW\(", "_LOW(", e)
        e = e.replace("/", "//")

        def name(m):
            n = m.group(0)
            if n in ("_HIGH", "_LOW") or re.match(r"0[xb]", n):
                return n
            if n not in self.v:
                raise KeyError(n)
            return str(self.v[n])
        e = re.sub(r"\b[A-Za-z_][A-Za-z0-9_]*\b", name, e)
        return int(eval(e, {"_HIGH": lambda x: (x >> 8) & 0xFF, "_LOW": lambda x: x & 0xFF}))

    def load(self, rel):
        path = os.path.join(SRC, rel)
        state = {"value": 0, "inc": 1, "defs": 0, "ext": 0, "tclass": 0}
        stack = []
        in_macro = False
        for raw in open(path, encoding="utf-8"):
            line = raw.split(";")[0].strip()
            if not line:
                continue
            if re.match(r"MACRO\b", line, re.I):
                in_macro = True
                continue
            if re.match(r"ENDM\b", line, re.I):
                in_macro = False
                continue
            if in_macro:
                continue
            if m := re.match(r"if\s+(.*)$", line):
                stack.append(self.cond(m.group(1)))
                continue
            if line == "else":
                stack[-1] = not stack[-1]
                continue
            if line == "endc":
                stack.pop()
                continue
            if not all(stack):
                continue
            self.statement(line, state, rel)

    def cond(self, c):
        c = c.strip()
        if m := re.fullmatch(r"(!?)DEF\((\w+)\)", c):
            return (m.group(2) in self.defines or m.group(2) in self.v) != bool(m.group(1))
        try:
            return bool(self.eval(c))
        except Exception:
            return True

    def put(self, name, value, state, rel):
        self.v[name] = value
        self.section[name] = (rel, state["defs"])

    def statement(self, line, s, rel):
        self.v["ext_const_value"] = s["ext"]
        self.v["const_inc"] = s["inc"]
        if line == "rsreset":
            self.v["_RS"] = 0
        elif m := re.match(r"(?:DEF\s+(\w+)\s+)?(rb|rw)(_skip)?(?:\s+(.+))?$", line):
            if m.group(1):
                self.put(m.group(1), self.v["_RS"], s, rel)
            try:
                self.v["_RS"] += (self.eval(m.group(4)) if m.group(4) else 1) * (2 if m.group(2) == "rw" else 1)
            except (KeyError, SyntaxError):
                self.v["_RS"] = 0x10000   # a struct of sizes this doesn't know: none of its offsets are used
        elif m := re.match(r"const_def(?:\s+([^,]+))?(?:,\s*(.+))?$", line):
            s["value"] = self.eval(m.group(1)) if m.group(1) else 0
            s["inc"] = self.eval(m.group(2)) if m.group(2) else 1
            s["defs"] += 1
            self.v["const_value"] = s["value"]
        elif m := re.match(r"const\s+(\w+)$", line):
            self.put(m.group(1), s["value"], s, rel)
            s["value"] += s["inc"]
        elif m := re.match(r"const_skip(?:\s+(.+))?$", line):
            s["value"] += (self.eval(m.group(1)) if m.group(1) else 1) * s["inc"]
        elif m := re.match(r"const_next\s+(.+)$", line):
            s["value"] = self.eval(m.group(1))
        elif m := re.match(r"trainerclass\s+(\w+)$", line):
            self.put(m.group(1), s["tclass"], s, rel)
            s["tclass"] += 1
            s["value"], s["inc"] = 1, 1
            s["defs"] += 1
        elif m := re.match(r"add_(tm|hm)\s+(\w+)$", line):
            self.put(f"{m.group(1).upper()}_{m.group(2)}", s["value"], s, rel)
            s["value"] += 1
        elif m := re.match(r"ext_const_def(?:\s+([^,]+))?(?:,\s*(\w+))?$", line):
            s["ext"] = self.eval(m.group(1)) if m.group(1) else 0
            if m.group(2):
                self.put(m.group(2), s["ext"], s, rel)
                s["ext"] += s["inc"]
        elif m := re.match(r"ext_const\s+(\w+)$", line):
            s["value"] += s["inc"]
            self.put(m.group(1), s["ext"], s, rel)
            s["ext"] += s["inc"]
        elif m := re.match(r"(?:DEF|def|REDEF|redef)\s+(\w+)\s+(?:EQU|=)\s+(.+)$", line):
            self.v["const_value"] = s["value"]
            try:
                self.put(m.group(1), self.eval(m.group(2)), s, rel)
            except (KeyError, SyntaxError, TypeError, NameError):
                pass
        self.v["const_value"] = s["value"]
        self.v["ext_const_value"] = s["ext"]


def load_consts(defines=()):
    c = Consts(defines)
    for rel in ["constants/pokemon_data_constants.asm", "constants/pokemon_constants.asm", "constants/item_constants.asm",
                "constants/tmhm_constants.asm", "constants/event_flags.asm", "constants/trainer_constants.asm",
                "constants/script_constants.asm", "constants/ram_constants.asm",
                "constants/trainer_data_constants.asm", "constants/map_object_constants.asm", "constants/map_data_constants.asm",
                "constants/text_constants.asm", "constants/npc_trade_constants.asm"]:
        c.load(rel)
    return c


C = load_consts()
ITEM_SECTION = C.section["NO_ITEM"]
KEY_SECTION = C.section["BICYCLE"]
LEVEL_FROM_BADGES = C.v["LEVEL_FROM_BADGES"]


def script_opcodes():
    """events.asm's `const <name>_command` list, in order: the script commands' opcodes."""
    ops, n = {}, 0
    for line in open(os.path.join(SRC, "macros", "scripts", "events.asm"), encoding="utf-8"):
        if m := re.match(r"\s*const\s+(\w+)_command\b", line):
            ops[m.group(1)] = n
            n += 1
        elif re.match(r"\s*const_skip\b", line):
            n += 1
    return ops


OPS = script_opcodes()


def check_tms(r):
    """TM_* / HM_* constants are TMHMMoves' indexes: each one's move is there (the companion names TM n + 1 by it)."""
    moves = Consts()
    moves.load("constants/move_constants.asm")
    for name, v in C.v.items():
        if re.match(r"(TM|HM)_\w+$", name) and name[3:] in moves.v:
            assert r.d[r.at("TMHMMoves") + v] == moves.v[name[3:]], f"{name}: TMHMMoves[{v}] isn't {name[3:]}"


def item_id(name):
    """A regular item constant's id (the companion's id), a key item's 0x200 + id, a TM / HM's 0x101 + n."""
    if name.startswith(("TM_", "HM_")):
        return 0x101 + C.v[name]
    if C.section.get(name) == KEY_SECTION:
        return 0x200 + C.v[name]
    assert C.section.get(name) == ITEM_SECTION, f"{name} isn't an item"
    return C.v[name]


# ---------------------------------------------------------------------------------------------------------------------
# The map list (group / number by maps.asm's order) and each label's place in the ROM.

def map_list():
    out, group, num = {}, 0, 0
    for line in open(os.path.join(SRC, "data", "maps", "maps.asm"), encoding="utf-8"):
        if m := re.match(r"MapGroup(\d+):", line):
            group, num = int(m.group(1)), 0
        elif group and (m := re.match(r"\s*map\s+(\w+)", line)):
            num += 1
            out[m.group(1)] = (group, num)
    return out


MAPS = map_list()


class Rom:
    """A release build: its bytes, its symbols and the spans between them."""

    def __init__(self, b):
        self.b, self.d = b, b.d
        self.addrs = {}
        for name, (bank, addr) in b.sym.items():
            self.addrs[name] = bank * 0x4000 + (addr - 0x4000) if bank else addr
        by_bank = {}
        for name, (bank, addr) in b.sym.items():
            if addr >= 0x8000 and bank < 0x80:
                continue
            by_bank.setdefault(bank, set()).add(addr)
        self.sorted = {k: sorted(v) for k, v in by_bank.items()}
        self.variants = []
        o = self.addrs["VariantSpeciesAndFormTable"]
        while self.d[o] != 0:
            self.variants.append((self.d[o] | (0x100 if self.d[o + 1] & 0x20 else 0)) << 8 | (self.d[o + 1] & 0x1F))
            o += 2

    def at(self, label):
        return self.addrs[label]

    def span(self, label):
        """[start, end) of label's bytes: to the next symbol in its bank."""
        bank, addr = self.b.sym[label]
        nxt = [a for a in self.sorted[bank] if a > addr]
        end = nxt[0] if nxt else 0x8000
        start = self.addrs[label]
        return start, start + (end - addr)

    def map_span(self, label):
        """The map's own bytes: its MapScriptHeader up to the next map's (its scripts and texts follow it)."""
        bank, addr = self.b.sym[label + "_MapScriptHeader"]
        nxt = sorted(a for n, (bk, a) in self.b.sym.items() if bk == bank and a > addr and n.endswith("_MapScriptHeader"))
        start = self.addrs[label + "_MapScriptHeader"]
        return start, start + ((nxt[0] if nxt else 0x8000) - addr)

    def ptr(self, o, bank):
        """A dw at o, read as an address in [bank]."""
        a = struct.unpack_from("<H", self.d, o)[0]
        return a if a < 0x4000 else bank * 0x4000 + (a - 0x4000)

    def species(self, sp, form):
        """The companion's species id for a `dp` pair (polishedSpeciesId)."""
        s9 = sp | (0x100 if form & 0x20 else 0)
        key = s9 << 8 | (form & 0x1F)
        return pc.NUM_SPECIES + 1 + self.variants.index(key) if key in self.variants else s9

    def text(self, o):
        out = []
        while self.d[o] != pc.TERM:
            out.append(pc.CHARS.get(self.d[o], "?"))
            o += 1
        return "".join(out), o + 1


# ---------------------------------------------------------------------------------------------------------------------
# Wild Pokémon.

GRASS_LEN = C.v["GRASS_WILDDATA_LENGTH"]
WATER_LEN = C.v["WATER_WILDDATA_LENGTH"]
NUM_GRASSMON, NUM_WATERMON = C.v["NUM_GRASSMON"], C.v["NUM_WATERMON"]


def wild(r):
    grass, water = {}, {}
    for region in ("Johto", "Kanto", "Orange"):
        o = r.at(region + "GrassWildMons")
        while r.d[o] != 0xFF:
            key = (r.d[o], r.d[o + 1])
            rates = list(r.d[o + 2:o + 5])
            slots = [(r.d[o + 5 + 3 * i], r.species(r.d[o + 6 + 3 * i], r.d[o + 7 + 3 * i])) for i in range(3 * NUM_GRASSMON)]
            assert key not in grass, f"grass {key} twice"
            grass[key] = (rates, slots)
            o += GRASS_LEN
        o = r.at(region + "WaterWildMons")
        while r.d[o] != 0xFF:
            key = (r.d[o], r.d[o + 1])
            slots = [(r.d[o + 3 + 3 * i], r.species(r.d[o + 4 + 3 * i], r.d[o + 5 + 3 * i])) for i in range(NUM_WATERMON)]
            assert key not in water, f"water {key} twice"
            water[key] = (r.d[o + 2], slots)
            o += WATER_LEN
    return grass, water


def map_table(r, label, width=3):
    """(group, number) -> the byte after it, for TreeMonMaps-shaped tables (ending at -1)."""
    out, o = {}, r.at(label)
    while r.d[o] != 0xFF:
        out[(r.d[o], r.d[o + 1])] = r.d[o + 2]
        o += width
    return out


def slot_list(r, o):
    """{%, dp species, level} rows until -1; returns them and the next offset."""
    rows = []
    while r.d[o] != 0xFF:
        rows.append((r.d[o], r.species(r.d[o + 1], r.d[o + 2]), r.d[o + 3]))
        o += 4
    return rows, o + 1


def fishing(r):
    bank = r.b.sym["FishGroups"][0]
    groups = []
    o = r.at("FishGroups")
    for g in range(C.v["NUM_FISHGROUPS"]):
        rods = []
        for rod in range(3):
            p = r.ptr(o + 2 + 2 * rod, bank)
            rows, last = [], -1
            while True:
                t, sp, form, lv = r.d[p:p + 4]
                rows.append((t - last, r.species(sp, form) if sp else 0, lv))
                last = t
                p += 4
                if t >= 0xFF:
                    break
            rods.append(rows)
        groups.append(rods)
        o += C.v["FISHGROUP_DATA_LENGTH"]
    return groups


def trees(r):
    bank = r.b.sym["TreeMons"][0]
    sets = []
    rock = C.v["TREEMON_SET_ROCK"]
    for s in range(C.v["NUM_TREEMON_SETS"]):
        p = r.ptr(r.at("TreeMons") + 2 * s, bank)
        common, p = slot_list(r, p)
        rare = [] if s == rock else slot_list(r, p)[0]
        sets.append((common, rare))
    return sets


# ---------------------------------------------------------------------------------------------------------------------
# Trainers.

TT = {k: C.v["TRNTYPE_" + k] for k in ("ITEM", "EVS", "DVS", "PERSONALITY", "NICKNAME", "MOVES")}


def party(r, cls, tid):
    o = r.at("TrainerGroups") + 3 * (cls - 1)
    bank, addr = r.d[o], struct.unpack_from("<H", r.d, o + 1)[0]
    p = bank * 0x4000 + (addr - 0x4000)
    for _ in range(tid - 1):
        p += 1 + r.d[p]
    size = r.d[p]
    end = p + 1 + size
    name, p = r.text(p + 1)
    flags = r.d[p]
    p += 1
    mons = []
    while p < end:
        lv, sp, form = r.d[p:p + 3]
        p += 3
        item = 0
        if flags >> TT["ITEM"] & 1:
            item = r.d[p]
            p += 1
        if flags >> TT["DVS"] & 1:
            p += 1
        if flags >> TT["PERSONALITY"] & 1:
            p += 1
        if flags >> TT["NICKNAME"] & 1:
            _, p = r.text(p)
        if flags >> TT["EVS"] & 1:
            p += 1
        moves = []
        if flags >> TT["MOVES"] & 1:
            moves = [m for m in r.d[p:p + 4] if m]
            p += 4
        mons.append((lv, r.species(sp, form), item, tuple(moves)))
    assert p == end, f"trainer {cls}/{tid}: {p - end} bytes off"
    return name, tuple(mons)


# The bosses NEXT BOSS walks through (GuidePolishedCrystal.kt lists them in this order): Kotlin name, trainer class and
# party, the script that battles them, the event flag that script sets once they're beaten.
BOSSES = [
    ("FALKNER", "FALKNER", "1", "EVENT_BEAT_FALKNER"),
    ("BUGSY", "BUGSY", "1", "EVENT_BEAT_BUGSY"),
    ("WHITNEY", "WHITNEY", "1", "EVENT_BEAT_WHITNEY"),
    ("MORTY", "MORTY", "1", "EVENT_BEAT_MORTY"),
    ("CHUCK", "CHUCK", "1", "EVENT_BEAT_CHUCK"),
    ("JASMINE", "JASMINE", "1", "EVENT_BEAT_JASMINE"),
    ("PRYCE", "PRYCE", "1", "EVENT_BEAT_PRYCE"),
    ("CLAIR", "CLAIR", "1", "EVENT_BEAT_CLAIR"),
    ("WILL", "WILL", "1", "EVENT_BEAT_ELITE_4_WILL"),
    ("KOGA", "KOGA", "1", "EVENT_BEAT_ELITE_4_KOGA"),
    ("BRUNO", "BRUNO", "1", "EVENT_BEAT_ELITE_4_BRUNO"),
    ("KAREN", "KAREN", "1", "EVENT_BEAT_ELITE_4_KAREN"),
    ("LANCE", "CHAMPION", "LANCE", "EVENT_BEAT_CHAMPION_LANCE"),
    ("BROCK", "BROCK", "1", "EVENT_BEAT_BROCK"),
    ("MISTY", "MISTY", "1", "EVENT_BEAT_MISTY"),
    ("LT_SURGE", "LT_SURGE", "1", "EVENT_BEAT_LTSURGE"),
    ("ERIKA", "ERIKA", "1", "EVENT_BEAT_ERIKA"),
    ("JANINE", "JANINE", "1", "EVENT_BEAT_JANINE"),
    ("SABRINA", "SABRINA", "1", "EVENT_BEAT_SABRINA"),
    ("BLAINE", "BLAINE", "1", "EVENT_BEAT_BLAINE"),
    ("BLUE", "BLUE", "1", "EVENT_BEAT_BLUE"),
    ("WILL_2", "WILL", "2", "EVENT_BEAT_ELITE_4_WILL"),
    ("KOGA_2", "KOGA", "2", "EVENT_BEAT_ELITE_4_KOGA"),
    ("BRUNO_2", "BRUNO", "2", "EVENT_BEAT_ELITE_4_BRUNO"),
    ("KAREN_2", "KAREN", "2", "EVENT_BEAT_ELITE_4_KAREN"),
    ("LANCE_2", "CHAMPION", "LANCE2", "EVENT_BEAT_CHAMPION_LANCE"),
    ("RED", "RED", "1", "EVENT_GOT_MYSTICTICKET_FROM_RED"),
]


def boss_labels():
    """Each boss's `loadtrainer` in the map scripts: (map, the label it's under, the global script it runs in)."""
    out = {}
    for label in MAPS:
        path = os.path.join(SRC, "maps", label + ".asm")
        if not os.path.exists(path):
            continue
        for name, lines in blocks(preprocess(open(path, encoding="utf-8").read(), ())):
            for line in lines:
                if m := re.match(r"loadtrainer\s+(\w+)\s*,\s*(\w+)$", line):
                    out.setdefault((m.group(1), m.group(2)), []).append((label, name))
    return {key: out[(cls, tid)] for key, cls, tid, flag in BOSSES}


# Other flags the Kotlin side reads: a League run won (HallOfFame.asm), and won again with all 16 badges.
EXTRA_FLAGS = ["EVENT_BEAT_ELITE_FOUR", "EVENT_BEAT_ELITE_FOUR_AGAIN"]


def boss_scripts(r):
    """Each boss: its team, after finding its loadtrainer in its label's bytes and the setevent that marks it beaten in
    the script it runs in (to the next label outside it: the rematches' setevent comes after a shared .EndBattle)."""
    out = {}
    for key, cls, tid, flag in BOSSES:
        c, t = C.v[cls], (int(tid) if tid.isdigit() else C.v[tid])
        [(mapl, label)] = BOSS_LABELS[key]
        s, e = r.span(label)
        assert bytes([OPS["loadtrainer"], c, t]) in r.d[s:e], f"{r.b.name}: {label} has no loadtrainer {cls}, {tid}"
        glob = label.split(".")[0]
        bank, gaddr = r.b.sym[glob]
        later = sorted(a for n, (bk, a) in r.b.sym.items() if bk == bank and a > gaddr and not n.startswith(glob + "."))
        end = r.at(glob) + ((later[0] if later else 0x8000) - gaddr)
        se = bytes([OPS["setevent"]]) + struct.pack("<H", C.v[flag])
        assert se in r.d[r.at(glob):end], f"{r.b.name}: {glob} sets no {flag}"
        out[key] = (c, t) + party(r, c, t)
    return out


# ---------------------------------------------------------------------------------------------------------------------
# Map events: item balls, hidden items, trades.

OT = {k: C.v["OBJECTTYPE_" + k] for k in ("ITEMBALL", "COMMAND")}
PE = {k: C.v["PLAYEREVENT_" + k] for k in ("ITEMBALL", "KEYITEMBALL", "TMHMBALL")}
BGEVENT_ITEM = C.v["BGEVENT_ITEM"]


def header_landmark(r, label):
    group, num = MAPS[label]
    gp, gbank = r.at("MapGroupPointers"), r.b.sym["MapGroupPointers"][0]
    first = struct.unpack_from("<H", r.d, gp + 2 * (group - 1))[0]
    return r.d[gbank * 0x4000 + first - 0x4000 + 7 * (num - 1) + 4]


def map_events(r):
    """label -> [(kind, id, flag, qty, wants)] from its MapScriptHeader."""
    trades = r.at("NPCTrades")
    tlen = C.v["NPCTRADE_STRUCT_LENGTH"]
    out = {}
    for label in MAPS:
        o = r.at(label + "_MapScriptHeader")
        o += 1 + 2 * r.d[o]          # scene scripts
        o += 1 + 3 * r.d[o]          # callbacks
        o += 1 + 5 * r.d[o]          # warps
        o += 1 + 5 * r.d[o]          # coord events
        rows = []
        n = r.d[o]
        o += 1
        for i in range(n):           # bg events: y, x, function, dw
            func, word = r.d[o + 2], struct.unpack_from("<H", r.d, o + 3)[0]
            if func >= BGEVENT_ITEM:
                rows.append(("HIDDEN", func - BGEVENT_ITEM, 0 if word == 0xFFFF else word, 1, 0))
            o += 5
        n = r.d[o]
        o += 1
        for i in range(n):           # object events: 9 bytes, then a pointer / contents and an event flag
            typ, sight = r.d[o + 7], r.d[o + 8]
            a, b_, flag = r.d[o + 9], r.d[o + 10], struct.unpack_from("<H", r.d, o + 11)[0]
            flag = 0 if flag == 0xFFFF else flag
            if typ == OT["ITEMBALL"] and sight == PE["ITEMBALL"]:
                rows.append(("ITEM", a, flag, b_, 0))
            elif typ == OT["ITEMBALL"] and sight == PE["KEYITEMBALL"]:
                rows.append(("ITEM", 0x200 + a, flag, 1, 0))
            elif typ == OT["ITEMBALL"] and sight == PE["TMHMBALL"]:
                rows.append(("ITEM", 0x101 + a, flag, 1, 0))
            elif typ == OT["COMMAND"] and sight == OPS["trade"]:
                t = trades + tlen * a
                wants = r.species(r.d[t + 1], r.d[t + 2])
                gets = r.species(r.d[t + 3], r.d[t + 4])
                rows.append(("TRADE", gets, TRADE_FLAG_BASE + a, 1, wants))
            o += 13
        out[label] = rows
    return out


# ---------------------------------------------------------------------------------------------------------------------
# Gifts, from the map scripts.

def preprocess(text, defines):
    out, stack = [], []
    for line in text.splitlines():
        s = line.split(";")[0].strip()
        if m := re.match(r"if\s+(!?)DEF\((\w+)\)$", s):
            stack.append((m.group(2) in defines) != bool(m.group(1)))
            continue
        if re.match(r"if\s", s):    # a condition on the code being built (inside a DEBUG block here): kept
            stack.append(True)
            continue
        if s == "else" and stack:
            stack[-1] = not stack[-1]
            continue
        if s == "endc" and stack:
            stack.pop()
            continue
        if all(stack):
            out.append(line)
    return out


def blocks(lines):
    """(full label, its lines) in order; a local label (.Foo) is Parent.Foo, as the .sym names it."""
    out, glob, label, cur = [], None, None, []
    for line in lines:
        if m := (re.match(r"^(\w+)::?\s*(?:;.*)?$", line) or re.match(r"^(\.\w+):{0,2}\s*(?:;.*)?$", line)):
            if label:
                out.append((label, cur))
            name = m.group(1)
            if name.startswith("."):
                label = f"{glob}{name}"
            else:
                glob = label = name
            cur = []
        elif label:
            cur.append(line.split(";")[0].strip())
    if label:
        out.append((label, cur))
    return out


PURCHASE = re.compile(r"^(checkmoney|takemoney|checkcoins|takecoins|pokemart)\b")


def received_flag(seq, at, owner, starts, name):
    """The event a gift at seq[at] is marked with: an EVENT_GOT_* (or *RECEIVED* / *GAVE*) the script sets after it,
    following its `sjump`s to the end of the script, else one it set just before it (ELM's starters set it first), else
    the first setevent right after it, else the checkevent before it. A flag naming the gift ([name]) comes first."""
    stop = re.compile(r"^(end|return|endcallback|sjump\w*|jump\w*|endtext)\b")
    got = re.compile(r"_GOT_|RECEIVED|_GAVE_")
    after, near = [], []
    i, hops, k = at + 1, 0, 0
    while i < len(seq) and k < 80:
        line = seq[i]
        if m := re.match(r"setevent\s+(EVENT_\w+)", line):
            if not m.group(1).startswith("EVENT_TEMPORARY"):
                after.append(m.group(1))
                if k < 14:
                    near.append(m.group(1))
        if m := re.match(r"sjump\w*\s+(\.?\w+)$", line):
            target = m.group(1)
            target = owner[i].split(".")[0] + target if target.startswith(".") else target
            if target in starts and hops < 4:
                i, hops = starts[target], hops + 1
                k += 1
                continue
        if stop.match(line):
            break
        i, k = i + 1, k + 1
    before = []
    for line in reversed(seq[max(0, at - 15):at]):
        if stop.match(line):
            break
        if m := re.match(r"setevent\s+(EVENT_\w+)", line):
            before.append(m.group(1))
    order = [f for f in after if got.search(f)] + [f for f in before if got.search(f)] + near[:1]
    for f in [f for f in order if name and name in f] + order:
        return f
    for line in reversed(seq[max(0, at - 30):at]):
        if m := re.match(r"checkevent\s+(EVENT_\w+)", line):
            return m.group(1)
    return None


def dp_bytes(species, form):
    v = C.v[species]
    return bytes([v & 0xFF, (v >> 8) << C.v["MON_EXTSPECIES_F"] | form])


def form_value(expr):
    return C.eval(expr) if expr else C.v["PLAIN_FORM"]


def gifts(defines):
    """label -> [(kind, id, flag name, qty, wants, script label, command bytes)]"""
    out = {}
    for label in MAPS:
        path = os.path.join(SRC, "maps", label + ".asm")
        if not os.path.exists(path):
            continue
        bl = blocks(preprocess(open(path, encoding="utf-8").read(), defines))
        # The blocks as one run (a script falls through its local labels), with each line's block.
        seq, owner, starts = [], [], {}
        for name, lines in bl:
            starts[name] = len(seq)
            for ln in lines:
                seq.append(ln)
                owner.append(name)
        rows = []
        for i, line in enumerate(seq):
            got = None
            if m := re.match(r"(verbosegiveitem|giveitem)\s+(\w+)(?:\s*,\s*(\d+))?$", line):
                if m.group(2) in C.v and C.section.get(m.group(2)) == ITEM_SECTION and 0 < C.v[m.group(2)] < 0xFF:
                    qty = int(m.group(3) or 1)
                    got = ("GIFT", item_id(m.group(2)), qty, 0, bytes([OPS[m.group(1)], C.v[m.group(2)], qty]))
            elif m := re.match(r"(verbosegivekeyitem|givekeyitem)\s+(\w+)$", line):
                got = ("KEY", item_id(m.group(2)), 1, 0, bytes([OPS[m.group(1)], C.v[m.group(2)]]))
            elif m := re.match(r"(verbosegivetmhm|givetmhm)\s+((?:TM|HM)_\w+)$", line):
                got = ("GIFT", item_id(m.group(2)), 1, 0, bytes([OPS[m.group(1)], C.v[m.group(2)]]))
            elif m := re.match(r"givepoke\s+(\w+)\s*,\s*([^,]+?)(?:\s*,\s*([^,]+?))?(?:\s*,.*)?$", line):
                species = m.group(1)
                if m.group(3) is None:       # givepoke SPECIES, LEVEL
                    form, level = C.v["PLAIN_FORM"], C.eval(m.group(2))
                else:
                    form, level = form_value(m.group(2)), C.eval(m.group(3))
                sp = dp_bytes(species, form)
                got = ("MON", ROM0.species(sp[0], sp[1]), 1, 0, bytes([OPS["givepoke"]]) + sp + bytes([level]))
            elif m := re.match(r"giveegg\s+(\w+)(?:\s*,\s*(.+))?$", line):
                form = form_value(m.group(2)) | C.v["IS_EGG_MASK"]
                sp = dp_bytes(m.group(1), form)
                got = ("EGG", ROM0.species(sp[0], sp[1] & ~C.v["IS_EGG_MASK"]), 1, 0, bytes([OPS["giveegg"]]) + sp)
            if not got:
                continue
            # A purchase (the Game Corner's prizes, a vending machine) isn't a gift.
            if any(PURCHASE.match(x) for x in seq[max(0, i - 12):i]):
                continue
            kind, ident, qty, wants, cmd = got
            name = re.match(r"\w+\s+(\w+)", line).group(1).replace("TM_", "").replace("HM_", "")
            rows.append((kind, ident, received_flag(seq, i, owner, starts, name), qty, wants, owner[i], cmd))
        out[label] = rows
    return out


# ---------------------------------------------------------------------------------------------------------------------
# Names of sub-maps ("ElmsLab" in NEW BARK TOWN -> "ELMS LAB").

def humanise(label, place):
    words = re.sub(r"(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Z][a-z])|(?<=[A-Z])(?=[A-Z][a-z])", " ", label.replace("_", " ")).upper().split()
    pw = re.sub(r"[^A-Z0-9 ]", "", place.upper().replace("¯", " ")).split()
    full = " ".join(words)
    if re.sub(r"\s", "", full) == re.sub(r"\s", "", " ".join(pw)):
        return ""
    # Drop the place's own name in front ("VIOLET ONIX TRADE HOUSE" in VIOLET CITY -> "ONIX TRADE HOUSE").
    for k in range(len(pw), 0, -1):
        if words[:k] == pw[:k] and len(words) > k and (not words[k][0].isdigit() or re.fullmatch(r"\d+F", words[k])):
            words = words[k:]
            break
    text = " ".join(words)
    return (text.replace("POKECENTER", "POKé CENTER").replace("POKEMON", "POKéMON").replace("POKE ", "POKé "))


# ---------------------------------------------------------------------------------------------------------------------

def read(r, defines):
    grass, water = wild(r)
    check_tms(r)
    w = dict(grass=grass, water=water, fishmaps=map_table(r, "FishMonMaps"), fish=fishing(r),
             treemaps=map_table(r, "TreeMonMaps"), rockmaps=map_table(r, "RockMonMaps"), trees=trees(r),
             badges=list(r.d[r.at("BadgeBaseLevels"):r.at("BadgeBaseLevels") + 17]))
    w["bosses"] = boss_scripts(r)
    w["events"] = map_events(r)
    w["landmarks"] = {label: header_landmark(r, label) for label in MAPS}
    w["ram"] = {n: r.b.sym[n] for n in ("wEventFlags", "wTimeOfDay", "wJohtoBadges", "wKantoBadges", "wTradeFlags")}
    # The gifts' commands, each in its script's span.
    w["gift_misses"] = []
    for label, rows in GIFTS[defines].items():
        for kind, ident, flag, qty, wants, owner, cmd in rows:
            if owner in r.b.sym:
                s, e = r.span(owner)
            else:   # an older release's script is laid out under other labels: the map's whole script section then
                s, e = r.map_span(label)
            if cmd not in r.d[s:e]:
                w["gift_misses"].append(f"{owner}: no {cmd.hex()} ({kind} {ident})")
                continue
            if flag:
                se = bytes([OPS["setevent"]]) + struct.pack("<H", C.v[flag])
                ce = bytes([OPS["checkevent"]]) + struct.pack("<H", C.v[flag])
                if se not in r.d[s - 0x200:e + 0x200] and ce not in r.d[s - 0x200:e + 0x200]:
                    w["gift_misses"].append(f"{owner}: no {flag}")
    return w


def main():
    global ROM0, GIFTS, BOSS_LABELS
    if len(sys.argv) != 2:
        sys.exit(f"usage: {sys.argv[0]} <releases dir>")
    builds = [pc.Build(os.path.join(sys.argv[1], n + ".gbc"), n) for n in pc.RELEASES]
    roms = {b.name: Rom(b) for b in builds}
    ROM0 = roms["polishedcrystal-3.2.3"]
    GIFTS = {(): gifts(()), ("FAITHFUL",): gifts(("FAITHFUL",))}
    BOSS_LABELS = boss_labels()
    data = {n: read(r, ("FAITHFUL",) if "faithful" in n else ()) for n, r in roms.items()}
    base, faithful = data["polishedcrystal-3.2.3"], data["polishedcrystal-faithful-3.2.3"]
    for n, x in data.items():
        if x["gift_misses"]:
            print(n, len(x["gift_misses"]), x["gift_misses"][:40])
    for n, x in data.items():
        ref = faithful if "faithful" in n else base
        for k in x:
            assert x[k] == ref[k], f"{n}: {k} differs from {'faithful ' if 'faithful' in n else ''}3.2.3"
    for k in base:
        if k != "bosses":
            assert base[k] == faithful[k], f"faithful's {k} differ"
    assert GIFTS[()] == GIFTS[("FAITHFUL",)], "faithful's gifts differ"
    assert base["badges"][:17] == base["badges"], base["badges"]

    lm_names = pc.read(ROM0.b)["landmarks"]
    lines = []
    # Area things, by landmark.
    rows = set()
    for label, things in base["events"].items():
        lm = base["landmarks"][label]
        if lm == 0:
            continue
        where = humanise(label, lm_names[lm][0])
        for kind, ident, flag, qty, wants in things:
            rows.add((lm, kind, ident, flag, qty, wants, where))
    for label, things in GIFTS[()].items():
        lm = base["landmarks"][label]
        if lm == 0:
            continue
        where = humanise(label, lm_names[lm][0])
        for kind, ident, flag, qty, wants, owner, cmd in things:
            rows.add((lm, kind, ident, C.v[flag] if flag else 0, qty, wants, where))
    seen, area = set(), []
    for lm, kind, ident, flag, qty, wants, where in sorted(rows):
        key = (lm, kind, ident, where) if kind not in ("ITEM", "HIDDEN") else (lm, kind, ident, flag, where)
        if key in seen:
            continue
        seen.add(key)
        area.append(f"{lm}|{kind}|{ident}|{flag}|{qty}|{wants}|{where}")

    # Wild Pokémon: one line per map and method.
    wild_lines = []
    for (g, n), (rates, slots) in sorted(base["grass"].items()):
        wild_lines.append(f"G|{g}|{n}|{','.join(map(str, rates))}|" + " ".join(f"{lv}:{sp}" for lv, sp in slots))
    for (g, n), (rate, slots) in sorted(base["water"].items()):
        wild_lines.append(f"W|{g}|{n}|{rate}|" + " ".join(f"{lv}:{sp}" for lv, sp in slots))
    for (g, n), fg in sorted(base["fishmaps"].items()):
        wild_lines.append(f"F|{g}|{n}|{fg}")
    for (g, n), ts in sorted(base["treemaps"].items()):
        wild_lines.append(f"T|{g}|{n}|{ts}")
    for (g, n), ts in sorted(base["rockmaps"].items()):
        wild_lines.append(f"R|{g}|{n}|{ts}")
    for i, rods in enumerate(base["fish"]):
        wild_lines.append(f"FG|{i}|" + "|".join(" ".join(f"{c}:{sp}:{lv}" for c, sp, lv in rod) for rod in rods))
    for i, (common, rare) in enumerate(base["trees"]):
        wild_lines.append(f"TS|{i}|" + " ".join(f"{p}:{sp}:{lv}" for p, sp, lv in common) + "|" +
                          " ".join(f"{p}:{sp}:{lv}" for p, sp, lv in rare))

    def boss_lines(bosses):
        out = []
        for key, (c, t, name, mons) in bosses.items():
            out.append(f"{key}|{c}|{t}|{name}|" + ";".join(f"{lv} {sp} {item} {','.join(map(str, mv))}" for lv, sp, item, mv in mons))
        return out
    nb, fb = boss_lines(base["bosses"]), boss_lines(faithful["bosses"])
    faithful_only = [x for x in fb if x not in nb]

    ram = base["ram"]
    flags = sorted({b[3] for b in BOSSES} | set(EXTRA_FLAGS))

    def chunk(name, rows_, doc):
        parts, cur = [], []
        for x in rows_:
            cur.append(x)
            if sum(len(y) + 1 for y in cur) > 30000:
                parts.append(cur)
                cur = []
        if cur:
            parts.append(cur)
        s = doc + f"internal val {name}: List<String> = listOf(\n"
        for p in parts:
            s += '    """\n' + "\n".join(x.replace("$", "${'$'}") for x in p) + '\n""",\n'
        return s + ")\n"

    kt = ["package com.pokedaisy.app.companion.data\n",
          "// GENERATED by scripts/gen_polished_crystal_guide.py from the Polished Crystal 3.2.x release ROMs (every build",
          "// checked against 3.2.3's; the faithful builds' bosses against faithful 3.2.3's) - do not hand-edit.\n",
          "// RAM, the same in every build: wEventFlags (NUM_EVENTS bits), wTimeOfDay (MORN 0, DAY 1, NITE 2, EVE 3),",
          "// wJohtoBadges / wKantoBadges, wTradeFlags (a bit per NPC trade). WRAM bank 1 (wramx).",
          f"internal const val PC_EVENT_FLAGS = 0x{ram['wEventFlags'][1]:04X}",
          f"internal const val PC_EVENT_FLAG_BYTES = {(C.v['NUM_EVENTS'] + 7) // 8}",
          f"internal const val PC_TIME_OF_DAY = 0x{ram['wTimeOfDay'][1]:04X}",
          f"internal const val PC_BADGES = 0x{ram['wJohtoBadges'][1]:04X}",
          f"internal const val PC_TRADE_FLAGS = 0x{ram['wTradeFlags'][1]:04X}",
          f"internal const val PC_TRADE_FLAG_BYTES = {(C.v['NUM_NPC_TRADES'] + 7) // 8}",
          f"internal const val PC_LEVEL_FROM_BADGES = {LEVEL_FROM_BADGES}",
          f"internal const val PC_MORN = {C.v['MORN']}",
          f"internal const val PC_DAY = {C.v['DAY']}",
          f"internal const val PC_NITE = {C.v['NITE']}",
          f"internal const val PC_EVE = {C.v['EVE']}",
          "// The key item RED hands over (a TIPS entry's Have).",
          f"internal const val PC_KEY_MYSTICTICKET = {C.v['MYSTICTICKET']}",
          "// Fish.TimeEncounter's two (a rod slot of species 0).",
          f"internal const val PC_SPECIES_CORSOLA = {ROM0.species(dp_bytes('CORSOLA', 0)[0], dp_bytes('CORSOLA', 0)[1])}",
          f"internal const val PC_SPECIES_STARYU = {ROM0.species(dp_bytes('STARYU', 0)[0], dp_bytes('STARYU', 0)[1])}",
          "",
          "// The event flags NEXT BOSS reads (constants/event_flags.asm; each boss's own found set by its script in every build).",
          ]
    for f in flags:
        kt.append(f"internal const val PC_{f} = {C.v[f]}")
    kt.append("\n// BadgeBaseLevels: the level a LEVEL_FROM_BADGES slot starts from, by badges held (0..16).")
    kt.append("internal val PC_BADGE_BASE_LEVELS = intArrayOf(" + ", ".join(map(str, base["badges"])) + ")\n")
    kt.append(chunk("PC_WILD_RAW", wild_lines,
                    "// Wild Pokémon. G|group|num|morn,day,nite rate|21 x level:species (7 morning, 7 day, 7 night);\n"
                    "// W|group|num|rate|3 x level:species; F / T / R|group|num|fish group / headbutt set / rock set;\n"
                    "// FG|group|old|good|super (count of 256:species:level, species 0 = CORSOLA morning / day, STARYU evening / night);\n"
                    "// TS|set|common|rare (percent:species:level). A level >= 101 is LEVEL_FROM_BADGES-relative.\n"))
    kt.append(chunk("PC_BOSSES_RAW", nb,
                    "// Bosses: key|class|party|name|level species item moves;... (the normal builds).\n"))
    kt.append(chunk("PC_BOSSES_FAITHFUL_RAW", faithful_only, "// The faithful builds' bosses where they differ.\n"))
    kt.append(chunk("GUIDE_AREAS_POLISHED_CRYSTAL_RAW", area,
                    f"// HERE's areas: {len(area)} things in {len({a.split('|')[0] for a in area})} landmarks. Line format: "
                    "landmark|kind|id|flag|qty|wants|where (GuideAreas.kt);\n// a trade's flag is 0x20000 + its wTradeFlags bit.\n"))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(kt))
    print("wrote", os.path.relpath(OUT))
    print(f"{len(base['grass'])} grass, {len(base['water'])} water, {len(base['fishmaps'])} fishing, {len(base['treemaps'])} "
          f"headbutt, {len(base['rockmaps'])} rock smash maps; {len(nb)} bosses ({len(faithful_only)} faithful teams of their "
          f"own); {len(area)} area things; all 16 builds checked")


if __name__ == "__main__":
    main()
