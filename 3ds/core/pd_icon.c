#include "pd_icon.h"

#include <string.h>

#include "pd_gb.h"
#include "pd_romart.h"
#include "pd_tables.h"

#define CLEAR 0xFF000000u
#define CACHE 24 // a party, a foe team and a few more

struct icon {
    unsigned rom;
    int species; // 0 = empty slot
    bool shiny;
    bool ok;
    uint32_t px[PD_ICON * PD_ICON];
};

static struct icon cache[CACHE];
static int nextSlot;

static uint32_t u32le(const uint8_t* p) {
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

static uint32_t bgr555(uint16_t c) {
    uint32_t r = c & 0x1F, g = (c >> 5) & 0x1F, b = (c >> 10) & 0x1F;
    return (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
}

// A battle palette (gMonPaletteTable / gMonShinyPaletteTable entry: {LZ77 pointer, u16 tag}), its 16 colours.
static bool battle_pal(const struct pd_game* g, uint32_t table, int species, int tag, uint16_t* out) {
    uint8_t e[8];
    if (!pd_rom_read(g, table + (uint32_t) species * 8, e, 8) || (e[4] | e[5] << 8) != tag) return false;
    const uint8_t* src = pd_rom_at(g, u32le(e), 4);
    if (!src || src[0] != 0x10) return false;
    size_t size = src[1] | (size_t) src[2] << 8 | (size_t) src[3] << 16;
    uint8_t raw[128];
    if (size < 32 || size > sizeof raw) return false;
    size_t avail = g->romSize - (u32le(e) - 0x08000000u);
    if (pd_lz77(src, avail < 128 ? avail : 128, raw, size) != size) return false;
    for (int i = 0; i < 16; i++) out[i] = (uint16_t) (raw[i * 2] | raw[i * 2 + 1] << 8);
    return true;
}

// The app's shinyIconPalette: a shared icon palette in the species' shiny colours - each icon colour swapped for the
// shiny battle palette's colour where its nearest normal battle colour sits (the game never shows shiny icons).
#define SHINY_PALETTE_TABLE_OFFSET 0xDC0u
#define SHINY_PALETTE_TAG 500
static bool shiny_icon_pal(const struct pd_game* g, uint32_t battle, int species, uint16_t* pal) {
    uint16_t normal[16], shiny[16];
    if (!battle || !battle_pal(g, battle, species, species, normal) ||
        !battle_pal(g, battle + SHINY_PALETTE_TABLE_OFFSET, species, species + SHINY_PALETTE_TAG, shiny)) return false;
    for (int i = 1; i < 16; i++) {
        int best = 1, bestD = 1 << 30;
        for (int k = 1; k < 16; k++) {
            int r = (pal[i] & 31) - (normal[k] & 31), gg = (pal[i] >> 5 & 31) - (normal[k] >> 5 & 31),
                b = (pal[i] >> 10 & 31) - (normal[k] >> 10 & 31);
            int d = r * r + gg * gg + b * b;
            if (d < bestD) { bestD = d; best = k; }
        }
        pal[i] = shiny[best];
    }
    return true;
}

static bool decode(const struct pd_game* g, int species, bool shiny, uint32_t* out) {
    const struct pd_config* cfg = g->cfg;
    if (cfg && cfg->gb) {  // a Game Boy cart's own 16x16 icon, at twice its size like the GBA's 32x32
        uint32_t small[16 * 16];
        if (!pd_gb_icon(g, species, small)) return false;
        for (int y = 0; y < PD_ICON; y++)
            for (int x = 0; x < PD_ICON; x++) {
                uint32_t v = small[(y / 2) * 16 + x / 2];
                out[y * PD_ICON + x] = v ? v & 0xFFFFFF : CLEAR;
            }
        return true;
    }
    if (!cfg || !cfg->monIconTable || species <= 0) return false;
    if (species == PD_SPECIES_EGG) species = cfg->eggSpecies ? cfg->eggSpecies : 412;
    else if (species >= (cfg->speciesCount ? cfg->speciesCount : cfg->eggSpecies ? cfg->eggSpecies + 1 : 412)) return false;
    uint8_t w[4], idx;
    if (!pd_rom_read(g, cfg->monIconTable + (uint32_t) species * (uint32_t) cfg->monIconStride, w, 4)) return false;
    const uint8_t* tiles = pd_rom_at(g, u32le(w), 16 * 32);
    if (!tiles) return false;
    if (cfg->monIconPalettes) {  // a palette per species; the shiny one's pointer follows
        if (!pd_rom_read(g, cfg->monIconPalettes + (uint32_t) species * (uint32_t) cfg->monIconStride + (shiny ? 4 : 0), w, 4))
            return false;
    } else {
        if (!pd_rom_read(g, cfg->monIconPaletteIndices + (uint32_t) species * (uint32_t) cfg->monPalIdxStride, &idx, 1) ||
            (idx &= (uint8_t) cfg->monPalIdxMask) > 15) return false;
        if (!pd_rom_read(g, cfg->monIconPaletteTable + (uint32_t) idx * 8, w, 4)) return false;
    }
    const uint8_t* raw = pd_rom_at(g, u32le(w), 32);
    if (!raw) return false;
    uint16_t pal[16];
    for (int i = 0; i < 16; i++) pal[i] = (uint16_t) (raw[i * 2] | raw[i * 2 + 1] << 8);
    if (shiny && !cfg->monIconPalettes) {
        const struct pd_party_layout* l = cfg->partyLayout;
        uint32_t battle = (l && l->shinyBelow > 0) || g->shinyBelow > 0 ? (cfg->dexPalettes ? cfg->dexPalettes : cfg->monPalettes) : 0;
        if (!shiny_icon_pal(g, battle, species, pal)) return false;
    }
    uint32_t colours[16];
    for (int i = 0; i < 16; i++) colours[i] = bgr555(pal[i]);
    // 4x4 tiles of 8x8, row by row; a byte holds two pixels, the left one low.
    for (int t = 0; t < 16; t++) {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                uint8_t b = tiles[t * 32 + y * 4 + x / 2];
                int v = x & 1 ? b >> 4 : b & 0x0F;
                out[((t / 4) * 8 + y) * PD_ICON + (t % 4) * 8 + x] = v ? colours[v] : CLEAR;
            }
        }
    }
    return true;
}

