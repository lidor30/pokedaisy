#include "pd_icon.h"

#include <string.h>

#include "pd_gb.h"
#include "pd_romart.h"
#include "pd_smol.h"
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

// Monomyth's packed icons (MonomythIcons.kt): the first frame of the blob at b, as vanilla's 512 bytes of tiles.
#define MM_BLOB 0x1000
#define MM_FRAME 512

struct mm {
    const uint8_t* b;
    uint8_t* out;
    int n, shift;
    bool bad;
};
struct mm_streams { int w, h, b; };

static uint32_t mm8(struct mm* m, int o) {
    if (o < 0 || o >= MM_BLOB) {
        m->bad = true;
        return 0;
    }
    return m->b[o];
}
static uint32_t mm16(struct mm* m, int o) { return mm8(m, o) | mm8(m, o + 1) << 8; }
static uint32_t mm32(struct mm* m, int o) { return mm16(m, o) | mm16(m, o + 2) << 16; }

static void mm_put(struct mm* m, const void* data, int len) {
    int room = MM_FRAME - m->n;
    if (len > room) len = room;
    if (len <= 0) return;
    if (data) memcpy(m->out + m->n, data, (size_t) len);
    else memset(m->out + m->n, 0, (size_t) len);
    m->n += len;
}

static void mm_built(struct mm* m, struct mm_streams* s, int counter) {
    uint32_t mask = mm16(m, s->h) << 16;
    s->h += 2;
    bool right = (counter >> (m->shift - 1)) & 1;
    for (int i = 0; i < 8; i++) {
        bool c = mask >> 31, n = (mask >> 30) & 1;
        mask <<= 2;
        uint32_t w = 0;
        if (c && !n) {
            w = mm32(m, s->w);
            s->w += 4;
        } else if (!c && n) {
            w = mm16(m, s->h);
            s->h += 2;
            if (right) w <<= 16;
        } else if (!c) {
            w = mm8(m, s->b);
            s->b += 1;
            if (right) w <<= 24;
        }
        uint8_t bytes[4] = { (uint8_t) w, (uint8_t) (w >> 8), (uint8_t) (w >> 16), (uint8_t) (w >> 24) };
        mm_put(m, bytes, 4);
    }
}

