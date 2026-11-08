#!/usr/bin/env python3
"""Fingerprints of the graphics the companion rebuilds from the player's own ROM
(companion/data/RomArt.kt) - FireRed / Emerald's party-menu slot, Poke Ball,
status icons, small font, party backdrop, region maps, the region map's player
icons and the trainer card's pieces (kept raw for TrainerCardArt.kt), and the
hacks' own: Heart and Soul's and the CFRU hacks' party menus, region maps, ... Nothing of the
ROM is bundled: per blob this stores only

  size    decoded length (LZ77 blobs: the decompressed length)
  crc     CRC32 of the decoded bytes
  headOff / head   where a distinctive 16-byte window sits inside the blob's
          raw ROM bytes, and a 64-bit hash of it (RomArt.head) - what the
          one-pass ROM scan looks for before checking size + crc.
  pre     a 16-bit hash of the window's first 8 bytes (RomArt.pre): the scan's
          cheap per-byte filter, before it computes the full head.

Retail and hacks that kept the art all match, wherever the linker
put it. Source: the pinned decomp builds (see CLAUDE.md) and their ELFs.

Usage: scripts/gen_rom_art_sigs.py   (needs $DECOMPS/pokefirered and
$DECOMPS/pokeemerald built once, and the hack ROMs at the *_ROM paths below - Unbound /
Radical Red / Odyssey / Amethyst / Sword and Shield Ultimate Plus / Heart and Soul / Lazarus / Seaglass / SoulGold / Glazed / Imperium / Dreamstone Mysteries / Wishes of Tomorrow /
Quetzal / R.O.W.E. / Super Mariomon / Recharged Emerald, and $DECOMPS/pokelegacy built; a missing one keeps its rows as they were. Re-run only if a pin changes)
"""
import os
import subprocess
import zlib

import smol
from decomps import decomp

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = decomp()
KOTLIN = os.path.join(HERE, "../app/src/main/kotlin/com/pokedaisy/app/companion/data/RomArtSigsGen.kt")

M64 = (1 << 64) - 1


def fmix(k):
    k ^= k >> 33
    k = (k * 0xFF51AFD7ED558CCD) & M64
    k ^= k >> 33
    k = (k * 0xC4CEB9FE1A85EC53) & M64
    k ^= k >> 33
    return k


def pre(b):
    """RomArt.pre: the first 8 bytes as a little-endian long, times an odd constant, top 16 bits."""
    return ((int.from_bytes(b[0:8], "little") * 0x9E3779B97F4A7C15) & M64) >> 48


def head(b):
    """RomArt.head: two little-endian longs, mixed."""
    a, c = int.from_bytes(b[0:8], "little"), int.from_bytes(b[8:16], "little")
    return fmix(a ^ fmix(c))


def lz77(rom, off):
    """-> (decoded, raw length consumed)."""
    assert rom[off] == 0x10
    size = int.from_bytes(rom[off + 1:off + 4], "little")
    out, i = bytearray(), off + 4
    while len(out) < size:
        flags = rom[i]; i += 1
        for b in range(8):
            if len(out) >= size:
                break
            if flags & (0x80 >> b):
                d = rom[i] << 8 | rom[i + 1]; i += 2
                for _ in range((d >> 12) + 3):
                    out.append(out[-((d & 0xFFF) + 1)])
            else:
                out.append(rom[i]); i += 1
    # A last back-reference can run past the size in the header (CFRU's party palette: 512
    # bytes, its last copy ends at 513); the game, and RomArt.lz77, stop at the size.
    return bytes(out[:size]), i - off


# Pokemon Unbound v2.1.1.1 (sha1 b4776b82...): a binary hack with no ELF. Its
# player's front pics replace Red / Leaf at the same pic ids (TRAINER_PIC_RED
# 135, _LEAF 136) in its own gTrainerFrontPicTable / PaletteTable (found by
# shape: 148 {ptr, size, tag = index} records, 18 / 20 literal-pool refs).
GBA_DIR = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/")


def first_rom(*names):
    """The first of [names] (one ROM's file names seen on this machine) that exists."""
    return next((GBA_DIR + n for n in names if os.path.exists(GBA_DIR + n)), GBA_DIR + names[0])


UNBOUND_ROM = first_rom("Pokemon Unbound (v2.1.1.1).gba", "Pokemon Unbound (v2.1.1.1).gba")
# Its French build redrew three pieces of the party menu in place (GitHub #227: "PV" / "N.", French status
# icons): the slot background tiles, the status icons and FONT_SMALL (0x1EAF00 in both; its widths are FireRed's).
# RomArt writes them to partycfru_fr/; everything else is English Unbound's bytes.
UNBOUND_FR_ROM = first_rom("Pokemon Unbound (v2.1.1.1) (French).gba")
UNBOUND_PIC_TABLE = 0x23957C
UNBOUND_PAL_TABLE = 0x239A1C


def unbound_symbols(rom):
    """The pics' ROM offsets by made-up symbol name, read through Unbound's tables."""
    def entry(table, i):
        return int.from_bytes(rom[table + 8 * i:table + 8 * i + 4], "little") - 0x08000000, 0
    return {
        "PicMale": entry(UNBOUND_PIC_TABLE, 135), "PalMale": entry(UNBOUND_PAL_TABLE, 135),
        "PicFemale": entry(UNBOUND_PIC_TABLE, 136), "PalFemale": entry(UNBOUND_PAL_TABLE, 136),
        **cfru_symbols(rom),
    }


# The CFRU hacks (Unbound, Radical Red, Odyssey, Amethyst) share one party menu: FireRed's
# party_menu.c with 112x40 slot windows, new slot tilemaps and new background art (see
# scripts/gen_cfru_party_assets.py, which checked it the same in all four). Through FireRed rev 0's
# literal pools, which the hacks kept: AllocPartyMenuBgGfx's (gPartyMenuBg_Gfx / _Tilemap / _Pal,
# LZ77) and the status icons' CompressedSpriteSheet; the slot tilemaps sit at fixed addresses.
# The font, the Poke Ball (only Unbound draws one) and both sprite palettes are FireRed's own
# bytes, which FR_FONT_SMALL / FR_BALL_* / FR_STATUS_PAL already find. Only the status icons
# differ per hack.
RADICAL_RED_ROM = GBA_DIR + "Pokemon Radical Red (v4.1).gba"
ODYSSEY_ROM = GBA_DIR + "Pokemon Odyssey (v4.1.1).gba"
AMETHYST_ROM = GBA_DIR + "Pokemon Amethyst (v1.3.0).gba"  # v1.4.1's status icons are the same bytes
# Sword and Shield Ultimate Plus kept CFRU's party tiles, backdrop tilemap and slot tilemaps byte for
# byte; only its palette (Galar red / charcoal) is its own (its status icons are Odyssey's).
SWORD_SHIELD_ROM = GBA_DIR + "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba"
# Let's Go Pikachu / Eevee kept CFRU's party menu code and slot tilemaps (its literal pools at CFRU's offsets) with its own
# tiles, palette, backdrop tilemap and status icons, the same bytes in both versions.
LETS_GO_ROM = GBA_DIR + "Pokemon Let's Go Pikachu.gba"


