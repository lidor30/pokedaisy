// PARTY: the six slots, each with the game's own icon (from the ROM, pd_icon), and a summary on tap. A game with its own
// art (pd_party_art) gets the game's slot, drawn like GbaPartySlot; every other one a slot in its PartyPalette.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_games.h"
#include "pd_icon.h"
#include "pd_party_art.h"
#include "pd_dex.h"
#include "pd_gb.h"
#include "pd_tables.h"
#include "pd_experience.h"
#include "pd_ui_internal.h"

static bool party_egg(const struct pd_ui* ui, const struct pd_mon* m) {
    return ui && ui->partySource && ui->partySource->egg ? ui->partySource->egg(ui->partySource->ctx, m) : m->egg || m->species == PD_SPECIES_EGG;
}
static const char* party_species(const struct pd_ui* ui, const struct pd_game* g, int id, char* buf, size_t n) {
    return ui && ui->partySource && ui->partySource->species ? ui->partySource->species(ui->partySource->ctx, id) : pd_species_name(g, id, buf, n);
}
// A backend's own icon (libretro's DS companion), else the ROM's.
static bool party_icon(const struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_mon* m, int x, int y,
                       int scale, bool faded) {
    return ui && ui->partySource && ui->partySource->icon ? ui->partySource->icon(ui->partySource->ctx, c, m, x, y, scale, faded)
        : pd_draw_mon_icon_shiny(c, g, m->species, m->shiny, x, y, scale, faded);
}
#define SHINY_MARK 0xF8C830 // a shiny mon's gold asterisk

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

static const char* mon_display_name(const struct pd_ui* ui, const struct pd_game* g, const struct pd_mon* m, char* buf, size_t len) {
    if (party_egg(ui, m)) return "EGG";
    if (m->nickname[0]) return m->nickname;
    return party_species(ui, g, m->species, buf, len);
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
    g.hpTextMid = g.barY + 10 * u + 1; // the numbers' tops clear of the bar
    return g;
}

static int cut_inset(int row, int height) {
    int edge = 9 - row, lower = 9 - (height - row - 1);
    if (lower > edge) edge = lower;
    return edge > 0 ? edge : 0;
}

static void chamfer(struct pd_canvas* c, int x, int y, int w, int h, int u, uint32_t color) {
    for (int row = 0; row < h; ++row) {
        int edge = cut_inset(row, h);
        pd_fill(c, x + edge * u, y + row * u, (w - 2 * edge) * u, u, color);
    }
}

static void slot_frame(struct pd_canvas* c, int x, int y, const struct slot_geometry* g, const struct pd_party_slot_colors* col, bool angled) {
    int u = g->u;
    if (angled) {
        int left = x + g->fx, top = y + g->fy, w = g->fw / u, h = g->fh / u;
        chamfer(c, left, top, w, h, u, col->outline);
        chamfer(c, left + u, top + u, w - 2, h - 2, u, col->light);
        chamfer(c, left + 2 * u, top + 2 * u, w - 4, h - 4, u, col->shade);
        chamfer(c, left + 3 * u, top + 3 * u, w - 6, h - 6, u, col->fill);
        if (col->stripe) {
            int innerW = w - 6, innerH = h - 6;
            for (int row = 0; row < innerH; ++row) {
                int edge = cut_inset(row, innerH);
                int from = innerW * 62 / 100 - row, to = from + innerW * 46 / 100;
                if (from < edge) from = edge;
                if (to > innerW - edge) to = innerW - edge;
                if (from < to)
                    pd_fill(c, left + (3 + from) * u, top + (3 + row) * u, (to - from) * u, u, col->stripe);
            }
        }
        return;
    }
    pd_round_rect(c, x + g->fx, y + g->fy, g->fw, g->fh, 3 * u, col->outline);
    pd_round_rect(c, x + g->fx + 2 * u, y + g->fy + 2 * u, g->fw - 4 * u, g->fh - 4 * u, u, col->light);
    pd_fill(c, x + g->fx + 3 * u, y + g->fy + 3 * u, g->fw - 6 * u, g->dividerY - g->fy - 3 * u, col->fill);
    if (col->band != col->light) pd_fill(c, x + g->fx + 3 * u, y + g->dividerY, g->fw - 6 * u, g->bottom - 3 * u - g->dividerY, col->band);
    pd_fill(c, x + g->fx + 2 * u, y + g->bottom - 3 * u, g->fw - 4 * u, u, col->shade);
}

static void hp_bar(struct pd_canvas* c, int x, int y, const struct slot_geometry* g, int hp, int maxHp, const struct pd_party_source_palette* p) {
    int u = g->u, by = y + g->barY;
    // The "HP" label box, sharing its right edge with the bar's outline.
    pd_round_rect(c, x + g->labelX, by, g->barX - g->labelX + u, 7 * u, u, p ? p->hpFrame : HP_FRAME);
    for (int r = 0; r < 5; r++) {
        for (int col = 0; HP_LETTERS[r][col]; col++) {
            if (HP_LETTERS[r][col] == '1')
                pd_fill(c, x + g->labelX + (1 + col) * u, by + (1 + r) * u, u, u, r < 3 ? (p ? p->hpLabel : HP_LETTER) : (p ? p->hpLabelShade : HP_LETTER_SHADE));
        }
    }
    int w = g->barRight - g->barX;
    pd_round_rect(c, x + g->barX, by, w, 7 * u, 2 * u, p ? p->hpFrame : HP_FRAME);
    pd_round_rect(c, x + g->barX + u, by + u, w - 2 * u, 5 * u, u, p ? p->hpInner : HP_INNER);
    int inner = w - 4 * u;
    if (hp > maxHp) hp = maxHp;
    if (hp < 0) hp = 0;
    int filled = maxHp > 0 ? inner / u * hp / maxHp * u : 0;
    if (filled < u && hp > 0) filled = u;
    const struct pd_hp_band* col = maxHp <= 0 || hp * 2 > maxHp ? (p ? &p->hpGreen : &HP_GREEN)
        : hp * 5 > maxHp ? (p ? &p->hpYellow : &HP_YELLOW) : (p ? &p->hpRed : &HP_RED);
    const struct pd_hp_band* empty = p ? &p->hpEmpty : &HP_NONE;
    int bx = x + g->barX + 2 * u;
    pd_fill(c, bx, by + 2 * u, inner, u, empty->top);
    pd_fill(c, bx, by + 3 * u, inner, 2 * u, empty->main);
    pd_fill(c, bx, by + 2 * u, filled, u, col->top);
    pd_fill(c, bx, by + 3 * u, filled, 2 * u, col->main);
}

// The status chip (StatusChip): a darker rim around the status colour, FNT grey.
uint32_t pd_ui_status_color(const char* label) {
    return !strcmp(label, "FNT") ? 0x6B6B6B : !strcmp(label, "SLP") ? 0x8A8A8A
        : !strcmp(label, "PSN") ? 0xA259D9 : !strcmp(label, "BRN") ? 0xE0693E : !strcmp(label, "PAR") ? 0xD9C341
        : !strcmp(label, "FRZ") ? 0x56C2E6 : !strcmp(label, "TOX") ? 0x7A3EA1 : 0x8890A0;
}

static void status_chip(struct pd_canvas* c, int x, int y, const char* label) {
    uint32_t color = pd_ui_status_color(label);
    uint32_t rim = ((((color >> 16) & 0xFF) * 65 / 100) << 16) | ((((color >> 8) & 0xFF) * 65 / 100) << 8)
        | ((color & 0xFF) * 65 / 100);
    uint32_t shadow = ((((color >> 16) & 0xFF) / 2) << 16) | ((((color >> 8) & 0xFF) / 2) << 8) | ((color & 0xFF) / 2);
    int w = pd_text_width(label) + 8;
    pd_round_rect(c, x, y, w, 14, 2, rim);
    pd_round_rect(c, x + 1, y + 1, w - 2, 12, 1, color);
    pd_text(c, x + 4, y - 1, label, 0xFFFFFF, shadow);
}

// An empty slot: its box, faint over the backdrop (drawn whole, then blended in).
static void faded_slot(struct pd_canvas* c, int x, int y, int w, int h, const struct slot_geometry* g,
                       const struct pd_party_slot_colors* colors, int alpha) {
    static uint32_t scratch[512 * 256];
    if (w <= 0 || h <= 0 || w * h > (int) (sizeof(scratch) / sizeof(*scratch))) return;
    struct pd_canvas t;
    pd_canvas_init(&t, w, h, scratch);
    memset(scratch, 0xFF, (size_t) w * h * sizeof(*scratch)); // 0xFFFFFFFF: nothing drawn
    slot_frame(&t, 0, 0, g, colors, false);
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
                out |= ((a * alpha + b * (255 - alpha)) / 255) << sh;
            }
            *d = out;
        }
    }
}

// --- the game's own slot (GbaPartySlot.kt) ---

#define GLYPH_H 13 // PartySlotLayout: DecompressGlyph_Small's height
#define TEXT_TOP 4 // the first row of a FONT_SMALL cell capitals are drawn in
#define ICON_BOUNCE_DOWN 1
#define NUM_PAD 1  // stands for the game's right-align pad character in a number
#define LV_MARK 2  // the "Lv" glyph
#define MAX_WINDOW_SIDE 1024

// Where a slot goes (GbaPartySlot's Canvas): the window at (ox, oy) in `scale` canvas pixels a game pixel, grown by the
// stretch columns and rows to fill the cell.
struct art_geometry {
    const struct pd_party_style* st;
    int scale, ox, oy, left, mid, top, midV, bottom, w, h;
};

static struct art_geometry art_geometry(const struct pd_party_style* st, int w, int h) {
    struct art_geometry a = { .st = st };
    int minW = st->visibleW - st->regionX0, minH = st->visibleH - st->regionY0;
    int scale = w / minW < h / minH ? w / minW : h / minH;
    a.scale = scale < 1 ? 1 : scale;
    int regionW = w / a.scale, regionH = st->stretchRows ? h / a.scale : minH;
    int extraW = regionW > minW ? regionW - minW : 0, extraH = regionH > minH ? regionH - minH : 0;
    if (st->hasBand) extraH &= ~1; // a band frame's empty slot grows by whole stripe pairs
    a.ox = (w - regionW * a.scale) / 2 - st->regionX0 * a.scale;
    a.oy = (h - regionH * a.scale) / 2 - st->regionY0 * a.scale;
    a.left = extraW / 3;
    a.mid = extraW - extraW / 3;
    // The extra height goes between the top padding, the gap between level and HP box, and the bottom padding.
    a.top = extraH / 3;
    a.midV = (extraH - a.top) / 2;
    a.bottom = extraH - a.top - a.midV;
    a.w = st->slotW + extraW;
    a.h = st->slotH + extraH;
    return a;
}

// Px.left / right / end / y / textY: a game coordinate past the stretch points.
static int art_left(const struct art_geometry* a, int x) {
    return x > a->st->leftCol ? x + a->left : x;
}

static int art_right(const struct art_geometry* a, int x) {
    return art_left(a, x) + (x > a->st->midCol ? a->mid : 0);
}

