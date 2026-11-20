package com.pokedaisy.app

import com.pokedaisy.app.companion.data.FfMusicKey
import com.pokedaisy.app.companion.data.Gfx
import com.pokedaisy.app.companion.data.MemoryReader

/**
 * Where a song loops, read from the m4a sound engine itself while it plays on
 * the render core (FfMusicRenderer) - not guessed from the audio. Every Gen 3
 * song's tracks end in a GOTO back to their loop start (after any intro), so
 * the BGM player's first track jumping backwards through a GOTO marks the
 * loop's end, and the frame its stream first ran past the GOTO's target marks
 * the start. A song that loops from its very first note started before the
 * watch did, so for it the period is taken between two GOTOs instead.
 *
 * (An earlier audio-only detector compared the first 2 s of a recording with
 * later audio: on battle themes, which repeat a bar every few seconds, it cut
 * clips to ~3 s, and it always looped back to the intro.)
 *
 * Offsets from pokefirered's include/gba/m4a_internal.h (shared by every
 * agbcc-built game and binary hack): MusicPlayerInfo.trackCount +0x08,
 * .tracks +0x2C; MusicPlayerTrack (0x50 bytes) .flags +0x00, .patternLevel
 * +0x02, .cmdPtr +0x40, .patternStack[0] +0x44.
 */
class M4aLoopWatch(private val reader: MemoryReader) {
    /** Per rendered frame: the track's position in its main command stream
     * (inside a pattern, where that pattern returns to), or -1 if unreadable. */
    private val history = ArrayList<Long>()
    private var track = -1
    private var firstGoto = -1

    /** The frame whose processing started the loop, and the one that jumped back; -1 until [found]. */
    var startFrame = -1
        private set
    var endFrame = -1
        private set
    val found: Boolean get() = endFrame >= 0

    /** Call once after each rendered frame, in order from the first. */
    fun onFrame() {
        val f = history.size
        val main = mainPos() ?: -1L
        history += main
        if (found || f == 0) return
        val prev = history[f - 1]
        if (main < 0 || prev < 0 || main >= prev) return
        val target = gotoTarget(prev, main) ?: return
        // The first frame that ran the loop's first command, if it was seen happen.
        val crossed = (0 until f).firstOrNull { history[it] > target }
        if (crossed != null && crossed > 0 && history[crossed - 1] in 0..target) {
            startFrame = crossed
            endFrame = f
        } else if (firstGoto >= 0) {
            startFrame = firstGoto
            endFrame = f
        } else {
            firstGoto = f
        }
    }

    private fun mainPos(): Long? = runCatching {
        val bgm = FfMusicKey.bgmPlayer(reader) ?: return null
        val tracks = u32(bgm + TRACKS)
        if (!FfMusicKey.inRam(tracks)) return null
        if (track < 0) {
            // The first track that exists, kept for the whole recording.
            val count = minOf(u8(bgm + TRACK_COUNT), MAX_TRACKS)
            track = (0 until count).firstOrNull { u8(tracks + it * TRACK_SIZE) and FLAG_EXIST != 0 } ?: return null
        }
        val t = tracks + track * TRACK_SIZE
        val pos = if (u8(t + PATTERN_LEVEL) == 0) u32(t + CMD_PTR) else u32(t + PATTERN_STACK)
        pos.takeIf { Gfx.inRom(it) }
    }.getOrNull()

    /** The target of a GOTO (0xB2 + pointer) just past [prev] that lands at or before [now], or null. */
    private fun gotoTarget(prev: Long, now: Long): Long? {
        val bytes = runCatching { reader.readCoreMemory(prev, GOTO_SCAN) }.getOrNull() ?: return null
        for (i in 0..bytes.size - 5) {
            if (bytes[i].toInt() and 0xFF != GOTO) continue
            val ptr = Gfx.u32(bytes, i + 1)
            if (Gfx.inRom(ptr) && ptr <= now && ptr < prev + i) return ptr
        }
        return null
    }

    private fun u8(addr: Long): Int = reader.readCoreMemory(addr, 1)[0].toInt() and 0xFF
    private fun u32(addr: Long): Long = Gfx.u32(reader.readCoreMemory(addr, 4), 0)

    private companion object {
        const val TRACK_COUNT = 0x08L
        const val TRACKS = 0x2CL
        const val TRACK_SIZE = 0x50
        const val PATTERN_LEVEL = 0x02L
        const val CMD_PTR = 0x40L
        const val PATTERN_STACK = 0x44L
        const val FLAG_EXIST = 0x80
        const val MAX_TRACKS = 16
        const val GOTO = 0xB2
        const val GOTO_SCAN = 128   // a frame's worth of commands past where the track stood
    }
}

/** Cuts a seamless loop out of a recording, given where the song's loop starts and ends. */
object M4aLoopSplice {
    /**
     * [pcm] holds [len] interleaved stereo shorts; [loopStart] / [loopEnd] are
     * short offsets of the loop's start and end, to within a frame or so (m4a
     * steps once per frame, and its tempo phase can drift by one). The clip
     * starts a second into the loop - past notes still ringing out from the
     * intro - and its length is that estimate tuned to the sample by matching
     * the audio at its start against candidates one period on.
     *
     * @return the clip as (first short, length in shorts), or null if the
     * recording doesn't hold a full period past the clip's start.
     */
    fun clip(pcm: ShortArray, len: Int, loopStart: Int, loopEnd: Int, sampleRate: Int): Pair<Int, Int>? {
        val total = len / 2
        val start = loopStart / 2
        val p0 = (loopEnd - loopStart) / 2
        val w = sampleRate / 10            // 100 ms compared
        val r = sampleRate / 60 * 3        // +- 3 frames searched
        if (p0 <= r + w) return null
        var s = start + sampleRate
        if (s + p0 + r + w > total) s = total - p0 - r - w
        if (s < start) return null
        var best = p0
        var bestErr = Long.MAX_VALUE
        for (p in p0 - r..p0 + r) {
            var err = 0L
            var i = 0
            while (i < w * 2 && err < bestErr) {
                val d = pcm[s * 2 + i].toLong() - pcm[(s + p) * 2 + i].toLong()
                err += d * d
                i++
            }
            if (err < bestErr) {
                bestErr = err
                best = p
            }
        }
        return s * 2 to best * 2
    }
}
