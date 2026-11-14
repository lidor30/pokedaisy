package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * SHINY ODDS on Gold / Silver / Crystal ([ShinyOdds.GbRoller]) over a fake RAM laid out as the games' own. The numbers are
 * the headless runs' (docs/ROM_FEATURES.md's note 39): a Lv3 SENTRET in Route 29's grass, DVs 4E 61 in Crystal, its stats
 * as the game worked them out.
 */
class GbShinyOddsTest {
    // SENTRET's base stats (HP, Attack, Defense, Speed, Sp. Atk, Sp. Def) and gender ratio, as wBaseStats held them.
    private val sentret = intArrayOf(35, 46, 34, 20, 35, 45)
    private val half = 127

    @Test
    fun statsMatchTheGames() {
        // Crystal: DVs 4E 61 -> [15, 8, 7, 6, 7, 7]; Gold: BA 16 -> [15, 8, 7, 6, 7, 8]; Silver: 15 00 -> [15, 7, 7, 6, 7, 7].
        assertArrayEquals(intArrayOf(15, 8, 7, 6, 7, 7), ShinyOdds.gen2Stats(sentret, 0x4E, 0x61, 3))
        assertArrayEquals(intArrayOf(15, 8, 7, 6, 7, 8), ShinyOdds.gen2Stats(sentret, 0xBA, 0x16, 3))
        assertArrayEquals(intArrayOf(15, 7, 7, 6, 7, 7), ShinyOdds.gen2Stats(sentret, 0x15, 0x00, 3))
        // The most a wild one's can be (no stat experience): base 255, DV 15, Lv100.
        assertEquals(650, ShinyOdds.gen2Stats(intArrayOf(255, 255, 255, 255, 255, 255), 0xFF, 0xFF, 100)[0])
    }

    @Test
    fun shinyRule() {
        for (atk in 0 until 16) assertEquals(atk in ShinyOdds.GEN2_SHINY_ATK, ShinyOdds.gen2Shiny((atk shl 4) or 0x0A, 0xAA))
        assertFalse(ShinyOdds.gen2Shiny(0xEB, 0xAA))
        assertFalse(ShinyOdds.gen2Shiny(0xEA, 0xAB))
    }

    @Test
    fun genderKept() {
        val r = Random(1)
        for (ratio in listOf(0, 31, 63, 127, 191, 0xFE, 0xFF)) for (ad in 0 until 256 step 7) for (ss in 0 until 256 step 11) {
            val new = ShinyOdds.gen2ShinyDvs(ad, ss, ratio, r)
            val female = ShinyOdds.gen2Female(ad, ss, ratio)
            if (new == null) {
                // Only a female of a species whose shinies can't be female (12.5%: Attack DV 0 / 1 only).
                assertEquals(31, ratio); assertEquals(true, female); continue
            }
            assertTrue(ShinyOdds.gen2Shiny(new.first, new.second))
            assertEquals(female, ShinyOdds.gen2Female(new.first, new.second, ratio))
        }
    }

    /** RAM as Crystal's: wEnemyMon and the rest at GB_CRYSTAL's addresses. */
    private class Ram(val t: ShinyOdds.GbTarget) {
        val bytes = HashMap<Long, Byte>()
        fun read(a: Long, n: Int) = ByteArray(n) { bytes[a + it] ?: 0 }
        fun write(a: Long, b: ByteArray): Boolean { b.forEachIndexed { i, v -> bytes[a + i] = v }; return true }
        fun put(a: Long, vararg v: Int) = v.forEachIndexed { i, x -> bytes[a + i] = x.toByte() }
        fun be(a: Long) = ((read(a, 1)[0].toInt() and 0xFF) shl 8) or (read(a + 1, 1)[0].toInt() and 0xFF)

