package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Too Many Types 2's ROM-extracted type system (see TypeChartTmt2.kt). */
class Tmt2TypesTest {
    @Test
    fun `renumbered and custom types resolve`() {
        activeGame = GameKind.TMT2
        assertEquals("Fire", typeName(11))
        assertEquals("Steel", typeName(9))
        // The user's Chimchar and its moves, as extracted from the ROM.
        assertEquals(SpeciesTypes(11, 61), speciesTypeDataTmt2[390])
        assertEquals("Monke", typeName(61))
        assertEquals("Sharp", typeName(moveDataTmt2.getValue(10).type)) // Scratch
        assertEquals(40, moveDataTmt2.getValue(10).power)
        assertEquals(11, activeTypeIdByName()["Fire"])
    }

    @Test
    fun `chart matches known pairs`() {
        activeGame = GameKind.TMT2
        assertEquals(200, typeMultiplierPct(11, 13, TYPE_NONE)) // Fire > Grass
        assertEquals(50, typeMultiplierPct(11, 12, TYPE_NONE))  // Fire > Water
        assertEquals(0, typeMultiplierPct(14, 5, TYPE_NONE))    // Electric > Ground
        assertEquals(0, typeMultiplierPct(1, 8, TYPE_NONE))     // Normal > Ghost
    }

    @Test
    fun `steel is not skipped as mystery`() {
        // Vanilla's id 9 is "???" and typeMatchups skipped it by id; here 9 is
        // Steel, and Fairy is weak to Steel - so it must be listed.
        activeGame = GameKind.TMT2
        val m = typeMatchups(19, 19) // Fairy
        assertTrue(m.weaknesses.any { it.type == "Steel" })
        assertTrue(m.weaknesses.none { it.type == "???" })
    }
}
