// FREE FLIGHT: see pk_flight.h. GPL-3.0 (see NOTICE).
#include "pk_flight.h"
#include "pk_wide.h"

#include <stdio.h>
#include <math.h>
#include <string.h>

#include <mgba/core/core.h>


// struct ObjectEvent (include/global.fieldmap.h): 0x24 bytes. The bit fields' bytes.
#define FL_OBJ_SIZE 0x24u
#define FL_OBJ_FLAGS0 0x00u        // bit 6 heldMovementActive, bit 7 heldMovementFinished
#define FL_OBJ_FLAGS1 0x01u        // bit 5 invisible
#define FL_OBJ_FLAGS2 0x02u        // bit 0 isPlayer
#define FL_OBJ_SPRITE_ID 0x04u
#define FL_OBJ_LOCAL_ID 0x08u
#define FL_OBJ_ELEVATION 0x0Bu     // currentElevation (low nibble), previousElevation (high)
#define FL_OBJ_X 0x10u             // currentCoords (s16 x, y), map grid coordinates (+ FL_MAP_OFFSET)
#define FL_OBJ_Y 0x12u
#define FL_OBJ_EFFECT_SPRITE 0x1Au // fieldEffectSpriteId: the bird's sprite while flying (the surf blob's slot - never both)
#define FL_OBJ_ACTION 0x1Cu        // movementActionId
#define FL_HELD_ACTIVE 0x40u
#define FL_HELD_FINISHED 0x80u
#define FL_INVISIBLE 0x20u
#define FL_IS_PLAYER 0x01u
#define FL_LOCALID_PLAYER 0xFFu
#define FL_LOCALID_CAMERA 0x7Fu    // DoFlaggedGroundEffects skips an invisible object with this localId: no ground effects
#define FL_MOVEMENT_ACTION_NONE 0xFFu

// struct PlayerAvatar
#define FL_AVATAR_FLAGS 0x00u      // PLAYER_AVATAR_FLAG_*: ON_FOOT 1, MACH_BIKE 2, ACRO_BIKE 4, SURFING 8, UNDERWATER 0x10
#define FL_AVATAR_OBJECT_ID 0x05u
#define FL_FLAG_ON_FOOT_OR_BIKE 0x07u
#define FL_FLAG_ON_WATER 0x18u

// struct Sprite (include/sprite.h): 0x44 bytes.
#define FL_SPR_SIZE 0x44u
#define FL_SPR_ATTR1 0x02u         // bit 12: hflip
#define FL_SPR_ATTR2 0x04u         // bits 10-11 priority, 12-15 palette
#define FL_SPR_X 0x20u
#define FL_SPR_Y 0x22u
#define FL_SPR_X2 0x24u
#define FL_SPR_Y2 0x26u
#define FL_SPR_ANIM_NUM 0x2Au
#define FL_SPR_DATA 0x2Eu
#define FL_SPR_FLAGS 0x3Eu         // bit 0 inUse, bit 1 coordOffsetEnabled, bit 2 invisible
#define FL_SPR_FLAGS2 0x3Fu        // bit 0 hFlip
#define FL_MAX_SPRITES 64u

#define FL_MAP_OFFSET 7
#define FL_MAPGRID_UNDEFINED 0x3FFu
#define FL_OBJECT_EVENTS_COUNT 16u

#define FL_DIR_SOUTH 1
#define FL_DIR_NORTH 2
#define FL_DIR_WEST 3
#define FL_DIR_EAST 4

// Timing, in frames, and the rider's height over the ground.
#define FL_SWOOP_FRAMES 24
#define FL_RISE_FRAMES 16
#define FL_LEAVE_FRAMES 24
#define FL_LIFT 16
#define FL_SHADOW_Y 13      // the shadow's centre under the player's place on the ground: the sprite centre to its feet, less half the shadow
#define FL_SHADOW_DROP 11   // ... and that much lower again at full height: the higher the bird, the further below it
#define FL_LAND_RADIUS 24

static uint32_t flCfg[PK_FLIGHT_WORDS];
static int flConfigured;
static int flState = PK_FLIGHT_IDLE;
static int flPhase;               // frames into the current part
static int flBird = -1;           // the bird's sprite id
static int flShadowOn;            // its shadow is drawn (pk_flight_draw): up, on the field
static int flShadeW;              // the width of what pk_flight_draw last darkened (0 = nothing to put back)
static int flStepDir;             // the step under way's direction
static double flVx, flVy;         // the velocity the flight means, px a frame (east / south +): EXPAND's camera moves at it
static int flBlockedH, flBlockedV; // the world's edge stopped this way of a diagonal: it flies the other way only
static int flLook = FL_DIR_WEST;  // how the bird is drawn: from behind (NORTH) or from the side, facing WEST / EAST
static int flLift;                // pixels over the ground
static int flAnchorX, flAnchorY;    // the player's place on screen (the camera keeps it there)
static int flFacing = FL_DIR_EAST;   // the last way flown
static int flLastHorizontal = FL_DIR_EAST;
static int flLandWanted;
static int flLandX = -1, flLandY = -1;
static int flStepParity;
static uint8_t flRoute[4096];
static int flRouteLen = -1, flRouteDone;
static int flBob;

static uint32_t flObjAddr(struct mCore* core) {
    uint32_t id = core->busRead8(core, flCfg[PK_FLIGHT_PLAYER_AVATAR] + FL_AVATAR_OBJECT_ID);
    return flCfg[PK_FLIGHT_OBJECT_EVENTS] + (id < FL_OBJECT_EVENTS_COUNT ? id : 0) * FL_OBJ_SIZE;
}

static uint32_t flSpriteAddr(int id) {
    return flCfg[PK_FLIGHT_SPRITES] + (uint32_t) id * FL_SPR_SIZE;
}

static pk_flight_call_fn flCallFn;

void pk_flight_set_call(pk_flight_call_fn fn) {
    flCallFn = fn;
}

static int flCall(struct mCore* core, uint32_t fn, uint32_t a0, uint32_t a1, uint32_t a2, uint32_t a3, uint32_t* r0) {
    uint32_t args[4] = { a0, a1, a2, a3 };
    uint32_t out = 0;
    int ok = flCallFn && fn && flCallFn(core, fn, args, &out);
    if (r0) *r0 = out;
    return ok;
}

/** The free field: the overworld's callback, no fade going. (Whether the controls are locked is the caller's.) */
static int flOnField(struct mCore* core) {
    uint32_t cb2 = core->busRead32(core, flCfg[PK_FLIGHT_MAIN] + 4) & ~1u;
    if (cb2 != (flCfg[PK_FLIGHT_CB2_OVERWORLD] & ~1u)) return 0;
    return !(core->busRead8(core, flCfg[PK_FLIGHT_PALETTE_FADE] + 7) & 0x80);
}

/** Whether VMap tile (x, y), past the current map's [dir] edge, belongs to a connected map on the no-fly list: the
 * map's connections (gMapHeader +0xC: {u8 dir, s32 offset, u8 group, u8 num}, 12 bytes) in that direction, each covering
 * its own width (or height) from its offset along the edge. */
