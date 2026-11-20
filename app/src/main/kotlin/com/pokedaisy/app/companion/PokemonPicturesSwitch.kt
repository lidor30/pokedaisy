package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bumped each time POKéMON PICTURES is flipped ([PrefsCompanionSettings]). The setting itself isn't Compose state, so
 * the companion and the HUD read this before it: a flip shows at once, not on the next sample (with LIGHT COMPANION a
 * still field can go a while without one). Here, not in companion.ui, because PrefsCompanionSettings is built without
 * the UI (ui-preview's data module).
 */
object PokemonPicturesSwitch {
    var version by mutableStateOf(0)
        private set
    fun flipped() { version++ }
}
