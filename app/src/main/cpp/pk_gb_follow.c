// PokeDaisy - FOLLOWER on Pokemon Crystal: the party's lead walks a step behind the player, drawn into the finished frame.
// Nothing of the game is written (no RAM, no VRAM, no OAM): the follower is pixels over the picture the core made, so
// states, saves and RetroAchievements never see it. GPL-3.0 (see NOTICE).
//
// Where it walks: the path the screen scrolled (SCX / SCY as the frame was drawn, unwrapped), the player always being
// in the middle (the Game Boy games keep the camera on him); the follower sits 16 pixels of that path behind. A jump
// in the player's map or tile while the screen stands is a warp (walking over a map's edge changes them mid-step): the
// path starts again and the follower waits for the first step, as after a state load or a rewind. What hides it: a battle or its transition, a menu or text (only where their tiles are), surfing, the
// sprites off, the player's sprite gone. Tall grass covers its lower half (its pixels only where the BG is colour 0,
// as the game's own OBJ-behind-BG priority does to the player), the BG's priority tiles and the window cover it too,
// and it goes behind the game's sprites unless it stands lower on the screen than the player. Its colours follow the
// scene: fades and the time of day, read off the player's own OBJ palette.

#include "pk_gb_follow.h"
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <mgba/core/core.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gb/io.h>
#include <mgba/internal/gb/renderers/software.h>

#define GBF_W 16
#define GBF_H 16
#define GBF_BEHIND 16            // pixels of path between the player and the follower
#define GBF_TRAIL 64
#define GBF_PX 64          // the player's 16 x 16 on screen (OAM 0x48 / 0x4C)
#define GBF_PY 60

// Crystal's RAM (pret/pokecrystal; the same in rev 0, rev 1, Australia and the German cart). D000+ is WRAM bank 1.
#define GBF_PLAYER_OBJ 0xD4D6   // the player's object struct (0x28 bytes)
#define GBF_STATE_FLAGS 0xD0ED  // wStateFlags: bit 6 while a menu / text is up
#define GBF_BATTLE_MODE 0xD22D
#define GBF_PLAYER_STATE 0xD95D // 0 walk, 1 bike, 2 skate, 4 surf, 8 surf (Pikachu)
#define GBF_MAP_GROUP 0xDCB5
#define GBF_MAP_NUMBER 0xDCB6
#define GBF_VBLANK 0xFF9E      // hVBlank: 1 = the battle transition's

static const uint32_t gbfCrcs[] = { 0x3358E30Au, 0xEE6F5188u, 0xBB6DD80Cu, 0x616D85DEu };

static struct {
    bool known, show, have;
    uint32_t icon[2][GBF_W * GBF_H];   // two frames, XBGR with the alpha in the top byte (0 = see-through)
    // the path
    int camX, camY;
    uint8_t lastScx, lastScy, lastMapGroup, lastMapNum, lastTileX, lastTileY;
    bool started;
    int tx[GBF_TRAIL], ty[GBF_TRAIL];
    uint8_t tgrass[GBF_TRAIL];
    int tn;
    int walked;              // pixels of path since it last started (the follower waits for its first step)
    unsigned pathPx;         // pixels walked in all: its two poses change with its steps
    int facing;              // 0 down, 1 up, 2 left, 3 right
    unsigned tick;
    char why[48];
    int sx, sy;
    uint32_t c0, c3;
    unsigned movedAt;
} gbf;

static inline uint8_t gbf_ram(const struct GB* gb, uint32_t a) {
    if (a >= 0xC000 && a < 0xD000) return gb->memory.wram[a - 0xC000];
    if (a >= 0xD000 && a < 0xE000) return gb->memory.wram[4096 + a - 0xD000];
    if (a >= 0xFF80 && a < 0xFFFF) return gb->memory.hram[a - 0xFF80];
    return 0;
}

int pk_gb_follow_knows(uint32_t crc) {
    for (unsigned i = 0; i < sizeof gbfCrcs / sizeof gbfCrcs[0]; i++)
        if (gbfCrcs[i] == crc) return 1;
    return 0;
}

