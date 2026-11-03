#include "pd_map.h"

#include "pd_games.h"

#include <stdlib.h>
#include <string.h>

// --- drawing (RomArt's screen / emeraldRegionMap / sprite) ---

static uint32_t bgr555(uint16_t c) {
    uint32_t r = c & 0x1F, g = (c >> 5) & 0x1F, b = (c >> 10) & 0x1F;
    r = r << 3 | r >> 2;
    g = g << 3 | g >> 2;
    b = b << 3 | b >> 2;
    return r << 16 | g << 8 | b;
}

static void palette(const uint8_t* raw, int colours, uint32_t* out) {
    for (int i = 0; i < colours; i++) out[i] = bgr555((uint16_t) (raw[i * 2] | raw[i * 2 + 1] << 8));
}

// FireRed: 4bpp tiles, a 30x20 u16 tilemap (tile, flips, palette).
static void screen_4bpp(const uint8_t* tiles, size_t tilesSize, const uint32_t* pal, int palCount, const uint8_t* map,
                        uint32_t* out) {
    for (int ty = 0; ty < 20; ty++) {
        for (int tx = 0; tx < 30; tx++) {
            uint16_t e = (uint16_t) (map[(ty * 30 + tx) * 2] | map[(ty * 30 + tx) * 2 + 1] << 8);
            int t = e & 0x3FF, pn = e >> 12;
            bool hf = e & 0x400, vf = e & 0x800;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int sx = hf ? 7 - x : x, sy = vf ? 7 - y : y;
                    size_t at = (size_t) t * 32 + sy * 4 + sx / 2;
                    int v = at < tilesSize ? (sx & 1 ? tiles[at] >> 4 : tiles[at] & 0xF) : 0;
                    int ci = pn * 16 + v;
                    out[(ty * 8 + y) * PD_MAP_W + tx * 8 + x] = ci < palCount ? pal[ci] : pal[0];
                }
            }
        }
    }
}

// Emerald: 8bpp tiles, a 64-wide u8 affine map, palette loaded at slot 7 (112).
static void screen_8bpp_from(const uint8_t* tiles, size_t tilesSize, const uint32_t* pal, int palCount, const uint8_t* map,
                             uint32_t* out, int first) {
    for (int ty = 0; ty < 20; ty++) {
        for (int tx = 0; tx < 30; tx++) {
            int t = map[ty * 64 + tx];
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    size_t at = (size_t) t * 64 + y * 8 + x;
                    int ci = (at < tilesSize ? tiles[at] : 0) - first;
                    out[(ty * 8 + y) * PD_MAP_W + tx * 8 + x] = ci >= 0 && ci < palCount ? pal[ci] : pal[0];
                }
            }
        }
    }
}

static void screen_8bpp(const uint8_t* tiles, size_t tilesSize, const uint32_t* pal, int palCount, const uint8_t* map, uint32_t* out) {
    screen_8bpp_from(tiles, tilesSize, pal, palCount, map, out, 112);
}

// A 32-wide u16 tilemap of 4bpp tiles (Dreamstone's TOWN MAP).
static void screen_4bpp_wide(const uint8_t* tiles, size_t tilesSize, const uint32_t* pal, int palCount, const uint8_t* map, uint32_t* out) {
    static uint8_t cut[30 * 20 * 2];
    for (int ty = 0; ty < 20; ty++) memcpy(cut + ty * 60, map + ty * 64, 60);
    screen_4bpp(tiles, tilesSize, pal, palCount, cut, out);
}

// Monomyth's ATHANTO MAP (RomArt's monomythRegionMap): 8bpp tiles loaded from tile 0x60 through a 32x32 u16 tilemap (flips
// allowed), shown from row 2, its palette at slot 7. A pixel outside that palette stays clear.
static void screen_monomyth(const uint8_t* tiles, size_t tilesSize, const uint32_t* pal, int palCount, const uint8_t* map, uint32_t* out) {
    for (int i = 0; i < PD_MAP_W * PD_MAP_H; i++) out[i] = PD_MAP_CLEAR;
    for (int ty = 0; ty < 20; ty++) {
        for (int tx = 0; tx < 30; tx++) {
            int o = ((ty + 2) * 32 + tx) * 2, e = map[o] | map[o + 1] << 8, t = (e & 0x3FF) - 0x60;
            if (t < 0 || (size_t) (t + 1) * 64 > tilesSize) continue;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int sx = e & 0x400 ? 7 - x : x, sy = e & 0x800 ? 7 - y : y;
                    int v = tiles[t * 64 + sy * 8 + sx] - 112;
                    if (v >= 0 && v < palCount) out[(ty * 8 + y) * PD_MAP_W + tx * 8 + x] = pal[v];
                }
            }
        }
    }
}

