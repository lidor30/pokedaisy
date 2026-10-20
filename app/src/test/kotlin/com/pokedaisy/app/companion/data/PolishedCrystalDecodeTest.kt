package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pokémon Polished Crystal 3.2.x (Game Boy Color, a Crystal-engine hack) on a new game played headless in 3.2.3 - see the
 * polished_crystal* fixtures' READMEs: the save booted in 3.2.3 (polished_crystal), 3.2.0 - 3.2.2 and faithful-3.2.3, a
 * wild battle on ROUTE 29 with its menus, and LYRA's battle in ELM's lab (polished_crystal_trainer).
 */
class PolishedCrystalDecodeTest {
    private fun read(fixture: String, cfg: Gen2Config = GEN2_POLISHED_CRYSTAL): Telemetry {
        activeGame = GameKind.POLISHED_CRYSTAL
        romLanguage = 'E'
        romGameCode = ""
        activeGen2 = cfg
        return readGen2Telemetry(FixtureMemoryReader.load(fixture), cfg)
    }

    @Test
    fun `party, money, bag and place - the same save in every build`() {
        for ((fixture, cfg) in listOf(
            "polished_crystal" to GEN2_POLISHED_CRYSTAL, "polished_crystal_3_2_2" to GEN2_POLISHED_CRYSTAL,
            "polished_crystal_3_2_1" to GEN2_POLISHED_CRYSTAL, "polished_crystal_3_2_0" to GEN2_POLISHED_CRYSTAL,
            "polished_crystal_faithful" to GEN2_POLISHED_CRYSTAL_FAITHFUL,
        )) {
            val t = read(fixture, cfg)
            assertFalse(fixture, t.inBattle)
            assertEquals(fixture, listOf("Cyndaquil", "Pidgey"), t.party.map { speciesName(it.species) })
            // Its charmap's 0x53-terminated wPartyMonNicknames.
            assertEquals(fixture, listOf("Cyndaquil", "Pidgey"), t.party.map { it.nickname })
            val cyndaquil = t.party[0]
            assertEquals(155 to 5, cyndaquil.species to cyndaquil.level)
            assertEquals(13 to 19, cyndaquil.hp to cyndaquil.maxHp)
            assertEquals(listOf("Tackle", "Leer"), cyndaquil.moves.filter { it != 0 }.map { lookupMove(it).name })
            assertEquals("Oran Berry", itemName(cyndaquil.heldItem))
            val pidgey = t.party[1]
            assertEquals(16 to 3, pidgey.species to pidgey.level)
            assertEquals(3 to 15, pidgey.hp to pidgey.maxHp)
            assertEquals(GENDER_SYMBOL_FEMALE, pidgey.genderSymbol)
            assertEquals(3000L, t.money)
            // MEDICINE lists with the items; BALLS their own pocket.
            assertEquals(listOf("Potion" to 1, "Poké Ball" to 3), t.items.map { itemName(it.itemId) to it.quantity })
            assertEquals(listOf(POCKET_ITEMS, POCKET_POKE_BALLS), t.items.map { it.pocket })
            assertEquals(24 to 3, t.mapGroup to t.mapNum)
            assertEquals(49 to 12, t.x to t.y)
            assertEquals("Route 29", lookupLocation(t.regionMapSectionId).mapSecName)
            assertEquals(1, t.playerGender)   // the middle photo: the girl
        }
    }

    @Test
    fun `its bag's other pockets - berries, key items and a bit per TM`() {
        val base = FixtureMemoryReader.load("polished_crystal")
        val cfg = GEN2_POLISHED_CRYSTAL
        val reader = object : MemoryReader {
            override fun readCoreMemory(addr: Long, len: Int): ByteArray = when (addr) {
                cfg.numBerries -> byteArrayOf(1, 0x44, 2, -1).copyOf(len)                    // 2 ORAN BERRY... by id
                cfg.numKeyItems -> byteArrayOf(1, 0).copyOf(len)                            // key item 1
                cfg.tmsHms -> ByteArray(len).also { it[0] = 0x01 }                          // TM01
                else -> base.readCoreMemory(addr, len)
            }
        }
        activeGame = GameKind.POLISHED_CRYSTAL
        activeGen2 = cfg
        val bag = readGen2Bag(reader, cfg)
        assertEquals(listOf(POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_BERRIES, POCKET_KEY_ITEMS, POCKET_TM_HM), bag.map { it.pocket })
        assertEquals(0x201 to 1, bag[3].itemId to bag[3].quantity)
        assertEquals(0x101, bag[4].itemId)
        assertTrue(itemName(bag[4].itemId), itemName(bag[4].itemId).startsWith("TM01"))
    }

    @Test
    fun `dex flags - by species, past the 8th bit (with the ROM)`() {
        val rom = File(File(RETAIL_ROM_DIR).parentFile, "gbc/polishedcrystal-3.2.3.gbc")
        assumeTrue("Polished Crystal 3.2.3 not on this machine", rom.isFile)
        Gen1Dex.rom = rom.readBytes()
        try {
            val cfg = GEN2_POLISHED_CRYSTAL.copy(pokedex = polishedCrystalPokedex(TelemetrySampler.POLISHED_CRYSTAL_V3_2_3_SHA1))
            val t = read("polished_crystal", cfg)
            assertEquals(setOf(16, 155), t.pokedex!!.caught)
            assertEquals(setOf(16, 152, 155), t.pokedex!!.seen)   // LYRA's CHIKORITA, seen in the lab
        } finally {
            Gen1Dex.rom = null
        }
    }

