#!/usr/bin/env python3
"""Generates the GUIDE's data for retail Pokémon Crystal from pret/pokecrystal's three builds:

    scripts/gen_crystal_guide.py
    scripts/gen_crystal_guide.py legacy

`legacy`: Crystal Legacy v1.3.1 from $DECOMPS/pokecrystallegacy (cRz-Shadows/Pokemon_Crystal_Legacy at tag V1.3.1, its pokecrystal.gbc
the patched cart byte for byte), one build, written to GuideCrystalLegacyGen.kt under the prefix CL_. It forked pokecrystal before
rgbds' DEF syntax, so its text sources are read from a copy with that brought up to date ([normalized_tree]).

$DECOMPS/pokecrystal (default ~/Projects/tests/my-rom-hacks/build) holds pokecrystal.gbc (rev 0), pokecrystal11.gbc (rev 1)
and pokecrystal_au.gbc (Australia) with their .sym files; roms.sha1 there says each is byte for byte the retail ROM (checked
below). The source gives what the bytes mean: the constants (event flags, items, species, moves, trainer classes, script
commands), the map list and the map scripts' gifts and trades.

Everything the guide shows is read out of all three ROMs through their own .sym files and compared; a difference fails the
run. Read from the ROMs:
  - the wild Pokémon: JohtoGrassWildMons / KantoGrassWildMons (map id, morning / day / night rates, 7 slots per time of day),
    the water tables (one rate, 3 slots), FishGroups + TimeFishGroups (each rod's slots: a cumulative threshold of 256,
    species 0 = a time-of-day table), the fishing group in every map's header, TreeMonMaps + TreeMons (headbutt: a common and
    a rare table per set), RockMonMaps + the rock set (ROCK SMASH). A map gets an F (fishing) line only when its blocks hold
    a water tile (the tileset's collision data: the game's rods need the facing tile's permission to be WATER_TILE).
  - the bosses' parties: TrainerGroups (a name, the TRAINERTYPE_*, then per Pokémon level, species, [item] [4 moves]); a
    party without moves gets what the game gives it (FillMoves: the species' level-up moves up to its level, the last four).
    Each boss's script (loadtrainer ... setevent EVENT_BEAT_*) is found in the source and its bytes in every ROM.
  - each map's events (<Map>_MapEvents): item balls (item, quantity, event flag), hidden items (BGEVENT_ITEM -> dwb flag, item).
  - NPC trades (the `trade` command's id -> NPCTrades' wanted and offered species; done = its wTradeFlags bit, the companion's
    flag 0x20000 + id) and the gifts (giveitem / verbosegiveitem / givepoke / giveegg) from the map scripts in the source
    (a purchase - checkcoins / checkmoney before it - isn't a gift), each one's command bytes looked for in its label's span
    in every ROM, with the event flag that marks it received.

Writes app/src/main/kotlin/com/pokedaisy/app/companion/data/GuideCrystalGen.kt.
"""
import hashlib
import os
import re
import shutil
import struct
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DECOMPS = os.environ.get("DECOMPS", os.path.expanduser("~/Projects/tests/my-rom-hacks/build"))
LEGACY = sys.argv[1:] == ["legacy"]
SRC = os.path.join(DECOMPS, "pokecrystallegacy" if LEGACY else "pokecrystal")
DATA_DIR = os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "pokedaisy", "app", "companion", "data")
OUT = os.path.join(DATA_DIR, "GuideCrystalLegacyGen.kt" if LEGACY else "GuideCrystalGen.kt")
P = "CL_" if LEGACY else "CR_"     # the generated names' prefix
TRADE_FLAG_BASE = 0x20000   # the companion's flag id for wTradeFlags bit 0

BUILDS = [("rev0", "pokecrystal"), ("rev1", "pokecrystal11"), ("au", "pokecrystal_au")]
SHA1S = {
    "pokecrystal": "f4cd194bdee0d04ca4eac29e09b8e4e9d818c133",
    "pokecrystal11": "f2f52230b536214ef7c9924f483392993e226cfb",
    "pokecrystal_au": "a0fc810f1d4e124434f7be2c989ab5b5892ddf36",
}
if LEGACY:
    BUILDS = [("rev0", "pokecrystal")]
    SHA1S = {"pokecrystal": "74a6727e4406c35bc2d4f81347a5c5fdb8970663"}


def normalized_tree(tree):
    """A copy of a fork's sources with rgbds 0.5's `NAME EQU x`, `NAME = x`, `NAME rb` and `name: MACRO` in today's syntax;
    the ROM and .sym stay as they are, linked in."""
    out = tempfile.mkdtemp(prefix="crystal-guide-")
    for top in ("constants", "data", "macros", "maps", "engine"):
        for root, _, files in os.walk(os.path.join(tree, top)):
            for fn in files:
                if not fn.endswith(".asm"):
                    continue
                src = os.path.join(root, fn)
                dst = os.path.join(out, os.path.relpath(src, tree))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                text = open(src, encoding="utf-8", errors="replace").read()
                text = re.sub(r"(?m)^(\w+)\s+(EQU|=|rb|rw)\b", r"DEF \1 \2", text)
                text = re.sub(r"(?m)^(\w+): MACRO\b", r"MACRO \1", text)
                text = text.replace("TREEMON_SET_CITY", "TREEMON_SET_NONE")   # pret's later name: TreeMonSet_City has no data of its own
                open(dst, "w", encoding="utf-8").write(text)
    for fn in ("pokecrystal.gbc", "pokecrystal.sym"):
        os.symlink(os.path.join(tree, fn), os.path.join(out, fn))
    return out


if LEGACY:
    SRC = normalized_tree(SRC)


# ---------------------------------------------------------------------------------------------------------------------
# Constants, evaluated the way rgbds counts them (only what these files use).

