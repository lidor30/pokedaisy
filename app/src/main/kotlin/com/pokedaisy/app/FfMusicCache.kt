package com.pokedaisy.app

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

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
class FfMusicCache(filesDir: File, private val romCrc: String) {
    // CACHE_VERSION as a path segment: bump it whenever anything about what
    // gets written changes, so old clips in the
    // previous format are simply never found — rather than being read as
    // valid — and get freshly re-recorded instead of silently kept stale.
    private val root = File(File(filesDir, "ffmusic"), "v$CACHE_VERSION")
    private val dir = File(root, romCrc).apply { mkdirs() }

    init {
        // Older versions are never read again (v3 keyed clips by a per-game
        // song table that got FireRed's battle theme wrong; v4 had clips
        // captured from the live game, sound effects and all): drop them
        // rather than leave hundreds of MB of WAVs behind.
        File(filesDir, "ffmusic").listFiles()?.filter { it.name != "v$CACHE_VERSION" }?.forEach { it.deleteRecursively() }
    }

    private fun file(key: String) = File(dir, "$key.flac")
    private fun introFile(key: String) = File(dir, "$key.intro.flac")
    // Clips from before FLAC: played as they are until [prepare] converts them.
    private fun wavFile(key: String) = File(dir, "$key.wav")
    private fun wavIntroFile(key: String) = File(dir, "$key.intro.wav")

    /** Whether FfMusicRenderer has finished a full pass for this ROM. */
    var prefetched: Boolean
        get() = File(dir, PREFETCHED).isFile
        set(v) { if (v) File(dir, PREFETCHED).writeText("") else File(dir, PREFETCHED).delete() }

    fun has(key: String): Boolean = fileIfCached(key) != null

    /** How many launches' background passes [song] (a song id or key) failed in (the pass gives up on it after a couple:
     * retrying it every launch kept the render core busy - and the device hot - for good). */
    fun failures(song: String): Int = failureCounts()[song] ?: 0

    fun noteFailure(song: String) = synchronized(this) {
        val counts = failureCounts().toMutableMap()
        counts[song] = (counts[song] ?: 0) + 1
        runCatching { File(dir, FAILED).writeText(counts.entries.joinToString("\n") { "${it.key} ${it.value}" }) }
    }

    private fun failureCounts(): Map<String, Int> = runCatching {
        File(dir, FAILED).takeIf { it.isFile }?.readLines()?.mapNotNull { l ->
            l.split(' ').takeIf { it.size == 2 }?.let { (a, b) -> b.toIntOrNull()?.let { a to it } }
        }?.toMap()
    }.getOrNull().orEmpty()

    /** The cached clip for [key], or null if nothing's been captured yet. */
    fun fileIfCached(key: String): File? =
        file(key).takeIf { it.isFile && it.length() > 0 } ?: wavFile(key).takeIf { it.isFile && it.length() > WAV_HEADER }

    /** What plays once before [key]'s loop: the song from its first note up to
     * where the clip starts (empty for a song kept whole). Null for a clip
     * recorded before intros were kept - it then plays loop-only until the
     * song is heard and recorded again. */
    fun introIfCached(key: String): File? =
        introFile(key).takeIf { it.isFile && it.length() > 0 } ?: wavIntroFile(key).takeIf { it.isFile && it.length() > WAV_HEADER }

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
    fun roomForBackground(): Boolean = FfMusicBudget.hasRoom(root)

    /** Brings the clips under budget, keeping [keep]'s song (just recorded). */
    private fun trim(keep: String? = null) {
        val freed = runCatching { FfMusicBudget.trim(root, setOfNotNull(keep?.let { "$romCrc/$it" })) }.getOrDefault(0L)
        if (freed > 0) Log.i("pokedaisy", "FF music: over the cache's budget, freed ${freed shr 20} MB of songs played longest ago")
    }

