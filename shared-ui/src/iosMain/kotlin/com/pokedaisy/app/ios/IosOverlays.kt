package com.pokedaisy.app.ios

import androidx.compose.runtime.*
import com.pokedaisy.app.companion.OverlayImport
import com.pokedaisy.app.companion.OverlayImportSession
import com.pokedaisy.app.overlay.*
import kotlinx.coroutines.*
import platform.UIKit.UIViewController

/** Native Files scheduling and publication; import rules, notices, storage and rendering are shared. */
internal object IosOverlays {
    val store by lazy { OverlayStore(appFilesDir()) }
    var revision by mutableIntStateOf(0)
        private set
    var choices by mutableStateOf(builtInOverlayChoices().map { (key, label) -> OverlayChoice.of(key) to label })
        private set
    private val imports by lazy { OverlayImportSession(store) }
    init { refresh() }

    private fun refresh() = IosFileImports.scope.launch {
        choices = withContext(Dispatchers.IO) { overlayChoices(store) }
        revision++
    }

    fun cancel() = imports.cancel()

    fun pick(from: UIViewController, before: () -> Unit, done: (OverlayImport.Completed?) -> Unit) {
        var earlier = emptyList<OverlayStore.Picked>()
        IosFileImports.pick(from, before = {
            earlier = imports.takePendingImage()
            before()
        }, read = { path ->
            val picked = runCatching { OverlayStore.readPicked(path) }.getOrNull()
            if (picked == null) imports.import(emptyList(), OverlayStore.Reason.TOO_BIG)
            else imports.import(earlier + picked)
        }, done = { result ->
            var completed: OverlayImport.Completed? = null
            try {
                if (result != null) {
                    completed = imports.complete(result, iosPreferences.overlay)
                    iosPreferences.overlay = completed.selection
                    choices = result.choices
                    revision++
                }
            } finally { done(completed) }

        })
    }

    fun delete(id: String) = remove { imports.delete(id) }

    fun deleteAll() = remove { imports.deleteAll() }

    private fun remove(delete: () -> OverlayImportSession.Removal) = IosFileImports.scope.launch {
        val removal = withContext(Dispatchers.IO) { delete() }
        iosPreferences.overlay = removal.selectionAfter(iosPreferences.overlay)
        choices = removal.choices
        revision++
    }
}
