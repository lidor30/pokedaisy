# PokeDaisy - developer notes

How PokeDaisy is built, how each game is read, and how to work on it. For what
the app does and how to install it, see the [README](../README.md).

Dual-screen Android app for the AYN Thor (and the Retroid Pocket Duo / Duo
Lite): an embedded **mGBA** core on the top screen + a live-data companion UI
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
  battle panel, mon detail) in `companion/` (it started as the FireRed QoL
  project's standalone Android companion), shown on the Thor's Screen-2
  via a `DualScreenPresentation`. Its old UDP transport is replaced by
  `InProcessReader` → `MgbaCore.pkReadBytes` (bus reads on the emulator thread,
  ~1×/sec, via `EmulatorEngine.onSample`). Game auto-detect from the ROM header;
  `firered-qol` / `emerald-qol` use the `gQolTelemetry` struct (found by scanning
  IWRAM/EWRAM for the `QOLT` magic — no hardcoded address), vanilla FireRed and
  Unbound use native-RAM reads.

Bottom screen confirmed rendering on the Thor's Screen-2; two early crashes
fixed (a `ConcurrentHashMap` null-value NPE on missing sprites, and a Compose
`Dialog` that can't open a window inside a `Presentation` — the mon-detail
popup is now an in-composition overlay). 90 s interaction soak = 0 crashes.
**Rule for this UI: no `Dialog`/`Popup` inside the Presentation.**

## Status: Phase 4 (polish) — in progress

- **Dynamic mon/item icons (done, verified):** the v2 `gQolTelemetry` struct
  (`QOL_TELEMETRY_VERSION 2`, 460 bytes) exports the ROM addresses of
  `gMonIconTable` / `gMonIconPaletteTable` / `gMonIconPaletteIndices` /
  `gItemIconTable`. `DecompIconSource` decodes party + bag sprites straight
  from the running ROM (`Gfx.kt` has the shared 4bpp / LZ77 decoders), falling
  back to the bundled PNGs. **Rebuild + re-apply both QoL ROMs** —
  `firered-qol` sha1 `221d3aca…`, `emerald-qol` sha1 `f96fa970…`.

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
  catalogued on SteamGridDB yet). Vanilla retail FireRed/LeafGreen/Emerald and
  this project's own firered-qol/emerald-qol (rebuilt with a different hash
  every time) aren't in this table on purpose and fall back to the
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
  (`MapSecDataEmerald.kt`) and Hoenn region map, so both `emerald-qol` and
  vanilla Emerald show real Hoenn location names and the map.
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
  back; the party's icons sit between them and jump on tap. No EXP bar — the
  telemetry doesn't export experience. It's one window split by rules
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
  summary shows an EXP bar ("NEXT n" to the next level). The QoL struct has no
  EXP, so it's read from the party's own RAM struct (decrypted Growth
  substruct) and only used when that slot's species and level match the
  struct's; thresholds come from the game's growth-rate tables
  (`data/ExpTables.kt`, generated by `scripts/gen_exp_tables.py`).
- **Battle controls, Platinum pass:** big centred FIGHT, BAG / POKéMON in the
  bottom corners with RUN lower between them, INFO / SUGGESTIONS beside the
  six-slot ball strip (grey discs for empty slots), black-outlined buttons with
  a light top band and dark side bands, shadowed labels; the "busy" dots are a
  rocking Poké Ball. INFO and SUGGESTIONS link to each other bottom-left.
