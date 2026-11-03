# PokeDaisy Linux foundation

SDL2 host for the pinned mGBA library and the existing portable companion in
`3ds/core/`. The host is isolated here beside `3ds/`. It does not introduce
another emulator, companion decoder, game-address table or data generator.

It has EXPAND (below). It leaves out the downstream Polished adapter,
enhanced summaries, filters, automatic recovery and
firmware suspend/shutdown. Those implementations remain on the development
branches of keeganwhitacre/pokedaisy-brick. This branch is not an update of the installed
Brick development package. Use a separate data directory when evaluating it.

## Native build

Requires C11, CMake 3.18+, SDL2 development headers/library, POSIX threads,
libm/libdl, Python 3.12+ for tests. zlib is optional. On Ubuntu 24.04:

```
sudo apt-get install cmake build-essential libsdl2-dev python3
git submodule update --init third_party/mgba
cmake -S linux -B linux/build -DCMAKE_BUILD_TYPE=Release
cmake --build linux/build -j2
ctest --test-dir linux/build --output-on-failure
linux/build/pokedaisy-linux --data /your/test-directory /your/game.gba
```

Without a ROM argument, choose a `.gb`, `.gbc` or `.gba` from DATA/roms.
The host creates DATA/saves and DATA/states. It backs up an existing battery
save before opening the game and stops emulation on a latched save I/O failure.
State writes use a temporary file and rename. States include RTC, not battery
save restoration. These are not guarantees against sudden power loss.
No existing emulator save is discovered or imported automatically. Copy only
a backed-up raw battery save to DATA/saves/COMPLETE-ROM-FILENAME.sav when needed.
Two ROMs with the same filename share this path; keep such games in separate
DATA directories. DATA/settings.ini is host-local. Graceful quit flushes mGBA;
forced termination cannot do that. Keep using in-game saves.

## Controls and display

Keyboard: arrows, Z=A, X=B, Enter=Start, Backspace=Select, Q/E=L/R,
Tab=companion, F=quick menu, Escape=game menu. In the companion use
D-pad to focus, A to choose, B to go back and L/R to change tabs.
Save/load/quit confirmations are cancel-first. Fast-forward has no audio.

Y (F on a keyboard) opens the QUICK MENU over the running game. In retail
English FireRed, LeafGreen and Emerald: PC (the game's own PC, anywhere on the
field), FLY (once a Pokemon in the party knows the move: a list of the towns
you have been to, then the game's own flight), REPEL (the bag's; a list when it
holds more than one kind, dimmed with the steps left while one works) and BIKE
(GET OFF while riding: the bike goes into the game's SELECT slot for a moment,
yours is put back). Then WILD ENCOUNTERS on or off (saved in DATA/settings.ini;
GBA games whose switch `wild.c` finds: retail FireRed / LeafGreen / Emerald /
Ruby / Sapphire in every language and the CFRU hacks), SAVE STATE, LOAD STATE,
FF and COMPANION. The D-pad picks a tile, A runs it, B or Y closes it; the
game's buttons wait meanwhile. Each calls the game's own functions between
frames, at the addresses in `gamedata/` (the app's `FLY_*` / `FIELD_ITEMS_*`
records), and only where the ROM's code is where they say.

The HUD (companion SETTINGS > HUD, on by default) shows over the overworld, the
app's CORNERS layout in its glass look: the party bottom left (level, HP,
status), the region map around you top right, then REPEL STEPS and your Poke
Balls bottom right. Each part can be switched off. The app's HUD buttons (BAG,
PC, WILD) aren't there: without a touch screen the quick menu has them. It goes
away in text boxes, menus and battles, and comes back after a moment of
walking. With EXPAND it uses the screen's corners, else the game's picture.

SETTINGS > TAB BAR picks which tabs sit on the companion's bar (at most five);
the others open from the MORE chip next to fast-forward. SETTINGS scrolls where
its rows don't fit.

