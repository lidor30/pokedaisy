#!/usr/bin/env python3
"""Generates Super Mariomon's display tables from the ROMs the user owns (Too
Many Productions' Mario-themed Emerald hack; no source; nothing is downloaded).

    scripts/gen_mariomon_tables.py <rom.gba>... [--out DIR]

Pass every release you have (v1.2.1, v1.2.2, v1.3, v1.5.1-Anniversary,
v1.5.2-Anniversary; told apart by SHA1). Writes {SpeciesNames,SpeciesTypes,
GenderRatios,ItemNames,MoveData,TypeChart,MapSecData}Mariomon.kt (default: the
app's data package). Item descriptions aren't written: the app reads them from
the player's ROM (RomItemText, NativeConfig.itemDescs).

The releases come in two sets with the same tables inside each (the script checks
that every ROM of a set gives the same names): v1.5.x (`...Mariomon`) and
v1.2.1-v1.3 (`...MariomonV13`); items, type names and the type chart are the same
in all five. Every address below is in the ROM's own GF header (0x08000100:
names, base stats, items, moves) or a literal-pool word of the code that uses it
(the same code in every release):
  - gSpeciesNames: 11-byte names (title-case vanilla species 1..899 are still
    there, unused); the Mario species start at 906 (PIRANHA P.), in the ROM's
    own capitals. NUM_SPECIES 1402.
  - gBaseStats: 0x24-byte entries (an old pokeemerald-expansion's): types
    +6/+7, genderRatio +0x12 (BOB-OMB 912: Fire/Fire, 127).
  - gMoveNames: 17-byte names; gBattleMoves 0x14-byte entries {u16 effect,
    u8 power, u8 type, ...} (Tackle 40 Normal, Ember 40 Fire, Charge Electric).
    MOVES_COUNT 840 (v1.5) / 833, the Z-move names follow.
  - gItems: 0x28-byte struct Item (name[14], u16 price +0xE, description*
    +0x14, pocket +0x19): item 1 Plumber Cap (POKE BALL), 28 Mushroom (POTION).
  - gTypeNames: [19][7], vanilla's ids and FAIRY 18 ("???" 9 unused).
    gTypeEffectiveness: [19][19] u32 uq4.12 (0x1000 = 1x; its only reader is
    a `mul 76` lookup), the chart is the Gen 6+ one.
  - gRegionMapEntries {x, y, w, h, name*} by mapsec id (0xD5, Emerald's ids
    renamed: WORLD 1-1 is 0x65), on its own map (Emerald's region_map.c, the
    art RomArt's MM_REGION_*); the cursor grid (sRegionMap_MapSectionLayout,
    [15][28], GetMapSecIdAt's pool) sits at the screen's (1, 2) like Emerald's.
"""
import hashlib
import os
import struct
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shared", "src", "commonMain", "kotlin",
                       "com", "pokedaisy", "app", "companion", "data")

