#!/usr/bin/env python3
"""Builds public/fonts/GalmuriKO-site.ttf, the site's Korean behind Pixel Operator (Base.astro's 'Galmuri KO'
@font-face).

The app's own GalmuriKO.ttf (scripts/gen_ko_font.py: Galmuri9 on Pixel Operator's pixel grid) is 1.5 MB of every
Hangul syllable; the site needs only what its Korean uses, so this cuts it to the characters in src/i18n/ko.json,
the manual's Korean pages (docs/manual/translations/ko) and the language picker's names, plus the jamo (so a new string rarely needs a rebuild). test/i18n.test.js fails
when ko.json uses a character the file lacks: rerun this. Needs fonttools.

    python3 website/scripts/gen_site_ko_font.py
"""
import json
from pathlib import Path

from fontTools import subset

SITE = Path(__file__).resolve().parent.parent
APP_FONT = SITE.parent / "app/src/main/assets/fonts/GalmuriKO.ttf"
OUT = SITE / "public/fonts/GalmuriKO-site.ttf"

text = "".join(json.loads((SITE / "src/i18n/ko.json").read_text("utf-8")).values()) + "".join(
    p.read_text("utf-8") for p in sorted((SITE.parent / "docs/manual/translations/ko").glob("*.md"))
) + "한국어"
chars = {ord(c) for c in text if 0xAC00 <= ord(c) <= 0xD7A3}
chars |= set(range(0x3131, 0x318F))  # compatibility jamo

options = subset.Options()
options.name_IDs = ["*"]
options.notdef_outline = True
font = subset.load_font(str(APP_FONT), options)
subsetter = subset.Subsetter(options)
subsetter.populate(unicodes=sorted(chars))
subsetter.subset(font)
subset.save_font(font, str(OUT), options)
print(f"{OUT}: {OUT.stat().st_size // 1024} KB, {len(font.getBestCmap())} characters")
