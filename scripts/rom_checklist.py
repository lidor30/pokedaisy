#!/usr/bin/env python3
"""Is a ROM's support complete? The last step of the add-rom skill (.claude/skills/add-rom/), run in the checkout
that has the change:

    python3 scripts/rom_checklist.py ROM [--key FIXTURE_KEY ...] [--no-run] [--ds-port]
    make rom-checklist ROM=<file>

Each place CLAUDE.md says a new ROM has to reach, checked by the ROM's SHA-1 (a DS cart by its header) and its
library title:

  detection      the app names the SHA-1 (or a DS cart's code + revision) in its code or game data
  title          GameTitles gives it a name (`GameTitles.BY_SHA1`, the ports' generated titles)
  website        compat.json has it (else: UPDATE_SITE_DATA=1 ... SiteDataExportTest)
  readme / features / manual / development
                 README.md's Supported games, docs/ROM_FEATURES.md, docs/manual/supported-games.md and
                 docs/DEVELOPMENT.md name the game (and its version, when the file name has one)
  fixtures       scripts/host_roms.conf has a line for this file; each key's fixture folder has its README.txt and
                 RAM, and a test reads it (a DS language port needs none: --ds-port)
  committable    nothing changed or new that mustn't be committed (ROMs, saves, keys, home paths: the ROM agent's
                 guard)
  generated      `gen_gamedata.py --check` and `check_translations.py` pass (skipped with --no-run)
  file           the ROM's own file name and folder follow CLAUDE.md's rule

MISSING fails (exit 1); CHECK is for a person to look at. It never edits anything.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS / "rom_agent"))
sys.path.insert(0, str(SCRIPTS))
from add_rom_support import guard, rom_identity  # noqa: E402
from rom_identify import DS_BASES, RETAIL_NAME, header_support, host_roms, name_check  # noqa: E402

GBA_BASES = {"BPR": "FireRed", "BPG": "LeafGreen", "BPE": "Emerald", "AXV": "Ruby", "AXP": "Sapphire"}
LANGUAGES = {"D": "German", "F": "French", "S": "Spanish", "I": "Italian", "J": "Japanese", "K": "Korean"}


def port_needles(rom: dict) -> list[str] | None:
    """A retail cart in another language is named in the books as its English game plus the language
    ("Pokémon Black: the USA / Europe, French, German, ... releases"), not by its own title."""
    code = rom.get("code") or ""
    base = (DS_BASES if rom["platform"] == "NDS" else GBA_BASES).get(code[:3])
    lang = LANGUAGES.get(code[3:])
    if base and lang and RETAIL_NAME.match(rom["file_name"]):
        return [base, lang]
    return None

FIXTURES = REPO / "app" / "src" / "test" / "resources" / "fixtures"


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True).stdout


def fold(s: str) -> str:
    return unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()


def app_title(sha1: str) -> str:
    """The library's title from the app's own sources (not the website's export, which may be stale)."""
    gt = (REPO / "app/src/main/kotlin/com/pokedaisy/app/GameTitles.kt").read_text()
    m = re.search(rf'"{sha1}"\s+to\s+"([^"]+)"', gt)
    if m:
        return m.group(1)
    for name in re.findall(rf'val\s+(\w+)\s*=\s*"{sha1}"', git("grep", "-h", sha1, "--", "app/src/main")):
        m = re.search(rf'\b{name}\s+to\s+"([^"]+)"', gt)
        if m:
            return m.group(1)
    gen = git("grep", "-h", "-A0", sha1, "--", "app/src/main/kotlin/com/pokedaisy/app/companion/data/RetailPortsGen.kt")
    m = re.search(r'to\s+"([^"]+)"', gen)
    if m:
        return m.group(1)
    ports = (REPO / "gamedata/nds/ports.toml").read_text()
    m = re.search(rf'title = "([^"]+)"\nsha1 = "{sha1}"', ports)
    return m.group(1) if m else ""


def short_name(title: str) -> str:
    return re.sub(r"^(Pok[eé]mon|Moemon)\s+", "", title).strip()


def version_of(file_name: str) -> str:
    m = re.search(r"\(v?([0-9][0-9.]*[0-9a-z-]*)", file_name)
    return m.group(1) if m else ""


def lines_naming(path: Path, needles: list[str]) -> list[str]:
    if not path.exists():
        return []
    out = []
    for line in path.read_text().splitlines():
        f = fold(line)
        if all(fold(n) in f for n in needles if n):
            out.append(line.strip()[:160])
    return out


class Report:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, str]] = []

    def add(self, state: str, what: str, detail: str = "") -> None:
        self.rows.append((state, what, detail))

    def print(self) -> int:
        width = max(len(w) for _, w, _ in self.rows)
        for state, what, detail in self.rows:
            print(f"  {state:8} {what:{width}}  {detail}")
        missing = sum(s == "MISSING" for s, _, _ in self.rows)
        checks = sum(s == "CHECK" for s, _, _ in self.rows)
        print(f"\n{missing} missing, {checks} to look at.")
        return 1 if missing else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("rom")
    ap.add_argument("--key", action="append", default=[], help="a fixture key to check (default: host_roms.conf's)")
    ap.add_argument("--save", help="the save used, so a copy of it is caught")
    ap.add_argument("--ds-port", action="store_true", help="a DS language port: no fixtures expected")
    ap.add_argument("--no-run", action="store_true", help="skip gen_gamedata.py --check and check_translations.py")
    args = ap.parse_args()

    path = Path(os.path.expanduser(args.rom)).resolve()
    rom = rom_identity(path)
    sha1, plat = rom["sha1"], rom["platform"]
    header = header_support()
    by_header = plat == "NDS" and (rom["code"], rom["revision"]) in header["NDS"]
    r = Report()
    print(f"{rom['file_name']}  ({plat} {rom.get('code') or '-'} rev {rom.get('revision')}, SHA-1 {sha1})\n")

    # Detection
    named = [l for l in git("grep", "-l", sha1, "--", "app/src/main", "gamedata").splitlines() if l]
    if named:
        r.add("ok", "detection", ", ".join(Path(n).name for n in named[:5]))
    elif by_header and RETAIL_NAME.match(rom["file_name"]):
        r.add("ok", "detection", f"by header {rom['code']} rev {rom['revision']} (DS)")
    elif by_header:
        r.add("CHECK", "detection", f"only by header ({rom['code']} rev {rom['revision']}): the app reads this hack as the "
              "retail game; a hack needs a check of its own (Origin HeartGold's code word)")
    else:
        r.add("MISSING", "detection", "no code or game data names its SHA-1 (SUPPORTED_HACK_SHA1S, a config's hash, ports.toml)")

    # Title
    title = app_title(sha1)
    r.add("ok" if title else ("CHECK" if by_header else "MISSING"), "title",
          title or "not in GameTitles.BY_SHA1 (a hack's version goes in its *_V<ver>_SHA1 constant's name)")
    name = short_name(title) if title else ""
    ver = version_of(rom["file_name"])

    # Website
    compat = json.loads((REPO / "website/src/data/compat.json").read_text())
    if sha1 in (REPO / "website/src/data/compat.json").read_text():
        r.add("ok", "website", "compat.json names it")
    elif plat == "NDS" and any((d["code"], d["rev"]) == (rom["code"], rom["revision"]) for d in compat.get("ds", [])):
        r.add("ok", "website", "compat.json's ds list has its header")
    else:
        r.add("MISSING", "website", "compat.json is stale: UPDATE_SITE_DATA=1 ./gradlew :app:testDebugUnitTest -Pquick "
              "--tests '*SiteDataExportTest'")

    # The books
    books = [("readme", REPO / "README.md"), ("features", REPO / "docs/ROM_FEATURES.md"),
             ("manual", REPO / "docs/manual/supported-games.md"), ("development", REPO / "docs/DEVELOPMENT.md")]
    port = port_needles(rom)
    for what, file in books:
        if port:
            hits = lines_naming(file, port) or lines_naming(file, [port[0], "every language"])
            # A miss is for a person to look at: the books often list a game's languages under a heading of its own.
            r.add("ok" if hits else "CHECK", what, hits[0] if hits else f"no line names {port[0]} with {port[1]}")
            continue
        if not name:
            r.add("CHECK", what, f"no title to look for: check {file.relative_to(REPO)} by hand")
            continue
        hits = lines_naming(file, [name])
        with_ver = [h for h in hits if fold(ver) in fold(h)] if ver else hits
        if with_ver:
            r.add("ok", what, with_ver[0])
        elif hits and what in ("manual", "development"):
            r.add("ok", what, hits[0])  # the manual names games, not every version; DEVELOPMENT.md may group them
        elif hits:
            r.add("CHECK", what, f"names {name} but not version {ver}: {hits[0]}")
        else:
            r.add("MISSING", what, f"{file.relative_to(REPO)} doesn't name {name!r}")

    # Fixtures
    rows = [x for x in host_roms() if Path(os.path.expanduser(x["rom"])).name == rom["file_name"]]
    keys = args.key or [x["key"] for x in rows]
    if args.ds_port:
        r.add("ok", "fixtures", "a DS language port: none expected (checked headless beside its US cart)")
    elif not keys:
        r.add("MISSING", "fixtures", "no scripts/host_roms.conf line for this file name (key|label|~ rom path|~ save path)")
    tests = git("grep", "-l", "-E", "|".join(rf'"{re.escape(k)}"' for k in keys), "--", "app/src/test") if keys else ""
    for k in keys:
        d = FIXTURES / k
        if not d.is_dir():
            r.add("MISSING", f"fixture {k}", f"no {d.relative_to(REPO)}/ (capture_fixture_headless.sh {k})")
            continue
        has_ram = any(d.glob("*.bin"))
        readme = d / "README.txt"
        used = bool(git("grep", "-l", f'"{k}"', "--", "app/src/test"))
        problems = ([] if readme.exists() else ["no README.txt"]) + ([] if has_ram else ["no RAM dump"]) + \
                   ([] if used else ["no test reads it"])
        if readme.exists() and str(Path.home()) in readme.read_text():
            problems.append("README.txt writes the home folder out: use ~")
        r.add("MISSING" if problems else "ok", f"fixture {k}", "; ".join(problems) or "README, RAM, read by a test")
    if keys and tests:
        r.add("ok", "tests", ", ".join(Path(t).stem for t in tests.split()[:4]))
    for x in rows:
        if str(Path.home()) in x["rom"] + x["save"]:
            r.add("MISSING", f"host_roms {x['key']}", "an absolute home path: write ~")

    # Nothing that mustn't be committed
    save = None
    if args.save:
        sp = Path(os.path.expanduser(args.save))
        import hashlib
        save = {"sha1": hashlib.sha1(sp.read_bytes()).hexdigest()}
    problems = guard(REPO, rom, save)
    r.add("MISSING" if problems else "ok", "committable", "; ".join(problems) or "no ROM, save, key or home path in the change")

    # Generated files and translations
    if not args.no_run:
        for what, cmd in (("generated", ["python3", "scripts/gen_gamedata.py", "--check"]),
                          ("translations", ["python3", "scripts/check_translations.py"])):
            res = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True)
            last = (res.stdout + res.stderr).strip().splitlines()[-1:] or [""]
            r.add("ok" if res.returncode == 0 else "MISSING", what, last[0][:150] if res.returncode else " ".join(cmd[1:]))

    # The ROM's own file
    for note in name_check(rom):
        r.add("CHECK", "file", note)

    code = r.print()
    print("Then: the new tests by name (make test-quick T='*<Game>DecodeTest'), make test-quick, and make docs-check "
          "if the manual changed. Full runs only with the user's OK.")
    return code


if __name__ == "__main__":
    sys.exit(main())
