// PokeDaisy for the Nintendo 3DS: the game on the top screen - a GBA game on armGBA (../emu), a Game Boy one on
// libmgba - and the companion (../core) on the bottom one. A homebrew .3dsx; see README.md.
//
// The screens are drawn by the GPU (gpu.c). Audio is ndsp, stereo PCM16: mGBA's blip buffers resampled to the DSP's own
// rate at the 3DS's refresh rate, or armGBA's 32768 Hz played that much faster.
#include <3ds.h>

#include <dirent.h>
#include <fcntl.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>

#include <mgba-util/vfs.h>
#include <mgba/core/blip_buf.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/serialize.h>
#include <mgba/internal/gba/audio.h>

#include "../core/pd_canvas.h"
#include "../core/pd_game.h"
#include "../core/pd_games.h"
#include "../core/pd_library.h"
#include "../core/pd_menu.h"
#include "../core/pd_save.h"
#include "../core/pd_settings.h"
#include "../core/pd_style.h"
#include "../host/pd_session.h"
#include "../host/pd_mixer.h"
#include "../emu/pd_armgba.h"
#include "host.h"
#include "../../app/src/main/cpp/pk_gb_audio.h"
#include "../../app/src/main/cpp/pk_host.h"
#include "devlink.h"
#include "../../app/src/main/cpp/pk_call.h"
#include "gpu.h"
#include "../../app/src/main/cpp/pk_wide.h"

// The dev build keeps to a folder of its own: a test never touches the player's games and saves.
#ifdef PD_DEV
#define BASE_DIR "/pokedaisy-dev"
#else
#define BASE_DIR "/pokedaisy"
#endif
#define ROM_DIR BASE_DIR "/roms"
#define BACKUP_DIR ROM_DIR "/pokedaisy-backups"
#define STATE_DIR BASE_DIR "/states"
// An optional gba_bios.bin (else armGBA's own BIOS), and what armGBA keeps for itself: each game's warm list.
#define SYSTEM_DIR BASE_DIR "/system"
#define WORK_DIR BASE_DIR "/armgba"
#define LOG_FILE "sdmc:" BASE_DIR "/log.txt"
#define SETTINGS_FILE "sdmc:" BASE_DIR "/settings.ini"
#define MAX_BACKUPS 10
#define MAX_ROMS 256

#define TOP_W 400
#define TOP_H 240

// GBA key bits (GBA_KEY_*), as in the app's pokedaisy_jni.c.
enum {
    GBA_A = 1 << 0, GBA_B = 1 << 1, GBA_SELECT = 1 << 2, GBA_START = 1 << 3,
    GBA_RIGHT = 1 << 4, GBA_LEFT = 1 << 5, GBA_UP = 1 << 6, GBA_DOWN = 1 << 7,
    GBA_R = 1 << 8, GBA_L = 1 << 9,
};


static uint32_t topPx[TOP_W * TOP_H];
static uint32_t bottomPx[PD_UI_WIDTH * PD_UI_HEIGHT];
static struct pd_settings settings;

// --- logging: mGBA's default logger prints every unmapped I/O write ---

static void noopLog(struct mLogger* log, int category, enum mLogLevel level, const char* format, va_list args) {
    (void) log; (void) category; (void) level; (void) format; (void) args;
}
static struct mLogger silentLogger = { .log = noopLog, .filter = NULL };

// --- audio ---

// The DSP's own rate, 268111856 / 8192: a channel at any other one is resampled, and mGBA's frontend's 32768 through
// NDSP_INTERP_NONE drops a sample every 800 or so, 40 small clicks a second under everything. Here a sample out is a sample
// played.
#define DSP_RATE 32728.4978f
#define AUDIO_SAMPLES 384
#define DSP_BUFFERS 8
// How many chunks the queue keeps ahead, and how far either way it may wander before the rate moves.
#define AUDIO_TARGET 5
#define AUDIO_SLACK 2
#define AUDIO_MADE_EASE 0.02f  // about 0.6 s of chunks
#define AUDIO_RATE_EASE 0.1f
#define AUDIO_QUEUE_PULL 0.004f
#define AUDIO_RATE_MIN 0.85f
#define AUDIO_RATE_MAX 1.01f

static bool hasSound;
static bool audioMuted; // while fast-forwarding
static ndspWaveBuf dspBuffer[DSP_BUFFERS];
static int16_t* audioBuf;
static int bufferId;
static struct mAVStream stream;
static float audioRate = 1.0f, audioRateSet = 1.0f, audioMade = 1.0f;
// The channel's rate at full speed: the DSP's own for mGBA's resampled sound; armGBA's 32768 a game second, at the
// screen's 59.83 frames a second for the GBA's 59.73.
static float dspBase = DSP_RATE;
#define SCREEN_HZ (268111856.0 / 4481136.0)
#define ARMGBA_RATE ((float) (PD_ARMGBA_SAMPLE_RATE * SCREEN_HZ / PD_ARMGBA_FPS))
static u64 audioLast;
static unsigned audioGaps, audioDrops; // the queue ran dry / had no room, for the dev build's stat
static unsigned audioGapsTotal, audioQueueMin = 99;

// A game under full speed makes its sound too slowly, and a dry queue is a hole in the music, several a second: that was
// the crunch. So the channel follows how fast the chunks really come: the sound plays as slowly as the game runs, a slow
// glide rather than a gap, and at full speed the rate is exactly 1 again.
static void audio_follow(int queued) {
    u64 now = svcGetSystemTick();
    if (audioLast && now > audioLast) {
        float made = (float) AUDIO_SAMPLES * (float) SYSCLOCK_ARM11 / (float) (now - audioLast) / dspBase;
        if (made < 0.3f) made = 0.3f; // a pause (the HOME menu) says nothing about the game's speed
        if (made > 2.0f) made = 2.0f;
        audioMade += (made - audioMade) * AUDIO_MADE_EASE;
    }
    audioLast = now;
    int off = queued - AUDIO_TARGET;
    float want = audioMade + AUDIO_QUEUE_PULL * (float) off;
    if (want > 0.985f && off >= -AUDIO_SLACK && off <= AUDIO_SLACK) want = 1.0f;
    if (want < AUDIO_RATE_MIN) want = AUDIO_RATE_MIN;
    if (want > AUDIO_RATE_MAX) want = AUDIO_RATE_MAX;
    audioRate += (want - audioRate) * AUDIO_RATE_EASE;
    if (want == 1.0f && audioRate > 0.999f) audioRate = 1.0f;
    float moved = audioRate - audioRateSet;
    if (moved > 0.0005f || moved < -0.0005f || (audioRate == 1.0f && moved != 0.0f)) {
        ndspChnSetRate(0, dspBase * audioRate);
        audioRateSet = audioRate;
    }
}

