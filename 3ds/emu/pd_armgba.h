// armGBA (third_party/armgba: a GBA core with a recompiler) for the 3DS port's GBA games, behind mGBA's own
// `struct mCore`, so the code shared with the mGBA hosts reads the game the same way whichever core runs it. Game Boy
// and Game Boy Color games stay on mGBA. One armGBA game at a time.
//
// The core it hands out is a plain mGBA `struct mCore` with the members PokeDaisy's C calls filled in, and its `board` an
// mGBA-shaped `struct GBA` whose memory pointers point into armGBA's machine (io, palette and OAM are arrays in mGBA's
// struct: they're copied in after every frame). Its renderer is NULL, so pk_wide.c has no lines to hook and reads the
// registers a frame ends with. Nothing else in mGBA's struct is set: code that needs mGBA's CPU or renderer
// (pd_mixer.c, pk_cheats.c's ROM patches, idle-loop stats) checks pd_armgba_is() first.
//
// Based on ratherDashing's armGBA glue for the pokedaisy-3ds fork (3ds/emu/pd_emu.c there), without its Game Boy side.
#ifndef PD_ARMGBA_H
#define PD_ARMGBA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct mCore;

// What armGBA's sound comes out at, stereo, a game second's worth each game second.
#define PD_ARMGBA_SAMPLE_RATE 32768
// A GBA frame: 280896 cycles of a 16.78 MHz clock.
#define PD_ARMGBA_FPS (16777216.0 / 280896.0)

struct pd_armgba_host {
    // A frame's sound as it is made (on the thread that runs the core).
    void (*audio)(void* ctx, const int16_t* stereo, size_t frames);
    // A line from the core or this layer; NULL: dropped.
    void (*log)(void* ctx, const char* line);
    void* ctx;
    // A folder that may hold gba_bios.bin (else armGBA's own BIOS); NULL: none.
    const char* systemDir;
    // A folder for what armGBA keeps for itself (the recompiler's warm list). Never the saves.
    const char* workDir;
    // The renderer and the mixer on threads of their own (the New 3DS's other cores).
    bool threaded;
    // Where the cartridge goes: a buffer of at least size bytes, zeroed, that armGBA may "free" (the host keeps it); NULL:
    // calloc'd for each game.
    void* (*romBuffer)(size_t size);
};

// Whether a ROM of this name and header goes to armGBA (a GBA game: anything that isn't a Game Boy cart).
bool pd_armgba_wants(const uint8_t* header, size_t headerLen);

// Loads the GBA game at romPath with its save at savePath (mGBA's layout: the save memory, then the clock's 16 bytes
// where the cartridge has one; made on the game's first save). NULL with a message in error if it can't.
struct mCore* pd_armgba_load(const char* romPath, const char* savePath, const struct pd_armgba_host* host, char* error, size_t errorLen);

// Whether core is the one pd_armgba_load handed out.
bool pd_armgba_is(const struct mCore* core);
bool pd_armgba_recompiling(void);
bool pd_armgba_threaded(void);
// The frames from now on aren't shown (fast-forward's extra ones): armGBA needn't draw them.
void pd_armgba_skip_video(bool skip);
// The stock H-blank handler's interrupts with no callback taken by the host (pd_armgba.c, on by default; off to compare).
void pd_armgba_hblank_skips(bool on);
void pd_armgba_hblank_cost(int cycles);
// RAM writes (EWRAM / IWRAM), with any compiled code over them thrown out.
bool pd_armgba_write(uint32_t addr, const void* data, size_t len);

enum pd_armgba_state {
    PD_ARMGBA_STATE_OK,
    PD_ARMGBA_STATE_NO_FILE,
    PD_ARMGBA_STATE_FOREIGN,    // not armGBA's: an mGBA state from an earlier build, or anything else
    PD_ARMGBA_STATE_OTHER_GAME,
    PD_ARMGBA_STATE_BAD,        // armGBA's, but it didn't load (another version of the core)
};
// States are armGBA's own under a header naming the game; a load keeps the cartridge's save memory as it is now (a
// state must never put an old save back: CLAUDE.md's "save files are sacred").
bool pd_armgba_save_state(const char* path);
enum pd_armgba_state pd_armgba_load_state(const char* path);
// Writes the save file now if the game wrote to its save memory since the last time.
void pd_armgba_flush_save(void);

// pk_host.h / pk_call.h / pk_cheats.h for armGBA (the mGBA versions are built as *_mgba; 3ds/emu/pd_dispatch.c picks).
int pd_armgba_save_dirty(void);
int pd_armgba_finish_save(struct mCore* core, int maxFrames, void (*afterFrame)(void));
void pd_armgba_heal_rtc(void);
int pd_armgba_call(uint32_t fn, const uint32_t args[4], uint32_t* r0);
int pd_armgba_call_safe(void);
void pd_armgba_call_set_cap(long steps);
void pd_armgba_rest_note(void);
void pd_armgba_rest_reset(void);
void pd_armgba_cheats_clear(void);
// For the native m4a mixer (host/pd_mixer.c): the handler of a Thumb BKPT (true: taken, the CPU set going on with
// pd_armgba_set_reg / pd_armgba_jump; NULL: none), the registers of the mode the CPU is in, a jump (bit 0: Thumb).
void pd_armgba_set_bkpt(bool (*handler)(uint32_t address, unsigned immediate));
uint32_t pd_armgba_reg(int n);
void pd_armgba_set_reg(int n, uint32_t value);
void pd_armgba_jump(uint32_t to);
int pd_armgba_vcount(void);
int pd_armgba_cheats_apply(const char* text, size_t len);

#endif
