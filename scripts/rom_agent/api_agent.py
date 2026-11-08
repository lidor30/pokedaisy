"""The ROM agent on the plain Messages API, for an ANTHROPIC_API_KEY (add_rom_support.py's `--engine api`).

The driver's own client, not Claude Code: Anthropic's bash and text editor tools run here, web search and fetch on
Anthropic's side. Same limits as the `claude -p` launch: the blocked commands, the budget, nothing published by the
agent. Its shell gets no API key or GitHub token and can't push. A blocklist can be walked around, so this is the
guard rail the CLI's rules were, not a sandbox: run it on a machine you'd let the agent work on.
"""
from __future__ import annotations

import base64
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

import anthropic

MAX_TOKENS = 64_000
RETRY_MAX_TOKENS = 128_000  # once, for a tool call cut off by MAX_TOKENS
COMMAND_TIMEOUT = 1800  # seconds; a cold Gradle or Docker build takes minutes
OUTPUT_LIMIT = 30_000  # characters of a tool result, the start and the end
IMAGE_LIMIT = 5_000_000
COMPACT_AT = 300_000  # input tokens; CLAUDE.md alone is about 126K, and every token is read again each turn
IMAGE_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif",
               ".webp": "image/webp"}

# The agent's shell doesn't need these, and a command's output could carry them into the transcript.
SECRET_ENV = re.compile(r"^(ANTHROPIC_|AI_GATEWAY_|COPILOT_PROVIDER_|GH_TOKEN$|GITHUB_TOKEN$|GH_ENTERPRISE_TOKEN$)")

# USD per million tokens: input, output, cache read. An unknown model counts at the dearest, so the cap holds.
PRICES = {
    "claude-fable-5-1": (10.0, 50.0, 0.25),
    "claude-opus-5-5": (4.0, 20.0, 0.20),
    "claude-opus-5": (5.0, 25.0, 0.50),
    "claude-opus-4-8": (5.0, 25.0, 0.50),
    "claude-sonnet-5-5": (2.0, 10.0, 0.20),
    "claude-haiku-5-5": (0.10, 0.50, 0.01),
}
DEAREST = (10.0, 50.0, 1.0)
WEB_SEARCH_USD = 0.01
# The models whose refusals the API can hand to another model in the same call.
FALLBACK_MODELS = {"claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5"}

# The basic web tools on purpose: the _20260209 ones run code on Anthropic's side, and with them comes a sandbox with
# an editor of its own, where the agent wrote files that never reached the worktree.
TOOLS = [
    {"type": "bash_20250124", "name": "bash"},
    {"type": "text_editor_20250728", "name": "str_replace_based_edit_tool"},
    {"type": "web_search_20250305", "name": "web_search", "max_uses": 20},
    {"type": "web_fetch_20250910", "name": "web_fetch", "max_uses": 20},
]

SYSTEM_NOTE = """\
You are a coding agent working in a checkout of the PokeDaisy repository, run unattended by \
scripts/rom_agent/add_rom_support.py. The repository's CLAUDE.md follows: follow it.

Your tools: bash (one shell that keeps its directory and variables, a command stops after 30 minutes, long output is \
cut in the middle), the text editor (viewing a PNG or JPEG shows you the picture; it reaches only the worktree, the \
ROM and save folders and your work folder, and writes only the worktree and your work folder), web search and web \
fetch. You have no subagents, so where the brief says to use them, do the search yourself with grep and keep what \
you read small: every token you read stays in the conversation and is paid for again on later turns.
"""


class ToolError(Exception):
    """A tool call that can't go ahead; its message goes back to the model."""


def clip(text: str) -> str:
    if len(text) <= OUTPUT_LIMIT:
        return text
    head, tail = OUTPUT_LIMIT // 3, OUTPUT_LIMIT * 2 // 3
    return f"{text[:head]}\n\n[... {len(text) - head - tail} characters cut ...]\n\n{text[-tail:]}"