static int flNoFlyAt(struct mCore* core, int x, int y, int xs, int ys, int dir) {
    uint32_t a = flCfg[PK_FLIGHT_NO_FLY], b = flCfg[PK_FLIGHT_NO_FLY + 1];
    uint32_t ids[4] = { a & 0xFFFF, a >> 16, b & 0xFFFF, b >> 16 };
    if (!flCfg[PK_FLIGHT_MAP_HEADER] || !flCfg[PK_FLIGHT_MAP_GROUPS] || (ids[0] == 0xFFFF && ids[2] == 0xFFFF && ids[1] == 0xFFFF && ids[3] == 0xFFFF))
        return 0;
    uint32_t conns = core->busRead32(core, flCfg[PK_FLIGHT_MAP_HEADER] + 0xC);
    if (conns < 0x08000000u || conns >= 0x0A000000u) return 0;
    int n = (int) core->busRead32(core, conns);
    uint32_t list = core->busRead32(core, conns + 4);
    if (n <= 0 || n > 16 || list < 0x08000000u || list >= 0x0A000000u) return 0;
    int lx = x - FL_MAP_OFFSET, ly = y - FL_MAP_OFFSET;   // the current map's own coordinates
    (void) xs; (void) ys;
    for (int i = 0; i < n; i++) {
        uint32_t e = list + 12u * (uint32_t) i;
        if ((int) core->busRead8(core, e) != dir) continue;
        uint32_t g = core->busRead8(core, e + 8), m = core->busRead8(core, e + 9), id = g << 8 | m;
        if (id != ids[0] && id != ids[1] && id != ids[2] && id != ids[3]) continue;
        int off = (int) core->busRead32(core, e + 4);
        uint32_t grp = core->busRead32(core, flCfg[PK_FLIGHT_MAP_GROUPS] + 4 * g);
        uint32_t hdr = grp ? core->busRead32(core, grp + 4 * m) : 0;
        uint32_t lay = hdr ? core->busRead32(core, hdr) : 0;
        if (!lay) return 1;   // can't tell its span: keep out
        int tw = (int) core->busRead32(core, lay), th = (int) core->busRead32(core, lay + 4);
        int along = dir == FL_DIR_SOUTH || dir == FL_DIR_NORTH ? lx : ly, span = dir == FL_DIR_SOUTH || dir == FL_DIR_NORTH ? tw : th;
        if (along >= off && along < off + span) return 1;
    }
    return 0;
}

/** The map grid's edges, mended: crossing into a map, FireRed copies the screen-sized area around the camera from the
 * old map's grid into the new one (MoveMapViewToBackup), so near the crossing the new map's connections show the old
 * map's emptiness (undefined squares) - unreachable walking, but a diagonal flight into a corner met it: the bird stopped
 * above Cinnabar with Route 21 right there. Every undefined square the map's own layout or a connection covers gets its
 * square back from the ROM, and those in view are drawn again. Once per map (VMap's
 * size and the map's layout tell a new one), while flying. */
static uint32_t flMendedLayout;
static int flMendPending;
static int flMendAt[64][2], flMendN;   // squares mended in view, still to be drawn

static uint32_t flLayoutOf(struct mCore* core, uint32_t g, uint32_t m) {
    uint32_t grp = core->busRead32(core, flCfg[PK_FLIGHT_MAP_GROUPS] + 4 * g);
    uint32_t hdr = grp >= 0x08000000u && grp < 0x0A000000u ? core->busRead32(core, grp + 4 * m) : 0;
    uint32_t lay = hdr >= 0x08000000u && hdr < 0x0A000000u ? core->busRead32(core, hdr) : 0;
    return lay >= 0x08000000u && lay < 0x0A000000u ? lay : 0;
}

static void flMend(struct mCore* core) {
    uint32_t vmap = flCfg[PK_FLIGHT_VMAP], hdr = flCfg[PK_FLIGHT_MAP_HEADER];
    if (!hdr || !flCfg[PK_FLIGHT_MAP_GROUPS]) return;
    int xs = (int) core->busRead32(core, vmap), ys = (int) core->busRead32(core, vmap + 4);
    uint32_t map = core->busRead32(core, vmap + 8);
    uint32_t layout = core->busRead32(core, hdr);
    if (xs <= 15 || ys <= 14 || !map || !layout) return;
    if (layout != flMendedLayout) { flMendedLayout = layout; flMendPending = 1; }
    if (!flMendPending) return;
    flMendPending = 0;
    int mw = xs - 15, mh = ys - 14;
    // The map's own squares first (the copy reaches into it too: Route 20's west edge after Cinnabar), from its layout.
    uint32_t own = core->busRead32(core, layout + 0xC);
    if (own >= 0x08000000u && own < 0x0A000000u && (int) core->busRead32(core, layout) == mw && (int) core->busRead32(core, layout + 4) == mh) {
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                uint32_t at = map + 2u * (uint32_t) (x + FL_MAP_OFFSET + xs * (y + FL_MAP_OFFSET));
                if ((core->busRead16(core, at) & 0xFFFF) != FL_MAPGRID_UNDEFINED) continue;
                uint16_t v = core->busRead16(core, own + 2u * (uint32_t) (x + mw * y));
                if ((v & 0x3FF) == FL_MAPGRID_UNDEFINED) continue;
                core->busWrite16(core, at, v);
                if (flMendN < 64) { flMendAt[flMendN][0] = x + FL_MAP_OFFSET; flMendAt[flMendN][1] = y + FL_MAP_OFFSET; flMendN++; }
            }
        }
    }
    uint32_t conns = core->busRead32(core, hdr + 0xC);
    if (conns < 0x08000000u || conns >= 0x0A000000u) return;
    int n = (int) core->busRead32(core, conns);
    uint32_t list = core->busRead32(core, conns + 4);
    if (n <= 0 || n > 16 || list < 0x08000000u || list >= 0x0A000000u) return;
    uint32_t a = flCfg[PK_FLIGHT_NO_FLY], b = flCfg[PK_FLIGHT_NO_FLY + 1];
    for (int i = 0; i < n; i++) {
        uint32_t e = list + 12u * (uint32_t) i;
        int dir = core->busRead8(core, e), off = (int) core->busRead32(core, e + 4);
        uint32_t g = core->busRead8(core, e + 8), m = core->busRead8(core, e + 9), id = g << 8 | m;
        if (id == (a & 0xFFFF) || id == (a >> 16) || id == (b & 0xFFFF) || id == (b >> 16)) continue;   // stays out
        uint32_t lay = flLayoutOf(core, g, m);
        if (!lay) continue;
        int cw = (int) core->busRead32(core, lay), ch = (int) core->busRead32(core, lay + 4);
        uint32_t data = core->busRead32(core, lay + 0xC);
        if (cw <= 0 || ch <= 0 || data < 0x08000000u || data >= 0x0A000000u) continue;
        // The padding this connection fills, in VMap squares, and the connected map's square for each.
        int x0, x1, y0, y1;
        if (dir == FL_DIR_SOUTH || dir == FL_DIR_NORTH) {
            x0 = FL_MAP_OFFSET + off; x1 = x0 + cw;
            y0 = dir == FL_DIR_NORTH ? 0 : FL_MAP_OFFSET + mh; y1 = dir == FL_DIR_NORTH ? FL_MAP_OFFSET : ys;
        } else {
            y0 = FL_MAP_OFFSET + off; y1 = y0 + ch;
            x0 = dir == FL_DIR_WEST ? 0 : FL_MAP_OFFSET + mw; x1 = dir == FL_DIR_WEST ? FL_MAP_OFFSET : xs;
        }
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x1 > xs) x1 = xs;
        if (y1 > ys) y1 = ys;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                uint32_t at = map + 2u * (uint32_t) (x + xs * y);
                if ((core->busRead16(core, at) & 0xFFFF) != FL_MAPGRID_UNDEFINED) continue;
                int sx, sy;
                if (dir == FL_DIR_NORTH) { sx = x - FL_MAP_OFFSET - off; sy = ch - FL_MAP_OFFSET + y; }
                else if (dir == FL_DIR_SOUTH) { sx = x - FL_MAP_OFFSET - off; sy = y - FL_MAP_OFFSET - mh; }
                else if (dir == FL_DIR_WEST) { sx = cw - FL_MAP_OFFSET + x; sy = y - FL_MAP_OFFSET - off; }
                else { sx = x - FL_MAP_OFFSET - mw; sy = y - FL_MAP_OFFSET - off; }
                if (sx < 0 || sx >= cw || sy < 0 || sy >= ch) continue;
                uint16_t v = core->busRead16(core, data + 2u * (uint32_t) (sx + cw * sy));
                if ((v & 0x3FF) == FL_MAPGRID_UNDEFINED) continue;
                core->busWrite16(core, at, v);
                if (flMendN < 64) { flMendAt[flMendN][0] = x; flMendAt[flMendN][1] = y; flMendN++; }
            }
        }
    }
}

