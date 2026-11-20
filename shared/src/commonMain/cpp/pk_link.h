#ifndef PK_LINK_H
#define PK_LINK_H

// LINK PLAY: up to four GBA games joined by mGBA's own link cable (gba/sio/lockstep.c), in one process, on one thread
// (pk_link.c). Separate from the player's core in pokedaisy_jni.c: none of the companion's reads, EXPAND or cheats.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <mgba/core/core.h>

#define PK_LINK_MAX 4

/** Opens [n] games (2..PK_LINK_MAX) and joins them: [roms] / [saves] in link order, game 0 the cable's parent. A save
 * that can't be written is read only (its progress isn't kept), as the player's own core does. False if one can't. */
bool pk_link_open(int n, const char* const* roms, const char* const* saves, int sampleRate);

/** Lets every game finish a save it's writing (at most [maxFrames] frames, the link running), then closes them all. */
void pk_link_close(int maxFrames);

int pk_link_count(void);

/** One frame of game 0, the others kept in step with it. False on a lockstep fault (both waiting), reported once. */
bool pk_link_run_frame(void);

struct mCore* pk_link_core(int i);
const color_t* pk_link_video(int i);
unsigned pk_link_video_w(int i);
unsigned pk_link_video_h(int i);
void pk_link_set_keys(int i, uint32_t keys);

/** Game [i]'s audio since the last frame, interleaved stereo, into [out] (up to [maxFrames] frames); the frames read.
 * Every game's audio is drained after each frame, so a game that isn't read is simply not heard. */
int pk_link_read_audio(int i, int16_t* out, int maxFrames);

/** The audio rate every game is resampled to (the player's pacing, like pkSetAudioRate). */
void pk_link_set_audio_rate(double rate);

/** True while a game is writing its save (mGBA's dirty flag): don't stop it now. */
bool pk_link_saving(void);

/** Each game's SIO mode, the lockstep phase and the budgets, for logs. */
void pk_link_status(char* out, size_t size);

#endif
