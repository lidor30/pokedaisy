#!/usr/bin/env python3
"""Writes 3ds/core/pd_tr_gen.c: the menus' translations (pd_tr.h) from the app's own tables.

The app's companion/i18n/Tr*.kt are the source of truth; 3ds/tools/tr_3ds.txt adds the lines only the 3DS / Linux
menus have, in the same `"ENGLISH" to Tr(ja = ..., ..., ko = ...)` form (so a line can move into the app's tables as
is). Only keys the 3DS / Linux code has as a C string literal are written. Japanese and Korean are left out until
their glyphs are in the 3DS's fonts (pd_font_gen.c): every other translation must draw with those fonts, else this
fails and names the letters.

    python3 3ds/tools/gen_tr.py            write pd_tr_gen.c
    python3 3ds/tools/gen_tr.py --check    fail if pd_tr_gen.c is stale
    python3 3ds/tools/gen_tr.py --missing  list literals in the menus' code that look like menu text with no line
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
APP_I18N = os.path.join(ROOT, "app/src/main/kotlin/com/pokedaisy/app/companion/i18n")
OWN = os.path.join(ROOT, "3ds/tools/tr_3ds.txt")
OUT = os.path.join(ROOT, "3ds/core/pd_tr_gen.c")
FONT = os.path.join(ROOT, "3ds/tools/gen_font.py")
# Where the menus' code is (a key counts when one of these files has it as a literal).
CODE_DIRS = ["3ds/core", "3ds/ctr", "3ds/desktop", "linux", "libretro"]
# The menus proper, for --missing (the rest of the code has literals that aren't drawn).
MENU_FILES = re.compile(r"3ds/core/pd_(ui|library|settings|menu|tr)[\w]*\.c$|3ds/ctr/main\.c$|3ds/desktop/pd_preview\.c$")

LANGS = ["fr", "de", "it", "es", "br", "pt"]  # pd_tr.h's enum pd_lang after EN
STR = r'"((?:[^"\\]|\\.)*)"'
# `"KEY" to Tr(ja = "...", fr = "...", ...)`: the app's lines name all eight; tr_3ds.txt may leave ja / ko out.
ENTRY = re.compile(STR + r'\s+to\s+Tr\(((?:\s*\w+\s*=\s*' + STR + r'\s*,?)+)\s*\)')
ARG = re.compile(r'(\w+)\s*=\s*' + STR)
PH = re.compile(r'\{\d+\}')
# A key that is a printf format ("%d TABS": the code snprintfs pd_tr's answer) keeps its conversions, in order.
FMT = re.compile(r'%[-+ #0]*(?:\*|\d+)?(?:\.(?:\*|\d+))?(?:hh|h|ll|l|z)?[diouxXcsf%]')
C_TOKEN = re.compile(r'"(?:[^"\\\n]|\\.)*"|/\*.*?\*/|//[^\n]*|\'(?:[^\'\\\n]|\\.)+\'', re.S)


def unescape(s):
    return s.replace('\\"', '"').replace("\\\\", "\\").replace("\\n", "\n").replace("\\$", "$")


def entries(path):
    out = {}
    with open(path, encoding="utf-8") as f:
        text = f.read()
    for m in ENTRY.finditer(text):
        key = unescape(m.group(1))
        args = {a.group(1): unescape(a.group(2)) for a in ARG.finditer(m.group(2))}
        missing = [lang for lang in LANGS if lang not in args]
        if missing:
            sys.exit(f"{os.path.relpath(path, ROOT)}: {key!r} has no {', '.join(missing)}")
        out[key] = {lang: args[lang] for lang in LANGS}
    return out


def tables():
    table, own = {}, {}
    for name in sorted(os.listdir(APP_I18N)):
        if name.startswith("Tr") and name.endswith(".kt"):
            table.update(entries(os.path.join(APP_I18N, name)))
    if os.path.exists(OWN):
        own = entries(OWN)
    clash = sorted(k for k in own if k in table)
    if clash:
        sys.exit("tr_3ds.txt repeats keys the app's tables already have - keep the app's: " + ", ".join(map(repr, clash)))
    table.update(own)
    return table


def c_literals():
    """Every string literal in the menus' code, by file (comments left out)."""
    found = {}
    for d in CODE_DIRS:
        base = os.path.join(ROOT, d)
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            if not name.endswith(".c") or name.endswith("_gen.c") or name.endswith("_gen.h"):
                continue
            rel = f"{d}/{name}"
            with open(os.path.join(base, name), encoding="utf-8", errors="replace") as f:
                text = f.read()
            lits = []
            last_end = None  # adjacent literals ("a" "b") are one string, as C joins them
            for m in C_TOKEN.finditer(text):
                t = m.group(0)
                if not t.startswith('"'):
                    continue
                s = unescape(t[1:-1])
                if last_end is not None and not text[last_end:m.start()].strip() and lits:
                    lits[-1] = (lits[-1][0] + s, lits[-1][1])
                else:
                    lits.append((s, text.count("\n", 0, m.start()) + 1))
                last_end = m.end()
            found[rel] = lits
    return found


