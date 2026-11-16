// gpSP behind pd_gpsp.h: a libretro frontend for its core (as gpSP 3D's own 3DS frontend is, gpsp3ds/source/main.c,
// whose options, saves and states this follows), the game's memory for the mGBA view (pd_gpsp_core.c), the save file in
// mGBA's layout and states of its own. Built into one object with gpSP (emu/gpsp.cmake): this is the only file that
// sees gpSP's headers, and pd_gpsp.h's names are the object's only globals.
#include "pd_gpsp.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "common.h"
#include "libretro.h"

#ifdef HAVE_DYNAREC
#include "cpu.h"
#endif
#include "video_thread.h"

// gpSP's own (gba_memory.c, the patch's arena hook; libretro.c).
extern u8* (*gpsp_host_rom_arena)(size_t size);
extern u8* gamepak_buffers[32];
u32 gpsp_backup_size(void);
extern int gpsp_frontend_skip;
#ifdef HAVE_DYNAREC
extern int dynarec_enable;
#endif

#if defined(_3DS) && defined(HAVE_DYNAREC)
// gpSP maps its translation caches with svcControlProcessMemory only when told the process may (RetroArch's
// ctr_svchax.c sets this in gpSP 3D). armGBA's recompiler, in the same app, maps its code memory the same way.
u32 __ctr_svchax = 1;
#endif

#ifndef HAVE_DYNAREC
// gpSP's DMA marks writes over compiled RAM code for its recompiler even when built without one (the simulator).
void smc_note_write(u32 address) {
    (void) address;
}
#endif

#define SAVE_QUIET_FRAMES 15 // a save's writes have stopped this long: the file is written (mGBA's own wait)
#define GBA_RTC_TRAILER 16   // mGBA's clock after the save data: armGBA and mGBA write it, gpSP keeps it as it was
#define STATE_MAGIC 0x50534750u // "PGSP"
#define STATE_VERSION 1

static struct {
    bool loaded;
    struct pd_gpsp_host host;
    char savePath[1024];
    uint32_t keys;
    uint32_t* video;
    size_t stride;
    bool skipVideo;
    uint32_t crc;
    // The save: what the file held past the save data (mGBA's clock), how big the save was, the last written copy's hash.
    uint8_t trailer[GBA_RTC_TRAILER];
    bool hadTrailer;
    size_t loadedSize;
    uint32_t savedHash, pendingHash;
    bool savePending;
    int saveQuiet;
    int frames;
    bool threaded;
    volatile bool hookDrew; // the render thread's frame hook already wrote this frame into [video]
} G;

static void emu_log(const char* format, ...) {
    if (!G.host.log) return;
    char line[512];
    va_list args;
    va_start(args, format);
    vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    line[strcspn(line, "\n")] = 0;
    G.host.log(G.host.ctx, line);
}

// --- what gpSP's libretro layer asks its frontend ---

static void core_log(enum retro_log_level level, const char* format, ...) {
    if (!G.host.log || level < RETRO_LOG_WARN) return;
    char line[480];
    va_list args;
    va_start(args, format);
    vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    emu_log("gpSP: %s", line);
}

// gpSP 3D's own choices (gpsp3ds/source/main.c's var_value), but the clock: the system's, like armGBA's.
static const char* option_value(const char* key) {
    static const struct { const char* key; const char* value; } FIXED[] = {
        { "gpsp_drc", "enabled" },
        { "gpsp_bios", "auto" },
        { "gpsp_boot_mode", "game" },
        { "gpsp_sound_rate", "32768" },
        { "gpsp_frameskip", "disabled" },
        { "gpsp_profile", "disabled" },
        { "gpsp_color_correction", "disabled" },
        { "gpsp_frame_mixing", "disabled" },
        { "gpsp_sprlim", "disabled" },
        { "gpsp_rtc", "auto" },
        { "gpsp_rtc_time_source", "system" },
        { "gpsp_rumble", "disabled" },
        { "gpsp_serial", "disabled" },
        { "gpsp_turbo_period", "4" },
    };
    for (size_t i = 0; i < sizeof(FIXED) / sizeof(*FIXED); i++)
        if (!strcmp(key, FIXED[i].key)) return FIXED[i].value;
    return NULL;
}

