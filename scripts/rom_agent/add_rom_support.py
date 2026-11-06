#!/usr/bin/env python3
"""Add PokeDaisy support for one ROM with a Claude agent, then open a PR to dev.

    scripts/rom_agent/add_rom_support.py ROM [--save SAVE] [options]
    make rom-support ROM=<rom> [SAVE=<save>] [ARGS="--name 'Pokemon Foo' --budget 40"]

What it does, in order:

 1. Reads the ROM's identity (platform, header code / title / revision, size, SHA-1, MD5, CRC32) and stops if
    the SHA-1 is already in the repo (the game is supported already; --force goes on anyway).
 2. Makes a git worktree on a new branch off origin/<base> (dev), next to this checkout, so the run never
    touches the checkout you're working in (submodules borrowed from it, local.properties / local.mk copied).
 3. Runs Claude Code headless (`claude -p`, billed to ANTHROPIC_API_KEY) in that worktree with the brief in
    scripts/rom_agent/prompt.md: identify the game, capture RAM headless, map addresses, add the config /
    tables / fixtures / tests / docs, and write a report.json describing what it did.
 4. Checks the result itself, not the agent's word for it: the game-data generator is in step, the
    translations check, `make test-quick`, the manual check (when website/node_modules is there), and a
    guard that no ROM, save, archive, patch, absolute home path or file over 4.5 MB is in the change.
    A failing check goes back to the same agent session to fix (--fix-rounds times).
 5. Commits (no attribution lines: the repo's commit-msg hook rejects them), pushes the branch and opens a PR
    to <base> with `gh`. A run whose checks still fail opens it as a draft; one that couldn't add support
    opens nothing and leaves the worktree for a look.

Runs on the machine that has the ROMs, the decomps, Docker and the Android SDK (the Mac in CLAUDE.md's "Local
setup"), never in a cloud session. Needs: python3 (3.9+), git, the `claude` CLI, `gh` (signed in), and
ANTHROPIC_API_KEY (or `claude` signed in another way). Everything the run writes outside the repo goes to
~/.cache/pokedaisy-rom-agent/<sha1 prefix>/ (logs, the filled-in brief, report.json).
"""
from __future__ import annotations

import argparse
import binascii
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PROMPT_TEMPLATE = HERE / "prompt.md"
FIX_TEMPLATE = HERE / "fix_prompt.md"

DEFAULT_MODEL = "claude-opus-5-5"
DEFAULT_EFFORT = "high"

# Files a change must never carry (CLAUDE.md: "Never commit ROMs, saves or secrets").
FORBIDDEN_EXTENSIONS = {
    ".gba", ".agb", ".gb", ".gbc", ".sgb", ".nds", ".dsi", ".srl",  # ROMs
    ".sav", ".srm", ".sa1", ".sa2", ".dsv", ".fla", ".eep", ".rtc",  # saves
    ".ss", ".ss0", ".ss1", ".state", ".st0", ".mst", ".ml1",  # save states
    ".zip", ".7z", ".rar", ".gz", ".tar",  # archives
    ".bps", ".ips", ".ups", ".xdelta", ".vcdiff",  # patches
    ".jks", ".keystore", ".p12", ".pem",  # keys
}
FORBIDDEN_NAMES = {"local.properties", "local.mk", "keystore.properties", "google-services.json"}
MAX_FILE_BYTES = 4_500_000  # the largest committed fixtures are DS main RAM dumps, 4 MB

# The agent must never publish anything itself: the driver commits, pushes and opens the PR.
DISALLOWED_TOOLS = [
    "Bash(git push:*)", "Bash(git commit:*)", "Bash(git remote:*)", "Bash(git worktree:*)",
    "Bash(git reset --hard:*)", "Bash(git clean:*)", "Bash(git checkout:*)", "Bash(git switch:*)",
    "Bash(git rebase:*)", "Bash(gh:*)", "Bash(adb:*)", "Bash(sudo:*)",
    "Bash(make release:*)", "Bash(make bump:*)",
]
# (Full test runs - make test / check / test-roms, a plain testDebugUnitTest - are off by the brief, not by a rule
# here: a `make test:*` prefix rule would also catch `make test-quick`.)
ALLOWED_TOOLS = ["Read", "Edit", "Write", "Glob", "Grep", "Bash", "Agent", "TodoWrite", "WebSearch", "WebFetch"]

