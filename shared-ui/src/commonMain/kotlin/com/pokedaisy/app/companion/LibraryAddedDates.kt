package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.readText
import com.pokedaisy.app.companion.data.writeTextAtomically
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path
import kotlin.time.Clock

/** One DATE ADDED / NEW policy over the installed metadata codecs. Null read means no previous record. */
class LibraryAddedDates(
    private val read: () -> Map<String, Entry>?,
    private val write: (Map<String, Entry>) -> Unit,
    private val firstAsInitial: Boolean = true,
) {
    data class Entry(val millis: Long, val initial: Boolean)
    private val lock = SynchronizedObject()
    private var cache: Map<String, Entry>? = null
    private var present = false
    fun all(): Map<String, Entry> = synchronized(lock) {
        cache ?: read().let { value -> present = value != null; value.orEmpty().also { cache = it } }
    }
    fun record(ids: List<String>, initial: Boolean, now: Long = Clock.System.now().toEpochMilliseconds()) = synchronized(lock) {
        val known = all()
        val fresh = ids.filter { it !in known }
        if (fresh.isEmpty() && present) return@synchronized
        val updated = known + fresh.associateWith { Entry(now, initial || (!present && firstAsInitial)) }
        write(updated); cache = updated; present = true
    }
    fun forget(id: String) = synchronized(lock) {
        val known = all()
        if (id in known) { val updated = known - id; write(updated); cache = updated }
    }
    fun reload() = synchronized(lock) { cache = null }
    fun addedAt(id: String): Long? = all()[id]?.millis
    fun isNew(id: String, now: Long = Clock.System.now().toEpochMilliseconds()): Boolean =
        all()[id]?.let { !it.initial && now - it.millis in 0 until NEW_FOR_MILLIS } == true
    companion object { const val NEW_FOR_MILLIS = 24L * 60 * 60 * 1000 }
}

/** Existing Android rom-added.tsv format, now a portable codec. */
object LibraryAddedFiles {
    private val lock = SynchronizedObject()
    private val stores = mutableMapOf<Path, LibraryAddedDates>()
    fun at(directory: Path): LibraryAddedDates = synchronized(lock) {
        stores.getOrPut(directory) {
            val file = directory / "rom-added.tsv"
            LibraryAddedDates(read = {
                if (!file.isFile) null else runCatching {
                    file.readText().lineSequence().mapNotNull { line ->
                        val parts = line.split('\t')
                        val time = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                        parts[0] to LibraryAddedDates.Entry(time, parts.getOrNull(2) == "1")
                    }.toMap()
                }.getOrDefault(emptyMap())
            }, write = { entries ->
                // Android's installed date store is best effort; keep its in-memory behavior on a failed write.
                runCatching { file.writeTextAtomically(entries.entries.joinToString("") { (id, entry) ->
                    "$id\t${entry.millis}\t${if (entry.initial) 1 else 0}\n"
                }) }
                Unit
            })
        }
    }
    fun reload() = synchronized(lock) { stores.values.forEach { it.reload() }; stores.clear() }
}
