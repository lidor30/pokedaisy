#include "pd_map.h"

#include <stdio.h>

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

// --- a FireRed 1.0 hack's own map, read from its ROM (the app's RomRegionMap.read) ---
// CFRU and most binary hacks keep FireRed 1.0's region_map.c where it is and repoint its data: the literal-pool words that
// code loads its tiles, palette, tilemaps and section tables from say where each hack keeps them, each landing on data of
// the right shape or the ROM gets no map of its own. The screens stay whole (the app crops the backdrop border off), so a
// section's rectangle is its corner + the grid's 4-tile inset.
#define RRM_LIT_GFX 0xC0330u
#define RRM_LIT_PAL 0xC02ECu
#define RRM_LIT_SEVII 0xC00BCu
#define RRM_SEVII_CMP 0xC0064u
#define RRM_LIT_NAMES 0xC0C94u
#define RRM_LIT_CORNERS 0xC3D3Cu
#define RRM_LIT_DIMS 0xC3D38u
#define RRM_FIRST 0x58
#define RRM_SECTIONS 109
#define RRM_NONE 0xC5
#define RRM_GRID_W 22
#define RRM_GRID_H 15
#define RRM_FIRERED_TILES 0x3EF61Cu

struct rom_map {
    bool ok;
    struct pd_mapsec_rect rects[RRM_FIRST + RRM_SECTIONS]; // page -1 = not a section here
    struct pd_region_layout layouts[PD_MAP_PAGES];
    uint8_t grid[PD_MAP_PAGES][2][RRM_GRID_W * RRM_GRID_H];
};
static struct rom_map* romMap; // for cachedRom

static uint32_t rrm_u32(const struct pd_game* g, uint32_t off, bool* ok) {
    const uint8_t* p = pd_rom_at(g, 0x08000000u + off, 4);
    if (!p) { *ok = false; return 0; }
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}
// The ROM offset a literal pool word points at (false when off the ROM).
static bool rrm_ptr(const struct pd_game* g, uint32_t lit, uint32_t* out) {
    bool ok = true;
    uint32_t v = rrm_u32(g, lit, &ok);
    if (!ok || v < 0x08000000u || v - 0x08000000u >= g->romSize) return false;
    *out = v - 0x08000000u;
    return true;
}
// LZ77 at a ROM offset into a new buffer; its size within [min, max].
static uint8_t* rrm_lz(const struct pd_game* g, uint32_t off, size_t min, size_t max, size_t* size) {
    const uint8_t* src = pd_rom_at(g, 0x08000000u + off, 4);
    if (!src || src[0] != 0x10) return NULL;
    size_t n = src[1] | (size_t) src[2] << 8 | (size_t) src[3] << 16;
    if (n < min || n > max) return NULL;
    uint8_t* out = malloc(n < 30 * 20 * 2 ? 30 * 20 * 2 : n);
    if (!out) return NULL;
    size_t avail = g->romSize - off;
    if (pd_lz77(src, avail < 0x12000 ? avail : 0x12000, out, n) != n) { free(out); return NULL; }
    *size = n;
    return out;
}

// RomRegionMap.backdropCrop's verdict alone: whether the screen has none of the backdrop border.
static bool rrm_uncut(const uint32_t* img, uint32_t backdrop) {
    for (int x = 0; x < PD_MAP_W; x++) if (img[x] != backdrop) return true;  // the top row isn't plain
    return false;
}