/** The mended squares drawn (CurrentMapDrawMetatileAt skips one out of view); what can't be called this frame waits. */
static void flMendDraw(struct mCore* core) {
    while (flMendN > 0 && flCfg[PK_FLIGHT_DRAW_METATILE]) {
        if (!flCall(core, flCfg[PK_FLIGHT_DRAW_METATILE], (uint32_t) flMendAt[flMendN - 1][0], (uint32_t) flMendAt[flMendN - 1][1], 0, 0, NULL)) return;
        flMendN--;
    }
    flMendN = 0;
}

/** GetMapBorderIdAt != CONNECTION_INVALID, read from RAM (fieldmap.c): inside the map, or past an edge that has a
 * connection. The same answer CanCameraMoveInDirection gives the player's next tile. */
static int flPassable(struct mCore* core, int x, int y) {
    uint32_t vmap = flCfg[PK_FLIGHT_VMAP];
    int xs = (int) core->busRead32(core, vmap), ys = (int) core->busRead32(core, vmap + 4);
    uint32_t map = core->busRead32(core, vmap + 8);
    if (xs <= 0 || ys <= 0 || !map) return 0;
    if (x >= 0 && x < xs && y >= 0 && y < ys) {
        if ((core->busRead16(core, map + 2u * (uint32_t) (x + xs * y)) & 0xFFFFu) == FL_MAPGRID_UNDEFINED) return 0;
    }
    uint8_t f = core->busRead8(core, flCfg[PK_FLIGHT_CONNECTION_FLAGS]);
    if (x >= xs - (FL_MAP_OFFSET + 1)) return (f & 8) != 0 && !flNoFlyAt(core, x, y, xs, ys, FL_DIR_EAST);
    if (x < FL_MAP_OFFSET) return (f & 4) != 0 && !flNoFlyAt(core, x, y, xs, ys, FL_DIR_WEST);
    if (y >= ys - FL_MAP_OFFSET) return (f & 1) != 0 && !flNoFlyAt(core, x, y, xs, ys, FL_DIR_SOUTH);
    if (y < FL_MAP_OFFSET) return (f & 2) != 0 && !flNoFlyAt(core, x, y, xs, ys, FL_DIR_NORTH);
    return 1;
}

static void flStepTo(int dir, int* x, int* y) {
    if (dir == FL_DIR_SOUTH) (*y)++;
    else if (dir == FL_DIR_NORTH) (*y)--;
    else if (dir == FL_DIR_WEST) (*x)--;
    else if (dir == FL_DIR_EAST) (*x)++;
}

/** Whether the player's held movement is done (or none is going). */
static int flStepDone(struct mCore* core, uint32_t obj) {
    uint8_t f0 = core->busRead8(core, obj + FL_OBJ_FLAGS0);
    return !(f0 & FL_HELD_ACTIVE) || (f0 & FL_HELD_FINISHED);
}

/** ObjectEventClearHeldMovement then ObjectEventSetHeldMovement, as RAM writes (event_object_movement.c). */
/** The step a stretch of the trip wants. A straight step takes [faster] 4 frames (else 8); a staircase covers less
 * ground per step (a diagonal 1.41 tiles in two), so its steps take that share of the time ([straightness]: the trip's
 * real distance over its steps') for the same speed along the way. The game's speeds give 8, 6, 4 or 2 frames a tile:
 * each step takes the one nearest what's owed so far (flDebt), so the average comes out exact; the unevenness is
 * EXPAND's camera's to hide (it moves at the velocity the flight means, flVx / flVy). Returns the action's first id
 * (then UP, LEFT, RIGHT). */
static double flDebt;
static uint32_t flStepAction(int faster, double straightness) {
    static const int kFrames[4] = { 8, 6, 4, 2 };
    const uint32_t kAction[4] = { flCfg[PK_FLIGHT_WALK_FAST], flCfg[PK_FLIGHT_WALK_CURRENT], flCfg[PK_FLIGHT_WALK_FASTER], flCfg[PK_FLIGHT_WALK_SLIDE] };
    if (straightness >= 0.999) {
        flDebt = 0;   // a straight run: its own speed, nothing owed
        return flCfg[faster ? PK_FLIGHT_WALK_FASTER : PK_FLIGHT_WALK_FAST];
    }
    flDebt += (faster ? 4.0 : 8.0) * (straightness < 0.5 ? 0.5 : straightness);
    int best = faster ? 2 : 0;
    for (int i = 0; i < 4; i++) {
        if (!kAction[i]) continue;
        double d = flDebt - kFrames[i], b = flDebt - kFrames[best];
        if ((d < 0 ? -d : d) < (b < 0 ? -b : b)) best = i;
    }
    flDebt -= kFrames[best];
    return kAction[best];
}

