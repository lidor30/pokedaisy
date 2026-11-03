// STEADY FF music for a DS game: the music the game is playing, heard at 1x while the game runs fast.
//
// A DS game's sequencer runs on the ARM7 (the SDK's sound driver: the ARM9 only sends it commands), from data the game
// already loaded into main RAM. So a second melonDS loaded from the game's own state with its ARM9 stopped for good goes
// on playing exactly what the game was playing, from that very moment, by itself - its loops, its tempo, nothing to
// record or cut. That copy has no cart (the cart's part of the state is skipped, so its save is never touched) and no JIT,
// and only its ARM7, timers and sound run; what the ARM7 sends the ARM9 is read off its FIFO every frame, as the ARM9
// would (a full FIFO stalls the driver: a buzz), and the shared flag the ARM9 releases is released (else the driver spins
// on it for good: a drone). A new song in the game: a new copy (pk_nds_music_begin again).
//
// Shared by the app (pk_nds.cpp) and the headless tool (native-capture/nds_dump's music).
#pragma once

#include <cstdint>

namespace melonDS { class NDS; }

/** Copies [live]'s state into the music instance (made on first use, kept) and stops its ARM9: from now on
 * pk_nds_music_frame plays on from here. [sampleRate]: its output rate. Between [live]'s frames, on its thread.
 * Returns false if the state didn't load (nothing plays). */
bool pk_nds_music_begin(melonDS::NDS* live, int sampleRate);

/** One more frame (1/60 s) of the music instance: its audio (stereo, interleaved) into [out], at most [maxFrames];
 * returns the frames written (0 without a begin). Same thread as pk_nds_music_begin. */
int pk_nds_music_frame(int16_t* out, int maxFrames);

/** Frames the instance's channels have all stood still while one of them sounds: its sound driver has stopped (0 while
 * it's sequencing, or silent). */
int pk_nds_music_stalled(void);

/** Debug: the instance's ARM7, IPC and shared flag state and its sounding channels, a line into [out]. */
void pk_nds_music_debug(char* out, int len);

/** Fades what it plays out over [frames] frames and stays silent (the game's own fades run on its ARM9, which is stopped
 * here): a song fading out in the game before the next one. A begin plays at full volume again. */
void pk_nds_music_fade(int frames);

/** Stops playing (the instance is kept for the next begin; [release] frees it). */
void pk_nds_music_end(bool release = false);
