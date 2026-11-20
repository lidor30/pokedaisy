package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HERE's area data (GuideAreas*Gen.kt), checked against the decomps' own maps and scripts. */
class GuideAreasTest {
    /** MAPSEC_CELADON_CITY: the rooftop EEVEE (CeladonCity_Condominiums_RoofRoom) and the TEA. */
    @Test fun celadonCity() {
        val things = areaThings(GuideId.FIRERED, 94)
        assertTrue(things.any { it.kind == AreaKind.MON && it.id == 133 && it.where == "CONDOMINIUMS ROOF ROOM" })
        assertTrue(things.any { it.kind == AreaKind.GIFT && it.id == 369 && it.flag != 0 }) // ITEM_TEA
    }

    /** MAPSEC_RUSTBORO_CITY: HM01 CUT in the CUTTER's house, flagged FLAG_RECEIVED_HM_CUT (0x89). */
    @Test fun rustboroCity() {
        val cut = areaThings(GuideId.EMERALD, 10).single { it.kind == AreaKind.GIFT && it.id == 339 }
        assertEquals("CUTTERS HOUSE", cut.where)
        assertEquals(0x89, cut.flag)
    }

    /** Route 104's ITEM_POTION item ball; hidden items there too. */
    @Test fun route104() {
        val things = areaThings(GuideId.EMERALD, 19)
        assertTrue(things.any { it.kind == AreaKind.ITEM && it.id == 13 })
        assertTrue(things.count { it.kind == AreaKind.HIDDEN } >= 3)
    }

    /** pokefirered's `#if defined(LEAFGREEN)` trades: ROUTE 5's gate wants NIDORAN♀ for NIDORAN♂ in LeafGreen. */
    @Test fun leafGreenTrades() {
        val fr = areaThings(GuideId.FIRERED, 129).single { it.kind == AreaKind.TRADE }
        val lg = areaThings(GuideId.LEAFGREEN, 129).single { it.kind == AreaKind.TRADE }
        assertEquals(29 to 32, fr.id to fr.wants) // NIDORAN♀ for NIDORAN♂
        assertEquals(32 to 29, lg.id to lg.wants)
        assertEquals(fr.flag, lg.flag)
    }

    @Test fun otherGamesHaveNone() {
        assertTrue(areaThings(guideId(GameKind.UNBOUND, null), 10).isEmpty())
        assertEquals(areaKey("Pokémon Tower"), areaKey("POKEMON TOWER"))
    }
}