SDL GameController mappings take priority. The right face button acts as game
A; the bottom button acts as game B. A controller can be plugged in or pulled
out while the game runs: the first one SDL has a mapping for is used, from SDL's
own database, `DATA/gamecontrollerdb.txt` (the community file's format) or
`SDL_GAMECONTROLLERCONFIG` (which PortMaster sets for the device it runs on).
An unmapped device is named in the log with its GUID, to write it a mapping. It
is otherwise ignored unless you explicitly set `POCKET_RAW_TRIMUI=1`, which
enables the existing TrimUI raw button layout. Other controllers are not
claimed hardware support.

`POCKET_FULLSCREEN=1` uses SDL's current display mode; no Brick panel size is
assumed. Gameplay uses centered whole-pixel scaling. The companion's canvas
follows the screen too: the largest whole scale that keeps it at least 320x240
(the 3DS's, which its tabs are laid out for), so its text is about the same size
on every panel: 320x240 at 2x on 640x480, 426x240 at 3x on 1280x720, 341x256 on
1024x768, 360x360 on 720x720, 480x270 on 1920x1080. `POCKET_COMPANION_SCALE=N`
picks the scale instead. The 3DS screen-mode setting is hidden by
an optional host capability; fast-forward and state settings stay available.
Audio-device failure is logged and leaves silent gameplay available.
`POCKET_WINDOW=WxH` opens the window at a device's panel size instead (1280x720,
640x480, 720x720 ...), to try a layout on a desktop.

### EXPAND

The companion's SETTINGS has ASPECT: ORIGINAL, EXPAND or ZOOM OUT, with AUTO ZOOM
IN under it (saved in DATA/settings.ini). EXPAND is the Android app's: the app's
`pk_wide.c` / `pk_wide_gb.c` rebuild the overworld past the game's edges for every
game in `pk_wide_games.h` (GBA by ROM CRC, and the Game Boy games it knows). The
window follows the screen: the game keeps the whole-number scale its own picture
gets and the map fills the rest (320x180 at 4x on 1280x720, 256x192 on 1024x768,
240x240 on 720x720); ZOOM OUT is one scale less. Battles, menus, transitions and,
with AUTO ZOOM IN on, text boxes show the game's own picture. Other games read
ASPECT N/A.

The existing portable core provides the supported retail English Gen 3
companion. GB/GBC games, including Crystal and Polished Crystal, are emulation
only in this foundation. Do not mistake the downstream Polished companion or
Emerald Summary enhancements for features included here. Unsupported games
keep the existing core's unsupported-companion message.

## PortMaster (ROCKNIX, Knulli, muOS ...)

`python3 linux/package.py --portmaster <aarch64 binary> <out>` lays the build
out as a PortMaster port: `PokeDaisy.sh` (`linux/portmaster/`, PortMaster's usual
launcher: its `control.txt`, the device's controller mapping, `gptokeyb` for the
exit hotkey) beside a `pokedaisy/` folder with the binary, licenses and empty
`roms/`, `saves/` and `states/` once it has run. Copy both into the firmware's
`ports` folder and your games into `pokedaisy/roms/`. It fills the screen and
uses the firmware's own SDL2. CTest's `portmaster-launcher` runs the launcher
against a stand-in PortMaster (native builds). Not yet tried on a device.

## TrimUI cross-build and launcher

The checksum-pinned toolchain is AArch64 GCC 8.3 with the TrimUI SDK sysroot.
It is a build target, not an assertion of firmware/device compatibility.

```
python3 linux/setup_trimui_sdk.py /absolute/path/trimui-sdk
export NEXTUI_TOOLCHAIN=/absolute/path/trimui-sdk/cross/x86_64-aarch64-nextui-linux-gnu
cmake -S linux -B linux/build-arm -DCMAKE_TOOLCHAIN_FILE=trimui-toolchain.cmake -DCMAKE_BUILD_TYPE=Release
cmake --build linux/build-arm -j2
python3 linux/package.py linux/build-arm/pokedaisy-linux /new/package-output
```

The package uses firmware-provided SDL2 and C libraries, with no replacement
system libraries or root operations. The optional launch.sh sets fullscreen
and the explicit raw TrimUI fallback, passes quoted ROM arguments, and keeps
a bounded log. Put the extracted package in a separately named NextUI Tools
pak if your firmware supports that launcher convention. Direct command-line
launch works without NextUI; the binary never invokes firmware power helpers.

Earlier development builds were reported running by the user on the Brick
and by Lidor on the Smart Pro. That does not validate this newly separated
foundation on either device. Recheck controls, readable display, audio,
in-game saves, state load, exit and firmware power behavior on both devices.

## Tests and CI artifacts

CTest runs save mapping/error-injection tests and the actual SDL/mGBA host
on original GB/GBA diagnostic programs, and checks EXPAND's window size on
common handheld panels. Runtime tests cover companion pause,
menu/back, controller isolation, save/state roundtrips and a battery marker.
The diagnostics contain no commercial ROM or game data. The shared host-display
capability has a separate `make -C 3ds test-host-display` regression test.

The workflow builds/tests x86_64 and AArch64 under QEMU and uploads the executable,
license notices and source revision. The ARM SDK's offscreen SDL software driver
is used under QEMU. This is not physical hardware or remote CI validation by
itself. Private integration can use `linux-runtime-test ROM DATA CAPTURES [WxH,...]`, with
an isolated copy of a compatible raw battery save: it boots to the field, walks,
and checks that EXPAND and ZOOM OUT keep the game's own frame pixel for pixel in
the middle of the window at each size, then (for a companion game) opens PARTY
and a summary. Every companion tab at a canvas size, from the RAM fixtures:
`PD_PREVIEW_SIZE=426x240 3ds/build/pd_preview <fixture> <code> <out> <name> [ROM]`
(after `make -C 3ds preview`). No private ROM, save, capture
or extracted artwork is included in commits or install artifacts.

GPL-3.0, preserving Lidor Itzhari's attribution. Existing dependency/font notices
are in NOTICE and the packaged licenses. The normal build adds no third-party library; DS builds additionally use DSperate.

## Optional DS backend

Build with `-DPOCKET_DSPERATE=ON` to add DSperate emulation and a limited,
read-only Platinum USA rev1 companion. DS is disabled in normal builds.
See [dsperate/README.md](dsperate/README.md) for builds, controls, save paths,
architecture and current limits. This backend does not include DS expansion
or custom battle controls. DSperate is an additional opt-in dependency.
