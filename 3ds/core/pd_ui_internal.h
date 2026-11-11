// Shared by the companion's tab files (pd_ui_*.c): layout, hit ids and the
// small widgets every tab draws with.
#ifndef PD_UI_INTERNAL_H
#define PD_UI_INTERNAL_H

#include <stddef.h>

#include "pd_icons_gen.h"
#include "pd_style.h"
#include "pd_ui.h"

// Where a tab's content starts: the tab bar is along the bottom, like the app's
// (pd_ui_draw hands the tabs the canvas above it).
#define CONTENT_Y MARGIN
#define BAR_Y(c) ((c)->h - 30) // the tab bar: 28 tall, 2 above the edge
#define MARGIN 4
#define ROW_H 18

// Hit ids.
// The game pd_ui_draw is drawing (its names and types for the widgets that aren't handed it).
extern const struct pd_game* ui_game;

enum {
    HIT_TAB = 100,         // + tab
    HIT_FF = 110,
    HIT_SCROLL_BAR = 113,  // a list's bar: dragged, not tapped
    HIT_SHOULDER_L = 114,  // the L / R keys at the tab bar's ends while the buttons are the companion's
    HIT_SHOULDER_R = 115,
    HIT_MORE = 130,        // the tab bar's MORE chip
    HIT_MORE_TAB_BAR = 112, // MORE's TAB BAR button
    HIT_SLOT = 200,        // + party slot
    HIT_SUMMARY_MON = 210, // + party slot: the summary's column
    HIT_SUMMARY_TAKE = 221,
    HIT_BACK = 300,
    HIT_SUMMARY_STATS = 301,
    HIT_SUMMARY_INFO = 302,
    HIT_SUMMARY_LEARN = 303,
    HIT_BAG_TAB = 400,     // + the bag's pocket tab (ALL first)
    HIT_BAG_LIST = 410,
    HIT_SETTINGS_LIST = 411, // the SETTINGS list (a drag scrolls it)
    HIT_BAG_USE = 490,
    HIT_BAG_GIVE = 491,
    HIT_ITEM_CANCEL = 492,
    HIT_ITEM_SLOT = 480,   // + party slot
    HIT_ITEM_MOVE = 486,   // + move
    HIT_SETTING = 600,     // + enum setting_row
    HIT_OPTION = 700,      // + pick-list option
    HIT_BAG_SORT = 6090,
    HIT_BOX_SLOT = 6100,   // + slot
    HIT_BOX_PREV = 6140,
    HIT_BOX_NEXT = 6141,
    HIT_BOX_NAME = 6142,
    HIT_BOX_PICK = 6150,   // + box
    HIT_BOX_MESSAGE = 6180,
    HIT_SUMMARY_PC = 6181,
    HIT_BOX_CONFIRM = 6182,
    HIT_BOX_CANCEL = 6183,
    HIT_YES = 800,
    HIT_NO = 801,
    HIT_SCRIM = 802,
    HIT_MAP = 900,         // the map itself (where: the touch's start)
    HIT_MAP_REGION = 901,
    HIT_MAP_ME = 902,
    HIT_MAP_PLACES = 903,
    HIT_MAP_FLY = 904,
    HIT_MAP_TAKE_OFF = 905,
    HIT_FLY_TOWN = 1300,   // + town
    HIT_PLACE = 1000,      // + PLACES row
    HIT_DEX_LIST = 1400,
    HIT_DEX_ROW = 30000,   // + national number (up to 2048)
    HIT_DEX_READ_ONLY = 33000, // + national number, focusable for scrolling
    HIT_DEX_TOGGLE = 1900,
    HIT_DEX_PREV = 1901,
    HIT_DEX_NEXT = 1902,
    HIT_DEX_SECTION = 1910, // + section
    HIT_DEX_PAGE = 1920,    // the section's page: a drag scrolls it
    HIT_DEX_FILTER = 1940, // + four filters
    HIT_DEX_CYCLE = 1944,
    HIT_GUIDE_PAGE = 2000, // + page
    HIT_GUIDE_ROW = 2100,  // + entry
    HIT_GUIDE_LIST = 2900,
    HIT_NOTICE_OK = 2901,
    HIT_NOTICE_BACK = 2902,
    HIT_BATTLE_SUGGEST = 3000,
    HIT_BATTLE_INFO = 3001,
    HIT_FOE_SLOT = 3010,   // + foe party index
    HIT_BAG_ROW = 20000,   // + item index
    HIT_BAG_ROW_END = HIT_BAG_ROW + PD_POCKET_COUNT * PD_POCKET_SLOTS,
    HIT_BATTLE_CONTROLS = 3020, // INFO / SUGGESTIONS' way back to the controls
    HIT_BATTLE_FIGHT = 3030,
    HIT_BATTLE_BAG = 3031,
    HIT_BATTLE_RUN = 3032,
    HIT_BATTLE_POKEMON = 3033,
    HIT_BATTLE_CANCEL = 3034,
    HIT_BATTLE_MOVE = 3035, // + move
    HIT_STATE_SLOT = 3050, // + slot
    HIT_STATE_SAVE = 3070,
    HIT_STATE_LOAD = 3071,
    HIT_STATE_UNDO_SAVE = 3072,
    HIT_STATE_UNDO_LOAD = 3073,
    HIT_OPEN_CARD = 3100,
    HIT_CARD = 3101,
    HIT_SETTINGS_GROUP = 3110, // + SETTINGS' group tab (the rail)
    HIT_FOE_DETAILS = 3120,    // INFO's expand / shrink
};

