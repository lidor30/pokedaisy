package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Game Boy games' DVs, stat experience and Polished Crystal's nature (the summary's STATS page): each checked by working
 * the stored stats out again from the species' base stats, the way the games do (CalcMonStats).
 */
class GbStatsTest {
    /** Gen 1 / 2's stat: ((base + DV) * 2 + ceil(sqrt(stat exp)) / 4) * level / 100 + 5; HP + level + 10 instead of 5. */
    private fun gbStat(base: Int, dv: Int, statExp: Int, level: Int, hp: Boolean): Int {
        val exp = kotlin.math.ceil(kotlin.math.sqrt(statExp.toDouble())).toInt() / 4
        val core = ((base + dv) * 2 + exp) * level / 100
        return if (hp) core + level + 10 else core + 5
    }

    /** [base] in [MonStats.stats]' order (HP, Attack, Defense, Speed, Sp. Atk, Sp. Def). */
    private fun assertRecomputes(what: String, base: List<Int>, level: Int, s: MonStats) {
        val worked = List(6) { i ->
            val v = gbStat(base[i], s.ivs[i], if (s.kind.statExp) s.evs[i] else 0, level, hp = i == STAT_HP)
            // Polished Crystal's natures, as Gen 3's: x1.1 / x0.9, rounded down.
            when (i) {
                natureRaises(s.nature) -> v * 110 / 100
                natureLowers(s.nature) -> v * 90 / 100
                else -> v
            }
        }
        assertEquals(what, worked, s.stats)
    }

    @Test
    fun `Gen 1 - DVs and stat experience from the party struct, HP's DV from the others' low bits`() {
        activeGame = GameKind.YELLOW
        val t = readGen1Telemetry(FixtureMemoryReader.load("yellow"), GEN1_YELLOW)
        val pikachu = t.party[0].stats!!
        assertEquals(StatKind.GEN1, pikachu.kind)
        // DV bytes 0xC2 0x04: Attack 12, Defense 2, Speed 0, Special 4; HP = 0b0000.
        assertEquals(listOf(0, 12, 2, 0, 4, 4), pikachu.ivs)
        assertEquals(listOf(7014, 7733, 8275, 7650, 6689, 6689), pikachu.evs)
        assertEquals(NO_NATURE, pikachu.nature)
        assertEquals(listOf(182, 141, 71, 187, 115, 115), pikachu.stats)
        assertFalse(hasHiddenPower(pikachu))
    }

    @Test
    fun `Gen 1 - a wild foe's DVs from its battle struct`() {
        activeGame = GameKind.YELLOW
        val t = readGen1Telemetry(FixtureMemoryReader.load("yellow_battle"), GEN1_YELLOW)
        val foe = t.battleFoes.single()
        assertEquals(4 to 12, foe.species to foe.level)   // CHARMANDER
        // Gen 1 has one Special: base 50 for both of its slots.
        assertRecomputes("CHARMANDER", listOf(39, 52, 43, 65, 50, 50), 12, foe.stats!!)
        assertEquals(listOf(9, 1, 10, 6, 3, 3), foe.stats.ivs)
    }

    @Test
    fun `Gen 2 - party and wild foe, every stat worked out again`() {
        activeGame = GameKind.CRYSTAL
        romLanguage = 'E'
        romGameCode = ""
        val t = readGen2Telemetry(FixtureMemoryReader.load("crystal"), GEN2_CRYSTAL)
        val chikorita = t.party[0].stats!!
        assertEquals(StatKind.GEN2, chikorita.kind)
        assertEquals(listOf(0, 6, 2, 6, 10, 10), chikorita.ivs)
        assertRecomputes("CHIKORITA", listOf(45, 49, 65, 45, 49, 65), 5, chikorita)
        assertTrue(hasHiddenPower(chikorita))

        val battle = readGen2Telemetry(FixtureMemoryReader.load("crystal_battle"), GEN2_CRYSTAL)
        val sentret = battle.battleFoes.single()
        assertEquals(161 to 3, sentret.species to sentret.level)
        assertRecomputes("SENTRET", listOf(35, 46, 34, 20, 35, 45), 3, sentret.stats!!)
    }

    @Test
    fun `Gen 2 Hidden Power - type from Attack and Defense, power from the top bits`() {
        activeGame = GameKind.CRYSTAL
        val perfect = gbDvs(0xFF, 0xFF)
        assertEquals("Dark", gen2HiddenPowerType(perfect).lowercase().replaceFirstChar { it.uppercase() })
        assertEquals(70, gen2HiddenPowerPower(perfect))
        val zero = gbDvs(0x00, 0x00)
        assertEquals("Fighting", gen2HiddenPowerType(zero).lowercase().replaceFirstChar { it.uppercase() })
        assertEquals(31, gen2HiddenPowerPower(zero))
    }

    @Test
    fun `Polished Crystal - six DVs, EVs and its nature, party and wild foe`() {
        activeGame = GameKind.POLISHED_CRYSTAL
        romLanguage = 'E'
        romGameCode = ""
        val t = readGen2Telemetry(FixtureMemoryReader.load("polished_crystal"), GEN2_POLISHED_CRYSTAL)
        val cyndaquil = t.party[0].stats!!
        assertEquals(StatKind.POLISHED, cyndaquil.kind)
        assertEquals(listOf(3, 15, 6, 8, 7, 7), cyndaquil.ivs)
        assertEquals(listOf(0, 0, 0, 1, 0, 0), cyndaquil.evs)
        assertEquals("Mild", natureName(cyndaquil.nature).lowercase().replaceFirstChar { it.uppercase() })
        assertRecomputes("CYNDAQUIL", listOf(39, 52, 43, 65, 60, 50), 5, cyndaquil)
        assertRecomputes("PIDGEY", listOf(40, 45, 40, 56, 35, 35), 3, t.party[1].stats!!)
        assertFalse(hasHiddenPower(cyndaquil))

        val battle = readGen2Telemetry(FixtureMemoryReader.load("polished_crystal_battle"), GEN2_POLISHED_CRYSTAL)
        val sentret = battle.battleFoes.single()
        // Naughty (+Attack, -Sp. Def): Sp. Def 7 without it, 6 with.
        assertRecomputes("SENTRET", listOf(35, 46, 34, 20, 35, 45), 2, sentret.stats!!)
        assertEquals(6, sentret.stats.stats[STAT_SPDEF])
    }

    @Test
    fun `a wild foe's stats are its own, not what a stat stage made of them`() {
        activeGame = GameKind.GOLD
        romLanguage = 'E'
        romGameCode = ""
        // gold_battle_faint: the player's LEER took the wild PIDGEY's Defense a stage down (6 -> 3 in its battle struct).
        val t = readGen2Telemetry(FixtureMemoryReader.load("gold_battle_faint"), GEN2_GOLD)
        val pidgey = t.battleFoes.single()
        assertEquals(16 to 2, pidgey.species to pidgey.level)
        assertEquals(3, t.battleMons[BATTLE_POS_OPPONENT_LEFT].stats!![1])
        assertRecomputes("PIDGEY", listOf(40, 45, 40, 56, 35, 35), 2, pidgey.stats!!)
    }

    /**
     * Every Gen 1 / 2 fixture's party and foes against the base stats in the player's own carts (skips without them:
     * `-DromDir=`'s ../gb/ or ../gbc/): exact for a foe, and for a party Pokémon between its stats with no stat experience
     * and with all of it (the game recomputes them on a level-up only, so stat experience earned since - or at Lv100 -
     * isn't in them yet).
     */
    @Test
    fun `every Game Boy fixture's stats from its cart's base stats`() {
        val roms = File(RETAIL_ROM_DIR).parentFile
        fun rom(name: String) = listOf("gb", "gbc").map { File(File(roms, it), name) }.firstOrNull { it.isFile }?.readBytes()
        fun find(r: ByteArray, vararg b: Int): Int {
            val pat = ByteArray(b.size) { b[it].toByte() }
            return (0..r.size - pat.size).first { i -> pat.indices.all { r[i + it] == pat[it] } }
        }
        fun u(r: ByteArray, o: Int) = r[o].toInt() and 0xFF
        var checked = 0
        fun check(what: String, base: List<Int>, mon: Mon, exact: Boolean) {
            val s = mon.stats!!
            fun at(statExp: Boolean) = List(6) { gbStat(base[it], s.ivs[it], if (statExp) s.evs[it] else 0, mon.level, it == STAT_HP) }
            val (lo, hi) = at(false) to at(true)
            if (exact) assertEquals(what, hi, s.stats)
            else assertTrue("$what: ${s.stats} not between $lo and $hi", s.stats.indices.all { s.stats[it] in lo[it]..hi[it] })
            checked++
        }
        val gen1 = listOf(
            Triple("yellow", GEN1_YELLOW, "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb"),
            Triple("red", GEN1_RED, "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb"),
            Triple("blue", GEN1_BLUE, "Pokemon - Blue Version (USA, Europe) (SGB Enhanced).gb"),
        )
        for ((prefix, cfg, file) in gen1) {
            val r = rom(file) ?: continue
            activeGame = if (prefix == "yellow") GameKind.YELLOW else GameKind.RED
            // BaseStats (dex order, 28 bytes from BULBASAUR's), MEW's apart: HP, ATTACK, DEFENSE, SPEED, SPECIAL.
            val bulbasaur = find(r, 1, 45, 49, 49, 45, 65)
            val mew = find(r, 151, 100, 100, 100, 100, 100)
            fun base(dex: Int): List<Int> {
                val o = if (dex == 151) mew else bulbasaur + 28 * (dex - 1)
                assertEquals(dex, u(r, o))
                return List(5) { u(r, o + 1 + it) } + u(r, o + 5)
            }
            for (f in fixtures(prefix)) {
                val t = readGen1Telemetry(FixtureMemoryReader.load(f), cfg)
                t.party.forEach { check("$f party ${it.species}", base(it.species), it, exact = false) }
                t.battleFoes.forEach { check("$f foe ${it.species}", base(it.species), it, exact = true) }
            }
        }
        val gen2 = listOf(
            Triple("crystal", GEN2_CRYSTAL, "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc"),
            Triple("gold", GEN2_GOLD, "Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc"),
            Triple("silver", GEN2_SILVER, "Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc"),
        )
        for ((prefix, cfg, file) in gen2) {
            val r = rom(file) ?: continue
            activeGame = if (prefix == "crystal") GameKind.CRYSTAL else GameKind.GOLD
            romLanguage = 'E'
            romGameCode = ""
            // BaseStats: 32 bytes a species from BULBASAUR's; HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF.
            val bulbasaur = find(r, 1, 45, 49, 49, 45, 65, 65)
            fun base(species: Int): List<Int> {
                val o = bulbasaur + 32 * (species - 1)
                assertEquals(species, u(r, o))
                return List(6) { u(r, o + 1 + it) }
            }
            for (f in fixtures(prefix)) {
                val t = readGen2Telemetry(FixtureMemoryReader.load(f), cfg)
                t.party.filter { !it.isEgg }.forEach { check("$f party ${it.species}", base(it.species), it, exact = false) }
                t.battleFoes.forEach { check("$f foe ${it.species}", base(it.species), it, exact = true) }
            }
        }
        assumeTrue("no Game Boy carts next to $RETAIL_ROM_DIR", checked > 0)
    }

    /** The English fixtures of one game: `<prefix>`, `<prefix>_battle*`, `<prefix>_trainer` (not the other languages' or the hacks'). */
    private fun fixtures(prefix: String): List<String> =
        File(javaClass.classLoader!!.getResource("fixtures")!!.toURI()).list()!!.filter {
            it == prefix || it.startsWith("${prefix}_battle") || it == "${prefix}_trainer" || it == "${prefix}_rev0" || it == "${prefix}_au"
        }.sorted()

    @Test
    fun `an egg has no stats`() {
        val raw = ByteArray(GEN2_PARTY_MON_SIZE).also { it[0] = 152.toByte(); it[31] = 5 }
        assertNull(decodeGen2PartyMon(raw, 0, GEN2_CRYSTAL, egg = true)!!.stats)
    }
}