        fun wildSentret(type: Int = 0, species: Int = 161) {
            put(t.battleMode, 1); put(t.battleType, type)
            val s = ShinyOdds.gen2Stats(intArrayOf(35, 46, 34, 20, 35, 45), 0x4E, 0x61, 3)
            put(t.enemyMon, species, 0, 33, 45, 0, 0, 0x4E, 0x61, 35, 40, 0, 0, 70, 3, 0, 0)
            put(t.enemyMon + 16, 0, s[0])
            for (i in 0 until 6) put(t.enemyMon + 18 + i * 2, 0, s[i])
            put(t.baseDexNo, species, 35, 46, 34, 20, 35, 45)
            put(t.baseGender, 127)
        }
    }

    private fun run(ram: Ram, frames: Int = 3, odds: Int = 1): Int {
        val roller = ShinyOdds.GbRoller(ram.t, Random(7))
        var n = 0
        repeat(frames) { n += roller.tick(odds, ram::read, ram::write) }
        return n
    }

    @Test
    fun wildFoeMadeShiny() {
        val ram = Ram(ShinyOdds.GB_CRYSTAL)
        ram.wildSentret()
        assertEquals(1, run(ram))
        val t = ram.t
        val ad = ram.read(t.enemyMon + 6, 1)[0].toInt() and 0xFF
        val ss = ram.read(t.enemyMon + 7, 1)[0].toInt() and 0xFF
        assertTrue(ShinyOdds.gen2Shiny(ad, ss))
        // Female before (4E 61 -> 0x46 <= 127), female after.
        assertEquals(true, ShinyOdds.gen2Female(ad, ss, 127))
        val want = ShinyOdds.gen2Stats(sentret, ad, ss, 3)
        assertEquals(want[0], ram.be(t.enemyMon + 16))   // full HP
        for (i in 0 until 6) assertEquals(want[i], ram.be(t.enemyMon + 18 + i * 2))
        for (i in 0 until 5) assertEquals(want[i + 1], ram.be(t.enemyStats + i * 2))
        // The rest of the struct (PP, happiness, level) as it was.
        assertEquals(35, ram.read(t.enemyMon + 8, 1)[0].toInt())
        assertEquals(3, ram.read(t.enemyMon + 13, 1)[0].toInt())
    }

    @Test
    fun onceABattleAndOnlyWild() {
        for (type in listOf(1, 2, 3, 5, 7)) {   // CANLOSE, DEBUG, TUTORIAL, ROAMING, FORCESHINY
            val ram = Ram(ShinyOdds.GB_GOLD_SILVER)
            ram.wildSentret(type)
            assertEquals("battle type $type", 0, run(ram))
        }
        val trainer = Ram(ShinyOdds.GB_CRYSTAL).apply { wildSentret(); put(t.battleMode, 2) }
        assertEquals(0, run(trainer))
        val link = Ram(ShinyOdds.GB_CRYSTAL).apply { wildSentret(); put(t.linkMode, 1) }
        assertEquals(0, run(link))
        val unown = Ram(ShinyOdds.GB_CRYSTAL).apply { wildSentret(species = 201) }
        assertEquals(0, run(unown))
        // Base data for another species (GetBaseData called since): left alone.
        val other = Ram(ShinyOdds.GB_CRYSTAL).apply { wildSentret(); put(t.baseDexNo, 19) }
        assertEquals(0, run(other))
        // The game's odds: nothing.
        val normal = Ram(ShinyOdds.GB_CRYSTAL).apply { wildSentret() }
        assertEquals(0, run(normal, odds = ShinyOdds.NORMAL))
        // One frame isn't enough to decide (the record must read the same twice).
        val early = Ram(ShinyOdds.GB_CRYSTAL).apply { wildSentret() }
        assertEquals(0, run(early, frames = 1))
        // Decided once: the same battle never rolls again.
        val once = Ram(ShinyOdds.GB_CRYSTAL).apply { wildSentret() }
        assertEquals(1, run(once, frames = 10))
    }

    @Test
    fun femaleThatCantBeShinyIsLeft() {
        assertNull(ShinyOdds.gen2ShinyDvs(0x1A, 0x00, 31, Random(0)))
    }
}
