# nds_dump: headless melonDS for DS fixtures

`mgba_dump`'s twin for the DS games (see docs/DEVELOPMENT.md's "Nintendo DS"). It links
melonDS's core library from `third_party/melonds` (our patch applied at configure time) and the app's own DS sources
(`app/src/main/cpp/nds/`: the field read, the function caller, EXPAND, the STEADY music copy), so what it checks is the
code the app runs. No Android, no Docker: it builds on the Mac with CMake and zlib.

```bash
git submodule update --init third_party/melonds
cmake -S native-capture/nds_dump -B native-capture/nds_dump/build
cmake --build native-capture/nds_dump/build -j
native-capture/nds_dump/build/nds_dump ROM.nds SAVE.sav [--jit] < script.txt
```

It boots with melonDS's FreeBIOS and generated firmware (no BIOS or firmware files), reads `SAVE.sav` if it exists and
**writes every save the game makes back to it**: always give it a copy, never the player's save. `--jit` runs the app's
JIT settings (fast memory on, which melonDS turns off on macOS: test it on a device); without it, the interpreter. `NDS_BIOS_DIR=<folder>` boots with the real BIOS from that folder
(`bios7.bin`, `bios9.bin`) instead, to rule FreeBIOS in or out.

### The GPU renderer (DS RESOLUTION)

Built with `-DNDS_DUMP_GL=ON`, it also runs the app's GPU path (`nds/pk_nds_gl.cpp`: melonDS's OpenGL ES renderer, EXPAND's
one-picture list, our compositor) through ANGLE: point `ANGLE_DIR` at a folder holding `libEGL.dylib` and `libGLESv2.dylib`
(Chromium's, e.g. copied from an app that bundles them: their install names are `./lib*.dylib`, so run with
`DYLD_LIBRARY_PATH` set to that folder) and `GLES_INCLUDE` at copies of the NDK sysroot's `EGL`, `GLES2`, `GLES3` and
`KHR` header folders. ANGLE on Metal gives ES 3.0 with OES_draw_buffers_indexed, which is all the port needs.

```bash
cmake -S native-capture/nds_dump -B build-gl -DNDS_DUMP_GL=ON -DANGLE_DIR=<angle> -DGLES_INCLUDE=<headers>
cmake --build build-gl -j
DYLD_LIBRARY_PATH=<angle> build-gl/nds_dump ROM.nds SAVE.sav --jit < script.txt
```

## Script commands

One per line from stdin; `#` starts a comment. Numbers take `0x` hex. Keys are `A B SELECT START RIGHT LEFT UP DOWN R L
X Y`, joined with commas (`A,UP`), or `NONE`.

