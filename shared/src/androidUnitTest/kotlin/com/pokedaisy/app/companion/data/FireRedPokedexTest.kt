package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FireRedPokedexTest {
    @Test
    fun `seen and caught counts match the game's own POKeDEX screen`() {
        // Read off the real game's POKéDEX TABLE OF CONTENTS for this exact
        // save (headless mgba_dump `shot`, 2026-09-29): Seen KANTO 131 /
        // NATIONAL 138, Owned KANTO 69 / NATIONAL 69.
        val t = decodeNative("firered_vanilla", NATIVE_FIRERED_REV1)
        val dex = assertNotNullDex(t.pokedex)
        assertTrue(dex.national)
        assertEquals(138, dex.seen.size)
        assertEquals(131, dex.seen.count { it <= 151 })
        assertEquals(69, dex.caught.size)
        assertEquals(69, dex.caught.count { it <= 151 })
        assertTrue("caught must be a subset of seen", dex.seen.containsAll(dex.caught))
    }

    @Test
    fun `entries decode from the retail ROM`() {
        val rom = RomFileReader.load(RomFileReader.FIRERED_REV1_PATH)
        assumeTrue("retail FireRed ROM not on this machine", rom != null)
        activeGame = GameKind.FIRERED
        PokedexSource.reader = rom!!
        val t = POKEDEX_FIRERED_REV1
        assertTrue(pokedexMatchesRom(rom, t))
        assertFalse("Emerald's tables aren't in FireRed", pokedexMatchesRom(rom, POKEDEX_EMERALD))

        val bulbasaur = PokedexSource.entry(t, 1)!!
        assertEquals(1, bulbasaur.species)
        assertEquals("SEED", bulbasaur.category)
        assertEquals("2'04\"", formatDexHeight(bulbasaur.heightDm))
        assertEquals("15.2 lbs.", formatDexWeight(bulbasaur.weightHg))
        assertEquals(listOf("Grass", "Poison"), bulbasaur.types)
        assertEquals(listOf(45, 49, 49, 65, 65, 45), bulbasaur.baseStats)
        assertEquals(listOf("OVERGROW"), bulbasaur.abilities)
        assertEquals(listOf("MONSTER", "GRASS"), bulbasaur.eggGroups)
        assertTrue(bulbasaur.description.startsWith("There is a plant seed on its back right from the day"))

        // Hoenn species aren't in national order internally (Treecko = 277).
        val treecko = PokedexSource.entry(t, 252)!!
        assertEquals(277, treecko.species)
        assertEquals("WOOD GECKO", treecko.category)
        assertEquals(listOf("Grass"), treecko.types)

        // Every national number resolves to a species with a named category.
        for (n in 1..t.nationalCount) {
            val e = PokedexSource.entry(t, n)
            assertNotNull("entry $n", e)
            assertTrue("entry $n category", e!!.category.isNotBlank() && Gen3Text.UNKNOWN !in e.category)
            assertTrue("entry $n description", e.description.length > 40 && Gen3Text.UNKNOWN !in e.description)
            assertTrue("entry $n abilities", e.abilities.isNotEmpty() && e.abilities.none { Gen3Text.UNKNOWN in it })
        }
        assertEquals("POKéMON", Gen3Text.decode(byteArrayOf(0xCA.toByte(), 0xC9.toByte(), 0xC5.toByte(), 0x1B, 0xC7.toByte(), 0xC9.toByte(), 0xC8.toByte(), 0xFF.toByte())))
    }

    private fun assertNotNullDex(d: PokedexState?): PokedexState {
        assertNotNull("no pokedex state decoded", d)
        return d!!
    }
}
