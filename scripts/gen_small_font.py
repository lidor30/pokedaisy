#!/usr/bin/env python3
"""Writes app/src/main/assets/fonts/PixelSmall.ttf: SETTINGS > FONT's PIXEL SMALL (the default).

The glyphs are the small pixel font the Linux / 3DS HUD draws (3ds/tools/gen_font.py's SMALL_GLYPHS: our own
drawing, capitals 8 tall, letters 5 wide), here as a TrueType font on Pixel Operator's grid: a 100-unit pixel at
1600 units per em, ascent 1300 / descent 300, the capitals' bottom on its baseline. So the app's GbaTextMetrics draws
both at whole screen pixels the same way, and anything this font lacks (1/2, 1/4, kana, Hangul) falls back to Pixel
Operator and its fallbacks (PixelTypeface.kt) at the same size. Lit pixel runs become square rectangles, like
BitmapTtf's; the HUD's softened diagonal steps are left out (the app draws crisp pixels only).

    python3 scripts/gen_small_font.py          (rerun after changing SMALL_GLYPHS)
"""
import importlib.util
import os
import struct

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "app/src/main/assets/fonts/PixelSmall.ttf")
spec = importlib.util.spec_from_file_location("gen_font", os.path.join(ROOT, "3ds/tools/gen_font.py"))
gen_font = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen_font)

UPM, PX, ASC, DESC, GAP = 1600, 100, 1300, -300, 72
CAP_ROWS = 8          # the capitals' rows from the art's first row; the baseline is under the last
FAMILY = "PokeDaisy Pixel Small"


def outline(art, top=0):
    """Rectangles (x0, y0, x1, y1) in font units, one per run of lit pixels in a row of the art (its rows start
    `top` rows above the small font's top: an accented capital's mark, SMALL_GLYPHS' optional third item)."""
    out = []
    for r, line in enumerate(art, top):
        x = 0
        while x < len(line):
            if line[x] != "#":
                x += 1
                continue
            s = x
            while x < len(line) and line[x] == "#":
                x += 1
            top = (CAP_ROWS - r) * PX
            out.append((s * PX, top - PX, x * PX, top))
    return out


