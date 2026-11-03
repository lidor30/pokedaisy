#pragma once

#include <stdbool.h>
#include <stdint.h>

#include <mgba/core/core.h>
#include <mgba/core/interface.h>

/** A Game Boy core's sound, resampled here instead of by the blip_buf hookup in mGBA 0.10.5's GB core: see
 * pk_gb_audio.c. Zeroed = off (a GBA core, or no core). */
struct pk_gb_audio {
    struct mAVStream stream;            // first: the core's callbacks get this pointer back
    struct blip_t* left;
    struct blip_t* right;
    int16_t lastLeft, lastRight;
    unsigned clock;                     // samples in since the last blip frame ended
    unsigned dropped;                   // samples let go because nothing read them (a full buffer)
};

/** Takes [core]'s samples from now on, resampled to [rate] a second; false (and nothing done) for a core that isn't
 * the Game Boy's. */
bool pk_gb_audio_attach(struct pk_gb_audio* a, struct mCore* core, double rate);
/** Gives [core] its own audio back and frees the buffers ([core] may be NULL once it's gone). */
void pk_gb_audio_detach(struct pk_gb_audio* a, struct mCore* core);
/** Samples out per emulated second (the vsync pacing's rate control moves it a little). */
void pk_gb_audio_set_rate(struct pk_gb_audio* a, double rate);
/** Up to [max] stereo frames into [out] (interleaved); how many. */
int pk_gb_audio_read(struct pk_gb_audio* a, int16_t* out, int max);
