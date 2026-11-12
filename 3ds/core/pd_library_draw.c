// The library's touch screen: the rail of pages, GAMES' list or grid, SETTINGS, and the pieces the save tools share.
// Drawn in the companion's look (FireRed's OPTION screen: white title window, grey list window, the white cursor row, two-tone
// chips) over the companion's own striped backdrop.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "pd_library_internal.h"
#include "pd_ui.h"

// --- hits ---

#define MAX_HITS 160
struct lib_hit_rect { int x, y, w, h, id; };
static struct lib_hit_rect hits[MAX_HITS];
static int hitCount;

void lib_hits_clear(void) { hitCount = 0; }

void lib_hit(int x, int y, int w, int h, int id) {
    if (hitCount < MAX_HITS && w > 0 && h > 0) hits[hitCount++] = (struct lib_hit_rect) { x, y, w, h, id };
}

int lib_hit_at(int x, int y) {
    for (int i = hitCount - 1; i >= 0; i--)
        if (x >= hits[i].x && x < hits[i].x + hits[i].w && y >= hits[i].y && y < hits[i].y + hits[i].h) return hits[i].id;
    return HIT_NONE;
}

// --- art (the app's ToolIcons and LibraryTools' GAMES / SETTINGS, 16 px) ---

#define OUTLINE 0x303038
static const char* const GAMES_ROWS[] = {
    ".kkkkkkkkkkkk...", ".khhhhhhhhhhhk..", ".khsssssssssssk.", ".khkkkkkkkkkksk.", ".khkbbbbbbbbksk.", ".khkbwwwbbbbksk.",
    ".khkbwbbbbbbksk.", ".khkbbbbbyybksk.", ".khkbbbbyyybksk.", ".khkbbbbbbbbksk.", ".khkkkkkkkkkksk.", ".khssssssssssdk.",
    ".khsksksksksddk.", ".khsksksksksddk.", ".kddddddddddddk.", ".kkkkkkkkkkkkkk.",
};
static const uint32_t GAMES_COLORS[] = { OUTLINE, 0xE0E0E8, 0xB4B4C4, 0x84849A, 0x3878E0, 0xB0D0FF, 0xF8D030 };
const struct pd_art LIB_ICON_GAMES = { 16, 16, GAMES_ROWS, "khsdbwy", GAMES_COLORS };

static const char* const EDIT_ROWS[] = {
    "kkkkkkkkkkkkk...", "khhkssssssskbk..", "khbksssskkskbbk.", "kbbksssskkskbbk.", "kbbksssskkskbbk.", "kbbkssssssskbbk.",
    "kbbbkkkkkkkbbbk.", "kbkkkkkkkkkkkbk.", "kbwwwwwwwwwwwbk.", "kbwwwwwwwwwwwbk.", "kbwwlllllllwwbk.", "kbwwwwwwwwwwwbk.",
    "kbwwlllllllwwbk.", "kbwwwwwwwwwwwbk.", "kbwwwwwwwwwwwbk.", "kkkkkkkkkkkkkkk.",
};
static const uint32_t EDIT_COLORS[] = { OUTLINE, 0x3878E0, 0x90B8F8, 0xC0C0D0, 0xF8F8F8, 0xA8A8B8 };
const struct pd_art LIB_ICON_EDIT = { 16, 16, EDIT_ROWS, "kbhswl", EDIT_COLORS };

static const char* const TRADE_ROWS[] = {
    "..........k.....", "..........kk....", "kkkkkkkkkkkok...", "kooooooooooook..", "kkkkkkkkkkkok...", "..........kk....",
    "..........k.....", "................", ".....k..........", "....kk..........", "...kbkkkkkkkkkkk", "..kbbbbbbbbbbbbk",
    "...kbkkkkkkkkkkk", "....kk..........", ".....k..........", "................",
};
static const uint32_t TRADE_COLORS[] = { OUTLINE, 0xF08830, 0x3878E0 };
const struct pd_art LIB_ICON_TRADE = { 16, 16, TRADE_ROWS, "kob", TRADE_COLORS };

static const char* const SETTINGS_ROWS[] = {
    "......kkkk......", "...kk.khhk.kk...", "..khk.khhk.ksk..", ".khhhkhhssksssk.", ".kkhhhhsssssskk.", "...khhskksssk...",
    "kkkhhsk..kssskkk", "khhhsk....kssddk", "khhssk....ksdddk", "kkksssk..ksddkkk", "...kssskksddk...", ".kkssssssddddkk.",
    ".kssskssddkdddk.", "..ksk.kddk.kdk..", "...kk.kddk.kk...", "......kkkk......",
};
static const uint32_t SETTINGS_COLORS[] = { OUTLINE, 0xD8E0EC, 0xA4AEC0, 0x6E788C };
const struct pd_art LIB_ICON_SETTINGS = { 16, 16, SETTINGS_ROWS, "khsd", SETTINGS_COLORS };

