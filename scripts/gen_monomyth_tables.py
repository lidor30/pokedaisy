#!/usr/bin/env python3
"""Generates Pokémon Monomyth's display tables from the ROM the user owns (Team Monomyth's closed game on its own
pokeemerald build, game code MONE, 32 MB; nothing is downloaded).

    scripts/gen_monomyth_tables.py "<Pokemon Monomyth (v1.0).gba>" ["<Pokemon Monomyth (v1.1).gba>"]

Writes app/src/main/kotlin/.../companion/data/MonomythGen.kt; with the v1.1 ROM it first checks every table there equals
v1.0's (the same data, moved: the app reads one set of tables for both, game code BPEE on v1.1). Its tables keep vanilla pokeemerald's shapes (names in
arrays of their own, not inline like pokeemerald-expansion's) with sizes and ids of its own, every one found in the ROM
and checked here against the user's save (NativeReader's config comments say how the RAM was found):
  - gSpeciesNames 0x084E8BBC: 11-byte names, mixed case, 405 of them (its Athanto dex: real and invented Pokémon in
    its own order, then forms; 402 / 403 are "???" and 405 is "-").
  - gBaseStats 0x084F6DBC: 24-byte entries (vanilla's 28 less its EV yields and items): base stats +0..+5, types +6 / +7
    (vanilla ids, FAIRY 18), genderRatio +9, growthRate +0xC numbered by its own gExperienceTables' rows.
  - gExperienceTables 0x084F65D8: five rows of 101 u32 (0 fluctuating, 1 slow, 2 medium slow, 3 medium fast, 4 fast):
    vanilla's curves to Lv70, steeper after ("experience gain slows down in stages", its intro says).
  - gMoveNames 0x084E9D23: 13-byte names, 596+ moves (Gen 3's ids first: the save's Water Gun is 55); gBattleMoves
    0x084F17E0: 16-byte entries, power +2, type +3.
  - gItems 0x089F15D0: 32-byte entries {price u16, name[14], two use functions, description pointer +0x18, packed
    pocket / type bytes}, 447 items in its own numbering (1 Master Ball, 4 PokéBall, 32 Potion, 93 Repel).
  - gTypeNames 0x084EC5F8 (7 bytes) and gTypeEffectiveness 0x084EF720: u16 UQ4.12 [19][19], Gen 6's rules.
  - gNatureNamePointers 0x08AC16B4: 250 natures of its own, flavour only (its summary's "Dreamy nature"; GetNature
    hashes the personality, Gen3Mon.kt's monomythNature).
  - gRegionMapEntries 0x08A166AC: vanilla's {x, y, w, h, name pointer}, 0xCE sections (Athanto's: 0x00 CHAMOMILE TOWN,
    0x01 CATSEAR CITY, routes from 0x10), in the cursor grid's cells; sRegionMap_MapSectionLayout 0x08A16000 is
    vanilla's [15][28] u8 grid (MAPSEC_NONE 0xD5), cell (0, 0) at screen tile (1, 2) of the PokeNav's ATHANTO MAP
    (regionmap/monomyth.png, RomArt's MMY_REGION_*: the player's head on CATSEAR CITY (3, 10) sits on tile (4, 12)).
"""
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_expansion_tables import CHARS, OUT_DIR, Rom, kstr  # noqa: E402

SHA1 = "ae1c4be9be8d706bb989602bc05731203d42860d"
V1_1_SHA1 = "eb660caffbc26e1f09f86714d03af4c3eccd9e4d"
V1_0 = dict(species_names=0x084E8BBC, base_stats=0x084F6DBC, exp_tables=0x084F65D8, move_names=0x084E9D23,
            battle_moves=0x084F17E0, items=0x089F15D0, type_names=0x084EC5F8, chart=0x084EF720, mapsecs=0x08A166AC,
            grid=0x08A16000, nature_names=0x08AC16B4)
V1_1 = dict(species_names=0x084E9044, base_stats=0x084F7244, exp_tables=0x084F6A60, move_names=0x084EA1AB,
            battle_moves=0x084F1C68, items=0x089F19E8, type_names=0x084ECA80, chart=0x084EFBA8, mapsecs=0x08A16AC4,
            grid=0x08A16418, nature_names=0x08AC1AC0)
