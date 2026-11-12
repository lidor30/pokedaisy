/* FieldHud.kt's battle HP box look, shared by the field HUD and Quick Menu. */
#ifndef POCKET_HUD_STYLE_H
#define POCKET_HUD_STYLE_H
#include "pd_canvas.h"
#include <stdbool.h>

#define HUD_SELECTED 0xF07038u

struct hud_ink {
  uint32_t fill, edge, text, shadow, accent, muted, boxShadow, tileTop, tileLow, onAccent;
  bool window;
};

const struct hud_ink *hud_ink(int look);
void hud_panel(struct pd_canvas *c, const struct hud_ink *ink, int x, int y, int w, int h, int shadow);
void hud_tile(struct pd_canvas *c, const struct hud_ink *ink, int x, int y, int w, int h, bool selected);

#endif
