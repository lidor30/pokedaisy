// The dev build's Wi-Fi link (PD_DEV only, never in a player's build): tools/pdlink.py pushes a new build, moves
// files in the dev folder, presses buttons, taps and takes screenshots from a computer. See README.md.
#ifndef DEVLINK_H
#define DEVLINK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef PD_DEV

#define DEVLINK_PORT 17498
#define DEVLINK_BEACON_PORT 17499
#define DEVLINK_SELF "/3ds/pokedaisy-dev.3dsx"

// What the main thread reports each second, for `stat`.
struct devlink_stats {
    unsigned frames;      // game frames run in the last second
    unsigned coreUs;      // of that second: running the core
    unsigned uiUs;        // the snapshot and the companion's drawing
    unsigned drawUs;      // gpu_draw, including its wait for the refresh
    unsigned worstUs;     // the longest whole loop
    unsigned late;        // loops over a refresh (16.7 ms)
    unsigned runUs, waitUs, wideUs, snapUs; // of coreUs: the game itself, waiting for its renderer, EXPAND (with that wait), the snapshot
    unsigned gaps, drops, rate;             // sound: the queue ran dry, a chunk had no room, the channel's rate x 1000
    unsigned redraws, redrawWorstUs;        // the companion's redraws and the slowest one
    int idleMode;                           // mGBA's busy-loop skipping: -1 off for good, 0 skipping, 1 still looking
    unsigned idleLoop, idleFails;           // the loop it skips, and its failed tries
    int flightState;                        // FREE FLIGHT's pk_flight_state (0 on the ground)
    unsigned flightTickUs, flightDrawUs;    // of coreUs: its step after each frame, its shadow and bird drawn in
    // armGBA's own counters (of runUs: waiting for its render worker, compiling, cache flushes; the worker's drawing on
    // core 2), and the fourth core's: EXPAND's margins and the screens' draw jobs.
    unsigned agWaitUs, agRenderUs, agCompileUs, agFlushUs, wideThreadUs, drawJobUs;
    unsigned agStepped, agCompiled, agInvalidated, agCodeWrites;   // armGBA's recompiler, in the last second
    unsigned mixes;   // the native m4a mixer's, in the last second
    // Since the game started (read twice, a quiet stretch apart): loops, late loops by what ran over (the core over 12 ms,
    // the draw over 10 ms, else the rest of the loop), the sound queue running dry, its fewest chunks queued.
    unsigned totLoops, totLate, totLateCore, totLateDraw, totLateRest, totGaps, totFrames, queueMin;
    unsigned jobTexUs, jobBeginUs, jobCanvasUs, jobRestUs;   // the draw jobs in the last second (gpu.c)
};

void devlink_start(void);
void devlink_stop(void);
// This loop's injected keys (hidKeysHeld's bits), to OR in.
uint32_t devlink_keys(void);
// A touch to act on: true with its position while one is held.
bool devlink_touch(int* x, int* y);
// A game to open from the list ("" none): taken once.
bool devlink_take_launch(char* out, unsigned outLen);
// True when the running game should be left (`leave`).
bool devlink_take_leave(void);
// A wild battle to start (`wild`): taken once.
bool devlink_take_wild(int* species, int* level);
// `teach SLOT INDEX MOVE`: a party Pokemon's move set by the game's SetMonData, between frames.
bool devlink_take_teach(int* slot, int* index, int* move);
// SCREEN and ASPECT to set while playing (`opt`; -1 leaves one alone): taken once.
bool devlink_take_opt(int* screen, int* aspect);
// True when a new build waits: leave everything and run it.
bool devlink_restart_wanted(void);
// After the frame is on screen: serves a waiting screenshot.
void devlink_after_frame(void);
void devlink_report(const struct devlink_stats* s);
// A line for the `log` command.
void devlink_log(const char* format, ...) __attribute__((format(printf, 1, 2)));
// Asks the Homebrew Launcher's loader for this build next (Rosalina's hb:ldr). False if it can't.
bool devlink_chainload(void);
// This console's address, for the splash ("" before Wi-Fi is up).
const char* devlink_address(void);

#else

static inline void devlink_start(void) {}
static inline void devlink_stop(void) {}
static inline uint32_t devlink_keys(void) { return 0; }
static inline bool devlink_touch(int* x, int* y) { (void) x; (void) y; return false; }
static inline bool devlink_take_launch(char* out, unsigned outLen) { (void) out; (void) outLen; return false; }
static inline bool devlink_take_leave(void) { return false; }
static inline bool devlink_take_wild(int* species, int* level) { (void) species; (void) level; return false; }
static inline bool devlink_take_teach(int* slot, int* index, int* move) { (void) slot; (void) index; (void) move; return false; }
static inline bool devlink_take_opt(int* screen, int* aspect) { (void) screen; (void) aspect; return false; }
static inline bool devlink_restart_wanted(void) { return false; }
static inline void devlink_after_frame(void) {}

#endif
#endif
