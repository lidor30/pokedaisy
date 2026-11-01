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
#endif
#if __has_include("../app/src/main/cpp/pk_wide.c")
#include "../app/src/main/cpp/pk_wide.c"
#include "../app/src/main/cpp/pk_wide_gb.c"
#include "../app/src/main/cpp/pk_gb_field.c"
#define HAVE_PK_WIDE 1
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

// `wild ADDR KEEP`: WILD ENCOUNTERS' roll before every frame, as the app runs it (-1 = off).
static int wildKeep = -1;

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

static void runFrame(void) {
#ifdef HAVE_PK_WIDE
    if (wildKeep >= 0) pk_field_state(core, 0, wildKeep);
#endif
    core->runFrame(core);
    if (core->platform(core) == mPLATFORM_GBA) pk_rest_note(core);
    wideAfterFrame();
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
static void gb_music_state(struct mCore* core, uint8_t* out, bool crystal) {
    static const struct { uint16_t a; int n; } R[] = {{0xC006, 8}, {0xC016, 8}, {0xC026, 4}, {0xC0B6, 4}, {0xC0BE, 4}};
    // Crystal's channel structs (wChannel1-4, 0x32 bytes from C101): Flags1, MusicAddress + LastMusicAddress,
    // NoteDuration, LoopCount - Gen2Music's loop state.
    static const struct { uint16_t a; int n; } C[] = {{3, 1}, {6, 4}, {0x15, 1}, {0x18, 1}};
    int k = 0;
    if (crystal) {
        for (int ch = 0; ch < 4; ch++)
            for (int r = 0; r < 4; r++)
                for (int i = 0; i < C[r].n; i++) out[k++] = core->busRead8(core, (uint16_t) (0xC101 + 0x32 * ch + C[r].a + i));
        return;
    }
    for (int r = 0; r < 5; r++) for (int i = 0; i < R[r].n; i++) out[k++] = core->busRead8(core, R[r].a + i);
}

static void gb_loop(struct mCore* core, int frames, bool crystal) {
    enum { W = 28 };
    uint8_t* states = malloc((size_t) frames * W);
    int still = 0;
    for (int f = 0; f < frames; f++) {
        core->runFrame(core);
        uint8_t* st = states + (size_t) f * W;
        gb_music_state(core, st, crystal);
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
    for (int i = 0; i < 2000000; i++) {
        if (cpu->executionMode == MODE_THUMB && (uint32_t) cpu->gprs[ARM_PC] - WORD_SIZE_THUMB == PK_CALL_SENTINEL) {
            ok = true;
            break;
        }
        core->step(core);
    }
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
    printf("romcheck raw bus: %d byte(s) differ\n", romDiffs(file, size, false));
    printf("romcheck overlay: %d byte(s) differ\n", romDiffs(file, size, true));
    pk_cheats_swap_rom(core);
    printf("romcheck swapped: %d byte(s) differ\n", romDiffs(file, size, false));
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

    char line[512];
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
        } else if (!strcmp(cmd, "poke8p") || !strcmp(cmd, "peek8p")) {
            // poke8p PTR OFF VAL / peek8p PTR OFF: the byte at *(u32 *)PTR + OFF -
            // for save-block fields, since Emerald moves its save blocks on every load.
            char p[64] = {0}, o[64] = {0}, v[64] = {0};
            sscanf(line, "%*s %63s %63s %63s", p, o, v);
            uint32_t addr = core->busRead32(core, (uint32_t) strtoul(p, NULL, 0)) + (uint32_t) strtoul(o, NULL, 0);
            if (cmd[1] == 'o') core->busWrite8(core, addr, (uint8_t) strtoul(v, NULL, 0));
            else printf("%08x: %02x\n", addr, core->busRead8(core, addr));
        } else if (!strncmp(cmd, "poke", 4)) {
            char a[64] = {0}, v[64] = {0};
            sscanf(line, "%*s %63s %63s", a, v);
            uint32_t addr = (uint32_t) strtoul(a, NULL, 0), val = (uint32_t) strtoul(v, NULL, 0);
            if (!strcmp(cmd, "poke8")) core->busWrite8(core, addr, (uint8_t) val);
            else if (!strcmp(cmd, "poke16")) core->busWrite16(core, addr, (uint16_t) val);
            else if (!strcmp(cmd, "poke32")) core->busWrite32(core, addr, val);
            else fprintf(stderr, "mgba_dump: unknown command '%s'\n", cmd);
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
            bool ok = pk_call(core, (uint32_t) strtoul(a, NULL, 0), args);
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
            gb_loop(core, n, !strcmp(what, "crystal"));
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
            pk_field_configure(crc);   // fieldinfo's reads: the same table
            wideView = (struct PkWideView) { -n, 0, pk_wide_frame_w() + 2 * n, pk_wide_frame_h() };
            memset(&wideStats, 0, sizeof(wideStats));
            printf("wideon: ROM %08x %s, margin %d\n", crc, wideOn ? "known" : "NOT KNOWN (WIDE off)", n);
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
            printf("fieldinfo: frame %u known %d locked %d battle %d text %d bike %d surf %d avatar %d\n",
                   core->frameCounter(core), !!(fs & PK_FIELD_KNOWN), !!(fs & PK_FIELD_LOCKED), !!(fs & PK_FIELD_BATTLE),
                   !!(fs & PK_FIELD_TEXT), !!(fs & PK_FIELD_BIKE), !!(fs & PK_FIELD_SURF), !!(fs & PK_FIELD_AVATAR));
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
        } else if (!strcmp(cmd, "followinfo")) {
            char info[200];
            pk_follower_info(info, sizeof(info));
            printf("followinfo: %s\n", info);
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

    core->deinit(core);
    free(video);
    return 0;
}
