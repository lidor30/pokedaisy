package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pokémon Black (Nintendo DS) on the player's own save - see the black and black_battle fixtures' READMEs. */
class BlackDecodeTest {
    private fun read(fixture: String): Telemetry {
        activeGame = GameKind.BLACK
        romLanguage = 'E'
        romGameCode = ""
        return readGen5Telemetry(FixtureMemoryReader.load(fixture), GEN5_BLACK)
    }

    @Test
    fun `party - Gen 4's encrypted struct, species past 493`() {
        val t = read("black")
        assertFalse(t.inBattle)
        assertEquals(listOf(381, 245, 639, 521, 612), t.party.map { it.species })
        assertEquals(listOf(100, 100, 100, 81, 82), t.party.map { it.level })
        val latios = t.party[0]
        assertEquals(302 to 302, latios.hp to latios.maxHp)
        assertEquals(listOf(406, 473, 58, 85), latios.moves.toList())
        assertEquals(270, latios.heldItem)
        val unfezant = t.party[3]
        assertEquals(0x04FD3A09L, unfezant.personality)
        assertEquals(GENDER_SYMBOL_FEMALE, unfezant.genderSymbol)
        assertEquals(19, unfezant.moves[0])   // FLY
    }

    @Test
    fun `bag pockets, money, gender, the zone and its place`() {
        val t = read("black")
        assertEquals(75 + 9 + 64 + 24 + 13, t.items.size)
        assertEquals(Item(575, 155, POCKET_ITEMS), t.items.first())   // the Pass Orbs
        assertEquals(9, t.items.count { it.pocket == POCKET_KEY_ITEMS })
        assertEquals(64, t.items.count { it.pocket == POCKET_TM_HM })
        assertEquals(24, t.items.count { it.pocket == POCKET_MEDICINE })
        assertEquals(13, t.items.count { it.pocket == POCKET_BERRIES })
        assertEquals(684_260L, t.money)
        assertEquals(0, t.playerGender)
        assertEquals(68, t.mapNum)
        assertEquals("Gear Station", t.mapSecName)
    }

    @Test
    fun `the save's Pokedex, flags and badges`() {
        val t = read("black")
        val dex = t.pokedex!!
        assertEquals(POKEDEX_BLACK, dex.tables)
        assertEquals(638, dex.caught.size)   // the CONTINUE screen's "Pokédex: 638"
        assertTrue(dex.seen.containsAll(dex.caught))
        assertTrue(dex.national)
        val p = t.progress!!
        assertTrue((0 until 8).all { p.flag(GEN4_BADGE_FLAG + it) })
    }

    @Test
    fun `the live zone - Route 1 after a warp the save's Location doesn't know of, and HERE's grass`() {
        val t = read("black_route1")
        assertEquals(317, t.mapNum)
        assertEquals("Route 1", t.mapSecName)
        assertEquals(0, t.repelSteps)
        val here = BLACK_WORLD.encounters(0, t.mapNum, t.progress)
        assertTrue(here.grass.any { it.species == 506 })   // LILLIPUP
    }

