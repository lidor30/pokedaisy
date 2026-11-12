// PokeDaisy — JNI bridge over the mGBA core (libmgba, vendored at 0.10.5).
//
// Phase 0 scope: bring up a GBA core, run frames, expose the RGBA framebuffer
// and interleaved s16 stereo audio, take key input, and do raw save/load state.
// Everything here is called from a single Kotlin "emu thread", which also copies
// pkVideoBuffer()'s frames out for the GL thread (EmulatorView.publishFrame).

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <time.h>

#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/config.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/serialize.h>
#include <mgba-util/vfs.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/arm/isa-inlines.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gb/io.h>
#include <mgba/internal/sm83/sm83.h>

#include "pk_call.h"
#include "pk_cheats.h"
#include "pk_coop.h"
#include "pk_coop_after.h"
#include "pk_coop_field.h"
#include "pk_coop_lock.h"   // coop-lock (plan 5 Task 5)
#include "pk_core_access.h"   // coop-lock (plan 5 Task 5)
#include "pk_rewind.h"
#include "pk_savevf.h"
#include "pk_wide.h"
#include "pk_flight.h"
#include "pk_gb_field.h"
#include "pk_gb_audio.h"
#include "pk_host.h"
#include "nds/pk_nds.h"

#define LOG_TAG "pokedaisy/jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// mGBA's log, for every core in the process (the player's, STEADY's render core, LINK PLAY's). With no logger mGBA
// printf()s each message, and a game reading unmapped memory logs every byte: a hack's BIOS LZ77 copy from a bad
// address held one frame for seconds, and RESTART GAME's wait for the emu thread became an ANR (Crashlytics, 1.4.0).
// Only errors reach logcat, at most a few a second; game errors, stubs and the rest are dropped unformatted.
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
    __android_log_print(ANDROID_LOG_ERROR, "pokedaisy/mgba", "%s: %s", mLogCategoryName(category), line);
}

static struct mLogger pk_mgbaLogger = { .log = pk_mgba_log, .filter = NULL };

__attribute__((constructor)) static void pk_install_mgba_logger(void) {
    mLogSetDefaultLogger(&pk_mgbaLogger);
}

// GBA hardware key bit positions (GBA_KEY_* in mgba/gba/input.h). Kept local so
// this file doesn't depend on the GBA-specific header.
enum {
    PK_KEY_A = 0, PK_KEY_B = 1, PK_KEY_SELECT = 2, PK_KEY_START = 3,
    PK_KEY_RIGHT = 4, PK_KEY_LEFT = 5, PK_KEY_UP = 6, PK_KEY_DOWN = 7,
    PK_KEY_R = 8, PK_KEY_L = 9
};

#define PK_SAMPLE_RATE 48000
/** The GB core's master volume (mGBA's full is 0x100): see pk_gb_config. */
#define PK_GB_VOLUME 0x80