// A 16x16 sprite from 2x2 4bpp tiles; index 0 is see-through.
static void sprite16(const uint8_t* gfx, const uint32_t* pal, uint32_t* out) {
    for (int y = 0; y < 16; y++) {
        for (int x = 0; x < 16; x++) {
            int tile = (y / 8) * 2 + x / 8;
            uint8_t b = gfx[tile * 32 + (y % 8) * 4 + (x % 8) / 2];
            int v = x & 1 ? b >> 4 : b & 0xF;
            out[y * 16 + x] = v ? pal[v] : PD_MAP_CLEAR;
        }
    }
}

static struct pd_map_art* cached;
static unsigned cachedRom;

// A hack's own pages, by fingerprint (RomArt's REGION blobs).
static void hack_pages(const struct pd_game* g, const struct pd_game_entry* e) {
    const struct pd_blob_sig* sigs[3 * PD_MAP_PAGES + 2];
    uint8_t* blobs[3 * PD_MAP_PAGES + 2];
    int n = e->regionPageCount < PD_MAP_PAGES ? e->regionPageCount : PD_MAP_PAGES;
    for (int i = 0; i < n; i++) {
        sigs[3 * i] = e->regionPages[i].gfx;
        sigs[3 * i + 1] = e->regionPages[i].pal;
        sigs[3 * i + 2] = e->regionPages[i].map;
    }
    int total = 3 * n;
    bool head = n > 0 && e->regionPages[0].headGfx;
    if (head) {
        sigs[total++] = e->regionPages[0].headGfx;
        sigs[total++] = e->regionPages[0].headPal;
    }
    pd_romart_find(g, sigs, total, blobs);
    static uint32_t pal[256];
    for (int i = 0; i < n; i++) {
        const struct pd_region_page* p = &e->regionPages[i];
        uint8_t *gfx = blobs[3 * i], *colours = blobs[3 * i + 1], *map = blobs[3 * i + 2];
        // Pages can share a palette: the scan fills only the first with that fingerprint.
        for (int k = 0; !colours && k < 3 * n; k++)
            if (sigs[k] == p->pal) colours = blobs[k];
        for (int k = 0; !map && k < 3 * n; k++)
            if (sigs[k] == p->map) map = blobs[k];
        if (!gfx || !colours || !map) break;
        int count = (int) p->pal->size / 2;
        if (count > 256) count = 256;
        palette(colours, count, pal);
        if (p->kind == 2) screen_monomyth(gfx, p->gfx->size, pal, count, map, cached->page[i]);
        else if (p->kind == 1) screen_4bpp_wide(gfx, p->gfx->size, pal, count, map, cached->page[i]);
        else screen_8bpp_from(gfx, p->gfx->size, pal, count, map, cached->page[i], p->firstColour);
        cached->pages = i + 1;
        cached->ok = true;
    }
    if (head && blobs[total - 2] && blobs[total - 1]) {
        palette(blobs[total - 1], 16, pal);
        sprite16(blobs[total - 2], pal, cached->head[0]);
        memcpy(cached->head[1], cached->head[0], sizeof(cached->head[0]));
        cached->hasHead[0] = cached->hasHead[1] = true;
    }
    for (int i = 0; i < total; i++) free(blobs[i]);
}

// RomRegionMap: FireRed 1.0's region_map.c stays where it is in most binary hacks and CFRU, with its data repointed: the words
// its literal pools load say where each hack keeps its map.
#define LIT_GFX 0xC0330
#define LIT_PAL 0xC02EC
#define LIT_SEVII 0xC00BC
#define SEVII_CMP 0xC0064
#define LIT_CORNERS 0xC3D3C
#define LIT_DIMS 0xC3D38
#define FIRST_MAPSEC 0x58
#define SECTIONS 109
#define MAPSEC_NONE 0xC5
#define GRID_OFFSET 4
#define SCREEN_BYTES (30 * 20 * 2)
#define FIRERED_TILES 0x3EF61C