// The next free chunk, its queue followed; NULL when every one is still queued.
static ndspWaveBuf* audio_next(void) {
    int queued = 0, free = -1;
    for (int i = 0; i < DSP_BUFFERS; i++) {
        int at = (bufferId + i) & (DSP_BUFFERS - 1);
        if (dspBuffer[at].status == NDSP_WBUF_QUEUED || dspBuffer[at].status == NDSP_WBUF_PLAYING) queued++;
        else if (free < 0) free = at;
    }
    if (free < 0) {
        audioDrops++;
        audio_follow(queued);
        return NULL;
    }
    if (!queued) audioGaps++, audioGapsTotal++;
    if ((unsigned) queued < audioQueueMin) audioQueueMin = (unsigned) queued;
    audio_follow(queued);
    bufferId = free;
    ndspWaveBuf* b = &dspBuffer[free];
    int16_t* data = b->data_pcm16;
    memset(b, 0, sizeof(*b));
    b->data_pcm16 = data;
    b->nsamples = AUDIO_SAMPLES;
    return b;
}

static void audio_send(ndspWaveBuf* b) {
    DSP_FlushDataCache(b->data_pcm16, AUDIO_SAMPLES * 2 * sizeof(int16_t));
    ndspChnWaveBufAdd(0, b);
}

// mGBA's GBA sound calls this in blip_add_delta's place (CMakeLists.txt).
void pd_blip_delta(blip_t* blip, unsigned clock, int delta) {
    if (delta) blip_add_delta(blip, clock, delta);
}

static void post_audio(struct mAVStream* s, blip_t* left, blip_t* right) {
    (void) s;
    ndspWaveBuf* b = hasSound && !audioMuted ? audio_next() : NULL;
    if (!b) {
        // Muted, or no room: this chunk goes rather than wait.
        blip_clear(left);
        blip_clear(right);
        return;
    }
    blip_read_samples(left, b->data_pcm16, AUDIO_SAMPLES, true);
    blip_read_samples(right, b->data_pcm16 + 1, AUDIO_SAMPLES, true);
    audio_send(b);
}

// A Game Boy game's sound comes from pk_gb_audio (never its core's own audio channels): each full chunk into a free buffer.
static struct pk_gb_audio gbAudio;
static bool gbAudioOn;

static void gb_audio_pump(void) {
    if (!gbAudioOn) return;
    static int16_t chunk[AUDIO_SAMPLES * 2];
    static int have;
    for (;;) {
        have += pk_gb_audio_read(&gbAudio, chunk + have * 2, AUDIO_SAMPLES - have);
        if (have < AUDIO_SAMPLES) return;
        have = 0;
        ndspWaveBuf* b = hasSound && !audioMuted ? audio_next() : NULL;
        if (!b) continue;
        memcpy(b->data_pcm16, chunk, sizeof(chunk));
        audio_send(b);
    }
}

// armGBA's sound as each frame makes it, cut into the DSP's chunks; a chunk with nowhere to go (muted in fast-forward,
// no sound, every buffer still queued) is dropped rather than waited for.
static void armgba_audio(void* ctx, const int16_t* stereo, size_t frames) {
    (void) ctx;
    static int16_t chunk[AUDIO_SAMPLES * 2];
    static size_t have;
    while (frames) {
        size_t n = AUDIO_SAMPLES - have < frames ? AUDIO_SAMPLES - have : frames;
        memcpy(chunk + have * 2, stereo, n * 2 * sizeof(int16_t));
        have += n;
        stereo += n * 2;
        frames -= n;
        if (have < AUDIO_SAMPLES) break;
        have = 0;
        ndspWaveBuf* b = hasSound && !audioMuted ? audio_next() : NULL;
        if (!b) continue;
        memcpy(b->data_pcm16, chunk, sizeof(chunk));
        audio_send(b);
    }
}

static void audio_init(void) {
    // ndsp needs the DSP firmware dump (sdmc:/3ds/dspfirm.cdc); without it the
    // game just runs silent.
    if (ndspInit()) return;
    ndspSetOutputMode(NDSP_OUTPUT_STEREO);
    ndspSetOutputCount(1);
    ndspChnReset(0);
    ndspChnSetFormat(0, NDSP_FORMAT_STEREO_PCM16);
    // One channel at full level can't clip, and the default soft clipping bends the loud passages.
    ndspSetClippingMode(NDSP_CLIP_NORMAL);
    // At the DSP's rate a sample stays a sample; linear only matters while the rate follows a slow game.
    ndspChnSetInterp(0, NDSP_INTERP_LINEAR);
    ndspChnSetRate(0, DSP_RATE);
    ndspChnWaveBufClear(0);
    audioBuf = linearMemAlign(AUDIO_SAMPLES * DSP_BUFFERS * 2 * sizeof(int16_t), 0x80);
    if (!audioBuf) {
        ndspExit();
        return;
    }
    memset(dspBuffer, 0, sizeof(dspBuffer));
    for (int i = 0; i < DSP_BUFFERS; i++) {
        dspBuffer[i].data_pcm16 = &audioBuf[AUDIO_SAMPLES * i * 2];
        dspBuffer[i].nsamples = AUDIO_SAMPLES;
    }
    hasSound = true;
}

static void audio_exit(void) {
    if (!hasSound) return;
    ndspChnWaveBufClear(0);
    ndspExit();
    linearFree(audioBuf);
    hasSound = false;
}

// --- files ---

static void make_dirs(void) {
    mkdir("sdmc:" BASE_DIR, 0777);
    mkdir("sdmc:" ROM_DIR, 0777);
    mkdir("sdmc:" BACKUP_DIR, 0777);
    mkdir("sdmc:" STATE_DIR, 0777);
    mkdir("sdmc:" SYSTEM_DIR, 0777);
    mkdir("sdmc:" WORK_DIR, 0777);
}

// What the app plays: a GBA game, or a Game Boy / Color one.
static bool ends_with_gba(const char* name) {
    size_t gbn = strlen(name);
    if (gbn > 3 && !strcasecmp(name + gbn - 3, ".gb")) return true;
    if (gbn > 4 && !strcasecmp(name + gbn - 4, ".gbc")) return true;
    size_t n = strlen(name);
    return n > 4 && !strcasecmp(name + n - 4, ".gba");
}

static int cmp_str(const void* a, const void* b) {
    return strcasecmp(*(const char* const*) a, *(const char* const*) b);
}

// The .gba files in ROM_DIR, sorted; each entry is malloc'd.
static int list_roms(char** out) {
    int n = 0;
    DIR* d = opendir("sdmc:" ROM_DIR);
    if (!d) return 0;
    struct dirent* e;
    while ((e = readdir(d)) && n < MAX_ROMS) {
        if (e->d_name[0] != '.' && ends_with_gba(e->d_name)) out[n++] = strdup(e->d_name);
    }
    closedir(d);
    qsort(out, (size_t) n, sizeof(char*), cmp_str);
    return n;
}

