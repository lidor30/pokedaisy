#!/usr/bin/env python3
"""Generates Pokémon Modern Emerald's tables (v3.6, resetes12's pokeemerald fork, BPEE, 32 MB) from the player's ROM; the
per-save type rules come from its public source at the release's tag (nothing is downloaded here).

    scripts/gen_modern_emerald_tables.py <Pokemon Modern Emerald (v3.6).gba> <$DECOMPS/pokemodernemerald>

It runs as EMERALD with these laid over Emerald's tables (ActiveTables' `emeraldHack`, ModernEmerald.kt's HackTexts).
Read from the ROM: the GF header (0x08000100) gives gSpeciesNames, gMoveNames, gSpeciesInfo (0x28-byte records: types +6,
gender ratio +0x10, growth rate +0x13, types_old +0x20, types_new +0x24), gItems (44 bytes) and gBattleMoves (12 bytes:
power +1, type +2); gTypeNames, the two type charts and gRegionMapEntries sit at the addresses `make modern` of the tag
puts them, moved by the release's own layout (each found by its bytes, ROM pointers masked: docs/DEVELOPMENT.md's
"Modern Emerald"). 461 species (EGG is 462), 374 moves, 397 items, FAIRY is type 18; its text is mixed case (`ownCase`).

The game picks a species' types per save (pokemon.c's GetTypeBySpecies, by SaveBlock1's tx_Mode_Modern_Types and
tx_Mode_Fairy_Types): the species it names are read from that function's source, with their `types_old` / `types_new`.
"""
import hashlib
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_emerald_lang_tables import CHARS  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin",
                   "com", "pokedaisy", "app", "companion", "data", "ModernEmeraldGen.kt")
SHA1 = "ab3bc012598d73dce965450df79893aeff97c834"
B = 0x08000000
SPECIES, MOVES, ITEMS, TYPES = 462, 375, 398, 19
SPECIES_INFO_SIZE = 0x28   # 0x4830 bytes for 462 species
TYPE_NAMES = 0x08686B74
CHART_MODERN = 0x086868A0      # gTypeEffectiveness: TYPE CHART "Modern" (tx_Mode_TypeEffectiveness 1)
CHART_GEN6 = 0x08686A04        # gTypeEffectiveness_GenVI: "Gen VI+" (0, the default)
REGION_MAP_ENTRIES = 0x089192D8
MAPSEC_COUNT = 0xD5            # Emerald's MAPSEC_NONE: the boxes before it
CH = dict(CHARS)


def u8(d, a):
    return d[a - B]


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


def header(d, off):
    return struct.unpack_from("<I", d, 0x100 + off)[0]


def chart(d, a):
    out = {}
    while u8(d, a) != 0xFF:
        atk, dfn, x = u8(d, a), u8(d, a + 1), u8(d, a + 2)
        a += 3
        if atk != 0xFE:   # the Foresight separator
            out[atk * 100 + dfn] = x * 10
    return out


def read(d):
    names, moves, info, items, battle = header(d, 0x44), header(d, 0x48), header(d, 0xBC), header(d, 0xC8), header(d, 0xCC)
    sp, ty, old, new, gr, gw = {}, {}, {}, {}, [], []
    for i in range(SPECIES):
        a = info + SPECIES_INFO_SIZE * i
        if 0 < i < SPECIES:
            sp[i] = text(d, names + 11 * i, 11)
            ty[i] = (u8(d, a + 6), u8(d, a + 7))
            old[i] = (u8(d, a + 0x20), u8(d, a + 0x21))
            new[i] = (u8(d, a + 0x24), u8(d, a + 0x25))
        gr.append(u8(d, a + 0x10))
        gw.append(u8(d, a + 0x13))
    mv = {i: (text(d, moves + 13 * i, 13), u8(d, battle + 12 * i + 2), u8(d, battle + 12 * i + 1)) for i in range(1, MOVES)}
    it = {i: text(d, items + 44 * i, 14) for i in range(1, ITEMS)}
    tn = {i: text(d, TYPE_NAMES + 7 * i, 7) for i in range(TYPES)}
    ms = {}
    for i in range(MAPSEC_COUNT):
        p = u32(d, REGION_MAP_ENTRIES + 8 * i + 4)
        ms[i] = text(d, p, 32) if B <= p < B + len(d) else None
    return dict(species=sp, types=ty, old=old, new=new, gender=gr, growth=gw, moves=mv, items=it, type_names=tn,
                mapsecs=ms, modern=chart(d, CHART_MODERN), gen6=chart(d, CHART_GEN6))


def species_ids(src):
    ids = {}
    for line in open(os.path.join(src, "include", "constants", "species.h"), encoding="utf-8"):
        m = re.match(r"#define (SPECIES_\w+)\s+(\d+)\b", line)
        if m:
            ids[m.group(1)] = int(m.group(2))
    return ids


