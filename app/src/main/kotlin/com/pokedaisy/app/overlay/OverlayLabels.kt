package com.pokedaisy.app.overlay

import com.pokedaisy.app.companion.i18n.tk

/**
 * SCREEN > OVERLAY's choices with the names both screens show them by: NONE, the built-in frames, then the player's
 * imports by their own names (made unique, since the pick-lists match by label).
 */
fun overlayChoices(store: OverlayStore): List<Pair<OverlayChoice, String>> {
    val imported = store.list()
    val seen = HashSet<String>()
    return OverlayChoice.all(store).map { c ->
        val base = when (c) {
            OverlayChoice.None -> tk("NONE")
            is OverlayChoice.BuiltIn -> builtInLabel(c.style)
            is OverlayChoice.Imported -> imported.firstOrNull { it.id == c.id }?.name ?: c.id.uppercase()
        }
        var label = base
        var n = 2
        while (!seen.add(label)) label = "$base ${n++}"
        c to label
    }
}

/** The label of [key] ([OverlayChoice.key]) among [choices]; NONE for one that's gone (a removed import). */
fun overlayLabel(choices: List<Pair<OverlayChoice, String>>, key: String): String =
    choices.firstOrNull { it.first.key == key }?.second ?: choices.first().second

private fun builtInLabel(style: BuiltInFrames.Style) = when (style) {
    BuiltInFrames.Style.DAISY -> tk("DAISY")
    BuiltInFrames.Style.BEZEL -> tk("BEZEL")
}
