#!/usr/bin/env python3
"""Writes 3ds/core/pd_map_gen.c from the app's region-map tables:

- the fingerprints RomArt finds art by in a ROM (RomArtSigsGen.kt - only
  the blobs the 3DS MAP, TRAINER CARD and PARTY need),
- the cursor grids (RegionMapLayoutsGen.kt),
- every map section's page and rectangle (MapSecData*.kt), the Game Boy games' too, and Gold / Silver's
  landmark per map (MapSecDataGoldSilver.kt).

    python3 3ds/tools/gen_map_tables.py
"""
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA = os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/data")
OUT = os.path.join(ROOT, "3ds/core/pd_map_gen.c")

BLOBS = [
    "FR_REGION_GFX", "FR_REGION_PAL", "FR_KANTO_MAP", "FR_SEVII123_MAP", "FR_SEVII45_MAP", "FR_SEVII67_MAP",
    "FR_PLAYER_RED_GFX", "FR_PLAYER_LEAF_GFX", "FR_PLAYER_PAL",
    "EM_REGION_GFX", "EM_REGION_PAL", "EM_REGION_MAP",
    "EM_PLAYER_BRENDAN_GFX", "EM_PLAYER_BRENDAN_PAL", "EM_PLAYER_MAY_GFX", "EM_PLAYER_MAY_PAL",
    # The TRAINER CARD (TrainerCardArt.kt's KANTO and HOENN blobs).
    "FR_CARD_GFX", "FR_CARD_FRONT", "FR_CARD_BACK", "FR_CARD_BG",
    "FR_CARD_PAL0", "FR_CARD_PAL1", "FR_CARD_PAL2", "FR_CARD_PAL3", "FR_CARD_PAL4",
    "FR_CARD_FEMALE_PAL", "FR_CARD_BADGES_PAL", "FR_CARD_BADGES_GFX",
    "FR_CARD_STICKERS_GFX", "FR_CARD_STICKER_PAL1", "FR_CARD_STICKER_PAL2", "FR_CARD_STICKER_PAL3",
    "FR_CARD_STICKER_PAL4",
    "FR_PIC_RED", "FR_PIC_RED_PAL", "FR_PIC_LEAF", "FR_PIC_LEAF_PAL",
    "FR_FONT_NORMAL", "FR_FONT_NORMAL_WIDTHS",
    "EM_CARD_GFX", "EM_CARD_FRONT", "EM_CARD_BACK", "EM_CARD_BG",
    "EM_CARD_PAL0", "EM_CARD_PAL1", "EM_CARD_PAL2", "EM_CARD_PAL3", "EM_CARD_PAL4",
    "EM_CARD_FEMALE_PAL", "EM_CARD_BADGES_PAL", "EM_CARD_BADGES_GFX",
    "EM_PIC_BRENDAN", "EM_PIC_BRENDAN_PAL", "EM_PIC_MAY", "EM_PIC_MAY_PAL",
    "EM_FONT_NORMAL", "EM_FONT_NORMAL_WIDTHS",
    "CARD_STAR_PAL",
    # Emerald Legacy's card (TrainerCardArt.kt LEGACY): HOENN's, plus the fifth star's (black) palette.
    "LG_CARD_PAL5",
    # Unbound's card (TrainerCardArt.kt UNBOUND): FireRed's, recoloured, with its own pics.
    "UB_PIC_MALE", "UB_PIC_MALE_PAL", "UB_PIC_FEMALE", "UB_PIC_FEMALE_PAL",
    # Lazarus's card (TrainerCardArt.kt LAZARUS): its own tiles, tilemaps, 0- and 1-star palettes, badges, pics and font.
    "LZ_CARD_GFX", "LZ_CARD_FRONT", "LZ_CARD_BACK", "LZ_CARD_PAL0", "LZ_CARD_PAL1",
    "LZ_CARD_BADGES_PAL", "LZ_CARD_BADGES_GFX",
    "LZ_PIC_MALE", "LZ_PIC_MALE_PAL", "LZ_PIC_FEMALE", "LZ_PIC_FEMALE_PAL",
    "LZ_FONT_NORMAL", "LZ_FONT_NORMAL_WIDTHS",
    # Recharged Yellow's card (TrainerCardArt.kt RECHARGED_YELLOW): FireRed's with its own pics and font.
    "RY_PIC_MALE", "RY_PIC_MALE_PAL", "RY_PIC_FEMALE", "RY_PIC_FEMALE_PAL", "RY_FONT_NORMAL", "RY_FONT_NORMAL_WIDTHS",
    # Glazed's card (TrainerCardArt.kt GLAZED): retail Emerald's with its own badges, pics and font.
    "GZ_CARD_BADGES_PAL", "GZ_CARD_BADGES_GFX", "GZ_PIC_MALE", "GZ_PIC_MALE_PAL", "GZ_PIC_FEMALE", "GZ_PIC_FEMALE_PAL",
    "GZ_FONT_NORMAL",
    # SoulGold's card (TrainerCardArt.kt SOULGOLD): its Johto art, most of it smol-compressed.
    "SG_CARD_GFX", "SG_CARD_FRONT", "SG_CARD_BACK", "SG_CARD_BG",
    "SG_CARD_PAL0", "SG_CARD_PAL1", "SG_CARD_PAL2", "SG_CARD_PAL3", "SG_CARD_PAL4",
    "SG_CARD_FEMALE_PAL", "SG_CARD_BADGES_PAL", "SG_CARD_BADGES_GFX",
    "SG_PIC_MALE", "SG_PIC_MALE_PAL", "SG_PIC_FEMALE", "SG_PIC_FEMALE_PAL", "SG_FONT_NORMAL", "SG_FONT_NORMAL_WIDTHS",
    # The games' type tags (TypeBadge): the 128x128 sheet their move lists draw 32x12 tags from, and its colours.
    "FR_TYPE_TAGS_GFX", "FR_TYPE_TAGS_PAL", "EM_TYPE_TAGS_GFX", "EM_TYPE_TAGS_PAL",
]

