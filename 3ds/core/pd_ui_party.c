// PARTY: the six slots in FireRed's party colours, each with the game's own
// icon (from the ROM, pd_icon), and a summary on tap.
#include <stdio.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_games.h"
#include "pd_icon.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

// A game's slots: PartyPalette (PartyScreen.kt), pd_party_cur. A state's frame is its outline, light inner edge, upper
// fill and bottom shade, the HP band below the divider its band colour.
#define PP pd_party_cur
#define SLOT_TEXT (PP->text)
#define SLOT_TEXT_SHADOW (PP->textShadow)
#define MALE (PP->male)
#define MALE_SHADOW (PP->maleShadow)
#define FEMALE (PP->female)
#define FEMALE_SHADOW (PP->femaleShadow)
#define HP_FRAME (PP->hpFrame)
#define HP_INNER (PP->hpInner)
#define HP_LETTER (PP->hpLabel)
#define HP_LETTER_SHADE (PP->hpLabelShade)
#define EMPTY_ALPHA (PP->emptyAlpha)
#define SLOT_NORMAL (PP->normal)
#define SLOT_SELECTED (PP->selected)
#define SLOT_FAINTED (PP->fainted)
#define SLOT_SELECTED_FAINTED (PP->selectedFainted)
#define HP_GREEN (PP->hpGreen)
#define HP_YELLOW (PP->hpYellow)
#define HP_RED (PP->hpRed)
#define HP_NONE (PP->hpEmpty)

// FireRed's closed party Poke Ball (PartyBall: partyfr/pokeball.png, 20x20).
static const char* const BALL[20] = {
    ".......KKKKKK.......", ".....KKKKKKKKKK.....", "....KKKRRRRRRKKK....", "...KKRRRRRRRRRRKK...",
    "..KKRRRRRRRRRRRRKK..", ".KKRRRRRRRRRRRRRRKK.", ".KKRRRRRRRRRRRRRRKK.", "KKRRRRRRKKKKRRRRRRKK",
    "KKHHHHHKKWWKKRRRRRKK", "KKKKKKKKWWWWKKKKKKKK", "KKKKKKKKWWWWKKKKKKKK", "KKWWWWWKKWWKKWWWWWKK",
    "KKWWWWWWKKKKWWWWWWKK", ".KKWWWWWWWWWWWWWWKK.", ".KKWWWWWWWWWWWWWWKK.", "..KKWWWWWWWWWWWWKK..",
    "...KKWWWWWWWWWWKK...", "....KKKWWWWWWKKK....", ".....KKKKKKKKKK.....", ".......KKKKKK.......",
};
// The "HP" label's letters (HpLetters): the top 3 rows in HP_LETTER, the rest in HP_LETTER_SHADE.
static const char* const HP_LETTERS[5] = { "1001.1110", "1001.1001", "1111.1110", "1001.1000", "1001.1000" };
// PixelIcons.male / female.
static const char* const MALE_MARK[8] = { "00001111", "00000011", "00000101", "01111000",
                                          "10001000", "10001000", "10001000", "01110000" };
static const char* const FEMALE_MARK[8] = { "01110", "10001", "10001", "10001", "01110", "00100", "11111", "00100" };

// A character bitmap, `u` canvas pixels a pixel: one colour per character, '.' / '0' clear.
static void char_bitmap(struct pd_canvas* c, const char* const* rows, int n, int x, int y, int u,
                        const char* keys, const uint32_t* colors) {
    for (int r = 0; r < n; r++) {
        for (int col = 0; rows[r][col]; col++) {
            const char* k = strchr(keys, rows[r][col]);
            if (k && rows[r][col] != '.' && rows[r][col] != '0') pd_fill(c, x + col * u, y + r * u, u, u, colors[k - keys]);
        }
    }
}

// Our own mark for a held item (the game's is its art): a little satchel at the icon's lower right.
static void held_mark(struct pd_canvas* c, int x, int y) {
    static const char* const MARK[7] = { "..KKK..", ".K...K.", "KKKKKKK", "KWWWWWK", "KWYYYWK", "KWWWWWK", "KKKKKKK" };
    static const uint32_t COLORS[3] = { 0x303030, 0xF8F8F8, 0xF8C030 };
    for (int r = 0; r < 7; r++)
        for (int col = 0; col < 7; col++) {
            char k = MARK[r][col];
            if (k != '.') pd_fill(c, x + col, y + r, 1, 1, COLORS[k == 'K' ? 0 : k == 'W' ? 1 : 2]);
        }
}

