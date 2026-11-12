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

// HeartGold's battle buttons (BattleControlsScreen.kt's Hg*, sampled headless): the dark outline, each button's upper and
// lower colour.
#define HG_OUTLINE 0x414141
#define HG_RING 0xFBFBFB
#define HG_LABEL 0xFBF3E3
#define HG_RED 0xEB3838
#define HG_RED_LOW 0x8A4141
#define HG_ORANGE 0xE39A28
#define HG_ORANGE_LOW 0x8A6938
#define HG_BLUE 0x2892CB
#define HG_BLUE_LOW 0x415971
#define HG_GREEN 0x59B228
#define HG_GREEN_LOW 0x517141
#define INFO_PURPLE 0x9068D0
#define INFO_PURPLE_DARK 0x5C3C98
#define SUGGEST_GOLD 0xE8B830
#define SUGGEST_GOLD_DARK 0xA87C10
#define MOVE_TEXT 0x404850
#define MOVE_TEXT_SHADOW 0xC0C088
#define SLOT_FILL_DARK 0x308878
#define SLOT_FRAME 0x206858
#define LABEL_SHADOW 0x383838
#define DISABLED_ALPHA 115 // 0.45 of 255
// HeartGold's layout (ActionButtons): its bottom screen less the rows above the buttons, in DS pixels.
#define HG_WIDTH 256
#define HG_HEIGHT 160

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

// HeartGold's button (PlatinumButton): a thick dark outline, a thin white ring, then [fill] over [frame] (drawTwoTone). A Game
// Boy game's is its text box.
void ui_platinum(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, uint32_t fill, uint32_t frame, int id) {
    bool pressed = ui->pressedId == id && id >= 0;
    if (GB_LOOK) {
        pd_layered_box(c, x, y, w, h, 2, pd_cur->chip, pd_cur->chipCount, pressed ? PD_ROW_SELECTED : PD_TITLE_FILL);
        if (id >= 0) ui_add_hit(ui, x, y, w, h, id);
        return;
    }
    if (pressed) {
        fill = mix(fill, 0, 18);
        frame = mix(frame, 0, 18);
    }
    const struct pd_layer layers[3] = { { HG_OUTLINE, 2 }, { HG_RING, 1 }, { frame, 1 } };
    pd_layered_two_tone(c, x, y, w, h, 4, layers, 3, fill, frame);
    if (id >= 0) ui_add_hit(ui, x, y, w, h, id);
}

void ui_platinum_label(struct pd_canvas* c, int x, int y, int w, int h, const char* text) {
    pd_text(c, x + (w - pd_text_width(text)) / 2, pd_text_y(y, h), text, GB_LOOK ? PD_TITLE_TEXT : 0xFFFFFF,
            GB_LOOK ? PD_NO_SHADOW : 0x383838);
}

void ui_platinum_back(struct pd_canvas* c, int x, int y, int w, int h) {
    int ax = x + (w - PD_ICON_BACK.w) / 2, ay = y + (h - PD_ICON_BACK.h) / 2;
    if (!GB_LOOK) ui_mono(c, &PD_ICON_BACK, ax + 1, ay + 1, 1, 0x383838);
    ui_mono(c, &PD_ICON_BACK, ax, ay, 1, GB_LOOK ? PD_TITLE_TEXT : 0xFFFFFF);
}

// HeartGold's lettering (ButtonLabel(outlined)): cream with a dark line a font pixel all round. A Game Boy game's is its ink.
static void outlined(struct pd_canvas* c, int x, int y, const char* text, int scale) {
    if (GB_LOOK) {
        pd_text_scaled(c, x, y, text, PD_TITLE_TEXT, PD_NO_SHADOW, scale);
        return;
    }
    for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++)
            if (dx || dy) pd_text_scaled(c, x + dx * scale, y + dy * scale, text, HG_OUTLINE, PD_NO_SHADOW, scale);
    pd_text_scaled(c, x, y, text, HG_LABEL, PD_NO_SHADOW, scale);
}

static void label(struct pd_canvas* c, int x, int y, int w, int h, const char* text, int scale) {
    int tw = pd_text_width(text) * scale;
    pd_text_scaled(c, x + (w - tw) / 2, y + (h - 18 * scale) / 2, text, GB_LOOK ? PD_TITLE_TEXT : 0xFFFFFF,
                   GB_LOOK ? PD_NO_SHADOW : LABEL_SHADOW, scale);
}