static int art_end(const struct art_geometry* a, int x) {
    return x + a->left + a->mid;
}

static int art_y(const struct art_geometry* a, int v) {
    return v + (v > a->st->topRow ? a->top : 0) + (v > a->st->midRow ? a->midV : 0) + (v > a->st->bottomRow ? a->bottom : 0);
}

static int art_text_y(const struct art_geometry* a, int v) {
    return art_y(a, v + TEXT_TOP) - TEXT_TOP;
}

// The window as it stands: frame, text and HP bar, at one game pixel a pixel. As big as a cell has needed.
static uint32_t* window;
static int windowSize;

// The frame, grown by repeating its uniform columns and rows; a band frame gets its diagonals redrawn (buildBandFrame).
static bool art_frame(const struct pd_party_art* art, enum pd_slot_frame frame, const struct art_geometry* a) {
    static short cols[MAX_WINDOW_SIDE], rows[MAX_WINDOW_SIDE];
    const struct pd_party_style* st = a->st;
    const uint32_t* px = art->frames[frame];
    int w = st->slotW, h = st->slotH;
    if (a->w > MAX_WINDOW_SIDE || a->h > MAX_WINDOW_SIDE) return false;
    if (a->w * a->h > windowSize) {
        uint32_t* grown = realloc(window, (size_t) a->w * a->h * sizeof(*grown));
        if (!grown) return false;
        window = grown;
        windowSize = a->w * a->h;
    }
    bool empty = frame == PD_SLOT_EMPTY;
    int ow = 0, oh = 0, k = a->top + a->midV + a->bottom;
    for (int x = 0; x < w; x++) {
        cols[ow++] = (short) x;
        if (x == st->leftCol) for (int i = 0; i < a->left; i++) cols[ow++] = (short) x;
        if (x == st->midCol) for (int i = 0; i < a->mid; i++) cols[ow++] = (short) x;
    }
    for (int y = 0; y < h; y++) {
        rows[oh++] = (short) y;
        if (empty && st->hasBand) {
            if (y == st->bandEmptyStripeRow + 1)
                for (int i = 0; i < k / 2; i++) {
                    rows[oh++] = st->bandEmptyStripeRow;
                    rows[oh++] = (short) (st->bandEmptyStripeRow + 1);
                }
        } else {
            if (y == st->topRow) for (int i = 0; i < a->top; i++) rows[oh++] = (short) y;
            if (y == st->midRow) for (int i = 0; i < a->midV; i++) rows[oh++] = (short) y;
            if (y == st->bottomRow) for (int i = 0; i < a->bottom; i++) rows[oh++] = (short) y;
        }
    }
    for (int j = 0; j < oh; j++)
        for (int i = 0; i < ow; i++) window[j * ow + i] = px[rows[j] * w + cols[i]];
    if (!st->hasBand || empty) return true;
    uint32_t light = px[st->bandTop * w + st->bandLeftTopX - 1], edge = px[st->bandTop * w + st->bandLeftTopX],
             dark = px[st->bandTop * w + st->bandLeftTopX + 1];
    int lt = art_right(a, st->bandLeftTopX), lb = art_right(a, st->bandLeftBottomX);
    int rt = art_right(a, st->bandRightTopX), rb = art_right(a, st->bandRightBottomX);
    int span = st->bandBottom + k - st->bandTop;
    for (int j = st->bandTop; j <= st->bandBottom + k; j++) {
        // Rounded like floor(a + (b - a) * t + 0.5), t = (j - top) / span.
        int t = j - st->bandTop;
        int xl = (2 * (lt * span + (lb - lt) * t) + span) / (2 * span), xr = (2 * (rt * span + (rb - rt) * t) + span) / (2 * span);
        for (int i = 0; i < ow; i++) {
            uint32_t* p = &window[j * ow + i];
            if (*p != light && *p != edge && *p != dark) continue;
            *p = i == xl || i == xr ? edge : i < xl || i > xr ? light : dark;
        }
    }
    return true;
}

// The Western letters past ASCII in Gen 3's charmap, from 0x01 (Gen3Text); a space = none. encodeSmallFont's WESTERN_CODES.
static const char* const WESTERN[] = { "À", "Á", "Â", "Ç", "È", "É", "Ê", "Ë", "Ì", " ", "Î", "Ï", "Ò", "Ó", "Ô", "Œ", "Ù", "Ú", "Û", "Ñ", "ß", "à", "á", " ",
                                       "ç", "è", "é", "ê", "ë", "ì", " ", "î", "ï", "ò", "ó", "ô", "œ", "ù", "ú", "û", "ñ", "º", "ª" };
static const struct { const char* c; int code; } OTHER_CODES[] = {
    { "é", 0x1B }, { "É", 0x1B }, { "’", 0xB4 }, { "♂", 0xB5 }, { "♀", 0xB6 }, { "Ä", 0xF1 }, { "Ö", 0xF2 }, { "Ü", 0xF3 }, { "ä", 0xF4 },
    { "ö", 0xF5 }, { "ü", 0xF6 }, { "¿", 0x51 }, { "¡", 0x52 }, { "Í", 0x5A }, { "â", 0x68 }, { "í", 0x6F },
};

// The next character's Gen 3 code for FONT_SMALL (encodeSmallFont); '?' for anything the font doesn't have.
static int small_font_code(const struct pd_party_style* st, const char** s) {
    unsigned char c = (unsigned char) **s;
    if (c < 0x80) {
        (*s)++;
        switch (c) {
        case NUM_PAD: return st->padGlyph;
        case LV_MARK: return st->lvGlyph;
        case ' ': return 0x00;
        case '/': return 0xBA;
        case '.': return 0xAD;
        case '-': return 0xAE;
        case '!': return 0xAB;
        case '?': return 0xAC;
        case '\'': return 0xB4;
        case ':': return 0xF0;
        case '&': return 0x2D;
        case '+': return 0x2E;
        case '%': return 0x5B;
        case '(': return 0x5C;
        case ')': return 0x5D;
        case ',': return 0xB8;
        default: break;
        }
        return c >= 'A' && c <= 'Z' ? 0xBB + c - 'A' : c >= 'a' && c <= 'z' ? 0xD5 + c - 'a' : c >= '0' && c <= '9' ? 0xA1 + c - '0' : 0xAC;
    }
    int len = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : 2;
    const char* at = *s;
    for (int i = 1; i < len && at[i]; i++) (*s)++;
    (*s)++;
    for (size_t i = 0; i < sizeof(OTHER_CODES) / sizeof(*OTHER_CODES); i++)
        if (!strncmp(at, OTHER_CODES[i].c, (size_t) len)) return OTHER_CODES[i].code;
    for (size_t i = 0; i < sizeof(WESTERN) / sizeof(*WESTERN); i++)
        if (!strncmp(at, WESTERN[i], (size_t) len)) return (int) i + 1;
    return 0xAC;
}

// A card shown scaled keeps its text out of the scaling, which would blur it: art_text notes it down here and card_texts
// draws it on the canvas afterwards, at whole pixels.
struct card_text {
    char text[24];
    short x, y;
    bool right;
    uint32_t fg, shadow;
};
static struct card_text cardTexts[PD_PARTY_SIZE * 6];
static int cardTextCount, cardX, cardY;
static bool cardTextsOn;

static void card_text_add(const char* s, int x, int y, bool right, uint32_t fg, uint32_t shadow) {
    if (cardTextCount >= (int) (sizeof(cardTexts) / sizeof(*cardTexts))) return;
    struct card_text* t = &cardTexts[cardTextCount++];
    char* o = t->text;
    for (; *s && o < t->text + sizeof(t->text) - 3; s++) {
        if (*s == NUM_PAD) continue;
        if (*s == LV_MARK) {
            *o++ = 'L';
            *o++ = 'v';
            continue;
        }
        *o++ = *s;
    }
    *o = 0;
    t->x = (short) (cardX + x);
    t->y = (short) (cardY + y);
    t->right = right;
    t->fg = fg;
    t->shadow = shadow;
}

// Px.text, into the window.
static void art_text(const struct pd_party_art* art, const struct art_geometry* a, const char* s, int x, int y, uint32_t fg, uint32_t shadow) {
    if (cardTextsOn) {
        card_text_add(s, x, y, false, fg, shadow);
        return;
    }
    while (*s) {
        int g = small_font_code(art->style, &s);
        for (int yy = 0; yy < GLYPH_H; yy++) {
            int wy = y + yy, row = pd_party_glyph_row(art, g, yy);
            if (wy < 0 || wy >= a->h || !row) continue;
            for (int xx = 0; xx < 8; xx++) {
                int wx = x + xx, p = row >> (14 - 2 * xx) & 3;
                if ((p == 1 || p == 2) && wx >= 0 && wx < a->w) window[wy * a->w + wx] = PD_ART_OPAQUE | (p == 1 ? fg : shadow);
            }
        }
        x += art->style->glyphWidths[g];
    }
}

static void art_rect(const struct art_geometry* a, int x, int y, int w, int h, uint32_t color) {
    for (int j = y; j < y + h && j < a->h; j++)
        for (int i = x; i < x + w && i < a->w; i++) window[j * a->w + i] = PD_ART_OPAQUE | color;
}

// GetScaledHPFraction: floor(hp * scale / max), but never 0 while alive.
static int scaled_hp(int hp, int maxHp, int scale) {
    if (maxHp <= 0) return 0;
    int r = hp * scale / maxHp;
    return !r && hp > 0 ? 1 : r;
}

// DisplayPartyPokemonHPBar: a highlight row over a 2 px body, the rest in the window's colours. The colour goes by the
// game's own 48 px scale (GetHPBarLevel), the fill spans the stretched bar.
static void art_hp_bar(const struct art_geometry* a, int hp, int maxHp) {
    const struct pd_party_style* st = a->st;
    int level = scaled_hp(hp, maxHp, st->barW);
    uint32_t top = st->hpGreenTop, body = st->hpGreen;
    if (hp != maxHp && level <= st->barW * 50 / 100) {
        bool yellow = level > st->barW * 20 / 100;
        top = yellow ? st->hpYellowTop : st->hpRedTop;
        body = yellow ? st->hpYellow : st->hpRed;
    }
    int x = art_left(a, st->barX), y = art_y(a, st->barY), w = art_right(a, st->barX + st->barW - 1) - x + 1;
    int f = scaled_hp(hp, maxHp, w);
    art_rect(a, x, y, f, 1, top);
    art_rect(a, x, y + 1, f, 2, body);
    art_rect(a, x + f, y, w - f, 1, st->hpEmptyTop);
    art_rect(a, x + f, y + 1, w - f, 2, st->hpEmpty);
}

