package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter
import com.pokedaisy.app.companion.Smoothing
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.displayName
import com.pokedaisy.app.companion.ui.theme.pickTheme
import com.pokedaisy.app.companion.ui.theme.themeById
import platform.Foundation.NSUserDefaults
import com.pokedaisy.app.game.GameViewController

/**
 * The companion's SETTINGS on the iPad: shared battle and appearance options, playback and sound, kept in the
 * app's defaults. Only supported rows are shown ([shownRows]); shaders and EXPAND wait for later ports.
 */
internal class IosCompanionSettings(
    private val romPath: String?,
    /** The ROM header's game code (BPEE...), for LANGUAGE's AUTO; null for a Game Boy cart. */
    private val romCode: String?,
    private val game: GameViewController?,
    private val onClose: () -> Unit,
    private val onRestart: () -> Unit,
    private val onDisableResume: () -> Boolean,
    private val onEditTouch: () -> Unit,
    private val readTouchStage: () -> com.pokedaisy.app.companion.TouchStage?,
    private val gameScreen: () -> IosGameScreen? = { null },
    private val playback: () -> com.pokedaisy.app.companion.GameRuntime? = { null },
    private val stored: com.pokedaisy.app.companion.PrefsCompanionSettings =
        com.pokedaisy.app.companion.PrefsCompanionSettings(iosPreferences,
            languageRomCode = { romCode }, allowDisableResume = onDisableResume),
) : CompanionSettings, com.pokedaisy.app.companion.StoredCompanionSettings by stored {
    private fun options() = playback()?.options
    private fun cheatHost() = playback()?.cheats
    private val d = NSUserDefaults.standardUserDefaults
    private val libraryInput = if (game == null) com.pokedaisy.app.game.GameInput() else null
    override val keyCapture get() = game?.keyCapture ?: libraryInput!!.capture
    fun pollKeyCapture() { libraryInput?.pollCapture() }
    private val bindings by lazy {
        com.pokedaisy.app.companion.GameBindings(com.pokedaisy.app.game.IosGameInputBindings.directory,
            hotkeyActions, ::reloadBindings)
    }
    private fun reloadBindings() {
        actions?.cancelHardware()
        game?.reloadBindings() ?: libraryInput?.reloadBindings()
        game?.setHotkeys(if (hotkeysEnabled) Hotkeys.load(com.pokedaisy.app.game.IosGameInputBindings.directory).forActions(hotkeyActions) else null)
    }

    override val hasRunningGame get() = game != null
    override val gamePickBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_PICKER)
    override val shownRows: Set<String> get() = setOf("BATTLE HINTS", "FOE IVS", "TAB BAR", "DARK MODE", "THEME", "LANGUAGE", "HELP",
        "FF", "FF SPEED", "GAME SPEED", "GAME VOLUME", "MUTE GAME", "ONE-TAP MOVES", "BATTLE INFO", "BATTLE STYLE", "BATTLE VIEW", "BATTLE FOCUS", "RESUME GAMES", "TOUCH CONTROLS", "COMPANION VIEW", "SPEED OPTIONS", "INSTANT TEXT", "REUSABLE TMS", "AUTO-RUN", "RUN INDOORS", "WILD ENCOUNTERS", "GAME BUTTONS", "HOTKEYS", "REWIND", "CHEATS")
        .let { if (game == null) it - "GAME SPEED" - "FF" else it }
        .let { if (cheatHost()?.supported != true) it - "CHEATS" else it }

    override val cheats get() = cheatHost()?.list.orEmpty()
    override fun setCheatsEnabled(on: Boolean) { stored.setCheatsEnabled(on); cheatHost()?.apply() }
    override val cheatsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_CHEATS)
    override val cheatsEditable get() = cheatHost()?.editable == true
    override fun setCheatEnabled(index: Int, on: Boolean) {
        cheatHost()?.setEnabled(index, on)
    }
    override fun addCheat(cheat: com.pokedaisy.app.cheats.Cheat) { cheatHost()?.add(cheat) }
    override fun removeCheat(index: Int) { cheatHost()?.remove(index) }
    override fun importCheats(onResult: (com.pokedaisy.app.cheats.CheatStore.Import?) -> Unit) {
        val g = game ?: return
        val host = cheatHost() ?: return
        val from = g.view.window?.rootViewController ?: return
        g.uiPaused = true
        g.pause()
        com.pokedaisy.app.game.RomPicker.pickData(from) { path ->
            try { if (path != null && g.isRunning && cheatHost() === host) onResult(runCatching { host.import(path) }.getOrNull()) }
            finally { g.uiPaused = false; g.resume() }
        }
    }

    var optionsChanged: () -> Unit = {}
    override val rewindBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_REWIND)
    override fun setRewind(on: Boolean) { stored.setRewind(on); releaseRewind() }
    var actions: com.pokedaisy.app.companion.GameActions? = null
    var rewindHeld = false
        private set
    var rewindChanged: () -> Unit = {}
    var rewindUnavailable: () -> Unit = {}
    fun holdRewind(on: Boolean) {
        if (on && !rewind) rewindUnavailable()
        rewindHeld = on && rewind; rewindChanged()
    }
    fun releaseRewind() {
        actions?.release(Hotkeys.Action.REWIND_HOLD)
        holdRewind(false)
    }
    override val autoRunBuiltIn get() = hasRunningGame && activeGame in com.pokedaisy.app.companion.data.GAMES_WITH_OWN_AUTO_RUN
    override val runIndoorsSupported get() = fieldReadable && com.pokedaisy.app.companion.data.GameField.supportsRunIndoors(romCode.orEmpty())
    override val wildEncountersSupported get() = fieldReadable && options()?.wildFlagFound == true
    override val fieldOptionsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_FIELD_OPTIONS)
    override val hmFreeSupported get() = options()?.hmFreeSupported == true
    override val expShareSupported get() = options()?.expShareSupported == true
    override val levelCapSupported get() = options()?.levelCapSupported == true
    override val levelCapLevel get() = options()?.levelCapNow?.level
    override val instantTextSupported get() = options()?.instantTextSupported == true
    override val reusableTmsSupported get() = options()?.reusableTmsSupported == true
    override val reusableTmsBuiltIn get() = hasRunningGame && activeGame in com.pokedaisy.app.companion.data.ReusableTms.BUILT_IN
    override val gameOptionsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_OPTIONS)

    override val touchRevision get() = IosTouchPreferences.revision
    override val touchStyle get() = IosTouchPreferences.style
    override fun setTouchStyle(style: TouchControlsStyle) { IosTouchPreferences.style = style }
    override val touchButtons get() = IosTouchPreferences.buttons
    override val touchSkinsSupported = false
    override val touchXyBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_TOUCH_XY)
    override val touchBadge get() = touchXyBadge ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_SCREEN)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IPAD_TOUCH)
    override val companionViewBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_SCREEN)
    override val companionWindow get() = gameScreen()?.takeUnless { it.portrait }?.window
    override fun setCompanionWindow(on: Boolean) { gameScreen()?.window = on }
    override val touchStage get() = readTouchStage()
    override val touchStageInOtherOrientation get() = touchStage?.let {
        gameScreen()?.previewStage(otherOrientation = true) ?:
            com.pokedaisy.app.companion.ui.gamePanelLayout(it.h, it.w, it.dp, gameBoy = it.gameBoy, underGame = IosTouchPreferences.underGame, style = IosTouchPreferences.style)
    }
    override val touchControlsShown get() = IosTouchPreferences.visible
    override fun setPortraitUnderGame(on: Boolean) = IosTouchPreferences.setUnderGame(on)
    override val uprightUnderGame get() = IosTouchPreferences.underGame
    override fun editTouchLayout() = onEditTouch()
    override fun feelVibration(strength: Int) = IosTouchPreferences.feel(strength)

    override val resumeGamesEditable = true
    override val resumeBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IPAD_RESUME)

    override fun setAppTheme(id: Int) = pickTheme(themeById(id))

    override val gameName: String get() = if (game == null) "" else activeGame.displayName()
    override val romFileName: String get() = romPath.orEmpty().substringAfterLast('/')

    override fun closeGame() = onClose()
    override fun restartGame() = onRestart()

    override val statesBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IPAD_STATES)

    override val playbackBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IPAD_PLAYBACK)

    private var ffToggle by mutableStateOf(iosPreferences.ffToggled)
    override val ffToggled get() = ffToggle
    private var ffHeld by mutableStateOf(false)
    var slowmoHeld by mutableStateOf(false)
        private set
    val ffActive get() = ffToggled || ffHeld
    fun holdSlowmo(on: Boolean) { slowmoHeld = on; applyPlayback() }
    fun holdFastForward(on: Boolean) { ffHeld = on; applyPlayback() }

    var playbackChanged: () -> Unit = {}
    init {
        stored.changed = { change ->
            when (change) {
                com.pokedaisy.app.companion.PrefsCompanionSettings.Change.PLAYBACK -> applyPlayback()
                com.pokedaisy.app.companion.PrefsCompanionSettings.Change.FIELD_OPTIONS -> optionsChanged()
            }
        }
        applyPlayback(); reloadBindings()
    }

    private fun applyPlayback() {
        val game = game ?: return
        val requested = com.pokedaisy.app.companion.PlaybackPolicy.requestedSpeed(ffActive, ffMaxSpeed, slowmoHeld, gameSpeed)
        game.speed = if (requested.isInfinite()) 0.0 else requested.toDouble()
        game.volume = gameVolume / 100f
        game.muted = gameMuted
        playbackChanged()
    }

    override fun setFfToggled(on: Boolean) { ffToggle = on; stored.setFfToggled(on) }
    override val gameSpeed: Float get() = if (romPath == null) 1f else d.floatForKey("game_speed:$romPath").takeIf { it in gameSpeeds } ?: 1f
    override fun setGameSpeed(v: Float) {
        if (romPath == null || v !in gameSpeeds) return
        d.setFloat(v, "game_speed:$romPath"); applyPlayback()
    }

    // Not on the iPad yet: never shown (shownRows), so fixed values.
    override val ffMusicMode = FfMusicMode.SPED_UP
    override fun setFfMusicMode(mode: FfMusicMode) {}
    override val ffMusicModes = listOf(FfMusicMode.SPED_UP)
    override val speedOptionsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_SCENE_SPEEDS)
    override val fieldReadable get() = playback()?.fieldReadable == true
    override val fieldMovesReadable get() = playback()?.fieldMovesReadable == true
    override val gameButtonsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_BINDINGS)
    override val buttonGroup = GbaControls.Group.GBA
    override fun gbaControlBindings() = bindings.gameButtons()
    override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {
        bindings.setGameButton(btn, keyName)
    }
    override fun clearGbaControlBinding(btn: GbaControls.Btn) {
        bindings.clearGameButton(btn)
    }
    override val hotkeyActions get() = com.pokedaisy.app.companion.GameActions.BASIC_ACTIONS
    override val hotkeysBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_HOTKEYS)
    // Preserve the old triggers as L/R until the player opts in to emulator shortcuts.
    override fun setHotkeysEnabled(on: Boolean) { stored.setHotkeysEnabled(on); reloadBindings() }
    override fun hotkeyBindings() = bindings.hotkeys()
    override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {
        bindings.setHotkey(action, keyNames, takeFrom)
    }
    override val clickSound = false
    override fun setClickSound(on: Boolean) {}
    override val statusBar = false
    override fun setStatusBar(on: Boolean) {}
    override val gameAspect = GameAspect.ORIGINAL
    override fun setGameAspect(aspect: GameAspect) {}
    override val gbaColors = false
    override fun setGbaColors(on: Boolean) {}
    override val screenFilter = ScreenFilter.NONE
    override fun setScreenFilter(filter: ScreenFilter) {}
    override val companionShaders = false
    override fun setCompanionShaders(on: Boolean) {}
    override val gridStrength = GridStrength.MEDIUM
    override fun setGridStrength(strength: GridStrength) {}
    override val smoothing = Smoothing.OFF
    override fun setSmoothing(mode: Smoothing) {}
}
