// PokeDaisy — EXPAND on the Game Boy (pk_wide_gb.c): the overworld of Pokémon Yellow, Crystal and the hacks in GB_ROMS
// rebuilt past the 160 x 144 screen.
//
// pk_wide.c hands every call over to this file while a Game Boy game is configured (pk_wide_gb_configure). The GBA's
// rebuild reads a map of metatiles; Gen 1 and Gen 2 keep less, and all of it at hand:
//
// - the map's blocks inside a 3-block border (wOverworldMap / wOverworldMapBlocks), which holds the connected maps'
//   edges and, elsewhere, the map's background / border block;
// - the tileset's blockset in ROM: 4 x 4 tile ids a block;
// - the tiles in VRAM, and the Color palettes as mGBA's renderer has them. Gen 1 has no colour per tile: a tile id's
//   palette is learned from the BG map's attributes wherever that tile is on screen. Crystal's is the tileset's palette
//   map in ROM (a nibble per tile id: palette + VRAM bank), as the game builds its own attribute map;
// - past the border, the connected maps go on (their blocks from the ROM, through the map headers), and past those the
//   background block.
//
// Where the screen sits in that world isn't taken from RAM (it's a frame ahead of the picture, and mid-step the
// scroll and the block pointer move at different times): the visible BG map's tile ids are matched against the
// world around RAM's guess, and the fine scroll is SCX / SCY as drawn. The match is the check too: a frame that
// isn't the field (a menu screen, a battle, a transition's wipe) doesn't match, and the strips go to a bar in the
// screen's own background. Text boxes are font tiles (0x60 and up) and don't count either way, nor does a window
// over part of the screen (Crystal's map name sign).
//
// Objects: what OAM holds (the player, Pikachu, NPCs on screen - one stepping off the edge carries on into the
// strips), plus the NPCs the game doesn't show because they're off screen, drawn standing where they stand: Gen 1's
// from wSpriteStateData1 / 2 (image index $FF; Gen 1 freezes them there) unless the game hides them for good (a
// toggleable object switched off: an item taken, a Pokémon caught); Crystal's from its object structs (OFF_SCREEN)
// and, further out, from the map's objects not loaded into one (where they'll appear, unless masked: an event flag,
// the time of day).

#include "pk_wide.h"

#include <stdbool.h>
#include <string.h>

#include <mgba/core/core.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gb/io.h>
#include <mgba/internal/gb/renderers/software.h>

#define GBW PK_WIDE_GB_W
#define GBH PK_WIDE_GB_H

/** A game's addresses (WRAM unless noted; D000-DFFF in WRAM bank 1). Gen 1's and Crystal's share where they mean the
 * same; the rest is one generation's (0 in the other's rows). */
#include "pk_wide_gb_profile.h"
#include "pk_wide_gb_polished_tables.h"   // pcWideConfig: Polished Crystal 3.2.3 (normal), by PC_WIDE_CRC
#include "pk_wide_gb_polished_lz.inc"       // pcLz

#define GB_LINEAR(bank, addr) ((uint32_t) (bank) * 0x4000u + ((addr) - 0x4000u))

static const struct GbGame { uint32_t crc; int gen; uint32_t a[GB_CONFIG]; } GB_ROMS[] = {
    // Pokémon Yellow (USA, Europe): pret/pokeyellow's symbols (its build is the cart byte for byte).
    { 0x7D527D62u, 1, { 0xC100, 0xC200, 0xC6E8, 0xD35E, 0xD360, 0xD362, 0xD366, 0xD367, 0xD36F, 0xD52A, 0xD3AC, 0xD056,
                        0xCFC3, 0xD5A5, 0xD5CD, GB_LINEAR(0x3F, 0x41F2), GB_LINEAR(0x3F, 0x43E4), 0xCC2D, 0xD746 } },
    // Pokémon Yellow Legacy v1.0.10: its pokeyellow.sym (its build is the patched cart byte for byte).
    { 0xE7D07A3Fu, 1, { 0xC100, 0xC200, 0xC6E8, 0xD3AC, 0xD3AE, 0xD3B0, 0xD3B4, 0xD3B5, 0xD3BD, 0xD578, 0xD3FA, 0xD057,
                        0xCFC3, 0xD5F3, 0xD61B, GB_LINEAR(0x3F, 0x41F2), GB_LINEAR(0x3F, 0x43E4), 0xCC2D, 0xD746 } },
    // Pokémon Crystal rev 1, rev 0, Australia: pret/pokecrystal's symbols, the same in all three builds.
#define GB_CRYSTAL { 0, 0, 0xC800, 0xD194, 0xDCB7, 0xD196, 0xD199, 0xD19E, 0xD1A8, 0xD1DC, 0xD19D, 0xD22D, 0xD0ED, 0, 0, \
                     GB_LINEAR(0x25, 0x4000), 0, 0xCF74, 0xDA72, 0xD1E6, 0x13, 0xD4D6, 0xD71E, 0xD81E, 0xD154, 0xD82E, \
                     GB_LINEAR(0x05, 0x4736), GB_LINEAR(0x01, 0x4273), 0xFF9E, 0xFFCF, 0xD269, 0xFF94 }
    { 0x3358E30Au, 2, GB_CRYSTAL },
    { 0xEE6F5188u, 2, GB_CRYSTAL },
    { 0xBB6DD80Cu, 2, GB_CRYSTAL },
#undef GB_CRYSTAL
    // Pokémon Kristall-Edition (Germany): Crystal's, SpriteMovementData 3 bytes later.
    { 0x616D85DEu, 2, { 0, 0, 0xC800, 0xD194, 0xDCB7, 0xD196, 0xD199, 0xD19E, 0xD1A8, 0xD1DC, 0xD19D, 0xD22D, 0xD0ED, 0, 0,
                        GB_LINEAR(0x25, 0x4000), 0, 0xCF74, 0xDA72, 0xD1E6, 0x13, 0xD4D6, 0xD71E, 0xD81E, 0xD154, 0xD82E,
                        GB_LINEAR(0x05, 0x4736), GB_LINEAR(0x01, 0x4276), 0xFF9E, 0xFFCF, 0xD269, 0xFF94 } },
    // Pokémon Crystal Legacy v1.3.1: its pokecrystal.sym (its build is the patched cart byte for byte).
    { 0x12E97FB4u, 2, { 0, 0, 0xC800, 0xD19F, 0xDCC2, 0xD1A1, 0xD1A4, 0xD1A9, 0xD1B3, 0xD1E7, 0xD1A8, 0xD238, 0xD0F8, 0, 0,
                        GB_LINEAR(0x25, 0x4000), 0, 0xCF74, 0xDA7D, 0xD1F1, 0x13, 0xD4E1, 0xD729, 0xD829, 0xD15F, 0xD839,
                        GB_LINEAR(0x05, 0x4586), GB_LINEAR(0x01, 0x4273), 0xFF9E, 0xFFCF, 0xD274, 0xFF94 } },
    // Pokémon Crystal Clear v2.6.0 and v2.5.11, each Standard Colors and Color Filter: Crystal's RAM (scripts/port_gen1.py)
    // but the tileset header (the palette map's bank at D1E4, its pointer a byte before Crystal's); the ROM tables from its
    // code, the same in all four. GB_CLEAR's differences: gbPalAttr, gbCrystalSprite, gbBlock and gbLz.
#define GB_CRYSTAL_CLEAR { 0, 0, 0xC800, 0xD194, 0xDCB7, 0xD196, 0xD199, 0xD19E, 0xD1A8, 0xD1DC, 0xD19D, 0xD22D, 0xD0ED, 0, 0, \
                           GB_LINEAR(0x25, 0x4000), 0, 0xCF74, 0xDA72, 0xD1E5, 0xD1E4, 0xD4D6, 0xD71E, 0xD81E, 0xD154, 0xD82E, \
                           GB_LINEAR(0x05, 0x4B92), GB_LINEAR(0x01, 0x459D), 0xFF9E, 0xFFCF, 0xD269, 0xFF94, 1 }
    { 0x17617CDEu, 2, GB_CRYSTAL_CLEAR },
    { 0x58F40DE1u, 2, GB_CRYSTAL_CLEAR },
    { 0x5AF9A949u, 2, GB_CRYSTAL_CLEAR },
    { 0xFC33D126u, 2, GB_CRYSTAL_CLEAR },
#undef GB_CRYSTAL_CLEAR
};

static bool gbPolished;
static pk_wide_gb_decode_fn gbDecode;
#define GB_CRYSTAL_OW_MAX 1300   // Crystal's wOverworldMap buffer
#define GB_OW_MAX 1326           // Polished Crystal's larger buffer
#define GB_MAP_TILES 0x60        // tile ids below this are the map's; the font (text boxes) starts here
#define GB_MIN_SCORE 900         // per mille of the screen's map tiles the world must match
#define GB_MIN_MAP_TILES 120     // ... and at least this many of its 360 tiles are map tiles (else it's a menu screen)
#define GB_OVERLAY_TILES 4       // fewer, all where the field last was: a box over a blanked field (SAVE)
#define GB_START_OPTION 5        // the START menu's OPTION row (counted with POKéDEX)
#define GB_START_TILE_L 0x79     // the START menu's box: '┌' at screen tile (10, 0), '┐' at (19, 0)
#define GB_START_TILE_R 0x7B
#define GB_MIX_STEP 16           // the strips <-> the glow crossfade over 16 frames
#define GB_FADE_LEVEL 12         // a frame this dark (or this near white) everywhere is a fade
#define GB_SEARCH 4              // tiles either way of RAM's guess the screen is looked for
#define GB_DOOR_FRAMES 30        // a screen of map tiles that doesn't match, this soon after the field: a map change

static const uint32_t* gbCfg;
static int gbGen;   // 1 Yellow, 2 Crystal
static struct GB* gbB;
static struct GBVideoSoftwareRenderer* gbR;

// What the drawing reads of the game, as the frame ended: its VRAM, its palettes (converted once a view, not per pixel)
// and Crystal Clear's two RAM bytes.
static const uint8_t* gbVram;   // the core's VRAM
static uint32_t gbPal[64];
static uint8_t gbClearRam[2];   // Crystal Clear: wDecoCarpet (DC10) and D9C2, for its room floor's attributes

static inline int gbClamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

/** WRAM, D000-DFFF always from bank 1: Crystal switches that bank mid-frame (whatever SVBK holds as the frame ends isn't
 * necessarily the game's state), and a game that never switches it (Yellow) has bank 1 there all along. */
static uint8_t gbRam(uint32_t a) {
  unsigned bank = a >> 16;
  a &= 0xffff;
  if (a >= 0xC000 && a < 0xD000)
    return gbB->memory.wram[a - 0xC000];
  if (a >= 0xD000 && a < 0xE000 && bank < 8)
    return gbB->memory.wram[(bank ? bank : 1) * 4096 + a - 0xD000];
  return 0;
}
static uint8_t gbHram(uint32_t a) { return a >= 0xFF80 && a < 0xFFFF ? gbB->memory.hram[a - 0xFF80] : 0; }
static uint16_t gbRam16(uint32_t a) { return (uint16_t) (gbRam(a) | gbRam(a + 1) << 8); }
static void gbRamCopy(uint8_t* dst, uint32_t a, int n) {
    for (int i = 0; i < n; i++) dst[i] = gbRam(a + (uint32_t) i);
}

/** ROM bytes at a linear offset, or NULL past its end. */
static const uint8_t* gbRom(uint32_t at, uint32_t n) {
    if (!gbB->memory.rom || at + n > gbB->memory.romSize || at + n < at) return NULL;
    return gbB->memory.rom + at;
}
static uint32_t gbLinear(int bank, uint16_t addr) {
    return addr < 0x4000 ? addr : (uint32_t) bank * 0x4000 + (addr - 0x4000u);
}

// --- the world ---------------------------------------------------------------------------------------

struct GbConn {
    bool on;
    int offX, offY;      // the connected map's block (0, 0) is wOverworldMap's block (offX, offY)
    int w, h;
    uint32_t blocks;     // its blocks (ROM, linear)
    int lz;              // Crystal Clear and Polished: their slot in gbLz
    uint32_t objs;       // Crystal: its object events (ROM, linear; 13 bytes each), nObjs of them
    int nObjs;
};

