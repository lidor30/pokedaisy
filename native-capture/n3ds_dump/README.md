# n3ds_dump: headless Azahar for 3DS fixtures

`nds_dump`'s twin for 3DS carts (docs/3DS_GAMES_PLAN.md). It links Azahar's core libraries from `third_party/azahar`
(our patch applied at configure time, the key blob forced off as in the app) with the software renderer, so it needs no
GPU, Android or Docker: it builds on the Mac with CMake 3.25+ (the Android SDK's `cmake/3.31.6` has Ninja with it).

```bash
git submodule update --init --recursive third_party/azahar
SDK=~/Library/Android/sdk/cmake/3.31.6/bin
$SDK/cmake -S native-capture/n3ds_dump -B native-capture/n3ds_dump/build -G Ninja -DCMAKE_MAKE_PROGRAM=$SDK/ninja
$SDK/ninja -C native-capture/n3ds_dump/build n3ds_dump
native-capture/n3ds_dump/build/n3ds_dump CART.3ds USERDIR [--save MAIN] [--new3ds] < script.txt
```

Only decrypted carts boot (no keys, ever). `USERDIR` is Azahar's user folder for the run (its emulated SD card, NAND and
config): give it a scratch folder. `--save MAIN` copies a save (a Checkpoint / JKSM backup's `main`) into the cart's save
folder there first; the game then writes its saves to that copy, never to `MAIN`. The clock and the start-up ticks are
fixed, so a script plays the same way each run.

## Script commands

One per line from stdin; `#` starts a comment. Numbers take `0x` hex. Keys are `A B X Y UP DOWN LEFT RIGHT L R START
SELECT ZL ZR HOME`, joined with commas (`A,UP`), or `NONE`. A frame is one the software renderer finished.

| Command | What it does |
| --- | --- |
| `wait N` | Runs N frames with the held keys. |
| `keys K` | Holds K from now on (`keys NONE` lets go). |
| `press K` / `hold K N` | K for one frame (or N), then one frame without. |
| `touch X Y [N]` | The stylus at (X, Y) on the bottom screen (320x240) for N frames (default 2), then up. |
| `shot FILE` / `shots PREFIX EVERY COUNT` | Top screen over the bottom one, as a 400x480 PNG. |
| `regions` / `dump DIR` | The game process's mapped memory (base, size, permissions); `dump` writes each as `<base>.bin`. |
| `peek8/16/32 ADDR`, `poke8/16/32 ADDR V` | The game process's memory. |
| `savestate FILE` / `loadstate FILE` | An Azahar state. |
| `bench N` | N frames flat out, then ms a frame. |
| `modules` | The loaded CRO modules, as the app reads them (`DllField DllUSC02 ...`). |
| `waitmod M [N]` / `waitnomod M [N]` | Frames until module M is loaded (or gone), at most N (3000). |
| `pressuntil K M [N]` | K for 2 frames every 60 until module M is loaded, at most N frames. |
| `frames` | Frames run so far. |

`N3DS_LOG` sets Azahar's log filter (default `*:Warning`, e.g. `N3DS_LOG='*:Info'`).

A module loads a few seconds before its screen shows (the field's with the start menu still up): wait ~500 frames after
`pressuntil A DllPssMessageWindow` before walking. `savestate` fails on Pokemon X (a boost archive class isn't
registered), so every run boots. With the user's save the field opens on a "changed console" message on the touch
screen; after a touch the pictures stopped changing once (2026-11-15), not looked into.
