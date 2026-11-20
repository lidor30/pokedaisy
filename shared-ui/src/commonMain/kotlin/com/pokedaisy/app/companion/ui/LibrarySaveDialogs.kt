package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import com.pokedaisy.app.companion.i18n.tr

/** The library's dated backup choices feed the same confirmation as an imported save. */
@Composable
fun <T> LibraryRestoreBackupDialog(
    files: List<T>, label: (T) -> String, m: GbaTextMetrics,
    onPick: (T) -> Unit, onDismiss: () -> Unit,
) {
    OptionSelector(
        tr("RESTORE BACKUP"), files.indices.toList(), -1, { label(files[it]) }, m,
        onPick = { onPick(files[it]) }, onDismiss = onDismiss,
    )
}

/** Confirm replacing a battery save through LOAD SAVE, whether picked from Files or from the backup list. */
@Composable
fun LibraryLoadSaveDialog(
    game: String, source: String, keepPreviousAs: String?, m: GbaTextMetrics,
    warnAboutGbaSize: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    val replacement = if (keepPreviousAs != null) {
        tr("{0}: replace its save with {1}? The current save is kept, renamed to {2}.", game, source, keepPreviousAs)
    } else {
        tr("{0} has no save yet: use {1} as its save?", game, source)
    }
    val fresh = tr("The game starts from this save next time, not from where you left off.")
    val warning = if (warnAboutGbaSize) " " + tr("This doesn't look like a GBA save file.") else ""
    OptionConfirm(tr("LOAD SAVE?"), "$replacement $fresh$warning", tr("LOAD"), m,
        onDismiss = onDismiss, onConfirm = onConfirm)
}
