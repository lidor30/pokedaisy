// BATTLE: INFO (BattleInfoScreen.kt) and SUGGESTIONS (SuggestionsScreen.kt).
//
// INFO: one white window split by rules - the foe, you, then your moves with
// their plain-words verdicts against the foe. SUGGESTIONS: the foe on a VS
// line, then the party's three best picks against it, each with its best
// move (the app's rankSuggestions). Under either, a bar: the button to the
// other pane and, in a trainer battle, the FOE TEAM - the trainer's party in
// order, Poké Balls until sent out or announced, fainted ones faded, the one
// the panes are about framed in red. Tapping one shows it (NEXT / FAINTED /
// RESERVE / UNSEEN), tapping it again follows the battle.
#include <stdio.h>
#include <stdlib.h>

#include "pd_icon.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

#define BAR_H 34
#define ROW 34      // a card's row: the icon and two lines of text
#define RULE 5      // a separator and the space around it
#define FRAME 3     // the white window's frame
#define LINE 16     // PD_FONT_HEIGHT

// BattleControlsScreen's button colours.
#define SUGGEST_GOLD 0xE8B830
#define SUGGEST_GOLD_DARK 0xA87C10
#define INFO_PURPLE 0x9068D0
#define INFO_PURPLE_DARK 0x5C3C98
#define VERDICT_GREY 0x8C8C94
#define OUT_GREEN 0x48A858

// What a card shows: a battler (types from the battle) or a party Pokémon.
struct mon_view {
    int species, level, hp, maxHp;
    int type1, type2;
    uint32_t status;
    const int* moves;
    const int* pp;
    bool shiny;
};

static struct mon_view from_battler(const struct pd_battle_mon* b) {
    return (struct mon_view) { b->species, b->level, b->hp, b->maxHp, b->type1, b->type2, b->status, b->moves, b->pp, b->shiny };
}

static struct mon_view from_party(const struct pd_game* g, const struct pd_mon* m) {
    struct mon_view v = { m->species, m->level, m->hp, m->maxHp, PD_TYPE_NONE, PD_TYPE_NONE, m->status, m->moves, m->pp, m->shiny };
    pd_species_type_pair(g, m->species, &v.type1, &v.type2);
    return v;
}

static void types_text(const struct pd_game* g, int t1, int t2, char* buf, size_t len) {
    if (t1 == PD_TYPE_NONE) snprintf(buf, len, "-");
    else if (t2 != t1 && t2 != PD_TYPE_NONE) snprintf(buf, len, "%s/%s", pd_type_name(g, t1), pd_type_name(g, t2));
    else snprintf(buf, len, "%s", pd_type_name(g, t1));
}

// A small pill of white text (VerdictPill): returns its width.
static int pill(struct pd_canvas* c, int x, int y, const char* text, uint32_t fill) {
    int w = pd_text_width(text) + 6;
    pd_round_rect(c, x, y + 1, w, 14, 2, fill);
    pd_text(c, x + 3, y - 1, text, 0xFFFFFF, PD_NO_SHADOW);
    return w;
}

// The icon on the window's grey (a shiny one in its own colours, on pale gold in a gold frame: the app's monIconBox), or a
// ball when the ROM has no icon for it.
#define SHINY_FRAME 0xE0A820
#define SHINY_FILL 0xFFF4C8
static void icon(struct pd_canvas* c, const struct pd_game* g, int species, bool shiny, int x, int y, bool faded) {
    if (shiny) {
        pd_round_rect(c, x, y, PD_ICON + 2, PD_ICON + 2, 3, SHINY_FRAME);
        pd_round_rect(c, x + 1, y + 1, PD_ICON, PD_ICON, 2, SHINY_FILL);
    } else {
        pd_round_rect(c, x, y, PD_ICON + 2, PD_ICON + 2, 3, PD_LIST_FILL);
    }
    if (!pd_draw_mon_icon_shiny(c, g, species, shiny, x + 1, y + 1, 1, faded)) ui_ball(c, x + 12, y + 12, true);
}

