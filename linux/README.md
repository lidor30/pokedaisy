# PokeDaisy Linux

`pokedaisy-linux` is an SDL2 frontend for the PokeDaisy libretro cores. It links no emulator: it loads the core for
the ROM from its own folder, `pokedaisy_gba_libretro.so` for `.gba` (armGBA, `libretro/armgba/`) and
`pokedaisy_gbc_libretro.so` for `.gb` / `.gbc` (mGBA, `libretro/`), and shows what the core draws. So the companion,
the HUD, the quick menu (PC, FLY, REPEL, BIKE, WILD ENCOUNTERS), EXPAND and every companion setting are the cores'
own, the same as on any other frontend, and a change to them reaches this executable with the next core build.

The host owns what a frontend owns: input, sound, pacing, the battery save and its backups, save states,
fast-forward, rewind, the LCD mask and the game list.

## Native build

Requires C11, CMake 3.18+, SDL2 development headers / library, libdl, Python 3 for tests. The cores build from
`libretro/` and `libretro/armgba/` (their READMEs); put them beside the binary, or point `POCKET_CORE_DIR` at them.
On Ubuntu 22.04:

```
sudo apt-get install cmake build-essential libsdl2-dev zlib1g-dev python3
git submodule update --init third_party/mgba third_party/armgba third_party/zlib
cmake -S libretro -B build/gbc -DCMAKE_BUILD_TYPE=Release && cmake --build build/gbc --target pokedaisy_gbc_libretro
cmake -S libretro/armgba -B build/gba -DCMAKE_BUILD_TYPE=Release && cmake --build build/gba --target pokedaisy_gba_libretro
mkdir -p build/cores && cp build/gbc/pokedaisy_gbc_libretro.so build/gba/pokedaisy_gba_libretro.so build/cores/
cmake -S linux -B linux/build -DCMAKE_BUILD_TYPE=Release -DPOCKET_TEST_CORES=$PWD/build/cores
cmake --build linux/build -j2
ctest --test-dir linux/build --output-on-failure
POCKET_CORE_DIR=build/cores linux/build/pokedaisy-linux --data /your/test-directory /your/game.gba
```

Without a ROM argument, choose a `.gb`, `.gbc` or `.gba` from DATA/roms. The host creates DATA/saves and
DATA/states. Before a game opens its battery save is backed up into DATA/saves/pokedaisy-backups (a new copy only
when it changed, the newest 10 kept; with `--save FILE`, DATA/backups); a save that can't be backed up keeps the game
closed. The save is DATA/saves/COMPLETE-ROM-FILENAME.sav, the raw battery save, read into the core when the game
opens and written back (to a temporary file, then renamed) within two seconds of the game changing it and on quit.
States are DATA/states/ROM.ss on the mGBA core and ROM.armgba.ss on the armGBA core: the two can't read each
other's. The companion's settings are the core's, in DATA/saves/pokedaisy/settings.ini (an older DATA/settings.ini is
copied there once). The host's own are DATA/host.ini.

## Controls and display

Keyboard: arrows, Z=A, X=B, Enter=Start, Backspace=Select, Q/E=L/R, 1/2=L2/R2, Tab=X, F=Y, Space=fast-forward
(held), R=rewind (held), F2=save state, F4=load state, Escape=the host menu. The core's own controls work as on any
frontend: X opens the companion, Y the quick menu (each can be rebound in the companion's SETTINGS).

START+SELECT (or the controller's Guide button, or Escape) opens the host menu over the game: RESUME, SAVE STATE,
LOAD STATE, FAST FORWARD (on / off), FF SPEED (MAX, or 2x to 8x), REWIND (keeps the last ~20 s while on, in a
32 MB budget: `POCKET_REWIND_MB`), LCD FILTER and QUIT (QUIT TO GAME LIST when the game came from the list). The game
pauses while it is open. Fast-forward plays its sound sped up. A touch screen or the mouse reaches the core's
companion and HUD.

SDL GameController mappings take priority. The right face button acts as game A; the bottom button acts as game B.
A controller can be plugged in or pulled out while the game runs: the first one SDL has a mapping for is used, from
SDL's own database, `DATA/gamecontrollerdb.txt` (the community file's format) or `SDL_GAMECONTROLLERCONFIG` (which
PortMaster sets for the device it runs on). An unmapped device is named in the log with its GUID, to write it a
mapping. It is otherwise ignored unless you explicitly set `POCKET_RAW_TRIMUI=1`, which enables the TrimUI raw button
layout. plorpOS's hotkeys file (`POCKET_HOTKEYS_FILE`: ff, rewind, savestate, loadstate) and the Brick's Fn keys
(`POCKET_FN_KEYS`) still work.

`POCKET_FULLSCREEN=1` uses SDL's current display mode. The host tells the core the screen's size (its
`pokedaisy_panel` option), so the core picks its canvas for that screen (EXPAND at the game's whole scale, the
companion at the largest whole scale keeping 320x240), and the host shows the frame at the largest whole scale that
fits, centred. `POCKET_WINDOW=WxH` opens the window at a device's panel size instead (1280x800, 1280x720, 640x480,
720x720 ...), to try a layout on a desktop. The screen paces the game when it runs at 58-62 Hz and presents wait for
it, else a timer does; the sound is resampled a little faster or slower to keep up. Audio-device failure is logged
and leaves silent gameplay available; the device is reopened when it comes back. `POCKET_PERF=1` logs frame timings
every 5 s.