static const struct mCore* gbfCore;   // the core the cart was last checked for

/** A new core (another game, a restart): which cart it runs, by its CRC32. */
static void gbf_check(struct mCore* core) {
    if (core == gbfCore) return;
    gbfCore = core;
    uint32_t crc = 0;
    if (core->platform(core) == mPLATFORM_GB) core->checksum(core, &crc, mCHECKSUM_CRC32);
    pk_gb_follow_configure(crc);
}

void pk_gb_follow_forget(void) {
    gbfCore = NULL;
    gbf.known = false;
}

int pk_gb_follow_runs(const struct mCore* core) {
    // any thread: only the emu thread's frames check a cart (gbf_check), so this reads what they found
    return core && core == gbfCore && gbf.known;
}

void pk_gb_follow_lost(void) {
    gbf.started = false;
    gbf.walked = 0;
}

void pk_gb_follow_configure(uint32_t crc) {
    gbf.known = pk_gb_follow_knows(crc) != 0;
    gbf.started = false;
    gbf.tn = 0;
    gbf.walked = 0;
}

void pk_gb_follow_set(const uint32_t* argb) {
    if (!argb) {
        gbf.have = false;
        return;
    }
    for (int f = 0; f < 2; f++)
        for (int i = 0; i < GBF_W * GBF_H; i++) {
            uint32_t c = argb[f * GBF_W * GBF_H + i];
            uint32_t a = c >> 24;
            gbf.icon[f][i] = a < 128 ? 0 : 0xFF000000u | (c & 0xFFu) << 16 | (c & 0xFF00u) | ((c >> 16) & 0xFFu);
        }
    gbf.have = true;
}

void pk_gb_follow_show(int on) {
    gbf.show = on != 0;
}

static void gbf_restart(int camX, int camY, bool grass) {
    gbf.tn = 1;
    gbf.tx[0] = camX;
    gbf.ty[0] = camY;
    gbf.tgrass[0] = grass;
    gbf.walked = 0;
}

static void gbf_push(int x, int y, bool grass) {
    if (gbf.tn == GBF_TRAIL) {
        memmove(gbf.tx, gbf.tx + 1, (GBF_TRAIL - 1) * sizeof gbf.tx[0]);
        memmove(gbf.ty, gbf.ty + 1, (GBF_TRAIL - 1) * sizeof gbf.ty[0]);
        memmove(gbf.tgrass, gbf.tgrass + 1, GBF_TRAIL - 1);
        gbf.tn--;
    }
    gbf.tx[gbf.tn] = x;
    gbf.ty[gbf.tn] = y;
    gbf.tgrass[gbf.tn] = grass;
    gbf.tn++;
}

