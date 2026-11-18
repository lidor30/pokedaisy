// gpSP (third_party/gpsp, bheppe's gpSP 3D) as the 3DS port's third GBA core: the library's GBA CORE GPSP. Its ARM11
// recompiler is the fastest of the three on an Old 3DS (gpSP 3D's own claim: full speed there; not yet measured in
// this app), its drawing on another core (pd_gpsp_host.threaded). Like armGBA it sits behind an mGBA-shaped `struct mCore` (pd_gpsp_core.c, its board a view of gpSP's
// memory: RAM, ROM, VRAM, and io / palette / OAM copied after each frame, no renderer), so the companion's readers run
// unchanged. Cheats are mGBA's parser and cheat device over that view (a hook code's sets run at the frame's end). The
// game's empty H-blank interrupts are stood in for as on armGBA (pd_gpsp_hblank_skips).
//
// Two halves: pd_gpsp.c (the only file that sees gpSP's headers; built into one object with gpSP whose only globals are
// the pd_gpsp_* below, emu/gpsp.cmake) and pd_gpsp_core.c (the mCore, mGBA's headers).
#ifndef PD_GPSP_H
#define PD_GPSP_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct mCore;

#define PD_GPSP_SAMPLE_RATE 32768 // gpSP's sound, a GBA second's samples (gpsp_sound_rate)
#define PD_GPSP_FPS (16777216.0 / 280896.0)

struct pd_gpsp_host {
    // A frame's sound as it is made (stereo pairs, on the thread that runs the core).
    void (*audio)(void* ctx, const int16_t* stereo, size_t frames);
    // A line from the core or this layer; NULL: dropped.
    void (*log)(void* ctx, const char* line);
    void* ctx;
    // A folder that may hold gba_bios.bin (else gpSP's own BIOS).
    const char* systemDir;
    // A contiguous 32 MiB cartridge, zeroed, never freed (the host keeps it).
    // NULL callback uses a private arena. A failed allocation refuses to start.
    void* (*romBuffer)(size_t size);
    // The ROM's CRC-32 when the host knows it from an earlier start of the same file (0: worked out here).
    uint32_t knownCrc;
    // gpSP's render thread (video_thread.cc): drawing a few lines behind the game, the same picture, no frame later.
    // On [renderCore] (the New 3DS's third, an Old 3DS's system core with its time lent: host_core1_take; -1 = any, the
    // simulator); off, the game's thread draws.
    bool threaded;
    int renderCore;
};

// The mCore (pd_gpsp_core.c): NULL with [error] when the game didn't load.
struct mCore* pd_gpsp_load(const char* romPath, const char* savePath, const struct pd_gpsp_host* host, char* error, size_t errorLen);
bool pd_gpsp_is(const struct mCore* core);
// pk_cheats.h on gpSP (pd_gpsp_core.c): mGBA's parser and cheat device over the view, applied at each frame's end.
void pd_gpsp_cheats_clear(void);
int pd_gpsp_cheats_apply(const char* text, size_t len);

// --- pd_gpsp.c: gpSP itself ---

struct pd_gpsp_memory {
    uint8_t* ewram;   // 256 KB
    uint8_t* iwram;   // 32 KB
    uint8_t* rom;     // the whole cartridge, in one block (the host's buffer)
    size_t romSize;
    uint16_t* io;     // 1 KB
    uint8_t* palette; // 1 KB
    uint8_t* vram;    // 96 KB
    uint8_t* oam;     // 1 KB
};

