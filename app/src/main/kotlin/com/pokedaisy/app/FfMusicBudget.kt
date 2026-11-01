package com.pokedaisy.app

import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps every ROM's STEADY clips ([FfMusicCache]) under one budget: at most [MAX_BYTES], and at most a quarter of what
 * the clips and the device's free space make together (a nearly full device keeps fewer). Past it, the songs played
 * longest ago go first; songs only prefetched in the background, never played, go before any that were. A song that's
 * gone is simply recorded again when the game plays it next.
 *
 * "Played" = the game started the song while FF MUSIC was STEADY ([notePlayed]), kept in a log at the cache's root
 * (`.played`: `<rom crc> <song key> <millis>` per line, newest last; file times aren't reliable on Android's external
 * storage). The background pass only fills free room ([hasRoom]) and never pushes anything out.
 */
internal object FfMusicBudget {
    /** 1.5 GB. */
    const val MAX_BYTES = 1536L shl 20

    /** Room a background song needs before it's recorded: more than one song takes. */
    const val BACKGROUND_HEADROOM = 32L shl 20

    private const val PLAYED = ".played"

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pokedaisy-ffmusic-budget").apply { isDaemon = true } }

    /** The newest [notePlayed] song ("crc/key"): never trimmed (it's likely playing). */
    @Volatile private var lastPlayed: String? = null

    fun budget(clipBytes: Long, freeBytes: Long): Long = minOf(MAX_BYTES, (clipBytes + freeBytes) / 4)

    /** Notes that [rom]'s song [key] just played, off the calling thread (the emu thread calls it). */
    fun notePlayed(root: File, rom: String, key: String, now: Long = System.currentTimeMillis()) {
        lastPlayed = "$rom/$key"
        writer.execute { append(root, rom, key, now) }
    }

    @Synchronized
    internal fun append(root: File, rom: String, key: String, now: Long) {
        runCatching { File(root, PLAYED).appendText("$rom $key $now\n") }
    }

    /** A cached song: both its files. [played] is null for one never played (prefetched only). */
    class Song(val rom: String, val key: String, val files: List<File>, val bytes: Long, val written: Long, val played: Long?)

    /** Every song under [root]: one directory per ROM, a song's loop and intro files. */
    @Synchronized
    fun songs(root: File): List<Song> {
        val played = readPlayed(root)
        return root.listFiles()?.filter { it.isDirectory }.orEmpty().flatMap { dir ->
            dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") }.orEmpty()
                .groupBy { songKey(it.name) }
                .filterKeys { it != null }
                .map { (key, files) ->
                    Song(dir.name, key!!, files, files.sumOf { it.length() }, files.maxOf { it.lastModified() }, played["${dir.name}/$key"])
                }
        }
    }

    /** Whether a background recording fits: the clips stay [BACKGROUND_HEADROOM] under the budget. */
    fun hasRoom(root: File, freeBytes: Long = root.usableSpace, now: Long = System.currentTimeMillis()): Boolean {
        val used = used(root, now)
        return used + BACKGROUND_HEADROOM <= budget(used, freeBytes)
    }

    // The clips' total from the last scan: every write trims (a scan), so it's only stale for what other ROMs'
    // renderers wrote meanwhile - and the background pass asks before every song.
    private var usedRoot: File? = null
    private var usedBytes = 0L
    private var usedAt = 0L

    @Synchronized
    private fun used(root: File, now: Long): Long {
        if (root != usedRoot || now - usedAt > USED_FRESH_MILLIS) remember(root, songs(root).sumOf { it.bytes }, now)
        return usedBytes
    }

    private fun remember(root: File, bytes: Long, now: Long) {
        usedRoot = root; usedBytes = bytes; usedAt = now
    }

    private const val USED_FRESH_MILLIS = 60_000L

    /**
     * Deletes songs until the clips fit the budget: never-played ones first (oldest written first), then played ones,
     * longest ago first. [keep] ("crc/key") and the newest played song stay whatever. Returns the bytes freed.
     */
    @Synchronized
    fun trim(root: File, keep: Set<String> = emptySet(), freeBytes: Long = root.usableSpace): Long {
        val songs = songs(root)
        var used = songs.sumOf { it.bytes }
        val limit = budget(used, freeBytes)
        var freed = 0L
        val deleted = HashSet<Song>()
        if (used > limit) {
            val spared = keep + listOfNotNull(lastPlayed)
            val order = songs.filter { "${it.rom}/${it.key}" !in spared }
                .sortedWith(compareBy<Song>({ it.played != null }, { it.played ?: it.written }))
            for (s in order) {
                if (used <= limit) break
                s.files.forEach { it.delete() }
                deleted += s
                used -= s.bytes
                freed += s.bytes
            }
        }
        remember(root, used, System.currentTimeMillis())
        compactPlayed(root, songs - deleted)
        return freed
    }

    /** "crc/key" -> when it last played. */
    private fun readPlayed(root: File): Map<String, Long> {
        val f = File(root, PLAYED)
        if (!f.isFile) return emptyMap()
        val out = HashMap<String, Long>()
        runCatching {
            f.forEachLine { line ->
                val parts = line.split(' ')
                val at = parts.getOrNull(2)?.toLongOrNull() ?: return@forEachLine
                out["${parts[0]}/${parts[1]}"] = maxOf(at, out["${parts[0]}/${parts[1]}"] ?: 0L)
            }
        }
        return out
    }

    /** Rewrites the log with one line per song still cached, once it has grown well past that. */
    private fun compactPlayed(root: File, songs: List<Song>) {
        val f = File(root, PLAYED)
        if (!f.isFile) return
        val lines = runCatching { f.readLines() }.getOrNull() ?: return
        val cached = songs.mapTo(HashSet()) { "${it.rom}/${it.key}" }
        if (lines.size <= 2 * cached.size + 200) return
        val played = readPlayed(root).filterKeys { it in cached }
        val tmp = File(root, "$PLAYED.tmp")
        runCatching {
            tmp.writeText(played.entries.sortedBy { it.value }.joinToString("") { (k, at) -> "${k.replace('/', ' ')} $at\n" })
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    /** The song a cache file belongs to: `<key>.flac` / `<key>.intro.flac` (or a WAV from before FLAC). */
    fun songKey(name: String): String? {
        val base = name.removeSuffix(".flac").removeSuffix(".wav").takeIf { it != name } ?: return null
        return base.removeSuffix(".intro")
    }
}
