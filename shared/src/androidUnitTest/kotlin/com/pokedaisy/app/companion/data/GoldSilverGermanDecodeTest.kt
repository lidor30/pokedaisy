package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * German Gold / Silver (Goldene / Silberne Edition) on the gold fixture's game written out as its save - see the gold_de /
 * silver_de fixtures' READMEs: English Gold's RAM, their own names (German, from the cart), Kristall's battle menu columns;
 * and, from the player's own carts (skipped without them), their dex pages and art.
 */
class GoldSilverGermanDecodeTest {
    private val dir = File(File(RETAIL_ROM_DIR).parentFile, "gbc")
    private fun romOf(name: String): ByteArray {
        val f = File(dir, name)
        assumeTrue("$name not on this machine", f.isFile)
        return f.readBytes()
    }
    private fun golden() = romOf("Pokemon - Goldene Edition (Germany) (SGB Enhanced) (GB Compatible).gbc")
    private fun silberne() = romOf("Pokemon - Silberne Edition (Germany) (SGB Enhanced) (GB Compatible).gbc")
    private fun gold() = romOf("Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc")
    private fun silver() = romOf("Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc")

    private fun read(fixture: String, cfg: Gen2Config = GEN2_GOLD_DE): Telemetry {
        german(cfg)
        return readGen2Telemetry(FixtureMemoryReader.load(fixture), cfg)
    }

    private fun german(cfg: Gen2Config = GEN2_GOLD_DE) {
        activeGame = if (cfg === GEN2_SILVER_DE) GameKind.SILVER else GameKind.GOLD
        activeGen2 = cfg
        romLanguage = 'D'
        romGameCode = cfg.gameCode ?: ""
    }

    @After
    fun english() {
        activeGen2 = GEN2_CRYSTAL
        romLanguage = 'E'
        romGameCode = ""
    }

    @Test
    fun `party, bag and place in the game's own words - in Gold and in Silver`() {
        for ((fixture, cfg) in listOf("gold_de" to GEN2_GOLD_DE, "silver_de" to GEN2_SILVER_DE)) {
            val t = read(fixture, cfg)
            assertFalse(fixture, t.inBattle)
            assertEquals(fixture, listOf("FEURIGEL", "TAUBSI"), t.party.map { speciesName(it.species) })
            val feurigel = t.party[0]
            assertEquals(155 to 5, feurigel.species to feurigel.level)
            assertEquals(17 to 20, feurigel.hp to feurigel.maxHp)
            assertEquals(listOf("TACKLE", "SILBERBLICK", "-", "-"), feurigel.moves.map { lookupMove(it).name })
            assertEquals("BEERE", itemName(feurigel.heldItem))
            assertEquals(3000L, t.money)
            assertEquals(listOf("TRANK" to 1, "POKéBALL" to 3), t.items.map { itemName(it.itemId) to it.quantity })
            assertEquals(24 to 3, t.mapGroup to t.mapNum)
            assertEquals("ROUTE 29", lookupLocation(t.regionMapSectionId).mapSecName)
            assertEquals("NEUBORKIA", lookupLocation(1).mapSecName)
            assertEquals(setOf(16, 155), t.pokedex!!.caught)
            // Moves keep English Gold's types and powers under their German names.
            assertEquals("NORMAL" to 35, typeName(lookupMove(33).type) to lookupMove(33).power)
            assertEquals("DRACHEN", typeLabel("DRAGON"))
            assertEquals("FEUER", typeLabel("FIRE"))
        }
    }

