package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_ROWE: its bit-packed struct Pokemon, the bag, the battle (see each fixture's README). */
class RoweDecodeTest {
    @Test
    fun `party, bag, money and location`() {
        activeGame = GameKind.ROWE
        val t = decodeNative("rowe", NATIVE_ROWE)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals("Duraludon", speciesNamesRowe[mon.species])
        assertEquals("Duraludon", mon.nickname) // its 12-byte nickname at +0x08
        assertEquals(10 to 34, mon.level to mon.hp)
        assertEquals(34, mon.maxHp)
        assertEquals(listOf(232, 43, 249), mon.moves.filter { it != 0 }.toList())
        assertEquals(listOf(35, 30, 15), mon.pp.take(3))
        assertEquals(1000L, mon.exp)
        assertEquals(0xEF60DBA3L, mon.personality)
        assertEquals(7, mon.stats!!.nature) // Relaxed, as its summary says (not PID % 25)
        assertEquals(listOf(34, 24, 30, 19, 29, 15), mon.stats!!.stats)
        assertFalse(mon.isEgg)

        val bag = t.items.associate { itemNamesRowe[it.itemId] to it.quantity }
        assertEquals(5, bag["Repel"])
        assertEquals(6, bag["Potion"])
        assertEquals(10, bag["Poké Ball"])
        assertEquals(9, t.items.count { it.pocket == POCKET_KEY_ITEMS })
        assertPocketsInRange(t)

        assertEquals(3000L, t.money)
        assertEquals(0, t.regionMapSectionId)
        assertEquals("Littleroot Town", mapSecDataRowe.getValue(0).name)
        assertEquals(20 to 9, t.x to t.y)
        assertFalse(t.inBattle)
    }

    @Test
    fun `a scripted wild Wurmple`() {
        activeGame = GameKind.ROWE
        val ram = FixtureMemoryReader.load("rowe_battle")
        val t = readNativeTelemetry(ram, NATIVE_ROWE)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(884 to 10, me.species to me.level)
        assertEquals(8 to 16, me.type1 to me.type2) // Steel / Dragon
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(265 to 3, foe.species to foe.level)
        assertEquals(15 to 15, foe.hp to foe.maxHp)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_ROWE))
        assertEquals(listOf(265), t.battleFoes.map { it.species })
    }

    @Test
    fun `dex flags`() {
        val dex = readPokedexState(FixtureMemoryReader.load("rowe_battle"), NATIVE_ROWE, POKEDEX_ROWE)!!
        assertEquals(setOf(265, 884), dex.seen)
        assertEquals(setOf(884), dex.caught)
    }
}