def unbound_fr_symbols(rom):
    return {**cfru_symbols(rom), "FontSmall": (0x1EAF00, 0x4000)}


def cfru_symbols(rom):
    def ptr(at):
        return int.from_bytes(rom[at:at + 4], "little") & 0x1FFFFFF, 0
    return {"PartyBgGfx": ptr(0x11EFB0), "PartyBgMap": ptr(0x11EFCC), "PartyBgPal": ptr(0x11EFF4),
            "SlotMain": (0x45A180, 70), "SlotNoHp": (0x45A1C8, 70), "SlotEmpty": (0x45A210, 70),
            "StatusGfx": ptr(0x45A574)}


# Pokemon Lazarus v2.0 (sha1 7dcdc7e2...): a pokeemerald build (gcc) with its own
# region map in Emerald's region_map.c. LoadRegionMapGfx's literal pool (ROM
# 0x1FC6A0) holds sRegionMapBg_GfxLZ, _TilemapLZ, BG_CHAR_ADDR(2),
# BG_SCREEN_ADDR(28), sRegionMapBg_Pal - only 2 palettes' worth (0x40) before
# the tiles, and the tiles only use colours 1-28.
LAZARUS_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Lazarus (v2.0).gba")


# Its TRAINER CARD is Emerald's Hoenn trainer_card.c with its own art (found from a headless VRAM dump of the open card and
# the card code's literal pools; rendered from these, the game's own frames matched pixel for pixel): the card's tiles and
# front / back tilemaps, its 0- and 1-star palettes (the rest, the backdrop tilemap and the female palette are retail's
# bytes), its badges, the player's front pics (trainer pics 0x6B / 0x6C) and its own FONT_NORMAL.
def lazarus_symbols(rom):
    return {"RegionGfx": (0xCE1D20, 0), "RegionMap": (0xCE3248, 0), "RegionPal": (0xCE1CE0, 0x40),
            "CardGfx": (0x51FE6C, 0), "CardFront": (0x5202AC, 0), "CardBack": (0x520438, 0),
            "CardPal0": (0x51FE0C, 0x60), "CardPal1": (0xE4E5BC, 0x60),
            "BadgesPal": (0xE4E8FC, 0x20), "BadgesGfx": (0xE4E9DC, 0),
            "PicMale": (0x45160C, 0), "PicMalePal": (0x451B78, 0), "PicFemale": (0x4518C4, 0), "PicFemalePal": (0x451B90, 0),
            "FontNormal": (0x80FABC, 0x8000), "FontNormalWidths": (0x817ABC, 0x200)}


# Pokemon SoulGold v1.1.4 (sha1 ea5d369c...): a newer pokeemerald-expansion
# build whose bag screen draws a night sky of stars on BG3 (headless VRAM dump
# with the bag open, matched byte for byte). The literal pool next to the bag's
# graphics loads (ROM 0x0A3444) holds its smol tiles (mode 6, 80 tiles), its
# smol tilemap (mode 8, 32x32) and the raw palette (BG palette 0).
SOULGOLD_ROM = first_rom("Pokemon SoulGold (v1.1.4).gba", "Pokemon-SoulGold-v1.1.4.gba")


# Its Johto region map is Emerald's region_map.c with smol art: 8bpp tiles
# (mode 5), the 64x64 one-byte tilemap (mode 8) and 3 palettes loaded at BG
# palette 7 (raw, 0x60), checked by rendering them (sRegionMapBg_*).
# Its TRAINER CARD is expansion's trainer_card.c on Emerald's Hoenn layout with its own Johto art (from a headless VRAM
# dump of the open card and the card code, 0x0826AB34; rendered from these, the game's frames matched pixel for pixel in
# all four builds): smol tiles (mode 6) and tilemaps (mode 8), a palette per star count, badges (smol mode 3), its
# player pics (trainer pics 71 / 72) and FONT_NORMAL (Emerald's with glyph 0x38 redrawn, its own widths). The star's
# palette is retail's bytes (CARD_STAR_PAL).
def soulgold_symbols(rom):
    return {
        "BagStarsGfx": (0x6AED68, 0), "BagStarsMap": (0x6AE9F4, 0), "BagStarsPal": (0x6AF0D8, 0x20),
        "RegionGfx": (0xF39DA0, 0), "RegionMap": (0xF39AC4, 0), "RegionPal": (0xF37148, 0x60),
        "CardGfx": (0x66BB14, 0), "CardFront": (0x66B958, 0), "CardBack": (0x66B8C0, 0), "CardBg": (0x66BB00, 0),
        "CardPal0": (0x66BE54, 0x60), "CardPal1": (0x13D097C, 0x60), "CardPal2": (0x13D08BC, 0x60),
        "CardPal3": (0x13D07FC, 0x60), "CardPal4": (0x13D073C, 0x60), "CardFemalePal": (0x13D0100, 0x20),
        "BadgesPal": (0x13D00E0, 0x20), "BadgesGfx": (0x13D03D8, 0),
        "PicMale": (0x70C31C, 0), "PicMalePal": (0x70C2FC, 0x20), "PicFemale": (0x70C044, 0), "PicFemalePal": (0x70C024, 0x20),
        "FontNormal": (0x61ADE8, 0x8000), "FontNormalWidths": (0x61ABE8, 0x200),
    }


# Emerald Seaglass v3.0 (sha1 b9f4d332...) redrew the region map's tiles but
# kept Emerald's tilemap and palette (EM_REGION_MAP / _PAL): LoadRegionMapGfx's
# literal pool (ROM 0x1DFFC4) holds the tiles at 0x089534A0, LZ77.
SEAGLASS_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Emerald Seaglass (v3.0).gba")


def seaglass_symbols(rom):
    return {"RegionGfx": (0x9534A0, 0)}


# Pokemon Glazed 9.2.0 (sha1 e10105d8...), an in-place edit of retail Emerald: its own
# region map (Tunod and Johto side by side) written over retail's own blobs, so
# LoadRegionMapGfx's literal pool still names retail's addresses - the 8bpp tiles
# (LZ77), the 64x32 one-byte tilemap (LZ77; retail's is 64x64) and the palette
# (two palettes' worth: its tiles use colours 1-24 at BG palette 7).
GLAZED_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Glazed (v9.2.0).gba")


# Pokemon Emerald Imperium v1.3.1 (sha1 1d20091c...), pokeemerald-expansion 1.10: Emerald's
# region map with markers for its five new places - 7 tiles added to the 8bpp tiles (LZ77) and
# 4 cells changed in the 64x64 tilemap (LZ77); the palette is Emerald's own (EM_REGION_PAL).
IMPERIUM_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Emerald Imperium (v1.3.1).gba")


def imperium_symbols(rom):
    # Its trainer card is Emerald's in its own FONT_NORMAL (glyphs 0x38 / 0x39 redrawn) and widths.
    return {"RegionGfx": (0xDD84CC, 0), "RegionMap": (0xDD92D0, 0), "FontNormal": (0x664350, 32768),
            "FontNormalWidths": (0x66C350, 512)}