static void flStartStep(struct mCore* core, uint32_t obj, int dir, int faster, double straightness) {
    uint32_t spr = flSpriteAddr(core->busRead8(core, obj + FL_OBJ_SPRITE_ID));
    uint32_t base = flStepAction(faster, straightness);
    core->busWrite16(core, spr + FL_SPR_DATA + 2, 0);
    core->busWrite16(core, spr + FL_SPR_DATA + 4, 0);
    core->busWrite8(core, obj + FL_OBJ_ACTION, (uint8_t) (base + (uint32_t) dir - 1));
    uint8_t f0 = core->busRead8(core, obj + FL_OBJ_FLAGS0);
    core->busWrite8(core, obj + FL_OBJ_FLAGS0, (uint8_t) ((f0 & ~FL_HELD_FINISHED) | FL_HELD_ACTIVE));
    flFacing = dir;
    if (dir == FL_DIR_WEST || dir == FL_DIR_EAST) flLastHorizontal = dir;
    flStepDir = dir;
}

static void flClearStep(struct mCore* core, uint32_t obj) {
    uint8_t f0 = core->busRead8(core, obj + FL_OBJ_FLAGS0);
    if (!(f0 & FL_HELD_ACTIVE)) return;
    uint32_t spr = flSpriteAddr(core->busRead8(core, obj + FL_OBJ_SPRITE_ID));
    core->busWrite16(core, spr + FL_SPR_DATA + 2, 0);
    core->busWrite16(core, spr + FL_SPR_DATA + 4, 0);
    core->busWrite8(core, obj + FL_OBJ_ACTION, FL_MOVEMENT_ACTION_NONE);
    core->busWrite8(core, obj + FL_OBJ_FLAGS0, (uint8_t) (f0 & ~(FL_HELD_ACTIVE | FL_HELD_FINISHED)));
}

static int flGender(struct mCore* core) {
    uint32_t sb2 = core->busRead32(core, flCfg[PK_FLIGHT_SAVE_BLOCK2_PTR]);
    return sb2 ? core->busRead8(core, sb2 + 8) & 1 : 0;
}

/** Which way the bird is drawn for a trip heading [h] (any east / west in it) and [up] (north alone): a diagonal keeps
 * the side view the whole way - switching to the back view and out again on every step of the staircase was the jumpy
 * look - and going south keeps the side it had. */
static void flHeading(int h, int up) {
    if (h == FL_DIR_WEST || h == FL_DIR_EAST) flLook = h;
    else if (up) flLook = FL_DIR_NORTH;
    else if (flLook == FL_DIR_NORTH) flLook = flLastHorizontal == FL_DIR_EAST ? FL_DIR_EAST : FL_DIR_WEST;
}

/** How straight the route is over its next 8 steps: their net distance over the steps (1 straight, 0.71 a diagonal). */
static double flRouteStraightness(void) {
    int dx = 0, dy = 0, n = 0;
    for (int i = flRouteDone; i < flRouteLen && n < 8; i++, n++) {
        if (flRoute[i] == FL_DIR_EAST) dx++;
        else if (flRoute[i] == FL_DIR_WEST) dx--;
        else if (flRoute[i] == FL_DIR_SOUTH) dy++;
        else dy--;
    }
    return n ? sqrt((double) (dx * dx + dy * dy)) / n : 1.0;
}

/** The route's heading, from where its next 24 steps go in all (a staircase's jogs one way are no turn): the side view
 * toward the east / west it makes, the back view while it mostly climbs north, the side it had going south. With some
 * slack each way, so a trip near the line between two views doesn't flip between them: Pallet Town to Viridian City is
 * north with a step east every eight or so, and took the side view at each jog and the back view between them. */
static void flRouteHeading(void) {
    int dx = 0, north = 0, south = 0;
    for (int i = flRouteDone; i < flRouteLen && i < flRouteDone + 24; i++) {
        if (flRoute[i] == FL_DIR_EAST) dx++;
        else if (flRoute[i] == FL_DIR_WEST) dx--;
        else if (flRoute[i] == FL_DIR_NORTH) north++;
        else south++;
    }
    int across = dx < 0 ? -dx : dx;
    int side = dx > 0 ? FL_DIR_EAST : FL_DIR_WEST;
    if (flLook == FL_DIR_NORTH) {
        // Back view: out of it once the trip leans well to one side, or turns south.
        if (across && (across * 3 >= north || south > north)) flLook = side;
    } else if (north > south && across * 6 < north) {
        flLook = FL_DIR_NORTH;
    } else if (across >= 2 && side != flLook) {
        flLook = side;   // the other way only once it really goes there
    }
    if (flLook != FL_DIR_NORTH) flLastHorizontal = flLook;
}

/** The bird's picture: 0 alone; with the rider, the side view (1 / 3: FireRed's bird flies off to the left, so it's
 * flipped going east) or the back (2 / 4, flying north). Read off the sprite, not remembered: a loaded state brings its
 * own. Only what changed is set (StartSpriteAnim restarts the frame copy). 0 when the game's code couldn't be called. */
static int flBirdLook(struct mCore* core, int ridden) {
    if (flBird < 0) return 1;
    int anim = !ridden ? 0 : (flLook == FL_DIR_NORTH ? 2 : 1) + 2 * flGender(core);
    int flip = ridden && flLook == FL_DIR_EAST;
    uint32_t spr = flSpriteAddr(flBird);
    int shownAnim = core->busRead8(core, spr + FL_SPR_ANIM_NUM);
    int shownFlip = (core->busRead16(core, spr + FL_SPR_ATTR1) & 0x1000u) != 0;
    if (anim == shownAnim && flip == shownFlip) return 1;
    uint8_t f2 = core->busRead8(core, spr + FL_SPR_FLAGS2);
    core->busWrite8(core, spr + FL_SPR_FLAGS2, (uint8_t) ((f2 & ~1u) | (uint8_t) flip));
    if (anim != shownAnim && !flCall(core, flCfg[PK_FLIGHT_START_SPRITE_ANIM], spr, (uint32_t) anim, 0, 0, NULL)) return 0;
    // The frame's flip as the anim set it, then ours (an END-ed anim never sets it again).
    uint16_t a1 = core->busRead16(core, spr + FL_SPR_ATTR1);
    core->busWrite16(core, spr + FL_SPR_ATTR1, (uint16_t) ((a1 & ~0x1000u) | (flip ? 0x1000u : 0)));
    return 1;
}

static void flBirdAt(struct mCore* core, int x, int y) {
    if (flBird < 0) return;
    uint32_t spr = flSpriteAddr(flBird);
    core->busWrite16(core, spr + FL_SPR_X, (uint16_t) x);
    core->busWrite16(core, spr + FL_SPR_Y, (uint16_t) y);
    core->busWrite16(core, spr + FL_SPR_X2, 0);
    core->busWrite16(core, spr + FL_SPR_Y2, 0);
}

/** Where the bird sits: over the player's place, raised by [lift], bobbing a pixel while up. */
static void flBirdOverPlayer(struct mCore* core) {
    int b = 0;
    if (flLift >= FL_LIFT) {
        flBob = (flBob + 1) & 63;
        b = flBob < 16 ? 0 : flBob < 32 ? -1 : flBob < 48 ? -1 : 0;
    }
    flBirdAt(core, flAnchorX, flAnchorY + 8 - flLift + b);
}