    @Test
    fun `the Town Map - the player's place, its point and the towns arrived at`() {
        val model = blackTownMap
        // Route 1: its own record, the point (232, 148)
        val r1 = read("black_route1")
        assertEquals(232 / GEN5_TOWN_MAP_TILE to 148 / GEN5_TOWN_MAP_TILE, r1.regionMapTile)
        val route1 = mapPlaceIdsBlack.getValue(317)
        assertEquals(route1, model.pick(0, r1.regionMapTile!!.first, r1.regionMapTile!!.second)?.mapsec)
        assertEquals(232 to 148, model.points[route1])
        // The Gear Station platform: no place of its own on the map, so its parent zone's, Nimbasa City (132, 91)
        val gear = read("black")
        val nimbasa = mapPlaceIdsBlack.getValue(62)
        assertEquals(132 / GEN5_TOWN_MAP_TILE to 91 / GEN5_TOWN_MAP_TILE, gear.regionMapTile)
        assertEquals(nimbasa, model.pick(0, gear.regionMapTile!!.first, gear.regionMapTile!!.second)?.mapsec)
        // Every arrival flag but Unity Tower's (2494) set, and the Liberty Pass in the bag
        val visited = gear.townsVisited!!
        assertEquals(0xBFFFL, visited and 0xFFFF)
        assertTrue((visited shr GEN5_LIBERTY_PASS_BIT) and 1L != 0L)
        // A place a point: each point's cell names its own place, and every place is on the map
        for (p in townMapPlacesBlack) {
            assertEquals(placeNamesBlack[p.place], p.place, model.pick(0, p.x / GEN5_TOWN_MAP_TILE, p.y / GEN5_TOWN_MAP_TILE)?.mapsec)
        }
        assertEquals(61, model.places().sumOf { it.second.size })
        // 16 towns (and Victory Road, Unity Tower) by their arrival flag, plus Liberty Garden by the pass
        assertEquals(17, model.marks.size)
    }

    @Test
    fun `a wild battle - no FOE TEAM, the foe read from its party`() {
        val t = read("black_wild")
        assertTrue(t.inBattle)
        assertEquals(false, t.isTrainerBattle)
        assertEquals(381 to 100, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { it.species to it.level })
        assertEquals(506 to 2, t.battleMons[BATTLE_POS_OPPONENT_LEFT].let { it.species to it.level })
        assertTrue(t.enemyParty.isEmpty())
        assertEquals(listOf(506), t.battleFoes.map { it.species })
    }

    @Test
    fun `a trainer battle - the battlers, their stats and the foe's party`() {
        val t = read("black_battle")
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        assertEquals(true, t.isTrainerBattle)
        val latios = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(381 to 50, latios.species to latios.level)
        assertEquals(156 to 156, latios.hp to latios.maxHp)
        assertEquals(16 to 14, latios.type1 to latios.type2)   // DRAGON / PSYCHIC in Gen 3's ids
        assertEquals(listOf(406, 473, 58, 85), latios.moves.toList())
        assertEquals(listOf(10, 10, 10, 15), latios.pp.toList())
        assertEquals(26, latios.ability)   // LEVITATE
        assertEquals(listOf(99, 100, 178, 182, 130), latios.stats!!.toList())   // attack, defense, speed, sp. atk, sp. def
        assertEquals(List(7) { 6 }, latios.statStages!!.toList())
        assertEquals(350, t.battleMons[BATTLE_POS_OPPONENT_LEFT].species)
        assertEquals(listOf(350, 419, 160), t.enemyParty.map { it.species })
        assertEquals(0, t.enemyActive)
    }

    @Test
    fun `a full party - the sixth slot reads too`() {
        // GitHub #328: a party of six showed nothing (the sixth slot was read 16 bytes past the party). The fixture has
        // five, so its lead is copied into slot 6 (a Pokemon's encryption doesn't depend on its slot).
        val fixture = FixtureMemoryReader.load("black")
        val party = 0x0221BBACL + GEN5_BLACK.partyOff
        val reader = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray {
                val out = fixture.readCoreMemory(addr, size)
                for (i in out.indices) {
                    val a = addr + i
                    when {
                        a == party + 4 -> out[i] = 6
                        a in party + 8 + 5 * 0xDC until party + 8 + 6 * 0xDC ->
                            out[i] = fixture.readCoreMemory(a - 5 * 0xDC, 1)[0]
                    }
                }
                return out
            }
        }
        activeGame = GameKind.BLACK
        romLanguage = 'E'
        romGameCode = ""
        val t = readGen5Telemetry(reader, GEN5_BLACK)
        assertEquals(listOf(381, 245, 639, 521, 612, 381), t.party.map { it.species })
        assertEquals(302 to 302, t.party[5].hp to t.party[5].maxHp)
        assertEquals(684_260L, t.money)
    }
}
