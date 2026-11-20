package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * NATIVE_GS_CHRONICLES: Johto and Kanto on CFRU - FireRed rev 0's RAM, the hack's own tables. No save of the
 * user's: the fixtures are a new game played headless (see their READMEs), checked against the game's own screens.
 */
class GsChroniclesDecodeTest {
    @Test
    fun `party, bag and location decode from the new game's save`() {
        activeGame = GameKind.GS_CHRONICLES
        val t = decodeNative("gs_chronicles", NATIVE_GS_CHRONICLES)
        assertEquals(
            listOf("Cyndaquil 5 19/19", "Totodile 6 23/23", "Chikorita 7 26/26"),
            t.party.map { "${speciesName(it.species)} ${it.level} ${it.hp}/${it.maxHp}" },
        )
        val mon = t.party[0]
        assertEquals(155, mon.species)
        assertEquals(listOf("Tackle", "Leer", "Defense Curl"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals(listOf("Fire"), listOf(typeName(activeSpeciesTypeData.getValue(155).type1)))
        // Its summary page: HP 19, ATTACK 9, DEFENSE 10, SP. ATK 12, SP. DEF 11, SPEED 11 (the struct's order: speed 4th).
        assertEquals(listOf(19, 9, 10, 11, 12, 11), mon.stats!!.stats)
        // Its bag screen: Potion x5, Awakening x3 under Items, Poké Ball x10 under Poké Balls.
        assertEquals(
            mapOf("Potion" to 5, "Awakening" to 3, "Poké Ball" to 10),
            t.items.associate { itemName(it.itemId) to it.quantity },
        )
        assertPocketsInRange(t)
        assertEquals(0x58, t.regionMapSectionId)
        assertEquals("New Bark Town", activeMapSecData[t.regionMapSectionId]!!.name)
        assertEquals(13000L, t.money)
        assertFalse(t.inBattle)
        // CFRU's flags in SaveBlock1, as the game's POKéDEX counts them: Seen 3, Owned 3.
        assertEquals(listOf(152, 155, 158), t.pokedex!!.seen.sorted())
        assertEquals(listOf(152, 155, 158), t.pokedex!!.caught.sorted())
        assertFalse(t.pokedex!!.national) // its POKéDEX has the Johto list only
    }

    /** A second capture, the party reordered in the game's own menu: the same addresses hold. */
    @Test
    fun `a second capture with the party swapped`() {
        activeGame = GameKind.GS_CHRONICLES
        val t = decodeNative("gs_chronicles_swapped", NATIVE_GS_CHRONICLES)
        assertEquals(listOf("Chikorita", "Totodile", "Cyndaquil"), t.party.map { speciesName(it.species) })
        assertEquals(setOf("Potion", "Awakening", "Poké Ball"), t.items.map { itemName(it.itemId) }.toSet())
        assertEquals("New Bark Town", activeMapSecData[t.regionMapSectionId]!!.name)
        assertEquals(13000L, t.money)
    }

    @Test
    fun `a scripted wild battle - SENTRET`() {
        activeGame = GameKind.GS_CHRONICLES
        val ram = FixtureMemoryReader.load("gs_chronicles_battle")
        val t = decodeNative("gs_chronicles_battle", NATIVE_GS_CHRONICLES)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("Cyndaquil" to 5, speciesName(me.species) to me.level)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Sentret" to 3, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_GS_CHRONICLES))
        val wild = decodePartyMon(ram.readCoreMemory(NATIVE_GS_CHRONICLES.enemyParty, MON_STRUCT_SIZE), 0)!!
        assertEquals(161 to 3, wild.species to wild.level)
    }

    @Test
    fun `its tables are where the config says`() {
        activeGame = GameKind.GS_CHRONICLES
        val rom = RomFileReader.load(RETAIL_ROM_DIR + ROM)
        assumeTrue("no GS Chronicles ROM here", rom != null)
        assertTrue(pokedexMatchesRom(rom!!, POKEDEX_GS_CHRONICLES))
        assertTrue(guideTablesMatchRom(rom, GUIDE_TABLES_GS_CHRONICLES))
        assertTrue(RomItemText.matchesRom(rom, NATIVE_GS_CHRONICLES.itemDescs!!))
        // USE / GIVE / TAKE: FireRed rev 0's functions (CRC-checked) over its own gItems.
        val bytes = java.io.File(RETAIL_ROM_DIR + ROM).readBytes()
        val support = fieldItemSupport(fieldItemsForGame(GameKind.GS_CHRONICLES, "BPRE", 0)!!) { a, n ->
            bytes.copyOfRange((a - 0x08000000L).toInt(), (a - 0x08000000L).toInt() + n)
        }!!
        assertTrue(support.canUse(13)) // POTION
        assertTrue(support.canGive(13))
        assertFalse(support.canGive(262)) // OLD ROD, a key item
    }

    private companion object {
        const val ROM = "Pokemon GS Chronicles (v2.7.6).gba"
    }
}