# Pokemon Dreamstone Mysteries (sha1 20e91aec...), pokeemerald-expansion 1.11.2: its own Cormoria TOWN MAP, a FireRed-like
# screen - 4bpp tiles (LZ77; an 8bpp copy of the same pixels sits beside them), a 32x20 u16 tilemap (LZ77) and a 16-colour
# palette at BG palette 0 - from its map code's literal pool (ROM 0x1DC23C / 0x1DC45C); headless, the TOWN MAP's VRAM and
# palette RAM matched them.
DREAMSTONE_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Dreamstone Mysteries.gba")


def dreamstone_symbols(rom):
    return {"RegionGfx": (0xE846E8, 0), "RegionMap": (0xE843E8, 0), "RegionPal": (0xE826E8, 0x20)}


# Pokemon Wishes of Tomorrow v1.2.6 (sha1 60182b93...), pokeemerald-expansion 1.15.3: its own JOKAIM TOWN MAP, Emerald's
# kind of screen - 8bpp tiles (smol), a 64x64 one-byte affine tilemap (smol) and a 48-colour palette at BG palette 7 - the
# second triple of the first row of its map code's region table (0x08CDC868: seven words a region, six regions; the rest
# are FireRed-style Kanto / Sevii maps and one more the game doesn't show for any of its places). Headless, the TOWN MAP's
# VRAM, BG2 tilemap and palette RAM matched them.
WISHES_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Wishes of Tomorrow (v1.2.6).gba")


def wishes_symbols(rom):
    return {"RegionGfx": (0xCE59A8, 0), "RegionMap": (0xCE5880, 0), "RegionPal": (0xCE77C8, 0x60)}


# Pokemon Team Rocket Edition 2026, Spanish (v3.0.9, sha1 0d8bdaee...): Wishes' kind of screen (8bpp smol tiles, a 64x64
# one-byte smol tilemap, a 48-colour palette) for each of its seven region maps. 0x08192D44 turns the map section into a
# region (1 Kanto 0x7A-0xE6, 2-4 the Sevii Islands 1-3 / 4-5 / 6-7, 5 Johto 0x13D-0x190, 6 Hoenn 0x00-0x79, 7 Sinnoh
# 0x191-0x1EE) and the map code's switches (jump tables 0x08E69B28 / B68 / B88) load that region's tilemap, tiles and
# palette - Kanto's, Hoenn's and Sinnoh's palette at BG palette 7 like Emerald's, the other four at BG palette 0.
TEAM_ROCKET_V3_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Team Rocket Edition 2026 (Spanish).gba")


def team_rocket_v3_symbols(rom):
    out = {}
    for name, (gfx, tilemap, pal) in {
        "Kanto": (0xE70B68, 0xE7097C, 0xE69F6C), "Sevii123": (0xE6FB48, 0xE6FA94, 0xE6A02C),
        "Sevii45": (0xE6F8DC, 0xE69EF4, 0xE6A06C), "Sevii67": (0xE6F544, 0xE6F46C, 0xE6A08C),
        "Johto": (0xE70090, 0xE6FE44, 0xE69FAC), "Hoenn": (0xE7168C, 0xE7147C, 0xE69FEC),
        "Sinnoh": (0xE6E9A4, 0xE6E764, 0xE6A0AC),
    }.items():
        out.update({f"{name}Gfx": (gfx, 0), f"{name}Map": (tilemap, 0), f"{name}Pal": (pal, 0x60)})
    return out


# Pokemon Monomyth v1.0 (sha1 ae1c4be9...), its own closed pokeemerald build: the PokeNav's ATHANTO MAP, a mode-0 screen -
# 8bpp tiles (LZ77, loaded from tile 0x60), a 32x32 u16 text tilemap (LZ77; the game shows rows 2..21) and a 48-colour
# palette at BG palette 7 - and the one player head its map code loads (0x08117950's pool: the same for either gender).
# Headless, the ATHANTO MAP's VRAM (BG2), palette RAM and OBJ tiles matched them.
MONOMYTH_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Monomyth (v1.0).gba")


def monomyth_symbols(rom):
    return {"RegionGfx": (0xA12550, 0), "RegionMap": (0xA1582C, 0), "RegionPal": (0xA124F0, 0x60),
            "PlayerGfx": (0xA15DA8, 0), "PlayerPal": (0xA15D88, 0x20)}


# Pokemon Quetzal English Alpha 9 v0 (sha1 d0658315...): FireRed's Kanto / Sevii maps (pixel for
# pixel, 3 tiles left and 2 up of FireRed's screen) redrawn for Emerald's region_map.c - 8bpp
# tiles and a 64x64 affine tilemap per map (LZ77), FireRed's palette loaded at BG palette 7. Its
# map code's literal pool (ROM 0x1221B54) lists the four tile blobs, then the four tilemaps.
QUETZAL_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Quetzal (Alpha 9.0) (English).gba")


def quetzal_symbols(rom):
    return {"RegionPal": (0x121EA1C, 0x40), "JohtoGfx": (0x121DC04, 0), "JohtoMap": (0x121E6A0, 0),
            "KantoGfx": (0x121EA5C, 0), "Sevii123Gfx": (0x121F84C, 0), "Sevii45Gfx": (0x1220428, 0), "Sevii67Gfx": (0x1220F38, 0),
            "KantoMap": (0x121F51C, 0), "Sevii123Map": (0x12201EC, 0), "Sevii45Map": (0x1220D14, 0), "Sevii67Map": (0x1221920, 0)}


# Pokemon Heart and Soul v2.0.6 (sha1 79ee6df0...): Emerald's region_map.c with smol art and seven
# maps (gRegionMapInfos 0x08D4D330); the Johto + Kanto one ([6], REGION_MAP_JK): mode-5 tiles and a
# mode-8 tilemap (equal to VRAM with the Pokegear map up, byte for byte), Emerald's palette.
HNS_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon Heart and Soul (v2.0.6).gba")


def hns_symbols(rom):
    # Its party menu (graphics/party_menu/hns/, the expansion's party_menu.c): background tiles
    # (smol) + palette (raw, the whole .gbapal) + tilemap (smol tilemap), through
    # AllocPartyMenuBgGfx's literal pool; the Poke Ball's and status icons' sheets (smol) and
    # palettes (raw), through their sprite-sheet structs; FONT_SMALL (raw, gFontSmallLatinGlyphs,
    # 512 16x16 glyphs). Its slot tilemaps are Emerald's bytes (EM_SLOT_MAIN / _NO_HP find them).
    return {"RegionGfx": (0xD4D630, 0), "RegionMap": (0xD4D3F4, 0),
            "PartyBgGfx": (0x6CC324, 0), "PartyBgPal": (0x6CC124, 512), "PartyBgMap": (0x6CC07C, 0),
            "BallGfx": (0x6CBF60, 0), "BallPal": (0x6CBE9C, 32), "StatusGfx": (0x6CBCC4, 0),
            "StatusPal": (0x6CBCA4, 32), "FontSmall": (0x665040, 32768)}


