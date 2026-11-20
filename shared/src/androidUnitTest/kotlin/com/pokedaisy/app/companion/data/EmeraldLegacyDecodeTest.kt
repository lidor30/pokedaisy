package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import okio.Path.Companion.toOkioPath

/**
 * NATIVE_EMERALD_LEGACY: Emerald Legacy v1.1.4, its own agbcc build of pokeemerald (every address its ELF's). The
 * fixtures are the user's own save (Route 101, a Lv5 TORCHIC), on the field, in a scripted wild battle, and with the
 * TRAINER CARD's five stars poked in (emerald_legacy_stars' README). The ROM checks skip where the ROM isn't here.
 */
class EmeraldLegacyDecodeTest {
    @Before fun legacy() {
        activeGame = GameKind.EMERALD
        romGameCode = LEGACY_TEXT
    }

    @After fun back() {
        romGameCode = ""
    }

    @Test
    fun `party, money and its 120-slot ITEMS pocket decode`() {
        val t = decodeNative("emerald_legacy", NATIVE_EMERALD_LEGACY)
        assertEquals(listOf("TORCHIC" to 5), t.party.map { activeSpeciesNames[it.species] to it.level })
        assertEquals(20 to 20, t.party[0].hp to t.party[0].maxHp)
        assertEquals(listOf("POTION" to 1), t.items.map { activeItemNames[it.itemId] to it.quantity })
        assertPocketsInRange(t)
        assertEquals(3000L, readMoney(FixtureMemoryReader.load("emerald_legacy"), NATIVE_EMERALD_LEGACY))
        assertFalse(t.inBattle)
    }

    @Test
    fun `a wild battle decodes, at the action menu`() {
        val ram = FixtureMemoryReader.load("emerald_legacy_battle")
        val t = readNativeTelemetry(ram, NATIVE_EMERALD_LEGACY)
        assertTrue(t.inBattle)
        assertEquals("TORCHIC" to 5, activeSpeciesNames[t.battleMons[BATTLE_POS_PLAYER_LEFT].species] to t.battleMons[BATTLE_POS_PLAYER_LEFT].level)
        assertEquals("PIKACHU" to 5, activeSpeciesNames[t.battleMons[BATTLE_POS_OPPONENT_LEFT].species] to t.battleMons[BATTLE_POS_OPPONENT_LEFT].level)
        // Its controller sits in HandleInputChooseAction (its own build's address), FIGHT highlighted.
        assertEquals(NATIVE_EMERALD_LEGACY.handleInputChooseAction or 1, u32le(ram.readCoreMemory(NATIVE_EMERALD_LEGACY.battlerControllerFuncs, 4), 0))
        assertEquals(0, ram.readCoreMemory(NATIVE_EMERALD_LEGACY.actionCursor, 1)[0].toInt())
    }

    @Test
    fun `its own names, moves and eggs`() {
        // Three Deoxys forms where vanilla's species stop (412-414); SPECIES_EGG is 415, flagged, not read as 412.
        assertEquals(listOf("DEOXYS", "DEOXYS", "DEOXYS"), (412..414).map { activeSpeciesNames[it] })
        assertFalse(isVanillaEgg(SPECIES_EGG_VANILLA))
        assertEquals(415, NATIVE_EMERALD_LEGACY.eggSpecies)
        assertEquals(SpeciesTypes(14, 14), activeSpeciesTypeData[413])
        assertEquals("BRICK PIECE", activeItemNames[226])
        // Rebalanced moves: CUT is a BUG move of 55 power with a raised critical-hit rate, BLIZZARD a freeze hit.
        assertEquals(MoveInfoLookup("CUT", 6, 55), lookupMove(15))
        assertEquals("HIGH_CRITICAL", moveStats(15)?.effect)
        assertEquals(MoveStats("FREEZE_HIT", 70, 5, 10, 0), moveStats(59))
        assertEquals(MoveInfoLookup("TACKLE", 0, 35), lookupMove(33))
    }

    @Test
    fun `its card - shifted SaveBlock1, then five stars on the black card`() {
        // What its card shows for this save (headless): LIDOR, IDNo.27007, ₽3000, 0:10, no POKéDEX yet.
        val c = readTrainerCard(FixtureMemoryReader.load("emerald_legacy"), NATIVE_EMERALD_LEGACY)!!
        assertEquals("LIDOR", c.nameText)
        assertEquals(27007, c.trainerId)
        assertEquals(3000L, c.money)
        assertNull(c.dexCaught)
        assertEquals(0, c.stars)
        assertEquals(CardStyle.LEGACY, c.style)
        // Every star poked in: the game drew five on its black card, POKéDEX 210, all eight badges, HOF 1:02:03.
        val five = readTrainerCard(FixtureMemoryReader.load("emerald_legacy_stars"), NATIVE_EMERALD_LEGACY)!!
        assertEquals(5, five.stars)
        assertEquals(210, five.dexCaught)
        assertEquals(0xFF, five.badges)
        assertEquals((1 shl 16) or (2 shl 8) or 3, five.hofDebut)
        assertEquals(0 to 10, five.hours to five.minutes)
    }

