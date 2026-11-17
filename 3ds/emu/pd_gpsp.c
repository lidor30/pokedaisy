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
extern const unsigned gamepak_buffer_blocksize;
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

static void hblank_find(void); // the H-blank stand-in (below), set up as a game opens

static struct {
    bool loaded;
    uint8_t* romArena;
    struct pd_gpsp_host host;
    char savePath[1024];
    uint32_t keys;
    uint32_t* video;
    const uint16_t* nativePixels;
    size_t nativeStride;
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
    int threadStartError; // this open's attempt, not the previous game's worker
    volatile bool hookDrew; // the render thread's frame hook already wrote this frame into [video]
    char lastWarn[160];     // gpSP's last warning or error, for a failed load's message
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
    if (level < RETRO_LOG_WARN) return;
    char line[480];
    va_list args;
    va_start(args, format);
    vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    line[strcspn(line, "\n")] = 0;
    snprintf(G.lastWarn, sizeof(G.lastWarn), "%s", line);
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
    if (!data || G.skipVideo) return;
    G.nativePixels = data;
    G.nativeStride = pitch / sizeof(uint16_t);
    if (!G.video || drew) return;
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
    static size_t capacity;
    if (!arena) {
        arena = malloc(size);
        if (arena) capacity = size;
    }
    if (size > capacity) return NULL;
    if (arena) memset(arena, 0, size);
    return arena;
}

#define GPSP_ROM_ARENA_SIZE (32u << 20)

static u8* reserved_arena(size_t size) {
    return size <= GPSP_ROM_ARENA_SIZE ? G.romArena : NULL;
}

// A fragmented gpSP cartridge cannot be exposed as a flat ROM to the
// companion, mixer or CRC reader. Check before any of them can see it.
static bool flat_rom_ready(void) {
    if (!G.romArena || !gamepak_size || gamepak_size > GPSP_ROM_ARENA_SIZE) return false;
    size_t blocks = (gamepak_size + gamepak_buffer_blocksize - 1) / gamepak_buffer_blocksize;
    if (blocks > gamepak_buffer_count) return false;
    for (size_t i = 0; i < blocks; ++i) {
        if (gamepak_buffers[i] != G.romArena + i * gamepak_buffer_blocksize) return false;
    }
    return true;
}

bool pd_gpsp_open(const char* romPath, const char* savePath, const struct pd_gpsp_host* host, char* error, size_t errorLen) {
    if (G.loaded) {
        snprintf(error, errorLen, "a game is already running");
        return false;
    }
    memset(&G, 0, sizeof(G));
    G.host = *host;
    snprintf(G.savePath, sizeof(G.savePath), "%s", savePath);
    // Acquire the contiguous cartridge BEFORE retro_init(). Otherwise gpSP
    // silently falls back to separate 1 MiB allocations and ROM scans crash.
    G.romArena = host->romBuffer ? host->romBuffer(GPSP_ROM_ARENA_SIZE) : own_arena(GPSP_ROM_ARENA_SIZE);
    if (!G.romArena) {
        snprintf(error, errorLen, "gpSP needs a contiguous 32 MiB ROM buffer");
        emu_log("gpSP: no contiguous 32 MiB ROM buffer; core not started");
        return false;
    }
    gpsp_host_rom_arena = reserved_arena;

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
        if (G.lastWarn[0]) snprintf(error, errorLen, "gpSP couldn't load it: %s", G.lastWarn);
        else snprintf(error, errorLen, "gpSP couldn't load it (no reason given)");
        return false;
    }
    if (!flat_rom_ready()) {
        retro_unload_game();
        retro_deinit();
        snprintf(error, errorLen, "gpSP ROM buffer is fragmented or too small");
        return false;
    }
#ifdef HAVE_DYNAREC
    emu_log("gpSP JIT: %s; ROM cache %u KiB, RAM cache %u KiB",
            dynarec_enable ? "enabled" : "INTERPRETER fallback",
            (unsigned) (ROM_TRANSLATION_CACHE_SIZE >> 10), (unsigned) (RAM_TRANSLATION_CACHE_SIZE >> 10));