// Game Boy / Color ROMs run on mGBA's GB core too (M_CORE_GB). Red/Blue/Yellow
// are SGB-enhanced, so the core would frame them in a 256x224 Super Game Boy
// border: turn borders off, which keeps every GB game at its 160x144 screen. And half volume (below).
static void pk_gb_config(struct mCore* core) {
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

static int pk_is_gba(const struct mCore* core) {
    return core && core->platform(core) == mPLATFORM_GBA;
}
#define PK_AUDIO_SCRATCH_SAMPLES 8192   // per-channel; * 2 shorts for interleave

static struct {
    struct mCore* core;
    color_t* video;
    unsigned vw, vh;
    struct VFile* saveVf;
    bool saveReadOnly;                  // nothing we load can reach the file
    // A GBA game's ROM file, mapped read-only for the companion's ROM reads (pk_read_rom_file): mGBA's own mapping
    // can be swapped for a copy mid-game. Set and dropped under pk_coreLock's write lock, with the core.
    struct VFile* romFileVf;
    const uint8_t* romFile;
    size_t romFileSize;
    int16_t audio[PK_AUDIO_SCRATCH_SAMPLES * 2];
    int audioAvail;                     // interleaved shorts ready in audio[]
    struct pk_gb_audio gbAudio;         // a Game Boy game's sound, resampled here (pk_gb_audio.c); zeroed for a GBA game
    // REWIND (pk_rewind.c): a state every PK_REWIND_INTERVAL frames while rewindEntries > 0;
    // while rewinding, each frame goes back one of them instead.
    struct mCoreRewindContext rewind;
    int rewindEntries;
    int rewindTick;
    bool rewinding;
    // WIDE (pk_wide.c): the window around the frame with the rebuilt scene, wideW x wideH (0 = not
    // drawn: the screen shows `video`). The window comes from WideCamera (ZOOM, the look-around) per frame.
    uint32_t* wide;
    int wideW, wideH;
    uint32_t wideCrc;                   // the game WIDE draws (its ROM's CRC32; 0 = off)
    volatile int viewX, viewY, viewW, viewH;
    volatile bool wideSkipNext;         // the next frame won't be shown: only note it (pk_wide_skip); false = draw
    bool wideSkipped;                   // the last frame was only noted, not drawn: wide / wideW still hold the one before
} g;

#define PK_REWIND_INTERVAL 2

// A second, fully independent core used only to prerecord FF background-
// music clips (see FfMusicRenderer.kt / EmulatorEngine's "FF music"
// section) — completely separate from `g` (the player's actual running
// game). Never loads a save file, never shown on screen, never touched by
// player input. Isolating it this way means even a mistake in the song-
// injection below (pkRenderForceSong) can only break this disposable core,
// never the player's real session.
static struct {
    struct mCore* core;
    unsigned vw, vh;                    // unused (no video output) but core->init needs a buffer set
    color_t* video;
    int16_t audio[PK_AUDIO_SCRATCH_SAMPLES * 2];
    int audioAvail;
    struct pk_gb_audio gbAudio;         // as g's: STEADY clips of a Game Boy game's music
} rg;

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

// Guards g.core's lifetime, not its state: the emu thread owns frames, saves and
// teardown, but the companion's ROM readers (Pokédex, GUIDE, icons) call
// pkReadBytes from IO threads, and one in flight while the game closed read a
// core pkTeardown had just freed. Readers share it; init / teardown take it alone.
static pthread_rwlock_t pk_coreLock = PTHREAD_RWLOCK_INITIALIZER;

static void pkRewindOff(void) {
    if (g.rewindEntries > 0) {
        pk_rewind_deinit(&g.rewind);
        memset(&g.rewind, 0, sizeof(g.rewind));
    }
    g.rewindEntries = 0;
    g.rewindTick = 0;
}

static void pkTeardown(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    pk_nds_deinit();
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

static void pkRenderDrainAudio(void) {
    if (rg.gbAudio.left) {
        rg.audioAvail = pk_gb_audio_read(&rg.gbAudio, rg.audio, PK_AUDIO_SCRATCH_SAMPLES) * 2;
        return;
    }
    struct blip_t* left = rg.core->getAudioChannel(rg.core, 0);
    struct blip_t* right = rg.core->getAudioChannel(rg.core, 1);
    int avail = blip_samples_avail(left);
    if (avail > PK_AUDIO_SCRATCH_SAMPLES) {
        avail = PK_AUDIO_SCRATCH_SAMPLES;
    }
    if (avail > 0) {
        blip_read_samples(left, rg.audio, avail, 1);
        blip_read_samples(right, rg.audio + 1, avail, 1);
        rg.audioAvail = avail * 2;
    } else {
        rg.audioAvail = 0;
    }
}

static void pkRenderTeardown(void) {
    if (rg.core) {
        pk_gb_audio_detach(&rg.gbAudio, rg.core);
        rg.core->deinit(rg.core);       // no save VFile ever attached — nothing to flush
        rg.core = NULL;
    }
    free(rg.video);
    rg.video = NULL;
    rg.vw = rg.vh = 0;
    rg.audioAvail = 0;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkInit(JNIEnv* env, jobject thiz,
                                                  jstring jRomPath, jstring jSavePath) {
    if (g.core || pk_nds_active()) {
        pkTeardown();
    }

    const char* romPath = (*env)->GetStringUTFChars(env, jRomPath, NULL);
    if (pk_nds_is_rom(romPath)) {
        // A DS cart: melonDS (nds/pk_nds.cpp) instead of mGBA; g.core stays NULL, so every GBA-only call is a no-op.
        const char* savePath = jSavePath ? (*env)->GetStringUTFChars(env, jSavePath, NULL) : NULL;
        pthread_rwlock_wrlock(&pk_coreLock);
        bool ok = pk_nds_init(romPath, savePath, PK_SAMPLE_RATE);
        // EXPAND's window, as a GBA game's (pk_nds_wide_draw fills it)
        if (ok) g.wide = calloc((size_t) PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H, sizeof(uint32_t));
        pthread_rwlock_unlock(&pk_coreLock);
        if (savePath) (*env)->ReleaseStringUTFChars(env, jSavePath, savePath);
        (*env)->ReleaseStringUTFChars(env, jRomPath, romPath);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    struct VFile* rom = VFileOpen(romPath, O_RDONLY);
    struct VFile* romFileVf = rom ? VFileOpen(romPath, O_RDONLY) : NULL;   // pk_read_rom_file's own handle
    (*env)->ReleaseStringUTFChars(env, jRomPath, romPath);
    if (!rom) {
        LOGE("cannot open ROM");
        return JNI_FALSE;
    }

    struct mCore* core = mCoreFindVF(rom);
    if (!core) {
        LOGE("mCoreFindVF: unrecognized ROM");
        rom->close(rom);
        if (romFileVf) romFileVf->close(romFileVf);
        return JNI_FALSE;
    }
    if (!core->init(core)) {
        LOGE("core->init failed");
        rom->close(rom);
        if (romFileVf) romFileVf->close(romFileVf);
        core->deinit(core);
        return JNI_FALSE;
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
        return JNI_FALSE;
    }

    struct VFile* saveVf = NULL;
    g.saveReadOnly = false;
    if (jSavePath) {
        const char* savePath = (*env)->GetStringUTFChars(env, jSavePath, NULL);
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
        (*env)->ReleaseStringUTFChars(env, jSavePath, savePath);
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
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkDeinit(JNIEnv* env, jobject thiz) {
    pkTeardown();
}

JNIEXPORT jobject JNICALL
Java_com_pokedaisy_app_MgbaCore_pkVideoBuffer(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) {
        return (*env)->NewDirectByteBuffer(env, pk_nds_top(), (jlong) PK_NDS_W * PK_NDS_H * 4);
    }
    if (!g.video) {
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, g.video, (jlong) g.vw * g.vh * sizeof(color_t));
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkVideoWidth(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) return PK_NDS_W;
    return (jint) g.vw;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkVideoHeight(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) return PK_NDS_H;
    return (jint) g.vh;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSampleRate(JNIEnv* env, jobject thiz) {
    return PK_SAMPLE_RATE;
}

// The rate the core's audio is resampled to, in samples per emulated second: PK_SAMPLE_RATE
// normally; a little off it while frames are paced by the display (EmulatorEngine's vsync
// pacing), so the audio device still gets PK_SAMPLE_RATE per real second.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetAudioRate(JNIEnv* env, jobject thiz, jdouble rate) {
    if (pk_nds_active()) {
        pk_nds_set_audio_rate(rate);
        return;
    }
    if (g.gbAudio.left) {
        pk_gb_audio_set_rate(&g.gbAudio, rate);
    } else if (g.core && rate > 0) {
        blip_set_rates(g.core->getAudioChannel(g.core, 0), g.core->frequency(g.core), rate);
        blip_set_rates(g.core->getAudioChannel(g.core, 1), g.core->frequency(g.core), rate);
    }
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetKeys(JNIEnv* env, jobject thiz, jint mask) {
    if (pk_nds_active()) {
        pk_nds_set_keys((uint32_t) mask);
        return;
    }
    if (g.core) {
        g.core->setKeys(g.core, (uint32_t) mask);
    }
}

/** pkFinishSave's frames go unheard. */
static void pkDropAudio(void) {
    pkDrainAudio();
    g.audioAvail = 0;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFinishSave(JNIEnv* env, jobject thiz, jint maxFrames) {
    if (pk_nds_active()) return pk_nds_finish_save(maxFrames);
    if (!g.core) return 0;
    return pk_finish_save(g.core, maxFrames, pkDropAudio);
}

// WIDE: the core's frame is the size the configured game's rebuild expects (the GBA's 240 x 160, a Game Boy's 160 x 144).
static bool pkWideFrameFits(void) {
    return (int) g.vw == pk_wide_frame_w() && (int) g.vh == pk_wide_frame_h();
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRunFrame(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) {
        pk_nds_run_frame();
        g.audioAvail = 2 * pk_nds_read_audio(g.audio, PK_AUDIO_SCRATCH_SAMPLES);
        // EXPAND: the camera's window around the frame (the margins drawn on the overworld only)
        g.wideSkipped = false;
        // Not drawn (fast-forward's unseen frames, or one whose 3D the frame before skipped): nothing new to show.
        if (g.wide && g.wideCrc && g.viewW > 0 && g.viewH > 0 && (g.wideSkipNext || !pk_nds_frame_drawn())) {
            g.wideSkipped = true;
        } else if (pk_nds_scale() <= 1 && g.wide && g.wideCrc && g.viewW > 0 && g.viewH > 0 &&
                   pk_nds_wide_draw(g.wide, g.viewX, g.viewY, g.viewW, g.viewH)) {
            // (the GPU path puts its window out itself: pk_nds_presented)
            g.wideW = g.viewW;
            g.wideH = g.viewH;
        } else {
            g.wideW = g.wideH = 0;
        }
        return;
    }
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

// WIDE: draw the game whose ROM has this CRC32 (pk_wide_games.h; 0 = off). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideConfigure(JNIEnv* env, jobject thiz, jint crc) {
    if (pk_nds_active()) {
        // A DS game: any id but 0 turns its EXPAND on (WideScreen.NDS)
        g.wideCrc = (uint32_t) crc;
        pk_nds_wide_enable(crc != 0);
        if (!crc) g.wideW = g.wideH = 0;
        return;
    }
    uint32_t want = g.core ? (uint32_t) crc : 0;
    if (want == g.wideCrc) return;
    g.wideCrc = pk_wide_configure(want) ? want : 0;
}

// SCENE SPEEDS / AUTO-RUN / RUN INDOORS / WILD ENCOUNTERS: read the field with the tables of the game whose ROM has
// this CRC32 (0 = none), and its wild-encounter switch at wildFlag (0 = not found). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFieldConfigure(JNIEnv* env, jobject thiz, jint crc, jint wildFlag, jboolean wildOff) {
    bool gba = g.core && pk_is_gba(g.core);
    pk_field_configure(gba ? (uint32_t) crc : 0);
    pk_field_wild(gba ? (uint32_t) wildFlag : 0, wildOff == JNI_TRUE);
}

// This frame's PK_FIELD_* bits (pk_field_state; 0 = not a game it knows); runIndoors lets the player run indoors,
// wildKeep (percent) thins out wild encounters. Emu thread, between frames.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFieldState(JNIEnv* env, jobject thiz, jboolean runIndoors, jint wildKeep) {
    if (pk_nds_active()) {
        // a DS game's own read (nds/pk_nds_field.cpp); WILD ENCOUNTERS is on / off there (any percent < 100 = off)
        pk_nds_set_wild_off(wildKeep < 100);
        return pk_nds_field_bits();
    }
    return g.core && pk_is_gba(g.core) ? pk_field_state(g.core, runIndoors, wildKeep) : 0;
}

// PLAYER LOOK: the player's sprite set up again from its graphics record (after the look changed, or a state loaded).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkLookRefresh(JNIEnv* env, jobject thiz, jbyteArray pal) {
    if (!g.core || !pk_is_gba(g.core)) return JNI_FALSE;
    jbyte* p = pal && (*env)->GetArrayLength(env, pal) == 32 ? (*env)->GetByteArrayElements(env, pal, NULL) : NULL;
    int ok = pk_look_refresh(g.core, (const uint8_t*) p);
    if (p) (*env)->ReleaseByteArrayElements(env, pal, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// FOLLOWER: its frames + palette (null = none), and whether it's shown this frame. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFollowerSet(JNIEnv* env, jobject thiz, jbyteArray frames, jbyteArray pal) {
    if (!frames || !pal || (*env)->GetArrayLength(env, frames) != 3072 || (*env)->GetArrayLength(env, pal) != 32) {
        pk_follower_set(NULL, NULL);
        return;
    }
    jbyte* f = (*env)->GetByteArrayElements(env, frames, NULL);
    jbyte* p = (*env)->GetByteArrayElements(env, pal, NULL);
    pk_follower_set((const uint8_t*) f, (const uint8_t*) p);
    (*env)->ReleaseByteArrayElements(env, frames, f, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, pal, p, JNI_ABORT);
}

// RIDE ON: the vehicle's views + palette (null = none) and whether it's drawn. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRideSet(JNIEnv* env, jobject thiz, jbyteArray frames, jbyteArray pal, jint width) {
    if (!frames || !pal || (width != 32 && width != 64) || (*env)->GetArrayLength(env, frames) != PK_RIDE_BYTES ||
        (*env)->GetArrayLength(env, pal) != 32) {
        pk_ride_set(NULL, NULL, 32);
        return;
    }
    jbyte* f = (*env)->GetByteArrayElements(env, frames, NULL);
    jbyte* p = (*env)->GetByteArrayElements(env, pal, NULL);
    pk_ride_set((const uint8_t*) f, (const uint8_t*) p, width);
    (*env)->ReleaseByteArrayElements(env, frames, f, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, pal, p, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRideShow(JNIEnv* env, jobject thiz, jboolean on) {
    if (g.core && pk_is_gba(g.core)) pk_ride_show(g.core, on);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFollowerShow(JNIEnv* env, jobject thiz, jboolean on) {
    if (g.core && pk_is_gba(g.core)) pk_follower_show(g.core, on);
}

// Co-op presence: the player's map, fine position, facing, mode and step count this frame (pk_partner_self's
// PK_PARTNER_SELF_N ints), or null off a GBA game the field reads know. Emu thread, once a frame between frames.
JNIEXPORT jintArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkPartnerSelf(JNIEnv* env, jobject thiz) {
    int32_t v[PK_PARTNER_SELF_N];
    if (!g.core || !pk_is_gba(g.core) || !pk_partner_self(g.core, v)) return NULL;
    jintArray out = (*env)->NewIntArray(env, PK_PARTNER_SELF_N);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, PK_PARTNER_SELF_N, (const jint*) v);
    return out;
}

// Co-op presence: the partner's art (PK_PARTNER_ART_BYTES: 42 32x32 frames + palette), or null / any other length = none
// (a wrong-sized array is refused, never read past its end). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkPartnerSet(JNIEnv* env, jobject thiz, jbyteArray art) {
    if (!art || (*env)->GetArrayLength(env, art) != PK_PARTNER_ART_BYTES) {
        pk_partner_set(NULL);
        return;
    }
    jbyte* a = (*env)->GetByteArrayElements(env, art, NULL);
    if (!a) {
        pk_partner_set(NULL);
        return;
    }
    pk_partner_set((const uint8_t*) a);
    (*env)->ReleaseByteArrayElements(env, art, a, JNI_ABORT);
}

// Co-op presence: where the partner is drawn from the next frame on (pk_partner_place). Emu thread, between frames.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkPartnerPlace(JNIEnv* env, jobject thiz, jboolean visible, jint fineX, jint fineY, jint frame,
                                               jboolean hflip, jint blobFrame, jint bob) {
    if (g.core && pk_is_gba(g.core)) pk_partner_place(g.core, visible ? 1 : 0, fineX, fineY, frame, hflip ? 1 : 0, blobFrame, bob);
}

// DAY AND NIGHT: the map's and sprites' palettes tinted (256 = as is). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetTint(JNIEnv* env, jobject thiz, jint r, jint gr, jint b) {
    if (g.core && pk_is_gba(g.core)) pk_tint_set(g.core, r, gr, b);
}

// SCENE SPEEDS' TEXT / AUTO-RUN / RUN INDOORS / WILD ENCOUNTERS: whether the field reads know the ROM with this CRC32.
// Any thread, no core needed.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFieldKnows(JNIEnv* env, jobject thiz, jint crc) {
    return pk_field_knows((uint32_t) crc) ? JNI_TRUE : JNI_FALSE;
}

// WIDE: whether it has tables for the ROM with this CRC32. Any thread, no core needed.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideKnows(JNIEnv* env, jobject thiz, jint crc) {
    return pk_wide_knows((uint32_t) crc) ? JNI_TRUE : JNI_FALSE;
}

// WIDE and the field reads: a hack RetailEngine.kt proved to keep a retail build's engine, by its own row. Any thread, no core
// needed; before the game is configured.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideAddDerived(JNIEnv* env, jobject thiz, jint crc, jint baseCrc, jintArray row) {
    if (!row) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, row);
    jint* a = (*env)->GetIntArrayElements(env, row, NULL);
    if (!a) return JNI_FALSE;
    int ok = pk_wide_add_derived((uint32_t) crc, (uint32_t) baseCrc, (const int32_t*) a, (int) n);
    (*env)->ReleaseIntArrayElements(env, row, a, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// WIDE: the window to draw from the next frame on, in the frame's pixels (its top-left (0, 0)); w 0 = none.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideSetView(JNIEnv* env, jobject thiz, jint x, jint y, jint w, jint h) {
    g.viewX = x;
    g.viewY = y;
    g.viewH = h < 0 ? 0 : h > PK_WIDE_MAX_VIEW_H ? PK_WIDE_MAX_VIEW_H : h;
    g.viewW = w < 0 ? 0 : w > PK_WIDE_MAX_VIEW_W ? PK_WIDE_MAX_VIEW_W : w;
    if (pk_nds_active()) pk_nds_wide_window(x, y, w, h);
}