GRID_W, GRID_H, GRID_NONE, GRID_OX, GRID_OY = 28, 15, 0xD5, 1, 2
NATURES = 250
SPECIES, MOVES, ITEMS_COUNT, TYPES, MAPSEC_COUNT = 405, 1024, 447, 19, 0xCE
GROWTH_ROWS = 5
# The app's type names (its colours are keyed by them), by the game's ids: vanilla's, FAIRY 18.
APP_TYPE_NAMES = {0: "Normal", 1: "Fighting", 2: "Flying", 3: "Poison", 4: "Ground", 5: "Rock", 6: "Bug", 7: "Ghost",
                  8: "Steel", 10: "Fire", 11: "Water", 12: "Grass", 13: "Electric", 14: "Psychic", 15: "Ice",
                  16: "Dragon", 17: "Dark", 18: "Fairy"}

# A few of its names use the Western charmap's accented letters (Süßchen, Slizón, Jiaomolóng).
CHARS.update({0x01: "À", 0x02: "Á", 0x06: "É", 0x0E: "Ó", 0x12: "Ú", 0x14: "Ñ", 0x15: "ß", 0x16: "à", 0x17: "á",
              0x19: "ç", 0x1A: "è", 0x1E: "ì", 0x22: "ò", 0x23: "ó", 0x26: "ù", 0x27: "ú", 0x29: "ñ", 0x2E: "+",
              0x6F: "í", 0xF1: "Ä", 0xF2: "Ö", 0xF3: "Ü", 0xF4: "ä", 0xF5: "ö", 0xF6: "ü"})


def read(rom, at=V1_0):
    SPECIES_NAMES, BASE_STATS, EXP_TABLES = at["species_names"], at["base_stats"], at["exp_tables"]
    MOVE_NAMES, BATTLE_MOVES, ITEMS, TYPE_NAMES = at["move_names"], at["battle_moves"], at["items"], at["type_names"]
    CHART, MAPSECS, GRID, NATURE_NAMES = at["chart"], at["mapsecs"], at["grid"], at["nature_names"]
    sp, ty, gr, gw = {}, {}, {}, {}
    for i in range(1, SPECIES):
        n = rom.text(SPECIES_NAMES + 11 * i, 11)
        if not n or n.startswith("?") or n == "-":
            continue
        sp[i] = n
        a = BASE_STATS + 24 * i
        ty[i] = (rom.u8(a + 6), rom.u8(a + 7))
        gr[i] = rom.u8(a + 9)
        gw[i] = rom.u8(a + 0xC)
    exp = [[rom.u32(EXP_TABLES + 404 * r + 4 * lv) for lv in range(101)] for r in range(GROWTH_ROWS)]
    mv, miss = {}, 0
    for i in range(1, MOVES):
        n = rom.text(MOVE_NAMES + 13 * i, 13)
        if not n or n == "-":
            miss += 1
            if miss > 4:
                break
            continue
        miss = 0
        a = BATTLE_MOVES + 16 * i
        mv[i] = (n, rom.u8(a + 3), rom.u8(a + 2))
    it = {}
    for i in range(1, ITEMS_COUNT):
        n = rom.text(ITEMS + 32 * i + 2, 14)
        if not n or n.startswith("?") or n == "UNOBTAINABLE":
            continue
        it[i] = n
    tn = {i: rom.text(TYPE_NAMES + 7 * i, 7) for i in range(TYPES)}
    chart = {}
    for atk in APP_TYPE_NAMES:
        for dfn in APP_TYPE_NAMES:
            v = rom.u16(CHART + 2 * (atk * TYPES + dfn))
            if v != 0x1000:
                chart[atk * 100 + dfn] = v * 100 // 0x1000
    ms = {}
    for i in range(MAPSEC_COUNT):
        a = MAPSECS + 8 * i
        p = rom.u32(a + 4)
        n = rom.text(p, 40) if rom.is_ptr(p) else None
        if n:
            ms[i] = (n, rom.u8(a), rom.u8(a + 1), rom.u8(a + 2), rom.u8(a + 3))
    grid = bytes(rom.u8(GRID + k) for k in range(GRID_W * GRID_H))
    nat = [rom.text(rom.u32(NATURE_NAMES + 4 * i), 30) for i in range(NATURES)]
    return dict(natures=nat, species=sp, types=ty, gender=gr, growth=gw, exp=exp, moves=mv, items=it, type_names=tn, chart=chart,
                mapsecs=ms, grid=grid)


