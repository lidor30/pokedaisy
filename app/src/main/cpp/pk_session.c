// PokeDaisy — the player's game on mGBA, behind pk_api.h: start and close it, run its frames, its picture, sound and
// keys, and read its memory. No JNI here: Android calls it through pokedaisy_jni.c, the iPad app straight.
// GPL-3.0 (see NOTICE).

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <time.h>

#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/config.h>
#include <mgba/core/blip_buf.h>
#include <mgba-util/vfs.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gb/gb.h>

#include "pk_call.h"
#include "pk_cheats.h"
#include "pk_coop.h"
#include "pk_coop_lock.h"
#include "pk_core_access.h"
#include "pk_flight.h"
#include "pk_host.h"
#include "pk_rewind.h"
#include "pk_savevf.h"
#include "pk_wide.h"
#include "pk_session.h"   // last: its `g` (the game's state) is a macro

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "pokedaisy/jni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "pokedaisy/jni", __VA_ARGS__)
#define PK_LOG_MGBA(category, line) __android_log_print(ANDROID_LOG_ERROR, "pokedaisy/mgba", "%s: %s", category, line)
#else
#define LOGI(...) (fprintf(stderr, "pokedaisy: " __VA_ARGS__), fputc('\n', stderr))
#define LOGE(...) LOGI(__VA_ARGS__)
#define PK_LOG_MGBA(category, line) fprintf(stderr, "pokedaisy/mgba: %s: %s\n", category, line)
#endif

_Static_assert(sizeof(color_t) == 4, "pk_api_video's pixels are 4 bytes");

struct pk_session pk_session;
pthread_rwlock_t pk_coreLock = PTHREAD_RWLOCK_INITIALIZER;

#define PK_REWIND_INTERVAL 2

// mGBA's log, for every core in the process (the player's, STEADY's render core, LINK PLAY's). With no logger mGBA
// printf()s each message, and a game reading unmapped memory logs every byte: a hack's BIOS LZ77 copy from a bad
// address held one frame for seconds, and RESTART GAME's wait for the emu thread became an ANR (Crashlytics, 1.4.0).
// Only errors are logged, at most a few a second; game errors, stubs and the rest are dropped unformatted.
static void pk_mgba_log(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
    (void) logger;
    if (!(level & (mLOG_FATAL | mLOG_ERROR))) return;
    static struct timespec windowStart;
    static int inWindow;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec != windowStart.tv_sec) {
        windowStart = now;
        inWindow = 0;
    }
    if (++inWindow > 5) return;
    char line[256];
    vsnprintf(line, sizeof(line), format, args);
    PK_LOG_MGBA(mLogCategoryName(category), line);
}

static struct mLogger pk_mgbaLogger = { .log = pk_mgba_log, .filter = NULL };

__attribute__((constructor)) static void pk_install_mgba_logger(void) {
    mLogSetDefaultLogger(&pk_mgbaLogger);
}

/** The GB core's master volume (mGBA's full is 0x100): see pk_gb_config. */
#define PK_GB_VOLUME 0x80

// Game Boy / Color ROMs run on mGBA's GB core too (M_CORE_GB). Red/Blue/Yellow
// are SGB-enhanced, so the core would frame them in a 256x224 Super Game Boy
// border: turn borders off, which keeps every GB game at its 160x144 screen. And half volume (below).
void pk_gb_config(struct mCore* core) {
    if (core->platform(core) != mPLATFORM_GB) {
        return;
    }
    // Option by option: mCoreLoadForeignConfig maps the whole config, and with
    // no "volume" key in it the GB core's master volume went to 0 (Yellow was silent).
    mCoreConfigSetIntValue(&core->config, "sgb.borders", 0);
    core->reloadConfigOption(core, "sgb.borders", &core->config);
    // Half of mGBA's full volume: at 0x100 its GB mixer scales the channels by 12 into an int16, which four loud
    // channels overflow - Crystal and Yellow wrapped and clipped in every loud passage (the "crushed" sound players
    // reported). Recorded headless at both volumes (mgba_dump's `volume` + `wav`): at 0x80 the loudest peak is ~28,700
    // of 32,767 and the music is about as loud as FireRed's.
    mCoreConfigSetIntValue(&core->config, "volume", PK_GB_VOLUME);
    core->reloadConfigOption(core, "volume", &core->config);
}

