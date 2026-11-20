package com.pokedaisy.app

import com.pokedaisy.app.companion.data.coop.COOP_STORY_EVENTS
import com.pokedaisy.app.companion.data.coop.CoopJoinRules
import com.pokedaisy.app.companion.data.coop.PresenceUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmeraldBattleFieldTest {
    @Test fun bothPositionsUseTheSameTileUnits() {
        var position = intArrayOf(1, 2, 3, 39, 49)
        var peer = PresenceUpdate(0, true, false, 2, 3, 39, 49, 1, 0, 0, 0)
        val field = EmeraldBattleField({ _, _ -> null }, { position }, { peer }, { null })
        assertEquals(CoopJoinRules.Spot(2, 3, 2, 3), field.mySpot())
        assertEquals(field.mySpot(), field.partnerSpot())
        position = intArrayOf(1)
        peer = peer.copy(valid = false)
        assertNull(field.mySpot())
        assertNull(field.partnerSpot())
    }

    @Test fun partyReadsOnlyCompleteRecordsAndCapsTheCountAtSix() {
        var data = ByteArray(600) { (it / 100).toByte() }
        val field = EmeraldBattleField({ addr, size ->
            if (addr == CoopEmeraldRam.PLAYER_PARTY_COUNT) byteArrayOf(-1)
            else data.copyOfRange(0, minOf(size, data.size))
        }, { null }, { null }, { null })
        assertEquals((0..5).toList(), field.hostParty().map { it.first().toInt() })
        data = ByteArray(599)
        assertTrue(field.hostParty().isEmpty())
    }

    @Test fun parkedStoryUsesTheHeldEventEvenWithoutAFieldPosition() {
        val event = COOP_STORY_EVENTS.first { it.trainers.size == 1 && it.trainers[0] > 0 }
        val field = EmeraldBattleField({ _, _ -> byteArrayOf() }, { null }, { null }, { event.id })
        assertEquals(CoopJoinRules.StoryAt(event.id, event.trainers.single(), true), field.storyAt())
    }
}
