// How the player's mGBA core is set up for speed, shared by the app (pk_session.c) and mgba_dump. GPL-3.0 (see NOTICE).
#ifndef PK_PERF_H
#define PK_PERF_H

#include <mgba/core/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/overrides.h>

// Idle loops: mGBA halts the CPU in a loop that only waits for an interrupt (the game's wait for VBlank) instead of
// running it instruction by instruction. Its default skips only the loops its own table knows (Emerald's); DETECT also
// finds them by watching the code (FireRed's spin on gMain.intrCheck), as mGBA's 3DS front end does. Measured on one of
// the Thor's A510 cores (mgba_dump `bench`, 2026-11-19): FireRed 4.3 -> 2.7 ms a frame; Emerald already had its loop,
// Radical Red waits in the BIOS (already halted). A game whose table entry names a loop keeps it: detection would look
// for another one. After reset (the table is applied there). GBA only.
//
// Tried and left out: mGBA's renderer on its own thread (threadedVideo) - on two A510 cores a frame came 3-9% sooner
// at best (Emerald without EXPAND later), for 0.2-0.3 ms more CPU a frame handing lines between the threads.
static inline void pk_idle_detect(struct mCore* core, int on) {
    if (!core || core->platform(core) != mPLATFORM_GBA) return;
    struct GBA* gba = (struct GBA*) core->board;
    if (gba->idleLoop != IDLE_LOOP_NONE) return;
    gba->idleOptimization = on ? IDLE_LOOP_DETECT : IDLE_LOOP_REMOVE;
}

#endif