// The largest scale up to [max] whose label fits [w] less the button's frame and padding.
static int fit_scale(const char* text, int w, int max) {
    int s = max;
    while (s > 1 && pd_text_width(text) * s + 2 * s > w - 8) s--;
    return s;
}

// A label HeartGold's way at [scale], its letters narrowed first (FittedGbaText: 2 tall, 1.5 wide) where it's too wide.
static void outlined_label(struct pd_canvas* c, int x, int y, int w, int h, const char* text, int scale) {
    int room = w - 8 - 2 * scale, num = scale * 4, den = 4;
    while (num > 4 && pd_text_width(text) * num / den > room) num--;
    int tw = pd_text_width(text) * num / den, tx = x + (w - tw) / 2, ty = y + (h - 17 * scale) / 2;   // the capitals in the middle, their outline all round
    if (num == scale * 4 || GB_LOOK) {
        outlined(c, tx, ty, text, scale);
        return;
    }
    for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++)
            if (dx || dy) pd_text_narrow_shifted(c, tx, ty + dy * scale, text, HG_OUTLINE, scale, num, den, dx);
    pd_text_narrow(c, tx, ty, text, HG_LABEL, scale, num, den);
}

static void button(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, const char* text, int scale,
                   uint32_t fill, uint32_t frame, int id, bool enabled) {
    if (!enabled) save_rect(c, x, y, w, h);
    ui_platinum(ui, c, x, y, w, h, fill, frame, enabled ? id : -1);
    label(c, x, y, w, h, text, scale);
    if (!enabled) ui_fade_rect(c, saved, x, y, w, h, DISABLED_ALPHA);
}

// A battle-menu button: HeartGold's lettering at the largest scale that fits.
static void hg_button(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, const char* text, int scale,
                      uint32_t fill, uint32_t frame, int id, bool enabled) {
    if (!enabled) save_rect(c, x, y, w, h);
    ui_platinum(ui, c, x, y, w, h, fill, frame, enabled ? id : -1);
    outlined_label(c, x, y, w, h, text, scale);
    if (!enabled) ui_fade_rect(c, saved, x, y, w, h, DISABLED_ALPHA);
}

