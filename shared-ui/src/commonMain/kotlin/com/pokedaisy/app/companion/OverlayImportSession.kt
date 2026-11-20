package com.pokedaisy.app.companion

import com.pokedaisy.app.overlay.OverlayChoice
import com.pokedaisy.app.overlay.OverlayStore
import com.pokedaisy.app.overlay.overlayChoices

/** The unfinished config belongs to the settings session. Hosts own document pickers and their IO dispatch. */
class OverlayImportSession(private val store: OverlayStore) {
    private var pending = emptyList<OverlayStore.Picked>()

    class Imported internal constructor(
        internal val result: OverlayStore.Result,
        internal val files: List<OverlayStore.Picked>,
        val choices: List<Pair<OverlayChoice, String>>,
    )

    class Removal internal constructor(
        private val id: String?,
        val choices: List<Pair<OverlayChoice, String>>,
    ) {
        fun selectionAfter(selected: String): String {
            val imported = OverlayChoice.of(selected) as? OverlayChoice.Imported ?: return selected
            return if (id == null || imported.id == id) OverlayChoice.None.key else selected
        }
    }

    /** Consume the config once, including when the image picker is cancelled. Call on the UI thread. */
    fun takePendingImage(): List<OverlayStore.Picked> = pending.also { cancel() }

    fun cancel() { pending = emptyList() }

    /** Readable files take precedence over a failed read in a multi-file pick. Call off the UI thread. */
    fun import(
        files: List<OverlayStore.Picked>,
        emptyReason: OverlayStore.Reason = OverlayStore.Reason.NOTHING_USABLE,
    ): Imported {
        val result = if (files.isEmpty()) OverlayStore.Result.Failed(emptyReason) else store.import(files)
        return Imported(result, files, overlayChoices(store))
    }

    /** Apply the result against the current preference, which may have changed while reading the document. */
    fun complete(imported: Imported, selected: String): OverlayImport.Completed {
        val completed = OverlayImport.complete(imported.result, selected, imported.choices)
        pending = if (completed.needsImage != null) imported.files else emptyList()
        return completed
    }

    fun delete(id: String): Removal {
        store.delete(id)
        return Removal(id, overlayChoices(store))
    }

    fun deleteAll(): Removal {
        store.deleteAll()
        return Removal(null, overlayChoices(store))
    }
}