enum setting_row {
    SETTING_SCREEN,
    SETTING_FF,
    SETTING_SAVE_STATE,
    SETTING_LOAD_STATE,
    SETTING_LEAVE,
    SETTING_ASPECT,    // hosts with hasExpand
    SETTING_AUTO_ZOOM,
    SETTING_TAB_BAR,   // opens its page
    SETTING_HUD,       // hosts with hasHud: opens its page
    SETTING_FILTER, // optional host LCD display mask
    SETTING_QUICK_MENU, // hosts with hasQuickMenu: two styles, flips in place
    SETTING_QUICK_BTN,  // hosts with hasButtonRows: cycle X / Y / L2 / R2
    SETTING_COMPANION_BTN,
    SETTING_HUD_BTN,    // also NONE
    SETTING_PERF,      // hosts with hasPerf: flips in place
    SETTING_FF_TOGGLE,
    SETTING_GAME_SPEED,
    SETTING_VOLUME,
    SETTING_MUTE,
    SETTING_DARK,
    SETTING_FF_MODE,
    SETTING_CHEATS,
    SETTING_THEME,
    SETTING_WILD,
    SETTING_AUTO_RUN,
    SETTING_RUN_INDOORS,
    SETTING_HINTS,
    SETTING_ONE_TAP,
    SETTING_BATTLE_STYLE,
    SETTING_BATTLE_INFO,
    SETTING_BATTLE_VIEW,
    SETTING_FLY_STYLE,
    SETTING_FLIGHT_AREA,
    SETTING_FLY_ON,
    SETTING_RESTART,
};

int ui_settings_follow(int scroll, int y, int height);
// SETTINGS' rail: a tap on group [i]'s tab scrolls the list to it.
void ui_settings_group(struct pd_ui* ui, int i);

// The tabs TAB BAR offers, in the bar's order.
#define UI_BAR_CHOICES 10
extern const enum pd_tab UI_BAR_CHOICE[UI_BAR_CHOICES];
const char* ui_tab_label(enum pd_tab t);

