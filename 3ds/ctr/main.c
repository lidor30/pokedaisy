// PokeDaisy for the Nintendo 3DS: the game (libmgba) on the top screen, the
// companion (../core) on the bottom one. A homebrew .3dsx; see README.md.
//
// The screens are drawn by the GPU (gpu.c). Audio follows mGBA's own 3DS
// frontend (src/platform/3ds/main.c): ndsp, stereo PCM16 at 32 kHz, the blip
// buffers resampled to the 3DS's refresh rate.
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
#include "../core/pd_settings.h"
#include "../host/pd_session.h"
#include "../../app/src/main/cpp/pk_gb_audio.h"
#include "../../app/src/main/cpp/pk_host.h"
#include "devlink.h"
#include "../../app/src/main/cpp/pk_call.h"
#include "gpu.h"

// The dev build keeps to a folder of its own: a test never touches the player's games and saves.
#ifdef PD_DEV
#define BASE_DIR "/pokedaisy-dev"
#else
#define BASE_DIR "/pokedaisy"
#endif
#define ROM_DIR BASE_DIR "/roms"
#define BACKUP_DIR ROM_DIR "/pokedaisy-backups"
#define STATE_DIR BASE_DIR "/states"
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

// --- audio (as mGBA's 3DS frontend) ---

#define AUDIO_SAMPLES 384
#define DSP_BUFFERS 4

static bool hasSound;
static bool audioMuted; // while fast-forwarding
static ndspWaveBuf dspBuffer[DSP_BUFFERS];
static int16_t* audioBuf;
static int bufferId;
static struct mAVStream stream;

static void post_audio(struct mAVStream* s, blip_t* left, blip_t* right) {
    (void) s;
    if (!hasSound || audioMuted) {
        blip_clear(left);
        blip_clear(right);
        return;
    }
    int start = bufferId;
    while (dspBuffer[bufferId].status == NDSP_WBUF_QUEUED || dspBuffer[bufferId].status == NDSP_WBUF_PLAYING) {
        bufferId = (bufferId + 1) & (DSP_BUFFERS - 1);
        if (bufferId == start) {
            // Every buffer still queued: drop this chunk rather than wait.
            blip_clear(left);
            blip_clear(right);
            return;
        }
    }
    int16_t* data = dspBuffer[bufferId].data_pcm16;
    memset(&dspBuffer[bufferId], 0, sizeof(dspBuffer[bufferId]));
    dspBuffer[bufferId].data_pcm16 = data;
    dspBuffer[bufferId].nsamples = AUDIO_SAMPLES;
    blip_read_samples(left, data, AUDIO_SAMPLES, true);
    blip_read_samples(right, data + 1, AUDIO_SAMPLES, true);
    DSP_FlushDataCache(data, AUDIO_SAMPLES * 2 * sizeof(int16_t));
    ndspChnWaveBufAdd(0, &dspBuffer[bufferId]);
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
        if (!hasSound || audioMuted) continue;
        int start = bufferId;
        bool full = false;
        while (dspBuffer[bufferId].status == NDSP_WBUF_QUEUED || dspBuffer[bufferId].status == NDSP_WBUF_PLAYING) {
            bufferId = (bufferId + 1) & (DSP_BUFFERS - 1);
            if (bufferId == start) {
                full = true;
                break;
            }
        }
        if (full) continue;
        int16_t* data = dspBuffer[bufferId].data_pcm16;
        memset(&dspBuffer[bufferId], 0, sizeof(dspBuffer[bufferId]));
        dspBuffer[bufferId].data_pcm16 = data;
        dspBuffer[bufferId].nsamples = AUDIO_SAMPLES;
        memcpy(data, chunk, sizeof(chunk));
        DSP_FlushDataCache(data, sizeof(chunk));
        ndspChnWaveBufAdd(0, &dspBuffer[bufferId]);
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
    ndspChnSetInterp(0, NDSP_INTERP_NONE);
    ndspChnSetRate(0, 0x8000);
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
    uint8_t header[0xC0] = { 0 };
    rom->seek(rom, 0, SEEK_SET);
    rom->read(rom, header, sizeof(header));
    rom->seek(rom, 0, SEEK_SET);
    struct pd_game game;
    pd_game_detect(&game, header, (size_t) rom->size(rom));

    struct mCore* core = mCoreFindVF(rom);
    if (!core || !core->init(core)) {
        rom->close(rom);
        show_message("Not a GBA game:", file);
        return true;
    }
    mCoreInitConfig(core, NULL);
    // mGBA's renderer on the New 3DS's third core, as its own 3DS front end has it.
    bool isNew = false;
    APT_CheckNew3DS(&isNew);
    if (isNew) mCoreConfigSetIntValue(&core->config, "threadedVideo", 1);
    core->setVideoBuffer(core, (color_t*) gpu_game_buffer(), GPU_GAME_STRIDE);
    pk_gb_settings(core);
    core->setAudioBufferSize(core, AUDIO_SAMPLES);
    // Into mGBA's fixed 32 MB buffer: core->loadROM maps the file with linearAlloc, and the 15 MB of linear memory the
    // GPU leaves can't hold a 16 MB game on a real console. This closes rom.
    if (!mCorePreloadVF(core, rom)) {
        core->deinit(core);
        show_message("The game didn't load (too big?):", file);
        return true;
    }
    // As mGBA's own 3DS front end has it (gui-runner.c): skips the busy loops games wait in. Without it FireRed takes 19 ms
    // a frame on a New 3DS, 30 FPS; with it 9.
    if (core->platform(core) == mPLATFORM_GBA) ((struct GBA*) core->board)->idleOptimization = IDLE_LOOP_DETECT;
    backup_save(base, saveSd);
    struct VFile* save = VFileOpen(savePath, O_CREAT | O_RDWR);
    if (save) core->loadSave(core, save);
    bool gb = core->platform(core) == mPLATFORM_GB;
    if (gb) {
        gbAudioOn = pk_gb_audio_attach(&gbAudio, core, 32768);
    } else {
        stream.postAudioBuffer = post_audio;
        core->setAVStream(core, &stream);
        if (hasSound) {
            double ratio = GBAAudioCalculateRatio(1, 268111856.f / 4481136.f, 1);
            blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), 32768 * ratio);
            blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), 32768 * ratio);
        }
    }
    core->reset(core);
    unsigned videoW = 240, videoH = 160;
    core->desiredVideoDimensions(core, &videoW, &videoH);
    gpu_set_game_size((int) videoW, (int) videoH);
    pk_heal_rtc(core);

    static struct pd_session session;
    memset(&session, 0, sizeof(session));
    uint32_t crc = 0;
    core->checksum(core, &crc, mCHECKSUM_CRC32);
    pd_game_identify(&game, crc);
    session.game = game;
    struct game_files gameFiles;
    snprintf(gameFiles.base, sizeof(gameFiles.base), "%s", base);
    snprintf(gameFiles.saveSd, sizeof(gameFiles.saveSd), "%s", saveSd);
    struct pd_session_host files = { state_path, backup_for_load, settings_changed, cheat_path, &gameFiles };
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
    u64 fpsStart = osGetTime();
    u64 lastTick = fpsStart;