struct GbSnap {
    bool ok;
    uint8_t ow[GB_OW_MAX];
    int w, h;            // the map in blocks; wOverworldMap is (w + 6) x (h + 6)
    int guessX, guessY;  // RAM's screen top-left, in world tiles
    int px, py;          // the player's step (wXCoord / wYCoord)
    uint32_t blockset;   // ROM, linear
    int bgBlock, tileset;
    struct GbConn conn[4];   // north, south, west, east
    uint8_t spr1[256], spr2[256];
    uint8_t toggleFlags[32], toggleList[40];
    uint32_t palMap;     // Crystal: the tileset's palette map (ROM, linear), a nibble per tile id
    uint8_t objs[13 * 0x28], mapObjs[21 * 16], masks[21], used[64], vars[16];   // Crystal/Polished objects
    uint8_t tiles[4096], attrs[4096];
    uint8_t loadedPals[8];
    uint8_t scx, scy;    // Crystal: the scroll its objects' screen positions go with (hSCX / hSCY)
    bool battle, text;
    int startItem;       // the START menu item picked last, counted with the POKéDEX row (5 = OPTION)
};

static struct GbSnap gbSnaps[2];
static int gbPrev = -1;
// The last snapshot that matched the screen, and where: walking across a map connection, RAM holds the new map a few
// frames before its border has the old view in it - the screen still shows the old one, which this one has.
static struct GbSnap gbGood;
static int gbGoodTx, gbGoodTy;
static bool gbFromStart;   // the START menu was the last thing up on the field (cleared by the plain field, a battle)
static int memoBest = -2;  // pk_wide_gb_view's last search, -2 = none (another game, a reset)

/** Crystal: the map header of group:number (9 bytes: attributes bank, tileset, ..., attributes pointer at +3). */
static const uint8_t* gbCrystalMapHeader(int group, int number) {
    if (group < 1 || number < 1) return NULL;
    const uint8_t* g = gbRom(gbCfg[GB_MAP_HEADER_PTRS] + 2 * (uint32_t) (group - 1), 2);
    if (!g) return NULL;
    uint32_t bank = gbCfg[GB_MAP_HEADER_PTRS] / 0x4000;
    return gbRom(gbLinear((int)bank, (uint16_t)(g[0] | g[1] << 8)) +
                     (gbPolished ? 7 : 9) * (uint32_t)(number - 1),
                 gbPolished ? 7 : 9);
}

// Crystal Clear and Polished keep their blocks compressed in ROM. A connection's strip pointer is into the
// game's decompression buffer. Connected maps are unpacked here once per map. Three snapshots can
// hold four maps each; one that lost its slot draws the background block until it's taken again.
#define GB_LZ_SLOTS 12
#define GB_LZ_BASE 0xD000

static struct GbLz { uint32_t at; int n; uint8_t b[GB_OW_MAX]; } gbLz[GB_LZ_SLOTS];
static int gbLzNext;

/** pokecrystal's LZ (home/decompress.asm) from ROM offset `at`: the bytes written, or -1 if it isn't one that fits. */
static int gbLzDecode(uint32_t at, uint8_t* out, int cap) {
    const uint8_t* d = gbB->memory.rom;
    const uint32_t size = gbB->memory.romSize;
    int n = 0;
    if (!d) return -1;
    while (at + 2 < size) {
        int c = d[at], cmd, len;
        if (c == 0xFF) return n;
        if ((c & 0xE0) == 0xE0) {
            cmd = (c << 3) & 0xE0;
            len = ((c & 3) << 8 | d[at + 1]) + 1;
            at += 2;
        } else {
            cmd = c & 0xE0;
            len = (c & 0x1F) + 1;
            at++;
        }
        if (n + len > cap || at + (uint32_t) len + 2 > size) return -1;
        switch (cmd) {
        case 0x00:   // the bytes as they are
            memcpy(out + n, d + at, (size_t) len);
            at += (uint32_t) len;
            n += len;
            break;
        case 0x20:   // one byte, repeated
            memset(out + n, d[at++], (size_t) len);
            n += len;
            break;
        case 0x40:   // two bytes in turn
            for (int k = 0; k < len; k++) out[n++] = d[at + (uint32_t) (k & 1)];
            at += 2;
            break;
        case 0x60:   // zeros
            memset(out + n, 0, (size_t) len);
            n += len;
            break;
        default: {   // from what's already out: as it is (0x80), its bits reversed (0xA0), backwards (0xC0)
            int src = d[at] & 0x80 ? n - (d[at] & 0x7F) - 1 : d[at] << 8 | d[at + 1];
            at += d[at] & 0x80 ? 1 : 2;
            if (src < 0 || src >= n || (cmd == 0xC0 && src - len + 1 < 0)) return -1;
            for (int k = 0; k < len; k++) {
                uint8_t b = out[cmd == 0xC0 ? src - k : src + k];
                if (cmd == 0xA0) {
                    b = (uint8_t) ((b & 0xF0) >> 4 | (b & 0x0F) << 4);
                    b = (uint8_t) ((b & 0xCC) >> 2 | (b & 0x33) << 2);
                    b = (uint8_t) ((b & 0xAA) >> 1 | (b & 0x55) << 1);
                }
                out[n++] = b;
            }
        }
        }
    }
    return -1;
}

/** The slot holding the blocks at ROM offset `at`, unpacked if none does. */
static int gbLzLoad(uint32_t at) {
    for (int i = 0; i < GB_LZ_SLOTS; i++)
        if (gbLz[i].at == at) return i;
    int i = gbLzNext;
    gbLzNext = (gbLzNext + 1) % GB_LZ_SLOTS;
    gbLz[i].at = at;
    gbLz[i].n = gbPolished
        ? gbDecode(gbB->memory.rom, gbB->memory.romSize, at, gbLz[i].b, GB_OW_MAX)
        : gbLzDecode(at, gbLz[i].b, GB_CRYSTAL_OW_MAX);
    return i;
}

/** A connected map's blocks: where its block (0, 0) sits in the overworld array, its size and its blocks in ROM.
 * `src` is the strip's first block in that map's blocks (an address in its bank), `dst` where it went in the array. */
static void gbConnect(struct GbConn* c, const struct GbSnap* s, int bank, uint16_t blocks, int cw, int ch, uint16_t src,
                      uint16_t dst) {
    int unpacked = gbPolished ? (int)(gbCfg[PC_SCRATCH] & 0xFFFF) :
                   gbCfg[GB_CLEAR] ? GB_LZ_BASE : blocks;
    int from = (int)src - unpacked;
    int to = (int)dst - (int)(gbCfg[GB_OVERWORLD] & 0xFFFF);
    int W = s->w + 6;
    if (to < 0 || to >= W * (s->h + 6) || cw <= 0 || ch <= 0) return;
    int fromRow = from >= 0 ? from / cw : -((-from + cw - 1) / cw);
    c->offX = to % W - (from - fromRow * cw);
    c->offY = to / W - fromRow;
    c->w = cw;
    c->h = ch;
    c->blocks = gbLinear(bank, blocks);
    if (gbPolished || gbCfg[GB_CLEAR]) {
        c->lz = gbLzLoad(c->blocks);
        int count = gbLz[c->lz].n;
        c->on = gbPolished ? count == cw * ch : count >= cw * ch;
    } else {
        c->on = gbRom(c->blocks, (uint32_t)(cw * ch)) != NULL;
    }
}

/** A connected map's block `at`, or NULL. */
static const uint8_t* gbConnBlock(const struct GbConn* c, int at) {
    if (!gbPolished && !gbCfg[GB_CLEAR]) return gbRom(c->blocks + (uint32_t)at, 1);
    return gbLz[c->lz].at == c->blocks ? &gbLz[c->lz].b[at] : NULL;
}

static void pcWideConnectionEvents(struct GbConn *c, const uint8_t *at) {
    uint32_t ev = gbLinear(at[6], at[7] | at[8] << 8);
    static const int sizes[] = {2, 3, 5, 5, 5};
    c->nObjs = 0;
    for (int k = 0; k < 5 && ev; k++) {
      const uint8_t *n = gbRom(ev, 1);
      ev = n ? ev + 1 + n[0] * (uint32_t)sizes[k] : 0;
    }
    const uint8_t *n = ev ? gbRom(ev, 1) : NULL;
    if (n && n[0] <= 20 && gbRom(ev + 1, 13u * n[0])) {
      c->objs = ev + 1;
      c->nObjs = n[0];
    }
}

static void gbTake(struct GbSnap* s) {
    const uint32_t* A = gbCfg;
    s->ok = false;
    int h = gbRam(A[GB_MAP_HEIGHT]), w = gbRam(A[GB_MAP_HEIGHT] + 1);
    if (!w || !h || (w + 6) * (h + 6) > (gbPolished ? GB_OW_MAX : GB_CRYSTAL_OW_MAX)) return;
    s->w = w;
    s->h = h;
    gbRamCopy(s->ow, A[GB_OVERWORLD], (w + 6) * (h + 6));
    int rel = (int)gbRam16(A[GB_VIEW_PTR]) - (int)(A[GB_OVERWORLD] & 65535);
    if (rel < 0 || rel >= (w + 6) * (h + 6)) rel = 0;
    s->guessX = rel % (w + 6) * 4 + (gbRam(A[GB_YBLOCK] + 1) ? 2 : 0);
    s->guessY = rel / (w + 6) * 4 + (gbRam(A[GB_YBLOCK]) ? 2 : 0);
    s->py = gbRam(A[GB_YCOORD]);
    s->px = gbRam(A[GB_YCOORD] + 1);
    s->blockset = gbLinear(gbRam(A[GB_TILESET_BANK]), gbRam16(A[GB_TILESET_BANK] + 1));
    s->bgBlock = gbRam(A[GB_BG_BLOCK]);
    s->tileset = gbRam(A[GB_TILESET]);
    int flags = gbRam(A[GB_CONNECTIONS]);
    for (int d = 0; d < 4; d++) {
        struct GbConn* c = &s->conn[d];
        c->on = false;
        c->nObjs = 0;
        if (!(flags & (8 >> d))) continue;
        if (gbGen == 2) {
            // wNorth/South/West/EastMapConnection: group, number, strip pointer, strip location, ...
            uint32_t hdr = A[GB_CONNECTIONS] + 1 + 12 * (uint32_t) d;
            const uint8_t* mh = gbCrystalMapHeader(gbRam(hdr), gbRam(hdr + 1));
            // Only a map drawn with the same tileset: another tileset's tiles aren't in VRAM.
            if (!mh || mh[gbPolished ? 0 : 1] != s->tileset)
              continue;
            if (gbPolished) {
              const uint8_t *at =
                  gbRom(gbLinear(A[PC_MAP_ATTR_BANK], mh[2] | mh[3] << 8), 10);
              if (at) {
                gbConnect(c, s, at[3], at[4] | at[5] << 8, at[2], at[1],
                          gbRam16(hdr + 2), gbRam16(hdr + 4));
                // Polished combines scripts/events: counted scene (2),
                // callback (3), warp (5), coordinate (5), background (5).
                pcWideConnectionEvents(c, at);
              }
              continue;
            }
            const uint8_t* at = gbRom(gbLinear(mh[0], (uint16_t) (mh[3] | mh[4] << 8)), 11);
            if (!at) continue;
            gbConnect(c, s, at[3], (uint16_t) (at[4] | at[5] << 8), at[2], at[1], gbRam16(hdr + 2), gbRam16(hdr + 4));
            // Its people: the map events (in the scripts' bank) - 2 filler bytes, then warps (5 bytes each), coord events
            // (8), bg events (5), object events (13), each list after its count.
            c->nObjs = 0;
            uint32_t ev = gbLinear(at[6], (uint16_t) (at[9] | at[10] << 8)) + 2;
            static const int SIZE[3] = { 5, 8, 5 };
            for (int k = 0; k < 3 && ev; k++) {
                const uint8_t* n = gbRom(ev, 1);
                ev = n ? ev + 1 + (uint32_t) (n[0] * SIZE[k]) : 0;
            }
            const uint8_t* n = ev ? gbRom(ev, 1) : NULL;
            if (n && n[0] <= 16 && gbRom(ev + 1, 13u * n[0])) {
                c->objs = ev + 1;
                c->nObjs = n[0];
            }
            continue;
        }
        uint32_t hdr = A[GB_CONNECTIONS] + 1 + 11 * (uint32_t) d;
        int id = gbRam(hdr);
        const uint8_t* bank = gbRom(A[GB_MAP_HEADER_BANKS] + (uint32_t) id, 1);
        const uint8_t* ptr = gbRom(A[GB_MAP_HEADER_PTRS] + 2 * (uint32_t) id, 2);
        if (!bank || !ptr) continue;
        const uint8_t* mh = gbRom(gbLinear(bank[0], (uint16_t) (ptr[0] | ptr[1] << 8)), 5);
        if (!mh || mh[0] != s->tileset || !mh[1] || !mh[2]) continue;
        gbConnect(c, s, bank[0], (uint16_t) (mh[3] | mh[4] << 8), mh[2], mh[1], gbRam16(hdr + 1), gbRam16(hdr + 3));
    }
    s->battle = gbRam(A[GB_IN_BATTLE]) != 0;
    if (gbGen == 2) {
        // A wild battle's flashes run on the field before wBattleMode is set: DoBattleTransition's VBlank mode (Crystal's
        // VBLANK_CUTSCENE 1; Polished Crystal's is 3, its 1 is the magnet train's).
        s->battle = s->battle || gbHram(A[GB_VBLANK_MODE]) == (gbPolished ? 3 : 1);
        if (gbPolished) {
          gbRamCopy(s->tiles, A[PC_METATILES], 4096);
          gbRamCopy(s->attrs, A[PC_ATTRIBUTES], 4096);
          memset(s->objs, 0, sizeof s->objs);
          for (int i = 0; i < 13; i++)
            gbRamCopy(s->objs + i * 40, A[GB_OBJECTS] + i * 34, 34);
          memset(s->mapObjs, 0, sizeof s->mapObjs);
          for (int i = 0; i < 21; i++)
            gbRamCopy(s->mapObjs + i * 16, A[GB_MAP_OBJECTS] + i * 14, 14);
          gbRamCopy(s->masks, A[GB_OBJECT_MASKS], 21);
          gbRamCopy(s->loadedPals, A[PC_LOADED_PALS], 8);
          memset(s->used, 0, sizeof s->used);
          gbRamCopy(s->vars, A[GB_VAR_SPRITES], 11);
          s->scx = gbHram(A[GB_HSCX]);
          s->scy = gbHram(A[GB_HSCX] + 1);
          s->text = (gbRam(A[GB_FONT_LOADED]) >> 6) & 1;
          s->startItem = gbRam(A[GB_START_ITEM]);
          s->ok = true;
          return;
        }
        s->scx = gbHram(A[GB_HSCX]);
        s->scy = gbHram(A[GB_HSCX] + 1);
        s->palMap = gbLinear(A[GB_CLEAR] ? gbRam(A[GB_PAL_BANK]) : (int) A[GB_PAL_BANK], gbRam16(A[GB_PAL_MAP]));
        gbRamCopy(s->objs, A[GB_OBJECTS], (int) sizeof(s->objs));
        gbRamCopy(s->mapObjs, A[GB_MAP_OBJECTS], 16 * 16);
        gbRamCopy(s->masks, A[GB_OBJECT_MASKS], 16);
        gbRamCopy(s->used, A[GB_USED_SPRITES], (int) sizeof(s->used));
        gbRamCopy(s->vars, A[GB_VAR_SPRITES], (int) sizeof(s->vars));
        s->text = (gbRam(A[GB_FONT_LOADED]) >> 6) & 1;   // TEXT_STATE_F: a text box or a menu is up
        s->startItem = gbRam(A[GB_START_ITEM]);
        s->ok = gbRom(s->blockset, 256 * 16) != NULL && gbRom(s->palMap, A[GB_CLEAR] ? 256 : 128) != NULL;
        return;
    }
    gbRamCopy(s->spr1, A[GB_SPRITES1], 256);
    gbRamCopy(s->spr2, A[GB_SPRITES2], 256);
    gbRamCopy(s->toggleFlags, A[GB_TOGGLE_FLAGS], 32);
    gbRamCopy(s->toggleList, A[GB_TOGGLE_LIST], 40);
    s->text = gbRam(A[GB_FONT_LOADED]) & 1;
    bool gotDex = (gbRam(A[GB_EVENT_FLAGS] + 37 / 8) >> (37 % 8)) & 1;
    s->startItem = gbRam(A[GB_START_ITEM]) + (gotDex ? 0 : 1);
    s->ok = gbRom(s->blockset, 256 * 16) != NULL;
}

