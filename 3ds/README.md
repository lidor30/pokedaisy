# PokeDaisy for the Nintendo 3DS (early preview)

A homebrew port of PokeDaisy: the game runs on the top screen (libmgba, the
same mGBA 0.10.5 submodule the Android app uses) and the companion lives on
the touch screen. It is a rewrite, not a recompile - the Android app is Kotlin
and Jetpack Compose, which the 3DS can't run - so this folder holds a small,
portable C core that re-implements the companion's reading and drawing, plus a
3DS host and a desktop preview tool around it.

**Status: preview.** It builds, every screen below has been driven on real ROMs and copies of real saves in the
headless simulator (`make sim-test`, below), and the dev build runs on a New 3DS: FireRed, Emerald, Unbound and Monomyth
(32 MB) and Yellow boot copies of real saves to the field with the companion, the HUD and the minimap up. At ASPECT ORIGINAL it holds
60 FPS on the field and in battle in FireRed, Emerald and Yellow; Unbound's field and Monomyth run at about 50, EXPAND at about 46
(`pdlink.py stat`). HOME and the lid work; most companion screens haven't been tried on a console.
Target the **New 3DS / New 3DS XL / New 2DS XL** first; the original 3DS / 3DS XL / 2DS may not hold full speed
(see "Hardware").

## What works

- Game list (`sdmc:/pokedaisy/roms/*.gba`), D-pad or stylus.
- The game on the top screen, with sound (ndsp), in three SCREEN modes:
  **PIXEL 1x** (pixel for pixel, centred), **SHARP 1.5x** (fills the height;
  the default) and **STRETCH** (fills the screen). The smooth two use mGBA's
  own "sharp bilinear" look: the frame at 2x nearest, sampled linearly.
- **Fast-forward**: the ▶▶ chip on the companion toggles it, ZR (New 3DS) holds
  it; 2x / 3x / 4x in SETTINGS. The game is muted meanwhile.
- **Save state**: one slot per game (`sdmc:/pokedaisy/states/`). A state never
  carries the save data, so loading one can't write an old copy over the save
  file; the save is backed up before every load anyway.
