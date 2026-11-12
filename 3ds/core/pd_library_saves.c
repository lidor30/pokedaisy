// SAVE EDITOR and TRADE SAVES on the 3DS: the app's library tools (SaveEditorActivity's PC BOXES and TRADE), cut down to what
// fits a 320x240 touch screen and the D-pad. The save is the file beside the ROM (the game isn't running here, so nothing
// else writes it); every write goes the game's way (pd_save_build: the other slot, read back) and the host backs the file up
// first. Retail English / European FireRed, LeafGreen, Ruby, Sapphire and Emerald saves, as in the app.
//
// The touch screen shows the box under work, the top screen the Pokémon on the cursor (and in a trade, both sides' picks).
#include <stdio.h>
#include <string.h>

#include "pd_game.h"
#include "pd_icon.h"
#include "pd_library_internal.h"
#include "pd_save.h"
#include "pd_tables.h"
#include "pd_ui.h"

enum stage { PICK_A, PICK_B, LOADING, OPEN };
enum dialog { NONE, CONFIRM_WRITE, CONFIRM_TRADE, CONFIRM_LEAVE, MESSAGE, NUMBER };
enum number_of { MONEY, COINS };
#define SLOTS PD_SAVE_BOX_SLOTS
#define COLS 6
#define ROWS 5
#define BUTTONS 3 // the row under the box: the cursor goes there past its last row

struct side {
    int game;
    struct pd_save save;
    int box;
    int mark; // TRADE: this side's pick (box * SLOTS + slot), -1 = none
    struct pd_trade_side trade;
};

struct pd_library_saves {
    int page, stage, loadingFrames;
    struct lib_list pick;
    struct side sides[2];
    int active;         // the side the touch screen shows (TRADE)
    int cursor;         // a box slot, or SLOTS + a button
    int held;           // SAVE EDITOR: the Pokémon picked up (box * SLOTS + slot), -1 = none
    struct pd_game names; // side 0's game, for its names and (with its ROM loaded) its icons
    bool icons;
    int dialog;
    char title[32], line1[96], line2[96];
    int numberOf, digit;
    long number;
};

static struct pd_library_saves state;
static uint8_t written[2][PD_SAVE_MAX_SIZE];
static uint8_t readBuf[PD_SAVE_MAX_SIZE];

static struct pd_library_saves* S(struct pd_library* l) {
    if (!l->saves) l->saves = &state;
    return l->saves;
}

static bool editable(const struct pd_library_game* g) {
    // A 32 MB game under a retail code is a CFRU / expansion hack, whose save is its own layout (0 = not measured yet).
    return g->console == PD_CONSOLE_GBA && pd_save_layout_for(g->code) != NULL && g->size <= 16 * 1024 * 1024;
}

// The games the tool can open, in the library's order (the other side's game left out when picking a trade's second save).
static void fill_pick(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    int keep = s->pick.count ? s->pick.items[s->pick.selected] : -1;
    s->pick.count = 0;
    for (int i = 0; i < l->list.count + l->count; i++) {
        // GAMES' order first (its view), then anything that view hides.
        int game = i < l->list.count ? l->list.items[i] : i - l->list.count;
        bool seen = false;
        for (int k = 0; k < s->pick.count; k++) seen |= s->pick.items[k] == game;
        if (seen || !editable(&l->games[game])) continue;
        if (s->stage == PICK_B && game == s->sides[0].game) continue;
        s->pick.items[s->pick.count++] = game;
    }
    s->pick.selected = 0;
    for (int i = 0; i < s->pick.count; i++)
        if (s->pick.items[i] == keep) s->pick.selected = i;
}

void lib_saves_reset(void) {
    state.page = -1;
    state.stage = PICK_A;
    state.dialog = NONE;
    state.held = -1;
    state.icons = false;
    memset(&state.names, 0, sizeof state.names);
}

void lib_saves_open(struct pd_library* l, int page) {
    struct pd_library_saves* s = S(l);
    if (s->page == page && s->stage != PICK_A) return; // back to where it was
    memset(&s->sides[0].trade, 0, sizeof s->sides[0].trade);
    s->page = page;
    s->stage = PICK_A;
    s->dialog = NONE;
    s->held = -1;
    s->pick.count = 0;
    s->pick.scroll = 0;
    fill_pick(l);
}

struct lib_list* lib_saves_list(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    return s->stage == PICK_A || s->stage == PICK_B ? &s->pick : NULL;
}

bool lib_saves_busy(const struct pd_library* l) {
    const struct pd_library_saves* s = l->saves;
    return s && (s->stage != PICK_A || s->dialog != NONE);
}

static void message(struct pd_library_saves* s, const char* title, const char* line1, const char* line2) {
    s->dialog = MESSAGE;
    snprintf(s->title, sizeof s->title, "%s", title);
    snprintf(s->line1, sizeof s->line1, "%s", line1 ? line1 : "");
    snprintf(s->line2, sizeof s->line2, "%s", line2 ? line2 : "");
}

// --- opening a save ---