# Pokemon R.O.W.E. v2.1.9.1 Experimental (sha1 81bd0f4b...): Emerald's region_map.c with three maps
# picked by gMapHeader.region - Hoenn, Kanto, Sevii: 8bpp tiles and a 64x64 affine tilemap each
# (LZ77), one palette (sRegionMapBg_Pal, at BG palette 7). Its own symbol table names them.
ROWE_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Pokemon R.O.W.E. (v2.1.9.1 Experimental).gba")


def rowe_symbols(rom):
    return {"RegionPal": (0xFCE754, 0x40), "RegionGfx": (0xFCE794, 0), "RegionMap": (0xFCF604, 0),
            "KantoGfx": (0xFCFB34, 0), "KantoMap": (0xFD069C, 0), "SeviiGfx": (0xFD0B9C, 0), "SeviiMap": (0xFD17C4, 0)}


# Super Mariomon v1.5.1-Anniversary (sha1 831fe0f9...): Emerald's region_map.c with its own
# world map - LoadRegionMapGfx's literal pool (0x08154968, retail's code) holds the tiles,
# tilemap and palette (the same bytes in all five releases) - and its own head for the map's
# player icon in the BRENDAN slot (CreateRegionMapPlayerIcon's pool: an 11-colour palette packed
# right before the 16x16 tiles; MAY's slot keeps retail's).
MARIOMON_ROM = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba/Super Mariomon (v1.5.1 Anniversary).gba")


def mariomon_symbols(rom):
    return {"RegionGfx": (0x834E48, 0), "RegionMap": (0x835C34, 0), "RegionPal": (0x834E08, 0x40),
            "PlayerGfx": (0x835FD6, 0x80), "PlayerPal": (0x835FC0, 0x20)}


# Pokémon Recharged Emerald v2.2.8 (sha1 56c97772...; v2.2.5 and Rebalanced hold the same bytes): a gcc pokeemerald
# whose art is recompressed (another LZ77 encoder: none of Emerald's heads match) and partly redrawn. Found headless:
# the party menu's VRAM / palette RAM matched against every LZ77 stream in the ROM (the backdrop tiles, 80 of them, a
# few overwritten by text windows; its palette and status palette are its own, the tilemap and status icons
# Emerald's data recompressed, the Poké Ball Emerald's bytes: EM_BALL_*); the slot tilemaps are Emerald's bytes (EM_SLOT_*); FONT_SMALL by retail's glyphs'
# votes (five glyphs changed). The region map (redrawn) from region_map.c's literal pools (0x081A2784 / 0x081A2788,
# the palette the raw 64 bytes beside them), the map's player heads from CreateRegionMapPlayerIcon's pools
# (0x081A3A0C.. : Brendan's {gfx, pal} first, then May's).
RECHARGED_EMERALD_ROM = GBA_DIR + "Pokemon Recharged Emerald (v2.2.8).gba"


def recharged_emerald_symbols(rom):
    return {"PartyBgGfx": (0x624B70, 0), "PartyBgPal": (0x624F0C, 0), "PartyBgMap": (0x624FF8, 0),
            "StatusGfx": (0x625540, 0), "StatusPal": (0x625738, 0),
            "FontSmall": (0x859644, 0x4000),
            "RegionGfx": (0x9110CC, 0), "RegionMap": (0x911C4C, 0), "RegionPal": (0x91108C, 0x40),
            "BrendanGfx": (0x911FF0, 0x80), "BrendanPal": (0x911FD0, 0x20), "MayGfx": (0x912090, 0x80), "MayPal": (0x912070, 0x20)}
# Pokémon Recharged Yellow v1.8.0 (sha1 be503359...): its TRAINER CARD is FireRed's art byte for byte (the FR_CARD_*
# rows find it) under card code of its own, with its own player pics (trainer pics 135 / 136, LZ77) and its own copy of
# FONT_NORMAL (8 glyphs redrawn). Only the font's first 0x118 glyphs (and widths) are fingerprinted: every character the
# card prints is below that, and v1.9.4 added glyphs past it - so one row finds it in all four releases.
RECHARGED_YELLOW_ROM = first_rom("Pokemon Recharged Yellow (v1.8.0).gba")


def recharged_yellow_symbols(rom):
    return {"PicMale": (0x545754, 0), "PicMalePal": (0x545A70, 0), "PicFemale": (0x545A98, 0), "PicFemalePal": (0x545DA8, 0),
            "FontNormal": (0x7CBF84, 0x118 * 64), "FontNormalWidths": (0x7D3F84, 0x118)}


def glazed_symbols(rom):
    # Its trainer card: Tunod badges (tiles repointed, the palette edited in place), its player
    # pics (front-pic table entries 71 / 72 repointed, palettes edited in place) and FONT_NORMAL
    # edited in place - each found through retail's tables and literal pools, rendered and checked.
    return {"RegionGfx": (0x59F77C, 0), "RegionMap": (0x5A04E0, 0), "RegionPal": (0x59F73C, 0x40),
            "BadgesPal": (0x56F4EC, 32), "BadgesGfx": (0x452334, 0), "PicMale": (0x1202798, 0),
            "PicMalePal": (0xD61A30, 0), "PicFemale": (0x12045DC, 0), "PicFemalePal": (0xD61D58, 0),
            "FontNormal": (0x64C2E4, 32768)}


# The European Emeralds (Spanish, German, French, Italian) redrew three party-menu
# pieces: the background tiles (the slot's HP label: PS / KP / PV; the CANCEL
# button), the status icons (localized abbreviations) and FONT_SMALL (a few
# glyphs). Everything else the party menu uses is byte for byte English's. Found
# by scanning near English's offset for a blob of the same size (the palette,
# tilemaps and Poke Ball beside them are identical), FONT_SMALL through
# English's literal pool. RomArt writes them to partyem_<lang>/.
# The other languages sit in the retail ROM folder with the English ones, under their No-Intro names.
MULTI_LANGUAGE = os.path.expanduser("~/Downloads/Game ROMs & Emulation/gba")


def multi_language_rom(name):
    return os.path.join(MULTI_LANGUAGE, name + ".gba")


EMERALD_EU_ROMS = {
    "ES": multi_language_rom("Pokemon - Edicion Esmeralda (Spain)"),
    "DE": multi_language_rom("Pokemon - Smaragd-Edition (Germany)"),
    "FR": multi_language_rom("Pokemon - Version Emeraude (France)"),
    "IT": multi_language_rom("Pokemon - Versione Smeraldo (Italy)"),
}
EMERALD_EU_OFFSETS = {  # gPartyMenuBg_Gfx, gStatusGfx_Icons, gFontSmallLatinGlyphs
    "ES": (0xD96728, 0xD96ECC, 0x636218),
    "DE": (0xD967E8, 0xD96F8C, 0x644D88),
    "FR": (0xD967E0, 0xD96F84, 0x637C18),
    "IT": (0xD96764, 0xD96F08, 0x630338),
}