int pk_is_gba(const struct mCore* core) {
    return core && core->platform(core) == mPLATFORM_GBA;
}

static void pkDrainAudio(void) {
    if (g.gbAudio.left) {
        g.audioAvail = pk_gb_audio_read(&g.gbAudio, g.audio, PK_AUDIO_SCRATCH_SAMPLES) * 2;
        return;
    }
    struct blip_t* left = g.core->getAudioChannel(g.core, 0);
    struct blip_t* right = g.core->getAudioChannel(g.core, 1);
    int avail = blip_samples_avail(left);
    if (avail > PK_AUDIO_SCRATCH_SAMPLES) {
        avail = PK_AUDIO_SCRATCH_SAMPLES;
    }
    if (avail > 0) {
        blip_read_samples(left, g.audio, avail, 1);
        blip_read_samples(right, g.audio + 1, avail, 1);
        g.audioAvail = avail * 2;
    } else {
        g.audioAvail = 0;
    }
}

void pkRewindOff(void) {
    if (g.rewindEntries > 0) {
        pk_rewind_deinit(&g.rewind);
        memset(&g.rewind, 0, sizeof(g.rewind));
    }
    g.rewindEntries = 0;
    g.rewindTick = 0;
}

void pk_api_deinit(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    pkRewindOff();
    if (g.core) {
        pk_coop_uninstall(g.core);      // co-op's patches / block / RAM first (while the core is still up)
        pk_coop_lock_uninstall(g.core);  // coop-lock (plan 5 Task 5)
        pk_cheats_clear(g.core);        // and forgets the ROM bytes it kept
        pk_cheats_unpatch(g.core);      // the game options' (REUSABLE TMS) too
        pk_cheats_unblock(g.core);      // and PLAYER LOOK's blocks
        pk_gb_audio_detach(&g.gbAudio, g.core);
        g.core->deinit(g.core);         // unloads ROM, flushes and closes the save VFile
        g.core = NULL;
        // Both cores close the save VFile they were given themselves (GBUnloadROM: sramRealVf;
        // GBAUnloadROM: savedata.realVf), so closing it again here was a double close. With a
        // bare VFileFD the GBA's second close usually just failed (close() on a dead fd, EBADF,
        // and no free); a reused fd made it fdsan's abort in _vfdClose (1.2.1's Game Boy games).
        // pk_save_vf's close frees whatever happens, so in 1.3.1 a GBA game's close was a double
        // free: SIGABRT in pkSvClose (Crashlytics, 1.3.1 on the Thor).
        g.saveVf = NULL;
    }
    if (g.romFileVf) {
        if (g.romFile) g.romFileVf->unmap(g.romFileVf, (void*) g.romFile, g.romFileSize);
        g.romFileVf->close(g.romFileVf);
    }
    g.romFileVf = NULL;
    g.romFile = NULL;
    g.romFileSize = 0;
    pk_coop_reset();                // never a stale "installed" for the next game
    pk_coop_lock_reset();           // coop-lock (plan 5 Task 5)
    g.saveReadOnly = false;
    free(g.video);
    g.video = NULL;
    g.vw = g.vh = 0;
    g.audioAvail = 0;
    pk_wide_configure(0);
    pk_wide_extras_reset();   // FOLLOWER and DAY AND NIGHT: the closed game's lead walked on in the next one
    pk_flight_configure(NULL, 0);   // FREE FLIGHT: the next game configures its own
    g.wideCrc = 0;
    g.wideW = 0;
    free(g.wide);
    g.wide = NULL;
    pthread_rwlock_unlock(&pk_coreLock);
}

