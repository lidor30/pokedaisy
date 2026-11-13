// FREE FLIGHT: see pk_flight.h. GPL-3.0 (see NOTICE).
#include "pk_flight.h"
#include "pk_flight_vehicles.h"
#include "pk_wide.h"

#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>

#include <mgba/core/core.h>


// struct ObjectEvent (include/global.fieldmap.h): 0x24 bytes. The bit fields' bytes.
#define FL_OBJ_SIZE 0x24u
#define FL_OBJ_FLAGS0 0x00u        // bit 6 heldMovementActive, bit 7 heldMovementFinished
#define FL_OBJ_FLAGS1 0x01u        // bit 5 invisible
#define FL_OBJ_FLAGS2 0x02u        // bit 0 isPlayer
#define FL_OBJ_SPRITE_ID (flCfg[PK_FLIGHT_OBJ_SPRITE_ID] ? flCfg[PK_FLIGHT_OBJ_SPRITE_ID] : 0x04u)   // spriteId (pokeemerald-expansion: +0x23)
#define FL_OBJ_LOCAL_ID 0x08u
#define FL_OBJ_ELEVATION 0x0Bu     // currentElevation (low nibble), previousElevation (high)
#define FL_OBJ_X 0x10u             // currentCoords (s16 x, y), map grid coordinates (+ FL_MAP_OFFSET)
#define FL_OBJ_Y 0x12u
#define FL_OBJ_EFFECT_SPRITE 0x1Au // fieldEffectSpriteId: the bird's sprite while flying (the surf blob's slot - never both)
#define FL_OBJ_ACTION 0x1Cu        // movementActionId
#define FL_HELD_ACTIVE 0x40u
#define FL_HELD_FINISHED 0x80u
#define FL_NO_COVERING 0x10u     // disableCoveringGroundEffects
#define FL_INVISIBLE 0x20u
#define FL_INANIMATE 0x10u      // ObjectEvent.inanimate: its movements start no step animation
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
#define FL_TURN_SHARE 0.5         // a bank of half the heading's angle: 22 degrees on a diagonal (pixel art turned 45 degrees
                                  // came out ragged, the rider's face too) ...
#define FL_TURN_MAX 0.6108652     // ... and 35 at most (straight up or down from the side)
#define FL_SPR_DATA 0x2Eu
#define FL_SPR_RIDER (FL_SPR_DATA + 12u)   // the bird's data[6]: the rider's sprite id + 1 (0 = none), so a state brings it back
#define FL_SPR_FLAGS 0x3Eu         // bit 0 inUse, bit 1 coordOffsetEnabled, bit 2 invisible
#define FL_SPR_FLAGS2 0x3Fu        // bit 0 hFlip
#define FL_MAX_SPRITES 64u

#define FL_MAP_OFFSET 7
#define FL_MAPGRID_UNDEFINED (flCfg[PK_FLIGHT_MAPGRID_UNDEFINED] ? flCfg[PK_FLIGHT_MAPGRID_UNDEFINED] : 0x3FFu)
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
#define FL_SHADOW_DROP 24   // ... and that much lower again at full height: the higher the bird, the further below it
#define FL_LAND_RADIUS 24

static uint32_t flCfg[PK_FLIGHT_WORDS];
static int flConfigured;
static int flState = PK_FLIGHT_IDLE;
static int flPhase;               // frames into the current part
static int flBird = -1;           // the bird's sprite id
static int flRider = -1;          // Emerald: the rider's sprite id (PK_FLIGHT_RIDER_TEMPLATE), kept in the bird's data[6] too
static double flAngle;            // how far it's turned now, radians, clockwise on screen (eased toward the heading)
static double flAngleTarget;
static int flAngleSx = 1;         // the mirror the angle was eased under (1 the picture's own west, -1 flipped east)
static int flStill;               // frames with no velocity: back to level after a few
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

static void flCloudsStep(struct mCore* core);
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

/** Whether VMap tile (x, y), past the current map's [dir] edge, belongs to a connected map on the no-fly list (or one
 * FLIGHT AREA leaves out: pk_flight_set_area): the
 * map's connections (gMapHeader +0xC: {u8 dir, s32 offset, u8 group, u8 num}, 12 bytes) in that direction, each covering
 * its own width (or height) from its offset along the edge. */
static uint8_t flArea[PK_FLIGHT_AREA_BYTES];   // FLIGHT AREA: a bit per map (group << 8 | num) the flight may cross into
static int flAreaOn;
static int flAreaStop;      // the last flPassable said no because of FLIGHT AREA
static int flAreaTurned;    // the D-pad met FLIGHT AREA's edge since pk_flight_area_turned last asked

void pk_flight_set_area(const uint8_t* bits, int n) {
    if (!bits || n <= 0) {
        flAreaOn = 0;
        return;
    }
    memset(flArea, 0, sizeof flArea);
    memcpy(flArea, bits, (size_t) (n < PK_FLIGHT_AREA_BYTES ? n : PK_FLIGHT_AREA_BYTES));
    flAreaOn = 1;
}

int pk_flight_area_turned(void) {
    int t = flAreaTurned;
    flAreaTurned = 0;
    return t;
}

/** Map [id] (group << 8 | num) is outside FLIGHT AREA's maps. */
static int flOutsideArea(uint32_t id) {
    return flAreaOn && !((flArea[(id >> 3) & (PK_FLIGHT_AREA_BYTES - 1)] >> (id & 7)) & 1);
}

static int flNoFlyAt(struct mCore* core, int x, int y, int xs, int ys, int dir) {
    uint32_t a = flCfg[PK_FLIGHT_NO_FLY], b = flCfg[PK_FLIGHT_NO_FLY + 1];
    uint32_t ids[4] = { a & 0xFFFF, a >> 16, b & 0xFFFF, b >> 16 };
    if (!flCfg[PK_FLIGHT_MAP_HEADER] || !flCfg[PK_FLIGHT_MAP_GROUPS] ||
        (!flAreaOn && ids[0] == 0xFFFF && ids[2] == 0xFFFF && ids[1] == 0xFFFF && ids[3] == 0xFFFF))
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
        int listed = id == ids[0] || id == ids[1] || id == ids[2] || id == ids[3];
        if (!listed && !flOutsideArea(id)) continue;
        int off = (int) core->busRead32(core, e + 4);
        uint32_t grp = (core->busRead32(core, flCfg[PK_FLIGHT_MAP_GROUPS] + 4 * g) ^ flCfg[PK_FLIGHT_MAP_GROUPS_XOR]);
        uint32_t hdr = grp ? core->busRead32(core, grp + 4 * m) : 0;
        uint32_t lay = hdr ? core->busRead32(core, hdr) : 0;
        if (!lay) { flAreaStop = !listed; return 1; }   // can't tell its span: keep out
        int tw = (int) core->busRead32(core, lay), th = (int) core->busRead32(core, lay + 4);
        int along = dir == FL_DIR_SOUTH || dir == FL_DIR_NORTH ? lx : ly, span = dir == FL_DIR_SOUTH || dir == FL_DIR_NORTH ? tw : th;
        if (along >= off && along < off + span) { flAreaStop = !listed; return 1; }
    }
    return 0;
}

/** The map grid around a crossing, made what the game would load (FL_MEND_FRAMES after a new map, while flying).
 * Crossing into a map, the game copies the screen-sized area around the camera from the old map's grid into the new
 * one (MoveMapViewToBackup), so near the crossing the new grid holds what the old one had there: its emptiness
 * (undefined squares), a third map it was joined to, its own border. Walking never reaches those corners; a diagonal
 * flight does - the bird stopped above Cinnabar with Route 21 right there, Emerald's route stopped at Route 116's edge
 * after Route 115, and EXPAND's margins showed the strays. So the new map's own undefined squares get theirs back from
 * its layout, and its padding is what the connections fill (InitBackupMapLayoutConnections, in their order), undefined
 * where none reaches. Then the whole view is drawn again (DrawWholeMapView): what's on screen was drawn from the old
 * map's metatiles, and where the two maps' tilesets differ it was garbled (Emerald, Route 117 into Verdanturf) and
 * EXPAND's tilemap match failed (the zoom pulled in) until it scrolled away. Repeated for FL_MEND_FRAMES: a frame can
 * end part-way through the crossing (Emerald's LoadMapFromCameraTransition runs past VBlank), the new layout already in
 * gMapHeader and the old view copied in on the next frame; the redraw waits for a frame the game is at rest. */
#define FL_MEND_FRAMES 30
#define FL_VMAP_MAX 16384
static uint32_t flMendedLayout;
static int flMendPending;
static int flRedrawWanted;   // DrawWholeMapView once the crossing has settled
#define FL_DRAW_MAX 256
static int flMendAt[FL_DRAW_MAX][2], flMendN;   // squares to draw again (CurrentMapDrawMetatileAt), still to be drawn
static int flDrawnAt[FL_DRAW_MAX][2], flDrawnN; // ... and every one queued since the map changed (each drawn once)
static uint8_t flCovered[FL_VMAP_MAX];

/** Queues square (x, y) of VMap to be drawn again, once per map. */
static void flQueueDraw(int x, int y) {
    for (int i = 0; i < flDrawnN; i++) if (flDrawnAt[i][0] == x && flDrawnAt[i][1] == y) return;
    if (flDrawnN >= FL_DRAW_MAX || flMendN >= FL_DRAW_MAX) return;
    flDrawnAt[flDrawnN][0] = x; flDrawnAt[flDrawnN][1] = y; flDrawnN++;
    flMendAt[flMendN][0] = x; flMendAt[flMendN][1] = y; flMendN++;
}