static const char* mon_display_name(const struct pd_mon* m, char* buf, size_t len) {
    if (m->egg) return "EGG";
    if (m->nickname[0]) return m->nickname;
    return pd_species_name(m->species, buf, len);
}

// The summary's ♂ / ♀ after the name, in their own colours.
static void gender_mark(struct pd_canvas* c, int x, int y, enum pd_gender g) {
    if (g == PD_GENDER_MALE) pd_text(c, x, y, "♂", MALE, MALE_SHADOW);
    if (g == PD_GENDER_FEMALE) pd_text(c, x, y, "♀", FEMALE, FEMALE_SHADOW);
}

// Where everything sits (PartyScreen.kt's SlotGeometry), in whole slot pixels `u`
// (canvas pixels): FireRed's MAIN slot, its frame stretched to the cell, the Poke
// Ball and icon overhanging 8 px left and 6 px up.
struct slot_geometry {
    int u, fx, fy, fw, fh, bottom, dividerY, barY, labelX, barX, barRight, textX;
    int nameMid, levelMid, hpTextMid;
};

static struct slot_geometry geometry(int w, int h) {
    struct slot_geometry g;
    float scale = (float) w / 86 < (float) h / 57 ? (float) w / 86 : (float) h / 57;
    g.u = scale < 1 ? 1 : (int) scale;
    int u = g.u;
    g.fx = 8 * u;
    g.fy = 6 * u;
    g.fw = (w - g.fx - 2 * u) / u * u;
    g.fh = (h - g.fy - 2 * u) / u * u;
    g.bottom = g.fy + g.fh;
    g.dividerY = g.bottom - 19 * u;
    g.barY = g.dividerY + u;
    g.labelX = g.fx + 20 * u;
    g.barX = g.labelX + 11 * u;
    g.barRight = g.fx + g.fw - 4 * u;
    g.textX = g.fx + 30 * u;
    g.nameMid = g.fy + 3 * u + (int) ((g.dividerY - g.fy - 3 * u) * 0.3f);
    g.levelMid = g.fy + 3 * u + (int) ((g.dividerY - g.fy - 3 * u) * 0.74f);
    g.hpTextMid = g.barY + 10 * u;
    return g;
}

static void slot_frame(struct pd_canvas* c, int x, int y, const struct slot_geometry* g, const struct pd_slot_colors* col) {
    int u = g->u;
    pd_round_rect(c, x + g->fx, y + g->fy, g->fw, g->fh, 3 * u, col->outline);
    pd_round_rect(c, x + g->fx + 2 * u, y + g->fy + 2 * u, g->fw - 4 * u, g->fh - 4 * u, u, col->light);
    pd_fill(c, x + g->fx + 3 * u, y + g->fy + 3 * u, g->fw - 6 * u, g->dividerY - g->fy - 3 * u, col->fill);
    if (col->band != col->light) pd_fill(c, x + g->fx + 3 * u, y + g->dividerY, g->fw - 6 * u, g->bottom - 3 * u - g->dividerY, col->band);
    pd_fill(c, x + g->fx + 2 * u, y + g->bottom - 3 * u, g->fw - 4 * u, u, col->shade);
}

static void hp_bar(struct pd_canvas* c, int x, int y, const struct slot_geometry* g, int hp, int maxHp) {
    int u = g->u, by = y + g->barY;
    // The "HP" label box, sharing its right edge with the bar's outline.
    pd_round_rect(c, x + g->labelX, by, g->barX - g->labelX + u, 7 * u, u, HP_FRAME);
    for (int r = 0; r < 5; r++) {
        for (int col = 0; HP_LETTERS[r][col]; col++) {
            if (HP_LETTERS[r][col] == '1')
                pd_fill(c, x + g->labelX + (1 + col) * u, by + (1 + r) * u, u, u, r < 3 ? HP_LETTER : HP_LETTER_SHADE);
        }
    }
    int w = g->barRight - g->barX;
    pd_round_rect(c, x + g->barX, by, w, 7 * u, 2 * u, HP_FRAME);
    pd_round_rect(c, x + g->barX + u, by + u, w - 2 * u, 5 * u, u, HP_INNER);
    int inner = w - 4 * u;
    if (hp > maxHp) hp = maxHp;
    if (hp < 0) hp = 0;
    int filled = maxHp > 0 ? inner / u * hp / maxHp * u : 0;
    if (filled < u && hp > 0) filled = u;
    const struct pd_hp_band* col = maxHp <= 0 || hp * 2 > maxHp ? &HP_GREEN : hp * 5 > maxHp ? &HP_YELLOW : &HP_RED;
    int bx = x + g->barX + 2 * u;
    pd_fill(c, bx, by + 2 * u, inner, u, HP_NONE.top);
    pd_fill(c, bx, by + 3 * u, inner, 2 * u, HP_NONE.main);
    pd_fill(c, bx, by + 2 * u, filled, u, col->top);
    pd_fill(c, bx, by + 3 * u, filled, 2 * u, col->main);
}

