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
) {
    val functions get() = listOf(lockControls, unlockControls, createSprite, destroySprite, startSpriteAnim, behaviorAt,
        collisionAt, elevationAt, objectAt, playSe, drawMetatileAt)

    /** [noFly] as pk_flight.h's two words: a u16 each, 0xFFFF where none. */
    private fun noFlyWords(): List<Int> {
        val ids = (noFly.take(4) + List(4) { 0xFFFF }).take(4)
        return listOf(ids[0] or (ids[1] shl 16), ids[2] or (ids[3] shl 16))
    }

    /** pk_flight_configure's words (pk_flight.h's PK_FLIGHT_* order). */
    fun words(): IntArray {
        val land = IntArray(8)
        for (b in landOk) if (b in 0..255) land[b / 32] = land[b / 32] or (1 shl (b % 32))
        return (listOf(objectEvents, playerAvatar, sprites, vmap, connectionFlags, saveBlock2Ptr, main, cb2Overworld,
            lockByte, paletteFade, lockControls, unlockControls, createSprite, destroySprite, startSpriteAnim, birdTemplate,
            behaviorAt, collisionAt, elevationAt, objectAt, playSe, seFly.toLong(), seFailure.toLong(), walkFast.toLong(),
            walkFaster.toLong(), spriteOffset, walkCurrent.toLong(), walkSlide.toLong(), mapHeader, mapGroups, drawMetatileAt).map { it.toInt() } +
            noFlyWords() + land.toList()).toIntArray()
    }
}

/** Whether the running ROM has [f]'s functions where they're expected. */
fun flightCodeMatches(f: FlightFns, read: (addr: Long, len: Int) -> ByteArray): Boolean =
    f.functions.size == f.codeCrcs.size && f.functions.zip(f.codeCrcs).all { (fn, crc) ->
        java.util.zip.CRC32().apply { update(read(fn, 32)) }.value == crc
    }

/** pkFlightTakeOff's answer as FLY's ([FlyResult]). */
fun flightResult(code: Int): FlyResult = when (code) {
    0 -> FlyResult.OK
    -2, -3, -4 -> FlyResult.NOT_NOW
    else -> FlyResult.FAILED
}
