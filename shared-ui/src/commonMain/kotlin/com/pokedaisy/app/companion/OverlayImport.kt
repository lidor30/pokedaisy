package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.overlay.*

/** The selection and notices after a native file picker; a collection never replaces the current choice. */
object OverlayImport {
    data class Completed(val selection: String, val notice: Pair<String, String>? = null, val needsImage: OverlayStore.Result.NeedsImage? = null)

    fun complete(result: OverlayStore.Result, selected: String, choices: List<Pair<OverlayChoice, String>>): Completed = when (result) {
        is OverlayStore.Result.Imported -> {
            val single = result.ids.singleOrNull()?.let { OverlayChoice.Imported(it).key }
            val skipped = if (result.skipped > 0) " " + tr("{0} couldn't be used: an image missing, or not a PNG.", result.skipped) else ""
            Completed(single ?: selected, if (single != null)
                tk("OVERLAY ADDED") to tr("{0} is on. Games show it from now on.", overlayLabel(choices, single)) + skipped
            else tk("OVERLAYS ADDED") to tr("{0} overlays added. Tap one to use it.", result.ids.size) + skipped)
        }
        is OverlayStore.Result.NeedsImage -> Completed(selected, needsImage = result)
        is OverlayStore.Result.Failed -> Completed(selected, tk("NOT AN OVERLAY") to when (result.reason) {
            OverlayStore.Reason.TOO_BIG -> tr("That file is too big for an overlay.")
            OverlayStore.Reason.NOTHING_USABLE -> tr("Pick a RetroArch overlay (.cfg with its image), a .zip of them, or a PNG with a see-through screen.")
        })
    }

    fun imagePrompt(need: OverlayStore.Result.NeedsImage) = tr(
        "{0} draws {1}, which wasn't picked with it. Pick {1} next: it's usually beside the .cfg or in a folder next to it.", need.cfg, need.image)
}
