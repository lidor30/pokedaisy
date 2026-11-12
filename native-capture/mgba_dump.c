// mgba_dump — headless libmgba memory-capture tool, no Android/JNI/adb
// involved at all. Loads a ROM (+ optional save), runs a scripted boot
// sequence read from stdin, and dumps EWRAM+IWRAM to the exact same
// ewram.bin/iwram.bin format PokeDaisyActivity's dumpFixtureIfPending()
// produces on-device — so it's a drop-in replacement for
// scripts/capture_fixture.sh's data source, consumable by the SAME JVM
// FixtureMemoryReader tests with zero changes there.
//
// Mirrors app/src/main/cpp/pokedaisy_jni.c's proven
// libmgba API usage (core creation, key bits, bus reads) — see that file's
// comments for why each call is there. The one thing this tool gets for
// free that the Android/JNI path had to work hard for: since this process
// alone drives every frame, there is no other thread that could ever catch
// a struct mid-rewrite — no torn-read risk exists here by construction
// (contrast dumpFixtureIfPending()'s isStructSnapshotConsistent() check,
// needed there specifically because the emu thread and Kotlin's periodic
// sampling are two different execution contexts).
//
// stdin commands, one per line:
//   wait N            - advance N frames (no keys held, unless `keys` holds some)
//   keys KEYS          - hold KEYS from now on, through every `wait` (NONE releases them) -
//                        for frame-by-frame captures: `hold KEYS 1` runs 2 frames (it releases)
//   press KEYS        - press KEYS for one frame then release (comma-separated:
//                        A,B,SELECT,START,UP,DOWN,LEFT,RIGHT,L,R)
//   hold KEYS N        - hold KEYS for N frames then release
//   dump DIR           - write DIR/ewram.bin and DIR/iwram.bin (DIR must exist);
//                        a GB/GBC ROM writes DIR/wram.bin (C000-DFFF) + hram.bin (FF80-FFFE),
//                        a GBC one also wramx.bin (all 8 WRAM banks, 0x1000 each)
//   savestate FILE     - save the core (RAM, CPU, RTC) for a later loadstate
//   vdump DIR          - write DIR/{vram,pal,oam,io}.bin (video memory, palette
//                        RAM, OAM and the first 0x400 I/O registers) - what the
//                        PPU is drawing from, for pulling a screen's graphics - and
//                        iolatch.bin, the registers as last written (write-only
//                        ones like the BG scroll offsets read back as open bus)
//   poke8/poke16/poke32 ADDR VAL - write RAM (hex or decimal), e.g. to put a
//                        party mon into a state worth screenshotting
//   wideon MARGIN      - run the app's WIDE (pk_wide.c) after every frame from now on, MARGIN
//                        GBA px per side, for the game the ROM header names (repo builds only)
//   widecheck [all]    - also rebuild the middle 240x160 each frame and compare it with the real
//                        frame, pixel for pixel, printing frames that differ (the first 4 saved as
//                        /work/miss_<frame>.ppm: real | rebuild); `widestats` prints
//                        the tally since. Map frames only, unless `all` (battles: capture with
//                        `layers 0x18`, since their rebuild is BG3 + sprites)
//   wideview X Y W H   - from now on draw this window instead (the frame's pixels, its top-left
//                        (0, 0); up to 800x800): ZOOM and the look-around, e.g. -200 -100 640 360
//   widefuzz N SEED    - N frames of random windows and random bytes / pointers written over the RAM
//                        WIDE reads: it must never crash (an ASan build reports any bad read)
//   wideinfo [lines]   - print the last frame's mode, score, torn and close (ZOOM steps in) flags
//   wideshot FILE      - write the last wide frame (the window) as a PPM
//   widecentre FILE    - write the last rebuild of the middle 240x160 (widecheck on) as a PPM
//   widerec FILE N     - run N frames, recording every wide frame into FILE (scripts/wide_gif.py)
//   layers MASK        - draw only the video layers in MASK from the next frame on
//                        (bit 0-3 = BG0-BG3, bit 4 = OBJ; 0x1F = all, the default)
//   follower FILE      - FOLLOWER's Pokémon: FILE = its six 32x32 frames + palette (3104 bytes); `followon 1` draws it
//   followon 0|1       - FOLLOWER shown (needs `wideon`: the field reads' table)
//   partnerself        - co-op presence's read of the player (pk_partner_self, taken after every frame once `wideon`
//                        loaded the field table): "valid V map G.N fine X,Y facing F mode M step S"
//   partnerset FILE    - the partner's art (21536 bytes: 42 32x32 4bpp frames + palette; PartnerArt's)
//   partnerpos VIS FX FY FRAME FLIP BLOB BOB - the partner placed there (pk_partner_place); BLOB -1 = no surf blob
//   partnernear DX DY FRAME FLIP BLOB - from now on each frame at the player's fine position + DX, DY tiles x 16
//                        (needs `wideon`: pk_partner_self's read)
//   partnerinfo [LABEL] - "why W oam O blobOam B slot S at X,Y frame F" (why: pk_wide.h's PK_PARTNER_*)
//   memhash NAME       - CRC32 of EWRAM, IWRAM, VRAM, OAM and palette RAM kept as NAME; prints "memhash NAME XXXXXXXX"
//   expectsame A B     - PASS if two memhashes are equal, else FAIL (exit status 1)
//   framediff A B [LABEL] - the box of pixels two `shot` PPMs differ in: "framediff box X0,Y0-X1,Y1" or "framediff none"
//   lookrefresh [PAL]  - PLAYER LOOK's refresh of the player's sprite from its record (pk_look_refresh), after
//                        `romblocks` / `romunblock` changed the look in place; needs `wideon` (the field reads' table)
//   tint R G B         - DAY AND NIGHT's tint from the next frame (256 each = none): the map's and sprites' palettes
//   rawshot FILE       - write the current frame as "W H\n" then its 32-bit pixels as the core gives them, mGBA's
//                        layer tags in the top byte (BG n = 0x08 | n << 4; DAY AND NIGHT's tint reads them)
//   shot FILE          - write the current frame to FILE as a binary PPM (P6) -
//                        no PNG dependency; `python3 -c 'from PIL import Image;
//                        Image.open("x.ppm").save("x.png")'` converts it
//   cheat TYPE CODE    - add a cheat (TYPE auto/cb/gs/ar, CODE's lines joined by
//                        '+') and load the list into the core the way the app
//                        does (app/src/main/cpp/pk_cheats.c, compiled in below)
//   cheatclear         - take every cheat out again
//   gbpark SPIN        - GB: park the main loop on SPIN (a `jr @`) once interrupts are on
//                        (the app's pkRenderGbPark); a SPIN in WRAM gets the `jr @` written first
//   gbcall FN A C RET  - GB: call FN with A / C until it returns to RET (pkRenderGbCall)
//   gbloop N           - GB: run N frames and report where Gen 1's music channel state
//                        (Gen1LoopWatch: Yellow's addresses) first repeats
//   gbfar BANK ADDR A BC DE HL - GB (hex): call BANK:ADDR between frames to its return, registers put back
//   gbinject TRAMP BANK ADDR [AT] - GB (hex): make the game call BANK:ADDR from its frame wait (runs on in `wait`s)
//   gbpoke BANK ADDR BYTE.. - GB (hex): write WRAM bank BANK's bytes at ADDR
//   gbpeek BANK ADDR N - GB (hex): print N bytes of WRAM bank BANK at ADDR (C000-CFFF ignores the bank)
//   gbcallat AT FN A B HL - GB (hex): the game calls FN (A, B, HL) from its loop at AT, back there after (pk_gb_call_at)
//   gbvdump DIR        - GB: VRAM (both banks), OAM and the Color palettes into DIR
//   rompatch AT EXPECT ADDR VALUE - a game option's ROM patch the way the app makes it
//                        (pk_cheats_patch; hex, EXPECT / VALUE as byte strings):
//                        VALUE at ADDR only where the ROM holds EXPECT from AT
//   romunpatch         - take the game options' patches out again (pk_cheats_unpatch)
//   romblocks FILE     - PLAYER LOOK's blocks the way the app puts them in (pk_cheats_block, all or
//                        none; FILE = PlayerLook.Patch.toText(): `block ADDR CRC HEX` lines, then
//                        `pal FROMHEX TOHEX` lines for the palettes already loaded, pk_cheats_repalette)
//   romunblock         - take them out again (pk_cheats_unblock)
//   romcheck ROMFILE   - compare the ROM as the companion reads it (bus +
//                        pk_cheats_overlay) and as RA hashes it (swapped) with
//                        the file
//   expect8/expect16/expect32 ADDR VAL [N] - check the bus holds VAL at ADDR (N values in a row: a range), printing
//                        `PASS <addr>` or `FAIL <addr> got X want Y`; any FAIL makes mgba_dump exit 1 at the end
//   waittask FUNC MAX  - Emerald: run frames until a gTasks entry is active with func FUNC (PASS), FAIL after MAX
//   coopinstall        - co-op's retail check (pk_coop_install, repo builds only; it changes nothing): `coop: installed`
//                        or `coop: refused <why>` (the first function whose code CRC didn't match, ...)
//   coopuninstall      - forget the check (pk_coop_uninstall): `coop: uninstalled`
//   call *TABLE [ARGS] - as `call`, the function being the pointer stored at TABLE (a game function table slot)
//   coopfreefield      - pk_coop_on_free_field: `freefield 0|1` (field, controls free, no fade, no battle)
//   coopstarting       - whether a battle can be armed now (pk_coop_battle_starting): `starting 0|1`
//   lockinstall / lockuninstall / lockactive 0|1 / lockheld / lockgo ID / lockcancel - the co-op story lock (pk_coop_lock.c; coop-lock, plan 5 Task 5): `lock: installed`, `lock: held ID kind K`, `lock: go R` ...
//   pair SAVE          - a second core of the same ROM with its own save (a disposable copy), from reset; from now on every frame run
//                        (`wait`, `press`, the waits inside commands) runs both cores, A then B, and moves the link bridge's blocks
//   on a|b             - which core the next commands drive (keys, pokes, expects, calls...); the other one keeps playing frames
//   waitmem8/16/32 ADDR VAL MAX - frames until the bus holds VAL at ADDR: `PASS waitmem` / FAIL after MAX
//   waitchange32 ADDR MAX - frames until the bus word at ADDR is no longer what it is now: `PASS waitchange` / FAIL after MAX
//   linkrun N          - N frames, as `wait` (both cores, blocks moved after each)
//   linkdelay F        - blocks arrive F frames after they were sent (0 = the next pump)
//   linkstate          - `linkstate a S b S` (PK_COOP_LINK_*: 0 idle, 1 setup, 2 battle, 3 ended)
//   linkwait STATE MAX - frames until every core is in link state STATE: `PASS linkwait` / FAIL after MAX
//   coopbegin MASTER KIND TA TB - pk_coop_link_begin on the current core (kind 1 wild, 2 trainers, 3 single, 4 story; TA / TB the AI
//                        trainers' gTrainers ids), installing the bridge first: `link: begin 1 (after N frame(s))` or `link: begin 0 refused WHY`
//   joinhold           - task 2: `link: hold R` (pk_coop_link_host_hold on the current core: its Task_BattleStart held, a joined battle offered)
//   linkstart MASTER [KIND TA TB] - task 2: MASTER 1 = pk_coop_link_host_go (the held battle starts as the link battle, frames wait for a pk_call-safe one), 0 = pk_coop_link_partner_go with KIND / TA / TB: `link: start 1 (after N frame(s))` or `link: start 0 refused WHY`
//   storyarm TRAINER / storystate / storywait STATE MAX / storygo MASTER / storycancel - task 6 (story battles, pk_coop_link_story.c): arm the
//                        hook at the trainer script's `dotrainerbattle` (`story: armed 1`), the state of each core (0 idle, 1 armed, 2 picking,
//                        3 ready, 4 cancelled), frames until every core is in STATE (`PASS storywait`), begin the link battle with the picked
//                        team (`story: go 1 (after N frame(s))`), or let the game's own solo battle start (`story: cancel 1`)
//   storydisarm / coopreleasescript - a dropped story join (plan 6 final fix): the hook out without the solo battle / the held script let go
//   linkend / linkreset / linkuninstall - pk_coop_link_end / _reset / _uninstall on the current core
//   linkhold 0|1 / linkleave 0|1 / corereset - task 7: no block arrives (0 = the line is up again) / pk_coop_link_leave (1 = blackout) / this core restarts
//   linkinfo           - each core's bridge counters (sent, received, refused, dropped, overrun, seqs)
//   linkresult LABEL / linkresultwait MAX [a|b] / linkresultclear / linkdrained - (task 5) the end's result (pk_coop_link_result), waiting for it, the outbox
//   linkmoneymark N / linkmoneyexpect N DELTA / linkflag ID WANT - (task 5) money marks per core, trainer flags
//   never32 ADDR VAL LABEL / linkdelayend F / linkafterend - (task 5) FAIL if the word is ever VAL; ENDLINKBATTLE's block F frames late; blocks sent after ENDED
//   linkgiveseed SEQ|next [LABEL] - pk_coop_link_give a well-formed seed block of that seq (next = the expected one): `link: give R status B -> A LABEL`
//   monexp NAME ADDR / bagqty NAME ITEM / peeknum NAME ADDR WIDTH - keep a number (EXP + .lv .hp .sp; a bag count; a bus value); expectnum NAME VAL / expectdelta A B N / expectge NAME VAL check them (plan 6 Task 4)
//   linkcaught FILE - pk_coop_link_caught: `link: caught by 0|1|2`, the Pokemon's 100 bytes (re-keyed to the catcher) in FILE; expectotname MON
//   linkgivehex KIND LABEL HEX / linkgivemon KIND SPECIES MOVE PP ITEM LABEL - pk_coop_link_give of a forged block (kind a number, payload hex / an unencrypted Pokemon with those fields), the next seq: `link: give R status B -> A LABEL`
//   rangehash NAME ADDR LEN - CRC32 of LEN bus bytes at ADDR kept under NAME (for `expectsame`, across `on a` / `on b`)
//   expectmon ADDR OTID SPECIES [GENDER] - a struct Pokémon at ADDR: OT ID, decrypted species, checksum (and the OT-gender bit when given) valid: PASS / FAIL
//   memsave ADDR LEN FILE - write LEN bytes of RAM to FILE (e.g. a save's own party slots, to compare later with expectfile)
//   expectspecies ADDR MONADDR - the halfword at ADDR equals the (encrypted) mon struct at MONADDR's species
//   expectin16 ADDR VAL... - check the bus halfword at ADDR is one of the values (`PASS` / `FAIL`)
//   expectmonin ADDR SPECIES... - check the (encrypted) Pokémon struct at ADDR has one of these species (Task 5: the second wild
//                        Pokémon is in the map's table): `PASS <addr> species N` or a FAIL line
//   reown MON OTID     - give the struct Pokemon at MON another OT ID, its encrypted substructures re-keyed (a co-op
//                        partner's Pokémon of another trainer than the host)
//   expectframecrc X Y W H CRC - check the current frame's region (R G B bytes) has this CRC32 (hex): `PASS framecrc`
//                        or a FAIL line that shows the CRC it got
//   expectfile ADDR FILE - check the bus holds FILE's bytes from ADDR: `PASS <addr> (N bytes)` or a FAIL line
//
// Usage: mgba_dump <rom.gba> [save.sav] < commands.txt

#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <stdarg.h>

// The library's build flags, before any mGBA header: struct mCore has fields under
// USE_DEBUGGERS etc., and without them every member past those (enableVideoLayer, ...)
// is read at the wrong offset.
#if defined(__has_include)
#if __has_include(<mgba/flags.h>)
#include <mgba/flags.h>
#endif
#endif
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/log.h>
#include <mgba/core/serialize.h>
#include <mgba-util/vfs.h>
#include <mgba-util/crc32.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/arm/isa-inlines.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gb/io.h>
#include <mgba/internal/sm83/sm83.h>

// The app's own cheat code, so `cheat` tests exactly what ships - when the
// repo is there to compile it from (the capture scripts mount native-capture/
// alone; mount the repo root and build from native-capture/ to get it).
#if defined(__has_include)
#if __has_include("../app/src/main/cpp/pk_cheats.c")
#include "../app/src/main/cpp/pk_cheats.c"
#define HAVE_PK_CHEATS 1
#if __has_include("../app/src/main/cpp/pk_coop.c")
#include "../app/src/main/cpp/pk_coop.c"
#include "../app/src/main/cpp/pk_coop_field.c"
#include "../app/src/main/cpp/pk_coop_after.c"
// coop-field (plan 4 Task 7)
// coop-after (plan 4 Task 8)
// romcheck's last counts (and the mark romchecksame compares to)
static int lastRomDiffs[3], markRomDiffs[3];
#include "../app/src/main/cpp/pk_coop_lock.c"   // coop-lock (plan 5 Task 5)
#include "../app/src/main/cpp/pk_coop_link.c"   // the link bridge (plan 6 Task 1)
#include "../app/src/main/cpp/pk_coop_link_start.c"   // task 2: a joined trainer battle starts as a link battle
#include "../app/src/main/cpp/pk_coop_link_wild.c"   // task 3: wild as a link battle
#include "../app/src/main/cpp/pk_coop_link_story.c"   // task 6: story battles
#include "../app/src/main/cpp/pk_coop_link_drop.c"   // a dropped link (plan 6 Task 7)
#include "../app/src/main/cpp/pk_coop_link_rewards.c"   // task 4: EXP, the BAG and catching in a link battle
#include "../app/src/main/cpp/pk_coop_link_end.c"   // task 5: the end of a joined link battle
#define HAVE_PK_COOP 1
#endif
#endif
#if __has_include("../app/src/main/cpp/pk_wide.c")
#include "../app/src/main/cpp/pk_wide.c"
#include "../app/src/main/cpp/pk_wide_gb.c"
#include "../app/src/main/cpp/pk_gb_field.c"
#define HAVE_PK_WIDE 1
#endif
#if __has_include("../app/src/main/cpp/pk_flight.c")
#include "../app/src/main/cpp/pk_flight.c"
#define HAVE_PK_FLIGHT 1
#endif
#if __has_include("../app/src/main/cpp/pk_gb_audio.c")
#include "../app/src/main/cpp/pk_gb_audio.c"
#define HAVE_PK_GB_AUDIO 1
#endif
#endif

// GBA key bit positions (GBA_KEY_* in mgba/gba/input.h) — kept local, same
// as pokedaisy_jni.c, so this file doesn't depend on the GBA-specific
// header. Matches this project's MgbaCore.Key (app/.../MgbaCore.kt).
enum {
    KEY_A = 1 << 0, KEY_B = 1 << 1, KEY_SELECT = 1 << 2, KEY_START = 1 << 3,
    KEY_RIGHT = 1 << 4, KEY_LEFT = 1 << 5, KEY_UP = 1 << 6, KEY_DOWN = 1 << 7,
    KEY_R = 1 << 8, KEY_L = 1 << 9,
};

#define SAMPLE_RATE 48000

static struct mCore* core;
static int expectFailures;   // `expect*` / `waittask` FAILs so far: the exit status
static color_t* video;
static unsigned videoW, videoH;

#ifdef HAVE_PK_WIDE
// WIDE (the app's pk_wide.c): run after every frame once `wideon`, as the app does.
static bool wideOn, wideCheck, wideCheckBattles;
static int wideDumped;   // misses saved as images since the last widecheck
static struct PkWideView wideView;   // the window drawn: `wideon`'s strips, or `wideview`'s
static uint32_t wideOut[PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H];
static uint32_t wideCentre[PK_WIDE_W * PK_WIDE_H];
static struct { int frames, checked, exact, unverified, battle, torn, worst, worstFrame; double ns, maxNs; } wideStats;

static int wideSkipEvery, wideSkipCount;   // wideskip N: like fast-forward, N - 1 of every N frames only noted (pk_wide_skip)

static void wideAfterFrame(void) {
    if (!wideOn) return;
    if (wideSkipEvery > 1 && ++wideSkipCount % wideSkipEvery) {
        pk_wide_skip(core, (const uint32_t*) video);
        return;
    }
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    pk_wide_view(core, (const uint32_t*) video, wideOut, wideView, wideCheck ? wideCentre : NULL);
#ifdef HAVE_PK_FLIGHT
    if (!wideCheck) pk_flight_draw_wide(wideOut, wideView.w, wideView.h, wideView.x, wideView.y);   // the app's clouds over it
#endif
    clock_gettime(CLOCK_MONOTONIC, &t1);
    double ns = (t1.tv_sec - t0.tv_sec) * 1e9 + (t1.tv_nsec - t0.tv_nsec);
    wideStats.ns += ns;
    if (ns > wideStats.maxNs) wideStats.maxNs = ns;
    if (ns > 2e6) printf("wide: frame %u took %.1f ms (score %d)\n", core->frameCounter(core), ns / 1e6, pk_wide_last_score());
    wideStats.frames++;
    if (pk_wide_last_torn()) wideStats.torn++;
    if (pk_wide_last_mode() == PK_WIDE_MODE_NONE || pk_wide_last_mode() == PK_WIDE_MODE_MENU) {   // a menu's: its back layer alone
        wideStats.unverified++;
        return;
    }
    if (pk_wide_last_mode() == PK_WIDE_MODE_BATTLE) wideStats.battle++;
    // A battle's rebuild draws BG3 and sprites only: compare it only when asked (a capture with `layers 0x18`).
    if (!wideCheck || (pk_wide_last_mode() == PK_WIDE_MODE_BATTLE && !wideCheckBattles)) return;
    // The rebuild of the middle against the real frame, pixel for pixel (RGB). A Game Boy game's is 160 x 144.
    const int FW = pk_wide_frame_w(), FH = pk_wide_frame_h();
    int miss = 0, x0 = FW, x1 = -1, y0 = FH, y1 = -1;
    for (int i = 0; i < FW * FH; i++) {
        if (!(((wideCentre[i] ^ (uint32_t) video[(i / FW) * videoW + i % FW]) & 0xFFFFFF))) continue;
        miss++;
        int x = i % FW, y = i / FW;
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        if (y > y1) y1 = y;
    }
    // Every difference inside the partly shown tile column (row) at an edge the field is scrolled by: the game hasn't
    // drawn that ring column into VRAM yet (Sword and Shield's "performance" builds wait for a whole tile) - its own
    // stale tiles, not the rebuild's to match.
    int hs = gbGame ? 0 : G->memory.io[0x14 >> 1] & 7, vs = gbGame ? 0 : G->memory.io[0x16 >> 1] & 7;   // BG1's scroll within a tile
    bool staleEdge = miss && ((hs && ((x0 >= FW - hs) || (x1 < 8 - hs))) || (vs && ((y0 >= FH - vs) || (y1 < 8 - vs))));
    wideStats.checked++;
    if (!miss) wideStats.exact++;
    else if (staleEdge && pk_wide_last_mode() == PK_WIDE_MODE_MAP) {
        wideStats.checked--;
        wideStats.unverified++;
        printf("widecheck: frame %u: %d px differ in the scrolled-in edge tile (x %d-%d, y %d-%d), the game's stale ring (not counted)\n",
               core->frameCounter(core), miss, x0, x1, y0, y1);
    }
    else if (pk_wide_last_vram_torn()) {
        // VRAM written while the frame was drawn: its lines show two sets of tiles - not the rebuild's to match.
        wideStats.checked--;
        wideStats.unverified++;
        printf("widecheck: frame %u: %d px differ, %s written mid-frame (not counted)\n", core->frameCounter(core), miss,
               gbGame ? "VRAM or a palette" : "VRAM");
    } else {
        printf("widecheck: frame %u (%s): %d px differ\n", core->frameCounter(core),
               pk_wide_last_mode() == PK_WIDE_MODE_BATTLE ? "battle" : "map", miss);
        // The first few as images: the real frame, then the rebuild, side by side. A battle check (`widecheck all`,
        // BG3 + sprites) saves only its battle frames: its map frames lack BG1-2 by design.
        if (wideDumped < 4 && !(wideCheckBattles && pk_wide_last_mode() != PK_WIDE_MODE_BATTLE)) {
            char path[64];
            snprintf(path, sizeof(path), "/work/miss_%u.ppm", core->frameCounter(core));
            FILE* f = fopen(path, "wb");
            if (f) {
                wideDumped++;
                fprintf(f, "P6\n%d %d\n255\n", 2 * FW, FH);
                for (int y = 0; y < FH; y++)
                    for (int x = 0; x < 2 * FW; x++) {
                        uint32_t c = x < FW ? (uint32_t) video[y * videoW + x] : wideCentre[y * FW + x - FW];
                        uint8_t rgb[3] = { (uint8_t) c, (uint8_t) (c >> 8), (uint8_t) (c >> 16) };
                        fwrite(rgb, 1, 3, f);
                    }
                fclose(f);
            }
        }
    }
    if (miss > wideStats.worst) {
        wideStats.worst = miss;
        wideStats.worstFrame = (int) core->frameCounter(core);
    }
}
#else
static void wideAfterFrame(void) {}
#endif

