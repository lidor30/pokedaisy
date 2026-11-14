package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The DS games' IVs, EVs and nature (the summary's STATS page): Gen 4 / 5 keep them in the record the party decode already
 * decrypts ([decodeGen4PartyMon]). Checked the way the GBA's were: every Black / White / Black 2 / White 2 fixture's party and
 * foes worked out again from the base stats in Black 2's / White 2's own personal records (skips without the ROMs: `-DromDir=`'s ../nds/), and
 * Platinum / SoulSilver fixture Pokémon from their public base stats.
 */
class DsStatsTest {
    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")

    @After
    fun reset() {
        NdsArt.dir = null
    }

    /** Alternate forms' base stats (HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF): the DEX's lines hold species 1-649's own
     * only. KYUREM's Black / White forms (Black 2 / White 2's story). */
    private val FORMS = mapOf(
        646 to listOf(listOf(125, 170, 100, 95, 120, 90), listOf(125, 120, 90, 95, 170, 100)),
    )

    /** The Gen 3+ stat: (2 x base + IV + EV / 4) x level / 100, + level + 10 for HP, else + 5 and the nature's 1.1 / 0.9. */
    private fun stat(base: Int, iv: Int, ev: Int, level: Int, stat: Int, nature: Int): Int {
        val core = (2 * base + iv + ev / 4) * level / 100
        if (stat == STAT_HP) return core + level + 10
        val v = core + 5
        return when (stat) {
            natureRaises(nature) -> v * 110 / 100
            natureLowers(nature) -> v * 90 / 100
            else -> v
        }
    }

    @Test
    fun `Black, White, Black 2 and White 2 - every fixture's stats from the cart's base stats`() {
        val black2 = "Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced).nds"
        // Black / White's species and base stats are Black 2's (Gen 5 changed none between them): their fixtures are checked
        // against Black 2's cart, read through their own configs.
        val carts = listOf(
            Triple(GameKind.BLACK2, black2, listOf("black2", "black2_nimbasa", "black2_wild")),
            Triple(GameKind.WHITE2, "Pokemon - White Version 2 (USA, Europe) (NDSi Enhanced).nds", listOf("white2")),
            Triple(GameKind.BLACK, black2, listOf("black", "black_battle", "black_route1", "black_wild")),
            Triple(GameKind.WHITE, black2, listOf("white", "white_forest", "white_route1", "white_wild")),
        )
        var checked = 0
        for ((kind, file, fixtures) in carts) {
            val rom = File(nds, file)
            if (!rom.isFile) continue
            val dir = kotlin.io.path.createTempDirectory("nds-stats").toFile()
            try {
                assertTrue(NdsArt.extractTo(rom, dir))
                NdsArt.dir = dir
                activeGame = kind
                romLanguage = 'E'
                romGameCode = ""
                val cfg = when (kind) {
                    GameKind.WHITE2 -> GEN5_WHITE2
                    GameKind.BLACK -> GEN5_BLACK
                    GameKind.WHITE -> GEN5_WHITE
                    else -> GEN5_BLACK2
                }
                for (f in fixtures) {
                    val t = readGen5Telemetry(FixtureMemoryReader.load(f), cfg)
                    for (mon in (t.party + t.battleFoes).filter { !it.isEgg }) {
                        val s = assertNotNull("$f ${mon.species}", mon.stats).let { mon.stats!! }
                        // The DEX's lines keep the page's order: HP, ATTACK, DEFENSE, SP. ATK, SP. DEF, SPEED.
                        val page = Gen4Dex.entry(GEN5_DEX_BLACK2, mon.species)!!.baseStats
                        val bases = listOf(listOf(page[0], page[1], page[2], page[5], page[3], page[4])) + FORMS[mon.species].orEmpty()
                        fun fits(base: List<Int>): Boolean {
                            val withEvs = List(6) { stat(base[it], s.ivs[it], s.evs[it], mon.level, it, s.nature) }
                            val noEvs = List(6) { stat(base[it], s.ivs[it], 0, mon.level, it, s.nature) }
                            // The game recomputes stats on a level-up only: EVs earned since aren't in them yet.
                            return s.stats.indices.all { s.stats[it] in noEvs[it]..withEvs[it] }
                        }
                        assertTrue(
                            "$f ${mon.species} Lv${mon.level}: ${s.stats} from ${bases.first()} (IVs ${s.ivs}, EVs ${s.evs}, nature ${s.nature})",
                            bases.any(::fits),
                        )
                        checked++
                    }
                }
            } finally {
                NdsArt.dir = null
                dir.deleteRecursively()
            }
        }
        assumeTrue("no Black 2 / White 2 carts in $nds", checked > 0)
        println("DsStatsTest: $checked Pokémon checked")
    }

    /**
     * Platinum and HeartGold / SoulSilver (no cart needed): fixture Pokémon whose base stats are public, every stat exact -
     * Gen 4's nature is the personality's (Calm's lower ATTACK and higher SP. DEF show on the wild one).
     */
    @Test
    fun `Platinum and SoulSilver - stats worked out again from IVs, EVs and nature`() {
        romLanguage = 'E'
        romGameCode = ""
        fun check(kind: GameKind, fixture: String, cfg: Gen4Config, foe: Boolean, species: Int, base: List<Int>, nature: Int) {
            activeGame = kind
            val t = readGen4Telemetry(FixtureMemoryReader.load(fixture), cfg)
            val mon = (if (foe) t.battleFoes else t.party).first { it.species == species }
            val s = mon.stats!!
            assertEquals("$fixture $species nature", nature, s.nature)
            assertEquals("$fixture $species", List(6) { stat(base[it], s.ivs[it], s.evs[it], mon.level, it, s.nature) }, s.stats)
        }
        // Base stats: HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF.
        check(GameKind.PLATINUM, "platinum", GEN4_PLATINUM_US, false, 387, listOf(55, 68, 64, 31, 45, 55), 6)          // Docile
        check(GameKind.PLATINUM, "platinum_battle", GEN4_PLATINUM_US, true, 390, listOf(44, 58, 44, 61, 58, 44), 20)   // Calm
        check(GameKind.SOULSILVER, "soulsilver", GEN4_SOULSILVER_US, false, 157, listOf(78, 84, 78, 100, 109, 85), 6) // Lv100, EVs
    }

    @Test
    fun `an egg has no stats, and Gen 5's own nature byte wins`() {
        activeGame = GameKind.BLACK2
        romLanguage = 'E'
        romGameCode = ""
        val t = readGen5Telemetry(FixtureMemoryReader.load("black2"), GEN5_BLACK2)
        assertTrue(t.party.isNotEmpty())
        for (mon in t.party) {
            if (mon.isEgg) assertEquals(null, mon.stats)
            else assertTrue("${mon.species}: ${mon.stats}", mon.stats!!.nature in 0..24 && mon.stats!!.ivs.all { it in 0..31 })
        }
    }
}
