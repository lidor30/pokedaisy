#!/usr/bin/env python3
"""What a ROM is, before adding support for it: the first step of the add-rom skill (.claude/skills/add-rom/).

    python3 scripts/rom_identify.py ROM [--save SAVE] [--json] [--no-siblings]
    make rom-identify ROM=<file>

Prints, without changing anything:

- its identity: platform, header code / title / revision, size, SHA-1 / MD5 / CRC32, and whether the repo already
  names that SHA-1 (then it's supported, or at least known);
- its engine: same-offset likeness to the retail ROM of its base game (an in-place edit keeps most of retail's
  bytes; a rebuild keeps none), pokeemerald-expansion's RHHEXP block (version, species / moves / items);
- its closest siblings among the ROMs on this machine (`RETAIL_ROM_DIR`'s gba / gbc / nds): code likeness by
  function tails (the bytes before each Thumb `bx lr` / Z80 `ret`, which survive a rebuild that moves the code),
  name likeness, whether each is supported, its title, config file and fixtures (host_roms.conf);
- saves that look like its own (RetroArch's folder, the Discord saves folder), then the siblings' saves to try
  first (a sibling's save often loads: reuse it before playing a new game headless);
- the file name against CLAUDE.md's naming rule and where it should sit;
- which playbook of the skill to follow (`.claude/skills/add-rom/playbooks/<id>.md`).

Hashes of the ROM folder are cached in ~/.cache/pokedaisy-rom-tools/ by path + size + modified time. Only the
standard library: it reuses `scripts/rom_agent/add_rom_support.py`'s header reader.
"""
from __future__ import annotations

import argparse
import hashlib
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
from add_rom_support import rom_identity, tilde  # noqa: E402

HOME = Path.home()
HEADER: dict[str, set[tuple[str, int]]] = {}
DS_TITLES: dict[tuple[str, int], str] = {}
ROM_ROOT = HOME / "Downloads" / "Game ROMs & Emulation"
SAVE_DIRS = [HOME / "Documents" / "RetroArch" / "saves", ROM_ROOT / "rom saves from discord", ROM_ROOT]
SAVE_EXTS = {".sav", ".srm", ".dsv", ".sa1", ".fla"}
ROM_EXTS = {"GBA": {".gba"}, "GB": {".gb", ".gbc"}, "GBC": {".gb", ".gbc"}, "NDS": {".nds"}}
PLATFORM_DIR = {"GBA": "gba", "GB": "gbc", "GBC": "gbc", "NDS": "nds"}
CACHE = HOME / ".cache" / "pokedaisy-rom-tools" / "sha1.tsv"

# The English retail ROM of each GBA base game, by header code prefix and revision (No-Intro names).
RETAIL_BASE = {
    ("BPR", 0): "Pokemon - FireRed Version (USA).gba",
    ("BPR", 1): "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba",
    ("BPG", 0): "Pokemon - LeafGreen Version (USA).gba",
    ("BPG", 1): "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba",
    ("BPE", 0): "Pokemon - Emerald Version (USA, Europe).gba",
    ("AXV", 0): "Pokemon - Ruby Version (USA, Europe).gba",
    ("AXV", 1): "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba",
    ("AXV", 2): "Pokemon - Ruby Version (USA, Europe) (Rev 2).gba",
    ("AXP", 0): "Pokemon - Sapphire Version (USA, Europe).gba",
    ("AXP", 1): "Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba",
    ("AXP", 2): "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba",
}
# The supported DS base games by header code prefix (a cart of another language is a port of these).
DS_BASES = {"CPU": "Platinum", "IPK": "HeartGold", "IPG": "SoulSilver", "IRB": "Black", "IRA": "White",
            "IRE": "Black 2", "IRD": "White 2"}
# Game Boy carts pret has a disassembly of (a hack of one is most likely a fork of it).
GB_BASES = {"POKEMON RED": "pokered", "POKEMON BLUE": "pokered", "POKEMON YELLOW": "pokeyellow",
            "POKEMON_GLD": "pokegold", "POKEMON_SLV": "pokegold", "PM_CRYSTAL": "pokecrystal"}