// `memhash NAME`'s results (the newest 64), for `expectsame`.
#define MEM_HASHES 64
static struct { char name[64]; uint32_t crc; } memHashes[MEM_HASHES];
static int memHashCount;

// task 4's numbers (`monexp`, `bagqty`, `peeknum`: EXP, levels, HP, bag counts), kept by name for `expectnum` / `expectdelta`.
#define NUM_STORE 128
static struct { char name[80]; long v; } numStore[NUM_STORE];
static int numCount;
static void numSet(const char* name, long v) {
    for (int i = 0; i < numCount; i++) {
        if (!strcmp(numStore[i].name, name)) {
            numStore[i].v = v;
            return;
        }
    }
    if (numCount < NUM_STORE) {
        snprintf(numStore[numCount].name, sizeof(numStore[0].name), "%s", name);
        numStore[numCount++].v = v;
    }
}
static long numGet(const char* name) {
    for (int i = 0; i < numCount; i++) {
        if (!strcmp(numStore[i].name, name)) return numStore[i].v;
    }
    return -999999;
}

// `wild ADDR KEEP`: WILD ENCOUNTERS' roll before every frame, as the app runs it (-1 = off).
static int wildKeep = -1;

#ifdef HAVE_PK_WIDE
// Co-op presence: pk_partner_self read after every frame once `wideon` has loaded the field table, as the app reads it
// (the step's progress needs every frame); `partnerself` prints the last read.
static bool partnerTrack;
static int partnerOk;
static int32_t partnerSelf[PK_PARTNER_SELF_N];
// `partnernear` runs: the partner follows the player by (partnerDx, partnerDy) tiles, placed after every frame's read.
static int partnerNear, partnerDx, partnerDy, partnerFrame, partnerFlip, partnerBlob;
static void partnerApplyNear(void) {
    if (!partnerNear) return;
    if (partnerOk && partnerSelf[PK_PARTNER_SELF_VALID])
        pk_partner_place(core, 1, partnerSelf[PK_PARTNER_SELF_FX] + partnerDx * 16, partnerSelf[PK_PARTNER_SELF_FY] + partnerDy * 16,
                         partnerFrame, partnerFlip, partnerBlob, 0);
    else pk_partner_place(core, 0, 0, 0, 0, 0, -1, 0);
}
#endif

// Where the game's main loop waits for VBlank, as a stack depth: the System-mode stack pointer most frames end with
// (the game in its wait - a spin on gMain.intrCheck in FireRed, VBlankIntrWait in Emerald - with nothing of its own
// in progress). pk_call_safe calls the game's functions only at that depth. Counted per loaded game.
#define PK_REST_SLOTS 8
static uint32_t pkRestSp[PK_REST_SLOTS];
static uint32_t pkRestCount[PK_REST_SLOTS];

static uint32_t pk_sys_sp(struct ARMCore* cpu) {
    return cpu->privilegeMode == MODE_SYSTEM || cpu->privilegeMode == MODE_USER
        ? (uint32_t) cpu->gprs[ARM_SP] : (uint32_t) cpu->bankedRegisters[BANK_NONE][0];
}

__attribute__((unused)) static void pk_rest_reset(void) {
    memset(pkRestSp, 0, sizeof(pkRestSp));
    memset(pkRestCount, 0, sizeof(pkRestCount));
}

static void pk_rest_note(struct mCore* core) {
    struct ARMCore* cpu = (struct ARMCore*) core->cpu;
    if (cpu->privilegeMode == MODE_IRQ) {
        return;
    }
    uint32_t sp = pk_sys_sp(cpu);
    int low = 0;
    for (int i = 0; i < PK_REST_SLOTS; i++) {
        if (pkRestSp[i] == sp) {
            if (pkRestCount[i] < 100000) pkRestCount[i]++;
            return;
        }
        if (pkRestCount[i] < pkRestCount[low]) low = i;
    }
    pkRestSp[low] = sp;
    pkRestCount[low] = 1;
}

/** The main loop's waiting depth, or 0 until enough frames have ended (about a second's worth). */
static uint32_t pk_rest_sp(void) {
    int best = 0;
    for (int i = 1; i < PK_REST_SLOTS; i++) if (pkRestCount[i] > pkRestCount[best]) best = i;
    return pkRestCount[best] >= 30 ? pkRestSp[best] : 0;
}

static void pairAfterFrame(void);   // `pair` mode (below): the other core's frame and the link bridge's blocks

static void frameOne(void) {
#ifdef HAVE_PK_WIDE
    if (wildKeep >= 0) pk_field_state(core, 0, wildKeep);
#endif
    core->runFrame(core);
#ifdef HAVE_PK_COOP
    pk_coop_lock_tick(core);   // coop-lock (plan 5 Task 5)
    pk_coop_link_tick(core);   // task 5
#endif
    if (core->platform(core) == mPLATFORM_GBA) pk_rest_note(core);
#ifdef HAVE_PK_FLIGHT
    pk_flight_tick(core);   // FREE FLIGHT (`flighton`): its step after every frame, like the app's pkRunFrame
    if (sizeof(color_t) == 4 && core->platform(core) == mPLATFORM_GBA) pk_flight_draw((uint32_t*) video, 240, 160, (int) videoW, !wideOn);
#endif
#ifdef HAVE_PK_WIDE
    if (partnerTrack) {
        partnerOk = pk_partner_self(core, partnerSelf);
        partnerApplyNear();
    }
#endif
    wideAfterFrame();
}

// One frame of the active core - and, in `pair` mode, of the other one and the link bridge's blocks between them.
static void runFrame(void) {
    frameOne();
    pairAfterFrame();
}

// mGBA's default logger is noisy (every unmapped I/O register write, DMA,
// etc, straight to stdout/stderr) - fine for interactive debugging, useless
// noise for a scripted capture tool. A no-op logger silences it; pass
// --verbose to keep the default logger instead, for debugging a boot script.
static void noopLog(struct mLogger* log, int category, enum mLogLevel level, const char* format, va_list args) {
    (void) log; (void) category; (void) level; (void) format; (void) args;
}
static struct mLogger silentLogger = { .log = noopLog, .filter = NULL };

static int keyBitFor(const char* name) {
    if (!strcmp(name, "A")) return KEY_A;
    if (!strcmp(name, "B")) return KEY_B;
    if (!strcmp(name, "SELECT")) return KEY_SELECT;
    if (!strcmp(name, "START")) return KEY_START;
    if (!strcmp(name, "RIGHT")) return KEY_RIGHT;
    if (!strcmp(name, "LEFT")) return KEY_LEFT;
    if (!strcmp(name, "UP")) return KEY_UP;
    if (!strcmp(name, "DOWN")) return KEY_DOWN;
    if (!strcmp(name, "R")) return KEY_R;
    if (!strcmp(name, "L")) return KEY_L;
    fprintf(stderr, "mgba_dump: unknown key %s\n", name);
    return 0;
}

static uint32_t parseKeys(char* s) {
    uint32_t mask = 0;
    char* tok = strtok(s, ",");
    while (tok) {
        mask |= keyBitFor(tok);
        tok = strtok(NULL, ",");
    }
    return mask;
}

static void dumpRegion(uint32_t addr, int len, const char* path) {
    FILE* f = fopen(path, "wb");
    if (!f) {
        fprintf(stderr, "mgba_dump: cannot open %s for writing\n", path);
        return;
    }
    uint8_t buf[4096];
    int off = 0;
    while (off < len) {
        int chunk = len - off;
        if (chunk > (int) sizeof(buf)) chunk = sizeof(buf);
        for (int i = 0; i < chunk; i++) {
            buf[i] = (uint8_t) core->busRead8(core, addr + off + i);
        }
        fwrite(buf, 1, chunk, f);
        off += chunk;
    }
    fclose(f);
}

// Built without mGBA's PNG helpers on purpose (they pull libpng into the
// link); color_t is 32-bit XBGR8888 in the Debian libmgba build.
static void cmdShot(const char* path) {
    FILE* f = fopen(path, "wb");
    if (!f) {
        fprintf(stderr, "mgba_dump: cannot open %s for writing\n", path);
        return;
    }
    // The buffer is sized for the largest frame (a GB core: the SGB border's 256x224);
    // the frame itself (160x144 without a border) sits top-left at stride videoW
    // (this libmgba predates currentVideoSize).
    unsigned w = videoW, h = videoH;
    if (core->platform(core) == mPLATFORM_GB) { w = 160; h = 144; }
    fprintf(f, "P6\n%u %u\n255\n", w, h);
    for (unsigned i = 0; i < w * h; i++) {
        uint32_t c = (uint32_t) video[(i / w) * videoW + i % w];
        uint8_t rgb[3] = { (uint8_t) c, (uint8_t) (c >> 8), (uint8_t) (c >> 16) };
        fwrite(rgb, 1, 3, f);
    }
    fclose(f);
    fprintf(stderr, "mgba_dump: shot %s (frame %u)\n", path, core->frameCounter(core));
}

// Runs `frames` frames and writes what the APU played meanwhile as a 16-bit
// stereo WAV (what the app's render core records, e.g. for the click sound).
#ifdef HAVE_PK_GB_AUDIO
// A Game Boy game's sound as the app resamples it (pk_gb_audio.c), not through mGBA's own blip_buf; `gbaudio 0`
// records mGBA's own path again, for a before / after.
static struct pk_gb_audio gbAudio;
#endif

static void cmdWav(const char* path, int frames) {
    struct blip_t* l = core->getAudioChannel(core, 0);
    struct blip_t* r = core->getAudioChannel(core, 1);
    blip_clear(l);
    blip_clear(r);
    size_t cap = (size_t) frames * (SAMPLE_RATE / 20) * 2, pos = 0;
    int16_t* pcm = calloc(cap, sizeof(int16_t));
#ifdef HAVE_PK_GB_AUDIO
    if (gbAudio.left) {
        // What piled up before (nothing reads it between commands): read away, so the level carries on.
        while (pk_gb_audio_read(&gbAudio, pcm, (int) (cap / 2)) > 0) {}
    }
#endif
    // Audio made per runFrame: the app plays one frame a display refresh, so a frame much shorter or longer than
    // the core's frame rate (a Game Boy game turning its screen off or on restarts mGBA's frame) is a gap or a glut.
    int least = 1 << 30, most = 0, uneven = 0;
    const double expect = SAMPLE_RATE * (double) core->frameCycles(core) / core->frequency(core);
    for (int i = 0; i < frames; i++) {
        core->runFrame(core);
#ifdef HAVE_PK_GB_AUDIO
        if (gbAudio.left) {
            int n = pk_gb_audio_read(&gbAudio, pcm + pos, (int) ((cap - pos) / 2));
            if (n < least) least = n;
            if (n > most) most = n;
            if (n < expect * 0.9 || n > expect * 1.1) {
                if (uneven < 40) printf("wav: frame %d made %d samples (%.0f%%)\n", i, n, 100.0 * n / expect);
                uneven++;
            }
            pos += (size_t) n * 2;
            continue;
        }
#endif
        int n = blip_samples_avail(l);
        if (n < least) least = n;
        if (n > most) most = n;
        if (n < expect * 0.9 || n > expect * 1.1) {
            if (uneven < 40) printf("wav: frame %d made %d samples (%.0f%%)\n", i, n, 100.0 * n / expect);
            uneven++;
        }
        if (pos + (size_t) n * 2 > cap) n = (int) ((cap - pos) / 2);
        blip_read_samples(l, pcm + pos, n, true);
        blip_read_samples(r, pcm + pos + 1, n, true);
        pos += (size_t) n * 2;
    }
    printf("wav: %d frames, %.1f samples a frame expected, %d to %d made, %d off by over 10%%\n", frames, expect, least, most, uneven);
    FILE* f = fopen(path, "wb");
    if (!f) {
        fprintf(stderr, "mgba_dump: cannot open %s for writing\n", path);
        free(pcm);
        return;
    }
    uint32_t data = (uint32_t) (pos * 2), riff = 36 + data, fmt = 16, rate = SAMPLE_RATE, bps = SAMPLE_RATE * 4;
    uint16_t pcmFmt = 1, ch = 2, align = 4, bits = 16;
    fwrite("RIFF", 1, 4, f); fwrite(&riff, 4, 1, f); fwrite("WAVEfmt ", 1, 8, f);
    fwrite(&fmt, 4, 1, f); fwrite(&pcmFmt, 2, 1, f); fwrite(&ch, 2, 1, f); fwrite(&rate, 4, 1, f);
    fwrite(&bps, 4, 1, f); fwrite(&align, 2, 1, f); fwrite(&bits, 2, 1, f);
    fwrite("data", 1, 4, f); fwrite(&data, 4, 1, f); fwrite(pcm, 2, pos, f);
    fclose(f);
    free(pcm);
    fprintf(stderr, "mgba_dump: wav %s (%zu frames of audio)\n", path, pos / 2);
}

static void cmdDump(const char* outDir) {
    char path[1024];
    if (core->platform(core) == mPLATFORM_GB) {
        // Game Boy / Color: WRAM C000-DFFF (the bank SVBK has in D000) and HRAM FF80-FFFE.
        snprintf(path, sizeof(path), "%s/wram.bin", outDir);
        dumpRegion(0xC000, 0x2000, path);
        snprintf(path, sizeof(path), "%s/hram.bin", outDir);
        dumpRegion(0xFF80, 0x7F, path);
        // A Color game's whole WRAM, all 8 banks (0x1000 each), whatever SVBK has mapped: Crystal keeps
        // its game state in bank 1 but switches D000 to other banks mid-frame (FixtureMemoryReader maps
        // bank B's D000-DFFF at 0xBD000).
        struct GB* gb = (struct GB*) core->board;
        if (gb->model >= GB_MODEL_CGB) {
            snprintf(path, sizeof(path), "%s/wramx.bin", outDir);
            FILE* f = fopen(path, "wb");
            if (f) { fwrite(gb->memory.wram, 1, GB_SIZE_WORKING_RAM, f); fclose(f); }
        }
        fprintf(stderr, "mgba_dump: dumped wram+hram to %s (frame %u)\n", outDir, core->frameCounter(core));
        return;
    }
    snprintf(path, sizeof(path), "%s/ewram.bin", outDir);
    dumpRegion(0x02000000, 0x40000, path);
    snprintf(path, sizeof(path), "%s/iwram.bin", outDir);
    dumpRegion(0x03000000, 0x8000, path);
    fprintf(stderr, "mgba_dump: dumped ewram+iwram to %s (frame %u)\n", outDir, core->frameCounter(core));
}

static void cmdVdump(const char* outDir) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/vram.bin", outDir);
    dumpRegion(0x06000000, 0x18000, path);
    snprintf(path, sizeof(path), "%s/pal.bin", outDir);
    dumpRegion(0x05000000, 0x400, path);
    snprintf(path, sizeof(path), "%s/oam.bin", outDir);
    dumpRegion(0x07000000, 0x400, path);
    snprintf(path, sizeof(path), "%s/io.bin", outDir);
    dumpRegion(0x04000000, 0x400, path);
    // The registers as last written, write-only ones included (a bus read of BGxHOFS etc.
    // is open bus): mGBA keeps every write in memory.io, so this is what the PPU drew with.
    snprintf(path, sizeof(path), "%s/iolatch.bin", outDir);
    FILE* f = fopen(path, "wb");
    if (f) {
        fwrite(((struct GBA*) core->board)->memory.io, 1, 0x400, f);
        fclose(f);
    }
    fprintf(stderr, "mgba_dump: dumped vram+pal+oam+io to %s (frame %u)\n", outDir, core->frameCounter(core));
}


// Calls Thumb function `fn` with r0-r3 = `args` and returns once it has, leaving
// the CPU exactly as it was (registers, pipeline, halt): the frame can end
// anywhere - mid-way through the game's own code, whose live registers a bare
// PC hijack clobbered (Unbound reset its sound). Runs instruction by
// instruction with IRQs masked until the call returns to SENTINEL, an address
// in the ROM header that is never executed. Mirrors pokedaisy_jni.c's pk_call.
#define PK_CALL_SENTINEL 0x080000C0u

// --- Game Boy: the app's pkRenderGbPark / pkRenderGbCall, for Gen 1's music ---
// A Color WRAM bank's D000-DFFF bytes, whatever SVBK maps there now (the app's pk_gb_read_banked).
static bool pk_gb_read_bank(struct mCore* core, unsigned bank, uint16_t addr, uint8_t* dst, int len) {
    struct GB* gb = (struct GB*) core->board;
    if (bank > 7 || addr + len > 0xE000 || !gb->memory.wram) return false;
    memcpy(dst, gb->memory.wram + bank * GB_SIZE_WORKING_RAM_BANK0 + (addr - 0xD000), (size_t) len);
    return true;
}

static bool gb_park(struct mCore* core, uint16_t spin) {
    struct GB* gb = (struct GB*) core->board;
    struct SM83Core* cpu = (struct SM83Core*) core->cpu;
    for (int i = 0; i < 5000000; i++) {
        core->step(core);
        if (gb->memory.ime && (gb->memory.ie & 1) && (gb->memory.io[GB_REG_LCDC] & 0x80) && !cpu->halted && !cpu->irqPending) {
            // A spin in WRAM (Red / Blue: bank 0 holds no `jr @`) is written there first - a byte the
            // parked game never touches again.
            if (spin >= 0xC000 && spin < 0xDFFF) {
                core->busWrite8(core, (uint16_t) spin, 0x18);
                core->busWrite8(core, (uint16_t) (spin + 1), 0xFE);
            }
            cpu->pc = spin;
            cpu->memory.setActiveRegion(cpu, cpu->pc);
            return true;
        }
    }
    return false;
}

static bool gb_call(struct mCore* core, uint16_t fn, uint8_t a, uint8_t c, uint16_t ret) {
    struct GB* gb = (struct GB*) core->board;
    struct SM83Core* cpu = (struct SM83Core*) core->cpu;
    bool ime = gb->memory.ime, pending = cpu->irqPending;
    gb->memory.ime = false;
    cpu->irqPending = false;
    cpu->sp -= 2;
    core->busWrite8(core, cpu->sp, ret & 0xFF);
    core->busWrite8(core, (uint16_t) (cpu->sp + 1), ret >> 8);
    cpu->a = a;
    cpu->c = c;
    cpu->d = 0;   // and DE = c: Crystal's PlayMusic takes its song in E
    cpu->e = c;
    cpu->halted = false;
    cpu->pc = fn;
    cpu->memory.setActiveRegion(cpu, cpu->pc);
    bool ok = false;
    for (int i = 0; i < 2000000 && !ok; i++) {
        core->step(core);
        ok = cpu->pc == ret;
    }
    gb->memory.ime = ime;
    cpu->irqPending = pending;
    return ok;
}

// A Game Boy cart's function in ROM bank BANK at ADDR, called between frames with A / BC / DE / HL set, run to its
// return and every register put back (unlike gb_call, which leaves the CPU where the function returned): the bank is
// switched in and written to hROMBank (HROM, FF9D in the pokecrystal family) so the function's own far calls come back
// to it, and WRAM bank 1 is mapped at D000. Interrupts stay off, so nothing that waits for a frame returns. How Prism's
// new game got its first Pokémon (TryAddMonToParty) and items headless.
static bool gb_far(struct mCore* core, uint8_t bank, uint16_t addr, uint8_t a, uint16_t bc, uint16_t de, uint16_t hl,
                   uint16_t hrom, uint8_t* outA) {
    struct GB* gb = (struct GB*) core->board;
    struct SM83Core* cpu = (struct SM83Core*) core->cpu;
    struct SM83Core saved = *cpu;
    bool ime = gb->memory.ime;
    uint8_t oldBank = core->busRead8(core, hrom), oldSvbk = core->busRead8(core, 0xFF70);
    const uint16_t magic = 0x0003;   // inside rst $00's vector: never executed by the function itself
    gb->memory.ime = false;
    cpu->irqPending = false;
    core->busWrite8(core, 0xFF70, 1);
    core->busWrite8(core, hrom, bank);
    core->busWrite8(core, 0x2000, bank);
    cpu->sp -= 2;
    core->busWrite8(core, cpu->sp, magic & 0xFF);
    core->busWrite8(core, (uint16_t) (cpu->sp + 1), magic >> 8);
    cpu->a = a; cpu->b = bc >> 8; cpu->c = bc & 0xFF; cpu->d = de >> 8; cpu->e = de & 0xFF; cpu->h = hl >> 8; cpu->l = hl & 0xFF;
    cpu->halted = false;
    cpu->pc = addr;
    cpu->memory.setActiveRegion(cpu, cpu->pc);
    bool ok = false;
    for (int i = 0; i < 20000000 && !ok; i++) {
        core->step(core);
        ok = cpu->pc == magic;
    }
    *outA = cpu->a;
    core->busWrite8(core, 0x2000, oldBank);
    core->busWrite8(core, hrom, oldBank);
    core->busWrite8(core, 0xFF70, oldSvbk);
    *cpu = saved;
    cpu->memory.setActiveRegion(cpu, cpu->pc);
    gb->memory.ime = ime;
    return ok;
}

// Gen1LoopWatch's state: Yellow's wChannelCommandPointers, ReturnAddresses (CHAN1-4),
// SoundIDs, NoteDelayCounters, LoopCounters.
// gen2: 0 = Gen 1, 1 = Crystal (and Crystal Clear), 2 = Gold / Silver, 3 = Polished Crystal.
static void gb_music_state(struct mCore* core, uint8_t* out, int gen2) {
    static const struct { uint16_t a; int n; } R[] = {{0xC006, 8}, {0xC016, 8}, {0xC026, 4}, {0xC0B6, 4}, {0xC0BE, 4}};
    // Crystal's channel structs (wChannel1-4, 0x32 bytes from C101; Gold's from C001): Flags1, MusicAddress +
    // LastMusicAddress, NoteDuration, LoopCount - Gen2Music's loop state. Polished Crystal's: 0x24 bytes from CB7F.
    static const struct { uint16_t a; int n; } C[] = {{3, 1}, {6, 4}, {0x15, 1}, {0x18, 1}};
    static const struct { uint16_t a; int n; } P[] = {{7, 1}, {3, 4}, {0x12, 1}, {0x14, 1}};
    int k = 0;
    if (gen2) {
        uint16_t base = gen2 == 3 ? 0xCB7F : gen2 == 2 ? 0xC001 : 0xC101;
        int stride = gen2 == 3 ? 0x24 : 0x32;
        for (int ch = 0; ch < 4; ch++)
            for (int r = 0; r < 4; r++) {
                int off = gen2 == 3 ? P[r].a : C[r].a, len = gen2 == 3 ? P[r].n : C[r].n;
                for (int i = 0; i < len; i++) out[k++] = core->busRead8(core, (uint16_t) (base + stride * ch + off + i));
            }
        return;
    }
    for (int r = 0; r < 5; r++) for (int i = 0; i < R[r].n; i++) out[k++] = core->busRead8(core, R[r].a + i);
}

