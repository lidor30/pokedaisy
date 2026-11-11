# The PokeDaisy GBA core over armGBA

`pokedaisy_gba_libretro.so` (CMake target `pokedaisy_gba_libretro`): the same companion as the mGBA-based core `pokedaisy_gbc_libretro.so` (party, bag, dex, map, guide, settings, HUD,
quick menu, EXPAND, FLY / PC / REPEL / BIKE, the wild-encounter switch, RUN INDOORS), with the Game Boy Advance run by
[armGBA](https://github.com/MagnominusGit/armGBA) (a recompiler) instead of mGBA. On the Brick (Cortex-A53, FireRed, EXPAND
on) a frame takes 4.2 ms against 7.1 ms. Game Boy and Game Boy Color games are not on this core: armGBA runs them on
Gambatte (GPL-2.0 only, not linkable into this GPL-3.0 work), so they stay on `pokedaisy_gbc_libretro.so`: a release ships the two cores side by side, one per job.

## How it is built
- `cmake -S libretro/armgba -B build -DCMAKE_TOOLCHAIN_FILE=libretro/aarch64.cmake`, target `pokedaisy_gba_libretro`, which writes `pokedaisy_gba_libretro.so`.
  Submodules: `third_party/armgba` (pinned, v1.5) and `third_party/zlib` (v1.3.1; the cross toolchain image has none, and
  zlib's CMake renames `zconf.h` inside its source tree, so `git checkout third_party/zlib` after a container build).
- `libretro/pd_libretro.c` reaches its emulator through `libretro/pd_backend.h`: mGBA's `libretro.c` (the default build,
  `libretro/CMakeLists.txt`), or armGBA's with `-DPD_ARMGBA` (this directory). armGBA's libretro glue is included with its entry points renamed and is never edited.
- The files written against mGBA's headers and shared with the Android app (`app/src/main/cpp/pk_wide.c`, `linux/fly.c`,
  `linux/field_items.c`) are compiled UNMODIFIED: `armgba/shim/` gives them mGBA's names (a header shim, forced in with
  `-include shim/pd_names.h`) over `pd_agba_view.[ch]`, a view that points at armGBA's arrays.
- EXPAND's per-line record needs a call as each visible line is drawn. armGBA draws a line from `video.c` at its H-blank
  with the machine in that line's state, as mGBA's `drawScanline` is called; the CMake project renames that call (and the
  render thread's) to `pdRenderScanline` at compile time. armGBA's dirty bits (`memoryChanged`, `vramChanged`) stand in for
  mGBA's write hooks.
- `pd_agba_call.c` is `pk_call.h` over armGBA's exact CPU (`src/cpu/step.c`): registers, pipeline and halt are saved and
  put back, the clock is not.
- `pd_agba_gb_stub.c` answers armGBA's Game Boy interface (`libretro/gb.h`) so no Gambatte is linked; `pd_agba_stubs.c` holds
  what is still mGBA-only (Game Boy field reads and EXPAND).

## Test builds (compile flags, never shipped)
- `-DPD_WIDECHECK`: the rebuilt EXPAND window's middle against the frame the emulator drew, per field frame (must be 0 px).
- `-DPD_CALLTEST` (+ `-DPD_CALLTEST_PC`): FLY's question and a full flight, or `pc_open`, through the real functions.