# Regional dexes the card counts (TrainerCard.kt), by name: written as pd_card_dex_<lower>[].
CARD_DEXES = ["GLAZED_REGIONAL_TO_NATIONAL", "LAZARUS_REGIONAL_TO_NATIONAL", "SOULGOLD_REGIONAL_TO_NATIONAL", "SOULGOLD_CARD_SKIPPED",
              "SOULGOLD_ACHIEVEMENT_IDS"]

# The party menus (RomArt's PARTIES and cfru): looked for only in the games whose style names them.
PARTY_BLOBS = [
    "FR_PARTY_BG_GFX", "FR_PARTY_BG_PAL", "FR_PARTY_BG_MAP", "FR_SLOT_MAIN", "FR_SLOT_NO_HP", "FR_BALL_GFX", "FR_BALL_PAL",
    "FR_STATUS_GFX", "FR_STATUS_PAL", "FR_FONT_SMALL", "FR_HOLD_GFX", "FR_HOLD_PAL",
    "EM_PARTY_BG_GFX", "EM_PARTY_BG_PAL", "EM_PARTY_BG_MAP", "EM_SLOT_MAIN", "EM_SLOT_NO_HP", "EM_BALL_GFX", "EM_BALL_PAL",
    "EM_STATUS_GFX", "EM_STATUS_PAL", "EM_FONT_SMALL",
    "CFRU_PARTY_BG_GFX", "CFRU_PARTY_BG_PAL", "CFRU_PARTY_BG_MAP", "CFRU_SLOT_MAIN", "CFRU_SLOT_NO_HP", "CFRU_SLOT_EMPTY",
    "UB_STATUS_GFX", "UBFR_PARTY_BG_GFX", "UBFR_STATUS_GFX", "UBFR_FONT_SMALL", "RR_STATUS_GFX", "OD_STATUS_GFX", "AM_STATUS_GFX",
    "SS_PARTY_BG_PAL", "LGO_PARTY_BG_GFX", "LGO_PARTY_BG_PAL", "LGO_PARTY_BG_MAP", "LGO_STATUS_GFX",
    "HNS_PARTY_BG_GFX", "HNS_PARTY_BG_PAL", "HNS_PARTY_BG_MAP", "HNS_BALL_GFX", "HNS_BALL_PAL", "HNS_STATUS_GFX", "HNS_STATUS_PAL",
    "HNS_FONT_SMALL",
    "RE_PARTY_BG_GFX", "RE_PARTY_BG_PAL", "RE_PARTY_BG_MAP", "RE_STATUS_GFX", "RE_STATUS_PAL", "RE_FONT_SMALL",
] + [f"{game}_{lang}_{part}" for game in ("EM", "FR") for lang in ("ES", "DE", "FR", "IT")
     for part in ("PARTY_BG_GFX", "STATUS_GFX", "FONT_SMALL")]