// The GAMES cartridge with its label in the console's colour (a hack's red).
void lib_cartridge(struct pd_canvas* c, int x, int y, int scale, enum pd_console console, bool hack) {
    uint32_t label = hack ? 0xE04040 : console == PD_CONSOLE_GBA ? 0x7048C8 : console == PD_CONSOLE_GBC ? 0xE8A820 : 0x38A890;
    uint32_t light = hack ? 0xFF9080 : console == PD_CONSOLE_GBA ? 0xC0A8F8 : console == PD_CONSOLE_GBC ? 0xFFE890 : 0xA8E8D0;
    uint32_t colors[7] = { OUTLINE, 0xE0E0E8, 0xB4B4C4, 0x84849A, label, light, 0xF8D030 };
    struct pd_art art = { 16, 16, GAMES_ROWS, "khsdbwy", colors };
    ui_art_at(c, &art, x, y, scale);
}

// --- pieces ---

// --- art at 24 px: Scale3x, then every other (the logo every fourth) pixel - halving the art broke its outlines ---

static char at3(const struct pd_art* a, int x, int y) {
    x = x < 0 ? 0 : x >= a->w ? a->w - 1 : x;
    y = y < 0 ? 0 : y >= a->h ? a->h - 1 : y;
    return a->rows[y][x];
}

// One pixel of [a] scaled 3x by Scale3x (AdvMAME3x): sub-pixel (sx, sy) of source pixel (x, y).
static char scale3x(const struct pd_art* a, int x, int y, int sx, int sy) {
    char A = at3(a, x - 1, y - 1), B = at3(a, x, y - 1), C = at3(a, x + 1, y - 1), D = at3(a, x - 1, y), E = at3(a, x, y),
         F = at3(a, x + 1, y), G = at3(a, x - 1, y + 1), H = at3(a, x, y + 1), I = at3(a, x + 1, y + 1);
    if (B == H || D == F) return E;
    char e[9] = {
        D == B ? D : E, (D == B && E != C) || (B == F && E != A) ? B : E, B == F ? F : E,
        (D == B && E != G) || (D == H && E != A) ? D : E, E, (B == F && E != I) || (H == F && E != C) ? F : E,
        D == H ? D : E, (D == H && E != I) || (H == F && E != G) ? H : E, H == F ? F : E,
    };
    return e[sy * 3 + sx];
}

#define SMALL 24
// A 24 px picture drawn smooth: Scale3x, then each block averaged (2x2 for the 16 px icons, 4x4 for the 32 px logo), the
// colour of what's there and how much of the block it covers - soft edges, like the 3DS's own menus, rather than a pixel
// picked from each block.
struct small_art { uint32_t argb[SMALL * SMALL]; };

static void make_small(struct small_art* out, const struct pd_art* a) {
    int block = a->w * 3 / SMALL;
    for (int y = 0; y < SMALL; y++) {
        for (int x = 0; x < SMALL; x++) {
            int r = 0, g = 0, b = 0, n = 0;
            for (int dy = 0; dy < block; dy++) {
                for (int dx = 0; dx < block; dx++) {
                    int bx = x * block + dx, by = y * block + dy;
                    char ch = scale3x(a, bx / 3, by / 3, bx % 3, by % 3);
                    const char* k = ch == '.' ? NULL : strchr(a->keys, ch);
                    if (!k) continue;
                    uint32_t c = a->colors[k - a->keys];
                    r += (int) (c >> 16 & 0xFF);
                    g += (int) (c >> 8 & 0xFF);
                    b += (int) (c & 0xFF);
                    n++;
                }
            }
            out->argb[y * SMALL + x] = n ? (uint32_t) (n * 255 / (block * block)) << 24 | (uint32_t) (r / n) << 16 | (uint32_t) (g / n) << 8 | (uint32_t) (b / n) : 0;
        }
    }
}

static struct small_art smallIcons[PD_PAGE_COUNT], smallLogo;
static bool smallMade;

static void make_smalls(void) {
    if (smallMade) return;
    static const struct pd_art* const ART[PD_PAGE_COUNT] = { &LIB_ICON_GAMES, &LIB_ICON_EDIT, &LIB_ICON_TRADE, &LIB_ICON_SETTINGS };
    for (int i = 0; i < PD_PAGE_COUNT; i++) make_small(&smallIcons[i], ART[i]);
    make_small(&smallLogo, &PD_LOGO);
    smallMade = true;
}

static void draw_small(struct pd_canvas* c, const struct small_art* art, int x0, int y0) {
    for (int y = 0; y < SMALL; y++) {
        int py = y0 + y;
        if (py < c->cy0 || py >= c->cy1) continue;
        for (int x = 0; x < SMALL; x++) {
            int px = x0 + x;
            uint32_t v = art->argb[y * SMALL + x];
            if (!(v >> 24) || px < c->cx0 || px >= c->cx1) continue;
            uint32_t* d = &c->px[py * c->w + px];
            *d = pd_mix(*d, v & 0xFFFFFF, (int) (v >> 24));
        }
    }
}

void lib_draw_icon_24(struct pd_canvas* c, int page, int x, int y) {
    make_smalls();
    draw_small(c, &smallIcons[page], x, y);
}

