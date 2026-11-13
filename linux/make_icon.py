"""linux/icon.png from the Android launcher icon (its lattice background + the flower foreground, cropped to what
Android's adaptive-icon mask shows), drawn like the TrimUI menu's own app icons: a 300x300 image with a 204 px
rounded square in it. Run after the launcher icon changes (needs Pillow): python3 linux/make_icon.py [out.png]"""
from pathlib import Path
import sys
from PIL import Image, ImageDraw
repo = Path(__file__).resolve().parents[1]
res = repo / 'app/src/main/res'
fg = Image.open(res / 'mipmap-xxxhdpi/ic_launcher_foreground.png').convert('RGBA')
bg = Image.open(res / 'mipmap-xxxhdpi/ic_launcher_background.png').convert('RGBA')
size, box, radius = 300, 204, 40
# An adaptive icon shows the middle 72 of its 108 dp: the middle two thirds of each layer.
crop = fg.width * 2 // 3
off = (fg.width - crop) // 2
square = bg.crop((off, off, off + crop, off + crop)).resize((box, box), Image.NEAREST)
square.alpha_composite(fg.crop((off, off, off + crop, off + crop)).resize((box, box), Image.NEAREST))
mask = Image.new('L', (box, box), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, box - 1, box - 1), radius=radius, fill=255)
icon = Image.new('RGBA', (size, size), (0, 0, 0, 0))
icon.paste(square, ((size - box) // 2, (size - box) // 2), mask)
out = Path(sys.argv[1]) if len(sys.argv) > 1 else repo / 'linux/icon.png'
icon.save(out, optimize=True)
print(out)
