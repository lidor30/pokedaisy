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
#include <string.h>

#include "pd_dex.h"
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

struct battle_data {
    const struct pd_game* game;
    const struct pd_battle_source* source;
};

static const char* species_name(const struct battle_data* d, int id, char* buf, size_t len) {
    return d->source ? d->source->species(d->source->ctx, id) : pd_species_name(d->game, id, buf, len);
}

static const char* move_name(const struct battle_data* d, int id, char* buf, size_t len) {
    return d->source ? d->source->move(d->source->ctx, id) : pd_move_name(d->game, id, buf, len);
}

static int move_type(const struct battle_data* d, int id) {
    return d->source ? d->source->moveType(d->source->ctx, id) : pd_move_type(d->game, id);
}

static int move_power(const struct battle_data* d, int id) {
    return d->source ? d->source->movePower(d->source->ctx, id) : pd_move_power(d->game, id);
}

static void species_types(const struct battle_data* d, int id, int* t1, int* t2) {
    if (d->source) d->source->speciesTypes(d->source->ctx, id, t1, t2);
    else pd_species_type_pair(d->game, id, t1, t2);
}

static bool draw_icon(const struct battle_data* d, struct pd_canvas* c, int species, bool shiny, int x, int y, bool faded) {
    return d->source ? d->source->icon(d->source->ctx, c, species, x, y, faded) :
        pd_draw_mon_icon_shiny(c, d->game, species, shiny, x, y, 1, faded);
}

static struct mon_view from_battler(const struct pd_battle_mon* b) {
    return (struct mon_view) { b->species, b->level, b->hp, b->maxHp, b->type1, b->type2, b->status, b->moves, b->pp, b->shiny };
}

static struct mon_view from_party(const struct battle_data* d, const struct pd_mon* m) {
    struct mon_view v = { m->species, m->level, m->hp, m->maxHp, PD_TYPE_NONE, PD_TYPE_NONE, m->status, m->moves, m->pp, m->shiny };
    species_types(d, m->species, &v.type1, &v.type2);
    return v;
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
static void icon(struct pd_canvas* c, const struct battle_data* d, int species, bool shiny, int x, int y, bool faded) {
    if (shiny) {
        pd_round_rect(c, x, y, PD_ICON + 2, PD_ICON + 2, 3, SHINY_FRAME);
        pd_round_rect(c, x + 1, y + 1, PD_ICON, PD_ICON, 2, SHINY_FILL);
    } else {
        pd_round_rect(c, x, y, PD_ICON + 2, PD_ICON + 2, 3, PD_LIST_FILL);
    }
    if (!draw_icon(d, c, species, shiny, x + 1, y + 1, faded)) ui_ball(c, x + 12, y + 12, true);
}

// A card across w: icon, then [side] name ... [state] Lv on the first line,
// types, HP bar and HP on the second.
static void card(struct pd_canvas* c, const struct battle_data* d, int x, int y, int w, const struct mon_view* m,
                 const char* side, const char* state) {
    char buf[40];
    icon(c, d, m->species, m->shiny, x, y, false);
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
                                   species_name(d, m->species, buf, sizeof(buf)), PD_LABEL, PD_LABEL_SHADOW);
    if (m->shiny) nameEnd += pd_text(c, nameEnd + 2, ty, "*", SHINY_FRAME, PD_LABEL_SHADOW) + 2;  // the app's ShinySparkle
    if (*status) {
        ui_status_badge(c, nameEnd + 4, ty + 1, m->status);
        nameEnd += statusW;
    }
    if (stateW) pill(c, nameEnd + 4, ty, state, VERDICT_GREY);

    ty += LINE + 1;
    // The types as the game's coloured tags.
    int typesW = 0;
    if (m->type1 == PD_TYPE_NONE) typesW = pd_text(c, tx, ty, "-", PD_MUTED, PD_MUTED_SHADOW);
    else {
        typesW = ui_type_badge(c, tx, ty + 3, m->type1);
        if (m->type2 != PD_TYPE_NONE && m->type2 != m->type1) typesW += 2 + ui_type_badge(c, tx + typesW + 2, ty + 3, m->type2);
    }
    char hp[16];
    snprintf(hp, sizeof(hp), "%d/%d", m->hp, m->maxHp);
    int hpx = right - pd_text_width(hp);
    pd_text(c, hpx, ty, hp, PD_LABEL, PD_LABEL_SHADOW);
    int barX = tx + (typesW > 92 ? typesW : 92) + 6;
    int barW = hpx - 6 - barX - 18;
    if (barW >= 24) ui_hp_bar(c, barX, ty + 5, barW, m->hp, m->maxHp);
}