def emerald_eu_symbols(lang):
    gfx, status, font = EMERALD_EU_OFFSETS[lang]
    return lambda rom: {"PartyBgGfx": (gfx, 0), "StatusGfx": (status, 0), "FontSmall": (font, 0x8000)}


# (Kotlin name, symbol, LZ77? - True / False / "smol") per game.
GAMES = {
    "FR": ("pokefirered/pokefirered_rev1.gba", "pokefirered/pokefirered_rev1.elf", [
        ("FR_PARTY_BG_GFX", "gPartyMenuBg_Gfx", True),
        ("FR_PARTY_BG_PAL", "gPartyMenuBg_Pal", True),
        ("FR_PARTY_BG_MAP", "gPartyMenuBg_Tilemap", True),
        ("FR_SLOT_MAIN", "sSlotTilemap_Main", False),
        ("FR_SLOT_NO_HP", "sSlotTilemap_MainNoHP", False),
        ("FR_BALL_GFX", "gPartyMenuPokeball_Gfx", True),
        ("FR_BALL_PAL", "gPartyMenuPokeball_Pal", True),
        ("FR_STATUS_GFX", "gStatusGfx_Icons", True),
        ("FR_STATUS_PAL", "gStatusPal_Icons", True),
        ("FR_FONT_SMALL", "sFontSmallLatinGlyphs", False),
        # The held-item marker by a party slot's icon: an item, then Mail (8x8 each), and its palette (the same in Emerald).
        # The type tags (a 128x128 sheet, the games' own list of 32x12 tags) and their colours.
        ("FR_TYPE_TAGS_GFX", "gMenuInfoElements_Gfx", False),
        ("FR_TYPE_TAGS_PAL", "gMenuInfoElements2_Pal", False),
        ("FR_HOLD_GFX", "sHeldItemGfx", False),
        ("FR_HOLD_PAL", "sHeldItemPalette", False),
        ("FR_REGION_GFX", "sRegionMap_Gfx", True),
        ("FR_REGION_PAL", "sRegionMap_Pal", False),
        ("FR_KANTO_MAP", "sKanto_Tilemap", True),
        ("FR_SEVII123_MAP", "sSevii123_Tilemap", True),
        ("FR_SEVII45_MAP", "sSevii45_Tilemap", True),
        ("FR_SEVII67_MAP", "sSevii67_Tilemap", True),
        ("FR_PLAYER_RED_GFX", "sPlayerIcon_Red", True),
        ("FR_PLAYER_PAL", "sPlayerIcon_RedPal", False), # sPlayerIcon_LeafPal is the same colours
        ("FR_PLAYER_LEAF_GFX", "sPlayerIcon_Leaf", True),
        # The trainer card (src/trainer_card.c): card tiles + front/back/backdrop
        # tilemaps, a palette per star count, the female backdrop palette,
        # badges, the player's front pic, and FONT_NORMAL to print on it.
        ("FR_CARD_GFX", "gKantoTrainerCard_Gfx", True),
        ("FR_CARD_FRONT", "sKantoTrainerCardFront_Tilemap", True),
        ("FR_CARD_BACK", "sKantoTrainerCardBack_Tilemap", True),
        ("FR_CARD_BG", "sKantoTrainerCardBg_Tilemap", True),
        ("FR_CARD_PAL0", "gKantoTrainerCardBlue_Pal", False),
        ("FR_CARD_PAL1", "sKantoTrainerCardGreen_Pal", False),
        ("FR_CARD_PAL2", "sKantoTrainerCardBronze_Pal", False),
        ("FR_CARD_PAL3", "sKantoTrainerCardSilver_Pal", False),
        ("FR_CARD_PAL4", "sKantoTrainerCardGold_Pal", False),
        ("FR_CARD_FEMALE_PAL", "sKantoTrainerCardFemaleBg_Pal", False),
        ("FR_CARD_BADGES_PAL", "sKantoTrainerCardBadges_Pal", False),
        ("FR_CARD_BADGES_GFX", "sKantoTrainerCardBadges_Gfx", True),
        ("CARD_STAR_PAL", "sTrainerCardStar_Pal", False), # the same in Emerald
        ("FR_CARD_STICKERS_GFX", "sTrainerCardStickers_Gfx", True),
        ("FR_CARD_STICKER_PAL1", "sTrainerCardStickerPal1", False),
        ("FR_CARD_STICKER_PAL2", "sTrainerCardStickerPal2", False),
        ("FR_CARD_STICKER_PAL3", "sTrainerCardStickerPal3", False),
        ("FR_CARD_STICKER_PAL4", "sTrainerCardStickerPal4", False),
        ("FR_PIC_RED", "gTrainerFrontPic_Red", True),
        ("FR_PIC_RED_PAL", "gTrainerPalette_Red", True),
        ("FR_PIC_LEAF", "gTrainerFrontPic_Leaf", True),
        ("FR_PIC_LEAF_PAL", "gTrainerPalette_Leaf", True),
        ("FR_FONT_NORMAL", "sFontNormalLatinGlyphs", False),
        ("FR_FONT_NORMAL_WIDTHS", "sFontNormalLatinGlyphWidths", False),
    ]),
    "EM": ("pokeemerald/pokeemerald.gba", "pokeemerald/pokeemerald.elf", [
        ("EM_TYPE_TAGS_GFX", "gMenuInfoElements_Gfx", False),
        ("EM_TYPE_TAGS_PAL", "gMenuInfoElements2_Pal", False),
        ("EM_PARTY_BG_GFX", "gPartyMenuBg_Gfx", True),
        ("EM_PARTY_BG_PAL", "gPartyMenuBg_Pal", True),
        ("EM_PARTY_BG_MAP", "gPartyMenuBg_Tilemap", True),
        ("EM_SLOT_MAIN", "sSlotTilemap_Main", False),
        ("EM_SLOT_NO_HP", "sSlotTilemap_MainNoHP", False),
        ("EM_BALL_GFX", "gPartyMenuPokeball_Gfx", True),
        ("EM_BALL_PAL", "gPartyMenuPokeball_Pal", True),
        ("EM_STATUS_GFX", "gStatusGfx_Icons", True),
        ("EM_STATUS_PAL", "gStatusPal_Icons", True),
        ("EM_FONT_SMALL", "gFontSmallLatinGlyphs", False),
        ("EM_REGION_GFX", "sRegionMapBg_GfxLZ", True),
        ("EM_REGION_PAL", "sRegionMapBg_Pal", False),
        ("EM_REGION_MAP", "sRegionMapBg_TilemapLZ", True),
        ("EM_PLAYER_BRENDAN_GFX", "sRegionMapPlayerIcon_BrendanGfx", False),
        ("EM_PLAYER_BRENDAN_PAL", "sRegionMapPlayerIcon_BrendanPal", False),
        ("EM_PLAYER_MAY_GFX", "sRegionMapPlayerIcon_MayGfx", False),
        ("EM_PLAYER_MAY_PAL", "sRegionMapPlayerIcon_MayPal", False),
        ("EM_CARD_GFX", "gHoennTrainerCard_Gfx", True),
        ("EM_CARD_FRONT", "gHoennTrainerCardFront_Tilemap", True),
        ("EM_CARD_BACK", "gHoennTrainerCardBack_Tilemap", True),
        ("EM_CARD_BG", "gHoennTrainerCardBg_Tilemap", True),
        ("EM_CARD_PAL0", "gHoennTrainerCardGreen_Pal", False),
        ("EM_CARD_PAL1", "sHoennTrainerCardBronze_Pal", False),
        ("EM_CARD_PAL2", "sHoennTrainerCardCopper_Pal", False),
        ("EM_CARD_PAL3", "sHoennTrainerCardSilver_Pal", False),
        ("EM_CARD_PAL4", "sHoennTrainerCardGold_Pal", False),
        ("EM_CARD_FEMALE_PAL", "sHoennTrainerCardFemaleBg_Pal", False),
        ("EM_CARD_BADGES_PAL", "sHoennTrainerCardBadges_Pal", False),
        ("EM_CARD_BADGES_GFX", "sHoennTrainerCardBadges_Gfx", True),
        ("EM_PIC_BRENDAN", "gTrainerFrontPic_Brendan", True),
        ("EM_PIC_BRENDAN_PAL", "gTrainerPalette_Brendan", True),
        ("EM_PIC_MAY", "gTrainerFrontPic_May", True),
        ("EM_PIC_MAY_PAL", "gTrainerPalette_May", True),
        ("EM_FONT_NORMAL", "gFontNormalLatinGlyphs", False),
        ("EM_FONT_NORMAL_WIDTHS", "gFontNormalLatinGlyphWidths", False),
    ]),
    "UB": (UNBOUND_ROM, None, [
        ("UB_PIC_MALE", "PicMale", True),
        ("UB_PIC_MALE_PAL", "PalMale", True),
        ("UB_PIC_FEMALE", "PicFemale", True),
        ("UB_PIC_FEMALE_PAL", "PalFemale", True),
        ("CFRU_PARTY_BG_GFX", "PartyBgGfx", True),
        ("CFRU_PARTY_BG_PAL", "PartyBgPal", True),
        ("CFRU_PARTY_BG_MAP", "PartyBgMap", True),
        ("CFRU_SLOT_MAIN", "SlotMain", False),
        ("CFRU_SLOT_NO_HP", "SlotNoHp", False),
        ("CFRU_SLOT_EMPTY", "SlotEmpty", False),
        ("UB_STATUS_GFX", "StatusGfx", True),
    ]),
    "UBFR": (UNBOUND_FR_ROM, None, [
        ("UBFR_PARTY_BG_GFX", "PartyBgGfx", True),
        ("UBFR_STATUS_GFX", "StatusGfx", True),
        ("UBFR_FONT_SMALL", "FontSmall", False),
    ]),
    "RR": (RADICAL_RED_ROM, None, [("RR_STATUS_GFX", "StatusGfx", True)]),
    "OD": (ODYSSEY_ROM, None, [("OD_STATUS_GFX", "StatusGfx", True)]),
    "AM": (AMETHYST_ROM, None, [("AM_STATUS_GFX", "StatusGfx", True)]),
    # (its status icons are Odyssey's bytes: OD_STATUS_GFX finds them)
    "SS": (SWORD_SHIELD_ROM, None, [("SS_PARTY_BG_PAL", "PartyBgPal", True)]),
    "LGO": (LETS_GO_ROM, None, [
        ("LGO_PARTY_BG_GFX", "PartyBgGfx", True),
        ("LGO_PARTY_BG_PAL", "PartyBgPal", True),
        ("LGO_PARTY_BG_MAP", "PartyBgMap", True),
        ("LGO_STATUS_GFX", "StatusGfx", True),
    ]),
    "LZ": (LAZARUS_ROM, None, [
        ("LZ_REGION_GFX", "RegionGfx", True),
        ("LZ_REGION_PAL", "RegionPal", False),
        ("LZ_REGION_MAP", "RegionMap", True),
        ("LZ_CARD_GFX", "CardGfx", True),
        ("LZ_CARD_FRONT", "CardFront", True),
        ("LZ_CARD_BACK", "CardBack", True),
        ("LZ_CARD_PAL0", "CardPal0", False),
        ("LZ_CARD_PAL1", "CardPal1", False),
        ("LZ_CARD_BADGES_PAL", "BadgesPal", False),
        ("LZ_CARD_BADGES_GFX", "BadgesGfx", True),
        ("LZ_PIC_MALE", "PicMale", True),
        ("LZ_PIC_MALE_PAL", "PicMalePal", True),
        ("LZ_PIC_FEMALE", "PicFemale", True),
        ("LZ_PIC_FEMALE_PAL", "PicFemalePal", True),
        ("LZ_FONT_NORMAL", "FontNormal", False),
        ("LZ_FONT_NORMAL_WIDTHS", "FontNormalWidths", False),
    ]),
    "SGL": (SEAGLASS_ROM, None, [
        ("SGL_REGION_GFX", "RegionGfx", True),
    ]),
    "IMP": (IMPERIUM_ROM, None, [
        ("IMP_REGION_GFX", "RegionGfx", True),
        ("IMP_REGION_MAP", "RegionMap", True),
        ("IMP_FONT_NORMAL", "FontNormal", False),
        ("IMP_FONT_NORMAL_WIDTHS", "FontNormalWidths", False),
    ]),
    "DSM": (DREAMSTONE_ROM, None, [
        ("DSM_REGION_GFX", "RegionGfx", True),
        ("DSM_REGION_MAP", "RegionMap", True),
        ("DSM_REGION_PAL", "RegionPal", False),
    ]),
    "WSH": (WISHES_ROM, None, [
        ("WSH_REGION_GFX", "RegionGfx", "smol"),
        ("WSH_REGION_MAP", "RegionMap", "smol"),
        ("WSH_REGION_PAL", "RegionPal", False),
    ]),
    "TR3": (TEAM_ROCKET_V3_ROM, None, [
        row for region in ("KANTO", "SEVII123", "SEVII45", "SEVII67", "JOHTO", "HOENN", "SINNOH")
        for row in ((f"TR3_{region}_GFX", f"{region.title()}Gfx", "smol"), (f"TR3_{region}_MAP", f"{region.title()}Map", "smol"),
                    (f"TR3_{region}_PAL", f"{region.title()}Pal", False))
    ]),
    "MMY": (MONOMYTH_ROM, None, [
        ("MMY_REGION_GFX", "RegionGfx", True),
        ("MMY_REGION_MAP", "RegionMap", True),
        ("MMY_REGION_PAL", "RegionPal", False),
        ("MMY_PLAYER_GFX", "PlayerGfx", True),
        ("MMY_PLAYER_PAL", "PlayerPal", False),
    ]),
    "RW": (ROWE_ROM, None, [
        ("RW_REGION_PAL", "RegionPal", False),
        ("RW_REGION_GFX", "RegionGfx", True),
        ("RW_REGION_MAP", "RegionMap", True),
        ("RW_KANTO_GFX", "KantoGfx", True),
        ("RW_KANTO_MAP", "KantoMap", True),
        ("RW_SEVII_GFX", "SeviiGfx", True),
        ("RW_SEVII_MAP", "SeviiMap", True),
    ]),
    "HNS": (HNS_ROM, None, [
        ("HNS_REGION_GFX", "RegionGfx", "smol"),
        ("HNS_REGION_MAP", "RegionMap", "smol"),
        ("HNS_PARTY_BG_GFX", "PartyBgGfx", "smol"),
        ("HNS_PARTY_BG_PAL", "PartyBgPal", False),
        ("HNS_PARTY_BG_MAP", "PartyBgMap", "smol"),
        ("HNS_BALL_GFX", "BallGfx", "smol"),
        ("HNS_BALL_PAL", "BallPal", False),
        ("HNS_STATUS_GFX", "StatusGfx", "smol"),
        ("HNS_STATUS_PAL", "StatusPal", False),
        ("HNS_FONT_SMALL", "FontSmall", False),
    ]),
    "QTZ": (QUETZAL_ROM, None, [
        ("QTZ_REGION_PAL", "RegionPal", False),
        ("QTZ_KANTO_GFX", "KantoGfx", True),
        ("QTZ_KANTO_MAP", "KantoMap", True),
        ("QTZ_SEVII123_GFX", "Sevii123Gfx", True),
        ("QTZ_SEVII123_MAP", "Sevii123Map", True),
        ("QTZ_SEVII45_GFX", "Sevii45Gfx", True),
        ("QTZ_SEVII45_MAP", "Sevii45Map", True),
        ("QTZ_SEVII67_GFX", "Sevii67Gfx", True),
        ("QTZ_SEVII67_MAP", "Sevii67Map", True),
        ("QTZ_JOHTO_GFX", "JohtoGfx", True),
        ("QTZ_JOHTO_MAP", "JohtoMap", True),
    ]),
    "GZ": (GLAZED_ROM, None, [
        ("GZ_REGION_GFX", "RegionGfx", True),
        ("GZ_REGION_PAL", "RegionPal", False),
        ("GZ_REGION_MAP", "RegionMap", True),
        ("GZ_CARD_BADGES_PAL", "BadgesPal", False),
        ("GZ_CARD_BADGES_GFX", "BadgesGfx", True),
        ("GZ_PIC_MALE", "PicMale", True),
        ("GZ_PIC_MALE_PAL", "PicMalePal", True),
        ("GZ_PIC_FEMALE", "PicFemale", True),
        ("GZ_PIC_FEMALE_PAL", "PicFemalePal", True),
        ("GZ_FONT_NORMAL", "FontNormal", False),
    ]),
    # Pokémon Emerald Legacy v1.1.4: its source built with agbcc (= the release, byte for byte), so its ELF names the
    # one card blob retail doesn't have - the black card for a fifth star (catching MEW), sHoennTrainerCardBlack_Pal.
    # Every other card / party / map blob is retail Emerald's, byte for byte.
    "LG": ("pokelegacy/pokeemerald.gba", "pokelegacy/pokeemerald.elf", [
        ("LG_CARD_PAL5", "sHoennTrainerCardBlack_Pal", False),
    ]),
    "RY": (RECHARGED_YELLOW_ROM, None, [
        ("RY_PIC_MALE", "PicMale", True),
        ("RY_PIC_MALE_PAL", "PicMalePal", True),
        ("RY_PIC_FEMALE", "PicFemale", True),
        ("RY_PIC_FEMALE_PAL", "PicFemalePal", True),
        ("RY_FONT_NORMAL", "FontNormal", False),
        ("RY_FONT_NORMAL_WIDTHS", "FontNormalWidths", False),
    ]),
    "MM": (MARIOMON_ROM, None, [
        ("MM_REGION_GFX", "RegionGfx", True),
        ("MM_REGION_PAL", "RegionPal", False),
        ("MM_REGION_MAP", "RegionMap", True),
        ("MM_PLAYER_MARIO_GFX", "PlayerGfx", False),
        ("MM_PLAYER_MARIO_PAL", "PlayerPal", False),
    ]),
    "RE": (RECHARGED_EMERALD_ROM, None, [
        ("RE_PARTY_BG_GFX", "PartyBgGfx", True),
        ("RE_PARTY_BG_PAL", "PartyBgPal", True),
        ("RE_PARTY_BG_MAP", "PartyBgMap", True),
        ("RE_STATUS_GFX", "StatusGfx", True),
        ("RE_STATUS_PAL", "StatusPal", True),
        ("RE_FONT_SMALL", "FontSmall", False),
        ("RE_REGION_GFX", "RegionGfx", True),
        ("RE_REGION_MAP", "RegionMap", True),
        ("RE_REGION_PAL", "RegionPal", False),
        ("RE_PLAYER_BRENDAN_GFX", "BrendanGfx", False),
        ("RE_PLAYER_BRENDAN_PAL", "BrendanPal", False),
        ("RE_PLAYER_MAY_GFX", "MayGfx", False),
        ("RE_PLAYER_MAY_PAL", "MayPal", False),
    ]),
    "SG": (SOULGOLD_ROM, None, [
        ("SG_BAG_STARS_GFX", "BagStarsGfx", "smol"),
        ("SG_BAG_STARS_MAP", "BagStarsMap", "smol"),
        ("SG_BAG_STARS_PAL", "BagStarsPal", False),
        ("SG_REGION_GFX", "RegionGfx", "smol"),
        ("SG_REGION_MAP", "RegionMap", "smol"),
        ("SG_REGION_PAL", "RegionPal", False),
        ("SG_CARD_GFX", "CardGfx", "smol"),
        ("SG_CARD_FRONT", "CardFront", "smol"),
        ("SG_CARD_BACK", "CardBack", "smol"),
        ("SG_CARD_BG", "CardBg", "smol"),
        ("SG_CARD_PAL0", "CardPal0", False),
        ("SG_CARD_PAL1", "CardPal1", False),
        ("SG_CARD_PAL2", "CardPal2", False),
        ("SG_CARD_PAL3", "CardPal3", False),
        ("SG_CARD_PAL4", "CardPal4", False),
        ("SG_CARD_FEMALE_PAL", "CardFemalePal", False),
        ("SG_CARD_BADGES_PAL", "BadgesPal", False),
        ("SG_CARD_BADGES_GFX", "BadgesGfx", "smol"),
        ("SG_PIC_MALE", "PicMale", "smol"),
        ("SG_PIC_MALE_PAL", "PicMalePal", False),
        ("SG_PIC_FEMALE", "PicFemale", "smol"),
        ("SG_PIC_FEMALE_PAL", "PicFemalePal", False),
        ("SG_FONT_NORMAL", "FontNormal", False),
        ("SG_FONT_NORMAL_WIDTHS", "FontNormalWidths", False),
    ]),
}