static bool read_rom_map(const struct pd_game* g, struct pd_map_art* art, struct rom_map* rm) {
    uint8_t rev = 0;
    if (strcmp(g->code, "BPRE") || !pd_rom_read(g, 0x080000BC, &rev, 1) || rev == 1) return false;
    uint32_t gfxOff, palOff, mapOff[4], namesOff, cornersOff, dimsOff, seviiOff;
    static const uint32_t LIT_MAPS[4] = { 0xC035C, 0xC0370, 0xC0388, 0xC03A4 };
    static const uint32_t LIT_GRIDS[4] = { 0xC4194, 0xC419C, 0xC41A4, 0xC41CC };
    if (!rrm_ptr(g, RRM_LIT_GFX, &gfxOff) || !rrm_ptr(g, RRM_LIT_PAL, &palOff)) return false;
    size_t tilesSize = 0;
    uint8_t* tiles = rrm_lz(g, gfxOff, 32, 0x12000, &tilesSize);
    if (!tiles || tilesSize % 32) { free(tiles); return false; }
    const uint8_t* palRaw = pd_rom_at(g, 0x08000000u + palOff, 16 * 32);
    bool ok = palRaw != NULL;
    uint32_t pal[256] = {0};
    if (ok) palette(palRaw, 256, pal);
    art->hasBackdrop = ok;
    art->backdrop = ok ? pal[0] : 0;
    // Hacks with one region point every slot at the same tilemap: one page each distinct screen.
    uint32_t distinct[4];
    int nDistinct = 0, imageOf[4];
    for (int i = 0; ok && i < 4; i++) {
        ok = rrm_ptr(g, LIT_MAPS[i], &mapOff[i]);
        int k = 0;
        while (ok && k < nDistinct && distinct[k] != mapOff[i]) k++;
        if (ok && k == nDistinct) distinct[nDistinct++] = mapOff[i];
        imageOf[i] = k;
    }
    for (int k = 0; ok && k < nDistinct; k++) {
        size_t n = 0;
        uint8_t* map = rrm_lz(g, distinct[k], 30 * 20, 0x800, &n);
        if (!map) { ok = false; break; }
        // A tilemap cut short of the whole screen (Unbound's) ends, from its last whole row, in its top-left tile.
        for (size_t i = n / 60 * 60; i < 30 * 20 * 2; i++) map[i] = map[i & 1];
        screen_4bpp(tiles, tilesSize, pal, 256, map, art->page[k]);
        free(map);
    }
    free(tiles);
    ok = ok && rrm_ptr(g, RRM_LIT_NAMES, &namesOff) && rrm_ptr(g, RRM_LIT_CORNERS, &cornersOff) &&
         rrm_ptr(g, RRM_LIT_DIMS, &dimsOff) && rrm_ptr(g, RRM_LIT_SEVII, &seviiOff);
    const uint8_t* corners = ok ? pd_rom_at(g, 0x08000000u + cornersOff, RRM_SECTIONS * 4) : NULL;
    const uint8_t* dims = ok ? pd_rom_at(g, 0x08000000u + dimsOff, RRM_SECTIONS * 4) : NULL;
    const uint8_t* sevii = ok ? pd_rom_at(g, 0x08000000u + seviiOff, 3 * 30) : NULL;
    if (!corners || !dims || !sevii) return false;
    // A Sevii screen with none of the main one's backdrop border is FireRed's tilemap in the hack's tiles: junk.
    bool junk[4] = { false };
    bool mainUncut = rrm_uncut(art->page[imageOf[0]], pal[0]);
    for (int k = 0; k < nDistinct; k++) junk[k] = k != imageOf[0] && rrm_uncut(art->page[k], pal[0]) && !mainUncut;
    // Where the Sevii sections start (InitRegionMap's `cmp r0, #SEVII_MAPSEC_START - 1` + bls), or none at all (Gaia).
    const uint8_t* cmp = pd_rom_at(g, 0x08000000u + RRM_SEVII_CMP, 4);
    int seviiStart = cmp && cmp[1] == 0x28 && cmp[3] == 0xD9 ? cmp[0] + 1 : 0x100;
    for (int i = 0; i < RRM_FIRST + RRM_SECTIONS; i++) rm->rects[i].page = -1;
    int sections = 0;
    for (int i = 0; i < RRM_SECTIONS; i++) {
        uint32_t at;
        if (!rrm_ptr(g, namesOff + (uint32_t) i * 4, &at)) break;
        const uint8_t* np = pd_rom_at(g, 0x08000000u + at, 1);
        if (!np || *np == 0xFF) continue;  // no name: not a section here
        int mapsec = RRM_FIRST + i, region = 0;
        if (mapsec >= seviiStart)
            for (int j = 0; j < 3 && !region; j++)
                for (int k = 0; k < 30 && sevii[j * 30 + k] != RRM_NONE; k++)
                    if (sevii[j * 30 + k] == mapsec) { region = j + 1; break; }
        int x = corners[i * 4] | corners[i * 4 + 1] << 8, y = corners[i * 4 + 2] | corners[i * 4 + 3] << 8;
        int w = dims[i * 4] | dims[i * 4 + 1] << 8, h = dims[i * 4 + 2] | dims[i * 4 + 3] << 8;
        int image = imageOf[region];
        struct pd_mapsec_rect* r = &rm->rects[mapsec];
        if ((x == 0 && y == 0) || w == 0 || h == 0 || junk[image] || x + 4 + w > 30 || y + 4 + h > 20) {
            *r = (struct pd_mapsec_rect) { (int8_t) (junk[image] ? imageOf[0] : image), 0, 0, 0, 0 };
        } else {
            *r = (struct pd_mapsec_rect) { (int8_t) image, (uint8_t) (x + 4), (uint8_t) (y + 4), (uint8_t) w, (uint8_t) h };
        }
        sections++;
    }
    if (!sections) return false;
    // FireRed's own Sevii tilemap in the hack's moved tiles, holding no section or hardly any placed on it: junk too.
    if (gfxOff != RRM_FIRERED_TILES) {
        for (int k = 0; k < nDistinct; k++) {
            if (k == imageOf[0] || junk[k] || (distinct[k] != 0x3F0AFC && distinct[k] != 0x3F0C0C && distinct[k] != 0x3F0CF0)) continue;
            int on = 0, placed = 0;
            for (int m = 0; m < RRM_FIRST + RRM_SECTIONS; m++)
                if (rm->rects[m].page == k) { on++; placed += rm->rects[m].w > 0; }
            if (on == 0 || placed * 4 < on) {
                junk[k] = true;
                for (int m = 0; m < RRM_FIRST + RRM_SECTIONS; m++)
                    if (rm->rects[m].page == k) rm->rects[m] = (struct pd_mapsec_rect) { (int8_t) imageOf[0], 0, 0, 0, 0 };
            }
        }
    }
    // Each page's cursor grid (the first region drawn on it), kept only if 9 in 10 of its cells name this ROM's sections.
    int kept[4], nKept = 0;
    for (int k = 0; k < nDistinct; k++) if (!junk[k]) kept[nKept++] = k;
    for (int pi = 0; pi < nKept; pi++) {
        int k = kept[pi], region = 0;
        while (imageOf[region] != k) region++;
        uint32_t gridOff;
        const uint8_t* grid = rrm_ptr(g, LIT_GRIDS[region], &gridOff) ? pd_rom_at(g, 0x08000000u + gridOff, 2 * RRM_GRID_W * RRM_GRID_H) : NULL;
        int named = 0, known = 0;
        for (int i = 0; grid && i < 2 * RRM_GRID_W * RRM_GRID_H; i++)
            if (grid[i] != RRM_NONE) { named++; known += grid[i] < RRM_FIRST + RRM_SECTIONS && rm->rects[grid[i]].page >= 0; }
        rm->layouts[pi] = (struct pd_region_layout) { 4, 4, RRM_GRID_W, RRM_GRID_H, RRM_NONE, { NULL, NULL } };
        if (grid && named && known * 10 >= named * 9) {
            memcpy(rm->grid[pi], grid, sizeof rm->grid[pi]);
            rm->layouts[pi].layers[0] = rm->grid[pi][0];
            rm->layouts[pi].layers[1] = rm->grid[pi][1];
        }
        if (pi != k) memcpy(art->page[pi], art->page[k], sizeof art->page[pi]);
    }
    // the dropped screens' sections onto the kept pages' numbering
    for (int m = 0; m < RRM_FIRST + RRM_SECTIONS; m++) {
        if (rm->rects[m].page < 0) continue;
        int pi = 0;
        while (pi < nKept && kept[pi] != rm->rects[m].page) pi++;
        rm->rects[m].page = (int8_t) (pi < nKept ? pi : 0);
    }
    art->pages = nKept;
    art->ok = rm->ok = true;
    return true;
}