static bool rom_ptr(const struct pd_game* g, uint32_t at, uint32_t* out) {
    if (at + 4 > g->romSize) return false;
    const uint8_t* b = g->rom + at;
    uint32_t p = (uint32_t) b[0] | (uint32_t) b[1] << 8 | (uint32_t) b[2] << 16 | (uint32_t) b[3] << 24;
    if (p < 0x08000000 || p - 0x08000000 >= g->romSize) return false;
    *out = p - 0x08000000;
    return true;
}

// LZ77 of whatever size its header says, malloc'd.
static uint8_t* rom_lz(const struct pd_game* g, uint32_t off, size_t* size) {
    if (off + 4 > g->romSize || g->rom[off] != 0x10) return NULL;
    *size = g->rom[off + 1] | (size_t) g->rom[off + 2] << 8 | (size_t) g->rom[off + 3] << 16;
    if (*size == 0 || *size > 0x12000) return NULL;
    uint8_t* out = malloc(*size);
    if (out && pd_lz77(g->rom + off, g->romSize - off, out, *size) != *size) {
        free(out);
        out = NULL;
    }
    return out;
}

// A row or column of plain backdrop, the BG's transparent colour: junk Sevii screens have none of the main one's border.
static bool screen_bordered(const uint32_t* px, uint32_t backdrop) {
    for (int x = 0; x < PD_MAP_W; x++)
        if (px[x] != backdrop) return false;
    return true;
}

static void rom_pages(const struct pd_game* g) {
    static const uint32_t LIT_TILEMAPS[4] = { 0xC035C, 0xC0370, 0xC0388, 0xC03A4 };
    static const uint32_t FIRERED_SEVII[3] = { 0x3F0AFC, 0x3F0C0C, 0x3F0CF0 };
    uint32_t gfxAt, palAt, sevii, corners, dims;
    if (!rom_ptr(g, LIT_GFX, &gfxAt) || !rom_ptr(g, LIT_PAL, &palAt) || !rom_ptr(g, LIT_SEVII, &sevii) ||
        !rom_ptr(g, LIT_CORNERS, &corners) || !rom_ptr(g, LIT_DIMS, &dims)) return;
    size_t tilesSize = 0;
    uint8_t* tiles = rom_lz(g, gfxAt, &tilesSize);
    if (!tiles || tilesSize % 32) {
        free(tiles);
        return;
    }
    static uint32_t pal[256];
    palette(g->rom + palAt, 256, pal);
    // Hacks with one region point every slot at the same tilemap: one page each.
    uint32_t distinct[4];
    int imageOf[4], count = 0;
    for (int i = 0; i < 4; i++) {
        uint32_t at;
        if (!rom_ptr(g, LIT_TILEMAPS[i], &at)) {
            free(tiles);
            return;
        }
        int k = 0;
        while (k < count && distinct[k] != at) k++;
        if (k == count) distinct[count++] = at;
        imageOf[i] = k;
    }
    static uint8_t screen[SCREEN_BYTES];
    bool bordered[4] = { false }, junk[4] = { false };
    for (int i = 0; i < count; i++) {
        size_t size = 0;
        uint8_t* map = rom_lz(g, distinct[i], &size);
        if (!map || size < SCREEN_BYTES / 2 || size > 0x800) {
            free(map);
            free(tiles);
            return;
        }
        // A tilemap cut short of the whole screen (Unbound's) ends in its top-left tile, the border's.
        size_t n = size < SCREEN_BYTES ? size : SCREEN_BYTES;
        memcpy(screen, map, n);
        for (size_t k = size / 60 * 60; k < SCREEN_BYTES && size < SCREEN_BYTES; k++) screen[k] = map[k & 1];
        free(map);
        screen_4bpp(tiles, tilesSize, pal, 256, screen, cached->page[i]);
        bordered[i] = screen_bordered(cached->page[i], pal[0]);
    }
    free(tiles);
    for (int i = 0; i < count; i++) junk[i] = i != imageOf[0] && !bordered[i] && bordered[imageOf[0]];

    const uint8_t* cmp = g->rom + SEVII_CMP;
    int seviiStart = cmp[1] == 0x28 && cmp[3] == 0xD9 ? cmp[0] + 1 : 0xFFFF;
    const uint8_t* seviiList = g->rom + sevii;
    const uint8_t* cornerList = g->rom + corners;
    const uint8_t* dimList = g->rom + dims;
    if (sevii + 90 > g->romSize || corners + SECTIONS * 4 > g->romSize || dims + SECTIONS * 4 > g->romSize) return;
    memset(cached->sections, 0, sizeof(cached->sections));
    for (int i = 0; i < 256; i++) cached->sections[i].page = -1;
    int placedOn[4] = { 0 }, onPage[4] = { 0 };
    for (int i = 0; i < SECTIONS; i++) {
        int mapsec = FIRST_MAPSEC + i, region = 0;
        if (mapsec >= seviiStart) {
            for (int j = 0; j < 3 && !region; j++) {
                for (int k = 0; k < 30; k++) {
                    int m = seviiList[j * 30 + k];
                    if (m == MAPSEC_NONE) break;
                    if (m == mapsec) region = j + 1;
                }
            }
        }
        int x = cornerList[i * 4] | cornerList[i * 4 + 1] << 8, y = cornerList[i * 4 + 2] | cornerList[i * 4 + 3] << 8;
        int w = dimList[i * 4] | dimList[i * 4 + 1] << 8, h = dimList[i * 4 + 2] | dimList[i * 4 + 3] << 8;
        int image = imageOf[region];
        bool off = (x == 0 && y == 0) || w == 0 || h == 0 || junk[image];
        struct pd_mapsec_rect* r = &cached->sections[mapsec];
        r->page = (int8_t) (junk[image] ? imageOf[0] : image);
        if (!off) {
            r->x = (uint8_t) (x + GRID_OFFSET);
            r->y = (uint8_t) (y + GRID_OFFSET);
            r->w = (uint8_t) w;
            r->h = (uint8_t) h;
            placedOn[image]++;
        }
        onPage[image]++;
    }
    // FireRed's own Sevii tilemap in the hack's moved tiles, holding hardly anything placed: junk too.
    if (gfxAt != FIRERED_TILES) {
        for (int i = 0; i < count; i++) {
            bool fireRed = false;
            for (int k = 0; k < 3; k++) fireRed |= distinct[i] == FIRERED_SEVII[k];
            if (i == imageOf[0] || junk[i] || !fireRed) continue;
            if (!onPage[i] || placedOn[i] * 4 < onPage[i]) {
                junk[i] = true;
                for (int m = 0; m < 256; m++) {
                    if (cached->sections[m].page != i) continue;
                    cached->sections[m] = (struct pd_mapsec_rect) { (int8_t) imageOf[0], 0, 0, 0, 0 };
                }
            }
        }
    }
    // Pages kept close up.
    int to[4], kept = 0;
    for (int i = 0; i < count; i++) {
        to[i] = -1;
        if (junk[i]) continue;
        if (kept != i) memcpy(cached->page[kept], cached->page[i], sizeof(cached->page[0]));
        to[i] = kept++;
    }
    for (int m = 0; m < 256; m++)
        if (cached->sections[m].page >= 0) cached->sections[m].page = (int8_t) to[cached->sections[m].page];
    cached->pages = kept;
    cached->ok = kept > 0;
    cached->romSections = true;
}

