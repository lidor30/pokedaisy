package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmeraldVanillaDecodeTest {
    @Test
    fun `party populated, items span multiple pockets`() {
        val t = decodeNative("emerald_vanilla", NATIVE_EMERALD)
        assertTrue(t.party.isNotEmpty())
        t.party.forEach { assertTrue(it.species > 0) }
        assertEquals("KADABRA", t.party[0].nickname)
        assertPocketsInRange(t)
        val pockets = t.items.map { it.pocket }.toSet()
        assertTrue("expected items in >1 pocket, got $pockets", pockets.size > 1)
    }
}
