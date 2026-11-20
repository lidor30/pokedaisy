package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class UnboundPokedexTest {
    @Test
    fun `seen and caught flags match the game's own POKeDEX screen`() {
        // The game's Table of Contents for this save (headless mgba_dump
        // `shot`, 2026-09-29): Seen Borrius 2 / National 4, Owned National 1
        // (the party's GIBLE). Its Borrius list marks SKORUPI and INKAY seen.
        val t = decodeNative("unbound", NATIVE_UNBOUND_WITH_DEX)
        val dex = t.pokedex
        assertNotNull("no pokedex state decoded", dex)
        assertEquals(setOf(246, 443, 451, 686), dex!!.seen)
        assertEquals(setOf(443), dex.caught)
        assertTrue(dex.national)
    }

    @Test
    fun `entries and the Borrius order decode from the ROM`() {
        val rom = RomFileReader.load(RomFileReader.UNBOUND_PATH)
        assumeTrue("Unbound ROM not on this machine", rom != null)
        activeGame = GameKind.UNBOUND
        PokedexSource.reader = rom!!
        val t = POKEDEX_UNBOUND
        assertTrue(pokedexMatchesRom(rom, t))

        val borrius = PokedexSource.regionalOrder(t)!!
        assertEquals(498, borrius.size)
        assertEquals(498, borrius.toSet().size)
        assertTrue(borrius.all { it in 1..t.nationalCount })
        // "Seen: Borrius 2" = SKORUPI + INKAY; LARVITAR and GIBLE aren't in it.
        assertEquals(setOf(451, 686), setOf(246, 443, 451, 686).intersect(borrius.toSet()))

        val bulbasaur = PokedexSource.entry(t, 1)!!
        assertEquals("Seed", bulbasaur.category)
        assertEquals(listOf("Overgrow"), bulbasaur.abilities)
        assertEquals("Chlorophyll", bulbasaur.hiddenAbility)
        val enamorus = PokedexSource.entry(t, 905)!!
        assertEquals(listOf("Fairy", "Flying"), enamorus.types)
        assertEquals(254, enamorus.genderRatio)
        for (n in 1..t.nationalCount) {
            val e = PokedexSource.entry(t, n)
            assertNotNull("entry $n", e)
            assertTrue("entry $n category '${e!!.category}'", e.category.isNotBlank() && Gen3Text.UNKNOWN !in e.category)
            assertTrue("entry $n description '${e.description}'", e.description.length > 20 && Gen3Text.UNKNOWN !in e.description)
        }
    }
}