static bool copy_file(const char* from, const char* to) {
    FILE* in = fopen(from, "rb");
    if (!in) return false;
    FILE* out = fopen(to, "wb");
    if (!out) {
        fclose(in);
        return false;
    }
    static char buf[16 * 1024];
    size_t n;
    bool ok = true;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) {
            ok = false;
            break;
        }
    }
    fclose(in);
    if (fclose(out) != 0) ok = false;
    return ok;
}

// Whether name is one of base's backups: exactly "<base>-YYYYmmdd-HHMMSS.sav"
// (a prefix match would count "<base>-2"'s backups as base's and prune them).
static bool is_backup_of(const char* name, const char* base) {
    size_t baseLen = strlen(base);
    if (strncmp(name, base, baseLen)) return false;
    const char* rest = name + baseLen;
    static const char PATTERN[] = "-########-######.sav";
    if (strlen(rest) != sizeof(PATTERN) - 1) return false;
    for (size_t i = 0; PATTERN[i]; i++) {
        if (PATTERN[i] == '#' ? (rest[i] < '0' || rest[i] > '9') : rest[i] != PATTERN[i]) return false;
    }
    return true;
}

// Save files are sacred (CLAUDE.md): every start - and every state load -
// copies the save into BACKUP_DIR first, keeping the newest MAX_BACKUPS per game.
static void backup_save(const char* base, const char* savePath) {
    struct stat st;
    if (stat(savePath, &st) || st.st_size == 0) return;
    char stamp[32];
    time_t now = time(NULL);
    // The 3DS clock is local time; gmtime keeps it as it is.
    strftime(stamp, sizeof(stamp), "%Y%m%d-%H%M%S", gmtime(&now));
    char to[512];
    snprintf(to, sizeof(to), "sdmc:" BACKUP_DIR "/%s-%s.sav", base, stamp);
    copy_file(savePath, to);

    // Prune: the names sort by time, so the oldest come first.
    char* names[64];
    int n = 0;
    DIR* d = opendir("sdmc:" BACKUP_DIR);
    if (!d) return;
    struct dirent* e;
    while ((e = readdir(d)) && n < 64) {
        if (is_backup_of(e->d_name, base)) names[n++] = strdup(e->d_name);
    }
    closedir(d);
    qsort(names, (size_t) n, sizeof(char*), cmp_str);
    for (int i = 0; i < n; i++) {
        if (i < n - MAX_BACKUPS) {
            char p[512];
            snprintf(p, sizeof(p), "sdmc:" BACKUP_DIR "/%s", names[i]);
            remove(p);
        }
        free(names[i]);
    }
}

// --- the game ---

static uint32_t gba_keys(u32 held) {
    uint32_t k = 0;
    if (held & KEY_A) k |= GBA_A;
    if (held & KEY_B) k |= GBA_B;
    if (held & KEY_SELECT) k |= GBA_SELECT;
    if (held & KEY_START) k |= GBA_START;
    if (held & KEY_RIGHT) k |= GBA_RIGHT; // KEY_RIGHT = D-pad or circle pad
    if (held & KEY_LEFT) k |= GBA_LEFT;
    if (held & KEY_UP) k |= GBA_UP;
    if (held & KEY_DOWN) k |= GBA_DOWN;
    if (held & KEY_R) k |= GBA_R;
    if (held & KEY_L) k |= GBA_L;
    return k;
}

static void show_message(const char* line1, const char* line2) {
    struct pd_canvas top;
    pd_canvas_init(&top, TOP_W, TOP_H, topPx);
    pd_splash_draw(&top, line1, line2, "Press B.");
    gpu_set_top(topPx);
    while (aptMainLoop()) {
        hidScanInput();
        if ((hidKeysDown() | devlink_keys()) & (KEY_B | KEY_A) || devlink_restart_wanted()) break;
        gpu_draw(false, 0);
        devlink_after_frame();
    }
}

// The state file's time for LOAD STATE, or false if there's none. stat()
// leaves the time 0 on the SD card; archive_getmtime reads it.
// Where the frame's time goes, in the dev build (devlink.h's stats).
#ifdef PD_DEV
#define DEV_MARK(name) u64 name = svcGetSystemTick()
#define TICKS_TO_US(t) ((unsigned) ((t) / (SYSCLOCK_ARM11 / 1000000)))
#else
#define DEV_MARK(name)
#endif

// The stylus, or the dev link's: true while one is down.
static bool read_touch(u32 held, int* x, int* y) {
    if (devlink_touch(x, y)) return true;
    if (!(held & KEY_TOUCH)) return false;
    touchPosition t;
    hidTouchRead(&t);
    *x = t.px;
    *y = t.py;
    return true;
}

// GAME VOLUME / MUTE GAME: the sound channel's level.
static void apply_volume(void) {
    if (!hasSound) return;
    float v = settings.gameMuted ? 0.0f : settings.gameVolume / 100.0f;
    float mix[12] = { v, v };
    ndspChnSetMix(0, mix);
}

// The left stick for the QUICK MENU's ring: degrees clockwise from up, -1 near the middle.
static int stick_angle(void) {
    circlePosition pad;
    hidCircleRead(&pad);
    if (pad.dx * pad.dx + pad.dy * pad.dy < 80 * 80) return -1;
    float a = atan2f((float) pad.dx, (float) pad.dy) * 180.0f / 3.14159265f;
    return (int) (a < 0 ? a + 360 : a) % 360;
}

struct game_files {
    char base[256];
    char saveSd[520];
};

// mGBA's 3DS VFile takes the path on the SD card; stdio wants the sdmc: in front.
static void state_path(void* ctx, int slot, char* vf, size_t vfLen, char* path, size_t pathLen) {
    const struct game_files* f = ctx;
    snprintf(vf, vfLen, STATE_DIR "/%s.ss%d", f->base, slot + 1);
    snprintf(path, pathLen, "sdmc:%s", vf);
}

static void backup_for_load(void* ctx) {
    const struct game_files* f = ctx;
    backup_save(f->base, f->saveSd);
}

static void settings_changed(void* ctx) {
    (void) ctx;
    pd_settings_save(&settings, SETTINGS_FILE);
}

// A Game Boy cart's own screen, not a Super Game Boy border (pk_gb_config).
static void pk_gb_settings(struct mCore* core) {
    if (core->platform(core) != mPLATFORM_GB) return;
    mCoreConfigSetIntValue(&core->config, "sgb.borders", 0);
    core->reloadConfigOption(core, "sgb.borders", &core->config);
    // mGBA's 0x100 overflows the GB mixer's int16 in loud passages (PK_GB_VOLUME).
    mCoreConfigSetIntValue(&core->config, "volume", 0x80);
    core->reloadConfigOption(core, "volume", &core->config);
}

static void cheat_path(void* ctx, uint32_t crc, char* path, size_t len) {
    (void) ctx;
    snprintf(path, len, "sdmc:" BASE_DIR "/cheats/%08lX.cheats", (unsigned long) crc);
}

