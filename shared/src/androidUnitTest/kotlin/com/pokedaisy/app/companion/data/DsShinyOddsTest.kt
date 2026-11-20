package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * SHINY ODDS on the DS ([ShinyOdds.DsRoller]) over the game's own memory in its window: the *_shiny_window fixtures, dumped
 * headless on the frame the battle had its setup but hadn't copied the parties yet (their READMEs). The same method made
 * the foe shiny in a running game on every cart of [ShinyOdds.DS_VERIFIED], the battle drawing it in its shiny colours.
 */
class DsShinyOddsTest {
    private class Ram(fixture: String) {
        val reader = FixtureMemoryReader.load(fixture)
        val written = HashMap<Long, Byte>()
        val read = { a: Long, n: Int -> reader.readCoreMemory(a, n).also { b -> for (i in 0 until n) written[a + i]?.let { b[i] = it } } }
        val write = { a: Long, b: ByteArray -> b.forEachIndexed { i, v -> written[a + i] = v }; true }
        fun u32(a: Long) = u32le(read(a, 4), 0)
        fun put32(a: Long, v: Long) = write(a, ByteArray(4) { (v ushr (8 * it)).toByte() })
    }

    private val platinum = ShinyOdds.DsTarget(gen5 = false, systemPtr = 0x021C07DC, battleTemplate = 0x020EA358)
    private val heartGold = ShinyOdds.DsTarget(gen5 = false, systemPtr = 0x021D4158, battleTemplate = 0x020FA484)
    private val black2 = ShinyOdds.DsTarget(gen5 = true, systemPtr = 0x021410F4, battleOverlay = 167)

    /** The foe's record address in a Gen 4 window: sFieldSystem -> process manager -> child -> args (the setup) -> party. */
    private fun gen4Foe(r: Ram, t: ShinyOdds.DsTarget): Long {
        val child = r.u32(r.u32(r.u32(t.systemPtr)) + 4)
        return r.u32(r.u32(child + 0x18) + 8) + 8
    }

    private fun gen5Foe(r: Ram, t: ShinyOdds.DsTarget): Long {
        val proc = r.u32(r.u32(r.u32(t.systemPtr) + 8) + 8)
        return r.u32(r.u32(proc + 0x10) + 0x28) + 8
    }

    private fun checkMadeShiny(fixture: String, t: ShinyOdds.DsTarget, foe: (Ram) -> Long, size: Int, species: Int) {
        val r = Ram(fixture)
        val at = foe(r)
        val before = r.read(at, size)
        val old = assertNotNull(gen4RecordFacts(before)).let { gen4RecordFacts(before)!! }
        assertEquals(fixture, species, old.species)
        assertTrue(shinyValue(old.personality, old.otId) >= 8)
        val roller = ShinyOdds.DsRoller(t, Random(3))
        assertEquals(fixture, 1, roller.tick(1, r.read, r.write))
        val after = r.read(at, size)
        val new = gen4RecordFacts(after)!!
        assertTrue("$fixture: shiny value ${shinyValue(new.personality, new.otId)}", shinyValue(new.personality, new.otId) < 8)
        assertEquals(old.species, new.species)
        assertEquals(old.otId, new.otId)
        assertEquals(old.personality % 25, new.personality % 25)                   // nature (Gen 4's)
        assertEquals(old.personality and 0xFF, new.personality and 0xFF)            // gender, ability bit
        if (t.gen5) assertEquals(old.personality and 0x80010000L, new.personality and 0x80010000L)
        // Everything the companion reads of it is as it was: the record decodes the same but for the personality.
        val a = decodeGen4PartyMon(before, 0, 649, size)!!
        val b = decodeGen4PartyMon(after, 0, 649, size)!!
        assertEquals(
            listOf(a.species, a.level, a.hp, a.maxHp, a.status, a.exp, a.heldItem, a.nickname, a.genderSymbol, a.isEgg),
            listOf(b.species, b.level, b.hp, b.maxHp, b.status, b.exp, b.heldItem, b.nickname, b.genderSymbol, b.isEgg),
        )
        assertArrayEquals(a.moves, b.moves)
        assertArrayEquals(a.pp, b.pp)
        // Looked at once: the next frame leaves it be.
        assertEquals(0, roller.tick(1, r.read, r.write))
    }

