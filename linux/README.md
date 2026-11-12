# PokeDaisy Linux

`pokedaisy-linux` is an SDL2 frontend for the PokeDaisy libretro cores. It links no emulator: it loads the core for
the ROM from its own folder. It uses `pokedaisy_gba_libretro.so` for `.gba` (armGBA, `libretro/armgba/`),
`pokedaisy_gbc_libretro.so` for `.gb` / `.gbc` (mGBA, `libretro/`), or `pokedaisy_ds_libretro.so` for `.nds`
(DSperate, `linux/libretro-ds/`), and shows what the core draws. So the companion,
the HUD, the quick menu (PC, FLY, TAKE OFF, FLASH, REPEL, BIKE, WILD ENCOUNTERS), EXPAND and every companion setting are the cores'
own, the same as on any other frontend, and a change to them reaches this executable with the next core build.

The host owns what a frontend owns: input, sound, pacing, the battery save and its backups, save states,
fast-forward, rewind, the LCD mask and the game list.

The step-by-step install guide for players is [INSTALL.md](INSTALL.md), in every language the app has: [日本語](INSTALL.ja.md),
[한국어](INSTALL.ko.md), [Français](INSTALL.fr.md), [Deutsch](INSTALL.de.md), [Italiano](INSTALL.it.md),
[Español](INSTALL.es.md), [Português (Brasil)](INSTALL.pt-br.md), [Português (Portugal)](INSTALL.pt.md). It's made from the
manual's `docs/manual/linux-install.md` and its translations (also on pokedaisy.com/docs/linux-install/) by
`website/scripts/install-guides.mjs`: edit the manual, then run that.

## Native build

Requires C11, CMake 3.18+, SDL2 development headers / library, libdl, Python 3 for tests. Put the three cores beside the binary, or point `POCKET_CORE_DIR` at them.
On Ubuntu 22.04:

```
sudo apt-get install cmake build-essential libsdl2-dev zlib1g-dev python3
git submodule update --init third_party/mgba third_party/armgba third_party/zlib
git submodule update --init --recursive third_party/dsperate-lr
cmake -S libretro -B build/gbc -DCMAKE_BUILD_TYPE=Release && cmake --build build/gbc --target pokedaisy_gbc_libretro
cmake -S libretro/armgba -B build/gba -DCMAKE_BUILD_TYPE=Release && cmake --build build/gba --target pokedaisy_gba_libretro
cmake -S linux/libretro-ds -B build/ds -DCMAKE_BUILD_TYPE=Release && cmake --build build/ds --target pokedaisy_ds_libretro
mkdir -p build/cores
cp build/gbc/pokedaisy_gbc_libretro.so build/gba/pokedaisy_gba_libretro.so build/ds/pokedaisy_ds_libretro.so build/cores/
cmake -S linux -B linux/build -DCMAKE_BUILD_TYPE=Release -DPOCKET_TEST_CORES=$PWD/build/cores
cmake --build linux/build -j2
ctest --test-dir linux/build --output-on-failure
POCKET_CORE_DIR=build/cores linux/build/pokedaisy-linux --data /your/test-directory /your/game.gba
```

Without a ROM argument, choose a `.gb`, `.gbc`, `.gba` or `.nds` from DATA/roms and the folders `POCKET_ROM_DIRS` names
(colon-separated; the PortMaster launcher passes the firmware's `gba`, `gb`, `gbc` and `nds` folders where they exist). The host creates DATA/saves and
DATA/states. Before a game opens its battery save is backed up into DATA/saves/pokedaisy-backups (a new copy only
when it changed, the newest 10 kept; with `--save FILE`, DATA/backups); a save that can't be backed up keeps the game
closed. The save is DATA/saves/COMPLETE-ROM-FILENAME.sav, the raw battery save, read into the core when the game
opens and written back (to a temporary file, then renamed) within two seconds of the game changing it and on quit.
States are DATA/states/ROM.ss on the GB/GBC and DS cores and ROM.armgba.ss on the armGBA core: the two can't read each
other's. The companion's settings are the core's, in DATA/saves/pokedaisy/settings.ini (an older DATA/settings.ini is
copied there once). The host's own are DATA/host.ini.

## Controls and display