/** EXPAND's smooth camera (pk_wide_flight): the bird's tiles, whose OAM entry moves with it. */
static void flTellWide(struct mCore* core) {
    uint16_t tiles[2];
    int n = 0;
    if (flBird >= 0) tiles[n++] = (uint16_t) (core->busRead16(core, flSpriteAddr(flBird) + FL_SPR_ATTR2) & 0x3FF);
    pk_wide_flight(flState != PK_FLIGHT_IDLE, tiles, n, flVx, flVy);
}

/** The player's sprite centre on screen right now (sprite x + x2 + the camera's sprite offset). */
static void flPlayerOnScreen(struct mCore* core, uint32_t obj, int* x, int* y) {
    uint32_t spr = flSpriteAddr(core->busRead8(core, obj + FL_OBJ_SPRITE_ID));
    int sx = (int16_t) core->busRead16(core, spr + FL_SPR_X) + (int16_t) core->busRead16(core, spr + FL_SPR_X2);
    int sy = (int16_t) core->busRead16(core, spr + FL_SPR_Y) + (int16_t) core->busRead16(core, spr + FL_SPR_Y2);
    if (core->busRead8(core, spr + FL_SPR_FLAGS) & 2) {
        sx += (int16_t) core->busRead16(core, flCfg[PK_FLIGHT_SPRITE_OFFSET]);
        sy += (int16_t) core->busRead16(core, flCfg[PK_FLIGHT_SPRITE_OFFSET] + 2);
    }
    *x = sx;
    *y = sy;
}

static void flHidePlayer(struct mCore* core, uint32_t obj, int hidden) {
    uint8_t f1 = core->busRead8(core, obj + FL_OBJ_FLAGS1);
    core->busWrite8(core, obj + FL_OBJ_FLAGS1, (uint8_t) (hidden ? f1 | FL_INVISIBLE : f1 & ~FL_INVISIBLE));
}

static void flMarkPlayer(struct mCore* core, uint32_t obj, int flying) {
    core->busWrite8(core, obj + FL_OBJ_LOCAL_ID, flying ? FL_LOCALID_CAMERA : FL_LOCALID_PLAYER);
    core->busWrite8(core, obj + FL_OBJ_EFFECT_SPRITE, flying && flBird >= 0 ? (uint8_t) flBird : 0);
}

static int flMarked(struct mCore* core, uint32_t obj) {
    return core->busRead8(core, obj + FL_OBJ_LOCAL_ID) == FL_LOCALID_CAMERA &&
           (core->busRead8(core, obj + FL_OBJ_FLAGS2) & FL_IS_PLAYER);
}

static void flPlaySe(struct mCore* core, uint32_t song) {
    if (flCfg[PK_FLIGHT_PLAY_SE]) flCall(core, flCfg[PK_FLIGHT_PLAY_SE], song, 0, 0, 0, NULL);
}

void pk_flight_reset(void) {
    flMendedLayout = 0;
    flMendN = 0;
    flShadeW = 0;
    flState = PK_FLIGHT_IDLE;
    flBird = -1;
    flShadowOn = 0;
    flPhase = flLift = 0;
    flLandWanted = 0;
    flLandX = flLandY = -1;
    flRouteLen = -1;
    flRouteDone = 0;
}

void pk_flight_configure(const uint32_t* words, int n) {
    // The same words again (the app re-sends them every half second): nothing changes, a flight goes on.
    if (flConfigured && words && n >= PK_FLIGHT_WORDS && !memcmp(words, flCfg, sizeof(flCfg))) return;
    pk_flight_reset();
    flConfigured = 0;
    if (!words || n < PK_FLIGHT_WORDS) return;
    memcpy(flCfg, words, sizeof(flCfg));
    flConfigured = 1;
}

int pk_flight_state(void) {
    return flState;
}

int pk_flight_take_off(struct mCore* core) {
    if (!flConfigured || !core) return PK_FLIGHT_NOT_CONFIGURED;
    if (flState != PK_FLIGHT_IDLE) return PK_FLIGHT_BUSY;
    uint32_t obj = flObjAddr(core);
    if (!flOnField(core) || core->busRead8(core, flCfg[PK_FLIGHT_LOCK_BYTE]) || !flStepDone(core, obj) || flMarked(core, obj))
        return PK_FLIGHT_NOT_NOW;
    uint8_t flags = core->busRead8(core, flCfg[PK_FLIGHT_PLAYER_AVATAR] + FL_AVATAR_FLAGS);
    if ((flags & FL_FLAG_ON_WATER) || !(flags & FL_FLAG_ON_FOOT_OR_BIKE)) return PK_FLIGHT_NOT_ON_FOOT;
    uint32_t spriteId = FL_MAX_SPRITES;
    flPlayerOnScreen(core, obj, &flAnchorX, &flAnchorY);
    // The bird comes in from the top right, alone (the game's FLY: CreateFlyBirdSprite's priority and palette).
    if (!flCall(core, flCfg[PK_FLIGHT_CREATE_SPRITE], flCfg[PK_FLIGHT_BIRD_TEMPLATE], (uint32_t) (flAnchorX + 120) & 0xFFFF,
              (uint32_t) (flAnchorY - 100) & 0xFFFF, 1, &spriteId) || spriteId >= FL_MAX_SPRITES) {
        return PK_FLIGHT_NOT_NOW;
    }
    flCall(core, flCfg[PK_FLIGHT_LOCK_CONTROLS], 0, 0, 0, 0, NULL);
    flBird = (int) spriteId;
    uint32_t spr = flSpriteAddr(flBird);
    uint16_t a2 = core->busRead16(core, spr + FL_SPR_ATTR2);
    core->busWrite16(core, spr + FL_SPR_ATTR2, (uint16_t) ((a2 & 0x03FFu) | (1u << 10)));   // priority 1, palette 0
    core->busWrite8(core, spr + FL_SPR_FLAGS, (uint8_t) (core->busRead8(core, spr + FL_SPR_FLAGS) & ~2u));  // screen coordinates
    uint8_t dir = core->busRead8(core, obj + 0x18) & 0x0F;
    flFacing = dir >= FL_DIR_SOUTH && dir <= FL_DIR_EAST ? dir : FL_DIR_EAST;
    flLastHorizontal = flFacing == FL_DIR_WEST ? FL_DIR_WEST : FL_DIR_EAST;
    flLook = flLastHorizontal;
    flBirdLook(core, 0);
    flMarkPlayer(core, obj, 1);   // marked from the start: a state saved from here on comes back flying
    flState = PK_FLIGHT_TAKING_OFF;
    flPhase = 0;
    flLift = 0;
    flLandWanted = 0;
    flRouteLen = -1;
    return PK_FLIGHT_OK;
}

void pk_flight_land(void) {
    if (flState == PK_FLIGHT_TAKING_OFF || flState == PK_FLIGHT_FLYING) flLandWanted = 1;
}

int pk_flight_route(const uint8_t* dirs, int n) {
    if (flState != PK_FLIGHT_FLYING && flState != PK_FLIGHT_TAKING_OFF) return PK_FLIGHT_NOT_NOW;
    if (n < 0) n = 0;
    if (n > (int) sizeof(flRoute)) n = (int) sizeof(flRoute);
    memcpy(flRoute, dirs, (size_t) n);
    flRouteLen = n;
    flRouteDone = 0;
    return PK_FLIGHT_OK;
}