class Consts:
    def __init__(self):
        self.v = {}
        self.section = {}       # name -> (file, const_def count): which const_def list a const belongs to

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
        return int(eval(e, {}))

    def load(self, rel):
        state = {"value": 0, "inc": 1, "defs": 0, "tclass": 0}
        in_macro = False
        for raw in open(os.path.join(SRC, rel), encoding="utf-8"):
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
            self.statement(line, state, rel)

    def put(self, name, value, state, rel):
        self.v[name] = value
        self.section[name] = (rel, state["defs"])

    def statement(self, line, s, rel):
        self.v["const_value"] = s["value"]
        if line == "rsreset":
            self.v["_RS"] = 0
        elif line == "rb_skip":
            self.v["_RS"] += 1
        elif m := re.match(r"(?:DEF\s+(\w+)\s+)?(rb|rw)(?:\s+(.+))?$", line):
            if m.group(1):
                self.put(m.group(1), self.v["_RS"], s, rel)
            try:
                self.v["_RS"] += (self.eval(m.group(3)) if m.group(3) else 1) * (2 if m.group(2) == "rw" else 1)
            except (KeyError, SyntaxError):
                self.v["_RS"] = 0x10000
        elif m := re.match(r"const_def(?:\s+([^,]+))?(?:,\s*(.+))?$", line):
            s["value"] = self.eval(m.group(1)) if m.group(1) else 0
            s["inc"] = self.eval(m.group(2)) if m.group(2) else 1
            s["defs"] += 1
        elif m := re.match(r"(?:shift_)?const\s+(\w+)$", line):
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
        elif m := re.match(r"(?:DEF|def|REDEF|redef)\s+(\w+)\s+(?:EQU|=)\s+(.+)$", line):
            try:
                self.put(m.group(1), self.eval(m.group(2)), s, rel)
            except (KeyError, SyntaxError, TypeError, NameError, ZeroDivisionError):
                pass
        self.v["const_value"] = s["value"]


def consts(*files):
    c = Consts()
    for f in files:
        c.load(f)
    return c


C = consts("constants/pokemon_data_constants.asm", "constants/event_flags.asm", "constants/trainer_constants.asm",
           "constants/script_constants.asm", "constants/map_data_constants.asm", "constants/text_constants.asm", "constants/npc_trade_constants.asm",
           "constants/item_data_constants.asm", "constants/trainer_data_constants.asm", "constants/tileset_constants.asm", "constants/collision_constants.asm", "constants/battle_constants.asm")
SPECIES = consts("constants/pokemon_constants.asm")
MOVES = consts("constants/move_constants.asm")
for _k in ("NPCTRADE_STRUCT_LENGTH", "MAP_LENGTH", "ITEMATTR_STRUCT_LENGTH", "FISHGROUP_DATA_LENGTH", "TILESET_LENGTH"):
    assert C.v[_k] < 0x100, _k
ITEMS = consts("constants/item_constants.asm")
ITEM_SECTION = ITEMS.section["NO_ITEM"]


def script_opcodes():
    """events.asm's `const <name>_command ; $xx` list, in order: the script commands' opcodes."""
    ops, n = {}, 0
    for line in open(os.path.join(SRC, "macros", "scripts", "events.asm"), encoding="utf-8"):
        if m := re.match(r"\s*const\s+(\w+)_command\b(?:\s*;\s*\$([0-9a-f]+))?", line):
            if m.group(2):
                assert int(m.group(2), 16) == n, f"{m.group(1)}: {n:#x} vs {m.group(2)}"
            ops[m.group(1)] = n
            n += 1
        elif re.match(r"\s*const_skip\b", line):
            n += 1
    return ops


OPS = script_opcodes()


# ---------------------------------------------------------------------------------------------------------------------
# A build: its bytes, its symbols and the spans between them.

SYM_RE = re.compile(r"^([0-9a-f]{2}):([0-9a-f]{4}) (\S+)$")


class Rom:
    def __init__(self, label, stem):
        self.label, self.stem = label, stem
        self.d = open(os.path.join(SRC, stem + ".gbc"), "rb").read()
        assert hashlib.sha1(self.d).hexdigest() == SHA1S[stem], f"{stem}.gbc isn't the retail ROM"
        self.sym, self.ramsym = {}, {}
        for line in open(os.path.join(SRC, stem + ".sym"), encoding="utf-8"):
            if m := SYM_RE.match(line.strip()):
                bank, addr = int(m.group(1), 16), int(m.group(2), 16)
                if addr < 0x8000:
                    self.sym[m.group(3)] = (bank, addr)
                elif addr >= 0xC000:
                    self.ramsym[m.group(3)] = (bank, addr)
        if LEGACY:
            self.sym["CollisionPermissionTable"] = self.sym["TileCollisionTable"]   # pret's later name
        by_bank = {}
        for name, (bank, addr) in self.sym.items():
            by_bank.setdefault(bank, set()).add(addr)
        self.sorted = {k: sorted(v) for k, v in by_bank.items()}

    def off(self, bank, addr):
        return addr if bank == 0 or addr < 0x4000 else bank * 0x4000 + (addr - 0x4000)

    def at(self, label):
        return self.off(*self.sym[label])

    def bank(self, label):
        return self.sym[label][0]

    def ptr(self, o, bank):
        """A dw at o, read as an address in [bank]."""
        return self.off(bank, struct.unpack_from("<H", self.d, o)[0])

    def span(self, label):
        """[start, end) of label's bytes: to the next symbol in its bank."""
        bank, addr = self.sym[label]
        nxt = [a for a in self.sorted[bank] if a > addr]
        end = nxt[0] if nxt else 0x8000
        start = self.at(label)
        return start, start + (end - addr)

    def global_span(self, label):
        """The whole script a (possibly local) label is in: its global label up to the next global one."""
        glob = label.split(".")[0]
        bank, gaddr = self.sym[glob]
        later = sorted(a for n, (bk, a) in self.sym.items() if bk == bank and a > gaddr and not n.startswith(glob + "."))
        start = self.at(glob)
        return start, start + ((later[0] if later else 0x8000) - gaddr)


CHARS = {0x7F: " ", 0xE8: ".", 0xE3: "-", 0xE0: "'", 0xF4: ",", 0xE6: "?", 0xE7: "!", 0xEF: "♂", 0xF5: "♀"}
CHARS.update({0x80 + i: chr(65 + i) for i in range(26)})
CHARS.update({0xA0 + i: chr(97 + i) for i in range(26)})
CHARS.update({0xF6 + i: str(i) for i in range(10)})


def text(r, o):
    out = []
    while r.d[o] != 0x50:
        out.append(CHARS.get(r.d[o], "?"))
        o += 1
    return "".join(out), o + 1


