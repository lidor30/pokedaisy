package com.pokedaisy.app.companion

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.i18n.tk

/**
 * Bridge for the bottom-screen SETTINGS tab: lets the player tweak settings
 * from Screen-2 without leaving the game. Implemented by PokeDaisyActivity,
 * which also live-applies changes to the running
 * [com.pokedaisy.app.EmulatorEngine] / touch-controls visibility,
 * not just persisting them to [com.pokedaisy.app.Prefs].
 *
 * Key/button rebinding here is a TAP-A-NAME-FROM-A-LIST picker, not "press the
 * button you want" — the Presentation on Screen-2 never receives physical
 * `KeyEvent`s (those go to the main Activity on the top screen), so there's no
 * way to capture a live keypress there. Picking from a list only needs touch,
 * which Screen-2 gets fine. Only single-key bindings are settable this way
 * (chords stay a top-screen-Settings-only feature).
 */
interface CompanionSettings {
    /** Fast-forward speed cap. 0 = unlimited. */
    val ffMaxSpeed: Float
    fun setFfMaxSpeed(v: Float)

    /** Whether fast-forward is currently toggled on (independent of the hold trigger). */
    val ffToggled: Boolean
    fun setFfToggled(on: Boolean)

    /** What fast-forward sounds like ([FfMusicMode]). */
    val ffMusicMode: FfMusicMode
    fun setFfMusicMode(mode: FfMusicMode)

    /** Where fast-forward applies ([FfMode]). */
    val ffMode: FfMode
    fun setFfMode(mode: FfMode)

    /** REWIND on / off ([Prefs.rewind]); hosts without a game leave it off. */
    val rewind: Boolean get() = false
    fun setRewind(on: Boolean) {}

    /** 0 = auto (only if no gamepad), 1 = always, 2 = never. */
    val touchControlsMode: Int
    fun setTouchControlsMode(v: Int)

    /** Current GBA-button bindings, by display name (e.g. "BUTTON_A", "Z"). */
    fun gbaControlBindings(): Map<GbaControls.Btn, List<String>>

