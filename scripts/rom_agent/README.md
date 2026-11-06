# ROM support agent

`add_rom_support.py` takes one or more ROMs (with saves, if you have them), has a Claude agent add PokeDaisy support
for each the way docs/DEVELOPMENT.md's "How a new ROM gets supported" describes, checks the result and opens one PR
to `dev`.

```sh
export ANTHROPIC_API_KEY=...        # the agent's Claude API key

# one game
make rom-support ROM="~/Downloads/Game ROMs & Emulation/gba/Pokemon Foo (v1.2).gba" \
                 SAVE="~/Documents/RetroArch/saves/mGBA/Pokemon Foo (v1.2).srm" \
                 ARGS="--name 'Pokemon Foo v1.2' --budget 40"

# several: a list file, or ROM::SAVE pairs on the command line
make rom-support LIST=~/roms-to-add.txt ARGS="--total-budget 200"
scripts/rom_agent/add_rom_support.py "a.gba::a.srm" b.gba "c.gbc::c.sav" [--dry-run]
```

A list file has a game a line: `rom | save | name | notes`, everything but the ROM optional, `#` for comments:

```
# rom                                                      | save                                           | name              | notes
~/Downloads/Game ROMs & Emulation/gba/Pokemon Foo (v1.2).gba | ~/Documents/RetroArch/saves/mGBA/Pokemon Foo (v1.2).srm | Pokemon Foo v1.2  | CFRU, from its Discord
~/Downloads/Game ROMs & Emulation/gba/Pokemon Bar (v3.0).gba |                                                  | Pokemon Bar v3.0  |
```

Run it on the machine with the ROMs, the decomps (`local.mk`), Docker and the Android SDK: the agent captures RAM
headless and runs `make test-quick`. It needs `git`, the `claude` CLI, `gh` (signed in) and python3 3.9+.

`ANTHROPIC_API_KEY` goes straight to `api.anthropic.com`: the run skips your user Claude settings and any
`ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_BASE_URL` in your shell, so a gateway set up there isn't used. `--allow-no-key`, with
no key set, runs `claude` on your own setup as it is. A small call checks the login before anything is made.

## What happens

1. Each ROM's identity (header, size, SHA-1 / MD5 / CRC32). A SHA-1 already in the repo is skipped (`--force`); the
   same ROM listed twice runs once.
2. One git worktree from `origin/dev`, next to this checkout (`../pokedaisy-rom-agent/<branch>`), on
   `rom-support/<name>-<sha1>` for one game or `rom-support/batch-<time>-<n>-games` for several: your checkout and
   other agents' work aren't touched.
3. For each game in turn, `claude -p` in that worktree with `prompt.md` filled in (CLAUDE.md loads by itself; the
   brief names the other games in the run, and a later game's agent sees the earlier ones' commits). It may read,
   edit and run anything but publish: commits, pushes, `gh`, resets and branch switches are blocked, and full test
   runs are ruled out by the brief. It ends by writing `report.json`.
4. The driver's own checks, after each game: `gen_gamedata.py --check`, `check_translations.py`,
   `check_melonds_patch.py`, `make test-quick`, `make docs-check` when the manual changed (with `website/node_modules`
   borrowed from this checkout), and a guard: no ROM / save / state / archive / patch / key file, no copy of the ROM
   or save, nothing over 4.5 MB, no absolute home path. Failures go back to the same agent session (`--fix-rounds`, 2).
5. A game that passes is one commit, with its report's message (attribution lines stripped; the commit-msg hook
   applies). One that still fails is set aside as `changes.patch` in its work folder and the worktree reset, so the
   next game starts clean and the rest can merge without it. Then a push and one `gh pr create --base dev`: a section
   per game, and "Not in this PR" for the skipped, unsupported and failed ones.
   - No game passed but some failed: their changes are put back, a commit each, for a **draft** PR.
   - None could be supported at all: no PR.

Per game, under `~/.cache/pokedaisy-rom-agent/<sha1 prefix>/`: `agent.jsonl` (the whole session; `claude --resume
<session id>` from the worktree picks it up by hand), `prompt.md`, `report.json`, the checks' logs, `changes.patch`.

## Options

| | |
| --- | --- |
| `--save`, `--name` | a single game's save and name (several: `ROM::SAVE` and the list's columns) |
| `--notes` | anything else the agent should know, for every game in the run |
| `--base dev` | the branch to start from and open the PR to |
| `--model`, `--effort` | `claude-opus-5-5`, `high` by default |
| `--budget 60` | USD per game (research + fixes), enforced by `claude --max-budget-usd` per round |
| `--total-budget` | USD for the whole run: no game starts once it's spent |
| `--fix-rounds 2` | times failing checks go back to the agent |
| `--allow-no-key` | with no `ANTHROPIC_API_KEY`, use your own `claude` setup |
| `--no-pr` | commit only; `--no-draft-on-failure`: no draft PR when no game passed |
| `--skip-tests`, `--labels a,b`, `--branch`, `--worktree` | |
| `--dry-run` | print each identity and brief, run nothing |

Exit status: 0 every game committed, 3 some left out (or a draft PR), 2 no game made it.

When the PR is merged: `git worktree remove ../pokedaisy-rom-agent/<branch>` and delete the branch.
