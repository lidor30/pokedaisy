// The Game Boy games' MAP (the app's Gen1Art.townMap / player and Gen2Art.townMap / player): rebuilt from the player's
// own cart, nothing bundled. The cart is matched by SHA1 first, so another cart never gets art cut from the wrong bytes;
// addresses are pret/pokeyellow's, pokered's, pokecrystal's and pokegold's symbols (bank, address), as Gen1Art /
// Gen2Art have them. Gen 1: one town map, CompressedMap's RLE (a byte = tile high, count low; 0 ends) of the 2bpp
// WorldMapTileGraphics, in a fixed four-colour palette (Yellow's CGB PAL_TOWNMAP, Red / Blue's Super Game Boy one), and
// the player's first standing frame. Gen 2: the Pokégear's Johto and Kanto, 20x18 tilemaps over the LZ TownMapGFX, each
// tile in the palette TownMapPals' nybble gives it (tiles from 0x60 in palette 0) of MalePokegearPals; the player CHRIS /
// KRIS in the day MapObjectPals' red / blue (Gold / Silver: the boy alone).
#include <string.h>

#include "pd_map.h"

#define GB_OFF(bank, addr) ((uint32_t) ((bank) * 0x4000 + ((addr) - 0x4000)))
#define TOWN_W 160
#define TOWN_H 144

struct gb_map_cart {
    const char* sha1[3];
    int gen;
    const struct pd_mapsec_rect* rects;
    const int* rectCount;
    // Gen 1: CompressedMap, WorldMapTileGraphics, the colours of pixel values 0-3, RedSprite, the player's colours
    // (pixel 0 is clear; all 0 = the map's).
    uint32_t townMap, tiles, colors[4], player, playerColors[4];
    // Gen 2: TownMapGFX (LZ), JohtoMap / KantoMap, TownMapPals' PalMap, MalePokegearPals, ChrisSpriteGFX /
    // KrisSpriteGFX (0 = none), MapObjectPals.
    uint32_t gfx, maps[2], palMap, pals, players[2], objPals;
};

static const struct gb_map_cart CARTS[] = {
    { { "cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1" }, 1, pd_mapsec_rects_gb_yellow, &pd_mapsec_rects_gb_yellow_count,
      .townMap = GB_OFF(0x1C, 0x518A), .tiles = GB_OFF(0x04, 0x5138), .colors = { 0xFFFFFF, 0x00ADFF, 0x52E700, 0x080808 },
      .player = GB_OFF(0x05, 0x4571) },
    // Red and Blue: the same art at the same addresses, in the SGB colours mGBA shows (the sprite through OBP0).
    { { "ea9bcae617fdf159b045185467ae58b2e4a48b9a", "d7037c83e1ae5b39bde3c30787637ba1d4c48ce2" }, 1,
      pd_mapsec_rects_gb_yellow, &pd_mapsec_rects_gb_yellow_count,
      .townMap = GB_OFF(0x1C, 0x5100), .tiles = GB_OFF(0x04, 0x65A8), .colors = { 0xFFEFFF, 0xA5D6FF, 0x8CBD52, 0x181010 },
      .player = GB_OFF(0x05, 0x4180), .playerColors = { 0, 0xFFEFFF, 0xA5D6FF, 0x181010 } },
    // Crystal rev 0, rev 1, Australia.
    { { "f4cd194bdee0d04ca4eac29e09b8e4e9d818c133", "f2f52230b536214ef7c9924f483392993e226cfb",
        "a0fc810f1d4e124434f7be2c989ab5b5892ddf36" }, 2, pd_mapsec_rects_gb_crystal, &pd_mapsec_rects_gb_crystal_count,
      .gfx = GB_OFF(0x3E, 0x4BA0), .maps = { GB_OFF(0x24, 0x5FFF), GB_OFF(0x24, 0x6168) }, .palMap = GB_OFF(0x24, 0x5F4B),
      .pals = GB_OFF(0x02, 0x7729), .players = { GB_OFF(0x30, 0x4000), GB_OFF(0x31, 0x7A40) }, .objPals = GB_OFF(0x02, 0x7469) },
    // Gold and Silver: one family, the same art at the same addresses.
    { { "d8b8a3600a465308c9953dfa04f0081c05bdcb94", "49b163f7e57702bc939d642a18f591de55d92dae" }, 2,
      pd_mapsec_rects_gb_gold_silver, &pd_mapsec_rects_gb_gold_silver_count,
      .gfx = GB_OFF(0x3E, 0x4C92), .maps = { GB_OFF(0x24, 0x5F52), GB_OFF(0x24, 0x60BB) }, .palMap = GB_OFF(0x24, 0x5EAC),
      .pals = GB_OFF(0x02, 0x7B6E), .players = { GB_OFF(0x30, 0x4000), 0 }, .objPals = GB_OFF(0x02, 0x78AE) },
};