// FLIGHT AREA's maps for one save of a ROM (the app's flight-area/<rom>-<save>.txt).
static void flight_area_path(void* ctx, uint32_t crc, uint32_t key, char* path, size_t len) {
    (void) ctx;
    mkdir("sdmc:" BASE_DIR "/flight-area", 0777);
    snprintf(path, len, "sdmc:" BASE_DIR "/flight-area/%08lX-%08lX.txt", (unsigned long) crc, (unsigned long) key);
}

static void write_card(const char* file, const struct pd_game* g, const struct pd_snapshot* s);

// Runs one game until the player leaves (true) or the app is closed (false).
static bool run_game(const char* file) {
    char romPath[512], savePath[512], base[256];
    snprintf(romPath, sizeof(romPath), ROM_DIR "/%s", file);
    snprintf(base, sizeof(base), "%s", file);
    char* dot = strrchr(base, '.');
    if (dot) *dot = 0;
    snprintf(savePath, sizeof(savePath), ROM_DIR "/%s.sav", base);
    char saveSd[520];
    snprintf(saveSd, sizeof(saveSd), "sdmc:%s", savePath);

    struct VFile* rom = VFileOpen(romPath, O_RDONLY);
    if (!rom) {
        show_message("Couldn't open the game:", file);
        return true;
    }
    uint8_t header[0x150] = { 0 }; // a GBA cart's header, and a Game Boy cart's (its logo at 0x104)
    rom->seek(rom, 0, SEEK_SET);
    rom->read(rom, header, sizeof(header));
    rom->seek(rom, 0, SEEK_SET);
    struct pd_game game;
    pd_game_detect(&game, header, (size_t) rom->size(rom));

    size_t nameLen = strlen(file);
    bool gbName = (nameLen > 3 && !strcasecmp(file + nameLen - 3, ".gb")) || (nameLen > 4 && !strcasecmp(file + nameLen - 4, ".gbc"));
    bool armgba = !gbName && pd_armgba_wants(header, sizeof(header));
#ifdef PD_DEV
    // The dev build's switch for comparing the cores: sdmc:/pokedaisy-dev/armgba/mgba runs GBA games on mGBA.
    {
        FILE* useMgba = fopen("sdmc:" WORK_DIR "/mgba", "rb");
        if (useMgba) {
            fclose(useMgba);
            armgba = false;
        }
    }
#endif
    struct mCore* core = NULL;
    backup_save(base, saveSd);
    audioRate = audioRateSet = audioMade = 1.0f;
    audioLast = 0;
    if (armgba) {
        // armGBA: its recompiler, its renderer on the New 3DS's third and fourth cores and its mixer on the fourth.
        rom->close(rom);
        dspBase = ARMGBA_RATE;
        if (hasSound) ndspChnSetRate(0, dspBase);
        char romSd[520];
        snprintf(romSd, sizeof(romSd), "sdmc:%s", romPath);
        struct pd_armgba_host emuHost = { .audio = armgba_audio, .log = host_emu_log, .systemDir = "sdmc:" SYSTEM_DIR,
                                          .workDir = "sdmc:" WORK_DIR, .threaded = host_is_new_3ds(), .romBuffer = host_rom_arena };
#ifdef PD_DEV
        // The dev build's switch: sdmc:/pokedaisy-dev/armgba/nothreads draws on the game's core.
        FILE* noThreads = fopen("sdmc:" WORK_DIR "/nothreads", "rb");
        if (noThreads) {
            fclose(noThreads);
            emuHost.threaded = false;
        }
#endif
        char error[96] = "";
        host_log("game: %s, heap %lu KiB free", file, (unsigned long) host_heap_free() >> 10);
        core = pd_armgba_load(romSd, saveSd, &emuHost, error, sizeof(error));
        if (!core) {
            host_log("game: didn't load: %s", error);
            show_message("The game didn't start:", error);
            return true;
        }
        core->setVideoBuffer(core, (color_t*) gpu_game_buffer(), GPU_GAME_STRIDE);
        host_log("game: running on %s; heap %lu KiB free", pd_armgba_recompiling() ? "armGBA's recompiler" : "armGBA's INTERPRETER",
                 (unsigned long) host_heap_free() >> 10);
        gpu_double_game(true);
        gpu_set_bars(0);
    } else {
        // On mGBA, read into the ROM arena (mCorePreloadVF: the linear memory can't map a game).
        core = mCoreFindVF(rom);
        if (!core || !core->init(core)) {
            rom->close(rom);
            show_message("Not a game PokeDaisy plays:", file);
            return true;
        }
        mCoreInitConfig(core, NULL);
        // mGBA's renderer on the New 3DS's third core, as its own 3DS front end has it.
        if (host_is_new_3ds()) mCoreConfigSetIntValue(&core->config, "threadedVideo", 1);
        core->setVideoBuffer(core, (color_t*) gpu_game_buffer(), GPU_GAME_STRIDE);
        pk_gb_settings(core);
        core->setAudioBufferSize(core, AUDIO_SAMPLES);
        // This closes rom.
        if (!mCorePreloadVF(core, rom)) {
            core->deinit(core);
            show_message("The game didn't load (too big?):", file);
            return true;
        }
        // As mGBA's own 3DS front end has it (gui-runner.c): skips the busy loops games wait in.
        if (core->platform(core) == mPLATFORM_GBA) ((struct GBA*) core->board)->idleOptimization = IDLE_LOOP_DETECT;
        struct VFile* save = VFileOpen(savePath, O_CREAT | O_RDWR);
        if (save) core->loadSave(core, save);
        bool gb = core->platform(core) == mPLATFORM_GB;
        gpu_double_game(!gb);
        gpu_set_bars(gb ? 0xFFFFFF : 0);
        // A game frame a refresh: its 59.73 frames' sound in the 59.83 the screen shows (both Game Boys' frames are the
        // GBA's length in time).
        double ratio = GBAAudioCalculateRatio(1, 268111856.f / 4481136.f, 1);
        dspBase = DSP_RATE;
        if (hasSound) ndspChnSetRate(0, dspBase);
        if (gb) {
            gbAudioOn = pk_gb_audio_attach(&gbAudio, core, DSP_RATE * ratio);
        } else {
            stream.postAudioBuffer = post_audio;
            core->setAVStream(core, &stream);
            if (hasSound) {
                blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), DSP_RATE * ratio);
                blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), DSP_RATE * ratio);
            }
        }
        core->reset(core);
        host_log("game: %s on mGBA", file);
    }
    unsigned videoW = 240, videoH = 160;
    core->desiredVideoDimensions(core, &videoW, &videoH);
    gpu_set_game_size((int) videoW, (int) videoH);
    pk_heal_rtc(core);

    static struct pd_session session;
    memset(&session, 0, sizeof(session));
    session.game = game;
    struct game_files gameFiles;
    snprintf(gameFiles.base, sizeof(gameFiles.base), "%s", base);
    snprintf(gameFiles.saveSd, sizeof(gameFiles.saveSd), "%s", saveSd);
    bool wideThread = gpu_wide_thread(pd_session_wide_finish);
    struct pd_session_host files = { state_path, backup_for_load, settings_changed, cheat_path, &gameFiles,
                                     wideThread ? gpu_wide_go : NULL, wideThread ? gpu_wide_join : NULL, NULL, flight_area_path };
