#!/usr/bin/env python3
"""Generates the game text of the other Emerald releases - Spanish, German,
French, Italian, Japanese - from the ROM the user owns (nothing is downloaded),
so the companion shows a game's names in its own language.

    scripts/gen_emerald_lang_tables.py <es|de|fr|it|ja|kaizo|legacy> <rom.gba>
    scripts/gen_emerald_lang_tables.py en <english rom.gba>   (checks only)

Writes shared/src/commonMain/kotlin/.../companion/data/EmeraldText<Lang>Gen.kt: species,
move, item, nature and map section names, keyed by the same
ids as English (Gen 3's ids don't change between languages), plus the small
font's glyph widths (the party slot's names; a few localized glyphs differ).
Item descriptions aren't written: the app reads them from the player's ROM
(NativeConfig.itemDescs = the `items` address below, RomItemText.kt).

`kaizo` is Pokémon Emerald Kaizo v2.0, an in-place edit of English Emerald: English's tables at English's
addresses, 29 moves replaced (Drill Run, Earth Power, ...) and 217 moves' battle data changed - so it also writes
the moves' types and powers (gBattleMoves), which the other languages take from the shared English table.

`legacy` is Pokémon Emerald Legacy v1.1.4, pokeemerald rebuilt from its own source (cRz-Shadows/Pokemon_Emerald_Legacy,
tag V1.1.4, built with agbcc byte for byte = the release): every table moved, so its addresses are its ELF's symbols. Three
Deoxys forms sit at species 412-414 (SPECIES_EGG is 415), ~130 moves were rebalanced (a few change type) and it adds two
move effects, so it writes the moves' types / powers and their MoveStats (the move advice) too.

Every other table sits elsewhere than in English (localized text has other lengths);
the European ones keep English's layout, Japanese has shorter names (species 5,
moves 7, items 9 characters + the terminator) and 40-byte item records. Each address is the word English's code loads the
table from, read at the same place in the language's code (the literal-pool
match the hacks' tables were found with). `en` decodes English's tables with
the same code and compares them, capitalised, with the app's English tables.
"""
import hashlib
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "..", "shared", "src", "commonMain", "kotlin", "com", "pokedaisy", "app", "companion", "data")

NUM_SPECIES = 412      # SPECIES_NONE .. SPECIES_CHIMECHO (+ the unused slots in between)
MOVES_COUNT = 355
ITEMS_COUNT = 377
NUM_NATURES = 25
MAPSEC_COUNT = 213     # MAPSEC_NONE

# gSpeciesNames, gMoveNames, gItems, gNatureNamePointers, gRegionMapEntries, gFontSmallLatinGlyphWidths.
LANGS = {
    "en": dict(sha1="f3ae088181bf583e55daf962a92bb46f4f1d07b7", suffix=None,
               species=0x083185C8, moves=0x0831977C, items=0x085839A0, natures=0x0861CB50,
               mapsecs=0x085A147C, small_widths=0x0863BCE4),
    "es": dict(sha1="fe1558a3dcb0360ab558969e09b690888b846dd9", suffix="Es", label="Edición Esmeralda (Spain)",
               species=0x0831E82C, moves=0x0831F9E0, items=0x0858639C, natures=0x0861F46C,
               mapsecs=0x085A41E4, small_widths=0x0863E218),
    "de": dict(sha1="61c2eb2b380b1a75f0c94b767a2d4c26cd7ce4e3", suffix="De", label="Smaragd-Edition (Germany)",
               species=0x0832CF38, moves=0x0832E0EC, items=0x085946DC, natures=0x0862E0A4,
               mapsecs=0x085B24B4, small_widths=0x0864CD88),
    "fr": dict(sha1="ca666651374d89ca439007bed54d839eb7bd14d0", suffix="Fr", label="Version Émeraude (France)",
               species=0x083200F8, moves=0x083212AC, items=0x08587D6C, natures=0x08620F54,
               mapsecs=0x085A5ADC, small_widths=0x0863FC18),
    "it": dict(sha1="1692db322400c3141c5de2db38469913ceb1f4d4", suffix="It", label="Versione Smeraldo (Italy)",
               species=0x08317F8C, moves=0x08319140, items=0x0858000C, natures=0x08619674,
               mapsecs=0x0859DEE8, small_widths=0x08638338),
    # Japanese: its own code, so gItems / gNatureNamePointers were found by content
    # (MASTER BALL's record with itemId 1 at +10; a table of 25 pointers to its natures).
    "ja": dict(sha1="d7cf8f156ba9c455d164e1ea780a6bf1945465c2", suffix="Ja", label="Emerald (Japan)",
               species=0x082EA31C, moves=0x082EACC4, items=0x0855CEE8, natures=0x085ECE24,
               mapsecs=0x0857CD6C, small_widths=None,
               species_len=6, move_len=8, item_stride=40, item_name_len=10),
    "kaizo": dict(sha1="a7c4e34fafb53f2d5283eb6d43dad285c0dd40a8", suffix="Kaizo", label="Emerald Kaizo v2.0",
                  chars="en", battle_moves=0x0831C898,
                  species=0x083185C8, moves=0x0831977C, items=0x085839A0, natures=0x0861CB50,
                  mapsecs=0x085A147C, small_widths=0x0863BCE4),
    # Emerald Legacy v1.1.4: its own build's symbols (gSpeciesNames, gMoveNames, gItems, gNatureNamePointers,
    # gRegionMapEntries, gFontSmallLatinGlyphWidths, gBattleMoves).
    "legacy": dict(sha1="cbd71af65ee3dcd53280a6a12014bfb11e8f626a", suffix="Legacy", label="Emerald Legacy v1.1.4",
                   chars="en", battle_moves=0x08344EA0, move_stats=True, species_count=415,
                   species=0x08340B58, moves=0x08341D2D, items=0x085B6210, natures=0x08650E30,
                   mapsecs=0x085D3D30, small_widths=0x0866FFDC),
}
# English's record layout, where a language doesn't say otherwise.
LAYOUT = dict(species_len=11, move_len=13, item_stride=44, item_name_len=14)

