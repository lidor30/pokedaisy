#!/usr/bin/env python3
"""Generates Pokémon Elite Redux's display tables from the ROMs the user owns (its own pokeemerald rebuild, BPEE, 32 MB;
nothing is downloaded).

    scripts/gen_elite_redux_tables.py <Pokemon Elite Redux (2.65.3b).gba> <Pokemon Elite Redux (2.65.4.1b).gba>

Writes shared/src/commonMain/kotlin/.../companion/data/EliteReduxGen.kt: 2.65.4.1b's tables whole, and 2.65.3b's as what differs
from them (its RAM never holds an id only 2.65.4.1b has, so 2.65.4.1b's names stand in for the rest). Every table was
found headless (see NativeReader.kt's NATIVE_ELITE_REDUX_*) and is checked here against the save's party and bag and the
game's own screens:
  - gSpeciesNames: a u32 POINTER per species (not inline names), 2901 of them (SPECIES_EGG is 2901); mixed case.
  - gBaseStats: 0x2C bytes, type1 / type2 +6 / +7, genderRatio +0x12.
  - gMoveNamesLong: 19-byte full names ("Breaking Swipe"; gMoveNames' 13 truncate them), 1032 moves; gBattleMoves: 24
    bytes, power +0x0A, type +0x0B.
  - gItems: 40 bytes, name POINTER +0, pocket +0x16 (ER's 1 ITEMS, 2 MEDICINE, 3 BATTLE ITEMS, 4 TM/HM, 5 BERRIES, 6 POKé
    BALLS, 7 KEY ITEMS, 8 MEGA STONES - the app's five pockets take them), 440 items, ER's own ids.
  - gTypeNames: vanilla's 0-17 (9 is ???), FAIRY 18, STELLAR 19 (left out: nothing is that type); the chart is
    u16 [20][20] with 0x400 = 1x, [attacker][defender].
  - gRegionMapEntries: Emerald's {x, y, w, h, name pointer} on Emerald's Hoenn grid (rects + (1, 2), as
    MapSecDataEmerald.kt), 220 sections.
"""
import hashlib
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_expansion_tables import OUT_DIR, Rom, kstr  # noqa: E402

BUILDS = {
    "2.65.3b": dict(sha1="5b38a681b6924ef3e0bb9f6cfbf22426c4c4e523", species=0x089C1614, base_stats=0x08BF5B84,
                    move_names=0x089CADD8, battle_moves=0x08BEC024, items=0x08AB9640, type_names=0x08517AF4,
                    chart=0x08540E40, mapsecs=0x08EE7EC0),
    "2.65.4.1b": dict(sha1="2f3e17e55c6afe23da97ec0f96a067eeb142ad15", species=0x089CEAFC, base_stats=0x08C033E4,
                      move_names=0x089D82C0, battle_moves=0x08BF9884, items=0x08AC6B24, type_names=0x08516318,
                      chart=0x08545A04, mapsecs=0x08EFA32C),
}
SPECIES, MOVES, ITEMS, TYPES, MAPSECS = 2901, 1032, 440, 20, 220
# The app's type names (its colours are keyed by them), by ER's ids: vanilla's, FAIRY 18.
TYPE_NAMES = {0: "Normal", 1: "Fighting", 2: "Flying", 3: "Poison", 4: "Ground", 5: "Rock", 6: "Bug", 7: "Ghost",
              8: "Steel", 10: "Fire", 11: "Water", 12: "Grass", 13: "Electric", 14: "Psychic", 15: "Ice", 16: "Dragon",
              17: "Dark", 18: "Fairy"}
# ER's item pockets -> the app's (Telemetry.kt POCKET_*: 0 ITEMS, 1 BALLS, 2 TM/HM, 3 BERRIES, 4 KEY ITEMS).
POCKETS = {1: 0, 2: 0, 3: 0, 4: 2, 5: 3, 6: 1, 7: 4, 8: 0}
MAP_OX, MAP_OY = 1, 2