for _lang, _rom in EMERALD_EU_ROMS.items():
    GAMES["EM" + _lang] = (_rom, None, [
        (f"EM_{_lang}_PARTY_BG_GFX", "PartyBgGfx", True),
        (f"EM_{_lang}_STATUS_GFX", "StatusGfx", True),
        (f"EM_{_lang}_FONT_SMALL", "FontSmall", False),
    ])

# The European FireReds redrew the same three party-menu pieces (the HP label PS / KP / PV, the
# status abbreviations, a few glyphs): scripts/port_retail.py finds them (build/ports/ports.json,
# "art") and RomArt writes them to partyfr_<lang>/. LeafGreen's are byte for byte FireRed's.
PORTS_JSON = os.path.join(HERE, "..", "build", "ports", "ports.json")
FR_LANGS = {"S": "ES", "D": "DE", "F": "FR", "I": "IT"}


def fireRed_ports():
    if not os.path.exists(PORTS_JSON):
        return {}
    import json
    out = {}
    for p in json.load(open(PORTS_JSON, encoding="utf-8")):
        if p["code"][:3] == "BPR" and p["code"][3] in FR_LANGS and p.get("art"):
            out[FR_LANGS[p["code"][3]]] = p
    return out


for _lang, _p in fireRed_ports().items():
    GAMES["FR" + _lang] = (_p["path"], None, [
        (f"FR_{_lang}_PARTY_BG_GFX", "PartyBgGfx", True),
        (f"FR_{_lang}_STATUS_GFX", "StatusGfx", True),
        (f"FR_{_lang}_FONT_SMALL", "FontSmall", False),
    ])


