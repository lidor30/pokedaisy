// PokeDaisy's emulator core as a plain C API: what a host calls to run a GBA or Game Boy game on mGBA and read its
// memory, with no JNI in it. Android's MgbaCore.kt reaches it through pokedaisy_jni.c (which adds the DS games on
// melonDS / DSperate in front of it); the iPad app calls it straight from Kotlin / Swift. The rest of pokedaisy_jni.c
// (EXPAND, the render core, the game calls...) moves under here area by area.
//
// Threads: the emu thread owns the game - init / deinit, frames, keys, audio. pk_api_read and the ROM reads may come
// from any thread (they take the core lock); they return false with no game up. GPL-3.0 (see NOTICE).
#ifndef PK_API_H
#define PK_API_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** The rate pk_api_audio's samples come at (unless pk_api_set_audio_rate moves it). */
#define PK_API_SAMPLE_RATE 48000

/** pk_api_platform's answers. */
#define PK_API_PLATFORM_NONE (-1)
#define PK_API_PLATFORM_GBA 0
#define PK_API_PLATFORM_GB 1

/** The GBA's keys as pk_api_set_keys' bits (a Game Boy game ignores L / R). */
enum {
    PK_API_KEY_A = 1 << 0, PK_API_KEY_B = 1 << 1, PK_API_KEY_SELECT = 1 << 2, PK_API_KEY_START = 1 << 3,
    PK_API_KEY_RIGHT = 1 << 4, PK_API_KEY_LEFT = 1 << 5, PK_API_KEY_UP = 1 << 6, PK_API_KEY_DOWN = 1 << 7,
    PK_API_KEY_R = 1 << 8, PK_API_KEY_L = 1 << 9
};

/** Starts the game at [romPath] (a .gba, .gb or .gbc), any game before it closed first. [savePath] (may be NULL) is
 * its battery save, created if missing and written as the game saves; a file it can't write still loads. */
bool pk_api_init(const char* romPath, const char* savePath);
/** Closes the game: its save written and closed, everything it held freed. Nothing up = nothing to do. */
void pk_api_deinit(void);
/** PK_API_PLATFORM_GBA / _GB, or _NONE with no game up. */
int pk_api_platform(void);

/** The last frame, pk_api_video_width() x pk_api_video_height() pixels of 4 bytes (R, G, B, unused), rows packed;
 * NULL with no game. The buffer stays put until pk_api_deinit. */
const void* pk_api_video(void);
int pk_api_video_width(void);
int pk_api_video_height(void);

/** Reversible GBA game-option patches, game thread between frames. New edits require matching expected bytes (1..64);
 * replacement is 1..4 bytes at a ROM address. Never changes the file. False for invalid input or no GBA core.
 * Repeating an already patched address is idempotent; unpatch before replacing the set of edits.
 * unpatch restores all game-option edits (and uninstalls co-op edits sharing that patch set). */
bool pk_api_rom_patch(uint32_t expectAddr, const uint8_t* expect, int expectLen,
                      uint32_t addr, const uint8_t* value, int len);
void pk_api_rom_unpatch(void);

/** Check GBA cheat lines without a running core, using mGBA's existing parser and GBACheatType values.
 * Returns a malloc-owned ASCII string: directive, newline, then one '1'/'0' per line; NULL on allocation failure.
 * The caller must free it. NULL code/directive mean empty strings. No game state is read or changed. */
char* pk_api_cheats_check(const char* code, int type, const char* directive);
/** Replace the running GBA game's enabled cheats with mGBA .cheats text (NULL/empty clears them).
 * Returns sets loaded, -1 on parse failure, 0 with no GBA game. Game thread between frames; takes the core lock.
 * Uses the existing patch/overlay bookkeeping; game-option patches remain separate. */
int pk_api_cheats_apply(const char* text);

/** Game thread between frames: write scratch data in GBA EWRAM/IWRAM or GB WRAM only.
 * CGB bank B uses (B << 16) | D000-DFFF. Rejects empty, crossing or non-RAM ranges without writing. */
bool pk_api_write(uint32_t addr, const uint8_t* src, int len);
/** Whether the running GBA core is at a safe depth for a game-function call; false on GB/no game. */
bool pk_api_call_safe(void);
/** Call an existing GBA Thumb function, preserving the CPU context. Returns unsigned r0 as int64_t,
 * or -1 if unavailable/unsafe/timed out. Game thread between frames; memory effects are not rolled back. */
