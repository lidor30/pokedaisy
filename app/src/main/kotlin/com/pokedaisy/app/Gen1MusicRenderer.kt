package com.pokedaisy.app

import android.os.Process
import android.util.Log
import com.pokedaisy.app.companion.data.Gen1Music
import com.pokedaisy.app.companion.data.Gen1MusicTables
import com.pokedaisy.app.companion.data.MemoryReader
import com.pokedaisy.app.companion.data.TelemetryDecodeException
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * STEADY FF music for a Game Boy game ([FfMusicRenderer]'s twin): each song rendered on its
 * own on the second core, the game's own PlayMusic called from a parked main loop
 * ([MgbaCore.pkRenderGbPark] / [MgbaCore.pkRenderGbCall]), and cut where the sound engine
 * comes round ([Gen1LoopWatch]). Songs the player hears first, then the whole list once
 * in the background. Keys are [Gen1Music.key], what [Gen1Music.current] reads off the
 * live game. Never touches the player's game or save.
 */
class Gen1MusicRenderer(
    private val rom: File,
    private val tables: Gen1MusicTables,
    private val cache: FfMusicCache,
) : SongRenderer {
    // String keys: heard now (first) or the background pass (last).
    private val queue = LinkedBlockingDeque<Any>()
    private val requested = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    @Volatile private var stopped = false

    fun start() {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                run()
            } catch (t: Throwable) {
                Log.w("pokedaisy", "GB FF music renderer failed", t)
                failed = true
            }
        }, "pokedaisy-gbmusic-render").apply { isDaemon = true; start() }
    }

    override fun request(key: String) {
        if (!stopped && Gen1Music.parse(key) != null && !cache.complete(key) && requested.add(key)) queue.offerFirst(key)
    }

    override fun stop() {
        stopped = true
        queue.offerFirst(STOP)
    }

    // Songs heard live that got no clip: STEADY plays them SPED-UP (see SongRenderer.willRender).
    private val noClip = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    @Volatile private var failed = false

    override fun willRender(key: String): Boolean =
        cache.complete(key) || !failed && !stopped && Gen1Music.parse(key) != null && key !in noClip

    private fun run() {
        val background = if (cache.prefetched) emptyList() else tables.songs.map { (b, id) -> Background(Gen1Music.key(b, id)) }
        background.forEach { queue.offerLast(it) }
        cache.prepare { stopped || queue.peekFirst() is String }
        var left = background.size
        var allOk = true
        var backgroundFull = false
        var core: Core? = null
        try {
            while (!stopped) {
                val job = queue.poll(IDLE_SECONDS, TimeUnit.SECONDS) ?: run {
                    core?.close(); core = null
                    queue.take()
                }
                if (job === STOP || stopped) break
                val bg = job is Background
                val key = if (job is Background) job.key else job as String
                // The cache's budget is full: the background pass stops for this launch (FfMusicRenderer's rule).
                if (bg && !cache.has(key) && (backgroundFull || !cache.roomForBackground())) { backgroundFull = true; continue }
                // A background song that failed on earlier launches too is left alone (FfMusicRenderer's rule).
                val outcome = if (cache.complete(key) || (bg && cache.has(key)) || (bg && cache.failures(key) >= 2)) Outcome.CACHED
                else (core ?: Core().also { core = it }).record(key, bg)
                if (!bg && !cache.complete(key) && (outcome == Outcome.FAILED || outcome == Outcome.ENDED)) noClip.add(key)
                if (bg) {
                    if (outcome == Outcome.DEFERRED) { queue.offerLast(job); continue }
                    if (outcome == Outcome.FAILED) {
                        cache.noteFailure(key)
                        allOk = cache.failures(key) >= 2 && allOk
                    }
                    if (--left == 0 && allOk) cache.prefetched = true
                }
            }
        } finally {
            core?.close()
        }
    }

    private class Background(val key: String)

    private enum class Outcome { CACHED, ENDED, FAILED, DEFERRED }

    /** The render core booted with this ROM and parked, held (under [RenderCoreLock]) until [close]. */
    private inner class Core {
        private val sampleRate: Int
        private val scratch = ShortArray(4096)
        private val parked: Boolean

        init {
            RenderCoreLock.acquire()
            check(MgbaCore.pkRenderInit(rom.absolutePath)) { "render core failed to init" }
            sampleRate = MgbaCore.pkRenderSampleRate()
            // Past the logos to the title screen, then the main loop parks: from here only the
            // VBlank handler runs, and it plays whatever PlayMusic starts.
            repeat(BOOT_FRAMES) { MgbaCore.pkRenderRunFrame() }
            parked = MgbaCore.pkRenderGbPark(tables.spin)
            if (!parked) Log.w("pokedaisy", "GB FF music: couldn't park ${rom.name}'s render core")
        }

        private val buf by lazy { ShortArray(sampleRate * 2 * FfMusicCache.CAPTURE_SECONDS) }
        private val frameStart = IntArray(WATCHDOG_FRAMES + 2)

        fun record(key: String, background: Boolean): Outcome {
            if (!parked) return Outcome.FAILED
            val (bank, id) = Gen1Music.parse(key) ?: return Outcome.FAILED
            repeat(ATTEMPTS) {
                if (!start(bank, id, key)) return Outcome.ENDED.also { Log.i("pokedaisy", "GB FF music: $key didn't start") }
                val loop = Gen1LoopWatch(reader, tables)
                var pos = 0
                var frames = 0
                var stopAt = buf.size
                var ended = false
                while (pos < stopAt && frames < WATCHDOG_FRAMES && !stopped) {
                    if (background && liveRequestWaiting(key)) return Outcome.DEFERRED
                    frameStart[frames++] = pos
                    MgbaCore.pkRenderRunFrame()
                    val n = MgbaCore.pkRenderReadAudio(scratch)
                    val now = Gen1Music.current(reader, tables)
                    if (now != key) { ended = now == null; pos = -1; break }
                    val take = minOf(n, buf.size - pos)
                    if (take > 0) System.arraycopy(scratch, 0, buf, pos, take)
                    pos += take.coerceAtLeast(0)
                    loop.onFrame()
                    if (loop.ended) { ended = true; pos = -1; break }
                    if (loop.found && stopAt == buf.size) stopAt = minOf(buf.size, pos + sampleRate * 2 * LOOP_TAIL_SECONDS)
                }
                frameStart[frames] = pos
                if (stopped) return Outcome.DEFERRED
                if (ended) return Outcome.ENDED.also { Log.i("pokedaisy", "GB FF music: $key ends by itself - no clip") }
                if (pos < 0) return@repeat
                if (loop.found && loop.endFrame <= frames) {
                    M4aLoopSplice.clip(buf, pos, frameStart[loop.startFrame], frameStart[loop.endFrame], sampleRate)?.let { (from, len) ->
                        cache.writeIntro(key, buf, from, sampleRate)
                        cache.writeClip(key, buf, from, len, sampleRate)
                        Log.i("pokedaisy", "GB FF music: recorded $key, a ${len / 2 / sampleRate.toFloat()} s loop")
                        return Outcome.CACHED
                    }
                }
                if (pos >= buf.size) {
                    cache.writeIntro(key, buf, 0, sampleRate)
                    cache.writeClip(key, buf, 0, pos, sampleRate)
                    Log.i("pokedaisy", "GB FF music: recorded $key, no loop seen - kept ${FfMusicCache.CAPTURE_SECONDS} s")
                    return Outcome.CACHED
                }
            }
            Log.w("pokedaisy", "GB FF music: $key kept getting interrupted")
            return Outcome.FAILED
        }

        private fun liveRequestWaiting(key: String): Boolean {
            val head = queue.peekFirst() as? String ?: return false
            return head != key && !cache.complete(head)
        }

        /** PlayMusic(id, bank) from the parked loop; true once its channels are playing it. */
        private fun start(bank: Int, id: Int, key: String): Boolean {
            // Crystal's _PlayMusic restarts only the channels the new song has, so a song after another kept what that
            // one left on the rest (HEAL looped the last song's drums): PlayMusic(0) - _InitSound - silences them first.
            if (tables.crystal && !MgbaCore.pkRenderGbCall(tables.playMusic, 0, 0, tables.spin)) return false
            // Gen 1: a = id, c = bank; Crystal's PlayMusic reads DE, which the call sets from c.
            if (!MgbaCore.pkRenderGbCall(tables.playMusic, id, if (tables.crystal) id else bank, tables.spin)) return false
            repeat(START_FRAMES) {
                MgbaCore.pkRenderRunFrame()
                MgbaCore.pkRenderReadAudio(scratch)
                if (Gen1Music.current(reader, tables) == key) return true
            }
            return false
        }

        fun close() {
            MgbaCore.pkRenderDeinit()
            RenderCoreLock.release()
        }
    }

    private val reader = object : MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int): ByteArray =
            MgbaCore.pkRenderReadBytes(addr, size) ?: throw TelemetryDecodeException("render core read @ 0x${addr.toString(16)} failed")
    }

    private companion object {
        val STOP = Any()
        const val IDLE_SECONDS = 30L
        const val ATTEMPTS = 2
        const val START_FRAMES = 30
        const val BOOT_FRAMES = 600          // ~10 s: past the logos and Pikachu's intro to the title screen
        val WATCHDOG_FRAMES = 60 * (FfMusicCache.CAPTURE_SECONDS + 5)
        const val LOOP_TAIL_SECONDS = 3
    }
}
