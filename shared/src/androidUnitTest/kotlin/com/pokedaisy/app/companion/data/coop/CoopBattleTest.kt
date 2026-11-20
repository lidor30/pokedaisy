package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.FixtureMemoryReader
import com.pokedaisy.app.companion.data.gen3SubstructOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopBattleTest {

    /** emerald_vanilla's gPlayerParty (EWRAM +0x244EC) and gPlayerPartyCount (+0x244E9). */
    private val party: ByteArray
    private val count: Int

    init {
        val mem = FixtureMemoryReader.load("emerald_vanilla")
        party = mem.readCoreMemory(0x02000000L + 0x244EC, 6 * CoopBattle.MON_SIZE)
        count = mem.readCoreMemory(0x02000000L + 0x244E9, 1)[0].toInt() and 0xFF
    }

    private fun mon(slot: Int) = party.copyOfRange(slot * CoopBattle.MON_SIZE, (slot + 1) * CoopBattle.MON_SIZE)

    private fun u32(b: ByteArray, off: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[off + i].toLong() and 0xFF) shl (8 * i)) }

    private fun putU32(b: ByteArray, off: Int, v: Long) { for (i in 0 until 4) b[off + i] = (v shr (8 * i)).toByte() }

    /** Decrypts [mon]'s substructs into logical order (Growth, Attacks, EVs, Misc), lets [edit] change them, re-encrypts with a fixed checksum. */
    private fun reencoded(mon: ByteArray, edit: (ByteArray) -> Unit): ByteArray {
        val out = mon.copyOf()
        val personality = u32(mon, 0)
        val key = personality xor u32(mon, 4)
        val order = gen3SubstructOrder[(personality % 24).toInt()]
        val data = ByteArray(48)
        for (slot in 0 until 4) for (w in 0 until 3)
            putU32(data, order[slot] * 12 + w * 4, u32(mon, 0x20 + slot * 12 + w * 4) xor key)
        edit(data)
        var sum = 0
        for (i in 0 until 24) sum = (sum + ((data[i * 2].toInt() and 0xFF) or ((data[i * 2 + 1].toInt() and 0xFF) shl 8))) and 0xFFFF
        out[0x1C] = sum.toByte(); out[0x1D] = (sum shr 8).toByte()
        for (slot in 0 until 4) for (w in 0 until 3)
            putU32(out, 0x20 + slot * 12 + w * 4, u32(data, order[slot] * 12 + w * 4) xor key)
        return out
    }

    @Test fun fixturePartyMonsPass() {
        assertTrue(count in 1..6)
        for (slot in 0 until count) assertEquals("slot $slot", CoopMonCheck.Ok, CoopBattle.check(mon(slot)))
    }

    @Test fun reencodingAnUnchangedMonKeepsItsBytes() {
        assertArrayEquals(mon(0), reencoded(mon(0)) {})
    }

    @Test fun flippedByteFailsChecksum() {
        val m = mon(0)
        m[40] = (m[40].toInt() xor 0x01).toByte()
        assertTrue(CoopBattle.check(m) is CoopMonCheck.Refused)
    }

    @Test fun eggIsRefused() {
        // Misc substruct (logical 3) +4 is the IV word; bit 30 is isEgg.
        val m = reencoded(mon(0)) { d -> d[36 + 7] = (d[36 + 7].toInt() or 0x40).toByte() }
        assertTrue(CoopBattle.check(m) is CoopMonCheck.Refused)
    }

    @Test fun outOfRangeSpeciesIsRefused() {
        val m = reencoded(mon(0)) { d -> d[0] = (412 and 0xFF).toByte(); d[1] = (412 shr 8).toByte() }
        assertTrue(CoopBattle.check(m) is CoopMonCheck.Refused)
    }

    private fun refusal(m: ByteArray): String = (CoopBattle.check(m) as CoopMonCheck.Refused).reason

    private fun putU16(d: ByteArray, off: Int, v: Int) { d[off] = v.toByte(); d[off + 1] = (v shr 8).toByte() }

    @Test fun unusedSpeciesSlotsAreRefused() {
        for (sp in intArrayOf(252, 276)) {
            val m = reencoded(mon(0)) { d -> putU16(d, 0, sp) }
            assertEquals("species $sp", refusal(m))
        }
    }

    @Test fun heldItemPastTheLastIsRefused() {
        assertEquals("item 377", refusal(reencoded(mon(0)) { d -> putU16(d, 2, 377) }))
        assertEquals(CoopMonCheck.Ok, CoopBattle.check(reencoded(mon(0)) { d -> putU16(d, 2, 376) }))
    }

    @Test fun moveIdPastTheLastIsRefused() {
        assertEquals("move 355", refusal(reencoded(mon(0)) { d -> putU16(d, 12, 355) }))
        assertEquals(CoopMonCheck.Ok, CoopBattle.check(reencoded(mon(0)) { d -> putU16(d, 12, 354); d[20] = 1 }))
    }

    @Test fun ppAboveTheMovesMaxIsRefused() {
        // SCRATCH (10) has 35 PP: 35 is fine, 36 is not; with all three PP Ups 56 is the most.
        fun with(pp: Int, bonuses: Int) = reencoded(mon(0)) { d -> putU16(d, 12, 10); d[8] = bonuses.toByte(); d[20] = pp.toByte() }
        assertEquals(CoopMonCheck.Ok, CoopBattle.check(with(35, 0)))
        assertEquals("PP 36 over 35 for move 10", refusal(with(36, 0)))
        assertEquals(CoopMonCheck.Ok, CoopBattle.check(with(56, 3)))
        assertEquals("PP 57 over 56 for move 10", refusal(with(57, 3)))
    }

    @Test fun firstThreeSkipsFainted() {
        assertTrue("the fixture needs 4+ party mons", count >= 4)
        val p = party.copyOf()
        p[86] = 0; p[87] = 0
        val packed = CoopBattle.firstThreeAble(p, count)
        assertEquals(3 * CoopBattle.MON_SIZE, packed.size)
        assertArrayEquals(mon(1), packed.copyOfRange(0, 100))
        assertArrayEquals(mon(2), packed.copyOfRange(100, 200))
        assertArrayEquals(mon(3), packed.copyOfRange(200, 300))
    }

    @Test fun aBadChecksumMonIsSkippedNeverSent() {
        assertTrue("the fixture needs 4+ party mons", count >= 4)
        val p = party.copyOf()
        p[40] = (p[40].toInt() xor 0x01).toByte()   // slot 0's data no longer matches its checksum
        val packed = CoopBattle.firstThreeAble(p, count)
        assertEquals(3 * CoopBattle.MON_SIZE, packed.size)
        assertArrayEquals(mon(1), packed.copyOfRange(0, 100))
        assertArrayEquals(mon(3), packed.copyOfRange(200, 300))
    }

    @Test fun armArgumentsCountIsTheSizeOverAHundred() {
        val p = party.copyOf()
        p[40] = (p[40].toInt() xor 0x01).toByte()
        val a = CoopBattle.armArguments(p, count)!!
        assertEquals(a.partner.size / 100, a.count)
        assertEquals(minOf(3, count - 1), a.count)
        assertNull(CoopBattle.armArguments(p, 1))   // the only mon is the bad one: nothing to arm with
    }
}
