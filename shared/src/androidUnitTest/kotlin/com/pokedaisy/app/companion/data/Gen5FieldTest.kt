package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Black's FLY destinations (Gen5Field.kt) on the player's own save - the `black` fixture. */
class Gen5FieldTest {
    @Test
    fun `FLY offers the towns whose arrival flag is set, by place`() {
        val r = FixtureMemoryReader.load("black")
        val open = gen5OpenFlySpots({ a, n -> r.readCoreMemory(a, n) }, GEN5_BLACK, gen5FlySpots(GameKind.BLACK))
        // every town but Unity Tower (flag 2494 unset in this save), as places: Nuvema 4 .. Victory Road 40
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 44, 41, 42, 50, 40), open)
    }

    @Test
    fun `a destination is its town's place, named by it`() {
        val spots = gen5FlySpots(GameKind.BLACK)
        assertEquals(16, spots.size)
        // one place per town, and back to the same zone
        assertEquals(spots.size, spots.map { gen5FlyPlace(GEN5_BLACK, it) }.toSet().size)
        for (s in spots) assertEquals(s, gen5FlySpotAt(GEN5_BLACK, gen5FlyPlace(GEN5_BLACK, s), spots))
        assertEquals(16, gen5FlySpotAt(GEN5_BLACK, 7, spots)?.mapHeader)
        assertEquals("Nacrene City", dsFlySpotName(GameKind.BLACK, 7))
        assertNull(dsFlySpotName(GameKind.BLACK, 14))   // Route 1: no FLY spot
        assertEquals(FlyResult.OK, gen5FlyResult(0))
        assertEquals(FlyResult.CANT_FLY_HERE, gen5FlyResult(1))
    }
}
