// SAVE EDITOR and TRADE SAVES on the 3DS: the app's library tools (SaveEditorActivity's pages and TRADE), cut down to what
// fits a 320x240 touch screen and the D-pad. The save is the file beside the ROM (the game isn't running here, so nothing
// else writes it); every write goes the game's way (pd_save_build: the other slot, read back) and the host backs the file up
// first. Retail English / European FireRed, LeafGreen, Ruby, Sapphire and Emerald saves, as in the app.
//
// SAVE EDITOR has the app's three tabs (SELECT or a tap steps them):
// - POKéMON: the party and the 14 boxes (L / R), A picks a Pokémon up and puts it down (party <-> PC by the PC's rules),
//   Y (or EDIT) opens its page - level, nature, gender, shiny, ability, friendship, IVs, EVs, nickname, item, moves,
//   RELEASE - each value only one the game could give it; BOX renames or sorts the box.
// - BAG: the pockets (L / R), A changes a stack's count, Y adds an item the game files there; key items and HMs stay.
// - TRAINER: money, coins, the Pokédex (register what the save holds, mark all seen / caught) and the event tickets.
// TRADE SAVES: a tab per side over its party or a box, a Pokémon (or an empty place) picked on each, Y trades.
//
// The touch screen shows what's under work, the top screen the Pokémon / item / trainer on the cursor.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "pd_game.h"
#include "pd_icon.h"
#include "pd_library_internal.h"
#include "pd_save.h"
#include "pd_stats.h"
#include "pd_tables.h"
#include "pd_ui.h"

enum stage { PICK_A, PICK_B, LOADING, OPEN };
enum tab { TAB_MONS, TAB_BAG, TAB_TRAINER, TABS };
enum dialog { NONE, CONFIRM, MESSAGE, NUMBER, PICK };
enum confirm_of { C_WRITE, C_EVOLVE, C_LEAVE, C_RELEASE, C_REGISTER, C_SEEN, C_CAUGHT, C_EVENT, C_QUIT, C_TRADE_BACK };
enum number_of { N_MONEY, N_COINS, N_LEVEL, N_FRIENDSHIP, N_IV, N_EV, N_QUANTITY, N_ADD };
enum pick_of { P_NATURE, P_ITEM, P_MOVE, P_ADD_ITEM, P_BOX_MENU };
#define BOX_SLOTS PD_SAVE_BOX_SLOTS
#define PARTY_VIEW (-1) // side->box: the party, then the boxes 0..13
#define BUTTONS 3       // the row under the box
#define ROW_H 18
#define PICK_MAX (PD_SAVE_LAST_MOVE + 8)
#define LABEL_LEN 28

// A Pokémon's page: its rows (groups have no value and the cursor skips them).
enum row {
    R_LEVEL, R_NATURE, R_GENDER, R_SHINY, R_ABILITY, R_FRIENDSHIP, R_NICKNAME, R_ITEM, G_IVS, R_IV, G_EVS = R_IV + 6, R_EV,
    G_MOVES = R_EV + 6, R_MOVE, R_RELEASE = R_MOVE + 4, ROW_COUNT,
};
// TRAINER's rows.
enum trainer_row { T_MONEY, T_COINS, TG_DEX, T_REGISTER, T_SEEN, T_CAUGHT, T_MISSING, TG_EVENTS, T_EVENT };
#define MISSING_MAX 400

struct side {
    int game;
    struct pd_save save;
    int box;  // PARTY_VIEW or a box
    int mark; // TRADE: this side's pick (an `at`), PD_AT_NONE = none
    struct pd_trade_side trade;
};

struct pd_library_saves {
    int page, stage, loadingFrames;
    struct lib_list pick;
    struct side sides[2];
    int active;         // the side the touch screen shows (TRADE)
    int tab;            // SAVE EDITOR's
    int cursor, button; // a slot of the view shown, or (button >= 0) a button under it
    int held;           // SAVE EDITOR: the Pokémon picked up (an `at`), PD_AT_NONE = none
    // A Pokémon's page: whose, the row, the list's scroll.
    int monAt, row, rowScroll;
    bool monPage;
    // BAG: the pocket, the row, the scroll. TRAINER: the row, the scroll.
    int pocket, bagRow, bagScroll, trainerRow, trainerScroll;
    // TRAINER > MISSING: the list (pd_missing), its other saves' games by source, the row, the scroll.
    bool missingOpen;
    int missingCount, missingRow, missingScroll, missingSources;
    int missingGames[PD_LIBRARY_MAX];
    struct pd_game names; // side 0's game, for its names and (with its ROM loaded) its icons
    bool icons;
    int dialog, confirmOf, confirmArg;
    char title[32], line1[120], line2[120];
    const char *yes, *no; // the confirm's buttons
    char tradeNote[120];  // TRADE: what the last trade did (the top screen's status)
    // NUMBER: what for (and which IV / EV / stack), the digit, the value, its range.
    int numberOf, numberArg, digit, digits;
    long number, numberMin, numberMax;
    // PICK: what for, its choices (ids) and their labels, the cursor and the scroll.
    int pickOf, pickArg, pickCount, pickSelected, pickScroll;
    uint16_t pickIds[PICK_MAX];
    char pickLabels[PICK_MAX][LABEL_LEN];
    char pickTitle[32];
};

static struct pd_library_saves state;
static uint8_t written[2][PD_SAVE_MAX_SIZE];
static uint8_t readBuf[PD_SAVE_MAX_SIZE];
static struct pd_missing missing[MISSING_MAX];
static struct pd_save missingSave; // each other save in turn, read for MISSING

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
    state.held = PD_AT_NONE;
    state.monPage = false;
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
    s->held = PD_AT_NONE;
    s->monPage = false;
    s->missingOpen = false;
    s->pick.count = 0;
    s->pick.scroll = 0;
    fill_pick(l);
}

struct lib_list* lib_saves_list(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    return s->stage == PICK_A || s->stage == PICK_B ? &s->pick : NULL;
}