# ---------------------------------------------------------------------------------------------------------------------
# The map list (group / number by maps.asm's order).

def map_list():
    """[(group, number, label, tileset, environment, landmark const, fish const)] in maps.asm's order."""
    lines = open(os.path.join(SRC, "data", "maps", "maps.asm"), encoding="utf-8").read().splitlines()
    groups, cur, out = [], None, []
    in_ptrs = False
    for line in lines:
        s = line.split(";")[0].strip()
        if s.startswith("MapGroupPointers"):
            in_ptrs = True
        elif in_ptrs and (m := re.match(r"dw\s+(MapGroup_\w+)", s)):
            groups.append(m.group(1))
        elif in_ptrs and s.startswith("assert_table_length"):
            in_ptrs = False
        elif m := re.match(r"(MapGroup_\w+):", s):
            cur, num = m.group(1), 0
        elif cur and (m := re.match(r"map\s+(\w+),\s*(\w+),\s*(\w+),\s*(\w+),", s)):
            num += 1
            out.append((groups.index(cur) + 1, num, m.group(1)))
    return out


MAPS = map_list()
MAP_LABELS = {(g, n): label for g, n, label in MAPS}


# ---------------------------------------------------------------------------------------------------------------------
# Wild Pokémon.

GRASS_LEN = C.v["GRASS_WILDDATA_LENGTH"]
WATER_LEN = C.v["WATER_WILDDATA_LENGTH"]
NUM_GRASSMON, NUM_WATERMON = C.v["NUM_GRASSMON"], C.v["NUM_WATERMON"]


def wild_tables(r, prefix_list):
    grass, water = {}, {}
    for region in prefix_list:
        o = r.at(region + "GrassWildMons")
        while r.d[o] != 0xFF:
            key = (r.d[o], r.d[o + 1])
            rates = list(r.d[o + 2:o + 5])
            slots = [(r.d[o + 5 + 2 * i], r.d[o + 6 + 2 * i]) for i in range(3 * NUM_GRASSMON)]
            assert key not in grass, f"grass {key} twice"
            grass[key] = (rates, slots)
            o += GRASS_LEN
        o = r.at(region + "WaterWildMons")
        while r.d[o] != 0xFF:
            key = (r.d[o], r.d[o + 1])
            slots = [(r.d[o + 3 + 2 * i], r.d[o + 4 + 2 * i]) for i in range(NUM_WATERMON)]
            assert key not in water, f"water {key} twice"
            water[key] = (r.d[o + 2], slots)
            o += WATER_LEN
    return grass, water


def map_table(r, label):
    """(group, number) -> the byte after it, for TreeMonMaps-shaped tables (ending at -1)."""
    out, o = {}, r.at(label)
    while r.d[o] != 0xFF:
        out[(r.d[o], r.d[o + 1])] = r.d[o + 2]
        o += 3
    return out


def tree_rows(r, o):
    rows = []
    while r.d[o] != 0xFF:
        rows.append((r.d[o], r.d[o + 1], r.d[o + 2]))
        o += 3
    return rows, o + 1


def trees(r):
    sets = []
    rock = C.v["TREEMON_SET_ROCK"]
    bank = r.bank("TreeMons")
    for s in range(C.v["NUM_TREEMON_SETS"]):
        if s == C.v["TREEMON_SET_NONE"]:
            sets.append(([], []))   # TreeMonSet_None has no data of its own: its label sits on the Canyon set's
            continue
        p = r.ptr(r.at("TreeMons") + 2 * s, bank)
        common, p = tree_rows(r, p)
        rare = [] if s == rock else tree_rows(r, p)[0]
        sets.append((common, rare))
    return sets


def fishing(r):
    """FishGroups: per group (bite chance, [rows per rod]); a row = (share of 256, species, level), species 0 = time group."""
    bank = r.bank("FishGroups")
    groups = []
    o = r.at("FishGroups")
    for g in range(C.v["NUM_FISHGROUPS"]):
        rods = []
        for rod in range(3):
            p = r.ptr(o + 1 + 2 * rod, bank)
            rows, last = [], -1
            while True:
                t, sp, lv = r.d[p:p + 3]
                rows.append((t - last, sp, lv))
                last = t
                p += 3
                if t >= 0xFF:
                    break
            assert sum(x[0] for x in rows) == 256, (g, rod, rows)
            rods.append(rows)
        groups.append((r.d[o], rods))
        o += C.v["FISHGROUP_DATA_LENGTH"]
    start = r.at("TimeFishGroups")
    nxt = [a for a in r.sorted[r.bank("TimeFishGroups")] if a > r.sym["TimeFishGroups"][1]]
    count = (nxt[0] - r.sym["TimeFishGroups"][1]) // 4 if nxt else 0
    time_groups = [tuple(r.d[start + 4 * i:start + 4 * i + 4]) for i in range(count)]
    return groups, time_groups


