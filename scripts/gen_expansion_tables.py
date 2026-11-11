#!/usr/bin/env python3
"""Generates PokeDaisy's per-game display tables for a pokeemerald-expansion
ROM hack, straight from the ROM the user owns (nothing is downloaded).

    scripts/gen_expansion_tables.py <game-key> <rom.gba>

Writes app/src/main/kotlin/.../companion/data/{SpeciesNames,ItemNames,MoveData,
SpeciesTypes,TypeChart,MapSecData,GenderRatios}<Suffix>.kt (or a game's `only` few).

Every expansion release moves these tables and changes the struct sizes, so
each game gets its own GAMES entry with addresses found by hand (see the
comments there for how). Adding another expansion hack is a new entry, not new
code, as long as its tables have the same shape:
  - gSpeciesInfo: fixed-stride entries with the name inline; base stats,
    types and genderRatio at fixed offsets before the name.
  - gItemsInfo / gMovesInfo: fixed-stride entries starting with a name
    pointer (or, for items in some builds, the name inline). Move type/power
    are a u16 bitfield (type:5, category:2, power:9).
  - gTypeEffectiveness: [n][n] u32 uq4.12 multipliers (0x1000 = 1x).
  - gRegionMapEntries: 8-byte entries indexed by mapsec id, name pointer at
    +0 (newer) or +4 (older {x, y, w, h, name}); mapsec_count of them (256
    unless the hack has u16 mapsec ids).
The sha1 check stops a table being generated from the wrong release.
"""
import hashlib
import os
import struct
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin",
                       "com", "pokedaisy", "app", "companion", "data")

# Newer expansion type ids (include/constants/pokemon.h): 0 None, 10 Mystery,
# 20 Stellar are left out on purpose - no species/move uses them, and
# typeMatchups() iterates the name map's keys.
EXPANSION_TYPE_NAMES = {
    1: "Normal", 2: "Fighting", 3: "Flying", 4: "Poison", 5: "Ground", 6: "Rock", 7: "Bug", 8: "Ghost",
    9: "Steel", 11: "Fire", 12: "Water", 13: "Grass", 14: "Electric", 15: "Psychic", 16: "Ice",
    17: "Dragon", 18: "Dark", 19: "Fairy",
}

