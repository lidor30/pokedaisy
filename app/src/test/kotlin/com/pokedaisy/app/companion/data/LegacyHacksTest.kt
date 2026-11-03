package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Yellow Legacy v1.0.10 and Crystal Legacy v1.3.1 on the retail saves rewritten by scripts/convert_save_legacy.py - see the
 * yellow_legacy* / crystal_legacy* fixtures' READMEs. Each runs its family's reader at the hack's own addresses, over the
 * family's tables but what it changed. The art and field tests read the patched carts (skipped without them).
 */
class LegacyHacksTest {
    private fun dump(fixture: String) = FixtureMemoryReader.load(fixture)
    private val gbc = File(File(RETAIL_ROM_DIR).parentFile, "gbc")

    private fun rom(name: String): ByteArray {
        val f = File(gbc, name)
        assumeTrue("$name not on this machine", f.isFile)
        return f.readBytes()
    }
    private val yellowLegacy get() = rom("Pokemon Yellow Legacy (v1.0.10).gb")
    private val crystalLegacy get() = rom("Pokemon Crystal Legacy (v1.3.1).gbc")

    private fun GbField.matches(rom: ByteArray) =
        codeMatches { off, len -> if (off + len <= rom.size) rom.copyOfRange(off.toInt(), (off + len).toInt()) else null }

    @After
    fun reset() {
        activeGame = GameKind.FIRERED
        activeGen2 = GEN2_CRYSTAL
        Gen1Dex.rom = null
    }

    @Test
    fun `Yellow Legacy - party, money, bag, badges and location`() {
        activeGame = GameKind.YELLOW_LEGACY
        val t = readGen1Telemetry(dump("yellow_legacy"), GEN1_YELLOW_LEGACY)
        assertFalse(t.inBattle)
        assertEquals(listOf("PIKACHU", "SANDSLASH", "SNORLAX", "LAPRAS", "PIDGEOT", "RATICATE"), t.party.map { speciesName(it.species) })
        assertEquals(listOf(182, 274, 450, 382, 300, 236), t.party.map { it.hp })
        assertEquals(999999L, t.money)
        assertEquals(17, t.items.size)
        assertEquals("HELIX FOSSIL", itemName(t.items[0].itemId))
        assertEquals(0x59, t.regionMapSectionId)
    }

    @Test
    fun `Yellow Legacy - its own moves and chart`() {
        activeGame = GameKind.YELLOW_LEGACY
        assertEquals(0, moveDataYellowLegacy.keys.minus(moveDataYellow.keys).size)
        assertTrue("its moves aren't Yellow's", moveDataYellowLegacy != moveDataYellow)
        assertEquals(200, singleTypeMultiplierPct(8, 24))   // GHOST into PSYCHIC, 0x in Yellow
        assertEquals("DRAGON", typeName(lookupMove(21).type))         // SLAM
        assertEquals("NORMAL", typeName(lookupMove(113).type))        // LIGHT SCREEN: the typeless slot, as the game prints it
        assertEquals(6, lookupMove(113).type)
        activeGame = GameKind.YELLOW
        assertEquals(0, singleTypeMultiplierPct(8, 24))
    }

