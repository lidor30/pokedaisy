#!/usr/bin/env python3
"""Generates Pokémon Quetzal's display tables from the ROM the user owns
(closed hack of Emerald by TenmaRH, no source; nothing is downloaded).

    scripts/gen_quetzal_tables.py <rom.gba> [out_dir]
    scripts/gen_quetzal_tables.py <rom.gba> --languages [<spanish rom.gba>]
    scripts/gen_quetzal_tables.py <rom.gba> --v084 <0.8.4 rom.gba>...
    scripts/gen_quetzal_tables.py <rom.gba> <spanish rom.gba> --v1 <Alpha 9 v1 rom.gba>...

Writes {SpeciesNames,SpeciesTypes,GenderRatios,ItemNames,ItemPockets,MoveData,
TypeChart,MapSecData}Quetzal.kt (default: the app's data package).

--languages writes QuetzalNamesGen.kt instead: the game's other name languages.
Quetzal carries English, Spanish, Latin American Spanish and Brazilian Portuguese
names in every build and picks them per kind with its own options (START >
OPCIONES > TEXTO > IDIOMA: POKéMON, MOVIMIENTOS, OBJETOS, LUGARES, ...), kept in
SaveBlock2 (NativeReader's readQuetzalNames). Only the names that differ from
English are written, as overlays. The Spanish release (Alpha 9 v0, sha1
fe346b5b...) is the same engine with Spanish dialogue, descriptions and dex text
and the same name tables (checked when its ROM is given); only its LUGARES default
differs (Spanish, not English). Portuguese isn't written: its names use glyphs
past the Western charmap (ã, õ).
--v084 checks Quetzal Alpha 0.8.4's builds (English / Spanish and their Moemon
Hotfix builds) against Alpha 9 and writes QuetzalV084Gen.kt: the few moves whose
power or type 0.8.4 has its own way. It shares every other table by id (its
species / items are Alpha 9's first 1520 / 879).
--v1 checks Quetzal Alpha 9 v1 (English / Spanish: v0 rebuilt, every table moved) against v0 and writes
QuetzalV1Gen.kt: the Latin American names of Johto's key items, which v1 corrected (v0's were Portuguese).
Item descriptions aren't written: the app reads them from the player's ROM
(RomItemText, NativeConfig.itemDescs).

Quetzal English Alpha 9 v0 (BPEE, 32 MB, header title "PKM QUETZAL") keeps
vanilla pokeemerald's table *shapes* (names in their own arrays, not inline in
gSpeciesInfo / gItemsInfo like pokeemerald-expansion), with grown structs and
expansion-sized contents. Every address below is also published in the ROM's
own GF header at 0x08000100 (pokemon quetzal version: monSpeciesNames,
moveNames, speciesInfo, items, moves - the pointers are current, its save
offsets are not):
  - gSpeciesNames 0x08504670: 13-byte names (12 chars + EOS), title case,
    1529 entries (0..1528); National Dex ids through 898 (Calyrex), then forms
    (899 Venusaur = Mega Venusaur, ...), Gen 9 from 1244 (Lechonk). A second,
    Spanish-named copy at 0x08509415 differs in 21 names (paradoxes, Type: Null).
  - gSpeciesInfo 0x0853E560: 0x24-byte entries - base stats +0..+5, types +6/+7,
    catchRate +8, expYield u16 +0xA, evYield +0xC, items +0xE/+0x10,
    genderRatio +0x12, eggCycles +0x13, friendship +0x14, growthRate +0x15,
    eggGroups +0x16/+0x17, abilities u16 +0x18/+0x1A/+0x1C (hidden).
    Bulbasaur 45/49/49/45/65/65 Grass/Poison 31, Pikachu Electric 127.
  - Type ids are vanilla's (0-17, 9 = "???") plus Fairy 18 and Stellar 19
    (gTypeNames 0x0851F1F4, 10-byte names). gTypeEffectiveness 0x0852DA8C is
    [20][20] u32 uq4.12 (0x1000 = 1x), Gen 6+ rules.
  - gMoveNames 0x0850E1BA: 17-byte names ("Pound" 1, "Tackle" 33).
    gBattleMoves 0x08530690: 0x18-byte entries {u16 effect; u8 power; u8 type;
    u8 accuracy; u8 pp; ...} (Tackle 40 Normal 35 PP, Ember 40 Fire).
  - gItemNames 0x091E66EC: 20-byte names, 890 ids (0..889, expansion's order:
    1 Poké Ball, 28 Potion). gItems 0x091E0594: 0x1C-byte {u16 itemId; u32
    price +4; u32 holdEffect/param +8; description* +0xC; u8 importance +0x10;
    u8 +0x11; u8 pocket +0x12; u8 type +0x13; fieldUseFunc +0x14; ...}.
  - gRegionMapEntries 0x09229558: 8-byte {names*, x, y, w, h} by mapsec id,
    0xD5 of them (u8 ids, MAPSEC_NONE 0xD5); names* points at 4 string
    pointers {English, Spanish, Spanish, Portuguese}. Emerald's mapsec ids
    (0 Littleroot .. 0x57, Kanto 0x58.. e.g. 0x65 ROUTE 1, the save's map,
    shown by the game's Town Map), caps -> title case. The game's region map is
    FireRed's (Kanto / ISLES): the app places these on it with FireRed's rects
    (ActiveTables' mapSecDataQuetzalOnMap), so here they're names only (region -1).
"""
import hashlib
import os
import struct
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin",
                       "com", "pokedaisy", "app", "companion", "data")