# pokeemerald's charmap.txt, Western half (Gen3Text in Pokedex.kt decodes the same).
CHARS = {0x00: " "}
CHARS.update({0x01 + i: c for i, c in enumerate("ÀÁÂÇÈÉÊËÌ ÎÏÒÓÔŒÙÚÛÑßàá çèéêëì îïòóôœùúûñºª") if c != " "})
CHARS.update({0x2D: "&", 0x2E: "+", 0x35: "=", 0x36: ";", 0x51: "¿", 0x52: "¡", 0x53: "PK", 0x54: "MN",
              0x5A: "Í", 0x5B: "%", 0x5C: "(", 0x5D: ")", 0x68: "â", 0x6F: "í", 0x85: "<", 0x86: ">",
              # POKEBLOCK's five tiles, LV, the small raised letters (1er, 2e, 1re), arrows, a spacer.
              0x55: "PO", 0x56: "Ké", 0x34: "Lv",
              0x2C: "er", 0x84: "e", 0xA0: "re", 0x79: "↑", 0x7A: "↓", 0x7B: "←", 0x7C: "→", 0x77: ""})
CHARS.update({0xA1 + i: str(i) for i in range(10)})
CHARS.update({0xAB: "!", 0xAC: "?", 0xAD: ".", 0xAE: "-", 0xAF: "·", 0xB0: "…", 0xB1: "“", 0xB2: "”",
              0xB3: "‘", 0xB4: "'", 0xB5: "♂", 0xB6: "♀", 0xB7: "¥", 0xB8: ",", 0xB9: "×", 0xBA: "/", 0xF0: ":"})
CHARS.update({0xBB + i: chr(65 + i) for i in range(26)})
CHARS.update({0xD5 + i: chr(97 + i) for i in range(26)})
CHARS.update({0xF1 + i: c for i, c in enumerate("ÄÖÜäöü")})
# The Pokéblock word is drawn with glyphs of its own, which each language redrew for its
# word (read off each ROM's FONT_NORMAL): POKéBLOCK, POKéCUBO, POKéRIEGEL, POKéBLOC, and
# Italian's POKéMELLE / POKéMELLA at 0x5E-0x63 (its 0x57-0x59 are blank).
LANG_CHARS = {
    "en": {0x57: "BL", 0x58: "OC", 0x59: "K"},
    "es": {0x57: "CU", 0x58: "BO", 0x59: ""},
    "de": {0x57: "RIE", 0x58: "GE", 0x59: "L"},
    "fr": {0x57: "BL", 0x58: "O", 0x59: "C"},
    "it": {0x5E: "PO", 0x5F: "Ké", 0x60: "ME", 0x61: "LL", 0x62: "A", 0x63: "E"},
}

LINE_BREAKS = {0xFA, 0xFB, 0xFE}


