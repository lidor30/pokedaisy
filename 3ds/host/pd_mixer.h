// The game's own sound mixer (the m4a engine's SoundMainRAM) run natively instead of through the emulator: on a New 3DS it was
// half of every frame, the one thing that kept Unbound under 60 FPS. pd_mixer.c.
#ifndef PD_MIXER_H
#define PD_MIXER_H

#include <stdbool.h>

#include <mgba/core/core.h>

extern unsigned pd_mixer_mixes; // native mixes so far

// Finds the stock mixer in a GBA game's ROM; false (and nothing changes) on a game with another one.
bool pd_mixer_attach(struct mCore* core);
// Every frame: the hook goes back in wherever the game (a reset, a loaded state) has put the mixer back.
void pd_mixer_frame(struct mCore* core);
// The game's own code back in place, so a saved state never holds the hook. The next frame puts it back.
void pd_mixer_release(struct mCore* core);

#ifdef PD_MIXER_CHECK
// Runs both on the same state each time and compares them: how many mixes, how many differed, and which paths ran.
struct pd_mixer_stats {
    unsigned mixes, differed, reverb, fixed, resampled, compressed, reversed, ended;
};
extern struct pd_mixer_stats pd_mixer_stats;
#endif

#endif
