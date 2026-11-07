// BATTLE's touch controls (BattleControlsScreen.kt): the party-health strips, INFO / SUGGESTIONS, and over them the
// game's own menu as big buttons - FIGHT, then BAG / RUN / POKéMON, or the move grid, or the party to send in. A tap
// presses the game's own buttons (pd_binput.c); only while it's the player's turn.
#include <stdio.h>
#include <string.h>

#include "pd_binput.h"
#include "pd_gb.h"
#include "pd_icon.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

#define BUTTON_OUTLINE 0x202020
#define FIGHT_RED 0xE8483C
#define FIGHT_RED_DARK 0xA82820
#define BAG_ORANGE 0xE0A030
#define BAG_ORANGE_DARK 0xA87018
#define RUN_BLUE 0x4890D8
#define RUN_BLUE_DARK 0x2860A0
#define MON_GREEN 0x58A840
#define MON_GREEN_DARK 0x307020
#define MOVE_CARD_FILL 0xE8E8B0
#define MOVE_TEXT 0x404850
#define MOVE_TEXT_SHADOW 0xC0C088
#define SLOT_FILL_DARK 0x308878
#define LABEL_SHADOW 0x383838
#define DISABLED_ALPHA 115 // 0.45 of 255

#define STRIP_H 20
#define GAP 4

// The Game Boy games' windows have no shadows: their buttons are plain text boxes, ink on paper (Gen1ActionButtons).
#define GB_LOOK (pd_cur->titleShadow == PD_NO_SHADOW)

static uint32_t mix(uint32_t a, uint32_t b, int pct) {
    uint32_t out = 0;
    for (int sh = 0; sh <= 16; sh += 8) {
        int x = (int) ((a >> sh) & 0xFF), y = (int) ((b >> sh) & 0xFF);
        out |= (uint32_t) (x + (y - x) * pct / 100) << sh;
    }
    return out;
}

void ui_fade_rect(struct pd_canvas* c, const uint32_t* before, int x, int y, int w, int h, int alpha) {
    for (int yy = 0; yy < h; yy++) {
        int sy = y + yy;
        if (sy < c->cy0 || sy >= c->cy1) continue;
        for (int xx = 0; xx < w; xx++) {
            int sx = x + xx;
            if (sx < c->cx0 || sx >= c->cx1) continue;
            uint32_t* d = &c->px[sy * c->w + sx];
            uint32_t out = 0, was = before[yy * w + xx];
            for (int sh = 0; sh <= 16; sh += 8) {
                uint32_t a = (*d >> sh) & 0xFF, b = (was >> sh) & 0xFF;
                out |= ((a * (uint32_t) alpha + b * (255 - (uint32_t) alpha)) / 255) << sh;
            }
            *d = out;
        }
    }
}

static uint32_t saved[PD_UI_WIDTH * PD_UI_HEIGHT];

static void save_rect(const struct pd_canvas* c, int x, int y, int w, int h) {
    for (int yy = 0; yy < h; yy++) {
        for (int xx = 0; xx < w; xx++) {
            int sx = x + xx, sy = y + yy;
            saved[yy * w + xx] = sx >= 0 && sx < c->w && sy >= 0 && sy < c->h ? c->px[sy * c->w + sx] : 0;
        }
    }
}

// A beveled button (PlatinumButton): a near-black outline, a light band across the top, darker bands down the sides.
void ui_platinum(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, uint32_t fill, uint32_t frame, int id) {
    bool pressed = ui->pressedId == id && id >= 0;
    if (GB_LOOK) {
        pd_layered_box(c, x, y, w, h, 2, pd_cur->chip, pd_cur->chipCount, pressed ? PD_ROW_SELECTED : PD_TITLE_FILL);
        if (id >= 0) ui_add_hit(ui, x, y, w, h, id);
        return;
    }
    uint32_t body = pressed ? mix(fill, 0, 18) : fill;
    pd_round_rect(c, x, y, w, h, 3, BUTTON_OUTLINE);
    pd_round_rect(c, x + 1, y + 1, w - 2, h - 2, 2, body);
    int side = w * 6 / 100;
    if (side < 3) side = 3;
    if (side > 10) side = 10;
    uint32_t inner = mix(body, frame, 55);
    for (int i = 0; i < side; i++) {
        uint32_t col = mix(frame, inner, i * 100 / side);
        pd_fill(c, x + 1 + i, y + 2, 1, h - 4, col);
        pd_fill(c, x + w - 2 - i, y + 2, 1, h - 4, col);
    }
    pd_fill(c, x + 2, y + 1, w - 4, 2, mix(body, 0xFFFFFF, 50));
    if (id >= 0) ui_add_hit(ui, x, y, w, h, id);
}