Keyboard: arrows, Z=A, X=B, Enter=Start, Backspace=Select, Q/E=L/R, 1/2=L2/R2, Tab=X, F=Y, Space=fast-forward
(held), R=rewind (held), F2=save state, F4=load state, Escape=the host menu. The core's own controls work as on any
frontend: X opens the companion, Y the quick menu (each can be rebound in the companion's SETTINGS).

START+SELECT (or the controller's Guide / Menu button, or Escape) opens the host menu over the game: RESUME, SAVE
STATE, LOAD STATE, HOTKEYS, REWIND (keeps the last ~20 s while on, in a 32 MB budget: `POCKET_REWIND_MB`), FF SPEED
(MAX, or 2x to 8x), LCD FILTER, A/B BUTTONS (NORMAL / SWAPPED: devices disagree on which physical button SDL calls A;
SWAPPED trades A with B and X with Y on controllers) and QUIT (QUIT TO GAME LIST when the game came from the list).
Fast-forward is the FAST FORWARD hotkey's, while it is held. `POCKET_MENU_COMBO=off` reserves START+SELECT for PortMaster's exit without opening the host menu. The game pauses while it is open.

Hold SELECT and press R2 to fast-forward, or L2 to rewind when enabled. A plain SELECT tap reaches the game
on release. In HOTKEYS you can rebind FAST FORWARD, REWIND, SAVE STATE and LOAD STATE (save and load start off).
Pick a row, then press a shoulder, stick click, X, Y or a reported paddle. B cancels and SELECT clears the action.
The MODIFIER row is shared by all cores. It accepts START and the same buttons as action bindings. Press SELECT
in that row to choose SELECT or toggle the modifier off. With the modifier off, hotkeys use single buttons.
Bindings are stored in DATA/host.ini. Fast-forward plays its sound sped up.

SDL GameController mappings take priority. The right face button acts as game A; the bottom button acts as game B.
A controller can be plugged in or pulled out while the game runs: the first one SDL has a mapping for is used, from
SDL's own database, `DATA/gamecontrollerdb.txt` (the community file's format) or `SDL_GAMECONTROLLERCONFIG` (which
PortMaster sets for the device it runs on). An unmapped device is named in the log with its GUID, to write it a
mapping. It is otherwise ignored unless you explicitly set `POCKET_RAW_TRIMUI=1`, which enables the TrimUI raw button
layout.

`POCKET_FULLSCREEN=1` uses SDL's current display mode. The host tells the core the screen's size (its
`pokedaisy_panel` option), so the core picks its canvas for that screen (EXPAND at the game's whole scale, the
companion at the largest whole scale keeping 320x240), and the host shows the frame at the largest whole scale that
fits, centred. `POCKET_WINDOW=WxH` opens the window at a device's panel size instead (1280x800, 1280x720, 640x480,
720x720 ...), to try a layout on a desktop. The screen paces the game when it runs at 58-62 Hz and presents wait for
it, else a timer does; the sound is resampled a little faster or slower to keep up. Audio-device failure is logged
and leaves silent gameplay available; the device is reopened when it comes back. `POCKET_PERF=1` logs frame timings
every 5 s. `POCKET_KEYS="TICK:KEY[:HOLD],..."` presses keys by loop tick, for tests (`test_host.py`).

## PortMaster (ROCKNIX, Knulli, muOS ...)

`python3 linux/package.py --portmaster <aarch64 binary> <out>` lays the build
out as a PortMaster port: `PokeDaisy.sh` (`linux/portmaster/`, PortMaster's usual
launcher: its `control.txt`, the device's controller mapping, `gptokeyb` for the
exit hotkey) beside a `pokedaisy/` folder with the binary, the cores (`--core`), licenses and empty
`roms/`, `saves/` and `states/` once it has run. Copy both into the firmware's
`ports` folder and your games into `pokedaisy/roms/`. It fills the screen and
uses the firmware's own SDL2. CTest's `portmaster-launcher` runs the launcher
against a stand-in PortMaster (native builds). The package has been played from the Ports menu of a ROCKNIX
handheld (GKD 350H): the firmware's ROM folders in the game list, the host menu on MENU, A/B BUTTONS.

## TrimUI cross-build and launcher

The checksum-pinned toolchain is AArch64 GCC 8.3 with the TrimUI SDK sysroot.
It is a build target, not an assertion of firmware/device compatibility.

