#!/usr/bin/env python3
"""Generates PokeDaisy's display tables for Polished Crystal (Rangi42's open-source Crystal hack, v3.2.x) from
its release ROMs (nothing is bundled):

    scripts/gen_polished_crystal_tables.py <releases dir>

<releases dir> holds the official release files - polishedcrystal[-faithful]-3.2.N.gbc and the .sym file each
release ships beside it (they are the player's ROMs byte for byte: the SHA-1s are in RELEASES below). The text
encoding comes from the source's constants/charmap.asm and the map list from data/maps/maps.asm, at
$DECOMPS/polishedcrystal/src (tag v3.2.3).

Polished Crystal keeps Crystal's engine but reshapes its data:
  - Species are 9-bit: the species byte plus the EXTSPECIES bit (the party struct's form byte, bit 5), 1..0x122
    (0xFF is the egg's slot, 0x100 unused). Regional / variant forms (ALOLAN RATTATA, GALARIAN MEOWTH, ...) are their
    own entries of every per-species table after NUM_SPECIES, in VariantSpeciesAndFormTable's order
    (GetSpeciesAndFormIndex). The companion's species id is that index + 1: a species' own 9-bit value, then
    NUM_SPECIES + 1 + k for variant form k.
  - Names: PokemonNames, 10 bytes each by 9-bit species (a variant is named as its species); MoveNames / ItemNames /
    KeyItemNames / ApricornNames are '@'-terminated lists in its own charmap ('@' is 0x53, text is mixed case and
    0x0A..0x4C are letter pairs).
  - BaseData: BASE_DATA_SIZE bytes per index (stats, types +6 / +7, gender ratio code in +0xC's high nibble, growth rate
    +0x10), Moves: 8 bytes (power +2, type +3, category +7).
  - Items: 1..NUM_ITEMS; key items and TMs / HMs are id spaces of their own. The companion keeps them apart: TM / HM n
    as 0x100 + n (named "TM01" + its move, as the pocket lists them), key item n as 0x200 + n. (Apricorns are counts in
    the APRICORN BOX key item, not in the bag.)
  - Types: Gen 2's ids renumbered (0 NORMAL .. 8 STEEL, 9 FIRE .. 16 DARK, 17 FAIRY, 18 ???); TypeMatchups are
    (attacker, defender, multiplier in 4.4 fixed point), 0xFE before Foresight's pairs, 0xFF at the end.
  - Landmarks: (x + 8, y + 16, name*), Johto from 1, Kanto from KANTO_LANDMARK, the Orange Islands from SHAMOUTI_LANDMARK;
    map headers are 7 bytes (landmark +4).
The faithful builds keep the original games' types and moves where the normal ones changed them: they get their own
species types and move data where those differ (the names, items, landmarks and maps are the same in every build).
"""
import hashlib
import os
import re
import struct
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin", "com", "pokedaisy",
                       "app", "companion", "data")
SRC = os.path.join(os.environ.get("DECOMPS", ""), "polishedcrystal", "src")

