package com.pokedaisy.app.companion.data

/** What kind of game a ROM is, for the library's tag when it has no cover art: its platform, or a hack. */
enum class RomCategory(val label: String) { GB("GB"), GBC("GBC"), GBA("GBA"), NDS("NDS"), HACK("HACK") }

/**
 * The library's quick views (the pills over the games, [com.pokedaisy.app.Prefs.libraryView]). [id] is what Prefs keeps (stable, never
 * renamed); [platform]: the console a platform view shows ([com.pokedaisy.app.GameTitles.platform], so a hack shows under its base game's
 * too); [category]: the kind a view shows ([com.pokedaisy.app.GameTitles.category]: HACKS).
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