def read(rom, b):
    sp, ty, gr = {}, {}, {}
    for i in range(1, SPECIES):
        p = rom.u32(b["species"] + 4 * i)
        n = rom.text(p, 20) if rom.is_ptr(p) else None
        if not n or n.startswith("?") or n == "-":
            continue
        sp[i] = n
        a = b["base_stats"] + 0x2C * i
        ty[i] = (rom.u8(a + 6), rom.u8(a + 7))
        gr[i] = rom.u8(a + 0x12)
    mv = {}
    for i in range(1, MOVES):
        n = rom.text(b["move_names"] + 19 * i, 19)
        if not n or n == "-":
            continue
        a = b["battle_moves"] + 24 * i
        mv[i] = (n, rom.u8(a + 0x0B), rom.u8(a + 0x0A))
    it, pk = {}, {}
    for i in range(1, ITEMS):
        a = b["items"] + 40 * i
        p = rom.u32(a)
        n = rom.text(p, 24) if rom.is_ptr(p) else None
        if not n or n.startswith("?"):
            continue
        it[i] = n
        pk[i] = POCKETS.get(rom.u8(a + 0x16), 0)
    tn = {i: rom.text(b["type_names"] + 7 * i, 7) for i in range(TYPES)}
    chart = {}
    for atk in TYPE_NAMES:
        for dfn in TYPE_NAMES:
            v = rom.u16(b["chart"] + 2 * (atk * TYPES + dfn))
            if v != 0x400:
                chart[atk * 100 + dfn] = v * 100 // 0x400
    ms = {}
    for i in range(MAPSECS):
        a = b["mapsecs"] + 8 * i
        p = rom.u32(a + 4)
        n = rom.text(p, 40) if rom.is_ptr(p) else None
        if n:
            ms[i] = (n, rom.u8(a), rom.u8(a + 1), rom.u8(a + 2), rom.u8(a + 3))
    return dict(species=sp, types=ty, gender=gr, moves=mv, items=it, pockets=pk, type_names=tn, chart=chart, mapsecs=ms)


def check(t, name):
    sp, mv, it = t["species"], t["moves"], t["items"]
    # The save's party (both builds), the bag's first rows, the summary's moves (NATIVE_ELITE_REDUX_* / the fixtures).
    assert (sp[1], sp[644], sp[380], sp[1071], sp[608]) == ("Bulbasaur", "Zekrom", "Latias", "Lucineon", "Lampent"), name
    assert t["types"][644] == (16, 13) and t["types"][1071] == (11, 18), name
    assert mv[33][0] == "Tackle" and mv[75] == ("Razor Leaf", 12, 55) and mv[17][0] == "Wing Attack", name
    assert it[1] == "Master Ball", name
    assert t["type_names"][18] == "Fairy" and t["type_names"][0] == "Normal", name
    assert t["chart"][1816] == 200 and t["chart"][1618] == 0 and t["chart"][7] == 0, name  # FAIRY->DRAGON, DRAGON->FAIRY, NORMAL->GHOST
    assert t["mapsecs"][10][0] == "Rustboro City" and t["mapsecs"][10][1:] == (0, 5, 1, 2), name


