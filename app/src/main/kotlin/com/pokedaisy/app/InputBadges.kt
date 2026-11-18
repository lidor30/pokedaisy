package com.pokedaisy.app

import com.pokedaisy.app.companion.NewFeatures

/** Presentation belongs to shared-ui; the binding models also run below the UI in shared. */
val Hotkeys.Action.badge: String? get() = when (this) {
    Hotkeys.Action.RESTART_GAME, Hotkeys.Action.CLOSE_APP -> NewFeatures.badge(NewFeatures.GAME_HOTKEYS)
    Hotkeys.Action.QUICK_MENU -> NewFeatures.badge(NewFeatures.QUICK_MENU)
    Hotkeys.Action.COMPANION -> NewFeatures.badge(NewFeatures.COMPANION_HOTKEY)
    Hotkeys.Action.MUTE -> NewFeatures.badge(NewFeatures.GAME_VOLUME)
    Hotkeys.Action.FREE_FLIGHT -> NewFeatures.badge(NewFeatures.FREE_FLIGHT)
    Hotkeys.Action.TOUCH_SCREEN, Hotkeys.Action.DS_LAYOUT -> NewFeatures.badge(NewFeatures.DS_LAYOUT)
    else -> null
}

val GbaControls.Btn.badge: String? get() = when (this) {
    GbaControls.Btn.TO_COMPANION -> NewFeatures.badge(NewFeatures.X_COMPANION)
    GbaControls.Btn.START_2, GbaControls.Btn.SELECT_2 -> NewFeatures.badge(NewFeatures.GBA_BUTTONS)
    GbaControls.Btn.UP, GbaControls.Btn.DOWN, GbaControls.Btn.LEFT, GbaControls.Btn.RIGHT -> NewFeatures.badge(NewFeatures.DPAD_KEYS)
    else -> null
}
