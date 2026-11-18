package com.pokedaisy.app.game

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.companion.data.PropertiesText
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.writeText
import okio.Path.Companion.toPath

/** Existing iOS defaults, stored in the same editable format as Android's bindings. */
object IosGameInputBindings {
    val directory get() = RomPicker.bindingsDir.toPath()

    fun load(): GbaControls.KeyMaps {
        val file = directory / "controls.properties"
        if (!file.isFile) file.writeText(PropertiesText.format(linkedMapOf(
            "a" to "BUTTON_A, X", "b" to "BUTTON_B, Z",
            "l" to "BUTTON_L1, BUTTON_L2, A", "r" to "BUTTON_R1, BUTTON_R2, S",
            "start" to "BUTTON_START, ENTER", "select" to "BUTTON_SELECT, DEL, SHIFT_RIGHT",
            "start2" to "", "select2" to "", "companion" to "",
        ), "PokeDaisy game buttons - canonical key names"))
        return GbaControls.load(directory)
    }
}