static void rule(struct pd_canvas* c, int x, int y, int w) {
    pd_fill(c, x, y + RULE / 2, w, 1, PD_DIVIDER);
}

static void separator(struct pd_canvas* c, int x, int y, int len, bool vertical) {
    pd_separator(c, x, y, len, vertical);
}

// verdictColor: darker than the bars' colours, so white text reads on them.
static uint32_t verdict_color(int pct) {
    return pct == 0 ? 0x585048 : pct > 100 ? 0x389830 : pct < 100 ? 0xD04030 : VERDICT_GREY;
}

static const char* multiplier(int pct) {
    return pct == 0 ? "0x" : pct <= 25 ? "1/4x" : pct < 100 ? "1/2x" : pct >= 400 ? "4x" : pct >= 200 ? "2x" : "1x";
}

// A move's plain-words verdict on the foe (MoveVerdict) as a pill, or just its multiplier where [room] is short; returns the
// pill's width (0: nothing to say).
static int move_verdict(struct pd_canvas* c, const struct battle_data* d, int x, int y, int room, int move, int pp,
                        const struct mon_view* foe, bool draw) {
    char text[24];
    uint32_t fill = VERDICT_GREY;
    if (pp == 0) snprintf(text, sizeof(text), "NO PP");
    else if (move_power(d, move) == 0) snprintf(text, sizeof(text), "STATUS");
    else if (!foe) return 0;
    else {
        int pct = pd_type_multiplier_pct(d->game, move_type(d, move), foe->type1, foe->type2);
        fill = verdict_color(pct);
        const char* word = pct == 0 ? "IMMUNE" : pct > 100 ? "SUPER" : pct < 100 ? "RESISTED" : "NEUTRAL";
        snprintf(text, sizeof(text), "%s %s", word, multiplier(pct));
        if (pd_text_width(text) + 6 > room) snprintf(text, sizeof(text), "%s", multiplier(pct));
    }
    int w = pd_text_width(text) + 6;
    if (draw) {
        pd_round_rect(c, x, y, w, 13, 2, fill);
        pd_text(c, x + 3, y - 2, text, 0xFFFFFF, PD_NO_SHADOW);
    }
    return w;
}

// SummaryBar: "HP" in the value colour, the bar in the window's dark frame, then hp / max.
static void hp_line(struct pd_canvas* c, int x, int y, int w, int hp, int maxHp) {
    int lx = x + pd_text(c, x, y - 3, "HP", PD_VALUE, PD_VALUE_SHADOW) + 3;
    char t[16];
    snprintf(t, sizeof(t), "%d/%d", hp, maxHp);
    int tw = pd_text_width(t);
    pd_text(c, x + w - tw, y - 3, t, PD_LABEL, PD_LABEL_SHADOW);
    int bw = x + w - tw - 3 - lx;
    if (bw < 8) return;
    pd_round_rect(c, lx, y + 2, bw, 6, 2, pd_cur->frameDark);
    pd_round_rect(c, lx + 1, y + 3, bw - 2, 4, 1, 0x506858);
    int inner = bw - 2, f = maxHp > 0 ? hp * inner / maxHp : 0;
    if (f == 0 && hp > 0) f = 1;
    if (f > inner) f = inner;
    uint32_t col = maxHp > 0 && hp * 5 <= maxHp ? 0xF85838 : maxHp > 0 && hp * 2 <= maxHp ? 0xF8C030 : 0x58D080;
    if (f > 0) pd_round_rect(c, lx + 1, y + 3, f, 4, 1, col);
}

#define CARD_H 46

// BattlerCard: [side] over the icon; the name (status, a state pill) / its types and level / HP, in a half of INFO.
// Whether the save's POKéDEX has [species] caught (the foe's card marks it with a Poke Ball, discussion #305).
static bool caught_species(const struct battle_data* d, const struct pd_snapshot* s, int species) {
    if (!s || !s->dexOk || !d->game || !species) return false;
    int national = pd_dex_national(d->game, species);
    return national > 0 && pd_dex_caught(s, national);
}