static void gb_loop(struct mCore* core, int frames, int gen2) {
    enum { W = 28 };
    uint8_t* states = malloc((size_t) frames * W);
    int still = 0;
    for (int f = 0; f < frames; f++) {
        core->runFrame(core);
        uint8_t* st = states + (size_t) f * W;
        gb_music_state(core, st, gen2);
        if (f > 0 && !memcmp(st, st - W, W)) {
            if (++still >= 90) { printf("gbloop: still from frame %d - ended\n", f - 90); free(states); return; }
        } else still = 0;
        for (int g = 0; g <= f - 120; g++) {
            if (!memcmp(states + (size_t) g * W, st, W)) {
                printf("gbloop: state after frame %d repeats after frame %d: loop [%d, %d), %.2f s, intro %.2f s\n",
                       g, f, g + 1, f + 1, (f - g) / 59.7275, (g + 1) / 59.7275);
                free(states);
                return;
            }
        }
    }
    printf("gbloop: no repeat in %d frames\n", frames);
    free(states);
}

// Whether the game's own code can be called right now (pk_call): parked on a `b .` (the render core), or the game
// waiting for VBlank with nothing of its own in progress - not in an interrupt, its System stack back at the main
// loop's depth (pk_rest_sp). A frame often ends part-way through the game's code instead (its VBlank handler running,
// a lag frame's logic), and a call there lands in the middle of the game's own read-modify-writes: FadeScreen while
// VBlank's handler was part-way through updating the palette fade, the handler then wrote its stale copy back, and the
// FLY warp that followed never finished loading (2026-10-20: a black screen, then a reset, on the Thor; reproduced
// with mgba_dump's `stepinto 3` + FLY's calls). The caller waits for a frame that ends well. Before the depth is
// known (the game's first second) a call goes ahead, as it always did.
static int pk_call_safe(struct mCore* core) {
    struct ARMCore* cpu = (struct ARMCore*) core->cpu;
    uint32_t pc = (uint32_t) cpu->gprs[ARM_PC];
    if (cpu->executionMode == MODE_THUMB && core->rawRead16(core, pc - WORD_SIZE_THUMB * 2, -1) == 0xE7FE) {
        return 1;   // b . - parked
    }
    if (cpu->privilegeMode == MODE_IRQ) {
        return 0;
    }
    uint32_t rest = pk_rest_sp();
    return !rest || pk_sys_sp(cpu) == rest;
}

static uint32_t pk_call_r0; // what the last pk_call returned in r0
static long pk_call_steps = 2000000, pk_call_used; // the step cap of one call (`calllimit N` raises it), and what the last call used

static bool pk_call(struct mCore* core, uint32_t fn, const uint32_t args[4]) {
    if (!pk_call_safe(core)) return false;
    struct ARMCore* cpu = (struct ARMCore*) core->cpu;
    struct ARMRegisterFile saved = cpu->regs;
    uint32_t prefetch0 = cpu->prefetch[0], prefetch1 = cpu->prefetch[1];
    // The memory region the CPU fetches from, and the idle-loop detector that region switches feed: the call
    // moves both to the ROM. A frame often ends just inside the interrupt handler (IWRAM, or the BIOS), and
    // with only the registers put back the CPU went on fetching that address from the ROM - garbage: the game
    // froze or reset (a TAKE on the Thor, reproduced with mgba_dump's `stepinto 3` + `call`).
    struct ARMMemory mem = cpu->memory;
    struct GBA* gba = (struct GBA*) core->board;
    int region = gba->memory.activeRegion;
    uint32_t biosPrefetch = gba->memory.biosPrefetch;
    uint32_t idleLoop = gba->idleLoop, lastJump = gba->lastJump;
    bool haltPending = gba->haltPending;
    int idleStep = gba->idleDetectionStep, idleFailures = gba->idleDetectionFailures;
    int32_t cached[16];
    memcpy(cached, gba->cachedRegisters, sizeof(cached));
    enum ExecutionMode exec = cpu->executionMode;
    int halted = cpu->halted;
    enum PrivilegeMode priv = cpu->privilegeMode;
    cpu->halted = 0;
    cpu->cpsr.i = 1;
    // On the main stack, in System mode: inside an interrupt the IRQ stack is 160 bytes with the main one right
    // under it (and the BIOS's SVC stack is smaller still), while below the main stack's SP nothing is in use.
    if (priv != MODE_SYSTEM && priv != MODE_USER) {
        ARMSetPrivilegeMode(cpu, MODE_SYSTEM);
        cpu->cpsr.priv = MODE_SYSTEM;
    }
    for (int i = 0; i < 4; i++) cpu->gprs[i] = (int32_t) args[i];
    cpu->gprs[ARM_LR] = (int32_t) (PK_CALL_SENTINEL | 1);
    cpu->gprs[ARM_PC] = (int32_t) (fn & ~1u);
    _ARMSetMode(cpu, MODE_THUMB);
    ThumbWritePC(cpu);
    bool ok = false;
    long used = 0;
    for (; used < pk_call_steps; used++) {
        if (cpu->executionMode == MODE_THUMB && (uint32_t) cpu->gprs[ARM_PC] - WORD_SIZE_THUMB == PK_CALL_SENTINEL) {
            ok = true;
            break;
        }
        core->step(core);
    }
    pk_call_used = used;
    pk_call_r0 = (uint32_t) cpu->gprs[0];
    cpu->regs = saved; // the banked registers too: the mode's own SP / LR are back as they were
    cpu->privilegeMode = priv;
    cpu->executionMode = exec == MODE_ARM ? MODE_THUMB : MODE_ARM; // force _ARMSetMode to apply
    _ARMSetMode(cpu, exec);
    cpu->prefetch[0] = prefetch0;
    cpu->prefetch[1] = prefetch1;
    cpu->memory = mem;
    gba->memory.activeRegion = region;
    gba->memory.biosPrefetch = biosPrefetch;
    gba->idleLoop = idleLoop;
    gba->lastJump = lastJump;
    gba->haltPending = haltPending;
    gba->idleDetectionStep = idleStep;
    gba->idleDetectionFailures = idleFailures;
    memcpy(gba->cachedRegisters, cached, sizeof(cached));
    cpu->halted = halted;
    return ok;
}


// Parks the game's main loop on `spin` (a Thumb `b .`) so it can't change the
// music any more, while the VBlank interrupt keeps the sound engine playing.
// Steps first until the CPU is in the main context (System/User mode, IRQs
// on) - parking inside an interrupt handler would leave IRQs off for good.
// Mirrors pokedaisy_jni.c's pk_park.
static bool pk_park(struct mCore* core, uint32_t spin) {
    struct ARMCore* cpu = (struct ARMCore*) core->cpu;
    for (int i = 0; i < 5000000; i++) {
        // ...and the VBlank IRQ fully on (IME, IE bit 0, DISPSTAT bit 3): games
        // switch it off around loads, and a parked loop never turns it back on.
        if ((cpu->privilegeMode == MODE_SYSTEM || cpu->privilegeMode == MODE_USER) && !cpu->cpsr.i && !cpu->halted
            && (core->busRead16(core, 0x04000208) & 1) && (core->busRead16(core, 0x04000200) & 1)
            && (core->busRead16(core, 0x04000004) & 8)) {
            cpu->gprs[ARM_PC] = (int32_t) (spin & ~1u);
            _ARMSetMode(cpu, MODE_THUMB);
            ThumbWritePC(cpu);
            return true;
        }
        core->step(core);
    }
    return false;
}

#ifdef HAVE_PK_CHEATS
// `cheat`: the cheats added so far as one mGBA .cheats text, rebuilt into the
// core on every change - what CheatStore + pkCheatsApply do in the app.
static char cheatText[16384];

static int cheatTypeFor(const char* name) {
    if (!strcmp(name, "cb")) return PK_CHEAT_CODEBREAKER;
    if (!strcmp(name, "gs")) return PK_CHEAT_GAMESHARK;
    if (!strcmp(name, "ar")) return PK_CHEAT_ACTION_REPLAY;
    return PK_CHEAT_AUTO;
}

static void cmdCheat(const char* type, const char* code) {
    char lines[1024];
    snprintf(lines, sizeof(lines), "%s", code);
    for (char* c = lines; *c; c++) {
        if (*c == '+') *c = '\n';
    }
    char* check = pk_cheats_check(lines, cheatTypeFor(type), "");
    if (!check) {
        printf("cheat: check failed\n");
        return;
    }
    char* nl = strchr(check, '\n');
    *nl = '\0';
    printf("cheat %s: directive '%s', lines ok %s\n", type, check, nl + 1);
    size_t len = strlen(cheatText);
    len += snprintf(cheatText + len, sizeof(cheatText) - len, "!reset\n");
    if (check[0]) {
        len += snprintf(cheatText + len, sizeof(cheatText) - len, "!%s\n", check);
    }
    snprintf(cheatText + len, sizeof(cheatText) - len, "# %s\n%s\n", code, lines);
    free(check);
    printf("cheat: %d set(s) loaded, rom patched: %s\n",
           pk_cheats_apply(core, cheatText, strlen(cheatText)), pk_cheats_rom_patched() ? "yes" : "no");
}

static int romDiffs(const uint8_t* file, size_t size, bool overlay) {
    int diffs = 0;
    uint8_t buf[4096];
    for (size_t off = 0; off < size; off += sizeof(buf)) {
        int n = size - off < sizeof(buf) ? (int) (size - off) : (int) sizeof(buf);
        for (int i = 0; i < n; i++) buf[i] = (uint8_t) core->busRead8(core, 0x08000000u + off + i);
        if (overlay) pk_cheats_overlay(0x08000000u + off, buf, n);
        for (int i = 0; i < n; i++) {
            // The RTC's GPIO registers read back live (Poller's GPIO hole).
            uint32_t a = off + i;
            if (a >= 0xC4 && a < 0xCA) continue;
            if (buf[i] != file[a]) {
                if (diffs < 4) printf("  %08x: bus %02x file %02x\n", 0x08000000u + a, buf[i], file[a]);
                diffs++;
            }
        }
    }
    return diffs;
}

static int hexBytes(const char* hex, uint8_t* out, int max) {
    int n = 0;
    while (hex[0] && hex[1] && n < max) {
        unsigned v;
        if (sscanf(hex, "%2x", &v) != 1) break;
        out[n++] = (uint8_t) v;
        hex += 2;
    }
    return n;
}

static void cmdRomPatch(const char* line) {
    unsigned at = 0, addr = 0;
    char expectHex[129] = {0}, valueHex[9] = {0};
    if (sscanf(line, "%*s %x %128s %x %8s", &at, expectHex, &addr, valueHex) != 4) {
        printf("rompatch: AT EXPECT ADDR VALUE\n");
        return;
    }
    uint8_t expect[64], value[4];
    int el = hexBytes(expectHex, expect, sizeof(expect));
    int vl = hexBytes(valueHex, value, sizeof(value));
    int ok = pk_cheats_patch(core, at, expect, el, addr, value, vl);
    printf("rompatch %08x: %s, rom patched: %s\n", addr, ok ? "in" : "code didn't match", pk_cheats_rom_patched() ? "yes" : "no");
}

static void cmdRomBlocks(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) {
        printf("romblocks: cannot open %s\n", path);
        return;
    }
    pk_cheats_unblock(core);
    static char line[1 << 17];
    static uint8_t data[1 << 16], to[32];
    int blocks = 0, ok = 1, pals = 0;
    while (ok && fgets(line, sizeof(line), f)) {
        unsigned addr = 0, crc = 0;
        if (!strncmp(line, "block ", 6)) {
            char* hex = line + 6;
            if (sscanf(hex, "%x %x", &addr, &crc) != 2) { ok = 0; break; }
            hex = strchr(strchr(hex, ' ') + 1, ' ') + 1;
            hex[strcspn(hex, "\r\n")] = 0;
            int n = hexBytes(hex, data, sizeof(data));
            ok = n > 0 && pk_cheats_block(core, addr, data, n, crc);
            if (!ok) printf("romblocks: block %08x (%d bytes) didn't go in\n", addr, n);
            blocks += ok;
        } else if (!strncmp(line, "pal ", 4)) {
            char* from = line + 4;
            char* sp = strchr(from, ' ');
            if (!sp) continue;
            *sp = 0;
            char* toHex = sp + 1;
            toHex[strcspn(toHex, "\r\n")] = 0;
            int n = hexBytes(from, data, sizeof(data));
            if (hexBytes(toHex, to, sizeof(to)) == 32 && n >= 32) pals += pk_cheats_repalette(core, data, n / 32, to);
        }
    }
    fclose(f);
    if (!ok) pk_cheats_unblock(core);
    printf("romblocks: %d block(s) in, %d palette(s) swapped, rom patched: %s\n", ok ? blocks : 0, pals,
           pk_cheats_rom_patched() ? "yes" : "no");
}

#ifdef HAVE_PK_COOP
#ifdef HAVE_PK_FLIGHT
// pk_flight's way to call the game (pk_flight_call_fn): mgba_dump's own pk_call.
static int flightCall(struct mCore* c, uint32_t fn, const uint32_t args[4], uint32_t* r0) {
    if (!pk_call(c, fn, args)) return 0;
    if (r0) *r0 = pk_call_r0;
    return 1;
}
#endif

// pk_coop's way to call the game (pk_coop_call_fn): mgba_dump's own pk_call, r0 back, 0xFFFFFFFF if it never returned.
static uint32_t coopCall(struct mCore* c, uint32_t fn, const uint32_t args[4]) {
    return pk_call(c, fn, args) ? pk_call_r0 : 0xFFFFFFFFu;
}

// coop-after (plan 4 Task 8): helpers for the coop* result writers' commands.
// The writers call the game (pk_call): frames run until a call can, like `call` - except while the game isn't free on
// the field, where they answer -1 at once and wait for nothing (what the not-free checks look at).
static void afterCap(long steps) {   // the app's pk_coop_cap: the game's save runs ~16M steps
    pk_call_steps = steps > 0 ? steps : 2000000;
}

static void afterWaitSafe(struct mCore* c) {
    pk_coop_after_set_call(coopCall);
    pk_coop_after_set_cap(afterCap);
    if (!pk_coop_on_free_field(c)) return;
    int waited = 0;
    for (; waited < 60 && !pk_call_safe(c); waited++) runFrame();
    if (waited) printf("coop: waited %d frame(s) for a safe point\n", waited);
}

static uint32_t afterHash(struct mCore* c, uint32_t addr, uint32_t len) {   // FNV-1a over the bus bytes
    uint32_t h = 2166136261u;
    for (uint32_t i = 0; i < len; i++) h = (h ^ (uint8_t) c->busRead8(c, addr + i)) * 16777619u;
    return h;
}

static uint32_t afterMoney(struct mCore* c) {   // the game's own GetMoney(&gSaveBlock1Ptr->money)
    const uint32_t args[4] = { (uint32_t) c->busRead32(c, COOP_EM_gSaveBlock1Ptr) + COOP_EM_OFF_SB1_MONEY, 0, 0, 0 };
    return pk_call(c, 0x080E5114u, args) ? pk_call_r0 : 0xFFFFFFFFu;
}

// --- `pair` mode (plan 6 Task 1): two cores of the same ROM in one process, a game each, the link bridge's blocks moved between
// them every frame. The tool's globals (`core`, the video buffer, pk_cheats' ROM patch lists, the pk_call rest depth, the bridge's
// own state) belong to one core at a time: `on a` / `on b` swaps them, so every command works on whichever core is current
// (the same pk_* code runs on both, as it would in two apps). In pair mode every frame run - `wait`, `press`, the waits inside
// commands - runs both cores (A, then B) and then moves the blocks.
struct pairCtx {
    struct mCore* core;
    color_t* video;
    struct pk_fix fixes[PK_MAX_FIXES];
    int fixCount;
    struct pk_fix options[PK_MAX_OPTIONS];
    int optionCount;
    struct pk_block blocks[PK_MAX_BLOCKS];
    int blockCount;
    uint32_t restSp[PK_REST_SLOTS], restCount[PK_REST_SLOTS];
    struct pk_link_ctx link;
    struct lstart_state start;   // task 2
    struct pk_story_ctx story;   // task 6
    struct lend_ctx linkEnd;   // task 5
};
static struct pairCtx pairSaved[2];   // a core's state while it is not the current one
static bool pairOn;
static int pairCur;                   // 0 = a, 1 = b: whose state is in the globals

static void pairSwap(int to) {
    if (!pairOn || to == pairCur) return;
    struct pairCtx* s = &pairSaved[pairCur];
    s->core = core;
    s->video = video;
    memcpy(s->fixes, sFixes, sizeof(sFixes));
    s->fixCount = sFixCount;
    memcpy(s->options, sOptions, sizeof(sOptions));
    s->optionCount = sOptionCount;
    memcpy(s->blocks, sBlocks, sizeof(sBlocks));
    s->blockCount = sBlockCount;
    memcpy(s->restSp, pkRestSp, sizeof(pkRestSp));
    memcpy(s->restCount, pkRestCount, sizeof(pkRestCount));
    s->link = sLink;
    s->start = sStart;
    s->story = sStory;
    s->linkEnd = sEnd;   // task 5
    s = &pairSaved[to];
    core = s->core;
    video = s->video;
    memcpy(sFixes, s->fixes, sizeof(sFixes));
    sFixCount = s->fixCount;
    memcpy(sOptions, s->options, sizeof(sOptions));
    sOptionCount = s->optionCount;
    memcpy(sBlocks, s->blocks, sizeof(sBlocks));
    sBlockCount = s->blockCount;
    memcpy(pkRestSp, s->restSp, sizeof(pkRestSp));
    memcpy(pkRestCount, s->restCount, sizeof(pkRestCount));
    sLink = s->link;
    sStart = s->start;
    sStory = s->story;
    sEnd = s->linkEnd;   // task 5
    pairCur = to;
}

// The blocks in flight, per direction (index = the sending core): delivered when `due` frames have passed (`linkdelay F`).
#define PAIR_Q 128
struct pairMsg { unsigned due; int size, reported; uint8_t d[PK_COOP_LINK_WIRE_HEADER + PK_COOP_LINK_BLOCK_MAX]; };
static struct pairMsg pairQ[2][PAIR_Q];
static int pairQHead[2], pairQCount[2];
static unsigned pairFrame, pairDelay;
static int pairHold;          // task 7: 1 = no block is delivered (`linkhold`)
static int pairStuck[2];      // consecutive frames the head block of a direction was refused
static long pairMoved[2];     // blocks delivered, per sending core
// task 5: blocks a core's game sent once its bridge said ENDED, blocks dropped because the receiver had ended, `never32` watches
static long pairSentAfterEnd[2], pairDroppedLate[2];
static struct { int core; uint32_t addr, val; int hit; char label[32]; } pairNeverW[8];
static int pairNever;
static int pairErrNoted[2];
static int pairNoErr;   // `linknoerror 1`: a block the bridge calls malformed (PK_COOP_LINK_ERROR) is a FAIL, with the block
static unsigned pairEndDelay;   // `linkdelayend F`

static void pairPump(void) {
    pairFrame++;
    int cur = pairCur;
    uint8_t buf[PK_COOP_LINK_WIRE_HEADER + PK_COOP_LINK_BLOCK_MAX];
    for (int c = 0; c < 2; c++) {
        pairSwap(c);
        int n;
        // task 5: `never32 ADDR VAL LABEL` watches (per core): a frame that has the word there is a FAIL, once
        for (int w = 0; w < pairNever; w++) {
            if (pairNeverW[w].core == c && !pairNeverW[w].hit && (uint32_t) core->busRead32(core, pairNeverW[w].addr) == pairNeverW[w].val) {
                pairNeverW[w].hit = 1;
                printf("FAIL never %s: %08X held 0x%X on core %c\n", pairNeverW[w].label, pairNeverW[w].addr, pairNeverW[w].val, 'a' + c);
                expectFailures++;
            }
        }
        if (pk_coop_link_state(core) == PK_COOP_LINK_ERROR && !pairErrNoted[c]) {   // an unclassifiable block the game's own send made (take) also lands here
            uint8_t bb[4 * (6 + PK_COOP_LINK_WIRE_HEADER + PK_COOP_LINK_BLOCK_MAX)];
            int bn = pk_coop_link_bad_blocks(bb, sizeof(bb));
            if (bn >= 6 && bb[2] == 2) {
                pairErrNoted[c] = 1;
                printf("%s link: core %c's game sent a block the bridge cannot classify (src %02x%02x%02x%02x)\n", pairNoErr ? "FAIL" : "note", 'a' + c, bb[9], bb[8], bb[7], bb[6]);
                if (pairNoErr) expectFailures++;
            }
        }
        while ((n = pk_coop_link_take(core, buf, sizeof(buf))) > 0) {
            if (pk_coop_link_state(core) == PK_COOP_LINK_ENDED) pairSentAfterEnd[c]++;
            if (pairQCount[c] >= PAIR_Q) {
                printf("FAIL link: the blocks from core %c pile up\n", 'a' + c);
                expectFailures++;
                break;
            }
            struct pairMsg* m = &pairQ[c][(pairQHead[c] + pairQCount[c]++) % PAIR_Q];
            m->due = pairFrame + pairDelay;
            m->reported = 0;
            // task 5: the block that carries CONTROLLER_ENDLINKBATTLE (a controller message, TO_CONTROLLER) arrives pairEndDelay frames later
            // still: that player's game is held back while the other one ends (`linkdelayend F`)
            if (buf[0] == PK_COOP_LINKB_BUFFER && n > PK_COOP_LINK_WIRE_HEADER + 8 && buf[PK_COOP_LINK_WIRE_HEADER] == COOP_EM_B_COMM_TO_CONTROLLER
                && buf[PK_COOP_LINK_WIRE_HEADER + 8] == COOP_EM_CONTROLLER_CMDS_COUNT - 2) {
                m->due += pairEndDelay;
            }
            m->size = n;
            memcpy(m->d, buf, (size_t) n);
        }
    }
    for (int c = 0; c < 2; c++) {
        pairSwap(1 - c);
        if (pairHold) continue;   // task 7: `linkhold 1`, the line is down: blocks pile up in the queues, none arrives
        while (pairQCount[c] && pairQ[c][pairQHead[c]].due <= pairFrame) {
            struct pairMsg* m = &pairQ[c][pairQHead[c]];
            if (!pk_coop_link_give(core, m->d, m->size)) {
                if (pk_coop_link_state(core) == PK_COOP_LINK_ERROR && !m->reported) {
                    uint8_t bb[4 * (6 + PK_COOP_LINK_WIRE_HEADER + PK_COOP_LINK_BLOCK_MAX)];
                    int bn = pk_coop_link_bad_blocks(bb, sizeof(bb));
                    printf("%s link: core %c refused a block as malformed (check line %d):", pairNoErr ? "FAIL" : "note", 'a' + (1 - c), bn >= 2 ? bb[0] | bb[1] << 8 : -1);
                    for (int b = 0; b < m->size && b < 120; b++) printf(" %02x", m->d[b]);
                    printf("\n");
                    if (pairNoErr) expectFailures++;
                    m->reported = 1;
                }
                if (pk_coop_link_state(core) == PK_COOP_LINK_ENDED) {
                    // task 5: the receiver's battle is over: a late block (the partner's trailing IS_DONE) is dropped, as the app does
                    pairDroppedLate[c]++;
                    pairStuck[c] = 0;
                    pairQHead[c] = (pairQHead[c] + 1) % PAIR_Q;
                    pairQCount[c]--;
                    continue;
                }
                if (++pairStuck[c] == 900) {
                    printf("FAIL link: core %c's block (kind %d seq %d size %d) refused for 900 frames:", 'a' + (1 - c), m->d[0], m->d[1], m->size);
                    for (int b = 0; b < m->size && b < 40; b++) printf(" %02x", m->d[b]);
                    printf("\n");
                    expectFailures++;
                }
                break;
            }
            pairStuck[c] = 0;
            pairMoved[c]++;
            pairQHead[c] = (pairQHead[c] + 1) % PAIR_Q;
            pairQCount[c]--;
        }
    }
    pairSwap(cur);
}

