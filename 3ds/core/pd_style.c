#include "pd_style.h"

static const struct pd_layer TITLE_LAYERS[] = { { 0x63737B, 2 }, { 0xCED6D6, 1 } };
static const struct pd_layer LIST_LAYERS[] = {
    { 0x293131, 1 }, { 0x8C8CCE, 1 }, { 0x736B84, 2 }, { 0xDED6DE, 1 }, { 0xFFFFFF, 2 },
};
// A Game Boy window: ink outline, a paper gap, ink again (the games' double-line box).
static const struct pd_layer GB_LAYERS[] = { { 0x181818, 1 }, { 0xFFFFFF, 1 }, { 0x181818, 1 } };
int pd_gb_look;
uint32_t pd_gb_ink = 0x181818, pd_gb_paper = 0xFFFFFF;
#define N_LAYERS(a) ((int) (sizeof(a) / sizeof((a)[0])))

int pd_title_box(struct pd_canvas* c, int x, int y, int w, int h, uint32_t fill) {
    if (pd_gb_look) return pd_layered_box(c, x, y, w, h, 3, GB_LAYERS, N_LAYERS(GB_LAYERS), fill);
    return pd_layered_box(c, x, y, w, h, 3, TITLE_LAYERS, N_LAYERS(TITLE_LAYERS), fill);
}

int pd_list_box(struct pd_canvas* c, int x, int y, int w, int h) {
    if (pd_gb_look) return pd_layered_box(c, x, y, w, h, 3, GB_LAYERS, N_LAYERS(GB_LAYERS), PD_LIST_FILL);
    return pd_layered_box(c, x, y, w, h, 3, LIST_LAYERS, N_LAYERS(LIST_LAYERS), PD_LIST_FILL);
}

void pd_backdrop(struct pd_canvas* c) {
    if (pd_gb_look) {
        pd_fill(c, 0, 0, c->w, c->h, PD_GB_PAPER);
        return;
    }
    for (int y = 0; y < c->h; y += 8) pd_fill(c, 0, y, c->w, 8, (y / 8) % 2 ? PD_BACKDROP_B : PD_BACKDROP_A);
    pd_fill(c, 0, c->h - 4, c->w, 4, PD_BACKDROP_BAND);
}