/** The camera's path this frame: moved, rebased (a redraw with the player where he was) or started again (a warp). */
static void gbf_track(const struct GB* gb) {
    const uint8_t scx = gb->memory.io[GB_REG_SCX], scy = gb->memory.io[GB_REG_SCY];
    const uint8_t mg = gbf_ram(gb, GBF_MAP_GROUP), mn = gbf_ram(gb, GBF_MAP_NUMBER);
    const uint8_t px = gbf_ram(gb, GBF_PLAYER_OBJ + 0x12), py = gbf_ram(gb, GBF_PLAYER_OBJ + 0x13);
    const bool grass = (gbf_ram(gb, GBF_PLAYER_OBJ + 0x05) & 0x08) != 0;
    if (!gbf.started) {
        gbf.started = true;
        gbf.camX = gbf.camY = 0;
        gbf_restart(0, 0, grass);
    } else {
        int dx = (int8_t) (scx - gbf.lastScx), dy = (int8_t) (scy - gbf.lastScy);
        bool sameMap = mg == gbf.lastMapGroup && mn == gbf.lastMapNum;
        int tdx = (int) px - gbf.lastTileX, tdy = (int) py - gbf.lastTileY;
        bool tileJump = tdx > 1 || tdx < -1 || tdy > 1 || tdy < -1;
        // a battle scrolls the screen as it slides in and out: none of that is the player walking
        bool field = (gb->memory.io[GB_REG_LCDC] & 0x80) && !gbf_ram(gb, GBF_BATTLE_MODE) && gbf_ram(gb, GBF_VBLANK) != 1;
        if (!field) {
        } else if ((!sameMap || tileJump) && !(gbf.tick - gbf.movedAt <= 2 && dx >= -8 && dx <= 8 && dy >= -8 && dy <= 8)) {
            // a warp (the player stood through its fade): start again. Walking over a map's edge into the next
            // map changes the map and the tile numbers mid-step, the screen scrolling on: keep the path
            gbf_restart(gbf.camX, gbf.camY, grass);
        } else if (dx > 8 || dx < -8 || dy > 8 || dy < -8) {
            // the screen redrawn somewhere else with the player where he was: keep the path
        } else if (dx || dy) {
            gbf.camX += dx;
            gbf.camY += dy;
            gbf.movedAt = gbf.tick;
            gbf.walked += (dx < 0 ? -dx : dx) + (dy < 0 ? -dy : dy);
            if (gbf.walked > 1000) gbf.walked = 1000;
            gbf_push(gbf.camX, gbf.camY, grass);
            gbf.pathPx += (unsigned) ((dx < 0 ? -dx : dx) + (dy < 0 ? -dy : dy));
        }
    }
    gbf.lastScx = scx;
    gbf.lastScy = scy;
    gbf.lastMapGroup = mg;
    gbf.lastMapNum = mn;
    gbf.lastTileX = px;
    gbf.lastTileY = py;
}

/** The follower's place on the path: GBF_BEHIND pixels back from the newest point (or its start, before that). */
static void gbf_place(int* x, int* y, bool* grass) {
    int left = GBF_BEHIND;
    int i = gbf.tn - 1;
    while (i > 0) {
        int sx = gbf.tx[i] - gbf.tx[i - 1], sy = gbf.ty[i] - gbf.ty[i - 1];
        int len = (sx < 0 ? -sx : sx) + (sy < 0 ? -sy : sy);
        if (len >= left) {
            // part way along the step from i - 1 to i, facing the way it went
            *x = gbf.tx[i] - (sx * left) / (len ? len : 1);
            *y = gbf.ty[i] - (sy * left) / (len ? len : 1);
            *grass = gbf.tgrass[i - 1] || gbf.tgrass[i];
            if (sx > 0) gbf.facing = 3;
            else if (sx < 0) gbf.facing = 2;
            else if (sy > 0) gbf.facing = 0;
            else if (sy < 0) gbf.facing = 1;
            return;
        }
        left -= len;
        i--;
    }
    *x = gbf.tx[0];
    *y = gbf.ty[0];
    *grass = gbf.tgrass[0];
}

/** BG colour index at a screen pixel (| 0x80 = the tile has priority over sprites), or -1 under the window. */
static int gbf_bgIndex(const struct GB* gb, int x, int y) {
    const uint8_t* io = gb->memory.io;
    const uint8_t* vram = gb->video.vram;
    uint8_t lcdc = io[GB_REG_LCDC];
    if ((lcdc & 0x20) && y >= io[GB_REG_WY] && x >= (int) io[GB_REG_WX] - 7) return -1;
    int bx = (x + io[GB_REG_SCX]) & 255, by = (y + io[GB_REG_SCY]) & 255;
    int map = (lcdc & 0x08) ? 0x1C00 : 0x1800;
    int cell = map + (by >> 3) * 32 + (bx >> 3);
    uint8_t id = vram[cell], attr = vram[0x2000 + cell];
    int base = (lcdc & 0x10) ? id * 16 : 0x1000 + (int8_t) id * 16;
    if (attr & 0x08) base += 0x2000;
    int row = by & 7, col = bx & 7;
    if (attr & 0x40) row = 7 - row;
    if (attr & 0x20) col = 7 - col;
    uint8_t lo = vram[base + row * 2], hi = vram[base + row * 2 + 1];
    int bit = 7 - col;
    int idx = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
    return idx | (attr & 0x80);
}

