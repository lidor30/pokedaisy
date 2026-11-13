#!/usr/bin/env python3
"""Generates PokeDaisy's display tables for a Generation 2 Game Boy Color Pokémon game
straight from the ROM the user owns (nothing is downloaded):

    scripts/gen_gen2_tables.py crystal <rom.gbc> [<another release's rom.gbc> ...]
    scripts/gen_gen2_tables.py crystal_clear <rom.gbc> [<another build's rom.gbc> ...]
    scripts/gen_gen2_tables.py prism <rom.gbc>
    scripts/gen_gen2_tables.py crystal_legacy <Crystal Legacy v1.3.1.gbc> --base <Crystal rom.gbc> ...

Writes app/src/main/kotlin/.../companion/data/{SpeciesNames,SpeciesTypes,MoveData,
ItemNames,TypeChart,MapSecData,Gen2Species}<Suffix>.kt.

Addresses (bank:address) are pret/pokecrystal's symbols: its `make` output is byte-identical
to every retail English Crystal (rev 0 crystal.gbc, rev 1 crystal11.gbc, Australia
crystal_au.gbc - roms.sha1), and the three keep every table here at the same address with
the same bytes: the generator reads the first ROM and checks the others agree byte for byte.
Map headers' landmarks are read from the ROM; their (group, number) come from the decomp's
data/maps/maps.asm order ($DECOMPS/pokecrystal). Names keep the game's own ALL-CAPS casing.

Crystal Clear (ShockSlayer's open-world hack, v2.5.11 and v2.6.0, each in a Standard Colors and a Color
Filter build) is pokecrystal grown and rebuilt: every table moved, no symbol file. Its addresses were found
with scripts/port_gen1.py (pokecrystal11.sym mapped by the code that uses each symbol) where the code kept
its shape, else by the tables' own contents (Crystal's bytes where the hack kept them; names, the 9-byte map
headers, 7-byte moves, 32-byte base data by their shape) - and are checked here, the same in all four builds.
It has no decomp, so its maps come from the ROM (MapGroupPointers: a group's headers run to the next group's,
the last group's while they're headers), and its TMs from its item names. It added landmarks past FAST SHIP
(0x60-0x74): its RegionCheck puts each in the region of a stand-in landmark from a table (landmark_regions).

Prism (RainbowDevs' Crystal-engine hack, v0.95 build 254; `prism <rom.gbc>`) has no decomp either and formats of its
own, read by read_prism: 254 species ids of its own (not Dex numbers; 253 is EGG), 24-byte base data, moves and items
named by a length byte and their text, a move's category in its type byte's top bits, five types more (Fairy, Gas,
Sound, Tri, Prism), a type chart of 2-bit cells per type pair, TMs that aren't items (a bit each in wTMsHMs: listed
here under ids of the companion's own, 0x100 + n), its landmarks placed by each region's town map grid and its one
Pokédex's order (253 species, numbered by their place in it; its pages are read from the ROM by Gen2Dex). Each
address is in GAMES["prism"], with the routine it was found by.

Crystal Legacy v1.3.1 (cRz-Shadows/Pokemon_Crystal_Legacy, a pret/pokecrystal fork: `make pokecrystal.gbc` with rgbds
0.5.2 at tag V1.3.1 is the patched cart byte for byte, from either retail revision's patch, so its pokecrystal.sym
names every table; $DECOMPS/pokecrystallegacy): Crystal's shapes at its own addresses, with its own stats, moves and items.

Gen 2 keys everything by the National Dex number (its species ids are Dex numbers, 1-251) - but Prism.
"""
import hashlib
import os
import re
import struct
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin",
                       "com", "pokedaisy", "app", "companion", "data")