static void side_card(struct pd_canvas* c, const struct battle_data* d, int x, int y, int w, const struct mon_view* m,
                      const char* side, const char* state, bool caught) {
    char buf[40];
    pd_text(c, x, y - 2, side, PD_VALUE, PD_VALUE_SHADOW);
    icon(c, d, m->species, m->shiny, x, y + 12, false);
    int tx = x + PD_ICON + 2 + 5, right = x + w;
    int stateW = state && *state ? pd_text_width(state) + 6 : 0;
    if (stateW) pill(c, right - stateW, y - 1, state, VERDICT_GREY);
    const char* status = pd_status_label(m->status);
    int statusW = *status ? pd_text_width(status) + 6 + 3 : 0, ballW = caught ? 13 : 0;
    int nameEnd = tx + pd_text_fit(c, tx, y - 2, right - tx - stateW - statusW - ballW - (stateW ? 3 : 0),
                                   species_name(d, m->species, buf, sizeof(buf)), PD_LABEL, PD_LABEL_SHADOW);
    if (m->shiny) nameEnd += pd_text(c, nameEnd + 2, y - 2, "*", SHINY_FRAME, PD_LABEL_SHADOW) + 2;
    if (caught) {
        ui_ball(c, nameEnd + 3, y + 2, true);
        nameEnd += ballW;
    }
    if (*status) ui_status_badge(c, nameEnd + 3, y + 1, m->status);
    // Types as the game's coloured tags, then Lv.
    int bx = tx, by = y + 17;
    pd_clip(c, tx, by - 2, right - tx, 16);
    if (m->type1 != PD_TYPE_NONE) bx += ui_type_badge(c, bx, by, m->type1) + 2;
    if (m->type2 != PD_TYPE_NONE && m->type2 != m->type1) bx += ui_type_badge(c, bx, by, m->type2) + 2;
    pd_unclip(c);
    char lv[12];
    snprintf(lv, sizeof(lv), "%d", m->level);
    int lvx = right - pd_text_width(lv);
    if (lvx - pd_text_width("Lv") - 2 > bx) {
        pd_text(c, lvx, by - 2, lv, PD_VALUE, PD_VALUE_SHADOW);
        pd_text(c, lvx - pd_text_width("Lv") - 2, by - 2, "Lv", PD_LABEL, PD_LABEL_SHADOW);
    }
    hp_line(c, tx, y + 33, right - tx, m->hp, m->maxHp);
}

// BattleMoveRow: a stripe in the type's colour, the name with its verdict; under them, where the row has room, the type, PWR
// and PP. Out of PP it's faded.
static void move_row(struct pd_canvas* c, const struct battle_data* d, int x, int y, int w, int h, int move, int pp,
                     const struct mon_view* foe, bool hints) {
    char buf[40];
    if (!move) {
        pd_text(c, x + 8, y + (h - LINE) / 2, "-", PD_MUTED, PD_MUTED_SHADOW);
        return;
    }
    int type = move_type(d, move);
    pd_round_rect(c, x, y + 2, 3, h - 4, 1, ui_type_color(type));
    int tx = x + 8, iw = w - 8;
    bool two = h >= 30;
    int ty = two ? y + (h - 30) / 2 : y + (h - LINE) / 2;
    const char* name = move_name(d, move, buf, sizeof(buf));
    // The verdict in words where the name leaves room for them, else just its multiplier.
    int room = iw - pd_text_width(name) - 3;
    int vw = hints ? move_verdict(c, d, 0, 0, room, move, pp, foe, false) : 0;
    uint32_t ink = pp == 0 ? PD_MUTED : PD_LABEL, inkShadow = pp == 0 ? PD_MUTED_SHADOW : PD_LABEL_SHADOW;
    pd_text_fit(c, tx, ty, iw - vw - (vw ? 3 : 0), name, ink, inkShadow);
    if (vw) move_verdict(c, d, tx + iw - vw, ty + 3, room, move, pp, foe, true);
    if (!two) return;
    int ly = ty + 17;
    int bx = tx + ui_type_badge(c, tx, ly, type) + 4;
    int power = move_power(d, move);
    char line[24];
    if (power > 0) snprintf(line, sizeof(line), "PWR %d  PP %d", power, pp);
    else snprintf(line, sizeof(line), "PWR -  PP %d", pp);
    if (pd_text_width(line) > tx + iw - bx) snprintf(line, sizeof(line), "PP %d", pp);
    pd_text_right(c, tx + iw, ly - 2, line, PD_MUTED, PD_MUTED_SHADOW);
}