static void label(struct pd_canvas* c, int x, int y, int w, int h, const char* text, int scale) {
    int tw = pd_text_width(text) * scale;
    pd_text_scaled(c, x + (w - tw) / 2, y + (h - PD_FONT_HEIGHT * scale) / 2 + scale, text, GB_LOOK ? PD_TITLE_TEXT : 0xFFFFFF,
                   GB_LOOK ? PD_NO_SHADOW : LABEL_SHADOW, scale);
}

static void button(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, const char* text, int scale,
                   uint32_t fill, uint32_t frame, int id, bool enabled) {
    if (!enabled) save_rect(c, x, y, w, h);
    ui_platinum(ui, c, x, y, w, h, fill, frame, enabled ? id : -1);
    label(c, x, y, w, h, text, scale);
    if (!enabled) ui_fade_rect(c, saved, x, y, w, h, DISABLED_ALPHA);
}

// One ball per team member, grey once fainted, discs for the empty slots (PartyBallRow).
static const char* const STRIP_BALL[11] = {
    "...KKKKK...", ".KKRRRRRKK.", ".KRHRRRRRK.", "KRRRRRRRRRK", "KKKKWWWKKKK", "KWWKWWWKWWK", "KWWWKKKWWWK",
    "KWWWWWWWWWK", ".KWWWWWWWK.", ".KKWWWWWKK.", "...KKKKK...",
};

static void strip_ball(struct pd_canvas* c, int x, int y, int scale, int alive) {
    // alive: 1 a ball, 0 a fainted one, -1 a disc for an empty slot.
    static const uint32_t LIVE[4] = { 0x202020, 0xE83028, 0xFFFFFF, 0xF8A8A0 };
    static const uint32_t DEAD[4] = { 0x585860, 0x9898A0, 0xC8C8D0, 0xB0B0B8 };
    const uint32_t* p = alive > 0 ? LIVE : DEAD;
    for (int r = 0; r < 11; r++) {
        for (int col = 0; col < 11; col++) {
            char k = STRIP_BALL[r][col];
            if (k == '.') continue;
            uint32_t v = alive < 0 ? (k == 'K' ? 0x8A8A8A : 0xA8A8A8)
                : p[k == 'K' ? 0 : k == 'R' ? 1 : k == 'W' ? 2 : 3];
            pd_fill(c, x + col * scale, y + r * scale, scale, scale, v);
        }
    }
}

static void ball_row(struct pd_canvas* c, const struct pd_mon* mons, int count, int x, int y, int size, bool mirrored) {
    int step = size + size / 4 + 1;
    int scale = size >= 22 ? 2 : 1;
    for (int slot = 0; slot < PD_PARTY_SIZE; slot++) {
        int i = mirrored ? PD_PARTY_SIZE - 1 - slot : slot;
        int alive = i < count ? mons[i].hp > 0 : -1;
        strip_ball(c, x + slot * step, y, scale, alive);
    }
}

// What a move does to the foe, as a chip (MultiplierChip).
static int mult_chip(struct pd_canvas* c, int x, int y, int pct) {
    char text[8];
    if (pct == 0) snprintf(text, sizeof(text), "0x");
    else if (pct == 25) snprintf(text, sizeof(text), "1/4x");
    else if (pct == 50) snprintf(text, sizeof(text), "1/2x");
    else snprintf(text, sizeof(text), "%dx", pct / 100);
    uint32_t fill = pct == 0 ? 0x585048 : pct > 100 ? 0x60D048 : pct < 100 ? 0xF04838 : 0x8890A0;
    int w = pd_text_width(text) + 6;
    pd_round_rect(c, x, y, w, 12, 2, fill);
    pd_text(c, x + 3, y - 2, text, pct == 0 ? 0xF2B8BD : 0xFFFFFF, PD_NO_SHADOW);
    return w;
}

