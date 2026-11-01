// Shared by the companion's tab files (pd_ui_*.c): layout, hit ids and the
// small widgets every tab draws with.
#ifndef PD_UI_INTERNAL_H
#define PD_UI_INTERNAL_H

#include <stddef.h>

#include "pd_style.h"
#include "pd_ui.h"

// Where a tab's content starts: the tab bar is along the bottom, like the app's
// (pd_ui_draw hands the tabs the canvas above it).
#define CONTENT_Y MARGIN
#define MARGIN 4
#define ROW_H 18

// Hit ids.
enum {
    HIT_TAB = 100,         // + tab
    HIT_FF = 110,
    HIT_MORE = 111,        // the tab bar's MORE chip
    HIT_SLOT = 200,        // + party slot
    HIT_BACK = 300,
    HIT_SUMMARY_STATS = 301,
    HIT_BAG_TAB = 400,     // + the bag's pocket tab (ALL first)
    HIT_BAG_LIST = 410,
    HIT_SETTINGS_LIST = 411, // the SETTINGS list (a drag scrolls it)
    HIT_SETTING = 600,     // + enum setting_row
    HIT_OPTION = 700,      // + pick-list option
    HIT_YES = 800,
    HIT_NO = 801,
    HIT_SCRIM = 802,
    HIT_MAP = 900,         // the map itself (where: the touch's start)
    HIT_MAP_REGION = 901,
    HIT_MAP_ME = 902,
    HIT_MAP_PLACES = 903,
    HIT_PLACE = 1000,      // + PLACES row
    HIT_DEX_LIST = 1400,
    HIT_DEX_ROW = 1500,    // + national number
    HIT_DEX_TOGGLE = 1900,
    HIT_DEX_PREV = 1901,
    HIT_DEX_NEXT = 1902,
    HIT_GUIDE_PAGE = 2000, // + page
    HIT_GUIDE_ROW = 2100,  // + entry
    HIT_GUIDE_LIST = 2900,
    HIT_NOTICE_OK = 2901,
    HIT_NOTICE_BACK = 2902,
    HIT_BATTLE_SUGGEST = 3000,
    HIT_BATTLE_INFO = 3001,
    HIT_FOE_SLOT = 3010,   // + foe party index
    HIT_BAG_ROW = 20000,   // + item index (up to PD_POCKET_SLOTS: a hack's pockets run past 100)
    HIT_OPEN_CARD = 3100,
    HIT_CARD = 3101,
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
};

// The tabs TAB BAR offers, in the bar's order.
#define UI_BAR_CHOICES 6
extern const enum pd_tab UI_BAR_CHOICE[UI_BAR_CHOICES];
const char* ui_tab_label(enum pd_tab t);

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
void ui_format_money(long v, char* buf, size_t len);

void ui_party_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_battle_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_bag_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_settings_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                     const struct pd_host_info* host);
void ui_map_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_guide_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_dex_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
void ui_overlay(struct pd_ui* ui, struct pd_canvas* c);
// TAB BAR's / HUD's / MORE's rows: row i picked.
void ui_toggle_act(struct pd_ui* ui, int i);
// The tabs off the bar (MORE's list), in the bar's order.
int ui_more_tabs(const struct pd_ui* ui, enum pd_tab out[UI_BAR_CHOICES]);
// How many tabs TAB BAR has on the bar.
int ui_bar_count(const struct pd_ui* ui);
// Overlays drawn by their tab's file (PLACES, the GUIDE notice).
void ui_places_overlay(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g);
void ui_guide_notice(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g);

// A tap each tab's file handles; true if it was theirs.
bool ui_map_act(struct pd_ui* ui, int id);
bool ui_dex_act(struct pd_ui* ui, int id);
bool ui_guide_act(struct pd_ui* ui, int id);
bool ui_battle_act(struct pd_ui* ui, int id);
bool ui_card_act(struct pd_ui* ui, int id);
void ui_card_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s);
bool ui_card_tick(struct pd_ui* ui, unsigned ms);

// The lists' caught / seen mark: a Poké Ball, or a grey one.
void ui_ball(struct pd_canvas* c, int x, int y, bool caught);

// A scrolled list's offset clamped to its content: max(0, total - view).
int ui_clamp_scroll(int scroll, int total, int view);
// A thin scroll bar along a list's right edge.
void ui_scroll_bar(struct pd_canvas* c, int x, int y, int h, int scroll, int total, uint32_t color);

#endif