GAMES = {
    "crystal": dict(
        suffix="Crystal",
        label="Pokemon Crystal Version (USA / USA, Europe rev 1 / Australia)",
        decomp="pokecrystal",
        sha1s={
            "f4cd194bdee0d04ca4eac29e09b8e4e9d818c133": "rev 0",
            "f2f52230b536214ef7c9924f483392993e226cfb": "rev 1",
            "a0fc810f1d4e124434f7be2c989ab5b5892ddf36": "Australia",
        },
        species_names=(0x14, 0x7384), name_len=10, species_count=251,
        base_data=(0x14, 0x5424), base_size=32,
        moves=(0x10, 0x5AFB), move_size=7, move_count=251, move_names=(0x72, 0x5F29),
        item_names=(0x72, 0x4000), item_count=0xFF,
        type_names=(0x14, 0x497B), type_count=28,
        type_matchups=(0x0D, 0x4BB1),
        landmarks=(0x72, 0x68C3), landmark_count=0x60, kanto_landmark=0x2F, fast_ship=0x5F,
        map_groups=(0x25, 0x4000), map_header_size=9,
        checks=dict(species={1: "BULBASAUR", 25: "PIKACHU", 152: "CHIKORITA", 249: "LUGIA", 251: "CELEBI"},
                    types={1: (22, 3), 25: (23, 23), 152: (22, 22), 16: (0, 2), 208: (9, 4)},
                    moves={1: ("POUND", 0, 40), 33: ("TACKLE", 0, 35), 45: ("GROWL", 0, 0), 242: ("CRUNCH", 27, 80)},
                    items={1: "MASTER BALL", 5: "POKé BALL", 18: "POTION", 0xAD: "BERRY", 0xBF: "TM01", 0xF3: "HM01"},
                    landmarks={1: "NEW BARK TOWN", 2: "ROUTE 29", 0x2F: "PALLET TOWN"},
                    maps={(24, 3): 2, (24, 4): 1, (24, 5): 1, (24, 7): 1}),
    ),
    "crystal_de": dict(
        suffix="CrystalDe",
        label="Pokemon - Kristall-Edition (Germany)",
        decomp="pokecrystal",
        sha1s={"accb584293ba056152f1fd908439b019017ff2fe": "Germany"},
        # English Crystal translated: only its names are written, the rest is checked equal to Crystal's (--base).
        names_over="crystal",
        tm_names=("TM01", "TM50", "VM01"),
        species_names=(0x14, 0x736E), name_len=10, species_count=251,
        base_data=(0x14, 0x540E), base_size=32,
        moves=(0x10, 0x5AEB), move_size=7, move_count=251, move_names=(0x72, 0x5E9D),
        item_names=(0x72, 0x4000), item_count=0xFF,
        type_names=(0x14, 0x496E), type_count=28,
        type_matchups=(0x0D, 0x4BB1),
        landmarks=(0x72, 0x68E5), landmark_count=0x60, kanto_landmark=0x2F, fast_ship=0x5F,
        map_groups=(0x25, 0x4000), map_header_size=9,
        checks=dict(species={1: "BISASAM", 25: "PIKACHU", 249: "LUGIA", 251: "CELEBI"},
                    types={1: (22, 3), 25: (23, 23), 152: (22, 22), 16: (0, 2), 208: (9, 4)},
                    moves={1: ("PFUND", 0, 40), 45: ("HEULER", 0, 0)},
                    items={1: "MEISTERBALL", 5: "POKéBALL", 0xBF: "TM01", 0xF3: "VM01"},
                    landmarks={1: "NEUBORKIA", 2: "ROUTE 29"},
                    maps={(24, 3): 2, (24, 4): 1, (24, 5): 1, (24, 7): 1}),
    ),
    "crystal_legacy": dict(
        suffix="CrystalLegacy",
        label="Pokemon Crystal Legacy v1.3.1",
        decomp="pokecrystallegacy",
        sha1s={"74a6727e4406c35bc2d4f81347a5c5fdb8970663": "v1.3.1"},
        # Its species, types, type chart and places are Crystal's (checked with --base); moves, items and TM ids its own.
        base="crystal", shares=("species", "types", "type_chart", "places"),
        species_names=(0x14, 0x73A9), name_len=10, species_count=251,
        base_data=(0x14, 0x5449), base_size=32,
        moves=(0x10, 0x4000), move_size=7, move_count=251, move_names=(0x72, 0x5F62),
        item_names=(0x72, 0x4000), item_count=0xFF,
        type_names=(0x14, 0x4980), type_count=28,
        type_matchups=(0x0D, 0x4BBB),
        landmarks=(0x72, 0x68FC), landmark_count=0x60, kanto_landmark=0x2F, fast_ship=0x5F,
        map_groups=(0x25, 0x4000), map_header_size=9,
        checks=dict(species={1: "BULBASAUR", 25: "PIKACHU", 152: "CHIKORITA", 249: "LUGIA", 251: "CELEBI"},
                    types={1: (22, 3), 25: (23, 23), 152: (22, 22)},
                    moves={1: ("POUND", 0, 40), 45: ("GROWL", 0, 0)},
                    items={1: "MASTER BALL", 5: "POKé BALL", 18: "POTION", 0xAD: "BERRY"},
                    landmarks={1: "NEW BARK TOWN", 2: "ROUTE 29", 0x2F: "PALLET TOWN"},
                    maps={(24, 3): 2, (24, 4): 1, (24, 5): 1, (24, 7): 1}),
    ),
    "gold_silver": dict(
        suffix="GoldSilver",
        label="Pokemon Gold / Silver Version (USA, Europe)",
        decomp="pokegold",
        sha1s={
            "d8b8a3600a465308c9953dfa04f0081c05bdcb94": "Gold",
            "49b163f7e57702bc939d642a18f591de55d92dae": "Silver",
        },
        # pret/pokegold's symbols: every table here sits at the same address with the same bytes in both versions
        # (checked byte for byte); only their dex pages, front pics and party icons' place differ (read from the ROM).
        species_names=(0x6C, 0x4B74), name_len=10, species_count=251,
        base_data=(0x14, 0x5B0B), base_size=32,
        moves=(0x10, 0x5AFE), move_size=7, move_count=251, move_names=(0x6C, 0x5574),
        item_names=(0x6C, 0x4000), item_count=0xFF,
        type_names=(0x14, 0x49AE), type_count=28,
        type_matchups=(0x0D, 0x4D01),
        landmarks=(0x24, 0x6382), landmark_count=0x5F, kanto_landmark=0x2E, fast_ship=0x5E,
        map_groups=(0x25, 0x40ED), map_header_size=9,
        # Crystal kept their species, base data, moves, types and type chart byte for byte (checked with --base); their
        # items are Crystal's but for the four Crystal filled in (CLEAR BELL, GS BALL, BLUE CARD, EGG TICKET), their
        # landmarks one fewer (no BATTLE TOWER: from ROUTE 40 on, one id lower than Crystal's).
        base="crystal", shares=("species", "types", "moves", "type_chart", "gen2_species"),
        checks=dict(species={1: "BULBASAUR", 25: "PIKACHU", 152: "CHIKORITA", 249: "LUGIA", 251: "CELEBI"},
                    types={1: (22, 3), 25: (23, 23), 152: (22, 22), 16: (0, 2), 208: (9, 4)},
                    moves={1: ("POUND", 0, 40), 33: ("TACKLE", 0, 35), 45: ("GROWL", 0, 0), 242: ("CRUNCH", 27, 80)},
                    items={1: "MASTER BALL", 5: "POKé BALL", 18: "POTION", 0xAD: "BERRY", 0xBF: "TM01", 0xF3: "HM01"},
                    landmarks={1: "NEW BARK TOWN", 2: "ROUTE 29", 0x2E: "PALLET TOWN", 0x5E: "FAST SHIP"},
                    maps={(24, 3): 2, (24, 4): 1, (24, 5): 1, (24, 7): 1}),
    ),
    "gold_silver_de": dict(
        suffix="GoldSilverDe",
        label="Pokemon - Goldene / Silberne Edition (Germany)",
        decomp="pokegold",
        sha1s={
            "9254195d461ea942eaaa08cc4b83de3cf82aea0d": "Gold",
            "8ecc58d621faaedf2a934bd2583d527220df7bb9": "Silver",
        },
        # English Gold / Silver translated: only its names are written, the rest is checked equal to theirs (--base).
        names_over="gold_silver",
        tm_names=("TM01", "TM50", "VM01"),
        species_names=(0x6C, 0x4B6B), name_len=10, species_count=251,
        base_data=(0x14, 0x5B00), base_size=32,
        moves=(0x10, 0x5AF1), move_size=7, move_count=251, move_names=(0x6C, 0x556B),
        item_names=(0x6C, 0x4000), item_count=0xFF,
        type_names=(0x14, 0x49A1), type_count=28,
        type_matchups=(0x0D, 0x4D01),
        landmarks=(0x27, 0x402D), landmark_count=0x5F, kanto_landmark=0x2E, fast_ship=0x5E,
        map_groups=(0x25, 0x40ED), map_header_size=9,
        checks=dict(species={1: "BISASAM", 25: "PIKACHU", 249: "LUGIA", 251: "CELEBI"},
                    types={1: (22, 3), 25: (23, 23), 152: (22, 22), 16: (0, 2), 208: (9, 4)},
                    moves={1: ("PFUND", 0, 40), 45: ("HEULER", 0, 0)},
                    items={1: "MEISTERBALL", 5: "POKéBALL", 0xBF: "TM01", 0xF3: "VM01"},
                    landmarks={1: "NEUBORKIA", 2: "ROUTE 29"},
                    maps={(24, 3): 2, (24, 4): 1, (24, 5): 1, (24, 7): 1}),
    ),
    "crystal_clear": dict(
        suffix="CrystalClear",
        label="Pokemon Crystal Clear (v2.5.11 / v2.6.0, Standard Colors / Color Filter)",
        decomp=None,
        sha1s={
            "a1c4da6b0057c98fe4ebea45d6812a484fc69abd": "v2.5.11",
            "6fc677bf7a9db103cb9ee11ac08d26782e69ae41": "v2.5.11 Color Filter",
            "84d5c4924c3f1f3fc690669148b7e392948fb23d": "v2.6.0",
            "534876e4e4d0d79a82fef3bbe5bea11c40451727": "v2.6.0 Color Filter",
        },
        species_names=(0x14, 0x75F8), name_len=10, species_count=251,
        base_data=(0x14, 0x5698), base_size=32,
        moves=(0x10, 0x4166), move_size=7, move_count=251, move_names=(0x72, 0x5E9B),
        item_names=(0x72, 0x4000), item_count=0xFF,
        type_names=(0x14, 0x4AFD), type_count=28,
        type_matchups=(0x0D, 0x4BCD),
        landmarks=(0x72, 0x6833), landmark_count=0x75, kanto_landmark=0x2F, fast_ship=0x5F,
        landmark_regions=(0x72, 0x6F8B),
        text_dict=(0x00, 0x11FF),
        # Its species, base data, moves, types and type chart are Crystal's, byte for byte (checked with --base).
        base="crystal", shares=("species", "types", "moves", "type_chart", "gen2_species"),
        map_groups=(0x25, 0x4000), map_group_count=26, map_header_size=9,
        checks=dict(species={1: "BULBASAUR", 25: "PIKACHU", 152: "CHIKORITA", 249: "LUGIA", 251: "CELEBI"},
                    types={1: (22, 3), 25: (23, 23), 152: (22, 22)},
                    moves={1: ("POUND", 0, 40), 33: ("TACKLE", 0, 35), 45: ("GROWL", 0, 0)},
                    items={1: "MASTER BALL", 5: "POKé BALL", 18: "POTION", 0xBF: "TM01", 0xF3: "HM01"},
                    landmarks={1: "NEW BARK TOWN", 2: "ROUTE 29", 0x2F: "PALLET TOWN", 0x5F: "FAST SHIP",
                               0x60: "VIRIDIAN FOREST"},
                    maps={(24, 3): 2, (24, 4): 1}),
    ),
    "prism": dict(
        suffix="Prism",
        label="Pokemon Prism (v0.95 build 254, Hotfix 5)",
        decomp=None, reader="prism",
        sha1s={"752076692ae3387cf426ce5f51a98c6b60e8df6a": "v0.95 build 254"},
        # Its own species (254 ids, not Dex numbers: 253 is EGG), 254 moves and items, each name a length byte
        # then the text (moves, items) or 10 bytes (species); BaseData shrank to 24 bytes (no TM bits).
        species_names=(0x05, 0x55E5), name_len=10, species_count=254,
        base_data=(0x14, 0x5A0E), base_size=24,
        moves=(0x10, 0x5263), move_size=7, move_count=254, move_names=(0x11, 0x61BC),
        item_names=(0x11, 0x4000), item_count=0xFF,
        # TypeNames' pointers, and its type chart: a 2-bit cell per (attacking, defending) type id, 7 bytes a row
        # (0 no effect, 1 half, 2 normal, 3 double; BattleCheckTypeMatchup at 0D:5C0E).
        type_names=(0x13, 0x53DA), type_count=28, type_matrix=(0x0D, 0x7AC4),
        # TMs aren't items: a bit per TM01-96 / HM01-05 (wTMsHMs), the move from TMHMMoves (GetTMHMMove, 04:5392).
        tmhm_moves=(0x04, 0x53A4), tm_count=96, hm_count=5,
        # Its one Pokédex: the species in the game's list order, No.1 first (10:48DF fills the list from it; 10:4847
        # numbers an entry by its place there). Every species but EGG, once.
        dex_order=(0x10, 0x4915), dex_count=253,
        # Landmarks: their names' pointers (09:67EF, GetLandmarkName), and each region's town map grid (09:6638:
        # LZ, 18 x 15 cells, a landmark id per cell, bit 7 on the cell its dot is drawn at).
        landmark_names=(0x09, 0x67EF), landmark_count=113, region_grids=(0x09, 0x6638), region_count=6,
        map_groups=(0x25, 0x4000), map_group_count=95, map_header_size=9,
        notes=dict(
            species=" PokemonNames, keyed by its own species id (1-254: 253 is EGG,\n"
                    "// 254 one past it; not Dex numbers).\n",
            types=" TypeNames (Gen 2's ids, plus 10 Fairy, 11 Gas, 13 Sound, 14 Tri,\n"
                  "// 15 Prism) and its type chart: key = atkType*100+defType -> percent, pairs not listed are 100.\n",
            regions="region 0 Naljo, 1 Rijon, 2 Johto, 3 Kanto, 4 Sevii, 5 Tunod",
            tms="// species id; the TM01-96 / HM01-05 ids the companion gives them (0x100 + n: not items in Prism), in\n"
                "// the TM pocket's (wTMsHMs, a bit each) order.\n"),
        checks=dict(species={1: "Bulbasaur", 27: "Shinx", 246: "Larvitar", 252: "Phancero", 253: "Egg",
                             254: "Libabeel"},
                    types={1: (22, 3), 27: (23, 23), 246: (5, 4), 4: (20, 20)},
                    moves={1: ("Pound", 0, 40), 2: ("Karate Chop", 1, 50), 33: ("Tackle", 0, 40)},
                    items={1: "Master Ball", 5: "POKé Ball", 18: "Potion", 173: "Oran Berry", 191: "TM Case"},
                    landmarks={0: "Special", 4: "Caper Ridge", 0x24: "Acqua Mines", 0x70: "Mystery Zone"},
                    maps={(1, 1): 0x2E, (31, 1): 0x24}),
    ),
}