static bool environment(unsigned cmd, void* data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback*) data)->log = core_log;
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable* v = data;
        v->value = option_value(v->key);
        return v->value != NULL;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool*) data = false;
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        return *(enum retro_pixel_format*) data == RETRO_PIXEL_FORMAT_RGB565;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char**) data = G.host.systemDir;
        return G.host.systemDir != NULL;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE: {
        const struct retro_message* m = data;
        if (m && m->msg) emu_log("gpSP says: %s", m->msg);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
        return true;
    default:
        return false; // no rumble, netplay, fast-forward override, audio status (our frame skip is ours)
    }
}

// The picture into PokeDaisy's buffer as mGBA's 32-bit renderer left it: 0x00BBGGRR, each channel widened c << 3 | c >> 2.
static void convert(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (width > G.stride) width = (unsigned) G.stride;
    for (unsigned y = 0; y < height; y++) {
        const uint16_t* in = (const uint16_t*) ((const uint8_t*) data + y * pitch);
        uint32_t* out = G.video + y * G.stride;
        for (unsigned x = 0; x < width; x++) {
            uint32_t p = in[x];
            uint32_t r = p >> 11 & 0x1F, g = p >> 5 & 0x3F, b = p & 0x1F;
            r = r << 3 | r >> 2;
            g = g << 2 | g >> 4;
            b = b << 3 | b >> 2;
            out[x] = b << 16 | g << 8 | r;
        }
    }
}

static void video_refresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    bool drew = G.hookDrew;
    G.hookDrew = false;
    if (!data || !G.video || G.skipVideo || drew) return;
    convert(data, width, height, pitch);
}

// With the render thread: each finished frame converted there too (gpSP 3D's screen copy on core 2), before
// video_thread_finish_frame lets the game's thread go on.
static void frame_hook(const unsigned short* left, const unsigned short* right, unsigned pitch, void* arg) {
    (void) right;
    (void) arg;
    if (!G.video || G.skipVideo) return;
    convert(left, 240, 160, pitch * sizeof(unsigned short));
    G.hookDrew = true;
}

static void audio_sample(int16_t left, int16_t right) {
    int16_t pair[2] = { left, right };
    if (G.host.audio) G.host.audio(G.host.ctx, pair, 1);
}

static size_t audio_batch(const int16_t* data, size_t frames) {
    if (G.host.audio) G.host.audio(G.host.ctx, data, frames);
    return frames;
}

static void input_poll(void) {}

// mGBA's key bits (A, B, SELECT, START, RIGHT, LEFT, UP, DOWN, R, L) as the RetroPad's.
static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    static const unsigned PAD[10] = {
        RETRO_DEVICE_ID_JOYPAD_A, RETRO_DEVICE_ID_JOYPAD_B, RETRO_DEVICE_ID_JOYPAD_SELECT, RETRO_DEVICE_ID_JOYPAD_START,
        RETRO_DEVICE_ID_JOYPAD_RIGHT, RETRO_DEVICE_ID_JOYPAD_LEFT, RETRO_DEVICE_ID_JOYPAD_UP, RETRO_DEVICE_ID_JOYPAD_DOWN,
        RETRO_DEVICE_ID_JOYPAD_R, RETRO_DEVICE_ID_JOYPAD_L,
    };
    (void) index;
    if (port || device != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
        int16_t mask = 0;
        for (int i = 0; i < 10; i++)
            if (G.keys >> i & 1) mask |= (int16_t) (1 << PAD[i]);
        return mask;
    }
    for (int i = 0; i < 10; i++)
        if (PAD[i] == id) return (int16_t) (G.keys >> i & 1);
    return 0;
}

// --- files ---

static uint32_t crc_table[256];

