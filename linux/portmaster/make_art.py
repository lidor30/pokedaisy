"""The PortMaster port's art for the firmware's game list (EmulationStation): cover.png (its box), marquee.png (its logo) in
linux/portmaster/, from PokeDaisy's own pieces only - the flower (3ds/core's PD_LOGO), the app's page (DaisyBackdrop's
blobs and dot grid), the OPTION window and the Pixel Operator font. Never a game's art. screenshot.png is the game
list itself over made-up file names (see linux/README.md's PortMaster section). Run after the logo changes (needs Pillow):

    python3 linux/portmaster/make_art.py"""
from pathlib import Path
import re
from PIL import Image, ImageDraw, ImageFont

repo = Path(__file__).resolve().parents[2]
out = Path(__file__).resolve().parent
src = (repo / '3ds/core/pd_icons_gen.c').read_text()
rows = re.findall(r'"([^"]*)"', src.split('logoRows[] = {')[1].split('};')[0])
colors = [int(c, 16) for c in re.findall(r'0x([0-9A-Fa-f]{6})', src.split('logoColors[] = {')[1].split('};')[0])]
keys = 'KRDHWSYyw'
font_path = repo / 'app/src/main/assets/fonts/PixelOperator.ttf'


def rgb(c, a=255):
    return (c >> 16 & 255, c >> 8 & 255, c & 255, a)


def logo(scale):
    im = Image.new('RGBA', (32 * scale, 32 * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in keys:
                d.rectangle((x * scale, y * scale, (x + 1) * scale - 1, (y + 1) * scale - 1), fill=rgb(colors[keys.index(ch)]))
    return im


def font(px):
    # Pixel Operator's pixel is 1/16 of its size: a whole number of image pixels per font pixel keeps it crisp.
    return ImageFont.truetype(str(font_path), 16 * px)


def text(d, xy, s, px, color, shadow=None):
    if shadow is not None:
        d.text((xy[0] + px, xy[1] + px), s, font=font(px), fill=shadow)
    d.text(xy, s, font=font(px), fill=color)


def page(w, h):
    """DaisyBackdrop, still: the light page, three soft blobs, a faint dot grid (pd_library_draw.c's make_page)."""
    blobs = [(11, 0, 9, 23, 0xFFD2CC), (90, 18, 20, 20, 0xFFF0B0), (45, 100, 5, 25, 0xD6E2FF)]
    im = Image.new('RGB', (w, h))
    px = im.load()
    big = max(w, h)
    for y in range(h):
        for x in range(w):
            c = [0xF7, 0xF6, 0xFB]
            for bx, by, yw, r, col in blobs:
                cx, cy, rr = bx * w // 100, by * h // 100 + yw * big // 100, r * big // 100
                d2 = (x - cx) ** 2 + (y - cy) ** 2
                if d2 >= (2 * rr) ** 2:
                    continue
                k = 140 if d2 <= rr * rr else 140 * (2 * rr - int(d2 ** 0.5)) // rr
                t = [col >> 16 & 255, col >> 8 & 255, col & 255]
                c = [c[i] + (t[i] - c[i]) * k // 256 for i in range(3)]
            if x % 24 == 0 and y % 24 == 0:
                c = [v + (0x1D - v) * 23 // 256 for v in c]
            px[x, y] = tuple(c)
    return im


def window(d, box, fill=0xFFFFFF):
    """The OPTION screen's title window: a dark outer line, a light band, the fill (pixel corners)."""
    x0, y0, x1, y1 = box
    for inset, col in ((0, 0x4A4A5A), (4, 0xD6D6E2), (8, fill)):
        d.rounded_rectangle((x0 + inset, y0 + inset, x1 - inset, y1 - inset), radius=max(4, 12 - inset), fill=rgb(col))


def cover():
    w, h = 640, 640
    im = page(w, h).convert('RGBA')
    flower = logo(10)
    im.alpha_composite(flower, ((w - flower.width) // 2, 60))
    d = ImageDraw.Draw(im)
    window(d, (60, 410, w - 60, 580))
    title = 'POKéDAISY'
    tw = d.textlength(title, font=font(5))
    text(d, ((w - tw) / 2, 418), title, 5, rgb(0x3A3A48), rgb(0xD0D0DC))
    sub = 'COMPANION'
    sw = d.textlength(sub, font=font(3))
    text(d, ((w - sw) / 2, 510), sub, 3, rgb(0xE83028), rgb(0xF0C8C0))
    im.convert('RGB').save(out / 'cover.png', optimize=True)


def marquee():
    flower = logo(5)
    title = 'POKéDAISY'
    probe = ImageDraw.Draw(Image.new('RGBA', (1, 1)))
    tw = int(probe.textlength(title, font=font(7)))
    w, h = flower.width + 24 + tw + 16, flower.height + 8
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    im.alpha_composite(flower, (0, 4))
    d = ImageDraw.Draw(im)
    y = (h - 16 * 7) // 2 - 7
    # A dark outline, so it reads on light and dark themes alike.
    for dx in (-7, 0, 7):
        for dy in (-7, 0, 7):
            if dx or dy:
                d.text((flower.width + 24 + dx, y + dy), title, font=font(7), fill=rgb(0x202020))
    d.text((flower.width + 24, y), title, font=font(7), fill=rgb(0xFFFFFF))
    im.save(out / 'marquee.png', optimize=True)


cover()
marquee()
print(out / 'cover.png', out / 'marquee.png')