// A card across w: icon, then [side] name ... [state] Lv on the first line,
// types, HP bar and HP on the second.
static void card(struct pd_canvas* c, const struct pd_game* g, int x, int y, int w, const struct mon_view* m,
                 const char* side, const char* state) {
    char buf[40];
    icon(c, g, m->species, m->shiny, x, y, false);
    int tx = x + PD_ICON + 8, right = x + w;
    int ty = y + 1;
    int nx = tx;
    if (side) nx += pd_text(c, nx, ty, side, PD_VALUE, PD_VALUE_SHADOW) + 5;
    char lv[12];
    snprintf(lv, sizeof(lv), "Lv%d", m->level);
    int lvx = right - pd_text_width(lv);
    pd_text(c, lvx, ty, lv, PD_LABEL, PD_LABEL_SHADOW);
    int stateW = state && *state ? pd_text_width(state) + 6 + 4 : 0;
    const char* status = pd_status_label(m->status);
    int statusW = *status ? pd_text_width(status) + 6 + 4 : 0;
    int nameEnd = nx + pd_text_fit(c, nx, ty, lvx - 6 - stateW - statusW - nx,
                                   pd_species_name(g, m->species, buf, sizeof(buf)), PD_LABEL, PD_LABEL_SHADOW);
    if (m->shiny) nameEnd += pd_text(c, nameEnd + 2, ty, "*", SHINY_FRAME, PD_LABEL_SHADOW) + 2;  // the app's ShinySparkle
    if (*status) {
        ui_status_badge(c, nameEnd + 4, ty + 1, m->status);
        nameEnd += statusW;
    }
    if (stateW) pill(c, nameEnd + 4, ty, state, VERDICT_GREY);

    ty += LINE + 1;
    types_text(g, m->type1, m->type2, buf, sizeof(buf));
    int typesW = pd_text_fit(c, tx, ty, 92, buf, PD_MUTED, PD_MUTED_SHADOW);
    char hp[16];
    snprintf(hp, sizeof(hp), "%d/%d", m->hp, m->maxHp);
    int hpx = right - pd_text_width(hp);
    pd_text(c, hpx, ty, hp, PD_LABEL, PD_LABEL_SHADOW);
    int barX = tx + (typesW > 92 ? typesW : 92) + 6;
    int barW = hpx - 6 - barX - 18;
    if (barW >= 24) ui_hp_bar(c, barX, ty + 5, barW, m->hp, m->maxHp);
}

// The plain-words verdict of BattleInfoScreen: SUPER 2x / RESISTED ½x / ...
static void verdict(int pct, const char** text, uint32_t* color, uint32_t* shadow) {
    if (pct == 0) { *text = "IMMUNE 0x"; *color = PD_MUTED; *shadow = PD_MUTED_SHADOW; }
    else if (pct >= 400) { *text = "SUPER 4x"; *color = PD_VALUE; *shadow = PD_VALUE_SHADOW; }
    else if (pct >= 200) { *text = "SUPER 2x"; *color = PD_VALUE; *shadow = PD_VALUE_SHADOW; }
    else if (pct <= 25) { *text = "RESISTED 1/4x"; *color = PD_MUTED; *shadow = PD_MUTED_SHADOW; }
    else if (pct < 100) { *text = "RESISTED 1/2x"; *color = PD_MUTED; *shadow = PD_MUTED_SHADOW; }
    else { *text = "NEUTRAL 1x"; *color = PD_LABEL; *shadow = PD_LABEL_SHADOW; }
}

// A move on the window's grey: its name and PP, then its verdict on foe
// (NULL = no foe: no verdict).
static void move_cell(struct pd_canvas* c, const struct pd_game* g, int x, int y, int w, int h, int move, int pp, const struct mon_view* foe) {
    char buf[40];
    pd_round_rect(c, x, y, w, h, 3, PD_LIST_FILL);
    if (!move) return;
    int ix = x + 5, iw = w - 10;
    int ty = y + (h - 2 * LINE - 1) / 2;
    char ppText[12];
    snprintf(ppText, sizeof(ppText), "PP %d", pp);
    int ppW = pd_text_width(ppText);
    pd_text_fit(c, ix, ty, iw - ppW - 6, pd_move_name(g, move, buf, sizeof(buf)), PD_LABEL, PD_LABEL_SHADOW);
    pd_text_right(c, ix + iw, ty, ppText, PD_MUTED, PD_MUTED_SHADOW);
    const char* text = "";
    uint32_t color = PD_LABEL, shadow = PD_LABEL_SHADOW;
    if (pd_move_power(g, move) == 0) {
        text = "STATUS";
        color = PD_MUTED;
        shadow = PD_MUTED_SHADOW;
    } else if (foe) {
        verdict(pd_type_multiplier_pct(g, pd_move_type(g, move), foe->type1, foe->type2), &text, &color, &shadow);
    }
    pd_text(c, ix, ty + LINE + 1, text, color, shadow);
}