def fireRed_symbols(p):
    a = p["art"]
    return lambda rom: {"PartyBgGfx": (a["party_bg"][0] - 0x08000000, 0), "StatusGfx": (a["status"][0] - 0x08000000, 0),
                        "FontSmall": (a["font"][0] - 0x08000000, 0x4000)}


# The binary hacks' made-up symbols (no ELF).
ROM_SYMBOLS = {"RY": recharged_yellow_symbols, "UB": unbound_symbols, "UBFR": unbound_fr_symbols, "RR": cfru_symbols, "OD": cfru_symbols, "AM": cfru_symbols, "SS": cfru_symbols, "LGO": cfru_symbols, "LZ": lazarus_symbols, "SGL": seaglass_symbols, "SG": soulgold_symbols,
               "GZ": glazed_symbols, "IMP": imperium_symbols, "DSM": dreamstone_symbols, "WSH": wishes_symbols, "MMY": monomyth_symbols, "TR3": team_rocket_v3_symbols, "QTZ": quetzal_symbols, "HNS": hns_symbols, "RW": rowe_symbols, "MM": mariomon_symbols, "RE": recharged_emerald_symbols,
               **{"EM" + lang: emerald_eu_symbols(lang) for lang in EMERALD_EU_ROMS},
               **{"FR" + lang: fireRed_symbols(p) for lang, p in fireRed_ports().items()}}


