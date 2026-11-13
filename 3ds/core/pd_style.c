#include "pd_style.h"

#include <string.h>

#include "pd_games.h"
#include "pd_gb.h"
#include "pd_icons_gen.h"
#include "pd_ui.h"

const struct pd_look* pd_cur = &pd_themes[0].look[0];
int pd_gb_look;
uint32_t pd_gb_ink = 0x181818, pd_gb_paper = 0xFFFFFF;
uint32_t pd_backdrop_colors[3] = { 0x4AADA5, 0x398C8C, 0x216B63 };

// NIGHT (theme/NightColors.kt): what a dark mode backdrop dims towards.
#define NIGHT 0x12141A

static uint32_t channels(uint32_t a, uint32_t b, int (*f)(int, int, int), int k) {
    uint32_t out = 0;
    for (int sh = 0; sh <= 16; sh += 8) out |= (uint32_t) f((int) (a >> sh & 0xFF), (int) (b >> sh & 0xFF), k) << sh;
    return out;
}

static int multiply(int a, int b, int k) {
    (void) k;
    return a * b / 255;
}

static int mix(int a, int b, int k) {
    return (a * (255 - k) + b * k) / 255;
}

bool pd_dark;

// The wash and scrim over the game's backdrop right now.
static uint32_t backdropWash = 0xFFFFFF;
static int backdropScrim;

uint32_t pd_backdrop_tint(uint32_t c) {
    return channels(channels(c, backdropWash, multiply, 0), NIGHT, mix, backdropScrim);
}

void pd_separator(struct pd_canvas* c, int x, int y, int len, bool vertical) {
    // A Game Boy game's windows: its divider, the rows' own rule.
    uint32_t dark = pd_gb_look ? pd_cur->divider : pd_dark ? 0x0E1116 : 0xB8B8C0;
    uint32_t light = pd_gb_look ? pd_cur->listFill : pd_dark ? 0x3A414D : 0xE8E8EC;
    pd_fill(c, x, y, vertical ? 1 : len, vertical ? len : 1, dark);
    pd_fill(c, x + (vertical ? 1 : 0), y + (vertical ? 0 : 1), vertical ? 1 : len, vertical ? len : 1, light);
}

uint32_t pd_battle_tint(uint32_t c) {
    return channels(c, NIGHT, mix, backdropScrim);
}

// DsBackdrops.kt's BattleBackdrop: HeartGold's battle screens' manner, sampled and drawn here (no game art) - pale mint between
// grey bands, a big faint Poké Ball in the middle, in whole DS-sized pixels (one canvas pixel on the 3DS's 240 rows).
void pd_battle_backdrop(struct pd_canvas* c) {
    static const uint32_t MINT = 0xCBFBF3, RING = 0xBADBCB, THIN = 0xC3E3D3, PEACH = 0xF3D3B2;
    static const struct { int rows; uint32_t color; } BANDS[3] = { { 16, 0xAAAA9A }, { 2, 0xB2C3A2 }, { 2, 0xBADBB2 } };
    int k = c->h / 192 > 1 ? c->h / 192 : 1;
    pd_fill(c, 0, 0, c->w, c->h, pd_backdrop_tint(MINT));
    int edge = 0;
    for (int i = 0; i < 3; i++) {
        int h = BANDS[i].rows * k;
        pd_fill(c, 0, edge, c->w, h, pd_backdrop_tint(BANDS[i].color));
        pd_fill(c, 0, c->h - edge - h, c->w, h, pd_backdrop_tint(BANDS[i].color));
        edge += h;
    }
    int cx = c->w / 2 / k * k, cy = c->h / 2 / k * k;
    const int R = 56, BAND = 5;
    for (int dy = -R; dy <= R; dy++) {
        int radii[5] = { R, R - 5, 20, 15, 10 };
        uint32_t cols[5] = { RING, dy < -BAND ? PEACH : dy > BAND ? MINT : RING, RING, MINT, THIN };
        for (int i = 0; i < 6; i++) {
            int r = i < 5 ? radii[i] : 8;
            uint32_t col = i < 5 ? cols[i] : MINT;
            if (dy * dy >= r * r) continue;
            int half = 0;
            while ((half + 1) * (half + 1) <= r * r - dy * dy) half++;
            if (half > 0) pd_fill(c, cx - half * k, cy + dy * k, half * 2 * k, k, pd_backdrop_tint(col));
        }
    }
}