GAMES = {
    # Pokemon Heart and Soul v2.0.6. Found from the ROM + headless captures:
    #  - species: "BULBASAUR" at 0x087E667C, "IVYSAUR" 0x10C later and
    #    "CYNDAQUIL" exactly at species 155 -> 0x10C-byte entries. Base stats
    #    45/49/49/45/65/65 at name-0x2C, types (13 Grass, 4 Poison) at
    #    name-0x26, genderRatio 31 at name-0x1A. Gen 9 sits past the forms;
    #    disabled species are zeroed entries.
    #  - items: the only pointer to the "POKé BALL" string is item 1's name
    #    (0x0878F000); the next name pointer is 0x2C later. Item 28 = POTION
    #    matches the user's bag (2 Potions).
    #  - moves: the only pointer to a "TACKLE" string is move 33's name; BODY
    #    SLAM (34) is 0x44 later. +0xA u16: type = low 5 bits (Ember 11 Fire,
    #    Surf 12 Water, Earthquake 5 Ground), power = bits 7-15 (Tackle 40,
    #    Surf 90, Earthquake 100, Leer 0).
    #  - chart: the unique [21][21] u32 run whose Normal row is 1x except
    #    Rock/Steel 0.5x and Ghost 0x; the Fire row checks out too.
    #  - mapsecs: 8-byte {name ptr, x, y, w, h} entries; the live gMapHeader in New
    #    Bark Town reads mapsec 0x3F, and 0x08D553D8 + 0x3F*8 names it. (A
    #    second table at 0x08D55DF0 has the same names with other coords.)
    "hns": dict(
        suffix="Hns",
        label="Pokemon Heart and Soul v2.0.6",
        sha1="79ee6df0869c1773c8c6a5f764afc1f8d833d8bb",
        species_name1=0x087E667C, species_stride=0x10C, species_max=1600,
        species_types_off=-0x26, species_gender_off=-0x1A,
        items_base=0x0878EFD4, items_stride=0x2C, items_desc_off=-8,
        moves_base=0x087B652C - 33 * 0x44, moves_stride=0x44, move_bits_off=0x0A,
        chart=0x08475D1C, chart_n=21,
        # {x, y, w, h, name*} entries - the Johto + Kanto ("JK") map's: its Pokegear map is Emerald's
        # region_map.c with smol art and 7 maps (gRegionMapInfos 0x08D4D330, by RegionMapType: HOENN,
        # KANTO, SEVII x3, JOHTO, JK). FlagGet(0x8FF) picks JK over JOHTO (and with it this table and
        # grid, at 0x081F711E / 0x081F7C10); JK covers every Johto and Kanto section, so it's the one
        # drawn (RomArt's HNS_REGION_*, Emerald's palette). Its grid is u8 [15][28] at (1, 2),
        # MAPSEC_NONE 0x7D (the code's `cmp #125`); New Bark, Pallet, Saffron, Cycling Road and the
        # Power Plant land on their markers, 96 of the grid's 106 sections inside their rects.
        mapsec_base=0x08D553D4, mapsec_name_off=4,
        region_map=dict(image="hns", layout=0x08D56858, w=28, h=15, ox=1, oy=2, none=0x7D),
        checks=dict(species={1: "BULBASAUR", 155: "CYNDAQUIL"}, types={1: (13, 4)}, gender={1: 31},
                    items={1: "POKé BALL", 28: "POTION"}, moves={33: (1, 40), 57: (12, 90), 89: (5, 100)}),
    ),
    # Pokemon Lazarus v2.0 (splash: "PRET x RHH"). Same gSpeciesInfo field
    # offsets as HnS, 0xD4-byte entries: "Fennekin" -> "Braixen" spacing, and
    # "Pikachu" lands exactly at 25 (no Bulbasaur - the dex is regional, with
    # unused species zeroed). Items have INLINE names, 0x50 apart ("Great
    # Ball" 0x50 after "Poké Ball", "Potion" at 28 = the user's bag item).
    # Moves: name pointers, 0x34 stride ("Tackle" 33 -> "Body Slam" 34), same
    # +0xA type/power bitfield. Chart: [21][21] u32 like HnS. Mapsecs: the
    # older {x, y, w, h, name ptr} layout, name at +4; the live map header
    # reads 0x5A = "Acrisia City" (the save's town). Hoenn ids 0-85 are empty.
    # Emerald Seaglass v3.0: species by National Dex number in 0xD0-byte
    # gSpeciesInfo entries (POKEDEX_EMERALD_SEAGLASS's speciesInfo), name at
    # +0x2C ("Bulbasaur" in entry 1), types at name-0x26, genderRatio name-0x1A.
    # gMovesInfo: the only pointer to "Pound" is move 1's name, 0x38 apart
    # ("Tackle" at 33), the same +0xA type/power bitfield. gItemsInfo: 0x54-byte
    # entries, inline names ("Ultra Ball" 3, "Master Ball" 4 - expansion's order),
    # descriptions 8 bytes before each entry, like Lazarus. Only the tables it
    # lacked: its item names (ItemNamesSeaglass.kt), Emerald's mapsecs and Heart
    # and Soul's type chart were already right.
    "seaglass": dict(
        suffix="Seaglass",
        label="Pokemon Emerald Seaglass v3.0",
        sha1="b9f4d332d30fc88c379f9e037f9eae3b2755ead4",
        only={"SpeciesTypes", "GenderRatios", "MoveData"},
        species_name1=0x088F0780 + 0xD0 + 0x2C, species_stride=0xD0, species_max=1489,
        species_types_off=-0x26, species_gender_off=-0x1A,
        items_base=0x0867E77C, items_stride=0x54, items_name_inline=True, items_desc_off=-8,
        moves_base=0x086D2A18 - 0x38, moves_stride=0x38, move_bits_off=0x0A,
        checks=dict(species={25: "Pikachu", 255: "Torchic", 258: "Mudkip"}, types={25: (14, 14), 255: (11, 11)},
                    gender={25: 127, 255: 127}, items={4: "Master Ball", 28: "Potion"},  # Torchic 50/50 here
                    moves={10: (1, 40), 33: (1, 40), 52: (11, 40), 57: (12, 90)}),
    ),
    "lazarus": dict(
        suffix="Lazarus",
        label="Pokemon Lazarus v2.0",
        sha1="7dcdc7e280bc4631487e13dd37e6e0cea04adea6",
        species_name1=0x08C7A438, species_stride=0xD4, species_max=1600,
        species_types_off=-0x26, species_gender_off=-0x1A,
        items_base=0x088685AC - 0x50, items_stride=0x50, items_name_inline=True, items_desc_off=-8,
        moves_base=0x088D017C - 33 * 0x34, moves_stride=0x34, move_bits_off=0x0A,
        chart=0x08571B78, chart_n=21,
        mapsec_base=0x08CE4100 - 4 - 0x5A * 8, mapsec_name_off=4,
        # Its own region map, in Emerald's region_map.c (gcc build): the 8bpp
        # tiles / tilemap / palette are RomArt's LZ_REGION_* (regionmap/lazarus.png),
        # sRegionMap_MapSectionLayout [15][28] sits right before the entries' names
        # (the literal pool next to the tiles + tilemap points at it too).
        region_map=dict(image="lazarus", layout=0x08CE35C8, w=28, h=15, ox=1, oy=2, none=0xD5),
        checks=dict(species={25: "Pikachu", 653: "Fennekin"}, types={25: (14, 14), 653: (11, 11)},
                    gender={25: 127}, items={1: "Poké Ball", 28: "Potion"},
                    moves={33: (1, 40), 57: (12, 90), 89: (5, 100)}),
    ),
    # Pokemon SoulGold v1.1.4 (a Johto remake on pokeemerald-expansion, gcc
    # build, no source). Found from the ROM alone:
    #  - species: "Bulbasaur" at 0x087D45F3, "Chikorita" / "Cyndaquil" exactly
    #    151 / 154 entries of 0x118 later, "Pikachu" at 25 -> National Dex ids,
    #    disabled species zeroed, forms + Gen 9 past 1000, the table ends at 1578
    #    (zeroed; dex text follows). Base stats 45/49/49/45/65/65 at name-0x33,
    #    types (13 Grass, 4 Poison) at name-0x2D, genderRatio 31 at name-0x21;
    #    names are 13-byte fields (12 chars + EOS: "Bulbasaur" is followed by
    #    3 zero bytes, then cryId / natDexNum 1), title case.
    #  - items: the only pointer to "Poké Ball" is item 1's name (0x087520CC),
    #    "Great Ball" 0x2C later, "Potion" at 28. Like HnS the description
    #    pointer is 8 bytes before the name pointer (the one 0x24 after it is
    #    the NEXT item's: it reads Super Potion's "60 points" for Potion).
    #  - moves: the only pointer to a "Pound" string is move 1's name, "Tackle"
    #    32 entries of 0x48 later (33), "Body Slam" right after. Same +0xA
    #    type/power bitfield (Tackle 1/40, Ember 11/40, Surf 12/90, Earthquake
    #    5/100, Moonblast 19/95).
    #  - chart: the unique [21][21] u32 run whose Normal row is 1x except
    #    Rock/Steel 0.5x and Ghost 0x, with Fire 2x on Grass/Ice/Bug/Steel.
    #  - mapsecs: older {x, y, w, h, name ptr} entries from 0x08F3782C (Hoenn
    #    0-0x49, Kanto/Sevii 0x4A-0xC3, then Johto: Violet 0xC4, Azalea 0xC5,
    #    Goldenrod 0xC6, Ecruteak 0xC7, Olivine 0xC8, Cianwood 0xC9, Mahogany 0xCB,
    #    Blackthorn 0xCC, Cherrygrove 0xCD, Routes 26-48 0xCF-0xE5, New Bark 0xE8,
    #    the hack's own areas from 0xFF up). 314 of them: mapsec ids are u16 here
    #    (MAPSEC_NONE = 0x13A; map headers hold a u16 at +0x14 - New Bark's 0xE8,
    #    then cave/weather/mapType shifted one byte - and use ids up to 0x139).
    #  - region map: Emerald's region_map.c with its own Johto art, but no
    #    region_map= yet: LoadRegionMapGfx's pool (0x082251FC..) holds the 8bpp
    #    tiles 0x08F39DA0 (smol, mode 5), the 64x64 affine tilemap 0x08F39AC4
    #    (smol tilemap, mode 8 - RomArt can't decode either), the 3-palette
    #    sRegionMapBg_Pal 0x08F37148 (raw, BG palette 7); the cursor grid is u16
    #    [15][28] (0x08F3967C, a second layer at 0x08F39334, MAPSEC_NONE filler),
    #    which RegionLayout's byte grid can't hold. Rendered with the game's own
    #    decompressor (headless), the entries' rects + (1, 2) land on its towns.
    "soulgold": dict(
        suffix="SoulGold",
        label="Pokemon SoulGold v1.1.4",
        sha1="ea5d369cc8a31cbf1cfacb7c9470ea670f08957b",
        species_name1=0x087D45F3, species_stride=0x118, species_max=1579,
        species_types_off=-0x2D, species_gender_off=-0x21,
        items_base=0x087520CC - 0x2C, items_stride=0x2C, items_desc_off=-8,
        moves_base=0x087767C0 - 0x48, moves_stride=0x48, move_bits_off=0x0A,
        chart=0x0843DBB4, chart_n=21,
        mapsec_base=0x08F3782C, mapsec_name_off=4, mapsec_count=0x13A,
        # Its Johto map is Emerald's region_map.c too, with smol art: the tiles /
        # tilemap / palette are RomArt's SG_REGION_* (regionmap/soulgold.png). The
        # cursor grid is u16 (ids past 255): sRegionMap_MapSectionLayout at
        # 0x08F3967C [15][28] and a second layer just before it (0x08F39334: a
        # few sections on top of the main one, like FireRed's dungeon layer).
        # Checked: New Bark (0xE8), Cherrygrove (0xCD), Goldenrod (0xC6, 1x2)
        # sit on the grid exactly where their entries' {x, y, w, h} say. Its map
        # art sits a row higher than Emerald's: the grid overlaid at (1, 1) covers
        # every route and town square (at Emerald's (1, 2) everything was a row low).
        # v1.2 (sha1 805d880e...) has these same tables, byte for byte but TM75 (hand-patched in
        # ActiveTables: soulGoldV12), at species_name1 0x087D5E23 / stride 0x120, items 0x08753834,
        # moves 0x08777EFC, chart 0x0843E6E4, mapsecs 0x08F42978, grid 0x08F447C8 / 0x08F44480.
        # A second v1.2 build (sha1 5d6a0362...) generates the same files again: species / items /
        # moves / chart 0x98 bytes earlier, mapsecs and the grid 0xA4.
        region_map=dict(image="soulgold", layout=0x08F3967C, extra_layers=[0x08F39334], cell=2,
                        w=28, h=15, ox=1, oy=1, none=0x13A),
        checks=dict(species={1: "Bulbasaur", 25: "Pikachu", 152: "Chikorita", 155: "Cyndaquil"},
                    types={1: (13, 4), 25: (14, 14), 152: (13, 13), 155: (11, 11)},
                    gender={1: 31, 25: 127, 155: 31}, items={1: "Poké Ball", 2: "Great Ball", 28: "Potion"},
                    moves={1: (1, 40), 33: (1, 40), 52: (11, 40), 57: (12, 90), 89: (5, 100), 585: (19, 95)}),
    ),
    # Pokemon Emerald Imperium v1.3.1 (splash "PRET x RHH"; the ROM header's RHHEXP block at 0x080001FC
    # says expansion 1.10.0, 0x600 species, 0x3EA items). Its GF header (0x08000100) points at the tables:
    # gSpeciesInfo 0x08D5D9D8 (+0x1BC), gItemsInfo 0x086C7A64 (+0x1C8), gMovesInfo 0x08704A74 (+0x1CC),
    # gMonIconPaletteTable 0x08DC0350 (+0x140). Checked against the data:
    #  - species: 0x104-byte entries, National Dex ids: "Bulbasaur" at 0x08D5DB08 (entry +0x2C), "Ivysaur"
    #    0x104 later, "Charmander" (the save's) at 4, "Pikachu" at 25, "Torchic" at 255. Base stats
    #    45/49/49/45/65/65 at name-0x2C, types (13 Grass, 4 Poison) name-0x26, genderRatio 31 name-0x1A,
    #    the same offsets as HnS / Lazarus. 1..1535 named; 1536 (= the header's species count) is the
    #    egg's entry.
    #  - items: inline names 0x14 into 0x50-byte entries ("Poké Ball" 0x086C7AC8, "Great Ball" 0x50
    #    later, "Potion" at 28 = the save's bag item); the description pointer 8 bytes before the name.
    #  - moves: the only pointer to "Pound" is move 1's name (0x08704AA8), 0x34 apart ("Scratch" 10,
    #    "Tackle" 33, "False Swipe" 206 - the save's Charmander knows 10/45/206); +0xA type/power bits.
    #  - chart: the unique [21][21] u32 run whose Normal row is 1x except Rock/Steel 0.5x, Ghost 0x
    #    (0x084EA6DC); Fire's row and Fairy (Dragon -> Fairy 0x) check out.
    #  - mapsecs: older {x, y, w, h, name ptr} entries from 0x08DDA3E4; 0x00-0xD4 are vanilla Emerald's,
    #    names and rects byte for byte, then 0xD5-0xD9 are new (FLOATING SLAB, ROCKY SLAB, GRASSY SLAB,
    #    HOT HOUSE, TRICK HOUSE) and MAPSEC_NONE is 0xDA. The save's gMapHeader reads 0x10 = ROUTE 101.
    #  - region map: Emerald's region_map.c; its grid (sRegionMap_MapSectionLayout, 0x08DD975C) is
    #    vanilla's with MAPSEC_NONE 0xDA; palette (0x08DD848C) vanilla's; tiles (LZ 0x08DD84CC, 15360
    #    bytes: vanilla's 233 + 7 new) and tilemap (LZ 0x08DD92D0, 4 cells changed) add markers for the
    #    new places, so Emerald's EM_REGION_GFX / _MAP fingerprints don't match: RomArt's IMP_REGION_*
    #    rebuild regionmap/imperium.png (Emerald's palette). Its rects are Emerald's, + (1, 2).
    "imperium": dict(
        suffix="Imperium",
        label="Pokemon Emerald Imperium v1.3.1",
        sha1="1d20091c4d936f5eb122db8780554dd0829ffb63",
        species_name1=0x08D5DB08, species_stride=0x104, species_max=1536,
        species_types_off=-0x26, species_gender_off=-0x1A,
        items_base=0x086C7A64 + 0x14, items_stride=0x50, items_name_inline=True, items_desc_off=-8,
        moves_base=0x08704A74, moves_stride=0x34, move_bits_off=0x0A,
        chart=0x084EA6DC, chart_n=21,
        mapsec_base=0x08DDA3E4, mapsec_name_off=4,
        region_map=dict(image="imperium", layout=0x08DD975C, w=28, h=15, ox=1, oy=2, none=0xDA),
        checks=dict(species={1: "Bulbasaur", 4: "Charmander", 25: "Pikachu", 255: "Torchic"},
                    types={1: (13, 4), 4: (11, 11), 25: (14, 14)},
                    gender={1: 31, 4: 31, 25: 127}, items={1: "Poké Ball", 2: "Great Ball", 28: "Potion"},
                    moves={1: (1, 40), 10: (1, 40), 33: (1, 40), 52: (11, 40), 57: (12, 90), 89: (5, 100), 585: (19, 95)}),
    ),
    # Pokemon Wishes of Tomorrow v1.2.6 (FlatFoot Labs; BPEE, 32 MB), pokeemerald-expansion 1.15.3 (its RHHEXP
    # block: 848 moves, 1581 species, 924 items, 20-letter item names), gcc, no source. Its GF ROM header gives
    # gSpeciesInfo 0x087011C0, gItemsInfo 0x086AF774 and gMovesInfo 0x086D00FC; checked against the data:
    #  - species: 0x104-byte entries, National Dex ids: "Bulbasaur" at 0x087012F0 (entry +0x2C), "Ivysaur"
    #    0x104 later; base stats 45/49/49/45/65/65 at name-0x2C, types (13 Grass, 4 Poison) name-0x26, genderRatio
    #    31 name-0x1A - the same offsets as Imperium's 1.10. Its headless save's party (Cyndaquil, Pikachu,
    #    Charizard, Gengar, Lucario, a Togepi egg) reads these names at those ids.
    #  - items: 0x2C-byte entries with a name POINTER at +0x14 ("Poké Ball" at 1, "Potion" at 28 = the bag's;
    #    Snag Ball 918), the description pointer 8 bytes before the name's.
    #  - moves: 0x44-byte entries, the name pointer first ("Pound" 1, "Karate Chop" 2); +0xA type/power bits.
    #  - chart: the unique [21][21] u32 run whose Normal row is 1x except Rock/Steel 0.5x, Ghost 0x (0x083DB990).
    #  - mapsecs: older {x, y, w, h, name ptr} entries from 0x08CE114C (0x00-0xE4, MAPSEC_NONE 0xE5); the save's gMapHeader
    #    in its first town reads 0xD1 = MUNEN VILLAGE. Emerald's / Kanto's ids keep their vanilla rects; its own JOKAIM
    #    sections are 0xD1-0xE4, with a few Kanto ids renamed for its places (SENNEN 0x66, TORII ROUTE 0x67).
    #  - region map: Emerald's kind of screen (an 8bpp affine BG, a 64x64 one-byte tilemap, its palette at BG palette 7),
    #    smol art: RomArt's WSH_REGION_* rebuild regionmap/wishes.png. Its map code keeps six regions (a table of
    #    tilemap / tiles / palette at 0x08CDC868, one u8 [15][28] cursor grid each from 0x08CE24CC: FireRed-style Kanto and
    #    Sevii maps among them), but the TOWN MAP shows JOKAIM for every place it has (headless: MUNEN VILLAGE and RYUDEN
    #    ISLAND, which isn't on JOKAIM's grid, both on JOKAIM with the player's head at the entry + (1, 2) tiles). JOKAIM's
    #    grid is 0x08CE2D00.
    "wishes": dict(
        suffix="Wishes",
        label="Pokemon Wishes of Tomorrow v1.2.6",
        sha1="60182b934d2e8eb3eef226a9bbc25e5df6becd4f",
        species_name1=0x087012F0, species_stride=0x104, species_max=1581,
        species_types_off=-0x26, species_gender_off=-0x1A, species_growth_off=-0x17,
        items_base=0x086AF774 + 0x14, items_stride=0x2C, items_desc_off=-8,
        moves_base=0x086D00FC, moves_stride=0x44, move_bits_off=0x0A,
        chart=0x083DB990, chart_n=21,
        mapsec_base=0x08CE114C, mapsec_name_off=4,
        # Below 0xD1 (its first own section) only the grid's are placed: Emerald's / Kanto's leftovers keep their rects.
        region_map=dict(image="wishes", layout=0x08CE2D00, w=28, h=15, ox=1, oy=2, none=0xE5, place_min=0xD1),
        checks=dict(species={1: "Bulbasaur", 6: "Charizard", 25: "Pikachu", 94: "Gengar", 155: "Cyndaquil",
                             448: "Lucario"},
                    types={1: (13, 4), 6: (11, 3), 25: (14, 14), 155: (11, 11)},
                    gender={1: 31, 25: 127}, growth={1: 3, 25: 0, 155: 3, 448: 3},
                    items={1: "Poké Ball", 2: "Great Ball", 28: "Potion", 918: "Snag Ball"},
                    moves={1: (1, 40), 2: (2, 50), 33: (1, 40), 52: (11, 40), 57: (12, 90), 89: (5, 100), 585: (19, 95)}),
    ),
    # Pokemon Dreamstone Mysteries (dsmyst; no version shown anywhere in the game). Its debug menu's EXPANSION VERSION
    # prints "v1.11.2 Development Build" (BufferExpansionVersion, 0x080F9F31), and there is no RHHEXP header block.
    # Found from the ROM + a new game played headless:
    #  - species: gSpeciesInfo 0x087B0160, 0x104-byte entries, Imperium's field offsets ("Bulbasaur" at +0x2C of
    #    entry 1, 45/49/49/45/65/65 at name-0x2C, types (13, 4) name-0x26, genderRatio 31 name-0x1A). National Dex ids
    #    to 905, then forms with Gen 9 among them (SPRIGATITO 1289), the table ends at 1524 (the egg, unnamed).
    #  - items: gItemsInfo 0x0872F6B8, 0x50-byte entries with the name inline at +0x14 ("Poké Ball" 1, "Potion" 28 =
    #    the save's bag), the description pointer 8 bytes before it, the pocket byte at +0x41 (1 Items, 2 Medicine,
    #    3 Battle Items, 4 Balls, 5 Berries, 6 TMs, 7 Key Items).
    #  - moves: gMovesInfo 0x08759858, 0x30 apart (the only pointer to "Pound" is move 1's name), +0xA bits as before.
    #  - chart: the unique [21][21] u32 run whose Normal row is 1x but Rock / Steel 0.5x, Ghost 0x (0x08531EC0).
    #  - mapsecs: {x, y, w, h, name ptr} from 0x08E82D24: Emerald's 0x00-0xD4, then its own Cormoria 0xD5-0xFB
    #    (CARABRUE TOWN 0xD5 = the save's gMapHeader); 0xFC (MAPSEC_NONE) has no entry.
    #  - region map: FireRed's kind of screen (an 8bpp text BG, its 4bpp twin, a 32x20 u16 tilemap, BG palette 0;
    #    RomArt's DSM_REGION_* rebuild regionmap/dreamstone.png); the cursor grid is u8 [15][28] right after the names
    #    (0x08E84144, MAPSEC_NONE 0xFC). In game (the TOWN MAP, headless) CARABRUE TOWN's marker sits at grid
    #    (0x12, 8) + (1, 2) tiles, where its entry says.
    "dreamstone": dict(
        suffix="Dreamstone",
        label="Pokemon Dreamstone Mysteries",
        sha1="20e91aecb3243eea7cd610659846f37062f5d709",
        species_name1=0x087B0290, species_stride=0x104, species_max=1525,
        species_types_off=-0x26, species_gender_off=-0x1A, species_growth_off=-0x17,
        # The table ends at 855 (ARCHAEOLENS 2.0): its item descriptions follow, which would read as names.
        items_base=0x0872F6B8 + 0x14, items_stride=0x50, items_name_inline=True, items_desc_off=-8, items_max=856,
        moves_base=0x08759858, moves_stride=0x30, move_bits_off=0x0A,
        chart=0x08531EC0, chart_n=21,
        mapsec_base=0x08E82D24, mapsec_name_off=4,
        # Cormoria reuses a few of Emerald's / Kanto's ids (IVY RIVER 0x8F, VICTORY ROAD 0x94, ...); the Hoenn sections
        # it doesn't use keep Emerald's rects, so below 0xD5 only the grid's sections are placed.
        region_map=dict(image="dreamstone", layout=0x08E84144, w=28, h=15, ox=1, oy=2, none=0xFC, place_min=0xD5),
        checks=dict(species={1: "Bulbasaur", 25: "Pikachu", 255: "Torchic", 501: "Oshawott", 1289: "Sprigatito"},
                    types={1: (13, 4), 25: (14, 14), 255: (11, 11), 501: (12, 12)},
                    gender={1: 31, 25: 127}, items={1: "Poké Ball", 2: "Great Ball", 28: "Potion", 829: "Lab Package"},
                    moves={1: (1, 40), 33: (1, 40), 52: (11, 40), 57: (12, 90), 89: (5, 100), 585: (19, 95)}),
    ),
    # Pokemon Team Rocket Edition 2026 - Dragonsden, Spanish (its START menu says v3.0.9; BPEE, 32 MB, DragonsdenTV's own
    # build, the text Spanish): pokeemerald-expansion 1.14.4 (its RHHEXP block: 848 moves, 1698 species), gcc, no source.
    # A rebuild on a newer expansion than v1.5's (gen_team_rocket_tables.py): its tables are these, not that script's. Its GF
    # ROM header gives gSpeciesInfo 0x08970CE8, gItemsInfo 0x088CCC1C and gMovesInfo 0x08934E7C; checked against the data:
    #  - species: 0xA8-byte entries (its build leaves out the female pictures and footprints), National Dex ids:
    #    "Bulbasaur" at +0x2C of entry 1 (46/49/49/45/65/65 at name-0x2C: its own HP), types (13 Grass, 4 Poison)
    #    name-0x26, genderRatio 31 name-0x1A, growthRate name-0x17 - Wishes' offsets. Forms and its own Pokemon past
    #    1025 (1669 is a ZUBAT: the save's starter), 1698 (the header's species count) is the egg's entry.
    #  - items: 0x2C-byte entries with a name POINTER at +0x14 ("Poke Ball" 1, "Perla" / "Perla Grande" 148 / 149 = the
    #    save's bag), the description pointer 8 bytes before the name's.
    #  - moves: 0x3C-byte entries, the name pointer first ("Destructor" 1, "Golpe Karate" 2, "Placaje" 33); +0xA bits.
    #  - chart: the unique [21][21] u32 run whose Normal row is 1x except Rock/Steel 0.5x, Ghost 0x (0x084BE1B0).
    #  - mapsecs: older {x, y, w, h, name ptr} entries from 0x08E6A944 (the literal pools' gRegionMapEntries), u16 ids
    #    (0x1EF of them): its own places first (VILLA RAIZ 0), then Emerald's / Kanto's / Johto's 34 later than
    #    vanilla's (PUEBLO PALETA 0x7A). The save's gMapHeader reads 0x10A = ALMACEN ROCKET. No region map picture.
    "team_rocket_v3": dict(
        suffix="TeamRocketV3",
        label="Pokemon Team Rocket Edition 2026 (Spanish, v3.0.9)",
        sha1="0d8bdaeeca0856175037a75679c1a2b73ad2b68a",
        spanish=True,
        potion_desc="20 PS",
        species_name1=0x08970DBC, species_stride=0xA8, species_max=1698,
        species_types_off=-0x26, species_gender_off=-0x1A, species_growth_off=-0x17,
        items_base=0x088CCC1C + 0x14, items_stride=0x2C, items_desc_off=-8,
        moves_base=0x08934E7C, moves_stride=0x3C, move_bits_off=0x0A,
        chart=0x084BE1B0, chart_n=21,
        mapsec_base=0x08E6A944, mapsec_name_off=4, mapsec_count=0x1EF,
        # Its seven region maps (RomArt's TR3_*: gen_rom_art_sigs.py says where each comes from): 0x08192D44 picks the
        # map by the section's id, 0x08192B80 reads that map's cursor grid, u16 [15][28] at (1, 2), MAPSEC_NONE 0x1EF.
        # Every section a grid names is in its map's range; their {x, y, w, h} land on their grids' cells.
        region_maps=[dict(image=img, layout=grid, ids=ids, w=28, h=15, ox=1, oy=2, none=0x1EF, cell=2) for img, grid, ids in (
            ("tr3_kanto", 0x08E6DDD4, (0x7A, 0xE6)), ("tr3_sevii123", 0x08E6D744, (0xE7, 0xFD)),
            ("tr3_sevii45", 0x08E6D3FC, (0xFE, 0x116)), ("tr3_sevii67", 0x08E6D0B4, (0x117, 0x13C)),
            ("tr3_johto", 0x08E6DA8C, (0x13D, 0x190)), ("tr3_hoenn", 0x08E6E11C, (0x00, 0x79)),
            ("tr3_sinnoh", 0x08E6CD6C, (0x191, 0x1EE)))],
        checks=dict(species={1: "Bulbasaur", 23: "Ekans", 25: "Pikachu", 41: "Zubat", 169: "Crobat", 1669: "Zubat"},
                    types={1: (13, 4), 23: (4, 4), 41: (4, 3), 169: (4, 3)},
                    gender={1: 31, 25: 127}, growth={1: 3, 41: 0},
                    items={1: "Poké Ball", 2: "Super Ball", 28: "Poción", 148: "Perla", 149: "Perla Grande"},
                    moves={1: (1, 40), 33: (1, 40), 44: (18, 60), 52: (11, 40), 57: (12, 90), 89: (5, 100),
                           585: (19, 95)}),
    ),
    # Team Rocket Edition 2026, Spanish v3.1 (its START menu says V.3.1): v3.0.9's RAM word for word (gPlayerParty's 552
    # literal-pool words and the rest), its ROM tables moved: gSpeciesInfo and the icon palettes +0x4BD8 (its GF header's
    # 0x080001BC / 0x08000140), gMovesInfo and gItemsInfo +0x4BD0 (the header's 0x080001CC / 0x080001C8),
    # the chart +0x12C8 (its Normal row's run), gRegionMapEntries and the region grids +0x4F20 (495 entries of
    # {x, y, w, h, name*}). Every table comes out equal to v3.0.9's, so it runs on those (`same_as`: checked, not written).
    "team_rocket_v3_1": dict(
        suffix="TeamRocketV3",
        same_as="team_rocket_v3",
        label="Pokemon Team Rocket Edition 2026 (Spanish, v3.1)",
        sha1="32d9e438ab791a0477fffe9f1cca750cab5a4a80",
        spanish=True,
        potion_desc="20 PS",
        species_name1=0x08975994, species_stride=0xA8, species_max=1698,
        species_types_off=-0x26, species_gender_off=-0x1A, species_growth_off=-0x17,
        items_base=0x088D17EC + 0x14, items_stride=0x2C, items_desc_off=-8,
        moves_base=0x08939A4C, moves_stride=0x3C, move_bits_off=0x0A,
        chart=0x084BF478, chart_n=21,
        mapsec_base=0x08E6F864, mapsec_name_off=4, mapsec_count=0x1EF,
        region_maps=[dict(image=img, layout=grid, ids=ids, w=28, h=15, ox=1, oy=2, none=0x1EF, cell=2) for img, grid, ids in (
            ("tr3_kanto", 0x08E72CF4, (0x7A, 0xE6)), ("tr3_sevii123", 0x08E72664, (0xE7, 0xFD)),
            ("tr3_sevii45", 0x08E7231C, (0xFE, 0x116)), ("tr3_sevii67", 0x08E71FD4, (0x117, 0x13C)),
            ("tr3_johto", 0x08E729AC, (0x13D, 0x190)), ("tr3_hoenn", 0x08E7303C, (0x00, 0x79)),
            ("tr3_sinnoh", 0x08E71C8C, (0x191, 0x1EE)))],
        checks=dict(species={1: "Bulbasaur", 23: "Ekans", 25: "Pikachu", 41: "Zubat", 169: "Crobat", 1669: "Zubat"},
                    types={1: (13, 4), 23: (4, 4), 41: (4, 3), 169: (4, 3)},
                    gender={1: 31, 25: 127}, growth={1: 3, 41: 0},
                    items={1: "Poké Ball", 2: "Super Ball", 28: "Poción", 148: "Perla", 149: "Perla Grande"},
                    moves={1: (1, 40), 33: (1, 40), 44: (18, 60), 52: (11, 40), 57: (12, 90), 89: (5, 100),
                           585: (19, 95)}),
    ),
}

