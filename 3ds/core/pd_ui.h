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
#include "pd_games.h"
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
    PD_TAB_CARD,
    PD_TAB_STATES,
    PD_TAB_BOXES,
    PD_TAB_NUZLOCKE,
    PD_TAB_ACHIEVEMENTS,
    PD_TAB_COUNT,
};

// How the game fills the top screen.
enum pd_screen_mode {
    PD_SCREEN_PIXEL,   // 1x, pixel for pixel
    PD_SCREEN_SHARP_125, // 1.25x, sharp bilinear
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
    bool showPerf;   // FPS / CPU in the corner of the game (hosts with hasPerf)
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
    // The 3DS's (the companion's SETTINGS rows on hosts that show them):
    bool hideHints;  // BATTLE HINTS off
    bool oneTapMoves; // ONE-TAP MOVES: the moves on the action menu where FIGHT is
    bool battleGen5;  // BATTLE STYLE: GEN 5 (Black / White's touch screen); off = GEN 4
    bool noBattleInfo;    // BATTLE INFO off: the battle buttons alone, no INFO / SUGGESTIONS or move ratings
    bool battleInfoFirst; // BATTLE VIEW: INFO; off = BUTTONS (what the BATTLE tab opens on)
    bool flyClassic;      // FLY STYLE: CLASSIC (the game's own FLY); off = SCENIC, flown over the field where the game can
    bool flightAnywhere;  // FLIGHT AREA: ANYWHERE; off = VISITED, the app's WHERE YOU'VE BEEN (only the maps this save has seen)
    int flightVehicle;    // FLY ON: linux/flight.h's enum flight_vehicle (0 the game's bird)
    int gameSpeed;   // GAME SPEED: an index into PD_GAME_SPEEDS
    int gameVolume;  // GAME VOLUME: 20 ... 100
    bool gameMuted;  // MUTE GAME
    bool autoRun;    // AUTO-RUN: B held for the player while walking
    bool darkMode;   // DARK MODE
    bool ffNormal;   // FF MODE: NORMAL; off = SMART, 1x while the game waits for the player
    bool cheats;     // CHEATS: the game's file's codes on
    int theme;       // THEME: a pd_themes[] id
    bool libraryGrid;    // the library's VIEW: GRID; off = LIST
    bool libraryRecent;  // the library's SORT: LAST PLAYED; off = NAME
};

enum pd_button { PD_BTN_X, PD_BTN_Y, PD_BTN_L2, PD_BTN_R2, PD_BTN_NONE, PD_BUTTONS };

// GAME SPEED's steps, as the app's SPEED_STEPS: the old five, then the slow two.
#define PD_GAME_SPEED_COUNT 7
extern const char* const PD_GAME_SPEEDS[PD_GAME_SPEED_COUNT];

// TAB BAR: at most this many tabs beside the gear (the app's MAX_BAR_TABS).
#define PD_MAX_BAR_TABS 5
#define PD_TAB_BAR_DEFAULT (1 << PD_TAB_PARTY | 1 << PD_TAB_DEX | 1 << PD_TAB_MAP | 1 << PD_TAB_BAG | 1 << PD_TAB_GUIDE)

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
    PD_ACTION_STATE_SLOT, // STATES picked another slot: ui.actionSlot
    PD_ACTION_RESTART_GAME,
    PD_ACTION_ITEM_PICK, // USE / GIVE: who it can go to (ui.itemId, ui.itemGive) is wanted
    PD_ACTION_ITEM_APPLY, // ... and the pick: ui.itemSlot, ui.itemMove
    PD_ACTION_ITEM_TAKE, // TAKE from ui.summarySlot's Pokémon
    PD_ACTION_UNDO_SAVE,
    PD_ACTION_UNDO_LOAD,
    PD_ACTION_BOX_WITHDRAW, // the PC: ui.boxShown, ui.boxSlot
    PD_ACTION_BOX_SWAP,     // ... with party slot ui.boxPartySlot
    PD_ACTION_BOX_DEPOSIT,  // party slot ui.boxPartySlot into box ui.boxTarget
    PD_ACTION_FLY_ASK,   // MAP's FLY: where it can go is wanted
    PD_ACTION_FLY_TO,    // ... and the pick: ui.flyPick
    PD_ACTION_FLIGHT,    // MAP's TAKE OFF / LAND (FREE FLIGHT)
};