static uint32_t flLayoutOf(struct mCore* core, uint32_t g, uint32_t m) {
    uint32_t grp = (core->busRead32(core, flCfg[PK_FLIGHT_MAP_GROUPS] + 4 * g) ^ flCfg[PK_FLIGHT_MAP_GROUPS_XOR]);
    uint32_t hdr = grp >= 0x08000000u && grp < 0x0A000000u ? core->busRead32(core, grp + 4 * m) : 0;
    uint32_t lay = hdr >= 0x08000000u && hdr < 0x0A000000u ? core->busRead32(core, hdr) : 0;
    return lay >= 0x08000000u && lay < 0x0A000000u ? lay : 0;
}

/** Writes VMap square (x, y) when it differs, and queues it to be drawn. */
static void flSetSquare(struct mCore* core, uint32_t map, int xs, int x, int y, uint16_t v) {
    uint32_t at = map + 2u * (uint32_t) (x + xs * y);
    if (core->busRead16(core, at) == v) return;
    core->busWrite16(core, at, v);
    flQueueDraw(x, y);
}

static void flMendSquares(struct mCore* core, uint32_t map, int xs, int ys, uint32_t layout, uint32_t hdr) {
    int mw = xs - 15, mh = ys - 14;
    // The map's own undefined squares (the copy reaches into it too: Route 20's west edge after Cinnabar), from its layout.
    uint32_t own = core->busRead32(core, layout + 0xC);
    if (own >= 0x08000000u && own < 0x0A000000u && (int) core->busRead32(core, layout) == mw && (int) core->busRead32(core, layout + 4) == mh) {
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                uint32_t at = map + 2u * (uint32_t) (x + FL_MAP_OFFSET + xs * (y + FL_MAP_OFFSET));
                if ((core->busRead16(core, at) & 0xFFFF) != FL_MAPGRID_UNDEFINED) continue;
                uint16_t v = core->busRead16(core, own + 2u * (uint32_t) (x + mw * y));
                if ((v & FL_MAPGRID_UNDEFINED) == FL_MAPGRID_UNDEFINED) continue;
                flSetSquare(core, map, xs, x + FL_MAP_OFFSET, y + FL_MAP_OFFSET, v);
            }
        }
    }
    // The padding: each connection's squares in the game's order, undefined where none reaches.
    if (xs * ys > FL_VMAP_MAX) return;
    memset(flCovered, 0, (size_t) (xs * ys));
    uint32_t conns = core->busRead32(core, hdr + 0xC);
    int n = 0;
    uint32_t list = 0;
    if (conns >= 0x08000000u && conns < 0x0A000000u) {
        n = (int) core->busRead32(core, conns);
        list = core->busRead32(core, conns + 4);
        if (n <= 0 || n > 16 || list < 0x08000000u || list >= 0x0A000000u) n = 0;
    }
    uint32_t a = flCfg[PK_FLIGHT_NO_FLY], b = flCfg[PK_FLIGHT_NO_FLY + 1];
    for (int i = 0; i < n; i++) {
        uint32_t e = list + 12u * (uint32_t) i;
        int dir = core->busRead8(core, e), off = (int) core->busRead32(core, e + 4);
        uint32_t g = core->busRead8(core, e + 8), m = core->busRead8(core, e + 9), id = g << 8 | m;
        uint32_t lay = flLayoutOf(core, g, m);
        if (!lay) continue;
        int cw = (int) core->busRead32(core, lay), ch = (int) core->busRead32(core, lay + 4);
        uint32_t data = core->busRead32(core, lay + 0xC);
        if (cw <= 0 || ch <= 0 || data < 0x08000000u || data >= 0x0A000000u) continue;
        int noFly = id == (a & 0xFFFF) || id == (a >> 16) || id == (b & 0xFFFF) || id == (b >> 16);
        // The padding this connection fills, in VMap squares, and the connected map's square for each.
        int x0, x1, y0, y1;
        if (dir == FL_DIR_SOUTH || dir == FL_DIR_NORTH) {
            x0 = FL_MAP_OFFSET + off; x1 = x0 + cw;
            y0 = dir == FL_DIR_NORTH ? 0 : FL_MAP_OFFSET + mh; y1 = dir == FL_DIR_NORTH ? FL_MAP_OFFSET : ys;
        } else if (dir == FL_DIR_WEST || dir == FL_DIR_EAST) {
            y0 = FL_MAP_OFFSET + off; y1 = y0 + ch;
            x0 = dir == FL_DIR_WEST ? 0 : FL_MAP_OFFSET + mw; x1 = dir == FL_DIR_WEST ? FL_MAP_OFFSET : xs;
        } else {
            continue;   // dive / emerge: not in the grid
        }
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x1 > xs) x1 = xs;
        if (y1 > ys) y1 = ys;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int sx, sy;
                if (dir == FL_DIR_NORTH) { sx = x - FL_MAP_OFFSET - off; sy = ch - FL_MAP_OFFSET + y; }
                else if (dir == FL_DIR_SOUTH) { sx = x - FL_MAP_OFFSET - off; sy = y - FL_MAP_OFFSET - mh; }
                else if (dir == FL_DIR_WEST) { sx = cw - FL_MAP_OFFSET + x; sy = y - FL_MAP_OFFSET - off; }
                else { sx = x - FL_MAP_OFFSET - mw; sy = y - FL_MAP_OFFSET - off; }
                if (sx < 0 || sx >= cw || sy < 0 || sy >= ch) continue;
                flCovered[x + xs * y] = 1;
                // A no-fly stand-in keeps what the game put there (its scrambled squares are its own business).
                if (noFly) continue;
                flSetSquare(core, map, xs, x, y, core->busRead16(core, data + 2u * (uint32_t) (sx + cw * sy)));
            }
        }
    }
    for (int y = 0; y < ys; y++) {
        for (int x = 0; x < xs; x++) {
            int inside = x >= FL_MAP_OFFSET && x < FL_MAP_OFFSET + mw && y >= FL_MAP_OFFSET && y < FL_MAP_OFFSET + mh;
            if (!inside && !flCovered[x + xs * y]) flSetSquare(core, map, xs, x, y, FL_MAPGRID_UNDEFINED);
        }
    }
}

static void flMend(struct mCore* core) {
    uint32_t vmap = flCfg[PK_FLIGHT_VMAP], hdr = flCfg[PK_FLIGHT_MAP_HEADER];
    if (!hdr || !flCfg[PK_FLIGHT_MAP_GROUPS]) return;
    int xs = (int) core->busRead32(core, vmap), ys = (int) core->busRead32(core, vmap + 4);
    uint32_t map = core->busRead32(core, vmap + 8);
    uint32_t layout = core->busRead32(core, hdr);
    if (xs <= 15 || ys <= 14 || !map || !layout) return;
    if (layout != flMendedLayout) {
        // The first map seen too: the player may have walked across a connection before taking off.
        flMendPending = FL_MEND_FRAMES;
        flRedrawWanted = 1;
        flMendedLayout = layout;
        flDrawnN = 0;
    }
    if (flMendPending <= 0) return;
    flMendPending--;
    flMendSquares(core, map, xs, ys, layout, hdr);
}

/** The view drawn again once the crossing has settled (DrawWholeMapView), else the squares mended since, one by one
 * (CurrentMapDrawMetatileAt skips one out of view); what can't be called this frame waits. */
static void flMendDraw(struct mCore* core) {
    if (flRedrawWanted && flCfg[PK_FLIGHT_DRAW_VIEW]) {
        if (!flCall(core, flCfg[PK_FLIGHT_DRAW_VIEW], 0, 0, 0, 0, NULL)) return;
        flRedrawWanted = 0;
        flMendN = 0;
        return;
    }
    flRedrawWanted = 0;
    while (flMendN > 0 && flCfg[PK_FLIGHT_DRAW_METATILE]) {
        if (!flCall(core, flCfg[PK_FLIGHT_DRAW_METATILE], (uint32_t) flMendAt[flMendN - 1][0], (uint32_t) flMendAt[flMendN - 1][1], 0, 0, NULL)) return;
        flMendN--;
    }
    flMendN = 0;
}

/** GetMapBorderIdAt != CONNECTION_INVALID, read from RAM (fieldmap.c): inside the map, or past an edge that has a
 * connection. The same answer CanCameraMoveInDirection gives the player's next tile. */