# The Western charmap's accented letters and Spanish punctuation, for a game whose text is Spanish (`spanish=True`).
SPANISH_CHARS = {0x01: "À", 0x02: "Á", 0x04: "Ç", 0x05: "È", 0x06: "É", 0x09: "Ì", 0x0D: "Ò", 0x0E: "Ó", 0x11: "Ù",
                 0x12: "Ú", 0x14: "Ñ", 0x16: "à", 0x17: "á", 0x19: "ç", 0x1A: "è", 0x1E: "ì", 0x22: "ò", 0x23: "ó",
                 0x26: "ù", 0x27: "ú", 0x29: "ñ", 0x2A: "º", 0x2B: "ª", 0x51: "¿", 0x52: "¡", 0x5A: "Í", 0x6F: "í",
                 0xF1: "Ä", 0xF2: "Ö", 0xF3: "Ü", 0xF4: "ä", 0xF5: "ö", 0xF6: "ü"}

CHARS = {0x00: " ", 0x1B: "é", 0xAB: "!", 0xAC: "?", 0xAD: ".", 0xAE: "-", 0xB0: "…", 0xB1: "“", 0xB2: "”",
         0xB3: "‘", 0xB4: "'", 0xB5: "♂", 0xB6: "♀", 0xB8: ",", 0xBA: "/", 0xF0: ":", 0x2D: "&", 0x5C: "(",
         0x5D: ")", 0x5B: "%", 0x35: "=", 0x36: ";", 0x53: "PK", 0x54: "MN"}