SHA1 = "d0658315da1e8827f66f15c3d3a000fe747e163e"
LABEL = "Pokemon Quetzal English Alpha 9 v0"

SPECIES_NAMES = 0x08504670   # 13-byte names
SPECIES_INFO = 0x0853E560    # 0x24-byte entries
SPECIES_COUNT = 1529
MOVE_NAMES = 0x0850E1BA      # 17-byte names
BATTLE_MOVES = 0x08530690    # 0x18-byte entries: power +2, type +3
ITEM_NAMES = 0x091E66EC      # 20-byte names
ITEMS = 0x091E0594           # 0x1C-byte entries: description ptr +0xC
ITEM_COUNT = 890
TYPE_CHART = 0x0852DA8C      # [20][20] u32 uq4.12
TYPE_COUNT = 20
REGION_MAP_ENTRIES = 0x09229558  # each {x, y, w, h, names**}'s names**: the table starts 4 bytes before
JOHTO_MAP_ENTRIES = 0x0922A7E8   # Johto's maps' own: {x, y, w, h, names**}
JOHTO_MAPSEC_BASE = 0x100        # where the app keeps them (NATIVE_QUETZAL.altMapSecGroups)
MAPSEC_COUNT = 0xD5
FIRST_KANTO_MAPSEC = 0x58        # below it the main table's sections are Hoenn's
HOENN_IMAGE, JOHTO_IMAGE = 4, 5  # regionMapImagesQuetzalOnMap
MAP_SCREEN_DX, MAP_SCREEN_DY = 1, 2   # the boxes' tile (0, 0) on the map's screen

TYPE_NAMES = {0: "Normal", 1: "Fighting", 2: "Flying", 3: "Poison", 4: "Ground", 5: "Rock", 6: "Bug", 7: "Ghost",
              8: "Steel", 10: "Fire", 11: "Water", 12: "Grass", 13: "Electric", 14: "Psychic", 15: "Ice",
              16: "Dragon", 17: "Dark", 18: "Fairy"}  # 9 "???" and 19 Stellar: no species / move uses them

CHARS = {0x00: " ", 0x1B: "é", 0xAB: "!", 0xAC: "?", 0xAD: ".", 0xAE: "-", 0xB0: "…", 0xB1: "“", 0xB2: "”",
         0xB3: "‘", 0xB4: "'", 0xB5: "♂", 0xB6: "♀", 0xB8: ",", 0xBA: "/", 0xF0: ":", 0x2D: "&", 0x5C: "(",
         0x5D: ")", 0x5B: "%", 0x35: "=", 0x36: ";", 0x53: "PK", 0x54: "MN"}
# Line / paragraph breaks: descriptions are wrapped for the game's box, the app rewraps.
CHARS.update({0xFA: " ", 0xFB: " ", 0xFE: " "})
CHARS.update({0xA1 + i: str(i) for i in range(10)})
CHARS.update({0xBB + i: chr(65 + i) for i in range(26)})
CHARS.update({0xD5 + i: chr(97 + i) for i in range(26)})

# Spot checks against what the game shows (the user's save: Charmander with
# Tackle / Growl / Ember, Tackle at 56 PP (35 + 3 PP Ups); the bag's items; the
# Town Map's ROUTE 1).
CHECKS = dict(species={1: "Bulbasaur", 4: "Charmander", 16: "Pidgey", 25: "Pikachu", 403: "Shinx", 1244: "Lechonk"},
              types={1: (12, 3), 4: (10, 10), 25: (13, 13), 35: (18, 18), 403: (13, 13)},
              gender={1: 31, 25: 127, 35: 191},
              moves={1: ("Pound", 0, 40), 33: ("Tackle", 0, 40), 45: ("Growl", 0, 0), 52: ("Ember", 10, 40),
                     57: ("Surf", 11, 90), 89: ("Earthquake", 4, 100)},
              items={1: "Poké Ball", 5: "Premier Ball", 28: "Potion", 44: "Paralyze Heal", 45: "Burn Heal",
                     114: "Repel", 120: "Escape Rope", 129: "Poké Doll", 461: "Exp. Share", 703: "Mega Ring",
                     704: "Z-Power Ring", 713: "Town Map"},
              mapsecs={0x00: "Littleroot Town", 0x10: "Route 101", 0x58: "Pallet Town", 0x65: "Route 1"})


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


def u32(rom, a):
    return struct.unpack_from("<I", rom, a - 0x08000000)[0]


def is_ptr(rom, v):
    return 0x08000000 <= v < 0x08000000 + len(rom)


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def title(s):
    words = []
    for w in s.split(" "):
        words.append(w if w.count(".") >= 2 else w[:1] + w[1:].lower())
    return " ".join(words)


def header(what):
    return (f"package com.pokedaisy.app.companion.data\n\n"
            f"// GENERATED by scripts/gen_quetzal_tables.py from the {LABEL} ROM\n"
            f"// (sha1 {SHA1[:8]}...) - do not hand-edit. {what}\n")


def write(name, body):
    path = os.path.join(OUT_DIR, name)
    with open(path, "w") as f:
        f.write(body)
    print("wrote", os.path.relpath(path))