    @Test fun platinumWildFoeMadeShiny() = checkMadeShiny("platinum_shiny_window", platinum, { gen4Foe(it, platinum) }, GEN4_MON_SIZE, 396)

    @Test fun heartGoldWildFoeMadeShiny() = checkMadeShiny("heartgold_shiny_window", heartGold, { gen4Foe(it, heartGold) }, GEN4_MON_SIZE, 16)

    @Test fun black2WildFoeMadeShiny() = checkMadeShiny("black2_shiny_window", black2, { gen5Foe(it, black2) }, GEN5_MON_SIZE, 504)

    @Test fun normalOddsTouchNothing() {
        for ((fixture, t) in listOf("platinum_shiny_window" to platinum, "black2_shiny_window" to black2)) {
            val r = Ram(fixture)
            assertEquals(fixture, 0, ShinyOdds.DsRoller(t).tick(ShinyOdds.NORMAL, r.read, r.write))
            assertTrue(r.written.isEmpty())
        }
    }

    /** A trainer's battle (Gen 4's battle type, Gen 5's competitor): left as it is. */
    @Test fun trainerBattlesAreLeftAlone() {
        val p = Ram("platinum_shiny_window")
        val setup = p.u32(p.u32(p.u32(p.u32(platinum.systemPtr)) + 4) + 0x18)
        p.put32(setup, 1)                                  // BATTLE_TYPE_TRAINER
        val before = p.written.size
        assertEquals(0, ShinyOdds.DsRoller(platinum).tick(1, p.read, p.write))
        assertEquals(before, p.written.size)
        val b = Ram("black2_shiny_window")
        val param = b.u32(b.u32(b.u32(b.u32(black2.systemPtr) + 8) + 8) + 0x10)
        b.put32(param, 1)                                  // competitor: a trainer
        assertEquals(0, ShinyOdds.DsRoller(black2).tick(1, b.read, b.write))
    }

    /** Once the battle has made its own copy (its data / work set) the window is shut: nothing is written. */
    @Test fun aClosedWindowIsLeftAlone() {
        val p = Ram("platinum_shiny_window")
        val child = p.u32(p.u32(p.u32(platinum.systemPtr)) + 4)
        p.put32(child + 0x1C, 0x02300000)
        assertEquals(0, ShinyOdds.DsRoller(platinum).tick(1, p.read, p.write))
        val b = Ram("black2_shiny_window")
        val proc = b.u32(b.u32(b.u32(black2.systemPtr) + 8) + 8)
        b.put32(proc + 0x14, 0x02300000)
        assertEquals(0, ShinyOdds.DsRoller(black2).tick(1, b.read, b.write))
    }

    /** A record the game has decrypted in place (its flags set) isn't touched, nor is one whose checksum fails. */
    @Test fun aRecordMidUseIsLeftAlone() {
        val r = Ram("platinum_shiny_window")
        val record = r.read(gen4Foe(r, platinum), GEN4_MON_SIZE)
        assertNotNull(gen4WithPersonality(record, 0x12345678))
        assertNull(gen4WithPersonality(record.copyOf().also { it[4] = 1 }, 0x12345678))
        assertNull(gen4RecordFacts(record.copyOf().also { it[0x20] = (it[0x20] + 1).toByte() }))
        // The same personality back: the very same bytes.
        assertArrayEquals(record, gen4WithPersonality(record, u32le(record, 0)))
    }

    @Test fun verifiedCartsAreTheRetailOnes() {
        assertEquals(setOf("CPUE1", "IPKE0", "IPGE0", "IPKE10", "IPGE10", "IRBO0", "IRAO0", "IREO0", "IRDO0"), ShinyOdds.DS_VERIFIED)
    }

    @Test fun keptBitsAreKept() {
        val random = Random(7)
        repeat(50) {
            val old = random.nextLong() and 0xFFFFFFFFL
            val keep = (1L shl 16) or (1L shl 31)
            val p = ShinyOdds.shinyPersonality(old, 0x12345678L, 504, random, national = true, keepBits = keep) ?: return@repeat
            assertEquals(old and keep, p and keep)
            assertTrue(shinyValue(p, 0x12345678L) < 8)
        }
    }
}
