// What the app shows of every game it knows beyond gamedata's config (looks, the PC's moves), by its ROM's SHA-1:
// pd_games_gen.c, from the app's own detection (3ds/tools/gen_games.py).
#ifndef PD_GAMES_H
#define PD_GAMES_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_canvas.h"
#include "pd_game.h"
#include "pd_romart.h"
#include "pd_tables.h"

struct pd_slot_colors {
    uint32_t outline, light, fill, shade, band;
    uint32_t text, textShadow; // PD_SLOT_TEXT_OWN = the palette's
};
#define PD_SLOT_TEXT_OWN 0xFFFFFFFFu

struct pd_hp_band {
    uint32_t top, main;
};

// PartyPalette: a game's party slots.
struct pd_party_palette {
    struct pd_slot_colors normal, selected, fainted, selectedFainted, empty;
    bool hasEmpty;
    uint32_t text, textShadow, male, maleShadow, female, femaleShadow, hpFrame, hpInner, hpLabel, hpLabelShade;
    struct pd_hp_band hpGreen, hpYellow, hpRed, hpEmpty;
    uint32_t ball[4]; // K R H W
    int emptyAlpha;   // 0-255
};

// PartySlotStyle: a game's own slot (GbaPartySlot.kt), its numbers and the blobs its art is made of (-1 = none).
struct pd_party_style {
    short gfx, pal, slotMain, slotNoHp, slotEmpty, ball, ballPal, status, statusPal, font;
    bool halfWidthFont;
    short tilesW, tilesH;
    const unsigned char* glyphWidths; // FONT_SMALL's advances by Gen 3 code (+0x100 for the 0xF9 symbols)
    short lvGlyph, padGlyph;
    uint32_t text, textShadow, male, maleShadow, female, femaleShadow;
    uint32_t hpGreenTop, hpGreen, hpYellowTop, hpYellow, hpRedTop, hpRed, hpEmptyTop, hpEmpty;
    short slotW, slotH, nameX, nameY, levelX, levelY, genderX, genderY, hpX, maxHpX, hpY, barX, barY, barW;
    short iconX, iconY, iconRestDx, iconRestDy, ballX, ballY, statusX, statusY;
    short leftCol, midCol, topRow, midRow, bottomRow, visibleW, visibleH, regionX0, regionY0;
    bool ballFollowsIcon, stretchRows, upperCaseNames, hasEmptySlot, blendOverBackdrop;
    bool hasBand;
    short bandTop, bandBottom, bandLeftTopX, bandLeftBottomX, bandRightTopX, bandRightBottomX, bandEmptyStripeRow;
    const char* eggName;
};

// A game's party backdrop (buildGameBackground): the screen behind the slots, or its 32x32 cell tiled. blank: 0 = one
// cell of the grid; 1 = the whole screen minus CANCEL; 2 = Heart and Soul's, its SEL-ORDER hint gone too. colors: the
// three the art's stripes turn into (from, then to).
struct pd_party_backdrop {
    short gfx, pal, map;
    unsigned char blank;
    bool recolor;
    uint32_t colors[6];
};

// BagPalette: a game's bag.
struct pd_bag_palette {
    struct pd_layer panel[6], desc[4];
    int panelLayers, tabLayers, descLayers;
    uint32_t fill, divider, text, textShadow, tabText, tabTextShadow, cursor;
    uint32_t descFill, descText, descTextShadow;
    bool hasDivider, hasBackdrop;
    uint32_t backdropA, backdropB;
};

extern const struct pd_party_palette* pd_party_cur;
extern const struct pd_bag_palette* pd_bag_cur;
extern const struct pd_party_palette* const pd_party_default[2];
extern const struct pd_bag_palette* const pd_bag_default[2];

// A hack's region map pages (RomArt's REGION blobs): which fingerprints make each. kind 0: Emerald's 8bpp screen; 1: a 32-wide 4bpp one;
// 2: Monomyth's 8bpp text screen.
struct pd_region_page {
    const struct pd_blob_sig* gfx;
    const struct pd_blob_sig* pal;
    const struct pd_blob_sig* map;
    int firstColour, kind;
    const char* label;
    // The head on it, where the map has one of its own (a 16x16 sprite).
    const struct pd_blob_sig *headGfx, *headPal;
};

// The PC's moves (the app's BoxSwapFns): BoxMonToMon, BoxMonRestorePP, GetBoxMonData, the storage pointer they use and
// their code's CRCs. NULL on an entry = no WITHDRAW / DEPOSIT / SWAP.
struct pd_box_swap {
    uint32_t boxToMon, boxRestorePP, boxGetData, boxStoragePtr;
    uint32_t boxCrcs[3];
};

struct pd_gb_dex;
struct pd_gb_art;

// What the app shows of a ROM beyond its config (gamedata's), found by its SHA-1 (pd_game_identify): exported from the
// app's own detection by gen_games.py. Its config, names, guide and Game Boy addresses are gamedata's, not here.
struct pd_game_entry {
    const char* sha1;
    const char* file;  // the file it was exported from, for the library's name
    const char* title;
    char language;
    const char* gameKind; // the app's GameKind
    const struct pd_party_palette* party[2]; // light, dark
    const struct pd_bag_palette* bag[2];
    uint32_t backdrop[3];
    const struct pd_region_page* regionPages; // the hack's own art; NULL = retail's fingerprints
    int regionPageCount;
    bool romRegionMap; // RomRegionMap: follows FireRed 1.0's code to a hack's map
    // With regionPages: each map section's page, x, y, w and h in tiles (the app's MapSecData; 255 page = not on it).
    const unsigned char (*mapsecAt)[5];
    int mapsecAtCount;
    const struct pd_party_style* partyStyle; // NULL = the palette slot
    const struct pd_party_backdrop* partyBackdrops;
    int partyBackdropCount;
    const struct pd_box_swap* boxSwap;
    // The field items the app picks in code (fieldItemsForGame: a hack's FLY / PC / Repel, USE / GIVE / TAKE) and a bag of its
    // own (Quetzal's BAG3, Elite Redux's owned bits): laid over the config's empty fields by pd_game_identify.
    const struct pd_config* fieldCfg;
    // A Game Boy cart's DEX pages and art (town maps, icons, the player) where gamedata's pd_gb config has none.
    const struct pd_gb_dex* gbDex;
    const struct pd_gb_art* gbArt;
};

extern const struct pd_game_entry pd_game_entries[];
extern const int pd_game_entry_count;

// The entry for a ROM's SHA-1 (lower-case hex), or NULL (they're sorted by it).
const struct pd_game_entry* pd_game_entry_for(const char* sha1);

#endif