# sha1 -> (label, table set, addresses)
VERSIONS = {
    "43dd208b23eade69aa3384301e934ecafbf6c2b3": ("v1.2.1", "V13", dict(
        species=0x0840E1A8, moves=0x08415A0E, stats=0x08427A6C, items=0x087EE200, battle=0x08420220,
        typeNames=0x084198F8, chart=0x0841D570, entries=0x08817934, grid=0x08816E8A)),
    "08d1c275c0ba4d559b33427292702d61ad49fb5c": ("v1.2.2", "V13", dict(
        species=0x0840E188, moves=0x084159EE, stats=0x08427A4C, items=0x087EE1E4, battle=0x08420200,
        typeNames=0x084198D8, chart=0x0841D550, entries=0x08817924, grid=0x08816E7A)),
    "043512c2497871c018df606fb90ffbd4c81430f4": ("v1.3", "V13", dict(
        species=0x0840E148, moves=0x084159AE, stats=0x08427A0C, items=0x087EE1C8, battle=0x084201C0,
        typeNames=0x08419898, chart=0x0841D510, entries=0x08817908, grid=0x08816E5E)),
    "831fe0f97301f17b24a9cd4a64ef0520835c4dde": ("v1.5.1-Anniversary", "", dict(
        species=0x0841F5A8, moves=0x08426E24, stats=0x08439060, items=0x0880C8DC, battle=0x08431730,
        typeNames=0x0842ADD4, chart=0x0842EA4C, entries=0x08836BAC, grid=0x088360F6)),
    "06e79d12afa578db97c23c1f7ba771fe2f8fe905": ("v1.5.2-Anniversary", "", dict(
        species=0x0841FA38, moves=0x084272B4, stats=0x084394F0, items=0x0880CD90, battle=0x08431BC0,
        typeNames=0x0842B264, chart=0x0842EEDC, entries=0x08837060, grid=0x088365AA)),
}
NUM_SPECIES = 1402
MOVES_COUNT = {"": 840, "V13": 833}
ITEM_LIMIT = 1000
MAPSEC_COUNT = 0xD5          # MAPSEC_NONE
GRID_W, GRID_H, GRID_X, GRID_Y = 28, 15, 1, 2

TYPE_NAMES = {0: "Normal", 1: "Fighting", 2: "Flying", 3: "Poison", 4: "Ground", 5: "Rock", 6: "Bug", 7: "Ghost",
              8: "Steel", 10: "Fire", 11: "Water", 12: "Grass", 13: "Electric", 14: "Psychic", 15: "Ice",
              16: "Dragon", 17: "Dark", 18: "Fairy"}  # 9 "???": no species / move uses it
TYPE_COUNT = 19

CHARS = {0x00: " ", 0x1B: "é", 0xAB: "!", 0xAC: "?", 0xAD: ".", 0xAE: "-", 0xB0: "…", 0xB1: "“", 0xB2: "”",
         0xB3: "‘", 0xB4: "'", 0xB5: "♂", 0xB6: "♀", 0xB8: ",", 0xBA: "/", 0xF0: ":", 0x2D: "&", 0x5C: "(",
         0x5D: ")", 0x5B: "%", 0x35: "=", 0x36: ";", 0x53: "PK", 0x54: "MN"}
CHARS.update({0xA1 + i: str(i) for i in range(10)})
CHARS.update({0xBB + i: chr(65 + i) for i in range(26)})
CHARS.update({0xD5 + i: chr(97 + i) for i in range(26)})

# Spot checks against what the game shows (the user's save: BOB-OMB Lv6 with Tackle / Charge /
# Self-Destruct / Ember, Fire type; 2 Mushrooms, 10 Plumber Caps, the HAMMER; WORLD 1-1; a
# scripted wild GOOMBA, Normal; the TATTLEDEX's No.001 PIRANHA P.).
CHECKS = dict(species={1: "Bulbasaur", 906: "PIRANHA P.", 912: "BOB-OMB", 915: "GOOMBA", 943: "YOSHI"},
              types={912: (10, 10), 915: (0, 0), 906: (12, 12)},
              gender={912: 31},
              growth={912: 3, 915: 0},  # MEDIUM_SLOW as its dex says; GOOMBA's 125 EXP at Lv5 = 5^3
              moves={33: ("Tackle", 0, 40), 52: ("Ember", 10, 40), 120: ("Self-Destruct", 0, 200),
                     268: ("Charge", 13, 0)},
              items={1: "Plumber Cap", 28: "Mushroom", 120: "Escape Rope", 461: "Exp. Share", 802: "Hammer"},
              mapsecs={0x00: "Toad Town", 0x65: "World 1-1", 0x58: "Mario's Pad", 0x0E: "Bowser's Castle"})


def text(rom, a, maxlen):
    out = []
    for b in rom[a - 0x08000000:a - 0x08000000 + maxlen]:
        if b == 0xFF:
            break
        if b not in CHARS:
            return None
        out.append(CHARS[b])
    s = " ".join("".join(out).split())
    return s if s and set(s) != {"?"} and s != "-" else None