static const struct gb_map_cart* cart_of(const struct pd_game* g) {
    if (g->kind != PD_GAME_GB || !g->sha1[0]) return NULL;
    for (size_t i = 0; i < sizeof CARTS / sizeof CARTS[0]; i++)
        for (int k = 0; k < 3 && CARTS[i].sha1[k]; k++)
            if (!strcmp(CARTS[i].sha1[k], g->sha1)) return &CARTS[i];
    return NULL;
}

static const uint8_t* at(const struct pd_game* g, uint32_t off, size_t len) {
    return g->rom && off <= g->romSize && len <= g->romSize - off ? g->rom + off : NULL;
}

// 2bpp tile pixel (0-3).
static int px2(const uint8_t* tile, int x, int y) {
    return (tile[2 * y] >> (7 - x) & 1) | (tile[2 * y + 1] >> (7 - x) & 1) << 1;
}

// GBC RGB555 as mGBA shows it (5 bits widened by their top 3).
static uint32_t rgb555(const uint8_t* p) {
    unsigned c = p[0] | p[1] << 8, r = c & 31, g = c >> 5 & 31, b = c >> 10 & 31;
    return (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
}

static bool palette(const struct pd_game* g, uint32_t off, int index, uint32_t out[4]) {
    const uint8_t* p = at(g, off + 8u * (uint32_t) index, 8);
    if (!p) return false;
    for (int i = 0; i < 4; i++) out[i] = rgb555(p + 2 * i);
    return true;
}

// A 16x16 sprite from 2x2 2bpp tiles (TL, TR, BL, BR); pixel 0 is clear.
static bool sprite16(const struct pd_game* g, uint32_t off, const uint32_t colors[4], uint32_t* out) {
    const uint8_t* t = at(g, off, 4 * 16);
    if (!t) return false;
    for (int y = 0; y < 16; y++)
        for (int x = 0; x < 16; x++) {
            int v = px2(t + 16 * ((y / 8) * 2 + x / 8), x % 8, y % 8);
            out[y * 16 + x] = v ? colors[v] : PD_MAP_CLEAR;
        }
    return true;
}

static bool gen1(const struct pd_game* g, const struct gb_map_cart* t, struct pd_map_art* art) {
    const uint8_t* tiles = at(g, t->tiles, 16 * 16);
    if (!tiles) return false;
    uint32_t* img = art->page[0];
    int cell = 0;
    for (uint32_t o = t->townMap;; o++) {
        const uint8_t* b = at(g, o, 1);
        if (!b) return false;
        if (!*b || cell >= 20 * 18) break;
        for (int n = 0; n < (*b & 0xF) && cell < 20 * 18; n++, cell++) {
            const uint8_t* tile = tiles + 16 * (*b >> 4);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) img[((cell / 20) * 8 + y) * TOWN_W + (cell % 20) * 8 + x] = t->colors[px2(tile, x, y)];
        }
    }
    if (cell < 20 * 18) return false;
    art->pages = 1;
    art->hasHead[0] = sprite16(g, t->player, t->playerColors[1] ? t->playerColors : t->colors, art->head[0]);
    return true;
}