// The status chip (StatusChip): a darker rim around the status colour, FNT grey.
static void status_chip(struct pd_canvas* c, int x, int y, const char* label) {
    uint32_t color = !strcmp(label, "FNT") ? 0x6B6B6B : !strcmp(label, "SLP") ? 0x8A8A8A
        : !strcmp(label, "PSN") ? 0xA259D9 : !strcmp(label, "BRN") ? 0xE0693E : !strcmp(label, "PAR") ? 0xD9C341
        : !strcmp(label, "FRZ") ? 0x56C2E6 : !strcmp(label, "TOX") ? 0x7A3EA1 : 0x8890A0;
    uint32_t rim = ((((color >> 16) & 0xFF) * 65 / 100) << 16) | ((((color >> 8) & 0xFF) * 65 / 100) << 8)
        | ((color & 0xFF) * 65 / 100);
    uint32_t shadow = ((((color >> 16) & 0xFF) / 2) << 16) | ((((color >> 8) & 0xFF) / 2) << 8) | ((color & 0xFF) / 2);
    int w = pd_text_width(label) + 8;
    pd_round_rect(c, x, y, w, 14, 2, rim);
    pd_round_rect(c, x + 1, y + 1, w - 2, 12, 1, color);
    pd_text(c, x + 4, y - 1, label, 0xFFFFFF, shadow);
}

// An empty slot: its box, faint over the backdrop (drawn whole, then blended in).
static void empty_slot(struct pd_canvas* c, int x, int y, int w, int h, const struct slot_geometry* g) {
    static uint32_t scratch[512 * 256];
    if (w <= 0 || h <= 0 || w * h > (int) (sizeof(scratch) / sizeof(*scratch))) return;
    struct pd_canvas t;
    pd_canvas_init(&t, w, h, scratch);
    memset(scratch, 0xFF, (size_t) w * h * sizeof(*scratch)); // 0xFFFFFFFF: nothing drawn
    slot_frame(&t, 0, 0, g, &SLOT_NORMAL);
    for (int yy = 0; yy < h; yy++) {
        int sy = y + yy;
        if (sy < c->cy0 || sy >= c->cy1) continue;
        for (int xx = 0; xx < w; xx++) {
            int sx = x + xx;
            uint32_t v = scratch[yy * w + xx];
            if (v == 0xFFFFFFFF || sx < c->cx0 || sx >= c->cx1) continue;
            uint32_t* d = &c->px[sy * c->w + sx];
            uint32_t out = 0;
            for (int sh = 0; sh <= 16; sh += 8) {
                uint32_t a = (v >> sh) & 0xFF, b = (*d >> sh) & 0xFF;
                out |= ((a * EMPTY_ALPHA + b * (255 - EMPTY_ALPHA)) / 255) << sh;
            }
            *d = out;
        }
    }
}