// Puts the window on the canvas. A game that blends its slots over the backdrop shows box + backdrop / 2; alpha < 255
// lays it over faintly (an empty slot where the game draws none).
static void art_window(struct pd_canvas* c, const struct art_geometry* a, int x, int y, int alpha) {
    int s = a->scale;
    bool blend = a->st->blendOverBackdrop;
    for (int yy = 0; yy < a->h * s; yy++) {
        int sy = y + a->oy + yy;
        if (sy < c->cy0 || sy >= c->cy1) continue;
        const uint32_t* row = window + yy / s * a->w;
        for (int xx = 0; xx < a->w * s; xx++) {
            int sx = x + a->ox + xx;
            uint32_t v = row[xx / s];
            if (!v || sx < c->cx0 || sx >= c->cx1) continue;
            uint32_t* d = &c->px[sy * c->w + sx];
            if (alpha != 255) {
                uint32_t out = 0;
                for (int sh = 0; sh <= 16; sh += 8)
                    out |= (((v >> sh & 0xFF) * (uint32_t) alpha + (*d >> sh & 0xFF) * (255 - (uint32_t) alpha)) / 255) << sh;
                *d = out;
            } else if (blend) {
                uint32_t half = *d >> 1 & 0x7F7F7F, r = (v >> 16 & 0xFF) + (half >> 16), gr = (v >> 8 & 0xFF) + (half >> 8 & 0xFF), b = (v & 0xFF) + (half & 0xFF);
                *d = (r > 255 ? 255 : r) << 16 | (gr > 255 ? 255 : gr) << 8 | (b > 255 ? 255 : b);
            } else {
                *d = v & 0xFFFFFF;
            }
        }
    }
}

// Px.image: part of a sprite sheet at window coordinates.
static void art_image(struct pd_canvas* c, const struct art_geometry* a, int x, int y, const uint32_t* img, int imgW, int srcX, int srcY,
                      int gx, int gy, int w, int h) {
    int s = a->scale;
    for (int yy = 0; yy < h * s; yy++) {
        int sy = y + a->oy + gy * s + yy;
        if (sy < c->cy0 || sy >= c->cy1) continue;
        for (int xx = 0; xx < w * s; xx++) {
            int sx = x + a->ox + gx * s + xx;
            uint32_t v = img[(srcY + yy / s) * imgW + srcX + xx / s];
            if (v && sx >= c->cx0 && sx < c->cx1) c->px[sy * c->w + sx] = v & 0xFFFFFF;
        }
    }
}

// Column in the status icons: PSN, PAR, SLP, FRZ, BRN, PKRS, FNT; -1 = none.
static int status_icon(const struct pd_mon* m) {
    if (m->hp == 0) return 6;
    const char* s = pd_status_label(m->status);
    return !strcmp(s, "PSN") || !strcmp(s, "TOX") ? 0 : !strcmp(s, "PAR") ? 1 : !strcmp(s, "SLP") ? 2 : !strcmp(s, "FRZ") ? 3
        : !strcmp(s, "BRN") ? 4 : -1;
}

// The Poke Ball (open while selected), then the icon above it, then the held-item marker over the icon. The icon stands
// on the first of the game's two bounces while selected, and rests apart while not.
static void art_sprites(struct pd_canvas* c, const struct art_geometry* a, const struct pd_party_art* art, const struct pd_game* g, int x, int y,
                        const struct pd_mon* m, bool selected) {
    const struct pd_party_style* st = a->st;
    if (art->ball) art_image(c, a, x, y, art->ball, 32, 0, selected ? 32 : 0, st->ballX, st->ballY + (st->ballFollowsIcon ? a->top : 0), 32, 32);
    int dx = selected ? 0 : st->iconRestDx;
    int dy = !selected ? st->iconRestDy : !m->egg && m->hp == 0 ? 0 : ICON_BOUNCE_DOWN;
    pd_draw_mon_icon_scaled(c, g, m->species, x + a->ox + (st->iconX + dx) * a->scale, y + a->oy + (st->iconY + dy + a->top) * a->scale,
                            a->scale, false);
    if (!m->heldItem) return;
    int hx = st->iconX + 16, hy = st->iconY + 22 + a->top;
    if (art->hold) art_image(c, a, x, y, art->hold, 8, 0, m->heldItem >= 121 && m->heldItem <= 132 ? 8 : 0, hx, hy, 8, 8);
    else held_mark(c, x + a->ox + hx * a->scale, y + a->oy + hy * a->scale);
}

static void art_slot_at(struct pd_canvas* c, const struct pd_game* g, const struct pd_party_art* art, const struct art_geometry* a, int x, int y,
                        const struct pd_mon* m, bool selected) {
    const struct pd_party_style* st = a->st;
    if (!m) {
        if (art_frame(art, st->hasEmptySlot ? PD_SLOT_EMPTY : PD_SLOT_NOHP, a)) art_window(c, a, x, y, st->hasEmptySlot ? 255 : 89);
        return;
    }
    if (m->egg) {
        // DisplayPartyPokemonData's egg branch: the no-HP box and the nickname only.
        if (!art_frame(art, selected ? PD_SLOT_NOHP_SELECTED : PD_SLOT_NOHP, a)) return;
        art_text(art, a, st->eggName, art_left(a, st->nameX), art_text_y(a, st->nameY), st->text, st->textShadow);
        art_window(c, a, x, y, 255);
        art_sprites(c, a, art, g, x, y, m, selected);
        return;
    }
    bool fainted = m->hp == 0;
    if (!art_frame(art, selected && fainted ? PD_SLOT_SELECTED_FAINTED : selected ? PD_SLOT_SELECTED : fainted ? PD_SLOT_FAINTED : PD_SLOT_NORMAL, a))
        return;
    char buf[40], line[16];
    // The nickname as the game prints it; without one, the species.
    const char* name = mon_display_name(NULL, g, m, buf, sizeof(buf));
    if (!m->nickname[0] && st->upperCaseNames) {
        if (name != buf) snprintf(buf, sizeof(buf), "%s", name);
        for (char* p = buf; *p; p++) if (*p >= 'a' && *p <= 'z') *p = (char) (*p - 32);
        name = buf;
    }
    art_text(art, a, name, art_left(a, st->nameX), art_text_y(a, st->nameY), st->text, st->textShadow);
    // Drawn whenever known, even beside a status icon, like the game.
    if (m->gender == PD_GENDER_MALE) art_text(art, a, "♂", art_right(a, st->genderX), art_text_y(a, st->genderY), st->male, st->maleShadow);
    if (m->gender == PD_GENDER_FEMALE) art_text(art, a, "♀", art_right(a, st->genderX), art_text_y(a, st->genderY), st->female, st->femaleShadow);
    int status = status_icon(m);
    if (status < 0) {
        snprintf(line, sizeof(line), "%c%d", LV_MARK, m->level);
        art_text(art, a, line, art_left(a, st->levelX), art_text_y(a, st->levelY), st->text, st->textShadow);
    }
    // "hp/" right-aligned in 3 digits and "/max": both move with the stretched bar's right end, they share the slash.
    if (cardTextsOn) {
        snprintf(line, sizeof(line), "%d/%d", m->hp, m->maxHp);
        // Ending where the game's "/max" ends: its slash and three digits in FONT_SMALL's widths.
        int end = art_end(a, st->maxHpX) + st->glyphWidths[0xBA] + 3 * st->glyphWidths[0xA1];
        card_text_add(line, end, art_text_y(a, st->hpY), true, st->text, st->textShadow);
    } else {
        snprintf(line, sizeof(line), "%3d/", m->hp);
        for (char* p = line; *p == ' '; p++) *p = NUM_PAD;
        art_text(art, a, line, art_end(a, st->hpX), art_text_y(a, st->hpY), st->text, st->textShadow);
        snprintf(line, sizeof(line), "/%3d", m->maxHp);
        for (char* p = line + 1; *p == ' '; p++) *p = NUM_PAD;
        art_text(art, a, line, art_end(a, st->maxHpX), art_text_y(a, st->hpY), st->text, st->textShadow);
    }
    art_hp_bar(a, m->hp, m->maxHp);
    art_window(c, a, x, y, 255);
    if (status >= 0) art_image(c, a, x, y, art->status, art->statusW, status * 32, 0, art_left(a, st->statusX), art_y(a, st->statusY), 32, 8);
    art_sprites(c, a, art, g, x, y, m, selected);
}

static void art_slot(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_party_art* art, int x, int y, int w,
                     int h, const struct pd_mon* m, int slot, bool selected) {
    struct art_geometry geo = art_geometry(art->style, w, h);
    if (m) ui_add_hit(ui, x, y, w, h, HIT_SLOT + slot);
    art_slot_at(c, g, art, &geo, x, y, m, selected);
}

// --- the game's cards in a grid ---
//
// The grid is ours (two columns, three rows, the whole width like the app's), each card the game's: its own height and
// paddings, widened by its stretch columns only. Drawn at the GBA's size and shown as big as three rows allow with a gap
// between them: 4/3 or 5/4, sharp bilinear like the game on the top screen, else 1x (the battle's pane, under its bar, for
// FireRed's tall card). Any other canvas keeps the slots stretched both ways.

#define CARDS_CANVAS_W 320
#define CARD_GAP 3
#define CARD_TEXT_DY (-4) // from where the game's capitals start to Pixel Operator's line

struct window_at { short x, y; };
struct card_scale { int num, den, extraW; };
static const struct card_scale CARD_SCALES[] = { { 4, 3, 0 }, { 5, 4, 0 }, { 1, 1, 0 } };

// The cards' windows at the size they're drawn at; false: the stretched grid.
static bool card_grid(const struct pd_party_style* st, const struct pd_canvas* c, struct window_at at[PD_PARTY_SIZE], struct card_scale* scale) {
    if (c->w != CARDS_CANVAS_W) return false;
    int overhang = st->regionY0 < 0 ? -st->regionY0 : 0, rows = 0, width = 0;
    bool fits = false;
    for (size_t i = 0; i < sizeof(CARD_SCALES) / sizeof(*CARD_SCALES) && !fits; i++) {
        *scale = CARD_SCALES[i];
        rows = c->h * scale->den / scale->num;
        width = c->w * scale->den / scale->num;
        fits = overhang + 3 * st->visibleH + 2 * CARD_GAP <= rows;
    }
    if (!fits) return false;
    int pitch = (rows - overhang - st->visibleH) / 2;
    if (pitch > st->visibleH + 6) pitch = st->visibleH + 6;
    int top = overhang + (rows - overhang - 2 * pitch - st->visibleH) / 2;
    // The right column's Poke Ball hangs into the gap, the left one's into the margin.
    int ball = st->regionX0 < 0 ? -st->regionX0 : 0, edge = 2, left = edge + ball, gap = edge + ball;
    int cardW = (width - left - edge - gap) / 2;
    scale->extraW = cardW > st->visibleW ? cardW - st->visibleW : 0;
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        at[i].x = (short) (left + i % 2 * (st->visibleW + scale->extraW + gap));
        at[i].y = (short) (top + i / 2 * pitch);
    }
    return true;
}

// a * wa + b * wb over wa + wb, each channel of 0xRRGGBB.
static uint32_t mix(uint32_t a, uint32_t b, int wa, int wb) {
    uint32_t out = 0;
    for (int s = 0; s <= 16; s += 8) out |= (((a >> s & 0xFF) * (uint32_t) wa + (b >> s & 0xFF) * (uint32_t) wb) / (uint32_t) (wa + wb)) << s;
    return out;
}