CHARS = {0x7F: " ", 0xE0: "'", 0xE3: "-", 0xE6: "?", 0xE7: "!", 0xE8: ".", 0xE9: "&", 0xEA: "é", 0xEF: "♂", 0xF5: "♀",
         0xF0: "¥", 0xF1: "×", 0xF2: ".", 0xF3: "/", 0xF4: ",", 0x9A: "(", 0x9B: ")", 0x9C: ":", 0x9D: ";", 0x9E: "[",
         0x9F: "]", 0x54: "POKé", 0x4A: "PKMN", 0xE1: "PK", 0xE2: "MN", 0xD0: "'d", 0xD1: "'l", 0xD2: "'m", 0xD3: "'r",
         0xD4: "'s", 0xD5: "'t", 0xD6: "'v", 0x75: "…", 0x56: "……", 0x5C: "TM", 0x5B: "PC", 0x5D: "TRAINER",
         0x5E: "ROCKET", 0x1F: " ", 0x1E: "-", 0x25: "", 0x22: " ", 0x4E: " ", 0x4F: " ", 0xC0: "Ä", 0xC1: "Ö", 0xC2: "Ü",
         0xC3: "ä", 0xC4: "ö", 0xC5: "ü"}
CHARS.update({0x80 + i: chr(65 + i) for i in range(26)})
CHARS.update({0xA0 + i: chr(97 + i) for i in range(26)})
CHARS.update({0xF6 + i: str(i) for i in range(10)})


