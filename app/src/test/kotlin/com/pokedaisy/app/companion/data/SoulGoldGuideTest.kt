package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** SoulGold's save progress (its vars aren't right after its flags) and NEXT BOSS. */
class SoulGoldGuideTest {
    @Test fun `the fixture's flags and starter var read`() {
        for ((fixture, cfg, tables) in listOf(
            Triple("soulgold", NATIVE_SOULGOLD, GUIDE_TABLES_SOULGOLD),
            Triple("soulgold_v12", NATIVE_SOULGOLD_V1_2, GUIDE_TABLES_SOULGOLD_V1_2),
        )) {
            val progress = readSaveProgress(FixtureMemoryReader.load(fixture), cfg, tables)
            assertNotNull(fixture, progress)
            // Cyndaquil was the starter (VAR_STARTER_SPECIES 0x4051).
            assertEquals(fixture, 155, progress!!.variable(0x4051))
            val sysFlags = 0x98C
            assertTrue(fixture, progress.flag(sysFlags))            // FLAG_SYS_POKEMON_GET
            assertTrue(fixture, progress.flag(sysFlags + 0x60))     // FLAG_SYS_B_DASH
            assertFalse(fixture, progress.flag(sysFlags + 7))       // no Falkner badge yet
            assertEquals(fixture, "LEADER FALKNER", gameGuide(GuideId.SOULGOLD)!!.bosses.first { !it.isDone(progress) }.title)
        }
    }

    /** A save with just [flags] set. */
    private fun save(vararg flags: Int) = SaveProgress(
        ByteArray(GUIDE_TABLES_SOULGOLD.flagBytes).also { b -> flags.forEach { b[it / 8] = (b[it / 8].toInt() or (1 shl (it % 8))).toByte() } },
        ByteArray(GUIDE_TABLES_SOULGOLD.varCount * 2),
    )

    @Test fun `CHUCK, JASMINE and PRYCE bring a stronger team for each of the other two beaten`() {
        val bosses = gameGuide(GuideId.SOULGOLD)!!.bosses
        // Each gym's FLAG_DEFEATED_*: Cianwood 0x4F4, Olivine 0x4F5, Mahogany 0x4F6.
        for ((title, own, others, teams) in listOf(
            Quad("LEADER CHUCK", 0x4F4, 0x4F5 to 0x4F6, listOf(510, 442, 538)),
            Quad("LEADER JASMINE", 0x4F5, 0x4F4 to 0x4F6, listOf(513, 651, 180)),
            Quad("LEADER PRYCE", 0x4F6, 0x4F4 to 0x4F5, listOf(546, 578, 707)),
        )) {
            val boss = bosses.single { it.title == title }
            assertEquals(title, teams[0], boss.trainer(save()))
            assertEquals(title, teams[0], boss.trainer(save(own))) // its own flag doesn't count
            assertEquals(title, teams[1], boss.trainer(save(others.first)))
            assertEquals(title, teams[1], boss.trainer(save(others.second)))
            assertEquals(title, teams[2], boss.trainer(save(others.first, others.second)))
            // Both difficulties follow the save.
            assertEquals(title, listOf(teams[1], teams[1] or (1 shl TRAINER_TABLE_SHIFT)),
                boss.variantsFor!!(save(others.first)).map { it.second })
        }
    }

    /** NEXT BOSS's teams read from the player's ROMs (skipped when they aren't on this machine): v1.1.4 and v1.2 keep the
     * same trainer ids, at their own addresses. */
    @Test fun `the first leaders' teams and CHUCK's three read from the ROM`() {
        for ((file, t) in listOf(
            "Pokemon SoulGold (v1.1.4).gba" to GUIDE_TABLES_SOULGOLD,
            "Pokemon SoulGold (v1.2).gba" to GUIDE_TABLES_SOULGOLD_V1_2,
        )) {
            val rom = RomFileReader.load(RETAIL_ROM_DIR + file)
            assumeTrue("$file not on this machine", rom != null)
            activeGame = GameKind.SOULGOLD
            PokedexSource.reader = rom!!
            assertTrue(file, guideTablesMatchRom(rom, t))
            fun team(id: Int) = GuideRomSource.party(t, id)!!
            // FALKNER (19): PIDGEY / GLIGAR Lv11, PIDGEOTTO Lv12 holding an ORAN BERRY.
            val falkner = team(19)
            assertEquals(file, listOf(16, 207, 17), falkner.map { it.species })
            assertEquals(file, listOf(11, 11, 12), falkner.map { it.level })
            assertEquals(file, 520, falkner[2].heldItem)
            assertEquals(file, listOf(33, 16, 98), falkner[0].moves) // TACKLE, GUST, QUICK ATTACK
            // BUGSY (596) and WHITNEY (604).
            assertEquals(file, listOf(123, 544, 742), team(596).map { it.species })
            assertEquals(file, listOf(20, 19, 19), team(596).map { it.level })
            assertEquals(file, listOf(531, 203, 241), team(604).map { it.species })
            assertEquals(file, listOf(25, 26, 27), team(604).map { it.level })
            // CHUCK's teams for none / one / both of the other two beaten: each a few levels up, the last two a fifth.
            val chuck = listOf(510, 442, 538).map(::team)
            assertEquals(file, listOf(4, 5, 5), chuck.map { it.size })
            assertEquals(file, listOf(42, 45, 48), chuck.map { team -> team.maxOf { it.level } })
        }
    }

    private data class Quad(val title: String, val own: Int, val others: Pair<Int, Int>, val teams: List<Int>)
}
