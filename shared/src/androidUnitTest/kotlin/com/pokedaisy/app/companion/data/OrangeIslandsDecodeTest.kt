package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_ORANGE_ISLANDS: retail FireRed rev 0's RAM, the hack's own tables (see the fixtures' READMEs). */
class OrangeIslandsDecodeTest {
    @Test
    fun `party, bag and location decode from the real save`() {
        activeGame = GameKind.ORANGE_ISLANDS
        val t = decodeNative("orange_islands", NATIVE_ORANGE_ISLANDS)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals("PIKACHU", speciesName(mon.species))
        assertEquals(10, mon.level)
        assertEquals(29 to 29, mon.hp to mon.maxHp)
        assertEquals(1062L, mon.exp)
        assertEquals(listOf("THUNDERSHOCK", "GROWL", "TAIL WHIP", "THUNDER WAVE"), mon.moves.map { lookupMove(it).name })
        // Its own base stats (PIKACHU 45/50/40/90/55/40) reproduce the stored stats.
        assertEquals(listOf(29, 18, 15, 23, 18, 15), mon.stats!!.stats)
        assertEquals(GENDER_SYMBOL_MALE, mon.genderSymbol)

        assertEquals(
            mapOf("POTION" to (1 to POCKET_ITEMS), "ORANGE PASS" to (1 to POCKET_KEY_ITEMS), "OLD ROD" to (1 to POCKET_KEY_ITEMS)),
            t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) },
        )
        assertPocketsInRange(t)

        assertEquals(3 to 1, t.mapGroup to t.mapNum)
        assertEquals(0x65, t.regionMapSectionId)
        assertEquals("Valencia Island", activeMapSecData[t.regionMapSectionId]!!.name)
        assertEquals(2000L, t.money)
        assertFalse(t.inBattle)
    }

    @Test
    fun `a scripted wild battle - its CRYSTAL ONIX`() {
        activeGame = GameKind.ORANGE_ISLANDS
        val ram = FixtureMemoryReader.load("orange_islands_battle")
        val t = decodeNative("orange_islands_battle", NATIVE_ORANGE_ISLANDS)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("PIKACHU" to 10, speciesName(me.species) to me.level)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(409, foe.species)
        assertEquals("ONIX" to 8, speciesName(foe.species) to foe.level)
        // FOE DETAILS' battle fields (vanilla layout): the player's Pikachu holds a Light Ball (202)
        // and has Static (9); battle stats are the party struct's, nothing staged yet.
        assertEquals(9 to 202, me.ability to me.item)
        assertEquals(t.party[0].stats!!.stats.drop(1), me.stats!!.toList())
        assertEquals(List(7) { 6 }, foe.statStages!!.toList())
        assertEquals(listOf(14, 32, 17, 11, 12), foe.stats!!.toList())
        // The hack's 24th type: CRYSTL (23) / GROUND.
        assertEquals(listOf("Crystal", "Ground"), listOf(typeName(foe.type1), typeName(foe.type2)))
        // WATER doesn't touch CRYSTL; GRASS is super effective on both its types.
        assertEquals(0, typeMultiplierPct(11, foe.type1, foe.type2))
        assertEquals(400, typeMultiplierPct(12, foe.type1, foe.type2))
        // Retail rev 0's battle handlers: the action menu waits.
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_ORANGE_ISLANDS))
        val wild = decodePartyMon(ram.readCoreMemory(NATIVE_ORANGE_ISLANDS.enemyParty, MON_STRUCT_SIZE), 0)!!
        assertEquals(409 to 8, wild.species to wild.level)
    }

    @Test
    fun `its names and chart come from its own tables`() {
        activeGame = GameKind.ORANGE_ISLANDS
        assertEquals("CRYSTALTHROW", lookupMove(353).name)
        assertEquals("Crystal", typeName(lookupMove(354).type))
        assertEquals("GS BALL", itemName(270))
        // FireRed's own ids elsewhere, its rebalanced moves (TACKLE is 40 power here).
        assertEquals(40, moveDataOrangeIslands.getValue(33).power)
    }
}
