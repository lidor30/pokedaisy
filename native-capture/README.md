# mgba_dump — headless test-fixture capture

A standalone C program linked directly against `libmgba` — no Android, no
JNI, no adb, no physical device or emulator. Loads a ROM (+ optional save),
runs a scripted boot sequence, and dumps EWRAM+IWRAM to the same
`ewram.bin`/`iwram.bin` format `PokeDaisyActivity`'s on-device capture
(`scripts/capture_fixture.sh`) produces — so it's a drop-in alternative data
source for the JVM decode-logic tests under `app/src/test/`.

Runs identically on a Mac (via Docker Desktop) or a Linux CI runner (e.g.
GitHub Actions) — it's just a Docker container + `libmgba-dev`, nothing
platform- or Android-specific. See `pokedaisy_jni.c` (the Android/JNI
bridge this mirrors) for why each `mCore` API call is there; this file's own
comments cover what's specific to headless capture.

## Usage

Normally you don't invoke this directly — use
`scripts/capture_fixture_headless.sh <key>`, which handles compiling it,
copying the ROM/save (never touches your real save file — always works on a
disposable copy, since the emulator can write to it), running the boot
sequence, and writing the result into
`app/src/test/resources/fixtures/<key>/`.

To run it by hand (e.g. to iterate on a new boot sequence):

```sh
docker run --rm -v "$(pwd):/work" -w /work pokedaisy-capture \
    -c "gcc -O2 -Wall -o mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"
docker run --rm -v "$(pwd):/work" -w /work pokedaisy-capture \
    -c "./mgba_dump rom.gba save.sav < boot/default.txt"
```

(Build the image once with `make capture-image` - the scripts also build it on
first use. Its `ENTRYPOINT` is `/bin/bash`, so always invoke it as
`-c "<full command>"`, not bare args.)

## stdin command language

One command per line:

| Command | Effect |
|---|---|
| `wait N` | Advance N frames with no keys held (unless `keys` holds some) |
| `keys KEYS` | Hold KEYS from now on, through every `wait`; `keys NONE` lets go. For frame-by-frame captures - `hold KEYS 1` runs two frames, since it lets go for one |
| `press KEYS` | Hold KEYS for one frame, release | `KEYS` is comma-separated: `A,B,SELECT,START,UP,DOWN,LEFT,RIGHT,L,R` |
| `hold KEYS N` | Hold KEYS for N frames, release |
| `dump DIR` | Write `DIR/ewram.bin` + `DIR/iwram.bin` (DIR must already exist); a Game Boy cart writes `wram.bin` (C000-DFFF as the bus has it) + `hram.bin`, and a Color cart also `wramx.bin`, all 8 WRAM banks |
| `shot FILE` | Write the current frame to `FILE` as a binary PPM (convert with PIL) — for checking what the game actually shows (e.g. party-menu icons) |
| `layers MASK` | Draw only these video layers from the next frame on (bit 0-3 = BG0-BG3, bit 4 = OBJ; `0x1F` = all) - e.g. `0x0E` for the overworld's map layers alone, no sprites or text |
| `vdump DIR` | Write `DIR/{vram,pal,oam,io}.bin` — VRAM, palette RAM, OAM and I/O registers, i.e. what the PPU draws from (how the CFRU party-slot graphics were traced back to the ROM) — plus `iolatch.bin`, the registers as last written: write-only ones (the BG scroll offsets) read back as open bus in `io.bin` |
| `poke8/poke16/poke32 ADDR VAL` | Write RAM (hex or decimal) — e.g. force a party mon fainted/asleep/an egg before opening the party menu |
| `peek32 ADDR` | Print a word to stdout |
| `poke8p PTR OFF VAL` / `peek8p PTR OFF` | Write / print the byte at `*(u32 *)PTR + OFF` — save-block fields (Emerald moves its save blocks on every load), e.g. `poke8p 0x030057D8 0x2951 0` clears a caught flag in TMT2 |
| `call ADDR [ARG0 .. ARG3]` | Run Thumb function ADDR with r0-r3 = the args (0 when left out) to its return, CPU state restored after (the app's `pk_call`), and print what it returned in r0 — e.g. `call 0x081dd164 297` starts FireRed's MUS_VS_TRAINER, `call 0x080413D4 0x02024284 13 0 0` is retail FireRed rev 1's ExecuteTableBasedItemEffect (a Potion on the lead). Interrupts stay masked, so nothing that waits on one returns (TrySavingData: save through the START menu instead) |
| `loadstate FILE` | Load an mGBA savestate (the app's `states/<crc>/resume` or a slot, pulled off the device) without its save data - to look at the exact spot a player reported, e.g. with `wideon` + `wideghosts` / `wideshot`. Only against the same ROM build |
| `savestate FILE` | Write an mGBA savestate (without its save data, like `loadstate`) - to play a new game in steps, each run picking up with `loadstate` where the last left off (how GS Chronicles' save was made) |
| `memcopy SRC DST LEN` | Copy LEN bytes of RAM - e.g. a one-Pokémon save's lead into the empty party slots (then `poke8` the party count) |
| `memsnap ADDR LEN` / `memrestore` | Copy LEN bytes (up to 256) and write them back later - how FieldItems' dry run on Emerald (an item's effect called on a party Pokémon, its 100 bytes put back) was checked |
| `park ADDR` | Park the main loop on a Thumb `b .` at ADDR once the VBlank IRQ is fully on (the app's `pkRenderPark`) |
| `gbpark ADDR` | Game Boy: park the main loop on a `jr @` at ADDR once interrupts are on (`pkRenderGbPark`); an ADDR in WRAM gets the `jr @` (18 FE) written there first - Red / Blue's bank 0 has none, so they park on 0xCD39 |
| `gbcall FN A C RET` | Game Boy: call FN with A / C until it returns to RET, e.g. Yellow's PlayMusic `gbcall 0x2211 240 8 0x1757` (`pkRenderGbCall`) |
| `gbloop N [crystal]` | Game Boy: run N frames and report where Gen 1's music channel state first repeats (`Gen1LoopWatch`); `crystal` watches Crystal's channel structs instead. `gbcall` also sets DE = C, for Crystal's PlayMusic (`gbcall 0x3B97 ID ID 0x28B2` after `gbpark 0x28B2`) |
| `gbfar BANK ADDR A BC DE HL` | Game Boy (all hex): call the function at BANK:ADDR between frames, the bank switched in and written to hROMBank (FF9D, the pokecrystal family's) so its own far calls come back, WRAM bank 1 at D000; run to its return, every register put back, and print A. Interrupts stay off, so nothing that waits for a frame returns - e.g. Prism's TryAddMonToParty (`gbfar 3c 4000 0 0 0 0` after `gbpoke`s of the species, level and wMonType) or ReceiveItem (`gbfar 00 12e5 0 0 0 D866`) |
| `gbinject TRAMP BANK ADDR [AT]` | Game Boy (all hex): make the game itself call BANK:ADDR from its frame wait (when the CPU halts, or reaches AT: DelayFrame's own `halt`, which mGBA can step over), interrupts on, through a 13-byte trampoline written at TRAMP (free RAM) that saves the registers and far-calls through `rst $08`; the call plays out in the following `wait`s - how Prism's trainer battle was started (`gbinject C002 25 614a 0f4e` after loadtrainer's writes) |
| `gbpoke BANK ADDR BYTE..` | Game Boy (all hex): write bytes at ADDR in WRAM bank BANK (D000-DFFF; C000-CFFF ignores the bank) |
| `gbpeek BANK ADDR N` | Game Boy (all hex): print N bytes at ADDR, from WRAM bank BANK for D000-DFFF (bank 0 = over the bus) |
| `gbcallat AT FN A B HL` | Game Boy (all hex): the app's `pk_gb_call_at` (`pk_gb_field.c`): run on until the CPU reaches AT, then call FN with A / B / HL and AT as its return address, so the game runs the call in the following `wait`s - Yellow's PC anywhere is `gbcallat 0248 3e84 0 18 66f4` (Bankswitch into the Pokémon Center PC tile's routine from the overworld loop) |
| `gbvdump DIR` | Game Boy: `DIR/gbvram.bin` (both Color banks), `gboam.bin` and `gbpal.bin` (the 32 BG then 32 OBJ Color palette entries) - `vdump` is the GBA's - how Prism's party icon colours were traced |
| `bgm` | Print the m4a BGM player: address, status, song header (what `FfMusicKey` reads) |
| `wav FILE N` | Run N frames and write what the APU played to `FILE` as a 16-bit stereo WAV — e.g. `call <m4aSongNumStart> 0`, `wait 20`, `call <m4aSongNumStart> 5`, `wav click.wav 45` renders SE_SELECT alone, the app's click sound. It also prints each frame whose audio is over 10% off one frame's worth (a Game Boy game turning its screen off or on restarts mGBA's frame) |
| `volume V` | mGBA's master volume from now on (0x100 = full; the app runs Game Boy games at 0x80, `PK_GB_VOLUME`): two `wav`s of the same frames at two volumes show where the mixer clips - half the volume should be half every sample |
| `gbaudio 0\|1` | A Game Boy game's `wav`: 1 (the default, built from the repo root) = resampled as the app does (`pk_gb_audio.c`), 0 = through mGBA 0.10.5's own blip_buf, whose pops were the Game Boy "crackle" (GitHub #269 / #327) |
| `cheat TYPE CODE` | Add a cheat (TYPE `auto` / `cb` / `gs` / `ar`, CODE's lines joined by `+`) and load every cheat added so far into the core the way the app does - it compiles in the app's own `app/src/main/cpp/pk_cheats.c`, so only when built with the repo mounted (see below). Prints the directive mGBA settled on and which lines it read |
| `cheatclear` | Take every cheat out again (the ROM goes back to the file's bytes) |
| `wideon MARGIN [CRC]` | Run the app's WIDE (`app/src/main/cpp/pk_wide.c`) after every frame from now on, MARGIN GBA px per side, for the game the ROM header names (retail FireRed rev 1 / Emerald). Repo builds only, like `cheat`. A Game Boy game it knows (English Yellow, Crystal: `pk_wide_gb.c`) works the same way in its 160x144 pixels: `widecheck`, `wideview`, `wideshot`, `widerec`, `wideinfo` (which also prints LCDC, the scroll and the window there) CRC (hex): look the game up by this CRC32 instead of the file's - retail's, for a randomized retail cart (`WideScreen.tablesCrc`) |
| `widecheck [all]` | Also rebuild the middle 240x160 every frame and compare it with the real frame pixel for pixel - how WIDE's rebuild was checked: it must match wherever it says the field checks out. Map frames only; `all` checks battle frames too, for a capture with `layers 0x18` (a battle's rebuild is BG3 + sprites). Not counted (and printed as such): a frame VRAM was written in while it was drawn, and one whose differences all lie in the partly shown edge tile column / row the field is scrolled by (the game's own stale ring) |
| `wideskip N` | From now on WIDE runs like fast-forward: only every Nth frame drawn, the others just noted (`pk_wide_skip`); 0 or 1 draws them all |
| `widestats` | Print WIDE's tally: frames, unverified ones (margins faded), torn sprite lists, centre checks exact / worst, and its time per frame (frames over 2 ms are also printed as they happen) |
| `wideview X Y W H` | From now on WIDE draws this window instead of `wideon`'s strips: the frame's pixels, its top-left (0, 0), up to 800x800 - ZOOM and the look-around (e.g. `wideview -200 -100 640 360`) |
| `wideinfo [lines]` | Print how the last frame went: mode (0 glow, 1 map, 2 battle), tilemap score (0..1000, -1 untried), torn sprite list, close (ZOOM steps back in), scene (0 field, 1 text, 2 overlay, 3 battle starting, 4 off); battle: how a battle frame's strips were drawn (`pk_wide_last_battle_ways`: 1 drawn, 2 BG3 repeats, 4 BG1 / BG2 at the edge, 8 / 16 BG3 broken past the left / right edge - the last four mirrored in); with `lines`, BG1's scroll as latched and per line as drawn |
| `wild ADDR KEEP` | WILD ENCOUNTERS as the app runs it on the game `wideon` picked: the switch at ADDR (`WildEncounters.kt` finds it) rolled before every frame, KEEP percent of encounters left (0 = none, 100 puts the switch back, -1 stops) |
| `fieldinfo [run]` | Print the app's field read of this frame (`pk_field_state`, behind SCENE SPEEDS' TEXT, AUTO-RUN and RUN INDOORS; `wideon` picks the game): known, locked (a script holds the field), battle, text (a text box up while locked), bike, surf, avatar (bike / surf from the game's own player state). With `run`, RUN INDOORS sets the map's allowRunning first |
| `wideghosts` | List the far-off objects (unspawned templates) the last snapshot holds: box in VMap pixels, palette tag, priority; `NO PALETTE` if neither a loaded slot nor the ROM's table has its colours |
| `wideshot FILE` | Write the last wide frame (`240 + 2 * MARGIN` x 160) as a PPM |
| `follower FILE` / `followon 0\|1` / `followinfo` | FOLLOWER: FILE is a Pokémon's six 32x32 frames + palette (3104 bytes); `followon 1` draws it behind the player (after `wideon`, which loads the field table); `followinfo` prints where it is and why a frame drew none. Repo builds only |
| `lookrefresh [PAL]` | PLAYER LOOK's refresh of the player's sprite from its record after `romunblock` / `romblocks`, PAL (64 hex digits) put over its palette slot |
| `tint R G B` | DAY AND NIGHT's tint from the next frame on (256 each = none): the map's BG palettes 0-12 and the sprites' as the renderer draws them, EXPAND's margins too (`pk_wide.c`'s `tintPalettes`). Repo builds only |
| `rawshot FILE` | The current frame as `W H` and a newline, then its 32-bit pixels as the core gives them (this libmgba leaves the top byte 0 for nearly every pixel: no layer tags) |
| `widecentre FILE` | Write the last frame's rebuild of the middle 240x160 (with `widecheck` on) as a PPM, to diff against `shot` |
| `widerec FILE N` | Run N frames (keys as `keys` holds them) recording every wide frame into FILE - `scripts/wide_gif.py FILE out.gif` makes an animation (or a contact sheet) of it |
| `rompatch AT EXPECT ADDR VALUE` | A game option's ROM patch as the app makes it (`pk_cheats_patch`, REUSABLE TMS: `ReusableTms.kt`; all hex, EXPECT and VALUE as byte strings): VALUE at ADDR, only where the ROM holds EXPECT from AT. Repo builds only, like `cheat`; `romcheck` then shows the file's bytes through the overlay and the swap |
| `romunpatch` | Take those patches out again |
| `romblocks FILE` | PLAYER LOOK's blocks the way the app puts them in (`pk_cheats_block`, all or none): FILE is `PlayerLook.Patch.toText()` (`block ADDR CRC HEX` lines, then `pal FROMHEX TOHEX` lines: the palettes already loaded, swapped by `pk_cheats_repalette`). `PlayerLookTest` writes one per hero and game to `app/build/player-look/`. Repo builds only |
| `romunblock` | Take PLAYER LOOK's blocks out again |
| `romcheck ROMFILE` | Compare the ROM with the file three ways: the raw bus, the bus through `pk_cheats_overlay` (what the Poller hashes) and swapped (what RetroAchievements hashes) |

Lines starting with `#`, and blank lines, are ignored.

## Gotchas already hit once (don't repeat)

- **Open the save file `O_RDWR`, not `O_RDONLY`.** The game writes to its own
  flash/SRAM during normal play; mGBA's flash-write path segfaults on the
  second in-game write if the backing VFile can't be written to. Always pass
  a **disposable copy** of a save, never the original — this tool WILL mutate
  it.
- **A save file's party/bag load as soon as "Continue" is selected**, well
  before any "quest log recap" dialogue has been clicked through — you don't
  need to mash through the recap pages to get valid data, just clear the
  title screen and pick Continue. `boot/default.txt` is deliberately more
  generous than that (extra presses to reach a stable "on the field" state
  for other purposes, e.g. screenshots), but if you only need the struct
  populated, a much shorter script suffices.
- **Extra button presses after actually reaching the field are not free.**
  If the save point happens to be near an NPC, shop counter, or PC, a spammed
  `press A` can trigger a real in-game interaction and change save state
  (seen once: a save's bag contents differed between two captures of nominally
  the same state, traced to exactly this). Prefer a boot script tuned to stop
  once the struct is populated over "press A a lot more, just in case."
- **mGBA's default logger is very noisy** (every unmapped I/O register, DMA,
  BIOS SWI call, straight to stdout/stderr) — this tool installs a no-op
  logger by default; pass `--verbose` (before the ROM path) to get the
  default logger back when debugging a new boot sequence.

## Cheats

`cheat` / `cheatclear` / `romcheck` (and `wideon` & co., `pk_wide.c`) need the app's `pk_cheats.c`, so build from the repo root
(the capture scripts mount `native-capture/` alone and get a build without them):

```sh
docker run --rm -v "$(pwd):/repo" -w /repo/native-capture pokedaisy-capture \
    -c "gcc -O2 -Wall -o mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"
```

Debian's libmgba is built with the debuggers on; the app's isn't (`USE_DEBUGGERS OFF`). To
test cheats against the app's own flags, build `third_party/mgba` on the host with the
`set(...)` lines from `app/src/main/cpp/CMakeLists.txt` and link this file against it - how
it was checked on 2026-10-08: on FireRed rev 1, a plain CodeBreaker write (`8203F000 1234`),
a hooked one (`100005FC 0007+8203F002 5678`, a hook on ReadKeys) and an encrypted GameShark
code (autodetected as `GSAv1`) all fired; `cheatclear` put the ROM back byte for byte.