// The foe's defensive chart (FoeDetailsColumn's matchups): WEAK TO strongest first, RESISTS, IMMUNE TO, each a heading over
// its type chips.
static void matchups(struct pd_canvas* c, const struct battle_data* d, const struct mon_view* foe, int x, int y, int w, int bottom) {
    char buf[40], head[48];
    snprintf(head, sizeof(head), "%s IS", species_name(d, foe->species, buf, sizeof(buf)));
    pd_text_fit(c, x, y - 2, w, head, PD_VALUE, PD_VALUE_SHADOW);
    y += 15;
    if (foe->type1 == PD_TYPE_NONE) {
        pd_text(c, x, y - 2, "NO MATCHUP DATA", PD_MUTED, PD_MUTED_SHADOW);
        return;
    }
    static const char* const HEADS[3] = { "WEAK TO", "RESISTS", "IMMUNE TO" };
    int n = pd_type_count(d->game);
    pd_clip(c, x, y - 2, w, bottom - y + 2);
    for (int g = 0; g < 3; g++) {
        int list[32], pcts[32], k = 0;
        for (int t = 0; t < n && k < 32; t++) {
            const char* name = pd_type_name(d->game, t);
            if (!name || !*name || name[0] == '?' || !strcmp(name, "MYSTERY") || !strcmp(name, "Mystery")) continue;
            int pct = pd_type_multiplier_pct(d->game, t, foe->type1, foe->type2);
            if ((g == 0 && pct > 100) || (g == 1 && pct > 0 && pct < 100) || (g == 2 && pct == 0)) {
                list[k] = t;
                pcts[k++] = pct;
            }
        }
        if (!k) continue;
        // Strongest first where it hits, weakest first where it's resisted.
        for (int i = 1; i < k; i++)
            for (int j = i; j > 0 && (g == 0 ? pcts[j] > pcts[j - 1] : pcts[j] < pcts[j - 1]); j--) {
                int tt = list[j]; list[j] = list[j - 1]; list[j - 1] = tt;
                tt = pcts[j]; pcts[j] = pcts[j - 1]; pcts[j - 1] = tt;
            }
        if (y > bottom - 12) break;
        pd_text(c, x, y - 2, HEADS[g], PD_LABEL, PD_LABEL_SHADOW);
        y += 14;
        int cx = x;
        for (int i = 0; i < k; i++) {
            char chip[24];
            const char* name = pd_type_name(d->game, list[i]);
            int nl = 0;
            for (; name[nl] && nl < 12; nl++) chip[nl] = name[nl] >= 'a' && name[nl] <= 'z' ? (char) (name[nl] - 32) : name[nl];
            chip[nl] = 0;
            if (g < 2) snprintf(chip + nl, sizeof(chip) - (size_t) nl, " %s", multiplier(pcts[i]));
            int cw = pd_text_width(chip) + 6;
            if (cx + cw > x + w && cx > x) {
                cx = x;
                y += 14;
            }
            pd_round_rect(c, cx, y, cw, 12, 2, ui_type_color(list[i]));
            pd_text(c, cx + 3, y - 2, chip, 0xFFFFFF, PD_NO_SHADOW);
            cx += cw + 3;
        }
        y += 16;
    }
    pd_unclip(c);
}


// TextNavButton / the summary's Back: a PlatinumButton (HeartGold's two-tone) with a white label, or the back arrow.
static void nav_button(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, const char* label,
                       uint32_t fill, uint32_t dark, int id) {
    ui_platinum(ui, c, x, y, w, h, fill, dark, id);
    if (label) ui_platinum_label(c, x, y, w, h, label);
    else ui_platinum_back(c, x, y, w, h);
}

// The FOE TEAM, from x to the right edge.
static void foe_strip(struct pd_ui* ui, struct pd_canvas* c, const struct battle_data* d, const struct pd_snapshot* s,
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
        } else if (!draw_icon(d, c, m->species, m->shiny, sx + (cell - PD_ICON) / 2, y + (cell - PD_ICON) / 2,
                                           m->hp == 0)) {
            char lv[8];
            snprintf(lv, sizeof(lv), "%d", m->level);
            pd_text(c, sx + (cell - pd_text_width(lv)) / 2, pd_text_y(y, cell), lv,
                    m->hp ? PD_LABEL : PD_MUTED, PD_NO_SHADOW);
        }
        if (!d->source || shown) ui_add_hit(ui, sx, y, cell, cell, HIT_FOE_SLOT + i);
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
static int rank(const struct battle_data* d, const struct pd_snapshot* s, const struct mon_view* foe, struct pick* out) {
    int n = 0;
    for (int i = 0; i < s->partyCount; i++) {
        const struct pd_mon* m = &s->party[i];
        if (m->hp <= 0 || m->egg) continue;
        int best = -1, bestValue = -1;
        for (int k = 0; k < PD_NUM_MOVES; k++) {
            int power = m->moves[k] ? move_power(d, m->moves[k]) : 0;
            if (power <= 0 || m->pp[k] <= 0) continue;
            int value = power * pd_type_multiplier_pct(d->game, move_type(d, m->moves[k]), foe->type1, foe->type2);
            if (value > bestValue) {
                bestValue = value;
                best = k;
            }
        }
        if (best < 0) continue;
        int t1, t2;
        species_types(d, m->species, &t1, &t2);
        int risk = 100;
        if (foe->type1 != PD_TYPE_NONE) {
            risk = pd_type_multiplier_pct(d->game, foe->type1, t1, t2);
            if (foe->type2 != PD_TYPE_NONE && foe->type2 != foe->type1) {
                int r2 = pd_type_multiplier_pct(d->game, foe->type2, t1, t2);
                if (r2 > risk) risk = r2;
            }
        }
        int pct = pd_type_multiplier_pct(d->game, move_type(d, m->moves[best]), foe->type1, foe->type2);
        out[n++] = (struct pick) { m, m->moves[best], m->pp[best], pct, move_power(d, m->moves[best]) * pct / 100 - risk };
    }
    qsort(out, (size_t) n, sizeof(out[0]), by_score);
    return n;
}

