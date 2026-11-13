package com.pokedaisy.app.companion.data

/**
 * FREE FLIGHT and the scenic FLY: the player flies over the field on the game's own FLY bird, through trees, water and
 * mountains, kept to the map and its connections, and lands on the nearest tile a player can stand on. The flying is
 * native (app/src/main/cpp/pk_flight.c, after every frame): the game's controls locked, the player stepped with its
 * held movements, hidden, and the bird with its rider a sprite of the game's own. These are the addresses it works
 * from, in pk_flight.h's order ([words]); [ScenicFly] reads the world's maps for the scenic FLY's route.
 *
 * Retail FireRed rev 1 first: pret's pokefirered built clean as firered_rev1 (SHA1 dd5945db..., byte for byte retail)
 * and its ELF. Flown headless with mgba_dump's `flighton` / `flight` on the user's save: over Route 20's mountain and
 * sea, across into Cinnabar Island (the game's own map sign), B landing on the nearest sand, a save state taken in the
 * air loading back flying.
 */
data class FlightFns(
    val objectEvents: Long,
    val playerAvatar: Long,
    val sprites: Long,
    /** VMap: struct BackupMapLayout (Xsize, Ysize, map) - the loaded map with its connections around it. */
    val vmap: Long,
    val connectionFlags: Long,
    val saveBlock1Ptr: Long,
    val saveBlock2Ptr: Long,
    val main: Long,
    val cb2Overworld: Long,
    /** sLockFieldControls. */
    val lockByte: Long,
    val paletteFade: Long,
    val lockControls: Long,
    val unlockControls: Long,
    val createSprite: Long,
    val destroySprite: Long,
    val startSpriteAnim: Long,
    /** gFieldEffectObjectTemplate_Bird: the FLY bird, alone (anim 0) or with the rider (1 / 2 Red, 3 / 4 Leaf). */
    val birdTemplate: Long,
    val behaviorAt: Long,
    val collisionAt: Long,
    val elevationAt: Long,
    val objectAt: Long,
    val playSe: Long,
    val seFly: Int,
    val seFailure: Int,
    /** MOVEMENT_ACTION_WALK_FAST_DOWN (then UP, LEFT, RIGHT) and WALK_FASTER_DOWN. */
    val walkFast: Int,
    val walkFaster: Int,
    /** gSpriteCoordOffsetX (Y at +2). */
    val spriteOffset: Long,
    /** MOVEMENT_ACTION_RIDE_WATER_CURRENT_DOWN (6 frames a tile) and MOVEMENT_ACTION_SLIDE_DOWN (2): a diagonal's quicker
     * steps, so it keeps the straight speed along it. */
    val walkCurrent: Int,
    val walkSlide: Int,
    /** gMapHeader (the loaded map's connections at +0xC). */
    val mapHeader: Long,
    /** CurrentMapDrawMetatileAt(x, y): a map square mended at the grid's edges drawn again (pk_flight.c's flMend). */
    val drawMetatileAt: Long,
    /** The metatile behaviours a player may land on (ground, grass, sand...; never water, a ledge, a door or a warp). */
    val landOk: Set<Int>,
    /** gMapGroups: a pointer per map group to its list of map header pointers ([mapGroupSizes] each). */
    val mapGroups: Long,
    val mapGroupSizes: List<Int>,
    /** sHealLocations: {s8 group, s8 num, s16 x, s16 y} per heal location id from 1, [healLocationSize] bytes each. */
    val healLocations: Long,
    val healLocationSize: Int,
    val healLocationCount: Int,
    /** Maps (group shl 8 or num) the flight never crosses into, at most 4: stand-ins the game only shows from outside
     * (FireRed's SaffronCity_Connection, 3.11: its tiles come out scrambled, and EXPAND can't match it). */
    val noFly: List<Int>,
    /** CRC32 of each called function's first 32 bytes ([functions]' order): a build that moved them is left alone. */
    val codeCrcs: List<Long>,
    /** Emerald's bird carries no rider: CopyObjectGraphicsInfoToSpriteTemplate, to make one from the player's surfing
     * graphics ([riderGfx]: GetPlayerAvatarGraphicsIdByStateId) in their get-on pose ([riderAnim], facing west), with
     * [spriteDummy] (SpriteCallbackDummy) and [scratch] (0x1C bytes of EWRAM nothing else uses). 0: the bird's own
     * picture has the rider (FireRed). */
    val riderTemplate: Long = 0,
    val riderGfx: Long = 0,
    val spriteDummy: Long = 0,
    val riderAnim: Int = 0,
    val scratch: Long = 0,
    /** DrawWholeMapView: the view drawn again after crossing into a map (pk_flight.c's flMend), 0 = none. */
    val drawView: Long = 0,
    /** [saveBlock1Ptr] / [saveBlock2Ptr] are the save blocks themselves, not pointers to them (Ruby / Sapphire). */
    val saveBlockStatic: Boolean = false,
    /** gSpriteCoordOffsetY, 0 = [spriteOffset] + 2 (FireRed / Emerald's s16 pair; Ruby / Sapphire keep two s32s apart). */
    val spriteOffsetY: Long = 0,
    /** XORed into each of [mapGroups]' group pointers (Unbound scrambles them), 0 = plain. */
    val mapGroupsXor: Long = 0,
    /** The hacks' palette loader, called with [birdPaletteTag] for the bird's palette slot (CFRU's dynamic palettes, as
     * its hooked CreateFlyBirdSprite does), 0 = palette 0 (FireRed's player palette, which has the bird's colours). */
    val birdPalette: Long = 0,
    val birdPaletteTag: Int = 0,
    /** ObjectEvent.spriteId's offset, 0 = 4 (pokeemerald-expansion keeps it at +0x23). */
    val objSpriteId: Int = 0,
    /** Where SaveBlock1's pos starts (location 4 after it): Heart and Soul puts a u16 saveVersion (padded) first, 4. */
    val saveBlock1PosOff: Int = 0,
    /** pokeemerald-expansion's following Pokemon: RemoveFollowingPokemon / UpdateFollowingPokemon (Heart and Soul), 0 = none. */
    val followerRemove: Long = 0,
    val followerUpdate: Long = 0,
    /** The map grid's undefined square, also its metatile-id mask: 0 = 0x3FF (SoulGold's ids are 11 bits: 0x7FF). */
    val mapGridUndefined: Int = 0,
) {
    val functions get() = listOf(lockControls, unlockControls, createSprite, destroySprite, startSpriteAnim, behaviorAt,
        collisionAt, elevationAt, objectAt, playSe, drawMetatileAt) + listOf(riderTemplate, riderGfx, drawView, birdPalette, followerRemove, followerUpdate).filter { it != 0L }

    /** [noFly] as pk_flight.h's two words: a u16 each, 0xFFFF where none. */
    private fun noFlyWords(): List<Int> {
        val ids = (noFly.take(4) + List(4) { 0xFFFF }).take(4)
        return listOf(ids[0] or (ids[1] shl 16), ids[2] or (ids[3] shl 16))
    }

    /** pk_flight_configure's words (pk_flight.h's PK_FLIGHT_* order); [vehicle]: MAKEOVER > FLY ON's ([FlightVehicle]). */
    fun words(vehicle: FlightVehicle = FlightVehicle.BIRD): IntArray {
        val land = IntArray(8)
        for (b in landOk) if (b in 0..255) land[b / 32] = land[b / 32] or (1 shl (b % 32))
        return (listOf(objectEvents, playerAvatar, sprites, vmap, connectionFlags, saveBlock2Ptr, main, cb2Overworld,
            lockByte, paletteFade, lockControls, unlockControls, createSprite, destroySprite, startSpriteAnim, birdTemplate,
            behaviorAt, collisionAt, elevationAt, objectAt, playSe, seFly.toLong(), seFailure.toLong(), walkFast.toLong(),
            walkFaster.toLong(), spriteOffset, walkCurrent.toLong(), walkSlide.toLong(), mapHeader, mapGroups, drawMetatileAt,
            riderTemplate, riderGfx, spriteDummy, riderAnim.toLong(), scratch, drawView, spriteOffsetY, mapGroupsXor, birdPalette, birdPaletteTag.toLong(), objSpriteId.toLong(), followerRemove, followerUpdate, mapGridUndefined.toLong(), vehicle.id.toLong()).map { it.toInt() } +
            noFlyWords() + land.toList()).toIntArray()
    }
}