// The wide frame buffer (room for the largest window; the last frame is pkWideWidth() x pkWideHeight()).
JNIEXPORT jobject JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideBuffer(JNIEnv* env, jobject thiz) {
    if (!g.wide) {
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, g.wide, (jlong) PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H * sizeof(uint32_t));
}

// The last frame's window size, or 0 when it wasn't drawn wide (the screen shows pkVideoBuffer). Emu thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideWidth(JNIEnv* env, jobject thiz) {
    return g.wideW;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideHeight(JNIEnv* env, jobject thiz) {
    return g.wideH;
}

// WIDE: draw the next frame's window (true), or only keep its RAM (false: the screen won't show it). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideSetDraw(JNIEnv* env, jobject thiz, jboolean draw) {
    g.wideSkipNext = !draw;
}

// WIDE: the last frame wasn't drawn (pkWideSetDraw(false)): nothing new to publish. Emu thread.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideSkipped(JNIEnv* env, jobject thiz) {
    return g.wideSkipped ? JNI_TRUE : JNI_FALSE;
}

// WIDE: the signal that switched it off for this game (pk_wide_view's fault guard), or 0. Any thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideFault(JNIEnv* env, jobject thiz) {
    return pk_wide_fault();
}

// ZOOM: whether the last frame wants the game close up (a battle, a menu, a text box). Emu thread.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideWantsClose(JNIEnv* env, jobject thiz) {
    return g.wideW && !pk_wide_wants_close() ? JNI_FALSE : JNI_TRUE;
}

// ZOOM: what the last frame is (PK_WIDE_SCENE_*: the field, a text box, an overlay, a battle starting, off). Emu thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWideScene(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) return pk_nds_wide_scene();
    return g.wideW ? pk_wide_scene() : PK_WIDE_SCENE_OFF;
}

// REWIND: keep [entries] states (0 = off, the buffer freed). Emu thread, like pkRunFrame.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetRewind(JNIEnv* env, jobject thiz, jint entries) {
    if (entries < 0) entries = 0;
    if (entries == g.rewindEntries) return;
    pkRewindOff();
    if (entries > 0 && g.core) {
        pk_rewind_init(&g.rewind, (size_t) entries);
        g.rewindEntries = entries;
    }
}

// While [on], each frame steps back through the REWIND buffer instead of forward.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSetRewinding(JNIEnv* env, jobject thiz, jboolean on) {
    g.rewinding = on && g.rewindEntries > 0;
}

// --- FF-music prerecording: a second, disposable core (see `rg` above) ------
// Same one-frame-then-drain contract as pkRunFrame/pkReadAudio, driven from
// Kotlin the same way (FfMusicRenderer), just on its own separate handle.

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderInit(JNIEnv* env, jobject thiz, jstring jRomPath) {
    if (rg.core) {
        pkRenderTeardown();
    }
    const char* romPath = (*env)->GetStringUTFChars(env, jRomPath, NULL);
    struct VFile* rom = VFileOpen(romPath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, jRomPath, romPath);
    if (!rom) {
        LOGE("pkRenderInit: cannot open ROM");
        return JNI_FALSE;
    }

    struct mCore* core = mCoreFindVF(rom);
    if (!core) {
        LOGE("pkRenderInit: mCoreFindVF failed");
        rom->close(rom);
        return JNI_FALSE;
    }
    if (!core->init(core)) {
        LOGE("pkRenderInit: core->init failed");
        rom->close(rom);
        core->deinit(core);
        return JNI_FALSE;
    }
    mCoreInitConfig(core, NULL);
    pk_gb_config(core);

    unsigned w, h;
    core->desiredVideoDimensions(core, &w, &h);
    // core->init's contract expects a video buffer even though this core's
    // frames are never displayed or read back.
    color_t* video = calloc((size_t) w * h, sizeof(color_t));
    core->setVideoBuffer(core, video, w);

    core->setAudioBufferSize(core, 2048);
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), PK_SAMPLE_RATE);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), PK_SAMPLE_RATE);

    if (!core->loadROM(core, rom)) {
        LOGE("pkRenderInit: core->loadROM failed");
        rom->close(rom);
        core->deinit(core);
        free(video);
        return JNI_FALSE;
    }

    // Deliberately no core->loadSave call — this core never reads or writes
    // the player's actual save file.
    core->reset(core);

    rg.core = core;
    rg.video = video;
    rg.vw = w;
    rg.vh = h;
    rg.audioAvail = 0;
    pk_gb_audio_attach(&rg.gbAudio, core, PK_SAMPLE_RATE);
    LOGI("render core up: %ux%u", w, h);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderDeinit(JNIEnv* env, jobject thiz) {
    pkRenderTeardown();
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderRunFrame(JNIEnv* env, jobject thiz) {
    if (!rg.core) {
        return;
    }
    rg.core->runFrame(rg.core);
    pkRenderDrainAudio();
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderSampleRate(JNIEnv* env, jobject thiz) {
    return PK_SAMPLE_RATE;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderReadAudio(JNIEnv* env, jobject thiz, jshortArray out) {
    if (rg.audioAvail <= 0) {
        return 0;
    }
    jsize cap = (*env)->GetArrayLength(env, out);
    int n = rg.audioAvail;
    if (n > cap) {
        n = cap;
    }
    (*env)->SetShortArrayRegion(env, out, 0, n, rg.audio);
    return n;
}

// Whether a pkCall on the player's core can run now (pk_call_safe): the field-item jobs wait a frame when it can't.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCallSafe(JNIEnv* env, jobject thiz) {
    return g.core && pk_is_gba(g.core) && pk_call_safe(g.core) ? JNI_TRUE : JNI_FALSE;
}

// Calls the game's own function `fn` (r0-r3 = a0-a3) on the player's core, between
// frames (the emu thread), and returns what it returned in r0 - or -1 if it never did.
// FieldItems uses it to use an item from the companion the way the game does
// (VarSet / RemoveBagItem, ExecuteTableBasedItemEffect(mon, item, slot, move))
// instead of steering the game's menus.
JNIEXPORT jlong JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCall(JNIEnv* env, jobject thiz, jlong fn, jint a0, jint a1, jint a2, jint a3) {
    const uint32_t args[4] = {(uint32_t) a0, (uint32_t) a1, (uint32_t) a2, (uint32_t) a3};
    uint32_t r0;
    if (!g.core || !pk_call(g.core, (uint32_t) fn, args, &r0)) {
        return -1;
    }
    return (jlong) r0;
}

// FREE FLIGHT (pk_flight.h): the game's functions through pk_call.
static int pkFlightCall(struct mCore* core, uint32_t fn, const uint32_t args[4], uint32_t* r0) {
    return pk_call(core, fn, args, r0);
}

// The game's addresses (FlightFns.words(), PK_FLIGHT_WORDS of them), or null to switch it off. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightConfigure(JNIEnv* env, jobject thiz, jintArray words) {
    pk_flight_set_call(pkFlightCall);
    if (!words || (*env)->GetArrayLength(env, words) < PK_FLIGHT_WORDS) {
        pk_flight_configure(NULL, 0);
        return;
    }
    uint32_t w[PK_FLIGHT_WORDS];
    (*env)->GetIntArrayRegion(env, words, 0, PK_FLIGHT_WORDS, (jint*) w);
    pk_flight_configure(w, PK_FLIGHT_WORDS);
}

// Take off (PK_FLIGHT_OK, or why not: pk_flight.h). Emu thread, between frames.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightTakeOff(JNIEnv* env, jobject thiz) {
    return g.core && pk_is_gba(g.core) ? pk_flight_take_off(g.core) : PK_FLIGHT_NOT_CONFIGURED;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightLand(JNIEnv* env, jobject thiz) {
    pk_flight_land();
}

// The scenic FLY's route: directions 1 down, 2 up, 3 left, 4 right. Emu thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightRoute(JNIEnv* env, jobject thiz, jbyteArray dirs) {
    jsize n = (*env)->GetArrayLength(env, dirs);
    jbyte* b = (*env)->GetByteArrayElements(env, dirs, NULL);
    int r = pk_flight_route((const uint8_t*) b, n);
    (*env)->ReleaseByteArrayElements(env, dirs, b, JNI_ABORT);
    return r;
}