static int flPassable(struct mCore* core, int x, int y) {
    flAreaStop = 0;
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
    // disableCoveringGroundEffects too: Ruby / Sapphire run a step's ground effects for every object (FireRed and Emerald
    // skip the camera's localId), so tall grass rustled and sand piled up under the bird; the step's start filters them.
    core->busWrite8(core, obj + FL_OBJ_FLAGS0, (uint8_t) ((f0 & ~FL_HELD_FINISHED) | FL_HELD_ACTIVE | FL_NO_COVERING));
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

/** Emerald: the bird carries no picture of the rider, so the rider is a sprite of its own - the player's surfing
 * graphics (by gender) in the get-on pose, as the game's FLY seats them: CopyObjectGraphicsInfoToSpriteTemplate into
 * the scratch, CreateSprite from it (the player's palette is loaded on the field; its frames get tiles of their own),
 * then the pose. Over the bird's centre by 8 px, in front of it (subpriority 0, the bird's 1): the game's cut-in hides all
 * but the hat behind the bird, which on a long flight read as no rider at all. 0 when the
 * game's code couldn't be called this frame (try again), 1 done (or not needed). */
static int flRiderMode(void) {
    return flCfg[PK_FLIGHT_RIDER_TEMPLATE] != 0 || flCfg[PK_FLIGHT_RIDER_ANIM] != 0;
}

/** A FireRed-engine bird drawn in a palette of its own (Unbound's, tag 0x11A2) carries no rider: the game's pictures
 * of it with Red / Leaf are the bird alone. The player's palette (0x1100), the expansion's by gender (0xFFFF) and the
 * loaders called first (bit 16) are the player's colours: their birds have the rider. Flown like Emerald's (its one side
 * picture, flipped going east), the player seated on its back by flSeatOnBird. */
static int flBirdNoRider(void) {
    uint32_t tag = flCfg[PK_FLIGHT_BIRD_PALETTE_TAG];
    return !flRiderMode() && flCfg[PK_FLIGHT_BIRD_PALETTE] && tag && tag != 0x1100u && tag != 0xFFFFu && !(tag & 0x10000u);
}

/** A game whose bird has no picture (Ghost Grey blanked its graphics): flCapture flies the player in the air balloon. */
static int flBirdBlank;

/** MAKEOVER > FLY ON's vehicle (pk_flight_vehicles.h), the balloon where the game's bird is blank, or NULL for the bird. */
static const struct pk_flight_vehicle* flVehicle(void) {
    uint32_t v = flCfg[PK_FLIGHT_VEHICLE];
    if (v >= 1 && v <= PK_FLIGHT_VEHICLE_COUNT) return &pk_flight_vehicles[v - 1];
    return flBirdBlank ? &pk_flight_vehicles[0] : NULL;
}

static int flMakeRider(struct mCore* core) {
    if (!flCfg[PK_FLIGHT_RIDER_TEMPLATE] || flRider >= 0 || flBird < 0) return 1;   // none: flSeatOnBird draws the player
    uint32_t scratch = flCfg[PK_FLIGHT_SCRATCH], gfx = 0, id = FL_MAX_SPRITES;
    if (!scratch) return 1;
    if (!flCall(core, flCfg[PK_FLIGHT_RIDER_GFX], 3, 0, 0, 0, &gfx)) return 0;   // PLAYER_AVATAR_STATE_SURFING
    if (!flCall(core, flCfg[PK_FLIGHT_RIDER_TEMPLATE], gfx & 0xFFFF, flCfg[PK_FLIGHT_SPRITE_DUMMY], scratch, scratch + 0x18, NULL)) return 0;
    uint32_t bird = flSpriteAddr(flBird);
    int bx = (int16_t) core->busRead16(core, bird + FL_SPR_X), by = (int16_t) core->busRead16(core, bird + FL_SPR_Y);
    if (!flCall(core, flCfg[PK_FLIGHT_CREATE_SPRITE], scratch, (uint32_t) bx & 0xFFFF, (uint32_t) (by - 8) & 0xFFFF, 0, &id)) return 0;
    if (id >= FL_MAX_SPRITES) return 1;   // no sprite free: the bird flies alone
    uint32_t spr = flSpriteAddr((int) id);
    // The player's own palette slot: Emerald loads the player's palette by slot, not by tag, so the template's tag finds
    // no slot (CreateSprite then picks 15: a white and green rider).
    uint32_t obj = flObjAddr(core);
    uint16_t pal = core->busRead16(core, flSpriteAddr(core->busRead8(core, obj + FL_OBJ_SPRITE_ID)) + FL_SPR_ATTR2) & 0xF000u;
    uint16_t a2 = core->busRead16(core, spr + FL_SPR_ATTR2);
    core->busWrite16(core, spr + FL_SPR_ATTR2, (uint16_t) ((a2 & ~0xFC00u) | (1u << 10) | pal));   // the bird's priority
    core->busWrite8(core, spr + FL_SPR_FLAGS, (uint8_t) (core->busRead8(core, spr + FL_SPR_FLAGS) & ~2u));  // screen coordinates
    flRider = (int) id;
    core->busWrite16(core, bird + FL_SPR_RIDER, (uint16_t) (id + 1));
    return 1;
}

/** The rider off the bird (it lands, or the state had none). 0 when DestroySprite couldn't be called this frame. */
static int flDropRider(struct mCore* core) {
    if (flRider < 0) return 1;
    if (!flCall(core, flCfg[PK_FLIGHT_DESTROY_SPRITE], flSpriteAddr(flRider), 0, 0, 0, NULL)) return 0;
    if (flBird >= 0) core->busWrite16(core, flSpriteAddr(flBird) + FL_SPR_RIDER, 0);
    flRider = -1;
    return 1;
}

/** Sets [spr]'s anim and flip, only what changed (StartSpriteAnim restarts the frame copy). 0 when the game's code
 * couldn't be called this frame. */
static int flSpriteLook(struct mCore* core, uint32_t spr, int anim, int flip) {
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

/** The bird turned the way it flies: its nose (west in the picture, east flipped, north from behind) along the
 * velocity the flight means, banked by FL_TURN_SHARE of the angle (at most FL_TURN_MAX), eased; level again once it
 * stands a few frames. Drawn by pk_flight_draw (flCapture's picture, turned the pixel-art way), not the GBA's affine
 * sprites: their nearest-pixel turn broke the outlines and the rider's face up (the RP6, 2026-11-10). */
static int flSx = 1;              // the mirror: 1 the picture's own west, -1 flipped east

static int flRidden(void) {
    return flState == PK_FLIGHT_FLYING || flState == PK_FLIGHT_LANDING || (flState == PK_FLIGHT_TAKING_OFF && flPhase > FL_SWOOP_FRAMES);
}

static void flTurn(void) {
    const struct pk_flight_vehicle* veh = flVehicle();
    int side = flLook;
    if (side == FL_DIR_NORTH && (flRiderMode() || flBirdNoRider() || veh))   // a bird with one picture, the vehicles: no back view
        side = flLastHorizontal == FL_DIR_EAST ? FL_DIR_EAST : FL_DIR_WEST;
    double share = veh ? veh->turnShare : FL_TURN_SHARE, most = veh ? veh->turnMax : FL_TURN_MAX;
    int sx = side == FL_DIR_EAST ? -1 : 1;
    double nx = side == FL_DIR_NORTH ? 0 : side == FL_DIR_EAST ? 1 : -1, ny = side == FL_DIR_NORTH ? -1 : 0;
    if (sx != flAngleSx) { flAngle = -flAngle; flAngleTarget = -flAngleTarget; flAngleSx = sx; }   // mirrored: the same tilt
    flSx = sx;
    double v = sqrt(flVx * flVx + flVy * flVy);
    if ((flState == PK_FLIGHT_FLYING || flState == PK_FLIGHT_LANDING) && v > 0.1) {
        double t = atan2(nx * flVy - ny * flVx, nx * flVx + ny * flVy) * share;
        flAngleTarget = t > most ? most : t < -most ? -most : t;
        flStill = 0;
    } else if (!flRidden() || ++flStill > 8) {
        flAngleTarget = 0;
    }
    flAngle += (flAngleTarget - flAngle) * 0.2;
}

/** The bird and its rider as one picture (flSrc: 64 x 64, the bird's centre at 32, 32, the rider 8 px over it, in
 * front), read off the sprites' tiles and palettes in VRAM after each frame - the game keeps animating them (FireRed's
 * wing beats) - while the sprites themselves are hidden; pk_flight_draw draws it turned. Off (the sprites shown
 * again) while the bird is alone: coming in, leaving. Its 8x scale (Scale2x three times) is rebuilt only when the
 * picture changes: rotating that and sampling it is RotSprite's way of turning pixel art. */
#define FL_SRC 64
#define FL_BIG (FL_SRC * 8)
static uint32_t flSrc[FL_SRC * FL_SRC], flSrcHash;
static uint32_t flBig[FL_BIG * FL_BIG], flMid[(FL_SRC * 4) * (FL_SRC * 4)];
// Which of flBig's pixels show (a bit each, its top byte set) and their box, in flBig's pixels (flBigU0 > flBigU1: none).
// pk_flight_draw tests the bit before it reads flBig (1 MB: a cache miss a pixel cost a 3DS ~2 ms a frame) and keeps to
// the box turned onto the screen, so it draws the same pixels.
static uint32_t flBigOpaque[FL_BIG * FL_BIG / 32];
static int flBigU0 = FL_BIG, flBigU1 = -1, flBigV0 = FL_BIG, flBigV1 = -1;
static int flSprOn;               // pk_flight_draw draws the bird (the sprites are hidden)
static int flDrawX, flDrawY;      // where the bird's centre is on screen (flBirdAt's)

static void flScale2x(const uint32_t* src, int w, int h, uint32_t* dst) {
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            uint32_t e = src[y * w + x];
            uint32_t b = y > 0 ? src[(y - 1) * w + x] : 0, hh = y < h - 1 ? src[(y + 1) * w + x] : 0;
            uint32_t d = x > 0 ? src[y * w + x - 1] : 0, f = x < w - 1 ? src[y * w + x + 1] : 0;
            uint32_t* o = dst + (2 * y) * (2 * w) + 2 * x;
            if (b != hh && d != f) {
                o[0] = d == b ? d : e;
                o[1] = b == f ? f : e;
                o[2 * w] = d == hh ? d : e;
                o[2 * w + 1] = hh == f ? f : e;
            } else {
                o[0] = o[1] = o[2 * w] = o[2 * w + 1] = e;
            }
        }
    }
}

/** mGBA's GBA memory blocks (getMemoryBlock ids: gba/memory.h's REGION_PALETTE_RAM, REGION_VRAM). */
#define FL_REGION_PALETTE 5
#define FL_REGION_VRAM 6

