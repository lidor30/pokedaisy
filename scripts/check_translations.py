#!/usr/bin/env python3
"""Checks the app's translation tables against the code.

Every tr("...") / tk("...") literal under app/src/main/kotlin must be a key in
one of companion/i18n/Tr*.kt, with all eight translations non-empty and the
same {0}-style placeholders as the English. Keys must be plain literals: an
interpolated "$x" key can't be looked up. Also lists table entries nothing uses
(-v). TranslationsTest runs the same check on the JVM. Usage:

    python3 scripts/check_translations.py [-v] [files...]
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/kotlin"
I18N = SRC / "com/pokedaisy/app/companion/i18n"
STR = r'"((?:[^"\\]|\\.)*)"'
CALL = re.compile(r'\bt[rk]\(\s*' + STR)
ENTRY = re.compile(
    STR + r'\s+to\s+Tr\(\s*ja\s*=\s*' + STR + r'\s*,\s*fr\s*=\s*' + STR + r'\s*,\s*de\s*=\s*' + STR
    + r'\s*,\s*it\s*=\s*' + STR + r'\s*,\s*es\s*=\s*' + STR
    + r'\s*,\s*br\s*=\s*' + STR + r'\s*,\s*pt\s*=\s*' + STR + r'\s*,\s*ko\s*=\s*' + STR + r'\s*,?\s*\)'
)
PH = re.compile(r'\{\d+\}')
# String literals first, so a "*/*" or "//" inside one isn't taken for a comment.
TOKEN = re.compile(r'"(?:[^"\\\n]|\\.)*"|/\*.*?\*/|//[^\n]*', re.S)


def code_only(text):
    """Comments blanked (newlines kept, so line numbers still match); strings kept."""
    return TOKEN.sub(lambda m: m.group(0) if m.group(0).startswith('"') else "\n" * m.group(0).count("\n"), text)


def tables():
    out, dupes = {}, []
    for f in sorted(I18N.glob("Tr*.kt")):
        for m in ENTRY.finditer(f.read_text()):
            if m.group(1) in out:
                dupes.append(f"{f.name}: {m.group(1)!r} is already in {out[m.group(1)][0]} - keep one")
            out[m.group(1)] = (f.name, m.groups()[1:])
    return out, dupes


def main():
    verbose = "-v" in sys.argv
    only = [Path(a).resolve() for a in sys.argv[1:] if a != "-v"]
    table, dupes = tables()
    used, errors = set(), list(dupes)
    for f in sorted(SRC.rglob("*.kt")):
        if f.parent == I18N and f.name.startswith("Tr"):
            continue
        text = code_only(f.read_text())
        for m in CALL.finditer(text):
            key = m.group(1)
            used.add(key)
            if only and f.resolve() not in only:
                continue
            where = f"{f.relative_to(ROOT)}:{text.count(chr(10), 0, m.start()) + 1}"
            if "$" in key.replace("\\$", ""):
                errors.append(f"{where}: interpolated key {key!r} - use {{0}} and tr(key, arg)")
            elif key not in table:
                errors.append(f"{where}: no translation for {key!r}")
    for key, (fname, langs) in table.items():
        want = sorted(PH.findall(key))
        for lang, s in zip(("ja", "fr", "de", "it", "es", "br", "pt", "ko"), langs):
            if not s.strip():
                errors.append(f"{fname}: {key!r} has an empty {lang}")
            elif sorted(PH.findall(s)) != want:
                errors.append(f"{fname}: {key!r} {lang} placeholders {PH.findall(s)} != {want}")
    for e in errors:
        print(e)
    if verbose:
        for key in sorted(set(table) - used):
            print(f"unused: {key!r} ({table[key][0]})")
    print(f"{len(table)} entries, {len(used)} keys used, {len(errors)} problems")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