    /** Rebinds a GBA button to a single key, by display name (no "KEYCODE_" prefix). */
    fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String)

    /** Whether the hotkeys fire at all ([com.pokedaisy.app.Prefs.hotkeysEnabled]). */
    val hotkeysEnabled: Boolean
    fun setHotkeysEnabled(on: Boolean)

    /** Current hotkey bindings, by display name. */
    fun hotkeyBindings(): Map<Hotkeys.Action, List<String>>

    /** Rebinds a hotkey to a single key, by display name — replaces any existing
     * chord with just this one key; null unbinds it. The key comes off [takeFrom]
     * (other hotkeys that had exactly it, moved by the player's MOVE). */
    fun setHotkeyBinding(action: Hotkeys.Action, keyName: String?, takeFrom: List<Hotkeys.Action> = emptyList())

    /** Hard-restarts the running game: stops the core, discards any pending
     * suspend-resume state, and reboots the same ROM re-reading its save file
     * fresh from disk — like RetroArch's "Restart". This is the escape hatch
     * for "the save didn't load" (e.g. the save file changed on disk after the
     * core already had it open), since a normal in-game reset never re-reads
     * the save. Caller (the UI) is expected to confirm before calling this. */
    fun restartGame()

    /** Ends the session and returns to the ROM list — same as backing out of
     * the game normally, so progress is suspended/saved the usual way via the
     * activity lifecycle. Caller (the UI) is expected to confirm first. */
    fun closeGame()

    /** Whether the companion can step off its own screen while the game runs (two screens, not swapped). */
    val canCloseCompanion: Boolean get() = false

    /** Closes the companion's screen (the device's own launcher shows there) - BACK on the game brings it back. */
    fun closeCompanion() {}

    /** Whether battle-only strategic hints (move effectiveness chips, foe
     * Weak-to/Resists/Immune-to) are shown — see [Prefs.showHints]. */
    val showHints: Boolean
    fun setShowHints(on: Boolean)

    /** The app's language setting ("AUTO" or an AppLanguage code) - see [Prefs.appLanguage]. */
    val appLanguage: String get() = com.pokedaisy.app.companion.i18n.LANGUAGE_AUTO
    fun setAppLanguage(code: String) {}

    /** Whether battle INFO has a STATS page with the foe's IVs - see [Prefs.showFoeIvs]. */
    val showFoeIvs: Boolean get() = false
    fun setShowFoeIvs(on: Boolean) {}

    /** Whether a launch picks up where the game was left - see [Prefs.autoResume]; off, CLOSE GAME warns. */
    val autoResume: Boolean get() = true

    /** Stores a SETTINGS > TWEAKS switch ([com.pokedaisy.app.companion.ui.CompanionTweaks]) by its key. */
    fun setTweak(key: String, on: Boolean) {}

    /** Whether the companion's buttons play the game's click - see [Prefs.clickSound]. */
    val clickSound: Boolean
    fun setClickSound(on: Boolean)

    /** Whether the top screen shows [com.pokedaisy.app.companion.ui.GameStatusBar]
     * above the game - see [Prefs.statusBar]. */
    val statusBar: Boolean
    fun setStatusBar(on: Boolean)

    /** The status bar over the companion's tabs instead of over the game - see [Prefs.statusBarOnCompanion]. */
    val statusBarOnCompanion: Boolean get() = false
    fun setStatusBarOnCompanion(on: Boolean) {}

    /** Whether the game is stretched to fill the top screen instead of kept at
     * 3:2 - see [Prefs.stretchGame]. */
    val stretchGame: Boolean
    fun setStretchGame(on: Boolean)

    /** SCREEN > OVERLAY's choices, as (key, label) - see [com.pokedaisy.app.Prefs.overlay]; empty = no row (no game).
     * Imported in the top screen's Settings (a file picker); picked here, live. */
    val overlayChoices: List<Pair<String, String>> get() = emptyList()
    val overlay: String get() = ""
    fun setOverlay(key: String) {}

    /** The GBA LCD's colours on the game - see [Prefs.gbaColors]. */
    val gbaColors: Boolean
    fun setGbaColors(on: Boolean)

    /** The screen effect over the game - see [Prefs.screenFilter]. */
    val screenFilter: ScreenFilter
    fun setScreenFilter(filter: ScreenFilter)

    /** Whether SHADERS also draw over the companion - see [Prefs.companionShaders]. */
    val companionShaders: Boolean
    fun setCompanionShaders(on: Boolean)

    /** How strong the grid filters' grid is - see [Prefs.gridStrength]. */
    val gridStrength: GridStrength
    fun setGridStrength(strength: GridStrength)

    /** Whether there's a second screen - SWAP SCREENS only shows then. */
    val hasSecondScreen: Boolean get() = false

    /**
     * A phone held upright: the companion right under the game (true; the touch pad gets the
     * screen's bottom) or along the screen's bottom - see [Prefs.portraitCompanionUnderGame].
     * Null when the companion isn't under the game (a second screen, or held sideways): no row.
     */
    val portraitUnderGame: Boolean? get() = null
    fun setPortraitUnderGame(on: Boolean) {}

    /** The game on the second screen and the companion on the main one - see [Prefs.swapScreens]. */
    val swapScreens: Boolean get() = false
    fun setSwapScreens(on: Boolean) {}

    /** The running game's cheats (added in the top screen's Settings > CHEATS), for SETTINGS > CHEATS. */
    val cheats: List<com.pokedaisy.app.cheats.Cheat> get() = emptyList()

    /** The CHEATS master switch - see [com.pokedaisy.app.Prefs.cheatsEnabled]. Applies at once. */
    val cheatsEnabled: Boolean get() = false
    fun setCheatsEnabled(on: Boolean) {}

    /** Turns the game's cheat [index] (in [cheats]) on or off; the game picks it up on the next frame. */
    fun setCheatEnabled(index: Int, on: Boolean) {}

    /** Display name of the currently-detected game (e.g. "Pokémon Heart and
     * Soul") — see [com.pokedaisy.app.companion.data.GameKind.displayName]. */
    val gameName: String

    /** File name of the ROM currently loaded (e.g. "heart_and_soul.gba"). */
    val romFileName: String

    /** Which of [COMPANION_TABS] sit in the tab bar (at most [MAX_BAR_TABS]);
     * the rest open from SETTINGS. See [Prefs.companionTabs]. */
    val companionTabs: List<String>
    fun setCompanionTabs(tabs: List<String>)

    /** Whether the GUIDE's "written with AI help" notice was accepted for [game]
     * (a [com.pokedaisy.app.companion.data.GameKind] name). */
    fun guideNoticeAccepted(game: String): Boolean
    fun acceptGuideNotice(game: String)
}