/** Sprite [id]'s current picture into flSrc, its centre at (cx, cy); its own pixels over what's there. */
static void flCopySprite(struct mCore* core, int id, int cx, int cy) {
    static const uint8_t kW[3][4] = { { 8, 16, 32, 64 }, { 16, 32, 32, 64 }, { 8, 8, 16, 32 } };
    static const uint8_t kH[3][4] = { { 8, 16, 32, 64 }, { 8, 8, 16, 32 }, { 16, 32, 32, 64 } };
    uint32_t spr = flSpriteAddr(id);
    uint16_t a0 = core->busRead16(core, spr), a1 = core->busRead16(core, spr + FL_SPR_ATTR1), a2 = core->busRead16(core, spr + FL_SPR_ATTR2);
    int shape = a0 >> 14, size = a1 >> 14;
    if (shape > 2 || (a0 & 0x2000u)) return;   // 8bpp: not the birds
    int w = kW[shape][size], h = kH[shape][size];
    uint32_t tiles = 0x06010000u + 32u * (a2 & 0x3FFu), pal = 0x05000200u + 32u * (a2 >> 12);
    // VRAM and palette RAM read in place where the core hands them out (a bus read a byte cost a 3DS ~0.5 ms a frame).
    size_t vramSize = 0, palSize = 0;
    const uint8_t* vram = core->getMemoryBlock ? (const uint8_t*) core->getMemoryBlock(core, FL_REGION_VRAM, &vramSize) : NULL;
    const uint8_t* palRam = core->getMemoryBlock ? (const uint8_t*) core->getMemoryBlock(core, FL_REGION_PALETTE, &palSize) : NULL;
    uint32_t tileOff = tiles - 0x06000000u, palOff = pal - 0x05000000u;
    if (!vram || vramSize < tileOff + (uint32_t) (w * h / 2)) vram = NULL;
    if (!palRam || palSize < palOff + 32u) palRam = NULL;
    uint32_t colors[16];
    for (int i = 1; i < 16; i++) {
        uint16_t c = palRam ? (uint16_t) (palRam[palOff + 2u * (uint32_t) i] | palRam[palOff + 2u * (uint32_t) i + 1] << 8)
                            : core->busRead16(core, pal + 2u * (uint32_t) i);
        uint32_t v = (uint32_t) (c & 0x1F) << 3 | (uint32_t) ((c >> 5) & 0x1F) << 11 | (uint32_t) ((c >> 10) & 0x1F) << 19;
        colors[i] = v | ((v >> 5) & 0x070707) | 0xFF000000u;   // mGBA's mColorFrom555; the top byte marks it opaque
    }
    for (int y = 0; y < h; y++) {
        int dy = cy - h / 2 + y;
        if (dy < 0 || dy >= FL_SRC) continue;
        for (int x = 0; x < w; x++) {
            int dx = cx - w / 2 + x;
            if (dx < 0 || dx >= FL_SRC) continue;
            uint32_t t = (uint32_t) ((y / 8) * (w / 8) + x / 8);
            uint32_t at = t * 32u + (uint32_t) ((y % 8) * 4 + (x % 8) / 2);
            uint8_t b = vram ? vram[tileOff + at] : core->busRead8(core, tiles + at);
            int ix = (x & 1) ? b >> 4 : b & 0xF;
            if (ix) flSrc[dy * FL_SRC + dx] = colors[ix];
        }
    }
}

static void flSetHidden(struct mCore* core, int id, int hidden) {
    if (id < 0) return;
    uint32_t spr = flSpriteAddr(id);
    uint8_t f = core->busRead8(core, spr + FL_SPR_FLAGS);
    core->busWrite8(core, spr + FL_SPR_FLAGS, (uint8_t) (hidden ? f | 4u : f & ~4u));
}

/** The player's overworld sprite as drawn this frame: 16 x 32 (pret's heroes) or 32 x 32 (Unbound's), 4bpp; its width,
 * 0 when it's neither. */
static int flPlayerSprite(struct mCore* core, uint32_t* spr) {
    *spr = flSpriteAddr(core->busRead8(core, flObjAddr(core) + FL_OBJ_SPRITE_ID));
    uint16_t a0 = core->busRead16(core, *spr), a1 = core->busRead16(core, *spr + FL_SPR_ATTR1);
    if (a0 & 0x2000u) return 0;
    if ((a0 >> 14) == 2 && (a1 >> 14) == 2) return 16;   // tall, 16 x 32
    if ((a0 >> 14) == 0 && (a1 >> 14) == 2) return 32;   // square, 32 x 32
    return 0;
}

/** Pixel (x, y) of the player's sprite frame (w wide, 32 tall) as a frame colour, 0 for none. */
static uint32_t flPlayerPixel(struct mCore* core, uint32_t spr, int w, int x, int y) {
    uint16_t a2 = core->busRead16(core, spr + FL_SPR_ATTR2);
    uint32_t tiles = 0x06010000u + 32u * (a2 & 0x3FFu), pal = 0x05000200u + 32u * (a2 >> 12);
    uint32_t t = (uint32_t) ((y / 8) * (w / 8) + x / 8);
    uint8_t b = core->busRead8(core, tiles + t * 32u + (uint32_t) ((y % 8) * 4 + (x % 8) / 2));
    int ix = (x & 1) ? b >> 4 : b & 0xF;
    if (!ix) return 0;
    uint16_t c = core->busRead16(core, pal + 2u * (uint32_t) ix);
    uint32_t col = (uint32_t) (c & 0x1F) << 3 | (uint32_t) ((c >> 5) & 0x1F) << 11 | (uint32_t) ((c >> 10) & 0x1F) << 19;
    return col | ((col >> 5) & 0x070707) | 0xFF000000u;
}

/** The pose the rider is drawn in: the player's own standing frames (ANIM_STD_FACE_SOUTH 0, NORTH 1, WEST 2 - the first
 * anims of every hero's table), not whatever step their hidden sprite was on (the walking frames bobbed the rider up and
 * down in the seat). From the side (west in the picture, mirrored going east); in the balloon, whose basket shows them
 * from every side, from behind going up and from the front going down. [flPoseAnim] -1 = take-off's own frame kept. */
#define FL_POSE_SOUTH 0
#define FL_POSE_NORTH 1
#define FL_POSE_WEST 2
static int flPose = FL_POSE_WEST;
static int flPoseSaved = -1;       // the player's anim before the flight posed them, put back on landing
static int flPoseSavedFlip;

static void flPosePlayer(struct mCore* core, int upDown) {
    if (!upDown) {
        flPose = FL_POSE_WEST;
    } else if (flVx * flVx + flVy * flVy > 0.01) {
        double ax = fabs(flVx), ay = fabs(flVy);
        if (ay > ax * 1.5) flPose = flVy < 0 ? FL_POSE_NORTH : FL_POSE_SOUTH;
        else if (ax > ay * 1.5) flPose = FL_POSE_WEST;
    }
    uint32_t spr;
    if (!flPlayerSprite(core, &spr)) return;
    if (flPoseSaved < 0) {
        flPoseSaved = core->busRead8(core, spr + FL_SPR_ANIM_NUM);
        flPoseSavedFlip = core->busRead8(core, spr + FL_SPR_FLAGS2) & 1;
    }
    flSpriteLook(core, spr, flPose, 0);
}

/** Landed: the player's own frame back (their sprite stands as it did at take-off until their first step). */
static void flUnposePlayer(struct mCore* core) {
    uint32_t spr;
    if (flPoseSaved < 0 || !flPlayerSprite(core, &spr)) { flPoseSaved = -1; return; }
    flSpriteLook(core, spr, flPoseSaved, flPoseSavedFlip);
    flPoseSaved = -1;
}

/** The player in a vehicle's seat: their overworld sprite's current frame (16 x 32, or 32 x 32 centred on the same
 * place; hidden while they fly), over the vehicle's SEAT pixels only, the right way round once pk_flight_draw mirrors the
 * picture. */
static void flSeatPlayer(struct mCore* core, const struct pk_flight_vehicle* v, const uint8_t* px, int ox, int oy) {
    uint32_t spr;
    int w = flPlayerSprite(core, &spr);
    if (!w) return;
    int flip = (core->busRead16(core, spr + FL_SPR_ATTR1) & 0x1000u) != 0;   // posed facing west: pk_flight_draw mirrors the whole picture going east
    for (int y = 0; y < 32; y++) {
        int vy = v->playerY + y;
        if (vy < 0 || vy >= v->h) continue;
        for (int x = 0; x < w; x++) {
            int vx = v->playerX - (w - 16) / 2 + x;
            if (vx < 0 || vx >= v->w || !(px[vy * v->w + vx] & 0x80)) continue;
            uint32_t col = flPlayerPixel(core, spr, w, flip ? w - 1 - x : x, y);
            int dx = ox + vx, dy = oy + vy;
            if (col && dx >= 0 && dy >= 0 && dx < FL_SRC && dy < FL_SRC) flSrc[dy * FL_SRC + dx] = col;
        }
    }
}

/** A game drawn in greys (Ghost Grey): the player's own palette is grey through and through, so the vehicle is drawn in
 * those greys too, each colour taken to the grey nearest its brightness. Read off the player's palette in the frame. */
static uint32_t flGreys[16];
static int flGreyN;

static void flReadGreys(struct mCore* core) {
    flGreyN = 0;
    uint32_t spr = flSpriteAddr(core->busRead8(core, flObjAddr(core) + FL_OBJ_SPRITE_ID));
    uint32_t pal = 0x05000200u + 32u * (uint32_t) (core->busRead16(core, spr + FL_SPR_ATTR2) >> 12);
    uint32_t got[16];
    int n = 0;
    for (int i = 1; i < 16; i++) {
        uint16_t c = core->busRead16(core, pal + 2u * (uint32_t) i);
        int r = c & 0x1F, g = (c >> 5) & 0x1F, b = (c >> 10) & 0x1F;
        if (abs(r - g) > 1 || abs(g - b) > 1) return;   // a colour: not a grey game
        uint32_t v = (uint32_t) r << 3 | (uint32_t) r >> 2;
        int seen = 0;
        for (int k = 0; k < n; k++) seen |= got[k] == v;
        if (!seen) got[n++] = v;
    }
    if (n < 3) return;   // too few greys to tell (a sprite palette barely used)
    memcpy(flGreys, got, sizeof(got));
    flGreyN = n;
}