/** The block at the overworld array's (bx, by) - anywhere: past the array, a connected map on that side, else the
 * background block. */
static int gbBlockRaw(const struct GbSnap* s, int bx, int by);
static int gbBlock(const struct GbSnap* s, int bx, int by) {
    int b = gbBlockRaw(s, bx, by);
    if (gbGen != 2) return b;
    // Crystal's LoadMetatiles: block 0 is the border block (the rows above and below a map without a connection there
    // are left at 0), and an id wraps at 128 (its doubling into 8 bits; Crystal Clear and Polished use all 256).
    b = b ? b : s->bgBlock;
    return gbPolished || gbCfg[GB_CLEAR] ? b : b & 0x7F;
}
static int gbBlockRaw(const struct GbSnap* s, int bx, int by) {
    int W = s->w + 6, H = s->h + 6;
    if (bx >= 0 && by >= 0 && bx < W && by < H) return s->ow[by * W + bx];
    const bool side[4] = { by < 3, by >= s->h + 3, bx < 3, bx >= s->w + 3 };
    for (int d = 0; d < 4; d++) {
        const struct GbConn* c = &s->conn[d];
        if (!c->on || !side[d]) continue;
        int cx = bx - c->offX, cy = by - c->offY;
        if (cx < 0 || cy < 0 || cx >= c->w || cy >= c->h) continue;
        const uint8_t* b = gbConnBlock(c, cy * c->w + cx);
        if (b) return b[0];
    }
    return s->bgBlock;
}

/** The blockset's tile byte at world tile (tx, ty) - (0, 0) is the array's top-left block's. Crystal's bit 7 picks the
 * palette map's second half (the VRAM bank 1 tiles); the BG map holds the id without it. */
static int gbTile(const struct GbSnap* s, int tx, int ty) {
    int blk = gbBlock(s, tx >> 2, ty >> 2);
    if (gbPolished)
      return s->tiles[blk * 16 + (ty & 3) * 4 + (tx & 3)];
    const uint8_t* b = gbRom(s->blockset + (uint32_t) blk * 16, 16);
    return b ? b[(ty & 3) * 4 + (tx & 3)] : 0;
}

/** Crystal: a tile byte's attributes, from the palette map (palette in bits 0-2, VRAM bank 1 in bit 3). */
static inline uint8_t gbPalAttr(const struct GbSnap* s, int t) {
    if (gbCfg[GB_CLEAR]) {
        // A byte of attributes per tile id, but for the floor of the player's room (tile 1 of tileset $14): it takes
        // another tile's, the carpet's (wDecoCarpet) or with none down the home's, from a table at $2E90 (00:2E3A's code;
        // its bit 7 swaps the VRAM bank).
        int flip = 0;
        if (t == 1 && s->tileset == 0x14) {
            uint8_t carpet = (uint8_t) (gbClearRam[0] - 7);
            const uint8_t* home = gbRom(0x2E90u + (uint8_t) (gbClearRam[1] - 0x1D), 1);
            if (gbClearRam[0]) t = (uint8_t) ((carpet << 4 | carpet >> 4) + 0x0D);
            else if (home) {
                t = *home;
                flip = t & 0x80 ? 8 : 0;
            }
        }
        const uint8_t* b = gbRom(s->palMap + (uint32_t) t, 1);
        return b ? (uint8_t) (*b ^ flip) : 0;
    }
    const uint8_t* p = gbRom(s->palMap + ((uint32_t) t >> 1), 1);
    return p ? (uint8_t) ((t & 1 ? *p >> 4 : *p) & 0x0F) : 0;
}

static uint8_t gbAttrAt(const struct GbSnap *s, int tx, int ty, int tile) {
  if (gbPolished)
    return s
        ->attrs[gbBlock(s, tx >> 2, ty >> 2) * 16 + (ty & 3) * 4 + (tx & 3)];
  return gbPalAttr(s, tile);
}
/** What the BG map holds for world tile (tx, ty), as gbScreenTile reads it: the tile id, and on Crystal its VRAM bank
 * as 0x100. */
static inline int gbKey(const struct GbSnap* s, int tx, int ty) {
    int t = gbTile(s, tx, ty);
    if (gbGen != 2) return t;
    return (t & (gbPolished ? 0xff : 0x7f)) |
           ((gbAttrAt(s, tx, ty, t) & 8) ? 0x100 : 0);
}

/** A map tile, not text: below the font (0x60), in either bank. */
static inline bool gbMapKey(int k) { return (k & 0xFF) < GB_MAP_TILES; }

// --- the world's tile keys, once per map ---------------------------------------------------------------------
//
// Where the screen is gets looked for by comparing its tiles with the world's at many places; on a screen that isn't the
// field (a menu half drawn, the Pokégear's pages) that's the whole loaded world, every frame. Each world tile's key
// (gbKey: its block, the blockset, the palette map) is the same every frame until the map changes, so it's read once
// into a grid here. A grid is for exactly what gbKey reads from a snapshot (and Crystal Clear's two floor bytes, the
// connected maps' unpacked blocks).

#define GB_GRID_X0 20                // world tiles left of / above the array the searches reach
#define GB_GRID_Y0 18
#define GB_GRID_MAX 0x10000
#define GB_GRIDS 3

struct GbGrid {
    bool ok;
    unsigned used;
    int gw, gh;
    // what it was built from
    int w, h, bgBlock, tileset;
    uint32_t blockset, palMap;
    uint8_t ow[GB_OW_MAX];
    struct GbConn conn[4];
    uint8_t tiles[4096], attrs[4096];
    uint8_t clear[2];
    uint32_t lzAt[GB_LZ_SLOTS];
    uint16_t key[GB_GRID_MAX];
};
static struct GbGrid gbGrids[GB_GRIDS];
static unsigned gbGridClock;

static bool gbGridIs(const struct GbGrid* g, const struct GbSnap* s, const uint8_t clear[2]) {
    if (!g->ok || g->w != s->w || g->h != s->h || g->bgBlock != s->bgBlock || g->tileset != s->tileset ||
        g->blockset != s->blockset || g->palMap != s->palMap || memcmp(g->clear, clear, 2)) return false;
    for (int i = 0; i < GB_LZ_SLOTS; i++)
        if (g->lzAt[i] != gbLz[i].at) return false;
    for (int d = 0; d < 4; d++) {
        const struct GbConn *a = &g->conn[d], *b = &s->conn[d];
        if (a->on != b->on || (b->on && (a->offX != b->offX || a->offY != b->offY || a->w != b->w || a->h != b->h ||
                                         a->blocks != b->blocks || a->lz != b->lz))) return false;
    }
    if (memcmp(g->ow, s->ow, (size_t) ((s->w + 6) * (s->h + 6)))) return false;
    return !gbPolished || (!memcmp(g->tiles, s->tiles, sizeof(s->tiles)) && !memcmp(g->attrs, s->attrs, sizeof(s->attrs)));
}

/** The grid for snapshot s's world (built now if no grid is), or NULL for a world too big for one. */
static const struct GbGrid* gbGridFor(const struct GbSnap* s) {
    if (!s->ok) return NULL;
    int gw = (s->w + 6) * 4 + 2 * GB_GRID_X0, gh = (s->h + 6) * 4 + 2 * GB_GRID_Y0;
    if (gw * gh > GB_GRID_MAX) return NULL;
    uint8_t clear[2] = { 0, 0 };
    if (gbCfg[GB_CLEAR]) { clear[0] = gbClearRam[0]; clear[1] = gbClearRam[1]; }
    struct GbGrid* g = NULL;
    for (int i = 0; i < GB_GRIDS && !g; i++)
        if (gbGridIs(&gbGrids[i], s, clear)) g = &gbGrids[i];
    if (!g) {
        g = &gbGrids[0];
        for (int i = 1; i < GB_GRIDS; i++)
            if (!gbGrids[i].ok || (g->ok && gbGrids[i].used < g->used)) g = &gbGrids[i];
        g->ok = true;
        g->gw = gw;
        g->gh = gh;
        g->w = s->w;
        g->h = s->h;
        g->bgBlock = s->bgBlock;
        g->tileset = s->tileset;
        g->blockset = s->blockset;
        g->palMap = s->palMap;
        memcpy(g->ow, s->ow, sizeof(g->ow));
        memcpy(g->conn, s->conn, sizeof(g->conn));
        if (gbPolished) {
            memcpy(g->tiles, s->tiles, sizeof(g->tiles));
            memcpy(g->attrs, s->attrs, sizeof(g->attrs));
        }
        memcpy(g->clear, clear, 2);
        for (int i = 0; i < GB_LZ_SLOTS; i++) g->lzAt[i] = gbLz[i].at;
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++) g->key[y * gw + x] = (uint16_t) gbKey(s, x - GB_GRID_X0, y - GB_GRID_Y0);
    }
    g->used = ++gbGridClock;
    return g;
}

static inline int gbGridKey(const struct GbGrid* g, const struct GbSnap* s, int tx, int ty) {
    int x = tx + GB_GRID_X0, y = ty + GB_GRID_Y0;
    if (g && x >= 0 && y >= 0 && x < g->gw && y < g->gh) return g->key[y * g->gw + x];
    return gbKey(s, tx, ty);
}

// --- the screen ----------------------------------------------------------------------------------------