int pk_flight_route_done(void) {
    return flRouteLen < 0 ? -1 : flRouteDone;
}

/** Whether a player could stand on (x, y): the game's own collision, elevation and behaviour there (a whitelist:
 * ground, grass, sand... never water, a ledge, a door or a warp), inside this map, and nobody else on it. -1 when the
 * game's code couldn't be called this frame. */
static int flLandable(struct mCore* core, int x, int y, uint32_t selfId) {
    uint32_t vmap = flCfg[PK_FLIGHT_VMAP];
    int xs = (int) core->busRead32(core, vmap), ys = (int) core->busRead32(core, vmap + 4);
    if (x < FL_MAP_OFFSET || x >= xs - (FL_MAP_OFFSET + 1) || y < FL_MAP_OFFSET || y >= ys - FL_MAP_OFFSET) return 0;
    uint32_t r = 0;
    if (!flCall(core, flCfg[PK_FLIGHT_COLLISION_AT], (uint32_t) x, (uint32_t) y, 0, 0, &r)) return -1;
    if (r & 0xFF) return 0;
    if (!flCall(core, flCfg[PK_FLIGHT_ELEVATION_AT], (uint32_t) x, (uint32_t) y, 0, 0, &r)) return -1;
    if ((r & 0xFF) == 15) return 0;
    if (!flCall(core, flCfg[PK_FLIGHT_BEHAVIOR_AT], (uint32_t) x, (uint32_t) y, 0, 0, &r)) return -1;
    uint32_t mb = r & 0xFF;
    if (!(flCfg[PK_FLIGHT_LAND_OK + mb / 32] & (1u << (mb % 32)))) return 0;
    if (!flCall(core, flCfg[PK_FLIGHT_OBJECT_AT], (uint32_t) x, (uint32_t) y, 0, 0, &r)) return -1;
    r &= 0xFF;
    return r >= FL_OBJECT_EVENTS_COUNT || r == selfId;
}

/** The nearest landable tile, ring by ring (the closest in a ring by straight-line distance). 1 found, 0 none,
 * -1 try again next frame. */
static int flFindLanding(struct mCore* core, uint32_t obj) {
    int px = (int16_t) core->busRead16(core, obj + FL_OBJ_X), py = (int16_t) core->busRead16(core, obj + FL_OBJ_Y);
    uint32_t selfId = core->busRead8(core, flCfg[PK_FLIGHT_PLAYER_AVATAR] + FL_AVATAR_OBJECT_ID);
    for (int d = 0; d <= FL_LAND_RADIUS; d++) {
        int best = -1, bx = 0, by = 0;
        for (int dy = -d; dy <= d; dy++) {
            for (int dx = -d; dx <= d; dx++) {
                if (dx != -d && dx != d && dy != -d && dy != d) continue;
                int dist = dx * dx + dy * dy;
                if (best >= 0 && dist >= best) continue;
                int ok = flLandable(core, px + dx, py + dy, selfId);
                if (ok < 0) return -1;
                if (ok) { best = dist; bx = px + dx; by = py + dy; }
            }
        }
        if (best >= 0) {
            flLandX = bx;
            flLandY = by;
            return 1;
        }
    }
    return 0;
}

/** The way the D-pad points (two held: taken in turn, so a diagonal comes out a staircase). */
static int flPadDirection(uint32_t keys) {
    int h = (keys & 0x10) ? FL_DIR_EAST : (keys & 0x20) ? FL_DIR_WEST : 0;   // GBA keys: RIGHT 0x10, LEFT 0x20, UP 0x40, DOWN 0x80
    int v = (keys & 0x40) ? FL_DIR_NORTH : (keys & 0x80) ? FL_DIR_SOUTH : 0;
    if (h && v && flBlockedV) return h;
    if (h && v && flBlockedH) return v;
    if (h && v) return (flStepParity & 1) ? h : v;
    return h ? h : v;
}

/** Adopt a flight a loaded state holds (the player marked), or drop ours if the state had none. */
static void flFollowRam(struct mCore* core, uint32_t obj) {
    int isMarked = flMarked(core, obj);
    if (flState == PK_FLIGHT_IDLE && isMarked) {
        int id = core->busRead8(core, obj + FL_OBJ_EFFECT_SPRITE);
        flBird = id < (int) FL_MAX_SPRITES && (core->busRead8(core, flSpriteAddr(id) + FL_SPR_FLAGS) & 1) ? id : -1;
        int hidden = (core->busRead8(core, obj + FL_OBJ_FLAGS1) & FL_INVISIBLE) != 0;
        int sx, sy;
        flPlayerOnScreen(core, obj, &sx, &sy);
        flAnchorX = sx;
        flAnchorY = sy;
        flLandWanted = 0;
        flRouteLen = -1;
        if (flBird < 0) {
            // Nothing to ride: put the player back on the ground where it is.
            flHidePlayer(core, obj, 0);
            flMarkPlayer(core, obj, 0);
            flCall(core, flCfg[PK_FLIGHT_UNLOCK_CONTROLS], 0, 0, 0, 0, NULL);
            return;
        }
        flState = hidden ? PK_FLIGHT_FLYING : PK_FLIGHT_TAKING_OFF;
        flPhase = hidden ? 0 : FL_SWOOP_FRAMES;
        flLift = hidden ? FL_LIFT : 0;
    } else if (flState != PK_FLIGHT_IDLE && flState != PK_FLIGHT_DISMOUNT && !isMarked) {
        pk_flight_reset();
    }
}