// FLIGHT AREA: a bit per map (group << 8 | num) the flight may cross into, or null = anywhere. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightArea(JNIEnv* env, jobject thiz, jbyteArray bits) {
    if (!bits) {
        pk_flight_set_area(NULL, 0);
        return;
    }
    jsize n = (*env)->GetArrayLength(env, bits);
    jbyte* b = (*env)->GetByteArrayElements(env, bits, NULL);
    pk_flight_set_area((const uint8_t*) b, n);
    (*env)->ReleaseByteArrayElements(env, bits, b, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightAreaTurned(JNIEnv* env, jobject thiz) {
    return pk_flight_area_turned() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightState(JNIEnv* env, jobject thiz) {
    return pk_flight_state();
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkFlightRouteDone(JNIEnv* env, jobject thiz) {
    return pk_flight_route_done();
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

// Calls the ROM's own m4aSongNumStart(songId, alt) at `addr` (FfMusicRenderer
// finds it with M4aSongs) on the render core, cleanly (pk_call). `alt` picks
// Heart and Soul's alternate soundtrack; other games' ignore r1.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderForceSong(JNIEnv* env, jobject thiz, jlong addr, jint songId, jint alt) {
    if (!rg.core || !rg.core->cpu) {
        return JNI_FALSE;
    }
    const uint32_t args[4] = {(uint32_t) songId, (uint32_t) alt, 0, 0};
    return pk_call(rg.core, (uint32_t) addr, args, NULL) ? JNI_TRUE : JNI_FALSE;
}

// Parks the render core's main loop on `spin` (a Thumb `b .`) so the booted
// game can't change the music while a song records; the VBlank interrupt
// keeps the sound engine running. Steps first until the CPU is in the main
// context (System/User mode, IRQs on) with the VBlank IRQ fully enabled (IME,
// IE bit 0, DISPSTAT bit 3) - games switch it off around loads, and parking
// then left the music frozen (Unbound). Tested with mgba_dump's `park`.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderPark(JNIEnv* env, jobject thiz, jlong spin) {
    if (!rg.core || !rg.core->cpu || !pk_is_gba(rg.core)) {
        return JNI_FALSE;
    }
    struct mCore* core = rg.core;
    struct ARMCore* cpu = (struct ARMCore*) core->cpu;
    for (int i = 0; i < 5000000; i++) {
        if ((cpu->privilegeMode == MODE_SYSTEM || cpu->privilegeMode == MODE_USER) && !cpu->cpsr.i && !cpu->halted
            && (core->busRead16(core, 0x04000208) & 1) && (core->busRead16(core, 0x04000200) & 1)
            && (core->busRead16(core, 0x04000004) & 8)) {
            cpu->gprs[ARM_PC] = (int32_t) ((uint32_t) spin & ~1u);
            _ARMSetMode(cpu, MODE_THUMB);
            ThumbWritePC(cpu);
            return JNI_TRUE;
        }
        core->step(core);
    }
    return JNI_FALSE;
}

// The Game Boy render core's twins of pkRenderPark / pkRenderForceSong (Gen 1's
// music: FfMusicRenderer's Game Boy path). Park: once the main loop runs with
// interrupts on (IME, IE's VBlank bit, the LCD on), its PC goes to `spin` - a
// `jr @` (18 FE) in ROM bank 0 - and only the VBlank handler, which runs the
// sound engine every frame, keeps going. Tested with mgba_dump's `gbpark`.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderGbPark(JNIEnv* env, jobject thiz, jint spin) {
    if (!rg.core || pk_is_gba(rg.core)) {
        return JNI_FALSE;
    }
    struct mCore* core = rg.core;
    struct GB* gb = (struct GB*) core->board;
    struct SM83Core* cpu = (struct SM83Core*) core->cpu;
    for (int i = 0; i < 5000000; i++) {
        core->step(core);   // ends on an instruction boundary (SM83_CORE_FETCH)
        if (gb->memory.ime && (gb->memory.ie & 1) && (gb->memory.io[GB_REG_LCDC] & 0x80) && !cpu->halted && !cpu->irqPending) {
            // A spin in WRAM (Red / Blue: bank 0 holds no `jr @`) is written there first - a byte the
            // parked game never touches again.
            if (spin >= 0xC000 && spin < 0xDFFF) {
                core->busWrite8(core, (uint16_t) spin, 0x18);
                core->busWrite8(core, (uint16_t) (spin + 1), 0xFE);
            }
            cpu->pc = (uint16_t) spin;
            cpu->memory.setActiveRegion(cpu, cpu->pc);
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

// Calls the game's `fn` (bank 0) with A = `a`, C = `c` on the parked GB render core
// and returns once it comes back to `ret` (the park loop, pushed as the return
// address), interrupts held off meanwhile so the VBlank handler can't run halfway
// through it. Gen 1: PlayMusic(a = song id, c = its audio bank); Crystal: PlayMusic(de = song id), so DE is set to c too.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderGbCall(JNIEnv* env, jobject thiz, jint fn, jint a, jint c, jint ret) {
    if (!rg.core || pk_is_gba(rg.core)) {
        return JNI_FALSE;
    }
    struct mCore* core = rg.core;
    struct GB* gb = (struct GB*) core->board;
    struct SM83Core* cpu = (struct SM83Core*) core->cpu;
    bool ime = gb->memory.ime;
    bool pending = cpu->irqPending;
    gb->memory.ime = false;
    cpu->irqPending = false;
    cpu->sp -= 2;
    core->busWrite8(core, cpu->sp, (uint8_t) (ret & 0xFF));
    core->busWrite8(core, (uint16_t) (cpu->sp + 1), (uint8_t) ((ret >> 8) & 0xFF));
    cpu->a = (uint8_t) a;
    cpu->c = (uint8_t) c;
    cpu->d = 0;   // and DE = c: Crystal's PlayMusic takes its song in E
    cpu->e = (uint8_t) c;
    cpu->halted = false;
    cpu->pc = (uint16_t) fn;
    cpu->memory.setActiveRegion(cpu, cpu->pc);
    int ok = 0;
    for (int i = 0; i < 2000000; i++) {
        core->step(core);
        if (cpu->pc == (uint16_t) ret) {
            ok = 1;
            break;
        }
    }
    gb->memory.ime = ime;
    cpu->irqPending = pending;
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Copies up to out.length interleaved shorts of the most recent frame's audio
// into `out`; returns the count written.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkReadAudio(JNIEnv* env, jobject thiz, jshortArray out) {
    if (g.audioAvail <= 0) {
        return 0;
    }
    jsize cap = (*env)->GetArrayLength(env, out);
    int n = g.audioAvail;
    if (n > cap) {
        n = cap;
    }
    (*env)->SetShortArrayRegion(env, out, 0, n, g.audio);
    return n;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkSaveState(JNIEnv* env, jobject thiz, jstring jPath) {
    if (pk_nds_active()) {
        const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
        bool ok = pk_nds_save_state(path);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    if (!g.core) {
        return JNI_FALSE;
    }
    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    // O_RDWR: the extended (mpack) state writer reads back parts of the VFile.
    struct VFile* vf = VFileOpen(path, O_RDWR | O_CREAT | O_TRUNC);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (!vf) {
        return JNI_FALSE;
    }
    bool ok = mCoreSaveStateNamed(g.core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_METADATA);
    vf->close(vf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// True if the save file holds exactly [data] (bytes it doesn't have yet count as
// 0xFF, erased flash: mGBA fills a short file with that). Only the first
// [size] bytes count, so mGBA's RTC footer on a .sav doesn't.
static bool pk_save_file_equals(const uint8_t* data, size_t size) {
    struct VFile* vf = g.saveVf;
    if (!vf || g.saveReadOnly) {
        return true;                    // no file a state load could overwrite
    }
    uint8_t buf[4096];
    size_t off = 0;
    vf->seek(vf, 0, SEEK_SET);
    while (off < size) {
        size_t want = size - off < sizeof(buf) ? size - off : sizeof(buf);
        ssize_t n = vf->read(vf, buf, want);
        if (n <= 0) {
            break;
        }
        if (memcmp(buf, data + off, (size_t) n) != 0) {
            return false;
        }
        off += (size_t) n;
    }
    for (; off < size; ++off) {
        if (data[off] != 0xFF) {
            return false;
        }
    }
    return true;
}

// Does the save inside the state at [path] match the save file the game just
// opened? A state carries the save as it was when the state was made, and
// pkLoadState writes that copy over the file. Run before the auto-resume, so a
// save changed since (another emulator, another saves folder, a file manager)
// is never replaced by an older or empty one. 1 = same, 0 = different,
// -1 = the state holds no save (loading it leaves the file alone) or can't be read.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkStateMatchesSave(JNIEnv* env, jobject thiz, jstring jPath) {
    if (pk_nds_active()) {
        const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
        int r = pk_nds_state_matches_save(path);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return r;
    }
    if (!g.core) {
        return -1;
    }
    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    struct VFile* vf = VFileOpen(path, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (!vf) {
        return -1;
    }
    int result = -1;
    struct mStateExtdata extdata;
    mStateExtdataInit(&extdata);
    if (mCoreExtractExtdata(g.core, vf, &extdata)) {
        struct mStateExtdataItem item;
        if (mStateExtdataGet(&extdata, EXTDATA_SAVEDATA, &item) && item.data && item.size > 0) {
            result = pk_save_file_equals(item.data, (size_t) item.size) ? 1 : 0;
        }
    }
    mStateExtdataDeinit(&extdata);
    vf->close(vf);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkLoadState(JNIEnv* env, jobject thiz, jstring jPath) {
    if (pk_nds_active()) {
        const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
        pthread_rwlock_wrlock(&pk_coreLock);
        bool ok = pk_nds_load_state(path);
        pthread_rwlock_unlock(&pk_coreLock);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    if (!g.core) {
        return JNI_FALSE;
    }
    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    struct VFile* vf = VFileOpen(path, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (!vf) {
        return JNI_FALSE;
    }
    bool ok = mCoreLoadStateNamed(g.core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
    vf->close(vf);
    if (ok) pk_heal_rtc(g.core);
    pk_wide_reset();   // the RAM it kept from the last frame is another moment's
    return ok ? JNI_TRUE : JNI_FALSE;
}

// --- memory / ROM introspection (for the companion / telemetry read) --------
// Called from the emu thread only (same as pkRunFrame), so bus reads never race
// a running frame.

static void pk_read_range(struct mCore* core, uint32_t addr, uint8_t* dst, int len) {
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
// the Thor and the Odin). This mapping only goes in pkTeardown, under the write lock. It holds the file's bytes, which
// is what these reads want anyway (pk_cheats_overlay). False for anything else: past the file's end, not the cart.
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

// pkReadBytes / pkReadInto: [len] bytes at [addr] into [dst], under the core lock; false with no core up.
static bool pk_read_any(uint32_t addr, uint8_t* dst, int len) {
    pthread_rwlock_rdlock(&pk_coreLock);
    bool ok = g.core != NULL || pk_nds_active();
    if (pk_nds_active()) {
        pk_nds_read(addr, dst, len);
    } else if (ok && pk_gb_read_banked(g.core, addr, dst, len)) {
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

// [dst]'s length of bytes at [addr], into [dst] (no new array: RamSnapshot copies the game's RAM with it each sample).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkReadInto(JNIEnv* env, jobject thiz, jlong addr, jbyteArray dst) {
    jsize len = (*env)->GetArrayLength(env, dst);
    if (len <= 0 || len > (1 << 24)) {
        return JNI_FALSE;
    }
    uint8_t* p = (*env)->GetPrimitiveArrayCritical(env, dst, NULL);
    if (!p) {
        return JNI_FALSE;
    }
    bool ok = pk_read_any((uint32_t) addr, p, len);
    (*env)->ReleasePrimitiveArrayCritical(env, dst, p, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkReadBytes(JNIEnv* env, jobject thiz, jlong addr, jint len) {
    if (len <= 0 || len > (1 << 24)) {
        return NULL;
    }
    uint8_t* tmp = malloc((size_t) len);
    if (!tmp) {
        return NULL;
    }
    bool ok = pk_read_any((uint32_t) addr, tmp, len);
    jbyteArray out = ok ? (*env)->NewByteArray(env, len) : NULL;
    if (out) {
        (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte*) tmp);
    }
    free(tmp);
    return out;
}

// Writes `bytes` to the player's core at GBA address `addr` - EWRAM / IWRAM only,
// so nothing else (I/O, VRAM, ROM) can be poked through it. The emu thread, between
// frames: FieldItems puts a SetMonData argument in scratch RAM with it. A Game Boy
// game: its WRAM only, a Color bank by (bank << 16) | D000-DFFF (pk_gb_write).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkWriteBytes(JNIEnv* env, jobject thiz, jlong addr, jbyteArray bytes) {
    jsize len = (*env)->GetArrayLength(env, bytes);
    uint32_t a = (uint32_t) addr;
    if (pk_nds_active()) {
        jbyte* b = (*env)->GetByteArrayElements(env, bytes, NULL);
        bool ok = b && pk_nds_write(a, (const uint8_t*) b, len);
        if (b) (*env)->ReleaseByteArrayElements(env, bytes, b, JNI_ABORT);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    if (g.core && !pk_is_gba(g.core)) {
        jbyte* gbBytes = len > 0 ? (*env)->GetByteArrayElements(env, bytes, NULL) : NULL;
        if (!gbBytes) {
            return JNI_FALSE;
        }
        bool ok = pk_gb_write(g.core, a, (const uint8_t*) gbBytes, len);
        (*env)->ReleaseByteArrayElements(env, bytes, gbBytes, JNI_ABORT);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    bool ram = (a >= 0x02000000u && a + (uint32_t) len <= 0x02040000u) || (a >= 0x03000000u && a + (uint32_t) len <= 0x03008000u);
    if (len <= 0 || !ram || !g.core || !pk_is_gba(g.core)) {
        return JNI_FALSE;
    }
    jbyte* b = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!b) {
        return JNI_FALSE;
    }
    for (jsize i = 0; i < len; i++) {
        g.core->busWrite8(g.core, a + (uint32_t) i, (uint8_t) b[i]);
    }
    (*env)->ReleaseByteArrayElements(env, bytes, b, JNI_ABORT);
    return JNI_TRUE;
}

// A Game Boy game's own call from its main loop (pk_gb_call_at): the game runs on to `at`, then calls `fn` with A = `a`,
// B = `b`, HL = `hl` and comes back to `at`. The emu thread, between frames - Yellow's PC anywhere (GbField.kt).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkGbCallAt(JNIEnv* env, jobject thiz, jint at, jint fn, jint a, jint b, jint hl) {
    return pk_gb_call_at(g.core, (uint16_t) at, (uint16_t) fn, (uint8_t) a, (uint8_t) b, (uint16_t) hl, 2) ? JNI_TRUE : JNI_FALSE;
}

// Same as pkReadBytes, from the FF-music render core (rg): FfMusicRenderer
// reads which song header it actually started, like the live game's key.
JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRenderReadBytes(JNIEnv* env, jobject thiz, jlong addr, jint len) {
    if (!rg.core || len <= 0 || len > (1 << 24)) {
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (!out) {
        return NULL;
    }
    uint8_t* tmp = malloc((size_t) len);
    if (!tmp) {
        return out;
    }
    pk_read_range(rg.core, (uint32_t) addr, tmp, len);
    (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte*) tmp);
    free(tmp);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomCode(JNIEnv* env, jobject thiz) {
    char ndsCode[5];
    if (pk_nds_rom_code(ndsCode)) {
        return (*env)->NewStringUTF(env, ndsCode);   // the DS header's game code (CPUE: Platinum)
    }
    if (!g.core) {
        return NULL;
    }
    if (!pk_is_gba(g.core)) {
        return (*env)->NewStringUTF(env, "");   // no GBA header (0xAC would be GB ROM bytes)
    }
    char code[5] = {0};
    if (!pk_read_any(0x080000ACu, (uint8_t*) code, 4)) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, code);
}

// The cartridge's own bytes [off, off + len) - a Game Boy cart is bank-switched,
// so its ROM can't be read whole over the bus (the Poller hashes it this way).
JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomRead(JNIEnv* env, jobject thiz, jlong off, jint len) {
    if (off < 0 || len <= 0) {
        return NULL;
    }
    jbyteArray out = NULL;
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && g.core->platform(g.core) == mPLATFORM_GB) {
        struct GB* gb = g.core->board;
        if (gb->memory.rom && (size_t) off + (size_t) len <= gb->pristineRomSize) {
            out = (*env)->NewByteArray(env, len);
            if (out) {
                (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte*) gb->memory.rom + off);
            }
        }
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return out;
}

// 0 = GBA, 1 = Game Boy / Color, 2 = Nintendo DS, -1 = no core.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkPlatform(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) {
        return 2;
    }
    if (!g.core) {
        return -1;
    }
    return pk_is_gba(g.core) ? 0 : 1;
}

JNIEXPORT jlong JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomSize(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) return (jlong) pk_nds_rom_size();
    return g.core ? (jlong) g.core->romSize(g.core) : 0;
}

// --- cheats (pk_cheats.c) ----------------------------------------------------

// Checks a cheat's code lines without a core (Settings can run with no game).
// Returns "<directive>\n<'1' / '0' per line>", null on failure.
JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCheatsCheck(JNIEnv* env, jobject thiz, jstring jCode, jint type, jstring jDirective) {
    const char* code = (*env)->GetStringUTFChars(env, jCode, NULL);
    const char* directive = (*env)->GetStringUTFChars(env, jDirective, NULL);
    char* result = pk_cheats_check(code, (int) type, directive);
    (*env)->ReleaseStringUTFChars(env, jDirective, directive);
    (*env)->ReleaseStringUTFChars(env, jCode, code);
    if (!result) {
        return NULL;
    }
    // Directive names and '0'/'1' only: plain ASCII, safe for NewStringUTF.
    jstring out = (*env)->NewStringUTF(env, result);
    free(result);
    return out;
}

// Emu thread: replaces the player's core's cheats with `text` (mGBA .cheats
// format, enabled cheats only; empty = none). Returns the sets loaded, -1 if it
// didn't parse. Under the core lock, so a ROM read never sees half a swap.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCheatsApply(JNIEnv* env, jobject thiz, jstring jText) {
    const char* text = (*env)->GetStringUTFChars(env, jText, NULL);
    pthread_rwlock_wrlock(&pk_coreLock);
    int n = g.core ? pk_cheats_apply(g.core, text, strlen(text)) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    (*env)->ReleaseStringUTFChars(env, jText, text);
    return n;
}

// Emu thread: a game option's ROM patch (ReusableTms.kt) - `value` at `addr`, only
// where the ROM holds `expect` from `expectAddr` (the code around it). Under the
// core lock, so a ROM read never sees half a patch.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomPatch(JNIEnv* env, jobject thiz, jlong expectAddr, jbyteArray jExpect,
                                           jlong addr, jbyteArray jValue) {
    jsize expectLen = (*env)->GetArrayLength(env, jExpect);
    jsize len = (*env)->GetArrayLength(env, jValue);
    uint8_t expect[64];
    uint8_t value[4];
    if (expectLen < 1 || expectLen > (jsize) sizeof(expect) || len < 1 || len > (jsize) sizeof(value)) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, jExpect, 0, expectLen, (jbyte*) expect);
    (*env)->GetByteArrayRegion(env, jValue, 0, len, (jbyte*) value);
    pthread_rwlock_wrlock(&pk_coreLock);
    int ok = g.core ? pk_cheats_patch(g.core, (uint32_t) expectAddr, expect, expectLen, (uint32_t) addr, value, len) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Emu thread: takes every game option's ROM patch out again.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomUnpatch(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core) {
        pk_coop_uninstall(g.core);   // its patches go with all the rest
        pk_coop_lock_uninstall(g.core);   // coop-lock (plan 5 Task 5)
        pk_cheats_unpatch(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
}

// Emu thread: PLAYER LOOK's blocks (PlayerLook.kt) in place of any before - block i is
// `lens[i]` bytes of `data` (back to back) at `addrs[i]`, only where the ROM's CRC32 there is
// `crcs[i]`. All or none: returns how many went in, or -1 (and none in) if one didn't.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomBlocks(JNIEnv* env, jobject thiz, jintArray jAddrs, jintArray jLens,
                                            jintArray jCrcs, jbyteArray jData) {
    jsize n = (*env)->GetArrayLength(env, jAddrs);
    if (n != (*env)->GetArrayLength(env, jLens) || n != (*env)->GetArrayLength(env, jCrcs)) {
        return -1;
    }
    jint* addrs = (*env)->GetIntArrayElements(env, jAddrs, NULL);
    jint* lens = (*env)->GetIntArrayElements(env, jLens, NULL);
    jint* crcs = (*env)->GetIntArrayElements(env, jCrcs, NULL);
    jsize size = (*env)->GetArrayLength(env, jData);
    jbyte* data = (*env)->GetByteArrayElements(env, jData, NULL);
    int done = 0;
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core) {
        pk_coop_uninstall(g.core);   // its block is in the ROM overlay this replaces (and may overlap a new one)
        pk_coop_lock_uninstall(g.core);   // coop-lock (plan 5 Task 5)
        pk_cheats_unblock(g.core);
        jsize at = 0;
        for (; done < n; done++) {
            if (lens[done] < 1 || at + lens[done] > size ||
                !pk_cheats_block(g.core, (uint32_t) addrs[done], (const uint8_t*) data + at, lens[done], (uint32_t) crcs[done])) {
                break;
            }
            at += lens[done];
        }
        if (done < n) {
            pk_cheats_unblock(g.core);
        }
    }
    pthread_rwlock_unlock(&pk_coreLock);
    (*env)->ReleaseByteArrayElements(env, jData, data, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jCrcs, crcs, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jLens, lens, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jAddrs, addrs, JNI_ABORT);
    return done == n ? n : -1;
}

// Emu thread: every PLAYER LOOK block out again.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRomUnblock(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core) {
        pk_coop_uninstall(g.core);
        pk_coop_lock_uninstall(g.core);   // coop-lock (plan 5 Task 5)
        pk_cheats_unblock(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
}

// --- co-op (pk_coop.c's retail check, the link bridge, the after-battle steps; Emerald, GBA only) ---------
// Emu thread, like pkCall. The bridge's install / begin change the ROM overlay and so take the core lock alone (like
// pkRomPatch); the rest only read or write RAM and share it.

// pk_coop's pk_coop_call_fn over the file's pk_call: r0, or 0xFFFFFFFF when the call couldn't run or never returned.
static uint32_t pk_coop_call(struct mCore* core, uint32_t fn, const uint32_t args[4]) {
    uint32_t r0;
    return pk_call(core, fn, args, &r0) ? r0 : 0xFFFFFFFFu;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopInstall(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    int ok = g.core && pk_is_gba(g.core) ? pk_coop_install(g.core) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopRefused(JNIEnv* env, jobject thiz) {
    pthread_rwlock_rdlock(&pk_coreLock);
    const char* r = pk_coop_refused();
    jstring s = (*env)->NewStringUTF(env, r ? r : "");
    pthread_rwlock_unlock(&pk_coreLock);
    return s;
}

JNIEXPORT jstring JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkRefused(JNIEnv* env, jobject thiz) {
    pthread_rwlock_rdlock(&pk_coreLock);
    const char* r = pk_coop_link_refused();
    jstring s = (*env)->NewStringUTF(env, r ? r : "");
    pthread_rwlock_unlock(&pk_coreLock);
    return s;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopUninstall(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core) {
        pk_coop_uninstall(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopBattleStarting(JNIEnv* env, jobject thiz) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int r = g.core && pk_is_gba(g.core) ? pk_coop_battle_starting(g.core) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return r ? JNI_TRUE : JNI_FALSE;
}

// --- plan 6: the link bridge (pk_coop_link.c). Main core only, never the render core. ---
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkInstall(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    int ok = g.core && pk_is_gba(g.core) ? pk_coop_link_install(g.core, pk_coop_call) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkBegin(JNIEnv* env, jobject thiz, jboolean master, jint kind, jint trainerA,
                                                jint trainerB, jbyteArray jName, jint gender, jint otId) {
    if (!jName || (*env)->GetArrayLength(env, jName) < 8 || kind < PK_COOP_LINK_WILD || kind > PK_COOP_LINK_STORY) {
        return JNI_FALSE;
    }
    struct pk_coop_link_setup s;
    memset(&s, 0, sizeof(s));
    s.master = master ? 1 : 0;
    s.kind = (uint8_t) kind;
    s.trainerA = (uint16_t) trainerA;
    s.trainerB = (uint16_t) trainerB;
    (*env)->GetByteArrayRegion(env, jName, 0, 8, (jbyte*) s.partnerName);
    s.partnerGender = gender ? 1 : 0;
    s.partnerOtId = (uint32_t) otId;
    pthread_rwlock_wrlock(&pk_coreLock);
    int ok = g.core && pk_is_gba(g.core) ? pk_coop_link_begin(g.core, &s) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkTake(JNIEnv* env, jobject thiz) {
    uint8_t buf[PK_COOP_LINK_WIRE_HEADER + PK_COOP_LINK_BLOCK_MAX];
    pthread_rwlock_wrlock(&pk_coreLock);
    int n = g.core && pk_is_gba(g.core) ? pk_coop_link_take(g.core, buf, (int) sizeof(buf)) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    if (n <= 0) {
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out) {
        (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte*) buf);
    }
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkGive(JNIEnv* env, jobject thiz, jbyteArray jBlock) {
    if (!jBlock) {
        return JNI_FALSE;
    }
    jsize n = (*env)->GetArrayLength(env, jBlock);
    if (n < PK_COOP_LINK_WIRE_HEADER || n > PK_COOP_LINK_WIRE_HEADER + PK_COOP_LINK_BLOCK_MAX) {
        return JNI_FALSE;
    }
    uint8_t buf[PK_COOP_LINK_WIRE_HEADER + PK_COOP_LINK_BLOCK_MAX];
    (*env)->GetByteArrayRegion(env, jBlock, 0, n, (jbyte*) buf);
    pthread_rwlock_wrlock(&pk_coreLock);
    int ok = g.core && pk_is_gba(g.core) ? pk_coop_link_give(g.core, buf, (int) n) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// The blocks the bridge last refused as malformed / could not classify (pk_coop_link_bad_blocks), for the log; null = none.
JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkBadBlocks(JNIEnv* env, jobject thiz) {
    uint8_t buf[4 * (6 + PK_COOP_LINK_WIRE_HEADER + PK_COOP_LINK_BLOCK_MAX)];
    int n = 0;
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) n = pk_coop_link_bad_blocks(buf, (int) sizeof(buf));
    pthread_rwlock_unlock(&pk_coreLock);
    if (n <= 0) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte*) buf);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkState(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) pk_coop_link_tick(g.core);   // task 5: the end's steps ride the app's per-frame state pump
    int s = g.core && pk_is_gba(g.core) ? pk_coop_link_state(g.core) : PK_COOP_LINK_IDLE;
    pthread_rwlock_unlock(&pk_coreLock);
    return s;
}

// Ends this game's side of the link battle now (pk_coop_link_leave, task 7): blackout = the game's whiteout, else back to the field.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkLeave(JNIEnv* env, jobject thiz, jboolean blackout) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) {
        pk_coop_link_leave(g.core, blackout ? 1 : 0);
    }
    pthread_rwlock_unlock(&pk_coreLock);
}

// The finished battle's result (PK_COOP_LINK_RESULT_SIZE bytes, pk_coop_link_result), null until the end's steps are through.
JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkResult(JNIEnv* env, jobject thiz) {
    uint8_t r[PK_COOP_LINK_RESULT_SIZE];
    int n = 0;
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) {
        n = pk_coop_link_result(g.core, r, sizeof(r));
    }
    pthread_rwlock_unlock(&pk_coreLock);
    if (n != PK_COOP_LINK_RESULT_SIZE) {
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out) {
        (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte*) r);
    }
    return out;
}

// --- plan 6 task 8b: how a joined battle starts and ends from the app (pk_coop_link_start.c, pk_coop_link_story.c, pk_coop_link_end.c) ---

// The partner's identity for host_go / story_go (the OTHER player's name, gender and trainer ID), or the host's for partner_go.
static int pk_link_other(JNIEnv* env, jbyteArray jName, jint gender, jint otId, struct pk_coop_link_setup* s) {
    memset(s, 0, sizeof(*s));
    if (!jName || (*env)->GetArrayLength(env, jName) < 8) {
        return 0;
    }
    (*env)->GetByteArrayRegion(env, jName, 0, 8, (jbyte*) s->partnerName);
    s->partnerName[7] = 0xFF;   // the name came over the network: it always ends
    s->partnerGender = gender ? 1 : 0;
    s->partnerOtId = (uint32_t) otId;
    return 1;
}

// 1 while the host's game is held where a joined battle is offered (wild, two trainers, one trainer with 2+ Pokemon).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkHostHold(JNIEnv* env, jobject thiz) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int r = g.core && pk_is_gba(g.core) ? pk_coop_link_host_hold(g.core) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return r ? JNI_TRUE : JNI_FALSE;
}

// The held battle becomes the link battle (the partner said yes): 1 entered, 0 refused (the held battle exactly as it was).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkHostGo(JNIEnv* env, jobject thiz, jbyteArray jName, jint gender, jint otId) {
    struct pk_coop_link_setup s;
    if (!pk_link_other(env, jName, gender, otId, &s)) {
        return JNI_FALSE;
    }
    pthread_rwlock_wrlock(&pk_coreLock);
    int ok = g.core && pk_is_gba(g.core) ? pk_coop_link_host_go(g.core, &s) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// The partner's start from its free field: 1 started, -1 the field is not free yet (ask again), 0 refused.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkPartnerGo(JNIEnv* env, jobject thiz, jint kind, jint trainerA, jint trainerB,
                                                    jbyteArray jName, jint gender, jint otId) {
    struct pk_coop_link_setup s;
    if (kind < PK_COOP_LINK_WILD || kind > PK_COOP_LINK_SINGLE || trainerA < 0 || trainerA > 0xFFFF || trainerB < 0
        || trainerB > 0xFFFF || !pk_link_other(env, jName, gender, otId, &s)) {
        return 0;
    }
    s.master = 0;
    s.kind = (uint8_t) kind;
    s.trainerA = (uint16_t) trainerA;
    s.trainerB = (uint16_t) trainerB;
    pthread_rwlock_wrlock(&pk_coreLock);
    int r = g.core && pk_is_gba(g.core) ? pk_coop_link_partner_go(g.core, &s) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return r;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryArm(JNIEnv* env, jobject thiz, jint trainerId) {
    if (trainerId <= 0 || trainerId > 0xFFFF) {
        return JNI_FALSE;
    }
    pthread_rwlock_wrlock(&pk_coreLock);
    int ok = g.core && pk_is_gba(g.core) ? pk_coop_story_arm(g.core, pk_coop_call, (uint16_t) trainerId) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// PK_COOP_STORY_*: pk_coop_story_state takes a mismatched hook out itself, so it writes.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryState(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    int s = g.core && pk_is_gba(g.core) ? pk_coop_story_state(g.core) : PK_COOP_STORY_IDLE;
    pthread_rwlock_unlock(&pk_coreLock);
    return s;
}

// Both teams are picked: the link battle begins ([master] = the host; the other player's identity as for host_go). 1 entered, 0 not yet / refused.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryGo(JNIEnv* env, jobject thiz, jboolean master, jbyteArray jName, jint gender, jint otId) {
    struct pk_coop_link_setup s;
    if (!pk_link_other(env, jName, gender, otId, &s)) {
        return JNI_FALSE;
    }
    s.master = master ? 1 : 0;
    pthread_rwlock_wrlock(&pk_coreLock);
    int ok = g.core && pk_is_gba(g.core) ? pk_coop_story_go(g.core, &s) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// The pick screen was backed out of, or the join fell through: the game's own solo battle starts. 1 = started.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryCancel(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    int ok = g.core && pk_is_gba(g.core) ? pk_coop_story_cancel(g.core) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopStoryDisarm(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) {
        pk_coop_story_disarm(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
}

// What the battle caught: [0] who threw the ball (1 the host, 2 the partner), then the Pokemon's 100 bytes; null when nothing was caught.
// Read before pk_coop_link_reset / leave (they zero it).
JNIEXPORT jbyteArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkCaught(JNIEnv* env, jobject thiz) {
    uint8_t buf[101];
    int who = 0;
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) {
        who = pk_coop_link_caught(g.core, buf + 1);
    }
    pthread_rwlock_unlock(&pk_coreLock);
    if (who != 1 && who != 2) {
        return NULL;
    }
    buf[0] = (uint8_t) who;
    jbyteArray out = (*env)->NewByteArray(env, 101);
    if (out) {
        (*env)->SetByteArrayRegion(env, out, 0, 101, (const jbyte*) buf);
    }
    return out;
}

// A story script held by its trainerbattle is let go (pk_coop_release_script): 1 released, 0 nothing held, -1 not yet (ask again).
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopReleaseScript(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    int r = -1;
    if (g.core && pk_is_gba(g.core)) {
        pk_coop_after_set_call(pk_coop_call);
        r = pk_coop_release_script(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return r;
}

// The battle is over and its result read: the bridge goes (hooks out, area zeroed) and the result is dropped.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopLinkReset(JNIEnv* env, jobject thiz) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) {
        pk_coop_link_result_clear(g.core);
        pk_coop_link_reset(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
}

// Emu thread: 1 while the game stands free on the field (pk_coop_on_free_field).
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopOnFreeField(JNIEnv* env, jobject thiz) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int r = g.core && pk_is_gba(g.core) ? pk_coop_on_free_field(g.core) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return r ? JNI_TRUE : JNI_FALSE;
}

// Emu thread: 0 not joinable, 1 wild, 2 two trainers (while Task_BattleStart runs; pk_coop_join_kind).
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopJoinKind(JNIEnv* env, jobject thiz) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int r = g.core && pk_is_gba(g.core) ? pk_coop_join_kind(g.core) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return r;
}

// Emu thread: [trainerA, trainerB] of a two-trainer battle, zeros otherwise.
JNIEXPORT jintArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopJoinTrainers(JNIEnv* env, jobject thiz) {
    int ids[2] = {0, 0};
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) pk_coop_join_trainers(g.core, ids);
    pthread_rwlock_unlock(&pk_coreLock);
    jintArray out = (*env)->NewIntArray(env, 2);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 2, (const jint*) ids);
    return out;
}

// Emu thread: free on the field, or on the START menu's top level.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopPartnerFree(JNIEnv* env, jobject thiz) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int r = g.core && pk_is_gba(g.core) ? pk_coop_partner_free(g.core) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return r ? JNI_TRUE : JNI_FALSE;
}