static uint8_t gbLcdc, gbScx, gbScy;   // as the frame was drawn
static bool gbCgb;
// What the screen shows: the BG map scrolled by SCX / SCY - or, while a text box or the START menu is up on Gen 1, the
// window over the whole screen, unscrolled (Gen 1 copies the screen with the box on it into the window's map and shows
// that; Crystal does the same for a moment as it opens one). A window over part of the screen (Crystal's map name sign)
// covers the BG there: those tiles count as neither map nor anything else.
static int gbMapBase;                // that map in VRAM (0x1800 / 0x1C00)
static int gbSrcX, gbSrcY;           // its scroll
static bool gbWinPart;               // a window over part of the screen, from (gbWinX, gbWinY) to the bottom right
static int gbWinX, gbWinY, gbWinBase;
static uint8_t gbTileAttr[256];      // Gen 1: the BG attributes each map tile id was last seen with on screen
static uint8_t gbAttrSeen[256];
static uint32_t gbAttrBlockset;      // ... for this blockset (another tileset forgets them)
static uint8_t gbAttrDefault;

#define GB_COVERED 0xFFFF            // a screen tile under the window

/** The screen's tile (column i, row j of the map's tiles in view) as gbKey has it, and its attributes. */
static inline int gbScreenTile(int i, int j, uint8_t* attr) {
    if (gbWinPart && j * 8 + 8 - (gbSrcY & 7) > gbWinY && i * 8 + 8 - (gbSrcX & 7) > gbWinX) return GB_COVERED;
    int at = gbMapBase + (((gbSrcY >> 3) + j) & 31) * 32 + (((gbSrcX >> 3) + i) & 31);
    uint8_t a = gbCgb ? gbVram[0x2000 + at] : 0;
    if (attr) *attr = a;
    int t = gbVram[at];
    return gbGen == 2 && (a & 8) ? t | 0x100 : t;
}

static uint16_t gbScreen[18][20];    // this frame's screen tiles (gbScreenTile), read once
static int gbScreenMap;              // ... how many are map tiles

/** Every map tile on screen is the world's at (tx, ty) - however few. */
static bool gbExact(const struct GbSnap* s, int tx, int ty) {
    for (int j = 0; j < 18; j++)
        for (int i = 0; i < 20; i++)
            if (gbMapKey(gbScreen[j][i]) && gbKey(s, tx + i, ty + j) != gbScreen[j][i]) return false;
    return true;
}

static void gbReadScreen(void) {
    gbScreenMap = 0;
    for (int j = 0; j < 18; j++)
        for (int i = 0; i < 20; i++) {
            gbScreen[j][i] = (uint16_t) gbScreenTile(i, j, NULL);
            gbScreenMap += gbMapKey(gbScreen[j][i]);
        }
}

/** Per mille of the screen's map tiles world tile (tx, ty) onwards matches, or -1 with too few map tiles. With
 * `quick`, gives up (0) as soon as it can't reach GB_MIN_SCORE. */
static int gbScore(const struct GbSnap* s, const struct GbGrid* g, int tx, int ty, bool quick) {
    if (gbScreenMap < GB_MIN_MAP_TILES) return -1;
    int miss = 0, allowed = gbScreenMap * (1000 - GB_MIN_SCORE) / 1000;
    for (int j = 0; j < 18; j++)
        for (int i = 0; i < 20; i++) {
            int t = gbScreen[j][i];
            if (!gbMapKey(t) || gbGridKey(g, s, tx + i, ty + j) == t) continue;
            if (++miss > allowed && quick) return 0;
        }
    return (gbScreenMap - miss) * 1000 / gbScreenMap;
}

/** Learns each map tile id's attributes (palette, flips, bank) from the screen. */
static void gbLearnAttrs(const struct GbSnap* s, int tx, int ty) {
    if (gbGen == 2) return;   // Crystal's come from the palette map
    if (s->blockset != gbAttrBlockset) {
        memset(gbAttrSeen, 0, sizeof(gbAttrSeen));
        gbAttrBlockset = s->blockset;
    }
    int votes[8] = { 0 };
    for (int j = 0; j < 18; j++)
        for (int i = 0; i < 20; i++) {
            uint8_t a;
            int t = gbScreenTile(i, j, &a);
            if (!gbMapKey(t) || gbTile(s, tx + i, ty + j) != t) continue;
            gbTileAttr[t] = a;
            gbAttrSeen[t] = 1;
            votes[a & 7]++;
        }
    int best = 0;
    for (int p = 1; p < 8; p++) if (votes[p] > votes[best]) best = p;
    gbAttrDefault = (uint8_t) best;
}

static inline uint32_t gbOpaque(uint32_t c, uint32_t alpha) { return (c & 0x00FFFFFFu) | alpha; }

// The colours the world is drawn in: the renderer's as the frame ends - or, while a screen of its own keeps the field's
// strips (OPTION, which loads palettes of its own), the field's as they last were (gbFieldPal).
static uint32_t gbFieldPal[64];
static bool gbUseFieldPal;