    /**
     * On the renderer's thread before it records anything: converts this ROM's clips from before FLAC (about half the
     * space; half the time spent waiting, so a big cache doesn't heat the device), then brings every ROM's clips
     * under budget. Other ROMs' WAVs are converted when they're played next; a song the player is hearing ([waiting])
     * comes first, the rest wait for the next launch (a WAV still plays).
     */
    fun prepare(waiting: () -> Boolean) {
        val wavs = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }.orEmpty()
        var converted = 0
        for (wav in wavs) {
            if (waiting()) break
            val began = System.nanoTime()
            runCatching {
                val flac = File(dir, wav.name.removeSuffix(".wav") + ".flac")
                val pcm = readWav(wav)
                if (pcm == null) wav.delete()
                else {
                    if (pcm.second.isEmpty()) flac.writeBytes(ByteArray(0)) else FlacWriter.write(flac, pcm.second, pcm.second.size, pcm.first)
                    flac.setLastModified(wav.lastModified())   // keeps its place among the never-played (best effort)
                    wav.delete()
                    converted++
                }
            }.onFailure { Log.w("pokedaisy", "FF music: couldn't convert ${wav.name}", it) }
            val took = (System.nanoTime() - began) / 1_000_000L
            if (took > 0) Thread.sleep(took)
        }
        if (converted > 0) Log.i("pokedaisy", "FF music: converted $converted clips to FLAC")
        trim()
    }

    /** Deletes every cached clip for this ROM — a manual escape hatch if a
     * capture ever comes out wrong (e.g. captured mid-transition). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Bump whenever the cache format changes (e.g. [CAPTURE_SECONDS] or
         * the loop-detection logic) so stale clips from a previous version
         * are never mistaken for current ones — see the constructor's `dir`.
         * v6: loops cut where the sound engine loops (v5's audio guess cut
         * battle themes to a few seconds and looped them back to the intro). */
        const val CACHE_VERSION = 6

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

/** A WAV [writeWav] wrote: its sample rate and samples, or null if it isn't one. */
internal fun readWav(file: File): Pair<Int, ShortArray>? {
    val bytes = file.readBytes()
    if (bytes.size < 44) return null
    val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    fun ascii(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
    if (ascii(0) != "RIFF" || ascii(8) != "WAVE" || ascii(12) != "fmt " || ascii(36) != "data") return null
    if (b.getShort(20).toInt() != 1 || b.getShort(22).toInt() != 2 || b.getShort(34).toInt() != 16) return null
    val dataBytes = minOf(b.getInt(40), bytes.size - 44).coerceAtLeast(0) and 3.inv()
    val pcm = ShortArray(dataBytes / 2)
    b.position(44)
    b.asShortBuffer().get(pcm)
    return b.getInt(24) to pcm
}

/** Writes [len] samples of interleaved stereo [pcm] to [file] as a plain
 * 16-bit PCM WAV, via a temp file so a half-written one is never read as valid. */
internal fun writeWav(file: File, pcm: ShortArray, len: Int, sampleRate: Int, from: Int = 0) {
    val dataBytes = len * 2
    val tmp = File(file.parentFile, "${file.name}.tmp")
    RandomAccessFile(tmp, "rw").use { raf ->
        raf.setLength(0)
        raf.writeAsciiLE("RIFF")
        raf.writeIntLE(36 + dataBytes)
        raf.writeAsciiLE("WAVE")
        raf.writeAsciiLE("fmt ")
        raf.writeIntLE(16)          // fmt chunk size
        raf.writeShortLE(1)         // PCM
        raf.writeShortLE(2)         // stereo
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(sampleRate * 2 * 2)   // byte rate
        raf.writeShortLE(4)         // block align (channels * bytes/sample)
        raf.writeShortLE(16)        // bits per sample
        raf.writeAsciiLE("data")
        raf.writeIntLE(dataBytes)
        val bytes = ByteArray(dataBytes)
        for (i in 0 until len) {
            val s = pcm[from + i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
    }
    tmp.renameTo(file)   // atomic-ish swap
}

private fun RandomAccessFile.writeAsciiLE(s: String) = write(s.toByteArray(Charsets.US_ASCII))
private fun RandomAccessFile.writeIntLE(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
}
private fun RandomAccessFile.writeShortLE(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF)
}