// One of HeartGold's four pale move places (BattlePlaceholder): behind FIGHT, and where a Pokémon has no move.
static void placeholder(struct pd_canvas* c, int x, int y, int w, int h) {
    const struct pd_layer layers[3] = {
        { pd_battle_tint(0xBADFD7), 2 }, { pd_battle_tint(0xFFFFFF), 1 }, { pd_battle_tint(0xD3F3F7), 1 },
    };
    pd_layered_box(c, x, y, w, h, 4, layers, 3, pd_battle_tint(0xDFFFFF));
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
int ui_mult_chip(struct pd_canvas* c, int x, int y, int pct) {
    char text[16];
    if (pct == 0) snprintf(text, sizeof(text), "0x");
    else if (pct == 25) snprintf(text, sizeof(text), "1/4x");
    else if (pct == 50) snprintf(text, sizeof(text), "1/2x");
    else snprintf(text, sizeof(text), "%dx", pct / 100);
    uint32_t fill = pct == 0 ? 0x585048 : pct > 100 ? 0x60D048 : pct < 100 ? 0xF04838 : 0x8890A0;
    int w = pd_text_width(text) + 6;
    if (!c) return w; // measuring
    pd_round_rect(c, x, y, w, 12, 2, fill);
    pd_text(c, x + 3, y - 2, text, pct == 0 ? 0xF2B8BD : 0xFFFFFF, PD_NO_SHADOW);
    return w;
}

// A move (MoveCard): HeartGold's card, the type's colour round a panel white over beige (hgMovePanel), in a short screen's two
// lines - name and PP, then type, power and the verdict. A move the Pokémon doesn't have is an empty place.
static void move_card(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, int x, int y, int w, int h,
                      int move, int pp, const struct pd_battle_mon* foe, int index, bool busy, bool hints) {
    (void) g;
    char buf[40];
    if (!move && !GB_LOOK) {
        placeholder(c, x, y, w, h);
        return;
    }
    bool usable = move && pp > 0 && !busy;
    int type = move ? pd_move_type(ui_game, move) : 0;
    uint32_t frame = move ? ui_type_color(type) : SLOT_FILL_DARK;
    if (!usable) save_rect(c, x, y, w, h);
    ui_platinum(ui, c, x, y, w, h, frame, mix(frame, 0, 50), usable ? HIT_BATTLE_MOVE + index : -1);
    if (!move) {
        label(c, x, y, w, h, "-", 1);
    } else {
        // The panel: in from the edge by the frame (4) and the card's padding (3), a pixel of rim, then the two halves.
        int px = x + 7, py = y + 4, pw = w - 14, ph = h - 8;
        if (!GB_LOOK) {
            pd_fill(c, px, py, pw, ph, mix(frame, 0xFFFFFF, 40));
            int split = (ph - 2) * 47 / 100;
            pd_fill(c, px + 1, py + 1, pw - 2, split, 0xFBFBFB);
            pd_fill(c, px + 1, py + 1 + split, pw - 2, 2, 0xE3DBC3);
            pd_fill(c, px + 1, py + 3 + split, pw - 2, ph - 4 - split, 0xCBBAA2);
        }
        // The name and PP centred on the white half, the rest on the beige (HeartGold's halves).
        int ix = px + 4, iw = pw - 8;
        int split = (ph - 2) * 47 / 100;
        int ty = GB_LOOK ? py + (ph - 31) / 2 : pd_text_y(py + 1, split);
        char ppText[12];
        snprintf(ppText, sizeof(ppText), "PP %d", pp);
        pd_text_right(c, ix + iw, ty, ppText, MOVE_TEXT, MOVE_TEXT_SHADOW);
        pd_text_fit(c, ix, ty, iw - pd_text_width(ppText) - 4, pd_move_name(ui_game, move, buf, sizeof(buf)), MOVE_TEXT, MOVE_TEXT_SHADOW);
        int cy = GB_LOOK ? ty + 18 : py + 3 + split + (ph - 4 - split - 12) / 2;
        int cx = ix + ui_type_badge(c, ix, cy, type) + 3;
        int power = pd_move_power(ui_game, move);
        if (power > 0) snprintf(buf, sizeof(buf), "PWR %d", power);
        else snprintf(buf, sizeof(buf), "PWR -");
        if (cx + pd_text_width(buf) > ix + iw - 20) {
            if (power > 0) snprintf(buf, sizeof(buf), "%d", power);
            else snprintf(buf, sizeof(buf), "-");
        }
        cx += pd_text(c, cx, cy - 2, buf, PD_MUTED, PD_NO_SHADOW) + 3;
        if (hints && foe && foe->species && power > 0)
            ui_mult_chip(c, cx, cy, pd_type_multiplier_pct(ui_game, type, foe->type1, foe->type2));
    }
    if (!usable) ui_fade_rect(c, saved, x, y, w, h, DISABLED_ALPHA);
}

static void move_grid(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                      int y, int bottom, bool busy, bool hints) {
    const struct pd_battle_mon* you = &s->battlers[PD_POS_PLAYER_LEFT];
    const struct pd_battle_mon* foe = &s->battlers[PD_POS_OPPONENT_LEFT];
    const int gap = 6;
    int x = MARGIN, w = c->w - 2 * MARGIN, cancelH = 26;
    int cardW = (w - gap) / 2, cardH = (bottom - y - cancelH - 2 * gap) / 2;
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        move_card(ui, c, g, x + (i % 2) * (cardW + gap), y + (i / 2) * (cardH + gap), cardW, cardH, you->moves[i],
                  you->pp[i], foe, i, busy, hints);
    }
    hg_button(ui, c, x, bottom - cancelH, w, cancelH, "CANCEL", 1, HG_BLUE, HG_BLUE_LOW, HIT_BATTLE_CANCEL, !busy);
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
    const struct pd_battle_mon* you = &s->battlers[PD_POS_PLAYER_LEFT];
    if (ui->settings && ui->settings->oneTapMoves) {
        // ONE-TAP MOVES: the moves themselves where FIGHT is - a tap opens FIGHT and picks the move in one go - over BAG,
        // RUN and POKéMON in a row.
        const int gap = 6, rowH = 34;
        int cardH = (h - rowH - 2 * gap) / 2, cardW = (w - gap) / 2;
        for (int i = 0; i < PD_NUM_MOVES; i++)
            move_card(ui, c, g, x + (i % 2) * (cardW + gap), y + (i / 2) * (cardH + gap), cardW, cardH, you->moves[i],
                      you->pp[i], &s->battlers[PD_POS_OPPONENT_LEFT], i, busy, hints);
        int bw = (w - 2 * 10) / 3, by = bottom - rowH;
        hg_button(ui, c, x, by, bw, rowH, "BAG", 2, HG_ORANGE, HG_ORANGE_LOW, HIT_BATTLE_BAG, !busy);
        hg_button(ui, c, x + bw + 10, by, bw, rowH, "RUN", 2, HG_BLUE, HG_BLUE_LOW, HIT_BATTLE_RUN, !busy);
        hg_button(ui, c, x + 2 * (bw + 10), by, w - 2 * (bw + 10), rowH, "POKéMON", 2, HG_GREEN, HG_GREEN_LOW,
                  HIT_BATTLE_POKEMON, !busy);
        return;
    }
    // HeartGold's layout and proportions, a DS pixel being [ds] 256ths of a canvas pixel: FIGHT over the four move places,
    // BAG and POKéMON in the bottom corners, RUN between them a little lower. The height comes from the width, so a tall pane
    // leaves room rather than stretching them; widths go on past HeartGold's where there's room (HG_MAX_WIDEN 1.3).
    int ds = w * 256 / HG_WIDTH;
    if (h * 256 / HG_HEIGHT < ds) ds = h * 256 / HG_HEIGHT;
    int wide = w * 256 / HG_WIDTH;
    if (wide > ds * 13 / 10) wide = ds * 13 / 10;
#define DS(n) ((n) * ds / 256)
#define WIDE(n) ((n) * wide / 256)
    int bottomRow = DS(46) + DS(8), panelH = h - bottomRow - DS(3), gap = DS(2) > 1 ? DS(2) : 1;
    int pw = (w - gap) / 2, ph = (panelH - gap) / 2;
    for (int i = 0; i < 4; i++) placeholder(c, x + (i % 2) * (pw + gap), y + (i / 2) * (ph + gap), pw, ph);
    int fightW = DS(208), fightH = DS(88);
    if (fightW > w) fightW = w;
    if (fightH > panelH) fightH = panelH;
    int fx = x + (w - fightW) / 2, fy = y + (panelH - fightH) / 2;
    if (busy) save_rect(c, fx, fy, fightW, fightH);
    ui_platinum(ui, c, fx, fy, fightW, fightH, HG_RED, HG_RED_LOW, busy ? -1 : HIT_BATTLE_FIGHT);
    // The active mon's icon left of the word, like the game.
    int scale = fit_scale("FIGHT", fightW - PD_ICON, 3), tw = pd_text_width("FIGHT") * scale, iconW = PD_ICON + 6;
    int cx = fx + (fightW - tw - iconW) / 2;
    // (A party icon stands at the bottom of its 32 x 32: raised a little, it sits level with the word.)
    if (pd_draw_mon_icon(c, g, you->species, cx, fy + (fightH - PD_ICON) / 2 - 3, false)) cx += iconW;
    else cx += iconW / 2;
    outlined(c, cx, fy + (fightH - 17 * scale) / 2, "FIGHT", scale);
    if (busy) ui_fade_rect(c, saved, fx, fy, fightW, fightH, DISABLED_ALPHA);
    // The row's ends flush with the move places' (HeartGold's 254 of 256 rounds to the pane's own edges).
    int rowW = w, bw = WIDE(82), bh = DS(46);
    int rx = x, ry = bottom - bottomRow;
    hg_button(ui, c, rx, ry, bw, bh, "BAG", 2, HG_ORANGE, HG_ORANGE_LOW, HIT_BATTLE_BAG, !busy);
    hg_button(ui, c, rx + (rowW - bw) / 2, bottom - bh, bw, bh, "RUN", 2, HG_BLUE, HG_BLUE_LOW, HIT_BATTLE_RUN, !busy);
    hg_button(ui, c, rx + rowW - bw, ry, bw, bh, "POKéMON", 2, HG_GREEN, HG_GREEN_LOW, HIT_BATTLE_POKEMON, !busy);
#undef DS
#undef WIDE
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

bool ui_battle_input_on(const struct pd_ui* ui, const struct pd_snapshot* s) {
    return ui->battleInput && pd_binput_available(ui->battleInput) && !s->isDouble;
}

bool ui_battle_info_on(const struct pd_ui* ui) { return !ui->settings || !ui->settings->noBattleInfo; }

bool ui_battle_hints_on(const struct pd_ui* ui) { return ui_battle_info_on(ui) && !(ui->settings && ui->settings->hideHints); }

bool ui_battle_controls_usable(const struct pd_ui* ui, const struct pd_snapshot* s) {
    if (!ui_battle_input_on(ui, s)) return false;
    switch (s->battleState) {
    case PD_BATTLE_ACTION: case PD_BATTLE_MOVE: case PD_BATTLE_BAG_OPEN: case PD_BATTLE_PARTY_OPEN: case PD_BATTLE_TARGET:
        return true;
    default:
        return false;
    }
}

void ui_battle_controls(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    bool busy = busy_now(ui);
    bool hints = ui_battle_hints_on(ui);
    bool canSwitch = ui->battleInput && pd_binput_can_switch(ui->battleInput);
    enum pd_battle_state state = s->battleState;
    // BATTLE STYLE > GEN 5: Black / White's touch screen (a Game Boy game keeps its own look).
    bool picking = (state == PD_BATTLE_ACTION && ui->battleChoosingMon) || (state == PD_BATTLE_PARTY_OPEN && canSwitch);
    if (ui_gen5_style(ui, g)) {
        ui_battle_gen5(ui, c, g, s, busy);
    } else if (picking) {
        party_picker(ui, c, g, s, busy, hints);
    } else {
        // Your balls from the left edge, the foe's (smaller) flush with the right one, both on one middle line.
        const int ballTop = 2, ballH = 22, foeBall = 11, foeRow = 5 * (foeBall + foeBall / 4 + 1) + foeBall;
        ball_row(c, s->party, s->partyCount, MARGIN, ballTop, ballH, false);
        if (s->isTrainer && s->foeCount > 0)
            ball_row(c, s->foes, s->foeCount, c->w - MARGIN - foeRow, ballTop + (ballH - foeBall) / 2, foeBall, true);
        int y = ballTop + ballH + 4;
        if (ui_battle_info_on(ui)) {
            int infoW = 72, suggestW = 112, btnH = 26, rowX = (c->w - infoW - suggestW - 6) / 2;
            button(ui, c, rowX, y, infoW, btnH, "INFO", 1, INFO_PURPLE, INFO_PURPLE_DARK, HIT_BATTLE_INFO, true);
            button(ui, c, rowX + infoW + 6, y, suggestW, btnH, "SUGGESTIONS", 1, SUGGEST_GOLD, SUGGEST_GOLD_DARK, HIT_BATTLE_SUGGEST, true);
            y += btnH + 5;
        }
        int bottom = c->h - 2;
        if (state == PD_BATTLE_MOVE || (state == PD_BATTLE_ACTION && ui->battleChoosingMove)) {
            move_grid(ui, c, g, s, y, bottom, busy, hints);
        } else if (state == PD_BATTLE_ACTION) {
            action_buttons(ui, c, g, s, y, bottom, busy, hints);
        } else if (state == PD_BATTLE_TARGET) {
            centered(c, y, bottom - y, "Confirming target...");
        } else if (state != PD_BATTLE_BAG_OPEN && state != PD_BATTLE_PARTY_OPEN) {
            // Between turns (BATTLE INFO off, or RUN's hold): nothing to press; after RUN there's no turn coming.
            if (!ui->ranAway && !busy) centered(c, y, bottom - y, "Waiting for your turn...");
        } else {
            // The game's bag / party screen is up: all we can offer is the way back.
            int bw = c->w / 2, bh = 28;
            hg_button(ui, c, (c->w - bw) / 2, y + (bottom - y - bh) / 2, bw, bh, "BACK", 1, SLOT_FILL_DARK, SLOT_FRAME,
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
        ui->ranAway = true;
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