def family(name):
    """Which ROMs a blob is looked for in: PD_BLOB_FIRERED / _EMERALD, both, or (0) the games that name it."""
    if name in PARTY_BLOBS:
        return "0"
    if name.startswith("FR_"):
        return "PD_BLOB_FIRERED"
    if name.startswith("EM_"):
        return "PD_BLOB_EMERALD"
    return "PD_BLOB_FIRERED | PD_BLOB_EMERALD"


def read(name):
    with open(os.path.join(DATA, name), encoding="utf-8") as f:
        return f.read()


def blobs():
    text = read("RomArtSigsGen.kt")
    out = []
    for name in BLOBS + PARTY_BLOBS:
        m = re.search(r"\b" + name + r"\((true|false), (\d+), (0x[0-9A-F]+)L, (\d+), (-?\d+)L, (0x[0-9A-F]+)(, smol = true)?", text)
        if not m:
            raise SystemExit(f"gen_map_tables: no {name} in RomArtSigsGen.kt")
        lz, size, crc, head_off, head, pre, smol = m.groups()
        out.append((name, lz == "true", int(size), int(crc, 16), int(head_off), int(head) & (2**64 - 1), int(pre, 16), bool(smol)))
    return out


def layouts():
    text = read("RegionMapLayoutsGen.kt")
    result = {}
    for game, decl in (("firered", "regionLayoutsFireRed"), ("emerald", "regionLayoutsEmerald")):
        body = text[text.index(f"val {decl}"):]
        body = body[:body.index("\n)\n")]
        pages = []
        for m in re.finditer(r"RegionLayout\((\d+), (\d+), (\d+), (\d+), (0x[0-9A-F]+), listOf\((.*?)\)\)", body, re.S):
            off_x, off_y, w, h, none, layers = m.groups()
            hexes = re.findall(r'hexBytes\("([0-9a-f]+)"\)', layers)
            pages.append((int(off_x), int(off_y), int(w), int(h), int(none, 16), [bytes.fromhex(x) for x in hexes]))
        result[game] = pages
    return result


def mapsecs(name, decl):
    text = read(name)
    body = text[text.index(f"val {decl}"):]
    body = body[:body.index("\n)\n")]
    return {int(k): (int(r), int(x), int(y), int(w), int(h)) for k, r, x, y, w, h in re.findall(
        r'(\d+) to MapSecInfo\("[^"]*", (-?\d+), (\d+), (\d+), (\d+), (\d+)\)', body)}


GB_SECTIONS = (("gb_yellow", "MapSecDataYellow.kt", "mapSecDataYellow"),
               ("gb_crystal", "MapSecDataCrystal.kt", "mapSecDataCrystal"),
               ("gb_gold_silver", "MapSecDataGoldSilver.kt", "mapSecDataGoldSilver"))


def c_bytes(data):
    lines = []
    for i in range(0, len(data), 24):
        lines.append("    " + ", ".join(f"0x{b:02X}" for b in data[i:i + 24]) + ",")
    return "\n".join(lines)


def card_dexes():
    """The regional dex tables the card counts: TrainerCard.kt's `val NAME = intArrayOf(...)`."""
    text = read("TrainerCard.kt")
    out = []
    for name in CARD_DEXES:
        m = re.search(r"val " + name + r" = intArrayOf\((.*?)\)\n", text, re.S)
        if not m:
            raise SystemExit(f"gen_map_tables: no {name} in TrainerCard.kt")
        nums = [int(x) for x in re.findall(r"\d+", m.group(1))]
        out.append(f"const uint16_t pd_card_{name.lower()}[{len(nums)}] = {{")
        for i in range(0, len(nums), 20):
            out.append("    " + ", ".join(map(str, nums[i:i + 20])) + ",")
        out.append("};")
        out.append(f"const int pd_card_{name.lower()}_count = {len(nums)};")
    return out


