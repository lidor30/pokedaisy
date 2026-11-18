#!/usr/bin/env python3
"""Writes the CIA's HOME Menu banner (3ds/ctr/cia/banner.png, 256x128) and its
sound (3ds/ctr/cia/banner.wav), both our own: the app's launcher flower
(app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png, from
scripts/gen_logo.mjs) over the launcher's lattice background, and a short
two-note chime made here.

    python3 3ds/tools/gen_cia_banner.py      (needs Pillow)
"""
import math
import os
import struct
import wave

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FG = os.path.join(ROOT, "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png")
BG = os.path.join(ROOT, "app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png")
OUT_DIR = os.path.join(ROOT, "3ds/ctr/cia")
W, H = 256, 128
FLOWER = 112  # the flower's box on the banner, square


def banner():
    fg = Image.open(FG).convert("RGBA")
    bg = Image.open(BG).convert("RGBA")
    # An adaptive icon shows the middle 72 of its 108 dp; the flower sits in it.
    w = fg.size[0]
    inset = w * 18 // 108
    box = (inset, inset, w - inset, w - inset)
    flower = fg.crop(box).resize((FLOWER, FLOWER), Image.LANCZOS)
    # The lattice repeated across the banner at the icon's own scale (the 48 px icon's).
    tile = bg.crop(box).resize((FLOWER, FLOWER), Image.LANCZOS)
    out = Image.new("RGBA", (W, H))
    fx, fy = (W - FLOWER) // 2, (H - FLOWER) // 2
    for y in range(fy % FLOWER - FLOWER, H, FLOWER):  # tiles lined up with the flower's box
        for x in range(fx % FLOWER - FLOWER, W, FLOWER):
            out.paste(tile, (x, y))
    out.alpha_composite(flower, (fx, fy))
    path = os.path.join(OUT_DIR, "banner.png")
    out.save(path)
    print(f"wrote {os.path.relpath(path, ROOT)}")


def chime():
    rate = 32000
    notes = [(0.0, 659.26, 0.9), (0.16, 987.77, 1.2)]  # E5, then B5, each fading out
    length = 1.6
    samples = []
    for i in range(int(rate * length)):
        t = i / rate
        v = 0.0
        for start, freq, decay in notes:
            if t < start:
                continue
            u = t - start
            env = min(1.0, u / 0.005) * math.exp(-u * 3.2 / decay)
            v += env * (math.sin(2 * math.pi * freq * u) + 0.25 * math.sin(4 * math.pi * freq * u))
        samples.append(int(max(-1.0, min(1.0, v * 0.32)) * 32767))
    path = os.path.join(OUT_DIR, "banner.wav")
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(rate)
        f.writeframes(b"".join(struct.pack("<h", s) for s in samples))
    print(f"wrote {os.path.relpath(path, ROOT)}")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    banner()
    chime()


if __name__ == "__main__":
    main()
