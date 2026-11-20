package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.SharedSetting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.PlayerLookSession
import com.pokedaisy.app.companion.Smoothing
import com.pokedaisy.app.companion.changeExpandedZoom
import com.pokedaisy.app.companion.zoomFactors
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.displayName
import com.pokedaisy.app.companion.ui.theme.pickTheme
import com.pokedaisy.app.companion.ui.theme.themeById
import platform.Foundation.NSUserDefaults
import com.pokedaisy.app.gameAudioGain
import com.pokedaisy.app.game.GameViewController

private val IOS_FF_MUSIC_MODES = FfMusicMode.entries

/**
 * The companion's SETTINGS on the iPad: shared battle and appearance options, playback and sound, kept in the
 * app's defaults. Only supported rows are shown ([shownRows]); screen filters wait for their native renderer adapter.
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
    private val presentFrom: () -> platform.UIKit.UIViewController? = { game?.view?.window?.rootViewController },
    private val looks: PlayerLookSession? = null,
    private val tradeSupported: () -> Boolean = { false },
    private val coop: IosCoopGame? = null,
    private val stored: com.pokedaisy.app.companion.PrefsCompanionSettings =
        com.pokedaisy.app.companion.PrefsCompanionSettings(iosPreferences,
            languageRomCode = { romCode }, allowDisableResume = onDisableResume),
) : CompanionSettings, com.pokedaisy.app.companion.StoredCompanionSettings by stored {
    override val playerLookSupported get() = looks?.supported == true
    override val playerLookBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_POKEMON_COSTUMES) ?:
        com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_PLAYER_LOOK)
    override val playerLooks get() = looks?.choices.orEmpty()
    override val playerLook get() = looks?.selected
    override fun playerLookLabel(name: String) = looks?.costumeLabel(name) ?: super<CompanionSettings>.playerLookLabel(name)
    override val playerLookOwn get() = looks?.own
    override val rivalLookSupported get() = looks?.rivalSupported == true
    override val rivalLookBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_RIVAL_LOOK)
    override fun setRivalLook(on: Boolean) {
        if (!rivalLookSupported) return
        stored.setRivalLook(on)
        looks?.apply()
    }
    override val textLooks get() = looks?.textChoices.orEmpty()
    override val textLook get() = looks?.textSelected
    override val textLookBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_TEXT_LOOK)
    override fun setTextLook(style: String?) { if (coop?.refuseBattle() != true) looks?.selectText(style) }
    override val rideVehicleSupported get() = looks?.supported == true
    override val rideVehicle get() = looks?.vehicle ?: com.pokedaisy.app.companion.data.RideVehicle.BIKE
    override val rideVehicleBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_RIDE_ON)
    override fun setRideVehicle(v: com.pokedaisy.app.companion.data.RideVehicle) { looks?.selectRide(v) }
    override fun setPlayerLook(look: String?) { if (coop?.refuseBattle() != true) looks?.select(look) }

    var canAutoRefill: () -> Boolean = { false }
    override val repelRefill get() = iosPreferences.repelRefill
    override val repelRefillSupported get() = canAutoRefill()
    override val repelRefillBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_AUTO_REFILL)
    override fun setRepelRefill(on: Boolean) { playback()?.autoRefill?.set(on) }

    private fun options() = playback()?.options
    override val followerSupported get() = playback()?.followerSupported == true
    override val followerReady get() = playback()?.follower?.ready == true
    override val followerBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_FOLLOWER)
    override fun setFollower(on: Boolean) { playback()?.follower?.set(on) }
    private fun flight() = playback()?.flight
    override val flyStyleSupported get() = flight()?.support() != null
    override val flightBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_FLIGHT)
    override val flyStyle get() = flight()?.style ?: iosPreferences.flyStyle
    override fun setFlyStyle(style: com.pokedaisy.app.companion.FlyStyle) { flight()?.setStyle(style) }
    override val flightArea get() = flight()?.areaMode ?: iosPreferences.flightArea
    override fun setFlightArea(mode: com.pokedaisy.app.companion.FlightAreaMode) { flight()?.setArea(mode) }
    override val flightVehicle get() = flight()?.vehicle ?: com.pokedaisy.app.companion.data.FlightVehicle.BIRD
    override fun setFlightVehicle(vehicle: com.pokedaisy.app.companion.data.FlightVehicle) { flight()?.setVehicle(vehicle) }
    override fun answerFlyStyle(style: com.pokedaisy.app.companion.FlyStyle?) { flight()?.answerStyle(style) }
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
    override val nuzlocke get() = com.pokedaisy.app.companion.Nuzlocke.on
    override val nuzlockeSupported get() = playback()?.nuzlockeSupported == true
    override val nuzlockeBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_NUZLOCKE)
    override fun setNuzlocke(on: Boolean) { com.pokedaisy.app.companion.Nuzlocke.setOn(on) }

    override val lowHpBeepSupported get() = playback()?.options?.lowHpBeepSupported == true
    override val lowHpBeepBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_LOW_HP_BEEP)
    override val fieldPoisonSupported get() = playback()?.options?.fieldPoisonSupported == true
    override val fieldPoisonBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_FIELD_POISON)
    override val battleRetry get() = iosPreferences.battleRetry
    override val battleRetryAsking get() = playback()?.battleRetry?.asking == true
    override val battleRetryBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_BOSS_RETRY)
    override fun setBattleRetry(on: Boolean) { playback()?.battleRetry?.set(on) }
    override val shinyAlert get() = iosPreferences.shinyAlert
    override val shinyHuntCount get() = playback()?.shinyAlert?.count ?: 0
    override val shinyAlertSupported get() = playback()?.shiny?.supported == true
    override val shinyAlertBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_SHINY_ALERT)
    override fun setShinyAlert(on: Boolean) { playback()?.shinyAlert?.set(on) }
    override val raIcons get() = iosPreferences.raIcons
    override fun setRaIcons(on: Boolean) {
        iosPreferences.raIcons = on
        com.pokedaisy.app.companion.ui.AchievementIcons.shown = on
    }

    override val shinyOdds get() = playback()?.shiny?.odds ?: com.pokedaisy.app.companion.data.ShinyOdds.NORMAL
    override val shinyOddsChoices get() = playback()?.shiny?.choices ?: com.pokedaisy.app.companion.data.ShinyOdds.CHOICES
    override val shinyOddsSupported get() = playback()?.shiny?.supported == true
    override val shinyOddsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_SHINY_ODDS)
    override fun setShinyOdds(odds: Int) { playback()?.shiny?.setOdds(odds) }
    override val dayNightSupported get() = playback()?.dayNightSupported == true
    override val dayNightBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_DAY_NIGHT)
    override val gamePickBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_PICKER)
    override val shownRows get() = SharedSetting.available(
        running = game != null, cheats = cheatHost()?.supported == true, gba = romCode != null,
    )

    override val cheats get() = cheatHost()?.list.orEmpty()
    override fun setCheatsEnabled(on: Boolean) {
        if (coop?.refuseCheats(on) == true) return
        stored.setCheatsEnabled(on)
        cheatHost()?.apply()
    }
    override val cheatsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_CHEATS)
    override val cheatsEditable get() = cheatHost()?.editable == true
    override fun setCheatEnabled(index: Int, on: Boolean) {
        if (coop?.refuseCheats(on) != true) cheatHost()?.setEnabled(index, on)
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
    override fun setRewind(on: Boolean) {
        stored.setRewind(on)
        releaseRewind()
        game?.rewindEnabled = rewind
    }
    var actions: com.pokedaisy.app.companion.GameActions? = null
    var rewindUnavailable: () -> Unit = {}
    fun showRewindDenied() {
        if (coop?.refuseLoad() == true) return
        if (!rewind) rewindUnavailable()
    }
    fun releaseRewind() {
        actions?.release(Hotkeys.Action.REWIND_HOLD)
        game?.revokeRewind()
    }
    override val autoRunBuiltIn get() = hasRunningGame && activeGame in com.pokedaisy.app.companion.data.GAMES_WITH_OWN_AUTO_RUN
    override val runIndoorsSupported get() = fieldReadable && com.pokedaisy.app.companion.data.GameField.supportsRunIndoors(romCode.orEmpty())
    override val wildEncountersSupported get() = fieldReadable && options()?.wildFlagFound == true ||
        playback()?.gameBoyFields?.available == true
    override val fieldOptionsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(
        if (playback()?.gameBoyFields?.available == true) com.pokedaisy.app.companion.NewFeatures.IOS_GB_ENCOUNTERS
        else com.pokedaisy.app.companion.NewFeatures.IOS_FIELD_OPTIONS)
    override val gameplayModifiersBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAMEPLAY_MODIFIERS)
    override val hmFreeSupported get() = options()?.hmFreeSupported == true
    override val expShareSupported get() = options()?.expShareSupported == true
    override val levelCapSupported get() = options()?.levelCapSupported == true
    override val levelCapLevel get() = options()?.levelCapNow?.level
    override val instantTextSupported get() = options()?.instantTextSupported == true
    override val reusableTmsSupported get() = options()?.reusableTmsSupported == true
    override val reusableTmsBuiltIn get() = hasRunningGame && activeGame in com.pokedaisy.app.companion.data.ReusableTms.BUILT_IN
    override val tradeEvolutionsSupported get() = tradeSupported()
    override val tradeEvolutionsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_TRADE_EVOLUTION)
    override val gameOptionsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_OPTIONS)

    override val touchRevision get() = IosTouchPreferences.revision
    override val touchStyle get() = IosTouchPreferences.style
    override fun setTouchStyle(style: TouchControlsStyle) { IosTouchPreferences.style = style }
    override val touchButtons get() = IosTouchPreferences.buttons
    override val touchSkins get() = IosTouchSkins.store.list()
    override val touchSkinsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_TOUCH_SKINS)
    private fun pauseForFileImport() {
        actions?.cancelTouch()
        game?.let { it.uiPaused = true; it.pause() }
    }
    private fun resumeAfterFileImport() {
        game?.takeIf { it.isRunning }?.let {
            it.uiPaused = false
            if (platform.UIKit.UIApplication.sharedApplication.applicationState == platform.UIKit.UIApplicationState.UIApplicationStateActive) it.resume()
        }
    }
    override val importTouchSkin: (((Pair<String, String>) -> Unit) -> Unit) get() = { done ->
        presentFrom()?.let { from ->
            IosTouchSkins.pick(from, before = ::pauseForFileImport, done = { notice ->
                resumeAfterFileImport()
                notice?.let(done)
            })
        }
    }
    override fun deleteTouchSkin(skin: com.pokedaisy.app.companion.InstalledSkin) = IosTouchSkins.delete(skin.id)
    override val touchXyBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_TOUCH_XY)
    override val touchBadge get() = touchSkinsBadge ?: touchXyBadge ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_SCREEN)
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

    override val coopEnabled get() = coop != null
    override val coopSettingsInLibrary get() = true
    override val coopState get() = IosCoopHost.session.link.state.value
    override val coopPairs get() = IosCoopHost.session.pairsList()
    override val coopLock get() = coop?.storyLock?.ui?.value
    override val coopLockActions get() = coop?.storyLock
    override val coopRelayOnly get() = IosCoopHost.relayOnly
    override val coopRelayValue get() = com.pokedaisy.app.companion.coop.CoopRelay.rowValue(
        iosPreferences.coopRelayUrl, IosCoopHost.defaultRelay,
    )
    override var coopNearbyRange: Int
        get() = iosPreferences.coopNearbyRange
        set(value) { iosPreferences.coopNearbyRange = value }

    override fun setCoopRelayOnly(on: Boolean) {
        IosCoopHost.session.changeRelay { iosPreferences.coopRelayOnly = on }
    }

    override fun coopUnpair(save: com.pokedaisy.app.companion.coop.CoopSaveId) {
        IosCoopHost.session.unpair(save)
    }

    override val gameName: String get() = if (game == null) "" else activeGame.displayName()
    override val romFileName: String get() = romPath.orEmpty().substringAfterLast('/')

    override fun closeGame() = onClose()
    override fun restartGame() = onRestart()

    override val lastBallButton get() = iosPreferences.lastBallButton
    override val lastBallBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_LAST_BALL)
    override fun setLastBallButton(on: Boolean) {
        val ball = playback()?.lastBall
        if (ball != null) ball.set(on) else iosPreferences.lastBallButton = on
    }

    override val battleButtonsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_BATTLE_BUTTONS)
    override val fieldHudBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_FIELD_HUD)
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
                com.pokedaisy.app.companion.PrefsCompanionSettings.Change.PICTURE -> gameScreen()?.refreshPicture()
            }
        }
        applyPlayback(); reloadBindings()
    }

    private fun applyPlayback() {
        val game = game ?: return
        val requested = com.pokedaisy.app.companion.PlaybackPolicy.requestedSpeed(ffActive, ffMaxSpeed, slowmoHeld, gameSpeed)
        game.speed = if (requested.isInfinite()) 0.0 else requested.toDouble()
        game.volume = gameAudioGain(gameVolume, gameMuted)
        game.muted = gameMuted
        val music = ffMusicMode
        game.muteAudioAtSpeed = { speed -> com.pokedaisy.app.companion.PlaybackPolicy.muteFastAudio(speed.toFloat(), music) }
        playbackChanged()
    }

    override fun setFfToggled(on: Boolean) { ffToggle = on; stored.setFfToggled(on) }
    override val gameSpeed: Float get() = if (romPath == null) 1f else d.floatForKey("game_speed:$romPath").takeIf { it in gameSpeeds } ?: 1f
    override fun setGameSpeed(v: Float) {
        if (romPath == null || v !in gameSpeeds) return
        d.setFloat(v, "game_speed:$romPath"); applyPlayback()
    }

    override val ffMusicModes get() = IOS_FF_MUSIC_MODES
    override val ffMusicMode get() = stored.ffMusicMode.takeIf { it in ffMusicModes } ?: FfMusicMode.SPED_UP
    override fun setFfMusicMode(mode: FfMusicMode) { if (mode in ffMusicModes) stored.setFfMusicMode(mode) }
    override val ffMusicBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_STEADY_MUSIC)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_FF_AUDIO)
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
    override val hotkeyActions get() = com.pokedaisy.app.companion.GameActions.BASIC_ACTIONS + listOf(com.pokedaisy.app.Hotkeys.Action.QUICK_MENU, com.pokedaisy.app.Hotkeys.Action.ZOOM, com.pokedaisy.app.Hotkeys.Action.FREE_FLIGHT)
    override val quickMenuSupportedActions get() = setOf(
        com.pokedaisy.app.companion.QuickAction.PC, com.pokedaisy.app.companion.QuickAction.FLY, com.pokedaisy.app.companion.QuickAction.FLIGHT,
        com.pokedaisy.app.companion.QuickAction.HEAL, com.pokedaisy.app.companion.QuickAction.FLASH,
        com.pokedaisy.app.companion.QuickAction.REPEL, com.pokedaisy.app.companion.QuickAction.WILD,
        com.pokedaisy.app.companion.QuickAction.BIKE, com.pokedaisy.app.companion.QuickAction.ROD,
        com.pokedaisy.app.companion.QuickAction.SAVE_STATE, com.pokedaisy.app.companion.QuickAction.LOAD_STATE,
        com.pokedaisy.app.companion.QuickAction.FF, com.pokedaisy.app.companion.QuickAction.COMPANION)
    override val quickMenuBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.REGISTERED_ITEMS)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GB_FIELD_ACTIONS)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GB_ENCOUNTERS)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_FLASH)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_HEAL)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_QUICK_MENU)
    override val hotkeysBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_HOTKEYS)
    // Preserve the old triggers as L/R until the player opts in to emulator shortcuts.
    override fun setHotkeysEnabled(on: Boolean) { stored.setHotkeysEnabled(on); reloadBindings() }
    override fun hotkeyBindings() = bindings.hotkeys()
    override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {
        bindings.setHotkey(action, keyNames, takeFrom)
    }
    override val clickSound get() = stored.clickSound
    override val clickSoundBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_CLICK_SOUND)
    override fun setClickSound(on: Boolean) = stored.setClickSound(on)
    override val statusBarBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_STATUS_BAR)
    override val performanceBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_PERFORMANCE)
    override val tipsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_TIPS)
    override val gameAspect get() = stored.gameAspect
    override fun setGameAspect(aspect: GameAspect) {
        if (aspect == GameAspect.WIDE && wideSupported) com.pokedaisy.app.companion.FeatureTips.used(com.pokedaisy.app.companion.TipId.EXPAND)
        stored.setGameAspect(aspect)
    }
    override val wideSupported get() = game?.wideSupported == true && playback()?.blocked != true
    override val pictureBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_EXPAND)
    var cameraRomKey = ""
    override val zoomSteps get() = game?.camera?.zoomFactors().orEmpty()
    override val zoomLevel get() = game?.camera?.level ?: 0
    override fun setZoomLevel(level: Int) = changeZoom(level)
    fun cycleZoom() = changeZoom()

    private fun changeZoom(step: Int? = null) {
        val game = game ?: return
        if (game.expanded) game.camera.changeExpandedZoom(iosPreferences, cameraRomKey, step)
    }

    override val lookAround get() = iosPreferences.lookAround
    override fun setLookAround(on: Boolean) { iosPreferences.lookAround = on; gameScreen()?.refreshPicture() }
    override val expandZoomOnText get() = iosPreferences.expandZoomOnText
    override fun setExpandZoomOnText(on: Boolean) { iosPreferences.expandZoomOnText = on; gameScreen()?.refreshPicture() }
    override val overlayChoices get() = IosOverlays.choices.map { (choice, label) -> choice.key to label }
    override val overlay get() = iosPreferences.overlay
    override val overlayRevision get() = IosOverlays.revision
    override val overlayBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_OVERLAY_IMPORTS)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_SCREEN_BORDERS)
    override fun setOverlay(key: String) {
        if (key.isNotEmpty() && com.pokedaisy.app.overlay.OverlayChoice.of(key) == com.pokedaisy.app.overlay.OverlayChoice.None) return
        iosPreferences.overlay = key
        gameScreen()?.refreshPicture()
    }
    override val importOverlay: (((com.pokedaisy.app.companion.OverlayImport.Completed) -> Unit) -> Unit) get() = { done ->
        presentFrom()?.let { from ->
            IosOverlays.pick(from, before = ::pauseForFileImport, done = { completed ->
                resumeAfterFileImport()
                gameScreen()?.refreshPicture()
                completed?.let(done)
            })
        }
    }
    override fun cancelOverlayImport() = IosOverlays.cancel()
    override fun deleteOverlay(id: String) { IosOverlays.delete(id).invokeOnCompletion { gameScreen()?.refreshPicture() } }
    override fun deleteAllOverlays() { IosOverlays.deleteAll().invokeOnCompletion { gameScreen()?.refreshPicture() } }
    override val shadersBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_SCREEN_EFFECTS)
    override val companionShadersBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_COMPANION_EFFECTS)
    override val smoothingBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_SCALEFX_SMOOTHING)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_XBR_SMOOTHING)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_SMOOTHING)
    override val smoothingModes get() = IosSmoothing.modes
    override val smoothingAvailable get() = smoothingModes.size > 1
    override val smoothing get() = stored.smoothing.takeIf { it in smoothingModes } ?: Smoothing.OFF
    override fun setSmoothing(mode: Smoothing) {
        if (mode in smoothingModes) stored.setSmoothing(mode)
    }
}