def species_block(name, sp):
    return f"internal val speciesNames{name}: Map<Int, String> by lazy {{ mapOf(\n" + \
        "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(sp.items())) + ") }\n"


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    data = {}
    for path in sys.argv[1:]:
        d = open(path, "rb").read()
        h = hashlib.sha1(d).hexdigest()
        ver = next((v for v, b in BUILDS.items() if b["sha1"] == h), None)
        if ver is None:
            sys.exit(f"{path}: sha1 {h} isn't a known Elite Redux build")
        data[ver] = d
    if set(data) != set(BUILDS):
        sys.exit("pass both builds: " + ", ".join(BUILDS))
    t = {v: read(Rom(data[v]), BUILDS[v]) for v in BUILDS}
    for v in t:
        check(t[v], v)
    new, old = t["2.65.4.1b"], t["2.65.3b"]
    assert new["type_names"] == old["type_names"] and new["chart"] == old["chart"]

    def diff(k):
        return {i: x for i, x in old[k].items() if new[k].get(i) != x}

    rows = []
    for i, (n, x, y, w, h) in sorted(new["mapsecs"].items()):
        if (x, y) != (0, 0) and w and h:
            rows.append(f"    {i} to MapSecInfo({kstr(n)}, 0, {x + MAP_OX}, {y + MAP_OY}, {w}, {h}),\n")
        else:
            rows.append(f"    {i} to MapSecInfo({kstr(n)}, -1, 0, 0, 0, 0),\n")
    assert old["mapsecs"] == new["mapsecs"], "the builds' map sections differ"
    top = max(new["gender"])
    gender = "".join("%02x" % new["gender"].get(i, 255) for i in range(top + 1))
    assert all(new["gender"].get(i) == g for i, g in old["gender"].items() if i in new["gender"])

    out = ["package com.pokedaisy.app.companion.data\n\n"
           "// GENERATED by scripts/gen_elite_redux_tables.py from the Pokemon Elite Redux 2.65.3b and 2.65.4.1b ROMs\n"
           f"// (sha1 {BUILDS['2.65.3b']['sha1'][:8]}... / {BUILDS['2.65.4.1b']['sha1'][:8]}...) - do not hand-edit. 2.65.4.1b's tables\n"
           "// whole, 2.65.3b's as what differs from them. Names in the game's own (mixed) case.\n\n"]
    out.append(species_block("EliteRedux", new["species"]))
    out.append("\ninternal val speciesTypeDataEliteRedux: Map<Int, SpeciesTypes> by lazy { mapOf(\n" +
               "".join(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(new["types"].items())) + ") }\n")
    out.append("\n// gBaseStats genderRatio by species id (0 always male, 254 always female, 255 genderless).\n"
               f"internal val genderRatiosEliteRedux: IntArray by lazy {{ unhexRatios(\"{gender}\") }}\n")
    out.append("\n// gMoveNamesLong + gBattleMoves type / power.\n"
               "internal val moveDataEliteRedux: Map<Int, MoveInfo> by lazy { mapOf(\n" +
               "".join(f"    {i} to MoveInfo({kstr(n)}, {ty}, {p}),\n" for i, (n, ty, p) in sorted(new["moves"].items())) + ") }\n")
    out.append("\ninternal val itemNamesEliteRedux: Map<Int, String> by lazy { mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(new["items"].items())) + ") }\n")
    out.append("\n// gItems' pocket, as the app's POCKET_* (Medicine, Battle Items and Mega Stones under ITEMS).\n"
               "internal val itemPocketsEliteRedux: Map<Int, Int> by lazy { mapOf(\n" +
               "".join(f"    {i} to {p},\n" for i, p in sorted(new["pockets"].items())) + ") }\n")
    out.append("\n// Vanilla type ids, FAIRY 18; the chart: key = atkType*100+defType -> percent, pairs not listed are 100.\n"
               "internal val typeNamesEliteRedux: Map<Int, String> = mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(TYPE_NAMES.items())) + ")\n\n"
               "internal val typeEffectivenessEliteRedux: Map<Int, Int> by lazy { mapOf(\n" +
               "".join(f"    {k} to {v},\n" for k, v in sorted(new["chart"].items())) + ") }\n")
    out.append("\n// gRegionMapEntries by mapsec id, rects in 8px tiles on Emerald's Hoenn map (its region_map.c and grid);\n"
               "// region -1 = not on the map.\n"
               "internal val mapSecDataEliteRedux: Map<Int, MapSecInfo> by lazy { mapOf(\n" + "".join(rows) + ") }\n")
    d_sp, d_ty, d_mv, d_it, d_pk = diff("species"), diff("types"), diff("moves"), diff("items"), diff("pockets")
    out.append("\n// 2.65.3b: what it names / types differently from 2.65.4.1b.\n"
               "internal val speciesNamesEliteRedux265_3: Map<Int, String> by lazy { speciesNamesEliteRedux + mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(d_sp.items())) + ") }\n"
               "internal val speciesTypeDataEliteRedux265_3: Map<Int, SpeciesTypes> by lazy { speciesTypeDataEliteRedux + mapOf(\n" +
               "".join(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(d_ty.items())) + ") }\n"
               "internal val moveDataEliteRedux265_3: Map<Int, MoveInfo> by lazy { moveDataEliteRedux + mapOf(\n" +
               "".join(f"    {i} to MoveInfo({kstr(n)}, {ty}, {p}),\n" for i, (n, ty, p) in sorted(d_mv.items())) + ") }\n"
               "internal val itemNamesEliteRedux265_3: Map<Int, String> by lazy { itemNamesEliteRedux + mapOf(\n" +
               "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(d_it.items())) + ") }\n"
               "internal val itemPocketsEliteRedux265_3: Map<Int, Int> by lazy { itemPocketsEliteRedux + mapOf(\n" +
               "".join(f"    {i} to {p},\n" for i, p in sorted(d_pk.items())) + ") }\n")
    path = os.path.join(OUT_DIR, "EliteReduxGen.kt")
    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(out))
    print("wrote", os.path.relpath(path))
    print(f"2.65.4.1b: {len(new['species'])} species, {len(new['moves'])} moves, {len(new['items'])} items, "
          f"{len(new['mapsecs'])} map sections; 2.65.3b differs in {len(d_sp)} species names, {len(d_ty)} types, "
          f"{len(d_mv)} moves, {len(d_it)} items, {len(d_pk)} pockets")


if __name__ == "__main__":
    main()
