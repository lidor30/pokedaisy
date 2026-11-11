// armGBA's side of pd_armgba.h: this file (with pd_armgba_call.c) sees armGBA's own headers and nothing of mGBA's; the
// `struct mCore` that faces the rest of PokeDaisy is built in pd_armgba_core.c from the ag_* functions here. armGBA runs
// through its own libretro layer, as armGBA-3DS drives it, with no Game Boy side (gb_none.c stands in for Gambatte).
// Based on ratherDashing's glue for the pokedaisy-3ds fork. armGBA itself is MPL-2.0, linked, not copied.
#include "pd_armgba_internal.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <time.h>

// armGBA (third_party/armgba/src, libretro)
#include "cpu/jit.h"
#include "gba.h"
#include "libretro.h"

// armGBA's libretro layer: player 1's console.
extern struct GBA* gba;
#if defined(__3DS__)
void jitWarmLoad(const char* path);
void jitWarmSave(const char* path);
#endif

#define STATE_MAGIC_TEXT "PDaG"
#define STATE_VERSION 1
#define SAVE_QUIET_FRAMES 15 // a save's writes have stopped this long: the file is written (mGBA's own wait)
#define GBA_RTC_TRAILER 16   // mGBA's GBASavedataRTCBuffer after the save data

static struct {
    bool loaded;
    struct ag_host host;
    char savePath[1024], warmPath[1100];
    uint32_t romCrc;
    uint32_t keys;
    uint32_t* video;
    size_t stride;
    bool skipVideo;
    bool savePending;
    int saveQuiet;
    bool hadTrailer;
    const struct retro_core_option_v2_definition* options;
    retro_audio_buffer_status_callback_t audioStatus;
    enum retro_pixel_format format;
} E;

static void emu_log(const char* format, ...) {
    if (!E.host.log) return;
    char line[512];
    va_list args;
    va_start(args, format);
    vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    line[strcspn(line, "\n")] = 0;
    E.host.log(E.host.ctx, line);
}

static uint32_t crc32_of(const void* buf, size_t size) {
    static uint32_t table[256];
    if (!table[1]) {
        for (uint32_t i = 0; i < 256; i++) {
            uint32_t c = i;
            for (int k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
    }
    uint32_t crc = 0xFFFFFFFFu;
    const uint8_t* p = buf;
    for (size_t i = 0; i < size; i++) crc = table[(crc ^ p[i]) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

// --- what armGBA's libretro layer asks its frontend ---

// The options that differ from armGBA's defaults. Threaded Video and Audio are armGBA-3DS's own settings on a New 3DS.
// Frame Skip is Auto only so that fast-forward's unseen frames can be left undrawn (the sound status below): a frame
// that is shown is always drawn. Player 1's save stays the frontend's (this file's), never armGBA's own folders. Its
// mixer stays on the game's thread: the New 3DS's fourth core is the companion's draw thread and EXPAND's (one render
// worker, on the third: ctr/host.c declines the second).
static const char* option_value(const char* key) {
    static const struct { const char* key; const char* value; } FIXED[] = {
        { "armgba_skip_bios", "enabled" },
        { "armgba_frame_skip", "auto" },
        { "armgba_restart_buttons", "off" },
        { "armgba_clock_source", "real" },
        { "armgba_player1_saves", "retroarch" },
        { "armgba_video_threads", "1" },
    };
    if (!strcmp(key, "armgba_threaded_video")) return E.host.threaded ? "enabled" : "disabled";
    if (!strcmp(key, "armgba_threaded_audio")) return "disabled";
    for (size_t i = 0; i < sizeof(FIXED) / sizeof(*FIXED); i++)
        if (!strcmp(key, FIXED[i].key)) return FIXED[i].value;
    for (const struct retro_core_option_v2_definition* d = E.options; d && d->key; d++)
        if (!strcmp(key, d->key)) return d->default_value;
    return NULL;
}

static void core_log(enum retro_log_level level, const char* format, ...) {
    if (!E.host.log || level < RETRO_LOG_INFO) return;
    char line[512];
    va_list args;
    va_start(args, format);
    vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    emu_log("armGBA: %s", line);
}

static bool environment(unsigned cmd, void* data) {
    switch (cmd & ~RETRO_ENVIRONMENT_EXPERIMENTAL) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback*) data)->log = core_log;
        return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char**) data = E.host.systemDir ? E.host.systemDir : E.host.workDir;
        return *(const char**) data != NULL;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char**) data = E.host.workDir;
        return E.host.workDir != NULL;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        E.format = *(const enum retro_pixel_format*) data;
        return E.format == RETRO_PIXEL_FORMAT_RGB565 || E.format == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        *(unsigned*) data = 2;
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        if (data) E.options = ((const struct retro_core_options_v2*) data)->definitions;
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable* v = data;
        v->value = v->key ? option_value(v->key) : NULL;
        return v->value != NULL;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool*) data = false;
        return true;
    case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK:
        E.audioStatus = data ? ((const struct retro_audio_buffer_status_callback*) data)->callback : NULL;
        return true;
    case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
        *(int*) data = 3;
        return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE: {
        const struct retro_message* m = data;
        if (m && m->msg) emu_log("armGBA says: %s", m->msg);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLE:
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
    case RETRO_ENVIRONMENT_SET_FASTFORWARDING_OVERRIDE:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
    case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
        return true;
    default:
        return false; // no rumble, netplay or link here
    }
}

