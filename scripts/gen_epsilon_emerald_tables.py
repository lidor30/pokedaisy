#!/usr/bin/env python3
"""Generates Pokémon Epsilon Emerald's tables (v1.0.0, pokemerrp's demake of the 3DS Hoenn remakes; BPEE, 32 MB, closed)
from the player's ROM (nothing is downloaded).

    scripts/gen_epsilon_emerald_tables.py <Pokemon Epsilon Emerald (v1.0.0).gba>

It runs as EMERALD with these laid over Emerald's tables (ActiveTables' `emeraldHack`, `hackTextEpsilonEmerald`). Its engine
is a gcc pokeemerald on an older pokeemerald-expansion battle engine, but it kept Emerald's species and item ids (its own
species from 412, its own items from 377) and the expansion's move ids (Emerald's 1..354, then on to 828). Everything is
read from the ROM:

- the GF header (0x08000100): gSpeciesNames (+0x44, 11 bytes), gMoveNames (+0x48, 13 bytes), gBaseStats (+0xBC: 36-byte
  records, types +6 / +7, gender ratio +0x12, growth rate +0x15), gItems (+0xC8: 36-byte records, name[14], itemId +0x0E,
  pocket +0x1A) and gBattleMoves (+0xCC: 24-byte records, power u16 +2, type +4);
- the type chart, a 19 x 19 table of uq4.12 multipliers (expansion's sTypeEffectivenessTable; FAIRY is type 18), found by
  its NORMAL row; gTypeNames by "Fairy", its 19th (7 bytes each);
- gRegionMapEntries (8 bytes: x, y, width, height, name) through the pointer to "LITTLEROOT TOWN": Emerald's boxes with
  seven sections of its own (SECRET MEADOW, ICE OASIS ...), whose names are written here.

Its text is mixed case ("Bulbasaur", "Potion"; the map in capitals), so names are written as the ROM has them (`ownCase`).
"""
import hashlib
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_emerald_lang_tables import CHARS  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin",
                   "com", "pokedaisy", "app", "companion", "data", "EpsilonEmeraldGen.kt")
SHA1 = "8decf0571ba3f17d31c76168346e240a14e248ce"
EMERALD_SHA1 = "f3ae088181bf583e55daf962a92bb46f4f1d07b7"
B = 0x08000000
SPECIES_EGG = 446          # a blank record between OBSTAGOON (445) and the UNOWN forms
LAST_SPECIES = 564         # gMonIconTable's 565 entries
MOVES = 829                # Z-moves (BREAKNECK BLITZ ...) from here
TYPES = 19
BASE_STATS_SIZE = 36
ITEM_SIZE = 36
MOVE_SIZE = 24
# ROM pocket ids -> the app's POCKET_* (Telemetry.kt).
POCKETS = {1: 0, 2: 1, 3: 2, 4: 3, 5: 4}
CH = dict(CHARS)


def u8(d, a):
    return d[a - B]


def u16(d, a):
    return struct.unpack_from("<H", d, a - B)[0]


def u32(d, a):
    return struct.unpack_from("<I", d, a - B)[0]


def text(d, a, maxlen):
    out = []
    for b in d[a - B:a - B + maxlen]:
        if b == 0xFF:
            break
        if b in (0xFA, 0xFB, 0xFE):
            out.append(" ")
            continue
        if b not in CH:
            return None
        out.append(CH[b])
    s = " ".join("".join(out).split())
    return s if s and set(s) not in ({"?"}, {"-"}) else None


def encode(s):
    rev = {v: k for k, v in CH.items()}
    return bytes(rev[c] for c in s) + b"\xff"


def header(d, off):
    return struct.unpack_from("<I", d, 0x100 + off)[0]


