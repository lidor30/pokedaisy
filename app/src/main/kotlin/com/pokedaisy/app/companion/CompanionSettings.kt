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
 * Key/button rebinding is by pressing the keys, never by picking a key's name: the Presentation on Screen-2
 * never receives physical `KeyEvent`s (they go to the game's Activity), so the capture runs there
 * ([keyCapture]) and the game's screen asks for the keys while a row here waits.
 */
interface CompanionSettings {
    /** Fast-forward speed cap. 0 = unlimited. */
    val ffMaxSpeed: Float
    fun setFfMaxSpeed(v: Float)

    /** GAME SPEED: the running game's speed step (1x normal; slower or faster until changed), kept per game. */
    val gameSpeed: Float get() = 1f
    val gameSpeeds: List<Float> get() = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f)
    fun setGameSpeed(v: Float) {}

    /** SCENE SPEEDS (GitHub #43 / #85): a battle's, a field text box's and a menu screen's speed while FF is off;
     * 0 = SAME (GAME SPEED's) - see [com.pokedaisy.app.Prefs.battleSpeed]. */
    val battleSpeed: Float get() = 0f
    fun setBattleSpeed(v: Float) {}
    val textSpeed: Float get() = 0f
    fun setTextSpeed(v: Float) {}
    val menuSpeed: Float get() = 0f
    fun setMenuSpeed(v: Float) {}
    /** BIKE / SURF SPEED - see [com.pokedaisy.app.Prefs.bikeSpeed]; [fieldMovesReadable]: the game's bike / surf state
     * is read (FireRed / Emerald engines with their player struct found, Ruby / Sapphire by sprite). */
    val bikeSpeed: Float get() = 0f
    fun setBikeSpeed(v: Float) {}
    val surfSpeed: Float get() = 0f
    fun setSurfSpeed(v: Float) {}
    val fieldMovesReadable: Boolean get() = false

    /** Whether the running game's field can be read for TEXT SPEED and AUTO-RUN (WIDE's tables know it). */
    val fieldReadable: Boolean get() = false

    /** AUTO-RUN: B held while walking (holding it walks) - see [com.pokedaisy.app.Prefs.autoRun]. */
    val autoRun: Boolean get() = false
    fun setAutoRun(on: Boolean) {}

    /** RUN INDOORS - see [com.pokedaisy.app.Prefs.runIndoors]; [runIndoorsSupported]: a FireRed / Emerald engine the
     * field reads know (Ruby / Sapphire tell indoor maps by their type, which other things read too). */
    val runIndoors: Boolean get() = false
    fun setRunIndoors(on: Boolean) {}
    val runIndoorsSupported: Boolean get() = false

    /** WILD ENCOUNTERS: the percent left - see [com.pokedaisy.app.Prefs.wildEncounters]; [wildEncountersSupported]: the
     * game's switch was found in the ROM ([com.pokedaisy.app.companion.data.WildEncounters]) and its field is read. */
    val wildEncounters: Int get() = 100
    fun setWildEncounters(percent: Int) {}
    val wildEncountersSupported: Boolean get() = false

    /** REUSABLE TMS - see [com.pokedaisy.app.Prefs.reusableTms]; [reusableTmsSupported]: the ROM's code that uses a TM
     * up is known ([com.pokedaisy.app.companion.data.ReusableTms]). */
    val reusableTms: Boolean get() = false
    fun setReusableTms(on: Boolean) {}
    val reusableTmsSupported: Boolean get() = false
    /** The game's own TMs never run out ([com.pokedaisy.app.companion.data.ReusableTms.BUILT_IN]): the row says so. */
    val reusableTmsBuiltIn: Boolean get() = false

    /** PLAYER LOOK ([com.pokedaisy.app.companion.data.PlayerLook]): [playerLookSupported] = a FireRed / LeafGreen /
     * Emerald game; [playerLooks] = the heroes it can wear now (another game's, kept from the player's own ROMs);
     * [playerLook] = the one worn, by name (null = the game's own). */
    val playerLookSupported: Boolean get() = false
    val playerLooks: List<String> get() = emptyList()
    val playerLook: String? get() = null
    /** The player's own hero here, by name (the picker's OFF tile); null before the save has a gender. */
    val playerLookOwn: String? get() = null
    fun setPlayerLook(look: String?) {}
    /** A PLAYER LOOK pick's name as the row and its tile show it (a hero's, or a Pokémon's in the game). */
    fun playerLookLabel(name: String): String = com.pokedaisy.app.companion.data.LookChar.byName(name)?.label ?: name
    /** RIVAL LOOK: the rival of the other gender ([rivalLookSupported]: Emerald, Ruby, Sapphire) dressed up with the player. */
    val rivalLookSupported: Boolean get() = false
    val rivalLook: Boolean get() = true
    fun setRivalLook(on: Boolean) {}
    /** FOLLOWER: the party's lead walks behind the player ([followerSupported]: a GBA game it's drawn in; [followerReady]:
     * Pokémon sprites kept, from Heart and Soul). */
    val followerSupported: Boolean get() = false
    val followerReady: Boolean get() = false
    val follower: Boolean get() = false
    fun setFollower(on: Boolean) {}
    /** DAY AND NIGHT ([com.pokedaisy.app.companion.data.DayNight]): the overworld tinted by the clock. */
    val dayNightSupported: Boolean get() = false
    val dayNight: Boolean get() = false
    fun setDayNight(on: Boolean) {}
    /** TEXT LOOK: the other games' text looks kept ([textLooks], "FIRERED" / "EMERALD"), the one on ([textLook], null =
     * the game's own). */
    val textLooks: List<String> get() = emptyList()
    val textLook: String? get() = null
    fun setTextLook(style: String?) {}

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
    /** Whether the running game's core can rewind (not a DS game: its states are megabytes each). */
    val rewindSupported: Boolean get() = true

    /** SETTINGS > TOUCH CONTROLS - see [com.pokedaisy.app.Prefs.touchControlsStyle]. */
    val touchStyle: TouchControlsStyle
    fun setTouchStyle(style: TouchControlsStyle)

    /** The game's screen as the touch controls see it, for TOUCH CONTROLS' preview; null = laid out like a phone's. */
    val touchStage: TouchStage? get() = null

    /** The newest savestate's picture for the preview (null: a drawn stand-in). */
    fun touchPreviewShot(): androidx.compose.ui.graphics.ImageBitmap? = null

    /** EDIT LAYOUT over the running game (the companion steps aside while it's open). */
    fun editTouchLayout() {}

    /** The touch controls are on the game's screen now (SHOW, a controller): EXPAND's UNDER GAME needs them. */
    val touchControlsShown: Boolean get() = true

    /** Bumped when EDIT LAYOUT saves (Compose state): TOUCH CONTROLS' page reads it to show CUSTOM. */
    val touchRevision: Int get() = 0

    /** Waits for the keys to bind (the game's activity gets every key); null where nothing can be pressed (previews). */
    val keyCapture: KeyCapture? get() = null

    /** Current GBA-button bindings, by display name (e.g. "BUTTON_A", "Z"). */
    fun gbaControlBindings(): Map<GbaControls.Btn, List<String>>

    /** Rebinds a GBA button to a single key, by display name (no "KEYCODE_" prefix). */
    fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String)

    /** Leaves TURBO A / B unbound ([GbaControls.Btn.canClear]). */
    fun clearGbaControlBinding(btn: GbaControls.Btn) {}

    /** Whether the hotkeys fire at all ([com.pokedaisy.app.Prefs.hotkeysEnabled]). */
    val hotkeysEnabled: Boolean
    fun setHotkeysEnabled(on: Boolean)

    /** Current hotkey bindings, by display name. */
    fun hotkeyBindings(): Map<Hotkeys.Action, List<String>>

    /** Rebinds a hotkey to one chord, by display names (pressed together) — replaces what it had; null unbinds it.
     * The chord comes off [takeFrom] (other hotkeys that had exactly it, moved by the player's MOVE). */
    fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action> = emptyList())

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

    /** Whether the companion can step off its own screen while the game runs (two screens; swapped, the game moves to the second). */
    val canCloseCompanion: Boolean get() = false

    /** Closes the companion's screen (the device's own launcher shows there) - BACK on the game brings it back. */
    fun closeCompanion() {}

    /**
     * The companion was closed while the screens were swapped: the game has the second screen and this companion opens
     * over it, as on one screen. Its SETTINGS then offers OPEN COMPANION ([openCompanion]) instead of CLOSE COMPANION.
     */
    val companionClosed: Boolean get() = false

    /** Puts the companion back on its own screen (the main one, the game back on the second). */
    fun openCompanion() {}

    /** Whether battle-only strategic hints (move effectiveness chips, foe
     * Weak-to/Resists/Immune-to) are shown — see [Prefs.showHints]. */
    val showHints: Boolean
    fun setShowHints(on: Boolean)

    /** The app's language setting ("AUTO" or an AppLanguage code) - see [Prefs.appLanguage]. */
    val appLanguage: String get() = com.pokedaisy.app.companion.i18n.LANGUAGE_AUTO
    fun setAppLanguage(code: String) {}

    /** Saves DARK MODE (an ordinal of DarkModeSetting, see [Prefs.darkMode]); the look itself is DarkMode's state. */
    fun setDarkMode(v: Int) {}

    /** Whether battle INFO has a STATS page with the foe's IVs - see [Prefs.showFoeIvs]. */
    val showFoeIvs: Boolean get() = false
    fun setShowFoeIvs(on: Boolean) {}

    /** ONE-TAP MOVES - see [Prefs.oneTapMoves]. */
    val oneTapMoves: Boolean get() = false
    fun setOneTapMoves(on: Boolean) {}

    /** Whether battle INFO shows FOE DETAILS - see [Prefs.foeDetails]. */
    val foeDetails: Boolean get() = false
    fun setFoeDetails(on: Boolean) {}

    /** See [Prefs.battleFocus]. */
    val battleFocus: Boolean get() = false
    fun setBattleFocus(on: Boolean) {}

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

    /** SETTINGS > HUD: the HUD over the overworld - see [Prefs.fieldHud]. */
    val fieldHud: Boolean get() = true
    fun setFieldHud(on: Boolean) {}

    /** SETTINGS > BATTLE BUTTONS: one screen, FIGHT, POKéMON and RUN beside a battle - see [Prefs.battleButtons]. */
    val battleButtons: Boolean get() = true
    fun setBattleButtons(on: Boolean) {}

    /** The FIELD HUD's layout, look, size and parts - see [Prefs.fieldHudStyle]. */
    val fieldHudStyle: com.pokedaisy.app.companion.FieldHudStyle get() = com.pokedaisy.app.companion.FieldHudStyle()
    fun setFieldHudStyle(style: com.pokedaisy.app.companion.FieldHudStyle) {}

    /** SETTINGS > QUICK MENU: the actions it shows - see [Prefs.quickMenuActions]. */
    val quickMenuActions: Set<com.pokedaisy.app.companion.QuickAction> get() = com.pokedaisy.app.companion.QuickAction.entries.toSet()
    fun setQuickMenuActions(actions: Set<com.pokedaisy.app.companion.QuickAction>) {}
    val quickMenuStyle: com.pokedaisy.app.companion.QuickMenuStyle get() = com.pokedaisy.app.companion.QuickMenuStyle.RING
    fun setQuickMenuStyle(style: com.pokedaisy.app.companion.QuickMenuStyle) {}
    val quickMenuLabels: Boolean get() = true
    fun setQuickMenuLabels(on: Boolean) {}

    /** FPS / CPU over the game's corner - see [Prefs.showPerformance]. */
    val showPerformance: Boolean get() = false
    fun setShowPerformance(on: Boolean) {}

    /** How the game sits on the top screen: 3:2, stretched or WIDE - see [Prefs.gameAspect]. */
    val gameAspect: GameAspect
    fun setGameAspect(aspect: GameAspect)

    /** Whether WIDE knows the running game (WideScreen.gameFor); it shows as ORIGINAL otherwise. */
    val wideSupported: Boolean get() = false

    /** EXPAND's AUTO ZOOM IN - see [com.pokedaisy.app.Prefs.expandZoomOnText]. */
    val expandZoomOnText: Boolean get() = true
    fun setExpandZoomOnText(on: Boolean) {}

    /** EXPAND's one-time offer in a game it knows, while EXPAND is off ([com.pokedaisy.app.Prefs.expandGameOfferSeen]):
     * true while it's up (Compose state). Shown once ever - the setting is global. */
    val expandOffer: Boolean get() = false
    fun answerExpandOffer(turnOn: Boolean) {}

    /** SCREEN > OVERLAY's choices, as (key, label) - see [com.pokedaisy.app.Prefs.overlay]; empty = no row (no game).
     * Imported in the top screen's Settings (a file picker); picked here, live. */
    val overlayChoices: List<Pair<String, String>> get() = emptyList()
    val overlay: String get() = ""
    fun setOverlay(key: String) {}

    /** The GBA LCD's colours on the game - see [Prefs.gbaColors]. */
    val gbaColors: Boolean
    fun setGbaColors(on: Boolean)

    /** The unlit GBA screen on the game - see [Prefs.unlitScreen]. */
    val unlitScreen: Boolean get() = false
    fun setUnlitScreen(on: Boolean) {}

    /** The screen effect over the game - see [Prefs.screenFilter]. */
    val screenFilter: ScreenFilter
    fun setScreenFilter(filter: ScreenFilter)

    /** DISPLAY OPTIONS > LIGHT COMPANION ([LightCompanion], whose mode is Compose state): the activity also stores it
     * and re-applies what it changes (ON COMPANION's shaders). */
    fun setLightCompanion(mode: LightCompanionMode) { LightCompanion.mode = mode }

    /** Whether SHADERS also draw over the companion - see [Prefs.companionShaders]. */
    val companionShaders: Boolean
    fun setCompanionShaders(on: Boolean)

    /** How strong the grid filters' grid is - see [Prefs.gridStrength]. */
    val gridStrength: GridStrength
    fun setGridStrength(strength: GridStrength)

    /** The edge-smoothing upscaler on the game - see [Prefs.smoothing]. */
    val smoothing: Smoothing
    fun setSmoothing(mode: Smoothing)

    /** Whether this device can run SMOOTHING (OpenGL ES 3); without it the row is greyed out. */
    val smoothingAvailable: Boolean get() = true

    /** Whether there's a second screen - SINGLE SCREEN only shows then. */
    val hasSecondScreen: Boolean get() = false

    /** A second screen played on one anyway - see [com.pokedaisy.app.Prefs.singleScreen]. */
    val singleScreen: Boolean get() = false
    fun setSingleScreen(on: Boolean) {}

    /** The companion is on the second screen (there is one, and SINGLE SCREEN is off): SWAP SCREENS shows, the
     * one-screen rows (BATTLE BUTTONS, COMPANION VIEW) don't. */
    val usesSecondScreen: Boolean get() = hasSecondScreen && !singleScreen

    /**
     * A phone held upright: the companion right under the game (true; the touch controls get the
     * screen's bottom) or along the screen's bottom - see [Prefs.portraitCompanionUnderGame].
     * Null when the companion isn't under the game (a second screen, or held sideways): no row.
     */
    val portraitUnderGame: Boolean? get() = null
    fun setPortraitUnderGame(on: Boolean) {}

    /** The same place for TOUCH CONTROLS' page, held either way on a one-screen device (the preview shows upright
     * too); null with a second screen. */
    val uprightUnderGame: Boolean? get() = null

    /**
     * One screen held sideways: the companion as a window in the middle over the game (true) or the side panel -
     * see [com.pokedaisy.app.Prefs.companionWindow]. Null when the companion isn't on the game's screen sideways: no row.
     */
    val companionWindow: Boolean? get() = null
    fun setCompanionWindow(on: Boolean) {}

    /** SETTINGS > COMPANION BUTTON: the edge tab that opens the companion ([com.pokedaisy.app.Prefs.companionTab]). Null
     * where [companionWindow] is (no row). */
    val companionTab: Boolean? get() = null
    fun setCompanionTab(on: Boolean) {}

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
     * the rest open from the MORE tab. See [Prefs.companionTabs]. */
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
 * bar, under the MORE tab. */