# Japanese: hiragana and katakana where the Western letters are (pokeemerald's charmap.txt,
# Japanese half), full-width punctuation; digits, A-Z and the rest as in the West.
JAPANESE = dict(CHARS)
JAPANESE.update({i: c for i, c in enumerate(
    "　あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
    "ぁぃぅぇぉゃゅょがぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽっ"
    "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"
    "ァィゥェォャュョガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポッ")})
JAPANESE.update({0xAB: "！", 0xAC: "？", 0xAD: "。", 0xAE: "ー", 0xB0: "⋯"})
LANG_CHARS["ja"] = {}


class Rom:
    def __init__(self, data, lang):
        self.d = data
        self.chars = {**(JAPANESE if lang == "ja" else CHARS), **LANG_CHARS[LANGS[lang].get("chars", lang)]}
        # A line break inside Japanese text reads as the full-width space it spaces phrases with.
        self.line_break = "　" if lang == "ja" else " "

    def u32(self, a):
        return struct.unpack_from("<I", self.d, a - 0x08000000)[0]

    def text(self, a, maxlen):
        out = []
        raw = self.d[a - 0x08000000:a - 0x08000000 + maxlen]
        skip = 0
        for b in raw:
            if skip:
                skip -= 1
                continue
            if b == 0xFF:
                break
            if b == 0xFD:  # a placeholder ({PLAYER}, ...) and its id: the text around it stays
                skip = 1
                continue
            if b in LINE_BREAKS:
                out.append(self.line_break)
                continue
            if b not in self.chars:
                raise ValueError(f"byte {b:#04x} at {a:#x}")
            out.append(self.chars[b])
        s = "".join(out)
        return s.strip() if self.line_break != " " else " ".join(s.split())


def tables(rom, v):
    lay = {**LAYOUT, **v}
    sl, ml = lay["species_len"], lay["move_len"]
    species = {i: rom.text(v["species"] + sl * i, sl) for i in range(1, v.get("species_count", NUM_SPECIES))}
    species = {i: n for i, n in species.items() if n and n not in ("?", "？")}
    moves = {i: rom.text(v["moves"] + ml * i, ml) for i in range(1, MOVES_COUNT)}
    items = {}
    for i in range(1, ITEMS_COUNT):
        a = v["items"] + lay["item_stride"] * i
        n = rom.text(a, lay["item_name_len"])
        if n and set(n) not in ({"?"}, {"？"}):  # "????????" = unused; German has a "?-ÖFFNER"
            items[i] = n
    natures = [rom.text(rom.u32(v["natures"] + 4 * i), 20) for i in range(NUM_NATURES)]
    mapsecs = {i: rom.text(rom.u32(v["mapsecs"] + 8 * i + 4), 30) for i in range(MAPSEC_COUNT)}
    widths = list(rom.d[v["small_widths"] - 0x08000000:][:512]) if v["small_widths"] else []
    return species, moves, items, natures, mapsecs, widths


# Legacy's two new move effects, as the move advice's tiers know them: EFFECT_BLIZZARD is a freeze hit (its battle
# script falls into BattleScript_EffectFreezeHit), EFFECT_LEAF_BLADE a hit with a raised critical-hit stage.
EFFECT_ALIASES = {"BLIZZARD": "FREEZE_HIT", "LEAF_BLADE": "HIGH_CRITICAL"}


def move_effect_names():
    """EFFECT_* ids -> MoveStats' effect names (no prefix), from Legacy's include/constants/battle_move_effects.h."""
    path = os.path.join(os.environ["DECOMPS"], "pokelegacy", "include", "constants", "battle_move_effects.h")
    out = {}
    for m in re.finditer(r"^#define\s+EFFECT_(\w+)\s+(\d+)\b", open(path).read(), re.M):
        out[int(m.group(2))] = EFFECT_ALIASES.get(m.group(1), m.group(1))
    return out


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def kmap(name, m):
    return (f"internal val {name}: Map<Int, String> by lazy {{\n    mapOf(\n" +
            "".join(f"        {i} to {kstr(n)},\n" for i, n in sorted(m.items())) + "    )\n}\n")