// A party slot in USE / GIVE's pick: dimmed, or which of its moves it would help.
struct pd_item_slot {
    bool ok;
    int heldItem;
    bool moves[4];
};

// STATES: the slots, as the host keeps them. thumb: the game at a quarter of its size (120x80 RGB, 0xRRGGBB), or NULL.
#define PD_STATE_SLOTS 10
#define PD_STATE_THUMB_W 120
#define PD_STATE_THUMB_H 80
struct pd_state_slot {
    bool used;
    char when[24];
    const uint32_t* thumb;
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
    bool hasFfChip;      // Optional: a fast-forward chip on the tab bar (the Linux host's; the 3DS has its own hotkeys)
    bool frontendOwnsPlayback; // Optional: the frontend runs fast-forward, save states and leaving the game: no such rows
    bool hasPerf;        // Optional: the host can show FPS / CPU (its SETTINGS row)
    bool hasFlightArea;  // Optional: TAKE OFF works in this game (FLIGHT AREA's row; the libretro core's free flight)
    bool hasState;      // a save state exists for LOAD STATE
    char stateWhen[24]; // when it was made, shown beside LOAD STATE
    bool fullSettings;  // Optional: SETTINGS' groups and the rows the 3DS has
    bool wildSupported, fieldSupported; // WILD ENCOUNTERS / the field read works on this game
    int batteryPct;     // -1 = unknown
    bool flyReady;      // MAP's FLY button: someone in the party can
    bool flightSupported; // FREE FLIGHT works in this game: FLY STYLE / FLIGHT AREA / FLY ON rows
    bool flightReady, flying; // MAP's TAKE OFF (someone knows FLY), and LAND while up
    bool canTake;       // the summary's TAKE
    bool (*itemCanUse)(int item);  // Optional: what the bag's USE / GIVE can do with an item
    bool (*itemCanGive)(int item);
    bool hasStates;     // Optional: the STATES tab's slots below (the 3DS)
    struct pd_state_slot slots[PD_STATE_SLOTS];
    bool undoSave, undoLoad; // STATES' UNDO has something to put back
    bool hasCheats;          // the game has a .cheats file
    int slotSelected;
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
    PD_OVERLAY_PICK_SPEED,
    PD_OVERLAY_PICK_THEME,
    PD_OVERLAY_PICK_BOX,    // the boxes, BOXES' name or DEPOSIT's where to
    PD_OVERLAY_CONFIRM_BOX, // WITHDRAW / DEPOSIT asked
    PD_OVERLAY_PICK_VOLUME,
    PD_OVERLAY_PICK_FLY_ON, // FLY ON: what FREE FLIGHT flies on
    PD_OVERLAY_CONFIRM_RESTART,
    PD_OVERLAY_FLY,     // MAP's FLY: the towns
};

// Optional presentation data for another emulator; IDs stay native to that backend.
struct pd_party_slot_colors {
    uint32_t outline, light, fill, shade, band, stripe;
};
struct pd_party_source_palette {
    struct pd_party_slot_colors normal, selected, fainted, selectedFainted, empty;
    uint32_t text, shadow, hpFrame, hpInner, hpLabel, hpLabelShade, ball[4];
    bool hideBall, chamfer;
    int emptyAlpha; // 0 means draw the explicit empty slot
    uint32_t emptyLines;
    struct pd_hp_band hpGreen, hpYellow, hpRed, hpEmpty;
    uint32_t male, maleShadow, female, femaleShadow;
};
uint32_t pd_ui_status_color(const char* label);