DICT_FIRST, DICT_LAST = 0x16, 0x5F


class Rom:
    def __init__(self, data, text_dict=None):
        self.d = data
        # Crystal Clear packs its text with a dictionary: PlaceNextChar looks a byte 0x16-0x5F up in a table
        # of 3-byte entries (0 + a bank-0 string - "with", "#MON" - or 1 + a routine: line breaks, names).
        self.words = {}
        if text_dict:
            o = self.off(*text_dict)
            for c in range(DICT_FIRST, DICT_LAST + 1):
                kind, p = data[o + 3 * (c - DICT_FIRST)], struct.unpack_from("<H", data, o + 3 * (c - DICT_FIRST) + 1)[0]
                if kind == 0 and p < 0x4000 and c not in (0x1F, 0x50):   # 0x1F: a space before the lookup
                    self.words[c] = p

    @staticmethod
    def off(bank, addr):
        return addr if bank == 0 else bank * 0x4000 + (addr - 0x4000)

    def u8(self, bank, addr):
        return self.d[self.off(bank, addr)]

    def text(self, bank, addr, maxlen=40):
        out = []
        o = self.off(bank, addr)
        for b in self.d[o:o + maxlen]:
            if b == 0x50:
                return "".join(out)
            if b in self.words:
                out.append(self.text(0, self.words[b]))
                continue
            if b not in CHARS:
                raise ValueError(f"unknown char {b:02X} at {bank:02X}:{addr:04X}")
            out.append(CHARS[b])
        raise ValueError(f"unterminated text at {bank:02X}:{addr:04X}")

    def text_list(self, bank, addr, count):
        """`count` 0x50-terminated strings back to back (MoveNames, ItemNames)."""
        out = []
        for _ in range(count):
            out.append(self.text(bank, addr))
            o = self.off(bank, addr)
            addr += self.d.index(0x50, o) - o + 1
        return out


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def write(name, header, body):
    path = os.path.join(OUT_DIR, name)
    with open(path, "w") as f:
        f.write("package com.pokedaisy.app.companion.data\n\n" + header + "\n" + body)
    print("wrote", os.path.relpath(path))


def kmap(name, typ, items, key="Int"):
    lines = [f"val {name}: Map<{key}, {typ}> = mapOf("]
    lines += [f"    {k} to {v}," for k, v in items]
    return "\n".join(lines) + "\n)\n"


def decomp_maps(decomp):
    """[(group, number, LANDMARK_*)] from data/maps/maps.asm, groups numbered by MapGroupPointers."""
    src = open(os.path.join(decomp, "data", "maps", "maps.asm")).read()
    order = re.findall(r"^\s*dw (MapGroup_\w+)", src, re.M)
    groups = {}
    cur = None
    for line in src.splitlines():
        m = re.match(r"^(MapGroup_\w+):", line)
        if m:
            cur = m.group(1)
            groups[cur] = []
            continue
        m = re.match(r"^\s*map (\w+), \w+, \w+, (LANDMARK_\w+)", line)
        if m and cur:
            groups[cur].append(m.group(2))
    consts = {}
    n = 0
    for line in open(os.path.join(decomp, "constants", "landmark_constants.asm")):
        m = re.match(r"^\s*const (LANDMARK_\w+)", line)
        if m:
            consts[m.group(1)] = n
            n += 1
    out = []
    for gi, g in enumerate(order):
        for mi, lm in enumerate(groups[g]):
            out.append((gi + 1, mi + 1, consts[lm]))
    return out


def rom_maps(rom, g):
    """[(group, number, landmark)] read from the ROM's MapGroupPointers (a game without a decomp): a group's
    headers run to the next group's; the last group's while they look like headers (the first byte, the
    attributes' bank, the same as every other header's; an environment 1-7; attributes in 4000-7FFF)."""
    gb, ga = g["map_groups"]
    size = g["map_header_size"]
    ptrs = [struct.unpack_from("<H", rom.d, rom.off(gb, ga + 2 * i))[0] for i in range(g["map_group_count"])]
    attr_bank = rom.d[rom.off(gb, ptrs[0])]

    def header(p):
        h = rom.d[rom.off(gb, p):rom.off(gb, p) + size]
        return h[0] == attr_bank and 1 <= h[2] <= 7 and 0x4000 <= (h[3] | h[4] << 8) < 0x8000

    out = []
    for gi, p in enumerate(ptrs):
        later = [q for q in ptrs if q > p]
        if later:
            assert (min(later) - p) % size == 0, f"group {gi + 1}: not whole headers"
            n = (min(later) - p) // size
        else:
            n = 0
            while header(p + n * size):
                n += 1
        for mi in range(n):
            assert header(p + mi * size), f"map {gi + 1}.{mi + 1}: not a header"
            out.append((gi + 1, mi + 1, rom.d[rom.off(gb, p + mi * size) + 5]))
    return out