// coop-field (plan 4 Task 7)
// coop-after (plan 4 Task 8): the battle's results written through the game's own functions (pk_coop_after.c); each takes
// the core lock shared (they call the game like pkCall does) and answers 1 / 0 / -1 as pk_coop_after.h says.
static void pk_coop_cap(long steps) {   // the step cap of pk_call: the game's save takes ~16M steps (0 = the default again)
    pk_call_set_cap(steps);
}

#define COOP_AFTER_GUARD(expr)                                                  \
    pthread_rwlock_rdlock(&pk_coreLock);                                        \
    int r = -1;                                                                 \
    if (g.core && pk_is_gba(g.core)) {                                          \
        pk_coop_after_set_call(pk_coop_call);                                   \
        pk_coop_after_set_cap(pk_coop_cap);                                     \
        r = (expr);                                                             \
    }                                                                           \
    pthread_rwlock_unlock(&pk_coreLock);                                        \
    return r;

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopGiveMon(JNIEnv* env, jobject thiz, jbyteArray jMon) {
    if (!jMon || (*env)->GetArrayLength(env, jMon) != 100) {
        return 2;
    }
    uint8_t mon[100];
    (*env)->GetByteArrayRegion(env, jMon, 0, 100, (jbyte*) mon);
    COOP_AFTER_GUARD(pk_coop_give_mon(g.core, mon))
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopRemoveItem(JNIEnv* env, jobject thiz, jint item, jint count) {
    COOP_AFTER_GUARD(pk_coop_remove_item(g.core, item, count))
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopSetTrainerFlag(JNIEnv* env, jobject thiz, jint id) {
    COOP_AFTER_GUARD(pk_coop_set_trainer_flag(g.core, id))
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopPrize(JNIEnv* env, jobject thiz, jint id, jboolean single) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int r = !g.core || !pk_is_gba(g.core) ? -1 : single ? pk_coop_prize_single(g.core, id) : pk_coop_prize(g.core, id);
    pthread_rwlock_unlock(&pk_coreLock);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopAddMoney(JNIEnv* env, jobject thiz, jint amount) {
    COOP_AFTER_GUARD(pk_coop_add_money(g.core, amount))
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopWritePartyMon(JNIEnv* env, jobject thiz, jint slot, jbyteArray jMon) {
    if (!jMon || (*env)->GetArrayLength(env, jMon) != 100) {
        return 0;
    }
    uint8_t mon[100];
    (*env)->GetByteArrayRegion(env, jMon, 0, 100, (jbyte*) mon);
    COOP_AFTER_GUARD(pk_coop_write_party_mon(g.core, slot, mon))
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopWhiteOut(JNIEnv* env, jobject thiz) {
    COOP_AFTER_GUARD(pk_coop_white_out(g.core))
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopEvolve(JNIEnv* env, jobject thiz, jint slot) {
    COOP_AFTER_GUARD(pk_coop_evolve(g.core, slot))
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkCoopSaveGame(JNIEnv* env, jobject thiz) {
    COOP_AFTER_GUARD(pk_coop_save_game(g.core))
}
// Emu thread: the 32-byte palettes in `from` (back to back) swapped for `to` wherever the
// game has one loaded (pk_cheats_repalette). Returns how many.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkRepalette(JNIEnv* env, jobject thiz, jbyteArray jFrom, jbyteArray jTo) {
    jsize n = (*env)->GetArrayLength(env, jFrom) / 32;
    if (n < 1 || (*env)->GetArrayLength(env, jTo) != 32) {
        return 0;
    }
    jbyte* from = (*env)->GetByteArrayElements(env, jFrom, NULL);
    uint8_t to[32];
    (*env)->GetByteArrayRegion(env, jTo, 0, 32, (jbyte*) to);
    pthread_rwlock_wrlock(&pk_coreLock);
    int swapped = g.core ? pk_cheats_repalette(g.core, (const uint8_t*) from, n, to) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    (*env)->ReleaseByteArrayElements(env, jFrom, from, JNI_ABORT);
    return swapped;
}

// --- Nintendo DS (nds/pk_nds.cpp) --------------------------------------------

// The DS's bottom screen (the touch screen), as pkVideoBuffer is its top one; null without a DS. Emu thread.
JNIEXPORT jobject JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsBottomBuffer(JNIEnv* env, jobject thiz) {
    if (!pk_nds_active()) return NULL;
    return (*env)->NewDirectByteBuffer(env, pk_nds_bottom(), (jlong) PK_NDS_W * PK_NDS_H * 4);
}

// A DS game's frames no one will see (fast-forward's): this one / the next skip their drawing (pk_nds_set_draw).
// The cart's wild encounter rates by map header id ([map, grass, surf] triples), for WILD ENCOUNTERS back on. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsWildRates(JNIEnv* env, jobject thiz, jintArray triples) {
    if (!pk_nds_active() || !triples) return;
    jsize len = (*env)->GetArrayLength(env, triples);
    jint* t = (*env)->GetIntArrayElements(env, triples, NULL);
    pk_nds_set_wild_rates((const int32_t*) t, len / 3);
    (*env)->ReleaseIntArrayElements(env, triples, t, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsSetDraw(JNIEnv* env, jobject thiz, jboolean draw, jboolean drawNext) {
    if (pk_nds_active()) pk_nds_set_draw(draw == JNI_TRUE, drawNext == JNI_TRUE);
}

// DS CPU BOOST: the ARM9 at twice the DS's clock. Emu thread, between frames.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsCpuBoost(JNIEnv* env, jobject thiz, jboolean on) {
    if (pk_nds_active()) pk_nds_set_cpu_boost(on == JNI_TRUE);
}

// DS CORE: which DS core the next pkInit of a DS cart starts (MgbaCore.NDS_CORE_*: 0 melonDS, 1 DSperate). Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsSetCore(JNIEnv* env, jobject thiz, jint core) {
    pk_nds_set_core(core);
}

// The DS core that's up (MgbaCore.NDS_CORE_*), or -1 with no DS game.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsCore(JNIEnv* env, jobject thiz) {
    return pk_nds_core();
}

// A DS game's Action Replay codes (cheats/Cheats.kt DsCheats): words, and how many belong to each cheat. Emu thread.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsCheats(JNIEnv* env, jobject thiz, jintArray jWords, jintArray jLengths) {
    if (!pk_nds_active()) return -1;
    jsize nWords = (*env)->GetArrayLength(env, jWords);
    jsize nCheats = (*env)->GetArrayLength(env, jLengths);
    jint* words = (*env)->GetIntArrayElements(env, jWords, NULL);
    jint* lengths = (*env)->GetIntArrayElements(env, jLengths, NULL);
    int n = pk_nds_set_cheats((const uint32_t*) words, nWords, (const int32_t*) lengths, nCheats);
    (*env)->ReleaseIntArrayElements(env, jLengths, lengths, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jWords, words, JNI_ABORT);
    return n;
}

// DS LAYOUT floats the X menu on one screen: it comes back after a menu screen left by touch. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsKeepStartMenu(JNIEnv* env, jobject thiz, jboolean on) {
    if (pk_nds_active()) pk_nds_set_keep_start_menu(on == JNI_TRUE);
}

// DS LAYOUT's transition hold (EmulatorEngine.dsHold): the two screens' brightness fade (pk_nds_fade). Emu thread, after a frame.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsFade(JNIEnv* env, jobject thiz) {
    return pk_nds_active() ? pk_nds_fade() : 0;
}

// DS RESOLUTION (nds/pk_nds_gl.h): 1 = the software renderer, 2-8 the GPU's. Taken before the next frame. Emu thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsSetScale(JNIEnv* env, jobject thiz, jint scale) {
    if (pk_nds_active()) pk_nds_set_scale(scale);
}