static void party_slot(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, int x, int y, int w,
                       int h, const struct pd_mon* m, int slot, bool selected) {
    struct slot_geometry geo = geometry(w, h);
    int u = geo.u;
    if (!m) {
        empty_slot(c, x, y, w, h, &geo);
        return;
    }
    char buf[40];
    bool egg = m->egg;
    bool fainted = !egg && m->hp == 0;
    const struct pd_slot_colors* col = fainted ? (selected ? &SLOT_SELECTED_FAINTED : &SLOT_FAINTED)
        : selected ? &SLOT_SELECTED : &SLOT_NORMAL;
    slot_frame(c, x, y, &geo, col);
    if (!egg) hp_bar(c, x, y, &geo, m->hp, m->maxHp);

    // The Poke Ball, then the icon over it, resting 4 px up.
    char_bitmap(c, BALL, 20, x + geo.fx - 6 * u, y + geo.fy - 4 * u, u, "KRHW", PP->ball);
    pd_draw_mon_icon_scaled(c, g, m->species, x + geo.fx - 8 * u, y + geo.fy - 4 * u, u, false);
    if (m->heldItem) held_mark(c, x + geo.fx + 13 * u, y + geo.fy + 15 * u);

    int textW = geo.fx + geo.fw - 4 * u - geo.textX;
    pd_text_fit(c, x + geo.textX, y + geo.nameMid - 8, textW, mon_display_name(m, buf, sizeof(buf)), SLOT_TEXT,
                SLOT_TEXT_SHADOW);
    if (!egg) {
        const char* status = fainted ? "FNT" : pd_status_label(m->status);
        if (*status) {
            status_chip(c, x + geo.textX + 6 * u, y + geo.levelMid - 7, status);
        } else {
            char lv[12];
            snprintf(lv, sizeof(lv), "Lv%d", m->level);
            pd_text(c, x + geo.textX + 6 * u, y + geo.levelMid - 8, lv, SLOT_TEXT, SLOT_TEXT_SHADOW);
        }
        int gx = x + geo.fx + geo.fw - 14 * u, gy = y + geo.levelMid - 4 * u;
        if (m->gender == PD_GENDER_MALE || m->gender == PD_GENDER_FEMALE) {
            bool male = m->gender == PD_GENDER_MALE;
            const uint32_t shadow[1] = { male ? MALE_SHADOW : FEMALE_SHADOW };
            const uint32_t color[1] = { male ? MALE : FEMALE };
            char_bitmap(c, male ? MALE_MARK : FEMALE_MARK, 8, gx + u, gy + u, u, "1", shadow);
            char_bitmap(c, male ? MALE_MARK : FEMALE_MARK, 8, gx, gy, u, "1", color);
        }
        char hp[16];
        snprintf(hp, sizeof(hp), "%d/%d", m->hp, m->maxHp);
        pd_text_right(c, x + geo.barRight, y + geo.hpTextMid - 8, hp, SLOT_TEXT, SLOT_TEXT_SHADOW);
    }
    ui_add_hit(ui, x + geo.fx, y + geo.fy, geo.fw, geo.fh, HIT_SLOT + slot);
}

// The stat table's order (SUMMARY_STAT_ORDER): HP, ATTACK, DEFENSE, SP. ATK, SP. DEF, SPEED, as indexes into the
// struct's order.
static const int STAT_ORDER[6] = { 0, 1, 2, 4, 5, 3 };
static const char* const STAT_LABELS[6] = { "HP", "ATTACK", "DEFENSE", "SP. ATK", "SP. DEF", "SPEED" };
static const char* const NATURES[25] = {
    "Hardy", "Lonely", "Brave", "Adamant", "Naughty", "Bold", "Docile", "Relaxed", "Impish", "Lax", "Timid", "Hasty",
    "Serious", "Jolly", "Naive", "Modest", "Mild", "Quiet", "Bashful", "Rash", "Calm", "Gentle", "Sassy", "Careful", "Quirky",
};
#define EXP_BLUE 0x40B8F8
#define STATS_PURPLE 0x9068D0
#define STATS_PURPLE_DARK 0x5C3C98
#define SUMMARY_COLUMN 34

// What the summary's bars show: a label, a bar in the HP frame, its text.
static void summary_bar(struct pd_canvas* c, int x, int y, int w, const char* label, const char* text, int value, int max,
                        uint32_t color) {
    int labelW = pd_text(c, x, y, label, PD_VALUE, PD_VALUE_SHADOW) + 3;
    int textW = pd_text_width(text);
    pd_text(c, x + w - textW, y, text, PD_LABEL, PD_LABEL_SHADOW);
    int bx = x + labelW, bw = w - labelW - textW - 4;
    pd_round_rect(c, bx, y + 6, bw, 6, 2, 0x525252);
    pd_fill(c, bx + 1, y + 7, bw - 2, 4, 0xB0B0B0);
    int fill = max > 0 ? (bw - 2) * value / max : 0;
    if (fill < 1 && value > 0) fill = 1;
    if (fill > 0) pd_fill(c, bx + 1, y + 7, fill, 4, color);
}