def rom_tm_items(items):
    """The TM01-50 / HM01-07 item ids from the item names (a game without a decomp)."""
    by_name = {}
    for k in sorted(items):
        by_name.setdefault(items[k], k)
    return [by_name[f"TM{i:02d}"] for i in range(1, 51)] + [by_name[f"HM{i:02d}"] for i in range(1, 8)]


def tm_items(decomp):
    """The TM01-50 / HM01-07 item ids, from the decomp's item constants (ITEM_C3 / ITEM_DC sit between)."""
    ids = []
    n = 0
    for line in open(os.path.join(decomp, "constants", "item_constants.asm")):
        if "\\" in line:
            continue  # a macro's own body
        m = re.match(r"^\s*const (\w+)", line)
        if m:
            n += 1
            continue
        m = re.match(r"^\s*add_(tm|hm) (\w+)", line)
        if m:
            ids.append(n)
            n += 1
    return ids


def load_roms(g, paths):
    roms = []
    for path in paths:
        data = open(path, "rb").read()
        h = hashlib.sha1(data).hexdigest()
        if h not in g["sha1s"]:
            sys.exit(f"{path} isn't {g['label']}")
        roms.append((g["sha1s"][h], data))
    return roms


def read_game(g, roms):
    """Every table [g] describes, from the first of [roms], checked against the others byte for byte."""
    if g.get("reader") == "prism":
        return read_prism(g, roms)
    decomp = g["decomp"] and os.path.join(os.environ.get("DECOMPS", ""), g["decomp"])
    if decomp and not os.path.isdir(decomp):
        sys.exit(f"needs $DECOMPS/{g['decomp']} (pret's decomp) for the map list")
    data = roms[0][1]
    if "text_dict" in g:
        td = Rom.off(*g["text_dict"])
        for name, other in roms[1:]:
            assert other[td:td + 3 * (DICT_LAST - DICT_FIRST + 1)] == data[td:td + 3 * (DICT_LAST - DICT_FIRST + 1)], \
                f"text dictionary differs in {name}"
    rom = Rom(data, g.get("text_dict"))
    others = [(name, Rom(other, g.get("text_dict"))) for name, other in roms[1:]]
    c = g["checks"]

    def same(bank, addr, size, what):
        o = rom.off(bank, addr)
        for name, other in roms[1:]:
            assert other[o:o + size] == data[o:o + size], f"{what} differs in {name}"

    # Species: names and base data are by Dex number.
    nb, na = g["species_names"]
    count = g["species_count"]
    same(nb, na, count * g["name_len"], "species names")
    names = {}
    for s in range(1, count + 1):
        raw = data[rom.off(nb, na + (s - 1) * g["name_len"]):][:g["name_len"]]
        names[s] = "".join(CHARS[b] for b in raw.split(b"\x50")[0])
    bb, ba = g["base_data"]
    same(bb, ba, count * g["base_size"], "base data")
    types, genders, growth = {}, [255], [0]
    for s in range(1, count + 1):
        o = rom.off(bb, ba + (s - 1) * g["base_size"])
        assert data[o] == s, f"base data {s}: dex id {data[o]}"
        types[s] = (data[o + 7], data[o + 8])
        genders.append(data[o + 13])
        growth.append(data[o + 22])
    assert all(names[k] == v for k, v in c["species"].items()), "species names"
    assert all(types[k] == v for k, v in c["types"].items()), "species types"

    # Moves: 7-byte entries (animation, effect, power, type, accuracy, pp, effect chance) + MoveNames.
    mnb, mna = g["move_names"]
    move_names = rom.text_list(mnb, mna, g["move_count"])
    for name, r in others:
        assert r.text_list(mnb, mna, g["move_count"]) == move_names, f"move names differ in {name}"
    mvb, mva = g["moves"]
    same(mvb, mva, g["move_count"] * g["move_size"], "moves")
    moves = {}
    for i in range(g["move_count"]):
        o = rom.off(mvb, mva + i * g["move_size"])
        assert data[o] == i + 1, f"move {i + 1}: animation {data[o]}"
        moves[i + 1] = (move_names[i], data[o + 3], data[o + 2])
    assert all(moves[k] == v for k, v in c["moves"].items()), "moves"

    # Items: ItemNames, TMs / HMs included; the unused slots keep their placeholder names.
    ib, ia = g["item_names"]
    items = {i + 1: n for i, n in enumerate(rom.text_list(ib, ia, g["item_count"]))}
    for name, r in others:
        assert r.text_list(ib, ia, g["item_count"]) == list(items.values()), f"item names differ in {name}"
    assert all(items[k] == v for k, v in c["items"].items()), "items"
    tms = tm_items(decomp) if decomp else rom_tm_items(items)
    tm01, tm50, hm01 = g.get("tm_names", ("TM01", "TM50", "HM01"))
    assert len(tms) == 57 and items[tms[0]] == tm01 and items[tms[49]] == tm50 and items[tms[50]] == hm01, "TM ids"

    # Types: a pointer table (BIRD and the unused ids, which point at NORMAL, left out) + TypeMatchups
    # (attacker, defender, x10 multiplier; 0xFE marks the Foresight split, 0xFF ends it).
    tb, ta = g["type_names"]

    def type_name(r, t):
        return r.text(tb, struct.unpack_from("<H", r.d, r.off(tb, ta + 2 * t))[0])
    type_names = {}
    for t in range(g["type_count"]):
        n = type_name(rom, t)
        if (n == "NORMAL" and t != 0) or n in ("BIRD", ""):
            continue
        type_names[t] = n
    for name, r in others:
        assert all(type_name(r, t) == n for t, n in type_names.items()), f"type names differ in {name}"
    eb, ea = g["type_matchups"]
    chart = {}
    o = rom.off(eb, ea)
    while data[o] != 0xFF:
        if data[o] == 0xFE:
            o += 1
            continue
        atk, dfn, mult = data[o], data[o + 1], data[o + 2]
        chart[atk * 100 + dfn] = mult * 10
        o += 3
    same(eb, ea, o - rom.off(eb, ea) + 1, "type matchups")

    # Landmarks: (x + 8, y + 16, name pointer) - x / y the centre of the town map's 16x16 player icon.
    lb, la = g["landmarks"]
    same(lb, la, g["landmark_count"] * 4, "landmarks")
    # The names follow the table, so the first one's address is where it ends: landmark_count is the ROM's own.
    assert min(struct.unpack_from("<H", data, rom.off(lb, la + 4 * i + 2))[0] for i in range(g["landmark_count"])) == \
        la + 4 * g["landmark_count"], "landmark count"
    stand_in = {}
    if "landmark_regions" in g:
        # RegionCheck for a landmark past FAST SHIP: the region of the stand-in landmark its table gives.
        rb, ra = g["landmark_regions"]
        n = g["landmark_count"] - (g["fast_ship"] + 1)
        same(rb, ra, n, "landmark regions")
        stand_in = {g["fast_ship"] + 1 + i: data[rom.off(rb, ra) + i] for i in range(n)}
        # The table stops one short: the last landmark (ORIGIN PLAINS) reads the code after it, no place on a map.
        assert sum(v >= g["fast_ship"] for v in stand_in.values()) <= 1, "stand-in landmarks"
    landmarks = {}
    for i in range(g["landmark_count"]):
        o = rom.off(lb, la + 4 * i)
        x, y, p = data[o] - 8, data[o + 1] - 16, struct.unpack_from("<H", data, o + 2)[0]
        landmarks[i] = (rom.text(lb, p), x, y)
        assert all(r.text(lb, p) == landmarks[i][0] for _, r in others), f"landmark {i}'s name"
    assert all(landmarks[k][0] == v for k, v in c["landmarks"].items()), "landmarks"

    # Map (group, number) -> landmark: the decomp's map list, checked against each ROM header's landmark byte;
    # without a decomp, the ROM's own list.
    gb, ga = g["map_groups"]
    maps = {}
    if decomp:
        for grp, num, lm in decomp_maps(decomp):
            gp = struct.unpack_from("<H", data, rom.off(gb, ga + 2 * (grp - 1)))[0]
            o = rom.off(gb, gp + (num - 1) * g["map_header_size"])
            assert data[o + 5] == lm, f"map {grp}.{num}: landmark {data[o + 5]} in the ROM, {lm} in maps.asm"
            maps[(grp, num)] = lm
    else:
        listed = rom_maps(rom, g)
        for name, r in others:
            assert rom_maps(r, g) == listed, f"maps differ in {name}"
        for grp, num, lm in listed:
            assert lm < g["landmark_count"], f"map {grp}.{num}: landmark {lm}"
            maps[(grp, num)] = lm
    assert all(maps[k] == v for k, v in c["maps"].items()), "maps"
    return dict(species=names, types=types, moves=moves, items=items, type_chart=(type_names, chart),
                gen2_species=(genders, growth, tms), landmarks=landmarks, stand_in=stand_in, maps=maps)