// The scale in effect (1: software), and whether the GPU path can run here (-1 not tried yet, 0 no, 1 yes).
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsScale(JNIEnv* env, jobject thiz) {
    return pk_nds_active() ? pk_nds_scale() : 1;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsGpuSupported(JNIEnv* env, jobject thiz) {
    return pk_nds_gpu_supported();
}

// The game screen's SurfaceTexture's Surface for the GPU path's frames (null: none). Any thread.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsGlSetWindow(JNIEnv* env, jobject thiz, jobject surface) {
    pk_nds_set_window(surface ? ANativeWindow_fromSurface(env, surface) : NULL);
}

// Whether the last frame went out through the GPU path (the view then takes it from the SurfaceTexture). Emu thread.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsGlPresented(JNIEnv* env, jobject thiz) {
    return pk_nds_active() && pk_nds_presented() ? JNI_TRUE : JNI_FALSE;
}

// The GPU path's frame presented at [timestamp] (SurfaceTexture.getTimestamp): x, y, w, h in DS pixels, texels wide,
// high into out (6 ints). False when it's not known. Any thread.
JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsGlFrameInfo(JNIEnv* env, jobject thiz, jlong timestamp, jintArray out) {
    int32_t v[6];
    if (!out || (*env)->GetArrayLength(env, out) < 6 || !pk_nds_gpu_frame_info((int64_t) timestamp, v)) return JNI_FALSE;
    (*env)->SetIntArrayRegion(env, out, 0, 6, (const jint*) v);
    return JNI_TRUE;
}

