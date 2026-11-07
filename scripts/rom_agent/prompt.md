# Add PokeDaisy support for one ROM

You're running unattended, started by `scripts/rom_agent/add_rom_support.py`. Nobody will answer a question:
when you'd ask, decide the way CLAUDE.md and docs/DEVELOPMENT.md point, and say what you decided in the report.
Your working directory is a fresh git worktree of the repo on its own branch, made from `origin/{{BASE}}`. When
you're done, the driver checks your work itself (game data in step, translations, `make test-quick`, the manual,
nothing that mustn't be committed), commits it and opens a PR to `{{BASE}}`. A failing check comes back to you.

## The ROM

- File: `{{ROM_PATH}}` (write it `{{ROM_PATH_TILDE}}` in anything committed)
- Platform: {{PLATFORM}}, header title `{{HEADER_TITLE}}`, code `{{HEADER_CODE}}`, maker `{{HEADER_MAKER}}`,
  revision {{REVISION}}
- Size: {{SIZE}} bytes ({{SIZE_MB}} MB)
- SHA-1 `{{SHA1}}`, MD5 `{{MD5}}`, CRC32 `{{CRC32}}`
- Save: {{SAVE}}
- What the player calls it: {{NAME_HINT}}
- Notes from whoever started the run: {{NOTES}}
- This run: {{BATCH}}
- Today: {{TODAY}}. A folder of your own outside the repo, for scratch files, dumps and the report: `{{WORK_DIR}}`

The ROM and the save are the player's own files: read them, never move, rename, change or copy them into the repo.

## The goal

The most of PokeDaisy's companion this ROM can honestly have, **every address and table proven, none guessed**,
added the way the repo adds games, so it reads as one more of the many entries CLAUDE.md describes. In order:

1. **Recognised**: the app knows the file (by hash, like every hack: `SUPPORTED_HACK_SHA1S` /
   `fireRedHackConfig` / the small-BPRE / BPEE checks, `HACK_GAME_CODES`, `*_SHA1S` sets for Game Boy and DS,
   whichever fits), with its name and version in `GameTitles.BY_SHA1`.
