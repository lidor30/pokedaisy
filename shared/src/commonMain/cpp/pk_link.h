#ifndef PK_LINK_H
#define PK_LINK_H

// LINK PLAY: up to four GBA games joined by mGBA's own link cable (gba/sio/lockstep.c), in one process, on one thread
// (pk_link.c). Separate from the player's core in pokedaisy_jni.c: none of the companion's reads, EXPAND or cheats.

#include "pk_link_api.h"
#include <mgba/core/core.h>

/** Optional headless reset hook; application hosts use pk_link_open_with_rtc. */
extern void (*pk_link_after_reset)(struct mCore*);

/** Opens [n] games (2..PK_LINK_MAX) and joins them: [roms] / [saves] in link order, game 0 the cable's parent. A save
 * that can't be written is read only (its progress isn't kept), as the player's own core does. False if one can't. */
bool pk_link_open(int n, const char* const* roms, const char* const* saves, int sampleRate);

struct mCore* pk_link_core(int i);
const color_t* pk_link_video(int i);

#endif
