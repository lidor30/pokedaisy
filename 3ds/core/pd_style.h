// The FireRed OPTION screen's look, shared by the companion and the ROM list:
// OptionColors (app/.../companion/ui/GbaMenu.kt) and the window boxes built
// from them.
#ifndef PD_STYLE_H
#define PD_STYLE_H

#include <stdint.h>

#include "pd_canvas.h"
#include "pd_font.h"

// A Game Boy game's windows are black on white (the app's gbInk / gbPaper), not FireRed's grey and teal: set by
// pd_game_identify. PD_GB(fireRed, gb) picks per game.
extern int pd_gb_look;
#define PD_GB(a, b) (pd_gb_look ? (b) : (a))
// The Game Boy look's ink and paper (the app's OptionColors.gbInk / gbPaper): Red / Blue's Super Game Boy colours
// (0x181010 on 0xFFEFFF), PureRGB's black, else 0x181818 on white. Set with pd_gb_look.
extern uint32_t pd_gb_ink, pd_gb_paper;
#define PD_GB_INK pd_gb_ink
#define PD_GB_PAPER pd_gb_paper

#define PD_TITLE_FILL PD_GB(0xFFFFFF, PD_GB_PAPER)
#define PD_TITLE_TEXT PD_GB(0x636363, PD_GB_INK)
#define PD_TITLE_SHADOW PD_GB(0xD6D6CE, PD_GB_PAPER)
#define PD_LIST_FILL PD_GB(0xE0DFDF, PD_GB_PAPER)
#define PD_ROW_SELECTED PD_GB(0xFFFFFF, 0xD8D8D8)
#define PD_LABEL PD_GB(0x575656, PD_GB_INK)
#define PD_LABEL_SHADOW PD_GB(0xBCBBB4, PD_GB_PAPER)
#define PD_VALUE PD_GB(0xCB0707, PD_GB_INK)
#define PD_VALUE_SHADOW PD_GB(0xE0A564, PD_GB_PAPER)
#define PD_MUTED PD_GB(0x8C8C94, 0x686868)
#define PD_MUTED_SHADOW PD_GB(0xD6D6D6, PD_GB_PAPER)
#define PD_DIVIDER PD_GB(0xC6C5C5, PD_GB_INK)
// Text straight on the backdrop: white with the hint bar's grey shadow.
#define PD_ON_BACKDROP PD_GB(0xFFFFFF, PD_GB_INK)
#define PD_ON_BACKDROP_SHADOW PD_GB(0x404850, PD_GB_PAPER)
// FireRed's party backdrop stripes (FIRERED_BACKDROP in theme/Theme.kt) -
// the plain stripes the app shows until a FireRed ROM supplied the art.
#define PD_BACKDROP_A PD_GB(0x4AADA5, PD_GB_PAPER)
#define PD_BACKDROP_B PD_GB(0x398C8C, PD_GB_PAPER)
#define PD_BACKDROP_BAND PD_GB(0x216B63, PD_GB_INK)

// The white title window and the grey list window; each returns its frame's width.
int pd_title_box(struct pd_canvas* c, int x, int y, int w, int h, uint32_t fill);
int pd_list_box(struct pd_canvas* c, int x, int y, int w, int h);
void pd_backdrop(struct pd_canvas* c);
// Text centred in a row of height h at y (the cell is 16 px, caps at rows 4-12).
static inline int pd_text_y(int y, int h) {
    return y + (h - PD_FONT_HEIGHT) / 2 + 1;
}

#endif
