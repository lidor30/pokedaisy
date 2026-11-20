package com.pokedaisy.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameTitleCatalogTest {
    @Test fun renameAndFileNamePreferenceSkipUnneededLookups() {
        val unavailable = { error("The unused name must not be read") }
        assertEquals("My name", GameTitleCatalog.label(unavailable, "My name", unavailable, unavailable))
        assertEquals("raw_name [!]", GameTitleCatalog.label({ "raw_name [!]" }, preferFileName = { true }, recognized = unavailable))
        assertEquals("Catalog title", GameTitleCatalog.label(unavailable, recognized = { "Catalog title" }))
        assertEquals("raw name", GameTitleCatalog.label({ "raw_name [!]" }))
        assertEquals("A.B (Rev 1)", romFileStem("A.B (Rev 1).gb"))
        assertEquals("No extension", romFileStem("No extension"))
    }

    @Test fun catalogPreservesRetailHackAndHashAdmissionRules() {
        val retail = "ea9bcae617fdf159b045185467ae58b2e4a48b9a"
        val hack = "b4776b82a4c7915d0fadeaa27e013523f99dfd94"
        assertEquals("Pokémon Red", GameTitleCatalog.BY_SHA1[retail])
        assertTrue(GameTitleCatalog.isRetail(retail))
        assertEquals("Pokémon Unbound", GameTitleCatalog.BY_SHA1[hack])
        assertTrue(GameTitleCatalog.knownHack(hack))
        assertEquals("Pokémon Red (Randomized)", GameTitleCatalog.randomizedTitle(retail))
        assertNull(GameTitleCatalog.randomizedTitle(hack))
        assertTrue(GameTitleCatalog.knowsGbaCode("BPEE"))
        assertTrue(GameTitleCatalog.knowsDsCode("CPUE"))
        assertFalse(GameTitleCatalog.knowsGbaCode(null))
        assertFalse(GameTitleCatalog.knowsDsCode("ZZZZ"))
    }

    @Test fun stockGbaGamesKeepTheirTitlesAndRandomizerBase() {
        val fireRed = "41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc"
        val spanishEmerald = "fe1558a3dcb0360ab558969e09b690888b846dd9"
        assertEquals("Pokémon FireRed", GameTitleCatalog.BY_SHA1[fireRed])
        assertEquals("Pokémon Edición Esmeralda", GameTitleCatalog.BY_SHA1[spanishEmerald])
        assertTrue(GameTitleCatalog.isRetail(fireRed))
        assertTrue(GameTitleCatalog.isRetail(spanishEmerald))
        assertEquals("Pokémon FireRed (Randomized)", GameTitleCatalog.randomizedTitle(fireRed))
    }
}
