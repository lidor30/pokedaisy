#!/usr/bin/env python3
"""Add PokeDaisy support for one or more ROMs with a Claude agent, then open a PR to dev.

    scripts/rom_agent/add_rom_support.py ROM[::SAVE] [ROM[::SAVE] ...] [--list FILE] [options]
    make rom-support ROM=<rom> [SAVE=<save>] [LIST=<file>] [ARGS="--name 'Pokemon Foo v1.2' --budget 40"]

A list file has a game a line, `rom | save | name | notes` (all but the ROM optional, `#` comments, `~` paths).

What it does, in order:

 1. Reads each ROM's identity (platform, header code / title / revision, size, SHA-1, MD5, CRC32) and skips any
    whose SHA-1 is already in the repo (supported already; --force runs it anyway). The same ROM twice runs once.
 2. Makes one git worktree on a new branch off origin/<base> (dev), next to this checkout, so the run never
    touches the checkout you're working in (submodules borrowed from it, local.properties / local.mk copied).
 3. For each game in turn, runs the agent in that worktree (with ANTHROPIC_API_KEY, api_agent.py on the Messages
    API; otherwise, or with --engine claude, Claude Code headless, `claude -p`) with the brief in
    scripts/rom_agent/prompt.md: identify the game, capture RAM headless, map addresses, add the
    config / tables / fixtures / tests / docs, and write a report.json describing what it did. A later game's
    agent sees the earlier games' commits.
 4. Checks each game's change itself, not the agent's word for it: the game-data generator is in step, the
    translations check, `make test-quick`, the manual check (when website/node_modules is there), and a
    guard that no ROM, save, archive, patch, absolute home path or file over 4.5 MB is in the change.
    A failing check goes back to the same agent session to fix (--fix-rounds times).
 5. A game that passes is committed (no attribution lines: the repo's commit-msg hook rejects them). One that
    still fails is set aside as a patch in its work folder, so the next game starts clean and the others can
    still merge. Then the branch is pushed and one PR opened to <base> with `gh`, a section per game and a list
    of the ones left out. When no game passed, the failing ones are put back for a draft PR (nothing is lost);
    when none could be supported at all, no PR.

Runs on the machine that has the ROMs, the decomps, Docker and the Android SDK (the Mac in CLAUDE.md's "Local
setup"), never in a cloud session. Needs: python3 (3.10+), git, `gh` (signed in), and ANTHROPIC_API_KEY with the
`anthropic` package (requirements.txt; `make rom-support` installs it in a venv of its own), or the `claude` CLI
(--allow-no-key: `claude` runs on your own setup; --engine claude: on the key, straight to api.anthropic.com past
any gateway your own Claude settings name). A small call checks the login before anything is made.
Everything the run writes outside the repo goes to
~/.cache/pokedaisy-rom-agent/<sha1 prefix>/ per game (logs, the filled-in brief, report.json, a set-aside patch).
Exit: 0 every game committed, 3 some left out or a draft PR, 2 no game made it.
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
import tempfile
import threading
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
        # .gitmodules' URL, not the one in .git/config: every worktree shares that file, and a local path there
        # fails ("transport 'file' not allowed", git's default for submodule clones).
        url = run(["git", "config", "-f", ".gitmodules", "--get", f"submodule.{name}.url"], cwd=path).stdout.strip()
        cmd = ["git", "-c", f"submodule.{name}.url={url}", "submodule", "update", "--init"]
        if ref.is_dir():
            cmd += ["--reference", str(ref)]
        log(f"submodule {sub}")
        res = run(cmd + ["--", sub], cwd=path, check=False, timeout=1800)
        if res.returncode != 0:
            # Without the cores the agent can't capture anything, so stop before it spends the budget finding out.
            run(["git", "worktree", "remove", "--force", str(path)], cwd=REPO, check=False)
            run(["git", "branch", "-D", branch], cwd=REPO, check=False)
            die(f"submodule {sub} didn't check out:\n{tail(res.stdout, 3)}")

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


def agent_env() -> dict:
    """With an API key, `claude` talks to Anthropic itself: a gateway token or URL from the shell would take the key
    elsewhere (a 401). agent_flags() drops the same from the user's Claude settings."""
    env = dict(os.environ)
    env.setdefault("CLAUDE_CODE_ENTRYPOINT", "pokedaisy-rom-agent")
    if env.get("ANTHROPIC_API_KEY"):
        env.pop("ANTHROPIC_AUTH_TOKEN", None)
        env["ANTHROPIC_BASE_URL"] = "https://api.anthropic.com"
    return env


