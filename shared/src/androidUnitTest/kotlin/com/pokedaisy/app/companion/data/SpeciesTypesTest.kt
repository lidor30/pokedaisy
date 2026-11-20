package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FireRed / Emerald's species types (SpeciesTypes.kt), keyed by internal species id. */
class SpeciesTypesTest {
    @Test
    fun `bulbasaur is species 1 and 0 is no species`() {
        // The table once started at 0, so BULBASAUR read no types / weaknesses.
        assertEquals(SpeciesTypes(12, 3), speciesTypeData[1]) // Grass / Poison
        assertNull(speciesTypeData[0])
    }

    @Test
    fun `every table starts at species 1`() {
        for ((name, table) in listOf(
            "Celia" to speciesTypeDataCelia, "Glazed" to speciesTypeDataGlazed, "Hns" to speciesTypeDataHns,
            "Imperium" to speciesTypeDataImperium, "Lazarus" to speciesTypeDataLazarus,
            "OrangeIslands" to speciesTypeDataOrangeIslands, "Quetzal" to speciesTypeDataQuetzal,
            "RadicalRed" to speciesTypeDataRadicalRed, "Rogue" to speciesTypeDataRogue, "Rowe" to speciesTypeDataRowe,
            "Seaglass" to speciesTypeDataSeaglass, "SoulGold" to speciesTypeDataSoulGold, "SwordShield" to speciesTypeDataSwordShield,
            "Tmt2" to speciesTypeDataTmt2,
            "Unbound" to speciesTypeDataUnbound, "Yellow" to speciesTypeDataYellow,
        )) assertNull("$name has a species 0", table[0])
    }
}
