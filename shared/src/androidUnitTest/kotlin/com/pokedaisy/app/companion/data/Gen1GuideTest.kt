package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Yellow's, Red's and Blue's GUIDE (GuideGen1.kt over GuideGen1Gen.kt) on the Game Boy fixtures: the user's Yellow save and
 * Red save (booted in Blue too), each with all 8 badges, in VERMILION CITY's Pokémon Center. The facts pinned here are
 * pret/pokeyellow's and pret/pokered's (data/wild/maps, data/trainers/parties.asm).
 */
class Gen1GuideTest {
    @After fun tearDown() {
        romLanguage = 'E'
        romGameCode = ""
    }

    private fun read(fixture: String, cfg: Gen1Config, game: GameKind, language: Char = 'E'): SnapshotView {
        activeGame = game
        romLanguage = language
        romGameCode = cfg.gameCode
        return buildSnapshotView(readGen1Telemetry(FixtureMemoryReader.load(fixture), cfg)).copy(game = game)
    }

    @Test
    fun `each game gets its own guide`() {
        assertEquals(GuideId.YELLOW, guideId(GameKind.YELLOW, null))
        assertEquals(GuideId.RED, guideId(GameKind.RED, null))
        assertEquals(GuideId.BLUE, guideId(GameKind.BLUE, null))
        assertTrue(gameGuide(GuideId.YELLOW)!!.world === YELLOW_WORLD)
        assertTrue(gameGuide(GuideId.RED)!!.world === RED_WORLD)
        assertTrue(gameGuide(GuideId.BLUE)!!.world === BLUE_WORLD)
        // The PureRGB hack has a RAM of its own: no guide RAM, so no flags read.
        assertEquals(null, gen1GuideRam(GEN1_PURE_RED))
        assertEquals(null, guideId(GameKind.PURE_RED, null))
    }

    @Test
    fun `the save's badges, in every language`() {
        val saves = listOf(
            Triple("yellow", GEN1_YELLOW, GameKind.YELLOW), Triple("yellow_de", GEN1_YELLOW_DE, GameKind.YELLOW),
            Triple("yellow_fr", GEN1_YELLOW_FR, GameKind.YELLOW), Triple("yellow_it", GEN1_YELLOW_IT, GameKind.YELLOW),
            Triple("yellow_es", GEN1_YELLOW_ES, GameKind.YELLOW), Triple("red", GEN1_RED, GameKind.RED), Triple("blue", GEN1_BLUE, GameKind.BLUE),
            Triple("red_de", GEN1_RED_DE, GameKind.RED), Triple("blue_de", GEN1_BLUE_DE, GameKind.BLUE),
        )
        for ((fixture, cfg, game) in saves) {
            val p = read(fixture, cfg, game, cfg.language).progress
            assertNotNull(fixture, p)
            assertTrue(fixture, (0 until 8).all { p!!.flag(G1_BADGE_FLAG + it) })
            // The European Yellows read the same save at their own addresses: the same flags.
            if (game == GameKind.YELLOW) assertEquals(fixture, read("yellow", GEN1_YELLOW, game).progress, p)
            // German Red / Blue: the red fixture's game written out as their save, read at their own addresses.
            if (cfg.language == 'D' && game != GameKind.YELLOW) assertEquals(fixture, read("red", GEN1_RED, GameKind.RED).progress, p)
        }
    }

    @Test
    fun `HERE on ROUTE 1 and the rods`() {
        activeGame = GameKind.YELLOW
        val route1 = YELLOW_WORLD.encounters(0, 12, null)
        assertEquals(
            listOf(Triple(16, 2..7, 70), Triple(19, 2..4, 30)),
            route1.grass.map { Triple(it.species, it.minLevel..it.maxLevel, it.percent) },
        )
        assertTrue(route1.water.isEmpty() && route1.oldRod.isEmpty() && route1.superRod.isEmpty())
        // PALLET TOWN fishes: MAGIKARP every cast with the OLD ROD; Yellow's SUPER ROD by map.
        val pallet = YELLOW_WORLD.encounters(0, 0, null)
        assertEquals(listOf(129 to 100), pallet.oldRod.map { it.species to it.percent })
        assertEquals(setOf(118, 60), pallet.goodRod.map { it.species }.toSet())
        assertEquals(setOf(120, 72), pallet.superRod.map { it.species }.toSet())
    }

