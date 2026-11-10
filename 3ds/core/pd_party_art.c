#include "pd_party_art.h"

#include <stdlib.h>
#include <string.h>

#include "pd_style.h"

// BGR555 to 0xFFRRGGBB, with mGBA's 5 to 8 bit expansion.
static uint32_t color_at(const uint8_t* pal, int i) {
    int c = pal[i * 2] | pal[i * 2 + 1] << 8;
    int r = c & 31, g = c >> 5 & 31, b = c >> 10 & 31;
    return PD_ART_OPAQUE | (uint32_t) (r << 3 | r >> 2) << 16 | (uint32_t) (g << 3 | g >> 2) << 8 | (uint32_t) (b << 3 | b >> 2);
}

// Colour index of pixel (x, y) of 4bpp tile t: a hack's tilemap can point past its tiles.
static int px4(const uint8_t* tiles, int size, int t, int x, int y) {
    int at = t * 32 + y * 4 + x / 2;
    if (at >= size) return 0;
    return x & 1 ? tiles[at] >> 4 & 15 : tiles[at] & 15;
}

static int blob_size(int b) {
    return (int) pd_blob_sigs[b].size;
}

// RomArt.sprite: consecutive tiles (or map's, one byte a tile) w x h, index 0 see-through.
static uint32_t* sprite(const uint8_t* tiles, int size, const uint32_t* cols, int w, int h, const uint8_t* map) {
    uint32_t* img = calloc((size_t) w * h, sizeof(*img));
    if (!img) return NULL;
    for (int ty = 0; ty < h / 8; ty++)
        for (int tx = 0; tx < w / 8; tx++) {
            int t = map ? map[ty * (w / 8) + tx] : ty * (w / 8) + tx;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    int v = px4(tiles, size, t, x, y);
                    if (v) img[(ty * 8 + y) * w + tx * 8 + x] = cols[v];
                }
        }
    return img;
}

// Window palette 3 with each state's LoadPartyBoxPalette overrides: (4, 5, 6 <- ids1), (1, 7, 8 <- ids2).
static const unsigned char SLOT_IDS[PD_SLOT_FRAMES][6] = {
    { 52, 53, 54, 49, 55, 56 }, { 116, 117, 118, 97, 103, 104 }, { 84, 85, 86, 81, 87, 88 }, { 148, 149, 150, 97, 103, 104 },
    { 52, 53, 54, 49, 55, 56 }, { 116, 117, 118, 97, 103, 104 }, { 52, 53, 54, 49, 55, 56 },
};

static void art_free(struct pd_party_art* a) {
    for (int i = 0; i < PD_SLOT_FRAMES; i++) free(a->frames[i]);
    free(a->ball);
    free(a->status);
    free(a->hold);
    memset(a, 0, sizeof(*a));
}