| Command | What it does |
| --- | --- |
| `wait N` | Runs N frames with the held keys. |
| `keys K` | Holds K from now on (`keys NONE` lets go). |
| `press K` | K for one frame, then one frame without. |
| `hold K N` | K for N frames, then one without. |
| `touch X Y [N]` | The stylus at (X, Y) on the bottom screen for N frames (default 2), then up. |
| `shot FILE` | Both screens, top over bottom, as a 256x384 PNG. |
| `shots PREFIX EVERY COUNT` | COUNT shots EVERY frames apart, `PREFIX_0000.png` ... |
| `dump DIR` | `mainram.bin` (4 MB at 0x02000000), `swram.bin`, `dtcm.bin`, `itcm.bin` into DIR. |
| `savestate FILE` / `loadstate FILE` | A melonDS state. |
| `savefile FILE` | The cart's save memory as it is now. |
| `peek8/16/32 ADDR`, `poke8/16/32 ADDR V` | The ARM9's bus. |
| `ards W W ...` | One Action Replay DS cheat (hex words, two a line) in melonDS's AREngine, replacing the ones before, as CHEATS does (`pk_nds_set_cheats`); `ards` alone takes it out. |
| `fieldinfo` | The field read (`pk_nds_field_state`) and the pointers behind it (Black, White and their sequels: the GameSystem's proc, its overlay, field map and event, engine A's BG tiles, the battle adapter's command). |
| `trace N` | N frames, each one's field bits, master brightness, DISPCNT and BG3's tiles. |
| `battlemenu N` | The battle controller's command (`pk_nds_battle_command`) each frame for N frames, as it changes. |
| `wide MX [MY]` | EXPAND with MX DS pixels left and right, MY above and below (0 = off). |
| `look DX DY` | From here on the window moves DX, DY DS pixels a frame before each frame, as the look-around moves it (`look 0 0` leaves it where it is); `ffmode` records that window. |
| `wideshot FILE`, `wideshots PREFIX EVERY COUNT` | The last wide frame(s) as PNG. |
| `widecheck [N]` | N frames, each checked where the slices overlap the game's own frame (must be 0 differing). |
| `widecount` | Polygons in each slice this frame. |
| `bgdump BG FILE` | Engine A's text BG whole, as the hardware reads it (clear = magenta). |
| `skipdraw N` | Fast-forward's frame skip: N = 1 skips drawing, as the app does on a frame nobody sees. |
| `threaded 1/0` | The 3D renderer on its own thread (on by default, as the app runs it) or on the emulation thread. A run with `ffmode` must give the same frames either way; a `3D: dropped ... stale line ...` line means a render was started that no frame waited for (it raced fast-forward on the Thor until 2026-11-09). |
| `ffmode G PREFIX` / `ffmode off` | From here on, the app's fast-forward pattern at G frames a refresh (EmulatorEngine's `pkNdsSetDraw`; 0 = every frame drawn), each frame drawn written as `PREFIX_<frame>.png` (and `_wide.png` with `wide` on). Run one script with `ffmode 0` and `ffmode 3` in two processes and compare the files frame by frame: they must be equal. |
| `ffgroup G` | Changes ffmode's G mid-run, as the app does when its frames a refresh change (SMART's 1x, FF off, the refresh pacing). |
| `ffguard 1/0` | `pk_nds_run_frame`'s guard on (default) / off: a frame whose 3D the one before skipped isn't drawn. Off shows the flash it prevents. |
| `musicon N` | STEADY's copy begun now and N of its frames run after each game frame, as the app runs it (0 stops it). |
| `bench N` | N frames flat out, then ms a frame. |
| `gpu N` | (`NDS_DUMP_GL` builds) DS RESOLUTION at N: the GPU renderer, or `gpu 1` back to the software one. `shot` then writes its 1x copies (a drawn frame late), `ffmode` the composed window. |
| `gpushot FILE` | (`NDS_DUMP_GL` builds) The GPU path's last window at its scale: the screen, or with `wide` on EXPAND's whole view. |
| `call FN [A0..A3]` | The game's function FN (Thumb: odd) from the field's main loop next frame; prints r0. |
| `action N [A0..A3]` | One of `pk_nds_call`'s jobs (1 = PC, 2 = FLY's checks, 3 = FLY; the Gen 5 games' FLY takes the party slot and the zone) and its results. |
| `script FILE` / `scriptgo FILE` | A field script (the game's bytecode) run as the PC job runs its own; `scriptgo` returns once it has started. |
| `itcm32 ADDR V` | A word into the hook's spare ITCM (an argument a call points at). |
| `watch LO HI [r\|w\|rw] [N]`, `watch off` | The game's own reads / writes of main RAM in [LO, HI) (at most N, default 20), each with the ARM9's PC, LR, r0-r5 and SP; `WATCH_STACK=N` in the environment adds N stack words (the callers' return addresses). The interpreter only: run without `--jit`. How Black's PC and FLY were traced. |
| `wildoff 1/0`, `battles` | WILD ENCOUNTERS OFF / back on, and the battles started since. |
| `music SECONDS FILE.wav` | STEADY's copy (`pk_nds_music`) begun from the game as it is, run alone; its audio, its cost a frame and the longest stall (`MUSIC_DEBUG=1` prints the ARM7 / IPC state and the sounding channels). |
| `steady G FILE.wav` / `steady off` | From here on, the app's STEADY as EmulatorEngine runs it at G game frames a refresh (a copy for each song once its player settles, taken again when it changes; `STEADY_DEBUG=1` prints the copy's state), its audio written at `steady off`; `steady 0` records the game's own audio instead. Run a script both ways and compare. |
| `dump7 ADDR LEN FILE` | LEN bytes of the ARM7's bus from ADDR (its WRAM and code). |
| `livewav SECONDS FILE.wav` | The game's own audio over that many frames. |
| `frame`, `echo TEXT` | The frame count; a line to stdout. |

## Fixtures

`app/src/test/resources/fixtures/platinum`, `platinum_battle`, `heartgold`, the `black*` and the `white*` ones (Black 2's and White 2's too) were made with it; each `README.txt` says
from which ROM, save and boot sequence. Their `mainram.bin` keeps only the ranges the readers use (zeros elsewhere), so
the files stay small in git.