const struct pd_map_art* pd_map_art(const struct pd_game* g) {
    if (!g->rom || (g->kind != PD_GAME_FIRERED && g->kind != PD_GAME_EMERALD)) return NULL;
    if (cachedRom == pd_rom_id(g)) return cached && cached->ok ? cached : NULL;
    free(cached);
    cached = calloc(1, sizeof(*cached));
    cachedRom = pd_rom_id(g);
    if (!cached) return NULL;
    const struct pd_game_entry* e = g->entry;
    if (e && e->regionPages) {
        hack_pages(g, e);
        return cached->ok ? cached : NULL;
    }
    if (e && e->romRegionMap) {
        rom_pages(g);
        if (cached->ok) return cached;
    }

    const uint8_t* blob[PD_BLOB_COUNT];
    for (int i = 0; i < PD_BLOB_COUNT; i++) blob[i] = pd_romart_blob(g, (enum pd_blob) i);
    uint32_t pal[256];
    if (g->kind == PD_GAME_FIRERED) {
        if (blob[PD_FR_REGION_GFX] && blob[PD_FR_REGION_PAL] && blob[PD_FR_KANTO_MAP]) {
            int colours = (int) pd_blob_sigs[PD_FR_REGION_PAL].size / 2;
            palette(blob[PD_FR_REGION_PAL], colours, pal);
            static const int MAPS[PD_MAP_PAGES] = {
                PD_FR_KANTO_MAP, PD_FR_SEVII123_MAP, PD_FR_SEVII45_MAP, PD_FR_SEVII67_MAP,
            };
            for (int p = 0; p < PD_MAP_PAGES && blob[MAPS[p]]; p++) {
                screen_4bpp(blob[PD_FR_REGION_GFX], pd_blob_sigs[PD_FR_REGION_GFX].size, pal, colours,
                            blob[MAPS[p]], cached->page[p]);
                cached->pages = p + 1;
            }
            cached->ok = true;
        }
        if (blob[PD_FR_PLAYER_PAL]) {
            // Red's palette serves Leaf too (the game does the same).
            palette(blob[PD_FR_PLAYER_PAL], 16, pal);
            for (int s = 0; s < 2; s++) {
                const uint8_t* gfx = blob[s ? PD_FR_PLAYER_LEAF_GFX : PD_FR_PLAYER_RED_GFX];
                if (!gfx) continue;
                sprite16(gfx, pal, cached->head[s]);
                cached->hasHead[s] = true;
            }
        }
    } else {
        if (blob[PD_EM_REGION_GFX] && blob[PD_EM_REGION_PAL] && blob[PD_EM_REGION_MAP]) {
            int colours = (int) pd_blob_sigs[PD_EM_REGION_PAL].size / 2;
            palette(blob[PD_EM_REGION_PAL], colours, pal);
            screen_8bpp(blob[PD_EM_REGION_GFX], pd_blob_sigs[PD_EM_REGION_GFX].size, pal, colours,
                        blob[PD_EM_REGION_MAP], cached->page[0]);
            cached->pages = 1;
            cached->ok = true;
        }
        static const int HEADS[2][2] = {
            { PD_EM_PLAYER_BRENDAN_GFX, PD_EM_PLAYER_BRENDAN_PAL }, { PD_EM_PLAYER_MAY_GFX, PD_EM_PLAYER_MAY_PAL },
        };
        for (int s = 0; s < 2; s++) {
            if (!blob[HEADS[s][0]] || !blob[HEADS[s][1]]) continue;
            palette(blob[HEADS[s][1]], 16, pal);
            sprite16(blob[HEADS[s][0]], pal, cached->head[s]);
            cached->hasHead[s] = true;
        }
    }
    return cached->ok ? cached : NULL;
}