struct pd_party_source {
    void* ctx;
    bool (*egg)(void*, const struct pd_mon*);
    const char* (*species)(void*, int);
    const char* (*move)(void*, int);
    int (*moveType)(void*, int);
    bool (*icon)(void*, struct pd_canvas*, const struct pd_mon*, int, int, int, bool);
    const char* (*info)(void*, const struct pd_mon*, int);
    const struct pd_party_source_palette* palette;
};

enum pd_summary_page { PD_SUMMARY_MOVES, PD_SUMMARY_STATS, PD_SUMMARY_LEARN };
struct pd_summary_learn_row { int level; const char* name; };
struct pd_summary_learn {
    const void* ctx;
    struct pd_summary_learn_row (*row)(const void*, int index, char* scratch, size_t capacity);
    int count, first;
};
struct pd_summary_move { const char* name; int type, power, pp; };
struct pd_summary_view {
    const struct pd_game* game; // type names and effectiveness; NULL for standard DS types
    void* ctx;
    bool (*icon)(void*, struct pd_canvas*, int slot, int x, int y, bool small);
    const char* name;
    const char* primary;
    const char* secondary;
    bool secondaryLearn, handheldControls, learnPage;
    const char* speciesName;
    const char* heldItem;
    int friendship; // -1 for unavailable
    const struct pd_mon_stats* stats;
    bool dvs, gen1;
    const struct pd_summary_learn* learn;
    int selected, count, level, hp, maxHp, type1, type2;
    uint32_t status, experience, toNext;
    int expPercent; // -1 when only the current EXP is known
    bool egg, hasExperience;
    struct pd_summary_move moves[PD_NUM_MOVES];
};
struct pd_bag_palette;

// Optional read-only bag presentation; item IDs remain backend-owned.
struct pd_bag_source {
    void* ctx;
    int tabs, count;
    const char* (*pocketName)(void*, int);
    bool (*item)(void*, int row, int* pocket, struct pd_item*, bool* quantity);
    const char* (*name)(void*, int);
};
// A list-only dex source: no ROM entry/sprite data is implied by a name.
struct pd_dex_source {
    void* ctx;
    int count, seenCount, caughtCount;
    const char* filterLabel;
    int (*national)(void*, int);
    bool (*seen)(void*, int);
    bool (*caught)(void*, int);
    const char* (*name)(void*, int);
};
// Backend-composited map art in native pixels. Cursor coordinates use those same pixels.
// No game config, ROM offsets or ownership of the supplied buffer is implied.
struct pd_map_source {
    const uint32_t* pixels;
    int width, height;
    const char* name;
    int cursorX, cursorY, cursorW, cursorH;
};
// Host-owned option rows use the established Settings frame and row widgets.
struct pd_option_row { const char* label; const char* value; };
struct pd_option_source { const char* title; const struct pd_option_row* rows; int count, infoCount; };
struct pd_guide_row {
    const char *title, *detail, *hint, *answer;
    bool owned, heading;
};
struct pd_guide_source {
    const char* const* pages;
    int pageCount;
    const struct pd_guide_row* rows;
    int rowCount;
    int noticeBit;
    bool verified;
};
// The handheld Dex Moves pane. Providers keep cartridge ids and decoding local.
struct pd_dex_move_row {
    int number; // level, or the TM / HM number
    bool hm;
    char name[48], type[24];
};
struct pd_dex_moves_source {
    const void* ctx;
    int levels, machines;
    bool hasLevels;
    const char* machinesLabel; // NULL when the cartridge has no compatibility mapping
    void (*row)(const void*, bool machine, int index, struct pd_dex_move_row*);
};
enum pd_dex_section { PD_DEX_INFO, PD_DEX_EVOLVE, PD_DEX_AREA, PD_DEX_MOVES, PD_DEX_SECTIONS };
struct pd_dex_card {
    const char *name, *types, *measurements, *category, *abilities;
    const uint32_t* pixels;
    int pictureSize, pictureScale;
    uint32_t clear;
    int number, row, count;
    bool caught;
    unsigned pages;
    enum pd_dex_section page;
};
struct pd_dex_link_row { char name[64], requirement[80]; };
struct pd_dex_links {
    const void* ctx;
    void (*row)(const void*, int index, struct pd_dex_link_row*);
    int count;
};
struct pd_dex_pane { int x, y, w, h; };
struct pd_ui;
int pd_ui_summary_learn_last(int canvasHeight, int count); // canvas above the tab bar
void pd_ui_summary_page(struct pd_canvas*, const struct pd_summary_view*, enum pd_summary_page);
void pd_ui_summary_footer(struct pd_ui*, struct pd_canvas*, const struct pd_summary_view*, bool stats);
// Draw the four Dex filters and return the x origin of the list beside them.
int pd_ui_dex_filters(struct pd_ui*, struct pd_canvas*, const int counts[4],
                      int active, int focus, const char* region);
