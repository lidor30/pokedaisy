// The companion on the 3DS's 320x240 bottom screen: PARTY (with a summary on
// tap), BAG, MAP, GUIDE, POKéDEX, BATTLE (cards + your moves' verdicts) and
// SETTINGS, in the app's
// FireRed OPTION-screen look (app/.../companion/ui/GbaMenu.kt). Immediate
// mode: every pd_ui_draw records where its tappable parts went, and
// pd_ui_touch looks a tap up there - no layout pass to keep in sync.
//
// The host owns the emulator: it shows what pd_ui_draw drew, feeds the stylus
// and buttons in, and carries out what pd_ui_take_action hands back (fast-
// forward, save states, leaving the game, saving changed settings).
#ifndef PD_UI_H
#define PD_UI_H

#include <stdbool.h>

#include "pd_canvas.h"
#include "pd_game.h"
#include "pd_snapshot.h"

#define PD_UI_WIDTH 320
#define PD_UI_HEIGHT 240

enum pd_tab {
    PD_TAB_PARTY,
    PD_TAB_BAG,
    PD_TAB_MAP,
    PD_TAB_GUIDE,
    PD_TAB_DEX,
    PD_TAB_BATTLE,
    PD_TAB_SETTINGS,
    PD_TAB_CARD, // the TRAINER CARD: no chip, it opens from SETTINGS
    PD_TAB_COUNT,
};

// How the game fills the top screen.
enum pd_screen_mode {
    PD_SCREEN_PIXEL,   // 1x, pixel for pixel
    PD_SCREEN_SHARP,   // 1.5x (fills the height), sharp bilinear
    PD_SCREEN_STRETCH, // the whole 400x240, sharp bilinear
    PD_SCREEN_MODES,
};

// How the game fills a host's screen, where the host can draw past the game's
// edges (pd_host_info.hasExpand; the Linux host: the app's ASPECT > EXPAND).
enum pd_aspect {
    PD_ASPECT_ORIGINAL, // the game's own picture, centred
    PD_ASPECT_EXPAND,   // the overworld rebuilt to the screen's edges, same scale
    PD_ASPECT_ZOOM_OUT, // EXPAND one whole scale smaller: more of the map
    PD_ASPECTS,
};

#define PD_FF_MIN 2
#define PD_FF_MAX 4
// plorpOS: the fast-forward speeds offered (the app's RATES less 1.5x), 0 = MAX: as many frames as a
// screen refresh has time for.
#define PD_FF_CHOICES 8
extern const int PD_FF_SPEEDS[PD_FF_CHOICES];
int pd_ff_index(int speed);

// plorpOS: how ORIGINAL's picture fills the screen - whole pixels, the largest 3:2, or all of it.
enum pd_scaling { PD_SCALE_INTEGER, PD_SCALE_FIT, PD_SCALE_STRETCH, PD_SCALINGS };

// What the player set; the host loads and saves it (pd_settings.h).
struct pd_settings {
    int screenMode; // enum pd_screen_mode
    int ffSpeed;    // PD_FF_MIN..PD_FF_MAX
    int guideNotice; // a bit per game kind whose GUIDE notice was accepted
    int aspect;      // enum pd_aspect (hosts with hasExpand only)
    bool autoZoom;   // AUTO ZOOM IN: ZOOM OUT steps back in off the plain overworld
    bool wildOff;    // WILD ENCOUNTERS off (hosts that can: the Linux host's quick menu)
    int tabBar;      // TAB BAR: a bit per enum pd_tab on the bar (PARTY .. DEX, CARD); the rest open from MORE
    bool hud;        // HUD over the overworld (hosts with hasHud) ...
    int hudHidden;   // ... less these parts: a bit per enum pd_hud_part
    int hudLayout;   // enum pd_hud_layout (the app's FieldHudLayout)
    int hudLook;     // enum pd_hud_look (FieldHudLook)
    int hudSize;     // enum pd_hud_size (FieldHudSize)
    // plorpOS (its in-game menu writes these too):
    int scaling;     // enum pd_scaling, ASPECT ORIGINAL's picture
    bool ffAudio;    // the game's sound sped up under fast-forward, else silent
    bool rewind;     // REWIND: the last ~20 s kept for the rewind hotkey
    bool runIndoors; // RUN INDOORS: every map lets the player run
    bool lcdFilter;  // optional host LCD display mask
    bool quickGrid;  // hosts with hasQuickMenu: false = RING, true = GRID
    // Hosts with hasButtonRows: the pad buttons for the quick menu, the companion and the HUD cursor (enum pd_button).
    int quickBtn, companionBtn, hudBtn;
};