FLIP = [int(f"{b:08b}"[::-1], 2) for b in range(256)]


def lz(data, start, limit=0x4000):
    """Gen 2's LZ (home/decompress.asm; Gen2Lz in the app): the bytes from [start], or None if they aren't LZ."""
    out, p = [], start
    while True:
        b = data[p]
        p += 1
        if b == 0xFF:
            return bytes(out)
        cmd = b >> 5
        if cmd == 7:
            cmd, n = (b >> 2) & 7, (((b & 3) << 8) | data[p]) + 1
            p += 1
        else:
            n = (b & 0x1F) + 1
        if cmd == 0:
            out += data[p:p + n]
            p += n
        elif cmd == 1:
            out += [data[p]] * n
            p += 1
        elif cmd == 2:
            out += [data[p] if i % 2 == 0 else data[p + 1] for i in range(n)]
            p += 2
        elif cmd == 3:
            out += [0] * n
        else:
            o = data[p]
            p += 1
            if o & 0x80:
                src = len(out) - (o & 0x7F) - 1
            else:
                src = (o << 8) | data[p]
                p += 1
            if not 0 <= src < len(out):
                return None
            for i in range(n):
                out.append(out[src + i] if cmd == 4 else FLIP[out[src + i]] if cmd == 5 else out[src - i])
        if len(out) > limit:
            return None


def len_names(rom, bank, addr, count):
    """[count] names, each a length byte (itself included) then its text: Prism's moves and items."""
    out, o = [], rom.off(bank, addr)
    for _ in range(count):
        n = rom.d[o]
        out.append("".join(CHARS[b] for b in rom.d[o + 1:o + n]))
        o += n
    return out