int* lib_saves_scroll(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    if (s->stage != OPEN) return NULL;
    if (s->dialog == PICK) return &s->pickScroll;
    if (s->dialog != NONE || s->page != PD_PAGE_EDITOR) return NULL;
    if (s->monPage) return &s->rowScroll;
    if (s->tab == TAB_BAG) return &s->bagScroll;
    if (s->tab == TAB_TRAINER) return &s->trainerScroll;
    return NULL;
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

// An edit's answer: nothing to say when it went through, else why not.
static bool done_or_why(struct pd_library_saves* s, const char* why) {
    if (why) message(s, "THE GAME WOULDN'T", why, NULL);
    return !why;
}

static void confirm(struct pd_library_saves* s, int of, int arg, const char* title, const char* line1, const char* line2) {
    s->dialog = CONFIRM;
    s->confirmOf = of;
    s->confirmArg = arg;
    s->yes = "YES";
    s->no = "NO";
    snprintf(s->title, sizeof s->title, "%s", title);
    snprintf(s->line1, sizeof s->line1, "%s", line1 ? line1 : "");
    snprintf(s->line2, sizeof s->line2, "%s", line2 ? line2 : "");
}

// --- names ---

static const char* species_text(struct pd_library_saves* s, int species, char* buf, size_t len) {
    return pd_species_name(&s->names, species, buf, len);
}

static const char* move_text(struct pd_library_saves* s, int move, char* buf, size_t len) {
    if (!move) return "-";
    return pd_move_name(&s->names, move, buf, len);
}

static const char* item_text(struct pd_library_saves* s, int item, char* buf, size_t len) {
    if (!item) return "NONE";
    const char* name = pd_item_name(&s->names, item);
    if (name && name[0]) snprintf(buf, len, "%s", name);
    else snprintf(buf, len, "ITEM %d", item);
    return buf;
}

static const char* money_text(long v, char* buf, size_t len) {
    char n[24];
    pd_ui_format_money(v, n, sizeof n);
    snprintf(buf, len, "$%s", n);
    return buf;
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
    side->mark = PD_AT_NONE;
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
    s->tab = TAB_MONS;
    s->cursor = 0;
    s->button = -1;
    s->held = PD_AT_NONE;
    s->monPage = false;
    s->missingOpen = false;
    s->pocket = PD_BAG_ITEMS;
    s->bagRow = s->bagScroll = s->trainerScroll = 0;
    s->trainerRow = T_MONEY;
}

void lib_saves_tick(struct pd_library* l) {
    struct pd_library_saves* s = l->saves;
    if (!s || s->stage != LOADING || ++s->loadingFrames < 2) return;
    load_names(l, s->sides[0].game);
    s->stage = OPEN;
    l->dirty = true;
}

// --- the party / box view ---

static struct side* active_side(struct pd_library_saves* s) {
    return &s->sides[s->page == PD_PAGE_TRADE ? s->active : 0];
}

static int view_cols(const struct side* side) { return side->box == PARTY_VIEW ? 2 : 6; }
static int view_rows(const struct side* side) { return side->box == PARTY_VIEW ? 3 : 5; }
static int view_slots(const struct side* side) { return view_cols(side) * view_rows(side); }

static int at_of(const struct side* side, int slot) {
    return side->box == PARTY_VIEW ? PD_AT_PARTY(slot) : PD_AT_BOX(side->box, slot);
}

static int cursor_at(struct pd_library_saves* s) {
    return s->button >= 0 ? PD_AT_NONE : at_of(active_side(s), s->cursor);
}

static void step_box(struct pd_library_saves* s, int step) {
    struct side* side = active_side(s);
    // PARTY, BOX 1 .. BOX 14, round again.
    int n = PD_SAVE_BOXES + 1, i = side->box + 1;
    side->box = (i + step + n) % n - 1;
    if (s->cursor >= view_slots(side)) s->cursor = view_slots(side) - 1;
}

static void move_cursor(struct pd_library_saves* s, int dx, int dy) {
    struct side* side = active_side(s);
    int cols = view_cols(side), rows = view_rows(side);
    if (s->button >= 0) {
        if (dy < 0) {
            s->cursor = (rows - 1) * cols + s->button * cols / BUTTONS;
            s->button = -1;
        } else if (dx) s->button = (s->button + dx + BUTTONS) % BUTTONS;
        return;
    }
    int col = s->cursor % cols, row = s->cursor / cols;
    if (dx) col = (col + dx + cols) % cols;
    if (dy) {
        row += dy;
        if (row >= rows) {
            s->button = col * BUTTONS / cols;
            return;
        }
        if (row < 0) row = rows - 1;
    }
    s->cursor = row * cols + col;
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

static void ask_write(struct pd_library_saves* s) {
    if (pd_save_changed(&s->sides[0].save))
        confirm(s, C_WRITE, 0, "SAVE", "Write these changes to the save?", "A backup of it is kept first.");
    else message(s, "NOTHING CHANGED", "Change a Pokémon, the bag or the trainer", "first.");
}

// --- the number window ---

static void edit_number(struct pd_library_saves* s, int of, int arg, long value, long min, long max) {
    s->dialog = NUMBER;
    s->numberOf = of;
    s->numberArg = arg;
    s->numberMin = min;
    s->numberMax = max;
    s->number = value;
    s->digits = 1;
    for (long m = max; m >= 10; m /= 10) s->digits++;
    s->digit = s->digits - 1;
}

static void number_step(struct pd_library_saves* s, int digit, int step) {
    long p = 1;
    for (int i = 0; i < s->digits - 1 - digit; i++) p *= 10;
    long d = s->number / p % 10, next = (d + step + 10) % 10;
    s->number += (next - d) * p;
    if (s->number > s->numberMax) s->number = s->numberMax;
}

static const char* const STAT_NAMES[6] = { "HP", "ATTACK", "DEFENSE", "SPEED", "SP. ATK", "SP. DEF" };

static const char* number_title(const struct pd_library_saves* s) {
    static char buf[32];
    switch (s->numberOf) {
    case N_MONEY: return "MONEY";
    case N_COINS: return "COINS";
    case N_LEVEL: return "LEVEL";
    case N_FRIENDSHIP: return "FRIENDSHIP";
    case N_IV: snprintf(buf, sizeof buf, "%s IV", STAT_NAMES[s->numberArg]); return buf;
    case N_EV: snprintf(buf, sizeof buf, "%s EV", STAT_NAMES[s->numberArg]); return buf;
    default: return "HOW MANY";
    }
}

static void number_done(struct pd_library_saves* s, bool ok) {
    s->dialog = NONE;
    if (!ok) return;
    struct pd_save* sv = &s->sides[0].save;
    long v = s->number;
    if (v < s->numberMin || v > s->numberMax) {
        char line[64];
        snprintf(line, sizeof line, "From %ld to %ld.", s->numberMin, s->numberMax);
        message(s, "NOT THAT ONE", line, NULL);
        return;
    }
    int at = s->monAt, ivs[6], evs[6];
    struct pd_box_mon m;
    switch (s->numberOf) {
    case N_MONEY: pd_save_set_money(sv, v); break;
    case N_COINS: pd_save_set_coins(sv, (int) v); break;
    case N_LEVEL: done_or_why(s, pd_save_set_level(sv, at, (int) v)); break;
    case N_FRIENDSHIP: done_or_why(s, pd_save_set_friendship(sv, at, (int) v)); break;
    case N_IV:
        pd_save_record_at(sv, at, &m);
        pd_box_mon_ivs(&m, ivs);
        ivs[s->numberArg] = (int) v;
        done_or_why(s, pd_save_set_ivs(sv, at, ivs));
        break;
    case N_EV:
        pd_save_record_at(sv, at, &m);
        pd_box_mon_evs(&m, evs);
        evs[s->numberArg] = (int) v;
        done_or_why(s, pd_save_set_evs(sv, at, evs));
        break;
    case N_QUANTITY: done_or_why(s, pd_save_set_quantity(sv, (enum pd_save_pocket) s->pocket, s->numberArg, (int) v)); break;
    case N_ADD: done_or_why(s, pd_save_add_item(sv, (enum pd_save_pocket) s->pocket, s->numberArg, (int) v)); break;
    }
}

// --- pick-lists ---

static int cmp_labels(const void* a, const void* b) {
    int ia = *(const int*) a, ib = *(const int*) b;
    return strcmp(state.pickLabels[ia], state.pickLabels[ib]);
}

// Sorts the pick-list's choices by their labels, keeping the first [first] where they are (NONE).
static void sort_pick(struct pd_library_saves* s, int first) {
    static int order[PICK_MAX];
    static uint16_t ids[PICK_MAX];
    static char labels[PICK_MAX][LABEL_LEN];
    int n = s->pickCount - first;
    for (int i = 0; i < n; i++) order[i] = first + i;
    qsort(order, (size_t) n, sizeof order[0], cmp_labels);
    for (int i = 0; i < n; i++) {
        ids[i] = s->pickIds[order[i]];
        memcpy(labels[i], s->pickLabels[order[i]], LABEL_LEN);
    }
    for (int i = 0; i < n; i++) {
        s->pickIds[first + i] = ids[i];
        memcpy(s->pickLabels[first + i], labels[i], LABEL_LEN);
    }
}

static void pick_add(struct pd_library_saves* s, int id, const char* label) {
    if (s->pickCount >= PICK_MAX) return;
    s->pickIds[s->pickCount] = (uint16_t) id;
    snprintf(s->pickLabels[s->pickCount], LABEL_LEN, "%s", label);
    s->pickCount++;
}

static void open_pick(struct pd_library_saves* s, int of, int arg, const char* title, int current) {
    s->dialog = PICK;
    s->pickOf = of;
    s->pickArg = arg;
    snprintf(s->pickTitle, sizeof s->pickTitle, "%s", title);
    s->pickSelected = 0;
    for (int i = 0; i < s->pickCount; i++)
        if (s->pickIds[i] == current) s->pickSelected = i;
    s->pickScroll = s->pickSelected * ROW_H - 3 * ROW_H;
}

static void pick_item(struct pd_library_saves* s) {
    struct pd_box_mon m;
    if (!pd_save_record_at(&s->sides[0].save, s->monAt, &m)) return;
    if (pd_save_is_mail(pd_box_mon_item(&m))) {
        message(s, "MAIL", "A Pokémon keeps its mail: take it off in", "the game.");
        return;
    }
    static uint16_t items[400];
    int n = pd_save_holdable(&s->sides[0].save, items, 400);
    char name[LABEL_LEN];
    s->pickCount = 0;
    pick_add(s, 0, "NONE");
    int now = pd_box_mon_item(&m);
    bool listed = now == 0;
    for (int i = 0; i < n; i++) {
        pick_add(s, items[i], item_text(s, items[i], name, sizeof name));
        listed |= items[i] == now;
    }
    if (!listed) pick_add(s, now, item_text(s, now, name, sizeof name)); // what it holds stays offered
    sort_pick(s, 1);
    open_pick(s, P_ITEM, 0, "ITEM", now);
}

static void pick_move(struct pd_library_saves* s, int slot) {
    struct pd_box_mon m;
    if (!pd_save_record_at(&s->sides[0].save, s->monAt, &m)) return;
    uint8_t learn[(PD_SAVE_LAST_MOVE + 8) / 8];
    pd_save_learnable(&s->sides[0].save, s->monAt, learn);
    int known[4], count = 0;
    for (int i = 0; i < 4; i++) {
        known[i] = pd_box_mon_move(&m, i);
        count += known[i] != 0;
    }
    char name[LABEL_LEN];
    s->pickCount = 0;
    // FORGET IT (never the last one), then the moves it knows or can learn, none twice.
    if (known[slot] && count > 1) pick_add(s, 0, "FORGET IT");
    for (int mv = 1; mv <= PD_SAVE_LAST_MOVE; mv++) {
        bool knows = false, other = false;
        for (int i = 0; i < 4; i++) {
            knows |= known[i] == mv;
            other |= known[i] == mv && i != slot;
        }
        if (other || (!knows && !(learn[mv / 8] >> (mv % 8) & 1))) continue;
        pick_add(s, mv, move_text(s, mv, name, sizeof name));
    }
    sort_pick(s, s->pickCount && s->pickIds[0] == 0 ? 1 : 0);
    char title[24];
    snprintf(title, sizeof title, "MOVE %d", slot + 1);
    open_pick(s, P_MOVE, slot, title, known[slot]);
}

static void pick_nature(struct pd_library_saves* s) {
    struct pd_box_mon m;
    if (!pd_save_record_at(&s->sides[0].save, s->monAt, &m)) return;
    s->pickCount = 0;
    for (int i = 0; i < 25; i++) pick_add(s, i, pd_nature_names[i]);
    open_pick(s, P_NATURE, 0, "NATURE", (int) (pd_box_mon_personality(&m) % 25));
}

static void pick_add_item(struct pd_library_saves* s) {
    struct pd_save* sv = &s->sides[0].save;
    enum pd_save_pocket p = (enum pd_save_pocket) s->pocket;
    if (p == PD_BAG_KEY) {
        message(s, "KEY ITEMS", "Key items come from the story: they aren't", "added here. The events are on TRAINER.");
        return;
    }
    struct pd_stack st[64];
    if (pd_save_pocket(sv, p, st, 64) >= pd_save_pocket_slots(sv, p)) {
        message(s, "POCKET FULL", "Take something out of this pocket first.", NULL);
        return;
    }
    static uint16_t items[400];
    int n = pd_save_addable(sv, p, items, 400);
    if (!n) {
        message(s, "NOTHING TO ADD", "This pocket holds every item the game", "files in it.");
        return;
    }
    char name[LABEL_LEN];
    s->pickCount = 0;
    for (int i = 0; i < n; i++) pick_add(s, items[i], item_text(s, items[i], name, sizeof name));
    if (p != PD_BAG_TMHM) sort_pick(s, 0); // TMs keep their numbers' order
    open_pick(s, P_ADD_ITEM, 0, "ADD AN ITEM", -1);
}

static void pick_box_menu(struct pd_library_saves* s) {
    struct side* side = active_side(s);
    if (side->box == PARTY_VIEW) {
        message(s, "THE PARTY", "Sort and rename are for the PC's boxes.", "Move a Pokémon with A.");
        return;
    }
    s->pickCount = 0;
    pick_add(s, 0, "RENAME THE BOX");
    pick_add(s, 1, "SORT BY POKéDEX NUMBER");
    pick_add(s, 2, "SORT BY LEVEL");
    pick_add(s, 3, "SORT BY NAME");
    char name[16];
    pd_save_box_name(&side->save, side->box, name, sizeof name);
    open_pick(s, P_BOX_MENU, 0, name, -1);
}

static void type_name(struct pd_library* l, bool box) {
    struct pd_library_saves* s = S(l);
    const struct pd_library_host* h = l->host;
    struct pd_save* sv = &s->sides[0].save;
    char initial[32], out[64];
    if (box) pd_save_box_name(sv, s->sides[0].box, initial, sizeof initial);
    else {
        struct pd_mon m;
        pd_save_mon_at(sv, s->monAt, &m);
        snprintf(initial, sizeof initial, "%s", m.nickname);
    }
    if (!h || !h->edit_text) {
        message(s, "NO KEYBOARD", "Names can't be typed here.", NULL);
        return;
    }
    if (!h->edit_text(h->ctx, box ? "Box name (8 letters)" : "Nickname (10 letters)", initial, box ? 8 : 10, out, sizeof out)) return;
    done_or_why(s, box ? pd_save_rename_box(sv, s->sides[0].box, out) : pd_save_set_nickname(sv, s->monAt, out));
}

static void pick_done(struct pd_library* l, bool ok) {
    struct pd_library_saves* s = S(l);
    s->dialog = NONE;
    if (!ok || s->pickSelected < 0 || s->pickSelected >= s->pickCount) return;
    struct pd_save* sv = &s->sides[0].save;
    int id = s->pickIds[s->pickSelected];
    switch (s->pickOf) {
    case P_NATURE: done_or_why(s, pd_save_reroll(sv, s->monAt, id, -1, -1, -1)); break;
    case P_ITEM: done_or_why(s, pd_save_set_held_item(sv, s->monAt, id)); break;
    case P_MOVE: {
        struct pd_box_mon m;
        pd_save_record_at(sv, s->monAt, &m);
        int moves[4], n = 0;
        for (int i = 0; i < 4; i++) {
            int mv = i == s->pickArg ? id : pd_box_mon_move(&m, i);
            if (mv) moves[n++] = mv;
        }
        done_or_why(s, pd_save_set_moves(sv, s->monAt, moves, n));
        break;
    }
    case P_ADD_ITEM: edit_number(s, N_ADD, id, 1, 1, pd_save_stack_max(sv, (enum pd_save_pocket) s->pocket, id)); break;
    case P_BOX_MENU:
        if (id == 0) type_name(l, true);
        else pd_save_sort_box(sv, s->sides[0].box, id == 1 ? PD_SORT_DEX : id == 2 ? PD_SORT_LEVEL : PD_SORT_NAME);
        break;
    }
}

// --- a Pokémon's page ---

static bool row_shown(const struct pd_library_saves* s, int r) {
    return r != R_RELEASE || !PD_AT_IS_PARTY(s->monAt);
}

static bool row_group(int r) { return r == G_IVS || r == G_EVS || r == G_MOVES; }

static void open_mon_page(struct pd_library_saves* s, int at) {
    if (at == PD_AT_NONE) return;
    const char* why = pd_save_editable(&s->sides[0].save, at);
    if (why) {
        message(s, "NOT THIS ONE", why, NULL);
        return;
    }
    s->monAt = at;
    s->monPage = true;
    s->row = R_LEVEL;
    s->rowScroll = 0;
}

// The next / previous Pokémon of the same box or party that can be edited (L / R on its page).
static void step_mon(struct pd_library_saves* s, int step) {
    struct side* side = &s->sides[0];
    int slots = side->box == PARTY_VIEW ? PD_SAVE_PARTY : BOX_SLOTS;
    int slot = PD_AT_IS_PARTY(s->monAt) ? PD_AT_PARTY_INDEX(s->monAt) : s->monAt % BOX_SLOTS;
    for (int k = 1; k < slots; k++) {
        int i = (slot + step * k + slots * 2) % slots, at = at_of(side, i);
        if (!pd_save_editable(&side->save, at)) {
            s->monAt = at;
            s->cursor = i;
            if (s->row == R_RELEASE && PD_AT_IS_PARTY(at)) s->row = R_LEVEL;
            return;
        }
    }
}

static void page_step(struct pd_library_saves* s, int dir) {
    struct pd_save* sv = &s->sides[0].save;
    int at = s->monAt, r = s->row;
    struct pd_box_mon m;
    if (!pd_save_record_at(sv, at, &m)) return;
    int species = pd_box_mon_species(&m), ivs[6], evs[6];
    uint32_t p = pd_box_mon_personality(&m);
    pd_box_mon_ivs(&m, ivs);
    pd_box_mon_evs(&m, evs);
    if (r == R_LEVEL) {
        int lv = pd_save_level_at(sv, at) + dir, min = pd_save_level_as_read(sv, at);
        if (lv >= min && lv <= 100) done_or_why(s, pd_save_set_level(sv, at, lv));
    } else if (r == R_NATURE) {
        done_or_why(s, pd_save_reroll(sv, at, (int) ((p % 25 + 25 + (unsigned) dir) % 25), -1, -1, -1));
    } else if (r == R_GENDER) {
        if (pd_save_both_genders(species))
            done_or_why(s, pd_save_reroll(sv, at, -1, -1, pd_save_gender_of(species, p) == PD_SAVE_MALE ? PD_SAVE_FEMALE : PD_SAVE_MALE, -1));
    } else if (r == R_SHINY) {
        done_or_why(s, pd_save_reroll(sv, at, -1, pd_save_is_shiny(p, pd_box_mon_ot_id(&m)) ? 0 : 1, -1, -1));
    } else if (r == R_ABILITY) {
        if (pd_save_ability_count(species) == 2) done_or_why(s, pd_save_reroll(sv, at, -1, -1, -1, 1 - pd_box_mon_ability_num(&m)));
    } else if (r == R_FRIENDSHIP) {
        int v = pd_box_mon_friendship(&m) + dir;
        if (v >= 0 && v <= 255) pd_save_set_friendship(sv, at, v);
    } else if (r >= R_IV && r < R_IV + 6) {
        int i = r - R_IV, v = ivs[i] + dir;
        if (v >= 0 && v <= 31) {
            ivs[i] = v;
            pd_save_set_ivs(sv, at, ivs);
        }
    } else if (r >= R_EV && r < R_EV + 6) {
        int i = r - R_EV, v = evs[i] + dir, total = 0;
        for (int k = 0; k < 6; k++) total += evs[k];
        if (v >= 0 && v <= 255 && total + dir <= 510) {
            evs[i] = v;
            pd_save_set_evs(sv, at, evs);
        }
    }
}

static void page_act(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    struct pd_save* sv = &s->sides[0].save;
    int at = s->monAt, r = s->row;
    struct pd_box_mon m;
    if (!pd_save_record_at(sv, at, &m)) return;
    int ivs[6], evs[6], total = 0;
    pd_box_mon_ivs(&m, ivs);
    pd_box_mon_evs(&m, evs);
    for (int i = 0; i < 6; i++) total += evs[i];
    if (r == R_LEVEL) edit_number(s, N_LEVEL, 0, pd_save_level_at(sv, at), pd_save_level_as_read(sv, at), 100);
    else if (r == R_NATURE) pick_nature(s);
    else if (r == R_GENDER || r == R_SHINY || r == R_ABILITY) page_step(s, 1);
    else if (r == R_FRIENDSHIP) edit_number(s, N_FRIENDSHIP, 0, pd_box_mon_friendship(&m), 0, 255);
    else if (r >= R_IV && r < R_IV + 6) edit_number(s, N_IV, r - R_IV, ivs[r - R_IV], 0, 31);
    else if (r >= R_EV && r < R_EV + 6) {
        int i = r - R_EV, room = 510 - total + evs[i];
        edit_number(s, N_EV, i, evs[i], 0, room < 255 ? room : 255);
    } else if (r == R_NICKNAME) {
        if (!pd_save_is_own(sv, at)) message(s, "NOT YOURS", "Only the trainer who caught it can rename", "it (the Name Rater's rule).");
        else type_name(l, false);
    } else if (r == R_ITEM) pick_item(s);
    else if (r >= R_MOVE && r < R_MOVE + 4) {
        int slot = r - R_MOVE, count = 0;
        for (int i = 0; i < 4; i++) count += pd_box_mon_move(&m, i) != 0;
        pick_move(s, slot < count ? slot : count); // an empty place: the next move
    } else if (r == R_RELEASE) {
        struct pd_mon mm;
        char name[24], line[96];
        pd_save_mon_at(sv, at, &mm);
        snprintf(line, sizeof line, "Release %s (%s)?", mm.nickname, species_text(s, mm.species, name, sizeof name));
        confirm(s, C_RELEASE, at, "RELEASE", line, "It's gone once the save is written.");
    }
}

static void page_move_row(struct pd_library_saves* s, int dy) {
    int r = s->row;
    for (int k = 0; k < ROW_COUNT; k++) {
        r = (r + dy + ROW_COUNT) % ROW_COUNT;
        if (!row_group(r) && row_shown(s, r)) break;
    }
    s->row = r;
}

// --- BAG ---

static int bag_count(struct pd_library_saves* s) {
    struct pd_stack st[64];
    return pd_save_pocket(&s->sides[0].save, (enum pd_save_pocket) s->pocket, st, 64);
}

static void bag_act(struct pd_library_saves* s) {
    struct pd_save* sv = &s->sides[0].save;
    enum pd_save_pocket p = (enum pd_save_pocket) s->pocket;
    struct pd_stack st[64];
    int n = pd_save_pocket(sv, p, st, 64);
    if (s->bagRow >= n) {
        pick_add_item(s);
        return;
    }
    if (pd_save_stack_fixed(p, st[s->bagRow].item)) {
        message(s, "IT STAYS", p == PD_BAG_KEY ? "Key items come from the story and stay." : "HMs are handed out once and stay.", NULL);
        return;
    }
    edit_number(s, N_QUANTITY, s->bagRow, st[s->bagRow].quantity, 0, pd_save_stack_max(sv, p, st[s->bagRow].item));
}

static void step_pocket(struct pd_library_saves* s, int step) {
    s->pocket = (s->pocket + step + PD_BAG_POCKETS) % PD_BAG_POCKETS;
    s->bagRow = s->bagScroll = 0;
}

// --- TRAINER ---

static int trainer_rows(const struct pd_library_saves* s, int* ids) {
    int n = 0;
    ids[n++] = T_MONEY;
    ids[n++] = T_COINS;
    ids[n++] = TG_DEX;
    ids[n++] = T_REGISTER;
    ids[n++] = T_SEEN;
    ids[n++] = T_CAUGHT;
    ids[n++] = T_MISSING;
    int events = 0;
    for (int e = 0; e < PD_EVENTS; e++) events += pd_save_event_state(&s->sides[0].save, (enum pd_save_event) e) != PD_EVENT_NOT_HERE;
    if (events) {
        ids[n++] = TG_EVENTS;
        for (int e = 0; e < PD_EVENTS; e++)
            if (pd_save_event_state(&s->sides[0].save, (enum pd_save_event) e) != PD_EVENT_NOT_HERE) ids[n++] = T_EVENT + e;
    }
    return n;
}

// MISSING: this Pokédex's gaps, and the library's other GBA saves read one by one for the Pokémon to fill them.
static void open_missing(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    const struct pd_library_host* h = l->host;
    s->missingCount = pd_missing_list(&s->sides[0].save, missing, MISSING_MAX);
    s->missingSources = 0;
    for (int game = 0; game < l->count && s->missingSources < PD_LIBRARY_MAX; game++) {
        const struct pd_library_game* g = &l->games[game];
        if (game == s->sides[0].game || !editable(g) || !h || !h->read_save) continue;
        long n = h->read_save(h->ctx, g->file, readBuf, sizeof readBuf);
        if (n <= 0 || pd_save_parse(&missingSave, readBuf, (size_t) n, pd_save_layout_for(g->code)) || pd_save_mismatch(&missingSave)) continue;
        struct pd_trade_side other = { &missingSave, g->title, { 0 } };
        snprintf(other.code, sizeof other.code, "%s", g->code);
        pd_missing_add(missing, s->missingCount, &s->sides[0].trade, &other, s->missingSources);
        s->missingGames[s->missingSources++] = game;
    }
    s->missingOpen = true;
    s->missingRow = s->missingScroll = 0;
}

static void trainer_act(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    struct pd_save* sv = &s->sides[0].save;
    int r = s->trainerRow;
    struct pd_save_dex d;
    pd_save_dex_counts(sv, &d);
    char line[96];
    if (r == T_MONEY) edit_number(s, N_MONEY, 0, pd_save_money(sv), 0, PD_SAVE_MAX_MONEY);
    else if (r == T_COINS) edit_number(s, N_COINS, 0, pd_save_coins(sv), 0, PD_SAVE_MAX_COINS);
    else if (r == T_REGISTER) confirm(s, C_REGISTER, 0, "POKéDEX", "Mark every Pokémon in the party and the PC", "as caught?");
    else if (r == T_MISSING) open_missing(l);
    else if (r == T_SEEN || r == T_CAUGHT) {
        snprintf(line, sizeof line, "Mark all of the %s Pokédex as %s?", d.national ? "National" : d.region[0] == 'K' ? "Kanto" : "Hoenn",
                 r == T_SEEN ? "seen" : "caught");
        confirm(s, r == T_SEEN ? C_SEEN : C_CAUGHT, 0, "POKéDEX", line, d.national ? NULL : "The regional one: no National Dex yet.");
    } else if (r >= T_EVENT) {
        enum pd_save_event e = (enum pd_save_event) (r - T_EVENT);
        enum pd_event_state st = pd_save_event_state(sv, e);
        if (st == PD_EVENT_HAS) message(s, pd_save_event_name(e), "It's in the bag already.", pd_save_event_note(sv));
        else if (st == PD_EVENT_DONE) message(s, pd_save_event_name(e), "This save got it once already.", NULL);
        else {
            snprintf(line, sizeof line, "Give the %s, as its event did?", pd_save_event_name(e));
            confirm(s, C_EVENT, e, "EVENT", line, pd_save_event_note(sv));
        }
    }
}

static void trainer_move(struct pd_library_saves* s, int dy) {
    int ids[16], n = trainer_rows(s, ids), at = 0;
    for (int i = 0; i < n; i++)
        if (ids[i] == s->trainerRow) at = i;
    for (int k = 0; k < n; k++) {
        at = (at + dy + n) % n;
        if (ids[at] != TG_DEX && ids[at] != TG_EVENTS) break;
    }
    s->trainerRow = ids[at];
}

// --- the trade ---

// The app's TRADE: each trade is made in both saves' working copies at once (a trade evolution asks first, like the game's
// evolution scene), they pile up until SAVE writes the two together, and UNDO ALL drops them.

static bool trades_changed(const struct pd_library_saves* s) {
    return pd_save_changed(&s->sides[0].save) || pd_save_changed(&s->sides[1].save);
}

// What the picks would do: from side [from] (its Pokémon) to the other; false if nothing would move.
static bool trade_picks(struct pd_library_saves* s, struct pd_mon* ma, struct pd_mon* mb, bool* fromA, bool* fromB) {
    struct side *a = &s->sides[0], *b = &s->sides[1];
    if (a->mark == PD_AT_NONE || b->mark == PD_AT_NONE) return false;
    *fromA = pd_save_mon_at(&a->save, a->mark, ma);
    *fromB = pd_save_mon_at(&b->save, b->mark, mb);
    return *fromA || *fromB;
}

static void do_trade(struct pd_library* l, bool evolve) {
    struct pd_library_saves* s = S(l);
    struct side *a = &s->sides[0], *b = &s->sides[1];
    struct pd_mon ma, mb;
    bool fromA, fromB;
    if (!trade_picks(s, &ma, &mb, &fromA, &fromB)) return;
    struct side *from = fromA ? a : b, *to = fromA ? b : a;
    struct pd_mon* sent = fromA ? &ma : &mb;
    int evolves = evolve ? pd_trade_evolution(&from->trade, from->mark, &to->trade) : 0;
    char why[160], name[24];
    const char* no = pd_trade(&from->trade, from->mark, &to->trade, to->mark, evolve, why, sizeof why);
    if (no) {
        message(s, "THE GAMES WON'T", no, NULL);
        return;
    }
    const struct pd_library_game* g = &l->games[to->game];
    const char* game = !strncmp(g->title, "Pokémon ", strlen("Pokémon ")) ? g->title + strlen("Pokémon ") : g->title;
    if (fromA && fromB) snprintf(s->tradeNote, sizeof s->tradeNote, "%.24s and %.24s traded.", ma.nickname, mb.nickname);
    else snprintf(s->tradeNote, sizeof s->tradeNote, "%.24s went to %.40s.", sent->nickname, game);
    if (evolves) {
        size_t n = strlen(s->tradeNote);
        snprintf(s->tradeNote + n, sizeof s->tradeNote - n, " It evolved into %s.", species_text(s, evolves, name, sizeof name));
    }
    a->mark = b->mark = PD_AT_NONE;
}

// A game's name without the series' ("Pokémon FireRed" -> "FireRed").
static const char* short_title(const struct pd_library_game* g) {
    return !strncmp(g->title, "Pokémon ", strlen("Pokémon ")) ? g->title + strlen("Pokémon ") : g->title;
}

// TRADE AND BACK: one Pokémon picked alone that the other save would evolve: its side, or NULL; [into] what it becomes.
static struct side* trip_home(struct pd_library_saves* s, int* into) {
    struct side *a = &s->sides[0], *b = &s->sides[1];
    if ((a->mark == PD_AT_NONE) == (b->mark == PD_AT_NONE)) return NULL;
    struct side *home = a->mark != PD_AT_NONE ? a : b, *partner = home == a ? b : a;
    struct pd_mon m;
    if (!pd_save_mon_at(&home->save, home->mark, &m)) return NULL;
    *into = pd_trade_evolution(&home->trade, home->mark, &partner->trade);
    return *into ? home : NULL;
}

static void do_trade_back(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    int into = 0;
    struct side* home = trip_home(s, &into);
    if (!home) return;
    struct side* partner = home == &s->sides[0] ? &s->sides[1] : &s->sides[0];
    struct pd_mon m;
    pd_save_mon_at(&home->save, home->mark, &m);
    char why[160], name[24];
    const char* no = pd_trade_back(&home->trade, home->mark, &partner->trade, why, sizeof why);
    if (no) {
        message(s, "THE GAMES WON'T", no, NULL);
        return;
    }
    snprintf(s->tradeNote, sizeof s->tradeNote, "%.20s went to %.30s and came back as %s.", m.nickname, short_title(&l->games[partner->game]),
             species_text(s, into, name, sizeof name));
    home->mark = PD_AT_NONE;
}

static void start_trade(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    struct side *a = &s->sides[0], *b = &s->sides[1];
    int into = 0;
    struct side* home = trip_home(s, &into);
    if (home) {
        struct side* partner = home == a ? b : a;
        char why[160], line1[120], name[24];
        const char* no = pd_trade_back_blocker(&home->trade, home->mark, &partner->trade, why, sizeof why);
        if (no) {
            message(s, "THE GAMES WON'T", no, NULL);
            return;
        }
        struct pd_mon m;
        pd_save_mon_at(&home->save, home->mark, &m);
        char line2[120];
        snprintf(line1, sizeof line1, "%.20s goes to %.24s,", m.nickname, short_title(&l->games[partner->game]));
        snprintf(line2, sizeof line2, "becomes %s and comes back.", species_text(s, into, name, sizeof name));
        confirm(s, C_TRADE_BACK, 0, "TRADE AND BACK", line1, line2);
        s->yes = "TRADE BACK";
        s->no = "CANCEL";
        return;
    }
    if (a->mark == PD_AT_NONE || b->mark == PD_AT_NONE) {
        message(s, "PICK BOTH", "Pick a Pokémon on one side and", "a Pokémon or an empty place on the other.");
        return;
    }
    struct pd_mon ma, mb;
    bool fromA, fromB;
    if (!trade_picks(s, &ma, &mb, &fromA, &fromB)) {
        message(s, "NOTHING TO TRADE", "Both places are empty.", NULL);
        return;
    }
    char why[160];
    const char* no = fromA ? pd_trade_blocker(&a->trade, a->mark, &b->trade, why, sizeof why) : NULL;
    if (!no && fromB) no = pd_trade_blocker(&b->trade, b->mark, &a->trade, why, sizeof why);
    if (no) {
        message(s, "THE GAMES WON'T", no, NULL);
        return;
    }
    // A trade evolution asks first: EVOLVE, or the game's B (traded as it is).
    int evoA = fromA ? pd_trade_evolution(&a->trade, a->mark, &b->trade) : 0;
    int evoB = fromB ? pd_trade_evolution(&b->trade, b->mark, &a->trade) : 0;
    if (!evoA && !evoB) {
        do_trade(l, true);
        return;
    }
    char line1[120], line2[120], name[24];
    const struct pd_mon* first = evoA ? &ma : &mb;
    snprintf(line1, sizeof line1, "%.20s evolves into %s.", first->nickname, species_text(s, evoA ? evoA : evoB, name, sizeof name));
    if (evoA && evoB) snprintf(line2, sizeof line2, "%.20s evolves into %s.", mb.nickname, species_text(s, evoB, name, sizeof name));
    else snprintf(line2, sizeof line2, "Or trade it as it is?");
    confirm(s, C_EVOLVE, 0, "EVOLVING", line1, line2);
    s->yes = "EVOLVE";
    s->no = "DON'T";
}

static void ask_write_trades(struct pd_library_saves* s) {
    if (!trades_changed(s)) {
        message(s, "NOTHING TO SAVE", "Make a trade first.", NULL);
        return;
    }
    confirm(s, C_WRITE, 0, "SAVE BOTH?", "Both saves are written, each backed up first.", "If one can't be written, neither is.");
}

static void undo_trades(struct pd_library_saves* s) {
    pd_save_revert(&s->sides[0].save);
    pd_save_revert(&s->sides[1].save);
    s->sides[0].mark = s->sides[1].mark = PD_AT_NONE;
    s->tradeNote[0] = 0;
}

// --- input ---

static void act_button(struct pd_library* l, int b) {
    struct pd_library_saves* s = S(l);
    if (s->page == PD_PAGE_EDITOR) {
        if (b == 0) open_mon_page(s, at_of(&s->sides[0], s->cursor));
        else if (b == 1) pick_box_menu(s);
        else ask_write(s);
    } else {
        if (b == 0) start_trade(l);
        else if (b == 1) undo_trades(s);
        else ask_write_trades(s);
    }
}

static void act_slot(struct pd_library* l, int slot) {
    struct pd_library_saves* s = S(l);
    struct side* side = active_side(s);
    int at = at_of(side, slot);
    if (s->page == PD_PAGE_TRADE) {
        side->mark = side->mark == at ? PD_AT_NONE : at;
        return;
    }
    struct pd_mon m;
    if (s->held == PD_AT_NONE) {
        if (pd_save_mon_at(&side->save, at, &m)) s->held = at;
        return;
    }
    const char* why = pd_save_move_at(&side->save, s->held, at);
    if (why) message(s, "CAN'T MOVE IT", why, NULL);
    s->held = PD_AT_NONE;
}

static void leave(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    if (s->page == PD_PAGE_TRADE && s->stage == PICK_B) {
        s->stage = PICK_A;
        fill_pick(l);
        return;
    }
    if (s->page == PD_PAGE_EDITOR && s->stage == OPEN && pd_save_changed(&s->sides[0].save)) {
        confirm(s, C_LEAVE, 0, "LEAVE", "Leave without writing the changes?", NULL);
        return;
    }
    if (s->page == PD_PAGE_TRADE && s->stage == OPEN && trades_changed(s)) {
        confirm(s, C_LEAVE, 0, "DISCARD CHANGES?", "The trades you made aren't saved.", "Leave without saving them?");
        return;
    }
    s->stage = PICK_A;
    s->held = PD_AT_NONE;
    s->monPage = false;
    fill_pick(l);
}

static void answer(struct pd_library* l, bool yes) {
    struct pd_library_saves* s = S(l);
    int d = s->confirmOf;
    s->dialog = NONE;
    struct pd_save* sv = &s->sides[0].save;
    if (d == C_EVOLVE) {
        do_trade(l, yes);
        return;
    }
    if (d == C_TRADE_BACK) {
        if (yes) do_trade_back(l);
        return;
    }
    if (!yes) return;
    if (d == C_QUIT) {
        l->quit = true;
    } else if (d == C_WRITE && s->page == PD_PAGE_TRADE) {
        if (write_sides(l, 2)) {
            s->tradeNote[0] = 0;
            message(s, "SAVED", "Both saves are written.", "Backups are in pokedaisy-backups.");
        }
    } else if (d == C_WRITE) {
        if (write_sides(l, 1)) message(s, "SAVED", "The save is written.", "A backup is in pokedaisy-backups.");
    } else if (d == C_LEAVE) {
        pd_save_revert(sv);
        pd_save_revert(&s->sides[1].save);
        s->tradeNote[0] = 0;
        s->stage = PICK_A;
        s->monPage = false;
        fill_pick(l);
    } else if (d == C_RELEASE) {
        if (done_or_why(s, pd_save_release(sv, s->confirmArg))) s->monPage = false;
    } else if (d == C_REGISTER) {
        pd_save_register_owned(sv);
    } else if (d == C_SEEN || d == C_CAUGHT) {
        pd_save_mark_all_dex(sv, d == C_CAUGHT);
    } else if (d == C_EVENT) {
        if (done_or_why(s, pd_save_give_event(sv, (enum pd_save_event) s->confirmArg)))
            message(s, "GIVEN", "It's in the key items pocket.", pd_save_event_note(sv));
    }
}

bool lib_saves_unsaved(const struct pd_library* l) {
    const struct pd_library_saves* s = l->saves;
    if (!s || s->stage != OPEN) return false;
    return s->page == PD_PAGE_TRADE ? trades_changed(s) : pd_save_changed(&s->sides[0].save);
}

void lib_saves_ask_quit(struct pd_library* l) {
    struct pd_library_saves* s = S(l);
    if (s->dialog != NONE) return;
    confirm(s, C_QUIT, 0, "CLOSE POKEDAISY?", s->page == PD_PAGE_TRADE ? "The trades you made aren't saved." : "Your changes aren't saved.",
            "Close without saving them?");
}

static int dx_of(unsigned down) { return !!(down & PD_LIB_RIGHT) - !!(down & PD_LIB_LEFT); }
static int dy_of(unsigned down) { return !!(down & PD_LIB_DOWN) - !!(down & PD_LIB_UP); }

static void pick_keys(struct pd_library* l, unsigned down) {
    struct pd_library_saves* s = S(l);
    int dy = dy_of(down), n = s->pickCount;
    if (down & PD_LIB_L) dy = -8;
    if (down & PD_LIB_R) dy = 8;
    if (dy && n) {
        int at = s->pickSelected + dy;
        if (dy == 1 || dy == -1) at = (at + n) % n;
        else at = at < 0 ? 0 : at >= n ? n - 1 : at;
        s->pickSelected = at;
        // Keep it in view (the window's list shows about nine rows).
        int view = 9 * ROW_H;
        if (at * ROW_H < s->pickScroll) s->pickScroll = at * ROW_H;
        if ((at + 1) * ROW_H > s->pickScroll + view) s->pickScroll = (at + 1) * ROW_H - view;
    }
    if (down & (PD_LIB_A | PD_LIB_B)) pick_done(l, down & PD_LIB_A);
}

void lib_saves_keys(struct pd_library* l, unsigned down) {
    struct pd_library_saves* s = S(l);
    if (s->dialog == MESSAGE) {
        if (down & (PD_LIB_A | PD_LIB_B)) s->dialog = NONE;
        return;
    }
    if (s->dialog == NUMBER) {
        int dx = dx_of(down);
        s->digit = (s->digit + dx + s->digits) % s->digits;
        if (down & PD_LIB_UP) number_step(s, s->digit, 1);
        if (down & PD_LIB_DOWN) number_step(s, s->digit, -1);
        if (down & (PD_LIB_A | PD_LIB_B)) number_done(s, down & PD_LIB_A);
        return;
    }
    if (s->dialog == PICK) {
        pick_keys(l, down);
        return;
    }
    if (s->dialog != NONE) {
        if (down & (PD_LIB_A | PD_LIB_B)) answer(l, down & PD_LIB_A);
        return;
    }
    if (s->stage == LOADING) return;
    int dx = dx_of(down), dy = dy_of(down);
    if (s->stage != OPEN) {
        bool grid = lib_grid(l);
        if (dx || dy) lib_list_move(&s->pick, grid ? dx : 0, dy, l->columns, 0, grid);
        if (down & PD_LIB_A && s->pick.count) picked(l, s->pick.items[s->pick.selected]);
        if (down & PD_LIB_B) leave(l);
        return;
    }
    if (s->page == PD_PAGE_EDITOR) {
        if (down & PD_LIB_X) {
            ask_write(s);
            return;
        }
        if (s->monPage) {
            if (dy) page_move_row(s, dy);
            if (dx) page_step(s, dx);
            if (down & PD_LIB_L) step_mon(s, -1);
            if (down & PD_LIB_R) step_mon(s, 1);
            if (down & PD_LIB_A) page_act(l);
            if (down & PD_LIB_B) s->monPage = false;
            return;
        }
        if (down & PD_LIB_SELECT) {
            s->tab = (s->tab + 1) % TABS;
            s->held = PD_AT_NONE;
            s->missingOpen = false;
            return;
        }
        if (s->tab == TAB_BAG) {
            int n = bag_count(s);
            if (dy) s->bagRow = (s->bagRow + dy + n + 1) % (n + 1); // past the last stack: ADD AN ITEM
            if (down & PD_LIB_L) step_pocket(s, -1);
            if (down & PD_LIB_R) step_pocket(s, 1);
            if (down & PD_LIB_A) bag_act(s);
            if (down & PD_LIB_Y) pick_add_item(s);
            if (down & PD_LIB_B) leave(l);
            return;
        }
        if (s->tab == TAB_TRAINER && s->missingOpen) {
            int n = s->missingCount;
            if (n && dy) s->missingRow = (s->missingRow + dy + n) % n;
            if (n && down & PD_LIB_L) s->missingRow = s->missingRow > 8 ? s->missingRow - 8 : 0;
            if (n && down & PD_LIB_R) s->missingRow = s->missingRow + 8 < n ? s->missingRow + 8 : n - 1;
            if (down & PD_LIB_B) s->missingOpen = false;
            return;
        }
        if (s->tab == TAB_TRAINER) {
            if (dy) trainer_move(s, dy);
            if (down & PD_LIB_A) trainer_act(l);
            if (down & PD_LIB_B) leave(l);
            return;
        }
    }
    if (dx || dy) move_cursor(s, dx, dy);
    if (down & PD_LIB_L) step_box(s, -1);
    if (down & PD_LIB_R) step_box(s, 1);
    if (down & PD_LIB_A) {
        if (s->button >= 0) act_button(l, s->button);
        else act_slot(l, s->cursor);
    }
    if (s->page == PD_PAGE_EDITOR && down & PD_LIB_Y && s->button < 0 && s->held == PD_AT_NONE) open_mon_page(s, cursor_at(s));
    if (s->page == PD_PAGE_TRADE && down & PD_LIB_X) s->active = 1 - s->active;
    if (s->page == PD_PAGE_TRADE && down & PD_LIB_Y) start_trade(l);
    if (s->page == PD_PAGE_TRADE && down & PD_LIB_SELECT) ask_write_trades(s);
    if (down & PD_LIB_B) {
        if (s->held != PD_AT_NONE) s->held = PD_AT_NONE;
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
        if (hit >= HIT_DIGIT_UP && hit < HIT_DIGIT_UP + 8) {
            s->digit = hit - HIT_DIGIT_UP;
            number_step(s, s->digit, 1);
        } else if (hit >= HIT_DIGIT_DOWN && hit < HIT_DIGIT_DOWN + 8) {
            s->digit = hit - HIT_DIGIT_DOWN;
            number_step(s, s->digit, -1);
        } else if (hit == HIT_YES || hit == HIT_NO) number_done(s, hit == HIT_YES);
        return;
    }
    if (s->dialog == PICK) {
        if (hit >= HIT_CHOICE && hit < HIT_CHOICE + PICK_MAX) {
            s->pickSelected = hit - HIT_CHOICE;
            pick_done(l, true);
        } else if (hit == HIT_NO || hit == HIT_PICK_CLOSE) pick_done(l, false);
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
    if (s->page == PD_PAGE_EDITOR) {
        if (hit >= HIT_TAB && hit < HIT_TAB + TABS) {
            s->tab = hit - HIT_TAB;
            s->monPage = false;
            s->held = PD_AT_NONE;
            s->missingOpen = false;
            return;
        }
        if (s->monPage) {
            if (hit >= HIT_EDIT_ROW && hit < HIT_EDIT_ROW + ROW_COUNT) {
                int r = hit - HIT_EDIT_ROW;
                if (r == s->row) page_act(l);
                s->row = r;
            } else if (hit >= HIT_EDIT_DEC && hit < HIT_EDIT_DEC + ROW_COUNT) {
                s->row = hit - HIT_EDIT_DEC;
                page_step(s, -1);
            } else if (hit >= HIT_EDIT_INC && hit < HIT_EDIT_INC + ROW_COUNT) {
                s->row = hit - HIT_EDIT_INC;
                page_step(s, 1);
            } else if (hit == HIT_BOX_PREV) step_mon(s, -1);
            else if (hit == HIT_BOX_NEXT) step_mon(s, 1);
            else if (hit == HIT_DONE) s->monPage = false;
            else if (hit == HIT_WRITE) ask_write(s);
            return;
        }
        if (s->tab == TAB_BAG) {
            if (hit >= HIT_POCKET && hit < HIT_POCKET + PD_BAG_POCKETS) {
                s->pocket = hit - HIT_POCKET;
                s->bagRow = s->bagScroll = 0;
            } else if (hit >= HIT_EDIT_ROW && hit < HIT_EDIT_ROW + 70) {
                int r = hit - HIT_EDIT_ROW;
                if (r == s->bagRow) bag_act(s);
                s->bagRow = r;
            } else if (hit == HIT_BUTTON) pick_add_item(s);
            else if (hit == HIT_WRITE) ask_write(s);
            return;
        }
        if (s->tab == TAB_TRAINER && s->missingOpen) {
            if (hit >= HIT_EDIT_ROW && hit < HIT_EDIT_ROW + MISSING_MAX) s->missingRow = hit - HIT_EDIT_ROW;
            else if (hit == HIT_DONE) s->missingOpen = false;
            return;
        }
        if (s->tab == TAB_TRAINER) {
            if (hit >= HIT_EDIT_ROW && hit < HIT_EDIT_ROW + 32) {
                int r = hit - HIT_EDIT_ROW;
                if (r == s->trainerRow) trainer_act(l);
                s->trainerRow = r;
            } else if (hit == HIT_WRITE) ask_write(s);
            return;
        }
    }
    struct side* side = active_side(s);
    if (hit >= HIT_SLOT && hit < HIT_SLOT + view_slots(side)) {
        s->cursor = hit - HIT_SLOT;
        s->button = -1;
        act_slot(l, s->cursor);
    } else if (hit == HIT_BOX_PREV) {
        step_box(s, -1);
    } else if (hit == HIT_BOX_NEXT) {
        step_box(s, 1);
    } else if (hit == HIT_SIDE_A || hit == HIT_SIDE_B) {
        s->active = hit == HIT_SIDE_B;
    } else if (hit >= HIT_BUTTON && hit < HIT_BUTTON + BUTTONS) {
        s->button = hit - HIT_BUTTON;
        act_button(l, s->button);
    } else if (hit == HIT_DONE) {
        leave(l);
    }
}

// --- drawing: the touch screen ---

static void draw_mon(struct pd_library_saves* s, struct pd_canvas* c, const struct pd_mon* m, int x, int y, bool faded) {
    int species = m->egg ? PD_SPECIES_EGG : m->species;
    if (!s->icons || !pd_draw_mon_icon(c, &s->names, species, x, y, faded)) pd_draw_party_ball(c, x + 6, y + 8);
}

// A title between two arrow buttons (the box's name, a Pokémon's).
static void arrow_head(struct pd_canvas* c, int x, int y, int w, const char* head) {
    lib_button(c, x, y, 26, 20, "", false, HIT_BOX_PREV);
    pd_triangle(c, x + 9, y + 5, 5, PD_LEFT, PD_VALUE);
    lib_button(c, x + w - 26, y, 26, 20, "", false, HIT_BOX_NEXT);
    pd_triangle(c, x + w - 15, y + 5, 5, PD_RIGHT, PD_VALUE);
    int tf = pd_title_box(c, x + 29, y, w - 58, 20, PD_TITLE_FILL);
    pd_text_fit(c, x + 29 + (w - 58 - pd_text_width(head)) / 2, pd_text_y(y, 20), w - 58 - 2 * tf, head, PD_TITLE_TEXT, PD_TITLE_SHADOW);
}

static void box_view(struct pd_library* l, struct pd_canvas* c, struct side* side, int y, int h) {
    struct pd_library_saves* s = S(l);
    int x = PAGE_X, w = PAGE_W;
    // The party's or the box's name between its arrows (L / R).
    char name[24], head[40];
    if (side->box == PARTY_VIEW) snprintf(head, sizeof head, "PARTY  %d/6", pd_save_party_count(&side->save));
    else {
        pd_save_box_name(&side->save, side->box, name, sizeof name);
        snprintf(head, sizeof head, "%s  %d/%d", name, pd_save_box_count(&side->save, side->box), BOX_SLOTS);
    }
    arrow_head(c, x, y, w, head);
    y += 23;
    h -= 23;
    int f = pd_list_box(c, x, y, w, h);
    int cols = view_cols(side), rows = view_rows(side), slots = view_slots(side);
    int cw = (w - 2 * f - 4) / cols, ch = (h - 2 * f - 4) / rows;
    int gx = x + f + 2 + (w - 2 * f - 4 - cw * cols) / 2, gy = y + f + 2;
    bool party = side->box == PARTY_VIEW;
    int count = pd_save_party_count(&side->save);
    pd_clip(c, x + f, y + f, w - 2 * f, h - 2 * f);
    for (int i = 0; i < slots; i++) {
        int cx = gx + (i % cols) * cw, cy = gy + (i / cols) * ch, at = at_of(side, i);
        bool cursor = s->cursor == i && s->button < 0 && s->dialog == NONE, marked = side->mark == at, held = s->held == at;
        if (party && i < count) pd_round_rect(c, cx + 2, cy + 1, cw - 4, ch - 2, 3, pd_mix(PD_LIST_FILL, PD_TITLE_FILL, 110));
        if (cursor) pd_round_rect(c, cx + 1, cy, cw - 2, ch, 3, PD_ROW_SELECTED);
        if (marked) {
            pd_round_rect(c, cx + 1, cy, cw - 2, ch, 3, PD_VALUE);
            pd_round_rect(c, cx + 3, cy + 2, cw - 6, ch - 4, 2, cursor ? PD_ROW_SELECTED : PD_LIST_FILL);
        }
        struct pd_mon m;
        if (pd_save_mon_at(&side->save, at, &m)) {
            if (party) {
                // The party menu's row: the icon, then the name over its level.
                draw_mon(s, c, &m, cx + 4, cy + (ch - 32) / 2 - 1, held);
                char lv[16];
                if (m.egg) snprintf(lv, sizeof lv, "EGG");
                else snprintf(lv, sizeof lv, "Lv%d", m.level);
                pd_text_fit(c, cx + 40, cy + ch / 2 - 15, cw - 44, m.nickname, held ? PD_MUTED : PD_LABEL, held ? PD_MUTED_SHADOW : PD_LABEL_SHADOW);
                pd_text(c, cx + 40, cy + ch / 2 + 1, lv, PD_VALUE, PD_VALUE_SHADOW);
            } else {
                draw_mon(s, c, &m, cx + (cw - 32) / 2, cy + (ch - 32) / 2 - 1, held);
            }
        } else if (party && i == count && (s->held != PD_AT_NONE || s->page == PD_PAGE_TRADE)) {
            pd_text_fit(c, cx + 10, pd_text_y(cy, ch), cw - 14, "Join the party", PD_MUTED, PD_MUTED_SHADOW);
        }
        lib_hit(cx, cy, cw, ch, HIT_SLOT + i);
    }
    pd_unclip(c);
    // The Pokémon picked up rides on the cursor, a little raised (the PC's hand).
    if (s->held != PD_AT_NONE && s->button < 0) {
        struct pd_mon m;
        if (pd_save_mon_at(&side->save, s->held, &m)) {
            int cx = gx + (s->cursor % cols) * cw, cy = gy + (s->cursor / cols) * ch;
            draw_mon(s, c, &m, party ? cx + 4 : cx + (cw - 32) / 2, cy + (ch - 32) / 2 - 9, false);
        }
    }
}

static void buttons(struct pd_library* l, struct pd_canvas* c, const char* const labels[BUTTONS], int on) {
    struct pd_library_saves* s = S(l);
    int y = LIB_H - LIB_M - BUTTON_H, gap = 4, w = (PAGE_W - gap * (BUTTONS - 1)) / BUTTONS;
    for (int i = 0; i < BUTTONS; i++) {
        bool picked = s->button == i || i == on || l->pressedHit == HIT_BUTTON + i;
        lib_button(c, PAGE_X + i * (w + gap), y, i == BUTTONS - 1 ? PAGE_W - i * (w + gap) : w, BUTTON_H, labels[i], picked, HIT_BUTTON + i);
    }
}

// A row of [n] buttons along the bottom, [hits] theirs.
static void bottom_buttons(struct pd_library* l, struct pd_canvas* c, int n, const char* const* labels, const int* hits) {
    int y = LIB_H - LIB_M - BUTTON_H, gap = 4, w = (PAGE_W - gap * (n - 1)) / n;
    for (int i = 0; i < n; i++)
        lib_button(c, PAGE_X + i * (w + gap), y, i == n - 1 ? PAGE_W - i * (w + gap) : w, BUTTON_H, labels[i], l->pressedHit == hits[i], hits[i]);
}

static void number_dialog(struct pd_library_saves* s, struct pd_canvas* c) {
    lib_dim(c);
    lib_hit(0, 0, LIB_W, LIB_H, HIT_MESSAGE);
    int digits = s->digits, dw = 20, w = 232, h = 132, x = (LIB_W - w) / 2, y = (LIB_H - h) / 2;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    pd_text(c, x + f + 6, y + f + 4, number_title(s), PD_VALUE, PD_VALUE_SHADOW);
    char range[32];
    snprintf(range, sizeof range, "%ld - %ld", s->numberMin, s->numberMax);
    pd_text_right(c, x + w - f - 6, y + f + 4, range, PD_MUTED, PD_MUTED_SHADOW);
    char text[12];
    snprintf(text, sizeof text, "%0*ld", digits, s->number);
    bool money = s->numberOf == N_MONEY;
    int dx = x + (w - digits * dw) / 2 + (money ? 6 : 0), dy = y + 44;
    if (money) pd_text(c, dx - 14, dy + 2, "$", PD_LABEL, PD_LABEL_SHADOW);
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

static void pick_dialog(struct pd_library* l, struct pd_canvas* c) {
    struct pd_library_saves* s = S(l);
    lib_dim(c);
    lib_hit(0, 0, LIB_W, LIB_H, HIT_PICK_CLOSE);
    int x = PAGE_X + 8, w = PAGE_W - 16, y = 10, th = 24;
    int tf = pd_title_box(c, x, y, w, th, PD_TITLE_FILL);
    pd_text_fit(c, x + tf + 6, pd_text_y(y, th), w - 2 * tf - 12, s->pickTitle, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    int ly = y + th + 3, lh = LIB_H - ly - BUTTON_H - 2 * LIB_M - 2;
    int f = pd_list_box(c, x, ly, w, lh);
    int top = ly + f + 2, view = lh - 2 * f - 4, total = s->pickCount * ROW_H;
    if (s->pickScroll > total - view) s->pickScroll = total - view;
    if (s->pickScroll < 0) s->pickScroll = 0;
    int lx = x + f + 6, lw = w - 2 * f - 12 - (total > view ? 10 : 0);
    pd_clip(c, x + f, top, w - 2 * f, view);
    for (int i = 0; i < s->pickCount; i++) {
        int ry = top + i * ROW_H - s->pickScroll;
        if (ry + ROW_H <= top || ry >= top + view) continue;
        bool on = i == s->pickSelected || l->pressedHit == HIT_CHOICE + i;
        if (on) pd_fill(c, lx - 4, ry, lw + 8, ROW_H, PD_ROW_SELECTED);
        pd_text_fit(c, lx, pd_text_y(ry, ROW_H), lw, s->pickLabels[i], on ? PD_VALUE : PD_LABEL, on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        int vy = ry < top ? top : ry, vh = (ry + ROW_H > top + view ? top + view : ry + ROW_H) - vy;
        lib_hit(x + f, vy, w - 2 * f, vh, HIT_CHOICE + i);
    }
    pd_unclip(c);
    lib_scroll_bar(c, x + w - f - 8, top + 1, view - 2, total, view, &s->pickScroll);
    int by = LIB_H - LIB_M - BUTTON_H - 2;
    lib_button(c, x + (w - 100) / 2, by, 100, BUTTON_H, "CANCEL", l->pressedHit == HIT_NO, HIT_NO);
}

static const char* tool_title(const struct pd_library_saves* s) {
    return s->page == PD_PAGE_EDITOR ? "SAVE EDITOR" : "TRADE SAVES";
}

// SAVE EDITOR's tabs along the title row, the battery in a window of its own at its right.
static void editor_tabs(struct pd_library* l, struct pd_canvas* c) {
    struct pd_library_saves* s = S(l);
    static const char* const NAMES[TABS] = { "POKéMON", "BAG", "TRAINER" };
    int y = TITLE_Y, gap = 4, bw = lib_battery_width() ? lib_battery_width() + 14 : 0;
    int w = (PAGE_W - gap * (TABS - 1) - (bw ? bw + gap : 0)) / TABS;
    if (bw) lib_battery_box(c, PAGE_X + PAGE_W - bw, y, bw, TITLE_H);
    for (int i = 0; i < TABS; i++) {
        int x = PAGE_X + i * (w + gap), tw = pd_text_width(NAMES[i]);
        bool on = s->tab == i;
        pd_layered_two_tone(c, x, y, w, TITLE_H, 3, pd_cur->chip, pd_cur->chipCount, on ? pd_cur->tabSelectedFill : pd_cur->tabIdleFill, 0);
        pd_text_fit(c, x + (w - tw > 8 ? (w - tw) / 2 : 4), pd_text_y(y, TITLE_H), w - 8, NAMES[i], on ? PD_VALUE : PD_LABEL,
                    on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        if (on) pd_fill(c, x + 6, y + TITLE_H - 5, w - 12, 2, PD_VALUE);
        lib_hit(x, y, w, TITLE_H, HIT_TAB + i);
    }
}

// A list of rows (label, value) in a window, the cursor's row white, ◀ / ▶ where a value steps.
struct row_draw { const char* label; char value[40]; bool group, muted, steps; };

static void row_list(struct pd_library* l, struct pd_canvas* c, int y, int h, const struct row_draw* rows, const int* ids, int n, int selected,
                     int* scroll, int valueAt, bool follow) {
    int x = PAGE_X, w = PAGE_W;
    int f = pd_list_box(c, x, y, w, h);
    int top = y + f + 2, view = h - 2 * f - 4, total = n * ROW_H;
    int sel = -1;
    for (int i = 0; i < n; i++)
        if (ids[i] == selected) sel = i;
    if (follow && sel >= 0) {
        if (sel * ROW_H < *scroll) *scroll = sel * ROW_H - (sel > 0 && rows[sel - 1].group ? ROW_H : 0);
        if ((sel + 1) * ROW_H > *scroll + view) *scroll = (sel + 1) * ROW_H - view;
    }
    if (*scroll > total - view) *scroll = total - view;
    if (*scroll < 0) *scroll = 0;
    int lx = x + f + 6, lw = w - 2 * f - 12 - (total > view ? 10 : 0);
    pd_clip(c, x + f, top, w - 2 * f, view);
    for (int i = 0; i < n; i++) {
        int ry = top + i * ROW_H - *scroll;
        if (ry + ROW_H <= top || ry >= top + view) continue;
        const struct row_draw* r = &rows[i];
        int ty = pd_text_y(ry, ROW_H);
        if (r->group) {
            if (pd_cur->groupTitleFill != PD_NO_SHADOW) pd_fill(c, lx - 4, ry + 1, pd_text_width(r->label) + 8, ROW_H - 2, pd_cur->groupTitleFill);
            pd_text(c, lx, ty, r->label, pd_cur->groupTitleText, pd_cur->groupTitleShadow);
            if (r->value[0]) pd_text_right(c, lx + lw, ty, r->value, PD_MUTED, PD_MUTED_SHADOW);
            continue;
        }
        bool on = i == sel || l->pressedHit == HIT_EDIT_ROW + ids[i];
        if (on) pd_fill(c, lx - 4, ry, lw + 8, ROW_H, PD_ROW_SELECTED);
        pd_text_fit(c, lx, ty, valueAt - 6, r->label, PD_LABEL, PD_LABEL_SHADOW);
        int vx = lx + valueAt, vw = lw - valueAt;
        int vy = ry < top ? top : ry, vh = (ry + ROW_H > top + view ? top + view : ry + ROW_H) - vy;
        lib_hit(x + f, vy, w - 2 * f, vh, HIT_EDIT_ROW + ids[i]);
        uint32_t vc = r->muted ? PD_MUTED : PD_VALUE, vs = r->muted ? PD_MUTED_SHADOW : PD_VALUE_SHADOW;
        if (r->steps) {
            // ◀ value ▶: the arrows step it, a tap on the row opens its window.
            pd_triangle(c, vx, ty + 3, 4, PD_LEFT, on ? PD_VALUE : PD_MUTED);
            pd_triangle(c, lx + lw - 5, ty + 3, 4, PD_RIGHT, on ? PD_VALUE : PD_MUTED);
            pd_text_fit(c, vx + 10, ty, vw - 22, r->value, vc, vs);
            lib_hit(vx - 6, vy, 22, vh, HIT_EDIT_DEC + ids[i]);
            lib_hit(lx + lw - 16, vy, 24, vh, HIT_EDIT_INC + ids[i]);
        } else {
            pd_text_fit(c, vx, ty, vw, r->value, vc, vs);
        }
    }
    pd_unclip(c);
    lib_scroll_bar(c, x + w - f - 8, top + 1, view - 2, total, view, scroll);
}

static void mon_page(struct pd_library* l, struct pd_canvas* c) {
    struct pd_library_saves* s = S(l);
    struct pd_save* sv = &s->sides[0].save;
    struct pd_box_mon m;
    struct pd_mon pm;
    if (!pd_save_record_at(sv, s->monAt, &m) || !pd_save_mon_at(sv, s->monAt, &pm)) {
        s->monPage = false;
        return;
    }
    // Its name between the arrows to the next one (L / R).
    int y = TITLE_Y + TITLE_H + LIB_M;
    arrow_head(c, PAGE_X, y, PAGE_W, pm.nickname);
    y += 23;
    int species = pd_box_mon_species(&m), ivs[6], evs[6], total = 0;
    uint32_t p = pd_box_mon_personality(&m);
    pd_box_mon_ivs(&m, ivs);
    pd_box_mon_evs(&m, evs);
    for (int i = 0; i < 6; i++) total += evs[i];
    static struct row_draw rows[ROW_COUNT];
    static int ids[ROW_COUNT];
    int n = 0;
    char name[32];
    for (int r = 0; r < ROW_COUNT; r++) {
        if (!row_shown(s, r)) continue;
        struct row_draw* d = &rows[n];
        memset(d, 0, sizeof *d);
        ids[n++] = r;
        d->group = row_group(r);
        if (r == R_LEVEL) {
            d->label = "LEVEL";
            snprintf(d->value, sizeof d->value, "%d", pd_save_level_at(sv, s->monAt));
            d->steps = true;
        } else if (r == R_NATURE) {
            d->label = "NATURE";
            snprintf(d->value, sizeof d->value, "%s", pd_nature_names[p % 25]);
            d->steps = true;
        } else if (r == R_GENDER) {
            enum pd_save_gender g = pd_save_gender_of(species, p);
            d->label = "GENDER";
            snprintf(d->value, sizeof d->value, "%s", g == PD_SAVE_MALE ? "MALE" : g == PD_SAVE_FEMALE ? "FEMALE" : "NONE");
            d->muted = !pd_save_both_genders(species);
        } else if (r == R_SHINY) {
            d->label = "SHINY";
            snprintf(d->value, sizeof d->value, "%s", pd_save_is_shiny(p, pd_box_mon_ot_id(&m)) ? "YES" : "NO");
        } else if (r == R_ABILITY) {
            d->label = "ABILITY";
            snprintf(d->value, sizeof d->value, "%s", pd_save_ability_name(pd_save_ability(species, pd_box_mon_ability_num(&m))));
            d->muted = pd_save_ability_count(species) < 2;
        } else if (r == R_FRIENDSHIP) {
            d->label = "FRIENDSHIP";
            snprintf(d->value, sizeof d->value, "%d", pd_box_mon_friendship(&m));
            d->steps = true;
        } else if (r == R_NICKNAME) {
            d->label = "NICKNAME";
            snprintf(d->value, sizeof d->value, "%s", pm.nickname);
            d->muted = !pd_save_is_own(sv, s->monAt);
        } else if (r == R_ITEM) {
            d->label = "ITEM";
            snprintf(d->value, sizeof d->value, "%s", item_text(s, pd_box_mon_item(&m), name, sizeof name));
            d->muted = pd_save_is_mail(pd_box_mon_item(&m));
        } else if (r == G_IVS) {
            d->label = "IVs";
            snprintf(d->value, sizeof d->value, "0 - 31");
        } else if (r >= R_IV && r < R_IV + 6) {
            d->label = STAT_NAMES[r - R_IV];
            snprintf(d->value, sizeof d->value, "%d", ivs[r - R_IV]);
            d->steps = true;
        } else if (r == G_EVS) {
            d->label = "EVs";
            snprintf(d->value, sizeof d->value, "%d / 510", total);
        } else if (r >= R_EV && r < R_EV + 6) {
            d->label = STAT_NAMES[r - R_EV];
            snprintf(d->value, sizeof d->value, "%d", evs[r - R_EV]);
            d->steps = true;
        } else if (r == G_MOVES) {
            d->label = "MOVES";
        } else if (r >= R_MOVE && r < R_MOVE + 4) {
            static const char* const MOVES[4] = { "MOVE 1", "MOVE 2", "MOVE 3", "MOVE 4" };
            int mv = pd_box_mon_move(&m, r - R_MOVE);
            d->label = MOVES[r - R_MOVE];
            snprintf(d->value, sizeof d->value, "%s", mv ? move_text(s, mv, name, sizeof name) : "-");
            d->muted = !mv;
        } else if (r == R_RELEASE) {
            d->label = "RELEASE";
            snprintf(d->value, sizeof d->value, "LET IT GO");
        }
    }
    static int lastRow = -1;
    row_list(l, c, y, LIB_H - y - BUTTON_H - 2 * LIB_M, rows, ids, n, s->row, &s->rowScroll, 104, lastRow != s->row);
    lastRow = s->row;
    static const char* const LABELS[2] = { "DONE", "SAVE" };
    static const int HITS[2] = { HIT_DONE, HIT_WRITE };
    bottom_buttons(l, c, 2, LABELS, HITS);
}

static const char* const POCKET_NAMES[PD_BAG_POCKETS] = { "ITEMS", "BALLS", "TMs", "BERRIES", "KEY" };

static void bag_tab(struct pd_library* l, struct pd_canvas* c) {
    struct pd_library_saves* s = S(l);
    struct pd_save* sv = &s->sides[0].save;
    // The pockets' chips, each as wide as its name and the room left shared out (BERRIES is the long one).
    int y = TITLE_Y + TITLE_H + LIB_M, gap = 3, text = 0;
    for (int i = 0; i < PD_BAG_POCKETS; i++) text += pd_text_width(POCKET_NAMES[i]);
    int extra = (PAGE_W - gap * (PD_BAG_POCKETS - 1) - text) / PD_BAG_POCKETS, x = PAGE_X;
    for (int i = 0; i < PD_BAG_POCKETS; i++) {
        int w = i == PD_BAG_POCKETS - 1 ? PAGE_X + PAGE_W - x : pd_text_width(POCKET_NAMES[i]) + extra;
        lib_button(c, x, y, w, 20, POCKET_NAMES[i], s->pocket == i, HIT_POCKET + i);
        x += w + gap;
    }
    y += 23;
    enum pd_save_pocket p = (enum pd_save_pocket) s->pocket;
    struct pd_stack st[64];
    int n = pd_save_pocket(sv, p, st, 64);
    if (s->bagRow > n) s->bagRow = n;
    static struct row_draw rows[66];
    static int ids[66];
    static char names[66][32];
    for (int i = 0; i <= n; i++) {
        memset(&rows[i], 0, sizeof rows[i]);
        ids[i] = i;
        if (i == n) {
            rows[i].label = p == PD_BAG_KEY ? "" : "+ ADD AN ITEM";
            snprintf(rows[i].value, sizeof rows[i].value, "%d/%d", n, pd_save_pocket_slots(sv, p));
            rows[i].muted = true;
            continue;
        }
        rows[i].label = item_text(s, st[i].item, names[i], sizeof names[i]);
        if (p != PD_BAG_KEY) snprintf(rows[i].value, sizeof rows[i].value, "x%d", st[i].quantity);
        rows[i].muted = pd_save_stack_fixed(p, st[i].item);
    }
    static int lastRow = -1, lastPocket = -1;
    bool follow = lastRow != s->bagRow || lastPocket != s->pocket;
    row_list(l, c, y, LIB_H - y - BUTTON_H - 2 * LIB_M, rows, ids, n + 1, s->bagRow, &s->bagScroll, PAGE_W - 76, follow);
    lastRow = s->bagRow;
    lastPocket = s->pocket;
    static const char* const LABELS[2] = { "ADD ITEM", "SAVE" };
    static const int HITS[2] = { HIT_BUTTON, HIT_WRITE };
    bottom_buttons(l, c, 2, LABELS, HITS);
}

// MISSING's touch screen: the gaps in the Pokédex's order, each with the game that can fill it (grey: none, or not yet).
static const char* missing_game(struct pd_library* l, const struct pd_missing* e) {
    struct pd_library_saves* s = S(l);
    if (e->source < 0 || e->source >= s->missingSources) return NULL;
    const char* t = l->games[s->missingGames[e->source]].title;
    return !strncmp(t, "Pokémon ", strlen("Pokémon ")) ? t + strlen("Pokémon ") : t;
}

static void missing_tab(struct pd_library* l, struct pd_canvas* c) {
    struct pd_library_saves* s = S(l);
    int y = TITLE_Y + TITLE_H + LIB_M;
    static struct row_draw rows[MISSING_MAX];
    static int ids[MISSING_MAX];
    char name[24];
    for (int i = 0; i < s->missingCount; i++) {
        const struct pd_missing* e = &missing[i];
        struct row_draw* r = &rows[i];
        memset(r, 0, sizeof *r);
        ids[i] = i;
        static char labels[MISSING_MAX][32];
        snprintf(labels[i], sizeof labels[i], "No.%03d %s", e->number, species_text(s, e->species, name, sizeof name));
        r->label = labels[i];
        const char* game = missing_game(l, e);
        if (!game) snprintf(r->value, sizeof r->value, "NONE");
        else snprintf(r->value, sizeof r->value, "%s", game);
        r->muted = !e->reachable;
    }
    static int lastRow = -1;
    if (!s->missingCount) {
        int f = pd_list_box(c, PAGE_X, y, PAGE_W, 60);
        pd_text(c, PAGE_X + f + 8, y + f + 16, "Every Pokémon this Pokédex lists is caught.", PD_LABEL, PD_LABEL_SHADOW);
    } else {
        row_list(l, c, y, LIB_H - y - BUTTON_H - 2 * LIB_M, rows, ids, s->missingCount, s->missingRow, &s->missingScroll, 170,
                 lastRow != s->missingRow);
    }
    lastRow = s->missingRow;
    static const char* const LABELS[1] = { "BACK" };
    static const int HITS[1] = { HIT_DONE };
    bottom_buttons(l, c, 1, LABELS, HITS);
}

static void trainer_tab(struct pd_library* l, struct pd_canvas* c) {
    if (S(l)->missingOpen) {
        missing_tab(l, c);
        return;
    }
    struct pd_library_saves* s = S(l);
    struct pd_save* sv = &s->sides[0].save;
    int y = TITLE_Y + TITLE_H + LIB_M;
    int ids[16], n = trainer_rows(s, ids);
    struct pd_save_dex d;
    pd_save_dex_counts(sv, &d);
    static struct row_draw rows[16];
    for (int i = 0; i < n; i++) {
        struct row_draw* r = &rows[i];
        memset(r, 0, sizeof *r);
        int id = ids[i];
        if (id == T_MONEY) {
            r->label = "MONEY";
            money_text(pd_save_money(sv), r->value, sizeof r->value);
        } else if (id == T_COINS) {
            r->label = "COINS";
            snprintf(r->value, sizeof r->value, "%d", pd_save_coins(sv));
        } else if (id == TG_DEX) {
            r->label = "POKéDEX";
            r->group = true;
            snprintf(r->value, sizeof r->value, "%s", d.national ? "NATIONAL" : d.region);
        } else if (id == T_REGISTER) {
            r->label = "REGISTER";
            snprintf(r->value, sizeof r->value, "PARTY + PC");
        } else if (id == T_SEEN || id == T_CAUGHT) {
            r->label = id == T_SEEN ? "ALL SEEN" : "ALL CAUGHT";
            int have = id == T_SEEN ? (d.national ? d.seen : d.regionalSeen) : (d.national ? d.caught : d.regionalCaught);
            snprintf(r->value, sizeof r->value, "%d/%d", have, d.national ? 386 : d.regionalSize);
        } else if (id == T_MISSING) {
            r->label = "MISSING";
            snprintf(r->value, sizeof r->value, "%d NOT CAUGHT", (d.national ? 386 - d.caught : d.regionalSize - d.regionalCaught));
        } else if (id == TG_EVENTS) {
            r->label = "EVENTS";
            r->group = true;
        } else if (id >= T_EVENT) {
            enum pd_save_event e = (enum pd_save_event) (id - T_EVENT);
            enum pd_event_state st = pd_save_event_state(sv, e);
            r->label = pd_save_event_name(e);
            snprintf(r->value, sizeof r->value, "%s", st == PD_EVENT_AVAILABLE ? "GIVE IT" : st == PD_EVENT_HAS ? "IN THE BAG" : "DONE");
            r->muted = st != PD_EVENT_AVAILABLE;
        }
    }
    static int lastRow = -1;
    row_list(l, c, y, LIB_H - y - BUTTON_H - 2 * LIB_M, rows, ids, n, s->trainerRow, &s->trainerScroll, 104, lastRow != s->trainerRow);
    lastRow = s->trainerRow;
    static const char* const LABELS[1] = { "SAVE" };
    static const int HITS[1] = { HIT_WRITE };
    bottom_buttons(l, c, 1, LABELS, HITS);
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
        editor_tabs(l, c);
        if (s->monPage) mon_page(l, c);
        else if (s->tab == TAB_BAG) bag_tab(l, c);
        else if (s->tab == TAB_TRAINER) trainer_tab(l, c);
        else {
            int top = TITLE_Y + TITLE_H + LIB_M;
            box_view(l, c, &s->sides[0], top, LIB_H - top - BUTTON_H - 2 * LIB_M);
            static const char* const LABELS[BUTTONS] = { "EDIT", "BOX", "SAVE" };
            buttons(l, c, LABELS, -1);
        }
    } else {
        // TRADE: a tab per side over its party or box, the battery in a window of its own at the right.
        int y = TITLE_Y, gap = 4, bw = lib_battery_width() ? lib_battery_width() + 14 : 0;
        int w = (PAGE_W - gap - (bw ? bw + gap : 0)) / 2;
        if (bw) lib_battery_box(c, PAGE_X + PAGE_W - bw, y, bw, TITLE_H);
        for (int i = 0; i < 2; i++) {
            const struct pd_library_game* g = &l->games[s->sides[i].game];
            int x = PAGE_X + i * (w + gap);
            bool on = s->active == i, marked = s->sides[i].mark != PD_AT_NONE;
            pd_layered_two_tone(c, x, y, w, TITLE_H, 3, pd_cur->chip, pd_cur->chipCount, on ? pd_cur->tabSelectedFill : pd_cur->tabIdleFill, 0);
            // The games' own names without the series' (two of them share the row with the battery).
            const char* name = !strncmp(g->title, "Pokémon ", strlen("Pokémon ")) ? g->title + strlen("Pokémon ") : g->title;
            pd_text_fit(c, x + 6, pd_text_y(y, TITLE_H), w - 12 - (marked ? 10 : 0), name, on ? PD_VALUE : PD_LABEL,
                        on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
            if (marked) pd_round_rect(c, x + w - 12, y + (TITLE_H - 6) / 2, 6, 6, 3, PD_VALUE);
            if (on) pd_fill(c, x + 6, y + TITLE_H - 5, w - 12, 2, PD_VALUE);
            lib_hit(x, y, w, TITLE_H, i ? HIT_SIDE_B : HIT_SIDE_A);
        }
        int top = TITLE_Y + TITLE_H + LIB_M;
        box_view(l, c, &s->sides[s->active], top, LIB_H - top - BUTTON_H - 2 * LIB_M);
        static const char* const LABELS[BUTTONS] = { "TRADE", "UNDO ALL", "SAVE" };
        buttons(l, c, LABELS, -1);
    }
    if (s->dialog == NUMBER) number_dialog(s, c);
    else if (s->dialog == PICK) pick_dialog(l, c);
    else if (s->dialog == MESSAGE) lib_dialog(c, s->title, s->line1, s->line2[0] ? s->line2 : NULL, "OK", NULL);
    else if (s->dialog != NONE) lib_dialog(c, s->title, s->line1[0] ? s->line1 : NULL, s->line2[0] ? s->line2 : NULL, s->yes, s->no);
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
    else snprintf(text, sizeof text, "Lv%d  %s", m->level, species_text(s, m->species, name, sizeof name));
    pd_text_fit(c, tx, top + 18, tw, text, PD_VALUE, PD_VALUE_SHADOW);
    if (!m->egg) {
        snprintf(text, sizeof text, "%s%s", pd_nature_names[m->personality % 25], m->shiny ? "  SHINY" : "");
        pd_text_fit(c, tx, top + 36, tw, text, PD_LABEL, PD_LABEL_SHADOW);
        char item[32];
        snprintf(text, sizeof text, "ITEM  %s", item_text(s, m->heldItem, item, sizeof item));
        pd_text_fit(c, tx, top + 54, tw, text, PD_MUTED, PD_MUTED_SHADOW);
    }
    // Its moves, two to a row.
    int my = top + well + 6;
    for (int i = 0; !m->egg && i < PD_NUM_MOVES && my + 18 <= y + h - f; i++) {
        if (!m->moves[i]) continue;
        int mx = x + f + 6 + (i % 2) * ((w - 2 * f - 12) / 2), ry = my + (i / 2) * 18;
        if (ry + 16 > y + h - f) break;
        pd_text_fit(c, mx, ry, (w - 2 * f - 12) / 2 - 4, move_text(s, m->moves[i], name, sizeof name), PD_LABEL, PD_LABEL_SHADOW);
    }
}

// Beside a Pokémon: its stats with the IVs and EVs behind them, its ability, OT and friendship.
static void stats_window(struct pd_library_saves* s, struct pd_canvas* c, int at, int x, int y, int w, int h) {
    struct pd_save* sv = &s->sides[0].save;
    int f = pd_list_box(c, x, y, w, h);
    struct pd_box_mon m;
    if (!pd_save_record_at(sv, at, &m) || pd_box_mon_egg(&m) || pd_box_mon_bad_egg(&m)) return;
    int stats[6], hp, ivs[6], evs[6];
    pd_save_stats_at(sv, at, stats, &hp);
    pd_box_mon_ivs(&m, ivs);
    pd_box_mon_evs(&m, evs);
    int nature = (int) (pd_box_mon_personality(&m) % 25), up = pd_nature_raises(nature), down = pd_nature_lowers(nature);
    int lx = x + f + 6, ty = y + f + 4, cw = w - 2 * f - 12;
    static const char* const SHORT[6] = { "HP", "ATK", "DEF", "SPD", "SP.A", "SP.D" };
    pd_text_right(c, lx + cw - 34, ty, "IV", PD_MUTED, PD_MUTED_SHADOW);
    pd_text_right(c, lx + cw, ty, "EV", PD_MUTED, PD_MUTED_SHADOW);
    for (int i = 0; i < 6; i++) {
        int ry = ty + 18 + i * 17;
        // The nature's stats: raised red, lowered blue (the summary's colours).
        uint32_t lc = i == up ? PD_VALUE : i == down ? 0x3060D0 : PD_LABEL;
        pd_text(c, lx, ry, SHORT[i], lc, PD_LABEL_SHADOW);
        char v[16];
        if (i == 0) snprintf(v, sizeof v, "%d/%d", hp, stats[0]);
        else snprintf(v, sizeof v, "%d", stats[i]);
        pd_text_right(c, lx + cw - 60, ry, v, PD_LABEL, PD_LABEL_SHADOW);
        snprintf(v, sizeof v, "%d", ivs[i]);
        pd_text_right(c, lx + cw - 34, ry, v, ivs[i] == 31 ? PD_VALUE : PD_LABEL, ivs[i] == 31 ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        snprintf(v, sizeof v, "%d", evs[i]);
        pd_text_right(c, lx + cw, ry, v, PD_LABEL, PD_LABEL_SHADOW);
    }
    int species = pd_box_mon_species(&m), by = ty + 18 + 6 * 17 + 6;
    pd_text_fit(c, lx, by, cw, pd_save_ability_name(pd_save_ability(species, pd_box_mon_ability_num(&m))), PD_VALUE, PD_VALUE_SHADOW);
    char ot[32], text[48];
    pd_gen3_text(m.header + 0x14, 7, ot, sizeof ot);
    snprintf(text, sizeof text, "OT %s  %05u", ot, (unsigned) (pd_box_mon_ot_id(&m) & 0xFFFF));
    pd_text_fit(c, lx, by + 18, cw, text, PD_MUTED, PD_MUTED_SHADOW);
}

static void tool_help(struct pd_library_saves* s, struct pd_canvas* c) {
    lib_top_title(c, s->page, tool_title(s), NULL);
    int lf = pd_list_box(c, TOP_M, TOP_BODY_Y, TOP_W - 2 * TOP_M, TOP_BODY_END - TOP_BODY_Y);
    static const char* const EDIT[] = {
        "Your party and PC, a Pokémon's level, nature,",
        "IVs, EVs, item and moves, the bag, money and",
        "the Pokédex: only values the game itself could",
        "give, then SAVE. A backup of the old save is kept.",
        "",
        "FireRed, LeafGreen, Ruby, Sapphire and Emerald.",
    };
    static const char* const TRADE[] = {
        "Trade Pokémon between two saves, party or PC,",
        "by the games' own rules: trade evolutions, the",
        "Pokédex, and what each game takes before its",
        "National Dex. Both saves are written.",
        "",
        "FireRed, LeafGreen, Ruby, Sapphire and Emerald.",
    };
    const char* const* lines = s->page == PD_PAGE_EDITOR ? EDIT : TRADE;
    for (int i = 0; i < 6; i++) pd_text_fit(c, TOP_M + lf + 8, TOP_BODY_Y + lf + 6 + i * 20, TOP_W - 2 * TOP_M - 2 * lf - 16, lines[i], i == 5 ? PD_MUTED : PD_LABEL,
                                            i == 5 ? PD_MUTED_SHADOW : PD_LABEL_SHADOW);
}

// The save at a glance (right of the top screen).
static void glance(struct pd_library_saves* s, struct pd_canvas* c, int x) {
    struct side* side = &s->sides[0];
    int w = TOP_W - TOP_M - x;
    int f = pd_list_box(c, x, TOP_M, w, TOP_BODY_END - TOP_M);
    char text[40], name[16];
    pd_save_trainer_name(&side->save, name, sizeof name);
    int ty = TOP_M + f + 4;
    pd_text(c, x + f + 6, ty, "PLAYER", PD_LABEL, PD_LABEL_SHADOW);
    pd_text_fit(c, x + f + 6, ty + 16, w - 2 * f - 12, name, PD_VALUE, PD_VALUE_SHADOW);
    pd_text(c, x + f + 6, ty + 38, "MONEY", PD_LABEL, PD_LABEL_SHADOW);
    pd_text_fit(c, x + f + 6, ty + 54, w - 2 * f - 12, money_text(pd_save_money(&side->save), text, sizeof text), PD_VALUE, PD_VALUE_SHADOW);
    struct pd_save_dex d;
    pd_save_dex_counts(&side->save, &d);
    snprintf(text, sizeof text, "%d / %d", d.national ? d.caught : d.regionalCaught, d.national ? 386 : d.regionalSize);
    pd_text(c, x + f + 6, ty + 76, "POKéDEX", PD_LABEL, PD_LABEL_SHADOW);
    pd_text_fit(c, x + f + 6, ty + 92, w - 2 * f - 12, text, PD_VALUE, PD_VALUE_SHADOW);
    if (pd_save_changed(&side->save)) pd_text_fit(c, x + f + 6, ty + 122, w - 2 * f - 12, "Not saved yet", PD_MUTED, PD_MUTED_SHADOW);
}

static void bag_top(struct pd_library_saves* s, struct pd_canvas* c) {
    struct pd_save* sv = &s->sides[0].save;
    enum pd_save_pocket p = (enum pd_save_pocket) s->pocket;
    struct pd_stack st[64];
    int n = pd_save_pocket(sv, p, st, 64), x = TOP_M, w = 236, h = TOP_BODY_END - TOP_M;
    int f = pd_title_box(c, x, TOP_M, w, h, PD_TITLE_FILL);
    int lx = x + f + 6, ty = TOP_M + f + 4, cw = w - 2 * f - 12;
    pd_text(c, lx, ty, POCKET_NAMES[p], PD_MUTED, PD_MUTED_SHADOW);
    char text[64], name[32];
    if (s->bagRow >= n) {
        snprintf(text, sizeof text, "%d of %d places used.", n, pd_save_pocket_slots(sv, p));
        pd_text_fit(c, lx, ty + 24, cw, text, PD_LABEL, PD_LABEL_SHADOW);
        pd_text_wrap(c, lx, ty + 48, cw, 16, 4,
                     p == PD_BAG_KEY ? "Key items come from the story. The event tickets are on TRAINER."
                                     : "ADD AN ITEM offers what this game files in this pocket, and only that.",
                     PD_MUTED, PD_MUTED_SHADOW);
        return;
    }
    int item = st[s->bagRow].item;
    pd_text_fit(c, lx, ty + 24, cw, item_text(s, item, name, sizeof name), PD_LABEL, PD_LABEL_SHADOW);
    if (p != PD_BAG_KEY) {
        snprintf(text, sizeof text, "x%d  (a stack holds %d)", st[s->bagRow].quantity, pd_save_stack_max(sv, p, item));
        pd_text_fit(c, lx, ty + 42, cw, text, PD_VALUE, PD_VALUE_SHADOW);
    }
    int teaches = pd_save_tmhm_move(item);
    if (teaches) {
        snprintf(text, sizeof text, "TEACHES  %s", move_text(s, teaches, name, sizeof name));
        pd_text_fit(c, lx, ty + 64, cw, text, PD_LABEL, PD_LABEL_SHADOW);
    }
    if (pd_save_stack_fixed(p, item))
        pd_text_wrap(c, lx, ty + 90, cw, 16, 3, p == PD_BAG_KEY ? "Key items stay: the story needs them." : "HMs are handed out once and stay.", PD_MUTED,
                     PD_MUTED_SHADOW);
}

// MISSING's top screen: the species on the cursor, where the one to bring is and how it comes, or why it can't yet.
static void missing_top(struct pd_library* l, struct pd_canvas* c) {
    struct pd_library_saves* s = S(l);
    int x = TOP_M, w = TOP_W - 2 * TOP_M, h = TOP_BODY_END - TOP_M;
    int f = pd_title_box(c, x, TOP_M, w, h, PD_TITLE_FILL);
    int lx = x + f + 8, ty = TOP_M + f + 6, cw = w - 2 * f - 16;
    char text[160], name[24], owned[24];
    snprintf(text, sizeof text, "MISSING  %d NOT CAUGHT", s->missingCount);
    pd_text(c, lx, ty, text, PD_MUTED, PD_MUTED_SHADOW);
    if (!s->missingCount) return;
    const struct pd_missing* e = &missing[s->missingRow];
    snprintf(text, sizeof text, "No.%03d %s", e->number, species_text(s, e->species, name, sizeof name));
    pd_text_fit(c, lx, ty + 24, cw, text, PD_VALUE, PD_VALUE_SHADOW);
    const char* game = missing_game(l, e);
    if (!game) {
        pd_text_wrap(c, lx, ty + 52, cw, 16, 3, "None of your other GBA saves has one, or one that evolves into it by trading.", PD_LABEL, PD_LABEL_SHADOW);
        return;
    }
    char where[24];
    if (PD_AT_IS_PARTY(e->at)) snprintf(where, sizeof where, "PARTY");
    else snprintf(where, sizeof where, "BOX %d", e->at / PD_SAVE_BOX_SLOTS + 1);
    species_text(s, e->owned, owned, sizeof owned);
    if (e->way == PD_MISSING_TRADE_BACK)
        snprintf(text, sizeof text, "Your %s Lv%d (%s) evolves into it on a trip to %s and back: TRADE SAVES, pick it alone, then Y.", owned, e->level, where, game);
    else if (e->way == PD_MISSING_EVOLVES)
        snprintf(text, sizeof text, "%s's %s Lv%d (%s) evolves into it as it arrives: TRADE SAVES.", game, owned, e->level, where);
    else snprintf(text, sizeof text, "%s has one, Lv%d (%s): TRADE SAVES brings it.", game, e->level, where);
    pd_text_wrap(c, lx, ty + 52, cw, 16, 4, text, PD_LABEL, PD_LABEL_SHADOW);
    if (!e->reachable) pd_text_wrap(c, lx, ty + 52 + 4 * 16 + 8, cw, 16, 3, e->why, PD_VALUE, PD_VALUE_SHADOW);
}

static void trainer_top(struct pd_library_saves* s, struct pd_canvas* c) {
    struct pd_save* sv = &s->sides[0].save;
    int x = TOP_M, w = TOP_W - 2 * TOP_M, h = TOP_BODY_END - TOP_M;
    int f = pd_title_box(c, x, TOP_M, w, h, PD_TITLE_FILL);
    int lx = x + f + 8, ty = TOP_M + f + 6, vx = lx + 120;
    char name[16], text[64];
    pd_save_trainer_name(sv, name, sizeof name);
    pd_text(c, lx, ty, name, PD_VALUE, PD_VALUE_SHADOW);
    snprintf(text, sizeof text, "IDNo. %05d", pd_save_trainer_id(sv));
    pd_text_right(c, x + w - f - 8, ty, text, PD_LABEL, PD_LABEL_SHADOW);
    struct pd_save_dex d;
    pd_save_dex_counts(sv, &d);
    struct { const char* label; char value[40]; } lines[6];
    lines[0].label = "MONEY";
    money_text(pd_save_money(sv), lines[0].value, sizeof lines[0].value);
    lines[1].label = "COINS";
    snprintf(lines[1].value, sizeof lines[1].value, "%d", pd_save_coins(sv));
    lines[2].label = "TIME";
    snprintf(lines[2].value, sizeof lines[2].value, "%d:%02d", pd_save_play_hours(sv), pd_save_play_minutes(sv));
    lines[3].label = "BADGES";
    snprintf(lines[3].value, sizeof lines[3].value, "%d", pd_save_badges(sv));
    lines[4].label = d.region[0] == 'K' ? "KANTO DEX" : "HOENN DEX";
    snprintf(lines[4].value, sizeof lines[4].value, "%d seen, %d caught", d.regionalSeen, d.regionalCaught);
    lines[5].label = "NATIONAL DEX";
    if (d.national) snprintf(lines[5].value, sizeof lines[5].value, "%d seen, %d caught", d.seen, d.caught);
    else snprintf(lines[5].value, sizeof lines[5].value, "not yet");
    for (int i = 0; i < 6; i++) {
        int ry = ty + 30 + i * 22;
        pd_text(c, lx, ry, lines[i].label, PD_LABEL, PD_LABEL_SHADOW);
        pd_text_fit(c, vx, ry, x + w - f - 8 - vx, lines[i].value, PD_VALUE, PD_VALUE_SHADOW);
    }
    if (pd_save_changed(sv)) pd_text(c, lx, ty + 30 + 6 * 22 + 4, "Not saved yet", PD_MUTED, PD_MUTED_SHADOW);
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
        if (s->monPage) {
            struct pd_mon m;
            bool has = pd_save_mon_at(&side->save, s->monAt, &m);
            mon_window(s, c, has ? &m : NULL, TOP_M, TOP_M, 236, TOP_BODY_END - TOP_M, NULL, "");
            stats_window(s, c, s->monAt, TOP_M + 242, TOP_M, TOP_W - 2 * TOP_M - 242, TOP_BODY_END - TOP_M);
            lib_hint_bar(c, "LEFT RIGHT: CHANGE   A: PICK   L R: NEXT   X: SAVE   B: BACK");
            return;
        }
        if (s->tab == TAB_BAG) {
            bag_top(s, c);
            glance(s, c, TOP_M + 242);
            lib_hint_bar(c, "A: HOW MANY   Y: ADD   L R: POCKETS   X: SAVE   SELECT: TABS");
            return;
        }
        if (s->tab == TAB_TRAINER && s->missingOpen) {
            missing_top(l, c);
            lib_hint_bar(c, "UP DOWN: STEP   L R: PAGE   B: BACK");
            return;
        }
        if (s->tab == TAB_TRAINER) {
            trainer_top(s, c);
            lib_hint_bar(c, "A: CHANGE   X: SAVE   SELECT: TABS   B: CLOSE");
            return;
        }
        struct pd_mon m;
        int at = s->held != PD_AT_NONE ? s->held : cursor_at(s);
        bool has = at != PD_AT_NONE && pd_save_mon_at(&side->save, at, &m);
        mon_window(s, c, has ? &m : NULL, TOP_M, TOP_M, 236, TOP_BODY_END - TOP_M, s->held != PD_AT_NONE ? "MOVING" : NULL, "An empty place.");
        glance(s, c, TOP_M + 242);
        lib_hint_bar(c, s->held != PD_AT_NONE ? "A: PUT IT HERE   L R: PARTY / BOXES   B: PUT IT BACK"
                                              : "A: MOVE   Y: EDIT   L R: PARTY / BOXES   X: SAVE   SELECT: TABS");
        return;
    }
    // TRADE: each side's pick, the arrow between them, and whether the games would do it.
    struct pd_mon ms[2];
    bool has[2];
    for (int i = 0; i < 2; i++) {
        struct side* side = &s->sides[i];
        has[i] = side->mark != PD_AT_NONE && pd_save_mon_at(&side->save, side->mark, &ms[i]);
        char heading[72];
        snprintf(heading, sizeof heading, "%s", l->games[side->game].title);
        mon_window(s, c, has[i] ? &ms[i] : NULL, i ? TOP_W / 2 + 4 : TOP_M, TOP_M, TOP_W / 2 - TOP_M - 4, 160, heading,
                   side->mark != PD_AT_NONE ? "An empty place: send here." : "Pick one below.");
    }
    pd_triangle(c, TOP_W / 2 - 2, 72, 3, PD_RIGHT, PD_ON_BACKDROP);
    pd_triangle(c, TOP_W / 2 - 1, 82, 3, PD_LEFT, PD_ON_BACKDROP);
    int sy = TOP_M + 160 + 6;
    int f = pd_list_box(c, TOP_M, sy, TOP_W - 2 * TOP_M, TOP_BODY_END - sy);
    const char* status = s->tradeNote[0] ? s->tradeNote : trades_changed(s) ? "Not saved yet." : "Pick a Pokémon on either side, then TRADE.";
    char why[160] = "";
    int tripInto = 0;
    if (trip_home(s, &tripInto)) {
        char name[24];
        snprintf(why, sizeof why, "Picked alone: Y trades it there and back as %s.", species_text(s, tripInto, name, sizeof name));
        status = why;
    }
    struct side *a = &s->sides[0], *b = &s->sides[1];
    if (a->mark != PD_AT_NONE && b->mark != PD_AT_NONE && (has[0] || has[1])) {
        const char* no = has[0] ? pd_trade_blocker(&a->trade, a->mark, &b->trade, why, sizeof why) : NULL;
        if (!no && has[1]) no = pd_trade_blocker(&b->trade, b->mark, &a->trade, why, sizeof why);
        int evolves = has[0] ? pd_trade_evolution(&a->trade, a->mark, &b->trade) : 0;
        if (!evolves && has[1]) evolves = pd_trade_evolution(&b->trade, b->mark, &a->trade);
        if (no) status = no;
        else if (evolves) {
            char name[24];
            snprintf(why, sizeof why, "Ready. It evolves into %s on arrival.", species_text(s, evolves, name, sizeof name));
            status = why;
        } else status = "Ready to trade.";
    }
    pd_text_wrap(c, TOP_M + f + 8, sy + f + 2, TOP_W - 2 * TOP_M - 2 * f - 16, 16, 2, status, PD_LABEL, PD_LABEL_SHADOW);
    lib_hint_bar(c, "A: PICK   L R: BOXES   X: OTHER SIDE   Y: TRADE   SELECT: SAVE");
}