REPORT_STATUSES = ("supported", "partial", "not_supported")


# --- small helpers -------------------------------------------------------------------------------------------------

def log(msg: str) -> None:
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def die(msg: str, code: int = 1) -> None:
    print(f"error: {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


def run(cmd, cwd: Path | None = None, check: bool = True, capture: bool = True, timeout: int | None = None,
        env: dict | None = None) -> subprocess.CompletedProcess:
    if isinstance(cmd, str):
        cmd = ["bash", "-lc", cmd]
    res = subprocess.run(cmd, cwd=str(cwd) if cwd else None, text=True, timeout=timeout, env=env,
                         stdout=subprocess.PIPE if capture else None,
                         stderr=subprocess.STDOUT if capture else None)
    if check and res.returncode != 0:
        out = (res.stdout or "")[-4000:]
        die(f"`{' '.join(shlex.quote(c) for c in cmd)}` failed ({res.returncode}):\n{out}")
    return res


def tilde(path: Path | str) -> str:
    """A path with the home folder written as ~ (CLAUDE.md: home paths are ~ in anything committed)."""
    p, home = str(path), str(Path.home())
    return "~" + p[len(home):] if p == home or p.startswith(home + os.sep) else p


def tail(text: str, lines: int = 150) -> str:
    return "\n".join(text.splitlines()[-lines:])


# --- 1. what the ROM is -------------------------------------------------------------------------------------------

NINTENDO_LOGO_GB_START = bytes.fromhex("CEED6666CC0D000B")


def rom_identity(path: Path) -> dict:
    data = path.read_bytes()
    ident = {
        "path": str(path),
        "path_tilde": tilde(path),
        "file_name": path.name,
        "size": len(data),
        "sha1": hashlib.sha1(data).hexdigest(),
        "md5": hashlib.md5(data).hexdigest(),
        "crc32": f"{binascii.crc32(data) & 0xFFFFFFFF:08x}",
    }

    def text(b: bytes) -> str:
        return b.split(b"\0")[0].decode("ascii", "replace").strip()

    suffix = path.suffix.lower()
    if len(data) >= 0x200 and (suffix == ".nds" or data[0xC0:0xC4] == bytes.fromhex("24FFAE51")):
        ident.update(platform="NDS", title=text(data[0x00:0x0C]), code=text(data[0x0C:0x10]),
                     maker=text(data[0x10:0x12]), revision=data[0x1E])
    elif len(data) >= 0x150 and data[0x104:0x10C] == NINTENDO_LOGO_GB_START:
        cgb = data[0x143]
        color = cgb in (0x80, 0xC0)
        # A Color cart's last 4 title bytes can be a manufacturer code (Crystal: PM_CRYSTAL + BYTE).
        has_code = color and all(0x30 <= c <= 0x39 or 0x41 <= c <= 0x5A for c in data[0x13F:0x143])
        ident.update(platform="GBC" if color else "GB",
                     title=text(data[0x134:0x13F if has_code else (0x143 if color else 0x144)]),
                     code=text(data[0x13F:0x143]) if has_code else "",
                     maker=f"{data[0x14B]:02x}", revision=data[0x14C], sgb=data[0x146] == 0x03, cgb_flag=cgb)
    elif len(data) >= 0xC0 and data[0xB2] == 0x96:
        ident.update(platform="GBA", title=text(data[0xA0:0xAC]), code=text(data[0xAC:0xB0]),
                     maker=text(data[0xB0:0xB2]), revision=data[0xBC])
    else:
        ident.update(platform="UNKNOWN", title="", code="", maker="", revision=0)
    return ident


def save_identity(path: Path) -> dict:
    data = path.read_bytes()
    return {"path": str(path), "path_tilde": tilde(path), "file_name": path.name, "size": len(data),
            "sha1": hashlib.sha1(data).hexdigest()}


def already_supported(sha1: str) -> list[str]:
    """Files in the repo that name this SHA-1 (configs, GameTitles, compat.json...)."""
    res = run(["git", "grep", "-il", sha1, "--", "app/src", "gamedata", "website/src/data", "3ds/core", "linux"],
              cwd=REPO, check=False)
    return [l for l in res.stdout.splitlines() if l.strip()]


def slug_for(ident: dict, name: str | None) -> str:
    base = name or ident.get("title") or Path(ident["file_name"]).stem
    base = re.sub(r"[^a-z0-9]+", "-", base.lower()).strip("-")[:40] or "rom"
    return f"{base}-{ident['sha1'][:8]}"


# --- 2. the worktree ----------------------------------------------------------------------------------------------

def make_worktree(base: str, branch: str, path: Path) -> None:
    log(f"fetching origin/{base}")
    for attempt in range(4):
        if run(["git", "fetch", "origin", base], cwd=REPO, check=False).returncode == 0:
            break
        time.sleep(2 ** (attempt + 1))
    else:
        die(f"git fetch origin {base} failed")
    if run(["git", "rev-parse", "--verify", "--quiet", f"refs/heads/{branch}"], cwd=REPO, check=False).returncode == 0:
        die(f"branch {branch} already exists (a run for this ROM already happened? delete it or pass --branch)")
    log(f"worktree {tilde(path)} on {branch} (from origin/{base})")
    run(["git", "worktree", "add", "-b", branch, str(path), f"origin/{base}"], cwd=REPO)

    # Submodules: borrow the objects this checkout already has instead of downloading them again.
    paths = run(["git", "config", "-f", ".gitmodules", "--get-regexp", r"submodule\..*\.path"], cwd=path,
                check=False).stdout.split("\n")
    for line in filter(None, paths):
        key, sub = line.split(" ", 1)
        name = key[len("submodule."):-len(".path")]
        ref = REPO / ".git" / "modules" / name
        cmd = ["git", "submodule", "update", "--init"]
        if ref.is_dir():
            cmd += ["--reference", str(ref)]
        log(f"submodule {sub}")
        res = run(cmd + ["--", sub], cwd=path, check=False, timeout=1800)
        if res.returncode != 0:
            log(f"  warning: submodule {sub} didn't check out ({tail(res.stdout, 3)}); native builds may fail")

    # Untracked local setup the build needs (CLAUDE.md "Local setup on this machine").
    for name in ("local.properties", "local.mk", "keystore.properties", "app/google-services.json"):
        src = REPO / name
        if src.exists():
            (path / name).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, path / name)