int64_t pk_api_call(uint32_t fn, uint32_t a0, uint32_t a1, uint32_t a2, uint32_t a3);
/** Let a GB game reach [at], then arrange its call of [fn] with A/B/HL (at most two HALT waits).
 * Returns once scheduled, not when fn returns. Game thread between frames; false on GBA/no game. */
bool pk_api_gb_call_at(uint16_t at, uint16_t fn, uint8_t a, uint8_t b, uint16_t hl);

/** Field observation only: configure the current GBA ROM by its CRC32 (zero disables), then read PK_API_FIELD_*.
 * Game thread, between frames. Unknown games, Game Boy and no core return zero. Does not enable game patches. */
bool pk_api_field_configure(uint32_t crc);
int pk_api_field_state(void);
/** Bind the verified EWRAM encounter-switch address after field_configure; zero disables it.
 * wasOff restores ownership when resuming a state saved while encounters were off. Game thread only. */
bool pk_api_field_wild(uint32_t addr, bool wasOff);
/** Run the existing field policy once per frame, including optional indoor running and encounter control.
 * wildKeep is clamped to 0..100; no known GBA field returns zero without writes. Game thread only. */
int pk_api_field_step(bool runIndoors, int wildKeep);

enum {
    PK_API_FIELD_KNOWN = 1, PK_API_FIELD_LOCKED = 2, PK_API_FIELD_BATTLE = 4, PK_API_FIELD_TEXT = 8,
    PK_API_FIELD_BIKE = 16, PK_API_FIELD_WILD_HELD = 32, PK_API_FIELD_SURF = 64, PK_API_FIELD_AVATAR = 128,
    PK_API_FIELD_WINDOW = 262144
};

/** Runs one frame: the picture into pk_api_video, its sound into pk_api_audio. */
void pk_api_run_frame(void);
/** The keys held from the next frame on (PK_API_KEY_* bits). */
void pk_api_set_keys(uint32_t keys);
/** The last frame's sound: [*count] interleaved stereo samples (left, right, ...); valid until the next frame. */
const int16_t* pk_api_audio(int* count);
/** The rate the sound is resampled to, in samples per emulated second: PK_API_SAMPLE_RATE normally, a little off it
 * while frames are paced by the display, so the device still gets PK_API_SAMPLE_RATE a real second. */
void pk_api_set_audio_rate(double rate);
/** Keep [entries] rewind snapshots, sampled every two game frames; zero/negative frees the history.
 * Game thread only. Uses the existing mGBA diff ring without battery-save data. Reconfiguring releases rewind. */
void pk_api_set_rewind(int entries);
/** While held, run_frame restores an older snapshot before drawing. No history stands still, with no audio.
 * Nothing happens with rewind disabled. Game thread only; deinit and successful state loads clear the history. */
void pk_api_set_rewinding(bool on);
/** Lets a save the game is writing finish before the game stops or a state is taken: runs up to [maxFrames] frames
 * unheard and unseen until it has (pk_host.h's pk_finish_save). Returns the frames run. */
int pk_api_finish_save(int maxFrames);

/** mGBA extended state with battery save, RTC and metadata. Game thread, between frames; false on failure.
 * Saving truncates [path]: hosts should write a temporary file and atomically replace the destination.
 * Loading intentionally restores the battery save too; make a backup before a manual load. */
bool pk_api_save_state(const char* path);
bool pk_api_load_state(const char* path);
/** Compare the state's battery save with the opened save file before automatic resume: 1 = equal, 0 = changed,
 * -1 = no embedded save or unreadable state. RTC footers are ignored; missing save bytes count as erased 0xFF.
 * Call on the game thread. Hosts must skip automatic resume when they cannot establish a match. */
int pk_api_state_matches_save(const char* path);

/** [len] bytes of the game's memory at [addr] into [dst]: the GBA's bus, a GBA cart's ROM from its file, a Game Boy
 * Color's WRAM bank B as (B << 16) | D000-DFFF. False (and [dst] untouched) with no game up. Any thread. */
bool pk_api_read(uint32_t addr, uint8_t* dst, int len);
/** The GBA header's 4-letter game code into [out] (NUL-terminated; "" for a Game Boy game). False with no game. */
bool pk_api_rom_code(char out[5]);
/** The loaded ROM's size in bytes, 0 with no game. */
int64_t pk_api_rom_size(void);
/** A Game Boy cart's own bytes [off, off + len) into [dst] (it's bank-switched, so not on the bus whole). False on a
 * GBA game, with no game, or past the cart's end. Any thread. */
bool pk_api_rom_read(int64_t off, uint8_t* dst, int len);

#ifdef __cplusplus
}
#endif

#endif
