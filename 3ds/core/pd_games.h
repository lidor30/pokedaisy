// Every game the app knows, by its ROM's CRC32: pd_games_gen.c, from the app's own detection (3ds/tools/gen_games.py).
#ifndef PD_GAMES_H
#define PD_GAMES_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_canvas.h"
#include "pd_game.h"
#include "pd_romart.h"
#include "pd_tables.h"

struct pd_chart_row {
    unsigned char atk, def;
    unsigned short pct;
};

// A game's own names and numbers (the app's ActiveTables), by its own ids. NULL slots are unknown ids.
struct pd_table_set {
    const char* const* species;
    int speciesCount;
    const struct pd_move_info* moves;
    int moveCount;
    const char* const* items;
    int itemCount;
    const unsigned char (*types)[2];
    int typesCount;
    const struct pd_chart_row* chart;
    int chartCount;
    const char* const* typeNames;
    int typeNameCount;
    const char* const* mapsecs;
    int mapsecCount;
    const unsigned char* gender;
    int genderCount;
    const unsigned char (*mapsecAt)[5]; // a section's region, x, y, w and h on the map, in tiles (255 region = not on it)
    int mapsecAtCount;
};

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

// A hack's region map pages (RomArt's REGION blobs): which fingerprints make each. kind 0: Emerald's 8bpp screen; 1: a 32-wide 4bpp one.
struct pd_region_page {
    const struct pd_blob_sig* gfx;
    const struct pd_blob_sig* pal;
    const struct pd_blob_sig* map;
    int firstColour, kind;
    const char* label;
};

struct pd_guide;
struct pd_area_thing;

struct pd_game_entry {
    uint32_t crc32;
    const char* file;  // the file it was exported from, for the library's name
    const char* title;
    char language;
    const struct pd_config* cfg;
    const struct pd_table_set* tables;
    enum pd_game_kind kind;
    const char* gameKind; // the app's GameKind
    const struct pd_guide* guide; // the game's own GUIDE, NULL = none
    const struct pd_area_thing* areas;
    const int* areaCount;
    const struct pd_gb_config* gb; // a Game Boy game's own config (cfg is then blank)
    const struct pd_party_palette* party[2]; // light, dark
    const struct pd_bag_palette* bag[2];
    uint32_t backdrop[3];
    const struct pd_region_page* regionPages; // the hack's own art; NULL = retail's fingerprints
    int regionPageCount;
    bool romRegionMap; // RomRegionMap: follows FireRed 1.0's code to a hack's map
};

extern const struct pd_game_entry pd_game_entries[];
extern const int pd_game_entry_count;

// The entry for a ROM's CRC32, or NULL (they're sorted by it).
const struct pd_game_entry* pd_game_entry_for(uint32_t crc32);
// Takes a known game over from the ROM header's guess, tables and all: false if the CRC isn't one.
bool pd_game_identify(struct pd_game* g, uint32_t crc32);

#endif
