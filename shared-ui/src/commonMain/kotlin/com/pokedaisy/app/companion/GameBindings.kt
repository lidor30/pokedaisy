package com.pokedaisy.app.companion

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import okio.Path

/** Publish a binding reload only after a valid edit is saved. Files retain unsupported platform choices. */
class GameBindings(
    private val directory: Path,
    private val actions: Collection<Hotkeys.Action>,
    private val changed: () -> Unit,
) {
    fun gameButtons() = GbaControls.rawBindings(directory)
    fun hotkeys() = Hotkeys.load(directory).forActions(actions).rawBindings

    fun setGameButton(button: GbaControls.Btn, key: String): Boolean =
        publish(GbaControls.setBindingNamed(directory, button, key))

    fun clearGameButton(button: GbaControls.Btn): Boolean = publish(GbaControls.clearBinding(directory, button))

    fun setHotkey(action: Hotkeys.Action, keys: List<String>?, takeFrom: List<Hotkeys.Action>): Boolean {
        if (action !in actions || takeFrom.any { it !in actions }) return false
        return publish(if (keys.isNullOrEmpty()) Hotkeys.clearBinding(directory, action)
            else Hotkeys.setBindingNamed(directory, action, keys, takeFrom))
    }

    private fun publish(saved: Boolean): Boolean {
        if (saved) changed()
        return saved
    }
}