# --- 3. the agent --------------------------------------------------------------------------------------------------

def fill(template: str, values: dict) -> str:
    def sub(m: re.Match) -> str:
        key = m.group(1)
        if key not in values:
            die(f"prompt template names {{{{{key}}}}}, which the driver doesn't fill")
        return str(values[key])
    return re.sub(r"\{\{(\w+)\}\}", sub, template)


def describe_tool(block: dict) -> str:
    name, inp = block.get("name", "?"), block.get("input") or {}
    if name == "Bash":
        detail = inp.get("description") or inp.get("command", "")
    elif name in ("Read", "Edit", "Write"):
        detail = tilde(inp.get("file_path", ""))
    elif name in ("Grep", "Glob"):
        detail = inp.get("pattern", "")
    elif name == "Agent":
        detail = inp.get("description", "")
    else:
        detail = ""
    detail = " ".join(str(detail).split())
    return f"{name}: {detail[:110]}" if detail else name


def run_agent(prompt: str, cwd: Path, extra_dirs: list[Path], log_path: Path, args, resume: str | None) -> dict:
    """One `claude -p` run, streamed to log_path; returns its result event."""
    cmd = ["claude", "-p", prompt, "--output-format", "stream-json", "--verbose",
           "--model", args.model, "--effort", args.effort,
           "--permission-mode", "dontAsk",
           "--allowedTools", *ALLOWED_TOOLS,
           "--disallowedTools", *DISALLOWED_TOOLS]
    for d in extra_dirs:
        cmd += ["--add-dir", str(d)]
    if args.budget_per_round:
        cmd += ["--max-budget-usd", f"{args.budget_per_round:.2f}"]
    if resume:
        cmd += ["--resume", resume]
    env = dict(os.environ)
    env.setdefault("CLAUDE_CODE_ENTRYPOINT", "pokedaisy-rom-agent")

    result: dict = {}
    with log_path.open("a") as out:
        proc = subprocess.Popen(cmd, cwd=str(cwd), env=env, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, bufsize=1)
        assert proc.stdout is not None
        for line in proc.stdout:
            out.write(line)
            out.flush()
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                if line.strip():
                    print("   " + line.rstrip()[:160], flush=True)
                continue
            kind = ev.get("type")
            if kind == "assistant":
                for block in (ev.get("message") or {}).get("content") or []:
                    if block.get("type") == "text" and block.get("text", "").strip():
                        first = block["text"].strip().splitlines()[0]
                        print(f"   │ {first[:150]}", flush=True)
                    elif block.get("type") == "tool_use":
                        print(f"   → {describe_tool(block)}", flush=True)
            elif kind == "result":
                result = ev
        proc.wait()
    if not result:
        die(f"claude exited ({proc.returncode}) without a result; see {tilde(log_path)}")
    cost = result.get("total_cost_usd")
    log(f"agent round done: {result.get('subtype')}, {result.get('num_turns')} turns"
        + (f", ${cost:.2f}" if isinstance(cost, (int, float)) else ""))
    return result