def u8(rom, a):
    return rom[a - 0x08000000]


def u16(rom, a):
    return struct.unpack_from("<H", rom, a - 0x08000000)[0]


def u32(rom, a):
    return struct.unpack_from("<I", rom, a - 0x08000000)[0]


def is_ptr(rom, v):
    return 0x08000000 <= v < 0x08000000 + len(rom)


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def title(s):
    """Map sections are capitals in the ROM ("WORLD 1-1", "MARIO's PAD"); the app's tables are title case."""
    words = []
    for w in s.split(" "):
        words.append(w if w.count(".") >= 2 else w[:1] + w[1:].lower())
    return " ".join(words)


def read(rom, a, tset):
    """Every table of one release."""
    names, types, gender, growth = {}, {}, {}, {}
    for i in range(1, NUM_SPECIES):
        n = text(rom, a["species"] + 11 * i, 11)
        if not n:
            continue
        names[i] = n
        b = a["stats"] + 0x24 * i
        types[i] = (u8(rom, b + 6), u8(rom, b + 7))
        gender[i] = u8(rom, b + 0x12)
        growth[i] = u8(rom, b + 0x15)
    moves = []
    for i in range(1, MOVES_COUNT[tset]):
        n = text(rom, a["moves"] + 17 * i, 17)
        if n:
            b = a["battle"] + 0x14 * i
            moves.append((i, n, u8(rom, b + 3), u8(rom, b + 2)))
    items = {}
    for i in range(1, ITEM_LIMIT):
        # The table ends where the records stop pointing at a description (ITEMS_COUNT: 821 / 824).
        if not is_ptr(rom, u32(rom, a["items"] + 0x28 * i + 0x14)):
            break
        n = text(rom, a["items"] + 0x28 * i, 14)
        if n:
            items[i] = n
    chart = {}
    for atk in TYPE_NAMES:
        for dfn in TYPE_NAMES:
            v = u32(rom, a["chart"] + 4 * (atk * TYPE_COUNT + dfn))
            if v != 0x1000:
                chart[atk * 100 + dfn] = v * 100 // 0x1000
    tnames = {i: text(rom, a["typeNames"] + 7 * i, 7) for i in TYPE_NAMES}
    grid = rom[a["grid"] - 0x08000000:a["grid"] - 0x08000000 + GRID_W * GRID_H]
    on_grid = set(grid) - {MAPSEC_COUNT}
    mapsecs, rows = {}, []
    for i in range(MAPSEC_COUNT):
        e = a["entries"] + 8 * i
        p = u32(rom, e + 4)
        s = text(rom, p, 24) if is_ptr(rom, p) else None
        if not s:
            continue
        mapsecs[i] = title(s)
        x, y, w, h = (u8(rom, e + k) for k in range(4))
        # Emerald's leftover sections keep rects off this map (ROUTE 101 at x 35): not on the map,
        # nor are the 0 / 0 ones, unless the grid names the section.
        if (x, y) != (0, 0) and w and h and x + w <= GRID_W and y + h <= GRID_H:
            rows.append(f"    {i} to MapSecInfo({kstr(mapsecs[i])}, 0, {x + GRID_X}, {y + GRID_Y}, {w}, {h}),\n")
        elif i in on_grid:
            rows.append(f"    {i} to MapSecInfo({kstr(mapsecs[i])}, 0, 0, 0, 0, 0),\n")
        else:
            rows.append(f"    {i} to MapSecInfo({kstr(mapsecs[i])}, -1, 0, 0, 0, 0),\n")
    return dict(names=names, types=types, gender=gender, growth=growth, moves=moves, items=items, chart=chart, tnames=tnames,
                mapsecs=mapsecs, rows=rows, grid=grid, on_grid=on_grid)


