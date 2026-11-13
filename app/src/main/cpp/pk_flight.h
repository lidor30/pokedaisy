// FREE FLIGHT: fly over the field on the game's own FLY bird, through trees, water and mountains, kept to the map
// and its connections; B lands on the nearest tile a player can stand on. GPL-3.0 (see NOTICE).
//
// The game does the moving: its controls locked (nothing a step triggers runs: wild battles, trainers' sight, warps,
// the START menu), the player stepped tile by tile with the game's held movements (a script's applymovement, which
// never checks collision), the camera and the map connections following as they do for any step. The player is
// hidden and marked (its localId set to the camera object's, so the game skips its ground effects: no footprints or
// rustling grass under a bird), and the game's FLY bird with its rider is a sprite of the game's own, fixed at the
// player's place on screen (the camera keeps the player there). Everything the flight is lives in the game's RAM, so
// a save state or REWIND taken mid-flight comes back flying (pk_flight_tick adopts it).
#ifndef PK_FLIGHT_H
#define PK_FLIGHT_H

#include <stdint.h>

struct mCore;

// The words pk_flight_configure takes, in this order (FlightFns in Kotlin, the FLIGHT_* records in gamedata/).
enum {
    PK_FLIGHT_OBJECT_EVENTS,     // gObjectEvents
    PK_FLIGHT_PLAYER_AVATAR,     // gPlayerAvatar
    PK_FLIGHT_SPRITES,           // gSprites
    PK_FLIGHT_VMAP,              // VMap (struct BackupMapLayout: Xsize, Ysize, map)
    PK_FLIGHT_CONNECTION_FLAGS,  // gMapConnectionFlags (south / north / west / east bits)
    PK_FLIGHT_SAVE_BLOCK2_PTR,   // gSaveBlock2Ptr (playerGender at +8)
    PK_FLIGHT_MAIN,              // gMain (callback2 at +4)
    PK_FLIGHT_CB2_OVERWORLD,     // CB2_Overworld
    PK_FLIGHT_LOCK_BYTE,         // sLockFieldControls
    PK_FLIGHT_PALETTE_FADE,      // gPaletteFade (active: bit 7 of +7)
    PK_FLIGHT_LOCK_CONTROLS,     // LockPlayerFieldControls
    PK_FLIGHT_UNLOCK_CONTROLS,   // UnlockPlayerFieldControls
    PK_FLIGHT_CREATE_SPRITE,     // CreateSprite(template, x, y, subpriority)
    PK_FLIGHT_DESTROY_SPRITE,    // DestroySprite(sprite)
    PK_FLIGHT_START_SPRITE_ANIM, // StartSpriteAnim(sprite, anim)
    PK_FLIGHT_BIRD_TEMPLATE,     // gFieldEffectObjectTemplate_Bird (FireRed: 64x64, its anims 0 alone, 1 / 2 Red, 3 / 4 Leaf;
                                 // Emerald: 32x32, the bird alone - the rider is a sprite of its own, PK_FLIGHT_RIDER_*)
    PK_FLIGHT_BEHAVIOR_AT,       // MapGridGetMetatileBehaviorAt(x, y)
    PK_FLIGHT_COLLISION_AT,      // MapGridGetCollisionAt(x, y)
    PK_FLIGHT_ELEVATION_AT,      // MapGridGetElevationAt(x, y)
    PK_FLIGHT_OBJECT_AT,         // GetObjectEventIdByXY(x, y): OBJECT_EVENTS_COUNT (16) for none
    PK_FLIGHT_PLAY_SE,           // PlaySE(song)
    PK_FLIGHT_SE_FLY,            // SE_M_FLY
    PK_FLIGHT_SE_FAILURE,        // SE_FAILURE
    PK_FLIGHT_WALK_FAST,         // MOVEMENT_ACTION_WALK_FAST_DOWN (then UP, LEFT, RIGHT)
    PK_FLIGHT_WALK_FASTER,       // MOVEMENT_ACTION_WALK_FASTER_DOWN
    PK_FLIGHT_SPRITE_OFFSET,     // gSpriteCoordOffsetX (Y at +2)
    PK_FLIGHT_WALK_CURRENT,      // MOVEMENT_ACTION_RIDE_WATER_CURRENT_DOWN: 6 frames a tile (a diagonal's step at the normal speed)
    PK_FLIGHT_WALK_SLIDE,        // MOVEMENT_ACTION_SLIDE_DOWN: 2 frames a tile (every other diagonal step at the faster one)
    PK_FLIGHT_MAP_HEADER,        // gMapHeader (its connections at +0xC)
    PK_FLIGHT_MAP_GROUPS,        // gMapGroups (a map's header: its layout, width and height)
    PK_FLIGHT_DRAW_METATILE,     // CurrentMapDrawMetatileAt(x, y): a repaired square drawn again (if it's in view)
    PK_FLIGHT_RIDER_TEMPLATE,    // CopyObjectGraphicsInfoToSpriteTemplate(graphicsId, callback, template *, subspriteTables **),
                                 // or 0: the bird's own picture carries the rider (FireRed). Emerald's FLY seats the player
                                 // on the bird: their surfing graphics in the get-on pose, 8 px over the bird's centre - a
                                 // sprite of its own made from that template (CreateSprite)
    PK_FLIGHT_RIDER_GFX,         // GetPlayerAvatarGraphicsIdByStateId(state): PLAYER_AVATAR_STATE_SURFING (3), by gender
    PK_FLIGHT_SPRITE_DUMMY,      // SpriteCallbackDummy (the rider's callback)
    PK_FLIGHT_RIDER_ANIM,        // ANIM_GET_ON_OFF_POKEMON_WEST: the rider sitting, facing west (flipped going east)
    PK_FLIGHT_SCRATCH,           // 0x1C bytes of EWRAM nothing else uses: the rider's SpriteTemplate and subspriteTables
    PK_FLIGHT_DRAW_VIEW,         // DrawWholeMapView(): the view drawn again after a crossing (flMend), or 0
    PK_FLIGHT_SPRITE_OFFSET_Y,   // gSpriteCoordOffsetY, or 0: right after X (FireRed / Emerald's s16 pair; Ruby / Sapphire keep two s32s apart)
    PK_FLIGHT_MAP_GROUPS_XOR,    // XORed into each of gMapGroups' group pointers (Unbound scrambles them), 0 = plain
    PK_FLIGHT_BIRD_PALETTE,      // the hacks' palette loader (CFRU's dynamic palettes: a slot by tag, loaded if need be), or 0:
                                 // CreateFlyBirdSprite's palette 0 (FireRed's player palette, with the bird's colours)
    PK_FLIGHT_BIRD_PALETTE_TAG,  // ... the tag it's given (Radical Red the player's 0x1100, Unbound its bird's 0x11A2)
                                 // 0xFFFF: the player's gender (the expansion); bit 16: called before CreateSprite with the low half,
                                 // the slot CreateSprite gives kept (R.O.W.E.)
    PK_FLIGHT_OBJ_SPRITE_ID,     // ObjectEvent.spriteId's offset, 0 = 4 (pokeemerald-expansion moved it to +0x23: graphicsId grew to a u16)
    PK_FLIGHT_FOLLOWER_REMOVE,   // pokeemerald-expansion's following Pokemon: RemoveFollowingPokemon() at take-off (as the game's
                                 // FLY does), UpdateFollowingPokemon() once down (back by the player, out of its ball at the
    PK_FLIGHT_FOLLOWER_UPDATE,   // first step, as after a map load); 0 = no follower
    PK_FLIGHT_MAPGRID_UNDEFINED, // the map grid's undefined square (its metatile-id mask too), 0 = 0x3FF (SoulGold's 11-bit ids: 0x7FF)
    PK_FLIGHT_VEHICLE,           // MAKEOVER > FLY ON: 0 the game's bird, else pk_flight_vehicles.h's from 1 (balloon, helicopter,
                                 // zeppelin) - the app's choice, not the record's; changing it mid-flight just redraws
    PK_FLIGHT_NO_FLY,            // 2 words: up to 4 maps (group << 8 | num, 0xFFFF none) the flight never crosses into -
                                 // stand-ins only ever seen from outside (FireRed's SaffronCity_Connection: scrambled tiles)
    PK_FLIGHT_LAND_OK = PK_FLIGHT_NO_FLY + 2,   // 8 words: a bit per metatile behavior a player may land on
    PK_FLIGHT_WORDS = PK_FLIGHT_LAND_OK + 8
};

