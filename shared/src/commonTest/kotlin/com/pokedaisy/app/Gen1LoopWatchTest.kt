package com.pokedaisy.app

import com.pokedaisy.app.companion.data.GEN1_MUSIC_YELLOW
import com.pokedaisy.app.companion.data.Gen1Music
import com.pokedaisy.app.companion.data.MemoryReader
import kotlin.test.*

class Gen1LoopWatchTest {
    /** WRAM C000-C0FF whose CHAN1 command pointer is [ptr] and note delay [delay]. */
    private class FakeWram : MemoryReader {
        val ram = ByteArray(0x100)
        override fun readCoreMemory(addr: Long, size: Int) = ram.copyOfRange((addr - 0xC000).toInt(), (addr - 0xC000).toInt() + size)
        fun set(ptr: Int, delay: Int) {
            ram[0x06] = ptr.toByte(); ram[0x07] = (ptr shr 8).toByte()
            ram[0xB6] = delay.toByte()
        }
    }

    @Test fun loopIsWhereTheChannelStateRepeats() {
        val r = FakeWram()
        val w = Gen1LoopWatch(r, GEN1_MUSIC_YELLOW)
        // A 60-frame intro, then a 300-frame loop: note every 10 frames, pointer +2 per note.
        for (f in 0 until 1000) {
            val ptr = if (f < 60) 0x4000 + f / 10 * 2 else 0x5000 + (f - 60) % 300 / 10 * 2
            r.set(ptr, 10 - f % 10)
            w.onFrame()
        }
        assertTrue(w.found)
        assertEquals(61, w.startFrame)
        assertEquals(361, w.endFrame)
        assertFalse(w.ended)
    }

    @Test fun aSongThatStopsHasEnded() {
        val r = FakeWram()
        val w = Gen1LoopWatch(r, GEN1_MUSIC_YELLOW)
        for (f in 0 until 200) { r.set(0x4000 + minOf(f, 40) / 10 * 2, if (f < 40) 10 - f % 10 else 0); w.onFrame() }
        assertTrue(w.ended)
        assertFalse(w.found)
    }

    @Test fun keysNameBankAndSong() {
        val r = FakeWram()
        assertNull(Gen1Music.current(r, GEN1_MUSIC_YELLOW))
        r.ram[0x26] = 240.toByte(); r.ram[0xEF] = 8
        assertEquals("gb_08_f0", Gen1Music.current(r, GEN1_MUSIC_YELLOW))
        assertEquals(8 to 240, Gen1Music.parse("gb_08_f0"))
    }
}
