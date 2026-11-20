#!/usr/bin/env python3
"""Generates Pokémon Team Rocket Edition: Dragonsden Version's display tables from the ROM the user owns (BPEE, 32 MB,
DragonsdenTV's closed pokeemerald build on an older pokeemerald-expansion; nothing is downloaded).

    scripts/gen_team_rocket_tables.py "<Pokemon Team Rocket Dragonsden Version.gba>"

Writes shared/src/commonMain/kotlin/.../companion/data/TeamRocketGen.kt. Every table was found from the ROM and headless dumps of
its one save (NativeReader.kt's NATIVE_TEAM_ROCKET says how) and is checked here against the save's party and bag:
  - gSpeciesNames: inline 11-byte names, mixed case, 1395 of them (SPECIES_EGG is 1395; National Dex order to 1025's
    Terapagos at 1379, then its own forms). The GF ROM header (0x08000144) points at it.
  - gBaseStats: 36 bytes (expansion's older struct: three u16 abilities), types +6 / +7, genderRatio +0x12, growthRate
    +0x15 (vanilla's GROWTH_* ids and curves: CROBAT's 493,039 EXP is medium fast's Lv79 exactly).
  - gMoveNames: inline 13-byte names, 755 moves (Gen 8's); gBattleMoves: 20 bytes, power +2, type +4.
  - gItems: vanilla's 44-byte struct (inline name, itemId +0x0E), expansion's item ids, 923 of them.
  - gTypeNames: 10-byte names, vanilla ids (9 is ???), FAIRY 18; the chart is u16 UQ4.12 [19][19], [attacker][defender].
  - gRegionMapEntries: vanilla's {x, y, w, h, name pointer}, 252 sections (MAPSEC_NONE 0xFC: GetMapName's own bound),
    Emerald's ids (Hoenn 0x00-0x57, Kanto / Sevii from 0x58 like FireRed's, Johto past them). Its towns are Kanto's and
    Johto's drawn with Hoenn tiles: the save stands in CELADON CITY (0x5E). No region map picture is read (it has several
    of its own), so every section is "not on the map" and the MAP tab names the place.
"""
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_expansion_tables import CHARS, OUT_DIR, Rom, kstr  # noqa: E402

SHA1 = "40780347d0b4df6bf491eaf12603e5422caee677"
SPECIES_NAMES, BASE_STATS = 0x08598560, 0x085AD228
MOVE_NAMES, BATTLE_MOVES = 0x0859C151, 0x085A5820
ITEMS, TYPE_NAMES, CHART, MAPSECS = 0x08C833B0, 0x0859EFF0, 0x085A29F2, 0x08CB0C68
SPECIES, MOVES, ITEMS_COUNT, TYPES, MAPSEC_COUNT = 1395, 755, 923, 19, 0xFC
# The app's type names (its colours are keyed by them), by the game's ids: vanilla's, FAIRY 18.
APP_TYPE_NAMES = {0: "Normal", 1: "Fighting", 2: "Flying", 3: "Poison", 4: "Ground", 5: "Rock", 6: "Bug", 7: "Ghost",
                  8: "Steel", 10: "Fire", 11: "Water", 12: "Grass", 13: "Electric", 14: "Psychic", 15: "Ice",
                  16: "Dragon", 17: "Dark", 18: "Fairy"}

# Its Spanish item names and a few forms use the Western charmap's accented letters.
CHARS.update({0x01: "À", 0x02: "Á", 0x06: "É", 0x0E: "Ó", 0x12: "Ú", 0x14: "Ñ", 0x16: "à", 0x17: "á", 0x19: "ç",
              0x1A: "è", 0x1E: "ì", 0x22: "ò", 0x23: "ó", 0x26: "ù", 0x27: "ú", 0x29: "ñ", 0x2E: "+", 0x6F: "í"})


def read(rom):
    sp, ty, gr, gw = {}, {}, {}, {}
    for i in range(1, SPECIES):
        n = rom.text(SPECIES_NAMES + 11 * i, 11)
        if not n or n.startswith("?") or n == "-":
            continue
        sp[i] = n
        a = BASE_STATS + 36 * i
        ty[i] = (rom.u8(a + 6), rom.u8(a + 7))
        gr[i] = rom.u8(a + 0x12)
        gw[i] = rom.u8(a + 0x15)
    mv = {}
    for i in range(1, MOVES):
        n = rom.text(MOVE_NAMES + 13 * i, 13)
        if not n or n == "-":
            continue
        a = BATTLE_MOVES + 20 * i
        mv[i] = (n, rom.u8(a + 4), rom.u8(a + 2))
    it = {}
    for i in range(1, ITEMS_COUNT):
        a = ITEMS + 44 * i
        n = rom.text(a, 14)
        if not n or n.startswith("?"):
            continue
        it[i] = n
    tn = {i: rom.text(TYPE_NAMES + 10 * i, 10) for i in range(TYPES)}
    chart = {}
    for atk in APP_TYPE_NAMES:
        for dfn in APP_TYPE_NAMES:
            v = rom.u16(CHART + 2 * (atk * TYPES + dfn))
            if v != 0x1000:
                chart[atk * 100 + dfn] = v * 100 // 0x1000
    ms = {}
    for i in range(MAPSEC_COUNT):
        p = rom.u32(MAPSECS + 8 * i + 4)
        n = rom.text(p, 40) if rom.is_ptr(p) else None
        if n:
            ms[i] = n
    return dict(species=sp, types=ty, gender=gr, growth=gw, moves=mv, items=it, type_names=tn, chart=chart, mapsecs=ms)