// The player's core, for pokedaisy_ra.c (RetroAchievements' memory reads and ROM hash).
struct mCore* pkMainCore(void) {
    return g.core;
}

bool pk_api_init(const char* romPath, const char* savePath) {
    if (g.core) {
        pk_api_deinit();
    }

    struct VFile* rom = VFileOpen(romPath, O_RDONLY);
    struct VFile* romFileVf = rom ? VFileOpen(romPath, O_RDONLY) : NULL;   // pk_read_rom_file's own handle
    if (!rom) {
        LOGE("cannot open ROM");
        return false;
    }

    struct mCore* core = mCoreFindVF(rom);
    if (!core) {
        LOGE("mCoreFindVF: unrecognized ROM");
        rom->close(rom);
        if (romFileVf) romFileVf->close(romFileVf);
        return false;
    }
    if (!core->init(core)) {
        LOGE("core->init failed");
        rom->close(rom);
        if (romFileVf) romFileVf->close(romFileVf);
        core->deinit(core);
        return false;
    }
    mCoreInitConfig(core, NULL);
    pk_gb_config(core);

    unsigned w, h;
    core->desiredVideoDimensions(core, &w, &h);
    color_t* video = calloc((size_t) w * h, sizeof(color_t));
    core->setVideoBuffer(core, video, w);

    core->setAudioBufferSize(core, 2048);
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), PK_SAMPLE_RATE);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), PK_SAMPLE_RATE);

    if (!core->loadROM(core, rom)) {
        LOGE("core->loadROM failed");
        rom->close(rom);
        if (romFileVf) romFileVf->close(romFileVf);
        core->deinit(core);
        free(video);
        return false;
    }

    struct VFile* saveVf = NULL;
    g.saveReadOnly = false;
    if (savePath) {
        // O_CREAT so a fresh game starts with an empty SRAM file we own; fall
        // back to read-only if the file exists but isn't writable (e.g. an
        // adb-pushed import) so its save data still loads.
        saveVf = VFileOpen(savePath, O_RDWR | O_CREAT);
        if (!saveVf) {
            saveVf = VFileOpen(savePath, O_RDONLY);
            g.saveReadOnly = saveVf != NULL;
            if (saveVf) {
                LOGI("save opened read-only (new saves won't persist): %s", savePath);
            }
        }
        // A map the file can't give (read-only, or a file system without shared mappings) becomes a copy in memory:
        // mGBA never checks its map, and a NULL one crashed the first flash read (pk_savevf.c).
        saveVf = pk_save_vf(saveVf);
        if (saveVf) {
            core->loadSave(core, saveVf);
        } else {
            LOGE("cannot open save path (continuing without persistent SRAM)");
        }
    }

    core->reset(core);
    pk_heal_rtc(core);
    pk_rest_reset();   // a new game: its own main loop depth

    const uint8_t* romFile = NULL;
    size_t romFileSize = 0;
    if (romFileVf && pk_is_gba(core)) {
        ssize_t size = romFileVf->size(romFileVf);
        romFileSize = size > 0 ? ((size_t) size < SIZE_CART0 ? (size_t) size : SIZE_CART0) : 0;
        romFile = romFileSize ? romFileVf->map(romFileVf, romFileSize, MAP_READ) : NULL;
    }
    if (!romFile && romFileVf) {
        romFileVf->close(romFileVf);
        romFileVf = NULL;
        romFileSize = 0;
    }

    pthread_rwlock_wrlock(&pk_coreLock);
    g.core = core;
    g.romFileVf = romFileVf;
    g.romFile = romFile;
    g.romFileSize = romFileSize;
    g.video = video;
    g.vw = w;
    g.vh = h;
    g.saveVf = saveVf;
    g.audioAvail = 0;
    pk_gb_audio_attach(&g.gbAudio, core, PK_SAMPLE_RATE);
    // EXPAND's window: the GBA's, and a Game Boy game's (pk_wide_gb.c: Yellow so far).
    g.wide = calloc((size_t) PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H, sizeof(uint32_t));
    pthread_rwlock_unlock(&pk_coreLock);

    LOGI("core up: %ux%u, freq %d Hz, sample rate %d", w, h, core->frequency(core), PK_SAMPLE_RATE);
    return true;
}