static int pairNoPump;   // task 7: `linkpump 0` = the app's state / take poll is skipped (a slow pump)
static void pairAfterFrame(void) {
    if (!pairOn) return;
    int cur = pairCur;
    pairSwap(1 - cur);
    core->runFrame(core);
    if (core->platform(core) == mPLATFORM_GBA) pk_rest_note(core);
    pk_coop_link_tick(core);   // task 5
    pairSwap(cur);
    if (!pairNoPump) pairPump();
}

static int pairState(int c) {
    int cur = pairCur, s;
    pairSwap(c);
    s = pk_coop_link_state(core);
    pairSwap(cur);
    return s;
}
#else
static void pairAfterFrame(void) {}
#endif

static void cmdRomCheck(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) {
        printf("romcheck: cannot open %s\n", path);
        return;
    }
    fseek(f, 0, SEEK_END);
    size_t size = (size_t) ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t* file = malloc(size);
    if (fread(file, 1, size, f) != size) size = 0;
    fclose(f);
    printf("romcheck raw bus: %d byte(s) differ\n", lastRomDiffs[0] = romDiffs(file, size, false));
    printf("romcheck overlay: %d byte(s) differ\n", lastRomDiffs[1] = romDiffs(file, size, true));
    pk_cheats_swap_rom(core);
    printf("romcheck swapped: %d byte(s) differ\n", lastRomDiffs[2] = romDiffs(file, size, false));
    pk_cheats_swap_rom(core);
    printf("romcheck swapped back: %d byte(s) differ\n", romDiffs(file, size, false));
    free(file);
}
#endif

int main(int argc, char** argv) {
    bool verbose = false;
    const char* args[8];
    int nargs = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--verbose")) {
            verbose = true;
        } else if (nargs < (int) (sizeof(args) / sizeof(args[0]))) {
            args[nargs++] = argv[i];
        }
    }
    if (nargs < 1) {
        fprintf(stderr, "usage: %s [--verbose] <rom.gba> [save.sav] < commands.txt\n", argv[0]);
        return 2;
    }
    if (!verbose) {
        mLogSetDefaultLogger(&silentLogger);
    }
    const char* romPath = args[0];
    const char* savePath = nargs > 1 ? args[1] : NULL;

    struct VFile* rom = VFileOpen(romPath, O_RDONLY);
    if (!rom) {
        fprintf(stderr, "mgba_dump: cannot open ROM %s\n", romPath);
        return 1;
    }
    core = mCoreFindVF(rom);
    if (!core) {
        fprintf(stderr, "mgba_dump: unrecognized ROM %s\n", romPath);
        return 1;
    }
    if (!core->init(core)) {
        fprintf(stderr, "mgba_dump: core->init failed\n");
        return 1;
    }
    mCoreInitConfig(core, NULL);
    if (core->platform(core) == mPLATFORM_GB) {
        // As the app (pokedaisy_jni.c's pk_gb_config): no SGB border, the 160x144 screen.
        mCoreConfigSetIntValue(&core->config, "sgb.borders", 0);
        core->reloadConfigOption(core, "sgb.borders", &core->config);
    }

    unsigned w, h;
    core->desiredVideoDimensions(core, &w, &h);
    videoW = w;
    videoH = h;
    video = calloc((size_t) w * h, sizeof(color_t));
    core->setVideoBuffer(core, video, w);
    core->setAudioBufferSize(core, 2048);
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), SAMPLE_RATE);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), SAMPLE_RATE);
#ifdef HAVE_PK_GB_AUDIO
    pk_gb_audio_attach(&gbAudio, core, SAMPLE_RATE);
