# PokeDaisy — Project Notes for Claude

Dual-screen Android app (AYN Thor, Retroid Pocket Duo): an embedded libmgba core on the
top screen and a live companion for Gen 3 Pokémon games and ROM hacks on the bottom
screen. Read `README.md` for the user-facing feature list and `docs/DEVELOPMENT.md` for the
per-ROM support details, how each game was mapped, frontend setup and releasing; this file is
process context that isn't obvious from the code. `docs/ROM_FEATURES.md` tracks which ROM has EXPAND, battle
buttons, one-press picks, the POKéMON switch, field items, FLY and the card: a change to any of those for a ROM (or a
new ROM) updates its row in the same commit.

**This repo is public.** Never commit ROMs, saves or secrets. The SteamGridDB API key is
entered by the user at runtime and must stay that way. Game art (FireRed / Emerald's and the
hacks') is rebuilt from the player's own ROM (`RomArt`), not bundled. Keep it that way. Write home paths as `~` in
anything committed (fixture READMEs: `capture_fixture_headless.sh` does). **Commits carry no Claude /
Anthropic attribution** (no `Co-Authored-By`, no "Generated with" lines, never authored as Claude) - the
user's firm rule; a local `commit-msg` hook rejects them, never bypass it.
**Exposed surfaces**: `LaunchActivity` is exported - it refuses the app's own files / provider, copies at
most 40 MB (512 MB with DS games on: `RomIntake.maxCopyBytes`) and plays only what has a cart header (`RomIdentity.looksLikeRom`); the updater installs only
this package, same signer, newer version (`AppUpdateFlow.isOurUpdate`); the website's release notes are
escaped (`website/src/lib/releases.js`, `website/test/releases.test.js`); workflow actions are pinned to
commit SHAs. `CoopLinkActivity` is exported for `pokedaisy://coop/<ticket>` only: it passes a `CoopInvite.parse`d ticket to the unexported Settings, nothing else.
New bundled code / fonts / data go into NOTICE and the LICENSES page (`licensesAsset`).
**Blocked games** (`companion/data/BlockedGames.kt`, 2026-11-12; Ghost Grey, at its developer's request): a list by SHA-1 and CRC32,
each with its reason and an optional link button. In a release a listed game plays as a plain game: the companion shows `BlockedView`
(no picker, no BEST EFFORT, no ASK FOR SUPPORT), the activity turns off EXPAND, the field reads, the game-option patches, the makeover
and the field items (`romBlocked`), the library tags it BLOCKED (black) and INFO says why; the site leaves it out. The support code stays.
A new entry: its hashes, CRCs, sizes, reason (+ its
eight translations in TrCompanion), the manual's "Games removed at their makers' request" and ROM_FEATURES' row.

**Co-op link** (`companion/coop/`; docs/DEVELOPMENT.md's "Co-op connection"): pure Kotlin, no native code - `RelayCoopTransport` over
our own WebSocket relay (`server/coop-relay/`, a separate Kotlin / JVM program on Java-WebSocket, never in the APK; its README covers deploying it) plus direct
TCP and Android NSD on the same Wi-Fi, every path carrying a Noise IK channel (`CoopChannel`, `Noise_IK_25519_AESGCM_SHA256`, Bouncy
Castle's X25519), poll-based (`poll()`); nothing from the network reaches game memory (`CoopSafetyTest`); pairs per save
(`CoopSaveId`), English Emerald only, refused with cheats loaded; the relay URL (`COOP_RELAY_URL`) is a build secret, never committed,
and with none the link is the same Wi-Fi and BY IP only. **Behind `FeatureFlags.coop`** (off; `-Ppokedaisy.coop=true` /
local.properties / marker `files/features/coop`): its rows' keywords sit in `FLAGGED_SETTING_KEYWORDS` and the manual page is a draft
in `docs/manual-drafts/coop.md` (move it into `docs/manual/` with the settings-reference / synonyms entries when the flag goes).
Transport start / close / poll never on the UI thread.
**Co-op joined battles** (Emerald, behind the same flag; docs/DEVELOPMENT.md's "Co-op joined battles" and "The link bridge", messages `0x0200`-`0x02FF`):
a nearby free partner (same map, within SETTINGS > CO-OP > NEARBY RANGE, `Prefs.coopNearbyRange`, 2 / 4 / 8 / 16 / 32 steps) joins a wild,
two-trainer or one-trainer (2+ Pokémon) battle with their first three able Pokémon, and a story battle (gym leader, rival, a boss) in every save when they
stand at the same spot (same trainer, not beaten; the game's own choose-three screen). **A joined battle is Emerald's own link battle on both devices** (the tower's
link multi, host = link master, `pk_coop_link*.c`, link.c's blocks carried by `CoopLinkFlow` over the co-op link, no serial emulation): each player sees it from their
own side with their own game's menus, the app draws only the join prompt, the waits and notices. Rules: the prize is what a two-trainer battle pays (no x2); a ball works
only with one wild Pokémon left and whoever catches it keeps it (the host holds the partner's catch until the partner has saved it); each side's EXP / bag is its own; a player
blacks out only when no Pokémon in the whole party can fight; PLAYER LOOK / TEXT LOOK must be off; a dropped link waits 60 s, then a story battle is a loss with a blackout for
both (every save, also on the team-pick screen) and any other battle ends for both with no prize, the trainer not beaten, what was earned kept, no evolution from it. A stop
always puts the game back before any resume / suspend state is written (a stop that can't be confirmed writes none). Its manual text is the "Battling together" part of the draft.

## The user manual (`docs/manual/`): keep it current

**Every change a player can notice updates the manual in the same change**: a new feature, setting, tab,
button or supported game, a changed default, a renamed label, a removed option (if unsure, it counts). The
manual is Markdown in `docs/manual/` (`README.md` there has the format and the writing rules) and the *only*
place it's written: the website renders it as `/docs/` (static pages, for search engines) and as the docs
API `/api/docs/v1/docs.json` (`website/src/lib/docs.js`), which the app's HELP reader (`DocsApi.kt`,
`companion/ui/DocsScreen.kt`: top-screen Settings > HELP, the library's `?`, companion SETTINGS > HELP)
fetches and keeps for offline reading. Both deploy with an app release, like the rest of the site.
Writing rules (the user's): human and plain, second person, **no em / en dashes** (nor a spaced hyphen for
one), **no real Pokémon / move / item names** in text or pictures (the invented creatures EMBERPUP & co. in
`website/src/lib/creatures.js` stand in, also on the homepage demos). Reading text is Atkinson Hyperlegible
(`readingFontFamily`), headings and labels stay pixel. Screenshots: `make docs-shots` (ui-preview's
`docsShots`, `ui-preview/src/render/DocsShots.kt`: the real screens over an invented save, no ROM art; add a
`Shot` there for a new screen). `make docs-check` / CI's Docs job fails on a page that won't parse, a broken
link or image, a dash, or a `SETTING_KEYWORDS` label no page names in backticks (so a new settings row needs
its keywords *and* its docs). Search is one engine in two languages, `website/src/lib/docsearch.js` and
`DocsSearch.kt`, kept in step by `website/test/docsearch-cases.json` (`docsearch.test.js`, `DocsSearchTest`,
ui-preview's `docsSearchCheck`); words people search for go in `docs/manual/synonyms.yml` or a page's
`keywords`. A block type the Markdown gains must be drawn by `DocBlocks.astro` *and* `DocsScreen.kt`.

## NEW tags on new features

**Every new player-facing feature gets a NEW tag** (the user's rule, from the HUD on): its settings row on both screens
carries `labelBadge = NewFeatures.badge(NewFeatures.<ID>)` (green, like the Library's NEW), and its id goes into
`NewFeatures.ALL` (`companion/NewFeatures.kt`). The tag shows for 7 days from the first launch of a build that has the
feature (dated at app start, `Prefs.newFeatureSince`), then goes by itself.

## Per-game data lives in `gamedata/`

The Gen 3 `NativeConfig`s are TOML in `gamedata/gen3/` (one file per game family, comments and all), shared by
every platform: `make gamedata` (`scripts/gen_gamedata.py`) writes the app's `NativeConfigsGen.kt` from them, and
`make check` / CI fail while it's stale. **A new config or a changed address goes in the TOML, never in Kotlin**;
a new field goes in `NativeConfig` *and* `gamedata/schema/gen3_config.toml` (the generator checks they agree).
`gamedata/README.md` has the format. The language ports are there too (`gamedata/gen3/ports/`, written by
`port_retail.py`), with their dex / guide tables spelled out as sub-tables over a named Kotlin val
(`[config.port_axpd0.pokedex]`, `base = 'POKEDEX_SAPPHIRE'`; `gamedata/schema/gen3_pokedex.toml` / `gen3_guide.toml`).
Phase 2: the records the 3DS / Linux companion reads are in `gamedata/gen3/records/` (retail FireRed / LeafGreen /
Emerald's `POKEDEX_*`, `GUIDE_TABLES_*`, `TRAINER_CARD_*`, `*_DEX_FLAGS`, `*_BAG_POCKET_ORDER`, `FIELD_ITEMS_*` (+ `PARTY_ITEMS_*`, `PC_*`, `FLY_*`, `HEAL_WARP_EMERALD`): `GameDataRecordsGen.kt`,
lazy vals), and the 3DS port's `struct pd_config`s are generated too (`3ds/core/pd_configs_gen.h`, from
`gamedata/targets/3ds.toml`: per config, which parts the port reads). The other records (hacks', other languages',
layouts: still Kotlin vals the TOML names), the games' identity and the name tables come later. Until phase 1's gate passes,
`GameDataMigrationTest` checks the generated configs and records equal the old Kotlin ones (`LegacyNativeConfigs.kt`,
`LegacyRetailPorts.kt`, `LegacyRecords.kt`, test sources; `scripts/convert_native_configs.py` made the first, a
one-off), and `make -C 3ds test-configs-gen` the generated C against the old hand-written tables
(`3ds/test/configs_gen_test.c`).

## Feature tips (APP > TIPS, ON)

A TIP card (the PokeDaisy flower, NO THANKS / TRY IT, a bar that runs out = later) introduces a feature the player hasn't
found (the user's picks, 2026-10-21): ZOOM / look-around, EXPAND, QUICK MENU, HUD taps, PC, AUTO-RUN, WILD ENCOUNTERS,
REUSABLE TMS, FLY, and the DS triggers (2026-11-03: `TipId.DS_TRIGGERS`, `early`: its moment - a DS game on one screen - skips the half
hour and the gap, `EARLY_SETTLE` into a session) (`TipId`, `companion/FeatureTips.kt`; card + texts `companion/ui/FeatureTip.kt`). `TipScheduler`'s rules
(`TipSchedulerTest`): 30 min of field play first, **at least 5 min of play between two cards** (the user's rule: never one
after the other), only on a calm field (no battle, menu, text, HUD panel, QUICK MENU or companion window), a moment's tip
first (`FeatureTips.moment`: B held while walking, 3 wild battles in 3 min, a TM used up, the game's region map closed),
never for a feature the player used (`FeatureTips.used`, called where each feature runs) or turned down; an ignored one
comes back once, an hour later. Two screens: the card is on the companion (`CompanionSettings.showsTips`); one screen: top
centre of the HUD's view. `PokeDaisyActivity.tickTips` / `tipAvailable` / `tryTip`. A new tip: a `TipId`, its text, where
it's used, its availability and its TRY IT, the manual's TIPS section.

## Relationship to the private ROM-hack repo

PokeDaisy was split out of the user's **private** ROM-hack repo (local
checkout `~/Projects/tests/my-rom-hacks`, where it lived as `tools/pokedaisey`
under the misspelled name pokedaisey) on 2026-10-06, without git history. That repo still owns:

- **The Go `tools/telemetry-viewer`**. Several Kotlin files here are ports of its readers
  (`native.go`, `gen3mon.go`, `unbound.go`, ...) and say "keep in sync".
- **The pinned, built decomps** (`build/pokefirered`, `build/pokeemerald`, `build/pokeruby`,
  `build/pokehns`, ...) that the generator scripts and ui-preview read via `$DECOMPS`.

Public readers can't see that repo, so user-facing docs don't name it or link to it.

## Local setup on this machine

- `local.mk` (untracked, see `local.mk.example`): `DECOMPS` →
  `~/Projects/tests/my-rom-hacks/build`, `MON_ICONS` → that repo's
  `tools/telemetry-viewer/assets/pokemon`. `make` exports both; outside make, export them
  yourself for `python3 scripts/gen_*.py` or `gradle render`.
- `local.properties` (untracked): Android `sdk.dir`.
- `third_party/mgba`: git submodule at mGBA `0.10.5`; `third_party/rcheevos`: at `v12.5.0`
  (`make submodules` fetches them and `third_party/melonds`).
- ROMs/saves for headless captures: `scripts/host_roms.conf`. On-device paths:
  `scripts/roms.conf` (`/sdcard/Android/data/com.pokedaisy.app/files/...`).
- The ROMs the tests read (`RETAIL_ROM_DIR`, `-DromDir` / `POKEDAISY_ROM_DIR` to move it) sit in
  `~/Downloads/Game ROMs & Emulation/`: `gba/` (the default dir, every language's GBA carts and the hacks, since 2026-11-07;
  others are found relative to it: `retailRomFiles` picks the retail ones by name), `gbc/` and `nds/`. Retail
  keeps its No-Intro name; a hack is `Pokemon <Title> (v<version>) (<variant>).<ext>` (Polished Crystal's
  `Pokemon Polished Crystal (v3.2.3) (Faithful).gbc`). Nothing loose in `~/Downloads`. A test, host_roms.conf line or
  fixture README naming a ROM uses these names; renaming a file means grepping for the old one (many tests skip
  quietly when their ROM isn't found, so a stale name only shows as a test that did nothing).
- The package / applicationId was renamed twice: `com.pokedaisey.PokeDaiseyApp` →
  `com.pokedaisey.app` → `com.pokedaisy.app` (2026-10-07, fixing the "Daisey" misspelling
  everywhere, prefs file and `LaunchActivity` component included; the GitHub repo became
  `lidor30/pokedaisy`, which GitHub redirects from the old name). Each is a new app to
  Android: an old install's data (`roms/`, `saves/`, states, prefs) sits under the old
  id's `Android/data/` folder and doesn't carry over by itself.

## Headless captures

`native-capture/mgba_dump` (see its README) runs in the `pokedaisy-capture` Docker image
(`native-capture/Dockerfile`; the capture scripts build it on first use, or run
`make capture-image`). The image's `ENTRYPOINT` is `/bin/bash`, so always run
`docker run <image> -c "<full command>"`. Bare args make bash treat the command as a
script and fail with a misleading `cannot execute binary file`. Never reuse a `.ss`
savestate against a rebuilt ROM (stored code pointers go stale). Boot from a `.srm`
instead. To get a battle without walking to grass: poke a tiny script
(`setwildbattle` / `dowildbattle` / `end` bytes) into free EWRAM and `call` the game's
`ScriptContext_SetupScript` with its address - find that function through the
literal-pool words pointing at `gScriptCmdTable` (Lazarus: `0x0820B2B0`, see the
`lazarus_battle` fixture's README; newer expansion's `setwildbattle` takes
species2/level2/item2 too, and zero padding is harmless since 0x00 is `nop`).

## 3DS port (`3ds/`, branch `port-3ds`)

A homebrew 3DS build, early preview (see `3ds/README.md`): a portable C core (`3ds/core/`) re-implements
the companion's reads (addresses generated from `gamedata/`: `pd_configs_gen.h`; decoding from `Gen3Mon.kt` - keep in sync) and
draws it at 320x240 in the OPTION look; `3ds/ctr/` runs GBA games on armGBA (`third_party/armgba`, ratherDashing's 3DS
fork at `3ds-v0.0.2-alpha`: a recompiler; MPL-2.0, its GPL-2.0-only Gambatte left out, `3ds/emu/gb_none.c`) and Game Boy
games on libmgba (the same submodule). armGBA sits behind mGBA's own `struct mCore` (`3ds/emu/pd_armgba_core.c`), so the
shared C (`pk_wide.c`, `linux/*.c`) needs no change for it; the app's `pk_call.c` / `pk_host.c` / `pk_cheats.c` are built
as `*_mgba` there and `3ds/emu/pd_dispatch.c` picks. Code that reaches into mGBA's CPU or renderer checks `pd_armgba_is()`
first; armGBA's renderer can't be hooked (`pk_wide.c`'s `lineHookless`). 3ds/README.md's "Sound and speed" has the numbers. Its
tables, font, map layouts, guides (`Guide*.kt` + `GuideAreas*Gen.kt`, by a Kotlin-subset parser in
`tools/gen_guide.py`) are generated from the app's (`make -C 3ds tables`). Look at UI changes with `make -C
3ds preview` (PNGs from the RAM fixtures); `make -C 3ds azahar-test` boots the real `.3dsx` in the
Azahar emulator on a fixture test ROM (GBA homebrew that loads a fixture's RAM, `3ds/test/`). MAP,
POKéDEX and GUIDE read the ROM: `make -C 3ds decomps` builds pret's FireRed rev 1 / Emerald (retail
SHA1s, checked) into `3ds/build/decomps/`, the preview passes them in, and `fixture-roms` writes the
fixture ROM over the decomp's start (`fixture-*-rom.gba`: the tables sit past 0x08200000) - build/ only,
never committed. Only retail English FireRed / LeafGreen / Emerald so far (ROM table addresses for FireRed
rev 1 and Emerald only: rev 0 / LeafGreen get MAP but no POKéDEX, and GUIDE without wild / BOSS); runs on a New 3DS
(first tried 2026-11-04: `3ds/README.md`). Its tabs: PARTY, BAG, MAP, GUIDE, POKéDEX (BATTLE takes the last chip in a battle: INFO /
SUGGESTIONS + the FOE TEAM, ported from BattleInfoScreen / SuggestionsScreen / CompanionScreen's foe state), and
SETTINGS (screen mode, FF, one save state slot - states never carry the save data), with the TRAINER CARD
under it (`pd_card`: its renders match `TrainerCardTest`'s pinned CRCs - `make preview` prints them; keep it
so). ROM art (`pd_romart`) is one scan per ROM for every blob (map + card), done while the game loads. No retail trainer-battle
fixture exists, so `make preview` builds one from `emerald_de_battle` (party structs copied into gEnemyParty)
and keeps it in `3ds/build/fixtures/emerald_trainer` for `make azahar-battle-test`. mGBA's 3DS build
loads every ROM into one fixed buffer, so per-ROM caches key on `pd_rom_id()`, never the ROM pointer. The GPU path
(`3ds/ctr/gpu.c`) writes textures from the CPU in the tiled order: display transfers into textures drew
upside down / rotated in Azahar, so don't go back to them without checking on hardware.

## Linux port (`linux/`)

`pokedaisy-linux` (`linux/main.c`) is an SDL2 **libretro frontend** for Linux desktops and handhelds (Steam Deck,
TrimUI, PortMaster): it links no emulator and loads the core for the ROM from beside itself - `pokedaisy_gba_libretro.so`
(`.gba`, armGBA, `libretro/armgba/`) or `pokedaisy_gbc_libretro.so` (`.gb` / `.gbc`, mGBA, `libretro/`; `POCKET_CORE_DIR` to
move them) - so the companion, HUD, quick menu, FLY / PC / REPEL / BIKE, WILD ENCOUNTERS and EXPAND are the cores' own and
never drift from them (the old host carried its own copy of each and fell behind; replaced 2026-11-10). The shared C
those cores build - `linux/hud.c`, `quick_menu.c`, `fly.c`, `field_items.c`, `wild.c`, `3ds/core/` (the 3DS port's companion,
so a companion change for 3DS shows in the cores too) - stays in `linux/`. The host tells the core the screen (its
`pokedaisy_panel` option, any `WxH`; the core picks its canvas) and shows the frame at a whole scale, and owns what a frontend
owns: input (keyboard, SDL controllers with hotplug and mappings from `DATA/gamecontrollerdb.txt` / `SDL_GAMECONTROLLERCONFIG`,
the TrimUI raw fallback, the HOTKEYS page's buttons, the touch screen / mouse as RETRO_DEVICE_POINTER), sound
(resampled to keep ~60 ms queued), pacing (the screen at 58-62 Hz, else a timer), the battery save (`DATA/saves/<rom>.sav`,
written back when it changes, backed up before a game opens: newest 10 distinct), states (`DATA/states/<rom>.ss`, armGBA's
`.armgba.ss`), fast-forward (sound sped up), rewind (XOR deltas from the newest state in a byte budget), the LCD mask and the game
list, with a host menu on START+SELECT / Guide / Escape (`DATA/host.ini`; its HOTKEYS page: FF / rewind / state buttons, `hk_*`). It answers the core's GET_THROTTLE_STATE so EXPAND
stays sharp in FF. `linux-host-test` (`test_host.py`, with `-DPOCKET_TEST_CORES=<dir>`) runs the real binary headless on the
diagnostic ROMs over both cores. `package.py --core` ships the cores beside the binary (`--portmaster`: the PortMaster layout).
CI (`.github/workflows/linux.yml`): x86_64 on Ubuntu 22.04 (glibc <= 2.35: SteamOS), AArch64 host + mGBA core on the TrimUI
toolchain (GCC 8.3), the armGBA core on Ubuntu 22.04's cross GCC (8.3 can't build it), every aarch64 core failing CI if it needs a
glibc past 2.33 (the Brick; `libretro/armgba/glibc_compat.*` pins armGBA's to 2.17). The DS experiment hook (`pd_ds_run`) is kept.
**DS on Linux** (opt-in, `-DPOCKET_DSPERATE=ON`; Keegan's, 2026-10-28; `linux/dsperate/README.md`): `.nds` games on the
DSperate core (`third_party/dsperate`, unmodified), its own event loop (`frontend.cpp`), a read-only Platinum USA rev 1
companion (`companion.cpp`, Gen4Reader.kt's rules), the shared quick menu and party HUD. It builds the app's
`pk_nds_field.cpp` as is - keep that file RAM-only (melonDS reads go in `pk_nds_field_gpu.cpp`). `data_gen.h` is scraped
from the Kotlin (Gen4Reader / Gen4TablesPlatinum / Gen4TownMapPlatinum / NdsArt / Gen4Dex ...) by `gen_data.py`: after
changing those, run it (`--check` fails in `.github/workflows/linux-ds-experiment.yml`). DSperate states carry the save;
loading one keeps the battery save in memory (`load_state_keeping_save`), like the GBA host.

## Testing: quick by default, full runs only with the user's OK

The user runs several agents at once and runs the full suites about once a day, so (the user's rule, 2026-11-01):

- **Everyday check: `make test-quick`** (`./gradlew :app:testDebugUnitTest -Pquick`, about 1 min warm). It
  drops the Paparazzi renders (`*ScreenshotTest`: they assert nothing and were 86% of the full run's ~10 min)
  and `BestEffortTest` (26 whole ROMs, ~45 s), and runs the forks side by side. Fixture decodes, ROM-backed
  tests and guards still run. Run it before calling a change done.
- **While working, run only the tests for what you touched**: `make test-quick T='*MoneyTest'` (~25 s), or
  several with `./gradlew :app:testDebugUnitTest -Pquick --tests '*Foo*' --tests '*Bar*'`. A test you add or
  change: run it by name. `BestEffortTest` runs by name too if you touch detection / `BestEffort`.
- **Ask the user before any full run**, and say why: `make test`, `make check`, `make test-roms`, a plain
  `./gradlew :app:testDebugUnitTest` (no `-Pquick`), every Paparazzi class at once (`recordPaparazziDebug` over
  `'*ScreenshotTest'`), and the whole ui-preview suites (`gradle render` with no `-Ponly`, `stress`,
  `docsSearchCheck`, `keyedStateCheck`, `docsShots`, `make docs-shots`). CI runs all of them on every PR, so a
  branch still gets the full check before it merges.
- **Looking at a changed screen is not a full run**: one Paparazzi class (`--tests '*PortraitScreenshotTest'`)
  or one ui-preview render (`gradle render -Ponly=settings-home`, ~25 s warm) is fine without asking (see UI
  work below). Name only the screens you changed.

## UI work

**Look at every UI change before calling it done** — render it and read the PNG, don't
reason about Compose layout blind. Two ways, same screens:

1. **Paparazzi** (needs the Android SDK + Google Maven, i.e. the user's Mac):
   `./gradlew :app:recordPaparazziDebug --tests '*CompanionScreenshotTest'` (the class for the screen you
   changed; all of them at once is a full run: ask first),
   then Read the PNGs in `app/src/test/snapshots/images/` (gitignored — a viewing tool,
   not a regression gate). Tests live in
   `app/src/test/kotlin/.../companion/ui/CompanionScreenshotTest.kt`: one per companion
   tab (via `CompanionScreen(initialTab = …)` — Paparazzi can't tap the tab bar) plus the
   shared `OptionSelector`/`OptionConfirm`, at the Thor bottom screen's 1240x1080 (landscape); `DuoLiteCompanionScreenshotTest`
re-runs them at the Retroid Pocket Duo Lite's 1280x960 (4:3: ~120 px less height - the summary moves its types beside the
icon, STATES flattens its thumbnails, both only when the full layout doesn't fit); `RgDsCompanionScreenshotTest` at the RG DS's
640x480 - a **short companion** (`isShortCompanion`: under 316 GBA pixels tall - the Thor's companion over a DS game's
COMPANION / GAME SCREEN bar is 310, where battle INFO cut its lines, the Duo Lite's 320 isn't; the pixel font can't go below 2 screen px a GBA
pixel, so there its text was as large for the screen as on a GBA): `LocalTextStep` -1, `LocalShortCompanion` (smaller
`NavButtonSize` / `SETTINGS_CHIP_WIDTH`, one-line summary moves, the DEX's HT over WT); ui-preview: `-PcompanionW=640
-PcompanionH=480 -PcompanionDensity=1.5`;
   `WideCompanionScreenshotTest` re-runs all of them at the top screen's 1920x1080 (where SWAP SCREENS puts
   the companion; density 2.0, the closest bucket layoutlib has). Wide-screen rules so far: `PartyGrid` goes
   to 3 columns past 2.0:1 (1.6 caught the Duo Lite's 4:3 grid, ~1.56-1.75) (a measuring `Layout` - BoxWithConstraints composed the slots late and FireRed's
   icons missed the frame), and the MAP fills the tab only while that stretches it <= 1.2x
   (`MAX_MAP_STRETCH`; the Thor's bottom is ~1.16), else it keeps its shape, centred, buttons with it. Add a
   test when adding a screen. Paparazzi is pinned to **1.3.4** — the last release on
   Kotlin 1.9.24; newer ones need a Kotlin 2 bump first. Library/Settings (top screen)
   are private composables inside their Activities, so Paparazzi can't reach them; use
   the preview harness below for those.
2. **`ui-preview/`** (Maven Central only — works in cloud sessions
   where `dl.google.com` is blocked, which also blocks AGP, so the real app can't even
   compile there). A Compose **Desktop** project that compiles the real app sources
   (`companion/**`, `LibraryActivity`, `SettingsActivity`, …) against small hand-written
   Android shims and renders PNGs of every companion tab _and_ the top-screen
   Library/Settings, including tapped states (selectors, confirms, sub-pages):
   `cd ui-preview && gradle render` → `build/shots/*.png`
   (`-Ponly=settings` filters by name, `-Pgame=EMERALD` switches game; render only what you changed,
   a render of everything needs the user's OK). It's also the
   only compile check available in such sessions, so run it (with `-Ponly`) after any UI edit there. See
   its README for the shims and quirks (CMP 1.5 / Kotlin 1.9.22, `Icons.AutoMirrored`
   rewritten to `Icons.Filled`, a stale incremental cache once faked "Unresolved
   reference" errors — delete `build/kotlin` if errors look impossible).

**The app's one look is FireRed's OPTION screen** (`companion/ui/GbaMenu.kt`): white
title window, grey multi-line list window, `LABEL  VALUE` rows with grey labels / red
values and a white row as the cursor, white framed buttons, black-out overlays. Text is
`GbaText` sized by `GbaTextMetrics` (whole screen pixels per font pixel, so the pixel
font stays crisp; `rememberGbaTextMetrics(1f)` for denser secondary text). Companion
SETTINGS/STATES and the top-screen Library/Settings are all built from these pieces over
a game backdrop (`GameBackdrop` on the companion, `AppBackdrop` - the website's
`DaisyBackdrop` in the THEME's colours - on the top screen); ITEMS (`ItemsScreen.kt`) is the per-game bag screen in the
same idiom. PARTY uses the game's own slot (`GbaPartySlot`, generated `PartySlotStyle`s) where one exists; every other game gets a FireRed-like slot drawn from a `PartyPalette` (`PartyScreen.kt`, `partyPaletteFor`) — restyle a game by adding a palette there, like ITEMS' `BagPalette`. Rogue, Gaia, Lazarus, Seaglass and R.O.W.E. have palettes sampled from their party menus (headless: the save's first mon copied into slots 2-3, slot 3 at 0 HP, so normal / selected / fainted all show; `PartySlotColors.band` / per-state `text`, `PartyPalette.empty` cover flat and white-box slots); their bags have `BagPalette`s (TMT2's is `EmeraldBag`; Celia and Gaia match FireRed's). Their backdrop is FireRed's party backdrop recoloured (`backdropColors` in `theme/Theme.kt`: its 3 colours mapped to the game's), or plain stripes until a FireRed ROM has supplied the art. Multi-choice settings open an `OptionSelector` pick-list — never make a row
cycle through more than two values (exceptions, the user's call: SHADERS > FILTER, GRID and SMOOTHING, and HUD > LAYOUT, LOOK and SIZE
cycle on tap, so each look shows on the game at once; every TOUCH CONTROLS row, so each shows in its preview; and DARK MODE,
OFF / ON / AUTO). **THEME** (Settings > THEME on both screens, SCREEN group, NEW tag; `ThemeSpec` / `APP_THEMES` / `SEASONAL_THEMES` in
`theme/Theme.kt`, `Prefs.appTheme` by id; the user's redesign, 2026-11-01): a theme colours the app *and* the companion the way
DARK MODE does. `OptionColors`' window getters go through `tint()` (`Color.themed(hue, wash)`, `theme/NightColors.kt`: greys
and whites take the hue, colours with saturation over 0.5 - HP, field-move blue, types - keep theirs), values / group titles take
`ThemeSpec.value` (`valueNight` in dark), the top screen's `DaisyBackdrop` its `page` / `blobs` / `motif` (16x16 pixel art in
`ThemeMotifs.kt` floating where the logo does; a `darkPage` theme - HALLOWEEN, NEW YEAR - makes `onBackdrop` text white), and a
game's party backdrop in the companion a Multiply `gameWash` (`GameBackdrop`, `AppBackdrop`'s in-game stripes). RAINBOW keeps
the default's windows and fills each tab chip (by its place in the bar) and `OptionButton` (the next of seven, in the order
they're first drawn) with a solid colour, white text; the open tab keeps its colour inside a white ring (`ThemeSpec.rainbow`,
`OptionColors.rainbow` / `RainbowColors`; STATES' buttons stay plain, `OptionButton(rainbow = false)`). Gen 1 / Gen 2's black-on-white windows and game art stay the game's.
POKéDAISY (`DAISY_THEME_ID` 8, the default, `hue` null) is the classic look, untinted. Ids 0-7 were the old game-named themes
(FireRed ... SoulSilver, which only changed the top screen's stripes): `Prefs.appTheme` reads them as 8 (the user's call: everyone
who picked one is on the new default) - never reuse 0-7. **Limited-time themes** (`SEASONAL_THEMES`, ids 40+, a `Season` each in
`theme/Seasons.kt`: LUNAR NEW YEAR / VALENTINE'S DAY / ST. PATRICK'S DAY / EASTER / HALLOWEEN / CHRISTMAS / NEW YEAR) are
offered only from a few days before their holiday to a few after (`availableThemes`, `ThemeClock.today`, which ui-preview moves);
Lunar New Year's dates are a table to 2035 (extend it then), Easter is computed. The Library announces one once per
window (`seasonalThemeToAnnounce`, `Prefs.seasonalThemesShown` keyed `id:windowStart`; `SeasonalThemeWindow`: TRY IT / NO THANKS,
after any update offer / WHAT'S NEW / support window). TRY IT or a pick remembers the theme before (`pickTheme`,
`Prefs.themeBeforeSeasonal`); `loadTheme` (AppTheme, Library resume) puts it back once the dates are over (`themeToWear`).
`ThemesTest`; ui-preview `-Ptheme=CHERRY` (any theme by English name) and `library-theme-*`, `library-seasonal-popup*`,
`settings-theme*` shots. A new theme: a `ThemeSpec` with a new id, its name's eight translations (TrSettings), the manual's
THEME section and SETTING_KEYWORDS' "THEME" words.
New screens should reuse these rather than
`GbaWindow`/`GbaButton` (the older cream-window look). Full-tab detail views (the Pokémon
summary, battle INFO / SUGGESTIONS) use `SummaryFrame` (`MonDetailScreen.kt`): one white
window split by `Separator`s over a row of square `PlatinumButton`s with Back bottom-right.

**SETTINGS' group tabs sit outside the card** (both screens, the user's call): `RailedWindow` leaves the window to the `GroupedRows` inside, which draws it round the list alone, the `TabRail` on the backdrop beside it.
**Buttons are HeartGold's two-tone (2026-11-05, the user's call)**: every companion button is a face in two colours with two soft lines between them (`drawTwoTone`, GbaText.kt): `PlatinumButton` (a thick dark outline, a thin white ring, `fill` over `frame`) and, through `drawLayeredBox(twoTone = true)`, `OptionButton`, the tab chips, SETTINGS' group chips, the DEX's sections, MORE's tiles and the bag's PC and sort; the quick menu's tiles (`quickTileFace`) add a thick outline. The BATTLE tab takes HeartGold's layout by proportion (`ActionButtons`' `ds`: a DS pixel from the pane's width, so a tall pane leaves room rather than stretching the buttons), `BattlePlaceholder`'s four move places behind FIGHT, and its move cards (`hgMovePanel`); RUN stays whole, not cut by the screen's edge as in the game.
**DARK MODE** (SETTINGS on either screen, SCREEN group; `Prefs.darkMode` OFF default / ON / AUTO = the device's uiMode;
`DarkMode` in `theme/DarkMode.kt`, Compose state, `override` for Paparazzi / ui-preview): every `OptionColors` getter has a
dark twin - the same window build (dark outer line, lighter inner band, lilac edge) in slate, light text with its GBA
shadow *between* text and window, a brighter red value; Gen 1's black-on-white turns white-on-black. Backdrops dim
towards `NIGHT` (`backdropScrim` 0.55, Gen 1 0.9): the game's party art under a scrim, the theme stripes lerped,
`DaisyBackdrop` its own night page (plum / amber / indigo glows). Game art stays the game's (party slots, map, card,
battle buttons); bags get `BagPalette.night()` (`theme/NightColors.kt`: each colour's hue kept, lightness remapped -
yellows lean amber, a dark cream read olive), Yellow's party slots `YellowPartyPaletteNight`, Gen 1 pictures a white card
(`spritePaper`: their paper is transparent). New colours: read `OptionColors` (or add a getter with both values), never a
bare white / black fill. Look at it: `gradle render -Pdark=true -Pout=build/shots-dark`, `DarkCompanionScreenshotTest`.
**ACHIEVEMENTS from a popup**: `AchievementPopup.achievementId` (unlocks and progress); a tap opens the tab on it
(`AchievementFocus`: scrolled to, the white cursor row, followed for 3 s while the list re-sorts), BACK returns to the
tab it was opened over (`popupReturn`). The list's filter button (pixel funnel, `OptionButton(pixelIcon = …)`) opens a
pick-list: SHOW ALL / LOCKED ONLY / UNLOCKED ONLY (`AchievementFilter`).
**Corners are pixel art, never smooth arcs**: use `PixelRoundedShape(r)` / `PixelPillShape`
(`companion/ui/PixelShapes.kt`) instead of `RoundedCornerShape`/`CircleShape`, and
`drawPixelRoundRect`/`drawPixelRoundFrame` instead of `drawRoundRect` — they step in
whole GBA pixels (`gbaPixelPx()`, the same unit as `GbaTextMetrics.px`). Icons are
pixel bitmaps too (`PixelIcons` + `PixelIcon`/`PixelArt`), not vector glyphs; Pixel
Operator has no ◀/▶, so draw cursors rather than typing them (the fallback font's
glyph is taller than a line). Names follow the game's own casing: `speciesName`/
`lookupMove`/`itemName` pass through `gameCase()`, which capitalises for FireRed/Emerald
(the shared tables are title-cased; ROM-extracted hack tables already carry their casing)
— don't `.uppercase()` names in the UI. The per-game look reads the `activeGame` global,
which Compose can't observe, so `CompanionScreen` is keyed on `SnapshotView.game`.

**Tab bar + GUIDE**: at most 5 tabs sit next to the SETTINGS gear (`MAX_BAR_TABS`); the
player picks them in SETTINGS > TAB BAR (`Prefs.companionTabs`, shared by every game — only
tabs the running game has count). The rest open from **MORE** (`MoreScreen.kt`, the user's design, 2026-10-18): an
icon-only chip left of the gear, which always ends the bar (`PixelIcons.more`, `IconTabChip` like the gear; the other chips stay text - the user's
call), gone when nothing is off the bar, opening a grid of app tiles - a coloured `TabIcons` bitmap per tab (our own
drawings, colours kept in dark mode) over its name - with a line and a TAB BAR button to SETTINGS' page under them. A tab
opened from it keeps MORE lit and BACK returns to it (`barIndex`). In a
battle BATTLE takes the last chosen tab's place, and when it ends the tab that was open before
it comes back. Tabs go by id (`"ITEMS"`); only the chip / MORE tile shows the game's own word
(`companionTabLabel`: BAG, Unbound's CUBE, R.O.W.E.'s INVENTORY - read off each ROM's START menu
in `native-capture/menu-shots/*/start.png`). The GUIDE tab (`GuideScreen.kt`) shows
hand-written pages per game (`companion/data/Guide*.kt`: TIPS / WHERE IS / STUCK?, with
`Have` checks marking what the save already has) plus pages read live from ROM + save:
HERE (the current map's wild encounters), NEXT BOSS (`GameGuide.bosses`, first one whose
badge/`FLAG_DEFEATED_*` flag is unset; teams from `gTrainers`, default moves from the
learnsets) and EVOLUTIONS (`Evolutions.kt`). ROM/save addresses live in `GuideTables`
(`GuideRom.kt`). The battle INFO pane (`BattleInfoScreen.kt`) is ordered by what a player asks
mid-battle, split 50/50 YOU/FOE: battler cards on top, your moves with a plain-words verdict
(SUPER 2x / RESISTED ½x / NEUTRAL 1x / IMMUNE 0x) bottom-left, the foe's WEAK TO / RESISTS
bottom-right — sized to fit a single battle without scrolling. The FOE TEAM
(`gEnemyParty`) sits in its bottom bar like the summary's party strip; unsent foes are
Poké Balls until tapped (a tapped foe gets a NEXT / FAINTED / RESERVE / UNSEEN pill by its name). SUGGESTIONS (`SuggestionsScreen.kt`) is built from INFO's pieces
(`BattlerCard`, `BattleMoveRow`, `FoeTeamStrip`): a VS line for the foe, then the top three
picks, each split 50/50 like INFO — the Pokémon's card (1ST/2ND/3RD, OUT on the one already
in battle), then its best move with the verdict. Change the shared pieces, and both panes follow. **FOE DETAILS** (GitHub #34): the foe column scrolls on past
its matchups to `FoeFacts` (ability named from the ROM via `PokedexSource.abilityName`, held item, gBattleMons'
stats + stage pills) and its moves, verdicts flipped to what they do to *you* (`BattleMoveRow(threat = true)`). The
floating bottom-right button (`PixelIcons.expand` / `shrink`, `Prefs.foeDetails`, kept across battles) gives the
foe the page; the player shrinks to a strip (YOU + sprite level with the foe card's side label / icon, upright HP
bars). The ability / item / stats offsets live on `BattleMonLayout` (vanilla / CFRU: u8 ability +0x20, item +0x2E;
expansion, SoulGold, HnS: u16 +0x20, item +0x30; stats +0x02, stages +0x18 everywhere), checked on every `*_battle`
fixture. A reserve foe (FOE TEAM tap) shows its party stats; its ability isn't read yet. A move's verdict goes through `moveMultiplierPct`, not the chart alone: Freeze-Dry (2x on Water), Flying Press (Fighting and Flying), Thousand Arrows (1x on Flying), by name in each game's spelling. Everything is hint-first (title, tap = hint, tap = answer) and only one entry
is open at a time. The first GUIDE open per game shows an "AI-written, may be wrong" notice.
Guide facts come from the game's own data (the decomp's map scripts for FireRed/Emerald),
written in our own words — never copied from community docs; mark a guide
`verified = false` until it's been checked against the game. Emerald's guide (`GuideEmerald.kt`,
`verified = false`) and its live tables (`GUIDE_TABLES_EMERALD`, plus the FOE TEAM addresses on
`NATIVE_EMERALD_RETAIL`) came from the pinned pokeemerald built unpatched with agbcc — sha1
`f3ae0881…`, byte-identical to retail — and its ELF; struct offsets via `offsetof` compiled with
agbcc. That built ROM stands in for retail in tests/previews: `gradle render -Pgame=EMERALD
-Prom=<pokeemerald.gba>` feeds HERE / NEXT BOSS from it plus the `emerald_vanilla` save fixture.
`guideTablesMatchRom` checks each game's probe trainer (BROCK 414 / ROXANNE 265).
**Which guide a game gets is a `GuideId` on its `GuideTables`** (FIRERED / LEAFGREEN / EMERALD /
RUBY / SAPPHIRE / HEART_AND_SOUL / UNBOUND / RADICAL_RED / ODYSSEY / GAIA / AMETHYST / CELIA), not its `GameKind`: LeafGreen runs as FIRERED and Ruby/Sapphire as EMERALD, but
their guides differ; with no live tables it falls back to the kind. Ruby/Sapphire
(`GuideRubySapphire.kt`, one text built per version: MAGMA vs AQUA, GROUDON vs KYOGRE, LATIOS vs
LATIAS; `verified = false`) come from pret/pokeruby cloned into `$DECOMPS/pokeruby` (its
`ruby_rev1`/`sapphire_rev1` builds byte-match the user's ROMs; build with `make CPP=cpp` after
`/opt/agbcc/install.sh`). LeafGreen shares FireRed's text built with `leafGreen = true` (Game
Corner prizes) and its own area data (trades). Version branches in decomp sources (`.ifdef
LEAFGREEN`, `#if defined(FIRERED)`) are resolved by `gen_guide_areas.py`'s `preprocess()`.
**Hack guides** (Heart and Soul, Unbound, Radical Red, Odyssey, Gaia, Amethyst, Celia: `Guide<Hack>.kt`,
`verified = false`) are NEXT BOSS + a few TIPS; WHERE IS is generated from area data
(`generatedWhereIs`: HMs, KEY items, gift mons). Heart and Soul's area data comes from its source
(`$DECOMPS/pokehns`, `gen_guide_areas.py hns`); the closed hacks' from `scripts/gen_guide_areas_rom.py`
reading the ROM's maps + script bytecode (see docs/DEVELOPMENT.md's GUIDE section). `Boss.variants` lists
several teams (Unbound's per difficulty) and `Boss.doneIf` overrides the flag check (HnS's League
var); `Boss.variantsFor` lets the teams follow the save (Amethyst's badge-scaled gyms). Amethyst v1.4.1 shares v1.3.0's guide
text but has its own area data (`GuideId.AMETHYST_V141`) and, via `amethystV141`, its own species / item names (species
renumbered past 1233). Expansion
trainers need `TrainerMonLayout` on the `GuideTables`; `altTrainers` reads a hack's extra trainer
tables. The expansion hacks without source (Lazarus, Seaglass, TMT2, SoulGold, Glazed, Imperium, Quetzal) get NEXT
BOSS + HERE only (`Guide<Hack>.kt`, tables found in their ROMs, HERE checked by walking grass headless
until a wild battle): no area data, since the ROM reader only knows the FireRed engine's maps and script
commands. TMT2 lists 11 land slots, so its last 1% reads past the table (in the game too); `merge` drops
slots whose levels can't be real. Lazarus picks a map's wild header by its clock (a player's report, 2026-10-20: night Pokémon missing from
HERE): 37 outdoor maps have a night header right after the day one, read from 20:00 to 6:59 by gLocalTime's hour
(`GuideTables.nightWild`, `GBA_VAR_HOUR` in `SaveProgress`); HERE follows the clock and lists the other time's
extras (AT NIGHT / BY DAY), the DEX's AREA both.
**HERE covers the whole area, for every game with a guide (the user's standing rule)**: not just
the wild Pokémon but TO DO (hand-written entries tagged `areas = listOf("ROUTE 104", …)`, matched
to the current map section's name via `areaKey()`), PEOPLE (gifts, gift Pokémon/eggs, in-game
trades) and ITEMS (item balls, hidden items). PEOPLE/ITEMS are generated per region map section
(so a city's buildings count with it) by `scripts/gen_guide_areas.py` from the
pinned decomp's map.json events + scripts.inc → `GuideAreas{FireRed,LeafGreen,Emerald,RubySapphire}Gen.kt`, each with the
flag that marks it done (a ball in the UI; needs save flags). When adding a guide for another game, generate its area data too
and tag its hand-written entries with areas. HERE shows whenever a game has area data, even
without the ROM tables (then no wild list).

**ROM art - no game art is bundled** (`assets/` holds only fonts and SMOOTHING's shaders): FireRed's / Emerald's party-menu art
(`partyfr/`, `partyem/`: slot frames, Poke Ball, status icons, small font), Heart and Soul's (`partyhns/`,
smol: `HNS_PARTY_*` / `_BALL_*` / `_STATUS_*` / `_FONT_SMALL` + Emerald's slot tilemaps) and the CFRU hacks'
(`partycfru/`: the shared 112x40 slot frames `CFRU_*`, each hack's status icons `UB_` / `RR_` / `OD_` /
`AM_STATUS_GFX` on FireRed's palette; Unbound's Poke Ball and every CFRU font are FireRed's own bytes,
so their styles use `partyfr/`), party backdrops (`partybg/firered.png`, `emerald.png`, `hns.png`,
`cfru_tile.png`) and region maps (`regionmap/*.png`) are rebuilt from the
player's ROM by `RomArt` (`companion/data/RomArt.kt`): one background scan per ROM on its first
launch (~0.9 s for 32 MB on the Thor), written to `filesDir/rom-art/` under those same paths and
shared by every game (hacks without a backdrop of their
own use FireRed's, else Emerald's). Blobs are found by fingerprint, not address -
`RomArtSigsGen.kt` from `scripts/gen_rom_art_sigs.py`, hashes/CRCs only - so retail,
LeafGreen and hacks that kept the art all match. Adding art means new sigs + a bump of
`RomArt.SCAN_VERSION` (ROMs scanned before get rescanned). Load art through `GameArt.get`
(`AssetImages.kt`; rom-art, then assets) keyed on `rememberArtGeneration()`, so it shows up
once a scan lands; with no art the party tab falls back to the `PartyPalette` slot and the map to
text. `gen_party_assets.py` / `gen_cfru_party_assets.py` only generate the `PartySlotStyle`s now (no
PNGs). `RomArtTest` pins the pixels (the hacks' matched the PNGs once bundled, pixel for pixel);
ui-preview rebuilds the art from the decomp builds (or `-PartRoms` / `-Prom`), Paparazzi from the ROMs.
**TRAINER CARD (the CARD tab, off the bar by default, so under MORE)**: retail FireRed /
LeafGreen / Emerald only (`NativeConfig.trainerCard`; the hacks copy the retail
configs, so it is set on the retail ones alone). `TrainerCard.kt` reads what `SetPlayerCardData` gathers (name, ID, play time, money, caught
count regional until the National Dex, badge flags, stars, game stats XOR the encryption key, FireRed's
Sticker Man vars); `TrainerCardArt.kt` draws the game's own card from ROM blobs `RomArt` caches raw
(`rom-art/trainercard/*.bin`: card tiles, tilemaps, a palette per star count, badges, trainer pics,
FONT*NORMAL + widths) with trainer_card.c's layers and coordinates. It matched headless screenshots of
the real card for both saves, front and back, pixel for pixel (`TrainerCardTest` pins those CRCs).
Font gotcha: FireRed's FONT_NORMAL letterSpacing is 1, but RenderText only adds it to Japanese text.
**Unbound gets the same card, not a copy of its own** (the user's call: hacks borrow the look, matched
in colour): `CardStyle.UNBOUND` = FireRed's card from its ROM (Unbound kept those blobs) through
`unboundPurple` (hue 266, sampled from Unbound's card headless), with Unbound's protagonists, which
replace Red / Leaf at pic ids 135/136 in its own front-pic tables (`gen_rom_art_sigs.py` `UB*\*`). Its
ROM changed FireRed's badge strip and dropped the 0-star palette, so no badges are drawn and every star
count starts from the 1-star palette. Save data: FireRed's layout + CFRU dex flags (`TRAINER*CARD_UNBOUND`on`NATIVE_UNBOUND_WITH_DEX`only). **SoulGold, Lazarus,
Recharged Yellow** (2026-10-15) draw their own cards (`CardStyle.SOULGOLD` / `.LAZARUS` / `.RECHARGED_YELLOW(_V1_9)`): found from
headless VRAM dumps of the open card + each card's code, every save field poked in and reopened. Their quirks are
`TrainerCardSave` fields: caught bits alone (`caughtOnly`), Lazarus's drawn stars by its own rule (`drawnStarFlags`) and
its regional loop's one-past-the-end bit (`regionalStrayBit`), SoulGold's frontier-record / achievement stars and 7-digit
money, Recharged Yellow's plain stats, flag-only National Dex and six icons on the back (`backIcons`, read from the ROM
on the bus); their wording / text colours / glyph clipping are `TrainerCardArt.Words` / `Layout`. Other hacks: no card yet.
The tab shows the card cropped to itself (no BG2 backdrop) at the largest whole scale (5x on the
Thor); a tap flips it (squash, like the game), and the time colon blinks via an infinite transition
(a`delay`loop never lets Compose tests go idle, which hung ui-preview once).
**BOXES tab** (`BoxesScreen.kt`, under MORE, NEW tag; retail FireRed / LeafGreen / Emerald / Ruby / Sapphire in every language,
the in-place hacks of retail (Kaizo, Glazed, Orange Islands, Ultra Violet, Rocket Edition, Ash Gray, Ghost Grey), the CFRU hacks
(Unbound, Radical Red, Amethyst, Sword and Shield, GS Chronicles: CFRU's 25 boxes, each with its own gBattleMoves / gExperienceTables -
CFRU's EXP rows are 256 long and medium slow is a point under vanilla's; Odyssey / Gaia: vanilla's 14 boxes in plaintext,
`PLAIN_PC_STORAGE`), SoulGold; each checked by a `<hack>_boxes` fixture of the game's own SendMonToPC): the
PC's boxes read live (`readPcBoxes`: gPokemonStoragePtr, set by the Poller on retail English FireRed / Emerald as
`pokemonStoragePtr` = saveBlock2Ptr + 4 - their configs are the hacks' bases - and in the TOML for the rest, the language ports'
mapped by port_retail.py and checked by verify_ports.py's BOXES column; Ruby / Sapphire's gPokemonStorage is static,
`STATIC_PC_STORAGE`; the Poller drops it on a non-retail hash under a port's code); a box record's level from the ROM's growth rate and its stats
from the ROM's base stats, `decodeBoxMon` / `gen3Stats` / `PokedexSource.growthRate`). How a game keeps them is its `pcStorage`
(`PcStorageLayout`): vanilla's encrypted 80-byte records; SoulGold's 15 boxes of 76-byte plaintext ones behind saveBlock2Ptr + 8;
CFRU's 25 boxes of 58-byte compressed ones (no PP: worked out from the ROM's gBattleMoves) at fixed addresses, boxes 20-25 elsewhere in
EWRAM (`UNBOUND_PC_STORAGE`, from CFRU's public src/pokemon_storage_system.c, checked by calling the game's SendMonToPC headless:
`unbound_boxes`). Other CFRU hacks likely share it but weren't checked. **Drawn in our own pieces, not the ROM's
PC art** (the user's call: one look every game can have, close to the game's, not 1:1) but **in its colours** (the
user's next call: sampled from FireRed's PC screen - `PcColors`: the PKMN DATA panel, picture well, peach backdrop, arrows;
`BoxWallpapers`: each Gen 3 wallpaper's frame / edge bevels, header-strip sky bands and fill, under patterns of our own;
Emerald's own set for the games that run as EMERALD - several frames and every header differ - while the screen around
them is the same in both).
Panel and box fill the tab (its peach backdrop is the screen's, CompanionScreen's `PcBackdrop`, like the bag's); icons at
whole scales, allowed past their cells like the game's. A new game needs only its storage read. **The PC's moves** (`BoxSwap.kt`,
FireRed rev 1, LeafGreen rev 0 / 1 and Emerald; the German / French / Italian / Spanish ones and English FireRed rev 0 from
`BOX_SWAP_PORTS`, mapped by port_field_items.py under the bag tables' keys - Japanese has none - and so Ultra Violet / Rocket Edition /
Ash Gray / Ghost Grey, which run rev 0's bag table; each checked by `scripts/verify_box_swap.py`, the game's functions called headless,
not yet an in-game save and reboot: `boxSwapFor`, only with vanilla boxes at the table's own storage pointer - the CFRU hacks
share FireRed rev 0's bag tables, and BoxMonToMon on a compressed record would wreck it), each in the running game the PC's own way, on
the bag's field gate: a box Pokémon's summary has WITHDRAW (`withdrawFromBox`: BoxMonToMon into gPlayerParty[count], the slot zeroed,
count + 1), which with six in the party opens SWAP's pick instead (`swapWithBox`: BoxMonToMon out, the party one's 80 bytes in +
BoxMonRestorePP); a party one's has DEPOSIT (`depositToBox`, a box pick: its 80 bytes into the box's first free slot +
BoxMonRestorePP, the party moved up and zeroed behind - CompactPartySlots - count - 1). Mail, the last fighter and a full box refused;
each checked headless on FireRed and Emerald with an in-game save and a reboot. The summary's Pokémon (party or box, six at a time) are
a column on the right with Back under it (`PartyColumn`, like battle INFO's foe team), the PC's button with STATS / LEARN. Then the move
plays over the tab (`BoxSwapAnimation`: white, shrunk in whole steps to a spark, the sparks cross on two arcs - one for DEPOSIT /
WITHDRAW - a burst) and its message stays in a window under the wells until OK (the user's call: no popup after it); a tap skips to the
message, LIGHT COMPANION starts there. A refusal is a popup (`BoxSwapNotice`). Its functions were found in retail by masked bytes from
the decomp build (the `$DECOMPS` FireRed map is shifted, 0x120 there): a new game needs its own.
**SoulGold v1.1.4** (`NATIVE_SOULGOLD`; v1.2 = `NATIVE_SOULGOLD_V1_2`, a rebuild at shifted addresses, same save
format, v1.1.4's generated tables but TM75 via `soulGoldV12`; a second v1.2 build = `NATIVE_SOULGOLD_V1_2B`, same RAM, ROM data 0x98-0xA4 earlier; a third = `NATIVE_SOULGOLD_V1_2C`, same RAM, ROM data 0x18C-0x198 later, wild / map tables 0x2DF30; v1.2.1 = `NATIVE_SOULGOLD_V1_2_1` (two builds, 2 bytes of item data apart; "Soulgold" in the header), v1.2's tables, IWRAM and battle globals, but EWRAM moved (the party globals -0x1BC / -0x1C0, gMapHeader / gObjectEvents +8, gBagPockets +0xC) and ROM data 0x7A38-0xBEE8; gPartyMenu comes from `NativeConfig.partyMenu`, newer expansion, no source) breaks several vanilla
assumptions, each a `NativeConfig` field now: its struct Pokemon is 96 bytes and plaintext
(`SOULGOLD_PARTY_MON`: 12-char nicknames push the egg flags to +0x15, status at +0x4C, level/HP
from +0x50), BattlePokemon is 0x98 bytes (`SOULGOLD_BATTLE_MON`), mapsec ids are u16 with mapType a
byte later (`mapSecWide`), and the bag has 8 pockets (Items holds 150). gPlayerPartyCount is 0x02038DD5, right before gEnemyParty (a wrong
guess once showed only the first mon - test party code on a save with a full party). Mon icons have a palette
per species (`gSpeciesInfo`+0x94, `IconTables.monIconPalettes`), not vanilla's 6 shared ones. Eggs in the
expansion hacks keep their species, so they're flagged (`Mon.isEgg`) and drawn with `NativeConfig.eggSpecies`'
icon (SoulGold 1578, Lazarus 1561: gSpeciesInfo's unnamed last entry; Heart and Soul / Seaglass: none found). Its art is smol-compressed:
`RomBlob(smol = true)` blobs go through `Smol.decompressAny` (tile modes 1-6 and the tilemap mode 8),
`scripts/smol.py` is the generator's twin. The bag's night sky (`bagbg/soulgold.png`) is a
`BagPalette.backdropArt`, drawn still (the game scrolls it). Its Johto map is Emerald's region_map.c
with smol art (`SG_REGION_*` -> `regionmap/soulgold.png`) and a u16 cursor grid
(`RegionLayout(cellBytes = 2)`, two layers), placed a row higher than Emerald's: offset (1, 1). Its
POKéDEX (`POKEDEX_SOULGOLD`): no Emerald-style nationalMagic (the game never sets one), so the tab
opens on Johto like the game; FLAG_SYS_POKEDEX_GET is 0x98D (to open its dex headlessly).
**Game Boy / Color (Pokémon Yellow first)**: mGBA's GB core is on (`M_CORE_GB`; `pk_gb_config` turns
SGB borders off so Red/Blue/Yellow give 160x144, not 256x224); `pkPlatform()` / `RomIdentity.isGameBoy`
(the header logo at 0x104) tell the platforms apart, and every GBA-only path stays off for GB: `pk_call` /
park / `pkRomCode` return early, the engine's watchers (`EmulatorEngine.gba`), FF music
renders, `RomArt` / `RomRegionMap`; the stage takes the core's aspect (10:9) and the touch pad drops L/R.
RetroAchievements identifies GB carts too (console 4, or 6 for a Color-only cart; RA's GB memory map).
A GB cart is bank-switched, so the Poller hashes it through `pkRomRead` (the cart buffer), not the bus.
Yellow's addresses are pret/pokeyellow's symbols - its build is byte-identical to retail, so build it
(rgbds 1.0.3 in Docker) for `pokeyellow.sym` rather than guessing (Red/Blue's WRAM is Yellow's + 1).
`Gen1Reader.kt` reads WRAM (big-endian party_struct / battle_struct, internal species -> Dex number via
PokedexOrder); tables from `scripts/gen_gen1_tables.py` in the game's own ALL-CAPS. **The look is the
game's** (the user's rule: match it even with fewer colours): `Gen1Art` rebuilds the font (8x8 1bpp
FontGraphics -> a TrueType file, `BitmapTtf`, Pixel Operator's 100-unit pixel; `LocalGameFont` from
CompanionScreen makes every `GbaText` use it), the party icons (MonPartySpritePointers into a virtual
VRAM, mirrored halves, the GBC party menu's white / yellow / black), the town map (CompressedMap RLE,
CGB PAL_TOWNMAP) and the player's town-map sprite into rom-art; `OptionColors` / PlatinumButton /
`YellowPartyPalette` / `YellowBag` / `MapLabelStyle.GEN1` switch to Gen 1's black-on-white windows (the
text box's double line) while YELLOW runs. Headless: `mgba_dump`'s `dump` writes `wram.bin` (C000-DFFF)
+ `hram.bin` for a GB core, which `FixtureMemoryReader` loads; a wild battle = poke wCurEnemyLevel
(0xD126) and wCurOpponent (0xD058, an internal species index) on the field.
**Yellow, round two**: `pk_gb_config` reloads options one by one (`reloadConfigOption`): a whole
`mCoreLoadForeignConfig` left the GB core's volume at 0 (Yellow was silent). It sets the volume to 0x80 (`PK_GB_VOLUME`,
2026-10-28, r/PokeDaisy's "crushed" Gen 1 / 2 sound): at mGBA's 0x100 the GB mixer overflows its int16 in loud passages
(Crystal / Yellow clipped headless; at 0x80 the peak is ~28,700 and the music about FireRed's loudness). That wasn't the
crackle players kept hearing (GitHub #269 / #327, 2026-10-29): mGBA 0.10.5's GB core resamples through a blip_buf hookup
whose 48 kHz output has sudden level drops of up to 25,000 (~4 a second in Crystal, 2.4 in Yellow; its own 131 kHz samples,
newer mGBA, Gambatte and SameBoy: 0 - 0.5). So both cores take the raw samples from the stream's `postAudioFrame` and
resample them themselves (`pk_gb_audio.c`, also in mgba_dump: `gbaudio 0` records mGBA's own path); never read a GB core's
`getAudioChannel`. STEADY clips recorded before that kept the crackle in FF: a Game Boy game's are recorded again when its sound
changes (`FfMusicCache`'s `GB_SOUND` stamp, bump it). Count pops by peaks over 4x the RMS on level-matched 90 s recordings, and listen. Debug builds record what
the AudioTrack is given (`EmulatorEngine.audioTapDir`: an `audio_tap` file in `files/debug/` -> `audio-tap.pcm` / `.txt`, 30 s).
Never tile a 1px backdrop
(`GameBackdrop` draws tiles one by one: Yellow's white was ~53,000 draws a frame, 77 ms on the
RenderThread) - it's stretched once now. A game font is set larger (`LocalGameTextScale`, Gen 1 9/7) except
where layouts are sized to the line (`rememberGbaTextMetrics(gameScaled = false)`: battle INFO / SUGGESTIONS), and drawn
condensed (`LocalGameFontWidth`, Gen 1 0.75: `GbaText`'s scaleX, rounded per size so a font pixel stays whole screen
pixels wide - 4 tall -> 3 wide). Its black-on-white settings get group titles as a black band (`OptionColors.groupTitleFill`).
Tab, button and battle-button labels are bold there (`GbaText(bold = true)` / `gameBoldLabels()`: a second copy
half a font pixel right - its strokes are 1px), the party slot's name and level a slot pixel per font pixel; its moves
are the other games' 2x2 grid (the game lists them in a column; `selectMove` steers either way).
STATES: a slot's thumbnail write time comes with the list (`StateSlots.Slot.thumbModifiedMillis`) and a SAVE / LOAD
re-lists a few times over ~2 s - an unchanged Slot skips its card's redraw, so a file time read in the card went stale.
SETTINGS keeps its page / row / scroll across tabs (`SettingsUiState`, held by CompanionScreen); with STATUS BAR >
COMPANION its HOME title window is dropped (the bar already shows the game and battery). HOME's title is SETTINGS.
The Gen 1 look only applies while a game is up (`OptionColors.inGame`, set by the activities' onResume): `activeGame`
stays YELLOW after closing it, and the Library once kept Yellow's windows. Gen 1's telemetry frame counter is the play
time (STATES re-lists on it; a constant 0 left new thumbnails unseen until the tab was reopened).
**Yellow in German, French, Italian and Spanish (2026-10-14)**: the European carts (`GEN1_YELLOW_DE` .. `_ES`, by hash in
`gen1ConfigFor`) are English's code with their own text: RAM is English's + 5 bytes from wBattleMon (0xD018) up, identical in
all four; menu / tile map / audio RAM didn't move. `scripts/port_gen1.py` maps pokeyellow.sym's symbols into a cart by the
English code that uses them (build pokeyellow in Docker for the .sym: rgbds 1.0.3); every table is then checked by contents
(`gen_gen1_tables.py yellow_<l>` asserts the shared ones are English's bytes). Their names come as a `GameText` keyed by the
CGB manufacturer code (APSD / APSF / APSI / APSS: `Gen1Config.gameCode` / `language` feed `romGameCode` / `romLanguage`, so AUTO
language follows the cart). Two charsets (`Gen1Charset`: German / French, Italian / Spanish) replace English's 'd 'l 's 't 'v 'r 'm
tiles with accented letters; each language gets its own font file (`Gen1Art.yellowFont`), the icons / town map / player are
English's pixels (`YellowEuropeDecodeTest`). Dex pages are metric ("0.4m", `Gen1DexTables.metric`: dm 1 byte + hg 2 before the
text pointer). The battle menu's cursor columns (`battleMenuLeftX` / `RightX`) and words (`gen1BattleMenuWords`) are per
language. The English save loads in all four, but French / Italian / Spanish keep the map header's text / script pointers
elsewhere, so their boot scripts (`native-capture/boot/yellow_<l>.txt`) poke the cart's own 10-byte header for the save's map
before CONTINUE goes on (else the map hangs; Spanish happened to survive). No item descriptions there (ours are English).
**STEADY FF music on Gen 1** (`Gen1MusicRenderer`, `Gen1Music`, `Gen1LoopWatch`): a song is (wAudioROMBank, the first
music channel's wChannelSoundIDs) - ids repeat across banks. The render core boots ~10 s, parks its main loop on a `jr @`
(`pkRenderGbPark`; Yellow 0x1757, the operand of a `jp z` - never a real jump target) and calls PlayMusic (a = id, c = bank,
`pkRenderGbCall`, interrupts held off); the VBlank handler runs the sound engine by itself. The loop is where the music
channels' state (command pointers, return addresses, note delay / loop counters) first repeats an earlier frame's; a state
that stops changing = a fanfare that ended. Checked headless with mgba_dump's `gbpark` / `gbcall` / `gbloop` (wild battle
31.3 s loop after 13 s, Route 1 22.9 s) and on the Thor. Battle controls: `readGen1BattleInput` (which menu waits = the filled ▶ tile at the
cursor in `wTileMap`; FIGHT/PkMn over ITEM/RUN, moves one list) feeds `BattleInputController.gen1`, which steers
from the read cursor; `Gen1ActionButtons` / `Gen1MoveList` draw them. Pokédex: `Gen1Dex` reads the cart bytes
(`Gen1Dex.rom`), `Gen1Pic` is home/uncompress.asm ported (verified on Pikachu), `POKEDEX_YELLOW`.
**Pokémon Crystal (Game Boy Color, Gen 2; 2026-10-14)**: rev 0, rev 1 and Australia (`CRYSTAL_SHA1S`, `GEN2_CRYSTAL`,
`GameKind.CRYSTAL`) - pret/pokecrystal builds all three byte for byte (rgbds 1.0.4 in Docker, `$DECOMPS/pokecrystal`) and
they share every RAM address and table the companion reads. Gen 2 code lives in `Gen2*.kt` (`Gen2Reader`, `Gen2Art`,
`Gen2Dex`), tables from `scripts/gen_gen2_tables.py crystal` (checks the three ROMs agree). **WRAM bank 1 is read by
bank**: the game switches D000 to other banks mid-frame (a bus peek of wBattleMode read 0 in a battle), so D000-DFFF
addresses are `wramx(1, addr)` = 0x1Dxxx, which `pkReadBytes` (`pk_gb_read_banked`) and `FixtureMemoryReader` (mgba_dump's
`wramx.bin`, all 8 banks) resolve to that bank; a C000-CFFF address is a plain bus read. The look is Yellow's (`isGameBoyGame`:
OptionColors' black-on-white windows, white backdrop, condensed game font) with Crystal's own font, red party icons, HP colours,
PACK and its blue checks, the POKéGEAR's Johto / Kanto maps (landmark coords = the icon's centre in screen pixels). The user had
no Crystal save: one was played headless in rev 1 with mgba_dump's `savestate` / `loadstate` (Poké Balls and a BERRY poked in -
the `crystal` fixture's README) and written to RetroArch's saves under each release's name; it loads in all three. Gen 2 can
miss a one-frame press outside its menus (hold 3 frames headless); the battle menus take the controls' one-frame presses.
Battle controls ride the Gen 1 path (`battleGen1Menus`): `readGen2BattleInput` reads `_2DMenu`'s cursor and checks the ▶.
STEADY FF music runs on Gen1MusicRenderer with Crystal's tables (`GEN2_MUSIC_CRYSTAL*`: the song is the playing channel's
MusicID, never the stale `wMusicID`; each recorded after PlayMusic(0), since `_PlayMusic` leaves unused channels playing the
last song). Crystal Clear, Gold / Silver and Polished Crystal have tables too (GitHub #390; `crystalClearMusic`, `goldSilverMusic`,
`polishedCrystalMusic`: Polished's channel struct is its own, `musicIdBack` 7); Prism's sound wrappers match none of them, so it stays SPED-UP. No GUIDE, CARD or trainer-battle fixture yet. The POKéMON switch: see the switch
paragraph's Game Boy part (`GbPartySwitch`).
**PC anywhere, FLY and WILD ENCOUNTERS on the Game Boy (2026-10-18, Red, Blue, Yellow in five languages, Crystal)**:
`GbField.kt`, each the game's own way in (no GBA calls exist there; Gen 1 = `GbField.Gen1`, a config per cart): Crystal
queues a map script (RunMemScript's wMapReentryScript*: PCScript, or FlyFunction.FlyScript after wDefaultSpawnpoint) and
holds wWildEncounterCooldown; Gen 1's PC is the PC tile's routine called through Bankswitch from the overworld loop
(`pk_gb_field.c`'s `pk_gb_call_at`, `MgbaCore.pkGbCallAt`; mgba_dump's `gbcallat`), its FLY the town map's warp flags,
its encounters the map's rates held at 0 (put back from ROM). Code checked in the cart first (`GbFieldTest`: each Gen 1
cart matches only its own config). Carts without an EXPAND scene go by RAM alone. docs/DEVELOPMENT.md's Game Boy field
section.
**Crystal Clear (2026-10-14)**: v2.5.11 / v2.6.0, each a Standard Colors and a Color Filter build (palettes apart, the same
bytes): `GameKind.CRYSTAL_CLEAR` on the Gen 2 reader (`isCrystalFamily` for every Crystal look / behaviour), by SHA-1
(`crystalClearConfigFor`). pokecrystal rebuilt without symbols: RAM mapped with `scripts/port_gen1.py` (now any pret .sym:
`--loose`, `--window`, `--data`) - Crystal's but wMapGroup / wMapNumber at DA3A (`Gen2Config.coords`: wYCoord stayed) and the
caught flags at DA0E. Its species / moves / types / chart are Crystal's (`gen_gen2_tables.py crystal_clear ... --base <Crystal
ROMs>` checks it), items / landmarks / maps its own (maps from the ROM, no decomp); its text is packed with a dictionary
(00:11FF, `Gen2DexTables.textDict`). The versions differ in a few ROM tables only (icon banks, move descriptions:
`*_V2_5_11`). Art into its own paths, rebuilt when another of its builds starts (`Gen2Art`'s `<name>-built` marker):
per-species LZ icons in their own colours, the RED POKéGEAR's maps, no player head (the player designs their look).
Crystal's save breaks it (the moved map ids): a new game was played headless in v2.6.0 and saved beside the others under
each build's name (`crystal_clear` README); hold presses 6 frames in its menus.
**Prism (2026-10-14)**: v0.95 build 254 (Hotfix 5), `GameKind.PRISM` / `GEN2_PRISM` (also `isCrystalFamily`; checked first in
ActiveTables). Its species are its own 254 ids, not Dex numbers (EGG still 253): every table its own, `gen_gen2_tables.py
prism` (`read_prism`: length-prefixed move / item names, 24-byte base data, 2-bit type chart cells, Fairy / Gas / Sound / Tri
/ Prism types, a move's category in its type byte's top bits). Crystal's RAM but the bag: a bit per TM / HM at D859 (TMs
aren't items: the companion lists them as ids 0x100 + n), pockets D866 / D8B8 / D8EC (40 / 50 / 25, `Gen2Config.itemsMax` &
co.). DEX (`POKEDEX_PRISM` / `GEN2_DEX_PRISM`): one list of 253 in its own order (`gen2DexOrderPrism`, 10:4915: dex numbers
are places there, `Gen2DexTables.speciesOf` / `dexOf`; flags by species id), pages metric (dm / hg) and read through its own
text engine (`Gen2PackedText`: plain strings, 3-byte references, 03 + a Huffman stream at 35:4E14, 01 + code that picks the
UNITS option's metric / imperial text - read as metric), item / TM-move descriptions the same way; checked entry by entry
against the game's dex screens headless (`PrismDexTest` pins CRCs, never the prose). A line-end hyphen joins the word back
unless the half after it is a compound's (`Gen2Dex.flow`, `COMPOUND_ENDS`). Art: per-species LZ icons in 8 OBJ palettes, six town maps (`Gen2ArtTables.townMapsLz`).
No save of its own fits it: a new game played headless, the story's D84C bit 5 (no START menu, no encounters) cleared to play
on; the game's own TryAddMonToParty / ReceiveItem / startbattle ran through mgba_dump's new `gbfar` / `gbinject` (README).
The user's "Crystal (USA) (patched)" file is "LOL PRISM": Crystal rev 0 whose first 32 KB only print "LOL" - not supported.
**Red / Blue (2026-10-15)**: `GameKind.RED` / `.BLUE`, `GEN1_RED` / `GEN1_BLUE` - Yellow's reader and tables (`isGen1`; `gen_gen1_tables.py
red` / `blue` checks every table equals Yellow's and writes nothing), RAM Yellow's + 1 from wOnSGB (CF1B) up. Blue is checked on
pret/pokered's `pokeblue.gbc` (= the cart; the user has none). They run as a **Super Game Boy** in mGBA (DMG cart with SGB flag):
their white / black are 0xFFEFFF / 0x181010 (`OptionColors.gbPaper` / `gbInk`, the backdrop, `RedBlueBag`, `RedBluePartyPalette`)
and the art takes the SGB palettes (sampled headless). MEW sits apart in bank 1 (`Gen1DexTables.mewBaseStats`). Their bank 0 has no
`jr @`: STEADY music parks on WRAM 0xCD39, which `pkRenderGbPark` (and mgba_dump's `gbpark`) writes the `jr @` into first. The
user's Red save holds Yellow's team; in Blue its map's tileset pointers are Red's (Blue's are 0x10 lower, garbage graphics until
`boot/blue.txt` pokes them). docs/DEVELOPMENT.md.
**PureRed / PureBlue v2.7.6 (2026-10-22)**: `GameKind.PURE_RED` / `.PURE_BLUE` (`isPureRgb`, in `isGen1`; checked before
`yellow` in ActiveTables), Vortyne's pureRGB at tag v2.7.6 (`$DECOMPS/purergb`, rgbds 1.0.3: `make red blue` = both releases byte for
byte, so its .sym gives everything). A Color cart that keeps its state in WRAM bank 1 (bank 2 = palette buffers): `GEN1_PURE_RED`'s
D000+ addresses are `wramx(1, ...)`. Its own tables (`gen_gen1_tables.py purered`, one `*PureRgb.kt` set for both: types NONE /
CRYSTAL / TRI / FLOATING / MAGMA and a second GROUND, name pointer tables, 35-byte base data, forms that carry a base Dex number,
growth rates, one-byte text words `Gen1Charset.PURE`), its own icon sheet and dex records (`Gen1Art.iconSheet`,
`Gen1DexTables.dexEntryData`), BATTLE_PARTY_MENU 1, a 30-item bag, GbField with its own banks. Its look is its default COLORS = OG:
the Color's two-tone palettes (sampled headless), the DEX in its SGB palettes. No save of its own: the retail Red save converts
through the game's updater on CONTINUE (`native-capture/boot/purergb.txt`: "Original Game", START, wCurMapTileset bit 7 cleared,
NO to its Pallet warp, which froze headless). No EXPAND. PureGreen (a .bps the user has) isn't added. docs/DEVELOPMENT.md.
**Yellow Legacy v1.0.10 / Crystal Legacy v1.3.1 (2026-11-04)**: `GameKind.YELLOW_LEGACY` / `.CRYSTAL_LEGACY` (`isGen1` / `isCrystalFamily`), cRz-Shadows' pret forks; their
builds (`$DECOMPS/pokeyellowlegacy` rgbds 0.6.1, `pokecrystallegacy` rgbds 0.5.2) are the patched carts byte for byte, so their .sym gives every address. Only what changed
is generated (`shares`; moves, chart, TMs, items). Retail saves don't read right in either: `scripts/convert_save_legacy.py`, and a converted Yellow save must be walked in,
not just looked at (a stale collision pointer turns the player without moving him). Yellow Legacy's status moves are the old BIRD slot, printed NORMAL (`typeless`); SLAM really
is DRAGON. Crystal Legacy scales Chuck / Jasmine / Pryce by badges (`clScaledBoss`) and Red's done flag is EVENT_DEFEATED_RED. docs/DEVELOPMENT.md.
**Gold / Silver (2026-10-15)**: `GameKind.GOLD` / `.SILVER` (`isGoldSilver`, both in `isGen2` with the Crystal family),
`GEN2_GOLD` / `GEN2_SILVER` - pret/pokegold's RAM (wBattleMon / wCurOTMon / wCurBattleMon in bank 0), Crystal's structs, menus and
look (the party menu and PACK are Crystal's colours, sampled headless), one table set (`gen_gen2_tables.py gold_silver`). Dex pages
per version; the page bank is computed and FixPicBank is a pair table (`Gen2DexTables.dexEntryFirstBank` / `picBankFixes`). The user
had no save: one was played headless in Gold (the same file under Silver's name). mgba_dump's `gbfar` / `gbinject` assume Crystal's
hROMBank / FarCall - see `gold_trainer`'s README; `loadstate` broke Gold's PACK, so run captures in one go.
**Polished Crystal (2026-10-16)**: 3.2.0 - 3.2.3, all 16 official release builds (normal / faithful, each with its debug build;
`POLISHED_CRYSTAL_SHA1S`, `GameKind.POLISHED_CRYSTAL`, `isCrystalFamily`, `GEN2_POLISHED_CRYSTAL(_FAITHFUL)`). Open source with a
.sym per release: `scripts/gen_polished_crystal_tables.py <releases dir>` (+ `$DECOMPS/polishedcrystal/src` at 3.2.3) checks every build's
tables equal 3.2.3's (the faithful builds: 33 species' types, 12 moves, 4 item names, an overlay) and writes each build's ROM addresses
(`polishedCrystalAddrs`); RAM is the same in all 16. Its own structs (`decodePolishedPartyMon`: 48-byte party_struct, a 9-bit species =
the byte + the form byte's bit 5, gender a bit, the egg bit 6; variant forms are companion ids past NUM_SPECIES 0x123; egg 255), bag
(Items / Medicine / Balls / Berries pairs, key items a 0-terminated id list at 0x200 + id, a bit per TM), charmap ('@' 0x53, mixed case,
▶ 0xF0: `Gen2Config.cursorTile`) and LZ variant (`Gen2Lz.decompress(polished = true)`), metric dex pages in National order
(`polishedCrystalPokedex(sha1)`, set by the Poller per build), growth rates numbered 0 / 1 / 2 / 3 = medium fast / medium slow / fast /
slow. Battle menus at Crystal's places; its SWITCH / SUMMARY / CANCEL a column left (11, 12); its party menus read keys every other
frame, like Prism's. The user had no save (a third-party Discord one exists, not used): a new game was played headless in 3.2.3 and saved
under every build's name (`polished_crystal` README; hROMBank is FF87, so mgba_dump's `gbfar` / `gbinject` don't work there). Its font
loads at tile 'A' (0x80) like Crystal's. Item / key item / TM descriptions, dex abilities and the map's player (by wPlayerGender:
CHRIS / KRIS / CRYS) come from each build's ROM; its `text` is often Huffman-compressed (<CTXT> 0x5D, `polishedString`).
It has the first Game Boy GUIDE (`GameGuide.world`: a `GuideWorld` gives HERE's wild Pokémon and NEXT BOSS's teams from generated
data, `gen_polished_crystal_guide.py`, every build checked; `readGen2Telemetry` fills `SaveProgress` from wEventFlags, the badges /
trade flags as banks 0x10000+ / 0x20000+ and the clock as var 0x10000). Its controls were run through the real controller headless:
the Game Boy battle menus drop presses as they open and after a cursor move, so every Game Boy pick (FIGHT / BAG / RUN / a move) now
steers on the menu read each frame (`driveGbPickLocked`, `GbPartySwitch.battleMenu`) - docs/DEVELOPMENT.md's Polished section.
**GUIDE on Yellow, Red, Blue and Crystal (2026-10-27, a player's request)**: HERE + NEXT BOSS + the generated WHERE IS over
`GuideWorld`, like Polished Crystal. Gen 1: `scripts/gen_gen1_guide.py` reads pokeyellow / pokered's builds through their .sym
-> `GuideGen1Gen.kt` (`G1Y_` / `G1R_` / `G1B_`), `GuideGen1.kt` (`Gen1World`, `readGen1Progress`: wEventFlags + banks for
toggleable objects / hidden items / trades / badges, wRivalStarter as the champion's team var; the European Yellows = Yellow's RAM
+5 from wBattleMon up, `gen1GuideRam`); areas keyed by wCurMap and regrouped by town map name (`gen1Area`). No event flag survives
Gen 1's Hall of Fame: a League boss is also done once the CERULEAN CAVE guard is hidden. Crystal: `scripts/gen_crystal_guide.py`
(rev 0 / rev 1 / AU checked equal) -> `GuideCrystalGen.kt` in Polished Crystal's line format (`Gen2WildTables` /
`Gen2BossTables` read both), `GuideCrystal.kt`; surfing adds 0-4 levels, RED is done when the League is won and his object
flag is set again. Swarms and roaming beasts aren't listed.
**Nintendo DS (2026-10-19; on for players from 1.3.0, `TelemetrySampler.NDS_SUPPORT = true` like the Game Boy's switch,
the cart list `NDS_GAMES`, which the website's ROM check reads by header through compat.json's `ds`)**: Platinum (USA rev 1)
and HeartGold / SoulSilver (USA) on melonDS (`third_party/melonds` at 1.1; our changes are one patch, `third_party/patches/melonds-pokedaisy.patch`,
applied at configure time - edit the submodule, then `git -C third_party/melonds diff >` it; `ignore = dirty` hides it from
`git status`, `scripts/check_melonds_patch.py` in `make check` fails on anything else in there). App code in
`app/src/main/cpp/nds/` (core, field read, function caller, EXPAND from the game's own polygons, STEADY = a second melonDS
from the game's state with its ARM9 stopped, its ARM7 FIFO drained every frame), `Gen4*.kt` / `NdsArt.kt` (reads from the
SaveData in RAM; art, DEX pages from the player's cart, never bundled), the DS bottom screen on the bottom display
(`NdsBottomScreen.kt`; SWAP SCREENS trades the two: the stage to the second display, `NdsBottomHost` as the activity's content; on one screen - GitHub #244 - the touch screen takes the companion's place, `DsTouchPane`: the side
panel locked at half the screen sideways, `SidePanel.setDsScreen`, the dock under the game upright). Addresses from pret's pokeplatinum / pokeheartgold `.xMAP`s; headless with
`native-capture/nds_dump` (its README); docs/DEVELOPMENT.md's "Nintendo DS". DEX on both (`POKEDEX_PLATINUM` /
`_HEARTGOLD`, pages from the cart); GUIDE on both (`GuidePlatinum.kt`, `GuideHeartGold.kt` over `Gen4World`:
`scripts/gen_gen4_guide.py` writes the wild sets, boss teams and HERE's items from the decomps; gym leaders go by badge -
HG / SS clears the leaders' trainer flags for rematches; `readGen4Progress` adds the badges and the DS clock's hour).
**DSperate, the second DS core (DS CORE, SCREEN group on both screens, `Prefs.dsCore` AUTO / MELONDS / DSPERATE, NEW tag;
2026-11-10)**: `third_party/dsperate` (the Linux port's submodule) is built into the APK too, for weak devices (AUTO = DSperate
where `DevicePerformance.looksWeak` or a 32-bit process: `companion/DsCoreSettings.kt`). `nds/pk_nds_core.cpp` hands pk_nds.h's
calls to `pk_nds.cpp` (`pk_ndsm_*`) or `pk_nds_dsp.cpp` (`pk_ndsd_*`); a new pk_nds call goes in `pk_nds_cores.h`'s list and
both cores. `nds/dsperate.cmake` makes the NDK fixes in the build directory, never in the submodule (the Linux port builds it
unmodified; a moved line fails the configure). On DSperate EXPAND, DS RESOLUTION, PC / FLY calls, STEADY and DS CPU BOOST are
off and greyed out (`PokeDaisyActivity.dsperate`). States never cross cores (`pk_nds_state_matches_save` 2; the other core's
resume is parked, `SaveStates.parkedResume`). Headless: `native-capture/dsp_dump` (nds_dump's commands, arm64 Linux only), which
can't show 32-bit ARM's alignment faults: a byte buffer cast to `u16*` / `u32*` in DSperate's NEON code faulted on a Galaxy Tab A
(fixed in the build copy, docs/DEVELOPMENT.md); try a new DSperate on a 32-bit device. HeartGold there: DSperate 60 fps, melonDS 16.
**French DS carts (2026-11-06)**: Platinum rev 0, HeartGold / SoulSilver, Black, White (`CPUF`, `IPKF`, `IPGF`, `IRBF`, `IRAF`): the US builds at other addresses, each run headless beside its US
cart with one script; everything but the addresses is shared. Another language: `scripts/port_nds_fns.py` (functions matched by structure and call anchors, wrong = 0 on
every known pair: `--check scripts/port_nds_fns_known.txt`, `scripts/test_port_nds_fns.py`; an UNRESOLVED hint is a lead to check live,
never an answer), docs/DEVELOPMENT.md's "French carts". Dry run (2026-11-07): B/W, B2W2 ES / DE / IT / JP / KR 13 of 14 (the BGM depth
isn't loaded from a pool), HG / SS ES / IT / KR / Europe rev 10 all 23, Japanese 20 of 23.
**DS language ports (2026-11-07)**: every cart after the French ones is a row of `gamedata/nds/ports.toml` (Spanish / Italian HG / SS,
Europe rev 10 of both, Japanese / Korean HG / SS, German / Spanish / Italian / Japanese / Korean B/W, B2/W2 in FR / DE / ES / IT / KR and
JP rev 1, Spanish Platinum: bases `heartgold` / `platinum` / `black` / `black2`, callSet 100 / 300 / 200 + n), not a hand-written table: `scripts/gen_nds_ports.py`
(`make gamedata`, `make check`) writes `pk_nds_ports_gen.h` (the native field / touch / call tables built from the US ones) and
`NdsPortsGen.kt` (`DsPorts`: configs, detection, titles, DS LAYOUT). A new cart: its row, then headless beside its US cart (docs/DEVELOPMENT.md's "Language ports"). Korean HG / SS saves have their own
footer magic (0x20070903); JP / KR text banks are numbered their own way (`NdsArt`'s `*_JA` / `*_KO`).
**DS RESOLUTION** (SCREEN group on both screens, `Prefs.dsResolution`, ORIGINAL default / 2x-8x, NEW tag; GitHub discussion #241):
melonDS's OpenGL renderer ported to OpenGL ES in our patch (`MELONDS_GLES`; ES 3.2 or 3.0 + draw_buffers_indexed) on an EGL
context of the emu thread (`nds/pk_nds_gl.cpp`), frames to EmulatorView through a SurfaceTexture (`gpuFrames`), our compositor
(melonDS's maths + EXPAND's margins by a CPU plan, `PkNdsWidePlan`), 1x copies of both screens read back a frame late. EXPAND
there is one polygon list of the whole view drawn in one pass (`submitGpu`, no slices): no seams, far cheaper. ORIGINAL keeps
the software renderer, pixel for pixel. Check GPU changes headless with nds_dump's `NDS_DUMP_GL` build (ANGLE; `gpu N`,
`gpushot`) and compare `ffmode 0` / `ffmode 3` runs; shaders compile as GLSL ES 3.00 in WebGL2 too. docs/DEVELOPMENT.md's
"DS RESOLUTION".
**Origin HeartGold (2026-11-02, the user's request)**: v4.0.3 + English patch 1.0.0-rc8, an hg-engine rebuild over the
*Japanese* HeartGold (header IPKJ rev 0, so `OriginHeartGold.matches` checks a code word first; app, native and website alike).
It runs as `GameKind.HEARTGOLD` with `originHeartGold` set (`GEN4_ORIGIN_HEARTGOLD`, `pk_nds_field.cpp` / `pk_nds_call.cpp`'s
`ORIGIN_HEARTGOLD`, callSet 7) and its own tables read from the cart (`NdsArt.readOrigin`: 1440 species / forms, 920 moves,
Fairy = 9, its type chart, icons, its DEX's pages): never bundled. Its battle engine is its own: the BATTLE tab reads its
battle objects (`Gen4ManagedBattle`), no battle buttons, the touch screen floats in while its battle waits (`battleManagerPtr`).
No GUIDE. Fixtures `origin_heartgold(_battle, _trainer)`; docs/DEVELOPMENT.md's "Origin HeartGold".
**Renegade Platinum v1.3.0 (2026-11-06)**: Drayano's hack, US Platinum rev 1 edited in place (header, RAM, save and ARM9 retail's,
but the shiny odds' word), so it runs as `GameKind.PLATINUM` everywhere the header decides (native side included).
`RenegadePlatinum.matches` (overlay 16's pointer to its moved type chart, cart 0x221060) sets `renegadePlatinum` (ActiveTables, by
the Poller): its types (Fairy = 9), moves, chart, TMs, icon palettes and wild rates are read from the cart (`NdsArt.readRenegade`,
never bundled), names from its text as every DS cart's, its art under `nds/renegade/`; no GUIDE (`Guide.kt`). Its Classic patch
differs only in `pl_personal.narc` and matches the same check. Fixtures `renegade_platinum(_battle)`; docs/DEVELOPMENT.md's
"Renegade Platinum".
**Blaze Black 2 / Volt White 2 Redux v1.4.1 (2026-11-12)**: the same path on Black 2 / White 2 (`Black2Redux`, `black2Redux`,
`GEN5_*2_REDUX`, `NdsArt.readRedux`, art under `nds/redux/`, eight builds: + Classic / EVless / Classic EVless). Told apart by
battle overlay 167's load address in the overlay table (cart 0xA02E4); its chart opens that overlay (Fairy = Gen 5's 17, which
`gen5Type` maps to 9 for every Gen 5 read). Retail saves load in them. No GUIDE. docs/DEVELOPMENT.md's "Blaze Black 2 Redux".
**DS LAYOUT (one screen held sideways, 2026-11-01; SETTINGS > SCREEN on both screens, `Prefs.dsLayout`, NEW tag)**: ONE SCREEN
(default) / SIDE BY SIDE / LARGE + SMALL / PICTURE IN PICTURE / STACKED, picked from drawn miniatures (`DsLayoutPicker`); the
stage places the screens (`GameStageLayout.dsArrange`, `DsLayouts.arrange`, edge to edge) over the game's own picture (`DsShots`:
PixelCopy about once a second - the overworld kept for menus, else the newest blurred; the user's rule: never black bars, nor a
plain colour). ONE SCREEN / PIP float the touch screen in over the dimmed game (`DsTouchWindow`, the user's modal idea) when the game
wants it: `PK_NDS_FIELD_TOUCH` from `pk_nds_field.cpp`'s touch rules (HG/SS: the X menu's task, menu screens, a battle's
bag / party / prompts; Platinum, 2026-11-01: its battle's are HG's, its X menu / party / options are top-screen pages,
`topOnly` -> `PK_NDS_FIELD_TOP_ONLY`, the game alone; Black / White: `GEN5_TOUCH`, the X menu's GMEVENT function up the
event's parents, touch-only apps by overlay, the battle's btlv_scd / btlv_input bytes - docs/ROM_FEATURES.md notes 26 / 27; Black 2 /
White 2 the same, their own offsets and overlays, 2026-11-02), or by hand (the HUD's TOUCH, the TOUCH SCREEN hotkey: L2; DS LAYOUT's, R2, steps the layouts - the user's pick: the triggers are
theirs only in a DS game on one screen, SPEED CYCLE / FF HOLD's elsewhere, `Action.dsOnly` / `Hotkeys.dsActive`). Games without rules
(`DsLayout.followed`: German HG/SS) get ONE SCREEN as PIP (`DsLayout.forGame`). The party's, the Pokédex's first and the options' pages float alone over the overworld
(`PK_NDS_FIELD_TOUCH_ONLY`, the user's call); other menu screens show both side by side (`dsMenuScreen`); a tap on the dim closes
/ presses B; outside the field and battles EXPAND gives way to the 4:3 (`dsWideScene`). A battle (HG/SS, Platinum, Black / White and their sequels) stays wide: a strip
over the text box's rows, the game's message at its far left (SoulSilver's: its gold line silver-blue), the game's four buttons at its right (the user's call:
`DsBattleMenu`, red corners in a white rim, pulsing; the D-pad its own; moves one line a card), pressing the game's touch buttons with the stylus (`NdsBattleTaps`,
its `Layout` per game; `readGen4BattleInput` / `readGen5BattleInput`). Each game's look on HG's layout (`DsLook`, sampled headless: Platinum's
bevelled buttons, cream move cards and red corners; Black / White's charcoal plates with coloured words - their round panel made a plain 2 x 2, the
user's call - teal message band and magenta round corners; Black 2 / White 2's `UNOVA2`: black plates rimmed in colour, a dark
blue band, white / pink / red rounded brackets). `gradle render -Ponly=ds-battle -PdsBattleShots=<dir of 256x192 battle frames>` draws them.
Fades (headless `trace`: HG fades by master brightness and blends, then both screens one colour for up to ~1.5 s):
both screens black (`PK_NDS_FIELD_DARK_TOP` / `_BOTTOM`, `EmulatorEngine.dsDark`) holds every DS flow and the first lit frame
decides at once; the window hides while the touch screen is blank (`dsTouchBlank`); a menu's way back to the field is covered by
the overworld's picture (the window view's cover); the battle's bag / party (`PK_NDS_FIELD_TOUCH_FADES`) float in only after
their fade (black, then 12 lit frames) and end at their first black (the battle redraws a Poké Ball for 5 frames at the bag's
step). The cover carries `DsLoader` (three dots; after 160 ms). **Menu transitions are held** (2026-11-09, players found DS loads long; `DsTransitionHold`, `EmulatorEngine.dsHold`,
one-screen DS stage only): from a menu flow's first faded frame (`pkNdsFade`: master brightness, read right after the
frame and before it's published) until the touch screen has been lit 12 frames with no fade, both screens keep their last
picture (`NdsBottomFrame.held`, the top's `holdFrame`), the window keeps its layout (the activity latches the flows), shaded
with the dots (`DsLoader(ball = false)`), and the game runs flat out, muted (at FF's speed with its music while FF is on),
drawing every 4th frame, keys and taps held back; the held picture then crossfades out. Never in a battle (its own rules).
`DsTransitionHoldTest` replays HeartGold's and Black 2's real traces (`resources/ds_transitions/`: Black 2's party keeps its
top screen one colour, so the touch screen decides). Without a hold, a window up when its touch screen goes black stays
with the dots in it (`DsTouchWindow(loading)`). Behind a window the game opened (not in a battle) the stage is frozen: one
full-size `DsShots.capture(full = true)` as the menus' first window opens on the field (also `field`, the two-screen pages' backdrop),
kept for every window until the game is back on the field with nothing up - the live stage jumped between EXPAND, a menu page's 4:3
and the two-screen layout under it (the user's report); a fresh copy on the way back from a two-screen page caught the stage half
resized (the game in a corner, black round it), so it's never retaken mid-session. HG closes its X menu after a screen left by touch
(start_menu.c's WaitApp: FieldSystem.menuInputState +0x10C TOUCH): floating, the screen's closing fade sets it to buttons
(`pk_nds_field_keep_start_menu`, `MgbaCore.pkNdsKeepStartMenu`), so the X menu comes back. The strip is drawn by the window's view (under its dim) and holds behind the bag / party; the game view holds the focus
while it's up (else Android eats the first D-pad press after a touch). Gen 4 / 5 party nicknames are read (block C).
The strip's moves are HG's move buttons redrawn (frame and colours sampled, thin top / bottom: ours are 21 DS px tall, the
game's 52) with `MoveVsFoe.label` in the corner; BACK is the battle bag's blue return button (0.72 as wide as tall); the message's text is a font pixel a
DS pixel (`dsTextMetrics`: both fonts' capitals are 9). **DS CPU BOOST** (SCREEN on both screens, `Prefs.dsCpuBoost` ON, NEW tag):
`ARM9ClockShift` 2 as melonDS's DSi mode, switched live (`pk_nds.cpp`'s `setArm9Shift`, states and STEADY's copy always at 1:
melonDS stores raw timestamps) - HG walking held a frame 3-4 refreshes every few steps (headless and on the RP6; the app's
`repeats` / `unshown` on the fps line were 0), steady 30 fps boosted; HG uses the cycles (work 3.8 -> 7.3 ms on the RP6).
LARGE + SMALL / PIP: the HUD's CORNERS go `DsCornerLayout` (buttons bottom left, party in two columns under the minimap,
three to a row above PIP's inset: `dsCorner` / `dsClearBottom`). Upright keeps the old dock (`DsTouchPane`). docs/DEVELOPMENT.md's "DS LAYOUT".
**Black (Gen 5, IRBO; 2026-10-27)**: no decomp, so every address is from headless captures of the player's save (heap blocks
carry their source file's name); `Gen5Reader.kt` (the live save image, the GameSystem's live zone, the battle's main module),
`pk_nds_field.cpp`'s `BLACK_US` (scene by the running proc's overlay, field map, event; WILD ENCOUNTERS on the zone's
encounter table), tables / GUIDE from the cart (`scripts/gen_gen5_tables.py`, `gen_gen5_guide.py`, `Gen5World`: seasons by
month), art and DEX pages by `NdsArt` (`Gen5Text`, `Gen5Lz`). MAP = the key item's Town Map (`NdsArt.townMapBlack` from a/0/8/5,
`blackTownMap`: places are points from a/0/8/6, an 8 px grid snaps to them; `townMapZonesBlack` = a zone's place, a house's
by its parent zone). PC / FLY: `pk_nds_call.cpp`'s `Gen5Fns` - the hook takes the GameSystem's field event check's place
for a frame (traced with nds_dump's `watch`), FLY's destinations are the towns' places (`Gen5Field.kt`). Its battles are all 3D, so EXPAND draws them as the overworld
(`pk_nds_battle_3d`), not with Gen 4's BG3 battle carry. Its companion look - and Platinum's, HG / SS's DEX - is the game's own
screens' (`BlackPartyPalette` with cut corners, `DsBackdrops.kt`'s patterns; the party's columns stay level, not lowered
on the right like the games' - the user's call). docs/DEVELOPMENT.md's "Black".
**White (IRAO; 2026-10-29)**: `GameKind.WHITE`, `isGen5` with Black: Black's code with its overlays and statics 0x20 higher
(`WHITE_US`, pk_nds_call's `WHITE`, `GEN5_WHITE`), Black's tables, art and hand-written guide (the cart's files are the same
but 5); its own Town Map places (White Forest for Black City: `Gen5TownMapWhite.kt`), DEX text (`NdsArt.WHITE_DEX`), wild
sets and NEXT BOSS (IRIS, N's RESHIRAM: `GuideGen5WhiteGen.kt`). White loads Black's save: the `white*` fixtures are it.
docs/DEVELOPMENT.md's "White".
**Black 2 / White 2 (IREO / IRDO; 2026-10-30)**: `GameKind.BLACK2` / `.WHITE2` (`isBw2`, in `isGen5`): Black's engine rebuilt -
its structs, every static moved (`GEN5_BLACK2`, `BLACK2_US`, pk_nds_call's `BLACK2`; field overlay 36, battle 167), a 0x26000
save image with moved blocks (event work 0x1FF00, money 0x21100, Pokédex 0x21400, Repel 0x2192D); White 2 is Black 2's code
0x40 higher and shares its tables, art and DEX (8 of 318 files differ). Black's species and moves; their own places, zones,
12 added key items, item icons, Town Map (`/a/0/8/4` art, `/a/0/8/5` places, arrival flags from 2469: `GEN5_BW2_FIRST_ARRIVAL`)
and red Pokédex: `gen_gen5_tables.py` with all four carts, `Gen5TablesBlack2.kt`, `NdsArt.BLACK2_*`. Fixtures `black2*`,
`white2` (the player's own saves). docs/DEVELOPMENT.md's "Black 2 / White 2".
**Companion options (2026-10-08)**: RESUME GAMES (`Prefs.autoResume`, top-screen Settings > LIBRARY; off =
a launch boots the save, a return from HOME still resumes - `freshLaunch`), CLOSE COMPANION (SETTINGS, two
screens: the Presentation goes away until BACK on the game), STATUS BAR OFF / GAME / COMPANION (`Prefs.statusBarOnCompanion`,
`CompanionStatusBar`: the same `GameStatusBar` over the companion's tabs, via `CompanionScreen(statusBar = …)`), TWEAKS
(`CompanionTweaks`: icon bounce, map cursor blink, tab animations, jump to battle). The Presentation is
FLAG_NOT_FOCUSABLE so HOME (the Thor's double press) acts on the game's display. ui-preview runs with a cached
Gradle (`~/.gradle/wrapper/dists/gradle-8.10.2-all/*/gradle-8.10.2/bin/gradle`): `gradle` isn't on PATH here.
**QoL batch 1 (GitHub #43 / #85)**: SETTINGS > FF / SPEED > SPEED OPTIONS > SCENE SPEEDS (BATTLE / TEXT / MENU SPEED: SAME = GAME
SPEED's, or 1x-4x, while FF is off; `EmulatorEngine.sceneSpeed`, scenes from the battle input state, `FfMenuWatch` and
the field read), CONTROLS > AUTO-RUN (B flipped while walking on the field: never locked, in battle, on a bike - the Acro
Bike's wheelie is B - or off the learned field callback, `FfMenuWatch.onField`) and RUN INDOORS (gMapHeader.allowRunning
set: FireRed +0x19 bit 1, Emerald / expansion +0x1A bit 2; Ruby / Sapphire test mapType instead, so never there). The
field read is `pk_field_state` (pk_wide.c): WIDE's table for the ROM, EXPAND on or off, its own copy of the addresses
(controls locked, gMain, object events, gMapHeader); `mgba_dump`'s `fieldinfo [run]` prints it. TEXT = locked + BG0 text
outside battles: it speeds the whole game while a box is up, not just the printing. GUIDE > HERE says "N of M caught
here" (the DexNav). PC anywhere (BAG's `PC`, `Pc.kt`; on retail the game's whole PC menu, items too: `PcMenuScript` copies its script graph to 0x0203FD00 at run time, found through the storage special, the exit's AnimatePcTurnOff blanked - left in, it stamps a PC tile on the map; hacks and anything of another shape keep the storage alone): `lockall; special ShowPokemonStorageSystemPC; waitstate;
releaseall; end` written to 0x0203FFF4 and run by ScriptContext_SetupScript - special 60 (FireRed / LeafGreen) / 63
(Emerald), traced from each retail ROM's script command table (ScrCmd_special's literal pool: the pokeemerald .map's
gSpecials symbol sat 0x14 off and gave a wrong index once). Debug builds log every scene / speed change and AUTO-RUN's ready / blocked.
GAMEPLAY > WILD ENCOUNTERS (ON / OFF since 2026-10-15, the user's call - the engine still takes a percent, and a 75 / 50 / 25 %
saved before reads as ON (100); `Prefs.wildEncounters`): the game's own
sWildEncountersDisabled (StandardWildEncounter returns at once on 1; fishing, Sweet Scent, scripted battles untouched),
found per ROM by `WildEncounters.find` from StandardWildEncounter's agbcc code (FireRed / Emerald shapes: retail in every
language + hacks that kept it), CFRU's hooked copy reading FireRed's 0x020386DC, or a looser gcc shape (`gccRead`: the
byte compared with 1, a call, 0xFFFF loaded - unique per ROM; SoulGold, Lazarus, Recharged Yellow, Seaglass, Imperium,
TMT2, Heart and Soul, R.O.W.E., Mariomon); Rogue and Quetzal aren't found (row N/A). Headless walks on the gcc hacks: warp
onto grass with `warp` (0x39 g n 0xFF x y; newer expansion asserts unless a `waitstate` follows, and Lazarus froze after
a cross-map warp - its own map worked), the grass found from the layout's metatile attributes. `pk_field_state`'s `wildRoll` sets it each frame the field is free (not locked / battle / FireRed's quest log
replay, which sets it itself) with probability 1 - keep; NORMAL puts it back once, only if we set it (or it was OFF when the game was configured:
`pk_field_wild(addr, wasOff)` - a resume state taken while OFF carries the switch on). The DS games zero the map's rates
instead and put the cart's own back from `Gen4World.wildRates` (`MgbaCore.pkNdsWildRates`): a remembered value didn't
survive a restart, and a resumed game stayed without wild Pokémon until a map change. Walked headless
(mgba_dump `wild ADDR KEEP`, warp into Route 101 / Route 1 grass): OFF 360 steps, none; 50% about twice NORMAL's steps.
**REUSABLE TMS** (GitHub #135, GAMEPLAY on the companion, `Prefs.reusableTms` OFF, NEW tag; `ReusableTms.kt`): a ROM patch, the
TM-number check before each `RemoveBagItem` (`cmp item, #ITEM_HM01-1; bhi`) turned into a `b`. The sites are found by their code
when a game loads (`ReusableTms.find`, beside WildEncounters.find): the FireRed engine has three, Emerald / Ruby / Sapphire one,
all calling one function or none (Ash Gray / Ghost Grey kept one of three: none). That gives every retail cart in every language
and the hacks on that code (Kaizo, Emerald Legacy, Orange Islands, Rocket Edition, Ultra Violet, Celia). Radical Red / Unbound / SoulGold's
TMs never run out by themselves (checked headless), so `ReusableTms.BUILT_IN` shows the row ON, greyed. Game-option patches are a
list beside the cheats' in `pk_cheats.c` (`sOptions`: loading cheats never drops them), with the same overlay / RA swap, so the
Poller's hash and RetroAchievements' see the file; they don't pause achievements. Played headless with mgba_dump's `rompatch`
(docs/ROM_FEATURES.md note 15). The newer-engine hacks build the check another way: each needs its own site.
**LINK PLAY** (2026-11-01, a game's library menu; `LinkActivity`, `LinkCore`, native `pk_link.c` / `pk_link_jni.c`): two to
four GBA games on mGBA's own lockstep link cable, apart from the player's core (`g`): only the games, no companion / EXPAND /
HUD / hotkeys / FF / states. mGBA defines only the link's hooks (struct mLockstep); `pk_link.c` fills them with Qt's
MultiplayerController callbacks on ONE thread ("asleep" = not run; game 0 the parent, the others run on its posted cycles) - never
call a sleeping parent's runLoop (its event would advance the transfer without the children). A tap / L2 R2 / L3 R3 picks the game
with the buttons and sound; the focused game's blocking audio writes pace the loop; a second screen gets half the games. Saves:
`SavesLocation.saveFor` + `SaveBackups.snapshot` each, a second copy of one ROM plays `<save> (link N)`; leaving runs frames until
no save is dirty. `native-capture/mgba_link.c` runs the same `pk_link.c` headless (its README: FireRed / LeafGreen / Emerald trades,
four-game record mixing). Not tried on a Thor yet.
**TRADE EVOLUTIONS** (2026-11-01, companion GAMEPLAY, `Prefs.tradeEvolutions` OFF, NEW tag; `TradeEvolution.kt`): the summary's
EVOLVE runs the game's own GetEvolutionTargetSpecies(mon, EVO_MODE_TRADE) + BeginEvolutionScene with gCB2_AfterEvolution =
CB2_ReturnToField, controls locked first, from the field only. Which Pokémon get the button: the same call on each slot with its 100
bytes put back (a held-item evolution's item would be spent otherwise). Records `TRADE_EVO_*` in gamedata (retail English FireRed
rev 1, LeafGreen rev 0 / 1 - found in retail by the shifted `$DECOMPS` build's masked code - and Emerald); hacks / Kaizo / Legacy off.
**INSTANT TEXT** (GitHub #111, GAMEPLAY on the companion, `Prefs.instantText` OFF, NEW tag; `InstantText.kt`): a game-option patch like REUSABLE TMS (`pkRomPatch` in 4-byte pieces, `EmulatorEngine.applyTmPatch` does both). RenderFont rewritten in its own 40 bytes (no free ROM): it goes on past RENDER_PRINT until a control code, textSpeed 0; Ruby / Sapphire: UpdateWindowText's delay turned into a `b` back to PrintNextChar. A delay of 0 (the game's own instant path) would lose everything after a page break. Found in the ROM's first 64 KB by code; gcc builds: N/A. TEXT SPEED stays: it's the emulator's speed, stops at what the device runs.
**PLAYER LOOK** (2026-10-22, the companion's MAKEOVER group - first, a blue-framed `featuredGroupTitle`, only in a game it works in; per ROM: `Prefs.playerLookFor`, NEW tag; `PlayerLook.kt`,
`PlayerLookRunner.kt`, docs/DEVELOPMENT.md's "PLAYER LOOK"): play retail FireRed / LeafGreen / Ruby / Sapphire / Emerald (and Kaizo, Legacy, Imperium, Ultra
Violet: `PlayerLook.HACKS`) as another game's hero, picked from tiles of the heroes walking (`PlayerLookPicker`),
its graphics found by fingerprint in the player's own ROMs (`scripts/gen_player_look_sigs.py`; kept in `rom-art/look/`, never
bundled) and patched into the running ROM in memory as blocks (`pk_cheats_block`, overlaid for the Poller / RA hashes like the
options' patches; mgba_dump `romblocks` / `romunblock`). The player's palette slot is shared with the surf blob, shadows, arrows
and Emerald's FLY bird: across games their colours stay and the hero is remapped into the rest (`PlayerLook.wear`) - a plain
palette swap recoloured the blob. Loaded palettes are swapped in RAM on toggle and after state loads (`pk_cheats_repalette`).
The companion's MAP head and CARD follow `PlayerLook.worn`. Ruby / Sapphire are a third engine (`LookEngine.RUBY`: Emerald's
sheets, walking + running in one, front and back palettes on one tag); a hack is offered only once played dressed up.
Heart and Soul's Gold / Kris are worn by the others (`LookEngine.HEART_AND_SOUL`, never a host; smol front pictures).
The party's Pokémon too (`MonArt`: the follower sprites of Heart and Soul, R.O.W.E., Recharged Yellow, Seaglass; `buildMon` makes the hero's records 32x32 over
new frame tables, frames picked by the records' own animations). RIVAL LOOK (`Prefs.rivalLook`, on): Emerald / R/S's rival (the other gender's hero: same sheets, palette tag,
trainer pic) wears the look's `counterpart`, built in the same patch (`build(rival = true)`, `settleBoth`). TEXT LOOK
(`TextLook.kt`, same group and patch): FireRed's / Emerald's dialogue box and font in the other; FireRed's box in Emerald
needs Emerald's frame routines swapped for `scripts/text_look_dialogue.s` (assembled bytes in `TextLook.DIALOGUE_CODE`).
FOLLOWER (same group): the party lead walks behind the player as one more hardware sprite put in at line 0 and taken
out after line 159 (pk_wide.c `followBegin` / `followEnd`: OBJ tiles 1008-1023, a free OAM entry and palette slot).
DAY AND NIGHT (`DayNight.kt`, same group, any GBA game): the renderer's BG palettes 0-12 + OBJ tinted at line 0 through
`writePalette` (pk_wide.c `tintPalettes`, palette RAM untouched; text windows 13-15 kept), eased by the engine on the field.
**QoL batch 2**: PC anywhere on every language + FireRed rev 0 + Ruby / Sapphire (`port_field_items.py` maps PcFns, each
checked against its gSpecials; R/S's special is ShowPokemonStorageSystem, 60, same opcodes) and so on the hacks using those
tables (CFRU, Gaia, Odyssey, Orange Islands, Ultra Violet: retail's gSpecials, checked). The field reads take the WIDE
`pending` hacks too (`PK_FIELD_ONLY_ROMS`, `pk_field_knows`, `WideScreen.fieldGameFor` - EXPAND stays off there) and
Celia's CONTROLS_LOCKED (0x03000FA4); the activity's `fieldGame` (not `wideGame`) gates the field rows. Bike / surf come
from gPlayerAvatar.flags (`playerAvatar`: gObjectEvents + 0x240, R.O.W.E. + 0x4A8 from its ROM symbol table, checked live
and latched) - the sprite-id check misread R.O.W.E.'s u16 graphics id as a bike; SCENE SPEEDS gained BIKE / SURF SPEED
(Scene.BIKE / SURF, N/A until `EmulatorEngine.fieldMovesKnown`). pkFieldState now runs every frame on a known game.
docs/ROM_FEATURES.md has a column per QoL feature.
**No empty BATTLE tab**: the tab (and the jump to it) needs `SnapshotView.showsBattle` - in a
battle *and* something read (a battler, the foe's party or the battle input state). A game whose
battle memory isn't mapped stays on its tabs instead of showing a blank INFO page. Seaglass's battle
block is Lazarus's layout (its gBattleStruct pointer 8 bytes earlier); its map is its own redrawn
Hoenn tiles (`SGL_REGION_GFX`) on Emerald's tilemap - Emerald's tiles aren't in its ROM, so without
a retail Emerald scanned it had no picture.
**Lazarus's own region map** is Emerald's `region_map.c` with its own art (gcc build):
`RomArt` rebuilds `regionmap/lazarus.png` from fingerprints (`LZ_REGION_*`, from the
Lazarus ROM in `gen_rom_art_sigs.py`), and `gen_expansion_tables.py` writes its section
rects + cursor grid (`regionLayoutsLazarus`). **CFRU hacks' tables** (Radical Red:
`scripts/gen_cfru_tables.py`) are found by comparing a retail FireRed rev 0 ROM's literal
pools word for word: CFRU repoints them, so the word at the same offset is the hack's table.
**FireRed-engine hacks' own region maps** (Unbound, Odyssey, Gaia, Amethyst, Radical Red) come
from`RomRegionMap` (`companion/data/RomRegionMap.kt`), not fingerprints: those hacks keep
FireRed 1.0's `region_map.c`code and only repoint its data, so it follows that code's literal
pools (tiles, palette, 4 tilemaps, names, corner/size tables, the Sevii list + the`cmp r0, #SEVII_MAPSEC_START-1`hacks move) on every launch, caches the screens (backdrop border
cropped) as`rom-art/regionmap/rom-<crc>-v<N>-<i>.png`, and `lookupLocation`prefers it for every
game but FireRed. Needs a BPRE rev 0 ROM whose pointers land on valid data, else nothing
(Celia's hack, LeafGreen).`RomRegionMapTest`pins it; ui-preview:`-Pgame=UNBOUND -Prom=<rom>`.
The Map tab (`MapScreen.kt`) draws the region map like the game: the place name in the game's
own label (`MapLabelStyle`, sampled headless: FireRed family = a darkening strip top-left, white
text, plus a second strip for a dungeon on that tile; Emerald family = the framed window
bottom-right), the player's head (`regionmap/player*{red,leaf,brendan,may}.png`, from the ROM by
`RomArt`, picked by `SaveBlock2.playerGender`) on the tile the game puts it (`PlayerMapTile`=
region_map.c's GetPlayerPositionOnRegionMap, from`gMapHeader`'s layout size + mapType; indoors
it keeps the last outdoor tile), and FireRed's map cursor (four white 2px corners that swap
between two sizes every 20 frames, from `graphics/region_map/cursor.png`), for every game.
Tapping a tile names it via the game's own cursor grid (`RegionMapModel.pick`; FireRed/Emerald
grids are `RegionMapLayoutsGen.kt`from`scripts/gen_region_map_layouts.py`, FireRed-engine
hacks' come from the ROM by `RomRegionMap`, other games fall back to the smallest section rect);
PLACES lists every town / route / place to jump to (the "where is X?" answer), the region
button flips Kanto / Sevii maps, ME returns to the player. `MapSecData.kt`'s dungeons and other
"not on the map" sections are 0,0,0,0 (they used to sit at a fake (4,4)); the grid's dungeon
layer places them (`RegionMapModel.tilesOf`). ui-preview: `-Ponly=map` renders the tapped /
PLACES / region states too.
**DEX entry sections (GitHub #28)**: chips over the entry's right column switch INFO / EVOLVE / AREA / MOVES
(`DexSection`, `ui/DexDetailPages.kt`; `DexUiState.section` sticks while stepping entries), data from
`DexDetails` (`data/DexDetails.kt`, process-wide caches per ROM, IO only). EVOLVE = the whole family both ways, each
row decoded into `EvoReq`s by the game's `EvoScheme` - methods past vanilla's 15 are numbered differently in every
engine (CFRU vs Radical Red, Gaia, expansion before 1.12, Lazarus one lower, Rogue's own 47+, Quetzal / R.O.W.E. to
42 / 31; expansion 1.12+ is EVO_LEVEL / TRADE / ITEM ... + an `IF_*` condition list ending at `conditionsEnd`, 37 in
TMT2, 39 in SoulGold / Heart and Soul), each checked against its ROM's own table (`DexDetailsTest`); an unnamed method
reads "Special condition" and gives way to a named one to the same target. Layouts: `PokedexTables.evolutions` (vanilla)
or `evoLayout` (a table, or `SpeciesInfoDex.evolutionsOff`). AREA = every wild header's slots (all time-of-day sets) by
map section via `GuideTables.mapGroups` (gMapGroups; Unbound XORs it, SoulGold's sections are u16; a wrong / missing one
is found by shape in the first 16 MB), plus gifts / trades from the area data. MOVES = level-up from
`GuideTables.learnsets` / `SpeciesInfoDex.levelUpOff` / `PokedexTables.levelUpLearnsets`, then TM / HM
(`tmhmLearnsets` + `tmhmMoves`, retail only, checked on BULBASAUR) or expansion's `teachableOff` list; CFRU / Gaia /
Glazed / Odyssey say level-up only. Celia has no evolutions or learnsets located; Rogue no learnsets (its baked profiles).
**Move advice (GitHub #45, phases 1-2: the original games only)**: `MoveAdvisor` (`data/MoveAdvice.kt`) scores a moveset
as the mean over defending types of its best hit (power x accuracy x1.5 own type x the stat it runs on - Gen 3 splits
physical / special by type) plus status / side-effect tiers by gBattleMoves effect (a share of that score, less for a second
of a kind); the best swap wins, SKIP under `MIN_GAIN` (`MIN_GAIN_HM` to learn an HM); HMs are never forgotten. Ties prefer
forgetting a weaker move of the new move's type. Data: `MoveStatsGen.kt` (`scripts/gen_move_stats.py`, pokefirered's
gBattleMoves - shared by FR/LG/R/S/E, `movesAreVanilla` = FIRERED / EMERALD kinds), TM items 289..346 -> `DexDetails.tmhmMoves`.
`MoveLearning` gathers a mon's next 4 level-up moves + bag TMs (`options`) and a TM's party fit (`learners`), on IO, cached for
the UI to start from. UI: the summary's LEARN button (`CanLearnSection`, moves left with a FORGET tag on the pick's victim) and
the ITEMS bar's `TmLearnersRow` (`ItemsScreen(itemExtra = ...)`); verdicts follow BATTLE HINTS, facts don't. Hacks need their
own move stats (their generators read gBattleMoves already) and TM item -> move mapping; phase 3 (the game's "forget a move?"
prompt) needs per-game addresses.
**IVs / EVs (summary STATS, battle INFO STATS)**: `decodePartyMon` reads them from the BoxPokemon itself
into `Mon.stats` (`MonStats.kt`): EVs substruct, Misc +4's IV word (plaintext CFRU boxes: +0x38 / +0x48),
nature = PID % 25 XOR expansion's `hiddenNatureModifier` (+0x12 bits 3-7, 0 elsewhere: Mints), stats from the
party fields. Checked on every fixture by recomputing the stored stats from the ROM's base stats (equal, or
1 below where EVs came after the last level-up); SoulGold's 96-byte struct didn't add up, so it gets none.
Battlers get theirs by matching species / level / HP
against the party and `Telemetry.battleFoes` (gEnemyParty, now read in wild battles too; `enemyParty` stays
trainer-only for FOE TEAM). Hidden Power's power shows only on FIRERED/EMERALD kinds (expansion uses a flat
60). Foe IVs sit behind SETTINGS > FOE IVS (`Prefs.showFoeIvs`, off): INFO's STATS button.
**Battle POKéMON pane + automated input** (`BattleControlsScreen.PartyPicker`, `BattleInputController`):
on FireRed rev 1 / Emerald (`switchAddrsFor`in`PokeDaisyActivity`; gPartyMenu
FR `0x0203B0A0`/ EM`0x0203CEC8`, slotId at +9) and the hacks listed there (Unbound since
2026-10-13: FireRed's addresses under CFRU's two-column grid, `grid = true` like Amethyst - DOWN from slot 0 lands on 2;
SoulGold's config entry, checked headless the same day - both with the lead copied into slots 2-3, A + SHIFT
on slot 2 put 2 into gBattlerPartyIndexes[0]; SoulGold's shape is its OPTIONS > PARTY MENU, read at every step: CUSTOM a list
of six with no CANCEL row, HGSS / BW a two-column grid - `partyMenuStyleOff`, SaveBlock1 +0x348C, `SwitchAddrs.gridOption`;
steered as a list on HGSS it bounced between two slots, GitHub #316), POKéMON opens the PARTY tab's own slots
(`PartyGrid`, shared with `PartyScreen`; taps leave the sound to the game, as above) - the game's cursor and a BEST tag on
`recommendedSwitch`= SUGGESTIONS' top pick with BATTLE HINTS on, else the cursor on the battler (tagged OUT), and a
"Choose a POKéMON." window with CANCEL below, full height (no ball strips or INFO / SUGGESTIONS over it); it also replaces the game's party screen whenever that's open (after a faint). A tap runs`switchTo(personality)`:
POKéMON on the action menu, then a closed loop on the game's memory - `OpenPartyMenuInBattle` really reorders
gPlayerParty to battle order while the menu is open, so the mon is found there by personality and DOWN is pressed
until slotId matches (Emerald drops presses for ~35 frames after the menu opens, so it re-reads after each), A,
A on SHIFT. While a switch runs the top screen keeps its last frame (`BattleInputController.holdFrame`->`EmulatorView.holdFrame`skips the texture upload), so the game's fade to black and its party menu never show;
let go 12 frames after the switch ends (WaitForMonSelection only returns once the battle has faded back in).
Verified headless on both games (scripted wild battle via CreateScriptedWildMon +
StartScriptedWildBattle). **The Game Boy games' switch** (Yellow in all five languages, Crystal, Crystal Clear, Prism;
2026-10-14) is the same pane over `SwitchAddrs(gb = …)` (`companion/data/GbPartySwitch.kt`, from
`TelemetrySampler.gbPartySwitch`): no gPartyMenu and no personality, so a party read's `personality` there is
`gbMonKey` (species, OT ID, DVs) and the switch looks the slot up by it each step; the party isn't reordered in battle.
The menus (pret/pokeyellow, pret/pokecrystal): Yellow's party list is HandleMenuInput (wTopMenuItemY 1 / X 0,
wCurrentMenuItem = slot, wraps, no CANCEL), Crystal's a _2DMenu from (1, 0) with a CANCEL row (`GB_PARTY_CANCEL`); A on a
slot opens SWITCH / STATS / CANCEL (top-left (12, 12) in both, SWITCH first every time), except after a faint
(`partyMenuType` 2 / `partyMenuAction` 2: A sends the slot out). A menu counts only while its ▶ is filled (a pick turns
it hollow at once), so the readers report PARTY_OPEN for the list and the SWITCH menu (an item's "use on which?" party
menu is left out by its type) and `driveGbSwitchLocked` re-presses whatever the menu dropped: Yellow drops presses for
Delay3 after drawing a cursor, Crystal's SWITCH menu for up to ~30 frames, and **Prism's party menu reads keys every other frame**,
so every switch press is held 2 frames and released 2 (`pressGbLocked`: one-frame A's re-pressed 12 frames apart all hit
the same parity and never took). Its battle menus read every frame (one-frame RIGHT / DOWN / A / B took at all 8 frame
offsets, headless), so FIGHT, moves and RUN keep one-frame presses. A mon that's gone, fainted or out is refused before any press; after SWITCH the list
coming back means the game said no (Crystal's "can't be recalled") and the switch stops. The frame is held 20 frames past
the pick (white, then the battle: ~8 frames Yellow, ~14 Crystal). Checked by driving the real controller and readers
against mgba_dump's running game frame by frame (a local harness over its stdin; not committed): every case in
docs/ROM_FEATURES.md's note 5; fixtures `yellow_battle_party` / `_switch_menu` / `_faint`, `crystal_battle_party` /
`_switch_menu` / `_faint` (`GbPartySwitchTest`), the simulated menus in `BattleInputControllerGbTest`. RUN presses A to close "Got away safely!" (every 30 frames until the battle ends or the
action menu returns), each only on a battle state read that same frame (`BattleInputController.stateNow`): A says YES to
Unbound's (CFRU's) "Are you sure you want to run away?" (a shiny foe, or flag 0x16C6) - B said NO, and RUN did nothing - but
an A that met the action menu back between two polls would run again. With no fresh read it's B, as before. Against a
shiny foe (`runAsksFirst`, from the battlers' shiny read) RUN presses nothing after the pick: the user wants Unbound's question
answered by the player on the game's own YES / NO (flag 0x16C6's case isn't readable: its flags sit past the ones read). All synthetic presses go through`GbaInput.setScript(bits, exclusive)`: the player's own
keys are ignored while a sequence runs (they used to share `touchBits`, which also wiped the touch pad's presses) -
only while presses play or a closed loop steers (`exclusiveLocked`), never during the passive target-confirm /
pending-move watches: those once held the lock through the whole turn, so a level-up or "learn a move?" message
waiting on A left the player stuck. Watches expire after 300 frames; a 1200-frame watchdog cancels any lockout.
`BattleInputControllerTest`simulates the party menu.
**Faster picks (GitHub #33)**: FIGHT / BAG / RUN / a move used to walk blind - LEFT, UP, then RIGHT / DOWN, A, 20 frames
after each direction (Unbound's move screen drops presses during its cursor fade): 46-90 frames a tap. Where the menus'
cursors are known (`NativeConfig.actionCursor` / `moveCursor`: gActionSelectionCursor / gMoveSelectionCursor, a cell per
battler; FireRed rev 1, retail / European Emerald; zeroed on JA Emerald and LeafGreen) a pick is a closed loop
(`drivePickLocked`): read the cursor, press only toward the cell (a dropped press is pressed again), A once there - the move
already highlighted is one press. Since 2026-10-13 a pick **writes** the cell into the cursor (`BattleInputController.writer`, MgbaCore.pkWriteBytes,
read back) and presses A once: the game reads the cursor on A and draws it from there the next time the menu opens (headless on
FireRed: BAG -> FIGHT, move 0 -> THUNDERBOLT, PP spent, no stray arrows next turn) - one game select sound instead of one per
step (the user heard three clicks a move). A write that doesn't take walks as before. Headless on both retail games: presses land back to back and A on the next frame. The
Poller hands them out only for a retail hash / other-language retail / a hash-matched hack (`knownCursorAddrs`,
`ramVerified`) - never a BEST EFFORT match or an unrecognised same-size hack on a retail config, where a wrong byte holding
the wanted cell would confirm the wrong choice; a cursor out of 0..3 or that won't move falls back to the blind walk. **Hacks
get the one-press picks only with their cursor addresses on the config** (the rest walk blind, 46-90 frames a tap - the
"taps take a while on hacks" report): Unbound keeps FireRed's (`NATIVE_UNBOUND`, CFRU's EWRAM), SoulGold's are 0x02001E50 /
0x02001E54 in every build, Kaizo inherits retail Emerald's - each checked headless (RIGHT / DOWN walk the byte 0 -> 1 -> 3, a
written 2 + A opens the party menu, a written move cell + A spends that move's PP: `unbound_battle`'s README has the recipe).
Batch 2 (2026-10-13, same checks, fixtures `radical_red_battle` / `gaia_battle` added): Radical Red and Gaia keep FireRed's,
Glazed retail Emerald's, Lazarus's sit +0x10 / +0x14 past its gBattleStruct pointer (0x0200083C / 0x02000840). The other CFRU
hacks very likely share FireRed's and the expansion hacks need a dump diff each; add them the same way. Party menus seen so
far: CFRU's two-column grid on Unbound / Radical Red / Amethyst / Odyssey / Sword and Shield, FireRed's own list on Gaia
(DOWN 0 -> 1), a list on Glazed / Seaglass / TMT2 / Heart and Soul, a grid on Lazarus, SoulGold's by its option. Batch 3 (same day):
Odyssey, Amethyst, Sword and Shield keep FireRed's cursors; Seaglass's are Lazarus's layout 8 bytes earlier (0x02000834 /
0x02000838); TMT2's 0x02000414 / 0x02000418 (dump diff). **Heart and Soul** had no battle input at all: its controller
funcs (0x03002F54) and the action / move / bag / party handlers came from diffing dumps of one Route 29 battle (no
scripted battle there yet: its gScriptCmdTable at 0x08500DE0 is referenced only from a pointer table at 0x08500C94, not
from any Thumb pool, so ScriptContext_SetupScript wasn't found - pace the grass, RTC-seeded, it took one try each time);
cursors 0x020003A4 / 0x020003A8, gPartyMenu 0x020341FC (slotId +9, a list); no target handler and no
gBattlerPartyIndexes (five candidate words after a switch, none confirmed). Batch 4 (same day, the same checks, every
build with a `*_battle` fixture pinned in `BattleCursorTest`): Orange Islands / Ultra Violet keep FireRed's; Recharged
Yellow (all four), Super Mariomon (all five), Quetzal (EN / ES), Imperium and R.O.W.E. have theirs +0x10 / +0x14 past their
gBattleStruct pointer (the expansion / gcc order held everywhere but TMT2 and the CFRU-moved Celia); **Celia** had no
battle input either: handlers / cursors / gPartyMenu / gBattlerPartyIndexes from dump diffs of a scripted battle (its
ScriptContext_SetupScript, 0x0807676D, found by matching rev 0's code with pool loads and calls masked - the same trick
should find Heart and Soul's once its code shape is known). Party menus: lists on Orange Islands / Ultra Violet / Recharged
Yellow / Quetzal / Celia, grids on Mariomon / Imperium / R.O.W.E. (as their entries said). Only Emerald Rogue is left out
(touch battle input off by design) and the QoL builds (no fixed addresses). SETTINGS > ONE-TAP MOVES
(`Prefs.oneTapMoves`, off; COMPANION group, under BATTLE HINTS) puts the moves on the action menu where FIGHT is
(`MoveCard(compact)`: name sized to fit beside PP) - a tap is FIGHT + the move in one sequence; ui-preview `*-battle-onetap`.
**Switches run flat out**: the party menu fades in for ~50 frames (presses dropped, headless on both games), then the
cursor moves every 3 frames, SHIFT takes A 6 frames (FireRed) / 2 (Emerald) after the mon's. So no fixed settle any more -
NAVIGATE presses at once and re-presses what the fade drops (bounded by MENU_TIMEOUT_FRAMES), goes the shorter way round the
list (UP wraps 0 -> CANCEL 7 -> 5), 3 frames between reads, 8 before SHIFT - and while the frame is held (`holdFrame`) the
engine runs uncapped and muted (`effectiveSpeed`, `wantPaused`), sampling the battle state every frame so it stops as the
battle fades back in rather than 66 ms (dozens of turbo frames) later.
**GAME SPEED / RTC (GitHub #50)**: the companion's FF / SPEED group has GAME SPEED (0.25x ... 4x, a pick-list): it sets
`EmulatorEngine`'s speed step, saved per ROM like SPEED CYCLE's (`Prefs.speedIndexFor`). `SPEED_STEPS` appends 0.5x / 0.25x
after the old five (saved indexes keep their meaning); SPEED CYCLE only cycles the first `CYCLE_STEPS` and goes from a slow step
back to 1x. Below 1x the timer paces frames and audio is muted (as SLOWMO HOLD's 0.5x always was); FF, when on, wins. FF SPEED
gained 1.5x on both screens. The RTC needs no code of ours: mGBA's GBA reset applies its overrides - RTC for AXVE / AXPE /
BPEE by code, and for any Pokémon ROM it doesn't know by CRC (the hacks) - reading the device's time(0); saves and states keep it
(`SAVESTATE_RTC`). Verified headless: retail Emerald's `sErrorStatus` (0x03000DB8) is 0 and `sRtc` holds the real date / time. One
exception, `pkHealRtc` (pokedaisy_jni.c, after reset and state loads): mGBA restores the clock from the 16 bytes after the
save data, and a save dumped off a real cartridge with a dead battery (power-fail bit) or a 12-hour control byte made R/S/E
say "The internal battery has run dry" - such a clock goes back to a fresh 24-hour one (checked headless: sErrorStatus
0x20 / 0x10 -> 0).
**FF MODE** (SETTINGS,`FfMode`: SMART default / NORMAL). NORMAL = fast everywhere. SMART = 1x while a
menu screen or the region map is up, FF (still on) resuming when it closes. **A battle stays fast but where it waits
for the player** (the user's rule, 2026-10-19: the bobbing Pokémon and boxes at 1x while choosing): in battle the game's
own battle input state decides (`smartSlows`, `SmartFf.kt`, fed by `BattleInputController.gameState`) - ACTION / MOVE /
TARGET_SELECT and PARTY_OPEN / BAG_OPEN slow down, animations and text never, whatever the menu watch below thinks
(it once slowed whole battles); a DS game reports the wait itself (`MgbaCore.FIELD_BATTLE_INPUT`: HG/SS's
BattleContext.command = SELECTION_SCREEN_INPUT, `pk_nds_battle_command`); the menu and map watches only decide outside
battles, or in a game that doesn't report a battle state. Menus are found by `FfMenuWatch`with no per-game table: the field and battles each run one fixed`gMain.callback2`, every standalone screen
(party, bag, summary, PC, Pokédex..., in battle too) its own - the START menu and dialogue are overlays that keep
the field's (checked headless on Emerald, Rogue). gMain is found by scanning IWRAM twice 16 frames
apart (vblankCounter1 at +0x20 moving exactly that much; FireRed's is a pointer, so counter2 at +0x24 then); the
field's callback2 is learned from the player moving (snapshot positions), the battle's from frames in battle,
both persisted per ROM CRC (`Prefs.ffMenuCallbacks`). Ruby/Sapphire's inBattle byte is +0x43D.
**Shops stay fast** (GitHub #41, the user's call: the clerk's lines are printed inside the buy screen and crawled at
1x): `ShopWatch` says a menu screen is a shop's while a script context's scriptPtr sits just past a `pokemart` /
`pokemartdecoration(2)` command (0x86-0x88 + a ROM pointer) - the clerk's script waits there through the menu, the buy
screen and the sell bag. The contexts are found by shape in IWRAM (ROM command table + its end at +0x5C / +0x60), no
per-game table; captured headless on retail Emerald and FireRed (`fixtures/ff_shop`).
**Settings search / subtitles / setup buttons**: top-screen Settings HOME has a search box (`filterRows`, SettingRows.kt):
a row matches on its label (English and translated), value, `subtitle` and its words in `SETTING_KEYWORDS` (by English
label, shared by both screens: "rebind", "keybinds", "turbo"... - give a new row its words). The companion's SETTINGS has
no search (the Presentation can't host a keyboard). `SettingRow.subtitle` / `OptionLine(subtitle)` draw a grey line
under the label (GAME BUTTONS: key bindings; each TWEAKS switch its own `Tweak.description`, on a wider label column). **Rebinding is always by pressing the keys,
never a list of key names** (the user's rule: a list confused players): `KeyCapture` (companion/KeyCapture.kt) + `KeyBindRow` /
`KeyCaptureBar` / `KeyCapturePopup` (companion/ui/KeyCaptureUi.kt), one per activity - top-screen Settings, first-time setup's
GAME BUTTONS step (`SetupState.capture`: rows rebind in place, never another screen) and the game, which runs the companion's
(`CompanionSettings.keyCapture`: the Presentation gets no keys, so `PokeDaisyActivity` takes every key / trigger first and shows
`KeyCapturePopup` over the game screen, `GameStageLayout.overGame`, letting go of the game's keys / FF holds / chord state). While
one waits every key is its (system keys aside): a hotkey takes a chord, a GBA button one key and never the D-pad; BACK cancels; a
key held when it began, or still held when it ends, keeps its release there. HOTKEYS and TURBO A / B can be unbound (CLEAR
while a row waits), and a chord another hotkey already has
exactly (same keys, any order - sharing only some keys is fine) asks KEY IN USE / MOVE first (`Hotkeys.clashes`, `takeFrom`);
clashes already in a file are left alone. `KeyCaptureTest`; ui-preview `*-capture` shots.
**SETTINGS layout**: the options come in titled groups in ONE scrolling column at the normal text size (a two-column,
denser try was too small to tap - the user's call), **ordered by how often they're changed** (the user's rule, 2026-10-15:
what you flip while playing first, set-once setup last, LANGUAGE just above HELP at the very end): companion = (GAME: PLAYS
AS) / FF / SPEED / GAMEPLAY (WILD ENCOUNTERS, AUTO-RUN, RUN INDOORS, CHEATS) / SCREEN / COMPANION / CONTROLS / APP (LANGUAGE,
HELP), ending in CLOSE GAME / RESTART GAME (under a `Separator`); the top-screen Settings uses the same `GroupedRows` /
`SettingRow` (`companion/ui/SettingRows.kt`) with FF / SPEED / SCREEN / LIBRARY / ONLINE / CONTROLS / APP. A new row goes
where its frequency puts it, in the same place on both screens. Set-once rows of a busy group go on a sub-page whose row stays
in that group: FF / SPEED > SPEED OPTIONS (SCENE SPEEDS - companion only - FF MODE, FF MUSIC; `Page.SPEED` / `Screen.SPEED`) and
SCREEN > DISPLAY OPTIONS (STATUS BAR, SHADERS, OVERLAY, an upright phone's COMPANION; `Page.DISPLAY` / `Screen.DISPLAY`). SHADERS
opens from DISPLAY OPTIONS, so BACK returns there (`Page.parent`, SettingsActivity's `goBack`). A row that opens a page of its own (not a pick-list) sets
`SettingRow.opensPage`: a pixel chevron (`PixelIcons.chevronRight`) at its right end. Sub-pages and pick-lists get the title window's back arrow (`onBack`). The tabs not in the tab bar used to sit under the
list as a second row of chips (TOOLS); they're the MORE tab now.
**Settings round (2026-11-01, the user's calls)**: the top-screen Settings draws a step smaller (`LocalTextStep` -1 for the whole
activity); rows whose label says it all carry no subtitle; a row can be a slider (`SettingRow.slider` -> `SettingSlider` /
`GbaSlider`, LEFT / RIGHT step it) and GAME VOLUME's opens `OptionSliderPopup` on a tap (a bigger bar, - / +, DONE); the tools
are the library's TOOLS tab, not a Settings group (2026-11-03); NUZLOCKE sits under MORE in every game the tracker follows, a TURN ON page
while it's off (never a bar filler then); TRADE EVOLUTIONS' row reads TRADE EVOS (with NEW the long label left the badge a
dot); Monomyth's orange party backdrop gets a blue D-pad ring (`AppColors.focusRing`).
**OVERLAY** (SCREEN group on both screens, `Prefs.overlay`; `overlay/Overlays.kt` + `OverlayBitmaps.kt`, drawn by
`EmulatorView`): a frame around the game. Two built-in frames of our own (DAISY / BEZEL, drawn at the game's pixel size,
shown at a whole scale) plus the player's imported RetroArch overlays (.cfg + PNG, .zip, bare PNG; window = the .cfg's
viewport or the PNG's see-through middle) under `<external files>/overlays/`. **No third-party overlay is ever bundled,
linked file by file or shown in our screenshots** (the user's rule: the usual console borders carry the console maker's
logo and shell). Imports happen on the top screen (file picker); the companion only picks. See docs/DEVELOPMENT.md's
"Overlays".
**FF steps aside on the game's region map** (SMART only):`RegionMapWatch`finds the map screens' EWRAM
pointers per ROM (FireRed family:`sRegionMap` `0x020399D4`, found beside its `0x4796`struct
offset in the literal pools - Town Map, Fly map, wall maps; retail Emerald:`sFieldRegionMapHandler`+`sFlyMap`+ the PokéNav's HOENN MAP - Emerald has no Town Map item -
via`gPokenavResources` `0x0203CF40`->`substructPtrs[POKENAV_SUBSTRUCT_REGION_MAP_STATE]`(+0x1C, set only while that page is up; verified headless); Emerald hacks and Ruby/Sapphire:
none yet - R/S's PokéNav map has no clean "open" flag), and`EmulatorEngine` caps the
speed at 1x while one is non-null - FF stays on and resumes on close. Verified headless
(`call ShowTownMap`: 0 on the field, set while open, 0 after B).

**FF MUSIC** (SETTINGS: STEADY / SPED-UP / OFF, `FfMusicMode`, in that order - OFF last).
SPED-UP plays the core's own audio box-filtered down to real time, written non-blocking so FF is
never paced by the audio device. STEADY loops a clean clip of the song playing (`FfMusicPlayer`,
`FfMusicCache` per ROM CRC), keyed by the song itself: `FfMusicKey` reads the BGM player's
`songHeader` from the m4a engine every frame (`0x03007FF0` -> SoundInfo -> player chain, BGM is
the tail) - no per-game song table (a hand-picked FireRed "battle song" was MUS_RS_VS_TRAINER, i.e.
Emerald's theme). `M4aSongs` matches agbcc's `m4aSongNumStart` and, masked, Emerald Rogue's gcc build of it, newer expansion's
(ident check), Monomyth's (agbcc, its gSongTable's headers stored top bit flipped: `Signature.headerXor`) and Heart and Soul's, which takes a second argument: set, it plays the song's entry in an alternate
soundtrack table (its songs are `M4aSongs.ALT` ids; `pkRenderForceSong` always passes r1, which used to be whatever the
game left there); the render core boots 300 frames, then waits (up to 30 s) for the game's own music, since Rogue keeps the sound engine paused through ~15 s of splash screens and a forced song never starts there. Clips are **never captured from the live game** (that mix carried menu clicks
and battle sounds): `FfMusicRenderer` renders each song alone on the second core (`rg`) as the
game starts it, calling the ROM's `m4aSongNumStart` - found by code signature (`M4aSongs`; every
agbcc-built game and binary hack, not pokeemerald-expansion) - via `pk_call`, which runs the
call to its return and restores the CPU (a bare PC hijack corrupted Unbound mid-frame; a frame that ended inside the game's
interrupt handler is refused, so the renderer retries a frame at a time, `forceSong` - every song after Let's Go's title once
"didn't start"), after
parking the booted game on a `b .` so its intro can't switch songs (only once the VBlank IRQ is
fully on - Unbound froze otherwise). Until a song's clip exists STEADY is silent
through FF (`SongRenderer.willRender`; the user's call - it used to play the song SPED-UP meanwhile); only a song
that can't get a clip (an unrecognised sound engine, FAILED / ENDED / not in the table, a renderer that died)
plays SPED-UP. Every ROM pre-renders its music once in the background: FireRed/LeafGreen/Emerald-sized
ROMs their `MapMusic*.kt` list, any other ROM every `gSongTable` entry on the BGM player
(`M4aSongs.bgmSongIds`, `ms` 0); a song being heard pre-empts that pass mid-recording, songs that end by
themselves (fanfares) count as done. **Clips loop where the song loops** (`M4aLoop.kt`, cache v6): the BGM's
first track jumping back through its GOTO (0xB2) marks the loop end, the frame its main stream passed the
GOTO target the start (no intro: two GOTOs apart); `M4aLoopSplice` cuts one period starting 1 s into the
loop, tuned to the sample. The recording before that cut is saved as `<key>.intro.wav`: `FfMusicPlayer` (an AudioTrack built like the game's, USAGE_GAME / low latency: a MediaPlayer played as media on the
deep-buffer output, where the Thor's settings app runs a system-wide equalizer - the clips came out louder and harsher
than the game) plays it once, then
runs straight into the loop in one stream, both started at the song's own position
(`Clip.songStartedAt`, when the BGM key changed). Without the intro a song with one started ~9 s in
(FireRed's Route 7). A heard song whose clip predates intros is re-recorded; the background pass isn't redone. The old audio-envelope `LoopDetector` cut battle themes to ~3 s and looped back
to the intro. **The clip follows the FF the player asked for** (`EmulatorEngine.requestedSpeed`), not SMART's
temporary 1x: it plays straight through a menu (game audio muted meanwhile, menu SFX included) instead of
handing over to the game's music and restarting from 0:00 when FF resumed; and a clip that comes back (after a menu, or FF
off for a moment) picks up where the song is by now (`Clip.songStartedAt`). Debug the
render core headless with `native-capture/mgba_dump`'s `call` / `park` / `bgm` / `wav`. The
SETTINGS title also shows the device battery (`BatteryIndicator`, fed by the activity through
`DeviceBattery`).

**AUDIO EFFECTS** (SOUND on both screens, `Prefs.audioEffects` OFF, NEW tag; GitHub #336): low-latency tracks skip the device's audio effects, so JamesDSP never touched the game (and since 1.4.0 not STEADY's clips either). On, `AudioEffectsTrack` builds the game's and the clips' tracks as media with no low latency, on one shared session announced by Android's open / close broadcasts; from the next game start (a track keeps what it was built with). The Thor's own system-wide equalizer applies then too. Not yet tried on a device.
**STATUS BAR** (SETTINGS on either screen, `Prefs.statusBar`, off by default, live-toggled): a
white title-window strip on top of the game, exactly as wide as it (`GameStatusBar`, laid out by
`GameStageLayout`, which shrinks the game to 3:2 under it and moves the HUD down) - the ROM's
Library name, map section, money, clock (system 12/24h) and battery. Money is
SaveBlock1.money ^ SaveBlock2.encryptionKey via `NativeConfig.moneyOff` (FireRed family `0x290`,
Emerald family / R/S `0x490`, Heart and Soul `0x494`, Emerald Rogue `0x4A8` (its party mons are 104
bytes), -1 = unknown); values over 999,999 are dropped.
`MoneyTest` pins every fixture.
**HUD** (SETTINGS > HUD on either screen - the code calls it FieldHud; a page: `fieldHudRows`, shared; `Prefs.fieldHud` ON default, also with a
second screen - the user's call; `Prefs.fieldHudStyle`): a Legends-Arceus-style HUD over the overworld (`companion/ui/FieldHud.kt`) -
party icons with HP bar / status chip (no Lv; the tags get a row of their own over the icons only while someone has one) (a tap opens `MonDetailScreen` over the game), a region-map minimap (a tap opens `MapScreen`
with FLY), the bag's Poké Balls, BAG (a compact grid with USE through `ItemUse`, PP items left to the BAG tab), PC (left of BAG, `ItemUse.openPc`, where the BAG tab has it; a corner ↗ `PixelIcons.inGame` = opens the game's own screen; a "not now" pops up as an `ItemNotice`; the companion window closes on its own once the PC is up) and WILD (`hudWild`
over `GuideRomSource.encounters`, like GUIDE > HERE). LAYOUT CORNERS (default; its minimap top-right - FireRed / Emerald slide a new area's name in top-left) / CORNERS 2 (its right side swapped: minimap bottom-right, balls and buttons top-right, BAG / WILD open from the top) / RAILS (columns beside the middle 3:2) / DOCK, LOOK
GLASS (default, see-through dark) / WINDOW (OPTION windows), every window shaped like the battle HP box (`hudPanel`: its shadow `HUD_SHADOW` down-right; buttons inside panels and the quick menu's tiles `hudButtonFace`, the picked one `HUD_SELECTED` orange - the user's pick of five looks, 2026-11-09), SIZE SMALL / NORMAL / LARGE = the GBA pixel one whole screen pixel down /
up (a `Density` of `k * 16 / 20` under the HUD; its panels stay at the companion's size), then every size drawn 12.5% bigger (`HUD_SCALE`,
`hudScale`: 3 -> 3.375 on the RP6, liked over 3.5; laid out shrunk, drawn scaled from the top left - the user's call, a whole pixel
up was too big, so its pixels aren't whole screen pixels), each part hideable (`FieldHudPart`). A picture too narrow for CORNERS' party row beside its balls + buttons
(`HudFit`, measured in the same layout pass; a docked side panel's 960 px game) gets `CompactLayout` whatever LAYOUT says: no
party or minimap, the balls over the buttons + a MAP button right of WILD, all bottom left and left-aligned (the user's call), and
SETTINGS > HUD > LAYOUT is greyed out saying the screen is too small (`FieldHudFit.tight`, Compose state). DOCK whose bar is wider than
the picture falls back to CORNERS. The
game is **never paused** under its panels (the user's call). Shown only while `EmulatorEngine.onField` (GBA, on the field screen positively - `FfMenuWatch.onField`, the learned field callback2, so a new game's naming screen doesn't pass for field; a Game Boy game: pk_wide_gb.c's scene says FIELD - with EXPAND off the engine keeps it configured with nothing drawn, `gbSceneGame` / `pk_wide_probe`, Yellow and Crystal - no battle /
region map / held frame, and at EXPAND `pkWideScene` FIELD; FADE holds; on after 20 field frames, off at once) and the snapshot has a
game out of battle; off on a phone held upright (to do later). Without EXPAND every layout but RAILS keeps to the game's picture (`EmulatorView.onGameRect` -> `FieldHud(frame)`; RAILS take the room beside it). The ComposeView sits over the game view (`GameStageLayout.overGame`)
above the touch pad (untouched areas fall through) and keeps clear of the pad's rows (`TouchControlsView.clearTop` / `clearBottom`);
An open, unlocked side panel closes on a tap on the game or the HUD and lets the tap through (a HUD item opens right after; `SidePanel.catchOutside`, not with the touch pad up). One screen: while it shows and the side panel is closed, the panel's open tab is a COMPANION button beside BAG / WILD (`SidePanel.closed` / `tabInHud`; the edge tab steps aside), and the side panel's views go above the HUD (`SidePanel(under = fieldHud)`). BACK (or a tap on the dim around a panel) closes its panels first (`hudBack`); the balls open the BAG on POKé BALLS. On GLASS the summary is `MonDetailScreen` under `LocalOptionLook` = `HudGlassLook`: **`OptionLook`** (GbaMenu.kt) is a scoped palette over `OptionColors` (`Live` forwards to it) that the summary screens and `OptionListWindow` read through `optionLook` - capture it before a draw lambda. Title windows' back arrow and the Library's favorite stars are pixel art (`PixelIcons.arrowBack`, `star` / `starOutline`), no Material icons. `FieldHudScreenshotTest` (+ `FieldHudCloseUpScreenshotTest`, 1:1 crops) renders it over a
real EXPAND frame when `app/build/field-hud/{fr,em}.png` exist (mgba_dump `wideon 22` + `wideshot` on the player's own ROM and save,
never committed), else a drawn stand-in.
**QUICK MENU** (`companion/ui/QuickMenu.kt`, `QuickAction` / `QuickMenuStyle` in `companion/QuickMenuSettings.kt`; the QUICK MENU hotkey, L3 by
default - the user's pick, which took L3 from SLOT PREV, migrated only where unchanged; SETTINGS > QUICK MENU on both screens: STYLE and the
actions, `Prefs.quickMenuActions` stored as the ones switched off so a new action shows by itself, NEW tag): PC / FLY / REPEL / BIKE / ROD /
WILD / SAVE STATE / LOAD STATE / FF / COMPANION as tiles over the game in the HUD's view (`QuickMenuState`), the HUD and BATTLE BUTTONS faded
to 0.25 meanwhile and its open panel closed (`FieldHud(closePanels)`, bumped on open): a press opens it for the D-pad / stick + A (B / the button closes); no hold-and-release (tried, dropped - the user's
call: a slip on letting go ran actions nobody meant).
Every tile is the largest one's size (`UniformTiles`, a SubcomposeLayout: measured loose, then all at the max). STYLE RING (default; a tap flips
it - an AUTO that followed a left stick was tried and dropped, the user's call) or GRID (rows evened out, 9 = 5 + 4); RING = the tiles round a circle, first at the top, clockwise, each the same gap from the last (`ringPlaces`: rectangles at equal angles meet corner to corner closer than side to side, so it walks the gap tile by tile and bisects the radius until the loop closes; an oval when too tall), the highlighted one's name (+ why it's dimmed) in the middle; LABELS (`Prefs.quickMenuLabels`, ON) = names under the icons, OFF = icons alone (a bigger icon, more padding) (the user wanted both; round tiles were tried and turned down);
the left stick aims (`QuickMenuState.aim`, angle -> tile; each new tile a CLOCK_TICK haptic, `onAimMove` - the user asked for
it, the one haptic outside the library's long press), the hat still steps. Its keys come from `PokeDaisyActivity.quickMenuTakes` /
`quickMotion` (withheld from the game like the companion window's, `menuHeld`). Offered only where the game can (`QuickActions.offered`),
dimmed with a reason when not now (`quickBlocked`). FLY becomes its destination list in the menu (not a Compose-focus page, so it works with
a controller on both devices); REPEL too when the bag holds more than one kind (`REPEL_KINDS`: id + steps, 100 / 200 / 250), one kind is
used at once, and it's dimmed while one works (the steps left, below). BIKE / ROD use the game's SELECT register (`KeyItems`): the item
into `SaveBlock1.registeredItem` (= `moneyOff` + 6 in every retail layout: 0x296 / 0x496), SELECT pressed 2 frames through
`GbaInput.setPress` (not setScript: BattleInputController rewrites that every frame), the player's own registration put back 20 frames
later - no per-game function table; FIRERED / EMERALD kinds with a retail money offset only (`keyItemsWork`). SoulGold (GitHub #242) has no single register: SELECT opens a wheel of four registered key items
(SaveBlock1 +0x3484: up, right, down, left), so BIKE / ROD write the item into its DOWN slot, press SELECT, then DOWN 8 frames
later, and put the slot back (`KeyItems.selectWheel`); its REPEL uses its own ids 114..116 and its Lure bit (`FieldItemFns.repelItems`
/ `repelLureBit`, `scripts/find_field_items_soulgold.py`). Using a bike while riding
gets off in every Gen 3 game (GetOnOffBike), so BIKE reads GET OFF while `EmulatorEngine.riding` (pkFieldState's AVATAR + BIKE). A stick still tilted when it closes stays away from the game until centred
(`quickStickHold`: on the ring the stick aims, so A came while tilted and the bike rode off - and FireRed drops SELECT at full bike
speed, GetPlayerSpeed() == FASTEST); `pressSelect` presses again only where taking it shows for sure (riding flips, or the field
controls stay locked - getting on / off locks and frees them within a frame, and a blind retry toggled the bike 20 times). WILD
flips WILD ENCOUNTERS to OFF and back to the value before (`Prefs.wildEncountersBeforeOff`), offered where that row works. The game's
config reaches it through `activeNativeConfig` (the Poller's setter). The manual can't name the Repel (docs.test.js bans it): the tile
keeps the game's word, the manual says "the keep-away spray".
**HUD REPEL STEPS** (`FieldHudPart.REPEL`, NEW tag): VAR_REPEL_STEP_COUNT read from the snapshot's `SaveProgress` (`ItemUse.repelVar` =
the field items' `repelVar`: FireRed 0x4020, Emerald / R/S 0x4021, so only games whose bag can USE a Repel) in its own window by the balls
(`RepelSteps`: over them, under them in CORNERS 2, narrow in RAILS, unframed in DOCK's bar; the user picked it from four mock-ups): the
icon, the count (1.25x, gold for the last 20) + "steps", and a bar draining green / yellow / red against the steps the kind started
from (`repelFull`: the smallest of 100 / 200 / 250 covering the first count seen). The game doesn't keep which kind is working, so
it's the plain Repel's icon.
**BATTLE BUTTONS** (`companion/ui/BattleButtons.kt`, SETTINGS on either screen - one screen only; `Prefs.battleButtons` ON, NEW
tag): one screen held sideways, a single battle whose input state has been read (`battleRead`: NONE all battle = no touch input),
FIGHT / POKéMON / RUN at the top of the room right of the GBA's own picture - `EmulatorView.onCoreRect` (EXPAND's middle 3:2 at
the view's height, which `onGameRect` leaves null) - in the HUD's ComposeView. They're the companion's battle buttons
(`PlatinumButton`, a thicker `outline`; FIGHT carries the battler's party icon at 3x), the user's call over the HUD's looks. Hidden
with no room (< 40 GBA px: STRETCH, a docked side panel); 64-76 px wide - at least 64 even past a 16:9 screen's ~22 px margin, into
the picture's top-right corner (only background there in a battle). FIGHT / POKéMON open lists down the right side over the dimmed game,
no window behind them (`BattleLists.kt`, the user's call after a modern game's move list): `BattleMoveList` = a row per move in the move
cards' look (pale card, type-coloured frame) sized in the HUD's GBA pixels - name + PP, type + PWR, the SUPER 2x verdict - and
`BattlePartyList` = FireRed's party list rows drawn from `partyPaletteFor` (ball + icon, name, Lv / status + BEST / OUT, HP bar +
numbers; the cursor = the selected colours), each over a blue CANCEL. `BattleList` draws a list at the largest whole GBA pixel that fits the height under the buttons'
top and 55% of the width (its own `Density`, like the HUD's SIZE; the user asked for more of the screen), and the lists' rows and
FIGHT / POKéMON / RUN run `EDGE` (13) GBA pixels past the screen's right edge - no right border, corners or bevel band: tabs out of
the side (the user's call). Party rows: a Poké Ball the row's height poking out of the slot's left edge, the icon at the same
sprite pixel over it with the game's two poses (ICON_FRAME_TICKS by HP) and the cursor's bob. The buttons slide off right
(`slide`, 260 ms) while a list is open, the game's party / bag is up (PARTY_OPEN / BAG_OPEN) or there's no battle, and the lists
slide in from the right over a fading dim (`movesIn` / `partyIn`; snapped in Paparazzi). The buttons are out while one is open (`BattleButtonsWindow`,
`BattleList`; the side panel's edge tab steps aside meanwhile);
RUN = `selectAction(3)`, wild battles only. `FittedGbaText` narrows a label's letters (3 tall, 3 / 2 / 1.5 / 1 wide) before it
shrinks - measure both ways, layoutlib's measurer ignores scaleX. `BattleButtonsScreenshotTest` (+ 1:1 close-ups, a 20:9 phone).
**BATTLE STYLE** (companion SETTINGS > COMPANION, `Prefs.battleStyle`, GEN 4 default / GEN 5, NEW tag; asked once on the Library,
`BattleStyleIntro`): GEN 5 (`BattleControlsGen5.kt`) is Black / White's battle touch screen redrawn - colours sampled and shapes measured
off headless frames of the player's Black (nds_dump, the Battle Subway's first battle: command menu, moves, party), never their art (its
ball sprites are ours). Lengths are the game's pixels over the tab (`G5`: 256 x 176, placed by width / height, sized by the smaller scale,
every edge stepped in whole screen pixels of one game pixel, `rowsPath`); the lens / field, discs (`DISC`) and plates (`PLATE`) are profile
tables from the frames. Edge to edge: CompanionScreen eases its margin away and docks the tab bar on `Gen5Dock` while the controls are up.
D-pad focus is the game's key cursor (magenta corner brackets). Look at it with `gradle render -Ponly=battle-gen5` beside the game's frames.
**ASPECT** (SETTINGS on either screen, `Prefs.stretchGame`, ORIGINAL default / STRETCH): STRETCH fills
the top screen - `EmulatorView.stretch` drops the quad's letterbox and `GameStageLayout.stretch` gives the
game all the space under the status bar. The bottom screen flips it in place; the top-screen Settings opens
`AspectPicker` (both choices drawn as the top screen in miniature, with the newest savestate thumbnail, else a
drawn stand-in) and the game picks it up on resume (`syncGameScreen`).
**ASPECT > WIDE** (shown as EXPAND since it also extends a portrait phone's game up and down; `GameAspect`, `Prefs.gameAspect` - it replaced the `stretch_game` boolean, which still reads
as STRETCH): the overworld rebuilt past the GBA's edges by `pk_wide.c` every frame (docs/DEVELOPMENT.md's "WIDE"
section has how and why). The ground rule it was built on: **the rebuild of the middle 240x160 must equal the real
frame pixel for pixel** - check any change with `mgba_dump`'s `wideon 22` + `widecheck` + `widestats` on walking
captures of both games, never by eye. Every retail FireRed / LeafGreen / Emerald / Ruby / Sapphire (all languages and revisions; R/S's Subsprite is 8 bytes) and
the hacks in `gen_wide_tables.py`'s HACKS (binary FireRed / Emerald hacks - CFRU's Unbound, Radical Red, ... - mapped like
ports; the gcc builds - expansion's Lazarus, SoulGold, Heart and Soul, ..., and Recharged Yellow - found by
`scripts/find_wide_addrs.py` + `find_wide_objects.py`; `pending` ones are checked but left out until exact):
`pk_wide_games.h` by ROM CRC32, generated by `scripts/gen_wide_tables.py` and checked by `scripts/verify_wide.py`
(rerun both after touching pk_wide.c); RAM after frame N-1 is what frame N shows, and a snapshot can be torn (checked against VRAM tilemaps /
OAM, never trusted alone). ASPECT has three values now, so the companion's row is an `OptionSelector`. Off the map the
strips are **ambient edges** (a glow from the game's own edge pixels), crossfaded with the map strips; look at transition
changes with `widerec` + `scripts/wide_gif.py --sheet` (boot and a scripted wild battle, both games), not by guessing.
Battle strips: `scripts/wide_battle_shots.py` pictures every battle scene (terrains, gym, Elite Four, ...) of every WIDE
game, each shot labelled with which of the strips' ways drew it (docs/DEVELOPMENT.md's WIDE section).
**ZOOM + look-around** (`WideCamera`, `pk_wide_view`): any window around the frame, whole GBA pixels per step
(`levelsFor`), pulled back to 1x on `pk_wide_scene` (TEXT: the field's `CONTROLS_LOCKED` + a text box / line effect;
OVERLAY: a BG0 picture over the field; BATTLE_START: `sTransitionData` set, `PK_WIDE_TRANSITION_PTR`; OFF: a battle /
menu) - one timed ease-in-out per event, started before anything shows (the user's complaint: the old exponential
ease reacted to the wipe already on screen, while the margins dissolved); the right stick moves the view (stays
standing, glides back when walking). docs/DEVELOPMENT.md's WIDE section.
**EXPAND on the Game Boy (2026-10-18, English Yellow, then Crystal)**: `pk_wide_gb.c`, which `pk_wide.c` hands every call to
while a Game Boy game is configured (same CRC lookup; frame 160x144: `pk_wide_frame_w`, `WideCamera.frameW`). The field rebuilt
from the overworld block array + the ROM blockset + VRAM + the renderer's Color palettes, connected maps from the ROM; the screen
found by matching the BG map (or the full-screen window a Gen 1 text box uses) against that world, never from RAM alone;
off-screen NPCs drawn from RAM (Gen 1's wSpriteStateData, Crystal's object structs and map objects; Crystal's connected maps' people from
their ROM object events). Gen 1 shows an NPC only a tile inside the screen, so one within a step of the edge goes over the game's picture too (`GbObj.over`, plain field only, GitHub #385; `widecheck` still compares the picture alone). Crystal (`gen` 2 rows):
colours from the tileset's palette map, D000-DFFF read from WRAM bank 1, its battle transition (`hVBlank` 1) as the battle's
start. Same rule as the GBA: `widecheck` must find the middle exact (frames the game wrote VRAM or a palette in mid-frame aren't
counted: `pk_wide_gb_last_torn`). Battles / menus get plain bars in the screen's background colour by bands of rows (the user's call: white
in Yellow - the trainer card too - no glow; black beside the Pokégear's top part), SAVE's prompt and OPTION keep the map (OPTION in the field's last colours).
docs/DEVELOPMENT.md's "WIDE on the Game Boy".
**EXPAND on Crystal Clear (2026-11-05, GitHub #143)**: all four builds, one `GB_ROMS` row with `GB_CLEAR` set: Crystal's RAM, but a byte of
attributes per tile id from the tileset's own bank, 256 blocks with no wrap, LZ-compressed blocks (a connected map goes through
`gbLz`), 5-byte object sprites with no palette. `widecheck` proved it on a sweep of every map, warped to by poking the game's own
`warp`: docs/DEVELOPMENT.md's "WIDE on the Game Boy".
**EXPAND on Polished Crystal (2026-11-12)**: 3.2.3's normal build only (CRC32 `f98367e4`), Keegan's profile from the libretro core, now
reached the same way by every build: `pk_wide_gb_knows` / `_configure` answer for that CRC with `pcWideConfig`
(`pk_wide_gb_polished_tables.h`, from the release's `.sym` by `scripts/gen_polished_wide.py`) and its LZ (`pk_wide_gb_polished_lz.inc`).
Its battle transition is `hVBlank` 3 (Crystal's 1). docs/DEVELOPMENT.md's "WIDE on the Game Boy".
**Transitions (2026-10-12, FireRed first)**: FireRed's map preview (a BG0 picture over the field on entering a forest /
cave) dims the map around it, frame and margins (`overlayDim`; mGBA's output pixels carry their layer in the top byte,
`fromBg`, so BG0's own pixels are left alone). **AUTO ZOOM IN** (SETTINGS on either screen, under ASPECT, shown only on EXPAND; `Prefs.expandZoomOnText`, ON):
OFF leaves text boxes zoomed out; menu screens (SCENE_MENU: the user's call, a zoomed-out one sat small) and battles always pull in, their POKéMON / BAG screens with
them (gMain.inBattle set = the battle's scene, not SCENE_MENU: they used to zoom out mid-battle); a picture over the field (the
map preview) never moves the camera, and its dim follows the card's alpha blend out (`overlayStrength`). A fade between maps (black / white frame: `fadedFrame`) is SCENE_FADE, which the camera holds through; FireRed's
quest-log replay ("Previously on your quest", `PK_WIDE_QUEST_LOG`) is OFF (1x) until the field is the player's. Other games get no early battle pull-in until their `TRANSITION_PTR` is
added to gen_wide_tables.py's REFERENCES (Emerald's / Ruby's are 0). **Replacing the battle background with the
overworld was tried ten ways and dropped** - the user found none good (see docs/DEVELOPMENT.md's WIDE section); don't
bring it back without a genuinely different idea.
Removed NPCs are drawn where last seen, not at home (`rememberObjects`). Unbound's stutter at map borders is the game's
own lag (frames dropped by its map loading, headless too), not the app's - see docs/DEVELOPMENT.md's WIDE section.
**Battles are wide** (BG3 is 512 px in VRAM): drawn like a wider PPU, with per-scanline registers from a hook on mGBA's
`drawScanline` (where BG3 alone can't go on - a 256 px picture repeated, a broken seam, scenery on BG1 / BG2 - a side mirrors
BG1-3 in from its edge; the text box rows always continue the dark band: GitHub #114); check battle changes with `layers 0x18` + `widecheck all` (BG3 + OBJ only: the middle must match exactly).
The map uses those per-line registers too (windows, scroll, layer enables - Flash caves, transition wedges); weather
sprites are repeated 256 px into the margins. A change to drawing gets the sweep (docs/DEVELOPMENT.md's WIDE section):
warps to caves, gyms, weather routes on both games with BG0 off, every map frame exact.
**SHADERS** (a sub-page of SETTINGS on either screen; `EmulatorView.FrameRenderer`, shaders in
`ScreenShaders.kt`): FILTER (`Prefs.screenFilter`: NONE / LCD / LCD PAPER / SCANLINES / CRT), GRID (`Prefs.gridStrength`: SOFT /
MEDIUM default / STRONG, for LCD and LCD PAPER - the `uGrid` uniform from `ScreenShaders.gridFor`, greyed out on
the other filters) and GBA COLORS
(`Prefs.gbaColors`, mGBA's `gba-color`), which stack. GBA COLORS runs into an FBO at the GBA's own size. A
`prescale` effect (LCD, SCANLINES: mGBA's MIT shaders, hard-edged) draws into an FBO at the largest whole
multiple of 240x160 that fits per axis, then LINEAR-scales onto the view, so its grid stays even at the
Thor's 6.75x; a direct one (CRT, ours: gaussian beams + a 1-view-pixel RGB mask on `gl_FragCoord`; LCD PAPER, simpletex_lcd's
smooth grid, but lines darken their own pixel - its white or a fixed grey washed colours out - over colours 15%
desaturated (its DARKEN_COLOUR looked more vibrant than plain LCD), + procedural paper) is the view pass itself. Every texture keeps the frame's top-first row order (`vUv.y` runs down the game); only
the view pass flips. Shaders get `uTex` / `vUv` / `uTexSize`. None of it shows in Paparazzi / ui-preview:
screenshot the Thor (`adb exec-out screencap -p -d <display id>`), or run the real shader strings in WebGL
(GLSL ES 1.0, same as GLES2) in the browser pane - how the CRT was tuned, on a 240x160 frame at 1620x1080.
SHADERS > UNLIT SCREEN (`Prefs.unlitScreen`, OFF, NEW tag; GitHub #105, the "pixel transparency" look): `ScreenShaders.UNLIT`,
ours (the issue's shaders were only looked at - one has no licence): linear light = backing x the pixel x (the cell up-left it
came in through, 4 smooth taps), so white is the grey-green backing and dark pixels drop a soft shadow. Its own pass after
SMOOTHING, at FILTER's whole multiple (prescaled) else the view's pixels, which the next pass reads as a picture (`uSmoothed`);
the game only, not ON COMPANION. Tuned in the browser harness on headless Emerald battle / FireRed START frames.
SHADERS > SMOOTHING (`Prefs.smoothing`: OFF / xBRZ / xBR / SCALEFX, GitHub #42; SABR, also asked for, notched
edges in the harness) runs libretro's own .glsl files, unmodified in `assets/shaders/`, between GBA COLORS and FILTER:
`EmulatorView.RetroProgram` draws them as RetroArch would (VertexCoord / TexCoord / MVPMatrix, Texture / TextureSize /
InputSize / OutputSize, PassPrevN, `#pragma parameter` defaults), `ScreenShaders.smoothingSource` makes them GLSL ES 3.00
(+ xBR's `delta` globals const). Hence the ES 3 context (`GlCaps.gles3`; ES 2 devices get the row greyed out); ScaleFX's
first two passes draw into RGBA16F. Sizes: xBRZ at the game's on-screen size, xBR at the largest whole multiple (then
smooth), ScaleFX 3x; with LCD / SCANLINES (prescaled) everything goes through the whole multiple. FILTER then draws
over the smoothed picture (`uSmoothed`: LCD PAPER and CRT read it where they are, not at GBA pixel centres). Never on the
companion. Picked in the harness's mini .glslp runner (WebGL2), then checked on the Thor: every mode and xBRZ + CRT / LCD,
live cycling, FF still 300 fps. A build failure or a missing half-float framebuffer logs and turns it off for that context.
SHADERS > ON COMPANION (`Prefs.companionShaders`, default ON) puts FILTER and GBA COLORS over the companion and
the top screen's status bar too, without touching the colour scheme: `CompanionColors` runs one AGSL shader (GBA
COLORS, then the filter) on every tracked view as a `RenderEffect` (Android 13+; older devices keep the plain look;
a shader that fails to compile is logged and skipped). Its cell is the game's own pixel on screen
(`EmulatorView.onGamePixel` -> `CompanionColors.setCell`: 6.75 px on the Thor, rounded to 7 - a non-whole cell made
some lines look thicker), so both screens show the same grid -
the companion's own 3 px GBA pixel was too fine to see on the bottom screen and only darkened it. Fitted to UI text
in the harness on ui-preview renders: LCD / SCANLINES lines a fixed 2 px, SCANLINES at
0.7 (the game's 0.5 cut letters), CRT without its horizontal blend. New companion `ComposeView`s go through `CompanionColors.track`. Checked on the Thor (GBA COLORS
alone matched the formula within 1 per channel; the filters compiled and ran in a live battle).

**RetroAchievements** (`achievements/RetroAchievements.kt` + `RaNative.kt`, native
`app/src/main/cpp/pokedaisy_ra.c`): rcheevos' `rc_client` over the player's core (`pkMainCore()`,
never `rg`), read through RA's GBA map ($0 IWRAM / $8000 EWRAM / $48000 save RAM, via
`getMemoryBlock`; a DS game: melonDS's main RAM at $0, its data TCM at $1000000); the game is loaded by the ROM
*file's* hash (`RomIdentity.raHash`: its MD5, a DS cart's by RA's DS method - retail FireRed rev 1 = game 515, Emerald
= 668, Unbound v2.1.1.1 = 17530, Platinum = 11732, HG / SS = 7212), never the core's ROM: a state load writes the cart's
GPIO registers into it (0xC4-0xC9), so a resumed game hashed to nothing (GitHub #197).
Kotlin does the HTTP (`User-Agent: PokeDaisy/<versionName> (Android <ver>) rcheevos/12.5`); answers
are queued natively and `RetroAchievements.onFrame` (after every `pkRunFrame`) delivers them before
`rc_client_do_frame`, because rc_client reads game memory while finishing a game load - with no core
up the HTTP thread delivers (login only). Signed in = on; Prefs keep the username + token, never the
password. Each savestate gets its progress beside it (`<state>.ra`); the resume state's is put back
once the set loads. **Softcore only**: the server downgrades hardcore from a client it doesn't
recognise (RA validates new emulators; one must be public 6+ months), and hardcore would also have
to block state loads (incl. the resume-on-launch) and the automated battle input ("scripted input"
is banned).
UI: sign-in is top-screen Settings > RETROACHIEVEMENTS (the Presentation can't host a keyboard well); a
token login that can't reach the server keeps the account and retries with backoff (15 s doubling to
5 min). The companion reads it through `CompanionAchievements` (`companion/Achievements.kt`, faked in
Paparazzi / ui-preview): the ACHIEVEMENTS tab (`AchievementsScreen.kt`, chip label CHEEVOS - the full
word truncates; off the bar by default, under MORE; works on unsupported ROMs too) lists
rc_client's PROGRESS grouping, parsed from `raAchievementList` (0x1E records / 0x1F fields, strings
built from real UTF-8 - `NewStringUTF` takes modified UTF-8 and an emoji title would abort under
CheckJNI); `AchievementPopupHost` shows unlocks / mastery / "N OF M UNLOCKED" on load / progress /
leaderboards / offline notices over every tab, top center; `AchievementIndicators` (bottom right of the
tab area) shows running leaderboard trackers' values and active challenges' badges, from rc_client's
TRACKER_* / CHALLENGE_INDICATOR_* events. The tab's LEADERBOARDS button (only when the set has boards,
`raLeaderboardList`) lists them by set; a board's page (`openLeaderboard`) fetches the top 10 and 5
around the player (`raFetchLeaderboard`, answered through `onLeaderboardEntries`, keyed by a token so a
late answer for another board is dropped) and marks the player's row as the white cursor row. Badges are fetched once into
`cacheDir/ra-badges` (`AchievementBadges.dir`; null in previews = a pixel trophy). rc_client adds a
"Warning: Unknown Emulator" placeholder (id >= 101000001) while the server doesn't recognise the
client; `raAchievementList` drops it, as rc_client's own summary does. Debug builds log
`raDebugStatus` every ~600 frames (rich presence, short memory reads, achievements by state) - the
first thing to read when an achievement doesn't fire (`adb logcat -s pokedaisy/ra`).
An unlock plays the game's own level-up fanfare (MUS_LEVEL_UP: song 257 in FireRed's table - also
LeafGreen's and the CFRU hacks' - 367 in Emerald's and Ruby/Sapphire's, picked by the header's game
code; other games borrow another ROM's), rendered per ROM by `FfMusicRenderer.recordSfx` like the click
into `sfx/<crc>-fanfare.wav` (`GameClickSound(name = FANFARE)`, ~1 s once trimmed). The Library's INFO
has a RETROACHIEVEMENTS section: the ROM's MD5 looked up with the public `r=gameid` request, and the
player's unlocks from `rc_client_begin_fetch_all_user_progress` for all GBA games (cached a minute).
A leaderboard with no entries can be a counter the set only shows as a tracker: its definition never
submits (`SUB:1=2`) - FireRed's three bonus-set boards are all like that.

**Button clicks, no haptics**: companion buttons play the game's own menu click (SE_SELECT = song 5
in every Gen 3 song table), rendered once per ROM by `FfMusicRenderer` (after MUS_DUMMY silences the
title music) into `filesDir/sfx/<crc>-select.wav` and played by `GameClickSound` (SoundPool); a ROM
the renderer can't drive borrows the newest other game's click. In Compose, use
`Modifier.soundClickable` (`companion/ui/ClickSound.kt`, `LocalClickSound`, provided by
`CompanionScreen(clickSound = …)`) instead of `clickable` - except tap-swallowing scrims. Buttons that press the
game's own (battle FIGHT / BAG / moves / BACK / the POKéMON picker's slots: `PlatinumButton(pressesGame = true)`,
`slotClickable(sound = false)`) leave the sound to the game's select sound - one click a tap, the user's call - and click
themselves only while the game can't be heard (`BattleInput.gameSilent` = `EmulatorEngine.gameAudioPaused`: FF with STEADY
music, slow-mo, rewind, a held frame). The top-screen Library / Settings stay silent.

**SWAP SCREENS** (SETTINGS on either screen, SCREEN group, only with a second screen; `Prefs.swapScreens`): the game
on the second display and the companion on the main one, for a device whose main display is its bottom screen
(the Anbernic RG DS, by a user's report - no device to check it on yet, and no automatic rule until someone posts
its `dumpsys display`). The activity never moves: the two windows trade *contents* (`PokeDaisyActivity.syncPresentation`)
- the game's stage (`GameStageLayout`: game view, status bar, touch pad, HUD, side panel) goes into the Presentation
(`DualScreenPresentation` takes any content now) and a companion becomes the activity's content. A Presentation
can't go on the default display (WindowManager refuses TYPE_PRESENTATION there), which is why it's not the other way
round. The GLSurfaceView makes a new GL thread each time it's attached again, and a detached one's `queueEvent`
goes to the old, exited thread - so `EmulatorView` hands the frame buffer over under a lock, not through the queue.
The stage keeps its own ViewTree owners (set on itself), the Presentation's are on a frame around its content.
`Screens` finds the second screen (ui-preview shims it as the Thor's).
Every Compose view on the stage runs on `stageRecomposer` (the activity's own, set as the stage's `compositionContext`):
a ComposeView keeps the coroutine scope of the first window recomposer it met, the stage changes windows on every
HOME / sleep / lid close while swapped, and once that first one was cancelled every tap handler on the stage (HUD, touch
controls, BATTLE BUTTONS) started dead - the views swallowed touches and nothing happened. Never let a view that moves
between windows fall back to the window's recomposer. An app left on the second screen (Chrome on the Thor's bottom one, the user's report 2026-11-05) has the say over that screen's bars: `hide` from a Presentation, which can't take focus, does nothing, and the white gesture handle sits on the companion. The Presentation watches its insets (`watchBars`, nav bar still visible after 1.5 s = no swipe) and `SecondScreenHolder.coverApp` puts its window over the app, hiding the bars; `uncoverApp` in `onPause`. With the launcher there the bar reads hidden, so nothing opens. CLOSE COMPANION works swapped too: the activity itself moves to the
second display (`Screens.moveToDisplay`, the game its content, the main screen Android's; Android 10+ keeps both screens'
activities resumed); meanwhile the companion opens over the game there as on one screen (`companionOverGame`:
`syncSingleScreen(true)`, the side panel's tab / window, BACK opens it) and its SETTINGS shows OPEN COMPANION
(`CompanionSettings.companionClosed` / `openCompanion`), which moves the activity back. The new intent's pause / resume
around a move is skipped (`movePause`), so the game doesn't reload. The Thor's input dispatcher can drop the window's focus on a move (it
asks while the window's input info still names the old display, then never again: no buttons reached the game after OPEN
COMPANION), so `followDisplay` checks 600 ms later and flips FLAG_NOT_FOCUSABLE off and on to make it ask again (`refocus`). SETTINGS > COMPANION BUTTON (`Prefs.companionTab`, NEW
tag) hides that white edge tab (`SidePanel.openTabShown`); BACK and the HUD's COMPANION still open the companion. Over the
game there the companion fills the screen (`SidePanel.setFullScreen` -> `SidePanelHost(fullScreen)`: window keys, no frame),
whatever COMPANION VIEW says. The Thor's own controller focus mode decides where its pad goes: locked to the top screen
(`screen_focus_lock`), touching the bottom moves Android's focus but not the pad; the user's fix was its Auto-Lock mode.
**Before the player is in the game** (`GameStart.waiting`, CompanionScreen's WAITING pane: the game's empty party slots +
PRESS START): PARTY / BAG / MAP / CARD wait on a boot from the title (RESTART, a fresh launch) until `onField`, a battle or
`PLAY_POSITIONS` places walked (Too Many Types 2 fills its position in on the title); a resume from a state counts as
playing at once (`waitForPlay` / `nowPlaying` in PokeDaisyActivity). Android drops the first touch on the second screen after the game opens (an input reconfiguration
cancels it, system-side).
**SINGLE SCREEN** (SETTINGS > SCREEN on both screens, only with a second screen; `Prefs.singleScreen`, OFF, NEW tag; a player's
request to multitask without closing the app): `PokeDaisyActivity.secondScreen()` returns null while it's on, so `syncPresentation`
takes the one-screen path below (window / side panel, HUD, BATTLE BUTTONS, the user's rotation) and the second display is Android's.
`CompanionSettings.usesSecondScreen` (= hasSecondScreen && !singleScreen) gates SWAP SCREENS / the one-screen rows; CLOSE COMPANION
goes too. Flipped from the companion, the window opens at once (it would otherwise just vanish from the bottom screen).
**Single-screen devices** (`SidePanel.kt`, Compose pieces in `companion/ui/SidePanel.kt`; tested on the
Retroid Pocket 6, 1920x1080): with no second display (`syncPresentation` finds none, debug mirror off) and held
sideways, SETTINGS > COMPANION VIEW (`Prefs.companionWindow`, both screens, NEW tag) picks the **window** (default) or
the side panel. The window (`SidePanelHost(window = true)`): the companion framed in the middle at 88% of the height,
at least the Thor bottom screen's shape and wider on a wider screen (`companionWindowSize`: 0.78 of the screen's aspect,
up to 1.4:1 - the user asked for a wider one on the RP6), over a dim of the game, which keeps running (the user's call:
no pause); an X at its top right (beside it, else on its corner), small L / R keys at the tab bar's ends
(`LocalShoulderHints` -> `ShoulderHint`; the user's call over
a button legend), no lock tab. While it's open the game's buttons are the companion's: `PokeDaisyActivity.menuKeyTakes` maps each
key through the player's bindings (`GbaInput.buttonFor`) to a `MenuKey`; B = `onBack`, START closes, the rest go to
`CompanionKeys` (newest handler first, like `CompanionBack`; `CompanionScreen` steps tabs on L / R). Keys pressed in
it are withheld from the game until let go (`menuHeld`), held keys are released on open (`GbaInput.releaseAll`), hotkeys
only hear releases. **D-pad / A = Compose focus, as on the top screen**: what no handler takes, `SidePanelHost` passes to
`SidePanel.focusKey`, which sends the D-pad's own key event (A = DPAD_CENTER) to the panel's view after
`requestFocusFromTouch` (out of touch mode, also on open: else Android eats the first press to leave it). With nothing
focused, `FocusStarts` puts it on the newest start first (`Modifier.focusStart`: GroupedRows' / OptionRows' cursor row;
`OptionOverlay` registers its current choice), so SETTINGS' cursor follows focus (`onFocused`) and only one row is white
(`OptionSelector` too). Pop-ups trap focus and give it back via the `OverlayStack` the window provides. Anything clickable
is a stop, so give a clickable a focused look (`gbaFocusRing`: tab chips, `PlatinumButton`, `OptionButton`; rows turn
white) or `focusProperties { canFocus = false }` (the title window's back arrow, the window's X, the L / R keys). Checked on the
API 32 emulator in landscape (`adb shell input keyevent KEYCODE_DPAD_DOWN / BUTTON_A / BUTTON_L1`; `uiautomator dump`
finds the focused node; game keys need `sendevent` holds on `qwerty2`, `input keyevent` is shorter than a frame).
PARTY: `PartyGrid` moves its selected slot (the game's own cursor art) to the focused one and starts on it (slots have
no ripple: its focus layer greyed the art). ITEMS: a focused row selects its item (the bag's ▶ + description), A on the
selected row sends focus to USE / GIVE (`actionFocus`; a tap still closes it), the first row is the start. In the window BATTLE
is INFO / SUGGESTIONS only (the user's call: `canUseControls` is false under `LocalCompanionWindow`; the player uses the
game's own menu); `FoeSlot`, folder tabs, SORT have rings. A battle pick (from the HUD's BATTLE BUTTONS) or FLY closes the
window (`PokeDaisyActivity.closeCompanionWindow`, in `battleInput` / `flyAction`). DEX rows turn white on focus, starting on
`DexUiState.lastOpened`. The HUD keeps its COMPANION button while the window is up (`SidePanel.windowMode`).
MAP: the map itself is a stop (the tab's start, `RegionMap` ringed): the D-pad moves its tile cursor (`cursorTile`, through
`onTap`, so the label names each place), and off its edge on the buttons' side (top / right; bottom-row maps bottom / left)
`focusProperties` send it to them, and they send LEFT / DOWN (UP on a bottom row) back - inside the map's own box neither finds the
other. GUIDE: the page cursor follows focus (OptionRows `onFocused`), entries are white on focus and every one is a stop (facts too:
else the D-pad can't scroll HERE). STATES: the thumbnail isn't a stop (LOAD is), SAVE of the current slot is the start. CARD: one
ringed stop, never disabled mid-flip (a disabled clickable drops the focus). Rows with nothing to tap that the D-pad should walk
use `Modifier.focusRow` (ACHIEVEMENTS, leaderboard entries); pages with nothing to tap that scroll use `Modifier.dpadScroll` (DEX INFO
/ AREA / MOVES, the summary's matchups): one stop, UP / DOWN scroll, focus moves on at the ends, no stop while it fits, its ring just
outside the edge (inside, it covered the rows' first letters).
The window (not the side panel: "way too small" there, the user's call) gets `LocalTextStep` -1 on a screen >= 2.5" tall (`COMPACT_TEXT_INCHES`, the short side by xdpi /
ydpi - the user's call, by height: the RP6 and the 4.5" RP Nova are both ~2.7"): text over 1x a font pixel smaller, never
below 1x, GBA pixels (paddings) unchanged; `WindowRp6ScreenshotTest` renders it at the RP6's 369 dpi.
Otherwise the side panel: a panel on the right of the game's screen. A BACK tap slides it in over the game; with it open
BACK is the companion's back, and with nothing left to go back from it closes the panel - unless it's locked.
The tab on its edge (`SidePanelHandle`: a padlock, `ShadowedPixelIcon`) locks it beside the game (the game's right margin,
which `GameStageLayout` honours; the touch pad moves to the game's side whenever the panel is open) and
unlocks it; two more tabs for touch (`SidePanelOpenTab` on the screen's right edge, bottom, while closed;
`SidePanelCloseTab` outside the panel's bottom-left corner, which closes it even locked) go see-through
(`LOCKED_TAB_ALPHA`) while locked; dragging the lock tab sideways resizes it (`DragFrame`, screen coordinates - the tab moves with the
finger), snapping when the game is within 48 px of a whole-number scale. Default width half the screen: the
game at exactly 4x on 1080p; also checked at 3:2 (810 px panel, all fits) and 4:3 (640 px, a few names
truncate). The panel draws the companion at `sidePanelDensity` - the Thor's own 2.625-equivalent where it's
big enough, else enough to give it 500 dp of width (half a 1080p screen: ~1.9, where the pixel font steps
down to 2 screen px per font px and nothing truncates). ui-preview: `-PcompanionW=960 -PcompanionH=1080`.
Prefs: `sidePanelDocked` (a locked panel comes back with the game), `sidePanelWidth`. The game is a
SurfaceView: the window's hole over it is measured from where views were at the last layout, so the slide
re-requests the transparent region every frame (`punchThrough`) - without it the panel and tab stayed
hidden over the game.
**Phones upright** (`PortraitPanel.kt`, Compose pieces + the sizing math `PortraitLayout` in
`companion/ui/PortraitPanel.kt`): one screen held portrait puts the game across the top (`GameStageLayout.topAligned`,
the game's own shape whatever ASPECT says) and the companion docked along the bottom, always open, at
`sidePanelDensity`. Its height is `Prefs.portraitCompanionRatio` (height / width, default the Thor bottom
screen's 1080/1240, so it keeps its shape across phones; from `MIN_RATIO` 0.6 to everything under the game):
drag the grip on its top edge (`PortraitGrip`, the shared `DragFrame`, snaps to the default / largest) or tap
it to step small / default / full. The touch pad takes the gap between them when it fits (`padInGap`), else
lies over the game. `PokeDaisyActivity.syncSingleScreen` picks SidePanel or PortraitPanel by orientation and
again on rotation (`onConfigurationChanged`); the manifest no longer locks landscape - `syncOrientation`
locks it with a second screen (or the debug mirror) and follows the user's rotation (`SCREEN_ORIENTATION_USER`)
without one. Paparazzi: `PortraitScreenshotTest` (Pixel 6, 1080x2400); ui-preview's `*-phone` shots are the
Library at that size.
**FOLD LAYOUT** (SCREEN group on both screens, `Prefs.foldLayout` ON, shown once the device has reported a hinge -
`Prefs.foldSeen`; NEW tag; 2026-11-02, players' requests): Jetpack WindowManager's FoldingFeature (`androidx.window` +
`window-java`'s callback adapter: PokeDaisyActivity is a plain Activity) -> `FoldPosture` (companion/FoldPosture.kt, pure,
`FoldPostureTest`: half open or a separating hinge, in the stage's coordinates, not within 25% of an edge). TABLETOP (hinge
across) = `PortraitPanel.hinge`: the game and touch controls above the hinge, the companion (a DS game's touch screen) below,
no grip, whichever way the phone is held - the user's call: companion on the bottom, like the Thor; BOOK (hinge down) =
`SidePanel.book`: the right page locked from the hinge, no tabs, BACK only the companion's. `syncSingleScreen` picks them;
`foldRelayout` re-places the hinge when the stage resizes. Not tried on a foldable or its emulator yet.
**TOUCH CONTROLS** (was TOUCH PAD; SETTINGS > CONTROLS on both screens, a page with a preview; `TouchControlsStyle` in
`companion/TouchControlsSettings.kt`, `Prefs.touchControlsStyle`, NEW tag): one geometry for the game, the preview and the
editor (`companion/ui/TouchGeometry.kt`: sizes in dp, presets SIDES default / CLASSIC / COMPACT, SIDES falling back to
CLASSIC where the bars beside a 3:2 game are too narrow - 16:9, upright; the player's CUSTOM layout per orientation as
edge-anchored `TouchPlace`s), drawn in Compose in the HUD's GLASS pixel look (`companion/ui/TouchControls.kt`; the game's
`TouchControlsView` only hosts it). EDIT LAYOUT runs over the game (`PokeDaisyActivity.openTouchEditor`, the real stage:
`measuredTouchStage`) or, in the top-screen Settings, over a stand-in built by `simulateTouchStage`. Extras (FF tap / hold,
QUICK MENU, TURBO A / B through `GbaInput.setTouchBits(gba, turbo)`, SAVE / LOAD STATE) go through the hotkeys' handler.
`TouchGeometryTest` checks every preset x size x screen for overlaps and off-area buttons: rerun it after touching the
presets. AUTO counts only a gamepad source with an A button as a controller (`isGamepad`): some phones list a built-in
joystick-source device, which hid the controls. Upright, POSITION (TOP / MIDDLE / BOTTOM, presets only) moves the
whole block within its room (`shiftTo`), and the page repeats DISPLAY OPTIONS' COMPANION (AT BOTTOM / UNDER GAME); the
upright preview shows only the screen's part around the controls. EXPAND upright fills everything above the companion, so
there UNDER GAME = the controls' own strip along the bottom (`PortraitLayout.arrange(padStrip)`, `TouchGeometry.naturalHeight`,
set by `syncTouchControls`), and COMPANION / POSITION grey out with a reason where they'd do nothing (`uprightCompanionRow`). ui-preview `touch-*` / `settings-touch*` shots.
LOOK (GLASS / SOLID / OUTLINE / CONSOLE) and COLORS (`TouchColors`, ARGB + optional A / B / X / Y accents) are drawn through
`PadPaint` (TouchControls.kt); MIRRORED / RAISED are transforms of SIDES / CLASSIC (`TouchGeometry.mirrored` / `raised`). **SKINS**
(NEW tag `TOUCH_SKINS`): Delta's `.deltaskin` (zip: info.json + PDF / PNG) read by our own parser (`companion/DeltaSkin.kt`: the
representation closest in shape, `SkinFit`: sideways the whole picture with the game moved into its screen through
`GameStageLayout.skinGame`; upright only the part under its screen, from its topmost button, in the controls' area), imported on the
top screen only (`DeltaSkinStore`, files/skins/<identifier>/: entry names checked, size / count capped), pictures decoded by
`SkinImages.decode` (the app's `SkinBitmaps`: PdfRenderer / BitmapFactory; ui-preview's PNG-only `DemoSkin`). GBA and GBC skins
only (a GBC skin for Game Boy games); DS skins are refused for now. **No third-party skin is ever bundled, linked or shown in our
shots** (the overlays' rule): ui-preview's demo skin is drawn in code. `DeltaSkinTest`.
**External controllers** (8BitDo, GameSir, ...): `configChanges` includes `keyboard` (many controller modes
register as a keyboard, and connecting one recreated the activity mid-game); diagonal D-pad keycodes press
both bits; joystick motion is taken in `dispatchGenericMotionEvent` before a focused ComposeView in the same
window sees it; a real button used for the game hides the AUTO touch pad even when the device doesn't report
itself as a gamepad (`physicalPadUsed`, reset when a device goes); an unbound BUTTON_MODE (HOME / guide) is a
BACK tap (`companionBackTap`); connect / disconnect show a HUD notice. Not tried on real controllers yet.
Analog L2 / R2 (Retroid's triggers send no keys) are the BUTTON_L2 / R2 keys past half way (`triggerKey`): hotkeys first
(their switch and bindings), then the game's controls, and the top-screen rebind capture takes them too. They used to be
hard-wired to FF / slow-mo holds, which ignored HOTKEYS OFF and any rebinding (issue #54).
**Device BACK**: a BACK tap (from either screen - the Presentation forwards every key to
`PokeDaisyActivity`) is the companion's back; a hold still leaves the game. Anything that
opens over / inside a tab registers `CompanionBackHandler` (`companion/ui/CompanionBack.kt`,
newest wins, like androidx's `BackHandler`, which the Presentation / Paparazzi / ui-preview
can't host). `OptionOverlay`, `SummaryFrame` and `OptionTitleWindow(onBack)` already do, so
selectors, confirms and summary panes get it for free; a new sub-page or overlay built another
way needs its own. ui-preview's `*-back-*` shots press it (`-Ponly=back`).

**Emerald Rogue v2.2.1-EX** (`NATIVE_EMERALD_ROGUE`): source is public (Pokabbie/pokeemerald-rogue,
`expansion` branch = v2.2.1, cloned into `$DECOMPS/pokerogue`) for struct layouts; addresses come from headless
captures of the user's save (pulled from the Thor's SD card: `/storage/XXXX-XXXX/RetroArch/saves/mGBA/`) and
the ROM. 104-byte party mons (`NativeConfig.monStride`); one sorted 450-slot bag behind 9 `gBagPockets` views;
SaveBlock2 key `+0x4C`; the hub (mapsec 0) is named by `SaveBlock2.pokemonHubName` (`hubNameOff` ->
`Telemetry.mapSecName`); icons from `gSpeciesInfo` / `gItemIconTable` plus `gRogueItems` for its own items
(`IconTables.extraItemIconTable`); item names and Rogue's mapsec table + grid on Emerald's Hoenn
map from `scripts/gen_rogue_tables.py`, which also writes Rogue's moves (Mainline table - its Revised mode's
isn't read), species types and Gen 6+ type chart (vanilla type ids + Fairy 18). Battle globals came from live
`dumpFixture` dumps mid-battle on the Thor (`emerald_rogue_battle` fixture): battle_main.c's EWRAM_DATA keep
their declaration order, and `BattlePokemon` (0x60) / `BattleStruct.monToSwitchIntoId` (0x3C) offsets were
confirmed by compiling Rogue's headers with host `clang --target=arm-none-eabi` (stub `string.h`, empty
`generated/*`). Touch battle input stays off.

**Glazed / Emerald Imperium / Quetzal (2026-10-09)**: three more BPEE hacks, found headless from the user's saves
(`glazed*`, `imperium*`, `quetzal*` fixtures). **Glazed 9.2.0** is an in-place binary edit of retail Emerald: its RAM,
code and table addresses are retail's (compare literal pools word for word with retail - only the type chart moved),
its names sit in retail's 412 / 355 / 377 slots (`gen_glazed_tables.py`), FAIRY is type 9, its own Tunod / Johto
region map is written over retail's blobs (`GZ_REGION_*`, a 64x32 tilemap). **Imperium v1.3.1** is expansion 1.10.0
(`gen_expansion_tables.py imperium`, `IMP_REGION_*`): 1.10 also packs experience into 21 bits (`Mon.masked`). **Quetzal**
is its own engine: a 104-byte plaintext struct (`QUETZAL_PARTY_MON`: 10-bit HP at +0x23, egg = IV bit 30), its own
SaveBlock1 layout and a "BAG3" bit-stream bag (`Bag3Layout`) instead of gBagPockets (`gen_quetzal_tables.py`). Its wild
Pokémon walk the grass (no random encounters): script a battle (setwildbattle takes a second mon's fields;
ScriptContext_SetupScript 0x080DB9CC). Its Town Map is FireRed's Kanto / Sevii art, pixel for pixel, redrawn for Emerald's
region_map.c (8bpp affine, `QTZ_*`) 3 tiles left / 2 up of FireRed's screen, and its mapsec ids are FireRed's: FireRed's
rects and grids, shifted (`mapSecDataQuetzalOnMap`). Its Hoenn is Emerald's map byte for byte and its Johto its own (`QTZ_JOHTO_*`, Emerald's palette; the game loads them at 0x0818A7xx): the ROM's boxes +1, +2 (gRegionMapEntries is `{x, y, w, h, names**}` from 0x09229554), images 4 and 5, a tap goes by the boxes (no cursor grid read). Glazed's and Quetzal's party menus are Emerald's slots on TMT2's olive stripes.
DEX: Glazed is `POKEDEX_EMERALD` (retail's tables rewritten in place, its own 1..386); Imperium's is gSpeciesInfo-based;
Quetzal's needed new shapes (`entryCategoryPtr`, `BaseStatsLayout`, `DexFlags.seenLevels` / `caughtRegions`). CARD:
`CardStyle.GLAZED` (its badges / pics / font, 7-digit money, `NativeConfig.maxMoney` 9,999,999) and `.IMPERIUM` (its font,
three relabelled lines); the card's "all Hoenn caught" star reads each game's own regional list. Quetzal has no card on its
menus (Emerald's card code survives, reachable only from the cable club) and would need an outfit-based player pic, flag
banks and per-region badges: no CARD tab there. TMT2's battle block is its own order (PP at +0x26, `TMT2_BATTLE_MON`);
Amethyst's is rev 0's plus rev 1's three foe fields, its own target-select handler (`*_battle` fixtures).
R.O.W.E. v2.x (BelialClover's own pokeemerald fork, no expansion): a bit-packed 0x4C-byte plaintext struct Pokemon with no
IVs (`ROWE_PARTY_MON` / `decodeRoweMon`; nature stored, not PID % 25), vanilla-shaped grown tables (`gen_rowe_tables.py`;
u16 base stats: `BaseStatsLayout.statsU16`), three region maps picked by gMapHeader.region (`RW_*`), a 10-pocket bag; the
ROM carries its own symbol table (0x08FB0670) naming most globals. Its system flags were renumbered (none of 0x800+ set on
the save), so no CARD or GUIDE yet.
Rogue's DEX: a 2-bit state per *species id* (`DexFlags.bySpecies` / `seenOrCaught`: pokedexBitFlags1 bit 0, ...2 bit 1),
its own species names (`SpeciesNamesRogue`: ids are national only up to 905). Heart and Soul's map: its Pokégear's
Johto + Kanto map (`HNS_REGION_*`, smol; the JK entries + grid, MAPSEC_NONE 0x7D) - FLAG 0x8FF picks it in game.
Guides for the three (live pages only, no area data): Glazed's Tunod gyms; Imperium's (0x78-byte trainers, a 24-byte
`WildLayout`, random Elite Four teams as variants); Quetzal's three regions as separate campaigns (`GameGuide.bossesFor`
by map group, NORMAL / HARD variants, Johto / Kanto trainers in `altTrainers`, `GuideTables.flagBanks` / `varBanks` for
its 0x1000+ flags and 0x5000+ vars). Quetzal's Johto maps (groups 34-35) name their sections from a
table of their own (0x0922A7E8): `NativeConfig.altMapSecGroups` reads them as 0x100 + id, where the generator puts them.
**Emerald Kaizo v2.0 (2026-10-11)**: retail Emerald edited in place (16 MB, 95% byte for byte): retail's code, RAM and
save, so `NATIVE_EMERALD_KAIZO` is `NATIVE_EMERALD_RETAIL` + its moves and its wild table. It replaced 29 moves and
rebalanced 217 (names, types and powers from its gBattleMoves) - a `GameText` (`KAIZO_TEXT`, `gen_emerald_lang_tables.py
kaizo` -> `EmeraldTextKaizoGen.kt`), so its names show as the ROM has them; gWildMonHeaders moved to 0x08E3D4A4
(`GUIDE_TABLES_EMERALD_KAIZO`); trainers, base stats and TM compatibility are rewritten in place (read live). Detected by
the small-BPEE hash check. The user has no Kaizo save: the `emerald_kaizo*` fixtures are retail Emerald's save booted in
it. The GUIDE's hand-written pages are retail Emerald's; NEXT BOSS and HERE read Kaizo's own tables.
**Elite Redux 2.65.3b / 2.65.4.1b (2026-10-16)** (`GameKind.ELITE_REDUX`, `NATIVE_ELITE_REDUX_*`): its own pokeemerald rebuild
(gcc), 32 MB BPEE; its source is public but untagged, so every address came from the ROMs and headless dumps of its one save
(`~/Downloads/Game ROMs & Emulation/rom saves from discord/saves/`). It breaks several engine assumptions, each a switch now:
a packed plaintext 0x4C-byte party mon (`ELITE_REDUX_PARTY_MON`: no IVs - all 31, `speedDown` = Speed 0 -, the nature stored);
a 0x68-byte BattlePokemon with species at +0x14 (`BattleMonLayout.species` / `stats` / `statStages` are fields now); the bag one
owned bit per item, no counts (`NativeConfig.itemFlagsBag`, `bagHasCounts`: the ITEMS tab drops "× N"); money plain (it zeroes
the encryption key); a battle menu with no BAG (`battleHasBag`) whose cursor values are FIGHT 0 / POKéMON 1 / RUN 2 / INFO 3 and
whose moves are a column (`BattleInputController.MenuCells.ELITE_REDUX`, picked by the activity for this kind); species ids to
2901 (BestEffort's MAX_SPECIES 4095); dex flags by dex number in SaveBlock1, ability names behind pointers
(`PokedexTables.abilityNamePtr`), categories behind pointers up to 20 chars, trainer names behind pointers
(`GuideTables.trainerNamePtr`). Its gym leaders have several teams levelled to the level cap: no NEXT BOSS, HERE only. Emerald's
party slots (names in its mixed case), backdrop, bag and Hoenn map (RomArt finds them in its ROM). The builds differ: EWRAM past
the battle globals +8 in 2.65.4.1b, every ROM table moved (`eliteRedux265_3`: 2.65.3b's few name / type / move differences).
EXPAND / field reads / WILD ENCOUNTERS (2026-10-16): its own WIDE rows on the EX family (R.O.W.E.'s 12-bit metatiles / 2048
primaries, object gfx ids 271+ from vars, no sprite palette tag list: SPRITE_PAL_TAGS 0 like Rogue), exact on both builds; its
wild switch 0x0203D214 / 0x0203D21C (2.65.4.1b reads the player's coordinates before the header lookup: gccRead's call window is
11 lines now). Its R button is an auto run of its own. Trainer battles: gBattleTypeFlags' TRAINER is vanilla's 8, gEnemyParty the same packed mons (`elite_redux_trainer*` fixtures); a single
battle never opens the target screen (no move targets user-or-selected), so its handler was read in a forced double battle.
No field items, FLY or card; its START menu has its own PC.
**Team Rocket Edition: Dragonsden Version 1.5 (2026-10-21)** (`GameKind.TEAM_ROCKET`, `NATIVE_TEAM_ROCKET`; not FireRed Rocket
Edition): DragonsdenTV's closed BPEE build (32 MB, gcc) on an older pokeemerald-expansion - vanilla struct shapes with expansion's
ids (National Dex species to 1395 = SPECIES_EGG, 755 moves, expansion's items in vanilla's 44-byte gItems, 36-byte base stats).
Mapped from its one save (`~/Downloads/Game ROMs & Emulation/rom saves from discord/mGBA Romhack Saves/Pokemon - Team Rocket
Edition.srm`) and the ROM; the version is on its START menu. Gotchas: the party mon is vanilla's encrypted 100 bytes but
language + flags share +0x12 (hasSpecies bit 4, isEgg bit 5) and the OT name starts at +0x13, so expansion's Mint XOR must stay off
(`PartyMonLayout.natureModifier`); BattlePokemon is 0x5C (a third type at +0x24); monToSwitchIntoId is gBattleStruct + 0x62; its
setwildbattle takes a second mon's fields (ScriptContext_SetupScript 0x080CEDC5); its Kanto / Johto towns are Hoenn maps under
Kanto names (the save's "Rustboro" is CELADON CITY, mapsec 0x5E); gItems has a few slots whose itemId is another's (names go by
slot). Its party menu is Emerald's slots on FireRed's teal backdrop (`buildGameBackground`). No DEX (a regional order of its own:
the summary shows CROBAT as No. 067), region map, EXPAND, field reads or field items yet; GUIDE is HERE only.
**Team Rocket Edition 2026, Spanish v3.0.9 (2026-11-01, aelias22's PR #352)** (`NATIVE_TEAM_ROCKET_V3`, `teamRocketV3` under
`GameKind.TEAM_ROCKET`, set by the Poller like `eliteRedux265_3`): a rebuild on expansion 1.14.4 with none of v1.5's addresses, so
its own config (`[config.team_rocket_v3]`) and tables (`gen_expansion_tables.py team_rocket_v3`, Spanish charmap). Its bag's eight
pockets show as eight tabs (`activePocketOrder`, `TEAM_ROCKET_V3_BAG_POCKET_ORDER`, `POCKET_MEGA_STONES`); DEX from its gSpeciesInfo
(1-1062); seven region maps (RomArt's `TR3_*`, a section's map picked by its id range, `region_maps` in the generator). No EXPAND,
field reads or field items. v3.1 (2026-11-12, `NATIVE_TEAM_ROCKET_V3_1`) is the same RAM and tables, its ROM moved (`gen_expansion_tables.py team_rocket_v3_1` checks the tables
equal, `same_as`).
**Wishes of Tomorrow 1.2.6 (2026-10-22)** (`GameKind.WISHES`, `NATIVE_WISHES`): FlatFoot Labs' closed BPEE build (32 MB) on
pokeemerald-expansion 1.15.3 (its RHHEXP block). No save existed: two new games played headless (randomizer off / on), party,
bag and dex flags given through its own script commands (`callnative ScrCmd_createmon` 0x0A200DD5; FLAG_SYS_POKEMON_GET 0x880:
896 trainer flags), saved in game next to RetroArch's. RAM by literal-pool reference counts against a build of the expansion tag
(`nm`; its globals sit a constant few bytes off the reference's), each then read live. Its BattlePokemon is 0x88, its struct
Trainer 0x38 (gTrainers[3][896], EASY empty); a Mega Evolution is its own species id (TYRANITAR 926). Its first-A-is-swallowed
main menu needs two A presses headless. Its **randomizer** swaps species at run time (wild and trainers, never the ROM's tables)
by a hash of species and SaveBlock2's trainer ID (`WishesRandomizer`, checked against the game's own function); the GUIDE's wild
lists and the DEX's AREA follow it through `GuideRandomizer` (`GuideTables.trainerIdVar`). DEX: expansion's Hoenn list (u32
entries) + National. MAP: its JOKAIM TOWN MAP, Emerald's kind of 8bpp affine screen with smol art (RomArt's `WSH_REGION_*`,
the first of six regions in its map code's table at 0x08CDC868 - the others, FireRed-style Kanto / Sevii and one more, the game
never shows for its places), its own cursor grid (`place_min` 0xD1) and Emerald's Brendan / May heads, as in the game. No
EXPAND, field reads, field items (so no FLY) or card yet; GUIDE is HERE only.
**Dreamstone Mysteries (2026-10-23)** (`GameKind.DREAMSTONE`, `NATIVE_DREAMSTONE`; dsmyst's Cormoria, BPEE 32 MB, no version in
the game - its debug menu's EXPANSION VERSION says "v1.11.2 Development Build"): closed, so pokeemerald-expansion's 1.11.2 tag
built plain (Debian's arm-none-eabi-gcc 12, in a container) was the map - every RAM global sits within a few bytes of that
build's, then checked in dumps, and struct offsets came from `offsetof` compiled against its headers. No save existed: a new
game was played headless and given five Pokémon through its own `callnative ScrCmd_createmon` (0x0A1EDA35: side, slot 6,
species, level, a u32 of optional-field bits) - `~/Documents/RetroArch/saves/mGBA/Pokemon Dreamstone Mysteries.sav`, the
`dreamstone` README. Gotchas: its script commands run through the ROM's 0x0A000000 mirror (a ROM scan for 0x08 code
pointers misses gScriptCmdTable, 0x0826CF9C; find it in the script context in RAM instead); the field-controls lock is
expansion's special flag 0x4005 (a bit at 0x0200179C), not a byte, so `pk_field_state` can't read it - no field reads, WILD
ENCOUNTERS (its switch 0x02037F88 checked headless anyway), EXPAND, field items or FLY; gTrainers is [3][855] by difficulty
(var 0x40A8; 0 reads the first table where it has a team: `altTrainers`); the clock seeds the RNG, so a fair fight replays
differently each run (`dreamstone_trainer_next` pokes the foe's HP). Imperium's gSpeciesInfo DEX (`POKEDEX_DREAMSTONE`: its
own 315-entry Cormoria list, which the game titles HOENN), party palette and backdrop; Emerald's bag; its TOWN MAP is a
FireRed-like screen (RomArt's `DSM_REGION_*`), no player head (the player picks one of five looks).
**Monomyth v1.0 (2026-10-31)** (`GameKind.MONOMYTH`, `NATIVE_MONOMYTH`; Team Monomyth's Athanto game): its own game code
**MONE** (32 MB), so it rides `HACK_GAME_CODES` like Ultra Violet's PUVV and is detected by hash. A closed pokeemerald build
(gcc): globals sit in EWRAM behind section anchors, so a literal-pool word points near a variable, not at it - find RAM by
diffing dumps, not by counting pool references. Vanilla's shapes with its own twists, each a switch now: party substructs
XORed with a key of its own (`PartyMonLayout.monomythKey`, its box decryption at 0x08068198), 250 flavour-only natures
(`monomythNature`, carried as `MONOMYTH_NATURE_BASE` + index so no stat arrows), a rearranged 0x58-byte BattlePokemon
(`MONOMYTH_BATTLE_MON`), a struct Main in its own order (`frameCounterOff`: no intrCheck; inBattle 0x17 bytes before callback1, at 0x02020601), 4 bytes
before SaveBlock1's `pos`, the key at SaveBlock2 +0x40, its own EXP curves past Lv70 (`expTablesMonomyth`) trainer
parties behind a u32 (`GuideTables.trainerMonStrides` / `trainerMonHeader`) and party icons packed by its own tile coder
(`MonomythIcons`, `monIconPacked`: an ARM routine at 0x081C4284 it runs from the stack). Its system flags sit 0x20 above vanilla's
(POKéMON 0x880, badges 0x887+), Heroic mode is flag 0x8A3. The user's save (`~/Documents/RetroArch/saves/mGBA/Pokemon
Monomyth (v1.0).srm`, a copy of theirs) is every fixture's; its setwildbattle takes species, level, item, a byte and a word
(ScriptContext_SetupScript 0x08090871). Tables: `scripts/gen_monomyth_tables.py`. DEX: `POKEDEX_MONOMYTH` (its one ATHANTO dex: 0x28-byte entries with 14-byte
categories, smol front pictures, packed egg groups: `BaseStatsLayout.eggGroupsPacked`, `EvoScheme.MONOMYTH`). MAP: its
PokeNav's ATHANTO MAP (RomArt's `MMY_REGION_*`, a mode-0 text screen of its own; one head for either gender). EXPAND and
field reads have a WIDE family of its own (`PK_WIDE_FAMILY_MM`: inBattle at -0x17 from callback1, `CAMERA_WIDE` s16 camera
offsets, `MAX_SPRITES` 80; it doesn't rewrite DISPCNT each frame, so mgba_dump's `layers` goes before the boot); PC
anywhere is special 57 (`FIELD_ITEMS_MONOMYTH`, PC only); STEADY needed `M4aSongs`' flipped-header signature. Its StandardWildEncounter has no switch (no WILD ENCOUNTERS),
and a look-alike gcc shape there taught `WildEncounters.gccRead` to want the HEADER_NONE compare. No FLY, bag USE, card or
far-off characters in EXPAND.
**Monomyth v1.1 (2026-11-04)** (`NATIVE_MONOMYTH_V1_1`, still `GameKind.MONOMYTH`): v1.0 rebuilt, the RAM and save unchanged (every pool word
pointing at RAM reads the same; v1.0's save is its fixtures'), the ROM moved, one table set for both (`gen_monomyth_tables.py` with both ROMs
checks it). Its header's game code is **BPEE**, so it's found by hash with the big BPEE hacks, and anything going by game code sees Emerald:
`fanfareSongId` turns it away by its title, so it borrows a fanfare like v1.0 (it has its own song table; which song is its level-up isn't mapped). The next build: map the ROM by word pairs (v1.0 word -> the word at
the shifted place) before anything else; if the RAM words still agree, it's a config over this one.
**Emerald Legacy v1.1.4 (2026-10-14)**: BPEE, 16 MB, but a rebuild - its public source (`$DECOMPS/pokelegacy`, tag V1.1.4)
built with agbcc is the release byte for byte, so take every address from that ELF (`nm`) and every struct offset / constant
from agbcc compiling `offsetof` against its headers, never from retail. Runs as `GameKind.EMERALD` under `LEGACY_TEXT`
(`emeraldLegacy`, like Kaizo); anything keyed on `"BPEE"` / EMERALD with retail addresses must check `emeraldLegacy` first
(switch, RegionMapWatch, field items, the PC BOXES editor refuses it). Its SaveBlock1 grew (120-slot ITEMS, 960 trainer
flags: system flags at 0x8C0), species 412-414 are Deoxys forms (egg 415, flagged), ~40 maps pick a second wild header by
a var (`GuideTables.wildSetVars`), its card has a fifth star (MEW) on a black card (`CardStyle.LEGACY`). docs/DEVELOPMENT.md.
**Orange Islands / Unbound FR / Quetzal ES (2026-10-09)**: **Orange Islands** (`GameKind.ORANGE_ISLANDS`,
`NATIVE_ORANGE_ISLANDS`) is retail FireRed rev 0 edited in place at 16 MB - the size of retail, so detect() now hashes a
small BPRE ROM once too (like Seaglass's BPEE check; an unknown hash falls through to retail). Its two builds (Beta 5.7
and an unversioned one, `*_UNVERSIONED_SHA1` -> no version on the site) share every address. Retail rev 0's RAM and battle
code; its own names, CRYSTL type 23 + chart, repointed wild headers (`gen_orange_islands_tables.py`); its archipelago map
comes from `RomRegionMap`; FireRed's party art over its cream stripes; no CARD (its card is its own art). **Unbound v2.1.1.1
FR** is English Unbound's code and RAM with French text: `NATIVE_UNBOUND_FR` (the Poller sets `nativeCfg` for it; English
Unbound still leaves it null), names via a `GameText` keyed `UNBOUND_FR_TEXT` over Unbound's own moves / map sections
(`GameText(englishMoves = ...)`), `gen_unbound_fr_tables.py`; its header's revision byte is 0x9E, so `RomRegionMap` turns
away only rev 1 now. **Quetzal Spanish** (`NATIVE_QUETZAL_ES`) is English's RAM with every ROM address moved (mapped
through English's literal pools). Both Quetzal releases carry English / Spanish / Latin American / Portuguese names and
pick them per kind from the save's IDIOMA options: `readQuetzalNames` -> `quetzalNames` (set by the Poller every sample),
overlays in `QuetzalNamesGen.kt` (`gen_quetzal_tables.py --languages`; Portuguese not generated). Quetzal's money passes
999,999 (`maxMoney`). Headless saves: `orange_islands(_battle)`, `unbound_fr`, `quetzal_es(_battle)`, `quetzal_johto` (a
second English save, in Johto's map groups). Orange Islands' battle POKéMON switch would need a `switchAddrsFor` entry
(FireRed's list menu, gPartyMenu 0x0203B0A0) - not added (PokeDaisyActivity).
**Quetzal Alpha 0.8.4 (2026-10-13)**: English / Spanish and their Moemon Hotfix builds (`NATIVE_QUETZAL_V084`, `_ES`,
`NATIVE_QUETZAL_MOEMON_V084(_ES)`; `quetzalV084` on the config -> ActiveTables' `quetzalV084`, set by the Poller like
`amethystV141`). The release before Alpha 9, Hoenn alone (34 map groups): Emerald's region map art and rects, vanilla
`gRegionMapEntries` (one language a build, no LUGARES option - `readQuetzalNames` skips that byte), vanilla gBagPockets
(11 pockets, quantity ^ key) instead of BAG3, caught = a seen level of 2+ (`DexFlags.caughtFromLevel`, no per-region
arrays), its own gMain / save pointers / handlers, 16 moves of its own power (`QuetzalV084Gen.kt`, `gen_quetzal_tables.py
--v084`, which checks every other table against Alpha 9's: species / items are Alpha 9's first 1520 / 879, egg 1520).
The Moemon builds are their base build's code and tables, moved; only the pictures differ. **No 0.8.4 save exists**:
Spanish Alpha 9's loads in all four (English Alpha 9's stand in Kanto / Johto and fade to black), party / money / map /
dex intact, its bag misread by 0.8.4 as by our decode (the game's own bag screen shows the same junk). Fixtures
`quetzal_084`, `quetzal_es_084`, `quetzal_moemon_084`, `quetzal_moemon_es_084` and their `_battle`s; **the Moemon battle
dumps had the build's decompressed battle sprites and their palettes zeroed** (its pictures are never committed - do the
same for any new Moemon capture). Cursors, the four handlers and the POKéMON switch (gPartyMenu 0x0203EA08, a list) were
checked headless on all four. No EXPAND (not mapped).
**Quetzal Alpha 9 v1 (2026-10-15)**: English / Spanish (`NATIVE_QUETZAL_V1` / `_ES_V1`, `quetzalV1`): v0 rebuilt, v0's RAM, every ROM
table moved (each checked equal to v0's, `gen_quetzal_tables.py --v1`, `QuetzalV1Test`); the v0 saves load in it. Its bag is a new
`BAG4` record (`Bag4Layout`, read before `bag3`: v1 rewrites a v0 BAG3 on load) whose item lists are in the ROM - a fixture test
needs the ROM file (`RomFileReader.withRam`). Spanish v0's dex config read English's evolution / learnset tables until then.
**Ultra Violet / Sword and Shield Ultimate Plus (2026-10-11)**: **Ultra Violet v1.22** is FireRed rev 0 edited in place
under a game code of its own, **PUVV** (`TelemetrySampler.HACK_GAME_CODES`: detect() hashes it before the "not a Pokémon
game" check; `CompanionSupport`, `GameTitles`' hashing, INFO's base game, the level-up fanfare, `RegionMapWatch` and the
site's `compat.json` `hackCodes` treat it as FireRed; its BEST EFFORT candidate is tried only for PUV codes). It runs as
`GameKind.FIRERED` (FireRed's look, guide bosses, growth rates) with `NATIVE_ULTRA_VIOLET` and `ActiveTables.fireRedHack`
(set like `amethystV141`) laying `UltraVioletGen.kt` over the shared tables - a diff against retail rev 0 by
`scripts/gen_ultra_violet_tables.py` (WHITE BALL, LAND DEED, GYARADOS WATER / DRAGON, a few renamed sections), its bag
text via `itemNamesUltraVioletGame`. Its own gWildMonHeaders / gMapGroups (GuideId.ULTRA_VIOLET's area data from the ROM).
**Sword and Shield Ultimate Plus v1.2.1.2** is Galar on CFRU (`GameKind.SWORD_SHIELD`, `gen_cfru_tables.py sword_shield`,
which now also writes gender ratios and map names for a game that asks): species are DPE's ids, so without its own
config it reads as Amethyst (BestEffortTest's siblings) - and Unbound, which used to read as Amethyst, now has two equal
CFRU siblings and goes to the player. Its party menu is CFRU's tiles and slot tilemaps in a palette of its own
(`SS_PARTY_BG_PAL`): `RomArt` rebuilds its frames into `partyswsh/` and its grid cell as `partybg/swsh_tile.png`
(`SwordShieldPartyStyle`, generated by `gen_cfru_party_assets.py` with `blendOverBackdrop = false` - its slots are solid;
status icons are Odyssey's bytes; `SwordShieldPartyPalette` until a scan has made the art). Its bag is a sampled
`SwordShieldBag`. Its Casual / Performance / Casual and Performance patches share every table and the RAM (one config, four
hashes; the base save loads in each: `sword_shield_up_*` fixtures); its Galar map comes from `RomRegionMap`, which now drops a Sevii screen with none of the main one's
backdrop border (FireRed's tilemap in the hack's tiles: S&S's and Odyssey's), its sections going on the main map unhighlighted.
Its gyms reuse FireRed's gym maps (their sections are leftovers), so NEXT BOSS names them by badge; badges 4 and 6 have a
second leader's script (BEA / GORDIE), both listed. Headless battles for both: `setwildbattle` through rev 0's
`ScriptContext_SetupScript` (0x08069AE5, kept by both), fixtures `*_battle`.
**FireRed Rocket Edition / Ash Gray (2026-10-13)**: two more FireRed rev 0 hacks edited in place (16 MB BPRE, by hash in the
small-BPRE check, `TelemetrySampler.fireRedHackConfig`), run as `GameKind.FIRERED` like Ultra Violet: `ActiveTables.fireRedHack`
(a `HackText`, `fireRedHackFor(cfg)`; it replaced the `ultraViolet` flag) lays each one's names, types, moves, items, map
sections, type names / chart and gender ratios over FireRed's (`scripts/gen_firered_hack_tables.py` -> `RocketEditionGen.kt`,
`AshGrayGen.kt`; UV keeps its own generator). Rocket Edition's text is mixed case, so its tables are whole and `gameCase` leaves
them be (`ownCase`); it added moves 355..486 and FAIRY (23); `movesAreVanilla` is off for any such hack with moves of its own.
Ash Gray redrew the region map (its Orange Islands): `ownRegionMap` sends the MAP to `RomRegionMap` (`readsRomRegionMap`). No
save of their own: retail FireRed's `.srm` boots in all three builds (`native-capture/boot/firered_hack.txt`: B presses past
Ash Gray's quest-log recap); battles, one-press picks, the switch, a Potion through ExecuteTableBasedItemEffect and EXPAND
(`verify_wide.py`) checked headless. Ash Gray's NEXT BOSS skips CASCADE / RAINBOW (scripts hand them over, no battle).
**Ghost Grey (2026-10-16)**: v1.0.1 / v1.0.2 / v1.0.4, a 32 MB FireRed rev 0 hack edited in place (HexManiacAdvance, not CFRU),
so it rides the same path from the big-BPRE check (`fireRedHackConfig` runs before the CFRU hashes): Rocket Edition's repointed
moves / FAIRY / ability names, its ghosts in FireRed's 411 species slots (`gen_firered_hack_tables.py ghost_grey` -> `GhostGreyGen.kt`;
`groups`: v1.0.1 differs in PURIFY's type only, a small `HackText` over v1.0.2's, so `NATIVE_GHOST_GREY_V1_0_1` is its own config
object - `fireRedHackFor` goes by identity). Its own save (1.0.3, from its Discord: `~/Downloads/Game ROMs & Emulation/rom saves
from discord/`) loads in all three. Seven badges on FireRed's flags 0x821..0x827 (0x820 never given); Cinnabar's leftover script
also sets 0x826, and SUMMER's sets 0x827 before the battle (her trainer flag marks her beaten). Its repel items last 250 / 200 / 250. USE, the PC, FLY, the field reads, the bike and WILD ENCOUNTERS were each
checked headless on v1.0.4 and v1.0.1 (docs/ROM_FEATURES.md's note 11).
**Let's Go Pikachu / Eevee (2026-11-02)**: Kevin Pokefan's English-translated FireRed rev 0 hack (32 MB, no version in the
game; each version has a "Translation Only" build, three moves' battle data apart, sharing its tables): Ghost Grey's path
(`fireRedHackConfig`, `GameKind.FIRERED`, `LetsGoGen.kt` from `gen_firered_hack_tables.py lets_go`, one `HackText` per version) but
the species grew to 987 ids, so its tables carry every species with gender ratios and growth rates (`HackText.growthRates`, read
by `expProgress`), its config repoints the icon tables, and its DEX flags left the save blocks for EWRAM (0x0203C0C8 seen /
0x0203C133 caught, `DexFlagBlock.FIXED`) under its own dex numbers (to 855). Its party menu is a two-column grid
(`runningLetsGo`, the switch's `grid`), drawn as the game draws it: CFRU's party menu code and slot tilemaps over its own
tiles, palette, grid and status icons (RomArt's `LGO_*` -> `partylg/`, `LetsGoPartyStyle`). Its wild Pokémon walk the grass. Retail FireRed's save is "deleted" by it: the `lets_go*`
fixtures are a new game played headless (setup through rev 0's ScriptContext_SetupScript), loading in all four builds; its intro
takes ~1500 frames and its title wants held presses (`boot/lets_go.txt`). The manual can't name it in full (docs.test.js bans one
of the names): it's "Let's Go" there.
**GS Chronicles v2.7.6 (2026-10-13)** (`GameKind.GS_CHRONICLES`, `NATIVE_GS_CHRONICLES`): Johto and Kanto on CFRU (32 MB BPRE
rev 0, Ruki Studios), FireRed rev 0's RAM (every RAM global's pool word is rev 0's) with its tables repointed
(`gen_cfru_tables.py gs_chronicles`; gBattleMoves at an odd 0x09D10BE3). Gotchas: its gItems is indexed by slot and the
`.itemId` field is junk (often a copied 374): names keyed by slot (`item_key="slot"`, `--items` for the area data); its
primary tilesets hold 512 metatiles (WIDE's `PRIMARY_OVERRIDE`: score 0 without it); it uses EWRAM 0x0203FF00, so its
scripted battles run from 0x0203F400 (a battle from 0x0203FF00 met a blank Lv0 foe; verify_wide.py's `script_ram`). The
user has no save and retail FireRed's is "deleted" by it: `~/Documents/RetroArch/saves/mGBA/Pokemon GS Chronicles (v2.7.6).sav`
is a new game played headless (mgba_dump's new `savestate`, then givemon / additem through ScriptContext_SetupScript).
Its dex is one 501-entry Johto list of species ids; DPE species ids make it read SURE as Unbound without its config. Its
region map's Kanto boxes are the hack's own and don't line up; RomRegionMap now also drops a FireRed Sevii tilemap in a
hack's moved tiles that holds no section or under a quarter placed (GS Chronicles' two, Gaia's three). NEXT BOSS: the
8 Johto leaders only (Kanto badges / League use flags not traced). Party menu: CFRU's own slots, Odyssey's status icons.
**Recharged Yellow (2026-10-11)** (`GameKind.RECHARGED_YELLOW`, `NATIVE_RECHARGED_YELLOW_V1_8_0` .. `_V1_9_7`): a 16 MB BPRE
rev 0 that is pokefirered rebuilt with modern gcc - no retail address, literal-pool trick or `RomRegionMap` applies (it's kept
off RomRegionMap; FireRed's region map art, rects and grids, from its own ROM, fit). The one save (v1.8.0's) loads in every
release, so each has field + battle fixtures of it. Its GF ROM header (0x08000100) gives the icon / name / base-stat / item tables
and SaveBlock1's own layout (flags +0xEFB, party +0x44); no encryption key (`encryptionKeyOff = -1`, money +0x29C). v1.9.x moved
EWRAM past 0x02005000 +8, v1.9.7 the save-block pointers +0x1C. Gotchas: trainer records carry a NORMAL and a HARD party
(`GuideTables.trainerHardPartyOff`, size nibbles at +0x1F); its system flags moved (badges 0x880, game clear 0x864) and the
hidden-item flag base wasn't found (HERE marks none); its script commands differ (`gen_guide_areas_rom.py --ops`, see
`gen_recharged_yellow_tables.py`'s doc); its song ids are Emerald-ordered (`isRechargedYellowRom`: fanfare 367, all BGM
prefetched) and `RegionMapWatch` reads sRegionMap two pool words before 0x4796. It has a text-language option and other
languages' name tables in the ROM - the companion shows English, and best effort is never SURE of it (ranked first instead).
Its trainer card is FireRed's frame with its own pictures / font, wording and save fields (`CardStyle.RECHARGED_YELLOW`).
**Recharged Emerald (2026-10-14)** (v2.2.5, v2.2.8, Rebalanced v2.2.8; `NATIVE_RECHARGED_EMERALD_*`): Jaizu's pokeemerald rebuilt
with modern gcc, like Recharged Yellow, but on Emerald's ids, so it runs as `GameKind.EMERALD` with `ActiveTables.emeraldHack` (a
`HackText`: the class the FireRed hacks' `fireRedHack` uses, renamed from FireRedHackText) laying its names (mixed case, `ownCase`)
and Rebalanced's types / move powers over Emerald's (`gen_recharged_emerald_tables.py`). Anything keyed on EMERALD + BPEE that
assumes retail's RAM must check `runningEmeraldHack` (the POKéMON switch does; RegionMapWatch and FIELD items gate themselves by
reference counts / CRCs). Its SaveBlock1 differs past the bag (6 pockets, flags +0x1344), so retail Emerald's save boots it only
rewritten by `scripts/convert_save_recharged_emerald.py` (converted saves next to RetroArch's; `recharged_emerald*` fixtures).
Rebalanced has 4 more bytes in front of both save blocks (`saveBlock1PosOff`, `playerGenderOff`) and 0x24-byte wild headers. The
game-clear flag moved to 0x863. Its art is recompressed and partly redrawn, so it has its own RomArt fingerprints (`RE_*`: `partyre/`, its backdrop, its Hoenn
map and map heads, found by matching VRAM against the ROM's LZ77 streams). FIELD items come from its config
(`NativeConfig.fieldItems`, see USE on a Pokémon below). No EXPAND (its overworld sprites load tiles at run time), FLY or CARD.
**Super Mariomon (2026-10-11)**: five releases (`NATIVE_MARIOMON_*`, `GameKind.MARIOMON`), an old pokeemerald-expansion
built with agbcc - retail Emerald's code word for word where the expansion left it, so its RAM came from retail's literal
pools (`pref`-style: the bytes before each pool word matched in the ROM) and was then checked in captures. Two RAM layouts
(v1.5.x; v1.2.1-v1.3, most EWRAM 0x100-0xA00 lower) and two table sets (`mariomonV13`, set by the Poller like
`amethystV141`; `gen_mariomon_tables.py`). Gotchas: its saves use 4084-byte sectors and v1.5 grew SaveBlock1 (a Medicine
pocket at its end), so v1.3 / v1.2.x call the v1.5.1 save corrupt - `scripts/trim_save_block1.py` cuts it to 0x3E28 and
they load it with the same party (their fixtures). Species names 387-905 are blank (the Mario species start at 906: dex
No. != species id); gTypeEffectiveness is u32 uq4.12 (not u16); struct Item has no itemId (0x28 bytes); struct Trainer is
0x2C bytes with two more parties picked by flags 0x29 / 0x885 (NEXT BOSS shows the default one) and TrainerMons without
moves get their level-up moves (`GuideTables.learnsetPairs`). Its region map's tilemap names 21 tiles past its graphics
(bottom right, under the game's text box): `emeraldRegionMap` leaves such cells transparent. The party menu is a 2-column
grid where DOWN walks 0 -> 2 -> 4 -> 5 (`grid = true`). The TATTLEDEX is its WORLD dex (sHoennToNationalOrder): 152
entries in v1.5, the 153rd (BOWSER) hidden like Emerald's Jirachi / Deoxys. No CARD: its MARIO card is its own art.
**USE on the ITEMS tab** (`FieldItems.kt`, retail FireRed rev 1 / Emerald): a Repel is used by calling the game's own
VarGet / VarSet / CheckBagHasItem / RemoveBagItem between frames (`MgbaCore.pkCall`, the main core's pk_call returning r0),
only while ArePlayerFieldControlsLocked is 0 and no battle / menu screen is up; the functions' code is CRC-checked first.
The game's own step counter then runs it out. mgba_dump's `call` prints r0 to test such calls.
**Held-item marker** (PARTY slots): the game's own (`sHeldItemGfx`, an item over Mail, 8x8 each - `RomArt.HOLD_ICONS`, FireRed's
bytes = Emerald's) at the icon's top-left + (16, 22) - the party menu's sprite coords (20, 50) by the icon's (16, 40) - moving
with the icon; `Mon.heldItem` / `MonView.heldItemId` from the Growth substruct (CFRU plaintext boxes: +2). GbaPartySlot and the
palette slot both draw it.
**USE on a Pokémon / GIVE / TAKE** (`FieldItems.kt`'s `PartyItemFns`, `fieldItemsFor(code, rev)`; retail English FireRed rev 1, LeafGreen rev 0 / 1, Emerald - with Kaizo, same code - Ruby and Sapphire rev 1 / 2): medicine and PP items go
through the game's PokemonItemUseNoEffect (which slots / moves light up in the PARTY-slot pick, `ItemPartyPicker`) and
ExecuteTableBasedItemEffect(mon, item, slot, move), then RemoveBagItem; GIVE / TAKE set the held item with SetMonData (it
re-encrypts and fixes the checksum), its `&item` in `scratch` - EWRAM past the build's last symbol, put back after - written by
`MgbaCore.pkWriteBytes` (EWRAM / IWRAM only). `pk_call` takes r0-r3. GIVE follows the game's switch order (the new item out,
the old one in, both back on a full bag); every path undoes the bag if SetMonData didn't take. Not Rare Candy / stones (the
party menu's level-up and evolution), not Mail. Addresses come from pret's pokefirered built clean as firered_rev1 (= retail;
LeafGreen / Ruby / Sapphire likewise from pokefirered / pokeruby built clean, each SHA1 = the retail ROM). Bag rows the ROM can act on get the Poké Ball mark (`PixelIcons.itemAction`, a legend under the
list), only where `party` is set. Checked headless (damage / status / PP poked with SetMonData, the calls, an in-game save, a
reboot from the `.srm`: every change kept, checksums valid) and in the app on the API 32 emulator. The other languages
(and English FireRed / Ruby / Sapphire rev 0) are generated: `scripts/port_field_items.py <multi-language dir>` maps each English
table's functions (code bytes, else BL-masked), party globals and gItems (literal pools) into the port and checks them against the
ROM (gItems' records, the Repel's steps, the mapped field-use routines = what gItems points at) -> `FieldItemsPortsGen.kt`,
35 ROMs, each checked headless like the English ones; since 2026-10-14 the Japanese Emerald / Ruby / Sapphire too (40):
their rebuilt code needs the fallbacks - gItems by its records (`detect_items`), fieldControlsLocked from the
ScriptContext2 trio (`controls_trio`), the small FLY functions by their code alone (`short_code`), the PC special's
index searched (Japanese Emerald's is 60). FLY and PC opened headless on Japanese Emerald and Ruby (English saves). Hacks
that are retail code edited in place (`fieldItemsForGame`, by GameKind): Glazed (Emerald's table, its hooked VarGet / VarSet's CRCs),
Orange Islands and Ultra Violet (FireRed rev 0's), and the CFRU hacks (Unbound, Radical Red, Odyssey, Amethyst, Gaia, Sword and
Shield: FireRed rev 0's functions, each hack's own gItems; S&S keeps Berries / Mail in pocket 2, so only the TM pocket is kept out
by pocket there). CFRU boxes are plaintext: no checksum to check, their own GetMonData reads the changes back after a reboot. Emerald has no
PokemonItemUseNoEffect: its check is ExecuteTableBasedItemEffect on the Pokémon with its 100 bytes put back (`itemNoEffect = null`;
mgba_dump's `memsnap` / `memrestore` check it the same way). **Recharged Emerald** (a gcc rebuild: no retail code, but retail's
BPEE code / revision / kind) gets its tables from its config (`NativeConfig.fieldItems` -> `TelemetrySampler.knownFieldItems`,
which `fieldItemsForGame` takes before the kind; the activity's support cache keys on the table too):
`FieldItemsRechargedEmeraldGen.kt` by `scripts/find_field_items_recharged_emerald.py`, which finds each function through the
game's own callers (gScriptCmdTable's additem / removeitem / checkitem, a Repel's / Potion's fieldUseFunc down to Task_UseRepel and
ItemUseCB_Medicine, script.c's lock trio). Its differences, each a field now: struct Item is 48 bytes with holdEffectParam at 0x15
(`itemParamOffset`), pockets 4 TMs / 6 key items; GetMonData's ids from SPECIES on are 9 later (`monFieldShift`: 9 fields of its
own before ENCRYPT_SEPARATOR); Task_UseRepel also zeroes its Lure's vars 0x4055 / 0x404E and keeps the Repel in 0x4052
(`repelClearVars`, `lastRepelVar`). Its PEWTER ARARE (62) runs the medicine routine with no effect entry, so it isn't offered;
Lures (52-54) aren't either. Checked headless on all three builds the retail way (calls, the dry run leaving RAM untouched but the
stack, an in-game save, a reboot keeping every change; scratch 0x0203FFF0: nothing past 0x0203D600 is ever written).
**FLY on the MAP tab** (`Fly.kt`, `FlyFns` on `FieldItemFns.fly`; retail English FireRed rev 1, LeafGreen rev 0 / 1, Emerald): a FLY button when the ROM's code
matches and a party Pokémon knows MOVE_FLY (19). The party menu's checks in its order (fields locked = NOT NOW, the badge flag,
SetUpFieldMove_Fly = may fly from this map), the list = sMapFlyDestinations' sections whose GetMapsecType is VISITED; a pick runs
LockPlayerFieldControls + FadeScreen(FADE_TO_BLACK), then ~30 frames later (`requestFieldItemJob(delayFrames)`) gPartyMenu.slotId
= the flyer and SetFlyWarpDestination(mapsec) - the game plays its own cut-in, flight and landing. A failed second step fades
back in and unlocks. Checked headless on the user's FireRed save (landed outside Cerulean's Pokémon Center, controls free).
Emerald has no SetFlyWarpDestination: `HealWarp` replays CB_ExitFlyMap (sMapHealLocations' heal location, Littleroot by
gender, Ever Grande = the city, Battle Frontier past the table) + ReturnToFieldFromFlyMapSelect; checked headless (Fortree).
Since 2026-10-14 also every language port + FireRed rev 0 (`port_field_items.py` maps FlyFns: code, gPartyMenu /
gSaveBlock2Ptr by pool, sMapFlyDestinations by pool or bytes) and the hacks on FireRed rev 0's code (Radical Red, Ultra
Violet; Gaia, Odyssey, Amethyst, Sword and Shield, Orange Islands through `GET_MAPSEC_TYPE_U16`, their 16-bit map section
patch of GetMapsecType) - flown headless on each (a party Pokémon given the move by SetMonData, badges poked in where
missing). Emerald Kaizo is off: the shortcut's ReturnToField from the live field crashed its map reload (its own Fly map
works). Ruby / Sapphire (`FLY_RUBY` / `FLY_SAPPHIRE`, every language but Japanese): Emerald's HealWarp with their own
pieces - SetUpFieldMove_Fly there also sets the party menu's callbacks, so `mapHeader` makes the gate
Overworld_MapTypeAllowsTeleportAndFly(gMapHeader.mapType); the static Fly map type function (0x080FB778 in rev 1); the
cut-in's gLastFieldPokeMenuOpened; gSaveBlock2 in place (`saveBlock2Static`). Glazed flies with Emerald's (its towns are
Hoenn's map slots, CB_ExitFlyMap retail's). Unbound (`flyUnbound`): CFRU jumps GetMapsecType / SetFlyWarpDestination
into its own code - a visited flag per section from 0x57, and a list of 10-byte fly spots per region (Borrius: 27 at
0x09FE6694, `FlySpots`, which also gives FLY its towns); the region is GetSelectedRegionMap = sRegionMap (0x020399D4)
+0x479B, NULL on the field, so `RegionMapPin` aims it at a zero scratch byte for the call (unpinned, the warp read
garbage and landed in an indoor battle room). The expansion hacks (2026-10-14: Heart and Soul, Lazarus, Emerald Imperium)
get FLY and PC only (`FieldItemFns.bagActions = false`; 0 = an unused function the CRCs skip): addresses from a build of
their source (Heart and Soul's repo at 2.0.6) or of pokeemerald-expansion 1.10.0 (Lazarus, Imperium), plain `make`
with Debian's arm-none-eabi-gcc 12 in a container, each function then found in the release by its masked first
instructions, else through its callers' BLs (votes), else small ones by their RAM byte (the field-lock trio). The
expansion renumbered MON_DATA (MOVE1 20; IS_EGG 54 Heart and Soul / 52) - `FlyFns.monMoveField` / `monEggField`;
Heart and Soul gates FLY with gFieldMoveInfo's IsFieldMoveUnlocked_Fly / SetUpFieldMove_Fly. SoulGold too (four builds,
`fieldItemsCandidatesForGame` lets the CRCs pick; MOVE1 / IS_EGG 21 / 55; its GetMapsecType takes the section - 0xC3, its
PC is special 71). Seaglass (Lazarus's table ported, PC too) and TMT2 (expansion 1.12.3: functions matched by
instruction sequence against that tag's build, then checked; FLY only), R.O.W.E. and Mariomon 1.5.x (retail's table ported;
R.O.W.E.'s CB_ExitFlyMap switch decoded) and Quetzal Alpha 9 (Hoenn's towns; its own badge check as the flag check) since
2026-11-11. Rogue refuses FLY in its own party menu: none there. Not done: Mariomon 1.2.x / 1.3, Recharged Yellow, Celia. The button is the party menu's field-move
blue (`OptionColors.fieldMove` / `fieldMoveLayers`), under PLACES; the map buttons sit top right on every map (Emerald-style maps had them bottom left, over Hoenn) but Heart and
Soul's and R.O.W.E.'s (`BOTTOM_ROW_MAPS`: land and routes fill their top right), which get one row along the bottom left,
on the sea left of the label window - checked on all three of R.O.W.E.'s maps (ui-preview `rowe-map-region*`).
**FREE FLIGHT + the scenic FLY** (2026-11-10, retail FireRed rev 1 and Emerald; then FireRed rev 0, LeafGreen, Ruby / Sapphire, every language (port_field_items.py's port_flight), the randomized builds, Emerald Legacy and the hacks on retail code - Radical Red, Unbound, Glazed, Ultra Violet, Orange Islands, Ghost Grey (its blank bird: the balloon, in greys), Let's Go, Odyssey, Gaia, Amethyst, Sword and Shield, GS Chronicles, Rocket Edition, Ash Gray, Emerald Kaizo (FLY over the map only: `classicFly` false, its warp crashed), and the expansion hacks Imperium, Lazarus, Heart and Soul, Seaglass, TMT2, R.O.W.E., Mariomon 1.5.x, Quetzal Alpha 9 and SoulGold's five builds (their following Pokemon removed at take-off and brought back once down: `followerRemove` / `followerUpdate`; SoulGold's 11-bit grid: `mapGridUndefined`); the player inanimate while up (fast movements' step anims ran past FireRed's table: Rocket Edition reset) - `flightAllowed`, docs/DEVELOPMENT.md's "Which games"; `pk_flight.c` after every frame, `Flight.kt`, `ScenicFly.kt`,
`FLIGHT_FIRERED_REV1` / `FLIGHT_EMERALD` on `FieldItemFns.flight` - Emerald's from pret's `symbols` branch, its rider a sprite of its own; NEW tags `FREE_FLIGHT` / `SCENIC_FLY`; docs/DEVELOPMENT.md's section): TAKE OFF / LAND on the
MAP tab, the QUICK MENU (`QuickAction.FLIGHT`) and the FREE FLIGHT hotkey; MAKEOVER > FLY ON (`FlightVehicle`, NEW tag `FLY_ON`) swaps the bird for our own balloon / helicopter / zeppelin / plane; MAKEOVER > RIDE ON (`RideOn.kt`, `RideVehicle`, `Prefs.rideVehicleId`, NEW tag `RIDE_ON`; `scripts/gen_ride_vehicles.py` -> `RideVehiclesGen.kt`) swaps the bike for a motorcycle / scooter: PLAYER LOOK's patch leaves the rider alone in the hero's bike sheets (`PlayerLookRunner.ride`: after the look's writes, their upper body from their standing frames, holes where front parts go, no pedalling) and the vehicle is a sprite of its own behind them in its own 15 colours (`RideOn.Sprite` on `Patch.ride` -> `MgbaCore.pkRideSet` / `pkRideShow`, pk_wide.c's ride: OBJ tiles 944-975 as one 32 x 64 sprite (the wheels reach below the rider's frame), its exhaust smoke a 16 x 8 sprite behind it (tiles 942-943, `PK_RIDE_BYTES`), their OAM entries right after the player's with the game's later shown ones moved down a place for the frame (its tyre tracks and grass effects were drawn over the wheels), only while `onBike`, rider and vehicle bobbed a pixel together - slow standing, quick moving; mgba_dump `rideset` / `rideon`). Taking off (TAKE OFF, the scenic FLY) gets off the bike first (`offBikeThen`) (the rider in a standing pose facing west, the whole picture mirrored going east - never mirror the rider again; from behind / the front in the balloon going up / down: `upDown`) (`scripts/gen_flight_vehicles.py` -> `pk_flight_vehicles.h`, the player's sprite in its seat); GAMEPLAY > FLIGHT AREA (`FlightArea.kt`, NEW tag `FLIGHT_AREA`): VISITED (default) flies only over maps the save has seen (kept per save, seeded from its towns, the routes between them and maps with a beaten trainer), ANYWHERE pauses RetroAchievements from the first take-off; the companion's GAMEPLAY > FLY STYLE CLASSIC / SCENIC (`Prefs.flyStyleId`, SCENIC by default; the first scenic landing asks
KEEP IT / USE CLASSIC once, `FlyStyleAsk` in FeatureTip.kt where the tips show, `Prefs.flyStyleAsked`).
No ROM patch: controls locked, held movements written into the player's ObjectEvent (no collision; nothing a step triggers runs), the player
hidden with localId 127 (the game then skips its ground effects), the game's own FLY bird sprite at the player's fixed screen spot, a see-through
shadow drawn into each finished frame (`pk_flight_draw`, undone line by line where mGBA didn't redraw the line); with EXPAND the diagonal staircase is glided out (`pk_wide.c` flyBegin: the view moves at the velocity the flight means,
pulled toward the real camera; the window and the bird's OAM entry moved by the difference); diagonal steps quicker (8 / 6 / 4 / 2 frames a tile
by a running balance) so diagonals match the straight speed; the bird keeps its side view on a diagonal; the map's name sign is held still
(`flyLabel`); the grid's leftovers from FireRed's MoveMapViewToBackup mended once per map (`flMend`); no-fly stand-in maps (`FlightFns.noFly`:
SaffronCity_Connection). Its state is
in the game's RAM (that mark + the bird id in fieldEffectSpriteId), so states / REWIND taken in the air come back flying (`pk_flight_tick` adopts
them; `syncFlight` re-sends the same words every half second, which change nothing). SCENIC: A* over the maps joined by connections, pulled into
staircases, flown at WALK_FASTER with EXPAND at its widest step (`WideCamera.flightZoom`) and the game at `SCENIC_SPEED`; towns not joined to the
player's map (Saffron, the Sevii Islands) fly the classic way. mgba_dump: `flighton` / `flight` / `flightroute` / `flightinfo`. A native build of
mgba_dump on the Mac (no Docker) works: libmgba.a from `third_party/mgba` with cmake, then clang (2026-11-10, when Docker's VM kept crashing).
**Bag favorites** (ITEMS): the star button beside USE in an item's description bar stars it for this ROM only
(`FavoriteItems`, Compose state; `Prefs.favoriteItemsFor(crc)`, loaded in `loadRom`, item ids). Starred items come first
in the DEFAULT order (bag order kept among them) and carry a gold star (`PixelIcons.star`, `OptionColors.favorite`) in
every order. A FAVORITES tab (`FAVORITES_TAB`, under ALL) lists them alone, shown only while the bag holds one
(unstarring the last goes back to ALL); ui-preview `*-items-favorite`, `*-items-favorites-tab`.
**Item descriptions are read from the player's ROM, never bundled** (they're the games' prose; names stay bundled):
`itemDescription(id)` -> `RomItemText` (through `PokedexSource.reader`, cached per ROM) with the game's
`NativeConfig.itemDescs` (`ItemDescTable`: base / stride / description-pointer offset, `vanillaItems` / `japaneseItems`
for struct Item; expansion's gItemsInfo has it 8 bytes before the name; Rogue chains gRogueItems via `next`). The Poller
picks it on detect (`matchesRom` checks vanilla ones).
No ROM = "". Yellow keeps our own hand-written text (`ItemDescriptionsYellow.kt`).
The generators no longer write descriptions; `RomItemTextTest` pins a few per game. A new game needs its `itemDescs`.
**1x frames follow the game screen's vsync** (`EmulatorEngine.onVsync`, ticked from `EmulatorView`'s GL thread): one game
frame per refresh (60 Hz) or per two (120 Hz), audio resampled to match (`pkSetAudioRate`) with RetroArch-style dynamic
rate control on the AudioTrack's fill, written non-blocking; no usable vsync (or another speed) falls back to the timer +
blocking writes. Before, the audio clock paced frames and the display showed one twice / skipped one every few seconds.
Then (a Thor Pro report: smoother, but pacing still off): a draw no longer races the frame its refresh starts -
`EmulatorEngine.onVsync` returns how long the GL thread may wait for it (~60% of a refresh, only on the refresh that
starts a frame) and `EmulatorView` waits on `publish`; at most one frame's worth of refreshes is banked (4 were: after a
hiccup the game raced through frames); the game surface asks for 60 Hz (`Surface.setFrameRate`, a hint), so a 120 Hz
screen can drop to 60 while it's up. Not yet checked on a Thor - read `fps=` in logcat and `dumpsys SurfaceFlinger`.
**Fast-forward on the refresh (2026-10-13)**: a whole-number FF speed (2x / 3x / 4x on 60 Hz, `ffPerTick` =
speed / refreshes per frame, `ffFramesPerRefresh`: 2x is 1 a refresh on 120 Hz - multiplying ran it at 8x, GitHub #64) runs its group of game frames back to back on each refresh tick and draws only the last
(EXPAND's window too), so every displayed frame advances the same number of game frames. Timer-paced FF (frames every
4.17 ms with ms sleeps, EXPAND drawn every 12 ms) gave the 60 Hz screen steps of 3, 3, 5 - the user's "not as smooth as
RetroArch", measured on the Thor at 4x; the compositor itself presented every 16.6 ms. Other speeds (1.5x, below 1x,
uncapped) stay on the timer. The `fps=` log line carries refresh / paced / ffPerTick / late / work / underruns, the
companion's samples (`sample=count:avg/worst`) and the audio made / taken by the track / played (`audio=`); a debug build on
the RP6 (same SoC as the Thor) reads them with `adb logcat | grep fps=`.
**Pacing fixes (1.3.1, GitHub #215 / #216, measured on the RP6)**: (1) the companion's full sample no longer decodes on the
emu thread - it took 3-15 ms between two frames (GBA) and every one past ~8 ms cost a refresh: the emu thread copies the RAM
(`RamSnapshot`: EWRAM + IWRAM, a DS's 4 MB main RAM, `pkReadInto` into kept buffers, < 1 ms) and `PokeDaisyActivity.startSample`
decodes it on `pokedaisy-sample`, handing `afterSample` back through `EmulatorEngine.postToEmu`; `TelemetryStore.refresh` is
synchronized (a savestate's copy still refreshes on the emu thread); Game Boy games still sample on the emu thread. (2) A DS
frame (4-20 ms) is never waited for by the GL thread (`onVsync` returns 0 for `nds`): drawn a refresh later, a frame of
latency - waited for within 60% of a refresh, 45 of 120 were late. (3) FF on the refresh fits its group to the device
(`FfGroupFit`: a running average of group time; a frame goes only while the smaller group keeps 3/4 of the late one's
throughput, frames are tried back with a doubling back-off) and SPED-UP divides by the group really run. DS audio is
resampled by `NDS_FPS` (59.83), not the GBA's. (4) A GBA game whose frames keep missing the wait (3+ late draws in two 120-frame
windows: Radical Red, 5-9 ms frames with 12-14 ms ones, GitHub #63) goes the DS way until it closes (`slowFrames`, latched -
flipping back skips / repeats a frame; the `fps=` line says `paced=1 (slow: not waited for)`).
**FPS / CPU** (SETTINGS on either screen, SCREEN group, `Prefs.showPerformance`, off): flush in the game screen's
top-left corner on a small dark box (the HUD's messages moved top-right) - frames the core ran per second
(`EmulatorEngine.framesRun`, ~60.0 on a 60 / 120 Hz screen, 59.7 on the timer) and the app's share of the whole CPU
(`CpuUsage`, /proc/self/stat over all cores), twice a second. A tap on it or near it (`TOUCH_REACH`) opens the panel
(`PerfReading`, shadowed text with no box - the user's call: box on the short one, none on the long): frames on screen, late frames (the GL wait timing out), frame time avg / worst,
emulation load, refresh + pacing, speed, audio underruns, battery temperature, thermal status, memory.
**LIGHT COMPANION** (DISPLAY OPTIONS on both screens, `Prefs.lightCompanion` AUTO / ON / OFF, NEW tag; `companion/LightCompanion.kt`,
2026-10-20, an RG DS report: after FIGHT the game took no buttons until the companion had drawn the move list, the picture
running all along): the device's buttons reach the game through `dispatchKeyEvent` on the UI thread, which also draws the
companion, so a weak chip's busy companion leaves presses waiting. While on: TelemetryStore hands the companion a sample only
when something but the frame counter changed, at most every 500 ms (a battle starting / ending or its menu changing goes at
once); CompanionTweaks' motion tweaks read OFF (their rows keep the player's choice); the battle pane cuts instead of
crossfading; ON COMPANION's shaders are off. AUTO = `DevicePerformance.looksWeak` (low-RAM flag, < 3 GB, no core over 2.3 GHz:
the RK3568's A55s top out at 2.0) or a button handled 150 ms+ late 3 times in a minute (`noteKeyDelay`, logged as
`pokedaisy/perf`; remembered in `Prefs.lightCompanionMeasured`). ui-preview's `stress` times the BATTLE tab's samples and menu
flips with it off and on. **Touch controls' fingers are off the UI thread** (`TouchInputWindows`, 2026-11-08, a Galaxy Tab A: a
companion tab switch held the UI thread 150-850 ms and the pad's presses waited behind it): see-through windows of their own
over the buttons' clusters, added from the `pokedaisy-touch` thread, the drawn pad's hit test (`touchHits`) into one
`TouchPressTracker` with the drawn pad's; they let every touch through (`NOT_TOUCHABLE`) while anything is open over the game
(`PokeDaisyActivity.touchWindowsAllowed`: a HUD panel, the QUICK MENU, the companion window, a tip, the editor...), and the
drawn pad takes the fingers as before - a new overlay over the game goes on that list. Physical keys still arrive on the UI
thread (a focused window on its own Looper would change how every key arrives: two-screen focus, BACK, the Thor's focus modes). **Never on Samsung's Android**
(`TouchInputWindows.supported`, 1.5.0.1's hotfix): after a configuration change it updates every window of the activity,
sub-windows too, from the UI thread (seen in an S9's framework.jar), which crashed 1.5.0 on rotations on Galaxies; the drawn
pad takes the fingers there. Elsewhere their views read resources nothing updates (`FrozenContext`). The stock emulator never
makes that call: test a change here by turning a Samsung phone in a game, many times, fast.
**Heat**: the STEADY background pass runs at most 10x real time (`BACKGROUND_FRAME_NANOS`; songs being heard go flat
out), and gives up on a song that failed on 2 launches (`FfMusicCache.failures`) - a failing song kept the pass, and a
second core, running on every launch.
**Clip storage (2026-10-28, players' caches ran to many GB)**: clips are FLAC (`FlacWriter`, our own encoder: fixed predictors
+ Rice, lossless so loops stay seamless - never a lossy format, whose padding gaps every loop; `FlacWriterTest` decodes it
back), every ROM's under one budget (`FfMusicBudget`: min(1.5 GB, a quarter of clips + free space)). Over it, never-played
(prefetched) songs go first, then those played longest ago (`.played` log at the cache root, noted by the engine when a song
starts under STEADY); the background pass only fills free room and stops for the launch when full. A ROM's WAVs from before
are converted when it next starts (`FfMusicCache.prepare`, half duty, giving way to a song being heard) and play meanwhile.
The player streams WAV: a FLAC clip is decoded first by Android's own decoder into the cache dir (`FlacPcm`, the last 6 kept), on
the player's thread (until 2026-11-03 it opened FLACs as WAVs and STEADY played nothing).
**Updater loop** (same report: the bar filled, nothing installed): with "install unknown apps" off, the settings page
failing to open left only a toast and UPDATE downloading again; now the installer opens anyway (it asks itself), and an
APK already downloaded for the offered release is reused (`AppUpdateFlow.check` keeps it).
**Frontend launch (Cocoon / iiSU / ES-DE)**: `LaunchActivity` (exported, translucent, no intent
filter, `taskAffinity=""`) takes the ROM as intent data or a `rom`/`ROM`/`path`/`file`/`uri` extra,
plays a readable real path in place (`RomUris.originalPath`; needs All files access on 11+), else
copies it into `roms/` once (skipped while the bytes match), then starts `PokeDaisyActivity` with
`EXTRA_FROM_FRONTEND` - exit is then `finishAndRemoveTask()`, back to the frontend. A different ROM
arriving while a game is open is switched in `onNewIntent` via `loadRom()` (the activity is paused,
so the engine already stopped/suspended) + `TelemetryStore.reset()` (the sampler caches the
detected game). `LibraryActivity` forwards a non-VIEW launch carrying a ROM there. Setup table in
docs/DEVELOPMENT.md's "Launch from a frontend" section. Not yet tried from a real frontend.

**First-time setup + linked ROMs folders**: `SetupScreen.kt` (state + UI; `LibraryActivity` owns the
pickers and threads) shows over the library while `Prefs.setupDone` is false and the library is empty,
or after Settings' RUN SETUP (`Prefs.setupRequested`): ROMs folders → saves folder (offers folders that
already hold `.sav`/`.srm`: the ROMs folders, RetroArch's per volume - `SavesLocation.suggestions`) →
SteamGridDB key (the shared `CoverSync` runner, also behind Settings > Cover Art). Every step is
skippable. The library is `RomFolder.libraryRoms`: imported `files/roms/` plus the linked folders'
(`Prefs.romsFolders`, several since 2026-10-27 - players link `gba` / `gbc` / `nds` rather than a whole frontend tree,
slow to scan; the old one-folder `roms_folder` pref is read once; a folder inside a linked one is refused, one holding
linked ones replaces them: `RomFolder.coveredBy` / `withFolder`) supported ROMs, played in place (never copied), rescanned off the UI thread on
every library resume (an unreadable folder keeps its last games), with `CompanionSupport` verdicts cached by path+size+mtime in
`filesDir/rom-folder-scan.tsv`. Any game can be HIDDEN from its library menu (`Prefs.hiddenRoms`, by path;
name, cover and saves kept; Settings > HIDDEN GAMES shows them again); linked ROMs have no DELETE (the
player's own files). The library's + is a pick-list: ADD A ROM FILE / ADD A ROMS FOLDER / MANAGE ROMS FOLDERS (Settings
opened alone on FOLDERS > ROMS FOLDERS, `SCREEN_ROMS_FOLDERS`). Settings > FOLDERS is a hub of four pages: ROMS FOLDERS,
GAME SAVES, ALSO LOOK IN, SAVE STATES. Same-named imported ROMs win (covers/names are keyed by file name). Setup opened by
RUN SETUP has a BACK (bottom-left) that returns to Settings from the first step. Folder picks
need All files access first (`StorageAccess`); setup resumes the pick in `onResume` on return.
**Zipped ROMs** (`RomArchive.kt`, `.zip` via java.util.zip, `.7z` via commons-compress + xz): the linked
folder and `files/roms/` list archives as-is; `RomIdentity` / `SaveStates.crc32` / `CompanionSupport`
stream the ROM out of them (never unpacked for that), and only playing extracts one into
`cacheDir/rom-archives/` (`RomArchive.playable`, last 3 kept; `PokeDaisyActivity.romData` is what the
core and the ROM readers load, `rom` stays the archive). Saves and the default library name follow the ROM
*inside* (`RomArchive.saveNames` / `baseName`: RetroArch names a zipped game's save that way), falling back
to a save already under the archive's own name. Imports (+, frontend copies, VIEW) unpack instead, by
magic bytes (`RomArchive.sniff`). Library Refresh toasts what the scan found (`RomFolder.ScanResult`).
The bottom-screen Presentation hides the system bars (`DualScreenPresentation.goFullScreen`).

**PATCH A ROM** (2026-11-01; the library's + menu and Settings > LIBRARY, NEW tag; a `.bps` picked with ADD A ROM FILE or
opened with the app goes there too): `BpsPatch` (pure BPS decode, source / target / patch CRCs checked), `RomPatcher` (the base
ROM found by size + CRC32 among `RomFolder.allRomFiles` - imported, every linked file of any verdict, hidden ones - CRCs kept in
`filesDir/rom-crcs.tsv`; the result goes to a linked folder named for its kind (`gba` / `gb` / `gbc` / `nds`, or one right
inside a linked folder), else the base's folder if linked, else `roms/`, named after the patch, ` (2)` on a clash, the same
ROM kept), `RomPatchFlow` + `PatchWindow` (Looking / NotFound / Ready / Patching / Done / Failed; hosted by LibraryActivity and
SettingsActivity, PLAY only in the library). An UNSUPPORTED result in `roms/` is hidden (HIDDEN GAMES shows it). BPS only: UPS /
IPS / xdelta aren't read. `BpsPatchTest` (a hand-built patch with every action, and the player's `FireRed QoL.bps`); ui-preview
`library-patch-*`.
**CHEATS** (GameShark / Action Replay / CodeBreaker, GBA only; docs/DEVELOPMENT.md's "Cheats"): per ROM in
`filesDir/cheats/<CRC32>.cheats` (mGBA's format, `CheatStore`), master switch `Prefs.cheatsEnabled` (off). Added /
imported on the top screen (Settings > CHEATS, or a game's library menu > CHEATS - a keyboard); the companion's SETTINGS
> CHEATS only toggles, live (`EmulatorEngine.setCheats` -> `pkCheatsApply` on the next frame). File formats are parsed
in Kotlin (`CheatFiles`: mGBA's own parsers choke on CRLF and drop bad lines silently); every code line is checked by
mGBA's parser (`pkCheatsCheck`, no core needed) and a bad one is reported, never dropped quietly. Only *enabled* cheats
reach the core, and taking them out undoes hooks and ROM patches (`pk_cheats_clear`), so with none on the ROM is the
file. They work with `USE_DEBUGGERS OFF` (frame-end refresh + the cheat device's BKPT component; checked headless with
mgba_dump's `cheat`, built against the app's flags). A hook / ROM patch would change what identifies the game, so
`pk_cheats.c` keeps the original bytes: `pkReadBytes` overlays them (the Poller's hack SHA1); RA hashes the file. RetroAchievements pauses while any cheat is loaded (`rc_client_idle` instead of `do_frame`; CHEATS ON /
OFF popups, PAUSED badge on the ACHIEVEMENTS tab). The render core `rg` never gets cheats.

**Instant companion on a resume** (`SnapshotCache`, `<state>.companion` beside every savestate, a few KB): a launch
that resumes from a state shows the party / bag / map / status bar as that state left them
(`TelemetryStore.showCached`, set before the engine starts) and the live data replaces it in place once it has data
(`hasData`); DEX / GUIDE / CARD need ROM tables, so only their tab chips are kept (`SnapshotView.cachedTabs`) and they
wait on LOADING. A resume that doesn't load (save changed) drops the copy. Icons read live from the ROM reload on
`DecompIconSource.generation`. The 5-6 s LOADING it replaced was mostly detection; retail FireRed / Emerald are now
recognised at once by their masked hash (`RETAIL_SHA1S`). The open tab (`selectedLabel`, `backTab`, `settingsUi`) lives outside `key(snapshot.game)`, so the
game being detected mid-launch no longer sends a player who opened SETTINGS back to PARTY.
**Save files are sacred**: an mGBA state carries the save as it was, and loading one
(`SAVESTATE_SAVEDATA`) writes that copy over the save file. So the auto-resume first checks
`pkStateMatchesSave`: if the file changed since the state was made (RetroArch played it, the saves
folder moved, a save copied in), the state is set aside as `resume.replaced` and the game boots
from the file (a real report: a resume state from a blank first boot wiped an Unbound save to all
0xFF). The save reaches mGBA through `pk_save_vf` (`pk_savevf.c`): mGBA maps it with a shared writable mmap and never checks the result, so a save opened read-only (another app's file without All files access) gave it NULL and the first flash read crashed (GBASavedataReadFlash on Crashlytics); a map the file can't give is a copy in memory now, written back on sync / unmap. Every start also copies the save to `<saves>/pokedaisy-backups/` first (`SaveBackups`, newest
10 distinct), which the library's RESTORE BACKUP lists. Settings > FOLDERS > SAVE BACKUPS (`Prefs.saveBackups` /
`saveBackupsKeep` / `saveBackupsDir`, read through `SaveBackups.settings`, NEW tag) can turn that start's copy off, keep 1-50,
or put every game's in one folder (RESTORE BACKUP lists both places); the save editor's backups ignore the switch (`force`). Never add a path that writes the save
without both.
A save the game is writing always finishes before the core stops (`pkFinishSave`, from EmulatorEngine before the suspend
state and before `pkDeinit`): frames run unheard while mGBA's savedata `dirty` flag is set. Leaving mid-"SAVING..." and
then swiping the app from recents used to leave the newer slot half written ("The save file is corrupted"; checked
headless on Emerald: 276 frames to finish).
**Sleep (screen off) doesn't stop the game**: `onPause` with the device non-interactive calls `EmulatorEngine.sleep`, which
finishes a save in progress, writes the resume state (in case Android ends the app meanwhile) and holds the loop between
frames with the core up; `startGame` wakes it (`engine.sleeping`) and drops that state - no core restart or state load on
wake (players saw every wake as the game restarting). HOME, another app or a close still stop it. A paused activity no longer
always means a stopped engine: check `engine.running` (onNewIntent, onDestroy do).
**Where a save is** (`SavesLocation.saveFor`, every save path goes through it): the game's own folder (library
menu > SAVE FOLDER, `Prefs.romSaveDir`, by file name; setting it copies the current save over if the folder has
none - never moves it), else the first of the saves folder and Settings > FOLDERS > ALSO LOOK IN (`Prefs.extraSaveDirs`)
holding one - played and written where it is, so RetroArch's per-core folders keep their saves - else a new one in the
saves folder. Only the folders themselves, never below (a user's synced RetroArch/saves pulled in Syncthing's
`.stversions` when FOLDERS listed recursively; that list now skips hidden folders). `SavesLocationTest` pins the order.
**SAVE EDITOR / save editing** (was PC BOXES, renamed 2026-11-03; a game's library menu, `SaveEditorActivity`, `companion/data/save/`,
docs/DEVELOPMENT.md's "PC BOXES"; on for everyone since 2026-11-01, its flag gone; the library's TOOLS has SAVE EDITOR (`EXTRA_PICK`: the game
picked first) and TRADE SAVES (`EXTRA_TRADE`: the two games picked, then the trade alone, BACK out to TOOLS - the user's call: it read as a page
inside the editor); manual `save-trade.md`, NEW tag `SAVE_TRADE`):
the save *file* read and written with the game closed, never game RAM. Writes go the game's way (`Gen3SaveFile.withBlocks`:
the other slot, counter + 1, rotation + 1, checksums over each chunk's real size) so the loaded slot stays the game's fallback,
and only after `SaveFileEdits` has a backup, a still-unchanged file and a read-back; then `boot_fresh`. Retail-layout games only
(`Gen3SaveFormat`, `Gen3SaveData.mismatch()` refuses other layouts). PC records move byte for byte; TRADE (`Gen3Trade`) keeps the
games' own rules and after-effects (pret's `trade.c` / `link.c`: Sevii link flag, National Dex / regional dex, eggs, Mew / Deoxys'
fateful bit, the Pokédex's three seen copies, trade evolutions + EvolutionRenameMon, R/S's National Dex) and writes both saves or
neither (`SaveFileEdits.writeBoth`). Icons come from the ROM file (`RomMonIcons`, known tables only - never guessed). Values
(money, coins, the bag, a Pokémon's held item and moves) take only what `Gen3Legal` allows (`SaveTablesGen.kt` from
`scripts/gen_save_tables.py`: each game's pocket items, learnsets, TM / tutor sets, pre-evolutions); the editor throws before a
byte changes otherwise, and key items / HMs are never taken away. Event flags (ticket islands) come next.
**REWIND** (Settings, `Prefs.rewind`, off by default; REWIND HOLD hotkey, R on a keyboard): `pk_rewind.c` - mGBA's own
diff ring (`mCoreRewindContext`, 600 entries, one every 2 frames: ~20 s, rewinds at 2x) but with states taken / loaded
WITHOUT `SAVESTATE_SAVEDATA`: mGBA's `mCoreRewindRestore` would write the state's save over the save file every
rewound frame. Muted and 1x while held; at the oldest entry the frame stands still. Our native target must get
mGBA's own `USE_PTHREADS` (CMakeLists.txt): without it mGBA's headers fall back to `DISABLE_THREADING`, our
`mCoreRewindContext` came out ~100 bytes short of libmgba's, and REWIND OFF ran Deinit on garbage thread / mutex fields
past `g` - the game froze and the core lock deadlocked the next close (an ANR on Glazed); `pk_rewind.c` `#error`s on it now. Verified on the API 32 emulator
(`Pixel_3a_API_32_arm64-v8a`, arm64 like the Thor: `adb root`, push a ROM and `chown` it to the app's uid, prefs via
`run-as`, hold keys with `sendevent` on the `qwerty2` device).
**TURBO A / B** (issue #32, GAME BUTTONS, unbound by default): `GbaControls.TURBO` marks them in the key map,
`GbaInput.turboMask` holds them, the engine presses them 2 frames on / 2 off in game frames (so FF mashes faster too);
none while a battle script steers. Binding a key to a button takes it off every other button (it used to stay on the
later one).
**NOT SUPPORTED / ASK FOR SUPPORT**: the library tags ROMs whose cached verdict is unsupported (`RomFolder.unsupported`;
imported ROMs are checked into the same cache by `checkImported`, linked ones by the scan); the companion's NOT SUPPORTED
page and the library INFO of such a ROM open `RomSupportRequest.url` - the website's game request form (`/feedback/`)
prefilled through its #hash with the file name, game code, size and SHA-1, like the website's ROM check.
**BEST EFFORT** (`BestEffort.kt`): an unsupported ROM of a Gen 3 base game (header code BPR/BPG/BPE/AXV/AXP) is read
through every `BestEffort.candidates` config of that family, **automatically** (the sampler every 5 samples while
unsettled; there is no TRY button). What a config would *show* is checked against the ROM, not just its RAM - a Galar hack
once read as Emerald with a real party and wrong names, sprites and map. `RomNames` scans the ROM once for every
candidate's names (loose: case, spaces, `-.'`, é/♀/♂ ignored - the shared tables use modern spellings) and
`RomNameTables` locates each config's species / move / item / map-section tables in it by votes on its names' offsets
(inline arrays, or pointer tables via a second pass), so names are checked **at their ids** wherever a rebuild moved
them. A `Fit` is SURE when its party reads, its species table agrees (0.95) with no extra species past its last, it covers
0.7 of the ROM's species names, its map names hold (or come from `RomRegionMap`), and what it shows (party, moves, bag,
current map) is confirmed - and no other game is as sure (`Verdict.sure`: fewer ROM `Part`s off, or 0.15 more of its own
names in the ROM, else it's the player's call). Parts (`DEX`, `GUIDE`, `ITEM_TEXT`, `MON_ICONS`, `ITEM_ICONS`) that don't
hold are switched off - no sprites rather than wrong ones. Before a party, a ROM whose names + map table point at one game
gets it as a guess (`Verdict.guess`), dropped after ~10 min without a party. Otherwise the companion shows **WHICH GAME
IS THIS?** (`GamePicker.kt`) in place of NOT SUPPORTED: ranked, LIKELY tags, NONE OF THESE (`BestEffortStore.decline`) /
ASK FOR SUPPORT; also reachable from the match notice's WRONG GAME?, SETTINGS > GAME > PLAYS AS and the library INFO's
PICK A GAME. A pick (`Source.PICKED`) is confirmed once a party reads, else a WRONG GAME? notice (PICK AGAIN / KEEP).
`best-effort.tsv` rows carry source + verified; 4-column rows from before are re-checked as guesses. `BestEffortTest`
pins it over 26 real ROMs + saves: each SURE of its own config, and with its own game's configs removed nothing is sure
except three engine siblings (Orange Islands / Odyssey -> FireRed rev 0, Unbound -> Amethyst), every name confirmed.
Library: `CompanionSupport.verdict` (SUPPORTED / MATCHED / PARTIAL / TRYABLE / UNSUPPORTED) cached by `RomFolder` as
1/M/P/T/U; linked-folder TRYABLE ROMs are listed with NOT SUPPORTED, PARTIAL ones tagged PARTIALLY SUPPORTED.
**Sharing a BEST EFFORT match** (opt-in, `BestEffortShare.kt`): the fresh-match notice asks (`BestEffortShareNotice`,
listing `BestEffortShare.fields` value by value); only SHARE sends - one Firestore REST create into project `pokedaisy`,
collection `bestEffortReports`, no SDK / API key / ids: sha1, size, gameCode, revision, matchedAs, full, off,
appVersion (`BestEffortReport`), values regex-checked before sending (`BestEffortShareTest`). Offline: queued in
`filesDir/best-effort-share-queue.jsonl`, sent on the next activity start; a 4xx drops it. `website/firestore.rules`
accepts exactly that shape and nothing can read it back - change the three together. Firestore must be enabled in the
Firebase console and the rules deployed (`cd website && firebase deploy --only firestore:rules`) for sends to land.
**Issue #31 (STATUS BAR over a portrait game crashed)**: `PortraitPanel` sized itself in `onResume`, measuring the bar's
ComposeView before it had a window ("Cannot locate windowRecomposer") - `gameHeightFor` counts an unattached bar as 0
now. The relayout that follows resized the GLSurfaceView twice before its first frame, and GLSurfaceView keeps only the
latest `surfaceRedrawNeededAsync` callback, so the window never reported drawn and Android 12's splash stayed over the
game: `EmulatorView` runs every pending one with the next frame.
**Library menu: LOAD SAVE / INFO / HIDE**: LOAD SAVE (`GameSaves.load`) renames the current save to
`<rom>.backup-<yyyyMMdd-HHmmss>.<ext>` beside it and writes the picked file under the name the game reads,
then drops `SaveStates.freshBootFile` so the next start boots from the save instead of resuming (a resume
or manual state holds the old progress and would write it back). INFO is `GameInfo` (quick pass, then a
second with CRC32/SHA1/companion check). **Releases + updater**: `versionName` is semver (`1.0.3`),
tags are `v<versionName>`, GitHub releases with the APK attached; `AppUpdater` picks the highest-versioned
release with an `.apk`, skipping drafts and pre-releases (publish a test build as a pre-release to keep it from users) and `AppUpdateFlow` downloads it into `cache/updates/` and opens the installer via the
`${applicationId}.updates` FileProvider. Release builds check on every library open, debug builds only from
Settings > VERSION. Release signing: untracked `keystore.properties` → `~/.android/pokedaisey-release.jks`
(alias `pokedaisey`: the key predates the rename, kept as is)
on this Mac (same key for every release, or updates won't install).

**Game Boy / Yellow is on** (`TelemetrySampler.GAME_BOY_SUPPORT = true`; it was off in v1.1.2): `.gb` /
`.gbc` are in `RomArchive.ROM_EXTENSIONS` (library, folder scan, frontend launch, archives) and the
manifest's VIEW patterns. Setting the flag false switches it all off again (drop the two manifest lines
and the README row with it); the Poller then reads a Yellow cart as unsupported.
**Performance guards**: fonts come only from `PixelTypeface.kt`, built once per process (v1.1.0 built
a 1 MB typeface per `GbaText` and crashed when tabs were switched quickly); `PerformanceGuardsTest`
checks that, plus the GL unbind before `pkDeinit` and the native core lock. `cd ui-preview && gradle
stress` switches the companion's tabs every 2 frames and fails over its memory / time budgets (it
skips desktop Compose 1.5's own OnPositionedDispatcher NPE, which the app's 1.6.8 doesn't have) - CI runs
it; after touching anything a tab composes, ask the user before running it yourself. Per-visit work belongs in process-wide caches (slot art,
backdrops, card renders, state thumbnails), not `remember`, which a tab loses each time it leaves.
**Weak devices (a Galaxy Tab A, Android 11, Mali-G71, 2026-11-08: tab switches went from 150-1450 ms to 75-150 ms)**: `GbaText`
is one Text node drawing its three shadow copies from its own layout (`drawBehind` + `drawText`; four Text nodes laid every word out four
times). Never `clip()` a large area to a pixel shape: Android 11's renderer draws a path clip as a CPU mask every frame (the companion
window's cost 160 ms a frame) - `pixelClip` (smooth while LIGHT COMPANION is on), and layered backgrounds draw `background(color, shape)`
instead of clipping. Composition must not touch the disk or a system service (`MonArt.available` is cached, `secondScreen()` kept half a
second): SETTINGS' getters run row by row. The touch pad (and, with LIGHT COMPANION, the companion window) sits in an offscreen layer
the renderer keeps. A view over the stage keeps a fixed size (`WRAP_CONTENT` with changing text re-measured the whole stage). The
`benchmark` build is profileable from adb; profile a debug build with simpleperf's `app_profiler.py` (`-e task-clock` on that tablet).
**Crash reports** (`CrashReports.kt`, Firebase Crashlytics + -ndk): opt-in, off by default (manifest
`firebase_crashlytics_collection_enabled=false`); on via Settings > CRASH REPORTS or the Library's one-time ask
(`CrashReports.shouldAsk`: third app open - `PokeDaisyApp` counts process starts - and 48 h after
`firstInstallTime`; shown once ever, `Prefs.crashReportsAsked`). The Firebase plugins apply only when
`app/google-services.json` exists (gitignored - public repo; the release workflow writes it from a secret), so
forks / PR builds have `BuildConfig.CRASHLYTICS = false` and no row or ask. ui-preview stubs `CrashReports`.
**CI / releases** (docs/RELEASING.md): `.github/workflows/ci.yml` = required PR checks (`make check`: unit tests,
debug APK, ui-preview stress + docs search - no render: its PNGs are for looking at, `make preview`); ROM-backed tests skip there and run on the release's self-hosted
`pokedaisy-roms` runner (`POKEDAISY_ROM_DIR`, forwarded to the test JVM as `romDir`). `release.yml` fires on a
`v<versionName>` tag from `make release`, checks the signing cert against `RELEASE_CERT_SHA256` and publishes.
Collaborators get write access, the only level a personal repo has, so everything else is held back (2026-10-25,
RELEASING.md's "Collaborators with write access"): main / dev need the owner's review (CODEOWNERS), only the owner makes
tags, the release waits for the owner's approval, the Firebase key sits in a `website` environment that only runs on `v*`
tags (no PR previews), releases are immutable (every release goes up as a draft, which the owner publishes: notes and other platforms' files go on before), and the ROM runner's job check
(`scripts/runner_job_check.sh`) refuses all but the owner's release. Never move a secret back to repo level.
**Collection dumps of retail** (`RetailDumps`, `RetailDumpsGen.kt` from `scripts/gen_retail_dumps.py`): English
FireRed rev 0 / 1 and Emerald with the end's padding trimmed and / or a flash-cart patch (flash code + its chip descriptors,
Emerald's siirtc, the save warnings reworded, AgbMain's WAITCNT value) and things of a repack's own in retail's empty space
(64 KB+ runs of 0xFF) read as retail - library, `detect()` and the website's `retailDumpOf` alike: bytes [0, data end) with
those ranges (and the GPIO bytes) zeroed hash to the table's SHA-1. Anything else changed stays a hack.
The pokefirered map in `$DECOMPS` isn't byte-identical to retail (code 0x93C off), so FireRed's ranges are found by bytes.
**Randomized retail (2026-10-31)**: English FireRed rev 0 / 1 and Emerald run through Universal Pokemon Randomizer ZX 4.6.1
(any settings / seed) read as retail (`RandomizedRetail.kt`, `RandomizedRetailGen.kt` from `scripts/gen_randomized_retail.py`
with `UPR_SRC` = a UPR checkout, facts only - it's GPLv3): bytes [0, end) with the species names upper-cased (`fold`) and
every site UPR may write zeroed (`mask`: its offsets file, IPS records and map / table scans redone on retail, strided, on top
of `RetailDumps`' ranges) hash to `sha1`; `--check <folder>` proves a batch of outputs inside it. Retail's config,
`ramVerified`, retail's shiny check; `randomizedRetail` (ActiveTables, a `HackText`, `runningRandomized` / `gameRandomized`)
carries types, growth rates, gBattleMoves (+ `MoveStats`, effect names `vanillaMoveEffectNames`), the type chart and the
GUIDE's item / trade / gift species read from the ROM. Retail-data probes skip on it (BULBASAUR's evolution and TMs, the
guide's probe trainer name: UPR renames BROCK). Hand-written guide lines naming what a randomizer moves are
`Randomized.HIDE` (gifts, legendaries, stones), TIPS has a `Randomized.ONLY` line; `gameGuide(id)` filters. EXPAND / field
reads go by retail's CRC (`WideScreen.tablesCrc`; mgba_dump `wideon MARGIN CRC`). Library: "Pokémon FireRed (Randomized)";
website: `compat.json`'s `randomizedRetail`. docs/DEVELOPMENT.md's "Randomized ROMs" (and how to redo it for a new UPR).
**Retail engine (2026-11-07)**: an unrecognised BPRE (rev 0 / 1) / BPEE hack edited in place is proven by its code, not its hash
(`RetailEngine.kt`, `RetailEngineGen.kt` from `scripts/gen_retail_engine.py`: per RAM global up to 16 retail `ldr pc` sites, CRC32 of
the code around each, pool word must still hold the address; CRCs and offsets only). Proven: retail's cursors (`knownCursorAddrs`) and
gPartyMenu (`retailEnginePartyMenu`) while BEST EFFORT reads it as retail FireRed / Emerald, and EXPAND / field reads by a row rebuilt
from retail's with its repointed ROM tables followed (`WideScreen.tablesCrc` -> `pkWideAddDerived`; the far-off people's tables may
drop to 0). Rerun the generator after touching retail's rows in pk_wide_games.h; `--row HACK` + mgba_dump's `widerow` checks one
headless. `RetailEngineTest` rebuilds every in-place hack's hand-made row and refuses the rebuilds. docs/DEVELOPMENT.md's "Retail engine".
**Unsupported ROMs**: `CompanionSupport.isSupported(file)` (FireRed/Emerald game code, ≤16 MB,
retail LeafGreen rev 0/1 / Ruby / Sapphire rev 1/2 — `TelemetrySampler.OTHER_RETAIL_CODES`,
read as FireRed / Emerald — or a >16 MB hack whose SHA1 is in `TelemetrySampler.SUPPORTED_HACK_SHA1S`) mirrors the
Poller's live `detect()`. The library asks "add anyway?" before importing a ROM that fails
it; in game, `SnapshotView.unsupported` swaps every companion tab but SETTINGS for a
"not supported" notice. Adding a hack to `detect()` means adding its hash to that set too,
and its name to `GameTitles.BY_SHA1` (`GameTitlesTest` checks), its version in the `*_V<ver>_SHA1`
constant's name, and regenerating the website's `website/src/data/compat.json` (`SiteDataExportTest`
fails while it's stale: `UPDATE_SITE_DATA=1 ./gradlew :app:testDebugUnitTest --tests '*SiteDataExportTest'`).
**Website** (`website/`, Astro, static, Node 22.12+): its ROM check (`src/lib/compat.js`) runs
`isSupported`'s rules in the browser over that JSON - the ROM is never uploaded; keep the two in step.
Deployed to Firebase Hosting (`pokedaisy.com`) by `.github/workflows/website.yml` **only with an app release**
(the user's rule: the site never lists games the downloadable APK can't run): a published, non-pre-release release
deploys its tag's `website/`; pushes to main only build + test; a manual run takes a release tag (redeploy / roll
back). A site-only fix therefore waits for the next release (or a manual run of the current tag after moving it); the checker's ASK FOR SUPPORT opens the feedback form, prefilled through its #hash.
The site's game matrix is README's Supported games table; a row of all "—" means detected but no companion yet,
so the export lists it under `inProgress` - off the matrix, and "in progress" in the ROM check (none since R.O.W.E.
got its companion, 2026-10-09).
The site's demos and art never use real Pokémon / move / item names or the games' art (the user's rule).
**Website languages** (2026-11-07, website/README.md's "Languages"): the app's nine (EN / JA / KO / FR / DE / IT / ES / pt-BR / pt-PT),
English at the root, the rest under `/<code>/` (`src/views/` drawn by `src/pages/` and `src/pages/[lang]/`); the manual and
release notes stay English, but for pages translated in all eight (`docs/manual/translations/<code>/`, `/<code>/docs/<page>/`: the 3DS and
Linux install guides, `3ds.md` / `linux-install.md`, whose GitHub copies `3ds/INSTALL*.md` / `linux/INSTALL*.md` are made from them by
`website/scripts/install-guides.mjs`: its `GUIDES` list). Site text goes through `tr()` / `msg()` with its eight translations in `src/i18n/<code>.json`
(same writing rules: no dashes), else `test/i18n.test.js` fails; Japanese needs `website/scripts/gen_site_jp_font.py` for a
new character, Korean `website/scripts/gen_site_ko_font.py` for a new syllable. The picker's choice is `localStorage` `pd-lang`, else the browser's language (on English pages only).
**Website analytics** (`website/src/lib/analytics.js`, website/README.md's "Analytics"): anonymous GA4 counts, no cookies, a fixed
event list checked per value, live site only. gtag.js sends nothing without its cookie, so the page posts gtag's own hit itself;
`MEASUREMENT_ID` empty turns it off. A new tracked action goes on `EVENTS` with its allowed values (and the README's list).
**Feedback without GitHub** (`website/src/pages/feedback.astro`, `/feedback/`): game request / bug / idea, written
to Firestore `feedback` (write-only, `firestore.rules`) and posted on GitHub by `website/functions/index.js` (game requests /
ideas as discussions in the `game-requests` / `ideas` categories via GraphQL, bugs as issues)
(2nd-gen Firestore trigger, secret `GITHUB_TOKEN`, Blaze plan; setup in website/README.md). One definition,
`functions/feedback-form.js`, feeds the page, the function and the post layout (mirrors .github/DISCUSSION_TEMPLATE and
bug_report.yml); change it with the rules (`test/feedback.test.js` checks they agree). The contact never reaches the post.
The site's REPORT A BUG / REQUEST A GAME / SUGGEST A FEATURE go there (it links on to GitHub).
**GitHub layout (2026-10-18)**: Issues are for bugs (and accepted work); game requests and ideas are Discussions
(Game requests / Ideas, open-ended, upvoted). Labels: type `bug` / `game request` / `feature request` / `question`, topic
(`companion`, `display / expand`, ...), status `fixed in next release` / `needs info` / `planned`. `rom_request.yml` stays
only for older apps' ASK FOR SUPPORT; its issues get converted to Game requests. Astro needs Node 22.12+ (this Mac's
default 22.1 can't build it: use `~/.nvm/versions/node/v22.23.3/bin`).
**Answering GitHub issues** (the user's rules, gathered from every issue batch so far):
- **Draft first, post after.** Show the user every reply (in a batch: all of them at once, issue by issue) and post
  only after a clear OK. Then post, add the label, and close if they say so ("post and close" is the usual).
- **Short and plain.** Two or three sentences: thanks, what the player will see, when. No causes, internals, file
  names, emulator terms, measurements or a list of what was changed. The reporter wants to know it's handled, not how.
- **Future tense until it ships.** A fix that isn't released yet must never read as if it's out: "will show under GBC
  starting with v1.3.1", never "now shows under GBC" or "is fixed".
- **Name the next version** ("v1.3.1": `versionName` in `app/build.gradle.kts` once it's bumped; ask if it isn't).
- **No follow-up promises.** Never "I'll post here when it's out": the user doesn't go back to closed issues. Point to
  the repo's releases instead: "To get notified when it's out, watch the repo's releases (Watch > Custom > Releases)."
- **Their words, their game names.** Call a game what the reporter called it (no added "Pokemon"); thank anyone who
  added useful details in the thread by @name.
- **No "é", ever.** Pokemon, PokeDaisy, Pokedex, Poke Ball: anything with "Poke" in it is written without the accent in
  every reply (issues, discussions, PRs), whatever the app's own UI says.
- **Not fixed yet?** Ask for exactly what's needed, as a short numbered list, and leave it open (`needs info`).
- Labels: `fixed in next release` on a fixed one. Game requests and ideas go to Discussions (above); exact duplicates
  are closed pointing at the original.

**Support window + WHAT'S NEW (2026-10-27)**: the Library asks for support once (`SupportAsk`, `ui/SupportWindow.kt`): after 5 h of
play (`Prefs.playMillis`, counted by PokeDaisyActivity's play clock while a game runs in front, not asleep), REMIND ME LATER (or
BACK) +5 h, NO THANKS or a site (BUY ME A COFFEE / PATREON / KO-FI, the website's links) ends it; never on WHAT'S NEW's visit or
over an update offer / setup. The user's calls: no play time shown (it read as tracking), no line under the site names, no
scrolling on any screen (a short paragraph and COFFEE on the RG DS). WHAT'S NEW: the flower, the version as a red pill, NEW /
FIXES bands, daisy bullets, and every line leads with its feature and a colon, drawn red (`WhatsNewTest` checks the lead-in).
**Library GAMES / TOOLS / SETTINGS** (`companion/ui/LibraryTools.kt`, 2026-11-02; the players' pick in a Discord poll over a
TOOLS pill by the quick views): `LibraryNav` is a rail down the left of a wide library (its frame open at the screen's bottom) or a
bar along the bottom of an upright one; TOOLS (`LibraryActivity.toolsOpen`, BACK closes it) shows `ToolsPage`'s cards in the
games' place (`libraryTools()`: PATCH A ROM, LINK PLAY, SAVE EDITOR, TRADE SAVES - a new tool goes there and in the manual's "Games, tools and
settings"; never a Settings row: the TOOLS group left Settings on 2026-11-03, the user's call), a green dot on TOOLS while one is NEW; SETTINGS left the header for it. Docked to the
screen's edge (2026-11-03, the user's call: the frame only on the side facing the page; 16 px coloured `ToolIcons` at
NAV_ICON GBA pixels, not 9 px glyphs). TOOLS' header keeps only HELP. SETTINGS is a tab: SettingsActivity with `EXTRA_TAB`
(started with no animation, `settingsTab`) draws the same rail / bar at the library's text step (`LibraryLook`) and the
POKéDAISY header over HOME; its GAMES / TOOLS hand `EXTRA_SECTION` back and finish without a slide. The launcher's legend
floats over an upright bar. ui-preview `library-tools*`, `settings-tab*`, `launcher-portrait`.
**Library order + NEW** (`LibraryOrder.kt`): the header's sort button picks NAME / LAST PLAYED / DATE ADDED
(`Prefs.librarySort`, a `LibrarySort` id). LAST PLAYED reads `Prefs.romLastPlayed` (set by `pushRecentRom`, so frontend
launches count), older plays by their place in the recent list. DATE ADDED and the green NEW tag (24 h) come from
`RomAdded` (`filesDir/rom-added.tsv`): `listRoms()` dates every ROM the library lists for the first time - as `initial`
(dated, never NEW) while first-time setup runs or a newly linked folder's first scan lands (`linkingFolder`), and for
the whole library on the store's first write (an app update mustn't tag everything NEW). DELETE forgets the date.
`RomAddedTest`; ui-preview `library-new*`, `library-sort*`. Favorites (`Prefs.favoriteRoms`, by path; FAVORITE /
UNFAVORITE at the top of a game's menu - short: Material menus cap their width) come first whatever the order
(`sortLibrary`), a gold star (`OptionColors.favorite`) beside the title. A long press opens a game's menu in the list
as in the grid, with the device's long-press haptic (`longPressMenu` - the library's one haptic; the companion stays
click-sound only). The menu goes to two columns (down the first, then the second, DELETE last) when one wouldn't
fit the screen's height and two fit across it (`screenSize`, measured by the library screen). ui-preview
`library-favorites*`, `library-list-menu*`. The library's lists scroll out to the screen's edges (their 16 dp margin is
`contentPadding`; the RECENTLY PLAYED row bleeds past it, `bleedHorizontally`), so cards slide off the screen instead of
being cut at an inner box. D-pad focus (`gbaFocusRing`) is a ring flush inside the element's own outline (pass the frame's real corner radius: the
library cards 2u, OptionButton 3u - a rounder ring let the frame's corners show), layout untouched - a border +
padding shrank the focused item - in a bright orange (`AppColors.focusRing`, the user's call; it was cyan).
D-pad in the library: focusing a RECENTLY PLAYED tile brings the whole section (its title too) into view, and UP from
the games' top row (those tiles, else the first list row / grid row 0) goes to the header's leftmost button
(`toHeader`, a `focusProperties { up }` on those cards - the lazy list's top row had nowhere to go). ui-preview can't
check D-pad moves (desktop Compose doesn't move focus on arrow keys); the emulator can: `input keyevent KEYCODE_DPAD_UP`.
**A game always starts on the main display** (`Screens.mainDisplayLaunch`: `setLaunchDisplayId(DEFAULT_DISPLAY)` +
NEW_TASK, from the library and `LaunchActivity`): opened on the bottom screen, the game once ran there with the
companion over it and nothing on top. Checked on the API 32 emulator with an overlay display
(`settings put global overlay_display_devices 1280x960/240`, `am start --display 2`, `input -d 2 tap`; a pushed ROM
needs `restorecon -R /data/media/0/Android/data/<pkg>` or the app can't read it).
**The app itself opens on both screens** whichever one its icon was tapped on: a library on the second display
(`Screens.reopenOnMainDisplay`, from `LibraryActivity.onCreate` / `onResume`) reopens its own intent as a fresh task on
the main display (NEW_TASK + MULTIPLE_TASK) and removes the old task, guarded by `MOVED_TO_MAIN_DISPLAY_AT` (5 s) so a
move Android ignores can't loop; a running game whose task was brought to the second screen asks for its task back on
the main display (`Screens.bringToMainDisplay` from `PokeDaisyActivity.onResume`, once per 5 s, no restart). The
launcher never puts its Presentation on the activity's own display (`syncLauncher` needs `isOnMainDisplay`). Not yet
tried on a device: the overlay-display emulator above can (`am start --display 2 -n com.pokedaisy.app/.LibraryActivity`).
**Library names** (`GameTitles.kt`): a game shows as its own name ("Pokémon FireRed") - the player's
RENAME first, then the whole-file SHA1 looked up in `GameTitles.BY_SHA1` (retail from the pret
decomps' `*.sha1`, every supported hack), else the file name minus dump tags (`tidy`: "(USA, Europe)",
"(v1.3.1)", "1636 - ").
Hashing runs off the UI thread (library resume, folder scan, import, a frontend launch's status bar),
cached by path + size + mtime in `filesDir/rom-titles.tsv`; `GameTitles.label` only reads that cache.
**Library covers (SteamGridDB)**: `SteamGridDbGames.forRom` matches known hacks by SHA1, then
≤16 MB FireRed/Emerald ROMs by game code (so retail gets art). The
automatic pick is the preferred uploader's icon (`PREFERRED_AUTHOR_STEAM64`, the user's
choice) before the top-voted one; the long-press REPLACE COVER window (`CoverPicker.kt`)
lists every icon and can search other games. steamgriddb.com is unreachable from cloud
sessions — use `FakeCoverSource` in ui-preview to look at the picker.
**RetroAchievements box art** is the first source (`CoverArtSync.fetchOne`, the user's call: RA wherever it
has box art, then SteamGridDB for the games it knows): ROM MD5 -> `r=gameid` -> the Web API's
`API_GetGame.php` `ImageBoxArt` (square, e.g. FireRed's 320x320 `001918.png`), which needs the
player's own **Web API key** (`Prefs.raWebApiKey`, Settings > Cover Art - not the sign-in token; RA's
no-login calls only give the 96 px icon). It's the picker's first tile too. `Prefs.romCoverSource`
records which source an automatic cover came from, for INFO.
**ScreenScraper (game media, first for covers - the user's call)**: **merged but OFF** until the app has its developer
login (`FeatureFlags.screenScraper`: `-Ppokedaisy.screenScraper=true` / local.properties, or the device marker
`files/features/screenscraper`; read into `ScreenScraperClient.enabled` at process and library start). Off, no account
reads as signed in (`Prefs.screenScraperAccount` is null), so nothing is fetched, and the Settings row, setup's GAME MEDIA
step (`SetupState.steps`), the update popup (`screenScraperAsked` stays unset, so it still shows once it's on) and the
launcher's sign-in hint are hidden; its search words sit in `FLAGGED_SETTING_KEYWORDS`. Turning it on for players: flip the
build flag's default (or drop the flag), move that keyword line back into `SETTING_KEYWORDS`, and restore the manual /
README text the commit "ScreenScraper behind a feature flag" took out (`git show` it). `ScreenScraperClient` (API v2, `jeuInfos.php` by
CRC / MD5 / SHA-1 / size / file name, then `jeuRecherche.php` by the library name, then by the base game's title) with
two logins on every call: the app's developer account (`BuildConfig.SCREENSCRAPER_DEV_ID` / `_PASSWORD`, from untracked
`local.properties` `screenscraper.devId` / `devPassword` or the release workflow's secrets - never committed) and the
player's (`Prefs.screenScraperUser` / `Password`; the API has no token, so the password is kept, never logged - a media
URL carries it too). `GameMedia` keeps each ROM's set under `<external files>/media/<rom name>/` (box, box 3D,
screenshot, title, wheel, fanart, cartridge, video + `info.json`; regions by the header's language letter) and
`CoverArtSync.fetchOne` fetches it before anything else: its box art replaces an automatic RA / SteamGridDB cover, never a
manual one. One fetch at a time app-wide (free accounts get one thread); a pass stops asking after a fatal answer
(bad login, quota: `ScreenScraperPass.stopped`). Sign-in is shared (`ScreenScraperLogin` / `ScreenScraperUi.kt`):
Settings > ScreenScraper, setup's GAME MEDIA step and the update's one-time popup (`Prefs.screenScraperAsked`; setup
sets it). ScreenScraper answers most refusals as HTTP 200 + a French line (`parseAnswer` / `refusalCode`: "non trouvée"
skips the game, quota / closed / login stop the pass) and sometimes sends a trailing comma; a name search only takes a hit
whose name has the query's telling words (`nameMatches` - else a hack got its base game's box); Game Boy carts try GB then
GBC. **Check coverage of every supported game** with `cd ui-preview && SS_USER=… SS_PASSWORD=… SS_DEV_ID=… SS_DEV_PASSWORD=…
gradle sscheck` (by GameTitles' SHA-1s, then names; `build/sscheck/report.md` + `media/`), then `gradle render -Ponly=launcher
-PssMedia=build/sscheck/media` for the launcher on real media. Cloud sessions can't reach screenscraper.fr (the egress
policy refuses it): there `fakeMedia` draws stand-in media (never real art).
**Dual-screen launcher** (`LauncherScreen.kt` + `LauncherHost.kt`, `LibraryActivity`'s launcher section; Settings >
DUAL-SCREEN LAUNCHER, `Prefs.dualScreenLauncher`, on by default): with a second screen the library is the game list on
one screen and the selected game's media on the other (`LauncherMediaPane`: two layouts by aspect, the story
auto-scrolls on an infinite transition, the video starts after 1.2 s on a TextureView) - **only with ScreenScraper on**:
while its flag is off there is no second-screen window (`syncLauncher` dismisses it), no SWAP (X, the legend) and
`launcherSwapped` is ignored - the list on the main screen alone. The list is the classic library's own
pieces (`RomRow` / `RomTile` / RECENTLY PLAYED with a `LauncherCard`: the launcher's cursor, a tap moves it there and plays at once, ⋮ /
long press = B's menu), list or grid (`gridView`, shared), the path under a row on the main display only.
`LauncherState` keeps the cursor on the strip (`recentCursor`) or in the games, by grid row (`columns`). One `LauncherState`
drives both windows; the second is a `DualScreenPresentation` (never focusable, it forwards keys), so **every key goes through
`LibraryActivity.dispatchKeyEvent`**, which moves `launcher.selected` itself - no Compose focus, the D-pad works
whichever screen holds the list. A = play, B = the game's menu (`LauncherMenuOverlay`, two columns when it doesn't fit),
X = swap (`LauncherState.swap`: out 130 ms toward the other screen, trade, in 210 ms - needs the main window's frame
clock), Y = Settings, the floating `ButtonLegend` bottom right of the list says so. The classic library's GAMES / TOOLS /
SETTINGS rail (`LibraryNav`, `ToolsPage`) is in the list too, driven by these keys (`LauncherState.navCursor` / `toolCursor`):
LEFT off the left edge onto it, TOOLS in the games' place, its header without the gear (the Thor never showed TOOLS before:
the rail was the classic library's alone, 2026-11-03). While a library window is open
(`libraryDialogOpen`) keys go to Compose as before; the windows (`LibraryDialogs`) always draw on the main screen.
ui-preview: `launcher-*` shots (`-Ponly=launcher`), `LauncherHost.kt` shimmed (`CapturedSecond`).

The app's only font is **Pixel Operator** (`assets/fonts/PixelOperator.ttf`, CC0,
`pixelFontFamily()`). Its caps are ~0.56em (Press Start 2P, which it replaced, was
~0.88em), so plain `Text` sizes are ~1.5x what the old font needed. Japanese glyphs fall
back, glyph by glyph, to `PixelMplusJP.ttf` (`PixelTypeface.kt`, Typeface.CustomFallbackBuilder,
Android 10+): PixelMplus10 (M+ FONT LICENSE) re-declared at Pixel Operator's 1600 upm by
`scripts/gen_jp_font.py`, so both share the 100-unit pixel, and cut to Japanese only - anything
else Pixel Operator lacks (½, ◀) still falls to the system font, as before. Hangul falls to `GalmuriKO.ttf` after it the same
way (Galmuri9, OFL, by `scripts/gen_ko_font.py`: its syllables are 9 pixels, Pixel Operator's caps' height).

**European Emeralds** (BPES / BPED / BPEF / BPEI; `NATIVE_EMERALD_ES` / `_DE` / `_FR` / `_IT`): English's RAM and
save layout, their own ROM tables, and **names in the game's language** (the user's call): species / moves / items /
natures / map sections from each ROM (`scripts/gen_emerald_lang_tables.py` -> `EmeraldText<Lang>Gen.kt`), switched by
`NativeConfig.language` -> `romLanguage` -> `localEmerald` (types are keyed in English - colours, the chart - and drawn in the game's words by `typeLabel` /
`typeInText`, data/TypeNames.kt, GitHub #40). The dex page
follows each game's wording and metric units; party art is per language (`partyem_<lang>/`); no TRAINER CARD yet. See
docs/DEVELOPMENT.md. **Japanese Emerald** (BPEJ, `NATIVE_EMERALD_JA`) is its own build: every RAM global and the battle
code moved (structs didn't - English's save loads), shorter ROM records (dex entries 0x1C, trainers 0x20, items 40,
names 6/8/10 bytes - `PokedexTables.entry*`, `GuideTables.trainer*`), kana text (`romLanguage == 'J'` switches
`Gen3Text` to its Japanese table; line breaks = full-width spaces), and the app's palette party slot (its fonts aren't
the Western ones the slot art draws with).

**Other-language FireRed / LeafGreen / Ruby / Sapphire** are generated, not hand-ported: `scripts/port_retail.py
<roms dir>` maps each ROM's addresses from its English one (literal pools, code bytes, content fallbacks, layout
detection) into `gamedata/gen3/ports/*.toml` (`RETAIL_PORTS` by code + rev, consulted first by `otherRetailConfig`) +
`GameText<Code>Gen.kt` (names), and `scripts/verify_ports.py` checks every one headlessly against English (the English
saves load in them) -> `build/ports/report.md` / `fields.png`. Rerun both after touching a FireRed / LeafGreen / Ruby /
Sapphire config. `GameText` (EmeraldLanguages.kt) is keyed by game code (`NativeConfig.gameCode` -> `romGameCode` ->
`localText`). Japanese FireRed / LeafGreen can't read the English saves: verify_ports boots them with Japanese
ones (`firered_ja` / `leafgreen_ja`) and checks them absolutely.

**Languages** (`companion/i18n/`): the app's own text in EN / JA / FR / DE / IT / ES / BR / PT / KO - not game
data (species / moves / items / types / statuses, which come from each ROM in its own language).
**GUIDE text** (issue #35: a French game's guide was half English) is translated for the games that come in
other languages - FireRed / LeafGreen, Emerald, Ruby / Sapphire, Unbound (FR), Quetzal (ES): `trGuide()`
(`i18n/GuideText.kt`) looks a whole hand-written line (entry, hint, answer, note, boss, HERE's building names) up
in `i18n/guide/GuideText<Game>.kt`, whose names are the localized games' own (from their ROM tables). Separate
from the app's `Tr*.kt` (not checked by check_translations.py); `GuideTranslationsTest` fails while one of
those guides has a line without all five languages (Portuguese is optional there: `Tr.br` / `.pt` default null, so
guides show English in Portuguese) and lists them in `app/build/guide-missing/`. A new or
changed line in those guides needs its translations; English-only hacks' guides stay English.
`tr("ENGLISH")` looks the English up in the `Tr<Area>.kt` tables (one line per entry, parsed by
`scripts/check_translations.py`; `TranslationsTest` runs the same check), `tr("{0} LEFT", n)` for
arguments - never a `$template` key. `tk()` marks a string the code compares (selector options,
enum labels, GUIDE state keys) - it stays English and the OPTION pieces (OptionLine label/value,
OptionTitleWindow, OptionButton, OptionBadge, group titles) translate what they draw. `L10n.language`
is Compose state: a pick redraws both screens at once. Prefs.appLanguage AUTO = the ROM header's
language letter (BPR**E**/F/D/I/S/J - every ROM the companion supports today is E) in game, the
device's in the Library / Settings. **Portuguese** (GitHub #61) is two languages, BR (Brazil) and PT (Portugal), picked by
the device's region (`ofLocale`); no ROM is in Portuguese (`romLetter` null), so a Portuguese device's AUTO wins over an
English game too. Both use the Brazilian games' terms (MOCHILA, GOLPES, PS, MT); PT only swaps everyday words (ECRÃ,
GUARDAR, FICHEIRO, DEFINIÇÕES). **Korean** (KO, 2026-11-09): rom letter K, so the Korean DS carts pick it; the Korean games'
terms (가방, 도감, 기술), labels as nouns, sentences in 해요체, no particle right after a `{0}`; GUIDE text stays English
(`Tr.ko` optional there, like BR / PT). Japanese is kana only, like Gen 3's; translations use the
localized games' own words and stay about as short as the English (chips and buttons are sized
for it - `LocalizedScreenshotTest` renders the main screens per language). New UI text: wrap it
and add its eight translations in the area's table.
