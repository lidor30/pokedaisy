// A running game, the same on every host that has the companion on a screen of its own: the 3DS (ctr/main.c) and the
// headless simulator (desktop/sim/). The host owns the screens, the sound and the files; this owns the core's frames,
// what the companion reads, what its taps do to the game, the QUICK MENU, the HUD and EXPAND.
#ifndef PD_SESSION_H
#define PD_SESSION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "../../linux/fly.h"
#include "../../linux/flight.h"
#include "../../linux/hud.h"
#include "../../linux/quick_menu.h"
#include "../core/pd_binput.h"
#include "../core/pd_boxes.h"
#include "../core/pd_game.h"
#include "../core/pd_snapshot.h"
#include "../core/pd_ui.h"
#include "pd_items.h"

struct mCore;

// How often the companion reads the game (frames).
#define PD_SNAPSHOT_EVERY 10
// The top screen, where the overlay (QUICK MENU, HUD, notices) and EXPAND's window go.
#define PD_TOP_W 400
#define PD_TOP_H 240
#define PD_GAME_STRIDE 240 // a GBA frame's own width, so EXPAND reads it without a copy

// A player's buttons this loop: the GBA's (PD_KEY_*) and what the host maps to the app's own.
struct pd_input {
    uint32_t held, down;
    bool quickMenu;  // QUICK MENU: pressed this loop
    bool ffHeld;     // FF HOLD
    int stickAngle;  // the left stick aims the ring: degrees clockwise from up, -1 = centred
};

// What the host does for the session.
struct pd_session_host {
    // STATES' slots: the file's path for mGBA's VFile and for stdio (they differ on the 3DS).
    void (*statePath)(void* ctx, int slot, char* vfPath, size_t vfLen, char* stdPath, size_t stdLen);
    // The save was about to change under a load: copied aside first.
    void (*backupSave)(void* ctx);
    void (*settingsChanged)(void* ctx);
    // The game's .cheats file (mGBA's format), by the ROM's CRC32; NULL = none here.
    void (*cheatPath)(void* ctx, uint32_t crc, char* path, size_t len);
    void* ctx;
    // EXPAND's drawing on a thread of its own: pd_session_wide_finish starts there, and join waits for it. NULL: drawn in place.
    void (*wideGo)(void);
    void (*wideJoin)(void);
    // The dev build's clock, for where a frame goes (pd_session_prof); NULL: not counted.
    uint64_t (*clock)(void);
    // FLIGHT AREA's file for one save of a ROM (by its CRC32 and flight_save_key); NULL = kept for this run only.
    void (*flightAreaPath)(void* ctx, uint32_t crc, uint32_t key, char* path, size_t len);
};

// Clock ticks spent since the host last zeroed it: in the core, waiting for its renderer, in EXPAND, reading the snapshot,
// FREE FLIGHT's step and its drawing.
struct pd_session_prof {
    uint64_t run, wait, wide, snap, flightTick, flightDraw;
};

enum pd_job { PD_JOB_NONE, PD_JOB_GB_TAKEN, PD_JOB_PC, PD_JOB_REPEL, PD_JOB_BIKE, PD_JOB_BIKE_WAIT, PD_JOB_BIKE_RESTORE };

// The window's rectangle the frame fills (w 0: none): the game's pixels from (gameX, gameY) go in at (x, y).
struct pd_wide_hole {
    int x, y, w, h, gameX, gameY;
};

struct pd_session {
    struct mCore* core;
    struct pd_game game;
    struct pd_ui ui;
    struct pd_binput input;
    struct pd_snapshot snap, shown;
    struct pd_host_info host;
    struct pd_settings* settings;
    struct pd_session_host files;
    struct pd_session_prof prof;

    uint8_t* ewram;
    size_t ewramSize;
    uint8_t* iwram;
    size_t iwramSize;
    const uint8_t* rom;
    size_t romSize;

    const uint32_t* video; // the core's frame, PD_GAME_STRIDE wide
    unsigned frame;
    bool redraw;
    bool ffToggled, ff;
    bool leave;
    bool wasBusy;
    int speedDebt; // quarters of a frame owed at GAME SPEED

    // The field read (pk_field_state) and EXPAND.
    uint32_t romCrc;
    bool fieldKnown, wideKnown;
    uint32_t wildSwitch;
    int fieldFlags;
    bool menuLatched;     // the last scene looked at was a menu screen (pd_session.c's menu_screen)
    bool wideReady;       // this frame shows EXPAND's window
    struct pd_wide_hole wideHole;
    const uint32_t* nextVideo;
    int perfRight;        // FPS / CPU's right edge: the picture's, left of the HUD's minimap
    bool framePending;    // the core's run but the frame hasn't been waited for
    int wideW, wideH;     // ... this big, in game pixels
    int hudFieldFrames;
    int stripeIn; // the focus strip's slide, in frames
    struct hud_state hud;

    // The QUICK MENU.
    bool quickOpen;
    uint32_t quickHeld; // buttons pressed in the menu, kept from the game until let go (the app's menuHeld)
    int quickSel, quickCount;
    struct quick_item quick[QUICK_COUNT];
    char repelWhy[32];
    bool flyList, repelList;
    int flySel, repelSel;
    int flyAsk, flyStage, flyWait, flyMapsec;
    bool flyFromMap; // the MAP's FLY asked: its list is the companion's
    uint32_t flyCallback;
    struct fly_targets flyTargets;
    const char* flyNames[FLY_MAX_TOWNS];
    int repelKinds[3], repelKindCount;
    char repelRows[3][48];
    const char* repelNames[3];
    const struct pd_config* flyCfg;
    const struct pd_config* flashCfg; // FLASH from the quick menu (linux/flash.c), where the ROM has its code
    int flashAsk;                     // 1+ = FLASH picked, run between two frames
    const struct pd_config* tmCfg;    // USE on a TM / HM (linux/tm_teach.c), where the ROM has its code
    int tmStage, tmWait, tmItem;      // 1 = the fade's on its way to the game's TM screen
    uint32_t tmCallback;
    bool itemsOk, pcOk, bikeOk;