int pk_api_platform(void) {
    if (!g.core) {
        return PK_API_PLATFORM_NONE;
    }
    return pk_is_gba(g.core) ? PK_API_PLATFORM_GBA : PK_API_PLATFORM_GB;
}

const void* pk_api_video(void) {
    return g.video;
}

int pk_api_video_width(void) {
    return (int) g.vw;
}

int pk_api_video_height(void) {
    return (int) g.vh;
}

void pk_api_set_audio_rate(double rate) {
    if (g.gbAudio.left) {
        pk_gb_audio_set_rate(&g.gbAudio, rate);
    } else if (g.core && rate > 0) {
        blip_set_rates(g.core->getAudioChannel(g.core, 0), g.core->frequency(g.core), rate);
        blip_set_rates(g.core->getAudioChannel(g.core, 1), g.core->frequency(g.core), rate);
    }
}

void pk_api_set_keys(uint32_t keys) {
    if (g.core) {
        g.core->setKeys(g.core, keys);
    }
}

const int16_t* pk_api_audio(int* count) {
    *count = g.audioAvail > 0 ? g.audioAvail : 0;
    return g.audio;
}

/** pk_api_finish_save's frames go unheard. */
static void pkDropAudio(void) {
    pkDrainAudio();
    g.audioAvail = 0;
}

int pk_api_finish_save(int maxFrames) {
    if (!g.core) return 0;
    return pk_finish_save(g.core, maxFrames, pkDropAudio);
}

// WIDE: the core's frame is the size the configured game's rebuild expects (the GBA's 240 x 160, a Game Boy's 160 x 144).
static bool pkWideFrameFits(void) {
    return (int) g.vw == pk_wide_frame_w() && (int) g.vh == pk_wide_frame_h();
}