static bool mm_frame(const uint8_t* blob, uint8_t* out) {
    struct mm m = { blob, out, 0, 1, false };
    uint32_t h = mm32(&m, 0);
    int dictTiles = (int) (h & 0xFF), records = (int) ((h >> 8) & 0xFF), frames = (int) (h >> 24);
    int r0 = 4, perCode, codeCount = 1;
    uint32_t codes[4] = { 0 };
    switch ((h >> 16) & 3) {
    case 0: codes[0] = mm8(&m, r0) << 24; r0 += frames; perCode = 4; m.shift = 1; break;
    case 1: codes[0] = mm16(&m, r0) << 16; r0 += frames * 2; perCode = 8; m.shift = 1; break;
    case 2: codes[0] = mm32(&m, r0); r0 += frames * 4; perCode = 16; m.shift = 2; break;
    default:
        for (int i = 0; i < 4; i++) codes[i] = mm32(&m, r0 + 4 * i);
        r0 += frames * 16;
        perCode = 16;
        m.shift = 3;
        codeCount = 4;
        break;
    }
    r0 = (r0 + 3) & ~3;
    int dict = r0 + records * 4, streams = dict + dictTiles * 32, table = streams;
    int data = (streams + frames * 6 + 3) & ~3;
    struct mm_streams main = { data + (int) mm16(&m, table + 4) * 4, data + (int) mm16(&m, table + 2) * 2,
                               data + (int) mm16(&m, table) };
    for (int k = 0; k < codeCount && !m.bad; k++) {
        uint32_t code = codes[k];
        for (int counter = perCode - 1; counter >= 0 && !m.bad; counter--) {
            bool c = code >> 31, n = (code >> 30) & 1;
            code <<= 2;
            if (!c && !n) {
                mm_put(&m, NULL, 32);
            } else if (!c) {
                if (main.w < 0 || main.w + 32 > MM_BLOB) return false;
                mm_put(&m, blob + main.w, 32);
                main.w += 32;
            } else if (!n) {
                mm_built(&m, &main, counter);
            } else {
                int ip = (int) mm8(&m, main.b);
                main.b += 1;
                if (ip >= records) {
                    int at = dict + (ip - records) * 32;
                    if (at + 32 > MM_BLOB) return false;
                    mm_put(&m, blob + at, 32);
                } else {
                    uint32_t w = mm32(&m, r0 + ip * 4);
                    int base = data + (int) (w >> 16) * 4;
                    struct mm_streams s = { base, base + (int) ((w >> 8) & 0xFF) * 2, base + (int) (w & 0xFF) };
                    mm_built(&m, &s, counter);
                }
            }
        }
    }
    return !m.bad && m.n == MM_FRAME;
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

// Scale3x (AdvMAME3x) of a 16x16 icon (0 = clear): sub-pixel (sx, sy) of (x, y).
static uint32_t icon3x(const uint32_t* p, int x, int y, int sx, int sy) {
#define AT(xx, yy) ((xx) < 0 || (xx) > 15 || (yy) < 0 || (yy) > 15 ? 0u : p[(yy) * 16 + (xx)])
    uint32_t A = AT(x - 1, y - 1), B = AT(x, y - 1), C = AT(x + 1, y - 1), D = AT(x - 1, y), E = AT(x, y), F = AT(x + 1, y),
             G = AT(x - 1, y + 1), H = AT(x, y + 1), I = AT(x + 1, y + 1);
#undef AT
    if (B == H || D == F) return E;
    uint32_t e[9] = {
        D == B ? D : E, (D == B && E != C) || (B == F && E != A) ? B : E, B == F ? F : E,
        (D == B && E != G) || (D == H && E != A) ? D : E, E, (B == F && E != I) || (H == F && E != C) ? F : E,
        D == H ? D : E, (D == H && E != I) || (H == F && E != G) ? H : E, H == F ? F : E,
    };
    return e[sy * 3 + sx];
}

// A Game Boy icon (16x16) at 24 px in a 32 px cell: Scale3x, then each 2x2 block averaged - the colours blended where they
// meet, a pixel shown where at least half its block is the Pokémon. Doubled to 32, it filled the cell and stood a head
// taller than a GBA game's.
static void gb_icon_24(const uint32_t* small, uint32_t* out) {
    for (int i = 0; i < PD_ICON * PD_ICON; i++) out[i] = CLEAR;
    const int ox = 4, oy = 6;
    for (int y = 0; y < 24; y++) {
        for (int x = 0; x < 24; x++) {
            int r = 0, gg = 0, b = 0, n = 0;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    int bx = x * 2 + dx, by = y * 2 + dy;
                    uint32_t v = icon3x(small, bx / 3, by / 3, bx % 3, by % 3);
                    if (!v) continue;
                    r += (int) (v >> 16 & 0xFF);
                    gg += (int) (v >> 8 & 0xFF);
                    b += (int) (v & 0xFF);
                    n++;
                }
            }
            if (n >= 2) out[(oy + y) * PD_ICON + ox + x] = (uint32_t) (r / n) << 16 | (uint32_t) (gg / n) << 8 | (uint32_t) (b / n);
        }
    }
}