# The release builds this reads (the official GitHub releases' files = the player's): normal, faithful and their debug
# builds (the same RAM and tables, a debug menu more), 3.2.0 - 3.2.3.
RELEASES = {
    "polishedcrystal-3.2.3": "6930b48af5844d373e3c9130f26d6dd1084cf4ed",
    "polishedcrystal-3.2.2": "beb8b38280af59e9ef84be12684a6ecf09021c9b",
    "polishedcrystal-3.2.1": "79ebf9984eac8081f488d15a35a6c5d11b4f84e2",
    "polishedcrystal-3.2.0": "e55859484c5cda70e05daed59b14662e0a0f8eac",
    "polishedcrystal-faithful-3.2.3": "3ff091a8e94a0c031372b6f0275e12620fe3d766",
    "polishedcrystal-faithful-3.2.2": "e63b1e52a9f71c3948e3e7e515e9939d728fc679",
    "polishedcrystal-faithful-3.2.1": "666f8edfb9efd8c301adbc14d38ccfaae33a047d",
    "polishedcrystal-faithful-3.2.0": "b012f6d55c34c972778f5566e1cc910962dd7ab5",
    "polishedcrystal-debug-3.2.3": "457d61dfc28a17388b87792595433ba20bd1c1ed",
    "polishedcrystal-debug-3.2.2": "bea9804dc88a4ee370af62ff3139fdf1380277bf",
    "polishedcrystal-debug-3.2.1": "9c13585eb34efdcd881c2e68f029b820bddf349d",
    "polishedcrystal-debug-3.2.0": "11d461629363402fc15b5005ef51b8f44e726140",
    "polishedcrystal-faithful-debug-3.2.3": "b5393eb27fec51970cc9186f36c34e94494bfd39",
    "polishedcrystal-faithful-debug-3.2.2": "772858dd5a3e66dea4454d5f83b48c83d732ca69",
    "polishedcrystal-faithful-debug-3.2.1": "c4bd263e58325c6c04c34462c1b4a37fba085e9c",
    "polishedcrystal-faithful-debug-3.2.0": "c0a2c70d73ce68830a891ddd292300c90766acdd",
}
TM_COUNT, HM_COUNT = 75, 6   # constants/tmhm_constants.asm: add_tm / add_hm
NUM_SPECIES = 0x123          # constants/pokemon_constants.asm (const_value - 1 after the last 9-bit species)
TERM = 0x53                  # '@'


def charmap():
    m = {}
    for line in open(os.path.join(SRC, "constants", "charmap.asm"), encoding="utf-8"):
        x = re.match(r'\s*(?:charmap|ctxtmap)\s+"((?:[^"\\]|\\.)*)",\s*\$([0-9a-f]{2})', line)
        if x:
            m.setdefault(int(x.group(2), 16), x.group(1))
    # The Pokémon / ball glyphs print as their words; '#' is "POKé" in the game's own text.
    m[0x5E] = " "   # ¯: the town map's line break inside a place's name ("New Bark¯Town")
    m[0x4D] = "Poké"
    m[0x4E] = "Pokémon"
    return m


CHARS = charmap()


def num(path, name):
    """A DEF NUM_* constant of a constants file, evaluated the way rgbds counts const_def / const / const_skip."""
    vals, value = {}, 0
    for line in open(os.path.join(SRC, "constants", path), encoding="utf-8"):
        line = line.split(";")[0].strip()
        if m := re.match(r"const_def\s*(\$?[0-9a-fA-F]+)?$", line):
            value = int(m.group(1).replace("$", "0x"), 0) if m.group(1) else 0
        elif m := re.match(r"const_skip\s*(\d+)?$", line):
            value += int(m.group(1) or 1)
        elif m := re.match(r"const\s+(\w+)", line):
            vals[m.group(1)] = value
            value += 1
        elif m := re.match(r"DEF\s+(\w+)\s+EQU\s+const_value\s*(?:-\s*(\w+))?$", line):
            sub = m.group(2)
            vals[m.group(1)] = value - (0 if sub is None else int(sub) if sub.isdigit() else vals[sub])
    return vals[name]


class Build:
    def __init__(self, path, name):
        self.name = name
        self.d = open(path, "rb").read()
        assert hashlib.sha1(self.d).hexdigest() == RELEASES[name], f"{path} isn't the {name} release"
        self.sym = {}
        for line in open(path[:-4] + ".sym"):
            if m := re.match(r"([0-9a-f]{2}):([0-9a-f]{4}) (\S+)", line):
                self.sym.setdefault(m.group(3), (int(m.group(1), 16), int(m.group(2), 16)))

    def off(self, label, plus=0):
        bank, addr = self.sym[label]
        return (addr if bank == 0 else bank * 0x4000 + (addr - 0x4000)) + plus

    def text(self, o, n=None):
        out, i = [], o
        while n is None or i < o + n:
            c = self.d[i]
            i += 1
            if c == TERM:
                break
            out.append(CHARS.get(c, "?"))
        return "".join(out)

    def names(self, label, count):
        """[count] '@'-terminated strings from [label]."""
        out, o = [], self.off(label)
        for _ in range(count):
            j = self.d.index(TERM, o)
            out.append(self.text(o, j - o + 1))
            o = j + 1
        return out

    def u8(self, label, plus=0):
        return self.d[self.off(label, plus)]

    def u16(self, label, plus=0):
        return struct.unpack_from("<H", self.d, self.off(label, plus))[0]