// The picture into PokeDaisy's buffer as mGBA's 32-bit renderer left it: 0x00BBGGRR, each channel widened c << 3 | c >> 2.
static void video_refresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || !E.video || E.skipVideo) return;
    if (width > E.stride) width = (unsigned) E.stride;
    if (E.format == RETRO_PIXEL_FORMAT_RGB565) {
        for (unsigned y = 0; y < height; y++) {
            const uint16_t* in = (const uint16_t*) ((const uint8_t*) data + y * pitch);
            uint32_t* out = E.video + y * E.stride;
            for (unsigned x = 0; x < width; x++) {
                // armGBA's 5-6-5 is the GBA's 5-5-5 with green's low bit a copy of its high one.
                uint32_t c = in[x];
                uint32_t wide = (c >> 8 & 0xF8) | (c << 5 & 0xF800) | (c << 19 & 0xF80000);
                out[x] = wide | (wide >> 5 & 0x070707);
            }
        }
    } else {
        for (unsigned y = 0; y < height; y++) {
            const uint32_t* in = (const uint32_t*) ((const uint8_t*) data + y * pitch);
            uint32_t* out = E.video + y * E.stride;
            for (unsigned x = 0; x < width; x++) {
                uint32_t c = in[x];
                out[x] = (c >> 16 & 0xFF) | (c & 0xFF00) | (c << 16 & 0xFF0000);
            }
        }
    }
}

static size_t audio_batch(const int16_t* data, size_t frames) {
    if (E.host.audio && frames) E.host.audio(E.host.ctx, data, frames);
    return frames;
}

static void audio_sample(int16_t left, int16_t right) {
    int16_t pair[2] = { left, right };
    audio_batch(pair, 1);
}

static void input_poll(void) {
}

static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void) index;
    if (port || device != RETRO_DEVICE_JOYPAD) return 0;
    // PokeDaisy's keys are KEYINPUT's bits.
    static const int8_t BIT[16] = {
        [RETRO_DEVICE_ID_JOYPAD_A] = 1, [RETRO_DEVICE_ID_JOYPAD_B] = 2, [RETRO_DEVICE_ID_JOYPAD_SELECT] = 3, [RETRO_DEVICE_ID_JOYPAD_START] = 4,
        [RETRO_DEVICE_ID_JOYPAD_RIGHT] = 5, [RETRO_DEVICE_ID_JOYPAD_LEFT] = 6, [RETRO_DEVICE_ID_JOYPAD_UP] = 7, [RETRO_DEVICE_ID_JOYPAD_DOWN] = 8,
        [RETRO_DEVICE_ID_JOYPAD_R] = 9, [RETRO_DEVICE_ID_JOYPAD_L] = 10,
    };
    return id < 16 && BIT[id] ? (E.keys >> (BIT[id] - 1)) & 1 : 0;
}

// --- H-blank interrupts with nothing to do ---

