package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Black 2's and White 2's GUIDE data (GuideGen5Black2Gen.kt / GuideGen5White2Gen.kt: scripts/gen_gen5_guide.py, read
 * back by Gen5World) and their hand-written guide (GuideBlack2.kt), generated from the carts - so this needs no ROM.
 * The facts pinned are the carts': zones a/0/1/2, wild sets a/1/2/7, trainers a/0/9/1 / a/0/9/2, the zones' events
 * a/1/2/6 (by the zone header's +0x16) and the item ball scripts (a/0/5/6 member 1240).
 */
class Gen5Black2GuideDataTest {
    /** A save with [badges] (the case's first n), [flags] set and [month] (1-12) as Gen5World reads them. */
    private fun progress(badges: Int = 0, month: Int = 0, flags: List<Int> = emptyList()): SaveProgress {
        val bytes = ByteArray(0x200)
        for (f in flags) bytes[f / 8] = (bytes[f / 8].toInt() or (1 shl (f % 8))).toByte()
        return SaveProgress(
            bytes, ByteArray(0),
            flagBanks = listOf(SaveBank(GEN4_BADGE_FLAG, 0, 16) to byteArrayOf(((1 shl badges) - 1).toByte(), 0)),
            varBanks = listOf(SaveBank(GEN5_VAR_MONTH, 0, 1) to byteArrayOf(month.toByte(), 0)),
        )
    }

    private fun names(slots: List<EncounterSlot>) = slots.map { speciesNamesBlack[it.species] to it.percent }

    @Test
    fun `Route 19 - the first grass`() {
        assertEquals("Route 19", placeNamesBlack2[mapPlaceIdsBlack2[437]])
        val e = BLACK2_WORLD.encounters(0, 437, progress(month = 1))
        assertEquals(listOf("Purrloin" to 50, "Patrat" to 50), names(e.grass).sortedBy { it.first }.reversed())
        assertEquals(2, e.grass.minOf { it.minLevel })
        assertEquals(4, e.grass.maxOf { it.maxLevel })
        assertFalse(BLACK2_WORLD.seasonal(437))
        assertTrue(BLACK2_WORLD.seasonal(446))   // ROUTE 20
    }

    @Test
    fun `the versions differ in their wild Pokémon only`() {
        // Zone 500's grass: COTTONEE in Black 2, PETILIL in White 2.
        val b = BLACK2_WORLD.encounters(0, 500, progress(month = 1)).grass.map { it.species }
        val w = WHITE2_WORLD.encounters(0, 500, progress(month = 1)).grass.map { it.species }
        assertTrue(546 in b && 546 !in w)
        assertTrue(548 in w && 548 !in b)
        assertNotEquals(BLACK2_WILD_RAW, WHITE2_WILD_RAW)
        assertEquals(BLACK2_BOSSES_RAW, WHITE2_BOSSES_RAW.map { it })
        assertEquals(BLACK2_AREAS_RAW, WHITE2_AREAS_RAW)
        assertEquals(GUIDE_BLACK2.bosses.map { it.title }, GUIDE_WHITE2.bosses.map { it.title })
    }

    @Test
    fun `NEXT BOSS walks the gyms, GHETSIS, the Elite Four, then IRIS`() {
        val bosses = GUIDE_BLACK2.bosses
        assertEquals(14, bosses.size)
        assertFalse(GUIDE_BLACK2.verified)
        val cheren = bosses.first { !it.isDone(progress()) }
        assertEquals("LEADER CHEREN", cheren.title)
        assertEquals(listOf<Pair<String?, Int>>("NORMAL" to 156, "CHALLENGE" to 764), cheren.teams(progress()))
        assertEquals(listOf("Patrat" to 11, "Lillipup" to 13), BLACK2_WORLD.party(156, null).map { speciesNamesBlack[it.species] to it.level })
        assertEquals(3, BLACK2_WORLD.party(764, null).size)   // Challenge Mode adds a PIDOVE
        assertEquals("LEADER ROXIE", bosses.first { !it.isDone(progress(badges = 1)) }.title)
        assertEquals("LEADER MARLON", bosses.first { !it.isDone(progress(badges = 7)) }.title)
        assertEquals("GHETSIS", bosses.first { !it.isDone(progress(badges = 8)) }.title)
        val league = progress(badges = 8, flags = listOf(B2W2_FLAG_GHETSIS))
        assertEquals("ELITE FOUR SHAUNTAL", bosses.first { !it.isDone(league) }.title)
        val e4 = listOf(B2W2_FLAG_GHETSIS, B2W2_FLAG_E4, B2W2_FLAG_E4 + 1)
        assertEquals("ELITE FOUR MARSHAL", bosses.first { !it.isDone(progress(badges = 8, flags = e4)) }.title)
        val champion = progress(badges = 8, flags = listOf(B2W2_FLAG_GHETSIS) + (0..3).map { B2W2_FLAG_E4 + it })
        val iris = bosses.first { !it.isDone(champion) }
        assertEquals("CHAMPION IRIS", iris.title)
        assertEquals(612, BLACK2_WORLD.party(iris.teams(champion).first().second, null).last().species)   // HAXORUS last
        // Once the game is cleared the Elite Four and IRIS bring their second round's teams.
        val cleared = progress(badges = 8, flags = listOf(B2W2_FLAG_GHETSIS, B2W2_FLAG_CLEARED))
        assertEquals(listOf<Pair<String?, Int>>("NORMAL" to 143, "CHALLENGE" to 777), bosses.first { it.title == "ELITE FOUR SHAUNTAL" }.teams(cleared))
        assertEquals(listOf<Pair<String?, Int>>("NORMAL" to 536, "CHALLENGE" to 781), iris.teams(cleared))
        for (b in bosses) for (p in listOf(progress(), cleared)) for (t in b.trainers(p)) {
            val team = BLACK2_WORLD.party(t, null)
            assertTrue(b.title, team.size in 2..6 && team.all { it.moves.isNotEmpty() && it.level in 2..100 })
        }
    }

    @Test
    fun `HERE's item balls are keyed by place`() {
        val route19 = mapPlaceIdsBlack2.getValue(437)
        val things = BLACK2_AREAS_RAW.flatMap { it.lines() }.filter { it.startsWith("$route19|ITEM|") }
        // Route 19's three item balls, among them a Potion (17), each with its hide flag.
        assertEquals(3, things.size)
        assertTrue("$route19|ITEM|17|1423|1|0|" in things)
        assertEquals(route19, areaSection(GameKind.BLACK2, 437, -1))
        assertEquals(route19, areaSection(GameKind.WHITE2, 437, -1))
    }

    @Test
    fun `the sequels get their own guide`() {
        assertEquals(GuideId.BLACK2, guideId(GameKind.BLACK2, null))
        assertEquals(GuideId.WHITE2, guideId(GameKind.WHITE2, null))
        assertTrue(gameGuide(GuideId.BLACK2) === GUIDE_BLACK2)
        assertTrue(gameGuide(GuideId.WHITE2) === GUIDE_WHITE2)
    }
}
