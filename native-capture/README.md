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
| `keys KEYS` | Hold KEYS from now on, through every `wait`; `keys NONE` lets go. For frame-by-frame captures - `hold KEYS (`pk_coop_battle_starting`): `starting 0|1` runs two frames, since it lets go for one |
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
| `gbloop N [crystal\|gold\|polished]` | Game Boy: run N frames and report where Gen 1's music channel state first repeats (`Gen1LoopWatch`); `crystal` watches Crystal's channel structs instead (Crystal Clear's too), `gold` Gold / Silver's, `polished` Polished Crystal's. `gbcall` also sets DE = C, for Crystal's PlayMusic (`gbcall 0x3B97 ID ID 0x28B2` after `gbpark 0x28B2`) |
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
| `widerow BASECRC V,V,...` | Before `wideon`: WIDE and the field reads know this ROM (by the file's CRC32) by the given row, on the retail build BASECRC's engine, as the app does for a hack `RetailEngine.kt` proves (`pk_wide_add_derived`). `scripts/gen_retail_engine.py --row ROM` prints the line. Repo builds only |
| `wideview X Y W H` | From now on WIDE draws this window instead of `wideon`'s strips: the frame's pixels, its top-left (0, 0), up to 800x800 - ZOOM and the look-around (e.g. `wideview -200 -100 640 360`) |
| `wideinfo [lines]` | Print how the last frame went: mode (0 glow, 1 map, 2 battle), tilemap score (0..1000, -1 untried), torn sprite list, close (ZOOM steps back in), scene (0 field, 1 text, 2 overlay, 3 battle starting, 4 off); battle: how a battle frame's strips were drawn (`pk_wide_last_battle_ways`: 1 drawn, 2 BG3 repeats, 4 BG1 / BG2 at the edge, 8 / 16 BG3 broken past the left / right edge - the last four mirrored in); with `lines`, BG1's scroll as latched and per line as drawn |
| `wild ADDR KEEP` | WILD ENCOUNTERS as the app runs it on the game `wideon` picked: the switch at ADDR (`WildEncounters.kt` finds it) rolled before every frame, KEEP percent of encounters left (0 = none, 100 puts the switch back, -1 stops) |
| `fieldinfo [run]` | Print the app's field read of this frame (`pk_field_state`, behind SCENE SPEEDS' TEXT, AUTO-RUN and RUN INDOORS; `wideon` picks the game): known, locked (a script holds the field), battle, text (a text box up while locked), bike, surf, avatar (bike / surf from the game's own player state). With `run`, RUN INDOORS sets the map's allowRunning first |
| `wideghosts` | List the far-off objects (unspawned templates) the last snapshot holds: box in VMap pixels, palette tag, priority; `NO PALETTE` if neither a loaded slot nor the ROM's table has its colours |
| `wideshot FILE` | Write the last wide frame (`240 + 2 * MARGIN` x 160) as a PPM |
| `flighton W...` | FREE FLIGHT (`app/src/main/cpp/pk_flight.c`): configure it with the game's words in pk_flight.h's order (`FlightFns.words()`; retail FireRed rev 1's are `FLIGHT_FIRERED_REV1` in `gamedata/gen3/records/firered.toml`), ticked after every frame from then on. Repo builds only |
| `flight takeoff\|land` | Take off (waits up to 60 frames for a pk_call-safe one: `flight: takeoff R`, 0 = up, -3 not now, -4 not on foot) / ask to land (the nearest tile a player can stand on). While up, `keys` steer: the D-pad flies, `A` held faster, `B` lands |
| `flightroute DUL R...` | The scenic FLY's route as letters (D down, U up, L left, R right; `ScenicFlyTest` prints them as `ROUTE <mapsec> ...`), flown at the faster speed once up, then a landing |
| `flightarea G.N ...` | FLIGHT AREA: the maps (decimal group.num) the flight may cross into; with none, anywhere again |
| `rideset FILE W` / `rideon 0\|1` / `rideinfo` | RIDE ON's vehicle (`RideOn.Sprite`: three 1024-byte views in OBJ tile order, 32 x 64 or 64 x 32 by W, then its 32-byte palette), drawn behind the player while they're on a bike |
| `flightinfo` | `flight: state S phase P bird B lift L at X,Y screen X,Y anchor X,Y marked M land X,Y route DONE/LEN locked K`: S 0 idle, 1 taking off, 2 flying, 3 landing, 4 the bird leaving |
| `follower FILE` / `followon 0\|(`pk_coop_battle_starting`): `starting 0|1` / `followinfo` | FOLLOWER: FILE is a Pokémon's six 32x32 frames + palette (3104 bytes); `followon 1` draws it behind the player (after `wideon`, which loads the field table); `followinfo` prints where it is and why a frame drew none. Repo builds only |
| `partnerself` | Co-op: print this frame's `pk_partner_self`, what the app sends about the player: `valid`, `map G.N`, `fine X,Y` (object-event tile x 16 plus the pixels of the step in progress), `facing` (1 S / 2 N / 3 W / 4 E), `mode` (0 walk / 1 run / 2 Mach / 3 Acro / 4 surf), `step` (+1 a tile). Read after every frame once `wideon` has loaded the field table; `none` before that |
| `partnerset FILE` | Co-op: the partner's art, `PK_PARTNER_ART_BYTES` (21536: 42 frames of 32x32 4bpp, then a 16 colour palette). A file of any other length turns the partner's art off. `app/build/partner/brendan.bin` / `may.bin` are made by the ROM-backed `PartnerArtTest`: `POKEDAISY_ROM_DIR=<dir with emerald.gba> ./gradlew :app:testDebugUnitTest --tests '*PartnerArtTest'` (the env var, not `-DromDir`: Gradle forwards only that) |
| `partnerpos VIS FX FY FRAME FLIP BLOB BOB` | Co-op: place the partner by hand (what the app does each frame from `PresenceSmoother`): VIS 0 / 1, fine position, art frame 0-41, hflip, blob frame (-1 none, 39-41) and bob in pixels |
| `partnernear DX DY FRAME FLIP BLOB` | Co-op: place him DX / DY tiles from the player, re-placed after every frame so he follows the camera (the local fine position is the reference) |
| `partnerinfo [LABEL]` | Co-op: why the last frame drew the partner or not, as `why W oam O blobOam B slot S at X,Y frame F` plus `pal1 XXXX fadey N` (his colour 1 after fade and tint). `why`: 0 drawn, 1 off, 2 no art, 3 no player on screen, 4 offscreen (never put in OAM past the hardware's wrap: only EXPAND's margins draw it), 5 no free OAM entry, 6 no free palette slot, 7 his tiles are in use by the game, 8 bad frame (frame or blob outside the art). LABEL is printed, so a test pins each case as its own `# want:` line |
| `framediff A B [LABEL]` | The box of pixels two `shot` PPMs differ in, `box X0,Y0-X1,Y(`pk_coop_battle_starting`): `starting 0|1` or `none` (LABEL as in `partnerinfo`): how a partner's place is pinned, a run with him against the same run without |
| `memhash NAME` / `expectsame A B` | Co-op: a CRC of EWRAM, IWRAM, VRAM, OAM and palette RAM under NAME; `expectsame` passes when the newest hashes named A and B are equal and fails the run otherwise. How `partner_clean` shows the partner leaves nothing behind, in a state taken with him on too |
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
| `expect8 / expect16 / expect32 ADDR VAL [N]` | Check the bus holds VAL at ADDR (N values in a row: a range). Prints `PASS <addr>` or `FAIL <addr> got X want Y`; any FAIL makes the run exit 1 |
| `expectin16 ADDR VAL...` | The bus halfword at ADDR is one of the values: `PASS` / `FAIL` |
| `expectspecies ADDR MONADDR` | The halfword at ADDR equals the species of the (encrypted) Pokémon struct at MONADDR |
| `expectmon ADDR OTID SPECIES [GENDER]` | The struct Pokémon at ADDR has that OT ID and decrypted species, its checksum holds (and the OT-gender bit when given): PASS / FAIL |
| `expectmonin ADDR SPECIES...` | The Pokémon struct at ADDR has one of these species (the second wild Pokémon is in the map's table): `PASS <addr> species N` or a FAIL line |
| `expectfile ADDR FILE` | The bus holds FILE's bytes from ADDR: `PASS <addr> (N bytes)` or a FAIL line |
| `expectframecrc X Y W H CRC` | The current frame's region (R G B bytes) has this CRC32 (hex): `PASS framecrc`, or a FAIL line with the CRC it got |
| `waittask FUNC MAX` | Emerald: run frames until a gTasks entry is active with func FUNC (Thumb bit ignored): PASS, FAIL after MAX frames |
| `call *TABLE [ARGS]` | As `call`, the function being the pointer stored at TABLE (a game function table slot) |
| `memsave ADDR LEN FILE` | Write LEN bytes of RAM to FILE (a save's own party slots, to compare later with `expectfile`) |
| `reown MON OTID` | Give the struct Pokémon at MON another OT ID, its encrypted substructures re-keyed (a Pokémon that belongs to another trainer than the one whose game it is in) |
| `coopinstall` | Co-op's retail check (`pk_coop_install`, repo builds only; it changes nothing): `coop: installed` or `coop: refused <why>` (the first function whose code CRC didn't match) |
| `coopuninstall` | Forget the check (`pk_coop_uninstall`): `coop: uninstalled, rom patched: yes\|no` |
| `coopstarting` | Whether a battle is starting now, after a passed check (`pk_coop_battle_starting`): `starting 0|1` |
| `lockinstall` | The co-op story lock's install (`pk_coop_lock_install`, repo builds only): `lock: installed` or `lock: refused <why>` (a function name whose code CRC didn't match, `block overlap`, ...) |
| `lockuninstall` | Take it out again: `lock: uninstalled, rom patched: yes\|no`; `0x0203F400-0x0203F4FF` is zero afterwards |
| `lockactive 0\|(`pk_coop_battle_starting`): `starting 0|1` | Switch the lock on / off (off drops any hold): `lock: active N` |
| `lockheld` | The held story event: `lock: held <id> kind <k>` (id 0 = none; kind 0 COORD, 1 OBJECT, 2 FRAME) |
| `lockgo ID` | Start the held event (`pk_coop_lock_go`; waits, like `call`, for a pk_call-safe frame): `lock: go (`pk_coop_battle_starting`): `starting 0|1` (started / allowed), `0` (not held), `-1` (not now) |
| `lockcancel` | Drop the hold: `lock: cancelled`. A story script is started with `call 0x08098EF8 <script>` (ScriptContext_SetupScript, the script's address from `pk_coop_lock_emerald.h`'s table); `coop/lock_*.txt` |
| `coopjoin` | Co-op field signals: `join K A B` from `pk_coop_join_kind` (0 not joinable, 1 ordinary wild, 2 two trainers, 3 one trainer with two or more Pokemon) and `pk_coop_join_trainers`; only meaningful while `pk_coop_battle_starting` |
| `coopfree` / `coopfreefield` / `coopexpectfree 0\|(`pk_coop_battle_starting`): `starting 0|1` | `pk_coop_partner_free` (the free field or the START menu's top level) and `pk_coop_on_free_field` (the field alone, safe for the after-battle writes): `free F field G` / `freefield G` / PASS or FAIL |
| `coopgive FILE` / `coopwritemon SLOT FILE` | The after-battle writes: the 100 bytes of FILE given to the game (party, else PC: `give 0` party, `(`pk_coop_battle_starting`): `starting 0|1` PC, `2` no room, `-1` retry) or written into party slot SLOT. Each waits for a safe frame first |
| `coopremove ITEM COUNT` / `coopflag ID` / `coopmoney AMOUNT` / `coopwhiteout` / `coopevolve SLOT` / `coopsave` | The rest of `pk_coop_after.h`: take bag items, set a trainer flag, add money, white out, start a slot's evolution scene, save in game. Each prints its result (`remove N`, `flag N`, ...: 1 done, 0 refused, -1 retry) |
| `coopprize ID [single]` | `pk_coop_prize` (`pk_coop_prize_single` with the word `single`: a one-trainer battle's prize, x2 for a double-battle trainer): the base prize money a trainer pays, from the ROM's tables (`prize N`, -1 for a bad id; any frame) |
| `coopmoneymark` / `coopmoneyexpect delta N\|half` | Remember the game's money; then PASS / FAIL on it being the mark + N, or the mark halved (the white-out rule) |
| `afterhash SLOT ADDR LEN` / `afterexpecthash SLOT ADDR LEN` / `afterexpectsame A B LEN` | Hash LEN bus bytes into SLOT and PASS when the same bytes still hash the same / when two ranges are equal: how the after-battle tests show a refused write left the game untouched |
| `expect8p PTR OFF VAL` / `expectfilep PTR OFF FILE N` | The byte / the first N bytes of FILE sit at `*(u32 *)PTR + OFF` (a save block's or the PC's field: those move) |
| `romcheckmark` / `romchecksame` | Mark the last `romcheck`'s three counts; PASS when a later one equals them (the ROM overlay is back as it was) |
| `pair SAVE` | A second core of the same ROM with its own save (a disposable copy), from reset. From then on every frame run (`wait`, `press`, the waits inside commands) runs both cores, A then B, and moves the link bridge's blocks between them; `on a` / `on b` say which core the commands drive (keys, pokes, expects, calls, `coopbegin`...); each core has its own ROM patch lists, pk_call depth and bridge state. Plan 6's `coop/link_*.txt` |
| `on a\|b` / `linkrun N` / `linkdelay F` | Which core the next commands drive; N frames (as `wait`); blocks arrive F frames after they were sent (0 = the next pump) |
| `linkstate` / `linkwait STATE MAX` / `linkinfo` | `linkstate a S b S` (0 idle, 1 setup, 2 battle, 3 ended: `PK_COOP_LINK_*`); frames until every core is in STATE (`PASS linkwait`, FAIL after MAX); each core's bridge counters (sent, received, refused, dropped, overrun, seqs, ring) |
| `coopbegin MASTER KIND TA TB` | `pk_coop_link_begin` on the current core (installing the bridge first): MASTER 1 = the host, KIND 1 wild / 2 trainers / 3 single / 4 story, TA / TB the AI trainers' `gTrainers` ids; the other player is the other core when paired. Waits for a pk_call-safe frame: `link: begin 1 (after N frame(s))` or `link: begin 0 refused WHY` |
| `joinhold` / `linkstart MASTER [KIND TA TB]` | Plan 6 task 2: `link: hold R` (`pk_coop_link_host_hold`: this core is held at a joined trainer battle's start, after `waittask 0x080B05F0`); `linkstart (`pk_coop_battle_starting`): `starting 0|1` = `pk_coop_link_host_go` (the held battle becomes the link battle; no frame runs first), `linkstart 0 KIND TA TB` = `pk_coop_link_partner_go` from the free field, retrying frames for a safe one: `link: start 1 (after N frame(s))` or `link: start 0 refused WHY` |
| `storyarm` / `storystate` / `storywait STATE MAX` / `storygo MASTER` / `storycancel` | Task 6, story battles (`pk_coop_link_story.c`): `storyarm TRAINER` puts the hook (taking only that trainer) on the trainer script's `dotrainerbattle` on the current core (`story: armed (`pk_coop_battle_starting`): `starting 0|1`); `storystate` prints each core's state (0 idle, 1 armed, 2 picking, 3 ready, 4 cancelled); `storywait` runs frames until every core is in STATE (`PASS storywait`); `storygo` starts the link battle with the picked team (`story: go 1 (after N frame(s))`; MASTER 1 = the host); `storycancel` takes the hook out and lets the game's own battle start. `coop/link_story_gym.txt` |
| `linkend` / `linkreset` / `linkuninstall` | `pk_coop_link_end` / `_reset` / `_uninstall` on the current core |
| `heapmark` / `heapexpect` | task 7: remember the game heap's free bytes on the current core / they equal the mark (PASS / FAIL): what a screen allocated is freed |
| `linkhold 0\|(`pk_coop_battle_starting`): `starting 0|1` / `linkleave 0\|1` / `corereset` | task 7: 1 = no block is delivered (they pile up in the queues; 0 = the line is up); `pk_coop_link_leave` on the current core (1 = blackout), printing the state after; the current core restarts from its save with its bridge taken out (the app closed mid-battle) |
| `linkgiveseed SEQ\|next [LABEL]` | `pk_coop_link_give` of a well-formed seed block (next = the seq the bridge expects): `link: give R status B -> A LABEL`, B / A the game's received flag before / after (a refused block never changes it) |
| `linkgivehex KIND LABEL HEX` / `linkgivemon KIND SPECIES MOVE PP ITEM LABEL` | `pk_coop_link_give` of a forged block on the current core (kind = `PK_COOP_LINKB_*` as a number; payload bytes in hex / an unencrypted one-Pokemon block with those fields), with the next seq: `link: give R status B -> A LABEL` |
| `linkresult LABEL` / `linkresultwait MAX [a\|b]` / `linkresultclear` / `linkdrained` | (task 5) `pk_coop_link_result` of the current core: `link: result LABEL N <hex bytes>` (N 0 = not ready); frames until every core's (or that one's) end result is ready (`PASS linkresultwait after N frame(s)`, FAIL after MAX); drop it; `link: drained 0\|(`pk_coop_battle_starting`): `starting 0|1` (the game's outbox is empty) |
| `linkmoneymark N` / `linkmoneyexpect N DELTA` / `linkflag ID WANT` | (task 5) the current core's money into slot N (0-7, per core) / PASS when it is that mark + DELTA now / PASS when trainer ID's flag is WANT (0\|1) |
| `never32 ADDR VAL LABEL` / `linkdelayend F` / `linkafterend` | (task 5) from now on a frame in which the current core's word at ADDR is VAL is a FAIL (up to 8; checked every pump: the VS screen's and the record prompt's callback2 values); the block that carries the game's ENDLINKBATTLE arrives F frames later still (an uneven end: the other player held back); `linkafterend a N b N dropped a->b N b->a N`: blocks each game sent after its bridge said ENDED, and late blocks dropped at an ended receiver (the partner's trailing IS_DONE) |
| `rangehash NAME ADDR LEN` | CRC32 of LEN bus bytes at ADDR, kept under NAME for `expectsame` (works across `on a` / `on b`; the newest 64 are kept) |
| `waitmem8\|16\|32 ADDR VAL MAX` / `waitchange32 ADDR MAX` | Frames until the bus holds VAL at ADDR / until the word at ADDR is no longer what it is now (`PASS waitmem` / `PASS waitchange`, FAIL after MAX) |
| `monexp NAME ADDR` / `bagqty NAME ITEM` / `peeknum NAME ADDR WIDTH` | Plan 6 Task 4: keep a number under NAME: a struct Pokémon's EXP (also `NAME.lv` level, `NAME.hp`, `NAME.sp` species), how many of an item the game's bag holds (quantity decrypted), a 1 / 2 / 4 byte bus value |
| `expectnum NAME VAL` / `expectdelta A B N` / `expectge NAME VAL` | PASS / FAIL on a kept number: equal to VAL, B - A equal to N, at least VAL |
| `linkcaught FILE` | `pk_coop_link_caught`: `link: caught by 0\|1\|2` (nothing / the host's / the partner's ball) and, when something was caught, its 100 bytes in FILE (re-keyed to the catcher's trainer ID: `coopgive FILE` on the catcher's core after the battle gives it) |
| `expectotname MON` | The struct Pokémon at MON has the current game's trainer name as its OT name. `expectmon ADDR own SPECIES own` takes this game's trainer ID / gender as OTID / GENDER |
| `watch8 ADDR MAXSTEPS` | Single-steps the current core until the byte at ADDR changes: `watch8 ADDR OLD -> NEW pc PC lr LR r0 R0 after N step(s)` - finds the instruction that wrote it |

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


## Games on a link cable (`mgba_link`)

`mgba_link.c` runs two to four GBA games in one process joined by mGBA's own lockstep link
(`gba/sio/lockstep.c`, the multiplayer cable), on the app's own LINK PLAY code: it compiles
`app/src/main/cpp/pk_link.c` (and `pk_savevf.c`), so build it from the repo root. mGBA only defines the
link's hooks; its Qt frontend fills them with a thread per game that sleeps and wakes
(`MultiplayerController.cpp`). `pk_link.c` runs them all on one thread and "asleep" means "not run": the
first game is the link's parent, the others run only on the cycles it hands them, so none drifts more than
one lockstep step (2000 cycles) from it. Commands are mgba_dump's with a side (`0`..`3`, or `all` / `both`):
`wait N`, `keys`/`press`/`hold SIDE KEYS [N]`, `shot SIDE FILE` (every side: `_0`, `_1`, ... before the
extension), `dump`, `peek SIDE ADDR N`, `poke8/16/32` and `link` (each game's SIO mode, the lockstep phase and
budgets).

```sh
docker run --rm -v "$(pwd):/repo" -w /repo/native-capture pokedaisy-capture \
    -c "gcc -O2 -Wall -o mgba_link mgba_link.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm"
docker run --rm -v "$(pwd):/work" -w /work pokedaisy-capture \
    -c "./mgba_link fr.gba fr.sav lg.gba lg.sav < trade.txt"
```

What it has done, on copies of the user's saves (2026-10-31 / 11-01). Each save was first put at a cable club
counter with mgba_dump: `call` ScriptContext_SetupScript (FireRed / LeafGreen 0x08069AF9, Emerald 0x08098EF9,
Ruby rev 1 / 2 0x080655D9, Sapphire 0x080655DD) on a `warp` to a Pokémon Center 2F, then saved through START.

- **FireRed with LeafGreen** (Viridian 2F, x 10 y 3, the Direct Corner attendant): TRADE CENTER and YES to both
  saves; the parent (game 0) then says "When all players are ready... A Button: Confirm", the other "Awaiting
  linkup"; A twice more on the parent and A **held** for the last one (`Task_LinkupTryConfirmation` reads
  JOY_HELD once its message is gone). Walk in one tile at a time (`hold both UP 10`, LEFT / RIGHT, then
  `hold both UP 30` onto the chairs at (4, 5) / (7, 5)) and the trade screen opens on both. HAUNTER for
  HAUNTER: both evolved into GENGAR, both games saved, each save booted alone has the other's Pokémon, evolved.
  The one FireRed received kept its name: it's Italian (language 4), and EvolutionRenameMon only renames a
  Pokémon of the game's own language, as on a real cable.
- **FireRed with Emerald** (Emerald: Oldale 2F, the same (10, 3); set VAR_CABLE_CLUB_TUTORIAL_STATE 0x40CD
  to 2 first or its first-visit walk moves the player): the games' own rules held first - FireRed needs
  FLAG_SYS_CAN_LINK_WITH_RS (0x844) and Emerald FLAG_IS_CHAMPION (0x87F), else "The other TRAINER is not
  ready" / "I'm awfully sorry" over the cable. With both set: KADABRA for MANECTRIC, the KADABRA evolved into
  ALAKAZAM on Emerald, both saved.
- **Ruby rev 2, Sapphire rev 1, Sapphire rev 2 and Emerald, four games** at the Record Corner (Ruby / Sapphire:
  Oldale 2F, **(10, 4)**, a row lower than FireRed's and Emerald's: from (10, 3) the walk ends a tile past the
  door, SetCableClubWarp finds no warp under the player and warps to map 0.0, Petalburg). "4P LINK" on Ruby,
  every game in the Record Corner, each walked out of the doorway (y 9) to a seat, "Mixing records..." on all
  four, then "FEDE sent over one EON TICKET." on the other three, every save written.

Speed: one game alone ran ~2000 frames a second here, the linked pair ~1000: the link costs no more than
the second game. On the API 32 emulator the app's LINK PLAY ran two games at ~57 frames a second.

## Test saves

**Retail English Emerald, for the co-op battle checks** (`boot/emerald_coop.txt`, `boot/emerald_coop_newgame.txt`). Nobody
had a save of the player's own, so one was played headless from a new game (2026-10-24) and is kept outside the repo like
every ROM and save: the co-op checks read the ROM from `EMERALD_ROM` and the save from `EMERALD_SAVE` (say
`~/roms/emerald.gba` and `~/roms/emerald.sav`). It is a BOY called COOP standing on Route 101 (map 0.16) with the
story played as far as Birch's lab: TORCHIC (the starter, from the real rescue battle), WURMPLE and ZIGZAGOON (Lv 5, given
by the game's own `givemon` through `ScriptContext_SetupScript`), 5 POKé BALLs and 3 POTIONs (`additem`), the wall clock set,
saved from the START menu. No Pokédex yet.

- `boot/emerald_coop.txt` boots it to the field with the controls free in one run (title, CONTINUE, 1238 frames, nothing
  pressed on the field).
- `boot/emerald_coop_newgame.txt` rebuilds it: `head -c 131072 /dev/zero | tr '\0' '\377' > emerald.sav`, then
  `./mgba_dump emerald.gba emerald.sav < boot/emerald_coop_newgame.txt` (the file's header says what each stage does; a
  replay from a blank save gave the same party and bag). mgba_dump writes to the save it is given, and the in-game save
  makes it 131088 bytes (mGBA's clock trailer) - pass a copy to every run, never the original.
- On an x86_64 host link mgba_dump with `-L/usr/lib/x86_64-linux-gnu` (the lines above are the Mac's aarch64 path).

**The second player's save** (plan 6's two-core `pair` checks, `coop/link_*.txt`): `EMERALD_PARTNER_SAVE` names it (`$PARTNER_SAVE` in a test is
the work dir's copy of it, like the first save). It is a player called NED with MUDKIP first and WURMPLE and ZIGZAGOON behind it
(Lv 5), kept outside the repo like the other. `scripts/coop_headless_check.sh` replaces a line `@boot` in a test with
`boot/emerald_coop.txt`'s cold boot, which is how the second core gets to the field (`on b`, `@boot`, `on a`).