// Reads the side's save; false (with the reason shown) if it isn't one the tools can open.
static bool open_side(struct pd_library* l, int i, int game) {
    struct pd_library_saves* s = S(l);
    const struct pd_library_game* g = &l->games[game];
    const struct pd_library_host* h = l->host;
    struct side* side = &s->sides[i];
    side->game = game;
    side->box = 0;
    side->mark = -1;
    long n = h && h->read_save ? h->read_save(h->ctx, g->file, readBuf, sizeof readBuf) : -1;
    if (n <= 0) {
        message(s, "NO SAVE", "This game has no save yet.", "Save once in the game first.");
        return false;
    }
    const char* why = pd_save_parse(&side->save, readBuf, (size_t) n, pd_save_layout_for(g->code));
    if (!why) why = pd_save_mismatch(&side->save);
    if (why) {
        message(s, "CAN'T OPEN IT", why, NULL);
        return false;
    }
    side->box = pd_save_current_box(&side->save);
    side->trade.save = &side->save;
    side->trade.label = g->title;
    snprintf(side->trade.code, sizeof side->trade.code, "%s", g->code);
    return true;
}

// The names (and with its ROM, the icons) come from side 0's game: the Gen 3 games share their species, moves and items.
static void load_names(struct pd_library* l, int game) {
    struct pd_library_saves* s = S(l);
    const struct pd_library_host* h = l->host;
    const struct pd_library_game* g = &l->games[game];
    uint8_t header[0xC0] = { 0 };
    if (h && h->read_rom) h->read_rom(h->ctx, g->file, 0, header, sizeof header);
    size_t size = 0;
    const uint8_t* rom = h && h->load_rom ? h->load_rom(h->ctx, g->file, &size) : NULL;
    pd_game_detect(&s->names, rom ? rom : header, rom ? size : (size_t) g->size);
    s->names.rom = rom;
    s->names.romSize = rom ? size : 0;
    s->icons = rom != NULL;
}

static void picked(struct pd_library* l, int game) {
    struct pd_library_saves* s = S(l);
    int i = s->stage == PICK_B ? 1 : 0;
    if (!open_side(l, i, game)) return;
    if (s->page == PD_PAGE_TRADE && i == 0) {
        s->stage = PICK_B;
        fill_pick(l);
        s->pick.scroll = 0;
        return;
    }
    // The ROM is read after a frame showing LOADING (a 16 MB file takes a few seconds off the SD card).
    s->stage = LOADING;
    s->loadingFrames = 0;
    s->active = 0;
    s->cursor = 0;
    s->held = -1;
}

void lib_saves_tick(struct pd_library* l) {
    struct pd_library_saves* s = l->saves;
    if (!s || s->stage != LOADING || ++s->loadingFrames < 2) return;
    load_names(l, s->sides[0].game);
    s->stage = OPEN;
    l->dirty = true;
}

// --- the box ---

static struct side* active_side(struct pd_library_saves* s) {
    return &s->sides[s->page == PD_PAGE_TRADE ? s->active : 0];
}

static void step_box(struct pd_library_saves* s, int step) {
    struct side* side = active_side(s);
    side->box = (side->box + step + PD_SAVE_BOXES) % PD_SAVE_BOXES;
}

static void move_cursor(struct pd_library_saves* s, int dx, int dy) {
    if (s->cursor >= SLOTS) {
        int b = s->cursor - SLOTS;
        if (dy < 0) s->cursor = (ROWS - 1) * COLS + b * COLS / BUTTONS;
        else if (dx) s->cursor = SLOTS + (b + dx + BUTTONS) % BUTTONS;
        return;
    }
    int col = s->cursor % COLS, row = s->cursor / COLS;
    if (dx) col = (col + dx + COLS) % COLS;
    if (dy) {
        row += dy;
        if (row >= ROWS) {
            s->cursor = SLOTS + col * BUTTONS / COLS;
            return;
        }
        if (row < 0) row = ROWS - 1;
    }
    s->cursor = row * COLS + col;
}

// --- writing ---

static bool write_sides(struct pd_library* l, int n) {
    struct pd_library_saves* s = S(l);
    const struct pd_library_host* h = l->host;
    const char* files[2];
    const uint8_t* bytes[2];
    long lens[2];
    for (int i = 0; i < n; i++) {
        struct side* side = &s->sides[i];
        const char* why = pd_save_build(&side->save, written[i]);
        if (why) {
            message(s, "NOT WRITTEN", why, "The save is as it was.");
            return false;
        }
        files[i] = l->games[side->game].file;
        bytes[i] = written[i];
        lens[i] = (long) side->save.rawLen;
    }
    char why[96] = "";
    if (!h || !h->write_saves || !h->write_saves(h->ctx, n, files, bytes, lens, why, sizeof why)) {
        message(s, "NOT WRITTEN", why[0] ? why : "The SD card didn't take it.", "The save is as it was.");
        return false;
    }
    // What was written is the save now: the next edit starts from it.
    for (int i = 0; i < n; i++) {
        struct side* side = &s->sides[i];
        int box = side->box;
        pd_save_parse(&side->save, written[i], side->save.rawLen, side->save.f);
        side->box = box;
    }
    return true;
}

static void edit_number(struct pd_library_saves* s, int what) {
    s->dialog = NUMBER;
    s->numberOf = what;
    s->number = what == MONEY ? pd_save_money(&s->sides[0].save) : pd_save_coins(&s->sides[0].save);
    s->digit = 0;
}

static int number_digits(const struct pd_library_saves* s) {
    return s->numberOf == MONEY ? 6 : 4;
}

static void number_step(struct pd_library_saves* s, int digit, int step) {
    long p = 1;
    for (int i = 0; i < number_digits(s) - 1 - digit; i++) p *= 10;
    long d = s->number / p % 10, next = (d + step + 10) % 10;
    s->number += (next - d) * p;
}

