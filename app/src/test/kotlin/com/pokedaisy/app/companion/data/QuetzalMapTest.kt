package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Alpha 9's maps: a player in Hoenn or Johto only had Kanto's picture to look at. */
class QuetzalMapTest {
    private var game = activeGame

    @Before fun quetzal() {
        game = activeGame
        activeGame = GameKind.QUETZAL
    }

    @After fun back() {
        activeGame = game
    }

    @Test fun eachRegionHasItsMap() {
        assertEquals(regionMapImagesEmerald[0], lookupLocation(0).regionMapAsset)   // Littleroot Town: Emerald's map
        assertEquals("quetzal_johto", lookupLocation(0x102).regionMapAsset)  // Violet City
        assertEquals("quetzal_kanto", lookupLocation(88).regionMapAsset)     // Pallet Town
    }

    @Test fun johtosBoxesAreTheRoms() {
        val violet = lookupLocation(0x102)
        assertEquals("Violet City", violet.mapSecName)
        assertEquals(13 * 8, violet.highlightX)
        assertEquals(6 * 8, violet.highlightY)
    }

    @Test fun hoennsPlacesSitWhereEmeraldHasThem() {
        for (id in listOf(0, 1, 8, 10, 18)) {   // Littleroot, Oldale, Slateport, Rustboro, Route 103
            val em = mapSecDataEmerald.getValue(id)
            val place = lookupLocation(id)
            assertEquals(em.name, em.x * 8, place.highlightX)
            assertEquals(em.name, em.y * 8, place.highlightY)
        }
    }

    @Test fun aPlaceOffTheMapHasNoPicture() {
        assertNull(lookupLocation(86).regionMapAsset)   // Secret Base
    }
}