enum pd_button { PD_BTN_X, PD_BTN_Y, PD_BTN_L2, PD_BTN_R2, PD_BTN_NONE, PD_BUTTONS };

// TAB BAR: at most this many tabs beside the gear (the app's MAX_BAR_TABS).
#define PD_MAX_BAR_TABS 5
#define PD_TAB_BAR_DEFAULT (1 << PD_TAB_PARTY | 1 << PD_TAB_BAG | 1 << PD_TAB_MAP | 1 << PD_TAB_GUIDE | 1 << PD_TAB_DEX)

// The HUD's parts (the app's FieldHudPart, in its order) and SETTINGS > HUD's LAYOUT / LOOK / SIZE
// (FieldHudSettings.kt). The buttons (PC, BAG, WILD) are reached with the HUD hotkey on a host without touch.
enum pd_hud_part { PD_HUD_PARTY, PD_HUD_MAP, PD_HUD_BALLS, PD_HUD_REPEL, PD_HUD_PC, PD_HUD_BAG, PD_HUD_WILD, PD_HUD_PARTS };
enum pd_hud_layout { PD_HUD_CORNERS, PD_HUD_CORNERS_2, PD_HUD_RAILS, PD_HUD_DOCK, PD_HUD_LAYOUTS };
enum pd_hud_look { PD_HUD_GLASS, PD_HUD_WHITE_GLASS, PD_HUD_WINDOW, PD_HUD_LOOKS };
enum pd_hud_size { PD_HUD_SMALL, PD_HUD_NORMAL, PD_HUD_LARGE, PD_HUD_SIZES };
const char* pd_hud_part_name(int part);
const char* pd_hud_layout_name(int layout);
const char* pd_hud_look_name(int look);
const char* pd_hud_size_name(int size);

enum pd_action {
    PD_ACTION_NONE,
    PD_ACTION_TOGGLE_FF,
    PD_ACTION_SAVE_STATE,
    PD_ACTION_LOAD_STATE,
    PD_ACTION_LEAVE_GAME,
    PD_ACTION_SETTINGS_CHANGED,
};

// What the host tells the companion about itself.
#define PD_UI_HOST_LINES 2
struct pd_host_info {
    const char* labels[PD_UI_HOST_LINES]; // extra SETTINGS info rows (FILE, SPEED ...)
    char values[PD_UI_HOST_LINES][40];
    int count;
    bool ffOn;
    bool hideScreenMode; // Optional: host owns display scaling. Zero keeps the 3DS row.
    bool hasExpand;      // Optional: the host can EXPAND (ASPECT / AUTO ZOOM IN rows) ...
    bool expandGame;     // ... and does for this game (else ASPECT reads N/A)
    bool hasHud;         // Optional: the host draws the HUD over the game (its SETTINGS row)
    bool hasDisplayFilter; // host implements OFF / LCD
    bool hasQuickMenu;   // host implements the selectable RING / GRID quick menu
    bool hasButtonRows;  // Optional: QUICK MENU / COMPANION / HUD CURSOR button rows (the host reads settings.*Btn)
    bool frontendOwnsPlayback; // Optional: the frontend runs fast-forward, save states and leaving the game: no such rows
    bool hasState;      // a save state exists for LOAD STATE
    char stateWhen[24]; // when it was made, shown beside LOAD STATE
};

#define PD_UI_MAX_HITS 64
struct pd_hit {
    int x, y, w, h;
    int id;
    bool scroll; // part of a list that a drag scrolls
};