// Gen 2's LZ (pokecrystal home/decompress.asm; the app's Gen2Lz): the decoded size, 0 on bad data or past max.
static size_t gen2_lz(const struct pd_game* g, uint32_t off, uint8_t* out, size_t max) {
    size_t n = 0;
    const uint8_t* p = at(g, off, 1);
    const uint8_t* end = g->rom + g->romSize;
    if (!p) return 0;
#define NEXT() (p < end ? *p++ : (uint8_t) 0xFF)
    for (;;) {
        if (p >= end) return 0;
        int b = *p++;
        if (b == 0xFF) return n;
        int cmd = b >> 5, len;
        if (cmd == 7) {
            cmd = b >> 2 & 7;
            len = ((b & 3) << 8 | NEXT()) + 1;
        } else {
            len = (b & 0x1F) + 1;
        }
        if (n + (size_t) len > max) return 0;
        if (cmd == 0) {
            for (int i = 0; i < len; i++) out[n++] = NEXT();
        } else if (cmd == 1) {
            uint8_t v = NEXT();
            for (int i = 0; i < len; i++) out[n++] = v;
        } else if (cmd == 2) {
            uint8_t a = NEXT(), c = NEXT();
            for (int i = 0; i < len; i++) out[n++] = i % 2 ? c : a;
        } else if (cmd == 3) {
            memset(out + n, 0, (size_t) len);
            n += (size_t) len;
        } else {
            int o = NEXT();
            long from = o & 0x80 ? (long) n - (o & 0x7F) - 1 : (long) (o << 8 | NEXT());
            if (from < 0 || (size_t) from >= n) return 0;
            for (int i = 0; i < len; i++) {
                if (cmd == 6 && from - i < 0) return 0;
                uint8_t v = cmd == 6 ? out[from - i] : out[from + i];
                if (cmd == 5) {
                    uint8_t r = 0;
                    for (int k = 0; k < 8; k++) r |= (uint8_t) ((v >> k & 1) << (7 - k));
                    v = r;
                }
                out[n++] = v;
            }
        }
    }
#undef NEXT
}

static bool gen2(const struct pd_game* g, const struct gb_map_cart* t, struct pd_map_art* art) {
    static uint8_t tiles[0x4000];
    size_t size = gen2_lz(g, t->gfx, tiles, sizeof tiles);
    uint32_t pals[6][4];
    for (int i = 0; i < 6; i++)
        if (!palette(g, t->pals, i, pals[i])) return false;
    const uint8_t* palMap = at(g, t->palMap, 0x30);
    if (!size || !palMap) return false;
    for (int page = 0; page < 2; page++) {
        const uint8_t* map = at(g, t->maps[page], 20 * 18);
        if (!map) return false;
        for (int cell = 0; cell < 20 * 18; cell++) {
            int tile = map[cell];
            if (tile == 0xFF || (size_t) tile * 16 + 16 > size) return false;
            int pal = tile >= 0x60 ? 0 : (palMap[tile / 2] >> (tile % 2 ? 4 : 0)) & 7;
            if (pal >= 6) return false;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    art->page[page][((cell / 20) * 8 + y) * TOWN_W + (cell % 20) * 8 + x] = pals[pal][px2(tiles + tile * 16, x, y)];
        }
    }
    art->pages = 2;
    for (int s = 0; s < 2; s++) {
        uint32_t colors[4];
        if (t->players[s] && palette(g, t->objPals, 8 + s, colors)) art->hasHead[s] = sprite16(g, t->players[s], colors, art->head[s]);
    }
    return true;
}

bool pd_gb_map_draw(const struct pd_game* g, struct pd_map_art* art) {
    const struct gb_map_cart* t = cart_of(g);
    if (!t || !g->rom) return false;
    art->w = TOWN_W;
    art->h = TOWN_H;
    art->ok = t->gen == 1 ? gen1(g, t, art) : gen2(g, t, art);
    return art->ok;
}

const struct pd_mapsec_rect* pd_gb_map_rects(const struct pd_game* g, int* count) {
    const struct gb_map_cart* t = cart_of(g);
    *count = t ? *t->rectCount : 0;
    return t ? t->rects : NULL;
}

bool pd_gb_map_gen1(const struct pd_game* g) {
    const struct gb_map_cart* t = cart_of(g);
    return t && t->gen == 1;
}

const char* pd_gb_map_page_name(const struct pd_game* g, int page) {
    const struct gb_map_cart* t = cart_of(g);
    if (!t) return "";
    if (t->gen == 1) return "KANTO";
    return page == 0 ? "JOHTO" : page == 1 ? "KANTO" : "";
}
