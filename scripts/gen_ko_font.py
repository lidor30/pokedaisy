#!/usr/bin/env python3
"""Builds assets/fonts/GalmuriKO.ttf, the Korean fallback behind Pixel Operator (and PixelMplusJP).

Input: Galmuri9.ttf from https://github.com/quiple/galmuri (2.40.3, also the `galmuri` npm package's
dist/; SIL Open Font License 1.1 with no reserved font name, copied beside the output as LICENSE-Galmuri.txt).

Galmuri9 draws its Hangul on a 9-pixel grid of 100 units (unitsPerEm 1000), baseline at 0: a syllable is
exactly as tall as Pixel Operator's capitals, whose pixel is also 100 units, at unitsPerEm 1600. Re-declaring
this font's em as 1600 (outlines untouched) puts both on the same pixel grid, so Korean comes out as tall as the
Latin text beside it and crisp at every whole GbaTextMetrics scale, like scripts/gen_jp_font.py does for
Japanese. Line metrics are copied from Pixel Operator so a line holding Korean is no taller than one without.
Only Hangul is kept (syllables and jamo): anything else Pixel Operator lacks keeps falling through to
PixelMplusJP or the system font. Needs fonttools.

    python3 scripts/gen_ko_font.py Galmuri9.ttf
"""
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
FONTS = ROOT / "app/src/main/assets/fonts"
FAMILY = "PokeDaisy Galmuri KO"

src, ref = TTFont(sys.argv[1]), TTFont(FONTS / "PixelOperator.ttf")
assert src["head"].unitsPerEm == 1000
src["head"].unitsPerEm = ref["head"].unitsPerEm
src["hhea"].ascent, src["hhea"].descent, src["hhea"].lineGap = ref["hhea"].ascent, ref["hhea"].descent, ref["hhea"].lineGap
o, r = src["OS/2"], ref["OS/2"]
o.sTypoAscender, o.sTypoDescender, o.sTypoLineGap = r.sTypoAscender, r.sTypoDescender, r.sTypoLineGap
o.usWinAscent, o.usWinDescent = r.usWinAscent, r.usWinDescent
# A modified copy gets its own name (the OFL asks that it not pass for the original).
for rec in src["name"].names:
    if rec.nameID in (1, 4, 16):
        rec.string = FAMILY
    elif rec.nameID == 6:
        rec.string = FAMILY.replace(" ", "") + "-Regular"
HANGUL = [(0x1100, 0x11FF), (0x3130, 0x318F), (0xA960, 0xA97F), (0xAC00, 0xD7A3), (0xD7B0, 0xD7FF)]
keep = [c for c in src.getBestCmap() if any(a <= c <= b for a, b in HANGUL)]
sub = subset.Subsetter(subset.Options(name_IDs=["*"], name_languages=["*"], notdef_outline=True))
sub.populate(unicodes=keep)
sub.subset(src)
src.save(FONTS / "GalmuriKO.ttf")
print("wrote", FONTS / "GalmuriKO.ttf", len(keep), "characters")