# The per-language name tables (English ROM), from the code that picks them by option:
# GetSpeciesName (0x080A3464: SaveBlock2+0x2E0 bits 5-7, 1 or 2 -> the Spanish table),
# GetMoveName (0x080A34B8: +0x2E1 bits 0-2, 1 / 2 / 3), ItemId_GetName (0x0813EE74:
# +0x2E2 bits 3-5, 1 / 2 / 3); map sections: gRegionMapEntries' names** [EN, ES, LA, PT].
LANG_TABLES = dict(
    species={"Es": 0x08509415},
    moves={"Es": 0x08511FE2, "La": 0x08515E0A},
    items={"Es": 0x091EAC74, "La": 0x091EF1FC},
)
# The Spanish release's copies of the same tables (its own addresses).
ES_SHA1 = "fe346b5b0eb022e3a103f81e7dcefba6f1dc542f"
ES_TABLES = dict(species={"En": 0x08510080, "Es": 0x08514E25}, moves={"En": 0x08519BCA, "Es": 0x0851D9F2, "La": 0x0852181A},
                 items={"En": 0x091E96F4, "Es": 0x091EDC7C, "La": 0x091F2204})
ES_REGION_MAP_ENTRIES = 0x0922C7D4
ES_JOHTO_MAP_ENTRIES = 0x0922DA64


def languages(rom, es_rom):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from gen_emerald_lang_tables import CHARS as WESTERN

    def ltext(r, a, maxlen):
        out = []
        for b in r[a - 0x08000000:a - 0x08000000 + maxlen]:
            if b == 0xFF:
                break
            if b in (0xFA, 0xFB, 0xFE):
                out.append(" ")
                continue
            if b not in WESTERN:
                return None
            out.append(WESTERN[b])
        s = " ".join("".join(out).split())
        return s if s and set(s) != {"?"} and s != "-" else None

    en_species = {i: text(rom, SPECIES_NAMES + 13 * i, 13) for i in range(1, SPECIES_COUNT)}
    en_moves = {i: text(rom, MOVE_NAMES + 17 * i, 17) for i in range(1, 1000)}
    en_items = {i: text(rom, ITEM_NAMES + 20 * i, 20) for i in range(1, ITEM_COUNT)}
    out = {}
    for kind, stride, count, en in (("species", 13, SPECIES_COUNT, en_species), ("moves", 17, 1000, en_moves),
                                    ("items", 20, ITEM_COUNT, en_items)):
        for lang, base in LANG_TABLES[kind].items():
            names = {}
            for i in range(1, count):
                if not en.get(i):
                    continue
                n = ltext(rom, base + stride * i, stride)
                if n and n != en[i]:
                    names[i] = n
            out[(kind, lang)] = names
            if es_rom is not None:
                e = ES_TABLES[kind][lang]
                same = all(rom[base - 0x08000000 + stride * i:base - 0x08000000 + stride * (i + 1)] ==
                           es_rom[e - 0x08000000 + stride * i:e - 0x08000000 + stride * (i + 1)] for i in range(1, count))
                assert same, f"the Spanish release's {kind} ({lang}) differ"
    assert out[("species", "Es")][772] == "Código Cero" and len(out[("species", "Es")]) == 21
    assert out[("moves", "Es")][33] == "Placaje" and out[("items", "Es")][28] == "Poción"
    assert out[("items", "La")][1] == "Pokébola" and out[("items", "Es")][120] == "Cuerda Huida"

    def mapsecs(r, kanto, johto, idx):
        m = {}
        for base, first, names_at in ((kanto, 0, 0), (johto, JOHTO_MAPSEC_BASE, 4)):
            for i in range(MAPSEC_COUNT):
                p = u32(r, base + 8 * i + names_at)
                if not is_ptr(r, p) or not is_ptr(r, u32(r, p + 4 * idx)):
                    continue
                s = ltext(r, u32(r, p + 4 * idx), 30)
                if s:
                    m[first + i] = title(s)
        return m

    en_secs = mapsecs(rom, REGION_MAP_ENTRIES, JOHTO_MAP_ENTRIES, 0)
    for idx, lang in ((1, "Es"), (2, "La")):
        secs = mapsecs(rom, REGION_MAP_ENTRIES, JOHTO_MAP_ENTRIES, idx)
        if es_rom is not None:
            assert secs == mapsecs(es_rom, ES_REGION_MAP_ENTRIES, ES_JOHTO_MAP_ENTRIES, idx), "map names differ"
        out[("mapsecs", lang)] = {i: n for i, n in secs.items() if n != en_secs.get(i)}
    assert out[("mapsecs", "Es")][0x58] == "Pueblo Paleta" and out[("mapsecs", "Es")][JOHTO_MAPSEC_BASE + 2] == "Ciudad Malva"

    def kmap(name, m):
        return (f"internal val {name}: Map<Int, String> by lazy {{\n    mapOf(\n" +
                "".join(f"        {i} to {kstr(n)},\n" for i, n in sorted(m.items())) + "    )\n}\n")

    body = header("Quetzal's Spanish and Latin American Spanish names, where\n"
                  "// they differ from English (overlays on the *Quetzal tables), by the same ids. The game picks\n"
                  "// them per kind from its IDIOMA options (NativeReader's readQuetzalNames).\n")
    for (kind, lang), m in out.items():
        name = {"species": "speciesNames", "moves": "moveNames", "items": "itemNames", "mapsecs": "mapSecNames"}[kind]
        body += "\n" + kmap(f"{name}Quetzal{lang}", m)
    write("QuetzalNamesGen.kt", body)
    print(", ".join(f"{k}/{l} {len(m)}" for (k, l), m in out.items()))