static uint32_t crc32_of(const uint8_t* p, size_t n) {
    if (!crc_table[1])
        for (uint32_t i = 0; i < 256; i++) {
            uint32_t c = i;
            for (int k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            crc_table[i] = c;
        }
    uint32_t c = 0xFFFFFFFFu;
    while (n--) c = crc_table[(c ^ *p++) & 0xFF] ^ (c >> 8);
    return ~c;
}

static uint32_t hash_of(const uint8_t* p, size_t n) {
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < n; i++) h = (h ^ p[i]) * 16777619u;
    return h;
}

static uint8_t* read_file(const char* path, size_t* size) {
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long length = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t* data = length >= 0 ? malloc(length ? (size_t) length : 1) : NULL;
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
#if defined(_3DS)
    remove(path); // the SD card's rename won't replace a file
#endif
    return rename(temp, path) == 0;
}

static void read_save(void) {
    G.hadTrailer = false;
    G.loadedSize = 0;
    size_t size = 0;
    uint8_t* file = read_file(G.savePath, &size);
    if (!file) {
        // A write cut off between the old file going and the new one's rename.
        char temp[1100];
        snprintf(temp, sizeof(temp), "%s.tmp", G.savePath);
        file = read_file(temp, &size);
        if (file) emu_log("save: %s is missing, read its .tmp", G.savePath);
    }
    if (file) {
        // mGBA's layout: the save memory (512 B ... 128 KB, a multiple of 512), then maybe its 16-byte clock.
        size_t n = size;
        if (n > GBA_RTC_TRAILER && (n & 0x1FF) == GBA_RTC_TRAILER) {
            n -= GBA_RTC_TRAILER;
            memcpy(G.trailer, file + n, GBA_RTC_TRAILER);
            G.hadTrailer = true;
        }
        if (n > sizeof(gamepak_backup)) n = sizeof(gamepak_backup);
        memcpy(gamepak_backup, file, n);
        G.loadedSize = n;
        emu_log("save: read %u bytes from %s", (unsigned) n, G.savePath);
    }
    free(file);
    G.savedHash = hash_of(gamepak_backup, sizeof(gamepak_backup));
    G.savePending = false;
    G.saveQuiet = 0;
}

static bool write_save(void) {
    // The cartridge's real size once the game has used its save memory, never less than the file had (an 8 KB EEPROM
    // reads as 512 bytes until the game uses it: gpSP 3D's sram_file_size).
    size_t size = gpsp_backup_size();
    if (size < G.loadedSize) size = G.loadedSize;
    if (size > sizeof(gamepak_backup)) size = sizeof(gamepak_backup);
    if (!size) return true; // the game never touched its save memory: nothing to keep
    bool ok = write_file(G.savePath, gamepak_backup, size, G.trailer, G.hadTrailer ? GBA_RTC_TRAILER : 0);
    emu_log(ok ? "save: wrote %s" : "save: COULDN'T WRITE %s", G.savePath);
    if (ok) {
        G.savedHash = hash_of(gamepak_backup, sizeof(gamepak_backup));
        G.loadedSize = size;
        G.savePending = false;
        G.saveQuiet = 0;
    }
    return ok;
}

// Every fourth frame (a 128 KB hash): the game's writes to its save memory, and the file once they've stopped for
// SAVE_QUIET_FRAMES.
static void track_save(void) {
    if (++G.frames % 4) return;
    uint32_t h = hash_of(gamepak_backup, sizeof(gamepak_backup));
    if (h == G.savedHash) {
        G.savePending = false;
        return;
    }
    if (!G.savePending || h != G.pendingHash) {
        G.savePending = true;
        G.pendingHash = h;
        G.saveQuiet = 0;
        return;
    }
    G.saveQuiet += 4;
    if (G.saveQuiet >= SAVE_QUIET_FRAMES) write_save();
}

int pd_gpsp_save_dirty(void) {
    return G.loaded && (G.savePending || hash_of(gamepak_backup, sizeof(gamepak_backup)) != G.savedHash);
}

void pd_gpsp_flush_save(void) {
    if (G.loaded && hash_of(gamepak_backup, sizeof(gamepak_backup)) != G.savedHash) write_save();
}

// --- the machine ---

static u8* own_arena(size_t size) {
    static u8* arena;
    if (!arena) arena = malloc(size);
    if (arena) memset(arena, 0, size);
    return arena;
}