struct pd_dex_pane pd_ui_dex_card(struct pd_ui*, struct pd_canvas*, const struct pd_dex_card*);
void pd_ui_dex_links(struct pd_canvas*, struct pd_dex_pane, const struct pd_dex_links*, int selected);
void pd_ui_dex_info(struct pd_canvas*, struct pd_dex_pane, const char* text, const int* stats, bool gen1);
void pd_ui_dex_list_row(struct pd_canvas*, int x, int y, int w, int number, const char* name,
                        bool seen, uint32_t mark, bool selected, const uint32_t* icon, uint32_t clear);
int pd_ui_dex_machine_row(int levels);
void pd_ui_dex_moves(struct pd_canvas*, int x, int y, int w, int h,
                     const struct pd_dex_moves_source*, int selected);

// Battle readers supply their own move IDs and species data.
struct pd_battle_source {
    void* ctx;
    const char* (*species)(void*, int);
    const char* (*move)(void*, int);
    int (*moveType)(void*, int);
    int (*movePower)(void*, int);
    void (*speciesTypes)(void*, int, int*, int*);
    bool (*icon)(void*, struct pd_canvas*, int, int, int, bool);
};

struct pd_ui {
    // Optional backend content inside the shared companion frame and tab bar.
    unsigned availableTabs; // 0 preserves the original complete tab set.
    void* contentCtx;
    void (*content)(void*, struct pd_ui*, struct pd_canvas*);
    const struct pd_option_source* optionSource;
    int optionSelected;
    const struct pd_dex_source* dexSource;
    const struct pd_bag_source* bagSource;
    const struct pd_bag_palette* bagPalette;
    bool keepBackdrop;
    bool bagDetails;
    bool handheldDex, handheldParty;
    bool shoulderTabs; // handheld tab cycle includes More, without D-pad tab focus
    bool hudLayoutFixed; // the host draws one HUD layout: the HUD page's LAYOUT row is N/A (the DS core)
    const struct pd_party_source* partySource;
    int summaryPage;
    bool pixelOff; // SCREEN doesn't offer PIXEL 1x: EXPAND can't hold its speed that wide
    const struct pd_battle_source* battleSource;
    struct pd_binput* battleInput; // the host's, NULL = no touch controls
    const struct pd_snapshot* drawn; // what the last pd_ui_draw showed, for the taps on it
    const struct pd_game* drawnGame;
    bool hostHasStates;              // the host's STATES tab has slots to show
    enum pd_tab tab;
    enum pd_tab tabBeforeBattle; // the tab a battle took over from
    bool battleOnBar;            // BATTLE leads the bar: a battle is on
    bool battleWasOn;
    // BATTLE: INFO (0) or SUGGESTIONS (1); the FOE TEAM's slots seen so far
    // this battle (a bit each), the one tapped (-1 = follow the battle) and
    // the one shown (set by the draw); what the last read said was out / next.
    int battlePane;
    bool battleInfoOpen;     // INFO over the touch controls (their way back shows)
    bool battleChoosingMon;  // POKéMON tapped: our party pane, not yet the game's
    bool battleChoosingMove; // FIGHT tapped: our move grid, FIGHT not sent yet
    // RUN picked: the controls stay up until the battle ends, or the menu is back after a turn (the escape failed).
    bool ranAway, ranWaited;
    unsigned foeSeen;
    int foeSelected, foeShown;
    bool foeDetails; // FOE DETAILS: INFO gives the foe the page (Prefs.foeDetails, kept across battles)
    int lastFoeActive, lastFoeNext;
    int summarySlot; // party slot whose summary is open, -1 = none
    bool summaryLearn, summaryCanLearn;
    int summaryLearnRow, summaryLearnLast;
    bool summaryStats; // STATS replaces MOVES, as in MonDetailScreen
    bool summaryInfo;  // INFO (EXP, held item, type matchups) replaces them
    // BOXES: what the host read (NULL = the tab isn't here), the box asked for, the picked slot and the box summary.
    const struct pd_boxes* boxes;
    bool boxMoves;   // WITHDRAW / DEPOSIT / SWAP work here
    int boxShown, boxSlot, boxSummary, boxPartySlot, boxTarget;
    bool boxSwapPick, boxDepositing;
    char boxMessage[64];
    int bagPocket;   // the open pocket tab (native ID for bagSource, 0 is always ALL)
    int bagTabCount;
    bool bagTabs;    // the D-pad cursor is on the pocket tab bar rather than the list ...
    int bagTabKey;   // ... on this tab: its native pocket ID for a bagSource, else its place (0 is always ALL)
    unsigned bagPocketMask; // bagSource: occupied native pocket IDs plus ALL
    int bagSelected; // the item's row in that tab, -1 = none
    int bagScroll;   // pixels
    // USE / GIVE on the open item: the host's answer, the pick (itemPicking), a move's pick for that slot (-1 = none).
    int itemId, bagItemShown;
    bool itemGive, itemPicking;
    int itemSlot, itemMove, itemMoveSlot;
    struct pd_item_slot itemSlots[PD_PARTY_SIZE];