// The games' HBlankIntr (pret's main.c: gMain.hblankCallback if set, then INTR_CHECK and gMain.intrCheck |= H-blank), the
// same code in FireRed (0x080007F0) and Emerald (0x08000814) but for the call's offset and gMain's address. A battle
// keeps the H-blank interrupt on with no callback: 228 interrupts a frame that only set two flags, 12 ms of armGBA's
// 18 on a New 3DS (it leaves compiled code for each). Where the game has this handler, the hook (armgba-pokedaisy.patch)
// sets the flags itself and the interrupt isn't raised; with a callback set, it is.
static const uint8_t HBLANK_INTR[40] = {
    0x10, 0xB5, 0x09, 0x4C, 0x20, 0x69, 0x00, 0x28, 0x01, 0xD0, 0x00, 0x00, 0x00, 0x00, 0x07, 0x4A, 0x10, 0x88, 0x02, 0x21,
    0x08, 0x43, 0x10, 0x80, 0xA0, 0x8B, 0xA2, 0x8B, 0x01, 0x43, 0xA1, 0x83, 0x10, 0xBC, 0x01, 0xBC, 0x00, 0x47, 0x00, 0x00,
};
#define HBLANK_INTR_BL 10 // the bl to _call_via_r0, 4 bytes, masked
#define INTR_CHECK 0x03007FF8u
#define MAIN_HBLANK_CALLBACK 0x10
#define MAIN_INTR_CHECK 0x1C
#define IRQ_FLAG_HBLANK 2

extern bool (*gbaHblankIrqHook)(struct GBA* gba); // armgba-pokedaisy.patch
static uint32_t hblankMain;   // gMain, where the game has the stock handler; 0: the interrupt runs as ever
static bool hblankSkips = true;
// CPU cycles the handler would have taken (BIOS entry, IntrMain, HBlankIntr, back). Its real time varies a little with
// what it interrupts; at 190 the sim's frames equal the real interrupts' in seven of eight test runs (FireRed and
// Emerald: battle, RUN, GEN 5, flight), the eighth reaching its battle menu a frame sooner (180-200 all close).
static int32_t hblankCost = 190;

static uint8_t* ram_at(struct GBA* g, uint32_t addr, uint32_t len) {
    if (addr >= 0x02000000 && addr - 0x02000000 + len <= SIZE_EWRAM) return g->ewram + (addr - 0x02000000);
    if (addr >= 0x03000000 && addr - 0x03000000 + len <= SIZE_IWRAM) return g->iwram + (addr - 0x03000000);
    return NULL;
}

static bool hblank_hook(struct GBA* g) {
    uint8_t* m = ram_at(g, hblankMain, 0x20);
    uint8_t* check = ram_at(g, INTR_CHECK, 2);
    if (!m || !check) return false;
    if (m[MAIN_HBLANK_CALLBACK] | m[MAIN_HBLANK_CALLBACK + 1] | m[MAIN_HBLANK_CALLBACK + 2] | m[MAIN_HBLANK_CALLBACK + 3]) return false;
    check[0] |= IRQ_FLAG_HBLANK;
    m[MAIN_INTR_CHECK] |= IRQ_FLAG_HBLANK;
    // The time it would have taken, so the game keeps the speed (and the lag frames) it has on a GBA. A halted CPU
    // (waiting for V-blank) would have gone back to its wait: nothing to charge.
    if (!g->cpu.halted) g->cpu.cycles += hblankCost;
    return true;
}

static void hblank_find(void) {
    hblankMain = 0;
    gbaHblankIrqHook = NULL;
    const uint8_t* rom = gba->rom;
    size_t size = gba->romSize < 0x400000 ? gba->romSize : 0x400000; // it sits near the start
    for (size_t at = 0; rom && at + 48 <= size; at += 4) {
        if (rom[at] != 0x10 || rom[at + 1] != 0xB5 || memcmp(rom + at, HBLANK_INTR, HBLANK_INTR_BL) ||
            memcmp(rom + at + HBLANK_INTR_BL + 4, HBLANK_INTR + HBLANK_INTR_BL + 4, sizeof(HBLANK_INTR) - HBLANK_INTR_BL - 4))
            continue;
        uint32_t main = (uint32_t) rom[at + 40] | (uint32_t) rom[at + 41] << 8 | (uint32_t) rom[at + 42] << 16 | (uint32_t) rom[at + 43] << 24;
        uint32_t check = (uint32_t) rom[at + 44] | (uint32_t) rom[at + 45] << 8 | (uint32_t) rom[at + 46] << 16 | (uint32_t) rom[at + 47] << 24;
        if (check != INTR_CHECK || !ram_at(gba, main, 0x20)) continue;
        hblankMain = main;
        break;
    }
    if (hblankMain && hblankSkips) gbaHblankIrqHook = hblank_hook;
    emu_log("H-blank handler: %s", hblankMain ? "the stock one, taken when it has no callback" : "not the stock one, left alone");
}

