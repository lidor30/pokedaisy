#!/usr/bin/env python3
"""Fingerprints of FireRed's and Emerald's text look - the dialogue box (its tiles and palette) and the normal font
(glyphs and widths) - for TEXT LOOK (companion/data/TextLook.kt): one game's text boxes and letters in the other,
taken from the player's own ROM of that game. Nothing of the ROM is bundled; the fields are gen_player_look_sigs.py's.

What each graphic is comes from the pinned decomps: FireRed's built .4bpp / .gbapal / .fwlatfont files and the widths
arrays in its text.c, Emerald's PNGs converted as its build does (gbagfx's latfont: 16x16 glyphs, four 2bpp tiles each)
and the widths in its fonts.c. Every one is then found in the retail ROMs (host_roms.conf's firered_vanilla /
emerald_vanilla); FireRed keeps its font five times (normal, two copies, male, female: the same glyphs).

Usage: scripts/gen_text_look_sigs.py [ROM ...]   (extra ROMs are only reported: which parts each one holds)
"""
import os
import re
import sys
import zlib

from decomps import decomp
from gen_player_look_sigs import content, find_all, host_rom, png_4bpp, window
from gen_rom_art_sigs import head, pre

HERE = os.path.dirname(os.path.abspath(__file__))
KOTLIN = os.path.join(HERE, "../shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/TextLookSigsGen.kt")
FR = decomp("pokefirered", "graphics")
EM = decomp("pokeemerald", "graphics")
FR_SRC = decomp("pokefirered", "src")
EM_SRC = decomp("pokeemerald", "src")


