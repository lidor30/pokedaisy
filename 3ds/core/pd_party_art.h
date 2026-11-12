// The party's own art, rebuilt from the player's ROM like the app's RomArt (none of it is bundled): a game's slot frames,
// Poke Ball, status icons and FONT_SMALL, and the backdrop behind them. Built once a game, on first use, from the blobs
// pd_romart_scan found while the game loaded. A game with no pd_party_style, or a ROM that lacks a blob the style needs,
// gets NULL here and keeps the palette slot (pd_ui_party.c).
#ifndef PD_PARTY_ART_H
#define PD_PARTY_ART_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_canvas.h"
#include "pd_games.h"

enum pd_slot_frame {
    PD_SLOT_NORMAL, PD_SLOT_SELECTED, PD_SLOT_FAINTED, PD_SLOT_SELECTED_FAINTED, PD_SLOT_NOHP, PD_SLOT_NOHP_SELECTED, PD_SLOT_EMPTY,
    PD_SLOT_FRAMES,
};

// Art pixels: 0xFFRRGGBB, 0 = see-through.
#define PD_ART_OPAQUE 0xFF000000u

struct pd_party_art {
    const struct pd_party_style* style;
    uint32_t* frames[PD_SLOT_FRAMES]; // slotW x slotH; EMPTY only where the game draws one
    uint32_t* ball;                   // 32 x 64, closed over open; NULL where the game's sprite is blank
    uint32_t* status;                 // a row of 32 x 8 icons: PSN, PAR, SLP, FRZ, BRN, PKRS, FNT
    int statusW;
    uint32_t* hold;                   // 8 x 16, an item over Mail; NULL where the ROM lost it
    const uint8_t* font;              // the blob itself
};

const struct pd_party_art* pd_party_art_for(const struct pd_game* g);

// The game's type tags (TypeBadge): its 128x128 sheet read from the ROM, 0 see-through, or NULL (not an English FireRed /
// Emerald engine, or the ROM lost them). Built once a ROM.
const uint32_t* pd_type_tags(const struct pd_game* g);
// Where a type's 32x12 tag starts in the sheet, by its English name ("FIRE"); false for one the sheet hasn't.
bool pd_type_tag_at(const char* name, int* x, int* y);

// Row y (0..15) of a FONT_SMALL glyph: two bits a pixel, the first pixel in the top bits; 1 text, 2 shadow, else clear.
int pd_party_glyph_row(const struct pd_party_art* a, int glyph, int y);

// Fills c with the game's party backdrop under the theme's wash and dark mode's scrim, or returns false for the stripes.
bool pd_party_backdrop(struct pd_canvas* c, const struct pd_game* g);

// The backdrop behind the slots at the game's own size, filling c: no zig-zags, text box or CANCEL.
bool pd_party_screen(struct pd_canvas* c, const struct pd_game* g);

#endif