/** The game's sprites' opaque pixels over the follower's box; and whether the player's own sprite is up. */
static bool gbf_spriteMask(const struct GB* gb, int x0, int y0, uint8_t mask[GBF_H][GBF_W], int* playerPal) {
    bool player = false;
    uint8_t lcdc = gb->memory.io[GB_REG_LCDC];
    int h = (lcdc & 0x04) ? 16 : 8;
    memset(mask, 0, GBF_H * GBF_W);
    for (int n = 0; n < 40; n++) {
        const uint8_t* o = &gb->video.oam.raw[n * 4];
        int oy = o[0] - 16, ox = o[1] - 8;
        uint8_t tile = o[2], attr = o[3];
        if (ox >= GBF_PX && ox < GBF_PX + 16 && oy >= GBF_PY && oy < GBF_PY + 16 && o[2] != 0xFE) {
            if (!player) *playerPal = attr & 7;
            player = true;
        }
        if (oy >= y0 + GBF_H || oy + h <= y0 || ox >= x0 + GBF_W || ox + 8 <= x0) continue;
        if (h == 16) tile &= 0xFE;
        int base = ((attr & 0x08) ? 0x2000 : 0) + tile * 16;
        for (int r = 0; r < h; r++) {
            int sy = oy + r - y0;
            if (sy < 0 || sy >= GBF_H) continue;
            int tr = (attr & 0x40) ? h - 1 - r : r;
            uint8_t lo = gb->video.vram[base + tr * 2], hi = gb->video.vram[base + tr * 2 + 1];
            for (int c = 0; c < 8; c++) {
                int sx = ox + c - x0;
                if (sx < 0 || sx >= GBF_W) continue;
                int bit = (attr & 0x20) ? c : 7 - c;
                if (((lo >> bit) & 1) | ((hi >> bit) & 1)) mask[sy][sx] = 1;
            }
        }
    }
    return player;
}

static inline uint32_t gbf_liveColor(const struct GB* gb, int i) {
    const struct GBVideoSoftwareRenderer* r = (const struct GBVideoSoftwareRenderer*) gb->video.renderer;
    return (uint32_t) r->palette[r->lookup[i]];
}

static bool gbf_hidden(const char* why) {
    snprintf(gbf.why, sizeof gbf.why, "%s", why);
    return true;
}