static uint32_t flGrey(uint32_t c) {
    if (!flGreyN) return c;
    int lum = (int) ((c & 0xFF) * 30 + ((c >> 8) & 0xFF) * 59 + ((c >> 16) & 0xFF) * 11) / 100;
    uint32_t best = flGreys[0];
    for (int k = 1; k < flGreyN; k++)
        if (abs((int) flGreys[k] - lum) < abs((int) best - lum)) best = flGreys[k];
    return (c & 0xFF000000u) | best << 16 | best << 8 | best;
}

/** A vehicle's frame into flSrc (its anchor at the centre), the player in its seat while they ride, its FRONT pixels
 * (a basket's rim, ropes, window frames) over them. */
static int flVehicleTick;
static void flDrawVehicle(struct mCore* core, const struct pk_flight_vehicle* v) {
    flVehicleTick++;
    int f = v->sequence[(flVehicleTick / v->ticks) % v->sequenceLength];
    if (f >= v->frames) f = 0;
    const uint8_t* px = v->pixels + (size_t) f * (size_t) (v->w * v->h);
    int ox = FL_SRC / 2 - v->anchorX, oy = FL_SRC / 2 - v->anchorY;
    for (int pass = 0; pass < 2; pass++) {
        for (int y = 0; y < v->h; y++) {
            for (int x = 0; x < v->w; x++) {
                uint8_t b = px[y * v->w + x];
                int dx = ox + x, dy = oy + y;
                if (!(b & 0x3F) || (pass && !(b & 0x40)) || dx < 0 || dy < 0 || dx >= FL_SRC || dy >= FL_SRC) continue;
                flSrc[dy * FL_SRC + dx] = flGrey(v->palette[b & 0x3F]);
            }
        }
        if (!pass && flRidden()) flSeatPlayer(core, v, px, ox, oy);
    }
}

/** An Emerald-style bird with no rider sprite to build (pokeemerald-expansion 1.13 dropped
 * CopyObjectGraphicsInfoToSpriteTemplate; Lazarus's we couldn't place), or a bird with no rider in its pictures
 * (flBirdNoRider: Unbound's): the player's own overworld sprite on its back, cut at the waist (sprite rows up to 26: the
 * cap starts at 13), its waist on the bird's back, the right way round once pk_flight_draw mirrors the picture. */
static void flSeatOnBird(struct mCore* core) {
    uint32_t spr;
    int w = flPlayerSprite(core, &spr);
    if (!w) return;
    int flip = (core->busRead16(core, spr + FL_SPR_ATTR1) & 0x1000u) != 0;   // posed facing west: pk_flight_draw mirrors the whole picture going east
    // Cut at the waist: row 26 of pret's 16 x 32 heroes (the cap starts at 13), row 20 of Unbound's taller 32 x 32 ones (whose
    // legs hid its small bird); the waist on the bird's back, a little behind the head (west in the picture).
    int waist = w == 32 ? 20 : 26;
    int ox = FL_SRC / 2 - w / 2 + 3, oy = FL_SRC / 2 - waist - (w == 32 ? 8 : 3);
    for (int y = 0; y <= waist; y++) {
        for (int x = 0; x < w; x++) {
            uint32_t col = flPlayerPixel(core, spr, w, flip ? w - 1 - x : x, y);
            if (col) flSrc[(oy + y) * FL_SRC + ox + x] = col;
        }
    }
}

static void flCapture(struct mCore* core) {
    // A bird with nothing in its picture: the balloon instead (checked on the bird's own frame, every frame).
    int birdCopied = 0;
    if (flBird >= 0 && !flCfg[PK_FLIGHT_VEHICLE]) {
        memset(flSrc, 0, sizeof(flSrc));
        flCopySprite(core, flBird, FL_SRC / 2, FL_SRC / 2);
        int any = 0;
        for (int i = 0; i < FL_SRC * FL_SRC && !any; i++) any = flSrc[i] != 0;
        flBirdBlank = !any;
        birdCopied = 1;
    }
    // A vehicle is drawn the whole time (coming in empty, leaving empty); the game's bird only while ridden.
    const struct pk_flight_vehicle* veh = flVehicle();
    int on = flBird >= 0 && (veh || flRidden());
    flSetHidden(core, flBird, on);
    flSetHidden(core, flRider, on);
    flSprOn = on;
    if (!on) return;
    // The rider's pose (their own sprite's frame; flSrc untouched).
    if (flRidden() && (veh || flBirdNoRider() || (flRiderMode() && !flCfg[PK_FLIGHT_RIDER_TEMPLATE])))
        flPosePlayer(core, veh && veh->upDown);
    if (veh) {
        memset(flSrc, 0, sizeof(flSrc));
        flReadGreys(core);
        flDrawVehicle(core, veh);
    } else {
        if (flBirdNoRider()) {
            // Unbound's bird is small for its tall hero: the rider first, the bird over their legs (seated in it, wings in front).
            memset(flSrc, 0, sizeof(flSrc));
            flSeatOnBird(core);
            flCopySprite(core, flBird, FL_SRC / 2, FL_SRC / 2);
        } else if (!birdCopied) {
            memset(flSrc, 0, sizeof(flSrc));
            flCopySprite(core, flBird, FL_SRC / 2, FL_SRC / 2);
        } // else the bird alone is in flSrc already (just copied above): the rider goes over it
        if (flRider >= 0) flCopySprite(core, flRider, FL_SRC / 2, FL_SRC / 2 - 8);
        else if (flRiderMode() && !flCfg[PK_FLIGHT_RIDER_TEMPLATE]) flSeatOnBird(core);
    }
    uint32_t hsh = 2166136261u;
    for (int i = 0; i < FL_SRC * FL_SRC; i++) hsh = (hsh ^ flSrc[i]) * 16777619u;
    if (hsh == flSrcHash) return;
    flSrcHash = hsh;
    static uint32_t s2[(FL_SRC * 2) * (FL_SRC * 2)];
    flScale2x(flSrc, FL_SRC, FL_SRC, s2);
    flScale2x(s2, FL_SRC * 2, FL_SRC * 2, flMid);
    flScale2x(flMid, FL_SRC * 4, FL_SRC * 4, flBig);
    memset(flBigOpaque, 0, sizeof(flBigOpaque));
    flBigU0 = flBigV0 = FL_BIG;
    flBigU1 = flBigV1 = -1;
    for (int v = 0; v < FL_BIG; v++)
        for (int u = 0; u < FL_BIG; u++) {
            if (!(flBig[v * FL_BIG + u] >> 24)) continue;
            flBigOpaque[(v * FL_BIG + u) >> 5] |= 1u << (u & 31);
            if (u < flBigU0) flBigU0 = u;
            if (u > flBigU1) flBigU1 = u;
            if (v < flBigV0) flBigV0 = v;
            if (v > flBigV1) flBigV1 = v;
        }
}

/** The bird's picture: 0 alone; with the rider, the side view (1 / 3: FireRed's bird flies off to the left, so it's
 * flipped going east) or the back (2 / 4, flying north). Read off the sprite, not remembered: a loaded state brings its
 * own. Emerald's bird has one picture, from the side (flipped the same way), and its rider is a sprite of its own
 * (flMakeRider), facing the way the bird does - from the side, north too. 0 when the game's code couldn't be called. */
static int flBirdLook(struct mCore* core, int ridden) {
    if (flBird < 0) return 1;
    if (flRiderMode() || flBirdNoRider()) {
        int flip = ridden && (flLook == FL_DIR_EAST || (flLook == FL_DIR_NORTH && flLastHorizontal == FL_DIR_EAST));
        if (!flSpriteLook(core, flSpriteAddr(flBird), 0, flip)) return 0;
        if (!ridden) return flDropRider(core);
        if (!flMakeRider(core)) return 0;
        return flRider < 0 || flSpriteLook(core, flSpriteAddr(flRider), (int) flCfg[PK_FLIGHT_RIDER_ANIM], flip);
    }
    int anim = !ridden ? 0 : (flLook == FL_DIR_NORTH ? 2 : 1) + 2 * flGender(core);
    int flip = ridden && flLook == FL_DIR_EAST;
    return flSpriteLook(core, flSpriteAddr(flBird), anim, flip);
}

static void flSpriteAt(struct mCore* core, int id, int x, int y) {
    uint32_t spr = flSpriteAddr(id);
    core->busWrite16(core, spr + FL_SPR_X, (uint16_t) x);
    core->busWrite16(core, spr + FL_SPR_Y, (uint16_t) y);
    core->busWrite16(core, spr + FL_SPR_X2, 0);
    core->busWrite16(core, spr + FL_SPR_Y2, 0);
}

/** The bird at (x, y), its rider (Emerald's) 8 px over it, as the game's FLY seats them. */
static void flBirdAt(struct mCore* core, int x, int y) {
    if (flBird < 0) return;
    flSpriteAt(core, flBird, x, y);
    if (flRider >= 0) flSpriteAt(core, flRider, x, y - 8);
    flDrawX = x;
    flDrawY = y;
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
    if (flRider >= 0) tiles[n++] = (uint16_t) (core->busRead16(core, flSpriteAddr(flRider) + FL_SPR_ATTR2) & 0x3FF);
    pk_wide_flight(flState != PK_FLIGHT_IDLE, tiles, n, flVx, flVy);
}

/** The player's sprite centre on screen right now (sprite x + x2 + the camera's sprite offset). */
static void flPlayerOnScreen(struct mCore* core, uint32_t obj, int* x, int* y) {
    uint32_t spr = flSpriteAddr(core->busRead8(core, obj + FL_OBJ_SPRITE_ID));
    int sx = (int16_t) core->busRead16(core, spr + FL_SPR_X) + (int16_t) core->busRead16(core, spr + FL_SPR_X2);
    int sy = (int16_t) core->busRead16(core, spr + FL_SPR_Y) + (int16_t) core->busRead16(core, spr + FL_SPR_Y2);
    if (core->busRead8(core, spr + FL_SPR_FLAGS) & 2) {
        sx += (int16_t) core->busRead16(core, flCfg[PK_FLIGHT_SPRITE_OFFSET]);
        uint32_t oy = flCfg[PK_FLIGHT_SPRITE_OFFSET_Y] ? flCfg[PK_FLIGHT_SPRITE_OFFSET_Y] : flCfg[PK_FLIGHT_SPRITE_OFFSET] + 2;
        sy += (int16_t) core->busRead16(core, oy);
    }
    *x = sx;
    *y = sy;
}