#ifdef PD_DEV
    files.clock = svcGetSystemTick;
#endif
    session.host = (struct pd_host_info) { .count = 2 };
    session.host.labels[0] = "FILE";
    snprintf(session.host.values[0], sizeof(session.host.values[0]), "%s", file);
    session.host.labels[1] = "SPEED";
    snprintf(session.host.values[1], sizeof(session.host.values[1]), "-");
    pd_session_start(&session, core, gpu_game_buffer(), &settings, &files);
    apply_volume();
    struct pd_ui* uiPtr = &session.ui;
    struct pd_host_info* hostPtr = &session.host;
    struct pd_canvas bottom;
    pd_canvas_init(&bottom, PD_UI_WIDTH, PD_UI_HEIGHT, bottomPx);

    bool touching = false;
    unsigned fpsFrames = 0;
    u64 busyTicks = 0;
    const uint32_t* perfPicture = NULL;
    int perfWidth = 0;
    u64 fpsStart = osGetTime();
    u64 lastTick = fpsStart;
#ifdef PD_DEV
    u64 devSecond = fpsStart;
#endif
    bool appRunning = true;
    u32 lastInjected = 0, withheld = 0;
#ifdef PD_DEV
    struct devlink_stats dev = { 0 };
    u64 loopStart = svcGetSystemTick();