def font_chars():
    with open(FONT, encoding="utf-8") as f:
        text = f.read()
    extra = "".join(re.findall(r'"([^"]*)"', re.search(r"^EXTRA = (.*)$", text, re.M).group(1)))
    glyphs = re.search(r"^PIXEL_GLYPHS = \{(.*?)^\}", text, re.M | re.S)
    drawn = "".join(re.findall(r'^\s*"(.)":', glyphs.group(1), re.M)) if glyphs else ""
    return set(chr(c) for c in range(32, 127)) | set(extra) | set(drawn)


def c_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def build():
    table = tables()
    lits = c_literals()
    used = sorted({s for ls in lits.values() for s, _ in ls if s in table})
    chars = font_chars()
    errors = []
    for key in used:
        want = sorted(PH.findall(key))
        for lang in LANGS:
            s = table[key][lang]
            if not s.strip():
                errors.append(f"{key!r}: empty {lang}")
            if sorted(PH.findall(s)) != want:
                errors.append(f"{key!r}: {lang} placeholders {PH.findall(s)} != {want}")
            if FMT.findall(s) != FMT.findall(key):
                errors.append(f"{key!r}: {lang} {s!r} changes the format's conversions {FMT.findall(key)}")
            bad = sorted(set(ch for ch in s if ch not in chars))
            if bad:
                errors.append(f"{key!r}: {lang} {s!r} has letters the 3DS fonts lack: {''.join(bad)!r} (gen_font.py's EXTRA)")
    if errors:
        sys.exit("\n".join(errors))
    keys = sorted(used, key=lambda k: k.encode("utf-8"))  # strcmp order
    out = [
        "// Generated by 3ds/tools/gen_tr.py from the app's companion/i18n/Tr*.kt and 3ds/tools/tr_3ds.txt - don't edit by",
        "// hand: change those and run `make -C 3ds tables`. The keys the 3DS / Linux code uses, in strcmp order (pd_tr.h).",
        '#include "pd_tr.h"',
        "",
        f"const struct pd_tr_entry pd_tr_table[{max(len(keys), 1)}] = {{",
    ]
    for key in keys:
        en = key.split("|", 1)[0]
        texts = [en] + [table[key][lang] for lang in LANGS]
        # A translation equal to the English (AUTO, OK) is NULL: the English is drawn.
        parts = [c_str(texts[0])] + ["NULL" if t == en else c_str(t) for t in texts[1:]]
        out.append(f"    {{ {c_str(key)}, {{ {', '.join(parts)} }} }},")
    if not keys:
        out.append('    { "", { "" } },')
    out.append("};")
    out.append(f"const int pd_tr_table_count = {len(keys)};")
    return "\n".join(out) + "\n", table, lits


MENU_TEXT = re.compile(r"^(?=.*[A-Za-z]{3})[A-Z0-9% .(][A-Za-z0-9éÉ .,'!?:/()+%&*·-]*$")


def main():
    text, table, lits = build()
    if "--missing" in sys.argv:
        seen = set()
        for rel, ls in lits.items():
            if not MENU_FILES.search(rel):
                continue
            for s, line in ls:
                if s in table or s in seen or not MENU_TEXT.match(s):
                    continue
                seen.add(s)
                print(f"{rel}:{line}: {s!r}")
        print(f"{len(seen)} without a line", file=sys.stderr)
        return
    if "--check" in sys.argv:
        with open(OUT, encoding="utf-8") as f:
            if f.read() != text:
                sys.exit("3ds/core/pd_tr_gen.c is stale: run python3 3ds/tools/gen_tr.py")
        print("pd_tr_gen.c up to date")
        return
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"wrote {os.path.relpath(OUT, ROOT)}: {text.count(chr(10)) - 6} keys")


if __name__ == "__main__":
    main()