RETAIL_NAME = re.compile(r"^(Pokemon|Pocket Monsters) - ")
HACK_NAME = re.compile(r"^(Pokemon|Moemon|Super Mariomon) [^()]+( \(v?[0-9][^()]*\))?( \([^()]+\))*\.[a-z]+$")


# --- reading the repo ----------------------------------------------------------------------------------------------

def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True).stdout


def repo_sha1s() -> set[str]:
    """Every SHA-1 the app's code, game data or the website's data names (detection, titles, configs)."""
    out = git("grep", "-ohE", "[0-9a-f]{40}", "--", "app/src/main", "gamedata", "website/src/data")
    return set(out.split())


def repo_titles() -> dict[str, str]:
    """SHA-1 -> library title: the website's export, then GameTitles' own pairs (newer than the export), the DS ports."""
    titles: dict[str, str] = {}
    compat = REPO / "website" / "src" / "data" / "compat.json"
    if compat.exists():
        titles.update(json.loads(compat.read_text()).get("titles", {}))
    consts = dict(re.findall(r'val\s+(\w+)\s*=\s*"([0-9a-f]{40})"', git("grep", "-hE", r'val\s+\w+\s*=\s*"[0-9a-f]{40}"',
                                                                            "--", "app/src/main")))
    text = (REPO / "app/src/main/kotlin/com/pokedaisy/app/GameTitles.kt").read_text()
    for key, title in re.findall(r'([\w.]+|"[0-9a-f]{40}")\s+to\s+"([^"]+)"', text):
        sha = key.strip('"') if key.startswith('"') else consts.get(key.split(".")[-1])
        if sha:
            titles.setdefault(sha, title)
    ports = REPO / "gamedata" / "nds" / "ports.toml"
    if ports.exists():
        for title, sha in re.findall(r'title = "([^"]+)"\nsha1 = "([0-9a-f]{40})"', ports.read_text()):
            titles.setdefault(sha, title)
    return titles


BASE_WORDS = {"firered", "leafgreen", "emerald", "ruby", "sapphire", "red", "blue", "gold", "silver", "crystal", "yellow"}


def header_support() -> dict[str, set[tuple[str, int]]]:
    """(code, revision) pairs supported by header rather than by hash: the DS carts (compat.json's `ds`, the
    language ports' rows) and the GBA retail carts (`retail`, which are read whatever the dump's own hash)."""
    out = {"NDS": set(), "GBA": set()}
    compat = REPO / "website" / "src" / "data" / "compat.json"
    if compat.exists():
        d = json.loads(compat.read_text())
        out["NDS"] |= {(r["code"], r["rev"]) for r in d.get("ds", [])}
        DS_TITLES.update({(r["code"], r["rev"]): r["title"] for r in d.get("ds", [])})
        out["GBA"] |= {(r["code"], v) for r in d.get("retail", []) for v in r.get("revs", [])}
    ports = REPO / "gamedata" / "nds" / "ports.toml"
    if ports.exists():
        out["NDS"] |= {(c, int(r)) for c, r in re.findall(r'code = "(\w{4})"\nrev = (\d+)', ports.read_text())}
    return out


def config_files(sha1: str, title: str, file_name: str) -> list[str]:
    """Where the repo names the game: the files naming its SHA-1 (detection, titles), then the game data TOML that
    holds its config (configs name their game, not its hash: retail's under retail/ and ports/, a hack's under
    hacks/ by a word of its title)."""
    named = [Path(l).name for l in git("grep", "-l", sha1, "--", "gamedata", "app/src/main").splitlines()
             if l and "ShinyOdds" not in l]
    gen3 = REPO / "gamedata" / "gen3"
    if RETAIL_NAME.match(file_name):
        tw, dirs = words(title) & BASE_WORDS, [gen3 / "retail", gen3 / "ports"]
    else:
        tw, dirs = (words(title) - BASE_WORDS) or words(title), [gen3 / "hacks"]
    tomls = [str(p.relative_to(REPO)) for d in dirs if d.is_dir() for p in sorted(d.glob("*.toml"))
             if tw & set(re.split(r"[_\-]", p.stem))]
    shown = ", ".join(named[:4]) + (f" (+{len(named) - 4})" if len(named) > 4 else "")
    return ([f"named in {shown}"] if named else []) + tomls[:3]


