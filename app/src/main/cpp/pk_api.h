// PokeDaisy's emulator core as a plain C API: what a host calls to run a GBA or Game Boy game on mGBA and read its
// memory, with no JNI in it. Android's MgbaCore.kt reaches it through pokedaisy_jni.c (which adds the DS games on
// melonDS / DSperate in front of it); the iPad app calls it straight from Kotlin / Swift. The rest of pokedaisy_jni.c
// (states, cheats, EXPAND, REWIND, the render core, the game calls...) moves under here area by area.
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

/** Runs one frame: the picture into pk_api_video, its sound into pk_api_audio. */
void pk_api_run_frame(void);
/** The keys held from the next frame on (PK_API_KEY_* bits). */
void pk_api_set_keys(uint32_t keys);
/** The last frame's sound: [*count] interleaved stereo samples (left, right, ...); valid until the next frame. */
const int16_t* pk_api_audio(int* count);
/** The rate the sound is resampled to, in samples per emulated second: PK_API_SAMPLE_RATE normally, a little off it
 * while frames are paced by the display, so the device still gets PK_API_SAMPLE_RATE a real second. */
void pk_api_set_audio_rate(double rate);
/** Lets a save the game is writing finish before the game stops or a state is taken: runs up to [maxFrames] frames
 * unheard and unseen until it has (pk_host.h's pk_finish_save). Returns the frames run. */
int pk_api_finish_save(int maxFrames);

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
