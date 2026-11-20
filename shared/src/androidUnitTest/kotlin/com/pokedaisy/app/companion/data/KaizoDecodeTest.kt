package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * NATIVE_EMERALD_KAIZO: retail Emerald's RAM, Kaizo's own moves (KAIZO_TEXT) and wild table. The fixtures are
 * retail Emerald's save booted in Kaizo (the user has no Kaizo save; Kaizo keeps retail's SaveBlocks) - see their
 * READMEs. The ROM checks are skipped where the ROM isn't on this machine.
 */
class KaizoDecodeTest {
    @Before fun kaizo() {
        activeGame = GameKind.EMERALD
        romGameCode = KAIZO_TEXT
    }

    @After fun back() {
        romGameCode = ""
    }

    @Test
    fun `party, moves and bag decode, with Kaizo's moves`() {
        val t = decodeNative("emerald_kaizo", NATIVE_EMERALD_KAIZO)
        assertEquals(listOf("KADABRA" to 36, "MANECTRIC" to 36, "TRAPINCH" to 27, "BLAZIKEN" to 44, "PELIPPER" to 43, "SEEDOT" to 11),
            t.party.map { activeSpeciesNames[it.species] to it.level })
        // Move 209 is SPARK in retail and WILD CHARGE in Kaizo; BLAZE KICK hits for 100 there (85 in retail).
        val manectric = t.party[1].moves.map { lookupMove(it).name }
        assertEquals(listOf("THUNDER WAVE", "THUNDERBOLT", "QUICK ATTACK", "WILD CHARGE"), manectric)
        assertEquals(MoveInfoLookup("BLAZE KICK", 10, 100), lookupMove(299))
        assertEquals(listOf("POTION" to 2, "PARLYZ HEAL" to 4), t.items.take(2).map { activeItemNames[it.itemId] to it.quantity })
        assertPocketsInRange(t)
        assertFalse(t.inBattle)
    }

    @Test
    fun `a wild battle decodes`() {
        val t = readNativeTelemetry(FixtureMemoryReader.load("emerald_kaizo_battle"), NATIVE_EMERALD_KAIZO)
        assertTrue(t.inBattle)
        assertEquals("KADABRA" to 36, activeSpeciesNames[t.battleMons[BATTLE_POS_PLAYER_LEFT].species] to t.battleMons[BATTLE_POS_PLAYER_LEFT].level)
        assertEquals("PIKACHU" to 5, activeSpeciesNames[t.battleMons[BATTLE_POS_OPPONENT_LEFT].species] to t.battleMons[BATTLE_POS_OPPONENT_LEFT].level)
    }

    @Test
    fun `its ROM - the tables, every boss team, its own wild table`() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Emerald Kaizo (v2.0).gba")
        assumeTrue("Kaizo's ROM isn't here", rom != null)
        PokedexSource.reader = rom!!
        val t = NATIVE_EMERALD_KAIZO.guideTables!!
        assertTrue(guideTablesMatchRom(rom, t))
        assertTrue(pokedexMatchesRom(rom, NATIVE_EMERALD_KAIZO.pokedex!!))
        val noProgress = SaveProgress(ByteArray(0x120), ByteArray(0x200))
        for (boss in gameGuide(t.guide)!!.bosses) for ((label, id) in boss.teams(noProgress)) {
            val team = GuideRomSource.party(t, id)
            assertTrue("${boss.title} $label", team != null && team.size in 1..6 && team.all { it.species in 1..411 && it.level in 1..100 })
        }
        // Route 101 (0.16): a whole grass table of its own - not retail's (the table moved, its slots changed).
        val here = GuideRomSource.encounters(t, 0, 16)!!
        assertEquals(100, here.grass.sumOf { it.percent })
        assertNotEquals(GuideRomSource.encounters(GUIDE_TABLES_EMERALD, 0, 16)?.grass, here.grass)
    }
}
