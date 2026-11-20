package com.pokedaisy.app.companion.data

import org.junit.Assert.assertTrue
import org.junit.Test

class GaiaDecodeTest {
    @Test
    fun `party populated, pockets are in range`() {
        // Fixture is a fresh/near-start save (party=1, one item) - see
        // NativeDecodeSupport.kt for the fixture-testing philosophy.
        val t = decodeNative("gaia", NATIVE_GAIA_V3_2)
        assertTrue(t.party.isNotEmpty())
        t.party.forEach { assertTrue(it.species > 0) }
        assertPocketsInRange(t)
    }
}