def read_report(path: Path) -> dict | None:
    try:
        report = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(report, dict) or report.get("status") not in REPORT_STATUSES:
        return None
    return report


# --- 4. the checks -------------------------------------------------------------------------------------------------

def changed_files(wt: Path) -> list[str]:
    out = run(["git", "status", "--porcelain", "--untracked-files=all", "-z"], cwd=wt).stdout
    files, entries = [], out.split("\0")
    i = 0
    while i < len(entries):
        entry = entries[i]
        if len(entry) > 3:
            status, name = entry[:2], entry[3:]
            if "R" in status or "C" in status:
                i += 1  # the rename's old path follows
            if "D" not in status:
                files.append(name)
        i += 1
    return files


def guard(wt: Path, rom: dict, save: dict | None) -> list[str]:
    """Reasons the change can't be committed: ROMs, saves, secrets, absolute home paths, huge files."""
    problems, home = [], str(Path.home())
    banned_hashes = {rom["sha1"]} | ({save["sha1"]} if save else set())
    for name in changed_files(wt):
        p = wt / name
        if not p.is_file():
            continue
        if p.suffix.lower() in FORBIDDEN_EXTENSIONS or p.name in FORBIDDEN_NAMES:
            problems.append(f"{name}: a ROM / save / archive / patch / key file must never be committed")
            continue
        size = p.stat().st_size
        if size > MAX_FILE_BYTES:
            problems.append(f"{name}: {size} bytes, over the {MAX_FILE_BYTES} limit")
            continue
        data = p.read_bytes()
        if hashlib.sha1(data).hexdigest() in banned_hashes:
            problems.append(f"{name}: a copy of the ROM or the save")
            continue
        if len(home) > 1 and home.encode() in data:
            problems.append(f"{name}: writes the home folder out ({home}); use ~ instead")
    return problems