#ifdef COLOR_16_BIT
// The libretro build's renderer keeps 16-bit colours (5-6-5 with red high); everything here draws 32-bit XBGR like the
// frame it sits beside, rounded as the frame's own conversion is (green's low bit from its top).
static inline uint32_t gbLiveRaw(int i) {
    uint32_t c = gbR->palette[gbR->lookup[i]], r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
    g |= g >> 5;
    return ((b << 3 | b >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (r << 3 | r >> 2);
}
#else
static inline uint32_t gbLiveRaw(int i) { return gbR->palette[gbR->lookup[i]]; }
#endif

static inline uint32_t gbLive(int i) { return gbPal[i]; }

static void gbCopyLive(void) {
    gbVram = gbB->video.vram;
    for (int i = 0; i < 64; i++) gbPal[i] = gbLiveRaw(i);
    gbClearRam[0] = gbCfg[GB_CLEAR] ? gbRam(0xDC10) : 0;
    gbClearRam[1] = gbCfg[GB_CLEAR] ? gbRam(0xD9C2) : 0;
}

static inline uint32_t gbWorldColor(int i) { return gbUseFieldPal ? gbFieldPal[i] : gbLive(i); }

/** A BG colour: the world's (`world`), else the screen's own (a text tile in the middle). */
static inline uint32_t gbBgColor(uint8_t attr, int idx, uint32_t alpha, bool world) {
    int i = gbCgb ? (attr & 7) * 4 + idx : idx;
    return gbOpaque(world ? gbWorldColor(i) : gbLive(i), alpha);
}

static inline uint32_t gbObjColor(uint8_t attr, int idx, uint32_t alpha) {
    int i = 0x20 + (gbCgb ? (attr & 7) * 4 : ((attr >> 4) & 1) * 4) + idx;
    return gbOpaque(gbWorldColor(i), alpha);
}

/** Colour index (0..3) of a tile's pixel; `base` is the tile's offset in VRAM. */
static inline int gbTilePixel(uint32_t base, int x, int y) {
    uint8_t lo = gbVram[base + (uint32_t) y * 2], hi = gbVram[base + (uint32_t) y * 2 + 1];
    int bit = 7 - x;
    return ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
}

static inline uint32_t gbBgTileBase(int t, uint8_t attr) {
    uint32_t base = (gbLcdc & 0x10) ? (uint32_t) t * 16 : (uint32_t) (0x1000 + (int8_t) t * 16);
    return base + ((gbCgb && (attr & 8)) ? 0x2000u : 0u);
}

// --- writes while the screen is drawn --------------------------------------------------------------------
//
// The strips are drawn from VRAM and the palettes as the frame ends. A game that writes either while the screen is being
// drawn (a door's fade, the colours of the next map as a connection is crossed, Crystal's menus loading their font and
// copying the screen back in thirds) shows the lines above the write as they were - for a frame the strips can't match
// them, and the headless check doesn't count such a frame (pk_wide_gb_last_torn).

static void (*gbOrigWritePalette)(struct GBVideoRenderer*, int, uint16_t);
static void (*gbOrigWriteVRAM)(struct GBVideoRenderer*, uint16_t);
static struct GBVideoRenderer* gbHooked;
static struct GB* gbHookGb;
static bool gbPalTorn, gbLastPalTorn;

static inline void gbMarkTorn(void) {
    if (gbHookGb && gbHookGb->video.ly < GBH && (gbHookGb->memory.io[GB_REG_LCDC] & 0x80)) gbPalTorn = true;
}

static void gbHookWritePalette(struct GBVideoRenderer* r, int index, uint16_t value) {
    gbMarkTorn();
    gbOrigWritePalette(r, index, value);
}

static void gbHookWriteVRAM(struct GBVideoRenderer* r, uint16_t address) {
    gbMarkTorn();
    gbOrigWriteVRAM(r, address);
}

static void gbHookPalettes(struct GB* gb) {
    struct GBVideoRenderer* r = gb->video.renderer;
    gbHookGb = gb;
    if (!r || r == gbHooked || r->writePalette == gbHookWritePalette) return;
    gbOrigWritePalette = r->writePalette;
    gbOrigWriteVRAM = r->writeVRAM;
    r->writePalette = gbHookWritePalette;
    r->writeVRAM = gbHookWriteVRAM;
    gbHooked = r;
}

int pk_wide_gb_last_torn(void) { return gbLastPalTorn; }

// --- objects -------------------------------------------------------------------------------------------

// screen pixels; under: the lower half hides in grass; over: drawn over the game's own picture too (gbObjsOffScreen)
struct GbObj { int x, y, tile, attr, under; bool over; };

#define GB_MAX_OBJS 104
static struct GbObj gbObjs[GB_MAX_OBJS];
static int gbNObjs;
static const uint8_t *gbObjectRomTiles[GB_MAX_OBJS];

/** OAM as drawn, past the screen's edges too: an NPC walking off the right edge keeps its objects at x 168-176 for the
 * step (the hardware shows none of them; the strip shows all of them), one walking off the left or top edge wraps to
 * the byte's top. Gen 1 marks an unused entry by y $A0 (PrepareOAMData's clear), so that y is skipped - and an
 * all-zero entry (nothing written yet). */
static void gbObjsFromOam(void) {
    gbNObjs = 0;
    memset(gbObjectRomTiles, 0, sizeof gbObjectRomTiles);
    if (!(gbLcdc & 0x02)) return;
    bool tall = gbLcdc & 0x04;
    for (int i = 0; i < 40 && gbNObjs < GB_MAX_OBJS; i++) {
        const uint8_t* o = &gbB->video.oam.raw[i * 4];
        if (o[0] == 0xA0 || (o[0] == 0 && o[1] == 0)) continue;
        int x = o[1] - 8, y = o[0] - 16;
        if (x >= 200) x -= 256;   // wrapped: left of the screen
        if (y >= 200) y -= 256;   // above it
        int tile = tall ? o[2] & ~1 : o[2];
        gbObjs[gbNObjs++] = (struct GbObj) { x, y, tile, o[3], -1 };
        if (tall) gbObjs[gbNObjs++] = (struct GbObj) { x, y + 8, tile | 1, o[3] | 0x100, -1 };
    }
}

/** Is sprite slot n a toggleable object switched off (IsObjectHidden)? */
static bool gbToggledOff(const struct GbSnap* s, int n) {
    for (int i = 0; i + 1 < (int) sizeof(s->toggleList) && s->toggleList[i] != 0xFF; i += 2) {
        if (s->toggleList[i] != n) continue;
        int g = s->toggleList[i + 1];
        return (s->toggleFlags[(g >> 3) & 31] >> (g & 7)) & 1;
    }
    return false;
}

// The standing frames of a 16 x 16 object: y, x, tile, x-flip (Gen 1's SpriteFacingAndAnimationTable, Crystal's Facings:
// the same 12 tiles).
static const int8_t GB_FACE[4][4][4] = {
    { { 0, 0, 0, 0 }, { 0, 8, 1, 0 }, { 8, 0, 2, 0 }, { 8, 8, 3, 0 } },       // down
    { { 0, 0, 4, 0 }, { 0, 8, 5, 0 }, { 8, 0, 6, 0 }, { 8, 8, 7, 0 } },       // up
    { { 0, 0, 8, 0 }, { 0, 8, 9, 0 }, { 8, 0, 10, 0 }, { 8, 8, 11, 0 } },     // left
    { { 0, 8, 8, 1 }, { 0, 0, 9, 1 }, { 8, 8, 10, 1 }, { 8, 0, 11, 1 } },     // right
};

/** OAM (its first `fromOam` objects) already has a whole 16 x 16 - its four objects - less than a step from (sx, sy):
 * an NPC walking there, drawn as it is. (Any object that near would also have matched the next NPC along, 16 px over.) */
static bool gbOamHas(int sx, int sy, int fromOam) {
    for (int k = 0; k < fromOam; k++) {
        int x = gbObjs[k].x, y = gbObjs[k].y;
        if (x <= sx - 16 || x >= sx + 16 || y <= sy - 16 || y >= sy + 16) continue;
        int quads = 0;
        for (int q = 0; q < fromOam; q++)
            quads |= (gbObjs[q].x == x ? 1 : gbObjs[q].x == x + 8 ? 2 : 0) << (gbObjs[q].y == y ? 0 : gbObjs[q].y == y + 8 ? 2 : 4);
        if ((quads & 0xF) == 0xF) return true;
    }
    return false;
}

/** A 16 x 16 standing at screen (sx, sy): `face` 0 down .. 3 right, `base` its first tile; the lower half's attributes
 * add `lowAttr` (behind tall grass). */
static void gbAddStanding(int sx, int sy, int face, int base, int attr, int lowAttr) {
    for (int k = 0; k < 4 && gbNObjs < GB_MAX_OBJS; k++) {
        const int8_t* f = GB_FACE[face & 3][k];
        int a = attr | (f[3] ? 0x20 : 0) | (k >= 2 ? lowAttr : 0);
        gbObjs[gbNObjs++] = (struct GbObj) { sx + f[1], sy + f[0], base + f[2], a, -1 };
    }
}

#define GB_EDGE_STEP 20   // a step (16 px) from the edge, and the few frames OAM is behind RAM once it's there

/** Every NPC OAM doesn't show, standing where it stands ((ox, oy) is the screen's world pixel): the ones the game hides
 * for being off screen, and one stepping on or off it while OAM, a frame behind RAM, doesn't have it yet. Going by the
 * image index alone ($FF = hidden) made such an NPC vanish for two frames at every edge it crossed: a flicker. */
static void gbObjsOffScreen(const struct GbSnap* s, int ox, int oy) {
    // A battle starting (its flashes and wipe hide the field's sprites) or the sprite layer off: none of them.
    if (s->battle || !(gbLcdc & 0x02)) return;
    const int fromOam = gbNObjs;
    for (int n = 1; n < 15; n++) {   // not the player (0) nor Pikachu (15)
        const uint8_t* a = &s->spr1[n * 16];
        const uint8_t* b = &s->spr2[n * 16];
        if (!a[0] || !b[0xE]) continue;
        int mx = b[5] - 4, my = b[4] - 4;
        bool onScreen = my >= s->py - 4 && my <= s->py + 4 && mx >= s->px - 4 && mx <= s->px + 5;
        // On screen and hidden under a text box: the game's call. (Hidden on screen otherwise is RAM a step ahead of
        // the picture: the player has moved, the NPC's image index catches up a frame later.)
        if (onScreen && a[2] == 0xFF && s->text) continue;
        if (gbToggledOff(s, n)) continue;
        int sx0 = 32 + 16 * b[5] - ox, sy0 = 32 + 16 * b[4] - 4 - oy;
        if (gbOamHas(sx0, sy0, fromOam)) continue;
        int slot = b[0xE] - 1;
        int face = (a[9] >> 2) & 3, base = slot * 12;
        if (slot >= 10) { face = 0; base = slot == 11 ? 0x7C : 120; }   // one-frame sprites (item balls, boulders)
        int first = gbNObjs;
        gbAddStanding(sx0, sy0, face, base, 0, (b[7] & 0x80) ? 0x80 : 0);
        // Gen 1 shows an NPC only once it stands a tile inside the screen: for the step that brings it in, the game's
        // picture has no NPC, and one drawn in the strip alone was cut at the old edge, then gone until the game drew it
        // (GitHub #385). So one within a step of the edge goes over the picture too - never further in, where a sprite
        // the game left out is the game's business (FLY's animation clears them).
        if (sx0 >= GBW - GB_EDGE_STEP || sx0 <= GB_EDGE_STEP - 16 || sy0 >= GBH - GB_EDGE_STEP || sy0 <= GB_EDGE_STEP - 16)
            for (int k = first; k < gbNObjs; k++) gbObjs[k].over = true;
    }
}

/** Crystal: a sprite's type (1 walking, 2 standing, 3 still: one frame) and palette, as _GetSpritePalette finds them:
 * the variable sprites through wVariableSprites, a Pokémon's (SPRITE_POKEMON on) walking in palette 0. */
static bool gbCrystalSprite(const struct GbSnap* s, int sprite, int* type, int* pal) {
    if (gbCfg[GB_CLEAR]) {
        // 5 bytes a sprite, no palette: palette 0 but for sprite E2 (02:4189's code). From DE up they all walk.
        *pal = sprite == 0xE2;
        *type = 1;
        if (sprite >= 0xDE) return sprite != 0;
        const uint8_t* d = sprite ? gbRom(gbCfg[GB_SPRITE_DATA] + 5 * (uint32_t) (sprite - 1), 5) : NULL;
        if (!d) return false;
        *type = d[4];
        return true;
    }
  if (gbPolished) {
    for (int k = 0; k < 4 && sprite >= 0xf5; k++)
      sprite = sprite <= 0xff ? s->vars[sprite - 0xf5] : 0;
    if (!sprite || sprite >= 0xf5)
      return false;
    if (sprite >= 0xef) {
      *type = 3;
      *pal = 0;
      return true;
    }
    const uint8_t *d =
        gbRom(gbCfg[GB_SPRITE_DATA] + 4 * (uint32_t)(sprite - 1), 4);
    if (!d)
      return false;
    *type = (d[3] >> 6) + 1;
    *pal = d[3] & 7;
    return true;
  }
    for (int k = 0; k < 4 && sprite >= 0x8C; k++) sprite = sprite - 0x8C < (int) sizeof(s->vars) ? s->vars[sprite - 0x8C] : 0;
    if (!sprite || sprite >= 0x8C) return false;
    if (sprite >= 0x67) { *type = 1; *pal = 0; return true; }
    const uint8_t* d = gbRom(gbCfg[GB_SPRITE_DATA] + 6 * (uint32_t) (sprite - 1), 6);
    if (!d) return false;
    *type = d[4];
    *pal = d[5] & 7;
    return true;
}

/** Crystal: a map object standing at screen (sx, sy) - not yet in an object struct, so drawn as the game will spawn it:
 * its sprite's tiles from wUsedSprites (every sprite of the area is in VRAM), facing its movement's way
 * (SpriteMovementData), its palette byte's colour if it has one (MAPOBJECT_PALETTE's high nibble). */
static void gbAddMapObject(const struct GbSnap* s, int sprite, int sx, int sy, int movement, int palByte, int fromOam) {
    int vtile = -1;
    for (int k = 1; k < 32; k++)
        if (s->used[2 * k] == sprite) { vtile = s->used[2 * k + 1]; break; }
    int type = 1, pal = 0;
    if (vtile < 0 || !gbCrystalSprite(s, sprite, &type, &pal)) return;
    if (palByte & 0xF0) pal = (palByte >> 4) & 7;
    const uint8_t* mv = gbRom(gbCfg[GB_MOVE_DATA] + 6 * (uint32_t) movement, 6);
    int face = type == 3 || !mv ? 0 : mv[1] & 3;
    if (gbOamHas(sx, sy, fromOam)) return;
    gbAddStanding(sx, sy, face, vtile & 0x7F, (vtile & 0x80 ? 0 : 8) | pal, 0);
}

/** Crystal: would an object event with hours h1 / h2 and event flag `flag` be out now? CheckObjectTime: h1 = -1 makes h2
 * times of day (MORN 1, DAY 2, NITE 4; -1 always), else the hours from h1 to h2 (wrapping past midnight when h1 > h2; h1 =
 * h2 always); a set event flag hides it. */
static bool gbCrystalObjectNow(int h1, int h2, uint16_t flag) {
    if ((flag >> 8) == 0xFF) {   // CheckObjectFlag: -1 is always; any other $FFxx never
        if (flag != 0xFFFF) return false;
    } else if ((gbRam(gbCfg[GB_EVENT_FLAGS] + flag / 8u) >> (flag % 8)) & 1) {
        return false;
    }
    if (h1 == 0xFF) {
        if (h2 == 0xFF) return true;
        int tod = gbRam(gbCfg[GB_TIME_OF_DAY]);
        return tod > 2 || (h2 & (1 << tod));
    }
    int hour = gbHram(gbCfg[GB_HOURS]);
    if (h1 == h2) return true;
    return h1 < h2 ? hour >= h1 && hour <= h2 : hour >= h1 || hour <= h2;
}

#include "pk_wide_gb_polished.inc"

/** Crystal: every NPC OAM doesn't show. Those in an object struct where the game would draw them (the game keeps a
 * struct for an object up to a step past the screen's edges, OFF_SCREEN); further out, the map's objects not in one,
 * standing where the game will put them when the player comes near (their own spot, facing their movement's way),
 * unless masked (an event flag, the time of day). Their tiles: the struct's, or wUsedSprites' for the sprite (every
 * sprite the map uses is in VRAM). */
static void gbObjsOffScreenCrystal(const struct GbSnap* s, int ox, int oy) {
    if (s->battle || !(gbLcdc & 0x02)) return;
    const int fromOam = gbNObjs;
    // A struct's screen position (OBJECT_SPRITE_X / Y + offsets) goes with the scroll in hSCX / hSCY, the player's
    // standing at (64, 60): the NPC's is the player's in the world plus the difference - where the game draws it, a
    // step it began and took back (a wandering NPC blocked) included, which its map coordinates move for a frame.
    const uint8_t* me = &s->objs[0];
    int dX = (int8_t) (uint8_t) (s->scx - gbScx), dY = (int8_t) (uint8_t) (s->scy - gbScy);
    if (dX < -8 || dX > 8) dX = 0;
    if (dY < -8 || dY > 8) dY = 0;
    for (int n = 1; n < 13; n++) {
        const uint8_t* o = &s->objs[n * 0x28];
        if (!o[0] || (o[4] & 1) || (o[6] & 0x80)) continue;   // empty, INVISIBLE, a BIG_OBJECT
        int mx = o[0x10], my = o[0x11], face = o[0xD];
        bool onScreen = mx - s->px >= 0 && mx - s->px <= 9 && my - s->py >= 0 && my - s->py <= 8;
        // On screen and not drawn (STANDING) under a text box or a menu: the game's call.
        if (onScreen && face == 0xFF && s->text) continue;
        int sx0 = 64 + dX + (int8_t) (uint8_t) (o[0x17] + o[0x19] - me[0x17]);
        int sy0 = 60 + dY + (int8_t) (uint8_t) (o[0x18] + o[0x1A] - me[0x18]);
        if (gbOamHas(sx0, sy0, fromOam)) continue;
        int type = 1, pal = 0;
        if (!gbCrystalSprite(s, o[0], &type, &pal)) continue;
        int tile = o[2], f2 = o[5];
        int attr = (tile & 0x80 ? 0 : 8) | (f2 & 0x80 ? 0x80 : 0) | (f2 & 0x10 ? 0x10 : 0) | (o[6] & 7);
        int dir = face < 16 ? face >> 2 : (o[8] >> 2) & 3, step = face < 16 ? face & 3 : 0;
        int base = tile & 0x7F;
        if (type == 3) dir = step = 0;
        int grass = f2 & 0x08 ? 0x80 : 0;
        if (step & 1) base += 0x80;   // a walking frame: Facings' tiles + $80 ...
        if (step == 3 && dir < 2) gbAddStanding(sx0, sy0, 3, base - 8 + dir * 4, attr, grass);   // ... down / up's 2nd mirrored
        else gbAddStanding(sx0, sy0, dir, base, attr, grass);
    }
    if (gbPolished) {
      pcWideObjects(s, ox, oy, fromOam);
      return;
    }
    for (int n = 1; n < 16; n++) {
        const uint8_t* m = &s->mapObjs[n * 16];
        if (!m[1] || m[0] != 0xFF || s->masks[n]) continue;
        gbAddMapObject(s, m[1], 16 * m[3] + 32 - ox, 16 * m[2] + 28 - oy, m[4], m[8], fromOam);
    }
    // The connected maps' people, from their object events in ROM (the game loads a map's objects only once it's the
    // player's): unless their event flag is set or the time of day keeps them away (CheckObjectTime).
    for (int d = 0; d < 4; d++) {
        const struct GbConn* c = &s->conn[d];
        for (int n = 0; c->on && n < c->nObjs; n++) {
            const uint8_t* e = gbRom(c->objs + 13u * (uint32_t) n, 13);   // sprite, y + 4, x + 4, movement, ...
            if (!e || !e[0] || !gbCrystalObjectNow(e[5], e[6], (uint16_t) (e[11] | e[12] << 8))) continue;
            gbAddMapObject(s, e[0], 32 * c->offX + 16 * (e[2] - 4) - ox, 32 * c->offY + 16 * (e[1] - 4) - 4 - oy, e[3], e[7],
                           fromOam);
        }
    }
}

// --- drawing -------------------------------------------------------------------------------------------

#define GB_BUF (PK_WIDE_MAX_VIEW_W * PK_WIDE_MAX_VIEW_H)
static uint32_t gbRaw[GB_BUF];       // the last scene that checked out, for the window gbRawView
static uint8_t gbBgIdx[GB_BUF];      // its BG colour index per pixel (| 0x80: the BG tile has priority), for objects
static uint8_t gbObjMask[GB_BUF];    // an object already took this pixel
static struct PkWideView gbRawView;
static uint32_t gbOver[GB_MAX_OBJS * 64];   // gbRaw's pixels (an `over` object's) that go over the game's own picture
static int gbNOver;

static inline bool gbOnFrame(int x, int y) { return x >= 0 && x < GBW && y >= 0 && y < GBH; }

/** The world from screen pixel (x0, y0) for w x h into dst (stride `stride`), then the objects over it. With
 * `screen` (the middle, for the headless check), the screen's own font tiles go over the world, as the PPU draws them:
 * a text box, the START menu. */
static void gbDraw(const struct GbSnap* s, int ox, int oy, int x0, int y0, int w, int h, uint32_t* dst, int stride,
                   uint32_t alpha, bool screen) {
    // The world alone (every frame, the whole view: the 3DS draws ~96,000 pixels of it): a tile row's 8 pixels decoded at
    // once from its two VRAM bytes and its palette's 4 colours, the same pixels the per-pixel loop below draws.
    if (!screen) {
        // Under the game's own picture nothing of this is shown (the view takes the frame's pixels there), but for Gen 1's
        // people a step past the edge, drawn over the picture (`over`): their rows are drawn whole, for what's under them.
        static uint8_t rowWhole[PK_WIDE_MAX_VIEW_H];
        memset(rowWhole, 0, (size_t) h);
        for (int k = 0; k < gbNObjs; k++) {
            if (!gbObjs[k].over) continue;
            for (int yy = 0; yy < 8; yy++) {
                int j = gbObjs[k].y + yy - y0;
                if (j >= 0 && j < h) rowWhole[j] = 1;
            }
        }
        int fa = gbClamp(-x0, 0, w), fb = gbClamp(GBW - x0, fa, w);   // the frame's columns in the view
        // A row of tiles is looked up once (its tile, attributes, colours), then its 8 rows of pixels drawn from it.
        struct GbTileLook { uint32_t base; uint8_t attr; uint32_t col[4]; };
        static struct GbTileLook look[PK_WIDE_MAX_VIEW_W / 8 + 2];
        int firstTx = (ox + x0) >> 3, lastTy = 0x7FFFFFFF;
        int nt = ((ox + x0 + w - 1) >> 3) - firstTx + 1;
        for (int j = 0; j < h; j++) {
            int wy = oy + y0 + j, ty = wy >> 3, fy = wy & 7;
            if (ty != lastTy) {
                lastTy = ty;
                for (int c = 0; c < nt; c++) {
                    int tx = firstTx + c, t = gbTile(s, tx, ty);
                    uint8_t attr;
                    if (gbGen == 2) {
                        attr = gbAttrAt(s, tx, ty, t);
                        if (!gbPolished) t &= 0x7F;
                    } else {
                        attr = gbAttrSeen[t] ? gbTileAttr[t] : gbAttrDefault;
                    }
                    struct GbTileLook* L = &look[c];
                    L->base = gbBgTileBase(t, attr);
                    L->attr = attr;
                    int pal = gbCgb ? (attr & 7) * 4 : 0;
                    for (int q = 0; q < 4; q++) L->col[q] = gbOpaque(gbWorldColor(pal + q), alpha);
                }
            }
            int sy = y0 + j;
            bool skip = sy >= 0 && sy < GBH && !rowWhole[j];
            uint32_t* out = dst + j * stride;
            uint8_t* bgIdx = gbBgIdx + j * w;
            for (int i = 0; i < w;) {
                if (skip && i >= fa && i < fb) {
                    i = fb;
                    continue;
                }
                int wx = ox + x0 + i, fx = wx & 7;
                const struct GbTileLook* L = &look[(wx >> 3) - firstTx];
                uint8_t attr = L->attr;
                uint32_t base = L->base + (uint32_t) ((attr & 0x40) ? 7 - fy : fy) * 2;
                unsigned lo = gbVram[base], hi = gbVram[base + 1];
                uint8_t prio = attr & 0x80;
                int n = 8 - fx < w - i ? 8 - fx : w - i;
                for (int k = 0; k < n; k++, i++) {
                    int bit = (attr & 0x20) ? fx + k : 7 - (fx + k);
                    int idx = (int) ((lo >> bit) & 1) | (int) (((hi >> bit) & 1) << 1);
                    out[i] = L->col[idx];
                    bgIdx[i] = (uint8_t) (idx | prio);
                }
            }
        }
    }
    if (!screen) memset(gbObjMask, 0, (size_t) w * (size_t) h);
    for (int j = 0; screen && j < h; j++) {
        int wy = oy + y0 + j, ty = wy >> 3, fy = wy & 7;
        int lastTx = 0x7FFFFFFF, t = 0;
        uint8_t attr = 0;
        uint32_t base = 0;
        for (int i = 0; i < w; i++) {
            int wx = ox + x0 + i, tx = wx >> 3;
            if (tx != lastTx) {
                lastTx = tx;
                t = gbTile(s, tx, ty);
                if (gbGen == 2) {
                  attr = gbAttrAt(s, tx, ty, t);
                  if (!gbPolished)
                    t &= 0x7F;
                } else {
                    attr = gbAttrSeen[t] ? gbTileAttr[t] : gbAttrDefault;
                }
                base = gbBgTileBase(t, attr);
            }
            int px = (attr & 0x20) ? 7 - (wx & 7) : wx & 7, py = (attr & 0x40) ? 7 - fy : fy;
            int idx = -1;
            uint8_t a = attr;
            int sx = x0 + i, sy = y0 + j;
            if (screen && sx >= 0 && sx < GBW && sy >= 0 && sy < GBH) {
                // The screen's own: the window where it covers the BG, the BG's text tiles.
                bool win = gbWinPart && sy >= gbWinY && sx >= gbWinX;
                int mx = win ? sx - gbWinX : gbSrcX + sx, my = win ? sy - gbWinY : gbSrcY + sy;
                int at = (win ? gbWinBase : gbMapBase) + ((my >> 3) & 31) * 32 + ((mx >> 3) & 31);
                int st = gbVram[at];
                uint8_t sa = gbCgb ? gbVram[0x2000 + at] : 0;
                if (win || !gbMapKey(st)) {
                    a = sa;
                    int fx = mx & 7, fyy = my & 7;
                    idx = gbTilePixel(gbBgTileBase(st, a), (a & 0x20) ? 7 - fx : fx, (a & 0x40) ? 7 - fyy : fyy);
                }
            }
            bool world = idx < 0;
            if (world) idx = gbTilePixel(base, px, py);
            dst[j * stride + i] = gbBgColor(a, idx, alpha, world);
            gbBgIdx[j * w + i] = (uint8_t) (idx | (a & 0x80));
            gbObjMask[j * w + i] = 0;
        }
    }
    // Objects, the first (lowest OAM index) on top as on a Color; one behind the BG (its priority bit, or the BG
    // tile's) shows only over BG colour 0. LCDC bit 0 off on a Color lets every object over the BG.
    for (int k = 0; k < gbNObjs; k++) {
        const struct GbObj* o = &gbObjs[k];
        if (o->x >= x0 + w || o->x + 8 <= x0 || o->y >= y0 + h || o->y + 8 <= y0) continue;
        uint32_t tb = (uint32_t) (o->tile & 0xFF) * 16 + ((gbCgb && (o->attr & 8)) ? 0x2000u : 0u);
        for (int yy = 0; yy < 8; yy++) {
            int j = o->y + yy - y0;
            if (j < 0 || j >= h) continue;
            // An 8x16 object's halves swap with a vertical flip.
            int row = (o->attr & 0x40) ? 7 - yy : yy;
            uint32_t tbase = tb;
            if ((gbLcdc & 0x04) && (o->attr & 0x40)) tbase ^= 16;
            for (int xx = 0; xx < 8; xx++) {
                int i = o->x + xx - x0;
                if (i < 0 || i >= w || gbObjMask[j * w + i]) continue;
                int idx = gbTilePixel(tbase, (o->attr & 0x20) ? 7 - xx : xx, row);
                if (gbObjectRomTiles[k]) {
                  int x = (o->attr & 0x20) ? 7 - xx : xx;
                  const uint8_t *tile = gbObjectRomTiles[k] + row * 2;
                  idx = ((tile[0] >> (7 - x)) & 1) | (((tile[1] >> (7 - x)) & 1) << 1);
                }
                if (!idx) continue;
                gbObjMask[j * w + i] = 1;
                uint8_t bg = gbBgIdx[j * w + i];
                bool behind = (o->attr & 0x80) || (gbCgb && (bg & 0x80));
                if (behind && (bg & 3) && (!gbCgb || (gbLcdc & 0x01))) continue;
                dst[j * stride + i] = gbObjColor((uint8_t) o->attr, idx, alpha);
                if (o->over && !screen && gbOnFrame(x0 + i, y0 + j)) gbOver[gbNOver++] = (uint32_t) (j * stride + i);
            }
        }
    }
}

// --- off the field: a bar in the screen's own background ------------------------------------------------------
//
// A menu screen, a battle, the title: nothing to rebuild past the edges, and Gen 1 / Gen 2 draw these on plain
// backgrounds, so the strips are that background, flat - per band of rows, the colour the screen's sides have there
// (white on Yellow's menus, battles and trainer card - its thin red border doesn't count -, black above Crystal's
// Pokégear and its light colour below, black in a fade to black). The GBA's glow spread from the edges looked smeared
// against them. OPTION, opened from the START menu, keeps the field's strips (the user's call, like SAVE's prompt).

#define GB_SIDE 32               // the bars' colour: the most common one in the outer 32 px each side ...
#define GB_BAND 32               // ... of the rows 32 above and below
#define GB_ROW_KINDS 8           // colours counted per row (a row's sides hold a few)

static uint32_t gbFillRow[GBH];
static bool gbFillSet;

/** Each row's bar colour (gbFillRow): the screen's sides around it, by bands. One row's own sides striped the bars
 * wherever an icon or a box touched an edge (the Pokégear's SWITCH button, flush with the right edge over its icons). */
static void gbBackgroundRows(const uint32_t* frame) {
    // Per row: its sides' colours (every other pixel of the outer 32 px, both sides), counted.
    static uint32_t col[GBH][GB_ROW_KINDS];
    static int cnt[GBH][GB_ROW_KINDS], kinds[GBH];
    for (int y = 0; y < GBH; y++) {
        kinds[y] = 0;
        for (int x = 0; x < GB_SIDE; x += 2)
            for (int e = 0; e < 2; e++) {
                uint32_t c = frame[y * GBW + (e ? GBW - 1 - x : x)] & 0x00FFFFFFu;
                int k = 0;
                while (k < kinds[y] && col[y][k] != c) k++;
                if (k == kinds[y]) {
                    if (k == GB_ROW_KINDS) continue;
                    col[y][k] = c;
                    cnt[y][k] = 0;
                    kinds[y]++;
                }
                cnt[y][k]++;
            }
    }
    for (int y = 0; y < GBH; y++) {
        uint32_t c[64];
        int n[64], m = 0, best = 0;
        int a = y - GB_BAND < 0 ? 0 : y - GB_BAND, b = y + GB_BAND >= GBH ? GBH - 1 : y + GB_BAND;
        for (int r = a; r <= b; r++)
            for (int i = 0; i < kinds[r]; i++) {
                int k = 0;
                while (k < m && c[k] != col[r][i]) k++;
                if (k == m) {
                    if (m == 64) continue;
                    c[m] = col[r][i];
                    n[m++] = 0;
                }
                n[k] += cnt[r][i];
            }
        for (int k = 1; k < m; k++) if (n[k] > n[best]) best = k;
        gbFillRow[y] = m ? c[best] : 0xFFFFFFu;
    }
    gbFillSet = true;
}

// The libretro core (PK_WIDE_GB_GLOW=1, libretro/CMakeLists.txt) asks for a glow instead of the bars, the one
// pk_wide.c gives a Game Boy Advance game. The app and the Linux host keep the bars.
#ifndef PK_WIDE_GB_GLOW
#define PK_WIDE_GB_GLOW 0
#endif

// Past the screen's edges, wherever the scene is not drawn (menus, rewind, fast-forward) the window is a glow spread from
// the frame's own edge pixels, the same one pk_wide.c gives a Game Boy Advance game: each pixel takes its colour from the
// nearest edge band, blurred over more of that edge the further out it is, and dimmed towards the window's edge.
#define GB_AMBIENT_BAND 12         // the game's edge columns the glow is spread from

static int32_t gbEdgeAccH[2][GBH][3], gbEdgeAccV[2][GBW][3];   // the edge bands' colour per row / column, x16, smoothed over frames
static int32_t gbPreH[2][GBH + 1][3], gbPreV[2][GBW + 1][3];   // their running sums (a blur is a difference)
static bool gbEdgeAccOk;

static void gbAmbientPrepare(const uint32_t* frame) {
    for (int side = 0; side < 2; side++) {
        int x0 = side ? GBW - GB_AMBIENT_BAND : 0, y0 = side ? GBH - GB_AMBIENT_BAND : 0;
        for (int c = 0; c < 3; c++) gbPreH[side][0][c] = gbPreV[side][0][c] = 0;
        for (int y = 0; y < GBH; y++) {
            int32_t sum[3] = { 0, 0, 0 };
            for (int x = x0; x < x0 + GB_AMBIENT_BAND; x++) {
                uint32_t v = frame[y * GBW + x];
                for (int c = 0; c < 3; c++) sum[c] += (v >> (8 * c)) & 0xFF;
            }
            for (int c = 0; c < 3; c++) {
                int32_t now = sum[c] * 16 / GB_AMBIENT_BAND;
                gbEdgeAccH[side][y][c] = gbEdgeAccOk ? (gbEdgeAccH[side][y][c] + now) / 2 : now;
                gbPreH[side][y + 1][c] = gbPreH[side][y][c] + gbEdgeAccH[side][y][c];
            }
        }
        for (int x = 0; x < GBW; x++) {
            int32_t sum[3] = { 0, 0, 0 };
            for (int y = y0; y < y0 + GB_AMBIENT_BAND; y++) {
                uint32_t v = frame[y * GBW + x];
                for (int c = 0; c < 3; c++) sum[c] += (v >> (8 * c)) & 0xFF;
            }
            for (int c = 0; c < 3; c++) {
                int32_t now = sum[c] * 16 / GB_AMBIENT_BAND;
                gbEdgeAccV[side][x][c] = gbEdgeAccOk ? (gbEdgeAccV[side][x][c] + now) / 2 : now;
                gbPreV[side][x + 1][c] = gbPreV[side][x][c] + gbEdgeAccV[side][x][c];
            }
        }
    }
    gbEdgeAccOk = true;
}

/** The glow at screen pixel (X, Y), off the frame; `reach` is how far the window goes past each edge (left, right, top,
 * bottom), where it is dimmest. */
static uint32_t gbGlowAt(const uint32_t* frame, int X, int Y, const int reach[4], uint32_t alpha) {
    int ox = X < 0 ? -X : X >= GBW ? X - GBW + 1 : 0;
    int oy = Y < 0 ? -Y : Y >= GBH ? Y - GBH + 1 : 0;
    bool across = ox >= oy;   // from the left / right edge, else the top / bottom one
    int side = across ? X >= GBW : Y >= GBH;
    int d = across ? ox : oy, len = across ? GBH : GBW;
    int at = across ? gbClamp(Y, 0, GBH - 1) : gbClamp(X, 0, GBW - 1);
    int32_t (*pre)[3] = across ? gbPreH[side] : gbPreV[side];
    uint32_t exact = across ? frame[at * GBW + (side ? GBW - 1 : 0)] : frame[(side ? GBH - 1 : 0) * GBW + at];
    int far = reach[across ? side : 2 + side];
    int r = 2 + d * 3 / 4;                                            // the glow spreads as it goes out
    int bright = 256 - 136 * (d - 1) / (far > 1 ? far - 1 : 1);       // 1.0 at the game's edge -> ~0.47 at the window's
    if (bright < 120) bright = 120;
    int seam = d >= 4 ? 256 : d * 64;                                 // the first pixels lean on the edge itself
    int lo = at - r < 0 ? 0 : at - r, hi = at + r >= len ? len - 1 : at + r;
    uint32_t v = 0;
    for (int c = 0; c < 3; c++) {
        int32_t blur = (pre[hi + 1][c] - pre[lo][c]) / (hi - lo + 1) / 16;
        int32_t e = (exact >> (8 * c)) & 0xFF;
        int32_t mixd = (e * (256 - seam) + blur * seam) >> 8;
        v |= (uint32_t) ((mixd * bright) >> 8) << (8 * c);
    }
    return v | alpha;
}

static inline uint32_t gbLerp(uint32_t a, uint32_t b, int t) {
    if (t <= 0) return a;
    if (t >= 256) return b;
    uint32_t rb = (((a & 0xFF00FF) * (uint32_t) (256 - t) + (b & 0xFF00FF) * (uint32_t) t) >> 8) & 0xFF00FF;
    uint32_t g = (((a & 0x00FF00) * (uint32_t) (256 - t) + (b & 0x00FF00) * (uint32_t) t) >> 8) & 0x00FF00;
    return (a & 0xFF000000) | rb | g;
}

static bool gbFaded(const uint32_t* frame) {
    int lo = 255, hi = 0;
    for (int y = 2; y < GBH; y += 6)
        for (int x = 3; x < GBW; x += 6) {
            uint32_t p = frame[y * GBW + x];
            int r = p & 0xFF, g = (p >> 8) & 0xFF, b = (p >> 16) & 0xFF;
            int mx = r > g ? (r > b ? r : b) : (g > b ? g : b), mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
            if (mx > hi) hi = mx;
            if (mn < lo) lo = mn;
            if (hi > GB_FADE_LEVEL && lo < 255 - GB_FADE_LEVEL) return false;
        }
    return hi <= GB_FADE_LEVEL || lo >= 255 - GB_FADE_LEVEL;
}

// --- the frame -----------------------------------------------------------------------------------------

static int gbSinceField = 1000;   // frames since the field (or a whole black / white frame) last showed
static bool gbMixSnap; // pk_wide_gb_snap
static int gbMix, gbLastScore = -1, gbLastMode = PK_WIDE_MODE_NONE, gbLastClose = 1, gbLastScene = PK_WIDE_SCENE_OFF;

int pk_wide_gb_knows(uint32_t crc) {
  if (crc && crc == PC_WIDE_CRC)
    return 1;
  for (size_t i = 0; i < sizeof(GB_ROMS) / sizeof(GB_ROMS[0]); i++)
    if (GB_ROMS[i].crc == crc)
      return 1;
  return 0;
}

static int pk_wide_gb_configure_polished(const uint32_t addresses[GB_CONFIG], pk_wide_gb_decode_fn decode);

int pk_wide_gb_configure(uint32_t crc) {
    // Polished Crystal 3.2.3 (normal): its own structs and LZ, a profile of its own (pk_wide_gb_polished.inc)
    if (crc && crc == PC_WIDE_CRC) return pk_wide_gb_configure_polished(pcWideConfig, pcLz);
    gbCfg = NULL;
    for (size_t i = 0; i < sizeof(GB_ROMS) / sizeof(GB_ROMS[0]); i++)
        if (crc && GB_ROMS[i].crc == crc) {
            gbCfg = GB_ROMS[i].a;
            gbGen = GB_ROMS[i].gen;
        }
    gbPolished = false;
    gbDecode = NULL;
    pcWideSpriteCacheReset();
    gbAttrBlockset = 0;
    memset(gbLz, 0, sizeof(gbLz));
    gbLzNext = 0;
    pk_wide_gb_reset();
    return gbCfg != NULL;
}

static int pk_wide_gb_configure_polished(const uint32_t addresses[GB_CONFIG], pk_wide_gb_decode_fn decode) {
    pk_wide_gb_configure(0);
    if (!addresses || !decode) return 0;
    gbCfg = addresses;
    gbGen = 2;
    gbPolished = true;
    gbDecode = decode;
    return 1;
}

void pk_wide_gb_snap(void) { gbMixSnap = true; }

void pk_wide_gb_reset(void) {
    gbPrev = -1;
    gbSnaps[0].ok = gbSnaps[1].ok = gbGood.ok = false;
    gbMix = 0;
    gbMixSnap = false;
    gbRawView.w = 0;
    gbLastScore = -1;
    gbLastMode = PK_WIDE_MODE_NONE;
    gbLastClose = 1;
    gbLastScene = PK_WIDE_SCENE_OFF;
    gbSinceField = 1000;
    gbFromStart = false;
    gbEdgeAccOk = false;
    gbFillSet = false;
    memoBest = -2;
    for (int i = 0; i < GB_GRIDS; i++) gbGrids[i].ok = false;
}

int pk_wide_gb_scene(void) { return gbLastScene; }
int pk_wide_gb_wants_close(void) { return gbLastClose; }
int pk_wide_gb_last_mode(void) { return gbLastMode; }
int pk_wide_gb_last_score(void) { return gbLastScore; }


/** A frame shown without its window (fast-forward): the frame over the ambient glow, no map read.
 * The RAM history is the caller's (pk_wide_skip). */
int pk_wide_gb_glow(struct mCore* core, const uint32_t* frame, uint32_t* out, struct PkWideView v) {
    if (!core || core->platform(core) != mPLATFORM_GB || !gbCfg || !out) return 0;
    uint32_t alpha = frame[0] & 0xFF000000u;
    if (PK_WIDE_GB_GLOW) gbAmbientPrepare(frame);
    else gbBackgroundRows(frame);
    int reach[4] = { -v.x, v.x + v.w - GBW, -v.y, v.y + v.h - GBH };
    for (int j = 0; j < v.h; j++)
        for (int i = 0; i < v.w; i++) {
            int X = v.x + i, Y = v.y + j;
            out[j * v.w + i] = gbOnFrame(X, Y) ? frame[Y * GBW + X]
                : PK_WIDE_GB_GLOW ? gbGlowAt(frame, X, Y, reach, alpha) : gbFillRow[gbClamp(Y, 0, GBH - 1)] | alpha;
        }
    return 1;
}

/** The view from the frame, the rebuilt scene (gbRaw, at gbMix) and the bars, then the objects that go over the frame. */
static void gbCompose(const uint32_t* frame, uint32_t* out, struct PkWideView v, uint32_t alpha, bool over) {
    int reach[4] = { -v.x, v.x + v.w - GBW, -v.y, v.y + v.h - GBH };

    for (int j = 0; j < v.h; j++) {
        int Y = v.y + j;
        uint32_t* row = out + j * v.w;
        uint32_t fill = gbFillRow[gbClamp(Y, 0, GBH - 1)] | alpha;   // above / below the frame: its top / bottom band
        int ry = Y - gbRawView.y;
        bool rawRow = ry >= 0 && ry < gbRawView.h;
        // The scene as it is (the field, fully in): whole runs from gbRaw, the frame's rows over the middle.
        bool whole = gbMix >= 256 && rawRow && gbRawView.x == v.x && gbRawView.w == v.w;
        if (whole) {
            const uint32_t* raw = gbRaw + ry * gbRawView.w;
            if (Y >= 0 && Y < GBH) {
                int a = -v.x > 0 ? -v.x : 0, b = GBW - v.x < v.w ? GBW - v.x : v.w;   // the frame's columns in the row
                if (a > v.w) a = v.w;
                if (b < a) b = a;
                memcpy(row, raw, (size_t) a * sizeof(uint32_t));
                memcpy(row + a, frame + Y * GBW + v.x + a, (size_t) (b - a) * sizeof(uint32_t));
                memcpy(row + b, raw + b, (size_t) (v.w - b) * sizeof(uint32_t));
            } else {
                memcpy(row, raw, (size_t) v.w * sizeof(uint32_t));
            }
            continue;
        }
        for (int i = 0; i < v.w; i++) {
            int X = v.x + i;
            if (gbOnFrame(X, Y)) {
                int n = (GBW - X) < (v.w - i) ? GBW - X : v.w - i;
                memcpy(row + i, frame + Y * GBW + X, (size_t) n * sizeof(uint32_t));
                i += n - 1;
                continue;
            }
            int rx = X - gbRawView.x, m = gbMix;
            uint32_t scene = 0;
            if (rawRow && rx >= 0 && rx < gbRawView.w) scene = gbRaw[ry * gbRawView.w + rx];
            else m = 0;
            row[i] = m >= 256 ? scene : gbLerp(PK_WIDE_GB_GLOW ? gbGlowAt(frame, X, Y, reach, alpha) : fill, scene, m);
        }
    }
    if (over) for (int k = 0; k < gbNOver; k++) out[gbOver[k]] = gbRaw[gbOver[k]];
}



int pk_wide_gb_view(struct mCore* core, const uint32_t* frame, uint32_t* out, struct PkWideView v, uint32_t* centre) {
    if (!core || core->platform(core) != mPLATFORM_GB || !gbCfg) {
        if (out) {
            uint32_t alpha = frame[0] & 0xFF000000u;
            for (int j = 0; j < v.h; j++)
                for (int i = 0; i < v.w; i++) {
                    int X = v.x + i, Y = v.y + j;
                    out[j * v.w + i] = gbOnFrame(X, Y) ? frame[Y * GBW + X] : alpha;
                }
        }
        gbLastClose = 1;
        return 0;
    }
    gbB = (struct GB*) core->board;
    gbR = (struct GBVideoSoftwareRenderer*) gbB->video.renderer;
    gbHookPalettes(gbB);
    gbLastPalTorn = gbPalTorn;   // written while the frame just finished was drawn
    gbPalTorn = false;
    int cur = gbPrev == 0 ? 1 : 0;
    gbTake(&gbSnaps[cur]);
    gbCopyLive();
    if (!out) {   // pk_wide_skip: only the RAM after this frame, for the next one drawn
        gbPrev = cur;
        return gbMix;
    }
    if (v.w > PK_WIDE_MAX_VIEW_W) v.w = PK_WIDE_MAX_VIEW_W;
    if (v.h > PK_WIDE_MAX_VIEW_H) v.h = PK_WIDE_MAX_VIEW_H;
    if (v.w < 1 || v.h < 1) return 0;
    uint32_t alpha = frame[0] & 0xFF000000u;

    gbLcdc = gbB->memory.io[GB_REG_LCDC];
    gbScx = gbB->memory.io[GB_REG_SCX];
    gbScy = gbB->memory.io[GB_REG_SCY];
    gbCgb = gbR->model >= GB_MODEL_CGB;
    int wy = gbB->memory.io[GB_REG_WY], wx = gbB->memory.io[GB_REG_WX];
    bool window = (gbLcdc & 0x20) && wy < GBH && wx < GBW + 7;
    bool fullWindow = window && wy == 0 && wx <= 7;
    gbWinBase = (gbLcdc & 0x40) ? 0x1C00 : 0x1800;
    gbMapBase = fullWindow ? gbWinBase : (gbLcdc & 0x08) ? 0x1C00 : 0x1800;
    gbSrcX = fullWindow ? 0 : gbScx;
    gbSrcY = fullWindow ? 0 : gbScy;
    gbWinPart = window && !fullWindow;
    gbWinX = wx - 7;
    gbWinY = wy;

    // Where the screen is: the snapshot the frame was drawn from (the one before, else this one) and the world tile
    // around RAM's guess whose tiles match the screen's BG map.
    int best = -1, bestTx = 0, bestTy = 0, bestSnap = -1;
    bool option = false;
    gbScreenMap = 0;
    if (gbLcdc & 0x80) {
        gbReadScreen();
        const int order[2] = { gbPrev, cur };
        // A screen that isn't the field (the Pokégear, the trainer card) makes the search below look through the whole
        // loaded world every frame - several times the rest of EXPAND. Its answer depends on nothing but the screen's
        // tiles, the two snapshots (in this order), the last match and Crystal Clear's two floor bytes: while those are
        // the same as last frame, so is the answer.
        static uint16_t memoScreen[18][20];
        static struct GbSnap memoSnap[2], memoGood;
        static int memoGoodTx, memoGoodTy, memoTx, memoTy, memoAt;
        static bool memoOk[2];
        static uint8_t memoClear[2];
        uint8_t clear[2] = { 0, 0 };
        if (gbCfg[GB_CLEAR]) { clear[0] = gbClearRam[0]; clear[1] = gbClearRam[1]; }
        bool same = memoBest != -2 && !memcmp(memoScreen, gbScreen, sizeof(gbScreen)) && !memcmp(memoClear, clear, 2) &&
                    memoGoodTx == gbGoodTx && memoGoodTy == gbGoodTy && !memcmp(&memoGood, &gbGood, sizeof(gbGood));
        for (int k = 0; same && k < 2; k++) {
            bool ok = order[k] >= 0 && gbSnaps[order[k]].ok;
            same = ok == memoOk[k] && (!ok || !memcmp(&memoSnap[k], &gbSnaps[order[k]], sizeof(struct GbSnap)));
        }
        if (same) {
            best = memoBest;
            bestTx = memoTx;
            bestTy = memoTy;
            bestSnap = memoAt < 0 ? -1 : memoAt == 2 ? 2 : order[memoAt];
        }
        bool costly = false;   // past the guess: worth keeping for the next frame (the field at the guess isn't)
        for (int k = 0; !same && k < 2 && best < 1000; k++) {
            int si = order[k];
            if (si < 0 || !gbSnaps[si].ok) continue;
            const struct GbSnap* s = &gbSnaps[si];
            const struct GbGrid* g = NULL;   // the field at the guess needs no grid: built only past it
            // Nearest first: on a tie (a field of grass), the guess wins.
            for (int r = 0; r <= GB_SEARCH && best < 1000; r++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dx = -r; dx <= r; dx++) {
                        if ((dx < 0 ? -dx : dx) != r && (dy < 0 ? -dy : dy) != r) continue;
                        if (r && !g) g = gbGridFor(s);
                        if (r) costly = true;
                        int sc = gbScore(s, g, s->guessX + dx, s->guessY + dy, false);
                        if (sc > best) { best = sc; bestTx = s->guessX + dx; bestTy = s->guessY + dy; bestSnap = si; }
                    }
        }
        // Not near the guess, or not exactly: the last snapshot that matched, around where it did (crossing a connection,
        // both snapshots can hold the new map half loaded - Crystal's zeros, its border block - while the screen still
        // shows the old one).
        if (!same && best >= 0 && best < 1000 && gbGood.ok) {
            costly = true;
            const struct GbGrid* g = gbGridFor(&gbGood);
            for (int dy = -GB_SEARCH; dy <= GB_SEARCH; dy++)
                for (int dx = -GB_SEARCH; dx <= GB_SEARCH; dx++) {
                    int sc = gbScore(&gbGood, g, gbGoodTx + dx, gbGoodTy + dy, true);
                    if (sc > best) { best = sc; bestTx = gbGoodTx + dx; bestTy = gbGoodTy + dy; bestSnap = 2; }
                }
        }
        // Still nothing, but the screen is the field: RAM is somewhere else for a moment - look everywhere in the loaded
        // world, giving up on each spot as soon as it can't match (the best spot wins, not the first).
        bool wide = !same && best >= 0 && best < GB_MIN_SCORE;
        for (int k = 0; k < 2 && wide && best < 1000; k++) {
            int si = order[k];
            if (si < 0 || !gbSnaps[si].ok) continue;
            const struct GbSnap* s = &gbSnaps[si];
            const struct GbGrid* g = gbGridFor(s);
            for (int ty = -18; ty <= (s->h + 6) * 4 && best < 1000; ty++)
                for (int tx = -20; tx <= (s->w + 6) * 4 && best < 1000; tx++) {
                    int sc = gbScore(s, g, tx, ty, true);
                    if (sc > best) { best = sc; bestTx = tx; bestTy = ty; bestSnap = si; }
                }
        }
        if (!same && !costly) memoBest = -2;
        if (!same && costly) {
            memcpy(memoScreen, gbScreen, sizeof(gbScreen));
            memcpy(memoClear, clear, 2);
            memoGood = gbGood;
            memoGoodTx = gbGoodTx;
            memoGoodTy = gbGoodTy;
            for (int k = 0; k < 2; k++) {
                memoOk[k] = order[k] >= 0 && gbSnaps[order[k]].ok;
                if (memoOk[k]) memoSnap[k] = gbSnaps[order[k]];
            }
            memoBest = best;
            memoTx = bestTx;
            memoTy = bestTy;
            memoAt = bestSnap < 0 ? -1 : bestSnap == 2 ? 2 : bestSnap == order[0] ? 0 : 1;
        }
        // A box over a blanked field - SAVE's prompt clears the screen behind its boxes but for a corner of the map: too
        // few map tiles to place the screen by, but every one of them is where the field last was, so it's still the
        // field (the strips keep the map). A menu screen has none of those tiles, or not there.
        if (best < 0 && gbScreenMap >= GB_OVERLAY_TILES && gbGood.ok && !gbSnaps[cur].battle && gbExact(&gbGood, gbGoodTx, gbGoodTy)) {
            best = 1000;
            bestTx = gbGoodTx;
            bestTy = gbGoodTy;
            bestSnap = 2;
        }
        // OPTION from the START menu: a screen of its own, but the field's strips stay (the user's call) - the field where
        // it last was. The START menu has to have been what was up (its box at the top right), so a later screen with
        // the same row remembered (a naming screen after a gift) doesn't pass for it.
        if (best < GB_MIN_SCORE && gbFromStart && gbGood.ok && !gbSnaps[cur].battle && gbSnaps[cur].ok &&
            gbSnaps[cur].startItem == GB_START_OPTION) {
            best = 1000;
            bestTx = gbGoodTx;
            bestTy = gbGoodTy;
            bestSnap = 2;
            option = true;
        }
    }
    gbLastScore = best;
    bool valid = best >= GB_MIN_SCORE;
    const struct GbSnap* now = &gbSnaps[cur];
    bool battle = now->ok && now->battle;
    bool faded = !valid && gbFaded(frame);
    // A door: between the fade and the new map, VRAM is rewritten a third a frame (the BG transfer) and matches nothing
    // for a few frames - still the field's business (ZOOM holds), not a menu screen to pull in for.
    bool fade = faded || (!valid && best >= 0 && !battle && gbSinceField <= GB_DOOR_FRAMES);
    gbSinceField = valid || faded ? 0 : gbSinceField + 1;
    gbLastMode = valid ? PK_WIDE_MODE_MAP : PK_WIDE_MODE_NONE;
    if (battle) gbFromStart = false;
    else if (valid && bestSnap != 2 && gbScreenMap > 0) {
        const struct GbSnap* m = &gbSnaps[bestSnap];
        bool startBox = gbScreen[0][10] == GB_START_TILE_L && gbScreen[0][19] == GB_START_TILE_R;
        gbFromStart = m->text && startBox ? true : m->text ? gbFromStart : false;
    }
    // A wild battle's flashes and wipe run on the field with wIsInBattle already set: the pull-in starts with them.
    const struct GbSnap* match = !valid ? NULL : bestSnap == 2 ? &gbGood : &gbSnaps[bestSnap];
    gbLastScene = fade ? PK_WIDE_SCENE_FADE
                : battle ? (valid ? PK_WIDE_SCENE_BATTLE_START : PK_WIDE_SCENE_OFF)
                : !valid ? PK_WIDE_SCENE_MENU
                : match->text ? PK_WIDE_SCENE_TEXT : PK_WIDE_SCENE_FIELD;
    gbLastClose = gbLastScene != PK_WIDE_SCENE_FIELD;

    if (valid) {
        const struct GbSnap* s = match;
        if (s != &gbGood) {
            gbGood = *s;
            gbGoodTx = bestTx;
            gbGoodTy = bestTy;
        }
        int ox = bestTx * 8 + (gbSrcX & 7), oy = bestTy * 8 + (gbSrcY & 7);
        // The field's colours, kept from the plain field: by the time OPTION shows, the START menu has faded them out.
        if (gbLastScene == PK_WIDE_SCENE_FIELD)
            for (int i = 0; i < 64; i++) gbFieldPal[i] = gbLive(i);
        gbUseFieldPal = option;
        gbLearnAttrs(s, bestTx, bestTy);
        gbObjsFromOam();
        if (gbGen == 2) gbObjsOffScreenCrystal(s, ox, oy);
        else gbObjsOffScreen(s, ox, oy);
        gbNOver = 0;
        gbDraw(s, ox, oy, v.x, v.y, v.w, v.h, gbRaw, v.w, alpha, false);
        gbRawView = v;
        if (centre) {
            int n = gbNObjs;
            gbObjsFromOam();   // the middle as the hardware draws it: OAM's objects alone
            gbDraw(s, ox, oy, 0, 0, GBW, GBH, centre, GBW, alpha, true);
            gbNObjs = n;
        }
        gbMix = gbMixSnap || gbMix + GB_MIX_STEP > 256 ? 256 : gbMix + GB_MIX_STEP;
        gbMixSnap = false;
    } else {
        gbMix = gbMix - GB_MIX_STEP < 0 ? 0 : gbMix - GB_MIX_STEP;   // the last scene, frozen, dissolves into the glow
        gbMixSnap = false; // pk_wide_jump's cut is the frame after it, as pk_wide.c's mixSnap
    }
    if (!gbRawView.w) gbMix = 0;
    if (!PK_WIDE_GB_GLOW) {
        if (!valid || !gbFillSet) gbBackgroundRows(frame);   // fading in again from a menu: from its bars
    } else if (gbMix < 256) gbAmbientPrepare(frame);
    else gbEdgeAccOk = false;   // the glow starts from the edges as they are when it is next needed
    gbCompose(frame, out, v, alpha, valid && gbLastScene == PK_WIDE_SCENE_FIELD);
    gbPrev = cur;
    return gbMix;
}

