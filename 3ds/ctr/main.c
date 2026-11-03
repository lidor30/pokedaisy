// PokeDaisy for the Nintendo 3DS: the game (libmgba) on the top screen, the
// companion (../core) on the bottom one. A homebrew .3dsx; see README.md.
//
// The screens are drawn by the GPU (gpu.c). Audio follows mGBA's own 3DS
// frontend (src/platform/3ds/main.c): ndsp, stereo PCM16 at 32 kHz, the blip
// buffers resampled to the 3DS's refresh rate.
#include <3ds.h>

#include <dirent.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>

#include <mgba-util/vfs.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/serialize.h>
#include <mgba/internal/gba/audio.h>

#include "../core/pd_canvas.h"
#include "../core/pd_game.h"
#include "../core/pd_map.h"
#include "../core/pd_romart.h"
#include "../core/pd_menu.h"
#include "../core/pd_settings.h"
#include "../core/pd_snapshot.h"
#include "../core/pd_ui.h"
#include "gpu.h"

#define BASE_DIR "/pokedaisy"
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

// How often the companion reads the game (frames), and how long X+Y is held
// to leave the game (frames).
#define SNAPSHOT_EVERY 10
#define LEAVE_HOLD 60
// What a save state keeps: the RTC, never the save data - loading one must
// not write an old copy over the save file (CLAUDE.md: save files are sacred).
#define STATE_FLAGS SAVESTATE_RTC

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

static bool ends_with_gba(const char* name) {
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

struct game_mem {
    uint8_t* ewram;
    size_t ewramSize;
    uint8_t* iwram;
    size_t iwramSize;
    const uint8_t* rom;
    size_t romSize;
};

static bool bus_read(void* ctx, uint32_t addr, void* out, size_t len) {
    const struct game_mem* m = ctx;
    if (addr >= 0x02000000 && addr - 0x02000000 + len <= m->ewramSize) {
        memcpy(out, m->ewram + (addr - 0x02000000), len);
        return true;
    }
    if (addr >= 0x03000000 && addr - 0x03000000 + len <= m->iwramSize) {
        memcpy(out, m->iwram + (addr - 0x03000000), len);
        return true;
    }
    // ROM: pointers in RAM often lead into it (a map's layout).
    if (addr >= 0x08000000 && addr - 0x08000000 + len <= m->romSize) {
        memcpy(out, m->rom + (addr - 0x08000000), len);
        return true;
    }
    return false;
}

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
        if (hidKeysDown() & (KEY_B | KEY_A)) break;
        gpu_draw(false, 0);
    }
}

// The state file's time for LOAD STATE, or false if there's none. stat()
// leaves the time 0 on the SD card; archive_getmtime reads it.
static bool state_info(const char* path, char* when, size_t len) {
    struct stat st;
    if (stat(path, &st) || st.st_size == 0) return false;
    u64 mtime = 0;
    if (R_SUCCEEDED(archive_getmtime(path, &mtime)) && mtime) {
        time_t t = (time_t) mtime;
        strftime(when, len, "%m-%d %H:%M", gmtime(&t));
    } else {
        snprintf(when, len, "SAVED");
    }
    return true;
}

static bool save_state(struct mCore* core, const char* path) {
    struct VFile* vf = VFileOpen(path, O_CREAT | O_TRUNC | O_RDWR);
    if (!vf) return false;
    bool ok = mCoreSaveStateNamed(core, vf, STATE_FLAGS);
    vf->close(vf);
    return ok;
}

static bool load_state(struct mCore* core, const char* path) {
    struct VFile* vf = VFileOpen(path, O_RDONLY);
    if (!vf) return false;
    bool ok = mCoreLoadStateNamed(core, vf, STATE_FLAGS);
    vf->close(vf);
    return ok;
}

