package com.pokedaisy.app.companion.data.coop

/**
 * Where a player stands in a story event, for a save without PLAY TOGETHER ONLY (the lock's hold names the event by itself): a story
 * trainer's spot is the tile its COORD trigger sits on, or the object's own tile and the four beside it (the player faces it). A
 * FRAME script starts by itself on entering a map, nobody stands "at" it. An event with two or more story trainers (a rival whose
 * script holds one team per starter) or none is never a joinable spot: the trainer to arm for isn't known before the script runs.
 */
object CoopStorySpots {
    const val MAP_OFFSET = 7

    /** The joinable story event at [spot], null when none. */
    fun at(spot: CoopJoinRules.Spot?, events: List<StoryEvent> = COOP_STORY_EVENTS): StoryEvent? {
        if (spot == null) return null
        // [spot] is the player object's currentCoords (MAP_OFFSET 7 added, checked headless: story_coords); an event's x / y are the map's own.
        val tx = spot.tileX - MAP_OFFSET; val ty = spot.tileY - MAP_OFFSET
        return events.firstOrNull { e ->
            e.trainerId() != 0 && e.mapGroup == spot.mapGroup && e.mapNum == spot.mapNum && when (e.kind) {
                StoryKind.COORD -> e.x == tx && e.y == ty
                StoryKind.OBJECT -> kotlin.math.abs(e.x - tx) + kotlin.math.abs(e.y - ty) == 1   // beside it (the app does not read which way the player faces)
                StoryKind.FRAME -> false
            }
        }
    }

    /** The one story trainer an event battles, 0 = none or several. */
    fun StoryEvent.trainerId(): Int = trainers.singleOrNull() ?: 0

    /** A story event as one player sees it: [beaten] from that trainer's flag in the save. */
    fun storyAt(event: StoryEvent?, beaten: (Int) -> Boolean): CoopJoinRules.StoryAt {
        val id = event?.trainerId() ?: 0
        return if (event == null || id == 0) CoopJoinRules.StoryAt.NONE else CoopJoinRules.StoryAt(event.id, id, beaten(id))
    }

    /** Whether the save's trainer flag for [trainerId] is set: SaveBlock1's flag bytes through [read] (address, length). */
    fun trainerBeaten(trainerId: Int, read: (Long, Int) -> ByteArray?): Boolean {
        if (trainerId !in 1 until COOP_TRAINERS_COUNT) return true
        val p = read(COOP_SAVEBLOCK1_PTR, 4) ?: return true
        val sb1 = (p[0].toLong() and 0xFF) or ((p[1].toLong() and 0xFF) shl 8) or ((p[2].toLong() and 0xFF) shl 16) or ((p[3].toLong() and 0xFF) shl 24)
        if (sb1 < 0x02000000L || sb1 >= 0x02040000L) return true
        val flag = COOP_TRAINER_FLAGS_START + trainerId
        val b = read(sb1 + COOP_SB1_FLAGS_OFF + flag / 8, 1) ?: return true
        return (b[0].toInt() shr (flag % 8)) and 1 != 0
    }
}
