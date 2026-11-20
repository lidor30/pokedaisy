#!/usr/bin/env python3
"""Generates Pokémon R.O.W.E.'s display tables from the ROM the user owns
(BelialClover's open-world Emerald; v2.x has no public source, 1.9.4's is
github.com/BelialClover/RoweSource; nothing is downloaded).

    scripts/gen_rowe_tables.py <rom.gba> [out_dir]

Writes {SpeciesNames,SpeciesTypes,GenderRatios,ItemNames,MoveData,TypeChart,
MapSecData}Rowe.kt (default: the app's data package).
Item descriptions aren't written: the app reads them from the player's ROM
(RomItemText, NativeConfig.itemDescs).

R.O.W.E. v2.1.9.1 Experimental (BPEE, 32 MB, header title "POKEMON EMER")
keeps vanilla pokeemerald's table *shapes* (names in their own arrays, not
inline like pokeemerald-expansion), grown. Every address below is also in the
ROM's own symbol table at 0x08FB0670 ({char *name, u32 addr} x 150, for its
online play: "gSpeciesNames 084180A4", "gBaseStats 08502C54", ...) and its GF
header at 0x08000100:
  - gSpeciesNames 0x084180A4: 13-byte names (12 chars + EOS), title case, 1960
    entries (up to gMoveNames); National Dex ids 1..1025 (Pecharunt), then
    form species from 1551 (gFormSpeciesIdTables 0x085E700C maps a species +
    the box's 5-bit formId to them: Rattata form 1 = 1551). SPECIES_EGG is 2300.
  - gBaseStats 0x08502C54: 0x40-byte entries - base stats u16 x6 +0..+0xB,
    types +0xC/+0xD (vanilla ids 0-18: 9 Mystery, 18 Fairy), catchRate +0xE,
    genderRatio +0x18, abilities u16 +0x1E/+0x20 (hidden +0x24), natDexNum
    u16 +0x2C. Bulbasaur 45/49/49/45/65/65 Grass/Poison 31, Duraludon Steel/Dragon.
  - gTypeEffectiveness (battle_util.c's sTypeEffectivenessTable) 0x0842C182:
    [19][19] u16 uq4.12 (0x1000 = 1x), Gen 6+ rules (an inverse table follows).
  - gMoveNames 0x0841E42C: 17-byte names ("Pound" 1, "Tackle" 33, "Metal Claw"
    232). gBattleMoves 0x08435298: 0x38-byte entries {u16 effect; u8 power;
    u8 type; u8 accuracy; u8 pp; ...} (Tackle 40 Normal, Metal Claw 60 Steel).
  - gItems 0x08F76D78: 0x38-byte entries, name inline (18 bytes), itemId u16
    +0x12, price +0x14, description* +0x1C, pocket +0x22 (1 Items, 2 Medicine,
    3 Balls, 4 Battle Items, 5 Type Items, 6 Mega Stones, 7 Berries, 8 Power Up,
    9 TMs/HMs, 10 Key Items). 1 Poké Ball (4 is a second "Poké Ball"), 28 Potion,
    90 Repel, 214 Exp. Share (a key item), 467 Bike.
  - gRegionMapEntries 0x08FD278C: 8-byte {x, y, w, h, name*} by mapsec id, 0xD5
    of them (u8 ids, MAPSEC_NONE 0xD5): Emerald's ids (0 Littleroot .. 0x57,
    Kanto 0x58.., Sevii 0x8F..). A second copy follows at 0x08FD2E34.
  - Region maps: Emerald's region_map.c with three maps, picked by
    gMapHeader.region (+0x19: 0 Hoenn, 1 Kanto, 2 Sevii). Each is 8bpp tiles
    + a 64x64 affine tilemap (LZ77) on one 32-colour palette loaded at index 112
    (sRegionMapBg_Pal 0x08FCE754): Hoenn 0x08FCE794 / 0x08FCF604, Kanto
    0x08FCFB34 / 0x08FD069C, Sevii 0x08FD0B9C / 0x08FD17C4 (RomArt's RW_*).
    The cursor grids [15][28] (GetMapSecIdAt 0x0817A860): Hoenn 0x08FCF950,
    Kanto 0x08FD09B8, Sevii 0x08FD1A80, at the screen's (1, 2) on all three.
"""
import hashlib
import os
import struct
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shared", "src", "commonMain", "kotlin",
                       "com", "pokedaisy", "app", "companion", "data")

SHA1 = "81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77"
LABEL = "Pokemon R.O.W.E. v2.1.9.1 Experimental"