def check(t):
    sp, mv, it = t["species"], t["moves"], t["items"]
    # The save's party (Crobat, Houndoom, Marowak, Aerodactyl, Venusaur, Porygon) and Crobat's moves.
    assert [sp[i] for i in (169, 229, 105, 142, 3, 137)] == \
        ["Crobat", "Houndoom", "Marowak", "Aerodactyl", "Venusaur", "Porygon"]
    assert sp[387] == "Turtwig" and sp[1379] == "Terapagos" and sp[926] == "Poliwrath&"
    assert t["types"][169] == (3, 2) and t["types"][1] == (12, 3) and t["gender"][1] == 31
    assert t["growth"][169] == 0 and t["growth"][1] == 3   # medium fast, medium slow
    assert [mv[i][0] for i in (174, 366, 305, 19)] == ["Curse", "Tailwind", "Poison Fang", "Fly"]
    assert mv[1] == ("Pound", 0, 40) and mv[7] == ("Fire Punch", 10, 75) and mv[174][1] == 7  # Curse is GHOST
    # The bag's pockets as the game's bag screen showed them.
    assert (it[1], it[4], it[121], it[471], it[544], it[592], it[864], it[910]) == \
        ("Poké Ball", "Master Ball", "Escape Rope", "Exp. Share", "Pomeg Berry", "TM01", "Mach Bike", "Rainbow Pass")
    assert t["type_names"][18] == "Fairy" and t["type_names"][9] == "???"
    assert t["chart"][1816] == 200 and t["chart"][1618] == 0 and t["chart"][7] == 0  # FAIRY->DRAGON, DRAGON->FAIRY, NORMAL->GHOST
    assert t["mapsecs"][0x5E] == "CELADON CITY" and t["mapsecs"][0x0A] == "RUSTBORO CITY"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    data = open(sys.argv[1], "rb").read()
    h = hashlib.sha1(data).hexdigest()
    if h != SHA1:
        sys.exit(f"{sys.argv[1]}: sha1 {h} isn't Team Rocket Edition: Dragonsden Version's ({SHA1})")
    t = read(Rom(data))
    check(t)
    top = max(t["gender"])
    gender = "".join("%02x" % t["gender"].get(i, 255) for i in range(top + 1))
    out = ["package com.pokedaisy.app.companion.data\n\n"
           "// GENERATED by scripts/gen_team_rocket_tables.py from the Pokemon Team Rocket Edition: Dragonsden Version\n"
           f"// ROM (sha1 {SHA1[:8]}...) - do not hand-edit. Names in the game's own (mixed) case.\n\n"]
    out.append("internal val speciesNamesTeamRocket: Map<Int, String> by lazy { mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(t["species"].items())) + ") }\n")
    out.append("\ninternal val speciesTypeDataTeamRocket: Map<Int, SpeciesTypes> by lazy { mapOf(\n" +
               "".join(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(t["types"].items())) + ") }\n")
    out.append("\n// gBaseStats genderRatio by species id (0 always male, 254 always female, 255 genderless).\n"
               f"internal val genderRatiosTeamRocket: IntArray by lazy {{ unhexRatios(\"{gender}\") }}\n")
    growth = "".join("%02x" % t["growth"].get(i, 255) for i in range(max(t["growth"]) + 1))
    out.append("\n// gBaseStats growthRate by species id (vanilla's GROWTH_* ids: ExpTables.kt's curves).\n"
               f"internal val growthRatesTeamRocket: IntArray by lazy {{ unhexRatios(\"{growth}\") }}\n")
    out.append("\n// gMoveNames + gBattleMoves type / power.\n"
               "internal val moveDataTeamRocket: Map<Int, MoveInfo> by lazy { mapOf(\n" +
               "".join(f"    {i} to MoveInfo({kstr(n)}, {ty}, {p}),\n" for i, (n, ty, p) in sorted(t["moves"].items())) +
               ") }\n")
    out.append("\ninternal val itemNamesTeamRocket: Map<Int, String> by lazy { mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(t["items"].items())) + ") }\n")
    out.append("\n// Vanilla type ids, FAIRY 18; the chart: key = atkType*100+defType -> percent, pairs not listed are 100.\n"
               "internal val typeNamesTeamRocket: Map<Int, String> = mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(APP_TYPE_NAMES.items())) + ")\n\n"
               "internal val typeEffectivenessTeamRocket: Map<Int, Int> by lazy { mapOf(\n" +
               "".join(f"    {k} to {v},\n" for k, v in sorted(t["chart"].items())) + ") }\n")
    out.append("\n// gRegionMapEntries' names by mapsec id; region -1 = not on a map the app draws (it has several of its own).\n"
               "internal val mapSecDataTeamRocket: Map<Int, MapSecInfo> by lazy { mapOf(\n" +
               "".join(f"    {i} to MapSecInfo({kstr(n)}, -1, 0, 0, 0, 0),\n" for i, n in sorted(t["mapsecs"].items())) +
               ") }\n")
    path = os.path.join(OUT_DIR, "TeamRocketGen.kt")
    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(out))
    print("wrote", os.path.relpath(path))
    print(f"{len(t['species'])} species, {len(t['moves'])} moves, {len(t['items'])} items, "
          f"{len(t['mapsecs'])} map sections")


if __name__ == "__main__":
    main()
