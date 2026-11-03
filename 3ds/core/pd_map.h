// The region map, rebuilt from the player's own ROM like the app's RomArt
// (nothing of the games' art is bundled): the blobs are found by fingerprint
// (pd_map_gen.c, from RomArtSigsGen.kt), LZ77-decoded, CRC-checked and drawn
// into 240x160 pages; plus the player's 16x16 head, the cursor grids
// (RegionMapLayoutsGen.kt) and each map section's rectangle (MapSecData*.kt).
#ifndef PD_MAP_INCLUDED
#define PD_MAP_INCLUDED

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "pd_game.h"
#include "pd_romart.h"
#include "pd_snapshot.h"

#define PD_MAP_W 240
#define PD_MAP_H 160
#define PD_MAP_PAGES 7 // FireRed: Kanto + three Sevii pages; Emerald: Hoenn; a hack up to seven

struct pd_region_layout {
    int offX, offY, w, h; // the grid's place on the 30x20 screen, in tiles
    uint8_t none;         // the "no section" cell
    const uint8_t* layers[2]; // the map, then dungeons (NULL = none)
};

struct pd_mapsec_rect {
    int8_t page; // -1 = not on the map
    uint8_t x, y, w, h; // in 8px tiles of the 240x160 page; 0 x 0 = only on the grid
};

extern const struct pd_region_layout pd_layouts_firered[];
extern const int pd_layouts_firered_count;
extern const struct pd_region_layout pd_layouts_emerald[];
extern const int pd_layouts_emerald_count;
extern const struct pd_mapsec_rect pd_mapsec_rects_firered[];
extern const int pd_mapsec_rects_firered_count;
extern const struct pd_mapsec_rect pd_mapsec_rects_emerald[];
extern const int pd_mapsec_rects_emerald_count;

// The art, built once per ROM (pd_map_art). Pixels are 0xRRGGBB; the heads'
// transparent pixels are PD_MAP_CLEAR.
#define PD_MAP_CLEAR 0xFF000000u
struct pd_map_art {
    bool ok;
    int pages;
    uint32_t page[PD_MAP_PAGES][PD_MAP_W * PD_MAP_H];
    bool hasHead[2];
    uint32_t head[2][16 * 16]; // [0] boy, [1] girl
    // A hack whose map was read from its ROM (RomRegionMap): its sections, by id.
    bool romSections;
    struct pd_mapsec_rect sections[256];
};

// Finds and draws the map for g's ROM - the first call scans the ROM (well
// under a second on a New 3DS), later ones return the same art. NULL if the
// game has no map art this port knows, or the ROM lacks it.
const struct pd_map_art* pd_map_art(const struct pd_game* g);

// A tile rectangle.
struct pd_tile_rect {
    int x, y, w, h;
};

// The section's page, and its rectangles (the rect from MapSecData, else every
// grid cell naming it - a dungeon). Returns how many (0 = not on the map).
int pd_map_tiles(const struct pd_game* g, int mapsec, int* page, struct pd_tile_rect* out, int max);
// What's under tile (tx, ty) of a page: the section and the dungeon there (-1 = none).
void pd_map_pick(const struct pd_game* g, int page, int tx, int ty, int* mapsec, int* dungeon);
// The tile the game puts the player's head on (region_map.c's
// GetPlayerPositionOnRegionMap); false when the section isn't on the map.
bool pd_map_player_tile(const struct pd_game* g, const struct pd_snapshot* s, int* page, int* tx, int* ty);
// The page's name for the region button ("KANTO", "SEVII 1-3" ...).
const char* pd_map_page_name(const struct pd_game* g, int page);

#endif