bool pd_gpsp_open(const char* romPath, const char* savePath, const struct pd_gpsp_host* host, char* error, size_t errorLen) {
    if (G.loaded) {
        snprintf(error, errorLen, "a game is already running");
        return false;
    }
    memset(&G, 0, sizeof(G));
    G.host = *host;
    snprintf(G.savePath, sizeof(G.savePath), "%s", savePath);
    // The cartridge in one block, which the view and the patches count on: the host's, else one of our own (kept).
    gpsp_host_rom_arena = host->romBuffer ? (u8 * (*) (size_t)) host->romBuffer : own_arena;

    retro_set_environment(environment);
    retro_set_video_refresh(video_refresh);
    retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);
    retro_set_input_poll(input_poll);
    retro_set_input_state(input_state);
    retro_init();
    struct retro_game_info info = { romPath, NULL, 0, NULL };
    if (!retro_load_game(&info)) {
        retro_deinit();
        snprintf(error, errorLen, "gpSP couldn't load it");
        return false;
    }
    G.loaded = true;
    if (host->threaded && video_thread_start(host->renderCore) == 0) {
        video_thread_set_frame_hook(frame_hook, NULL);
        G.threaded = true;
    }
    G.crc = host->knownCrc ? host->knownCrc : crc32_of(gamepak_buffers[0], gamepak_size);
    read_save();
    emu_log("gpSP: %s, %s, %u KiB game", pd_gpsp_recompiling() ? "recompiler" : "interpreter",
            G.threaded ? "drawn on its own thread" : host->threaded ? "its render thread DIDN'T START, drawn with the game" : "drawn with the game",
            (unsigned) (gamepak_size >> 10));
    return true;
}

void pd_gpsp_close(void) {
    if (!G.loaded) return;
    pd_gpsp_flush_save();
    if (G.threaded) {
        video_thread_set_frame_hook(NULL, NULL);
        video_thread_stop();
        G.threaded = false;
    }
    retro_unload_game();
    retro_deinit();
    G.loaded = false;
    G.video = NULL;
}

bool pd_gpsp_loaded(void) {
    return G.loaded;
}

bool pd_gpsp_threaded(void) {
    return G.loaded && G.threaded;
}

void pd_gpsp_render_us(unsigned* render, unsigned* wait) {
    *render = G.threaded ? video_thread_last_render_us() : 0;
    *wait = G.threaded ? video_thread_last_wait_us() : 0;
}

bool pd_gpsp_recompiling(void) {
#ifdef HAVE_DYNAREC
    return G.loaded && dynarec_enable;
#else
    return false;
#endif
}

void pd_gpsp_memory(struct pd_gpsp_memory* m) {
    memset(m, 0, sizeof(*m));
    if (!G.loaded) return;
    // gpSP keeps each RAM's code-tracking marks beside it: EWRAM's data first, IWRAM's after its 32 KB of marks.
    m->ewram = ewram;
    m->iwram = iwram + 0x8000;
    m->rom = gamepak_buffers[0];
    m->romSize = gamepak_size;
    m->io = io_registers;
    m->palette = (uint8_t*) palette_ram;
    m->vram = vram;
    m->oam = (uint8_t*) oam_ram;
}

void pd_gpsp_set_keys(uint32_t keys) {
    G.keys = keys;
}

uint32_t pd_gpsp_keys(void) {
    return G.keys;
}

void pd_gpsp_run_frame(void) {
    if (!G.loaded) return;
    if (G.skipVideo) gpsp_frontend_skip = 1;
    retro_run();
    track_save();
}

void pd_gpsp_reset(void) {
    if (G.loaded) retro_reset();
}

void pd_gpsp_set_video(uint32_t* buffer, size_t stride) {
    G.video = buffer;
    G.stride = stride;
}

void pd_gpsp_skip_video(bool skip) {
    G.skipVideo = skip;
}

uint32_t pd_gpsp_crc(void) {
    return G.crc;
}