// Output pixel o of a num / den enlargement covers [o * den, o * den + den) in num-ths of a source pixel: one source pixel,
// or the end of one and the start of the next.
static int source_of(int o, const struct card_scale* k, int* near) {
    int at = o * k->den, i = at / k->num, end = (i + 1) * k->num;
    *near = end - at >= k->den ? k->den : end - at;
    return i;
}

static void widen_row(const uint32_t* src, int srcW, uint32_t* dst, int w, const struct card_scale* k) {
    for (int x = 0; x < w; x++) {
        int near, i = source_of(x, k, &near);
        dst[x] = near == k->den || i + 1 >= srcW ? src[i] : mix(src[i], src[i + 1], near, k->den - near);
    }
}

static void screen_up(struct pd_canvas* c, const uint32_t* px, int srcW, int srcH, const struct card_scale* k) {
    static uint32_t r[2][CARDS_CANVAS_W];
    int have[2] = { -1, -1 };
    for (int y = 0; y < c->h; y++) {
        int near, s = source_of(y, k, &near);
        if (s >= srcH) break;
        for (int i = 0; i < 2; i++) {
            int want = s + i < srcH ? s + i : s;
            if (have[want & 1] != want) {
                widen_row(px + want * srcW, srcW, r[want & 1], c->w, k);
                have[want & 1] = want;
            }
        }
        const uint32_t* a = r[s & 1];
        const uint32_t* b = r[(s + 1 < srcH ? s + 1 : s) & 1];
        uint32_t* d = c->px + y * c->w;
        for (int x = 0; x < c->w; x++) d[x] = near == k->den ? a[x] : mix(a[x], b[x], near, k->den - near);
    }
}

// A card's geometry: as art_geometry grows a slot, in width only.
static struct art_geometry card_geometry(const struct pd_party_style* st, int extraW) {
    struct art_geometry a = { .st = st, .scale = 1, .left = extraW / 3, .mid = extraW - extraW / 3, .w = st->slotW + extraW, .h = st->slotH };
    return a;
}

// FNV-1a over [n] bytes, continuing from [h].
static uint32_t hash_bytes(uint32_t h, const void* p, size_t n) {
    const unsigned char* b = p;
    for (size_t i = 0; i < n; i++) h = (h ^ b[i]) * 16777619u;
    return h;
}

// What the scaled cards are drawn from: the party, the cursor, the layout, the look and the art. The same key = the same
// pixels, so a redraw for something else (a tab press, the clock) copies them instead of scaling the screen again - on a
// 3DS the scale took most of a frame.
static uint32_t cards_key(const struct pd_canvas* c, const struct pd_game* g, const struct pd_party_art* art, const struct window_at* at,
                          const struct card_scale* k, const struct pd_snapshot* s, int selected) {
    uint32_t h = 2166136261u;
    const void* ptrs[4] = { c->px, g, art, pd_cur };
    int ints[9] = { c->w, c->h, c->cx0, c->cy0, c->cx1, c->cy1, selected, s->partyCount, (int) pd_rom_id(g) };
    h = hash_bytes(h, ptrs, sizeof(ptrs));
    h = hash_bytes(h, ints, sizeof(ints));
    h = hash_bytes(h, k, sizeof(*k));
    h = hash_bytes(h, at, sizeof(*at) * PD_PARTY_SIZE);
    h = hash_bytes(h, pd_backdrop_colors, sizeof(pd_backdrop_colors));
    int n = s->partyCount < PD_PARTY_SIZE ? s->partyCount : PD_PARTY_SIZE;
    return hash_bytes(h, s->party, sizeof(s->party[0]) * (size_t) (n > 0 ? n : 0));
}

// The cards on the canvas, over its backdrop, or only the backdrop if `cards` is false. selected: which card, -1 none.
static bool draw_cards(struct pd_canvas* c, const struct pd_game* g, const struct pd_party_art* art, const struct window_at* at,
                       const struct card_scale* k, const struct pd_snapshot* s, int selected, bool cards) {
    static uint32_t px[PD_UI_WIDTH * PD_UI_HEIGHT];
    static uint32_t kept[PD_UI_WIDTH * PD_UI_HEIGHT], keptKey;
    static bool keptOk;
    struct pd_canvas screen;
    bool up = k->num != k->den;
    // The scaled party screen as last drawn: copied when nothing it shows has changed.
    bool cache = up && cards && c->w * c->h <= PD_UI_WIDTH * PD_UI_HEIGHT;
    uint32_t key = cache ? cards_key(c, g, art, at, k, s, selected) : 0;
    if (cache && keptOk && key == keptKey) {
        memcpy(c->px, kept, (size_t) c->w * c->h * sizeof(*kept));
        return true;
    }
    int w = (c->w * k->den + k->num - 1) / k->num + 1, h = (c->h * k->den + k->num - 1) / k->num + 1;
    pd_canvas_init(&screen, w, h, px);
    if (up && !pd_party_screen(&screen, g)) return false;
    struct art_geometry a = card_geometry(art->style, k->extraW);
    cardTextsOn = up;
    cardTextCount = 0;
    for (int i = 0; cards && i < PD_PARTY_SIZE; i++) {
        const struct pd_mon* m = i < s->partyCount ? &s->party[i] : NULL;
        cardX = at[i].x;
        cardY = at[i].y;
        art_slot_at(up ? &screen : c, g, art, &a, at[i].x, at[i].y, m, m && i == selected);
    }
    cardTextsOn = false;
    if (up) screen_up(c, px, w, h, k);
    for (int i = 0; i < cardTextCount; i++) {
        const struct card_text* t = &cardTexts[i];
        int x = t->x * k->num / k->den, y = (t->y + TEXT_TOP) * k->num / k->den + CARD_TEXT_DY;
        if (t->right) pd_text_right(c, x, y, t->text, t->fg, t->shadow);
        else pd_text(c, x, y, t->text, t->fg, t->shadow);
    }
    if (cache) {
        memcpy(kept, c->px, (size_t) c->w * c->h * sizeof(*kept));
        keptKey = key;
        keptOk = true;
    }
    return true;
}

// A card's rectangle on the canvas.
static void card_rect(const struct pd_party_style* st, const struct window_at* at, const struct card_scale* k, int* x, int* y, int* w, int* h) {
    *x = at->x * k->num / k->den;
    *y = at->y * k->num / k->den;
    *w = (st->visibleW + k->extraW) * k->num / k->den;
    *h = st->visibleH * k->num / k->den;
}

