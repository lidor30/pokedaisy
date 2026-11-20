package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crystal's GUIDE (GuideCrystal.kt over GuideCrystalGen.kt) on the `crystal` fixture: a new game saved on ROUTE 29 with
 * CHIKORITA and no badges (see its README). The facts pinned here are pret/pokecrystal's (data/wild/johto_grass.asm,
 * data/trainers/parties.asm, maps/ElmsLab.asm).
 */
class CrystalGuideTest {
    private fun read(fixture: String = "crystal"): SnapshotView {
        activeGame = GameKind.CRYSTAL
        romLanguage = 'E'
        romGameCode = ""
        activeGen2 = GEN2_CRYSTAL
        return buildSnapshotView(readGen2Telemetry(FixtureMemoryReader.load(fixture), GEN2_CRYSTAL)).copy(game = GameKind.CRYSTAL)
    }

    @Test
    fun `the guide is Crystal's own`() {
        assertEquals(GuideId.CRYSTAL, guideId(GameKind.CRYSTAL, null))
        val g = gameGuide(GuideId.CRYSTAL)!!
        assertFalse(g.verified)
        assertTrue(g.world === CrystalWorld)
    }

    @Test
    fun `the save's flags and badges`() {
        for (fixture in listOf("crystal", "crystal_rev0", "crystal_au")) {
            val p = read(fixture).progress!!
            assertFalse(fixture, (0 until 16).any { p.flag(CR_BADGE_FLAG + it) })
            assertFalse(fixture, p.flag(CR_EVENT_BEAT_FALKNER))
            assertTrue(fixture, p.crystalTimeOfDay() in CR_MORN..CR_NITE)
        }
    }

    @Test
    fun `HERE on ROUTE 29 follows the clock`() {
        fun grass(tod: Int): List<Triple<String, IntRange, Int>> {
            val p = SaveProgress(ByteArray(0), ByteArray(0), varBanks = listOf(SaveBank(CR_VAR_TIME_OF_DAY, 0, 1) to byteArrayOf(tod.toByte(), 0)))
            return CrystalWorld.encounters(24, 3, p).grass.map { Triple(speciesName(it.species), it.minLevel..it.maxLevel, it.percent) }
        }
        activeGame = GameKind.CRYSTAL
        assertEquals(
            listOf(Triple("PIDGEY", 2..3, 50), Triple("SENTRET", 2..3, 40), Triple("RATTATA", 2..2, 5), Triple("HOPPIP", 3..3, 5)),
            grass(CR_DAY),
        )
        assertEquals(listOf(Triple("HOOTHOOT", 2..3, 55), Triple("RATTATA", 2..3, 45)), grass(CR_NITE))
        // The fixture's own clock picks one of the three lists.
        val v = read()
        assertEquals(grass(v.progress.crystalTimeOfDay()).map { it.first }, CrystalWorld.encounters(v.mapGroup, v.mapNum, v.progress).grass.map { speciesName(it.species) })
    }

    @Test
    fun `surfing finds up to four levels over the slot`() {
        val maps = CR_WILD_RAW.flatMap { it.lines() }.filter { it.startsWith("W|") }.map { it.split('|') }.filter { it[3].toInt() > 0 }
        assertTrue(maps.isNotEmpty())
        for (f in maps) {
            val water = CrystalWorld.encounters(f[1].toInt(), f[2].toInt(), null).water
            assertTrue(f.toString(), water.isNotEmpty() && water.all { it.maxLevel - it.minLevel >= 4 })
        }
    }

    @Test
    fun `NEXT BOSS is FALKNER, then the rest in the game's order`() {
        val p = read().progress!!
        val bosses = gameGuide(GuideId.CRYSTAL)!!.bosses
        val next = bosses.first { !it.isDone(p) }
        assertEquals("LEADER FALKNER", next.title)
        val team = CrystalWorld.party(next.trainer(p), p)
        assertEquals(listOf("PIDGEY" to 7, "PIDGEOTTO" to 9), team.map { speciesName(it.species) to it.level })
        assertEquals(listOf("TACKLE", "MUD-SLAP", "GUST"), team[1].moves.map { lookupMove(it).name.uppercase() })
        for (b in bosses) {
            val t = CrystalWorld.party(b.trainer(p), p)
            assertTrue(b.title, t.size in 1..6 && t.all { it.moves.isNotEmpty() && it.level in 2..100 })
        }
        assertEquals("RED", bosses.last().title)
    }

    @Test
    fun `the League and RED follow their flags`() {
        val bosses = gameGuide(GuideId.CRYSTAL)!!.bosses
        fun progress(vararg events: Int, badges: Int = 0xFF): SaveProgress {
            val flags = ByteArray(CR_EVENT_FLAG_BYTES)
            for (e in events) flags[e / 8] = (flags[e / 8].toInt() or (1 shl (e % 8))).toByte()
            return SaveProgress(flags, ByteArray(0), flagBanks = listOf(SaveBank(CR_BADGE_FLAG, 0, 16) to byteArrayOf(badges.toByte(), 0)))
        }
        val johto = intArrayOf(
            CR_EVENT_BEAT_FALKNER, CR_EVENT_BEAT_BUGSY, CR_EVENT_BEAT_WHITNEY, CR_EVENT_BEAT_MORTY,
            CR_EVENT_BEAT_CHUCK, CR_EVENT_BEAT_JASMINE, CR_EVENT_BEAT_PRYCE, CR_EVENT_BEAT_CLAIR,
        )
        // A new game hides RED (his object flag set); the League not won yet: WILL is next.
        assertEquals("ELITE FOUR WILL", bosses.first { !it.isDone(progress(*johto, CR_EVENT_RED_IN_MT_SILVER)) }.title)
        // Mid-run, the rooms' own flags count.
        assertEquals("ELITE FOUR BRUNO", bosses.first { !it.isDone(progress(*johto, CR_EVENT_BEAT_ELITE_4_WILL, CR_EVENT_BEAT_ELITE_4_KOGA)) }.title)
        // League won (the Hall of Fame showed RED): Kanto's gyms, and RED stays until he's gone again.
        val won = progress(*johto, CR_EVENT_BEAT_ELITE_FOUR)
        assertEquals("LEADER BROCK", bosses.first { !it.isDone(won) }.title)
        assertFalse(bosses.last().isDone(won))
        assertTrue(bosses.last().isDone(progress(*johto, CR_EVENT_BEAT_ELITE_FOUR, CR_EVENT_RED_IN_MT_SILVER)))
    }

    @Test
    fun `HERE lists NEW BARK TOWN's gifts with their flags`() {
        val things = areaThings(GuideId.CRYSTAL, 1)
        // ELM's three starters, each marked by its own EVENT_GOT_*_FROM_ELM.
        assertEquals(setOf(152, 155, 158), things.filter { it.kind == AreaKind.MON }.map { it.id }.toSet())
        assertTrue(things.filter { it.kind == AreaKind.MON }.all { it.flag != 0 && it.where == "ELMS LAB" })
    }
}