# Line / paragraph breaks: descriptions are wrapped for the game's box, the app rewraps.
CHARS.update({0xFA: " ", 0xFB: " ", 0xFE: " "})
CHARS.update({0xA1 + i: str(i) for i in range(10)})
CHARS.update({0xBB + i: chr(65 + i) for i in range(26)})
CHARS.update({0xD5 + i: chr(97 + i) for i in range(26)})


class Rom:
    def __init__(self, data):
        self.d = data

    def off(self, a):
        return a - 0x08000000

    def u8(self, a):
        return self.d[self.off(a)]

    def u16(self, a):
        return struct.unpack_from("<H", self.d, self.off(a))[0]

    def u32(self, a):
        return struct.unpack_from("<I", self.d, self.off(a))[0]

    def is_ptr(self, v):
        return 0x08000000 <= v < 0x08000000 + len(self.d)

    def text(self, a, maxlen=40):
        """Gen 3 string at a, or None if it isn't clean text."""
        out = []
        for b in self.d[self.off(a):self.off(a) + maxlen]:
            if b == 0xFF:
                s = " ".join("".join(out).split())
                return s if s else None
            if b not in CHARS:
                return None
            out.append(CHARS[b])
        return None


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def title(s):
    words = []
    for w in s.split(" "):
        # "S.S." / "MT." / "DIGLETT'S" -> "S.S." / "Mt." / "Diglett's"
        words.append(w if w.count(".") >= 2 else w[:1] + w[1:].lower())
    return " ".join(words)


