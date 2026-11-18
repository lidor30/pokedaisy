package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.LibraryView
import com.pokedaisy.app.companion.data.RomCategory
import kotlin.test.*
import kotlinx.serialization.json.*

class LibraryModelTest {
    @Test fun prefsAndLegacyJsonHostsShareFavoritesHistorySortingAndViews() {
        val flat = PrefsCompanionSettingsTest.Store()
        var entries: Map<String, LibraryAddedDates.Entry>? = null
        var json: String? = null
        var time = 100L
        val android = LibraryPreferences(Prefs(flat), LibraryAddedDates({ entries }, { entries = it }), { time })
        val ios = LibraryPreferences({ json }, { json = it }, { time })
        for (model in listOf(android, ios)) {
            model.setGrid(false); model.setSort(LibrarySort.LAST_PLAYED); model.setView(LibraryView.RECENT)
            model.recordAdded(listOf("a.gba", "b.gb"))
            model.recordPlay("a.gba"); time++
            model.recordPlay("b.gb"); model.setFavorite("a.gba", true)
            val games = listOf("a.gba", "b.gb", "c.gbc")
            assertEquals(listOf("a.gba", "b.gb", "c.gbc"), model.ordered(games) { it })
            assertEquals(listOf("b.gb", "a.gba"), model.filtered(games, LibraryView.RECENT, { it }, { RomCategory.GBA }))
            assertEquals(listOf("a.gba"), model.filtered(games, LibraryView.FAVORITES, { it }, { RomCategory.GBA }))
            assertFalse(model.grid); assertEquals(LibrarySort.LAST_PLAYED, model.sort)
        }
        val reopened = LibraryPreferences({ json }, { json = it })
        assertEquals(setOf("a.gba"), reopened.favorites); assertEquals("b.gb", reopened.last)
        assertEquals(setOf("a.gba", "b.gb"), reopened.played.keys)
        assertEquals(0, flat.data["library_view_mode"]); assertEquals("a.gba", flat.data["favorite_roms"])
    }

    @Test fun addedDatePolicyPreservesInitialLibrariesAndDoesNotRedateExistingGames() {
        var saved: Map<String, LibraryAddedDates.Entry>? = null
        val dates = LibraryAddedDates({ saved }, { saved = it })
        dates.record(listOf("a"), initial = false, now = 100)
        assertFalse(dates.isNew("a", 101))
        dates.record(listOf("a", "b"), initial = false, now = 200)
        assertEquals(100L, dates.addedAt("a")); assertTrue(dates.isNew("b", 201))
        assertFalse(dates.isNew("b", 200 + LibraryAddedDates.NEW_FOR_MILLIS))
        dates.forget("b"); dates.record(listOf("b"), initial = false, now = 300)
        assertEquals(300L, dates.addedAt("b"))
    }

    @Test fun legacyJsonKeepsArbitraryNamesUnknownFieldsAndEpochZeroHistory() {
        val id = "a,quoted\"é\n.gba"
        var raw: String? = """{"grid":true,"played":{"old.gb":0},"future":{"keep":true}}"""
        val model = LibraryPreferences({ raw }, { raw = it }, { 10L })
        model.toggleFavorite(id); model.recordPlay(id); model.recordAdded(listOf(id))
        model.recordAdded(listOf("initial.gb"), initial = true)
        val reopened = LibraryPreferences({ raw }, { raw = it }, { 11L })
        assertEquals(setOf(id), reopened.favorites); assertEquals(0L, reopened.played["old.gb"])
        assertEquals(10L, reopened.added[id]); assertEquals(id, reopened.last)
        assertTrue(reopened.isNew(id)); assertFalse(reopened.isNew("initial.gb"))
        val parsed = Json.parseToJsonElement(raw!!).jsonObject
        assertTrue(parsed.getValue("future").jsonObject.getValue("keep").jsonPrimitive.boolean)
        assertEquals(id, parsed.getValue("favorites").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun failedLegacyWriteLeavesObservableChoicesUnchangedAndSearchFoldsAccents() {
        val model = LibraryPreferences({ null }, { error("storage unavailable") })
        assertFails { model.setGrid(false) }; assertTrue(model.grid)
        assertTrue(matchesLibrarySearch("pokemon red", listOf("Poke\u0301mon FireRed")))
        assertFalse(matchesLibrarySearch("red green", listOf("FireRed")))
    }
}