def host_roms() -> list[dict]:
    rows = []
    for line in (REPO / "scripts" / "host_roms.conf").read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) >= 3:
            rows.append({"key": parts[0], "label": parts[1], "rom": parts[2], "save": parts[3] if len(parts) > 3 else ""})
    return rows


# --- hashing and likeness ------------------------------------------------------------------------------------------

def load_cache() -> dict[str, str]:
    cache = {}
    if CACHE.exists():
        for line in CACHE.read_text().splitlines():
            parts = line.split("\t")
            if len(parts) == 2:
                cache[parts[0]] = parts[1]
    return cache


def save_cache(cache: dict[str, str]) -> None:
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text("".join(f"{k}\t{v}\n" for k, v in cache.items()))


def cache_key(p: Path) -> str:
    st = p.stat()
    return f"{p}|{st.st_size}|{int(st.st_mtime)}"


def tails(data: bytes, platform: str, width: int = 20) -> set[bytes]:
    """The bytes before each function return: Thumb `bx lr` (70 47) on the GBA, `ret` (C9) on the Game Boy."""
    marker, step = (b"\x70\x47", 2) if platform == "GBA" else (b"\xc9", 1)
    width = width if platform == "GBA" else 12
    out = set()
    for m in re.finditer(re.escape(marker), data):
        i = m.start()
        if i >= width and i % step == 0:
            w = data[i - width:i]
            if w.count(w[0:1]) != width:
                out.add(w)
    return out


def same_offset(a: bytes, b: bytes, block: int = 256, limit: int = 16 << 20) -> float:
    """Share of `a`'s 256-byte blocks (fill skipped) equal to `b`'s at the same offset, in the first 16 MB."""
    m, eq, tot = min(len(a), len(b), limit), 0, 0
    for i in range(0, m - block + 1, block):
        x = a[i:i + block]
        if x.count(x[0:1]) == block:
            continue
        tot += 1
        eq += x == b[i:i + block]
    return eq / tot if tot else 0.0


STOP = {"pokemon", "version", "the", "edition", "rom", "hack", "usa", "europe", "en", "english"}


def words(name: str) -> set[str]:
    s = unicodedata.normalize("NFKD", Path(name).stem).encode("ascii", "ignore").decode().lower()
    s = re.sub(r"\(v?[0-9][^)]*\)", " ", s)  # versions
    return {w for w in re.findall(r"[a-z0-9']+", s) if w not in STOP and not re.fullmatch(r"v?[0-9.]+", w)}


def name_likeness(a: str, b: str, drop: frozenset = frozenset()) -> float:
    wa, wb = words(a) - drop, words(b) - drop
    return len(wa & wb) / len(wa | wb) if wa and wb else 0.0


def rhh_block(data: bytes) -> dict | None:
    """pokeemerald-expansion's ROM header block: "RHHEXP", version, then move / species / item counts."""
    i = data.find(b"RHHEXP", 0, 0x400)
    if i < 0:
        return None
    b = data[i:i + 24]
    u16 = lambda o: b[o] | b[o + 1] << 8  # noqa: E731
    return {"offset": f"0x{i:X}", "version": f"{b[6]}.{b[7]}.{b[8]}", "moves": u16(10), "species": u16(12),
            "items": u16(20)}


# --- the pieces of the answer --------------------------------------------------------------------------------------

