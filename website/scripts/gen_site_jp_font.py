#!/usr/bin/env python3
"""Builds public/fonts/PixelMplusJP-site.ttf, the site's Japanese behind Pixel Operator (Base.astro's
'PixelMplus JP' @font-face).

The app's own PixelMplusJP.ttf (scripts/gen_jp_font.py: PixelMplus10 on Pixel Operator's pixel grid) is
1 MB of every JIS kanji; the site needs only what its Japanese uses, so this cuts it to the characters in
src/i18n/ja.json, the manual's Japanese pages (docs/manual/translations/ja) and the language picker's names, plus the kana and CJK punctuation (so a new string
rarely needs a rebuild). test/i18n.test.js fails when ja.json uses a character the file lacks: rerun this.
Needs fonttools.

    python3 website/scripts/gen_site_jp_font.py
"""
import json
from pathlib import Path

from fontTools import subset

SITE = Path(__file__).resolve().parent.parent
APP_FONT = SITE.parent / "app/src/main/assets/fonts/PixelMplusJP.ttf"
OUT = SITE / "public/fonts/PixelMplusJP-site.ttf"

text = "".join(json.loads((SITE / "src/i18n/ja.json").read_text("utf-8")).values()) + "".join(
    p.read_text("utf-8") for p in sorted((SITE.parent / "docs/manual/translations/ja").glob("*.md"))
) + "日本語"
chars = {ord(c) for c in text}
chars |= set(range(0x3000, 0x3040)) | set(range(0x3040, 0x30A0)) | set(range(0x30A0, 0x3100))  # punctuation, kana
chars |= set(range(0xFF01, 0xFF5F))  # full-width forms

options = subset.Options()
options.name_IDs = ["*"]
options.notdef_outline = True
font = subset.load_font(str(APP_FONT), options)
subsetter = subset.Subsetter(options)
subsetter.populate(unicodes=sorted(chars))
subsetter.subset(font)
subset.save_font(font, str(OUT), options)
print(f"{OUT}: {OUT.stat().st_size // 1024} KB, {len(font.getBestCmap())} characters")