void pd_armgba_hblank_cost(int cycles) {
    hblankCost = cycles;
}

void pd_armgba_hblank_skips(bool on) {
    hblankSkips = on;
    gbaHblankIrqHook = hblankMain && on ? hblank_hook : NULL;
}

// --- the save file ---

static void put64(uint8_t* to, uint64_t v) {
    for (int i = 0; i < 8; i++) to[i] = (uint8_t) (v >> (i * 8));
}

static uint8_t* read_file(const char* path, size_t* size) {
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long length = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t* data = length > 0 ? malloc((size_t) length) : NULL;
    if (data && fread(data, 1, (size_t) length, f) != (size_t) length) {
        free(data);
        data = NULL;
    }
    fclose(f);
    if (data) *size = (size_t) length;
    return data;
}

// The whole file to a new one first, then over the old: a crash or a full card can't leave half a save.
static bool write_file(const char* path, const uint8_t* a, size_t aLen, const uint8_t* b, size_t bLen) {
    char temp[1100];
    snprintf(temp, sizeof(temp), "%s.tmp", path);
    FILE* f = fopen(temp, "wb");
    if (!f) return false;
    bool ok = fwrite(a, 1, aLen, f) == aLen && (!bLen || fwrite(b, 1, bLen, f) == bLen);
    ok = fclose(f) == 0 && ok;
    if (!ok) {
        remove(temp);
        return false;
    }
#if defined(__3DS__)
    remove(path); // the SD card's rename won't replace a file
#endif
    return rename(temp, path) == 0;
}

static void read_save(void) {
    size_t size = 0;
    uint8_t* file = read_file(E.savePath, &size);
    if (!file) {
        // A write cut off between the old file going and the new one's rename.
        char temp[1100];
        snprintf(temp, sizeof(temp), "%s.tmp", E.savePath);
        file = read_file(temp, &size);
        if (file) emu_log("save: %s is missing, read its .tmp", E.savePath);
    }
    E.hadTrailer = false;
    if (file) {
        // As large as the largest kind until the game shows which it has; a file is as long as its kind.
        size_t n = size < sizeof(gba->savedata.storage) ? size : sizeof(gba->savedata.storage);
        size_t known = savedataSize(gba);
        if (known && n > known) n = known;
        if (!known && n > GBA_RTC_TRAILER && (n & 0x1FF) == GBA_RTC_TRAILER) n -= GBA_RTC_TRAILER;
        memcpy(gba->savedata.storage, file, n);
        // mGBA's clock after the data: only its control byte says anything (24-hour, battery); the time is the device's.
        if ((gba->gpio.devices & HW_RTC) && size >= n + GBA_RTC_TRAILER) {
            gba->gpio.rtc.control = file[n + 7];
            E.hadTrailer = true;
        }
        gba->savedata.dirty = false;
        emu_log("save: read %u bytes from %s", (unsigned) n, E.savePath);
    }
    free(file);
    E.savePending = false;
    E.saveQuiet = 0;
}

static bool write_save(void) {
    size_t size = savedataSize(gba);
    if (!size) return true; // the game never touched its save memory: nothing to keep
    uint8_t trailer[GBA_RTC_TRAILER] = { 0 };
    bool clock = (gba->gpio.devices & HW_RTC) != 0;
    if (clock) {
        memcpy(trailer, gba->gpio.rtc.time, 7);
        trailer[7] = gba->gpio.rtc.control;
        put64(trailer + 8, (uint64_t) time(NULL));
    }
    bool ok = write_file(E.savePath, gba->savedata.storage, size, trailer, clock ? sizeof(trailer) : 0);
    emu_log(ok ? "save: wrote %s" : "save: COULDN'T WRITE %s", E.savePath);
    if (ok) {
        E.savePending = false;
        E.saveQuiet = 0;
    }
    return ok;
}

// After each frame: the game's writes to its save memory, and the file once they've stopped.
static void track_save(void) {
    if (gba->savedata.dirty) {
        gba->savedata.dirty = false;
        E.savePending = true;
        E.saveQuiet = 0;
    }
    if (E.savePending && ++E.saveQuiet >= SAVE_QUIET_FRAMES) write_save();
}

