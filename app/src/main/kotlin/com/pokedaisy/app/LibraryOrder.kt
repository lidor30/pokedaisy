package com.pokedaisy.app

import android.content.Context
import java.io.File

/**
 * When the library first saw each ROM - its DATE ADDED order and the NEW tag (added in the last
 * [NEW_FOR_MILLIS], by the player: not what first-time setup or a newly linked folder brought in,
 * nor a library that was already there when this was first kept). In
 * `filesDir/rom-added.tsv`: `<path>\t<millis>\t<1 = came in with setup>`.
 */
object RomAdded {
    const val NEW_FOR_MILLIS = 24L * 60 * 60 * 1000

    class Entry(val millis: Long, val initial: Boolean)

    @Volatile private var cache: Map<String, Entry>? = null
    /** Where the file lives: the app's files dir unless a test sets it. */
    @Volatile internal var dirOverride: File? = null

    private fun file(context: Context?) = File(dirOverride ?: context!!.applicationContext.filesDir, "rom-added.tsv")

    @Synchronized
    fun all(context: Context?): Map<String, Entry> = cache ?: run {
        val f = file(context)
        val read = if (!f.isFile) emptyMap() else runCatching {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                val millis = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                p[0] to Entry(millis, p.getOrNull(2) == "1")
            }.toMap()
        }.getOrDefault(emptyMap())
        read.also { cache = it }
    }

    /**
     * Notes [roms] the store hasn't seen, as added now. [initial]: they came with first-time setup
     * or a folder just linked - dated, never NEW. Before the store's first write every ROM is one
     * that was already there, so those are initial too.
     */
    @Synchronized
    fun record(context: Context?, roms: List<File>, initial: Boolean, now: Long = System.currentTimeMillis()) {
        val known = all(context)
        val first = !file(context).isFile
        val fresh = roms.map { it.absolutePath }.filter { it !in known }
        if (fresh.isEmpty() && !first) return
        val updated = known + fresh.associateWith { Entry(now, initial || first) }
        write(context, updated)
    }

    /** A deleted ROM: added again later, it's new again. */
    @Synchronized
    fun forget(context: Context?, rom: File) {
        val known = all(context)
        if (rom.absolutePath in known) write(context, known - rom.absolutePath)
    }

    /** Read the file again (ui-preview writes one per shot). */
    internal fun reload() { cache = null }

    fun addedAt(context: Context?, rom: File): Long? = all(context)[rom.absolutePath]?.millis

    /** Added by the player within the last day. */
    fun isNew(context: Context?, rom: File, now: Long = System.currentTimeMillis()): Boolean =
        all(context)[rom.absolutePath]?.let { !it.initial && now - it.millis in 0 until NEW_FOR_MILLIS } == true

    private fun write(context: Context?, entries: Map<String, Entry>) {
        cache = entries
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(entries.entries.joinToString("") { (path, e) -> "$path\t${e.millis}\t${if (e.initial) 1 else 0}\n" })
            tmp.renameTo(f)
        }
    }
}

/** The library's order ([Prefs.librarySort]). [id] is what Prefs keeps (stable, never renamed). */
enum class LibrarySort(val id: Int, val label: String) {
    NAME(0, "NAME"),
    LAST_PLAYED(1, "LAST PLAYED"),
    DATE_ADDED(2, "DATE ADDED");

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: NAME
    }
}

/** The library's SEARCH: every word of [query] is in one of [names], whatever the case or accents ("poke red" finds
 * "Pokémon FireRed"). */
fun matchesSearch(query: String, names: List<String>): Boolean {
    val all = names.map(::searchable)
    return searchable(query).split(' ').filter { it.isNotEmpty() }.all { word -> all.any { word in it } }
}

private fun searchable(s: String) =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/**
 * [roms] (alphabetical, as [RomFolder.libraryRoms] lists them) in [sort]'s order, the player's
 * favorites ([Prefs.favoriteRoms]) first - each part in that order. LAST PLAYED: by
 * the time each was last started, plays from before those were kept by their place in the recent
 * list, never-played ones after, by name. DATE ADDED: newest first, by name among equals.
 */
fun sortLibrary(context: Context, prefs: Prefs, roms: List<File>, sort: LibrarySort): List<File> {
    val favorites = prefs.favoriteRoms
    val (top, rest) = sorted(context, prefs, roms, sort).partition { it.absolutePath in favorites }
    return top + rest
}

private fun sorted(context: Context, prefs: Prefs, roms: List<File>, sort: LibrarySort): List<File> = when (sort) {
    LibrarySort.NAME -> roms
    LibrarySort.LAST_PLAYED -> {
        val recent = prefs.recentRomPaths()
        // sortedBy is stable: equal keys keep the alphabetical order.
        roms.sortedWith(
            compareByDescending<File> { prefs.romLastPlayed(it.absolutePath) }
                .thenBy { recent.indexOf(it.absolutePath).let { i -> if (i < 0) Int.MAX_VALUE else i } },
        )
    }
    LibrarySort.DATE_ADDED -> roms.sortedByDescending { RomAdded.addedAt(context, it) ?: 0L }
}

/**
 * The library's quick views (the pills over the games, [Prefs.libraryView]). [id] is what Prefs keeps (stable, never
 * renamed); [platform]: the console a platform view shows ([GameTitles.platform], so a hack shows under its base game's
 * too); [category]: the kind a view shows ([GameTitles.category]: HACKS).
 */
enum class LibraryView(val id: Int, val label: String, val platform: RomCategory? = null, val category: RomCategory? = null) {
    ALL(0, "ALL"),
    FAVORITES(1, "FAVORITES"),
    RECENT(2, "RECENT"),
    GB(3, "GB", platform = RomCategory.GB),
    GBC(4, "GBC", platform = RomCategory.GBC),
    GBA(5, "GBA", platform = RomCategory.GBA),
    DS(6, "DS", platform = RomCategory.NDS),
    HACKS(7, "HACKS", category = RomCategory.HACK);

    /** A view of some games by what they are (a console, the hacks): shown only while the library has one. */
    val byKind get() = platform != null || category != null

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/**
 * [sorted] (the library in its order, [sortLibrary]) narrowed to [view]. RECENT is every game ever started,
 * the last played first whatever the order (favorites not first: it's a history). [platform] / [category]: a game's
 * console and kind, null until the background title pass has looked at it (then it's in no such view yet).
 */
fun libraryViewOf(
    context: Context, prefs: Prefs, sorted: List<File>, view: LibraryView,
    platform: (File) -> RomCategory?, category: (File) -> RomCategory?,
): List<File> = when (view) {
    LibraryView.ALL -> sorted
    LibraryView.FAVORITES -> prefs.favoriteRoms.let { fav -> sorted.filter { it.absolutePath in fav } }
    LibraryView.RECENT -> {
        val recent = prefs.recentRomPaths()
        sorted(context, prefs, sorted, LibrarySort.LAST_PLAYED)
            .filter { prefs.romLastPlayed(it.absolutePath) > 0 || it.absolutePath in recent }
    }
    LibraryView.HACKS -> sorted.filter { category(it) == RomCategory.HACK }
    else -> sorted.filter { platform(it) == view.platform }
}