// Runs one game until the player leaves (true) or the app is closed (false).
static bool run_game(const char* file) {
    char romPath[512], savePath[512], statePath[512], base[256];
    snprintf(romPath, sizeof(romPath), ROM_DIR "/%s", file);
    snprintf(base, sizeof(base), "%s", file);
    base[strlen(base) - 4] = 0;
    snprintf(savePath, sizeof(savePath), ROM_DIR "/%s.sav", base);
    snprintf(statePath, sizeof(statePath), STATE_DIR "/%s.ss1", base);
    char saveSd[520], stateSd[520];
    snprintf(saveSd, sizeof(saveSd), "sdmc:%s", savePath);
    snprintf(stateSd, sizeof(stateSd), "sdmc:%s", statePath);

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
    core->setVideoBuffer(core, (color_t*) gpu_game_buffer(), GPU_GAME_STRIDE);
    core->setAudioBufferSize(core, AUDIO_SAMPLES);
    if (!core->loadROM(core, rom)) {
        core->deinit(core);
        show_message("The game didn't load (too big?):", file);
        return true;
    }
    backup_save(base, saveSd);
    struct VFile* save = VFileOpen(savePath, O_CREAT | O_RDWR);
    if (save) core->loadSave(core, save);
    stream.postAudioBuffer = post_audio;
    core->setAVStream(core, &stream);
    if (hasSound) {
        double ratio = GBAAudioCalculateRatio(1, 268111856.f / 4481136.f, 1);
        blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), 32768 * ratio);
        blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), 32768 * ratio);
    }
    core->reset(core);

    struct game_mem mem = { 0 };
    mem.ewram = core->getMemoryBlock(core, 0x02, &mem.ewramSize);
    mem.iwram = core->getMemoryBlock(core, 0x03, &mem.iwramSize);
    // The ROM as mGBA loaded it, for the tabs that read its tables and art.
    // Its art (the region map, the TRAINER CARD) is found now - one pass over
    // the whole ROM, under a second here - rather than on the first open,
    // mid-game.
    size_t romSize = 0;
    game.rom = core->getMemoryBlock(core, 0x08, &romSize);
    game.romSize = game.rom ? romSize : 0;
    mem.rom = game.rom;
    mem.romSize = game.romSize;
    pd_romart_scan(&game);
    pd_map_art(&game);

    struct pd_ui ui;
    pd_ui_init(&ui, &settings);
    // What the companion shows: read once now (RAM still blank: an empty
    // party), then every SNAPSHOT_EVERY frames.
    static struct pd_snapshot snap, shown; // too big for the 3DS's small stack
    pd_snapshot_read(&shown, &game, bus_read, &mem);
    shown.frame = 0;
    struct pd_host_info host = { .count = 2 };
    host.labels[0] = "FILE";
    snprintf(host.values[0], sizeof(host.values[0]), "%s", file);
    host.labels[1] = "SPEED";
    snprintf(host.values[1], sizeof(host.values[1]), "-");
    host.hasState = state_info(stateSd, host.stateWhen, sizeof(host.stateWhen));
    struct pd_canvas bottom;
    pd_canvas_init(&bottom, PD_UI_WIDTH, PD_UI_HEIGHT, bottomPx);

    bool redraw = true;
    bool touching = false;
    bool ffToggled = false;
    int leaveHeld = 0;
    unsigned frame = 0, fpsFrames = 0;
    u64 fpsStart = osGetTime();
    u64 lastTick = fpsStart;
    bool appRunning = true;

    while ((appRunning = aptMainLoop())) {
        hidScanInput();
        u32 held = hidKeysHeld(), down = hidKeysDown();

        // X+Y held a second: save and back to the game list.
        if ((held & (KEY_X | KEY_Y)) == (KEY_X | KEY_Y)) {
            if (++leaveHeld >= LEAVE_HOLD) break;
        } else {
            leaveHeld = 0;
            if (down & KEY_X) { pd_ui_next_tab(&ui, 1); redraw = true; }
            if (down & KEY_Y) { pd_ui_back(&ui); redraw = true; }
        }

        if (held & KEY_TOUCH) {
            touchPosition t;
            hidTouchRead(&t);
            redraw |= pd_ui_touch(&ui, t.px, t.py, true);
            touching = true;
        } else if (touching) {
            redraw |= pd_ui_touch(&ui, 0, 0, false);
            touching = false;
        }

        bool leave = false;
        switch (pd_ui_take_action(&ui)) {
        case PD_ACTION_TOGGLE_FF:
            ffToggled = !ffToggled;
            break;
        case PD_ACTION_SAVE_STATE:
            save_state(core, statePath);
            host.hasState = state_info(stateSd, host.stateWhen, sizeof(host.stateWhen));
            break;
        case PD_ACTION_LOAD_STATE:
            backup_save(base, saveSd);
            load_state(core, statePath);
            break;
        case PD_ACTION_LEAVE_GAME:
            leave = true;
            break;
        case PD_ACTION_SETTINGS_CHANGED:
            pd_settings_save(&settings, SETTINGS_FILE);
            break;
        case PD_ACTION_NONE:
            break;
        }
        if (leave) break;

        // Fast-forward: toggled from the companion, or held on ZR (New 3DS).
        bool ff = ffToggled || (held & KEY_ZR);
        if (ff != host.ffOn) {
            host.ffOn = ff;
            audioMuted = ff;
            redraw = true;
        }
        uint32_t keys = gba_keys(held);
        int frames = ff ? settings.ffSpeed : 1;
        for (int i = 0; i < frames; i++) {
            core->setKeys(core, keys);
            core->runFrame(core);
            frame++;
        }

        if (frame / SNAPSHOT_EVERY != (frame - frames) / SNAPSHOT_EVERY && mem.ewram && mem.iwram) {
            pd_snapshot_read(&snap, &game, bus_read, &mem);
            pd_ui_update(&ui, &snap);
            // Only a change the companion shows is worth a redraw (not the frame counter).
            snap.frame = 0;
            if (memcmp(&snap, &shown, sizeof(snap))) {
                shown = snap;
                redraw = true;
            }
        }

        fpsFrames += (unsigned) frames;
        u64 now = osGetTime();
        redraw |= pd_ui_tick(&ui, (unsigned) (now - lastTick));
        lastTick = now;
        if (now - fpsStart >= 1000) {
            // Against the GBA's own 268111856 / 4481136 = 59.73 frames a second.
            unsigned pct = (unsigned) ((u64) fpsFrames * 1000 * 100 * 4481136 / ((now - fpsStart) * 268111856ULL));
            snprintf(host.values[1], sizeof(host.values[1]), "%u%% (%u FPS)", pct, fpsFrames);
            fpsFrames = 0;
            fpsStart = now;
            if (ui.tab == PD_TAB_SETTINGS) redraw = true;
        }

        if (redraw) {
            pd_ui_draw(&ui, &bottom, &game, &shown, &host);
            gpu_set_bottom(bottomPx);
            redraw = false;
        }
        gpu_draw(true, settings.screenMode);
    }

    audioMuted = false;
    if (hasSound) ndspChnWaveBufClear(0);
    // deinit writes the save back (the 3DS VFile's unmap flushes it).
    core->deinit(core);
    return appRunning;
}