static void rule(struct pd_canvas* c, int x, int y, int w) {
    pd_fill(c, x, y + RULE / 2, w, 1, PD_DIVIDER);
}

// A coloured square-cornered button (PlatinumButton / TextNavButton).
static void nav_button(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, const char* label,
                       uint32_t fill, uint32_t dark, int id) {
    bool pressed = ui->pressedId == id;
    static const struct pd_layer FRAME_LAYERS[1] = { { 0, 2 } };
    struct pd_layer frame = FRAME_LAYERS[0];
    frame.color = dark;
    pd_layered_box(c, x, y, w, h, 3, &frame, 1, pressed ? dark : fill);
    pd_text(c, x + (w - pd_text_width(label)) / 2, pd_text_y(y, h), label, 0xFFFFFF, dark);
    ui_add_hit(ui, x, y, w, h, id);
}

// The FOE TEAM, from x to the right edge.
static void foe_strip(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                      int x, int y, int right) {
    if (s->foeCount < 2) return;
    int cell = BAR_H, gap = 3;
    int need = s->foeCount * cell + (s->foeCount - 1) * gap;
    if (right - x < need) cell = (right - x - (s->foeCount - 1) * gap) / s->foeCount;
    int sx = right - (s->foeCount * cell + (s->foeCount - 1) * gap);
    for (int i = 0; i < s->foeCount; i++, sx += cell + gap) {
        bool shown = (ui->foeSeen >> i & 1) || i == ui->foeShown;
        bool current = i == ui->foeShown;
        struct pd_layer layers[2] = { { PD_VALUE, 2 }, { PD_VALUE_SHADOW, 1 } };
        struct pd_layer plain[2] = { { 0x63737B, 2 }, { 0xCED6D6, 1 } };
        pd_layered_box(c, sx, y, cell, cell, 3, current ? layers : plain, 2, shown ? PD_TITLE_FILL : PD_LIST_FILL);
        const struct pd_mon* m = &s->foes[i];
        if (!shown) {
            ui_ball(c, sx + (cell - 10) / 2, y + (cell - 10) / 2, true);
        } else if (!pd_draw_mon_icon_shiny(c, g, m->species, m->shiny, sx + (cell - PD_ICON) / 2, y + (cell - PD_ICON) / 2, 1,
                                           m->hp == 0)) {
            char lv[8];
            snprintf(lv, sizeof(lv), "%d", m->level);
            pd_text(c, sx + (cell - pd_text_width(lv)) / 2, pd_text_y(y, cell), lv,
                    m->hp ? PD_LABEL : PD_MUTED, PD_NO_SHADOW);
        }
        ui_add_hit(ui, sx, y, cell, cell, HIT_FOE_SLOT + i);
    }
}

// The foe the panes are about: the tapped one, else the one about to come
// in, else the one out (CompanionScreen's shownFoe). A party Pokémon only
// when it isn't the one out - that one is the battler, with its live types.
static const struct pd_mon* shown_foe(struct pd_ui* ui, const struct pd_snapshot* s, const char** state) {
    int shown = ui->foeSelected >= 0 ? ui->foeSelected : s->foeNext >= 0 ? s->foeNext : s->foeActive;
    ui->foeShown = shown;
    *state = "";
    if (shown < 0 || shown >= s->foeCount || shown == s->foeActive) return NULL;
    const struct pd_mon* m = &s->foes[shown];
    if (shown == s->foeNext) *state = "NEXT";
    else if (m->hp == 0) *state = "FAINTED";
    else if (ui->foeSeen >> shown & 1) *state = "RESERVE";
    else *state = "UNSEEN";
    return m;
}

// --- SUGGESTIONS ---

struct pick {
    const struct pd_mon* mon;
    int move, pp, pct, score;
};

// Whether party Pokémon m is one of yours on the field (isOut: battlers
// carry no personality to match on).
static bool is_out(const struct pd_mon* m, const struct pd_snapshot* s) {
    for (int p = 0; p < 4; p += 2) {
        const struct pd_battle_mon* b = &s->battlers[p];
        if (b->species && b->species == m->species && b->level == m->level && b->maxHp == m->maxHp) return true;
    }
    return false;
}

