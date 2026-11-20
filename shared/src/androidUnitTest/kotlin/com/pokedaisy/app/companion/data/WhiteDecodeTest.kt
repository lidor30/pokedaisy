package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon White (Nintendo DS) on the player's Black save, which White loads - see the white* fixtures' READMEs. White is
 * Black's code with its statics 0x20 higher ([GEN5_WHITE]); these pin what's White's own: those pointers, White Forest
 * on its Town Map and FLY, its wild Pokémon, IRIS and N's team.
 */
class WhiteDecodeTest {
    private fun read(fixture: String): Telemetry {
        activeGame = GameKind.WHITE
        romLanguage = 'E'
        romGameCode = ""
        return readGen5Telemetry(FixtureMemoryReader.load(fixture), GEN5_WHITE)
    }

    private val whiteForest = mapPlaceIdsBlack.getValue(424)

    @Test
    fun `the save through White's pointers - party, bag, money, the zone, the Pokedex`() {
        val t = read("white")
        assertFalse(t.inBattle)
        assertEquals(listOf(381, 245, 639, 521, 612), t.party.map { it.species })
        assertEquals(listOf(406, 473, 58, 85), t.party[0].moves.toList())
        assertEquals(75 + 9 + 64 + 24 + 13, t.items.size)
        assertEquals(684_260L, t.money)
        assertEquals(68, t.mapNum)
        assertEquals("Gear Station", t.mapSecName)
        val dex = t.pokedex!!
        assertEquals(POKEDEX_WHITE, dex.tables)
        assertEquals(638, dex.caught.size)
        assertTrue((0 until 8).all { t.progress!!.flag(GEN4_BADGE_FLAG + it) })
    }

    @Test
    fun `the live zone - Route 1 and HERE's grass`() {
        val t = read("white_route1")
        assertEquals(317, t.mapNum)
        assertEquals("Route 1", t.mapSecName)
        assertTrue(WHITE_WORLD.encounters(0, t.mapNum, t.progress).grass.any { it.species == 506 })   // LILLIPUP
    }

    @Test
    fun `a wild battle`() {
        val t = read("white_wild")
        assertTrue(t.inBattle)
        assertEquals(false, t.isTrainerBattle)
        assertEquals(381 to 100, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { it.species to it.level })
        assertEquals(506 to 2, t.battleMons[BATTLE_POS_OPPONENT_LEFT].let { it.species to it.level })
        assertEquals(listOf(506), t.battleFoes.map { it.species })
    }

    @Test
    fun `White Forest - its zone, its place on the Town Map, where Black City is on Black's`() {
        val t = read("white_forest")
        assertEquals(424, t.mapNum)
        assertEquals("White Forest", t.mapSecName)
        assertEquals(190 / GEN5_TOWN_MAP_TILE to 91 / GEN5_TOWN_MAP_TILE, t.regionMapTile)
        assertEquals(whiteForest, whiteTownMap.pick(0, t.regionMapTile!!.first, t.regionMapTile!!.second)?.mapsec)
        assertEquals(190 to 91, whiteTownMap.points[whiteForest])
        // Black City's place is Black's alone; White's map has White Forest in its record.
        val blackCity = mapPlaceIdsBlack.getValue(0)
        assertEquals(190 to 91, blackTownMap.points[blackCity])
        assertEquals(null, whiteTownMap.points[blackCity])
        assertEquals(61, whiteTownMap.places().sumOf { it.second.size })
        // Its arrival flag (2492) is set in the save: White Forest's mark shows.
        assertTrue((t.townsVisited!! shr (2492 - GEN5_FIRST_ARRIVAL)) and 1L != 0L)
    }

    @Test
    fun `FLY offers White Forest where Black offers Black City`() {
        val r = FixtureMemoryReader.load("white")
        val open = gen5OpenFlySpots({ a, n -> r.readCoreMemory(a, n) }, GEN5_WHITE, gen5FlySpots(GameKind.WHITE))
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 44, 41, 42, whiteForest, 40), open)
        assertEquals("White Forest", dsFlySpotName(GameKind.WHITE, whiteForest))
        assertEquals(424, gen5FlySpotAt(GEN5_WHITE, whiteForest, gen5FlySpots(GameKind.WHITE))?.mapHeader)
    }

    @Test
    fun `GUIDE - White's own wild Pokemon, IRIS and N's RESHIRAM`() {
        assertEquals(GuideId.WHITE, guideId(GameKind.WHITE, null))
        assertFalse(GUIDE_WHITE.verified)
        // Zone 155: White's PETILIL (548) where Black has COTTONEE (546).
        val white = WHITE_WORLD.encounters(0, 155, null).grass.map { it.species }
        val black = BLACK_WORLD.encounters(0, 155, null).grass.map { it.species }
        assertTrue(548 in white && 546 !in white)
        assertTrue(546 in black && 548 !in black)
        val bosses = GUIDE_WHITE.bosses
        assertEquals(14, bosses.size)
        assertEquals("LEADER IRIS", bosses[7].title)
        assertEquals("OPELUCID CITY GYM", bosses[7].where)
        assertEquals("LEADER DRAYDEN", GUIDE_BLACK.bosses[7].title)
        val n = bosses.first { it.title == "N" }
        assertEquals(643, WHITE_WORLD.party(n.trainer(SaveProgress(ByteArray(0x200), ByteArray(0))), null).first().species)   // RESHIRAM
        for (b in bosses) for (t in b.trainers(SaveProgress(ByteArray(0x200), ByteArray(0)))) {
            val team = WHITE_WORLD.party(t, null)
            assertTrue(b.title, team.size in 2..6 && team.all { it.moves.isNotEmpty() && it.level in 2..100 })
        }
        // The item balls are Black's, place for place.
        assertEquals(BLACK_AREAS_RAW, WHITE_AREAS_RAW)
    }
}