// A move's chip: MoveCard in its short screen's two lines.
static void move_card(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, int x, int y, int w, int h,
                      int move, int pp, const struct pd_battle_mon* foe, int index, bool busy, bool hints) {
    (void) g;
    char buf[40];
    bool usable = move && pp > 0 && !busy;
    uint32_t frame = move ? ui_type_color(pd_move_type(ui_game, move)) : SLOT_FILL_DARK;
    if (!usable) save_rect(c, x, y, w, h);
    ui_platinum(ui, c, x, y, w, h, move ? MOVE_CARD_FILL : SLOT_FILL_DARK, frame, usable ? HIT_BATTLE_MOVE + index : -1);
    if (!move) {
        label(c, x, y, w, h, "-", 1);
    } else {
        int ix = x + 11, iw = w - 22;
        int ty = y + (h - 31) / 2;
        char ppText[12];
        snprintf(ppText, sizeof(ppText), "PP %d", pp);
        pd_text_right(c, ix + iw, ty, ppText, MOVE_TEXT, MOVE_TEXT_SHADOW);
        pd_text_fit(c, ix, ty, iw - pd_text_width(ppText) - 4, pd_move_name(ui_game, move, buf, sizeof(buf)), MOVE_TEXT, MOVE_TEXT_SHADOW);
        int cy = ty + 17;
        int type = pd_move_type(ui_game, move);
        int cx = ix + ui_type_badge(c, ix, cy, type) + 3;
        int power = pd_move_power(ui_game, move);
        if (power > 0) snprintf(buf, sizeof(buf), "%d", power);
        else snprintf(buf, sizeof(buf), "-");
        cx += pd_text(c, cx, cy - 2, buf, PD_MUTED, MOVE_TEXT_SHADOW) + 3;
        if (hints && foe && foe->species && power > 0)
            mult_chip(c, cx, cy, pd_type_multiplier_pct(ui_game, type, foe->type1, foe->type2));
    }
    if (!usable) ui_fade_rect(c, saved, x, y, w, h, DISABLED_ALPHA);
}

static void move_grid(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                      int y, int bottom, bool busy, bool hints) {
    const struct pd_battle_mon* you = &s->battlers[PD_POS_PLAYER_LEFT];
    const struct pd_battle_mon* foe = &s->battlers[PD_POS_OPPONENT_LEFT];
    int x = MARGIN, w = c->w - 2 * MARGIN, cancelH = 24;
    int cardW = (w - GAP) / 2, cardH = (bottom - y - cancelH - 2 * GAP) / 2;
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        move_card(ui, c, g, x + (i % 2) * (cardW + GAP), y + (i / 2) * (cardH + GAP), cardW, cardH, you->moves[i],
                  you->pp[i], foe, i, busy, hints);
    }
    button(ui, c, x, bottom - cancelH, w, cancelH, "CANCEL", 1, RUN_BLUE, RUN_BLUE_DARK, HIT_BATTLE_CANCEL, !busy);
}

// The Game Boy games' menu as the game lays it out: FIGHT and PkMn over ITEM and RUN, in the cart's own words.
static void gb_action_buttons(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                              int y, int bottom, bool busy) {
    static const char* const WORDS[][4] = {
        { "FIGHT", "PkMn", "ITEM", "RUN" }, { "KMPF", "PkMn", "ITEM", "FLUCHT" }, { "ATTAQ", "PkMn", "OBJET", "FUITE" },
        { "LOTTA", "PkMn", "STRUM.", "FUGA" }, { "LUCHA", "PkMn", "OBJ.", "ESC" }, { "FIGHT", "PKMN", "PACK", "RUN" },
    };
    int lang = g->cfg->gb->gen == 2 ? 5 : g->language == 'D' ? 1 : g->language == 'F' ? 2 : g->language == 'I' ? 3 : g->language == 'S' ? 4 : 0;
    static const int HITS[4] = { HIT_BATTLE_FIGHT, HIT_BATTLE_POKEMON, HIT_BATTLE_BAG, HIT_BATTLE_RUN };
    int x = MARGIN, w = c->w - 2 * MARGIN;
    int bw = (w - GAP) / 2, bh = (bottom - y - GAP) / 2;
    for (int i = 0; i < 4; i++) {
        int bx = x + (i % 2) * (bw + GAP), by = y + (i / 2) * (bh + GAP);
        button(ui, c, bx, by, bw, bh, WORDS[lang][i], 2, 0, 0, HITS[i], !busy);
    }
    pd_draw_mon_icon(c, g, s->battlers[PD_POS_PLAYER_LEFT].species, x + 4, y + (bh - PD_ICON) / 2, false);
}