#endif

    while ((appRunning = aptMainLoop())) {
        hidScanInput();
        u32 injected = devlink_keys();
        u32 held = hidKeysHeld() | injected, down = hidKeysDown() | (injected & ~lastInjected);
        lastInjected = injected;
        if (devlink_take_leave() || devlink_restart_wanted()) break;
#ifdef PD_DEV
        int optScreen, optAspect;
        if (devlink_take_opt(&optScreen, &optAspect)) {
            if (optScreen >= 0) settings.screenMode = optScreen;
            if (optAspect >= 0) settings.aspect = optAspect;
            session.redraw = true;
        }
        int wildSpecies, wildLevel;
        if (session.game.kind != PD_GAME_GB && pk_call_safe(core) && session.game.cfg && session.game.cfg->pcSetupScript && devlink_take_wild(&wildSpecies, &wildLevel)) {
            // setwildbattle species level item; dowildbattle; end (pd_sim's `wild`).
            const struct pd_config* cfg = session.game.cfg;
            uint8_t script[] = { 0xB6, (uint8_t) wildSpecies, (uint8_t) (wildSpecies >> 8), (uint8_t) wildLevel, 0, 0, 0xB7, 0x02 };
            pd_session_write(&session, cfg->pcScript, script, sizeof(script));
            uint32_t args[4] = { cfg->pcScript, 0, 0, 0 };
            pk_call(core, cfg->pcSetupScript, args, NULL);
        }
        int teachSlot, teachIndex, teachMove;
        const struct pd_party_fns* partyFns = session.game.cfg ? session.game.cfg->partyFns : NULL;
        if (session.game.kind != PD_GAME_GB && pk_call_safe(core) && partyFns && partyFns->setMonData && partyFns->scratch &&
            devlink_take_teach(&teachSlot, &teachIndex, &teachMove)) {
            // The party Pokemon's move by the game's SetMonData (MON_DATA_MOVE1 + index), as pd_sim's `teach`.
            uint8_t v[2] = { (uint8_t) teachMove, (uint8_t) (teachMove >> 8) };
            pd_session_write(&session, partyFns->scratch, v, 2);
            uint32_t args[4] = { partyFns->party + (uint32_t) teachSlot * 100, 13u + (uint32_t) (teachIndex + partyFns->monFieldShift),
                                 partyFns->scratch, 0 };
            pk_call(core, partyFns->setMonData, args, NULL);
        }
#endif

        // X on the field sends the buttons to the companion (the app's bottom focus), elsewhere it's BACK; Y the QUICK
        // MENU; ZL the tab before. With the buttons on the companion L / R step the tabs, the D-pad and A its cursor;
        // X, START, or B with nothing left to go back from bring them back.
        if (uiPtr->keyFocus) {
            static const u32 DIRS[4] = { KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT };
            static int heldFor[4];
            if (down & (KEY_X | KEY_START)) pd_session_set_focus(&session, false);
            else if ((down & KEY_B) && !pd_ui_back(uiPtr)) pd_session_set_focus(&session, false);
            else if (down & KEY_A) pd_ui_focus_press(uiPtr);
            else if (down & (KEY_L | KEY_R)) pd_ui_next_tab(uiPtr, down & KEY_L ? -1 : 1);
            for (int i = 0; i < 4; i++) {
                heldFor[i] = held & DIRS[i] ? heldFor[i] + 1 : 0;
                if ((down & DIRS[i]) || (heldFor[i] >= 24 && heldFor[i] % 6 == 0)) {
                    pd_ui_focus_move(uiPtr, i);
                    session.redraw = true;
                }
            }
            if (down) session.redraw = true;
            withheld |= held;
        } else {
            if (down & KEY_X) {
                if (pd_session_can_focus(&session)) {
                    pd_session_set_focus(&session, true);
                    withheld |= held;
                } else {
                    pd_ui_back(uiPtr);
                }
                session.redraw = true;
            }
            if (down & KEY_ZL) { pd_ui_next_tab(uiPtr, -1); session.redraw = true; }
        }
        // Keys pressed on the companion stay away from the game until they're let go.
        withheld &= held;

        int touchX, touchY;
        if (read_touch(held, &touchX, &touchY)) {
            session.redraw |= pd_ui_touch(uiPtr, touchX, touchY, true);
            touching = true;
        } else if (touching) {
            session.redraw |= pd_ui_touch(uiPtr, 0, 0, false);
            touching = false;
        }

        bool leave = false;
        enum pd_action action = pd_ui_take_action(uiPtr);
        if (!pd_session_action(&session, action)) {
            switch (action) {
            case PD_ACTION_LEAVE_GAME:
                leave = true;
                break;
            case PD_ACTION_RESTART_GAME:
                pk_finish_save(core, PK_FINISH_SAVE_FRAMES, NULL);
                core->reset(core);
                pk_heal_rtc(core);
                break;
            case PD_ACTION_SETTINGS_CHANGED:
                pd_settings_save(&settings, SETTINGS_FILE);
                apply_volume();
                break;
            default:
                break;
            }
        }
        if (leave) break;

        struct pd_input in = { .held = gba_keys(held & ~withheld), .down = gba_keys(down & ~withheld),
                               .quickMenu = (down & KEY_Y) && !uiPtr->keyFocus,
                               .ffHeld = held & KEY_ZR, .stickAngle = stick_angle() };
        DEV_MARK(coreStart);
        u64 busyStart = svcGetSystemTick();
        u64 tickNow = osGetTime();
        unsigned before = session.frame;
        pd_session_tick(&session, &in, (unsigned) (tickNow - lastTick));
        gb_audio_pump();
        lastTick = tickNow;
        unsigned frames = session.frame - before;
        if (frames) pd_session_set_video(&session, gpu_frame_done());
        audioMuted = session.ff;
        busyTicks += svcGetSystemTick() - busyStart;
        DEV_MARK(uiStart);

        fpsFrames += (unsigned) frames;
        u64 now = osGetTime();
        if (now - fpsStart >= 1000) {
            // Against the GBA's own 268111856 / 4481136 = 59.73 frames a second.
            unsigned pct = (unsigned) ((u64) fpsFrames * 1000 * 100 * 4481136 / ((now - fpsStart) * 268111856ULL));
            snprintf(hostPtr->values[1], sizeof(hostPtr->values[1]), "%u%% (%u FPS)", pct, fpsFrames);
            // The game's own core, how much of the last second it was busy: 100 and the game can't keep up.
            unsigned cpu = (unsigned) (busyTicks / (SYSCLOCK_ARM11 / 1000) * 100 / (now - fpsStart));
            perfPicture = settings.showPerf ? pd_session_perf((unsigned) ((u64) fpsFrames * 10000 / (now - fpsStart)), cpu > 100 ? 100 : cpu, &perfWidth) : NULL;
            busyTicks = 0;
            fpsFrames = 0;
            fpsStart = now;
            u8 battery = 0;
            if (R_SUCCEEDED(MCUHWC_GetBatteryLevel(&battery))) hostPtr->batteryPct = battery;
            if (uiPtr->tab == PD_TAB_SETTINGS) session.redraw = true;
        }

        if (session.redraw) {
            DEV_MARK(redrawStart);
            pd_session_draw(&session, &bottom);
            gpu_set_bottom(bottomPx);
#ifdef PD_DEV
            unsigned took = TICKS_TO_US(svcGetSystemTick() - redrawStart);
            dev.redraws++;
            if (took > dev.redrawWorstUs) dev.redrawWorstUs = took;
#endif
        }
        DEV_MARK(drawStart);
        gpu_set_perf(perfPicture, session.perfRight - perfWidth, 3, settings.showPerf ? perfWidth : 0);
        perfPicture = NULL;
        gpu_hold_game(pd_session_holds_frame(&session));
        gpu_set_wide(session.wideReady ? pd_session_wide(&session) : NULL, session.wideW, session.wideH);
        const struct pd_wide_hole* hole = &session.wideHole;
        gpu_set_wide_hole(hole->x, hole->y, hole->w, hole->h, hole->gameX, hole->gameY);
        if (session.overlayDirty) gpu_set_overlay(pd_session_overlay(&session));
        gpu_show_overlay(session.overlayShown, session.overlayDim);
        gpu_draw(true, pd_session_screen(&session));
        devlink_after_frame();
#ifdef PD_DEV
        {
            u64 end = svcGetSystemTick();
            dev.coreUs += TICKS_TO_US(uiStart - coreStart);
            dev.uiUs += TICKS_TO_US(drawStart - uiStart);
            dev.drawUs += TICKS_TO_US(end - drawStart);
            unsigned whole = TICKS_TO_US(end - loopStart);
            if (whole > dev.worstUs) dev.worstUs = whole;
            if (whole > 17500) dev.late++;
            {
                static unsigned loops, late, lateCore, lateDraw, lateRest, framesTotal;
                loops++;
                framesTotal += (unsigned) frames;
                unsigned coreUs = TICKS_TO_US(uiStart - coreStart), drawUs = TICKS_TO_US(end - drawStart);
                if (whole > 17500) {
                    late++;
                    if (coreUs > 12000) lateCore++;
                    else if (drawUs > 10000) lateDraw++;
                    else lateRest++;
                }
                dev.totLoops = loops, dev.totLate = late, dev.totLateCore = lateCore, dev.totLateDraw = lateDraw;
                dev.totLateRest = lateRest, dev.totGaps = audioGapsTotal, dev.totFrames = framesTotal, dev.queueMin = audioQueueMin;
            }
            loopStart = end;
            dev.frames += (unsigned) frames;
            if (now - devSecond >= 1000) {
                devSecond = now;
                dev.runUs = TICKS_TO_US(session.prof.run);
                dev.waitUs = TICKS_TO_US(session.prof.wait);
                dev.wideUs = TICKS_TO_US(session.prof.wide);
                dev.snapUs = TICKS_TO_US(session.prof.snap);
                dev.flightTickUs = TICKS_TO_US(session.prof.flightTick);
                dev.flightDrawUs = TICKS_TO_US(session.prof.flightDraw);
                dev.flightState = session.flightState;
                {
                    extern uint64_t renderWaitTicks, renderWorkTicks, jitCompileTicks, jitFlushTicks;
                    extern volatile u64 gpuWideTicks, gpuDrawTicks;
                    dev.agWaitUs = TICKS_TO_US(renderWaitTicks);
                    dev.agRenderUs = TICKS_TO_US(renderWorkTicks);
                    dev.agCompileUs = TICKS_TO_US(jitCompileTicks);
                    dev.agFlushUs = TICKS_TO_US(jitFlushTicks);
                    dev.wideThreadUs = TICKS_TO_US(gpuWideTicks);
                    dev.drawJobUs = TICKS_TO_US(gpuDrawTicks);
                    extern volatile u64 gpuTexTicks, gpuCanvasTicks, gpuBeginTicks, gpuRestTicks;
                    dev.jobTexUs = TICKS_TO_US(gpuTexTicks);
                    dev.jobBeginUs = TICKS_TO_US(gpuBeginTicks);
                    dev.jobCanvasUs = TICKS_TO_US(gpuCanvasTicks);
                    dev.jobRestUs = TICKS_TO_US(gpuRestTicks);
                    gpuTexTicks = gpuCanvasTicks = gpuBeginTicks = gpuRestTicks = 0;
                    renderWaitTicks = renderWorkTicks = jitCompileTicks = jitFlushTicks = 0;
                    {
                        static unsigned mixesWas;
                        dev.mixes = pd_mixer_mixes - mixesWas;
                        mixesWas = pd_mixer_mixes;
                    }
                    if (armgba) {
                        void pd_armgba_jit_counts(uint32_t out[4]);
                        static uint32_t was[4];
                        uint32_t now[4];
                        pd_armgba_jit_counts(now);
                        dev.agStepped = now[0] - was[0];
                        dev.agCompiled = now[1] - was[1];
                        dev.agInvalidated = now[2] - was[2];
                        dev.agCodeWrites = now[3] - was[3];
                        memcpy(was, now, sizeof(was));
                    }
                    gpuWideTicks = gpuDrawTicks = 0;
                }
                if (core->platform(core) == mPLATFORM_GBA && !armgba) {
                    // Whether mGBA still skips the game's wait loop (a scene that stops it costs twice the CPU).
                    const struct GBA* gba = core->board;
                    dev.idleMode = gba->idleOptimization;
                    dev.idleLoop = gba->idleLoop;
                    dev.idleFails = (unsigned) gba->idleDetectionFailures;
                }
                memset(&session.prof, 0, sizeof(session.prof));
                dev.gaps = audioGaps;
                dev.drops = audioDrops;
                dev.rate = (unsigned) (audioRate * 1000);
                audioGaps = audioDrops = 0;
                devlink_report(&dev);
                memset(&dev, 0, sizeof(dev));
                audioQueueMin = 99;
            }
        }
#endif
    }

    // A save the game is writing finishes, unheard, before the core stops; then deinit writes it back (the 3DS
    // VFile's unmap flushes it).
    audioMuted = true;
    pk_finish_save(core, PK_FINISH_SAVE_FRAMES, NULL);
    audioMuted = false;
    if (hasSound) ndspChnWaveBufClear(0);
    if (gbAudioOn) pk_gb_audio_detach(&gbAudio, core);
    gbAudioOn = false;
    gpu_wide_join();
    write_card(file, &session.game, &session.snap);
    // armGBA writes the save if the game changed it, and its warm list; mGBA's VFile flushes on unmap.
    core->deinit(core);
    host_log("game: closed");
    return appRunning;
}

