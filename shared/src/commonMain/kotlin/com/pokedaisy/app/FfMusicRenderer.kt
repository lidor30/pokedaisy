package com.pokedaisy.app

import com.pokedaisy.app.companion.data.EMERALD_LOCALIZED_CODES
import com.pokedaisy.app.companion.data.FfMusicKey
import com.pokedaisy.app.companion.data.M4aSongs
import com.pokedaisy.app.companion.data.MapMusicEmerald
import com.pokedaisy.app.companion.data.MapMusicFireRed
import kotlin.concurrent.Volatile
import okio.Path
import com.pokedaisy.app.companion.data.readBytes
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import okio.FileSystem
import okio.SYSTEM

/** What the activity drives: FfMusicRenderer (GBA) or Gen1MusicRenderer (Game Boy). */
interface SongRenderer {
    fun start()
    /** Render [key]'s song soon, unless it's cached or already asked for. */
    fun request(key: String)
    fun stop()

    /** Whether [key]'s clip is coming (cached, or still to be rendered): STEADY stays silent through FF
     * while it is, and plays the song SPED-UP only when no clip ever will (an unrecognised sound engine,
     * a song that couldn't be recorded or ends by itself, a renderer that stopped). */
    fun willRender(key: String): Boolean
}

/**
 * Records STEADY FF-music clips: each song rendered on its own on a second,
 * disposable mGBA core by
 * calling the ROM's own `m4aSongNumStart` - only the music, never the live
 * game's mix (which carried menu clicks and battle sounds into the clips).
 * Never touches the player's running game or save.
 *
 * Works on any ROM whose sound engine [M4aSongs] recognises. Songs are
 * [request]ed as the game starts playing them (at any speed, so a clip is
 * usually ready before fast-forward needs it), ahead of a one-time pass over
 * the ROM's whole music list in the background - FireRed / LeafGreen /
 * Emerald-sized ROMs their map + battle list
 * ([MapMusicFireRed] / [MapMusicEmerald]), any other ROM every song its table
 * plays on the BGM player - so a song is rarely heard sped up before its clip
 * exists. A requested song pre-empts the background pass mid-recording. Clips are keyed by [FfMusicKey] read back from the render core - the
 * key the live game produces for that song.
 *
 * It also renders the game's menu click (SE_SELECT) and level-up fanfare
 * (MUS_LEVEL_UP, for RetroAchievements unlocks) for [RenderedGameSound], first
 * thing, once per ROM.
 */
