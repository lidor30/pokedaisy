package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** NATIVE_ULTRA_VIOLET: retail FireRed rev 0's RAM, FireRed's tables with its own names (see the fixtures' READMEs). */
class UltraVioletDecodeTest {
    @Before fun setUp() {
        activeGame = GameKind.FIRERED
        fireRedHack = fireRedHackUltraViolet
    }

    @After fun tearDown() {
        fireRedHack = null
    }

    @Test
    fun `party, bag and location decode from the real save`() {
        val t = decodeNative("ultra_violet", NATIVE_ULTRA_VIOLET)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals("CHARMANDER", speciesName(mon.species))
        assertEquals(6, mon.level)
        assertEquals(10 to 21, mon.hp to mon.maxHp)
        assertEquals(listOf("SCRATCH", "GROWL"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        // FireRed's base stats (CHARMANDER 39/52/43/65/60/50) reproduce the stored stats.
        assertEquals(listOf(21, 10, 10, 13, 13, 11), mon.stats!!.stats)

        assertEquals(mapOf("POTION" to (1 to POCKET_ITEMS)), t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) })
        assertPocketsInRange(t)

        assertEquals(3 to 0, t.mapGroup to t.mapNum)
        assertEquals(0x58, t.regionMapSectionId)
        assertEquals("Pallet Town", activeMapSecData[t.regionMapSectionId]!!.name)
        assertEquals(3080L, t.money)
        assertFalse(t.inBattle)
        // The game's own POKéDEX flags: CHARMANDER caught, SQUIRTLE and PIDGEY seen.
        assertEquals(listOf(4, 7, 16), t.pokedex!!.seen.sorted())
        assertEquals(listOf(4), t.pokedex!!.caught.sorted())
    }

    @Test
    fun `a scripted wild battle - PIDGEY`() {
        val ram = FixtureMemoryReader.load("ultra_violet_battle")
        val t = decodeNative("ultra_violet_battle", NATIVE_ULTRA_VIOLET)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("CHARMANDER" to 6, speciesName(me.species) to me.level)
        assertEquals(10 to 21, me.hp to me.maxHp)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("PIDGEY" to 3, speciesName(foe.species) to foe.level)
        // Retail rev 0's battle handlers: the action menu waits.
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_ULTRA_VIOLET))
        val wild = decodePartyMon(ram.readCoreMemory(NATIVE_ULTRA_VIOLET.enemyParty, MON_STRUCT_SIZE), 0)!!
        assertEquals(16 to 3, wild.species to wild.level)
    }

    @Test
    fun `its own names and types over FireRed's`() {
        assertEquals("WHITE BALL", itemName(12))
        assertEquals("LAND DEED", itemName(347))
        assertEquals("Catacombs", activeMapSecData[0x9C]!!.name)
        // GYARADOS is WATER / DRAGON here.
        assertEquals(SpeciesTypes(11, 16), activeSpeciesTypeData[130])
        // ... and only here: plain FireRed keeps its own.
        fireRedHack = null
        assertEquals("PREMIER BALL", itemName(12))
        assertEquals(SpeciesTypes(11, 2), activeSpeciesTypeData[130])
    }
}