static void flHidePlayer(struct mCore* core, uint32_t obj, int hidden) {
    // Inanimate too while up: the flight's fast movements start the "go faster / fastest" step animations, which the player's
    // own table doesn't have (FireRed's has 13: anim 13 / 17 read EWRAM as commands, and in Rocket Edition, whose EWRAM
    // differs, the hidden sprite's animation jumped through a bad pointer and reset the game). A player is never inanimate.
    uint8_t f1 = core->busRead8(core, obj + FL_OBJ_FLAGS1);
    core->busWrite8(core, obj + FL_OBJ_FLAGS1,
                    (uint8_t) (hidden ? f1 | FL_INVISIBLE | FL_INANIMATE : f1 & ~(FL_INVISIBLE | FL_INANIMATE)));
}

static void flMarkPlayer(struct mCore* core, uint32_t obj, int flying) {
    core->busWrite8(core, obj + FL_OBJ_LOCAL_ID, flying ? FL_LOCALID_CAMERA : FL_LOCALID_PLAYER);
    core->busWrite8(core, obj + FL_OBJ_EFFECT_SPRITE, flying && flBird >= 0 ? (uint8_t) flBird : 0);
}

static int flMarked(struct mCore* core, uint32_t obj) {
    return core->busRead8(core, obj + FL_OBJ_LOCAL_ID) == FL_LOCALID_CAMERA &&
           (core->busRead8(core, obj + FL_OBJ_FLAGS2) & FL_IS_PLAYER);
}

/** The following Pokemon back by the player (pokeemerald-expansion's UpdateFollowingPokemon: spawned hidden on the player's
 * tile, out of its ball at the next step), where the record has one. */
static void flFollowerBack(struct mCore* core) {
    if (flCfg[PK_FLIGHT_FOLLOWER_UPDATE]) flCall(core, flCfg[PK_FLIGHT_FOLLOWER_UPDATE], 0, 0, 0, 0, NULL);
}

static void flPlaySe(struct mCore* core, uint32_t song) {
    if (flCfg[PK_FLIGHT_PLAY_SE]) flCall(core, flCfg[PK_FLIGHT_PLAY_SE], song, 0, 0, 0, NULL);
}

void pk_flight_reset(void) {
    flBirdBlank = 0;
    flMendedLayout = 0;
    flMendN = 0;
    flDrawnN = 0;
    flRedrawWanted = 0;
    flMendPending = 0;
    flShadeW = 0;
    flState = PK_FLIGHT_IDLE;
    flBird = -1;
    flRider = -1;
    flAngle = flAngleTarget = 0;
    flAngleSx = 1;
    flStill = 0;
    flSprOn = 0;
    flSrcHash = 0;
    flShadowOn = 0;
    flPhase = flLift = 0;
    flLandWanted = 0;
    flLandX = flLandY = -1;
    flRouteLen = -1;
    flRouteDone = 0;
    flPose = FL_POSE_WEST;
    flPoseSaved = -1;
}

void pk_flight_configure(const uint32_t* words, int n) {
    // The same words again (the app re-sends them every half second): nothing changes, a flight goes on.
    if (flConfigured && words && n >= PK_FLIGHT_WORDS) {
        uint32_t same[PK_FLIGHT_WORDS];
        memcpy(same, words, sizeof(same));
        same[PK_FLIGHT_VEHICLE] = flCfg[PK_FLIGHT_VEHICLE];
        if (!memcmp(same, flCfg, sizeof(flCfg))) {   // FLY ON picked mid-flight: the next frame is drawn in it
            flCfg[PK_FLIGHT_VEHICLE] = words[PK_FLIGHT_VEHICLE];
            return;
        }
    }
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
    // A tag with bit 16: the loader runs first, as R.O.W.E.'s CreateFlyBirdSprite does (LoadFieldEffectPalette-like, by index),
    // and CreateSprite then gives the bird its slot by the template's palette tag: that slot is kept.
    int preload = (flCfg[PK_FLIGHT_BIRD_PALETTE_TAG] & 0x10000u) != 0;
    if (preload && flCfg[PK_FLIGHT_BIRD_PALETTE]) flCall(core, flCfg[PK_FLIGHT_BIRD_PALETTE], flCfg[PK_FLIGHT_BIRD_PALETTE_TAG] & 0xFFFFu, 0, 0, 0, NULL);
    if (!flCall(core, flCfg[PK_FLIGHT_CREATE_SPRITE], flCfg[PK_FLIGHT_BIRD_TEMPLATE], (uint32_t) (flAnchorX + 120) & 0xFFFF,
              (uint32_t) (flAnchorY - 100) & 0xFFFF, 1, &spriteId) || spriteId >= FL_MAX_SPRITES) {
        return PK_FLIGHT_NOT_NOW;
    }
    flCall(core, flCfg[PK_FLIGHT_LOCK_CONTROLS], 0, 0, 0, 0, NULL);
    // A following Pokemon goes, as for the game's FLY: left on the ground it was lost on the next map (Heart and Soul).
    if (flCfg[PK_FLIGHT_FOLLOWER_REMOVE]) flCall(core, flCfg[PK_FLIGHT_FOLLOWER_REMOVE], 0, 0, 0, 0, NULL);
    flBird = (int) spriteId;
    uint32_t spr = flSpriteAddr(flBird);
    // Heart and Soul's template has no callback (its FLY sets one after): SpriteCallbackDummy, where the record has it.
    if (flCfg[PK_FLIGHT_SPRITE_DUMMY]) core->busWrite32(core, spr + 0x1Cu, flCfg[PK_FLIGHT_SPRITE_DUMMY] | 1u);
    uint16_t a2 = core->busRead16(core, spr + FL_SPR_ATTR2);
    // Palette 0 as the game's FLY has it, or where the hack's own FLY puts it (CFRU hooks CreateFlyBirdSprite there).
    uint32_t slot = 0;
    // (A tag of 0xFFFF: the loader takes the player's gender - pokeemerald-expansion's LoadPlayerObjectEventPalette.)
    uint32_t tag = flCfg[PK_FLIGHT_BIRD_PALETTE_TAG] == 0xFFFF ? (uint32_t) flGender(core) : flCfg[PK_FLIGHT_BIRD_PALETTE_TAG];
    if (preload) slot = a2 >> 12;
    else if (flCfg[PK_FLIGHT_BIRD_PALETTE] && (!flCall(core, flCfg[PK_FLIGHT_BIRD_PALETTE], tag, 0, 0, 0, &slot) || slot > 15)) slot = 0;
    core->busWrite16(core, spr + FL_SPR_ATTR2, (uint16_t) ((a2 & 0x03FFu) | (1u << 10) | (slot << 12)));   // priority 1
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
        flRider = -1;
        if (flBird >= 0 && flRiderMode()) {
            int r = (int) core->busRead16(core, flSpriteAddr(flBird) + FL_SPR_RIDER) - 1;
            if (r >= 0 && r < (int) FL_MAX_SPRITES && r != flBird && (core->busRead8(core, flSpriteAddr(r) + FL_SPR_FLAGS) & 1)) flRider = r;
        }
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
            flFollowerBack(core);
            return;
        }
        if (hidden) flHidePlayer(core, obj, 1);   // a state from before the player was made inanimate too
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
                if (flAreaStop) flAreaTurned = 1;
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
            if (!flBirdLook(core, 0)) break;   // Emerald's rider off the bird first (DestroySprite): next frame if not now
            flUnposePlayer(core);
            flHidePlayer(core, obj, 0);
            flMarkPlayer(core, obj, 0);
            flPlaySe(core, flCfg[PK_FLIGHT_SE_FLY]);
            flCall(core, flCfg[PK_FLIGHT_UNLOCK_CONTROLS], 0, 0, 0, 0, NULL);
            flFollowerBack(core);
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
    if (flShadowOn) flCloudsStep(core);
    flTurn();
    flCapture(core);
    flTellWide(core);
}

/** The shadow, into the finished frame: a soft oval in three bands, each darkening what's under it (ground, water, the
 * tree tops: it falls on whatever is below), larger and fainter the higher the bird. Then the bird with its rider
 * (flCapture's picture), turned by flAngle and mirrored by flSx: each pixel of the box around it looks up the picture's
 * 8x scale through the turn (RotSprite), so the outlines stay one pixel wide. Their places follow EXPAND's smooth camera
 * (pk_wide_flight_offset: the frame's own offset), as the bird's sprite did. */
// What the last pk_flight_draw changed: where, the pixels before, and what it wrote. mGBA redraws a line only when
// something on it changed, so a still line keeps last frame's drawing: put back first, or it darkens on itself frame
// after frame (on the RP6: 99, 69, 47, 21, 12, then a line that changed - the blinking).
// The box is the whole frame while the clouds are out (flClouds): the buffers grow to fit it.
static uint32_t* flShadeFrame;
static int flShadeX0, flShadeY0, flShadeH, flShadeStride;
static uint32_t *flShadeOld, *flShadeNew;
static size_t flShadeCap;