void pk_flight_tick(struct mCore* core) {
    if (!flConfigured || !core) return;
    uint32_t obj = flObjAddr(core);
    flFollowRam(core, obj);
    if (flState == PK_FLIGHT_IDLE || !flOnField(core)) {
        flShadowOn = 0;
        flTellWide(core);
        return;
    }
    uint32_t keys = core->getKeys ? core->getKeys(core) : 0;
    if ((keys & 0x2) && flState != PK_FLIGHT_DISMOUNT) flLandWanted = 1;   // B
    if (flState != PK_FLIGHT_DISMOUNT) {
        flMend(core);
        flMendDraw(core);
    }
    switch (flState) {
    case PK_FLIGHT_TAKING_OFF:
        if (flPhase < FL_SWOOP_FRAMES) {
            // In along a curve from the top right, slowing as it comes.
            int t = FL_SWOOP_FRAMES - flPhase;   // frames left
            int k = t * t;
            flBirdAt(core, flAnchorX + 120 * k / (FL_SWOOP_FRAMES * FL_SWOOP_FRAMES),
                   flAnchorY + 8 - 108 * k / (FL_SWOOP_FRAMES * FL_SWOOP_FRAMES));
            flPhase++;
            break;
        }
        if (flPhase == FL_SWOOP_FRAMES) {
            if (!flBirdLook(core, 1)) break;   // StartSpriteAnim not callable this frame: next
            flPlaySe(core, flCfg[PK_FLIGHT_SE_FLY]);
            flHidePlayer(core, obj, 1);
        }
        flPhase++;
        flLift = (flPhase - FL_SWOOP_FRAMES) * FL_LIFT / FL_RISE_FRAMES;
        flBirdOverPlayer(core);
        if (flLift >= FL_LIFT) {
            flLift = FL_LIFT;
            flState = PK_FLIGHT_FLYING;
            flPhase = 0;
        }
        break;
    case PK_FLIGHT_FLYING:
        flBirdOverPlayer(core);
        if (flRouteLen < 0) {
            // The bird faces the way the D-pad points (a diagonal: its east / west), not each step of the staircase.
            int h = (keys & 0x10) ? FL_DIR_EAST : (keys & 0x20) ? FL_DIR_WEST : 0;
            if (h || (keys & 0xC0)) flHeading(h, (keys & 0x40) && !h);
            flBirdLook(core, 1);
        }
        if (!flStepDone(core, obj)) break;
        flClearStep(core, obj);
        if (flLandWanted) {
            flRouteLen = -1;
            int found = flFindLanding(core, obj);
            if (found < 0) break;
            flLandWanted = 0;
            if (!found) {
                flPlaySe(core, flCfg[PK_FLIGHT_SE_FAILURE]);
                break;
            }
            flState = PK_FLIGHT_LANDING;
            flPhase = 0;
            break;
        }
        if (flRouteLen >= 0) {
            if (flRouteDone >= flRouteLen) {
                flRouteLen = -1;
                flLandWanted = 1;
                break;
            }
            int dir = flRoute[flRouteDone];
            int x = (int16_t) core->busRead16(core, obj + FL_OBJ_X), y = (int16_t) core->busRead16(core, obj + FL_OBJ_Y);
            flStepTo(dir, &x, &y);
            if (!flPassable(core, x, y)) {   // the route doesn't fit this map: come down here rather than hang in the air
                flRouteLen = -1;
                flLandWanted = 1;
                break;
            }
            double straight = flRouteStraightness();
            flRouteDone++;
            flStartStep(core, obj, dir, 1, straight);
            flRouteHeading();
            flBirdLook(core, 1);
            break;
        }
        {
            int dir = flPadDirection(keys);
            if (!dir) break;
            flStepParity++;
            int x = (int16_t) core->busRead16(core, obj + FL_OBJ_X), y = (int16_t) core->busRead16(core, obj + FL_OBJ_Y);
            flStepTo(dir, &x, &y);
            int horizontal = dir == FL_DIR_WEST || dir == FL_DIR_EAST;
            if (!flPassable(core, x, y)) {
                // The world's edge: the bird turns (above), the player stays - or, on a diagonal, flies on the other way.
                flFacing = dir;
                if (horizontal) { flLastHorizontal = dir; flBlockedH = 1; } else flBlockedV = 1;
                break;
            }
            if (horizontal) flBlockedH = 0; else flBlockedV = 0;
            // A held: faster. Two ways held: a diagonal, its steps quicker to keep the speed along it.
            int diagonal = (keys & 0x30) && (keys & 0xC0) && !flBlockedH && !flBlockedV;
            flStartStep(core, obj, dir, (keys & 0x1) != 0, diagonal ? 0.70710678 : 1.0);
        }
        break;
    case PK_FLIGHT_LANDING:
        flBirdOverPlayer(core);
        if (!flStepDone(core, obj)) break;
        flClearStep(core, obj);
        {
            int x = (int16_t) core->busRead16(core, obj + FL_OBJ_X), y = (int16_t) core->busRead16(core, obj + FL_OBJ_Y);
            if (x != flLandX || y != flLandY) {
                int dir = x < flLandX ? FL_DIR_EAST : x > flLandX ? FL_DIR_WEST : y < flLandY ? FL_DIR_SOUTH : FL_DIR_NORTH;
                flStartStep(core, obj, dir, 0, 1.0);
                flHeading(dir == FL_DIR_EAST || dir == FL_DIR_WEST ? dir : 0, dir == FL_DIR_NORTH);
                flBirdLook(core, 1);
                break;
            }
        }
        if (flLift > 0) {
            flLift -= 1;
            flBirdOverPlayer(core);
                break;
        }
        {
            // Down: the player back, standing on the tile at its height, and the field theirs again; the bird leaves.
            uint32_t r = 0;
            if (!flCall(core, flCfg[PK_FLIGHT_ELEVATION_AT], (uint32_t) flLandX, (uint32_t) flLandY, 0, 0, &r)) break;
            uint8_t e = (uint8_t) (r & 0x0F);
            uint8_t old = core->busRead8(core, obj + FL_OBJ_ELEVATION);
            core->busWrite8(core, obj + FL_OBJ_ELEVATION, (uint8_t) (e ? (e | (e << 4)) : ((old & 0xF0) | 0)));
            flHidePlayer(core, obj, 0);
            flMarkPlayer(core, obj, 0);
            flBirdLook(core, 0);
            flPlaySe(core, flCfg[PK_FLIGHT_SE_FLY]);
            flCall(core, flCfg[PK_FLIGHT_UNLOCK_CONTROLS], 0, 0, 0, 0, NULL);
            flState = PK_FLIGHT_DISMOUNT;
            flPhase = 0;
        }
        break;
    case PK_FLIGHT_DISMOUNT:
        flPhase++;
        if (flPhase < FL_LEAVE_FRAMES) {
            int k = flPhase * flPhase;
            flBirdAt(core, flAnchorX - 140 * k / (FL_LEAVE_FRAMES * FL_LEAVE_FRAMES),
                   flAnchorY + 8 - 110 * k / (FL_LEAVE_FRAMES * FL_LEAVE_FRAMES));
            break;
        }
        if (flBird >= 0 && !flCall(core, flCfg[PK_FLIGHT_DESTROY_SPRITE], flSpriteAddr(flBird), 0, 0, 0, NULL)) break;
        pk_flight_reset();
        break;
    }
    // The velocity the flight means (EXPAND's camera moves at it): the route's way over its next steps at the straight
    // speed; the D-pad's (a diagonal at the same speed along it); a landing's step; nothing while it stands.
    flVx = flVy = 0;
    if (!(keys & 0x30)) flBlockedH = 0;
    if (!(keys & 0xC0)) flBlockedV = 0;
    if (flState == PK_FLIGHT_FLYING && flRouteLen >= 0) {
        int dx = 0, dy = 0;
        for (int i = flRouteDone > 0 ? flRouteDone - 1 : 0, n = 0; i < flRouteLen && n < 8; i++, n++) {
            dx += flRoute[i] == FL_DIR_EAST ? 1 : flRoute[i] == FL_DIR_WEST ? -1 : 0;
            dy += flRoute[i] == FL_DIR_SOUTH ? 1 : flRoute[i] == FL_DIR_NORTH ? -1 : 0;
        }
        double len = sqrt((double) (dx * dx + dy * dy));
        if (len > 0 && !flStepDone(core, obj)) { flVx = 4.0 * dx / len; flVy = 4.0 * dy / len; }
    } else if (flState == PK_FLIGHT_FLYING && !flStepDone(core, obj)) {
        double sp = (keys & 0x1) ? 4.0 : 2.0;
        int hx = flBlockedH ? 0 : (keys & 0x10) ? 1 : (keys & 0x20) ? -1 : 0;
        int vy = flBlockedV ? 0 : (keys & 0x80) ? 1 : (keys & 0x40) ? -1 : 0;
        if (!hx && !vy) {   // keys let go mid-step: the step's own way
            hx = flStepDir == FL_DIR_EAST ? 1 : flStepDir == FL_DIR_WEST ? -1 : 0;
            vy = flStepDir == FL_DIR_SOUTH ? 1 : flStepDir == FL_DIR_NORTH ? -1 : 0;
        }
        double k = hx && vy ? 0.70710678 : 1.0;
        flVx = sp * k * hx;
        flVy = sp * k * vy;
    } else if (flState == PK_FLIGHT_LANDING && !flStepDone(core, obj)) {
        flVx = flStepDir == FL_DIR_EAST ? 2.0 : flStepDir == FL_DIR_WEST ? -2.0 : 0;
        flVy = flStepDir == FL_DIR_SOUTH ? 2.0 : flStepDir == FL_DIR_NORTH ? -2.0 : 0;
    }
    // The rider is up (the bird alone is coming in, or leaving: no rider, no shadow of one).
    flShadowOn = flState != PK_FLIGHT_IDLE && flState != PK_FLIGHT_DISMOUNT && flLift > 0;
    flTellWide(core);
}

