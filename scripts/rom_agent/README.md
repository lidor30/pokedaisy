# ROM support agent

`add_rom_support.py` takes a ROM (and a save, if you have one), has a Claude agent add PokeDaisy support for it the way
docs/DEVELOPMENT.md's "How a new ROM gets supported" describes, checks the result and opens a PR to `dev`.

```sh
export ANTHROPIC_API_KEY=...        # the agent's Claude API key
make rom-support ROM="~/Downloads/Game ROMs & Emulation/gba/Pokemon Foo (v1.2).gba" \
                 SAVE="~/Documents/RetroArch/saves/mGBA/Pokemon Foo (v1.2).srm" \
                 ARGS="--name 'Pokemon Foo v1.2' --budget 40"
# or: scripts/rom_agent/add_rom_support.py ROM [--save SAVE] [--name ...] [--notes ...] [--dry-run]
```

Run it on the machine with the ROMs, the decomps (`local.mk`), Docker and the Android SDK: the agent captures RAM
headless and runs `make test-quick`. It needs `git`, the `claude` CLI, `gh` (signed in) and python3 3.9+.

## What happens

1. The ROM's identity (header, size, SHA-1 / MD5 / CRC32). A SHA-1 already in the repo stops the run (`--force`).
2. A git worktree on `rom-support/<name>-<sha1>` from `origin/dev`, next to this checkout
   (`../pokedaisy-rom-agent/<slug>`): your checkout and other agents' work aren't touched.
3. `claude -p` in that worktree with `prompt.md` filled in (CLAUDE.md loads by itself). It may read, edit and run
   anything but publish: commits, pushes, `gh`, resets and branch switches are blocked, and full test runs are
   ruled out by the brief. It ends by writing `report.json`.
4. The driver's own checks: `gen_gamedata.py --check`, `check_translations.py`, `check_melonds_patch.py`,
   `make test-quick`, `make docs-check` when the manual changed (with `website/node_modules` borrowed from this
   checkout), and a guard: no ROM / save / state / archive / patch / key file, no copy of the ROM or save, nothing
   over 4.5 MB, no absolute home path. Failures go back to the same agent session (`--fix-rounds`, 2).
5. A commit with the report's message (attribution lines stripped; the commit-msg hook applies), a push and
   `gh pr create --base dev`. Checks still failing: a **draft** PR with the output. `not_supported`: no PR.

Logs, the filled-in brief and the report: `~/.cache/pokedaisy-rom-agent/<sha1 prefix>/` (`agent.jsonl` is the whole
session; `claude --resume <session id>` from the worktree picks it up by hand).

## Options

| | |
| --- | --- |
| `--name`, `--notes` | what the player calls it; anything else the agent should know |
| `--base dev` | the branch to start from and open the PR to |
| `--model`, `--effort` | `claude-opus-5-5`, `high` by default |
| `--budget 60` | USD for the whole run (research + fixes), enforced by `claude --max-budget-usd` per round |
| `--fix-rounds 2` | times failing checks go back to the agent |
| `--no-pr` | commit only; `--no-draft-on-failure`: nothing at all when checks fail |
| `--skip-tests`, `--labels a,b`, `--branch`, `--worktree` | |
| `--dry-run` | print the identity and the brief, run nothing |

When the PR is merged: `git worktree remove ../pokedaisy-rom-agent/<slug>` and delete the branch.