static void flShadeUndo(void) {
    if (!flShadeFrame || !flShadeW) return;
    // Line by line (mGBA redraws a line only when something on it changed): a line still as written wasn't drawn again
    // since, so it gets its pixels back; any other was, and is the game's new picture already.
    for (int y = 0; y < flShadeH; y++) {
        uint32_t* row = flShadeFrame + (flShadeY0 + y) * flShadeStride + flShadeX0;
        if (!memcmp(row, flShadeNew + (size_t) y * (size_t) flShadeW, (size_t) flShadeW * sizeof(uint32_t)))
            memcpy(row, flShadeOld + (size_t) y * (size_t) flShadeW, (size_t) flShadeW * sizeof(uint32_t));
    }
    flShadeW = 0;
}

/**
 * Clouds while up: a sheet of soft white puffs between the bird and the ground, seen through (at most FL_CLOUD_ALPHA), over
 * the ground and its shadow, under the bird. They're nearer the eye than the ground, so they slide by a little faster than
 * it does (FL_CLOUD_PARALLAX times the camera's own movement) and drift a little on the wind - the ground moving one
 * way and the clouds another is what reads as height. A 128 x 128 map of round puffs in clusters (flMakeClouds), each texel
 * 2 x 2 pixels, wrapped; they thin in with the climb and out with the landing.
 */
#define FL_CLOUD_N 128
#define FL_CLOUD_PARALLAX 1.15
#define FL_CLOUD_ALPHA 0.7
static uint8_t flCloudMap[FL_CLOUD_N * FL_CLOUD_N];
static int flCloudMade;
static double flCloudX, flCloudY;   // where the sheet is (pixels, wrapped by the map)

static void flMakeClouds(void) {
    if (flCloudMade) return;
    // Cumulus as the games draw them: a few clusters of round puffs, a big one in the middle and smaller ones round it,
    // flatter at the bottom. Each puff a dome of density; the map keeps the densest of any.
    uint32_t seed = 0x9E3779B9u;
    #define RND() ((seed = seed * 1664525u + 1013904223u) >> 8) / 16777216.0
    memset(flCloudMap, 0, sizeof(flCloudMap));
    for (int c = 0; c < 7; c++) {
        double cx = RND() * FL_CLOUD_N, cy = RND() * FL_CLOUD_N;
        int puffs = 4 + (int) (RND() * 4);
        double big = 7 + RND() * 4;
        for (int p = 0; p < puffs; p++) {
            double r = p == 0 ? big : big * (0.45 + RND() * 0.35);
            double a = RND() * 6.2832, d = p == 0 ? 0 : big * (0.55 + RND() * 0.5);
            double px = cx + cos(a) * d * 1.4, py = cy + sin(a) * d * 0.55 - (p == 0 ? 0 : r * 0.2);
            for (int y = (int) (py - r) - 1; y <= (int) (py + r) + 1; y++) {
                for (int x = (int) (px - r) - 1; x <= (int) (px + r) + 1; x++) {
                    double dx = (x - px) / r, dy = (y - py) / r;
                    if (dy > 0.55) continue;   // flat bottoms
                    double q = 1 - (dx * dx + dy * dy);
                    if (q <= 0) continue;
                    int v = (int) (sqrt(q) * 255 * 1.6);
                    if (v > 255) v = 255;
                    uint8_t* m = &flCloudMap[(y & (FL_CLOUD_N - 1)) * FL_CLOUD_N + (x & (FL_CLOUD_N - 1))];
                    if (v > *m) *m = (uint8_t) v;
                }
            }
        }
    }
    #undef RND
    flCloudMade = 1;
}

/** The sheet moved one frame (pk_flight_tick) with the camera the frame shows - the map's BG2 scroll as it was drawn,
 * which moves exactly as the ground does, staircase and all (the flight's own speed doesn't on a diagonal, and the game's
 * sprite offset is the next frame's: either one shook the clouds against the ground) - FL_CLOUD_PARALLAX times as far,
 * plus the wind. The map is read at the pixel plus this offset. */
static int flCloudPrevX = 0x7FFF, flCloudPrevY;
static void flCloudsStep(struct mCore* core) {
    int sx, sy;
    pk_wide_bg2_scroll(core, &sx, &sy);
    if (flCloudPrevX != 0x7FFF) {
        int dx = ((sx - flCloudPrevX + 128) & 0xFF) - 128, dy = ((sy - flCloudPrevY + 128) & 0xFF) - 128;   // the map's 256 px square wraps
        if (abs(dx) <= 16 && abs(dy) <= 16) {   // a warp or a new map's reset: no step
            flCloudX += dx * FL_CLOUD_PARALLAX;
            flCloudY += dy * FL_CLOUD_PARALLAX;
        }
    }
    flCloudPrevX = sx;
    flCloudPrevY = sy;
    flCloudX += 0.08;
    flCloudY -= 0.03;
    double span = FL_CLOUD_N * 2.0;
    flCloudX = fmod(flCloudX, span); if (flCloudX < 0) flCloudX += span;
    flCloudY = fmod(flCloudY, span); if (flCloudY < 0) flCloudY += span;
}

/** Where the map is read for frame pixel (0, 0): the sheet's offset, and with EXPAND's smooth camera (its window [fdx,
 * fdy] off the real one) as far again times FL_CLOUD_PARALLAX - 1, so the clouds keep their speed against the ground there too. */
static void flCloudOrigin(int fdx, int fdy, int* ox, int* oy) {
    *ox = (int) floor(flCloudX + (FL_CLOUD_PARALLAX - 1) * fdx);
    *oy = (int) floor(flCloudY + (FL_CLOUD_PARALLAX - 1) * fdy);
}

/** The map at half a pixel's resolution, bilinear (2 x 2 blocks read as squares). */
static int flCloudAt(int px, int py) {
    int x = px & (FL_CLOUD_N * 2 - 1), y = py & (FL_CLOUD_N * 2 - 1);
    int x0 = x >> 1, y0 = y >> 1, x1 = (x0 + 1) & (FL_CLOUD_N - 1), y1 = (y0 + 1) & (FL_CLOUD_N - 1);
    int a = flCloudMap[y0 * FL_CLOUD_N + x0], b = flCloudMap[y0 * FL_CLOUD_N + x1];
    int c = flCloudMap[y1 * FL_CLOUD_N + x0], d = flCloudMap[y1 * FL_CLOUD_N + x1];
    int fx = x & 1, fy = y & 1;
    int top = fx ? (a + b) >> 1 : a, bottom = fx ? (c + d) >> 1 : c;
    return fy ? (top + bottom) >> 1 : top;
}

/** The bird's pixels in the last frame pk_flight_draw drew (GBA frame coordinates): the clouds over EXPAND's whole picture
 * (pk_flight_draw_wide) leave them alone. */
static uint8_t flBirdMask[240 * 160];
static int flBirdMaskOn;
static double flCloudStrength;   // the last frame's (0 = none)

/** The clouds over [out]'s rows / columns [x0, x1] x [y0, y1] ([stride] a row), its pixel (x, y) being GBA frame pixel
 * (x + fx, y + fy) - the map read there - at [strength] (0..1): pixel-art puffs, a white core, a thinner rim round it, a
 * bluer underside where the cloud thins below (lit from the top, as the games' are). [mask]: skip the bird's pixels. */
/** flDrawClouds with the map's origin given ([ox], [oy]: flCloudOrigin) and, past [skipW] x [skipH] > 0, the frame's own
 * pixels (0, 0)..(skipW - 1, skipH - 1) left alone. Reads nothing that changes: any thread. */
static void flDrawCloudsAt(uint32_t* out, int stride, int x0, int y0, int x1, int y1, int fx, int fy, double strength, int mask,
                           int ox, int oy, int skipW, int skipH) {
    int core = (int) (strength * FL_CLOUD_ALPHA * 256), rim = core * 5 / 9;
    for (int y = y0; y <= y1; y++) {
        uint32_t* row = out + y * stride;
        int gy = y + fy;
        int sa = x1 + 1, sb = x1;   // the row's columns over the frame: [sa, sb]
        if (gy >= 0 && gy < skipH) {
            sa = -fx > x0 ? -fx : x0;
            sb = skipW - 1 - fx < x1 ? skipW - 1 - fx : x1;
        }
        for (int x = x0; x <= x1; x++) {
            if (x == sa && sa <= sb) { x = sb; continue; }
            int gx = x + fx;
            if (mask && gx >= 0 && gy >= 0 && gx < 240 && gy < 160 && flBirdMask[gy * 240 + gx]) continue;
            int m = flCloudAt(gx + ox, gy + oy);
            if (m < 40) continue;
            int under = flCloudAt(gx + ox, gy + oy + 3) < m - 30;   // the cloud thins below: its shaded side
            int a = m >= 140 ? core : rim;
            int wr = under ? 206 : 248, wg = under ? 220 : 250, wb = under ? 240 : 255;
            uint32_t c = row[x];
            int r = c & 0xFF, gg = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
            r += ((wr - r) * a) >> 8; gg += ((wg - gg) * a) >> 8; b += ((wb - b) * a) >> 8;
            row[x] = (c & 0xFF000000u) | (uint32_t) b << 16 | (uint32_t) gg << 8 | (uint32_t) r;
        }
    }
}

static void flDrawClouds(uint32_t* out, int stride, int x0, int y0, int x1, int y1, int fx, int fy, double strength, int mask) {
    if (strength <= 0.01) return;
    flMakeClouds();
    int fdx = 0, fdy = 0;
    pk_wide_flight_offset(&fdx, &fdy);
    int ox, oy;
    flCloudOrigin(fdx, fdy, &ox, &oy);
    flDrawCloudsAt(out, stride, x0, y0, x1, y1, fx, fy, strength, mask, ox, oy, 0, 0);
}

void pk_flight_draw_wide(uint32_t* out, int w, int h, int vx, int vy) {
    if (!out || flCloudStrength <= 0.01) return;
    int fdx = 0, fdy = 0;
    pk_wide_flight_offset(&fdx, &fdy);
    // The window as pk_wide_view drew it: moved by the flight's smooth camera.
    flDrawClouds(out, w, 0, 0, w - 1, h - 1, vx + fdx, vy + fdy, flCloudStrength, flBirdMaskOn);
}