def run_checks(wt: Path, work: Path, rom: dict, save: dict | None, round_no: int, skip_tests: bool) -> list[dict]:
    """The driver's own checks. Each: {name, ok, output}. Logs go to the work folder."""
    checks: list[tuple[str, str, int]] = [
        ("game data in step", "python3 scripts/gen_gamedata.py --check", 600),
        ("translations", "python3 scripts/check_translations.py", 600),
        ("melonDS patch", "python3 scripts/check_melonds_patch.py", 300),
    ]
    if not skip_tests:
        checks.append(("make test-quick", "make test-quick", 3600))
    touched_docs = any(f.startswith(("docs/manual/", "website/")) for f in changed_files(wt))
    if touched_docs and ((wt / "website" / "node_modules").exists() or _link_node_modules(wt)):
        checks.append(("make docs-check", "make docs-check", 1200))

    results = []
    problems = guard(wt, rom, save)
    results.append({"name": "nothing that mustn't be committed", "ok": not problems, "output": "\n".join(problems)})
    for name, cmd, timeout in checks:
        log(f"check: {name}")
        try:
            res = run(cmd, cwd=wt, check=False, timeout=timeout)
            ok, output = res.returncode == 0, res.stdout or ""
        except subprocess.TimeoutExpired as e:
            ok, output = False, f"timed out after {timeout} s\n{(e.output or '')}"
        (work / f"check-{round_no}-{re.sub(r'[^a-z0-9]+', '-', name.lower())}.log").write_text(output)
        results.append({"name": name, "ok": ok, "output": tail(output)})
        log(f"  {'ok' if ok else 'FAILED'}")
    return results


def _link_node_modules(wt: Path) -> bool:
    """Reuse this checkout's website/node_modules in the worktree (npm ci there would download everything)."""
    src = REPO / "website" / "node_modules"
    if not src.is_dir():
        return False
    dst = wt / "website" / "node_modules"
    if not dst.exists():
        dst.symlink_to(src)
    return True


# --- 5. commit, push, PR -------------------------------------------------------------------------------------------

ATTRIBUTION = re.compile(r"^(co-authored-by:.*|.*generated with .*claude.*|.*claude-session:.*)$", re.I | re.M)


def commit(wt: Path, report: dict, rom: dict) -> None:
    title = (report.get("commit_title") or report.get("pr_title") or f"Support {rom['file_name']}").strip()
    body = (report.get("commit_body") or report.get("summary") or "").strip()
    message = ATTRIBUTION.sub("", f"{title}\n\n{body}").strip() + "\n"
    run(["git", "add", "-A"], cwd=wt)
    if not run(["git", "diff", "--cached", "--name-only"], cwd=wt).stdout.strip():
        die("the agent left no changes to commit")
    msg_file = wt.parent / f".commit-msg-{rom['sha1'][:8]}.txt"
    msg_file.write_text(message)
    try:
        run(["git", "commit", "-F", str(msg_file)], cwd=wt)
    finally:
        msg_file.unlink(missing_ok=True)


def push(wt: Path, branch: str) -> None:
    for attempt in range(5):
        res = run(["git", "push", "-u", "origin", branch], cwd=wt, check=False)
        if res.returncode == 0:
            return
        if attempt < 4:
            log(f"push failed, retrying in {2 ** (attempt + 1)} s")
            time.sleep(2 ** (attempt + 1))
    die(f"git push failed:\n{tail(res.stdout, 20)}")


def bullet(items) -> str:
    items = [str(i).strip() for i in (items or []) if str(i).strip()]
    return "\n".join(f"- {i}" for i in items) if items else "- (none)"