// The tab's coloured tile, its game's word on the chip ("BAG"), and whether this game has it.
const struct pd_art* ui_tab_art(enum pd_tab t);
bool ui_tab_available(const struct pd_ui* ui, const struct pd_game* g, enum pd_tab t);
// The tabs off the bar: MORE's page.
void ui_more_page(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g);
// An icon at `scale` canvas pixels a pixel.
void ui_mono(struct pd_canvas* c, const struct pd_mono* icon, int x, int y, int scale, uint32_t color);
void ui_art(struct pd_canvas* c, const struct pd_art* art, int x, int y, int scale);

void ui_add_hit(struct pd_ui* ui, int x, int y, int w, int h, int id);
void ui_add_scroll_hit(struct pd_ui* ui, int x, int y, int w, int h, int id);

// A white framed button; pressed = grey, like the tab chips.
void ui_button(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, const char* label, int id);
// The party menu's HP bar: a dark frame, the "HP" tag, a two-tone fill.
void ui_hp_bar(struct pd_canvas* c, int x, int y, int barW, int hp, int maxHp);
void ui_status_badge(struct pd_canvas* c, int x, int y, uint32_t status);
// A message in a list window across the content area.
void ui_notice(struct pd_canvas* c, const char* title, const char* line1, const char* line2);
// A LABEL  VALUE row, the value from ~42% across (OptionLine).
void ui_row(struct pd_canvas* c, int x, int y, int w, const char* label, const char* value, bool selected);
// ui_row with the value column at [valuePct] of the width (the app's labelWeight).
void ui_row_split(struct pd_canvas* c, int x, int y, int w, const char* label, const char* value, bool selected, int valuePct);

bool ui_summary_learn(struct pd_ui*);
void ui_party_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                  const struct pd_host_info* host);
void ui_battle_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_bag_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                const struct pd_host_info* host);
bool ui_bag_act(struct pd_ui* ui, int id, const struct pd_snapshot* s);
// The party's slots for USE / GIVE's pick, the ones it can't go to faded.
void ui_item_grid(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_settings_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                     const struct pd_host_info* host);
void ui_map_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                const struct pd_host_info* host);
void ui_guide_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_dex_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_states_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_host_info* host);
bool ui_states_act(struct pd_ui* ui, int id);
void ui_boxes_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
bool ui_boxes_act(struct pd_ui* ui, int id, const struct pd_snapshot* s);
void ui_boxes_overlay(struct pd_ui* ui, struct pd_canvas* c);
void ui_box_message(struct pd_ui* ui, struct pd_canvas* c);
void ui_mon_summary(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s, const struct pd_mon* m,
                    const struct pd_mon* const* column, int columnCount, int shown, bool canTake, const char* pcLabel);
void ui_overlay(struct pd_ui* ui, struct pd_canvas* c);
// TAB BAR's / HUD's / MORE's rows: row i picked.
void ui_toggle_act(struct pd_ui* ui, int i);
// The tabs off the bar (MORE's list), in the bar's order.
int ui_configurable_tabs(const struct pd_ui*, enum pd_tab out[UI_BAR_CHOICES]);
int ui_more_tabs(const struct pd_ui* ui, const struct pd_game* g, enum pd_tab out[UI_BAR_CHOICES]);
// How many tabs TAB BAR has on the bar.
int ui_bar_count(const struct pd_ui* ui);
// Overlays drawn by their tab's file (PLACES, the GUIDE notice).
void ui_places_overlay(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g);
void ui_fly_overlay(struct pd_ui* ui, struct pd_canvas* c);
void ui_guide_notice(struct pd_ui* ui, struct pd_canvas* c);

// A tap each tab's file handles; true if it was theirs.
bool ui_map_act(struct pd_ui* ui, int id);
void ui_dex_next_filter(struct pd_ui* ui);
bool ui_dex_act(struct pd_ui* ui, int id);
bool ui_guide_act(struct pd_ui* ui, int id);
bool ui_battle_act(struct pd_ui* ui, int id);
bool ui_card_act(struct pd_ui* ui, int id);
void ui_card_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
bool ui_card_tick(struct pd_ui* ui, unsigned ms);