static void suggestions(struct pd_canvas* c, const struct battle_data* d, const struct pd_snapshot* s,
                        const struct mon_view* foe, const char* foeState, int ix, int iy, int iw, int ih) {
    if (!foe) {
        pd_text(c, ix, iy, "NO FOE ON THE FIELD", PD_MUTED, PD_MUTED_SHADOW);
        return;
    }
    card(c, d, ix, iy, iw, foe, "VS", foeState);
    int y = iy + ROW;
    rule(c, ix, y, iw);
    y += RULE;
    struct pick picks[PD_PARTY_SIZE];
    int n = rank(d, s, foe, picks);
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
        icon(c, d, p->mon->species, p->mon->shiny, ix, ry, false);
        int tx = ix + PD_ICON + 8, right = ix + half;
        int nx = tx + pd_text(c, tx, ry + 1, RANKS[i], PD_VALUE, PD_VALUE_SHADOW) + 5;
        pd_text_fit(c, nx, ry + 1, right - nx, species_name(d, p->mon->species, buf, sizeof(buf)), PD_LABEL,
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
        separator(c, ix + half + RULE / 2, y + 2, rowH - 4, true);
        move_row(c, d, ix + half + RULE, y + 1, iw - half - RULE, rowH - 2, p->move, p->pp, foe, true);
        y += rowH;
    }
}

// --- FOE DETAILS ---

// threatColor: a foe's move from the player's side - super effective on you is red, resisted green.
static uint32_t threat_color(int pct) {
    return pct == 0 || pct < 100 ? verdict_color(200) : pct > 100 ? verdict_color(50) : VERDICT_GREY;
}

// A foe's move (BattleMoveRow(threat, shortVerdict)): the stripe, the name, what it does to you as a multiplier pill; then
// its type and PP where the row has room.
static void threat_row(struct pd_canvas* c, const struct battle_data* d, int x, int y, int w, int h, int move, int pp,
                       const struct mon_view* you) {
    char buf[40];
    if (!move) {
        pd_text(c, x + 8, y + (h - LINE) / 2, "-", PD_MUTED, PD_MUTED_SHADOW);
        return;
    }
    int type = move_type(d, move);
    pd_round_rect(c, x, y + 2, 3, h - 4, 1, ui_type_color(type));
    int tx = x + 8, iw = w - 8;
    int ty = h >= 30 ? y + (h - 30) / 2 : y + (h - LINE) / 2;
    char pillText[8] = "";
    uint32_t fill = VERDICT_GREY;
    if (move_power(d, move) > 0 && you) {
        int pct = pd_type_multiplier_pct(d->game, type, you->type1, you->type2);
        snprintf(pillText, sizeof(pillText), "%s", multiplier(pct));
        fill = threat_color(pct);
    }
    int pw = *pillText ? pd_text_width(pillText) + 6 : 0;
    pd_text_fit(c, tx, ty, iw - pw - (pw ? 3 : 0), move_name(d, move, buf, sizeof(buf)), PD_LABEL, PD_LABEL_SHADOW);
    if (pw) {
        pd_round_rect(c, tx + iw - pw, ty + 3, pw, 13, 2, fill);
        pd_text(c, tx + iw - pw + 3, ty + 1, pillText, 0xFFFFFF, PD_NO_SHADOW);
    }
    if (h < 30) return;
    int ly = ty + 17;
    ui_type_badge(c, tx, ly, type);
    char ppText[12];
    snprintf(ppText, sizeof(ppText), "PP %d", pp);
    pd_text_right(c, tx + iw, ly - 2, ppText, PD_MUTED, PD_MUTED_SHADOW);
}