#ifdef PD_DEV
    u64 devSecond = fpsStart;
#endif
    bool appRunning = true;
    u32 lastInjected = 0;
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
        int wildSpecies, wildLevel;
        if (!session.game.gb && pk_call_safe(core) && session.game.cfg && session.game.cfg->pcSetupScript && devlink_take_wild(&wildSpecies, &wildLevel)) {
            // setwildbattle species level item; dowildbattle; end (pd_sim's `wild`).
            const struct pd_config* cfg = session.game.cfg;
            uint8_t script[] = { 0xB6, (uint8_t) wildSpecies, (uint8_t) (wildSpecies >> 8), (uint8_t) wildLevel, 0, 0, 0xB7, 0x02 };
            pd_session_write(&session, cfg->pcScript, script, sizeof(script));
            uint32_t args[4] = { cfg->pcScript, 0, 0, 0 };
            pk_call(core, cfg->pcSetupScript, args, NULL);
        }
#endif

        // X is the device's BACK (the app's companion back); Y the QUICK MENU; ZL the next tab.
        if (down & KEY_X) { pd_ui_back(uiPtr); session.redraw = true; }
        if (down & KEY_ZL) { pd_ui_next_tab(uiPtr, 1); session.redraw = true; }

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

        struct pd_input in = { .held = gba_keys(held), .down = gba_keys(down), .quickMenu = down & KEY_Y,
                               .ffHeld = held & KEY_ZR, .stickAngle = stick_angle() };
        DEV_MARK(coreStart);
        u64 tickNow = osGetTime();
        unsigned before = session.frame;
        pd_session_tick(&session, &in, (unsigned) (tickNow - lastTick));
        gb_audio_pump();
        lastTick = tickNow;
        unsigned frames = session.frame - before;
        audioMuted = session.ff;
        DEV_MARK(uiStart);

        fpsFrames += (unsigned) frames;
        u64 now = osGetTime();
        if (now - fpsStart >= 1000) {
            // Against the GBA's own 268111856 / 4481136 = 59.73 frames a second.
            unsigned pct = (unsigned) ((u64) fpsFrames * 1000 * 100 * 4481136 / ((now - fpsStart) * 268111856ULL));
            snprintf(hostPtr->values[1], sizeof(hostPtr->values[1]), "%u%% (%u FPS)", pct, fpsFrames);
            fpsFrames = 0;
            fpsStart = now;
            u8 battery = 0;
            if (R_SUCCEEDED(MCUHWC_GetBatteryLevel(&battery))) hostPtr->batteryPct = battery;
            if (uiPtr->tab == PD_TAB_SETTINGS) session.redraw = true;
        }

        if (session.redraw) {
            pd_session_draw(&session, &bottom);
            gpu_set_bottom(bottomPx);
        }
        DEV_MARK(drawStart);
        gpu_hold_game(pd_session_holds_frame(&session));
        gpu_set_wide(session.wideReady ? pd_session_wide(&session) : NULL, session.wideW, session.wideH);
        if (session.overlayDirty) gpu_set_overlay(pd_session_overlay(&session));
        gpu_show_overlay(session.overlayShown, session.overlayDim);
        gpu_draw(true, settings.screenMode);
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
            loopStart = end;
            dev.frames += (unsigned) frames;
            if (now - devSecond >= 1000) {
                devSecond = now;
                devlink_report(&dev);
                memset(&dev, 0, sizeof(dev));
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
    core->deinit(core);
    return appRunning;
}