static void party_slot(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, int x, int y, int w,
                       int h, const struct pd_mon* m, int slot, bool selected) {
    const struct pd_party_art* art = pd_party_art_for(g);
    if (art) {
        art_slot(ui, c, g, art, x, y, w, h, m, slot, selected);
        return;
    }
    struct slot_geometry geo = geometry(w, h);
    int u = geo.u;
    const struct pd_party_source_palette* p=ui->partySource?ui->partySource->palette:NULL;
    if (!m) {
        if (!p) {
            const struct pd_slot_colors* slot = &SLOT_NORMAL;
            const struct pd_party_slot_colors colors = {slot->outline, slot->light, slot->fill, slot->shade, slot->band, 0};
            faded_slot(c, x, y, w, h, &geo, &colors, EMPTY_ALPHA);
        } else if (p->emptyAlpha) {
            faded_slot(c, x, y, w, h, &geo, selected ? &p->selected : &p->normal, p->emptyAlpha);
        } else if (p->emptyLines) {
            int left = x + geo.fx, top = y + geo.fy, height = geo.fh / u;
            if (!p->chamfer) {
                pd_round_rect(c, left, top, geo.fw, geo.fh, 3 * u, p->empty.outline);
                pd_round_rect(c, left + 2 * u, top + 2 * u, geo.fw - 4 * u, geo.fh - 4 * u, u, p->empty.fill);
            }
            for (int row = p->chamfer ? 1 : 3; row < height - (p->chamfer ? 1 : 2); row += 2) {
                int edge = (p->chamfer ? cut_inset(row, height) : 2) * u;
                pd_fill(c, left + edge, top + row * u, geo.fw - 2 * edge, u, p->emptyLines);
            }
        } else {
            slot_frame(c, x, y, &geo, &p->empty, p->chamfer);
        }
        return;
    }
    char buf[40];
    bool egg = party_egg(ui, m);
    bool fainted = !egg && m->hp == 0;
    const struct pd_slot_colors* frame = fainted ? (selected ? &SLOT_SELECTED_FAINTED : &SLOT_FAINTED)
        : selected ? &SLOT_SELECTED : &SLOT_NORMAL;
    const struct pd_party_slot_colors colors = {frame->outline, frame->light, frame->fill, frame->shade, frame->band, 0};
    const struct pd_party_slot_colors* col = p ?
        (fainted ? (selected ? &p->selectedFainted : &p->fainted) : (selected ? &p->selected : &p->normal)) : &colors;
    slot_frame(c, x, y, &geo, col, p && p->chamfer);
    if (!egg) hp_bar(c, x, y, &geo, m->hp, m->maxHp, p);
    uint32_t text=p?p->text:SLOT_TEXT, shadow=p?p->shadow:SLOT_TEXT_SHADOW;

    // The Poke Ball, then the icon over it, resting 4 px up.
    if(!p || !p->hideBall)char_bitmap(c, BALL, 20, x + geo.fx - 6 * u, y + geo.fy - 4 * u, u, "KRHW", p ? p->ball : PP->ball);
    party_icon(ui, c, g, m, x + geo.fx - 8 * u, y + geo.fy - 4 * u, u, false);
    if (m->heldItem) held_mark(c, x + geo.fx + 13 * u, y + geo.fy + 15 * u);

    int textW = geo.fx + geo.fw - 4 * u - geo.textX;
    pd_text_fit(c, x + geo.textX, y + geo.nameMid - 8, textW, mon_display_name(ui, g, m, buf, sizeof(buf)), text,
                shadow);
    if (!egg) {
        const char* status = fainted ? "FNT" : pd_status_label(m->status);
        if (*status) {
            status_chip(c, x + geo.textX + 6 * u, y + geo.levelMid - 7, status);
        } else {
            char lv[12];
            snprintf(lv, sizeof(lv), "Lv%d", m->level);
            int lw = pd_text(c, x + geo.textX + 6 * u, y + geo.levelMid - 8, lv, text, shadow);
            if (m->shiny) pd_text(c, x + geo.textX + 6 * u + lw + 3, y + geo.levelMid - 8, "*", SHINY_MARK, shadow);
        }
        int gx = x + geo.fx + geo.fw - 14 * u, gy = y + geo.levelMid - 4 * u;
        if (m->gender == PD_GENDER_MALE || m->gender == PD_GENDER_FEMALE) {
            bool male = m->gender == PD_GENDER_MALE;
            const uint32_t shadow[1] = { male ? (p ? p->maleShadow : MALE_SHADOW) : (p ? p->femaleShadow : FEMALE_SHADOW) };
            const uint32_t color[1] = { male ? (p ? p->male : MALE) : (p ? p->female : FEMALE) };
            char_bitmap(c, male ? MALE_MARK : FEMALE_MARK, 8, gx + u, gy + u, u, "1", shadow);
            char_bitmap(c, male ? MALE_MARK : FEMALE_MARK, 8, gx, gy, u, "1", color);
        }
        char hp[16];
        snprintf(hp, sizeof(hp), "%d/%d", m->hp, m->maxHp);
        pd_text_right(c, x + geo.barRight, y + geo.hpTextMid - 8, hp, text, shadow);
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
        int pct = pd_type_multiplier_pct(ui_game, t, t1, t2);
        any = t != 9 && pct >= lo && pct <= hi;
    }
    if (!any || y + 14 > bottom) return y;
    pd_text(c, x, y - 1, heading, PD_MUTED, PD_MUTED_SHADOW);
    int cx = x + pd_text_width(heading) + 4;
    for (int t = 0; t < 18; t++) {
        int pct = pd_type_multiplier_pct(ui_game, t, t1, t2);
        if (t == 9 || pct < lo || pct > hi) continue;
        char label[12];
        snprintf(label, sizeof(label), "%.3s", pd_type_name(ui_game, t));
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
    const struct pd_mon_stats* st = &m->stats;
    char buf[32];
    int labelW = w * 36 / 100, statW = 26, evW = 24;
    pd_text_right(c, x + labelW + statW, y, "STAT", PD_MUTED, PD_MUTED_SHADOW);
    if (st->hasIvs) pd_text(c, x + labelW + statW + 6, y, "IV", PD_MUTED, PD_MUTED_SHADOW);
    if (st->hasEvs) pd_text_right(c, x + w, y, "EV", PD_MUTED, PD_MUTED_SHADOW);
    y += 15;
    bool ownNature = st->nature >= PD_MONOMYTH_NATURE_BASE;
    int up = st->hasNature ? pd_nature_raises(st->nature) : -1, down = st->hasNature ? pd_nature_lowers(st->nature) : -1;
    int totalIv = 0, totalEv = 0;
    for (int i = 0; i < 6; i++) {
        int k = STAT_ORDER[i];
        pd_text(c, x, y, STAT_LABELS[i], PD_LABEL, PD_LABEL_SHADOW);
        if (k == up) pd_triangle(c, x + labelW - 8, y + 5, 5, PD_UP, 0xE04040);
        if (k == down) pd_triangle(c, x + labelW - 8, y + 6, 5, PD_DOWN, 0x3984F7);
        snprintf(buf, sizeof(buf), "%d", st->values[k]);
        pd_text_right(c, x + labelW + statW, y, buf, PD_LABEL, PD_LABEL_SHADOW);
        if (st->hasIvs) {
            int bx = x + labelW + statW + 5, bw = w - labelW - statW - 5 - evW - 16, iv = st->ivs[k];
            uint32_t col = iv >= 31 ? 0xE8B830 : iv * 2 > 31 ? 0x60D048 : iv * 5 > 31 ? 0xF8C838 : 0xF04838;
            pd_round_rect(c, bx, y + 6, bw, 6, 2, 0x525252);
            pd_fill(c, bx + 1, y + 7, bw - 2, 4, 0x506858);
            pd_fill(c, bx + 1, y + 7, (bw - 2) * iv / 31, 4, col);
            snprintf(buf, sizeof(buf), "%d", iv);
            pd_text_right(c, x + w - evW, y, buf, PD_VALUE, PD_VALUE_SHADOW);
        }
        if (st->hasEvs) {
            snprintf(buf, sizeof(buf), "%d", st->evs[k]);
            pd_text_right(c, x + w, y, buf, PD_VALUE, PD_VALUE_SHADOW);
        }
        totalIv += st->ivs[k];
        totalEv += st->evs[k];
        y += 15;
    }
    pd_text(c, x, y, "TOTAL", PD_LABEL, PD_LABEL_SHADOW);
    snprintf(buf, sizeof(buf), "%d", totalIv);
    if (st->hasIvs) pd_text_right(c, x + w - evW, y, buf, PD_VALUE, PD_VALUE_SHADOW);
    snprintf(buf, sizeof(buf), "%d", totalEv);
    if (st->hasEvs) pd_text_right(c, x + w, y, buf, PD_VALUE, PD_VALUE_SHADOW);
    y += 17;
    if (st->hasNature) {
        pd_text(c, x, y, "NATURE", PD_LABEL, PD_LABEL_SHADOW);
        const char* nature = ownNature ? pd_monomyth_natures[st->nature - PD_MONOMYTH_NATURE_BASE]
            : st->nature >= 0 && st->nature < 25 ? NATURES[st->nature] : "?";
        pd_text(c, x + w * 46 / 100, y, nature, PD_VALUE, PD_VALUE_SHADOW);
        y += 15;
    }
    if (st->hasHiddenPower) {
        int lw = pd_text(c, x, y, "HIDDEN POWER", PD_MUTED, PD_MUTED_SHADOW);
        if (st->hiddenPowerPower > 0) snprintf(buf, sizeof(buf), "%s  PWR %d", pd_type_name(NULL, st->hiddenPowerType), st->hiddenPowerPower);
        else snprintf(buf, sizeof(buf), "%s", pd_type_name(NULL, st->hiddenPowerType));
        int vw = pd_text_width(buf);
        if (vw > w - lw - 8) vw = w - lw - 8;
        pd_text_fit(c, x + w - vw, y, vw, buf, PD_LABEL, PD_LABEL_SHADOW);
    }
}

// INFO (dev's pane, in the window's right half here): the EXP bar, the held item and its description, the type matchups
// (MonDetailScreen's information column, after Keegan Whitacre's Summary INFO in pokedaisy-brick).
static void summary_info(struct pd_canvas* c, const struct pd_game* g, const struct pd_mon* m, int x, int y, int w,
                         int bottom, bool describe) {
    char buf[96];
    int row = y;
    bool narrow = w < 220;
    struct pd_exp xp;
    if (m->hasExperience && pd_retail_exp_progress(m->species, m->level, m->experience, w - 2, &xp)) {
        pd_text(c, x, row, "EXP", PD_MUTED, PD_MUTED_SHADOW);
        snprintf(buf, sizeof(buf), "%lu", (unsigned long) m->experience);
        pd_text_right(c, x + w, row, buf, PD_VALUE, PD_VALUE_SHADOW);
        row += 17;
        pd_fill(c, x, row, w, 9, PD_DIVIDER);
        pd_fill(c, x + 1, row + 1, w - 2, 7, 0x506858);
        pd_fill(c, x + 1, row + 1, xp.filled, 7, 0x40B8F8);
        row += 10;
        if (m->level == 100) snprintf(buf, sizeof(buf), "MAX");
        else snprintf(buf, sizeof(buf), "NEXT %lu", (unsigned long) xp.toNext);
        pd_text_right(c, x + w, row, buf, PD_VALUE, PD_VALUE_SHADOW);
        row += 17;
    }
    if (m->hasHeldItem && row + 17 <= bottom) {
        pd_text(c, x, row, "HELD ITEM", PD_MUTED, PD_MUTED_SHADOW);
        const char* item = m->heldItem ? pd_item_name(g, m->heldItem) : "NONE";
        if (!item[0]) item = "UNKNOWN ITEM";
        if (narrow) row += 15; // a half-width pane: the name under its label
        pd_text_fit(c, narrow ? x : x + 84, row, narrow ? w : w - 84, item, PD_LABEL, PD_LABEL_SHADOW);
        row += 17;
        const char* desc = m->heldItem ? pd_item_description(g, m->heldItem) : "";
        if (describe && desc[0] && row + 14 <= bottom) {
            int lines = (bottom - row - 40) / 14;
            if (lines > 2) lines = 2;
            if (lines > 0) row += pd_text_wrap(c, x, row, w, 14, lines, desc, PD_LABEL, PD_LABEL_SHADOW) * 14 + 4;
        }
    }
    int t1, t2;
    pd_species_type_pair(g, m->species, &t1, &t2);
    static const char* const heads[3] = {"WEAK TO", "RESISTS", "IMMUNE"};
    for (int group = 0; group < 3; group++) {
        buf[0] = 0;
        for (int t = 0; t < 32; t++) {
            if (!pd_type_name(g, t)[0] || !strcmp(pd_type_name(g, t), "???")) continue;
            int pct = pd_type_multiplier_pct(g, t, t1, t2);
            if (!((group == 0 && pct > 100) || (group == 1 && pct > 0 && pct < 100) || (group == 2 && pct == 0)))
                continue;
            size_t n = strlen(buf);
            snprintf(buf + n, sizeof(buf) - n, "%s%s", n ? ", " : "", pd_type_name(g, t));
        }
        if (!buf[0] || row + 13 > bottom) continue;
        pd_text(c, x, row, heads[group], PD_MUTED, PD_MUTED_SHADOW);
        if (narrow) {
            // The words under their heading, wrapped to the lines left.
            row += 14;
            int lines = (bottom - row) / 14;
            if (lines > 2) lines = 2;
            if (lines < 1) break;
            row += pd_text_wrap(c, x, row, w, 14, lines, buf, PD_LABEL, PD_LABEL_SHADOW) * 14;
            continue;
        }
        pd_text_fit(c, x + 66, row, w - 66, buf, PD_LABEL, PD_LABEL_SHADOW);
        row += 14;
    }
}

static void summary_chip(struct pd_canvas* c, int x, int y, int width, int type, const char* label) {
    static const uint32_t colors[] = {0xa8a878,0xc03028,0xa890f0,0xa040a0,0xe0c068,0xb8a038,
        0xa8b820,0x705898,0xb8b8d0,0x68a090,0xf08030,0x6890f0,0x78c850,0xf8d030,0xf85888,0x98d8d8,0x7038f8,0x705848};
    if (width < 15) return;
    uint32_t color = type >= 0 && type < (int)(sizeof colors / sizeof *colors) ? colors[type] : 0x8a8a8a;
    const unsigned light = 3 * (color >> 16) + 6 * ((color >> 8) & 255) + (color & 255);
    pd_round_rect(c, x, y, width, 13, 2, color);
    pd_text_fit(c, x + 3, y - 1, width - 6, label, light >= 1220 ? 0x141820 : 0xffffff, PD_NO_SHADOW);
}

static void summary_stats_left(struct pd_canvas* c, const struct pd_summary_view* v, int w, int y, int bottom) {
    const struct pd_mon_stats* st = v->stats;
    if (v->speciesName && strcmp(v->speciesName, v->name) && y + 15 <= bottom) {
        pd_text_fit(c, 13, y, w, v->speciesName, PD_LABEL, PD_LABEL_SHADOW);
        y += 15;
    }
    if (st && st->hasNature && st->nature >= 0 && st->nature < pd_nature_names_count && y + 15 <= bottom) {
        pd_text_fit(c, 13, y, w, pd_nature_names[st->nature], PD_LABEL, PD_LABEL_SHADOW);
        y += 15;
    }
    if (v->friendship >= 0 && y + 15 <= bottom) {
        char line[24];
        snprintf(line, sizeof line, "FRIENDSHIP %d", v->friendship);
        pd_text_fit(c, 13, y, w, line, PD_LABEL, PD_LABEL_SHADOW);
        y += 15;
    }
    if (v->heldItem && y + 30 <= bottom) {
        pd_text(c, 13, y, "HELD ITEM", PD_MUTED, PD_MUTED_SHADOW);
        pd_text_fit(c, 13, y + 15, w, v->heldItem, PD_LABEL, PD_LABEL_SHADOW);
    }
}

static void summary_stats_right(struct pd_canvas* c, const struct pd_summary_view* v, int x, int w, int bottom) {
    const struct pd_mon_stats* s = v->stats;
    pd_text(c, x, 12, "STATS", PD_MUTED, PD_MUTED_SHADOW);
    if (!s || !s->valid) {
        pd_text_wrap(c, x, 37, w, 15, 3, "Stats unavailable for this cartridge.", PD_LABEL, PD_LABEL_SHADOW);
        return;
    }
    static const int order[] = {0,1,2,4,5,3}, gen1Order[] = {0,1,2,4,3};
    static const char* const labels[] = {"HP","ATK","DEF","SP. ATK","SP. DEF","SPEED"};
    static const char* const gen1Labels[] = {"HP","ATK","DEF","SPECIAL","SPEED"};
    const int ev = x + w - 3, iv = ev - (s->hasEvs ? 30 : 0), stat = iv - (s->hasIvs ? 29 : 0);
    const int top = 33, row = 16;
    pd_text_right(c, stat, top, "STAT", PD_MUTED, PD_MUTED_SHADOW);
    if (s->hasIvs) pd_text_right(c, iv, top, v->dvs ? "DV" : "IV", PD_MUTED, PD_MUTED_SHADOW);
    if (s->hasEvs) pd_text_right(c, ev, top, "EV", PD_MUTED, PD_MUTED_SHADOW);
    const int raised = s->hasNature ? pd_nature_raises(s->nature) : -1;
    const int lowered = s->hasNature ? pd_nature_lowers(s->nature) : -1;
    const int n = v->gen1 ? 5 : 6;
    int total = 0;
    for (int j = 0; j < n; j++) {
        const int i = v->gen1 ? gen1Order[j] : order[j], y = top + (j + 1) * row;
        char text[36];
        snprintf(text, sizeof text, "%s%s", v->gen1 ? gen1Labels[j] : labels[j],
                 i == raised ? " +" : i == lowered ? " -" : "");
        pd_text_fit(c, x, y, stat - x - 25, text, i == raised ? 0xC03535 : i == lowered ? 0x2664BF : PD_LABEL, PD_LABEL_SHADOW);
        snprintf(text, sizeof text, "%d", s->values[i]);
        pd_text_right(c, stat, y, text, PD_LABEL, PD_LABEL_SHADOW);
        if (s->hasIvs) {
            snprintf(text, sizeof text, "%d", s->ivs[i]);
            pd_text_right(c, iv, y, text, PD_VALUE, PD_VALUE_SHADOW);
        }
        if (s->hasEvs) {
            snprintf(text, sizeof text, "%d", s->evs[i]);
            pd_text_right(c, ev, y, text, PD_VALUE, PD_VALUE_SHADOW);
            total += s->evs[i];
        }
    }
    int y = top + (n + 1) * row;
    char text[52];
    if (s->hasEvs) {
        snprintf(text, sizeof text, "EV TOTAL  %d", total);
        pd_text_right(c, ev, y, text, PD_MUTED, PD_MUTED_SHADOW);
        y += row;
    }
    if (s->hasHiddenPower && y + 15 <= bottom) {
        const char* type = pd_type_name(NULL, s->hiddenPowerType);
        if (s->hiddenPowerPower > 0) snprintf(text, sizeof text, "HIDDEN POWER  %s %d", type, s->hiddenPowerPower);
        else snprintf(text, sizeof text, "HIDDEN POWER  %s", type);
        pd_text_fit(c, x, y, w, text, PD_MUTED, PD_MUTED_SHADOW);
    }
}

static int learn_rows(int height) {
    const int rows = (height - 32 - 32) / 17;
    return rows > 0 ? rows : 1;
}

int pd_ui_summary_learn_last(int height, int count) {
    const int last = count - learn_rows(height);
    return last > 0 ? last : 0;
}

static void summary_learn(struct pd_canvas* c, const struct pd_summary_view* v, int x, int w, int bottom) {
    const struct pd_summary_learn* list = v->learn;
    pd_text_fit(c, x, 12, w, "LEVEL-UP MOVES", PD_MUTED, PD_MUTED_SHADOW);
    if (!list || list->count <= 0) {
        pd_text_fit(c, x, 37, w, list && list->count == 0 ? "No level-up moves." : "No learnset data.", PD_LABEL, PD_LABEL_SHADOW);
        return;
    }
    const int visible = learn_rows(c->h), last = pd_ui_summary_learn_last(c->h, list->count);
    const int first = list->first < 0 ? 0 : list->first > last ? last : list->first;
    for (int i = first; i < list->count && i < first + visible; i++) {
        const int y = 32 + (i - first) * 17;
        char scratch[48], level[12];
        const struct pd_summary_learn_row move = list->row(list->ctx, i, scratch, sizeof scratch);
        if (move.level) snprintf(level, sizeof level, "Lv%d", move.level);
        else snprintf(level, sizeof level, "EVO.");
        pd_text(c, x, y, level, move.level > v->level ? PD_VALUE : PD_MUTED, PD_MUTED_SHADOW);
        pd_text_fit(c, x + 42, y, w - 47, move.name, PD_LABEL, PD_LABEL_SHADOW);
    }
    ui_scroll_bar(c, x + w - 2, 32, bottom - 32, first * 17, list->count * 17, PD_MUTED);
}

void pd_ui_summary_page(struct pd_canvas* c, const struct pd_summary_view* v, enum pd_summary_page page) {
    const int w = c->w - 8, h = c->h - 32, side = w * 46 / 100, rx = side + 16, rw = c->w - rx - 12;
    pd_title_box(c, 4, 4, w, h, PD_TITLE_FILL);
    pd_list_box(c, 11, 13, 34, 34);
    v->icon(v->ctx, c, v->selected, 12, 14, false);
    pd_text_fit(c, 49, 12, side - 40, v->name, PD_LABEL, PD_LABEL_SHADOW);
    if (v->egg) {
        pd_text(c, 14, 58, "It will hatch in time.", PD_MUTED, PD_MUTED_SHADOW);
        return;
    }
    char line[48];
    snprintf(line, sizeof line, "Lv%d", v->level);
    pd_text(c, 49, 28, line, PD_VALUE, PD_VALUE_SHADOW);
    pd_text_right(c, side + 7, 28, v->hp ? pd_status_label(v->status) : "FNT", PD_VALUE, PD_VALUE_SHADOW);
    const int types[] = {v->type1, v->type2};
    int x = 49;
    for (int i = 0; i < 2; i++) {
        if (types[i] < 0 || (i && types[i] == types[0])) continue;
        const char* label = pd_type_name(v->game, types[i]);
        if (!*label) continue;
        int width = pd_text_width(label) + 6;
        if (width > side + 8 - x) width = side + 8 - x;
        summary_chip(c, x, 45, width, types[i], label);
        x += width + 3;
    }
    pd_fill(c, side + 11, 12, 1, h - 16, PD_DIVIDER);
    const int bar = side - 4;
    pd_text(c, 13, 60, "HP", PD_MUTED, PD_MUTED_SHADOW);
    snprintf(line, sizeof line, "%d/%d", v->hp, v->maxHp);
    pd_text_right(c, side + 8, 60, line, PD_LABEL, PD_LABEL_SHADOW);
    const uint32_t color = v->hp * 5 <= v->maxHp ? 0xDA4A48 : v->hp * 2 <= v->maxHp ? 0xD8A52E : 0x4D9A58;
    pd_fill(c, 13, 75, bar, 5, PD_DIVIDER);
    if (v->maxHp > 0) pd_fill(c, 13, 75, bar * (v->hp < v->maxHp ? v->hp : v->maxHp) / v->maxHp, 5, color);
    int y = 85;
    if (v->hasExperience) {
        pd_text(c, 13, 82, "EXP", PD_MUTED, PD_MUTED_SHADOW);
        if (v->expPercent >= 0) {
            if (v->level >= 100) snprintf(line, sizeof line, "MAX");
            else snprintf(line, sizeof line, "NEXT %lu", (unsigned long)v->toNext);
            pd_fill(c, 13, 97, bar, 5, PD_DIVIDER);
            pd_fill(c, 13, 97, v->expPercent * bar / 100, 5, 0x5883BF);
        } else snprintf(line, sizeof line, "%lu", (unsigned long)v->experience);
        pd_text_right(c, side + 8, 82, line, PD_VALUE, PD_VALUE_SHADOW);
        y = 107;
    }
    if (page == PD_SUMMARY_STATS) {
        summary_stats_left(c, v, bar, y, h);
        summary_stats_right(c, v, rx, rw, h);
        return;
    }
    static const char* const headings[] = {"WEAK TO","RESISTS","IMMUNE TO"};
    for (int group = 0; group < 3; group++) {
        x = 13;
        for (int type = 0; type < 32; type++) {
            const char* label = pd_type_name(v->game, type);
            if (!*label || v->type1 < 0) continue;
            const int pct = pd_type_multiplier_pct(v->game, type, v->type1, v->type2);
            if (!(group == 0 ? pct > 100 : group == 1 ? pct > 0 && pct < 100 : pct == 0)) continue;
            if (x == 13) {
                if (y + 27 > h) break;
                pd_text(c, x, y, headings[group], PD_MUTED, PD_MUTED_SHADOW);
                y += 14;
            }
            const char* amount = pct == 400 ? " 4x" : pct == 200 ? " 2x" : pct == 50 ? " .5x" : pct == 25 ? " .25x" : "";
            snprintf(line, sizeof line, "%s%s", label, amount);
            const int chip = pd_text_width(line) + 6;
            if (x + chip > side + 9) { x = 14; y += 14; }
            if (y + 13 > h) break;
            summary_chip(c, x, y, chip, type, line);
            x += chip + 3;
        }
        if (x != 13) y += 14;
    }
    if (page == PD_SUMMARY_LEARN) {
        summary_learn(c, v, rx, rw, h);
        return;
    }
    pd_text(c, rx, 12, "CURRENT MOVES", PD_MUTED, PD_MUTED_SHADOW);
    const int step = (h - 32) / PD_NUM_MOVES;
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        const struct pd_summary_move* move = &v->moves[i];
        if (!move->name) continue;
        const int top = 30 + i * step;
        pd_text_fit(c, rx, top, rw - 3, move->name, PD_LABEL, PD_LABEL_SHADOW);
        char power[12] = "-";
        if (move->power == 1) snprintf(power, sizeof power, "VAR");
        else if (move->power > 1) snprintf(power, sizeof power, "%d", move->power);
        snprintf(line, sizeof line, "PWR %s PP %d", power, move->pp);
        pd_text_right(c, rx + rw - 3, top + 15, line, PD_VALUE, PD_VALUE_SHADOW);
        const char* label = pd_type_name(v->game, move->type);
        int chip = rw - pd_text_width(line) - 8, need = pd_text_width(label) + 6;
        if (*label) summary_chip(c, rx, top + 15, chip < need ? chip : need, move->type, label);
        if (i != PD_NUM_MOVES - 1) pd_fill(c, rx, top + step - 4, rw, 1, PD_DIVIDER);
    }
}