static void action_buttons(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                           int y, int bottom, bool busy, bool hints) {
    if (g->cfg && g->cfg->gb && !(ui->settings && ui->settings->oneTapMoves)) {
        gb_action_buttons(ui, c, g, s, y, bottom, busy);
        return;
    }
    int x = MARGIN, w = c->w - 2 * MARGIN, h = bottom - y;
    int lowH = (h - GAP * 3) * 38 / 100, drop = 7;
    int cols = (w - 2 * 7) / 3;
    const struct pd_battle_mon* you = &s->battlers[PD_POS_PLAYER_LEFT];
    if (ui->settings && ui->settings->oneTapMoves) {
        // ONE-TAP MOVES: the moves themselves where FIGHT is - a tap opens FIGHT and picks the move in one go.
        int rowH = (h - lowH - 2 * GAP) / 2, cardW = (w - GAP) / 2;
        for (int i = 0; i < PD_NUM_MOVES; i++)
            move_card(ui, c, g, x + (i % 2) * (cardW + GAP), y + (i / 2) * (rowH + GAP), cardW, rowH, you->moves[i],
                      you->pp[i], &s->battlers[PD_POS_OPPONENT_LEFT], i, busy, hints);
        drop = 0;
    } else {
        int fightW = w * 76 / 100, fightH = h - lowH - drop - GAP;
        int fx = x + (w - fightW) / 2;
        if (busy) save_rect(c, fx, y, fightW, fightH);
        ui_platinum(ui, c, fx, y, fightW, fightH, FIGHT_RED, FIGHT_RED_DARK, busy ? -1 : HIT_BATTLE_FIGHT);
        int tw = pd_text_width("FIGHT") * 3, iconW = PD_ICON + 6;
        int cx = fx + (fightW - tw - iconW) / 2;
        if (pd_draw_mon_icon(c, g, you->species, cx, y + (fightH - PD_ICON) / 2, false)) cx += iconW;
        else cx += iconW / 2;
        pd_text_scaled(c, cx, y + (fightH - PD_FONT_HEIGHT * 3) / 2 + 3, "FIGHT", 0xFFFFFF, LABEL_SHADOW, 3);
        if (busy) ui_fade_rect(c, saved, fx, y, fightW, fightH, DISABLED_ALPHA);
    }
    // BAG and POKéMON in the corners with RUN between them, a little lower.
    int ly = bottom - lowH - drop;
    button(ui, c, x, ly, cols, lowH, "BAG", 2, BAG_ORANGE, BAG_ORANGE_DARK, HIT_BATTLE_BAG, !busy);
    button(ui, c, x + cols + 7, ly + drop, cols, lowH, "RUN", 2, RUN_BLUE, RUN_BLUE_DARK, HIT_BATTLE_RUN, !busy);
    button(ui, c, x + 2 * (cols + 7), ly, w - 2 * (cols + 7), lowH, "POKéMON", 2, MON_GREEN, MON_GREEN_DARK,
           HIT_BATTLE_POKEMON, !busy);
}

