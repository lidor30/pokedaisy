package com.pokedaisy.app

import com.pokedaisy.app.companion.data.coop.CoopJoinRules
import com.pokedaisy.app.companion.data.coop.CoopStory
import com.pokedaisy.app.companion.data.coop.CoopStorySpots
import com.pokedaisy.app.companion.data.coop.PresenceUpdate
import com.pokedaisy.app.companion.data.save.Gen3BoxMon

interface CoopBattleField {
    fun mySpot(): CoopJoinRules.Spot?
    fun partnerSpot(): CoopJoinRules.Spot?
    fun hostParty(): List<ByteArray>
    fun foeSpecies(): Int
    fun storyAt(): CoopJoinRules.StoryAt = CoopJoinRules.StoryAt.NONE
}

/** Reads the Emerald field between frames for joined battles and story locks. */
class EmeraldBattleField(
    private val read: (Long, Int) -> ByteArray?,
    private val position: () -> IntArray?,
    private val partner: () -> PresenceUpdate?,
    private val heldStory: () -> Int?,
) : CoopBattleField {
    override fun mySpot(): CoopJoinRules.Spot? {
        val p = position() ?: return null
        if (p.size < 5 || p[0] == 0) return null
        return CoopJoinRules.Spot(p[1], p[2], p[3] / 16, p[4] / 16)
    }

    override fun partnerSpot() = partner()?.takeIf { it.valid }?.let {
        CoopJoinRules.Spot(it.mapGroup, it.mapNum, it.fineX / 16, it.fineY / 16)
    }

    fun partySize(): Int? = read(CoopEmeraldRam.PLAYER_PARTY_COUNT, 1)?.firstOrNull()?.toInt()?.and(0xff)

    override fun hostParty(): List<ByteArray> {
        val count = partySize()?.coerceAtMost(6) ?: return emptyList()
        if (count == 0) return emptyList()
        val size = CoopEmeraldRam.MON_SIZE
        val bytes = read(CoopEmeraldRam.PLAYER_PARTY, count * size) ?: return emptyList()
        if (bytes.size < count * size) return emptyList()
        return List(count) { bytes.copyOfRange(it * size, (it + 1) * size) }
    }

    override fun foeSpecies(): Int {
        val bytes = read(CoopEmeraldRam.ENEMY_PARTY, CoopEmeraldRam.MON_SIZE) ?: return 0
        return runCatching { Gen3BoxMon.read(bytes, 0).species }.getOrDefault(0)
    }

    override fun storyAt(): CoopJoinRules.StoryAt {
        val held = heldStory()
        val event = if (held != null && held != 0) CoopStory.byId(held) else CoopStorySpots.at(mySpot())
        return CoopStorySpots.storyAt(event) { trainer ->
            CoopStorySpots.trainerBeaten(trainer) { address, length -> read(address, length)?.takeIf { it.size == length } }
        }
    }
}

/** The retail Emerald's RAM addresses the app side reads (the co-op bridge's own are in pk_coop_emerald.h). */
object CoopEmeraldRam {
    const val PLAYER_PARTY = 0x020244ECL
    const val PLAYER_PARTY_COUNT = 0x020244E9L
    const val ENEMY_PARTY = 0x02024744L
    const val MON_SIZE = 100
}
