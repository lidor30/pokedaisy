package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.overlay.OverlayChoice

/** The same choice rows in the application settings and the in-game companion. */
fun overlayChoiceRows(choices: List<Pair<String, String>>, selected: String, removing: Boolean,
                      pick: (String) -> Unit, remove: (String, String) -> Unit): List<SettingRow> = buildList {
    add(groupTitle(tk("BUILT-IN")))
    choices.filter { OverlayChoice.of(it.first) !is OverlayChoice.Imported }.forEach { (key, label) ->
        add(SettingRow(label, tk("ON").takeIf { key == selected }) { pick(key) })
    }
    add(groupTitle(tk("IMPORTED")))
    val imported = choices.mapNotNull { (key, label) -> (OverlayChoice.of(key) as? OverlayChoice.Imported)?.let { Triple(key, it.id, label) } }
    if (imported.isEmpty()) add(SettingRow(tk("NONE YET: IMPORT FILE BELOW"), null, enabled = false) {})
    imported.forEach { (key, id, label) ->
        if (removing) add(SettingRow(label, tk("REMOVE")) { remove(id, label) })
        else add(SettingRow(label, tk("ON").takeIf { key == selected }) { pick(key) })
    }
}