def header(g, what):
    return (f"package com.pokedaisy.app.companion.data\n\n"
            f"// GENERATED by scripts/gen_expansion_tables.py from the {g['label']} ROM\n"
            f"// (sha1 {g['sha1'][:8]}...) - do not hand-edit. {what}\n")


# A game's `only` set: the tables (file names without the suffix) to write; None = all.
ONLY = None


# A game's `same_as`: another build whose generated files this one's must equal (header lines aside); nothing is written.
SAME_AS = None
PENDING = {}


def check_same():
    def tail(text):
        return text.split(" - do not hand-edit.", 1)[1].split("\n", 1)[1]
    for path, body in PENDING.items():
        with open(path) as f:
            assert tail(f.read()) == tail(body), f"{os.path.basename(path)} differs from {SAME_AS}'s"
        print("same as", SAME_AS + ":", os.path.relpath(path))


def write(name, body):
    if ONLY is not None and not any(name.startswith(t) for t in ONLY):
        return
    path = os.path.join(OUT_DIR, name)
    if SAME_AS is not None:
        PENDING[path] = body   # the last write of a file counts (check_same, at the end)
        return
    with open(path, "w") as f:
        f.write(body)
    print("wrote", os.path.relpath(path))


def species(rom, g):
    names, types, gender, growth = {}, {}, {}, {}
    for i in range(1, g["species_max"]):
        a = g["species_name1"] + (i - 1) * g["species_stride"]
        if rom.off(a) + g["species_stride"] > len(rom.d):
            break
        n = rom.text(a, 13)
        if not n or n.startswith("?"):
            continue
        names[i] = n
        types[i] = (rom.u8(a + g["species_types_off"]), rom.u8(a + g["species_types_off"] + 1))
        gender[i] = rom.u8(a + g["species_gender_off"])
        if "species_growth_off" in g:
            growth[i] = rom.u8(a + g["species_growth_off"])
    c = g["checks"]
    assert all(names.get(i) == n for i, n in c["species"].items()), "species table address is wrong"
    assert all(types.get(i) == t for i, t in c["types"].items()), "species type offset is wrong"
    assert all(gender.get(i) == r for i, r in c["gender"].items()), "species gender offset is wrong"
    assert all(growth.get(i) == r for i, r in c.get("growth", {}).items()), "species growth rate offset is wrong"
    return names, types, gender, growth