def latfont(path):
    """gbagfx png -> latfont: per glyph its four 8x8 tiles (top left, top right, bottom left, bottom right), each row
    two bytes of 2bpp pixels, the first pixel in the high bits, the bytes swapped."""
    from PIL import Image
    img = Image.open(path)
    px = img.load()
    out = bytearray()
    for row in range(img.size[1] // 16):
        for col in range(16):
            for t in range(4):
                x0, y0 = col * 16 + (t & 1) * 8, row * 16 + (t >> 1) * 8
                for y in range(8):
                    p = [px[x0 + x, y0 + y] & 3 for x in range(8)]
                    a = p[0] << 6 | p[1] << 4 | p[2] << 2 | p[3]
                    b = p[4] << 6 | p[5] << 4 | p[6] << 2 | p[7]
                    out += bytes([b, a])
    return bytes(out)


def widths(path, name):
    src = open(path).read()
    body = re.search(name + r"\[\]\s*=\s*\{(.*?)\};", src, re.S).group(1)
    return bytes(int(v, 0) for v in re.findall(r"0x[0-9A-Fa-f]+|\d+", re.sub(r"//.*|/\*.*?\*/", "", body, flags=re.S)))


# (style, part, data): the box's tiles (FireRed's 18, Emerald's 14, in the order the game numbers them), its palette
# (FireRed's dialogue uses stdpal_0), the font's 512 glyphs and their widths.
PARTS = [
    ("FIRERED", "BOX", content(f"{FR}/text_window/menu_message.4bpp")),
    ("FIRERED", "BOX_PAL", content(f"{FR}/text_window/stdpal_0.gbapal")),
    ("FIRERED", "FONT", content(f"{FR}/fonts/latin_normal.fwlatfont")),
    ("FIRERED", "FONT_WIDTHS", widths(f"{FR_SRC}/text.c", "sFontNormalLatinGlyphWidths")),
    ("EMERALD", "BOX", png_4bpp(f"{EM}/text_window/message_box.png", 1, 1)),
    ("EMERALD", "BOX_PAL", content(f"{EM}/text_window/message_box.png")),
    ("EMERALD", "FONT", latfont(f"{EM}/fonts/latin_normal.png")),
    ("EMERALD", "FONT_WIDTHS", widths(f"{EM_SRC}/fonts.c", "gFontNormalLatinGlyphWidths")),
]


# Emerald's two dialogue frame routines (menu.c's WindowFunc_DrawDialogueFrame and
# WindowFunc_DrawDialogFrameWithCustomTileAndPalette) in retail Emerald (USA, Europe), found by a fingerprint like the
# graphics' - no code bytes are kept but an 8-byte anchor: its first 8 bytes no mask touches, then the CRC32 of the whole
# routine with BL pairs and address-like pool words zeroed (Emerald in other languages, Kaizo and Legacy have the same
# code elsewhere). TextLook finds them to swap in FireRed's frame (scripts/text_look_dialogue.s).
EM_CODE = {"DIALOGUE_FRAME": (0x1975F4, 0x1C8), "DIALOGUE_FRAME_CUSTOM": (0x197BB4, 0x244)}


def mask(code):
    """Which bytes of [code] a build can change: each byte of a BL pair or a 4-aligned word that looks like an address
    (TextLook.mask is this, in Kotlin)."""
    import struct
    keep = [True] * len(code)
    i = 0
    while i + 4 <= len(code):
        h, h2 = struct.unpack_from("<HH", code, i)
        if (h & 0xF800) == 0xF000 and (h2 & 0xF800) == 0xF800:
            keep[i:i + 4] = [False] * 4
            i += 4
            continue
        i += 2
    for i in range(0, len(code) - 3, 4):
        if code[i + 3] in (2, 3, 8, 9):
            keep[i:i + 4] = [False] * 4
    return keep


def code_sig(code):
    """CodeSig(anchor, its 8 bytes, length, CRC32 of the code with the masked bytes zeroed)."""
    keep = mask(code)
    anchor = next(i for i in range(len(code) - 7) if all(keep[i:i + 8]))
    crc = zlib.crc32(bytes(b if k else 0 for b, k in zip(code, keep)))
    return f'CodeSig({anchor}, "{code[anchor:anchor + 8].hex().upper()}", {len(code)}, 0x{crc:08X}L)'


def main():
    roms = {"FIRERED": open(host_rom("firered_vanilla"), "rb").read(), "EMERALD": open(host_rom("emerald_vanilla"), "rb").read()}
    rows = []
    for style, part, data in PARTS:
        rom = roms[style]
        hits = find_all(rom, data, False)
        if not hits:
            raise SystemExit(f"{style} {part}: not in the retail ROM")
        off, raw_len = hits[0]
        w = window(rom, off, raw_len)
        win = rom[off + w:off + w + 16]
        h = head(win)
        h = h - (1 << 64) if h >= 1 << 63 else h
        rows.append(f"    {style}_{part}(LookEngine.{style}, TextPart.{part}, {len(data)}, 0x{zlib.crc32(data):08X}L, {w}, {h}L, 0x{pre(win):04X}),")
        print(f"{style} {part}: {len(hits)} in retail @{', '.join(hex(o) for o, _ in hits)}, {len(data)} bytes, window +{w}")
    em = roms["EMERALD"]
    code_rows = "".join(f'    val {k} = {code_sig(em[a:a + n])}\n' for k, (a, n) in EM_CODE.items())
    kt = """package com.pokedaisy.app.companion.data

// GENERATED by scripts/gen_text_look_sigs.py from the pinned pokefirered / pokeemerald
// graphics, found in the retail ROMs - do not hand-edit; rerun the script instead.

/** A piece of a game's text look [TextLook] looks for in a ROM - see the script for the fields. */
enum class TextBlob(
    val style: LookEngine, val part: TextPart, override val size: Int, override val crc: Long, override val headOff: Int,
    override val head: Long, override val pre: Int,
) : LookFingerprint {
""" + "\n".join(rows) + "\n}\n" + """
/** Code [TextLook] finds in an Emerald ROM, by fingerprint (see the script and [CodeSig]). */
object TextLookCode {
""" + code_rows + "}\n"
    open(KOTLIN, "w").write(kt)
    print("wrote", KOTLIN)
    for path in sys.argv[1:]:
        rom = open(path, "rb").read()
        have = [f"{s}.{p}({len(find_all(rom, d, False))})" for s, p, d in PARTS if find_all(rom, d, False)]
        print(f"{os.path.basename(path)}: {' '.join(have) or '-'}")


if __name__ == "__main__":
    main()
