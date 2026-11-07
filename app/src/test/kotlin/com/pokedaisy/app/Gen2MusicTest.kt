package com.pokedaisy.app

import com.pokedaisy.app.companion.data.GEN2_MUSIC_CRYSTAL_REV1
import com.pokedaisy.app.companion.data.Gen1Music
import com.pokedaisy.app.companion.data.MemoryReader
import com.pokedaisy.app.companion.data.TelemetrySampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Gen2MusicTest {
    /** Crystal's audio WRAM, C100-C2FF. */
    private class FakeWram : MemoryReader {
        val ram = ByteArray(0x200)
        override fun readCoreMemory(addr: Long, size: Int) = ram.copyOfRange((addr - 0xC100).toInt(), (addr - 0xC100).toInt() + size)
        fun channel(n: Int, id: Int, on: Boolean) {
            val b = 1 + 0x32 * n
            ram[b] = id.toByte()
            ram[b + 3] = if (on) 1 else 0
        }
    }

    @Test fun theSongIsWhatTheChannelsPlayNotWMusicId() {
        // The Thor's state after a save loaded on ROUTE 29: wMusicID still 0x08, the channels playing 0x34.
        val r = FakeWram()
        r.ram[0x19D] = 0x08
        for (n in 0 until 4) r.channel(n, 0x34, on = true)
        assertEquals(Gen1Music.key(0, 0x34), Gen1Music.current(r, GEN2_MUSIC_CRYSTAL_REV1))
        // Channel 1 off (a song without it): the first one that's on.
        r.channel(0, 0x11, on = false)
        assertEquals(Gen1Music.key(0, 0x34), Gen1Music.current(r, GEN2_MUSIC_CRYSTAL_REV1))
    }

    @Test fun silenceIsNoSong() {
        val r = FakeWram()
        r.channel(0, 0x34, on = false)
        assertNull(Gen1Music.current(r, GEN2_MUSIC_CRYSTAL_REV1))
    }

    // GitHub #390: every Gen 2 game but Crystal had no table, so STEADY played the sped-up game audio.
    @Test fun gen2GamesHaveTheirMusicTables() {
        val games = TelemetrySampler.CRYSTAL_CLEAR_SHA1S + TelemetrySampler.POLISHED_CRYSTAL_SHA1S +
            TelemetrySampler.GOLD_SHA1 + TelemetrySampler.SILVER_SHA1
        for (sha1 in games) assertNotNull(sha1, Gen1Music.forSha1(sha1))
    }

    @Test fun goldReadsItsOwnChannels() {
        val t = Gen1Music.forSha1(TelemetrySampler.GOLD_SHA1)!!
        val r = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int) = when (addr) {
                0xC004L -> byteArrayOf(1)        // wChannel1Flags1: on
                0xC001L -> byteArrayOf(0x2A, 0)  // wChannel1MusicID
                else -> ByteArray(size)
            }
        }
        assertEquals(Gen1Music.key(0, 0x2A), Gen1Music.current(r, t))
    }

    @Test fun polishedCrystalsIdSitsSevenBytesBeforeItsFlags() {
        val t = Gen1Music.forSha1(TelemetrySampler.POLISHED_CRYSTAL_V3_2_3_SHA1)!!
        val r = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int) = when (addr) {
                0xCB86L -> byteArrayOf(1)        // wChannel1Flags: on
                0xCB7FL -> byteArrayOf(0x78, 0)  // wChannel1MusicID: ROUTE 1
                else -> ByteArray(size)
            }
        }
        assertEquals(Gen1Music.key(0, 0x78), Gen1Music.current(r, t))
    }
}
