package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.LibraryView

enum class LibrarySection(val label: String) { GAMES("GAMES"), TOOLS("TOOLS"), SETTINGS("SETTINGS") }

/** Commands consumed by the library presenter, independent of native screens and files. */
class LibraryActions(
    val add: () -> Unit, val refresh: () -> Unit, val search: () -> Unit, val sort: () -> Unit,
    val toggleGrid: () -> Unit, val help: () -> Unit, val query: (String) -> Unit,
    val view: (LibraryView) -> Unit, val section: (LibrarySection) -> Unit,
)

/** Library navigation and preference actions. A native Settings launcher keeps its own screen lifecycle;
 * without one, Settings is presented inline. The preference provider is read only after host startup. */
class LibraryCoordinator(
    private val preferences: () -> LibraryPreferences,
    private val settings: ((String?) -> Unit)? = null,
    private val changed: (Change) -> Unit = {},
) {
    enum class Change { QUERY, VIEW, SORT, GRID }
    enum class Dialog { SEARCH, SORT }
    var section by mutableStateOf(LibrarySection.GAMES)
        private set
    var query by mutableStateOf("")
        private set
    var dialog by mutableStateOf<Dialog?>(null)
        private set
    var settingsPage by mutableStateOf<String?>(null)
        private set
    val grid get() = preferences().grid
    val sort get() = preferences().sort
    val view get() = preferences().view

    fun selectSection(value: LibrarySection) {
        if (value == LibrarySection.SETTINGS) openSettings(null) else section = value
    }
    fun help() = openSettings("HELP")
    private fun openSettings(page: String?) {
        settingsPage = page
        if (settings == null) section = LibrarySection.SETTINGS else settings.invoke(page)
    }
    fun search(value: String) { query = value; changed(Change.QUERY) }
    fun openSearch() { dialog = Dialog.SEARCH }
    fun openSort() { dialog = Dialog.SORT }
    fun dismiss(value: Dialog) { if (dialog == value) dialog = null }
    fun selectView(value: LibraryView) {
        section = LibrarySection.GAMES
        preferences().setView(value)
        changed(Change.VIEW)
    }
    fun selectSort(value: LibrarySort) {
        preferences().setSort(value)
        dismiss(Dialog.SORT)
        changed(Change.SORT)
    }
    fun toggleGrid() { preferences().setGrid(!grid); changed(Change.GRID) }

    /** Root navigation only; native and shared modal handlers get first refusal. */
    fun back(): Boolean = when {
        dialog != null -> { dialog = null; true }
        section == LibrarySection.TOOLS -> { section = LibrarySection.GAMES; true }
        section == LibrarySection.GAMES && query.isNotEmpty() -> { search(""); true }
        else -> false
    }

    fun actions(add: () -> Unit, refresh: () -> Unit) = LibraryActions(
        add = add, refresh = refresh, search = ::openSearch, sort = ::openSort,
        toggleGrid = ::toggleGrid, help = ::help, query = ::search,
        view = ::selectView, section = ::selectSection)
}