def named_table(rom, base, stride, inline=False, limit=4000):
    """Entries 1.. of a fixed-stride table whose name is a pointer at +0, or
    (inline) the entry itself starting with the name."""
    out = {}
    miss = 0
    for i in range(1, limit):
        a = base + i * stride
        if rom.off(a) + stride > len(rom.d):
            break
        if inline:
            n = rom.text(a, 21)
        else:
            p = rom.u32(a)
            n = rom.text(p) if rom.is_ptr(p) else None
        if n is None:
            miss += 1
            if miss > 8:
                break
            continue
        miss = 0
        out[i] = n
    return out


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in GAMES:
        sys.exit(f"usage: {sys.argv[0]} <{'|'.join(GAMES)}> <rom.gba>")
    g = GAMES[sys.argv[1]]
    data = open(sys.argv[2], "rb").read()
    sha1 = hashlib.sha1(data).hexdigest()
    if sha1 != g["sha1"]:
        sys.exit(f"sha1 {sha1} is not {g['label']} ({g['sha1']})")
    rom = Rom(data)
    if g.get("spanish"):
        CHARS.update(SPANISH_CHARS)
    global ONLY, SAME_AS
    ONLY = g.get("only")
    SAME_AS = g.get("same_as")
    sfx = g["suffix"]
    low = sfx[0].lower() + sfx[1:]

    names, types, gender, growth = species(rom, g)
    write(f"SpeciesNames{sfx}.kt", header(g, "gSpeciesInfo inline names, keyed by the\n"
          "// game's own species ids (National Dex order, then forms/customs).\n") +
          f"val speciesNames{sfx}: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(names.items())) + ")\n")
    write(f"SpeciesTypes{sfx}.kt", header(g, "gSpeciesInfo types[2], ids per typeNames" + sfx + ".\n") +
          f"val speciesTypeData{sfx}: Map<Int, SpeciesTypes> = mapOf(\n" +
          "".join(f"    {i} to SpeciesTypes({a}, {b}),\n" for i, (a, b) in sorted(types.items())) + ")\n")
    top = max(gender)
    hexs = "".join("%02x" % gender.get(i, 255) for i in range(top + 1))
    if "species_growth_off" in g:  # opt-in: games whose EXP bar reads their own growth rates
        rates = "".join("%02x" % (rom.u8(g["species_name1"] + (i - 1) * g["species_stride"] + g["species_growth_off"])
                                 if i in names else 255) for i in range(max(names) + 1))
        assert rates[2:4] == "03", "species growth rate offset is wrong"  # BULBASAUR: MEDIUM_SLOW
        write(f"GrowthRates{sfx}.kt", header(g, "gSpeciesInfo growthRate by species id (vanilla's 0..5:\n"
              "// MEDIUM_FAST, ERRATIC, FLUCTUATING, MEDIUM_SLOW, FAST, SLOW; 255 = no species).\n") +
              f"internal val growthRates{sfx}: IntArray by lazy {{ unhexRatios(\"{rates}\") }}\n")
    write(f"GenderRatios{sfx}.kt", header(g, "gSpeciesInfo genderRatio by species id (0 always\n"
          "// male, 254 always female, 255 genderless, else female when (personality & 0xFF) < ratio).\n") +
          f"val genderRatios{sfx}: IntArray by lazy {{ unhexRatios(\"{hexs}\") }}\n")
    if growth:  # only games that ask (species_growth_off): their EXP bar
        hexg = "".join("%02x" % growth.get(i, 255) for i in range(max(growth) + 1))
        write(f"GrowthRates{sfx}.kt", header(g, "gSpeciesInfo growthRate by species id (vanilla's GROWTH_* ids:\n"
              "// ExpTables.kt's curves; 255 = no species).\n") +
              f"internal val growthRates{sfx}: IntArray by lazy {{ unhexRatios(\"{hexg}\") }}\n")

    items = named_table(rom, g["items_base"], g["items_stride"], g.get("items_name_inline", False),
                        limit=g.get("items_max", 4000))
    assert all(items.get(i) == n for i, n in g["checks"]["items"].items()), "item table address is wrong"
    write(f"ItemNames{sfx}.kt", header(g, "gItemsInfo names, keyed by item id.\n") +
          f"val itemNames{sfx}: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(n)},\n" for i, n in sorted(items.items())) + ")\n")
    # The descriptions aren't bundled: the app reads them from the player's ROM
    # (RomItemText) through the game's NativeConfig.itemDescs - this table. The
    # description pointer sits items_desc_off bytes before the name (pointer or inline).
    p = rom.u32(g["items_base"] + 28 * g["items_stride"] + g["items_desc_off"])
    probe = g.get("potion_desc", "20 points")  # POTION's description, in the game's language
    assert rom.is_ptr(p) and probe in (rom.text(p, 200) or ""), "item description offset is wrong"
    print(f"itemDescs = ItemDescTable(0x{g['items_base']:08X}L, 0x{g['items_stride']:X}, {g['items_desc_off']})")

    moves = named_table(rom, g["moves_base"], g["moves_stride"])
    rows = []
    for i, n in sorted(moves.items()):
        bits = rom.u16(g["moves_base"] + i * g["moves_stride"] + g["move_bits_off"])
        rows.append((i, n, bits & 0x1F, bits >> 7))
    info = {i: (t, p) for i, _, t, p in rows}
    assert all(info.get(i) == v for i, v in g["checks"]["moves"].items()), "move fields are wrong"
    write(f"MoveData{sfx}.kt", header(g, "gMovesInfo name pointer + type/power bitfield.\n") +
          f"val moveData{sfx}: Map<Int, MoveInfo> = mapOf(\n" +
          "".join(f"    {i} to MoveInfo({kstr(n)}, {t}, {p}),\n" for i, n, t, p in rows) + ")\n")

    if "chart" not in g:  # a game whose chart is another's (Seaglass: Heart and Soul's)
        print(f"{len(names)} species, {len(items)} items, {len(moves)} moves")
        return
    n = g["chart_n"]
    chart = {}
    for atk in range(n):
        for dfn in range(n):
            v = rom.u32(g["chart"] + 4 * (atk * n + dfn))
            if atk in EXPANSION_TYPE_NAMES and dfn in EXPANSION_TYPE_NAMES and v != 0x1000:
                chart[atk * 100 + dfn] = v * 100 // 0x1000
    assert chart[1 * 100 + 8] == 0 and chart[11 * 100 + 13] == 200 and chart[14 * 100 + 5] == 0, "chart is wrong"
    write(f"TypeChart{sfx}.kt", header(g, "Newer pokeemerald-expansion type ids (NOT vanilla's):\n"
          "// 1-9 Normal..Steel, 11-19 Fire..Fairy. The chart is gTypeEffectiveness [n][n] u32 uq4.12;\n"
          "// key = atkType*100+defType -> percent, pairs not listed are 100.\n") +
          f"val typeNames{sfx}: Map<Int, String> = mapOf(\n" +
          "".join(f"    {i} to {kstr(s)},\n" for i, s in sorted(EXPANSION_TYPE_NAMES.items())) + ")\n\n" +
          f"val typeEffectiveness{sfx}: Map<Int, Int> = mapOf(\n" +
          "".join(f"    {k} to {v},\n" for k, v in sorted(chart.items())) + ")\n")

    mapsecs = {}
    for i in range(0, g.get("mapsec_count", 256)):
        a = g["mapsec_base"] + 8 * i
        p = rom.u32(a + g.get("mapsec_name_off", 0))
        if p == 0:
            continue
        if not rom.is_ptr(p):
            break
        s = rom.text(p)
        if s is not None:  # some slots point at an empty string
            mapsecs[i] = title(s)
    rm = g.get("region_map")
    if "region_maps" in g:
        # Several region maps, each with its own cursor grid and its own range of mapsec ids (the game's map code
        # picks the map by the section's id): a section goes on the map whose range holds it, its rect + (ox, oy) as
        # below, or its grid tiles where it has no rect of its own.
        assert g.get("mapsec_name_off") == 4
        images, rows, layouts = [], [], []
        regions = g["region_maps"]
        for r in regions:
            images.append(r["image"])
            gr = rom.d[rom.off(r["layout"]):rom.off(r["layout"]) + r["w"] * r["h"] * r.get("cell", 1)]
            layouts.append(gr)
            r["on_grid"] = {struct.unpack_from("<H" if r.get("cell", 1) == 2 else "<B", gr, k)[0]
                            for k in range(0, len(gr), r.get("cell", 1))} - {r["none"]}
            assert r["on_grid"] <= set(mapsecs), sorted(r["on_grid"] - set(mapsecs))
            assert all(r["ids"][0] <= s <= r["ids"][1] for s in r["on_grid"]), r["image"]
        for i, s in sorted(mapsecs.items()):
            k = next((k for k, r in enumerate(regions) if r["ids"][0] <= i <= r["ids"][1]), None)
            a = g["mapsec_base"] + 8 * i
            x, y, mw, mh = (rom.u8(a + n) for n in range(4))
            if k is None:
                rows.append(f"    {i} to MapSecInfo({kstr(s)}, -1, 0, 0, 0, 0),\n")
            elif (x, y) != (0, 0) and mw and mh:
                r = regions[k]
                rows.append(f"    {i} to MapSecInfo({kstr(s)}, {k}, {x + r['ox']}, {y + r['oy']}, {mw}, {mh}),\n")
            elif i in regions[k]["on_grid"]:
                rows.append(f"    {i} to MapSecInfo({kstr(s)}, {k}, 0, 0, 0, 0),\n")
            else:
                rows.append(f"    {i} to MapSecInfo({kstr(s)}, -1, 0, 0, 0, 0),\n")
        write(f"MapSecData{sfx}.kt", header(g, "gRegionMapEntries by mapsec id, rects in 8px tiles on\n"
              "// regionMapImages' screens (RomArt rebuilds them from the ROM); region -1 = not on a map. The grids are\n"
              "// each map's cursor grid, by image.\n") +
              f"val regionMapImages{sfx} = arrayOf({', '.join(kstr(n) for n in images)})\n\n" +
              f"val mapSecData{sfx}: Map<Int, MapSecInfo> = mapOf(\n" + "".join(rows) + ")\n\n" +
              f"internal val regionLayouts{sfx}: List<RegionLayout> = listOf(\n" +
              "".join(f"    RegionLayout({r['ox']}, {r['oy']}, {r['w']}, {r['h']}, 0x{r['none']:02X}, listOf(\n"
                      f"        hexBytes(\"{gr.hex()}\"),\n" +
                      ("    ), cellBytes = 2),\n" if r.get("cell", 1) == 2 else "    )),\n")
                      for r, gr in zip(regions, layouts)) + ")\n")
    elif rm is None:
        write(f"MapSecData{sfx}.kt", header(g, "gRegionMapEntries names by mapsec id. No region-map\n"
              "// image yet, so every entry is region -1 (text-only Map tab, like Unbound).\n") +
              f"val regionMapImages{sfx} = arrayOf<String>()\n\n" +
              f"val mapSecData{sfx}: Map<Int, MapSecInfo> = mapOf(\n" +
              "".join(f"    {i} to MapSecInfo({kstr(s)}, -1, 0, 0, 0, 0),\n" for i, s in sorted(mapsecs.items())) + ")\n")
    else:
        # Older {u8 x, y, w, h; name} entries, in the cursor grid's tiles: the
        # screen rect is that + (ox, oy), like MapSecDataEmerald. {0, 0} is "not
        # on the map", unless the grid names the section (then its tiles place it).
        assert g.get("mapsec_name_off") == 4
        w, h = rm["w"], rm["h"]
        cell = rm.get("cell", 1)  # bytes per grid cell: u16 where mapsec ids pass 255
        grids = [rom.d[rom.off(a):rom.off(a) + w * h * cell] for a in [rm["layout"]] + rm.get("extra_layers", [])]
        on_grid = {struct.unpack_from("<H" if cell == 2 else "<B", gr, k)[0] for gr in grids for k in range(0, w * h * cell, cell)}
        on_grid -= {rm["none"]}
        assert on_grid <= set(mapsecs), sorted(on_grid - set(mapsecs))
        rows = []
        for i, s in sorted(mapsecs.items()):
            a = g["mapsec_base"] + 8 * i
            x, y, mw, mh = (rom.u8(a + k) for k in range(4))
            # place_min: a hack that drew a region of its own over Emerald's ids keeps Emerald's {x, y, w, h} for
            # the Hoenn sections it never uses - below this id a section is placed only where the grid names it.
            stale = i < rm.get("place_min", 0) and i not in on_grid
            if (x, y) != (0, 0) and mw and mh and not stale:
                rows.append(f"    {i} to MapSecInfo({kstr(s)}, 0, {x + rm['ox']}, {y + rm['oy']}, {mw}, {mh}),\n")
            elif i in on_grid:
                rows.append(f"    {i} to MapSecInfo({kstr(s)}, 0, 0, 0, 0, 0),\n")
            else:
                rows.append(f"    {i} to MapSecInfo({kstr(s)}, -1, 0, 0, 0, 0),\n")
        write(f"MapSecData{sfx}.kt", header(g, "gRegionMapEntries by mapsec id, rects in\n"
              f"// 8px tiles on regionmap/{rm['image']}.png (RomArt rebuilds it from the ROM); region -1 =\n"
              "// not on the map. The grid is sRegionMap_MapSectionLayout, for the map cursor.\n") +
              f"val regionMapImages{sfx} = arrayOf(\"{rm['image']}\")\n\n" +
              f"val mapSecData{sfx}: Map<Int, MapSecInfo> = mapOf(\n" + "".join(rows) + ")\n\n" +
              f"internal val regionLayouts{sfx}: List<RegionLayout> = listOf(\n"
              f"    RegionLayout({rm['ox']}, {rm['oy']}, {w}, {h}, 0x{rm['none']:02X}, listOf(\n" +
              "".join(f"        hexBytes(\"{gr.hex()}\"),\n" for gr in grids) +
              ("    ), cellBytes = 2),\n)\n" if cell == 2 else "    )),\n)\n"))
    print(f"{len(names)} species, {len(items)} items, {len(moves)} moves, {len(mapsecs)} mapsecs")


if __name__ == "__main__":
    main()
    if SAME_AS is not None:
        check_same()
