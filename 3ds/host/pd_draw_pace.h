#ifndef PD_DRAW_PACE_H
#define PD_DRAW_PACE_H

#include <stdbool.h>
#include <stdint.h>

// An Old 3DS's renderer on core 1 can take longer than a refresh for one picture (gpSP's render times in the players'
// test logs, 2026-11-18). Waiting for it at every frame's end held the game back with it: a third of the frames came
// late and were dropped behind. Planned instead from the renderer's own time, a picture is started on one frame and
// collected a frame or more later, the frames between run undrawn, so the game and its sound keep their speed and the
// picture comes at an even rhythm. Pure C, no clock: the caller says how long the last picture took.
enum pd_draw_plan {
    PD_DRAW_NOW,       // draw this frame and wait for it, as without a plan
    PD_DRAW_LATE,      // draw this frame, collect it after the frames that follow (show the last picture meanwhile)
    PD_DRAW_SKIP,      // run this frame undrawn, the picture started before still on its way
    PD_DRAW_SKIP_LAST, // run this frame undrawn, then collect the picture started before and show it
};

#define PD_DRAW_MOST 4    // at most one picture in four frames: past that a screen is a slideshow anyway
#define PD_DRAW_CALM 40   // pictures in a row with room to spare before the rhythm speeds up again

struct pd_draw_pace {
    uint8_t every; // frames per picture (0 = not started: 1)
    uint8_t left;  // undrawn frames still to run before the next picture
    uint8_t needs[8], at;
    uint16_t calm;
};

// Frames one picture needs when the renderer may use `percent` of each refresh.
static inline unsigned pd_draw_frames_for(unsigned renderUs, unsigned refreshUs, unsigned percent) {
    unsigned room = refreshUs * percent / 100;
    if (!room) return 1;
    unsigned n = (renderUs + room - 1) / room;
    return n < 1 ? 1 : n > PD_DRAW_MOST ? PD_DRAW_MOST : n;
}

// The plan for the coming frame. renderUs: how long the renderer took for its last picture. Only the renderer's own time
// counts, not how long the game waited for a picture at a frame's end (the renderer follows the game's lines, so a
// picture that fits a refresh can still come a few ms late): planned from that wait too, a stand-in Old 3DS kept FireRed
// at 60 frames a second but showed 18 new pictures a second instead of 27, the catching up undrawing frames on top.
static inline enum pd_draw_plan pd_draw_pace_next(struct pd_draw_pace* p, unsigned renderUs, unsigned refreshUs) {
    if (!p->every) p->every = 1;
    if (p->left) {
        p->left--;
        return p->left ? PD_DRAW_SKIP : PD_DRAW_SKIP_LAST;
    }
    // Slower: as soon as one of the last eight pictures needed more frames than the rhythm gives (with a tenth of a
    // refresh kept for handing the picture over). Faster: only after PD_DRAW_CALM pictures that would all have fit in
    // three quarters of the quicker rhythm, so it doesn't flip between two rhythms on a screen right at the edge.
    p->needs[p->at++ & 7] = (uint8_t) pd_draw_frames_for(renderUs, refreshUs, 90);
    unsigned most = 1;
    for (int i = 0; i < 8; i++)
        if (p->needs[i] > most) most = p->needs[i];
    if (most > p->every) {
        p->every = (uint8_t) most;
        p->calm = 0;
    } else if (p->every > 1 && most < p->every && pd_draw_frames_for(renderUs, refreshUs, 75) < p->every) {
        if (++p->calm >= PD_DRAW_CALM) {
            p->every--;
            p->calm = 0;
            for (int i = 0; i < 8; i++) p->needs[i] = 0;
        }
    } else {
        p->calm = 0;
    }
    p->left = (uint8_t) (p->every - 1);
    return p->left ? PD_DRAW_LATE : PD_DRAW_NOW;
}

// Back to every frame drawn (a scene where the plan can't apply: EXPAND, FREE FLIGHT, fast-forward).
static inline void pd_draw_pace_reset(struct pd_draw_pace* p) {
    p->every = 1;
    p->left = 0;
    p->calm = 0;
    for (int i = 0; i < 8; i++) p->needs[i] = 0;
}

// The picture on screen stays as it is this loop: one is on its way and not collected yet.
static inline bool pd_draw_plan_holds(enum pd_draw_plan plan) {
    return plan == PD_DRAW_LATE || plan == PD_DRAW_SKIP;
}

#endif