- Saves next to the ROM (`<rom>.sav`, like mGBA's own 3DS build), and a backup
  of the save before every start in `sdmc:/pokedaisy/roms/pokedaisy-backups/`
  (newest 10 per game) - the repo's "save files are sacred" rule.
- Settings kept in `sdmc:/pokedaisy/settings.ini`.
- The companion, in the app's FireRed OPTION-screen look:
  - **PARTY**: the six slots in the app's FireRed-like slot (`PartySlot` /
    `SlotGeometry`: the Poke Ball and the game's own icon from your ROM over the
    frame's corner, name, level or a status chip, ♂ / ♀, the HP bar with its HP
    tag and the numbers under it, empty slots faint), edge to edge like
    `PartyGrid`; tap one for its summary (moves, type, PP).
  - **BATTLE**: opens by itself in a battle and goes back after it. **INFO**:
    the foe and you (with the game's own party icons, read from your ROM),
    your moves with the app's verdicts (SUPER 2x / NEUTRAL 1x / RESISTED 1/2x /
    IMMUNE 0x / STATUS). **SUGGESTIONS**: your party's three best picks against
    the foe, each with its best move and verdict (OUT on the one already in
    battle) - the app's ranking. In a trainer battle the bar under both carries
    the **FOE TEAM**: the trainer's party in order, Poké Balls until sent out
    or announced, fainted ones faded; tap one to see it (NEXT / FAINTED /
    RESERVE / UNSEEN) and have INFO and SUGGESTIONS work against it, tap it
    again to follow the battle.
  - **BAG**: one pocket at a time in the game's own order and colours (FireRed's
    or Emerald's bag), item names and counts as the game prints them, a list a
    drag scrolls, the tapped item's description.
  - **MAP**: the game's own region map, rebuilt from your ROM (the app's
    `RomArt`: found by fingerprint, nothing bundled) - Kanto and the Sevii
    pages, or Hoenn - with the player's head where the game puts it, the place
    name in the game's own label, and FireRed's blinking cursor on a tapped
    tile. PLACES lists every town, route and place to jump to.
  - **GUIDE**: the app's hand-written pages (TIPS / WHERE IS / STUCK?, with the
    save's progress checked off), hint first, then the answer. HERE is the
    current area: its to-dos, wild POKéMON (from the ROM, with odds), gifts,
    trades and items (a ball = done). BOSS is the next unbeaten gym leader /
    Elite Four / champion with their team from the ROM. A first-open notice
    says the guide is AI-written and may be wrong, as in the app.
  - **POKéDEX**: seen / caught from the save, in Kanto / Hoenn or National
    order (the save's own, or the other with a tap); an entry has the game's
    picture, category, types, height / weight, text, base stats and ability.
  - **SETTINGS** (the gear): place, money, file and speed, then SCREEN and
    FAST-FORWARD (pick-lists), TAB BAR (which tabs sit on the bar, at most five;
    the rest open from a MORE chip), SAVE STATE / LOAD STATE / LEAVE GAME
    (confirmed). The list scrolls (a drag) where it doesn't fit.
  - **TRAINER CARD** (the button in SETTINGS' title, like the app's TOOLS): the
    game's own card, drawn from your ROM's art pixel for pixel - name, ID,
    money, POKéDEX, play time with its blinking colon, badges, stars, the
    trainer pic; a tap flips it to the back (Hall of Fame debut, link battles,
    trades, FireRed's stickers ...) the way the game does. FireRed rev 1,
    LeafGreen and Emerald, as in the app.
- **Games: what the app knows** (`core/pd_games_gen.c`, 158 ROMs by CRC32, exported from the app's own detection):
  every retail GBA game in every language, the GBA hacks, and the Game Boy games (Red, Blue, Yellow in five
  languages, Gold, Silver, Crystal, Crystal Clear, Prism, Polished Crystal, PureRed / PureBlue). Per game: its
  names, moves, types and type chart, party and bag palettes, backdrop, and the companion's windows in the Game
  Boy games' black on white. No DS: the 3DS can't run melonDS.
- **Library**: the app's rail, view pills and tiles.
- **Battle controls**: FIGHT / BAG / RUN / POKéMON, the moves, the party to send in (BEST / OUT), ONE-TAP MOVES,
  on the GBA games with their handlers known and on every Game Boy game (its 2x2 menu in the cart's own words).
- **QUICK MENU** (Y): PC, FLY, REPEL, BIKE, WILD, states, FF. The Game Boy games have PC, FLY and WILD (`GbField`).
- **EXPAND / ZOOM OUT** on the games the app has it for, Game Boy too, and the **HUD** (party, minimap, balls,
  Repel steps), kept to the game's picture without EXPAND.
- **BOXES**: the PC's boxes live, in FireRed's and Emerald's colours and wallpapers; encrypted, plain and CFRU
  storage. WITHDRAW, DEPOSIT and SWAP through the game's own functions where the app has them.
- **USE / GIVE / TAKE**, the summary's STATS, the bag's SORT, **DEX** sections (INFO, EVOLVE, AREA, MOVES on
  vanilla's tables), region maps on the hacks (`RomRegionMap`, RomArt's fingerprints, smol) and the Game Boy games'
  town maps, **STATES** with ten slots, thumbnails and UNDO, **DARK MODE**, **THEME**, **FF MODE**, **CHEATS**
  (mGBA's format, `sdmc:/pokedaisy/cheats/<CRC32>.cheats`, one master switch).

Not here yet: the Game Boy games' DEX and GUIDE, GUIDE's live pages on hacks with their own tables, the hacks'
evolutions and learnsets in DEX, Quetzal v1's BAG4, the game's own font on the Game Boy games, Japanese text
(kana), REWIND, STEADY music, shaders, ACHIEVEMENTS, Nuzlocke, co-op, translated UI, seasonal themes, RAINBOW's
coloured chips, per-cheat switches.

## Controls

| | |
|---|---|
| A / B / START / SELECT / L / R / D-pad or circle pad | the GBA's buttons |
| Touch screen | the companion |
| Y | QUICK MENU (the circle pad aims the ring) |
| X | companion back (close a summary, an entry, a pick-list, a confirm) |
| ZL (New 3DS) | next companion tab |
| ZR (New 3DS) | fast-forward while held |
| START on the game list | quit to the Homebrew Launcher |

## Installing on a 3DS

Needs a 3DS with custom firmware (Luma3DS) and the Homebrew Launcher.

1. Copy `pokedaisy.3dsx` to `sdmc:/3ds/`.
2. Put your own legally dumped `.gba` files (and any `.sav` with the same name)
   in `sdmc:/pokedaisy/roms/`.
3. For sound, the DSP firmware must be dumped once (`sdmc:/3ds/dspfirm.cdc`, the
   DSP1 homebrew does it) - without it the game runs silent.

## Running it without a 3DS (Azahar)

[Azahar](https://azahar-emu.org) (Citra's successor) runs `.3dsx` homebrew on
a Mac, Windows or Linux.

1. Install Azahar (macOS: the `.dmg` from its releases page). In its settings,
   turn on **Enable New 3DS mode** (System) - the port targets the New 3DS.
2. Build the app: `cd 3ds && make 3ds` (Docker Desktop running) gives
   `ctr/pokedaisy.3dsx`.
3. **File > Open Azahar Folder**, then in `sdmc/` make `pokedaisy/roms/` and
   put your `.gba` files there (a `.sav` beside one with the same name is used).
   No ROM handy? `make decomps fixture-roms` makes test ROMs: the
   `build/fixture-*-rom.gba` ones open every tab (the trainer battle is
   `fixture-emerald_trainer-rom.gba`).
4. **File > Load File...** and pick `pokedaisy.3dsx`. The mouse is the stylus
   on the bottom screen.

Azahar's default keys (Emulation > Configure > Controls to change them): A =
`A`, B = `S`, X = `Z` (next companion tab), Y = `X` (companion back), START =
`M`, SELECT = `N`, L / R = `Q` / `W`, ZR = `2` (fast-forward), D-pad =
`T` `F` `G` `H`, circle pad = the arrow keys. Azahar plays sound without the
DSP firmware. It shows that things work, not how fast they'll run on a console.

## A dev build over Wi-Fi

`make 3ds-dev` builds `ctr/pokedaisy-dev.3dsx`, kept apart from the real one (`sdmc:/pokedaisy-dev/`, title
"PokeDaisy dev"), with a small link (`ctr/devlink.c`, TCP 17498, a UDP beacon on 17499) that `tools/pdlink.py`
talks to: `find`, `ping`, `stat`, `log`, `put` / `push` / `get` / `ls` / `rm` (only under `sdmc:/pokedaisy-dev/`),
`press`, `tap`, `drag`, `shot`, `leave`, `restart`, `launch`. Start it once from the Homebrew Launcher (press Y
there for its netloader and send it with `3dslink`, or copy it to `sdmc:/3ds/`), then `tools/pdlink.py find`. Its port
17491 can take a while to open after Y, so retry `3dslink -a IP`. `push` puts the build in `sdmc:/3ds/` too, and games go
in `roms/` (a new one shows after `restart`). Never in a release build.

## The simulator

`make sim` builds `build/sim/pd_sim` (`desktop/sim/`): the real session (`host/`) and companion over the real mGBA,
with no 3DS code, from a script of `run`, `press`, `quick`, `tap`, `hit ID`, `hits`, `shot`, `wild`, `wait-state`,
`press-to-state`, `poke`, `peek`, `state`, `expect`, `aspect`, `screen`, `dark`, `theme`. `make sim-test` runs
`test/sim_*.txt` on FireRed and Emerald (`ROM_DIR`, `SAVE_DIR`), `make sim-test-gb` the Game Boy script on Yellow; saves
are copied first, never written.

## Game data from the app

```sh
cd ui-preview && gradle gameExport -PgameExport -Proms=<ROMs folder> -Pout=<dir>
python3 3ds/tools/gen_games.py <dir>
```

`src/render/GameExport.kt` runs each ROM through the app's own detection (with `-PgameExport` the core is a stub
answering from the file) and writes what it picked: the config, the tables, the palettes, every theme's windows,
which art it wants. `gen_games.py` turns that into `core/pd_games_gen.c`. A new game or a changed table: run both.

## Building and testing

Everything runs in Docker, so it works the same on a Mac or a cloud session.

```sh
cd 3ds
make decomps       # pret's FireRed rev 1 / Emerald, = retail (below; optional)
make preview       # the companion from the RAM fixtures -> build/shots/*.png
make 3ds           # ctr/pokedaisy.3dsx (devkitpro/devkitarm image)
make fixture-roms  # build/fixture-*.gba test ROMs (below)
make azahar-test   # the .3dsx in the Azahar 3DS emulator -> build/azahar/*.png
make azahar-battle-test  # BATTLE in Azahar, a trainer battle -> build/azahar-battle/
make tables        # regenerate core/*_gen.c and ctr/icon.png from the app
```

- **`make preview`** is this port's ui-preview: `desktop/pd_preview.c` reads a
  fixture's `ewram.bin` / `iwram.bin` (`app/src/test/resources/fixtures/`) and
  drives the companion with taps found by hit id - a pressed slot, a summary,
  BATTLE, BAG (an item, a drag, the next pocket), SETTINGS, a pick-list, a
  confirm, MAP (a tapped tile, the blink, PLACES), POKéDEX (scrolled, an
  entry, the next, the other dex), GUIDE (the notice, every page, a hint and
  its answer), BATTLE's INFO and SUGGESTIONS, the TRAINER CARD (blinked, mid-flip,
  the back) - drawing each as the 3DS's two screens. It also renders each card
  whole and prints the CRC32 `TrainerCardTest` pins: FireRed's `d62339db` /
  `045996a2` and Emerald's `f3e14960` / `74484c21`, the cards that matched the
  games' own screens pixel for pixel, come out the same here. There's no retail trainer-battle fixture, so the preview makes one
  from the wild battle (`emerald_de_battle`): three of the player's Pokémon
  copied into gEnemyParty beside the wild one, the trainer flag set; then the
  first faints and the trainer's next pick is set, and the last slot is
  tapped (`-trainer*` shots). That RAM is kept as
  `build/fixtures/emerald_trainer` for `make fixture-roms`. No emulator: look at
  every UI change here. `PD_PREVIEW_HITS=1` also prints each shot's tap
  targets, for writing the Azahar steps.
- **`make decomps`** (`test/build_decomps.sh`; git, a C compiler, libpng and
  devkitARM) builds pret's pokefirered (rev 1) and pokeemerald at pinned
  commits into `build/decomps/` and checks they match the retail SHA1s. The
  tabs that read the ROM need one; the preview passes them in when they're
  there (or `make preview FR_ROM=... EM_ROM=...`). Never commit them.
- **Fixture ROMs** (`test/fixture_rom.c`) are a few lines of GBA homebrew that
  copy a fixture's RAM into the GBA and idle, with FireRed's or Emerald's game
  code in the header. The companion can't tell them from the real game, so the
  whole app can be tested without a Pokémon ROM. They hold no game code or
  data, only the fixture's save state. With the decomps built there's also
  `build/fixture-*-rom.gba`: the same written over the decomp's ROM (its
  first ~300 KB, game code the loader never runs), so MAP / POKéDEX / GUIDE
  find the game's tables and art - derived from a game ROM, so build/ only.
- **`make azahar-test`** boots the real `.3dsx` in Azahar (the
  `linuxserver/azahar` image, on Xvfb) on the FireRed fixture ROM and taps
  through it like a player: game list, PARTY, BAG, SETTINGS, each SCREEN mode,
  fast-forward, a save state and loading it, MAP, PLACES, POKéDEX, GUIDE (on
  the `-rom` fixture when it's built) - screenshots in `build/azahar/`,
  and the emulated SD card (states, backups, settings) in `build/azahar/sd/`.
  Azahar shows that it works, not how fast: speed has to be measured on a
  console. **`make azahar-battle-test`** does BATTLE the same way on the
  trainer battle: INFO with the FOE TEAM, SUGGESTIONS, foes tapped.

## Layout

| Path | What |
|---|---|
| `core/` | Portable C, no platform code: game detection (`pd_game`, the app's `NativeConfig` addresses), the snapshot reader (`pd_snapshot`: party, battle, bag, money, place - ports `Gen3Mon.kt` / `readNativeTelemetry` / `readNativeBag`), the canvas (`pd_canvas`: pixel-stepped corners, layered OPTION frames, GBA-shadowed and wrapped text, drawn cursors), the companion (`pd_ui.c` frame + `pd_ui_party` / `_battle` (INFO, SUGGESTIONS, FOE TEAM) / `_bag` / `_map` / `_guide` / `_dex` / `_settings` / `_widgets`), the ROM readers (`pd_romart`: `RomArt`'s scan - every blob of the game in one pass; `pd_map`: the region map and heads, `RegionMapModel`'s pick, `PlayerMapTile`; `pd_dex`: the dex tables and front pics; `pd_guide`: `GuideRom.kt`'s bosses, teams and wild tables), the party icons (`pd_icon`: `DecompIconSource`'s tables), the TRAINER CARD (`pd_card`: `readTrainerCard` + `TrainerCardArt`; `pd_ui_card`), the game list (`pd_menu`) and the settings file (`pd_settings`) |
| `core/*_gen.c` | Generated: names / moves / type chart / map sections / item text / gender ratios from the app's Kotlin tables (`tools/gen_tables.py`), the ROM art's fingerprints (map and card) and the cursor grids (`tools/gen_map_tables.py`), the guides and area data (`tools/gen_guide.py`), Pixel Operator as a 1-bit font plus drawn ♂ ♀ (`tools/gen_font.py`) |
| `ctr/` | The 3DS host: `main.c` (game loop, ndsp audio, HID, files, states) and `gpu.c` (citro2d: the game and canvases written into textures in the GPU's tiled order, drawn as quads), and the CMake build against the mGBA submodule |
| `host/` | What a running game needs on any host: the session (`pd_session`), USE / GIVE / TAKE and the PC's moves (`pd_items`), the Game Boy games' field actions (`pd_gb_field`) |
| `desktop/` | The preview tool, its small PNG writer and the simulator (`sim/`) |
| `test/` | The fixture ROM, the decomp build and the Azahar script |
| `tools/` | The generators |

Keep in sync with the app: `core/pd_configs_gen.h`'s addresses are generated from
`gamedata/` with the app's (`make gamedata`; `gamedata/targets/3ds.toml` picks what
this port reads), `pd_snapshot.c` mirrors `Gen3Mon.kt` / `readNativeTelemetry`,
and the tables are regenerated (`make tables`) whenever the app's change.

GPU notes (found in Azahar): textures are written by the CPU straight into
linear memory in the tiled order, top row first (v = 1). Display transfers into
textures came out upside down, and into VRAM textures rotated, so they're
not used; nor is render-to-texture.

## Hardware

- **New 3DS / New 3DS XL / New 2DS XL** (804 MHz, 256 MB): the target; the app
  turns on the faster clock (`osSetSpeedupEnable`).
- **3DS / 3DS XL / 2DS** (268 MHz, 128 MB): mGBA alone is close to its limit
  there, and the companion's drawing shares the CPU. Expect slowdowns; PIXEL 1x
  is the cheapest screen mode, and fast-forward may not get past 1x.

## Next steps

- Run it on a New 3DS: measure the frame time per screen mode, check the texture orientation, the overlay's byte
  order, sound and save writes on hardware.
- The list under "What works": the Game Boy games' DEX and GUIDE first.