void pd_armgba_flush_save(void) {
    if (!E.loaded) return;
    if (gba->savedata.dirty) {
        gba->savedata.dirty = false;
        E.savePending = true;
    }
    if (E.savePending) write_save();
}

int pd_armgba_save_dirty(void) {
    return E.loaded && (E.savePending || gba->savedata.dirty);
}

// --- the machine ---

void ag_memory(struct ag_memory* m) {
    memset(m, 0, sizeof(*m));
    if (!E.loaded || !gba) return;
    m->ewram = gba->ewram;
    m->iwram = gba->iwram;
    m->rom = gba->rom;
    m->romSize = gba->romSize;
    m->io = gba->io;
    m->ioSize = sizeof(gba->io);
    m->palette = gba->palette;
    m->vram = gba->vram;
    m->oam = gba->oam;
}

void ag_set_keys(uint32_t keys) {
    E.keys = keys;
}

uint32_t ag_keys(void) {
    return E.keys;
}

void ag_run_frame(void) {
    // Frame Skip's Auto leaves a frame undrawn while the sound is said to be running short: said only for the frames
    // fast-forward doesn't show.
    if (E.audioStatus) E.audioStatus(true, E.skipVideo ? 0 : 100, E.skipVideo);
    retro_run();
    track_save();
}

void ag_step(void) {
    pd_armgba_step(gba);
}

void ag_reset(void) {
    retro_reset();
}

void ag_set_video(uint32_t* buffer, size_t stride) {
    E.video = buffer;
    E.stride = stride;
}

uint32_t ag_crc(void) {
    return E.romCrc;
}

uint32_t ag_read8(uint32_t address) {
    const uint8_t* p = codePointer(gba, address);
    if (p && (address >> 24) != REGION_BIOS) return *p;
    return memoryView32(gba, address & ~3u) >> ((address & 3) * 8) & 0xFF;
}

void ag_write8(uint32_t address, uint8_t value) {
    int cycles = 0;
    memoryStore8(gba, address, value, &cycles);
}

void ag_write16(uint32_t address, uint16_t value) {
    int cycles = 0;
    memoryStore16(gba, address, value, &cycles);
}

void ag_write32(uint32_t address, uint32_t value) {
    int cycles = 0;
    memoryStore32(gba, address, value, &cycles);
}

bool pd_armgba_write(uint32_t addr, const void* data, size_t len) {
    if (!E.loaded) return false;
    uint8_t* to;
    if (addr >= 0x02000000 && addr - 0x02000000 + len <= SIZE_EWRAM) to = gba->ewram + (addr - 0x02000000);
    else if (addr >= 0x03000000 && addr - 0x03000000 + len <= SIZE_IWRAM) to = gba->iwram + (addr - 0x03000000);
    else return false;
    memcpy(to, data, len);
    jitInvalidate(gba, addr, (uint32_t) len);
    return true;
}

void ag_unload(void) {
    if (!E.loaded) return;
    gbaHblankIrqHook = NULL;
    pd_armgba_flush_save();
#if defined(__3DS__)
    if (E.warmPath[0]) jitWarmSave(E.warmPath);
#endif
    retro_unload_game();
    retro_deinit();
    E.loaded = false;
    E.video = NULL;
}

void pd_armgba_skip_video(bool skip) {
    E.skipVideo = skip;
}

bool ag_skipping_video(void) {
    return E.skipVideo;
}

#if defined(__3DS__)
#include "frontend.h" // armGBA-3DS's: jitCounts

// The recompiler's running counts (jitmem.c's jitCounts), for the dev build's stat.
void pd_armgba_jit_counts(uint32_t out[4]) {
    struct JitCounts c;
    jitCounts(&c);
    out[0] = (uint32_t) c.stepped;
    out[1] = (uint32_t) c.compiled;
    out[2] = (uint32_t) c.invalidations;
    out[3] = (uint32_t) c.codeWrites;
}
#endif

bool pd_armgba_recompiling(void) {
    return E.loaded && gba->jit != NULL;
}

bool pd_armgba_threaded(void) {
    return E.loaded && gba->renderThread != NULL;
}