def odds(r, label, n):
    """GrassMonProbTable / WaterMonProbTable: (cumulative percent, index * 2) pairs -> percent per slot."""
    o, last, out = r.at(label), 0, [0] * n
    for _ in range(n):
        pct, idx = r.d[o], r.d[o + 1]
        out[idx // 2] = pct - last
        last = pct
        o += 2
    assert last == 100
    return out


# ---------------------------------------------------------------------------------------------------------------------
# Map headers, water in a map's blocks.

def header(r, g, n):
    gp, gbank = r.at("MapGroupPointers"), r.bank("MapGroupPointers")
    first = r.ptr(gp + 2 * (g - 1), gbank)
    return r.d[first + C.v["MAP_LENGTH"] * (n - 1):first + C.v["MAP_LENGTH"] * n]


COLL_PERM = {}


def water_blocks(r, tileset):
    """The block ids of a tileset with a tile the rods can face (collision permission WATER_TILE)."""
    perm = r.at("CollisionPermissionTable")
    ent = r.at("Tilesets") + C.v["TILESET_LENGTH"] * tileset
    cbank, caddr = r.d[ent + 6], struct.unpack_from("<H", r.d, ent + 7)[0]
    coll = r.off(cbank, caddr)
    out = set()
    for b in range(256):
        for k in range(4):
            c = r.d[coll + 4 * b + k]
            if r.d[perm + c] & 0x0F == C.v["WATER_TILE"]:
                out.add(b)
    return out


def maps_info(r):
    out = {}
    cache = {}
    for g, n, label in MAPS:
        h = header(r, g, n)
        bank, tileset, env = h[0], h[1], h[2]
        attrs = r.off(bank, struct.unpack_from("<H", h, 3)[0])
        height, width = r.d[attrs + 1], r.d[attrs + 2]
        bbank, baddr = r.d[attrs + 3], struct.unpack_from("<H", r.d, attrs + 4)[0]
        blocks = r.d[r.off(bbank, baddr):r.off(bbank, baddr) + height * width]
        if tileset not in cache:
            cache[tileset] = water_blocks(r, tileset)
        out[(g, n)] = dict(label=label, landmark=h[5], fish=h[8], tileset=tileset, env=env,
                           water=any(b in cache[tileset] for b in blocks))
    return out


# ---------------------------------------------------------------------------------------------------------------------
# Trainers.

TT = {k: C.v["TRAINERTYPE_" + k] for k in ("NORMAL", "MOVES", "ITEM", "ITEM_MOVES")}


def learnset(r, species):
    """EvosAttacksPointers: skip the evolutions (to the first 0 byte, as FillMoves does), then (level, move) pairs."""
    bank = r.bank("EvosAttacksPointers")
    p = r.ptr(r.at("EvosAttacksPointers") + 2 * (species - 1), bank)
    while r.d[p] != 0:
        p += 1
    p += 1
    out = []
    while r.d[p] != 0:
        out.append((r.d[p], r.d[p + 1]))
        p += 2
    return out


def fill_moves(r, species, level):
    """FillMoves (engine/pokemon/evolve.asm) for a fresh Pokémon: each level-up move up to the level, if not known yet,
    into a free slot, else everything shifts down and it takes the last."""
    moves = [0, 0, 0, 0]
    for lv, mv in learnset(r, species):
        if lv > level:
            break
        if mv in moves:
            continue
        if 0 in moves:
            moves[moves.index(0)] = mv
        else:
            moves = moves[1:] + [mv]
    return [m for m in moves if m]


def party_legacy(r, cls, tid):
    """Crystal Legacy's ReadTrainerParty: TrainerGroups holds a bank and an address, and the party type is flags - each Pokémon
    is its level and species, then a nickname, DVs, stat experience, happiness, item and moves as the type says."""
    o = r.at("TrainerGroups") + 3 * (cls - 1)
    bank = r.d[o]
    p = r.ptr(o + 1, bank)
    for _ in range(tid - 1):
        while r.d[p] != 0xFF:
            p += 1
        p += 1
    name, p = text(r, p)
    typ = r.d[p]
    p += 1
    has = lambda flag: typ & C.v["TRAINERTYPE_" + flag]
    assert not has("VARIABLE") and not has("NICKNAME"), f"{name}: a boss's party type the guide doesn't read"
    mons = []
    while r.d[p] != 0xFF:
        lv, sp = r.d[p:p + 2]
        p += 2
        p += 2 if has("DVS") else 0
        p += 10 if has("STAT_EXP") else 0
        p += 1 if has("HAPPINESS") else 0
        item = 0
        if has("ITEM"):
            item = r.d[p]
            p += 1
        if has("MOVES"):
            moves = [m for m in r.d[p:p + 4] if m]
            p += 4
        else:
            moves = fill_moves(r, sp, lv)
        mons.append((lv, sp, item, tuple(moves)))
    return name, tuple(mons)


def party(r, cls, tid):
    if LEGACY:
        return party_legacy(r, cls, tid)
    o = r.at("TrainerGroups") + 2 * (cls - 1)
    p = r.ptr(o, r.bank("TrainerGroups"))
    for _ in range(tid - 1):     # ReadTrainerParty: scan to each trainer's -1
        while r.d[p] != 0xFF:
            p += 1
        p += 1
    name, p = text(r, p)
    typ = r.d[p]
    p += 1
    mons = []
    while r.d[p] != 0xFF:
        lv, sp = r.d[p:p + 2]
        p += 2
        item = 0
        if typ in (TT["ITEM"], TT["ITEM_MOVES"]):
            item = r.d[p]
            p += 1
        if typ in (TT["MOVES"], TT["ITEM_MOVES"]):
            moves = [m for m in r.d[p:p + 4] if m]
            p += 4
        else:
            moves = fill_moves(r, sp, lv)
        mons.append((lv, sp, item, tuple(moves)))
    return name, tuple(mons)


# Kotlin name, trainer class, trainer id, the event flag the boss's script sets once they're beaten (None: it sets none).
BOSSES = [
    ("FALKNER", "FALKNER", "FALKNER1"),
    ("BUGSY", "BUGSY", "BUGSY1"),
    ("WHITNEY", "WHITNEY", "WHITNEY1"),
    ("MORTY", "MORTY", "MORTY1"),
    ("CHUCK", "CHUCK", "CHUCK1"),
    ("JASMINE", "JASMINE", "JASMINE1"),
    ("PRYCE", "PRYCE", "PRYCE1"),
    ("CLAIR", "CLAIR", "CLAIR1"),
    ("WILL", "WILL", "WILL1"),
    ("KOGA", "KOGA", "KOGA1"),
    ("BRUNO", "BRUNO", "BRUNO1"),
    ("KAREN", "KAREN", "KAREN1"),
    ("LANCE", "CHAMPION", "LANCE"),
    ("BROCK", "BROCK", "BROCK1"),
    ("MISTY", "MISTY", "MISTY1"),
    ("LT_SURGE", "LT_SURGE", "LT_SURGE1"),
    ("ERIKA", "ERIKA", "ERIKA1"),
    ("JANINE", "JANINE", "JANINE1"),
    ("SABRINA", "SABRINA", "SABRINA1"),
    ("BLAINE", "BLAINE", "BLAINE1"),
    ("BLUE", "BLUE", "BLUE1"),
    ("RED", "RED", "RED1"),
]
EXTRA_FLAGS = ["EVENT_BEAT_ELITE_FOUR", "EVENT_RED_IN_MT_SILVER"]
if LEGACY:
    EXTRA_FLAGS = ["EVENT_BEAT_ELITE_FOUR", "EVENT_DEFEATED_RED"]   # it clears EVENT_RED_IN_MT_SILVER when he's beaten
    # Crystal Legacy scales the three gyms a player can take in any order by badge count: the team for 5 badges or fewer
    # (the key above), 6 (_6) and 7 (_7); its other second teams are rematches, after a boss is beaten. Its scripts jump to a
    # common end before `setevent`, so the flags are named here and checked against each script's bytes.
    BOSSES += [(f"{name}_{badges}", name, str(team)) for name in ("CHUCK", "JASMINE", "PRYCE") for badges, team in ((6, 2), (7, 3))]
    BOSS_FLAGS = {key: f"EVENT_BEAT_{key}" for key in (
        "FALKNER", "BUGSY", "WHITNEY", "MORTY", "CHUCK", "JASMINE", "PRYCE", "CLAIR", "BROCK", "MISTY", "ERIKA", "JANINE",
        "SABRINA", "BLAINE", "BLUE")}
    BOSS_FLAGS.update({"LT_SURGE": "EVENT_BEAT_LTSURGE", "WILL": "EVENT_BEAT_ELITE_4_WILL", "KOGA": "EVENT_BEAT_ELITE_4_KOGA",
                       "BRUNO": "EVENT_BEAT_ELITE_4_BRUNO", "KAREN": "EVENT_BEAT_ELITE_4_KAREN"})


def boss_party(tid):
    """A boss's party number: a trainer constant, or the plain number Crystal Legacy's scripts use for later teams."""
    return int(tid) if tid.isdigit() else C.v[tid]


# ---------------------------------------------------------------------------------------------------------------------
# Scripts in the source: labels, gifts, bosses.

def preprocess(txt, defines):
    out, stack = [], []
    for line in txt.splitlines():
        s = line.split(";")[0].strip()
        if m := re.match(r"if\s+(!?)DEF\((\w+)\)$", s):
            stack.append((m.group(2) in defines) != bool(m.group(1)))
            continue
        if re.match(r"if\s", s):
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


def flatten(bl):
    seq, owner, starts = [], [], {}
    for name, lines in bl:
        starts[name] = len(seq)
        for ln in lines:
            seq.append(ln)
            owner.append(name)
    return seq, owner, starts


PURCHASE = re.compile(r"^(checkmoney|takemoney|checkcoins|takecoins|pokemart)\b")


def received_flag(seq, at, owner, starts, name):
    """The event a gift at seq[at] is marked with: an EVENT_GOT_* (or *RECEIVED* / *GAVE*) the script sets after it,
    following its jumps to the end of the script, else one it set just before it, else the first setevent right after
    it, else the checkevent before it. A flag naming the gift ([name]) comes first."""
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


def map_source(label, defines):
    path = os.path.join(SRC, "maps", label + ".asm")
    if not os.path.exists(path):
        return None
    return flatten(blocks(preprocess(open(path, encoding="utf-8").read(), defines)))


def item_info(name):
    """(item id, is a key item / HM) for an item constant, else None."""
    if name in ITEMS.v and ITEMS.section.get(name) == ITEM_SECTION and 0 < ITEMS.v[name] < 0xFF:
        return ITEMS.v[name]
    return None


def gifts(defines):
    """label -> [(kind, id, flag name, qty, wants, script label, command bytes)]; kind GIFT / KEY / MON / EGG / TRADE."""
    out = {}
    for g, n, label in MAPS:
        src = map_source(label, defines)
        if not src:
            continue
        seq, owner, starts = src
        rows = []
        for i, line in enumerate(seq):
            got = None
            if m := re.match(r"(verbosegiveitem|giveitem)\s+(\w+)(?:\s*,\s*(\d+))?$", line):
                item = item_info(m.group(2))
                if item is not None:
                    qty = int(m.group(3) or 1)
                    key = m.group(2).startswith("HM_") or ("KEY", item) in KEY_ITEMS
                    got = ("KEY" if key else "GIFT", item, qty, 0, bytes([OPS[m.group(1)], item, qty]))
            elif m := re.match(r"givepoke\s+(.+)$", line):
                args = [a.strip() for a in m.group(1).split(",")]
                sp, lv = SPECIES.v[args[0]], C.eval(args[1])
                itm = ITEMS.v[args[2]] if len(args) > 2 else 0
                trainer = 1 if len(args) == 5 else (C.eval(args[3]) if len(args) == 4 else 0)
                got = ("MON", sp, 1, 0, bytes([OPS["givepoke"], sp, lv, itm, trainer]))
            elif m := re.match(r"giveegg\s+(\w+)\s*,\s*(.+)$", line):
                sp = SPECIES.v[m.group(1)]
                got = ("EGG", sp, 1, 0, bytes([OPS["giveegg"], sp, C.eval(m.group(2))]))
            elif m := re.match(r"trade\s+(NPC_TRADE_\w+)$", line):
                tid = C.v[m.group(1)]
                got = ("TRADE", tid, 1, 0, bytes([OPS["trade"], tid]))
            if not got:
                continue
            if got[0] != "TRADE" and any(PURCHASE.match(x) for x in seq[max(0, i - 12):i]):
                continue            # the Game Corner's prizes: a purchase isn't a gift
            kind, ident, qty, wants, cmd = got
            if kind == "TRADE":
                rows.append((kind, ident, None, qty, wants, owner[i], cmd))
                continue
            name = re.match(r"\w+\s+(\w+)", line).group(1).replace("TM_", "").replace("HM_", "")
            rows.append((kind, ident, received_flag(seq, i, owner, starts, name), qty, wants, owner[i], cmd))
        out[label] = rows
    return out


def boss_flags(defines):
    """Each boss's loadtrainer in the map scripts: (map, label, the setevent EVENT_BEAT_* after it or None)."""
    out = {}
    stop = re.compile(r"^(end|endcallback|sjump\w*|jump\w*|return)\b")
    for g, n, label in MAPS:
        src = map_source(label, defines)
        if not src:
            continue
        seq, owner, starts = src
        for i, line in enumerate(seq):
            if m := re.match(r"loadtrainer\s+(\w+)\s*,\s*(\w+)$", line):
                flag = None
                for x in seq[i + 1:i + 80]:
                    if stop.match(x):
                        break
                    if f := re.match(r"setevent\s+(EVENT_BEAT_\w+)", x):
                        flag = f.group(1)
                        break
                out.setdefault((m.group(1), m.group(2)), []).append((label, owner[i], flag))
    return out


# ---------------------------------------------------------------------------------------------------------------------
# Map events: item balls, hidden items.

OT_ITEMBALL = C.v["OBJECTTYPE_ITEMBALL"]
BGEVENT_ITEM = C.v["BGEVENT_ITEM"]


def map_events(r):
    """label -> [(kind, id, flag, qty, wants)] from its MapEvents."""
    out = {}
    for g, n, label in MAPS:
        name = label + "_MapEvents"
        bank, o = r.bank(name), r.at(name)
        o += 2                             # filler
        o += 1 + 5 * r.d[o]                # warps
        o += 1 + 8 * r.d[o]                # coord events
        rows = []
        cnt = r.d[o]
        o += 1
        for _ in range(cnt):               # bg events: y, x, function, dw
            func, word = r.d[o + 2], struct.unpack_from("<H", r.d, o + 3)[0]
            if func == BGEVENT_ITEM:
                p = r.off(bank, word)      # hiddenitem: dwb flag, item
                flag = struct.unpack_from("<H", r.d, p)[0]
                rows.append(("HIDDEN", r.d[p + 2], 0 if flag == 0xFFFF else flag, 1, 0))
            o += 5
        cnt = r.d[o]
        o += 1
        for _ in range(cnt):               # object events, 13 bytes
            typ = r.d[o + 7] & 0x0F
            script, flag = struct.unpack_from("<HH", r.d, o + 9)
            if typ == OT_ITEMBALL:
                p = r.off(bank, script)    # itemball: db item, quantity
                rows.append(("ITEM", r.d[p], 0 if flag == 0xFFFF else flag, r.d[p + 1], 0))
            o += 13
        out[label] = rows
    return out


def trades(r):
    """NPCTrades: id -> (wanted species, offered species)."""
    t0, ln = r.at("NPCTrades"), C.v["NPCTRADE_STRUCT_LENGTH"]
    return {i: (r.d[t0 + ln * i + 1], r.d[t0 + ln * i + 2]) for i in range(C.v["NUM_NPC_TRADES"])}


def key_items(r):
    """Item ids whose ItemAttributes pocket is KEY_ITEM."""
    o = r.at("ItemAttributes")
    ln = C.v["ITEMATTR_STRUCT_LENGTH"]
    return {("KEY", i) for i in range(1, ITEMS.v["NUM_ITEMS"] + 1) if r.d[o + ln * (i - 1) + 5] == C.v["KEY_ITEM"]}


# ---------------------------------------------------------------------------------------------------------------------
# Names of sub-maps ("ElmsLab" in NEW BARK TOWN -> "ELMS LAB").

def humanise(label, place):
    words = re.sub(r"(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Z][a-z])|(?<=[A-Z])(?=[A-Z][a-z])", " ", label.replace("_", " ")).upper().split()
    pw = re.sub(r"[^A-Z0-9 ]", "", place.upper().replace("¯", " ")).split()
    full = " ".join(words)
    if re.sub(r"\s", "", full) == re.sub(r"\s", "", " ".join(pw)):
        return ""
    for k in range(len(pw), 0, -1):
        if words[:k] == pw[:k] and len(words) > k and (not words[k][0].isdigit() or re.fullmatch(r"\d+F", words[k])):
            words = words[k:]
            break
    t = " ".join(words)
    return t.replace("POKECENTER", "POKé CENTER").replace("POKEMON", "POKéMON").replace("POKE ", "POKé ")


def app_landmarks():
    """MapSecDataCrystal.kt: landmark id -> name, and wMapGroup * 256 + wMapNumber -> landmark id."""
    txt = open(os.path.join(DATA_DIR, "MapSecDataCrystal.kt"), encoding="utf-8").read()   # Legacy's too: gen_gen2_tables.py checks it
    names = {int(i): n for i, n in re.findall(r'^\s*(\d+) to MapSecInfo\("([^"]*)"', txt, re.M)}
    part = txt.split("val mapLandmarksCrystal", 1)[1]
    lm = {int(k): int(v) for k, v in re.findall(r"^\s*(\d+) to (\d+),?\s*$", part, re.M)}
    return names, lm


# ---------------------------------------------------------------------------------------------------------------------

def read(r):
    w = {}
    w["grass"], w["water"] = wild_tables(r, ("Johto", "Kanto"))
    w["swarm"] = wild_tables(r, ("Swarm",))
    w["treemaps"], w["rockmaps"] = map_table(r, "TreeMonMaps"), map_table(r, "RockMonMaps")
    w["trees"] = trees(r)
    w["fish"], w["timefish"] = fishing(r)
    w["grass_odds"], w["water_odds"] = odds(r, "GrassMonProbTable", NUM_GRASSMON), odds(r, "WaterMonProbTable", NUM_WATERMON)
    w["maps"] = maps_info(r)
    w["events"] = map_events(r)
    w["trades"] = trades(r)
    w["keyitems"] = key_items(r)
    bosses = {}
    for key, cls, tid in BOSSES:
        bosses[key] = (C.v[cls], boss_party(tid)) + party(r, C.v[cls], boss_party(tid))
    w["bosses"] = bosses
    w["ram"] = {n: r.ramsym[n] for n in ("wEventFlags", "wTimeOfDay", "wJohtoBadges", "wKantoBadges", "wTradeFlags", "wMapGroup")}
    return w


def check_scripts(r, gift_rows, boss_rows):
    """Every gift / trade command in its label's bytes, every boss's loadtrainer and setevent in its script's."""
    misses = []
    for label, rows in gift_rows.items():
        for kind, ident, flag, qty, wants, owner, cmd in rows:
            s, e = r.span(owner)
            if cmd not in r.d[s:e]:
                misses.append(f"{owner}: no {cmd.hex()} ({kind} {ident})")
                continue
            if flag:
                se = bytes([OPS["setevent"]]) + struct.pack("<H", C.v[flag])
                ce = bytes([OPS["checkevent"]]) + struct.pack("<H", C.v[flag])
                gs, ge = r.global_span(owner)
                if se not in r.d[gs:ge] and ce not in r.d[gs:ge] and se not in r.d[s - 0x200:e + 0x200]:
                    misses.append(f"{owner}: no {flag}")
    for key, cls, tid in BOSSES:
        label, owner, flag = boss_rows[key][0]
        s, e = r.span(owner)
        assert bytes([OPS["loadtrainer"], C.v[cls], boss_party(tid)]) in r.d[s:e], f"{r.label}: {owner} has no loadtrainer {cls}, {tid}"
        flag = BOSS_FLAGS.get(key) if LEGACY else flag
        if flag:
            gs, ge = r.global_span(owner)
            se = bytes([OPS["setevent"]]) + struct.pack("<H", C.v[flag])
            assert se in r.d[gs:ge], f"{r.label}: {owner} sets no {flag}"
    return misses


KEY_ITEMS = set()


def main():
    global KEY_ITEMS
    roms = {label: Rom(label, stem) for label, stem in BUILDS}
    data = {label: read(r) for label, r in roms.items()}
    base = data["rev0"]
    for label, x in data.items():
        for k in x:
            if k == "ram":
                continue
            assert x[k] == base[k], f"{label}: {k} differs from rev0"
    ram = {n: v[1] for n, v in base["ram"].items()}
    for label, x in data.items():
        assert {n: v[1] for n, v in x["ram"].items()} == ram, f"{label}: RAM symbols differ from rev0"
    assert ram["wKantoBadges"] == ram["wJohtoBadges"] + 1
    KEY_ITEMS = base["keyitems"]

    # The scripts (the AU build differs from the others only in the Game Corner's prizes, which are purchases).
    gift_by_build = {"rev0": gifts(()), "rev1": gifts(()), "au": gifts(("_CRYSTAL_AU",))}
    assert gift_by_build["rev0"] == gift_by_build["au"], "the AU build's gifts differ from the others"
    gift_rows = gift_by_build["rev0"]
    boss_by_build = {"rev0": boss_flags(()), "rev1": boss_flags(()), "au": boss_flags(("_CRYSTAL_AU",))}
    assert boss_by_build["rev0"] == boss_by_build["au"]
    boss_rows = {key: boss_by_build["rev0"][(cls, tid)] for key, cls, tid in BOSSES}
    for label, r in roms.items():
        misses = check_scripts(r, gift_rows, boss_rows)
        assert not misses, f"{label}: {misses[:20]}"

    # Landmarks against the app's tables.
    lm_names, lm_by_map = app_landmarks()
    for (g, n), info in base["maps"].items():
        lm = info["landmark"]
        assert lm in lm_names, f"{info['label']}: landmark {lm} isn't in MapSecDataCrystal"
        assert lm_by_map.get(g * 256 + n) == lm, f"{info['label']}: header landmark {lm} vs mapLandmarksCrystal {lm_by_map.get(g * 256 + n)}"
    gen2 = open(os.path.join(DATA_DIR, "Gen2Reader.kt"), encoding="utf-8").read()
    m = re.search(r"val GEN2_CRYSTAL_LEGACY = GEN2_CRYSTAL.copy\((.*?)\n\)" if LEGACY else r"val GEN2_CRYSTAL = Gen2Config\((.*?)\n\)", gen2, re.S)
    assert f"mapGroup = wramx(1, 0x{ram['wMapGroup']:04X})" in m.group(1), "wMapGroup isn't GEN2_CRYSTAL's mapGroup"

    # Falkner: Pidgey 7 (TACKLE, MUD_SLAP) and Pidgeotto 9 (TACKLE, MUD_SLAP, GUST).
    fk = base["bosses"]["FALKNER"]
    assert fk[2] == "FALKNER"
    if not LEGACY:
        assert fk[3] == (
            (7, SPECIES.v["PIDGEY"], 0, (MOVES.v["TACKLE"], MOVES.v["MUD_SLAP"])),
            (9, SPECIES.v["PIDGEOTTO"], 0, (MOVES.v["TACKLE"], MOVES.v["MUD_SLAP"], MOVES.v["GUST"]))), fk

        # FillMoves, on a trainer whose party has none: a Youngster's Rattata.
        r0 = roms["rev0"]
        assert fill_moves(r0, SPECIES.v["RATTATA"], 4) == [MOVES.v["TACKLE"], MOVES.v["TAIL_WHIP"]]

    # ---- area things, by landmark
    lm_of = {info["label"]: info["landmark"] for info in base["maps"].values()}
    tr = base["trades"]
    rows = set()
    for label, things in base["events"].items():
        lm = lm_of[label]
        if lm == 0:
            continue
        where = humanise(label, lm_names[lm])
        for kind, ident, flag, qty, wants in things:
            rows.add((lm, kind, ident, flag, qty, wants, where))
    for label, things in gift_rows.items():
        lm = lm_of[label]
        if lm == 0:
            continue
        where = humanise(label, lm_names[lm])
        for kind, ident, flag, qty, wants, owner, cmd in things:
            if kind == "TRADE":
                wanted, offered = tr[ident]
                rows.add((lm, "TRADE", offered, TRADE_FLAG_BASE + ident, 1, wanted, where))
            else:
                f = 0
                if flag:
                    assert flag in C.v, flag
                    f = C.v[flag]
                rows.add((lm, kind, ident, f, qty, wants, where))
    seen, area = set(), []
    for lm, kind, ident, flag, qty, wants, where in sorted(rows):
        key = (lm, kind, ident, flag, where) if kind in ("ITEM", "HIDDEN") else (lm, kind, ident, where)
        if key in seen:
            continue
        seen.add(key)
        area.append(f"{lm}|{kind}|{ident}|{flag}|{qty}|{wants}|{where}")

    # ---- wild Pokémon: one line per map and method
    wl = []
    for (g, n), (rates, slots) in sorted(base["grass"].items()):
        wl.append(f"G|{g}|{n}|{','.join(map(str, rates))}|" + " ".join(f"{lv}:{sp}" for lv, sp in slots))
    for (g, n), (rate, slots) in sorted(base["water"].items()):
        wl.append(f"W|{g}|{n}|{rate}|" + " ".join(f"{lv}:{sp}" for lv, sp in slots))
    fish_maps = 0
    for (g, n), info in sorted(base["maps"].items()):
        if info["fish"] and info["water"]:
            wl.append(f"F|{g}|{n}|{info['fish'] - 1}")
            fish_maps += 1
    for (g, n), ts in sorted(base["treemaps"].items()):
        if ts != C.v["TREEMON_SET_NONE"]:
            wl.append(f"T|{g}|{n}|{ts}")
    for (g, n), ts in sorted(base["rockmaps"].items()):
        wl.append(f"R|{g}|{n}|{ts}")
    fish_groups, time_groups = base["fish"], base["timefish"]
    bites = {b for b, rods in fish_groups}
    for i, (bite, rods) in enumerate(fish_groups):
        wl.append(f"FG|{i}|" + "|".join(" ".join(f"{c}:{sp}:{lv}" for c, sp, lv in rod) for rod in rods))
    for i, (day_sp, day_lv, nite_sp, nite_lv) in enumerate(time_groups):
        wl.append(f"TG|{i}|{day_sp}:{day_lv}|{nite_sp}:{nite_lv}")
    for i, (common, rare) in enumerate(base["trees"]):
        wl.append(f"TS|{i}|" + " ".join(f"{p}:{sp}:{lv}" for p, sp, lv in common) + "|" +
                  " ".join(f"{p}:{sp}:{lv}" for p, sp, lv in rare))

    # ---- bosses
    boss_lines = []
    for key, (c, t, name, mons) in base["bosses"].items():
        boss_lines.append(f"{key}|{c}|{t}|{name}|" + ";".join(f"{lv} {sp} {item} {','.join(map(str, mv))}" for lv, sp, item, mv in mons))
    flags = {}
    for key, cls, tid in BOSSES:
        flags[key] = BOSS_FLAGS.get(key) if LEGACY else boss_rows[key][0][2]
    used = sorted({f for f in flags.values() if f} | set(EXTRA_FLAGS))

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
          *(["// GENERATED by scripts/gen_crystal_guide.py legacy from Crystal Legacy v1.3.1's build (the patched cart byte for byte)",
             "// - do not hand-edit.\n"] if LEGACY else
            ["// GENERATED by scripts/gen_crystal_guide.py from pret/pokecrystal's three builds (rev 0, rev 1, Australia; each is the",
             "// retail ROM byte for byte, every table read from all three and compared) - do not hand-edit.\n"]),
          "// RAM, the same in every build: wEventFlags (NUM_EVENTS bits), wTimeOfDay (MORN 0, DAY 1, NITE 2),",
          "// wJohtoBadges (wKantoBadges follows it), wTradeFlags (a bit per NPC trade). WRAM bank 1 (wramx).",
          f"internal const val {P}EVENT_FLAGS = 0x{ram['wEventFlags']:04X}",
          f"internal const val {P}EVENT_FLAG_BYTES = {(C.v['NUM_EVENTS'] + 7) // 8}",
          f"internal const val {P}TIME_OF_DAY = 0x{ram['wTimeOfDay']:04X}",
          f"internal const val {P}BADGES = 0x{ram['wJohtoBadges']:04X}",
          f"internal const val {P}TRADE_FLAGS = 0x{ram['wTradeFlags']:04X}",
          f"internal const val {P}TRADE_FLAG_BYTES = {(C.v['NUM_NPC_TRADES'] + 7) // 8}",
          f"internal const val {P}MORN = 0",
          f"internal const val {P}DAY = 1",
          f"internal const val {P}NITE = 2",
          "",
          "// data/wild/probabilities.asm: the percent each slot is chosen with (7 grass slots, 3 water slots).",
          f"internal val {P}GRASS_ODDS = intArrayOf(" + ", ".join(map(str, base["grass_odds"])) + ")",
          f"internal val {P}WATER_ODDS = intArrayOf(" + ", ".join(map(str, base["water_odds"])) + ")",
          "",
          "// The event flags NEXT BOSS reads (constants/event_flags.asm; each boss's own found set by its script in every build).",
          ]
    for f in used:
        kt.append(f"internal const val {P}{f} = {C.v[f]}")
    kt.append("")
    kt.append(chunk(f"{P}WILD_RAW", wl,
                    "// Wild Pokémon. G|group|num|morn,day,nite rate (bytes)|21 x level:species (7 morning, 7 day, 7 night);\n"
                    "// W|group|num|rate|3 x level:species; F|group|num|fish group (a map with water in its blocks), T / R|group|num|\n"
                    "// headbutt / rock set; FG|group|old|good|super (share of 256:species:level, species 0 = TG time group\n"
                    "// <level>); TG|n|daySpecies:dayLevel|niteSpecies:niteLevel (morning counts as day); TS|set|common|rare\n"
                    "// (percent:species:level; set 0 has none). F's group is FG's index (the header's FISHGROUP_* minus 1).\n"))
    kt.append(chunk(f"{P}BOSSES_RAW", boss_lines,
                    "// Bosses: key|class|party|name|level species item moves;... (a party with no moves of its own gets the\n"
                    "// level-up moves the game gives it).\n"))
    kt.append(chunk("GUIDE_AREAS_CRYSTAL_LEGACY_RAW" if LEGACY else "GUIDE_AREAS_CRYSTAL_RAW", area,
                    f"// HERE's areas: {len(area)} things in {len({a.split('|')[0] for a in area})} landmarks. Line format: "
                    "landmark|kind|id|flag|qty|wants|where (GuideAreas.kt);\n// a trade's flag is 0x20000 + its wTradeFlags bit. "
                    "Item ids are Crystal's own (a TM or HM is its item id).\n"))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(kt))

    # ---- report
    kinds = {}
    for a in area:
        kinds[a.split("|")[1]] = kinds.get(a.split("|")[1], 0) + 1
    by = {}
    for x in wl:
        by[x.split("|")[0]] = by.get(x.split("|")[0], 0) + 1
    print("wrote", os.path.relpath(OUT, ROOT))
    print("wild lines:", by, "| bosses:", len(boss_lines), "| area:", kinds, f"in {len({a.split('|')[0] for a in area})} landmarks")
    print("RAM:", {n: f"0x{v:04X}" for n, v in ram.items()})
    print("bite chance bytes:", sorted(bites), "| time groups:", len(time_groups), "| fish maps:", fish_maps)
    print("boss flags:", flags)
    print("swarm tables:", {k: sorted(v) for k, v in zip(("grass", "water"), base["swarm"])})
    print("all three ROMs agree; scripts found in all three")


if __name__ == "__main__":
    main()
