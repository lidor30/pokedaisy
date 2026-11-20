package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** NATIVE_SWORD_SHIELD: Galar on CFRU - FireRed rev 0's RAM, the hack's own tables (see the fixtures' READMEs). */
class SwordShieldDecodeTest {
    @Test
    fun `party, bag and location decode from the real save`() {
        activeGame = GameKind.SWORD_SHIELD
        val t = decodeNative("sword_shield_up", NATIVE_SWORD_SHIELD)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals(1105, mon.species)
        assertEquals("Scorbunny", speciesName(mon.species))
        assertEquals(5, mon.level)
        assertEquals(21 to 21, mon.hp to mon.maxHp)
        assertEquals(listOf("Growl", "Tackle"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals(listOf("Fire"), listOf(typeName(activeSpeciesTypeData.getValue(1105).type1)))
        // Its base stats (SCORBUNNY 50/71/40/69/40/40) and perfect IVs reproduce the stored stats.
        assertEquals(listOf(31, 31, 31, 31, 31, 31), mon.stats!!.ivs)
        assertEquals(listOf(21, 13, 11, 13, 10, 9), mon.stats!!.stats)

        // What its bag screen shows (headless): Potion x2, Soothe Bell under Items, Old Rod under Key Items.
        assertEquals(
            mapOf("Potion" to (2 to POCKET_ITEMS), "Soothe Bell" to (1 to POCKET_ITEMS), "Old Rod" to (1 to POCKET_KEY_ITEMS)),
            t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) },
        )
        assertPocketsInRange(t)

        assertEquals(4 to 6, t.mapGroup to t.mapNum)
        assertEquals(0x58, t.regionMapSectionId)
        assertEquals("Postwick", activeMapSecData[t.regionMapSectionId]!!.name)
        assertEquals(5000L, t.money)
        assertFalse(t.inBattle)
        // CFRU's flags in SaveBlock1: GROOKEY / SCORBUNNY / WOOLOO / ZACIAN seen, SCORBUNNY caught (No. 813).
        assertEquals(listOf(810, 813, 831, 888), t.pokedex!!.seen.sorted())
        assertEquals(listOf(813), t.pokedex!!.caught.sorted())
    }

    @Test
    fun `a scripted wild battle - WOOLOO`() {
        activeGame = GameKind.SWORD_SHIELD
        val ram = FixtureMemoryReader.load("sword_shield_up_battle")
        val t = decodeNative("sword_shield_up_battle", NATIVE_SWORD_SHIELD)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("Scorbunny" to 5, speciesName(me.species) to me.level)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Wooloo" to 4, speciesName(foe.species) to foe.level)
        assertEquals(listOf("Growl", "Tackle", "Defense Curl"), foe.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_SWORD_SHIELD))
        val wild = decodePartyMon(ram.readCoreMemory(NATIVE_SWORD_SHIELD.enemyParty, MON_STRUCT_SIZE), 0)!!
        assertEquals(1123 to 4, wild.species to wild.level)
    }

    /** The Casual / Performance patches: the base build's save (and the same battle) in each, read alike. */
    @Test
    fun `its three patched releases read the same`() {
        activeGame = GameKind.SWORD_SHIELD
        for (patch in listOf("casual", "performance", "casual_performance")) {
            val t = decodeNative("sword_shield_up_$patch", NATIVE_SWORD_SHIELD)
            assertEquals(patch, listOf("Scorbunny 5 21/21"), t.party.map { "${speciesName(it.species)} ${it.level} ${it.hp}/${it.maxHp}" })
            assertEquals(patch, setOf("Potion", "Soothe Bell", "Old Rod"), t.items.map { itemName(it.itemId) }.toSet())
            assertEquals(patch, 5000L, t.money)
            assertEquals(patch, "Postwick", activeMapSecData[t.regionMapSectionId]!!.name)
            val ram = FixtureMemoryReader.load("sword_shield_up_${patch}_battle")
            val b = decodeNative("sword_shield_up_${patch}_battle", NATIVE_SWORD_SHIELD)
            assertEquals(patch, "Wooloo" to 4, speciesName(b.battleMons[BATTLE_POS_OPPONENT_LEFT].species) to b.battleMons[BATTLE_POS_OPPONENT_LEFT].level)
            assertEquals(patch, 0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_SWORD_SHIELD))
        }
    }

    @Test
    fun `its tables are where the config says`() {
        activeGame = GameKind.SWORD_SHIELD
        for (file in ROMS) {
            val rom = RomFileReader.load(RETAIL_ROM_DIR + file) ?: continue
            assertTrue(file, pokedexMatchesRom(rom, POKEDEX_SWORD_SHIELD))
            assertTrue(file, guideTablesMatchRom(rom, GUIDE_TABLES_SWORD_SHIELD))
            assertTrue(file, RomItemText.matchesRom(rom, NATIVE_SWORD_SHIELD.itemDescs!!))
        }
        assumeTrue("no Sword and Shield ROM here", ROMS.any { java.io.File(RETAIL_ROM_DIR + it).isFile })
    }

    private companion object {
        val ROMS = listOf(
            "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba",
            "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2) (Casual Patch).gba",
            "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2) (Performance Patch).gba",
            "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2) (Casual and Performance Patch).gba",
        )
    }
}