SPECIES_NAMES = 0x084180A4   # 13-byte names
MOVE_NAMES = 0x0841E42C      # 17-byte names; the species names end here
SPECIES_COUNT = (MOVE_NAMES - SPECIES_NAMES) // 13
BASE_STATS = 0x08502C54      # 0x40-byte entries: types +0xC/+0xD, genderRatio +0x18
BATTLE_MOVES = 0x08435298    # 0x38-byte entries: power +2, type +3
ITEMS = 0x08F76D78           # 0x38-byte entries: inline name, description* +0x1C
ITEM_LIMIT = 1000            # GetItemIconPicOrPalette treats ids > 999 as 0
TYPE_CHART = 0x0842C182      # [19][19] u16 uq4.12
TYPE_COUNT = 19
REGION_MAP_ENTRIES = 0x08FD278C  # {x, y, w, h, name*}
MAPSEC_COUNT = 0xD5
# Region maps: (image, cursor grid) by gMapHeader.region; the grid sits at (1, 2) on its map.
REGIONS = [("rowe_hoenn", 0x08FCF950), ("rowe_kanto", 0x08FD09B8), ("rowe_sevii", 0x08FD1A80)]
GRID_W, GRID_H, GRID_OX, GRID_OY = 28, 15, 1, 2

TYPE_NAMES = {0: "Normal", 1: "Fighting", 2: "Flying", 3: "Poison", 4: "Ground", 5: "Rock", 6: "Bug", 7: "Ghost",
              8: "Steel", 10: "Fire", 11: "Water", 12: "Grass", 13: "Electric", 14: "Psychic", 15: "Ice",
              16: "Dragon", 17: "Dark", 18: "Fairy"}  # 9 Mystery: no species / move uses it

CHARS = {0x00: " ", 0x1B: "é", 0xAB: "!", 0xAC: "?", 0xAD: ".", 0xAE: "-", 0xB0: "…", 0xB1: "“", 0xB2: "”",
         0xB3: "‘", 0xB4: "'", 0xB5: "♂", 0xB6: "♀", 0xB8: ",", 0xBA: "/", 0xF0: ":", 0x2D: "&", 0x5C: "(",
         0x5D: ")", 0x5B: "%", 0x35: "=", 0x36: ";", 0x53: "PK", 0x54: "MN"}
# Line / paragraph breaks: descriptions are wrapped for the game's box, the app rewraps.
CHARS.update({0xFA: " ", 0xFB: " ", 0xFE: " "})
CHARS.update({0xA1 + i: str(i) for i in range(10)})
CHARS.update({0xBB + i: chr(65 + i) for i in range(26)})
CHARS.update({0xD5 + i: chr(97 + i) for i in range(26)})
# FD xx placeholders (Emerald's): only "{AQUA} Hideout" (mapsec 0x42) uses one in these tables.
PLACEHOLDERS = {0x08: "Aqua", 0x09: "Magma"}

# Spot checks against what the game shows (the user's save: Duraludon Lv10 with Metal Claw /
# Leer / Rock Smash, a scripted wild Wurmple; the bag's Repel x5 / Potion x6 / Poké Ball x10 and
# key items; Littleroot Town on the field).
CHECKS = dict(species={1: "Bulbasaur", 4: "Charmander", 25: "Pikachu", 265: "Wurmple", 884: "Duraludon",
                       1025: "Pecharunt", 1551: "Rattata"},
              types={1: (12, 3), 4: (10, 10), 25: (13, 13), 35: (18, 18), 265: (6, 6), 884: (8, 16)},
              gender={1: 31, 25: 127, 35: 191, 884: 127},
              moves={1: ("Pound", 0, 40), 33: ("Tackle", 0, 40), 43: ("Leer", 0, 0), 52: ("Ember", 10, 40),
                     57: ("Surf", 11, 90), 89: ("Earthquake", 4, 100), 232: ("Metal Claw", 8, 60),
                     249: ("Rock Smash", 1, 40)},
              items={1: "Poké Ball", 2: "Ultra Ball", 3: "Great Ball", 28: "Potion", 90: "Repel",
                     214: "Exp. Share", 457: "Dowsing Machine", 467: "Bike", 832: "Surfboard"},
              mapsecs={0x00: "Littleroot Town", 0x10: "Route 101", 0x42: "Aqua Hideout", 0x58: "Pallet Town",
                       0x65: "Route 1", 0x8F: "One Island"})