def read_prism(g, roms):
    """Pokémon Prism: no decomp, formats of its own (see GAMES["prism"]); one release, nothing to compare."""
    rom = Rom(roms[0][1])
    data = rom.d
    c = g["checks"]
    nb, na = g["species_names"]
    count = g["species_count"]
    names = {}
    for s in range(1, count + 1):
        raw = data[rom.off(nb, na + (s - 1) * g["name_len"]):][:g["name_len"]]
        names[s] = "".join(CHARS[b] for b in raw.split(b"\x50")[0])
    bb, ba = g["base_data"]
    types, genders, growth = {}, [255], [0]
    for s in range(1, count + 1):
        o = rom.off(bb, ba + (s - 1) * g["base_size"])
        assert data[o] == s, f"base data {s}: species id {data[o]}"
        types[s] = (data[o + 7], data[o + 8])
        genders.append(data[o + 13])
        growth.append(data[o + 22])
    assert all(names[k] == v for k, v in c["species"].items()), "species names"
    assert all(types[k] == v for k, v in c["types"].items()), "species types"

    move_names = len_names(rom, *g["move_names"], g["move_count"])
    mvb, mva = g["moves"]
    moves = {}
    for i in range(g["move_count"]):
        o = rom.off(mvb, mva + i * g["move_size"])
        assert data[o] == i + 1, f"move {i + 1}: animation {data[o]}"
        # The type byte's top two bits are the move's category (physical / special / status).
        moves[i + 1] = (move_names[i], data[o + 3] & 0x3F, data[o + 2])
    assert all(moves[k] == v for k, v in c["moves"].items()), "moves"

    items = {i + 1: n for i, n in enumerate(len_names(rom, *g["item_names"], g["item_count"]))}
    assert all(items[k] == v for k, v in c["items"].items()), "items"
    # TMs / HMs aren't items in Prism. The companion lists them under ids of its own past the items' (0x100 + n),
    # named as the TM pocket shows them.
    tb, ta = g["tmhm_moves"]
    tms = []
    for i in range(g["tm_count"] + g["hm_count"]):
        move = data[rom.off(tb, ta) + i]
        assert 1 <= move <= g["move_count"], f"TM {i + 1}: move {move}"
        label = f"TM{i + 1:02d}" if i < g["tm_count"] else f"HM{i - g['tm_count'] + 1:02d}"
        items[0x101 + i] = f"{label} {move_names[move - 1]}"
        tms.append(0x101 + i)
    assert data[rom.off(tb, ta) + g["tm_count"] + g["hm_count"]] == 0, "TMHMMoves' end"

    tyb, tya = g["type_names"]
    type_names = {}
    for t in range(g["type_count"]):
        n = rom.text(tyb, struct.unpack_from("<H", data, rom.off(tyb, tya + 2 * t))[0])
        if (n == "Normal" and t != 0) or n in ("Bird", "???"):
            continue
        type_names[t] = n
    mb, ma = g["type_matrix"]
    chart = {}
    for atk in type_names:
        for dfn in type_names:
            v = (data[rom.off(mb, ma) + 7 * atk + dfn // 4] >> (2 * (dfn % 4))) & 3
            if v != 2:
                chart[atk * 100 + dfn] = (0, 50, 100, 200)[v]

    # Landmarks: the names, then each one's dot on its region's town map: grid cell (x, y) is the screen's 8px
    # cell (x + 1, y + 2) (09:656F places the dot there).
    lb, la = g["landmark_names"]
    landmarks = {}
    for i in range(g["landmark_count"]):
        landmarks[i] = rom.text(lb, struct.unpack_from("<H", data, rom.off(lb, la + 2 * i))[0])
    assert all(landmarks[k] == v for k, v in c["landmarks"].items()), "landmarks"
    rb, ra = g["region_grids"]
    placed = {}
    for r in range(g["region_count"]):
        grid = lz(data, rom.off(rb, struct.unpack_from("<H", data, rom.off(rb, ra + 2 * r))[0]))
        assert grid is not None and len(grid) == 18 * 15, f"region {r}'s grid"
        for cell, v in enumerate(grid):
            if v != 0xFF and v & 0x80:
                assert (v & 0x7F) not in placed, f"landmark {v & 0x7F} placed twice"
                placed[v & 0x7F] = (r, cell % 18 + 1, cell // 18 + 2)
    maps = {}
    for grp, num, lm in rom_maps(rom, g):
        assert lm < g["landmark_count"], f"map {grp}.{num}: landmark {lm}"
        maps[(grp, num)] = lm
    assert all(maps[k] == v for k, v in c["maps"].items()), "maps"

    db, da = g["dex_order"]
    dex_order = list(data[rom.off(db, da):][:g["dex_count"]])
    assert sorted(dex_order) == [s for s in range(1, count + 1) if names[s] != "Egg"], "dex order: each species once"
    assert dex_order[0] == 246 and dex_order[-1] == 254, "dex order: LARVITAR first, LIBABEEL last"

    def mixed(s):
        # Prism's text is mixed case: its "POKé" character prints "Poké" ("Poké Ball").
        return s.replace("POKé", "Poké")
    return dict(species={k: mixed(v) for k, v in names.items()}, types=types,
                moves={k: (mixed(n), ty, pw) for k, (n, ty, pw) in moves.items()},
                items={k: mixed(v) for k, v in items.items()}, type_chart=(type_names, chart),
                gen2_species=(genders, growth, tms), landmarks={i: (mixed(n), -1, -1) for i, n in landmarks.items()},
                placed=placed, stand_in={}, maps=maps, dex_order=dex_order)


def write_names_over(g, t, base_paths):
    """A translation ([g]'s names_over: the base game's code and data with its own text): everything but the names is
    checked equal to the base's, then only the names are written, keyed as the base's tables are."""
    if not base_paths:
        sys.exit(f"pass --base <{g['names_over']} rom.gbc>")
    b = read_game(GAMES[g["names_over"]], load_roms(GAMES[g["names_over"]], base_paths))
    assert t["types"] == b["types"], "species types"
    assert t["gen2_species"] == b["gen2_species"], "gender ratios, growth rates or TM ids"
    assert {k: v[1:] for k, v in t["moves"].items()} == {k: v[1:] for k, v in b["moves"].items()}, "moves' types / powers"
    assert t["type_chart"][1] == b["type_chart"][1], "type chart"
    assert set(t["type_chart"][0]) == set(b["type_chart"][0]), "type ids"
    assert {k: v[1:] for k, v in t["landmarks"].items()} == {k: v[1:] for k, v in b["landmarks"].items()}, "landmarks' places"
    assert t["maps"] == b["maps"], "maps"
    assert len(t["items"]) == len(b["items"]), "item count"
    sfx = g["suffix"]
    head = (f"// GENERATED by scripts/gen_gen2_tables.py from the {g['label']} ROM\n"
            f"// - do not hand-edit. Its names over {GAMES[g['names_over']]['label']}'s tables, by the same\n"
            f"// ids (Dex number, move id, item id, landmark id, type id); everything else is that game's (checked).\n")
    body = (kmap(f"speciesNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(t["species"].items())]) + "\n" +
            kmap(f"moveNames{sfx}", "String", [(k, kstr(v[0])) for k, v in sorted(t["moves"].items())]) + "\n" +
            kmap(f"itemNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(t["items"].items())]) + "\n" +
            kmap(f"landmarkNames{sfx}", "String", [(k, kstr(v[0])) for k, v in sorted(t["landmarks"].items())]) + "\n" +
            kmap(f"typeNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(t["type_chart"][0].items())]))
    write(f"Gen2Text{sfx}Gen.kt", head, body)
    print(f"{len(t['species'])} species, {len(t['moves'])} moves, {len(t['items'])} items, {len(t['landmarks'])} landmarks: "
          f"names over {g['names_over']}'s tables, the rest checked equal")


def main():
    args = sys.argv[1:]
    base_paths = []
    if "--base" in args:
        k = args.index("--base")
        base_paths = args[k + 1:]
        args = args[:k]
    if len(args) < 2 or args[0] not in GAMES:
        sys.exit(f"usage: {sys.argv[0]} <{'|'.join(GAMES)}> <rom.gbc> [<rom.gbc> ...] [--base <base game's rom.gbc> ...]")
    g = GAMES[args[0]]
    roms = load_roms(g, args[1:])
    t = read_game(g, roms)
    if "names_over" in g:
        return write_names_over(g, t, base_paths)
    shared = set(g.get("shares", ()))
    if shared:
        # A hack that kept its base game's tables reads them from the base's files: they're checked equal here.
        if not base_paths:
            sys.exit(f"{args[0]} shares {', '.join(sorted(shared))} with {g['base']}: pass --base <{g['base']} rom.gbc>")
        base = read_game(GAMES[g["base"]], load_roms(GAMES[g["base"]], base_paths))
        for what in shared:
            for key in {"places": ("landmarks", "maps")}.get(what, (what,)):
                assert t[key] == base[key], f"{key} isn't {g['base']}'s"
    sfx = g["suffix"]
    notes = g.get("notes", {})
    head = (f"// GENERATED by scripts/gen_gen2_tables.py from the {g['label']} ROM\n"
            f"// - do not hand-edit.")
    names, types, moves, items = t["species"], t["types"], t["moves"], t["items"]
    type_names, chart = t["type_chart"]
    genders, growth, tms = t["gen2_species"]
    landmarks, stand_in, maps = t["landmarks"], t["stand_in"], t["maps"]

    if "species" not in shared:
        write(f"SpeciesNames{sfx}.kt", head + notes.get("species", " PokemonNames, keyed by National Dex number.\n"),
              kmap(f"speciesNames{sfx}", "String", [(k, kstr(names[k])) for k in sorted(names)]))
    if "types" not in shared:
        write(f"SpeciesTypes{sfx}.kt", head + f" BaseData types, ids per typeNames{sfx}.\n",
              kmap(f"speciesTypeData{sfx}", "SpeciesTypes", [(k, f"SpeciesTypes({a}, {b})") for k, (a, b) in sorted(types.items())]))
    if "moves" not in shared:
        write(f"MoveData{sfx}.kt", head + " MoveNames + Moves (type, power).\n",
              kmap(f"moveData{sfx}", "MoveInfo", [(k, f"MoveInfo({kstr(n)}, {ty}, {p})") for k, (n, ty, p) in sorted(moves.items())]))
    if "items" not in shared:
        write(f"ItemNames{sfx}.kt", head + " ItemNames, TMs / HMs included.\n",
              kmap(f"itemNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(items.items())]))
    if "type_chart" not in shared:
        write(f"TypeChart{sfx}.kt", head + notes.get("types", " TypeNames (Gen 2 ids: 0-9 Normal..Steel,\n"
              "// 19 the ??? of CURSE, 20-27 Fire..Dark) and TypeMatchups: key = atkType*100+defType -> percent,\n"
              "// pairs not listed are 100.\n"),
              kmap(f"typeNames{sfx}", "String", [(k, kstr(v)) for k, v in sorted(type_names.items())]) + "\n" +
              kmap(f"typeEffectiveness{sfx}", "Int", sorted(chart.items())))

    placed = t.get("placed")

    def sec(i):
        name, x, y = landmarks[i]
        if placed is not None:
            # Prism: a landmark is on the region whose town map grid places its dot, else on no map.
            r, px, py = placed.get(i, (-1, 0, 0))
            return f"MapSecInfo({kstr(name)}, {r}, {px}, {py}, 1, 1)"
        if i in stand_in:
            v = stand_in[i]
            region = -1 if v >= g["fast_ship"] else (1 if v >= g["kanto_landmark"] else 0)
        else:
            region = -1 if i == 0 or i >= g["fast_ship"] else (1 if i >= g["kanto_landmark"] else 0)
        # The 8px cell under the icon's centre (Gen2Art draws the town maps at the screen's own pixels).
        return f"MapSecInfo({kstr(name)}, {region}, {max(0, x) // 8}, {max(0, y) // 8}, 1, 1)"
    if "places" not in shared:
        write(f"MapSecData{sfx}.kt", head + " Landmarks (the town map's places, what the\n"
              "// game names a map by) - " + notes.get("regions", "region 0 Johto, 1 Kanto") +
              " (the town maps Gen2Art rebuilds from the ROM),\n"
              "// -1 not on a map; x / y = the 8px cell under the place's icon. Then each map's landmark by\n"
              "// (group, number) from its header (map_header byte 5).\n",
              kmap(f"mapSecData{sfx}", "MapSecInfo", [(i, sec(i)) for i in sorted(landmarks)]) + "\n" +
              f"/** wMapGroup * 256 + wMapNumber -> landmark id ([mapSecData{sfx}]). */\n" +
              kmap(f"mapLandmarks{sfx}", "Int", [(f"{grp * 256 + num}", lm) for (grp, num), lm in sorted(maps.items())]))
    if "gen2_species" not in shared:
        write(f"Gen2Species{sfx}.kt", head + " BaseData's gender ratio (a mon is female when\n"
              "// (Attack DV << 4 | Speed DV) <= it: 0 all male, 254 all female, 255 genderless) and growth rate by\n" +
              notes.get("tms", "// Dex number; the TM01-50 / HM01-07 item ids, in the TM pocket's (wTMsHMs) order.\n"),
              f"val gen2GenderRatios{sfx} = intArrayOf(\n" + "".join(f"    {v},\n" for v in genders) + ")\n\n" +
              f"val gen2GrowthRates{sfx} = intArrayOf(\n" + "".join(f"    {v},\n" for v in growth) + ")\n\n" +
              f"val gen2TmItems{sfx} = intArrayOf(\n" + "".join(f"    {v},\n" for v in tms) + ")\n" +
              ("" if "dex_order" not in t else
               "\n/** The species ids in the game's Pokédex order: No.1 first (its own numbers, not the National Dex's). */\n"
               f"val gen2DexOrder{sfx} = intArrayOf(\n" + "".join(f"    {v},\n" for v in t["dex_order"]) + ")\n"))
    print(f"{len(names)} species, {len(moves)} moves, {len(items)} items, {len(type_names)} types, "
          f"{len(landmarks)} landmarks, {len(maps)} maps; checked against {', '.join(n for n, _ in roms)}" +
          (f"; {', '.join(sorted(shared))} are {g['base']}'s" if shared else ""))


if __name__ == "__main__":
    main()