/** The shadow, into the finished frame: a soft oval in three bands, each darkening what's under it (ground, water, the
 * tree tops: it falls on whatever is below), larger and fainter the higher the bird. Its place follows EXPAND's smooth
 * camera (pk_wide_flight_offset: the frame's own offset), as the bird does. */
// What the last pk_flight_draw changed: where, the pixels before, and what it wrote. mGBA redraws a line only when
// something on it changed, so a still line keeps last frame's shadow: put back first, or it darkens on itself frame
// after frame (on the RP6: 99, 69, 47, 21, 12, then a line that changed - the blinking).
#define FL_SHADE_MAX 48
static uint32_t* flShadeFrame;
static int flShadeX0, flShadeY0, flShadeH, flShadeStride;
static uint32_t flShadeOld[FL_SHADE_MAX][FL_SHADE_MAX], flShadeNew[FL_SHADE_MAX][FL_SHADE_MAX];

static void flShadeUndo(void) {
    if (!flShadeFrame || !flShadeW) return;
    // Line by line (mGBA redraws a line only when something on it changed): a line still as written wasn't drawn again
    // since, so it gets its pixels back; any other was, and is the game's new picture already.
    for (int y = 0; y < flShadeH; y++) {
        uint32_t* row = flShadeFrame + (flShadeY0 + y) * flShadeStride + flShadeX0;
        if (!memcmp(row, flShadeNew[y], (size_t) flShadeW * sizeof(uint32_t)))
            memcpy(row, flShadeOld[y], (size_t) flShadeW * sizeof(uint32_t));
    }
    flShadeW = 0;
}

void pk_flight_draw(uint32_t* frame, int w, int h, int stride) {
    if (!frame) return;
    if (frame == flShadeFrame) flShadeUndo();
    flShadeFrame = frame;
    flShadeW = 0;
    if (!flShadowOn) return;
    int dx = 0, dy = 0;
    pk_wide_flight_offset(&dx, &dy);
    int lift = flLift < 0 ? 0 : flLift > FL_LIFT ? FL_LIFT : flLift;
    int cx = flAnchorX + dx, cy = flAnchorY + FL_SHADOW_Y + FL_SHADOW_DROP * lift / FL_LIFT + dy;
    // At FL_LIFT: 34 x 11 px, darkest 30%; touching down: 24 x 7, darkest 42%.
    double rx = 12.0 + 5.0 * lift / FL_LIFT, ry = 3.5 + 2.0 * lift / FL_LIFT;
    double dark = 0.42 - 0.12 * lift / FL_LIFT;
    int x0 = (int) (cx - rx - 1), x1 = (int) (cx + rx + 1), y0 = (int) (cy - ry - 1), y1 = (int) (cy + ry + 1);
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x1 >= w) x1 = w - 1;
    if (y1 >= h) y1 = h - 1;
    if (x1 < x0 || y1 < y0 || x1 - x0 + 1 > FL_SHADE_MAX || y1 - y0 + 1 > FL_SHADE_MAX) return;
    flShadeX0 = x0;
    flShadeY0 = y0;
    flShadeStride = stride;
    flShadeH = y1 - y0 + 1;
    int bw = x1 - x0 + 1;
    for (int y = y0; y <= y1; y++) {
        double ny = (y + 0.5 - cy) / ry;
        for (int x = x0; x <= x1; x++) {
            uint32_t c = frame[y * stride + x];
            flShadeOld[y - y0][x - x0] = c;
            double nx = (x + 0.5 - cx) / rx;
            double d = nx * nx + ny * ny;
            if (d < 1.0) {
                double a = d < 0.4 ? dark : d < 0.75 ? dark * 0.65 : dark * 0.35;
                uint32_t keep = (uint32_t) ((1.0 - a) * 256.0);
                uint32_t r0 = ((c & 0xFF) * keep) >> 8, g0 = (((c >> 8) & 0xFF) * keep) >> 8, b0 = (((c >> 16) & 0xFF) * keep) >> 8;
                c = (c & 0xFF000000u) | (b0 << 16) | (g0 << 8) | r0;
                frame[y * stride + x] = c;
            }
            flShadeNew[y - y0][x - x0] = c;
        }
    }
    flShadeW = bw;
}

void pk_flight_info(struct mCore* core, char* out, int n) {
    if (!flConfigured || !core) {
        snprintf(out, (size_t) n, "flight: off");
        return;
    }
    uint32_t obj = flObjAddr(core);
    int x = (int16_t) core->busRead16(core, obj + FL_OBJ_X), y = (int16_t) core->busRead16(core, obj + FL_OBJ_Y);
    int sx = 0, sy = 0;
    flPlayerOnScreen(core, obj, &sx, &sy);
    int wx = 0, wy = 0, vx = 0, vy = 0;
    pk_wide_flight_offset(&wx, &wy);
    pk_wide_flight_camera(&vx, &vy);
    snprintf(out, (size_t) n, "flight: state %d phase %d bird %d shadow %d lift %d look %d at %d,%d screen %d,%d anchor %d,%d marked %d "
             "land %d,%d route %d/%d locked %d wide %d,%d view %d,%d", flState, flPhase, flBird, flShadowOn, flLift, flLook, x, y, sx, sy, flAnchorX, flAnchorY,
             flMarked(core, obj), flLandX, flLandY, flRouteDone, flRouteLen, core->busRead8(core, flCfg[PK_FLIGHT_LOCK_BYTE]), wx, wy, vx, vy);
}
