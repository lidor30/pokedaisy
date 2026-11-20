package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import com.pokedaisy.app.companion.LibraryCoordinator
import com.pokedaisy.app.companion.LibrarySort
import com.pokedaisy.app.companion.i18n.tr

/** The library's search and sort use one state owner and the same presenters on both platforms. */
@Composable
fun LibraryNavigationDialogs(library: LibraryCoordinator, m: GbaTextMetrics, small: GbaTextMetrics) {
    when (library.dialog) {
        LibraryCoordinator.Dialog.SEARCH -> LibrarySearchDialog(library.query, library::search, m, small) {
            library.dismiss(LibraryCoordinator.Dialog.SEARCH)
        }
        LibraryCoordinator.Dialog.SORT -> OptionSelector(tr("SORT BY"), LibrarySort.entries.toList(), library.sort,
            { tr(it.label) }, m, onPick = library::selectSort,
            onDismiss = { library.dismiss(LibraryCoordinator.Dialog.SORT) })
        null -> Unit
    }
}