static bool rom_map_on(const struct pd_game* g) {
    return romMap && romMap->ok && cachedRom == pd_rom_id(g);
}

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

static bool plain(const uint32_t* px, int x0, int y0, int w, int h, uint32_t backdrop) {
    for (int y = y0; y < y0 + h; y++)
        for (int x = x0; x < x0 + w; x++)
            if (px[y * PD_MAP_W + x] != backdrop) return false;
    return true;
}

static bool border_colour(uint32_t c) {
    int r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
    return (r < 24 && g < 24 && b < 24) || (r > 240 && g > 240 && b > 240);
}

static bool border_line(const uint32_t* px, int x0, int y0, int w, int h) {
    for (int y = y0; y < y0 + h; y++)
        for (int x = x0; x < x0 + w; x++)
            if (!border_colour(px[y * PD_MAP_W + x])) return false;
    return true;
}

// The BG's backdrop off by whole tiles (RomRegionMap.backdropCrop), then rows and columns of solid black or white
// (regionMapContentBounds): only those, so a map edge that's all sea stays, and at most a quarter of each side goes.
static void find_crop(const uint32_t* px, bool hasBackdrop, uint32_t backdrop, int16_t* out) {
    int left = 0, right = 30, top = 0, bottom = 20;
    if (hasBackdrop) {
        while (top < 5 && plain(px, 0, top * 8, PD_MAP_W, 8, backdrop)) top++;
        while (bottom > 15 && plain(px, 0, bottom * 8 - 8, PD_MAP_W, 8, backdrop)) bottom--;
        while (left < 7 && plain(px, left * 8, top * 8, 8, (bottom - top) * 8, backdrop)) left++;
        while (right > 23 && plain(px, right * 8 - 8, top * 8, 8, (bottom - top) * 8, backdrop)) right--;
    }
    int l0 = left * 8, t0 = top * 8, r0 = right * 8, b0 = bottom * 8;
    int w = r0 - l0, h = b0 - t0, l = l0, r = r0, t = t0, b = b0;
    while (l < l0 + w / 4 && border_line(px, l, t, 1, b - t)) l++;
    while (r > r0 - w / 4 && border_line(px, r - 1, t, 1, b - t)) r--;
    while (t < t0 + h / 4 && border_line(px, l, t, r - l, 1)) t++;
    while (b > b0 - h / 4 && border_line(px, l, b - 1, r - l, 1)) b--;
    out[0] = (int16_t) l;
    out[1] = (int16_t) t;
    out[2] = (int16_t) r;
    out[3] = (int16_t) b;
}