// Who to send in: the PARTY tab's own slots, the game's cursor on SUGGESTIONS' top pick (tagged BEST) else on the one
// already battling (tagged OUT), over the game's prompt and CANCEL.
static void party_picker(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                         bool busy, bool hints) {
    int barH = 26, y = c->h - barH - 2;
    struct pd_canvas top = *c;
    top.h = y;
    if (top.cy1 > y) top.cy1 = y;
    int best = hints ? ui_recommended_switch(s) : -1;
    ui_party_grid(ui, &top, g, s, best, ui_out_slots(s));
    int x = MARGIN, w = c->w - 2 * MARGIN;
    pd_title_box(c, x, y, w, barH, PD_TITLE_FILL);
    pd_text(c, x + 8, pd_text_y(y, barH), "Choose a POKéMON.", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    if (busy) return;
    ui_button(ui, c, x + w - 70, y + 3, 66, barH - 6, "CANCEL", HIT_BATTLE_CANCEL);
}

static void centered(struct pd_canvas* c, int y, int h, const char* text) {
    pd_text(c, (c->w - pd_text_width(text)) / 2, pd_text_y(y, h), text, PD_ON_BACKDROP, PD_ON_BACKDROP_SHADOW);
}

static bool busy_now(const struct pd_ui* ui) {
    return ui->battleInput && pd_binput_busy(ui->battleInput);
}

bool ui_battle_controls_usable(const struct pd_ui* ui, const struct pd_snapshot* s) {
    if (!ui->battleInput || !pd_binput_available(ui->battleInput) || s->isDouble) return false;
    switch (s->battleState) {
    case PD_BATTLE_ACTION: case PD_BATTLE_MOVE: case PD_BATTLE_BAG_OPEN: case PD_BATTLE_PARTY_OPEN: case PD_BATTLE_TARGET:
        return true;
    default:
        return false;
    }
}

void ui_battle_controls(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    bool busy = busy_now(ui);
    bool hints = !ui->settings || !ui->settings->hideHints;
    bool canSwitch = ui->battleInput && pd_binput_can_switch(ui->battleInput);
    enum pd_battle_state state = s->battleState;
    bool picking = (state == PD_BATTLE_ACTION && ui->battleChoosingMon) || (state == PD_BATTLE_PARTY_OPEN && canSwitch);
    if (picking) {
        party_picker(ui, c, g, s, busy, hints);
    } else {
        int y = 1;
        ball_row(c, s->party, s->partyCount, MARGIN, y + 1, 22, false);
        if (s->isTrainer && s->foeCount > 0) ball_row(c, s->foes, s->foeCount, c->w - MARGIN - 6 * 15 + 2, y + 5, 11, true);
        y += STRIP_H + 4;
        int infoW = 64, suggestW = 104, btnH = 20, rowX = (c->w - infoW - suggestW - 6) / 2;
        button(ui, c, rowX, y, infoW, btnH, "INFO", 1, 0x9068D0, 0x5C3C98, HIT_BATTLE_INFO, true);
        button(ui, c, rowX + infoW + 6, y, suggestW, btnH, "SUGGESTIONS", 1, 0xE8B830, 0xA87C10, HIT_BATTLE_SUGGEST, true);
        y += btnH + 4;
        int bottom = c->h - 2;
        if (state == PD_BATTLE_MOVE || (state == PD_BATTLE_ACTION && ui->battleChoosingMove)) {
            move_grid(ui, c, g, s, y, bottom, busy, hints);
        } else if (state == PD_BATTLE_ACTION) {
            action_buttons(ui, c, g, s, y, bottom, busy, hints);
        } else if (state == PD_BATTLE_TARGET) {
            centered(c, y, bottom - y, "Confirming target...");
        } else {
            // The game's bag / party screen is up: all we can offer is the way back.
            int bw = c->w / 2, bh = 28;
            button(ui, c, (c->w - bw) / 2, y + (bottom - y - bh) / 2, bw, bh, "BACK", 1, SLOT_FILL_DARK, 0x206858,
                   HIT_BATTLE_CANCEL, !busy);
        }
    }
    if (busy) {
        // "Your tap registered, the game just hasn't caught up yet" (ProcessingBall).
        int size = 34, x = (c->w - size) / 2, y = (c->h - size) / 2;
        pd_title_box(c, x, y, size, size, PD_TITLE_FILL);
        ui_ball(c, x + 12, y + 12, true);
    }
}

bool ui_battle_controls_act(struct pd_ui* ui, int id, const struct pd_snapshot* s) {
    struct pd_binput* b = ui->battleInput;
    if (ui->tab != PD_TAB_BATTLE || !b) return false;
    // A slot of the POKéMON pane: fainted ones, eggs and the one that's out can't be picked (canSendIn).
    if (id >= HIT_SLOT && id < HIT_SLOT + PD_PARTY_SIZE) {
        int i = id - HIT_SLOT;
        const struct pd_mon* m = s && i < s->partyCount ? &s->party[i] : NULL;
        if (!pd_binput_busy(b) && m && m->hp > 0 && !m->egg && m->personality &&
            !(ui_out_slots(s) >> i & 1)) {
            pd_binput_switch(b, m->personality);
            ui->battleChoosingMon = false;
        }
        return true;
    }
    if (id < HIT_BATTLE_FIGHT || id >= HIT_BATTLE_MOVE + PD_NUM_MOVES) return false;
    if (pd_binput_busy(b)) return true;
    switch (id) {
    case HIT_BATTLE_FIGHT:
        // FIGHT opens our move grid at once, from the moves already read: no wait for the game's move screen.
        ui->battleChoosingMove = true;
        break;
    case HIT_BATTLE_BAG:
        pd_binput_action(b, PD_ACT_BAG);
        break;
    case HIT_BATTLE_RUN:
        pd_binput_action(b, PD_ACT_RUN);
        break;
    case HIT_BATTLE_POKEMON:
        if (pd_binput_can_switch(b)) ui->battleChoosingMon = true;
        else pd_binput_action(b, PD_ACT_POKEMON);
        break;
    case HIT_BATTLE_CANCEL:
        if (ui->battleChoosingMove || ui->battleChoosingMon) ui->battleChoosingMove = ui->battleChoosingMon = false;
        else pd_binput_back(b);
        break;
    default:
        pd_binput_move(b, id - HIT_BATTLE_MOVE);
        ui->battleChoosingMove = false;
        break;
    }
    return true;
}