void pk_api_run_frame(void) {
    if (!g.core) {
        return;
    }
    if (g.rewindEntries > 0) {
        if (g.rewinding) {
            // Back one entry, then that frame runs to draw it. At the oldest one, stand still.
            if (!pk_rewind_restore(&g.rewind, g.core)) {
                g.audioAvail = 0;
                return;
            }
        } else if (++g.rewindTick >= PK_REWIND_INTERVAL) {
            g.rewindTick = 0;
            pk_rewind_append(&g.rewind, g.core);
        }
    }
    g.core->runFrame(g.core);
    if (pk_is_gba(g.core)) pk_rest_note(g.core);
    if (pk_is_gba(g.core)) {   // coop-lock (plan 5 Task 5): RAM only, under the read lock (an uninstall takes the write lock)
        pthread_rwlock_rdlock(&pk_coreLock);
        if (g.core) pk_coop_lock_tick(g.core);
        pthread_rwlock_unlock(&pk_coreLock);
        pk_flight_tick(g.core);   // FREE FLIGHT's next step (nothing until a game configures it)
        // Its shadow, clouds and bird; with EXPAND drawing its picture next, the clouds go over that (pk_flight_draw_wide).
        bool wideNext = g.wide && g.wideCrc && g.viewW > 0 && g.viewH > 0 && g.viewW <= PK_WIDE_MAX_VIEW_W &&
            g.viewH <= PK_WIDE_MAX_VIEW_H && pkWideFrameFits() && !g.wideSkipNext;
        if (sizeof(color_t) == 4) pk_flight_draw((uint32_t*) g.video, (int) g.vw, (int) g.vh, (int) g.vw, !wideNext);
    }
    pkDrainAudio();
    // WIDE: only with a game it knows and a window set. 32-bit colour, like the screen's upload.
    struct PkWideView v = { g.viewX, g.viewY, g.viewW, g.viewH };
    g.wideSkipped = false;
    if (g.wide && g.wideCrc && v.w > 0 && v.h > 0 && v.w <= PK_WIDE_MAX_VIEW_W && v.h <= PK_WIDE_MAX_VIEW_H &&
        sizeof(color_t) == 4 && pkWideFrameFits() && g.wideSkipNext) {
        pk_wide_skip(g.core, (const uint32_t*) g.video);
        g.wideSkipped = true;
    } else if (g.wide && g.wideCrc && v.w > 0 && v.h > 0 && v.w <= PK_WIDE_MAX_VIEW_W && v.h <= PK_WIDE_MAX_VIEW_H &&
        sizeof(color_t) == 4 && pkWideFrameFits()) {
        pk_wide_view(g.core, (const uint32_t*) g.video, g.wide, v, NULL);
        pk_flight_draw_wide(g.wide, v.w, v.h, v.x, v.y);   // FREE FLIGHT's clouds over the whole picture
        g.wideW = v.w;
        g.wideH = v.h;
        if (pk_wide_fault()) {
            // It faulted drawing (and switched itself off): this game shows the plain frame from now on.
            LOGI("WIDE switched off: signal %d while drawing", pk_wide_fault());
            g.wideCrc = 0;
            g.wideW = g.wideH = 0;
        }
    } else if (g.wide && g.wideCrc && !pk_is_gba(g.core) && pkWideFrameFits()) {
        // A Game Boy game with EXPAND off: its scene alone (the FIELD HUD's field read), nothing drawn.
        pk_wide_probe(g.core, (const uint32_t*) g.video, g.wideSkipNext);
        g.wideW = g.wideH = 0;
    } else {
        g.wideW = g.wideH = 0;
    }
}

// coop-lock (plan 5 Task 5): what pk_coop_lock_jni.c needs of the player's core (pk_core_access.h)
void pk_core_rdlock(void) { pthread_rwlock_rdlock(&pk_coreLock); }
void pk_core_wrlock(void) { pthread_rwlock_wrlock(&pk_coreLock); }
void pk_core_unlock(void) { pthread_rwlock_unlock(&pk_coreLock); }
int pk_core_is_gba(const struct mCore* core) { return pk_is_gba(core); }
uint32_t pk_core_call(struct mCore* core, uint32_t fn, const uint32_t args[4]) {   // a pk_coop_call_fn
    uint32_t r0;
    return pk_call(core, fn, args, &r0) ? r0 : 0xFFFFFFFFu;
}

// --- memory / ROM introspection (for the companion / telemetry read) --------

void pk_read_range(struct mCore* core, uint32_t addr, uint8_t* dst, int len) {
    int i = 0;
    // Bytes up to a word boundary first: busRead32 behaves like the CPU's own
    // LDR, which rounds an unaligned address down and rotates the word, so a
    // range starting at an odd address came back with every 4th byte taken
    // from 4 bytes earlier (Unbound's owned-dex flags start at ...8B9, which
    // turned GIBLE's bit into GALLADE's).
    for (; i < len && ((addr + i) & 3); i++) {
        dst[i] = (uint8_t) core->busRead8(core, addr + i);
    }
    for (; i + 4 <= len; i += 4) {
        uint32_t v = core->busRead32(core, addr + i);
        dst[i] = (uint8_t) v;
        dst[i + 1] = (uint8_t) (v >> 8);
        dst[i + 2] = (uint8_t) (v >> 16);
        dst[i + 3] = (uint8_t) (v >> 24);
    }
    for (; i < len; i++) {
        dst[i] = (uint8_t) core->busRead8(core, addr + i);
    }
}