#endif

    if (!core->loadROM(core, rom)) {
        fprintf(stderr, "mgba_dump: core->loadROM failed\n");
        return 1;
    }

    struct VFile* saveVf = NULL;
    if (savePath) {
        // O_RDWR, not O_RDONLY: the game can write to its own flash/SRAM
        // during normal play (autosave, etc), and mGBA's flash-write path
        // doesn't tolerate a read-only backing VFile gracefully (segfaults on
        // the second in-game write in testing here) - always pass a
        // disposable COPY of a save, never the user's real one, since this
        // will genuinely mutate it.
        saveVf = VFileOpen(savePath, O_RDWR);
        if (saveVf) {
            core->loadSave(core, saveVf);
        } else {
            fprintf(stderr, "mgba_dump: warning: cannot open save %s, continuing without\n", savePath);
        }
    }

    core->reset(core);
    fprintf(stderr, "mgba_dump: core ready (%ux%u, %d Hz)\n", w, h, core->frequency(core));

    char line[8192];   // a scenic FLY route (`flightroute`) is one line
    while (fgets(line, sizeof(line), stdin)) {
        char* nl = strchr(line, '\n');
        if (nl) *nl = 0;
        if (line[0] == '#' || line[0] == '\0') continue;

        char cmd[32] = {0};
        char arg1[256] = {0};
        int n = 0;
        if (sscanf(line, "%31s", cmd) != 1) continue;

        if (!strcmp(cmd, "wait")) {
            sscanf(line, "%*s %d", &n);
            for (int i = 0; i < n; i++) runFrame();
        } else if (!strcmp(cmd, "keys")) {
            sscanf(line, "%*s %255s", arg1);
            core->setKeys(core, strcmp(arg1, "NONE") ? parseKeys(arg1) : 0);
        } else if (!strcmp(cmd, "press")) {
            sscanf(line, "%*s %255s", arg1);
            uint32_t mask = parseKeys(arg1);
            core->setKeys(core, mask);
            runFrame();
            core->setKeys(core, 0);
            runFrame();
        } else if (!strcmp(cmd, "hold")) {
            sscanf(line, "%*s %255s %d", arg1, &n);
            uint32_t mask = parseKeys(arg1);
            core->setKeys(core, mask);
            for (int i = 0; i < n; i++) runFrame();
            core->setKeys(core, 0);
            runFrame();
        } else if (!strcmp(cmd, "dump")) {
            sscanf(line, "%*s %255s", arg1);
            cmdDump(arg1);
        } else if (!strcmp(cmd, "loadstate")) {
            // An mGBA savestate (the app's states/<crc>/resume, a slot): where the player was, to look at headless.
            // Never its save data (SAVESTATE_SAVEDATA), and never against a rebuilt ROM (stale code pointers).
            sscanf(line, "%*s %255s", arg1);
            struct VFile* sf = VFileOpen(arg1, O_RDONLY);
            bool ok = sf && mCoreLoadStateNamed(core, sf, SAVESTATE_RTC);
            if (sf) sf->close(sf);
            printf("loadstate: %s %s (frame %u)\n", arg1, ok ? "loaded" : "FAILED", core->frameCounter(core));
        } else if (!strcmp(cmd, "savestate")) {
            // The other half of `loadstate`: play a new game in steps (each run picks up where the last left
            // off). Without the save data, like loadstate: the save file passed on the command line keeps it.
            sscanf(line, "%*s %255s", arg1);
            struct VFile* sf = VFileOpen(arg1, O_CREAT | O_TRUNC | O_RDWR);
            bool ok = sf && mCoreSaveStateNamed(core, sf, SAVESTATE_RTC);
            if (sf) sf->close(sf);
            printf("savestate: %s %s (frame %u)\n", arg1, ok ? "written" : "FAILED", core->frameCounter(core));
        } else if (!strcmp(cmd, "vdump")) {
            sscanf(line, "%*s %255s", arg1);
            cmdVdump(arg1);
        } else if (!strcmp(cmd, "money")) {
            // money: retail Emerald's money (SaveBlock1 +0x490 ^ SaveBlock2's encryption key +0xAC), printed as "money N".
            uint32_t sb1 = core->busRead32(core, 0x03005D8C), sb2 = core->busRead32(core, 0x03005D90);
            printf("money %u\n", core->busRead32(core, sb1 + 0x490) ^ core->busRead32(core, sb2 + 0xAC));
        } else if (!strcmp(cmd, "flag")) {
            // flag ID: retail Emerald's event flag ID (SaveBlock1 +0x1270), printed as "flag ID 0|1".
            char a[64] = {0};
            sscanf(line, "%*s %63s", a);
            uint32_t id = (uint32_t) strtoul(a, NULL, 0), sb1 = core->busRead32(core, 0x03005D8C);
            printf("flag %u %d\n", id, (core->busRead8(core, sb1 + 0x1270 + id / 8) >> (id % 8)) & 1);
        } else if (!strcmp(cmd, "poke8p") || !strcmp(cmd, "peek8p")) {
            // poke8p PTR OFF VAL / peek8p PTR OFF: the byte at *(u32 *)PTR + OFF -
            // for save-block fields, since Emerald moves its save blocks on every load.
            char p[64] = {0}, o[64] = {0}, v[64] = {0};
            sscanf(line, "%*s %63s %63s %63s", p, o, v);
            uint32_t addr = core->busRead32(core, (uint32_t) strtoul(p, NULL, 0)) + (uint32_t) strtoul(o, NULL, 0);
            if (cmd[1] == 'o') core->busWrite8(core, addr, (uint8_t) strtoul(v, NULL, 0));
            else printf("%08x: %02x\n", addr, core->busRead8(core, addr));
        } else if (!strncmp(cmd, "poke", 4) && strcmp(cmd, "pokelog")) {
            char a[64] = {0}, v[64] = {0};
            sscanf(line, "%*s %63s %63s", a, v);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0), val = (uint32_t) strtoul(v, NULL, 0);
            if (!strcmp(cmd, "poke8")) core->busWrite8(core, addr, (uint8_t) val);
            else if (!strcmp(cmd, "poke16")) core->busWrite16(core, addr, (uint16_t) val);
            else if (!strcmp(cmd, "poke32")) core->busWrite32(core, addr, val);
            else fprintf(stderr, "mgba_dump: unknown command '%s'\n", cmd);
        } else if (!strcmp(cmd, "calllimit")) {
            // calllimit N: the step cap of one pk_call (2,000,000 by default; the game's save runs far longer)
            sscanf(line, "%*s %ld", &pk_call_steps);
            printf("calllimit %ld\n", pk_call_steps);
        } else if (!strcmp(cmd, "call")) {
            // call ADDR [ARG0 [ARG1 [ARG2 [ARG3]]]]: run Thumb function ADDR with
            // r0-r3 = the args (0 when left out) to its return, then resume exactly
            // where the CPU was - the app's pkRenderForceSong (e.g. `call 0x081dd164
            // 297` = m4aSongNumStart), or a game's VarSet(id, value), or FieldItems'
            // ExecuteTableBasedItemEffect(mon, item, partyIndex, moveIndex).
            char a[64] = {0}, v[4][64] = {{0}};
            int got = sscanf(line, "%*s %63s %63s %63s %63s %63s", a, v[0], v[1], v[2], v[3]);
            uint32_t args[4] = {0};
            for (int i = 0; i < 4 && i < got - 1; i++) args[i] = (uint32_t) strtoul(v[i], NULL, 0);
            int waited = 0;
            for (; waited < 60 && !pk_call_safe(core); waited++) runFrame();
            if (waited) printf("call: waited %d frame(s) for a safe point\n", waited);
            // `*TABLE`: the function is the pointer stored at TABLE (a game's function table entry - one of co-op's hooks
            // sits wherever the code area puts it, so its test names the table slot instead of an address)
            uint32_t fnAddr = a[0] == '*' ? (uint32_t) core->busRead32(core, (uint32_t) strtoul(a + 1, NULL, 0))
                                          : (uint32_t) strtoul(a, NULL, 0);
            bool ok = pk_call(core, fnAddr, args);
            if (pk_call_steps != 2000000) printf("call: %ld steps (the cap is %ld)\n", pk_call_used, pk_call_steps);
            if (ok) printf("call %s(%s %s %s %s): returned 0x%X\n", a, v[0], v[1], v[2], v[3], pk_call_r0);
            else printf("call %s(%s %s %s %s): DID NOT RETURN\n", a, v[0], v[1], v[2], v[3]);
        } else if (!strcmp(cmd, "cpu")) {
            // cpu: the CPU's mode, PC, SP and the banked stack pointers where the frame ended - what a
            // pk_call starts from (a frame ends at VBlank, often with the game halted in the BIOS).
            struct ARMCore* c = (struct ARMCore*) core->cpu;
            printf("cpu: mode=0x%02X %s pc=0x%08X sp=0x%08X halted=%d i=%d sp_irq=0x%08X sp_svc=0x%08X sp_sys=0x%08X\n",
                   c->privilegeMode, c->executionMode == MODE_THUMB ? "thumb" : "arm", (uint32_t) c->gprs[ARM_PC],
                   (uint32_t) c->gprs[ARM_SP], c->halted, c->cpsr.i,
                   c->privilegeMode == MODE_IRQ ? (uint32_t) c->gprs[ARM_SP] : (uint32_t) c->bankedRegisters[BANK_IRQ][0],
                   c->privilegeMode == MODE_SUPERVISOR ? (uint32_t) c->gprs[ARM_SP] : (uint32_t) c->bankedRegisters[BANK_SUPERVISOR][0],
                   (c->privilegeMode == MODE_SYSTEM || c->privilegeMode == MODE_USER) ? (uint32_t) c->gprs[ARM_SP] : (uint32_t) c->bankedRegisters[BANK_NONE][0]);
        } else if (!strcmp(cmd, "watch8")) {
            // watch8 ADDR MAXSTEPS: single-steps the current core (the other one stands still) until the byte at ADDR changes: prints
            // `watch8 ADDR OLD -> NEW pc PC lr LR r0 R0` (the instruction that wrote it has just run), or `watch8 ADDR unchanged`
            char a[64] = {0};
            long max = 0;
            sscanf(line, "%*s %63s %ld", a, &max);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0);
            uint8_t old = core->busRead8(core, addr);
            struct ARMCore* cpu = (struct ARMCore*) core->cpu;
            long i = 0;
            for (; i < max; i++) {
                core->step(core);
                uint8_t now = core->busRead8(core, addr);
                if (now != old) {
                    printf("watch8 %08X %02X -> %02X pc %08X lr %08X r0 %08X after %ld step(s)\n", addr, old, now,
                           (uint32_t) cpu->gprs[ARM_PC], (uint32_t) cpu->gprs[ARM_LR], (uint32_t) cpu->gprs[0], i);
                    break;
                }
            }
            if (i == max) printf("watch8 %08X unchanged\n", addr);
        } else if (!strcmp(cmd, "stepinto")) {
            // stepinto REGION: step the CPU until it runs code in that memory region (0 = BIOS, 3 = IWRAM: the
            // interrupt handler) - a frame that ends there is what a pk_call must come back to.
            int region = 3;
            sscanf(line, "%*s %i", &region);
            struct ARMCore* c = (struct ARMCore*) core->cpu;
            int i = 0;
            for (; i < 2000000 && ((uint32_t) c->gprs[ARM_PC] >> 24) != (uint32_t) region; i++) core->step(core);
            printf("stepinto %d: pc=0x%08X after %d steps\n", region, (uint32_t) c->gprs[ARM_PC], i);
        } else if (!strcmp(cmd, "stacksnap") || !strcmp(cmd, "stackdiff")) {
            // stacksnap / stackdiff: IWRAM's top 1 KB (every stack the game has) before and after a call -
            // stackdiff prints the lowest and highest addresses that changed.
            static uint8_t snap[0x400];
            uint8_t now[0x400];
            for (int i = 0; i < 0x400; i++) now[i] = core->rawRead8(core, 0x03007C00 + i, -1);
            if (!strcmp(cmd, "stacksnap")) memcpy(snap, now, sizeof(snap));
            else {
                int lo = -1, hi = -1;
                for (int i = 0; i < 0x400; i++) if (now[i] != snap[i]) { if (lo < 0) lo = i; hi = i; }
                if (lo < 0) printf("stackdiff: nothing changed\n");
                else printf("stackdiff: 0x%08X .. 0x%08X changed\n", 0x03007C00 + lo, 0x03007C00 + hi);
            }
        } else if (!strcmp(cmd, "memcopy")) {
            // memcopy SRC DST LEN: copy LEN bytes of RAM - a save's lead Pokémon into the empty party slots.
            char a[64] = {0}, d[64] = {0};
            uint32_t len = 0;
            sscanf(line, "%*s %63s %63s %u", a, d, &len);
            uint32_t src = (uint32_t) strtoul(a, NULL, 0), dst = (uint32_t) strtoul(d, NULL, 0);
            for (uint32_t i = 0; i < len; i++) core->busWrite8(core, dst + i, core->busRead8(core, src + i));
        } else if (!strcmp(cmd, "memsave")) {
            // memsave ADDR LEN FILE: write LEN bytes of RAM to FILE - a save's own party slots as a co-op partner's
            // Pokémon, made in the work dir and never committed.
            char a[64] = {0};
            uint32_t len = 0;
            sscanf(line, "%*s %63s %u %255s", a, &len, arg1);
            uint32_t src = (uint32_t) strtoul(a, NULL, 0);
            FILE* f = fopen(arg1, "wb");
            for (uint32_t i = 0; f && i < len; i++) fputc(core->busRead8(core, src + i), f);
            if (f) fclose(f);
            printf("memsave %08x (%u bytes) -> %s%s\n", src, len, arg1, f ? "" : ": cannot write");
        } else if (!strcmp(cmd, "reown")) {
            // reown MON OTID: give a struct Pokemon (100 bytes in RAM) another OT ID, keeping its data valid - the
            // 48 substructure bytes (+0x20) are XORed with personality ^ OT ID, so every word is XORed with old ^ new.
            char a[64] = {0}, o[64] = {0};
            sscanf(line, "%*s %63s %63s", a, o);
            uint32_t mon = (uint32_t) strtoul(a, NULL, 0), id = (uint32_t) strtoul(o, NULL, 0);
            // the old OT ID XOR the new one over the 48 encrypted bytes (the checksum, a sum of the decrypted words, stays valid); only the OT ID changes
            uint32_t old = 0;
            for (int i = 0; i < 4; i++) old |= (uint32_t) core->busRead8(core, mon + 4u + (uint32_t) i) << (8 * i);
            for (int i = 0; i < 48; i++) {
                core->busWrite8(core, mon + 0x20u + (uint32_t) i, (uint8_t) (core->busRead8(core, mon + 0x20u + (uint32_t) i) ^ (uint8_t) ((old ^ id) >> (8 * (i & 3)))));
            }
            for (int i = 0; i < 4; i++) core->busWrite8(core, mon + 4u + (uint32_t) i, (uint8_t) (id >> (8 * i)));
            printf("reown %08x -> OT ID %08x\n", mon, id);
        } else if (!strcmp(cmd, "memsnap") || !strcmp(cmd, "memrestore")) {
            // memsnap ADDR LEN / memrestore: copy LEN bytes (up to 256) and write them back later -
            // FieldItems' dry run of a party Pokémon's item effect (call, then put its struct back).
            static uint32_t snapAddr, snapLen;
            static uint8_t snap[256];
            if (cmd[3] == 's') {
                char a[64] = {0};
                sscanf(line, "%*s %63s %u", a, &snapLen);
                snapAddr = (uint32_t) strtoul(a, NULL, 0);
                if (snapLen > sizeof snap) snapLen = sizeof snap;
                for (uint32_t i = 0; i < snapLen; i++) snap[i] = core->busRead8(core, snapAddr + i);
            } else {
                for (uint32_t i = 0; i < snapLen; i++) core->busWrite8(core, snapAddr + i, snap[i]);
            }
            printf("%s %08x (%u bytes)\n", cmd, snapAddr, snapLen);
        } else if (!strcmp(cmd, "park")) {
            char a[64] = {0};
            sscanf(line, "%*s %63s", a);
            printf("park %s: %s\n", a, pk_park(core, (uint32_t) strtoul(a, NULL, 0)) ? "parked" : "FAILED");
        } else if (!strcmp(cmd, "gbpark")) {
            char a[64] = {0};
            sscanf(line, "%*s %63s", a);
            printf("gbpark %s: %s\n", a, gb_park(core, (uint16_t) strtoul(a, NULL, 0)) ? "parked" : "FAILED");
        } else if (!strcmp(cmd, "gbcall")) {
            char f[64] = {0}, a[64] = {0}, c[64] = {0}, r[64] = {0};
            sscanf(line, "%*s %63s %63s %63s %63s", f, a, c, r);
            bool ok = gb_call(core, (uint16_t) strtoul(f, NULL, 0), (uint8_t) strtoul(a, NULL, 0), (uint8_t) strtoul(c, NULL, 0), (uint16_t) strtoul(r, NULL, 0));
            printf("gbcall %s(a=%s, c=%s): %s\n", f, a, c, ok ? "returned" : "DID NOT RETURN");
        } else if (!strcmp(cmd, "gbfar")) {
            unsigned bank = 0, addr = 0, a = 0, bc = 0, de = 0, hl = 0;
            sscanf(line, "%*s %x %x %x %x %x %x", &bank, &addr, &a, &bc, &de, &hl);
            uint8_t outA = 0;
            bool ok = gb_far(core, (uint8_t) bank, (uint16_t) addr, (uint8_t) a, (uint16_t) bc, (uint16_t) de, (uint16_t) hl, 0xFF9D, &outA);
            printf("gbfar %02x:%04x: %s, a=%02x\n", bank, addr, ok ? "returned" : "DID NOT RETURN", outA);
        } else if (!strcmp(cmd, "gbpeek")) {
            unsigned bank = 0, addr = 0, cnt = 1;
            sscanf(line, "%*s %x %x %x", &bank, &addr, &cnt);
            uint8_t buf[256];
            if (cnt > sizeof buf) cnt = sizeof buf;
            if (bank >= 1 && addr >= 0xD000 && addr < 0xE000 && pk_gb_read_bank(core, bank, (uint16_t) addr, buf, (int) cnt)) {
            } else for (unsigned k = 0; k < cnt; k++) buf[k] = core->busRead8(core, (uint16_t) (addr + k));
            printf("gbpeek %x:%04x:", bank, addr);
            for (unsigned k = 0; k < cnt; k++) printf(" %02x", buf[k]);
            printf("\n");
        } else if (!strcmp(cmd, "gbcallat")) {
            // gbcallat AT FN A B HL: the app's pkGbCallAt - Yellow's PC anywhere (Bankswitch into the PC tile's routine
            // from the overworld loop).
            unsigned at = 0, fn = 0, a = 0, b = 0, hl = 0;
            sscanf(line, "%*s %x %x %x %x %x", &at, &fn, &a, &b, &hl);
            bool ok = pk_gb_call_at(core, (uint16_t) at, (uint16_t) fn, (uint8_t) a, (uint8_t) b, (uint16_t) hl, 2);
            printf("gbcallat %04x -> %04x(a=%02x): %s\n", at, fn, a, ok ? "called" : "THE LOOP DIDN'T COME BY");
        } else if (!strcmp(cmd, "gbinject")) {
            // gbinject TRAMP BANK ADDR [AT]: make the game itself call BANK:ADDR from where it waits for the next frame,
            // interrupts on, so a routine that runs for many frames (a script's battle) plays out in the following
            // `wait`s. TRAMP is free RAM (C000-CFFF) for a trampoline that saves the registers, far-calls through the
            // pokecrystal family's `rst $08` (bank and address inline) and returns to the interrupted code. Only
            // while the CPU sits in a HALT (the frame wait), or once it reaches AT (DelayFrame's own `halt`, which
            // mGBA may step over in one go): how Prism's trainer battle was started headless.
            unsigned tramp = 0, bank = 0, addr = 0, at = 0;
            sscanf(line, "%*s %x %x %x %x", &tramp, &bank, &addr, &at);
            struct SM83Core* cpu = (struct SM83Core*) core->cpu;
            bool there = false;
            for (int i = 0; i < 2000000 && !there; i++) {
                there = at ? cpu->pc == at : cpu->halted;
                if (!there) core->step(core);
            }
            if (!there) {
                printf("gbinject: the CPU never got there\n");
            } else {
                const uint8_t code[] = {0xF5, 0xC5, 0xD5, 0xE5, 0xCF, (uint8_t) bank, (uint8_t) (addr & 0xFF), (uint8_t) (addr >> 8),
                                        0xE1, 0xD1, 0xC1, 0xF1, 0xC9};
                for (unsigned k = 0; k < sizeof code; k++) core->busWrite8(core, (uint16_t) (tramp + k), code[k]);
                uint16_t back = cpu->pc;
                cpu->sp -= 2;
                core->busWrite8(core, cpu->sp, back & 0xFF);
                core->busWrite8(core, (uint16_t) (cpu->sp + 1), back >> 8);
                cpu->halted = false;
                cpu->pc = (uint16_t) tramp;
                cpu->memory.setActiveRegion(cpu, cpu->pc);
                printf("gbinject %02x:%04x via %04x (back to %04x)\n", bank, addr, tramp, back);
            }
        } else if (!strcmp(cmd, "gbvdump")) {
            // gbvdump DIR: a Game Boy's VRAM (both Color banks, 0x2000 each), OAM, and the Color palettes (32 BG
            // colours then 32 OBJ, as mGBA holds them) - what a sprite is drawn in (how Prism's icon colours were found).
            char d[256] = {0}, path[300];
            sscanf(line, "%*s %255s", d);
            struct GB* gb = (struct GB*) core->board;
            FILE* f;
            snprintf(path, sizeof path, "%s/gbvram.bin", d);
            if ((f = fopen(path, "wb"))) { fwrite(gb->video.vram, 1, 0x4000, f); fclose(f); }
            snprintf(path, sizeof path, "%s/gboam.bin", d);
            if ((f = fopen(path, "wb"))) { for (int k = 0; k < 0xA0; k++) fputc(core->busRead8(core, (uint16_t) (0xFE00 + k)), f); fclose(f); }
            snprintf(path, sizeof path, "%s/gbpal.bin", d);
            if ((f = fopen(path, "wb"))) { fwrite(gb->video.palette, 2, 64, f); fclose(f); }
            printf("gbvdump %s\n", d);
        } else if (!strcmp(cmd, "gbpoke")) {
            // gbpoke BANK ADDR BYTE..: WRAM bank BANK's bytes at ADDR (D000-DFFF; C000-CFFF ignores the bank).
            char* p = line;
            unsigned bank = 0, addr = 0;
            int used = 0;
            sscanf(p, "%*s %x %x%n", &bank, &addr, &used);
            p += used;
            uint8_t oldSvbk = core->busRead8(core, 0xFF70);
            core->busWrite8(core, 0xFF70, (uint8_t) bank);
            unsigned v;
            int k = 0;
            while (sscanf(p, " %x%n", &v, &used) == 1) { core->busWrite8(core, (uint16_t) (addr + k++), (uint8_t) v); p += used; }
            core->busWrite8(core, 0xFF70, oldSvbk);
            printf("gbpoke %x:%04x: %d bytes\n", bank, addr, k);
        } else if (!strcmp(cmd, "gbloop")) {
            char what[16] = {0};
            sscanf(line, "%*s %d %15s", &n, what);
            gb_loop(core, n, !strcmp(what, "crystal") ? 1 : !strcmp(what, "gold") ? 2 : !strcmp(what, "polished") ? 3 : 0);
        } else if (!strcmp(cmd, "peek32")) {
            char a[64] = {0};
            sscanf(line, "%*s %63s", a);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0);
            printf("%08x: %08x\n", addr, core->busRead32(core, addr));
        } else if (!strcmp(cmd, "bgm")) {
            // The m4a BGM player (FfMusicKey): tail of SoundInfo's player chain.
            uint32_t info = core->busRead32(core, 0x03007FF0), p = core->busRead32(core, info + 0x24), bgm = 0;
            for (int i = 0; p && i < 12; i++) { bgm = p; p = core->busRead32(core, p + 0x3C); }
            printf("bgm soundInfo=%08x player=%08x status=%08x header=%08x ident=%08x\n", info, bgm,
                   bgm ? core->busRead32(core, bgm + 4) : 0, bgm ? core->busRead32(core, bgm) : 0,
                   bgm ? core->busRead32(core, bgm + 0x34) : 0);
        } else if (!strcmp(cmd, "volume")) {
            // mGBA's master volume (0x100 = full): two recordings at two volumes show where the mixer wraps.
            sscanf(line, "%*s %d", &n);
            mCoreConfigSetIntValue(&core->config, "volume", n);
            core->reloadConfigOption(core, "volume", &core->config);
            printf("volume: %d\n", n);
#ifdef HAVE_PK_GB_AUDIO
        } else if (!strcmp(cmd, "gbaudio")) {
            // 1 (the default) = a Game Boy game's sound resampled as the app does (pk_gb_audio.c); 0 = mGBA's own.
            sscanf(line, "%*s %d", &n);
            pk_gb_audio_detach(&gbAudio, core);
            if (n) pk_gb_audio_attach(&gbAudio, core, SAMPLE_RATE);
            printf("gbaudio: %s\n", gbAudio.left ? "the app's" : "mGBA's own");
#endif
        } else if (!strcmp(cmd, "wav")) {
            sscanf(line, "%*s %255s %d", arg1, &n);
            cmdWav(arg1, n);
#ifdef HAVE_PK_CHEATS
        } else if (!strcmp(cmd, "cheat")) {
            char t[16] = {0};
            sscanf(line, "%*s %15s %255[^\n]", t, arg1);
            cmdCheat(t, arg1);
        } else if (!strcmp(cmd, "cheatclear")) {
            cheatText[0] = '\0';
            pk_cheats_clear(core);
            printf("cheatclear: rom patched: %s\n", pk_cheats_rom_patched() ? "yes" : "no");
        } else if (!strcmp(cmd, "rompatch")) {
            cmdRomPatch(line);
        } else if (!strcmp(cmd, "romunpatch")) {
            pk_cheats_unpatch(core);
            printf("romunpatch: rom patched: %s\n", pk_cheats_rom_patched() ? "yes" : "no");
        } else if (!strcmp(cmd, "romblocks")) {
            sscanf(line, "%*s %255[^\n]", arg1);
            cmdRomBlocks(arg1);
        } else if (!strcmp(cmd, "romunblock")) {
            pk_cheats_unblock(core);
            printf("romunblock: rom patched: %s\n", pk_cheats_rom_patched() ? "yes" : "no");
        } else if (!strcmp(cmd, "romcheck")) {
            sscanf(line, "%*s %255[^\n]", arg1);
            cmdRomCheck(arg1);
#endif
#ifdef HAVE_PK_WIDE
        } else if (!strcmp(cmd, "wideon")) {
            unsigned crcArg = 0;
            sscanf(line, "%*s %d %x", &n, &crcArg);
            // The game by its ROM's CRC32, as the app picks it (pk_wide_games.h): the file's bytes. Not the ROM in
            // memory - mGBA keeps a cart's GPIO registers (the RTC of Emerald, Unbound...) inside its ROM buffer at
            // 0xC4, so once the game has read its clock the buffer (and the bus) no longer hash as the file does.
            // A CRC after the margin is used instead: the retail one the app hands over for a randomized retail cart
            // (WideScreen.tablesCrc).
            uint32_t crc = crcArg;
            FILE* rf = crc ? NULL : fopen(romPath, "rb");
            if (rf) {
                fseek(rf, 0, SEEK_END);
                long fsz = ftell(rf);
                fseek(rf, 0, SEEK_SET);
                uint8_t* fb = fsz > 0 ? malloc((size_t) fsz) : NULL;
                if (fb && fread(fb, 1, (size_t) fsz, rf) == (size_t) fsz) crc = doCrc32(fb, (size_t) fsz);
                free(fb);
                fclose(rf);
            }
            wideOn = pk_wide_configure(crc);
            partnerTrack = pk_field_configure(crc) != 0;   // fieldinfo's reads: the same table
            partnerOk = partnerTrack ? pk_partner_self(core, partnerSelf) : 0;
            wideView = (struct PkWideView) { -n, 0, pk_wide_frame_w() + 2 * n, pk_wide_frame_h() };
            memset(&wideStats, 0, sizeof(wideStats));
            printf("wideon: ROM %08x %s, margin %d\n", crc, wideOn ? "known" : "NOT KNOWN (WIDE off)", n);
        } else if (!strcmp(cmd, "widerow")) {
            // A hack proven to keep a retail engine (RetailEngine.kt): its row as the app hands it to pk_wide.c, by the
            // file's CRC32 - `scripts/gen_retail_engine.py --row HACK` prints the line. Then `wideon` as usual.
            unsigned base = 0;
            char list[2048] = { 0 };
            int32_t row[PK_WIDE_ADDRS];
            int count = 0;
            if (sscanf(line, "%*s %x %2047s", &base, list) == 2) {
                for (char* t = strtok(list, ","); t && count < PK_WIDE_ADDRS; t = strtok(NULL, ","))
                    row[count++] = (int32_t) strtol(t, NULL, 16);
            }
            uint32_t crc = 0;
            FILE* rf = fopen(romPath, "rb");
            if (rf) {
                fseek(rf, 0, SEEK_END);
                long fsz = ftell(rf);
                fseek(rf, 0, SEEK_SET);
                uint8_t* fb = fsz > 0 ? malloc((size_t) fsz) : NULL;
                if (fb && fread(fb, 1, (size_t) fsz, rf) == (size_t) fsz) crc = doCrc32(fb, (size_t) fsz);
                free(fb);
                fclose(rf);
            }
            int ok = pk_wide_add_derived(crc, base, row, count);
            printf("widerow: ROM %08x on %08x's engine, %d values: %s\n", crc, base, count, ok ? "set" : "REFUSED");
        } else if (!strcmp(cmd, "widefuzz")) {
            // widefuzz N SEED: N frames of abuse for pk_wide - a random window each frame (empty, huge, far off) and
            // random bytes / pointers written over the RAM it reads (VMap, gMapHeader, the camera, object events,
            // sprites, the sprite order, SaveBlock1). The game may well crash inside the emulator; pk_wide mustn't
            // (build with -fsanitize=address,undefined -DPK_WIDE_NO_GUARD to have every bad read reported).
            unsigned seed = 1;
            sscanf(line, "%*s %d %u", &n, &seed);
            srand(seed);
            const int slots[] = { PK_WIDE_VMAP, PK_WIDE_MAP_HEADER, PK_WIDE_CAMERA_OFFSET, PK_WIDE_OBJECT_EVENTS,
                                  PK_WIDE_SPRITES, PK_WIDE_COORD_OFFSET, PK_WIDE_SPRITE_ORDER, PK_WIDE_SAVE_BLOCK1_PTR,
                                  PK_WIDE_SPRITE_PAL_TAGS, PK_WIDE_SB1_TEMPLATES };
            for (int k = 0; k < n; k++) {
                wideView = (struct PkWideView) { rand() % 2000 - 1000, rand() % 1400 - 700, rand() % 900 - 20, rand() % 900 - 20 };
                for (int m = rand() % 6; m > 0; m--) {
                    int slot = slots[rand() % 10];
                    uint32_t base = (uint32_t) C[slot];
                    if (slot == PK_WIDE_SB1_TEMPLATES) {   // the far-off objects' templates, flags and vars in SaveBlock1
                        uint32_t sb1 = C[PK_WIDE_SAVE_STATIC] ? (uint32_t) C[PK_WIDE_SAVE_BLOCK1_PTR]
                                                              : r32((uint32_t) C[PK_WIDE_SAVE_BLOCK1_PTR]);
                        base = base && sb1 ? sb1 + base : 0;
                    }
                    if (!base) continue;
                    uint32_t at = base + (uint32_t) (rand() % 0x800);
                    if (rand() % 2) {
                        const uint32_t ptrs[] = { 0x02000000u, 0x03000000u, 0x08000000u, 0x09000000u, 0x0E000000u, 0 };
                        uint32_t v = ptrs[rand() % 6] | ((uint32_t) rand() & 0x01FFFFFFu);
                        if (rand() % 4 == 0) v = (uint32_t) rand() * 2654435761u;
                        core->busWrite32(core, at & ~3u, v);
                    } else {
                        core->busWrite8(core, at, (uint8_t) rand());
                    }
                }
                if (rand() % 50 == 0) pk_wide_reset();
                runFrame();
            }
            printf("widefuzz: %d frames done (seed %u), fault %d\n", n, seed, pk_wide_fault());
        } else if (!strcmp(cmd, "wideview")) {
            // wideview X Y W H: draw this window (the frame's pixels, its top-left (0, 0)) - ZOOM / look-around.
            struct PkWideView v = { 0, 0, 0, 0 };
            sscanf(line, "%*s %d %d %d %d", &v.x, &v.y, &v.w, &v.h);
            if (v.w > PK_WIDE_MAX_VIEW_W) v.w = PK_WIDE_MAX_VIEW_W;
            if (v.h > PK_WIDE_MAX_VIEW_H) v.h = PK_WIDE_MAX_VIEW_H;
            if (v.w > 0 && v.h > 0) wideView = v;
        } else if (!strcmp(cmd, "wideskip")) {
            // wideskip N: fast-forward's way from now on - only every Nth frame drawn, the others noted (0 / 1: all drawn).
            sscanf(line, "%*s %d", &n);
            wideSkipEvery = n;
            wideSkipCount = 0;
        } else if (!strcmp(cmd, "widecheck")) {
            char what[16] = {0};
            sscanf(line, "%*s %15s", what);
            wideCheck = true;
            wideCheckBattles = !strcmp(what, "all");
            wideDumped = 0;   // each widecheck saves its own first few misses
            memset(&wideStats, 0, sizeof(wideStats));
        } else if (!strcmp(cmd, "widestats")) {
            printf("wide: %d frames, %d battle, %d unverified (ambient edges), %d with torn sprite lists; centre checked on %d: "
                   "%d exact, worst %d px (frame %d); %.0f us a frame on average, %.0f at most%s\n", wideStats.frames,
                   wideStats.battle, wideStats.unverified, wideStats.torn, wideStats.checked, wideStats.exact, wideStats.worst,
                   wideStats.worstFrame, wideStats.frames ? wideStats.ns / wideStats.frames / 1000 : 0,
                   wideStats.maxNs / 1000, wideCheck ? " (with the centre check)" : "");
        } else if (!strcmp(cmd, "widecentre")) {
            // The last frame's rebuild of the middle 240x160 (widecheck on) as a PPM, to diff with `shot`.
            sscanf(line, "%*s %255s", arg1);
            FILE* f = fopen(arg1, "wb");
            if (f) {
                fprintf(f, "P6\n%d %d\n255\n", pk_wide_frame_w(), pk_wide_frame_h());
                for (int i = 0; i < pk_wide_frame_w() * pk_wide_frame_h(); i++) {
                    uint8_t rgb[3] = { (uint8_t) wideCentre[i], (uint8_t) (wideCentre[i] >> 8), (uint8_t) (wideCentre[i] >> 16) };
                    fwrite(rgb, 1, 3, f);
                }
                fclose(f);
            }
        } else if (!strcmp(cmd, "widerec")) {
            // N frames (keys as `keys` holds them), every wide frame's RGB appended to FILE after a
            // u32 width, height, count header - scripts/wide_gif.py turns it into an animation.
            sscanf(line, "%*s %255s %d", arg1, &n);
            FILE* f = fopen(arg1, "wb");
            if (f) {
                uint32_t hdr[3] = { (uint32_t) wideView.w, (uint32_t) wideView.h, (uint32_t) n };
                fwrite(hdr, 4, 3, f);
                for (int k = 0; k < n; k++) {
                    runFrame();
                    for (uint32_t i = 0; i < hdr[0] * hdr[1]; i++) {
                        uint8_t rgb[3] = { (uint8_t) wideOut[i], (uint8_t) (wideOut[i] >> 8), (uint8_t) (wideOut[i] >> 16) };
                        fwrite(rgb, 1, 3, f);
                    }
                }
                fclose(f);
            }
        } else if (!strcmp(cmd, "wideinfo")) {
            // How the last frame went: its mode (0 glow, 1 map, 2 battle), tilemap score (0..1000, -1 untried), torn.
            printf("wideinfo: frame %u mode %d score %d torn %d close %d scene %d battle %d\n", core->frameCounter(core),
                   pk_wide_last_mode(), pk_wide_last_score(), pk_wide_last_torn(), pk_wide_wants_close(), pk_wide_scene(),
                   pk_wide_last_battle_ways());
            if (gbGame) {   // and the Game Boy's registers as the frame ended
                const uint8_t* io = ((struct GB*) core->board)->memory.io;
                printf("wideinfo: lcdc %02x scx %d scy %d wx %d wy %d\n", io[GB_REG_LCDC], io[GB_REG_SCX], io[GB_REG_SCY],
                       io[GB_REG_WX], io[GB_REG_WY]);
            }
            sscanf(line, "%*s %255s", arg1);
            if (!strcmp(arg1, "lines") && !gbGame) {   // `wideinfo lines`: BG1's scroll per line as drawn, and as latched
                printf("wideinfo: latched BG1 %d,%d; per line:", G->memory.io[0x14 >> 1] & 0x1FF, G->memory.io[0x16 >> 1] & 0x1FF);
                for (int y = 0; y < PK_WIDE_H; y++) printf(" %d,%d", lineRegs(y)[0x14 >> 1] & 0x1FF, lineRegs(y)[0x16 >> 1] & 0x1FF);
                printf("\n");
            }
        } else if (!strcmp(cmd, "fieldinfo")) {
            // The app's SCENE SPEEDS / AUTO-RUN / RUN INDOORS read of this frame (pk_field_state, the game `wideon`
            // picked): with `run`, RUN INDOORS sets gMapHeader's allowRunning first.
            arg1[0] = 0;
            sscanf(line, "%*s %255s", arg1);
            int fs = pk_field_state(core, !strcmp(arg1, "run"), wildKeep >= 0 ? wildKeep : 100);
            printf("fieldinfo: frame %u known %d locked %d battle %d text %d bike %d surf %d avatar %d window %d\n",
                   core->frameCounter(core), !!(fs & PK_FIELD_KNOWN), !!(fs & PK_FIELD_LOCKED), !!(fs & PK_FIELD_BATTLE),
                   !!(fs & PK_FIELD_TEXT), !!(fs & PK_FIELD_BIKE), !!(fs & PK_FIELD_SURF), !!(fs & PK_FIELD_AVATAR),
                   !!(fs & PK_FIELD_WINDOW));
        } else if (!strcmp(cmd, "partnerself")) {
            // Co-op presence: this frame's pk_partner_self (map, fine position x16 + pixels, facing, mode, step count).
            if (!partnerOk) printf("partnerself: none (no field table: `wideon` first)\n");
            else printf("partnerself: valid %d map %d.%d fine %d,%d facing %d mode %d step %d\n",
                        partnerSelf[PK_PARTNER_SELF_VALID], partnerSelf[PK_PARTNER_SELF_GROUP], partnerSelf[PK_PARTNER_SELF_NUM],
                        partnerSelf[PK_PARTNER_SELF_FX], partnerSelf[PK_PARTNER_SELF_FY], partnerSelf[PK_PARTNER_SELF_FACING],
                        partnerSelf[PK_PARTNER_SELF_MODE], partnerSelf[PK_PARTNER_SELF_STEP]);
        } else if (!strcmp(cmd, "partnerset")) {
            sscanf(line, "%*s %255s", arg1);
            static uint8_t art[PK_PARTNER_ART_BYTES];
            FILE* f = fopen(arg1, "rb");
            if (f && fread(art, 1, sizeof(art), f) == sizeof(art) && fgetc(f) == EOF) pk_partner_set(art);
            else { fprintf(stderr, "mgba_dump: partnerset: can't read %d bytes from %s\n", PK_PARTNER_ART_BYTES, arg1); pk_partner_set(NULL); }
            if (f) fclose(f);
        } else if (!strcmp(cmd, "partnerpos")) {
            int vis = 0, fx = 0, fy = 0, frame = 0, flip = 0, blob = -1, bob = 0;
            sscanf(line, "%*s %d %d %d %d %d %d %d", &vis, &fx, &fy, &frame, &flip, &blob, &bob);
            partnerNear = 0;
            pk_partner_place(core, vis, fx, fy, frame, flip, blob, bob);
        } else if (!strcmp(cmd, "partnernear")) {
            partnerDx = partnerDy = partnerFrame = partnerFlip = 0;
            partnerBlob = -1;
            sscanf(line, "%*s %d %d %d %d %d", &partnerDx, &partnerDy, &partnerFrame, &partnerFlip, &partnerBlob);
            partnerNear = 1;
            partnerApplyNear();   // from the last frame's read; each frame after it follows
        } else if (!strcmp(cmd, "partnerinfo")) {
            char info[200], label[64] = {0};
            sscanf(line, "%*s %63s", label);   // an optional label: each case of a test is then its own line
            pk_partner_info(info, sizeof(info));
            printf("partnerinfo%s%s: %s\n", label[0] ? " " : "", label, info);
        } else if (!strcmp(cmd, "memhash")) {
            // EWRAM, IWRAM, VRAM, OAM and palette RAM of a GBA core: what a co-op partner must never leave behind.
            char name[64] = {0};
            sscanf(line, "%*s %63s", name);
            struct GBA* gba = (struct GBA*) core->board;
            uint32_t h = doCrc32(gba->memory.wram, SIZE_WORKING_RAM);   // each region's CRC32, folded in
            h = (h << 5 | h >> 27) ^ doCrc32(gba->memory.iwram, SIZE_WORKING_IRAM);
            h = (h << 5 | h >> 27) ^ doCrc32(gba->video.vram, SIZE_VRAM);
            h = (h << 5 | h >> 27) ^ doCrc32(gba->video.oam.raw, SIZE_OAM);
            h = (h << 5 | h >> 27) ^ doCrc32(gba->video.palette, SIZE_PALETTE_RAM);
            memHashes[memHashCount % MEM_HASHES].crc = h;
            snprintf(memHashes[memHashCount % MEM_HASHES].name, sizeof(memHashes[0].name), "%s", name);
            memHashCount++;
            printf("memhash %s %08X\n", name, h);
        } else if (!strcmp(cmd, "expectsame")) {
            char a[64] = {0}, b[64] = {0};
            sscanf(line, "%*s %63s %63s", a, b);
            const uint32_t* ha = NULL;
            const uint32_t* hb = NULL;
            for (int i = 0; i < memHashCount && i < MEM_HASHES; i++) {   // the newest of each name
                int k = (memHashCount - 1 - i) % MEM_HASHES;
                if (!ha && !strcmp(memHashes[k].name, a)) ha = &memHashes[k].crc;
                if (!hb && !strcmp(memHashes[k].name, b)) hb = &memHashes[k].crc;
            }
            if (ha && hb && *ha == *hb) printf("expectsame %s %s: PASS\n", a, b);
            else {
                printf("expectsame %s %s: FAIL memhash %s != %s\n", a, b, a, b);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "framediff")) {
            // The box of pixels two `shot` PPMs (P6, same size) differ in.
            char pa[256] = {0}, pb[256] = {0}, label[64] = {0}, tag[80];
            sscanf(line, "%*s %255s %255s %63s", pa, pb, label);   // an optional label, as partnerinfo's
            snprintf(tag, sizeof(tag), "framediff%s%s", label[0] ? " " : "", label);
            int x0 = 1 << 20, y0 = 1 << 20, x1 = -1, y1 = -1;
            FILE* fa = fopen(pa, "rb");
            FILE* fb = fopen(pb, "rb");
            unsigned wa = 0, ha = 0, wb = 0, hb = 0;
            if (fa && fb && fscanf(fa, "P6 %u %u 255", &wa, &ha) == 2 && fscanf(fb, "P6 %u %u 255", &wb, &hb) == 2 && wa == wb && ha == hb &&
                fgetc(fa) != EOF && fgetc(fb) != EOF) {
                for (unsigned y = 0; y < ha; y++)
                    for (unsigned x = 0; x < wa; x++) {
                        uint8_t ca[3], cb[3];
                        if (fread(ca, 1, 3, fa) != 3 || fread(cb, 1, 3, fb) != 3) { y = ha; break; }
                        if (memcmp(ca, cb, 3)) {
                            if ((int) x < x0) x0 = (int) x;
                            if ((int) x > x1) x1 = (int) x;
                            if ((int) y < y0) y0 = (int) y;
                            if ((int) y > y1) y1 = (int) y;
                        }
                    }
                if (x1 < 0) printf("%s none\n", tag);
                else printf("%s box %d,%d-%d,%d\n", tag, x0, y0, x1, y1);
            } else {
                printf("framediff: FAIL can't compare %s with %s\n", pa, pb);
                expectFailures++;
            }
            if (fa) fclose(fa);
            if (fb) fclose(fb);
        } else if (!strcmp(cmd, "wild")) {
            // wild ADDR KEEP: WILD ENCOUNTERS on the game `wideon` picked - its switch (sWildEncountersDisabled, as
            // WildEncounters.kt finds it) rolled before every frame with KEEP percent of encounters left (100 puts
            // it back; -1 stops rolling).
            unsigned addr = 0;
            int keep = 100;
            sscanf(line, "%*s %x %d", &addr, &keep);
            pk_field_wild(addr, false);
            wildKeep = keep;
            printf("wild: switch %08x keep %d\n", addr, keep);
        } else if (!strcmp(cmd, "wideghosts")) {
            // The far-off objects the last snapshot holds (not spawned): box in VMap pixels, palette tag, whether it has colours.
            const struct Snap* s = prev >= 0 ? &snaps[prev] : NULL;
            printf("wideghosts: %d\n", s ? s->nghosts : -1);
            for (int c = 0; s && c < s->nconn; c++)   // the maps they come from: where each sits in VMap cells
                printf("wideghosts: conn %d.%d dir %d degree %d at %d,%d size %dx%d\n", s->conn[c].group, s->conn[c].num,
                       s->conn[c].dir, s->conn[c].degree, s->conn[c].ox, s->conn[c].oy, s->conn[c].w, s->conn[c].h);
            for (int i = 0; s && i < s->nghosts; i++) {
                uint32_t pal[16];
                const struct Ghost* g = &s->ghosts[i];
                printf("wideghosts: %d,%d %dx%d tag %#x prio %d%s%s\n", g->x, g->y, g->w, g->h, g->palTag, g->prio,
                       g->hflip ? " hflip" : "", ghostPalette(g->palTag, 0xFF000000u, pal) ? "" : " NO PALETTE");
            }
        } else if (!strcmp(cmd, "wideshot")) {
            sscanf(line, "%*s %255s", arg1);
            FILE* f = fopen(arg1, "wb");
            if (f) {
                fprintf(f, "P6\n%d %d\n255\n", wideView.w, wideView.h);
                for (int i = 0; i < wideView.w * wideView.h; i++) {
                    uint8_t rgb[3] = { (uint8_t) wideOut[i], (uint8_t) (wideOut[i] >> 8), (uint8_t) (wideOut[i] >> 16) };
                    fwrite(rgb, 1, 3, f);
                }
                fclose(f);
            }
#endif
        } else if (!strcmp(cmd, "expectspecies")) {
            // expectspecies ADDR MONADDR: the halfword at ADDR (a gBattleMons species) equals the species of the
            // (encrypted) Pokémon struct at MONADDR (decrypted as expectmonin does).
            char a[64] = {0}, m[64] = {0};
            sscanf(line, "%*s %63s %63s", a, m);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0), mon = (uint32_t) strtoul(m, NULL, 0);
            uint32_t pers = (uint32_t) core->busRead32(core, mon), otid = (uint32_t) core->busRead32(core, mon + 4);
            static const uint8_t growthPos[24] = {0, 0, 0, 0, 0, 0, 1, 1, 2, 3, 2, 3, 1, 1, 2, 3, 2, 3, 1, 1, 2, 3, 2, 3};
            uint32_t want = ((uint32_t) core->busRead32(core, mon + 0x20 + 12u * growthPos[pers % 24]) ^ pers ^ otid) & 0xFFFF;
            uint32_t got = (uint32_t) core->busRead16(core, addr) & 0xFFFF;
            if (got == want && want) printf("PASS %08X species %u == %08X\n", addr, got, mon);
            else {
                printf("FAIL %08X species %u, %08X has %u\n", addr, got, mon, want);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "expectin16")) {
            // expectin16 ADDR VAL...: the halfword at ADDR must be one of the listed values (`PASS` / `FAIL`).
            char a[64] = {0};
            sscanf(line, "%*s %63s", a);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0);
            uint32_t got = (uint32_t) core->busRead16(core, addr) & 0xFFFF;
            bool in = false;
            const char* p = strstr(line, a);
            for (char* tok = p ? (char*) p + strlen(a) : NULL; tok && *tok;) {
                char* end = NULL;
                unsigned long v = strtoul(tok, &end, 0);
                if (end == tok) break;
                if ((uint32_t) v == got) in = true;
                tok = end;
            }
            if (in) printf("PASS %08X 0x%X\n", addr, got);
            else {
                printf("FAIL %08X got 0x%X not in the list\n", addr, got);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "expectmonin")) {
            // expectmonin ADDR SPECIES...: the Gen 3 Pokémon struct at ADDR (decrypted here: personality ^ OT ID keys the
            // four 12-byte substructs, whose order is personality % 24; the species is the Growth one's first halfword)
            // must be one of the listed species: `PASS <addr> species N` or `FAIL <addr> species N not in ...`.
            char a[64] = {0};
            char* rest = NULL;
            sscanf(line, "%*s %63s", a);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0);
            uint32_t pers = (uint32_t) core->busRead32(core, addr), otid = (uint32_t) core->busRead32(core, addr + 4);
            // where each personality % 24 puts the Growth substruct (G A M E orders, pret's sSubstructOrder)
            static const uint8_t growthAt[24] = {0, 0, 0, 0, 0, 0, 1, 1, 2, 3, 2, 3, 1, 1, 2, 3, 2, 3, 1, 1, 2, 3, 2, 3};
            uint32_t species = ((uint32_t) core->busRead32(core, addr + 0x20 + 12u * growthAt[pers % 24]) ^ pers ^ otid)
                               & 0xFFFF;
            bool in = false;
            const char* p = strstr(line, a);
            rest = p ? (char*) p + strlen(a) : NULL;
            for (char* tok = rest; tok && *tok;) {
                char* end = NULL;
                unsigned long v = strtoul(tok, &end, 0);
                if (end == tok) break;
                if ((uint32_t) v == species) in = true;
                tok = end;
            }
            if (in) printf("PASS %08X species %u\n", addr, species);
            else {
                printf("FAIL %08X species %u not in:%s\n", addr, species, rest ? rest : "");
                expectFailures++;
            }
        } else if (!strcmp(cmd, "expectframecrc")) {
            // expectframecrc X Y W H CRC: CRC32 of the current frame's X,Y,W,H pixels (R G B bytes, row by row) must be
            // CRC (hex): a pinned picture - a text box - without committing the picture. `PASS framecrc` or a FAIL
            // line that shows the CRC it got.
            unsigned x = 0, y = 0, w = 0, h = 0;
            char c[32] = {0};
            sscanf(line, "%*s %u %u %u %u %31s", &x, &y, &w, &h, c);
            uint32_t want = (uint32_t) strtoul(c, NULL, 16), got = 0;
            if (w && h && x + w <= videoW && y + h <= videoH) {
                uint8_t* rgb = malloc((size_t) w * h * 3);
                for (unsigned i = 0; i < w * h; i++) {
                    uint32_t px = (uint32_t) video[(y + i / w) * videoW + x + i % w];
                    rgb[3 * i] = (uint8_t) px;
                    rgb[3 * i + 1] = (uint8_t) (px >> 8);
                    rgb[3 * i + 2] = (uint8_t) (px >> 16);
                }
                got = 0xFFFFFFFFu;
                for (size_t i = 0; i < (size_t) w * h * 3; i++) {
                    got ^= rgb[i];
                    for (int b = 0; b < 8; b++) got = (got >> 1) ^ (0xEDB88320u & (0u - (got & 1u)));
                }
                got = ~got;
                free(rgb);
            }
            if (got == want) {
                printf("PASS framecrc %u,%u %ux%u\n", x, y, w, h);
            } else {
                printf("FAIL framecrc %u,%u %ux%u got 0x%08X want 0x%08X\n", x, y, w, h, got, want);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "expectfile")) {
            // expectfile ADDR FILE: the bus must hold FILE's bytes from ADDR (`PASS <addr> (N bytes)` or the first
            // byte that differs as a FAIL; any FAIL makes the exit status 1).
            char a[64] = {0};
            sscanf(line, "%*s %63s %255s", a, arg1);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0);
            FILE* f = fopen(arg1, "rb");
            if (!f) {
                printf("FAIL expectfile: cannot open %s\n", arg1);
                expectFailures++;
            } else {
                int c, i = 0;
                bool ok = true;
                for (; ok && (c = fgetc(f)) != EOF; i++) {
                    uint32_t got = (uint32_t) core->busRead8(core, addr + (uint32_t) i);
                    if (got != (uint32_t) c) {
                        printf("FAIL %08X got 0x%X want 0x%X\n", addr + (uint32_t) i, got, (unsigned) c);
                        ok = false;
                    }
                }
                fclose(f);
                if (ok) printf("PASS %08X (%d bytes)\n", addr, i);
                else expectFailures++;
            }
        } else if (!strcmp(cmd, "expect8") || !strcmp(cmd, "expect16") || !strcmp(cmd, "expect32")) {
            // expectW ADDR VAL [N]: the bus must hold VAL at ADDR (N of them in a row, W bits apart) - the co-op
            // checks' assertions. One line per command; any FAIL makes the exit status 1.
            char a[64] = {0}, v[64] = {0};
            int count = 1;
            sscanf(line, "%*s %63s %63s %d", a, v, &count);
            int width = cmd[6] == '8' ? 1 : cmd[6] == '1' ? 2 : 4;
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0), want = (uint32_t) strtoul(v, NULL, 0);
            if (width < 4) want &= (1u << (8 * width)) - 1;
            bool ok = true;
            for (int k = 0; k < count && ok; k++) {
                uint32_t at = addr + (uint32_t) (k * width);
                uint32_t got = width == 1 ? (uint32_t) core->busRead8(core, at)
                             : width == 2 ? (uint32_t) core->busRead16(core, at) : (uint32_t) core->busRead32(core, at);
                if (width < 4) got &= (1u << (8 * width)) - 1;
                if (got != want) {
                    printf("FAIL %08X got 0x%X want 0x%X\n", at, got, want);
                    ok = false;
                }
            }
            if (ok) printf("PASS %08X\n", addr);
            else expectFailures++;
        } else if (!strcmp(cmd, "waitmem8") || !strcmp(cmd, "waitmem16") || !strcmp(cmd, "waitmem32")) {
            // waitmemW ADDR VAL MAX: frames until the bus holds VAL at ADDR (PASS waitmem), FAIL after MAX frames
            char a[64] = {0}, v[64] = {0};
            int max = 0, frames = 0, width = cmd[7] == '8' ? 1 : cmd[7] == '1' ? 2 : 4;
            sscanf(line, "%*s %63s %63s %d", a, v, &max);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0), want = (uint32_t) strtoul(v, NULL, 0), got = 0;
            for (;;) {
                got = width == 1 ? (uint32_t) core->busRead8(core, addr) : width == 2 ? (uint32_t) core->busRead16(core, addr)
                                                                                      : (uint32_t) core->busRead32(core, addr);
                if (got == want || frames >= max) break;
                runFrame();
                frames++;
            }
            if (got == want) printf("PASS waitmem %08X after %d frame(s)\n", addr, frames);
            else {
                printf("FAIL waitmem %08X got 0x%X want 0x%X after %d frame(s)\n", addr, got, want, frames);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "waitchange32")) {
            // waitchange32 ADDR MAX: frames until the bus word at ADDR differs from what it holds now (PASS waitchange), FAIL after MAX
            char a[64] = {0};
            int max = 0, frames = 0;
            sscanf(line, "%*s %63s %d", a, &max);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0), was = core->busRead32(core, addr), got = was;
            while (frames < max && (got = core->busRead32(core, addr)) == was) {
                runFrame();
                frames++;
            }
            if (got != was) printf("PASS waitchange %08X 0x%X -> 0x%X after %d frame(s)\n", addr, was, got, frames);
            else {
                printf("FAIL waitchange %08X still 0x%X after %d frame(s)\n", addr, was, frames);
                expectFailures++;
            }