// A stat stage's pill (StagePill): a raise on the foe is bad news (red), a drop good (green).
static int stage_pill(struct pd_canvas* c, int x, int y, int stage) {
    char t[6];
    snprintf(t, sizeof(t), stage > 0 ? "+%d" : "%d", stage);
    int w = pd_text_width(t) + 6;
    pd_round_rect(c, x, y, w, 13, 2, stage > 0 ? verdict_color(50) : verdict_color(200));
    pd_text(c, x + 3, y - 2, t, 0xFFFFFF, PD_NO_SHADOW);
    return w;
}

// FoeFacts (wide): ABILITY and HELD ITEM on a line, then the five stats as the battle has them, a stage pill on any raised or
// lowered. Returns the height drawn (0: nothing known).
static int foe_facts(struct pd_canvas* c, const struct battle_data* d, const struct pd_battle_mon* b, int x, int y, int w) {
    if (!b || !b->hasFacts) return 0;
    char name[32];
    int h = 0, half = w / 2;
    pd_dex_ability_name(d->game, b->ability, name, sizeof(name));
    if (*name) {
        int lw = pd_text(c, x, y - 2, "ABILITY", PD_LABEL, PD_LABEL_SHADOW) + 4;
        pd_text_fit(c, x + lw, y - 2, half - lw - 4, name, PD_VALUE, PD_VALUE_SHADOW);
    }
    const char* item = b->heldItem ? pd_item_name(d->game, b->heldItem) : "NONE";
    int lw = pd_text(c, x + half, y - 2, "ITEM", PD_LABEL, PD_LABEL_SHADOW) + 4;
    pd_text_fit(c, x + half + lw, y - 2, w - half - lw, item && *item ? item : "-", PD_VALUE, PD_VALUE_SHADOW);
    h += 15;
    // The summary's order: Attack, Defense, Sp. Atk, Sp. Def, Speed (the struct's: Atk, Def, Speed, Sp. Atk, Sp. Def).
    static const int ORDER[5] = { 0, 1, 3, 4, 2 };
    static const char* const LABELS[5] = { "ATK", "DEF", "SP.ATK", "SP.DEF", "SPEED" };
    int col = w / 5;
    for (int k = 0; k < 5; k++) {
        int i = ORDER[k], cx = x + k * col;
        pd_text(c, cx, y + h - 2, LABELS[k], PD_LABEL, PD_LABEL_SHADOW);
        char v[8];
        snprintf(v, sizeof(v), "%d", b->stats[i]);
        int vw = pd_text(c, cx, y + h + 12, v, PD_VALUE, PD_VALUE_SHADOW);
        int stage = b->stages[i + 1];
        if (stage) stage_pill(c, cx + vw + 2, y + h + 14, stage);
    }
    return h + 29;
}

