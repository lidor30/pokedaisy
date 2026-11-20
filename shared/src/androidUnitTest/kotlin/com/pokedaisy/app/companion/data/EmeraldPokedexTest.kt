package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EmeraldPokedexTest {
    @Test
    fun `seen and caught counts match the game's own POKeDEX screen`() {
        // Read off the real game's POKéDEX list for this exact save (headless
        // mgba_dump `shot`, 2026-09-29): Hoenn mode, SEEN 108, OWN 28.
        val t = decodeNative("emerald_vanilla", NATIVE_EMERALD_RETAIL)
        val dex = t.pokedex
        assertNotNull("no pokedex state decoded", dex)
        assertFalse(dex!!.national)
        assertEquals(108, dex.seen.size)
        assertEquals(28, dex.caught.size)
    }

    @Test
    fun `hacks built on NATIVE_EMERALD don't inherit the retail dex`() {
        assertEquals(null, NATIVE_EMERALD.pokedex)
        assertEquals(POKEDEX_EMERALD_SEAGLASS, NATIVE_EMERALD_SEAGLASS.pokedex)
    }

    @Test
    fun `entries and the Hoenn order decode from the retail ROM`() {
        val rom = RomFileReader.load(RomFileReader.EMERALD_PATH)
        assumeTrue("retail Emerald ROM not on this machine", rom != null)
        activeGame = GameKind.EMERALD
        PokedexSource.reader = rom!!
        val t = POKEDEX_EMERALD
        assertTrue(pokedexMatchesRom(rom, t))
        assertFalse("FireRed's tables aren't in Emerald", pokedexMatchesRom(rom, POKEDEX_FIRERED_REV1))

        val hoenn = PokedexSource.regionalOrder(t)!!
        assertEquals(202, hoenn.size)
        assertEquals(252, hoenn[0]) // TREECKO is HOENN No.001
        assertEquals(386, hoenn[201]) // ...and DEOXYS No.202
        assertEquals(202, hoenn.toSet().size)

        val bulbasaur = PokedexSource.entry(t, 1)!!
        assertEquals("SEED", bulbasaur.category)
        assertEquals("2'04\"", formatDexHeight(bulbasaur.heightDm))
        assertEquals(listOf("OVERGROW"), bulbasaur.abilities)
        for (n in 1..t.nationalCount) {
            val e = PokedexSource.entry(t, n)
            assertNotNull("entry $n", e)
            assertTrue("entry $n category", e!!.category.isNotBlank() && Gen3Text.UNKNOWN !in e.category)
            assertTrue("entry $n description", e.description.length > 40 && Gen3Text.UNKNOWN !in e.description)
        }
    }
}