static void summary_control(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, const char* label, int hit) {
    pd_title_box(c, x, y, w, 23, PD_TITLE_FILL);
    pd_text_fit(c, x + 5, y + 3, w - 10, label, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    if (ui) ui_add_hit(ui, x, y, w, 23, hit);
}

void pd_ui_summary_footer(struct pd_ui* ui, struct pd_canvas* c, const struct pd_summary_view* v, bool stats) {
    const int y = c->h - 25, bw = c->w < 334 ? 68 : 74;
    int first = 0, end = v->count < PD_PARTY_SIZE ? v->count : PD_PARTY_SIZE;
    int start = 5 + 2 * bw, step = 19, back = 62;
    int primary = bw, secondary = bw - 7, sx = 7 + bw;
    if (v->handheldControls) {
        primary = v->primary ? pd_text_width(v->primary) + 10 : 0;
        secondary = v->secondary ? pd_text_width(v->secondary) + 10 : 0;
        back = pd_text_width("B BACK") + 10;
        sx = 4 + primary + (primary ? 3 : 0);
        start = sx + secondary + 3;
        const int room = c->w - back - 8 - start;
        const bool arrows = end > 1 && room >= end * 18 + 12;
        const int strip = room - (arrows ? 12 : 0);
        const int shown = strip / 18 < end ? strip / 18 : end;
        if (shown > 0) {
            if (shown * 19 > strip) step = 18;
            first = v->selected - shown / 2;
            if (first < 0) first = 0;
            if (first > end - shown) first = end - shown;
            end = first + shown;
            start += (room - shown * step) / 2;
            if (arrows) {
                const uint32_t ink = PD_ON_BACKDROP;
                pd_triangle(c, start - 6, y + 8, 5, v->learnPage ? PD_UP : PD_LEFT, ink);
                pd_triangle(c, start + shown * step + 1, y + 8, 5, v->learnPage ? PD_DOWN : PD_RIGHT, ink);
            }
        } else end = 0;
    }
    if (v->primary) summary_control(ui, c, 4, y, primary, v->primary, HIT_SUMMARY_STATS);
    if (v->secondary) summary_control(ui, c, sx, y, secondary, v->secondary, v->secondaryLearn ? HIT_SUMMARY_LEARN : HIT_SUMMARY_INFO);
    for (int i = first; i < end; i++) {
        const int x = start + (i - first) * step;
        pd_title_box(c, x, y + 2, 18, 19, i == v->selected ? PD_ROW_SELECTED : PD_LIST_FILL);
        if (!v->icon(v->ctx, c, i, x + 1, y + 3, true)) {
            const char slot[] = {(char)('1' + i), 0};
            pd_text(c, x + 6, y + 3, slot, PD_LABEL, PD_LABEL_SHADOW);
        }
        if (i == v->selected) pd_fill(c, x + 2, y + 18, 14, 2, PD_VALUE);
        if (ui) ui_add_hit(ui, x, y + 2, 18, 19, HIT_SUMMARY_MON + i);
    }
    summary_control(ui, c, c->w - back - 4, y, back, "B BACK", HIT_BACK);
    if (ui) ui->startId = stats ? HIT_SUMMARY_STATS : HIT_SUMMARY_MON + v->selected;
}

struct native_summary { const struct pd_game* game; const struct pd_snapshot* snapshot; };
static bool native_summary_icon(void* ctx, struct pd_canvas* c, int slot, int x, int y, bool small) {
    const struct native_summary* source = ctx;
    const struct pd_mon* mon = &source->snapshot->party[slot];
    if (!small) return pd_draw_mon_icon_shiny(c, source->game, mon->species, mon->shiny, x, y, 1, false);
    const uint32_t* icon = pd_mon_icon_pixels(source->game, mon->species, mon->shiny);
    if (!icon) return false;
    for (int row = 0; row < 16; row++)
        for (int col = 0; col < 16; col++) {
            uint32_t px = icon[row * 2 * PD_ICON + col * 2];
            if (px != 0xff000000u) pd_fill(c, x + col, y + row, 1, 1, px);
        }
    return true;
}

struct native_learnset {
    const struct pd_game* game;
    struct pd_level_move moves[100];
};
static struct pd_summary_learn_row native_learn_row(const void* data, int i, char* scratch, size_t n) {
    const struct native_learnset* source = data;
    const struct pd_level_move move = source->moves[i];
    return (struct pd_summary_learn_row){move.level, pd_move_name(source->game, move.move, scratch, n)};
}

void pd_ui_party_summary_control(struct pd_ui* ui, const struct pd_snapshot* s, int dx, int dy,
                                 bool confirm, bool select) {
    if (ui->summarySlot < 0 || ui->summarySlot >= s->partyCount) return;
    const int next = ui->summarySlot + (dy ? dy : ui->summaryLearn ? 0 : dx);
    if (next >= 0 && next < s->partyCount && next != ui->summarySlot) {
        ui->summarySlot = next;
        ui->summaryLearnRow = 0;
    }
    const struct pd_mon* mon = &s->party[ui->summarySlot];
    if (party_egg(ui, mon)) return;
    if (select) {
        if (ui->summaryInfo) ui->summaryInfo = false;
        else if (!ui_summary_learn(ui)) {
            ui->summaryInfo = true;
            ui->summaryStats = false;
        }
    } else if (confirm) {
        ui->summaryStats = !ui->summaryLearn && !ui->summaryInfo && !ui->summaryStats && mon->stats.valid;
        ui->summaryLearn = ui->summaryInfo = false;
    } else if (ui->summaryLearn) {
        ui->summaryLearnRow += dx;
        if (ui->summaryLearnRow < 0) ui->summaryLearnRow = 0;
        if (ui->summaryLearnRow > ui->summaryLearnLast) ui->summaryLearnRow = ui->summaryLearnLast;
    }
}

static void native_summary_view(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    const struct pd_mon* m = &s->party[ui->summarySlot];
    char name[40], species[40], moveNames[PD_NUM_MOVES][40];
    static struct native_learnset learnset;
    static unsigned rom;
    static int cachedSpecies, count = -1;
    if (rom != pd_rom_id(g) || cachedSpecies != m->species) {
        count = pd_dex_level_moves(g, m->species, learnset.moves, 100);
        rom = pd_rom_id(g);
        cachedSpecies = m->species;
    }
    learnset.game = g;
    ui->summaryCanLearn = count >= 0 && !party_egg(ui, m);
    if (!ui->summaryCanLearn) ui->summaryLearn = false;
    ui->summaryLearnLast = pd_ui_summary_learn_last(c->h, count);
    if (ui->summaryLearnRow > ui->summaryLearnLast) ui->summaryLearnRow = ui->summaryLearnLast;
    const struct pd_summary_learn learn = {&learnset, native_learn_row, count, ui->summaryLearnRow};
    if (!m->stats.valid) ui->summaryStats = false;
    const bool secondaryLearn = ui->summaryLearn || (!ui->summaryInfo && ui->summaryCanLearn &&
        (ui->shoulderTabs || !ui->summaryStats));
    const bool gb = g->cfg->gb != NULL;
    struct native_summary ctx = {g, s};
    struct pd_summary_view view = {
        .game = g, .ctx = &ctx, .icon = native_summary_icon,
        .name = mon_display_name(ui, g, m, name, sizeof name),
        .primary = ui->shoulderTabs
            ? (ui->summaryStats || ui->summaryLearn || ui->summaryInfo ? "A MOVES" : m->stats.valid ? "A STATS" : NULL)
            : m->stats.valid ? (ui->summaryStats ? "MOVES" : "STATS") : NULL,
        .secondary = ui->shoulderTabs
            ? (ui->summaryInfo || ui->summaryLearn ? "SEL MOVES" : secondaryLearn ? "SEL LEARN" : "SEL INFO")
            : ui->summaryInfo || ui->summaryLearn ? "MOVES" : secondaryLearn ? "LEARN" : "INFO",
        .secondaryLearn = secondaryLearn, .handheldControls = ui->shoulderTabs, .learnPage = ui->summaryLearn,
        .speciesName = party_species(ui, g, m->species, species, sizeof species),
        .heldItem = m->hasHeldItem ? (m->heldItem ? pd_item_name(g, m->heldItem) : "NONE") : NULL,
        .friendship = m->hasFriendship ? m->friendship : -1, .stats = &m->stats,
        .dvs = gb, .gen1 = gb && g->cfg->gb->gen == 1, .learn = &learn,
        .selected = ui->summarySlot, .count = s->partyCount,
        .level = m->level, .hp = m->hp, .maxHp = m->maxHp, .status = m->status,
        .experience = m->experience, .expPercent = -1, .hasExperience = m->hasExperience,
        .egg = party_egg(ui, m),
    };
    pd_species_type_pair(g, m->species, &view.type1, &view.type2);
    struct pd_exp exp;
    if (m->hasExperience && (gb
        ? pd_exp_progress(pd_polished_growth(g, m->species), m->level, m->experience, 100, &exp)
        : !g->hackMaps && pd_retail_exp_progress(m->species, m->level, m->experience, 100, &exp))) {
        view.expPercent = exp.filled;
        view.toNext = exp.toNext;
    }
    for (int i = 0; i < PD_NUM_MOVES; i++)
        if (m->moves[i])
            view.moves[i] = (struct pd_summary_move){
                pd_move_name(g, m->moves[i], moveNames[i], sizeof moveNames[i]),
                pd_move_type(g, m->moves[i]), pd_move_power(g, m->moves[i]), m->pp[i],
            };
    if (ui->summaryInfo && !view.egg) {
        pd_title_box(c, 4, 4, c->w - 8, c->h - 32, PD_TITLE_FILL);
        pd_text_fit(c, 13, 12, c->w - 26, view.name, PD_VALUE, PD_VALUE_SHADOW);
        summary_info(c, g, m, 13, 34, c->w - 26, c->h - 37, true);
    } else pd_ui_summary_page(c, &view, ui->summaryLearn ? PD_SUMMARY_LEARN : ui->summaryStats ? PD_SUMMARY_STATS : PD_SUMMARY_MOVES);
    if (view.egg) view.primary = view.secondary = NULL;
    pd_ui_summary_footer(ui, c, &view, view.primary != NULL);
}

static void summary_moves(const struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_mon* m, int x, int y,
                          int w, int h) {
    const struct pd_party_source* src = ui->partySource;
    char buf[40];
    int rowH = h / 4;
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        int ry = y + i * rowH, move = m->moves[i];
        if (i) pd_fill(c, x, ry - 1, w, 1, PD_DIVIDER);
        if (!move) continue;
        int type = src && src->moveType ? src->moveType(src->ctx, move) : pd_move_type(g, move);
        pd_fill(c, x, ry + 2, 2, rowH - 5, ui_type_color(type));
        const char* name = src && src->move ? src->move(src->ctx, move) : pd_move_name(g, move, buf, sizeof(buf));
        pd_text_fit(c, x + 5, ry + 1, w - 5, name ? name : "", PD_LABEL, PD_LABEL_SHADOW);
        int power = src ? 0 : pd_move_power(g, move);
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
    int x = MARGIN, y = CONTENT_Y - 2, btnH = 26, full = c->h - y - 2, h = full - btnH - 3;
    int colX = c->w - MARGIN - SUMMARY_COLUMN, w = colX - 3 - x;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    int ix = x + f + 4, iy = y + f + 2, iw = w - 2 * (f + 4), ih = h - 2 * (f + 2);
    int half = iw * 50 / 100, rx = ix + half + 7, rw = iw - half - 7;
    pd_fill(c, ix + half + 3, iy, 1, ih, PD_DIVIDER);

    bool egg = party_egg(ui, m);
    int ty = iy;
    int nameW = pd_text_fit(c, ix, ty, half - 12, mon_display_name(ui, g, m, buf, sizeof(buf)), PD_LABEL, PD_LABEL_SHADOW);
    if (!egg) gender_mark(c, ix + nameW + 2, ty, m->gender);
    if (!egg && m->shiny) pd_text(c, ix + nameW + 13, ty, "*", SHINY_MARK, PD_LABEL_SHADOW);
    ty += 16;
    pd_round_rect(c, ix, ty, PD_ICON + 2, PD_ICON + 2, 3, PD_LIST_FILL);
    party_icon(ui, c, g, m, ix + 1, ty + 1, 1, !egg && m->hp == 0);
    if (egg) {
        pd_text_wrap(c, ix + PD_ICON + 6, ty, half - PD_ICON - 6, 15, 3, "It will hatch in time.", PD_MUTED, PD_MUTED_SHADOW);
    } else {
        int tx = ix + PD_ICON + 6;
        snprintf(buf, sizeof(buf), "Lv%d", m->level);
        int lw = pd_text(c, tx, ty, buf, PD_VALUE, PD_VALUE_SHADOW);
        const char* status = pd_status_label(m->status);
        if (*status) ui_status_badge(c, tx + lw + 4, ty + 1, m->status);
        int t1, t2;
        pd_species_type_pair(g, m->species, &t1, &t2);
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
        if (rate >= 0 && m->level < 100 && m->hasExperience) {
            long at = pd_exp_at(rate, m->level), next = pd_exp_at(rate, m->level + 1);
            char left[20];
            snprintf(left, sizeof(left), "%ld", next - (long) m->experience);
            summary_bar(c, ix, ty, half, "EXP", left, (int) ((long) m->experience - at), (int) (next - at), EXP_BLUE);
            ty += 15;
        }
        if (m->heldItem) {
            int takeW = canTake ? 36 : 0;
            const char* item = pd_item_name(g, m->heldItem);
            pd_text_fit(c, ix, ty, half - takeW, *item ? item : "UNKNOWN ITEM", PD_LABEL, PD_LABEL_SHADOW);
            if (canTake) ui_button(ui, c, ix + half - takeW, ty + 1, takeW, 15, "TAKE", HIT_SUMMARY_TAKE);
            ty += 16;
            // Its description, where the window has the room (the INFO page's on the Linux host's taller canvas).
            const char* desc = pd_item_description(g, m->heldItem);
            if (*desc && iy + ih - ty > 5 * 13) ty += pd_text_wrap(c, ix, ty, half, 13, 2, desc, PD_MUTED, PD_MUTED_SHADOW) * 13 + 2;
        }
        pd_fill(c, ix, ty + 1, half, 1, PD_DIVIDER);
        ty += 4;
        int bottom = iy + ih;
        ty = chips_of(c, "WEAK", t1, t2, 150, 1000, ix, ty, half, bottom);
        ty = chips_of(c, "RESIST", t1, t2, 1, 75, ix, ty, half, bottom);
        chips_of(c, "IMMUNE", t1, t2, 0, 0, ix, ty, half, bottom);
    }
    if (!egg) {
        const struct pd_party_source* src = ui->partySource;
        if (src && src->info && ui->summaryPage == 0) {
            // A backend's own lines (libretro's DS companion).
            for (int i = 0; i < 3; i++) {
                const char* text = src->info(src->ctx, m, i);
                pd_text_fit(c, rx, iy + i * 18, rw, text ? text : "", PD_LABEL, PD_LABEL_SHADOW);
            }
        } else if (ui->summaryInfo) {
            summary_info(c, g, m, rx, iy, rw, iy + ih, true);
        } else if (ui->summaryStats && m->stats.valid) {
            summary_stats(c, m, rx, iy, rw);
        } else {
            summary_moves(ui, c, g, m, rx, iy, rw, ih);
        }
    }

    // The party beside the window, the shown one framed red; under them a row of buttons, Back at its right end.
    int cell = SUMMARY_COLUMN, rows = columnCount;
    if (rows * (cell + 1) > h) cell = h / rows - 1;
    for (int i = 0; i < columnCount; i++) {
        int cy = y + i * (cell + 1);
        bool here = i == shown;
        struct pd_layer redFrame[2] = { { PD_VALUE, 2 }, { PD_VALUE_SHADOW, 1 } };
        pd_layered_box(c, colX, cy, SUMMARY_COLUMN, cell, 3, here ? redFrame : pd_cur->title, here ? 2 : pd_cur->titleCount, PD_TITLE_FILL);
        pd_clip(c, colX + 2, cy + 2, SUMMARY_COLUMN - 4, cell - 4);
        party_icon(ui, c, g, column[i], colX + 1, cy + (cell - PD_ICON) / 2, 1, column[i]->hp == 0 && !party_egg(ui, column[i]));
        pd_unclip(c);
        ui_add_hit(ui, colX, cy, SUMMARY_COLUMN, cell, HIT_SUMMARY_MON + i);
    }
    int by = y + full - btnH, bx = x;
    ui_platinum(ui, c, colX, by, SUMMARY_COLUMN, btnH, 0x4890D8, 0x2860A0, HIT_BACK);
    ui_platinum_back(c, colX, by, SUMMARY_COLUMN, btnH);
    if (pcLabel) {
        int bw = pd_text_width(pcLabel) + 16;
        ui_platinum(ui, c, bx, by, bw, btnH, 0x58A840, 0x307020, HIT_SUMMARY_PC);
        ui_platinum_label(c, bx, by, bw, btnH, pcLabel);
        bx += bw + 3;
    }
    if (m->stats.valid && !egg) {
        const char* label = ui->summaryStats ? "MOVES" : "STATS";
        int bw = pd_text_width("MOVES") + 16;
        ui_platinum(ui, c, bx, by, bw, btnH, STATS_PURPLE, STATS_PURPLE_DARK, HIT_SUMMARY_STATS);
        ui_platinum_label(c, bx, by, bw, btnH, label);
        bx += bw + 3;
    }
    if (!egg) {
        // INFO's pane in the right half; its own button goes back to MOVES.
        const char* label = ui->summaryInfo ? "MOVES" : "INFO";
        int bw = pd_text_width("MOVES") + 16;
        ui_platinum(ui, c, bx, by, bw, btnH, 0x4890D8, 0x2860A0, HIT_SUMMARY_INFO);
        ui_platinum_label(c, bx, by, bw, btnH, label);
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
        if (ui->handheldParty && !ui->boxMoves && !(host && host->canTake)) {
            native_summary_view(ui, c, g, s);
            return;
        }
        const struct pd_mon* column[PD_PARTY_SIZE];
        for (int i = 0; i < s->partyCount; i++) column[i] = &s->party[i];
        ui_mon_summary(ui, c, g, s, &s->party[ui->summarySlot], column, s->partyCount, ui->summarySlot, host && host->canTake,
                       ui->boxMoves ? "DEPOSIT" : NULL);
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
    const struct pd_party_art* art = pd_party_art_for(g);
    struct window_at at[PD_PARTY_SIZE];
    struct card_scale k;
    if (art && card_grid(art->style, c, at, &k)) {
        // The cards; a Pokémon the item can't go on fades into the backdrop.
        static uint32_t empty[PD_UI_WIDTH * PD_UI_HEIGHT];
        memcpy(empty, c->px, (size_t) c->w * c->h * 4);
        struct pd_canvas back;
        pd_canvas_init(&back, c->w, c->h, empty);
        if (draw_cards(&back, g, art, at, &k, s, -1, false) && draw_cards(c, g, art, at, &k, s, -1, true)) {
            for (int i = 0; i < s->partyCount && i < PD_PARTY_SIZE; i++) {
                int x, y, cw, ch;
                card_rect(art->style, &at[i], &k, &x, &y, &cw, &ch);
                if (ui->itemSlots[i].ok) {
                    ui_add_hit(ui, x, y, cw, ch, HIT_ITEM_SLOT + i);
                    continue;
                }
                for (int yy = 0; yy < ch; yy++) memcpy(before + yy * cw, empty + (y + yy) * c->w + x, (size_t) cw * 4);
                ui_fade_rect(c, before, x, y, cw, ch, 110);
            }
            return;
        }
    }
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
    const struct pd_party_art* art = pd_party_art_for(g);
    struct window_at at[PD_PARTY_SIZE];
    struct card_scale k;
    if (art && card_grid(art->style, c, at, &k) && draw_cards(c, g, art, at, &k, s, selected, true)) {
        for (int i = 0; i < s->partyCount && i < PD_PARTY_SIZE; i++) {
            int x, y, cw, ch;
            card_rect(art->style, &at[i], &k, &x, &y, &cw, &ch);
            ui_add_hit(ui, x, y, cw, ch, HIT_SLOT + i);
            // On the card's corner: the name is right under where it sits on a stretched slot.
            if (i == best) slot_tag(c, x + 6, y - 8, cw, "BEST", 0xE8B830);
            else if (out >> i & 1) slot_tag(c, x + 6, y - 8, cw, "OUT", PD_MUTED);
        }
        return;
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

// The party's Poke Ball, 20x20 at 1x: what the HUD draws where a game has no item icons (Ruby / Sapphire; the
// app's PixelIcons.pokeBall in FieldHud).
void pd_draw_party_ball(struct pd_canvas* c, int x, int y) {
    static const uint32_t RED_WHITE[4] = { 0x383840, 0xE04838, 0xA83028, 0xF8F8F8 };
    char_bitmap(c, BALL, 20, x, y, 1, "KRHW", RED_WHITE);
}

void pd_ui_party_view(struct pd_ui* ui,struct pd_canvas* c,const struct pd_game* g,
                      const struct pd_snapshot* s,int selected) {
    ui->hitCount=0;ui->focusId=HIT_SLOT+selected;
    if (!ui->keepBackdrop) pd_backdrop(c);
    ui_party_tab(ui, c, g, s, NULL);
}