static int by_score(const void* a, const void* b) {
    const struct pick *x = a, *y = b;
    if (x->score != y->score) return y->score - x->score;
    return (int) (x->mon - y->mon); // stable: party order on ties
}

// rankSuggestions: every living party Pokémon with a usable damaging move,
// by (its best move's power x multiplier on the foe) minus the worst the
// foe's own types do to it.
static int rank(const struct pd_game* g, const struct pd_snapshot* s, const struct mon_view* foe, struct pick* out) {
    int n = 0;
    for (int i = 0; i < s->partyCount; i++) {
        const struct pd_mon* m = &s->party[i];
        if (m->hp <= 0 || m->species == PD_SPECIES_EGG) continue;
        int best = -1, bestValue = -1;
        for (int k = 0; k < PD_NUM_MOVES; k++) {
            int power = m->moves[k] ? pd_move_power(g, m->moves[k]) : 0;
            if (power <= 0 || m->pp[k] <= 0) continue;
            int value = power * pd_type_multiplier_pct(g, pd_move_type(g, m->moves[k]), foe->type1, foe->type2);
            if (value > bestValue) {
                bestValue = value;
                best = k;
            }
        }
        if (best < 0) continue;
        int t1, t2;
        pd_species_type_pair(g, m->species, &t1, &t2);
        int risk = 100;
        if (foe->type1 != PD_TYPE_NONE) {
            risk = pd_type_multiplier_pct(g, foe->type1, t1, t2);
            if (foe->type2 != PD_TYPE_NONE && foe->type2 != foe->type1) {
                int r2 = pd_type_multiplier_pct(g, foe->type2, t1, t2);
                if (r2 > risk) risk = r2;
            }
        }
        int pct = pd_type_multiplier_pct(g, pd_move_type(g, m->moves[best]), foe->type1, foe->type2);
        out[n++] = (struct pick) { m, m->moves[best], m->pp[best], pct, pd_move_power(g, m->moves[best]) * pct / 100 - risk };
    }
    qsort(out, (size_t) n, sizeof(out[0]), by_score);
    return n;
}

static void suggestions(struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                        const struct mon_view* foe, const char* foeState, int ix, int iy, int iw, int ih) {
    if (!foe) {
        pd_text(c, ix, iy, "NO FOE ON THE FIELD", PD_MUTED, PD_MUTED_SHADOW);
        return;
    }
    card(c, g, ix, iy, iw, foe, "VS", foeState);
    int y = iy + ROW;
    rule(c, ix, y, iw);
    y += RULE;
    struct pick picks[PD_PARTY_SIZE];
    int n = rank(g, s, foe, picks);
    if (!n) {
        pd_text_wrap(c, ix, y + 2, iw, LINE, 2, "NO USABLE DAMAGING MOVES IN YOUR PARTY", PD_MUTED, PD_MUTED_SHADOW);
        return;
    }
    static const char* const RANKS[3] = { "1ST", "2ND", "3RD" };
    int rowH = (iy + ih - y - 2 * RULE) / 3;
    int half = (iw - RULE) / 2;
    for (int i = 0; i < n && i < 3; i++) {
        if (i) {
            rule(c, ix, y, iw);
            y += RULE;
        }
        const struct pick* p = &picks[i];
        int ry = y + (rowH - ROW) / 2;
        // The Pokémon: rank and name, then its level, HP and OUT when it's in battle.
        char buf[40];
        icon(c, g, p->mon->species, p->mon->shiny, ix, ry, false);
        int tx = ix + PD_ICON + 8, right = ix + half;
        int nx = tx + pd_text(c, tx, ry + 1, RANKS[i], PD_VALUE, PD_VALUE_SHADOW) + 5;
        pd_text_fit(c, nx, ry + 1, right - nx, pd_species_name(g, p->mon->species, buf, sizeof(buf)), PD_LABEL,
                    PD_LABEL_SHADOW);
        int ty = ry + LINE + 1;
        char lv[12];
        snprintf(lv, sizeof(lv), "Lv%d", p->mon->level);
        int bx = tx + pd_text(c, tx, ty, lv, PD_LABEL, PD_LABEL_SHADOW) + 5;
        int outW = is_out(p->mon, s) ? pd_text_width("OUT") + 6 : 0;
        if (outW) pill(c, right - outW, ty, "OUT", OUT_GREEN);
        int barW = right - (outW ? outW + 5 : 0) - bx - 18;
        if (barW > 48) barW = 48;
        if (barW >= 16) ui_hp_bar(c, bx, ty + 5, barW, p->mon->hp, p->mon->maxHp);
        // Its best move against the foe.
        pd_fill(c, ix + half + RULE / 2, y + 2, 1, rowH - 4, PD_DIVIDER);
        move_cell(c, g, ix + half + RULE, y + 1, iw - half - RULE, rowH - 2, p->move, p->pp, foe);
        y += rowH;
    }
}

