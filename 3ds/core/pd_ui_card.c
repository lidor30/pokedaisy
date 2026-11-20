// TRAINER CARD (TrainerCardScreen.kt): the game's own card drawn from the ROM
// (pd_card), cropped to itself and centred; its play-time colon blinks every
// second like the game's, and a tap flips it over the way the game does -
// squashed shut top and bottom, opened on the other side. It opens from
// SETTINGS (the app keeps it off the tab bar too); BACK goes back there.
#include <string.h>

#include "pd_card.h"
#include "pd_font.h"
#include "pd_ui_internal.h"

#define FLIP_MS 150 // each half of the flip

// The last renders (both colon states of the side shown), cropped, kept until
// the card, its side or the ROM changes - not redrawn every blink.
struct render {
    bool ok;
    unsigned rom;
    struct pd_card_info info;
    bool back;
    int w, h;
    uint32_t px[2][PD_CARD_W * PD_CARD_H]; // [colon]
};
static struct render cache;

static const struct render* rendered(const struct pd_game* g, const struct pd_card_info* info, bool back) {
    unsigned rom = pd_rom_id(g);
    if (cache.rom == rom && cache.back == back && !memcmp(&cache.info, info, sizeof(*info))) {
        return cache.ok ? &cache : NULL;
    }
    cache.rom = rom;
    cache.back = back;
    cache.info = *info;
    cache.ok = false;
    static uint32_t full[PD_CARD_W * PD_CARD_H];
    int l = 0, t = 0, r = PD_CARD_W, b = PD_CARD_H;
    for (int colon = 0; colon < 2; colon++) {
        if (!pd_card_render(g, info, back, colon, false, full)) return NULL;
        if (!colon) pd_card_bounds(full, &l, &t, &r, &b);
        cache.w = r - l;
        cache.h = b - t;
        for (int y = 0; y < cache.h; y++) {
            memcpy(&cache.px[colon][y * cache.w], &full[(t + y) * PD_CARD_W + l], (size_t) cache.w * sizeof(uint32_t));
        }
    }
    cache.ok = true;
    return &cache;
}

void ui_card_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    struct pd_card_info info;
    if (!pd_card_info(g, s, &info)) {
        ui_notice(c, "NO TRAINER CARD", "The card shows once a save is loaded,", "in FireRed, LeafGreen and Emerald.");
        return;
    }
    // Mid-flip the side shown changes at the halfway point.
    bool back = ui->cardBack;
    if (ui->cardFlipMs > FLIP_MS) back = !back;
    const struct render* r = rendered(g, &info, back);
    if (!r) {
        ui_notice(c, "NO TRAINER CARD", "The card's art wasn't found in this ROM.", NULL);
        return;
    }
    const char* caption = pd_tr(ui->cardBack ? "TAP TO SEE THE FRONT" : "TAP TO FLIP THE CARD");
    int areaH = c->h - CONTENT_Y - MARGIN;
    int gap = 6;
    int x = (c->w - r->w) / 2;
    int y = CONTENT_Y + (areaH - r->h - gap - PD_FONT_HEIGHT) / 2;
    // The flip: squashed towards the card's middle row, then opened again.
    int h = r->h;
    if (ui->cardFlipMs > 0) {
        int t = ui->cardFlipMs <= FLIP_MS ? FLIP_MS - ui->cardFlipMs : ui->cardFlipMs - FLIP_MS;
        h = r->h * t / FLIP_MS;
    }
    const uint32_t* px = r->px[ui->cardColon ? 1 : 0];
    int top = y + (r->h - h) / 2;
    for (int dy = 0; dy < h; dy++) {
        int sy = dy * r->h / h;
        int cy = top + dy;
        if (cy < c->cy0 || cy >= c->cy1) continue;
        for (int dx = 0; dx < r->w; dx++) {
            uint32_t v = px[sy * r->w + dx];
            int cx = x + dx;
            if (v && cx >= c->cx0 && cx < c->cx1) c->px[cy * c->w + cx] = v & 0xFFFFFF;
        }
    }
    ui_add_hit(ui, x, y, r->w, r->h, HIT_CARD);
    int cw = pd_text_width(caption);
    pd_text(c, (c->w - cw) / 2, y + r->h + gap, caption, PD_ON_BACKDROP, PD_ON_BACKDROP_SHADOW);
}

bool ui_card_act(struct pd_ui* ui, int id) {
    if (id == HIT_OPEN_CARD) {
        ui->tab = PD_TAB_CARD;
        ui->cardBack = false;
        ui->cardFlipMs = 0;
        // The colon starts shown, a second each way from here.
        ui->cardColon = true;
        ui->cardColonMs = 0;
        return true;
    }
    if (id == HIT_CARD) {
        if (!ui->cardFlipMs) ui->cardFlipMs = 1; // pd_ui_tick runs it
        return true;
    }
    return false;
}

bool ui_card_tick(struct pd_ui* ui, unsigned ms) {
    bool redraw = false;
    // BlinkTimeColon: the colon toggles every 60 frames.
    ui->cardColonMs += ms;
    if (ui->cardColonMs >= 1000) {
        ui->cardColonMs %= 1000;
        ui->cardColon = !ui->cardColon;
        redraw = ui->tab == PD_TAB_CARD && !ui->cardBack;
    }
    if (ui->cardFlipMs > 0) {
        ui->cardFlipMs += (int) ms;
        if (ui->cardFlipMs >= 2 * FLIP_MS) {
            ui->cardFlipMs = 0;
            ui->cardBack = !ui->cardBack;
        }
        redraw = true;
    }
    return redraw && ui->tab == PD_TAB_CARD;
}