// FOE DETAILS (the expand button): your side shrinks to a strip - YOU, the icon, the HP standing up - and the foe gets the
// page: its card, its facts, its moves with what each does to you, then its matchups where there's room.
static void foe_details(struct pd_canvas* c, const struct battle_data* d, const struct pd_snapshot* s, const struct mon_view* foe,
                        const char* foeState, const struct pd_battle_mon* foeBattler, const struct mon_view* you, int ix,
                        int iy, int iw, int ih, bool hints) {
    const int gap = 3, strip = PD_ICON + 2 + 6;
    // The strip: YOU over the icon, then an upright HP bar emptying from the top, the numbers under it.
    pd_text(c, ix, iy, "YOU", PD_VALUE, PD_VALUE_SHADOW);
    icon(c, d, you->species, you->shiny, ix, iy + 14, false);
    int barX = ix + (PD_ICON + 2 - 8) / 2, barY = iy + 14 + PD_ICON + 2 + 6, barH = iy + ih - 32 - barY;
    if (barH > 12) {
        pd_round_rect(c, barX, barY, 8, barH, 2, pd_cur->frameDark);
        pd_round_rect(c, barX + 1, barY + 1, 6, barH - 2, 1, 0x506858);
        int inner = barH - 2, f = you->maxHp > 0 ? you->hp * inner / you->maxHp : 0;
        if (f == 0 && you->hp > 0) f = 1;
        if (f > inner) f = inner;
        uint32_t col = you->maxHp > 0 && you->hp * 5 <= you->maxHp ? 0xF85838 : you->maxHp > 0 && you->hp * 2 <= you->maxHp ? 0xF8C030 : 0x58D080;
        if (f > 0) pd_round_rect(c, barX + 1, barY + 1 + inner - f, 6, f, 1, col);
    }
    char t[12];
    snprintf(t, sizeof(t), "%d", you->hp);
    pd_text(c, ix + (PD_ICON + 2 - pd_text_width(t)) / 2, iy + ih - 30, t, PD_LABEL, PD_LABEL_SHADOW);
    snprintf(t, sizeof(t), "/%d", you->maxHp);
    pd_text(c, ix + (PD_ICON + 2 - pd_text_width(t)) / 2, iy + ih - 16, t, PD_MUTED, PD_MUTED_SHADOW);
    separator(c, ix + strip, iy, ih, true);
    int rx = ix + strip + 2 + gap, rw = iw - strip - 2 - gap, y = iy + 2;
    if (!foe) {
        pd_text(c, rx, y - 2, "NO FOE", PD_MUTED, PD_MUTED_SHADOW);
        return;
    }
    side_card(c, d, rx, y, rw, foe, s->isTrainer ? "FOE" : "WILD", foeState, caught_species(d, s, foe->species));
    y = iy + CARD_H + 4;
    separator(c, rx, y, rw, false);
    y += 5;
    y += foe_facts(c, d, foeBattler, rx, y, rw);
    int bottom = iy + ih;
    // The foe's moves two by two, a row each pair.
    // The floating expand button sits in the bottom right corner: a row beside it keeps clear of it (FoeDetailsColumn's
    // bottomRoom).
    int rowH = 24, colW = (rw - 6) / 2, buttonTop = bottom - (PD_ICON_SHRINK.h + 10) - 2;
    for (int i = 0; i < PD_NUM_MOVES && y + rowH <= bottom; i += 2) {
        for (int j = 0; j < 2; j++) {
            int cw = j == 1 && y + rowH > buttonTop ? colW - (PD_ICON_SHRINK.w + 10) - 4 : colW;
            threat_row(c, d, rx + j * (colW + 6), y, cw, rowH, foe->moves[i + j], foe->pp[i + j], you);
        }
        y += rowH;
    }
    if (hints && y + 30 <= bottom) {
        separator(c, rx, y + 1, rw, false);
        matchups(c, d, foe, rx, y + 5, rw, bottom);
    }
}

// --- INFO ---

// BattleInfoScreen, split 50 / 50 YOU / FOE (the order a player asks in): the battlers' cards on top, your moves with their
// verdicts bottom left, the foe's WEAK TO / RESISTS bottom right.
static void info(struct pd_canvas* c, const struct battle_data* d, const struct pd_snapshot* s,
                 const struct mon_view* foe, const char* foeState, const struct mon_view* you, int ix, int iy,
                 int iw, int ih, bool hints) {
    const int gap = 3;
    int half = (iw - 2 - 2 * gap) / 2, rx = ix + half + gap + 2 + gap;
    int y = iy + 2;
    side_card(c, d, ix, y, half, you, "YOU", "", false);
    separator(c, ix + half + gap, iy, CARD_H + 2, true);
    if (foe) side_card(c, d, rx, y, half, foe, s->isTrainer ? "FOE" : "WILD", foeState, caught_species(d, s, foe->species));
    else pd_text(c, rx, y - 2, "NO FOE", PD_MUTED, PD_MUTED_SHADOW);
    y = iy + CARD_H + 4;
    separator(c, ix, y, iw, false);
    y += 4;
    int bottom = iy + ih;
    separator(c, ix + half + gap, y, bottom - y, true);
    int rowH = (bottom - y) / PD_NUM_MOVES;
    for (int i = 0; i < PD_NUM_MOVES; i++)
        move_row(c, d, ix, y + i * rowH, half, rowH, you->moves[i], you->pp[i], foe, hints);
    if (hints && foe) matchups(c, d, foe, rx, y + 2, half, bottom);
}