uint32_t pd_gpsp_read8(uint32_t address) {
    return G.loaded ? read_memory8(address) : 0;
}

// gpSP's code marks (cpu_threaded.c): a write over RAM it has compiled code from throws the code out.
static void ram_written(uint32_t addr, size_t len) {
#ifdef HAVE_DYNAREC
    bool ew = (addr >> 24) == 0x02;
    if (!ew && (addr >> 24) != 0x03) return;
    const uint8_t* marks = ew ? ewram + 0x40000 : iwram;
    uint32_t mask = ew ? 0x3FFFF : 0x7FFF;
    for (size_t i = 0; i < len; i++)
        if (marks[(addr + i) & mask]) {
            flush_translation_cache_ram();
            return;
        }
#else
    (void) addr;
    (void) len;
#endif
}

bool pd_gpsp_write(uint32_t addr, const void* data, size_t len) {
    if (!G.loaded) return false;
    const uint8_t* s = data;
    uint8_t* base;
    uint32_t off = addr & 0x00FFFFFF, size;
    switch (addr >> 24) {
    case 0x02: base = ewram; off &= 0x3FFFF; size = 0x40000; break;
    case 0x03: base = iwram + 0x8000; off &= 0x7FFF; size = 0x8000; break;
    case 0x05: base = (uint8_t*) palette_ram; off &= 0x3FF; size = 0x400; break;
    case 0x06: base = vram; size = sizeof(vram); break;
    case 0x07: base = (uint8_t*) oam_ram; off &= 0x3FF; size = 0x400; break;
    default: return false;
    }
    if (off + len > size) return false;
    memcpy(base + off, s, len);
    ram_written(addr, len);
    return true;
}

bool pd_gpsp_rom_write(uint32_t addr, const void* data, size_t len) {
    if (!G.loaded) return false;
    uint32_t off = addr - 0x08000000u;
    if (addr < 0x08000000u || off >= gamepak_size || len > gamepak_size - off) return false;
    memcpy(gamepak_buffers[0] + off, data, len);
#ifdef HAVE_DYNAREC
    flush_translation_cache_rom();
#endif
    return true;
}

// --- states: "PGSP", the version, the game's CRC, retro_serialize's bytes ---

bool pd_gpsp_save_state(const char* path) {
    if (!G.loaded) return false;
    size_t size = retro_serialize_size();
    uint8_t* buf = malloc(size + 12);
    if (!buf) return false;
    uint32_t head[3] = { STATE_MAGIC, STATE_VERSION, G.crc };
    memcpy(buf, head, sizeof(head));
    bool ok = retro_serialize(buf + 12, size) && write_file(path, buf, size + 12, NULL, 0);
    free(buf);
    return ok;
}

enum pd_gpsp_state pd_gpsp_load_state(const char* path) {
    if (!G.loaded) return PD_GPSP_STATE_BAD;
    size_t size = 0;
    uint8_t* buf = read_file(path, &size);
    if (!buf) return PD_GPSP_STATE_NO_FILE;
    uint32_t head[3] = { 0 };
    if (size >= 12) memcpy(head, buf, sizeof(head));
    enum pd_gpsp_state r = PD_GPSP_STATE_OK;
    if (size < 12 || head[0] != STATE_MAGIC) r = PD_GPSP_STATE_FOREIGN;
    else if (head[2] != G.crc) r = PD_GPSP_STATE_OTHER_GAME;
    else if (head[1] != STATE_VERSION || size - 12 != retro_serialize_size()) r = PD_GPSP_STATE_BAD;
    if (r == PD_GPSP_STATE_OK) {
        // The save memory stays as it is now: a state never puts an old save back.
        uint8_t* keep = malloc(sizeof(gamepak_backup));
        if (!keep) {
            free(buf);
            return PD_GPSP_STATE_BAD;
        }
        memcpy(keep, gamepak_backup, sizeof(gamepak_backup));
        if (!retro_unserialize(buf + 12, size - 12)) r = PD_GPSP_STATE_BAD;
        memcpy(gamepak_backup, keep, sizeof(gamepak_backup));
        free(keep);
    }
    free(buf);
    return r;
}