// --- the game list ---

// Picks a game; false when the app should close.
static bool pick_rom(char* out, size_t outLen) {
    char* names[MAX_ROMS];
    int n = list_roms(names);
    // Shown without ".gba".
    static char labels[MAX_ROMS][128];
    const char* items[MAX_ROMS];
    for (int i = 0; i < n; i++) {
        snprintf(labels[i], sizeof(labels[i]), "%s", names[i]);
        labels[i][strlen(labels[i]) - 4] = 0;
        items[i] = labels[i];
    }
    struct pd_menu menu;
    pd_menu_init(&menu, "CHOOSE A GAME", items, n);
    struct pd_canvas top, bottom;
    pd_canvas_init(&top, TOP_W, TOP_H, topPx);
    pd_canvas_init(&bottom, PD_UI_WIDTH, PD_UI_HEIGHT, bottomPx);
    pd_splash_draw(&top, "The companion for Gen 3 Pokémon.", "A: play   START: quit",
                   "In game: X tabs, Y back, ZR fast-forward.");
    gpu_set_top(topPx);

    bool dirty = true;
    bool touching = false, picked = false, running = true;
    while ((running = aptMainLoop())) {
        hidScanInput();
        u32 down = hidKeysDown(), held = hidKeysHeld();
        if (down & KEY_START) {
            running = false;
            break;
        }
        if (down & KEY_DOWN) { pd_menu_move(&menu, 1); dirty = true; }
        if (down & KEY_UP) { pd_menu_move(&menu, -1); dirty = true; }
        if ((down & KEY_A) && n > 0) {
            picked = true;
            break;
        }
        if (held & KEY_TOUCH) {
            touchPosition t;
            hidTouchRead(&t);
            pd_menu_touch(&menu, t.px, t.py, true);
            touching = true;
            dirty = true;
        } else if (touching) {
            touching = false;
            dirty = true;
            if (pd_menu_touch(&menu, 0, 0, false) >= 0) {
                picked = true;
                break;
            }
        }
        if (dirty) {
            pd_menu_draw(&menu, &bottom);
            gpu_set_bottom(bottomPx);
            dirty = false;
        }
        gpu_draw(false, 0);
    }
    if (picked) snprintf(out, outLen, "%s", names[menu.selected]);
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

    char file[256];
    while (pick_rom(file, sizeof(file))) {
        if (!run_game(file)) break;
    }

    audio_exit();
    gpu_exit();
    return 0;
}