def main():
    out = [
        "// Generated by 3ds/tools/gen_map_tables.py from the app's RomArtSigsGen.kt,",
        "// RegionMapLayoutsGen.kt and MapSecData*.kt - do not hand-edit.",
        '#include "pd_map.h"',
        "",
        "const struct pd_blob_sig pd_blob_sigs[PD_BLOB_COUNT] = {",
    ]
    for name, lz, size, crc, head_off, head, pre, smol in blobs():
        out.append(f"    [PD_{name}] = {{ {str(lz).lower()}, {size}, 0x{crc:08X}u, {head_off}, 0x{head:016X}ull, 0x{pre:04X}, "
                   f"{family(name)}, {str(smol).lower()} }},")
    out.append("};")
    out.append("")
    out.extend(card_dexes())
    out.append("")

    lay = layouts()
    for game, pages in lay.items():
        for i, (ox, oy, w, h, none, layers) in enumerate(pages):
            for l, data in enumerate(layers):
                assert len(data) == w * h, (game, i, l, len(data))
                out.append(f"static const unsigned char {game}_layout{i}_{l}[{len(data)}] = {{")
                out.append(c_bytes(data))
                out.append("};")
        out.append(f"const struct pd_region_layout pd_layouts_{game}[{len(pages)}] = {{")
        for i, (ox, oy, w, h, none, layers) in enumerate(pages):
            l1 = f"{game}_layout{i}_1" if len(layers) > 1 else "NULL"
            out.append(f"    {{ {ox}, {oy}, {w}, {h}, 0x{none:02X}, {{ {game}_layout{i}_0, {l1} }} }},")
        out.append("};")
        out.append(f"const int pd_layouts_{game}_count = {len(pages)};")
        out.append("")

    for game, name, decl in (("firered", "MapSecData.kt", "mapSecData"),
                             ("emerald", "MapSecDataEmerald.kt", "mapSecDataEmerald")):
        secs = mapsecs(name, decl)
        top = max(secs) + 1
        out.append(f"const struct pd_mapsec_rect pd_mapsec_rects_{game}[{top}] = {{")
        for i in range(top):
            if i in secs:
                r, x, y, w, h = secs[i]
                out.append(f"    {{ {r}, {x}, {y}, {w}, {h} }},")
            else:
                out.append("    { -1, 0, 0, 0, 0 },")
        out.append("};")
        out.append(f"const int pd_mapsec_rects_{game}_count = {top};")
        out.append("")

    # The Game Boy games' places (pd_gb_map.c): each one cell (x, y in 8px tiles of the 160x144 town map), Gen 1's by
    # wCurMap (Red / Blue share Yellow's map ids), Gen 2's by landmark; page = Johto 0 / Kanto 1, -1 = not on a map.
    for game, name, decl in GB_SECTIONS:
        secs = mapsecs(name, decl)
        top = max(secs) + 1
        out.append(f"const struct pd_mapsec_rect pd_mapsec_rects_{game}[{top}] = {{")
        for i in range(top):
            r, x, y, w, h = secs.get(i, (-1, 0, 0, 0, 0))
            assert x + w <= 20 and y + h <= 18, (game, i)
            out.append(f"    {{ {r}, {x}, {y}, {w}, {h} }},")
        out.append("};")
        out.append(f"const int pd_mapsec_rects_{game}_count = {top};")
        out.append("")
    # Gold / Silver's landmark per map (wMapGroup * 256 + wMapNumber), like gen_guide.py's Crystal one.
    text = read("MapSecDataGoldSilver.kt")
    body = text[text.index("val mapLandmarksGoldSilver"):]
    body = body[body.index("\n") + 1:body.index("\n)\n")]
    marks = sorted((int(k, 0), int(v)) for k, v in re.findall(r"(0x[0-9A-Fa-f]+|\d+) to (\d+)", body))
    out.append("const uint16_t pd_gb_landmarks_gold_silver[][2] = {")
    out += [f"    {{ 0x{k:04X}, {v} }}," for k, v in marks]
    out.append("};")
    out.append(f"const int pd_gb_landmarks_gold_silver_count = {len(marks)};")
    out.append("")
    # Polished Crystal's (MapSecDataPolishedCrystal.kt, a lazy map): its GUIDE world's HERE names the place by it.
    text = read("MapSecDataPolishedCrystal.kt")
    body = text[text.index("val mapLandmarksPolishedCrystal"):]
    body = body[body.index("mapOf(") + 6:body.index("\n    )\n")]
    marks = sorted((int(k, 0), int(v)) for k, v in re.findall(r"(0x[0-9A-Fa-f]+|\d+) to (\d+)", body))
    out.append("const uint16_t pd_gb_landmarks_polished[][2] = {")
    out += [f"    {{ 0x{k:04X}, {v} }}," for k, v in marks]
    out.append("};")
    out.append(f"const int pd_gb_landmarks_polished_count = {len(marks)};")
    out.append("")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print(f"wrote {os.path.relpath(OUT, ROOT)}: {len(BLOBS) + len(PARTY_BLOBS)} blobs, "
          f"{len(lay['firered'])}+{len(lay['emerald'])} layouts")


if __name__ == "__main__":
    main()