// --- INFO ---

static void info(struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                 const struct mon_view* foe, const char* foeState, const struct mon_view* you, int ix, int iy,
                 int iw, int ih) {
    int y = iy;
    if (foe) card(c, g, ix, y, iw, foe, s->isTrainer ? "FOE" : "WILD", foeState);
    else pd_text(c, ix, y + 1, "NO FOE ON THE FIELD", PD_MUTED, PD_MUTED_SHADOW);
    y += ROW;
    rule(c, ix, y, iw);
    y += RULE;
    card(c, g, ix, y, iw, you, "YOU", "");
    y += ROW;
    rule(c, ix, y, iw);
    y += RULE;
    int gap = 4;
    int bw = (iw - gap) / 2, bh = (iy + ih - y - gap) / 2;
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        move_cell(c, g, ix + (i % 2) * (bw + gap), y + (i / 2) * (bh + gap), bw, bh, you->moves[i], you->pp[i], foe);
    }
}

void ui_battle_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    const struct pd_battle_mon* youB = &s->battlers[PD_POS_PLAYER_LEFT];
    if (!s->inBattle || !youB->species) {
        ui_notice(c, "NO BATTLE", "A battle opens this tab by itself,", "and its end takes you back.");
        return;
    }
    // The foe: the FOE TEAM's shown one, else the battler out.
    const char* foeState;
    const struct pd_mon* party = shown_foe(ui, s, &foeState);
    struct mon_view foeView, you = from_battler(youB);
    const struct mon_view* foe = NULL;
    if (party) {
        foeView = from_party(g, party);
        foe = &foeView;
    } else if (s->battlers[PD_POS_OPPONENT_LEFT].species) {
        foeView = from_battler(&s->battlers[PD_POS_OPPONENT_LEFT]);
        foe = &foeView;
    }

    int x = MARGIN, w = c->w - 2 * MARGIN;
    int barY = c->h - MARGIN - BAR_H;
    int h = barY - 4 - CONTENT_Y;
    int f = pd_title_box(c, x, CONTENT_Y, w, h, PD_TITLE_FILL);
    int ix = x + f + 4, iy = CONTENT_Y + f + 2, iw = w - 2 * (f + 4), ih = h - 2 * (f + 2);
    if (ui->battlePane) suggestions(c, g, s, foe, foeState, ix, iy, iw, ih);
    else info(c, g, s, foe, foeState, &you, ix, iy, iw, ih);

    // The bar: the other pane's button, then the FOE TEAM.
    // One width for both, so the same spot flips back.
    const char* label = ui->battlePane ? "INFO" : "SUGGESTIONS";
    int bw = pd_text_width("SUGGESTIONS") + 20;
    if (ui->battlePane) nav_button(ui, c, x, barY, bw, BAR_H, label, INFO_PURPLE, INFO_PURPLE_DARK, HIT_BATTLE_INFO);
    else nav_button(ui, c, x, barY, bw, BAR_H, label, SUGGEST_GOLD, SUGGEST_GOLD_DARK, HIT_BATTLE_SUGGEST);
    foe_strip(ui, c, g, s, x + bw + 8, barY, x + w);
}

bool ui_battle_act(struct pd_ui* ui, int id) {
    if (id == HIT_BATTLE_SUGGEST || id == HIT_BATTLE_INFO) {
        ui->battlePane = id == HIT_BATTLE_SUGGEST;
        return true;
    }
    if (id >= HIT_FOE_SLOT && id < HIT_FOE_SLOT + PD_PARTY_SIZE) {
        int i = id - HIT_FOE_SLOT;
        // Tapping the one shown goes back to following the battle.
        ui->foeSelected = i == ui->foeShown && ui->foeSelected >= 0 ? -1 : i;
        return true;
    }
    return false;
}