    @Test
    fun `NEXT BOSS has BROCK's team as each game loads it`() {
        val none = SaveProgress(ByteArray(0), ByteArray(0))
        fun brock(world: Gen1World) = world.bosses.first { !it.isDone(none) }.let { world.party(it.trainer(none), none) }
        assertEquals(listOf(74 to 10, 95 to 12), brock(YELLOW_WORLD).map { it.species to it.level })
        assertEquals(listOf(74 to 12, 95 to 14), brock(RED_WORLD).map { it.species to it.level })
        // Red's ONIX ends on BIDE, BROCK's own move.
        assertEquals(117, brock(RED_WORLD)[1].moves.last())
        for (world in listOf(YELLOW_WORLD, RED_WORLD, BLUE_WORLD)) {
            assertEquals(13, world.bosses.size)
            assertEquals("CHAMPION, YOUR RIVAL", world.bosses.last().title)
            for (b in world.bosses) for ((_, id) in b.teams(none)) {
                val t = world.party(id, none)
                assertTrue(b.title, t.size in 1..6 && t.all { it.moves.isNotEmpty() && it.level in 2..100 })
            }
        }
    }

    @Test
    fun `the champion's team follows the rival's pick`() {
        fun team(world: Gen1World, v: Int): List<Pair<String?, Int>> {
            val p = SaveProgress(ByteArray(0), ByteArray(0), varBanks = listOf(SaveBank(G1_VAR_TEAM, 0, 1) to byteArrayOf(v.toByte(), 0)))
            val champion = world.bosses.last()
            return champion.teams(p).map { (label, id) -> label to world.party(id, p).last().species }
        }
        activeGame = GameKind.YELLOW
        // Yellow: wRivalStarter 1 / 2 / 3 = the JOLTEON / FLAREON / VAPOREON team; before it's set, all three.
        assertEquals(listOf(null to 135), team(YELLOW_WORLD, 1))
        assertEquals(listOf(null to 134), team(YELLOW_WORLD, 3))
        assertEquals(3, team(YELLOW_WORLD, 0).size)
        // Red / Blue: the rival's starter's internal id - SQUIRTLE (177) brings BLASTOISE.
        activeGame = GameKind.RED
        assertEquals(listOf(null to 9), team(RED_WORLD, 177))
        assertEquals(listOf(null to 6), team(RED_WORLD, 176))
    }

    @Test
    fun `the League stays done after the Hall of Fame`() {
        val bosses = YELLOW_WORLD.bosses
        val badges = SaveBank(G1_BADGE_FLAG, 0, 8) to byteArrayOf(0xFF.toByte())
        val fresh = SaveProgress(ByteArray(G1Y_EVENT_FLAG_BYTES), ByteArray(0), flagBanks = listOf(badges))
        assertEquals("ELITE FOUR LORELEI", bosses.first { !it.isDone(fresh) }.title)
        // The Hall of Fame resets the League's event flags but hides the CERULEAN CAVE guard for good.
        val guard = bosses.first { it.title == "ELITE FOUR LORELEI" }
        assertFalse(guard.isDone(fresh))
        val missable = ByteArray(G1Y_MISSABLE_FLAG_BYTES).also { it[1] = 0x02 }   // TOGGLE_CERULEAN_CAVE_GUY (9)
        val won = SaveProgress(ByteArray(G1Y_EVENT_FLAG_BYTES), ByteArray(0),
            flagBanks = listOf(badges, SaveBank(G1_MISSABLE_FLAG, 0, G1Y_MISSABLE_FLAG_BYTES * 8) to missable))
        assertTrue(bosses.all { it.isDone(won) })
    }

    @Test
    fun `HERE groups a town's buildings with it`() {
        // VERMILION CITY (5): its Pokémon Center (89) counts with it, and Yellow's SQUIRTLE gift is there.
        assertEquals(5, gen1Area(89))
        val things = areaThings(GuideId.YELLOW, 5)
        assertTrue(things.any { it.kind == AreaKind.MON && it.id == 7 })
        assertTrue(areaThings(GuideId.YELLOW, 89).isEmpty())
    }
}