- **No bundled sprites (done):** every game now decodes party/bag icons
  straight from the running ROM (QoL ROMs via the v2 struct, retail FR/Emerald
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
one is running (SHA1 of a live core-memory read for the ROM hacks below, the
`"QOLT"` magic for the two custom QoL patches, the GBA header's game code (and
revision byte) for plain vanilla FireRed/Emerald/LeafGreen/Ruby/Sapphire — see
`Poller.kt`'s `detect()`) and switches
species/item/map tables and RAM addresses accordingly (`ActiveTables.kt`).

✅ = verified live (has a passing fixture-based JVM decode test under
`app/src/test/`, see below; for DEX, checked against the game's own POKéDEX screen) · ⚠️ = wired up but unverified/partial · ❌ = not
working · — = not attempted.

| ROM                                       | Base                                                                                                       | Party/Lvl/HP          | Battle                                                                                                                                                                                                                                                                                                                                                                                          | Bag/Items                                 | Map/Location                                                       | Sprites                                                                                                                                                                                                                                                                                                                                                  | DEX                                                                      | Notes                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------------------- | --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- | ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **FireRed QoL** (sister ROM hack)         | custom `pokefirered` patch                                                                                 | ✅                    | ✅                                                                                                                                                                                                                                                                                                                                                                                              | ✅                                        | ✅                                                                 | ✅                                                                                                                                                                                                                                                                                                                                                       | — not yet (QoL struct path)                                              | Exports its own `gQolTelemetry` v2 struct — the richest path (also drives touch battle control).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Emerald QoL** (sister ROM hack)         | custom `pokeemerald` patch                                                                                 | ✅                    | ✅                                                                                                                                                                                                                                                                                                                                                                                              | ✅                                        | ✅                                                                 | ✅                                                                                                                                                                                                                                                                                                                                                       | — not yet (QoL struct path)                                              | Same struct, ported to Emerald.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **Vanilla FireRed** (rev 0/1)             | retail                                                                                                     | ✅                    | ✅                                                                                                                                                                                                                                                                                                                                                                                              | ✅                                        | ✅                                                                 | ✅                                                                                                                                                                                                                                                                                                                                                       | ✅ rev 1 (rev 0: no ROM on file to check)                                | Native RAM, addresses from a byte-verified decomp build.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **Vanilla Emerald**                       | retail                                                                                                     | ✅                    | ✅                                                                                                                                                                                                                                                                                                                                                                                              | ✅                                        | ✅                                                                 | ✅                                                                                                                                                                                                                                                                                                                                                       | ✅                                                                       | Native RAM, addresses from a vanilla `pokeemerald` ELF (sha1-matched to retail).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Vanilla LeafGreen** (rev 0/1)           | retail (BPGE)                                                                                              | ✅                    | ⚠️ FireRed's battle addresses (identical in the map), not battle-tested                                                                                                                                                                                                                                                                                                                         | ✅                                        | ✅ (Kanto)                                                         | ✅ mon + item icons                                                                                                                                                                                                                                                                                                                                      | ✅                                                                       | `NATIVE_LEAFGREEN_REV0/1`. pret/pokefirered @ c75f352 `leafgreen`/`leafgreen_rev1` build byte-identical to retail (sha1 `574fa542…`/`7862c67b…`); every RAM global and the battle-controller code sit at FireRed's own addresses for the same revision, only ROM data moves (icons, dex tables, LeafGreen's own dex text). Shown with the FireRed look.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| **Vanilla Ruby** (rev 1/2)                | retail (AXVE), pokeruby engine                                                                             | ✅                    | ⚠️ inBattle + battle mons mapped, not battle-tested; touch controls off                                                                                                                                                                                                                                                                                                                         | ✅ (no item icons — the R/S bag has none) | ✅ (Emerald's Hoenn table: same mapsec ids)                        | ✅ mon icons                                                                                                                                                                                                                                                                                                                                             | ✅                                                                       | `NATIVE_RUBY`. pret/pokeruby `ruby_rev1`/`ruby_rev2` build byte-identical to retail (sha1 `610b96a9…`/`5b64eacf…`) and share every address. Differs from Emerald: party in IWRAM (`0x03004360`), **static** save blocks (SB1 `0x02025734`, SB2 `0x02024EA4` — `NativeConfig.staticSaveBlocks`), `gBagPockets` is a const ROM table pointing into SaveBlock1, no bag-quantity XOR key, `gMain.inBattle` at +0x43D. Rev 0 is reported unsupported (no rev 0 ROM to check). Shown with the Emerald look.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **Vanilla Sapphire** (rev 1/2)            | retail (AXPE), pokeruby engine                                                                             | ✅                    | ⚠️ as Ruby                                                                                                                                                                                                                                                                                                                                                                                      | ✅ (no item icons)                        | ✅ (Emerald's Hoenn table)                                         | ✅ mon icons                                                                                                                                                                                                                                                                                                                                             | ✅                                                                       | `NATIVE_SAPPHIRE`: Ruby's RAM layout (all four pokeruby maps agree), its own ROM addresses; `sapphire_rev1`/`sapphire_rev2` byte-identical to retail (sha1 `4722efb8…`/`89b45fb1…`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| **Pokémon Unbound** v2.1.1.1              | CFRU/ASM hack on FireRed rev 0                                                                             | ✅                    | ⚠️ static-verified, not live-battle-confirmed                                                                                                                                                                                                                                                                                                                                                   | ✅                                        | ✅                                                                 | ✅ (live ROM fetch)                                                                                                                                                                                                                                                                                                                                      | ✅ Borrius + National                                                    | Own species/item tables (renumbered dex); "Cube" tab label instead of "Items".                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **Pokémon Unbound** v2.1.1.1 FR (French fan translation) | the same release with its text translated (32 MB, sha1 `0ce2a880…`, revision byte 0x9E) | ✅ (`unbound_fr`: English's RAM, every literal pool unchanged) | ⚠️ as English | ✅ | ✅ its Borrius map from the ROM (`RomRegionMap` now reads any revision byte but rev 1's) | ✅ (live ROM fetch) | ✅ Borrius + National, French dex text ("Pokémon Graine", metric) | `NATIVE_UNBOUND_FR`. Names in French (`UnboundTextFrGen.kt` from `scripts/gen_unbound_fr_tables.py`, a `GameText` over Unbound's own move / map tables, `UNBOUND_FR_TEXT`): species, moves (to 893 - the Max Moves past it keep English's), items, natures, map sections. Item descriptions, dex entries and map names were translated in place, so they read French from the ROM. Its trainers are renamed too (MIRSKLE is Sylvain); the GUIDE's hand-written boss titles stay English. |
| **Pokémon Gaia** v3.2                     | ASM hack on FireRed rev 0                                                                                  | ✅                    | ⚠️ not individually re-checked                                                                                                                                                                                                                                                                                                                                                                  | ✅                                        | ⚠️ new region, mapsec names not yet re-derived                     | ✅ (relocated tables found via still-identical helper-function literal pools)                                                                                                                                                                                                                                                                            | ✅ one dex (721)                                                         | Own relocated species table (~930 species, `SpeciesNamesGaia.kt`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **Pokémon Radical Red** v4.1              | ASM hack on FireRed rev 0                                                                                  | ✅                    | ⚠️ touch battle input unverified (reworked UI)                                                                                                                                                                                                                                                                                                                                                  | ✅ own names (`ItemNamesRadicalRed.kt`) + descriptions (its `gItems`, from the ROM) + icons | ⚠️ untested (inherits rev-0 addresses)                             | ✅ own icon tables (`gMonIconTable` 0x097FE6CC, item icons 0x093C8100)                                                                                                                                                                                                                                                                                    | ✅ Kanto + National (1025)                                               | Everything from its own ROM by `scripts/gen_cfru_tables.py`: `gSpeciesNames` (its own ids, to 1349 - not National Dex numbers), long move names + `gBattleMoves`, `gItems` (by `.itemId`), `gBaseStats` types. CFRU repoints FireRed 1.0's literal pools, so each table is the word retail rev 0 loads at the same offset. Fixture `radical_red_1636`: a later save with Gen 7-9 species and items.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **Pokémon Odyssey** v4.1.1                | ASM hack on FireRed rev 0                                                                                  | ✅                    | ⚠️ untested guess                                                                                                                                                                                                                                                                                                                                                                               | ✅                                        | ⚠️ untested guess                                                  | ⚠️ untested guess                                                                                                                                                                                                                                                                                                                                        | ✅ Talrega + National (409)                                              | Basic connectivity only so far — inherits rev-0 addresses unchanged, no custom species/item tables built yet.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| **Pokémon Heart and Soul** v2.0.6         | newer `pokeemerald-expansion` (RHH fork), 32 MB                                                            | ✅                    | ✅ one wild battle captured headlessly (expansion `BattlePokemon` is 0x88 bytes, `NativeConfig.battleMonLayout`). Touch battle input not derived                                                                                                                                                                                                                                                | ✅ id/qty/pocket + real names (6 pockets) | ✅ its Johto + Kanto Pokégear map, rebuilt from the ROM (smol, `HNS_REGION_*`) + cursor grid | ✅ mon + item icons (item tiles use the expansion's **smol** compression, `Smol.kt`). **Party tab** draws the game's own slot and backdrop (`partyhns/`, rebuilt from the ROM by `RomArt` - smol `HNS_PARTY_*` blobs; layout from its source's `graphics/party_menu/hns/` via `scripts/gen_party_assets.py`, pixel-diffed against the real party screen); **Items tab** uses HnS's bag colours and its own item descriptions | ✅ Johto + National (1080)                                               | Redone 2026-09-27 against the **release** ROM with headless captures. The earlier from-source build's addresses were 4 bytes off in upper EWRAM, which is why the party showed empty. `species:11\|teraType:5` packing like TMT2; SaveBlock1 has 4 extra leading bytes (`saveBlock1PosOff = 4`). Own tables (`*Hns.kt`) generated by `scripts/gen_expansion_tables.py`. STEADY FF music via `M4aSongs`' Heart and Soul signature (expansion's `m4aSongNumStart` + an `alt` argument for its alternate soundtrack table; both soundtracks are rendered).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| **Pokémon Lazarus** v2.0                  | `pokeemerald-expansion` (BPEE, "PRET x RHH" splash), no source available                                   | ✅                    | ✅ battle mons, types, moves, foe party, touch controls (2-column party grid) - headless scripted wild battle (`lazarus_battle`)                                                                                                                                                                                                                                                                 | ✅ id/qty/pocket + real names             | ✅ its own region map (`regionmap/lazarus.png` rebuilt from the ROM by `RomArt`) + cursor grid | ✅ mon + item icons                                                                                                                                                                                                                                                                                                                                      | ⚠️ Ilios + National; flags matched to the save, not to the game's screen | Party/bag found empirically from live memory back in September (see `NATIVE_LAZARUS`). On 2026-09-27, headless captures added gMain/SaveBlock1/map header/object events, and the ROM gave its own species/item/move/type/location tables (`*Lazarus.kt`, same generator as Heart and Soul). The "2 extra pockets" were NULL slots. There are 5 real pockets in Emerald order.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| **Pokémon SoulGold** v1.1.4, v1.2 | newer `pokeemerald-expansion` (BPEE, 32 MB, gcc), no source available | ✅ (its own plaintext 96-byte struct Pokemon) | ✅ battle mons (0x98-byte BattlePokemon), touch controls - headless scripted wild battle (`soulgold_battle`); trainer battles not checked | ✅ 8 pockets, names + descriptions | ✅ its own Johto map, rebuilt from the ROM (smol art, u16 cursor grid at offset (1, 1)) | ✅ mon + item icons (smol item tiles) | ✅ Johto (702) + National, matched to the game's own POKéDEX | Everything from headless captures of the user's save - see `NATIVE_SOULGOLD`. ROM tables from `gen_expansion_tables.py soulgold`; the bag's night sky is rebuilt from the ROM by `RomArt` (smol tiles + tilemap, `SG_BAG_STARS_*`). STEADY FF music via `M4aSongs`' newer-expansion signature. v1.2 (`NATIVE_SOULGOLD_V1_2`) is a rebuild: v1.1.4's addresses matched into it (literal pools / masked byte runs), checked on the same save headlessly (`soulgold_v12`, `soulgold_v12_battle`); it shares v1.1.4's tables but for TM75 (`soulGoldV12`). A second build also released as v1.2 (sha1 `5d6a0362…`, `NATIVE_SOULGOLD_V1_2B`) keeps every RAM address and battle handler of the first and moves only ROM data (0x98 / 0xA4 bytes); same tables, checked on the same save (`soulgold_v12b`, `soulgold_v12b_battle`). |
| **Pokémon R.O.W.E.** 2.1.9.1 Experimental | Emerald-based (BPEE, 32 MB), BelialClover's own pokeemerald fork (no expansion), no source for v2.x | ✅ its bit-packed 0x4C-byte struct (`ROWE_PARTY_MON`) | ✅ scripted wild battle captured headless (`rowe_battle`); touch controls, 2-column party grid | ✅ 10 pockets | ✅ its three region maps (Hoenn / Kanto / Sevii, `RW_*`) | ✅ mon + item icons | ⚠️ National (1019), flags matched to the save, not to the game's screen (no POKéDEX on its menu yet) | The ROM's own symbol table (0x08FB0670) names most globals. Tables: `scripts/gen_rowe_tables.py`. No CARD / GUIDE: its system flags were renumbered. |
| **Emerald Rogue** v2.2.1-EX               | Emerald-based (BPEE), no source available                                                                  | ✅ (see caveat below) | — not attempted                                                                                                                                                                                                                                                                                                                                                                                 | ❌ not found                              | — not attempted                                                    | ❌ (inherits vanilla Emerald's icon addresses, unverified)                                                                                                                                                                                                                                                                                               | ✅ National (1025) + its MODERN list (400): a 2-bit state per species id in SaveBlock1, matched to its own dex | Species by National Dex too (shares `SpeciesNamesNationalDex.kt`). **Address-stability cautionary tale**: two live-memory scans each found a pair of stable-looking party candidates; the first pick (matching vanilla's exact `SaveBlock1+0x238` struct offset _and_ confirmed by an IWRAM pointer) shipped and immediately decoded garbage in real play — a 3rd, later capture showed that address had silently drifted (a relocatable scratch buffer, not a real global), while the address that looked "less confirmed" had stayed byte-identical all along. Fixed by switching to the one proven stable across 3+ independent captures. Bag hunting (both the Lazarus-style XOR-key pattern search and a raw cross-capture byte-stability scan) didn't converge on a confident candidate.                                                                                                                                                                                                                                                                                                                                                                           |
| **Emerald Seaglass** v3.0                 | Emerald-based (BPEE, 16 MB), expansion-style recompile, no source available                                | ✅                    | ✅ battle mons, types, moves, touch controls - headless scripted wild battle (`emerald_seaglass_battle`); its own move / species-type tables (`gen_expansion_tables.py seaglass`) | ✅ id/qty/pocket + real names             | ✅ its own Hoenn art (`SGL_REGION_GFX`, rebuilt from the ROM) on Emerald's tilemap and grid | ✅ mon + item icons (expansion keeps them inside `gSpeciesInfo`/`gItemsInfo` entries — `IconTables` got stride/mask fields for that; decoded offline and checked visually; TMs have no icon, expansion draws those per move type)                                                                                                                        | ✅ Hoenn + National (430)                                                | First hack added **fully headless** — no device: `scripts/capture_fixture_headless.sh emerald_seaglass`, then 4 extra captures with varied boot timing (so a different RNG seed and save-block offset each) to prove every address stable across 5 captures (see `NATIVE_EMERALD_SEAGLASS`). Exactly 16 MB, so `Poller.detect()` got a new hash check for 16 MB BPEE ROMs (unknown hashes still fall through to vanilla/QoL Emerald). Species by National Dex; items from the ROM's own table (`ItemNamesSeaglass.kt`, 827 entries). Detection hash not yet confirmed on-device.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Celia's Stupid Romhack** v1.1.4         | FireRed-based (BPRE rev 0), full `pokefirered` recompile, no source available                              | ✅                    | ⚠️ addresses from ROM literal-pool analysis only (no battle reachable headlessly). **Types ✅**: 34 types (joke Brock/Weird/Dad/Choco/…), a 34×34 chart (32-bit uq4_12 matrix — every vanilla chart-reading code site was rewritten, so it's not vanilla's triplet list; includes a joke 5× and 0.2×), species types, and move type/power from a 16-byte move record (`TypeChartCelia.kt` etc.) | ✅ id/qty/pocket + real names             | ✅ (vanilla Kanto mapsec ids)                                      | ✅ mon + item icons (checked by decoding + visually)                                                                                                                                                                                                                                                                                                     | ✅ one dex (150)                                                         | Custom dex order (1 = VICTINI, 3 = CHARMANDER), renumbered moves (1189), joke renames — own species/item/move-name tables (`*Celia.kt`). **Stale-table trap**: the ROM also holds an unreferenced vanilla-ordered species table that decoded the starter as VENUSAUR; the game's own party screen (`mgba_dump`'s new `shot` command) showed CHARMANDER, and a literal-pool reference count (48 vs 0) picked the live table. Encryption key moved to SaveBlock2+0xB18.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **Too Many Types 2** v1.5.2               | Emerald-based (BPEE, 32 MB), newer `pokeemerald-expansion` recompile, no source available                  | ✅                    | ✅ battle mons (expansion's 0x60 bytes, PP at +0x26), foe party, touch controls - headless scripted wild battle (`tmt2_battle`). **Types ✅**: all 82 type ids (60 joke types), the 82×82 chart, species types and move type/power extracted from the ROM (`TypeChartTmt2.kt` etc.)                                                                                                                                                              | ✅ id/qty/pocket + real names (7 pockets) | ✅ (vanilla Hoenn mapsec ids)                                      | ✅ mon + item icons (item palettes are raw, not LZ77)                                                                                                                                                                                                                                                                                                    | ✅ Hoenn + National (1052)                                               | Newer expansion packs `species:11\|teraType:5` (and moves/PP) into the BoxPokemon fields — `NativeConfig` gained `speciesMask`/`moveMask`/`ppMask`. National Dex order only to 905, forms after, so own species/item/move-name tables (`*Tmt2.kt`). Pocket order read from each item's own `gItemsInfo` pocket field.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **Pokémon Glazed** 9.2.0                  | Emerald-based (BPEE, 32 MB), an in-place binary edit of retail Emerald, no source                          | ✅                    | ✅ wild battle captured headless (`glazed_battle`); retail's RAM, layout and battle handlers                                                                                                                                                                                                                                                                                                   | ✅ (retail's 5 pockets)                   | ✅ its own Tunod / Johto map, rebuilt from the ROM (`GZ_REGION_*`) | ✅ mon + item icons (retail's tables, its own art)                                                                                                                                                                                                                                                                                                       | ✅ retail's tables rewritten in place (its own 1..386, Tunod as "HOENN") | Retail Emerald's RAM and code; its own names in retail's 412 species / 355 move / 377 item slots, FAIRY as type 9, a repointed type chart: `scripts/gen_glazed_tables.py`. |
| **Emerald Imperium** v1.3.1               | Emerald-based (BPEE, 32 MB), `pokeemerald-expansion` 1.10.0 (gcc), no source                               | ✅                    | ✅ wild battle captured headless (`imperium_battle`); expansion `BattlePokemon`, touch input + grid party menu                                                                                                                                                                                                                                                                                 | ✅ 6 pockets (Mega Stones folded into Items) | ✅ Hoenn ids + 5 of its own; its map rebuilt from the ROM (`IMP_REGION_*`) | ✅ mon + item icons                                                                                                                                                                                                                                                                                                                                      | ✅ Hoenn (214) + National (1025), from gSpeciesInfo | 1.10 packs species / moves (11 bits), PP (7) and experience (21 bits, the nickname's 11th character above it). Tables: `gen_expansion_tables.py imperium`. |
| **Pokémon Quetzal** English Alpha 9 v0    | Emerald-based (BPEE, 32 MB, "PKM QUETZAL"), its own engine on pokeemerald, no source                       | ✅ its own 104-byte plaintext struct (`QUETZAL_PARTY_MON`) | ✅ scripted wild battle captured headless (`quetzal_battle`); expansion `BattlePokemon`, touch input                                                                                                                                                                                                                                                                                           | ✅ its "BAG3" bit stream in SaveBlock1 (`Bag3Layout`); no TMs | ✅ its Kanto / Sevii maps rebuilt from the ROM (`QTZ_*`), FireRed's rects shifted (-3, -2) | ✅ mon + item icons                                                                                                                                                                                                                                                                                                                                      | ✅ National (1034): 12-byte entries by species, 3-bit seen levels, caught per region | SaveBlock1 is its own (position +0x470, money +0x918, key SB2+0x2C). HP is 10 bits at +0x23, the egg flag the IV word's bit 30. Wild Pokémon walk the grass (gEnemyParty holds them on the field). Tables: `scripts/gen_quetzal_tables.py`. |
| **Pokémon Quetzal** Spanish Alpha 9 v0 | the same engine rebuilt with Spanish dialogue / descriptions / dex text (sha1 `fe346b5b…`) | ✅ English's RAM (`quetzal_es`: six Lv42s) | ✅ scripted wild battle captured headless (`quetzal_es_battle`); its handlers 0x24 before English's | ✅ (3403 Master Balls - the save's own) | ✅ as English | ✅ mon + item icons (its own addresses) | ✅ "POKéMON Semilla", metric | `NATIVE_QUETZAL_ES`: ROM tables mapped from English's through the literal pools (`port_retail.py`'s matching). Quetzal - both releases - carries English, Spanish, Latin American Spanish and Portuguese names and picks them per kind with its own IDIOMA options (START > OPCIONES > TEXTO), kept in SaveBlock2 (+0x2E0..+0x2E2, +0x2F4): `readQuetzalNames`, `QuetzalNamesGen.kt` (`gen_quetzal_tables.py --languages`). Portuguese isn't generated (glyphs past the Western charmap): English there. Money goes past 999,999 (`maxMoney` 9,999,999). |
| **Pokémon Amethyst** v1.3.0, v1.4.1        | FireRed-based (BPRE), no source available                                                                  | ✅                    | ✅ battle mons (rev 0's RAM), foe party, touch controls (2-column party grid) - headless wild battle (`amethyst_battle`) | ✅ id/qty/pocket + real names             | ✅ (untested beyond one location)                                  | ✅ mon + item icons (visually confirmed)                                                                                                                                                                                                                                                                                                                 | ✅ own dex (390) + National (690)                                        | The easy case: kept vanilla FireRed's RAM layout entirely unchanged (`NATIVE_AMETHYST` needed zero RAM address changes, unlike the Emerald-based hacks) — only species/items and the icon-graphics ROM tables are renumbered/relocated. Species/item name tables extracted **directly from the ROM's own name strings** (`SpeciesNamesAmethyst.kt`/`ItemNamesAmethyst.kt`, same anchor-and-walk technique `gen_unbound_data.py` uses for Unbound): species 1-251 is vanilla National Dex order, 252-~411 is vanilla FireRed's own _internal_ (non-dex) Gen 3 order unchanged, and Amethyst's own Gen 4-8 additions are appended past that non-sequentially (up to id 1267). Icon tables found via Gaia's literal-pool technique — `GetMonIconTiles`/`GetItemIconGfxPtr` are still byte-identical CODE at their exact vanilla rev-0 addresses (the latter's address itself obtained by building the pinned pokefirered commit unpatched and reading its own symbol table, not guessed), just pointing at relocated DATA; both decoded end-to-end into real, recognizable icons (a Tepig, a Potion bottle) as verification, not left to a plausible-looking address alone. `gMonIconPaletteIndices` moved too (0x09C01408; the vanilla table first used was right for Tepig, wrong for 757 species). v1.4.1 (`NATIVE_AMETHYST_V1_4_1`) keeps the RAM (a v1.3.0 save loads, `amethyst_v141` fixture) and vanilla code; its own data moved (literal pools), and 26 Hisuian forms inserted at species 1234 renumber everything after, so it has its own names (`scripts/gen_amethyst_tables.py`, keyed by item slot - the records' itemId field is stale in a few), gender ratios and area data (`GuideId.AMETHYST_V141`), picked by `amethystV141`. |
| **Pokémon Orange Islands** (Beta 5.7 + an unversioned build) | FireRed-based (BPRE rev 0, **16 MB**), retail rev 0 edited in place, no source | ✅ (`orange_islands`) | ✅ scripted wild battle captured headless (`orange_islands_battle`, its CRYSTAL ONIX); retail rev 0's handlers byte for byte, touch input | ✅ | ✅ its Orange Archipelago over FireRed's four screens, from the ROM (`RomRegionMap`) | ✅ retail rev 0's tables | ✅ retail rev 0's tables, edited (`POKEDEX_ORANGE_ISLANDS`) | `NATIVE_ORANGE_ISLANDS`, `GameKind.ORANGE_ISLANDS`. 16 MB like retail, so detect() hashes small BPRE ROMs once too. Its own names, a 24th type CRYSTL (23) with its chart rows, rebalanced moves / base stats: `scripts/gen_orange_islands_tables.py`. The two builds differ in 80 bytes (a line of dialogue and a warp). No CARD: its trainer card is its own art (Ash, four badges). |
| **Pokémon Yellow** (Game Boy, USA/Europe) | Gen 1 retail cart (sha1 `cc7d0326…`, = pret/pokeyellow's build) | ✅ (`Gen1Reader`: big-endian party_struct, internal species → Dex via PokedexOrder) | ⚠️ both battlers (wBattleMon / wEnemyMon) + a trainer's party - headless scripted wild battle (`yellow_battle`); no touch controls | ✅ one list, game order | ✅ its own town map from the ROM (`Gen1Art`: CompressedMap RLE) + the place's cell | ✅ its party icons from the ROM (MonPartySpritePointers, GBC colours) | — | Runs on mGBA's GB core (M_CORE_GB, SGB borders off). Tables from `scripts/gen_gen1_tables.py yellow` (pokeyellow.sym addresses); the companion sets its text in the game's own font (`Gen1Art` → `BitmapTtf`) and Gen 1 windows (`OptionColors` while YELLOW). |

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
   `RetailPortsGen.kt`: one config per game code + revision (`RETAIL_PORTS`, which
   `TelemetrySampler.otherRetailConfig` consults first), titles by SHA1, the name sets.
   `build/ports/ports.json` records how each address was found.

`verify_ports.py` boots every ported ROM and its English one (pokedaisy-capture,
`mgba_dump`) with that game's English save - the other languages load it as is -
dumps it on the field and in a scripted wild battle (stepping through FIGHT / BAG /
POKéMON and reading gBattlerControllerFuncs at each), then `PortsVerifyTest` compares
each with English: party, bag, money, place, the dex (every national number's species,
height and weight), the guide tables and the gym leader's party, the battlers, the
battle handlers, and that every name resolves. It writes `build/ports/report.md` and
`build/ports/fields.png` (every ROM's field, at a glance). All 33 pass. `PortedRomsTest`
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
games with a `PokedexTables` config get the tab — the QoL builds don't (yet):

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
| SoulGold v1.1.4, v1.2           | Johto (702, the national numbers with a species) + National (to 1025) | SaveBlock1+0x31F8 / +0x3279 | Seen 6 / Own 1 (CYNDAQUIL), matched to the game's own POKéDEX (opened via FLAG_SYS_POKEDEX_GET 0x98D headlessly), plus poked bits |
| Emerald Seaglass v3.0           | Hoenn (212) + National (430, TREECKO = No.1)                | SaveBlock1+0x28FC / +0x2932                      | Hoenn: seen 2 / own 1; poking bits gave 3 / 0                                  |
| Too Many Types 2 v1.5.2         | Hoenn (398) + National (1052, CHIMCHAR = No.397)            | SaveBlock1+0x289C / +0x2920                      | Hoenn: seen 1 / own 1; poking No.913 showed SPRIGATITO as Hoenn #001           |

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
  gRogueItems from 827), Quetzal (0x1C, +0xC), R.O.W.E. (0x38, +0x1C).

`scripts/port_retail.py` writes each port's `itemDescs` (its detected item
table); the other generators print theirs. The QoL builds run on the struct
path with no config and their gItems moves every rebuild, so the Poller finds
it by shape (`RomItemText.findVanillaItems`: item ids 1, 2, 3... in consecutive
records) once per ROM; a config whose vanilla table doesn't check out
(`matchesRom`) falls back to the same scan. Yellow keeps our own hand-written
text (Gen 1 items have none). Text decodes with `Gen3Text` (Japanese for
`romLanguage` 'J', each language's POKéBLOCK glyphs, placeholders dropped),
line breaks collapsed to spaces.

Checked against the bundled tables they replaced, every id, every ROM on the
dev machine (2026-10-09): identical for Emerald (+ QoL), the five other-language
Emeralds, all 31 other-language FireRed / LeafGreen / Ruby / Sapphire ports, Heart
and Soul, Lazarus, SoulGold, Rogue, Seaglass, Glazed, Imperium, Quetzal and
R.O.W.E. The rest changed for the better: FireRed / LeafGreen (+ QoL) TMs show
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
| Lazarus v2.0                      | —                                                                | HERE's wild Pokémon only                                             | 8 Muses (NORMAL / HARD by flag 0x8E6), the RUINS OF AHIYAWA's four, ANYALIOS |
| Seaglass v3.0                     | —                                                                | HERE's wild Pokémon only                                             | Emerald's 8 gyms, Elite Four, WALLACE (its own teams) |
| Too Many Types 2 v1.5.2           | —                                                                | HERE's wild Pokémon only (11 land slots: the last 1% reads past them) | Hoenn's gyms in its reversed order, Elite Four (rival by starter), STEVEN |
| SoulGold v1.1.4, v1.2 (both)      | —                                                                | HERE's wild Pokémon only                                             | 16 gyms (NORMAL + HARD), CHUCK / JASMINE / PRYCE by how many of the three are beaten, Elite Four, LANCE, GOLD / CRYSTAL |
| Glazed v9.2.0                     | —                                                                | HERE's wild Pokémon only                                             | Tunod's 8 gyms |
| Imperium v1.3.1                   | —                                                                | HERE's wild Pokémon only                                             | 8 gyms, Elite Four, champion |
| Quetzal Alpha 9                   | —                                                                | HERE's wild Pokémon only                                             | per region (by the map group you're in), NORMAL + HARD |
| Quetzal Spanish Alpha 9           | —                                                                | HERE's wild Pokémon only                                             | as English (its trainers' Spanish names: 265 is PETRA) |

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
   the FireRed QoL project's `tools/telemetry-viewer/scripts/gen_unbound_data.py`'s
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
   commit **completely unpatched** in the project's own `firered-qol-build`
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
lists emulators from its own platform files, so PokeDaisy has to be added there by
its developer.

To try it without a frontend:

```bash
adb shell am start -n com.pokedaisy.app/.LaunchActivity \
  -a android.intent.action.VIEW -d "file:///storage/emulated/0/ROMs/gba/firered-qol.gba"
adb shell am start -n com.pokedaisy.app/.LaunchActivity \
  --es rom /storage/emulated/0/ROMs/gba/firered-qol.gba
```

## Hotkeys

Read from `files/hotkeys.properties` (written with defaults on first run), and
rebindable in Settings > HOTKEYS. Values are Android key names, comma-separated.

| Action                      | Default buttons                          |
| --------------------------- | ---------------------------------------- |
| `save_state` (current slot) | `SELECT`+`R1`, `BUTTON_Y`, keyboard `F1` |
| `load_state` (current slot) | `SELECT`+`L1`, `BUTTON_X`, keyboard `F2` |
| `undo_save` / `undo_load`   | keyboard `F3` / `F4`                     |
| `slot_next`                 | `BUTTON_THUMBR` (right-stick click), `]` |
| `slot_prev`                 | `BUTTON_THUMBL` (left-stick click), `[`  |
| `ff_hold`                   | `BUTTON_R2`, keyboard `SPACE`            |
| `ff_toggle`                 | keyboard `TAB`                           |
| `speed_cycle`               | `BUTTON_L2`, keyboard `=`                |
| `slowmo_hold`               | keyboard `-`                             |
| `exit_game`                 | `SELECT`+`START`, keyboard `ESC`         |

Single-button defaults use only buttons the GBA itself doesn't need; the SELECT
chords are the exception. Hotkey buttons are not forwarded to the game.

The **right and left analog triggers** are also wired directly (many handhelds,
maybe the Thor, report L2/R2 as axes not buttons): right trigger = hold
fast-forward, left trigger = hold slow-mo. If a physical button doesn't trigger
its hotkey, press it once and check `adb logcat -s pokedaisy` for an
`unmapped key <code> (NAME)` line, then put `NAME` in `hotkeys.properties`.

## Cheats

GameShark / Action Replay (v1-v3) / CodeBreaker / VBA codes on mGBA's own cheat engine,
GBA only. Where they live: top-screen Settings > CHEATS (also a game's library menu > CHEATS)
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

## Build

```bash
git submodule update --init third_party/mgba third_party/rcheevos   # first time only (or: make submodules)
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

## Releasing

1. Bump `versionCode` (always up) and `versionName` (semver, e.g. `1.0.4`)
   in `app/build.gradle.kts`.
2. `./gradlew :app:assembleRelease`, signed through an untracked
   `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
   **Every release must use the same key**: the updater's APK only installs
   over a build signed with it. Keep the keystore backed up outside the repo.
   Either password can be left out of `keystore.properties` and kept in the macOS
   Keychain instead (`security add-generic-password -a pokedaisy -s
   pokedaisy-storePassword -w`, and `pokedaisy-keyPassword`): the build reads it from there.
3. Tag `v<versionName>` and publish a GitHub release with the APK attached as
   `PokeDaisy-<versionName>.apk`. The app compares the tag against its own
   `versionName` and ignores drafts and pre-releases, so a test build can go out
   as a pre-release without reaching users.

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
adb push firered-qol.gba /sdcard/Android/data/com.pokedaisy.app/files/roms/
```

Launch the app; it boots the first `.gba` it finds in that folder. Swap ROMs by
pushing a different file (only one at a time for now). SRAM persists to
`files/saves/<rom>.sav`.

**Phase 0 is done when** `firered-qol`, `emerald-qol`, vanilla FireRed, vanilla
Emerald, and Unbound each boot, play at full speed, and have correct sound.

## Layout

| Path                                                    | What                                                                                                                                                               |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `third_party/mgba/`                                     | mGBA source, git submodule @ `0.10.5`                                                                                                                              |
| `third_party/rcheevos/`                                 | RetroAchievements' rcheevos, git submodule @ `v12.5.0`                                                                                                             |
| `app/src/main/cpp/`                                     | `CMakeLists.txt` + JNI bridge                                                                                                                                      |
| `app/src/main/kotlin/com/pokedaisy/app/`               | app code                                                                                                                                                           |
| `.../companion/data/NativeReader.kt`                    | Per-ROM `NativeConfig` address tables + decode logic — start here for "how is game X read" (each config has an extensive comment on how its addresses were found). |
| `.../companion/data/ActiveTables.kt`                    | Picks which species/item/map-name tables and sprite source apply for the currently-detected `GameKind`.                                                            |
| `.../companion/data/SpeciesNames*.kt`, `Poller.kt`      | Per-hack species tables and the SHA1 detection constants, respectively.                                                                                            |
| `scripts/capture_fixture.sh`, `scripts/roms.conf`       | Captures a live EWRAM+IWRAM memory fixture for one ROM off the device.                                                                                             |
| `app/src/test/resources/fixtures/<key>/`                | Captured `{ewram,iwram}.bin` + `README.txt` per ROM — replayed by the JVM decode tests, not committed ROMs/saves themselves.                                       |
| `app/src/test/kotlin/.../companion/data/*DecodeTest.kt` | One regression test per supported ROM, decoding its fixture and asserting the known-real values.                                                                   |