static bool art_build(struct pd_party_art* a, const struct pd_game* g, const struct pd_party_style* st) {
    const uint8_t* gfx = pd_romart_blob(g, st->gfx);
    const uint8_t* pal = pd_romart_blob(g, st->pal);
    const uint8_t* main = pd_romart_blob(g, st->slotMain);
    const uint8_t* noHp = pd_romart_blob(g, st->slotNoHp);
    const uint8_t* empty = st->slotEmpty >= 0 ? pd_romart_blob(g, st->slotEmpty) : NULL;
    const uint8_t* status = pd_romart_blob(g, st->status);
    const uint8_t* statusPal = pd_romart_blob(g, st->statusPal);
    const uint8_t* font = pd_romart_blob(g, st->font);
    const uint8_t* ball = st->ball >= 0 ? pd_romart_blob(g, st->ball) : NULL;
    const uint8_t* ballPal = st->ball >= 0 ? pd_romart_blob(g, st->ballPal) : NULL;
    if (!gfx || !pal || !main || !noHp || !status || !statusPal || !font) return false;
    if ((st->slotEmpty >= 0 && !empty) || (st->ball >= 0 && (!ball || !ballPal))) return false;
    a->style = st;
    a->font = font;
    int w = st->tilesW * 8, h = st->tilesH * 8, size = blob_size(st->gfx);
    for (int f = 0; f < PD_SLOT_FRAMES; f++) {
        const uint8_t* map = f == PD_SLOT_EMPTY ? empty : f == PD_SLOT_NOHP || f == PD_SLOT_NOHP_SELECTED ? noHp : main;
        if (!map) continue;
        uint32_t cols[16];
        for (int i = 0; i < 16; i++) cols[i] = color_at(pal, 48 + i);
        static const unsigned char AT[6] = { 4, 5, 6, 1, 7, 8 };
        for (int i = 0; i < 6; i++) cols[AT[i]] = color_at(pal, SLOT_IDS[f][i]);
        if (!(a->frames[f] = sprite(gfx, size, cols, w, h, map))) return false;
    }
    uint32_t cols[16];
    for (int i = 0; i < 16; i++) cols[i] = color_at(statusPal, i);
    a->statusW = blob_size(st->status) / 32 * 8;
    if (!(a->status = sprite(status, blob_size(st->status), cols, a->statusW, 8, NULL))) return false;
    if (ball) {
        for (int i = 0; i < 16; i++) cols[i] = color_at(ballPal, i);
        if (!(a->ball = sprite(ball, blob_size(st->ball), cols, 32, 64, NULL))) return false;
    }
    // The held-item marker: FireRed's bytes, Emerald's the same; every slot style uses it.
    const uint8_t* hold = pd_romart_blob(g, PD_FR_HOLD_GFX);
    const uint8_t* holdPal = pd_romart_blob(g, PD_FR_HOLD_PAL);
    if (hold && holdPal) {
        for (int i = 0; i < 16; i++) cols[i] = color_at(holdPal, i);
        a->hold = sprite(hold, blob_size(PD_FR_HOLD_GFX), cols, 8, 16, NULL);
    }
    return true;
}

const struct pd_party_art* pd_party_art_for(const struct pd_game* g) {
    static struct pd_party_art art;
    static unsigned builtRom;
    static bool built;
    unsigned rom = pd_rom_id(g);
    if (!rom) return NULL;
    if (rom != builtRom) {
        art_free(&art);
        builtRom = rom;
        const struct pd_party_style* st = g->entry ? g->entry->partyStyle : NULL;
        built = st && art_build(&art, g, st);
        if (!built) art_free(&art);
    }
    return built ? &art : NULL;
}

int pd_party_glyph_row(const struct pd_party_art* a, int glyph, int y) {
    // FireRed keeps a glyph as two 8x8 tiles, Emerald as four (16x16) of which the left two count.
    int bytes = a->style->halfWidthFont ? 32 : 64, bottom = a->style->halfWidthFont ? 16 : 32;
    if ((glyph + 1) * bytes > blob_size(a->style->font)) return 0;
    const uint8_t* p = a->font + glyph * bytes + (y >> 3) * bottom + (y & 7) * 2;
    return p[0] | p[1] << 8;
}

// --- the backdrop ---

#define SCREEN_W 240
#define SCREEN_H 160
#define CELL 32
#define SCREEN_SLOTS_BOTTOM 128 // where the slots end and the text box starts

// The tile RomArt puts over CANCEL (the app has none): the plain panel.
#define PLAIN_PANEL 0x100E

static bool blanked(int blank, int tx, int ty) {
    if (blank == 2 && ty == 15 && tx >= 3 && tx <= 7) return true;
    return blank != 0 && tx >= 23 && (ty == 17 || ty == 18);
}

