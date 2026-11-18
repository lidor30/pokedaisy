package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pokémon RecordKeepers v1.2.1: a roguelike on a newer pokeemerald-expansion, its own RAM and names
 * (gen_expansion_tables.py recordkeepers). A new game played headless (the fixtures' READMEs). */
class RecordKeepersDecodeTest {
    @Test
    fun `the save - party, bag, money, place`() {
        activeGame = GameKind.RECORDKEEPERS
        val t = decodeNative("recordkeepers", NATIVE_RECORDKEEPERS)
        assertEquals(listOf(Triple("Charmander", 10, 29), Triple("Pikachu", 8, 24), Triple("Mudkip", 5, 20)),
            t.party.map { Triple(speciesName(it.species), it.level, it.maxHp) })
        assertEquals(listOf("Potion" to 5, "Poké Ball" to 10), t.items.map { itemName(it.itemId) to it.quantity })
        assertEquals(3000L, t.money)
        assertEquals("Abandoned Ship", activeMapSecData[t.regionMapSectionId]?.name)
        assertFalse(t.inBattle)
        assertPocketsInRange(t)
    }

    @Test
    fun `a wild battle`() {
        activeGame = GameKind.RECORDKEEPERS
        val t = decodeNative("recordkeepers_battle", NATIVE_RECORDKEEPERS)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(listOf("Scratch", "Growl", "Ember", "Smokescreen"), me.moves.map { lookupMove(it).name })
        assertEquals(Triple("Charmander", 10, 29), Triple(speciesName(me.species), me.level, me.maxHp))
        // The game scales the wild foe (setwildbattle asked for Lv5).
        assertEquals("Rattata" to 1, speciesName(foe.species) to foe.level)
    }
}