/** What FREE FLIGHT and the scenic FLY fly on (MAKEOVER > FLY ON, [com.pokedaisy.app.Prefs.flightVehicle]): the game's
 * own bird, or one of our vehicles (pk_flight_vehicles.h, scripts/gen_flight_vehicles.py's art) with the player in it.
 * [id] is pk_flight.h's PK_FLIGHT_VEHICLE word; [label] the row's value. */
enum class FlightVehicle(val id: Int, val label: String) {
    BIRD(0, "BIRD"), BALLOON(1, "AIR BALLOON"), HELICOPTER(2, "HELICOPTER"), ZEPPELIN(3, "ZEPPELIN"), PLANE(4, "PLANE");

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: BIRD
    }
}

/** Whether the running ROM has [f]'s functions where they're expected. */
fun flightCodeMatches(f: FlightFns, read: (addr: Long, len: Int) -> ByteArray): Boolean =
    f.functions.size == f.codeCrcs.size && f.functions.zip(f.codeCrcs).all { (fn, crc) ->
        java.util.zip.CRC32().apply { update(read(fn, 32)) }.value == crc
    }

/**
 * Whether FREE FLIGHT may fly this game: the code CRCs only say its functions are where the record has them, and a hack
 * edited in place keeps them while redrawing the world - each is let in only once its own maps have been flown headless
 * and it has a record of its own ([flight]). [retail]: the Poller's retail hash (randomized retail and every language too).
 */
