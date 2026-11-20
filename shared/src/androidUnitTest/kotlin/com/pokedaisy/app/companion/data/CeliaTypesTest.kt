package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Celia's ROM-extracted type names + species types (TypeNamesCelia.kt). */
class CeliaTypesTest {
    @Test
    fun `species types use the hack's own type ids`() {
        activeGame = GameKind.CELIA
        // The user's starter: Charmander (species 3) is Fire/Fighting here.
        val st = speciesTypeDataCelia.getValue(3)
        assertEquals("Fire", typeName(st.type1))
        assertEquals("Fighting", typeName(st.type2))
        val victini = speciesTypeDataCelia.getValue(1)
        assertEquals(listOf("Psychic", "Fire"), monTypes(victini.type1, victini.type2))
        assertEquals("Steel", typeName(4)) // reshuffled vs vanilla (vanilla 4 = Ground)
    }

    @Test
    fun `chart uses celia ids`() {
        activeGame = GameKind.CELIA
        assertEquals(200, typeMultiplierPct(14, 16, TYPE_NONE)) // Fire > Grass
        assertEquals(0, typeMultiplierPct(17, 13, TYPE_NONE))   // Electric > Ground
        assertEquals(0, typeMultiplierPct(0, 7, TYPE_NONE))     // Normal > Ghost
        assertEquals(500, typeMultiplierPct(23, 2, TYPE_NONE))  // Brock > Flying (joke 5x)
        // The user's Fire/Fighting Charmander is weak to Water (15).
        assertTrue(typeMatchups(14, 1).weaknesses.any { it.type == "Water" })
    }

    @Test
    fun `move types and powers come from the rom`() {
        activeGame = GameKind.CELIA
        assertEquals(MoveInfo("SCRATCH", 0, 35), moveDataCelia.getValue(10))
        assertEquals(MoveInfo("GROWL", 0, 0), moveDataCelia.getValue(359))
        assertEquals("Grass", typeName(moveDataCelia.getValue(22).type)) // VINE WHIP
    }
}
