// Game Boy sound without mGBA 0.10.5's own resampling (GitHub #269 / #327: "crackles", worst in Crystal).
//
// mGBA's GB core mixes the four channels into a sample every 32 clocks (131072 a second) and resamples them to the
// output rate through a blip_buf of its own (gb/audio.c, _sample). That 48 kHz output carried sudden drops in level of
// up to 25,000 that crept back over ~10 ms - about 4 a second in Crystal, 2.4 in Yellow, on the Thor and headless
// alike - which the core's own samples don't have: recorded before that step, they pop as rarely as newer mGBA's
// (which dropped the hookup), Gambatte's or SameBoy's (0 - 0.5 a second, the same scenes, at the same loudness). The
// core hands every one of those samples to its stream's postAudioFrame as well, so they're resampled here instead,
// through a blip_buf fed one sample a clock at 131072 Hz.
#include "pk_gb_audio.h"

#include <string.h>

#include <mgba/core/blip_buf.h>

#define PK_GB_AUDIO_RATE 131072         // DMG_SM83_FREQUENCY / 32: the GB core's audioRateChanged
#define PK_GB_AUDIO_BUFFER 0x4000       // blip_buf's room, in output samples
#define PK_GB_AUDIO_FRAME 4096          // clocks before a blip frame is ended without a read (~1,500 samples out)

static void pk_gb_audio_frame(struct mAVStream* stream, int16_t left, int16_t right) {
    struct pk_gb_audio* a = (struct pk_gb_audio*) stream;
    if (a->clock >= PK_GB_AUDIO_FRAME) {
        // Nobody read for a while (the render core between songs, say): end the frame; past half the buffer let the
        // samples go, as mGBA's own buffer does when it's full, keeping the level where it was.
        if (blip_samples_avail(a->left) > PK_GB_AUDIO_BUFFER / 2) {
            ++a->dropped;
            return;
        }
        blip_end_frame(a->left, a->clock);
        blip_end_frame(a->right, a->clock);
        a->clock = 0;
    }
    blip_add_delta(a->left, a->clock, left - a->lastLeft);
    blip_add_delta(a->right, a->clock, right - a->lastRight);
    a->lastLeft = left;
    a->lastRight = right;
    ++a->clock;
}

bool pk_gb_audio_attach(struct pk_gb_audio* a, struct mCore* core, double rate) {
    memset(a, 0, sizeof(*a));
    if (!core || core->platform(core) != mPLATFORM_GB) {
        return false;
    }
    a->left = blip_new(PK_GB_AUDIO_BUFFER);
    a->right = blip_new(PK_GB_AUDIO_BUFFER);
    if (!a->left || !a->right) {
        pk_gb_audio_detach(a, NULL);
        return false;
    }
    pk_gb_audio_set_rate(a, rate);
    a->stream.postAudioFrame = pk_gb_audio_frame;
    core->setAVStream(core, &a->stream);
    return true;
}

void pk_gb_audio_detach(struct pk_gb_audio* a, struct mCore* core) {
    if (core && a->left) {
        core->setAVStream(core, NULL);
    }
    if (a->left) blip_delete(a->left);
    if (a->right) blip_delete(a->right);
    memset(a, 0, sizeof(*a));
}

void pk_gb_audio_set_rate(struct pk_gb_audio* a, double rate) {
    if (!a->left || rate <= 0) {
        return;
    }
    blip_set_rates(a->left, PK_GB_AUDIO_RATE, rate);
    blip_set_rates(a->right, PK_GB_AUDIO_RATE, rate);
}

int pk_gb_audio_read(struct pk_gb_audio* a, int16_t* out, int max) {
    if (!a->left) {
        return 0;
    }
    // Everything the frame made is readable at once: end the blip frame where the samples stopped.
    if (a->clock) {
        blip_end_frame(a->left, a->clock);
        blip_end_frame(a->right, a->clock);
        a->clock = 0;
    }
    int n = blip_samples_avail(a->left);
    if (n > max) {
        n = max;
    }
    if (n > 0) {
        blip_read_samples(a->left, out, n, 1);
        blip_read_samples(a->right, out + 1, n, 1);
    }
    return n;
}