def check(t, label):
    assert all(t["names"].get(i) == n for i, n in CHECKS["species"].items()), f"{label}: species names are wrong"
    assert all(t["types"].get(i) == v for i, v in CHECKS["types"].items()), f"{label}: species types are wrong"
    assert all(t["gender"].get(i) == v for i, v in CHECKS["gender"].items()), f"{label}: gender ratios are wrong"
    assert all(t["growth"].get(i) == v for i, v in CHECKS["growth"].items()), f"{label}: growth rates are wrong"
    info = {i: (n, ty, p) for i, n, ty, p in t["moves"]}
    assert all(info.get(i) == v for i, v in CHECKS["moves"].items()), f"{label}: moves are wrong"
    assert all(t["items"].get(i) == n for i, n in CHECKS["items"].items()), f"{label}: item names are wrong"
    assert all(t["tnames"][i] is not None for i in TYPE_NAMES) and t["tnames"][18] == "Fairy", f"{label}: type names"
    c = t["chart"]
    assert c[0 * 100 + 7] == 0 and c[10 * 100 + 12] == 200 and c[13 * 100 + 4] == 0 \
        and c[16 * 100 + 18] == 0 and c[18 * 100 + 16] == 200, f"{label}: chart is wrong"
    assert all(t["mapsecs"].get(i) == n for i, n in CHECKS["mapsecs"].items()), f"{label}: map sections are wrong"
    assert t["on_grid"] <= set(t["mapsecs"]), f"{label}: grid names unknown sections"
    assert t["grid"][7 * GRID_W + 10] == 0x65, f"{label}: grid is wrong (WORLD 1-1 at 10, 7)"


def header(what):
    return (f"package com.pokedaisy.app.companion.data\n\n"
            f"// GENERATED by scripts/gen_mariomon_tables.py from the Super Mariomon ROMs - do not\n"
            f"// hand-edit. {what}\n")


def write(name, body):
    path = os.path.join(OUT_DIR, name)
    with open(path, "w") as f:
        f.write(body)
    print("wrote", os.path.relpath(path))