    // FREE FLIGHT, FLIGHT AREA and the scenic FLY (linux/flight.c over the app's pk_flight.c): NULL where the game can't.
    const struct pd_config* flightCfg;
    int flightAsk;           // 1+ = TAKE OFF / LAND picked, run between two frames
    int flightState;         // pk_flight_state() after the last frame
    int flightVehicle;       // FLY ON the words were last given with
    bool flightDrawn;        // the flight's shadow went into the last frame shown
    bool scenic;             // the flight up there is the scenic FLY's (the trip runs faster)
    int scenicAsk;           // 1+ = FLY STYLE SCENIC to scenicMapsec, routed between two frames
    int scenicMapsec;
    bool flightAreaAnywhere; // pk_flight was told ANYWHERE
    int flightAreaTold;      // the frame FLIGHT AREA's edge was last explained
    struct flight_area flightArea;

    // USE / GIVE / TAKE, each run between frames.
    int itemAsk;   // 1 = pick wanted, 2 = apply, 3 = take
    struct pd_party_targets itemTargets;
    enum pd_job job;
    int jobItem, jobFrames, jobTries;
    bool bikeWasRiding, bikeTook;
    uint16_t bikeOld;
    uint32_t gameKeys;
    int gameKeyFrames;

    // The Game Boy games' PC / FLY / WILD ENCOUNTERS: NULL where the cart isn't known.
    const struct gb_field* gbField; // linux/gb_field.c's, for a Game Boy cart
    bool gbWildHeld;

    // CHEATS: the file's text.
    char* cheatText;
    size_t cheatLen;
    bool cheatsOn;

    // BOXES.
    struct pd_boxes boxes;
    int boxAsk; // the PC's move asked: enum pd_action

    // What the overlay shows.
    char notice[64];
    int noticeFrames;
    uint32_t overlayKey;
    bool overlayShown, overlayDirty, overlayDim;

    // STATES.
    int undoSaveSlot; // the slot a SAVE replaced, -1 = none
    uint32_t thumbs[PD_STATE_SLOTS][PD_STATE_THUMB_W * PD_STATE_THUMB_H];
};

// The core has its ROM and save and has been reset, [video] is the buffer it draws in. Sets up what the companion
// reads, scans the ROM's art.
void pd_session_start(struct pd_session* s, struct mCore* core, const uint32_t* video, struct pd_settings* settings,
                      const struct pd_session_host* files);
// One loop: the QUICK MENU, the frames (several in fast-forward), the jobs between them, the overlay.
void pd_session_tick(struct pd_session* s, const struct pd_input* in, unsigned ms);
// One emulated frame with the player's keys held: the battle's touch controls may take the keys over.
void pd_session_run_frame(struct pd_session* s, uint32_t playerKeys, bool last);
// The next frame is drawn in this buffer (the host hands out two in turn); s->video stays the last whole frame until then.
void pd_session_set_video(struct pd_session* s, const uint32_t* video);
// What pd_ui_take_action handed over: true if the session has dealt with it (else it's the host's: CLOSE GAME ...).
bool pd_session_action(struct pd_session* s, enum pd_action action);
// X on the field: the game's buttons go to the companion (Android's bottom focus), a silver strip on the game's picture
// pointing down at it. Leaving the field brings them back by itself.
bool pd_session_can_focus(const struct pd_session* s);
void pd_session_set_focus(struct pd_session* s, bool on);
// Draws the companion on the bottom canvas.
void pd_session_draw(struct pd_session* s, struct pd_canvas* bottom);
// A Game Boy / Color game (160x144): its picture is pillarboxed in white, never stretched.
bool pd_session_game_boy(const struct pd_session* s);
// The last frame stays on screen (the battle's switch drives the game's party menu behind it).
bool pd_session_holds_frame(const struct pd_session* s);
// The overlay's canvas (PD_TOP_W x PD_TOP_H, alpha in the top byte); redrawn when overlayDirty.
const uint32_t* pd_session_overlay(struct pd_session* s);
// The size the game is drawn at now (enum pd_screen_mode): SCREEN's, but never PIXEL 1x in EXPAND, and 1.5x in a battle.
int pd_session_screen(const struct pd_session* s);
// FPS / CPU's picture, *w x PD_PERF_H pixels in a PD_PERF_W-wide buffer (alpha in the top byte, 0 = see-through).
#define PD_PERF_W 128
#define PD_PERF_H 16
const uint32_t* pd_session_perf(unsigned fpsTenths, unsigned cpuPercent, int* w);
// EXPAND's window, s->wideW x s->wideH pixels, when s->wideReady.
const uint32_t* pd_session_wide(const struct pd_session* s);
// EXPAND's deferred drawing (pk_wide_finish) and then FREE FLIGHT's clouds over its margins: the host's wideGo thread runs
// this (it's called in place without one).
void pd_session_wide_finish(void);
// STATES: the slot's file and picture; the save state of the session's game.
bool pd_session_save_state(struct pd_session* s, int slot);
bool pd_session_load_state(struct pd_session* s, int slot);
bool pd_session_undo_save(struct pd_session* s);
bool pd_session_undo_load(struct pd_session* s);
void pd_session_show(struct pd_session* s, const char* text);

bool pd_session_read(void* ctx, uint32_t addr, void* out, size_t len);
bool pd_session_write(void* ctx, uint32_t addr, const void* data, size_t len);

#endif
