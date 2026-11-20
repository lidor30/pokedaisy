package com.pokedaisy.app.companion.ui.theme

import com.pokedaisy.app.Prefs

/** Theme choices use the same preference keys and defaults on both platforms. */
class PrefsThemeStore(prefs: Prefs) : ThemeStore {
    override var appTheme by prefs::appTheme
    override var themeBeforeSeasonal by prefs::themeBeforeSeasonal
    override var appFont by prefs::appFont
    override var darkMode by prefs::darkMode
}
