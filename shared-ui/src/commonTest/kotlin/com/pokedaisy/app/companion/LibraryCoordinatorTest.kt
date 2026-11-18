package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.LibraryView
import com.pokedaisy.app.companion.ui.LibrarySection
import kotlin.test.*

class LibraryCoordinatorTest {
    private var saved: String? = null
    private val prefs = LibraryPreferences({ saved }, { saved = it })

    @Test fun inlineAndNativeSettingsKeepTheirExistingNavigation() {
        val inline = LibraryCoordinator({ prefs })
        inline.selectSection(LibrarySection.TOOLS); inline.help()
        assertEquals(LibrarySection.SETTINGS, inline.section)
        assertEquals("HELP", inline.settingsPage)
        inline.selectSection(LibrarySection.GAMES)
        inline.selectSection(LibrarySection.SETTINGS)
        assertNull(inline.settingsPage)

        val launched = mutableListOf<String?>()
        val native = LibraryCoordinator({ prefs }, settings = { launched += it })
        native.selectSection(LibrarySection.TOOLS); native.help()
        assertEquals(LibrarySection.TOOLS, native.section)
        native.selectSection(LibrarySection.SETTINGS)
        assertEquals(listOf("HELP", null), launched)
    }

    @Test fun backClosesDialogsThenToolsThenSearchWithoutLosingTheQueryEarly() {
        val library = LibraryCoordinator({ prefs })
        library.search("example"); library.selectSection(LibrarySection.TOOLS)
        library.openSearch(); library.openSort()
        library.dismiss(LibraryCoordinator.Dialog.SEARCH)
        assertEquals(LibraryCoordinator.Dialog.SORT, library.dialog)
        assertTrue(library.back()); assertNull(library.dialog)
        assertEquals(LibrarySection.TOOLS, library.section)
        assertTrue(library.back()); assertEquals("example", library.query)
        assertTrue(library.back()); assertEquals("", library.query)
        assertFalse(library.back())
    }

    @Test fun preferenceChangesAreSavedBeforeTheNativeRefreshAndSurviveANewHost() {
        prefs.setGrid(false)
        lateinit var library: LibraryCoordinator
        val changes = mutableListOf<LibraryCoordinator.Change>()
        library = LibraryCoordinator({ prefs }, changed = {
            changes += it
            when (it) {
                LibraryCoordinator.Change.SORT -> assertEquals(LibrarySort.DATE_ADDED, prefs.sort)
                LibraryCoordinator.Change.VIEW -> {
                    assertEquals(LibraryView.RECENT, prefs.view)
                    assertEquals(LibrarySection.GAMES, library.section)
                }
                LibraryCoordinator.Change.GRID -> assertTrue(prefs.grid)
                else -> Unit
            }
        })
        library.openSort(); library.selectSort(LibrarySort.DATE_ADDED)
        assertNull(library.dialog)
        library.selectSection(LibrarySection.TOOLS); library.selectView(LibraryView.RECENT)
        library.toggleGrid(); library.search("temporary")
        assertEquals(LibraryCoordinator.Change.entries.toSet(), changes.toSet())
        val reopened = LibraryCoordinator({ LibraryPreferences({ saved }, { saved = it }) })
        assertEquals(LibrarySort.DATE_ADDED, reopened.sort)
        assertEquals(LibraryView.RECENT, reopened.view); assertTrue(reopened.grid)
        assertEquals("", reopened.query); assertNull(reopened.dialog)
    }

    @Test fun navigationBeforeNativeStartupDoesNotAccessPreferences() {
        val library = LibraryCoordinator({ error("host not attached") })
        library.selectSection(LibrarySection.TOOLS); library.openSearch()
        assertTrue(library.back()); assertTrue(library.back())
        assertEquals(LibrarySection.GAMES, library.section)
    }

    @Test fun sharedActionsKeepNativeAddAndRefreshAndOwnTheRemainingTransitions() {
        prefs.setGrid(false)
        var added = 0; var refreshed = 0
        val library = LibraryCoordinator({ prefs })
        val actions = library.actions({ added++ }, { refreshed++ })
        actions.add(); actions.refresh(); actions.query("query")
        actions.search(); assertEquals(LibraryCoordinator.Dialog.SEARCH, library.dialog)
        actions.sort(); assertEquals(LibraryCoordinator.Dialog.SORT, library.dialog)
        actions.toggleGrid(); assertTrue(library.grid)
        actions.section(LibrarySection.TOOLS); actions.view(LibraryView.ALL)
        assertEquals(LibrarySection.GAMES, library.section)
        actions.help(); assertEquals("HELP", library.settingsPage)
        assertEquals(1, added); assertEquals(1, refreshed)
    }
}
