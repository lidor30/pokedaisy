package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.LibraryView
import com.pokedaisy.app.companion.data.RomCategory

/** One ordered library split into quick views, used by both library screens and Android's launcher.
 * Hosts supply visible games and cached ROM metadata. [available] controls which console/kind pills
 * exist; it can retain games outside a search, as the iOS library does.
 */
class LibraryProjection<T>(
    preferences: LibraryPreferences,
    val library: List<T>,
    id: (T) -> String,
    platform: (T) -> RomCategory?,
    category: (T) -> RomCategory? = platform,
    available: List<T> = library,
) {
    private val platforms = (library + available).distinct().associateWith(platform)
    private val categories = (library + available).distinct().associateWith(category)
    private val byView = LibraryView.entries.associateWith {
        preferences.filtered(library, it, id, platforms::get, categories::get)
    }
    val shown = LibraryView.entries.filter { view ->
        !view.byKind || available.any {
            if (view == LibraryView.HACKS) categories[it] == RomCategory.HACK else platforms[it] == view.platform
        }
    }
    val counts = byView.mapValues { it.value.size }
    fun open(want: LibraryView) = want.takeIf { it in shown } ?: LibraryView.ALL
    fun games(want: LibraryView) = byView[open(want)].orEmpty()
}