def symbols(elf):
    out = {}
    for line in subprocess.run(["nm", "-S", elf], capture_output=True, text=True, check=True).stdout.splitlines():
        p = line.split()
        if len(p) == 4:
            out[p[3]] = (int(p[0], 16) - 0x08000000, int(p[1], 16))
    return out


def existing_rows():
    """The rows already in RomArtSigsGen.kt, by blob name."""
    if not os.path.exists(KOTLIN):
        return {}
    return {line.strip().split("(")[0]: line for line in open(KOTLIN) if line.startswith("    ") and "(" in line
            and line.strip().split("(")[0].isupper()}


def main():
    rows = []
    kept = existing_rows()
    if not fireRed_ports():  # no ports.json here: the European FireReds' rows stay as they were
        GAMES.update({"FR" + l: ("/nonexistent", None, [(n, "", False) for n in kept if n.startswith(f"FR_{l}_")])
                      for l in FR_LANGS.values() if any(n.startswith(f"FR_{l}_") for n in kept)})
    for game, (rom_path, elf, blobs) in GAMES.items():
        path = os.path.join(ROOT, rom_path)
        if not elf and not os.path.exists(path):
            # A hack's ROM that isn't on this machine: keep its fingerprints as they were.
            missing = [name for name, _, _ in blobs if name not in kept]
            if missing:
                raise SystemExit(f"{path} missing and no earlier rows for {missing}")
            rows += [kept[name].rstrip("\n") for name, _, _ in blobs]
            print(f"{game}: ROM not here, kept its {len(blobs)} rows")
            continue
        rom = open(path, "rb").read()
        syms = symbols(os.path.join(ROOT, elf)) if elf else ROM_SYMBOLS[game](rom)
        for name, sym, lz in blobs:
            off, size = syms[sym]
            if lz == "smol":
                data, raw_len = smol.decode(rom[off:off + 0x10000])
            else:
                data, raw_len = lz77(rom, off) if lz else (rom[off:off + size], size)
            # The first window (2-byte steps) seen no more often than the blob
            # itself (some are stored twice): an all-blank font glyph would
            # otherwise match everywhere.
            copies = rom.count(rom[off:off + raw_len])
            # The most varied window first; glyph-width tables and mostly-blank
            # palettes hold few distinct values, so settle for 4 there.
            for distinct in (6, 4):
                w = next((w for w in range(0, raw_len - 15, 2)
                          if len(set(rom[off + w:off + w + 16])) >= distinct
                          and rom.count(rom[off + w:off + w + 16]) == copies), None)
                if w is not None:
                    break
            else:
                # No window of its own (Recharged Emerald keeps two FONT_SMALLs a glyph apart): one a few copies
                # share - RomArt checks each hit's CRC and scans on, so the other copy is passed over.
                w = next((w for w in range(0, raw_len - 15, 2) if len(set(rom[off + w:off + w + 16])) >= 6
                          and rom.count(rom[off + w:off + w + 16]) <= copies + 2), None)
                if w is None:
                    raise SystemExit(f"{name}: no unique window")
            win = rom[off + w:off + w + 16]
            h = head(win)
            h = h - (1 << 64) if h >= 1 << 63 else h
            rows.append(f'    {name}({"false" if lz == "smol" else str(lz).lower()}, {len(data)}, 0x{zlib.crc32(data):08X}L, {w}, {h}L, 0x{pre(win):04X}'
                        f'{", smol = true" if lz == "smol" else ""}),')
            print(f"{name}: {sym} @{off:#x} raw {raw_len} decoded {len(data)} window +{w}")
    kt = """package com.pokedaisy.app.companion.data

// GENERATED by scripts/gen_rom_art_sigs.py from the pinned pokefirered /
// pokeemerald builds - do not hand-edit; rerun the script instead.

/** A graphics blob [RomArt] looks for in the loaded ROM - see the script for the fields. */
enum class RomBlob(val lz: Boolean, val size: Int, val crc: Long, val headOff: Int, val head: Long, val pre: Int, val smol: Boolean = false) {
""" + "\n".join(rows) + "\n}\n"
    # Long literals: 0x... > Long.MAX_VALUE don't compile as Long, so the CRCs stay
    # under 2^32 (fine) and the heads are emitted signed (above).
    open(KOTLIN, "w").write(kt)
    print("wrote", KOTLIN)


if __name__ == "__main__":
    main()