def agent_flags() -> list[str]:
    return ["--setting-sources", "project,local"] if os.environ.get("ANTHROPIC_API_KEY") else []


def check_agent_auth(args) -> None:
    """One small call, before any worktree or agent: a refused login otherwise only shows after minutes of retries."""
    if args.engine == "api":
        log(f"checking the key can use {args.model}")
        problem = api_agent().check_auth(args.model)
        if problem:
            die(f"ANTHROPIC_API_KEY can't use {args.model}: {problem}")
        return
    log(f"checking `claude` can reach {args.model}")
    cmd = ["claude", "-p", "Reply with OK.", "--output-format", "stream-json", "--verbose", "--model", args.model,
           "--tools", "", *agent_flags()]
    # Not in the repo: its CLAUDE.md would make this a large call.
    proc = subprocess.Popen(cmd, cwd=tempfile.gettempdir(), env=agent_env(), text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, bufsize=1)
    timer = threading.Timer(120, proc.kill)
    timer.start()
    problem = "no answer in 2 minutes"
    assert proc.stdout is not None
    try:
        for line in proc.stdout:
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            if ev.get("subtype") == "api_retry" and ev.get("error_status") in (401, 403):
                problem = f"HTTP {ev['error_status']} ({ev.get('error')})"
                break
            if ev.get("type") == "result":
                problem = str(ev.get("result"))[:300] if ev.get("is_error") else ""
                break
    finally:
        timer.cancel()
        proc.kill()
        proc.wait()
    if problem:
        how = ("The run sends ANTHROPIC_API_KEY straight to api.anthropic.com: check the key and its credit."
               if os.environ.get("ANTHROPIC_API_KEY") else
               "With no ANTHROPIC_API_KEY the run uses your own `claude` setup (settings, gateway, login) as it is.")
        die(f"`claude` can't reach {args.model}: {problem}\n{how}")


def round_done(result: dict) -> dict:
    cost = result.get("total_cost_usd")
    log(f"agent round done: {result.get('subtype')}, {result.get('num_turns')} turns"
        + (f", ${cost:.2f}" if isinstance(cost, (int, float)) else ""))
    if result.get("is_error"):
        log(f"   it ended on an error: {str(result.get('result'))[:300]}")
    return result


def api_agent():
    try:
        import api_agent as module
    except ImportError:
        die("--engine api needs the `anthropic` package: run through `make rom-support`, or "
            "`pip install -r scripts/rom_agent/requirements.txt`")
    return module


