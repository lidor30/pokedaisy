package com.pokedaisy.app.companion.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** SETTINGS > DARK MODE, stored by [ordinal] (Prefs.darkMode). AUTO follows the device's own setting. */
enum class DarkModeSetting(val label: String) {
    OFF("OFF"), ON("ON"), AUTO("AUTO");

    /** The row's next value on a tap (DARK MODE cycles: OFF, ON, AUTO, OFF...). */
    fun next(): DarkModeSetting = entries[(ordinal + 1) % entries.size]
}

/**
 * Dark mode, for both screens at once: the OPTION windows ([com.pokedaisy.app.companion.ui.OptionColors]),
 * the app's backdrops and the bags' palettes switch to their dark versions. Game art (party slots, the
 * map, the trainer card, battle buttons) stays the game's; only what's behind it is dimmed. Compose state
 * in a process-wide object like [AppColors], so a pick on either screen redraws both.
 */
object DarkMode {
    var setting by mutableStateOf(DarkModeSetting.OFF)
    /** The device is in its dark theme (AUTO follows it); set by the activities and AppTheme. */
    var systemDark by mutableStateOf(false)

    /** Screenshot tests / ui-preview: forced on or off, whatever the setting. */
    var override: Boolean? = null

    val on: Boolean get() = override ?: (setting == DarkModeSetting.ON || setting == DarkModeSetting.AUTO && systemDark)

    /** The stored setting ([ThemeStore]); the device's own dark mode comes from AppTheme and the game's activity. */
    fun load() {
        setting = ThemeStore.current?.let { s -> DarkModeSetting.entries.getOrNull(s.darkMode) } ?: DarkModeSetting.OFF
    }

    fun set(s: DarkModeSetting) {
        ThemeStore.current?.darkMode = s.ordinal
        setting = s
    }
}

/**
 * Where THEME and DARK MODE are kept: the app's Prefs, which the host sets at start ([current]); null (previews,
 * screenshot tests) = the defaults, nothing saved.
 */
interface ThemeStore {
    /** A [ThemeSpec.id]. */
    var appTheme: Int
    /** The theme worn before a limited-time one, to go back to when its dates are over. */
    var themeBeforeSeasonal: Int
    /** A [DarkModeSetting.ordinal]. */
    var darkMode: Int

    companion object {
        @kotlin.concurrent.Volatile var current: ThemeStore? = null
    }
}