void lib_draw_logo_24(struct pd_canvas* c, int x, int y) {
    make_smalls();
    draw_small(c, &smallLogo, x, y);
}

// --- the PokéDaisy page (the app's DaisyBackdrop, still): its light page, three soft blobs, a faint dot grid and the mark
// floating faintly where the site's petals are; at night the blobs glow plum, amber and indigo on ink. Worked out once a
// size and kept: per pixel it's too slow to redraw. ---

struct blob { int x, y, yw, r; uint32_t day, night; };
static const struct blob BLOBS[3] = {
    { 11, 0, 9, 23, 0xFFD2CC, 0x6A2F45 }, { 90, 18, 20, 20, 0xFFF0B0, 0x6A4418 }, { 45, 100, 5, 25, 0xD6E2FF, 0x233A7A },
};
static const struct { int x, y, scale; } PETALS[6] = { { 4, 22, 1 }, { 91, 10, 2 }, { 70, 62, 1 }, { 10, 78, 2 }, { 46, 92, 1 }, { 95, 70, 1 } };

#define PAGE_DAY 0xF7F6FB
#define PAGE_NIGHT 0x12141C

static uint32_t pageTop[400 * 240], pageBottom[320 * 240];
static int pageTopKey = -1, pageBottomKey = -1;

static void make_page(uint32_t* px, int w, int h, bool night) {
    uint32_t page = night ? PAGE_NIGHT : PAGE_DAY;
    int big = (w > h ? w : h);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            uint32_t c = page;
            for (int i = 0; i < 3; i++) {
                const struct blob* b = &BLOBS[i];
                // The site's blur(80px) at 55%: full inside the blob's radius, fading out over as much again.
                int bx = b->x * w / 100, by = b->y * h / 100 + b->yw * big / 100, r = b->r * big / 100, reach = r * 2;
                int dx = x - bx, dy = y - by, d2 = dx * dx + dy * dy;
                if (d2 >= reach * reach) continue;
                int k;
                if (d2 <= r * r) k = 140;
                else {
                    int d = 0;
                    while ((d + 1) * (d + 1) <= d2) d++;
                    k = 140 * (reach - d) / (reach - r);
                }
                c = pd_mix(c, night ? b->night : b->day, k);
            }
            // The dot grid: a pixel of ink at 9% every 12.
            if (x % 12 == 0 && y % 12 == 0) c = pd_mix(c, night ? 0xFFFFFF : 0x1D1B26, night ? 16 : 23);
            px[y * w + x] = c;
        }
    }
    // The mark, faint, at the petals' places.
    for (int i = 0; i < 6; i++) {
        int s = PETALS[i].scale, x0 = PETALS[i].x * w / 100 - 16 * s, y0 = PETALS[i].y * h / 100 - 16 * s;
        for (int r = 0; r < PD_LOGO.h * s; r++) {
            for (int col = 0; col < PD_LOGO.w * s; col++) {
                int x = x0 + col, y = y0 + r;
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                char ch = PD_LOGO.rows[r / s][col / s];
                const char* k = ch == '.' ? NULL : strchr(PD_LOGO.keys, ch);
                if (k) px[y * w + x] = pd_mix(px[y * w + x], PD_LOGO.colors[k - PD_LOGO.keys], night ? 28 : 41);
            }
        }
    }
}

void lib_page_backdrop(struct pd_canvas* c) {
    bool top = c->w == 400;
    uint32_t* page = top ? pageTop : pageBottom;
    int* key = top ? &pageTopKey : &pageBottomKey;
    if ((c->w != 400 && c->w != 320) || c->h != 240) {
        pd_fill(c, 0, 0, c->w, c->h, pd_dark ? PAGE_NIGHT : PAGE_DAY);
        return;
    }
    if (*key != pd_dark) {
        make_page(page, c->w, c->h, pd_dark);
        *key = pd_dark;
    }
    memcpy(c->px, page, sizeof(uint32_t) * (size_t) c->w * (size_t) c->h);
}

int lib_battery = -1;

int lib_battery_width(void) {
    if (lib_battery < 0) return 0;
    char pct[8];
    snprintf(pct, sizeof pct, "%d%%", lib_battery > 100 ? 100 : lib_battery);
    return 24 + pd_text_width(pct);
}

// The battery, as the companion's SETTINGS title has it: its level drawn, then the percentage, ending at [end].
static void battery_at(struct pd_canvas* c, int end, int ty) {
    if (lib_battery < 0) return;
    int pct = lib_battery > 100 ? 100 : lib_battery, x = end - lib_battery_width();
    char text[8];
    snprintf(text, sizeof text, "%d%%", pct);
    pd_fill(c, x, ty + 5, 18, 8, PD_TITLE_TEXT);
    pd_fill(c, x + 1, ty + 6, 16, 6, PD_TITLE_FILL);
    pd_fill(c, x + 2, ty + 7, 14 * pct / 100, 4, pct <= 15 ? PD_VALUE : PD_TITLE_TEXT);
    pd_fill(c, x + 18, ty + 7, 2, 4, PD_TITLE_TEXT);
    pd_text(c, end - pd_text_width(text), ty, text, PD_TITLE_TEXT, PD_TITLE_SHADOW);
}