## PortMaster (ROCKNIX, Knulli, muOS ...)

`python3 linux/package.py --portmaster <aarch64 binary> <out>` lays the build
out as a PortMaster port: `PokeDaisy.sh` (`linux/portmaster/`, PortMaster's usual
launcher: its `control.txt`, the device's controller mapping, `gptokeyb` for the
exit hotkey) beside a `pokedaisy/` folder with the binary, the cores (`--core`), licenses and empty
`roms/`, `saves/` and `states/` once it has run. Copy both into the firmware's
`ports` folder and your games into `pokedaisy/roms/`. It fills the screen and
uses the firmware's own SDL2. CTest's `portmaster-launcher` runs the launcher
against a stand-in PortMaster (native builds). The package has run FireRed headless on a ROCKNIX handheld
(GKD 350H); a launch from a firmware's Ports menu is still to be reported.

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
python3 linux/package.py linux/build-arm/pokedaisy-linux /new/package-output \
  --core build/gba-arm/pokedaisy_gba_libretro.so --core build/gbc-arm/pokedaisy_gbc_libretro.so
```

Both cores load on glibc 2.33 (the Brick) and newer; the armGBA one on any aarch64 glibc from 2.17
(`libretro/armgba/glibc_compat.*`). CI fails a core that needs more than 2.33.

The package uses firmware-provided SDL2 and C libraries, with no replacement
system libraries or root operations. The optional launch.sh sets fullscreen
and the explicit raw TrimUI fallback, passes quoted ROM arguments, and keeps
a bounded log. Put the extracted package in a separately named NextUI Tools
pak if your firmware supports that launcher convention. Direct command-line
launch works without NextUI; the binary never invokes firmware power helpers.

On the Brick (Cortex-A53) the packaged cores run FireRed at 4.2 ms a frame (armGBA) and 7.3 ms (mGBA). The
executable itself has not run on a TrimUI device yet: TrimUI's SDL2 has no headless driver to test it with. Recheck
controls, readable display, audio, in-game saves, state load, exit and firmware power behavior there.

## Tests and CI artifacts

CTest runs the save-mapping and error-injection tests, the package and PortMaster launcher checks and, with
`POCKET_TEST_CORES` set, `linux-host-test`: the real binary, headless, on original GB / GBA diagnostic programs
(`make_diagnostic.py`) over both cores. The GB one writes a battery-save marker, which has to reach DATA/saves and be
backed up on the next run; the GBA one fills its screen red, which has to reach the window; the game list draws, and
a ROM no core plays is refused. The diagnostics contain no commercial ROM or game data. The companion, HUD, quick menu
and EXPAND are the cores' and are tested there (`libretro/test/`, `libretro/armgba/README.md`).

The workflow builds and tests x86_64 (Ubuntu 22.04, so the package needs glibc 2.35 at most) and AArch64 (the host
under QEMU), builds both cores for each, and uploads the packages with the cores, license notices and source
revisions. No private ROM, save, capture or extracted artwork is included in commits or install artifacts.

GPL-3.0, preserving Lidor Itzhari's attribution. Existing dependency/font notices
are in NOTICE and the packaged licenses (mGBA, and armGBA and zlib for the GBA core). DS builds additionally use DSperate.

## Optional DS backend

Build with `-DPOCKET_DSPERATE=ON` to add DSperate emulation and a limited,
read-only Platinum USA rev1 companion for `.nds` games (the host hands them to `dsperate/frontend.cpp`; the DS libretro
core is not used here). DS is disabled in normal builds.
See [dsperate/README.md](dsperate/README.md) for builds, controls, save paths,
architecture and current limits. This backend does not include DS expansion
or custom battle controls. DSperate is an additional opt-in dependency.
