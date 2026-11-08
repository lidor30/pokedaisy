package com.pokedaisy.app.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A party icon's pose as UpdateMonIconFrame plays sMonIconAnims: duration + 1 frames, and a frame for the loop's jump. */
class PartyIconPaceTest {
    @Test fun `a cycle is twice the duration plus three frames`() {
        assertEquals(125, partyIconPoseMs(6))    // full HP: 15 frames a cycle
        assertEquals(158, partyIconPoseMs(8))    // green: 19
        assertEquals(258, partyIconPoseMs(14))   // yellow: 31
        assertEquals(391, partyIconPoseMs(22))   // red: 47
    }
}