    // POKéDEX: the list's scroll, the open entry (national number, 0 = the
    // list), and which dex shows (-1 = the save's own: National once it has
    // it, else the regional one).
    int dexScroll, dexOpen, dexNational;
    int bagSort; // the bag's SORT: DEFAULT, NAME, COUNT
    int dexSection, dexPageScroll, dexPageMax; // the entry's INFO / EVOLVE / AREA / MOVES
    int dexReturn; // national number to focus on return, or -1 for the first filtered row
    unsigned dexPages;
    int dexFilter, dexPage, dexDetailRow, dexLevelCount, dexMachineCount, dexLinkCount, dexSpotCount;
    // MAP: the page shown (-1 = the player's), the tapped place, the cursor's blink.
    int mapPage, mapSel, mapDungeon, mapSelTx, mapSelTy;
    bool mapBlink;
    unsigned blinkMs;
    int placesScroll;
    const char* const* flyNames; // FLY's towns, as the host found them
    int flyCount, flyPick;
    // GUIDE: the page, its scroll, the open entry and how far it's revealed.
    int guidePage, guideScroll, guideOpen, guideLevel;
    bool guideController, guideFollow, guideNoticeConfirm, guideVerified;
    int guidePageCount, guidePageFocus, guideRowFocus, guideNoticeBit;
    // TRAINER CARD: the side shown, a flip running (ms into it, 0 = none) and
    // the play time's blinking colon.
    bool cardBack, cardColon;
    int cardFlipMs;
    unsigned cardColonMs;

    enum pd_overlay overlay;
    bool moreOpened; // the open tab came from MORE (BACK doesn't send the CARD to SETTINGS then)
    int settingsNavId, settingsNavCount, settingsNavHeight;
    struct { int id, top; } settingsNav[PD_UI_MAX_HITS];
    int settingsScroll; // SETTINGS' list, in canvas pixels
    struct pd_settings* settings;
    enum pd_action action;
    int actionSlot;