fun flightAllowed(kind: GameKind?, retail: Boolean, flight: FlightFns? = null): Boolean = when (kind) {
    GameKind.FIRERED -> retail || FIRERED_HACK_FLIGHTS.any { it === flight }
    GameKind.EMERALD -> retail || flight === FLIGHT_EMERALD_LEGACY || flight === FLIGHT_EMERALD_KAIZO
    // Known by their hash, their worlds flown headless.
    GameKind.RADICAL_RED, GameKind.UNBOUND, GameKind.GLAZED, GameKind.ORANGE_ISLANDS, GameKind.ODYSSEY, GameKind.GAIA,
    GameKind.AMETHYST, GameKind.SWORD_SHIELD, GameKind.GS_CHRONICLES, GameKind.IMPERIUM, GameKind.LAZARUS, GameKind.HEART_AND_SOUL, GameKind.EMERALD_SEAGLASS, GameKind.TMT2, GameKind.ROWE, GameKind.MARIOMON, GameKind.QUETZAL,
    GameKind.SOULGOLD -> flight != null
    else -> false
}

/** The FireRed 1.0 hacks running as FIRERED that have a flight record of their own (FieldItems.kt's fireRedHackFlight). */
private val FIRERED_HACK_FLIGHTS: List<FlightFns> by lazy {
    listOf(FLIGHT_ULTRA_VIOLET, FLIGHT_GHOST_GREY, FLIGHT_LETS_GO, FLIGHT_ROCKET_EDITION, FLIGHT_ASH_GRAY)
}

/** pkFlightTakeOff's answer as FLY's ([FlyResult]). */
fun flightResult(code: Int): FlyResult = when (code) {
    0 -> FlyResult.OK
    -2, -3, -4 -> FlyResult.NOT_NOW
    else -> FlyResult.FAILED
}