void pk_flight_clouds_plan(struct PkFlightClouds* c, int vx, int vy, int fw, int fh) {
    memset(c, 0, sizeof(*c));
    if (flCloudStrength <= 0.01) return;
    flMakeClouds();
    int fdx = 0, fdy = 0;
    pk_wide_flight_offset(&fdx, &fdy);
    c->strength = flCloudStrength;
    flCloudOrigin(fdx, fdy, &c->ox, &c->oy);
    c->fx = vx + fdx;
    c->fy = vy + fdy;
    c->fw = fw;
    c->fh = fh;
}

void pk_flight_clouds_margins(const struct PkFlightClouds* c, uint32_t* out, int w, int h) {
    if (!out || !c || c->strength <= 0.01) return;
    flDrawCloudsAt(out, w, 0, 0, w - 1, h - 1, c->fx, c->fy, c->strength, 0, c->ox, c->oy, c->fw, c->fh);
}

void pk_flight_draw(uint32_t* frame, int w, int h, int stride, int cloudsHere) {
    if (!frame) return;
    if (frame == flShadeFrame) flShadeUndo();
    flShadeFrame = frame;
    flShadeW = 0;
    if (!flShadowOn && !flSprOn) { flCloudStrength = 0; return; }
    int dx = 0, dy = 0;
    pk_wide_flight_offset(&dx, &dy);
    int lift = flLift < 0 ? 0 : flLift > FL_LIFT ? FL_LIFT : flLift;
    int cx = flAnchorX + dx, cy = flAnchorY + FL_SHADOW_Y + FL_SHADOW_DROP * lift / FL_LIFT + dy;
    // At FL_LIFT: 34 x 11 px, darkest 30%; touching down: 24 x 7, darkest 42%.
    double rx = 12.0 + 5.0 * lift / FL_LIFT, ry = 3.5 + 2.0 * lift / FL_LIFT;
    double dark = 0.42 - 0.12 * lift / FL_LIFT;
    int bx = flDrawX + dx, by = flDrawY + dy;   // the bird's centre
    // The box both go in: the shadow's oval, the bird's turned picture (half a diagonal of FL_SRC around its centre).
    int half = (FL_SRC * 3 + 3) / 4;   // ~0.71 * FL_SRC: any turn of the picture fits
    int x0 = flShadowOn ? (int) (cx - rx - 1) : w, x1 = flShadowOn ? (int) (cx + rx + 1) : -1;
    int y0 = flShadowOn ? (int) (cy - ry - 1) : h, y1 = flShadowOn ? (int) (cy + ry + 1) : -1;
    if (flSprOn) {
        if (bx - half < x0) x0 = bx - half;
        if (bx + half > x1) x1 = bx + half;
        if (by - half < y0) y0 = by - half;
        if (by + half > y1) y1 = by + half;
    }
    // The clouds: as thick as the bird is high, while it's up (not the bird alone coming in or leaving) - here, or over
    // EXPAND's whole picture after it's made (pk_flight_draw_wide: [cloudsHere] 0).
    double clouds = flShadowOn ? (double) lift / FL_LIFT : 0;
    flCloudStrength = clouds;
    if (!cloudsHere) clouds = 0;
    if (clouds > 0.01) { x0 = 0; y0 = 0; x1 = w - 1; y1 = h - 1; }
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x1 >= w) x1 = w - 1;
    if (y1 >= h) y1 = h - 1;
    if (x1 < x0 || y1 < y0) return;
    int bw = x1 - x0 + 1;
    size_t need = (size_t) bw * (size_t) (y1 - y0 + 1);
    if (need > flShadeCap) {
        uint32_t* o = realloc(flShadeOld, need * sizeof(uint32_t));
        if (o) flShadeOld = o;
        uint32_t* nn = realloc(flShadeNew, need * sizeof(uint32_t));
        if (nn) flShadeNew = nn;
        if (!o || !nn) return;
        flShadeCap = need;
    }
    flShadeX0 = x0;
    flShadeY0 = y0;
    flShadeStride = stride;
    flShadeH = y1 - y0 + 1;
    for (int y = y0; y <= y1; y++) memcpy(flShadeOld + (size_t) (y - y0) * (size_t) bw, frame + y * stride + x0, (size_t) bw * sizeof(uint32_t));
    if (flShadowOn) {
        for (int y = (int) (cy - ry - 1); y <= (int) (cy + ry + 1); y++) {
            if (y < y0 || y > y1) continue;
            double ny = (y + 0.5 - cy) / ry;
            for (int x = (int) (cx - rx - 1); x <= (int) (cx + rx + 1); x++) {
                if (x < x0 || x > x1) continue;
                double nx = (x + 0.5 - cx) / rx;
                double d = nx * nx + ny * ny;
                if (d >= 1.0) continue;
                uint32_t c = frame[y * stride + x];
                double a = d < 0.4 ? dark : d < 0.75 ? dark * 0.65 : dark * 0.35;
                uint32_t keep = (uint32_t) ((1.0 - a) * 256.0);
                uint32_t r0 = ((c & 0xFF) * keep) >> 8, g0 = (((c >> 8) & 0xFF) * keep) >> 8, b0 = (((c >> 16) & 0xFF) * keep) >> 8;
                frame[y * stride + x] = (c & 0xFF000000u) | (b0 << 16) | (g0 << 8) | r0;
            }
        }
    }
    flDrawClouds(frame, stride, x0, y0, x1, y1, 0, 0, clouds, 0);
    flBirdMaskOn = w == 240 && h == 160;
    if (flBirdMaskOn) memset(flBirdMask, 0, sizeof(flBirdMask));
    if (flSprOn) {
        // Picture = mirror * R(-angle) * screen, around the centre; sampled in the 8x picture at the pixel's centre.
        // The same sums as one expression a pixel, the row's terms once; floor() of a value >= 0 is its truncation (and
        // a negative one is skipped either way): a 3DS spent ~1 ms a frame in floor().
        double c = cos(flAngle), s = sin(flAngle);
        int ya = by - half > y0 ? by - half : y0, yb = by + half < y1 ? by + half : y1;
        int xa = bx - half > x0 ? bx - half : x0, xb = bx + half < x1 ? bx + half : x1;
        if (flBigU0 > flBigU1) yb = ya - 1;   // nothing in the picture
        else {
            // The picture's box turned onto the screen (px = c U - s V, py = s U + c V, U = (u - 32) flSx, V = v - 32), a
            // pixel of room each way: every pixel outside samples outside it, where nothing shows.
            double us[2] = { flBigU0 / 8.0 - FL_SRC / 2, (flBigU1 + 1) / 8.0 - FL_SRC / 2 };
            double vs[2] = { flBigV0 / 8.0 - FL_SRC / 2, (flBigV1 + 1) / 8.0 - FL_SRC / 2 };
            double pxa = 1e9, pxb = -1e9, pya = 1e9, pyb = -1e9;
            for (int i = 0; i < 4; i++) {
                double U = us[i & 1] * flSx, V = vs[i >> 1];
                double qx = c * U - s * V, qy = s * U + c * V;
                if (qx < pxa) pxa = qx;
                if (qx > pxb) pxb = qx;
                if (qy < pya) pya = qy;
                if (qy > pyb) pyb = qy;
            }
            int bxa = bx + (int) floor(pxa) - 2, bxb = bx + (int) ceil(pxb) + 2;
            int bya = by + (int) floor(pya) - 2, byb = by + (int) ceil(pyb) + 2;
            if (bxa > xa) xa = bxa;
            if (bxb < xb) xb = bxb;
            if (bya > ya) ya = bya;
            if (byb < yb) yb = byb;
        }
        for (int y = ya; y <= yb; y++) {
            double py = y + 0.5 - by;
            double spy = s * py, cpy = c * py;
            for (int x = xa; x <= xb; x++) {
                double px = x + 0.5 - bx;
                double u8 = (flSx * (c * px + spy) + FL_SRC / 2) * 8, v8 = (-s * px + cpy + FL_SRC / 2) * 8;
                if (u8 < 0 || v8 < 0) continue;
                int iu = (int) u8, iv = (int) v8;
                if (iu >= FL_BIG || iv >= FL_BIG || !(flBigOpaque[(iv * FL_BIG + iu) >> 5] >> (iu & 31) & 1)) continue;
                uint32_t p = flBig[iv * FL_BIG + iu];
                if (!(p >> 24)) continue;
                frame[y * stride + x] = (frame[y * stride + x] & 0xFF000000u) | (p & 0x00FFFFFFu);
                if (flBirdMaskOn) flBirdMask[y * 240 + x] = 1;
            }
        }
    }
    for (int y = y0; y <= y1; y++) memcpy(flShadeNew + (size_t) (y - y0) * (size_t) bw, frame + y * stride + x0, (size_t) bw * sizeof(uint32_t));
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
    int cox, coy;
    flCloudOrigin(wx, wy, &cox, &coy);
    snprintf(out, (size_t) n, "flight: state %d phase %d bird %d shadow %d lift %d look %d at %d,%d screen %d,%d anchor %d,%d marked %d "
             "land %d,%d route %d/%d locked %d wide %d,%d view %d,%d turn %d drawn %d clouds %d,%d bg %d,%d", flState, flPhase, flBird, flShadowOn, flLift, flLook, x, y, sx, sy, flAnchorX, flAnchorY,
             flMarked(core, obj), flLandX, flLandY, flRouteDone, flRouteLen, (int) core->busRead8(core, flCfg[PK_FLIGHT_LOCK_BYTE]), wx, wy, vx, vy,
             (int) lround(flAngle * 57.29578), flSprOn, cox, coy, flCloudPrevX, flCloudPrevY);
}