bool pd_armgba_wants(const uint8_t* header, size_t headerLen) {
    // A Game Boy cart has Nintendo's logo at 0x104 (RomIdentity.isGameBoy); a GBA cart its own at 0x04.
    static const uint8_t GB_LOGO[8] = { 0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B };
    return !(headerLen >= 0x10C && !memcmp(header + 0x104, GB_LOGO, sizeof(GB_LOGO)));
}

bool ag_load(const char* romPath, const char* savePath, const struct ag_host* host, char* error, size_t errorLen) {
    if (E.loaded) {
        snprintf(error, errorLen, "a game is already running");
        return false;
    }
    memset(&E, 0, sizeof(E));
    E.host = *host;
    snprintf(E.savePath, sizeof(E.savePath), "%s", savePath);

    FILE* f = fopen(romPath, "rb");
    if (!f) {
        snprintf(error, errorLen, "can't open the game");
        return false;
    }
    fseek(f, 0, SEEK_END);
    long length = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (length < 0xC0 || length > SIZE_ROM) {
        fclose(f);
        snprintf(error, errorLen, "not a GBA game (%ld bytes)", length);
        return false;
    }
    // On the 3DS into a buffer armGBA takes over as its cartridge: a 32 MB game is never in memory twice.
#if defined(__3DS__)
    size_t bufferSize = gbaRomBufferSize((size_t) length);
    bool hosts = E.host.romBuffer != NULL;
    uint8_t* rom = hosts ? E.host.romBuffer(bufferSize) : calloc(1, bufferSize);
#else
    uint8_t* rom = malloc((size_t) length);
#endif
    if (!rom || fread(rom, 1, (size_t) length, f) != (size_t) length) {
        fclose(f);
#if defined(__3DS__)
        if (!hosts)
#endif
        free(rom);
        snprintf(error, errorLen, "no memory for the game (%ld KiB), or it couldn't be read", length >> 10);
        return false;
    }
    fclose(f);
    E.romCrc = crc32_of(rom, (size_t) length);

    retro_set_environment(environment);
    retro_set_video_refresh(video_refresh);
    retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);
    retro_set_input_poll(input_poll);
    retro_set_input_state(input_state);
    retro_init();
    struct retro_game_info info = { .path = romPath, .data = rom, .size = (size_t) length, .meta = NULL };
#if defined(__3DS__)
    gbaOfferRom(rom);
    bool ok = retro_load_game(&info);
    bool taken = gbaOfferedRom() == NULL; // armGBA's now, and it frees it
    gbaOfferRom(NULL);
#else
    bool ok = retro_load_game(&info);
    bool taken = false; // copied
#endif
#if defined(__3DS__)
    if (hosts) taken = true; // the host's
#endif
    if (!taken) free(rom);
    if (!ok || !gba) {
        if (ok) retro_unload_game();
        retro_deinit();
        snprintf(error, errorLen, "armGBA couldn't start it");
        return false;
    }
    E.loaded = true;
    hblank_find();
    read_save();
    emu_log("armGBA: %s, %s, %u KiB game", gba->jit ? "recompiler" : "interpreter (no recompiler)",
            gba->renderThread ? "renderer and mixer on their own cores" : "one thread", (unsigned) (gba->romSize >> 10));
#if defined(__3DS__)
    if (E.host.workDir) {
        snprintf(E.warmPath, sizeof(E.warmPath), "%s/%08lX.warm", E.host.workDir, (unsigned long) E.romCrc);
        jitWarmLoad(E.warmPath);
    }
#endif
    return true;
}

// --- states ---

struct state_header {
    char magic[4];
    uint32_t version;
    uint32_t platform;
    uint32_t romCrc;
    uint32_t size;
};

bool pd_armgba_save_state(const char* path) {
    if (!E.loaded) return false;
    size_t size = retro_serialize_size();
    uint8_t* data = size ? malloc(size) : NULL;
    if (!data || !retro_serialize(data, size)) {
        free(data);
        return false;
    }
    struct state_header h = { .version = STATE_VERSION, .platform = 0, .romCrc = E.romCrc, .size = (uint32_t) size };
    memcpy(h.magic, STATE_MAGIC_TEXT, 4);
    bool ok = write_file(path, (const uint8_t*) &h, sizeof(h), data, size);
    free(data);
    return ok;
}