// --- the game list ---

#define CARD_DIR BASE_DIR "/cards"
#define LIBRARY_FILE "sdmc:" BASE_DIR "/library.tsv"

static void base_of(const char* file, char* base, size_t len) {
    snprintf(base, len, "%s", file);
    char* dot = strrchr(base, '.');
    if (dot) *dot = 0;
}

static long lib_read_rom(void* ctx, const char* file, long offset, void* buf, long len) {
    (void) ctx;
    char path[512];
    snprintf(path, sizeof path, "sdmc:" ROM_DIR "/%s", file);
    FILE* f = fopen(path, "rb");
    if (!f) return -1;
    long n = fseek(f, offset, SEEK_SET) ? 0 : (long) fread(buf, 1, (size_t) len, f);
    fclose(f);
    return n;
}

static void save_path_of(const char* file, char* path, size_t len) {
    char base[256];
    base_of(file, base, sizeof base);
    snprintf(path, len, "sdmc:" ROM_DIR "/%s.sav", base);
}

static long lib_read_save(void* ctx, const char* file, uint8_t* buf, long max) {
    (void) ctx;
    char path[600];
    save_path_of(file, path, sizeof path);
    FILE* f = fopen(path, "rb");
    if (!f) return -1;
    long n = (long) fread(buf, 1, (size_t) max, f);
    fclose(f);
    return n;
}

static bool same_file(const char* path, const uint8_t* bytes, long len) {
    static uint8_t back[PD_SAVE_MAX_SIZE];
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    long n = (long) fread(back, 1, sizeof back, f);
    fclose(f);
    return n == len && !memcmp(back, bytes, (size_t) len);
}

// Save files are sacred: each is backed up, then written beside itself and read back, and only once every one has, put in
// place. Nothing is replaced while any of them is in doubt.
static bool lib_write_saves(void* ctx, int n, const char* const* files, const uint8_t* const* bytes, const long* lens, char* why, size_t whyLen) {
    (void) ctx;
    char paths[2][600], temps[2][620];
    if (n > 2) return false;
    for (int i = 0; i < n; i++) {
        char base[256];
        base_of(files[i], base, sizeof base);
        save_path_of(files[i], paths[i], sizeof paths[i]);
        snprintf(temps[i], sizeof temps[i], "%.590s.new", paths[i]);
        backup_save(base, paths[i]);
        FILE* f = fopen(temps[i], "wb");
        bool ok = f && fwrite(bytes[i], 1, (size_t) lens[i], f) == (size_t) lens[i];
        if (f && fclose(f)) ok = false;
        if (!ok || !same_file(temps[i], bytes[i], lens[i])) {
            for (int k = 0; k <= i; k++) remove(temps[k]);
            snprintf(why, whyLen, "Couldn't write %s", files[i]);
            host_log("save tools: %s didn't write", temps[i]);
            return false;
        }
    }
    for (int i = 0; i < n; i++) {
        remove(paths[i]);
        if (rename(temps[i], paths[i])) {
            snprintf(why, whyLen, "Couldn't put %s in place", files[i]);
            host_log("save tools: %s didn't rename; its backup is in " BACKUP_DIR, temps[i]);
            return false;
        }
        host_log("save tools: wrote %s", paths[i]);
    }
    return true;
}

// The whole ROM in the arena the games use (no game runs while the library is up).
static const uint8_t* lib_load_rom(void* ctx, const char* file, size_t* size) {
    (void) ctx;
    char path[512];
    snprintf(path, sizeof path, "sdmc:" ROM_DIR "/%s", file);
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t* buf = len > 0 ? host_rom_arena((size_t) len) : NULL;
    bool ok = buf && fread(buf, 1, (size_t) len, f) == (size_t) len;
    fclose(f);
    if (!ok) return NULL;
    *size = (size_t) len;
    return buf;
}

static void card_path(const char* file, char* path, size_t len) {
    char base[256];
    base_of(file, base, sizeof base);
    snprintf(path, len, "sdmc:" CARD_DIR "/%s.card", base);
}

static bool lib_read_card(void* ctx, const char* file, struct pd_game_card* out) {
    (void) ctx;
    char path[600];
    card_path(file, path, sizeof path);
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    bool ok = fread(out, 1, sizeof *out, f) == sizeof *out;
    fclose(f);
    return ok && out->magic == PD_CARD_MAGIC;
}

// What the game looked like as it was left, for the library's top screen.
static void write_card(const char* file, const struct pd_game* g, const struct pd_snapshot* s) {
    static struct pd_game_card card, before;
    if (!pd_game_card_fill(&card, g, s, (uint32_t) time(NULL))) return;
    mkdir("sdmc:" CARD_DIR, 0777);
    char path[600];
    card_path(file, path, sizeof path);
    // Left before the map was up (the title screen): the place it was last left in still stands.
    if (!card.place[0] && lib_read_card(NULL, file, &before)) snprintf(card.place, sizeof card.place, "%s", before.place);
    FILE* f = fopen(path, "wb");
    if (!f) return;
    fwrite(&card, 1, sizeof card, f);
    fclose(f);
}

static void lib_settings_changed(void* ctx) {
    (void) ctx;
    pd_settings_save(&settings, SETTINGS_FILE);
    apply_volume();
}

static struct pd_library library;
static bool cacheDirty;

static void lib_cache_changed(void* ctx) {
    (void) ctx;
    cacheDirty = true;
}

