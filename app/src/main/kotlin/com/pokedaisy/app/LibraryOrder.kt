package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.LibraryAddedDates
import com.pokedaisy.app.companion.LibraryAddedFiles
import com.pokedaisy.app.companion.LibraryPreferences
import com.pokedaisy.app.companion.data.LibraryView
import com.pokedaisy.app.companion.data.RomCategory
import java.io.File
import okio.Path.Companion.toOkioPath

/** Android's installed metadata location; date and NEW behavior live in common code. */
object RomAdded {
    const val NEW_FOR_MILLIS = LibraryAddedDates.NEW_FOR_MILLIS
    internal var dirOverride: File? = null
    internal fun forContext(context: Context?): LibraryAddedDates =
        LibraryAddedFiles.at((dirOverride ?: context!!.applicationContext.filesDir).toOkioPath())
    fun all(context: Context?) = forContext(context).all()
    fun record(context: Context?, roms: List<File>, initial: Boolean, now: Long = System.currentTimeMillis()) {
        runCatching { forContext(context).record(roms.map { it.absolutePath }, initial, now) }
    }
    fun forget(context: Context?, rom: File) { runCatching { forContext(context).forget(rom.absolutePath) } }
    internal fun reload() = LibraryAddedFiles.reload()
    fun addedAt(context: Context?, rom: File) = forContext(context).addedAt(rom.absolutePath)
    fun isNew(context: Context?, rom: File, now: Long = System.currentTimeMillis()) = forContext(context).isNew(rom.absolutePath, now)
}

typealias LibrarySort = com.pokedaisy.app.companion.LibrarySort
fun matchesSearch(query: String, names: List<String>) = com.pokedaisy.app.companion.matchesLibrarySearch(query, names)
fun sortLibrary(context: Context, prefs: Prefs, roms: List<File>, sort: LibrarySort): List<File> =
    LibraryPreferences(prefs, RomAdded.forContext(context)).ordered(roms, sort) { it.absolutePath }
fun libraryViewOf(context: Context, prefs: Prefs, sorted: List<File>, view: LibraryView,
                  platform: (File) -> RomCategory?, category: (File) -> RomCategory?): List<File> =
    LibraryPreferences(prefs, RomAdded.forContext(context)).filtered(sorted, view, { it.absolutePath }, platform, category)