void pk_gb_follow_frame(struct mCore* core, uint32_t* video, int w, int h, int stride) {
    gbf.tick++;
    gbf.sx = gbf.sy = -999;
    if (!core || core->platform(core) != mPLATFORM_GB || w != 160 || h != 144) return;
    gbf_check(core);
    if (!gbf.known) return;
    const struct GB* gb = (const struct GB*) core->board;
    gbf_track(gb);
    if (!gbf.show || !gbf.have) {
        gbf_hidden(gbf.have ? "off" : "no icon");
        return;
    }
    const uint8_t lcdc = gb->memory.io[GB_REG_LCDC];
    if (!(lcdc & 0x80) || !(lcdc & 0x02)) { gbf_hidden("objects off"); return; }
    if (gbf_ram(gb, GBF_BATTLE_MODE) || gbf_ram(gb, GBF_VBLANK) == 1) { gbf_hidden("battle"); return; }
    uint8_t state = gbf_ram(gb, GBF_PLAYER_STATE);
    if (state == 4 || state == 8) { gbf_hidden("surfing"); return; }
    if (gbf.walked == 0) { gbf_hidden("waiting for a step"); return; }
    int px, py;
    bool grass;
    gbf_place(&px, &py, &grass);
    int x0 = GBF_PX + (px - gbf.camX), y0 = GBF_PY + (py - gbf.camY);
    if (x0 == GBF_PX && y0 == GBF_PY) { gbf_hidden("under the player"); return; }
    uint8_t mask[GBF_H][GBF_W];
    int pal = 0;
    if (!gbf_spriteMask(gb, x0, y0, mask, &pal)) { gbf_hidden("no player sprite"); return; }
    bool textUp = (gbf_ram(gb, GBF_STATE_FLAGS) & 0x40) != 0;
    bool front = y0 > GBF_PY;
    // The scene's light off the player's palette: colour 0 against the day's (27, 31, 27) for fades to black and
    // the night, colour 3 (black by day) for fades to white.
    uint32_t c0 = gbf_liveColor(gb, 32 + pal * 4), c3 = gbf_liveColor(gb, 32 + pal * 4 + 3);
    int mr = (int) (c0 & 0xFF) * 256 / 222, mg = (int) ((c0 >> 8) & 0xFF) * 256 / 255, mb = (int) ((c0 >> 16) & 0xFF) * 256 / 222;
    if (mr > 256) mr = 256;
    if (mg > 256) mg = 256;
    if (mb > 256) mb = 256;
    gbf.c0 = c0;
    gbf.c3 = c3;
    int wr = (int) (c3 & 0xFF), wg = (int) ((c3 >> 8) & 0xFF), wb = (int) ((c3 >> 16) & 0xFF);
    // two poses, the other one each tile walked (half steps looked hectic): still while the player stands
    int frame = (int) (gbf.pathPx / 16) & 1;
    const uint32_t* icon = gbf.icon[frame];
    bool flip = gbf.facing == 3;
    snprintf(gbf.why, sizeof gbf.why, "shown");
    gbf.sx = x0;
    gbf.sy = y0;
    for (int j = 0; j < GBF_H; j++) {
        int y = y0 + j;
        if (y < 0 || y >= h) continue;
        for (int i = 0; i < GBF_W; i++) {
            int x = x0 + i;
            if (x < 0 || x >= w) continue;
            uint32_t c = icon[j * GBF_W + (flip ? GBF_W - 1 - i : i)];
            if (!c) continue;
            if (!front && mask[j][i]) continue;
            int bg = gbf_bgIndex(gb, x, y);
            if (bg < 0) continue;                                   // the window over it
            if ((bg & 0x80) && (bg & 3)) continue;                  // a BG tile with priority
            if (grass && j >= GBF_H / 2 && (bg & 3)) continue;         // tall grass round its feet
            if (textUp) {
                // a menu / text box: its frame and letters (tiles 0x60 and up) stay over it
                const uint8_t* io = gb->memory.io;
                int bx = (x + io[GB_REG_SCX]) & 255, by = (y + io[GB_REG_SCY]) & 255;
                int map = (lcdc & 0x08) ? 0x1C00 : 0x1800;
                if (gb->video.vram[map + (by >> 3) * 32 + (bx >> 3)] >= 0x60) continue;
            }
            int r = (int) (c & 0xFF) * mr >> 8, g = (int) ((c >> 8) & 0xFF) * mg >> 8, b = (int) ((c >> 16) & 0xFF) * mb >> 8;
            r = r + (255 - r) * wr / 255;
            g = g + (255 - g) * wg / 255;
            b = b + (255 - b) * wb / 255;
            video[y * stride + x] = (uint32_t) r | (uint32_t) g << 8 | (uint32_t) b << 16 | 0xFF000000u;
        }
    }
}

void pk_gb_follow_info(char* out, int n) {
    snprintf(out, (size_t) n, "known=%d show=%d icon=%d walked=%d trail=%d cam=%d,%d at=%d,%d facing=%d c0=%06x c3=%06x %s", gbf.known,
        gbf.show, gbf.have, gbf.walked, gbf.tn, gbf.camX, gbf.camY, gbf.sx, gbf.sy, gbf.facing, gbf.c0 & 0xFFFFFF, gbf.c3 & 0xFFFFFF, gbf.why);
}
