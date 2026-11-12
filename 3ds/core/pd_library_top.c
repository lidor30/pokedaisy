// The library's top screen: the game under the cursor at a glance, the way the games' own CONTINUE screen shows a save
// (PLAYER / TIME / POKéDEX / BADGES), over that game's own backdrop colours - and the party it was left with.
// The numbers come from the save file itself where the save editor reads it (fresh after an edit or a trade), the place
// and the party's icons from the card the last session left (pd_library_card.c).
#include <stdio.h>
#include <string.h>

#include "pd_games.h"
#include "pd_icon.h"
#include "pd_library_internal.h"
#include "pd_save.h"
#include "pd_ui.h"

#define TOP_W 400
#define TOP_H 240
#define HINT_H 22

void lib_hint_bar(struct pd_canvas* c, const char* text) {
    uint32_t fill = pd_dark ? 0x1A1D24 : 0x404850, shadow = pd_dark ? 0x000000 : 0x606870;
    int y = c->h - HINT_H;
    pd_fill(c, 0, y, c->w, HINT_H, fill);
    pd_fill(c, 0, y, c->w, 1, pd_dark ? 0x30343C : 0x586068);
    pd_text_fit(c, 8, pd_text_y(y, HINT_H), c->w - 16, text, 0xF8F8F8, shadow);
}

