package com.pokedaisy.app

import com.pokedaisy.app.companion.data.MemoryReader
import kotlin.test.*

/**
 * [M4aLoopWatch] against a simulated m4a BGM player whose one track walks its
 * command stream a few bytes a frame, and [M4aLoopSplice] against a recording
 * that loops after an intro with a period that isn't a whole number of frames.
 */
class M4aLoopTest {
    /** One BGM player, one track; [cmdPtr] / [level] / [returnTo] are the track's live state. */
    private class Sound : MemoryReader {
        var cmdPtr = SONG
        var level = 0
        var returnTo = 0L
        val rom = HashMap<Long, Int>()

        fun goto(at: Long, target: Long) {
            rom[at] = 0xB2
            for (k in 0 until 4) rom[at + 1 + k] = ((target shr (8 * k)) and 0xFF).toInt()
        }

        override fun readCoreMemory(addr: Long, size: Int): ByteArray {
            fun le(v: Long) = ByteArray(size) { ((v shr (8 * it)) and 0xFF).toByte() }
            return when (addr) {
                0x03007FF0L -> le(INFO)
                INFO + 0x24 -> le(BGM)
                BGM + 0x3C -> le(0)
                BGM + 0x08 -> le(1)
                BGM + 0x2C -> le(TRACKS)
                TRACKS -> le(0x80)
                TRACKS + 0x02 -> le(level.toLong())
                TRACKS + 0x40 -> le(cmdPtr)
                TRACKS + 0x44 -> le(returnTo)
                else -> if (addr >= 0x08000000L) ByteArray(size) { (rom[addr + it] ?: 0).toByte() } else ByteArray(size)
            }
        }
    }

    /** Walks the track [step] bytes a frame from [from], jumping back to [target] at the GOTO at [goto]. */
    private fun play(sound: Sound, watch: M4aLoopWatch, from: Long, target: Long, goto: Long, frames: Int, step: Int = 4) {
        sound.goto(goto, target)
        sound.cmdPtr = from
        repeat(frames) {
            sound.cmdPtr = if (sound.cmdPtr + step > goto) target + (sound.cmdPtr + step - goto - 1) else sound.cmdPtr + step
            watch.onFrame()
            if (watch.found) return
        }
    }

    @Test fun findsTheLoopAfterAnIntro() {
        val sound = Sound()
        val watch = M4aLoopWatch(sound)
        // A 0x40-byte intro (16 frames), then a 0x1C0-byte loop body.
        play(sound, watch, from = SONG, target = SONG + 0x40, goto = SONG + 0x200, frames = 400)
        assertTrue(watch.found)
        assertEquals(16, watch.startFrame)          // the first frame that ran past the loop's start
        assertEquals(0x1C0 / 4, watch.endFrame - watch.startFrame)
    }

    @Test fun aSongWithNoIntroIsTimedBetweenTwoLoops() {
        val sound = Sound()
        val watch = M4aLoopWatch(sound)
        // Already past its loop start (the song's first note) when the watch began.
        play(sound, watch, from = SONG + 0x20, target = SONG, goto = SONG + 0x180, frames = 400)
        assertTrue(watch.found)
        assertEquals(0x180 / 4, watch.endFrame - watch.startFrame)
    }

    @Test fun aPatternCallIsNotALoop() {
        val sound = Sound()
        val watch = M4aLoopWatch(sound)
        repeat(50) { f ->
            // Inside a pattern stored before the main stream: cmdPtr jumps back, the return address doesn't.
            if (f in 20..30) { sound.level = 1; sound.cmdPtr = PATTERN + f; sound.returnTo = SONG + 0x100 }
            else { sound.level = 0; sound.cmdPtr = SONG + 0x100 + f * 4 }
            watch.onFrame()
        }
        assertFalse(watch.found)
    }

    @Test fun theSpliceFindsTheExactPeriod() {
        val rate = 8000
        val intro = 5_731                 // stereo frames
        val period = 20_000 + 61          // 2.5 s and a bit - not a whole number of 60 Hz frames
        val total = intro + 2 * period + 3 * rate
        fun noise(i: Int): Short = ((i * 1103515245 + 12345) ushr 16).toShort()
        val pcm = ShortArray(total * 2)
        for (t in 0 until total) {
            val v = if (t < intro) noise(t + 999_999) else noise((t - intro) % period)
            pcm[t * 2] = v
            pcm[t * 2 + 1] = (v / 2).toShort()
        }
        // The watch's estimates are a frame or so off at each end.
        val clip = M4aLoopSplice.clip(pcm, pcm.size, (intro + 90) * 2, (intro + period - 70) * 2, rate)
        assertNotNull(clip)
        val (from, len) = clip!!
        assertEquals(period * 2, len)
        assertTrue(from / 2 >= intro)     // starts inside the loop, past the intro
    }

    private companion object {
        const val INFO = 0x03001000L
        const val BGM = 0x03002000L
        const val TRACKS = 0x03003000L
        const val SONG = 0x08100000L
        const val PATTERN = 0x08000800L
    }
}