```
python3 linux/setup_trimui_sdk.py /absolute/path/trimui-sdk
export NEXTUI_TOOLCHAIN=/absolute/path/trimui-sdk/cross/x86_64-aarch64-nextui-linux-gnu
cmake -S linux -B linux/build-arm -DCMAKE_TOOLCHAIN_FILE=trimui-toolchain.cmake -DCMAKE_BUILD_TYPE=Release
cmake --build linux/build-arm -j2
cmake -S libretro -B build/gbc-arm -DCMAKE_TOOLCHAIN_FILE=$PWD/linux/trimui-toolchain.cmake -DCMAKE_BUILD_TYPE=Release
cmake --build build/gbc-arm --target pokedaisy_gbc_libretro
# armGBA needs a newer GCC than the TrimUI toolchain's 8.3: Ubuntu 22.04's gcc-aarch64-linux-gnu, as CI does.
cmake -S libretro/armgba -B build/gba-arm -DCMAKE_TOOLCHAIN_FILE=$PWD/libretro/aarch64.cmake -DCMAKE_BUILD_TYPE=Release
cmake --build build/gba-arm --target pokedaisy_gba_libretro
cmake -S linux/libretro-ds -B build/ds-arm -DCMAKE_TOOLCHAIN_FILE=$PWD/linux/trimui-toolchain.cmake -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
cmake --build build/ds-arm --target pokedaisy_ds_libretro
python3 linux/package.py linux/build-arm/pokedaisy-linux /new/package-output \
  --core build/gba-arm/pokedaisy_gba_libretro.so --core build/gbc-arm/pokedaisy_gbc_libretro.so --core build/ds-arm/pokedaisy_ds_libretro.so
```

The three cores target glibc 2.33 (the Brick) and newer; the armGBA one on any aarch64 glibc from 2.17
(`libretro/armgba/glibc_compat.*`). CI fails a core that needs more than 2.33.

The package uses firmware-provided SDL2 and C libraries, with no replacement
system libraries or root operations. The optional launch.sh sets fullscreen
and the explicit raw TrimUI fallback, passes quoted ROM arguments, and keeps
a bounded log. Put the extracted package in a separately named NextUI Tools
pak if your firmware supports that launcher convention. Direct command-line
launch works without NextUI; the binary never invokes firmware power helpers.

On the Brick (Cortex-A53) the packaged cores run FireRed at 4.2 ms a frame (armGBA) and 7.3 ms (mGBA). The
executable has run under QEMU with SDL's offscreen driver. Physical TrimUI testing still needs to check
controls, readable display, audio, in-game saves, state load, exit and firmware power behavior there.

## Tests and CI artifacts

CTest runs the save-mapping and error-injection tests, the package and PortMaster launcher checks and, with
`POCKET_TEST_CORES` set, `linux-host-test`: the real binary, headless, on original GB / GBA / DS diagnostic programs
(`make_diagnostic.py` and `dsperate/make_diagnostic.py`). The GB one writes a battery-save marker, which has to reach DATA/saves and be
backed up on the next run. The GBA and DS diagnostics fill their screens red and green. The test launches DS from
the game list, saves a state, and checks that L2 opens the Companion. Unsupported ROM extensions are refused. The diagnostics contain no commercial ROM or game data. The companion, HUD, quick menu
and EXPAND are the cores' and are tested there (`libretro/test/`, `libretro/armgba/README.md`).

The workflow builds and tests x86_64 (Ubuntu 22.04, so the package needs glibc 2.35 at most) and AArch64 (the host
under QEMU), builds all three cores for each, and uploads the packages with the cores, license notices and source
revisions. No private ROM, save, capture or extracted artwork is included in commits or install artifacts.

GPL-3.0, preserving Lidor Itzhari's attribution. Existing dependency/font notices
are in NOTICE and the packaged licenses (mGBA, and armGBA and zlib for the GBA core). DS builds additionally use DSperate.

## Nintendo DS

The bundled DS core starts in its One Screen layout. L2 opens the Companion and R2 switches screens by default.
Hold L2 to open Quick Menu. With SELECT as the host modifier, SELECT + Y toggles stylus mode and SELECT + D-pad/A
moves or taps the stylus. DS host actions can be rebound separately in HOTKEYS (SELECT + R2 / L2 by default; with
the modifier off they move to R3 / L3, since bare R2 / L2 belong to the core). Companion Settings → HUD opens the shared HUD
page (on or off, LOOK, SIZE and each part), as on the GB and GBA cores. MINIMAL HUD hides the party cards.

To bring over an existing DS battery save, copy its raw save data to `DATA/saves/<game>.nds.sav` (including the
ROM extension in the name), or pass its path with `--save`.

The older opt-in DSperate experiment is still available with `-DPOCKET_DSPERATE=ON`. See
[dsperate/README.md](dsperate/README.md) for that backend's build and save paths.