static const struct pd_map_art* load_art(const struct pd_game* g);

const struct pd_map_art* pd_map_art(const struct pd_game* g) {
    if (!g->rom || (g->kind != PD_GAME_FIRERED && g->kind != PD_GAME_EMERALD && g->kind != PD_GAME_GB)) return NULL;
    if (cachedRom == pd_rom_id(g)) return cached && cached->ok ? cached : NULL;
    free(cached);
    free(romMap);
    romMap = NULL;
    cached = calloc(1, sizeof(*cached));
    cachedRom = pd_rom_id(g);
    if (!cached) return NULL;
    const struct pd_map_art* art = load_art(g);
    for (int i = 0; art && g->kind != PD_GAME_GB && i < art->pages; i++)
        find_crop(cached->page[i], cached->hasBackdrop, cached->backdrop, cached->crop[i]);
    return art;
}

static const struct pd_map_art* load_art(const struct pd_game* g) {
    if (g->kind == PD_GAME_GB) return pd_gb_map_draw(g, cached) ? cached : NULL;
    cached->w = PD_MAP_W;
    cached->h = PD_MAP_H;
    const struct pd_game_entry* e = g->entry;
    if (e && e->regionPages) {
        hack_pages(g, e);
        return cached->ok ? cached : NULL;
    }
    // A FireRed 1.0 hack that keeps its own map where region_map.c finds it (the hacks whose names are read that way,
    // pd_text.mapsecsRom, or that the app reads with RomRegionMap): that map, its rects and grids; the player's head
    // stays the fingerprinted one below.
    bool own = false;
    bool romRegion = (g->cfg && g->cfg->text && g->cfg->text->mapsecsRom) || (e && e->romRegionMap);
    if (romRegion && (romMap = calloc(1, sizeof(*romMap)))) {
        own = read_rom_map(g, cached, romMap);
        if (!own) {
            free(romMap);
            romMap = NULL;
            memset(cached, 0, sizeof(*cached));
            cached->w = PD_MAP_W;
            cached->h = PD_MAP_H;
        }
    }

    const uint8_t* blob[PD_BLOB_COUNT];
    for (int i = 0; i < PD_BLOB_COUNT; i++) blob[i] = pd_romart_blob(g, (enum pd_blob) i);
    uint32_t pal[256];
    if (g->kind == PD_GAME_FIRERED) {
        if (!own && blob[PD_FR_REGION_GFX] && blob[PD_FR_REGION_PAL] && blob[PD_FR_KANTO_MAP]) {
            int colours = (int) pd_blob_sigs[PD_FR_REGION_PAL].size / 2;
            palette(blob[PD_FR_REGION_PAL], colours, pal);
            // FireRed's four, whatever a hack's PD_MAP_PAGES allows: a page past them had no map and no name.
            static const int MAPS[] = { PD_FR_KANTO_MAP, PD_FR_SEVII123_MAP, PD_FR_SEVII45_MAP, PD_FR_SEVII67_MAP };
            for (int p = 0; p < (int) (sizeof(MAPS) / sizeof(MAPS[0])) && blob[MAPS[p]]; p++) {
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
    if (rom_map_on(g)) {
        *n = cached->pages;
        return romMap->layouts;
    }
    if (g->kind == PD_GAME_GB) {  // no cursor grid: the smallest section rect (pd_map_pick)
        *n = 0;
        return NULL;
    }
    if (g->kind == PD_GAME_EMERALD) {
        *n = pd_layouts_emerald_count;
        return pd_layouts_emerald;
    }
    *n = pd_layouts_firered_count;
    return pd_layouts_firered;
}

static const struct pd_mapsec_rect* rect_of(const struct pd_game* g, int mapsec) {
    if (rom_map_on(g))
        return mapsec >= 0 && mapsec < RRM_FIRST + RRM_SECTIONS && romMap->rects[mapsec].page >= 0 ? &romMap->rects[mapsec] : NULL;
    if (g->kind == PD_GAME_GB) {
        int n;
        const struct pd_mapsec_rect* r = pd_gb_map_rects(g, &n);
        return r && mapsec >= 0 && mapsec < n ? &r[mapsec] : NULL;
    }
    if (g->entry && g->entry->regionPages) {
        // A hack's own sections, from the app's MapSecData (pd_games_gen.c).
        static struct pd_mapsec_rect r;
        const struct pd_game_entry* e = g->entry;
        if (mapsec < 0 || mapsec >= e->mapsecAtCount || e->mapsecAt[mapsec][0] == 255) return NULL;
        const unsigned char* a = e->mapsecAt[mapsec];
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
    if (g->entry && g->entry->regionPages) return 0;
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
    if (g->entry && g->entry->regionPages) {
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
    if (g->kind == PD_GAME_GB) {  // the smallest rect there, the first section of that size
        int n, best = 0;
        const struct pd_mapsec_rect* r = pd_gb_map_rects(g, &n);
        for (int i = 0; r && i < n; i++) {
            if (r[i].page != page || !r[i].w || !r[i].h || !*pd_mapsec_name(g, i)) continue;
            if (tx < r[i].x || ty < r[i].y || tx >= r[i].x + r[i].w || ty >= r[i].y + r[i].h) continue;
            if (*mapsec < 0 || r[i].w * r[i].h < best) {
                *mapsec = i;
                best = r[i].w * r[i].h;
            }
        }
        return;
    }
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

bool pd_map_bounds(const struct pd_game* g, int page, int* minX, int* minY, int* maxX, int* maxY) {
    const struct pd_map_art* art = pd_map_art(g);
    if (!art || page < 0 || page >= art->pages) return false;
    int count;
    const struct pd_region_layout* ls = layouts(g, &count);
    if (page < count) {
        *minX = ls[page].offX;
        *minY = ls[page].offY;
        *maxX = ls[page].offX + ls[page].w - 1;
        *maxY = ls[page].offY + ls[page].h - 1;
    } else {
        *minX = *minY = 0;
        *maxX = art->w / 8 - 1;
        *maxY = art->h / 8 - 1;
    }
    return true;
}

int pd_map_player_section(const struct pd_game* g, const struct pd_snapshot* s) {
    return pd_gb_map_gen1(g) && s->posOk ? s->mapNum : s->mapsec;
}

bool pd_map_player_tile(const struct pd_game* g, const struct pd_snapshot* s, int* page, int* tx, int* ty) {
    static int lastSec = -1, lastX, lastY;
    struct pd_tile_rect r;
    int sec = pd_map_player_section(g, s);
    if (!s->valid || pd_map_tiles(g, sec, page, &r, 1) < 1) return false;
    if (outdoor(s->mapType) && s->posOk && s->mapW > 0 && s->mapH > 0) {
        int sw = s->mapW / r.w, sh = s->mapH / r.h;
        int dx = s->x / (sw > 1 ? sw : 1), dy = s->y / (sh > 1 ? sh : 1);
        if (dx > r.w - 1) dx = r.w - 1;
        if (dy > r.h - 1) dy = r.h - 1;
        if (dx < 0) dx = 0;
        if (dy < 0) dy = 0;
        *tx = r.x + dx;
        *ty = r.y + dy;
        lastSec = sec;
        lastX = *tx;
        lastY = *ty;
    } else if (lastSec == sec && lastX >= r.x && lastX < r.x + r.w && lastY >= r.y && lastY < r.y + r.h) {
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
    if (rom_map_on(g)) {  // a hack's own screens: the app's "MAP n"
        static char name[16];
        snprintf(name, sizeof name, "MAP %d", page + 1);
        return name;
    }
    if (g->kind == PD_GAME_GB) return pd_gb_map_page_name(g, page);
    if (g->kind == PD_GAME_EMERALD) return "HOENN";
    static const char* const NAMES[] = { "KANTO", "SEVII 1-3", "SEVII 4-5", "SEVII 6-7" };
    return page >= 0 && page < (int) (sizeof(NAMES) / sizeof(NAMES[0])) ? NAMES[page] : "";
}
