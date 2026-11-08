---
name: add-rom
description: Add PokeDaisy support for a ROM - a new ROM hack, a new version / build of a supported hack, another language or revision of a retail game (GBA, Game Boy or DS), or a list of them (roms-to-add.txt). Use whenever the user says "add / support <game>", "new version of <hack>", "the <language> cart", "port <game> to <language>", hands over a ROM path or a roms-to-add list, or asks what a ROM is and whether it can be supported. Also for filling in a missing feature column (EXPAND, FLY, battle buttons...) of a supported ROM.
---

# Add a ROM

The work always runs in the same order: **identify → save → capture → prove → implement → extras → books → checklist**.
The two scripts below cover the steps a script can do. You prove the addresses; that part can't be skipped.
CLAUDE.md stays the authority on every rule. This skill only puts those rules in order.

## Interactive here, or the unattended agent?

- **One game, or the user wants to watch / steer**: follow this skill in this session.
- **Several games, nobody watching**: `make rom-support ROM=<rom> [SAVE=<save>]` or `LIST=<file>` (scripts/rom_agent/, its
  README). It opens one PR to `dev` and reads these same playbooks. Offer it for a batch of three or more. It costs API money
  (`--budget`), so ask first.

## 0. Before anything

- Work on a branch of its own off `dev` (`feature/<game>-support`, never `claude` in the name), or in a worktree.
- Rules that apply throughout: never commit ROMs, saves, states or game art, write home paths as `~`, no Claude attribution in
  commits. The user's commit rules (memory) apply: **ask before every commit**.
- Several sessions run at once on this machine: never `gradlew --stop`, never a full test run without the user's OK.

## 1. Identify: `make rom-identify ROM="<path>"`

(`python3 scripts/rom_identify.py <rom> [--json]`.) It prints:
- the header, the hashes, and whether the repo knows the SHA-1;
- the engine: same-offset likeness to retail, the expansion version (RHHEXP);
- the **closest supported ROMs on this machine** (code likeness by function tails, name likeness), each with its config,
  fixtures and saves;
- candidate saves;
- the file-name check;
- **which playbook to follow**.

Then:
1. Read `playbooks/<id>.md` (the line it prints).
2. Read the closest sibling's section in docs/DEVELOPMENT.md ("Supported ROMs" table + its own section) and its CLAUDE.md
   paragraph. **The sibling is the template: follow its path, don't invent a new one.**