// --- the game list ---

// Picks a game; false when the app should close.
static bool pick_rom(char* out, size_t outLen) {
    static struct pd_library library;
    static char titles[PD_LIBRARY_MAX][96];
    static char* names[MAX_ROMS];
    static int viewKept;
    int n = list_roms(names);
    pd_library_init(&library);
    library.view = viewKept;
    for (int i = 0; i < n && i < PD_LIBRARY_MAX; i++) {
        struct pd_library_game* g = &library.games[library.count++];
        g->file = names[i];
        g->title = titles[i];
        pd_library_title(names[i], titles[i], sizeof(titles[i]), &g->hack, &g->supported);
        size_t len = strlen(names[i]);
        g->console = len > 4 && !strcasecmp(names[i] + len - 4, ".gbc") ? PD_CONSOLE_GBC
            : len > 3 && !strcasecmp(names[i] + len - 3, ".gb") ? PD_CONSOLE_GB : PD_CONSOLE_GBA;
    }
    pd_library_refresh(&library);
    struct pd_canvas top, bottom;
    pd_canvas_init(&top, TOP_W, TOP_H, topPx);
    pd_canvas_init(&bottom, PD_UI_WIDTH, PD_UI_HEIGHT, bottomPx);

    bool dirty = true;
    bool touching = false, picked = false, running = true;
    u32 lastInjected = 0;
#ifdef PD_DEV
    char shownAddress[32] = "";
#endif
    while ((running = aptMainLoop())) {
        hidScanInput();
        u32 injected = devlink_keys();
        u32 down = hidKeysDown() | (injected & ~lastInjected), held = hidKeysHeld() | injected;
        lastInjected = injected;
        if (down & KEY_START || devlink_restart_wanted()) {
            running = false;
            break;
        }
        char wanted[256];
        if (devlink_take_launch(wanted, sizeof(wanted))) {
            for (int i = 0; i < library.count; i++)
                if (!strcmp(library.games[i].file, wanted)) library.picked = i;
        }
        int dx = !!(down & KEY_RIGHT) - !!(down & KEY_LEFT), dy = !!(down & KEY_DOWN) - !!(down & KEY_UP);
        if (dx || dy) {
            pd_library_move(&library, dx, dy);
            dirty = true;
        }
        if (down & KEY_L || down & KEY_R) {
            library.view = (library.view + (down & KEY_R ? 1 : PD_VIEW_COUNT - 1)) % PD_VIEW_COUNT;
            library.selected = library.scroll = 0;
            pd_library_refresh(&library);
            dirty = true;
        }
        if ((down & KEY_A) && library.shownCount) library.picked = library.shown[library.selected];
        int touchX, touchY;
        if (read_touch(held, &touchX, &touchY)) {
            pd_library_touch(&library, touchX, touchY, true);
            touching = true;
            dirty = true;
        } else if (touching) {
            touching = false;
            pd_library_touch(&library, 0, 0, false);
            dirty = true;
        }
        if (library.picked >= 0) {
            picked = true;
            break;
        }
        if (dirty) {
            pd_library_draw(&library, &bottom);
            gpu_set_bottom(bottomPx);
#ifdef PD_DEV
            snprintf(shownAddress, sizeof(shownAddress), "%s", devlink_address());
            char line[64];
            snprintf(line, sizeof(line), "Dev link: %s", shownAddress);
            pd_library_draw_top(&library, &top, line);
#else
            pd_library_draw_top(&library, &top, NULL);
#endif
            gpu_set_top(topPx);
            dirty = false;
        }
        gpu_draw(false, 0);
        devlink_after_frame();
#ifdef PD_DEV
        if (strcmp(shownAddress, devlink_address())) dirty = true;
#endif
    }
    viewKept = library.view;
    if (picked) snprintf(out, outLen, "%s", library.games[library.picked].file);
    for (int i = 0; i < n; i++) free(names[i]);
    return running && picked;
}

int main(void) {
    if (!gpu_init()) {
        gfxExit();
        return 1;
    }
    // The New 3DS's 804 MHz mode (a no-op on the original models).
    osSetSpeedupEnable(true);
    mLogSetDefaultLogger(&silentLogger);
    make_dirs();
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