    @Test
    fun `its wild sets - the save's var picks the map's second header`() {
        val t = NATIVE_EMERALD_LEGACY.guideTables!!
        val vars = ByteArray(0x202)
        val before = SaveProgress(ByteArray(0x138), vars)
        assertEquals(0, t.wildSetOf(0, 16, before))
        vars[(0x40F7 - 0x4000) * 2] = 1   // VAR_ROUTE101_WILD_SET, set by the post-game event in MOSSDEEP CITY
        assertEquals(1, t.wildSetOf(0, 16, SaveProgress(ByteArray(0x138), vars)))
        assertEquals(0, t.wildSetOf(0, 10, null))
    }

    @Test
    fun `its ROM - tables, bosses, wild sets, field code, art`() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Emerald Legacy (v1.1.4).gba")
        assumeTrue("Emerald Legacy's ROM isn't here", rom != null)
        PokedexSource.reader = rom!!
        val t = NATIVE_EMERALD_LEGACY.guideTables!!
        assertTrue(guideTablesMatchRom(rom, t))
        assertTrue(pokedexMatchesRom(rom, NATIVE_EMERALD_LEGACY.pokedex!!))
        assertEquals(255, PokedexSource.nationalOf(NATIVE_EMERALD_LEGACY.pokedex!!, 280))   // TORCHIC
        assertEquals(410, PokedexSource.speciesFor(NATIVE_EMERALD_LEGACY.pokedex!!, 386))   // DEOXYS, not a form
        // Every boss's team, BRAWLY's three included; the champion is STEVEN, the eighth leader WALLACE.
        val guide = gameGuide(t.guide)!!
        assertEquals(listOf("LEADER WALLACE", "CHAMPION STEVEN"), guide.bosses.map { it.title }.filter { "WALLACE" in it || "STEVEN" in it })
        val ids = guide.bosses.flatMap { it.trainers(SaveProgress(ByteArray(0x138), ByteArray(0x202))) } + listOf(860, 861)
        for (id in ids) {
            val team = GuideRomSource.party(t, id)
            assertTrue("trainer $id", team != null && team.size in 1..6 && team.all { it.species in 1..414 && it.level in 1..100 && it.moves.isNotEmpty() })
        }
        assertEquals(listOf("STEVEN"), listOf(Gen3Text.decode(rom.readCoreMemory(t.trainers + 804L * t.trainerStride + 4, 12))))
        // Route 101 has two headers back to back; the second (after the post-game event) differs.
        val first = GuideRomSource.encounters(t, 0, 16, 0)!!
        val second = GuideRomSource.encounters(t, 0, 16, 1)!!
        assertEquals(100, first.grass.sumOf { it.percent })
        assertEquals(100, second.grass.sumOf { it.percent })
        assertNotEquals(first.grass, second.grass)
        assertEquals(2, GuideRomSource.encounterSets(t, 0, 16).size)
        // A var past its headers keeps the first rather than read the next map's.
        assertEquals(first, GuideRomSource.encounters(t, 0, 16, 5))
        assertTrue(allAreaThings(GuideId.EMERALD_LEGACY).isNotEmpty())
        // USE / GIVE / TAKE, FLY and the PC find their code; SMART FF finds the three region map pointers.
        val read = { a: Long, n: Int -> rom.readCoreMemory(a, n) }
        val support = fieldItemSupport(FIELD_ITEMS_EMERALD_LEGACY, read)
        assertNotNull(support)
        assertTrue(support!!.canFly && support.canPc)
        assertTrue(support.canUse(13) && support.canUse(86) && support.picksMon(13))
        assertNull(fieldItemSupport(FIELD_ITEMS_EMERALD, read))   // retail's addresses aren't its code
        val bytes = java.io.File(RETAIL_ROM_DIR + "Pokemon Emerald Legacy (v1.1.4).gba").readBytes()
        assertEquals(3, RegionMapWatch.find(bytes).size)
        assertEquals(0x02038D8CL, WildEncounters.find(java.io.File(RETAIL_ROM_DIR + "Pokemon Emerald Legacy (v1.1.4).gba").toOkioPath()))
        assertTrue(RomBlob.LG_CARD_PAL5 in RomArt.find(bytes))
        assertNotNull(TrainerCardArt.art(CardStyle.LEGACY) { RomArt.find(bytes)[it] })
    }
}
