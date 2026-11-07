package com.pokedaisy.app.companion.data.coop

import org.junit.Assert.assertEquals
import org.junit.Test

class CoopStoryTest {
    @Test fun winFlagsAddTheTrainersOwnFlag() {
        val table = listOf(TrainerWin(265, listOf(0x807, 0x4F0)))
        assertEquals(listOf(0x500 + 265, 0x807, 0x4F0), CoopStory.winFlagsFrom(table, 265))
        assertEquals(listOf(0x500 + 3), CoopStory.winFlagsFrom(emptyList(), 3))
    }

    @Test fun winFlagsRefuseOutOfRange() {
        assertEquals(emptyList<Int>(), CoopStory.winFlagsFrom(emptyList(), 0))
        assertEquals(emptyList<Int>(), CoopStory.winFlagsFrom(emptyList(), COOP_TRAINERS_COUNT))
    }
}
