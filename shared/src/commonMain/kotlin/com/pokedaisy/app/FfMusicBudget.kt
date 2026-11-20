package com.pokedaisy.app

import com.pokedaisy.app.companion.data.delete
import com.pokedaisy.app.companion.data.isDirectory
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.lastModified
import com.pokedaisy.app.companion.data.length
import com.pokedaisy.app.companion.data.listFiles
import com.pokedaisy.app.companion.data.readLines
import com.pokedaisy.app.companion.data.writeTextAtomically
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.use

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
object FfMusicBudget {
    /** 1.5 GB. */
    const val MAX_BYTES = 1536L shl 20

    /** Room a background song needs before it's recorded: more than one song takes. */
    const val BACKGROUND_HEADROOM = 32L shl 20

    private const val PLAYED = ".played"

    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lock = SynchronizedObject()

    /** The newest [notePlayed] song ("crc/key"): never trimmed (it's likely playing). */
    @Volatile private var lastPlayed: String? = null

    fun budget(clipBytes: Long, freeBytes: Long): Long = minOf(MAX_BYTES, (clipBytes + freeBytes) / 4)

    /** Notes that [rom]'s song [key] just played, off the calling thread (the emu thread calls it). */
    fun notePlayed(root: Path, rom: String, key: String, now: Long = Clock.System.now().toEpochMilliseconds()) {
        lastPlayed = "$rom/$key"
        writer.launch { append(root, rom, key, now) }
    }

    fun append(root: Path, rom: String, key: String, now: Long) {
        synchronized(lock) {
            runCatching {
                FileSystem.SYSTEM.appendingSink(root / PLAYED).buffer().use { it.writeUtf8("$rom $key $now\n") }
            }
        }
    }

    /** A cached song: both its files. [played] is null for one never played (prefetched only). */
    class Song(val rom: String, val key: String, val files: List<Path>, val bytes: Long, val written: Long, val played: Long?)

    /** Every song under [root]: one directory per ROM, a song's loop and intro files. */
    fun songs(root: Path): List<Song> = synchronized(lock) {
        val played = readPlayed(root)
        root.listFiles().filter { it.isDirectory }.flatMap { dir ->
            dir.listFiles().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") }
                .groupBy { songKey(it.name) }
                .filterKeys { it != null }
                .map { (key, files) ->
                    Song(dir.name, key!!, files, files.sumOf { it.length }, files.maxOf { it.lastModified }, played["${dir.name}/$key"])
                }
        }
    }

    /** Whether a background recording fits: the clips stay [BACKGROUND_HEADROOM] under the budget. */
    fun hasRoom(root: Path, freeBytes: Long, now: Long = Clock.System.now().toEpochMilliseconds()): Boolean {
        val used = used(root, now)
        return used + BACKGROUND_HEADROOM <= budget(used, freeBytes)
    }

    // The clips' total from the last scan: every write trims (a scan), so it's only stale for what other ROMs'
    // renderers wrote meanwhile - and the background pass asks before every song.
    private var usedRoot: Path? = null
    private var usedBytes = 0L
    private var usedAt = 0L

    private fun used(root: Path, now: Long): Long = synchronized(lock) {
        if (root != usedRoot || now - usedAt > USED_FRESH_MILLIS) remember(root, songs(root).sumOf { it.bytes }, now)
        usedBytes
    }

    private fun remember(root: Path, bytes: Long, now: Long) {
        usedRoot = root; usedBytes = bytes; usedAt = now
    }

    private const val USED_FRESH_MILLIS = 60_000L

    /**
     * Deletes songs until the clips fit the budget: never-played ones first (oldest written first), then played ones,
     * longest ago first. [keep] ("crc/key") and the newest played song stay whatever. Returns the bytes freed.
     */
    fun trim(root: Path, keep: Set<String> = emptySet(), freeBytes: Long): Long = synchronized(lock) {
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
                val reclaimed = s.files.sumOf { path ->
                    val bytes = path.length
                    if (path.delete()) bytes else 0L
                }
                if (s.files.none { it.isFile }) deleted += s
                used -= reclaimed
                freed += reclaimed
            }
        }
        remember(root, used, Clock.System.now().toEpochMilliseconds())
        compactPlayed(root, songs - deleted)
        freed
    }

    /** "crc/key" -> when it last played. */
    private fun readPlayed(root: Path): Map<String, Long> {
        val f = root / PLAYED
        if (!f.isFile) return emptyMap()
        val out = HashMap<String, Long>()
        runCatching {
            f.readLines().forEach { line ->
                val parts = line.split(' ')
                val at = parts.getOrNull(2)?.toLongOrNull() ?: return@forEach
                out["${parts[0]}/${parts[1]}"] = maxOf(at, out["${parts[0]}/${parts[1]}"] ?: 0L)
            }
        }
        return out
    }

    /** Rewrites the log with one line per song still cached, once it has grown well past that. */
    private fun compactPlayed(root: Path, songs: List<Song>) {
        val f = root / PLAYED
        if (!f.isFile) return
        val lines = runCatching { f.readLines() }.getOrNull() ?: return
        val cached = songs.mapTo(HashSet()) { "${it.rom}/${it.key}" }
        if (lines.size <= 2 * cached.size + 200) return
        val played = readPlayed(root).filterKeys { it in cached }
        runCatching {
            f.writeTextAtomically(played.entries.sortedBy { it.value }.joinToString("") { (k, at) -> "${k.replace('/', ' ')} $at\n" })
        }
    }

    /** The song a cache file belongs to: `<key>.flac` / `<key>.intro.flac` (or a WAV from before FLAC). */
    fun songKey(name: String): String? {
        val base = name.removeSuffix(".flac").removeSuffix(".wav").takeIf { it != name } ?: return null
        return base.removeSuffix(".intro")
    }
}