val COMPANION_TABS = listOf("PARTY", "DEX", "MAP", "ITEMS", "GUIDE", "CARD", "STATES", "ACHIEVEMENTS")
val DEFAULT_COMPANION_TABS = listOf("PARTY", "DEX", "MAP", "ITEMS", "GUIDE")

/** Tabs next to SETTINGS: six chips in all is as many as fit. During a
 * battle BATTLE takes the last chosen tab's place. */
const val MAX_BAR_TABS = 5

/** Where fast-forward applies, in the order SETTINGS lists them. [label] stays English (the
 * selectors match by it); the rows translate it where they draw it. */
enum class FfMode(val label: String) {
    /** Menu screens (party, bag, summary, PC, Pokédex, trainer card...) and the
     * region map run at 1x; the field, dialogue and battles stay fast - but a
     * battle waiting for the player's command (its menus, bag, party) runs at 1x ([smartSlows]). */
    SMART(tk("SMART")),

    /** Fast everywhere. */
    NORMAL(tk("NORMAL")),
}


/** SCENE SPEEDS' choices, SAME (0: GAME SPEED's) first. */
val SCENE_SPEEDS = listOf(0f, 1f, 1.5f, 2f, 3f, 4f)

/** SETTINGS > ASPECT: how the game sits on its screen, in the order the pickers list them.
 * [label] stays English; the rows translate it. */