// --- sections, the cursor grid, the player (RegionMapModel) ---

static const struct pd_region_layout* layouts(const struct pd_game* g, int* n) {
    if (g->kind == PD_GAME_EMERALD) {
        *n = pd_layouts_emerald_count;
        return pd_layouts_emerald;
    }
    *n = pd_layouts_firered_count;
    return pd_layouts_firered;
}

static const struct pd_mapsec_rect* rect_of(const struct pd_game* g, int mapsec) {
    if (cached && cached->romSections) {
        return mapsec >= 0 && mapsec < 256 && cached->sections[mapsec].page >= 0 ? &cached->sections[mapsec] : NULL;
    }
    const struct pd_table_set* t = g->tables;
    if (g->entry && g->entry->regionPages && t) {
        // A hack's own sections, from the app's tables.
        static struct pd_mapsec_rect r;
        if (mapsec < 0 || mapsec >= t->mapsecAtCount || t->mapsecAt[mapsec][0] == 255) return NULL;
        const unsigned char* a = t->mapsecAt[mapsec];
        r = (struct pd_mapsec_rect) { (int8_t) a[0], a[1], a[2], a[3], a[4] };
        return &r;
    }
    if (g->kind == PD_GAME_EMERALD) {
        return mapsec >= 0 && mapsec < pd_mapsec_rects_emerald_count ? &pd_mapsec_rects_emerald[mapsec] : NULL;
    }
    return mapsec >= 0 && mapsec < pd_mapsec_rects_firered_count ? &pd_mapsec_rects_firered[mapsec] : NULL;
}

static int layout_at(const struct pd_region_layout* l, int layer, int tx, int ty) {
    if (!l->layers[layer]) return -1;
    int x = tx - l->offX, y = ty - l->offY;
    if (x < 0 || y < 0 || x >= l->w || y >= l->h) return -1;
    int v = l->layers[layer][y * l->w + x];
    return v == l->none ? -1 : v;
}

