package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Black 2 and White 2 (Nintendo DS) on the player's saves - see the black2* and white2 fixtures' READMEs. Black 2
 * is Black's engine rebuilt: its statics moved ([GEN5_BLACK2]), its save image grew and its blocks moved (money, the
 * Pokédex, the event work, the Repel); White 2 is Black 2's code 0x40 higher ([GEN5_WHITE2]).
 */
class Black2DecodeTest {
    private fun read(fixture: String, kind: GameKind = GameKind.BLACK2): Telemetry {
        activeGame = kind
        romLanguage = 'E'
        romGameCode = ""
        return readGen5Telemetry(FixtureMemoryReader.load(fixture), if (kind == GameKind.WHITE2) GEN5_WHITE2 else GEN5_BLACK2)
    }

    @Test
    fun `the save - party, bag, money, the zone, the Pokedex, the badges`() {
        val t = read("black2")
        assertFalse(t.inBattle)
        assertEquals(listOf(500, 571, 547, 520, 55, 505), t.party.map { it.species })
        assertEquals(listOf(394, 372, 457, 535), t.party[0].moves.toList())
        assertEquals(50, t.items.size)
        assertEquals(656_841L, t.money)
        assertEquals(465, t.mapNum)
        assertEquals("Humilau City", t.mapSecName)
        assertEquals(0, t.repelSteps)
        val dex = t.pokedex!!
        assertEquals(POKEDEX_BLACK2, dex.tables)
        assertEquals(15, dex.caught.size)
        assertEquals(248, dex.seen.size)
        assertTrue(dex.national)   // its Pokédex screen offers NATIONAL
        assertTrue((0 until 8).all { t.progress!!.flag(GEN4_BADGE_FLAG + it) })
    }

    @Test
    fun `the Town Map - Humilau City's point, the arrival flags`() {
        val t = read("black2")
        val humilau = mapPlaceIdsBlack2.getValue(465)
        assertEquals(235 / GEN5_TOWN_MAP_TILE to 36 / GEN5_TOWN_MAP_TILE, t.regionMapTile)
        assertEquals(humilau, black2TownMap.pick(0, t.regionMapTile!!.first, t.regionMapTile!!.second)?.mapsec)
        // Every town's arrival flag but Unity Tower's (2486) is set, and Victory Road's (2530).
        val visited = t.townsVisited!!
        fun arrived(flag: Int) = (visited shr (flag - GEN5_BW2_FIRST_ARRIVAL)) and 1L != 0L
        assertTrue((2469..2492).filter { it != 2486 }.all(::arrived))
        assertFalse(arrived(2486))
        assertTrue(arrived(2530))
        // N's Castle shows (its flag 0x99F is set: the game draws its green mark), the Abyssal Ruins don't (0x9A0).
        assertEquals(listOf(0x99F, 0x9A0), gen5ShownFlags(townMapPlacesBlack2))
        assertTrue((visited shr GEN5_BW2_SHOWN_BIT) and 1L != 0L)
        assertFalse((visited shr (GEN5_BW2_SHOWN_BIT + 1)) and 1L != 0L)
        assertEquals(GEN5_BW2_SHOWN_BIT, black2TownMap.marks.single { it.x == 182 && it.y == 6 }.visitedFlag)
        // Black City on Black 2's map, White Forest on White 2's, at the same point.
        assertEquals(190 to 91, black2TownMap.points[mapPlaceIdsBlack2.getValue(0)])
        assertEquals(null, black2TownMap.points[mapPlaceIdsBlack2.getValue(424)])
        assertEquals(190 to 91, white2TownMap.points[mapPlaceIdsBlack2.getValue(424)])
        assertEquals(84, black2TownMap.places().sumOf { it.second.size })
    }

    @Test
    fun `a wild battle on Humilau City's water`() {
        val t = read("black2_wild")
        assertTrue(t.inBattle)
        assertEquals(false, t.isTrainerBattle)
        assertEquals(500 to 100, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { it.species to it.level })
        assertEquals(120 to 35, t.battleMons[BATTLE_POS_OPPONENT_LEFT].let { it.species to it.level })   // STARYU
        assertEquals(listOf(120), t.battleFoes.map { it.species })
    }

    @Test
    fun `the second save - a full bag, money at its cap, a Max Repel's steps`() {
        val t = read("black2_nimbasa")
        assertEquals(listOf(646, 561), t.party.map { it.species })
        assertEquals(321, t.items.size)
        assertEquals(9_999_999L, t.money)
        assertEquals(64, t.mapNum)
        assertEquals("Nimbasa City", t.mapSecName)
    }

    @Test
    fun `White 2 through its own pointers`() {
        val t = read("white2", GameKind.WHITE2)
        assertFalse(t.inBattle)
        assertEquals(listOf(497, 227, 579, 505, 127, 184), t.party.map { it.species })
        assertEquals(49, t.items.size)
        assertEquals(544_628L, t.money)
        assertEquals(448, t.mapNum)
        assertEquals("Virbank City", t.mapSecName)
        assertEquals(230, t.pokedex!!.seen.size)
    }

    @Test
    fun `FLY - the towns arrived at, Black City in Black 2 and White Forest in White 2`() {
        val r = FixtureMemoryReader.load("black2")
        val open = gen5OpenFlySpots({ a, n -> r.readCoreMemory(a, n) }, GEN5_BLACK2, gen5FlySpots(GameKind.BLACK2))
        assertEquals(23, open.size)   // every one but Unity Tower
        assertTrue(mapPlaceIdsBlack2.getValue(465) in open)
        assertTrue(mapPlaceIdsBlack2.getValue(0) in open)
        assertEquals("Humilau City", dsFlySpotName(GameKind.BLACK2, mapPlaceIdsBlack2.getValue(465)))
        assertEquals(0, gen5FlySpotAt(GEN5_BLACK2, mapPlaceIdsBlack2.getValue(0), gen5FlySpots(GameKind.BLACK2))?.mapHeader)
        assertEquals(424, gen5FlySpotAt(GEN5_WHITE2, mapPlaceIdsBlack2.getValue(424), gen5FlySpots(GameKind.WHITE2))?.mapHeader)
        val w = FixtureMemoryReader.load("white2")
        val wOpen = gen5OpenFlySpots({ a, n -> w.readCoreMemory(a, n) }, GEN5_WHITE2, gen5FlySpots(GameKind.WHITE2))
        assertTrue(mapPlaceIdsBlack2.getValue(424) in wOpen && mapPlaceIdsBlack2.getValue(0) !in wOpen)
    }

    @Test
    fun `the tables - Black's species and moves, the key items the sequels added`() {
        activeGame = GameKind.BLACK2
        assertEquals("Snivy", activeSpeciesNames[495])
        assertEquals("Medal Box", activeItemNames[627])
        assertEquals("Reveal Glass", activeItemNames[638])
        assertEquals(NdsArt.BLACK2_ICONS, activeMonSpriteDir)
        assertEquals(NdsArt.BLACK2_ITEMS, activeItemSpriteDir)
        assertEquals(301, unovaDexBlack2.size)
    }
}