void ui_battle_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    const struct battle_data data = {g, ui->battleSource};
    const struct pd_battle_mon* youB = &s->battlers[PD_POS_PLAYER_LEFT];
    if (!s->inBattle || !youB->species) {
        ui_notice(c, "NO BATTLE", "A battle opens this tab by itself,", "and its end takes you back.");
        return;
    }
    bool controls = ui_battle_controls_usable(ui, s);
    if (!ui_battle_info_on(ui)) {
        // BATTLE INFO off: the buttons, waiting between turns too, or - where there are none (a double battle) - a word
        // to use the game's own menu.
        if (ui_battle_input_on(ui, s)) ui_battle_controls(ui, c, g, s);
        else ui_notice(c, "BATTLE", "Use the game's own menu.", "");
        return;
    }
    // RUN picked: the controls stay up until the battle ends (no INFO flashing by on the way out).
    bool hold = ui->ranAway && ui_battle_input_on(ui, s);
    if (!ui->battlePane && ((controls && !ui->battleInfoOpen) || hold)) {
        ui_battle_controls(ui, c, g, s);
        return;
    }
    // The foe: the FOE TEAM's shown one, else the battler out.
    const char* foeState;
    const struct pd_mon* party = shown_foe(ui, s, &foeState);
    struct mon_view foeView, you = from_battler(youB);
    const struct mon_view* foe = NULL;
    if (party) {
        foeView = from_party(&data, party);
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
    bool hints = !ui->settings || !ui->settings->hideHints;
    if (ui->battlePane) suggestions(c, &data, s, foe, foeState, ix, iy, iw, ih);
    else if (ui->foeDetails) foe_details(c, &data, s, foe, foeState, party ? NULL : &s->battlers[PD_POS_OPPONENT_LEFT], &you, ix, iy, iw, ih, hints);
    else info(c, &data, s, foe, foeState, &you, ix, iy, iw, ih, hints);
    if (!ui->battlePane) {
        // FOE DETAILS' button, floating at the window's bottom right: the expand / shrink corners.
        const struct pd_mono* glyph = ui->foeDetails ? &PD_ICON_SHRINK : &PD_ICON_EXPAND;
        int bs = glyph->w + 10, bx = x + w - f - bs - 2, by = CONTENT_Y + h - f - bs - 2;
        ui_button(ui, c, bx, by, bs, bs, "", HIT_FOE_DETAILS);
        ui_mono(c, glyph, bx + (bs - glyph->w) / 2, by + (bs - glyph->h) / 2, 1, PD_TITLE_TEXT);
    }

    // The bar: the other pane's button, then the FOE TEAM.
    // One width for both, so the same spot flips back.
    const char* label = ui->battlePane ? "INFO" : "SUGGESTIONS";
    int bw = pd_text_width("SUGGESTIONS") + 20;
    if (ui->battlePane) nav_button(ui, c, x, barY, bw, BAR_H, label, INFO_PURPLE, INFO_PURPLE_DARK, HIT_BATTLE_INFO);
    else nav_button(ui, c, x, barY, bw, BAR_H, label, SUGGEST_GOLD, SUGGEST_GOLD_DARK, HIT_BATTLE_SUGGEST);
    int right = x + w;
    // Back to the controls, while there are any to go back to.
    if (controls) {
        right -= BAR_H + 2;
        nav_button(ui, c, right + 2, barY, BAR_H, BAR_H, NULL, 0x2892CB, 0x415971, HIT_BATTLE_CONTROLS);
    }
    foe_strip(ui, c, &data, s, x + bw + 8, barY, right);
}

// The pick of ui_battle_tab's SUGGESTIONS, for the POKéMON pane: recommendedSwitch.
int ui_recommended_switch(const struct pd_snapshot* s) {
    const struct pd_battle_mon* b = &s->battlers[PD_POS_OPPONENT_LEFT];
    if (!b->species) return -1;
    struct mon_view foe = from_battler(b);
    struct pick picks[PD_PARTY_SIZE];
    const struct battle_data data = {ui_game, NULL};
    int n = rank(&data, s, &foe, picks);
    for (int i = 0; i < n; i++)
        if (!is_out(picks[i].mon, s)) return (int) (picks[i].mon - s->party);
    return -1;
}

int ui_out_slots(const struct pd_snapshot* s) {
    int bits = 0;
    for (int i = 0; i < s->partyCount; i++)
        if (is_out(&s->party[i], s)) bits |= 1 << i;
    return bits;
}

bool ui_battle_act(struct pd_ui* ui, int id) {
    if (id == HIT_BATTLE_SUGGEST || id == HIT_BATTLE_INFO) {
        ui->battlePane = id == HIT_BATTLE_SUGGEST;
        ui->battleInfoOpen = true;
        return true;
    }
    if (id == HIT_FOE_DETAILS) {
        ui->foeDetails = !ui->foeDetails;
        return true;
    }
    if (id == HIT_BATTLE_CONTROLS) {
        ui->battleInfoOpen = false;
        ui->battlePane = 0;
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