enum pd_overlay {
    PD_OVERLAY_NONE,
    PD_OVERLAY_PICK_SCREEN,
    PD_OVERLAY_PICK_FF,
    PD_OVERLAY_CONFIRM_SAVE,
    PD_OVERLAY_CONFIRM_LOAD,
    PD_OVERLAY_CONFIRM_LEAVE,
    PD_OVERLAY_PLACES,       // the MAP's list of places
    PD_OVERLAY_GUIDE_NOTICE, // the GUIDE's "AI-written, may be wrong" notice
    PD_OVERLAY_PICK_ASPECT,
    PD_OVERLAY_TAB_BAR, // SETTINGS > TAB BAR: each tab ON / OFF
    PD_OVERLAY_HUD,     // SETTINGS > HUD: the HUD and its parts ON / OFF
    PD_OVERLAY_MORE,    // the tabs off the bar
};

struct pd_ui {
    enum pd_tab tab;
    enum pd_tab tabBeforeBattle; // the tab a battle took over from
    int battleSlot;              // the bar slot BATTLE shows in, -1 = none
    bool battleWasOn;
    // BATTLE: INFO (0) or SUGGESTIONS (1); the FOE TEAM's slots seen so far
    // this battle (a bit each), the one tapped (-1 = follow the battle) and
    // the one shown (set by the draw); what the last read said was out / next.
    int battlePane;
    unsigned foeSeen;
    int foeSelected, foeShown;
    int lastFoeActive, lastFoeNext;
    int summarySlot; // party slot whose summary is open, -1 = none
    bool summaryStats; // STATS replaces MOVES, as in MonDetailScreen
    bool summaryInfo;  // INFO (EXP, held item, type matchups) replaces them
    int bagPocket;   // the open pocket tab: 0 ALL, then each pocket with items, in the game's order
    int bagSelected; // the item's row in that tab, -1 = none
    int bagScroll;   // pixels

    // POKéDEX: the list's scroll, the open entry (national number, 0 = the
    // list), and which dex shows (-1 = the save's own: National once it has
    // it, else the regional one).
    int dexScroll, dexOpen, dexNational;
    // MAP: the page shown (-1 = the player's), the tapped place, the cursor's blink.
    int mapPage, mapSel, mapDungeon, mapSelTx, mapSelTy;
    bool mapBlink;
    unsigned blinkMs;
    int placesScroll;
    // GUIDE: the page, its scroll, the open entry and how far it's revealed.
    int guidePage, guideScroll, guideOpen, guideLevel;
    bool guideNoticeShown;
    // TRAINER CARD: the side shown, a flip running (ms into it, 0 = none) and
    // the play time's blinking colon.
    bool cardBack, cardColon;
    int cardFlipMs;
    unsigned cardColonMs;

    enum pd_overlay overlay;
    bool moreOpened; // the open tab came from MORE (BACK doesn't send the CARD to SETTINGS then)
    int settingsScroll; // SETTINGS' list, in canvas pixels
    struct pd_settings* settings;
    enum pd_action action;

    bool touching, dragging, touchScroll;
    int touchX0, touchY0, touchLastY;
    int pressedId; // what the stylus went down on (-1 = nothing)
    int focusId;   // the hit a host's D-pad focus is on, drawn as that part's own cursor (-1 = none)
    int startId;   // set by each draw: where a D-pad starts on this screen (-1 = its first item)
    struct pd_hit hits[PD_UI_MAX_HITS];
    int hitCount;
};

void pd_ui_init(struct pd_ui* ui, struct pd_settings* settings);
// Follows the game: a battle opens BATTLE, its end goes back.
void pd_ui_update(struct pd_ui* ui, const struct pd_snapshot* s);
void pd_ui_draw(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g,
                const struct pd_snapshot* s, const struct pd_host_info* host);
// Stylus: down / held / up at (x, y). Returns true if the screen needs a redraw.
bool pd_ui_touch(struct pd_ui* ui, int x, int y, bool down);
// Buttons the GBA doesn't have (the 3DS's X / Y): next / previous tab, BACK.
void pd_ui_next_tab(struct pd_ui* ui, int dir);
bool pd_ui_back(struct pd_ui* ui);
// The last thing the player asked the host for (once), or PD_ACTION_NONE.
enum pd_action pd_ui_take_action(struct pd_ui* ui);
// Time passing (milliseconds since the last call): true when something on
// screen animates and needs a redraw (the MAP cursor's blink, the TRAINER
// CARD's colon and flip).
bool pd_ui_tick(struct pd_ui* ui, unsigned ms);

const char* pd_screen_mode_name(int mode);
const char* pd_aspect_name(int aspect);

#endif
