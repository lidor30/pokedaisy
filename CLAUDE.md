# PokeDaisy — Project Notes for Claude

Dual-screen Android app (AYN Thor, Retroid Pocket Duo): an embedded libmgba core on the
top screen and a live companion for Gen 3 Pokémon games and ROM hacks on the bottom
screen. Read `README.md` for the user-facing feature list and `docs/DEVELOPMENT.md` for the
per-ROM support details, how each game was mapped, frontend setup and releasing; this file is
process context that isn't obvious from the code.

**This repo is public.** Never commit ROMs, saves or secrets. The SteamGridDB API key is
entered by the user at runtime and must stay that way. Game art (FireRed / Emerald's and the
hacks') is rebuilt from the player's own ROM (`RomArt`), not bundled. Keep it that way. Write home paths as `~` in
anything committed (fixture READMEs: `capture_fixture_headless.sh` does). **Commits carry no Claude /
Anthropic attribution** (no `Co-Authored-By`, no "Generated with" lines, never authored as Claude) - the
user's firm rule; a local `commit-msg` hook rejects them, never bypass it.
**Exposed surfaces**: `LaunchActivity` is exported - it refuses the app's own files / provider, copies at
most 40 MB and plays only what has a cart header (`RomIdentity.looksLikeRom`); the updater installs only
this package, same signer, newer version (`AppUpdateFlow.isOurUpdate`); the website's release notes are
escaped (`website/src/lib/releases.js`, `website/test/releases.test.js`); workflow actions are pinned to
commit SHAs. New bundled code / fonts / data go into NOTICE and the LICENSES page (`licensesAsset`).

## Relationship to the FireRed QoL repo

PokeDaisy was split out of the user's **private** FireRed QoL ROM-hack repo (local
checkout `~/Projects/tests/my-rom-hacks`, where it lived as `tools/pokedaisey`
under the misspelled name pokedaisey) on 2026-10-06, without git history. That repo still owns:

- **The `gQolTelemetry` struct** the FireRed / Emerald QoL builds export
  (`include/qol_telemetry.h` via its `patches-firered/0009-*` / `patches-emerald/0001-*`,
  documented in its `docs/telemetry.md`). `companion/data/Telemetry.kt` decodes it byte
  for byte, so a struct change there needs the matching change here.
- **The Go `tools/telemetry-viewer`**. Several Kotlin files here are ports of its readers
  (`native.go`, `gen3mon.go`, `unbound.go`, ...) and say "keep in sync".
- **The pinned, built decomps** (`build/pokefirered`, `build/pokeemerald`, `build/pokeruby`,
  `build/pokehns`, ...) that the generator scripts and ui-preview read via `$DECOMPS`.

Public readers can't see that repo, so user-facing docs call it "the FireRed QoL project"
and don't link to it.

## Local setup on this machine

- `local.mk` (untracked, see `local.mk.example`): `DECOMPS` →
  `~/Projects/tests/my-rom-hacks/build`, `MON_ICONS` → that repo's
  `tools/telemetry-viewer/assets/pokemon`. `make` exports both; outside make, export them
  yourself for `python3 scripts/gen_*.py` or `gradle render`.
- `local.properties` (untracked): Android `sdk.dir`.
- `third_party/mgba`: git submodule at mGBA `0.10.5`; `third_party/rcheevos`: at `v12.5.0`
  (`make submodules` fetches both).
- ROMs/saves for headless captures: `scripts/host_roms.conf`. On-device paths:
  `scripts/roms.conf` (`/sdcard/Android/data/com.pokedaisy.app/files/...`).
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

## UI work

**Look at every UI change before calling it done** — render it and read the PNG, don't
reason about Compose layout blind. Two ways, same screens:

1. **Paparazzi** (needs the Android SDK + Google Maven, i.e. the user's Mac):
   `./gradlew :app:recordPaparazziDebug --tests '*ScreenshotTest'`,
   then Read the PNGs in `app/src/test/snapshots/images/` (gitignored — a viewing tool,
   not a regression gate). Tests live in
   `app/src/test/kotlin/.../companion/ui/CompanionScreenshotTest.kt`: one per companion
   tab (via `CompanionScreen(initialTab = …)` — Paparazzi can't tap the tab bar) plus the
   shared `OptionSelector`/`OptionConfirm`, at the Thor bottom screen's 1240x1080 (landscape);
   `WideCompanionScreenshotTest` re-runs all of them at the top screen's 1920x1080 (where SWAP SCREENS puts
   the companion; density 2.0, the closest bucket layoutlib has). Wide-screen rules so far: `PartyGrid` goes
   to 3 columns past 1.6:1 (a measuring `Layout` - BoxWithConstraints composed the slots late and FireRed's
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
   (`-Ponly=settings` filters by name, `-Pgame=EMERALD` switches game). It's also the
   only compile check available in such sessions, so run it after any UI edit there. See
   its README for the shims and quirks (CMP 1.5 / Kotlin 1.9.22, `Icons.AutoMirrored`
   rewritten to `Icons.Filled`, a stale incremental cache once faked "Unresolved
   reference" errors — delete `build/kotlin` if errors look impossible).

**The app's one look is FireRed's OPTION screen** (`companion/ui/GbaMenu.kt`): white
title window, grey multi-line list window, `LABEL  VALUE` rows with grey labels / red
values and a white row as the cursor, white framed buttons, black-out overlays. Text is
`GbaText` sized by `GbaTextMetrics` (whole screen pixels per font pixel, so the pixel
font stays crisp; `rememberGbaTextMetrics(1f)` for denser secondary text). Companion
SETTINGS/STATES and the top-screen Library/Settings are all built from these pieces over
a game backdrop (`GameBackdrop` on the companion, `AppBackdrop` — FireRed's party-menu
stripes — on the top screen); ITEMS (`ItemsScreen.kt`) is the per-game bag screen in the
same idiom. PARTY uses the game's own slot (`GbaPartySlot`, generated `PartySlotStyle`s) where one exists; every other game gets a FireRed-like slot drawn from a `PartyPalette` (`PartyScreen.kt`, `partyPaletteFor`) — restyle a game by adding a palette there, like ITEMS' `BagPalette`. Rogue, Gaia, Lazarus, Seaglass and R.O.W.E. have palettes sampled from their party menus (headless: the save's first mon copied into slots 2-3, slot 3 at 0 HP, so normal / selected / fainted all show; `PartySlotColors.band` / per-state `text`, `PartyPalette.empty` cover flat and white-box slots); their bags have `BagPalette`s (TMT2's is `EmeraldBag`; Celia and Gaia match FireRed's). Their backdrop is FireRed's party backdrop recoloured (`backdropColors` in `theme/Theme.kt`: its 3 colours mapped to the game's), or plain stripes until a FireRed ROM has supplied the art. Multi-choice settings open an `OptionSelector` pick-list — never make a row
cycle through more than two values (one exception, the user's call: SHADERS > FILTER and GRID cycle on tap, so each
look shows on the game at once). **The PokéDaisy theme** (the default, `DAISY_THEME_ID` 8 in `theme/Theme.kt`; Settings > THEME, `Prefs.appTheme` by
id - FireRed is 0, the old default) only changes the top screen's backdrop (Library, Settings, setup): `AppBackdrop`
-> `DaisyBackdrop`, the website's (blobs, dot grid and, on the Library, its floating logos). The windows and text
stay the OPTION look; only text drawn straight on the backdrop (`OptionColors.onBackdrop`) turns the windows' grey
there. Never in a game (`OptionColors.inGame`), and the theme's chrome colours are FireRed's, so nothing in game
changes. The logo (`LogoGen.kt`) and the launcher icon's foreground PNGs (on the blue `ic_launcher_background`) are
generated from the website's own drawing by `node scripts/gen_logo.mjs`: the app's screens use the website's mark as
it is; only the launcher icon has edits on top (the 3 and 9 o'clock petal tips rounded, a 2 px outline; its size
follows the flower, not the outline).
New screens should reuse these rather than
`GbaWindow`/`GbaButton` (the older cream-window look). Full-tab detail views (the Pokémon
summary, battle INFO / SUGGESTIONS) use `SummaryFrame` (`MonDetailScreen.kt`): one white
window split by `Separator`s over a row of square `PlatinumButton`s with Back bottom-right.

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
tabs the running game has count). The rest open from OPEN rows at the top of SETTINGS; in a
battle BATTLE takes the last chosen tab's place, and when it ends the tab that was open before
it comes back. Tabs go by id (`"ITEMS"`); only the chip / OPEN row shows the game's own word
(`companionTabLabel`: BAG, Unbound's CUBE, R.O.W.E.'s INVENTORY - read off each ROM's START menu
in `native-capture/menu-shots/*/start.png`). The GUIDE tab (`GuideScreen.kt`) shows
hand-written pages per game (`companion/data/Guide*.kt`: TIPS / WHERE IS / STUCK?, with
`Have` checks marking what the save already has) plus pages read live from ROM + save:
HERE (the current map's wild encounters), NEXT BOSS (`GameGuide.bosses`, first one whose
badge/`FLAG_DEFEATED_*` flag is unset; teams from `gTrainers`, default moves from the
learnsets) and EVOLUTIONS (`Evolutions.kt`). ROM/save addresses live in `GuideTables`
(`GuideRom.kt`) on the native path only — the QoL struct path gets the hand-written pages
alone. The battle INFO pane (`BattleInfoScreen.kt`) is ordered by what a player asks
mid-battle, split 50/50 YOU/FOE: battler cards on top, your moves with a plain-words verdict
(SUPER 2x / RESISTED ½x / NEUTRAL 1x / IMMUNE 0x) bottom-left, the foe's WEAK TO / RESISTS
bottom-right — sized to fit a single battle without scrolling. The FOE TEAM
(`gEnemyParty`) sits in its bottom bar like the summary's party strip; unsent foes are
Poké Balls until tapped (a tapped foe gets a NEXT / FAINTED / RESERVE / UNSEEN pill by its name). SUGGESTIONS (`SuggestionsScreen.kt`) is built from INFO's pieces
(`BattlerCard`, `BattleMoveRow`, `FoeTeamStrip`): a VS line for the foe, then the top three
picks, each split 50/50 like INFO — the Pokémon's card (1ST/2ND/3RD, OUT on the one already
in battle), then its best move with the verdict. Change the shared pieces, and both panes follow. Everything is hint-first (title, tap = hint, tap = answer) and only one entry
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
their guides differ; with no live tables (the QoL builds) it falls back to the kind. Ruby/Sapphire
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
slots whose levels can't be real.
**HERE covers the whole area, for every game with a guide (the user's standing rule)**: not just
the wild Pokémon but TO DO (hand-written entries tagged `areas = listOf("ROUTE 104", …)`, matched
to the current map section's name via `areaKey()`), PEOPLE (gifts, gift Pokémon/eggs, in-game
trades) and ITEMS (item balls, hidden items). PEOPLE/ITEMS are generated per region map section
(so a city's buildings count with it) by `scripts/gen_guide_areas.py` from the
pinned decomp's map.json events + scripts.inc → `GuideAreas{FireRed,LeafGreen,Emerald,RubySapphire}Gen.kt`, each with the
flag that marks it done (a ball in the UI; needs save flags, i.e. the native path — the QoL struct
path shows the lists unmarked). When adding a guide for another game, generate its area data too
and tag its hand-written entries with areas. HERE shows whenever a game has area data, even
without the ROM tables (then no wild list).

**ROM art - no game art is bundled** (`assets/` holds only fonts): FireRed's / Emerald's party-menu art
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
`RomArtSigsGen.kt` from `scripts/gen_rom_art_sigs.py`, hashes/CRCs only - so retail, the QoL
builds, LeafGreen and hacks that kept the art all match. Adding art means new sigs + a bump of
`RomArt.SCAN_VERSION` (ROMs scanned before get rescanned). Load art through `GameArt.get`
(`AssetImages.kt`; rom-art, then assets) keyed on `rememberArtGeneration()`, so it shows up
once a scan lands; with no art the party tab falls back to the `PartyPalette` slot and the map to
text. `gen_party_assets.py` / `gen_cfru_party_assets.py` only generate the `PartySlotStyle`s now (no
PNGs). `RomArtTest` pins the pixels (the hacks' matched the PNGs once bundled, pixel for pixel);
ui-preview rebuilds the art from the decomp builds (or `-PartRoms` / `-Prom`), Paparazzi from the ROMs.
**TRAINER CARD (the CARD tab, off the bar by default, so under SETTINGS > TOOLS)**: retail FireRed /
LeafGreen / Emerald and both QoL builds only (`NativeConfig.trainerCard`; the hacks copy the retail
configs, so it is set on the retail ones alone, and on `findStructMoney`'s candidates for the QoL
path). `TrainerCard.kt` reads what `SetPlayerCardData` gathers (name, ID, play time, money, caught
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
count starts from the 1-star palette. Save data: FireRed's layout + CFRU dex flags (`TRAINER*CARD_UNBOUND`on`NATIVE_UNBOUND_WITH_DEX`only). Other hacks: no card yet.
The tab shows the card cropped to itself (no BG2 backdrop) at the largest whole scale (5x on the
Thor); a tap flips it (squash, like the game), and the time colon blinks via an infinite transition
(a`delay`loop never lets Compose tests go idle, which hung ui-preview once).
**SoulGold v1.1.4** (`NATIVE_SOULGOLD`; v1.2 = `NATIVE_SOULGOLD_V1_2`, a rebuild at shifted addresses, same save
format, v1.1.4's generated tables but TM75 via `soulGoldV12`; a second v1.2 build = `NATIVE_SOULGOLD_V1_2B`, same RAM, ROM data 0x98-0xA4 earlier; gPartyMenu comes from `NativeConfig.partyMenu`, newer expansion, no source) breaks several vanilla
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
park / `pkFindMagic` / `pkRomCode` return early, the engine's watchers (`EmulatorEngine.gba`), FF music
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
**Yellow, round two**: `pk_gb_config` must only reload `sgb.borders` (`reloadConfigOption`): a whole
`mCoreLoadForeignConfig` left the GB core's volume at 0 (Yellow was silent). Never tile a 1px backdrop
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
**Companion options (2026-10-08)**: RESUME GAMES (`Prefs.autoResume`, top-screen Settings > LIBRARY; off =
a launch boots the save, a return from HOME still resumes - `freshLaunch`), CLOSE COMPANION (SETTINGS, two
screens: the Presentation goes away until BACK on the game), STATUS BAR OFF / GAME / COMPANION (`Prefs.statusBarOnCompanion`,
`CompanionStatusBar`: the same `GameStatusBar` over the companion's tabs, via `CompanionScreen(statusBar = …)`), TWEAKS
(`CompanionTweaks`: icon bounce, map cursor blink, tab animations, jump to battle). The Presentation is
FLAG_NOT_FOCUSABLE so HOME (the Thor's double press) acts on the game's display. ui-preview runs with a cached
Gradle (`~/.gradle/wrapper/dists/gradle-8.10.2-all/*/gradle-8.10.2/bin/gradle`): `gradle` isn't on PATH here.
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
**IVs / EVs (summary STATS, battle INFO STATS)**: `decodePartyMon` reads them from the BoxPokemon itself
into `Mon.stats` (`MonStats.kt`): EVs substruct, Misc +4's IV word (plaintext CFRU boxes: +0x38 / +0x48),
nature = PID % 25 XOR expansion's `hiddenNatureModifier` (+0x12 bits 3-7, 0 elsewhere: Mints), stats from the
party fields. Checked on every fixture by recomputing the stored stats from the ROM's base stats (equal, or
1 below where EVs came after the last level-up); SoulGold's 96-byte struct didn't add up, so it gets none.
The QoL path takes them with EXP (`withPartyExp`). Battlers get theirs by matching species / level / HP
against the party and `Telemetry.battleFoes` (gEnemyParty, now read in wild battles too; `enemyParty` stays
trainer-only for FOE TEAM). Hidden Power's power shows only on FIRERED/EMERALD kinds (expansion uses a flat
60). Foe IVs sit behind SETTINGS > FOE IVS (`Prefs.showFoeIvs`, off): INFO's STATS button.
**Battle POKéMON pane + automated input** (`BattleControlsScreen.PartyPicker`, `BattleInputController`):
on FireRed rev 1 / Emerald and their QoL builds (`switchAddrsFor`in`PokeDaisyActivity`; gPartyMenu
FR `0x0203B0A0`/ EM`0x0203CEC8`, slotId at +9, same in retail and QoL), POKéMON opens the PARTY tab's own slots
(`PartyGrid`, shared with `PartyScreen`; taps silent since the game sounds them) - the game's cursor and a BEST tag on
`recommendedSwitch`= SUGGESTIONS' top pick with BATTLE HINTS on, else the cursor on the battler (tagged OUT), and a
"Choose a POKéMON." window with CANCEL below, full height (no ball strips or INFO / SUGGESTIONS over it); it also replaces the game's party screen whenever that's open (after a faint). A tap runs`switchTo(personality)`:
POKéMON on the action menu, then a closed loop on the game's memory - `OpenPartyMenuInBattle` really reorders
gPlayerParty to battle order while the menu is open, so the mon is found there by personality and DOWN is pressed
until slotId matches (Emerald drops presses for ~35 frames after the menu opens, so it re-reads after each), A,
A on SHIFT. While a switch runs the top screen keeps its last frame (`BattleInputController.holdFrame`->`EmulatorView.holdFrame`skips the texture upload), so the game's fade to black and its party menu never show;
let go 12 frames after the switch ends (WaitForMonSelection only returns once the battle has faded back in).
Verified headless on both games (scripted wild battle via CreateScriptedWildMon +
StartScriptedWildBattle). RUN presses B to close "Got away safely!" (every 30 frames until the battle ends or the
action menu returns). All synthetic presses go through`GbaInput.setScript(bits, exclusive)`: the player's own
keys are ignored while a sequence runs (they used to share `touchBits`, which also wiped the touch pad's presses) -
only while presses play or a closed loop steers (`exclusiveLocked`), never during the passive target-confirm /
pending-move watches: those once held the lock through the whole turn, so a level-up or "learn a move?" message
waiting on A left the player stuck. Watches expire after 300 frames; a 1200-frame watchdog cancels any lockout.
`BattleInputControllerTest`simulates the party menu.
**FF MODE** (SETTINGS,`FfMode`: SMART default / NORMAL). NORMAL = fast everywhere. SMART = 1x while a
menu screen or the region map is up, FF (still on) resuming when it closes. **A battle stays fast** (the user's
rule): in battle the game's own battle input state decides (`smartSlows`, `SmartFf.kt`, fed by
`BattleInputController.gameState`) - only PARTY_OPEN / BAG_OPEN slow down, never the battle itself, whatever the
menu watch below thinks (it once slowed whole battles); it and the map watch only decide outside battles, or
in a game that doesn't report a battle state. Menus are found by `FfMenuWatch`with no per-game table: the field and battles each run one fixed`gMain.callback2`, every standalone screen
(party, bag, summary, PC, Pokédex..., in battle too) its own - the START menu and dialogue are overlays that keep
the field's (checked headless on FireRed QoL, Emerald, Rogue). gMain is found by scanning IWRAM twice 16 frames
apart (vblankCounter1 at +0x20 moving exactly that much; FireRed's is a pointer, so counter2 at +0x24 then); the
field's callback2 is learned from the player moving (snapshot positions), the battle's from frames in battle,
both persisted per ROM CRC (`Prefs.ffMenuCallbacks`). Ruby/Sapphire's inBattle byte is +0x43D.
**Settings search / subtitles / setup buttons**: top-screen Settings HOME has a search box (`filterRows`, SettingRows.kt):
a row matches on its label (English and translated), value, `subtitle` and its words in `SETTING_KEYWORDS` (by English
label, shared by both screens: "rebind", "keybinds", "turbo"... - give a new row its words). The companion's SETTINGS has
no search (the Presentation can't host a keyboard). `SettingRow.subtitle` / `OptionLine(subtitle)` draw a grey line
under the label (GAME BUTTONS: key bindings; each TWEAKS switch its own `Tweak.description`, on a wider label column). HOTKEYS
can be unbound (top screen: CLEAR while a row waits for keys; companion picker: NONE), and a chord another hotkey already has
exactly (same keys, any order - sharing only some keys is fine) asks KEY IN USE / MOVE first (`Hotkeys.clashes`, `takeFrom`);
clashes already in a file are left alone. First-time setup has a GAME BUTTONS step (`SetupState.Step.BUTTONS`)
listing the bindings; CHANGE BUTTONS opens Settings' rebind page alone (`EXTRA_SCREEN` = CONTROLS, BACK returns).
**SETTINGS layout**: the options come in titled groups (FAST-FORWARD / CONTROLS / COMPANION / SCREEN) in ONE
scrolling column at the normal text size (a two-column, denser try was too small to tap - the user's call),
ending in CLOSE GAME / RESTART GAME (under a `Separator`). The top-screen Settings uses the same
`GroupedRows` / `SettingRow` (`companion/ui/SettingRows.kt`), with its own groups (FAST-FORWARD / CONTROLS /
SCREEN / LIBRARY / ONLINE / APP). Sub-pages and pick-lists get the title window's back arrow (`onBack`). TOOLS (every tab not in the tab bar) sit under the list as `TabChip`s in
the bar's own columns (`barChips`wide, the gear's gap at the end), a second row of tabs only SETTINGS has.
**OVERLAY** (SCREEN group on both screens, `Prefs.overlay`; `overlay/Overlays.kt` + `OverlayBitmaps.kt`, drawn by
`EmulatorView`): a frame around the game. Two built-in frames of our own (DAISY / BEZEL, drawn at the game's pixel size,
shown at a whole scale) plus the player's imported RetroArch overlays (.cfg + PNG, .zip, bare PNG; window = the .cfg's
viewport or the PNG's see-through middle) under `<external files>/overlays/`. **No third-party overlay is ever bundled,
linked file by file or shown in our screenshots** (the user's rule: the usual console borders carry the console maker's
logo and shell). Imports happen on the top screen (file picker); the companion only picks. See docs/DEVELOPMENT.md's
"Overlays".
**FF steps aside on the game's region map** (SMART only):`RegionMapWatch`finds the map screens' EWRAM
pointers per ROM (FireRed family:`sRegionMap` `0x020399D4`, found beside its `0x4796`struct
offset in the literal pools - Town Map, Fly map, wall maps; retail/QoL Emerald:`sFieldRegionMapHandler`+`sFlyMap`+ the PokéNav's HOENN MAP - Emerald has no Town Map item -
via`gPokenavResources` `0x0203CF40`->`substructPtrs[POKENAV_SUBSTRUCT_REGION_MAP_STATE]`(+0x1C, set only while that page is up; verified headless); Emerald hacks and Ruby/Sapphire:
none yet - R/S's PokéNav map has no clean "open" flag), and`EmulatorEngine` caps the
speed at 1x while one is non-null - FF stays on and resumes on close. Verified headless
(`call ShowTownMap`: 0 on the field, set while open, 0 after B).

**FF MUSIC** (SETTINGS: STEADY [ALPHA] / SPED-UP / OFF, `FfMusicMode`, in that order - OFF last).
SPED-UP plays the core's own audio box-filtered down to real time, written non-blocking so FF is
never paced by the audio device. STEADY loops a clean clip of the song playing (`FfMusicPlayer`,
`FfMusicCache` per ROM CRC), keyed by the song itself: `FfMusicKey` reads the BGM player's
`songHeader` from the m4a engine every frame (`0x03007FF0` -> SoundInfo -> player chain, BGM is
the tail) - no per-game song table (a hand-picked FireRed "battle song" was MUS_RS_VS_TRAINER, i.e.
Emerald's theme). `M4aSongs` matches agbcc's `m4aSongNumStart` and, masked, Emerald Rogue's gcc build of it, newer expansion's
(ident check) and Heart and Soul's, which takes a second argument: set, it plays the song's entry in an alternate
soundtrack table (its songs are `M4aSongs.ALT` ids; `pkRenderForceSong` always passes r1, which used to be whatever the
game left there); the render core boots 300 frames, then waits (up to 30 s) for the game's own music, since Rogue keeps the sound engine paused through ~15 s of splash screens and a forced song never starts there. Clips are **never captured from the live game** (that mix carried menu clicks
and battle sounds): `FfMusicRenderer` renders each song alone on the second core (`rg`) as the
game starts it, calling the ROM's `m4aSongNumStart` - found by code signature (`M4aSongs`; every
agbcc-built game and binary hack, not pokeemerald-expansion) - via `pk_call`, which runs the
call to its return and restores the CPU (a bare PC hijack corrupted Unbound mid-frame), after
parking the booted game on a `b .` so its intro can't switch songs (only once the VBlank IRQ is
fully on - Unbound froze otherwise). Until a clip exists (or on an unsupported ROM) STEADY falls
back to SPED-UP, so every ROM pre-renders its music once in the background: FireRed/LeafGreen/Emerald-sized
ROMs (retail + QoL) their `MapMusic*.kt` list, any other ROM every `gSongTable` entry on the BGM player
(`M4aSongs.bgmSongIds`, `ms` 0); a song being heard pre-empts that pass mid-recording, songs that end by
themselves (fanfares) count as done. **Clips loop where the song loops** (`M4aLoop.kt`, cache v6): the BGM's
first track jumping back through its GOTO (0xB2) marks the loop end, the frame its main stream passed the
GOTO target the start (no intro: two GOTOs apart); `M4aLoopSplice` cuts one period starting 1 s into the
loop, tuned to the sample. The recording before that cut is saved as `<key>.intro.wav`: `FfMusicPlayer` plays it once, then
hands over gaplessly to the loop (`setNextMediaPlayer`), both started at the song's own position
(`Clip.songStartedAt`, when the BGM key changed). Without the intro a song with one started ~9 s in
(FireRed's Route 7). A heard song whose clip predates intros is re-recorded; the background pass isn't redone. The old audio-envelope `LoopDetector` cut battle themes to ~3 s and looped back
to the intro. **The clip follows the FF the player asked for** (`EmulatorEngine.requestedSpeed`), not SMART's
temporary 1x: it plays straight through a menu (game audio muted meanwhile, menu SFX included) instead of
handing over to the game's music and restarting from 0:00 when FF resumed; and a clip that comes back (after a menu, or FF
off for a moment) picks up where the song is by now (`Clip.songStartedAt`). Debug the
render core headless with `native-capture/mgba_dump`'s `call` / `park` / `bgm` / `wav`. The
SETTINGS title also shows the device battery (`BatteryIndicator`, fed by the activity through
`DeviceBattery`).

**STATUS BAR** (SETTINGS on either screen, `Prefs.statusBar`, off by default, live-toggled): a
white title-window strip on top of the game, exactly as wide as it (`GameStatusBar`, laid out by
`GameStageLayout`, which shrinks the game to 3:2 under it and moves the HUD down) - the ROM's
Library name, map section, money, clock (system 12/24h) and battery. Money is
SaveBlock1.money ^ SaveBlock2.encryptionKey via `NativeConfig.moneyOff` (FireRed family `0x290`,
Emerald family / R/S `0x490`, Heart and Soul `0x494`, Emerald Rogue `0x4A8` (its party mons are 104
bytes), -1 = unknown); values over 999,999 are dropped. The QoL struct has no money: the
Poller's `findStructMoney` tries the FireRed QoL build's own save pointers (`0x03005018`, not
retail's) then retail's, trusting one only once its SaveBlock1.location matches the struct's map.
`MoneyTest` pins every fixture.
**ASPECT** (SETTINGS on either screen, `Prefs.stretchGame`, ORIGINAL default / STRETCH): STRETCH fills
the top screen - `EmulatorView.stretch` drops the quad's letterbox and `GameStageLayout.stretch` gives the
game all the space under the status bar. The bottom screen flips it in place; the top-screen Settings opens
`AspectPicker` (both choices drawn as the top screen in miniature, with the newest savestate thumbnail, else a
drawn stand-in) and the game picks it up on resume (`syncGameScreen`).
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
`getMemoryBlock`); the hash is MD5 of the ROM bytes mGBA mapped (= the file, verified on the host:
retail FireRed rev 1 = game 515, Emerald = 668, Unbound v2.1.1.1 = 17530, the QoL builds have no set).
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
word truncates; off the bar by default, under SETTINGS > TOOLS; works on unsupported ROMs too) lists
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
`CompanionScreen(clickSound = …)`) instead of `clickable` - except buttons that press the game's
own buttons (battle FIGHT / BAG / moves / BACK: `PlatinumButton(pressesGame = true)`), which the
game already sounds, and tap-swallowing scrims. The top-screen Library / Settings stay silent.

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
**Single-screen devices** (`SidePanel.kt`, Compose pieces in `companion/ui/SidePanel.kt`; tested on the
Retroid Pocket 6, 1920x1080): with no second display (`syncPresentation` finds none, debug mirror off) the
companion is a panel on the right of the game's screen. A BACK tap slides it in over the game; with it open
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
**External controllers** (8BitDo, GameSir, ...): `configChanges` includes `keyboard` (many controller modes
register as a keyboard, and connecting one recreated the activity mid-game); diagonal D-pad keycodes press
both bits; joystick motion is taken in `dispatchGenericMotionEvent` before a focused ComposeView in the same
window sees it; a real button used for the game hides the AUTO touch pad even when the device doesn't report
itself as a gamepad (`physicalPadUsed`, reset when a device goes); an unbound BUTTON_MODE (HOME / guide) is a
BACK tap (`companionBackTap`); connect / disconnect show a HUD notice. Not tried on real controllers yet.
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
rects and grids, shifted (`mapSecDataQuetzalOnMap`). Glazed's and Quetzal's party menus are Emerald's slots on TMT2's olive stripes.
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
**Orange Islands / Unbound FR / Quetzal ES (2026-10-09)**: **Orange Islands** (`GameKind.ORANGE_ISLANDS`,
`NATIVE_ORANGE_ISLANDS`) is retail FireRed rev 0 edited in place at 16 MB - the size of retail, so detect() now hashes a
small BPRE ROM once too (like Seaglass's BPEE check; an unknown hash falls through to QoL / retail). Its two builds (Beta 5.7
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
**USE on the ITEMS tab** (`FieldItems.kt`, retail FireRed rev 1 / Emerald): a Repel is used by calling the game's own
VarGet / VarSet / CheckBagHasItem / RemoveBagItem between frames (`MgbaCore.pkCall`, the main core's pk_call returning r0),
only while ArePlayerFieldControlsLocked is 0 and no battle / menu screen is up; the functions' code is CRC-checked first
(FireRed's QoL build moved them). The game's own step counter then runs it out. mgba_dump's `call` prints r0 to test such calls.
**Item descriptions are read from the player's ROM, never bundled** (they're the games' prose; names stay bundled):
`itemDescription(id)` -> `RomItemText` (through `PokedexSource.reader`, cached per ROM) with the game's
`NativeConfig.itemDescs` (`ItemDescTable`: base / stride / description-pointer offset, `vanillaItems` / `japaneseItems`
for struct Item; expansion's gItemsInfo has it 8 bytes before the name; Rogue chains gRogueItems via `next`). The Poller
picks it on detect (`matchesRom` checks vanilla ones); the QoL builds have no config (gItems moves every rebuild), so
`findVanillaItems` finds it by shape. No ROM = "". Yellow keeps our own hand-written text (`ItemDescriptionsYellow.kt`).
The generators no longer write descriptions; `RomItemTextTest` pins a few per game. A new game needs its `itemDescs`.
**1x frames follow the game screen's vsync** (`EmulatorEngine.onVsync`, ticked from `EmulatorView`'s GL thread): one game
frame per refresh (60 Hz) or per two (120 Hz), audio resampled to match (`pkSetAudioRate`) with RetroArch-style dynamic
rate control on the AudioTrack's fill, written non-blocking; no usable vsync (or another speed) falls back to the timer +
blocking writes. Before, the audio clock paced frames and the display showed one twice / skipped one every few seconds.
**Frontend launch (Cocoon / iiSU / ES-DE)**: `LaunchActivity` (exported, translucent, no intent
filter, `taskAffinity=""`) takes the ROM as intent data or a `rom`/`ROM`/`path`/`file`/`uri` extra,
plays a readable real path in place (`RomUris.originalPath`; needs All files access on 11+), else
copies it into `roms/` once (skipped while the bytes match), then starts `PokeDaisyActivity` with
`EXTRA_FROM_FRONTEND` - exit is then `finishAndRemoveTask()`, back to the frontend. A different ROM
arriving while a game is open is switched in `onNewIntent` via `loadRom()` (the activity is paused,
so the engine already stopped/suspended) + `TelemetryStore.reset()` (the sampler caches the
detected game). `LibraryActivity` forwards a non-VIEW launch carrying a ROM there. Setup table in
docs/DEVELOPMENT.md's "Launch from a frontend" section. Not yet tried from a real frontend.

**First-time setup + linked ROMs folder**: `SetupScreen.kt` (state + UI; `LibraryActivity` owns the
pickers and threads) shows over the library while `Prefs.setupDone` is false and the library is empty,
or after Settings' RUN SETUP (`Prefs.setupRequested`): ROMs folder → saves folder (offers folders that
already hold `.sav`/`.srm`: the ROMs folder, RetroArch's per volume - `SavesLocation.suggestions`) →
SteamGridDB key (the shared `CoverSync` runner, also behind Settings > Cover Art). Every step is
skippable. The library is `RomFolder.libraryRoms`: imported `files/roms/` plus the linked folder's
(`Prefs.romsFolder`) supported ROMs, played in place (never copied), rescanned off the UI thread on
every library resume, with `CompanionSupport` verdicts cached by path+size+mtime in
`filesDir/rom-folder-scan.tsv`. Any game can be HIDDEN from its library menu (`Prefs.hiddenRoms`, by path;
name, cover and saves kept; Settings > HIDDEN GAMES shows them again); linked ROMs have no DELETE (the
player's own files). Same-named imported ROMs win (covers/names are keyed by file name). Setup opened by
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

**CHEATS** (GameShark / Action Replay / CodeBreaker, GBA only; docs/DEVELOPMENT.md's "Cheats"): per ROM in
`filesDir/cheats/<CRC32>.cheats` (mGBA's format, `CheatStore`), master switch `Prefs.cheatsEnabled` (off). Added /
imported on the top screen (Settings > CHEATS, or a game's library menu > CHEATS - a keyboard); the companion's SETTINGS
> CHEATS only toggles, live (`EmulatorEngine.setCheats` -> `pkCheatsApply` on the next frame). File formats are parsed
in Kotlin (`CheatFiles`: mGBA's own parsers choke on CRLF and drop bad lines silently); every code line is checked by
mGBA's parser (`pkCheatsCheck`, no core needed) and a bad one is reported, never dropped quietly. Only *enabled* cheats
reach the core, and taking them out undoes hooks and ROM patches (`pk_cheats_clear`), so with none on the ROM is the
file. They work with `USE_DEBUGGERS OFF` (frame-end refresh + the cheat device's BKPT component; checked headless with
mgba_dump's `cheat`, built against the app's flags). A hook / ROM patch would change what identifies the game, so
`pk_cheats.c` keeps the original bytes: `pkReadBytes` overlays them (the Poller's hack SHA1) and `raLoadGame` swaps them
in for RA's hash. RetroAchievements pauses while any cheat is loaded (`rc_client_idle` instead of `do_frame`; CHEATS ON /
OFF popups, PAUSED badge on the ACHIEVEMENTS tab). The render core `rg` never gets cheats.

**Save files are sacred**: an mGBA state carries the save as it was, and loading one
(`SAVESTATE_SAVEDATA`) writes that copy over the save file. So the auto-resume first checks
`pkStateMatchesSave`: if the file changed since the state was made (RetroArch played it, the saves
folder moved, a save copied in), the state is set aside as `resume.replaced` and the game boots
from the file (a real report: a resume state from a blank first boot wiped an Unbound save to all
0xFF). Every start also copies the save to `<saves>/pokedaisy-backups/` first (`SaveBackups`, newest
10 distinct), which the library's RESTORE BACKUP lists. Never add a path that writes the save
without both.
**Where a save is** (`SavesLocation.saveFor`, every save path goes through it): the game's own folder (library
menu > SAVE FOLDER, `Prefs.romSaveDir`, by file name; setting it copies the current save over if the folder has
none - never moves it), else the first of the saves folder and Settings > FOLDERS > ALSO LOOK IN (`Prefs.extraSaveDirs`)
holding one - played and written where it is, so RetroArch's per-core folders keep their saves - else a new one in the
saves folder. Only the folders themselves, never below (a user's synced RetroArch/saves pulled in Syncthing's
`.stversions` when FOLDERS listed recursively; that list now skips hidden folders). `SavesLocationTest` pins the order.
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
page and the library INFO of such a ROM open `RomSupportRequest.url` - rom_request.yml prefilled by field id with the
file name, game code, size and SHA-1, like the website's ROM check.
**BEST EFFORT** (`BestEffort.kt`): an unsupported ROM of a Gen 3 base game (header code BPR/BPG/BPE/AXV/AXP) gets
TRY BEST EFFORT on NOT SUPPORTED; the sampler then reads the running game through every `BestEffort.candidates`
config of that family (the decoders' globals set per try, put back after). A config fits when its party is real
(1-6 mons, encrypted boxes pass their checksum, species / level / HP plausible) - so the player must be in game with
a Pokémon; each ROM-side `Part` (DEX via `pokedexMatchesRom` or gSpeciesInfo's BULBASAUR, GUIDE's probe trainer, item
text) that doesn't hold is switched off (`Match.config`). All holding = FULL (a re-hashed supported build, like the
SoulGold v1.2 reports): treated as supported, no tag. Kept by SHA-1 in `filesDir/best-effort.tsv` (`BestEffortStore`,
candidate ids are stable - never rename one) and applied by `detect()` next launch (`unsupportedRom`); the version
globals compare `baseCfg` by identity. Library: `CompanionSupport.verdict` (SUPPORTED / MATCHED / PARTIAL / TRYABLE /
UNSUPPORTED) cached by `RomFolder` as 1/M/P/T/U (old "0" rows are re-checked once); linked-folder TRYABLE ROMs are
listed with NOT SUPPORTED so best effort is reachable, PARTIAL ones tagged PARTIALLY SUPPORTED; INFO's FORGET MATCH.
`BestEffortTest` matches 7 games' real saves to their own configs (all FULL) and a party-less game to nothing.
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
skips desktop Compose 1.5's own OnPositionedDispatcher NPE, which the app's 1.6.8 doesn't have) - run
it after touching anything a tab composes. Per-visit work belongs in process-wide caches (slot art,
backdrops, card renders, state thumbnails), not `remember`, which a tab loses each time it leaves.
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
Deployed to Firebase Hosting (`pokedaisy.web.app`) by `.github/workflows/website.yml` **only with an app release**
(the user's rule: the site never lists games the downloadable APK can't run): a published, non-pre-release release
deploys its tag's `website/`; pushes to main only build + test; a manual run takes a release tag (redeploy / roll
back). A site-only fix therefore waits for the next release (or a manual run of the current tag after moving it); the checker's ASK FOR SUPPORT pre-fills `.github/ISSUE_TEMPLATE/rom_request.yml` by field id.
The site's game matrix is README's Supported games table; a row of all "—" means detected but no companion yet,
so the export lists it under `inProgress` - off the matrix, and "in progress" in the ROM check (none since R.O.W.E.
got its companion, 2026-10-09).
The site's demos and art never use real Pokémon / move / item names or the games' art (the user's rule).

**Library names** (`GameTitles.kt`): a game shows as its own name ("Pokémon FireRed") - the player's
RENAME first, then the whole-file SHA1 looked up in `GameTitles.BY_SHA1` (retail from the pret
decomps' `*.sha1`, every supported hack), else the file name minus dump tags (`tidy`: "(USA, Europe)",
"(v1.3.1)", "1636 - "). The QoL builds have a new hash every rebuild, so they keep their file name.
Hashing runs off the UI thread (library resume, folder scan, import, a frontend launch's status bar),
cached by path + size + mtime in `filesDir/rom-titles.tsv`; `GameTitles.label` only reads that cache.
**Library covers (SteamGridDB)**: `SteamGridDbGames.forRom` matches known hacks by SHA1, then
≤16 MB FireRed/Emerald ROMs by game code (so retail and the QoL builds get art). The
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

The app's only font is **Pixel Operator** (`assets/fonts/PixelOperator.ttf`, CC0,
`pixelFontFamily()`). Its caps are ~0.56em (Press Start 2P, which it replaced, was
~0.88em), so plain `Text` sizes are ~1.5x what the old font needed. Japanese glyphs fall
back, glyph by glyph, to `PixelMplusJP.ttf` (`PixelTypeface.kt`, Typeface.CustomFallbackBuilder,
Android 10+): PixelMplus10 (M+ FONT LICENSE) re-declared at Pixel Operator's 1600 upm by
`scripts/gen_jp_font.py`, so both share the 100-unit pixel, and cut to Japanese only - anything
else Pixel Operator lacks (½, ◀) still falls to the system font, as before.

**European Emeralds** (BPES / BPED / BPEF / BPEI; `NATIVE_EMERALD_ES` / `_DE` / `_FR` / `_IT`): English's RAM and
save layout, their own ROM tables, and **names in the game's language** (the user's call): species / moves / items /
natures / map sections from each ROM (`scripts/gen_emerald_lang_tables.py` -> `EmeraldText<Lang>Gen.kt`), switched by
`NativeConfig.language` -> `romLanguage` -> `localEmerald` (types stay English: colours are keyed by name). The dex page
follows each game's wording and metric units; party art is per language (`partyem_<lang>/`); no TRAINER CARD yet. See
docs/DEVELOPMENT.md. **Japanese Emerald** (BPEJ, `NATIVE_EMERALD_JA`) is its own build: every RAM global and the battle
code moved (structs didn't - English's save loads), shorter ROM records (dex entries 0x1C, trainers 0x20, items 40,
names 6/8/10 bytes - `PokedexTables.entry*`, `GuideTables.trainer*`), kana text (`romLanguage == 'J'` switches
`Gen3Text` to its Japanese table; line breaks = full-width spaces), and the app's palette party slot (its fonts aren't
the Western ones the slot art draws with).

**Other-language FireRed / LeafGreen / Ruby / Sapphire** are generated, not hand-ported: `scripts/port_retail.py
<roms dir>` maps each ROM's addresses from its English one (literal pools, code bytes, content fallbacks, layout
detection) into `RetailPortsGen.kt` (`RETAIL_PORTS` by code + rev, consulted first by `otherRetailConfig`) +
`GameText<Code>Gen.kt` (names), and `scripts/verify_ports.py` checks every one headlessly against English (the English
saves load in them) -> `build/ports/report.md` / `fields.png`. Rerun both after touching a FireRed / LeafGreen / Ruby /
Sapphire config. `GameText` (EmeraldLanguages.kt) is keyed by game code (`NativeConfig.gameCode` -> `romGameCode` ->
`localText`). Japanese FireRed / LeafGreen can't read the English saves: verify_ports boots them with Japanese
ones (`firered_ja` / `leafgreen_ja`) and checks them absolutely.

**Languages** (`companion/i18n/`): the app's own text in EN / JA / FR / DE / IT / ES - not game
data (species / moves / items / types / statuses, which come from each ROM in its own language).
**GUIDE text** (issue #35: a French game's guide was half English) is translated for the games that come in
other languages - FireRed / LeafGreen, Emerald, Ruby / Sapphire, Unbound (FR), Quetzal (ES): `trGuide()`
(`i18n/GuideText.kt`) looks a whole hand-written line (entry, hint, answer, note, boss, HERE's building names) up
in `i18n/guide/GuideText<Game>.kt`, whose names are the localized games' own (from their ROM tables). Separate
from the app's `Tr*.kt` (not checked by check_translations.py); `GuideTranslationsTest` fails while one of
those guides has a line without all five languages and lists them in `app/build/guide-missing/`. A new or
changed line in those guides needs its translations; English-only hacks' guides stay English.
`tr("ENGLISH")` looks the English up in the `Tr<Area>.kt` tables (one line per entry, parsed by
`scripts/check_translations.py`; `TranslationsTest` runs the same check), `tr("{0} LEFT", n)` for
arguments - never a `$template` key. `tk()` marks a string the code compares (selector options,
enum labels, GUIDE state keys) - it stays English and the OPTION pieces (OptionLine label/value,
OptionTitleWindow, OptionButton, OptionBadge, group titles) translate what they draw. `L10n.language`
is Compose state: a pick redraws both screens at once. Prefs.appLanguage AUTO = the ROM header's
language letter (BPR**E**/F/D/I/S/J - every ROM the companion supports today is E) in game, the
device's in the Library / Settings. Japanese is kana only, like Gen 3's; translations use the
localized games' own words and stay about as short as the English (chips and buttons are sized
for it - `LocalizedScreenshotTest` renders the main screens per language). New UI text: wrap it
and add its five translations in the area's table.