enum {
    PK_FLIGHT_IDLE,
    PK_FLIGHT_TAKING_OFF,   // the bird coming in, then rising with the rider
    PK_FLIGHT_FLYING,
    PK_FLIGHT_LANDING,      // flying to the landing tile, then down
    PK_FLIGHT_DISMOUNT      // the player back on the ground, the bird leaving
};

// pk_flight_take_off's answers.
#define PK_FLIGHT_OK 0
#define PK_FLIGHT_NOT_CONFIGURED -1
#define PK_FLIGHT_BUSY -2          // already flying (or landing)
#define PK_FLIGHT_NOT_NOW -3       // not the free field: a script, a menu, a fade, a battle
#define PK_FLIGHT_NOT_ON_FOOT -4   // surfing (or anything but on foot / a bike)

/** How the game's functions are called (the app's pk_call; mgba_dump's own): 1 when it returned, r0 in *r0. */
typedef int (*pk_flight_call_fn)(struct mCore* core, uint32_t fn, const uint32_t args[4], uint32_t* r0);
void pk_flight_set_call(pk_flight_call_fn fn);
/** The game's addresses ([PK_FLIGHT_WORDS] words), or n = 0 to switch it off (a flight in progress is dropped). */
void pk_flight_configure(const uint32_t* words, int n);
/** FLIGHT AREA: the maps the flight may cross into, a bit per map (group << 8 | num: bit id % 8 of byte id / 8, at most
 * PK_FLIGHT_AREA_BYTES), the player's own map always being one of them; n = 0 = anywhere (the default). Kept across
 * pk_flight_configure / pk_flight_reset: it's the app's, not the game's. Emu thread. */