def read_clipped(path: Path) -> str:
    """A command's output, without loading a huge log to keep 30K of it."""
    size = path.stat().st_size if path.exists() else 0
    with path.open("rb") if size else open(os.devnull, "rb") as f:
        if size <= OUTPUT_LIMIT * 4:
            return clip(f.read().decode("utf-8", "replace"))
        head = f.read(OUTPUT_LIMIT // 3)
        f.seek(size - OUTPUT_LIMIT * 2 // 3)
        tail = f.read()
    return f"{head.decode('utf-8', 'replace')}\n\n[... {size - len(head) - len(tail)} bytes cut ...]\n\n" \
           f"{tail.decode('utf-8', 'replace')}"


def blocked_patterns(prefixes: list[str]) -> list[re.Pattern]:
    """The driver's blocked command prefixes as patterns: `git push` also catches `git -C x push`, and a one-word
    `gh` only where a command starts, so `ghostscript` or a path holding "gh" passes."""
    start = r"(?:^|[\n;&|(`]|\$\()\s*"
    gap = r"[^\n;&|]*"
    patterns = []
    for prefix in prefixes:
        words = [re.escape(w) for w in prefix.split()]
        if len(words) == 1:
            patterns.append(re.compile(rf"{start}(?:\S*/)?{words[0]}(?:\s|$)"))
        else:
            patterns.append(re.compile(rf"\b{words[0]}\b" + "".join(rf"{gap}(?<![\w-]){w}(?![\w-])" for w in words[1:])))
    return patterns


def shell_env() -> dict:
    env = {k: v for k, v in os.environ.items() if not SECRET_ENV.match(k)}
    # A push that gets past the blocklist has nowhere to go. The driver's own push doesn't use this environment.
    env.update({"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "remote.origin.pushurl",
                "GIT_CONFIG_VALUE_0": "/dev/null/agent-may-not-push"})
    return env


class Shell:
    """One bash for the session. A command runs from a file with its output in a file: nothing it does can jam a pipe
    here, a syntax error stays inside `source`, and an `exit` shows as the shell having gone."""

    def __init__(self, cwd: Path):
        self.cwd = cwd
        self.dir = Path(tempfile.mkdtemp(prefix="rom-agent-shell-"))
        self.proc: subprocess.Popen | None = None
        self.start()

    def start(self) -> None:
        self.proc = subprocess.Popen(["bash", "--noprofile", "--norc"], cwd=str(self.cwd), env=shell_env(), text=True,
                                     stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                     start_new_session=True)

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            try:
                os.killpg(self.proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            self.proc.wait()

    def close(self) -> None:
        self.stop()
        shutil.rmtree(self.dir, ignore_errors=True)

    def run(self, command: str, timeout: int = COMMAND_TIMEOUT) -> str:
        script, out, done = self.dir / "cmd.sh", self.dir / "out", self.dir / "done"
        script.write_text(command + "\n")
        out.write_bytes(b"")
        done.unlink(missing_ok=True)
        q = shlex.quote
        assert self.proc is not None and self.proc.stdin is not None
        self.proc.stdin.write(f"source {q(str(script))} > {q(str(out))} 2>&1 < /dev/null; "
                              f"echo $? > {q(str(done))}.tmp; mv {q(str(done))}.tmp {q(str(done))}\n")
        self.proc.stdin.flush()
        deadline = time.monotonic() + timeout
        while not done.exists():
            if self.proc.poll() is not None:
                self.start()
                return read_clipped(out) + "\n[the command ended the shell; a new one started in the worktree]"
            if time.monotonic() > deadline:
                self.stop()
                self.start()
                return read_clipped(out) + (f"\n[stopped after {timeout} s; a new shell started in the worktree. "
                                            "Run a long job in the background with its output in a file]")
            time.sleep(0.05)
        code = done.read_text().strip()
        return read_clipped(out) + (f"\n[exit status {code}]" if code != "0" else "")


class Editor:
    """The text editor tool, confined to the folders the session was given."""

    def __init__(self, cwd: Path, readable: list[Path], writable: list[Path]):
        self.cwd = cwd
        self.readable = [p.resolve() for p in readable]
        self.writable = [p.resolve() for p in writable]

    def path(self, raw, roots: list[Path]) -> Path:
        if not isinstance(raw, str) or not raw:
            raise ToolError("`path` is missing")
        p = Path(raw).expanduser()
        p = (p if p.is_absolute() else self.cwd / p).resolve()
        if not any(p.is_relative_to(root) for root in roots):
            raise ToolError(f"{raw} is outside the folders this tool may touch; use bash to read it")
        return p

    @staticmethod
    def text(p: Path) -> str:
        try:
            return p.read_text(encoding="utf-8")
        except FileNotFoundError:
            raise ToolError(f"{p} doesn't exist") from None
        except UnicodeDecodeError:
            raise ToolError(f"{p} isn't UTF-8 text; use bash (xxd, hexdump, a script) for binary files") from None

    def view(self, inp: dict):
        p = self.path(inp.get("path"), self.readable)
        if p.is_dir():
            return clip("\n".join(sorted(e.name + ("/" if e.is_dir() else "") for e in p.iterdir())))
        kind = IMAGE_TYPES.get(p.suffix.lower())
        if kind and p.is_file():
            if p.stat().st_size > IMAGE_LIMIT:
                raise ToolError(f"{p} is over {IMAGE_LIMIT} bytes; make a smaller copy first")
            return [{"type": "image", "source": {"type": "base64", "media_type": kind,
                                                 "data": base64.b64encode(p.read_bytes()).decode()}}]
        lines = self.text(p).split("\n")
        first, last = 1, len(lines)
        view_range = inp.get("view_range")
        if view_range:
            if not (isinstance(view_range, list) and len(view_range) == 2 and all(isinstance(n, int) for n in view_range)):
                raise ToolError("`view_range` is [first line, last line], -1 for the end")
            first, last = view_range[0], len(lines) if view_range[1] == -1 else view_range[1]
        return clip("\n".join(f"{i}\t{lines[i - 1]}" for i in range(max(first, 1), min(last, len(lines)) + 1)))

    def create(self, inp: dict) -> str:
        p = self.path(inp.get("path"), self.writable)
        body = inp.get("file_text")
        if not isinstance(body, str):
            raise ToolError("`file_text` is missing")
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")
        return f"wrote {p}"

    def str_replace(self, inp: dict) -> str:
        p = self.path(inp.get("path"), self.writable)
        old, new = inp.get("old_str"), inp.get("new_str", "")
        if not isinstance(old, str) or not old or not isinstance(new, str):
            raise ToolError("`old_str` is missing")
        body = self.text(p)
        count = body.count(old)
        if count != 1:
            raise ToolError(f"`old_str` is in {p} {count} times; it must be there exactly once")
        p.write_text(body.replace(old, new), encoding="utf-8")
        return f"edited {p}"

    def insert(self, inp: dict) -> str:
        p = self.path(inp.get("path"), self.writable)
        line, new = inp.get("insert_line"), inp.get("insert_text")
        if not isinstance(line, int) or not isinstance(new, str):
            raise ToolError("`insert_line` and `insert_text` are needed")
        lines = self.text(p).split("\n")
        if not 0 <= line <= len(lines):
            raise ToolError(f"{p} has {len(lines)} lines")
        lines[line:line] = new.split("\n")
        p.write_text("\n".join(lines), encoding="utf-8")
        return f"inserted into {p}"

    def run(self, inp: dict):
        handler = {"view": self.view, "create": self.create, "str_replace": self.str_replace,
                   "insert": self.insert}.get(inp.get("command"))
        if handler is None:
            raise ToolError(f"unknown command {inp.get('command')!r}")
        return handler(inp)


def usage_cost(usage, model: str) -> float:
    """What one response cost. `usage.iterations` is the per-attempt record (a fallback or a compaction is an attempt
    of its own); top-level usage covers only the last. Cache writes are all at the 1-hour rate, 2x input."""
    price_in, price_out, price_read = PRICES.get(model, DEAREST)
    cost = 0.0
    for entry in getattr(usage, "iterations", None) or [usage]:
        cost += (getattr(entry, "input_tokens", 0) or 0) * price_in
        cost += (getattr(entry, "output_tokens", 0) or 0) * price_out
        cost += (getattr(entry, "cache_creation_input_tokens", 0) or 0) * price_in * 2
        cost += (getattr(entry, "cache_read_input_tokens", 0) or 0) * price_read
    cost /= 1_000_000
    server = getattr(usage, "server_tool_use", None)
    return cost + (getattr(server, "web_search_requests", 0) or 0) * WEB_SEARCH_USD


def describe(name: str, inp: dict) -> str:
    detail = inp.get("command") if name == "bash" else f"{inp.get('command')} {inp.get('path')}"
    return f"{name}: {' '.join(str(detail).split())[:110]}"


def check_auth(model: str) -> str | None:
    """None when the key can use the model, else what the API said."""
    try:
        anthropic.Anthropic(max_retries=2, timeout=60.0).messages.create(
            model=model, max_tokens=64, messages=[{"role": "user", "content": "Reply with OK."}])
    except anthropic.APIStatusError as e:
        return f"HTTP {e.status_code}: {e.message}"[:300]
    except anthropic.APIConnectionError as e:
        return str(e)[:300]
    return None


# Conversations by session id, for the rounds that come back to one (the missing report, a failing check).
SESSIONS: dict[str, list] = {}


def run(prompt: str, cwd: Path, readable: list[Path], writable: list[Path], log_path: Path, model: str, effort: str,
        budget: float | None, blocked: list[str], resume: str | None, say) -> dict:
    """One round, until the model stops or the budget runs out. Returns `claude -p`'s result fields the driver reads."""
    session = resume or str(uuid.uuid4())
    messages = SESSIONS.setdefault(session, [])  # only ever appended to: an edit would break the cache and thinking
    messages.append({"role": "user", "content": prompt})

    project_notes = (cwd / "CLAUDE.md").read_text(encoding="utf-8") if (cwd / "CLAUDE.md").is_file() else ""
    cache = {"type": "ephemeral", "ttl": "1h"}  # a test run or a capture can outlast the 5-minute default
    betas = ["compact-2026-01-12"]
    extra = {}
    if model in FALLBACK_MODELS:
        betas.append("server-side-fallback-2026-07-01")
        extra["fallbacks"] = "default"
    client = anthropic.Anthropic(max_retries=6)
    patterns = blocked_patterns(blocked)
    shell = Shell(cwd)
    editor = Editor(cwd, readable, writable)
    cost, turns, max_tokens = 0.0, 0, MAX_TOKENS

    def finish(subtype: str, text: str, is_error: bool = True) -> dict:
        return {"subtype": subtype, "is_error": is_error, "result": text, "num_turns": turns,
                "total_cost_usd": cost, "session_id": session}

    def tool_result(block, over_budget: bool) -> dict:
        result = {"type": "tool_result", "tool_use_id": block.id}
        inp = block.input if isinstance(block.input, dict) else {}
        try:
            if over_budget:
                raise ToolError("not run: the budget is spent")
            if block.name == "bash":
                command = inp.get("command")
                if inp.get("restart"):
                    shell.stop()
                    shell.start()
                    result["content"] = "shell restarted"
                elif not isinstance(command, str) or not command:
                    raise ToolError("`command` is missing")
                elif any(p.search(command) for p in patterns):
                    raise ToolError("blocked: the driver commits, pushes and opens the PR; this command isn't yours "
                                    f"to run here ({' / '.join(blocked)})")
                else:
                    result["content"] = shell.run(command) or "(no output)"
            elif block.name == "str_replace_based_edit_tool":
                result["content"] = editor.run(inp)
            else:
                raise ToolError(f"unknown tool {block.name}")
        except (ToolError, OSError) as e:
            result.update(content=str(e), is_error=True)
        return result

    try:
        with log_path.open("a") as log:
            while True:
                try:
                    with client.beta.messages.stream(
                        model=model, max_tokens=max_tokens, betas=betas, tools=TOOLS, messages=messages,
                        system=[{"type": "text", "text": f"{SYSTEM_NOTE}\n# CLAUDE.md\n\n{project_notes}",
                                 "cache_control": cache}],
                        cache_control=cache,
                        thinking={"type": "adaptive", "display": "summarized"},
                        output_config={"effort": effort},
                        context_management={"edits": [{"type": "compact_20260112", "trigger": {
                            "type": "input_tokens", "value": COMPACT_AT}}]},
                        **extra,
                    ) as stream:
                        response = stream.get_final_message()
                except anthropic.APIStatusError as e:
                    return finish("error_during_execution", f"HTTP {e.status_code}: {e.message}")
                except anthropic.APIConnectionError as e:
                    return finish("error_during_execution", f"connection failed: {e}")

                turns += 1
                cost += usage_cost(response.usage, response.model)
                log.write(json.dumps({"type": "assistant", "session_id": session, "cost_so_far": cost,
                                      "message": response.model_dump(mode="json")}) + "\n")
                log.flush()
                calls = [b for b in response.content if b.type == "tool_use"]

                if response.stop_reason == "refusal":
                    return finish("error_during_execution", f"the model declined: {response.stop_details}")
                if response.stop_reason == "max_tokens":
                    if calls and max_tokens < RETRY_MAX_TOKENS:
                        max_tokens = RETRY_MAX_TOKENS  # a cut-off call isn't safe to run: ask again with more room
                        continue
                    return finish("error_during_execution", "the response ran out of tokens")

                messages.append({"role": "assistant", "content": response.content})
                for block in response.content:
                    text = getattr(block, "text", None) if block.type == "text" else \
                        getattr(block, "thinking", None) if block.type == "thinking" else None
                    if text and text.strip():
                        say(f"   │ {text.strip().splitlines()[0][:150]}")
                    elif block.type == "tool_use":
                        say(f"   → {describe(block.name, block.input if isinstance(block.input, dict) else {})}")

                over_budget = budget is not None and cost >= budget
                if calls:
                    messages.append({"role": "user", "content": [tool_result(b, over_budget) for b in calls]})
                if over_budget:
                    return finish("error_max_budget_usd", f"stopped at the round's budget, ${budget:.2f}")
                if response.stop_reason in ("tool_use", "pause_turn"):
                    max_tokens = MAX_TOKENS
                    continue
                final = "".join(b.text for b in response.content if b.type == "text")
                return finish("success", final, is_error=False)
    finally:
        shell.close()