def check_english(species, moves, items, natures):
    """English through this decoder vs the app's own (title-cased) English tables."""
    def kt(name):
        src = open(os.path.join(OUT_DIR, name), encoding="utf-8").read()
        return {int(a): b for a, b in re.findall(r'^\s+(\d+) to "((?:[^"\\]|\\.)*)"', src, re.M)}
    def caps(s):  # gameCase(): capitals, but POKé's é stays small
        return "".join(c if c == "é" else c.upper() for c in s)
    app_species = kt("SpeciesNames.kt")
    bad = [i for i, n in species.items() if i in app_species and caps(app_species[i]) != n]
    print("species", len(species), "differ from the app's:", [(i, species[i], app_species[i]) for i in bad[:8]])
    app_items = kt("ItemNames.kt")
    bad = [i for i, n in items.items() if i in app_items and caps(app_items[i]) != n]
    print("items", len(items), "differ from the app's:", [(i, items[i], app_items[i]) for i in bad[:8]])
    print("moves", len(moves), [moves[i] for i in (1, 33, 354)], "natures", natures[:3], natures[-1])


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in LANGS:
        sys.exit(f"usage: {sys.argv[0]} <{'|'.join(LANGS)}> <rom.gba>")
    lang, v = sys.argv[1], LANGS[sys.argv[1]]
    data = open(sys.argv[2], "rb").read()
    if hashlib.sha1(data).hexdigest() != v["sha1"]:
        sys.exit(f"{sys.argv[2]} isn't Emerald {lang} (sha1 {v['sha1']})")
    species, moves, items, natures, mapsecs, widths = tables(Rom(data, lang), v)
    if lang == "en":
        check_english(species, moves, items, natures)
        return
    sfx = v["suffix"]
    kt = (f"package com.pokedaisy.app.companion.data\n\n"
          f"// Generated by scripts/gen_emerald_lang_tables.py from Pokémon {v['label']}\n"
          f"// (sha1 {v['sha1'][:8]}...) - do not hand-edit. The game's own names, by English's ids.\n\n" +
          kmap(f"speciesNamesEmerald{sfx}", species) + "\n" +
          kmap(f"moveNamesEmerald{sfx}", moves) + "\n" +
          kmap(f"itemNamesEmerald{sfx}", items) + "\n" +
          f"internal val natureNamesEmerald{sfx}: List<String> = listOf(\n" +
          "".join(f"    {kstr(n)},\n" for n in natures) + ")\n\n" +
          kmap(f"mapSecNamesEmerald{sfx}", mapsecs) + "\n" +
          f"/** gFontSmallLatinGlyphWidths: the party slot's FONT_SMALL advances by char code. */\n"
          f"internal val smallFontWidthsEmerald{sfx}: IntArray by lazy {{ intArrayOf({', '.join(map(str, widths))}) }}\n")
    if v.get("battle_moves"):   # a hack's own moves: their types and powers too (gBattleMoves: power +1, type +2)
        b = v["battle_moves"] - 0x08000000
        kt += ("\n/** gBattleMoves' types and powers, under the game's own names. */\n"
               f"internal val moveDataEmerald{sfx}: Map<Int, MoveInfo> by lazy {{\n    mapOf(\n" +
               "".join(f"        {i} to MoveInfo({kstr(n)}, {data[b + 12 * i + 2]}, {data[b + 12 * i + 1]}),\n"
                       for i, n in sorted(moves.items())) + "    )\n}\n")
    if v.get("move_stats"):   # the move advice's data: gBattleMoves' effect, accuracy, PP, effect chance, priority
        b = v["battle_moves"] - 0x08000000
        effects = move_effect_names()
        kt += ("\n/** gBattleMoves' effect, accuracy, PP, effect chance and priority (MoveStats), by move id. */\n"
               f"internal val moveStatsEmerald{sfx}: List<MoveStats> by lazy {{\n    listOf(\n" +
               "".join(f"        MoveStats({kstr(effects[data[b + 12 * i]])}, {data[b + 12 * i + 3]}, {data[b + 12 * i + 4]}, "
                       f"{data[b + 12 * i + 5]}, {struct.unpack_from('<b', data, b + 12 * i + 7)[0]}),\n"
                       for i in range(MOVES_COUNT)) + "    )\n}\n")
    path = os.path.join(OUT_DIR, f"EmeraldText{sfx}Gen.kt")
    with open(path, "w", encoding="utf-8") as f:
        f.write(kt)
    print(f"wrote {os.path.relpath(path)}: {len(species)} species, {len(moves)} moves, {len(items)} items, "
          f"{len(mapsecs)} map sections; {species[1]}, {moves[1]}, {items[1]}, {mapsecs[0]}")


if __name__ == "__main__":
    main()