    bool touching, dragging, touchScroll;
    int touchX0, touchY0, touchLastY;
    int pressedId; // what the stylus went down on (-1 = nothing)
    bool barDragging;                 // the stylus is on a list's bar
    int barY, barH, barThumb, barMax; // that bar, as last drawn
    bool keyFocus; // the game's buttons are on the companion: pd_ui_focus_*, L / R keys at the tab bar's ends
    int focusId;   // the hit a host's D-pad focus is on, drawn as that part's own cursor (-1 = none)
    int startId;   // set by each draw: where a D-pad starts on this screen (-1 = its first item)
    struct pd_hit hits[PD_UI_MAX_HITS];
    int hitCount;
};

// Shared modal choice window for backend-owned preferences (selection is host-owned).
void pd_ui_choices_view(struct pd_canvas*, const char*, const char* const*, int, int);

// Shared party body, including its original frames, HP bars, status and summary.
struct pd_map_viewport { int x, y, w, h; };
struct pd_map_viewport pd_ui_map_view(struct pd_canvas*, const struct pd_map_source*);
enum pd_map_input {
    PD_MAP_IDLE, PD_MAP_MOVE, PD_MAP_CENTER, PD_MAP_OPEN_PLACES, PD_MAP_CHOOSE, PD_MAP_CLOSE,
};
// action is -1 on the grid, or the index of the focused button.
enum pd_map_input pd_ui_map_control(int* action, int count, int dx, int dy, bool accept, bool back, bool places);
int pd_ui_list_step(int selected, int count, int step);
bool pd_ui_settings_control(struct pd_ui*, int dy, bool accept);
void pd_ui_format_money(long, char*, size_t);
void pd_ui_options_view(struct pd_ui*,struct pd_canvas*,const struct pd_game*,const struct pd_snapshot*);
void pd_ui_guide_view(struct pd_ui*, struct pd_canvas*, const struct pd_guide_source*);
void pd_ui_guide_reset(struct pd_ui*);
void pd_ui_guide_control(struct pd_ui*, int dx, int dy, bool confirm);
void pd_ui_guide_boss_note(char*, size_t, const char* place, int count, int ace, int best);
void pd_ui_dex_view(struct pd_ui*,struct pd_canvas*,const struct pd_game*,const struct pd_snapshot*,int selected);
void pd_ui_bag_cycle_sort(struct pd_ui*);
// The tab bar's D-pad step: the key after / before `key` in `keys` (the tabs in the order they are drawn), the ends
// stopping the cursor; a key not among them starts from the first.
int pd_ui_bag_tab_step(const int* keys, int count, int key, int dy);
void pd_ui_bag_view(struct pd_ui*, struct pd_canvas*, const struct pd_game*, const struct pd_snapshot*);
void pd_ui_party_view(struct pd_ui*, struct pd_canvas*, const struct pd_game*,
                      const struct pd_snapshot*, int selected);
void pd_ui_init(struct pd_ui* ui, struct pd_settings* settings);
// Follows the game: a battle opens BATTLE, its end goes back.
void pd_ui_update(struct pd_ui* ui, const struct pd_snapshot* s);
void pd_ui_draw(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g,
                const struct pd_snapshot* s, const struct pd_host_info* host);
// Stylus: down / held / up at (x, y). Returns true if the screen needs a redraw.
bool pd_ui_touch(struct pd_ui* ui, int x, int y, bool down);
// Buttons the GBA doesn't have (the 3DS's X / Y): next / previous tab, BACK.
// Controller access to the same TAB BAR / MORE overlays used by touch hosts.
int pd_ui_tab_menu_count(const struct pd_ui*);
void pd_ui_tab_menu_focus(struct pd_ui*, int row);
void pd_ui_tab_menu_choose(struct pd_ui*, int row);
void pd_ui_next_tab(struct pd_ui* ui, int dir);
void pd_ui_party_summary_control(struct pd_ui*, const struct pd_snapshot*, int dx, int dy, bool confirm, bool select);
// The buttons on the companion (keyFocus): a direction (0 up, 1 down, 2 left, 3 right), A on what the cursor's on.
void pd_ui_focus_move(struct pd_ui* ui, int direction);
void pd_ui_focus_press(struct pd_ui* ui);
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