// EXP at a level by growth rate (the games' gExperienceTables): medium fast, erratic, fluctuating, medium slow, fast, slow.
long pd_exp_at(int rate, int level) {
    long n = level, n3 = n * n * n;
    switch (rate) {
    case 1:
        if (n <= 50) return n3 * (100 - n) / 50;
        if (n <= 68) return n3 * (150 - n) / 100;
        if (n <= 98) return n3 * ((1911 - 10 * n) / 3) / 500;
        return n3 * (160 - n) / 100;
    case 2:
        if (n <= 15) return n3 * ((n + 1) / 3 + 24) / 50;
        if (n <= 36) return n3 * (n + 14) / 50;
        return n3 * (n / 2 + 32) / 50;
    case 3: return 6 * n3 / 5 - 15 * n * n + 100 * n - 140;
    case 4: return 4 * n3 / 5;
    case 5: return 5 * n3 / 4;
    default: return n3;
    }
}

// The type's matchups as chips: what attacks it at pct, wrapped into w. Returns the y after them.
static int chips_of(struct pd_canvas* c, const char* heading, int t1, int t2, int lo, int hi, int x, int y, int w, int bottom) {
    bool any = false;
    for (int t = 0; t < 18 && !any; t++) {
        int pct = pd_type_multiplier_pct(t, t1, t2);
        any = t != 9 && pct >= lo && pct <= hi;
    }
    if (!any || y + 14 > bottom) return y;
    pd_text(c, x, y - 1, heading, PD_MUTED, PD_MUTED_SHADOW);
    int cx = x + pd_text_width(heading) + 4;
    for (int t = 0; t < 18; t++) {
        int pct = pd_type_multiplier_pct(t, t1, t2);
        if (t == 9 || pct < lo || pct > hi) continue;
        char label[12];
        snprintf(label, sizeof(label), "%.3s", pd_type_name(t));
        for (char* q = label; *q; q++) if (*q >= 'a' && *q <= 'z') *q = (char) (*q - 32);
        int cw = pd_text_width(label) + 6;
        if (cx + cw > x + w) {
            cx = x;
            y += 14;
            if (y + 13 > bottom) return y;
        }
        pd_round_rect(c, cx, y + 1, cw, 12, 1, ui_type_color(t));
        pd_text(c, cx + 3, y - 1, label, 0xFFFFFF, PD_NO_SHADOW);
        cx += cw + 2;
    }
    return y + 14;
}

static void summary_stats(struct pd_canvas* c, const struct pd_mon* m, int x, int y, int w) {
    char buf[24];
    int labelW = w * 36 / 100, statW = 26, evW = 24;
    pd_text_right(c, x + labelW + statW, y, "STAT", PD_MUTED, PD_MUTED_SHADOW);
    pd_text(c, x + labelW + statW + 6, y, "IV", PD_MUTED, PD_MUTED_SHADOW);
    pd_text_right(c, x + w, y, "EV", PD_MUTED, PD_MUTED_SHADOW);
    y += 15;
    int up = m->nature / 5, down = m->nature % 5; // attack, defense, speed, sp. atk, sp. def
    int totalIv = 0, totalEv = 0;
    for (int i = 0; i < 6; i++) {
        int s = STAT_ORDER[i];
        pd_text(c, x, y, STAT_LABELS[i], PD_LABEL, PD_LABEL_SHADOW);
        if (up != down && s == up + 1) pd_triangle(c, x + labelW - 8, y + 5, 5, PD_UP, 0xE04040);
        if (up != down && s == down + 1) pd_triangle(c, x + labelW - 8, y + 6, 5, PD_DOWN, 0x3984F7);
        snprintf(buf, sizeof(buf), "%d", m->stats[s]);
        pd_text_right(c, x + labelW + statW, y, buf, PD_LABEL, PD_LABEL_SHADOW);
        int bx = x + labelW + statW + 5, bw = w - labelW - statW - 5 - evW - 16;
        uint32_t col = m->ivs[s] >= 31 ? 0xE8B830 : m->ivs[s] * 2 > 31 ? 0x60D048 : m->ivs[s] * 5 > 31 ? 0xF8C838 : 0xF04838;
        pd_round_rect(c, bx, y + 6, bw, 6, 2, 0x525252);
        pd_fill(c, bx + 1, y + 7, bw - 2, 4, 0x506858);
        pd_fill(c, bx + 1, y + 7, (bw - 2) * m->ivs[s] / 31, 4, col);
        snprintf(buf, sizeof(buf), "%d", m->ivs[s]);
        pd_text_right(c, x + w - evW, y, buf, PD_VALUE, PD_VALUE_SHADOW);
        snprintf(buf, sizeof(buf), "%d", m->evs[s]);
        pd_text_right(c, x + w, y, buf, PD_VALUE, PD_VALUE_SHADOW);
        totalIv += m->ivs[s];
        totalEv += m->evs[s];
        y += 15;
    }
    pd_text(c, x, y, "TOTAL", PD_LABEL, PD_LABEL_SHADOW);
    snprintf(buf, sizeof(buf), "%d", totalIv);
    pd_text_right(c, x + w - evW, y, buf, PD_VALUE, PD_VALUE_SHADOW);
    snprintf(buf, sizeof(buf), "%d", totalEv);
    pd_text_right(c, x + w, y, buf, PD_VALUE, PD_VALUE_SHADOW);
    y += 17;
    pd_text(c, x, y, "NATURE", PD_LABEL, PD_LABEL_SHADOW);
    pd_text(c, x + w * 46 / 100, y, NATURES[m->nature % 25], PD_VALUE, PD_VALUE_SHADOW);
}