def text(rom, a, maxlen):
    out = []
    o = a - 0x08000000
    end = o + maxlen
    while o < end:
        b = rom[o]
        if b == 0xFF:
            break
        if b == 0xFD and rom[o + 1] in PLACEHOLDERS:
            out.append(PLACEHOLDERS[rom[o + 1]])
            o += 2
            continue
        if b not in CHARS:
            return None
        out.append(CHARS[b])
        o += 1
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


def header(what):
    return (f"package com.pokedaisy.app.companion.data\n\n"
            f"// GENERATED by scripts/gen_rowe_tables.py from the {LABEL} ROM\n"
            f"// (sha1 {SHA1[:8]}...) - do not hand-edit. {what}\n")


def write(name, body):
    path = os.path.join(OUT_DIR, name)
    with open(path, "w") as f:
        f.write(body)
    print("wrote", os.path.relpath(path))


def main():
    global OUT_DIR
    if len(sys.argv) not in (2, 3):
        sys.exit(f"usage: {sys.argv[0]} <rom.gba> [out_dir]")
    if len(sys.argv) == 3:
        OUT_DIR = sys.argv[2]
    rom = open(sys.argv[1], "rb").read()
    if hashlib.sha1(rom).hexdigest() != SHA1:
        sys.exit(f"not {LABEL} (sha1 {SHA1})")

    names, types, gender = {}, {}, {}
    for i in range(1, SPECIES_COUNT):
        n = text(rom, SPECIES_NAMES + 13 * i, 13)
        if not n:
            continue
        names[i] = n
        b = BASE_STATS + 0x40 * i
        types[i] = (u8(rom, b + 0xC), u8(rom, b + 0xD))
        gender[i] = u8(rom, b + 0x18)
    assert all(names.get(i) == n for i, n in CHECKS["species"].items()), "species names are wrong"
    assert all(types.get(i) == t for i, t in CHECKS["types"].items()), "species types are wrong"
    assert all(gender.get(i) == g for i, g in CHECKS["gender"].items()), "species gender ratios are wrong"
    write("SpeciesNamesRowe.kt", header("gSpeciesNames, keyed by the game's own species ids\n"
          "// (National Dex order through 1025, then form species from 1551).\n") +
          "val speciesNamesRowe: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(names.items())) + ")\n")
    write("SpeciesTypesRowe.kt", header("gBaseStats types, ids per typeNamesRowe.\n") +
          "val speciesTypeDataRowe: Map<Int, SpeciesTypes> = mapOf(\n" +
          "".join(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(types.items())) + ")\n")
    hexs = "".join("%02x" % gender.get(i, 255) for i in range(max(gender) + 1))
    write("GenderRatiosRowe.kt", header("gBaseStats genderRatio by species id (0 always\n"
          "// male, 254 always female, 255 genderless, else female when (personality & 0xFF) < ratio).\n") +
          f"val genderRatiosRowe: IntArray by lazy {{ unhexRatios(\"{hexs}\") }}\n")

    moves, miss = [], 0
    for i in range(1, 1024):  # the box keeps moves in 10 bits
        n = text(rom, MOVE_NAMES + 17 * i, 17)
        if not n:
            miss += 1
            if miss > 4:
                break
            continue
        miss = 0
        b = BATTLE_MOVES + 0x38 * i
        moves.append((i, n, u8(rom, b + 3), u8(rom, b + 2)))
    info = {i: (n, t, p) for i, n, t, p in moves}
    assert all(info.get(i) == v for i, v in CHECKS["moves"].items()), "moves are wrong"
    write("MoveDataRowe.kt", header("gMoveNames + gBattleMoves type / power (type ids per\n"
          "// typeNamesRowe).\n") +
          "val moveDataRowe: Map<Int, MoveInfo> = mapOf(\n" +
          "".join(f"    {i} to MoveInfo({kstr(n)}, {t}, {p}),\n" for i, n, t, p in moves) + ")\n")

    items = {}
    for i in range(1, ITEM_LIMIT):
        a = ITEMS + 0x38 * i
        n = text(rom, a, 18)
        if n and u16(rom, a + 0x12) == i:
            items[i] = n
    assert all(items.get(i) == n for i, n in CHECKS["items"].items()), "item names are wrong"
    p = u32(rom, ITEMS + 0x38 * 28 + 0x1C)
    assert is_ptr(rom, p) and "20 points" in (text(rom, p, 200) or ""), "item description offset is wrong"
    assert u8(rom, ITEMS + 0x38 * 28 + 0x22) == 2 and u8(rom, ITEMS + 0x38 * 214 + 0x22) == 10, "item pocket is wrong"
    write("ItemNamesRowe.kt", header("gItems names, keyed by item id.\n") +
          "val itemNamesRowe: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(items.items())) + ")\n")

    chart = {}
    for atk in TYPE_NAMES:
        for dfn in TYPE_NAMES:
            v = u16(rom, TYPE_CHART + 2 * (atk * TYPE_COUNT + dfn))
            if v != 0x1000:
                chart[atk * 100 + dfn] = v * 100 // 0x1000
    assert chart[0 * 100 + 7] == 0 and chart[10 * 100 + 12] == 200 and chart[13 * 100 + 4] == 0 \
        and chart[16 * 100 + 18] == 0 and chart[18 * 100 + 16] == 200, "chart is wrong"
    write("TypeChartRowe.kt", header("Vanilla type ids plus Fairy 18 (9 Mystery unused).\n"
          "// sTypeEffectivenessTable [19][19] u16 uq4.12; key = atkType*100+defType -> percent, pairs\n"
          "// not listed are 100.\n") +
          "val typeNamesRowe: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(s)},\n" for i, s in sorted(TYPE_NAMES.items())) + ")\n\n" +
          "val typeEffectivenessRowe: Map<Int, Int> = mapOf(\n" +
          "".join(f"    {k} to {v},\n" for k, v in sorted(chart.items())) + ")\n")

    mapsecs = {}
    for i in range(MAPSEC_COUNT):
        p = u32(rom, REGION_MAP_ENTRIES + 8 * i + 4)
        s = text(rom, p, 30) if is_ptr(rom, p) else None
        if s:
            mapsecs[i] = s
    assert all(mapsecs.get(i) == n for i, n in CHECKS["mapsecs"].items()), "map sections are wrong"
    grids = [rom[a - 0x08000000:a - 0x08000000 + GRID_W * GRID_H] for _, a in REGIONS]
    on_grid = [set(g) - {MAPSEC_COUNT} for g in grids]
    assert on_grid[0] >= set(range(0x10, 0x32)) and 0x58 in on_grid[1] and 0x8F in on_grid[2], "grids are wrong"

    def region(i):
        # The map the game shows there: the grid that names the section (Sevii before Kanto -
        # Kanto's map has a Sevii inset), else by id: Hoenn 0x00-0x57 and Emerald's 0xC4-0xD4,
        # Kanto 0x58-0x8E, Sevii 0x8F-0xC3.
        for r in (0, 2, 1):
            if i in on_grid[r]:
                return r
        return 1 if 0x58 <= i < 0x8F else 2 if 0x8F <= i < 0xC4 else 0

    rows = []
    for i, s in sorted(mapsecs.items()):
        a = REGION_MAP_ENTRIES + 8 * i
        x, y, w, h = (u8(rom, a + k) for k in range(4))
        r = region(i)
        if (x, y) != (0, 0) and w and h:
            rows.append(f"    {i} to MapSecInfo({kstr(s)}, {r}, {x + GRID_OX}, {y + GRID_OY}, {w}, {h}),\n")
        elif i in on_grid[r]:
            rows.append(f"    {i} to MapSecInfo({kstr(s)}, {r}, 0, 0, 0, 0),\n")
        else:
            rows.append(f"    {i} to MapSecInfo({kstr(s)}, -1, 0, 0, 0, 0),\n")
    write("MapSecDataRowe.kt", header("gRegionMapEntries by mapsec id, rects in 8px tiles on\n"
          "// regionmap/rowe_{hoenn,kanto,sevii}.png (RomArt rebuilds them from the ROM; region = the\n"
          "// image index, -1 = not on a map). The grids are its GetMapSecIdAt's, for the map cursor.\n") +
          "val regionMapImagesRowe = arrayOf(" + ", ".join(f'"{n}"' for n, _ in REGIONS) + ")\n\n" +
          "val mapSecDataRowe: Map<Int, MapSecInfo> = mapOf(\n" + "".join(rows) + ")\n\n" +
          "internal val regionLayoutsRowe: List<RegionLayout> = listOf(\n" +
          "".join(f"    RegionLayout({GRID_OX}, {GRID_OY}, {GRID_W}, {GRID_H}, 0x{MAPSEC_COUNT:02X}, listOf(\n"
                  f"        hexBytes(\"{g.hex()}\"),\n    )),\n" for g in grids) + ")\n")
    print(f"{len(names)} species, {len(moves)} moves, {len(items)} items, "
          f"{len(chart)} chart pairs, {len(mapsecs)} map sections")


if __name__ == "__main__":
    main()
