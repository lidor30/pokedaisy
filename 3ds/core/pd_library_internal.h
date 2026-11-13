// What the library's files share: the layout, the touch hits, the game list both GAMES and the save tools' pickers draw.
#ifndef PD_LIBRARY_INTERNAL_H
#define PD_LIBRARY_INTERNAL_H

#include "pd_icons_gen.h"
#include "pd_library.h"
#include "pd_style.h"

#define LIB_W 320
#define LIB_H 240
#define RAIL_W 44
#define LIB_M 4 // the touch screen's margin: beside the rail, at the right, top and bottom, and between windows
#define PAGE_X (RAIL_W + LIB_M)
#define PAGE_W (LIB_W - PAGE_X - LIB_M)
#define TITLE_Y LIB_M
#define TITLE_H 24
#define LIST_ROW_H 30
#define TILE_H 66
#define BUTTON_H 22

// What a tap can land on, as drawn last.
enum {
    HIT_NONE = 0,
    HIT_RAIL = 100,      // + page
    HIT_VIEW = 200,      // + view
    HIT_ROW = 1000,      // + index in the list shown
    HIT_SETTING = 2000,  // + settings row
    HIT_PICK = 3000,     // + pick-list choice
    HIT_PICK_CLOSE = 3999,
    HIT_SLOT = 4000,     // + box slot (the save tools)
    HIT_BOX_PREV = 4100, HIT_BOX_NEXT, HIT_SIDE_A, HIT_SIDE_B, HIT_MONEY, HIT_COINS, HIT_WRITE, HIT_DONE, HIT_TRADE,
    HIT_YES, HIT_NO, HIT_DIGIT_UP = 4200, HIT_DIGIT_DOWN = 4300, HIT_OK, HIT_MESSAGE,
    HIT_SCROLL = 5000, // a list's scroll bar: dragged, not tapped
};

// A list's scroll bar (the one on the page now): its track, drawn and dragged. [scroll] is the list's.
struct lib_scroll { int x, y, h, total, view; int* scroll; };
// Draws the bar beside a list of [total] pixels showing [view] from [y], and takes its drags.
void lib_scroll_bar(struct pd_canvas* c, int x, int y, int h, int total, int view, int* scroll);
// The stylus on the bar: the list jumps there and follows it.
bool lib_scroll_drag(int touchY);

void lib_hits_clear(void);
void lib_hit(int x, int y, int w, int h, int id);
// The hit under (x, y), the last one drawn first; HIT_NONE = none.
int lib_hit_at(int x, int y);

// The pages' art: the rail's icons (the app's ToolIcons / LibraryTools' GAMES and SETTINGS).
extern const struct pd_art LIB_ICON_GAMES, LIB_ICON_EDIT, LIB_ICON_TRADE, LIB_ICON_SETTINGS;
// The rail's icons and the logo at 24 px, drawn smooth over what's behind (pd_library_draw.c makes them from the 16 / 32 px art).
#define LIB_ICON_SIZE 24
void lib_draw_icon_24(struct pd_canvas* c, int page, int x, int y);
void lib_draw_logo_24(struct pd_canvas* c, int x, int y);
// A cartridge in a console's colours (a game never played here), 16 px.
void lib_cartridge(struct pd_canvas* c, int x, int y, int scale, enum pd_console console, bool hack);

// The page's backdrop, its title window and the rail.
void lib_page_backdrop(struct pd_canvas* c);
int lib_title(struct pd_canvas* c, const char* title, const char* right, bool logo);
extern int lib_battery;   // what lib_title shows at its right (pd_library.batteryPct as the page is drawn)
int lib_battery_width(void);   // its icon and percentage, 0 = unknown
void lib_battery_box(struct pd_canvas* c, int x, int y, int w, int h);   // in a title window of its own
void lib_rail(struct pd_library* l, struct pd_canvas* c);
// A two-tone button in the companion's look (OptionButton); [on] = pressed / picked.
void lib_button(struct pd_canvas* c, int x, int y, int w, int h, const char* label, bool on, int hit);
// The black-out the app's windows open over (OptionOverlay's dim).
void lib_dim(struct pd_canvas* c);
// A window in the middle with a message and buttons: [yes] / [no] NULL = that button not shown.
void lib_dialog(struct pd_canvas* c, const char* title, const char* line1, const char* line2, const char* yes, const char* no);

// The games list (GAMES, and the save tools' pickers): rows or tiles of these games, the cursor on [selected].
#define lib_list pd_library_list
void lib_list_draw(struct pd_library* l, struct pd_canvas* c, struct lib_list* list, int y, int h, bool grid, const char* empty1,
                   const char* empty2);
void lib_list_move(struct lib_list* list, int dx, int dy, int columns, int viewH, bool grid);
int lib_list_hit(const struct lib_list* list, int hit); // the index a HIT_ROW tap picked, -1 = none
// A game's lead Pokémon icon from its card (cached per game), NULL = none yet.
const uint32_t* lib_lead_icon(struct pd_library* l, int game);
// The game's card (read once while it's the one the cursor is on); NULL = none.
const struct pd_game_card* lib_card(struct pd_library* l, int game);

// SAVE EDITOR and TRADE SAVES (pd_library_saves.c).
void lib_saves_open(struct pd_library* l, int page);
// The library opened again (a game may have changed any save since): nothing read before is kept.
void lib_saves_reset(void);
void lib_top_reset(void);
void lib_draw_reset(void);
// Once a frame: a ROM to load once LOADING has shown.
void lib_saves_tick(struct pd_library* l);
void lib_saves_keys(struct pd_library* l, unsigned down);
void lib_saves_tap(struct pd_library* l, int hit);
void lib_saves_draw(struct pd_library* l, struct pd_canvas* c);
void lib_saves_draw_top(struct pd_library* l, struct pd_canvas* c);
// Whether the save tools take a drag (their lists) and B (an open save): the library leaves those to them.
bool lib_saves_busy(const struct pd_library* l);
struct lib_list* lib_saves_list(struct pd_library* l);

// SETTINGS (pd_library_draw.c's list, pd_library.c's actions).
int lib_settings_rows(const struct pd_library* l);
void lib_settings_act(struct pd_library* l, int row);
void lib_settings_pick(struct pd_library* l, int choice);
void lib_settings_draw(struct pd_library* l, struct pd_canvas* c);
const char* lib_settings_label(const struct pd_library* l, int row, const char** value, bool* group, int* choices);
const char* lib_settings_choice(const struct pd_library* l, int row, int choice);

// What the app knows of a game by its hash or file name, NULL = nothing.
struct pd_game_entry;
const struct pd_game_entry* lib_entry(const struct pd_library_game* g);
// The top screen's title window: [icon] (a page's 24 px icon, -1 = the 24 px logo), the title, [right] in red. Its frame's width.
#define TOP_M 8 // the top screen's margin, every side
#define TOP_TITLE_Y TOP_M
#define TOP_TITLE_H 32
#define TOP_BODY_Y (TOP_TITLE_Y + TOP_TITLE_H + 6)
#define TOP_BODY_END (240 - 22 - TOP_M) // above the help bar
int lib_top_title(struct pd_canvas* c, int icon, const char* title, const char* right);
// The top screen's help bar along its bottom.
void lib_hint_bar(struct pd_canvas* c, const char* text);

// The game list GAMES shows (the view's games in the library's order).
struct lib_list* lib_games_list(struct pd_library* l);
bool lib_grid(const struct pd_library* l);
// The 16 px logo / the console tag for a game.
const char* lib_console_tag(const struct pd_library_game* g);

#endif