#ifdef HAVE_PK_COOP
        } else if (!strcmp(cmd, "waittask")) {
            // waittask FUNC MAX: frames until one of Emerald's 16 gTasks is active running FUNC (Thumb bit ignored).
            char a[64] = {0};
            int max = 0;
            sscanf(line, "%*s %63s %d", a, &max);
            uint32_t fn = (uint32_t) strtoul(a, NULL, 0) & ~1u;
            bool found = false;
            int frames = 0;
            for (;;) {
                for (int t = 0; t < 16 && !found; t++) {
                    uint32_t task = COOP_EM_gTasks + (uint32_t) (t * COOP_EM_OFF_TASK_SIZE);
                    found = core->busRead8(core, task + COOP_EM_OFF_TASK_IS_ACTIVE)
                         && ((uint32_t) core->busRead32(core, task + COOP_EM_OFF_TASK_FUNC) & ~1u) == fn;
                }
                if (found || frames >= max) break;
                runFrame();
                frames++;
            }
            if (found) printf("PASS waittask %08X after %d frame(s)\n", fn, frames);
            else {
                printf("FAIL waittask %08X not active after %d frame(s)\n", fn, frames);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "coopinstall")) {
            if (pk_coop_install(core)) printf("coop: installed\n");
            else printf("coop: refused %s\n", pk_coop_refused());
        } else if (!strcmp(cmd, "coopuninstall")) {
            pk_coop_uninstall(core);
            printf("coop: uninstalled, rom patched: %s\n", pk_cheats_rom_patched() ? "yes" : "no");
        } else if (!strcmp(cmd, "coopfreefield")) {
            printf("freefield %d\n", pk_coop_on_free_field(core));
        } else if (!strcmp(cmd, "coopjoin")) {
            // prints `join K A B`: pk_coop_join_kind and pk_coop_join_trainers
            int ids[2];
            int kind = pk_coop_join_kind(core);
            pk_coop_join_trainers(core, ids);
            printf("join %d %d %d\n", kind, ids[0], ids[1]);
        } else if (!strcmp(cmd, "coopfree")) {
            // prints `free F field G`: pk_coop_partner_free, pk_coop_on_free_field
            printf("free %d field %d\n", pk_coop_partner_free(core), pk_coop_on_free_field(core));
        // coop-field (plan 4 Task 7)
        // coop-after (plan 4 Task 8)
        } else if (!strcmp(cmd, "coopgive") || !strcmp(cmd, "coopwritemon")) {
            // coopgive FILE / coopwritemon SLOT FILE: the 100 bytes of FILE given to the game (party, else PC) or put
            // into party slot SLOT. Prints `give R` / `writemon R` (pk_coop_after.h's results).
            int slot = 0;
            uint8_t mon[100];
            memset(mon, 0, sizeof(mon));
            if (cmd[4] == 'w') sscanf(line, "%*s %d %255s", &slot, arg1);
            else sscanf(line, "%*s %255s", arg1);
            FILE* f = fopen(arg1, "rb");
            if (!f || fread(mon, 1, sizeof(mon), f) != sizeof(mon)) fprintf(stderr, "mgba_dump: %s: can't read %s\n", cmd, arg1);
            if (f) fclose(f);
            afterWaitSafe(core);
            int r = cmd[4] == 'w' ? pk_coop_write_party_mon(core, slot, mon) : pk_coop_give_mon(core, mon);
            printf("%s %d\n", cmd[4] == 'w' ? "writemon" : "give", r);
        } else if (!strcmp(cmd, "coopremove")) {
            int item = 0, count = 0;
            sscanf(line, "%*s %i %i", &item, &count);
            afterWaitSafe(core);
            printf("remove %d\n", pk_coop_remove_item(core, item, count));
        } else if (!strcmp(cmd, "coopflag")) {
            int id = 0;
            sscanf(line, "%*s %i", &id);
            afterWaitSafe(core);
            printf("flag %d\n", pk_coop_set_trainer_flag(core, id));
        } else if (!strcmp(cmd, "coopprize")) {
            int id = 0;
            char single[16] = {0};
            sscanf(line, "%*s %i %15s", &id, single);   // coopprize ID [single]: a one-trainer battle's prize
            printf("prize %d\n", single[0] ? pk_coop_prize_single(core, id) : pk_coop_prize(core, id));
        } else if (!strcmp(cmd, "coopmoney")) {
            int amount = 0;
            sscanf(line, "%*s %i", &amount);
            afterWaitSafe(core);
            printf("money %d\n", pk_coop_add_money(core, amount));
        } else if (!strcmp(cmd, "coopwhiteout")) {
            afterWaitSafe(core);
            printf("whiteout %d\n", pk_coop_white_out(core));
        } else if (!strcmp(cmd, "coopevolve")) {
            int slot = 0;
            sscanf(line, "%*s %i", &slot);
            afterWaitSafe(core);
            printf("evolve %d\n", pk_coop_evolve(core, slot));
        } else if (!strcmp(cmd, "coopsave")) {
            afterWaitSafe(core);
            printf("save %d\n", pk_coop_save_game(core));
        } else if (!strcmp(cmd, "coopmoneymark") || !strcmp(cmd, "coopmoneyexpect")) {
            // coopmoneymark: remember the game's money (its own GetMoney). coopmoneyexpect delta N / half: the money now is
            // the mark + N / the mark halved (CB2_WhiteOut's SetMoney(money / 2)): PASS / FAIL.
            static uint32_t mark;
            pk_coop_after_set_call(coopCall);
            int waited = 0;
            for (; waited < 60 && !pk_call_safe(core); waited++) runFrame();
            uint32_t now = afterMoney(core);
            if (cmd[9] == 'm') {
                mark = now;
                printf("money mark %u\n", now);
            } else {
                char mode[16] = {0};
                int n = 0;
                sscanf(line, "%*s %15s %d", mode, &n);
                uint32_t want = mode[0] == 'h' ? mark / 2 : mark + (uint32_t) n;
                if (now == want) printf("PASS money %u (mark %u)\n", now, mark);
                else { printf("FAIL money %u want %u (mark %u)\n", now, want, mark); expectFailures++; }
            }
        } else if (!strcmp(cmd, "afterhash") || !strcmp(cmd, "afterexpecthash")) {
            // afterhash SLOT ADDR LEN: remember a hash of LEN bus bytes; afterexpecthash SLOT ADDR LEN: the same bytes hash the same.
            static uint32_t hashes[8];
            char a[64] = {0};
            int slot = 0;
            uint32_t len = 0;
            sscanf(line, "%*s %d %63s %i", &slot, a, (int*) &len);
            uint32_t h = afterHash(core, (uint32_t) strtoul(a, NULL, 0), len);
            slot &= 7;
            if (cmd[5] == 'h') {
                hashes[slot] = h;
                printf("memhash %d %08x\n", slot, h);
            } else if (h == hashes[slot]) {
                printf("PASS hash %d unchanged (%08x)\n", slot, h);
            } else {
                printf("FAIL hash %d is %08x want %08x\n", slot, h, hashes[slot]);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "expect8p")) {
            // expect8p PTR OFF VAL: the byte at *(u32 *)PTR + OFF is VAL (a save block's field: those move).
            char p[64] = {0}, o[64] = {0}, v[64] = {0};
            sscanf(line, "%*s %63s %63s %63s", p, o, v);
            uint32_t addr = (uint32_t) core->busRead32(core, (uint32_t) strtoul(p, NULL, 0)) + (uint32_t) strtoul(o, NULL, 0);
            uint32_t got = core->busRead8(core, addr), want = (uint32_t) strtoul(v, NULL, 0) & 0xFF;
            if (got == want) printf("PASS %08X\n", addr);
            else { printf("FAIL %08X got 0x%X want 0x%X\n", addr, got, want); expectFailures++; }
        } else if (!strcmp(cmd, "coopexpectfree")) {
            // coopexpectfree 0|1: pk_coop_on_free_field is that (PASS / FAIL, unlike `coopfreefield`'s plain print).
            int want = 1;
            sscanf(line, "%*s %d", &want);
            int got = pk_coop_on_free_field(core);
            if (got == want) printf("PASS freefield %d\n", got);
            else { printf("FAIL freefield %d want %d\n", got, want); expectFailures++; }
        } else if (!strcmp(cmd, "expectfilep")) {
            // expectfilep PTR OFF FILE N: the first N bytes of FILE are on the bus at *(u32 *)PTR + OFF (a save block's or
            // the PC storage's field: those move).
            char p[64] = {0}, o[64] = {0};
            int n = 0;
            sscanf(line, "%*s %63s %63s %255s %d", p, o, arg1, &n);
            uint32_t addr = (uint32_t) core->busRead32(core, (uint32_t) strtoul(p, NULL, 0)) + (uint32_t) strtoul(o, NULL, 0);
            FILE* f = fopen(arg1, "rb");
            bool ok = f != NULL;
            for (int i = 0; ok && i < n; i++) {
                int c = fgetc(f);
                if (c == EOF || (uint8_t) c != core->busRead8(core, addr + (uint32_t) i)) {
                    printf("FAIL expectfilep %08X byte %d\n", addr, i);
                    ok = false;
                }
            }
            if (!f) printf("FAIL expectfilep: cannot open %s\n", arg1);
            if (f) fclose(f);
            if (ok) printf("PASS expectfilep %08X (%d bytes)\n", addr, n);
            else expectFailures++;
        } else if (!strcmp(cmd, "afterexpectsame")) {
            // expectsame A B LEN: the bus holds the same LEN bytes at A and at B.
            char a[64] = {0}, b[64] = {0};
            uint32_t len = 0;
            sscanf(line, "%*s %63s %63s %i", a, b, (int*) &len);
            uint32_t pa = (uint32_t) strtoul(a, NULL, 0), pb = (uint32_t) strtoul(b, NULL, 0);
            if (afterHash(core, pa, len) == afterHash(core, pb, len)) printf("PASS same %08X %08X\n", pa, pb);
            else { printf("FAIL %08X and %08X differ\n", pa, pb); expectFailures++; }
        } else if (!strcmp(cmd, "romcheckmark")) {
            memcpy(markRomDiffs, lastRomDiffs, sizeof(markRomDiffs));
            printf("romcheckmark: %d / %d / %d\n", markRomDiffs[0], markRomDiffs[1], markRomDiffs[2]);
        } else if (!strcmp(cmd, "romchecksame")) {
            // romchecksame: PASS / FAIL on the last romcheck's three counts equalling the marked ones.
            int ok = !memcmp(markRomDiffs, lastRomDiffs, sizeof(markRomDiffs));
            printf("%s romchecksame: %d / %d / %d (marked %d / %d / %d)\n", ok ? "PASS" : "FAIL", lastRomDiffs[0],
                   lastRomDiffs[1], lastRomDiffs[2], markRomDiffs[0], markRomDiffs[1], markRomDiffs[2]);
            if (!ok) expectFailures++;
        } else if (!strcmp(cmd, "coopstarting")) {
            printf("starting %d\n", pk_coop_battle_starting(core));
        // coop-lock (plan 5 Task 5): the story lock (pk_coop_lock.c), tests in coop/lock_*.txt
        } else if (!strcmp(cmd, "lockinstall")) {
            if (pk_coop_lock_install(core, coopCall)) printf("lock: installed\n");
            else printf("lock: refused %s\n", pk_coop_lock_refused());
        } else if (!strcmp(cmd, "lockuninstall")) {
            pk_coop_lock_uninstall(core);
            printf("lock: uninstalled, rom patched: %s\n", pk_cheats_rom_patched() ? "yes" : "no");
        } else if (!strcmp(cmd, "lockactive")) {
            sscanf(line, "%*s %d", &n);
            pk_coop_lock_set_active(core, n);
            printf("lock: active %d\n", n ? 1 : 0);
        } else if (!strcmp(cmd, "lockheld")) {
            int kind = 0;
            int held = pk_coop_lock_held(core, &kind);
            printf("lock: held %d kind %d\n", held, kind);
        } else if (!strcmp(cmd, "lockgo")) {
            // lockgo ID: pk_coop_lock_go, which calls the game (pk_call): wait, like `call`, for a frame that is safe
            sscanf(line, "%*s %d", &n);
            int waited = 0;
            for (; waited < 60 && !pk_call_safe(core); waited++) runFrame();
            if (waited) printf("lock: waited %d frame(s) for a safe point\n", waited);
            printf("lock: go %d\n", pk_coop_lock_go(core, n));
        } else if (!strcmp(cmd, "lockcancel")) {
            pk_coop_lock_cancel(core);
            printf("lock: cancelled\n");
        // The link bridge (plan 6 Task 1; pk_coop_link.c) and `pair` mode: two cores, tests in coop/link_*.txt
        } else if (!strcmp(cmd, "pair")) {
            // pair SAVE: a second core of the same ROM with its own save (a disposable copy, like the first: the game writes to it)
            // that runs beside the first from now on; `on b` / `on a` say which one the commands drive. The second starts from reset.
            sscanf(line, "%*s %255s", arg1);
            struct VFile* rom2 = pairOn ? NULL : VFileOpen(romPath, O_RDONLY);
            struct mCore* c2 = rom2 ? mCoreFindVF(rom2) : NULL;
            if (!c2 || !c2->init(c2)) {
                printf("FAIL pair: cannot make the second core\n");
                expectFailures++;
            } else {
                mCoreInitConfig(c2, NULL);
                color_t* video2 = calloc((size_t) videoW * videoH, sizeof(color_t));
                c2->setVideoBuffer(c2, video2, videoW);
                c2->setAudioBufferSize(c2, 2048);
                blip_set_rates(c2->getAudioChannel(c2, 0), c2->frequency(c2), SAMPLE_RATE);
                blip_set_rates(c2->getAudioChannel(c2, 1), c2->frequency(c2), SAMPLE_RATE);
                bool ok = c2->loadROM(c2, rom2);
                struct VFile* sv2 = VFileOpen(arg1, O_RDWR);
                if (sv2) c2->loadSave(c2, sv2);
                else printf("pair: warning: cannot open save %s\n", arg1);
                c2->reset(c2);
                memset(pairSaved, 0, sizeof(pairSaved));
                pairSaved[1].core = c2;
                pairSaved[1].video = video2;
                pairSaved[1].link.refused = "";
                pairOn = true;
                pairCur = 0;
                printf("pair: %s\n", ok ? "ready" : "FAILED");
            }
        } else if (!strcmp(cmd, "on")) {
            sscanf(line, "%*s %255s", arg1);
            if (!pairOn || (strcmp(arg1, "a") && strcmp(arg1, "b"))) {
                printf("FAIL on: needs `pair` first and a or b\n");
                expectFailures++;
            } else {
                pairSwap(arg1[0] == 'b');
                printf("on %s\n", arg1);
            }
        } else if (!strcmp(cmd, "linknoerror")) {
            sscanf(line, "%*s %d", &pairNoErr);
            printf("linknoerror %d\n", pairNoErr);
        } else if (!strcmp(cmd, "linkdelay")) {
            sscanf(line, "%*s %u", &pairDelay);
            printf("linkdelay %u\n", pairDelay);
        // task 7 (a dropped link; pk_coop_link_drop.c): the line goes down / up, a core leaves its battle, a core restarts
        } else if (!strcmp(cmd, "linkhold")) {
            // linkhold 0|1: 1 = every block in flight (and every later one) stays in its queue; 0 = the line is up again
            sscanf(line, "%*s %d", &pairHold);
            pairHold = pairHold ? 1 : 0;
            printf("linkhold %d\n", pairHold);
        } else if (!strcmp(cmd, "linkleave")) {
            // linkleave 0|1: pk_coop_link_leave on the current core (1 = blackout); prints the bridge state after
            sscanf(line, "%*s %d", &n);
            pk_coop_link_leave(core, n);
            printf("link: left (blackout %d), state %d\n", n ? 1 : 0, pk_coop_link_state(core));
        } else if (!strcmp(cmd, "linkpump")) {
            // linkpump 0|1: 0 = the pump (take / give / the tidy-up tick) does not run after frames; 1 = it does
            int on = 1;
            sscanf(line, "%*s %d", &on);
            pairNoPump = !on;
            printf("linkpump %d\n", on ? 1 : 0);
        } else if (!strcmp(cmd, "dropleaks")) {
            printf("dropleaks %d\n", pk_coop_link_drop_leaks(core));
        } else if (!strcmp(cmd, "heapmark")|| !strcmp(cmd, "heapexpect")) {
            // heapmark: remember the game heap's free bytes (malloc.c's block ring from sHeapStart) for this core; heapexpect: the
            // free bytes now equal the mark (PASS / FAIL): nothing a screen allocated is still held
            static uint32_t heapMark[2];
            uint32_t start = core->busRead32(core, COOP_EM_sHeapStart), pos = start, freeBytes = 0, used = 0;
            for (int i = 0; i < 1024; i++) {
                if (core->busRead16(core, pos) == 0) freeBytes += core->busRead32(core, pos + 4);
                else used++;
                pos = core->busRead32(core, pos + 12);
                if (pos == start) break;
            }
            if (cmd[4] == 'm') {
                heapMark[pairCur] = freeBytes;
                printf("heap mark: free %u used blocks %u\n", freeBytes, used);
            } else if (freeBytes == heapMark[pairCur]) {
                printf("PASS heap free %u (used blocks %u)\n", freeBytes, used);
            } else {
                printf("FAIL heap free %u want %u (used blocks %u)\n", freeBytes, heapMark[pairCur], used);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "corereset")) {
            // corereset: this core's game restarts from its save (the app was closed mid-battle): the bridge's patches go first
            // (a restart takes the app's ROM changes with it), no blocks leave it afterwards
            pk_coop_link_uninstall(core);
            pk_coop_link_forget();
            core->reset(core);
            printf("link: core reset\n");
        } else if (!strcmp(cmd, "linkrun")) {
            sscanf(line, "%*s %d", &n);
            for (int i = 0; i < n; i++) runFrame();
        } else if (!strcmp(cmd, "linkstate")) {
            if (pairOn) printf("linkstate a %d b %d\n", pairState(0), pairState(1));
            else printf("linkstate %d\n", pk_coop_link_state(core));
        } else if (!strcmp(cmd, "linkwait")) {
            // linkwait STATE MAX: frames until every core is in link state STATE (PK_COOP_LINK_*)
            int want = 0, max = 0, frames = 0;
            sscanf(line, "%*s %d %d", &want, &max);
            while ((pairState(0) != want || (pairOn && pairState(1) != want)) && frames < max) {
                runFrame();
                frames++;
            }
            if (pairState(0) == want && (!pairOn || pairState(1) == want)) printf("PASS linkwait %d after %d frame(s)\n", want, frames);
            else {
                printf("FAIL linkwait %d: a %d b %d after %d frame(s)\n", want, pairState(0), pairOn ? pairState(1) : -1, frames);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "coopbegin")) {
            // coopbegin MASTER KIND TRAINERA TRAINERB: pk_coop_link_begin on the current core (installing the bridge first). The
            // other player is the other core when paired (its save's name, gender and OT id), else a stand-in. Frames run (both
            // cores) until the game is on a pk_call-safe one: `link: begin 1 (after N frame(s))`.
            int master = 0, kind = 0, ta = 0, tb = 0;
            sscanf(line, "%*s %d %d %i %i", &master, &kind, &ta, &tb);
            struct pk_coop_link_setup setup;
            memset(&setup, 0, sizeof(setup));
            setup.master = (uint8_t) master;
            setup.kind = (uint8_t) kind;
            setup.trainerA = (uint16_t) ta;
            setup.trainerB = (uint16_t) tb;
            memset(setup.partnerName, 0xFF, sizeof(setup.partnerName));
            if (pairOn) {
                int cur = pairCur;
                pairSwap(1 - cur);
                uint32_t sb2 = core->busRead32(core, COOP_EM_gSaveBlock2Ptr);
                for (int b = 0; b < 8; b++) setup.partnerName[b] = core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_NAME + (uint32_t) b);
                setup.partnerGender = core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_GENDER);
                for (int b = 0; b < 4; b++) setup.partnerOtId |= (uint32_t) core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_TRAINER_ID + (uint32_t) b) << (8 * b);   // (byte reads: an unaligned word comes back rotated)
                pairSwap(cur);
            }
            pk_coop_after_set_call(coopCall);   // task 5: the end's prize / flags / evolutions run through the game's own functions
            pk_coop_after_set_cap(afterCap);
            if (!pk_coop_link_installed() && !pk_coop_link_install(core, coopCall)) {
                printf("link: refused %s\n", pk_coop_link_refused());
            } else {
                int tries = 0, began = 0;
                while (!(began = pk_coop_link_begin(core, &setup)) && tries < 120) {
                    if (strcmp(pk_coop_link_refused(), "no safe frame")) break;
                    runFrame();
                    tries++;
                }
                if (began) printf("link: begin 1 (after %d frame(s))\n", tries);
                else printf("link: begin 0 refused %s\n", pk_coop_link_refused());
            }
        } else if (!strcmp(cmd, "expectptr")) {
            // expectptr ADDR OFF1 OFF2 VAL: p = u32 at ADDR + OFF1, q = u32 at p + OFF2, the u32 at q must be VAL (e.g. the AI's
            // flags: gBattleResources -> ai -> aiFlags): `PASS <addr>` / `FAIL`
            char a[64] = {0}, o1[64] = {0}, o2[64] = {0}, v[64] = {0};
            sscanf(line, "%*s %63s %63s %63s %63s", a, o1, o2, v);
            uint32_t p = (uint32_t) core->busRead32(core, (uint32_t) strtoul(a, NULL, 0)) + (uint32_t) strtoul(o1, NULL, 0);
            uint32_t q = (uint32_t) core->busRead32(core, p) + (uint32_t) strtoul(o2, NULL, 0);
            uint32_t got = (uint32_t) core->busRead32(core, q), want = (uint32_t) strtoul(v, NULL, 0);
            if (got == want) printf("PASS %s ptr\n", a);
            else { printf("FAIL %s ptr got 0x%X want 0x%X\n", a, got, want); expectFailures++; }
        } else if (!strcmp(cmd, "linkforcefail")) {
            // linkforcefail 0|1: pk_coop_link_begin refuses (after writing everything) while it is 1
            pk_coop_link_test_fail = atoi(line + 13);
            printf("link: forcefail %d\n", pk_coop_link_test_fail);
        } else if (!strcmp(cmd, "joinhold")) {
            printf("link: hold %d\n", pk_coop_link_host_hold(core));
        } else if (!strcmp(cmd, "linkstart")) {
            // linkstart MASTER [KIND TA TB]: task 2's start (see the list above). The other player is the other core when paired.
            int master = 0, kind = 0, ta = 0, tb = 0;
            sscanf(line, "%*s %d %d %i %i", &master, &kind, &ta, &tb);
            struct pk_coop_link_setup setup;
            memset(&setup, 0, sizeof(setup));
            setup.master = (uint8_t) master;
            setup.kind = (uint8_t) kind;
            setup.trainerA = (uint16_t) ta;
            setup.trainerB = (uint16_t) tb;
            memset(setup.partnerName, 0xFF, sizeof(setup.partnerName));
            if (pairOn) {
                int cur = pairCur;
                pairSwap(1 - cur);
                uint32_t sb2 = core->busRead32(core, COOP_EM_gSaveBlock2Ptr);
                for (int b = 0; b < 8; b++) setup.partnerName[b] = core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_NAME + (uint32_t) b);
                setup.partnerGender = core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_GENDER);
                setup.partnerOtId = core->busRead32(core, sb2 + COOP_EM_OFF_SB2_PLAYER_TRAINER_ID);
                pairSwap(cur);
            }
            if (!pk_coop_link_installed() && !pk_coop_link_install(core, coopCall)) {
                printf("link: refused %s\n", pk_coop_link_refused());
            } else {
                int tries = 0, began = 0;
                while ((began = master ? pk_coop_link_host_go(core, &setup) : pk_coop_link_partner_go(core, &setup)) != 1 && tries < 120) {
                    if (master) break;   // the host stays held: a frame would start the transition
                    if (began == 0 && strcmp(pk_coop_link_refused(), "no safe frame")) break;   // -1 = the field is not free yet
                    runFrame();
                    tries++;
                }
                if (began == 1) printf("link: start 1 (after %d frame(s))\n", tries);
                else printf("link: start 0 refused %s\n", began < 0 ? "field not free" : pk_coop_link_refused());
            }
        } else if (!strcmp(cmd, "storyarm")) {
            // storyarm: task 6, pk_coop_story_arm on the current core (installing the bridge first): the next `dotrainerbattle` runs
            // the team pick first. `story: armed 1` / `story: armed 0`.
            int trainer = 0;
            sscanf(line, "%*s %i", &trainer);
            int ok = (pk_coop_link_installed() || pk_coop_link_install(core, coopCall)) && pk_coop_story_arm(core, coopCall, (uint16_t) trainer);
            printf("story: armed %d\n", ok);
        } else if (!strcmp(cmd, "storystate")) {
            // storystate: `story: state a S b S` (PK_COOP_STORY_*: 0 idle, 1 armed, 2 picking, 3 ready, 4 cancelled)
            if (pairOn) {
                int cur = pairCur, st[2];
                for (int c = 0; c < 2; c++) { pairSwap(c); st[c] = pk_coop_story_state(core); }
                pairSwap(cur);
                printf("story: state a %d b %d\n", st[0], st[1]);
            } else printf("story: state %d\n", pk_coop_story_state(core));
        } else if (!strcmp(cmd, "storywait")) {
            // storywait STATE MAX: frames (both cores) until every core's story state is STATE: PASS / FAIL
            int want = 0, max = 0, frames = 0, all = 0;
            sscanf(line, "%*s %d %d", &want, &max);
            for (;;) {
                int cur = pairCur, ok = 1;
                for (int c = 0; c < (pairOn ? 2 : 1); c++) { pairSwap(c); ok &= pk_coop_story_state(core) == want; }
                pairSwap(cur);
                all = ok;
                if (all || frames >= max) break;
                runFrame();
                frames++;
            }
            if (all) printf("PASS storywait %d after %d frame(s)\n", want, frames);
            else { printf("FAIL storywait %d after %d frame(s)\n", want, frames); expectFailures++; }
        } else if (!strcmp(cmd, "storygo")) {
            // storygo MASTER: pk_coop_story_go on the current core (the other player = the other core when paired): `story: go 1
            // (after N frame(s))` / `story: go 0`. Frames run until the game is on a pk_call-safe one.
            int master = 0;
            sscanf(line, "%*s %d", &master);
            struct pk_coop_link_setup setup;
            memset(&setup, 0, sizeof(setup));
            setup.master = (uint8_t) master;
            memset(setup.partnerName, 0xFF, sizeof(setup.partnerName));
            if (pairOn) {
                int cur = pairCur;
                pairSwap(1 - cur);
                uint32_t sb2 = core->busRead32(core, COOP_EM_gSaveBlock2Ptr);
                for (int b = 0; b < 8; b++) setup.partnerName[b] = core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_NAME + (uint32_t) b);
                setup.partnerGender = core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_GENDER);
                setup.partnerOtId = core->busRead32(core, sb2 + COOP_EM_OFF_SB2_PLAYER_TRAINER_ID);
                pairSwap(cur);
            }
            int tries = 0, began = 0;
            while (!(began = pk_coop_story_go(core, &setup)) && tries < 120 && pk_coop_story_state(core) == PK_COOP_STORY_READY) {
                runFrame();
                tries++;
            }
            if (began) printf("story: go 1 (after %d frame(s))\n", tries);
            else printf("story: go 0 (link: %s)\n", pk_coop_link_refused());
        } else if (!strcmp(cmd, "storydisarm")) {
            // storydisarm: pk_coop_story_disarm on the current core (a dropped join: the hook out WITHOUT the game's own battle): `story: disarm, state S`
            pk_coop_story_disarm(core);
            printf("story: disarm, state %d\n", pk_coop_story_state(core));
        } else if (!strcmp(cmd, "coopreleasescript")) {
            // coopreleasescript: pk_coop_release_script (-1 the game is not on its field, 0 no script was held, 1 let go): `release R`
            pk_coop_after_set_call(coopCall);   // the held script keeps the controls locked: no safe-frame wait, only the call hook
            printf("release %d\n", pk_coop_release_script(core));
        } else if (!strcmp(cmd, "storycancel")) {
            printf("story: cancel %d\n", pk_coop_story_cancel(core));
        } else if (!strcmp(cmd, "linkend")) {
            pk_coop_link_end(core);
            printf("link: ended\n");
        } else if (!strcmp(cmd, "linkreset")) {
            pk_coop_link_reset(core);
            printf("link: reset, state %d\n", pk_coop_link_state(core));
        } else if (!strcmp(cmd, "linkuninstall")) {
            pk_coop_link_uninstall(core);
            printf("link: uninstalled, rom patched: %s\n", pk_cheats_rom_patched() ? "yes" : "no");
        } else if (!strcmp(cmd, "linkinfo")) {
            // linkinfo: the bridge's counters of each core (blocks sent / received / refused / dropped, overruns, the seqs)
            for (int c = 0; c < (pairOn ? 2 : 1); c++) {
                int cur = pairCur;
                pairSwap(c);
                printf("linkinfo %c: sent %u recv %u refused %u dropped %u overrun %u tx %u rx %u head %u tail %u\n", 'a' + c,
                       core->busRead16(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, sent)),
                       core->busRead16(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, received)),
                       core->busRead16(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, refused)),
                       core->busRead16(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, dropped)),
                       core->busRead16(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, overrun)),
                       core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, txSeq)),
                       core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, rxSeq)),
                       core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, head)),
                       core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, tail)));
                pairSwap(cur);
            }
        // --- task 5: the end of a joined link battle (pk_coop_link_end.c) ---
        } else if (!strcmp(cmd, "linkresult")) {
            // linkresult LABEL: pk_coop_link_result of the current core: `link: result LABEL N <hex>` (N 0 = not ready)
            char label[32] = {0};
            sscanf(line, "%*s %31s", label);
            uint8_t r[PK_COOP_LINK_RESULT_SIZE];
            int n = pk_coop_link_result(core, r, sizeof(r));
            printf("link: result %s %d", label, n);
            for (int b = 0; b < n; b++) printf(" %02X", r[b]);
            printf("\n");
        } else if (!strcmp(cmd, "linkresultwait")) {
            // linkresultwait MAX [a|b]: frames until every core's (or that one's) result is ready: `PASS linkresultwait after N frame(s)` / FAIL
            int max = 0, frames = 0;
            char only[8] = {0};
            sscanf(line, "%*s %d %7s", &max, only);
            for (;;) {
                int ready = 1;
                uint8_t r[PK_COOP_LINK_RESULT_SIZE];
                for (int c = 0; c < (pairOn ? 2 : 1); c++) {
                    if (only[0] && only[0] - 'a' != c) continue;
                    int cur = pairCur;
                    pairSwap(c);
                    if (pk_coop_link_result(core, r, sizeof(r)) != PK_COOP_LINK_RESULT_SIZE) ready = 0;
                    pairSwap(cur);
                }
                if (ready || frames >= max) {
                    if (ready) printf("PASS linkresultwait after %d frame(s)\n", frames);
                    else { printf("FAIL linkresultwait: not ready after %d frame(s)\n", frames); expectFailures++; }
                    break;
                }
                runFrame();
                frames++;
            }
        } else if (!strcmp(cmd, "linkresultclear")) {
            pk_coop_link_result_clear(core);
            printf("link: result cleared\n");
        } else if (!strcmp(cmd, "never32")) {
            // never32 ADDR VAL LABEL: from now on a frame that has this word at ADDR on the current core is a FAIL (checked each pump)
            char a[32] = {0}, v[32] = {0};
            if (pairNever < 8) {
                sscanf(line, "%*s %31s %31s %31s", a, v, pairNeverW[pairNever].label);
                pairNeverW[pairNever].core = pairCur;
                pairNeverW[pairNever].addr = (uint32_t) strtoul(a, NULL, 0);
                pairNeverW[pairNever].val = (uint32_t) strtoul(v, NULL, 0);
                pairNeverW[pairNever].hit = 0;
                pairNever++;
            }
        } else if (!strcmp(cmd, "linkafterend")) {
            // linkafterend: how many blocks each game sent after its bridge said ENDED, and late blocks dropped at an ended receiver
            printf("linkafterend a %ld b %ld dropped a->b %ld b->a %ld\n", pairSentAfterEnd[0], pairSentAfterEnd[1], pairDroppedLate[0], pairDroppedLate[1]);
        } else if (!strcmp(cmd, "linkmoneymark") || !strcmp(cmd, "linkmoneyexpect")) {
            // linkmoneymark N: remember this core's money in slot N (0-7); linkmoneyexpect N DELTA: it is the mark + DELTA now (PASS / FAIL)
            static uint32_t marks[2][8];
            int n = 0;
            long delta = 0;
            sscanf(line, "%*s %d %ld", &n, &delta);
            n &= 7;
            uint32_t sb1 = core->busRead32(core, 0x03005D8C), sb2 = core->busRead32(core, 0x03005D90);
            uint32_t now = core->busRead32(core, sb1 + 0x490) ^ core->busRead32(core, sb2 + 0xAC);
            if (cmd[9] == 'm') {
                marks[pairCur][n] = now;
                printf("link: money mark %d %u\n", n, now);
            } else if (now == marks[pairCur][n] + (uint32_t) delta) {
                printf("PASS link money %u (mark %u + %ld)\n", now, marks[pairCur][n], delta);
            } else {
                printf("FAIL link money %u want %u (mark %u + %ld)\n", now, marks[pairCur][n] + (uint32_t) delta, marks[pairCur][n], delta);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "linkflag")) {
            // linkflag ID WANT: trainer ID's flag (0x500 + ID, SaveBlock1 +0x1270) on the current core is WANT (0 / 1): PASS / FAIL
            int id = 0, want = 0;
            sscanf(line, "%*s %i %d", &id, &want);
            uint32_t sb1 = core->busRead32(core, 0x03005D8C), flag = 0x500u + (uint32_t) id;
            int got = (core->busRead8(core, sb1 + 0x1270 + flag / 8) >> (flag % 8)) & 1;
            if (got == want) printf("PASS linkflag %d = %d (core %c)\n", id, got, 'a' + pairCur);
            else { printf("FAIL linkflag %d = %d want %d (core %c)\n", id, got, want, 'a' + pairCur); expectFailures++; }
        } else if (!strcmp(cmd, "flagexpect")) {
            // flagexpect ID WANT: event flag ID (SaveBlock1 +0x1270) on the current core is WANT (0 / 1): `PASS flagexpect ID = N (core c)` / FAIL
            int id = 0, want = 0;
            sscanf(line, "%*s %i %d", &id, &want);
            uint32_t sb1 = core->busRead32(core, 0x03005D8C);
            int got = (core->busRead8(core, sb1 + 0x1270 + (uint32_t) id / 8) >> (id % 8)) & 1;
            if (got == want) printf("PASS flagexpect %d = %d (core %c)\n", id, got, 'a' + pairCur);
            else { printf("FAIL flagexpect %d = %d want %d (core %c)\n", id, got, want, 'a' + pairCur); expectFailures++; }
        } else if (!strcmp(cmd, "linkdelayend")) {
            sscanf(line, "%*s %u", &pairEndDelay);
            printf("linkdelayend %u\n", pairEndDelay);
        } else if (!strcmp(cmd, "linkdrained")) {
            printf("link: drained %d\n", pk_coop_link_drained(core));
        } else if (!strcmp(cmd, "linkgiveseed")) {
            // linkgiveseed SEQ|next [LABEL]: pk_coop_link_give with a well-formed 4-byte seed block of that seq (`next` = the one the
            // bridge expects), on the current core - for the refusal checks (a block while idle, a stale seq, one after the end).
            // Prints `link: give R status B -> A LABEL`: the result and the other player's received flag before / after (a refused
            // block never changes it).
            char sq[32] = {0}, label[64] = {0};
            sscanf(line, "%*s %31s %63s", sq, label);
            int seq = !strcmp(sq, "next") ? core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, rxSeq)) : atoi(sq);
            uint8_t blk[PK_COOP_LINK_WIRE_HEADER + 4] = { PK_COOP_LINKB_SEED, (uint8_t) seq, 4, 0, 0, 0, 0, 0 };
            int other = 1 - core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, myId));
            int before = core->busRead8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) other);
            int given = pk_coop_link_give(core, blk, (int) sizeof(blk));
            printf("link: give %d status %d -> %d %s\n", given, before, core->busRead8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) other), label);
        } else if (!strcmp(cmd, "linkgivehex")) {
            // linkgivehex KIND LABEL HEX: pk_coop_link_give of a block of that kind (a number: PK_COOP_LINKB_*) with the next seq and these
            // payload bytes (hex), on the current core - for forged blocks. `link: give R status B -> A LABEL`.
            char kd[16] = {0}, label[64] = {0}, hex[600] = {0};
            sscanf(line, "%*s %15s %63s %599s", kd, label, hex);
            uint8_t blk[PK_COOP_LINK_WIRE_HEADER + 300];
            int len = hexBytes(hex, blk + PK_COOP_LINK_WIRE_HEADER, 300);
            blk[0] = (uint8_t) atoi(kd);
            blk[1] = core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, rxSeq));
            blk[2] = (uint8_t) len;
            blk[3] = (uint8_t) (len >> 8);
            int other = 1 - core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, myId));
            int before = core->busRead8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) other);
            int given = pk_coop_link_give(core, blk, PK_COOP_LINK_WIRE_HEADER + len);
            printf("link: give %d status %d -> %d %s\n", given, before, core->busRead8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) other), label);
        } else if (!strcmp(cmd, "linkgivemon")) {
            // linkgivemon KIND SPECIES MOVE PP ITEM LABEL: pk_coop_link_give of a one-Pokemon block (an unencrypted struct Pokemon, personality 0:
            // level 5, 20 / 20 HP, the checksum right) with this species, first move, its PP and held item (numbers) and the next seq.
            char kd[16] = {0}, label[64] = {0};
            int species = 0, move = 0, pp = 0, item = 0;
            sscanf(line, "%*s %15s %i %i %i %i %63s", kd, &species, &move, &pp, &item, label);
            uint8_t blk[PK_COOP_LINK_WIRE_HEADER + 100];
            memset(blk, 0, sizeof(blk));
            uint8_t* m = blk + PK_COOP_LINK_WIRE_HEADER;
            m[0x13] = 0x02;   // hasSpecies
            m[0x20] = (uint8_t) species; m[0x21] = (uint8_t) (species >> 8);   // growth substruct first (personality 0: G A E M)
            m[0x22] = (uint8_t) item; m[0x23] = (uint8_t) (item >> 8);
            m[0x2C] = (uint8_t) move; m[0x2D] = (uint8_t) (move >> 8);        // attacks substruct
            m[0x34] = (uint8_t) pp;
            m[0x54] = 5; m[0x56] = 20; m[0x58] = 20;
            uint32_t sum = 0;
            for (int w = 0; w < 24; w++) sum += m[0x20 + 2 * w] | m[0x21 + 2 * w] << 8;
            m[0x1C] = (uint8_t) sum; m[0x1D] = (uint8_t) (sum >> 8);
            blk[0] = (uint8_t) atoi(kd);
            blk[1] = core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, rxSeq));
            blk[2] = 100;
            int other = 1 - core->busRead8(core, PK_COOP_LINK_RAM + offsetof(struct pk_coop_link_ram, myId));
            int before = core->busRead8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) other);
            int given = pk_coop_link_give(core, blk, (int) sizeof(blk));
            printf("link: give %d status %d -> %d %s\n", given, before, core->busRead8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) other), label);
        } else if (!strcmp(cmd, "rangehash")) {
            // rangehash NAME ADDR LEN: CRC32 of LEN bus bytes at ADDR, kept under NAME for `expectsame` (across `on a` / `on b`)
            char name[64] = {0}, a[64] = {0}, l[64] = {0};
            sscanf(line, "%*s %63s %63s %63s", name, a, l);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0), len = (uint32_t) strtoul(l, NULL, 0), h = 0xFFFFFFFFu;
            for (uint32_t b = 0; b < len; b++) {
                h ^= core->busRead8(core, addr + b);
                for (int k = 0; k < 8; k++) h = (h >> 1) ^ (0xEDB88320u & (0u - (h & 1u)));
            }
            h = ~h;
            memHashes[memHashCount % MEM_HASHES].crc = h;
            snprintf(memHashes[memHashCount % MEM_HASHES].name, sizeof(memHashes[0].name), "%s", name);
            memHashCount++;
            printf("rangehash %s %08X\n", name, h);
        // task 4's numbers: kept under a name, checked with expectnum / expectdelta (EXP, levels, HP, bag counts)
        } else if (!strcmp(cmd, "monexp")) {
            // monexp NAME ADDR: the struct Pokémon at ADDR: its EXP (decrypted from the Growth substructure) as NAME, its level as
            // NAME.lv, its HP as NAME.hp, its species as NAME.sp
            char nm[64] = {0}, a[64] = {0}, key[80];
            sscanf(line, "%*s %63s %63s", nm, a);
            uint32_t mon = (uint32_t) strtoul(a, NULL, 0);
            uint32_t pers = (uint32_t) core->busRead32(core, mon), otid = (uint32_t) core->busRead32(core, mon + 4);
            static const uint8_t growthAt[24] = {0, 0, 0, 0, 0, 0, 1, 1, 2, 3, 2, 3, 1, 1, 2, 3, 2, 3, 1, 1, 2, 3, 2, 3};
            uint32_t g = mon + 0x20 + 12u * growthAt[pers % 24];
            uint32_t species = ((uint32_t) core->busRead32(core, g) ^ pers ^ otid) & 0xFFFF;
            uint32_t exp = (uint32_t) core->busRead32(core, g + 4) ^ pers ^ otid;
            numSet(nm, (long) exp);
            snprintf(key, sizeof(key), "%s.lv", nm);
            numSet(key, core->busRead8(core, mon + 0x54));
            snprintf(key, sizeof(key), "%s.hp", nm);
            numSet(key, core->busRead16(core, mon + 0x56));
            snprintf(key, sizeof(key), "%s.sp", nm);
            numSet(key, (long) species);
            printf("monexp %s species %u exp %u level %u hp %u\n", nm, species, exp, (unsigned) core->busRead8(core, mon + 0x54),
                   (unsigned) core->busRead16(core, mon + 0x56));
        } else if (!strcmp(cmd, "bagqty")) {
            // bagqty NAME ITEM: how many of the item the game's bag holds (all pockets, the quantity decrypted by the save's key)
            char nm[64] = {0};
            unsigned item = 0;
            sscanf(line, "%*s %63s %u", nm, &item);
            uint32_t sb1 = (uint32_t) core->busRead32(core, COOP_EM_gSaveBlock1Ptr), sb2 = (uint32_t) core->busRead32(core, COOP_EM_gSaveBlock2Ptr);
            uint32_t xorKey = (uint32_t) core->busRead32(core, sb2 + COOP_EM_OFF_SB2_ENCRYPTION_KEY) & 0xFFFF;
            long qty = 0;
            for (uint32_t o = COOP_EM_OFF_SB1_BAG_START; o < COOP_EM_OFF_SB1_BAG_END; o += 4) {
                if (core->busRead16(core, sb1 + o) == item) qty += (long) ((uint32_t) core->busRead16(core, sb1 + o + 2) ^ xorKey);
            }
            numSet(nm, qty);
            printf("bagqty %s item %u = %ld\n", nm, item, qty);
        } else if (!strcmp(cmd, "bagset")) {
            // bagset ITEM QTY: the first slot of the Items pocket becomes QTY of ITEM (the quantity encrypted by the save's key)
            unsigned item = 0, qty = 0;
            sscanf(line, "%*s %u %u", &item, &qty);
            uint32_t sb1 = (uint32_t) core->busRead32(core, COOP_EM_gSaveBlock1Ptr), sb2 = (uint32_t) core->busRead32(core, COOP_EM_gSaveBlock2Ptr);
            uint32_t xorKey = (uint32_t) core->busRead32(core, sb2 + COOP_EM_OFF_SB2_ENCRYPTION_KEY) & 0xFFFF;
            core->busWrite16(core, sb1 + COOP_EM_OFF_SB1_BAG_START, (uint16_t) item);
            core->busWrite16(core, sb1 + COOP_EM_OFF_SB1_BAG_START + 2, (uint16_t) (qty ^ xorKey));
            printf("bagset %u x %u\n", item, qty);
        } else if (!strcmp(cmd, "peeknum")) {
            // peeknum NAME ADDR WIDTH: the 1, 2 or 4 byte bus value at ADDR, kept as NAME
            char nm[64] = {0}, a[64] = {0};
            int w = 4;
            sscanf(line, "%*s %63s %63s %d", nm, a, &w);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0);
            long v = w == 1 ? core->busRead8(core, addr) : w == 2 ? core->busRead16(core, addr) : (long) (uint32_t) core->busRead32(core, addr);
            numSet(nm, v);
            printf("peeknum %s %08X = %ld\n", nm, addr, v);
        } else if (!strcmp(cmd, "expectnum")) {
            // expectnum NAME VALUE: the number kept under NAME is VALUE
            char nm[64] = {0};
            long want = 0;
            sscanf(line, "%*s %63s %ld", nm, &want);
            long got = numGet(nm);
            if (got == want) printf("PASS expectnum %s = %ld\n", nm, got);
            else {
                printf("FAIL expectnum %s = %ld, wanted %ld\n", nm, got, want);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "expectdelta")) {
            // expectdelta A B N: B - A == N (numbers kept by monexp / bagqty / peeknum)
            char a[64] = {0}, b[64] = {0};
            long want = 0;
            sscanf(line, "%*s %63s %63s %ld", a, b, &want);
            long da = numGet(a), db = numGet(b);
            if (db - da == want) printf("PASS expectdelta %s -> %s = %ld\n", a, b, db - da);
            else {
                printf("FAIL expectdelta %s (%ld) -> %s (%ld) = %ld, wanted %ld\n", a, da, b, db, db - da, want);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "expectge")) {
            // expectge NAME VALUE: the number kept under NAME is at least VALUE
            char nm[64] = {0};
            long want = 0;
            sscanf(line, "%*s %63s %ld", nm, &want);
            long got = numGet(nm);
            if (got >= want) printf("PASS expectge %s = %ld >= %ld\n", nm, got, want);
            else {
                printf("FAIL expectge %s = %ld, wanted at least %ld\n", nm, got, want);
                expectFailures++;
            }
        } else if (!strcmp(cmd, "linkcaught")) {
            // linkcaught FILE: what the battle's ball caught (pk_coop_link_caught: who threw it, the Pokemon's 100 bytes written to FILE)
            char f[256] = {0};
            sscanf(line, "%*s %255s", f);
            uint8_t mon[100];
            int who = pk_coop_link_caught(core, mon);
            if (who) {
                FILE* o = fopen(f, "wb");
                if (o) {
                    fwrite(mon, 1, sizeof(mon), o);
                    fclose(o);
                }
            }
            printf("link: caught by %d\n", who);
        } else if (!strcmp(cmd, "expectotname")) {
            // expectotname MON: the struct Pokemon at MON has the current game's trainer name as its OT name (7 bytes at +0x14)
            char a[64] = {0};
            sscanf(line, "%*s %63s", a);
            uint32_t mon = (uint32_t) strtoul(a, NULL, 0), sb2 = (uint32_t) core->busRead32(core, COOP_EM_gSaveBlock2Ptr);
            int same = 1;
            for (uint32_t b = 0; b < 7; b++) {
                if (core->busRead8(core, mon + 0x14 + b) != core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_NAME + b)) same = 0;
            }
            printf("%s expectotname %08X\n", same ? "PASS" : "FAIL", mon);
            if (!same) expectFailures++;
        } else if (!strcmp(cmd, "expectmon")) {
            // expectmon ADDR OTID SPECIES: the struct Pokémon at ADDR has OT ID OTID and (decrypted with its personality ^
            // OT ID) species SPECIES, and its checksum (+0x1C: the sum of the 24 decrypted u16 words) is valid.
            char a[64] = {0}, o[64] = {0}, sp[64] = {0}, ge[64] = {0};
            sscanf(line, "%*s %63s %63s %63s %63s", a, o, sp, ge);
            uint32_t mon = (uint32_t) strtoul(a, NULL, 0), id = (uint32_t) strtoul(o, NULL, 0);
            uint32_t want = (uint32_t) strtoul(sp, NULL, 0);
            {   // task 4: OTID `own` / GENDER `own` = this game's trainer (its save block's OT ID and gender)
                uint32_t sb2 = (uint32_t) core->busRead32(core, COOP_EM_gSaveBlock2Ptr);
                if (!strcmp(o, "own")) { id = 0; for (int b = 0; b < 4; b++) id |= (uint32_t) core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_TRAINER_ID + (uint32_t) b) << (8 * b); }
                if (!strcmp(ge, "own")) snprintf(ge, sizeof(ge), "%d", core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_GENDER));
            }
            // byte reads: the Pokémon may sit at an odd address (the co-op result's caught slot), and the bus rotates
            // an unaligned word
