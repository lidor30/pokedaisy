# PokeDaisy - developer notes

How PokeDaisy is built, how each game is read, and how to work on it. For what
the app does and how to install it, see the [README](../README.md).

Dual-screen Android app for the AYN Thor and the Retroid Pocket Duo Lite (its
4:3 1280x960 bottom screen: `DuoLiteCompanionScreenshotTest`; the original Duo
should work too): an embedded **mGBA** core on the top screen + a live-data companion UI
for Gen 3 Pokémon games and ROM hacks on the bottom screen, in one process.
RetroArch-style savestates and speed control on configurable shortcuts.

**Bottom screens.** The companion goes on the first presentation display, or
failing that any other public non-default display (`syncPresentation()`). The
Thor's bottom screen is 1240x1080 (near-square, landscape); the Duo's is 1280x960
(4:3, landscape). Every tab was checked in Compose renders of the Duo's screen
at 320 and 400 dpi; the only layout change it needed is the Settings tab's
compact mode (game name in the title window, tighter rows). Not yet run on a
real Duo, so how its second screen is exposed to apps is still unconfirmed.

See [PLAN.md](../PLAN.md) for the full design and phase breakdown.

## Status: Phase 3 (bottom screen) — data + presentation verified on the Thor

What works:

- `libmgba` (vendored submodule, pinned `0.10.5`) builds for `arm64-v8a` via the
  NDK with all frontends/deps stripped — see [app/src/main/cpp/CMakeLists.txt](../app/src/main/cpp/CMakeLists.txt).
- JNI bridge [`pokedaisy_jni.c`](../app/src/main/cpp/pokedaisy_jni.c): load ROM,
  attach SRAM, run frames, expose the RGBA framebuffer + interleaved s16 stereo
  audio, take key input, `mCoreSaveStateNamed`/`LoadStateNamed`.
- RetroAchievements: `rcheevos` (vendored submodule, pinned `v12.5.0`) built into the
  same library; [`pokedaisy_ra.c`](../app/src/main/cpp/pokedaisy_ra.c) runs its
  `rc_client` over the player's core (softcore only so far): sign-in in Settings, unlock popups and
  an ACHIEVEMENTS tab on the bottom screen.
- Kotlin shell: `EmulatorView` (GLES2 aspect-fit blit), `EmulatorEngine` (emu
  thread, audio-paced, services savestate requests between frames), `GbaInput`
  (gamepad + keyboard → GBA bitmask), `Hotkeys` (configurable), `SaveStates`
  (per-game CRC folder), `PokeDaisyActivity` (full-screen, ROM auto-discovery,
  HUD).
- **Savestates**: slots 0–9 in `files/states/<rom-crc32>/ss<N>` (mGBA's own
  extended `.ss` format) + a `ss<N>.png` thumbnail. On-screen HUD confirms each
  save/load.
- **Suspend/resume**: backgrounding writes a `resume` state and quitting the loop;
  next foreground boots and loads it (no more fresh-boot-on-resume).
- **Speed control**: hold or toggle fast-forward (capped at 2× by default, Settings > FF SPEED, `EmulatorEngine.ffMaxSpeed`,
  0 = unlimited), a 1×/1.5×/2×/3×/4× cycle, and hold-to-slow-mo (½×). Audio is
  muted at any speed ≠ 1× (proper resampling is Phase 4). Frame pacing is exact
  (measured 90/120/180/240 fps for the cycle steps).
- **Bottom screen**: the Compose companion UI (Party / Map / Items,
  battle panel, mon detail) in `companion/` (it started as a standalone
  Android companion app), shown on the Thor's Screen-2
  via a `DualScreenPresentation`. Its old UDP transport is replaced by
  `InProcessReader` → `MgbaCore.pkReadBytes` (bus reads on the emulator thread,
  ~1×/sec, via `EmulatorEngine.onSample`). Game auto-detect from the ROM header;
  vanilla FireRed and Unbound use native-RAM reads.

Bottom screen confirmed rendering on the Thor's Screen-2; two early crashes
fixed (a `ConcurrentHashMap` null-value NPE on missing sprites, and a Compose
`Dialog` that can't open a window inside a `Presentation` — the mon-detail
popup is now an in-composition overlay). 90 s interaction soak = 0 crashes.
**Rule for this UI: no `Dialog`/`Popup` inside the Presentation.**

## Status: Phase 4 (polish) — in progress

- **Dynamic mon/item icons (done, verified):** `DecompIconSource` decodes party +
  bag sprites straight from the running ROM through `gMonIconTable` /
  `gMonIconPaletteTable` / `gMonIconPaletteIndices` / `gItemIconTable` (`Gfx.kt`
  has the shared 4bpp / LZ77 decoders), falling back to the bundled PNGs.

- **ROM library (done, verified):** `LibraryActivity` is the launcher — pick a
  ROM from `files/roms/`, import one via the system file picker, tap to play,
  "last played" is remembered. In-game, **hold BACK** to return to the library.
- **Library: Recently Played row + Grid view + SteamGridDB cover art (done,
  compiled only — not yet tested on a physical device):** the header's toggle
  switches between the existing list and a tile grid (uniform-size tiles via
  `minLines`/`maxLines` on the title text, so a short name doesn't shrink a
  tile, and a square 1:1 cover ratio matching SteamGridDB's icons — see
  below); a "RECENTLY PLAYED" row (last 3 distinct ROMs played) is the first
  item inside that same scrollable list/grid (not a fixed header above it),
  so it scrolls away with the rest, styled as list rows or tiles to match the
  active view mode.

  Covers are fetched live from [SteamGridDB](https://www.steamgriddb.com)'s
  **icons** endpoint (square, app-icon-style images — not its portrait "grid"
  box art, which looked wrong forced into a compact list row) using a
  **user-supplied key** (Settings > Cover Art — `Prefs.steamGridDbApiKey`) —
  nothing is bundled in the APK; a fetched image is cached under
  `files/covers/` after first use. `SteamGridDbGames.kt` maps
  each **specific ROM hack release** (not just "FireRed vs. Emerald" — most
  hacks share their base game's header code, so that alone can't tell Unbound
  from vanilla FireRed from Radical Red) to a SteamGridDB game ID, keyed by
  the ROM's whole-file SHA1 (`RomIdentity.kt`) — deliberately the _same_ hash
  values `Poller.kt` already uses for live hack detection (both confirmed to
  equal a plain file hash). Covered today: Unbound, Gaia, Radical Red,
  Odyssey, Heart & Soul, Lazarus, R.O.W.E., Emerald Rogue (Amethyst isn't
  catalogued on SteamGridDB yet). Vanilla retail FireRed/LeafGreen/Emerald
  aren't in this table on purpose and fall back to the
  placeholder + manual picker below.

  **Two earlier approaches were tried and reverted before landing here** —
  worth knowing if this ever needs revisiting: (1) booting the ROM headlessly
  and grabbing a live frame reliably landed on an early, uninteresting boot
  frame instead of the title screen (a "boot N frames" heuristic proved too
  fragile); (2) compositing a cover from each game's own decomp title-screen
  source assets (`graphics/title_screen/` in pokefirered/pokeemerald) worked
  correctly but only covers the base games, not the actual ROM hacks this was
  meant to help with, and the user judged the composited result low-quality
  anyway. `hackdex.app` (a ROM-hack-specific cover-art site) was considered
  too, but has site-wide bot-mitigation blocking automated fetches entirely.

  Any ROM not in the SteamGridDB table (or fetched with no API key set yet)
  falls back to the placeholder icon + manual **Set Cover** picker (any
  image, center-cropped to a square); **Reset Cover** re-queues a fetch. A
  manually-set cover is never overwritten by the Refresh-triggered
  backfill pass.

  Tapping **Save** on the API key immediately scans the whole library and
  shows live progress (a bar + a per-ROM outcome log — Fetched / Cached /
  Manual / Not supported / No icon found) right there in Settings, via a
  small shared helper (`CoverArtSync.kt`) also used by Library's Refresh
  backfill — no need to separately go check the Library screen to see
  whether the key actually worked. The key input field is wrapped in a
  `GbaWindow` (cream panel) rather than sitting directly on the screen's
  dark background — its text color is designed for a light panel and was
  barely legible without one. Verified via `./gradlew assembleDebug` +
  `testDebugUnitTest` only.

- **In-app settings (done, verified):** `SettingsActivity` (from the Library) —
  a fast-forward cap stepper and a hotkey list where **Rebind** captures the
  next key/button. Persists to `hotkeys.properties` + prefs; picked up on
  return to the emulator.
- **Fast-forward audio (done):** speeds of 1.5–8× now play sped-up, pitched-up
  audio (sample decimation) instead of silence. Slow-mo and uncapped FF still
  mute.
- **Launcher icon (done):** adaptive icon, no longer the stock Android robot.
- **Per-game default speed (done):** the speed-cycle position is remembered per
  ROM and restored on next launch.
- **Emerald support (done):** `NATIVE_EMERALD` config for vanilla Emerald
  (party / battle / bag / coords via native RAM) + a Hoenn map-section table
  (`MapSecDataEmerald.kt`) and Hoenn region map, so vanilla Emerald
  shows real Hoenn location names and the map.
- **Bottom-screen savestate panel (done):** a "States" tab on the companion —
  a grid of the 10 slots with thumbnails; tap to load, "Save here" to save.
- **On-screen controls (done):** a translucent D-pad + A/B + Start/Select + L/R
  overlay; auto-shown only when no gamepad is connected (Settings →
  "On-screen controls": Auto / Always / Never).
- **OPTION-screen restyle (done, rendered via `ui-preview` only — not yet
  checked on the Thor):** the companion's SETTINGS and STATES tabs and the
  top-screen Library/Settings now share FireRed's OPTION-screen look
  (`companion/ui/GbaMenu.kt`: white title window, grey list window,
  `LABEL  VALUE` rows, white framed buttons) over the game's party-menu
  backdrop; multi-choice settings (FF speed, touch pad, theme) open a pick-list
  instead of cycling. The app's font is now Pixel Operator throughout (Press
  Start 2P removed). The Map tab crops FireRed's black/white region-map border
  and sits in a rounded frame. Savestate thumbnails were being built from a
  _premultiplied_ bitmap fed mGBA's junk 4th byte as alpha, which wrecked their
  colors ("shades" with missing patches); they're now built from the RGB bytes
  directly — slots saved before this keep their old broken thumbnail until
  re-saved. To see UI changes without a device: Paparazzi
  (`make shots`) or [`ui-preview/`](../ui-preview/README.md).
- **Pokémon summary screen (done, `ui-preview` only — not yet on the Thor):**
  tapping a party slot now opens a full-tab summary (`MonDetailScreen.kt`)
  instead of a pop-up: name/gender/level/types/HP top-left, weaknesses /
  resistances / immunities bottom-left, the four moves (type, power, PP, and
  per-foe multipliers in battle) on the right. Square battle-style buttons
  with pixel icons: up/down cycle the party (wrapping), a U-turn arrow goes
  back; the party's icons sit between them and jump on tap. It's one window split by rules
  rather than separate cards.
- **Pixel-art pass (done, `ui-preview` only):** every corner in the app is a
  whole-GBA-pixel staircase (`PixelShapes.kt`) instead of a smooth arc; the
  battle strip's Poké Balls and the loading ball are pixel art; the companion
  tabs use the OPTION-screen look with bigger text; battle buttons and move
  cards have larger text. FireRed/Emerald species, move and item names show in
  the game's capitals (`gameCase()`).
- **Launch fixes (done, `ui-preview` only):** the companion used to keep the
  previous game's backdrop after a launch (it read a global Compose can't
  observe) - snapshots now carry their game and the UI is keyed on it. Until
  the first real data arrives the data tabs show a rocking Poké Ball
  ("LOADING..."); STATES and SETTINGS stay usable meanwhile.
- **Battle INFO / SUGGESTIONS (done, `ui-preview` only):** both are now
  full-tab panes in the Pokémon summary's look (one white window, rules
  between sections, Back bottom-right) instead of an overlay / a cramped
  panel under a "◀ CONTROLS" chip (which, after the tab restyle, had grown to
  fill the whole tab). Move buttons: bigger text, PP bottom-right. Screenshot
  tools now use the Thor's real bottom screen, 1240x1080 landscape.
- **EXP (done for FireRed/Emerald, fixture-tested, not yet on the Thor):** the
  summary shows an EXP bar ("NEXT n" to the next level). It's read from the
  party's own RAM struct (decrypted Growth substruct); thresholds come from the game's growth-rate tables
  (`data/ExpTables.kt`, generated by `scripts/gen_exp_tables.py`).
- **Move advice (the original games so far):** PARTY > a Pokémon's summary >
  LEARN lists its next level-up moves and the TMs / HMs in the bag it can learn,
  each rated LEARN / FORGET <move> / SKIP with its reasons; a TM selected on
  ITEMS shows the party as ABLE / NOT ABLE / LEARNED with the BEST pick. The
  scoring is `data/MoveAdvice.kt` (`MoveAdvisor`, `MoveAdviceTest`); accuracy,
  PP and effects come from `data/MoveStatsGen.kt`, generated by
  `scripts/gen_move_stats.py` from the pinned pokefirered's gBattleMoves.
- **Battle controls, Platinum pass:** big centred FIGHT, BAG / POKéMON in the
  bottom corners with RUN lower between them, INFO / SUGGESTIONS beside the
  six-slot ball strip (grey discs for empty slots), black-outlined buttons with
  a light top band and dark side bands, shadowed labels; the "busy" dots are a
  rocking Poké Ball. INFO and SUGGESTIONS link to each other bottom-left.
- **No bundled sprites (done):** every game now decodes party/bag icons
  straight from the running ROM (retail FR/Emerald
  via fixed addresses from a vanilla decomp build, Unbound via its own reader).
  The ~3 MB `assets/pokemon` + `assets/items` PNG set was removed.
- **No bundled FireRed / Emerald art (done):** their party-menu slot, Poké
  Ball, status icons, small font, party backdrop and the Kanto/Sevii/Hoenn
  region maps are rebuilt from the player's ROM the first time it runs
  (`RomArt`, <1 s in the background) and cached in the app's files. Heart and
  Soul's and the CFRU hacks' party art (`partyhns/`, `partycfru/`, their
  backdrops) followed (2026-10-09): found by fingerprint in their ROMs
  (`HNS_*`, `CFRU_*`, `<hack>_STATUS_GFX` in `scripts/gen_rom_art_sigs.py`),
  pixel-identical to the PNGs that were bundled. Only the fonts are bundled now.
- **Rewind:** tried, reverted — mGBA's non-threaded rewind (a full savestate
  every frame) white-screened the emulator. Revisit with `mCoreThread` or a
  coarse snapshot interval.

Not done yet: Hoenn mapsec table, touch overlay.

## Supported ROMs

The Library (`files/roms/`) can hold any of these; the app auto-detects which
one is running (SHA1 of a live core-memory read for the ROM hacks below, the GBA header's game code (and
revision byte) for plain vanilla FireRed/Emerald/LeafGreen/Ruby/Sapphire — see
`Poller.kt`'s `detect()`) and switches
species/item/map tables and RAM addresses accordingly (`ActiveTables.kt`).

✅ = verified live (has a passing fixture-based JVM decode test under
`app/src/test/`, see below; for DEX, checked against the game's own POKéDEX screen) · ⚠️ = wired up but unverified/partial · ❌ = not
working · — = not attempted.

| ROM                                       | Base                                                                                                       | Party/Lvl/HP          | Battle                                                                                                                                                                                                                                                                                                                                                                                          | Bag/Items                                 | Map/Location                                                       | Sprites                                                                                                                                                                                                                                                                                                                                                  | DEX                                                                      | Notes                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------------------- | --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- | ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Vanilla FireRed** (rev 0/1)             | retail                                                                                                     | ✅                    | ✅                                                                                                                                                                                                                                                                                                                                                                                              | ✅                                        | ✅                                                                 | ✅                                                                                                                                                                                                                                                                                                                                                       | ✅ rev 1 (rev 0: no ROM on file to check)                                | Native RAM, addresses from a byte-verified decomp build.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **Vanilla Emerald**                       | retail                                                                                                     | ✅                    | ✅                                                                                                                                                                                                                                                                                                                                                                                              | ✅                                        | ✅                                                                 | ✅                                                                                                                                                                                                                                                                                                                                                       | ✅                                                                       | Native RAM, addresses from a vanilla `pokeemerald` ELF (sha1-matched to retail).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Vanilla LeafGreen** (rev 0/1)           | retail (BPGE)                                                                                              | ✅                    | ⚠️ FireRed's battle addresses (identical in the map), not battle-tested                                                                                                                                                                                                                                                                                                                         | ✅                                        | ✅ (Kanto)                                                         | ✅ mon + item icons                                                                                                                                                                                                                                                                                                                                      | ✅                                                                       | `NATIVE_LEAFGREEN_REV0/1`. pret/pokefirered @ c75f352 `leafgreen`/`leafgreen_rev1` build byte-identical to retail (sha1 `574fa542…`/`7862c67b…`); every RAM global and the battle-controller code sit at FireRed's own addresses for the same revision, only ROM data moves (icons, dex tables, LeafGreen's own dex text). Shown with the FireRed look.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| **Vanilla Ruby** (rev 1/2)                | retail (AXVE), pokeruby engine                                                                             | ✅                    | ✅ scripted wild battle captured headless (`sapphire_rev1_battle`); pokeruby's controller functions, touch input + one-press picks (no POKéMON switch: its party cursor is a sprite's)                                                                                                                                                                                                                                                                                                                         | ✅ (no item icons — the R/S bag has none) | ✅ (Emerald's Hoenn table: same mapsec ids)                        | ✅ mon icons                                                                                                                                                                                                                                                                                                                                             | ✅                                                                       | `NATIVE_RUBY`. pret/pokeruby `ruby_rev1`/`ruby_rev2` build byte-identical to retail (sha1 `610b96a9…`/`5b64eacf…`) and share every address. Differs from Emerald: party in IWRAM (`0x03004360`), **static** save blocks (SB1 `0x02025734`, SB2 `0x02024EA4` — `NativeConfig.staticSaveBlocks`), `gBagPockets` is a const ROM table pointing into SaveBlock1, no bag-quantity XOR key, `gMain.inBattle` at +0x43D. Rev 0 is reported unsupported (no rev 0 ROM to check). Shown with the Emerald look.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **Vanilla Sapphire** (rev 1/2)            | retail (AXPE), pokeruby engine                                                                             | ✅                    | ✅ as Ruby (its battle was the one captured)                                                                                                                                                                                                                                                                                                                                                       | ✅ (no item icons)                        | ✅ (Emerald's Hoenn table)                                         | ✅ mon icons                                                                                                                                                                                                                                                                                                                                             | ✅                                                                       | `NATIVE_SAPPHIRE`: Ruby's RAM layout (all four pokeruby maps agree), its own ROM addresses; `sapphire_rev1`/`sapphire_rev2` byte-identical to retail (sha1 `4722efb8…`/`89b45fb1…`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| **Pokémon Unbound** v2.1.1.1              | CFRU/ASM hack on FireRed rev 0                                                                             | ✅                    | ⚠️ static-verified, not live-battle-confirmed                                                                                                                                                                                                                                                                                                                                                   | ✅                                        | ✅                                                                 | ✅ (live ROM fetch)                                                                                                                                                                                                                                                                                                                                      | ✅ Borrius + National                                                    | Own species/item tables (renumbered dex); "Cube" tab label instead of "Items".                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **Pokémon Unbound** v2.1.1.1 FR (French fan translation) | the same release with its text translated (32 MB, sha1 `0ce2a880…`, revision byte 0x9E) | ✅ (`unbound_fr`: English's RAM, every literal pool unchanged) | ⚠️ as English | ✅ | ✅ its Borrius map from the ROM (`RomRegionMap` now reads any revision byte but rev 1's) | ✅ (live ROM fetch) | ✅ Borrius + National, French dex text ("Pokémon Graine", metric) | `NATIVE_UNBOUND_FR`. Names in French (`UnboundTextFrGen.kt` from `scripts/gen_unbound_fr_tables.py`, a `GameText` over Unbound's own move / map tables, `UNBOUND_FR_TEXT`): species, moves (to 893 - the Max Moves past it keep English's), items, natures, map sections. Item descriptions, dex entries and map names were translated in place, so they read French from the ROM. Its trainers are renamed too (MIRSKLE is Sylvain); the GUIDE's hand-written boss titles stay English. |
| **Pokémon Gaia** v3.2                     | ASM hack on FireRed rev 0                                                                                  | ✅                    | ⚠️ not individually re-checked                                                                                                                                                                                                                                                                                                                                                                  | ✅                                        | ⚠️ new region, mapsec names not yet re-derived                     | ✅ (relocated tables found via still-identical helper-function literal pools)                                                                                                                                                                                                                                                                            | ✅ one dex (721)                                                         | Own relocated species table (~930 species, `SpeciesNamesGaia.kt`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **Pokémon Radical Red** v4.1              | ASM hack on FireRed rev 0                                                                                  | ✅                    | ⚠️ touch battle input unverified (reworked UI)                                                                                                                                                                                                                                                                                                                                                  | ✅ own names (`ItemNamesRadicalRed.kt`) + descriptions (its `gItems`, from the ROM) + icons | ⚠️ untested (inherits rev-0 addresses)                             | ✅ own icon tables (`gMonIconTable` 0x097FE6CC, item icons 0x093C8100)                                                                                                                                                                                                                                                                                    | ✅ Kanto + National (1025)                                               | Everything from its own ROM by `scripts/gen_cfru_tables.py`: `gSpeciesNames` (its own ids, to 1349 - not National Dex numbers), long move names + `gBattleMoves`, `gItems` (by `.itemId`), `gBaseStats` types. CFRU repoints FireRed 1.0's literal pools, so each table is the word retail rev 0 loads at the same offset. Fixture `radical_red_1636`: a later save with Gen 7-9 species and items.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **Pokémon Odyssey** v4.1.1                | ASM hack on FireRed rev 0                                                                                  | ✅                    | ⚠️ untested guess                                                                                                                                                                                                                                                                                                                                                                               | ✅                                        | ⚠️ untested guess                                                  | ⚠️ untested guess                                                                                                                                                                                                                                                                                                                                        | ✅ Talrega + National (409)                                              | Basic connectivity only so far — inherits rev-0 addresses unchanged, no custom species/item tables built yet.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| **Pokémon Heart and Soul** v2.0.6         | newer `pokeemerald-expansion` (RHH fork), 32 MB                                                            | ✅                    | ✅ one wild battle captured headlessly (expansion `BattlePokemon` is 0x88 bytes, `NativeConfig.battleMonLayout`). Touch battle input not derived                                                                                                                                                                                                                                                | ✅ id/qty/pocket + real names (6 pockets) | ✅ its Johto + Kanto Pokégear map, rebuilt from the ROM (smol, `HNS_REGION_*`) + cursor grid | ✅ mon + item icons (item tiles use the expansion's **smol** compression, `Smol.kt`). **Party tab** draws the game's own slot and backdrop (`partyhns/`, rebuilt from the ROM by `RomArt` - smol `HNS_PARTY_*` blobs; layout from its source's `graphics/party_menu/hns/` via `scripts/gen_party_assets.py`, pixel-diffed against the real party screen); **Items tab** uses HnS's bag colours and its own item descriptions | ✅ Johto + National (1080)                                               | Redone 2026-09-27 against the **release** ROM with headless captures. The earlier from-source build's addresses were 4 bytes off in upper EWRAM, which is why the party showed empty. `species:11\|teraType:5` packing like TMT2; SaveBlock1 has 4 extra leading bytes (`saveBlock1PosOff = 4`). Own tables (`*Hns.kt`) generated by `scripts/gen_expansion_tables.py`. STEADY FF music via `M4aSongs`' Heart and Soul signature (expansion's `m4aSongNumStart` + an `alt` argument for its alternate soundtrack table; both soundtracks are rendered).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| **Pokémon Lazarus** v2.0                  | `pokeemerald-expansion` (BPEE, "PRET x RHH" splash), no source available                                   | ✅                    | ✅ battle mons, types, moves, foe party, touch controls (2-column party grid) - headless scripted wild battle (`lazarus_battle`)                                                                                                                                                                                                                                                                 | ✅ id/qty/pocket + real names             | ✅ its own region map (`regionmap/lazarus.png` rebuilt from the ROM by `RomArt`) + cursor grid | ✅ mon + item icons                                                                                                                                                                                                                                                                                                                                      | ⚠️ Ilios + National; flags matched to the save, not to the game's screen | Party/bag found empirically from live memory back in September (see `NATIVE_LAZARUS`). On 2026-09-27, headless captures added gMain/SaveBlock1/map header/object events, and the ROM gave its own species/item/move/type/location tables (`*Lazarus.kt`, same generator as Heart and Soul). The "2 extra pockets" were NULL slots. There are 5 real pockets in Emerald order. CARD: Emerald's card code with its own art and font (`LZ_*`), 4 POKéDEX digits, its own count of drawn stars (the HALL OF FAME, flags 0x29 / 0x4A6, the frontier symbols) while the colour follows retail's, and a regional loop that reads one bit past the dex (`TRAINER_CARD_LAZARUS`). |
| **Pokémon SoulGold** v1.1.4, v1.2, v1.2.1 | newer `pokeemerald-expansion` (BPEE, 32 MB, gcc), no source available | ✅ (its own plaintext 96-byte struct Pokemon) | ✅ battle mons (0x98-byte BattlePokemon), touch controls - headless scripted wild battle (`soulgold_battle`); trainer battles not checked | ✅ 8 pockets, names + descriptions | ✅ its own Johto map, rebuilt from the ROM (smol art, u16 cursor grid at offset (1, 1)) | ✅ mon + item icons (smol item tiles) | ✅ Johto (702) + National, matched to the game's own POKéDEX | Everything from headless captures of the user's save - see `NATIVE_SOULGOLD`. ROM tables from `gen_expansion_tables.py soulgold`; the bag's night sky is rebuilt from the ROM by `RomArt` (smol tiles + tilemap, `SG_BAG_STARS_*`). STEADY FF music via `M4aSongs`' newer-expansion signature. v1.2 (`NATIVE_SOULGOLD_V1_2`) is a rebuild: v1.1.4's addresses matched into it (literal pools / masked byte runs), checked on the same save headlessly (`soulgold_v12`, `soulgold_v12_battle`); it shares v1.1.4's tables but for TM75 (`soulGoldV12`). A second build also released as v1.2 (sha1 `5d6a0362…`, `NATIVE_SOULGOLD_V1_2B`) keeps every RAM address and battle handler of the first and moves only ROM data (0x98 / 0xA4 bytes); same tables, checked on the same save (`soulgold_v12b`, `soulgold_v12b_battle`). A third v1.2 build (sha1 `2be7c99a…`, `NATIVE_SOULGOLD_V1_2C`) keeps the same RAM and battle handlers too; its ROM data moved 0x18C-0x198 bytes, the wild / map tables 0x2DF30 (new data before them), same tables (`soulgold_v12c`, `soulgold_v12c_battle`). CARD: Emerald's layout with its own smol art and font (`SG_*`), mixed-case words, 7 money / 4 POKéDEX digits, stars for the HALL OF FAME, the regional dex minus legendaries, a Battle Frontier record past 49 and 80 of its 120 achievements (`TRAINER_CARD_SOULGOLD` / `_V1_2`: v1.2 marks six more legendary); money caps at 9,999,999. |
| **Pokémon R.O.W.E.** 2.1.9.1 Experimental | Emerald-based (BPEE, 32 MB), BelialClover's own pokeemerald fork (no expansion), no source for v2.x | ✅ its bit-packed 0x4C-byte struct (`ROWE_PARTY_MON`) | ✅ scripted wild battle captured headless (`rowe_battle`); touch controls, 2-column party grid | ✅ 10 pockets | ✅ its three region maps (Hoenn / Kanto / Sevii, `RW_*`) | ✅ mon + item icons | ⚠️ National (1019), flags matched to the save, not to the game's screen (no POKéDEX on its menu yet) | The ROM's own symbol table (0x08FB0670) names most globals. Tables: `scripts/gen_rowe_tables.py`. No CARD / GUIDE: its system flags were renumbered. |
| **Emerald Rogue** v2.2.1-EX               | Emerald-based (BPEE), no source available                                                                  | ✅ (see caveat below) | — not attempted                                                                                                                                                                                                                                                                                                                                                                                 | ❌ not found                              | — not attempted                                                    | ❌ (inherits vanilla Emerald's icon addresses, unverified)                                                                                                                                                                                                                                                                                               | ✅ National (1025) + its MODERN list (400): a 2-bit state per species id in SaveBlock1, matched to its own dex | Species by National Dex too (shares `SpeciesNamesNationalDex.kt`). **Address-stability cautionary tale**: two live-memory scans each found a pair of stable-looking party candidates; the first pick (matching vanilla's exact `SaveBlock1+0x238` struct offset _and_ confirmed by an IWRAM pointer) shipped and immediately decoded garbage in real play — a 3rd, later capture showed that address had silently drifted (a relocatable scratch buffer, not a real global), while the address that looked "less confirmed" had stayed byte-identical all along. Fixed by switching to the one proven stable across 3+ independent captures. Bag hunting (both the Lazarus-style XOR-key pattern search and a raw cross-capture byte-stability scan) didn't converge on a confident candidate.                                                                                                                                                                                                                                                                                                                                                                           |
| **Emerald Seaglass** v3.0                 | Emerald-based (BPEE, 16 MB), expansion-style recompile, no source available                                | ✅                    | ✅ battle mons, types, moves, touch controls - headless scripted wild battle (`emerald_seaglass_battle`); its own move / species-type tables (`gen_expansion_tables.py seaglass`) | ✅ id/qty/pocket + real names             | ✅ its own Hoenn art (`SGL_REGION_GFX`, rebuilt from the ROM) on Emerald's tilemap and grid | ✅ mon + item icons (expansion keeps them inside `gSpeciesInfo`/`gItemsInfo` entries — `IconTables` got stride/mask fields for that; decoded offline and checked visually; TMs have no icon, expansion draws those per move type)                                                                                                                        | ✅ Hoenn + National (430)                                                | First hack added **fully headless** — no device: `scripts/capture_fixture_headless.sh emerald_seaglass`, then 4 extra captures with varied boot timing (so a different RNG seed and save-block offset each) to prove every address stable across 5 captures (see `NATIVE_EMERALD_SEAGLASS`). Exactly 16 MB, so `Poller.detect()` got a new hash check for 16 MB BPEE ROMs (unknown hashes still fall through to vanilla Emerald). Species by National Dex; items from the ROM's own table (`ItemNamesSeaglass.kt`, 827 entries). Detection hash not yet confirmed on-device.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Celia's Stupid Romhack** v1.1.4         | FireRed-based (BPRE rev 0), full `pokefirered` recompile, no source available                              | ✅                    | ⚠️ addresses from ROM literal-pool analysis only (no battle reachable headlessly). **Types ✅**: 34 types (joke Brock/Weird/Dad/Choco/…), a 34×34 chart (32-bit uq4_12 matrix — every vanilla chart-reading code site was rewritten, so it's not vanilla's triplet list; includes a joke 5× and 0.2×), species types, and move type/power from a 16-byte move record (`TypeChartCelia.kt` etc.) | ✅ id/qty/pocket + real names             | ✅ (vanilla Kanto mapsec ids)                                      | ✅ mon + item icons (checked by decoding + visually)                                                                                                                                                                                                                                                                                                     | ✅ one dex (150)                                                         | Custom dex order (1 = VICTINI, 3 = CHARMANDER), renumbered moves (1189), joke renames — own species/item/move-name tables (`*Celia.kt`). **Stale-table trap**: the ROM also holds an unreferenced vanilla-ordered species table that decoded the starter as VENUSAUR; the game's own party screen (`mgba_dump`'s new `shot` command) showed CHARMANDER, and a literal-pool reference count (48 vs 0) picked the live table. Encryption key moved to SaveBlock2+0xB18.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **Too Many Types 2** v1.5.2               | Emerald-based (BPEE, 32 MB), newer `pokeemerald-expansion` recompile, no source available                  | ✅                    | ✅ battle mons (expansion's 0x60 bytes, PP at +0x26), foe party, touch controls - headless scripted wild battle (`tmt2_battle`). **Types ✅**: all 82 type ids (60 joke types), the 82×82 chart, species types and move type/power extracted from the ROM (`TypeChartTmt2.kt` etc.)                                                                                                                                                              | ✅ id/qty/pocket + real names (7 pockets) | ✅ (vanilla Hoenn mapsec ids)                                      | ✅ mon + item icons (item palettes are raw, not LZ77)                                                                                                                                                                                                                                                                                                    | ✅ Hoenn + National (1052)                                               | Newer expansion packs `species:11\|teraType:5` (and moves/PP) into the BoxPokemon fields — `NativeConfig` gained `speciesMask`/`moveMask`/`ppMask`. National Dex order only to 905, forms after, so own species/item/move-name tables (`*Tmt2.kt`). Pocket order read from each item's own `gItemsInfo` pocket field.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **Pokémon Glazed** 9.2.0                  | Emerald-based (BPEE, 32 MB), an in-place binary edit of retail Emerald, no source                          | ✅                    | ✅ wild battle captured headless (`glazed_battle`); retail's RAM, layout and battle handlers                                                                                                                                                                                                                                                                                                   | ✅ (retail's 5 pockets)                   | ✅ its own Tunod / Johto map, rebuilt from the ROM (`GZ_REGION_*`) | ✅ mon + item icons (retail's tables, its own art)                                                                                                                                                                                                                                                                                                       | ✅ retail's tables rewritten in place (its own 1..386, Tunod as "HOENN") | Retail Emerald's RAM and code; its own names in retail's 412 species / 355 move / 377 item slots, FAIRY as type 9, a repointed type chart: `scripts/gen_glazed_tables.py`. |
| **Emerald Imperium** v1.3.1               | Emerald-based (BPEE, 32 MB), `pokeemerald-expansion` 1.10.0 (gcc), no source                               | ✅                    | ✅ wild battle captured headless (`imperium_battle`); expansion `BattlePokemon`, touch input + grid party menu                                                                                                                                                                                                                                                                                 | ✅ 6 pockets (Mega Stones folded into Items) | ✅ Hoenn ids + 5 of its own; its map rebuilt from the ROM (`IMP_REGION_*`) | ✅ mon + item icons                                                                                                                                                                                                                                                                                                                                      | ✅ Hoenn (214) + National (1025), from gSpeciesInfo | 1.10 packs species / moves (11 bits), PP (7) and experience (21 bits, the nickname's 11th character above it). Tables: `gen_expansion_tables.py imperium`. |
| **Pokémon Quetzal** English Alpha 9 v0    | Emerald-based (BPEE, 32 MB, "PKM QUETZAL"), its own engine on pokeemerald, no source                       | ✅ its own 104-byte plaintext struct (`QUETZAL_PARTY_MON`) | ✅ scripted wild battle captured headless (`quetzal_battle`); expansion `BattlePokemon`, touch input                                                                                                                                                                                                                                                                                           | ✅ its "BAG3" bit stream in SaveBlock1 (`Bag3Layout`); no TMs | ✅ its Kanto / Sevii maps rebuilt from the ROM (`QTZ_*`), FireRed's rects shifted (-3, -2); its Hoenn is Emerald's map byte for byte and its Johto its own (`QTZ_JOHTO_*`, Emerald's palette), the ROM's boxes +1, +2, no cursor grid | ✅ mon + item icons                                                                                                                                                                                                                                                                                                                                      | ✅ National (1034): 12-byte entries by species, 3-bit seen levels, caught per region | SaveBlock1 is its own (position +0x470, money +0x918, key SB2+0x2C). HP is 10 bits at +0x23, the egg flag the IV word's bit 30. Wild Pokémon walk the grass (gEnemyParty holds them on the field). Tables: `scripts/gen_quetzal_tables.py`. |
| **Pokémon Quetzal** Spanish Alpha 9 v0 | the same engine rebuilt with Spanish dialogue / descriptions / dex text (sha1 `fe346b5b…`) | ✅ English's RAM (`quetzal_es`: six Lv42s) | ✅ scripted wild battle captured headless (`quetzal_es_battle`); its handlers 0x24 before English's | ✅ (3403 Master Balls - the save's own) | ✅ as English | ✅ mon + item icons (its own addresses) | ✅ "POKéMON Semilla", metric | `NATIVE_QUETZAL_ES`: ROM tables mapped from English's through the literal pools (`port_retail.py`'s matching). Quetzal - both releases - carries English, Spanish, Latin American Spanish and Portuguese names and picks them per kind with its own IDIOMA options (START > OPCIONES > TEXTO), kept in SaveBlock2 (+0x2E0..+0x2E2, +0x2F4): `readQuetzalNames`, `QuetzalNamesGen.kt` (`gen_quetzal_tables.py --languages`). Portuguese isn't generated (glyphs past the Western charmap): English there. Money goes past 999,999 (`maxMoney` 9,999,999). |
| **Pokémon Quetzal** Alpha 9 v1, English / Spanish | Alpha 9 v0 rebuilt (sha1 `c108cec7…` / `ff008b70…`): every ROM table moved, v0's RAM | ✅ v0's (`quetzal_v1` / `quetzal_es_v1`: the v0 saves, which v1 loads unchanged) | ✅ scripted wild battles (`quetzal_v1_battle` / `quetzal_es_v1_battle`); its handlers read in each menu, the one-press picks checked (v0's cursors) | ✅ a new record, BAG4 (`Bag4Layout`): v1 rewrites v0's BAG3 into it on load | ✅ as v0 | ✅ mon + item icons (its own addresses) | ✅ as v0 | `NATIVE_QUETZAL_V1` / `_ES_V1`. Every table checked equal to v0's, entry for entry with pointers followed (`gen_quetzal_tables.py --v1`, `QuetzalV1Test`); its only data changes are one move's PP and the Latin American names of Johto's key items (Portuguese in v0, `QuetzalV1Gen.kt`). See below. |
| **Pokémon Quetzal** Alpha 0.8.4, English / Spanish, + Moemon Hotfix builds | the release before Alpha 9 (Hoenn only, 34 map groups); Moemon = the same code with other pictures | ✅ Alpha 9's RAM for the party (`quetzal_084` & co.: Spanish Alpha 9's save, six Lv42s), its own gMain / save pointers | ✅ scripted wild battle captured headless in each (`*_084_battle`); cursors, handlers and the switch checked headless | ✅ vanilla gBagPockets (11 pockets) - the Alpha 9 save's bag is misread by the game itself, our decode matches its bag screen | ✅ Emerald's Hoenn map | ✅ mon + item icons | ✅ caught = seen level 2+ (SEEN 466 / OWN 347, as its dex) | `NATIVE_QUETZAL_V084*`: RAM and ROM through Alpha 9's literal pools, checked across three captures each; tables shared with Alpha 9 by id (`gen_quetzal_tables.py --v084` checks them) but 16 moves. The Moemon battle fixtures have their sprites zeroed. |
| **Pokémon Amethyst** v1.3.0, v1.4.1        | FireRed-based (BPRE), no source available                                                                  | ✅                    | ✅ battle mons (rev 0's RAM), foe party, touch controls (2-column party grid) - headless wild battle (`amethyst_battle`) | ✅ id/qty/pocket + real names             | ✅ (untested beyond one location)                                  | ✅ mon + item icons (visually confirmed)                                                                                                                                                                                                                                                                                                                 | ✅ own dex (390) + National (690)                                        | The easy case: kept vanilla FireRed's RAM layout entirely unchanged (`NATIVE_AMETHYST` needed zero RAM address changes, unlike the Emerald-based hacks) — only species/items and the icon-graphics ROM tables are renumbered/relocated. Species/item name tables extracted **directly from the ROM's own name strings** (`SpeciesNamesAmethyst.kt`/`ItemNamesAmethyst.kt`, same anchor-and-walk technique `gen_unbound_data.py` uses for Unbound): species 1-251 is vanilla National Dex order, 252-~411 is vanilla FireRed's own _internal_ (non-dex) Gen 3 order unchanged, and Amethyst's own Gen 4-8 additions are appended past that non-sequentially (up to id 1267). Icon tables found via Gaia's literal-pool technique — `GetMonIconTiles`/`GetItemIconGfxPtr` are still byte-identical CODE at their exact vanilla rev-0 addresses (the latter's address itself obtained by building the pinned pokefirered commit unpatched and reading its own symbol table, not guessed), just pointing at relocated DATA; both decoded end-to-end into real, recognizable icons (a Tepig, a Potion bottle) as verification, not left to a plausible-looking address alone. `gMonIconPaletteIndices` moved too (0x09C01408; the vanilla table first used was right for Tepig, wrong for 757 species). v1.4.1 (`NATIVE_AMETHYST_V1_4_1`) keeps the RAM (a v1.3.0 save loads, `amethyst_v141` fixture) and vanilla code; its own data moved (literal pools), and 26 Hisuian forms inserted at species 1234 renumber everything after, so it has its own names (`scripts/gen_amethyst_tables.py`, keyed by item slot - the records' itemId field is stale in a few), gender ratios and area data (`GuideId.AMETHYST_V141`), picked by `amethystV141`. |
| **Pokémon Orange Islands** (Beta 5.7 + an unversioned build) | FireRed-based (BPRE rev 0, **16 MB**), retail rev 0 edited in place, no source | ✅ (`orange_islands`) | ✅ scripted wild battle captured headless (`orange_islands_battle`, its CRYSTAL ONIX); retail rev 0's handlers byte for byte, touch input | ✅ | ✅ its Orange Archipelago over FireRed's four screens, from the ROM (`RomRegionMap`) | ✅ retail rev 0's tables | ✅ retail rev 0's tables, edited (`POKEDEX_ORANGE_ISLANDS`) | `NATIVE_ORANGE_ISLANDS`, `GameKind.ORANGE_ISLANDS`. 16 MB like retail, so detect() hashes small BPRE ROMs once too. Its own names, a 24th type CRYSTL (23) with its chart rows, rebalanced moves / base stats: `scripts/gen_orange_islands_tables.py`. The two builds differ in 80 bytes (a line of dialogue and a warp). No CARD: its trainer card is its own art (Ash, four badges). |
| **Pokémon Ultra Violet** v1.22 | FireRed-based (rev 0 code under its own game code **PUVV**, **16 MB**), retail rev 0 edited in place, no source | ✅ (`ultra_violet`) | ✅ scripted wild battle captured headless (`ultra_violet_battle`, a PIDGEY); retail rev 0's handlers, touch input, POKéMON switch (rev 0's party-menu code and RAM unchanged, as in Orange Islands) | ✅ | ✅ FireRed's map byte for byte, a few sections renamed (CATACOMBS, DREAD MOUNTAIN, ...) | ✅ FireRed's tables | ✅ retail rev 0's tables (`POKEDEX_ULTRA_VIOLET`) | `NATIVE_ULTRA_VIOLET`, runs as `GameKind.FIRERED` with `ActiveTables.fireRedHack` (a `HackText`) laying its few renames over the shared tables (`UltraVioletGen.kt` from `scripts/gen_ultra_violet_tables.py`, a diff against retail rev 0: WHITE BALL, LAND DEED, a WATER / DRAGON GYARADOS). Detected by hash on its own code (`TelemetrySampler.HACK_GAME_CODES`, which the library, INFO, the fanfare and the region-map FF watch treat as FireRed). No CARD. |
| **Pokémon FireRed Rocket Edition** | FireRed-based (**BPRE** rev 0, **16 MB**), retail rev 0 edited in place, no source | ✅ (`rocket_edition`: retail FireRed's save boots in it) | ✅ scripted wild battle (`rocket_edition_battle`, SNORLAX Lv100); retail rev 0's handlers, one-press picks (cursors written + A, checked headless), POKéMON switch | ✅ | ✅ FireRed's map byte for byte | ✅ its own NEXT BOSS (ARIANA holds Viridian's badge), its maps' areas | ✅ rev 0's tables, its ability names moved (`POKEDEX_ROCKET_EDITION`) | `NATIVE_ROCKET_EDITION`, `GameKind.FIRERED` with `ActiveTables.fireRedHack` (`RocketEditionGen.kt` from `scripts/gen_firered_hack_tables.py`): its mixed-case text whole (`ownCase`), moves 355..486 (repointed gMoveNames / gBattleMoves), a FAIRY type 23 and its chart. Detected by hash in the small-BPRE check. No CARD. |
| **Pokémon Ash Gray** v4.5.3, Fork Bug Fix v1.0 | FireRed-based (**BPRE** rev 0, **16 MB**), retail rev 0 edited in place, no source | ✅ (`ash_gray`, `ash_gray_fork`: retail FireRed's save) | ✅ scripted wild battles (`*_battle`), as Rocket Edition | ✅ | ✅ its own Kanto and Orange Islands (`RomRegionMap`, `ownRegionMap`) | ✅ NEXT BOSS for the badges won in battle, its own gWildMonHeaders / gMapGroups | ✅ rev 0's tables | `NATIVE_ASH_GRAY` (both builds), `GameKind.FIRERED` with `ActiveTables.fireRedHack` (`AshGrayGen.kt`): a few species slots, 25 moves, key items, the Orange Islands' sections, gender ratios, ELECTRIC on GROUND at half. No CARD. |
| **Pokémon Ghost Grey** v1.0.1, v1.0.2, v1.0.4 | FireRed-based (**BPRE** rev 0, **32 MB**, JoeyZeed), retail rev 0 edited in place with HexManiacAdvance (not CFRU), no source | ✅ (`ghost_grey`, `ghost_grey_v102`, `ghost_grey_v101`: a 1.0.3 save from its Discord loads in all three) | ✅ scripted wild battles (`ghost_grey_battle`, `ghost_grey_v101_battle`, GREAVARD Lv30); rev 0's handlers, one-press picks and the POKéMON switch (checked headless on both) | ✅ | ✅ its own grey Kanto (`RomRegionMap`, `ownRegionMap`) | ✅ NEXT BOSS for its seven badges, its own gWildMonHeaders (0x09300100) | ✅ Rocket Edition's layout (`POKEDEX_GHOST_GREY`) | `NATIVE_GHOST_GREY` (v1.0.2, v1.0.4) / `NATIVE_GHOST_GREY_V1_0_1`, `GameKind.FIRERED` with `ActiveTables.fireRedHack` (`GhostGreyGen.kt` from `scripts/gen_firered_hack_tables.py ghost_grey`): its ghosts in FireRed's 411 species slots, Rocket Edition's repointed moves / FAIRY / ability names, GHOST on GHOST 1.2x; v1.0.1 differs in PURIFY's type only. Detected by hash in the big-BPRE check (`fireRedHackConfig`). No CARD; party / bag in FireRed's look. |
| **Pokémon Let's Go Pikachu / Eevee** (English translation, each with a Translation Only build) | FireRed-based (**BPRE** rev 0, **32 MB**, Kevin Pokefan), retail rev 0 edited in place (not CFRU), no source, no version in the game | ✅ (`lets_go_pikachu`, `lets_go_eevee`: retail FireRed's save is "deleted" by it, so a new game played headless, which loads in all four) | ✅ scripted wild battles (`lets_go_*_battle`, WOOLOO Lv5); rev 0's handlers, one-press picks (written cursors + A) and the POKéMON switch on its two-column party grid (`runningLetsGo`), checked headless | ✅ | ✅ FireRed's Kanto | ✅ FireRed's NEXT BOSS (its badge scripts are FireRed's: 414-420, 350, the League), its own teams; HERE from each version's gWildMonHeaders, its maps' areas (`GuideAreasLetsGoGen.kt`) | ✅ 987 species: FireRed's dex shapes repointed past 16 MB, its own numbers to 855, the flags in EWRAM (0x0203C0C8 seen / 0x0203C133 caught, `DexFlagBlock.FIXED`; `POKEDEX_LETS_GO`) | `NATIVE_LETS_GO_PIKACHU` / `NATIVE_LETS_GO_EEVEE` (each with its Translation Only build: three moves' battle data apart), `GameKind.FIRERED` with `ActiveTables.fireRedHack` (`LetsGoGen.kt` from `scripts/gen_firered_hack_tables.py lets_go`): Rocket Edition's repointed moves (to 510) / FAIRY / ability names, the species grown to 987 ids (newer ones in FireRed's Hoenn slots and past EGG 412, regional forms their own ids), with their gender ratios and growth rates; the icon table / palette indices / gMonPaletteTable repointed in its config. Pikachu / Eevee differ in their exclusives (species, moves 510, gWildMonHeaders). Its wild Pokémon walk in the grass. Detected by hash in the big-BPRE check. No CARD; its party menu is CFRU's in its own blues (RomArt's `LGO_*`: `partylg/`, its grid `partybg/letsgo_tile.png`, the Spanish original's status icons), the bag FireRed's. |
| **Pokémon Prism** v0.95 build 254 (Hotfix 5) | Crystal-engine hack (GBC, 2 MB, RainbowDevs: Naljo and Rijon), no source or symbols (sha1 `75207669…`) | ✅ Crystal's struct, its own 254 species ids (`prism`: a new game played headless) | ⚠️ both battlers (`prism_battle`), LEADER JOSIAH's two (`prism_trainer`, started through its own script code); touch controls on Crystal's 2D menu (a move and RUN checked headless) | ✅ its bigger pockets (40 items, 25 balls, 50 key items) and a bit per TM / HM | ✅ its own 113 landmarks and 450 maps from the ROM, its six town maps (`Gen2Art`); no player head (six models) | ✅ its own per-species icons (LZ, 8 OBJ palettes) | ✅ its own list of 253 in its order, pages and pictures read through its text engine (`PrismDexTest`) | `GEN2_PRISM`, `GameKind.PRISM`. Tables from `scripts/gen_gen2_tables.py prism`. See below. |
| **Sword and Shield Ultimate Plus** v1.2.1.2 (+ Casual / Performance patches) | FireRed-based CFRU hack (BPRE rev 0, **32 MB**), no source | ✅ (`sword_shield_up`) | ✅ scripted wild battle captured headless (`sword_shield_up_battle`, a WOOLOO); rev 0's battle RAM and stored handler addresses, touch input (no POKéMON switch: unverified party menu) | ✅ | ✅ Galar from the ROM (`RomRegionMap`; FireRed's Sevii screens, junk in its tiles, dropped) | ✅ its own (CFRU tables via FireRed's literal pools) | ✅ one national dex 1-898 (`POKEDEX_SWORD_SHIELD`, Unbound's CFRU flags) | `NATIVE_SWORD_SHIELD`, `GameKind.SWORD_SHIELD`. Names / types / moves / items / gender / map names: `scripts/gen_cfru_tables.py sword_shield` (13-byte move names, no long-name table). Party menu rebuilt from the ROM (CFRU's tiles in its own palette: `partyswsh/`, `partybg/swsh_tile.png`), bag sampled headless (`SwordShieldBag`). Its Casual / Performance / Casual and Performance patches (sha1 daa2f899… / 4858cc3d… / 932323d1…) share every table and the RAM: the base save and a battle decode in each (`sword_shield_up_casual*`, `_performance*`). No CARD. |
| **Pokémon GS Chronicles** v2.7.6 | FireRed-based CFRU hack (BPRE rev 0, **32 MB**, Ruki Studios), no source | ✅ (`gs_chronicles`, `gs_chronicles_swapped`: a new game played headless - retail FireRed's save is "deleted" by it) | ✅ scripted wild battle captured headless (`gs_chronicles_battle`, a SENTRET; its script at 0x0203F400, the hack uses 0x0203FF00); rev 0's battle RAM and handlers, FireRed's cursors (one-press picks), the POKéMON switch on CFRU's grid - each checked headless | ✅ | ✅ Johto and Kanto from the ROM (`RomRegionMap`; its other two screens are FireRed's Sevii tilemaps in its tiles, dropped). Johto's boxes match the map; Kanto's are the hack's own (they don't line up with its towns) | ✅ its own (CFRU tables via FireRed's literal pools; Odyssey's status icons) | ✅ its 501-entry Johto list (`POKEDEX_GS_CHRONICLES`, CFRU's flags), NATIONAL once FireRed's unlock byte is set | `NATIVE_GS_CHRONICLES`, `GameKind.GS_CHRONICLES`. Names / types / moves / items / gender / map names: `scripts/gen_cfru_tables.py gs_chronicles` (17-byte move names; gItems keyed by slot - its `.itemId` is often a copied 374). Party menu: CFRU's shared slots and grid from the ROM (`partycfru/`), bag sampled headless (`GsChroniclesBag`). Species are DPE's ids, so without its own config it reads SURE as Unbound (BestEffortTest's siblings). No CARD (its trainer card is its own art). |
| **Pokémon Recharged Yellow** v1.8.0, v1.9.0, v1.9.4, v1.9.7 | FireRed-based (BPRE rev 0, **16 MB**), a full pokefirered rebuild with modern gcc, no source | ✅ (`recharged_yellow_v180` / `_v190` / `_v194` / `recharged_yellow`: the one v1.8.0 save, which every release loads) | ✅ scripted wild (`*_battle`), trainer (`recharged_yellow_trainer`, BROCK) and double battles captured headless; each release's own five handlers read live in their menus, touch input + the POKéMON switch (gPartyMenu per release) | ✅ 6 pockets (a Medicine pocket folded into Items), plain quantities - no encryption key | ✅ FireRed's map sections under its own names, FireRed's region map art rebuilt from its own ROM | ✅ mon + item icons (the GF ROM header's icon tables; item icons by MASTER BALL's {tiles, palette} pair) | ✅ Kanto (151) + National, 14-byte categories, its POKéDEX's Seen 58 / Own 31 | `NATIVE_RECHARGED_YELLOW_V1_8_0` .. `_V1_9_7`, `GameKind.RECHARGED_YELLOW`. Nothing is at a retail address: RAM from headless captures (party / count / save blocks / gMain / map header / objects / bag / battle block, each a literal-pool word, reference counts in the config's comment), ROM tables from the GF ROM header at 0x08000100 and by content. v1.9.x moved EWRAM past 0x02005000 up 8 bytes and v1.9.7 the save-block pointers up 0x1C; the battle block and gMain stayed. FireRed's species / move ids, types, gender ratios, growth rates and type chart (the generator checks them by hash); its own names (title-cased moves and items, 409 item slots: Mints, Bottle Caps, LINKING CORD, DexNav, ...), `scripts/gen_recharged_yellow_tables.py`. CARD: FireRed's art under its own card code - its player pictures and font (`RY_*`), its strings and Emerald-style placement, plain game stats, stars for the HALL OF FAME / its 37 achievements / Kanto / 1-248 + 252-384, six Pokémon icons on the back (`TRAINER_CARD_RECHARGED_YELLOW_*`; v1.9's red is lighter: `CardStyle.RECHARGED_YELLOW_V1_9`). |
| **Super Mariomon** v1.2.1, v1.2.2, v1.3, v1.5.1-Anniversary, v1.5.2-Anniversary | Emerald-based (BPEE, 32 MB), Too Many Productions' Mario hack on an old pokeemerald-expansion (agbcc, struct BaseStats 0x24 bytes, no RHHEXP header), no source | ✅ vanilla 100-byte struct, its species from 906 | ✅ scripted wild GOOMBA in every release (`mariomon_*_battle`), the five handlers read in each menu, a SHIFT for monToSwitchIntoId; touch controls (2-column grid) | ✅ v1.5: 6 pockets (a Medicine pocket at the end of SaveBlock1), v1.3 and before Emerald's 5 | ✅ its own world map (`MM_REGION_*`), cursor grid, MARIO's head | ✅ mon + item icons (vanilla-shaped tables) | ✅ the WORLD dex (152 / 151 entries), checked against its TATTLEDEX | Two RAM layouts (v1.5.x / v1.2.1-v1.3) and two table sets; the v1.5.1 save loads in v1.5.2, and in v1.3 / v1.2.x once cut to their SaveBlock1 size (`scripts/trim_save_block1.py`). Tables: `scripts/gen_mariomon_tables.py`. No CARD (its MARIO card is its own art). |
| **Pokémon Recharged Emerald** v2.2.5, v2.2.8, Rebalanced v2.2.8 | Emerald-based (BPEE, **16 MB**), a full pokeemerald rebuild with modern gcc by Recharged Yellow's author, no source | ✅ (`recharged_emerald_v225` / `recharged_emerald` / `recharged_emerald_rebalanced`: the user's retail Emerald save rewritten into its save layout by `scripts/convert_save_recharged_emerald.py`) | ✅ scripted wild battles (`*_battle`) captured headless; each build's five handlers read live in their menus, the one-press picks and the POKéMON switch checked on each | ✅ 6 pockets (a Medicine pocket folded into Items), Emerald's encryption key | ✅ Emerald's map sections, boxes and cursor grid under its own redrawn Hoenn and player heads (`regionmap/recharged_emerald.png`, `player_*_re`, from its ROM by RomArt) | ✅ mon + item icons (the GF ROM header's icon tables; item icons by MASTER BALL's {tiles, palette} pair) | ✅ Hoenn (202) + National, Emerald's entry shape in its casing, SEEN 108 / OWN 28 on the converted save | `NATIVE_RECHARGED_EMERALD_V2_2_5` / `_V2_2_8` / `_REBALANCED_V2_2_8`, `GameKind.EMERALD` with `ActiveTables.emeraldHack`. See "Pokémon Recharged Emerald" below. |
| **Pokémon Modern Emerald** v3.6 | Emerald-based (BPEE, 32 MB), resetes12's public pokeemerald fork (tag `Release3.6`) built with `make modern` | ✅ (`modern_emerald`: a new game played headless, six given through its own `givemon`, saved in the moving truck) | ✅ a scripted wild battle (`modern_emerald_battle`); the five handlers read live in their menus, the one-press picks and the POKéMON switch checked | ✅ Emerald's five pockets (Items holds 90), the key at SaveBlock2 +0xBC | ◐ Emerald's map sections; its picture not checked | ✅ mon + item icons (its own tables, by their neighbours' bytes) | — | `NATIVE_MODERN_EMERALD`, `GameKind.EMERALD` with `ActiveTables.emeraldHack` per save (`ModernEmerald`). See "Pokémon Modern Emerald" below. |
| **Pokémon Elite Redux** 2.65.3b, 2.65.4.1b | Emerald-based (BPEE, **32 MB**), its own pokeemerald rebuild (gcc, from Inclement Emerald); its source is public (Elite-Redux/eliteredux-source) but no release is tagged, so everything was mapped from the ROMs | ✅ (`elite_redux`, `elite_redux_v2653`: the one save, from its Discord, loads in both) - a packed plaintext 0x4C-byte party mon, no IVs (all 31; `speedDown` = a 0 Speed IV), the nature stored | ✅ scripted wild and trainer battles (`*_battle`, `*_trainer`: ROXANNE's FOE TEAM, the next foe after a faint), its 0x68-byte BattlePokemon; its own battle menu (no BAG; FIGHT 0, POKéMON 1, RUN 2, INFO 3; moves a column) - one-press picks and the POKéMON switch (a two-column grid) checked headless | ✅ one owned bit per item, no counts (`ItemFlagsBag`), its 8 pockets folded into the app's 5 | ✅ Emerald's Hoenn art and grid (its ROM kept them), its own section names | ✅ mon + item icons (the GF ROM header's) | ✅ one National dex of 1222 by dex number, SaveBlock1's flags (CAUGHT 29 = its title screen) | `NATIVE_ELITE_REDUX_V2_65_4_1B` / `_V2_65_3B`, `GameKind.ELITE_REDUX`; tables from `scripts/gen_elite_redux_tables.py` (2.65.4.1b whole, 2.65.3b's differences). EWRAM past the battle globals is 8 higher in 2.65.4.1b; every ROM table moved. GUIDE: HERE only. EXPAND (its own WIDE rows, exact), the field reads and WILD ENCOUNTERS checked headless; no field items, FLY or card. |
| **Pokémon Team Rocket Edition: Dragonsden Version** 1.5 | Emerald-based (BPEE, **32 MB**), DragonsdenTV's own closed pokeemerald build (gcc) on an older pokeemerald-expansion (36-byte base stats, vanilla's 44-byte items in expansion's ids, National Dex species to 1395), no source | ✅ (`team_rocket`: the one save, from its Discord) - vanilla's encrypted 100-byte mon, but language + flags share +0x12 (hasSpecies bit 4, isEgg bit 5) and the OT name starts at +0x13; no Mints (nature = PID % 25) | ✅ scripted wild (`team_rocket_battle`) and trainer battles (`team_rocket_trainer*`: BROCK's FOE TEAM, the next foe at gBattleStruct + 0x62), its 0x5C-byte BattlePokemon; the five handlers read live, one-press picks and the POKéMON switch (Emerald's list) checked headless | ✅ 8 pockets folded into the app's 5, Emerald's encryption key | ◐ its own section names (Kanto / Johto drawn with Hoenn tiles: the save is in CELADON CITY); its region maps aren't read | ✅ mon + item icons (the GF ROM header's; sItemIconTable by shape) | — (a regional dex of its own: the summary shows CROBAT as No. 067; the save has no Pokédex yet) | `NATIVE_TEAM_ROCKET`, `GameKind.TEAM_ROCKET`; tables from `scripts/gen_team_rocket_tables.py`. Its setwildbattle takes a second mon's fields; ScriptContext_SetupScript 0x080CEDC5. GUIDE: HERE only (`team_rocket_route`, warped to ROUTE 101). No EXPAND, field reads, field items, FLY or card yet. |
| **Pokémon Team Rocket Edition 2026** 3.0.9, 3.1 (Spanish) | Emerald-based (BPEE, **32 MB**), pokeemerald-expansion 1.14.4 (its RHHEXP block), closed | ✅ 3.1 (`team_rocket_v3_1`: a new game played headless, its ZUBAT); 3.0.9 mapped from a contributor's save (no fixture) | ✅ 3.1 (`team_rocket_v3_1_battle`: a scripted wild battle through its ScriptContext_SetupScript 0x081A4175; handlers v3.0.9's +0x68) | ✅ eight pockets | ✅ its seven region maps (RomArt's `TR3_*`) | ✅ | ✅ gSpeciesInfo pages | 3.1 is 3.0.9's RAM word for word with its ROM moved: GF header / RHHEXP block pointers, chart / trainers / wild headers by content; every name table equal (`gen_expansion_tables.py team_rocket_v3_1`, `same_as`) |
| **Pokémon Wishes of Tomorrow** 1.2.6 | Emerald-based (BPEE, **32 MB**), FlatFoot Labs' closed build on pokeemerald-expansion 1.15.3 (its RHHEXP block: 848 moves, 1581 species, 924 items), gcc, no source | ✅ (`wishes`: a new game played headless, randomizer off, its party / bag / Pokédex flags given through the game's own script commands, then saved in game) - expansion's encrypted 100-byte mon (species / moves 11 bits, PP 7, experience 21, held item 10) | ✅ scripted wild and trainer battles (`wishes_battle`, `wishes_trainer*`: GENERAL EDWARDS' six, KENJI's next pick at gBattleStruct + 0xEB; `wishes_mega`: a Mega Evolved TYRANITAR reads as species 926), its 0x88-byte BattlePokemon; the five handlers read live (the target one in a double battle), one-press picks and the POKéMON switch (Emerald's list) checked headless | ✅ Emerald's five pockets | ✅ its own JOKAIM TOWN MAP (`regionmap/wishes.png` rebuilt from the ROM: RomArt's `WSH_REGION_*`, Emerald's kind of 8bpp affine screen with smol art) and cursor grid, its own section names (MUNEN VILLAGE 0xD1 ...); the player's head is Emerald's Brendan / May, as in the game | ✅ mon + item icons (gSpeciesInfo / gItemsInfo, smol item tiles, plain palettes) | ✅ expansion's Hoenn list (214, u32 entries) + National (1025), SaveBlock1+0x359C / +0x361D | `NATIVE_WISHES`, `GameKind.WISHES`; tables from `gen_expansion_tables.py wishes`, RAM by literal-pool reference counts against an expansion 1.15.3 build of the tag (`nm`), every address then checked live. GUIDE: HERE only; its randomizer is followed (`WishesRandomizer`, see below). No EXPAND, field reads, field items, FLY or card yet. |
| **Pokémon Dreamstone Mysteries** (no version shown) | Emerald-based (BPEE, **32 MB**), dsmyst's closed Cormoria hack on pokeemerald-expansion **1.11.2** (its debug menu's EXPANSION VERSION: "v1.11.2 Development Build"), gcc | ✅ (`dreamstone`: no save existed, so a new game was played headless and five Pokémon given through its own ScrCmd_createmon) - 1.10's packed encrypted 100-byte mon, Imperium's handling | ✅ scripted wild (`dreamstone_battle`) and trainer battles (`dreamstone_trainer*`: BACKPACKER RONNIE's FOE TEAM, the next foe at gBattleStruct + 0x5B), expansion's 0x60-byte BattlePokemon; the five handlers read live, one-press picks and the POKéMON switch (a two-column grid) checked headless | ✅ 7 pockets (Medicine, Battle Items folded into Items) | ✅ its own Cormoria TOWN MAP (`regionmap/dreamstone.png` rebuilt from the ROM: RomArt's `DSM_REGION_*`, a FireRed-like 4bpp screen) and cursor grid | ✅ mon + item icons (gSpeciesInfo / gItemsInfo) | ✅ its own regional list (315, GOTHITA first; the game titles it HOENN) + National, SaveBlock1's flags | `NATIVE_DREAMSTONE`, `GameKind.DREAMSTONE`; tables from `gen_expansion_tables.py dreamstone` (+ growth rates). pokeemerald-expansion 1.11.2 built plain was the map for the RAM (every global within a few bytes of it, then checked in dumps). GUIDE: NEXT BOSS (the eight badge holders by the badge flag their scripts set, then VICTORY CAPE's five) and HERE. Its gScriptCmdTable's commands run through the ROM's 0x0A000000 mirror. No EXPAND, field reads, field items, FLY, PC or card. |
| **Pokémon Monomyth** 1.0, 1.1 | its own game code on 1.0 (**MONE**, 32 MB; 1.1 is **BPEE**, found by hash with the big BPEE hacks), Team Monomyth's closed game on its own pokeemerald build (gcc: globals in EWRAM behind section anchors, so literal-pool words point near a variable, not at it), the Athanto region | ✅ (`monomyth`: the user's save) - vanilla's 100-byte mon, its substructs XORed with a key of its own (`monomythKey`: its box decryption at 0x08068198, from the personality and OT ID); 250 natures of its own, flavour only (`monomythNature`, its GetNature's hash) | ✅ scripted wild (`monomyth_battle`) and trainer battles (`monomyth_trainer*`: BUG CATCHER TIM's FOE TEAM, his second out), its rearranged 0x58-byte BattlePokemon (PP first, species +0x0C, item +0x20); the five handlers read live, one-press picks and the POKéMON switch (a two-column grid) checked headless; the trainer's next pick isn't mapped | ✅ 8 pockets (Medicine and held items folded into Items), the key at SaveBlock2 +0x40 | ✅ its PokeNav's ATHANTO MAP (RomArt's `MMY_REGION_*`: a mode-0 screen, 8bpp tiles from tile 0x60 through a 32x32 u16 tilemap shown from row 2, BG palette 7; matched the game's screen pixel for pixel), its one player head, section rects at 0x08A166AC on cursor grid 0x08A16000 (28x15, from tile (1, 2)) | ✅ mon + item icons (vanilla-shaped tables; the mon icons packed by its own tile coder, `MonomythIcons`) | ✅ its one ATHANTO dex (362 numbers, metric), SaveBlock1's flags (SEEN 22 / OWN 11 on the save), smol front pictures | `NATIVE_MONOMYTH`, `GameKind.MONOMYTH`, detected by hash under `HACK_GAME_CODES`; tables from `scripts/gen_monomyth_tables.py` (its own EXP curves past Lv70 too). Its struct Main is in its own order (no intrCheck: `frameCounterOff`; inBattle at 0x02020601, 0x17 before callback1). setwildbattle takes species, level, item, a byte and a word; ScriptContext_SetupScript 0x08090871. GUIDE: NEXT BOSS and HERE; DEX AREA by gMapGroups 0x0886EED8. EXPAND and field reads (`PK_WIDE_FAMILY_MM`: lock 0x0203BF67, 16-bit camera offsets, 80 sprites) and PC anywhere (special 57); STEADY FF music (`M4aSongs`' fifth signature: agbcc's m4aSongNumStart at 0x08259D0D, gSongTable 0x08E3AE10 with every song header stored top bit flipped); no WILD ENCOUNTERS (no switch), bag USE, FLY or card. 1.1 is the same game rebuilt: `NATIVE_MONOMYTH_V1_1` over v1.0's RAM, every pool word pointing at RAM unchanged and v1.0's save loading as it is, with the ROM moved (`POKEDEX_` / `GUIDE_TABLES_` / `FIELD_ITEMS_MONOMYTH_V1_1`; `gen_monomyth_tables.py` with both ROMs checks its tables equal v1.0's, so `MonomythGen.kt` serves both; of the trainers, only one name changed). Its fixtures are `monomyth_v1_1*`. Its header says BPEE, so whatever goes by game code sees Emerald: `fanfareSongId` turns it away by its title, and RegionMapWatch, REUSABLE TMS and the save editor find nothing to act on. |
| **Pokémon Yellow** (Game Boy, USA/Europe) | Gen 1 retail cart (sha1 `cc7d0326…`, = pret/pokeyellow's build) | ✅ (`Gen1Reader`: big-endian party_struct, internal species → Dex via PokedexOrder) | ⚠️ both battlers (wBattleMon / wEnemyMon) + a trainer's party - headless scripted wild battle (`yellow_battle`); no touch controls | ✅ one list, game order | ✅ its own town map from the ROM (`Gen1Art`: CompressedMap RLE) + the place's cell | ✅ its party icons from the ROM (MonPartySpritePointers, GBC colours) | — | Runs on mGBA's GB core (M_CORE_GB, SGB borders off). Tables from `scripts/gen_gen1_tables.py yellow` (pokeyellow.sym addresses); the companion sets its text in the game's own font (`Gen1Art` → `BitmapTtf`) and Gen 1 windows (`OptionColors` while YELLOW). |
| **Pokémon Yellow** (Game Boy: Gelbe Edition, Version Jaune, Versione Gialla, Edición Amarilla) | the German / French / Italian / Spanish carts (sha1 `42f3714e…` / `0aceec0e…` / `05bb8e99…` / `1dc24203…`): English's code with their own text | ✅ (`GEN1_YELLOW_DE` .. `_ES`: English's RAM + 5 bytes from wBattleMon up, the same in all four; the English save loads) | ⚠️ as English (`yellow_<l>_battle*`); the battle menu's cursor columns are per language (`battleMenuLeftX` / `RightX`) | ✅ | ✅ English's town map (the same pixels), their own place names | ✅ English's icons, their own font (`Gen1Charset`) | ✅ their own text, metric (`Gen1DexTables.metric`) | Addresses from `scripts/port_gen1.py` (English's code matched in theirs, every table checked by contents); names from `scripts/gen_gen1_tables.py yellow_<l>` (`YellowText<Lang>Gen.kt`, a `GameText` by the cart's CGB code APSD / APSF / APSI / APSS). No item descriptions (ours are English). |
| **Pokémon Red / Blue** (Game Boy, USA/Europe) | Gen 1 retail carts (sha1 `ea9bcae6…` / `d7037c83…`, = pret/pokered's `pokered` / `pokeblue` builds; Blue checked on the build) | ✅ (`GEN1_RED` / `GEN1_BLUE`: Yellow's reader, Yellow's RAM + 1 from wOnSGB up; `red` / `blue`: the user's Red save in both) | ⚠️ both battlers from a wild battle (`red_battle`, `blue_battle`); touch controls and the POKéMON switch as Yellow's (`red_battle_*`) | ✅ one list, game order | ✅ its town map from the ROM in the SGB's colours | ✅ its party icons from the ROM (14 icon loads a frame, no PIKACHU shape) | ✅ its own pages (Yellow rewrote them), SGB palettes, MEW apart in bank 1 | `GameKind.RED` / `.BLUE`. Yellow's tables (`gen_gen1_tables.py red` / `blue` check every table equals Yellow's). See below. |
| **Pokémon PureRed / PureBlue** v2.7.6 (Game Boy, Vortyne's PureRGB) | a pret/pokered fork, open source: its `make red blue` (rgbds 1.0.3) is both releases byte for byte (sha1 `2e94d09c…` / `d419fe24…`), a Color cart (rgbfix -c) | ✅ (`GEN1_PURE_RED` / `GEN1_PURE_BLUE`: pokered.sym's RAM, WRAM bank 1 read by bank; `purered` / `pureblue`: the retail Red save through the game's own updater) | ⚠️ both battlers from a wild battle (`purered_battle`, `pureblue_battle`); touch controls and the POKéMON switch as Red's, its after-a-faint menu numbered 1 (`purered_battle_*`) | ✅ one list of 30 | ✅ its town map from the ROM in the Color's two-tone palette, its own place ids (SAFFRON is 7) | ✅ its own party icons (its 8-tile sheet, the ICONS option's OG set) | ✅ its own pages (fixed records by Dex number, the category apart), SGB palettes | `GameKind.PURE_RED` / `.PURE_BLUE`. Tables from `scripts/gen_gen1_tables.py purered` (one set, `*PureRgb.kt`; `pureblue` checks it equals PureRed's). See below. |
| **Pokémon Yellow Legacy** v1.0.10 (Game Boy, cRz-Shadows') | a pret/pokeyellow fork, open source: `make pokeyellow.gbc` with rgbds 0.6.1 at tag V1.0.10 is the release BPS over retail Yellow byte for byte (sha1 `2ac87de4…`, CRC `e7d07a3f`) | ✅ (`GEN1_YELLOW_LEGACY`) | ⚠️ both battlers, touch controls and the POKéMON switch as Yellow's (`yellow_legacy_battle*`) | ✅ one list of 59 | ✅ its own town map, Yellow's places | ✅ its own (an icon a species) | ✅ its own base data and pages | `GameKind.YELLOW_LEGACY`. See below. |
| **Pokémon Crystal Legacy** v1.3.1 (Game Boy Color, cRz-Shadows') | a pret/pokecrystal fork, open source: `make pokecrystal.gbc` with rgbds 0.5.2 at tag V1.3.1 is the release BPS over either retail revision byte for byte (sha1 `74a6727e…`, CRC `12e97fb4`) | ✅ (`GEN2_CRYSTAL_LEGACY`) | ⚠️ both battlers, touch controls and the POKéMON switch as Crystal's (`crystal_legacy_battle*`) | ✅ four pockets, ITEMS 84 | ✅ its own POKéGEAR maps, Crystal's places | ✅ its own (an icon a species, eight palettes) | ✅ its own base data and pages (a far pointer each) | `GameKind.CRYSTAL_LEGACY`. See below. |
| **Pokémon Crystal** (Game Boy Color: USA rev 0, USA/Europe rev 1, Australia) | Gen 2 retail carts (sha1 `f4cd194b…` / `f2f52230…` / `a0fc810f…`, = pret/pokecrystal's `crystal` / `crystal11` / `crystal_au` builds) | ✅ (`Gen2Reader`: big-endian 48-byte party_struct, species = Dex number, held item, gender from the DVs; `crystal` / `crystal_rev0` / `crystal_au`: one save in all three) | ⚠️ both battlers (wBattleMon / wEnemyMon, 32-byte battle_struct with its types) from a wild battle (`crystal_battle`); a trainer's party (wOTPartyMons) is read but no trainer battle was captured. Touch controls: the 2D menu's cursor (`readGen2BattleInput`), steered like Yellow's; a move and RUN checked headless | ✅ four pockets (ITEMS, BALLS, KEY ITEMS, the TM / HM quantities) | ✅ landmarks: each map header's landmark by (group, number), the POKéGEAR's Johto and Kanto maps from the ROM (`Gen2Art`) | ✅ its party icons from the ROM (IconPointers, PartyMenuOBPals), the held-item / Mail marks | ✅ 251, national order; SEEN 2 / OWN 2 = the game's own POKéDEX (flag poked on to look) | Runs on mGBA's GB core. Tables from `scripts/gen_gen2_tables.py crystal` (pokecrystal.sym addresses, the three releases checked byte for byte). WRAM bank 1 is read by bank (`wramx`), see below. |
| **Pokémon Goldene Edition / Silberne Edition** (Game Boy Color, German Gold / Silver) | English Gold / Silver translated (sha1 `9254195d…` / `8ecc58d6…`) | ✅ English Gold's RAM (`gold_de` / `silver_de`: the gold fixture's save, loaded unchanged) | ⚠️ as English (`gold_de_battle*`, `silver_de_battle`), the menus at Kristall's columns | ✅ English's four pockets, their own names (BEUTEL) | ✅ their own landmark names, English's POKéGEAR maps | ✅ English's icons | ✅ 251, each version's own pages, metric | `GEN2_GOLD_DE` / `GEN2_SILVER_DE`. Names from `scripts/gen_gen2_tables.py gold_silver_de` (everything else checked equal to English's). See below. |
| **Pokémon Kristall** (Game Boy Color, German Crystal) | English Crystal translated (sha1 `accb5842…`) | ✅ English's RAM (`crystal_de`: the crystal fixture's save, loaded unchanged) | ⚠️ as English (`crystal_de_battle*`), its menus at its own columns | ✅ English's four pockets, its own names (BEUTEL) | ✅ its own landmark names, English's POKéGEAR maps | ✅ English's icons | ✅ 251, its own pages, metric | `GEN2_CRYSTAL_DE`. Names from `scripts/gen_gen2_tables.py crystal_de` (everything else checked equal to English's). See below. |
| **Pokémon Gold / Silver** (Game Boy Color, USA/Europe) | Gen 2 retail carts (sha1 `d8b8a360…` / `49b163f7…`, = pret/pokegold's `pokegold` / `pokesilver` builds) | ✅ Crystal's struct at their own RAM (`GEN2_GOLD` / `GEN2_SILVER`; `gold` / `silver`: one save played headless in Gold, loaded in both) | ⚠️ both battlers from wild battles (`gold_battle`, `silver_battle`) and a trainer's six (`gold_trainer`); touch controls and the POKéMON switch on Crystal's menus (`gold_battle_*`) | ✅ Crystal's four pockets, their own item names | ✅ their own 95 landmarks and maps, the POKéGEAR's Johto / Kanto maps (`Gen2Art`), the boy on them | ✅ their party icons from the ROM, Crystal's colours | ✅ 251, each version's own pages | `GameKind.GOLD` / `.SILVER`. Tables from `scripts/gen_gen2_tables.py gold_silver` (species, moves, types and chart checked equal to Crystal's with `--base`). See below. |
| **Polished Crystal** 3.2.0 - 3.2.3 (normal, faithful, + debug: 16 builds) | Rangi42's open-source Crystal hack (GBC, 2 MB); every official release build, by SHA-1 | ✅ its own 48-byte party_struct (9-bit species + forms, gender bit), `GEN2_POLISHED_CRYSTAL` (`polished_crystal*`: a new game played headless) | ⚠️ both battlers (35-byte battle_struct) from a wild battle (`polished_crystal_battle`) and LYRA's (`polished_crystal_trainer`); touch controls and the POKéMON switch on its menus (`polished_crystal_battle_*`) | ✅ ITEMS / MEDICINE / BALLS / BERRIES, key items (their own ids), TMs a bit each | ✅ its 145 landmarks and 605 maps, Johto / Kanto / Orange Islands town maps (`Gen2Art`) | ✅ its party minis, each species' own colours | ✅ 289 in National order, metric | `GameKind.POLISHED_CRYSTAL`. Tables from `scripts/gen_polished_crystal_tables.py` (the releases' own .sym files). See below. |
| **Pokémon Crystal Clear** v2.5.11, v2.6.0 (each Standard Colors and Color Filter) | Crystal-based (GBC, 2 MB), ShockSlayer's open-world hack: pokecrystal grown and rebuilt, no source or symbols (sha1 `a1c4da6b…` / `6fc677bf…` / `84d5c492…` / `534876e4…`) | ✅ Crystal's struct (`crystal_clear` / `crystal_clear_v2_5_11`: one save made headless in v2.6.0, loaded in all four builds) | ⚠️ both battlers and a trainer's team (`crystal_clear_trainer`: MOM's six), wild battles in both versions; touch controls on Crystal's 2D menu (a move and RUN checked headless in both) | ✅ Crystal's four pockets, its own item names | ✅ its own 117 landmarks and 488 maps from the ROM, its POKéGEAR Johto / Kanto maps (`Gen2Art`); no player head (the player is customised) | ✅ its own per-species icons (LZ, each in its colours) | ✅ 251, its dex pages (text packed with a dictionary) | `GEN2_CRYSTAL_CLEAR_V2_6_0` / `_V2_5_11`, `GameKind.CRYSTAL_CLEAR`. Tables from `scripts/gen_gen2_tables.py crystal_clear` (species, moves, types and chart checked equal to Crystal's with `--base`). See below. |

**Pokémon Quetzal Alpha 9 v1** (English / Spanish; `NATIVE_QUETZAL_V1` / `NATIVE_QUETZAL_ES_V1`) is Alpha 9 v0 rebuilt (28 of its
32 MB differ). How it was ported, for the next release:
- **RAM**: v0's. Every RAM word in v0's literal pools maps to the same word in v1's code (`port_retail.py`'s `map_literal`,
  English v0 -> v1 and Spanish v0 -> v1), and the v0 saves load in v1 unchanged (`quetzal_v1`, `quetzal_es_v1`). v1 sets three
  more bits of the party struct's byte 0x1B, which nothing here reads.
- **ROM tables**: v0's addresses through the same literal pools, then each table compared with v0's entry for entry, a pointer
  counting as equal when what it points at is (pages, pics, palettes, footprints, learnsets, trainer parties, wild slots).
  Kanto's trainer table (no literal pool) by its records with the party pointer masked. `gen_quetzal_tables.py --v1` checks the
  names (every language), species data, pockets, chart, moves' power / type and map sections: v1 changed one move's PP and
  corrected the Latin American names of Johto's key items (v0's were Portuguese) - `QuetzalV1Gen.kt`, laid over by
  `quetzalV1`. Spanish v0's config had read English's evolution and learnset addresses: it has its own now.
- **Battle handlers**: read in gBattlerControllerFuncs (0x03005580) in each menu of a scripted wild battle (setwildbattle
  through v1's ScriptContext_SetupScript, 0x080DE341 English / 0x080DE359 Spanish); Spanish's are English's - 0x24, as in v0.
  The target handler (doubles only) is v0's function, one local more. The cursors are v0's (RIGHT / DOWN walk 0 -> 1 -> 3, a
  written 2 + A opened the party menu).
- **The bag** is a new record, `BAG4` (`Bag4Layout`): read off the game's code (the check at 0x08144F88, its byte accessor
  0x08142D40, the entry reader 0x08142F7C, the item / key-item lookups 0x081431E8 / 0x08143304 in English v1). Per campaign
  (byte 5: the current of three) 10 pockets of 22-bit entries (an index into the pocket's ROM list of item ids + quantity XOR
  the key) and up to 128 key items (a byte each: an index into the 11th list). v1 writes a v0 save's BAG3 into it as it loads:
  the same items, quantities and key items, in v1's pocket order. Its lists sit in the ROM, so a RAM-only fixture reads no bag.
- **Not done**: EXPAND and the field reads (v0's are by ROM CRC), WILD ENCOUNTERS (not found, as v0).

**Pokémon Recharged Yellow** (Jaizu; four releases, `NATIVE_RECHARGED_YELLOW_*`) is pokefirered
rebuilt with modern gcc, so nothing sits where retail has it and FireRed's literal-pool tricks
(`RomRegionMap`, retail ports) don't apply. What was found, and how:
- **Every release from one save**: v1.9.0 / v1.9.4 / v1.9.7 load the v1.8.0 save as is (same party
  bytes, money, place), so each release has a field and a battle fixture of the same game; the addresses
  were ported through v1.8.0's literal pools and then read in each release's own captures.
- **Its GF ROM header** (0x08000100, pokefirered's `struct GFRomHeader`) names the icon tables,
  gSpeciesNames, gBaseStats, gItems, and SaveBlock1's own layout: party count +0x3B, party +0x44,
  flags +0xEFB, vars +0x1028. Money (+0x29C) and bag quantities are plain: the hack dropped the
  encryption key (a new game's money reads 3000 raw), so `encryptionKeyOff = -1`.
- **Trainers** are 0x24-byte records with two parties - NORMAL (size in the low nibble of +0x1F) and HARD
  (+0x20, the high nibble), picked by SaveBlock2+0x6E0 (its difficulty) - of 0x20-byte TrainerMons
  (`GuideTables.trainerHardPartyOff`). Its system flags moved (badges 0x880.., game clear 0x864); trainer
  flags (0x500 + id) and FLAG_DEFEATED_* are FireRed's; its hidden-item flags aren't (1000 + n reads set for islands the
  save never reached) and their base wasn't found, so HERE lists hidden items unmarked (`--no-hidden-flags`).
- **Area data**: `gen_guide_areas_rom.py` with `--items` (its 48-byte gItems, key pocket 6), `--ops` (the
  sizes of its script commands 0xD5..0xE9 and of the vanilla ones it changed - `message` carries one text
  per language, 25 / 29 bytes - read off each handler's ScriptRead* calls; 0xE3 is variable-length and
  stops a path) and `--max-species 412`. Each release moved items and gifts, so each has its own file.
- **Languages**: the game has a text-language option (SaveBlock2+0x91, low 5 bits; 6 languages, 7 in
  v1.9.7) and carries other languages' name tables after its English ones (French species, Portuguese
  moves). The companion shows the English names. Best effort can't be SURE of it for the same reason
  (no config's names "cover" a ROM that holds French species too), but ranks its own config first.
- **Music**: its song table is ordered like Emerald's (610 songs; FANFARE_LEVEL_UP is 367), so the
  renderer knows it by the GF header's SaveBlock1 size (`isRechargedYellowRom`) for the fanfare and
  renders all its BGM rather than FireRed's map-music list. `RegionMapWatch` reads sRegionMap two pool
  words before the 0x4796 offset there (gcc put another global between them).

**Pokémon Recharged Emerald** (Jaizu, like Recharged Yellow; v2.2.5, v2.2.8 and Rebalanced v2.2.8, `NATIVE_RECHARGED_EMERALD_*`)
is pokeemerald rebuilt with modern gcc, Recharged Yellow's recipe applied to Emerald: nothing at a retail address, but Emerald's
species / move / item ids, type ids and type chart, so it runs as `GameKind.EMERALD` with its own names laid over the shared
tables (`ActiveTables.emeraldHack`, a `HackText` like the FireRed hacks'; `scripts/gen_recharged_emerald_tables.py`). What was
found, and how:
- **No save of its own**: retail Emerald's loads, but RE's SaveBlock1 differs past the bag, so the menu lost POKéMON / POKéDEX and
  Rebalanced hung on CONTINUE. `scripts/convert_save_recharged_emerald.py` rewrites the save into its layout (both slots, checksums
  over its own sizes); the converted saves sit next to RetroArch's under each build's name, and boot cleanly in all three.
- **Its save layout**, from its GF ROM header (0x08000100: flags / vars / party offsets, the bag counts, the save block sizes) and
  SetBagItemsPointers: Items 60 slots at +0x560, Key Items 50 at +0x650, Poké Balls 32 at +0x718, TMs and Berries as retail at
  +0x798 / +0x898, the seen1 copy dropped, flags +0x1344 and vars +0x1470 (+0xD4), a Medicine pocket (50) in retail's
  unused_3598 at +0x366C, the seen2 copy dropped and 0x30 new bytes at the end (0x3DA0). SaveBlock2 has 0x1C more bytes at its end.
  Rebalanced puts 4 more bytes in front of both blocks (its header's offsets are all 4 higher): `saveBlock1PosOff` 4,
  `playerGenderOff` 0xC, money +0x494, the key +0xB0, the dex at SaveBlock2+0x1C.
- **RAM** from headless dumps of the converted save (party / save blocks / gMain / map header / objects / bag, each checked against
  its literal-pool reference count) and of a scripted wild battle through each build's ScriptContext_SetupScript (the function that
  loads gScriptCmdTable, 233 commands: six more than retail). The battle globals are Recharged Yellow's order 8 bytes lower.
- **ROM tables**: the header gives the icon tables, names, gBaseStats, gItems (Recharged Yellow's 48-byte records) and
  gBattleMoves; the Pokédex and guide tables are retail Emerald's shapes found by their bytes (dex entries by BULBASAUR's,
  learnsets by its list, trainers by ROXANNE's record: all 855 names sit at retail's ids). Rebalanced's wild headers are 0x24
  bytes (land, a second land table, water, rock smash, fishing at slots 0, 1, 2, 4, 6), found through the code that loads v2.2.8's.
- **Its art** is recompressed (another LZ77 encoder, so none of Emerald's fingerprints match: RomArt keys on the compressed
  bytes) and partly redrawn, so it has fingerprints of its own (`RE_*`, `gen_rom_art_sigs.py`), the same bytes in all three
  builds. Found headless: the party menu's VRAM and palette RAM matched against every LZ77 stream in the ROM (the backdrop's 80
  tiles, its palette and status palette; the tilemap and status icons are Emerald's data recompressed, the slot tilemaps and
  Poké Ball Emerald's bytes), FONT_SMALL by retail's glyphs (it widened 0x30-0x33 and one symbol: `RechargedEmeraldPartyStyle`), the
  Hoenn map and the two map heads from region_map.c's literal pools. RomArt writes them to `partyre/`, `partybg/recharged_emerald.png`,
  `regionmap/recharged_emerald.png` and `player_brendan_re` / `player_may_re`; the map keeps Emerald's boxes and cursor grid (the
  same layout). Its bag screen keeps Emerald's palette in the app.
- **FIELD items** (USE / GIVE / TAKE): no retail function is where retail has it, so `scripts/find_field_items_recharged_emerald.py
  <v2.2.5> <v2.2.8> <Rebalanced>` finds each one through the code that calls it and writes `FieldItemsRechargedEmeraldGen.kt`,
  which each config carries (`NativeConfig.fieldItems`). VarGet and the bag functions come from gScriptCmdTable's additem /
  removeitem / checkitem handlers; VarSet and the Repel's variables from Task_UseRepel (reached from a Repel's fieldUseFunc through
  its tasks' literal pools); GetMonData, ExecuteTableBasedItemEffect, gPartyMenu / gPlayerParty and gItemEffectTable from
  ItemUseCB_Medicine (a Potion's fieldUseFunc stores it in gItemUseCB); SetMonData as the function called most with
  MON_DATA_HELD_ITEM and a stack buffer; ArePlayerFieldControlsLocked from script.c's Lock / Unlock / IsLocked trio. What it
  changed from pokeemerald: struct Item lost itemId (48 bytes with name[18], holdEffectParam at 0x15, pocket 0x1E, fieldUseFunc
  0x20; pockets 1 Items, 2 Medicine, 3 Poké Balls, 4 TMs, 5 Berries, 6 Key Items); nine struct Pokemon fields of its own sit before
  MON_DATA_ENCRYPT_SEPARATOR (19), so SPECIES is 20, HELD_ITEM 21, MOVE1 22, PP1 26, IS_EGG 54, STATUS 64, HP 66 (GetBoxMonData's
  `cmp r1, #19` and SetMonData's `subs r3, #64` agree); its Repel also zeroes its Lure's 0x4055 / 0x404E and writes the item to
  0x4052 for the wore-off prompt. Checked headless on each build from the converted save: a Repel through those VarSets (100, then
  95 after a walk), a Potion / Antidote / Ether / Elixir / PP Up after SetMonData damage, the no-effect dry run (EWRAM identical
  after the 100 bytes went back, IWRAM only the call's stack), GIVE / TAKE through the scratch word, an in-game save from the START
  menu and a reboot from the `.srm` with every change kept and no bad egg.
- **Not done**: EXPAND (its object events are expansion's layout and its overworld sprites load their tiles at run time:
  gSprites' image pointers are null, which WIDE's far-off objects rely on), FLY and the CARD. FF music isn't checked on it.

**Pokémon Modern Emerald** (resetes12's; v3.6, `NATIVE_MODERN_EMERALD`, 2026-11-09): pokeemerald with 50 species past
Emerald's 411 (EGG 462), 374 moves, 397 items and FAIRY as type 18, public at github.com/resetes12/pokeemerald (tag `Release3.6`,
cloned into `$DECOMPS/pokemodernemerald`). The release was built with `make modern`, so the tag's build isn't it byte for byte,
but it is its map:
- **RAM**: every RAM symbol the build's code references (1000 of them) is referenced as often in the release, so the config's
  RAM is the build's `nm`. Struct offsets by `offsetof` compiled against the tag's headers (`pd_offsets.c` in the clone, never
  run, read with objdump): vanilla's party mons, BattlePokemon and Item, but Items holds 90 (the pockets after it moved) and
  SaveBlock2's encryption key sits at +0xBC.
- **ROM**: code moved and changed shape (another gcc), so tables were found in the release by their bytes with ROM pointers and
  calls masked (each at one place), pointer-only tables by their neighbours, functions through their literal pools
  (ScriptContext_SetupScript: both pools that hold gScriptCmdTable with sGlobalScriptContextStatus) and the battle handlers
  read live in each menu. The GF ROM header at 0x08000100 points at the same species / move / item / base stat tables.
- **Save**: it erases retail Emerald's save ("due to corruption"), so a new game was played headless: its game-mode screens
  (pages of settings; A acts only on each page's last row) took the Classic preset, then six Pokémon and a bag came from its
  own `givemon` / `additem` run through ScriptContext_SetupScript, and the START menu's SAVE wrote the save the fixtures boot.
- **Its settings decide its types**: a new game's MODERN TYPES, FAIRY TYPE and TYPE CHART (permanent) and its randomizer
  live in SaveBlock1's tx_ bitfields (+0x3D9D / +0x3DA5 / +0x3DA7; their bits read from compiled setters). pokemon.c's
  GetTypeBySpecies picks `types_old` / `types_new` for 43 species by them, and the chart is gTypeEffectiveness_GenVI or
  gTypeEffectiveness. `ModernEmerald.read` takes the bits each sample and `ModernEmerald.text` builds that save's tables
  (the randomizer's types through its seeded RandomSeededModulo, its chart through the live sTypeEffectivenessList). Its
  CHAOS option draws fresh random numbers in the game itself: nothing to follow there.
- `scripts/gen_modern_emerald_tables.py` writes ModernEmeraldGen.kt from the ROM, the per-save lists from the source.
  `runningRechargedEmerald` keeps Recharged Emerald's own art from reaching it (it kept Emerald's).
- **Not done**: DEX and GUIDE (its learnsets and base stats also follow the save: MODERN MOVES, NEW STATS), EXPAND, the field
  reads and field items, FLY, PC anywhere, the CARD, shiny odds.

**Pokémon Red and Blue** (Game Boy; `GEN1_RED` / `GEN1_BLUE`, `GameKind.RED` / `.BLUE`): pret/pokered builds both carts
byte for byte (`pokered.gbc` / `pokeblue.gbc`, its roms.sha1), and the two keep every RAM address and every table the
companion reads at the same place with the same bytes; the user has a Red cart, Blue is checked on the build. What differs
from Yellow:
- **RAM**: Yellow's plus one byte from wOnSGB (CF1B) up; the menus (wTopMenuItemY, wMoveMenuType, wPlayerMonNumber) and
  wTileMap below it, and the audio RAM, didn't move. The battle menu's cursor columns are Yellow's (9 / 15).
- **Tables**: every one `gen_gen1_tables.py` reads (species order, names, types, moves, items, chart, maps) comes out as
  Yellow's (`red` / `blue` check it and write nothing), so the app keeps one set. Yellow's hand-written item text fits them.
- **A Super Game Boy**: an SGB-enhanced cart that isn't a Color one runs as a Super Game Boy in mGBA (model 0x20), so its
  colours are the SGB palettes the game sends (pokered's data/sgb): the windows' white is 0xFFEFFF, black 0x181010
  (`OptionColors.gbPaper` / `gbInk`), the party menu PAL_MEWMON through OBP0 and PAL_GREENBAR / YELLOWBAR / REDBAR, the town
  map PAL_TOWNMAP (its sprite through OBP0: the player is white / light blue / black) - all sampled headless.
- **Art** (`Gen1Art.RED` / `.BLUE`, one set of files): pokered's addresses; 14 icon loads a frame (no PIKACHU shape).
- **DEX**: their own pages (Yellow rewrote the text) and SGB palettes (`SuperPalettes`, the same format as CGBBasePalettes);
  MEW was added last, so its base stats (`MewBaseStats`) and pics sit in bank 1 (`Gen1DexTables.mewBaseStats`).
- **STEADY FF music**: Yellow's sound engine and audio RAM, their own PlayMusic (0x23A1) and songs (bank 0x1F's ids from
  INTRO_BATTLE on are one lower; no bank 0x20). Bank 0 holds no `18 FE` to park on, so the render core parks on WRAM:
  `pkRenderGbPark` writes `jr @` at 0xCD39 (wUnusedSimulatedJoypadStatesMask, written by the main loop, never read) first
  (mgba_dump's `gbpark` too). Route 1 loops at 22.9 s, the wild battle at 31.3 s after 13.1 s: Yellow's numbers.
- **Saves**: the user's Red save (Yellow's team, Nelson) loads in Blue, but its map's tileset pointers are Red's, which
  Blue keeps 0x10 lower, so Blue draws that Pokémon Center as garbage (`native-capture/boot/blue.txt` pokes Blue's pointers
  in for the fixtures). Not the companion's concern: what it reads is right either way.

**Yellow Legacy v1.0.10 and Crystal Legacy v1.3.1** (cRz-Shadows' pret forks, `$DECOMPS/pokeyellowlegacy` / `pokecrystallegacy`; both builds are
the patched carts byte for byte, so their .sym files give every address). Crystal Legacy's two patches, one per retail revision,
make the same ROM.
- Save: each keeps the save's size, not its layout, and neither reads a retail save right. Yellow Legacy's bag grew to 59 out of
  wGameProgressFlags' padding, moving wPlayerMoney .. the last script index 78 bytes; Crystal Legacy's ITEMS grew to 84 and BALLS / the PC's
  items moved into padding. `scripts/convert_save_legacy.py` rewrites a retail save. Yellow's needs the hack's ROM too: CONTINUE keeps the
  map's header and the tileset's (LoadMapHeader returns before them on wCurMapTileset's bit 7, which the game sets itself), so
  retail's pointers would run as code (an illegal opcode on the field's first frame) and retail's collision pointer leaves the player
  able to turn but not walk, on a map that draws as if nothing were wrong. Check a converted save by walking, not by a screenshot.
- Tables: only what changed is generated (`gen_gen1_tables.py yellow_legacy`, `gen_gen2_tables.py crystal_legacy`; `shares` checks the rest
  equals retail's). Yellow Legacy: moves, chart (BIRD gone, GHOST into PSYCHIC 2x), TMs, growth rates. SLAM is DRAGON there, and its status
  moves are id 6, the old BIRD: no chart entry, so nothing resists them, printed NORMAL by the game (`typeless`). Crystal Legacy: moves,
  items, TM ids (ITEM_C3 / ITEM_DC moved ahead of TM01, 0xC1).
- Art: Yellow Legacy's icons are an 8-tile icon per species, frame 1 then frame 2, from MonIcons below 0x80 and MonIcons2 above
  (`iconSheet2`). Crystal Legacy's are per species too, uncompressed, in two banks split at ICON_MAGIKARP, in PartyMenuOBPals' palette
  MonMenuIconPals' high nibble picks (`iconBankSplit`, `iconPalNibbles`). Its PokedexDataPointerTable is bank + address per species
  (`dexEntryFar`), not Crystal's words and bank table.
- GUIDE: `gen_gen1_guide.py` and `gen_crystal_guide.py legacy` read the forks' older source through a normalized copy (`normalized_tree`: names
  and rgbds syntax brought up to date), so retail's output stays byte for byte. Yellow Legacy's OLD ROD rolls a table like the GOOD ROD's.
  Crystal Legacy's TrainerGroups are far pointers and its party types flags (DVs, stat experience, happiness); Chuck, Jasmine and Pryce
  scale by badge count (NEXT BOSS reads it: `clScaledBoss`); a second team elsewhere is a rematch. Red's done flag is EVENT_DEFEATED_RED:
  it clears EVENT_RED_IN_MT_SILVER when he's beaten, the opposite of Crystal.
- Checked headless (fixtures `yellow_legacy*`, `crystal_legacy*`): EXPAND exact on 1249 of 1249 walking frames in Yellow Legacy (town, Pikachu
  following) and 645 of 645 in Crystal Legacy (ROUTE 29 into NEW BARK TOWN); PC, FLY (landed in PALLET TOWN, CHERRYGROVE CITY) and 80
  grass steps with the cooldown held; STEADY's park and loops (ROUTE 1 32.15 s; Crystal Legacy's 24.11 s and 27.73 s). Not driven through
  the real battle controller, as Polished Crystal's were.

**PureRed and PureBlue v2.7.6** (Game Boy; Vortyne's PureRGB, `GEN1_PURE_RED` / `GEN1_PURE_BLUE`, `GameKind.PURE_RED` /
`.PURE_BLUE`, `isPureRgb`, also `isGen1`): a pret/pokered fork, cloned at tag v2.7.6 into `$DECOMPS/purergb` and built with
rgbds 1.0.3 in Docker (`make red blue`): both builds are the release ROMs byte for byte (its releases are BPS patches over
retail Red / Blue), so pokered.sym / pokeblue.sym give every address. The two builds differ in banks 0x01 (from the save
screen on), 0x1A (tilesets), 0x1D (credits) and 0x3D (title) only: one set of tables, RAM and code addresses for both.
What's its own:
- **A Color cart**: rgbfix -c, so mGBA runs it as a Game Boy Color, with WRAM bank 2 holding its palette buffers
  (wGBCFullPalBuffer): D000-DFFF addresses are `wramx(1, ...)`, read and written by bank like Crystal's. It runs at GBC
  double speed in the overworld (GBCSetCPU2xSpeed). Its COLORS option defaults to OG: the Color's own two-tone palettes
  for the original carts (PureRed white / pink / dark red / black with green sprites, PureBlue white / light blue / blue /
  black with pink ones), which the party icons, town map, player, party HP bar and windows (pure black ink) take, sampled
  headless; the DEX pictures take its SuperPalettes (the per-Pokémon colours of its SGB1 / SGB2 modes).
- **RAM**: pokered's structs (party_struct / battle_struct, CatchRate now a flags byte), most of the save data 8 bytes
  later, the bag at 1:D542 with 30 slots (`Gen1Config.bagSlots`), wEnemyMon two bytes earlier (0xCFE3). Its party menu
  types are renumbered: BATTLE_PARTY_MENU is 1 (`Gen1Config.battlePartyMenu`).
- **Tables** (`gen_gen1_tables.py purered`): move and item names behind pointer tables, 35-byte base data (MEW in the main
  table, the front pic's bank at +27), 5-byte InternalMapEntries, its own types (NONE for STRUGGLE, CRYSTAL, TRI,
  FLOATING, MAGMA, and BONEMERANG's type that it names GROUND: `typeMatchups` lists one GROUND), items (HYPER BALL where
  the TOWN MAP was, ...), moves (SLAM is FILTHY SLAM, POISON), places (SAFFRON moved to 7 for its FLY list) and 25
  species on other growth curves (`gen1GrowthRatesPureRgb`). Its text has one-byte words from 0x33 ("the", "ing",
  "#MON" ...: `Gen1Charset.PURE`). Its forms (FLOATING WEEZING, the spirits, HARDENED ONIX, ...) are internal species
  carrying a base species' Dex number: the companion shows them as that species. No item descriptions but Yellow's for the
  items it kept under the same name, and its own TMs' moves.
- **Art** (`Gen1Art.PURE_RED` / `.PURE_BLUE`): its party icons are its own system (engine/gfx/mon_icons2.asm): MonPartyData
  a byte per Dex number from MISSINGNO. at 0, each icon 8 tiles at PartyMonSprites1 (34:4000), frame 1 the left 16x16 and
  frame 2 the right one; the font, town map and player are pokered's art at its own addresses.
- **DEX**: DexEntryData is a fixed 8-byte record per Dex number (feet, inches, pounds x 10, a TX_FAR_END - 0x15, its
  renumbered text commands), the category an index into DexCategoryStrings (PokemonCategoryMapping).
- **STEADY FF music**: Red's sound engine and audio RAM, PlayMusic 0x21D1, Red's songs with EVOLUTION where SAFARI ZONE was
  and bank 0x08 / 0x1F ids shifted; the park is 0x0DAE (the operand of a `jr` in bank 0: `18 FE`). Its added songs (bank
  0x36 ...) start another way and play SPED-UP. Route 1 loops at 22.9 s, the wild battle at 31.3 s after 13.1 s.
- **PC / FLY / WILD ENCOUNTERS** (`GB_FIELD_PURE_RGB`): see "PC, FLY and WILD ENCOUNTERS on the Game Boy".
- **Saves**: its save layout isn't Red's, but CONTINUE on a Red / Blue save runs its own updater (engine/menus/
  save_file_updater.asm: "Original Game", START), which converts and writes it. The user's Red save goes through it in
  `native-capture/boot/purergb.txt`; that save keeps Red's map header and tileset pointers for its Pokémon Center, which
  CONTINUE doesn't reload (wCurMapTileset bit 7), so the script clears the bit and says NO to the updater's warp to
  Pallet (YES froze the game headless). The converted save is only in the capture's disposable copy.
- **EXPAND**: not mapped (no `pk_wide_gb.c` row), like Red / Blue.

**Pokémon Crystal** (Game Boy Color; `GEN2_CRYSTAL`, `GameKind.CRYSTAL`): pret/pokecrystal builds every English
release byte for byte (`make crystal crystal11 crystal_au` with rgbds 1.0.4, its roms.sha1), and the three keep every RAM
address and every table the companion reads at the same place (their .sym files differ only in code). What's Gen 2's own:
- **WRAM banks**: the game state sits in WRAM bank 1 (D000-DFFF), and the game switches D000 to other banks mid-frame
  (palettes in bank 5, the battle's scratch), so a bus read between frames can land on the wrong bank (seen headless: a peek
  of wBattleMode read 0 in a battle). `Gen2Reader` gives D000-DFFF addresses as `wramx(bank, addr)` = (bank << 16) | addr,
  which the native `pkReadBytes` reads from that bank directly (`pk_gb_read_banked`) and `FixtureMemoryReader` from
  mgba_dump's `wramx.bin` (all 8 banks, written beside wram.bin for a Color cart).
- **A new game, played headless**: the user had no Crystal save, so one was played in rev 1 with mgba_dump's new
  `savestate` (and `loadstate`) in steps: name CHRIS, CHIKORITA, the lab's POTION, a PIDGEY caught on ROUTE 29 with Poké
  Balls poked into the bag, a BERRY poked onto CHIKORITA, saved through the START menu. That save loads unchanged in rev 0
  and Australia (`crystal_rev0`, `crystal_au`). Gen 2 can miss a one-frame press outside its menus: hold buttons 3 frames.
- **Gender** is the party menu's GetGender: female while (Attack DV << 4 | Speed DV) is at most BaseData's ratio.
- **Art** (`Gen2Art`, like `Gen1Art`, keyed by SHA1): the font (`Font`, 1bpp), party icons (8 2bpp tiles per icon id,
  `MonMenuIcons`, in `PartyMenuOBPals`' palette: every icon is that red), `HeldItemIcons`, the POKéGEAR maps (`JohtoMap` /
  `KantoMap` over the LZ `TownMapGFX`, palettes by `TownMapPals.PalMap` from `MalePokegearPals`; matched headless against
  the POKéGEAR's map card, the map card flag poked on to see it) and CHRIS / KRIS (the walking sprite's first frame in day's
  red / blue). A landmark's coordinates are the POKéGEAR icon's centre in screen pixels, so the maps are drawn at (0, 0).
- **DEX** (`Gen2Dex`, from the cart like `Gen1Dex`): `PokedexDataPointerTable` (a bank per 64 entries), pics by
  `PokemonPicPointers` through `FixPicBank.PicsBanks` (the stored bank is an index from "Pics 1" plus PICS_FIX), Gen 2's
  LZ (`Gen2Lz`), placed in the 7x7 box as PadFrontpic does, coloured with `PokemonPalettes`. Item descriptions come from
  `ItemDescriptions`, a TM's from its move (`TMHMMoves` -> `MoveDescriptions`), as PrintItemDescription does.
- **STEADY FF music** (2026-10-18): Gen1MusicRenderer drives Gen 2's sound engine too (`GEN2_MUSIC_CRYSTAL*`, `crystal = true`):
  a song is the id the first music channel that's on carries (its struct's MusicID: `wMusicID` stays stale after a save's
  map music comes back - 0x08 over ROUTE 29's channels on the Thor); PlayMusic (00:3B97) takes it in DE (the render core's
  call sets DE from c); the park is 0x28B2 (`18 FE` inside SaveScreen's `jr z` / `cp`); the loop state is each music
  channel's Flags1, MusicAddress / LastMusicAddress, NoteDuration and LoopCount (`gbloop N crystal`). `_PlayMusic` restarts
  only the channels a song uses, so each one is recorded after PlayMusic(0) (`_InitSound`): HEAL "looped" the last song's
  drums every 3.6 s before that. On the Thor: ROUTE 29 62.3 s, NEW BARK 37.5 s, the wild battle 36.5 s, HEAL ends.
- **Not done**: GUIDE, the POKéMON switch, a trainer-battle fixture, the TRAINER CARD.

**Pokémon Kristall** (German Crystal, Game Boy Color; `GEN2_CRYSTAL_DE`, still `GameKind.CRYSTAL`; sha1 `accb5842…`, CRC32
`616d85de`): English Crystal's code translated. Its RAM is English's byte for byte (the `crystal` fixture's save, written out by
`scripts/gen2_save_from_fixture.py crystal`, loads in it unchanged: `crystal_de*`); its ROM tables moved. What differs:
- **Names**: `scripts/gen_gen2_tables.py crystal_de <kristall.gbc> --base <crystal rev 1.gbc>` checks every table but the names
  against English's (species, moves' types and powers, the chart, landmark places, maps) and writes the names alone
  (`Gen2TextCrystalDeGen.kt`), laid over Crystal's as a `GameText` keyed by its game code BYTD (`CRYSTAL_DE_TEXT`); its
  landmarks break a name with 0x1E (a hyphen). `Gen2Config.language` / `gameCode` set `romLanguage` / `romGameCode`, so type
  names follow the cart too (DRACHEN, where the German GBA games say DRACHE) and the bag tab reads BEUTEL.
- **Battle menus**: KMPF / PkMn / BEUTEL / FLUCHT from column 5 (English's 9, `battleMenuLeft`), TAUSCH / STATUS / ZURÜCK from
  column 10 (12, `switchMenuLeft`); the party menu is English's.
- **DEX** (`GEN2_DEX_CRYSTAL_DE`): its own pointers; a page is metric (`metricBody`: height a byte in dm, weight a u16 in hg,
  then the text), printed as GR. 0.9m / GEW 6.4kg with no category suffix (`POKEDEX_CRYSTAL_DE`).
- **Art**: English's pixels (font, icons, maps, checked equal); only the town maps' tilemaps moved (`Gen2Art.CRYSTAL_DE`).
- **STEADY music**: PlayMusic 0x3B81, the park 0x289C; songs loop exactly as English's (`gbloop`, songs 4 and 9).
- **EXPAND**: English's `GB_ROMS` row but SpriteMovementData (bank 1, 0x4276).
- **PC / FLY / WILD**: `GbField.CrystalDe` (RunMemScript one byte later, Flypoints 0x1E later, its Fly script's text id).
- **GUIDE**: Crystal's; its hand-written lines stay English, like the European Yellows'.

**Pokémon Goldene Edition / Silberne Edition** (German Gold / Silver, Game Boy Color; `GEN2_GOLD_DE` / `GEN2_SILVER_DE`, still
`GameKind.GOLD` / `.SILVER`; sha1 `9254195d…` / `8ecc58d6…`, CRC32 `4889dfaa` / `96c9db95`): English Gold / Silver translated,
the way Kristall is English Crystal. Their RAM is English Gold's (`port_gen1.py` over pokegold.sym: every symbol at the same
place), so the gold fixture's save (`scripts/gen2_save_from_fixture.py gold`) loads in both; the two German carts share every
name table byte for byte. What differs:
- **Names**: `scripts/gen_gen2_tables.py gold_silver_de <goldene.gbc> <silberne.gbc> --base <gold.gbc> <silver.gbc>` writes the
  names alone (`Gen2TextGoldSilverDeGen.kt`, `GOLD_SILVER_DE_TEXT`, game codes AAUD / AAXD); species, move and type names are
  Kristall's words, items and landmarks their own.
- **Battle menus**: Kristall's columns (5 and 10).
- **DEX** (`GEN2_DEX_GOLD_SILVER_DE`): English Gold's formats at their own addresses, pages metric as Kristall's.
- **Art**: English's pixels; the icon tables 2 bytes later, the town maps' tilemaps 0x32 later (`Gen2Art.GOLD_DE` / `.SILVER_DE`).
- **STEADY music**: PlayMusic 0x3D8F, the park 0x29D0; the songs loop as English's (`gbloop`).
- The user's German Gold saves were empty, hence the fixture's save.

**Pokémon Gold and Silver** (Game Boy Color; `GEN2_GOLD` / `GEN2_SILVER`, `GameKind.GOLD` / `.SILVER`): pret/pokegold
builds both carts byte for byte (`pokegold.gbc` / `pokesilver.gbc`, its roms.sha1), and the two keep every RAM address at the
same place (their .sym files agree on all of WRAM / HRAM). What differs from Crystal:
- **RAM**: every address its own (pokegold.sym), the structs and menus Crystal's; wBattleMon, wCurOTMon and wCurBattleMon sit
  in bank 0. No gender choice (`playerGender = -1`: the boy).
- **Tables**: one set for both (`gen_gen2_tables.py gold_silver`, the two ROMs checked byte for byte); species, moves, types
  and chart are Crystal's (`--base`), items Crystal's less the four Crystal filled in (CLEAR BELL, GS BALL, BLUE CARD, EGG
  TICKET), landmarks one fewer (no BATTLE TOWER: from ROUTE 40 on, one id below Crystal's).
- **Art** (`Gen2Art.GOLD` / `.SILVER`, one set of files): Crystal's pixels from their own addresses (Silver's icon tables sit
  0x1A bytes before Gold's); the party menu and PACK are Crystal's colours, pixel for pixel in both versions (sampled headless).
- **DEX**: each version's own pages (`POKEDEX_GOLD` / `POKEDEX_SILVER`, two objects so a cache never crosses them).
  GetDexEntryPointer computes the page's bank (64 species a bank from 68:4000: `Gen2DexTables.dexEntryFirstBank`), and
  FixPicBank is a table of {stored, real} bank pairs (`picBankFixes`), not Crystal's PicsBanks index.
- **A new game, played headless** in Gold (the user had none): GOLD, CYNDAQUIL, the aide's POTION, a PIDGEY caught on ROUTE 29
  with Poké Balls poked into the bag, a BERRY poked onto CYNDAQUIL; the same file loads in Silver. mgba_dump's `loadstate` left
  this cart's PACK listing "?" items (RAM identical), so the save and fixtures come from single runs fed commands live. Gold's
  hROMBank is FF9F (`gbfar` writes Crystal's FF9D) and its FarCall takes a = bank, hl = address (`gbinject`'s trampoline is
  Crystal's): `gold_trainer`'s README has the trainer battle's workaround (a 15-byte trampoline poked over it).
- **STEADY FF music** (GitHub #390): Crystal's engine at wChannel1 C001, PlayMusic 0x3D98, the park 0x29A3 (`goldSilverMusic`; `gbloop N gold`).
- **Not done**: GUIDE, the
  TRAINER CARD.

**Polished Crystal** (Game Boy Color; Rangi42's open-source hack of Crystal; `GameKind.POLISHED_CRYSTAL`, `GEN2_POLISHED_CRYSTAL` /
`_FAITHFUL`): versions 3.2.0 to 3.2.3, each in a normal, a faithful (the original games' types, moves and three item names) and
their debug builds - every official release build. Each release ships its own .sym file, so every address is a symbol: the
generator (`gen_polished_crystal_tables.py`, reading the releases dir and `$DECOMPS/polishedcrystal/src` at v3.2.3 for the
charmap and map list) checks all 16 builds and writes one table set plus a faithful overlay, and each build's ROM table
addresses (`Gen2PolishedCrystalGen.kt`, by SHA-1: the art and dex tables move between versions). RAM is the same in all 16.
What's its own:
- **Species are 9-bit**, with forms: the struct's species byte plus the form byte's bit 5. Regional / variant forms (ALOLAN
  RATTATA ...) have their own entry in every per-species table after NUM_SPECIES (0x123), in VariantSpeciesAndFormTable's order
  (GetSpeciesAndFormIndex): the companion's species id is that index + 1 (`polishedSpeciesId`), so a form has its own name,
  types and mini. Party icons and the dex flags go by the cosmetic index instead (38 cosmetic forms come before the variants).
- **Its charmap**: '@' is 0x53, text is mixed case and 0x0A - 0x4C are letter pairs; the generator reads charmap.asm.
- **Its LZ** (`Gen2Lz.decompress(polished = true)`): a run repeats its byte n + 2 times, the alternating pair writes n + 3
  bytes, a long length has one high bit - home/decompress.asm and its compressor's counts. Crystal's decoder got a third of
  the minis wrong.
- **Bag**: ITEMS / MEDICINE / BALLS / BERRIES as Crystal's {id, quantity} lists, key items a 0-terminated list of their own
  ids (the companion's 0x200 + n), TMs / HMs a bit each (0x100 + n), apricorns counts in the APRICORN BOX (not listed).
- **Art** (`Gen2Art`): FontNormal (1bpp, its charmap), party minis (MiniIconPointers, 8 LZ tiles) in white / the species'
  two PokemonPalettes colours / black (the game varies them by DVs), town maps with flip bits (yxTTTTTT) over TownMapGFX.
- **DEX** (`Gen2Dex`'s polished branch): PokedexDataPointerTable's pages (category, two pages, '@'-ended), PokemonBodyData's
  height / weight (metric), PokemonPicPointers' front pics sized by PokemonPicSizes' nybbles. National number = the species'
  9-bit value, less 2 past 0xFF / 0x100 (289). Abilities from BaseData +13 / +14 (the hidden one +15), named by AbilityNames
  (the faithful builds' own, read from their ROM).
- **Text**: '@' (0x53) or <DONE> (0x52) ends a string; <CTXT> (0x5D) starts Huffman-coded text, most significant bit first,
  walked through TextCompressionHuffmanTree (node n's branches at 2n / 2n + 1, a value from 0x7F a character, 0xEC - 0xFB
  standing for 0x4D - 0x5C: home/text.asm's ReadHuffmanChar) - the `text` macro compresses whenever that's shorter, so most
  item descriptions are. Line codes become line breaks, so `Gen2Dex.flow` rejoins the words the game hyphenates over two lines.
- **Bag text** (`Gen2Dex.itemDescription`): ItemDescriptions by item id, KeyItemDescriptions by key item, a TM / HM's move's
  MoveDescriptions (TMHMMoves) - PrintItemDescription / PrintKeyItemDescription / PrintTMHMDescription.
- **The player on the town map**: the overworld sprite wPlayerGender picks (ChrisSpriteGFX / KrisSpriteGFX / CrysSpriteGFX, LZ),
  its first frame in PokegearOBPals' red / blue / green (InitializePokegearPlayerIcon) - `Gen2Art.POLISHED_PLAYERS`.
- **Menus**: the battle menu and move list sit where Crystal's do (its words Fight / PkMn / Bag / Run), its ▶ is tile 0xF0
  (`Gen2Config.cursorTile`); the party menu is Crystal's, its SWITCH / SUMMARY / CANCEL a column left (from (11, 12)). Its party
  menus read the keys every other frame (`w2DMenuFlags1` bit 6), like Prism's, which the 2-frame Game Boy presses already cover.
- **Growth rates** are numbered 0 medium fast, 1 medium slow, 2 fast, 3 slow (Gen 3's curves 0, 3, 4, 5: `expProgress`).
- **GUIDE** (the first Game Boy guide; `GuidePolishedCrystal.kt` + `GuidePolishedCrystalGen.kt` from
  `scripts/gen_polished_crystal_guide.py <releases dir>`): no GBA tables, so `GameGuide.world` (a `GuideWorld`) gives HERE's
  wild Pokémon and NEXT BOSS's teams from generated data. The generator reads every build through its .sym and requires each to
  equal 3.2.3's (faithful builds faithful 3.2.3's: 8 boss teams differ): the wild tables (grass by morning / day / night, surfing,
  the rods' fish groups, headbutt tree and rock smash sets), the bosses' parties from TrainerGroups (each found by its own
  script's `loadtrainer` / `setevent`), each map's MapScriptHeader events (item / key item / TM balls, hidden items, `trade`
  objects) and the source's gifts, their command bytes found in each build's label spans. `readGen2Telemetry` fills
  `SaveProgress`: wEventFlags (01:DA5A) as the flags, wJohtoBadges / wKantoBadges as flags 0x10000+, wTradeFlags 0x20000+,
  wTimeOfDay as var 0x10000. Evening uses the night's grass, surfing adds up to 4 levels, a level over 100 is badge-relative
  (AdjustLevelForBadges). The League's room flags reset each visit, so the first run is done by EVENT_BEAT_ELITE_FOUR, the
  rematch by EVENT_BEAT_ELITE_FOUR_AGAIN. No EVOLUTIONS, swarms, roamers or static encounters; the HUD's WILD and the DEX's
  AREA still read GBA tables only.
- **Battle controls** were run through the real `BattleInputController` against mgba_dump running 3.2.3, frame by frame (a
  local harness over its stdin, not committed): a wild battle from the save and LYRA's, started headless (`loadtrainer`'s writes,
  then Script_startbattle 25:6AD6 through a `gbinject` using Polished's `rst $10` FarCall, address then bank inline - not
  pokecrystal's `rst $08`). Its battle menus drop presses in the 3 frames after a cursor move and in their first frames (Crystal's
  too), which once lost a FIGHT tap and once picked the wrong move, so every Game Boy pick now steers on the menu read each frame
  (`GbPartySwitch.battleMenu`, `driveGbPickLocked`) and presses again what didn't take. A pick takes 15-31 frames, a switch 16-57.
- **No save existed**: a new game was played headless in 3.2.3 (CYNDAQUIL, LYRA's lab battle lost, a PIDGEY caught on ROUTE 29)
  and the same file put under each build's name; it loads in 3.2.0 - 3.2.2 and faithful 3.2.3 as it is (save version 10 in all).
  Its hROMBank is FF87, not pokecrystal's FF9D, so mgba_dump's `gbfar` / `gbinject` don't run there; `loadstate` broke its
  move list's cursor (as Gold's PACK), so the fixtures were dumped in single live runs (see the `polished_crystal*` READMEs).

**Pokémon Crystal Clear** (Game Boy Color; ShockSlayer's open-world hack of Crystal; `GameKind.CRYSTAL_CLEAR`,
`GEN2_CRYSTAL_CLEAR_V2_6_0` / `_V2_5_11`): v2.5.11 and v2.6.0, each released as a Standard Colors and a Color Filter build.
The two builds of a version are the same code byte for byte but for ~9,400 bytes of palettes (and the header checksum), so
they share everything; the two versions share the RAM and nearly every ROM table. It is pokecrystal grown and rebuilt with
no symbol file (3% of bank 0 is where Crystal has it), so:
- **RAM**: `scripts/port_gen1.py` (generalised from the Yellow ports: any pret .sym into another build) maps
  pokecrystal11.sym by the code that uses each symbol: `port_gen1.py --loose pokecrystal11.sym crystal11.gbc cc.gbc
  wPartyCount wMapGroup ...` (`--loose` masks `ld a,n` and `jr` operands, `--window N` narrows the code compared).
  Everything the companion reads stayed but wMapGroup / wMapNumber (moved into bank-1 bytes Crystal leaves unused, DA3A;
  wYCoord / wXCoord stayed at DCB7, hence `Gen2Config.coords`) and wPokedexCaught (DA0E; wPokedexSeen stayed). Each was then
  checked in dumps (the map ids following the player through three maps, the caught bits after a catch). wPlayerGender's
  byte is still read, but the hack picks a gender (MALE / FEMALE / NONE) apart from the character's look, and nothing in the
  companion uses it there.
- **Crystal's save doesn't fit**: its map ids sit where Crystal Clear keeps other things, so CONTINUE lands on a broken map
  and overwrites WRAM. A new game was played headless in v2.6.0 (`savestate` / `loadstate` in steps; NORMAL mode, NEW BARK
  TOWN, the character screen's DONE, BULBASAUR, MOM's battle, a SENTRET caught on ROUTE 29) and saved in game; it loads in all
  four builds. Hold presses 6 frames in its menus (3 were dropped at times).
- **ROM tables** (gen_gen2_tables.py `crystal_clear`, every table checked the same in all four builds): found by their
  contents where Crystal's bytes survived (base data, moves, type chart, font, the town maps and their palettes, dex
  banks, pic banks, TM moves) and by the code that reads them otherwise (port_gen1.py's votes, or the routine by hand:
  the party icons at 23:6F3C, IsInJohto at 00:3060). Its species, base data, moves, types and type chart equal Crystal's
  (`--base` checks them and they aren't written again); its item names (16 renamed: REGION MAP, HOUSE KEY, DEX TRACKER, new
  balls...), 117 landmarks (21 past FAST SHIP) and 26 map groups (488 maps, no decomp: a group runs to the next group's
  pointer, the last one while its entries look like headers) are its own.
- **Its text is packed**: PlaceNextChar looks bytes 0x16-0x5F up in a table of 3-byte entries at 00:11FF (0 + a bank-0
  string such as "with " or "#MON", or 1 + a routine: line breaks, the player's name). Move names, dex pages and
  descriptions use it; the generator and `Gen2Dex` (`Gen2DexTables.textDict`) expand it.
- **Regions**: its RegionCheck gives a landmark past FAST SHIP the region of a stand-in landmark from a table (72:6F8B,
  one entry short: ORIGIN PLAINS reads the code after it and is on no map here).
- **Art** (`Gen2Art.CRYSTAL_CLEAR_V2_6_0` / `_V2_5_11`, into paths of its own that the art is rebuilt into whenever
  another of its builds starts, the Color Filter's colours included): a party icon per species, LZ-compressed, by Dex
  number from 23:7019 in bank 0x1B / 0x1D below / from species 0x80 (v2.5.11: 0x1C / 0x1E), UNOWN's by letter, coloured
  with the species' two icon colours (02:622C, 8 bytes each) over black; the town maps in the default RED POKéGEAR's
  colours (the player picks the gear's and the PACK's colours at the start). No player head: the character is the player's
  own model and colours.
- **STEADY FF music** (GitHub #390): Crystal's engine and channels, PlayMusic 0x3CDC (a wrapper that can swap the song for a map's), the park 0x28C8 (`crystalClearMusic`).
- **Not done**: GUIDE, the POKéMON switch, the CARD, its own trainer data, and its features
  (the Nuzlocke mode, following Pokémon, the character's own look).

**Pokémon Prism** (Game Boy Color; RainbowDevs' Crystal-engine hack with its own regions, Naljo and Rijon, plus parts
of Johto, Kanto, the Sevii Islands and Tunod; `GameKind.PRISM`, `GEN2_PRISM`): v0.95 build 254 (Hotfix 5), the final
release. No source or symbol file, and much more changed than Crystal Clear, so:
- **The "patched Crystal"**: a second file the user had, named as a patched Crystal (`Pokemon - Crystal Version (USA)
  (patched).gbc`, sha1 `3569e15d…`), calls itself "LOL PRISM" in its header. It is Crystal rev 0 with its first two
  banks (32 KB) replaced by a ROM-only program that fills the screen with "LOL" tiles and halts: no game to read, left out.
- **RAM**: `scripts/port_gen1.py --loose` with pokecrystal11.sym put nearly everything at Crystal's addresses (party,
  map ids and coordinates, wBattleMode, both battle_structs, wOTParty*, the 2D menu, the tilemap, money D84E, game time).
  The bag moved and grew: wTMsHMs D859 is 13 bytes of bits (TM01-96, HM01-05: Prism's TMs are reusable, FlagAction on
  it at 0B:48DC; the TM pocket matched a poked bit), the pockets follow at D866 (40 items), D8B8 (50 key items), D8EC
  (25 balls) and D920 (PC, 60) - ReceiveItem's capacities (12:437D). Caught / seen stayed at DE99 / DEB9, 32 bytes each
  (254 species fit). Every address was then checked on headless dumps.
- **A new game, headless**: Crystal's and Crystal Clear's saves both boot to "The save file is corrupted!". The intro
  runs on its own (clock, model, name), then the father's camp, the path down to a mine cart that takes the player into
  ACQUA MINES. The story there gives no Pokémon and locks the START menu and wild encounters (D84C bit 5, checked at
  25:54F2), so mgba_dump grew `gbfar` (call a banked routine between frames, registers put back) to run the game's own
  TryAddMonToParty (3C:4000, found by Crystal's code) and ReceiveItem (00:12E5), `gbpoke` for banked WRAM, `gbvdump`
  for the Color palettes / OAM, and `gbinject` (make the game call a routine from its frame wait, interrupts on) to
  start LEADER JOSIAH's battle through its own loadtrainer / startbattle (25:611B / 25:614A). The bit was cleared to walk
  until a wild SHINX came (caught) and to save, then set again at the save prompt: the save holds the game's own value.
- **ROM tables** (gen_gen2_tables.py `prism`, each found by the code that reads it, listed in GAMES["prism"]): species
  names (10 bytes, 05:55E5), BaseData shrunk to 24 bytes (14:5A0E: Crystal's first 24, no TM bits), moves (10:5263, 7
  bytes, the category in the type's top two bits), move and item names as a length byte then the text (11:61BC,
  11:4000), TypeNames' pointers (13:53DA: Fairy 10, Gas 11, Sound 13, Tri 14, Prism 15), a type chart of 2-bit cells
  (0D:7AC4, 7 bytes per attacking type: 0 none, 1 half, 2 normal, 3 double - BattleCheckTypeMatchup at 0D:5C0E),
  TMHMMoves (04:53A4, GetTMHMMove), landmark names (09:67EF) placed by each region's town map grid (09:6638: LZ, 18 x 15
  cells, bit 7 on the cell its dot is drawn at, 09:656F), 95 map groups (25:4000). Its growth rates add two Crystal lacks
  (6, 7): `expProgress` covers the four Gen 3 shares.
- **Art** (`Gen2Art.PRISM`): party icons LZ per species by a {bank, address} table (1C:77CE), each in one of the 8 OBJ
  palettes at 02:6079 its byte in 23:7BEB picks (high nybble; the low one is the shiny's: 23:7D42), traced with
  `gbvdump` on the party menu; the font at 3D:720D; six town maps (LZ tilemaps of 20 x 17 drawn from the second row,
  23:4F3C; LZ tiles 41:4210; the palette map 23:5151; BG palettes 02:6129), matched against the map screen headless
  (opened with `gbinject C002 23 4dd6 0f4e`) pixel for pixel but for its sprites and blinking dots.
- **DEX** (`GEN2_DEX_PRISM`, `POKEDEX_PRISM`), found from DisplayDexEntry (02:79C1, by the "No." tiles Crystal's places):
  GetDexEntryPointer (02:7ADA) takes a word per species at 02:7B1B, in the bank 00:081D names per 64 species. A page is
  the category, then (where its text's done left off: wc2d0) height in decimetres and weight in hectograms, a byte with
  page 1's length, page 1 and page 2. The game prints them metric unless its UNITS option (wOptions2 CFD1 bit 2) says
  imperial (51:4144 converts). The list (10:48DF) is the 253 species at 10:4915 in that order, numbered by their place
  (10:4847); seen / caught stay a bit per species id. The picture: GetMonFrontpic (0B:6F54) reads {bank, address} at
  17:4000 + 6 per species (the bank itself, no FixPicBank), Crystal's LZ, the size from BaseData; colours from 02:4C84 by
  species (Crystal's table where the species kept Crystal's id). The bag's text: 11:4AD0 (items, 11:4AF9) and the TM
  pocket's move text (0B:49EB, 0B:5704).
- **Its text engine** (`Gen2PackedText`; 00:3266 runs it): segments - a plain string to its 0x50 (PlaceString 00:2FC5,
  control characters 0x44-0x5F by 00:301C's table, some a bank-0 word: "Poké", "Trainer"), a reference (0x04-0x43 + a
  byte: the address less 0x3C00, then the bank, bit 7 = the text ends after it), 03 + a Huffman stream (35:4E14: bits
  most significant first down the tree at 35:4E9A, two bytes a node, leaves from 0x70; 0x78-0x7B are 0x58-0x5B,
  0xBC-0xCF the control characters 0x44-0x57, 0x7D + 4 bits from 35:4F74, 0x7C + 6 bits through the ranges at 35:4F84;
  a 0x50 leaf ends the stream), 01 + code (32C5 jumps into it: only the UNITS choice is used in the dex, `ld a,
  [wOptions2] / and 4 / ld hl, metric / ret z / ld hl, imperial / ret`); the text ends at a 0x50 where a segment starts
  or at a 0x57 (done: the caller reads on past it). Checked headless: the Pokédex flag (D84C bit 0) and every seen /
  caught bit poked on the prism save, START > Pokédex, then LARVITAR, FROSLASS (a UNITS text), PHANCERO, VARANEOUS,
  RAIWATO, FAMBACO and LIBABEEL: both page halves, HT / WT and the picture pixel for pixel (`PrismDexTest`'s CRCs);
  every page and item description decodes.
- **Not done**: GUIDE, the POKéMON switch, the CARD, STEADY FF music, abilities (Prism has them), the player's head on
  the map; the dex follows the UNITS option's default (metric) rather than the save's choice.

**The European Emeralds** (Spanish / German / French / Italian, BPES / BPED /
BPEF / BPEI, rev 0; `NATIVE_EMERALD_ES` / `_DE` / `_FR` / `_IT`) are English's
game: every RAM address the app reads is English's, and English's save loads in
each with the same party bytes, money and position (`emerald_es` / `_de` / `_fr`
/ `_it`: headless boots of that save, `EmeraldLanguagesTest`). German / Italian's
battle code sits 4 bytes later. Their ROM data moved (localized text has other
lengths) but kept English's layouts; each table was found as the word English's
code loads it from, read at the same place in the language's code (icons, dex,
trainers, learnsets, wild encounters). What's theirs:
- **Names in the game's language** (species, moves, items, natures, map
  sections; item descriptions are read from the ROM at runtime, see "Item
  descriptions" below): `scripts/gen_emerald_lang_tables.py` reads them from
  each ROM into `EmeraldText<Lang>Gen.kt` (its `en` mode decodes English's
  tables the same way and matches the app's). `NativeConfig.language` →
  `romLanguage` → `localEmerald` switches the lookups; `gameCase` leaves them
  alone. Types stay English's (the type colours are keyed by name). Each game
  redrew its POKéBLOCK glyphs for its own word (RIEGEL, CUBO, BLOC, Italian's
  MELLE / MELLA at 0x5E-0x63), read off the fonts. Hand-written GUIDE entries
  still tag areas by English name (`englishMapSecName`).
- **The dex page** as each game words it, from headless screenshots of
  TORCHIC's: German / French print the category alone, Spanish / Italian put
  POKéMON first (`categoryPrefix`), all four in metres / kilograms with a
  decimal comma (`metric`). `pokedexMatchesRom` checks each game's own word for
  BULBASAUR's category (`probeCategory`: SEMILLA / SAMEN / GRAINE / SEME), the
  guide probe each game's ROXANNE (PETRA / FELIZIA / ROXANNE / PETRA).
- **Party art**: the slot tiles (the HP label: PS / KP / PV, and the level
  glyph), status icons and FONT_SMALL (+ its widths) are localized, so they're
  fingerprinted per language (`EM_<LANG>_*`, `gen_rom_art_sigs.py`) and land in
  `partyem_<lang>/`; the backdrop and Poke Ball are English's, pixel for pixel.
- **No TRAINER CARD** yet: their cards are localized art, not matched.

**Japanese Emerald** (BPEJ, rev 0, `NATIVE_EMERALD_JA`) is its own build, but the
same game underneath: English's save loads in it with the same party bytes,
money and position, and a scripted wild battle reads with English's
BattlePokemon / gMain layouts (`emerald_ja`, `emerald_ja_battle`). What moved:
- **Every RAM global and the battle code** (-0x3F0): found through English's
  literal pools, word for word; the action / move / bag / party handlers were
  checked in that battle, the party menu at 0x0203CB94 (the POKéMON switch),
  the region map watch's three pointers likewise.
- **Shorter ROM records**: dex entries are 0x1C bytes (categoryName[6], height
  +6, weight +8, text +0x0C - `PokedexTables.entryCategoryLen` / `entryHeightOff`
  / `entryDescOff`), ability names 8, trainers 0x20 (trainerName[6], partySize
  +0x18, party +0x1C - `GuideTables.trainer*`), items 40 (name[10], text +16);
  species / move names 6 / 8 bytes. gItems and the nature names were found by
  content (its code doesn't match English's there).
- **Kana**: hiragana / katakana where the Western letters are (charmap.txt's
  Japanese half). `gen_emerald_lang_tables.py ja` writes its names;
  `Gen3Text` switches to its Japanese table while `romLanguage == 'J'` (dex
  text, abilities, trainer names), a line break reading as a full-width space.
  The dex page: "ひよこポケモン", "0.4m", "2.5kg" (`categorySeparator`,
  `decimalPoint`, `unitSpace`), as the game prints TORCHIC's.
- **Party slot**: the app's palette slot, which draws kana with the app's
  Japanese font - its own FONT_SMALL isn't the Western layout the slot art
  draws names with. No TRAINER CARD.

**Emerald Legacy v1.1.4** (BPEE, 16 MB, sha1 `cbd71af6…`, `NATIVE_EMERALD_LEGACY`) keeps retail's header but is a
rebuild: its source (cRz-Shadows/Pokemon_Emerald_Legacy, tag `V1.1.4`) cloned into `$DECOMPS/pokelegacy` and built
with agbcc (`make` in the decomp build image, after `/opt/agbcc/install.sh`) is the release byte for byte, so every
address in the app is a symbol of that ELF, and struct offsets / constants come from agbcc compiling `offsetof` against
its headers. It runs as `GameKind.EMERALD` (retail's look, art, map and menus: all 35 retail art blobs are in it,
byte for byte) under its own text key `LEGACY_TEXT` (`emeraldLegacy`), like Kaizo. What differs from retail:
- **RAM**: the battle globals kept retail's EWRAM (cursors, gEnemyParty, gBattleStruct); gMain, the save-block
  pointers, gObjectEvents, gMapHeader, gBagPockets, gPartyMenu and the battle input handlers moved. Every retail-keyed
  place checks `emeraldLegacy` first (the POKéMON switch, RegionMapWatch's pointers, field items), or it would read
  retail's addresses under the same BPEE header.
- **SaveBlock1** grew: a 120-slot ITEMS pocket (0x168 bytes more), 960 trainer flags (`MAX_TRAINERS_COUNT`: the
  system flags start at 0x8C0, not 0x860) and one more var. Flags +0x13D8, vars +0x1510, game stats +0x1714, dex seen
  copies +0xAF0 / +0x3B34, contest winners +0x3008; SaveBlock2's frontier is 0x14 longer (its Pyramid bag holds 13).
  The bag goes through gBagPockets, so it needs nothing; the dex, guide and card tables carry the offsets. The PC
  BOXES editor refuses it (`SaveFileEdits.formatFor`): it would edit the bag at retail's offsets.
- **Species**: three Deoxys forms at 412-414, SPECIES_EGG 415 - an egg is flagged (`NativeConfig.eggSpecies`) and
  `isVanillaEgg` is off for it; the forms take DEOXYS's types. ~130 moves rebalanced (a few change type), two new
  effects: `gen_emerald_lang_tables.py legacy` writes its names, move types / powers and the move advice's
  `MoveStats`. Its Hoenn dex is 210 (eight added after DEOXYS).
- **Guide**: its own (`GuideEmeraldLegacy.kt`): WALLACE leads SOOTOPOLIS until the HALL OF FAME, STEVEN is champion,
  BRAWLY's team follows the badges you have; WHERE IS and HERE's people / items come from its tree
  (`gen_guide_areas.py legacy`). Its trainers are one `struct TrainerMon` (`GuideTables.trainerMon`). About 40 maps keep
  a second set of wild Pokémon in the header after their first, picked by a var once the post-game event in MOSSDEEP
  CITY sets it (`GuideTables.wildSetVars`, `wildSetOf`).
- **TRAINER CARD**: `CardStyle.LEGACY` - retail's card plus a sixth palette (`LG_CARD_PAL5`, its black card): catching
  MEW is a fifth star. Matched pixel for pixel against its own card, plain and with every star poked in
  (`emerald_legacy_stars`).
- **WIDE**: the EX family (ghoulslash's overworld expansion: ObjectEvent.spriteId +0x23, a u16 template graphicsId at +2
  with the kind at +1, 300 graphics records), every map and battle frame exact (`verify_wide.py`).

**The other-language FireRed / LeafGreen / Ruby / Sapphire** (and English Ruby /
Sapphire rev 0) aren't hand-ported: `scripts/port_retail.py <roms dir>` does what
the Emerald ones needed, for every ROM in the dir, and `scripts/verify_ports.py`
checks the result headlessly. What the port script does per ROM:
1. picks the English ROM (same dir) whose code is most alike;
2. maps every address of its config - RAM and ROM tables through the literal pools
   of the code that loads them (the code before each use of the word in English,
   found in the language's code: the word there), battle handlers by their code
   bytes; fallbacks for what those miss: the handlers' code with BL targets blanked,
   near where the other handlers moved; language-neutral tables (species ->
   national, the icon tables) by their bytes / what they point at; ability names by
   their shape; natures by a pointer table starting with that language's HARDY;
3. detects the layouts Japanese shrank from known values (BULBASAUR's 7 / 69 for the
   dex entries, item ids 1, 2, 3... for gItems, 40 trainers in a row for gTrainers),
   and `struct Main`'s inBattle offset (a literal too: Japanese Ruby / Sapphire's is
   0x439, English's 0x43D);
4. reads the names (`GameText<Code>Gen.kt`), the probes (the dex's BULBASAUR
   category, the gym leader's name) and the FireReds' party art offsets, and writes
   `gamedata/gen3/ports/<game>.toml`: one config per game code + revision (`RETAIL_PORTS`
   in the generated `NativeConfigsGen.kt`, which `TelemetrySampler.otherRetailConfig` consults
   first; it runs `scripts/gen_gamedata.py` itself), and `RetailPortsGen.kt`: titles by SHA1, the name sets.
   `build/ports/ports.json` records how each address was found.

`verify_ports.py` boots every ported ROM and its English one (pokedaisy-capture,
`mgba_dump`) with that game's English save - the other languages load it as is -
dumps it on the field and in a scripted wild battle (stepping through FIGHT / BAG /
POKéMON and reading gBattlerControllerFuncs at each), then `PortsVerifyTest` compares
each with English: party, bag, money, place, the dex (every national number's species,
height and weight), the guide tables and the gym leader's party, the battlers, the
battle handlers, and that every name resolves. It writes `build/ports/report.md` and
`build/ports/fields.png` (every ROM's field, at a glance). All 34 pass. `PortedRomsTest`
keeps four of those dumps (`port_*`, against `port_en_*`) as fixtures. The party art the
European FireReds redrew (HP label, status icons, font) is fingerprinted from the ports
(`FR_<LANG>_*`, `gen_rom_art_sigs.py`) into `partyfr_<lang>/`; European Ruby / Sapphire
borrow that language's Emerald art, as English Ruby / Sapphire borrow English's.
Japanese FireRed / LeafGreen can't load the English saves ("the saved data was lost"),
so they boot with Japanese ones (`host_roms.conf`'s `firered_ja` / `leafgreen_ja`) and are
checked absolutely instead (no English run to compare with: real Pokémon, real items,
money in range, a named place - plus the ROM-only checks); their money / encryption key
offsets (English's, `0x290` / `0xF20`) matched the game's own trainer card (100171円 /
83420円), and `PortedRomsTest` pins Japanese FireRed's (`port_bprj1`). Japanese Emerald
was rechecked the same way with a Japanese save (`emerald_ja_own`: 284669円, 101 caught,
as its card shows). The Pokémon Box / debug / pirate dumps are skipped.

**LeafGreen / Ruby / Sapphire were checked against the games themselves**
(2026-09-29): each ROM booted headlessly with the user's save
(`capture_fixture_headless.sh <key> --script native-capture/boot/leafgreen.txt`
or `ruby_sapphire.txt`), the game's own party menu, every bag pocket and the
POKéDEX screenshotted, and the app's decode of the same RAM compared
(`LeafGreenDecodeTest`, `RubySapphireDecodeTest`, both revisions each):

| Save                       | Party (game screen = app)                                                            | Bag                                                                     | POKéDEX seen / owned      |
| -------------------------- | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------------- | ------------------------- |
| LeafGreen (FEDE, Cinnabar) | ARTICUNO 53, MOLTRES 55, MEWTWO 72, CHARIZARD 100, HAUNTER 25, BLASTOISE 68 — HP too | MOON STONE ×2, LUCKY PUNCH, … · TEACHY TV, TM CASE, … · POKé BALL ×62   | 180 / 86 (Kanto 142 / 75) |
| Ruby (FEDE, Slateport)     | RAYQUAZA 100, GROUDON 56, ZAPDOS 68, BLAZIKEN 100, CHARIZARD 100, ARTICUNO 68        | POTION … REVIVE · ULTRA ×17, DIVE ×3, NET ×2 · ITEMFINDER, MACH BIKE, … | 133 / 29                  |
| Sapphire (AHS, Oldale)     | WAILORD 40, RAYQUAZA, KYOGRE, MEW, CHARIZARD, MEWTWO (100)                           | Items empty · ULTRA BALL ×11 · TM20 + HMs · MACH BIKE, CONTEST PASS, …  | 199 / 159                 |

The Ruby save's newer slot is internally inconsistent, so the game itself
says "The save file is corrupt" and loads the older slot — the fixtures hold
that one. Ruby's TM case lists TM13 before the HMs in game; the app keeps the
save's slot order (as it does for Emerald).

### POKéDEX (DEX tab)

A **DEX** tab (between PARTY and MAP) lists every entry of the regional or
National Dex with the save's seen / caught marks, filters (ALL / CAUGHT /
SEEN ONLY / NOT SEEN), and a full entry page: front sprite, category, types,
height / weight in the game's own rounding, footprint, the dex text, base
stats, abilities (+ hidden where the game has them), egg groups, gender, catch rate. The
flags come from the save in RAM (checked the way each game's own
`GetSetPokedexFlag` does, anti-cheat copies included); everything else is read
from the running ROM (`companion/data/Pokedex.kt`, `ui/PokedexScreen.kt`). Only
games with a `PokedexTables` config get the tab:

| ROM                             | Dexes                                                       | Where the flags are                              | Checked against the game's own POKéDEX                                         |
| ------------------------------- | ----------------------------------------------------------- | ------------------------------------------------ | ------------------------------------------------------------------------------ |
| Vanilla FireRed rev 1           | Kanto (151) + National                                      | SaveBlock2 + SaveBlock1's two anti-cheat copies  | seen 138 / Kanto 131, owned 69                                                 |
| Vanilla LeafGreen rev 0/1       | Kanto (151) + National                                      | as FireRed                                       | seen 180 / Kanto 142, owned 86 / 75                                            |
| Vanilla Emerald                 | Hoenn (202, TREECKO = No.001) + National                    | as FireRed (Emerald's copies)                    | Hoenn mode: seen 108, owned 28                                                 |
| Other Emeralds (ES/DE/FR/IT/JP) | as Emerald, in the game's language and units                | as Emerald                                       | the English save's 108 / 28, via `pokedexMatchesRom` per language               |
| Vanilla Ruby / Sapphire rev 1/2 | Hoenn (202) + National; text is two pages, joined           | as Emerald                                       | 133 / 29 · 199 / 159                                                           |
| Unbound v2.1.1.1                | Borrius (498) + National (905)                              | SaveBlock1+0x310 / +0x38D (CFRU)                 | seen 4 (Borrius 2), owned 1 (GIBLE)                                            |
| Radical Red v4.1                | Kanto (151) + National (1025)                               | SaveBlock1+0x310 / +0x3B4, 164 bytes each        | seen 2 (CHARMANDER, SQUIRTLE), owned 1                                         |
| Amethyst v1.3.0, v1.4.1         | its own dex (390, the game calls it Kanto) + National (690) | as Unbound                                       | seen 2 (No.182 EEVEE, No.340 TEPIG), owned 1                                   |
| Odyssey v4.1.1                  | Talrega (= national 1-151) + National (409)                 | SaveBlock2, no copies                            | seen Talrega 1 / National 8, owned 2                                           |
| Gaia v3.2                       | one dex (721)                                               | fixed EWRAM 0x0203C400 / 0x0203C45B              | the list's only mark: No.390 CHIMCHAR, caught                                  |
| Celia's Stupid Romhack v1.1.4   | one dex (150; its No.73 has no species)                     | SaveBlock2+0x59 / +0x28, 49 bytes, No. n = bit n | seen 1 / owned 1 (CHARMANDER)                                                  |
| Heart and Soul v2.0.6           | Johto (282) + National (1080)                               | SaveBlock1+0x39C0 / +0x3A7F                      | Johto: seen 2 / own 1, and a wild SENTRET's bit appeared after the battle      |
| Lazarus v2.0                    | Ilios (430) + National (sparse, to 1028)                    | SaveBlock1+0x32A8 / +0x3329                      | ⚠️ no POKéDEX in the save's menu: matched to the save (FENNEKIN seen + caught) |
| SoulGold v1.1.4, v1.2, v1.2.1   | Johto (702, the national numbers with a species) + National (to 1025) | SaveBlock1+0x31F8 / +0x3279 | Seen 6 / Own 1 (CYNDAQUIL), matched to the game's own POKéDEX (opened via FLAG_SYS_POKEDEX_GET 0x98D headlessly), plus poked bits |
| Emerald Seaglass v3.0           | Hoenn (212) + National (430, TREECKO = No.1)                | SaveBlock1+0x28FC / +0x2932                      | Hoenn: seen 2 / own 1; poking bits gave 3 / 0                                  |
| Too Many Types 2 v1.5.2         | Hoenn (398) + National (1052, CHIMCHAR = No.397)            | SaveBlock1+0x289C / +0x2920                      | Hoenn: seen 1 / own 1; poking No.913 showed SPRIGATITO as Hoenn #001           |
| Recharged Yellow (all four)     | Kanto (151) + National                                      | SaveBlock2+0x18 (FireRed's struct Pokedex), no copies | seen 58 / owned 31 (the user's save, every release) |
| PureRed / PureBlue v2.7.6       | one list of 151                                             | wPokedexOwned / wPokedexSeen (WRAM bank 1), 19 bytes, bit n-1 = No. n | SEEN 106 / OWN 10, the converted Red save's (the game's own continue screen says 10) |
| Crystal (all three)             | one list of 251, national order (the game's New / Old modes order the same flags) | wPokedexCaught / wPokedexSeen (WRAM bank 1), 32 bytes, bit n-1 = No. n | SEEN 2 / OWN 2, the game's own POKéDEX (its flag poked on to open it) |
| Yellow Legacy v1.0.10           | one list of 151                                             | wPokedexOwned / wPokedexSeen, Yellow's places | Yellow's save's |
| Crystal Legacy v1.3.1           | one list of 251, national order                            | wPokedexCaught DEA4, wPokedexSeen DEC4 (WRAM bank 1) | Crystal's save's |
| Crystal Clear (all four)        | one list of 251, national order                            | caught DA0E, seen DEB9 (WRAM bank 1), 32 bytes each | 2 caught / 3 seen on the headless save (MOM's HYPNO seen) |
| Super Mariomon (all five)       | WORLD (152 in v1.5 / 151, PIRANHA P. = No.1) + National (155 / 153) | SaveBlock1+0x3790 / +0x37A4, 20 bytes each | TATTLEDEX SEEN 2 / OWN 1 (PIRANHA P., BOB-OMB); every bit poked on: SEEN 152 / 151, the list ending at TIPTRON / SONIC |
| Recharged Emerald (all three)   | Hoenn (202) + National                                      | SaveBlock2+0x18 (Rebalanced +0x1C), no copies | seen 108 / owned 28 (retail Emerald's save, converted) |
| Elite Redux (both builds)       | National only (1222 numbers; LUCINEON No. 1072)               | SaveBlock1+0x2E54 / +0x2F10, 188 bytes, by dex number | caught 29 = its title screen's POKéDEX 29 (seen 49) |
| Wishes of Tomorrow 1.2.6        | Hoenn (expansion's 214, u32 entries at 0x08CC18B0) + National (1025) | SaveBlock1+0x359C / +0x361D, 129 bytes, by dex number | SEEN 1 / OWN 1 in its regional list (PIKACHU, No. 164; the party's other four aren't in it) |
| Dreamstone Mysteries            | Cormoria (315, GOTHITA = No.1; titled HOENN in the game) + National | SaveBlock1+0x3494 / +0x3515, 129 bytes | SEEN 2 / OWN 2 (CHIKORITA No.054, OSHAWOTT No.224 of the five given), No.054's page "Leaf" 2'11" 14.1 lbs. |
| Monomyth 1.0, 1.1               | Athanto (362, its only dex; 0x28-byte entries at 0x089D3798 (1.1: 0x089D3BB0), 14-byte categories, metric) | SaveBlock1+0x37F4 / +0x3827, 51 bytes, by dex number | SEEN 22 / OWN 11; No.007 MISHIMIZU "Rookie" 0.5 m 9.1 kg, No.033 VENIPEDE "Centipede" 0.4 m 5.3 kg |

Emerald Rogue and R.O.W.E. have none: Rogue's dex (it has one: seen 2 /
caught 1 in the user's save) isn't a seen / caught bit array anywhere in RAM,
and R.O.W.E.'s party isn't decoded yet.

**How the hacks' dex data was found** (no source for any of them): the
tables in each ROM by content — BULBASAUR's base stats and SEED entry,
species 1's `{ptr, size, tag}` pic records, a 1, 2, 3 ... species -> dex run,
STENCH then DRIZZLE — keeping the copy the code references when a stale one is
left behind, then decoded and rendered. Hacks that number their dex their own
way (Amethyst, Celia, Seaglass, TMT2) were read off the game's own list in RAM
(EWRAM `{label, flags << 16 | species}` items on the FireRed engine,
`{u16 dexNum, seen:1, owned:1}` on the expansion one) and the matching table
found in the ROM. The flags came from headless captures of the user's saves
(the starter / rival bits), then each game's own POKéDEX confirmed them: a
save without a POKéDEX got FLAG_SYS_POKEDEX_GET (FireRed engine: poked into
SaveBlock1's flags; expansion: the game's own `FlagSet` called with
`mgba_dump`'s `call`), and `poke8p PTR OFF VAL` (a save-block-relative poke —
Emerald moves its save blocks on every load) set or cleared bits while
watching the counts. A regional dex whose order wasn't obvious (Heart and
Soul's Johto, a u32 list) was read by marking every species seen and dumping
the full list the game builds.

pokeemerald-expansion hacks (Heart and Soul, Lazarus, Seaglass, TMT2) keep the
whole dex page in each `gSpeciesInfo` entry (`SpeciesInfoDex`: category,
natDexNum, height/weight, description, front pic — smol-compressed on Heart
and Soul — palette, footprint), three u16 abilities (the third hidden) and
inline 17-byte ability names 0x1C apart; their flags are SaveBlock1
`dexSeen` / `dexCaught`. Radical Red, Amethyst and Gaia use CFRU's type ids
(Fairy = 23) and Odyssey turned vanilla's `???` (9) into Fairy, so their type
names follow; Seaglass uses the standard expansion ids.

Unbound is the odd one: CFRU keeps its flags at SaveBlock1+0x310 (seen) and
+0x38D (owned), 1000 bits each with no anti-cheat copies — found in a headless
capture and confirmed by poking a bit and watching the game's counts rise —
and the Borrius order (498 species ids at `0x09A3F398`) was read off the
game's own list in RAM. The owned array starting at an odd address exposed a
JNI bug (fixed): `pk_read_range` did unaligned `busRead32`s, which rotate like
the CPU's `LDR`, so the device showed GIBLE's bit as GALLADE's. Retail configs
are gated by `pokedexMatchesRom` (entry 1 must be SEED, 7 dm, 69 hg), since an
unknown hack of the same size falls through to them. The hacks' configs are
SHA1-pinned like the rest of their support. Tests: `HackPokedexTest` (each
`<game>_dex` fixture's flags vs what the game showed, and every listed entry
decoding from the user's ROM) and Paparazzi shots of each hack's list / entry.

### Item descriptions (ITEMS tab)

The games' item descriptions aren't bundled (they're the games' own prose; item
names still are): `itemDescription(id)` reads them from the player's ROM through
`RomItemText` (`companion/data/RomItemText.kt`), the way the dex reads its
flavour text, and caches each per ROM. Where a game keeps them is
`NativeConfig.itemDescs`, an `ItemDescTable` (base, stride, the description
pointer's offset, optionally an id range and a second table):

- vanilla `struct Item` (44 bytes, description at +0x14; `vanillaItems`):
  retail FireRed / LeafGreen / Emerald / Ruby / Sapphire (R/S capped at their
  349 items), the binary hacks (Glazed in place; Unbound, Radical Red, Amethyst
  and Celia repointed - the word FireRed 1.0's literal pools load gItems from,
  Celia's found by shape), Gaia / Odyssey at retail's address;
- Japanese releases: 40-byte records, description at +0x10 (`japaneseItems`);
- expansion hacks (`gItemsInfo`): the generators' name anchor with the
  description pointer 8 bytes before it (HnS / SoulGold 0x2C, Lazarus /
  Imperium 0x50, Seaglass / TMT2 0x54); Emerald Rogue (gItems 0x28 + its own
  gRogueItems from 827), Quetzal (0x1C, +0xC), R.O.W.E. (0x38, +0x1C), Super Mariomon
  (0x28 records, description at +0x14, no itemId; 824 items in v1.5, 821 before).

`scripts/port_retail.py` writes each port's `itemDescs` (its detected item
table); the other generators print theirs. A config whose vanilla table
doesn't check out (`matchesRom`) falls back to a scan by shape
(`RomItemText.findVanillaItems`: item ids 1, 2, 3... in consecutive records),
once per ROM. Yellow keeps our own hand-written
text (Gen 1 items have none); Crystal's and Crystal Clear's are read from the cart (`Gen2Dex.itemDescription`). Text decodes with `Gen3Text` (Japanese for
`romLanguage` 'J', each language's POKéBLOCK glyphs, placeholders dropped),
line breaks collapsed to spaces.

Checked against the bundled tables they replaced, every id, every ROM on the
dev machine (2026-10-09): identical for Emerald, the five other-language
Emeralds, all 31 other-language FireRed / LeafGreen / Ruby / Sapphire ports, Heart
and Soul, Lazarus, SoulGold, Rogue, Seaglass, Glazed, Imperium, Quetzal and
R.O.W.E. The rest changed for the better: FireRed / LeafGreen TMs show
the move's text the game shows (the decomp's `description_english` for TMs is
unused); Ruby / Sapphire their own wording (they borrowed Emerald's); Unbound,
Gaia, Odyssey and Amethyst their own (they borrowed vanilla FireRed's); Radical
Red's item 255 is the slot the game reads (the old table keyed a stale
`.itemId`); SoulGold v1.2's TM75 needs no special case; Celia and TMT2 have
descriptions now. `RomItemTextTest` pins a few per game (skipped without the ROM).

### GUIDE tab

Hint-first pages per game (title, tap = hint, tap = answer): TIPS / WHERE IS /
STUCK? where written by hand, plus HERE (the current area's wild Pokémon,
people and items), NEXT BOSS and EVOLUTIONS read live from ROM + save. Every
fact comes from the game's own data, in our own words; all but FireRed are
`verified = false` until checked in play.

| ROM                               | Text                                                             | Area data (HERE / WHERE IS)                                          | NEXT BOSS                                                                                                                                                                     |
| --------------------------------- | ---------------------------------------------------------------- | -------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FireRed rev 1 / LeafGreen rev 0/1 | hand-written (`GuideFireRed.kt`; LeafGreen's Game Corner prizes) | pokefirered map scripts                                              | 8 gyms, Elite Four, champion                                                                                                                                                  |
| Emerald                           | hand-written (`GuideEmerald.kt`)                                 | pokeemerald map scripts                                              | 8 gyms, Elite Four, champion                                                                                                                                                  |
| Ruby / Sapphire rev 1/2           | hand-written per version (`GuideRubySapphire.kt`)                | pokeruby map scripts                                                 | 8 gyms, Elite Four, STEVEN                                                                                                                                                    |
| Heart and Soul v2.0.6             | short TIPS; WHERE IS generated                                   | pokehns-expansion `_hns` map scripts                                 | 16 gyms, Elite Four, LANCE, RED; CHUCK / JASMINE / PRYCE's team by how many of the three you've beaten                                                                        |
| Unbound v2.1.1.1                  | short TIPS; WHERE IS generated                                   | read from the ROM's maps + script bytecode                           | 8 gyms, each with its VANILLA / DIFFICULT / EXPERT / INSANE team                                                                                                              |
| Radical Red v4.1                  | short TIPS; WHERE IS generated                                   | read from the ROM's maps + script bytecode                           | gyms 1-7 (the VIRIDIAN leader's party is built at run time; the Elite Four pick between two teams by a var no script sets)                                                    |
| Odyssey v4.1.1                    | short TIPS; WHERE IS generated                                   | read from the ROM                                                    | the 5 fights whose script hands over a badge, NORMAL + HARD teams (flag 0x1512, its HARD MODE); the other 3 badges end longer story events                                    |
| Gaia v3.2                         | WHERE IS generated                                               | read from the ROM                                                    | 8 gyms (FireRed's slots 414-421), Elite Four (own flags 0x123-0x126), champion HERSCHEL                                                                                       |
| Amethyst v1.3.0, v1.4.1           | short TIPS; WHERE IS generated                                   | read from the ROM                                                    | gyms 1-7, each in its 4 tables (STANDARD / HARD / DIVERGENT / both - flags 0x93C, 0x945); RAINE and CHANCE by badges held; gym 8 and the League load their trainer from a var |
| Celia's Stupid Romhack v1.1.4     | one TIP; WHERE IS generated                                      | read from the ROM (its newer-decomp script commands stop some paths) | 8 gyms; BLAINE is 8 one-Pokémon battles                                                                                                                                       |
| Orange Islands                    | one TIP (the CRYSTL type); WHERE IS generated                    | read from the ROM                                                    | CISSY, DANNY, RUDY, LUANA (trainers 1-4, badge flags 0x820 / 822 / 824 / 826), DRAKE (no flag of his own: his trainer flag 0x505) |
| Ultra Violet v1.22                | one TIP; WHERE IS generated                                      | read from the ROM (its own gMapGroups)                               | FireRed's (its gTrainers, gym scripts and League are FireRed's) |
| FireRed Rocket Edition            | two TIPS (this guide, FAIRY); WHERE IS generated                 | read from the ROM                                                    | the 8 badge scripts: FireRed's leaders 414-420, ARIANA (50) at Viridian; no League |
| Ash Gray v4.5.3, Fork Bug Fix 1.0 | two TIPS (this guide, ELECTRIC on GROUND); WHERE IS generated    | read from the ROM (its own gMapGroups)                               | the 6 badges won in battle (BROCK, LT. SURGE, KOGA, SABRINA, BLAINE, TEAM ROCKET 350); CASCADE / RAINBOW are handed over |
| Ghost Grey 1.0.1, 1.0.2, 1.0.4    | three TIPS (this guide, GHOST 1.2x, FAIRY); WHERE IS generated   | read from the ROM (v1.0.4's maps; its own gWildMonHeaders)           | its 7 badges (flags 0x821..0x827): ASTON 414, MAJ.VOLTAGE 415, ERIKA 417, the EMPEROR OF SAND 416, ONIBABA 418, CAP'N STEVE 420, SUMMER 435-437 by the starter; no League |
| Let's Go Pikachu / Eevee          | two TIPS (this guide, FAIRY); WHERE IS generated                 | read from the ROM (Eevee's maps; each version's own gWildMonHeaders) | FireRed's (its badge scripts, trainers and League are FireRed's), with its own teams |
| GS Chronicles v2.7.6              | two TIPS; WHERE IS generated                                     | read from the ROM (`--items` by slot: its gItems' .itemId isn't kept up) | the 8 Johto badge scripts' leaders (FALKNER 321 ... CLAIR 154; Mahogany's is LORELEI 153); no Kanto gyms or League (its own flags, not traced) |
| Sword and Shield Ultimate Plus    | two TIPS; WHERE IS generated                                     | read from the ROM                                                    | the 8 badge scripts' leaders (MILO 414 ... RAIHAN 350), BEA / ALLISTER and GORDIE / MELONY both listed; gyms named by badge (their maps' sections are FireRed's leftovers); no League (FireRed's ELITE FOUR records) |
| Recharged Yellow (all four)       | two TIPS (LINKING CORD, HARD teams); WHERE IS generated          | read from each release's ROM (its own script command sizes, `--ops`) | 8 gyms with NORMAL + HARD teams (badge flags 0x880..), Elite Four and the rival (rematch teams after game clear, 0x864) |
| Lazarus v2.0                      | —                                                                | HERE's wild Pokémon only                                             | 8 Muses (NORMAL / HARD by flag 0x8E6), the RUINS OF AHIYAWA's four, ANYALIOS |
| Seaglass v3.0                     | —                                                                | HERE's wild Pokémon only                                             | Emerald's 8 gyms, Elite Four, WALLACE (its own teams) |
| Too Many Types 2 v1.5.2           | —                                                                | HERE's wild Pokémon only (11 land slots: the last 1% reads past them) | Hoenn's gyms in its reversed order, Elite Four (rival by starter), STEVEN |
| SoulGold v1.1.4, v1.2, v1.2.1     | —                                                                | HERE's wild Pokémon only                                             | 16 gyms (NORMAL + HARD), CHUCK / JASMINE / PRYCE by how many of the three are beaten, Elite Four, LANCE, GOLD / CRYSTAL |
| Glazed v9.2.0                     | —                                                                | HERE's wild Pokémon only                                             | Tunod's 8 gyms |
| Super Mariomon (all five)         | —                                                                | HERE's wild Pokémon only (WORLD 1-1's GOOMBA met walking its grass headlessly) | the eight STARS' leaders (FLAG_BADGE01_GET.. 0x867-0x86E): PEACH, DAISY, WARIO, WALUIGI, DK, PAULINE, ROSALINA, BOWSER (default teams; its League isn't listed) |
| Recharged Emerald (all three)     | two TIPS (LINKING CORD, HARD teams)                              | HERE's wild Pokémon only           | Emerald's 8 gyms, Elite Four and WALLACE, each with NORMAL + HARD teams (game clear moved to 0x863) |
| Elite Redux (both builds)         | two TIPS (this guide, why no NEXT BOSS)                          | HERE's wild Pokémon only (28-byte headers: a hidden and a honey set besides vanilla's four) | none: each leader has several teams, levelled to the player's level cap (TrainerMon level 0) and picked at run time |
| Team Rocket Dragonsden 1.5        | two TIPS (this guide, why no NEXT BOSS)                          | HERE's wild Pokémon only (vanilla 20-byte headers, 443 of them)      | none yet: its story isn't the badge order (gTrainers is vanilla-shaped, BROCK 415, 24-byte TrainerMons) |
| Wishes of Tomorrow 1.2.6          | three TIPS (this guide, the randomizer, why no NEXT BOSS)        | HERE's wild Pokémon only (84-byte headers, four time-of-day sets, only the first filled), swapped like the game when the save's randomizer is on | none yet: its story isn't a badge order (gTrainers[3][896] of 0x38-byte records, EASY empty; GENERAL EDWARDS 885) |
| Dreamstone Mysteries              | one TIP (this guide)                                             | HERE's wild Pokémon only (24-byte headers, 165 of them)              | the eight badge holders, by the badge flag (0x867 + n) their scripts set after the battle, at their maps' sections; then VICTORY CAPE's four CONTENDERS and KOHLA by trainer flags (0x500 + id); teams follow the difficulty var 0x40A8 (0 reads the first of gTrainers' three tables) |
| Monomyth 1.0, 1.1                 | one TIP (this guide)                                             | HERE's wild Pokémon only (vanilla 20-byte headers, 211 of them)      | the eight leaders by the badge flag their scripts set (0x887 + n; HEM and LOCKE together for the eighth), then the Elite Four and CHAMPION STENVARD by trainer flags (0x500 + id); Heroic mode (flag 0x8A3) fights trainers 551-564 instead |
| Imperium v1.3.1                   | —                                                                | HERE's wild Pokémon only                                             | 8 gyms, Elite Four, champion |
| Quetzal Alpha 9                   | —                                                                | HERE's wild Pokémon only                                             | per region (by the map group you're in), NORMAL + HARD |
| Quetzal Spanish Alpha 9           | —                                                                | HERE's wild Pokémon only                                             | as English (its trainers' Spanish names: 265 is PETRA) |
| Quetzal Alpha 9 v1 (EN / ES)      | —                                                                | HERE's wild Pokémon only                                             | as Alpha 9 v0 (its tables at v1's addresses)                           |
| Quetzal Alpha 0.8.4 (+ Moemon)    | —                                                                | HERE's wild Pokémon only                                             | Hoenn's 8 gyms, Elite Four and champion, NORMAL + HARD (Alpha 9's ids) |
| Polished Crystal 3.2.x (16 builds) | seven TIPS; WHERE IS generated                                 | each build's map events + the source's map scripts (`gen_polished_crystal_guide.py`) | Johto's 8 gyms, Elite Four, LANCE, Kanto's 8 gyms, the 16-badge League rematch (party 2), RED |

Generated WHERE IS (`generatedWhereIs`): every HM, key item and gift Pokémon
someone hands out, with its area as the hint. The hacks without source get their
area data from `scripts/gen_guide_areas_rom.py`, which walks a FireRed-engine
ROM's map headers and event scripts (command sizes from pokefirered's
`event.inc`) for item balls, hidden items, `giveitem`/`finditem`/`additem`,
`givemon`/`giveegg` and badge-setting `trainerbattle`s (`--bosses`); checked
against the decomp-generated FireRed data with `--compare` (items and hidden
items exact). Unbound XORs its map-group pointers (`--groups 08b70498
--groups-xor 00b749de`); a script handing out more than 5 different things
(Radical Red's shop / menu scripts) is dropped as noise. An object using the item-ball sprite counts its
gift as an item lying there (Odyssey's balls run `giveitem`); empty map slots
are skipped, not the end of a group (Amethyst, which needs `--groups 083526a8`: vanilla's gMapGroups, missed by the shape scan); `gItems` is found by shape when
item names were changed (Celia). Hack tables (`gMapGroups`, `gWildMonHeaders`,
`gLevelUpLearnsets`, `gTrainers`) were found from the literal pools of
FireRed's own code that uses them (`--groups` when the shape scan misses).
Amethyst's trainer loader picks one of four `gTrainers` (`GuideTables.altTrainers`,
an id or'd with `(k + 1) << 16`); CFRU's 3-byte learnsets (`learnsetCfru`) give
Unbound / Radical Red / Amethyst teams without custom moves their moves. Unbound's
difficulty teams were read by running its own leader-picking routine headlessly
(`mgba_dump call`) for every gym x difficulty. Tests: `*GuideTest`, Paparazzi
`*Guide*` shots.

### How a new ROM gets supported

When there's no source (most of the ROM hacks above), addresses are found by
forcing a live memory dump off the device and brute-force-scanning it, not by
guessing:

1. `PokeDaisyActivity`'s `EXTRA_DUMP_FIXTURE`/`EXTRA_DUMP_FIXTURE_FORCE` intent
   extras dump the full EWRAM (256 KB) + IWRAM (32 KB) to a device file —
   `scripts/capture_fixture.sh <key>` drives this (live session if the app's
   already running via `onNewIntent`, cold relaunch otherwise) and pulls the
   result into `app/src/test/resources/fixtures/<key>/{ewram,iwram}.bin`.
2. `gPlayerParty` is found by scanning every 4-byte-aligned offset for a valid
   Gen3 `struct Pokemon` (`PID^OTID` key, `PID%24` substruct order, 16-bit
   checksum over the 12 decrypted words) — a checksum match is ~1/65536 odds,
   so it's a strong signal, **but not proof**: verify any hit against another
   independently-captured session before trusting it (a single capture can't
   tell a real global from a lucky transient-memory coincidence). Don't stop
   at two, either, and don't let a _structurally_ plausible match (e.g. an
   IWRAM pointer landing at exactly vanilla's `SaveBlock1+0x238` playerParty
   offset) substitute for it: Lazarus's first false lead was caught by a 2nd
   capture, but Emerald Rogue's false lead survived two back-to-back captures
   _and_ had a matching pointer, and only broke on a 3rd capture taken later
   after more real play time — turned out to be a relocatable scratch buffer
   that happened to coincidentally hold valid data, twice in a row, not a
   stable global. Cross-session address stability, checked against as many
   independent captures as it takes, is the only signal that actually proves
   it — everything else is corroborating at best. (Both incidents are
   preserved in `NATIVE_LAZARUS`/`NATIVE_EMERALD_ROGUE`'s comments.)
3. Other fields (bag pockets, save-block pointers, encryption key) are found
   the same way — searching memory for a pointer that resolves to an
   already-confirmed address, or for a value that matches an
   already-confirmed encryption key — rather than assuming vanilla's struct
   offsets hold (they often don't, even when the checksum/party layout is
   otherwise vanilla-compatible).
4. ROM-embedded data (icon graphics tables, item/species name tables) is a
   _different_ hunt — it's static ROM content, not RAM. Name tables are the
   tractable end of this: they're plain Gen 3-encoded text, findable by
   anchoring on 1-2 known names at known indices (e.g. "Bulbasaur"@1,
   "Ivysaur"@2) and walking the fixed-stride array from there — see
   the private ROM-hack project's `tools/telemetry-viewer/scripts/gen_unbound_data.py`'s
   `find_fixed_name_table`/`walk_name_table`/`gen_items`, reused as-is for
   Amethyst's species/item tables (`SpeciesNamesAmethyst.kt`/
   `ItemNamesAmethyst.kt`). Don't assume one anchor covers the whole range,
   though — Amethyst's species table turned out to be split: the vanilla
   1-251 block was findable from "Bulbasaur", but that walk silently ran into
   an _unrelated_ adjacent table (move names, which also happen to satisfy
   the Gen 3 text charset) past index 251, while the real, complete table
   (covering 1-1267, including the new species) lived at a totally different
   ROM address — only found by anchoring on a species confirmed from a live
   read instead ("Tepig" at the user's actual in-game species id). Icon
   graphics tables are the hard end — no text anchor to search for, so it
   needs either real source, a still-byte-identical helper function whose
   literal pool holds the relocated address (Gaia's icon tables were found
   this way), or a structural pattern search with something to validate a
   candidate against (Lazarus's and Emerald Rogue's icon table searches both
   came up empty for lack of either). The literal-pool technique isn't
   limited to whatever revision you happen to have addresses for already —
   Amethyst's `GetItemIconGfxPtr` needed the _rev 0_ address specifically
   (rev 1's didn't decode to anything real when tried directly), which
   wasn't on file, so it was obtained by building the pinned pokefirered
   commit **completely unpatched** in a pokefirered build
   Docker image (stash/move aside any patch files first) and reading
   `nm`'s symbol table — confirmed byte-identical to real retail rev 0 via
   sha1 first, so the address is exact, not guessed. However a candidate
   address is obtained, decode something through it end-to-end and look at
   the result (render tiles+palette to a PNG) rather than trusting that
   "looks like a valid ROM pointer" alone — Amethyst's mon/item icons were
   confirmed by actually seeing a recognizable Tepig and Potion bottle.
5. Once addresses are confirmed, add a fixture (step 1) + a
   `<Game>DecodeTest.kt` under `app/src/test/kotlin/.../companion/data/`
   asserting the known real values from that capture — this is what keeps a
   future refactor from silently re-breaking a hard-won address. `forkEvery = 1`
   in `build.gradle.kts` restarts the test JVM per class, needed because the
   bag-read throttle in `NativeReader.kt` is file-level mutable state shared
   process-wide.

**With an agent**: `make rom-support ROM=<rom> [SAVE=<save>]`, or `LIST=<file>` for several games
(`scripts/rom_agent/`, its README), runs all of the above unattended with Claude Code in a worktree off `dev` - the
brief is `scripts/rom_agent/prompt.md` - one game at a time, checks each change itself (game data, translations,
`make test-quick`, the manual, no ROM / save / home path in it) and commits the ones that pass, a commit each, then
opens one PR to `dev` (a game that still fails is left out as a patch; a draft PR when none passed). Review it like any other: the agent is told to leave out anything it
couldn't prove, and its PR lists what it checked and how.

**pokeemerald-expansion hacks** (a "PRET x RHH" boot splash) can be done with
no device at all: `scripts/capture_fixture_headless.sh <key> --script
native-capture/boot/rhh_splash.txt`, plus extra hand-run captures with a varied
first `wait` to prove each address stays put. Then add a `GAMES` entry to
`scripts/gen_expansion_tables.py` for the ROM-side tables (species names,
types and gender ratios, items, moves, the type chart, mapsec names). Things
that bit Heart and Soul:

- A checksum scan that filters on "plausible species" finds nothing when
  BoxPokemon packs `species:11|teraType:5`. Mask before filtering, or search
  for the nickname text instead.
- Building the hack's source tag isn't the same as the release. Heart and
  Soul's from-source addresses were 4 bytes off.
- `struct BattlePokemon` and the SaveBlock1 layout change between expansion
  versions (`NativeConfig.battleMonLayout` / `saveBlock1PosOff`). A battle
  capture is the only way to check them. Wild encounters depend on the RTC,
  so pace in grass and dump every step.
- Item icon tiles may be smol-compressed (header low nibble 1-6), not LZ77.
  `Smol.kt` handles both.

### Pokémon Wishes of Tomorrow: an expansion 1.15.3 build, and its randomizer

No source and no save on file, so: a new game played headless (randomizer off, then a second one with every option on),
the party / bag / Pokédex flags given through the game's own script commands (`callnative ScrCmd_createmon` = 0x0A200DD5,
copied from its own `givemon`s; FLAG_SYS_POKEMON_GET is 0x880 there, 896 trainer flags), then saved in game. Its RAM was
found by building pokeemerald-expansion's `expansion/1.15.3` tag (plain `make`, Debian's arm-none-eabi-gcc 12) and
matching each symbol's literal-pool reference count near its address (the hack's globals sit a constant few bytes from the
reference's: +0x58 around gPlayerParty, +0x64 in IWRAM, +0xAC around the wild encounter globals), each candidate then
read in a dump. Struct sizes from `offsetof` compiled against the tag's headers (BattlePokemon 0x88, TrainerMon 0x24);
the hack's own struct Trainer is 0x38 bytes (a 13-letter name), found from its records.

The **randomizer** (a new game's RANDOMIZER screen; flags 0x4A0 on, 0x4A2 starters, 0x4A3 wild, 0x4A4 trainers) leaves
the ROM's tables alone and swaps each species as the game makes the Pokémon, with one function (0x0823ECF4) seeded by
SaveBlock2's trainer ID: a murmur-style hash of species and ID picks a National Dex number, then its base species, up to
64 tries for one the build kept. `WishesRandomizer` ports it; the game's own function, called headless for 15 species on
the randomizer save, returned the same, and CreateWildMon there made a MEGANIUM of a ZIGZAGOON as predicted. The GUIDE's
wild lists (HERE, the HUD's WILD, the DEX's AREA) go through `GuideRandomizer`, set from the save's flags and trainer ID
(`GuideTables.trainerIdVar`). Trainers are swapped too (but shadow Pokémon, TrainerMon +0x1E bit 7, and battles with
trainers 659 / 660), which matters once it has a NEXT BOSS.

Its **TOWN MAP** (the Town Map key item: "took a closer look at the JOKAIM region map") is Emerald's kind of screen - BG2 an
8bpp affine layer, a 64x64 one-byte tilemap, the palette's 48 colours at BG palette 7 - with smol art. Its map code keeps a
table of six regions (0x08CDC868: seven words each, two tilemap / tiles / palette triples and the palette size; JOKAIM is the
first row's second triple: tilemap 0x08CE5880, tiles 0x08CE59A8, palette 0x08CE77C8, which matched VRAM and palette RAM
headless) and a u8 [15][28] cursor grid per region from 0x08CE24CC; the others are FireRed-style Kanto / Sevii maps and one
more, but the game shows JOKAIM wherever the player is (MUNEN VILLAGE, and RYUDEN ISLAND, which isn't on JOKAIM's grid:
its head goes to its entry's rect). So only JOKAIM is rebuilt (RomArt's `WSH_REGION_*` -> `regionmap/wishes.png`), with
its grid (0x08CE2D00, MAPSEC_NONE 0xE5) through `gen_expansion_tables.py wishes` (`place_min` 0xD1: Emerald's / Kanto's
leftover ids stay off the map, but SENNEN 0x66 / TORII ROUTE 0x67, Kanto ids it renamed, are on its grid). The rects sit at
the entry + (1, 2) tiles, where the game draws the player's head; moving its cursor names what our grid names. The head is
Emerald's own Brendan / May (the ROM keeps both; a female save shows May's), so the app's `player_brendan` /
`player_may` are the game's.

## Randomized ROMs (Universal Pokémon Randomizer ZX)

English FireRed rev 0 / rev 1 and Emerald run through [Universal Pokémon Randomizer ZX](https://github.com/Ajarmar/universal-pokemon-randomizer-zx)
4.6.1, with any settings and seed, read as retail with the randomizer's data taken from the ROM (`RandomizedRetail.kt`).
Other randomizers, other base games and other languages stay NOT SUPPORTED / BEST EFFORT.

### Recognising one: a masked hash

A seed changes thousands of bytes, but every byte UPR may write sits at a fixed offset of the retail layout: its tables,
the item balls / hidden items / NPC text pointers it finds by walking the maps, its code patches (all in retail's places,
never moved), and what grows goes into retail's 0xFF free space (FireRed from 0x800000, Emerald from 0xE40000, past the data
end `RetailDumps` already stops at). So, like `RetailDumps` (and on top of its ranges), bytes [0, end) with
- the species name table upper-cased (`fold`: UPR rewrites it on every run, lower case with its LOWER_CASE_POKEMON_NAMES
  tweak; folded it's retail's byte for byte, so the names, which the app bundles, are still checked), then
- every site UPR may write zeroed (`mask`)

hash to `sha1`. `scripts/gen_randomized_retail.py` writes `RandomizedRetailGen.kt` (offsets and hashes only) from the three
retail ROMs and a UPR checkout (`UPR_SRC`, read for facts only: UPR is GPLv3, none of its code is here): `gen3_offsets.ini`'s
offsets (tables, statics, roamers, starters, trades, shops, misc tweak sites), its IPS patches' records, and its scans
redone on the retail ROM (the wild header table, map events: item balls are object events with the item-ball picture whose
script starts `setorcopyvar 0x8000, item; setorcopyvar 0x8001, n; callstd`, hidden items bg events of kind 5-7; trainer
parties and learnsets as one run each; TM / tutor text pointers and their copies within 500 bytes). Strided fields stay
strided ("start:len:count:stride"): base stats bytes 0-8, 12-16, 19, 22-23 of each 28, move data 0-4 of each 12, trainers 0,
4-15, 24, 32, 36-39 of each 40, item prices, TM item palettes / descriptions, the trade table's fields. About 86 KB is masked
outside free space per game, ~800 descriptors. Not masked (so still checked): code outside the patches' own bytes, names of
moves / items / abilities, item data but prices, maps, scripts, graphics, the save code. UPR never touches the header,
key items, HMs or NPC item gifts.

**Validated** with `--check`: 102 UPR outputs (each game: 6 seeds with everything on, 2 with forced double battles, 2
without the misc tweaks, one with nothing on, one per misc tweak alone) all hash right and no byte changed outside the mask;
every 16 MB BPRE / BPEE hack on this machine (18: Kaizo, Legacy, Rocket Edition, Ash Gray, Orange Islands, the Recharged
games, the QoL builds, ...) is rejected (closest: Kaizo, 23,839 bytes outside). Retail itself also hashes right, which is
why the Poller and `CompanionSupport` try retail's SHA-1 and `RetailDumps` first. `RandomizedRetailTest` checks it on the
retail ROMs (a byte changed inside the mask matches, a lower-cased name matches, a code byte / a respelled name / a move
name doesn't).

**A new UPR version**: clone it at its tag and rerun the generator with `UPR_SRC` pointing there (UPR_VERSION at its top);
then make fresh outputs and `--check` them. UPR's CLI takes a settings file: `java -jar PokeRandoZX.jar cli -s <file.rnqs>
-i <rom> -o <out> -l`. The settings files were made with a few lines of Java against the jar (`com.dabomstew.pkrandom.
Settings`: set every mod, `setSelectedEXPCurve`, `setRomName`, `setCurrentRestrictions`, `setCurrentMiscTweaks`, then
`write(FileOutputStream)`) - kept outside the repo. Mind the `set...Mod(boolean...)` setters: the true one picks the enum
value by position, so `setTrainersMod(true, false, ...)` is UNCHANGED.

### Reading one

`TelemetrySampler.detect()` takes a match as retail (`retailHash`; retail's config; `ramVerified`, so the one-press picks;
retail's shiny check, which UPR doesn't touch) and reads `RandomizedRetail.read` into `randomizedRetail` (ActiveTables), a
`HackText` laid over retail's like the hacks' own (`runningRandomized`, kept apart from `fireRedHack` / `emeraldHack`: it's
retail's look and party art). What the app bundles for FireRed / Emerald and the randomizer changes, and where it comes from
now:

| Bundled table | Used by | On a randomized ROM |
|---|---|---|
| species types (`SpeciesTypes.kt`) | party / foe matchups, SUGGESTIONS, FOE TEAM, move advice, NEXT BOSS hints | gBaseStats +6/+7 |
| move type and power (`MoveData.kt`) | battle verdicts, SUGGESTIONS, move advice, DEX MOVES | gBattleMoves +1/+2 (retail's names) |
| move effect / accuracy / PP / chance / priority (`MoveStatsGen.kt`) | move advice, LEARN's PWR / ACC / PP | gBattleMoves, effect names by id (`vanillaMoveEffectNames`) |
| type chart (`TypeChart.kt`) | every matchup | gTypeEffectiveness (UPR's "update type effectiveness" rewrites it) |
| growth rates (`ExpTables.kt`) | the summary's EXP bar | gBaseStats +19 ("standardize EXP curves") |
| gender ratios, the experience tables, HMs, TM item ids | | unchanged by UPR |
| PC BOXES' legality tables (`SaveTablesGen.kt`) | the save editor | not offered: it needs retail's exact SHA-1 |

Already read from the ROM and so randomized for free: DEX pages (types, stats, abilities), learnsets, wild encounters, trainer
teams (NEXT BOSS, FOE TEAM), item descriptions, gBattleMons' types and ability. Two checks there assumed retail's data and
are skipped on a randomized game (`gameRandomized`; the hash vouches for the table's place instead): BULBASAUR's
evolution (`DexDetails`, `EvolutionSource`; their caches now drop on a ROM change, a randomized game shares retail's
`PokedexTables`) and the TM table's (any move on a TM, retail's HMs). `guideTablesMatchRom` skips its probe trainer's name
(`byName = false`): UPR's trainer names renamed BROCK.

### GUIDE

HERE's area data (`GuideAreas*Gen.kt`) is retail's, so `allAreaThings` lays the ROM over it (`randomizedAreas`, from
`RandomizedGuide`, read at detection): each item ball / hidden item's item by its flag (the generator maps every flag to its
site, all 339 FireRed and 274 Emerald), each trade's two species from its table row, a gift / static Pokémon's species
where only one static has that retail species (FireRed 6, Emerald 5; the others are left out). NPC item gifts and key
items stay. The hand-written pages mark what a randomizer moves (`Randomized.HIDE`: GIFT POKéMON, LEGENDARY POKéMON, the
evolution stone entry, FireRed's sleeping-Pokémon STUCK? entry) and TIPS gets a `Randomized.ONLY` section;
`gameGuide(id)` filters by `gameRandomized` (`randomized = null`: every line, for `GuideTranslationsTest`).

### EXPAND, field reads and the rest

`WideScreen.tablesCrc` hands `pkWideConfigure` / the field reads retail's CRC for a randomized cart (UPR leaves the maps,
tilesets, sprites and the RAM `pk_wide.c` reads alone; per-ROM caches - FF music, rom-art, cheats, favorites - stay on the
file's own CRC). Checked with mgba_dump's `wideon 22 <retail CRC>` (the new CRC argument) + `widecheck` + `widestats` on one
seed of each game, every option on: walking, 636 of 637 frames exact (the first after `wideon` has no snapshot, retail
the same); a scripted wild battle and a trainer battle with `layers 0x18` + `widecheck all`: exactly as many inexact frames
as retail on the same script. The field item / FLY / PC functions pass their code CRCs, WILD ENCOUNTERS' switch and REUSABLE
TMS' sites are found where retail's are (`RandomizedRetailTest`), and PLAYER LOOK is offered (`PlayerLookRunner`). The
library names it "Pokémon FireRed (Randomized)" (`GameTitles.randomizedTitle`, translated when shown), the website's ROM
check takes the same rule (`compat.js`' `randomizedRetailOf`, over `compat.json`'s `randomizedRetail`).

Test ROMs: `Pokemon FireRed Randomized (v4.6.1) (Rev 0, seed ...).gba`, `(Rev 1, seed ...)`, `Pokemon Emerald Randomized
(v4.6.1) (seed ...).gba` in the ROM folder (every option on); fixtures `firered_randomized*`, `emerald_randomized*` boot the
retail saves (UPR keeps the save format).

### Not done yet

- NEXT BOSS keeps the leaders' usual names when UPR renamed the trainers (the gym's own text still uses them).
- Gift / static Pokémon whose retail species isn't unique among the statics (two of a kind) aren't listed in HERE.
- Shops (the few UPR randomizes) aren't in the guide; the hand-written shop lines that UPR could change are hidden.
- The starters' choice isn't shown anywhere new (the game's own lab shows them).
- LeafGreen, Ruby, Sapphire, other languages and other randomizers.

## Retail engine (unknown hacks edited in place)

A hack made by editing retail FireRed or Emerald in place (a map editor, a hex editor, a patch tool) keeps retail's code
and RAM, but its hash is its own, so nothing per-ROM knows it: BEST EFFORT reads its party and names, and until now the
features that need trusted addresses stayed off. `RetailEngine.kt` proves the engine from the ROM's code instead of its
hash, address by address:

- **RAM.** Every global those features use (EXPAND's row, the battle menus' cursors, gPartyMenu, gPlayerParty) is loaded
  by retail's code through Thumb `ldr rN, [pc, #imm]`. `scripts/gen_retail_engine.py` keeps up to 16 such sites a global,
  spread through the ROM: the instruction's offset, its pool word's, and the CRC32 of the 26 bytes around the instruction
  (pool word blanked). A hack proves a global when 3 of every 4 of its sites (all of them for one or two) still match
  and their pool words still hold the address. A rebuild (pokeemerald-expansion, gcc, Recharged, Legacy, a QoL rebuild)
  moves the code, so nothing matches; an in-place hack doesn't move a global without changing every user.
- **ROM tables.** EXPAND's ROM slots the same way, but a hack may have repointed a table: where the window still matches,
  the hack's own pool word says where its table is now (the sites vote; 9 in 10 must agree). The far-off people's
  tables (graphics info, palettes, facing, palette tags, elevation) may be dropped when they can't be followed (pk_wide.c
  then doesn't draw those people); gMapGroups and the rest refuse EXPAND. Non-address settings (CFRU's 16-bit graphics
  tables, a bigger primary tileset) stay retail's: pk_wide.c's own frame check fades the strips where that's wrong.

What a proof unlocks: `Poller` keeps it (`proveRetailEngine`, for the unrecognised BPRE / BPEE ROMs, either size) and
hands out retail's cursors (`knownCursorAddrs`) and gPartyMenu (`retailEnginePartyMenu` -> `switchAddrsFor`) while BEST
EFFORT reads the hack as retail FireRed / Emerald; `WideScreen.tablesCrc` rebuilds EXPAND's row from the file and gives
it to pk_wide.c under the ROM's own CRC (`MgbaCore.pkWideAddDerived` -> `pk_wide_add_derived`, one at a time), so EXPAND
and the field reads know it. CFRU hacks may fail the RAM check (their battle code is rewritten in places: Odyssey's move
cursor keeps 11 of 16 retail sites), which is safe: they're known by hash.

Checking it: `RetailEngineTest` runs every ROM at hand: retail proves itself, the in-place hacks with hand-made rows
(Kaizo, Glazed, Ghost Grey, Ultra Violet, Rocket Edition, Ash Gray, Orange Islands, Let's Go, and the CFRU ones that
pass) rebuild their rows' address slots, the rebuilds and Unbound are refused. Headless: `scripts/gen_retail_engine.py
--row HACK` prints the row as mgba_dump's `widerow` line; then `wideon 22` + `widecheck` as in `verify_wide.py`. On a QoL
build of Emerald (no row of its own): map 440 / 440 and battle 1124 / 1124 frames exact, `fieldinfo` known, the action
cursor walked by RIGHT and a written RUN + A ending the battle. Rerun the generator after touching `pk_wide_games.h`'s
retail rows or the retail cursor / party addresses.

## Launch from a frontend (Cocoon, iiSU, ES-DE, ...)

Frontends can open a game in PokeDaisy directly, with no library screen in
between. Leaving the game (hold BACK, or the exit hotkey) returns to the frontend.
Add PokeDaisy as a custom / standalone emulator for GBA:

| Field    | Value                                                                                             |
| -------- | ------------------------------------------------------------------------------------------------- |
| Package  | `com.pokedaisy.app`                                                                              |
| Activity | `com.pokedaisy.app.LaunchActivity`                                                               |
| Action   | `android.intent.action.VIEW`                                                                      |
| Data     | the ROM (a `content://` link or a `file://` path - whatever the frontend's ROM placeholder gives) |

Instead of the data, the ROM can also come as a string extra `rom` (or `ROM`,
`path`, `file`, `uri`) holding a path or a URI. A frontend that only takes the app
(its launcher intent) plus the ROM works too: the library forwards it to
`LaunchActivity`.

What `LaunchActivity` does with the ROM:

- **A real path we can read** (`file://`, or a `content://` link from the system's
  storage provider, which is what folder grants give): the game runs from there,
  no copy. On Android 11+ that needs **All files access** (Settings > Folders >
  Change folder asks for it; a launch that can't read the path opens that
  permission page, and a `content://` link falls back to the copy below).
- **Anything else** (another app's provider): copied into the library once, under
  its own name, and reused on later launches while its bytes are unchanged.
- Saves are found by the ROM's name in the saves folder, as always - point
  Settings > Folders at the frontend's / RetroArch's saves to share them.
- No "add anyway?" prompt: an unsupported ROM plays with the companion's "not
  supported" notice.
- Launching a different game while one is open switches to it (the open one is
  suspended first, as on exit); the same game just comes back.

Step-by-step setup: [iiSU](iisu/README.md), [ES-DE](es-de/README.md). Cocoon only
lists emulators from its own platform files (inssekt/CocoonFE's `android/*.yaml`): PokeDaisy
is in `gba`, `gb`, `gbc` and `nds` (`LaunchActivity`, `game: uri`, files with `zip` / `7z`),
so a player only needs Cocoon's "Refetch platforms".

To try it without a frontend:

```bash
adb shell am start -n com.pokedaisy.app/.LaunchActivity \
  -a android.intent.action.VIEW -d "file:///storage/emulated/0/ROMs/gba/firered.gba"
adb shell am start -n com.pokedaisy.app/.LaunchActivity \
  --es rom /storage/emulated/0/ROMs/gba/firered.gba
```

## Hotkeys

Read from `files/hotkeys.properties` (written with defaults on first run), and
rebindable in Settings > HOTKEYS. Values are Android key names, comma-separated.

| Action                      | Default buttons                          |
| --------------------------- | ---------------------------------------- |
| `save_state` (current slot) | `SELECT`+`R1`, `BUTTON_Y`, keyboard `F1` |
| `load_state` (current slot) | `SELECT`+`L1`, `BUTTON_X`, keyboard `F2` |
| `undo_save` / `undo_load`   | keyboard `F3` / `F4`                     |
| `slot_next`                 | keyboard `]`                             |
| `slot_prev`                 | `BUTTON_THUMBL` (left-stick click), `[`  |
| `ff_hold`                   | `BUTTON_R2`, keyboard `SPACE`            |
| `ff_toggle`                 | keyboard `TAB`                           |
| `speed_cycle`               | `BUTTON_L2`, keyboard `=`                |
| `slowmo_hold`               | keyboard `-`                             |
| `zoom` (WIDE's zoom steps)  | `BUTTON_THUMBR` (right-stick click), `C` |
| `exit_game`                 | `SELECT`+`START`, keyboard `ESC`         |

Single-button defaults use only buttons the GBA itself doesn't need; the SELECT
chords are the exception. Hotkey buttons are not forwarded to the game.

The **right and left analog triggers** are also wired directly (many handhelds,
maybe the Thor, report L2/R2 as axes not buttons): right trigger = hold
fast-forward, left trigger = hold slow-mo. If a physical button doesn't trigger
its hotkey, press it once and check `adb logcat -s pokedaisy` for an
`unmapped key <code> (NAME)` line, then put `NAME` in `hotkeys.properties`.

## Cheats

GameShark / Action Replay (v1-v3) / CodeBreaker / VBA codes on mGBA's own cheat engine for GBA
games, Action Replay DS codes on melonDS's for DS games (below). Where they live: top-screen Settings > CHEATS (also a game's library menu > CHEATS)
adds them - typed (ADD CODE: NAME, CODE, TYPE AUTO / GAMESHARK / ACTION REPLAY / CODEBREAKER)
or imported (IMPORT FILE: RetroArch's libretro `.cht` or mGBA's `.cheats`) - and turns them on
and off; the companion's SETTINGS > CHEATS only toggles (no keyboard there). Typed codes start
ON, imported ones OFF. `Prefs.cheatsEnabled` is the master switch (off by default).

- **Storage**: `filesDir/cheats/<ROM CRC32>.cheats`, mGBA's `.cheats` format, enabled flags
  included, each cheat standing alone (`!reset`, then `!disabled` / its directive, `# NAME`,
  code lines). Keyed by CRC so a renamed or zipped ROM keeps them. Not a save: never in the
  saves folder, never backed up. Nothing is bundled (codes are community content).
- **Parsing** (`cheats/Cheats.kt`): the file formats are read in Kotlin - mGBA's parsers stop at
  a Windows line ending and drop lines they can't read without a word. Every code line still
  goes through mGBA's own code parser (`MgbaCore.pkCheatsCheck`, a scratch cheat device, no core
  needed) before it's kept; a code with a line mGBA can't read is refused with that line named,
  and IMPORT FILE reports how many it left out. The check also returns the directive mGBA settled
  on (`GSAv1`, `PARv3`...), stored with the cheat so an encrypted code loads the same way every time.
- **Loading** (`pk_cheats.c`): `EmulatorEngine` hands the core the *enabled* cheats as one
  `.cheats` text (`pkCheatsApply`) after the save backup and the resume, before the first frame,
  and again whenever a toggle changes it. Taking them out runs a disabled refresh and removes
  each set, so hooks and ROM patches are undone - with no cheat on the ROM is the file again.
- **Debuggers stay off**: GBA cheats run at frame end, or from a ROM hook (a BKPT that lands
  on the cheat device's CPU component, outside mGBA's `USE_DEBUGGERS` code). Checked headless with
  the app's own mGBA flags (`native-capture/README.md`, "Cheats").
- **The ROM still identifies the game**: a hook or ROM-patch code changes the ROM mGBA runs from,
  which the Poller hashes over the bus (hack SHA1s) and RetroAchievements hashes. `pk_cheats.c`
  keeps the original bytes under every patch: `pkReadBytes` puts them back into its reads, and
  `raLoadGame` swaps them in around the hash.
- **RetroAchievements**: while any cheat is loaded, `rc_client_idle` runs instead of
  `rc_client_do_frame` (nothing is checked or unlocked), a CHEATS ON / CHEATS OFF notice pops up
  for a game with a set, and the ACHIEVEMENTS tab shows PAUSED: CHEATS ON.
- **Savestates** don't carry cheats (`SAVESTATE_CHEATS` isn't in the flags); a state made with a
  cheat on does carry the memory it wrote, like any emulator.
- **DS games** take Action Replay DS codes on melonDS's own engine (`AREngine`, which runs the
  enabled codes when the ARM7 takes its VBlank interrupt, the way the real device hooked it). Same
  store and file; a code line is two 8-digit words, checked in Kotlin (`CheatCheck.checkDs`: mGBA's
  parser is GBA only), no TYPE / directive. `EmulatorEngine.applyCheats` turns the enabled ones into
  words (`DsCheats`) for `MgbaCore.pkNdsCheats` -> `pk_nds_set_cheats`, which replaces
  `AREngine.Cheats` between frames. melonDS states don't keep them either; STEADY's second melonDS
  never gets them. Game Boy games take none.

## PLAYER LOOK (another game's hero)

SETTINGS > MAKEOVER > PLAYER LOOK plays one game as another game's hero: Red or Leaf in Emerald, Brendan or May
(Emerald's or Ruby / Sapphire's: the same people drawn again for Emerald) in FireRed / LeafGreen, any of them in Ruby /
Sapphire, or the other hero of the same game. The pick is a grid of tiles (`PlayerLookPicker`), each hero walking in
place in their own colours (`LookArt.walk`), the game's own hero first as OFF. Nothing is bundled: the heroes' graphics come from the
player's own ROM of that game, and the running ROM is patched **in memory only**.

- **Finding the graphics** (`PlayerLook.scan`): every hero's sheets, palettes and pictures (`LookPart`) by
  fingerprint, RomArt's rolling window (`PlayerLookSigsGen.kt` from `scripts/gen_player_look_sigs.py`: pokefirered's and
  pokeruby's built files and pokeemerald's PNGs converted as its build does, each found in the retail ROMs - the pinned
  pokeemerald build isn't retail any more, so never its ELF). Every copy is kept, and so are the words pointing at them (a palette
  table's `{ptr, tag}`, a picture table's `{ptr, size, tag}`, literal pools). Every FireRed / LeafGreen / Ruby /
  Sapphire / Emerald cart in every language and revision holds the whole set (the script's report on the multi-language
  folder). Ruby / Sapphire (`LookEngine.RUBY`) have Emerald's sheets but walking and running in one (`RS_NORMAL`), their
  other sheets under Emerald's parts, a compressed back picture, and no reflection palette of the hero's (palette 10,
  one flat water colour, for both). Heart and Soul's Gold and Kris (`LookEngine.HEART_AND_SOUL`, worn only, never
  dressed up: `engineFor` never gives it) are Emerald's sheets frame for frame from pokehns' PNGs (its sparse checkout
  needs their folders added); their front picture is smol (`LookBlob.smol`, `Smol.decompressAny`), which the script
  finds by trying every target of a ROM pointer.
- **Keeping heroes** (`LookArt`): any scan leaves its whole heroes decoded in `rom-art/look/<hero>/` and their region
  map head in `regionmap/player_<hero>.png`. `PlayerLookRunner` scans the running ROM on load, then the library's other
  FireRed / LeafGreen / Ruby / Sapphire / Emerald ROMs for heroes still missing (each file once,
  `rom-art/look/scanned.txt`), builds every patch either gender could wear and lets go of the bytes. Only retail carts
  (`GameTitles.isRetail`, `RetailDumps`) and the hacks in `PlayerLook.HACKS` (Kaizo, Legacy, Imperium, Ultra Violet: each
  played dressed up) wear one; any hack's whole heroes are still kept. A ROM whose object-event palette table doesn't
  give the hero's tag gets no patch (`build` returns null): the frames alone would be drawn in the wrong colours.
- **The patch** (`PlayerLook.build`): the player's own sheets are written over in place, frame by frame. Within one
  engine a sheet is copied whole; across engines the frames are matched by the games' animation tables, always through
  Emerald's sheets (`PlayerLook.sheet`: `em()` gives any hero's frames in Emerald's sheets, FireRed's come from those): FireRed's run frames into Emerald's running sheet's order, a 16x32 frame centred in a 32x32 one
  (surfing, field move) or the middle of a 32x32 one taken (`widen` / `narrow`), Emerald-only poses from the nearest
  FireRed frame (wheelies from the bike, Dive recoloured to the Dive palette, watering standing), the battle back
  picture's throw (Emerald's 4 frames, FireRed's 5). The overworld palette goes in place where the object-event palette
  table gives the hero's tag (0x1100 / 0x1110, reflections + 1). **That palette slot is shared**: the surf blob, the
  jump shadows, the warp arrows and Emerald's FLY bird draw with it, so across games the colours they use stay
  (`LOOK_SHARED_COLOURS`, from the decomps' graphics) and the hero's go in the other slots (`PlayerLook.wear`: most used
  first, the rest joining the nearest colour), his pixels remapped to match - a straight palette swap turned Emerald's
  surf blob bright blue. Compressed pictures (front pic, trainer palettes, FireRed's map head) go into the ROM's empty
  tail as all-literal LZ77 streams and the pointers to them move there: the front pic's every reference, the trainer
  palettes' by tag (Emerald's player uses one palette for both pictures, its front and back tables' entries told apart
  by their tags; the front one's tag is the one its picture's `{ptr, 0x800, tag}` entry carries). Ruby / Sapphire give
  both entries the same tag and data, so there the back one is the entry 24 bytes past the hero's back picture entry
  (three back pictures, then their palettes: pokeruby's `battle_1.c`), and the back picture itself moves to the tail
  too. A Ruby / Sapphire hero worn elsewhere gets a reflection palette made from their own (`PlayerLook.reflect`). The player's own gender is dressed up; with
  RIVAL LOOK (`Prefs.rivalLook`, on) the other gender's hero too, as the look's `counterpart` (`build(rival = true)`, one
  free-tail cursor for both): in Emerald and Ruby / Sapphire that's the rival, whose records (RivalMayNormal & co.) use the
  same sheets, palette tag and trainer picture (`hasRival`; FireRed's rival is neither hero). The palettes to swap after a
  state load go per slot (`settleBoth`): a palette can't say whose slot it's in, so one the other slot takes is left be.
- **In the core** (`pk_cheats_block`): blocks beside the cheats' and REUSABLE TMS's patches, each keeping the file's
  bytes under it (malloc'd): only where the ROM's CRC32 there is the file's (no cheat on it), all or none
  (`pkRomBlocks`); `pk_cheats_overlay` and `pk_cheats_swap_rom` cover them, so the Poller's hash and RetroAchievements'
  see the file. mGBA copies the mapped ROM before the first write (`_pristineCow`): the file is never written. Palettes
  already loaded are swapped in EWRAM (the game's palette buffers) and OBJ palette RAM (`pk_cheats_repalette`), so a
  look put on or off mid-game shows at once, and again after every state load (a state made in another look). The save
  only holds a graphics id and the gender: untouched.
- **Checking it headless**: `PlayerLookTest` (ROM-backed) writes every hero x game patch to `app/build/player-look/`;
  mgba_dump's `romblocks FILE` puts one in exactly as the app does (and its `pal` lines), `romunblock` takes it out,
  `romcheck` compares the ROM with the file through the overlay and the swap. Checked on FireRed rev 1's, Emerald's,
  Ruby's and Sapphire's saves and on Kaizo (Emerald's save), Legacy, Imperium and Ultra Violet (their own): walking, running, both bikes, surfing (Surf taught by SetMonData, a `warp` beside water), the trainer card, a
  wild battle's throw, on and off mid-game; a FireRed patch is refused by Emerald (the CRCs). The rival
  (`*-rival.txt`): the map's first object template made the rival (RIVAL_*_NORMAL, its own local id) and added beside the
  player by a poked script (getplayerxy - map coordinates, no offset - addvar, setobjectxyperm, addobject), the card
  opened with the gender byte flipped for the rival's trainer picture; Emerald, Ruby and Sapphire.


### A Pokémon as the player look

PLAYER LOOK also offers the party's Pokémon (`"MON:<national>"` picks, `MonArt.monOf`) from a hack's follower sprites
(`MonArt.Source`, each kept in `rom-art/look/mon/<tag>/`; with several, a species comes from the first source in that order
that has it). R.O.W.E.: graphics ids 600 + species in its gObjectEventGraphicsInfoPointers (found as the table whose records'
paletteTag is 0x1800 + species), nine raw frame images (3-8 = the sheet), palettes by tag in sObjectEventSpritePalettes,
pictures from the tables its ROM's symbol table names. Recharged Yellow: a flat 0x24-byte record array by FireRed species id
(its base from the function that picks a follower's record, checked by shape), frames one LZ77 stream, the battle palette as
the overworld one, ids to national through sSpeciesToNationalPokedexNum (found by contents). Emerald Seaglass: gSpeciesInfo
0x088F0780 (0xD0 each), overworldData +0xA4 (one LZ77 stream; its size field says 0x200 even at 64x64), owPal +0xC8 and the
pictures / palette +0x58 / +0x60 / +0x68, all LZ77. A 64x64 sprite whose drawing sits in the bottom middle 32x32 of every
frame is kept cropped (`crop64`; Seaglass's and R.O.W.E.'s seven big ones). Radical Red has no following Pokémon (its
overworld Pokémon are a hand-picked set with no species link). Heart and Soul: `MonArt` reads v2.0.6's gSpeciesInfo (0x087E6544, 0x10C each; checked on PIKACHU's record): overworldData +0xB0
(an ObjectEventGraphicsInfo whose one image entry is the whole smol-compressed sheet: six 32x32 frames, south x2, north x2,
west x2), overworldPalette +0xF8 (raw), frontPic +0x58 / backPic +0x5C (smol), palette +0x60 (raw - TOGETIC's starts with
0x10, so LZ77 only with a 32-byte header), species 1-386.
`PlayerLook.buildMon` (`dress(mon = ...)`): no sheet is written; `monRecords` finds every overworld record of the hero
through its frame tables (entries pointing into one of the hero's sheets, the normal table on into the running one;
Emerald's normal record says size 512 for 256-byte frames, so the entries' size counts) and makes each 32x32 (size /
width / height, the bike record's OAM and subsprite tables, `TRACKS_BIKE_TIRE` -> `TRACKS_FOOT`) over a new frame
table into the six frames (in the tail, remapped into the slot like a hero's: `wearMon`); each entry's frame is the way the
record's animations show it (`facings`: animations in fours, south / north / west / east, the first four standing). The
back picture fills every throw frame (moved down by its empty bottom rows, `MonArt.asTrainerBack`: the player's picture is
centred at y 80, a Pokémon's at 80 + its back y_offset, which is those rows), the front picture and palette go where a hero's do. `PlayerLookRunner` keeps the
scan for these (built on first pick, `monPatch`) and takes the party from the snapshot (`onParty`, national numbers by
`PokedexSource.nationalOf`). Played headless (`PlayerLookTest.pokemonLooks` -> `<game>-<hero>-as-mon<n>.txt`) in Emerald,
FireRed, Ruby and Sapphire: walking, running, both bikes, the card, a battle.
A look changed mid-game leaves the player's sprite on the old record (a Pokémon's 32x32 shape, a frame table in the tail
the next look rewrites) and the old colours (a palette swap by content can't know a Pokémon's, nor tell Emerald's May's from
the rival's): on the field after every change and state load the engine calls `MgbaCore.pkLookRefresh` (pk_wide.c's
`pk_look_refresh`: the sprite set up from its record as ObjectEventSetGraphicsId does - only while it's still the player's,
its animations the record's - and `Patch.playerPal` put over its palette slot). mgba_dump's `lookrefresh
[PAL]` after `romunblock` / `romblocks` checks it: Red -> a Pokémon -> May -> Red in place, each right.
The palette goes only where the slot is: gPlttBufferUnfaded and gPlttBufferFaded (0x400 apart, the slot at 0x200 + 32 per
slot, both found holding palette RAM's colours) and palette RAM, and only while those three agree on a palette of 3+ colours;
otherwise the refresh returns 0 and the engine tries again on later field frames. The first version swapped the slot's
live colours by content everywhere in EWRAM: run on the first field frame after a battle, still faded to black, it wrote the
Pokémon's palette over every empty 32 bytes (5,399 of them: the map's tilemap buffers, the save blocks). `pk_cheats_repalette`
skips a `from` of under 3 colours for the same reason, and `EmulatorEngine.setPlayerLook` ignores the same patch again
(`Patch.sameAs`: the runner hands it over whenever the party read changes, as it does when a battle starts).

### TEXT LOOK (another game's text boxes and letters)

MAKEOVER > TEXT LOOK (`TextLook.kt`, `Prefs.textLookFor`) puts FireRed's dialogue box and normal font in Emerald, or
Emerald's in FireRed / LeafGreen, in the same patch as PLAYER LOOK's (`PlayerLookRunner` adds its blocks). The pieces are
found by fingerprint in one pass with the heroes (`PlayerLook.scan(rom, TextBlob.entries)`, `Fingerprints.find`;
`TextLookSigsGen.kt` from `scripts/gen_text_look_sigs.py`: FireRed's `menu_message.4bpp` + `stdpal_0`, `latin_normal.fwlatfont`
and text.c's widths; Emerald's `message_box.png` and `latin_normal.png` converted as gbagfx does - `latfont`, four 2bpp tiles a
glyph - and fonts.c's widths) and kept per style in `rom-art/look/text/` (`TextArt`).
- **Fonts**: one format in both games (512 glyphs x 64 bytes, a width byte each): written over every copy (FireRed keeps
  four). Each language has its own font, so the fingerprints (English's) only match English carts and their hacks; the box
  is in every Western cart (and the Japanese FireRed / LeafGreen).
- **The box**: the same window in both (30 tiles, 6 rows), drawn from Emerald's 14 tiles (straight sides, every middle row
  alike) or FireRed's 18 (a pill whose ends change row by row). Emerald's re-cuts into FireRed's slots and needs nothing else
  (`TextLook.cut`). FireRed's can't be drawn by Emerald's routines, so in Emerald its 13 tiles go into the box's slots as they
  are and both of Emerald's frame routines (menu.c's `WindowFunc_DrawDialogueFrame` and the custom-tile one the shops / bag
  messages use) are replaced through their pointers by `scripts/text_look_dialogue.s` (`TextLook.DIALOGUE_CODE`, assembled
  with the `firered-qol-build` image's arm-none-eabi-as), two copies in the ROM's last 2 KB: FireRed's layout one column
  narrower than Emerald's wider window, any height, the tile base / palette read from two constants or from
  `sTileNum` / `sPaletteNum` (the custom routine's only EWRAM pool words). The routines are found by their code
  (`TextLookCode`: a `CodeSig` fingerprint, no code kept but an 8-byte anchor - the CRC32 of the routine with BL pairs and
  address-like pool words zeroed, `TextLook.mask`) - English / German / French Emerald,
  Kaizo, Legacy; Imperium (a gcc rebuild) gets the letters only. Both boxes draw their frame with palette entries 10-15
  and text with 1-9, so the source's six frame colours go into the host's box palette (`TextLook.boxPalette`).
- **Checked headless**: `PlayerLookTest.textLooks` writes `<game>-text-<style>.txt`; the save prompt, the START menu, the
  bag and a direct call of `DrawDialogFrameWithCustomTileAndPalette` were shot with mgba_dump's `romblocks` in Emerald and
  FireRed.

### FOLLOWER (a Pokémon walking behind the player)

MAKEOVER > FOLLOWER (`Prefs.follower`, off): the party's lead (first non-egg, `PokedexSource.nationalOf`) walks a tile
behind the player, its sprite a hack's follower frames (`MonArt`). It's one more hardware sprite, put in through
pk_wide.c's scanline hook: at line 0 (`followBegin`) a hidden OAM entry after the game's (so drawn under them), OBJ tiles
1008-1023 and an OBJ palette slot no shown sprite uses are written - VRAM / OAM raw plus the renderer's `writeOAM` /
`writePalette` - and after line 159 (`followEnd`) the game's own bytes go back (a VRAM halfword the game wrote meanwhile,
seen by the `writeVRAM` hook, is kept). The game, its save and states never hold it; WIDE's per-line OAM record leaves
the entry out. Where it stands is an offset from the player's tile (gObjectEvents' current / previous coords), so map
connections (which shift every coordinate) don't move it; each step it takes the tile the player left, eased by BG2's
scroll (16 px a tile, any speed); a warp hides it until the next step. The player's place on screen is the box of the OAM
entries showing its sprite's tiles (subsprites split it; parked entries sit at y 160). Vanilla-shaped games only (an
8-bit graphics id); not on Heart and Soul / SoulGold (followers of their own) or while surfing (`EmulatorEngine.stepFollower`).
mgba_dump: `follower FILE` (frames + palette), `followon 1`, `followinfo`; checked walking / turning / running in
Emerald and FireRed, with EXPAND too.

### Co-op: seeing each other (the partner on your map)

Two linked players on retail English Emerald (BPEE, SHA-1 `f3ae0881...`, the same ROM on both) see each other's character
on a shared map: walking, running, riding either bike and surfing, in the game's picture and in EXPAND's margins. It is only
visual: you walk through each other, nothing is written into the game's RAM, save or states, and the partner's sprites are put
in and out per frame like FOLLOWER's. Code: `companion/data/coop/` (`Presence.kt`, `PresenceSmoother.kt`, `PartnerArt.kt`,
`CoopPresenceLink.kt`), `CoopPresence.kt` (the glue on the emu thread), `pk_wide.c`'s `pk_partner_*`. The transport is the
`CoopPresenceLink` seam (`send(bytes)`, `received`); the activity's `attachCoopLink(link)` sets it (null stops it and drops the `CoopPresence`). The presence is made only when
a link is attached and the loaded ROM is a 16 MB BPEE whose SHA-1 (hashed off the UI thread) is retail Emerald's; a player
without co-op hashes nothing and runs nothing. A reconfigure (`pk_field_configure`, a new game) also drops the native
request, fine position and fade memory, so a stopped engine's old partner can't come back; a new presence places none on
its first frame.
- **Wire format** (`PresenceCodec`, 20 bytes, little endian): u8 version (1), u8 flags (bit 0 valid, bit 1 female, the rest
  0), u8 map group, u8 map number, u16 fineX, u16 fineY (object-event coordinates x 16 plus the pixels into the step, 0..32767),
  u8 facing (the game's 1 S / 2 N / 3 W / 4 E), u8 mode (0 walk / 1 run / 2 Mach bike / 3 Acro bike / 4 surf), u8 step (+1 for
  every tile stepped onto; its low bit picks the foot), u8 look id, u32 sender time in milliseconds, u32 reserved (0).
  `decode` refuses a wrong size or version, a flag past bit 1, a non-zero reserved word, coordinates past 32767, facing outside
  1..4, mode outside 0..4 and a look id above 8; nothing is clamped. About ten updates a second (every 100 ms of wall
  clock, the first frame, and at once on a map change), whatever the game speed.
- **Look ids** (`PresenceLook`, pinned, never renumber): 0 the sender's own hero, 1 RED, 2 LEAF, 3 BRENDAN, 4 MAY, 5 RS_BRENDAN,
  6 RS_MAY, 7 GOLD, 8 KRIS. Only the id crosses the network, never ROM bytes: each device draws the partner from art it has
  itself (`LookArt`, `rom-art/look/`, kept by PLAYER LOOK), and a look it doesn't have shows as the game's own Brendan / May by
  the partner's gender. A PLAYER LOOK Pokémon sends 0. `valid` means the sender is on a map: a player in a battle or a menu
  keeps sending `valid` with the last field position, so the partner sees them standing there.
- **Reading the player** (`pk_partner_self`, beside FOLLOWER, same field table): map from SaveBlock1's location, tile from
  the player's object event (+0x10 / +0x14), facing +0x18, mode from gPlayerAvatar's flags, fine position = the previous tile x
  16 plus the progress BG2's scroll has made since the tile changed (FOLLOWER's wrap-safe measure, capped at 16). A step is
  counted when the object's own current and previous coordinates differ by one tile, so a warp or a map connection's shift
  (every coordinate moves at once) never counts. The reported map is held until the coordinates agree with it (at most 4
  frames), because the game names the new map one frame before it shifts the coordinates.
- **Smoothing** (`PresenceSmoother`, emu thread only): the partner is drawn about 150 ms behind real time, timed in wall-clock
  milliseconds (the clock offset is the smallest `arrived - sent` seen), so a sender at 4x or 0.25x moves at their real pace
  and never drifts. Late and duplicate updates are dropped, and render time never goes backwards. After a stall the partner
  holds at the newest update, then plays the buffered path back at about 2x until it has caught up, so there is no jump; it
  snaps only for a gap over 48 px between two adjacent updates, or a map change. Hidden after 3 s without an update, while the
  sender is on another map (a connected map is not shown) or not `valid`. A sender whose clock restarted (after a silence over
  3 s) is shown again.
- **Art** (`PartnerArt`, 42 frames of 32x32 4bpp then a 16 colour palette, 21536 bytes): 0-8 walking, 9-17 running, 18-26 Mach
  Bike, 27-35 Acro Bike (each sheet's first nine: stand S / N / W, then S step A, S step B, N A, N B, W A, W B), 36-38 surfing
  (the surfing sheet's frames 0, 2 and 4: S / N / W), 39-41 the surf blob (S / N / W, read from the ROM, CRC checked). East
  is west flipped; 16x32 frames are centred with `PlayerLook.widen`. A dressed look goes through `PlayerLook.wear`, as the
  player's own does. Built on a background thread, asked per (gender, look) and retried while a build runs.
- **On screen** (`partnerBegin` at line 0, `partnerEnd` after line 159, FOLLOWER's way): the rider is OBJ tiles 976-991 and the
  blob 992-1007 (FOLLOWER keeps 1008-1023), each a hidden OAM entry after the game's shown ones, found from 127 down and never
  FOLLOWER's, in the highest palette slot no shown sprite uses, with the player's priority. The game's VRAM, OAM and palette
  bytes go back after line 159, so states hold none of it (`partner_clean`: identical hashes with and without him). Where he
  stands is an offset from the player's sprite box (partner fine minus the local fine), which survives map connections. Why
  these choices: the same place, tile range and palette rules as FOLLOWER let the two share the screen without special cases,
  and nothing the game could read is ever changed. A partner whose box leaves the hardware's range (x -31..239, y -31..159) is
  never put in OAM, since the 9-bit x and 8-bit y would wrap him to the other side: only the margins draw him then.
- **Fades and DAY AND NIGHT**: the partner's colours are blended with gPaletteFade the game's way (BlendPalette's formula), then
  tinted like every other sprite. The game's OBJ palettes run 3 frames behind the struct's y, so a 3 frame history keeps him in
  step with the player beside him (right for fades without a delay: the field's screen and warp fades). A fade to black keeps
  its y until the fade back in, so the palettes it took are remembered until y is 0 again. Hardware and fast fades are ignored.
- **EXPAND's margins** (`drawPartner` in `drawRegion`, after the ghosts): drawn from the same computed place, honouring takenBuf,
  the OBJ enable, the window layers, priority against the player and the brightness effect. Over the real frame's area (the
  middle rebuild) it draws only where the hardware really drew him, so the rule holds: the rebuild of the middle 240x160 must
  equal the real frame pixel for pixel. Check with `wideon 40` + `widecheck` and `widestats`'s exact count (partner_wide: 60
  of 60), after the area-name popup is gone (it makes the middle differ by design). Nothing is ever put in OAM for the margins.
- **Checked headless**: `native-capture/coop/partner_*.txt` through `scripts/coop_headless_check.sh` (self, draw, clean, follower,
  offscreen, surf, badframe, fade, wide; commands in native-capture/README.md). JVM: `PresenceCodecTest`, `PresenceSmootherTest`,
  `PartnerArtTest` (ROM-backed ones take `POKEDAISY_ROM_DIR`), `CoopPresenceTest`.
- **Not done**: connected maps (a partner just across a map border is hidden until you are both on one map), reflections in
  water, tall grass over his feet, and NPC depth order north of the partner (he takes the player's priority only, so a person
  standing behind him can draw wrongly over him). The co-op transport and its manual page are a separate piece of work.

### DAY AND NIGHT (the overworld by the clock)

MAKEOVER > DAY AND NIGHT (`DayNight.kt`, `Prefs.dayNight`, off) tints the map by the device's clock: keyframes by hour
(night, dawn, day, dusk) blended by the minute. Picked over a GPU pass on the frame's layer tags: mGBA's output here
(mgba_dump's `rawshot`) leaves the top byte 0 for nearly every pixel, so text windows couldn't be told apart. Instead the
renderer's own palette copy is tinted: pk_wide.c's scanline hook, at line 0 of every frame, writes the tinted colours of
BG palettes 0-12 (the map's tilesets) and all 16 OBJ palettes through `renderer->writePalette` (`tintPalettes`); palettes
13-15 (the text windows, the START menu, FireRed's description box) are left alone. Palette RAM, the game's palette
buffers and the save never change; the game's VBlank copy overwrites the renderer's copy and the next line 0 tints it
again. EXPAND's margins come from palette RAM through pk_wide's `color()`, which tints them the same way while it's on.
- The activity sets `EmulatorEngine.dayNightTint` every half second (the clock's tint; none indoors - `Telemetry.mapType`
  not a town / city / route / ocean route - or with the row off, on a Game Boy / DS game or one in
  `DayNight.OWN_DAY_NIGHT`); the engine eases toward it on the field's screen (`FfMenuWatch.onField`, no battle) and back
  to none elsewhere, 4/256 a frame, through `MgbaCore.pkSetTint` (sent again every second while on: a new core is hooked
  when told).
- Checked headless with mgba_dump's `tint R G B`: field, START menu, the save prompt, EXPAND (`wideon 22` + `wideshot`)
  on Emerald and FireRed: map and people tinted, every window as it was, the margins matching the middle.
## PC BOXES and TRADE (save files)

A game's library menu > PC BOXES (`SaveEditorActivity`, `companion/ui/PcBoxesScreen.kt`) reads the
save file with the game closed, rearranges the PC, edits values and trades with another game's save. Event flags
come next, on the same reader and writer.

It's on for everyone since 2026-11-01 (it was behind `FeatureFlags.saveEditor`): a GBA game's library menu has PC BOXES,
and TRADE SAVES (the library's TOOLS, Settings > TOOLS) opens `SaveEditorActivity` with `EXTRA_TRADE` and no ROM, which asks for the first
game (TRADE FROM) and then the partner (TRADE WITH), the same list PC BOXES > TRADE gives. The manual's page is
`docs/manual/save-trade.md`.

- **Reading** (`companion/data/save/Gen3SaveFile.kt`): the Gen 3 flash layout as pret's `save.c`
  has it. Two 14-sector slots (0x0000, 0xE000); each 4 KB sector is 0xF80 bytes of data, then its
  id, checksum (the u32 words of its chunk summed, both halves folded), signature `0x08012025` and
  the save counter. Ids rotate inside a slot. The game loads the slot whose 14 sectors are all
  valid with the newer counter (`GetSaveValidStatus`, including its 0xFFFFFFFF -> 0 wrap), and so
  does the reader. Checksums cover each chunk's real size (`Gen3SaveFormat`: SaveBlock2 0x890 R/S,
  0xF24 FR/LG, 0xF2C E; SaveBlock1 0x3AC0 / 0x3D68 / 0x3D88; PokemonStorage 0x83D0), not the
  whole sector.
- **Writing** is a save as the game would make it: the edit goes into the *other* slot with the
  counter + 1 and the rotation + 1, each sector zeroed and filled like `HandleWriteSector`. The
  slot the game loaded stays as it was, so it's the game's own fallback if the new slot were
  bad. `withBlocks` reads its own output back before handing it out (`verifyWritten`): the new
  slot wins, the old one still loads, the blocks are exactly the edit, and no byte outside the
  new slot changed (Hall of Fame, Trainer Hill, recorded battle, mGBA's RTC footer).
- **The file** (`SaveFileEdits`): the save must still be what was read, a `SaveBackups` copy of
  it must exist, the edit goes to a synced temp file moved over the save, and it's read back
  (the backup goes back if it differs). Then `boot_fresh` (like LOAD SAVE): a resume state
  holds the old save and would write it back. A game left open behind the library is safe: its
  engine stopped on pause and reloads the file on start (`pkStateMatchesSave`). A **manual**
  savestate made before the edit still carries the old save, as with any outside change.
- **Which saves**: `Gen3SaveFormat.forGameCode` (AXV / AXP / BPE / BPR / BPG, not Japanese:
  shorter names change the structs) on ROMs up to 16 MB. `Gen3SaveData.mismatch()` then refuses a
  save that doesn't decode as that layout (party count, party checksums and species, the PC's
  records), which catches CFRU's plaintext boxes and expansion hacks that kept a vanilla game
  code. Hacks get their own layouts later, per `NativeConfig`.
- **Box records** (`Gen3BoxMon`): the 80-byte BoxPokemon decrypted (personality ^ OT id) and
  reordered (personality % 24); `encode()` gives back the game's bytes. A record whose checksum
  fails is a Bad Egg in the game and is never moved or re-encoded. Step one only moves records
  byte for byte, between PC slots: the party (100-byte records with stats) is shown, not moved or traded.
- **Tests**: `Gen3SaveFileTest` / `SaveFileEditsTest` build saves from scratch with their own
  writer (`SyntheticSave`: no real save can be in this public repo). Before trusting a change
  to the writer with real saves, boot an edited save headlessly (`mgba_dump`) and check the game
  loads it with no "save file is corrupted" message.
- **Icons** (`RomMonIcons`): read from the player's ROM *file* with the companion's own decoder
  (`decodeMonIcon`, shared with `DecompIconSource`) and the icon tables the companion already has: retail
  FireRed rev 0 / 1 and Emerald by whole-file SHA-1, the other retail games by game code + revision
  (`TelemetrySampler.otherRetailConfig`), each checked against the file first. Hacks get names only.
- **TRADE** (PC BOXES > TRADE, `Gen3Trade`, `TradeScreen`): two saves' boxes side by side; a slot on each side is a
  swap or a send. The rules are the games' (pret's `trade.c` / `link.c`): FireRed / LeafGreen link with R/S/E only
  with `FLAG_SYS_CAN_LINK_WITH_RS`; before its National Dex (`IsNationalPokedexEnabled`: magic, var and flag) a
  FireRed / LeafGreen / Emerald neither sends nor takes an egg or a species outside its regional dex (Kanto's 151 /
  Hoenn's 202 - Ruby / Sapphire's own code doesn't check, and a trade with FR / LG / E gives them the National Dex,
  `EnableNationalPokedex`); Mew / Deoxys need the fateful-encounter ribbon bit; mail and the Enigma Berry stay home.
  After a trade, what the game does: the received species seen in all three copies and caught
  (`UpdatePokedexForReceivedMon`; Unown / Spinda keep the first one's personality), trade evolutions
  (`gEvolutionTable`'s EVO_TRADE / EVO_TRADE_ITEM, Everstone stops them, the item is used up, an unnicknamed one is
  renamed - `EvolutionRenameMon` - so only English games evolve on arrival: other languages would rename it to their
  own word), asked first like the game's evolution scene. Both saves are written by `SaveFileEdits.writeBoth`: the
  partner first, and put back if the second write fails. The tables a save on its own can't read from a ROM (National
  / Hoenn Dex numbers, trade evolutions, a few item ids) are `SpeciesDexGen.kt`, from `scripts/gen_species_dex.py`
  over `$DECOMPS/pokeemerald`. Offsets of flags / vars / the seen copies per format come from the decomps' global.h.
- **Editing values** (`Gen3SaveEditor`, the BAG page and the detail pane's ITEM / move rows, `SaveBagScreen.kt`): only what
  the game could hold, from `Gen3Legal` over `SaveTablesGen.kt` (`scripts/gen_save_tables.py`, pokeruby / pokeemerald /
  pokefirered: per family the items each pocket takes - no key items, "????????" or Enigma Berry - each species' level-up
  learnset, TM / HM and tutor bits, and pre-evolutions). Money 0..999,999 and coins 0..9,999, XORed with SaveBlock2's
  encryption key like `SetMoney` / `SetCoins` (none on R/S); bag quantities XOR the key's low half (`SetBagItemQuantity`;
  PC items aren't), up to `AddBagItem`'s stack size (FR/LG 999, R/S/E 99 and berries 999; an HM 1). Pockets are written
  packed, TMs and berries in id order; key items and HMs are shown but never changed or removed (the game hands them out
  once). Held items: any bag item but HMs and mail. Moves: 1-4 different ones, each already known or one the species - or
  what it evolved from - learns by its level, by TM / HM or from the game's tutors (FR/LG add Cape Brink's three starter
  moves; Smeargle gets every move but Struggle). Egg / event moves stay if known but aren't offered. A kept move keeps its
  PP and PP Ups; a new one gets full base PP (`gBattleMoves`, `MoveStatsGen.kt`) and none. `Gen3SaveEditTest` covers them.
- **Look at it**: `cd ui-preview && gradle render -Ponly=pc-boxes,trade` (made-up saves, `SyntheticSave.demoFireRed` /
  `demoTrade`, with stand-in dots for icons - no game art in the repo); `PcBoxesScreenshotTest` for Paparazzi.

## WIDE (ASPECT > EXPAND)

SETTINGS > ASPECT > EXPAND (`GameAspect.WIDE` / WIDE in the code and prefs; renamed on screen once it also went
up and down on phones) fills a wide screen with the game at full height and square pixels: the middle is the
game's own 240x160 frame, the strips beside it (22 GBA px per side on the Thor's 1920x1080) are the overworld,
rebuilt every frame from the game's memory by `app/src/main/cpp/pk_wide.c`. A wider PPU can't do it: the field
only keeps a 256x256 ring of tiles in VRAM (`field_camera.c`), so the rest would be wrap-around garbage.

- **What it draws from**: the map grid in `VMap` (the map + 7 metatiles of stitched connections per side); past
  that, the connected maps' own cells from the ROM (`gMapHeader.connections`, the inverse of `fieldmap.c`'s
  `Fill*Connection`), else the border. Metatiles from the layout's tilesets (ROM), drawn as `DrawMetatile` does
  (BG1 top / BG2 middle / BG3 bottom by layer type; FireRed names its buffers misleadingly: `gBGTilemapBuffers2`
  is BG1). Pixels from VRAM and palette RAM, so animated tiles and fades are the game's. Sprites: `gSprites` in
  `gSpriteOrder` with subsprites and semi-transparent blending, like `BuildOamBuffer`; NPCs the game hid only for
  being more than 16 px off screen (`ObjectEvent.offScreen`) are shown - they stay loaded ~2 metatiles out.
- **Timing**: the main loop computes frame N while N-1 is on screen and the VBlank handler uploads it, so the RAM
  after frame N-1 describes the frame now drawn: `pk_wide.c` keeps that snapshot. When a step's row redraw runs the
  main loop past VBlank, a snapshot is caught mid-update (pos moved, the ring offsets not yet). So the camera comes
  from the BG scroll registers the frame was drawn with (mGBA's `memory.io`: write-only, open bus on a bus read)
  plus the ring's anchor from RAM, and every candidate (both snapshots, pos +-1 metatile) is scored against the
  frame's own BG1-3 tilemaps in VRAM; the sprite list must match the frame's OAM, else the last good one is kept.
- **Off the map: ambient edges.** Anything that doesn't check out - not mode 0 with BG1-3 on, windows hiding a layer
  at the screen's edges (a Flash-dark cave, a battle transition), under 90% of the tilemap matching (title, menu
  screens, battles) - crossfades the strips (16 frames) from the last map strips, frozen, to a glow spread from the
  game's own edge columns (`ambientEdges`): each strip column takes its row's colour from the 12-px edge band,
  blurred over more rows the further out it is, from full brightness at the game's edge to ~47% at the screen's,
  smoothed over frames so text passing the edge doesn't flicker it. It follows whatever the game shows (the title's
  flames, a battle's sky, flashes and fades) and invents nothing. Back on the map it crossfades to the map strips.
  The map strips also take BLDCNT's brightness fades (BLDY), not only palette ones.
- **Battles are wide too.** The battle background (BG3, `battle_bg.c`'s template: char base 2, screen base 26, a
  64x32 tilemap) is 512 px wide in VRAM - sky, terrain and both platforms all the way across - so with
  `gMain.inBattle` (bit 1 of `gMain + 0x439`) set and BG3 in that layout, the strips are what a wider PPU would draw:
  BG3 from VRAM, the frame's real OAM where sprites reach past the edges (affine ones too, with mGBA's own transform
  math: the battlers), windows continued past the edges (the opening band spans the whole width), blending and
  brightness. BG0-2 (text, menus, animations) aren't drawn past 240; where BG3 is transparent (the text box rows)
  the strip goes on with the game's own edge pixel of that row - the solid dark band the text box and menus sit on
  (the glow there looked muddy, the user's call). The text box rows (from y 112, `BATTLE_TEXT_TOP`) are always holes past
  the edges, so that band goes on even where a hack's BG3 has light tiles there (GitHub #114). A BG1 / BG2 tile past an edge in the top `BATTLE_BANNER_ROWS` isn't scenery going on: Unbound's banner, when a Pokémon on the bench levels up, has tiles past the edge while it slides, and the strip was mirrored in for those frames (GitHub #258; to trigger it headless, switch a second Pokémon in so both share the EXP). Where BG3 alone can't
  go on, a side's strip mirrors BG1-3 in from the edge instead (`battleMirrorX`, stacked by priority; BG0, the text,
  stays out): a background that is one 256 px picture twice over (BG3's two screen blocks equal above the text box:
  SoulGold's forest), BG3 that doesn't continue past that edge (`battleEdgeBroken`: the colour step across the edge,
  summed over the scene rows, well past the steps between the columns inside - SoulGold's Route 29, trees to the
  edge and bare sky past it) or scenery on BG1 / BG2 at the edges (`battleSceneOffBg3`: retail's intro dust and
  rocks too, which used to stop 44 px in). A window against the edge is never scenery (`findBattleBgScenery`: BG1 / BG2 with
  tiles in the last column and an empty map just past it on every such line - Unbound's level-up box on BG1 and its banner
  on BG2, GitHub #245): it neither turns mirroring on nor gets mirrored in. Otherwise BG3 is drawn as VRAM has it: retail's message / menu / RUN
  frames are byte-identical to before, and mirrored, its foe platform ending at the edge grew a second one.
  Checked headless on FireRed / Emerald (11 points each against the old code) and SoulGold, and on the Thor.
  Per-scanline registers: games rewrite scroll / windows between lines (the intro's sliding
  halves), so a hook on mGBA's `drawScanline` copies DISPCNT..BLDY as each line is drawn (it runs at HBlank's start,
  before that HBlank's DMA writes the next line's values). Checked like the map: captured with only BG3 + OBJ on
  (`layers 0x18`), the rebuild of the middle matched every battle frame of both games pixel for pixel - intro slide,
  send-out, menu, RUN, exit.
- **Line by line, on the map too.** The map rebuild draws each scanline with its own registers (the `drawScanline`
  hook): layer enables, windows and scroll (a line scrolled apart from the latched value - a transition's slices - is
  that much further along the map), and the camera score samples them the same way. So battle-start wedges and
  Flash's dark circle are drawn as the game draws them, and a pixel the windows leave without a map layer is a hole
  that continues the row's edge pixel (dark caves stay dark to the screen's edges). Sprites: affine ones are drawn
  (mGBA's transform), and screen-space sprites (not following the camera) wrap like OAM's 9 / 8-bit positions.
  Blending and brightness are done on the widened 8-bit colour, as mGBA's 32-bit renderer does, so they round alike.
- **Weather** is screen-space sprites (`GFXTAG_*` 0x1201-0x1206: fog, ash, sandstorm, bubbles, rain) laid only over the
  game's 240 px - fog / ash / sandstorm as 64 px grids, rain at random - so each is drawn again 256 px either way, in
  the margins only: it goes on seamlessly however wide the screen.
- **The sweep** (both games, `mgba_dump` with BG0 off so text boxes and popups don't count): warp in with the game's
  own `warp` script command (`0x39 group num warpId x y`), walk a loop with `widecheck` on. Pixel-exact on every map
  frame in towns, forests, lit and Flash-dark caves, gyms, fog (Lost Cave), shade (Berry Forest, Abandoned Ship),
  ash (Route 113, Mt. Chimney), rain (Routes 119, 120), sandstorm route, Seafoam, Meteor Falls. Known leftovers, all
  in the middle and never seen in the margins: a door's opening animation (drawn into VRAM, not the map data -
  arriving by warp shows one), the Pokémon Mansion's top line while the camera is at the map's top edge (the game's
  own stale tile row), a diving bubble a pixel off underwater, and Granite Cave B1F's Flash circle for the frames it
  fades back in after a battle.
- **Transitions, recorded**: `mgba_dump`'s `widerec` records every wide frame; `scripts/wide_gif.py` makes a GIF or a
  contact sheet. Checked on both games: boot (intro, title, Continue, FireRed's recap) to the map, and map -> a
  scripted wild battle (`setwildbattle` / `dowildbattle` / `end` poked at `0x0203FF00`, `call
  ScriptContext_SetupScript`: FireRed rev 1 `0x08069AF9`, Emerald `0x08098EF9`) -> RUN -> map.
- **Every battle scene, pictured**: `scripts/wide_battle_shots.py [--only NAME] [--margin N]` (Pillow, `$DECOMPS`) runs a
  scripted wild battle in one build of every game in `build/wide/wide.json` once per battle scene and shoots a wide
  frame every 10 frames, into `build/wide/battles/` (`index.html`: a sheet per game, the intro slide, text, action and
  move menus, the move's animation; a film of every shot per scene). Scenes are forced through the game's own scene
  functions (`rompatch` to `movs r0, #N; bx lr`): FireRed's `GetBattleTerrainOverride` (20 scenes) +
  `BattleSetup_GetTerrainId` (the intro's slide), Emerald / R/S's `BattleSetup_GetEnvironmentId` (10 terrains) +
  `GetCurrentMapBattleScene` (gym, Magma, Aqua, the Elite Four, Frontier) - found by code, and in the CFRU hacks
  under the jump CFRU puts at rev 0's entry; in the gcc-built Emerald hacks (Seaglass, Lazarus, SoulGold, ...) by what
  they do (`find_by_role`: the scene function returns the save's map header's battleType byte, the terrain function's
  result is the one call stored into the variable the background loaders read). Heart and Soul, Too Many Types 2,
  Celia and Recharged Yellow get only the save's own scene so far. Each shot is
  labelled with how the strips were drawn (`wideinfo`'s `battle` = `pk_wide_last_battle_ways`: `=` BG3 as VRAM has
  it, `R` / `O` / `<` / `>` the mirrored cases).
- **One motion per event** (`pk_wide_scene`, `WideCamera`): the camera no longer reacts to what's already on screen.
  pk_wide.c says what the frame *is* a frame early - FIELD, TEXT (controls locked + a text box / line effect), OVERLAY
  (a picture on BG0 over most of the field), BATTLE_START (battle_transition.c's `sTransitionData` is set:
  `PK_WIDE_TRANSITION_PTR`, found in the decomp ELF, checked in retail headless - non-null from the first flash to the
  wipe's end) or OFF (a battle, a menu) - and the camera runs one timed ease-in-out in log space per change of target:
  a text box or menu pulls in over 0.42 s, a battle over 0.55 s (its flashes last ~0.8 s, so it's over before the
  wipe slides anything), the way back out takes 0.65 s after the usual half-second delay. `WideCameraTest` checks the
  pull-in never jumps and eases both ends.
- **FireRed's map preview** (entering Viridian Forest, Mt. Moon...: `map_preview_screen.c` draws a picture on BG0 over
  the field while the new map sits under it, so the margins showed the new map around a boxed picture): OVERLAY is BG0
  covering >= 300 of the screen's 600 tiles in >= 11 full-width tile rows one after another (`OVERLAY_ROWS`: the picture
  is one block 14 rows tall; the START menu's SAVE screen covers as many tiles but in windows that never make more than
  a text box's 6 full rows - and Gen 3 draws window text as unique tiles, so counting distinct tiles can't tell them
  apart: it dimmed the SAVE screen once; Unbound's full-screen START menu and its save prompt make 11 full rows, but as
  two bars above and below the field, and dimmed it until the rows had to be one block - GitHub #86). Only the FireRed
  engine has the map preview (`mapPreviews`: the FR / FR_EX families), and never while a battle transition runs:
  SoulGold's item description over its message box and a trainer's mugshot sliding in passed as one, and since
  SoulGold keeps the window registers on, mGBA's output carries no layer tags there, so its windows went dark with
  the field (GitHub #100). While it's up the map around the picture is dimmed - the frame's own pixels that aren't BG0's
  and all of the margins - ramping over ~11 frames (`OVERLAY_DIM`) and then following the card's own alpha blend as
  the game fades it out (`overlayStrength`: BLDALPHA's first weight while BG0 is the first target), so the map comes
  back exactly as the card goes (it once stayed dark until the card's tiles were gone, then brightened in a step).
  The camera holds its zoom for the card (OVERLAY decides nothing, like FADE): a pull-in and a zoom back out around a
  two-second card was motion for nothing.
- **Bars over the field go on** (`markBarRows`): a row where BG0 is opaque all the way across and the frame shows it at
  both edges (Unbound's START menu: its quest bar over the field and its icon bar under it; FireRed's quest-log recap
  bars) is a hole in the margins, so the row's own edge pixel goes on like a battle's text box band - the map used to
  go on beside the bars. Both conditions count: Unbound's POKéDEX label and clock windows touch the screen's edges with
  the field between them (a white strip once), and BG0 sits opaque under the map beside them. Not under FireRed's map
  preview, which keeps its dimmed map around it. Only runs of such rows from the screen's top or bottom edge count
  (`barRows`): Unbound's text box is BG0 across too (a 1 px white outline at both edges, rows 116-157, the field under
  it), and carrying it out drew a white box behind every message in 1.4.0.
- **A warp holds the zoom** (`fadedFrame`): between two maps (a door, a connection building) the screen is black for a
  few frames with no map to check, which used to read as OFF - the camera pulled in to 1x and zoomed back out on the
  other side. A frame that's all black (or all white) outside a battle is FADE, whatever the map check or the menu
  check made of it (one black frame mid-warp passed as a menu screen): the camera changes nothing on it - neither the
  pull-in nor the way back out starts on a black screen (the boot's long black screens would have zoomed out unseen
  and pulled in on the title).
- **AUTO ZOOM IN** (SETTINGS, under ASPECT, only while ASPECT is EXPAND; `Prefs.expandZoomOnText`, ON by default;
  `WideCamera.zoomOnText`): OFF keeps the chosen zoom through conversations, signs, the START menu and menu screens (`PK_WIDE_SCENE_MENU`, a menu screen's own scene value so the option can leave it alone -
  its margins are the menu's back layer); a battle's start, a battle and the title still pull in - and so does a menu
  screen opened from a battle (POKéMON / BAG: `gMain.inBattle` stays set, so pk_wide.c reports it as the battle,
  `PK_WIDE_SCENE_OFF`, not `_MENU` - the party screen used to zoom out mid-battle and the battle zoom back in after it).
- **Leaving a building holds the zoom** (`textUp`, `POPUP_ROWS` / `POPUP_COLS`): the game locks the controls for the
  walk out of the door while the map-name popup is up (even Pallet Town's after the player's house), which read as a
  text box for ~80 frames - a pull-in and a zoom back out at every door. The popup's corner of BG0 (the top-left 14 x
  5 tiles, every Gen 3 game draws it there) doesn't count towards a text box; checked headless walking out of the
  player's house: FADE through the black, FIELD after.
- **"Previously on your quest" stays at 1x** (`questLogPlaying`, `PK_WIDE_QUEST_LOG` = FireRed's `gQuestLogState`, 2 /
  3 through the replay, checked headless on retail rev 1): the recap replays the field with scripted text boxes coming
  and going, which zoomed the camera in and out several times; it's OFF for the camera until the field is the
  player's, and the chosen step comes back half a second after that.
- **Borders past a neighbour's end** (`blockIn`): beyond the stitched strip, a point beside the current map but past
  the span of the neighbour on that side gets the current map's border, as the strip itself shows - not the
  neighbour's (Celadon City's trees above Route 16, where Route 16's sea border once drew a block with straight edges).
- **Tried and dropped (2026-10-12)**: replacing the battle background (BG3) with the place the battle started - the
  overworld around the player captured sprite-free as the transition begins, shown as a mip-filtered or crisp Mode 7
  floor under a sky in the terrain's colours, as a floor in whole scales, flat at 1:1 / 2:1, or as a stage strip of
  the map's rows between the game's own sky and ground. All ten looked wrong to the user (top-down map art never
  reads as a place behind side-view battlers), so none of it is in the code. Two findings from it worth keeping:
  mGBA's 32-bit output tags every pixel with its layer in the top byte (`FLAG_PRIORITY` / `FLAG_INDEX` /
  `FLAG_IS_BACKGROUND`: the backdrop has every flag set, a blended or covered pixel none - `fromBg`), which is how a
  frame's own pixels can be edited per layer (the OVERLAY dim uses it); and the platforms in a terrain's BG3 are the
  tiles its tilemap uses at most ~6 times (sky and ground repeat theirs along the row).
- **Games**: every retail FireRed, LeafGreen, Emerald, Ruby and Sapphire the user owns (English, Japanese, Spanish,
  German, French, Italian; every revision): `app/src/main/cpp/pk_wide_games.h`, one row of addresses per ROM by CRC32, generated by
  `scripts/gen_wide_tables.py <roms dirs>` from the English references (FireRed rev 1, Emerald: from the decomp ELFs,
  mapped onto retail through the literal pools - only FireRed's `VMap` / `gSaveBlock1Ptr` differ; Ruby
  rev 1: pret/pokeruby's `ruby_rev1` ELF, byte-identical to retail) through
  each ROM's literal pools (`port_retail.py`'s `map_literal`). Retail only, by the SHA-1s the app lists (GameTitles,
  RetailPortsGen): hacks share the game codes but move addresses. `gMapGroups` gets one pool vote,
  so it's also checked by the shapes of the maps it leads to. Each engine's layout (metatiles, attributes, border) is
  `pk_wide.c`'s `FAMILIES`. Ruby / Sapphire are Emerald's layout with their own quirks: `gSaveBlock1` is static (no
  pointer), `gSpriteCoordOffsetY` isn't next to X, `gMain.inBattle` is at +0x43D, and a `struct Subsprite` is 8 bytes
  (u16 x, y + the bits, padded to 4 by agbcc - the menu cursor's RAM table showed it); Japanese R/S keep
  FireRed / Emerald's gMain and 4-byte subsprites (`PK_WIDE_FAMILY_RS_JA`). Kotlin asks native (`WideScreen.gameFor` -> `pkWideKnows`); a game WIDE doesn't know shows
  as ORIGINAL (the companion's pick-list tags WIDE NOT YET). `scripts/verify_wide.py` boots every ROM in the table
  headless with its save and checks a walk (BG0 off) and a scripted battle (BG3 + OBJ) frame by frame - all 82 in the
  table pass (build/wide/verify.md; a hack marked `pending` is checked too - its row is under `PK_WIDE_PENDING`, which only
  verify_wide.py's build defines - but the app doesn't have it). Rerun both after
  touching pk_wide.c.
- **ROM hacks** (`HACKS` in `gen_wide_tables.py`, by SHA-1, each with its host_roms.conf save and any boot presses):
  - *CFRU* (Unbound, its French build, Radical Red) keep FireRed rev 0's RAM and code, so their addresses map through the
    literal pools like a retail port's; only `gMapGroups` is overridden (the app's GuideTables value - Unbound repoints it
    and XORs its entries with 0x00B749DE: `MAP_GROUPS_XOR`). Unbound's DrawMetatile writes 0 to BG3 under a NORMAL
    metatile where retail writes 0x3014 (`NORMAL_BG3`), and its snow lives in world space, relying on OAM's 9 / 8-bit wrap
    (a flake at y -232 shows at 24): world sprites are wrapped like the PPU does wherever that lands on the real screen
    (`hardwareWrap`), except NPCs we show past the edge on purpose.
  - *Binary FireRed hacks* (Odyssey, Gaia, Amethyst 1.3.0, Celia, Sword and Shield Ultimate Plus, GS Chronicles - its primary
    tilesets 512 metatiles, `PRIMARY_OVERRIDE` -, Ultra Violet - under
    its own game code PUVV -, FireRed Rocket Edition and Ash Gray, both booted with retail FireRed's save, Ghost Grey with its own, and Let's Go
    Pikachu / Eevee with a new game's - its object graphics table repointed) and *binary Emerald hacks* (Glazed, Emerald Kaizo - booted with retail Emerald's save -, Super Mariomon, whose RAM sits 0x80 lower from the
    sprites on: `sSpriteOrder` from find_wide_addrs.py) map through the literal pools the same way, `gMapGroups` from the
    app's GuideTables. Super Mariomon 1.3 / 1.2.x boot the 1.5.1 save cut to their SaveBlock1 (`trim_save_block1.py`).
  - *pokeemerald-expansion* (Lazarus, SoulGold's six builds, Seaglass, Imperium, Rogue, Heart and Soul, Too Many Types 2, and
    Elite Redux - its own gcc pokeemerald, on this family with R.O.W.E.'s 12-bit metatiles / 2048 primaries, object gfx ids
    271+ from vars and no sprite palette tag list; `PK_WIDE_FAMILY_EX`) and *Recharged Yellow* (a gcc pokefirered: FireRed's map and triple-layer metatiles with
    expansion's objects, `PK_WIDE_FAMILY_FR_EX`): gcc builds share no code with retail,
    so `scripts/find_wide_addrs.py` finds each address in headless dumps (boot, a step right, a step down: VMap by the
    layout's size, the camera by HOFS / tile offsets, gSprites by the OAM, the coord offsets from the player's sprite,
    sSpriteOrder as a permutation). Expansion moved `ObjectEvent.spriteId` to +0x23 (graphicsId is a u16). Both hacks use
    triple-layer metatiles - 24 bytes, BG3 / BG2 / BG1 (`METATILE_SIZE`) - and SoulGold 11-bit metatile ids with 1024
    primary metatiles (`METATILE_MASK`, `PRIMARY_OVERRIDE`). gcc places the camera pans where it likes (`PAN_OFF`: +8 in
    agbcc builds and Lazarus, -4 in SoulGold, 12 in Heart and Soul). Heart and Soul keeps SaveBlock1's pos 4 bytes in
    (`SB1_POS`; find_wide_addrs.py's `--pos`) and mixes layouts: its own (MapLayout +0x18 = 2) split the primary tileset
    at 640 metatiles like FireRed, the Emerald ones it kept (0) at 512 (`LAYOUT_VERSIONED`: the split is per snapshot,
    `syncSplit`). Its boot is `rhh_splash.txt` (`boot_file`: default.txt's A presses talk to the follower). Rogue's
    gMapGroups came from its save: the current map's header in ROM -> its group's array -> the table.
  - *Monomyth* (its own closed pokeemerald, game code MONE on 1.0, BPEE on 1.1; `PK_WIDE_FAMILY_MM`, a row each): Emerald's map and objects with
    triple-layer metatiles, found the same way (`--pos 4`). Its struct Main keeps inBattle 0x17 bytes before callback1 (the
    family's `INBATTLE_OFF` is -0x17, `GMAIN` is callback1), its `sFieldCameraOffset` is four s16 (`CAMERA_WIDE`, pans 0xC
    on) and it has 80 sprites (`MAX_SPRITES`: its snow and rain sit past 64; `Snap` holds up to `SPRITES_CAP`). It doesn't
    rewrite DISPCNT every frame, so mgba_dump's `layers` takes effect only from the game's next write: set it before the
    boot (verify_wide does) or BG0's map-name popup stays in the check. Exact headless indoors, in CATSEAR CITY, on Routes
    1 and 3 and under snow, ash and sandstorm; under rain a few frames (25 of 440) show raindrops where they were a frame
    before (the game moves them after building that frame's OAM, so WIDE keeps the last sprite list OAM agreed with).
    Far-off characters aren't drawn (find_wide_objects.py's graphics table agreed with 1 of 5 live objects).
  - *Pending* (mapped, checked, not in the app): Orange Islands (the top row, half the walk), Quetzal (its own SaveBlock1,
    pos 0x470 in: most map frames differ), R.O.W.E. (12-bit metatile ids - `METATILE_MASK` 0xFFF, elevation in the top
    nibble - and a 2048-metatile primary tileset get its map found, `NUM_METATILES_TOTAL` 4096; one NPC's sprite and a
    building's metatiles still differ).
  - *The game's own stale edge*: Sword and Shield's "performance" builds (and Amethyst 1.4.1 once) don't draw the tile
    column scrolling in until a whole tile has - the real frame shows the ring's old tiles there and the rebuild the map.
    `widecheck` doesn't count a frame whose differences all lie inside the partly shown edge tile column / row of BG1's
    scroll (`x >= 240 - (HOFS & 7)` or `x < 8 - (HOFS & 7)`, the same for rows), and says so.
- **Transitions that slide lines** (Unbound's wild battle start): the latched scroll is only the last line's, and the
  lines slide both ways around the camera, so the camera's own scroll is also taken from RAM (sFieldCameraOffset +
  sHorizontal / sVerticalCameraPan + 8, as FieldUpdateBgTilemapScroll writes it) and scored as a candidate. On the real
  screen a slid line shows the 256-px ring wrapped around (the map where it holds the camera's view, leftover tiles past
  it - which the next frame may already have redrawn, so those pixels are the real frame's); the margins show the map
  slid along. `mgba_dump`'s `wideinfo lines` prints each line's scroll.
- **The window** (`WideCamera`, `pk_wide_view`): pk_wide.c draws any rectangle around the frame - beside, above,
  below, or one the frame only partly covers (rows past the screen take its edge line's registers; holes go on with
  the frame's nearest edge pixel; the glow spreads from all four edges). Before each frame the engine asks
  `EmulatorView.camera` for the window (`pkWideSetView`), and `publishFrame` hands the GL thread the drawn window with
  where it goes: whole GBA pixels drawn, the quad offset by the fraction (`WideCamera.Window.offX` / `offY`), so zoom
  and look glide instead of stepping. Up to 800x800 (`PK_WIDE_MAX_VIEW_W` / `_H`). Savestate thumbnails stay 240x160.
- **ZOOM** (the ZOOM hotkey: R3 - the right stick's click, which used to be SLOT NEXT - or C on a keyboard): steps out from 1x (the game at the view's full height) to
  whole screen pixels per GBA pixel showing >= 1.2x more each (`WideCamera.levelsFor`: the Thor's 6.75 -> 5 / 4 / 3,
  ~1.35x / 1.7x / 2.25x the map). It eases back to 1x whenever `pk_wide_wants_close`: not on the map (battles, menu
  screens) or the field's controls locked (`CONTROLS_LOCKED`: sLockFieldControls / sScriptContext2Enabled - every game
  in the table: the decomp ELFs for retail, the Lock / Unlock / ArePlayerFieldControlsLocked trio for the gcc builds,
  and for Recharged Yellow, whose lock is inlined, the byte that's 1 under a scripted message and 0 through the test walk) with BG0 showing a text box / menu or lines drawn apart (a
  transition) - a warp's fade or the map-name popup don't. The step comes back 0.5 s after. NPCs only exist ~2 tiles
  past the game's own view, so far out they pop in as you near them.
- **Unbound lags at its map borders by itself**: walking near a connection it drops a frame every step, and 3-4 at
  the crossing (headless: sFieldCameraOffset not moving for a frame while LEFT is held, on mGBA with no app around it;
  in the middle of a city one every ~80 frames). The app's pacing stays at 60 fps through it (`fps=` lines, late 0),
  with EXPAND on or off - it's the game's own map loading, as on hardware or RetroArch's mGBA core.
- **An undefined cell is exactly 0x3FF** (`blockIn`): the game's MapGridGetMetatileIdAt compares the whole u16 with
  MAPGRID_UNDEFINED, so a map's own metatile 0x3FF with collision / elevation bits (Unbound's treetop corners, 0x33FF) is
  drawn, not the border. Masked first, those cells came out as Unbound's border (water squares over the trees).
- **Connected maps, zoomed out** (`readConnections`, `blockIn`, `foreignFor`): the game copies 7 metatiles of each
  neighbour into VMap and draws them with the current map's tilesets; past that, a neighbour may have tilesets of its
  own (FireRed's Route 18 is Celadon's, Fuchsia City its own), so off the real frame each connected map - its VMap strip
  too - is drawn with its own: metatiles, tiles LZ77-decompressed from the ROM (cached, 4 tileset pairs, never replaced
  mid-frame), palettes from the ROM; what it shares with the current map stays VRAM / palette RAM. On the real frame,
  exactly as the game. Neighbours' neighbours are drawn too (Route 20 from Fuchsia City), and where no map is, the border
  of the neighbour on that side - so the scenery doesn't change as you cross. Their object templates (from their headers)
  are ghosts as well, unless the current map has a clone of them.
- **Fast-forward** draws one window per ~12 ms (`EmulatorEngine.WIDE_DRAW_NANOS`, `pkWideSetDraw`): FF runs several
  frames per screen refresh and only one is seen; the rest only keep the RAM after them (`pk_wide_skip`) and aren't
  published. At 4x zoomed out it had the emulation thread at 100% of a core (the Thor ran hot, 174 fps of 240); now ~35%.
- **Far-off objects** (`takeGhosts` / `drawGhosts`; `OBJ_GFX_INFO` and friends - the decomp ELFs' statics for retail
  FireRed / Emerald / Ruby, mapped to every language and checked by content in gen_wide_tables.py, since the graphics
  table gets one pool vote; `scripts/find_wide_objects.py` for gcc builds, from a fixture's live objects): the game spawns object events only ~2 tiles past its own view, so zoomed out a cuttable tree,
  a boulder, an item ball or an NPC wasn't there until you neared it. Every template the save holds for the map
  (`SaveBlock1.objectEventTemplates`: home tiles, a boulder where Strength left it) that isn't hidden by its flag or
  already spawned is drawn from the ROM's graphics (`gObjectEventGraphicsInfoPointers`, its movement type's first
  facing), where SpawnObjectEvent would put it, in its palette slot if loaded (fades, tints) else the ROM's palette, behind
  every real sprite. An object the game has removed is drawn where it was last seen live, facing the way it faced
  (`rememberObjects` / `seenFind`): the game removes one once both its tile and its home are ~2 tiles past its own view
  and spawns it again at its home, so drawn from its template a wandering NPC jumped home the moment it went (zoomed
  out, in plain sight). It still jumps home when the game spawns it again, as in the game; the memory is per map and
  goes with a warp (the map no longer current or beside it) or a reset. A template whose object is still live - the
  map you just crossed from keeps its objects until they leave the view - isn't drawn either (it was a double at home).
  Never on the real frame (the game shows what's there), and only with an NPC palette from the ROM's table - Lazarus's
  lamp lights are templates too (palette tags 0x8001 / 0x8002, shown by the game as blended glows), which drew over the
  real glows, so those tags are never drawn: its ROM's table has them (GitHub #208: they glowed by day past the frame). A ROM palette is tinted as the game tinted its loaded ones (`measureTint`: palette RAM vs the ROM, per channel,
  darkening only): SoulGold's night, a fade. CFRU's ids are 16-bit (`OBJ_GFX_TABLES`: template byte 3 picks a table,
  0xFF a var; Unbound, Radical Red, Amethyst, Orange Islands, Sword and Shield), expansion's too (`TEMPLATE_KIND` 3:
  graphicsId at +1, kind at +3; ids past `OBJ_GFX_VAR_FROM` + 16 are overworld Pokémon, not drawn). Unbound's palette
  table runs on past an empty entry (0x1200s after 0x1100s); `romPalette` caches 64 tags. Each hack's SaveBlock1 is its
  own (`SB1_OFFSETS`; Recharged Yellow keeps its flags before its templates: `SB1_FLAGS_AT`); `mgba_dump`'s `wideghosts`
  lists them and the connected maps they come from, `NO PALETTE` where neither source has one.
  A template's flag is read the way the game's FlagGet reads it: past SaveBlock1's own array the engine reads on into
  the block (Odyssey's and Glazed's high flags live there), but CFRU's GetFlagPointer (Unbound, Radical Red, Amethyst,
  Sword and Shield) keeps 0x900-0x18FF in its own bank (`EXPANDED_FLAGS`, 0x0203B174) and 0x1900-0x3FFF on one byte
  (0x020370B8) - read from the save block, Unbound's hidden NPCs (a police officer beside a Pokémon Center) showed in
  the margins and vanished as you walked up. Special flags (0x4000 on) count as hidden. To look at a reported spot,
  pull the app's `states/<crc>/resume` and `mgba_dump`'s `loadstate` it, then `wideghosts` / `wideshot`.
  A template outside its own map isn't drawn (Too Many Types 2 parks cutscene trucks at y -1). The graphics tables of the
  gcc builds run into other data past their end, so each has its length (`OBJ_GFX_COUNT`): Rogue 0x269, Heart and Soul
  0x224, Too Many Types 2 0x10D, Recharged Yellow 0x159 (its code: ids to 0x158, vars from 0x15A, Pokémon from 0x200);
  where a game's var ids aren't known, `OBJ_GFX_VAR_FROM` 0xFFFF leaves them undrawn rather than guessed. Rogue's
  templates hold graphicsId at +2 (`TEMPLATE_KIND_AT` 1). find_wide_objects.py takes several dumps (a walk dumped every
  step: more live objects to vote). With no palette tag list (or none loaded) the tint comes from the map: BG palette RAM
  against the tilesets' colours in ROM.
- **Torn frames**: a lag frame can change palette RAM or OAM while it's drawn (Recharged Yellow's and Rogue's battle-exit
  fade, Seaglass's healthboxes), so the scanline hook keeps a copy of each whenever a line's differs from the last
  (`linePalBuf` / `lineOamBuf`, up to 8 a frame) and every line is drawn with its own (`useLine` sets the registers `R`
  and the palette `P`; a battle's sprites draw each OAM on its own lines). VRAM written mid-frame (a tile upload) can't
  be redrawn from anything taken after the frame: `pk_wide_last_vram_torn` reports it, and `widecheck` doesn't count
  such a frame. Brightness is mGBA's per channel in place (white darkened by 1 is 240, 239, 239: Imperium's flash), and
  a window's effects bit holds in battles too.
- **Menu screens** (`PK_WIDE_MODE_MENU`, `drawMenuRegion`): instead of the glow, the back BG layer goes on past the
  edges, each row repeating the pattern found along its edge (`menuPeriod`: the smallest 8..32 px that repeats); a row
  whose edge doesn't (a button reaching it) borrows the nearest row that does, so stripes and frames carry on without
  copying any text or panel. Mode 0 / 1 tiled screens; anything else still glows. Where the back layer is transparent
  the backdrop colour shows, as the PPU would - unless it's transparent at that row's edge while the frame shows another
  layer there: then the frame's edge pixel goes on (a hole). SoulGold's party screen leaves its back layer bare under a
  panel, and its backdrop is the palette's never-seen magenta: the strips were bright pink (GitHub #116). Retail's
  party and bag screens come out byte-identical.
- **Never a crash**: every read goes through bounds-checked helpers and every index is clamped; sizes from memory are
  bounded before they're multiplied; `_Static_assert`s keep the config rows in step with the slots. On top, `pk_wide_view`
  runs under a fault guard (SIGSEGV / SIGBUS / SIGFPE / SIGILL on its thread while drawing jump back out; WIDE switches
  itself off for the game - `pk_wide_fault` -> `MgbaCore.pkWideFault` -> a HUD notice - and the plain frame shows); a
  fault anywhere else goes on to the handler before it. The camera, the frame hand-off and the GL placement each catch
  and fall back. `mgba_dump`'s `widefuzz N SEED` throws random windows and random bytes / pointers at the RAM WIDE reads;
  built with `-fsanitize=address,undefined -DPK_WIDE_NO_GUARD` it ran 33,000 frames over six games (map and menus) clean,
  and since the far-off objects went to every engine (it also hits SaveBlock1's templates / flags / vars and the palette
  tag list) 66,000 more over eleven: retail Emerald and Ruby, Unbound, Radical Red, Amethyst, Lazarus, SoulGold, Seaglass,
  Rogue, Heart and Soul, Recharged Yellow.
- **Telling players** (`ExpandIntro`): once on the Library for anyone not on EXPAND (`Prefs.expandIntroSeen`; after
  first-time setup), and once more on the companion the first time a game EXPAND knows opens while it's still off
  (`Prefs.expandGameOfferSeen`, `CompanionSettings.expandOffer`) - "NOT NOW" on the Library doesn't stop that one, but
  any answer to it settles both. TURN ON sets ASPECT > EXPAND. ui-preview: `library-expand-intro`, `*-expand-offer`.
- **A phone upright** (`PortraitPanel.expand`, `GameStageLayout.expand`, `PortraitLayout.arrange(expand)`): the game
  takes everything above the companion (COMPANION's AT BOTTOM / UNDER GAME land in the same place then) and the touch
  pad lies over it; 1x is the frame at the view's full width (`levelsFor` takes the smaller fit), so the window goes
  on above and below it (a 1080-wide phone: 4.5 -> 3 / 2).
- **Look-around** (the right stick, AXIS_Z / RZ or RX / RY): standing, it moves the view and leaves it there; walking
  (a direction held) glides it back - with the stick held while walking it follows the stick, the player kept 28 px
  inside. Checked headless with `mgba_dump`'s `wideview X Y W H`.
- **Checks**: `scripts/widescreen_proto.py` is the Python original (reads `mgba_dump` captures). The C is checked
  headless with `mgba_dump`'s `wideon` / `widecheck` / `widestats` (`native-capture/README.md`): it rebuilds the
  middle 240x160 too and compares it with the real frame. Walking, running and NPCs moving, every frame matched
  pixel for pixel on both games (2026-10-09). Not covered yet: weather drawn over the field (rain sprites, fog on
  BG0) stops at the GBA's edges; scanline effects aren't extended.

### WIDE on the Game Boy (`pk_wide_gb.c`, Pokémon Yellow, Crystal, their Legacy hacks, Crystal Clear and Polished Crystal)

Since 2026-10-18 English Yellow (CRC32 `7d527d62`) has EXPAND, ZOOM and the look-around too. `pk_wide.c` hands every call
to `pk_wide_gb.c` while a Game Boy game is configured (`pk_wide_gb_knows` / `_configure`, same CRC lookup); the frame is
160x144 (`pk_wide_frame_w` / `_h`, the JNI's frame check, `WideCamera.frameW` / `frameH` from `EmulatorView.bindCore`, so
ZOOM's steps are whole Game Boy pixels: 7.5 -> 6 / 5 / 4 / 3 on 1080 rows). Addresses are pret/pokeyellow's `.sym`
(`GB_ROMS`).

- **What it draws from**: `wOverworldMap` (the map's blocks inside a 3-block border holding the connection strips and
  `wMapBackgroundTile`), the blockset at `wTilesetBank:wTilesetBlocksPtr` (4x4 tile ids a block), VRAM's tiles and the
  Color palettes as mGBA's software renderer holds them (`palette[lookup[...]]`). A tile id's BG attributes (palette,
  flips, bank) are learned from wherever it shows on screen. Past the border: the four connected maps' own blocks (their
  header through `MapHeaderBanks` / `MapHeaderPointers`, placed by the strip's source and destination pointers; only one
  drawn with the same tileset), then the background block.
- **Where the screen is**: RAM is a frame ahead (mGBA returns at VBlank's start, before the handler copies `hSCX` and the
  redrawn rows), and mid-step the block pointer and the scroll move at different times. So the screen's 20x18 BG map
  tiles are matched against the world around RAM's guess (`wCurrentTileBlockMapViewPointer` + `wX/YBlockCoord`, +-4
  tiles) in both snapshots, fine scroll from SCX / SCY as drawn; 90% of the screen's map tiles (ids under 0x60: text box
  tiles don't count) must match. A text box or the START menu is a copy of the screen in the window, full screen at
  (7, 0): then the window's map is matched, unscrolled. Crossing a map connection, RAM holds the new map ~5 frames before
  its border has the old view in it: the last snapshot that matched is tried next, then the whole loaded world.
- **Objects**: OAM as drawn (lower index on top, as on a Color; BG priority from the OAM and tile attributes), plus the
  NPCs Gen 1 hides for being off screen (image index $FF: they freeze there), standing at `32 + 16 * MAPX` /
  `28 + 16 * MAPY` with the standing frames of `SpriteFacingAndAnimationTable` - unless `IsObjectHidden` would hide them
  (`wToggleableObjectList` / `Flags`). Checked: those positions equal the on-screen NPCs' own (C1x4 / C1x6), walking too.
  The game shows an NPC only once it stands a tile inside the screen, so for the step that brings it in its picture has
  none: on the plain field, a hidden NPC within `GB_EDGE_STEP` of the edge also goes over the picture (`GbObj.over`, GitHub
  #385), or it was cut at the old edge and vanished until the game drew it. `widecheck` still compares the game's picture alone.
- **Scenes**: FIELD; TEXT while `wFontLoaded` bit 0 (any text box, the START menu); BATTLE_START from `wIsInBattle`
  while the field still matches (a wild battle sets it before its flashes and wipe); OFF in the battle; MENU for a menu
  screen (party, Pokédex: too few map tiles); FADE on a white / black frame, and for 30 frames after the field on a
  screen of map tiles that matches nothing (a door: the new map's VRAM goes up a third a frame - the zoom holds).
- **Off the field: plain bars** (the user's call): battles and menu screens get the screen's own background, flat - per
  row, the most common colour in the outer 32 px of both sides over the rows 32 above and below (`gbBackgroundRows`;
  above / below the frame, its top / bottom row's): white in Yellow's menus, battles and trainer card (its thin red border
  doesn't win), black over Crystal's Pokégear and its light colour beside the text box (the user's call). One row's own
  sides striped the bars where an icon or the SWITCH box touched an edge; the band evens that out. Crossfaded 16 frames; the GBA's glow looked smeared against Gen 1's plain screens. SAVE's prompt
  blanks the screen behind its boxes but for a corner of the map: fewer than 120 map tiles, but all where the field last
  was (`gbExact` on the last matching snapshot) - the field still, so the strips keep the map. OPTION keeps them too (the
  user's call): `wBattleAndStartSavedMenuItem` = OPTION (5, counted with the POKéDEX row: one less before
  EVENT_GOT_POKEDEX, flag 37), only after the START menu itself was on screen (`gbFromStart`: its box's corner tiles at
  (10, 0) / (19, 0) on a TEXT frame; the plain field or a battle clears it). The off-screen NPCs aren't drawn once a battle starts (its flashes hide the
  sprites) or with the sprite layer off.
- **OAM past the edges**: an NPC walking off the right edge keeps its objects at x 168-176 for the step, one walking off
  the left / top wraps; only Gen 1's cleared entries (y $A0) are skipped. An RAM-drawn NPC is left out only where OAM has
  all four of its objects less than a step from where it stands (any one object that near also matched the next NPC
  over). Before both, NPCs flickered at the edges: checked by `widerec` and counting one-frame blinks in the strips (none
  in 600 frames of Vermilion's NPCs walking; walking, only the water's own animation).
- **Checked** headless (`wideon`, `widecheck`, `wideview -160 -72 480 288`, `widerec`) on the English save: out of
  Vermilion City's Pokémon Center, around the city, north into Route 6 and back, the START menu, the Pokédex, a wild
  battle's start. Every map frame matched pixel for pixel but those the game wrote a palette in while drawing (a door's
  fade, the next map's colours at a connection, opening the START menu): `pk_wide_gb_last_torn`, from hooks on the
  renderer's `writePalette` and `writeVRAM` while `ly` < 144; `widecheck` doesn't count them. ~0.2-0.4 ms a frame at 480 x 288 with the
  check; the whole-world search, a few frames at a door, ~2.5 ms.
- **Crystal** (2026-10-18; rev 0 / rev 1 / Australia, CRC32 `ee6f5188` / `3358e30a` / `bb6dd80c`, pret/pokecrystal's
  symbols - the same in all three; a `GB_ROMS` row with `gen` 2). The same rebuild, Gen 2's reads where they differ:
  - RAM: D000-DFFF is always read from WRAM bank 1 (`gbRam`: Crystal switches SVBK mid-frame); `wOverworldMapBlocks`
    (C800) has Gen 1's layout, the guess is `wOverworldMapAnchor` + `wPlayerMetatileY / X`, the border is `wMapBorderBlock`.
    Block 0 there *is* the border (`LoadMetatiles` swaps it in: the rows above / below a map with no connection that way
    stay 0) and ids wrap at 128 (`gbBlock`) - New Bark's south edge didn't match on the Thor before that. Crossing a
    connection both snapshots can hold the new map half loaded (zeros), so the last exactly matching snapshot is tried
    whenever a match isn't exact, not only under 90%.
  - Colours: no learning. The blockset's tile byte indexes the tileset's palette map (`wTilesetPalettes`, bank $13: a
    nibble per tile, palette + VRAM bank 1), as `_LoadOverworldAttrmapPals` builds the game's own attribute map; the BG
    map holds the id without bit 7, so screen and world compare id + bank (`gbKey`, 0x100 = bank 1).
  - Connections: `wNorth/South/West/EastMapConnection` (12 bytes: group, number, strip source / destination), the
    connected map's header through `MapGroupPointers` (9 bytes, attributes at +3: height, width, blocks bank / pointer).
  - The screen: the START menu and text boxes are drawn on the BG, re-anchored to scroll 0 (the window covers the whole
    screen only for a moment); the map name sign is a window over the bottom rows (WY 112): tiles under a window over
    part of the screen count as neither map nor text, and the check's middle draws the window there.
  - NPCs: an object struct (`wObjectStructs`, kept up to a step past the screen, OFF_SCREEN) is drawn where the game
    would draw it - its `OBJECT_SPRITE_X / Y` + offsets against the player's, who stands at (64, 60), corrected by the
    scroll the next frame takes (`hSCX` - SCX: RAM is a frame ahead) - with its `OBJECT_FACING` frame (walking frames are
    the tiles + $80; `STANDING` = the direction's standing frame), its tile's bank (bit 7 clear = bank 1), palette, grass
    and priority flags as `InitSprites` builds them. Map coordinates were a blink: a wandering NPC that tries a blocked
    step holds the target tile for a frame. Further out, `wMapObjects` without a struct, unless `wObjectMasks` (event
    flags, time of day): standing on their own spot, facing their movement's way (`SpriteMovementData`), their tiles from
    `wUsedSprites`, colours from `OverworldSprites` or the object's own palette; variable sprites through
    `wVariableSprites`, a Pokémon's in palette 0. And the connected maps' people (the game loads only the player's map's
    objects): their object events from ROM (the attributes' events pointer, in the scripts' bank: 2 filler bytes, then
    warps 5 / coord events 8 / bg events 5 / objects 13 bytes each, every list after its count), placed through the
    connection's offset, out unless their event flag is set (CheckObjectFlag: -1 always, any other $FFxx never) or
    CheckObjectTime keeps them away (`wTimeOfDay` / `hHours`). The whole area's sprites are in VRAM (Crystal loads a map
    group's set), so New Bark's people stand in the strip from Route 29.
  - Scenes: TEXT while `wStateFlags` bit 6 (a text box, a menu); a battle from `wBattleMode` *or* `hVBlank` =
    VBLANK_CUTSCENE (1): DoBattleTransition's flashes and wipe run on the field before `wBattleMode` is set. OPTION:
    `wMenuSelection` = STARTMENUITEM_OPTION (5) after the START menu (same box corner tiles). OPTION loads palettes of its
    own, so the field is drawn in the colours it last had on the plain field (`gbFieldPal`; the START menu fades them).
  - Checked headless (fast-forward's skipped frames too: `wideskip 3`) on rev 1's save at night, and on a state pulled off
    the Thor: Route 29 east into New Bark Town (the sign up, 118 frames), a house in and
    out (FIELD / FADE only), the START menu, OPTION, SAVE's prompt, a wild battle from the grass to RUN and back - every
    map frame exact but those written mid-frame (opening / closing the START menu loads the font and copies the screen
    back in thirds); no NPC blinks in the strips standing or walking (the water / flowers animate, as in the middle).
- **Crystal Clear** (2026-11-05, GitHub #143; v2.6.0 and v2.5.11, Standard Colors and Color Filter, CRC32 `17617cde` /
  `58f40de1` / `5af9a949` / `fc33d126`; one row, `GB_CLEAR` set). No symbol file: `scripts/port_gen1.py` gives Crystal's RAM,
  what it can't vote on (the tables below) comes from reading the cart's code, the same in all four builds. What the hack
  rebuilt:
  - Colours: the tileset header holds the palette map's bank (D1E4: each tileset has its own) and pointer (D1E5, a byte
    before Crystal's), and the map is a byte of attributes per tile id, not a nibble (`gbPalAttr`; BG priority comes with
    it). The floor of the player's room (tile 1 of tileset $14) takes another tile's: `wDecoCarpet`'s, or with none down
    the table at 00:2E90 by D9C2, which picks the home too (poked to $1E, the stairs led to another room).
  - Blocks: `LoadMetatiles` doubles a block id in 16 bits, so all 256 are there, no wrap at 128 (`gbBlock`). Route 37's fruit
    trees are such blocks; with the wrap they read as flowers.
  - Connections: a map's blocks are LZ-compressed in ROM. The game unpacks a map into WRAM bank 6 at D000, and a
    connection's strip pointer is an address there (`GB_LZ_BASE`), so the connected maps go through `gbLzDecode`, kept in
    `gbLz` by ROM address. Every real map unpacks to width x height (`GB_OW_MAX` is plenty: the largest is 888).
  - Objects: `OverworldSprites` is 5 bytes a sprite with no palette (05:4B92, type at +4): palette 0, 1 for sprite E2, unless
    the object has its own, and from DE up they all walk (02:4189, 05:486A).
  - Checked headless on all four: Route 29 into New Bark Town (the sign up), the START menu, OPTION, a house, the stairs, the
    room (1733 of 1733 frames exact, those written mid-frame aside) and wild battles and back; on v2.6.0 also a carpet
    poked in and the room reloaded. A sweep over the cart's 488 maps (the game's own `warp` poked: wMapGroup at DA3A,
    wYCoord, D001 = -1, hMapEntryMethod $F1, wMapStatus D432 = 1, then four steps): about 148,000 map frames on each build,
    41 tilesets, every one exact but the wave transition below. No NPC blinks in the strips on 600 frames around New Bark;
    a connected map's strip in a 640 x 384 view is the map you arrive in, pixel for pixel but the people and the water.
    Not counted as a fault: a battle's wave transition (per-line SCX, LYOverrides) can't match the
    middle, for about 40 frames on the way into a battle indoors. Retail does the same (rev 1, Union Cave: 44 frames,
    `gbMarkTorn` sees no VRAM or palette write there), so `widecheck` lists them on every Crystal.
- **Polished Crystal** (3.2.3's normal build, CRC32 `f98367e4`; Keegan's profile, written for the libretro core on 2026-11-08,
  in the app since 2026-11-12). Its RAM isn't Crystal's shape, so it isn't a `GB_ROMS` row: `pk_wide_gb_knows` / `_configure`
  answer for its CRC with `pcWideConfig` (`pk_wide_gb_polished_tables.h`, written from the release's own `.sym` by
  `scripts/gen_polished_wide.py`, which checks the file's SHA-256: addresses only, no ROM bytes) and `gbPolished` set. What
  differs: metatiles and attributes decompressed into WRAM (`PC_METATILES` / `PC_ATTRIBUTES`, a byte of attributes per tile),
  34-byte object structs and 14-byte map objects, its LZ (`pk_wide_gb_polished_lz.inc`: connected maps' blocks, overworld
  sprites), its object palettes as loaded (`PC_LOADED_PALS`: a sprite whose palette isn't loaded isn't drawn) and the map
  objects in `pk_wide_gb_polished.inc`. Its battle transition sets `hVBlank` to 3, not Crystal's 1 (its 1 is the magnet
  train), so that's the pull-in's cue there. Checked headless on the new-game save (`native-capture/boot/polished_crystal.txt`):
  walking its route and grass, the START menu and OPTION, 2197 of 2265 map frames exact; the 68 others are a wild battle's
  wipe, as on Crystal, and report BATTLE_START. The faithful and debug builds and 3.2.0 - 3.2.2 have their own CRCs and RAM
  and aren't on yet: a `.sym` each through the generator, then a table of profiles by CRC.
- **Not yet**: the other languages' Yellows (their RAM is English's + 5 from 0xD018: a `GB_ROMS` row each, plus
  `MapHeaderPointers`), Red / Blue (Yellow's + 1 from 0xCF1B, SGB palettes: `palette` takes `sgbAttributes` there), Gold /
  Silver and Prism (Crystal's engine, their own addresses), Polished Crystal's other builds, battles past the edges, walking
  frames for Gen 1's off-screen NPCs.

## FREE FLIGHT and the scenic FLY (`pk_flight.c`, `Flight.kt`, `ScenicFly.kt`)

Retail FireRed rev 1 (`FLIGHT_FIRERED_REV1`, every address from pret's pokefirered built clean as firered_rev1 and its
ELF) and retail Emerald (`FLIGHT_EMERALD`, from pret's pokeemerald symbols - the `symbols` branch's `pokeemerald.sym`: the
build is retail byte for byte); the called functions are CRC-checked like FLY's. No ROM patch: the flight is the game's own pieces, driven after
every frame by `pk_flight_tick` (native, so mgba_dump runs the same code headless: `flighton`, `flight`, `flightroute`,
`flightinfo` in native-capture/README.md).

- **Moving**: LockPlayerFieldControls, then the player stepped with held movements written into its ObjectEvent
  (ObjectEventSetHeldMovement's fields: `movementActionId`, heldMovementActive / Finished, the sprite's data[1] / [2]),
  WALK_FAST (A held: WALK_FASTER). A held movement never checks collision, and with the controls locked nothing a step
  triggers runs (ProcessPlayerFieldInput: wild battles, trainers' sight, warps, the START menu). The camera follows the
  player as for any step, so CameraUpdate loads the next map across a connection and the game shows its map sign. Each
  step's tile is checked against GetMapBorderIdAt's rule, read from RAM (VMap + gMapConnectionFlags): the world's edge
  turns the bird instead.
- **Drawing**: the player hidden and its localId set to the camera object's (127): DoFlaggedGroundEffects skips an
  invisible object with that id, so no footprints, grass or ripples under the bird. The bird is the game's FLY bird
  (`gFieldEffectObjectTemplate_Bird`, CreateSprite; anims 1 / 3 = Red / Leaf riding seen from the side, flipped going
  east since the game's flies off west, 2 / 4 from behind going north), priority 1 (over BG1's tree tops), at a fixed
  place on screen: the camera keeps the player there (anchored from the player's sprite at take-off, checked constant).
- **Emerald's rider**: its bird (`gFieldEffectObjectTemplate_Bird`, 32x32) is one picture with no rider - its FLY seats
  the player's own sprite on it (the surfing graphics, ANIM_GET_ON_OFF_POKEMON_WEST, 8 px over the bird's centre). The
  flight makes that rider a sprite of its own (`PK_FLIGHT_RIDER_*`): GetPlayerAvatarGraphicsIdByStateId(SURFING),
  CopyObjectGraphicsInfoToSpriteTemplate into a scratch template (CreateObjectGraphicsSprite takes a fifth argument,
  which pk_call can't pass), CreateSprite, the pose, and the player's own palette slot (Emerald loads it by slot, so the
  template's tag finds none and CreateSprite picks 15: a white and green rider). In front of the bird (subpriority 0):
  the game's cut-in has the bird in front, all but the hat hidden, which over a long flight read as no rider at all. Its
  id + 1 in the bird's data[6], so a state brings it back. One side view only (flipped going east, the last side kept
  going north); DestroySprite as the player lands.
- **Turning (`flTurn`, `flCapture`)**: the bird banks toward the velocity the flight means: half the angle between the
  picture's nose and that heading, at most 35 degrees, eased, level again after a few still frames. While it's ridden
  the bird's (and Emerald's rider's) sprites are hidden and pk_flight_draw draws them into the finished frame instead:
  each frame their tiles and palettes are read from VRAM into one 64x64 picture (the game keeps animating them), its 8x
  scale built by Scale2x three times when the picture changes, and every pixel of the box around the bird looks that up
  through the turn and the mirror (RotSprite's way). The GBA's own affine sprites were tried first: their nearest-pixel
  turn broke the outlines and the rider's face up (seen on the RP6). The bird alone, coming in and leaving, is the
  game's sprite again.
- **The look on a diagonal**: the bird faces the D-pad's east / west, and only north alone shows it from behind. On a
  route it goes by where the next 24 steps go in all, with some slack: a mostly north staircase (Pallet Town to Viridian
  City: a step east every eight or so) took the side view at each jog and the back view between them.
- **Diagonal speed**: a staircase covers 1.41 tiles in two steps, 71% of the straight speed at the same step time. Where
  the trip isn't straight (the D-pad's two ways; the route's next 8 steps' net distance under their length) each step
  takes that share of the time, picked from the game's 8 / 6 / 4 / 2 frames a tile (WALK_FAST, RIDE_WATER_CURRENT,
  WALK_FASTER, SLIDE) by a running balance, so the average is exact (headless: 2.01 vs 2.02 px a frame).
- **The smooth camera (EXPAND)**: the game moves in tile steps, four ways, so a diagonal scrolls in a staircase - and with
  the step times mixed, unevenly. `pk_wide.c`'s `flyBegin` follows the camera from BG2's scroll at line 0 (continuous
  across map connections) and moves the view at the velocity the flight means (`pk_wide_flight`'s vx / vy: the route's
  way over its next 8 steps at the straight speed, the D-pad's, a landing's step), eased, pulled toward that camera (3%
  a frame moving, 12% standing); the drawn window and the bird's OAM entry move by the difference (put back after line
  159). Averaging the camera over the last steps (tried first) left a 2:1 or 3:1 staircase jumpy. Headless, Route 20 to
  Fuchsia / Pallet / Cerulean: speed 3.9-4.3 px a frame, frame-to-frame change at most 1.4 (whole-pixel rounding),
  never more than ~40 px off the camera. Without EXPAND the game's staircase stays. `WideCamera.flying` keeps the map
  signs (text while the controls are locked) from pulling the zoom in. The map's name sign (BG0, the top-left corner,
  20 tiles wide) would ride the offset: `flyLabel` paints the map back where it landed (drawRegion) and the sign where
  it'd be with no offset, its pixels found from BG0's tilemap and the line's registers (bgPixel), not the layer tags
  (mGBA keeps those only with the windows off; the Mac / Debian builds have none).
- **The shadow** is drawn into each finished frame (`pk_flight_draw`: a soft oval in three bands, larger, fainter and
  lower the higher the bird) and undone line by line first where the frame still holds it: mGBA redraws a line only when
  something on it changed, and a still line used to darken again every frame (the blink seen on the RP6).
- **The map grid mended (`flMend`)**: crossing into a map, the game copies the screen-sized area around the camera from
  the old map's grid into the new one (MoveMapViewToBackup), so near the crossing the new grid holds what the old one had
  there: its undefined squares (Route 21 above Cinnabar's corner, entered along Route 20's top row; Route 20's own edge
  after Cinnabar; Emerald's Route 116 beside Rustboro after Route 115, where the route stopped), a third map it was joined
  to, its border. Walking never reaches those corners; a diagonal flight does. For 30 frames after each new map (a frame
  can end part-way through Emerald's LoadMapFromCameraTransition, the new layout already in gMapHeader and the copy
  made on the next frame: a mend done once came too early), the map's own undefined squares get theirs back from its
  layout and its padding is what the connections fill, in their order, undefined where none reaches. Then the whole view
  is drawn again (DrawWholeMapView, once the game is at rest): what's on screen was drawn from the old map's metatiles,
  garbled where the two tilesets differ (Route 117 into Verdanturf) and unmatched by EXPAND until it scrolled away (the
  zoom pulled in, the margins showed the menu's bars). For the two or three frames the screen takes to catch up, pk_wide
  takes the camera as the RAM has it while flying (no menu opens with the controls locked) and draws the margins from the
  mended grid, rather than freezing them (the map and its people stood still, then jumped); `flyHold` is what's left if
  even that snapshot is torn. Headless, Emerald's routes
  from Route 114 to every town: no menu scene at all (they were 50-300 frames a crossing), FireRed's still none.
- **No-fly maps** (`FlightFns.noFly`, pk_flight's NO_FLY words): stand-ins the game only shows from outside - FireRed's
  SaffronCity_Connection (3.11), Saffron seen past its gatehouses, whose tiles come out scrambled up close (EXPAND then
  lost its match: zoom pulled in, wrong sides). The flight stops at its edge (the map's connections, `flNoFlyAt`) and
  ScenicFly leaves it out of the world, as it does every map's undefined squares (`Placed.holes`).
- **The HUD** closes its open panel (its map, where FLY was picked) as FLY or TAKE OFF starts (`hudCloseTick`).
- **State lives in the game's RAM**: the marked player (localId 127 + isPlayer) and the bird's sprite id in the player's
  fieldEffectSpriteId (the surf blob's slot: never both). A save state or REWIND taken in the air loads back flying:
  the tick adopts a marked player (or drops its own flight when the state had none). The app re-sends the same words every
  half second (`syncFlight`), so a resume state taken in the air is adopted as soon as the game is known.
- **Landing**: ring by ring around the player, the nearest tile inside the current map whose collision is 0, elevation
  not 15, behaviour on the record's `landOk` list (ground, grass, sand... never water, ledges, doors, warps) and with no
  one on it (MapGridGet*At / GetObjectEventIdByXY called once, when B is pressed). The bird flies there, comes down,
  the player is shown with that tile's elevation and the controls unlocked, and the bird leaves.
- **The scenic FLY** (`ScenicFly.kt`): the maps joined to the player's by connections (gMapGroups' headers, layout size,
  connection offsets) laid out on one grid; A* where a step between two maps must be one of their connections; the path
  pulled into staircases (`straighten`); flown with `pk_flight_route` at WALK_FASTER while EmulatorEngine runs the game at
  `SCENIC_SPEED` and WideCamera's `flightZoom` takes the widest EXPAND step; it lands on sMapFlyDestinations' heal
  location. A town not joined to the player's map (Saffron: its gatehouses are warps; the Sevii Islands) goes the classic
  way; Emerald's Littleroot by the player's gender, Ever Grande and the Battle Frontier outside (HEAL_WARP_EMERALD's
  special ids, as its Fly map exit). `ScenicFlyTest` replays every Kanto route over the world from the user's save's spot,
  and Emerald's from Oldale Town (every town but Sootopolis, reached only by diving). SCENIC is FLY STYLE's default;
  the first scenic landing asks once (`FlyStyleAsk`, the tip card's look where the tips go: KEEP IT / USE CLASSIC, the bar
  running out keeps it; `Prefs.flyStyleAsked`, also set by any FLY STYLE pick).
- **FLIGHT AREA** (GAMEPLAY, `Prefs.flightAreaId`, `FlightAreaMode`; `FlightArea.kt`): VISITED (default) keeps the
  flight to maps the player has seen, so it can't skip the story. The games keep no record of routes, so the app keeps one per
  save (`FlightArea.SeenMaps`, `filesDir/flight-area/<rom crc>-<save key>.txt`, the key a CRC of SaveBlock2's name, gender
  and trainer ID): every map stood on (a field job every half second, `Keeper.tick`), and, the first time a save is met in a
  run, what the save proves (`FlightArea.seed`): the towns GetMapsecType calls visited, the maps on a shortest way between two
  of them through non-town maps, and maps where FlagGet(0x500 + trainer id) is set for a trainerbattle object (the trainer id
  at +2, or +3 in the expansion's 1.12+ TrainerBattleParameter: voted per ROM by where the text pointer's top byte sits) -
  only next to a map already in, so a hack that numbers its trainer flags otherwise can't open a far map (Quetzal: none, its
  flag check is a region-badge one). pk_flight gets a bit per map (`pk_flight_set_area`, `MgbaCore.pkFlightArea`; kept
  across configure / reset), `flNoFlyAt` turns the bird at a connection to a map not in it and `pk_flight_area_turned` lets
  the app say why; `ScenicFly.read(allowed)` plans over those maps. ANYWHERE sends none and pauses RetroAchievements from the
  first take-off (`RetroAchievements.setFlightAnywhere`) until it's switched back. `FlightAreaTest` checks the maps and
  trainers read on every flight hack's ROM and the seeds on FireRed / Emerald's saves; mgba_dump `flightarea G.N ...`.
  The GBA libretro core (and so pokedaisy-linux) has it too, for its TAKE OFF (retail English FireRed rev 0 / 1, Emerald):
  `libretro/pd_flight_area.c` is the Keeper in C - keep the two in step - over `pd_flight_gen.h`'s `area` (the flight / FLY /
  HealWarp records, `libretro/gen_flight.py`, `--check` in libretro.yml), its seen maps in the frontend's save directory
  (`pokedaisy/flight-area/<rom crc>-<save key>.txt`, the app's format), the row in the companion's SETTINGS
  (`pd_settings.flightAnywhere`, `host.hasFlightArea`). There ANYWHERE can't pause achievements: the frontend runs them.
  The core flies the scenic FLY and FLY ON too (`host.flightSupported`: the compact SETTINGS list then has FLY STYLE /
  FLIGHT AREA / FLY ON by QUICK MENU): FLY ON is the profile's words with `PK_FLIGHT_VEHICLE` set (`flight_configure_words`,
  sent again when it changes, mid-flight too); a FLY pick under SCENIC routes with `linux/flight.c`'s `flight_here` /
  `flight_destination` / `flight_route` over the config's flight record and FLIGHT AREA's maps (`scenic_job`, the 3DS's
  flight_jobs), else the game's own FLY (no route, surfing, a config without the record: FireRed rev 0). MAP's TAKE OFF /
  LAND is `PD_ACTION_FLIGHT` (`host.flightReady` / `host.flying`).
- **Clouds** (pk_flight.c's flDrawClouds): while up, round-puff cumulus (flMakeClouds: clusters of domes on a wrapped 128 x 128
  map, 2 x 2 px a texel, read bilinear) drawn into the finished frame over the ground and shadow, under the bird: a white core
  and a thinner rim, bluer where the cloud thins below, at most 70% opaque, as thick as the bird is high. They slide by at
  1.15x the camera the frame shows (the map's BG2 scroll as it was drawn, `pk_wide_bg2_scroll`: it moves as the ground
  does, staircase and all - the flight's own speed, or gSpriteCoordOffset, which is the next frame's, shook them against
  the ground on diagonals) plus EXPAND's smooth-camera offset times 0.15, and a slow wind; mgba_dump's `flightinfo` prints
  their offset and the scroll, so a diagonal's frames can be checked: against the ground they only ever drift one way. With
  EXPAND the clouds go over its whole picture after it's made (`pk_flight_draw_wide`, the bird's pixels masked out), else
  into the GBA frame; there the draw's undo covers the whole frame (its buffers grow to it). Taking off from a bike gets off it first (`offBikeThen`: the game's own SELECT path).
- **FLY ON** (MAKEOVER, `Prefs.flightVehicleId`, `FlightVehicle`, the PK_FLIGHT_VEHICLE word): an air balloon, a helicopter,
  a zeppelin or a small plane in place of the bird, our own pixel art from `scripts/gen_flight_vehicles.py` at the games' angle (from
  the side and a little above: spinning discs for rotors and propellers, roofs, wings and fins spread both ways; parts shaded by a normal lit
  from the top left over a colour ramp, coloured outlines; rerun it after changing the art) -> `pk_flight_vehicles.h`.
  flCapture draws the vehicle's frame for the whole flight (the bird's hardware sprite and Emerald's rider stay hidden), the
  player's own overworld sprite (hidden while flying) over its SEAT pixels, then its FRONT pixels over them; the sprite held
  on the player's standing frame the way they fly (`flPosePlayer`: ANIM_STD_FACE_WEST, mirrored going east; the balloon's
  `upDown` takes FACE_NORTH / SOUTH going up / down; their walking frames bobbed in the seat), put back on landing; a 32 x 32
  hero (Unbound's) is seated centred like a 16 x 32 one. Each has its own lean (the balloon none) and no back view.
  A FireRed-engine bird in a palette of its own (`flBirdNoRider`: Unbound's tag 0x11A2) has no rider in its pictures: it
  flies like Emerald's (one side picture), the player seated on its back (flSeatOnBird). Changing it mid-flight only swaps the picture (configure
  compares the words without it). mgba_dump: the word after the bird palette's tag in `flighton`.
- **Which games** (`flightAllowed`, `FieldItemFns.flight`; the code CRCs still decide): retail FireRed rev 0 / 1, LeafGreen
  rev 0 / 1, Emerald, Ruby / Sapphire rev 1 / 2 (pret's `symbols` branches; R/S ride as Emerald, their save blocks in place,
  gSpriteCoordOffsetY its own s32 - `spriteOffsetY` - and the covering ground effects turned off on the hidden player each
  step, which FireRed / Emerald skip for the camera's localId by themselves), the randomized retail builds (retail's code;
  the Poller's `retailRom` covers them), and three hacks by their hash: Radical Red, Unbound (also French) and Glazed. CFRU
  hooks DestroySprite / PlaySE (their CRCs carry the hook's target) and sets the FLY bird's palette through its own loader in
  CreateFlyBirdSprite (`birdPalette` called with `birdPaletteTag`: Radical Red's player tag 0x1100, Unbound's bird 0x11A2 -
  palette 0 drew the bird in someone else's colours). Unbound XORs gMapGroups' group pointers (`mapGroupsXor`, as
  DexDetails reads them) and its FLY lands on its own fly spots (`FlySpots`: u16 section, num, group, u16 x, y), which
  `ScenicFly.destination` takes before heal locations. Glazed is Emerald's code: only its map group sizes differ. A new hack:
  its world flown headless first (free flight, then mgba_dump `flightroute` sweeps with `wideon`, counting frames whose
  margins didn't match - a randomized or edited build needs `wideon 60 <retail CRC>`), then a record and its kind in
  `flightAllowed`.
- **Every language** (40 carts): `scripts/port_field_items.py`'s port_flight maps the English record (functions by code, RAM
  through the literal pools, gFieldEffectObjectTemplate_Bird through gFieldEffectObjectTemplatePointers' pool, gMapGroups /
  sHealLocations through theirs) into `FieldItemsPortsGen.kt`, checking the bird template's callback, every map group's
  headers and the heal locations' bytes. MapGridGetCollisionAt and MapGridGetElevationAt start with the same 32 bytes, so
  a code match put both on one function: the collision one is placed at English's distance from the elevation one and
  compared past their pools (`ScenicFlyTest.everyLanguageCartsFlightMatchesItsRom` keeps them apart).
- **The FireRed rev 0 hacks** (`FLIGHT_ULTRA_VIOLET` ... `FLIGHT_GS_CHRONICLES`, firered.toml): rev 0's record with each
  hack's map groups and heal locations read through rev 0's literal pools, its own DestroySprite CRC (CFRU hooks it), the
  Saffron stand-in kept only where map 3.11 is still FireRed's. The ones running as FIRERED are picked by config
  (`fireRedHackFlight`) and let in by identity (`FIRERED_HACK_FLIGHTS`), Rocket Edition and Ash Gray (both builds) too
  since 2026-11-11. Ash Gray has gMapGroups of its own (rev 0's pool word points there); Rocket Edition hooks DestroySprite. Emerald Legacy (`FLIGHT_EMERALD_LEGACY`)
  is Emerald's record mapped into its rebuild by code; its GetPlayerAvatarGraphicsIdByStateId returns a u16.
- **The player is inanimate while up** (`flHidePlayer`: ObjectEvent.inanimate with invisible): the flight's fast movement
  actions start the "go faster / fastest" step animations, which no player table has (FireRed's has 13 entries: anim 13 / 17
  are past its end, read from EWRAM as commands). Rocket Edition reset on long flights from it (AnimateSprite's command jumped
  through a bad pointer into the BIOS); retail had harmless bytes there. A state from before the fix is made inanimate on adoption.
  Debugging a reset like it: single-step whole frames (pk_flight_tick at each frame's end) into a big ring of PCs and stop when
  gMain.callback2 changes; the last ROM code before the BIOS names the culprit.
- **Emerald Kaizo** (`FLIGHT_EMERALD_KAIZO`, Emerald's record under its own name for `flightAllowed`): its classic FLY's warp
  crashed from the field, so `FieldItemFns.classicFly` is false there: FLY flies over the map whatever FLY STYLE says, and a
  town with no route answers `FlyResult.NO_ROUTE` instead of falling back.
- **A blank bird** (Ghost Grey blanked its graphics): flCapture finds nothing in the bird's frame and flies the player in
  the balloon (`flBirdBlank`). **Greyscale games**: when every colour of the player's own sprite palette is a grey, a
  vehicle's colours are each taken to the nearest of those greys by brightness (`flReadGreys` / `flGrey`).
- **The pokeemerald-expansion hacks** (Emerald Imperium, Lazarus, Heart and Soul, SoulGold's five builds; emerald.toml):
  their ObjectEvent keeps the sprite id at +0x23 (`objSpriteId`) and graphicsId as a u16. Mapped from pokeemerald-expansion
  1.13's build (the scratch mapper: masked code, then shapes - CreateSprite from CreateFlyBirdSprite's 0xFF / 0xB4 / 1 call,
  LoadPlayerObjectEventPalette right after it, called with the player's gender: `birdPaletteTag` 0xFFFF), and every grid
  read checked by calling it on known tiles: a match can land past a function's first instructions (Heart and Soul's
  MapGridGetMetatileBehaviorAt was mapped two `lsls` late and read tile 0,0 everywhere, so no landing ever came). SoulGold's
  metatile ids are 11 bits with one collision bit (`mapGridUndefined` 0x7FF, which pk_flight's mend and ScenicFly's holes
  use) and gcc split DestroySprite: its callers test inUse and call the body. Its v1.2 builds came from v1.1.4 by masked code
  and the pool words of the matched functions. Lazarus, Heart and Soul and SoulGold have no rider graphics: `riderTemplate`
  0 seats the player's own sprite on the bird. Heart and Soul's bird template has no callback (`spriteDummy` is written after
  CreateSprite) and its SaveBlock1 position sits at +4 (`saveBlock1PosOff`, for ScenicFly's start).
- **Seaglass and Too Many Types 2** (FLY came with the flight; `FIELD_ITEMS_SEAGLASS` / `_TMT2`): read the expansion version
  off the ROM's RHHEXP block (0x204: `RHHEXP 01 <minor> <patch>`: Seaglass 1.9.2, Lazarus 1.9.4, TMT2 1.12.3, SoulGold 1.15.2).
  Seaglass is Lazarus's build: Lazarus's verified table ports by masked code, then by callers (each BL to a function from a
  caller that ports, the BL at the same place: votes), then pool words. TMT2 matched no plain build of its tag, so each
  function was matched by its mnemonic sequence (registers and pool values left out, difflib ratio over every BL target)
  and then proved: grid reads called on known tiles (elevation 3s, collision 0 / 1, metatile ids, behaviour 0 / sand / sea),
  CreateSprite by creating the bird, gPaletteFade through BeginNormalPaletteFade's pool, the lock trio beside
  ArePlayerFieldControlsLocked. Its CB_ExitFlyMap (found by calling both heal-warp functions) gives the town table and the
  specials, read straight from its code.
- **R.O.W.E. and Super Mariomon 1.5.x** (`FIELD_ITEMS_ROWE`, `_MARIOMON_V1_5(_2)`): pokeemerald forks, so retail Emerald's
  whole verified table ports (masked code, callers, pool words; R.O.W.E.'s RAM checked against the symbol table its ROM
  carries at 0x08FB0670). Both load the bird's palette before CreateSprite by an index (0x1A): `birdPaletteTag` with bit 16
  calls the loader first and keeps the slot CreateSprite picks from the template's tag. Their CB_ExitFlyMap is where the FLY
  rules are: find it as the one function calling both SetWarpDestinationToHealLocation and ...ToMapWarp, then read it -
  R.O.W.E.'s is a compiled switch (a jump table of 0x9E cases: `movs r0, #heal; bl` per case) decoded into
  `HealWarp.special`. A badge flag that moved (R.O.W.E.'s FLAG_BADGE01_GET 0xCD7) shows at retail's trainer-card badge check.
  Mariomon's builds share a kind and code: `fieldItemsCandidatesForGame` lets the CRCs pick, like SoulGold's.
- **Quetzal Alpha 9** (`FIELD_ITEMS_QUETZAL(_ES)(_V1)`): agbcc code (`adds rX, rY, #0` moves), so retail Emerald's table is
  the source, not an expansion or modern build. Its field-move gate is its own (`flagGet` = its region-badge check, `badgeFlag`
  = FLY's entry in its table), its ObjectEvent keeps the sprite id at +0x23 (with the wrong offset the held movement was set
  but never ran: the flight drove another sprite), its Fly warp is a function of its own (Hoenn's table and Emerald's
  specials replayed; its other regions not). Its saves: the Spanish one (Jagged Pass) loads in every Alpha 9 build.
- **Emerald Rogue** has no FLY: its party menu refuses it, so `flightAllowed` never sees a table there.
- **Following Pokemon** (the expansion's OW followers: Imperium, Lazarus, Heart and Soul, SoulGold, Seaglass): left on the ground, the follower was lost on the next map.
  The game's FLY removes it in its return-to-field code (`if (gFieldCallback == FieldCallback_UseFly) RemoveFollowingPokemon();
  else UpdateFollowingPokemon();`), which is where both are found: `followerRemove` is called at take-off, `followerUpdate`
  once down (it spawns the follower hidden on the player's tile; it walks out at the first step).
- **Overlapping layouts**: a loop of connections that doesn't close (Heart and Soul's Violet side) puts two maps on the same
  tiles. ScenicFly keeps the first placed, drops a town whose landing tile another map holds, and crosses an edge only where
  the game's first connection that way (fieldmap.c's order) is the map the layout shows, placed where that connection puts it.

## PC, FLY and WILD ENCOUNTERS on the Game Boy (`GbField.kt`)

The GBA's ways in (FieldItems.kt's calls between frames, ScriptContext_SetupScript, sWildEncountersDisabled) have no
counterpart in Gen 1 / 2, so each game is driven the way its own code already goes, from pret/pokeyellow's and
pret/pokered's and pret/pokecrystal's sources (`$DECOMPS/pokeyellow`, `pokered`, `pokecrystal`: their builds are the retail
carts, so their `.sym` files give every address; the German / French / Italian / Spanish Yellows' come from
`scripts/port_gen1.py`, the PC routine and the visited-towns flag by their code bytes). Gen 1 is one `GbField.Gen1` class
with a config per cart (`GB_FIELD_YELLOW`, `_DE`, `_FR`, `_IT`, `_ES`, `GB_FIELD_RED_BLUE`: Blue's code is Red's at every
address used); Crystal rev 0 / rev 1 / Australia share one. The code each action leans on is checked in the cart first
(`GbField.codeMatches`, `GbFieldTest`: the bytes include the RAM addresses the code uses, so each Gen 1 cart matches its own
config and no other).

- **Crystal** has a queue for a script the overworld runs once the player stands free: `RunMemScript`, from
  `PlayerEvents` (wMapReentryScriptQueueFlag, its bank and address, D45C-D45F in WRAM bank 1). The PC queues the standard
  `PCScript` (2F:41A9: opentext, special PokemonCenterPC, closetext), FLY sets wDefaultSpawnpoint and queues the field
  move's own `FlyFunction.FlyScript` (03:4AA3). A step that's under way finishes first (headless: queued mid-step, the
  PC opened as the step ended). `CheckWildEncounterCooldown` lets a step roll only once wWildEncounterCooldown counts
  down from 1 or 0, so holding it at 5 stops random encounters and nothing else.
- **Gen 1** has no queue. Its PC is the Pokémon Center PC tile's routine past its facing check (18:66F4: auto text box,
  then PrintPredefTextID of the PC's text), called through the game's `Bankswitch` - the predef text table points into
  that bank; called from bank 0 it printed "33 error." - from the overworld loop right after its frame wait
  (OverworldLoopLessDelay + 3): `pk_gb_field.c`'s `pk_gb_call_at` steps the core to that PC, pushes it as the return
  address and sets PC / A / B / HL, giving up after two more frame waits (the loop isn't running). FLY writes what the town
  map's A leaves for the loop (wDestinationMap, BIT_FLY_WARP, BIT_USED_FLY) plus (Yellow) Func_1510's Pikachu hiding; the loop
  then runs HandleFlyWarpOrDungeonWarp. Encounters roll a random byte against wGrassRate / wWaterRate, so 0 never meets
  anything; they're zeroed every frame out of battle (wGrassRate doubles as a link battle's enemy name) and put back
  from the map's wild data in ROM (LoadWildData's walk of WildDataPointers) when the setting is ON again.
- A queued action the game doesn't take within `GbField.TAKE_FRAMES` (a menu opened meanwhile) is withdrawn and the tap
  answered "not now". Game Boy field jobs count as busy unless `pk_wide_gb.c`'s scene says FIELD; a cart with no EXPAND
  row (no scene: the European Yellows, Red / Blue) goes by Gen1's RAM checks alone - the walk counter, wFontLoaded bit 0
  (a text box), wJoyIgnore, the scripted-movement and warp flags. Its START menu sets none of them, but the loop isn't
  running then, so the PC's call gives up and a flight is withdrawn.
- Headless: mgba_dump's `gbpoke` (WRAM bank writes), `gbpeek` and `gbcallat AT FN A B HL` (the app's `pk_gb_call_at`).
  What was run is in docs/ROM_FEATURES.md's note 13.
- **Yellow Legacy / Crystal Legacy** (`GB_FIELD_YELLOW_LEGACY`, `GbField.CrystalLegacy`, from their own .sym files): Yellow's and Crystal's ways in at
  their own addresses; `GbField.CrystalEngine` holds Crystal's logic for both. `gbcallat 242 3e87 0 18 6733` opens Yellow Legacy's PC.
- **PureRed / PureBlue** (`GB_FIELD_PURE_RGB`, from pureRGB's pokered.sym): `GbField.Gen1` with its own banks (`pcBank` 0x33,
  `wildBank` 0x2C) and WRAM bank 1 addresses. Its overworld loop has no frame wait of its own at OverworldLoopLessDelay (the
  wait is OverworldLoop's `rst DelayFrame` just before it), so the PC is called at 0x03D7: `gbcallat 03d7 3297 0 33 46b5`.
  FLY is Red's flags (its town map's pick sets the same: wDestinationMap, BIT_FLY_WARP, BIT_USED_FLY).

## Overlays

SCREEN > OVERLAY (both screens' Settings; `Prefs.overlay`, a `OverlayChoice.key`: `""` none - the
default -, `builtin:DAISY` / `builtin:BEZEL`, `user:<folder>`) draws a frame around the game on the top
screen. The code is `overlay/Overlays.kt` (plain Kotlin: parsing, the window finder, placement, the
built-in frames, storage + import - ui-preview compiles it, `OverlaysTest` covers it),
`overlay/OverlayBitmaps.kt` (decoding) and `EmulatorView` (drawing).

- **Nothing third-party is bundled**: the common console borders show the console maker's logo and
  shell, so the app ships only its own two frames and players import what they already have. Docs
  and the site may point at libretro's overlay collections in general, never at a branded file, and
  screenshots only ever show the built-in frames.
- **Built-in frames** (`BuiltInFrames`): DAISY (the launcher icon's blue, the website's dot grid, a dark
  screen surround, the PokeDaisy mark in the left margin) and BEZEL (plain charcoal). Drawn at the game's
  own pixel size for the view they're shown on and drawn at a whole-number scale (`scaleFor`: the
  largest that leaves 8 frame pixels at the sides, 4 above and below - 6x on the Thor's 1920x1080,
  7x for a Game Boy), so they're as crisp as the game; the game loses a little size (6x instead of 6.75x).
- **Imports** (top-screen Settings > OVERLAY > IMPORT FILE, several files at once): RetroArch's overlay
  `.cfg` (`overlays = N`, `overlayK_overlay` = its image, `overlayK_viewport` = "x,y,w,h" in 0..1
  where the game goes; touch-button entries ignored), a `.zip` of those (images found by their path
  inside it, `__MACOSX` / dot files skipped, 96 MB unpacked at most, 64 overlays per import) and bare PNGs.
  With any `.cfg` in an import, only its images come in: a pack's other PNGs (button art, previews) aren't overlays
  (they once each became one); a PNG is an overlay by itself only when no `.cfg` was picked with it.
  A `.cfg`'s image is looked for among the picked files, then beside its real path (with All files
  access), else the page asks for it (PICK THE IMAGE). Each overlay becomes a folder under
  `<external files>/overlays/` - its PNGs renamed `0.png`, `1.png`... and the `.cfg` rewritten to match,
  plus `pokedaisy_name`. PNG only (its window is its alpha); nothing is decoded at import.
- **The window**: the `.cfg`'s viewport, else `OverlayWindow.find` on the decoded image - the see-through
  region (alpha < 128) around the centre, as a rectangle, if it's at least 4% of the image and fills 60%
  of its box (Nosh's GBA bezel in libretro/overlay-borders has no viewport; it's what RetroArch users
  set by hand). No window: the image is a background, drawn behind the game.
- **Placement** (`OverlayGeometry.layout`): the image fitted to the view and centred, filling it when
  their shapes are within 4% (a 16:9 border on 16:9), the game fitted inside the window at its own shape
  (ASPECT > STRETCH fills the window instead). A pack's several overlays (landscape / portrait): the
  one closest to the view's shape (`StoredOverlay.layerFor`). Imports are decoded at the smallest power-of-two
  sample still at least the view's size (a 4K border is 1920x1080 on the Thor) and scaled smoothly; the built-in
  frames are scaled nearest.
- **Drawing** (`EmulatorView.FrameRenderer`): the overlay is a second texture, blended over the game
  (premultiplied, `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`) or drawn before it when it's a background; the game's
  quad is its place in the window, so SHADERS' prescale and the companion's grid cell (`onGamePixel`)
  follow the game's real size. With an overlay `GameStageLayout` gives the view all the room under the
  status bar. Off on a phone held upright (`topAligned`), where the game is its own shape across the top.
- **Previews**: `gradle render -Ponly=overlay` in ui-preview draws each built-in frame (Thor, Game Boy,
  half-screen side panel, 4:3) and an import of a drawn wooden frame (window found from alpha) around a
  stand-in game, with the same placement code; `settings-overlay*` / `*-settings-overlay` are the pages.

## Co-op joined battles (Emerald)

Retail English Emerald only (BPEE, SHA-1 `f3ae0881...`, the `RETAIL_SHA1S` entry). A player near a partner's wild battle,
two-trainer battle or one-trainer battle (2+ Pokémon) can be asked to join it, and a story battle (gym leader, rival, a team's
boss) can be joined in every save by a partner standing at the same spot. A joined battle is **Emerald's own link battle, run on
both devices**: the host's game is the link master (multiplayer id 0, battler 0) and the partner's game its link partner (id 1,
battler 2, its own `Player` controller), so each player sees the battle from their own side with their own game's menus,
drawn at their own screen's scale. The two games carry link.c's blocks over the co-op connection instead of the serial cable
(the bridge, below); the app draws nothing over the battle except the join prompt, the waits and the notices. Everything is
behind `FeatureFlags.coop`.

Code, by layer:
- `pk_coop.c`: the retail check (below). `pk_coop_field.c`: what the overworld is doing (the join rules' signals).
  `pk_coop_after.c`: the after-battle changes through the game's own functions. `pk_coop_link*.c`: the link bridge (its own
  section). `pk_coop_lock*.c`: the story lock (its own section). All plain C over `struct mCore*` (no JNI), so
  `native-capture/mgba_dump` compiles the same files in and tests them headless; `pokedaisy_jni.c` is the thin JNI layer
  (`MgbaCore.pkCoop*`), ticking `pk_coop_lock_tick` / the link's state pump after frames of the **main core only** (never `rg`).
- `companion/data/coop/`: `CoopBattleWire.kt` (the messages), `CoopJoin.kt` (the join rules and the offer / prompt state),
  `CoopLinkFlow.kt` + `CoopLinkBattle.kt` (the flow and the block pump), `CoopAfter.kt` / `CoopOpQueue.kt` (the after-battle
  changes), `CoopBattle.kt` (the partner's Pokémon checked), `CoopCatchStore.kt`. `CoopBattles.kt` (the glue) and
  `companion/ui/CoopBattleUi.kt` / `CoopBattleOverlays.kt` (the join prompt, the waits, the notices).

**Generated tables.** `scripts/gen_coop_emerald.py` reads the pinned pokeemerald ELF / ROM (built with agbcc, byte for byte
retail) in the `pokedaisy-decomp` image and writes `app/src/main/cpp/pk_coop_emerald.h`: every address, struct offset, the
first-32-byte CRC32 of each function co-op reads, calls or patches, the few code sites the bridge patches, and the bridge's
Thumb code (`scripts/coop_link_emerald.s`, assembled) as a `static const` array (the header is included by more than one
file, so nothing in it may be a plain definition). Rerun it, and `gen_coop_story.py` for the lock, after any change to the
sites or the assembly.

**The retail check** (`pk_coop_install`). Changes nothing: it compares the first 32 bytes of every function the join rules,
the wild roll and the after-battle steps read or call with retail Emerald's (a hack, another language or a cheat on that code
refuses it; the lock's and the bridge's own head patches don't count) and says whether this game is one co-op can run in.
`pk_coop_battle_starting` (a passed check and Task_BattleStart running) is what the join rules ask. Gate the check on the
retail SHA-1 as well, not on it alone: its 32-byte code CRCs also pass on Emerald Kaizo and Glazed (retail edited in place),
whose data differ. The check puts nothing in the ROM, but the bridge's code block (`0x08FFD000`-`0x08FFDFFF`) must be free of other ROM
blocks (`pk_coop_link_install` refuses otherwise): a PLAYER LOOK or TEXT LOOK block there makes the device not ready, and a joined
battle needs both players' PLAYER LOOK and TEXT LOOK off anyway (they change battle code and art the other game doesn't have). The
app refuses to switch them on while a battle runs.

**Who can join.** Joinable: ordinary wild battles, two-trainer battles and one-trainer battles with two or more Pokémon
(kind 3), and story battles (kind 4, below). Never: a one-Pokémon trainer, a story trainer as an everyday join (the host
turns kind 3 into 0 for `CoopStory.isStoryTrainer`), scripted or static wild battles, the Route 101 rescue, Safari, link,
Frontier. No prompt is shown for those. The partner must be *nearby* (same map group and number, Manhattan distance at most
NEARBY RANGE, `Prefs.coopNearbyRange`: 2 / 4 / 8 / 16 / 32 steps, default 8) and *free* (on the field with the controls free, or
on the START menu's top level: `pk_coop_partner_free`). The prompt lasts 30 seconds; no answer is a decline. Once a battle runs, a player who is slow to choose is
never timed out while the link is up (the 60 seconds are for a dropped link only). Each side, while it holds its own battle start, answers an
incoming offer with BUSY, so two battles starting at the same moment both go on single.
`pk_coop_join_kind` (only while `pk_coop_battle_starting`): 1 = an ordinary wild battle (no TRAINER flag, `gMain.savedCallback`
= `CB2_EndWildBattle`, none of FIRST_BATTLE / legendary / Safari / Pyramid / roamer / link / Frontier flags), 2 = two trainers
(TRAINER + TWO_OPPONENTS, `CB2_EndTrainerBattle`, both opponent ids set), 3 = one trainer with two or more Pokémon (flags exactly
TRAINER or TRAINER | DOUBLE, `CB2_EndTrainerBattle`, `gTrainerBattleOpponent_A` in 1..854, the record's `partySize` >= 2), else 0
(`pk_coop_join_trainers` gives the ids, [A, 0] for kind 3). `pk_coop_partner_free` = the free field or the START menu's top level:
the menu's task (`Task_ShowStartMenu`) running with `gMenuCallback` = `HandleStartMenuInput` (the callback alone stays stale
after the menu closes). Headless: mgba_dump's `coopjoin`, `coopfree`; tests `join_*`, `free_*`. The Route 101 rescue's
FIRST_BATTLE flag is set after its transition (no `Task_BattleStart` there), so `join_rescue` checks the flag rule on a wild
battle with the flag poked.

**Protocol** (`CoopBattleWire`, types `0x0200`-`0x02FF`, little-endian, every message after STATUS starts with a host-chosen
non-zero `u32 battleId`). `0x0204`-`0x020E` were the lockstep replica's messages: never reused, the decoder refuses them.

| Type | Name | From | Carries |
| --- | --- | --- | --- |
| 0x0200 | STATUS | both, on change and each second | flags: free, ready, and the story event stood at (battleId 0) |
| 0x0201 | OFFER | host | kind (1 wild / 2 trainers / 3 one trainer / 4 story), the trainer ids, the first foe's species, the host's name, gender, OT ID |
| 0x0202 | ANSWER | partner | accept; up to 3 Pokémon (100 bytes each), name, gender, OT ID, their party slots |
| 0x0203 | CANCEL | both | reason: TIMEOUT, DECLINED, ENGINE, LINK, BUSY, CLOSED |
| 0x020F | RESULT_ACK | partner | the catch the host named in CAUGHT_MON is in the partner's game, saved |
| 0x0210 | LINK_BLOCK | both | one link block of the bridge (`[u8 kind][u8 seq][u16 size][bytes]`) |
| 0x0211 | LINK_READY | partner | the partner's game is in the link battle's setup: the host's may begin |
| 0x0212 | CAUGHT_MON | host | what the battle caught: the host's own ball (nothing to send) or the partner's, with the Pokémon's 100 bytes |
| 0x0213 | STORY_STATE | both | a story join's progress (`pk_coop_story_*` state 0-5), or GO (6) from the host |

Everything received is untrusted: partner Pokémon pass `CoopBattle.check` (in ANSWER, and again when the host accepts); a caught one
must carry the partner's own OT ID; a block whose game is not in a link battle (late after the end, early before the start, a
stale seq) is dropped before anything touches RAM. A paired partner's host chooses what the battle is: it can name trainer ids
1..854 (the prize and the trainer flag), and the EXP / EVs / moves of what a battle does to the partner's Pokémon come from the
partner's own game, never from the network. Nothing from the network reaches game memory except through the checks and the
core's own `pk_coop_*` calls (`CoopSafetyTest`).

**After the battle** (`CoopLinkFlow`, `CoopOpQueue`, `pk_coop_after.c`, `pk_coop_link_end.c`): on a free field frame, in game RAM,
never through the save file; each game does its own (the other's is never written). The prize is what a two-trainer battle pays,
never doubled (`pk_coop_prize`, the ROM's own table); a single trainer's is that trainer's own (`pk_coop_prize_single`: x2 when the
trainer's record is a double battle). Trainers' flags are set on both games (`pk_coop_set_trainer_flag` reads the flag first and
answers 2 when this game already had it: `CoopOpQueue` then skips that trainer's prize). A player **blacks out only when no
Pokémon in the whole party can fight** (not just the three they sent): the game's own white-out runs. A Pokémon that grew a level
evolves in its owner's game (`pk_coop_evolve`) after a won battle, and the in-game save is made after a `SaveBackups` copy
(`CoopOpQueue`). Balls: with two wild Pokémon up a ball is refused, as in the game's own double battles; whoever catches the last
one keeps it, in their own party (else PC), with their own OT. The host keeps a partner's catch (`CoopCatchStore`) until the
partner acknowledges it saved (below). A level-up's "learn a move?" prompt and an evolution on the partner's side happen in the
partner's own game after the battle.

**In the game** (`CoopBattles.kt`): `CoopBattleGlue` holds the `CoopLinkFlow` and is the engine's `CoopFrameHook`
(`EmulatorEngine.coopBattles`, null for anyone else): asked every tick for the next frame's keys (always the player's own; null =
no frame, the game is held: a battle start waiting for the partner's answer, or a stop in flight; also called from the held branch,
so timers and link waits tick while no frame runs), `afterFrame` after each. It sends STATUS on change and every second (free =
`pkCoopPartnerFree`, ready = `pkCoopInstall` and `pkCoopLinkInstall` say yes, probed once a second and only on a free field / START
menu), takes the link's messages on the link's thread (a LINK_BLOCK straight into the flow's thread-safe queue, everything else
hopped into an emu job), feeds the flow the nearby spots (own `pkPartnerSelf`, the partner's last `CoopPresence.lastReceived`) and
sets plan 5's guards after every tick (`Hold.BATTLE` = the host's hold, `StateBlock.BATTLE` = busy). The activity attaches it only
for retail English Emerald's SHA-1 with the flag on (`CoopBattles.mayHook`), and also hands presence its `CoopPresenceChannel` (type
0x0100). Overlays (`companion/ui/CoopBattleOverlays.kt`) are drawn in the HUD's view, HUD on or off, and take the game's buttons
like the QUICK MENU (`coopOverlayTakes`, `coopMotion`, the touch pad): only the join prompt and the host's wait take keys. While
busy RESTART, SOFT RESET, SAVE / LOAD / UNDO STATE, REWIND (the engine keeps the ring off while states are blocked), PLAYER LOOK /
TEXT LOOK / RIVAL LOOK, REUSABLE TMS and CHEATS are refused with a notice, and RetroAchievements idles
(`RetroAchievements.coopBattle`). `pauseGame` / `coopStop` call `CoopBattles.stopNow()` before any suspend / sleep state: the
game is put back first (a link battle left mid-way, the other side told CANCEL(CLOSED)); a stop that could not be confirmed
(the emu thread did not answer) writes no resume state at all (`coopLost`, the next launch boots from the untouched save file).
Cheats can't be switched on while co-op runs (`CoopSession.refusesCheats`). `CoopLock.releaseGuards()` closes the lock for good
(a late `onFrame` takes nothing; `close()` still uninstalls) and `CoopGuards.reset()` runs when a game starts. REUSABLE TMS /
PLAYER LOOK wait in the engine while `CoopGuards.battleActive()`; after they run `CoopLock.requestReinstall()` puts the story
hook back on the next frame. `CoopBattles.resume()` replays the link state seen while stopped.

**Rulings** (plan 4's, kept through the link rewrite): the prize is what a two-trainer battle pays, no doubling; a player blacks out only
when their whole party is down; PLAYER LOOK and TEXT LOOK must be off; a single trainer with two or more Pokémon can be joined
(its prize is the trainer's own, one-Pokémon trainers have no co-op).

**Headless checks.** `scripts/coop_headless_check.sh` builds `mgba_dump` in the `pokedaisy-capture` image and runs every
`native-capture/coop/*.txt` on the co-op test save (`boot/emerald_coop.txt` first), failing on any `FAIL`, an
`unknown command`, or a missing `# want:` line. It needs the player's own files, never committed (the partner's save is the second
core's, for the `pair` tests):
```bash
EMERALD_ROM=~/roms/emerald.gba EMERALD_SAVE=~/roms/emerald.sav EMERALD_PARTNER_SAVE=~/roms/emerald_partner.sav COOP_WORK=~/roms/work/coop bash scripts/coop_headless_check.sh [name...]
```
`COOP_WORK` is a scratch folder (a temp one by default), Docker must be running, and a name runs just that test. The commands the
tests use are in `native-capture/README.md`. The groups: `install*`, `join_*`, `free_*` (the retail check and the join rules),
`after_*` (give party / PC, items, flags and money, evolve, save, white-out, a write refused off the free field), `link_*` (the
bridge, two cores in one process), `lock_*` / `story_*` (the lock and the story hook), `partner_*` (the partner on screen). Kotlin:
`CoopJoinTest`, `CoopBattleWireTest`, `CoopBattleTest`, `CoopLinkBattleTest`, `CoopLinkFlowTest` (both sides of the flow over two fake
engines: starts, cancels, stories, the end, the drop rule, catches and their acknowledgement), `CoopOpQueueTest`, `CoopBattlesTest` (the
glue: stop order and fail-closed stops, busy / state blocks, message hopping, STATUS) and `CoopSafetyTest`.

### The link bridge (a joined battle as a link battle)

A joined battle is Emerald's own link multi battle: both devices run the game's link code, the host as the
link master (multiplayer id 0, battler 0) and the partner's game its link partner (id 1, battler 2, its own `Player` controller), and link.c's block layer
is carried over our network instead of the serial cable. No serial (SIO) emulation: a frame-timed link over the internet can't
keep time. Code: `pk_coop_link.c` (API in `pk_coop.h`), the Thumb stubs in `scripts/coop_link_emerald.s` (generated into
`pk_coop_emerald.h` by `gen_coop_emerald.py`: code at `0x08FFD000`, its own 4 KB below the lock's `0x08FFE000`), headless
checks `native-capture/coop/link_*.txt` on two cores in one `mgba_dump` (`pair`, `on a|b`, `linkrun`, `linkdelay`, ...: its README).

**The link battle is the tower's link multi** (`BATTLE_TYPE_BATTLE_TOWER | DOUBLE | LINK | TRAINER | MULTI | TOWER_LINK_MULTI`: cable_club.c's
`USING_BATTLE_TOWER` flags plus battle_tower.c's `TOWER_LINK_MULTI` (1 << 23). Without that last one `HasNoMonsToSwitch` / `checkteamslost` look at the
wrong half of `gEnemyParty` for the foes (battler 1 reads slots 3-5, the AI's own loops 0-2): a foe whose own Pokémon were all down was sent a
"replacement" from the other foe's half, "sent out ???" with party slot 6, and the battle hung - found when the first foe of a wild battle fainted
on a device; `link_faint_wild.txt` / `link_faint_trainers.txt` play a battle to its end with `linknoerror 1`): two linked players against two AI trainers, the AI run by the master and mirrored on the partner by link
opponent controllers. The plain link multi (four linked players, no tower flag) has no AI controllers, so it would need two
fake linked players and a way to run their Pokémon; the tower flavour needs none of that. What the tower flag costs is a list of
Frontier rules the later tasks undo one by one: it counts as `BATTLE_TYPE_FRONTIER` (no EXP, no money, no catching, the enemy
party levelled to 50 by `TrySetLinkBattleTowerEnemyPartyLevel`, names and trainer pictures looked up in the Frontier's trainer
table by `gTrainerBattleOpponent_A` / `_B` - an id of a route trainer, 300 and up, runs the name copy over other EWRAM, so
`pk_coop_link_begin` puts two stand-in Frontier ids there and builds the real trainers' parties itself, before the tower flag
goes on: `CreateNPCTrainerParty` makes none for a Frontier battle) and its own end flow.

**What the bridge replaces** (head patches, 8 bytes: `ldr r3, [pc, #0]; bx r3; .word <routine | 1>`, put in by `begin` and taken
out by `reset`, each CRC-gated at install; the Thumb routines run whole in the original's place): `SendBlock` (the block goes to
a 16-entry outbox in the bridge's RAM at `0x0203D000` for the app, and into the game's own `gBlockRecvBuffer[myId]` with its
received flag set, as the cable echoes a sender's own block), `IsLinkTaskFinished` (the outbox has room and the own slot has
been read), `GetMultiplayerId` (0 host / 1 partner), `HandleLinkConnection` and `HasLinkErrorOccurred` (nothing happens on a
serial line that isn't there), `OpenLink` (the remote players are received at once) and `CloseLink` / `SetCloseLinkCallback`
(they are gone at once; closing while `BATTLE_TYPE_LINK_IN_BATTLE` is set is the battle's end). Receiving needs no hook:
`pk_coop_link_give` writes the block into `gBlockRecvBuffer[1 - myId]` and sets the game's `gBlockReceivedStatus`, after the
state, seq, kind and shape checks (`CoopSafetyTest`'s rule: nothing from the network reaches game memory unchecked). `begin`
also fills `gLinkPlayers` (host at 0, partner at 1), `gLinkStatus` (two players, master bit, connected), calls the game's own
`SaveLinkPlayers` (else `Task_WaitForLinkPlayerConnection` closes the link as a player count mismatch), trims `gPlayerParty` to
the first three able Pokémon (backed up in the bridge's RAM, put back by `reset`) and enters `CB2_InitBattle`.

**Blocks** (`[u8 kind][u8 seq][u16 size][bytes]`, `PK_COOP_LINKB_*`; the kind comes from where in the game the block was read:
`take` classifies by the source address, a block of no known kind is dropped and counted): HEADER (the link battler header,
version signature 0x0300), PARTY / ENEMY (one or two struct Pokemon: decrypted and checked - checksum, level, HP, status, a real species and never an egg, moves and held item inside their tables, PP within each move's cap), SEED (the recorded
battle's RNG seed), BUFFER (one battle controller message, checked by buffer id and command: the master accepts from the partner only its answers for battler 2 - an action 0-3, a move slot and target 0-3, a switch to one of its own live slots 3-5 with a sane party order, an item id inside the table, or a data reply that holds a sane Pokemon - and its "done" as player 1, never a command; `PK_COOP_LINK_ERROR` is the state once the game sent a block of no known kind), MPARTY (the three Pokémon the party
showcase screen shows). An exchange takes 8 blocks before the first controller message, then one block per controller message.
Each side's blocks go to the other in order; a block is refused (`give` = 0)
until the game has read the last one of its slot, which is the back-pressure.

**Starting a joined trainer battle from the field** (task 2, `pk_coop_link_start.c`; `link_start_trainers.txt`, `link_start_single.txt`,
`link_two_able.txt`). The host's game is held where the join is offered (`pk_coop_link_host_hold`: `pk_coop_join_kind` 2 or 3 with
Task_BattleStart running, usually already one transition frame in). On the partner's yes `pk_coop_link_host_go` puts the field's
callback back (the transition's first frame set CB2_OverworldBasic, which `begin` refuses), clears the battle flags, runs
`pk_coop_link_begin` as the master (the first three ABLE Pokémon, never an egg or a fainted one; the rest set aside and put back by
`reset`), then resets the task table (the game's own ResetTasks: Task_BattleStart and the transition's tasks would otherwise run on
into the party showcase, which skips ResetTasks while a link is up) and puts back what the host's script reads at the end:
`gMain.savedCallback = CB2_EndTrainerBattle` (begin sets CB2_ReturnToField, which would leave the script waiting forever) and the
real `gTrainerBattleOpponent_A` / `_B` (`pk_coop_link_end_ids`, once, when the link state first reads ENDED; a single trainer's B
goes back to what the script left). The partner starts from its free field (`pk_coop_link_partner_go`, `pk_coop_on_free_field`).
The Frontier rules the tower flag brings are undone for trainer battles by head patches of the tower flow's lookups, answered from
`gTrainers` for the two real ids (`GetFrontierTrainerName`, cut to 7 letters into a link player's name; `GetFrontierTrainerFrontSpriteId`;
`GetFrontierOpponentClass`; `GetBattleTowerTrainerLanguage`; `CopyFrontierTrainerText` = an empty speech on both games, the
speeches being in the host's map script only) and `TrySetLinkBattleTowerEnemyPartyLevel` = nothing (the foes keep their own
levels), and two patches in `BattleAI_SetupAIData` (the frontier-mask pool word loses the tower bit, the TWO_OPPONENTS test also
fires for it) give the foes each trainer's own AI scripts (A's | B's, + DOUBLE) as in the solo game, not the tower's three.
`CreateNPCTrainerParty` runs with TWO_OPPONENTS set (each trainer's party stops at 3) and the flag is cleared right after. A
refused `host_go` leaves the held battle exactly as it was (`begin` notes the ids, savedCallback and gEnemyParty and puts them
back; `link_start_refused.txt` forces a refusal with `linkforcefail`). Still as the tower has it: no EXP / money / items (later tasks). **A single trainer** (2+ Pokémon) is both foes of the tower multi: `CreateNPCTrainerParty` once, its Pokémon 0 2 4
to the left foe (gEnemyParty 0-2) and 1 3 5 to the right (3-5), `gTrainerBattleOpponent_B = _A` during the battle.

**What was checked** (`link_trainers.txt`, `link_late_block.txt`, `link_delay.txt` = the first with every block 12 frames late both ways; both cores in one process):
both bridges reach the battle state; the host's battler 0 and the partner's battler 2 are the game's own player
controller at its action prompt; the teams are laid out as the game lays a link multi out (gPlayerParty = the host's three, then
the partner's three, on both cores: the partner core's own first Pokémon is at slot 3, not 0); each core picks a move with its own
keys; a whole turn runs; the 12 HP halfwords of gPlayerParty / gEnemyParty are the same on both cores afterwards (the partner core's
`gBattleMons` stays empty: only the master runs the engine); a block while idle, a stale seq and a block after the end are
refused without touching the game's received flag.

**Wild battles** (task 3, `pk_coop_link_wild.c`, `PK_COOP_LINK_WILD`, tests `link_wild_intro` / `link_wild_run` / `link_wild_run_partner`): the same
link multi battle without trainers. The game itself ORs `BATTLE_TYPE_TRAINER` into the flags in `FindLinkBattleMaster` (the header
exchange, after our `begin`), so the wild kind head-patches that function with our stub (`link_entry_FINDLINKBATTLEMASTER`: player 0 is
the master, the trainer bit only for the other kinds) and leaves TRAINER out of the flags it writes; with no TRAINER the game makes
the intro ("Wild X and Y appeared!"), the foe sprites (no trainer pictures, no foe AI beyond the wild random pick) and the dex
rules itself. The host rolls the two wild Pokémon from the map's table (`GetCurrentMapWildMonHeaderId` + `TryGenerateWildMon` twice,
the water table while surfing) into `gEnemyParty` slots 0 and 3 (battler 1 reads 0, battler 3 reads 3); the partner's game gets them
as the link battle's own ENEMY blocks. Per-battle ROM patches for the wild kind only (`sLwPatches`, put in by `begin`, out by `reset`,
each checked against the file's bytes): the MULTI trainer picture at the foe-right position (`COOP_EM_INTRO_FOE_RIGHT_CMP`), `TryRunFromBattle`'s
double-battle test (`COOP_EM_RUN_DOUBLE_BNE`, the roll runs), `IsRunningFromBattleImpossible`'s `if (LINK) return SUCCESS` and `HandleAction_Run`'s
`if (LINK | RECORDED_LINK)` forfeit (so Shadow Tag & co. refuse at the prompt and the escape roll runs, "Can't escape!" and the next
prompt), `TrySetLinkBattleTowerEnemyPartyLevel` (returns at once: the foes keep their level, not the facility's 50), the VS screen's
task (our no-op: no "COOP VS NED / two trainers' names" screen; `COOP_EM_LINK_WILD_INTRO_VS_SPRITES` skips the intro slide's link task setting the VS letters' callbacks by the ids at `gBattleStruct` + 0x7D / 0x7E, which read 0 without that screen and slid the host's own health box (sprite 0) off the screen; `link_healthbox_*` check all four boxes' positions) and the `gBattleMainFunc = HandleEndTurn_FinishBattle` literal of
`HandleEndTurn_RanFromBattle` / `HandleEndTurn_MonFled` (our `link_entry_RAN_FINISH`: the escape's script ends with `end2`, which
ends only the master's game, so the stub sends `Cmd_endlinkbattle` once - the partner's `gBattleOutcome` is 4 too - and then runs the
game's own function). The music is chosen by `PlayBattleBGM` called while the flags are still 0 (`GetBattleBGM` says the trainer
song for any LINK flag). Not here: EXP / money (Task 4 / 5), catching (Task 4), the end flow after `CB2_InitEndLinkBattle` (Task 5),
the foes never being marked seen in the Pokédex (links skip it).
**Story battles as link battles** (plan 6 Task 6, `pk_coop_link_story.c`, `scripts/coop_link_emerald.s`'s `task 6` block, kind
`PK_COOP_LINK_STORY` = Offer kind 4): a gym leader, the rival or a Team Aqua / Magma boss is joinable in every save by a partner
standing at the same spot (`CoopJoin.storyOffer(host: StoryAt, partner: StoryAt, partnerFree)`: same event id and trainer, neither flag
set, the partner free; `Status.story` = the story event the sender stands at with that trainer's flag unset, 0 = none). Each game runs its
own `trainerbattle` script; the one place every trainer battle starts is `BattleSetup_StartTrainerBattle` (the `dotrainerbattle`
command), so that is where it joins. `pk_coop_story_arm` (the partner said yes) head-patches it (`link_entry_STORYSTART`; in only
until go / cancel / disarm). The first `dotrainerbattle` then points the script at a small pick script in the story RAM
(`0x0203FA00`, outside the bridge's zeroed area): `special ChooseHalfPartyForBattle; waitstate; dotrainerbattle; goto <what followed
the original dotrainerbattle>`. The game's own multi-battle "choose three" screen runs from the field (the CB2_PreInitMultiBattle
party screen is only a showcase, not a picker), and the pick script's own `dotrainerbattle` reads `gSelectedOrderFromParty` /
`gSpecialVar_Result` and holds the script (`ScriptContext_Stop`): state READY, or CANCELLED when the screen was backed out of
(`pk_coop_story_cancel` then lets the game's solo battle start). With both players READY the app calls `pk_coop_story_go`: begin
with `setup.picks` as the party (`link_trim_party`: the picks in order instead of the first three able), the real trainer ids
(`gTrainerBattleOpponent_A` / `_B`), the prelude of the original function written back (`sNoOfPossibleTrainerRetScripts`...) and
`gMain.savedCallback = CB2_EndTrainerBattle`: after the battle the game continues at the `goto`, so each game's own after-battle script
(badge, flags, text, TM) runs as it does solo. A story trainer with one party (Roxanne: three Pokémon) is split over the two foe slots
on the master (`story_split_foes`: the first half rounded up in slots 0-2, the rest from slot 3). The pick script's `special`
index and the opcodes come from the decomp (`story_constants` in `gen_coop_emerald.py`, the index checked against `gSpecials` in the
built ROM). `ChooseHalfPartyForBattle` also sets `VAR_FRONTIER_FACILITY` (the game's own side effect). `coop/link_story_gym.txt` runs
both cores through Roxanne's intro, the pick screen and into the link battle (the end of the battle and the after-battle scripts wait for
Task 5's normal end).
**A dropped link (Task 7, `pk_coop_link_drop.c`)**: the game has no timeout anywhere in the battle's path (a battle waits on
`gBattleControllerExecFlags` / the partner's block for ever; link errors are raised only from `gLinkStatus`, which the bridge
writes once), so a drop of any length is a stall the battle survives (`link_drop_back`: 30 s, nothing refused, the turn
completes after). The "Waiting for ..." text and its 60 s clock are the app's, not the game's. `pk_coop_link_leave(core,
blackout)` is the app's give-up: from any frame, by RAM writes only (no `pk_call`, so also where no call is safe), it points the
battle at the game's own ending (`gBattleMainFunc = HandleEndTurn_FinishBattle`, action `B_ACTION_FINISHED`: a fade, then the
non-link free of the battle's graphics, then `gMain.savedCallback`), after clearing the link bits of `gBattleTypeFlags` (the
game frees the battle's graphics only for a non-link battle), putting `BattleControllerDummy` on all four battlers, resetting
the tasks and text printers by hand (a menu's task would set callback2 back to its exit) and setting the outcome: TELEPORTED
for 0 (not a defeat, not a win: no prize, no EXP) or LOST for a blackout (`savedCallback = CB2_WhiteOut`: half the money,
the party healed, the heal location). **`savedCallback` is never trusted**: the party showcase of the tower multi leaves
`CB2_PreInitMultiBattle` there (a leave during it came back into the battle), so anything but the game's own wild / scripted /
field ends becomes `CB2_ReturnToField`; **a trainer's end is never continued**: `CB2_EndTrainerBattle` (it sets the trainers'
flags and registers them in the match call for any outcome that is not a defeat) and
`CB2_ReturnToFieldContinueScriptPlayMapMusic` (it would run the stopped trainer script's post-battle part: a badge, a gift, a
flag for a trainer nobody beat) both become `CB2_EndWildBattle`: the field, controls released, the stopped script just ends,
the trainer can be fought again (`link_drop_script`, which also shows the test script does set its flag when continued). Then
`pk_coop_link_reset` (patches out, the set-aside party back). Evolutions the battle would have run afterwards are dropped (the
game runs them only for a won battle; the manual draft says so). **The heap**: a screen of the battle (the party menu's
`sPartyMenuInternal` and boxes, the bag, the summary, an evolution scene, animation buffers) holds blocks only its own exit
frees. `pk_coop_link_begin` notes the heap blocks in use (`pk_coop_link_drop_arm`); once the game is back at its own end callback
(`pk_coop_link_drop_tick`, called by `state` / `take`, which the app pumps every frame) every block in use that was not there is
freed with `FreeInternal`'s logic in C, and `gBattleTypeFlags` is zeroed. `pk_coop_link_begin`'s gate is the field (callback2 =
CB2_Overworld, `gMain.inBattle` clear, no link up), not `gBattleTypeFlags`, which the game zeroes only when the next battle
starts (`link_begin_stale_flags`). Tests: `link_drop_back`, `link_drop_leave`, `link_drop_blackout`, `link_host_gone` (the host
core restarts: `corereset`), `link_drop_frames` (the setup, the intro text, the party screen, the action menu; heap free bytes
equal before and after), `link_drop_party_x20` (the party screen left 20 times, heap checked each time), `link_drop_script`.
**EXP, the BAG and catching (plan 6 Task 4: `pk_coop_link_rewards.c`, the `task 4` block of `coop_link_emerald.s`, tests
`coop/link_exp`, `link_bag`, `link_bag_host`, `link_catch_two_up`, `link_catch_partner`, `link_catch_host`).** The game's link battle
gives no EXP, refuses items and has no balls; `begin` puts these ROM changes in (and `reset` takes them out), all through pk_cheats:
the two battle-type literals of `Cmd_getexp` case 0 and `HandleTurnActionSelectionState`'s item test (Frontier bits and LINK out),
the INGAME_PARTNER traded-boost `beq` (slots 3-5 are never boosted), table pointers for `Cmd_getexp`, `Cmd_handlelearnnewmove` and
`sTurnActionsFuncsTable[B_ACTION_USE_ITEM]`, and head patches on `ItemUseInBattle_PokeBall`, `CompleteWhenChoseItem`,
`Cmd_trysetcaughtmondexflags`, `Cmd_givecaughtmon`.
- EXP: the master's `Cmd_getexp` wrapper fakes the exec flags in state 1 so the game works its totals out and waits, then works each
  side's share out (each side divides by ITS sent-in Pokémon and Exp. Share holders), sets `*exp` / `gExpShareExp` for the side of the
  Pokémon whose turn it is each frame in state 2 (and marks battler 0 absent for a benched partner Pokémon, so its EXP update goes
  to battler 2, not the host's controller), and mirrors the update into the master's own copy of a partner Pokémon in state 3 (the
  partner's game gives its copy the same through its own controller; the leftover after a level comes back as `TWORETURNVALUES` 11, accepted
  only while getexp's state is 4). A partner Pokémon's moves are not learned in battle (nothing-to-learn): its own game does that after
  the battle (Task 5 sets the level-up bits from the level change). Seen: the host presses A through every level-up box, the partner's too.
- BAG: the partner's `CompleteWhenChoseItem` answers `TWORETURNVALUES` (item low byte, item high byte, and a medicine's party slot in
  battle-id terms - the party menu lists the player's own Pokémon first and the game puts the physical order back when it closes) which
  `link_buffer_ok` accepts only while the engine waits for an item from battler 2; the master's `USEITEM` wrapper then runs
  `ExecuteTableBasedItemEffect` on its copy with `gBattlerInMenuId` = 2 (HP and status of the Pokémon in battle first taken from
  `gBattleMons[2]`: the partner's game keeps the real ones, the master's copy of a partner Pokémon is stale) - the item itself came out
  of the partner's own bag. A medicine's effect on the partner's own game emits a controller command of its own (its Pokémon's data
  request); the partner's `SendBlock` swallows every command made on the partner's side, else it waits for the busy controller
  forever (the IS_DONE is behind it in the receive ring). PP items aren't replayed on the master.
- Catching: the ball rule is `link_ball_refused` (a TRAINER battle: the game's own answer; otherwise the foe in front of the thrower
  must be the one left; each game reads `gEnemyParty` HP); the catch is `link_entry_CATCH_GIVE`: the foe's 100 bytes into the rewards'
  RAM with who threw (`pk_coop_link_caught`, re-keyed to the catcher's trainer ID), and the script's tail is ours (`endlinkbattle` with
  outcome 7, so the partner's game ends too). The catcher's own game gives it to itself after the battle (`pk_coop_give_mon`).
- Fix round 1: a ball catches the last wild Pokémon whichever slot it is in (`link_entry_BALLTHROW` swaps gBattlerAttacker for the
  game's own `Cmd_handleballthrow` so BATTLE_OPPOSITE is the living foe, keeps the real thrower in the rewards' RAM for the catch
  commands, restores it); the master enforces the rule too (`link_script_cancel`); the PP items' `gItems` pointers go to
  `link_entry_PPITEM` (the partner's bag refuses them, "Not now!", nothing used; the master's own is the game's; flutes are
  `ItemUseInBattle_Medicine` and take the medicine path); a partner Pokémon's level-up box gets an A press made for it every 49 frames
  (the host's own stay the game's); the "party and boxes full" check uses the catcher's real party count. Not replayed: the master's
  copy after a partner PP item (refused instead). A caught Pokémon's shininess follows the catcher's trainer ID (shiny = f(ID ^ SID ^
  PID)), so it can differ from what the battle showed (a wild Pokémon is made with the host's ID); the PID / OT pairing is kept, not
  adjusted. The traded 1.5x stays for the host's own traded Pokémon (only slots 3-5 are never boosted).
- Tests make the battle wild by clearing TRAINER on both cores once it runs (Task 3 does it in begin) and leave one foe by hand.
**The end of a joined link battle** (plan 6 Task 5; `pk_coop_link_end.c`, the merge in `pk_coop_link.c`; `link_end_*.txt`): the game's own end
of a link battle is a VS screen, a record prompt and a link reconnect, none of it wanted. One halfword is patched while `begin` is in:
`CB2_InitEndLinkBattle`'s `if (gBattleTypeFlags & BATTLE_TYPE_FRONTIER)` `beq` (0xD011, `COOP_EM_LINK_END_BEQ`) becomes a nop, so every
link end takes that function's frontier branch: `SetMainCallback2(gMain.savedCallback)` and the three `Free*` calls - the game's normal end
(`ReturnFromBattleToOverworld` and `TryEvolvePokemon` are never on a link battle's path, so what they do is the app's). `begin` sets
`savedCallback` to `CB2_EndWildBattle`: the field again, or `CB2_WhiteOut` when `IsPlayerDefeated(gBattleOutcome)` (LOST / DREW). Nothing in
a link battle pays or flags (no script runs it), so `pk_coop_link_tick` (every frame, with the core lock, riding `pkCoopLinkState`, the app's per-frame state pump) does it:
1. **The first frame the bridge is ENDED** (CloseLink ran, about 2-3 frames before the game's `IsPlayerDefeated`): the outcome is noted and the
   party is put back as a **merge** (`link_restore_party`): each pre-battle Pokémon (the backup `begin` took) is found in the battle's party
   by (personality, OT id) and replaced by the battle's 100 bytes (HP, PP, EXP, level, moves, a new species; switches reorder, personality
   survives evolution); the set-aside reserves come back byte for byte; the other player's Pokémon are dropped, so a partner's own
   Pokémon end up in the partner's own game. A LOST battle with an able Pokémon anywhere in the whole merged party gets `gBattleOutcome`
   = RAN (4) before the game looks, so it doesn't black out (the ruling: a player blacks out only when nothing in the whole party can fight);
   nothing able = the game's own white-out.
2. **On each game's own free field** (`pk_coop_on_free_field`), one step per frame, each retried while the game isn't free: the prize (WON only;
   a TRAINERS battle pays both trainers' `pk_coop_prize`, no x2; SINGLE pays `pk_coop_prize_single`: that trainer's own, x2 for a double
   record), the trainers' flags (`pk_coop_set_trainer_flag`: WON only, both games, idempotent), one `pk_coop_evolve` per original slot whose
   level went up (compared in the merge: the game's `gLeveledUpInBattle` is the master's alone), and the white-out of a win with nothing able
   (`pk_coop_white_out`, after the prize).
3. **The result** (`pk_coop_link_result`, 16 bytes) is ready once all that ran, the game is on the field again and the outbox is empty
   (`pk_coop_link_drained`): reset is safe from then on. `pk_coop_link_result_clear` drops it (a new `begin` makes the old one stale anyway).
STORY battles get none of the prize / flags part (the host's script's, a later task); a link lost (the 60 s rule) is a later task's too.
An uneven end (one game's text box holds its battle 300 frames longer: `link_end_uneven.txt` delays the block that carries
`CONTROLLER_ENDLINKBATTLE`) needs nothing special: the master ends first and never waits for the partner; the partner's own trailing
`CONTROLLER_IS_DONE` reaches an ended master, whose `give` refuses it (counted in `refused`; the app drops it) and no game sends a block
after its own ENDED. Checked: `link_end_won` / `_single_prize` / `_lost` / `_lost_reserve` / `_blackout` / `_blackout_all` / `_evolve` /
`_uneven` (the evolution scene's level is worked out from EXP again: a poked level shows as the old one afterwards).

**The app drives it (plan 6 Task 8b: `CoopLinkFlow`, `CoopLinkBattle`, `CoopBattles.kt`'s glue)**: with the native bridge the glue runs every joined
battle through `CoopLinkFlow` . Wild / two trainers / one trainer: the host's game is held
where `pk_coop_link_host_hold` says (wild too now: `host_go` makes kind WILD), the offer carries the host's name / gender / OT ID, the partner's yes
makes the PARTNER enter its link battle first (`partner_go`, retried while -1, its blocks wait in `CoopLinkBattle.expect`) and say `LinkReady`; only
then `host_go` runs (frames running so a safe one comes up, 120 tries); a refusal on either side CANCELs the other at once (no 60 s wait). Story
(kind 4): offered where `CoopJoinRules.storyOffer` says (`Status.story` = the event stood at: the lock's held event, else `CoopStorySpots` by tile),
both `pk_coop_story_arm`, each runs its own script, both READY -> the host `story_go`s and sends GO, the partner follows; the lock's go waits while the
join is decided (`CoopBattles.storyPending`). The pump: `state()` (which ticks the end) every frame until the result is read or a leave has settled,
trailing blocks refused after ENDED are dropped; the catch is read before reset / leave and the host sends it (`CaughtMon`) for the partner's ball, which
checks it (checksum, species, its own OT ID) before `give_mon`; saves go through `CoopOpQueue` after a backup.
**The drop rule, in the flow** (`CoopLinkBattle`, `CoopLinkFlow`; spec: the 60 s rule, changed 2026-10-26): a battle waits up to `DROP_MS` (60 s) for the link with
the "Waiting for ..." overlay; back in time it carries on where it was. Not back: a **story** battle (every save, also an armed join that was still on the team-pick
screen: `storyTick` waits the same minute, then `storyDropped`) is a loss with a blackout for both (`pk_coop_link_leave(blackout)` in a battle; the held script
let go and the game's own white-out for an armed join, `CoopOp.WhiteOut` + a save); any other battle ends for both with no prize, the trainer not beaten, what
was earned kept (`pk_coop_link_leave(0)`). An armed join whose game never frees (the pick screen left up for 120 s) is not dropped on the floor: the
blackout is **owed** (`blackoutOwed`, the flow stays busy) and runs as soon as the game stands free (`owedStep`), never a bare reset that lets the solo battle start.
**Final review fixes (2026-10-26)**: (1) A side that ends the battle by itself with the link up tells the other: `CoopLinkBattle.tell` sends CANCEL(ENGINE) from `failLoud` (bridge error, hostile block, full outbox, a block refused 1800 frames) and the no-result leave, a best-effort CANCEL(CLOSED) from the 60 s leave; the partner's RUNNING `cancelled` abandons at once for ENGINE. (2) A dropped story join **never calls `storyCancel`** (that one starts the solo trainer battle): `storyDropped` only latches `storyDrop`; `dropTick` keeps the hook in while the state is ARMED / PICKING (taking it out would let the script or the pick script reach the real `BattleSetup_StartTrainerBattle`), and once READY / CANCELLED (script held) calls `storyDisarm` (hook out, no battle), then the held script is let go, `CoopOp.WhiteOut` + a save; the hook gone by itself (another trainer's battle went by) just ends the join. The app closing in `S_ARMED` (`stopDisarm`) takes the hook out, lets a held script go (READY / CANCELLED) and never starts a battle; a script still on its way (ARMED / PICKING) is not ours to stop, so there the game goes on as if no join had been. Headless: `coop/link_story_drop_ready.txt` (`storydisarm`, `coopreleasescript`). (3) The host's own catch is kept like the partner's: `CoopCatchStore` under `<pairKey>#own` until given and saved, given on the next free field after a stop (a Pokémon already in the party is only saved). (4) `pairKey` is own save + the paired partner's peer key (`CoopSession.pairsList`), never a display name. (5) Trainer ids from the network are refused at `>= COOP_EM_TRAINERS_COUNT` (Kotlin decode, `pk_coop_link_begin`), kind 2 needs both.
**A partner's catch** is kept by the host (`CoopCatchStore`, `files/coop/catches.txt`, rewritten through a temp file and a rename, on a background thread: `FileCatchStore`
reads once at construction and writes snapshots through its `io` executor, never on the emu thread) until the partner's `ResultAck`, resent every 2 s while linked and
at once on a reconnect or restart. The partner gives any valid `CaughtMon` (check + its own OT ID, deduped by battle id + personality) whatever its own result said, and
**acknowledges only once the in-game save that follows the give has gone through** (`CoopOpQueue`'s `onDone` for a Save that answered 1): until then the Pokémon is
counted as given but unsaved (`unsaved`, in memory), so a resend retries the save instead of giving it twice, and only then is it remembered as given for good (persisted)
and acknowledged, so a close or a crash before the save never loses it. The result wait never counts while the game is off its field (an evolution, a prompt) and only a game
on the field with no result for a minute is left, then reset. Network names need a 0xFF terminator and charset bytes (wire) and are forced to end (JNI / `link_player`).
Story spots: the app's tile is the player object's currentCoords = SaveBlock1.pos + 7 (MAP_OFFSET; `coop/story_coords.txt`), events use map coordinates, so `CoopStorySpots`
subtracts 7; an OBJECT spot is the tile beside the object (facing is not read). **Not offered yet:** story events with several trainers (a rival whose script holds one team per
starter, doubles with two story trainers): the trainer to arm for is not known before the script runs.

**RAM** (EWRAM the game never uses: its last symbol ends at `0x0203CF64`; FieldItems owns `0x0203FFF0-0x0203FFFF`): the bridge `0x0203D000-0x0203EFFF`
(`PK_COOP_LINK_RAM`: state, the outbox, the party backup, the rewards at `0x0203E400`), `0x0203F000-0x0203F3FF` free, the lock `0x0203F400`, the story join `0x0203FA00`.
**Battle text** scrolls by itself in a joined battle because the game sets `BATTLE_TYPE_LINK` (`BattlePutTextOnWindow` sets `gTextFlags.autoScroll` for LINK / RECORDED
battles, then waits `TextPrinterWaitAutoMode`'s 49 frames instead of an A press); the earlier per-battle patch for it is gone. Menus (action, move, target, party, bag, Yes / No) still take input.

## Co-op lock and story events (Emerald)

`scripts/gen_coop_story.py` (same Docker image and line as `gen_coop_emerald.py`, whose ELF / probe readers it reuses)
reads the pinned, built pokeemerald (retail by SHA-1) and writes `app/src/main/cpp/pk_coop_lock_emerald.h` (the hook's
addresses, `COOP_LK_CRC_ScriptContext_SetupScript`, struct offsets, script command bytes, and the story table
`COOP_LK_STORY[]` as `static const` data) and `companion/data/coop/CoopStoryGen.kt` (`COOP_STORY_EVENTS`,
`COOP_TRAINER_WINS` and the ROM / RAM constants Kotlin needs). Rules, one function each:

- **COORD** (`collect_events`): every `coord_events` entry of `type: trigger` whose `var` is not 0; a `VAR_TEMP_*` one is kept only when its script (followed through `goto` / `call`) battles a story trainer.
- **FRAME**: every `map_script_2` of the map's `MAP_SCRIPT_ON_FRAME_TABLE` (a `VAR_TEMP_*` var only when the script battles a
  story trainer, as the champion's room does: its `VAR_TEMP_1` entry scene starts the Wallace battle, so it is locked).
- **OBJECT**: an object whose script, followed through `goto` / `call` / `goto_if*` / `call_if*` / `case` / a trainerbattle's
  post-battle script to labels of the same map's `scripts.inc`, has a `trainerbattle*` naming a story trainer (class
  LEADER, ELITE_FOUR, CHAMPION, AQUA / MAGMA LEADER / ADMIN; or `TRAINER_BRENDAN_*` / `MAY_*` / `WALLY_*`), or a
  `setwildbattle` then `dowildbattle`. A story trainer whose object sees the player (`trainer_type` not NONE) is left
  out (a sighted start goes through the generic approach script, which the hook can't tell apart) and printed.
  COORD / FRAME events get their `trainers` the same way (rival scenes list every starter variant: the script holds all six).
- **Numbering** (`number_events`): sorted (map name, kind, x, y, script), one script pointer once (the hook finds an event by
  the script it is handed, so a scene on several tiles is one event, at its first tile), ids 1..n.
- **Addresses**: the ELF's symbols; a label not found, or two, fails the run.
- **Wins** (`win_flags`): the `FLAG_BADGE0n_GET` / `FLAG_DEFEATED_*` set by what a trainer's battle leads to: the script after its
  `trainerbattle` and every script it goes on to, plus the battle's post-battle script. A flag the *caller* sets after a
  `call` returns (Wally in Mauville: FLAG_DEFEATED_WALLY_MAUVILLE) is not in his TrainerWin; the same goes for the Aqua / Magma bosses with no extra flags (Matt, Archie, Maxie); the trainer's own flag is always allowed anyway (`CoopStory.winFlags`).
- **ROM block check**: file offsets `0x00FFE000`-`0x00FFEFFF` of the built ROM must be all 0xFF (the lock's code block,
  `0x08FFE000`); the CRC32 of `ScriptContext_SetupScript`'s first 32 bytes is printed (`0xEF3698BA`).
- **Self-checks** (asserts at the end of `main`): at least 8 COORD and 8 FRAME events; the eight gym leaders each in an
  OBJECT event; ROXANNE's wins are the Stone Badge flag + `FLAG_DEFEATED_RUSTBORO_GYM`; Route 101's Birch scene is a COORD
  event; TRAINER_WALLACE in some event's trainers; ids unique, 1..n, script pointers unique.

**Open: a held story battle is not a joined co-op battle yet.** The lock only holds both players at the same spot and releases
them together; after the release each player fights the trainer in their own game. `CoopStory.storyBattle` and
`CoopStory.winFlags` have no callers until plan 4 can join single-trainer battles (spec section 5: the hosting player's battle,
the partner's game counting it as won).

**The hook re-arms**: the app's ROM-patch changes (REUSABLE TMS, other game options, PLAYER LOOK) call `pk_coop_lock_uninstall`, so
`CoopLock.onFrame` calls the idempotent `install()` every 300 frames even once installed, and re-applies `setActive`.

Last run (pokeemerald, retail bytes): 200 events (109 COORD, 27 OBJECT, 64 FRAME; 21 OBJECTs battle story trainers, 6 are
scripted wild battles; 63 duplicate scripts folded into the first), 26 trainers with win flags.

Gym leader TMs (can the leader's TM still be got by talking to them after the battle?): collectable for Roxanne, Brawly,
Wattson, Flannery, Winona, Tate and Liza and Juan (each behind its own `FLAG_RECEIVED_TM_*` receipt flag, after the
trainerbattle falls through); **Norman's is not collectable** (it sits behind `VAR_PETALBURG_GYM_STATE`: his script picks the
battle or the talk by that var, and only state 6 battles). Sighted story trainers left out: Tabitha (Magma Hideout, Mt.
Chimney) and Shelly (Weather Institute, Seafloor Cavern).

**The story hook** (`pk_coop_lock.c` / `.h`, `scripts/coop_lock_emerald.s`; Task 5). The generator also assembles the `.s` with
`arm-none-eabi-as` at `COOP_LK_CODE_ADDR` = `0x08FFE000` (every header value handed to the assembler as `.set COOP_LK_<name>`)
into `COOP_LK_CODE[]`: the code, `0xFF` up to `COOP_LK_TABLE_ADDR` (`0x08FFE800`), then the story table as bytes (8 an event:
script u32, id u16, kind u8, 0). Co-op's retail check (`pk_coop_install`) CRC-checks `ScriptContext_SetupScript` too and reads the file's bytes under the lock's patch
(`pk_coop_lock_original_bytes`), so either order installs and each uninstalls alone. Everything goes through `pk_cheats_block` / `pk_cheats_patch`, so the Poller's and
RetroAchievements' views are the file.
- **The patch**: `ScriptContext_SetupScript`'s first 8 bytes (two `pk_cheats_patch` halves) become `ldr r3, [pc, #0]; bx r3;
  .word <entry | 1>`. Its first four instructions (`push {r4, r5, lr}; adds r5, r0, #0; ldr r4, [pc, #32]; ldr r1, [pc, #36]`,
  checked by the generator) are replayed in the stub (`lock_orig`), the two pc-relative loads taken from the function's own
  literal pool, and it goes on at its fifth instruction (`COOP_LK_RESUME`).
- **The stub** (r0 = the script, lr = the caller's): not `active`, or the script isn't in the table, or its id is `allowId`
  (then `allowId = 0`) -> the game's function. Else it records the hold in the mailbox (`0x0203F400`: id, kind, map, the
  player's tile and facing, `gSpecialVar_LastTalked`, `gSelectedObjectEvent`) and OBJECT / FRAME return to the caller with
  nothing set up; COORD sets up one of four ROM scripts instead (`applymovement OBJ_EVENT_ID_PLAYER, <one step opposite the
  facing>; waitmovement 0; releaseall; end`), the tile recorded being the one after that step. A COORD with no 4-way facing
  runs its story script.
- **Mailbox** (`struct pk_coop_lock_mailbox`; the `.s` layout is checked against it by `_Static_assert`): `+0 active`,
  `+1 heldKind` (0 COORD, 1 OBJECT, 2 FRAME), `+2 heldId` (0 = none), `+4 allowId`, `+6/+7 held map group / number`,
  `+8/+10 heldX / heldY`, `+12 heldFacing` (1 south, 2 north, 3 west, 4 east), `+13 selectedObjectEvent`, `+14 lastTalked`,
  `+0x20` the re-entry script `pk_coop_lock_go` builds (its movement bytes at `+0x30`). The lock owns `0x0203F400-0x0203F4FF`.
- **Contract** (C API; `CoopLockNative` is the same over JNI): `install(core, call)` all or nothing (CRCs of the three functions,
  block overlap, both patches), `uninstall`, `set_active` (off also clears the hold and the allowed id), `held(&kind)`, `cancel`,
  `go(id)`: 0 when `id` isn't the held one; FRAME -> `allowId = id`, hold cleared, 1 (the game's own ON_FRAME check runs it);
  otherwise a free field is needed (`ScriptContext_IsEnabled` and `ArePlayerFieldControlsLocked` called through `pk_call`: a
  script running, locked controls or no safe frame -> -1, nothing changed); OBJECT -> `LastTalked` / `SelectedObjectEvent`
  put back, `allowId = id`, `SetupScript(script)`; COORD -> `applymovement player, <one step the way he faced>; waitmovement 0;
  goto <script>` written at `+0x20` and set up (a step back that was blocked, the player already on the trigger tile, just
  goes to the script), 1. `tick(core)` after every main-core frame (RAM only, no lock): a hold goes on a map change; a COORD
  also when the player is more than 1 tile (Chebyshev) from the recorded one, an OBJECT when his tile or facing changed.
  A held player is never stuck: he can walk away, change map or close the game.
- **JNI**: `pk_coop_lock_jni.c` (`Java_com_pokedaisy_app_CoopLockNative_*`) takes the core through `pk_core_access.h`
  (`pkMainCore`, `pk_core_rdlock` / `wrlock`, `pk_core_call`: one block in `pokedaisy_jni.c`): install / uninstall under the
  write lock, the rest under the read lock like the other co-op JNI calls. `pokedaisy_jni.c` ticks it (`pk_coop_lock_tick`) after every frame and uninstalls it
  wherever co-op is (teardown, `pkRomUnpatch`, `pkRomBlocks`, `pkRomUnblock`).
- **Tests**: `native-capture/coop/lock_*.txt` (`scripts/coop_headless_check.sh lock_install ...`; each names its events in its
  header). OBJECT: id 6 (`DewfordTown_Gym_EventScript_Brawly`); FRAME: id 109 (`Route101_EventScript_HideMapNamePopup`);
  COORD: id 97 (`PetalburgCity_EventScript_ShowGymToPlayer3`: it sets `VAR_0x8008`, which shows the story script ran). The
  scripts are *called* on the Route 101 save (the hook only looks at the pointer): a held call returns its script as r0, a
  call that ran returns `0x080000C1`.
- **The lock in the running game** (`CoopLock.kt`): one `CoopLock` per game while a pairing exists for the running save (made by
  `PokeDaisyActivity.startCoopLock` after the ROM's SHA-1 matched retail Emerald, no cheats; `activity.coopLock`, with `ui`,
  `notice` and the `CoopLockActions`, is what the later UI reads). It draws nothing. `EmulatorEngine.coopLock?.onFrame()` runs
  every loop turn on the emu thread, also while a hold runs no frame (so a held game can still be released). Link messages
  (`CoopLockWire`, 0x0301-0x0310) are only queued by `onMessage` (collector thread) and handled in `onFrame`; the actions
  (UI thread) use the party count `onFrame` last read from `gPlayerPartyCount`. `CoopLockNative.install()` happens on the
  first `onFrame`, `uninstall()` in `close()`.
- **Who owns which guard reason**: the lock owns `Hold.LOCK_START` (from the lock being made, on a locked save, until the first
  `Connected`), `Hold.LOCK_LINK` (`CoopLockRules.holdForLink`: a drop over 3 s, never during a co-op battle) and
  `StateBlock.LOCK` (while locked). Plan 4 owns `Hold.BATTLE`, `StateBlock.BATTLE` and `CoopGuards.battleActive`; the lock never
  touches them. A game closing calls `releaseGuards()` at once (any thread) and `close()` on the emu thread.
- **Message flow**: every `Connected` sends `Status(locked)`; a partner `Status(false)` / `Off` while locked turns ours off (a
  notice). PROPOSE (own party must be empty) -> partner ACCEPT / NO -> `Answer(accepted, partyCount)`, each side storing the
  lock only if both parties are empty. Locked: `setActive(connected && !battleActive)`, `CoopStoryHold.step(held id)` sends
  `Hold` and, when both hold the same event, `native.go(id)`, asked again each frame while it says -1. The overlay is
  `ui.waiting` (Partner while a hold is on, else AtStory).

## Nintendo DS (melonDS)

Platinum (USA rev 1, `CPUE`; France rev 0, `CPUF`), HeartGold / SoulSilver (USA, Germany and France rev 0, `IPKE` / `IPGE` / `IPKD` / `IPGD` /
`IPKF` / `IPGF`), Black / White (USA / Europe, `IRBO` / `IRAO`; France, `IRBF` / `IRAF`) and Black 2 / White 2 (USA / Europe, `IREO` / `IRDO`;
the Gen 5 carts are DSi enhanced and run in DS mode) run on melonDS, with the companion, EXPAND and STEADY music.

**The core.** `third_party/melonds` (a submodule pinned to upstream 1.1) builds its core library only, with the
software 3D renderer on melonDS's render thread (a quarter less time a frame than on the emulation thread, HeartGold's
overworld headless; from 2026-10-20 to 2026-11-09 it ran unthreaded, because a render started for a frame fast-forward
skipped - a state taken or loaded mid-skip, a restart, the thread switched on - left its 192 line signals for the next
frame, which then read 3D lines not drawn yet: black rows on the Thor, never on the Mac, whose render thread kept up; the
patch's `EnableRenderThread` starts none on a skipped frame now, and `RenderFrame` drops and logs any left over -
nds_dump prints `3D: dropped ...`), its OpenGL renderer ported to OpenGL ES (DS RESOLUTION, below) and the
JIT with fast memory (since 2026-11-09; melonDS's own fault handler, set up with the JIT either way, takes the faults in
compiled code and passes the rest on to crash reporting's). Our changes are one patch,
`third_party/patches/melonds-pokedaisy.patch`, applied to the submodule's working tree at configure time by
`apply_melonds_patch.cmake` (once; a tree that has it is left alone): EXPAND's GPU3D hooks (every polygon past culling,
the lists' swap at VBlank, the clipper), the software renderer's column range, fast-forward's frame skip
(`GPU::SkipDraw` / `SkipDrawNext`: no 2D lines or 3D render on a frame nobody sees, decided at line 215 where the next
frame's 3D starts, never while display capture is on), the OpenGL renderer's port (below) and `NDS::Current` as a plain static (Android's emulated
`thread_local` cost ~8% of the emulation thread). To change it: edit `third_party/melonds`, then
`git -C third_party/melonds diff > third_party/patches/melonds-pokedaisy.patch`. `.gitmodules` sets `ignore = dirty` on
the submodule, so the applied patch doesn't show in `git status`; `scripts/check_melonds_patch.py` (in `make check`) fails
when it holds anything but the patch (an edit not copied into it, a stray file). Moving the submodule means re-making the
patch. melonDS and its bundled pieces (FatFs, Teakra, xxHash, tiny-AES-c, blip_buf, Dolphin's code) are in NOTICE and
the LICENSES page.

**The app side.** `app/src/main/cpp/nds/`: `pk_nds.cpp` (the core behind the same JNI shape as mGBA's: frames, audio,
saves, states, input, the stylus), `pk_nds_platform.cpp` (melonDS's Platform: files, threads, the save callback),
`pk_nds_field.cpp` (the field read: field / text / menu / battle / bike / surf bits, the BGM id, WILD ENCOUNTERS' hold,
the battle controller's command), `pk_nds_call.cpp` + `stub/pk_nds_hook.c` (running the game's own functions from its
main loop: PC, FLY), `pk_nds_wide.cpp` (EXPAND), `pk_nds_music.cpp` (STEADY). Saves: the game writes its flash a sector
at a time, so the file (512 KB) is written once the writes have been quiet a while, and before a state or a close.
States carry the save memory's CRC32 in a header of ours, so a resume checks `pk_nds_state_matches_save` like the GBA's
`pkStateMatchesSave` (save files are sacred). The DS's bottom screen goes on the bottom display (`NdsBottomScreen.kt`)
where the companion goes for a GBA game; one GAME SCREEN / COMPANION toggle swaps them.

**Reads and tables.** Everything comes from pret's decomps, which build the retail ROMs byte for byte
(`$DECOMPS/pokeplatinum`, `$DECOMPS/pokeheartgold`, built in Docker): addresses from their `.xMAP`, struct offsets from
their headers, each checked on headless captures. `Gen4Reader.kt` reads the game's SaveData in RAM (party, the eight
pockets, money, map, Repel steps); `scripts/gen_gen4_tables.py platinum|heartgold` writes the names, types, moves,
items (and their pockets), map headers' place names, icon palettes and FLY spots (`Gen4Tables*.kt`, `Gen4TownMap*.kt`);
`scripts/gen_nds_stub.py` assembles the hook (clang for armv5te) and the PC scripts (the decomps' own macros) into
`pk_nds_stub_gen.h`. Art and text come from the player's cart (`NdsArt.kt`, through the cart's file system: party and
item icons, HG/SS's bag pocket buttons, Platinum's Town Map, and HG/SS's DEX pages as `dex.tsv` - entries, the Johto
order, evolutions, level-up moves, TM / HM bits - plus front pictures); bump `NdsArt.VERSION` when what it writes
changes. `Gen4Dex.kt` / `DexDetails` read those pages (Gen 4 evolution methods 16-26 decoded by `evoReqs`). Each game's
dex is a `PokedexTables` with `gen4` set (`POKEDEX_PLATINUM`: Sinnoh's 210 from `pl_pokezukan.narc`; `POKEDEX_HEARTGOLD`:
Johto's 256 from `a/1/3/8`), and the save's seen / caught flags are read from its Pokedex struct (`Gen4Config.pokedexOff`
and `nationalDexOff`: the struct's languages array differs in size, so the National Dex byte moved: 0x31B in Platinum,
0x337 in HG / SS). The TM / HM lists differ in HM05 (Defog / Whirlpool): `tmMovesPlatinum` lives in
`Gen4MachinesPlatinum.kt`, a file of its own, since `Gen4TablesPlatinum.kt`'s static initializer is at the JVM's limit.

German HeartGold rev 0 (`IPKD`, SHA-1 `13b6853aa93121069df1d17446d3ecb36872aecf`) shares the US save layout and
message banks. Its ARM9 literal pools give `sSaveDataPtr` at `0x021D2208` (`SaveData_Get`, `0x020272B0`),
`sFieldSysPtr` at `0x021D4138`, and `sSoundWork` at `0x02111938`; the battle template is `0x020FA438` and overlay 1's
field main is `0x021E5BC5`. The functions used by `pk_nds_call` keep their US addresses. `GEN4_HEARTGOLD_DE` and
`GEN4_SOULSILVER_DE` and the native `HEARTGOLD_DE` table select these addresses; `romGameCode` also carries `IPKD` so field actions use the
same config as the sampler, and the battle reads (`Gen4Battle`) take the German `sFieldSysPtr` and battle template too.
DS LAYOUT's touch rules (`TOUCH_RULES`, `DsLayout.followed`) are the USA carts' alone: the German ones fall back to
PICTURE IN PICTURE until their Task_StartMenu / app init addresses are checked. Names and art are read from the German cart. A new game and the PC script were checked
headlessly, with the captured state kept locally. `HeartGoldGermanTest` relocates the existing US fixture's two globals
to exercise the shared party, bag, dex and map layout without bundling another player's state.

German SoulSilver rev 0 (`IPGD`, SHA-1 `576cdb7eec728c49aed85b9dd7e1e084ba1f08f9`) shares these globals and the call ABI. Other German generations remain outside this change.

**French carts** (2026-11-06; Platinum `CPUF` rev 0, SHA-1 `62f9ec7a…`, HeartGold `IPKF` `dba64620…`, SoulSilver `IPGF` `6ed98d44…`, Black `IRBF`
`a559da54…`, White `IRAF` `28f07591…`): the US / Europe builds with the text changed, so the same overlays, the same file paths and text banks, the same
save layouts; only addresses moved. **Other languages** go the same way: `scripts/port_nds_fns.py US.nds OTHER.nds w:GLOBAL c:CODE[@OVERLAY] ...`
(or `--file`, a `name kind addr[@overlay]` a line) matches the two carts' functions by their instructions and calls, not their bytes, and gives each
word with how many matched functions vote for it, or UNRESOLVED (name the overlay for code: overlays overlap, and overlay 10 of Black / White shifts
part-way through). `--check scripts/port_nds_fns_known.txt`, run by `scripts/test_port_nds_fns.py`, tries it on every pair checked live so far (these
carts, German HG / SS, White from Black, Origin HeartGold) and fails on any wrong answer. The older `scripts/port_nds.py` lines bytes up and always
answers, right or not (four wrong of 21 on Origin HeartGold). What moved: HG / SS's globals +0x20,
`gOverlayTemplate_Battle` -0x1C, the field overlays +0x20, the low ARM9 functions not at all (callSet 2 serves both); Platinum's globals +0x1E0, the
battle template +0x88, its functions by 0x14 to 0xA4 (`CallFns PLATINUM_FR`, callSet 8; its own rev 0 is the US rev 1's code as far as anything here reads);
Black / White's statics and overlays -0x80, overlay 10's FLDSKILL and X menu -0x90 (`BLACK_FR` / `WHITE_FR`, callSets 9 / 10). The US saves load in every one
of them (White on Black's, as in English), so each was run headless next to its US cart with one script: field bits identical frame for frame, every pointer at
its offset. `TOUCH_RULES` and `GEN5_TOUCH` have them, so `DsLayout.followed` is on: the X menu, the party, options and Pokédex pages, the battle's panels all
reported as on the US carts (`childInit` equal to each table's word). PC, FLY (Black and White: the platform's zone header given FLY's bit, then on to Accumula), wild battles (Platinum and
HG through their own WildBattle commands; Black and White on Route 1) and EXPAND too: `widecheck`'s counts equal the US carts' on HG, Black and White; Platinum (warped to Floaroma, no US state to compare) by its `wideshot`. Platinum had no save, so a new game was played headless (Rowan's intro
wants a *touch* at each yes / no: A only nags, and its next question comes after the text ends), the party given by script, VAR_PLAYER_STARTER poked so the X menu shows
POKéMON. `FrenchDsTest` checks the configs through the US fixtures with the globals moved, and the names and art on the carts. The three French Gen 4 dumps at hand are tagged
`[b]` in their set's naming; their code and text matched everything checked here, and the app goes by the header, so a cleaner dump runs too (only its library name would fall back to the file's).

**Language ports** (2026-11-07): the carts after the French ones aren't hand-written tables but rows of `gamedata/nds/ports.toml`, one a
cart (header code + revision, the game it runs as, its title and SHA-1, its No-Intro file name) with its words under the names
`scripts/port_nds_fns_known.txt` uses. `scripts/gen_nds_ports.py` (run by `make gamedata`, checked by `make check`) writes
`app/src/main/cpp/nds/pk_nds_ports_gen.h`, from which `pk_nds_field.cpp` builds each cart's field table (a copy of `HEARTGOLD_US`,
`BLACK_US` or `BLACK2_US` with its words; callSet 100 + n / 200 + n) and touch rules, and `pk_nds_call.cpp` its functions (a copy of
`HEARTGOLD` / `BLACK`; every Gen 5 guard word is the same instruction in all of them); and `NdsPortsGen.kt`, from which `DsPorts`
builds the Gen 4 / Gen 5 configs and the detection, the library, EXPAND, DS LAYOUT and the website take the cart. Gen 5's BGM stack
depth is the stack's first song - 0xC in every build, so it isn't stored. In: Spanish / Italian / Japanese / Korean HeartGold and
SoulSilver, Europe's rev 10 of both (the US code under the same game code: only the header, the signature and one file of `/a/2/6/2`
differ), German / Spanish / Italian / Japanese / Korean Black and White, and Black 2 / White 2 in French, German, Spanish, Italian,
Korean and Japanese (rev 1). A new one: run `port_nds_fns.py
US.nds OTHER.nds --file <the US list>` (the lists are the known file's sections), add its row, `make gamedata`, then run it headless
beside its US cart on the US save with one script (the field, the X menu, `widecheck`, `action 2`, `action 1`; on HeartGold /
SoulSilver the party, options and Pokédex pages from the X menu and a wild battle through `hgwild`'s `scriptgo`) and compare the
`fieldinfo` bits and `childInit`s. Some carts' intros are slower: Spanish and French White 2 stop at the C-Gear's "continue anyway?"
where the US cart has gone on (one more A). Spanish HeartGold's low ARM9 sits 8 bytes lower than the others' (its own callSet row
covers it). Spanish White and Korean White share Black's addresses outright. **Spanish Platinum** (`CPUS` rev 0, 2026-11-09)
brought a third base, `platinum` (`PK_NDS_PORTS4P`: the USA rev 1 field / call tables, callSet 300 + n; its party and options
are top-screen pages, so no X menu task is listed): its functions are the French cart's, at the same addresses, its globals and
field overlays 0x20 above French's. No Platinum save is kept here, so it was proven on a new game played in it headless (the
intro's yes / no and the Poké Ball are touches): the field read, the save and field pointers, PC anywhere. **Japanese and Korean carts**: the US saves load in all of them but Korean HeartGold /
SoulSilver, whose save footer's magic is 0x20070903 (the US / Japanese carts' 0x20060623, outside the checksums: change the word
and the US save loads). Japanese HeartGold / SoulSilver's X menu, options and Pokédex differ in code (the tool leaves them
UNRESOLVED): read off the running game (the X menu's field task +4, each page's child init). Their text banks are numbered their own
way (`NdsArt`'s `NAMES_*_JA` / `_KO`, `DEX_*_JA` / `_KO`, Japanese Black / White's moves and categories): found by lining the banks'
line counts up against the US cart's, then read (neighbouring banks often have the same count: Japanese HeartGold's 804 is the
species in English, not the categories). Gen 4's Japanese long vowel is 0xF1, which pret's charmap writes as a full-width hyphen
(`Gen4Text` puts ー there). The text reads in each cart's language: `NdsArt.readNames` tells it by the FIGHTING type's name. `DsPortsTest` reads each port's save through the US fixtures with its globals moved, and
its names and art off the cart.

**Origin HeartGold** (v4.0.3, English patch 1.0.0-rc8 applied to the US HeartGold; SHA-1 `a54a647599d59ed0060dfc4375c289d6e041318a`):
Yannanfei TB's hack, rebuilt by Alex on hg-engine over the *Japanese* HeartGold, so its header is a real Japanese HeartGold's
(`IPKJ` rev 0) and nothing of the US cart's layout holds: 120 overlays at other addresses, a decompressed ARM9 of its own.
`OriginHeartGold.matches` tells it apart by SaveData_Get's literal pool (cart 0x2B73C holds 0x021D11B0: the save's static
block, its pointer 4 bytes in, where the US code keeps the pointer alone), and every table below is that build's; another
build fails the check and reads as not supported. The website does the same (`compat.json`'s `dsHacks`, `dsHackOf`).
Finding its addresses: each US function (pret/pokeheartgold's `.xMAP`, bytes from the US cart) searched in its ARM9 and
overlays with BL pairs and pool-looking words masked; a global through a US function that loads it from a pool, the hack's
match read at the same offset. Short functions matched look-alikes (the first "SaveData_Get" hit was another getter), so
every word was then checked live in nds_dump: `sFieldSysPtr` 0x021D31F4, the SaveData pointer 0x021D11B4,
`gOverlayTemplate_Battle` 0x020F70B8, FieldMap_Main 0x021E4C51, sSoundWork 0x021107A0 (+0xBEBFC the BGM), sSeqPlayer
0x021DEA04, Task_StartMenu 0x0203B3E9 (its StartMenu struct grew 4 bytes), and `pk_nds_call`'s functions (the PC and FLY
ran). The save is HeartGold's with a bigger bag (151 TM / HM slots for its 130 TMs) and Pokédex (a bit per species to 1600):
the sections' offsets are read off the SaveData's `arrayHeaders` (+0x2E014; `GEN4_ORIGIN_HEARTGOLD`). There was no save:
a new game was played headless (the `origin_heartgold` fixture's README; the US save isn't taken), its party and items given
through the game's own GiveMon / GiveItem. Its data is its own and read from the cart at run time (`NdsArt.readOrigin`,
`OriginHeartGold.Data`, never bundled): species / form ids to 1440 (forms past 1025 by its ARM9's form table: a party record
keeps the base species and the form), 52-byte personal records (types at 6 / 7), `/extra/new_move_data.narc` (40 bytes a
move: type 0, category 2, power 3) for its 920 moves, Fairy as type 9 and its 18 x 18 type chart (overlay 14, 4 = 1x), the
text banks renumbered (species 232, moves 739, items 219, places 272, types 724), party icons in `/data/pokeicon.narc` (7 +
2 x id, palettes by its ARM9's table), item icons at 2 x item + 2 in `/a/0/1/8`, TM tables for its 130 TMs. **Its battle
engine is its own**: `gOverlayTemplate_Battle` runs overlay 14's init, which makes a 0x274-byte manager (0x02255BAC) with
battle objects (0x414 bytes each) pointing at the parties' (encrypted) records: no BattleSystem / BattleContext. The BATTLE
tab reads those objects instead (`Gen4ManagedBattle`: HP, item, level, stats, types, stages, moves and PP, ability; the
player's six from manager +0x60, the foe's from +0x7C, each side in battle order, the one out first - a switch reorders them -
and the BattleSetup's type for trainer battles; found by diffing dumps across a Leer, an Agility and a switch). Its battle
buttons can't work: its panels aren't HeartGold's. Its touch screen's controller (manager +0x238) has an input handler at +0x10 while the battle waits
for a tap, which `pk_nds_field.cpp`'s `managedBattleInput` reads: DS LAYOUT floats the game's touch screen in then, and
SMART FF slows down. Its DEX (`POKEDEX_ORIGIN`, one national list of 1025) is written by `NdsArt.dexOrigin` from its cart:
the text banks 791 / 803 / 711, heights / weights in `/a/0/7/4`, abilities u16 (a hidden one at 0x1A), evolutions of ten
6-byte entries (HeartGold's methods 1-26, hg-engine's from 27: `Gen4Dex.originEvoReqs`), learnsets as { u16 level, u16 move },
machines by its own rule (the species' `/data/tutor_moves.narc` list or its learnset, RETURN anyone, MEW all), fronts from
`/data/pokepic_m.narc` (member 8 x species, 160 x 80, linear, unscrambled - a 37.6 MB archive, so `NdsArt.file` takes up to
48 MB). The save's Pokedex keeps 0xC8-byte flag arrays (`Gen4Config.dexFlagBytes`). No GUIDE (its world isn't HeartGold's).

**Renegade Platinum** (Drayano's v1.3.0, SHA-1 `d4ee068b7e0a19a848436369f145e3154afe13ca`): US Platinum rev 1 edited in
place, header and all (`CPUE` rev 1), so everything that goes by the header (the Poller's `detectDs`,
`pk_nds_field.cpp`, `pk_nds_call.cpp`, EXPAND, STEADY) runs it as Platinum, and it should: its RAM, save layout and ARM9
are retail's (one ARM9 word differs, the shiny odds); of the overlays only 11, 16, 21 and 73 changed. What the companion
would show wrong is its data: types (Fairy is type 9 and every species' typing is its own), move power / type /
category, the type chart, TMs and the party icons' palettes. `RenegadePlatinum.matches` tells it apart by battle overlay
16's pool word at cart 0x221060 (0x021E9F81, where retail's overlay holds 0x0226ECD5): the address of the moved type
chart (overlay 21, RAM 0x021E9F80, cart 0x2AFC00, retail's triples of attacker / defender / x10 with Fairy's rows
added), the same word the game's own lookup loads. The battle's chart in RAM was compared with the cart's in a wild
battle: equal. The Classic patch of the same release differs only in `pl_personal.narc`, so it passes the same check and
reads its own types; it only needs its SHA-1 and title. The data is read from the cart at run time
(`NdsArt.readRenegade` -> `RenegadePlatinum.Data`, never bundled): `pl_personal.narc` types at 6 / 7, `pl_waza_tbl.narc`
(category 2, power 3, type 4), the chart, the TM table and the icon palette bytes in the ARM9 (retail's offsets:
`tmMovesPlatinum` / `iconPalettesPlatinum` come out equal on a retail cart, which `RenegadePlatinumTest` checks), and
each map header's wild set (+0x0E) into `pl_enc_data.narc`'s grass and surf rates for WILD ENCOUNTERS' restore.
`ActiveTables.renegadePlatinum` (set by the Poller) switches the move / type / chart lookups and the art directories
(`nds/renegade/`, its own icons, items and DEX pages); `GEN4_RENEGADE_PLATINUM` is Platinum's config with
`POKEDEX_RENEGADE`. Its names come from its text like every DS cart's (`NdsArt.readNames`), which matters here: it put
about a dozen new moves in old ones' slots (Horn Drill's is Drill Run, Submission's Play Rough), renamed a few items
(429 Prism Scale) and writes species in mixed case. No GUIDE: its trainers and wild sets are its own, and Platinum's
generated guide would be wrong. Its save is retail's format: a new game was played headless, saved in game and rebooted
(the `renegade_platinum` fixtures' README); the rival's visit at the start and the walk out were skipped with a `warp`
script (scrcmd 0xBE) for the EXPAND / FLY checks. **Other builds** (2026-11-09): v1.3.0 with its Classic patch
(`8b9d933e…`) and v1.2.0 (`381f9eef…`, dated by the release's changelog) pass the same check and read through the same
places: v1.2.0's ARM9, overlays 16 / 21 and the four ARM9 tables are v1.3.0's byte for byte (overlay 16 one data byte
apart), and its own 12 changed files (personal, moves, trainers, encounters...) come through the file system. Both builds
keep the original typings of a few species v1.3.0 retyped (`RenegadePlatinumTest`'s "other builds" case).

**Blaze Black 2 Redux / Volt White 2 Redux** (Drayano and AphexCubed's v1.4.1; `Black2Redux`, eight SHA-1s): xdelta patches
on the US / Europe Black 2 and White 2, each with three more on top (Classic, EVless, Classic EVless). The headers stay
the retail carts' (`IREO` / `IRDO` rev 0) and so do the RAM, the save and the code the native side calls (the PC's and
FLY's code checks pass): the player's retail saves load in them, and `GEN5_BLACK2_REDUX` / `GEN5_WHITE2_REDUX` are the
retail configs with a DEX of their own. Changed: overlays 36, 166-168, 207, 255 and 265, and 33 data files (species,
moves, items, trainers, encounters, text, icons). The ARM9 is stored unpacked (retail's is BLZ-packed) with its tables
where the unpacked retail ARM9 has them: sTMHMMoves at 0x0208CC84 (15 TMs teach other moves), the party icon palettes at
0x0208C54C, the item icon pairs at 0x0208CD50 (White 2's all 0x2C later). Battle overlay 167 loads 0x180 lower and opens
with an 18 x 18 type chart (Gen 5's ids, Fairy 17; 4 = 1x), Gen 6's: `Black2Redux.matches` reads overlay 167's RAM address
from the overlay table (now at cart 0x9EE00, so its entry is at 0xA02E4), 0x02199740 / 0x02199780 against retail's
0x021998C0 / 0x02199900 (the retail table sits at 0x77800). Everything is read from the cart at run time (`NdsArt.readRedux`):
`/a/0/1/6` types at 6 / 7, `/a/0/2/1` (type 0, category 2 - Gen 5's 0 status, 1 physical, 2 special, turned into the
app's order - power 3), the chart from overlay 167 (BLZ, `NdsArt.overlay`), the ARM9 tables, and each zone's wild set
(`/a/0/1/2` +0x14, bits 0-12) into `/a/1/2/7`'s per-season rates for WILD ENCOUNTERS. `gen5Type` maps 17 to the app's 9
(Fairy in "???"'s slot, as Renegade Platinum, Glazed's names and chart); the battle structs and the DEX go through it too.
Their art and DEX go to `nds/redux/` with the build's personal-records CRC in the marker (Classic's species differ). Names
come from the cart's text as every DS cart's (Power-Up Punch in Comet Punch's slot, Dual Wingbeat in ViceGrip's...). No
GUIDE: their wild Pokémon and trainers are their own. The White 2 dump on this machine (`[b]`) has its DSi ARM9i's last
byte (0x11824C37) at 0xFF; Volt White 2's patch reads it as 0x00 (its checksums only pass with that byte put back).

**GUIDE.** `scripts/gen_gen4_guide.py` writes `GuideGen4PlatinumGen.kt` / `GuideGen4HeartGoldGen.kt` from the decomps,
numbers only: every map header's wild set (Platinum's grass with its day / night pair, HG / SS's per time of day and
each version's own branch, surf, rock smash, rods and HG / SS's night fish), the bosses' teams, and HERE's item balls
and hidden items with their done flags, keyed by place. `Gen4World` (`GuideGen4.kt`) picks from them for the save and
the DS clock's hour; `readGen4Progress` reads the save's VarsFlags (`Gen4Config.varsFlagsOff`: Platinum's at body
0xDAC, after a bag 12 bytes longer than its pockets) plus the badges from TrainerInfo as flags (`GEN4_BADGE_FLAG`):
gym leaders count by badge, since HG / SS clears the leaders' own trainer flags again for rematches. The hand-written
pages (`GuidePlatinum.kt`, `GuideHeartGold.kt`) come from the decomps' scripts, in our own words, `verified = false`.
`Gen4GuideTest` pins them on the fixtures; ui-preview's `*-real-guide*` shots render them.

**EXPAND.** The overworld is 3D, so the margins are drawn, not rebuilt: every polygon the game submits is clipped again
shifted by about a screen into lists of our own (left, right, above, below, the corners), and a second unthreaded
software renderer draws only the rows and columns the shown window needs. The middle is the game's own frame, untouched
(`nds_dump`'s `widecheck` must find no difference). Battles carry their 512 px BG3 background past the screen, the
battle's 3D over it (HG/SS's Pokemon are polygons: they slide in from past the edges), the scenery sprites behind the 3D
(the platforms in 64 px pieces and the trainer: each piece the screen shows part of, drawn from its own tiles, and the
pieces wholly off it that sit flush beside one of the same row, size, palette and priority - a lone off-screen sprite's
9-bit x could be either side, and games park unused ones there), and the HP boxes, the text box and the field menu's
bar to the edges, from the nearest steady rows (sprites over the 3D, tracked per row, counted in game frames so FF
carries them at the same frame as 1x; a row only where the sprite's edge pixel is opaque). The 2D state the margins
follow (engine A's registers, palettes, OAM) is a snapshot taken as line 192 starts (`GPU::VisibleEndHook`): read after
the frame, it was already the game's VBlank handler's next frame - a battle's fade-in a step brighter than the screen,
scroll and sprites a step on. `nds_dump`'s `look DX DY` moves the window a frame at a time, as the look-around does.

**DS LAYOUT (one screen).** With no second screen, held sideways, `GameStageLayout.dsArrange` places the two screens by
`DsLayouts.arrange` (the game in the top box, `NdsTouchScreenView` in the touch box, edge to edge; behind both the game's own
picture, `DsShots`: a PixelCopy of the game view about once a second, the overworld kept apart while a menu screen is up and
drawn sharp under its dim, else the newest drawn blurred; the HUD over the whole area, its rects moved by `dsGameOffset`). ONE SCREEN and PICTURE IN PICTURE float the touch
screen in (`DsTouchWindow`) while `EmulatorEngine.dsTouchWanted` says the game wants it: the field read's
`PK_NDS_FIELD_TOUCH` (and `_TOUCH_BATTLE_MENU` where BATTLE BUTTONS can't stand in), from `pk_nds_field.cpp`'s touch rules
(HeartGold / SoulSilver so far, found headless on the player's save with nds_dump's `fieldinfo`, `dump` and frame-by-frame
`peek`s): the X menu is the field task `Task_StartMenu` (0x0203BEF1, pokeheartgold's .xMAP; its buttons are on the touch
screen, the top one only says "look below"); every menu screen it opens (party, bag, Pokégear, Pokédex, card, options) is a
child process with its controls there (shown side by side, `EmulatorEngine.dsMenuScreen`, not floating) - but the party's
first page (app init 0x02078E31), the options (0x021E5901) and the Pokédex's first page (0x021E5AA1, SoulSilver's 0x021E5A9D -
the one address that differs between the two games; the rest were checked on the SoulSilver save too - while its state, the
OverlayManager child's +0x14, is 0 / 2 / 5 / 6: opening and the first page; 0xB is the list) float alone over the overworld
(`PK_NDS_FIELD_TOUCH_ONLY`, `touchOnlyPage`; the summary 0x02088299, the bag 0x021F9381, the card, the Pokégear stay side by
side); a battle's touch
panels hang off BattleSystem.battleInput (+0x19C), whose text objects (+0x4D0, non-NULL only while a panel is drawn) say one
is up, and battler 0's selection step (BattleContext's first byte: battle_controller_player.c's SSI states, 1 / 2 commands,
3 / 4 moves, 8 bag, 10 party) says which, while the controller waits (command 5): 1 - 4 are BATTLE BUTTONS' (curMenuId,
+0x68B, isn't used - HG redraws the commands under another id after a move's CANCEL), the bag and the party (screens of
their own, battleInput freed meanwhile) and any panel outside the selection (YES / NO, "Use next Pokémon?") the touch
screen's. Any game: a one-colour top screen over a touch screen that isn't (the title's CONTINUE) wants the touch screen too
(`pk_nds.cpp`'s `oneColour`). `readGen4BattleInput` reads the same for BATTLE BUTTONS
(`NdsBattleTaps`: the stylus on the game's own buttons) and `DsBattleMenu` (the battle stays wide; a strip over the text box's
rows 144-191 in the band's #202020, HG's message window drawn at its far left - outline, gold line, slate frame, sampled
headless - with the battler's nickname, the game's four buttons in its colours at its right, a 2 x 2 with FireRed's no-wrap
D-pad and pulsing red corners, moves a card each on one line; the D-pad is the menu's, `PokeDaisyActivity.dsBattleTakes`, with
the game view focused so no press goes to leaving touch mode). Steps 0 - 6 and 15 of the selection (the command init the move
panel's CANCEL passes through, two frames with the panel drawn) are the battle buttons' own, never the floating touch screen.
**Fades** (nds_dump's `trace` prints each screen's one-colour state): leaving a menu screen, HG fades both screens and keeps
them black for up to ~80 frames before the X menu or the field fades in; a battle's end ~95. While both are black
(`PK_NDS_FIELD_DARK_TOP` / `_BOTTOM`) every DS flow holds, and the first lit frame decides at once (so `TOUCH_OFF_FRAMES` is 6,
not the 12 that used to bridge the gap - and closed the window on the way back to the X menu); the window hides while the touch
screen is blank, and the black between a menu and the field is covered by the overworld's last picture (`DsShots.field`). The
battle's bag and party fade the touch screen out and in: `PK_NDS_FIELD_TOUCH_FADES` makes them float in only once it has been
black and then lit for 12 frames, and their window ends at the first black after (leaving the bag, the battle draws a Poké Ball
for 5 frames while still at the bag's step; no black inside the bag's pockets or the party's summary, headless).
The cover shows `DsLoader` (the PokeDaisy flower hopping in whole pixels, three dots in turn) once a wait has lasted 160 ms;
a side-by-side menu screen whose top half is already black (the card blanks it 16 frames before its touch half) counts as
covered too. **The X menu after a screen left by touch**: HG's `Task_StartMenu_WaitApp` closes the menu when the screen it
opened ends with `FieldSystem.menuInputState` (+0x10C) at TOUCH - so a screen's own Cancel / Quit button returns to the
field, B to the menu (headless: the card's Cancel, the menu back for 11 frames, then gone). With the X menu floating (one
screen), the screen's closing fade - black after it was lit, `menuLitFrames` - sets it to buttons
(`pk_nds_field_keep_start_menu`), so the menu comes back with its cursor; the screen itself still opens as it was opened. LARGE + SMALL and PICTURE IN PICTURE give
the HUD's CORNERS `DsCornerLayout` (FieldHud's `dsCorner`: the buttons bottom left, the party in two columns under the
minimap, three to a row where `GameStageLayout.dsTouchOverGame` says PIP's inset would meet it). Outside the field and battles
(`EmulatorEngine.dsWideScene` off) EXPAND has nothing to widen, so the stage keeps the game's 4:3 with the backdrop beside it. A game without rules (the German HeartGold / SoulSilver) gets ONE SCREEN as PICTURE IN PICTURE (`DsLayout.forGame`).

**Platinum's and Black / White's rules (2026-11-01).** Platinum (a new game played headless, given two Pokémon and a wild
battle through its own script commands: `GivePokemon` 0x96, `StartWildBattle` 0x124, `SetVarFromValue` 0x28 on
VAR_PLAYER_STARTER 0x4030 and `GivePokedex` 0x158 for the X menu's POKéMON / POKéDEX, run with nds_dump's `script`):
its X menu is a top-screen list (no field task to follow), and the party (app init 0x0207E0B9) and the options
(0x021D0D81) are top-screen pages whose touch screen is a picture: `topOnly`, reported as `PK_NDS_FIELD_TOP_ONLY`, so the
game stays alone (not side by side). Its battle is HeartGold's ancestor: BattleSystem.battleSubscreen at 0x19C (its first
word points back at the BattleSystem), the first font OAM at 0x4D0 set while a panel is drawn, the selection steps and
touch rects the same. Black / White (the player's save; White's wild battle, Black's field): the X menu is a GMEVENT
(parent at +0, function at +4: Black 0x0215FD0D, White 0x0215FD2D) and the save's question runs two events under it, so
the read walks up to three parents; the party (overlay 91), trainer card (111) and options (68) are touch-only
apps, the Pokédex (212) and the bag (70: the item picked shows on the top screen, 2026-11-03) both. The battle's touch screen came from the heap's named blocks (a 'DU' header, the source file's
name 0x14 in, the data 0x2C in): client 0 +0x54 -> btlv_core, +0x180 -> btlv_scd, +0xB0 -> btlv_input; input +0x58 the
panel (1 commands, 2 moves, 4 a prompt), input +0x08 its slide task (taps before it's NULL are lost), scd +0x60 a panel
up, scd +0x98 the screen opened from it (2 bag, 3 party; stale afterwards, so only under command 3); the adapter's command 5
is a fainted Pokémon's party, 7 a prompt. Their move panel reports itself for 3 frames between its two slides, so
`NdsBattleTaps` waits for the state to hold. The post-battle "give a nickname?" is its own proc (overlay 92): a menu screen.
Black 2 / White 2 (2026-11-02, Black 2's wild battle and both games' X menus headless): the same reads with their own numbers - the X
menu's function 0x0215A875 (White 2 0x0215A8B5), the touch-only apps party 165, trainer card 186, options 140 (the
Pokédex 302 and the bag 142 both screens), btlv_scd at core +0x1C4, btlv_input at scd +0xBC, the opened screen at scd +0xA4; their buttons sit
where Black's do (`NdsBattleTaps.GEN5` checked on Black 2). Its BattleContext / BattleSystem are Platinum's layout (battleMons at
0x2D40, 0xC0 each). A blank touch screen (`NdsBottomFrame.blank`: an evolution, a fade) never floats in.

**DS CPU BOOST.** `ARM9ClockShift` 2 (the ARM9 at twice the DS's, as melonDS's DSi mode runs it), switched between frames the
way `DSi::Set_SCFG_Clock9` does (timestamps rescaled, `UpdateRegionTimings`, the JIT's block cache dropped: `pk_nds.cpp`'s
`setArm9Shift`). States and STEADY's copy are always taken and read at 1 (melonDS stores the timestamps as they are). Found by
hashing every frame headless while walking in HeartGold (nds_dump's `arm9shift`, `shots`): at 1 the 30 fps overworld holds a
frame for 3-4 refreshes every few steps (the RP6 the same, while the fps line's `repeats` / `unshown` - refreshes with no new
frame, frames never drawn - stayed 0: the app showed every frame); at 2 and 4 alike, a steady 30. The game spends the
cycles (the RP6's work 3.8 -> 7.3 ms a frame), so it's a setting.

**DS RESOLUTION (the GPU path).** `ORIGINAL` is the software renderer above, pixel for pixel; `2x`-`8x` switch melonDS to its
OpenGL renderer (`nds/pk_nds_gl.cpp`, between frames, back and forth while playing). The patch ports it to OpenGL ES
(`MELONDS_GLES`): the GLSL 1.40 shaders rewritten to GLSL ES 3.00 when compiled (`OpenGLSupport.cpp`'s `ToGLES`: version
line, precisions, `layout(location)` for the outputs) plus the few lines ES rejects (int literals as floats), the
palette as raw R16UI halfwords decoded in the shader (ES has no 1_5_5_5_REV), `glMapBufferRange`, an RGBA readback for
display capture, and the fog / edge passes into a framebuffer without the depth texture they read (a feedback loop on
tile GPUs). At 2x and up opaque polygons grow by a texel (two from 5x), each edge offset outward square to itself
(`GrowPolygon`): Gen 4's map blocks meet corner-on-side, and at a higher resolution the clear colour showed through as
black cracks along the paths (pushing corners away from the centre left long thin strips' cracks open). It needs ES 3.2, or 3.0 / 3.1 with OES / EXT_draw_buffers_indexed (`glColorMaski` is loaded at run time into
`OpenGL::ColorMaski`); without either the row is `N/A`. Our hooks on the renderer: two colour textures drawn in turn (the
3D a frame shows survives the next frame's, drawn at line 215 before the frame ends, so we compose after `RunFrame`),
`BlitHook` in place of melonDS's compositor (records which texture the frame showed), and `Wide`, a polygon list of ours
drawn instead of the game's at any view size and scale. Our compositor (one shader in `pk_nds_gl.cpp`, melonDS's own
blend maths) draws the top screen's window into a SurfaceTexture the view hands over (`EmulatorView.gpuFrames`;
`pkNdsGlFrameInfo` matches each buffer's timestamp to its window and size) and both screens at 1x into a PBO, read a
drawn frame later, for the bottom display, thumbnails and EXPAND's checks. The EGL context lives on the emu thread for
the game; the view's context shares nothing with it. **EXPAND on the GPU**: the slices give way to one list - every
submission mapped from the window's view onto clip space (as the box test's retry maps it) and clipped once, placed in
the view's own pixels (`submitGpu` / `swapGpu` in `pk_nds_wide.cpp`) - drawn by the renderer as one picture, so no seams
and none of the slices' CPU cost; a submission it can't take (a viewport not the whole screen, the list full) draws the
game's own list for that frame. The margins' 2D (fog layers over the 3D, a battle's BG3, the screen's edge rows carried
on) is planned per pixel on the CPU as before (`PkNdsWidePlan`: a colour, the 3D alone or under a 2D colour, or a
screen pixel to repeat, or a battle's 3D over its background / a sprite) and put together by the compositor. The list
is clipped at the view a flush before it's drawn and shown a frame after that, so a moving window (the look-around, a
zoom easing out) showed black at its leading edge for two frames: it's built `GPU_HEADROOM` (32) DS pixels wider each
way, and the renderer draws only the part the window needs at line 215 (`WidePolygonList::Crop*`: the colour texture
holds that part, the viewport is the whole view moved onto it), `GPU_PAD` more each way, with the window's last step
added once more ahead (`pk_nds_wide_need`, which the slices follow too). Far zoom-outs draw the 3D at a lower scale (a
budget of 1.75 screens at 8x: a 16:9 window at 1x and its pad stay at 8x). Headless: nds_dump's `NDS_DUMP_GL` build runs
it through ANGLE (its README).

**EXPAND's slices off the emulator's thread** (2026-11-12). With the software renderer the margins are slices, each a
second SoftRenderer of its own drawing the lists the last VBlank swapped. After each frame they go to four helper threads
and draw while the next frame runs: what they read (our lists, the 3D registers latched at VBlank, the textures in
`GPU::VRAMFlat_*`, copied at line 215) doesn't change before the next VBlank, where `onVisibleEnd` waits for them
(`waitSlices`; `pk_nds_wide_sync` before a state load / save, a reset or another renderer). HeartGold walking headless
(the interpreter, as a 32-bit device runs it): EXPAND's cost a frame 1.15 -> 0.45 ms at 1x, 3.8 -> 1.45 ms zoomed out,
the emulator waiting ~0 ms; every `widecheck` count and wide shot the same as drawn in place, and a ThreadSanitizer
build of nds_dump found no race (it did find helpers restarted at an old job generation when EXPAND went off and on:
`stopPool` resets it).

**DSperate, the second DS core (DS CORE, 2026-11-10).** For devices too slow for melonDS (the RG DS's Cortex-A55s, the
Retroid Pocket Duo Lite, 32-bit tablets: melonDS has no JIT on 32-bit ARM), `third_party/dsperate` (beebono/DSperate, GPL-3.0,
the submodule the Linux port uses) runs DS carts too: its ARM -> AArch64 and ARM -> ARMv7 recompilers, fast memory (a memfd
mapped in 4 KB views; off on a 16 KB-page kernel), the 3D on worker threads and idle-loop skipping. `nds/dsperate.cmake` builds
its `src/core` alone (never its top-level CMakeLists: it turns LTO on for everything and builds ARMv7 hard-float, which isn't
Android's ABI) and makes what the NDK's clang and bionic need **without touching the submodule** (the Linux port builds it
unmodified): sources clang refuses are copied into the build directory with the fix (`dsperate_fix`: a braced NEON vector in a
macro argument, a `const void*` cast to a function pointer; a fix whose text has moved fails the configure), `dsperate_android.h`
is forced into every file (bionic's `PAGE_SIZE` macro, `aligned_alloc` before API 28, defined in `pk_nds_dsp.cpp`), and its
tiny-AES-c is renamed (`pk_dsp_AES_*`: melonDS bundles its own). The app side: `pk_nds.cpp`'s functions are `pk_ndsm_*`,
`pk_nds_dsp.cpp`'s `pk_ndsd_*`, and `pk_nds_core.cpp` defines pk_nds.h's from the list in `pk_nds_cores.h`, handing each call
to the core that's up (`pk_nds_set_core` before `pk_nds_init`, from `EmulatorEngine.ndsCore`, from `companion.DsCore`: AUTO =
DSperate where `DevicePerformance.looksWeak` or the process is 32-bit). On DSperate the field read is the same RAM-only
`pk_nds_field.cpp` (BG tiles and master brightness from DSperate's GPU), saves the same quiet-frames flush, audio resampled
linearly from its ~32728 Hz mixer; EXPAND, DS RESOLUTION, the game's own functions (PC / FLY), STEADY music and DS CPU BOOST are
melonDS internals and report off (the activity greys them out: `PokeDaisyActivity.dsperate`). States are each core's own,
behind a header with the save's CRC32 ("PKDS" melonDS, "PKDP" DSperate): `pk_nds_state_matches_save` answers 2 for the other
core's, which the engine parks as `resume.melonds` / `resume.dsperate` (`SaveStates.parkedResume`) and the next start on that
core takes back; a DSperate state loads into a second console first (a failed load leaves DSperate's machine unusable), and a
save file of the wrong size for the chip makes DSperate refuse to start rather than cut it. Headless: `native-capture/dsp_dump`
(its README) runs nds_dump's script commands through the app's `pk_ndsd_*`, so one script runs on both cores. HeartGold walking,
an arm64 Linux container on the Mac (4000 frames): DSperate 0.69-0.92 ms a frame wall, 1.07-1.68 ms CPU; melonDS as the app runs
it (JIT, 3D on the emulation thread, DS CPU BOOST) 2.86-3.15 ms, single-threaded. On a Galaxy Tab A (SM-T510, Exynos 7904,
Android 11, a 32-bit app), HeartGold walking: DSperate 60 fps, 12-17 ms of work a frame; melonDS 16 fps, ~63 ms (no JIT there).
32-bit ARM faults on clang's aligned NEON forms where a byte buffer is cast to `u16*` / `u32*` (its depth pre-pass's pass plane
killed a 3D band worker with SIGBUS on the tablet; arm64 doesn't care, so headless never showed it): those four sites are
`dsperate_fix`es too, through `dsperate_android.h`'s `pk_dsp_load16` / `load32` / `store32`. Check a new DSperate on a 32-bit
device, or disassemble the armv7 library for `[rN:16]` / `[rN:32]` forms in `ds::gpu::kern::neon`.

**Fast-forward.** A shown frame of an FF group draws; the others skip drawing (above), which took 4x from ~200 to 240
DS frames a second on the Thor. Whether the next frame is seen is decided a frame ahead (its 3D starts at line 215,
EXPAND's slices are handed out after the frame before), and FF can end a group part-way (SMART's 1x for a menu, FF off, the
refresh pacing dropping out): `pk_nds_run_frame` then skips that frame too and shows the next, whole - drawn, it came
up with an older frame's 3D and EXPAND's margins black, a flash each time (nds_dump's `ffmode` / `ffgroup` / `ffguard`).
SMART's battle wait reads HG/SS's `BattleContext.command` (5 = SELECTION_SCREEN_INPUT) and reports it as `MgbaCore.FIELD_BATTLE_INPUT`. STEADY music is a second melonDS loaded from
the game's own state with its ARM9 stopped: the sound driver runs on the ARM7 from data already in RAM, so it plays on
from that moment by itself, loops and tempo included. Two things the ARM9 does every frame the copy does for it: drain
the ARM7's FIFO (full, it stalls the driver into a buzz) and clear bit 0 of the shared flag at 0x027FFF96 (Gen 5 writes
it 0x02FFFF96, the same byte; the ARM7 sets it and spins until the ARM9 clears it - in Black 2 the copy froze into a
drone 15 frames in). It draws no video. A new song id starts a new copy, taken only once the song's own sequence player
has settled for a few frames (`pk_nds_bgm_settle`: no fade, and on Gen 5 the player holding the BGM stack entry's handle
not paused - a song coming back after a battle sits paused at 0 for a few frames, and the ARM9 marks a song started a
frame before the ARM7 gets it: copies taken then stayed silent); a change of that player's state later takes it again,
or fades the copy out when the game faded the song out. Check with nds_dump's `steady G FILE` against `steady 0 FILE` (the
game's own audio) over the same script (2026-11-01: Black's Battle Subway, Black 2 in and out of a Pokemon Center).

**Headless.** `native-capture/nds_dump` (its README): scripted input, shots, RAM dumps, states, EXPAND checks, music
copies, calls and scripts. The fixtures `platinum`, `platinum_battle` and `heartgold` are its dumps (main RAM with only
the read ranges kept); `PlatinumDecodeTest`, `NdsArtHeartGoldTest` and the reader tests run on them (the cart-backed ones
need the ROM, like the other ROM tests). No Platinum save existed: one was played headless; HG/SS use the player's own.

**Black (Gen 5).** pret has no Gen 5 decomp, so everything came from headless captures of the player's save (8 badges,
saved at Gear Station) and from the cart's own files. Heap blocks keep their source file's name (`save_control.c`,
`procsys.c`, `game_data.c`, `btl_pokeparam.c`, ...) right before their data, which is how most structures were named.
- The whole save image (the save file's first 0x24000-byte copy) lives in one heap block and the game plays from it:
  `[0x020A7298]` (SaveControl) -> +0x10 -> +0x34. Party 0x18E00 (Gen 4's 0xDC-byte encrypted struct, species to 649), bag
  0x18400 (five pockets of {item, count}, plain), trainer 0x19400, money / badges 0x21200 / 0x21204, Pokédex 0x21600
  (caught bits, then four seen sets), event vars / flags 0x20100 / 0x2037C, the Repel's steps 0x21B31. Its Location
  (0x19500) only moves on a warp; the live zone and position are the GameData's (`[0x02146248]` GameSystem -> +0x1C ->
  +0x114). `Gen5Reader.kt` reads all of it; fixtures `black`, `black_battle` (Battle Subway), `black_route1`,
  `black_wild` (`BlackDecodeTest`).
- The scene: the GameSystem's proc system runs one proc at a time, and its overlay says which (21 the field map, 93 a
  battle, anything else an app over the field: the party, the bag). The GameSystem's field map is NULL while a
  full-screen app or a battle runs, its event (GMEVENT) non-NULL while a script, a text box, a menu or a warp holds the
  field; the field's text box is engine A's BG1. The battle's main module (the proc's work) has the battle parties at
  +0xCC (`BTL_POKEPARAM` pointers, member 0 the one out: the array reorders on a switch, +0x19 keeps the original slot) and
  client 0 at +0x10, whose adapter (+0x50) holds the command at +0xF: 3 while the player chooses (SMART FF's battle
  wait). WILD ENCOUNTERS zeroes the seven rates of the zone's encounter table (the GameData's event data +0x38, the
  season's block of `/a/1/2/6`, reloaded on every zone change); the bike is the GameData's move form (+0x154). The BGM
  is a stack of songs (depth 0x020AA1B0, songs from 0x020AA1BC, 0x1C apart). All in `pk_nds_field.cpp`'s `BLACK_US`.
- EXPAND in a battle: Black's battle is all 3D (the sky and ground too, the Pokemon as 3D quads; BG3 is the 2D over it),
  so it gets the overworld's margins (`pk_nds_battle_3d`: the slices / GPU list past the screen, the text box's band
  carried by its edge-to-edge rows), not Gen 4's BG3 battle carry (`pk_nds_battle_margins`), which streaked its HP boxes.
  The boxes stop at the screen's edge there.
- Its own look on the companion (sampled headless from its screens, HP poked into the live party for the fainted / low
  states): the party's eight-sided slots (`BlackPartyPalette`: `PartyPalette.chamfer`, a diagonal `stripe`; the game
  sets its right column a quarter of a slot lower, the companion keeps them level - the user's call) on its navy grid, the bag's charcoal rows on its blue dots, the Pokedex's green grid
  (`DsBackdrops.kt`'s `DsPattern`s: a small tile repeated in GBA pixels). Platinum got the same treatment (its party's
  slate stripes and blue slots, its bag's white list and purple bar), HG / SS a Pokedex backdrop (white, grey grid).
- Getting out of the save's spot: Gear Station's hall is a rail map; the escalator's warp (the zone's event data in RAM)
  was pointed at another zone's door and the save moved on from there (`black_route1`'s README).
- The cart: Gen 5's text is UTF-16 encrypted per line (`Gen5Text.kt`; `/a/0/0/2`: species 70, moves 203, items 54,
  places 89), the ARM9 is BLZ-packed (`Gen5Lz.kt`), sprites LZ11. `scripts/gen_gen5_tables.py` writes the names, types,
  moves, items and their pockets, zones' places and FLY spots (`Gen5TablesBlack.kt`, `Gen5ItemsBlack.kt`);
  `scripts/gen_gen5_guide.py` the wild sets by season, the bosses' teams and HERE's item balls (`GuideGen5BlackGen.kt`,
  read by `Gen5World`); `NdsArt` rebuilds the icons, item icons, fronts and DEX pages (`dex.tsv`, types in the cart's
  Gen 5 ids, `Gen4Dex` maps them). Type ids: Gen 5 has no ??? type, so its 9..16 are the app's 10..17 (`gen5Type`).
- The MAP tab is the Town Map key item's (its touch screen), rebuilt from the cart by `NdsArt.townMapBlack` (a/0/8/5): two
  8bpp BGs over member 0's 16 extended palettes - the aerial picture (screen 16 over tiles 7) and the routes and places on
  it (screen 17 over tiles 5), 256 x 168 - and sprites from member 12's cells over tiles 8 (4bpp, 128-byte tile numbers:
  the NCER's mapping mode) in member 2's palettes: a town arrived at gets cell 4 over the BG's grey middle (Victory Road
  19, Unity Tower 23 + 19, shown only once arrived at; Liberty Garden's island 20, shown here once the Liberty Pass is in
  the bag - the record's +0x20 condition isn't decoded), the player the figure (8) under the red arrow (7), the cursor the
  ring (11 / 12). The places are a/0/8/6 member 0 (61 x 54 bytes: zone +0, the point +4 / +6, kind +0x16, arrival flag
  +0x1E = 2480.., the same base as Platinum's); `gen_gen5_tables.py` writes them and each zone's place on the map
  (`Gen5TownMapBlack.kt`: its own record, else its parent zone's at the zone header's +0x18, else one of the same name).
  The game's cursor snaps from place to place; `blackTownMap` gives the 8 px cells around each point (within 8 px of the
  point and 4 px up / down) to its place and draws the player and the cursor on the point. Checked against the game's own
  Town Map headless (nds_dump: X > BAG > KEY ITEMS > Town Map from `black_route1`, arrival flags poked off and on in the
  save image): the picture equal pixel for pixel where no sprite is, each sprite where the game puts it
  (`NdsArtBlackTest` pins the CRCs, `BlackDecodeTest` the fixtures' places).
- PC and FLY (`pk_nds_call.cpp`'s `Gen5Fns`, traced with nds_dump's `watch LO HI` - the ARM9's own reads / writes of a
  range with its registers and, with `WATCH_STACK=N`, its stack: who set the GameSystem's event when A was pressed at
  the PC, at a nurse, and when FLY was picked from the party menu). The GameSystem runs one GMEVENT at a time (+0x18);
  each frame with none, `GameSystem_Main` asks its event check (+0x10, the field's in overlay 21, given the field map)
  and runs what it returns. There's no field map main to hook as on Gen 4, so the hook takes the check's place for one
  frame - the game calls it only when no event runs, the moment pressing A would start one. A job that starts an event
  stores the check back, makes its event, sets it (`GameSystem_SetEvent`, 0x02012109) and goes on into an ARM9
  `movs r0, #0; bx lr`, so the check can't replace it. PC: the script event (0x02158769: gs, script, object) of the
  Pokémon Center PC's script, 10090 (what the check starts at Accumula's PC; the globe beside it is 2108). FLY: the
  party menu's FLDSKILL calls (overlay 10): the check work from the field map (0x0215E841), the skill's test (0x0215EA15:
  FLY = skill 4, allowed where the zone header's +0x1E bit 13 is set - outdoors), and its event (0x0215EA39 with the
  parameters { party slot, 4, zone }), which lands on the zone's spawn (`flySpotsBlack`' x / z are those, checked).
  The test and the event run together in a small helper of ours (`stub/pk_nds_skill.c`, in the spare ITCM), so a pick
  made after walking indoors starts nothing. Black has no badge rule for FLY (the save's badges cleared, the party menu
  still flew); the app offers the towns whose arrival flag (2480 + n) is set, as places (`Gen5Field.kt`: the Town Map's
  section ids). Checked headless: the PC's boxes opened and closed back to the field (walking, on the bike too), FLY to
  all 15 towns the save has, refused in a Pokémon Center, in a battle, a text box and the X menu; an in-game save after
  each loaded again at the new place.
- EXPAND: the field as on Gen 4. A battle's margins stay plain (`pk_nds_battle_margins`): its scene is all 3D and the
  battle carry, tuned on HG/SS, stretched its HP boxes across them; a battle pulls the view back to the screen anyway.
- Not done: battle input, the Elite Four's done flags (they're
  cleared at the League's door), Ghetsis' and N's (done with the National Dex, which Juniper hands over after the end).

**White (Gen 5).** White (`IRAO` rev 0, `GameKind.WHITE`, `isGen5` with Black) is Black's game: only 5 of the cart's 247
files differ (the wild sets `/a/1/2/6`, the Town Map's places `/a/0/8/6`, and `/a/0/2/6`, `/a/1/7/8`, `/a/2/3/1`, which the
companion doesn't read), and the ARM9 and overlays are Black's code with the overlays and the statics 0x20 higher (the
ARM9's low code - `returnZero`, `GameSystem_SetEvent` - where Black's is). Each address was found again through the code
that uses it in both carts (a literal-pool word's code before it, found in White's binary; a function by its bytes) and
checked headless: White loads Black's save as its own, so the `white*` fixtures are that save (Gear Station, Route 1, a
wild battle, White Forest).
- `pk_nds_field.cpp`'s `WHITE_US` and `pk_nds_call.cpp`'s `WHITE` (callSet 4), `GEN5_WHITE` (`Gen5Reader.kt`): the
  SaveControl pointer 0x020A72B8, the GameSystem 0x02146268, the BGM stack 0x020AA1D0 / 0x020AA1DC, sSeqPlayer 0x0214E044;
  the field's event check 0x0218A095, the PC's script event 0x02158789, FLY's skill calls 0x0215E861 / 0x0215EA35 /
  0x0215EA59. Checked: the field, a text box, the X menu, a wild battle (command 3 at FIGHT), the PC, FLY refused on the
  platform (indoors) then to Accumula Town and White Forest, WILD ENCOUNTERS' hold.
- Its names, items, icons, zones and their places are Black's (`gen_gen5_tables.py BLACK WHITE` checks it, table for
  table), so White reads Black's tables; its Town Map has White Forest (zone 424) where Black's has Black City (zone 0),
  at the same point: `Gen5TownMapWhite.kt` (its places, zones and FLY spots), `whiteTownMap`. The Town Map's picture and
  sprites, the icons and the fronts are the same files, so `NdsArt` writes them to Black's paths; White's DEX
  descriptions are the text's bank 236 (Black's 235; both carts hold both): `NdsArt.WHITE_DEX`, `POKEDEX_WHITE`.
- GUIDE: `gen_gen5_guide.py` takes either cart (`GuideGen5WhiteGen.kt`, `WHITE_WORLD`): its own wild sets (29 of 112
  differ), IRIS (trainer 132) in Opelucid City's gym where Black has DRAYDEN (133), N with RESHIRAM (trainer 586; Black's
  587 has ZEKROM). Its scripts, trainers and zone events are Black's file for file, so the hand-written pages and every
  flag are Black's (`GUIDE_WHITE` = `bwGuide(WHITE_WORLD, "IRIS")`).
- Its party, bag and Pokédex screens are Black's (the same files; checked headless), so the companion keeps Black's look.

**Black 2 / White 2 (Gen 5).** Black 2 (`IREO` rev 0, `GameKind.BLACK2`) and White 2 (`IRDO`, `GameKind.WHITE2`; `isBw2`,
both `isGen5`) are Black's engine rebuilt: the same structs, every address moved, the cart's archives renumbered. White 2
is Black 2's code with the overlays and statics 0x40 higher, and only 8 of its 318 files differ (its Town Map's places
`/a/0/8/5`, the wild sets `/a/1/2/7`, and six the companion doesn't read), so it shares Black 2's tables, art and DEX.
Everything was found the way White's was - a literal-pool word's code before it, a function by its bytes with the BLs
masked, from Black's RAM to Black 2's - and checked headless on the player's three saves (two Black 2, one White 2:
`black2`, `black2_wild`, `black2_nimbasa`, `white2`; `Black2DecodeTest`, `NdsArtBlack2Test`).
- RAM (`GEN5_BLACK2` / `GEN5_WHITE2`, `pk_nds_field.cpp`'s `BLACK2_US` / `WHITE2_US`, `pk_nds_call.cpp`'s `BLACK2` /
  `WHITE2`): the SaveControl pointer 0x0209A338 (White 2 0x0209A378), the GameSystem 0x021410F4 (0x02141134), the BGM
  stack 0x0209DA74 / 0x0209DA80, sSeqPlayer 0x02148EE4; the field map is overlay 36 (Black's 21), the party list 165, the
  battle 167 (the overlay tables' sizes say so too). The GameSystem, its proc system, the GameData (zone +0x114, the move
  form +0x154, the event data +0x158 and its encounter table +0x38) and the battle module are Black's.
- The save image is 0x26000 bytes and its blocks moved: the event work 0x1FF00 (431 vars, then 3064 flags), money /
  badges 0x21100 / 0x21104, the Pokédex 0x21400 (Black's format), the Repel's steps 0x2192D (found by using a Max Repel
  from the bag headless and diffing the image); party, bag, trainer and Location kept Black's offsets. The Town Map's
  arrival flags are 2469 (Pokéstar Studios) to 2492, and Victory Road's 2530 (`GEN5_BW2_FIRST_ARRIVAL`, 62 bits). Its Unova
  Dex has 301 species, older ones among them, so the National Dex is any seen species outside it (`unovaDexBlack2`).
- PC and FLY: the field's event check (0x0218185D), the script event (0x0215366D, the PC's script is still 10090),
  GameSystem_SetEvent (0x02016AED) and FLY's skill calls in overlay 12 (0x02159231 / 0x021593FD / 0x02159421) are Black's
  functions by their code; `returnZero` is an ARM9 `movs r0, #0; bx lr` at 0x020056B9. FLY is skill 4, refused indoors
  (checked in Humilau City's Pokémon Center).
- The cart (`gen_gen5_tables.py` with all four carts; Black's and White's files come out unchanged): text banks 90
  species, 403 moves, 64 items, 109 places, 398 types, 442 DEX descriptions (both versions'), 464 categories, 374
  abilities; 615 zones; the item icon table opens with the pair (1021, 1022) and is renumbered from item 505 on; 12 key
  items were added (627-638); the Town Map's places are `/a/0/8/5` (84 of 85: each version leaves out the other's Black
  City / White Forest, both at the same point) with a +0x20 condition (`Gen5TownMapPlace.shownBy`) and its art
  `/a/0/8/4` (Black's `/a/0/8/5`, member for member). Kinds: 0 a town, 3 the new places FLY reaches (PWT, Pokéstar
  Studios, Join Avenue, Victory Road), 4 the places the story shows (N's Castle, the Plasma Frigate, the Abyssal Ruins), 5
  Unity Tower.
- GUIDE (`gen_gen5_guide.py` with either sequel's cart -> `GuideGen5Black2Gen.kt` / `GuideGen5White2Gen.kt`, read by
  `BLACK2_WORLD` / `WHITE2_WORLD`; `GuideBlack2.kt`): wild sets `/a/1/2/7` in Black's block format, a zone's set the low
  13 bits of its header's +0x14 (0x1FFF none) and its event file its +0x16; trainers `/a/0/9/1` / `/a/0/9/2` (names bank
  382, classes 383), item-ball scripts `/a/0/5/6` member 1240, zone events `/a/1/2/6`. Every boss has a Normal and a
  Challenge Mode trainer (shown as two teams), the Elite Four and IRIS a second round after the Hall of Fame (flag
  0x960, IRIS's done flag); the Elite Four's room flags are 0x967-0x96A, GHETSIS's 0x375, the leaders go by badge. The two
  versions differ in their wild sets only.
- Its party and bag screens are Black's; its Pokédex is red (`DsPattern.BLACK2_DEX`, sampled headless).

## Frame pacing (`EmulatorEngine`)

At 1x a game frame runs on the game screen's refresh: `EmulatorView`'s GL thread calls `engine.onVsync()` once per
draw, and the loop runs one frame per refresh (60 Hz) or per two (120 Hz), the audio resampled to match with dynamic
rate control and written non-blocking. Whole-number FF speeds run their group of frames on each refresh (`FfGroupFit`).
Three ways out of that:

- `slowFrames`: a GBA game whose frames keep missing the draw's wait is still paced by the refresh, but the GL thread
  stops waiting for it (DS games always run that way). Latched until the core stops.
- the audio fallback (`vsyncFallback`, from `AudioTroubleWatch`): underruns that keep coming put the loop on the timer
  (`Thread.sleep` to 1/60 s) with blocking `track.write`s, which pace it in real time. Not for the whole run any more:
  vsync pacing is tried again after 60 s, the wait doubling each time it gives up again (to 16 min).
- `VsyncCheck` (GitHub #350): the ticks don't follow the refresh at all. The Anbernic RG DS (GammaOS, Android 14)
  swaps without waiting for vsync: ~400 draws a second on a 60 Hz screen, the period estimate (which only keeps 4-34 ms
  intervals) anywhere from 70 to 205 Hz, vsync pacing switching on and off on a made-up rate, 25-60 fps at 1x. The
  check counts ticks per second; a second strikes when it's over 1.25x the display's fastest mode and not steady (within
  5% of the one before), or over 2.1x it whatever it does (250 when the display reports none), or jumps 25% from the one
  before while one of the two is past 1.1x that mode. Two strikes out of four and the timer paces (blocking writes, FF on
  the timer too). A screen moving between 60 and 120 Hz, one that runs steadily faster than the modes it reports, or a
  GL thread too busy to keep up never strikes (`VsyncCheckTest`, with recorded sequences). The finding is kept across
  games but not for good: 10 clean seconds in a row and vsync paces again, the next stretch twice as long (to 160 s).
  1.5.0 kept it for the process, and a report from a Retroid Pocket Nova ("smooth, then suddenly choppy, still 60 fps")
  looked like a false strike that never let go.

FPS / CPU's panel names it on its SCREEN line: `VSYNC N:1`, `TIMER (AUDIO)` or `TIMER (SCREEN)`
(`EmulatorEngine.timerFallback`). The `fps=` log line says which: `paced=N` (refreshes per frame, 0 = the timer),
`fallback (audio)` / `fallback (ticks don't follow the refresh)`, `ticks=N/s` (the GL thread's last second) and, on the timer at 1x, `block=avg/max ms`, how long the
blocking writes held the loop. A device whose audio takes writes in big chunks shows a large max there (the RG DS's
reporter measured 60-88 ms: the loop stops for 4-5 frames, then runs them back to back); if that's what's left, the
next step is the timer with non-blocking writes and rate control.

## Build

```bash
git submodule update --init third_party/mgba third_party/rcheevos third_party/melonds   # first time only (or: make submodules)
./gradlew :app:assembleDebug                                        # or: make apk
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Requires the Android SDK with
NDK `28.2.13676358` and CMake `3.22.1` (both installable via `sdkmanager`).
`make help` lists the other workflows (install, Paparazzi shots, ui-preview,
headless captures).

The generator scripts under `scripts/` and ui-preview's ROM-art rendering read
built pret decomp checkouts (`pokefirered/`, `pokeemerald/`, `pokeruby/`,
`pokehns/`, each built at its pinned commit) from the directory in the
`DECOMPS` environment variable. Machine-local paths go in an untracked
`local.mk`, which the Makefile includes (see `local.mk.example`).

## ScreenScraper and the dual-screen launcher

Game media (box art, screenshots, title screens, wheel logos, fanart, cartridges, videos and the facts: synopsis,
release date, developer, publisher, genre, players, rating) comes from [ScreenScraper](https://www.screenscraper.fr)'s
API v2 (`ScreenScraperClient.kt`). Every call carries two logins:

- **The app's developer account.** ScreenScraper gives these out to software that asks on its forum. They stay out of
  this public repo: put them in the untracked `local.properties`

  ```properties
  screenscraper.devId=...
  screenscraper.devPassword=...
  ```

  (or `SCREENSCRAPER_DEV_ID` / `SCREENSCRAPER_DEV_PASSWORD` in the environment - the release workflow's secrets,
  see [RELEASING.md](RELEASING.md)). `app/build.gradle.kts` turns them into `BuildConfig` fields; a build without them
  sends empty ones and the sign-in shows what the server answered.
- **The player's own account**, entered in setup, Settings > ScreenScraper or the one-time popup after the update.

A ROM is looked up by its hashes and size (`jeuInfos.php`), then by its library name and then by its base game's title
(`jeuRecherche.php`: hacks are often catalogued by name only). `GameMedia.kt` downloads one file per kind into
`<external files>/media/<rom file name>/` plus `info.json`, picking regions by the cart header's language letter, and
`CoverArtSync` copies the box art over any automatic cover. A game ScreenScraper doesn't know is asked again after a week;
a failed download makes the next pass fetch again.

With a second screen the library is a launcher (`LauncherScreen.kt`): the list on the main screen, the selected game's
media on the second (`LauncherHost.kt`'s Presentation). X swaps them. Keys always reach `LibraryActivity` (the
Presentation forwards them), which drives the selection itself, so the D-pad works on either screen. Check layout changes
with `cd ui-preview && gradle render -Ponly=launcher` (stand-in media, both screens and the swapped arrangement).

To check what ScreenScraper has for every supported game - no ROMs needed, it asks by the SHA-1s `GameTitles` knows, then
by name, then by base game, through the app's own `GameMedia.find` / `save`:

```bash
cd ui-preview
SS_USER=... SS_PASSWORD=... SS_DEV_ID=... SS_DEV_PASSWORD=... gradle sscheck   # -Ponly=unbound,emerald  -Pvideos=true
gradle render -Ponly=launcher -PssMedia=build/sscheck/media                     # the launcher on that real media
```

`build/sscheck/report.md` lists each game: how it matched (HASH / NAME / BASE_GAME), ScreenScraper's name and id, the media
it has and which facts. The downloads and report stay in `build/` (never committed).

## Shared BEST EFFORT matches (Firestore)

Players can share a best-effort match (the companion asks after one; nothing is sent without SHARE):
`BestEffortShare` writes one document to Firestore collection `bestEffortReports` in project `pokedaisy`
through the REST API - `sha1`, `size`, `gameCode`, `revision`, `matchedAs` (a `BestEffort.Candidate` id),
`full`, `off`, `appVersion`. To receive them, once:

1. Firebase console > project `pokedaisy` > Firestore Database > Create database (production mode).
2. From `website/`: `firebase deploy --only firestore:rules` (`firestore.rules`: create-only, exactly
   those fields, no reads).

Read them in the console. A ROM shared as FULL is a re-hash of a supported version - add its SHA-1 to
`detect()` with that config; PARTIAL ones show which hacks share a supported game's RAM.

## Co-op connection

Pairing two devices' Emerald saves and keeping a link between them (plans 3 to 5 build on it). Off for players until
`FeatureFlags.coop` goes: `-Ppokedaisy.coop=true`, `local.properties`, or the device marker file `files/features/coop`.
Its manual page is a draft, `docs/manual-drafts/coop.md` (its top note says what to move when the flag goes).

- **Transport**: pure Kotlin, no native code (`companion/coop/`). `RelayCoopTransport` (a `CoopTransport`, polled by
  `CoopSession` on its own thread) joins rooms on our relay over a WebSocket (`OkHttpRelayConnector`, OkHttp) and listens on a TCP port
  for direct and same-Wi-Fi peers; the LAN is found with Android NSD (`NsdCoopLan`, service type `_pdcoop._tcp`, the TXT record
  carries the peer id and the HELLO user data). The relay server is `server/coop-relay/` (a separate Kotlin / JVM program on Java-WebSocket, never in the APK);
  its README covers deploying it (by hand, or to Google Cloud by `.github/workflows/coop-relay.yml` over `deploy/gcp/`) and its limits table.
- **Channel** (`CoopChannel`, one per connection, whatever the pipe is): a Noise IK handshake, `Noise_IK_25519_AESGCM_SHA256`
  (`CoopNoise`, X25519 from Bouncy Castle, the rest java.security). The static key is the device's `filesDir/coop/secret.key`
  (32 bytes; the peer id is its public key, 64 hex characters). The prologue is ASCII `PokeDaisy co-op 1`, one path byte (0 invite room,
  1 pair room, 2 TCP) and the room id's 32 characters (none for TCP). Message 1 carries the transport version (u16, 1), message 2 a
  status byte (0 go on, else a reason code). After the handshake every message is sealed; the plaintext is a kind byte plus a body:
  DATA 0 (an app frame, 1 to 65,536 bytes), PING 1, CLOSE 2 (a reason byte). A PING goes every 5 s, 20 s of silence is TIMEOUT, and the
  handshake has 5 s. TCP frames are length-prefixed (`CoopFrames`, at most 70,000 bytes).
- **Rooms**: a room id is 32 lowercase hex characters. The invite room is derived from the ticket's invite secret
  (HMAC-SHA256 of `pokedaisy coop invite`), the pair room from the two peers' DH secret (HMAC-SHA256 of `pokedaisy coop pair` and the two
  public keys, smaller first), so only the pair can find it. Two sockets per room at most; the relay forwards bytes and learns nothing else.
- **Ticket** (`CoopTicket`): `pdco` plus lowercase unpadded base32 of version 1, the 32-byte public key, a flags byte (bit 0: an invite
  secret of 16 bytes follows), an address count 0 to 4, and per address a family (4 or 6), the bytes and a u16 port. The link
  `pokedaisy://coop/<ticket>` goes to the exported `CoopLinkActivity`, which only passes a parsed ticket on.
- **Relay protocol**: `GET /v1/rooms/<room id>` upgraded to a binary-only WebSocket. Client to server `0x01` + bytes (DATA); server to
  client `0x01` + bytes (forwarded), `0x10` PEER_JOINED, `0x11` PEER_LEFT. Close codes: 4400 bad request, 4408 expired / idle, 4409 room
  full, 4413 too large, 4429 rate / limit (a bad path, 4400, and a per-IP limit, 4429, are refused in the HTTP handshake: the client sees a failed connect, 1006, not a close code). `GET /healthz` on the health port answers `200 ok`. The limits (`MAX_ROOMS`,
  `MAX_SOCKETS_PER_IP`, rates, timeouts, `TRUST_PROXY`) and how to deploy are in `server/coop-relay/README.md`. The relay logs counts and
  close codes only.
- **ROOMS (the lobby)**: `GET /v1/lobby` on the same relay, binary like the rooms: HOST `0x20` (flags, bit 0 listed; invite,
  game, name), UNHOST `0x21`, FIND `0x22` (code), LIST `0x23` (game); answers HOSTED `0x30` (code), FOUND `0x31` (invite), ROOMS
  `0x32` (count, then code / name / u16 minutes waiting), ERROR `0x3F` (request kind, reason: 1 not found, 2 bad request, 3 limit,
  4 full). A string is a u8 length and UTF-8 bytes. The server is `RelayLobby` + `LobbyWire`; the app's copy is `CoopLobbyWire`
  (change them together). A room belongs to its lobby socket and goes with it; a code is 6 of `23456789ABCDEFGHJKMNPQRSTVWXYZ`
  (no 0 / O, 1 / I / L, U), shown `K7Q-4MX`. The invite is opaque to the relay: the app sends its ticket minus the LAN addresses
  (`CoopLobbyWire.lobbyInvite`: they'd reach strangers), so a FIND gives exactly what a link gives, and `CoopPairing.join` dials it.
  The game key is the ROM's SHA-1, first 16 hex digits: LIST only shows the same ROM. Listed names are checked on both ends
  (letters of any script, digits, ` .'!?&♂♀-`, 16 at most). Lookups are 10 a minute per address, right or wrong (30^6 codes), and
  a room not refreshed for 30 minutes is dropped (the app re-hosts every 10). `CoopLobby` runs while `CoopSession`'s endpoint runs
  with a relay, ticked from its poll loop, and hosts while the pairing step is Inviting with a ticket (`syncLobby`); LIST MY ROOM
  is `CoopSession.roomListed`, off on every new invite page; OPEN ROOMS refreshes every 20 s while the page waits (`ROOMS_REFRESH_MS`).
  Pairing refuses a HELLO naming this device's own save (`CoopPairFail.SAME_SAVE`: a copied save): the host lets that joiner in
  long enough to send its own HELLO, so both sides stop with it instead of the joiner timing out. Tests: `RelayLobbyTest`, `LobbyServerTest` (server), `CoopLobbyTest`,
  `CoopRelayIntegrationTest.joinsThroughARoomCode` (the app's lobby and transport through the real relay). ui-preview:
  `coop-invite-rooms*`.
- **The PAIR page** (top-screen Settings > CO-OP): `CoopSettingsPages` holds its state and actions (PAIR, PARTNERS, RELAY;
  SettingsActivity only routes to it), `CoopUi.kt` draws it from one `CoopPairView`: the save list, then three panes on a
  `TabRail` (`CoopPairTab`: YOUR ROOM = code, LIST MY ROOM, QR and link; JOIN = a code, OPEN ROOMS, a pasted link; NEARBY =
  same Wi-Fi and BY IP, not with RELAY ONLY), then the steps after a dial. ui-preview passes a `CoopPairView` straight in
  (`coop-invite*` shots, `tab` picks the pane).
- **Dialing**: a `connect` tries the ticket's addresses and the NSD addresses over TCP (1.5 s connect) while the relay rooms are
  joined (the pair room of an allowed peer, else the ticket's invite room); whichever handshake finishes first wins. Relay rooms: a
  socket on the pair room of every allowed peer, on our own invite room while pairing is open; in a pair room the smaller key
  initiates once the relay says PEER_JOINED. One connection per peer: a newer one replaces the older unless the older is under 5 s
  old, when the direct one wins, then the smaller initiator key; the loser is closed DUPLICATE. A room's socket reconnects with a
  backoff of 1 s doubling to 30 s (plus or minus 20%).
- **RELAY ONLY** (`Prefs.coopRelayOnly`): no TCP listener and no NSD, so a partner never learns your IP address; the relay does.
- **Events** (`CoopEvent`, from `poll()`): Ready(ticket), Relay (kind 0 none, 1 custom), Connected, Frame, Disconnected(reason),
  DialFailed(reason, message), LanPeer, LanGone, Error (Path is no longer emitted). The queue holds 1,024 frames and 64 more slots for
  the rest; a frame that doesn't fit closes that peer OVERFLOW. Pending peers (not yet accepted) may send 8 frames of 2,048 bytes
  within 60 s. **Reason codes**: 0 closed, 1 timeout, 2 denied, 3 version, 4 not allowed, 5 overflow, 6 error, 7 pending expired,
  8 duplicate (above 8 reads as error).
- **Relay URL** (a build secret, never committed): `coop.relayUrl` in `local.properties` or `COOP_RELAY_URL` (`BuildConfig.COOP_RELAY_URL`,
  `https://host[:port]`); a player's override (`CoopRelay`, https only) wins; with neither there is no relay and co-op is the same
  Wi-Fi and BY IP only (the RELAY row reads NONE). The transport speaks `wss://`. The release workflow reads the `COOP_RELAY_URL` secret.
- **Threading**: start / close / poll never run on the UI thread (the session owns a worker); the channels' timers run on `tick()`.
- **Tests**: `RelayCoopTransportTest` over a fake connector, the channel / noise / ticket / frames tests with their vectors, and
  `CoopRelayIntegrationTest` (the real transport against a real `RelayServer` on loopback).
- **Licenses**: Bouncy Castle (MIT, `third_party/licenses/BouncyCastle-MIT.txt`), OkHttp and Okio (Apache 2.0) are in NOTICE and the
  `licensesAsset` list in `app/build.gradle.kts`.
- **Message types** (`CoopWire`): 0x0001-0x00FF the link's own (HELLO, PAIR_ACCEPT, PAIR_DENY, BYE); 0x0100-0x01FF plan 3,
  0x0200-0x02FF plan 4, 0x0300-0x03FF plan 5; others are dropped on receipt.

## Releasing

See [RELEASING.md](RELEASING.md): `make bump V=x.y.z`, merge, `make release` (tags
`v<versionName>` on main), and the Release workflow tests, signs and publishes the APK as
`PokeDaisy-<versionName>.apk`. The app compares the tag against its own `versionName` and
ignores drafts and pre-releases, so a test build can go out as a pre-release
(`1.2.0-rc.1`) without reaching users. **Every release must use the same key**: the
updater's APK only installs over a build signed with it.
Either keystore password can be left out of `keystore.properties` and kept in the macOS
Keychain instead (`security add-generic-password -a pokedaisy -s pokedaisy-storePassword -w`,
and `pokedaisy-keyPassword`): the build reads it from there.

Debug builds never auto-check for updates (a release APK can't install over a
debug-signed app); Settings > VERSION still does. Before handing a download to
Android's installer, `AppUpdateFlow.isOurUpdate` checks it is this app's package,
signed with the running app's own certificate and a higher `versionCode` - a
release asset swapped on GitHub can't install as a new app either.

The app's Settings > LICENSES page is `licenses.txt`, joined at build time
(`licensesAsset` in `app/build.gradle.kts`) from NOTICE, LICENSE and each bundled
component's license (`third_party/licenses/` holds the ones without a submodule).
Add a component there when a new library or asset ships in the APK.

## Test on the Thor (Phase 0 acceptance)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell mkdir -p /sdcard/Android/data/com.pokedaisy.app/files/roms
adb push firered.gba /sdcard/Android/data/com.pokedaisy.app/files/roms/
```

Launch the app; it boots the first `.gba` it finds in that folder. Swap ROMs by
pushing a different file (only one at a time for now). SRAM persists to
`files/saves/<rom>.sav`.

**Phase 0 is done when** vanilla FireRed, vanilla Emerald, and Unbound each boot, play at full speed, and have correct sound.

## Layout

| Path                                                    | What                                                                                                                                                               |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `third_party/mgba/`                                     | mGBA source, git submodule @ `0.10.5`                                                                                                                              |
| `third_party/rcheevos/`                                 | RetroAchievements' rcheevos, git submodule @ `v12.5.0`                                                                                                             |
| `app/src/main/cpp/`                                     | `CMakeLists.txt` + JNI bridge                                                                                                                                      |
| `app/src/main/kotlin/com/pokedaisy/app/`               | app code                                                                                                                                                           |
| `gamedata/gen3/`                                        | Per-ROM `NativeConfig` address tables (TOML, `gamedata/README.md`) — start here for "how is game X read" (each config has an extensive comment on how its addresses were found); `make gamedata` writes `NativeConfigsGen.kt` from them. |
| `.../companion/data/NativeReader.kt`                    | The `NativeConfig` data class + the decode logic.                                                                                                                  |
| `.../companion/data/ActiveTables.kt`                    | Picks which species/item/map-name tables and sprite source apply for the currently-detected `GameKind`.                                                            |
| `.../companion/data/SpeciesNames*.kt`, `Poller.kt`      | Per-hack species tables and the SHA1 detection constants, respectively.                                                                                            |
| `scripts/capture_fixture.sh`, `scripts/roms.conf`       | Captures a live EWRAM+IWRAM memory fixture for one ROM off the device.                                                                                             |
| `app/src/test/resources/fixtures/<key>/`                | Captured `{ewram,iwram}.bin` + `README.txt` per ROM — replayed by the JVM decode tests, not committed ROMs/saves themselves.                                       |
| `app/src/test/kotlin/.../companion/data/*DecodeTest.kt` | One regression test per supported ROM, decoding its fixture and asserting the known-real values.                                                                   |