static const struct icon* icon_for(const struct pd_game* g, int species, bool shiny) {
    unsigned rom = pd_rom_id(g);
    for (int i = 0; i < CACHE; i++) {
        if (cache[i].species == species && cache[i].shiny == shiny && cache[i].rom == rom) return &cache[i];
    }
    struct icon* e = &cache[nextSlot];
    nextSlot = (nextSlot + 1) % CACHE;
    e->rom = rom;
    e->species = species;
    e->shiny = shiny;
    e->ok = decode(g, species, shiny, e->px);
    if (!e->ok && shiny) return icon_for(g, species, false);  // no shiny colours found: the normal icon
    return e;
}

bool pd_draw_mon_icon(struct pd_canvas* c, const struct pd_game* g, int species, int x, int y, bool faded) {
    return pd_draw_mon_icon_scaled(c, g, species, x, y, 1, faded);
}

bool pd_draw_mon_icon_scaled(struct pd_canvas* c, const struct pd_game* g, int species, int x, int y, int scale,
                             bool faded) {
    return pd_draw_mon_icon_shiny(c, g, species, false, x, y, scale, faded);
}

bool pd_draw_mon_icon_shiny(struct pd_canvas* c, const struct pd_game* g, int species, bool shiny, int x, int y, int scale,
                            bool faded) {
    if (!pd_rom_id(g)) return false;
    const struct icon* e = icon_for(g, species, shiny && species != PD_SPECIES_EGG);
    if (!e->ok) return false;
    if (scale < 1) scale = 1;
    for (int py = 0; py < PD_ICON * scale; py++) {
        int sy = y + py;
        if (sy < c->cy0 || sy >= c->cy1) continue;
        for (int px = 0; px < PD_ICON * scale; px++) {
            int sx = x + px;
            uint32_t v = e->px[py / scale * PD_ICON + px / scale];
            if (v == CLEAR || sx < c->cx0 || sx >= c->cx1) continue;
            uint32_t* d = &c->px[sy * c->w + sx];
            if (faded) {
                uint32_t r = (((v >> 16) & 0xFF) * 35 + ((*d >> 16) & 0xFF) * 65) / 100;
                uint32_t gg = (((v >> 8) & 0xFF) * 35 + ((*d >> 8) & 0xFF) * 65) / 100;
                uint32_t b = ((v & 0xFF) * 35 + (*d & 0xFF) * 65) / 100;
                v = r << 16 | gg << 8 | b;
            }
            *d = v;
        }
    }
    return true;
}