// STEADY FF music for a DS game (nds/pk_nds_music.h): the song id the game plays (-1 = not mapped), a copy of the game
// that plays on from now at 1x, a frame of its audio, its fade-out, and stopping it. Emu thread, between frames.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsBgm(JNIEnv* env, jobject thiz) {
    return pk_nds_active() ? pk_nds_bgm() : -1;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsBgmState(JNIEnv* env, jobject thiz) {
    return pk_nds_active() ? pk_nds_bgm_state() : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsMusicStart(JNIEnv* env, jobject thiz) {
    return pk_nds_active() && pk_nds_music_start() ? JNI_TRUE : JNI_FALSE;
}

// Fills out (interleaved stereo shorts) with one frame of the copy's audio; returns the shorts written.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsMusicRead(JNIEnv* env, jobject thiz, jshortArray out) {
    if (!pk_nds_active()) return 0;
    jsize len = (*env)->GetArrayLength(env, out);
    jshort* buf = (*env)->GetShortArrayElements(env, out, NULL);
    int n = pk_nds_music_read(buf, len / 2);
    (*env)->ReleaseShortArrayElements(env, out, buf, 0);
    return 2 * n;
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsMusicFade(JNIEnv* env, jobject thiz, jint frames) {
    if (pk_nds_active()) pk_nds_music_fade_out(frames);
}

JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsMusicStop(JNIEnv* env, jobject thiz) {
    if (pk_nds_active()) pk_nds_music_stop();
}

// A DS game's own functions (pk_nds_call.h): posts an action for the next frame (0 = not now), and how it did.
// Emu thread, between frames.
JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsAction(JNIEnv* env, jobject thiz, jint action, jint a0, jint a1, jint a2, jint a3) {
    uint32_t args[4] = {(uint32_t) a0, (uint32_t) a1, (uint32_t) a2, (uint32_t) a3};
    return pk_nds_active() ? pk_nds_action((int) action, args) : 0;
}

// The job's results once it ran (op i's into [i]), or null.
JNIEXPORT jintArray JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsActionResults(JNIEnv* env, jobject thiz, jint seq) {
    uint32_t res[32];
    if (!pk_nds_active() || !pk_nds_action_results((int) seq, res)) return NULL;
    jintArray out = (*env)->NewIntArray(env, 32);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 32, (const jint*) res);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsActionStatus(JNIEnv* env, jobject thiz, jint seq) {
    return pk_nds_active() ? pk_nds_action_status((int) seq) : -1;
}

// The stylus on the DS's bottom screen at (x, y) in its pixels, or lifted. Any thread: applied on the next frame.
JNIEXPORT void JNICALL
Java_com_pokedaisy_app_MgbaCore_pkNdsTouch(JNIEnv* env, jobject thiz, jboolean down, jint x, jint y) {
    pk_nds_touch(down == JNI_TRUE, (int) x, (int) y);
}
