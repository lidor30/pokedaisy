package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/** The evolution table read from the retail FireRed ROM (skipped when it isn't on this machine). */
class FireRedEvolutionsTest {
    private lateinit var table: List<List<Evolution>>

    @Before
    fun load() {
        val rom = RomFileReader.load(RomFileReader.FIRERED_REV1_PATH)
        assumeNotNull(rom)
        activeGame = GameKind.FIRERED
        PokedexSource.reader = rom!!
        table = EvolutionSource.table(POKEDEX_FIRERED_REV1)!!
    }

    private fun texts(species: Int) = table[species].map(::evolutionText)

    @Test fun levelUp() = assertEquals(listOf("LV 16 → IVYSAUR"), texts(1))

    @Test fun stones() = assertEquals(
        listOf("THUNDER STONE → JOLTEON", "WATER STONE → VAPOREON", "FIRE STONE → FLAREON",
            "Level up with high friendship, by day → ESPEON", "Level up with high friendship, at night → UMBREON"),
        texts(133),
    )

    @Test fun trades() {
        assertEquals(listOf("Trade → ALAKAZAM"), texts(64))
        assertEquals(listOf("Trade holding METAL COAT → STEELIX"), texts(95))
    }

    @Test fun kinds() {
        assertEquals("TRADE", evolutionKind(table[64][0]))
        assertEquals("USE AN ITEM", evolutionKind(table[133][0]))
        assertEquals("LEVEL UP (STATS)", evolutionKind(table[236][0]))
    }

    /** A ROM whose table isn't where the config says gets nothing rather than garbage. */
    @Test fun wrongAddress() {
        EvolutionSource.table(POKEDEX_FIRERED_REV1.copy(evolutions = POKEDEX_FIRERED_REV1.evolutions + 8))
            .let { assertEquals(null, it) }
    }
}