def read(b):
    """Every table, from build [b], keyed as the companion keys them."""
    species_count = NUM_SPECIES
    # Variant forms, in GetSpeciesAndFormIndex's table order: (9-bit species, form bits).
    variants, o = [], b.off("VariantSpeciesAndFormTable")
    while b.d[o] != 0:
        variants.append((b.d[o] | (0x100 if b.d[o + 1] & 0x20 else 0), b.d[o + 1] & 0x1F))
        o += 2
    names = {}
    pn = b.off("PokemonNames")
    for s in range(1, species_count + 1):
        names[s] = b.text(pn + 10 * s, 10)
    # (0xFF is the egg's: the companion shows an egg as 255, named and drawn as the game's.)
    ids = {s: names[s] for s in range(1, species_count + 1) if not names[s].startswith("?")}
    for k, (s, form) in enumerate(variants):
        ids[NUM_SPECIES + 1 + k] = names[s]
    # BaseData by index (the companion's id - 1): its size from BULBASAUR's and IVYSAUR's stats.
    base = b.off("BaseData")
    size = next(n for n in range(20, 64) if b.d[base + n:base + n + 6] == bytes([60, 62, 63, 60, 80, 80]))
    assert b.d[base:base + 6] == bytes([45, 49, 49, 45, 65, 65]), "BaseData isn't BULBASAUR's"
    types, genders, growth = {}, {}, {}
    gender_codes = {0: 0, 1: 31, 2: 63, 3: 127, 4: 191, 5: 254, 6: 255, 7: 255}  # GENDER_F0 .. F100, UNKNOWN
    for i in ids:
        e = base + size * (i - 1)
        types[i] = (b.d[e + 6], b.d[e + 7])
        genders[i] = gender_codes.get(b.d[e + 0xC] >> 4, 255)
        growth[i] = b.d[e + 0x10]
    # Moves.
    mv = b.off("Moves")
    move_names = b.names("MoveNames", num("move_constants.asm", "NUM_ATTACKS"))
    moves = {}
    for i, n in enumerate(move_names):
        e = mv + 8 * i
        assert b.d[e + 3] <= 0x12 and b.d[e + 7] <= 2, f"move {i + 1}: type {b.d[e + 3]}, category {b.d[e + 7]}"
        moves[i + 1] = (n, b.d[e + 3], b.d[e + 2], b.d[e + 7])
    # Items, key items.
    item_names = b.names("ItemNames", num("item_constants.asm", "NUM_ITEMS") + 1)
    items = {i: item_names[i] for i in range(1, len(item_names))}
    key_names = b.names("KeyItemNames", num("item_constants.asm", "NUM_KEY_ITEMS") + 1)
    for k in range(1, len(key_names)):
        items[0x200 + k] = key_names[k]
    # TMs / HMs: a bit each in wTMsHMs (reusable), the moves TMHMMoves lists (then the tutors' 31, not in the pocket).
    tm_count, hm_count = TM_COUNT, HM_COUNT
    tms = []
    for n in range(tm_count + hm_count):
        move = b.d[b.off("TMHMMoves") + n]
        label = f"TM{n + 1:02d}" if n < tm_count else f"HM{n - tm_count + 1:02d}"
        items[0x101 + n] = f"{label} {move_names[move - 1]}"
        tms.append(0x101 + n)
    # Types: TypeNames' pointers (the ??? of CURSE left out); TypeMatchups (multiplier in 4.4 fixed point).
    # Type names: TypeNames is a byte offset per type to its string (data/types/names.asm); the strings, in that order.
    src = open(os.path.join(SRC, "data", "types", "names.asm"), encoding="utf-8").read()
    order = re.findall(r"^\tdr (\w+)", src.split("EggGroupNames")[0], re.M)
    text_of = dict(re.findall(r'^(\w+):\s+db "([^@"]*)@"', src, re.M))
    type_names = {t: text_of[label] for t, label in enumerate(order) if label != "UnknownType"}
    chart, o = {}, b.off("TypeMatchups")
    while b.d[o] != 0xFF:
        if b.d[o] == 0xFE:
            o += 1
            continue
        chart[b.d[o] * 100 + b.d[o + 1]] = b.d[o + 2] * 100 // 16
        o += 3
    # Landmarks: (x + 8, y + 16, name*) in Landmarks' bank; regions by KANTO_LANDMARK / SHAMOUTI_LANDMARK.
    kanto, orange = num("landmark_constants.asm", "KANTO_LANDMARK"), num("landmark_constants.asm", "SHAMOUTI_LANDMARK")
    count = num("landmark_constants.asm", "NUM_LANDMARKS")
    lm, lbank = b.off("Landmarks"), b.sym["Landmarks"][0]
    landmarks = {}
    for i in range(count):
        x, y, ptr = b.d[lm + 4 * i] - 8, b.d[lm + 4 * i + 1] - 16, struct.unpack_from("<H", b.d, lm + 4 * i + 2)[0]
        region = -1 if i == 0 or x < 0 or y < 0 else 0 if i < kanto else 1 if i < orange else 2
        landmarks[i] = (b.text(lbank * 0x4000 + ptr - 0x4000), region, max(0, x) // 8, max(0, y) // 8)
    # Maps: maps.asm's groups (each group's headers in order), each header's landmark (+4) from the ROM.
    maps, group, num_in = {}, 0, 0
    gp, gbank = b.off("MapGroupPointers"), b.sym["MapGroupPointers"][0]
    for line in open(os.path.join(SRC, "data", "maps", "maps.asm"), encoding="utf-8"):
        if m := re.match(r"MapGroup(\d+):", line):
            group, num_in = int(m.group(1)), 0
        elif group and re.match(r"\s*map\s+\w+", line):
            num_in += 1
            first = struct.unpack_from("<H", b.d, gp + 2 * (group - 1))[0]
            h = gbank * 0x4000 + first - 0x4000 + 7 * (num_in - 1)
            maps[(group, num_in)] = b.d[h + 4]
    assert all(v < count for v in maps.values()), "a map's landmark"
    return dict(ids=ids, variants=variants, types=types, genders=genders, growth=growth, moves=moves,
                items=items, tms=tms, type_names=type_names, chart=chart, landmarks=landmarks, maps=maps)


def kstr(v):
    return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'


def kmap(name, typ, pairs, doc=""):
    return (doc + f"val {name}: Map<Int, {typ}> by lazy {{\n    mapOf(\n" +
            "".join(f"        {k} to {v},\n" for k, v in pairs) + "    )\n}\n")


def write(name, body):
    path = os.path.join(OUT_DIR, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write("package com.pokedaisy.app.companion.data\n\n// GENERATED by scripts/gen_polished_crystal_tables.py from the Polished "
                "Crystal 3.2.x release ROMs\n// - do not hand-edit.\n\n" + body)
    print("wrote", os.path.relpath(path))


def changes(base, other):
    return {k: v for k, v in other.items() if base.get(k) != v}


def main():
    if len(sys.argv) != 2:
        sys.exit(f"usage: {sys.argv[0]} <releases dir>")
    builds = [Build(os.path.join(sys.argv[1], n + ".gbc"), n) for n in RELEASES]
    t = {b.name: read(b) for b in builds}
    base, faithful = t["polishedcrystal-3.2.3"], t["polishedcrystal-faithful-3.2.3"]
    for n, x in t.items():
        ref = faithful if "faithful" in n else base
        assert x == ref, f"{n} differs from {'faithful ' if 'faithful' in n else ''}3.2.3: {[k for k in x if x[k] != ref[k]]}"
    for k in faithful:
        if k not in ("types", "moves", "items"):
            assert faithful[k] == base[k], f"faithful's {k} differ"
    sfx = "PolishedCrystal"
    ids = base["ids"]
    write(f"SpeciesNames{sfx}.kt", "// PokemonNames by the companion's species id: a species' own 9-bit value, then NUM_SPECIES + 1 + k for\n"
          "// VariantSpeciesAndFormTable's entry k (named as its species).\n" +
          kmap(f"speciesNames{sfx}", "String", [(k, kstr(ids[k])) for k in sorted(ids)]))
    ty = lambda m: [(k, f"SpeciesTypes({a}, {b})") for k, (a, b) in sorted(m.items())]
    write(f"SpeciesTypes{sfx}.kt", "// BaseData types (Polished's type ids, typeNames" + sfx + "); the faithful builds' changes after.\n" +
          kmap(f"speciesTypeData{sfx}", "SpeciesTypes", ty(base["types"])) + "\n" +
          kmap(f"speciesTypeData{sfx}FaithfulChanges", "SpeciesTypes", ty(changes(base["types"], faithful["types"]))))
    mv = lambda m: [(k, f"MoveInfo({kstr(n)}, {ty_}, {p})") for k, (n, ty_, p, c) in sorted(m.items())]
    write(f"MoveData{sfx}.kt", "// MoveNames + Moves (type, power); the faithful builds' changes after.\n" +
          kmap(f"moveData{sfx}", "MoveInfo", mv(base["moves"])) + "\n" +
          kmap(f"moveData{sfx}FaithfulChanges", "MoveInfo", mv(changes(base["moves"], faithful["moves"]))))
    it = lambda m: [(f"0x{k:X}" if k > 0xFF else k, kstr(v)) for k, v in sorted(m.items())]
    write(f"ItemNames{sfx}.kt", "// ItemNames 1..; TM / HM n as 0x100 + n (its move), key item n as 0x200 + n (each an id space of\n"
          "// its own in the game); the faithful builds' changes after.\n" +
          kmap(f"itemNames{sfx}", "String", it(base["items"])) + "\n" +
          kmap(f"itemNames{sfx}FaithfulChanges", "String", it(changes(base["items"], faithful["items"]))))
    write(f"TypeChart{sfx}.kt", "// TypeNames (0 Normal .. 8 Steel, 9 Fire .. 16 Dark, 17 Fairy) and TypeMatchups: key =\n"
          "// atkType*100+defType -> percent, pairs not listed are 100.\n" +
          kmap(f"typeNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(base["type_names"].items())]) + "\n" +
          kmap(f"typeEffectiveness{sfx}", "Int", sorted(base["chart"].items())))
    lms = base["landmarks"]
    write(f"MapSecData{sfx}.kt", "// Landmarks: region 0 Johto, 1 Kanto, 2 the Orange Islands (the town maps), -1 not on a map;\n"
          "// x / y = the 8px cell under the place's icon. Then each map's landmark by (group, number) (header +4).\n" +
          kmap(f"mapSecData{sfx}", "MapSecInfo", [(i, f"MapSecInfo({kstr(n)}, {r}, {x}, {y}, 1, 1)") for i, (n, r, x, y) in sorted(lms.items())]) +
          "\n/** wMapGroup * 256 + wMapNumber -> landmark id ([mapSecData" + sfx + "]). */\n" +
          kmap(f"mapLandmarks{sfx}", "Int", [(g * 256 + n, v) for (g, n), v in sorted(base["maps"].items())]))
    top = max(ids)
    arr = lambda name, m, default: (f"val {name} = intArrayOf(\n" + "".join(f"    {m.get(i, default)},\n" for i in range(top + 1)) + ")\n")
    write(f"Gen2Species{sfx}.kt", "// By the companion's species id: BaseData's gender ratio as Crystal's byte (a code there: female\n"
          "// share 0 / 12.5 / 25 / 50 / 75 / 100 %, genderless) and growth rate; the TM / HM pocket's ids; the variant forms\n"
          "// (VariantSpeciesAndFormTable: 9-bit species << 8 | form), whose ids follow NUM_SPECIES.\n" +
          arr(f"gen2GenderRatios{sfx}", base["genders"], 255) + "\n" + arr(f"gen2GrowthRates{sfx}", base["growth"], 0) + "\n" +
          f"val gen2TmItems{sfx} = intArrayOf(\n" + "".join(f"    0x{v:X},\n" for v in base["tms"]) + ")\n\n" +
          f"const val POLISHED_NUM_SPECIES = 0x{NUM_SPECIES:X}\n\n" +
          f"val polishedVariantForms = intArrayOf(\n" + "".join(f"    0x{s_ << 8 | f:X},\n" for s_, f in base["variants"]) + ")\n")
    # Each build's art (Gen2Art.polishedCrystal): where its font, party minis, palettes and town maps sit, bank << 16 | address.
    art = ["FontNormal", "MiniIconPointers", "PokemonPalettes", "HeldItemIcons", "PartyMenuOBPals", "TownMapGFX", "JohtoMap",
           "KantoMap", "OrangeMap", "GetNextTownMapTilePalette.PalMap", "PokegearPals", "PokedexDataPointerTable",
           "PokemonBodyData", "PokemonPicPointers", "PokemonPicSizes", "BaseData",
           # The town map's player (its overworld sprite by wPlayerGender, in PokegearOBPals' red / blue / green), the bag's
           # descriptions (items, key items, a TM's move) and the dex's ability names.
           "PokegearOBPals", "ChrisSpriteGFX", "KrisSpriteGFX", "CrysSpriteGFX", "ItemDescriptions", "KeyItemDescriptions",
           "MoveDescriptions", "AbilityNames", "TMHMMoves",
           # The Huffman tree of its compressed text (<CTXT>: home/text.asm's ReadHuffmanChar).
           "TextCompressionHuffmanTree", "EvosAttacksPointers", "EvosAttacks"]
    rows = []
    for b in builds:
        cosmetic = (b.sym["VariantSpeciesAndFormTable"][1] - b.sym["CosmeticSpeciesAndFormTable"][1]) // 2
        assert cosmetic == 0x38, f"{b.name}: {cosmetic} cosmetic forms"
        rows.append(f'    "{RELEASES[b.name]}" to intArrayOf(' + ", ".join(f"0x{b.sym[n][0]:02X}{b.sym[n][1]:04X}" for n in art) +
                    f"),   // {b.name}\n")
    sizes = {b.name: next(n for n in range(20, 64) if b.d[b.off("BaseData") + n:b.off("BaseData") + n + 6] ==
                          bytes([60, 62, 63, 60, 80, 80])) for b in builds}
    assert len(set(sizes.values())) == 1, sizes
    # The National order the dex lists (GetNationalDexNumber: a species' 9-bit value, less 2 past 0xFF / 0x100).
    order = [n if n <= 0xFE else n + 2 for n in range(1, NUM_SPECIES - 1)]
    # The game's text characters (the default charmap: letters, letter pairs, "#" = Poké), for the dex pages in Kotlin.
    chars = [CHARS.get(c, "") if not CHARS.get(c, "").startswith("<") else "" for c in range(256)]
    chars[0x56] = chars[0x57] = chars[0x58] = chars[0x59] = " "   # <NEXT> <LINE> <CONT> <PARA>: a line break
    write(f"Gen2{sfx}Gen.kt", "// Each release build's ROM tables, by its SHA-1 (its .sym), bank << 16 | address: " + ", ".join(art) +
          ".\n// Mini icons are by GetCosmeticSpeciesAndFormIndex: a species' 9-bit value - 1, then 0x38 cosmetic forms, then the\n"
          "// variant forms. Then BaseData's record size, the dex's National order (species by No.) and the text characters.\n" +
          "internal const val POLISHED_NUM_COSMETIC_FORMS = 0x38\n\n"
          f"internal const val POLISHED_BASE_DATA_SIZE = {next(iter(sizes.values()))}\n\n"
          "internal val polishedCrystalAddrs: Map<String, IntArray> = mapOf(\n" + "".join(rows) + ")\n\n"
          f"val gen2DexOrder{sfx} = intArrayOf(\n" + "".join(f"    {v},\n" for v in order) + ")\n\n"
          "internal val polishedTextChars: Array<String> = arrayOf(\n" + "".join(f"    {kstr(c)},\n" for c in chars) + ")\n")
    print(f"{len(ids)} species / forms, {len(base['moves'])} moves, {len(base['items'])} items, {len(base['type_names'])} types, "
          f"{len(lms)} landmarks, {len(base['maps'])} maps; every build checked (faithful: {len(changes(base['types'], faithful['types']))} "
          f"species' types, {len(changes(base['moves'], faithful['moves']))} moves, {len(changes(base['items'], faithful['items']))} items its own)")


if __name__ == "__main__":
    main()