bool pd_gpsp_open(const char* romPath, const char* savePath, const struct pd_gpsp_host* host, char* error, size_t errorLen);
void pd_gpsp_close(void);
bool pd_gpsp_loaded(void);
void pd_gpsp_memory(struct pd_gpsp_memory* m);
void pd_gpsp_set_keys(uint32_t keys); // mGBA's GBA key bits (A 0 ... L 9)
uint32_t pd_gpsp_keys(void);
void pd_gpsp_run_frame(void);
void pd_gpsp_reset(void);
// The picture into [buffer]: 0x00BBGGRR, as mGBA's 32-bit renderer leaves it.
void pd_gpsp_set_video(uint32_t* buffer, size_t stride);
// Final RGB565 output, borrowed until the next core frame. stride is in pixels.
const uint16_t* pd_gpsp_native_pixels(size_t* stride);
// The frames from now on aren't shown (fast-forward's extra ones, the Old 3DS's catch-up): gpSP needn't draw them.
void pd_gpsp_skip_video(bool skip);
// The next frame drawn isn't waited for at its end: its picture is collected by pd_gpsp_collect after one or more
// undrawn frames (host/pd_draw_pace.h). Only with the render thread.
void pd_gpsp_draw_late(void);
// Waits for the picture started late (its frame hook converts it into the video buffer); false = none was on its way.
bool pd_gpsp_collect(void);
uint32_t pd_gpsp_crc(void);
bool pd_gpsp_recompiling(void);
bool pd_gpsp_threaded(void);
// The render thread's last frame: its drawing and the game thread's wait for it (microseconds; 0 without the thread).
void pd_gpsp_render_us(unsigned* render, unsigned* wait);
struct pd_gpsp_stats {
    int workerCpu, startError;
    bool threaded;
    unsigned snapshotBytes, renderUs, waitUs, hookUs;
    unsigned romFlushes, ramFullFlushes;
};
void pd_gpsp_get_stats(struct pd_gpsp_stats* out);
// Any address, through gpSP's own memory map (what the view doesn't hold).
uint32_t pd_gpsp_read8(uint32_t address);
// RAM writes (EWRAM / IWRAM / io / palette / VRAM / OAM), any compiled code over them thrown out.
bool pd_gpsp_write(uint32_t addr, const void* data, size_t len);
// The cartridge's bytes at [addr] (a game patch, between frames), the recompiled ROM code dropped.
bool pd_gpsp_rom_write(uint32_t addr, const void* data, size_t len);
// The save file (mGBA's layout: the save memory, then a 16-byte clock armGBA and mGBA keep, kept as it was).
int pd_gpsp_save_dirty(void);
void pd_gpsp_flush_save(void);

// pk_call.h on gpSP (3ds/emu/pd_dispatch.c picks): a game function run to its return between frames.
int pd_gpsp_call(uint32_t fn, const uint32_t args[4], uint32_t* r0);
int pd_gpsp_call_safe(void);
void pd_gpsp_call_set_cap(long steps);
void pd_gpsp_rest_note(void);
void pd_gpsp_rest_reset(void);

// A Thumb BKPT in the game's code to [hook] (pd_mixer.c), with the CPU between instructions: true when the hook has set
// the CPU itself (pd_gpsp_set_reg / _jump), else the BKPT does nothing. NULL: none.
void pd_gpsp_set_bkpt(bool (*hook)(uint32_t pc, unsigned immediate));
uint32_t pd_gpsp_reg(int n);
void pd_gpsp_set_reg(int n, uint32_t v);
void pd_gpsp_jump(uint32_t to);
int pd_gpsp_vcount(void);

// The game's stock H-blank handler stood in for when it has nothing to call (on by default; the sim's PD_SIM_HBLANK=0
// raises them as ever), and the cycles each is charged (190).
void pd_gpsp_hblank_skips(bool on);
void pd_gpsp_hblank_cost(int cycles);

enum pd_gpsp_state {
    PD_GPSP_STATE_OK,
    PD_GPSP_STATE_NO_FILE,
    PD_GPSP_STATE_FOREIGN,    // not gpSP's (armGBA's, mGBA's, anything else)
    PD_GPSP_STATE_OTHER_GAME,
    PD_GPSP_STATE_BAD,        // gpSP's, but it didn't load
};
// States are gpSP's own under a header naming the game; a load keeps the cartridge's save memory as it is now (a
// state must never put an old save back: CLAUDE.md's "save files are sacred").
bool pd_gpsp_save_state(const char* path);
enum pd_gpsp_state pd_gpsp_load_state(const char* path);

#endif
