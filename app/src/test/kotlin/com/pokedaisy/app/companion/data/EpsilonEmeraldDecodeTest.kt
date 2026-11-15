package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NATIVE_EPSILON_EMERALD (Pokémon Epsilon Emerald v1.0.0): retail Emerald's save, which it loads as it is (the
 * `epsilon_emerald` fixtures' READMEs). What each check expects is what the game's own party, bag and battle screens showed.
 */
class EpsilonEmeraldDecodeTest {
    private val cfg = NATIVE_EPSILON_EMERALD

    private fun use() {
        activeGame = GameKind.EMERALD
        emeraldHack = emeraldHackFor(cfg)
    }

    @After fun tearDown() {
        emeraldHack = null
    }

    @Test
    fun `the save's party, bag, money and place`() {
        use()
        val t = decodeNative("epsilon_emerald", cfg)
        // Its party screen: KADABRA Lv36, MANECTRIC Lv36, TRAPINCH Lv27, BLAZIKEN Lv44, PELIPPER Lv43, DOTS (a SEEDOT) Lv11.
        assertEquals(
            listOf("Kadabra" to 36, "Manectric" to 36, "Trapinch" to 27, "Blaziken" to 44, "Pelipper" to 43, "Seedot" to 11),
            t.party.map { speciesName(it.species) to it.level },
        )
        assertEquals(listOf(81, 108, 65, 145, 120, 30), t.party.map { it.maxHp })
        val bag = t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) }
        // Its bag screen, pocket by pocket: the Items pocket ends at SILK SCARF (27), the old key items past it left out.
        assertEquals(27, t.items.count { it.pocket == POCKET_ITEMS })
        assertEquals(2 to POCKET_ITEMS, bag["Potion"])
        assertEquals(1 to POCKET_ITEMS, bag["Silk Scarf"])
        assertEquals(listOf("Great Ball" to 6, "Poké Ball" to 2, "Premier Ball" to 1, "Nest Ball" to 3, "Ultra Ball" to 6),
            t.items.filter { it.pocket == POCKET_POKE_BALLS }.map { itemName(it.itemId) to it.quantity })
        // TMs & HMs: TM05 .. TM50 (18) and HM01 .. HM06, no counts.
        val tms = t.items.filter { it.pocket == POCKET_TM_HM }.map { itemName(it.itemId) }
        assertEquals(24, tms.size)
        assertEquals(listOf("TM05", "TM08", "TM09"), tms.take(3))
        assertEquals(listOf("TM50", "HM01", "HM02", "HM03", "HM04", "HM05", "HM06"), tms.takeLast(7))
        assertEquals(4 to POCKET_BERRIES, bag["Cheri Berry"])
        assertEquals(listOf("Wailmer Pail", "Dowsing Mchn", "Soot Sack", "Good Rod", "Devon Scope", "Magma Emblem", "Fame Checker",
            "Mega Bracelet"), t.items.filter { it.pocket == POCKET_KEY_ITEMS }.map { itemName(it.itemId) })
        assertPocketsInRange(t)
        assertEquals(185092L, t.money)
        assertEquals(0, t.playerGender)
        assertFalse(t.inBattle)
        // ROUTE 114: Emerald's map ids and section.
        assertEquals(0 to 29, t.mapGroup to t.mapNum)
        assertEquals(0x1D, t.regionMapSectionId)
    }

    @Test
    fun `a scripted wild battle`() {
        use()
        val ram = FixtureMemoryReader.load("epsilon_emerald_battle")
        val t = decodeNative("epsilon_emerald_battle", cfg)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("Kadabra" to 36, speciesName(me.species) to me.level)
        assertEquals(81 to 81, me.hp to me.maxHp)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Zigzagoon" to 5, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        assertTrue(t.enemyParty.isEmpty())
        assertEquals(listOf(288 to 5), t.battleFoes.map { it.species to it.level })
    }

    @Test
    fun `its names, types and chart`() {
        use()
        assertEquals("Obstagoon", speciesName(445))
        assertEquals("Roserade", speciesName(412))
        assertEquals("Roost", lookupMove(355).name)
        assertEquals("Hydro Steam", lookupMove(828).name)
        assertEquals("Mega Bracelet", itemName(348))
        assertEquals("Fairy", typeName(18))
        assertEquals("Ice Oasis", activeMapSecData[75]?.name)
        assertFalse(movesAreVanilla)
        fun types(species: Int) = activeSpeciesTypeData.getValue(species).let { typeName(it.type1) to typeName(it.type2) }
        assertEquals("Fairy" to "Fairy", types(35))     // CLEFAIRY
        assertEquals("Grass" to "Poison", types(1))
        // Its chart: Fairy beats Dragon, Dragon doesn't touch it.
        assertEquals(200, activeTypeEffectiveness[18 * 100 + 16])
        assertEquals(0, activeTypeEffectiveness[16 * 100 + 18])
    }
}