enum class GameAspect(val label: String) {
    /** The GBA's 3:2, bars at the sides. */
    ORIGINAL(tk("ORIGINAL")),
    /** The game stretched to fill the screen. */
    STRETCH(tk("STRETCH")),
    /** EXPAND (WIDE in the code and prefs): the overworld drawn past the GBA's edges - beside the game on a wide
     * screen, above and below it on a phone upright - rebuilt from the game's own memory (WideScreen, pk_wide.c);
     * ZOOM and the look-around on top (WideCamera). The games it doesn't know yet show as ORIGINAL. */
    WIDE(tk("EXPAND")),
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

/** SHADERS > SMOOTHING: an edge-smoothing upscaler run on the game before FILTER (ScreenShaders.smoothingPasses),
 * in the order a tap steps through them - it cycles like FILTER. Never on the companion (it's UI, not pixel art).
 * [label]s are the shaders' own names. */
enum class Smoothing(val label: String) {
    OFF(tk("OFF")),
    /** xBRZ freescale: sharp, the cleanest edges. */
    XBRZ(tk("xBRZ")),
    /** xBR level 2: rounder and softer. */
    XBR(tk("xBR")),
    /** ScaleFX: the most even outlines, 3x then scaled (needs float framebuffers). */
    SCALEFX(tk("SCALEFX")),
    ;

    fun next(): Smoothing = entries[(ordinal + 1) % entries.size]
}

/**
 * What fast-forward sounds like, in the order SETTINGS lists them. [label] is
 * what the rows show.
 */
enum class FfMusicMode(val label: String) {
    /** The song at its normal tempo: a clean clip rendered from the ROM
     * (FfMusicRenderer), looped while the game races; silent until it's ready (SPED-UP only for a song
     * that can't get a clip). */
    STEADY(tk("STEADY")),

    /** The game's own audio, sped up with it (pitch and tempo). */
    SPED_UP(tk("SPED-UP")),

    /** Silent. */
    OFF(tk("OFF")),
}