#define PK_FLIGHT_AREA_BYTES 8192
void pk_flight_set_area(const uint8_t* bits, int n);
/** 1 when the D-pad met FLIGHT AREA's edge (a map not in it) since the last call, which clears it: the app says why. */
int pk_flight_area_turned(void);
/** Takes off on the next frames (the gates the game would check for FLY are the caller's: badge, a Pokemon with
 * Fly, outdoors). Emu thread, between frames. */
int pk_flight_take_off(struct mCore* core);
/** Lands as soon as it can: the nearest tile a player can stand on, flown to first. */
void pk_flight_land(void);
/** Flies the route (directions 1 down, 2 up, 3 left, 4 right) at the faster speed, then lands - the scenic FLY. The
 * flight must be up (or taking off). Returns 0, or PK_FLIGHT_NOT_NOW when there's no flight to give it to. */
int pk_flight_route(const uint8_t* dirs, int n);
/** How far a route has got (steps taken), -1 with none. */
int pk_flight_route_done(void);
int pk_flight_state(void);
/** After every frame: the flight's next step. Reads the core's keys (D-pad to fly, A held faster, B to land). */
void pk_flight_tick(struct mCore* core);
/** After every frame, before it's shown: the shadow under the bird, drawn into the finished [frame] (w x h pixels,
 * [stride] a row, mGBA's 32-bit colour), then the clouds while up ([cloudsHere]; 0 = EXPAND draws its picture from this
 * frame next, and pk_flight_draw_wide puts them over all of it), then the bird. */
void pk_flight_draw(uint32_t* frame, int w, int h, int stride, int cloudsHere);
/** EXPAND's picture made (pk_wide_view's [out], w x h, its window at (vx, vy) of the frame): the clouds over all of it, but
 * the bird's pixels. */
void pk_flight_draw_wide(uint32_t* out, int w, int h, int vx, int vy);
/** The clouds over EXPAND's margins only, where pk_flight_draw put them into the frame itself ([cloudsHere] 1: a 3DS,
 * whose EXPAND may leave the frame's rectangle to the GPU or draw the margins later on another thread). Planned right
 * after pk_wide_view on the emulator's thread (its window at (vx, vy) of the [fw] x [fh] frame), drawn into its [out]
 * (w x h) once that's whole, on any thread: the plan holds everything the drawing reads. Strength 0 = no clouds. */
struct PkFlightClouds { double strength; int ox, oy, fx, fy, fw, fh; };
void pk_flight_clouds_plan(struct PkFlightClouds* c, int vx, int vy, int fw, int fh);
void pk_flight_clouds_margins(const struct PkFlightClouds* c, uint32_t* out, int w, int h);
/** A new game / reset: forget everything (the game's RAM is gone). */
void pk_flight_reset(void);
/** mgba_dump's `flightinfo`. */
void pk_flight_info(struct mCore* core, char* out, int n);

#endif