def pr_body(report: dict, rom: dict, save: dict | None, checks: list[dict], cost: float, draft: bool) -> str:
    manual = any(f.startswith("docs/manual/") for f in report.get("_files", []))
    failed = [c for c in checks if not c["ok"]]
    lines = [
        "## What changed", "",
        (report.get("summary") or "").strip(), "",
        "**Works:**", bullet(report.get("what_works")), "",
        "**Not done:**", bullet(report.get("not_done")), "",
        "## The ROM", "",
        f"- {report.get('game_name') or rom['title']}" + (f" {report['version']}" if report.get("version") else ""),
        f"- {rom['platform']} `{rom.get('code', '')}` rev {rom.get('revision', 0)}, {rom['size']} bytes",
        f"- SHA-1 `{rom['sha1']}`, CRC32 `{rom['crc32']}`",
        f"- Save: {'`' + save['file_name'] + '`' if save else 'none given (a new game was played headless, if any)'}",
    ]
    if report.get("suggested_rom_filename"):
        lines.append(f"- File name by the repo's convention: `{report['suggested_rom_filename']}`")
    lines += [
        "", "## How it was checked", "", bullet(report.get("verification")), "",
        "**Tests the agent ran:**", bullet(report.get("tests_run")), "",
        "**The driver's checks:**",
        "\n".join(f"- {'✅' if c['ok'] else '❌'} {c['name']}" for c in checks), "",
    ]
    if failed:
        lines += ["<details><summary>Failing output</summary>", ""]
        for c in failed:
            lines += [f"**{c['name']}**", "", "```", tail(c["output"], 60), "```", ""]
        lines += ["</details>", ""]
    if report.get("notes_for_reviewer"):
        lines += ["## For the reviewer", "", str(report["notes_for_reviewer"]).strip(), ""]
    lines += [
        "## Checklist", "",
        f"- [{'x' if manual else ' '}] The manual (`docs/manual/`) is updated for anything a player can notice"
        + ("" if manual else ": not touched by this run, check whether supported-games.md needs the game"),
        "- [ ] New or changed screens were looked at (ui-preview / Paparazzi), and the manual's screenshots re-rendered if they changed (`make docs-shots`).",
        f"- [ ] `make check` passes (or CI does). The run itself ran `make test-quick` only"
        + (" and it **failed**: draft PR." if draft else "."),
        "",
        f"_Made by `scripts/rom_agent/add_rom_support.py` (agent cost ${cost:.2f})._",
    ]
    return "\n".join(lines).rstrip() + "\n"


def open_pr(wt: Path, base: str, branch: str, title: str, body: str, draft: bool, labels: list[str]) -> str:
    body_file = wt.parent / f".pr-body-{branch.replace('/', '-')}.md"
    body_file.write_text(body)
    cmd = ["gh", "pr", "create", "--base", base, "--head", branch, "--title", title, "--body-file", str(body_file)]
    if draft:
        cmd.append("--draft")
    try:
        res = run(cmd + [a for label in labels for a in ("--label", label)], cwd=wt, check=False)
        if res.returncode != 0 and labels:
            log("gh pr create failed with labels, retrying without them")
            res = run(cmd, cwd=wt, check=False)
        if res.returncode != 0:
            die(f"gh pr create failed:\n{tail(res.stdout, 20)}")
    finally:
        body_file.unlink(missing_ok=True)
    return res.stdout.strip().splitlines()[-1]


# --- main ----------------------------------------------------------------------------------------------------------