def siblings(rom: dict, data: bytes, supported: set[str], titles: dict[str, str], rows: list[dict]) -> list[dict]:
    plat = rom["platform"]
    folder = ROM_ROOT / PLATFORM_DIR.get(plat, "gba")
    if not folder.is_dir():
        return []
    cache, dirty = load_cache(), False
    mine = tails(data, plat) if plat in ("GBA", "GB", "GBC") else set()
    by_rom = {}
    for r in rows:
        by_rom.setdefault(Path(os.path.expanduser(r["rom"])).name, []).append(r)
    out = []
    for p in sorted(folder.iterdir()):
        if not p.is_file() or p.suffix.lower() not in ROM_EXTS.get(plat, set()) or p.resolve() == Path(rom["path"]).resolve():
            continue
        key = cache_key(p)
        sha = cache.get(key)
        other = None
        if sha is None or mine:
            other = p.read_bytes()
        if sha is None:
            sha, dirty = hashlib.sha1(other).hexdigest(), True
            cache[key] = sha
        code_like = len(mine & tails(other, plat)) / len(mine) if mine and other is not None else 0.0
        head = other[:0x200] if other is not None else p.open("rb").read(0x200)
        code = head[0xAC:0xB0].decode("ascii", "replace") if plat == "GBA" else (
            head[0x0C:0x10].decode("ascii", "replace") if plat == "NDS" else "")
        rev = head[0x1E] if plat == "NDS" else 0
        by_header = plat == "NDS" and (code, rev) in HEADER["NDS"]
        out.append({"file": p.name, "sha1": sha, "supported": sha in supported or by_header, "title": titles.get(sha, ""),
                    "code": code, "code_likeness": round(code_like, 2),
                    "name_likeness": round(name_likeness(rom["file_name"], p.name), 2),
                    # The name without the base games' words: "Emerald Kaizo" and "Emerald Legacy" aren't builds of one game.
                    "own_name_likeness": round(name_likeness(rom["file_name"], p.name, frozenset(BASE_WORDS)), 2),
                    "configs": config_files(sha, titles.get(sha, p.name), p.name) if sha in supported else [],
                    "fixtures": [r["key"] for r in by_rom.get(p.name, [])],
                    "saves": sorted({r["save"] for r in by_rom.get(p.name, []) if r["save"]})})
    if dirty:
        save_cache(cache)
    same_code = rom.get("code", "")[:3]
    for s in out:
        s["score"] = round(s["code_likeness"] + 0.6 * s["name_likeness"] + (0.1 if s["code"][:3] == same_code else 0), 2)
    out.sort(key=lambda s: -s["score"])
    return out


def find_saves(rom: dict) -> list[str]:
    want = words(rom["file_name"])
    hits = []
    for d in SAVE_DIRS:
        if not d.is_dir():
            continue
        for root, dirs, files in os.walk(d):
            dirs[:] = [x for x in dirs if not x.startswith(".") and x not in ("gba", "gbc", "nds", "pokedaisy-backups")]
            for f in files:
                if Path(f).suffix.lower() not in SAVE_EXTS:
                    continue
                w = words(f)
                score = len(want & w) / len(want) if want else 0
                if score >= 0.6 or Path(f).stem == Path(rom["file_name"]).stem:
                    hits.append((score, tilde(Path(root) / f)))
    return [p for _, p in sorted(set(hits), key=lambda h: -h[0])][:8]


def name_check(rom: dict) -> list[str]:
    p, notes = Path(rom["path"]), []
    folder = ROM_ROOT / PLATFORM_DIR.get(rom["platform"], "gba")
    if rom["platform"] in PLATFORM_DIR and p.parent.resolve() != folder.resolve():
        notes.append(f"move it to {tilde(folder)}/ (CLAUDE.md: retail and hacks of a platform sit there; nothing loose in ~/Downloads)")
    if not (RETAIL_NAME.match(p.name) or HACK_NAME.match(p.name)):
        notes.append("rename it: retail keeps its No-Intro name, a hack is `Pokemon <Title> (v<version>) (<variant>).<ext>`")
    return notes


