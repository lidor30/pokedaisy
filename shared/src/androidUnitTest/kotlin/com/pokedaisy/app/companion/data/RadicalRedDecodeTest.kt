package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadicalRedDecodeTest {
    @Test
    fun `party populated, pockets are in range`() {
        // Fixture is a fresh/near-start save (party=1, one item) - see
        // NativeDecodeSupport.kt for the fixture-testing philosophy.
        val t = decodeNative("radical_red", NATIVE_RADICAL_RED_V4_1)
        assertTrue(t.party.isNotEmpty())
        t.party.forEach { assertTrue(it.species > 0) }
        assertPocketsInRange(t)
    }

    @Test
    fun `native gender matches the game's own party screen`() {
        // PID + genderRatiosRadicalRed; the game shows this Charmander as male
        // (headless mgba_dump `shot`, 2026-09-27).
        activeGame = GameKind.RADICAL_RED
        val t = decodeNative("radical_red", NATIVE_RADICAL_RED_V4_1)
        assertEquals(GENDER_SYMBOL_MALE, t.party[0].genderSymbol)
    }

    @Test
    fun `a later save's species, moves and items use Radical Red's own tables`() {
        // radical_red_1636: a save 6 badges-ish in, with Gen 7-9 species and
        // items past vanilla's (headless capture, 2026-10-07). These showed as
        // "Species#1331", "Move#438", "Item#675" and vanilla's unused "03a".
        activeGame = GameKind.RADICAL_RED
        val t = decodeNative("radical_red_1636", NATIVE_RADICAL_RED_V4_1)
        assertEquals(
            listOf("Maushold", "Crocalor", "Pawmo", "Varoom", "Brionne", "Cutiefly"),
            t.party.map { speciesName(it.species) },
        )
        assertEquals(listOf("Super Fang", "Double Hit", "Bullet Seed", "Encore"), t.party[0].moves.map { lookupMove(it).name })
        assertEquals("Muscle Wing", itemName(58))
        assertEquals("Wise Glasses", itemName(675))
        val names = t.party.map { speciesName(it.species) } +
            t.party.flatMap { m -> m.moves.filter { it != 0 }.map { lookupMove(it).name } } +
            t.items.map { itemName(it.itemId) }
        names.forEach { assertTrue("unresolved name $it", '#' !in it) }
        // Crocalor is Fire, Pawmo Electric/Fighting (CFRU type ids).
        assertEquals(SpeciesTypes(10, 10), activeSpeciesTypeData[925])
        assertEquals(SpeciesTypes(13, 1), activeSpeciesTypeData[844])
    }
}