static void summary_moves(struct pd_canvas* c, const struct pd_mon* m, int x, int y, int w, int h) {
    char buf[40];
    int rowH = h / 4;
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        int ry = y + i * rowH, move = m->moves[i];
        if (i) pd_fill(c, x, ry - 1, w, 1, PD_DIVIDER);
        if (!move) continue;
        int type = pd_move_type(move);
        pd_fill(c, x, ry + 2, 2, rowH - 5, ui_type_color(type));
        pd_text_fit(c, x + 5, ry + 1, w - 5, pd_move_name(move, buf, sizeof(buf)), PD_LABEL, PD_LABEL_SHADOW);
        int power = pd_move_power(move);
        char line[12];
        snprintf(line, sizeof(line), "%d", m->pp[i]);
        int px = x + w - pd_text_width(line);
        pd_text(c, px, ry + 18, line, PD_VALUE, PD_VALUE_SHADOW);
        px -= pd_text_width("PP") + 3;
        pd_text(c, px, ry + 18, "PP", PD_LABEL, PD_LABEL_SHADOW);
        if (power > 0) snprintf(line, sizeof(line), "%d", power);
        else snprintf(line, sizeof(line), "-");
        int vx = px - 6 - pd_text_width(line);
        pd_text(c, vx, ry + 18, line, PD_VALUE, PD_VALUE_SHADOW);
        pd_text_right(c, vx - 3, ry + 18, "PWR", PD_LABEL, PD_LABEL_SHADOW);
    }
}