/** Every tab the player can show or hide, in tab-bar order. BATTLE (while a
 * battle runs) and SETTINGS (the way to the hidden ones) are always there.
 * CARD (the TRAINER CARD) and ACHIEVEMENTS (RetroAchievements) start off the
 * bar, under SETTINGS > TOOLS. */
val COMPANION_TABS = listOf("PARTY", "DEX", "MAP", "ITEMS", "GUIDE", "CARD", "STATES", "ACHIEVEMENTS")
val DEFAULT_COMPANION_TABS = listOf("PARTY", "DEX", "MAP", "ITEMS", "GUIDE")

/** Tabs next to SETTINGS: six chips in all is as many as fit. During a
 * battle BATTLE takes the last chosen tab's place. */
const val MAX_BAR_TABS = 5

/** Where fast-forward applies, in the order SETTINGS lists them. [label] stays English (the
 * selectors match by it); the rows translate it where they draw it. */
enum class FfMode(val label: String) {
    /** Menu screens (party, bag, summary, PC, Pokédex, trainer card...) and the
     * region map run at 1x; the field, dialogue and battles stay fast - in a
     * battle only its own bag and party screens slow down ([smartSlows]). */
    SMART(tk("SMART")),

    /** Fast everywhere. */
    NORMAL(tk("NORMAL")),
}


/** SETTINGS > SHADERS > FILTER: the screen effect drawn over the game (EmulatorView, ScreenShaders),
 * in the order the selector lists them. [label] stays English; the rows translate it. */
enum class ScreenFilter(val label: String) {
    NONE(tk("NONE")),
    /** The GBA's pixel grid. */
    LCD(tk("LCD")),
    /** A soft grid on paper: an unlit, reflective LCD (simpletex_lcd's style). */
    LCD_PAPER(tk("LCD PAPER")),
    SCANLINES(tk("SCANLINES")),
    /** Soft scanline beams and an RGB mask. */
    CRT(tk("CRT")),
}

/** The filter after this one, wrapping: SHADERS > FILTER steps through them on each tap (the user's
 * call, so each look shows on the game at once - the one row that cycles past two values). */
fun ScreenFilter.next(): ScreenFilter = ScreenFilter.entries[(ordinal + 1) % ScreenFilter.entries.size]

/** Whether this filter draws a pixel grid that SHADERS > GRID tunes. */
val ScreenFilter.hasGrid: Boolean get() = this == ScreenFilter.LCD || this == ScreenFilter.LCD_PAPER

/** SHADERS > GRID: how strongly LCD / LCD PAPER draw their grid (ScreenShaders.gridFor), in the
 * order a tap steps through them - it cycles like FILTER, so each shows on the game at once. */
enum class GridStrength(val label: String) {
    SOFT(tk("SOFT")),
    MEDIUM(tk("MEDIUM")),
    STRONG(tk("STRONG")),
    ;

    fun next(): GridStrength = entries[(ordinal + 1) % entries.size]
}

/**
 * What fast-forward sounds like, in the order SETTINGS lists them. [label] is
 * what the rows show; [alpha] marks a mode that isn't finished yet.
 */
enum class FfMusicMode(val label: String, val alpha: Boolean = false) {
    /** The song at its normal tempo: a clean clip rendered from the ROM
     * (FfMusicRenderer), looped while the game races; SPED-UP until it's ready. */
    STEADY(tk("STEADY"), alpha = true),

    /** The game's own audio, sped up with it (pitch and tempo). */
    SPED_UP(tk("SPED-UP")),

    /** Silent. */
    OFF(tk("OFF")),
}