    @Test
    fun `wild battle - both battlers`() {
        val t = read("polished_crystal_battle")
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("Cyndaquil" to 5, speciesName(me.species) to me.level)
        assertEquals("Fire", typeName(me.type1))
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Sentret" to 2, speciesName(foe.species) to foe.level)
        assertEquals(13 to 13, foe.hp to foe.maxHp)
        assertEquals(listOf("Tackle", "Foresight"), foe.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertTrue(t.enemyParty.isEmpty())
    }

    @Test
    fun `trainer battle - LYRA's one, nicknamed`() {
        val t = read("polished_crystal_trainer")
        assertTrue(t.inBattle)
        assertEquals(listOf("Chikorita"), t.enemyParty.map { speciesName(it.species) })
        assertEquals(0, t.enemyActive)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Chikorita" to 5, speciesName(foe.species) to foe.level)
        assertEquals(20 to 20, foe.hp to foe.maxHp)
        assertEquals("Grass", typeName(foe.type1))
    }

    @Test
    fun `battle menus - which one waits, and its cursor`() {
        fun input(f: String) = readGen2BattleInput(FixtureMemoryReader.load(f), GEN2_POLISHED_CRYSTAL)
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, input("polished_crystal_battle"))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, input("polished_crystal_battle_menu"))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 0, input("polished_crystal_trainer"))
        assertEquals(BATTLE_INPUT_PARTY_OPEN to GB_PARTY_CANCEL, input("polished_crystal_battle_party"))
        assertEquals(BATTLE_INPUT_NONE to -1, input("polished_crystal"))
        // Crystal's cursor tile (0xED) is something else in Polished's font: Crystal's config sees no menu here.
        assertEquals(BATTLE_INPUT_BUSY to -1, readGen2BattleInput(FixtureMemoryReader.load("polished_crystal_battle_menu"),
            GEN2_POLISHED_CRYSTAL.copy(cursorTile = 0xED)))
    }

    @Test
    fun `the POKéMON switch - its menus, the party by key and the one out`() {
        fun r(f: String) = FixtureMemoryReader.load(f)
        val cfg = GEN2_POLISHED_CRYSTAL
        assertEquals(GbPartyMenu(submenu = false, cursor = GB_PARTY_CANCEL), readGen2PartyMenu(r("polished_crystal_battle_party"), cfg))
        // SWITCH / SUMMARY / CANCEL sit a column left of Crystal's, cursor on SUMMARY.
        assertEquals(GbPartyMenu(submenu = true, cursor = 1), readGen2PartyMenu(r("polished_crystal_battle_switch_menu"), cfg))
        assertEquals(GbPartyMenu(submenu = false, cursor = 1, forced = true), readGen2PartyMenu(r("polished_crystal_battle_faint"), cfg))
        for (f in listOf("polished_crystal_battle_menu", "polished_crystal_battle", "polished_crystal")) assertNull(f, readGen2PartyMenu(r(f), cfg))
        activeGame = GameKind.POLISHED_CRYSTAL
        activeGen2 = cfg
        val s = Gen2PartySwitch(cfg)
        val party = r("polished_crystal_battle_party")
        assertEquals(readGen2Telemetry(party, cfg).party.map { it.personality }, s.party(party).map { it.key })
        assertEquals(0, s.outSlot(party))
        assertEquals(0, s.party(r("polished_crystal_battle_faint"))[0].hp)
    }

    @Test
    fun `growth rates - its own numbering, Gen 3's curves`() {
        val t = read("polished_crystal")
        assertEquals(135L, expProgress(155, 5, t.party[0].exp)!!.levelStart)   // CYNDAQUIL, medium slow: Lv5 at 135
        assertEquals(179L, expProgress(155, 5, t.party[0].exp)!!.nextLevel)
        assertEquals(t.party[1].exp, expProgress(16, 3, t.party[1].exp)!!.levelStart)   // PIDGEY, caught at Lv3
        assertEquals(96L, expProgress(16, 4, 0)!!.levelStart)   // medium slow too
        assertEquals(3276L, expProgress(0x101, 16, 0)!!.levelStart)   // MISMAGIUS (species 0x101), fast: 4 x 16^3 / 5
    }

    @Test
    fun `faithful builds - their own types and moves`() {
        activeGame = GameKind.POLISHED_CRYSTAL
        activeGen2 = GEN2_POLISHED_CRYSTAL
        val normal = speciesTypeDataPolishedCrystalFaithfulChanges.keys.first()
        val polishedTypes = activeSpeciesTypeData.getValue(normal)
        activeGen2 = GEN2_POLISHED_CRYSTAL_FAITHFUL
        assertEquals(speciesTypeDataPolishedCrystalFaithfulChanges.getValue(normal), activeSpeciesTypeData.getValue(normal))
        assertTrue(polishedTypes != activeSpeciesTypeData.getValue(normal))
        activeGen2 = GEN2_POLISHED_CRYSTAL
    }

    @Test
    fun `detected by SHA-1, all sixteen builds`() {
        assertEquals(16, TelemetrySampler.POLISHED_CRYSTAL_SHA1S.size)
        for (sha in TelemetrySampler.POLISHED_CRYSTAL_SHA1S) {
            assertTrue(sha in TelemetrySampler.SUPPORTED_GB_SHA1S)
            assertEquals("Pokémon Polished Crystal", com.pokedaisy.app.GameTitles.BY_SHA1[sha])
        }
    }
}