// The summary (MonDetailScreen): one white window split by a rule - the Pokémon left, its moves (or STATS) right -
// beside a column of the party with Back under it.
// The summary of [m]; [column] is who the column on the right shows, [shown] the framed one. [pcLabel]: the PC's button.
void ui_mon_summary(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s, const struct pd_mon* m,
                    const struct pd_mon* const* column, int columnCount, int shown, bool canTake, const char* pcLabel) {
    (void) s;
    char buf[40];
    int x = MARGIN, y = CONTENT_Y - 2, h = c->h - y - 2;
    int colX = c->w - MARGIN - SUMMARY_COLUMN, w = colX - 3 - x;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    int ix = x + f + 4, iy = y + f + 2, iw = w - 2 * (f + 4), ih = h - 2 * (f + 2);
    int half = iw * 50 / 100, rx = ix + half + 7, rw = iw - half - 7;
    pd_fill(c, ix + half + 3, iy, 1, ih, PD_DIVIDER);

    bool egg = m->egg;
    int ty = iy;
    int nameW = pd_text_fit(c, ix, ty, half - 12, mon_display_name(m, buf, sizeof(buf)), PD_LABEL, PD_LABEL_SHADOW);
    if (!egg) gender_mark(c, ix + nameW + 2, ty, m->gender);
    ty += 16;
    pd_round_rect(c, ix, ty, PD_ICON + 2, PD_ICON + 2, 3, PD_LIST_FILL);
    pd_draw_mon_icon(c, g, m->species, ix + 1, ty + 1, !egg && m->hp == 0);
    if (egg) {
        pd_text_wrap(c, ix + PD_ICON + 6, ty, half - PD_ICON - 6, 15, 3, "It will hatch in time.", PD_MUTED, PD_MUTED_SHADOW);
    } else {
        int tx = ix + PD_ICON + 6;
        snprintf(buf, sizeof(buf), "Lv%d", m->level);
        int lw = pd_text(c, tx, ty, buf, PD_VALUE, PD_VALUE_SHADOW);
        const char* status = pd_status_label(m->status);
        if (*status) ui_status_badge(c, tx + lw + 4, ty + 1, m->status);
        int t1, t2;
        pd_species_type_pair(m->species, &t1, &t2);
        int cy = ty + 17;
        if (t1 != PD_TYPE_NONE) {
            int bw = ui_type_badge(c, tx, cy, t1);
            if (t2 != t1 && t2 != PD_TYPE_NONE) ui_type_badge(c, tx + bw + 2, cy, t2);
        }
        ty += PD_ICON + 3;
        char hp[16];
        snprintf(hp, sizeof(hp), "%d/%d", m->hp, m->maxHp);
        summary_bar(c, ix, ty, half, "HP", hp, m->hp, m->maxHp, m->hp * 2 > m->maxHp ? 0x60D048 : m->hp * 5 > m->maxHp ? 0xF8C838 : 0xF04838);
        ty += 15;
        int rate = pd_growth_rate(g, m->species);
        if (rate >= 0 && m->level < 100) {
            long at = pd_exp_at(rate, m->level), next = pd_exp_at(rate, m->level + 1);
            char left[20];
            snprintf(left, sizeof(left), "%ld", next - (long) m->exp);
            summary_bar(c, ix, ty, half, "EXP", left, (int) ((long) m->exp - at), (int) (next - at), EXP_BLUE);
            ty += 15;
        }
        if (m->heldItem) {
            int takeW = canTake ? 36 : 0;
            pd_text_fit(c, ix, ty, half - takeW, pd_item_name(g, m->heldItem), PD_LABEL, PD_LABEL_SHADOW);
            if (canTake) ui_button(ui, c, ix + half - takeW, ty + 1, takeW, 15, "TAKE", HIT_SUMMARY_TAKE);
            ty += 16;
        }
        pd_fill(c, ix, ty + 1, half, 1, PD_DIVIDER);
        ty += 4;
        int bottom = iy + ih;
        ty = chips_of(c, "WEAK", t1, t2, 150, 1000, ix, ty, half, bottom);
        ty = chips_of(c, "RESIST", t1, t2, 1, 75, ix, ty, half, bottom);
        chips_of(c, "IMMUNE", t1, t2, 0, 0, ix, ty, half, bottom);
    }
    if (!egg) {
        if (ui->summaryStats && m->hasStats) summary_stats(c, m, rx, iy, rw);
        else summary_moves(c, m, rx, iy, rw, ih);
    }

    // The party, the shown one framed red; Back under it, STATS over that.
    int cell = SUMMARY_COLUMN, btnH = 24;
    int rows = columnCount, room = h - btnH - 2 - (m->hasStats && !egg ? btnH + 2 : 0) - (pcLabel ? btnH + 2 : 0);
    if (rows * (cell + 1) > room) cell = room / rows - 1;
    for (int i = 0; i < columnCount; i++) {
        int cy = y + i * (cell + 1);
        bool here = i == shown;
        struct pd_layer redFrame[2] = { { PD_VALUE, 2 }, { PD_VALUE_SHADOW, 1 } };
        pd_layered_box(c, colX, cy, SUMMARY_COLUMN, cell, 3, here ? redFrame : pd_cur->title, here ? 2 : pd_cur->titleCount, PD_TITLE_FILL);
        pd_clip(c, colX + 2, cy + 2, SUMMARY_COLUMN - 4, cell - 4);
        pd_draw_mon_icon(c, g, column[i]->species, colX + 1, cy + (cell - PD_ICON) / 2, column[i]->hp == 0 && !column[i]->egg);
        pd_unclip(c);
        ui_add_hit(ui, colX, cy, SUMMARY_COLUMN, cell, HIT_SUMMARY_MON + i);
    }
    int by = y + h - btnH;
    ui_platinum(ui, c, colX, by, SUMMARY_COLUMN, btnH, 0x4890D8, 0x2860A0, HIT_BACK);
    ui_mono(c, &PD_ICON_BACK, colX + (SUMMARY_COLUMN - PD_ICON_BACK.w) / 2, by + (btnH - PD_ICON_BACK.h) / 2, 1, 0xFFFFFF);
    if (m->hasStats && !egg) {
        by -= btnH + 2;
        ui_platinum(ui, c, colX, by, SUMMARY_COLUMN, btnH, STATS_PURPLE, STATS_PURPLE_DARK, HIT_SUMMARY_STATS);
        const char* label = ui->summaryStats ? "MOVE" : "STAT";
        pd_text(c, colX + (SUMMARY_COLUMN - pd_text_width(label)) / 2, pd_text_y(by, btnH), label, 0xFFFFFF, 0x383838);
    }
    if (pcLabel) {
        by -= btnH + 2;
        ui_platinum(ui, c, colX, by, SUMMARY_COLUMN, btnH, 0x58A840, 0x307020, HIT_SUMMARY_PC);
        pd_text(c, colX + (SUMMARY_COLUMN - pd_text_width(pcLabel)) / 2, pd_text_y(by, btnH), pcLabel, 0xFFFFFF, 0x383838);
    }
}