static void number_done(struct pd_library_saves* s, bool ok) {
    s->dialog = NONE;
    if (!ok) return;
    if (s->numberOf == MONEY) pd_save_set_money(&s->sides[0].save, s->number);
    else pd_save_set_coins(&s->sides[0].save, (int) s->number);
}

// --- the trade ---

static void trade_text(struct pd_library_saves* s) {
    struct side *a = &s->sides[0], *b = &s->sides[1];
    struct pd_mon ma, mb;
    pd_save_box_mon(&a->save, a->mark / SLOTS, a->mark % SLOTS, &ma);
    bool hasB = b->mark >= 0 && pd_save_box_mon(&b->save, b->mark / SLOTS, b->mark % SLOTS, &mb);
    if (hasB) snprintf(s->line1, sizeof s->line1, "%s for %s?", ma.nickname, mb.nickname);
    else snprintf(s->line1, sizeof s->line1, "Send %s?", ma.nickname);
    snprintf(s->line2, sizeof s->line2, "Both saves are written (backed up first).");
}

static void start_trade(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    struct side *a = &s->sides[0], *b = &s->sides[1];
    if (a->mark < 0 || b->mark < 0) {
        message(s, "PICK BOTH", "Pick a Pokémon on one side and", "a Pokémon or an empty place on the other.");
        return;
    }
    struct pd_mon m;
    bool fromA = pd_save_box_mon(&a->save, a->mark / SLOTS, a->mark % SLOTS, &m);
    bool fromB = pd_save_box_mon(&b->save, b->mark / SLOTS, b->mark % SLOTS, &m);
    if (!fromA && !fromB) {
        message(s, "NOTHING TO TRADE", "Both places are empty.", NULL);
        return;
    }
    char why[160];
    const char* no = fromA ? pd_trade_blocker(&a->trade, a->mark / SLOTS, a->mark % SLOTS, &b->trade, why, sizeof why) : NULL;
    if (!no && fromB) no = pd_trade_blocker(&b->trade, b->mark / SLOTS, b->mark % SLOTS, &a->trade, why, sizeof why);
    if (no) {
        message(s, "THE GAMES WON'T", no, NULL);
        return;
    }
    s->dialog = CONFIRM_TRADE;
    snprintf(s->title, sizeof s->title, "TRADE");
    if (fromA) trade_text(s);
    else {
        pd_save_box_mon(&b->save, b->mark / SLOTS, b->mark % SLOTS, &m);
        snprintf(s->line1, sizeof s->line1, "Send %s?", m.nickname);
        snprintf(s->line2, sizeof s->line2, "Both saves are written (backed up first).");
    }
}

static void do_trade(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    struct side *a = &s->sides[0], *b = &s->sides[1];
    struct pd_mon m;
    bool fromA = pd_save_box_mon(&a->save, a->mark / SLOTS, a->mark % SLOTS, &m);
    struct side *from = fromA ? a : b, *to = fromA ? b : a;
    int evolves = pd_trade_evolution(&from->trade, from->mark / SLOTS, from->mark % SLOTS, &to->trade);
    char why[160];
    const char* no = pd_trade(&from->trade, from->mark / SLOTS, from->mark % SLOTS, &to->trade, to->mark / SLOTS, to->mark % SLOTS, why, sizeof why);
    if (no) {
        message(s, "THE GAMES WON'T", no, NULL);
        return;
    }
    if (!write_sides(l, 2)) {
        pd_save_revert(&a->save);
        pd_save_revert(&b->save);
        return;
    }
    char evo[64] = "";
    if (evolves) {
        char name[24];
        snprintf(name, sizeof name, "%s", evolves < pd_species_names_count && pd_species_names[evolves] ? pd_species_names[evolves] : "?");
        snprintf(evo, sizeof evo, "It evolved into %s on arrival!", name);
    }
    a->mark = b->mark = -1;
    message(s, "TRADED", "Both saves are written.", evo[0] ? evo : NULL);
}

// --- input ---

static void act_button(struct pd_library* l, int b) {
    struct pd_library_saves* s = S(l);
    if (s->page == PD_PAGE_EDITOR) {
        if (b == 0) edit_number(s, MONEY);
        else if (b == 1) edit_number(s, COINS);
        else if (pd_save_changed(&s->sides[0].save)) {
            s->dialog = CONFIRM_WRITE;
            snprintf(s->title, sizeof s->title, "SAVE");
            snprintf(s->line1, sizeof s->line1, "Write these changes to the save?");
            snprintf(s->line2, sizeof s->line2, "A backup of it is kept first.");
        } else {
            message(s, "NOTHING CHANGED", "Move a Pokémon or change the", "money first.");
        }
    } else {
        if (b == 0) s->active = 0;
        else if (b == 1) s->active = 1;
        else start_trade(l);
    }
}

static void act_slot(struct pd_library* l, int slot) {
    struct pd_library_saves* s = S(l);
    struct side* side = active_side(s);
    int at = side->box * SLOTS + slot;
    if (s->page == PD_PAGE_TRADE) {
        side->mark = side->mark == at ? -1 : at;
        return;
    }
    struct pd_mon m;
    if (s->held < 0) {
        if (pd_save_box_mon(&side->save, side->box, slot, &m)) s->held = at;
        return;
    }
    const char* why = pd_save_move(&side->save, s->held / SLOTS, s->held % SLOTS, side->box, slot);
    if (why) message(s, "CAN'T MOVE IT", why, NULL);
    s->held = -1;
}