def main():
    global OUT_DIR
    args = sys.argv[1:]
    if "--out" in args:
        i = args.index("--out")
        OUT_DIR = args[i + 1]
        del args[i:i + 2]
    if not args:
        sys.exit(f"usage: {sys.argv[0]} <rom.gba>... [--out DIR]")
    sets = {}
    for path in args:
        rom = open(path, "rb").read()
        sha = hashlib.sha1(rom).hexdigest()
        if sha not in VERSIONS:
            sys.exit(f"{path}: not a known Super Mariomon release (sha1 {sha})")
        label, tset, a = VERSIONS[sha]
        t = read(rom, a, tset)
        check(t, label)
        if tset in sets:
            first_label, first = sets[tset]
            for k in ("names", "types", "gender", "growth", "moves", "items", "chart", "mapsecs", "grid"):
                assert t[k] == first[k], f"{label} and {first_label} differ in {k}"
        else:
            sets[tset] = (label, t)
        print(f"{label}: {len(t['names'])} species, {len(t['moves'])} moves, {len(t['items'])} items, "
              f"{len(t['chart'])} chart pairs, {len(t['mapsecs'])} map sections")
    if set(sets) != {"", "V13"}:
        sys.exit("need a ROM of each set (v1.5.x and v1.2.1-v1.3) to write both")
    new, old = sets[""][1], sets["V13"][1]
    # v1.5 added three items at the end (MUSIC PLAYER, RESET BEAN, VS. RECORDER): one table serves both.
    assert old["items"].items() <= new["items"].items() and new["chart"] == old["chart"] and new["tnames"] == old["tnames"]
    sfx = {"": "v1.5.1 / v1.5.2-Anniversary", "V13": "v1.2.1 / v1.2.2 / v1.3"}

    def both(name, decl, body):
        return "".join(f"// {sfx[s]}\n{decl(s)}" + body(sets[s][1]) + "\n" for s in ("", "V13")).rstrip("\n") + "\n"

    write("SpeciesNamesMariomon.kt", header("gSpeciesNames, keyed by the game's own species ids\n"
          "// (the Mario species from 906; vanilla's 1..899 are left in the ROM, unused).\n") + "\n" +
          both("names", lambda s: f"val speciesNamesMariomon{s}: Map<Int, String> = mapOf(\n",
               lambda t: "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(t["names"].items())) + ")\n"))
    write("SpeciesTypesMariomon.kt", header("gBaseStats types, ids per typeNamesMariomon.\n") + "\n" +
          both("types", lambda s: f"val speciesTypeDataMariomon{s}: Map<Int, SpeciesTypes> = mapOf(\n",
               lambda t: "".join(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(t["types"].items())) + ")\n"))
    write("GenderRatiosMariomon.kt", header("gBaseStats genderRatio by species id (0 always\n"
          "// male, 254 always female, 255 genderless, else female when (personality & 0xFF) < ratio),\n"
          "// and growthRate (+0x15: vanilla's GROWTH_* ids, ExpTables.kt's curves).\n") + "\n" +
          both("gender", lambda s: f"val genderRatiosMariomon{s}: IntArray by lazy {{ unhexRatios(\"",
               lambda t: "".join("%02x" % t["gender"].get(i, 255) for i in range(max(t["gender"]) + 1)) + "\") }\n") + "\n" +
          both("growth", lambda s: f"val growthRatesMariomon{s}: IntArray by lazy {{ unhexRatios(\"",
               lambda t: "".join("%02x" % t["growth"].get(i, 255) for i in range(max(t["growth"]) + 1)) + "\") }\n"))
    write("MoveDataMariomon.kt", header("gMoveNames + gBattleMoves type / power (type ids per\n"
          "// typeNamesMariomon).\n") + "\n" +
          both("moves", lambda s: f"val moveDataMariomon{s}: Map<Int, MoveInfo> = mapOf(\n",
               lambda t: "".join(f"    {i} to MoveInfo({kstr(n)}, {ty}, {p}),\n" for i, n, ty, p in t["moves"]) + ")\n"))
    write("ItemNamesMariomon.kt", header("gItems names, keyed by item id (the same in every\n"
          "// release; v1.5 added 821-823).\n") +
          "val itemNamesMariomon: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(new["items"].items())) + ")\n")
    write("TypeChartMariomon.kt", header("Vanilla type ids plus Fairy 18 (9 \"???\" unused), the\n"
          "// same in every release. gTypeEffectiveness [19][19] u32 uq4.12; key = atkType*100+defType ->\n"
          "// percent, pairs not listed are 100.\n") +
          "val typeNamesMariomon: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(s)},\n" for i, s in sorted(TYPE_NAMES.items())) + ")\n\n" +
          "val typeEffectivenessMariomon: Map<Int, Int> = mapOf(\n" +
          "".join(f"    {k} to {v},\n" for k, v in sorted(new["chart"].items())) + ")\n")
    write("MapSecDataMariomon.kt", header("gRegionMapEntries by mapsec id, rects in 8px tiles\n"
          "// on regionmap/mariomon.png (RomArt rebuilds it from the ROM; region -1 = not on the map). The\n"
          "// grids are its sRegionMap_MapSectionLayout, for the map cursor (v1.5 added GLITZ PIT, 0xA7).\n") +
          "val regionMapImagesMariomon = arrayOf(\"mariomon\")\n\n" +
          both("rows", lambda s: f"val mapSecDataMariomon{s}: Map<Int, MapSecInfo> = mapOf(\n",
               lambda t: "".join(t["rows"]) + ")\n") + "\n" +
          both("grid", lambda s: f"internal val regionLayoutsMariomon{s}: List<RegionLayout> = listOf(\n",
               lambda t: f"    RegionLayout({GRID_X}, {GRID_Y}, {GRID_W}, {GRID_H}, 0x{MAPSEC_COUNT:02X}, listOf(\n"
                         f"        hexBytes(\"{t['grid'].hex()}\"),\n    )),\n)\n"))


if __name__ == "__main__":
    main()