#define RD8(o) ((uint32_t) core->busRead8(core, mon + (o)))
#define RD32(o) (RD8(o) | RD8((o) + 1) << 8 | RD8((o) + 2) << 16 | RD8((o) + 3) << 24)
            uint32_t pid = RD32(0), ot = RD32(4), key = pid ^ ot, stored = RD8(0x1C) | RD8(0x1D) << 8;
            uint32_t sum = 0, w[12];
            for (int i = 0; i < 12; i++) {
                w[i] = RD32(0x20 + 4 * i) ^ key;
                sum += (w[i] & 0xFFFF) + (w[i] >> 16);
            }
            // growth substructure position: pid % 24 indexes the lexicographic permutations of G A E M
            int perm = (int) (pid % 24), order[4];
            {
                int avail[4] = {0, 1, 2, 3}, left = 4, fact = 6;
                for (int i = 0; i < 4; i++) {
                    int pick = perm / fact;
                    perm %= fact;
                    order[i] = avail[pick];
                    for (int j = pick; j < left - 1; j++) avail[j] = avail[j + 1];
                    left--;
                    if (i < 3) fact /= (3 - i);
                }
            }
            int growthPos = 0;
            for (int i = 0; i < 4; i++) if (order[i] == 0) growthPos = i;
            uint32_t species = w[growthPos * 3] & 0xFFFF;
            int miscPos = 0;
            for (int i = 0; i < 4; i++) if (order[i] == 3) miscPos = i;
            int gender = (int) (w[miscPos * 3] >> 31);   // the Misc substructure's origins half-word, bit 15: the OT gender
            int ok = ot == id && species == want && (sum & 0xFFFF) == stored && (!ge[0] || gender == atoi(ge));
            printf("%s expectmon %08x: OT ID %08x species %u checksum %04x (stored %04x) OT gender %d\n", ok ? "PASS" : "FAIL",
                   mon, ot, species, sum & 0xFFFF, stored, gender);
            if (!ok) expectFailures++;