# Quetzal Alpha 0.8.4, the release before Alpha 9: English / Spanish, and their Moemon Hotfix builds (the same
# code and tables, other Pokémon pictures). Its tables are where its GF header (and these) say.
V084 = {
    "6806c7146aff457a4be85ef2030e9ddcdb17b665": dict(label="English Alpha 0.8.4", lang="en", item_names=0x08F9B248,
                                                     region_map_entries=0x08FD0B18),
    "b6542c7dbb40cf6ea96714a7a0e30cb468102861": dict(label="Spanish Alpha 0.8.4", lang="es", item_names=0x08F9A968,
                                                     region_map_entries=0x08FD043C),
    "f1035f615c13c46394a18fa99181c24c3cd15e12": dict(label="Moemon English Alpha 0.8.4 Hotfix", lang="en",
                                                     item_names=0x08F9B22C, region_map_entries=0x08FD0AFC),
    "ae5d88bb823a8f9d0d07e8784da2376ff7c1ae43": dict(label="Moemon Spanish Alpha 0.8.4 Hotfix", lang="es",
                                                     item_names=0x08F9A94C, region_map_entries=0x08FD0420),
}
V084_SPECIES = 1520  # 0..1519; 1520 is its egg (Alpha 9 added Browt / Pombon / Gecqua's lines there)
V084_ITEMS = 879     # 0..878; Alpha 9 added Johto's key items from 879
V084_MOVES = 849     # the same 1..848 as Alpha 9


