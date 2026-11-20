package com.pokedaisy.app

import com.pokedaisy.app.companion.data.Gen1Music
import com.pokedaisy.app.companion.data.Gen1MusicTables
import kotlin.concurrent.Volatile
import okio.Path

/**
 * STEADY FF music for a Game Boy game ([FfMusicRenderer]'s twin): each song rendered on its
 * own on the second core, the game's own PlayMusic called from a parked main loop
 * (the render core's GB park and call functions), and cut where the sound engine
 * comes round ([Gen1LoopWatch]). Songs the player hears first, then the whole list once
 * in the background. Keys are [Gen1Music.key], what [Gen1Music.current] reads off the
 * live game. Never touches the player's game or save.
 */
class Gen1MusicRenderer(
    private val rom: Path,
    private val tables: Gen1MusicTables,
    private val cache: FfMusicCache,
    private val host: MusicRenderHost,
) : SongRenderer {
    // String keys: heard now (first) or the background pass (last).
    private val queue = SongRenderQueue()
    private val requested = SeenSongs()
    @Volatile private var stopped = false

    override fun start() = host.start("pokedaisy-gbmusic-render", onFailure = {
        host.log("GB FF music renderer failed", it)
        failed = true
    }) { run() }

    override fun request(key: String) {
        if (!stopped && Gen1Music.parse(key) != null && !cache.complete(key) && requested.add(key)) queue.offerFirst(key)
    }

    override fun stop() {
        stopped = true
        queue.offerFirst(STOP)
    }

    // Songs heard live that got no clip: STEADY plays them SPED-UP (see SongRenderer.willRender).
    private val noClip = SeenSongs()
    @Volatile private var failed = false

    override fun willRender(key: String): Boolean =
        cache.complete(key) || !failed && !stopped && Gen1Music.parse(key) != null && key !in noClip

    private suspend fun run() {
        val background = if (cache.prefetched) emptyList() else tables.songs.map { (b, id) -> Background(Gen1Music.key(b, id)) }
        background.forEach { queue.offerLast(it) }
        host.prepareCache(cache) { stopped || queue.peekFirst() is String }
        var left = background.size
        var allOk = true
        var backgroundFull = false
        var core: Core? = null
        try {
            while (!stopped) {
                val job = queue.poll(IDLE_SECONDS * 1000L) ?: run {
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

    /** The render core booted with this ROM and parked. */
    private inner class Core {
        private val render = host.open(rom)
        private val sampleRate get() = render.sampleRate
        private val scratch = ShortArray(4096)
        private val parked = try {
            // Past the logos to the title screen, then the main loop parks. Only VBlank keeps playing sound.
            repeat(BOOT_FRAMES) { render.frame() }
            render.parkGb(tables.spin).also { if (!it) host.log("GB FF music: couldn't park ${rom.name}'s render core") }
        } catch (error: Throwable) {
            render.close()
            throw error
        }

        private val buf by lazy { ShortArray(sampleRate * 2 * FfMusicCache.CAPTURE_SECONDS) }
        private val frameStart = IntArray(WATCHDOG_FRAMES + 2)

        fun record(key: String, background: Boolean): Outcome {
            if (!parked) return Outcome.FAILED
            val (bank, id) = Gen1Music.parse(key) ?: return Outcome.FAILED
            repeat(ATTEMPTS) {
                if (!start(bank, id, key)) return Outcome.ENDED.also { host.log("GB FF music: $key didn't start") }
                val loop = Gen1LoopWatch(render, tables)
                var pos = 0
                var frames = 0
                var stopAt = buf.size
                var ended = false
                while (pos < stopAt && frames < WATCHDOG_FRAMES && !stopped) {
                    if (background && liveRequestWaiting(key)) return Outcome.DEFERRED
                    frameStart[frames++] = pos
                    render.frame()
                    val n = render.audio(scratch)
                    val now = Gen1Music.current(render, tables)
                    if (now != key) { ended = now == null; pos = -1; break }
                    val take = minOf(n, buf.size - pos)
                    if (take > 0) scratch.copyInto(buf, pos, 0, take)
                    pos += take.coerceAtLeast(0)
                    loop.onFrame()
                    if (loop.ended) { ended = true; pos = -1; break }
                    if (loop.found && stopAt == buf.size) stopAt = minOf(buf.size, pos + sampleRate * 2 * LOOP_TAIL_SECONDS)
                }
                frameStart[frames] = pos
                if (stopped) return Outcome.DEFERRED
                if (ended) return Outcome.ENDED.also { host.log("GB FF music: $key ends by itself - no clip") }
                if (pos < 0) return@repeat
                if (loop.found && loop.endFrame <= frames) {
                    M4aLoopSplice.clip(buf, pos, frameStart[loop.startFrame], frameStart[loop.endFrame], sampleRate)?.let { (from, len) ->
                        cache.writeIntro(key, buf, from, sampleRate)
                        cache.writeClip(key, buf, from, len, sampleRate)
                        host.log("GB FF music: recorded $key, a ${len / 2 / sampleRate.toFloat()} s loop")
                        return Outcome.CACHED
                    }
                }
                if (pos >= buf.size) {
                    cache.writeIntro(key, buf, 0, sampleRate)
                    cache.writeClip(key, buf, 0, pos, sampleRate)
                    host.log("GB FF music: recorded $key, no loop seen - kept ${FfMusicCache.CAPTURE_SECONDS} s")
                    return Outcome.CACHED
                }
            }
            host.log("GB FF music: $key kept getting interrupted")
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
            if (tables.crystal && !render.gbCall(tables.playMusic, 0, 0, tables.spin)) return false
            // Gen 1: a = id, c = bank; Crystal's PlayMusic reads DE, which the call sets from c.
            if (!render.gbCall(tables.playMusic, id, if (tables.crystal) id else bank, tables.spin)) return false
            repeat(START_FRAMES) {
                render.frame()
                render.audio(scratch)
                if (Gen1Music.current(render, tables) == key) return true
            }
            return false
        }

        fun close() = render.close()
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