// A BEST / OUT tag at the slot's top right (VerdictPill).
static void slot_tag(struct pd_canvas* c, int x, int y, int w, const char* text, uint32_t fill) {
    int tw = pd_text_width(text) + 6;
    pd_round_rect(c, x + w - tw - 4, y + 7, tw, 13, 2, fill);
    pd_text(c, x + w - tw - 1, y + 5, text, 0xFFFFFF, PD_NO_SHADOW);
}

void ui_party_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                  const struct pd_host_info* host) {
    if (ui->summarySlot >= 0) {
        const struct pd_mon* column[PD_PARTY_SIZE];
        for (int i = 0; i < s->partyCount; i++) column[i] = &s->party[i];
        ui_mon_summary(ui, c, g, s, &s->party[ui->summarySlot], column, s->partyCount, ui->summarySlot, host && host->canTake,
                       ui->boxMoves ? "PC" : NULL);
        if (*ui->boxMessage) ui_box_message(ui, c);
        return;
    }
    ui_party_grid(ui, c, g, s, -1, 0);
    if (*ui->boxMessage) ui_box_message(ui, c);
}

void ui_item_grid(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    int x0 = MARGIN, y0 = CONTENT_Y - 2, w = c->w - 2 * MARGIN, h = c->h - y0 - 1;
    int rows = 3;
    static uint32_t before[PD_UI_WIDTH * PD_UI_HEIGHT];
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        int col = i % 2, row = i / 2;
        int x = x0 + w * col / 2, y = y0 + h * row / rows;
        int cw = x0 + w * (col + 1) / 2 - x, ch = y0 + h * (row + 1) / rows - y;
        const struct pd_mon* m = i < s->partyCount ? &s->party[i] : NULL;
        bool ok = m && ui->itemSlots[i].ok;
        if (m && !ok)
            for (int yy = 0; yy < ch; yy++)
                memcpy(before + yy * cw, c->px + (y + yy) * c->w + x, (size_t) cw * 4);
        party_slot(ui, c, g, x, y, cw, ch, m, -1, false);
        if (m) {
            ui->hitCount--; // party_slot's own, for its summary
            if (ok) ui_add_hit(ui, x, y, cw, ch, HIT_ITEM_SLOT + i);
            else ui_fade_rect(c, before, x, y, cw, ch, 110);
        }
    }
}

void ui_party_grid(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s, int best, int out) {
    // PartyGrid: edge to edge (each slot carries its own Poke Ball overhang), two
    // columns, three on a screen wider than 2:1. The selected look follows the
    // pressed or D-pad slot, else the first, like the game's cursor.
    int x0 = MARGIN, y0 = CONTENT_Y - 2, w = c->w - 2 * MARGIN, h = c->h - y0 - MARGIN;
    int cols = w > 2 * h ? 3 : 2, rows = (PD_PARTY_SIZE + cols - 1) / cols;
    int selected = best;
    if (selected < 0)
        for (int i = 0; i < s->partyCount; i++)
            if (out >> i & 1) {
                selected = i;
                break;
            }
    if (selected < 0) selected = 0;
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        if (ui->focusId == HIT_SLOT + i || ui->pressedId == HIT_SLOT + i) selected = i;
    }
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        int col = i % cols, row = i / cols;
        int x = x0 + w * col / cols, y = y0 + h * row / rows;
        int cw = x0 + w * (col + 1) / cols - x, ch = y0 + h * (row + 1) / rows - y;
        const struct pd_mon* m = i < s->partyCount ? &s->party[i] : NULL;
        party_slot(ui, c, g, x, y, cw, ch, m, i, m && i == selected);
        if (m && i == best) slot_tag(c, x, y, cw, "BEST", 0xE8B830);
        else if (m && out >> i & 1) slot_tag(c, x, y, cw, "OUT", PD_MUTED);
    }
}
