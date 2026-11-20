package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** INSTANT TEXT on the Gen 4 DS games ([DsInstantText]): the printer task's call found, the routine's calls filled, on / off. */
class DsInstantTextTest {
    private val mem = HashMap<Long, Byte>()
    private val read = { a: Long, n: Int -> ByteArray(n) { mem[a + it] ?: 0 } }
    private val write = { a: Long, b: ByteArray -> b.forEachIndexed { i, v -> mem[a + i] = v }; true }
    private fun put(a: Long, b: ByteArray) { write(a, b) }

    /** HeartGold's RunTextPrinter switch at [site] - 2, calling RenderFont at [render] and CopyWindowToVram at [copy]. */
    private fun task(at: Long, render: Long, copy: Long) {
        put(at, byteArrayOf(0x20, 0x1C))
        put(at + 2, DsInstantText.bl(at + 2, render))
        put(at + 6, byteArrayOf(0x00, 0x28, 0x04, 0xD0.toByte(), 0x01, 0x28, 0x0E, 0xD0.toByte(), 0x03, 0x28, 0x03, 0xD0.toByte(),
            0x10, 0xBD.toByte(), 0x60, 0x68))
        put(at + 22, DsInstantText.bl(at + 22, copy))
    }

    private fun target(b: ByteArray, at: Long): Long {
        val h1 = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val h2 = (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8)
        var off = ((h1 and 0x7FF) shl 12) or ((h2 and 0x7FF) shl 1)
        if (off and 0x400000 != 0) off -= 0x800000
        return at + 4 + off
    }

    @Test fun heartGoldsTaskFoundAndTheRoutineCallsItsFunctions() {
        task(0x02020310, render = 0x02020358, copy = 0x0201D578)
        val f = DsInstantText.find(read)!!
        assertEquals(0x02020312L, f.site)
        assertEquals(DsInstantText.ROUTINE, target(f.patched, f.site))
        assertEquals(0x02020358L, target(f.routine.copyOfRange(0x08, 0x0C), DsInstantText.ROUTINE + 0x08))
        assertEquals(0x0201D578L, target(f.routine.copyOfRange(0x2A, 0x2E), DsInstantText.ROUTINE + 0x2A))
    }

    @Test fun onThenOffThenAStateFromTheOtherSetting() {
        task(0x0201D9A0, render = 0x0201D9E8, copy = 0x0201A000)   // Platinum's place
        val f = DsInstantText.find(read)!!
        DsInstantText.sync(f, true, read, write)
        assertArrayEquals(f.patched, read(f.site, 4))
        assertArrayEquals(f.routine, read(DsInstantText.ROUTINE, f.routine.size))
        DsInstantText.sync(f, false, read, write)
        assertArrayEquals(f.original, read(f.site, 4))
        // A state saved with it on, loaded with it off: put back on the next check.
        put(f.site, f.patched)
        DsInstantText.sync(f, false, read, write)
        assertArrayEquals(f.original, read(f.site, 4))
    }

    @Test fun twoLookAlikesOrNoneFindNothing() {
        assertNull(DsInstantText.find(read))
        task(0x02020310, 0x02020358, 0x0201D578)
        task(0x02030310, 0x02020358, 0x0201D578)
        assertNull(DsInstantText.find(read))
    }
}