static void leave(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    if (s->page == PD_PAGE_TRADE && s->stage == PICK_B) {
        s->stage = PICK_A;
        fill_pick(l);
        return;
    }
    if (s->page == PD_PAGE_EDITOR && s->stage == OPEN && pd_save_changed(&s->sides[0].save)) {
        s->dialog = CONFIRM_LEAVE;
        snprintf(s->title, sizeof s->title, "LEAVE");
        snprintf(s->line1, sizeof s->line1, "Leave without writing the changes?");
        s->line2[0] = 0;
        return;
    }
    s->stage = PICK_A;
    s->held = -1;
    fill_pick(l);
}

static void answer(struct pd_library* l, bool yes) {
    struct pd_library_saves* s = S(l);
    int d = s->dialog;
    s->dialog = NONE;
    if (!yes) return;
    if (d == CONFIRM_WRITE) {
        if (write_sides(l, 1)) message(s, "SAVED", "The save is written.", "Its last version is in pokedaisy-backups.");
    } else if (d == CONFIRM_TRADE) {
        do_trade(l);
    } else if (d == CONFIRM_LEAVE) {
        pd_save_revert(&s->sides[0].save);
        s->stage = PICK_A;
        fill_pick(l);
    }
}

void lib_saves_keys(struct pd_library* l, unsigned down) {
    struct pd_library_saves* s = S(l);
    if (s->dialog == MESSAGE) {
        if (down & (PD_LIB_A | PD_LIB_B)) s->dialog = NONE;
        return;
    }
    if (s->dialog == NUMBER) {
        int dx = !!(down & PD_LIB_RIGHT) - !!(down & PD_LIB_LEFT);
        s->digit = (s->digit + dx + number_digits(s)) % number_digits(s);
        if (down & PD_LIB_UP) number_step(s, s->digit, 1);
        if (down & PD_LIB_DOWN) number_step(s, s->digit, -1);
        if (s->numberOf == MONEY && s->number > PD_SAVE_MAX_MONEY) s->number = PD_SAVE_MAX_MONEY;
        if (down & (PD_LIB_A | PD_LIB_B)) number_done(s, down & PD_LIB_A);
        return;
    }
    if (s->dialog != NONE) {
        if (down & (PD_LIB_A | PD_LIB_B)) answer(l, down & PD_LIB_A);
        return;
    }
    if (s->stage == LOADING) return;
    if (s->stage != OPEN) {
        int dx = !!(down & PD_LIB_RIGHT) - !!(down & PD_LIB_LEFT), dy = !!(down & PD_LIB_DOWN) - !!(down & PD_LIB_UP);
        bool grid = lib_grid(l);
        if (dx || dy) lib_list_move(&s->pick, grid ? dx : 0, dy, l->columns, 0, grid);
        if (down & PD_LIB_A && s->pick.count) picked(l, s->pick.items[s->pick.selected]);
        if (down & PD_LIB_B) leave(l);
        return;
    }
    int dx = !!(down & PD_LIB_RIGHT) - !!(down & PD_LIB_LEFT), dy = !!(down & PD_LIB_DOWN) - !!(down & PD_LIB_UP);
    if (dx || dy) move_cursor(s, dx, dy);
    if (down & PD_LIB_L) step_box(s, -1);
    if (down & PD_LIB_R) step_box(s, 1);
    if (down & PD_LIB_A) {
        if (s->cursor >= SLOTS) act_button(l, s->cursor - SLOTS);
        else act_slot(l, s->cursor);
    }
    if (down & PD_LIB_X) act_button(l, s->page == PD_PAGE_EDITOR ? 2 : 1 - s->active);
    if (down & PD_LIB_Y && s->page == PD_PAGE_TRADE) start_trade(l);
    if (down & PD_LIB_B) {
        if (s->held >= 0) s->held = -1;
        else leave(l);
    }
}

void lib_saves_tap(struct pd_library* l, int hit) {
    struct pd_library_saves* s = S(l);
    if (s->dialog == MESSAGE) {
        if (hit == HIT_YES || hit == HIT_NO || hit == HIT_MESSAGE) s->dialog = NONE;
        return;
    }
    if (s->dialog == NUMBER) {
        if (hit >= HIT_DIGIT_UP && hit < HIT_DIGIT_UP + 8) s->digit = hit - HIT_DIGIT_UP, number_step(s, s->digit, 1);
        else if (hit >= HIT_DIGIT_DOWN && hit < HIT_DIGIT_DOWN + 8) s->digit = hit - HIT_DIGIT_DOWN, number_step(s, s->digit, -1);
        else if (hit == HIT_YES || hit == HIT_NO) number_done(s, hit == HIT_YES);
        if (s->numberOf == MONEY && s->number > PD_SAVE_MAX_MONEY) s->number = PD_SAVE_MAX_MONEY;
        return;
    }
    if (s->dialog != NONE) {
        if (hit == HIT_YES || hit == HIT_NO) answer(l, hit == HIT_YES);
        return;
    }
    if (s->stage == PICK_A || s->stage == PICK_B) {
        int i = lib_list_hit(&s->pick, hit);
        if (i >= 0) {
            if (i == s->pick.selected) picked(l, s->pick.items[i]);
            s->pick.selected = i;
        }
        if (hit == HIT_DONE) leave(l);
        return;
    }
    if (s->stage != OPEN) return;
    if (hit >= HIT_SLOT && hit < HIT_SLOT + SLOTS) {
        s->cursor = hit - HIT_SLOT;
        act_slot(l, s->cursor);
    } else if (hit == HIT_BOX_PREV) {
        step_box(s, -1);
    } else if (hit == HIT_BOX_NEXT) {
        step_box(s, 1);
    } else if (hit == HIT_SIDE_A || hit == HIT_SIDE_B) {
        s->active = hit == HIT_SIDE_B;
    } else if (hit == HIT_MONEY || hit == HIT_COINS || hit == HIT_WRITE || hit == HIT_TRADE) {
        int b = hit == HIT_MONEY ? 0 : hit == HIT_COINS ? 1 : 2;
        s->cursor = SLOTS + b;
        act_button(l, b);
    } else if (hit == HIT_DONE) {
        leave(l);
    }
}