class FfMusicRenderer(
    private val rom: Path,
    private val romCrc: String,
    private val cache: FfMusicCache,
    private val host: MusicRenderHost,
    private val click: RenderedGameSound? = null,
    private val fanfare: RenderedGameSound? = null,
    private val prefetch: Boolean = true,
) : SongRenderer {
    // String keys (played now: first), Int song ids (prefetch: last) or CLICK (first of all).
    private val queue = SongRenderQueue()
    private val requested = SeenSongs()
    // Songs heard live that got no clip (FAILED / ENDED / not in the song table): STEADY plays them SPED-UP.
    private val noClip = SeenSongs()
    @Volatile private var stopped = false

    /** False once the ROM's sound engine turned out unrecognised (STEADY then has no clips here). */
    @Volatile var supported = true
        private set

    override fun start() = host.start("pokedaisy-ffmusic-render", onFailure = {
        host.log("FF music renderer failed", it)
        supported = false
    }) { run() }

    override fun request(key: String) {
        if (!stopped && !cache.complete(key) && requested.add(key)) queue.offerFirst(key)
    }

    override fun stop() {
        stopped = true
        queue.offerFirst(STOP)
    }

    override fun willRender(key: String): Boolean = cache.complete(key) || supported && !stopped && key !in noClip

    private suspend fun run() {
        val (songs, prefetch) = locateSongs() ?: run {
            supported = false
            host.log("FF music: ${rom.name}'s sound engine isn't recognised - no STEADY clips")
            return
        }
        prefetch.forEach { queue.offerLast(it) }
        if (fanfare?.hasOwn == false && fanfareSongId() != null) queue.offerFirst(FANFARE)
        if (click?.hasOwn == false) queue.offerFirst(CLICK)
        // The click / fanfare and a song being heard come before converting old clips.
        host.prepareCache(cache) { stopped || queue.peekFirst().let { it is String || it === CLICK || it === FANFARE } }
        var prefetchLeft = prefetch.size
        var prefetchOk = true
        var backgroundFull = false
        var core: RenderCore? = null
        try {
            while (!stopped) {
                // Idle for a while: give the core's memory back until the next request.
                val job = queue.poll(IDLE_SECONDS * 1000L) ?: run {
                    core?.close(); core = null
                    queue.take()
                }
                if (job === STOP || stopped) break
                if (job === CLICK || job === FANFARE) {
                    val c = core ?: RenderCore(songs).also { core = it }
                    if (job === CLICK) c.recordSfx(songs, SE_SELECT, CLICK_FRAMES, click, "SE_SELECT")
                    else fanfareSongId()?.let { c.recordSfx(songs, it, FANFARE_FRAMES, fanfare, "MUS_LEVEL_UP") }
                    continue
                }
                val songId = when (job) {
                    is Int -> job
                    is String -> if (cache.complete(job)) null else songs.songId(job)
                    else -> null
                }
                // A background song that failed on earlier launches too is left alone (it counts as done).
                val givenUp = job is Int && cache.failures(job.toString()) >= MAX_SONG_FAILURES
                // The cache's budget is full: the background pass stops for this launch, never marked done, and never
                // pushes a song out (songs being heard still are recorded, and push out the ones played longest ago).
                if (job is Int && songId != null && !givenUp && (backgroundFull || !cache.roomForBackground())) {
                    if (!backgroundFull) host.log("FF music: the cache is full - no more background songs this time")
                    backgroundFull = true
                    continue
                }
                val outcome = if (songId == null || givenUp) Outcome.CACHED
                else (core ?: RenderCore(songs).also { core = it }).record(songs, songId, background = job is Int)
                if (job is String && !cache.complete(job) && (songId == null || outcome == Outcome.FAILED || outcome == Outcome.ENDED)) {
                    noClip.add(job)
                }
                if (job is Int) {
                    // A song the player is hearing came first: this one goes back in line.
                    if (outcome == Outcome.DEFERRED) { queue.offerLast(job); continue }
                    if (outcome == Outcome.FAILED) {
                        cache.noteFailure(job.toString())
                        prefetchOk = cache.failures(job.toString()) >= MAX_SONG_FAILURES && prefetchOk
                    }
                    if (--prefetchLeft == 0 && prefetchOk) cache.prefetched = true
                }
            }
        } finally {
            core?.close()
        }
    }

    /** The ROM's songs and the ones to render once in the background - the ROM's bytes are let go after. */
    private fun locateSongs(): Pair<M4aSongs, List<Int>>? {
        val romBytes = rom.readBytes()
        val songs = M4aSongs.locate(romBytes) ?: return null
        return songs to if (!prefetch || cache.prefetched) emptyList() else prefetchList(romBytes, songs)
    }

    /** The songs to render once in the background: see the class comment. */
    private fun prefetchList(romBytes: ByteArray, songs: M4aSongs): List<Int> {
        val code = if (romBytes.size >= 0xB0) romBytes.decodeToString(0xAC, 0xB0) else ""
        val retail = romBytes.size <= RETAIL_MAX_BYTES
        return when {
            // Recharged Yellow is FireRed-sized, but its song ids aren't FireRed's: all its BGM, like a hack.
            retail && (code.startsWith("BPR") || code.startsWith("BPG")) && !com.pokedaisy.app.companion.data.isRechargedYellowRom(romBytes) ->
                MapMusicFireRed.allSongIds.toList()
            retail && (code == "BPEE" || code in EMERALD_LOCALIZED_CODES) -> MapMusicEmerald.allSongIds.toList()
            else -> songs.bgmSongIds
        }
    }

    /** How a [RenderCore.record] went. */
    private enum class Outcome {
        CACHED,
        /** Nothing to loop: the song never started, or ended on its own (a fanfare). */
        ENDED,
        /** The booted game kept switching songs over it. */
        FAILED,
        /** A background recording stopped for a song the player is hearing now. */
        DEFERRED,
    }

    /** The render core booted with this ROM. */
    private inner class RenderCore(songs: M4aSongs) {
        private val render = host.open(rom)
        private val sampleRate get() = render.sampleRate
        private val scratch = ShortArray(4096)

        init {
            try {
                // Let boot settle (logos / title) so the sound engine is set up,
                // then park the game so its intro can't switch songs mid-recording.
                // (Unparked, a take the game interrupts is simply retried.) Some
                // games keep the engine paused through long splash screens (Emerald
                // Rogue: ~15 s), where a forced song never starts - so past
                // BOOT_FRAMES, wait for the game's own music to play.
                repeat(BOOT_FRAMES) { render.frame() }
                var extra = 0
                while (FfMusicKey.current(render) == null && extra++ < MAX_BOOT_FRAMES - BOOT_FRAMES) render.frame()
                val parked = songs.idleLoop?.let { render.parkGba(it) } ?: false
                if (!parked) host.log("FF music: couldn't park ${rom.name}'s render core")
            } catch (error: Throwable) {
                render.close()
                throw error
            }
        }

        // One recording's worth (~19 MB at 48 kHz), shared by every song this core records.
        private val buf by lazy { ShortArray(sampleRate * 2 * FfMusicCache.CAPTURE_SECONDS) }
        private val frameStart = IntArray(WATCHDOG_FRAMES + 1)   // each frame's first short in buf

        /**
         * Plays [songId] alone and caches one seamless loop of it, cut where
         * the sound engine itself loops ([M4aLoopWatch]); a song whose loop
         * isn't seen within CAPTURE_SECONDS is kept whole. A [background]
         * recording gives way to a song the player is hearing now.
         */
        suspend fun record(songs: M4aSongs, songId: Int, background: Boolean): Outcome {
            repeat(ATTEMPTS) {
                val key = start(songs, songId) ?: return Outcome.ENDED.also { host.log("FF music: song ${M4aSongs.label(songId)} didn't start") }
                // A song being heard is redone if its clip predates intros; the background pass isn't.
                if (if (background) cache.has(key) else cache.complete(key)) return Outcome.CACHED
                val loop = M4aLoopWatch(render)
                var pos = 0
                var frames = 0
                // The background pass runs at most BACKGROUND_SPEED x real time: flat out it kept a core busy for
                // minutes (heat, on a handheld) for clips nobody is waiting for. A song being heard goes flat out.
                val paceFrom = TimeSource.Monotonic.markNow()
                var stopAt = buf.size
                var ended = false
                while (pos < stopAt && frames < WATCHDOG_FRAMES && !stopped) {
                    if (background && liveRequestWaiting(songs, songId)) return Outcome.DEFERRED
                    frameStart[frames++] = pos
                    if (background) {
                        val ahead = frames * BACKGROUND_FRAME_NANOS - paceFrom.elapsedNow().inWholeNanoseconds
                        if (ahead > 2_000_000L) delay(ahead / 1_000_000L)
                    }
                    render.frame()
                    val n = render.audio(scratch)
                    // The song stopped by itself (a fanfare: nothing to loop), or the
                    // booted game changed the music (its intro moved on): this take
                    // is spoiled - start the song again.
                    val now = FfMusicKey.current(render)
                    if (now != key) { ended = now == null; pos = -1; break }
                    val take = minOf(n, buf.size - pos)
                    if (take > 0) scratch.copyInto(buf, pos, 0, take)
                    pos += take.coerceAtLeast(0)
                    loop.onFrame()
                    // Once the loop is known, only a little more is needed to cut it.
                    if (loop.found && stopAt == buf.size) stopAt = minOf(buf.size, pos + sampleRate * 2 * LOOP_TAIL_SECONDS)
                }
                if (stopped) return Outcome.DEFERRED
                if (ended) return Outcome.ENDED.also { host.log("FF music: song ${M4aSongs.label(songId)} ends by itself - no clip") }
                if (pos < 0) return@repeat
                if (loop.found) {
                    M4aLoopSplice.clip(buf, pos, frameStart[loop.startFrame], frameStart[loop.endFrame], sampleRate)?.let { (from, len) ->
                        // The recording starts at the song's first note: all of it up to the
                        // clip is the intro, which runs seamlessly into the clip.
                        cache.writeIntro(key, buf, from, sampleRate)
                        cache.writeClip(key, buf, from, len, sampleRate)
                        host.log("FF music: recorded song ${M4aSongs.label(songId)} as $key, a ${len / 2 / sampleRate.toFloat()} s loop")
                        return Outcome.CACHED
                    }
                }
                if (pos >= buf.size) {
                    cache.writeIntro(key, buf, 0, sampleRate)   // kept whole: it starts at the top already
                    cache.writeClip(key, buf, 0, pos, sampleRate)
                    host.log("FF music: recorded song ${M4aSongs.label(songId)} as $key, no loop seen - kept ${FfMusicCache.CAPTURE_SECONDS} s")
                    return Outcome.CACHED
                }
            }
            host.log("FF music: song ${M4aSongs.label(songId)} kept getting interrupted")
            return Outcome.FAILED
        }

        /** A song the player is hearing now waits at the queue's head (and it isn't [songId]). */
        private fun liveRequestWaiting(songs: M4aSongs, songId: Int): Boolean {
            val head = queue.peekFirst() as? String ?: return false
            return !cache.complete(head) && songs.songId(head).let { it != null && it != songId }
        }

        /** Plays [songId] alone for [frames] (the booted game's music stopped first) and hands it to [sink]. */
        fun recordSfx(songs: M4aSongs, songId: Int, frames: Int, sink: RenderedGameSound?, label: String) {
            val fn = songs.songNumStart and 1L.inv()
            // MUS_DUMMY replaces whatever the title screen was playing with silence.
            if (!forceSong(fn, MUS_DUMMY, 0)) return
            repeat(SILENCE_FRAMES) { render.frame(); render.audio(scratch) }
            if (!forceSong(fn, songId, 0)) return
            val buf = ShortArray(sampleRate * 2 * frames / 60 + scratch.size)
            var pos = 0
            repeat(frames) {
                render.frame()
                val n = minOf(render.audio(scratch), buf.size - pos)
                if (n > 0) scratch.copyInto(buf, pos, 0, n)
                pos += n.coerceAtLeast(0)
            }
            sink?.write(buf, pos, sampleRate)
            host.log("game sound: rendered $label (${pos / 2} frames)")
        }

        /** Starts [songId] via the ROM's m4aSongNumStart; the key it plays under, once its tracks run. */
        private fun start(songs: M4aSongs, songId: Int): String? {
            val fn = songs.songNumStart and 1L.inv()
            if (!forceSong(fn, M4aSongs.number(songId), M4aSongs.alt(songId))) return null
            // MPlayStart clears the player's status; the next sound tick marks its tracks playing.
            repeat(START_FRAMES) {
                render.frame()
                render.audio(scratch)
                FfMusicKey.current(render)?.let { return it }
            }
            return null
        }

        /**
         * Calls m4aSongNumStart, a frame at a time until the core lets it run: pk_call refuses while a frame ended inside
         * the game's interrupt handler, not on the parked `b .` (Let's Go Pikachu's render core, after a long recording:
         * every song after it "didn't start", 2026-11-03), as mgba_dump's `call` waits for.
         */
        private fun forceSong(fn: Long, number: Int, alt: Int): Boolean {
            repeat(SAFE_CALL_FRAMES) {
                if (render.gbaSong(fn, number, alt)) return true
                render.frame()
                render.audio(scratch)
            }
            return false
        }

        fun close() = render.close()
    }

    /**
     * MUS_LEVEL_UP's number in this ROM's song table, by the header's game code:
     * FireRed's table (and LeafGreen's, and the CFRU hacks' that keep FireRed's
     * code) has it at 257, Emerald's and Ruby / Sapphire's at 367. Any other
     * game: null, as song 257 there could be anything.
     */
    private fun fanfareSongId(): Int? {
        val head = runCatching {
            FileSystem.SYSTEM.read(rom) { readByteArray(0x200) }
        }.getOrNull() ?: return null
        // Recharged Yellow's song table is ordered like Emerald's: its sFanfares[0] (FANFARE_LEVEL_UP) is 367.
        if (com.pokedaisy.app.companion.data.isRechargedYellowRom(head)) return 367
        // Monomyth v1.1 has Emerald's game code but a song table of its own: it borrows a fanfare, like v1.0.
        if (head.decodeToString(0xA0, 0xAC) == "POKEMON MONO") return null
        // Pokémon Clover is FireRed 1.0 under Emerald's game code (and title): FireRed's song table.
        if (com.pokedaisy.app.companion.data.isFireRedUnderEmeraldHeader(head)) return 257
        return when (head.decodeToString(0xAC, 0xAF)) {
            "BPR", "BPG", "PUV" -> 257 // PUV: Ultra Violet, FireRed's song table
            "BPE", "AXV", "AXP" -> 367
            else -> null
        }
    }

    private companion object {
        val STOP = Any()
        val CLICK = Any()
        val FANFARE = Any()

        /** FireRed / Emerald-sized ROMs share the retail music lists; bigger ones are hacks. */
        const val RETAIL_MAX_BYTES = 16 shl 20

        // Song numbers every Gen 3 song table shares (include/constants/songs.h).
        const val MUS_DUMMY = 0
        const val SE_SELECT = 5
        const val SILENCE_FRAMES = 20                                 // the title music's release tail
        const val CLICK_FRAMES = 45                                   // SE_SELECT is ~0.25 s
        const val FANFARE_FRAMES = 240                                // MUS_LEVEL_UP is ~1.5 s; trimmed after

        const val IDLE_SECONDS = 30L
        const val ATTEMPTS = 2
        /** Launches a background song may fail in before the pass stops trying it. */
        const val MAX_SONG_FAILURES = 2
        /** The background pass's cap: 10x real time (a frame every 1.67 ms). */
        const val BACKGROUND_FRAME_NANOS = 1_000_000_000L / 60 / 10
        const val START_FRAMES = 30                                   // up to half a second for a forced song to start
        const val SAFE_CALL_FRAMES = 60                               // up to a second for a frame m4aSongNumStart may run in
        const val BOOT_FRAMES = 300                                   // ~5 s of game time at 60 fps
        const val MAX_BOOT_FRAMES = 1800                              // 30 s: waiting for the title music
        val WATCHDOG_FRAMES = 60 * (FfMusicCache.CAPTURE_SECONDS + 5)
        const val LOOP_TAIL_SECONDS = 3   // past the loop's end: the clip starts 1 s in, plus the splice search
    }
}
