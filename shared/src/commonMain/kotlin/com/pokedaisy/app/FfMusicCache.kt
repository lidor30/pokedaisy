package com.pokedaisy.app

import com.pokedaisy.app.companion.data.*
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * On-device cache of FF background-music clips (see FfMusicPlayer,
 * EmulatorEngine.loop()'s "FF background music" section). Nothing is
 * bundled or downloaded — each clip is one song rendered on its own from
 * the player's ROM by [FfMusicRenderer], keyed by [FfMusicKey] (which song),
 * written here as FLAC ([FlacWriter]: lossless, so loops stay seamless) and
 * reused on every later fast-forward while that song plays. Same shape as
 * [SaveStates]: one directory per ROM (keyed by CRC32), since different ROM
 * hacks can have completely different music. Every ROM's clips share one
 * budget ([FfMusicBudget]): the songs played longest ago go first.
 */
class FfMusicCache(
    filesDir: Path, private val romCrc: String, gameBoy: Boolean = false,
    private val freeBytes: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val failureLock = SynchronizedObject()
    // CACHE_VERSION as a path segment: bump it whenever anything about what
    // gets written changes, so old clips in the
    // previous format are simply never found — rather than being read as
    // valid — and get freshly re-recorded instead of silently kept stale.
    private val root = filesDir / "ffmusic" / "v$CACHE_VERSION"
    private val dir = (root / romCrc).apply { mkdirs() }

    init {
        // Older versions are never read again (v3 keyed clips by a per-game
        // song table that got FireRed's battle theme wrong; v4 had clips
        // captured from the live game, sound effects and all): drop them
        // rather than leave hundreds of MB of WAVs behind.
        (filesDir / "ffmusic").listFiles().filter { it.name != "v$CACHE_VERSION" }.forEach { runCatching { it.deleteRecursively() } }
        // A Game Boy game's clips are recorded again when its sound changes, without touching the GBA games' (a
        // CACHE_VERSION bump would re-render every game's music).
        if (gameBoy && !(dir / GB_SOUND).isFile) {
            clear()
            runCatching { (dir / GB_SOUND).writeText("") }
        }
    }

    private fun file(key: String) = (dir / "$key.flac")
    private fun introFile(key: String) = (dir / "$key.intro.flac")
    // Clips from before FLAC: played as they are until [prepare] converts them.
    private fun wavFile(key: String) = (dir / "$key.wav")
    private fun wavIntroFile(key: String) = (dir / "$key.intro.wav")

    /** Whether FfMusicRenderer has finished a full pass for this ROM. */
    var prefetched: Boolean
        get() = (dir / PREFETCHED).isFile
        set(v) { if (v) (dir / PREFETCHED).writeText("") else (dir / PREFETCHED).delete() }

    fun has(key: String): Boolean = fileIfCached(key) != null

    /** How many launches' background passes [song] (a song id or key) failed in (the pass gives up on it after a couple:
     * retrying it every launch kept the render core busy - and the device hot - for good). */
    fun failures(song: String): Int = failureCounts()[song] ?: 0

    fun noteFailure(song: String) = synchronized(failureLock) {
        val counts = failureCounts().toMutableMap()
        counts[song] = (counts[song] ?: 0) + 1
        runCatching { (dir / FAILED).writeTextAtomically(counts.entries.joinToString("\n") { "${it.key} ${it.value}" }) }
    }

    private fun failureCounts(): Map<String, Int> = runCatching {
        (dir / FAILED).takeIf { it.isFile }?.readLines()?.mapNotNull { l ->
            l.split(' ').takeIf { it.size == 2 }?.let { (a, b) -> b.toIntOrNull()?.let { a to it } }
        }?.toMap()
    }.getOrNull().orEmpty()

    /** The cached clip for [key], or null if nothing's been captured yet. */
    fun fileIfCached(key: String): Path? =
        file(key).takeIf { it.isFile && it.length > 0 } ?: wavFile(key).takeIf { it.isFile && it.length > WAV_HEADER }

    /** What plays once before [key]'s loop: the song from its first note up to
     * where the clip starts (empty for a song kept whole). Null for a clip
     * recorded before intros were kept - it then plays loop-only until the
     * song is heard and recorded again. */
    fun introIfCached(key: String): Path? =
        introFile(key).takeIf { it.isFile && it.length > 0 } ?: wavIntroFile(key).takeIf { it.isFile && it.length > WAV_HEADER }

    /** Both parts there: the loop and its intro (an empty file for a song with none). */
    fun complete(key: String): Boolean = has(key) && (introFile(key).isFile || wavIntroFile(key).isFile)

    /** Writes [len] shorts of [pcm] from [from] as [key]'s clip, a FLAC that
     * FfMusicPlayer loops as is (FfMusicRenderer cuts it at the song's own
     * loop, see M4aLoopSplice), then brings the cache under its budget. */
    fun writeClip(key: String, pcm: ShortArray, from: Int, len: Int, sampleRate: Int) {
        FlacWriter.write(file(key), pcm, len, sampleRate, from)
        wavFile(key).delete()
        trim(keep = key)
    }

    /** Writes the song's first [len] shorts of [pcm] as [key]'s intro: played once,
     * it runs straight into the clip (the clip starts where it ends). A song with
     * none gets an empty file, which says so. */
    fun writeIntro(key: String, pcm: ShortArray, len: Int, sampleRate: Int) {
        if (len > 0) FlacWriter.write(introFile(key), pcm, len, sampleRate, 0) else introFile(key).writeBytes(ByteArray(0))
        wavIntroFile(key).delete()
    }

    /** The game started [key]'s song (FF MUSIC on STEADY): it's kept over songs played longer ago. */
    fun notePlayed(key: String) = FfMusicBudget.notePlayed(root, romCrc, key)

    /** Whether the background pass may record another song: it only fills free room in the budget. */
    fun roomForBackground(): Boolean = FfMusicBudget.hasRoom(root, freeBytes = freeBytes())

    /** Brings the clips under budget, keeping [keep]'s song (just recorded). */
    private fun trim(keep: String? = null) {
        val freed = runCatching { FfMusicBudget.trim(root, setOfNotNull(keep?.let { "$romCrc/$it" }), freeBytes = freeBytes()) }.getOrDefault(0L)
        if (freed > 0) log("FF music: over the cache's budget, freed ${freed shr 20} MB of songs played longest ago")
    }

    /**
     * On the renderer's thread before it records anything: converts this ROM's clips from before FLAC (about half the
     * space; half the time spent waiting, so a big cache doesn't heat the device), then brings every ROM's clips
     * under budget. Other ROMs' WAVs are converted when they're played next; a song the player is hearing ([waiting])
     * comes first, the rest wait for the next launch (a WAV still plays).
     */
    fun prepare(
        waiting: () -> Boolean,
        keepLegacyTime: (Path, Path) -> Unit = { _, _ -> },
        rest: (Long) -> Unit = {},
    ) {
        val wavs = dir.listFiles().filter { it.isFile && it.name.endsWith(".wav") }
        var converted = 0
        for (wav in wavs) {
            if (waiting()) break
            val began = TimeSource.Monotonic.markNow()
            runCatching {
                val flac = dir / (wav.name.removeSuffix(".wav") + ".flac")
                val pcm = readWav(wav)
                if (pcm == null) wav.delete()
                else {
                    if (pcm.second.isEmpty()) flac.writeBytes(ByteArray(0)) else FlacWriter.write(flac, pcm.second, pcm.second.size, pcm.first)
                    keepLegacyTime(wav, flac)   // keeps its place among the never-played (best effort)
                    wav.delete()
                    converted++
                }
            }.onFailure { log("FF music: couldn't convert ${wav.name}: ${it.message}") }
            val took = began.elapsedNow().inWholeMilliseconds
            if (took > 0) rest(took)
        }
        if (converted > 0) log("FF music: converted $converted clips to FLAC")
        trim()
    }

    /** Deletes every cached clip for this ROM — a manual escape hatch if a
     * capture ever comes out wrong (e.g. captured mid-transition). */
    fun clear() {
        dir.listFiles().forEach { it.delete() }
    }

    companion object {
        /** Bump whenever the cache format changes (e.g. [CAPTURE_SECONDS] or
         * the loop-detection logic) so stale clips from a previous version
         * are never mistaken for current ones — see the constructor's `dir`.
         * v6: loops cut where the sound engine loops (v5's audio guess cut
         * battle themes to a few seconds and looped them back to the intro). */
        const val CACHE_VERSION = 6

        /** Marks a Game Boy game's clips as recorded with its current sound. Bump it when that changes. v2:
         * pk_gb_audio.c's resampling (GitHub #269 / #327) - clips made through mGBA's own blip_buf before it
         * kept its pops, so FF crackled while 1x didn't. */
        private const val GB_SOUND = ".gb-sound-v2"
        private const val PREFETCHED = ".prefetched"
        private const val WAV_HEADER = 44
        private const val FAILED = ".failed"

        /** Longest recording per key. Recording stops a few seconds after the
         * song's loop is seen (M4aLoopWatch), so this only has to cover the
         * longest loop - twice over for a song with no intro, whose period is
         * measured between two loops. A song that never loops within it is
         * kept whole. Prefetching runs in the background, so a generous
         * window costs nothing anyone waits on. */
        const val CAPTURE_SECONDS = 100
    }
}

/** Reads the PCM WAVs written by earlier versions of the cache. */
fun readWav(file: Path): Pair<Int, ShortArray>? {
    val bytes = file.readBytes()
    if (bytes.size < 44) return null
    fun ascii(at: Int) = bytes.decodeToString(at, at + 4)
    fun le16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    fun le32(at: Int) = le16(at) or (le16(at + 2) shl 16)
    if (ascii(0) != "RIFF" || ascii(8) != "WAVE" || ascii(12) != "fmt " || ascii(36) != "data") return null
    if (le16(20) != 1 || le16(22) != 2 || le16(34) != 16) return null
    val dataBytes = minOf(le32(40), bytes.size - 44).coerceAtLeast(0) and 3.inv()
    return le32(24) to ShortArray(dataBytes / 2) { le16(44 + it * 2).toShort() }
}