def classify(rom: dict, data: bytes, known: bool, sibs: list[dict]) -> tuple[str, list[str]]:
    """The playbook id, and why."""
    why = []
    plat, code, rev = rom["platform"], rom.get("code", ""), rom.get("revision", 0)
    best = sibs[0] if sibs else None
    if known:
        return "known", ["the repo already names this SHA-1: it's supported (check docs/ROM_FEATURES.md for what's missing)"]
    # Another build of a supported hack: its code mostly that hack's and its name too (retail siblings are left to the
    # retail checks below: an in-place hack's code is all retail's).
    hack = next((b for b in sibs if b["supported"] and not RETAIL_NAME.match(b["file"])), None)
    template = next((b for b in sibs if b["supported"]), None)
    if plat == "NDS":  # a DS cart's template is the supported cart of its game (code prefix), US / Europe first
        same = [b for b in sibs if b["supported"] and b["code"][:3] == code[:3]]
        same.sort(key=lambda b: b["code"][3:] not in ("E", "O"))
        template = same[0] if same else template
    if template:
        why.append(f"closest supported game: {template['file']} (code {template['code_likeness']:.0%}): your template")
    if hack and hack["code_likeness"] >= 0.5 and hack["own_name_likeness"] >= 0.25:
        why.append(f"its code is {hack['code_likeness']:.0%} {hack['file']}'s and the name matches: another build of it")
        return "new-version", why
    if plat == "NDS":
        base = DS_BASES.get(code[:3])
        if base and RETAIL_NAME.match(rom["file_name"]):
            why.append(f"a {base} cart ({code} rev {rev}) the repo doesn't know: another language or revision")
            return "ds-port", why
        if rom.get("header_supported"):
            why.append(f"its header ({code} rev {rev}) is a supported cart's: the app reads it as that game today, so check "
                       "what that shows and tell it apart by a code word (Origin HeartGold's `OriginHeartGold.matches`)")
        why.append(f"a DS cart ({code} rev {rev}) that isn't a supported retail cart: a hack or another game"
                   + (f"; its header is {base}'s, so start from that game's tables (and Origin HeartGold's path)" if base else ""))
        return "ds-hack", why
    if plat in ("GB", "GBC"):
        fork = GB_BASES.get(rom.get("title", "").upper())
        if RETAIL_NAME.match(rom["file_name"]):
            why.append("a retail Game Boy cart the repo doesn't know (language / revision)")
            return "gb-retail", why
        why.append(f"a Game Boy hack, header title {rom.get('title')!r}" + (f": most likely a fork of pret's {fork}" if fork else ""))
        return "gb-hack", why
    if plat != "GBA":
        return "not-pokemon", ["no GBA / Game Boy / DS header"]
    if code[:3] not in {k for k, _ in RETAIL_BASE} and code[:3] not in ("PUV", "MON"):
        why.append(f"game code {code}: not a Gen 3 base game; check HACK_GAME_CODES (Poller.kt) for codes of its own")
    base_name = RETAIL_BASE.get((code[:3], rev)) or RETAIL_BASE.get((code[:3], 0))
    if not base_name and template and RETAIL_NAME.match(template["file"]) and template["code_likeness"] >= 0.5:
        base_name = template["file"]  # a code of its own (Ultra Violet's PUVV): the retail game its code is
    base = ROM_ROOT / "gba" / base_name if base_name else None
    like = same_offset(data, base.read_bytes()) if base and base.exists() else None
    rom["retail_likeness"] = round(like, 2) if like is not None else None
    if like is not None:
        why.append(f"{like:.0%} of its blocks equal {base_name}'s at the same offset")
    rhh = rhh_block(data)
    if rhh:
        rom["expansion"] = rhh
        why.append(f"pokeemerald-expansion {rhh['version']} (RHHEXP block: {rhh['species']} species, {rhh['moves']} moves, {rhh['items']} items)")
        return "expansion", why
    if rom.get("header_supported") and like is not None and like >= 0.9:
        why.append(f"{code} rev {rev} is a supported retail cart, but not this dump: a bad / trimmed / patched dump")
        return "gba-retail-variant", why
    if RETAIL_NAME.match(rom["file_name"]) and code[:3] in {k for k, _ in RETAIL_BASE} and code[3:] != "E":
        why.append("a retail cart in another language")
        return "gba-retail-port", why
    if like is not None and like >= 0.98:
        why.append("retail with a few changes: a collection dump, a flash-cart patch or a randomizer")
        return "gba-retail-variant", why
    if like is not None and like >= 0.25:
        size = rom["size"] >> 20
        why.append(f"retail code edited in place ({size} MB" + (", FireRed rev 0 at 32 MB: CFRU or HexManiacAdvance" if code.startswith("BPR") and size > 16 else "") + ")")
        return "gba-inplace", why
    why.append("no retail code at the same offsets and no RHHEXP block: a rebuild (gcc pokefirered / pokeemerald, or an engine of its own)")
    return "gba-rebuild", why