    @Test
    fun `battle - both battlers, the menus at the German columns`() {
        val t = read("gold_de_battle")
        assertTrue(t.inBattle)
        assertEquals("FEURIGEL", speciesName(t.battleMons[BATTLE_POS_PLAYER_LEFT].species))
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("TAUBSI" to 2, speciesName(foe.species) to foe.level)
        val silver = read("silver_de_battle", GEN2_SILVER_DE).battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("WIESOR" to 3, speciesName(silver.species) to silver.level)
        val r = { f: String -> FixtureMemoryReader.load(f) }
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen2BattleInput(r("gold_de_battle"), GEN2_GOLD_DE))
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen2BattleInput(r("silver_de_battle"), GEN2_SILVER_DE))
        // KMPF / PkMn / BEUTEL / FLUCHT from column 5 (English's 9): the cursor on FLUCHT.
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen2BattleInput(r("gold_de_battle_menu"), GEN2_GOLD_DE))
        assertEquals(BATTLE_INPUT_BUSY to -1, readGen2BattleInput(r("gold_de_battle_menu"), GEN2_GOLD))
        assertEquals(BATTLE_INPUT_NONE to -1, readGen2BattleInput(r("gold_de"), GEN2_GOLD_DE))
    }

    @Test
    fun `the POKéMON switch - party menu, TAUSCH menu from column 10, the pick after a faint`() {
        val r = { f: String -> FixtureMemoryReader.load(f) }
        assertEquals(GbPartyMenu(submenu = false, cursor = GB_PARTY_CANCEL), readGen2PartyMenu(r("gold_de_battle_party"), GEN2_GOLD_DE))
        assertEquals(GbPartyMenu(submenu = true, cursor = 1), readGen2PartyMenu(r("gold_de_battle_switch_menu"), GEN2_GOLD_DE))
        assertNull(readGen2PartyMenu(r("gold_de_battle_switch_menu"), GEN2_GOLD))   // English's column 12: not it
        assertEquals(GbPartyMenu(submenu = false, cursor = 1, forced = true), readGen2PartyMenu(r("gold_de_battle_faint"), GEN2_GOLD_DE))
        for (f in listOf("gold_de_battle_menu", "gold_de_battle", "gold_de")) assertNull(f, readGen2PartyMenu(r(f), GEN2_GOLD_DE))
        german()
        val s = Gen2PartySwitch(GEN2_GOLD_DE)
        assertEquals(listOf(17, 13), s.party(r("gold_de_battle_party")).map { it.hp })
        assertEquals(listOf(0, 13), s.party(r("gold_de_battle_faint")).map { it.hp })
        assertEquals(0, s.outSlot(r("gold_de_battle_faint")))
    }

    @Test
    fun `dex pages from the cart - metric, each version its own`() {
        for (rom in listOf(golden(), silberne())) {
            Gen1Dex.rom = rom
            try {
                val e = Gen2Dex.entry(GEN2_DEX_GOLD_SILVER_DE, 155)!!
                assertEquals("FEUERMAUS", e.category)
                assertEquals(5 to 79, e.heightDm to e.weightHg)
                assertEquals(listOf(39, 52, 43, 60, 50, 65), e.baseStats)
                for (s in 1..251) assertTrue("entry $s", Gen2Dex.entry(GEN2_DEX_GOLD_SILVER_DE, s) != null)
                assertTrue(Gen2Dex.itemDescription(GEN2_DEX_GOLD_SILVER_DE, 0x12).isNotEmpty())   // TRANK
            } finally {
                Gen1Dex.rom = null
            }
        }
    }

    @Test
    fun `art - English Gold's and Silver's, found where the German carts keep them`() {
        for ((pair, tables) in listOf((golden() to gold()) to (Gen2Art.GOLD_DE to Gen2Art.GOLD), (silberne() to silver()) to (Gen2Art.SILVER_DE to Gen2Art.SILVER))) {
            val (de, en) = pair
            val (deT, enT) = tables
            for (i in 0..1) assertTrue("town map $i", Gen2Art.townMap(de, deT, i)!!.argb.contentEquals(Gen2Art.townMap(en, enT, i)!!.argb))
            val enIcons = Gen2Art.icons(en, enT)
            val deIcons = Gen2Art.icons(de, deT)
            assertEquals(enIcons.keys, deIcons.keys)
            for ((dex, img) in enIcons) assertTrue("icon $dex", img.argb.contentEquals(deIcons.getValue(dex).argb))
        }
    }
}