// --- drawing: the touch screen ---

static void draw_mon(struct pd_library_saves* s, struct pd_canvas* c, const struct pd_mon* m, int x, int y, bool faded) {
    int species = m->egg ? PD_SPECIES_EGG : m->species;
    if (!s->icons || !pd_draw_mon_icon(c, &s->names, species, x, y, faded)) pd_draw_party_ball(c, x + 6, y + 8);
}

static void box_view(struct pd_library* l, struct pd_canvas* c, struct side* side, int y, int h) {
    struct pd_library_saves* s = S(l);
    int x = PAGE_X, w = PAGE_W;
    // The box's name between its arrows (L / R).
    char name[24], head[40];
    pd_save_box_name(&side->save, side->box, name, sizeof name);
    snprintf(head, sizeof head, "%s  %d/%d", name, pd_save_box_count(&side->save, side->box), SLOTS);
    lib_button(c, x, y, 26, 20, "", false, HIT_BOX_PREV);
    pd_triangle(c, x + 9, y + 5, 5, PD_LEFT, PD_VALUE);
    lib_button(c, x + w - 26, y, 26, 20, "", false, HIT_BOX_NEXT);
    pd_triangle(c, x + w - 15, y + 5, 5, PD_RIGHT, PD_VALUE);
    int tf = pd_title_box(c, x + 29, y, w - 58, 20, PD_TITLE_FILL);
    pd_text_fit(c, x + 29 + (w - 58 - pd_text_width(head)) / 2, pd_text_y(y, 20), w - 58 - 2 * tf, head, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    y += 23;
    h -= 23;
    int f = pd_list_box(c, x, y, w, h);
    int cw = (w - 2 * f - 4) / COLS, ch = (h - 2 * f - 4) / ROWS;
    int gx = x + f + 2 + (w - 2 * f - 4 - cw * COLS) / 2, gy = y + f + 2;
    pd_clip(c, x + f, y + f, w - 2 * f, h - 2 * f);
    for (int i = 0; i < SLOTS; i++) {
        int cx = gx + (i % COLS) * cw, cy = gy + (i / COLS) * ch, at = side->box * SLOTS + i;
        bool cursor = s->cursor == i && s->dialog == NONE, marked = side->mark == at, held = s->held == at;
        if (cursor) pd_round_rect(c, cx + 1, cy, cw - 2, ch, 3, PD_ROW_SELECTED);
        if (marked) {
            pd_round_rect(c, cx + 1, cy, cw - 2, ch, 3, PD_VALUE);
            pd_round_rect(c, cx + 3, cy + 2, cw - 6, ch - 4, 2, cursor ? PD_ROW_SELECTED : PD_LIST_FILL);
        }
        struct pd_mon m;
        if (pd_save_box_mon(&side->save, side->box, i, &m)) draw_mon(s, c, &m, cx + (cw - 32) / 2, cy + (ch - 32) / 2 - 1, held);
        lib_hit(cx, cy, cw, ch, HIT_SLOT + i);
    }
    pd_unclip(c);
    // The Pokémon picked up rides on the cursor, a little raised (the PC's hand).
    if (s->held >= 0 && s->cursor < SLOTS) {
        struct pd_mon m;
        if (pd_save_box_mon(&side->save, s->held / SLOTS, s->held % SLOTS, &m)) {
            int cx = gx + (s->cursor % COLS) * cw, cy = gy + (s->cursor / COLS) * ch;
            draw_mon(s, c, &m, cx + (cw - 32) / 2, cy + (ch - 32) / 2 - 9, false);
        }
    }
}

static void buttons(struct pd_library* l, struct pd_canvas* c, const char* const labels[BUTTONS], const int hits[BUTTONS], int on) {
    struct pd_library_saves* s = S(l);
    int y = LIB_H - LIB_M - BUTTON_H, gap = 4, w = (PAGE_W - gap * (BUTTONS - 1)) / BUTTONS;
    for (int i = 0; i < BUTTONS; i++) {
        bool picked = s->cursor == SLOTS + i || i == on || l->pressedHit == hits[i];
        lib_button(c, PAGE_X + i * (w + gap), y, i == BUTTONS - 1 ? PAGE_W - i * (w + gap) : w, BUTTON_H, labels[i], picked, hits[i]);
    }
}

static void number_dialog(struct pd_library_saves* s, struct pd_canvas* c) {
    lib_dim(c);
    lib_hit(0, 0, LIB_W, LIB_H, HIT_MESSAGE);
    int digits = number_digits(s), dw = 20, w = 232, h = 132, x = (LIB_W - w) / 2, y = (LIB_H - h) / 2;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    pd_text(c, x + f + 6, y + f + 4, s->numberOf == MONEY ? "MONEY" : "COINS", PD_VALUE, PD_VALUE_SHADOW);
    char text[12];
    snprintf(text, sizeof text, "%0*ld", digits, s->number);
    int dx = x + (w - digits * dw) / 2 + (s->numberOf == MONEY ? 6 : 0), dy = y + 44;
    if (s->numberOf == MONEY) pd_text(c, dx - 14, dy + 2, "$", PD_LABEL, PD_LABEL_SHADOW);
    for (int i = 0; i < digits; i++) {
        int cx = dx + i * dw;
        bool on = i == s->digit;
        pd_triangle(c, cx + 5, dy - 14, 5, PD_UP, on ? PD_VALUE : PD_MUTED);
        pd_triangle(c, cx + 5, dy + 22, 5, PD_DOWN, on ? PD_VALUE : PD_MUTED);
        if (on) pd_round_rect(c, cx, dy, dw - 2, 20, 3, PD_LIST_FILL);
        char d[2] = { text[i], 0 };
        pd_text(c, cx + (dw - 2 - pd_text_width(d)) / 2, dy + 2, d, PD_LABEL, PD_LABEL_SHADOW);
        lib_hit(cx - 2, dy - 18, dw, 18, HIT_DIGIT_UP + i);
        lib_hit(cx - 2, dy + 20, dw, 18, HIT_DIGIT_DOWN + i);
    }
    int by = y + h - f - BUTTON_H - 4, bw = 84;
    lib_button(c, x + w / 2 - bw - 6, by, bw, BUTTON_H, "OK", true, HIT_YES);
    lib_button(c, x + w / 2 + 6, by, bw, BUTTON_H, "CANCEL", false, HIT_NO);
}

static const char* tool_title(const struct pd_library_saves* s) {
    return s->page == PD_PAGE_EDITOR ? "SAVE EDITOR" : "TRADE SAVES";
}

void lib_saves_draw(struct pd_library* l, struct pd_canvas* c) {
    struct pd_library_saves* s = S(l);
    if (s->stage == PICK_A || s->stage == PICK_B) {
        const char* which = s->page == PD_PAGE_EDITOR ? "PICK A GAME" : s->stage == PICK_A ? "FIRST GAME" : "SECOND GAME";
        lib_title(c, tool_title(s), which, false);
        int top = TITLE_Y + TITLE_H + LIB_M;
        lib_list_draw(l, c, &s->pick, top, LIB_H - top - LIB_M, lib_grid(l), "No game here has a save these tools open.",
                      "FireRed, LeafGreen, Ruby, Sapphire, Emerald");
    } else if (s->stage == LOADING) {
        lib_title(c, tool_title(s), NULL, false);
        int f = pd_list_box(c, PAGE_X, 60, PAGE_W, 60);
        pd_text(c, PAGE_X + f + 10, 60 + f + 16, "Loading the game...", PD_LABEL, PD_LABEL_SHADOW);
        l->dirty = true;
    } else if (s->page == PD_PAGE_EDITOR) {
        struct side* side = &s->sides[0];
        lib_title(c, l->games[side->game].title, pd_save_changed(&side->save) ? "CHANGED" : NULL, false);
        int top = TITLE_Y + TITLE_H + LIB_M;
        box_view(l, c, side, top, LIB_H - top - BUTTON_H - 2 * LIB_M);
        static const char* const LABELS[BUTTONS] = { "MONEY", "COINS", "SAVE" };
        static const int HITS[BUTTONS] = { HIT_MONEY, HIT_COINS, HIT_WRITE };
        buttons(l, c, LABELS, HITS, -1);
    } else {
        // TRADE: a tab per side over its box, the battery in a window of its own at the right.
        int y = TITLE_Y, gap = 4, bw = lib_battery_width() ? lib_battery_width() + 14 : 0;
        int w = (PAGE_W - gap - (bw ? bw + gap : 0)) / 2;
        if (bw) lib_battery_box(c, PAGE_X + PAGE_W - bw, y, bw, TITLE_H);
        for (int i = 0; i < 2; i++) {
            const struct pd_library_game* g = &l->games[s->sides[i].game];
            int x = PAGE_X + i * (w + gap);
            bool on = s->active == i;
            pd_layered_two_tone(c, x, y, w, TITLE_H, 3, pd_cur->chip, pd_cur->chipCount, on ? pd_cur->tabSelectedFill : pd_cur->tabIdleFill, 0);
            // The games' own names without the series' (two of them share the row with the battery).
            const char* name = !strncmp(g->title, "Pokémon ", strlen("Pokémon ")) ? g->title + strlen("Pokémon ") : g->title;
            pd_text_fit(c, x + 6, pd_text_y(y, TITLE_H), w - 12 - (s->sides[i].mark >= 0 ? 10 : 0), name, on ? PD_VALUE : PD_LABEL,
                        on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
            if (s->sides[i].mark >= 0) pd_round_rect(c, x + w - 12, y + (TITLE_H - 6) / 2, 6, 6, 3, PD_VALUE);
            if (on) pd_fill(c, x + 6, y + TITLE_H - 5, w - 12, 2, PD_VALUE);
            lib_hit(x, y, w, TITLE_H, i ? HIT_SIDE_B : HIT_SIDE_A);
        }
        int top = TITLE_Y + TITLE_H + LIB_M;
        box_view(l, c, &s->sides[s->active], top, LIB_H - top - BUTTON_H - 2 * LIB_M);
        static const char* const LABELS[BUTTONS] = { "FIRST", "SECOND", "TRADE" };
        static const int HITS[BUTTONS] = { HIT_SIDE_A, HIT_SIDE_B, HIT_TRADE };
        buttons(l, c, LABELS, HITS, s->active);
    }
    if (s->dialog == NUMBER) number_dialog(s, c);
    else if (s->dialog == MESSAGE) lib_dialog(c, s->title, s->line1, s->line2[0] ? s->line2 : NULL, "OK", NULL);
    else if (s->dialog != NONE) lib_dialog(c, s->title, s->line1, s->line2[0] ? s->line2 : NULL, "YES", "NO");
}

// --- drawing: the top screen ---

#define TOP_W 400

static void mon_window(struct pd_library_saves* s, struct pd_canvas* c, const struct pd_mon* m, int x, int y, int w, int h, const char* heading,
                       const char* empty) {
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    if (heading) pd_text_fit(c, x + f + 6, y + f + 2, w - 2 * f - 12, heading, PD_MUTED, PD_MUTED_SHADOW);
    int top = y + f + (heading ? 20 : 4);
    if (!m) {
        pd_text_fit(c, x + f + 6, top + 4, w - 2 * f - 12, empty, PD_LABEL, PD_LABEL_SHADOW);
        return;
    }
    // The icon at twice its size on a well, then who it is.
    int well = 70;
    pd_round_rect(c, x + f + 4, top, well, well, 4, PD_LIST_FILL);
    int species = m->egg ? PD_SPECIES_EGG : m->species;
    if (!s->icons || !pd_draw_mon_icon_scaled(c, &s->names, species, x + f + 4 + 3, top - 1, 2, false)) pd_draw_party_ball(c, x + f + 4 + 25, top + 25);
    int tx = x + f + well + 10, tw = x + w - f - 6 - tx;
    char text[48], name[24];
    pd_text_fit(c, tx, top, tw, m->nickname, PD_LABEL, PD_LABEL_SHADOW);
    if (m->egg) snprintf(text, sizeof text, "EGG");
    else snprintf(text, sizeof text, "Lv%d  %s", m->level, pd_species_name(&s->names, m->species, name, sizeof name));
    pd_text_fit(c, tx, top + 18, tw, text, PD_VALUE, PD_VALUE_SHADOW);
    if (!m->egg) {
        snprintf(text, sizeof text, "%s", pd_nature_names[m->personality % 25]);
        pd_text_fit(c, tx, top + 36, tw, text, PD_LABEL, PD_LABEL_SHADOW);
        const char* item = m->heldItem ? pd_item_name(&s->names, m->heldItem) : NULL;
        snprintf(text, sizeof text, "ITEM  %s", item && item[0] ? item : "NONE");
        pd_text_fit(c, tx, top + 54, tw, text, PD_MUTED, PD_MUTED_SHADOW);
    }
    // Its moves, two to a row.
    int my = top + well + 6;
    for (int i = 0; !m->egg && i < PD_NUM_MOVES && my + 18 <= y + h - f; i++) {
        if (!m->moves[i]) continue;
        int mx = x + f + 6 + (i % 2) * ((w - 2 * f - 12) / 2), ry = my + (i / 2) * 18;
        if (ry + 16 > y + h - f) break;
        pd_text_fit(c, mx, ry, (w - 2 * f - 12) / 2 - 4, pd_move_name(&s->names, m->moves[i], name, sizeof name), PD_LABEL, PD_LABEL_SHADOW);
    }
}

static void tool_help(struct pd_library_saves* s, struct pd_canvas* c) {
    lib_top_title(c, s->page, tool_title(s), NULL);
    int lf = pd_list_box(c, TOP_M, TOP_BODY_Y, TOP_W - 2 * TOP_M, TOP_BODY_END - TOP_BODY_Y);
    static const char* const EDIT[] = {
        "Move Pokémon between your PC boxes and change",
        "your money and coins, then SAVE. The game writes",
        "its save the way it always does, and a backup",
        "of the old one is kept first.",
        "",
        "FireRed, LeafGreen, Ruby, Sapphire and Emerald.",
    };
    static const char* const TRADE[] = {
        "Trade Pokémon between two saves, PC box to PC",
        "box, by the games' own rules: trade evolutions,",
        "the Pokédex, and what each game takes before",
        "its National Dex. Both saves are written.",
        "",
        "FireRed, LeafGreen, Ruby, Sapphire and Emerald.",
    };
    const char* const* lines = s->page == PD_PAGE_EDITOR ? EDIT : TRADE;
    for (int i = 0; i < 6; i++) pd_text_fit(c, TOP_M + lf + 8, TOP_BODY_Y + lf + 6 + i * 20, TOP_W - 2 * TOP_M - 2 * lf - 16, lines[i], i == 5 ? PD_MUTED : PD_LABEL,
                                            i == 5 ? PD_MUTED_SHADOW : PD_LABEL_SHADOW);
}

void lib_saves_draw_top(struct pd_library* l, struct pd_canvas* c) {
    struct pd_library_saves* s = S(l);
    lib_page_backdrop(c);
    if (s->stage == PICK_A || s->stage == PICK_B || s->stage == LOADING) {
        tool_help(s, c);
        lib_hint_bar(c, s->stage == LOADING ? "Loading..." : s->stage == PICK_B ? "A: OPEN THE SECOND SAVE   B: BACK" : "A: OPEN ITS SAVE   B: GAMES");
        return;
    }
    if (s->page == PD_PAGE_EDITOR) {
        struct side* side = &s->sides[0];
        struct pd_mon m;
        int at = s->held >= 0 ? s->held : s->cursor < SLOTS ? side->box * SLOTS + s->cursor : -1;
        bool has = at >= 0 && pd_save_box_mon(&side->save, at / SLOTS, at % SLOTS, &m);
        mon_window(s, c, has ? &m : NULL, TOP_M, TOP_M, 236, TOP_BODY_END - TOP_M, s->held >= 0 ? "MOVING" : NULL, "An empty place.");
        // The save at a glance, and what's changed.
        int x = TOP_M + 242, w = TOP_W - TOP_M - x;
        int f = pd_list_box(c, x, TOP_M, w, TOP_BODY_END - TOP_M);
        char text[40], name[16];
        pd_save_trainer_name(&side->save, name, sizeof name);
        int ty = TOP_M + f + 4;
        pd_text(c, x + f + 6, ty, "PLAYER", PD_LABEL, PD_LABEL_SHADOW);
        pd_text_fit(c, x + f + 6, ty + 16, w - 2 * f - 12, name, PD_VALUE, PD_VALUE_SHADOW);
        snprintf(text, sizeof text, "$%ld", pd_save_money(&side->save));
        pd_text(c, x + f + 6, ty + 38, "MONEY", PD_LABEL, PD_LABEL_SHADOW);
        pd_text_fit(c, x + f + 6, ty + 54, w - 2 * f - 12, text, PD_VALUE, PD_VALUE_SHADOW);
        snprintf(text, sizeof text, "%d", pd_save_coins(&side->save));
        pd_text(c, x + f + 6, ty + 76, "COINS", PD_LABEL, PD_LABEL_SHADOW);
        pd_text_fit(c, x + f + 6, ty + 92, w - 2 * f - 12, text, PD_VALUE, PD_VALUE_SHADOW);
        if (pd_save_changed(&side->save)) pd_text_fit(c, x + f + 6, ty + 122, w - 2 * f - 12, "Not saved yet", PD_MUTED, PD_MUTED_SHADOW);
        lib_hint_bar(c, s->held >= 0 ? "A: PUT IT HERE   L R: BOXES   B: PUT IT BACK" : "A: PICK UP   L R: BOXES   X: SAVE   B: CLOSE");
        return;
    }
    // TRADE: each side's pick, the arrow between them, and whether the games would do it.
    struct pd_mon ms[2];
    bool has[2];
    for (int i = 0; i < 2; i++) {
        struct side* side = &s->sides[i];
        has[i] = side->mark >= 0 && pd_save_box_mon(&side->save, side->mark / SLOTS, side->mark % SLOTS, &ms[i]);
        char heading[72];
        snprintf(heading, sizeof heading, "%s", l->games[side->game].title);
        mon_window(s, c, has[i] ? &ms[i] : NULL, i ? TOP_W / 2 + 4 : TOP_M, TOP_M, TOP_W / 2 - TOP_M - 4, 160, heading, side->mark >= 0 ? "An empty place: send here." : "Pick one in its box.");
    }
    pd_triangle(c, TOP_W / 2 - 2, 72, 3, PD_RIGHT, PD_ON_BACKDROP);
    pd_triangle(c, TOP_W / 2 - 1, 82, 3, PD_LEFT, PD_ON_BACKDROP);
    int sy = TOP_M + 160 + 6;
    int f = pd_list_box(c, TOP_M, sy, TOP_W - 2 * TOP_M, TOP_BODY_END - sy);
    const char* status = "Pick a Pokémon on either side, then TRADE.";
    char why[160] = "";
    struct side *a = &s->sides[0], *b = &s->sides[1];
    if (a->mark >= 0 && b->mark >= 0 && (has[0] || has[1])) {
        const char* no = has[0] ? pd_trade_blocker(&a->trade, a->mark / SLOTS, a->mark % SLOTS, &b->trade, why, sizeof why) : NULL;
        if (!no && has[1]) no = pd_trade_blocker(&b->trade, b->mark / SLOTS, b->mark % SLOTS, &a->trade, why, sizeof why);
        int evolves = has[0] ? pd_trade_evolution(&a->trade, a->mark / SLOTS, a->mark % SLOTS, &b->trade) : 0;
        if (!evolves && has[1]) evolves = pd_trade_evolution(&b->trade, b->mark / SLOTS, b->mark % SLOTS, &a->trade);
        if (no) status = no;
        else if (evolves) {
            snprintf(why, sizeof why, "Ready. It evolves into %s on arrival.", evolves < pd_species_names_count ? pd_species_names[evolves] : "?");
            status = why;
        } else status = "Ready to trade.";
    }
    pd_text_wrap(c, TOP_M + f + 8, sy + f + 2, TOP_W - 2 * TOP_M - 2 * f - 16, 16, 2, status, PD_LABEL, PD_LABEL_SHADOW);
    lib_hint_bar(c, "A: PICK   L R: BOXES   X: OTHER SIDE   Y: TRADE   B: CLOSE");
}