enum pd_armgba_state pd_armgba_load_state(const char* path) {
    if (!E.loaded) return PD_ARMGBA_STATE_BAD;
    size_t size = 0;
    uint8_t* file = read_file(path, &size);
    if (!file) return PD_ARMGBA_STATE_NO_FILE;
    struct state_header h;
    enum pd_armgba_state result = PD_ARMGBA_STATE_OK;
    if (size < sizeof(h) || memcmp(file, STATE_MAGIC_TEXT, 4)) {
        result = PD_ARMGBA_STATE_FOREIGN;
    } else {
        memcpy(&h, file, sizeof(h));
        if (h.version != STATE_VERSION || h.platform != 0) result = PD_ARMGBA_STATE_FOREIGN;
        else if (h.romCrc != E.romCrc) result = PD_ARMGBA_STATE_OTHER_GAME;
        else if (h.size != size - sizeof(h) || h.size != retro_serialize_size()) result = PD_ARMGBA_STATE_BAD;
    }
    if (result != PD_ARMGBA_STATE_OK) {
        free(file);
        return result;
    }
    // The save as it is now, kept through the load: armGBA's states hold the cartridge's save memory, and an old copy
    // of it must never reach the save file.
    size_t keptSize = sizeof(gba->savedata.storage);
    uint8_t* kept = malloc(keptSize);
    if (!kept) {
        free(file);
        return PD_ARMGBA_STATE_BAD;
    }
    memcpy(kept, gba->savedata.storage, keptSize);
    bool pending = E.savePending;
    bool ok = retro_unserialize(file + sizeof(h), h.size);
    free(file);
    memcpy(gba->savedata.storage, kept, keptSize);
    free(kept);
    gba->savedata.dirty = false;
    E.savePending = pending;
    return ok ? PD_ARMGBA_STATE_OK : PD_ARMGBA_STATE_BAD;
}

// --- pk_host.h's RTC rule: a dead battery's or 12-hour clock goes back to a running 24-hour one (pkHealRtc) ---

void pd_armgba_heal_rtc(void) {
    if (!E.loaded || !(gba->gpio.devices & HW_RTC)) return;
    const char* code = gba->rom && gba->romSize > 0xB0 ? (const char*) gba->rom + 0xAC : "";
    bool pokemon = !strncmp(code, "AXV", 3) || !strncmp(code, "AXP", 3) || !strncmp(code, "BPE", 3);
    uint8_t control = gba->gpio.rtc.control;
    if (!(control & 0x80) && ((control & 0x40) || !pokemon)) return;
    emu_log("RTC from the save: control 0x%02x (%s), reset to a running 24-hour clock", control, control & 0x80 ? "battery dead" : "12-hour");
    gba->gpio.rtc.control = 0x40;
}

// --- pk_cheats.h: mGBA's .cheats file, its enabled sets given to armGBA's cheat engine ---

void pd_armgba_cheats_clear(void) {
    if (E.loaded) retro_cheat_reset();
}

int pd_armgba_cheats_apply(const char* text, size_t len) {
    pd_armgba_cheats_clear();
    if (!E.loaded || !text || !len) return 0;
    // "# name" starts a set, "!disabled" before its codes switches it off, other "!" lines are mGBA's hints; the rest
    // are code lines, which armGBA tells apart itself (GameShark, Action Replay, CodeBreaker).
    char* codes = malloc(len + 2);
    if (!codes) return -1;
    size_t used = 0;
    int sets = 0;
    bool enabled = true, any = false;
    const char* at = text;
    const char* end = text + len;
    while (at <= end) {
        const char* eol = at < end ? memchr(at, '\n', (size_t) (end - at)) : NULL;
        if (!eol) eol = end;
        size_t n = (size_t) (eol - at);
        while (n && (at[n - 1] == '\r' || at[n - 1] == ' ')) n--;
        bool header = n && at[0] == '#', last = at >= end;
        if (header || last) {
            if (used && enabled) {
                codes[used] = 0;
                retro_cheat_set((unsigned) sets++, true, codes);
            }
            used = 0;
            enabled = true;
            any = false;
        } else if (n && at[0] == '!') {
            if (!any && n >= 9 && !strncasecmp(at, "!disabled", 9)) enabled = false;
        } else if (n) {
            if (used) codes[used++] = '+';
            memcpy(codes + used, at, n);
            used += n;
            any = true;
        }
        if (last) break;
        at = eol + 1;
        if (at > end) at = end;
    }
    free(codes);
    return sets;
}