def v084(rom, paths):
    """Checks Quetzal 0.8.4's tables against Alpha 9's (rom) and writes QuetzalV084Gen.kt: the moves whose
    power or type it has its own way. Everything else it shares by id: species names (and the Spanish table),
    types, gender ratios, item names (all three languages), pockets, the type chart, move names (all three) and
    map section names (its gRegionMapEntries are vanilla Emerald's {x, y, w, h, name*}, one language per build,
    Hoenn's rects Emerald's own)."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from gen_emerald_lang_tables import CHARS as WESTERN
    em = open(os.path.join(OUT_DIR, "MapSecDataEmerald.kt"), encoding="utf-8").read()
    import re
    em_rects = {int(m.group(1)): tuple(int(m.group(k)) for k in range(2, 7))
                for m in re.finditer(r"(\d+) to MapSecInfo\(\"[^\"]*\", (-?\d+), (\d+), (\d+), (\d+), (\d+)\)", em)}
    es_names = re.search(r"mapSecNamesQuetzalEs.*?mapOf\((.*?)\n    \)", open(os.path.join(OUT_DIR, "QuetzalNamesGen.kt"),
                                                                       encoding="utf-8").read(), re.S).group(1)
    es_names = {int(m.group(1)): m.group(2) for m in re.finditer(r"(\d+) to \"([^\"]*)\"", es_names)}

    def rtext(r, a, n=30):
        out = []
        for b in r[a - 0x08000000:a - 0x08000000 + n]:
            if b == 0xFF:
                break
            out.append(" " if b in (0xFA, 0xFB, 0xFE) else WESTERN.get(b, "?"))
        return " ".join("".join(out).split())

    def same(r, a, b, stride, count, src=rom):
        return [i for i in range(1, count) if src[a - 0x08000000 + stride * i:a - 0x08000000 + stride * (i + 1)] !=
                r[b - 0x08000000 + stride * i:b - 0x08000000 + stride * (i + 1)]]

    mapsec_names = {}
    for i in range(MAPSEC_COUNT):
        p = u32(rom, REGION_MAP_ENTRIES + 8 * i)
        if is_ptr(rom, p) and is_ptr(rom, u32(rom, p)):
            mapsec_names[i] = title(text(rom, u32(rom, p), 30) or "")
    changes = None
    for path in paths:
        r = open(path, "rb").read()
        meta = V084.get(hashlib.sha1(r).hexdigest())
        if meta is None:
            sys.exit(f"{path} isn't a Quetzal 0.8.4 build this knows")
        hdr = lambda o: u32(r, 0x08000100 + o)
        species, move_names, info, items, moves = hdr(0x44), hdr(0x48), hdr(0xBC), hdr(0xC8), hdr(0xCC)
        # English, then the other languages' tables right after (Spanish species; Spanish / Latin American moves, items).
        assert not same(r, SPECIES_NAMES, species, 13, V084_SPECIES), "species names differ"
        assert not same(r, LANG_TABLES["species"]["Es"], species + 13 * V084_SPECIES, 13, V084_SPECIES), "Spanish species differ"
        for k, lang in enumerate(("En", "Es", "La")):
            src = MOVE_NAMES if lang == "En" else LANG_TABLES["moves"][lang]
            assert not same(r, src, move_names + 17 * 936 * k, 17, V084_MOVES), f"move names ({lang}) differ"
            src = ITEM_NAMES if lang == "En" else LANG_TABLES["items"][lang]
            assert not same(r, src, meta["item_names"] + 20 * V084_ITEMS * k, 20, V084_ITEMS), f"item names ({lang}) differ"
        for i in range(1, V084_SPECIES):
            a, b = SPECIES_INFO + 0x24 * i, info + 0x24 * i
            assert (u8(rom, a + 6), u8(rom, a + 7), u8(rom, a + 0x12)) == (u8(r, b + 6), u8(r, b + 7), u8(r, b + 0x12)), i
        for i in range(1, V084_ITEMS):
            assert u8(rom, ITEMS + 0x1C * i + 0x12) == u8(r, items + 0x1C * i + 0x12), f"item {i}'s pocket"
        chart = rom[TYPE_CHART - 0x08000000:TYPE_CHART - 0x08000000 + 4 * TYPE_COUNT * TYPE_COUNT]
        assert r.count(chart) == 1, "type chart differs"
        mine = {}
        for i in range(1, V084_MOVES):
            a, b = BATTLE_MOVES + 0x18 * i, moves + 0x18 * i
            if (u8(rom, a + 2), u8(rom, a + 3)) != (u8(r, b + 2), u8(r, b + 3)):
                mine[i] = (text(rom, MOVE_NAMES + 17 * i, 17), u8(r, b + 3), u8(r, b + 2))
        assert changes is None or changes == mine, "the builds' moves differ"
        changes = mine
        e = meta["region_map_entries"]
        for i in range(MAPSEC_COUNT):
            x, y, w, h = r[e + 8 * i - 0x08000000:e + 8 * i + 4 - 0x08000000]
            name = title(rtext(r, u32(r, e + 8 * i + 4)))
            want = mapsec_names[i] if meta["lang"] == "en" else es_names.get(i, mapsec_names.get(i))
            if i not in (0x42, 0x57):  # {AQUA} HIDEOUT's glyph; Alpha 9's empty "Dynamic"
                assert name == want, (hex(i), name, want)
            rect = em_rects.get(i)
            if rect and rect[0] >= 0:
                assert (x + 1, y + 2, w, h) == rect[1:], (hex(i), (x, y, w, h), rect)
        print(f"{meta['label']}: shares Alpha 9's tables; {len(mine)} moves of its own")
    body = header("Quetzal Alpha 0.8.4's moves whose power or type differ from\n"
                  "// Alpha 9's (laid over moveDataQuetzal by ActiveTables' quetzalV084). Everything else it shares\n"
                  "// by id - see gen_quetzal_tables.py's v084().\n")
    body += ("internal val moveDataQuetzalV084Changes: Map<Int, MoveInfo> = mapOf(\n" +
             "".join(f"    {i} to MoveInfo({kstr(n)}, {t}, {p}),\n" for i, (n, t, p) in sorted(changes.items())) + ")\n")
    write("QuetzalV084Gen.kt", body)


# Quetzal Alpha 9 v1 (English / Spanish): Alpha 9 v0 rebuilt, every table moved (its GF header and these say where).
V1 = {
    "c108cec750a35775084c0806cc704d8abd1994e7": dict(label="English Alpha 9 v1", base=SHA1, item_names=0x091EFDC4,
                                                     region_map_entries=0x09233AE0, johto_map_entries=0x09234D70),
    "ff008b70b6f2b330da9ffa8a405fc951a9e54147": dict(label="Spanish Alpha 9 v1", base=ES_SHA1, item_names=0x091F2E08,
                                                     region_map_entries=0x09236DA4, johto_map_entries=0x09238034),
}


def v1(rom, es_rom, paths):
    """Checks Quetzal Alpha 9 v1's tables against v0's (rom: English v0, es_rom: Spanish v0) and writes QuetzalV1Gen.kt:
    the names v1 has its own way. Everything else is v0's by id, checked here: species (English, Spanish), move and item
    names (English, Spanish, Latin American), gSpeciesInfo's types / gender, item pockets, the type chart, moves' power /
    type (one PP differs: MOUNTAIN GALE 5 -> 10, read live from the Pokemon) and every map section's name (all four
    languages) and rect, Kanto's and Johto's. Its Latin American names of Johto's key items (879..889) were Portuguese
    in v0 and are Spanish in v1."""
    def same(a_rom, a, b_rom, b, stride, count, first=1):
        return [i for i in range(first, count) if a_rom[a - 0x08000000 + stride * i:a - 0x08000000 + stride * (i + 1)] !=
                b_rom[b - 0x08000000 + stride * i:b - 0x08000000 + stride * (i + 1)]]

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from gen_emerald_lang_tables import CHARS as WESTERN

    def wtext(r, a, maxlen):
        out = []
        for b in r[a - 0x08000000:a - 0x08000000 + maxlen]:
            if b == 0xFF:
                break
            out.append(" " if b in (0xFA, 0xFB, 0xFE) else WESTERN[b])
        return " ".join("".join(out).split())

    def names4(r, p):
        return tuple(text(r, u32(r, p + 4 * k), 40) if is_ptr(r, u32(r, p + 4 * k)) else None for k in range(4))

    la_items = None
    for path in paths:
        r = open(path, "rb").read()
        meta = V1.get(hashlib.sha1(r).hexdigest())
        if meta is None:
            sys.exit(f"{path} isn't a Quetzal Alpha 9 v1 build this knows")
        base = rom if meta["base"] == SHA1 else es_rom
        if hashlib.sha1(base).hexdigest() != meta["base"]:
            sys.exit(f"{meta['label']} is checked against {'English' if meta['base'] == SHA1 else 'Spanish'} Alpha 9 v0: pass it")
        b_hdr = lambda o: u32(base, 0x08000100 + o)
        hdr = lambda o: u32(r, 0x08000100 + o)
        assert not same(base, b_hdr(0x44), r, hdr(0x44), 13, 2 * SPECIES_COUNT), "species names differ"
        assert not same(base, b_hdr(0x48), r, hdr(0x48), 17, 3 * 936), "move names differ"
        b_items = ITEM_NAMES if base is rom else ES_TABLES["items"]["En"]
        assert not same(base, b_items, r, meta["item_names"], 20, 2 * ITEM_COUNT), "item names (English / Spanish) differ"
        la = same(base, b_items + 40 * ITEM_COUNT, r, meta["item_names"] + 40 * ITEM_COUNT, 20, ITEM_COUNT)
        mine = {i: wtext(r, meta["item_names"] + 20 * (2 * ITEM_COUNT + i), 20) for i in la}
        assert la_items is None or la_items == mine, "the builds' Latin American items differ"
        la_items = mine
        info, b_info = hdr(0xBC), b_hdr(0xBC)
        for i in range(1, SPECIES_COUNT):
            a, b = b_info + 0x24 * i, info + 0x24 * i
            assert base[a - 0x08000000:a - 0x08000000 + 0x24] == r[b - 0x08000000:b - 0x08000000 + 0x24], f"species {i}'s data"
        items, b_items_info = hdr(0xC8), b_hdr(0xC8)
        for i in range(1, ITEM_COUNT):
            assert u8(base, b_items_info + 0x1C * i + 0x12) == u8(r, items + 0x1C * i + 0x12), f"item {i}'s pocket"
        chart = rom[TYPE_CHART - 0x08000000:TYPE_CHART - 0x08000000 + 4 * TYPE_COUNT * TYPE_COUNT]
        assert r.count(chart) == 1, "type chart differs"
        moves, b_moves = hdr(0xCC), b_hdr(0xCC)
        for i in range(1, 936):
            a, b = b_moves + 0x18 * i, moves + 0x18 * i
            assert (u8(base, a + 2), u8(base, a + 3)) == (u8(r, b + 2), u8(r, b + 3)), f"move {i}'s power / type"
        b_e = REGION_MAP_ENTRIES if base is rom else ES_REGION_MAP_ENTRIES
        e = meta["region_map_entries"]
        for i in range(MAPSEC_COUNT):
            assert base[b_e + 8 * i + 4 - 0x08000000:b_e + 8 * i + 8 - 0x08000000] == r[e + 8 * i + 4 - 0x08000000:e + 8 * i + 8 - 0x08000000], hex(i)
            p, q = u32(base, b_e + 8 * i), u32(r, e + 8 * i)
            if is_ptr(base, p):
                assert names4(base, p) == names4(r, q), (hex(i), names4(base, p), names4(r, q))
        b_j = JOHTO_MAP_ENTRIES if base is rom else ES_JOHTO_MAP_ENTRIES
        j = meta["johto_map_entries"]
        for i in range(64):
            assert base[b_j + 8 * i - 0x08000000:b_j + 8 * i + 4 - 0x08000000] == r[j + 8 * i - 0x08000000:j + 8 * i + 4 - 0x08000000], f"johto {i}"
            p, q = u32(base, b_j + 8 * i + 4), u32(r, j + 8 * i + 4)
            if is_ptr(base, p):
                assert names4(base, p) == names4(r, q), (f"johto {i}", names4(base, p), names4(r, q))
        print(f"{meta['label']}: v0's tables; {len(mine)} Latin American item names of its own")
    body = header("Quetzal Alpha 9 v1's names that differ from v0's (laid over the Latin American item\n"
                  "// names by ActiveTables' quetzalV1). Everything else it shares by id - see gen_quetzal_tables.py's v1().\n")
    body += ("internal val itemNamesQuetzalLaV1Changes: Map<Int, String> = mapOf(\n" +
             "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(la_items.items())) + ")\n")
    write("QuetzalV1Gen.kt", body)


def main():
    global OUT_DIR
    if "--v1" in sys.argv:
        args = [a for a in sys.argv[1:] if a != "--v1"]
        rom, es_rom = open(args[0], "rb").read(), open(args[1], "rb").read()
        if hashlib.sha1(rom).hexdigest() != SHA1 or hashlib.sha1(es_rom).hexdigest() != ES_SHA1:
            sys.exit(f"usage: {sys.argv[0]} <English Alpha 9 v0> <Spanish Alpha 9 v0> --v1 <v1 rom.gba>...")
        v1(rom, es_rom, args[2:])
        return
    if "--v084" in sys.argv:
        rom = open(sys.argv[1], "rb").read()
        if hashlib.sha1(rom).hexdigest() != SHA1:
            sys.exit(f"not {LABEL} (sha1 {SHA1})")
        v084(rom, [a for a in sys.argv[2:] if a != "--v084"])
        return
    if "--languages" in sys.argv:
        rom = open(sys.argv[1], "rb").read()
        if hashlib.sha1(rom).hexdigest() != SHA1:
            sys.exit(f"not {LABEL} (sha1 {SHA1})")
        rest = [a for a in sys.argv[2:] if a != "--languages"]
        es = open(rest[0], "rb").read() if rest else None
        if es is not None and hashlib.sha1(es).hexdigest() != ES_SHA1:
            sys.exit(f"{rest[0]} isn't Quetzal Spanish Alpha 9 v0 (sha1 {ES_SHA1})")
        languages(rom, es)
        return
    if len(sys.argv) not in (2, 3):
        sys.exit(f"usage: {sys.argv[0]} <rom.gba> [out_dir]")
    if len(sys.argv) == 3:
        OUT_DIR = sys.argv[2]
    rom = open(sys.argv[1], "rb").read()
    if hashlib.sha1(rom).hexdigest() != SHA1:
        sys.exit(f"not {LABEL} (sha1 {SHA1})")

    names, types, gender, skipped = {}, {}, {}, []
    for i in range(1, SPECIES_COUNT):
        n = text(rom, SPECIES_NAMES + 13 * i, 13)
        if not n:
            if rom[SPECIES_NAMES + 13 * i - 0x08000000] != 0xAC:
                skipped.append(i)
            continue
        names[i] = n
        b = SPECIES_INFO + 0x24 * i
        types[i] = (u8(rom, b + 6), u8(rom, b + 7))
        gender[i] = u8(rom, b + 0x12)
    assert all(names.get(i) == n for i, n in CHECKS["species"].items()), "species names are wrong"
    assert all(types.get(i) == t for i, t in CHECKS["types"].items()), "species types are wrong"
    assert all(gender.get(i) == g for i, g in CHECKS["gender"].items()), "species gender ratios are wrong"
    write("SpeciesNamesQuetzal.kt", header("gSpeciesNames, keyed by the game's own species ids\n"
          "// (National Dex order through 898, then forms, Gen 9 from 1244).\n") +
          "val speciesNamesQuetzal: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(names.items())) + ")\n")
    write("SpeciesTypesQuetzal.kt", header("gSpeciesInfo types, ids per typeNamesQuetzal.\n") +
          "val speciesTypeDataQuetzal: Map<Int, SpeciesTypes> = mapOf(\n" +
          "".join(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(types.items())) + ")\n")
    hexs = "".join("%02x" % gender.get(i, 255) for i in range(max(gender) + 1))
    write("GenderRatiosQuetzal.kt", header("gSpeciesInfo genderRatio by species id (0 always\n"
          "// male, 254 always female, 255 genderless, else female when (personality & 0xFF) < ratio).\n") +
          f"val genderRatiosQuetzal: IntArray by lazy {{ unhexRatios(\"{hexs}\") }}\n")

    moves = []
    for i in range(1, 2000):
        n = text(rom, MOVE_NAMES + 17 * i, 17)
        if not n:
            if all(text(rom, MOVE_NAMES + 17 * (i + k), 17) is None for k in range(4)):
                break
            continue
        b = BATTLE_MOVES + 0x18 * i
        moves.append((i, n, u8(rom, b + 3), u8(rom, b + 2)))
    info = {i: (n, t, p) for i, n, t, p in moves}
    assert all(info.get(i) == v for i, v in CHECKS["moves"].items()), "moves are wrong"
    write("MoveDataQuetzal.kt", header("gMoveNames + gBattleMoves type / power (type ids per\n"
          "// typeNamesQuetzal).\n") +
          "val moveDataQuetzal: Map<Int, MoveInfo> = mapOf(\n" +
          "".join(f"    {i} to MoveInfo({kstr(n)}, {t}, {p}),\n" for i, n, t, p in moves) + ")\n")

    items = {}
    for i in range(1, ITEM_COUNT):
        n = text(rom, ITEM_NAMES + 20 * i, 20)
        if n:
            # gItems[i].itemId == i for every named item but Enigma Berry (581 reads 574).
            items[i] = n
    assert all(items.get(i) == n for i, n in CHECKS["items"].items()), "item names are wrong"
    p = u32(rom, ITEMS + 0x1C * 28 + 0xC)
    assert is_ptr(rom, p) and "20 points" in (text(rom, p, 200) or ""), "item description offset is wrong"
    write("ItemNamesQuetzal.kt", header("gItemNames, keyed by item id.\n") +
          "val itemNamesQuetzal: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(items.items())) + ")\n")
    # The bag screen's 11 tabs (gItems +0x12): ITEMS, MEDICINE, POKé BALLS, BERRIES, TRAINING,
    # EVO/FORMS, BATTLE ITEM, MEGA STONES, Z CRYSTALS, TERA SHARDS, KEY ITEMS; TMs are 100
    # (not in the bag). The app's pockets: balls, berries and key items; the rest are items.
    app_pocket = {3: "POCKET_POKE_BALLS", 4: "POCKET_BERRIES", 11: "POCKET_KEY_ITEMS", 100: "POCKET_TM_HM"}
    pockets = {i: app_pocket[u8(rom, ITEMS + 0x1C * i + 0x12)] for i in items
               if u8(rom, ITEMS + 0x1C * i + 0x12) in app_pocket}
    assert pockets.get(1) == "POCKET_POKE_BALLS" and 28 not in pockets and pockets.get(713) == "POCKET_KEY_ITEMS"
    write("ItemPocketsQuetzal.kt", header("The app's pocket for each item gItems doesn't file\n"
          "// under ITEMS (its +0x12 pocket: 3 balls, 4 berries, 11 key items, 100 TMs).\n") +
          "val itemPocketsQuetzal: Map<Int, Int> = mapOf(\n" +
          "".join(f"    {i} to {p},\n" for i, p in sorted(pockets.items())) + ")\n")

    chart = {}
    for atk in TYPE_NAMES:
        for dfn in TYPE_NAMES:
            v = u32(rom, TYPE_CHART + 4 * (atk * TYPE_COUNT + dfn))
            if v != 0x1000:
                chart[atk * 100 + dfn] = v * 100 // 0x1000
    assert chart[0 * 100 + 7] == 0 and chart[10 * 100 + 12] == 200 and chart[13 * 100 + 4] == 0 \
        and chart[16 * 100 + 18] == 0 and chart[18 * 100 + 16] == 200, "chart is wrong"
    write("TypeChartQuetzal.kt", header("Vanilla type ids plus Fairy 18 (19 Stellar unused).\n"
          "// gTypeEffectiveness [20][20] u32 uq4.12; key = atkType*100+defType -> percent, pairs not\n"
          "// listed are 100.\n") +
          "val typeNamesQuetzal: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(s)},\n" for i, s in sorted(TYPE_NAMES.items())) + ")\n\n" +
          "val typeEffectivenessQuetzal: Map<Int, Int> = mapOf(\n" +
          "".join(f"    {k} to {v},\n" for k, v in sorted(chart.items())) + ")\n")

    mapsecs = {}
    rects = {}   # Hoenn's and Johto's boxes, on their own maps: (image, x, y, w, h)

    def rect(i, image, at):
        x, y, w, h = rom[at - 0x08000000:at - 0x08000000 + 4]
        if w and h and (x or y):   # 0, 0 = off the map
            rects[i] = (image, x + MAP_SCREEN_DX, y + MAP_SCREEN_DY, w, h)

    for i in range(MAPSEC_COUNT):
        p = u32(rom, REGION_MAP_ENTRIES + 8 * i)
        if not is_ptr(rom, p):
            continue
        en = u32(rom, p)  # names[0] = English
        s = text(rom, en, 30) if is_ptr(rom, en) else None
        if s:
            mapsecs[i] = title(s)
            if i < FIRST_KANTO_MAPSEC:
                rect(i, HOENN_IMAGE, REGION_MAP_ENTRIES + 8 * i - 4)
    assert all(mapsecs.get(i) == n for i, n in CHECKS["mapsecs"].items()), "map sections are wrong"
    # Johto's map groups (34-35) name their sections from a table of their own (Violet City is 2 there).
    for i in range(MAPSEC_COUNT):
        p = u32(rom, JOHTO_MAP_ENTRIES + 8 * i + 4)
        if not is_ptr(rom, p):
            continue
        en = u32(rom, p)
        s = text(rom, en, 30) if is_ptr(rom, en) else None
        if s:
            mapsecs[JOHTO_MAPSEC_BASE + i] = title(s)
            rect(JOHTO_MAPSEC_BASE + i, JOHTO_IMAGE, JOHTO_MAP_ENTRIES + 8 * i)
    assert mapsecs[JOHTO_MAPSEC_BASE + 2] == "Violet City" and mapsecs[JOHTO_MAPSEC_BASE + 0x58] == "Route 29"
    # Hoenn is retail Emerald's map and places: Littleroot Town, Oldale Town, Rustboro City as MapSecDataEmerald has them.
    assert (rects[0], rects[1], rects[10]) == ((HOENN_IMAGE, 5, 13, 1, 1), (HOENN_IMAGE, 5, 11, 1, 1), (HOENN_IMAGE, 1, 7, 1, 2)), "Hoenn's boxes are wrong"
    assert rects[JOHTO_MAPSEC_BASE + 2] == (JOHTO_IMAGE, 13, 6, 1, 1), "Johto's boxes are wrong"
    write("MapSecDataQuetzal.kt", header("gRegionMapEntries' English names by mapsec id. Kanto's and Sevii's have region -1\n"
          "// here: ActiveTables puts them on its Kanto / Sevii maps with FireRed's rects. Hoenn's and Johto's keep the ROM's\n"
          "// boxes, on images 4 and 5 of regionMapImagesQuetzalOnMap. 0x100+: Johto's own\n"
          "// table's (its map groups 34-35 read their sections as 0x100 + id).\n") +
          "val regionMapImagesQuetzal = arrayOf<String>()\n\n" +
          "val mapSecDataQuetzal: Map<Int, MapSecInfo> = mapOf(\n" +
          "".join(f"    {i} to MapSecInfo({kstr(s)}, {', '.join(map(str, rects.get(i, (-1, 0, 0, 0, 0))))}),\n"
                  for i, s in sorted(mapsecs.items())) + ")\n")
    print(f"{len(names)} species ({len(skipped)} unreadable: {skipped[:20]}), {len(moves)} moves, {len(items)} items, "
          f"{len(chart)} chart pairs, {len(mapsecs)} map sections")


if __name__ == "__main__":
    main()
