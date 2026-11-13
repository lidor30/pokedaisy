# PokeDaisy for the Nintendo 3DS (early preview)

A homebrew port of PokeDaisy: the game runs on the top screen and the companion lives on the touch screen. GBA games
run on **armGBA** (`third_party/armgba`: a GBA core with a recompiler for the 3DS's ARM11, at ratherDashing's
`3ds-v0.0.2-alpha`), Game Boy and Game Boy Color games on **libmgba** (the same mGBA 0.10.5 submodule the Android app
uses). It is a rewrite, not a recompile - the Android app is Kotlin
and Jetpack Compose, which the 3DS can't run - so this folder holds a small,
portable C core that re-implements the companion's reading and drawing, plus a
3DS host and a desktop preview tool around it.

**Status: preview.** It builds, every screen below has been driven on real ROMs and copies of real saves in the
headless simulator (`make sim-test`, below), and the dev build runs on a New 3DS: FireRed, Emerald, Unbound and Monomyth
(32 MB) and Yellow boot copies of real saves to the field with the companion, the HUD and the minimap up. It holds
60 FPS on the field and in battle in FireRed, Emerald, Monomyth and Yellow, walking and surfing included, and in the
game's own menus (30 on mGBA); Unbound 58-60 on the field and 60 in battle (43 on mGBA; see "Sound and speed").
On the game's own menu screens (the intro, the title, CONTINUE, the party, the bag: EXPAND's MENU scene, and a fade
from one) EXPAND and ZOOM OUT give way to STRETCH: no map to show past them, and drawing their margins cost the frame
(`menu_screen` in `host/pd_session.c`). EXPAND runs at the same speeds at SHARP 1.5x and 1.25x (Emerald's Route 113, its ash the heaviest scene so far, 55-60), its drawing on the fourth core beside the next frame and the 2x picture built by the GPU; it has no PIXEL 1x, which couldn't keep up, and a battle always goes to 1.5x
(`pdlink.py stat`). HOME and the lid work; most companion screens haven't been tried on a console.
Target the **New 3DS / New 3DS XL / New 2DS XL** first; the original 3DS / 3DS XL / 2DS may not hold full speed
(see "Hardware").

## What works

- Game list (`sdmc:/pokedaisy/roms/`: `.gba`, `.gb`, `.gbc`), D-pad or stylus.
- The game on the top screen, with sound (ndsp), in three SCREEN modes:
  **PIXEL 1x** (pixel for pixel, centred), **SHARP 1.5x** (fills the height;
  the default) and **STRETCH** (fills the screen). The smooth two use mGBA's
  own "sharp bilinear" look: the frame at 2x nearest, sampled linearly.
- **Fast-forward**: the ▶▶ chip on the companion toggles it, ZR (New 3DS) holds
  it; 2x / 3x / 4x in SETTINGS. The game is muted meanwhile.
- **Save state**: one slot per game (`sdmc:/pokedaisy/states/`). Loading one keeps the save memory as it is, so it
  can't write an old copy over the save file; the save is backed up before every load anyway. A GBA game's states are
  armGBA's own: one made by an earlier (mGBA) build is turned away with a notice and left where it is.
- `sdmc:/pokedaisy/log.txt` says what the last run did (`log-prev.txt`: the run before): memory, whether armGBA's
  recompiler and its render thread started, each save written. Attach it to a bug report. `armgba/` keeps each game's
  warm list (the code the recompiler translated, so a second run translates it ahead of need); `system/gba_bios.bin` is
  used if it's there, else armGBA's own BIOS.
- Saves next to the ROM (`<rom>.sav`, in mGBA's layout: the save memory, then the clock, so a save moves either way
  between this build and mGBA; armGBA's are written to a new file first, then put in place), and a backup
  of the save before every start in `sdmc:/pokedaisy/roms/pokedaisy-backups/`
  (newest 10 per game) - the repo's "save files are sacred" rule.
- Settings kept in `sdmc:/pokedaisy/settings.ini`.
- The companion, in the app's FireRed OPTION-screen look with 1.5.0's buttons: every tab chip, button and tile has
  HeartGold's two-tone face (`pd_two_tone`, the app's drawTwoTone).
  - **PARTY**: two columns of three across the whole width, each card the game's own where
    the app has its art: its own height and paddings, widened only, shown as big as three
    rows allow (4/3 or 5/4 in sharp bilinear like the game on the top screen, 1x under the
    battle pane's bar). A scaled card's text is drawn over it at whole pixels in Pixel Operator.
    `pd_party_art` rebuilds the frames, Poke Ball, status icons, FONT_SMALL and the
    backdrop from your ROM, by RomArt's fingerprints. Any other game keeps the slot in its
    palette (`PartySlot` / `SlotGeometry`), edge to edge like
    `PartyGrid`; tap one for its summary (moves, type, PP).
  - **BATTLE**: opens by itself in a battle and goes back after it, over the DS battle screens' mint and Poké Ball.
    The controls are HeartGold's layout and buttons: FIGHT over the four pale move places, BAG and POKéMON in the
    corners with RUN between them a little lower, the moves as HeartGold's cards (the type's colour round white over
    beige). SETTINGS > BATTLE STYLE > GEN 5 (`pd_ui_battle_gen5.c`, the app's `BattleControlsGen5.kt`) draws Black / White's
    touch screen instead, edge to edge: the red and lilac ball with FIGHT! in its lens, BAG / RUN / POKéMON as round plates
    rising from the bottom, the moves as hexagon plates in the opened field, the game's green-rimmed party slots, and the
    closed ball in the dark behind INFO / SUGGESTIONS (a Game Boy game keeps its own look). BATTLE INFO off leaves the
    buttons alone (no INFO, SUGGESTIONS or move ratings; a double battle says to use the game's own menu); BATTLE VIEW picks
    whether the tab opens on the buttons or INFO; after RUN the buttons stay up until the battle ends. English FireRed and
    Emerald draw their own type tags, read from the ROM (`pd_type_tags`). **INFO**: you and the foe side by side (side, icon, type tags, level, HP), your moves with
    the app's verdict pills (SUPER 2x / NEUTRAL 1x / RESISTED 1/2x / IMMUNE 0x / STATUS) on the left and what the
    foe is WEAK TO / RESISTS / IMMUNE TO on the right. Its corner button opens **FOE DETAILS**: your side shrinks to a
    strip (icon, HP standing up) and the foe gets the page - its ability (named from the ROM), held item, live stats with
    stage pills, and its moves with what each does to you. **SUGGESTIONS**: your party's three best picks against
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
    Elite Four / champion with their team from the ROM. A game with no WHERE IS
    of its own gets one built from its area data: HMs, key items, gift POKéMON.
    Red, Blue, Yellow in five languages, Yellow Legacy, Crystal and Crystal Legacy
    have it from the app's generated guide tables (`core/pd_guide_gen.c`,
    `tools/gen_guide.py`), Polished Crystal from its own world (`core/pd_gb_world_gen.c`,
    `tools/gen_gb_world.py`): HERE follows the game's clock and, on Polished Crystal,
    your badges (Crystal Legacy's three any-order gyms too); the champion's teams show
    side by side until the save settles one. A first-open notice says the guide is
    AI-written and may be wrong, as in the app.
  - **POKéDEX**: seen / caught from the save, in Kanto / Hoenn or National
    order (the save's own, or the other with a tap); an entry has the game's
    picture, category, types, height / weight, text and base stats. On every Game
    Boy game too, one list each, read from the cart (`core/pd_gb_dex.c`: Gen 1's
    sprite compression, Gen 2's LZ, Crystal Clear's dictionary, Prism's packed text,
    Polished Crystal's Huffman). `make gb-check EXPORT=<a gameExport>` holds every
    page, picture and wild list of every cart against the app's own reading.
  - **SETTINGS** (the gear): GAME (place, money, file and speed), FF / SPEED, SOUND, GAMEPLAY, SCREEN and
    COMPANION, with the app's group tabs down the left outside the list's window (a tap scrolls to the group,
    the group in view lit); TAB BAR (which tabs sit on the bar, at most five; the rest open from MORE, a grid of
    tiles across the whole width), CLOSE GAME / RESTART GAME. The list scrolls (a drag) where it doesn't fit.
  - **TRAINER CARD** (the button in SETTINGS' title, like the app's TOOLS): the
    game's own card, drawn from your ROM's art pixel for pixel - name, ID,
    money, POKéDEX, play time with its blinking colon, badges, stars, the
    trainer pic; a tap flips it to the back (Hall of Fame debut, link battles,
    trades, FireRed's stickers ...) the way the game does. FireRed rev 1,
    LeafGreen and Emerald, as in the app.
- **Games: what the app knows**, found by SHA-1 (`core/pd_game.c`; configs from `gamedata/`, `core/pd_configs_gen.h`,
  the looks the TOML doesn't hold from the app's export, `core/pd_games_gen.c`): every retail GBA game in every
  language, collection dumps of retail, retail FireRed / Emerald as Universal Pokemon Randomizer ZX leaves them (any
  seed: `core/pd_randomized.c`, what the randomizer changed read from the ROM), the GBA hacks, and the Game Boy games
  (Red, Blue, Yellow in five languages, Yellow Legacy, Gold, Silver, Crystal, Crystal Clear, Crystal Legacy, Prism,
  Polished Crystal, PureRed / PureBlue). Per game: its names, moves, types and type chart, party and bag palettes,
  backdrop, and the companion's windows in the Game Boy games' black on white. No DS: the 3DS can't run melonDS.
- **Library**: a rail of pages down the touch screen, an icon each (the app's tool icons): GAMES, SAVE EDITOR, TRADE SAVES and
  SETTINGS (SELECT steps through them), each page's title with the battery and its percentage. GAMES lists the games as rows or tiles (SETTINGS > VIEW) in the companion's look,
  ALL / RECENT / GBA / GB / HACKS on L / R, sorted by NAME or LAST PLAYED. A game is named by its file name when the app
  knows it, else by its contents: the header's code at once, its SHA-1 a slice a frame (once; kept in `library.tsv`), so
  `unbound.gba` shows as Pokémon Unbound, a HACK. The top screen shows the game under the cursor the way the games' CONTINUE
  screen does: player, time, Pokédex, the badges, money, the place, and the party with its icons (from the save itself where
  the save editor reads it, the rest from `cards/<game>.card`, what the game looked like when it was last left). SETTINGS
  holds the settings that aren't a game's: the library's VIEW and SORT, THEME, DARK MODE, SCREEN, ASPECT, the sound, the
  battle buttons, HUD and FLY STYLE.
- **SAVE EDITOR** (`core/pd_library_saves.c` over `core/pd_save.c`, the app's `Gen3SaveFile` / `Gen3SaveEditor` ported): a game's
  save file opened with the game closed, its PC boxes on the touch screen (A picks a Pokémon up and puts it down, L / R the
  box), MONEY and COINS, SAVE. The top screen shows the Pokémon on the cursor. Written the game's way (the other slot,
  counter + 1, read back before it's handed out), backed up first and checked again on the SD card before it replaces the
  file. Retail English / European FireRed, LeafGreen, Ruby, Sapphire and Emerald saves, as in the app; no bag, held items or
  moves yet (the app's `Gen3Legal` tables).
- **TRADE SAVES**: two games' saves, PC box to PC box, by the games' own rules (`Gen3Trade`: FireRed / LeafGreen only after the
  Sevii Islands, no National Dex = no eggs or Pokémon outside the regional dex, Mew / Deoxys from an event only, no mail;
  trade evolutions, the Pokédex, Ruby / Sapphire's National Dex). A tab per side over its box, a Pokémon (or an empty place)
  picked on each, Y trades; both saves written or neither. `make test-save` runs `core/pd_save.c` on real saves.
- **Battle controls**: FIGHT / BAG / RUN / POKéMON, the moves, the party to send in (BEST / OUT), ONE-TAP MOVES,
  on the GBA games with their handlers known and on every Game Boy game (its 2x2 menu in the cart's own words).
- **QUICK MENU** (Y): PC, FLY, FLASH (English FireRed, LeafGreen, Emerald, Ruby, Sapphire: the game's own cut-in and light,
  dimmed outside a dark cave; `linux/flash.c`), REPEL, BIKE, WILD, states, FF. The Game Boy games have PC, FLY and WILD (`GbField`).
- **FREE FLIGHT** (retail English FireRed, LeafGreen and Emerald, as in the app): TAKE OFF from the QUICK MENU or under the
  MAP's buttons puts you on the game's FLY bird (or FLY ON's balloon, helicopter or zeppelin), the D-pad flies over anything,
  B or LAND comes down on the nearest ground. FLY STYLE SCENIC (the default) flies FLY's trip over the real map, zoomed out on
  EXPAND, and lands at the town's FLY spot; a town that isn't joined to where you are gets the game's own FLY. FLIGHT AREA
  VISITED (the default; the app's WHERE YOU'VE BEEN) keeps both to the maps your save has seen, kept per save in
  `sdmc:/pokedaisy/flight-area/`. The flying is the app's own `pk_flight.c`; `linux/flight.c` has its gates, the area and the
  route (a breadth-first search over the joined maps, about a millisecond on a desktop).
- **EXPAND / ZOOM OUT** on the games the app has it for, Game Boy too, and the **HUD** (party, minimap, balls,
  Repel steps), kept to the game's picture without EXPAND, in the app's battle HP box look: every window with its
  shadow down and right, the party as sprite and HP bar (status tags in a row of their own), the QUICK MENU's tiles
  two-tone glass with the party screen's orange on the picked one.
- **BOXES**: the PC's boxes live, in FireRed's and Emerald's colours and wallpapers; encrypted, plain and CFRU
  storage. WITHDRAW, DEPOSIT and SWAP through the game's own functions where the app has them.
- **USE / GIVE / TAKE** (USE on a TM or HM opens the game's own TM screen in English FireRed, LeafGreen and Emerald,
  back to the field when it closes: `linux/tm_teach.c`), the summary's STATS, the bag's SORT, **DEX** sections (INFO, EVOLVE, AREA, MOVES on
  vanilla's tables), region maps on the hacks (`RomRegionMap`, RomArt's fingerprints, smol) and the Game Boy games'
  town maps, **STATES** with ten slots, thumbnails and UNDO, **DARK MODE**, **THEME**, **FF MODE**, **CHEATS**
  (mGBA's format, `sdmc:/pokedaisy/cheats/<CRC32>.cheats`, one master switch).

Not here yet: GUIDE's live pages on hacks with their own tables, the hacks'
evolutions and learnsets in DEX, Quetzal v1's BAG4, the game's own font on the Game Boy games, Japanese text
(kana), REWIND, STEADY music, shaders, ACHIEVEMENTS, Nuzlocke, co-op, translated UI, seasonal themes, RAINBOW's
coloured chips, per-cheat switches.

## Sound and speed

**armGBA** (GBA games, `emu/`): its recompiler runs the game on the main core, its renderer on the New 3DS's third core
(one worker: armGBA-3DS puts a second on the fourth, where it starved the companion's draw thread and EXPAND's; `ctr/host.c`
declines it) and its mixer on the game's thread. It sits behind mGBA's own `struct mCore` (`emu/pd_armgba_core.c`: a
`struct GBA` of mGBA's shape whose memory points into armGBA's, io / palette / OAM copied after each frame), so every
reader shared with the mGBA hosts runs unchanged; `emu/pd_dispatch.c` sends `pk_call` / `pk_host` / `pk_cheats` to the
core that runs the game (the app's files are built with those names given an `_mgba` ending), and `pk_call` runs the
game's functions on armGBA's interpreter (`emu/pd_armgba_call.c`). armGBA's renderer can't be hooked, so EXPAND takes every
line as drawn with the registers the frame ended with and finds the VRAM tiles that changed by comparing
(`pk_wide.c`'s `lineHookless`), and FREE FLIGHT's glide is worked out after each frame (`pk_wide_after_frame`) instead of
at line 0, `pk_flight` drawing the bird at it; the composed screen equals mGBA's frame for frame in the sim (`widesum`,
walking, battles, a diagonal flight's glide). The native m4a mixer (`host/pd_mixer.c`, below) runs on armGBA too: a Thumb BKPT
there goes to `gbaBkptHook` first (`third_party/patches/armgba-pokedaisy.patch`, applied at configure time by
`apply_armgba_patch.cmake`, as melonDS's is), and the mixer reaches the CPU through `pd_armgba_reg` / `_set_reg` / `_jump`.
In the sim it matches the game's own mixer byte for byte on both cores (`mixer`), and `PD_SIM_MIXER_CHECK=OFF` builds the
native one alone, as the console runs it. It's what armGBA gains most from in Unbound (ipatix's mixer rewrites its own
code): fast-forward 130 FPS (95 without). A battle keeps the H-blank interrupt on with no callback set: 228 interrupts a frame
that only set two flags, 12 ms of armGBA's 18 (it leaves compiled code for each; 52-55 FPS). Where the game has the
stock HBlankIntr (found by its code, FireRed's at 0x080007F0, Emerald's at 0x08000814), armGBA asks first
(`gbaHblankIrqHook`, the same patch) and `pd_armgba.c` sets the two flags itself and charges the handler's time (190
cycles), so the game keeps its speed and lag frames: in the sim seven of eight test runs equal the real interrupts frame for
frame, the eighth reaching its battle menu a frame sooner (`PD_SIM_HBLANK=0` raises them as ever,
`PD_SIM_HBLANK_COST=N` tries another cost). Battles: FireRed, Emerald and Unbound 60 FPS, about 6-7 ms of armGBA a frame. Measured on a New 3DS (FireRed, `pdlink stat`): emulation 6-7 ms a frame on the field (mGBA 8.8), the game's
party menu 60 FPS (30), fast-forward 152 FPS at ORIGINAL and 141 with EXPAND (111). Gambatte, which armGBA runs Game Boy
games on, is GPL-2.0-only and can't be distributed with this GPL-3.0 code: `emu/gb_none.c` stands in for it. The cartridge
buffer is one 32 MB block reserved at start (`host_rom_arena`): a block per game left the heap too broken up for a 32 MB
game after another had run. The dev build's `sdmc:/pokedaisy-dev/armgba/mgba` runs GBA games on mGBA, to compare;
`stat` adds armGBA's counters (`ag_wait_us` / `ag_render_us` / `ag_compile_us` / `ag_flush_us`) and the fourth core's
(`wide_thread_us`, `draw_job_us`; its split `job_tex_us` / `job_begin_us`, the wait for the refresh / `job_canvas_us` /
`job_rest_us`). Each `stat` busies the link's core ~25 ms (its `clock_us`), so a reading a second costs frames of its own:
for a quiet measurement read the running totals (`tot_loops`, `tot_late` split by `tot_late_core` / `_draw` / `_rest`,
`tot_gaps`, the sound queue's `queue_min`) twice, a stretch of play apart. The sim runs armGBA's interpreter (a desktop recompiler would need its own executable
memory); `PD_SIM_CORE=mgba` puts a GBA game on mGBA there too.

**mGBA** (Game Boy games, and the comparison above): half of a frame on a New 3DS is the game's own sound mixer, the m4a
engine's `SoundMainRAM`. `host/pd_mixer.c` runs it natively:
a BKPT over its first instruction in IWRAM hands it to C, on every ROM that carries the stock routine (106 of the 136 GBA ROMs the app
knows, found by its CRC, and only once the copy in IWRAM is that code). Every sim run does both on the same state and compares every
byte (`mixer` in a script, `make sim-test`). Saved states never hold the hook. Unbound (both builds), Odyssey and Rocket Edition run
ipatix's self-modifying high-quality mixer in its place, ported the same way (`hq_*`), down to its skipped multiplies. GS Chronicles
runs another build of that one; Amethyst, Dreamstone Mysteries and Wishes of Tomorrow variants of the stock one: those stay emulated.

Sound plays at the DSP's own 32728 Hz, a sample out for a sample in. A game under full speed can't make its sound in time, so the
channel's rate follows how fast the chunks arrive (`audio_follow` in `ctr/main.c`): the music slows with the game rather than breaking
up, down to 85%, and is back at exactly 1 at full speed. `stat` on the dev link reports `gaps`, `drops` and the `rate`, with where a
frame went (`run_us` the game, `wait_us` its renderer, `wide_us` EXPAND, `snap_us` the snapshot), and the companion's `redraws`
with the slowest (`redraw_worst_us`). A redraw runs between two game frames, so it's paid in frame time: about 3.5-7 ms on a
New 3DS (a 320 x 240 x 4 memcpy alone is 1-1.8 ms there; a desktop is ~150x faster, so judge screens against each other with
the simulator's `bench N`). Boxes paint each frame layer once (`pd_canvas.c`'s rings), text is clipped a row at a time with its
shadow in one pass, and PARTY's scaled cards are kept until the party, cursor or look changes. GEN 5's ball screens are kept
as runs of one colour a row (drawn again with writes only) and drawn a row at a time: columns of fills missed the cache on
every pixel, 42 ms a redraw before, about 4 ms now.

## Controls

| | |
|---|---|
| A / B / START / SELECT / L / R / D-pad or circle pad | the GBA's buttons |
| Touch screen | the companion |
| Y | QUICK MENU (the circle pad aims the ring) |
| X | on the field: the buttons go to the companion, a silver strip on the game pointing down at it (L / R step its tabs, the D-pad and A its cursor; X, START, or B with nothing left to go back from bring them back). Elsewhere companion back |
| ZL (New 3DS) | previous companion tab |
| ZR (New 3DS) | fast-forward while held |
| START on the library | quit to the Homebrew Launcher |
| SELECT on the library | the next page: GAMES, SAVE EDITOR, TRADE SAVES, SETTINGS |

## Installing on a 3DS

The step-by-step guide for players is [INSTALL.md](INSTALL.md), in every language the app has: [日本語](INSTALL.ja.md),
[한국어](INSTALL.ko.md), [Français](INSTALL.fr.md), [Deutsch](INSTALL.de.md), [Italiano](INSTALL.it.md),
[Español](INSTALL.es.md), [Português (Brasil)](INSTALL.pt-br.md), [Português (Portugal)](INSTALL.pt.md) (the manual's
`docs/manual/3ds.md` and its translations, also on pokedaisy.com/docs/3ds/). In short: a 3DS with custom firmware
(Luma3DS) and the Homebrew Launcher; `pokedaisy.3dsx` in `sdmc:/3ds/`; your own `.gba` / `.gb` / `.gbc` files (and any
`.sav` with the same name) in `sdmc:/pokedaisy/roms/`; the DSP firmware dumped once for sound (`sdmc:/3ds/dspfirm.cdc`,
Rosalina's Miscellaneous options > Dump DSP firmware), without which the game runs silent. `make docs-shots` renders the
guide's screenshots (`desktop/pd_docs_shots.c`: the library over made-up file names).

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
"PokeDaisy [DEV]"), with a small link (`ctr/devlink.c`, TCP 17498, a UDP beacon on 17499) that `tools/pdlink.py`
talks to: `find`, `ping`, `stat`, `log`, `put` / `push` / `get` / `ls` / `rm` (only under `sdmc:/pokedaisy-dev/`),
`press`, `tap`, `drag`, `shot`, `leave`, `restart`, `launch`. Start it once from the Homebrew Launcher (press Y
there for its netloader and send it with `3dslink`, or copy it to `sdmc:/3ds/`), then `tools/pdlink.py find`. Its port
17491 can take a while to open after Y, so retry `3dslink -a IP`. `push` puts the build in `sdmc:/3ds/` too, and games go
in `roms/` (a new one shows after `restart`). Never in a release build.

## The simulator

`make sim` builds `build/sim/pd_sim` (`desktop/sim/`): the real session (`host/`) and companion over the real mGBA,
with no 3DS code, from a script of `run`, `press`, `quick`, `tap`, `hit ID`, `hits`, `shot`, `wild`, `wait-state`,
`press-to-state`, `poke`, `peek`, `state`, `expect`, `aspect`, `screen`, `dark`, `theme`, `gen5` / `onetap` / `noinfo` /
`infofirst` (BATTLE STYLE, ONE-TAP MOVES, BATTLE INFO, BATTLE VIEW), `script HEX` (script bytes run by the game: a warp),
`teach SLOT INDEX MOVE` (the game's SetMonData), `flash` (FLASH picked), `idle detect|remove|ignore` (mGBA's busy-loop
skipping; the console uses detect), `timeframes N` (the session's and a bare runFrame's cost a frame; PD_SIM_THREADED_VIDEO=1
puts the renderer on a thread of its own, as on a New 3DS), `bench` (times the companion's redraw). `make sim-test` runs
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
make library-preview  # the library on real ROMs and saves (read, never written) -> build/library-shots/*.png
make test-save     # the save tools' core on real saves
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
| `core/` | Portable C, no platform code: game detection (`pd_game`, the app's `NativeConfig` addresses), the snapshot reader (`pd_snapshot`: party, battle, bag, money, place - ports `Gen3Mon.kt` / `readNativeTelemetry` / `readNativeBag`), the canvas (`pd_canvas`: pixel-stepped corners, layered OPTION frames, GBA-shadowed and wrapped text, drawn cursors), the companion (`pd_ui.c` frame + `pd_ui_party` / `_battle` (INFO, SUGGESTIONS, FOE TEAM) / `_bag` / `_map` / `_guide` / `_dex` / `_settings` / `_widgets`), the ROM readers (`pd_romart`: `RomArt`'s scan - every blob of the game in one pass; `pd_party_art`: the party's slot art and backdrop; `pd_map`: the region map and heads, `RegionMapModel`'s pick, `PlayerMapTile`; `pd_dex`: the dex tables and front pics; `pd_guide`: `GuideRom.kt`'s bosses, teams and wild tables), the party icons (`pd_icon`: `DecompIconSource`'s tables), the TRAINER CARD (`pd_card`: `readTrainerCard` + `TrainerCardArt`; `pd_ui_card`), the library (`pd_library*`: its games and pages, the touch screen, the top screen, the save tools, the card a session leaves), the save file (`pd_save`) and the settings file (`pd_settings`) |
| `core/*_gen.c` | Generated: names / moves / type chart / map sections / item text / gender ratios from the app's Kotlin tables (`tools/gen_tables.py`), the ROM art's fingerprints (map and card) and the cursor grids (`tools/gen_map_tables.py`), the guides and area data (`tools/gen_guide.py`), Pixel Operator as a 1-bit font plus drawn ♂ ♀ (`tools/gen_font.py`) |
| `ctr/` | The 3DS host: `main.c` (game loop, ndsp audio, HID, files, states), `gpu.c` (citro2d: the game and canvases written into textures in the GPU's tiled order, drawn as quads), `host.c` (the heap split, the ROM buffer, the log, what armGBA's 3DS code asks of its frontend), and the CMake build against the armGBA and mGBA submodules |
| `emu/` | armGBA behind mGBA's `struct mCore` (`pd_armgba*.c`), the dispatch to whichever core runs the game (`pd_dispatch.c`), no Game Boy side in armGBA (`gb_none.c`), and its build (`armgba.cmake`) |
| `host/` | What a running game needs on any host: the session (`pd_session`), USE / GIVE / TAKE and the PC's moves (`pd_items`), the Game Boy games' field actions through the Linux host's `linux/gb_field.c` |
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
- **3DS / 3DS XL / 2DS** (268 MHz, 128 MB): not tried with armGBA (no cores to spare there); mGBA alone was close to its limit
  there, and the companion's drawing shares the CPU. Expect slowdowns; PIXEL 1x
  is the cheapest screen mode, and fast-forward may not get past 1x.

## Next steps

- Run it on a New 3DS: measure the frame time per screen mode, check the texture orientation, the overlay's byte
  order, sound and save writes on hardware.
- The list under "What works": the Game Boy games' DEX and GUIDE first.
