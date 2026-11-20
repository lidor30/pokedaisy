package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Unbound v2.1.1.1 FR: English Unbound's RAM, its names in French (see the fixture's README). */
class UnboundFrDecodeTest {
    @After fun reset() {
        romGameCode = ""
        romLanguage = 'E'
    }

    private fun select() {
        activeGame = GameKind.UNBOUND
        romGameCode = NATIVE_UNBOUND_FR.gameCode
        romLanguage = NATIVE_UNBOUND_FR.language
    }

    @Test
    fun `party, bag and location decode, in French`() {
        select()
        val t = decodeNative("unbound_fr", NATIVE_UNBOUND_FR)
        assertEquals(listOf("Embrylex" to 16, "Stalgamin" to 12, "Cadoizo" to 9), t.party.map { speciesName(it.species) to it.level })
        assertEquals(listOf(246, 346, 225), t.party.map { it.species }) // Unbound's own ids, as in English
        assertEquals(44 to 47, t.party[0].hp to t.party[0].maxHp)
        assertEquals(listOf("Tomberoche", "Jet-Pierres", "Tempêtesable", "Morsure"), t.party[0].moves.map { lookupMove(it).name })
        // A move keeps Unbound's type / power under its French name.
        assertEquals(moveDataUnbound.getValue(t.party[0].moves[3]).copy(name = "Morsure"), activeMoveData.getValue(t.party[0].moves[3]))
        assertEquals(
            setOf("Potion", "Repousse", "Antigel", "Antidote", "Boîte Costume", "Poké Ball", "Soin Ball", "Honor Ball"),
            t.items.map { itemName(it.itemId) }.toSet(),
        )
        assertEquals(5, t.items.first { it.itemId == 61 }.quantity)
        assertPocketsInRange(t)
        assertEquals(3 to 67, t.mapGroup to t.mapNum)
        assertEquals("Route 1", activeMapSecData[t.regionMapSectionId]!!.name)
        assertEquals("Bélenbourg", activeMapSecData[89]!!.name)
        assertEquals(1748L, t.money)
        assertFalse(t.inBattle)
        // Its natures are French too (the summary's STATS).
        assertEquals("Hardi", localText!!.natures[0])
    }

    @Test
    fun `English Unbound keeps its English names`() {
        activeGame = GameKind.UNBOUND
        assertEquals("Larvitar", speciesName(246))
    }
}