// Game Boy Color: WRAM bank B's D000-DFFF read by its own address, (B << 16) | addr, whatever SVBK maps
// at D000 right now - Crystal keeps its state in bank 1 but switches D000 to other banks mid-frame
// (Gen2Reader's wramx). False for any other address, which reads over the bus as before.
static bool pk_gb_read_banked(struct mCore* core, uint32_t addr, uint8_t* dst, int len) {
    if (addr < 0x10000u || core->platform(core) != mPLATFORM_GB) {
        return false;
    }
    struct GB* gb = core->board;
    uint32_t bank = addr >> 16, off = addr & 0xFFFFu;
    if (bank > 7 || off < 0xD000u || off + (uint32_t) len > 0xE000u || !gb->memory.wram) {
        memset(dst, 0, (size_t) len);
    } else {
        memcpy(dst, gb->memory.wram + bank * GB_SIZE_WORKING_RAM_BANK0 + (off - 0xD000u), (size_t) len);
    }
    return true;
}

// A GBA cart range read from our own mapping of the ROM file instead of over the bus. mGBA reads the ROM through a
// mapping of the file until something first writes to it (a cheat, a game option's patch, a game's AGBPrint on a
// 32 MB ROM...), then swaps in a copy and unmaps the file (_pristineCow) - without our lock when it happens mid-frame,
// so an IO thread's ROM read in flight faulted on the unmapped page (GBALoad32 from pkReadBytes, Crashlytics 1.3.1 on
// the Thor and the Odin). This mapping only goes in pk_api_deinit, under the write lock. It holds the file's bytes,
// which is what these reads want anyway (pk_cheats_overlay). False for anything else: past the file's end, not the cart.
static bool pk_read_rom_file(uint32_t addr, uint8_t* dst, int len) {
    if (!g.romFile || addr < BASE_CART0 || addr + (uint32_t) len > BASE_CART_SRAM || addr + (uint32_t) len < addr) {
        return false;
    }
    uint32_t off = addr & (SIZE_CART0 - 1);
    if ((size_t) off + (size_t) len > g.romFileSize) {
        return false;
    }
    memcpy(dst, g.romFile + off, (size_t) len);
    return true;
}

bool pk_api_read(uint32_t addr, uint8_t* dst, int len) {
    if (len <= 0) {
        return false;
    }
    pthread_rwlock_rdlock(&pk_coreLock);
    bool ok = g.core != NULL;
    if (ok && pk_gb_read_banked(g.core, addr, dst, len)) {
        // a Color cart's WRAM bank, read by bank
    } else if (ok && pk_read_rom_file(addr, dst, len)) {
        // the ROM, from the file
    } else if (ok) {
        pk_read_range(g.core, addr, dst, len);
        // The ROM as the file has it, under any cheat's hook or ROM patch: the
        // Poller identifies hacks by hashing it (see pk_cheats.c).
        pk_cheats_overlay(addr, dst, len);
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return ok;
}

bool pk_api_rom_code(char out[5]) {
    memset(out, 0, 5);
    if (!g.core) {
        return false;
    }
    if (!pk_is_gba(g.core)) {
        return true;   // no GBA header (0xAC would be GB ROM bytes)
    }
    return pk_api_read(0x080000ACu, (uint8_t*) out, 4);
}

int64_t pk_api_rom_size(void) {
    return g.core ? (int64_t) g.core->romSize(g.core) : 0;
}

bool pk_api_rom_read(int64_t off, uint8_t* dst, int len) {
    if (off < 0 || len <= 0) {
        return false;
    }
    bool ok = false;
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && g.core->platform(g.core) == mPLATFORM_GB) {
        struct GB* gb = g.core->board;
        if (gb->memory.rom && (size_t) off + (size_t) len <= gb->pristineRomSize) {
            memcpy(dst, (const uint8_t*) gb->memory.rom + off, (size_t) len);
            ok = true;
        }
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return ok;
}