def find_chart(d):
    row = [1, 1, 1, 1, 1, .5, 1, 0, .5, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
    pat = b"".join(struct.pack("<H", int(x * 0x1000)) for x in row)
    i = d.find(pat)
    assert i >= 0 and d.find(pat, i + 1) < 0, "the type chart's NORMAL row isn't found once"
    out = {}
    for atk in range(TYPES):
        for dfn in range(TYPES):
            x = struct.unpack_from("<H", d, i + 2 * (atk * TYPES + dfn))[0]
            if x != 0x1000:
                out[atk * 100 + dfn] = x * 100 // 0x1000
    return B + i, out


def read(d):
    names, moves, info, items, battle = header(d, 0x44), header(d, 0x48), header(d, 0xBC), header(d, 0xC8), header(d, 0xCC)
    sp, ty, gr, gw = {}, {}, [], []
    for i in range(LAST_SPECIES + 1):
        a = info + BASE_STATS_SIZE * i
        if 0 < i and i != SPECIES_EGG:
            n = text(d, names + 11 * i, 11)
            if n:
                sp[i] = n
                ty[i] = (u8(d, a + 6), u8(d, a + 7))
        gr.append(u8(d, a + 0x12))
        gw.append(u8(d, a + 0x15))
    mv = {}
    for i in range(1, MOVES):
        a = battle + MOVE_SIZE * i
        mv[i] = (text(d, moves + 13 * i, 13), u8(d, a + 4), min(u16(d, a + 2), 255))
    it, pockets = {}, {}
    i = 1
    while u16(d, items + ITEM_SIZE * i + 0x0E) in (i, 0):
        a = items + ITEM_SIZE * i
        n = text(d, a, 14)
        if n and u16(d, a + 0x0E) == i:
            it[i] = n
            p = POCKETS.get(u8(d, a + 0x1A))
            if p is not None:
                pockets[i] = p
        i += 1
    tm_items = sorted(k for k, p in pockets.items() if p == 2)
    tn_at = d.find(encode("Fairy")) - 7 * 18
    tn = {k: text(d, B + tn_at + 7 * k, 7) for k in range(TYPES)}
    chart_at, chart = find_chart(d)
    lr = d.find(encode("LITTLEROOT TOWN"))
    ref = d.find(struct.pack("<I", B + lr))
    entries = B + ref - 4
    ms = {}
    for k in range(0xD5):
        p = u32(d, entries + 8 * k + 4)
        ms[k] = text(d, p, 32) if B <= p < B + len(d) else None
    return dict(species=sp, types=ty, gender=gr, growth=gw, moves=mv, items=it, pockets=pockets, tm_items=tm_items,
                type_names=tn, chart=chart, chart_at=chart_at, mapsecs=ms, entries=entries)


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    d = open(sys.argv[1], "rb").read()
    if hashlib.sha1(d).hexdigest() != SHA1:
        sys.exit("not Pokemon Epsilon Emerald v1.0.0 (sha1 8decf057...)")
    t = read(d)
    # What the game shows: CLEFAIRY is Fairy, its chart's FAIRY row hits DRAGON twice, ROOST is 355, TM51 follows HM08.
    assert t["species"][1] == "Bulbasaur" and t["species"][445] == "Obstagoon" and t["types"][35] == (18, 18)
    assert t["type_names"][18] == "Fairy" and t["type_names"][0] == "Normal" and t["chart"][18 * 100 + 16] == 200
    assert t["moves"][355][0] == "Roost" and t["moves"][828][0] == "Hydro Steam" and t["moves"][1] == ("Pound", 0, 40)
    assert t["items"][13] == "Potion" and t["items"][339] == "HM01" and len(t["tm_items"]) == 118
    assert t["tm_items"][:58] == list(range(289, 347))
    # Emerald's sections but seven, which the reader renames (their boxes stay Emerald's).
    base = {}
    if len(sys.argv) == 3:
        em = open(sys.argv[2], "rb").read()
        assert hashlib.sha1(em).hexdigest() == EMERALD_SHA1, "the second ROM isn't retail Emerald"
        bt = 0x085A147C
        for k in range(0xD5):
            base[k] = text(em, u32(em, bt + 8 * k + 4), 32)
    # In the shared Emerald table's casing ("Fiery Path"): the app cases them as the game does.
    secs = {k: v.title() for k, v in t["mapsecs"].items() if v and (not base or (base.get(k) or "").upper() != v.upper())}
    if base:
        assert sorted(secs) == [54, 66, 75, 77, 200], secs   # SECRET MEADOW, SECRET SHORE, ICE OASIS, SECRET ISLET, DELTA METEOR
    rows = lambda m, f: "".join(f"            {k} to {f(v)},\n" for k, v in sorted(m.items()))
    out = ["package com.pokedaisy.app.companion.data\n\n",
           "// GENERATED by scripts/gen_epsilon_emerald_tables.py from the Pokemon Epsilon Emerald v1.0.0 ROM - do not hand-edit.\n",
           "// Names in the ROM's own casing, by its ids (Emerald's species and items, its own past them; the expansion's moves),\n",
           f"// laid over Emerald's (ActiveTables' emeraldHack). Type chart at 0x{t['chart_at']:08X}, gRegionMapEntries at 0x{t['entries']:08X}.\n\n",
           "internal val hackTextEpsilonEmerald: HackText by lazy {\n    HackText(\n",
           "        species = speciesNamesEpsilonEmerald,\n",
           "        types = speciesTypesEpsilonEmerald,\n",
           "        moves = movesEpsilonEmerald,\n",
           "        items = itemNamesEpsilonEmerald,\n",
           "        mapSecs = mapOf(\n", rows(secs, kstr), "        ),\n",
           "        typeNames = mapOf(18 to \"Fairy\"),\n",
           "        typeChart = typeChartEpsilonEmerald,\n",
           "        genderRatios = unhexRatios(\"" + bytes(t["gender"]).hex() + "\"),\n",
           "        growthRates = unhexBytes(\"" + bytes(t["growth"]).hex() + "\"),\n",
           "        ownCase = true,\n    )\n}\n\n",
           "// One lazy table each: a single initializer of them all would pass the JVM's 64 KB a method.\n",
           "private val speciesNamesEpsilonEmerald: Map<Int, String> by lazy {\n    mapOf(\n", rows(t["species"], kstr), "    )\n}\n\n",
           "private val speciesTypesEpsilonEmerald: Map<Int, SpeciesTypes> by lazy {\n    mapOf(\n",
           rows(t["types"], lambda v: f"SpeciesTypes({v[0]}, {v[1]})"), "    )\n}\n\n",
           "private val movesEpsilonEmerald: Map<Int, MoveInfo> by lazy {\n    mapOf(\n",
           rows({k: v for k, v in t["moves"].items() if v[0]}, lambda v: f"MoveInfo({kstr(v[0])}, {v[1]}, {v[2]})"), "    )\n}\n\n",
           "private val itemNamesEpsilonEmerald: Map<Int, String> by lazy {\n    mapOf(\n", rows(t["items"], kstr), "    )\n}\n\n",
           "/** Its 19 x 19 uq4.12 chart (atk * 100 + def -> percent, 100s left out). */\n",
           "internal val typeChartEpsilonEmerald: Map<Int, Int> by lazy {\n    mapOf(\n",
           "".join(f"        {k} to {v},\n" for k, v in sorted(t["chart"].items())), "    )\n}\n\n",
           "/** Each item's own pocket (gItems +0x1A, as the app's POCKET_*): its bag shows a pocket's items only, so the slots its\n",
           " * Items pocket shares with an Emerald save's old Key Items and Poké Balls stay out. */\n",
           "internal val ITEM_POCKETS_EPSILON_EMERALD: Map<Int, Int> by lazy {\n    mapOf(\n",
           "".join(f"        {k} to {v},\n" for k, v in sorted(t["pockets"].items())), "    )\n}\n\n",
           "/** The TM pocket's bits in order: TM01 .. TM50, HM01 .. HM08, TM51 .. TM110 (the TM pocket's items by id). */\n",
           "internal val TM_ITEMS_EPSILON_EMERALD: IntArray = intArrayOf(\n",
           "".join(f"    {', '.join(str(x) for x in t['tm_items'][i:i + 16])},\n" for i in range(0, len(t['tm_items']), 16)), ")\n"]
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("".join(out))
    print("wrote", os.path.relpath(OUT), f": {len(t['species'])} species, {sum(1 for v in t['moves'].values() if v[0])} moves, "
          f"{len(t['items'])} items, {len(secs)} map sections, {len(t['chart'])} chart entries")


if __name__ == "__main__":
    main()