def type_rules(src, ids):
    """GetTypeBySpecies' three lists: Modern Types off -> types_old, Fairy Types off -> types_old, Modern Types on ->
    types_new (pokemon.c), in the order the function checks them."""
    body = open(os.path.join(src, "src", "pokemon.c"), encoding="utf-8").read()
    fn = body[body.index("u8 GetTypeBySpecies("):]
    fn = fn[:fn.index("\n}\n")]
    parts = re.split(r"else if|if \(\(gSaveBlock1Ptr", fn)
    lists = []
    for p in parts:
        names = re.findall(r"species == (SPECIES_\w+)", p)
        if names:
            lists.append([ids[n] for n in names])
    assert len(lists) == 3, "GetTypeBySpecies changed shape"
    return lists


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    d = open(sys.argv[1], "rb").read()
    if hashlib.sha1(d).hexdigest() != SHA1:
        sys.exit("not Pokemon Modern Emerald v3.6 (sha1 ab3bc012...)")
    t = read(d)
    ids = species_ids(sys.argv[2])
    classic, nofairy, modern_new = type_rules(sys.argv[2], ids)
    # What the source says, the ROM holds: 18 is FAIRY, CLEFAIRY's base types are Fairy and its old ones Normal.
    assert t["type_names"][18] == "Fairy" and t["type_names"][9] is None   # "???" reads as blank
    assert t["species"][35] == "Clefairy" and t["types"][35] == (18, 18) and t["old"][35] == (0, 0)
    assert t["species"][444] == "Sylveon" and t["moves"][355][0] == "Dark Pulse" and t["items"][397] == "Timid Mint"
    assert len(classic) == 21 and len(nofairy) == 20 and len(modern_new) == 2
    assert t["modern"] != t["gen6"] and t["gen6"][18 * 100 + 16] == 200
    sp = {k: v for k, v in t["species"].items() if v}
    rows = lambda m, f: "".join(f"    {k} to {f(v)},\n" for k, v in sorted(m.items()))
    pairs = lambda ids_: {i: t["old"][i] for i in ids_}
    out = ["package com.pokedaisy.app.companion.data\n\n",
           "// GENERATED by scripts/gen_modern_emerald_tables.py from the Pokemon Modern Emerald v3.6 ROM and its source\n",
           "// (resetes12/pokeemerald, tag Release3.6) - do not hand-edit. Names in the ROM's own casing, by its ids\n",
           "// (Emerald's up to 411, its own to 461); ModernEmerald.kt lays them over Emerald's per save.\n\n",
           "internal val speciesNamesModernEmerald: Map<Int, String> = mapOf(\n", rows(sp, kstr), ")\n\n",
           "/** gSpeciesInfo's `types`: what the game shows unless a save's settings pick `types_old` / `types_new`. */\n",
           "internal val speciesTypesModernEmerald: Map<Int, SpeciesTypes> = mapOf(\n",
           rows({k: v for k, v in t["types"].items() if k in sp}, lambda v: f"SpeciesTypes({v[0]}, {v[1]})"), ")\n\n",
           "/** GetTypeBySpecies' species that go back to `types_old` with MODERN TYPES off (SaveBlock1's tx_Mode_Modern_Types 0). */\n",
           "internal val classicTypesModernEmerald: Map<Int, SpeciesTypes> = mapOf(\n",
           rows(pairs(classic), lambda v: f"SpeciesTypes({v[0]}, {v[1]})"), ")\n\n",
           "/** ... and with FAIRY TYPE off (tx_Mode_Fairy_Types 0): the 20 that became Fairy, their `types_old`. */\n",
           "internal val noFairyTypesModernEmerald: Map<Int, SpeciesTypes> = mapOf(\n",
           rows(pairs(nofairy), lambda v: f"SpeciesTypes({v[0]}, {v[1]})"), ")\n\n",
           "/** ... and with MODERN TYPES on: `types_new` for these. */\n",
           "internal val modernNewTypesModernEmerald: Map<Int, SpeciesTypes> = mapOf(\n",
           rows({i: t["new"][i] for i in modern_new}, lambda v: f"SpeciesTypes({v[0]}, {v[1]})"), ")\n\n",
           "internal val movesModernEmerald: Map<Int, MoveInfo> = mapOf(\n",
           rows({k: v for k, v in t["moves"].items() if v[0]}, lambda v: f"MoveInfo({kstr(v[0])}, {v[1]}, {v[2]})"), ")\n\n",
           "internal val itemNamesModernEmerald: Map<Int, String> = mapOf(\n",
           rows({k: v for k, v in t["items"].items() if v}, kstr), ")\n\n",
           "internal val mapSecNamesModernEmerald: Map<Int, String> = mapOf(\n",
           rows({k: v for k, v in t["mapsecs"].items() if v}, kstr), ")\n\n",
           "/** gTypeEffectiveness_GenVI: TYPE CHART \"Gen VI+\", the default (atk * 100 + def -> percent). */\n",
           "internal val typeChartGen6ModernEmerald: Map<Int, Int> = mapOf(\n", rows(t["gen6"], str), ")\n\n",
           "/** gTypeEffectiveness: TYPE CHART \"Modern\" (tx_Mode_TypeEffectiveness 1). */\n",
           "internal val typeChartModernModernEmerald: Map<Int, Int> = mapOf(\n", rows(t["modern"], str), ")\n\n",
           "internal val genderRatiosModernEmerald: IntArray = unhexRatios(\"" + bytes(t["gender"]).hex() + "\")\n\n",
           "internal val growthRatesModernEmerald: ByteArray = unhexBytes(\"" + bytes(t["growth"]).hex() + "\")\n"]
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("".join(out))
    print("wrote", os.path.relpath(OUT), f": {len(sp)} species, {len(classic)} + {len(nofairy)} + {len(modern_new)} per-save "
          f"types, {sum(1 for v in t['moves'].values() if v[0])} moves, {sum(1 for v in t['items'].values() if v)} items, "
          f"{sum(1 for v in t['mapsecs'].values() if v)} map sections, charts {len(t['gen6'])} / {len(t['modern'])}")


if __name__ == "__main__":
    main()