def build():
    chars = sorted(gen_font.SMALL_GLYPHS, key=ord)
    outs = [[]] + [outline(*gen_font.SMALL_GLYPHS[c][1:3]) for c in chars]
    advs = [4 * PX] + [(gen_font.SMALL_GLYPHS[c][0] + 1) * PX for c in chars]   # .notdef, then width + a pixel apart
    glyf = bytearray()
    loca = []
    max_p = max_c = 0
    box = [0, 0, 0, 0]
    for rects in outs:
        loca.append(len(glyf))
        if not rects:
            continue
        n = len(rects)
        max_c, max_p = max(max_c, n), max(max_p, 4 * n)
        gx0, gy0 = min(r[0] for r in rects), min(r[1] for r in rects)
        gx1, gy1 = max(r[2] for r in rects), max(r[3] for r in rects)
        box = [min(box[0], gx0), min(box[1], gy0), max(box[2], gx1), max(box[3], gy1)]
        glyf += struct.pack(">hhhhh", n, gx0, gy0, gx1, gy1)
        glyf += b"".join(struct.pack(">H", c * 4 + 3) for c in range(n))
        glyf += struct.pack(">H", 0) + bytes([1] * (4 * n))      # no instructions, every point on-curve
        xs, ys = [], []
        for x0, y0, x1, y1 in rects:                           # clockwise: up the left, along the top, down the right
            xs += [x0, x0, x1, x1]
            ys += [y0, y1, y1, y0]
        for coords in (xs, ys):
            prev = 0
            for v in coords:
                glyf += struct.pack(">h", v - prev)
                prev = v
        while len(glyf) % 4:
            glyf.append(0)
    loca.append(len(glyf))
    count, max_adv = len(outs), max(advs)

    head = struct.pack(">IIIIHHqqhhhhHHhhh", 0x10000, 0x10000, 0, 0x5F0F3CF5, 0xB, UPM, 0, 0, *box, 0, 8, 2, 1, 0)
    hhea = struct.pack(">IhhhHhhhhhhhhhhhH", 0x10000, ASC, DESC, GAP, max_adv, 0, 0, max_adv, 1, 0, 0, 0, 0, 0, 0, 0, count)
    hmtx = b"".join(struct.pack(">Hh", advs[i], min((r[0] for r in outs[i]), default=0)) for i in range(count))
    loca_t = b"".join(struct.pack(">I", v) for v in loca)
    maxp = struct.pack(">IHHHHHHHHHHHHHH", 0x10000, count, max_p, max_c, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0)
    os2 = (struct.pack(">HhHHH", 4, 6 * PX, 400, 5, 0) + struct.pack(">8h", *([4 * PX] * 8)) + struct.pack(">hhh", PX, 3 * PX, 0)
           + bytes(10) + struct.pack(">IIII", 1, 0, 0, 0) + b"NONE" + struct.pack(">HHH", 0x40, ord(chars[0]), ord(chars[-1]))
           + struct.pack(">hhhHH", ASC, DESC, GAP, ASC, -DESC) + struct.pack(">II", 1, 0)
           + struct.pack(">hhHHH", 5 * PX, 8 * PX, 0, 32, 1))
    # cmap format 4, a segment per character (glyph i + 1 for chars[i]).
    segs = len(chars) + 1
    p, e = 1, 0
    while p * 2 <= segs:
        p, e = p * 2, e + 1
    codes = [ord(c) for c in chars]
    sub = struct.pack(">HHHHHHH", 4, 16 + segs * 8, 0, segs * 2, p * 2, e, segs * 2 - p * 2)
    sub += b"".join(struct.pack(">H", c) for c in codes) + struct.pack(">HH", 0xFFFF, 0)
    sub += b"".join(struct.pack(">H", c) for c in codes) + struct.pack(">H", 0xFFFF)
    sub += b"".join(struct.pack(">H", (i + 1 - c) & 0xFFFF) for i, c in enumerate(codes)) + struct.pack(">H", 1)
    sub += bytes(segs * 2)
    cmap = struct.pack(">HHHHI", 0, 1, 3, 1, 12) + sub
    strings = [(1, FAMILY), (2, "Regular"), (4, FAMILY), (6, FAMILY.replace(" ", ""))]
    data = [s.encode("utf-16-be") for _, s in strings]
    name = struct.pack(">HHH", 0, len(strings), 6 + 12 * len(strings))
    off = 0
    for (nid, _), d in zip(strings, data):
        name += struct.pack(">HHHHHH", 3, 1, 0x409, nid, len(d), off)
        off += len(d)
    name += b"".join(data)
    post = struct.pack(">IIhhIIIII", 0x30000, 0, -PX, PX, 1, 0, 0, 0, 0)
    tables = dict(sorted({"OS/2": os2, "cmap": cmap, "glyf": bytes(glyf), "head": head, "hhea": hhea,
                          "hmtx": hmtx, "loca": loca_t, "maxp": maxp, "name": name, "post": post}.items()))
    return assemble(tables)


def checksum(b):
    b = b + bytes(-len(b) % 4)
    return sum(struct.unpack(">%dI" % (len(b) // 4), b)) & 0xFFFFFFFF


def assemble(tables):
    n = len(tables)
    p, e = 1, 0
    while p * 2 <= n:
        p, e = p * 2, e + 1
    font = bytearray(struct.pack(">IHHHH", 0x10000, n, p * 16, e, n * 16 - p * 16))
    offset, body, head_off = 12 + 16 * n, bytearray(), 0
    for tag, b in tables.items():
        if tag == "head":
            head_off = offset
        font += tag.encode() + struct.pack(">III", checksum(b), offset, len(b))
        padded = b + bytes(-len(b) % 4)
        body += padded
        offset += len(padded)
    font += body
    font[head_off + 8:head_off + 12] = struct.pack(">I", (0xB1B0AFBA - checksum(bytes(font))) & 0xFFFFFFFF)
    return bytes(font)


if __name__ == "__main__":
    font = build()
    with open(OUT, "wb") as f:
        f.write(font)
    print(f"wrote {os.path.relpath(OUT, ROOT)}: {len(gen_font.SMALL_GLYPHS)} glyphs, {len(font)} bytes")