3. If the file name breaks the naming rule (CLAUDE.md "Local setup"), tell the user. Rename it only with their OK, then
   grep the old name (tests skip quietly when a ROM isn't found).

| playbook | when |
|---|---|
| `known` | the repo already names the SHA-1 (fill in a missing feature instead) |
| `new-version` | another build / version of a supported hack (Monomyth v1.1, SoulGold v1.2.1, Quetzal v1) |
| `gba-retail-port` | FireRed / LeafGreen / Emerald / Ruby / Sapphire in another language or revision |
| `gba-retail-variant` | retail with a few changes: a trimmed / flash-cart dump, a UPR randomizer output, a bad dump |
| `gba-inplace` | retail code edited in place (Glazed, Kaizo, Ultra Violet, CFRU, HexManiacAdvance hacks) |
| `expansion` | pokeemerald-expansion builds (RHHEXP block: Lazarus, Imperium, Wishes, Dreamstone) |
| `gba-rebuild` | a gcc rebuild with no retail code and no RHHEXP (Recharged, Rogue, R.O.W.E., Monomyth, Elite Redux) |
| `gb-retail` | a Game Boy / Color retail cart in another language or revision |
| `gb-hack` | a Game Boy hack, usually a pret fork (Polished Crystal, the Legacy hacks, PureRGB, Prism) |
| `ds-port` | a DS cart of a supported game in another language / revision (a `gamedata/nds/ports.toml` row) |
| `ds-hack` | a DS hack, often keeping retail's header (Origin HeartGold, Renegade Platinum) |

The heuristics can be wrong. If the playbook doesn't fit what you find, say so and take the one that does.

## 2. A save

In this order (the user's rules, in memory):
1. A save of its own: identify's list, i.e. RetroArch's folder, `~/Downloads/Game ROMs & Emulation/rom saves from discord/`
   (and its `saves/`, `romhack saves/`).
2. **A sibling's save booted in it** (identify lists them). Do this for every version before anything else, and say per
   version whether it loaded. A save that doesn't load may only need converting (`convert_save_*.py`, `trim_save_block1.py`).
3. Only then a new game played headless (CLAUDE.md: Wishes of Tomorrow, Dreamstone, GS Chronicles: the game's own script
   commands give a party; `savestate` / `loadstate` play it in steps). Save it under the naming convention next to
   RetroArch's saves and tell the user where it is.

## 3. Capture fixtures headless

- Add a `scripts/host_roms.conf` line (`key|label|~ rom|~ save`, keys like the siblings').
- Capture with `scripts/capture_fixture_headless.sh <key>` (`--script native-capture/boot/<x>.txt` when the default boot
  doesn't reach the field). For a DS game use `native-capture/nds_dump` (its README).
- The rules that bit before (CLAUDE.md "Headless captures"):
  - `docker run <image> -c "<full command>"`;
  - never a `.ss` across a rebuilt ROM;
  - a battle comes from `setwildbattle` / `dowildbattle` poked into EWRAM and `call`ed through ScriptContext_SetupScript.
- Captures you'll want, as the tier needs them: the field (`<key>`), a wild battle (`<key>_battle`), a trainer battle
  (`<key>_trainer`, `_trainer_next`). Each fixture gets a `README.txt` saying what the game's own screens showed, with `~`
  paths.
- A Moemon-like build whose own art lands in RAM: zero those bytes in the dumps.

## 4. Prove, then implement like the sibling

- Every address is proven against **as many independent captures as it takes** (docs/DEVELOPMENT.md "How a new ROM gets
  supported": Emerald Rogue's false lead survived two). A ROM table is proven by decoding something end to end and looking
  at it (render an icon to a PNG and Read it).
- Configs and addresses go in `gamedata/` TOML, never Kotlin. Then `make gamedata`. A new field goes in `NativeConfig` *and*
  `gamedata/schema/gen3_config.toml`.
- Name tables come from a generator in `scripts/`, read from the ROM: extend the sibling's `GAMES` / `HACKS` table rather
  than writing a new script. Add a `GameKind` only when the game needs its own look or rules.
- Detection by hash: the SHA-1 in its `*_V<version>_SHA1` constant (+ `SUPPORTED_HACK_SHA1S` or the family's set), the
  title in `GameTitles.BY_SHA1`. The site export is `UPDATE_SITE_DATA=1 ./gradlew :app:testDebugUnitTest -Pquick --tests
  '*SiteDataExportTest'`.
- A decode test asserting the fixture's real values: a `<Game>DecodeTest`, or a case in the sibling's test. Also add the
  game to the cross-game tests its sibling sits in. Look at the sibling's commit: `git log --stat --grep=<sibling>` shows
  which ones (MoneyTest, BattleCursorTest, RomItemTextTest, WildEncountersTest, NuzlockeFixturesTest, RomArtTest...).

## 5. The extras (docs/ROM_FEATURES.md's columns)

Only what you can prove headless, each the way its own CLAUDE.md / DEVELOPMENT.md section says:
- **One-press picks**: write the cursor, press A, check the PP spent.
- **The POKéMON switch**: a `switchAddrsFor` entry, the party menu's grid or list checked.
- **Field items / PC / FLY**: the call, an in-game save, a reboot, every change kept; FLY: it lands.
- **WILD ENCOUNTERS**: walk the grass, none.
- **EXPAND**: `gen_wide_tables.py` + `verify_wide.py`, then `wideon 22` + `widecheck` exact on walking captures.
- **STEADY music**: `M4aSongs` finds the song start.
- **ROM art**: sigs + a `RomArt.SCAN_VERSION` bump.
- **DEX, GUIDE, CARD**: see their sections.

Anything partly right stays off: say what was left out and why.

## 6. The books (same commit as the support)

- **README.md**: the Supported games row (versions listed).
- **docs/ROM_FEATURES.md**: its row (CLAUDE.md: a change to a ROM's features updates its row in the same commit), plus a
  note when a column needs one.
- **docs/manual/supported-games.md**: the manual's rules apply. Plain, second person, **no em / en dashes or spaced
  hyphens, no real Pokémon / move / item names** (name the game only).
- **docs/DEVELOPMENT.md**: a "Supported ROMs" row and, if the mapping had surprises, a section in the siblings' style.
- **CLAUDE.md**: a short paragraph **only** for a gotcha a future change would trip on. It's loaded in every session, so
  keep it short.
- **NOTICE**: only if something new is bundled. It shouldn't be: no game art, ever.

## 7. Tests

- While working: the touched tests by name (`make test-quick T='*FooDecodeTest'`; `GameTitlesTest`, `SiteDataExportTest`,
  `GameDataMigrationTest`; `BestEffortTest` by name if detection / BestEffort changed).
- Before calling it done: `make test-quick`, and `make docs-check` if the manual changed.
- **Never** `make test` / `make check` / `make test-roms` / a plain `testDebugUnitTest` / every Paparazzi class / all of
  ui-preview without the user's OK. CI runs them on the PR.
- A screen you changed: render just that one (one Paparazzi class or `gradle render -Ponly=...`) and Read the PNG.

## 8. Checklist: `make rom-checklist ROM="<path>"`

(`python3 scripts/rom_checklist.py <rom> [--key K] [--save S] [--ds-port]`.) It checks every place a supported ROM has to
reach:
- detection, title, compat.json;
- README / ROM_FEATURES / manual / DEVELOPMENT naming it and its version;
- each fixture's README, RAM and a test reading it;
- the ROM agent's guard (no ROM, save, key or home path in the change);
- `gen_gamedata.py --check`, `check_translations.py`.

**0 MISSING** before you report. Go through each CHECK by hand. An unrelated untracked file the guard flags (the user's own
notes) is theirs: leave it, and mention it.

## 9. Report to the user

Keep it short and plain:
- what the player gets with this ROM (tabs and features);
- what was left out and why;
- how each address was proven (captures, headless checks);
- the save used, and any file you created outside the repo;
- the tests that ran.

Then ask before committing: one commit per game, a title in the history's style (`<Game> v<ver>: PARTY, BAG, BATTLE, DEX`).
A batch merges into `dev` the way past ones did ("Merge <x> (feature/<branch>) into dev").

## Improving this skill

When a game teaches something the next one needs, put it where it applies:
- a gotcha into its playbook here;
- a new kind of ROM into a new playbook, plus `classify()` in `scripts/rom_identify.py`;
- a new place a ROM must reach into `scripts/rom_checklist.py`.

Keep CLAUDE.md for rules, not per-game detail.