#endif
    G.loaded = true;
    if (host->threaded) {
        emu_log("gpSP render worker: target CPU %d, snapshots %u KiB plus stack/TLS",
                host->renderCore, (video_thread_snapshot_bytes() + 1023) >> 10);
        if (video_thread_start(host->renderCore) == 0) {
            video_thread_set_frame_hook(frame_hook, NULL);
            G.threaded = true;
        } else {
            static const char* const stages[] = { "unknown", "frame slot 0 allocation", "frame slot 1 allocation",
                "line semaphore", "done semaphore", "sync semaphore", "VRAM snapshots", "thread creation" };
            int err = G.threadStartError = video_thread_start_error();
            unsigned stage = err < 0 && err >= -7 ? (unsigned) -err : 0;
            emu_log("gpSP render worker FAILED: %s (stage %d); see thread-create Results", stages[stage], err);
        }
    }
    G.crc = host->knownCrc ? host->knownCrc : crc32_of(gamepak_buffers[0], gamepak_size);
    read_save();
    hblank_find();
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
    pd_gpsp_set_bkpt(NULL);
    gpsp_hblank_hook = NULL;
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

void pd_gpsp_get_stats(struct pd_gpsp_stats* out) {
    memset(out, 0, sizeof(*out));
    out->threaded = G.threaded;
    out->workerCpu = G.threaded ? video_thread_worker_cpu() : -1;
    out->startError = G.threadStartError;
    out->snapshotBytes = video_thread_snapshot_bytes();
    if (G.threaded) {
        out->renderUs = video_thread_last_render_us();
        out->waitUs = video_thread_last_wait_us();
        out->hookUs = video_thread_last_hook_us();
    }
#ifdef HAVE_DYNAREC
    extern u32 pd_gpsp_rom_flushes, pd_gpsp_ram_flushes;
    out->romFlushes = pd_gpsp_rom_flushes;
    out->ramFullFlushes = pd_gpsp_ram_flushes;
#endif
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

const uint16_t* pd_gpsp_native_pixels(size_t* stride) {
    *stride = G.nativeStride;
    return G.loaded ? G.nativePixels : NULL;
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
    bool touched = false;
    for (size_t i = 0; i < len; i++) {
        // gpSP stores a 16-bit code tag per halfword. A byte write must
        // inspect both tag bytes, including when it hits the odd address.
        uint32_t at = ((addr + (uint32_t) i) & mask) & ~1u;
        if (marks[at] || marks[at + 1]) {
            // The core can retain translations outside these writes. Without
            // SMC notes, its compatibility fallback clears the whole RAM JIT.
            smc_note_write(addr + (uint32_t) i);
            touched = true;
        }
    }
    if (touched) flush_translation_cache_ram();
#else
    (void) addr;
    (void) len;
#endif
}

bool pd_gpsp_write(uint32_t addr, const void* data, size_t len) {
    if (!G.loaded) return false;
    if (!len) return true;
    if (!data) return false;
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
    if (off > size || len > size - off) return false;
    if (((addr >> 24) == 2 || (addr >> 24) == 3) && !memcmp(base + off, s, len)) return true;
    memcpy(base + off, s, len);
    ram_written(addr, len);
    return true;
}

bool pd_gpsp_rom_write(uint32_t addr, const void* data, size_t len) {
    if (!G.loaded) return false;
    uint32_t off = addr - 0x08000000u;
    if (addr < 0x08000000u || off >= gamepak_size || len > gamepak_size - off) return false;
    if (!len) return true;
    if (!data) return false;
    if (!memcmp(gamepak_buffers[0] + off, data, len)) return true;
    memcpy(gamepak_buffers[0] + off, data, len);
#ifdef HAVE_DYNAREC
    flush_translation_cache_rom();
#endif
    return true;
}

// --- pk_call.h: the game's own Thumb functions called between frames ---
// As pd_armgba_call.c / the mGBA hosts' pk_call.c: the CPU run from the function to its return (on gpSP's interpreter,
// in the recompiler's build too: gpsp-pokedaisy.patch's execute_arm_until, the GBA's hardware not run meanwhile), IRQs
// masked, on the main stack in System mode, then every register put back.

#define CALL_SENTINEL 0x080000C0u // the cartridge header's: never code a game runs
#define CALL_CAP 2000000
static long callCap = CALL_CAP;

void pd_gpsp_call_set_cap(long steps) {
    callCap = steps > 0 ? steps : CALL_CAP;
}

// Where the game's main loop waits for VBlank, as a stack depth and the PC it was at (see pk_call.c): a call is safe
// there. gpSP ends a frame wherever its CPU is when the frame's time is up - FireRed in its wait loop, Emerald mostly in
// the middle of its work - so a call first runs the game on (gpsp_advance) to its wait: halted in VBlankIntrWait, or at
// the place most frames end in.
#define REST_SLOTS 8
#define REST_FADE 120 // notes between two halvings of the counts: the boot's screens don't outweigh the field's for long
static uint32_t restSp[REST_SLOTS], restPc[REST_SLOTS], restCount[REST_SLOTS], restNotes;

// gpSP keeps User / System's r13 in reg_mode[0] while another mode is in.
static uint32_t sys_sp(void) {
    return (reg[CPU_MODE] & 0xF) == (MODE_USER & 0xF) ? reg[REG_SP] : REG_MODE(MODE_USER)[5];
}

void pd_gpsp_rest_reset(void) {
    memset(restSp, 0, sizeof(restSp));
    memset(restPc, 0, sizeof(restPc));
    memset(restCount, 0, sizeof(restCount));
    restNotes = 0;
}

void pd_gpsp_rest_note(void) {
    if (!G.loaded || reg[CPU_MODE] == MODE_IRQ) return;
    if (++restNotes % REST_FADE == 0)
        for (int i = 0; i < REST_SLOTS; i++) restCount[i] /= 2;
    uint32_t sp = sys_sp();
    int low = 0;
    for (int i = 0; i < REST_SLOTS; i++) {
        if (restSp[i] == sp) {
            if (restCount[i] < 100000) restCount[i]++;
            restPc[i] = reg[REG_PC];
            return;
        }
        if (restCount[i] < restCount[low]) low = i;
    }
    restSp[low] = sp;
    restPc[low] = reg[REG_PC];
    restCount[low] = 1;
}

static int rest_slot(void) {
    int best = 0;
    for (int i = 1; i < REST_SLOTS; i++)
        if (restCount[i] > restCount[best]) best = i;
    return restCount[best] >= 30 ? best : -1;
}

// At the game's wait: halted (VBlankIntrWait), or on the stack depth most frames end at; never in an interrupt.
static bool at_rest(void) {
    if (reg[CPU_MODE] == MODE_IRQ) return false;
    if (reg[CPU_HALT_STATE] != CPU_ACTIVE) return true;
    int r = rest_slot();
    return r >= 0 && sys_sp() == restSp[r];
}

// Thumb `b .` at the PC: a parked game (the music renderer's).
static bool parked(void) {
    return (reg[REG_CPSR] & 0x20) && (read_memory8(reg[REG_PC]) | read_memory8(reg[REG_PC] + 1) << 8) == 0xE7FE;
}

#define ADVANCE_CAP 400000
// The game's own code run on (the GBA's hardware not: no new interrupt, no line drawn) until it waits: it halts, or
// reaches the PC most frames end at. What it does there it would have done before the next VBlank anyway.
static bool gpsp_advance(void) {
    if (at_rest()) return true;
    int r = rest_slot();
    execute_arm_until(r >= 0 ? restPc[r] : CALL_SENTINEL, ADVANCE_CAP);
    return at_rest();
}

int pd_gpsp_call_safe(void) {
    // Anywhere but an interrupt: the call runs the game on to its wait first (gpsp_advance).
    return G.loaded && (parked() || reg[CPU_MODE] != MODE_IRQ);
}

int pd_gpsp_call(uint32_t fn, const uint32_t args[4], uint32_t* r0) {
    if (!pd_gpsp_call_safe()) return 0;
    if (!parked() && !gpsp_advance()) return 0; // it didn't reach its wait: the next frame tries again
    static u32 savedReg[64], savedMode[7][7], savedSpsr[6];
    memcpy(savedReg, reg, sizeof(savedReg));
    memcpy(savedMode, reg_mode, sizeof(savedMode));
    memcpy(savedSpsr, spsr, sizeof(savedSpsr));

    reg[CPU_HALT_STATE] = CPU_ACTIVE;
    // On the main stack, in System mode (see pk_call.c: the IRQ stack is 160 bytes), IRQs masked, Thumb.
    if ((reg[CPU_MODE] & 0xF) != (MODE_USER & 0xF)) set_cpu_mode(MODE_SYSTEM);
    reg[REG_CPSR] = (reg[REG_CPSR] & ~0x1Fu) | (reg[CPU_MODE] == MODE_USER ? 0x10 : 0x1F);
    reg[REG_CPSR] |= 0x80 | 0x20;
    for (int i = 0; i < 4; i++) reg[i] = args[i];
    reg[REG_LR] = CALL_SENTINEL | 1;
    reg[REG_PC] = fn & ~1u;
    int ok = execute_arm_until(CALL_SENTINEL, (u32) callCap);
    if (r0) *r0 = reg[0];

    // What the call drew stays marked for the renderer (VRAM, the palette); everything else as it was.
    u32 vramUpdated = reg[REG_VRAM_UPDATED], palUpdated = reg[REG_PAL_UPDATED];
    memcpy(reg, savedReg, sizeof(savedReg));
    memcpy(reg_mode, savedMode, sizeof(savedMode));
    memcpy(spsr, savedSpsr, sizeof(savedSpsr));
    reg[REG_VRAM_UPDATED] |= vramUpdated;
    reg[REG_PAL_UPDATED] |= palUpdated;
    return ok;
}

// --- H-blank interrupts with nothing to do (pd_armgba.c's, the same handler found the same way) ---
// A battle keeps the H-blank interrupt on with no callback set: 228 interrupts a frame that only set two flags. Where the
// game has pret's stock HBlankIntr, gpsp-pokedaisy.patch's gpsp_hblank_hook asks here first: with no callback the flags
// are set here and the interrupt isn't raised, its time (190 cycles, armGBA's) taken from the CPU's next run.

static const uint8_t HBLANK_INTR[40] = {
    0x10, 0xB5, 0x09, 0x4C, 0x20, 0x69, 0x00, 0x28, 0x01, 0xD0, 0x00, 0x00, 0x00, 0x00, 0x07, 0x4A, 0x10, 0x88, 0x02, 0x21,
    0x08, 0x43, 0x10, 0x80, 0xA0, 0x8B, 0xA2, 0x8B, 0x01, 0x43, 0xA1, 0x83, 0x10, 0xBC, 0x01, 0xBC, 0x00, 0x47, 0x00, 0x00,
};
#define HBLANK_INTR_BL 10 // the bl to _call_via_r0, 4 bytes, masked
#define INTR_CHECK 0x03007FF8u
#define MAIN_HBLANK_CALLBACK 0x10
#define MAIN_INTR_CHECK 0x1C
#define IRQ_FLAG_HBLANK 2

static uint32_t hblankMain; // gMain, where the game has the stock handler; 0: the interrupt runs as ever
static bool hblankSkips = true;
static int hblankCost = 190;

static uint8_t* hblank_ram(uint32_t addr, uint32_t len) {
    if (addr >= 0x02000000u && addr - 0x02000000u + len <= 0x40000) return ewram + (addr - 0x02000000u);
    if (addr >= 0x03000000u && addr - 0x03000000u + len <= 0x8000) return iwram + 0x8000 + (addr - 0x03000000u);
    return NULL;
}

static int hblank_hook(void) {
    uint8_t* m = hblank_ram(hblankMain, 0x20);
    uint8_t* check = hblank_ram(INTR_CHECK, 2);
    if (!m || !check) return -1;
    if (m[MAIN_HBLANK_CALLBACK] | m[MAIN_HBLANK_CALLBACK + 1] | m[MAIN_HBLANK_CALLBACK + 2] | m[MAIN_HBLANK_CALLBACK + 3]) return -1;
    check[0] |= IRQ_FLAG_HBLANK;
    m[MAIN_INTR_CHECK] |= IRQ_FLAG_HBLANK;
    return hblankCost;
}

static void hblank_find(void) {
    hblankMain = 0;
    gpsp_hblank_hook = NULL;
    const uint8_t* rom = gamepak_buffers[0];
    size_t size = gamepak_size < 0x400000 ? gamepak_size : 0x400000; // it sits near the start
    for (size_t at = 0; rom && at + 48 <= size; at += 4) {
        if (rom[at] != 0x10 || rom[at + 1] != 0xB5 || memcmp(rom + at, HBLANK_INTR, HBLANK_INTR_BL) ||
            memcmp(rom + at + HBLANK_INTR_BL + 4, HBLANK_INTR + HBLANK_INTR_BL + 4, sizeof(HBLANK_INTR) - HBLANK_INTR_BL - 4))
            continue;
        uint32_t main = (uint32_t) rom[at + 40] | (uint32_t) rom[at + 41] << 8 | (uint32_t) rom[at + 42] << 16 | (uint32_t) rom[at + 43] << 24;
        uint32_t check = (uint32_t) rom[at + 44] | (uint32_t) rom[at + 45] << 8 | (uint32_t) rom[at + 46] << 16 | (uint32_t) rom[at + 47] << 24;
        if (check != INTR_CHECK || !hblank_ram(main, 0x20)) continue;
        hblankMain = main;
        break;
    }
    if (hblankMain && hblankSkips) gpsp_hblank_hook = hblank_hook;
    emu_log("gpSP H-blank handler: %s", hblankMain ? "the stock one, taken when it has no callback" : "not the stock one, left alone");
}

void pd_gpsp_hblank_skips(bool on) {
    hblankSkips = on;
    gpsp_hblank_hook = hblankMain && on ? hblank_hook : NULL;
}

void pd_gpsp_hblank_cost(int cycles) {
    hblankCost = cycles;
}

// --- the BKPT hook (pd_mixer.c's native mixer): gpsp-pokedaisy.patch's gpsp_bkpt, from the interpreter and the recompiler ---

static bool (*bkptHook)(uint32_t pc, unsigned immediate);

static u32 on_bkpt(u32 pc, u32 immediate) {
    return bkptHook && bkptHook(pc, immediate);
}

void pd_gpsp_set_bkpt(bool (*hook)(uint32_t pc, unsigned immediate)) {
    bkptHook = hook;
    gpsp_bkpt_hook = hook ? on_bkpt : NULL;
}

// The CPU as the hook sees it: reg[] holds every register while it runs (the recompiler's stub stored its cached ones).
uint32_t pd_gpsp_reg(int n) {
    return reg[n];
}

void pd_gpsp_set_reg(int n, uint32_t v) {
    reg[n] = v;
}

// On from [to] (bit 0: Thumb) once the hook returns.
void pd_gpsp_jump(uint32_t to) {
    reg[REG_PC] = to & ~1u;
    if (to & 1) reg[REG_CPSR] |= 0x20;
    else reg[REG_CPSR] &= ~0x20u;
}

int pd_gpsp_vcount(void) {
    return read_ioreg(REG_VCOUNT);
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
