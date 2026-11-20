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
 * German Crystal (Kristall) on the crystal fixture's game written out as its save - see the crystal_de fixtures' READMEs:
 * English's RAM, its own names (German, from the cart), its own battle menu columns; and, from the player's own carts
 * (skipped without them), its dex pages, art and field code.
 */
class CrystalGermanDecodeTest {
    private val dir = File(File(RETAIL_ROM_DIR).parentFile, "gbc")
    private fun romOf(name: String): ByteArray {
        val f = File(dir, name)
        assumeTrue("$name not on this machine", f.isFile)
        return f.readBytes()
    }
    private fun kristall() = romOf("Pokemon - Kristall-Edition (Germany).gbc")
    private fun crystal() = romOf("Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc")

    private fun read(fixture: String): Telemetry {
        german()
        return readGen2Telemetry(FixtureMemoryReader.load(fixture), GEN2_CRYSTAL_DE)
    }

    private fun german() {
        activeGame = GameKind.CRYSTAL
        activeGen2 = GEN2_CRYSTAL_DE
        romLanguage = 'D'
        romGameCode = "BYTD"
    }

    @After
    fun english() {
        activeGen2 = GEN2_CRYSTAL
        romLanguage = 'E'
        romGameCode = ""
    }

    @Test
    fun `party, bag and place in the game's own words`() {
        val t = read("crystal_de")
        assertFalse(t.inBattle)
        assertEquals(listOf("ENDIVIE", "TAUBSI"), t.party.map { speciesName(it.species) })
        val endivie = t.party[0]
        assertEquals(152 to 5, endivie.species to endivie.level)
        assertEquals(19 to 19, endivie.hp to endivie.maxHp)
        assertEquals(listOf("TACKLE", "HEULER", "-", "-"), endivie.moves.map { lookupMove(it).name })
        assertEquals("BEERE", itemName(endivie.heldItem))
        assertEquals(3000L, t.money)
        assertEquals(listOf("TRANK" to 1, "POKéBALL" to 4), t.items.map { itemName(it.itemId) to it.quantity })
        assertEquals(24 to 3, t.mapGroup to t.mapNum)
        assertEquals("ROUTE 29", lookupLocation(t.regionMapSectionId).mapSecName)
        assertEquals("ROSALIA CITY", lookupLocation(3).mapSecName)
        assertEquals(setOf(16, 152), t.pokedex!!.caught)
        // Moves keep English Crystal's types and powers under their German names.
        assertEquals("NORMAL" to 35, typeName(lookupMove(33).type) to lookupMove(33).power)
        // Types in the cart's words: Kristall says DRACHEN where the German GBA games say DRACHE.
        assertEquals("DRACHEN", typeLabel("DRAGON"))
        assertEquals("PFLANZE", typeLabel("GRASS"))
    }

    @Test
    fun `battle - both battlers, the menus at German Crystal's columns`() {
        val t = read("crystal_de_battle")
        assertTrue(t.inBattle)
        assertEquals("ENDIVIE", speciesName(t.battleMons[BATTLE_POS_PLAYER_LEFT].species))
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("TAUBSI" to 2, speciesName(foe.species) to foe.level)
        val r = { f: String -> FixtureMemoryReader.load(f) }
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen2BattleInput(r("crystal_de_battle"), GEN2_CRYSTAL_DE))
        // KMPF / PkMn / BEUTEL / FLUCHT from column 5 (English's 9): the cursor on FLUCHT.
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen2BattleInput(r("crystal_de_battle_menu"), GEN2_CRYSTAL_DE))
        assertEquals(BATTLE_INPUT_BUSY to -1, readGen2BattleInput(r("crystal_de_battle_menu"), GEN2_CRYSTAL))
        assertEquals(BATTLE_INPUT_NONE to -1, readGen2BattleInput(r("crystal_de"), GEN2_CRYSTAL_DE))
    }

    @Test
    fun `the POKéMON switch - party menu, TAUSCH menu from column 10, the pick after a faint`() {
        val r = { f: String -> FixtureMemoryReader.load(f) }
        assertEquals(GbPartyMenu(submenu = false, cursor = GB_PARTY_CANCEL), readGen2PartyMenu(r("crystal_de_battle_party"), GEN2_CRYSTAL_DE))
        assertEquals(GbPartyMenu(submenu = true, cursor = 1), readGen2PartyMenu(r("crystal_de_battle_switch_menu"), GEN2_CRYSTAL_DE))
        assertNull(readGen2PartyMenu(r("crystal_de_battle_switch_menu"), GEN2_CRYSTAL))   // English's column 12: not it
        assertEquals(GbPartyMenu(submenu = false, cursor = 1, forced = true), readGen2PartyMenu(r("crystal_de_battle_faint"), GEN2_CRYSTAL_DE))
        for (f in listOf("crystal_de_battle_menu", "crystal_de_battle", "crystal_de")) assertNull(f, readGen2PartyMenu(r(f), GEN2_CRYSTAL_DE))
        german()
        val s = Gen2PartySwitch(GEN2_CRYSTAL_DE)
        assertEquals(listOf(19, 14), s.party(r("crystal_de_battle_party")).map { it.hp })
        assertEquals(listOf(0, 14), s.party(r("crystal_de_battle_faint")).map { it.hp })
        assertEquals(0, s.outSlot(r("crystal_de_battle_faint")))
    }

    @Test
    fun `dex pages from the cart - metric, as the game prints them`() {
        Gen1Dex.rom = kristall()
        try {
            val e = Gen2Dex.entry(GEN2_DEX_CRYSTAL_DE, 152)!!
            assertEquals("LAUB", e.category)
            assertEquals(9 to 64, e.heightDm to e.weightHg)   // GR. 0.9m, GEW 6.4kg on the game's page
            assertTrue(e.description, e.description.startsWith("Es liegt gerne in der Sonne."))
            assertEquals(listOf(45, 49, 65, 49, 65, 45), e.baseStats)
            for (s in 1..251) assertTrue("entry $s", Gen2Dex.entry(GEN2_DEX_CRYSTAL_DE, s) != null)
            assertTrue(Gen2Dex.itemDescription(GEN2_DEX_CRYSTAL_DE, 0x12).isNotEmpty())   // TRANK
        } finally {
            Gen1Dex.rom = null
        }
    }

    @Test
    fun `art - English Crystal's, its town maps found where Kristall keeps them`() {
        val de = kristall()
        val en = crystal()
        for (i in 0..1) {
            assertTrue("town map $i", Gen2Art.townMap(de, Gen2Art.CRYSTAL_DE, i)!!.argb.contentEquals(Gen2Art.townMap(en, Gen2Art.CRYSTAL, i)!!.argb))
        }
        val enIcons = Gen2Art.icons(en, Gen2Art.CRYSTAL)
        val deIcons = Gen2Art.icons(de, Gen2Art.CRYSTAL_DE)
        assertEquals(enIcons.keys, deIcons.keys)
        for ((dex, img) in enIcons) assertTrue("icon $dex", img.argb.contentEquals(deIcons.getValue(dex).argb))
    }

    @Test
    fun `field - its own config's code, and not English's`() {
        fun GbField.matches(rom: ByteArray) =
            codeMatches { off, len -> if (off + len <= rom.size) rom.copyOfRange(off.toInt(), (off + len).toInt()) else null }
        val de = kristall()
        assertTrue(GbField.CrystalDe.matches(de))
        assertFalse(GbField.Crystal.matches(de))
        assertFalse(GbField.CrystalDe.matches(crystal()))
    }
}