// RomArt.screen: x0, y0 onwards of the 32 x 32 entry tilemap, w x h pixels.
static uint32_t* screen(const uint8_t* tiles, int size, const uint8_t* pal, int colours, const uint8_t* map, int blank,
                        int x0, int y0, int w, int h) {
    uint32_t* img = malloc((size_t) w * h * sizeof(*img));
    if (!img) return NULL;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            int sx = x0 + x, sy = y0 + y, tx = sx / 8, ty = sy / 8, at = (ty * 32 + tx) * 2;
            int e = blanked(blank, tx, ty) ? PLAIN_PANEL : map[at] | map[at + 1] << 8;
            int v = px4(tiles, size, e & 0x3FF, e & 0x400 ? 7 - sx % 8 : sx % 8, e & 0x800 ? 7 - sy % 8 : sy % 8);
            int i = (e >> 12) * 16 + v;
            img[y * w + x] = color_at(pal, i < colours ? i : 0);
        }
    return img;
}

static struct {
    unsigned rom;
    uint32_t* art; // 0xFFRRGGBB, before the theme
    int w, h;
    bool tiled;
    uint32_t* view; // the screen, for the size and theme it was made at
    int viewW, viewH;
    uint32_t viewKey[3];
} back;

static void backdrop_load(const struct pd_game* g) {
    free(back.art);
    back.art = NULL;
    const struct pd_game_entry* e = g->entry;
    for (int i = 0; e && i < e->partyBackdropCount && !back.art; i++) {
        const struct pd_party_backdrop* b = &e->partyBackdrops[i];
        const uint8_t* gfx = pd_romart_blob(g, b->gfx);
        const uint8_t* pal = pd_romart_blob(g, b->pal);
        const uint8_t* map = pd_romart_blob(g, b->map);
        if (!gfx || !pal || !map) continue;
        int colours = blob_size(b->pal) / 2;
        back.tiled = !b->blank;
        back.w = back.tiled ? CELL : SCREEN_W;
        back.h = back.tiled ? CELL : SCREEN_H;
        // The cell at (32, 32): clear of the zig-zags between the slot columns, in the screen's own phase.
        back.art = screen(gfx, blob_size(b->gfx), pal, colours, map, b->blank, back.tiled ? CELL : 0, back.tiled ? CELL : 0, back.w, back.h);
        if (back.art && b->recolor) {
            for (int p = 0; p < back.w * back.h; p++)
                for (int k = 0; k < 3; k++)
                    if ((back.art[p] & 0xFFFFFF) == b->colors[k]) {
                        back.art[p] = PD_ART_OPAQUE | b->colors[3 + k];
                        break;
                    }
        }
    }
}

bool pd_party_screen(struct pd_canvas* c, const struct pd_game* g) {
    unsigned rom = pd_rom_id(g);
    if (!rom) return false;
    if (rom != back.rom) {
        back.rom = rom;
        back.viewW = 0;
        backdrop_load(g);
    }
    if (!back.art) return false;
    uint32_t from = 0, to = 0;
    for (int y = 0; y < c->h; y++) {
        // Past the slots' rows is the game's text box, and past the screen nothing: the backdrop goes on, a tile at a time.
        int sy = y < SCREEN_SLOTS_BOTTOM ? y : SCREEN_SLOTS_BOTTOM - 8 + y % 8;
        for (int x = 0; x < c->w; x++) {
            int sx = x < SCREEN_W ? x : SCREEN_W - 8 + x % 8;
            uint32_t p = back.tiled ? back.art[y % CELL * CELL + x % CELL] : back.art[sy * SCREEN_W + sx];
            if (p != from) {
                from = p;
                to = pd_backdrop_tint(from & 0xFFFFFF);
            }
            c->px[y * c->w + x] = to;
        }
    }
    return true;
}