void lib_battery_box(struct pd_canvas* c, int x, int y, int w, int h) {
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    battery_at(c, x + w - f - 4, pd_text_y(y, h));
}

int lib_title(struct pd_canvas* c, const char* title, const char* right, bool logo) {
    int x = PAGE_X, w = PAGE_W;
    int f = pd_title_box(c, x, TITLE_Y, w, TITLE_H, PD_TITLE_FILL);
    int tx = x + f + 4, ty = pd_text_y(TITLE_Y, TITLE_H);
    (void) logo; // the logo sits under the rail, at its own size
    int end = x + w - f - 4;
    if (lib_battery >= 0) {
        battery_at(c, end, ty);
        end -= lib_battery_width() + 6;
    }
    int rw = right ? pd_text_width(right) + 6 : 0;
    pd_text_fit(c, tx, ty, end - rw - tx, title, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    if (right) pd_text_right(c, end, ty, right, PD_VALUE, PD_VALUE_SHADOW);
    return f;
}

void lib_button(struct pd_canvas* c, int x, int y, int w, int h, const char* label, bool on, int hit) {
    pd_layered_two_tone(c, x, y, w, h, 3, pd_cur->chip, pd_cur->chipCount, on ? pd_cur->tabSelectedFill : pd_cur->tabIdleFill, 0);
    int tw = pd_text_width(label);
    pd_text_fit(c, x + (w - tw > 8 ? (w - tw) / 2 : 4), pd_text_y(y, h), w - 8, label, on ? PD_VALUE : PD_LABEL,
                on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
    if (hit) lib_hit(x, y, w, h, hit);
}

void lib_dim(struct pd_canvas* c) {
    pd_dim(c, 150);
}

void lib_dialog(struct pd_canvas* c, const char* title, const char* line1, const char* line2, const char* yes, const char* no) {
    lib_dim(c);
    lib_hit(0, 0, LIB_W, LIB_H, HIT_MESSAGE); // a tap outside the buttons is the dialog's, not what's under it
    int w = 260, x = (LIB_W - w) / 2, lines = (line1 ? 1 : 0) + (line2 ? 1 : 0);
    int h = 30 + lines * 16 + ((yes || no) ? BUTTON_H + 12 : 8), y = (LIB_H - h) / 2;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    pd_text(c, x + f + 6, y + f + 4, title, PD_VALUE, PD_VALUE_SHADOW);
    int ty = y + f + 22;
    if (line1) pd_text_fit(c, x + f + 6, ty, w - 2 * f - 12, line1, PD_LABEL, PD_LABEL_SHADOW), ty += 16;
    if (line2) pd_text_fit(c, x + f + 6, ty, w - 2 * f - 12, line2, PD_LABEL, PD_LABEL_SHADOW);
    int by = y + h - f - BUTTON_H - 4, bw = 84;
    if (yes && no) {
        lib_button(c, x + w / 2 - bw - 6, by, bw, BUTTON_H, yes, true, HIT_YES);
        lib_button(c, x + w / 2 + 6, by, bw, BUTTON_H, no, false, HIT_NO);
    } else if (yes || no) {
        lib_button(c, x + (w - bw) / 2, by, bw, BUTTON_H, yes ? yes : no, true, yes ? HIT_YES : HIT_NO);
    }
}

// --- the rail ---


void lib_rail(struct pd_library* l, struct pd_canvas* c) {
    // Docked to the screen's edge like the app's rail: its window's frame only on the side facing the page. No frame round the
    // items (the app's NavItem): the open page is a white block, the rest the window's own grey.
    int f = pd_list_box(c, -6, -6, RAIL_W + 6, LIB_H + 12);
    // Centred in what shows of the panel (its frame is on the right only).
    const int inner = RAIL_W - f, x = 4, w = inner - 8, h = 34, gap = 4;
    for (int i = 0; i < PD_PAGE_COUNT; i++) {
        int y = 6 + i * (h + gap);
        bool on = l->page == i, pressed = l->pressedHit == HIT_RAIL + i;
        if (on || pressed) pd_round_rect(c, x, y, w, h, 3, pressed && !on ? pd_mix(PD_TITLE_FILL, PD_LIST_FILL, 128) : PD_TITLE_FILL);
        lib_draw_icon_24(c, i, x + (w - SMALL) / 2, y + (h - SMALL) / 2);
        lib_hit(0, y - gap / 2, RAIL_W, h + gap, HIT_RAIL + i);
    }
    lib_draw_logo_24(c, (inner - SMALL) / 2, LIB_H - SMALL - 8);
}

// --- the game list ---

#define LEAD_CACHE 24
static struct { int game; unsigned used; bool has; int hours, minutes; uint32_t px[PD_CARD_ICON * PD_CARD_ICON]; } leads[LEAD_CACHE];
static const struct pd_library* leadsOf;
static unsigned leadClock;

void lib_draw_reset(void) {
    leadsOf = NULL;
}

static int lead_slot(struct pd_library* l, int game) {
    if (leadsOf != l) {
        for (int i = 0; i < LEAD_CACHE; i++) leads[i].game = -1;
        leadsOf = l;
    }
    int oldest = 0;
    for (int i = 0; i < LEAD_CACHE; i++) {
        if (leads[i].game == game) {
            leads[i].used = ++leadClock;
            return i;
        }
        if (leads[i].used < leads[oldest].used) oldest = i;
    }
    static struct pd_game_card card;
    leads[oldest].game = game;
    leads[oldest].used = ++leadClock;
    leads[oldest].has = false;
    const struct pd_library_host* h = l->host;
    if (h && h->read_card && h->read_card(h->ctx, l->games[game].file, &card) && card.magic == PD_CARD_MAGIC) {
        leads[oldest].hours = card.playHours;
        leads[oldest].minutes = card.playMinutes;
        if (card.partyCount > 0) {
            memcpy(leads[oldest].px, card.icons[0], sizeof leads[oldest].px);
            leads[oldest].has = true;
        } else {
            leads[oldest].hours = -1;
        }
    } else {
        leads[oldest].hours = -1;
    }
    return oldest;
}

const uint32_t* lib_lead_icon(struct pd_library* l, int game) {
    int i = lead_slot(l, game);
    return leads[i].has ? leads[i].px : NULL;
}

static void draw_icon(struct pd_canvas* c, const uint32_t* px, int x, int y) {
    for (int r = 0; r < PD_CARD_ICON; r++) {
        int run = -1;
        for (int col = 0; col <= PD_CARD_ICON; col++) {
            bool on = col < PD_CARD_ICON && px[r * PD_CARD_ICON + col];
            if (on && (run < 0 || px[r * PD_CARD_ICON + col] != px[r * PD_CARD_ICON + run])) {
                if (run >= 0) pd_fill(c, x + run, y + r, col - run, 1, px[r * PD_CARD_ICON + run] & 0xFFFFFF);
                run = col;
            } else if (!on && run >= 0) {
                pd_fill(c, x + run, y + r, col - run, 1, px[r * PD_CARD_ICON + run] & 0xFFFFFF);
                run = -1;
            }
        }
    }
}

const char* lib_console_tag(const struct pd_library_game* g) {
    return g->console == PD_CONSOLE_GBA ? "GBA" : g->console == PD_CONSOLE_GBC ? "GBC" : "GB";
}

// A small tag: [text] in a rounded box of [color].
static int tag(struct pd_canvas* c, int x, int y, const char* text, uint32_t color) {
    int w = pd_text_width(text) + 6;
    pd_round_rect(c, x, y, w, 13, 2, color);
    pd_text(c, x + 3, y - 2, text, 0xFFFFFF, PD_NO_SHADOW);
    return w;
}

static uint32_t console_color(const struct pd_library_game* g) {
    return g->console == PD_CONSOLE_GBA ? 0x7048C8 : g->console == PD_CONSOLE_GBC ? 0xD89810 : 0x309880;
}

static bool listFollow = true;

// The scroll bar on the page now (one at a time) and its drag.
static struct lib_scroll bar;
static bool barShown;
#define BAR_W 6

void lib_scroll_bar(struct pd_canvas* c, int x, int y, int h, int total, int view, int* scroll) {
    barShown = total > view;
    if (!barShown) return;
    bar = (struct lib_scroll) { x, y, h, total, view, scroll };
    int th = h * view / total;
    if (th < 16) th = 16;
    int ty = y + (h - th) * *scroll / (total - view);
    // The track, then the thumb as a small two-tone button (easy to catch with the stylus).
    pd_round_rect(c, x, y, BAR_W, h, 3, pd_dark ? 0x2A2E36 : 0xD0D0D8);
    pd_round_rect(c, x, ty, BAR_W, th, 3, PD_VALUE);
    pd_fill(c, x + 1, ty + 2, 1, th - 4, pd_mix(PD_VALUE, 0xFFFFFF, 110));
    // Its hit is wider than it looks.
    lib_hit(x - 8, y, BAR_W + 12, h, HIT_SCROLL);
}

bool lib_scroll_drag(int touchY) {
    if (!barShown || !bar.scroll) return false;
    int th = bar.h * bar.view / bar.total;
    if (th < 16) th = 16;
    int room = bar.h - th;
    int at = touchY - bar.y - th / 2;
    if (at < 0) at = 0;
    if (at > room) at = room;
    *bar.scroll = room ? at * (bar.total - bar.view) / room : 0;
    return true;
}

void lib_list_move(struct lib_list* list, int dx, int dy, int columns, int viewH, bool grid) {
    (void) viewH;
    int n = list->count;
    if (!n) return;
    int at = list->selected + dx + dy * (grid ? columns : 1);
    if (at < 0) at = 0;
    if (at >= n) at = n - 1;
    list->selected = at;
    listFollow = true;
}

int lib_list_hit(const struct lib_list* list, int hit) {
    int i = hit - HIT_ROW;
    return hit >= HIT_ROW && i < list->count ? i : -1;
}

static void row(struct pd_library* l, struct pd_canvas* c, const struct pd_library_game* g, int game, int x, int y, int w, bool on) {
    if (on) pd_fill(c, x - 3, y, w + 6, LIST_ROW_H, PD_ROW_SELECTED);
    const uint32_t* lead = lib_lead_icon(l, game);
    if (lead) draw_icon(c, lead, x - 2, y - 3);
    else lib_cartridge(c, x + 6, y + 7, 1, g->console, g->hack);
    int tx = x + 34;
    pd_text_fit(c, tx, y + 1, x + w - tx, g->title, g->supported || g->console != PD_CONSOLE_GBA ? PD_LABEL : PD_MUTED, PD_LABEL_SHADOW);
    int ty = y + 16;
    tx += tag(c, tx, ty, lib_console_tag(g), console_color(g)) + 3;
    if (g->hack) tx += tag(c, tx, ty, "HACK", 0xD83838) + 3;
    if (!g->named && g->console == PD_CONSOLE_GBA) tx += tag(c, tx, ty, "...", 0x909098) + 3;
    int i = lead_slot(l, game);
    if (leads[i].hours >= 0) {
        char time[16];
        snprintf(time, sizeof time, "%d:%02d", leads[i].hours, leads[i].minutes);
        pd_text_right(c, x + w, ty - 2, time, PD_MUTED, PD_MUTED_SHADOW);
    }
}

static void tile(struct pd_library* l, struct pd_canvas* c, const struct pd_library_game* g, int game, int x, int y, int w, bool on) {
    int f = pd_title_box(c, x, y, w, TILE_H, on ? PD_ROW_SELECTED : PD_LIST_FILL);
    if (on) {
        // The cursor: the value colour round the tile, like the companion's D-pad ring.
        pd_fill(c, x, y, w, 2, PD_VALUE);
        pd_fill(c, x, y + TILE_H - 2, w, 2, PD_VALUE);
        pd_fill(c, x, y, 2, TILE_H, PD_VALUE);
        pd_fill(c, x + w - 2, y, 2, TILE_H, PD_VALUE);
    }
    const uint32_t* lead = lib_lead_icon(l, game);
    if (lead) draw_icon(c, lead, x + (w - 32) / 2, y + f + 2);
    else lib_cartridge(c, x + (w - 32) / 2, y + f + 4, 2, g->console, g->hack);
    tag(c, x + f + 2, y + f + 2, lib_console_tag(g), console_color(g));
    if (g->hack) tag(c, x + w - f - 2 - pd_text_width("HACK") - 6, y + f + 2, "HACK", 0xD83838);
    // The name without the "Pokémon" every game shares (the tile is narrow).
    const char* title = !strncmp(g->title, "Pokémon ", strlen("Pokémon ")) ? g->title + strlen("Pokémon ") : g->title;
    int tw = pd_text_width(title);
    int tx = tw < w - 2 * f - 4 ? x + (w - tw) / 2 : x + f + 2;
    pd_text_fit(c, tx, y + TILE_H - f - 17, w - 2 * f - 4, title, PD_LABEL, PD_LABEL_SHADOW);
}

void lib_list_draw(struct pd_library* l, struct pd_canvas* c, struct lib_list* list, int y, int h, bool grid, const char* empty1,
                   const char* empty2) {
    int x = PAGE_X, w = PAGE_W;
    if (!list->count) {
        int f = pd_list_box(c, x, y, w, h);
        pd_text_fit(c, x + f + 6, y + f + 8, w - 2 * f - 12, empty1, PD_LABEL, PD_LABEL_SHADOW);
        if (empty2) pd_text_fit(c, x + f + 6, y + f + 26, w - 2 * f - 12, empty2, PD_VALUE, PD_VALUE_SHADOW);
        return;
    }
    if (list->selected >= list->count) list->selected = list->count - 1;
    if (!grid) {
        int f = pd_list_box(c, x, y, w, h);
        int top = y + f + 2, view = h - 2 * f - 4, total = list->count * LIST_ROW_H;
        if (listFollow) {
            int at = list->selected * LIST_ROW_H;
            if (at < list->scroll) list->scroll = at;
            if (at + LIST_ROW_H > list->scroll + view) list->scroll = at + LIST_ROW_H - view;
        }
        if (list->scroll > total - view) list->scroll = total - view;
        if (list->scroll < 0) list->scroll = 0;
        pd_clip(c, x + f, top, w - 2 * f, view);
        for (int i = 0; i < list->count; i++) {
            int ry = top + i * LIST_ROW_H - list->scroll;
            if (ry + LIST_ROW_H <= top || ry >= top + view) continue;
            int game = list->items[i];
            row(l, c, &l->games[game], game, x + f + 5, ry, w - 2 * f - 10 - (total > view ? BAR_W + 4 : 0), i == list->selected);
            int vy = ry < top ? top : ry, vh = (ry + LIST_ROW_H > top + view ? top + view : ry + LIST_ROW_H) - vy;
            lib_hit(x + f, vy, w - 2 * f, vh, HIT_ROW + i);
        }
        pd_unclip(c);
        lib_scroll_bar(c, x + w - f - BAR_W - 2, top + 1, view - 2, total, view, &list->scroll);
    } else {
        const int gap = 4, cols = l->columns;
        int rows = (list->count + cols - 1) / cols, total = rows * (TILE_H + gap) - gap, view = h;
        int gw = total > view ? w - BAR_W - 4 : w;
        int colW = (gw - gap * (cols - 1)) / cols;
        if (listFollow) {
            int at = list->selected / cols * (TILE_H + gap);
            if (at < list->scroll) list->scroll = at;
            if (at + TILE_H > list->scroll + view) list->scroll = at + TILE_H - view;
        }
        if (list->scroll > total - view) list->scroll = total - view;
        if (list->scroll < 0) list->scroll = 0;
        pd_clip(c, x, y, w, view);
        for (int i = 0; i < list->count; i++) {
            int ty = y + i / cols * (TILE_H + gap) - list->scroll;
            if (ty + TILE_H <= y || ty >= y + view) continue;
            int game = list->items[i], tx = x + (i % cols) * (colW + gap);
            tile(l, c, &l->games[game], game, tx, ty, colW, i == list->selected);
            int vy = ty < y ? y : ty, vh = (ty + TILE_H > y + view ? y + view : ty + TILE_H) - vy;
            lib_hit(tx, vy, colW, vh, HIT_ROW + i);
        }
        pd_unclip(c);
        lib_scroll_bar(c, x + w - BAR_W, y, view, total, view, &list->scroll);
    }
    listFollow = false;
}

// --- GAMES ---

static const char* const VIEW_NAMES[PD_VIEW_COUNT] = { "ALL", "RECENT", "GBA", "GB", "HACKS" };
// Each view filled with its tag's colour (GBA's purple, GB's teal, a hack's red), RECENT a blue, ALL a slate.
static const uint32_t VIEW_COLORS[PD_VIEW_COUNT] = { 0x687088, 0x3878E0, 0x7048C8, 0x309880, 0xD83838 };

// A view's chip in its tag's colour, white letters like the tags'; the picked one keeps its colour inside a white ring (the
// app's RAINBOW tabs).
static void view_chip(struct pd_canvas* c, int x, int y, int w, int h, int view, bool on, int hit) {
    uint32_t color = VIEW_COLORS[view];
    pd_layered_two_tone(c, x, y, w, h, 3, pd_cur->chip, pd_cur->chipCount, color, 0);
    if (on) {
        int f = 2;
        pd_fill(c, x + f, y + f, w - 2 * f, 2, 0xFFFFFF);
        pd_fill(c, x + f, y + h - f - 2, w - 2 * f, 2, 0xFFFFFF);
        pd_fill(c, x + f, y + f, 2, h - 2 * f, 0xFFFFFF);
        pd_fill(c, x + w - f - 2, y + f, 2, h - 2 * f, 0xFFFFFF);
    }
    int tw = pd_text_width(VIEW_NAMES[view]);
    pd_text_fit(c, x + (w - tw > 8 ? (w - tw) / 2 : 4), pd_text_y(y, h), w - 8, VIEW_NAMES[view], 0xFFFFFF, pd_mix(color, 0x000000, 100));
    lib_hit(x, y, w, h, hit);
}

static void games_page(struct pd_library* l, struct pd_canvas* c) {
    lib_title(c, "POKéDAISY", NULL, true);
    // The views as chips under the title, sharing its width (L / R step them).
    int y = TITLE_Y + TITLE_H + LIB_M, h = 20, gap = 3, x = PAGE_X;
    int room = PAGE_W - gap * (PD_VIEW_COUNT - 1);
    for (int i = 0; i < PD_VIEW_COUNT; i++) {
        int w = room / PD_VIEW_COUNT + (i < room % PD_VIEW_COUNT ? 1 : 0);
        view_chip(c, x, y, w, h, i, l->view == i || l->pressedHit == HIT_VIEW + i, HIT_VIEW + i);
        x += w + gap;
    }
    int top = y + h + LIB_M;
    bool known = l->count > 0;
    lib_list_draw(l, c, &l->list, top, LIB_H - top - LIB_M, lib_grid(l), known ? "No games here." : "No games yet.",
                  known ? NULL : "Put them in /pokedaisy/roms/");
}

// --- SETTINGS ---

void lib_settings_draw(struct pd_library* l, struct pd_canvas* c) {
    lib_title(c, "SETTINGS", NULL, false);
    int x = PAGE_X, y = TITLE_Y + TITLE_H + LIB_M, w = PAGE_W, h = LIB_H - y - LIB_M;
    int f = pd_list_box(c, x, y, w, h);
    int rows = lib_settings_rows(l), rowH = 18, top = y + f + 2, view = h - 2 * f - 4, total = rows * rowH;
    if (l->pickRow < 0 && l->settingsFollow) {
        int at = l->settingsSelected * rowH;
        if (at < l->settingsScroll) l->settingsScroll = at - (l->settingsSelected == 1 ? rowH : 0);
        if (at + rowH > l->settingsScroll + view) l->settingsScroll = at + rowH - view;
    }
    if (l->settingsScroll > total - view) l->settingsScroll = total - view;
    if (l->settingsScroll < 0) l->settingsScroll = 0;
    l->settingsFollow = false;
    pd_clip(c, x + f, top, w - 2 * f, view);
    int lx = x + f + 6, lw = w - 2 * f - 12 - (total > view ? BAR_W + 4 : 0);
    for (int r = 0; r < rows; r++) {
        int ry = top + r * rowH - l->settingsScroll;
        if (ry + rowH <= top || ry >= top + view) continue;
        const char* value;
        bool group;
        int choices;
        const char* label = lib_settings_label(l, r, &value, &group, &choices);
        if (group) {
            if (pd_cur->groupTitleFill != PD_NO_SHADOW) pd_fill(c, lx - 4, ry + 1, pd_text_width(label) + 8, rowH - 2, pd_cur->groupTitleFill);
            pd_text(c, lx, pd_text_y(ry, rowH), label, pd_cur->groupTitleText, pd_cur->groupTitleShadow);
            continue;
        }
        bool on = r == l->settingsSelected || l->pressedHit == HIT_SETTING + r;
        if (on) pd_fill(c, lx - 4, ry, lw + 8, rowH, PD_ROW_SELECTED);
        pd_text(c, lx, pd_text_y(ry, rowH), label, PD_LABEL, PD_LABEL_SHADOW);
        int vx = lx + lw * 50 / 100;
        bool info = r >= lib_settings_rows(l) - (l->host && l->host->devLine ? 2 : 1);
        pd_text_fit(c, vx, pd_text_y(ry, rowH), lx + lw - vx - (choices ? 8 : 0), value ? value : "", info ? PD_MUTED : PD_VALUE,
                    info ? PD_MUTED_SHADOW : PD_VALUE_SHADOW);
        // A pick-list's row: a chevron at its end (the app's opensPage arrow).
        if (choices) pd_triangle(c, lx + lw - 5, pd_text_y(ry, rowH) + 6, 4, PD_RIGHT, PD_VALUE);
        int vy = ry < top ? top : ry, vh = (ry + rowH > top + view ? top + view : ry + rowH) - vy;
        lib_hit(x + f, vy, w - 2 * f, vh, HIT_SETTING + r);
    }
    pd_unclip(c);
    lib_scroll_bar(c, x + w - f - BAR_W - 2, top + 1, view - 2, total, view, &l->settingsScroll);
    if (l->pickRow < 0) return;
    // The pick-list (OptionSelector): the setting's choices over the dimmed page, the current one's value red.
    const char* value;
    bool group;
    int choices;
    const char* label = lib_settings_label(l, l->pickRow, &value, &group, &choices);
    lib_dim(c);
    lib_hit(0, 0, LIB_W, LIB_H, HIT_PICK_CLOSE);
    int pw = 200, ph = 30 + choices * rowH + 10;
    if (ph > LIB_H - 16) ph = LIB_H - 16;
    int px = (LIB_W - pw) / 2 + RAIL_W / 2, py = (LIB_H - ph) / 2;
    int tf = pd_title_box(c, px, py, pw, 24, PD_TITLE_FILL);
    pd_text(c, px + tf + 6, pd_text_y(py, 24), label, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    int ly = py + 27, lh = ph - 27;
    int lf = pd_list_box(c, px, ly, pw, lh);
    int rowsShown = (lh - 2 * lf - 4) / rowH, first = 0;
    if (l->pickSelected >= rowsShown) first = l->pickSelected - rowsShown + 1;
    for (int i = first; i < choices && i < first + rowsShown; i++) {
        int ry = ly + lf + 2 + (i - first) * rowH;
        const char* name = lib_settings_choice(l, l->pickRow, i);
        bool on = i == l->pickSelected || l->pressedHit == HIT_PICK + i;
        if (on) pd_fill(c, px + lf + 2, ry, pw - 2 * lf - 4, rowH, PD_ROW_SELECTED);
        bool current = value && !strcmp(name, value);
        pd_text_fit(c, px + lf + 8, pd_text_y(ry, rowH), pw - 2 * lf - 16, name, current ? PD_VALUE : PD_LABEL,
                    current ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        lib_hit(px + lf, ry, pw - 2 * lf, rowH, HIT_PICK + i);
    }
}

// --- the screen ---

void pd_library_draw(struct pd_library* l, struct pd_canvas* c) {
    lib_hits_clear();
    lib_battery = l->batteryPct;
    pd_font_set(NULL, 0); // the library's own font (a Game Boy game leaves its own installed)
    barShown = false;
    pd_look_apply(NULL, l->settings);
    lib_page_backdrop(c);
    lib_rail(l, c);
    switch (l->page) {
    case PD_PAGE_GAMES: games_page(l, c); break;
    case PD_PAGE_SETTINGS: lib_settings_draw(l, c); break;
    default: lib_saves_draw(l, c); break;
    }
    l->dirty = false;
}