static void write_cache(void) {
    static char text[PD_LIBRARY_MAX * 400];
    size_t n = pd_library_cache_write(&library, text, sizeof text);
    FILE* f = fopen(LIBRARY_FILE, "wb");
    if (!f) return;
    fwrite(text, 1, n, f);
    fclose(f);
    cacheDirty = false;
}

static void read_cache(void) {
    static char text[PD_LIBRARY_MAX * 400 + 1];
    FILE* f = fopen(LIBRARY_FILE, "rb");
    if (!f) return;
    size_t n = fread(text, 1, sizeof text - 1, f);
    fclose(f);
    text[n] = 0;
    pd_library_cache_read(&library, text);
}

static unsigned library_keys(u32 down) {
    unsigned k = 0;
    if (down & KEY_A) k |= PD_LIB_A;
    if (down & KEY_B) k |= PD_LIB_B;
    if (down & KEY_X) k |= PD_LIB_X;
    if (down & KEY_Y) k |= PD_LIB_Y;
    if (down & (KEY_L | KEY_ZL)) k |= PD_LIB_L;
    if (down & (KEY_R | KEY_ZR)) k |= PD_LIB_R;
    if (down & KEY_UP) k |= PD_LIB_UP;
    if (down & KEY_DOWN) k |= PD_LIB_DOWN;
    if (down & KEY_LEFT) k |= PD_LIB_LEFT;
    if (down & KEY_RIGHT) k |= PD_LIB_RIGHT;
    if (down & KEY_START) k |= PD_LIB_START;
    if (down & KEY_SELECT) k |= PD_LIB_SELECT;
    return k;
}

// Picks a game; false when the app should close.
static bool pick_rom(char* out, size_t outLen) {
    static char* names[MAX_ROMS];
    static int page, view = -1;
    static char lastFile[256];
#ifdef PD_DEV
    static char devLine[64];
#endif
    static struct pd_library_host host = {
        .read_rom = lib_read_rom, .read_save = lib_read_save, .write_saves = lib_write_saves, .load_rom = lib_load_rom,
        .read_card = lib_read_card, .settings_changed = lib_settings_changed, .cache_changed = lib_cache_changed,
        .version = PD_VERSION " (3DS)",
    };
    int n = list_roms(names);
    pd_library_init(&library, &settings, &host);
    for (int i = 0; i < n && i < PD_LIBRARY_MAX; i++) library.games[library.count++].file = names[i];
    pd_library_refresh(&library);
    read_cache();
    // Back where it was left: the page and the view.
    if (view >= 0) library.view = view;
    library.page = page == PD_PAGE_SETTINGS ? page : PD_PAGE_GAMES;
    pd_library_refresh(&library);
    pd_library_select(&library, lastFile);
    pd_look_apply(NULL, &settings);
    struct pd_canvas top, bottom;
    pd_canvas_init(&top, TOP_W, TOP_H, topPx);
    pd_canvas_init(&bottom, PD_UI_WIDTH, PD_UI_HEIGHT, bottomPx);

    bool touching = false, picked = false, running = true;
    u32 lastInjected = 0;
    while ((running = aptMainLoop())) {
        hidScanInput();
        u32 injected = devlink_keys();
        u32 down = hidKeysDown() | (injected & ~lastInjected), held = hidKeysHeld() | injected;
        lastInjected = injected;
        if (devlink_restart_wanted()) {
            running = false;
            break;
        }
        char wanted[256];
        if (devlink_take_launch(wanted, sizeof(wanted))) {
            for (int i = 0; i < library.count; i++)
                if (!strcmp(library.games[i].file, wanted)) library.picked = i;
        }
        pd_library_keys(&library, library_keys(down));
        if (library.quit) {
            running = false;
            break;
        }
        int touchX, touchY;
        if (read_touch(held, &touchX, &touchY)) {
            pd_library_touch(&library, touchX, touchY, true);
            touching = true;
        } else if (touching) {
            touching = false;
            pd_library_touch(&library, 0, 0, false);
        }
        if (library.picked >= 0) {
            // LOADING on both screens before the game is read: the draw thread shows a job a frame later, so a few.
            pd_library_draw_loading(&library, &bottom, &top);
            for (int i = 0; i < 3; i++) {
                gpu_set_bottom(bottomPx);
                gpu_set_top(topPx);
                gpu_draw(false, 0);
            }
            picked = true;
            break;
        }
        pd_library_tick(&library);
        {
            // The battery in every page's title, read once a second.
            static u64 batteryAt;
            u64 now = osGetTime();
            if (!batteryAt || now - batteryAt >= 1000) {
                batteryAt = now;
                u8 level = 0;
                int pct = R_SUCCEEDED(MCUHWC_GetBatteryLevel(&level)) ? level : -1;
                if (pct != library.batteryPct) library.dirty = true;
                library.batteryPct = pct;
            }
        }
#ifdef PD_DEV
        snprintf(devLine, sizeof devLine, "%s", devlink_address());
        if (!host.devLine || strcmp(host.devLine, devLine)) library.dirty = true;
        host.devLine = devLine;
#endif
        if (library.dirty) {
            pd_library_draw(&library, &bottom);
            gpu_set_bottom(bottomPx);
            pd_library_draw_top(&library, &top);
            gpu_set_top(topPx);
        }
        // The names the library worked out, kept for the next start (written between games, not every frame).
        if (cacheDirty && library.hashing < 0) write_cache();
        gpu_draw(false, 0);
        devlink_after_frame();
    }
    page = library.page;
    view = library.view;
    if (picked) {
        pd_library_played(&library, library.picked, (uint32_t) time(NULL));
        write_cache();
        snprintf(out, outLen, "%s", library.games[library.picked].file);
        snprintf(lastFile, sizeof lastFile, "%s", out);
    }
    for (int i = 0; i < n; i++) free(names[i]);
    return running && picked;
}

int main(void) {
    // First, while the heap is whole: the 32 MB every game is read into.
    bool arena = host_rom_arena_init();
    if (!gpu_init()) {
        gfxExit();
        return 1;
    }
    // The New 3DS's 804 MHz mode (a no-op on the original models).
    osSetSpeedupEnable(true);
    mLogSetDefaultLogger(&silentLogger);
    make_dirs();
    host_log_open(LOG_FILE);
    if (!arena) host_log("no memory for the ROM buffer");
    host_log("PokeDaisy: %s, heap %lu KiB free", host_is_new_3ds() ? "New 3DS" : "3DS", (unsigned long) host_heap_free() >> 10);
    pd_settings_load(&settings, SETTINGS_FILE);
    audio_init();
    mcuHwcInit();
    devlink_start();
    char file[256];
    while (pick_rom(file, sizeof(file))) {
        if (!run_game(file)) break;
    }

#ifdef PD_DEV
    bool restart = devlink_restart_wanted();
#endif
    devlink_stop();
#ifdef PD_DEV
    if (restart) devlink_chainload();
#endif
    mcuHwcExit();
    audio_exit();
    gpu_exit();
    return 0;
}