2. **Reads**: PARTY (species, level, HP, status, moves, held item), BAG, the current map section, money; then
   BATTLE (battlers, the foe's party), DEX, MAP, GUIDE (HERE / NEXT BOSS) where the game's data allows.
3. **Does**: one-press battle picks, the POKéMON switch, field items / PC / FLY, WILD ENCOUNTERS, EXPAND and the
   other per-ROM features in docs/ROM_FEATURES.md: only those you can prove headless. Skip the rest, say so.

Stop at the tier you can prove. A game that's recognised and reads its party right is worth a PR; one with a
battle tab showing the wrong foe isn't. Everything you couldn't do goes in the report's `not_done`.

## Read first

CLAUDE.md is already in your context. Then read, before changing anything:

- docs/DEVELOPMENT.md: "Supported ROMs" (how each game was mapped), "How a new ROM gets supported", and the
  section of whichever game yours turns out closest to.
- gamedata/README.md (configs and addresses live in the TOML, never in Kotlin; `make gamedata` after an edit).
- README.md's Supported games table, docs/ROM_FEATURES.md, docs/manual/supported-games.md and docs/manual/README.md
  (the manual's writing rules).
- native-capture/README.md (mgba_dump: `dump`, `call`, `fieldinfo`, `wideon` / `widecheck`, `savestate` ...),
  scripts/capture_fixture_headless.sh and scripts/host_roms.conf. For a DS game, native-capture/nds_dump's README.

## How to work

0. **The repo's own process**: `.claude/skills/add-rom/SKILL.md` is how this repo adds a ROM, and you follow it. Run
   `python3 scripts/rom_identify.py "{{ROM_PATH}}"`. It names the closest supported game, the saves to try and a playbook
   (`.claude/skills/add-rom/playbooks/<id>.md`): read that playbook and work through it. The steps below say the same
   things in brief. Where the skill says to ask the user or to wait for a commit, decide the way this brief says instead:
   nobody is there, and the driver commits.

1. **Identify it.** Which engine is this: a retail build (another language / revision, a collection dump, a
   randomized retail: `RetailDumps`, `RandomizedRetail`), retail code edited in place (Glazed, Kaizo, Ultra
   Violet, Rocket Edition), CFRU (Unbound, Radical Red), pokeemerald-expansion (which version: its RHHEXP block,
   the debug menu), a gcc rebuild of pokefirered / pokeemerald (Recharged Yellow / Emerald), a pret Game Boy fork,
   a DS game or hack? Compare the ROM against the retail ROM of its base game word for word (`RETAIL_ROM_DIR`,
   CLAUDE.md's "Local setup"), look for the hack's source online (a public repo at the release's tag can be built
   with Docker and read with `nm`, which beats any guess), and find the supported game it's closest to:
   `git grep` the header code, the sizes, the generator scripts' `GAMES` / `HACKS` tables. The closest game's
   config, generator and DEVELOPMENT.md section are your template; follow its path, don't invent a new one.
   If it's a new revision of a game already supported, map the new ROM onto the old one (literal pools, word
   pairs) and check whether the RAM moved before doing anything else.
2. **Capture RAM headless.** Add a `scripts/host_roms.conf` line for it (`~` paths, a key in the repo's style),
   then capture fixtures with `scripts/capture_fixture_headless.sh` (or mgba_dump / nds_dump directly), a boot
   script under `native-capture/boot/` if the default one doesn't reach the field. The Docker image builds on
   first use. Without a save, play a new game headless far enough for a party (CLAUDE.md: Wishes of Tomorrow,
   Dreamstone, GS Chronicles did it with the game's own script commands). Write that save into your work folder
   under the name CLAUDE.md's convention gives it, never into the repo or the player's folders; name it in
   host_roms.conf and the fixture README as `~/Documents/RetroArch/saves/mGBA/<that name>` and say in
   `notes_for_reviewer` that it has to be moved there. Remember CLAUDE.md's "Headless captures" gotchas (`docker run
   <image> -c "..."`, never a `.ss` across a rebuilt ROM, scripted wild battles through ScriptContext_SetupScript).
3. **Find each address the way DEVELOPMENT.md says**: a checksum scan for gPlayerParty, pointers that resolve to
   confirmed addresses, then **as many independent captures as it takes** (a later boot, more play time, a
   different first `wait`). Two matching captures aren't proof (Emerald Rogue's false lead survived two). Decode
   something end to end and look at it (render an icon to a PNG and Read it) before trusting a ROM table.
4. **Implement it like its siblings**: the config in `gamedata/` (+ `make gamedata`), name tables from a
   generator in `scripts/` (extend the closest one's `GAMES` table rather than writing a new script when it
   fits; names are read from the ROM, never typed in), a `GameKind` only if the game really needs its own look
   or rules. A fixture under `app/src/test/resources/fixtures/<key>/` with the `README.txt` the others have
   (what was on the game's own screens, `~` paths), and a `<Game>DecodeTest` asserting those values.
5. **Then the extras**, each proven headless the way its own section says (one-press picks: write the cursor,
   press A, check the PP spent; FLY: land; EXPAND: `widecheck` exact on walking captures; field items: the call,
   an in-game save and a reboot). Battle work needs a `*_battle` fixture.
6. **Keep the repo's books** in the same change, as CLAUDE.md requires: README.md's Supported games row,
   docs/ROM_FEATURES.md's row, docs/manual/supported-games.md (the manual's rules: plain, second person, no em /
   en dashes or spaced hyphens, no real Pokémon / move / item names: name the game itself only), a
   docs/DEVELOPMENT.md section in the style of the others (how it was mapped, its gotchas), the website's
   `compat.json` (`UPDATE_SITE_DATA=1 ./gradlew :app:testDebugUnitTest -Pquick --tests '*SiteDataExportTest'`),
   NOTICE if you bundle anything new (you shouldn't need to: no game art, ever), and a short CLAUDE.md paragraph
   for the game in the style of the others **only** if it has a gotcha a future change would trip on.

## Rules

- **Tests**: `make test-quick`, and the tests you touched by name while working
  (`make test-quick T='*FooDecodeTest'`; `GameTitlesTest`, `SiteDataExportTest`, `GameDataMigrationTest`,
  `BestEffortTest` by name when you touch detection). **Never a full run** (`make test`, `make check`,
  `make test-roms`, a plain `./gradlew :app:testDebugUnitTest`, every Paparazzi class, all of ui-preview): CI runs
  them on the PR. `make test-quick` must pass before you write the report. If gradle can't build at all (no
  Android SDK), say so in the report rather than working around it.
- **UI**: you shouldn't need any. If you change a screen, render just that one (ui-preview `-Ponly=...`, one
  Paparazzi class) and Read the PNG.
- **Never commit** ROMs, saves, states, patches, secrets, or the game's art (fixtures are RAM dumps; zero any of
  the game's own decompressed graphics in them if the game's art is in RAM, like the Moemon captures). Home paths
  are `~`. The driver refuses the whole change otherwise.
- **Git**: leave everything uncommitted in the worktree. No commit, push, branch switch, reset or `gh`: the
  driver does those, and they're blocked. Don't touch `local.properties` / `local.mk`.
- **Honesty**: an address you couldn't confirm stays out (or zeroed / null, like the configs that leave a field
  unknown). A feature that's partly right is worse than one that's off. If the game can't be supported at all
  (a format nothing here reads, protection, a broken dump), change nothing in the repo and say why.
- **Budget**: you have a dollar limit. Spend it on proving addresses, not on exploring features the game is
  unlikely to get. If you're running low, finish the tier you're on, make `make test-quick` pass, and report.
  Use subagents for wide read-only searches so your own context stays on the mapping.

## Before the report: the checklist

Run `python3 scripts/rom_checklist.py "{{ROM_PATH}}" --no-run` (`--ds-port` for a DS language port). Fix every MISSING
line it can fix, and look at each CHECK line. Anything you leave goes in `notes_for_reviewer` with the reason.

## When you're done: the report

Write `{{REPORT_PATH}}` (JSON, UTF-8), even when you gave up. The driver reads it for the commit and the PR, so
write the prose for a reviewer who knows the repo: plain, short, no em / en dashes. Fields:

```json
{
  "status": "supported | partial | not_supported",
  "game_name": "the game's name as the repo writes it, e.g. Pokémon Foo",
  "version": "v1.2.3, or empty",
  "summary": "2-4 sentences: what the player will see in PokeDaisy with this ROM",
  "what_works": ["PARTY, BAG, map section, money", "BATTLE: battlers and the foe's party", "..."],
  "not_done": ["FLY: no Fly special found", "EXPAND: not mapped", "..."],
  "verification": ["gPlayerParty 0x0202xxxx: 4 captures (boot, +20 min play, a battle, a reboot)", "..."],
  "tests_run": ["make test-quick: passed", "make test-quick T='*FooDecodeTest': passed", "..."],
  "commit_title": "one line, like the repo's history: 'Pokemon Foo v1.2: PARTY, BAG, BATTLE, DEX'",
  "commit_body": "a few lines on what was added and how it was mapped; no attribution lines",
  "pr_title": "Support Pokemon Foo v1.2",
  "notes_for_reviewer": "anything to look at closely, things you weren't sure of, what to try on a device",
  "suggested_rom_filename": "the name CLAUDE.md's convention gives this file, if it differs from its name now"
}
```

`supported` = recognised and the reads in tier 2 that apply to it work; `partial` = recognised with fewer reads;
`not_supported` = nothing added. The report is the last thing you do; after it, stop.