def run_agent(prompt: str, cwd: Path, extra_dirs: list[Path], log_path: Path, args, resume: str | None) -> dict:
    """One round of the agent, logged to log_path; returns its result event."""
    if args.engine == "api":
        blocked = [m.group(1) for m in map(re.compile(r"Bash\((.+):\*\)").fullmatch, DISALLOWED_TOOLS) if m]
        return round_done(api_agent().run(
            prompt, cwd, [cwd, *extra_dirs], [cwd, log_path.parent], log_path, args.model, args.effort,
            args.budget_per_round, blocked, resume, lambda line: print(line, flush=True)))

    cmd = ["claude", "-p", prompt, "--output-format", "stream-json", "--verbose",
           "--model", args.model, "--effort", args.effort,
           "--permission-mode", "dontAsk",
           "--allowedTools", *ALLOWED_TOOLS,
           "--disallowedTools", *DISALLOWED_TOOLS,
           *agent_flags()]
    for d in extra_dirs:
        cmd += ["--add-dir", str(d)]
    if args.budget_per_round:
        cmd += ["--max-budget-usd", f"{args.budget_per_round:.2f}"]
    if resume:
        cmd += ["--resume", resume]

    result: dict = {}
    with log_path.open("a") as out:
        proc = subprocess.Popen(cmd, cwd=str(cwd), env=agent_env(), text=True, stdout=subprocess.PIPE,
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
            elif ev.get("subtype") == "api_retry":
                log(f"   API retry {ev.get('attempt')} of {ev.get('max_retries')}: "
                    f"{ev.get('error_status')} {ev.get('error')}")
            elif kind == "result":
                result = ev
        proc.wait()
    if not result:
        die(f"claude exited ({proc.returncode}) without a result; see {tilde(log_path)}")
    return round_done(result)


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


def staged(wt: Path) -> list[str]:
    run(["git", "add", "-A"], cwd=wt)
    return [l for l in run(["git", "diff", "--cached", "--name-only"], cwd=wt).stdout.splitlines() if l.strip()]


def commit(wt: Path, report: dict, rom: dict) -> bool:
    """Commit everything in the worktree as this game's commit; False when there was nothing."""
    title = (report.get("commit_title") or report.get("pr_title") or f"Support {rom['file_name']}").strip()
    body = (report.get("commit_body") or report.get("summary") or "").strip()
    message = ATTRIBUTION.sub("", f"{title}\n\n{body}").strip() + "\n"
    if not staged(wt):
        return False
    msg_file = wt.parent / f".commit-msg-{rom['sha1'][:8]}.txt"
    msg_file.write_text(message)
    try:
        run(["git", "commit", "-F", str(msg_file)], cwd=wt)
    finally:
        msg_file.unlink(missing_ok=True)
    return True


def set_aside(wt: Path, patch: Path, exclude: list[str] = ()) -> bool:
    """Save the worktree's uncommitted change as a patch (minus `exclude`), then put the worktree back to HEAD."""
    files = staged(wt)
    if files:
        spec = ["--", "."] + [f":(exclude){f}" for f in exclude]
        patch.write_bytes(run(["git", "diff", "--cached", "--binary"] + spec, cwd=wt).stdout.encode())
    run(["git", "reset", "-q", "--hard", "HEAD"], cwd=wt)
    run(["git", "clean", "-fdq"], cwd=wt)  # untracked leftovers; ignored files (local.properties, builds) stay
    return bool(files)


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


def game_label(g: dict) -> str:
    r = g.get("report") or {}
    name = r.get("game_name") or g.get("name") or g["rom"]["title"] or g["rom"]["file_name"]
    return name + (f" {r['version']}" if r.get("version") else "")


def game_section(g: dict, level: str) -> list[str]:
    report, rom, save, checks = g["report"], g["rom"], g["save"], g["checks"]
    failed = [c for c in checks if not c["ok"]]
    lines = [
        (report.get("summary") or "").strip(), "",
        "**Works:**", bullet(report.get("what_works")), "",
        "**Not done:**", bullet(report.get("not_done")), "",
        f"{level} The ROM", "",
        f"- {rom['platform']} `{rom.get('code', '')}` rev {rom.get('revision', 0)}, {rom['size']} bytes",
        f"- SHA-1 `{rom['sha1']}`, CRC32 `{rom['crc32']}`",
        f"- Save: {'`' + save['file_name'] + '`' if save else 'none given (a new game was played headless, if any)'}",
    ]
    if report.get("suggested_rom_filename"):
        lines.append(f"- File name by the repo's convention: `{report['suggested_rom_filename']}`")
    lines += [
        "", f"{level} How it was checked", "", bullet(report.get("verification")), "",
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
        lines += [f"{level} For the reviewer", "", str(report["notes_for_reviewer"]).strip(), ""]
    return lines


LEFT_OUT = {
    "skipped": "already supported (its SHA-1 is in the repo)",
    "not_supported": "the agent couldn't add support",
    "failed": "checks still failed after the fix rounds; its change is saved as a patch, not in this PR",
    "refused": "its change held files that mustn't be committed; dropped",
    "no_report": "the agent wrote no usable report; dropped",
    "not_run": "not run: the run's budget was spent",
}


def pr_body(games: list[dict], cost: float, draft: bool) -> str:
    inside = [g for g in games if g["status"] == "committed"]
    left = [g for g in games if g["status"] != "committed"]
    manual = any(f.startswith("docs/manual/") for g in inside for f in g["files"])
    lines = ["## What changed", ""]
    if len(inside) == 1:
        lines += game_section(inside[0], "##")
    else:
        lines += [f"Support for {len(inside)} games, one commit each:", ""]
        lines += [f"- **{game_label(g)}**: {(g['report'].get('summary') or '').strip()}" for g in inside]
        lines.append("")
        for g in inside:
            lines += [f"## {game_label(g)}", ""] + game_section(g, "###")
    if left:
        lines += ["## Not in this PR", ""]
        for g in left:
            why = (g.get("report") or {}).get("summary") if g["status"] == "not_supported" else ""
            lines.append(f"- {game_label(g)} (`{g['rom']['file_name']}`): {LEFT_OUT[g['status']]}"
                         + (f". {why.strip()}" if why else ""))
        lines.append("")
    lines += [
        "## Checklist", "",
        f"- [{'x' if manual else ' '}] The manual (`docs/manual/`) is updated for anything a player can notice"
        + ("" if manual else ": not touched by this run, check whether supported-games.md needs the game"),
        "- [ ] New or changed screens were looked at (ui-preview / Paparazzi), and the manual's screenshots re-rendered if they changed (`make docs-shots`).",
        f"- [ ] `make check` passes (or CI does). The run itself ran `make test-quick` after each game"
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


# --- the games -----------------------------------------------------------------------------------------------------

def unquote(field: str) -> str:
    """A list field without its surrounding quotes: paths with spaces are often written "like this"."""
    field = field.strip()
    if len(field) >= 2 and field[0] == field[-1] and field[0] in "\"'":
        field = field[1:-1].strip()
    return field


def parse_games(args) -> list[dict]:
    """Every game asked for: positional ROMs (ROM::SAVE pairs a save) and --list lines (rom | save | name | notes)."""
    raw: list[tuple[str, str, str, str]] = []
    for item in args.roms:
        rom, _, save = item.partition("::")
        raw.append((unquote(rom), unquote(save), "", ""))
    if args.list:
        lst = args.list.expanduser().resolve()
        if not lst.is_file():
            stem = lst.name.split(".")[0].lower()
            near = sorted(p.name for p in lst.parent.glob("*") if p.is_file() and p.name.lower().startswith(stem[:6])) \
                if lst.parent.is_dir() else []
            die(f"no list at {lst}\n"
                + (f"files in {tilde(lst.parent)} with a name like it: {', '.join(near)}\n" if near else
                   f"nothing in {tilde(lst.parent)} has a name like it\n")
                + "a relative LIST= path is read from the repo's root (where the Makefile is); give the full path "
                  "if the file is elsewhere, and save it as plain text (TextEdit: Format > Make Plain Text)")
        if lst.suffix.lower() in {".rtf", ".rtfd", ".doc", ".docx", ".pages"}:
            die(f"{lst.name} is a formatted document; save the list as plain text (TextEdit: Format > Make Plain "
                "Text, then save as .txt)")
        # utf-8-sig: a list saved with a byte-order mark would otherwise carry it into the first path.
        for n, line in enumerate(lst.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = [unquote(p) for p in line.split("|")] + ["", "", ""]
            if not parts[0]:
                die(f"{lst}:{n}: no ROM path")
            raw.append((parts[0], parts[1], parts[2], "|".join(parts[3:]).strip("| ")))
    if not raw:
        die("no ROM given (ROM arguments and / or --list FILE)")
    if args.save:
        if len(raw) != 1 or raw[0][1]:
            die("--save goes with a single ROM; pair saves as ROM::SAVE or use --list")
        raw[0] = (raw[0][0], str(args.save), raw[0][2], raw[0][3])
    if args.name:
        if len(raw) != 1:
            die("--name goes with a single ROM; name each game in --list")
        raw[0] = (raw[0][0], raw[0][1], args.name, raw[0][3])

    games, seen = [], {}
    for rom_s, save_s, name, notes in raw:
        rom_path = Path(rom_s).expanduser().resolve()
        if not rom_path.is_file():
            die(f"no ROM at {rom_path}")
        if rom_path.suffix.lower() in {".zip", ".7z", ".rar"}:
            die(f"{rom_path.name}: unzip it first: the agent needs the ROM file itself")
        save_path = Path(save_s).expanduser().resolve() if save_s else None
        if save_path and not save_path.is_file():
            die(f"no save at {save_path}")
        rom = rom_identity(rom_path)
        if rom["platform"] == "UNKNOWN":
            die(f"{rom_path.name}: not a GBA / Game Boy / DS ROM (no header found)")
        if rom["sha1"] in seen:
            log(f"{rom_path.name}: the same ROM as {seen[rom['sha1']]}, listed once")
            continue
        seen[rom["sha1"]] = rom_path.name
        all_notes = "; ".join(n for n in (notes, args.notes.strip()) if n)
        work = Path.home() / ".cache" / "pokedaisy-rom-agent" / rom["sha1"][:12]
        games.append({"rom": rom, "rom_path": rom_path, "save_path": save_path,
                      "save": save_identity(save_path) if save_path else None,
                      "name": name, "notes": all_notes, "work": work, "report_path": work / "report.json",
                      "status": "pending", "report": None, "checks": [], "files": [], "cost": 0.0})
    return games


def brief_for(g: dict, games: list[dict], base: str) -> str:
    rom, save = g["rom"], g["save"]
    others = [o for o in games if o is not g and o["status"] != "skipped"]
    if others:
        before = [o for o in others if games.index(o) < games.index(g)]
        batch = (f"this run handles {len(others) + 1} games one at a time on the same branch, each committed before the "
                 "next starts; the others: " + "; ".join(f"`{o['rom']['file_name']}`" + (f" ({o['name']})" if o['name'] else "")
                                                        for o in others) + ". "
                 + ("The ones before yours are already committed here (or were dropped): build on their work and don't "
                    "redo it. " if before else "")
                 + "Do only your own ROM; if another is a version of the same game, say so in `notes_for_reviewer`.")
    else:
        batch = "only this game."
    values = {
        "ROM_PATH": rom["path"], "ROM_PATH_TILDE": rom["path_tilde"], "ROM_FILE": rom["file_name"],
        "PLATFORM": rom["platform"], "HEADER_TITLE": rom["title"] or "-", "HEADER_CODE": rom["code"] or "-",
        "HEADER_MAKER": rom["maker"] or "-", "REVISION": rom["revision"], "SIZE": rom["size"],
        "SIZE_MB": f"{rom['size'] / 1048576:.1f}", "SHA1": rom["sha1"], "MD5": rom["md5"], "CRC32": rom["crc32"],
        "SAVE": (f"`{save['path']}` (written `{save['path_tilde']}` in anything committed), {save['size']} bytes, "
                 f"sha1 {save['sha1']}") if save else
                "none. Start a new game headless and play it far enough to read a party (see the brief).",
        "NAME_HINT": g["name"] or "not given: find it on the title screen / in the ROM",
        "NOTES": g["notes"] or "none", "BATCH": batch,
        "REPORT_PATH": str(g["report_path"]), "WORK_DIR": str(g["work"]), "BASE": base,
        "TODAY": datetime.now().strftime("%Y-%m-%d"),
    }
    return fill(PROMPT_TEMPLATE.read_text(), values)


def do_game(g: dict, games: list[dict], wt: Path, args, budget: float) -> None:
    """One game, start to finish: the agent, the checks with fix rounds, then a commit or a set-aside patch."""
    rom, work, report_path = g["rom"], g["work"], g["report_path"]
    work.mkdir(parents=True, exist_ok=True)
    prompt = brief_for(g, games, args.base)
    (work / "prompt.md").write_text(prompt)
    report_path.unlink(missing_ok=True)
    agent_log = work / "agent.jsonl"
    extra_dirs = sorted({g["rom_path"].parent, work} | ({g["save_path"].parent} if g["save_path"] else set()))
    rounds = 1 + max(0, args.fix_rounds)

    def spend(result: dict) -> None:
        g["cost"] += float(result.get("total_cost_usd") or 0)

    def left() -> float:
        return budget - g["cost"]

    log(f"agent ({args.engine}): {args.model}, effort {args.effort}, budget ${budget:.0f} (log: {tilde(agent_log)})")
    args.budget_per_round = left() * (0.8 if rounds > 1 else 1.0)
    result = run_agent(prompt, wt, extra_dirs, agent_log, args, resume=None)
    spend(result)
    session = result.get("session_id")

    report = read_report(report_path)
    if report is None and session and left() > 1:
        log("no valid report.json; asking the agent for it")
        args.budget_per_round = min(left(), 5.0)
        spend(run_agent(f"You stopped without writing a valid {report_path}. Write it now, exactly as the brief's "
                        "last section says, for the work as it stands. Change nothing else.",
                        wt, extra_dirs, agent_log, args, resume=session))
        report = read_report(report_path)
    if report is None:
        set_aside(wt, work / "changes.patch")
        g["status"] = "no_report"
        log(f"no usable report; its change (if any) is in {tilde(work / 'changes.patch')}")
        return
    g["report"] = report
    log(f"agent says: {report['status']}: {report.get('summary', '')[:200]}")

    if report["status"] == "not_supported":
        set_aside(wt, work / "changes.patch")
        g["status"] = "not_supported"
        return

    checks: list[dict] = []
    for round_no in range(1, rounds + 1):
        checks = run_checks(wt, work, rom, g["save"], round_no, args.skip_tests)
        failed = [c for c in checks if not c["ok"]]
        if not failed:
            break
        if round_no == rounds or not session or left() < 2:
            log("checks still failing; out of fix rounds or budget")
            break
        log(f"{len(failed)} check(s) failed; fix round {round_no}")
        fix = fill(FIX_TEMPLATE.read_text(), {
            "FAILURES": "\n\n".join(f"### {c['name']}\n\n```\n{c['output']}\n```" for c in failed),
            "REPORT_PATH": str(report_path),
        })
        args.budget_per_round = left() / (rounds - round_no)
        spend(run_agent(fix, wt, extra_dirs, agent_log, args, resume=session))
        g["report"] = report = read_report(report_path) or report
    g["checks"] = checks
    g["files"] = changed_files(wt)

    failed = [c for c in checks if not c["ok"]]
    refused = guard(wt, rom, g["save"])
    if refused:
        bad = [line.split(":", 1)[0] for line in refused]
        set_aside(wt, work / "changes.patch", exclude=bad)
        g["status"] = "refused"
        log("its change held files that mustn't be committed, so it was dropped:\n   " + "\n   ".join(refused))
    elif failed:
        set_aside(wt, work / "changes.patch")
        g["status"] = "failed"
        log(f"checks failed: the change is set aside in {tilde(work / 'changes.patch')}")
    elif commit(wt, report, rom):
        g["status"] = "committed"
        log(f"committed: {game_label(g)}")
    else:
        g["status"] = "not_supported"
        log("the agent left no changes")


def recommit_failed(wt: Path, games: list[dict]) -> list[dict]:
    """Nothing passed: put the failing games' changes back, one commit each, for a draft PR (nothing is lost)."""
    back = []
    for g in games:
        patch = g["work"] / "changes.patch"
        if g["status"] != "failed" or not patch.is_file():
            continue
        if run(["git", "apply", "--3way", "--index", str(patch)], cwd=wt, check=False).returncode != 0:
            log(f"{g['rom']['file_name']}: its patch doesn't apply over the others; it stays in {tilde(patch)}")
            run(["git", "reset", "-q", "--hard", "HEAD"], cwd=wt)
            run(["git", "clean", "-fdq"], cwd=wt)
            continue
        if commit(wt, g["report"], g["rom"]):
            g["status"] = "committed"
            back.append(g)
    return back


# --- main ----------------------------------------------------------------------------------------------------------

def preflight(args) -> None:
    has_key = bool(os.environ.get("ANTHROPIC_API_KEY"))
    if args.engine == "auto":
        args.engine = "api" if has_key else "claude"
    for tool in ("git",) + (("claude",) if args.engine == "claude" else ()) + (() if args.no_pr else ("gh",)):
        if not shutil.which(tool):
            die(f"`{tool}` isn't on PATH")
    if not args.no_pr and run(["gh", "auth", "status"], check=False).returncode != 0:
        die("gh isn't signed in (gh auth login)")
    if not has_key and (args.engine == "api" or not args.allow_no_key):
        die("ANTHROPIC_API_KEY isn't set (or pass --allow-no-key when `claude` is signed in another way)")
    if not PROMPT_TEMPLATE.is_file() or not FIX_TEMPLATE.is_file():
        die("scripts/rom_agent/prompt.md or fix_prompt.md is missing")
    check_agent_auth(args)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("roms", nargs="*", metavar="ROM[::SAVE]",
                    help="ROM files (.gba / .gb / .gbc / .nds, unzipped); ROM::SAVE gives one its save")
    ap.add_argument("--list", type=Path, metavar="FILE",
                    help="a list of games, a line each: rom | save | name | notes (save, name, notes optional; # comments)")
    ap.add_argument("--save", type=Path, help="the save for a single ROM (.sav / .srm / .dsv)")
    ap.add_argument("--name", help="a single game's name and version as the player calls it, e.g. 'Pokemon Foo v1.2'")
    ap.add_argument("--notes", default="", help="anything else the agent should know, for every game in the run")
    ap.add_argument("--base", default="dev", help="the branch to start from and open the PR to (default: dev)")
    ap.add_argument("--branch", help="the branch to make (default: rom-support/<name>-<sha1> or rom-support/batch-<time>)")
    ap.add_argument("--worktree", type=Path, help="where to make the worktree (default: ../pokedaisy-rom-agent/<branch>)")
    ap.add_argument("--model", default=DEFAULT_MODEL, help=f"Claude model (default: {DEFAULT_MODEL})")
    ap.add_argument("--engine", default="auto", choices=["auto", "api", "claude"],
                    help="what runs the agent: api = api_agent.py on the Messages API, claude = `claude -p` "
                         "(default: api with ANTHROPIC_API_KEY, else claude)")
    ap.add_argument("--effort", default=DEFAULT_EFFORT, choices=["low", "medium", "high", "xhigh", "max"],
                    help=f"reasoning effort (default: {DEFAULT_EFFORT})")
    ap.add_argument("--budget", type=float, default=60.0,
                    help="USD cap per game, research + fixes (default: 60)")
    ap.add_argument("--total-budget", type=float, help="USD cap for the whole run: no game starts past it")
    ap.add_argument("--fix-rounds", type=int, default=2, help="times a failing check goes back to the agent (default: 2)")
    ap.add_argument("--no-pr", action="store_true", help="stop after the commits: no push, no PR")
    ap.add_argument("--no-draft-on-failure", action="store_true",
                    help="when no game passes, open nothing (default: a draft PR with the failing ones)")
    ap.add_argument("--skip-tests", action="store_true", help="skip `make test-quick` in the driver's checks")
    ap.add_argument("--labels", default="", help="comma-separated labels for the PR")
    ap.add_argument("--force", action="store_true", help="also run games whose SHA-1 is already in the repo")
    ap.add_argument("--allow-no-key", action="store_true", help="without ANTHROPIC_API_KEY, run `claude` on your own setup (settings, gateway, login)")
    ap.add_argument("--dry-run", action="store_true", help="print each ROM's identity and brief, run nothing")
    args = ap.parse_args()

    games = parse_games(args)
    for g in games:
        rom = g["rom"]
        log(f"{rom['file_name']}: {rom['platform']} '{rom['title']}' code {rom['code'] or '-'} rev {rom['revision']}, "
            f"{rom['size']} bytes, sha1 {rom['sha1']}" + (f", save {g['save']['file_name']}" if g["save"] else ""))
        known = already_supported(rom["sha1"])
        if known and not args.force:
            g["status"] = "skipped"
            log(f"   already supported, skipped (--force runs it anyway): {', '.join(known[:3])}")
    todo = [g for g in games if g["status"] == "pending"]
    if not todo:
        log("nothing to do")
        return 0

    if len(todo) == 1:
        slug = slug_for(todo[0]["rom"], todo[0]["name"])
    else:
        slug = f"batch-{datetime.now():%Y%m%d-%H%M}-{len(todo)}-games"
    branch = args.branch or f"rom-support/{slug}"
    wt = (args.worktree or REPO.parent / "pokedaisy-rom-agent" / branch.split("/")[-1]).expanduser().resolve()

    if args.dry_run:
        print(f"\nbranch {branch}\nworktree {tilde(wt)}")
        for g in todo:
            print(f"\n===== {g['rom']['file_name']} (work dir {tilde(g['work'])}) =====\n\n{brief_for(g, games, args.base)}")
        return 0

    preflight(args)
    make_worktree(args.base, branch, wt)
    args.budget_per_round = None
    spent = 0.0
    for i, g in enumerate(todo, 1):
        budget = args.budget
        if args.total_budget is not None:
            budget = min(budget, args.total_budget - spent)
            if budget < 2:
                g["status"] = "not_run"
                continue
        log(f"===== game {i} of {len(todo)}: {g['rom']['file_name']} =====")
        try:
            do_game(g, games, wt, args, budget)
        finally:
            spent += g["cost"]
        log(f"{g['rom']['file_name']}: {g['status']} (${g['cost']:.2f}; run so far ${spent:.2f})")

    committed = [g for g in games if g["status"] == "committed"]
    draft = False
    if not committed and not args.no_draft_on_failure:
        committed = recommit_failed(wt, games)
        draft = bool(committed)
    log("summary:\n" + "\n".join(f"   {g['status']:>13}  {game_label(g)} ({g['rom']['file_name']})" for g in games))
    if not committed:
        log(f"no game made it, so no PR. Reports and patches: ~/.cache/pokedaisy-rom-agent/; worktree {tilde(wt)}")
        return 2
    if args.no_pr:
        log(f"--no-pr: the commits stay on {branch} in {tilde(wt)}")
        return 0 if len(committed) == len(todo) else 3

    push(wt, branch)
    if len(committed) == 1:
        r = committed[0]["report"]
        title = (r.get("pr_title") or r.get("commit_title") or f"Support {committed[0]['rom']['file_name']}").strip()
    else:
        names = ", ".join(game_label(g) for g in committed)
        title = f"Support {len(committed)} games: {names}"
        title = title if len(title) <= 120 else title[:117] + "..."
    labels = [l.strip() for l in args.labels.split(",") if l.strip()]
    url = open_pr(wt, args.base, branch, title, pr_body(games, spent, draft), draft, labels)
    log(f"{'draft ' if draft else ''}PR: {url}  (agent cost ${spent:.2f})")
    log(f"when it's merged: git worktree remove {shlex.quote(tilde(wt))}")
    return 0 if len(committed) == len(todo) and not draft else 3


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
