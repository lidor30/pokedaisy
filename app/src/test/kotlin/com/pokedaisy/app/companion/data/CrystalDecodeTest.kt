package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Crystal (Game Boy Color) on a new game played headless - see the crystal fixtures' READMEs:
 * the same save booted in rev 1 (crystal), rev 0 (crystal_rev0) and Australia (crystal_au), and a
 * wild battle in rev 1 (crystal_battle, crystal_battle_menu).
 */
class CrystalDecodeTest {
    private fun read(fixture: String): Telemetry {
        activeGame = GameKind.CRYSTAL
        romLanguage = 'E'
        romGameCode = ""
        return readGen2Telemetry(FixtureMemoryReader.load(fixture), GEN2_CRYSTAL)
    }

    @Test
    fun `party - Gen 2's big-endian struct, held item, gender from the DVs`() {
        for (fixture in listOf("crystal", "crystal_rev0", "crystal_au")) {
            val t = read(fixture)
            assertFalse(fixture, t.inBattle)
            assertEquals(fixture, listOf("CHIKORITA", "PIDGEY"), t.party.map { speciesName(it.species) })
            // wPartyMonNicknames: the stored names when neither was nicknamed.
            assertEquals(fixture, listOf("CHIKORITA", "PIDGEY"), t.party.map { it.nickname })
            val chikorita = t.party[0]
            assertEquals(152 to 5, chikorita.species to chikorita.level)
            assertEquals(19 to 19, chikorita.hp to chikorita.maxHp)
            assertEquals(listOf("TACKLE", "GROWL", "-", "-"), chikorita.moves.map { lookupMove(it).name })
            assertEquals(listOf(35, 40, 0, 0), chikorita.pp.toList())
            assertEquals("BERRY", itemName(chikorita.heldItem))
            // DVs 0x62 0x6A: (Attack 6 << 4 | Speed 6) = 0x66 over GENDER_F12_5 (31): male, as the game shows it.
            assertEquals(GENDER_SYMBOL_MALE, chikorita.genderSymbol)
            val pidgey = t.party[1]
            assertEquals(16 to 2, pidgey.species to pidgey.level)
            assertEquals(14 to 14, pidgey.hp to pidgey.maxHp)
            assertEquals(0, pidgey.heldItem)
            // DVs 0x13 0x07: 0x10 <= GENDER_F50 (127): female (the battle showed ♀).
            assertEquals(GENDER_SYMBOL_FEMALE, pidgey.genderSymbol)
        }
    }

    @Test
    fun `money, bag pockets, place and play time`() {
        val t = read("crystal")
        assertEquals(3000L, t.money)
        assertEquals(listOf("POTION" to 1, "POKé BALL" to 4), t.items.map { itemName(it.itemId) to it.quantity })
        assertEquals(listOf(POCKET_ITEMS, POCKET_POKE_BALLS), t.items.map { it.pocket })
        assertEquals(24 to 3, t.mapGroup to t.mapNum)
        assertEquals(2, t.regionMapSectionId)
        assertEquals("ROUTE 29", lookupLocation(t.regionMapSectionId).mapSecName)
        assertEquals(46 to 11, t.x to t.y)
        assertEquals(0, t.playerGender)
        assertEquals(((4 * 60L) + 16) * 60 + 29, t.frameCounter)   // 0:04:16, frame 29
        val dex = t.pokedex!!
        assertEquals(setOf(16, 152), dex.caught)
        assertEquals(setOf(16, 152), dex.seen)
    }

    @Test
    fun `before a save loads - no party, no bag out of leftover WRAM`() {
        // Power-on WRAM (zeros headless, leftovers in the app: the first sample once listed 31 TMs) isn't a game.
        activeGame = GameKind.CRYSTAL
        for (fill in listOf<(Long) -> Byte>({ 0 }, { a -> (a * 37 + 11).toByte() })) {
            val r = object : MemoryReader {
                override fun readCoreMemory(addr: Long, size: Int) = ByteArray(size) { fill(addr + it) }
            }
            val t = readGen2Telemetry(r, GEN2_CRYSTAL)
            assertTrue(t.party.isEmpty())
            assertTrue(t.items.isEmpty())
        }
    }

    @Test
    fun `battle - both battlers, Gen 2 types`() {
        val t = read("crystal_battle")
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(152 to 5, me.species to me.level)
        assertEquals("GRASS", typeName(me.type1))
        assertEquals(19 to 19, me.hp to me.maxHp)
        assertEquals("BERRY", itemName(me.item))
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("SENTRET", speciesName(foe.species))
        assertEquals(3, foe.level)
        assertEquals("NORMAL", typeName(foe.type1))
        assertEquals(15 to 15, foe.hp to foe.maxHp)
        assertTrue(t.enemyParty.isEmpty()) // a wild battle has no FOE TEAM
        assertEquals(setOf(16, 152, 161), t.pokedex!!.seen)
    }

    @Test
    fun `battle menus - which one waits, and its cursor`() {
        // The move list with the cursor on GROWL (the 2nd move).
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen2BattleInput(FixtureMemoryReader.load("crystal_battle"), GEN2_CRYSTAL))
        // The battle menu with the cursor on RUN: column 1, row 1.
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen2BattleInput(FixtureMemoryReader.load("crystal_battle_menu"), GEN2_CRYSTAL))
        assertEquals(BATTLE_INPUT_ACTION_SELECT, read("crystal_battle_menu").battleInputState)
        // On the field: no battle.
        assertEquals(BATTLE_INPUT_NONE to -1, readGen2BattleInput(FixtureMemoryReader.load("crystal"), GEN2_CRYSTAL))
    }

    @Test
    fun `Gen 2 type chart and its own types`() {
        activeGame = GameKind.CRYSTAL
        val dark = activeTypeNames.entries.first { it.value == "DARK" }.key
        val psychic = activeTypeNames.entries.first { it.value == "PSYCHIC" }.key
        val steel = activeTypeNames.entries.first { it.value == "STEEL" }.key
        assertEquals(0, activeTypeEffectiveness[psychic * 100 + dark])
        assertEquals(200, activeTypeEffectiveness[dark * 100 + psychic])
        assertEquals(50, activeTypeEffectiveness[dark * 100 + steel])
        assertEquals("DARK", typeName(lookupMove(44).type)) // BITE
    }

    @Test
    fun `growth rates - Gen 3's curves, so the summary's EXP bar holds`() {
        // gen3GrowthRates (FireRed's) for the first 251 equal Crystal's BaseData byte for byte.
        for (s in 1..251) assertEquals("species $s", gen2GrowthRatesCrystal[s], gen3GrowthRates[s].toInt())
        // And the curves: a starter is given at Lv5 with Medium Slow's Lv5 total, the PIDGEY caught at Lv2 with its Lv2.
        val t = read("crystal")
        assertEquals(135L to 9L, t.party[0].exp to t.party[1].exp)
        assertEquals(135L, expProgress(152, 5, 135)!!.levelStart)
        assertEquals(9L, expProgress(16, 2, 9)!!.levelStart)
    }
}