void pd_look_apply(const struct pd_game* g, const struct pd_settings* s) {
    int dark = s && s->darkMode ? 1 : 0;
    pd_dark = dark;
    const struct pd_theme* theme = &pd_themes[0];
    for (int i = 0; s && i < pd_theme_count; i++)
        if (pd_themes[i].id == s->theme) theme = &pd_themes[i];
    const struct pd_game_entry* e = g ? g->entry : NULL;
    const struct pd_gb_config* gb = g && g->cfg ? g->cfg->gb : NULL;
    pd_gb_look = gb != NULL;
    if (gb) {
        // Gen 1's windows by cart (the app's isYellow / Red and Blue's Super Game Boy colours / PureRGB's), Gen 2's for the rest.
        int k = gb->gen == 2 ? PD_GB_LOOK_GEN2 : gb == &pd_gb_red || gb == &pd_gb_blue ? PD_GB_LOOK_RED :
            gb == &pd_gb_pure_red ? PD_GB_LOOK_PURE : PD_GB_LOOK_YELLOW;
        pd_cur = &pd_gb_looks[k][dark];
        pd_gb_ink = pd_cur->label;
        pd_gb_paper = pd_cur->listFill;
    } else {
        pd_cur = &theme->look[dark];
    }
    pd_party_cur = e ? e->party[dark] : pd_party_default[dark];
    pd_bag_cur = e ? e->bag[dark] : pd_bag_default[dark];
    static const uint32_t FIRERED[3] = { 0x4AADA5, 0x398C8C, 0x216B63 };
    static const uint32_t PLAIN[3] = { 0xFFFFFF, 0xFFFFFF, 0xFFFFFF };
    const uint32_t* base = gb ? PLAIN : e ? e->backdrop : FIRERED;
    int scrim = pd_cur->scrim;
    if (scrim && gb) scrim = 230; // Gen 1's white goes nearly black
    backdropWash = theme->wash;
    backdropScrim = scrim;
    for (int i = 0; i < 3; i++) {
        uint32_t c = base[i];
        if (!(gb)) c = channels(c, theme->wash, multiply, 0);
        pd_backdrop_colors[i] = channels(c, NIGHT, mix, scrim);
    }
}

int pd_title_box(struct pd_canvas* c, int x, int y, int w, int h, uint32_t fill) {
    return pd_layered_box(c, x, y, w, h, 3, pd_cur->title, pd_cur->titleCount, fill);
}

int pd_list_box(struct pd_canvas* c, int x, int y, int w, int h) {
    return pd_layered_box(c, x, y, w, h, 3, pd_cur->list, pd_cur->listCount, PD_LIST_FILL);
}

void pd_backdrop(struct pd_canvas* c) {
    for (int y = 0; y < c->h; y += 8) pd_fill(c, 0, y, c->w, 8, pd_backdrop_colors[(y / 8) % 2]);
    pd_fill(c, 0, c->h - 4, c->w, 4, pd_backdrop_colors[2]);
}

void ui_art_at(struct pd_canvas* c, const struct pd_art* art, int x, int y, int scale) {
    int step = scale < 1 ? 2 : 1, px = scale < 1 ? 1 : scale;
    for (int r = 0; r < art->h; r += step) {
        for (int col = 0; art->rows[r][col]; col += step) {
            const char* k = strchr(art->keys, art->rows[r][col]);
            if (k && art->rows[r][col] != '.') pd_fill(c, x + col / step * px, y + r / step * px, px, px, art->colors[k - art->keys]);
        }
    }
}
