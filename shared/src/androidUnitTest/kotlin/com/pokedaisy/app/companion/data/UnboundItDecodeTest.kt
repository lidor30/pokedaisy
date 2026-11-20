package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Unbound v2.1.1.1 IT (the Italian translation's patch v7): English Unbound's RAM, its names in Italian. The fixture
 * is the user's English save booted in it (see its README). */
class UnboundItDecodeTest {
    @After fun reset() {
        romGameCode = ""
        romLanguage = 'E'
    }

    private fun select() {
        activeGame = GameKind.UNBOUND
        romGameCode = NATIVE_UNBOUND_IT.gameCode
        romLanguage = NATIVE_UNBOUND_IT.language
    }

    @Test
    fun `party, bag and location decode, in Italian`() {
        select()
        val t = decodeNative("unbound_it", NATIVE_UNBOUND_IT)
        assertEquals(listOf("Gible" to 11), t.party.map { speciesName(it.species) to it.level })
        assertEquals(listOf(496), t.party.map { it.species }) // Unbound's own ids, as in English
        assertEquals(listOf("Azione", "Turbosabbia", "Ferrartigli"), t.party[0].moves.filter { it != 0 }.map { lookupMove(it).name })
        // A move keeps Unbound's type / power under its Italian name.
        assertEquals(moveDataUnbound.getValue(t.party[0].moves[0]).copy(name = "Azione"), activeMoveData.getValue(t.party[0].moves[0]))
        assertEquals(
            listOf("Pozione" to 2, "Repellente" to 2, "Succodibacca" to 1, "Box Costumi" to 1, "Poké Ball" to 5),
            t.items.map { itemName(it.itemId) to it.quantity },
        )
        assertEquals(3 to 66, t.mapGroup to t.mapNum)
        assertEquals("Alture Ghiacciate", activeMapSecData[t.regionMapSectionId]!!.name)
        assertEquals(3848L, t.money)
        assertFalse(t.inBattle)
        assertPocketsInRange(t)
        // Its natures are Italian too (the summary's STATS).
        assertEquals("Ardita", localText!!.natures[0])
        assertEquals("Borgo Bellin", activeMapSecData[89]!!.name)
    }

    @Test
    fun `its POKéDEX page is metric with a decimal comma`() {
        assertEquals("0,7 m", formatDexHeight(POKEDEX_UNBOUND_IT, 7))
        assertEquals("69,0 kg", formatDexWeight(POKEDEX_UNBOUND_IT, 690))
    }
}