bool pd_party_backdrop(struct pd_canvas* c, const struct pd_game* g) {
    unsigned rom = pd_rom_id(g);
    if (!rom) return false;
    bool fresh = rom != back.rom;
    if (fresh) {
        back.rom = rom;
        backdrop_load(g);
    }
    if (!back.art) return false;
    uint32_t key[3] = { pd_backdrop_tint(0xFFFFFF), pd_backdrop_tint(0x808080), pd_backdrop_tint(0) };
    if (fresh || c->w != back.viewW || c->h != back.viewH || memcmp(key, back.viewKey, sizeof(key))) {
        uint32_t* v = realloc(back.view, (size_t) c->w * c->h * sizeof(*v));
        if (!v) return false;
        back.view = v;
        back.viewW = c->w;
        back.viewH = c->h;
        memcpy(back.viewKey, key, sizeof(key));
        // A tiled cell goes at the slots' scale (GameBackdrop); a screen fills the canvas, cropped (ContentScale.Crop).
        int num = back.tiled ? 1 : c->w * back.h > c->h * back.w ? c->w : c->h;
        int den = back.tiled ? 1 : c->w * back.h > c->h * back.w ? back.w : back.h;
        int dx = back.tiled ? 0 : (back.w * num - c->w * den) / 2, dy = back.tiled ? 0 : (back.h * num - c->h * den) / 2;
        uint32_t from = 0, to = 0;
        for (int y = 0; y < c->h; y++) {
            int sy = (y * den + dy) / num % back.h;
            for (int x = 0; x < c->w; x++) {
                uint32_t p = back.art[sy * back.w + (x * den + dx) / num % back.w];
                if (p != from) {
                    from = p;
                    to = pd_backdrop_tint(p & 0xFFFFFF);
                }
                v[y * c->w + x] = to;
            }
        }
    }
    memcpy(c->px, back.view, (size_t) c->w * c->h * sizeof(*c->px));
    return true;
}

// The games' type tags (sMenuInfoIcons, 16 tiles a row): where each type's tag starts, in tiles.
static const struct { const char* name; int tile; } TYPE_TAGS[] = {
    { "NORMAL", 0x20 }, { "FIRE", 0x24 }, { "WATER", 0x28 }, { "GRASS", 0x2C }, { "ELECTRIC", 0x40 }, { "ROCK", 0x44 },
    { "GROUND", 0x48 }, { "ICE", 0x4C }, { "FLYING", 0x60 }, { "FIGHTING", 0x64 }, { "GHOST", 0x68 }, { "BUG", 0x6C },
    { "POISON", 0x80 }, { "PSYCHIC", 0x84 }, { "STEEL", 0x88 }, { "DARK", 0x8C }, { "DRAGON", 0xA0 }, { "???", 0xA4 },
};

bool pd_type_tag_at(const char* name, int* x, int* y) {
    for (size_t i = 0; i < sizeof(TYPE_TAGS) / sizeof(*TYPE_TAGS); i++) {
        const char *a = TYPE_TAGS[i].name, *b = name;
        while (*a && *b && *a == (*b >= 'a' && *b <= 'z' ? *b - 32 : *b)) a++, b++;
        if (!*a && !*b) {
            *x = TYPE_TAGS[i].tile % 16 * 8;
            *y = TYPE_TAGS[i].tile / 16 * 8;
            return true;
        }
    }
    return false;
}

const uint32_t* pd_type_tags(const struct pd_game* g) {
    static uint32_t* sheet;
    static unsigned builtRom;
    unsigned rom = pd_rom_id(g);
    if (!rom) return NULL;
    if (rom != builtRom) {
        free(sheet);
        sheet = NULL;
        builtRom = rom;
        // The words are in the picture: English only.
        bool fr = g->kind == PD_GAME_FIRERED, em = g->kind == PD_GAME_EMERALD;
        if ((fr || em) && g->language == 'E') {
            enum pd_blob gfx = fr ? PD_FR_TYPE_TAGS_GFX : PD_EM_TYPE_TAGS_GFX, pal = fr ? PD_FR_TYPE_TAGS_PAL : PD_EM_TYPE_TAGS_PAL;
            const uint8_t* tiles = pd_romart_blob(g, gfx);
            const uint8_t* colors = pd_romart_blob(g, pal);
            if (tiles && colors) {
                uint32_t cols[16];
                for (int i = 0; i < 16; i++) cols[i] = color_at(colors, i);
                sheet = sprite(tiles, blob_size(gfx), cols, 128, 128, NULL);
            }
        }
    }
    return sheet;
}
