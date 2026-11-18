package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.LibraryView
import com.pokedaisy.app.companion.data.RomCategory
import kotlin.test.*

class LibraryProjectionTest {
    private data class Game(val name: String, val platform: RomCategory, val kind: RomCategory = platform)
    private var saved: String? = null
    private var now = 100L
    private val prefs = LibraryPreferences({ saved }, { saved = it }, { now })
    private val gb = Game("old.gb", RomCategory.GB)
    private val gba = Game("retail.gba", RomCategory.GBA)
    private val hack = Game("custom.gba", RomCategory.GBA, RomCategory.HACK)
    private fun project(games: List<Game>, available: List<Game> = games) =
        LibraryProjection(prefs, games, { it.name }, { it.platform }, { it.kind }, available)

    @Test fun platformAndHackViewsOverlapAndUnavailableSelectionsFallBackToAll() {
        val projection = project(listOf(gb, hack, gba))
        assertEquals(listOf(LibraryView.ALL, LibraryView.FAVORITES, LibraryView.RECENT,
            LibraryView.GB, LibraryView.GBA, LibraryView.HACKS), projection.shown)
        assertEquals(listOf(hack, gba), projection.games(LibraryView.GBA))
        assertEquals(listOf(hack), projection.games(LibraryView.HACKS))
        assertEquals(2, projection.counts[LibraryView.GBA])
        assertEquals(1, projection.counts[LibraryView.HACKS])
        assertEquals(LibraryView.ALL, projection.open(LibraryView.DS))
        assertEquals(projection.library, projection.games(LibraryView.DS))
    }

    @Test fun favoritesAndRecentUseStoredIdsAndKeepTheirExistingOrder() {
        prefs.recordPlay(gba.name); now++; prefs.recordPlay(gb.name)
        prefs.setFavorite(gba.name, true)
        val projection = project(prefs.ordered(listOf(gb, hack, gba)) { it.name })
        assertEquals(listOf(gba, gb, hack), projection.library)
        assertEquals(listOf(gba), projection.games(LibraryView.FAVORITES))
        assertEquals(listOf(gb, gba), projection.games(LibraryView.RECENT))
        assertEquals(1, projection.counts[LibraryView.FAVORITES])
        assertEquals(2, projection.counts[LibraryView.RECENT])
    }

    @Test fun searchCanRetainConsolePillsWhileCountsOnlyIncludeMatches() {
        val all = listOf(gb, hack, gba)
        val matches = all.filter { matchesLibrarySearch("custom", listOf(it.name)) }
        val ios = project(matches, available = all)
        assertTrue(LibraryView.GB in ios.shown)
        assertEquals(0, ios.counts[LibraryView.GB])
        assertTrue(ios.games(LibraryView.GB).isEmpty())
        val android = project(matches)
        assertFalse(LibraryView.GB in android.shown)
        assertEquals(matches, android.games(LibraryView.GB))
        // A hidden/unsupported game excluded by a host cannot leak into any displayed result.
        for (view in android.shown) assertFalse(gb in android.games(view))
    }

    @Test fun emptyLibrariesKeepTheThreeGeneralViewsAndReadMetadataOnce() {
        val empty = project(emptyList())
        assertEquals(listOf(LibraryView.ALL, LibraryView.FAVORITES, LibraryView.RECENT), empty.shown)
        assertTrue(empty.counts.values.all { it == 0 })
        var platforms = 0; var kinds = 0
        LibraryProjection(prefs, listOf(gba), { it.name }, { platforms++; it.platform },
            { kinds++; it.kind }, available = listOf(gba, gb))
        assertEquals(2, platforms); assertEquals(2, kinds)
    }
}