static bool decode(const struct pd_game* g, int species, bool shiny, uint32_t* out) {
    const struct pd_config* cfg = g->cfg;
    if (cfg && cfg->gb) {  // a Game Boy cart's own 16x16 icon, as large in its 32x32 cell as a GBA icon's Pokémon (24 px)
        uint32_t small[16 * 16];
        if (!pd_gb_icon(g, species, small)) return false;
        gb_icon_24(small, out);
        return true;
    }
    if (!cfg || !cfg->monIconTable || species <= 0) return false;
    if (species == PD_SPECIES_EGG) species = cfg->eggSpecies ? cfg->eggSpecies : 412;
    else if (species >= (cfg->speciesCount ? cfg->speciesCount : cfg->eggSpecies ? cfg->eggSpecies + 1 : 412)) return false;
    uint32_t stride = (uint32_t) (cfg->monIconStride ? cfg->monIconStride : 4);
    uint32_t idxStride = (uint32_t) (cfg->monPalIdxStride ? cfg->monPalIdxStride : 1);
    uint8_t w[4], idx;
    if (!pd_rom_read(g, cfg->monIconTable + (uint32_t) species * stride, w, 4)) return false;
    // Unbound's entries point at the tiles with their palette behind them (UnboundIconSource); Monomyth's at packed
    // blobs (MonomythIcons.kt).
    static uint8_t unpacked[MM_FRAME];
    const uint8_t* tiles = pd_rom_at(g, u32le(w), cfg->monIconRecord ? 1024 + 32 : cfg->monIconPacked ? MM_BLOB : 16 * 32);
    if (!tiles) return false;
    const uint8_t* record = cfg->monIconRecord ? tiles : NULL;
    if (cfg->monIconPacked) {
        if (!mm_frame(tiles, unpacked)) return false;
        tiles = unpacked;
    }
    const uint8_t* raw;
    if (record) {
        raw = record + 1024;
    } else {
        if (cfg->monIconPalettes) {  // a palette per species; the shiny one's pointer follows
            if (!pd_rom_read(g, cfg->monIconPalettes + (uint32_t) species * stride + (shiny ? 4 : 0), w, 4)) return false;
        } else {
            if (!pd_rom_read(g, cfg->monIconPaletteIndices + (uint32_t) species * idxStride, &idx, 1)) return false;
            if (cfg->monPalIdxMask) idx &= (uint8_t) cfg->monPalIdxMask;
            if (idx > 15 || !pd_rom_read(g, cfg->monIconPaletteTable + (uint32_t) idx * 8, w, 4)) return false;
        }
        raw = pd_rom_at(g, u32le(w), 32);
    }
    if (!raw) return false;
    uint16_t pal[16];
    for (int i = 0; i < 16; i++) pal[i] = (uint16_t) (raw[i * 2] | raw[i * 2 + 1] << 8);
    if (shiny && !cfg->monIconPalettes && !record) {
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

const uint32_t* pd_mon_icon_pixels(const struct pd_game* g, int species, bool shiny) {
    if (!pd_rom_id(g)) return NULL;
    const struct icon* icon = icon_for(g, species, shiny && species != PD_SPECIES_EGG);
    return icon->ok ? icon->px : NULL;
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
    int extra = item - cfg->extraItemFirst;
    uint32_t entry = cfg->extraItemIconTable && extra >= 0 && extra < cfg->extraItemCount
        ? cfg->extraItemIconTable + (uint32_t) extra * (uint32_t) cfg->extraItemStride
        : cfg->itemIconTable + (uint32_t) item * (uint32_t) (cfg->itemIconStride ? cfg->itemIconStride : 8);
    if (!pd_rom_read(g, entry, w, 8)) return false;
    const uint8_t* tilesLz = pd_rom_at(g, u32le(w), 4);
    const uint8_t* palLz = pd_rom_at(g, u32le(w + 4), !cfg->itemPalCompressed ? 32 : 4);
    if (!tilesLz || !palLz) return false;
    uint8_t tiles[9 * 32], pal[32];
    size_t left = g->romSize - (size_t) (tilesLz - g->rom), palLeft = g->romSize - (size_t) (palLz - g->rom);
    // Newer expansion smol-compresses the tiles (DecompIconSource.fetchItem).
    if (tilesLz[0] == 0x10) {
        if (pd_lz77(tilesLz, left, tiles, sizeof(tiles)) < sizeof(tiles)) return false;
    } else if (pd_smol(tilesLz, left, tiles, sizeof(tiles)) != sizeof(tiles)) {
        return false;
    }
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