def check(t):
    sp, mv, it = t["species"], t["moves"], t["items"]
    # The user's save's party: Mishimizu, Leonte, Joltik, Venipede, Whismur, Scraggy (and the headless save's Kotora,
    # Growlithe, Staryu, Rotom, Togepi), as its party menu shows them.
    assert [sp[i] for i in (194, 183, 96, 72, 119, 106)] == \
        ["Mishimizu", "Leonte", "Joltik", "Venipede", "Whismur", "Scraggy"]
    assert [sp[i] for i in (1, 15, 19, 116, 98)] == ["Kotora", "Growlithe", "Staryu", "Rotom", "Togepi"]
    assert sp[338] == "Süßchen" and sp[320] == "Slizón"
    assert t["types"][1] == (13, 13) and t["types"][87] == (13, 8) and t["types"][93] == (8, 2)
    assert t["gender"][15] == 63 and t["gender"][19] == 255
    # Growth: Kotora medium fast (its Lv12 1,728 EXP), Growlithe and Staryu slow (911 at Lv9, 640 at Lv8), Togepi fast.
    assert (t["growth"][1], t["growth"][15], t["growth"][19], t["growth"][98]) == (3, 1, 1, 4)
    assert t["exp"][3][12] == 1728 and t["exp"][1][9] == 911 and t["exp"][1][8] == 640 and t["exp"][4][7] == 274
    assert t["exp"][3][70] == 343000 and t["exp"][3][100] == 1189244
    # Kotora's moves in battle (Leer, Assist, Charge, Quick Attack), the user's Mishimizu's and Whismur's.
    assert [mv[i][0] for i in (43, 271, 265, 98)] == ["Leer", "Assist", "Charge", "Quick Attack"]
    assert mv[1] == ("Pound", 0, 40) and mv[2] == ("Karate Chop", 1, 50) and mv[55] == ("Water Gun", 11, 40)
    # The bags' items: the user's (Repel; Potion, Antidote ... Fresh Water; PokéBall, Nest Ball, Premier Ball and the
    # Apricorns; Sharp Beak, Silver Powder; a TM; berries) as its bag screen shows them.
    assert (it[1], it[4], it[32], it[33], it[93], it[107], it[139], it[202], it[224]) == \
        ("Master Ball", "PokéBall", "Potion", "Antidote", "Repel", "Blu. Apricorn", "Oran Berry", "Silver Powder",
         "Sharp Beak")
    assert t["type_names"][18] == "Fairy" and t["type_names"][9] == "???"
    assert t["chart"][1816] == 200 and t["chart"][1618] == 0 and t["chart"][7] == 0  # FAIRY->DRAGON, DRAGON->FAIRY, NORMAL->GHOST
    assert t["natures"][190] == "Dreamy" and t["natures"][0] == "Loose-cannon" and all(t["natures"])
    ms = t["mapsecs"]
    assert ms[0x00][0] == "Chamomile Town" and ms[0x01][0] == "Catsear City" and ms[0x10][0] == "Route 1"
    # The grid names CATSEAR CITY where its rect is, and every section it names has a name.
    g = t["grid"]
    assert g[10 * GRID_W + 3] == 0x01 and g[8 * GRID_W + 1] == 0x00 and g[4 * GRID_W + 15] == 0x0A
    assert {c for c in g if c != GRID_NONE} <= set(ms)


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    data = open(sys.argv[1], "rb").read()
    h = hashlib.sha1(data).hexdigest()
    if h != SHA1:
        sys.exit(f"{sys.argv[1]}: sha1 {h} isn't Pokémon Monomyth v1.0's ({SHA1})")
    t = read(Rom(data))
    check(t)
    if len(sys.argv) == 3:
        newer = open(sys.argv[2], "rb").read()
        h = hashlib.sha1(newer).hexdigest()
        if h != V1_1_SHA1:
            sys.exit(f"{sys.argv[2]}: sha1 {h} isn't Pokémon Monomyth v1.1's ({V1_1_SHA1})")
        t1 = read(Rom(newer), V1_1)
        for k in t:
            assert t1[k] == t[k], f"v1.1's {k} isn't v1.0's"
        print("v1.1's tables are v1.0's")
    hexs = lambda d: "".join("%02x" % d.get(i, 255) for i in range(max(d) + 1))  # noqa: E731
    out = ["package com.pokedaisy.app.companion.data\n\n"
           "// GENERATED by scripts/gen_monomyth_tables.py from the Pokemon Monomyth v1.0 ROM\n"
           f"// (sha1 {SHA1[:8]}...) - do not hand-edit. Names in the game's own (mixed) case.\n\n"]
    out.append("internal val speciesNamesMonomyth: Map<Int, String> by lazy { mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(t["species"].items())) + ") }\n")
    out.append("\ninternal val speciesTypeDataMonomyth: Map<Int, SpeciesTypes> by lazy { mapOf(\n" +
               "".join(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(t["types"].items())) + ") }\n")
    out.append("\n// gBaseStats genderRatio by species id (0 always male, 254 always female, 255 genderless).\n"
               f"internal val genderRatiosMonomyth: IntArray by lazy {{ unhexRatios(\"{hexs(t['gender'])}\") }}\n")
    out.append("\n// gBaseStats growthRate by species id: a row of [expTablesMonomyth] (0 fluctuating, 1 slow, 2 medium slow,\n"
               "// 3 medium fast, 4 fast).\n"
               f"internal val growthRatesMonomyth: IntArray by lazy {{ unhexRatios(\"{hexs(t['growth'])}\") }}\n")
    out.append("\n// gExperienceTables: the EXP each level starts at, by growth rate (vanilla's curves to Lv70, steeper after).\n"
               "internal val expTablesMonomyth: Array<IntArray> by lazy { arrayOf(\n" +
               "".join("    intArrayOf(" + ", ".join(str(v) for v in row) + "),\n" for row in t["exp"]) + ") }\n")
    out.append("\n// gMoveNames + gBattleMoves type / power.\n"
               "internal val moveDataMonomyth: Map<Int, MoveInfo> by lazy { mapOf(\n" +
               "".join(f"    {i} to MoveInfo({kstr(n)}, {ty}, {p}),\n" for i, (n, ty, p) in sorted(t["moves"].items())) +
               ") }\n")
    out.append("\ninternal val itemNamesMonomyth: Map<Int, String> by lazy { mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(t["items"].items())) + ") }\n")
    out.append("\n// Its 250 natures (flavour only: no stat changes), by Gen3Mon.kt's monomythNature.\n"
               "internal val natureNamesMonomyth: List<String> by lazy { listOf(\n" +
               "".join(f"    {kstr(n)},\n" for n in t["natures"]) + ") }\n")
    out.append("\n// Vanilla type ids, FAIRY 18; the chart: key = atkType*100+defType -> percent, pairs not listed are 100.\n"
               "internal val typeNamesMonomyth: Map<Int, String> = mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(APP_TYPE_NAMES.items())) + ")\n\n"
               "internal val typeEffectivenessMonomyth: Map<Int, Int> by lazy { mapOf(\n" +
               "".join(f"    {k} to {v},\n" for k, v in sorted(t["chart"].items())) + ") }\n")
    # Like gen_expansion_tables.py's: a rect at {0, 0} is "not on the map" unless the grid names the section (then its
    # cells place it); region -1 = not on the map.
    on_grid = {c for c in t["grid"] if c != GRID_NONE}
    def row(i, n, x, y, w, h):
        if (x, y) != (0, 0) and w and h:
            return f"    {i} to MapSecInfo({kstr(n)}, 0, {x + GRID_OX}, {y + GRID_OY}, {w}, {h}),\n"
        return f"    {i} to MapSecInfo({kstr(n)}, {0 if i in on_grid else -1}, 0, 0, 0, 0),\n"
    out.append("\n// gRegionMapEntries by mapsec id: names, and the {x, y, w, h} rects in 8 px tiles on regionmap/monomyth.png\n"
               "// (the PokeNav's ATHANTO MAP, RomArt rebuilds it from the ROM); region -1 = not on the map.\n"
               "internal val mapSecDataMonomyth: Map<Int, MapSecInfo> by lazy { mapOf(\n" +
               "".join(row(i, *v) for i, v in sorted(t["mapsecs"].items())) + ") }\n\n"
               "internal val regionMapImagesMonomyth = arrayOf(\"monomyth\")\n\n"
               "// sRegionMap_MapSectionLayout, for the map cursor.\n"
               "internal val regionLayoutsMonomyth: List<RegionLayout> by lazy { listOf(\n"
               f"    RegionLayout({GRID_OX}, {GRID_OY}, {GRID_W}, {GRID_H}, 0x{GRID_NONE:02X}, listOf(hexBytes(\"{t['grid'].hex()}\"))),\n"
               ") }\n")
    path = os.path.join(OUT_DIR, "MonomythGen.kt")
    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(out))
    print("wrote", os.path.relpath(path))
    print(f"{len(t['species'])} species, {len(t['moves'])} moves, {len(t['items'])} items, "
          f"{len(t['mapsecs'])} map sections")


if __name__ == "__main__":
    main()
