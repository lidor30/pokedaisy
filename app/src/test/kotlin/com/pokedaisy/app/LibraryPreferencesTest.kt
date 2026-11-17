package com.pokedaisy.app

import com.pokedaisy.app.companion.LibraryPreferences
import com.pokedaisy.app.companion.LibrarySort
import com.pokedaisy.app.companion.data.LibraryView
import org.junit.Assert.*
import org.junit.Test

class LibraryPreferencesTest {
    private var saved: String? = null
    private var time = 100L
    private fun open() = LibraryPreferences({ saved }, { saved = it }, { time })

    @Test fun choicesAndNamesSurviveReopening() {
        val prefs = open()
        prefs.setGrid(false); prefs.setSort(LibrarySort.DATE_ADDED); prefs.setView(LibraryView.FAVORITES)
        prefs.toggleFavorite("a,quoted\"é.gba")
        val reopened = open()
        assertFalse(reopened.grid); assertEquals(LibrarySort.DATE_ADDED, reopened.sort)
        assertEquals(LibraryView.FAVORITES, reopened.view)
        assertEquals(setOf("a,quoted\"é.gba"), reopened.favorites)
        reopened.toggleFavorite("a,quoted\"é.gba"); assertTrue(open().favorites.isEmpty())
    }

    @Test fun playHistoryAndAddedDatesKeepTheirOriginalIdentity() {
        val prefs = open()
        prefs.recordAdded(listOf("a.gba", "b.gbc")); time = 200
        prefs.recordAdded(listOf("a.gba", "c.gb"))
        prefs.recordPlay("a.gba"); time = 300; prefs.recordPlay("b.gbc")
        val reopened = open()
        assertEquals(100L, reopened.added["a.gba"]); assertEquals(200L, reopened.added["c.gb"])
        assertEquals(200L, reopened.played["a.gba"]); assertEquals(300L, reopened.played["b.gbc"])
        assertEquals("b.gbc", reopened.last)
    }

    @Test fun malformedOrUnknownFieldsFallBackSafely() {
        saved = "broken"; assertTrue(open().grid)
        saved = """{"grid":false,"sort":999,"view":999,"favorites":false,"played":{"bad":"x","ok":2}}"""
        val prefs = open()
        assertFalse(prefs.grid); assertEquals(LibrarySort.NAME, prefs.sort)
        assertEquals(LibraryView.ALL, prefs.view); assertTrue(prefs.favorites.isEmpty())
        assertEquals(mapOf("ok" to 2L), prefs.played)
    }

    @Test fun failedWritesDoNotClaimToHaveSavedAChange() {
        val prefs = LibraryPreferences({ saved }, { error("storage unavailable") })
        assertTrue(runCatching { prefs.toggleFavorite("a.gba") }.isFailure)
        assertTrue(prefs.favorites.isEmpty())
    }
}
