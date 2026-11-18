package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.LibraryView
import kotlin.time.Clock

/** Stable sort IDs used by the library hosts. */
enum class LibrarySort(val id: Int, val label: String) {
    NAME(0, "NAME"), LAST_PLAYED(1, "LAST PLAYED"), DATE_ADDED(2, "DATE ADDED");
    companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } ?: NAME }
}

/** Library choices, favorites, history, sorting and filtering for both hosts. Storage codecs preserve installed formats. */
class LibraryPreferences private constructor(private val storage: Storage, private val now: () -> Long) {
    private data class Storage(val prefs: com.pokedaisy.app.Prefs, val added: LibraryAddedDates)
    constructor(prefs: com.pokedaisy.app.Prefs, added: LibraryAddedDates,
                now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : this(Storage(prefs, added), now)
    constructor(read: () -> String?, write: (String) -> Unit,
                now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : this(legacy(read, write), now)
    private val prefs get() = storage.prefs
    private data class Record(val grid: Boolean, val sort: LibrarySort, val view: LibraryView,
        val favorites: Set<String>, val played: Map<String, Long>, val added: Map<String, Long>, val last: String?)
    private fun read() = Record(prefs.libraryViewMode == 1, LibrarySort.of(prefs.librarySort), LibraryView.of(prefs.libraryView),
        prefs.favoriteRoms, prefs.romPlayTimes(), storage.added.all().mapValues { it.value.millis }, prefs.lastRomPath)
    private var record by mutableStateOf(read())
    val grid get() = record.grid
    val sort get() = record.sort
    val view get() = record.view
    val favorites get() = record.favorites
    val played get() = record.played
    val added get() = record.added
    val last get() = record.last
    fun reload() { storage.added.reload(); record = read() }
    fun setGrid(on: Boolean) { prefs.libraryViewMode = if (on) 1 else 0; record = read() }
    fun setSort(sort: LibrarySort) { prefs.librarySort = sort.id; record = read() }
    fun setView(view: LibraryView) { prefs.libraryView = view.id; record = read() }
    fun setFavorite(id: String, on: Boolean) {
        prefs.favoriteRoms = if (on) favorites + id else favorites - id
        record = read()
    }
    fun toggleFavorite(id: String) = setFavorite(id, id !in favorites)
    fun recordPlay(id: String) { prefs.recordRomPlayed(id, now()); record = read() }
    fun recordAdded(ids: List<String>, initial: Boolean = false) { storage.added.record(ids, initial, now()); record = read() }
    fun forget(id: String) {
        storage.added.forget(id); prefs.favoriteRoms = favorites - id
        if (last == id) prefs.lastRomPath = null
        record = read()
    }
    fun isNew(id: String) = storage.added.isNew(id, now())

    fun <T> ordered(roms: List<T>, order: LibrarySort = sort, id: (T) -> String): List<T> {
        val recent = prefs.recentRomPaths()
        val sorted = when (order) {
            LibrarySort.NAME -> roms
            LibrarySort.LAST_PLAYED -> roms.sortedWith(compareByDescending<T> { played[id(it)] ?: 0L }
                .thenBy { recent.indexOf(id(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } })
            LibrarySort.DATE_ADDED -> roms.sortedByDescending { added[id(it)] ?: 0L }
        }
        val (top, rest) = sorted.partition { id(it) in favorites }
        return top + rest
    }
    fun <T> filtered(roms: List<T>, view: LibraryView, id: (T) -> String,
                     platform: (T) -> com.pokedaisy.app.companion.data.RomCategory?,
                     category: (T) -> com.pokedaisy.app.companion.data.RomCategory? = platform): List<T> = when (view) {
        LibraryView.ALL -> roms
        LibraryView.FAVORITES -> roms.filter { id(it) in favorites }
        LibraryView.RECENT -> {
            val recent = prefs.recentRomPaths()
            roms.filter { id(it) in played || id(it) in recent }.sortedWith(compareByDescending<T> { played[id(it)] ?: 0L }
                .thenBy { recent.indexOf(id(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } })
        }
        LibraryView.HACKS -> roms.filter { category(it) == com.pokedaisy.app.companion.data.RomCategory.HACK }
        else -> roms.filter { platform(it) == view.platform }
    }
    private companion object {
        fun legacy(read: () -> String?, write: (String) -> Unit): Storage {
            val backend = LegacyLibraryStore(read, write)
            return Storage(com.pokedaisy.app.Prefs(backend, com.pokedaisy.app.Prefs.Defaults(libraryListsJson = true)), backend.added)
        }
    }
}

fun matchesLibrarySearch(query: String, names: List<String>): Boolean {
    fun searchable(value: String) = com.pokedaisy.app.companion.i18n.stripAccents(value).lowercase()
    val all = names.map(::searchable)
    return searchable(query).split(' ').filter { it.isNotEmpty() }.all { word -> all.any { word in it } }
}
