package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Clover v1.3.3: FireRed 1.0 edited in place under an Emerald header (BPEE), run as FIRERED with its own names
 * (ActiveTables' fireRedHack, CloverGen.kt). Retail FireRed's save loads in it (the fixtures' READMEs say what the game's own
 * screens show): its party under Clover's species, its bag, money; a scripted wild battle.
 */
class CloverDecodeTest {
    @After fun tearDown() {
        fireRedHack = null
    }

    private fun use() {
        activeGame = GameKind.FIRERED
        fireRedHack = fireRedHackFor(NATIVE_CLOVER)
    }

    @Test
    fun `the save - party, bag, money`() {
        use()
        val t = decodeNative("clover", NATIVE_CLOVER)
        // The save's JOLTEON / KADABRA / LAPRAS / NIDOKING / GEODUDE / ZAPDOS slots are Clover's own species.
        assertEquals(listOf(135, 64, 131, 34, 74, 145), t.party.map { it.species })
        assertEquals(listOf("Chasumo", "Haremit", "Marleyzard", "Armando", "Mooshock", "Foryu"), t.party.map { speciesName(it.species) })
        assertEquals(listOf(50, 50, 50, 49, 7, 50), t.party.map { it.level })
        assertEquals(listOf(127, 110, 200, 154, 22, 151), t.party.map { it.maxHp })
        assertEquals(listOf("Double Kick", "Thunder Wave", "Thunderbolt", "Quick Attack"), t.party[0].moves.map { lookupMove(it).name })
        assertEquals(
            listOf("Potion" to 5, "Repel" to 2, "TM Case" to 1, "Berry Pouch" to 1, "Poké Ball" to 3, "TM01" to 1, "Cheri Berry" to 4),
            t.items.map { itemName(it.itemId) to it.quantity },
        )
        assertEquals(246468L, t.money)
        assertFalse(t.inBattle)
        assertPocketsInRange(t)
    }

    @Test
    fun `its own names, types and moves`() {
        use()
        assertEquals("Grasshole", speciesName(1))
        assertEquals("Clovenix", speciesName(151))
        assertEquals("Elbow Drop", lookupMove(719).name)
        assertEquals("Fairy", typeName(23))
    }

    @Test
    fun `a wild battle`() {
        use()
        val ram = FixtureMemoryReader.load("clover_battle")
        val t = decodeNative("clover_battle", NATIVE_CLOVER)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Chasumo" to 50, speciesName(me.species) to me.level)
        assertEquals("Pikotton" to 5, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_CLOVER))
    }
}