    @Test
    fun `Yellow Legacy - battle, menus and the switch`() {
        activeGame = GameKind.YELLOW_LEGACY
        val t = readGen1Telemetry(dump("yellow_legacy_battle"), GEN1_YELLOW_LEGACY)
        assertTrue(t.inBattle)
        assertEquals(25 to 100, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { it.species to it.level })
        assertEquals("CHARMANDER", speciesName(t.battleMons[BATTLE_POS_OPPONENT_LEFT].species))
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 0, readGen1BattleInput(dump("yellow_legacy_battle"), GEN1_YELLOW_LEGACY))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen1BattleInput(dump("yellow_legacy_battle_menu"), GEN1_YELLOW_LEGACY))
        assertEquals(BATTLE_INPUT_NONE to -1, readGen1BattleInput(dump("yellow_legacy"), GEN1_YELLOW_LEGACY))
        assertEquals(GbPartyMenu(submenu = false, cursor = 2), readGen1PartyMenu(dump("yellow_legacy_battle_party"), GEN1_YELLOW_LEGACY))
        assertNull(readGen1PartyMenu(dump("yellow_legacy_battle_menu"), GEN1_YELLOW_LEGACY))
    }

    @Test
    fun `Crystal Legacy - party, pockets and location`() {
        activeGame = GameKind.CRYSTAL_LEGACY
        activeGen2 = GEN2_CRYSTAL_LEGACY
        val t = readGen2Telemetry(dump("crystal_legacy"), GEN2_CRYSTAL_LEGACY)
        assertFalse(t.inBattle)
        assertEquals(listOf("CHIKORITA", "PIDGEY"), t.party.map { speciesName(it.species) })
        assertEquals("BERRY", itemName(t.party[0].heldItem))
        assertEquals(setOf("POTION", "POKé BALL"), t.items.map { itemName(it.itemId) }.toSet().intersect(setOf("POTION", "POKé BALL")))
        assertEquals("ROUTE 29", lookupLocation(t.regionMapSectionId).mapSecName)
    }

    @Test
    fun `Crystal Legacy - its own moves and items`() {
        activeGame = GameKind.CRYSTAL_LEGACY
        activeGen2 = GEN2_CRYSTAL_LEGACY
        assertEquals("OLD AMBER", itemName(147))
        assertEquals("TM01", itemName(gen2TmItemsCrystalLegacy[0]))
        assertEquals(0xC1, gen2TmItemsCrystalLegacy[0])
        assertTrue("its moves aren't Crystal's", moveDataCrystalLegacy != moveDataCrystal)
    }

    @Test
    fun `Crystal Legacy - battle, menus and the switch`() {
        activeGame = GameKind.CRYSTAL_LEGACY
        activeGen2 = GEN2_CRYSTAL_LEGACY
        val t = readGen2Telemetry(dump("crystal_legacy_battle"), GEN2_CRYSTAL_LEGACY)
        assertTrue(t.inBattle)
        assertEquals("CHIKORITA", speciesName(t.battleMons[BATTLE_POS_PLAYER_LEFT].species))
        assertEquals("HOOTHOOT", speciesName(t.battleMons[BATTLE_POS_OPPONENT_LEFT].species))
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen2BattleInput(dump("crystal_legacy_battle"), GEN2_CRYSTAL_LEGACY))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen2BattleInput(dump("crystal_legacy_battle_menu"), GEN2_CRYSTAL_LEGACY))
        assertEquals(BATTLE_INPUT_NONE to -1, readGen2BattleInput(dump("crystal_legacy"), GEN2_CRYSTAL_LEGACY))
        assertEquals(GbPartyMenu(submenu = false, cursor = 1), readGen2PartyMenu(dump("crystal_legacy_battle_party"), GEN2_CRYSTAL_LEGACY))
    }

    @Test
    fun `Yellow Legacy's GUIDE - its own wild Pokémon and bosses, from the save's flags`() {
        activeGame = GameKind.YELLOW_LEGACY
        assertEquals(GuideId.YELLOW_LEGACY, guideId(GameKind.YELLOW_LEGACY, null))
        val guide = gameGuide(GuideId.YELLOW_LEGACY)!!
        val t = readGen1Telemetry(dump("yellow_legacy"), GEN1_YELLOW_LEGACY)
        val p = t.progress!!
        // Eight badges: every gym leader is done on this save.
        assertTrue(guide.bosses.take(8).all { it.isDone(p) })
        assertEquals(listOf("GEODUDE", "ONIX"), YELLOW_LEGACY_WORLD.party(0, null).map { speciesName(it.species) })
        // Its OLD ROD has a table of its own: GOLDEEN and POLIWAG, retail's lone MAGIKARP.
        val old = YELLOW_LEGACY_WORLD.encounters(0, 0, null).oldRod.map { speciesName(it.species) }.toSet()
        assertEquals(setOf("GOLDEEN", "POLIWAG"), old)
    }

    @Test
    fun `Crystal Legacy's GUIDE - scaled gyms follow the badge count, Red his own flag`() {
        activeGame = GameKind.CRYSTAL_LEGACY
        activeGen2 = GEN2_CRYSTAL_LEGACY
        assertEquals(GuideId.CRYSTAL_LEGACY, guideId(GameKind.CRYSTAL_LEGACY, null))
        val guide = gameGuide(GuideId.CRYSTAL_LEGACY)!!
        val chuck = guide.bosses.first { it.title == "LEADER CHUCK" }
        fun withBadges(n: Int) = SaveProgress(ByteArray(CL_EVENT_FLAG_BYTES), ByteArray(0), flagBanks = listOf(
            SaveBank(CR_BADGE_FLAG, 0, 16) to byteArrayOf(((1 shl n) - 1).toByte(), 0),
        ))
        val levels = listOf(4, 5, 6).map { n -> CrystalLegacyWorld.party(chuck.trainer(withBadges(n)), null).maxOf { it.level } }
        assertTrue("$levels", levels[0] < levels[1] && levels[1] < levels[2])
        assertEquals(guide.bosses.size, 22)
        val p = readGen2Telemetry(dump("crystal_legacy"), GEN2_CRYSTAL_LEGACY).progress!!
        assertFalse(guide.bosses.first().isDone(p))
    }

    @Test
    fun `PATCH A ROM makes each hack from the release's patches, whichever Crystal it starts from`() {
        val patches = File(gbc, "patches")
        fun patched(patch: String, base: String): String {
            val f = File(patches, patch)
            assumeTrue("$patch not on this machine", f.isFile)
            val out = com.pokedaisy.app.BpsPatch.parse(f.readBytes()).apply(rom(base))
            return java.security.MessageDigest.getInstance("SHA-1").digest(out).joinToString("") { "%02x".format(it) }
        }
        assertEquals(TelemetrySampler.YELLOW_LEGACY_V1_0_10_SHA1, patched(
            "Pokemon Yellow Legacy V1.0.10 Patches/Pokemon Yellow Legacy V1.0.10 Patch.bps",
            "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb"))
        assertEquals(TelemetrySampler.CRYSTAL_LEGACY_V1_3_1_SHA1, patched(
            "Pokemon Crystal Legacy V1.3.1 Patches/Version USA/Pokemon_Crystal_Legacy_USA_1.0_Patch.bps",
            "Pokemon - Crystal Version (USA).gbc"))
        assertEquals(TelemetrySampler.CRYSTAL_LEGACY_V1_3_1_SHA1, patched(
            "Pokemon Crystal Legacy V1.3.1 Patches/Version USA, Europe Rev 1/Pokemon_Crystal_Legacy_USA_1.1_Patch.bps",
            "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc"))
    }

    @Test
    fun `each hack's dex pages read from its own cart`() {
        activeGame = GameKind.YELLOW_LEGACY
        Gen1Dex.rom = yellowLegacy
        for (dex in 1..151) assertTrue("$dex", PokedexSource.entry(POKEDEX_YELLOW_LEGACY, dex)?.description?.isNotBlank() == true)
        activeGame = GameKind.CRYSTAL_LEGACY
        activeGen2 = GEN2_CRYSTAL_LEGACY
        Gen1Dex.rom = crystalLegacy
        for (dex in 1..251) assertTrue("$dex", PokedexSource.entry(POKEDEX_CRYSTAL_LEGACY, dex)?.description?.isNotBlank() == true)
        Gen1Dex.rom = null
    }

    @Test
    fun `each hack's field code is in its own cart and no other's`() {
        assertTrue(GB_FIELD_YELLOW_LEGACY.matches(yellowLegacy))
        assertTrue(GbField.CrystalLegacy.matches(crystalLegacy))
        assertFalse(GB_FIELD_YELLOW.matches(yellowLegacy))
        assertFalse(GbField.Crystal.matches(crystalLegacy))
        assertFalse(GB_FIELD_YELLOW_LEGACY.matches(crystalLegacy))
    }

    @Test
    fun `Yellow Legacy's art - its own icons, two frames, in Yellow's colours`() {
        val out = File("build/gen1-art-legacy")
        out.deleteRecursively()
        val wrote = Gen1Art.extractTo(yellowLegacy, out)
        assertTrue(Gen1Art.YELLOW_LEGACY_FONT in wrote && "regionmap/yellow_legacy.png" in wrote)
        val icons = Gen1Art.icons(yellowLegacy, Gen1Art.YELLOW_LEGACY)
        assertEquals(151, icons.size)
        for (dex in 1..151) assertTrue("$dex is blank", icons.getValue(dex).argb.any { it != 0 })
        assertEquals(Gen1Art.YELLOW.iconColors.toSet(), icons.getValue(25).argb.filter { it != 0 }.toSet())
        val pikachu = icons.getValue(25).argb
        assertNotEquals(pikachu.copyOfRange(0, 256).toList(), pikachu.copyOfRange(256, 512).toList())
    }

    @Test
    fun `Crystal Legacy's art - an icon a species, each in its palette`() {
        val out = File("build/gen2-art-legacy")
        out.deleteRecursively()
        val wrote = Gen2Art.extractTo(crystalLegacy, out)
        assertTrue(Gen2Art.CRYSTAL_LEGACY_FONT in wrote && "regionmap/crystal_legacy_johto.png" in wrote)
        val icons = Gen2Art.icons(crystalLegacy, Gen2Art.CRYSTAL_LEGACY)
        assertEquals(252, icons.size)
        for ((id, icon) in icons) assertTrue("$id is blank", icon.argb.any { it != 0 })
        // BULBASAUR's palette is TEAL, PIKACHU's isn't: the icons aren't one colour as in Crystal.
        assertNotEquals(icons.getValue(1).argb.filter { it != 0 }.toSet(), icons.getValue(25).argb.filter { it != 0 }.toSet())
    }
}
