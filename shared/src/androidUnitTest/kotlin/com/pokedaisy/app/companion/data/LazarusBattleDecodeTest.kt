package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_LAZARUS mid wild battle (a headless scripted battle, see the fixture's README). */
class LazarusBattleDecodeTest {
    private val t = decodeNative("lazarus_battle", NATIVE_LAZARUS)

    @Test
    fun `battle is on and both battlers decode`() {
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]!!
        assertEquals(737, me.species) // Charjabug, "Astaroth"
        assertEquals(31, me.level)
        assertEquals(85 to 85, me.hp to me.maxHp)
        assertEquals(7 to 14, me.type1 to me.type2) // Bug / Electric (expansion ids)
        assertEquals(t.party[0].moves.toList(), me.moves.toList())
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]!!
        assertEquals(25, foe.species) // Pikachu
        assertEquals(5, foe.level)
        assertEquals(20 to 20, foe.hp to foe.maxHp)
        assertEquals(14 to 14, foe.type1 to foe.type2) // Electric
        assertEquals(listOf(39, 84, 45, 0), foe.moves.toList()) // Tail Whip, Thunder Shock, Growl
        assertEquals(listOf(30, 30, 40, 0), foe.pp.toList())
        // FOE DETAILS' battle fields (expansion layout: u16 ability, item at +0x30).
        assertEquals(217, me.ability) // Battery
        assertEquals(9 to 0, foe.ability to foe.item) // Static, nothing held
        assertEquals(t.party[0].stats!!.stats.drop(1), me.stats!!.toList())
        assertEquals(listOf(11, 9, 15, 11, 9), foe.stats!!.toList())
    }

    @Test
    fun `touch battle control sees the action menu`() {
        // gBattlerControllerFuncs[0] is HandleInputChooseAction ("What will Astaroth do?").
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(FixtureMemoryReader.load("lazarus_battle"), NATIVE_LAZARUS))
    }

    @Test
    fun `the wild foe sits in gEnemyParty`() {
        // A wild battle shows no FOE TEAM (trainer battles only), so read the address itself.
        assertTrue(t.enemyParty.isEmpty())
        val raw = FixtureMemoryReader.load("lazarus_battle").readCoreMemory(NATIVE_LAZARUS.enemyParty, MON_STRUCT_SIZE)
        val foe = decodePartyMon(raw, 0)!!
        assertEquals(25 to 5, foe.species to foe.level)
    }

    @Test
    fun `Lazarus's own move and type tables name the battle`() {
        activeGame = GameKind.LAZARUS
        assertEquals("Thunder Shock", lookupMove(84).name)
        assertEquals(TYPE_ELECTRIC_EXPANSION, lookupMove(84).type)
        assertEquals("Electric", activeTypeNames[14])
    }
}

private const val TYPE_ELECTRIC_EXPANSION = 14