#undef RD8
#undef RD32
#endif
        } else if (!strcmp(cmd, "layers")) {
            char m[64] = {0};
            sscanf(line, "%*s %63s", m);
            unsigned mask = (unsigned) strtoul(m, NULL, 0);
            for (size_t i = 0; i < 5; i++) core->enableVideoLayer(core, i, (mask >> i) & 1);
        } else if (!strcmp(cmd, "follower")) {
            sscanf(line, "%*s %255s", arg1);
            static uint8_t buf[3104];
            FILE* f = fopen(arg1, "rb");
            if (f && fread(buf, 1, sizeof(buf), f) == sizeof(buf)) pk_follower_set(buf, buf + 3072);
            else fprintf(stderr, "mgba_dump: follower: can't read %s\n", arg1);
            if (f) fclose(f);
#ifdef HAVE_PK_FLIGHT
        } else if (!strcmp(cmd, "flighton")) {
            // flighton W0 W1 ... : pk_flight_configure with the words (hex or decimal; FlightFns.words() prints them)
            static uint32_t words[PK_FLIGHT_WORDS];
            int n = 0;
            const char* q = line + strlen("flighton");
            char* end;
            while (n < PK_FLIGHT_WORDS) {
                unsigned long v = strtoul(q, &end, 0);
                if (end == q) break;
                words[n++] = (uint32_t) v;
                q = end;
            }
            pk_flight_set_call(flightCall);
            pk_flight_configure(words, n);
            printf("flight: configured %d of %d words\n", n, PK_FLIGHT_WORDS);
        } else if (!strcmp(cmd, "flight")) {
            // flight takeoff | land: waits (up to 60 frames) for a frame the game's code can be called on
            sscanf(line, "%*s %255s", arg1);
            if (!strcmp(arg1, "takeoff")) {
                int r = PK_FLIGHT_NOT_NOW, waited = 0;
                for (; waited < 60; waited++) {
                    if (pk_call_safe(core)) { r = pk_flight_take_off(core); break; }
                    runFrame();
                }
                printf("flight: takeoff %d (after %d frame(s))\n", r, waited);
            } else if (!strcmp(arg1, "land")) {
                pk_flight_land();
                printf("flight: land asked\n");
            }
        } else if (!strcmp(cmd, "flightroute")) {
            // flightroute DIRS: a string of D U L R (down, up, left, right), the scenic FLY's route
            static uint8_t dirs[4096];
            int n = 0;
            for (const char* c = line + strlen("flightroute"); *c && n < 4096; c++)
                if (*c == 'D' || *c == 'U' || *c == 'L' || *c == 'R') dirs[n++] = *c == 'D' ? 1 : *c == 'U' ? 2 : *c == 'L' ? 3 : 4;
            printf("flight: route %d (%d steps)\n", pk_flight_route(dirs, n), n);
        } else if (!strcmp(cmd, "flightarea")) {
            // flightarea [G.N ...]: FLIGHT AREA's maps (decimal group.num), none = anywhere
            static uint8_t bits[PK_FLIGHT_AREA_BYTES];
            memset(bits, 0, sizeof bits);
            int n = 0, g, m, used;
            for (const char* q = line + strlen("flightarea"); sscanf(q, " %d.%d%n", &g, &m, &used) == 2; q += used, n++)
                bits[((g << 8) | m) >> 3] |= (uint8_t) (1u << (m & 7));
            pk_flight_set_area(n ? bits : NULL, n ? (int) sizeof bits : 0);
            printf("flight: area %d map(s)\n", n);
        } else if (!strcmp(cmd, "flightinfo")) {
            char info[300];
            pk_flight_info(core, info, sizeof(info));
            printf("%s\n", info);
#endif
        } else if (!strcmp(cmd, "followinfo")) {
            char info[200];
            pk_follower_info(info, sizeof(info));
            printf("followinfo: %s\n", info);
        } else if (!strcmp(cmd, "rideset")) {
            // rideset FILE W: RIDE ON's vehicle - PK_RIDE_BYTES (three views, 1024 bytes each in OBJ tile order, the smoke,
            // its places) then a 32-byte palette
            static uint8_t buf[PK_RIDE_BYTES + 32];
            int w = 32;
            sscanf(line, "%*s %255s %d", arg1, &w);
            FILE* f = fopen(arg1, "rb");
            size_t n = f ? fread(buf, 1, (size_t) (PK_RIDE_BYTES + 32), f) : 0;
            if (f) fclose(f);
            if (n == (size_t) (PK_RIDE_BYTES + 32)) pk_ride_set(buf, buf + PK_RIDE_BYTES, w);
            else fprintf(stderr, "mgba_dump: rideset: can't read %s\n", arg1);
        } else if (!strcmp(cmd, "rideon")) {
            int on = 0;
            sscanf(line, "%*s %d", &on);
            pk_ride_show(core, on);
        } else if (!strcmp(cmd, "rideinfo")) {
            char info[200];
            pk_ride_info(info, sizeof(info));
            printf("rideinfo: %s\n", info);
        } else if (!strcmp(cmd, "followon")) {
            int on = 0;
            sscanf(line, "%*s %d", &on);
            pk_follower_show(core, on);
        } else if (!strcmp(cmd, "lookrefresh")) {
            // An optional 64 hex digits: the palette to put over the player's slot (a patch file's `playerpal` line).
            char hex[80] = {0};
            uint8_t pal[32];
            int havePal = sscanf(line, "%*s %79s", hex) == 1 && strlen(hex) == 64;
            for (int i = 0; havePal && i < 32; i++) { unsigned v; sscanf(hex + i * 2, "%2x", &v); pal[i] = (uint8_t) v; }
            printf("lookrefresh: %d\n", pk_look_refresh(core, havePal ? pal : NULL));
        } else if (!strcmp(cmd, "tint")) {
            int tr = 256, tg = 256, tb = 256;
            sscanf(line, "%*s %d %d %d", &tr, &tg, &tb);
            pk_tint_set(core, tr, tg, tb);
        } else if (!strcmp(cmd, "rawshot")) {
            sscanf(line, "%*s %255s", arg1);
            FILE* f = fopen(arg1, "wb");
            if (f) {
                fprintf(f, "%u %u\n", videoW, videoH);
                for (unsigned y = 0; y < videoH; y++) fwrite(&video[y * videoW], 4, videoW, f);
                fclose(f);
            }
        } else if (!strcmp(cmd, "shot")) {
            sscanf(line, "%*s %255s", arg1);
            cmdShot(arg1);
        } else {
            fprintf(stderr, "mgba_dump: unknown command '%s'\n", cmd);
        }
        fflush(stderr);
    }

#ifdef HAVE_PK_COOP
    if (pairOn) {   // the other core of a pair, too (its save is written back as the first one's is)
        struct pairCtx* o = &pairSaved[1 - pairCur];
        o->core->deinit(o->core);
        free(o->video);
    }
#endif
    core->deinit(core);
    free(video);
    return expectFailures ? 1 : 0;
}