# --- main ----------------------------------------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("rom")
    ap.add_argument("--save", help="the save you mean to use (its hash and size are shown)")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--no-siblings", action="store_true", help="skip the ROM folder scan")
    ap.add_argument("--top", type=int, default=6)
    ap.add_argument("--as-new", action="store_true", help="classify it as if the repo didn't know it (to try the rules)")
    args = ap.parse_args()

    path = Path(os.path.expanduser(args.rom)).resolve()
    data = path.read_bytes()
    rom = rom_identity(path)
    supported, titles, rows = repo_sha1s(), repo_titles(), host_roms()
    HEADER.update(header_support())
    if args.as_new:
        supported.discard(rom["sha1"])
    by_header = (rom.get("code", ""), rom.get("revision", 0)) in HEADER.get(rom["platform"], set())
    # A DS cart is read by its header: a retail-named one with a supported header is known; a hack keeping retail's
    # header (RNGPLAT's CPUE rev 1) would be read as that game, which is what adding it has to check.
    known = rom["sha1"] in supported or (rom["platform"] == "NDS" and by_header and not args.as_new
                                         and bool(RETAIL_NAME.match(rom["file_name"])))
    rom["header_supported"] = by_header
    rom["known_title"] = titles.get(rom["sha1"], "") or (DS_TITLES.get((rom.get("code"), rom.get("revision")), "")
                                                         if rom["platform"] == "NDS" else "")
    sibs = [] if args.no_siblings else siblings(rom, data, supported, titles, rows)
    playbook, why = classify(rom, data, known, sibs)
    result = {
        "rom": {k: v for k, v in rom.items() if k != "path"},
        "known": known,
        "playbook": playbook,
        "playbook_file": f".claude/skills/add-rom/playbooks/{playbook}.md",
        "why": why,
        "siblings": sibs[:args.top],
        "saves": find_saves(rom),
        "sibling_saves": sorted({s for b in sibs[:3] if b["supported"] and (b["code_likeness"] >= 0.3 or b["name_likeness"] >= 0.5)
                                 for s in b["saves"]}),
        "name": name_check(rom),
    }
    if args.save:
        sp = Path(os.path.expanduser(args.save))
        result["save"] = {"path": tilde(sp), "size": sp.stat().st_size,
                          "sha1": hashlib.sha1(sp.read_bytes()).hexdigest()}
    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0

    r = result["rom"]
    print(f"{r['file_name']}")
    print(f"  {r['platform']}  code {r.get('code') or '-'}  title {r.get('title')!r}  rev {r.get('revision')}  "
          f"{r['size']} bytes ({r['size'] / 1048576:.1f} MB)")
    print(f"  SHA-1 {r['sha1']}  MD5 {r['md5']}  CRC32 {r['crc32']}")
    print(f"  in the repo: {'yes, ' + (r['known_title'] or 'untitled') if known else 'no'}")
    if r.get("expansion"):
        print(f"  expansion: {r['expansion']}")
    print(f"\nPlaybook: {playbook}  ->  {result['playbook_file']}")
    for w in why:
        print(f"  - {w}")
    if sibs:
        print("\nClosest ROMs on this machine (code = function tails shared, name = words shared):")
        for s in sibs[:args.top]:
            mark = "supported" if s["supported"] else "not supported"
            print(f"  {s['score']:.2f}  code {s['code_likeness']:.2f}  name {s['name_likeness']:.2f}  {s['file']}  [{mark}"
                  + (f": {s['title']}" if s["title"] else "") + "]")
            for c in s["configs"][:4]:
                print(f"         {c}")
            if s["fixtures"]:
                print(f"         fixtures: {', '.join(s['fixtures'][:6])}" + (" ..." if len(s["fixtures"]) > 6 else ""))
    print("\nSaves that look like its own:" if result["saves"] else "\nNo save of its own found (RetroArch's folder, the Discord saves folder).")
    for s in result["saves"]:
        print(f"  {s}")
    if result["sibling_saves"]:
        print("Siblings' saves to boot in it first (the user's rule: reuse a save before playing a new game):")
        for s in result["sibling_saves"]:
            print(f"  {s}")
    if result["name"]:
        print("\nFile:")
        for n in result["name"]:
            print(f"  - {n}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