// --- item icons: the bag's own (the app's DecompIconSource.fetchItem): sItemIconTable[item] = {tiles, palette},
// both LZ77, 3x3 tiles of 4bpp; colour 0 clear.
#define ITEM_CACHE 8

struct item_icon {
    unsigned rom;
    int item;
    bool ok;
    uint32_t px[PD_ITEM_ICON * PD_ITEM_ICON];
};

static struct item_icon itemCache[ITEM_CACHE];
static int nextItem;

static bool decode_item(const struct pd_game* g, int item, uint32_t* out) {
    const struct pd_config* cfg = g->cfg;
    if (!cfg || !cfg->itemIconTable || item <= 0) return false;
    uint8_t w[8];
    if (!pd_rom_read(g, cfg->itemIconTable + (uint32_t) item * (uint32_t) (cfg->itemIconStride ? cfg->itemIconStride : 8), w, 8))
        return false;
    const uint8_t* tilesLz = pd_rom_at(g, u32le(w), 4);
    const uint8_t* palLz = pd_rom_at(g, u32le(w + 4), 4);
    if (!tilesLz || !palLz) return false;
    uint8_t tiles[9 * 32], pal[32];
    size_t left = g->romSize - (size_t) (tilesLz - g->rom), palLeft = g->romSize - (size_t) (palLz - g->rom);
    if (pd_lz77(tilesLz, left, tiles, sizeof(tiles)) < sizeof(tiles)) return false;
    if (cfg->itemPalCompressed) {
        if (pd_lz77(palLz, palLeft, pal, sizeof(pal)) < sizeof(pal)) return false;
    } else {
        if (palLeft < sizeof(pal)) return false;
        memcpy(pal, palLz, sizeof(pal));
    }
    uint32_t colours[16];
    for (int i = 0; i < 16; i++) colours[i] = bgr555((uint16_t) (pal[i * 2] | pal[i * 2 + 1] << 8));
    for (int t = 0; t < 9; t++) {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                uint8_t b = tiles[t * 32 + y * 4 + x / 2];
                int v = x & 1 ? b >> 4 : b & 0x0F;
                out[((t / 3) * 8 + y) * PD_ITEM_ICON + (t % 3) * 8 + x] = v ? colours[v] : CLEAR;
            }
        }
    }
    return true;
}

bool pd_draw_item_icon(struct pd_canvas* c, const struct pd_game* g, int item, int x, int y, bool faded) {
    if (!pd_rom_id(g)) return false;
    unsigned rom = pd_rom_id(g);
    struct item_icon* e = NULL;
    for (int i = 0; i < ITEM_CACHE && !e; i++)
        if (itemCache[i].item == item && itemCache[i].rom == rom) e = &itemCache[i];
    if (!e) {
        e = &itemCache[nextItem];
        nextItem = (nextItem + 1) % ITEM_CACHE;
        e->rom = rom;
        e->item = item;
        e->ok = decode_item(g, item, e->px);
    }
    if (!e->ok) return false;
    for (int py = 0; py < PD_ITEM_ICON; py++) {
        int sy = y + py;
        if (sy < c->cy0 || sy >= c->cy1) continue;
        for (int px = 0; px < PD_ITEM_ICON; px++) {
            int sx = x + px;
            uint32_t v = e->px[py * PD_ITEM_ICON + px];
            if (v == CLEAR || sx < c->cx0 || sx >= c->cx1) continue;
            uint32_t* d = &c->px[sy * c->w + sx];
            if (faded) {
                uint32_t r = (((v >> 16) & 0xFF) * 45 + ((*d >> 16) & 0xFF) * 55) / 100;
                uint32_t gg = (((v >> 8) & 0xFF) * 45 + ((*d >> 8) & 0xFF) * 55) / 100;
                uint32_t b = ((v & 0xFF) * 45 + (*d & 0xFF) * 55) / 100;
                v = r << 16 | gg << 8 | b;
            }
            *d = v;
        }
    }
    return true;
}