// BATTLE's touch controls (pd_ui_battle_controls.c): whether they show now, the pane, a tap on it.
bool ui_battle_controls_usable(const struct pd_ui* ui, const struct pd_snapshot* s);
void ui_battle_controls(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
bool ui_battle_controls_act(struct pd_ui* ui, int id, const struct pd_snapshot* s);
// The party's slot SUGGESTIONS would send in (not one already out), or -1; a bit per party slot on the field.
int ui_recommended_switch(const struct pd_snapshot* s);
int ui_out_slots(const struct pd_snapshot* s);
// PARTY's grid in the canvas; with [out] / [best] its picker: those slots tagged, tapping sends a Pokémon in.
void ui_party_grid(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s, int best, int out);
// A beveled colour button (PlatinumButton), id < 0 = not tappable.
// What a move does to the foe, as a chip (MultiplierChip): returns its width (c NULL: just measures).
int ui_mult_chip(struct pd_canvas* c, int x, int y, int pct);
// BATTLE STYLE > GEN 5 (pd_ui_battle_gen5.c): the controls' pane, and the closed ball behind INFO / SUGGESTIONS.
void ui_battle_gen5(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s, bool busy);
void ui_gen5_closed_ball(struct pd_canvas* c);
// The BATTLE tab's backdrop in GEN 5: the closed ball, or only the strip under the pane while the controls cover it.
void ui_gen5_backdrop(struct pd_ui* ui, struct pd_canvas* c, const struct pd_snapshot* s);
// Whether the BATTLE tab takes GEN 5's look for this game.
bool ui_gen5_style(const struct pd_ui* ui, const struct pd_game* g);
// BATTLE INFO on (INFO, SUGGESTIONS and the move ratings), and the ratings with BATTLE HINTS too.
bool ui_battle_info_on(const struct pd_ui* ui);
bool ui_battle_hints_on(const struct pd_ui* ui);
// The battle's touch input is there for a single battle (whatever the game waits for now).
bool ui_battle_input_on(const struct pd_ui* ui, const struct pd_snapshot* s);
void ui_platinum(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, uint32_t fill, uint32_t frame, int id);
// A PlatinumButton's label / back arrow, centred: white on its colour, the window's ink on a Game Boy game's plain box.
void ui_platinum_label(struct pd_canvas* c, int x, int y, int w, int h, const char* text);
void ui_platinum_back(struct pd_canvas* c, int x, int y, int w, int h);
// Blends what was just drawn in a rectangle over [before] (w x h pixels saved first): alpha 0-255.
void ui_fade_rect(struct pd_canvas* c, const uint32_t* before, int x, int y, int w, int h, int alpha);
// A type's colour (AppColors.typeColors), and its badge: returns the width.
uint32_t ui_type_color(int type);
int ui_type_badge(struct pd_canvas* c, int x, int y, int type);

// The lists' caught / seen mark: a Poké Ball, or a grey one.
void ui_ball(struct pd_canvas* c, int x, int y, bool caught);

// A scrolled list's offset clamped to its content: max(0, total - view).
int ui_clamp_scroll(int scroll, int total, int view);
// The scroll that keeps a row (top..top+h in list pixels) wholly in a view of [view] pixels, moved as little as can be.
int ui_scroll_to(int scroll, int top, int h, int view);
// A thin scroll bar along a list's right edge.
void ui_scroll_bar(struct pd_canvas* c, int x, int y, int h, int scroll, int total, uint32_t color);
// A list's bar to drag (SETTINGS): the rail in x, y, w, h, the thumb for scroll of total with view shown.
#define SCROLL_BAR_W 8
void ui_scroll_rail(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, int scroll, int total, int view);
// A list's rail in the room its rows keep clear at their right edge ([right] = the list's inner right): dragged like SETTINGS'.
void ui_list_rail(struct pd_ui* ui, struct pd_canvas* c, int right, int y, int h, int scroll, int total);

#endif
