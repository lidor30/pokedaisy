package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** REUSABLE TMS on the Gen 4 DS games ([DsReusableTms]): LearnMoveToSlot's `bne` past Bag_TakeItem made a `b`. */
class DsReusableTmsTest {
    private val mem = HashMap<Long, Byte>()
    private val read = { a: Long, n: Int -> ByteArray(n) { mem[a + it] ?: 0 } }
    private val write = { a: Long, b: ByteArray -> b.forEachIndexed { i, v -> mem[a + i] = v }; true }

    /** HeartGold's code from its `bl MoveIsHM` (0x020825A0) to its `bl Bag_TakeItem`. */
    private fun learnMoveToSlot(at: Long) {
        val bytes = listOf(0xF5, 0xF7, 0x40, 0xFD, 0x00, 0x28, 0x07, 0xD1, 0x0A, 0x48, 0x01, 0x22, 0x29, 0x58, 0x0C, 0x23,
            0x48, 0x68, 0x09, 0x8D, 0xF5, 0xF7, 0x3E, 0xFF)
        bytes.forEachIndexed { i, v -> mem[at + i] = v.toByte() }
    }

    @Test fun theBneFoundAndMadeAB() {
        learnMoveToSlot(0x020825A0)
        val f = DsReusableTms.find(read)!!
        assertEquals(0x020825A6L, f.site)
        assertArrayEquals(byteArrayOf(0x07, 0xD1.toByte()), f.original)
        assertArrayEquals(byteArrayOf(0x07, 0xE0.toByte()), f.patched)   // b, the same 14 bytes on
        DsReusableTms.sync(f, true, read, write)
        assertArrayEquals(f.patched, read(f.site, 2))
        DsReusableTms.sync(f, false, read, write)
        assertArrayEquals(f.original, read(f.site, 2))
    }

    @Test fun noneOrTwoFindNothing() {
        assertNull(DsReusableTms.find(read))
        learnMoveToSlot(0x020825A0); learnMoveToSlot(0x020865E4)
        assertNull(DsReusableTms.find(read))
    }
}