def preflight(args) -> None:
    for tool in ("git", "claude") + (() if args.no_pr else ("gh",)):
        if not shutil.which(tool):
            die(f"`{tool}` isn't on PATH")
    if not args.no_pr and run(["gh", "auth", "status"], check=False).returncode != 0:
        die("gh isn't signed in (gh auth login)")
    if not os.environ.get("ANTHROPIC_API_KEY") and not args.allow_no_key:
        die("ANTHROPIC_API_KEY isn't set (or pass --allow-no-key when `claude` is signed in another way)")
    if not PROMPT_TEMPLATE.is_file() or not FIX_TEMPLATE.is_file():
        die("scripts/rom_agent/prompt.md or fix_prompt.md is missing")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("rom", type=Path, help="the ROM file (.gba / .gb / .gbc / .nds, unzipped)")
    ap.add_argument("--save", type=Path, help="a save for it (.sav / .srm / .dsv): the agent captures RAM from it")
    ap.add_argument("--name", help="the game's name and version as the player calls it, e.g. 'Pokemon Foo v1.2'")
    ap.add_argument("--notes", default="", help="anything else the agent should know (where it's from, what to try)")
    ap.add_argument("--base", default="dev", help="the branch to start from and open the PR to (default: dev)")
    ap.add_argument("--branch", help="the branch to make (default: rom-support/<name>-<sha1 prefix>)")
    ap.add_argument("--worktree", type=Path, help="where to make the worktree (default: ../pokedaisy-rom-agent/<slug>)")
    ap.add_argument("--model", default=DEFAULT_MODEL, help=f"Claude model (default: {DEFAULT_MODEL})")
    ap.add_argument("--effort", default=DEFAULT_EFFORT, choices=["low", "medium", "high", "xhigh", "max"],
                    help=f"reasoning effort (default: {DEFAULT_EFFORT})")
    ap.add_argument("--budget", type=float, default=60.0,
                    help="USD cap for the whole run, research + fixes (default: 60)")
    ap.add_argument("--fix-rounds", type=int, default=2, help="times a failing check goes back to the agent (default: 2)")
    ap.add_argument("--no-pr", action="store_true", help="stop after the commit: no push, no PR")
    ap.add_argument("--no-draft-on-failure", action="store_true",
                    help="open nothing when checks still fail (default: a draft PR)")
    ap.add_argument("--skip-tests", action="store_true", help="skip `make test-quick` in the driver's checks")
    ap.add_argument("--labels", default="", help="comma-separated labels for the PR")
    ap.add_argument("--force", action="store_true", help="go on even when the ROM's SHA-1 is already in the repo")
    ap.add_argument("--allow-no-key", action="store_true", help="don't require ANTHROPIC_API_KEY")
    ap.add_argument("--dry-run", action="store_true", help="print the ROM's identity and the brief, run nothing")
    args = ap.parse_args()

    rom_path = args.rom.expanduser().resolve()
    if not rom_path.is_file():
        die(f"no ROM at {rom_path}")
    if rom_path.suffix.lower() in {".zip", ".7z", ".rar"}:
        die("unzip the ROM first: the agent needs the ROM file itself")
    save_path = args.save.expanduser().resolve() if args.save else None
    if save_path and not save_path.is_file():
        die(f"no save at {save_path}")

    rom = rom_identity(rom_path)
    save = save_identity(save_path) if save_path else None
    log(f"{rom['file_name']}: {rom['platform']} '{rom['title']}' code {rom['code'] or '-'} rev {rom['revision']}, "
        f"{rom['size']} bytes, sha1 {rom['sha1']}")
    if rom["platform"] == "UNKNOWN":
        die("not a GBA / Game Boy / DS ROM (no header found)")

    known = already_supported(rom["sha1"])
    if known and not args.force:
        log("this ROM's SHA-1 is already in the repo, so it's supported (--force to run anyway):")
        for f in known:
            print(f"   {f}")
        return 0

    slug = slug_for(rom, args.name)
    branch = args.branch or f"rom-support/{slug}"
    wt = (args.worktree or REPO.parent / "pokedaisy-rom-agent" / slug).expanduser().resolve()
    work = Path.home() / ".cache" / "pokedaisy-rom-agent" / rom["sha1"][:12]
    report_path = work / "report.json"

    values = {
        "ROM_PATH": rom["path"], "ROM_PATH_TILDE": rom["path_tilde"], "ROM_FILE": rom["file_name"],
        "PLATFORM": rom["platform"], "HEADER_TITLE": rom["title"] or "-", "HEADER_CODE": rom["code"] or "-",
        "HEADER_MAKER": rom["maker"] or "-", "REVISION": rom["revision"], "SIZE": rom["size"],
        "SIZE_MB": f"{rom['size'] / 1048576:.1f}", "SHA1": rom["sha1"], "MD5": rom["md5"], "CRC32": rom["crc32"],
        "SAVE": (f"`{save['path']}` (written `{save['path_tilde']}` in anything committed), {save['size']} bytes, "
                 f"sha1 {save['sha1']}") if save else
                "none. Start a new game headless and play it far enough to read a party (see the brief).",
        "NAME_HINT": args.name or "not given: find it on the title screen / in the ROM",
        "NOTES": args.notes.strip() or "none",
        "REPORT_PATH": str(report_path), "WORK_DIR": str(work), "BASE": args.base,
        "TODAY": datetime.now().strftime("%Y-%m-%d"),
    }
    prompt = fill(PROMPT_TEMPLATE.read_text(), values)

    if args.dry_run:
        print(f"\nbranch {branch}\nworktree {tilde(wt)}\nwork dir {tilde(work)}\n\n{prompt}")
        return 0

    preflight(args)
    work.mkdir(parents=True, exist_ok=True)
    (work / "prompt.md").write_text(prompt)
    report_path.unlink(missing_ok=True)
    make_worktree(args.base, branch, wt)

    extra_dirs = sorted({rom_path.parent, work} | ({save_path.parent} if save_path else set()))
    rounds = 1 + max(0, args.fix_rounds)
    args.budget_per_round = None
    spent = 0.0

    def budget_left() -> float:
        return args.budget - spent

    # Research and build.
    log(f"agent: {args.model}, effort {args.effort}, budget ${args.budget:.0f} (log: {tilde(work / 'agent.jsonl')})")
    args.budget_per_round = budget_left() * (0.8 if rounds > 1 else 1.0)
    result = run_agent(prompt, wt, extra_dirs, work / "agent.jsonl", args, resume=None)
    spent += float(result.get("total_cost_usd") or 0)
    session = result.get("session_id")

    report = read_report(report_path)
    if report is None and session and budget_left() > 1:
        log("no valid report.json; asking the agent for it")
        args.budget_per_round = min(budget_left(), 5.0)
        result = run_agent(f"You stopped without writing a valid {report_path}. Write it now, exactly as the brief's "
                           "last section says, for the work as it stands. Change nothing else.",
                           wt, extra_dirs, work / "agent.jsonl", args, resume=session)
        spent += float(result.get("total_cost_usd") or 0)
        report = read_report(report_path)
    if report is None:
        die(f"the agent wrote no usable report; the work is in {tilde(wt)}, its log in {tilde(work)}")
    log(f"agent says: {report['status']}: {report.get('summary', '')[:200]}")

    if report["status"] == "not_supported":
        log(f"no support added, so no PR. Why: see {tilde(report_path)}; the worktree stays at {tilde(wt)}")
        return 2

    # The driver's own checks, with fix rounds.
    checks: list[dict] = []
    for round_no in range(1, rounds + 1):
        checks = run_checks(wt, work, rom, save, round_no, args.skip_tests)
        failed = [c for c in checks if not c["ok"]]
        if not failed:
            break
        if round_no == rounds or not session or budget_left() < 2:
            log("checks still failing; out of fix rounds or budget")
            break
        log(f"{len(failed)} check(s) failed; fix round {round_no}")
        fix = fill(FIX_TEMPLATE.read_text(), {
            "FAILURES": "\n\n".join(f"### {c['name']}\n\n```\n{c['output']}\n```" for c in failed),
            "REPORT_PATH": str(report_path), "ROM_PATH": rom["path"],
        })
        args.budget_per_round = budget_left() / (rounds - round_no)
        result = run_agent(fix, wt, extra_dirs, work / "agent.jsonl", args, resume=session)
        spent += float(result.get("total_cost_usd") or 0)
        report = read_report(report_path) or report

    failed = [c for c in checks if not c["ok"]]
    if any(c["name"].startswith("nothing that mustn't") for c in failed):
        die("the change holds files that mustn't be committed:\n" + next(
            c["output"] for c in failed if c["name"].startswith("nothing that mustn't")) +
            f"\nnothing committed; the worktree is at {tilde(wt)}")
    draft = bool(failed)
    if failed and args.no_draft_on_failure:
        log(f"checks failed and --no-draft-on-failure: nothing committed; the worktree is at {tilde(wt)}")
        return 3

    report["_files"] = changed_files(wt)
    commit(wt, report, rom)
    log(f"committed on {branch}")
    if args.no_pr:
        log(f"--no-pr: the commit stays on {branch} in {tilde(wt)}")
        return 0 if not failed else 3

    push(wt, branch)
    title = (report.get("pr_title") or report.get("commit_title") or f"Support {rom['file_name']}").strip()
    labels = [l.strip() for l in args.labels.split(",") if l.strip()]
    url = open_pr(wt, args.base, branch, title, pr_body(report, rom, save, checks, spent, draft), draft, labels)
    log(f"{'draft ' if draft else ''}PR: {url}  (agent cost ${spent:.2f})")
    log(f"when it's merged: git worktree remove {shlex.quote(tilde(wt))}")
    return 0 if not failed else 3


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