int lib_top_title(struct pd_canvas* c, int icon, const char* title, const char* right) {
    int f = pd_title_box(c, TOP_M, TOP_TITLE_Y, TOP_W - 2 * TOP_M, TOP_TITLE_H, PD_TITLE_FILL);
    int ty = pd_text_y(TOP_TITLE_Y, TOP_TITLE_H), rw = right ? pd_text_width(right) + 8 : 0, aw = LIB_ICON_SIZE;
    int ax = TOP_M + f + 4, ay = TOP_TITLE_Y + (TOP_TITLE_H - LIB_ICON_SIZE) / 2;
    if (icon < 0) lib_draw_logo_24(c, ax, ay);
    else lib_draw_icon_24(c, icon, ax, ay);
    pd_text_fit(c, TOP_M + f + aw + 10, ty, TOP_W - 2 * TOP_M - 2 * f - aw - 16 - rw, title, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    if (right) pd_text_right(c, TOP_W - TOP_M - f - 6, ty, right, PD_VALUE, PD_VALUE_SHADOW);
    return f;
}

// What the top screen shows of a game, worked out once its cursor has rested on it.
static struct info {
    int game;
    unsigned since; // the frame the cursor came to it
    bool read;      // tried
    bool fromSave, fromCard;
    char trainer[16], place[32];
    int hours, minutes, badges, badgeCount, dex, gender;
    long money;
    int partyCount;
    struct { int species, level; bool egg; const uint32_t* icon; char name[32]; } party[6];
} info = { .game = -1 };

void lib_top_reset(void) {
    info.game = -1;
    info.read = false;
}

static struct pd_save save;
static uint8_t saveBytes[PD_SAVE_MAX_SIZE];
static struct pd_game_card card;

static void read_info(struct pd_library* l, int game) {
    const struct pd_library_game* g = &l->games[game];
    const struct pd_library_host* h = l->host;
    memset(&info, 0, sizeof info);
    info.game = game;
    info.read = true;
    info.hours = info.badgeCount = info.dex = -1;
    info.money = -1;
    bool hasCard = h && h->read_card && h->read_card(h->ctx, g->file, &card) && card.magic == PD_CARD_MAGIC;
    const struct pd_save_layout* f = pd_save_layout_for(g->code);
    if (f && h && h->read_save) {
        long n = h->read_save(h->ctx, g->file, saveBytes, sizeof saveBytes);
        if (n > 0 && !pd_save_parse(&save, saveBytes, (size_t) n, f) && !pd_save_mismatch(&save)) {
            info.fromSave = true;
            pd_save_trainer_name(&save, info.trainer, sizeof info.trainer);
            info.hours = pd_save_play_hours(&save);
            info.minutes = pd_save_play_minutes(&save);
            info.money = pd_save_money(&save);
            info.badgeCount = 0;
            for (int i = 0; i < 8; i++)
                if (pd_save_flag(&save, f->badgeFlag + i)) info.badges |= 1 << i, info.badgeCount++;
            info.dex = pd_save_dex_caught(&save);
            info.gender = pd_save_gender(&save);
            info.partyCount = pd_save_party_count(&save);
            if (info.partyCount > 6) info.partyCount = 6;
            for (int i = 0; i < info.partyCount; i++) {
                struct pd_mon m;
                if (!pd_save_party_mon(&save, i, &m)) continue;
                info.party[i].species = m.species;
                info.party[i].level = m.level;
                info.party[i].egg = m.egg;
                snprintf(info.party[i].name, sizeof info.party[i].name, "%s", m.nickname);
            }
        }
    }
    if (hasCard) {
        info.fromCard = true;
        snprintf(info.place, sizeof info.place, "%s", card.place);
        if (!info.fromSave) {
            snprintf(info.trainer, sizeof info.trainer, "%s", card.trainer);
            info.hours = card.playHours;
            info.minutes = card.playMinutes;
            info.money = card.money;
            info.badges = card.badges;
            info.badgeCount = card.badgeCount;
            info.dex = card.dexCaught;
            info.gender = card.gender;
            info.partyCount = card.partyCount;
            for (int i = 0; i < card.partyCount && i < 6; i++) {
                info.party[i].species = card.party[i].species;
                info.party[i].level = card.party[i].level;
                info.party[i].egg = card.party[i].egg;
                snprintf(info.party[i].name, sizeof info.party[i].name, "%s", card.party[i].name);
            }
        }
        // The icons as the game drew them, where the save still has that Pokémon in that place.
        for (int i = 0; i < info.partyCount && i < card.partyCount; i++)
            if (info.party[i].species == card.party[i].species) info.party[i].icon = card.icons[i];
    }
}

static void draw_icon(struct pd_canvas* c, const uint32_t* px, int x, int y) {
    for (int r = 0; r < PD_CARD_ICON; r++)
        for (int col = 0; col < PD_CARD_ICON; col++)
            if (px[r * PD_CARD_ICON + col]) pd_fill(c, x + col, y + r, 1, 1, px[r * PD_CARD_ICON + col] & 0xFFFFFF);
}

// A badge pip: a gold one for each badge held, an empty socket for the rest (the trainer card's case, in miniature).
static void badge(struct pd_canvas* c, int x, int y, bool has) {
    pd_round_rect(c, x, y, 12, 12, 6, pd_dark ? 0x101218 : 0x585860);
    pd_round_rect(c, x + 1, y + 1, 10, 10, 5, has ? 0xF8C830 : (pd_dark ? 0x3A3E48 : 0xC8C8D0));
    if (has) pd_fill(c, x + 3, y + 3, 3, 2, 0xFFF0A0);
}

static void info_row(struct pd_canvas* c, int x, int y, int w, const char* label, const char* value) {
    pd_text(c, x, pd_text_y(y, 18), label, PD_LABEL, PD_LABEL_SHADOW);
    if (value) pd_text_fit(c, x + 92, pd_text_y(y, 18), w - 92, value, PD_VALUE, PD_VALUE_SHADOW);
}

static void game_top(struct pd_library* l, struct pd_canvas* c) {
    const struct lib_list* list = &l->list;
    const struct pd_library_game* g = list->count ? &l->games[list->items[list->selected]] : NULL;
    pd_look_apply(NULL, l->settings);
    lib_page_backdrop(c);
    // The title window: the logo, the game's name, its tags.
    if (!g) {
        lib_top_title(c, -1, "POKéDAISY", NULL);
        int lf = pd_list_box(c, TOP_M, TOP_BODY_Y, TOP_W - 2 * TOP_M, 96);
        pd_text(c, TOP_M + lf + 8, TOP_BODY_Y + lf + 8, l->count ? "No games in this view." : "No games yet.", PD_LABEL, PD_LABEL_SHADOW);
        if (!l->count) {
            pd_text(c, TOP_M + lf + 8, TOP_BODY_Y + lf + 28, "Put your games in", PD_LABEL, PD_LABEL_SHADOW);
            pd_text(c, TOP_M + lf + 8, TOP_BODY_Y + lf + 46, "sdmc:/pokedaisy/roms/", PD_VALUE, PD_VALUE_SHADOW);
        }
        lib_hint_bar(c, "L R: VIEWS   SELECT: PAGES   START: CLOSE");
        return;
    }
    int game = list->items[list->selected];
    if (info.game != game) {
        info.game = game;
        info.read = false;
        info.since = l->frame;
    }
    // Read once the cursor rests a moment (holding the D-pad through a long list reads nothing on the way).
    if (!info.read && l->frame - info.since >= 6) read_info(l, game);
    if (!info.read) l->dirty = true;
    char tags[32];
    snprintf(tags, sizeof tags, "%s%s", lib_console_tag(g), g->hack ? "  HACK" : "");
    lib_top_title(c, -1, g->title, tags);

    // The CONTINUE window.
    int x = TOP_M, y = TOP_BODY_Y, w = 236, h = TOP_BODY_END - TOP_BODY_Y - 24 - 6;
    int lf = pd_list_box(c, x, y, w, h);
    int rx = x + lf + 8, rw = w - 2 * lf - 16, ry = y + lf + 4;
    char text[48];
    if (!info.read) {
        pd_text(c, rx, pd_text_y(ry, 18), "...", PD_MUTED, PD_MUTED_SHADOW);
    } else if (!info.fromSave && !info.fromCard) {
        pd_text_fit(c, rx, pd_text_y(ry, 18), rw, "No save read yet.", PD_LABEL, PD_LABEL_SHADOW);
        pd_text_fit(c, rx, pd_text_y(ry + 20, 18), rw, "Play it once and its", PD_MUTED, PD_MUTED_SHADOW);
        pd_text_fit(c, rx, pd_text_y(ry + 38, 18), rw, "save shows here.", PD_MUTED, PD_MUTED_SHADOW);
    } else {
        info_row(c, rx, ry, rw, "PLAYER", info.trainer[0] ? info.trainer : "-");
        ry += 19;
        if (info.hours >= 0) snprintf(text, sizeof text, "%d:%02d", info.hours, info.minutes);
        else snprintf(text, sizeof text, "-");
        info_row(c, rx, ry, rw, "TIME", text);
        ry += 19;
        if (info.dex >= 0) snprintf(text, sizeof text, "%d", info.dex);
        else snprintf(text, sizeof text, "-");
        info_row(c, rx, ry, rw, "POKéDEX", text);
        ry += 19;
        info_row(c, rx, ry, rw, "BADGES", info.badgeCount < 0 ? "-" : NULL);
        for (int i = 0; info.badgeCount >= 0 && i < 8; i++) badge(c, rx + 92 + i * 15, ry + 3, info.badges >> i & 1);
        ry += 19;
        if (info.money >= 0) snprintf(text, sizeof text, "$%ld", info.money);
        else snprintf(text, sizeof text, "-");
        info_row(c, rx, ry, rw, "MONEY", text);
        ry += 19;
        info_row(c, rx, ry, rw, "PLACE", info.place[0] ? info.place : "-");
    }

    // The party, two rows of three, each its icon and level.
    int px = x + w + 6, pw = TOP_W - TOP_M - px;
    int pf = pd_title_box(c, px, y, pw, h, PD_TITLE_FILL);
    pd_text(c, px + pf + 6, y + pf + 2, "PARTY", PD_LABEL, PD_LABEL_SHADOW);
    int cellW = (pw - 2 * pf - 4) / 3, cellH = 52;
    for (int i = 0; i < 6; i++) {
        int cx = px + pf + 2 + (i % 3) * cellW, cy = y + pf + 20 + (i / 3) * cellH;
        if (i >= info.partyCount) {
            pd_round_rect(c, cx + (cellW - 16) / 2, cy + 12, 16, 16, 8, pd_dark ? 0x2A2E36 : 0xE0E0E8);
            continue;
        }
        if (info.party[i].icon) draw_icon(c, info.party[i].icon, cx + (cellW - 32) / 2, cy);
        else pd_draw_party_ball(c, cx + (cellW - 20) / 2, cy + 8);
        if (info.party[i].egg) snprintf(text, sizeof text, "EGG");
        else snprintf(text, sizeof text, "Lv%d", info.party[i].level);
        int tw = pd_text_width(text);
        pd_text(c, cx + (cellW - tw) / 2, cy + 27, text, PD_LABEL, PD_LABEL_SHADOW);
    }

    // The file, and what the buttons do.
    int fy = y + h + 6;
    int ff = pd_title_box(c, TOP_M, fy, TOP_W - 2 * TOP_M, 24, PD_TITLE_FILL);
    pd_text_fit(c, TOP_M + ff + 6, pd_text_y(fy, 24), TOP_W - 2 * TOP_M - 2 * ff - 12, g->file, PD_MUTED, PD_MUTED_SHADOW);
    lib_hint_bar(c, g->supported || g->console != PD_CONSOLE_GBA ? "A: PLAY   L R: VIEWS   SELECT: PAGES   START: CLOSE"
                                                                   : "A: PLAY (no companion for it yet)   L R: VIEWS");
}

// SETTINGS: the app's name, its version, and what the row under the cursor does.
static void settings_top(struct pd_library* l, struct pd_canvas* c) {
    pd_look_apply(NULL, l->settings);
    lib_page_backdrop(c);
    lib_top_title(c, -1, "POKéDAISY", l->host ? l->host->version : NULL);
    int bh = TOP_BODY_END - TOP_BODY_Y;
    pd_title_box(c, TOP_M, TOP_BODY_Y, 160, bh, PD_TITLE_FILL);
    ui_art_at(c, &PD_LOGO, TOP_M + (160 - 96) / 2, TOP_BODY_Y + (bh - 96) / 2, 3);
    int x = TOP_M + 166, w = TOP_W - TOP_M - x;
    int df = pd_list_box(c, x, TOP_BODY_Y, w, bh);
    const char* value;
    bool group;
    int choices;
    const char* label = lib_settings_label(l, l->settingsSelected, &value, &group, &choices);
    pd_text(c, x + df + 8, TOP_BODY_Y + df + 6, label, PD_VALUE, PD_VALUE_SHADOW);
    static const char* const HELP[][2] = {
        { "VIEW", "The games as a LIST of rows, or a GRID of tiles." },
        { "SORT", "By NAME, or the last one played first." },
        { "THEME", "The colours of every window, here and in the companion." },
        { "DARK MODE", "Darker windows and backdrops." },
        { "SCREEN", "How large the game is drawn on the top screen." },
        { "ASPECT", "EXPAND draws the map past the GBA's edges." },
        { "GAME VOLUME", "How loud the game plays." },
        { "MUTE GAME", "No game sound at all." },
        { "BATTLE STYLE", "The battle buttons as HeartGold's or Black's." },
        { "BATTLE HINTS", "Ratings of each move against the foe." },
        { "BATTLE INFO", "INFO and SUGGESTIONS beside the buttons." },
        { "HUD", "The party and minimap over the overworld." },
        { "FLY STYLE", "SCENIC flies over the map, CLASSIC is the game's." },
        { "VERSION", "This build of PokeDaisy." },
        { "DEV LINK", "Where the dev tools reach this 3DS." },
    };
    for (size_t i = 0; i < sizeof HELP / sizeof HELP[0]; i++)
        if (!strcmp(HELP[i][0], label))
            pd_text_wrap(c, x + df + 8, TOP_BODY_Y + df + 26, w - 2 * df - 16, 18, 7, HELP[i][1], PD_LABEL, PD_LABEL_SHADOW);
    lib_hint_bar(c, l->pickRow >= 0 ? "A: PICK   B: BACK" : "A: CHANGE   B: GAMES   SELECT: PAGES");
}

void pd_library_draw_top(struct pd_library* l, struct pd_canvas* c) {
    pd_font_set(NULL, 0); // the library's own font (a Game Boy game leaves its own installed)
    switch (l->page) {
    case PD_PAGE_GAMES: game_top(l, c); break;
    case PD_PAGE_SETTINGS: settings_top(l, c); break;
    default:
        pd_look_apply(NULL, l->settings);
        lib_saves_draw_top(l, c);
        break;
    }
    // The bottom screen is drawn in the library's own look.
    pd_look_apply(NULL, l->settings);
}


// The moment a game is picked: the library stays up, dimmed, with LOADING over it on both screens until the game's first
// frame (reading a 16-32 MB game off the SD card takes a few seconds, and nothing moved before).
void pd_library_draw_loading(struct pd_library* l, struct pd_canvas* bottom, struct pd_canvas* top) {
    if (l->picked < 0) return;
    pd_font_set(NULL, 0);
    const struct pd_library_game* g = &l->games[l->picked];
    l->page = PD_PAGE_GAMES;
    pd_library_select(l, g->file);
    if (info.game != l->picked || !info.read) read_info(l, l->picked);
    pd_library_draw(l, bottom);
    lib_dialog(bottom, "LOADING", g->title, "Just a moment...", NULL, NULL);
    pd_draw_party_ball(bottom, (LIB_W + 260) / 2 - 34, (LIB_H - 20) / 2); // lib_dialog's window: 260 wide, centred
    game_top(l, top);
    char text[96];
    snprintf(text, sizeof text, "Starting %s...", g->title);
    lib_hint_bar(top, text);
    pd_look_apply(NULL, l->settings);
}