int pd_map_tiles(const struct pd_game* g, int mapsec, int* page, struct pd_tile_rect* out, int max) {
    const struct pd_mapsec_rect* r = rect_of(g, mapsec);
    if (!r || r->page < 0 || max < 1) return 0;
    *page = r->page;
    if (r->w > 0 && r->h > 0) {
        out[0] = (struct pd_tile_rect) { r->x, r->y, r->w, r->h };
        return 1;
    }
    if ((cached && cached->romSections) || (g->entry && g->entry->regionPages)) return 0;
    // Only on the grid (a dungeon): every cell naming it, on its page.
    int n = 0, count;
    const struct pd_region_layout* ls = layouts(g, &count);
    if (r->page >= count) return 0;
    const struct pd_region_layout* l = &ls[r->page];
    for (int layer = 0; layer < 2; layer++) {
        for (int ty = l->offY; ty < l->offY + l->h; ty++) {
            for (int tx = l->offX; tx < l->offX + l->w; tx++) {
                if (layout_at(l, layer, tx, ty) != mapsec) continue;
                bool dup = false;
                for (int k = 0; k < n; k++) dup |= out[k].x == tx && out[k].y == ty;
                if (!dup && n < max) out[n++] = (struct pd_tile_rect) { tx, ty, 1, 1 };
            }
        }
    }
    return n;
}

void pd_map_pick(const struct pd_game* g, int page, int tx, int ty, int* mapsec, int* dungeon) {
    if ((cached && cached->romSections) || (g->entry && g->entry->regionPages)) {
        // No grid for these: the smallest section under the tile.
        int best = 1 << 30;
        *mapsec = *dungeon = -1;
        for (int m = 0; m < 256; m++) {
            const struct pd_mapsec_rect* r = rect_of(g, m);
            if (!r || r->page != page || !r->w || tx < r->x || ty < r->y || tx >= r->x + r->w || ty >= r->y + r->h) continue;
            if (r->w * r->h < best) {
                best = r->w * r->h;
                *mapsec = m;
            }
        }
        return;
    }
    int count;
    const struct pd_region_layout* ls = layouts(g, &count);
    *mapsec = *dungeon = -1;
    if (page < 0 || page >= count) return;
    *mapsec = layout_at(&ls[page], 0, tx, ty);
    *dungeon = layout_at(&ls[page], 1, tx, ty);
    // Only sections the game names count.
    if (*mapsec >= 0 && !rect_of(g, *mapsec)) *mapsec = -1;
    if (*dungeon >= 0 && !rect_of(g, *dungeon)) *dungeon = -1;
}

// TOWN, CITY, ROUTE, UNDERWATER, OCEAN_ROUTE: the maps the region map places
// the player inside of; indoors the head stays on the last outdoor tile.
static bool outdoor(int mapType) {
    return mapType == 1 || mapType == 2 || mapType == 3 || mapType == 5 || mapType == 6;
}

bool pd_map_player_tile(const struct pd_game* g, const struct pd_snapshot* s, int* page, int* tx, int* ty) {
    static int lastSec = -1, lastX, lastY;
    struct pd_tile_rect r;
    if (!s->valid || pd_map_tiles(g, s->mapsec, page, &r, 1) < 1) return false;
    if (outdoor(s->mapType) && s->posOk && s->mapW > 0 && s->mapH > 0) {
        int sw = s->mapW / r.w, sh = s->mapH / r.h;
        int dx = s->x / (sw > 1 ? sw : 1), dy = s->y / (sh > 1 ? sh : 1);
        if (dx > r.w - 1) dx = r.w - 1;
        if (dy > r.h - 1) dy = r.h - 1;
        if (dx < 0) dx = 0;
        if (dy < 0) dy = 0;
        *tx = r.x + dx;
        *ty = r.y + dy;
        lastSec = s->mapsec;
        lastX = *tx;
        lastY = *ty;
    } else if (lastSec == s->mapsec && lastX >= r.x && lastX < r.x + r.w && lastY >= r.y && lastY < r.y + r.h) {
        *tx = lastX;
        *ty = lastY;
    } else {
        *tx = r.x;
        *ty = r.y;
    }
    return true;
}

const char* pd_map_page_name(const struct pd_game* g, int page) {
    if (g->entry && g->entry->regionPages) return page >= 0 && page < g->entry->regionPageCount ? g->entry->regionPages[page].label : "";
    if (cached && cached->romSections) return page == 0 ? "MAP" : "MORE";
    if (g->kind == PD_GAME_EMERALD) return "HOENN";
    static const char* const NAMES[PD_MAP_PAGES] = { "KANTO", "SEVII 1-3", "SEVII 4-5", "SEVII 6-7" };
    return page >= 0 && page < PD_MAP_PAGES ? NAMES[page] : "";
}
