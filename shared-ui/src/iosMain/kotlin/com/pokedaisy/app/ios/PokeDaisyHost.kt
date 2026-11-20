@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlin.experimental.ExperimentalNativeApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.data.FfMenuWatch
import com.pokedaisy.app.companion.data.GameField

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.pokedaisy.app.companion.GamePlayerLook
import com.pokedaisy.app.companion.GameRide
import com.pokedaisy.app.companion.PlayerLookSession
import com.pokedaisy.app.companion.data.AppearanceAssets
import com.pokedaisy.app.companion.FeatureTips
import com.pokedaisy.app.companion.TipId
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.StateSlotController
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.StateResume
import com.pokedaisy.app.companion.data.SnapshotCache
import com.pokedaisy.app.companion.data.hasData
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.RunningGameStatusBar
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.data.Gen1Art
import com.pokedaisy.app.companion.data.Gen2Art
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.ui.CompanionScreen
import com.pokedaisy.app.companion.ui.theme.AppTheme
import com.pokedaisy.app.game.GameViewController
import com.pokedaisy.app.game.RomPicker
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSFileManager
import platform.UIKit.UIViewController

/** How often the companion reads the running game (Android's sampler runs about this often too). */
private const val SAMPLE_MILLIS = 500L

/** A save larger than this isn't one (a GBA flash chip is 128 KB; a Game Boy cart's RAM less). */
private const val MAX_SAVE_BYTES = com.pokedaisy.app.GameSaves.MAX_BYTES

/**
 * What the iPad app's Swift side calls (as `PokeDaisyHost.shared` in Swift; not `PokeDaisy`, which is the app's own
 * Swift module): [start] once at launch, [gameScreenViewController] for the game and companion, [games] /
 * [addGames] for the games added from Files, [closeGame] to close one. While a game runs,
 * [telemetry] reads it every half second, off the main thread (the core's reads may come from any thread), and the
 * companion follows it.
 */
object PokeDaisyHost {
    /** The companion's view of the running game; "starting…" until a game feeds it. */
    val telemetry = TelemetryStore()

    /** Swift's: CLOSE GAME / RESTART GAME in the companion's SETTINGS (the game's place is SwiftUI's to change). */
    var onCloseRequested: () -> Unit = {}
    var onRestartRequested: () -> Unit = {}

    // The running game's SETTINGS; null = NO GAME RUNNING.
    private var settings by mutableStateOf<IosCompanionSettings?>(null)
    private var battle by mutableStateOf<com.pokedaisy.app.companion.GameBattleInput?>(null)
    private var slots by mutableStateOf<StateSlotController?>(null)
    private var stateError by mutableStateOf<String?>(null)
    private var restartFresh = false
    private var started = false
    private var game: GameViewController? = null
    private val touch = IosTouchControls({ game }, { settings })
    private var screen: IosGameScreen? = null
    private var playback: com.pokedaisy.app.companion.GameRuntime? = null
    private var items by mutableStateOf<com.pokedaisy.app.companion.ItemUse?>(null)
    private var evolution: com.pokedaisy.app.companion.FieldTradeEvolution? = null
    private var flash: com.pokedaisy.app.companion.FieldFlashAction? = null
    private var fly by mutableStateOf<com.pokedaisy.app.companion.FlyAction?>(null)

    fun installCoopCrypto(primitives: IosCoopPrimitives) = IosCoopCrypto.install(primitives)

    fun receiveCoopInvite(link: String) = IosCoopHost.receiveInvite(link)

    fun installCoopNetwork(tcp: IosCoopTcpNative, discovery: IosCoopDiscovery) = IosCoopHost.install(tcp, discovery)

    /** Sets the shared screens' hosts (fonts, pictures, settings, the manual). Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        IosHosts.install(appFilesDir())
        IosTouchPreferences.changed = touch::releaseKeys
        IosTouchPreferences.install()
        IosAchievements.start()
        com.pokedaisy.app.companion.ui.AchievementIcons.shown = iosPreferences.raIcons
    }

    /** One Compose host for the native Metal game surface, shared controls and companion. */
    fun gameScreenViewController(romPath: String, onFailed: () -> Unit): UIViewController {
        start()
        val current = createGameController(romPath, onFailed)
        val gameBoy = romPath.substringAfterLast('.').lowercase() in listOf("gb", "gbc")
        val panels = IosGameScreen(game, touch, gameBoy).also { screen = it }
        val currentSettings = settings
        val currentSlots = slots
        val currentCoop = coopGame
        panels.coopKey = { currentCoop?.key(it) == true }
        val currentRuntime = playback
        val title = iosRomTitle(romPath)
        val statusBar: @Composable () -> Unit = {
            val snapshot by telemetry.snapshot.collectAsState()
            RunningGameStatusBar(title, snapshot, FoundationDates::shortTime)
        }
        var quickActions: com.pokedaisy.app.companion.ui.GameQuickActions? = null
        val controller = current as? GameViewController
        if (controller != null && currentSettings != null && currentSlots != null && currentRuntime != null) {
            currentSettings.rewindUnavailable = panels::showRewindUnavailable
            val shortcuts = currentRuntime.bindActions(currentSettings, currentSlots,
                selectSlot = { currentSlots.select(it) }, companion = panels::toggleCompanion,
                rewindHold = controller.rewindHold, canRewind = { controller.canRewind },
                rewindDenied = currentSettings::showRewindDenied,
                hold = { action, down ->
                    when (action) {
                        com.pokedaisy.app.Hotkeys.Action.FF_HOLD -> currentSettings.holdFastForward(down)
                        com.pokedaisy.app.Hotkeys.Action.SLOWMO_HOLD -> currentSettings.holdSlowmo(down)
                        com.pokedaisy.app.Hotkeys.Action.REWIND_HOLD -> controller.applyRewindHold()
                        else -> Unit
                    }
                })
            currentSettings.actions = shortcuts
            touch.actions = shortcuts
            panels.canOpenQuickMenu = {
                game === current && game?.isRunning == true && !currentRuntime.blocked && currentCoop?.overlay?.takesKeys != true
            }
            quickActions = com.pokedaisy.app.companion.ui.GameQuickActions(
                panels.quickMenu, iosPreferences, currentSettings, { telemetry.snapshot.value }, { items }, { fly },
                shortcuts::hotkey, panels::showQuickNotice,
                active = { game === current && game?.isRunning == true && !currentRuntime.blocked },
                registeredItems = { currentRuntime.registeredItems }, flash = { flash },
                extra = object : com.pokedaisy.app.companion.ui.QuickActions {
                    override fun offered(action: com.pokedaisy.app.companion.QuickAction) =
                        action == com.pokedaisy.app.companion.QuickAction.COMPANION && !panels.portrait ||
                            action == com.pokedaisy.app.companion.QuickAction.FLIGHT && fly?.canFreeFlight() == true
                    override fun run(action: com.pokedaisy.app.companion.QuickAction) {
                        if (action == com.pokedaisy.app.companion.QuickAction.COMPANION) panels.toggleCompanion()
                        if (action == com.pokedaisy.app.companion.QuickAction.FLIGHT) fly?.toggleFlight { result ->
                            if (result != null && result != com.pokedaisy.app.companion.data.FlyResult.OK)
                                panels.showQuickNotice(com.pokedaisy.app.companion.ui.flyMessage(result))
                        }
                    }
                    override fun riding() = currentRuntime.riding
                    override fun flying() = currentRuntime.flight?.state?.let { it != 0 } == true
                })
            game?.onHotkey = {
                if (it.action == com.pokedaisy.app.Hotkeys.Action.QUICK_MENU) panels.triggerQuickMenu(it.pressed)
                else if (!it.pressed || !panels.quickMenu.open) {
                    if (it.action == com.pokedaisy.app.Hotkeys.Action.ZOOM && it.pressed) currentSettings.cycleZoom()
                    else if (it.action == com.pokedaisy.app.Hotkeys.Action.FREE_FLIGHT && it.pressed)
                        quickActions?.run(com.pokedaisy.app.companion.QuickAction.FLIGHT)
                    else shortcuts.hotkey(it)
                }
            }
        }
        val tips = if (current is GameViewController && currentRuntime != null && currentSettings != null) {
            quickActions?.let { IosGameTips(current, currentRuntime, currentSettings, panels,
                { telemetry.snapshot.value }, { items }, { fly }, it) }
        } else null
        return ComposeUIViewController(configure = { configureUiAutomation() }) {
            AppTheme { CompositionLocalProvider(com.pokedaisy.app.companion.ui.LocalClickSound provides ::playGameClick) {
                tips?.Clock()
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    panels.Content(
                        gameContent = { UIKitViewController(factory = { current }, modifier = Modifier.fillMaxSize(),
                            properties = UIKitInteropProperties(interactionMode = null)) },
                        companion = { Companion(panels, statusBar.takeIf { currentSettings?.statusBar == true && currentSettings.statusBarOnCompanion }) },
                        statusBar = statusBar.takeIf { currentSettings?.statusBar == true && !currentSettings.statusBarOnCompanion },
                        gameOverlay = {
                            val snapshot by telemetry.snapshot.collectAsState()
                            currentSettings?.let { hudSettings ->
                                val hudShown = com.pokedaisy.app.companion.ui.fieldHudShown(
                                    hudSettings.fieldHud, panels.portrait, currentRuntime?.settledOnField == true, snapshot) &&
                                    !panels.state.side.windowOpen
                                com.pokedaisy.app.companion.ui.FieldHud(snapshot, hudShown, hudSettings.fieldHudStyle,
                                    itemUse = items, fly = fly, frame = panels.hudFrame(), avoid = panels.hudAvoid(),
                                    closePanels = panels.quickMenu.visit.toInt(),
                                    openPanel = com.pokedaisy.app.companion.ui.FieldHudPanel.SUMMARY to 0,
                                    openTick = tips?.summaryRequest ?: 0,
                                    onTap = { FeatureTips.used(TipId.HUD) },
                                    onInputCapture = { panels.captureOverlay(IosGameScreen.Overlay.FIELD_HUD, it) },
                                    onOpenCompanion = if (!panels.portrait && (!panels.state.side.shown || panels.state.side.isWindow))
                                        ({ panels.toggleCompanion() }) else null)
                            }
                            if (current is GameViewController && currentSettings?.showPerformance == true) {
                                Box(Modifier.fillMaxSize()) {
                                    val picture = panels.hudFrame()
                                    val inset = with(LocalDensity.current) { Modifier.padding(
                                        start = (picture?.left ?: 0f).coerceAtLeast(0f).toDp(),
                                        top = (picture?.top ?: 0f).coerceAtLeast(0f).toDp(),
                                    ) }
                                    IosPerformanceOverlay(current, telemetry, inset)
                                }
                            }
                            val battleRead = com.pokedaisy.app.companion.ui.rememberBattleButtonsRead(snapshot)
                            val buttons = com.pokedaisy.app.companion.battleButtonsWanted(
                                currentSettings?.battleButtons == true, true, panels.portrait, snapshot) &&
                                battleRead && !panels.state.side.windowOpen && !panels.quickMenu.open
                            val clear = panels.hudClear()
                            com.pokedaisy.app.companion.ui.BattleButtons(snapshot, buttons, battle,
                                currentSettings?.showHints == true, core = panels.coreFrame(),
                                clearTop = clear.first, clearBottom = clear.second, avoid = panels.hudAvoid(),
                                allowPictureOverlap = true,
                                onWindow = { panels.captureOverlay(IosGameScreen.Overlay.BATTLE, it) })
                            quickActions?.let { actions ->
                                com.pokedaisy.app.companion.ui.QuickMenu(panels.quickMenu, snapshot, actions,
                                    currentSettings!!.quickMenuActions, iosPreferences.fieldHudStyle.look)
                            }
                            tips?.Card()
                            com.pokedaisy.app.companion.ui.FlyStyleAskHost(
                                active = true, roomy = panels.portrait,
                                onAnswer = { currentRuntime?.flight?.answerStyle(it) })
                            currentCoop?.let { coop ->
                                com.pokedaisy.app.companion.ui.CoopGameOverlays(
                                    coop.overlay, coop.storyLock, coop.partnerName, proposalOnGameScreen = true,
                                    closeGame = ::finishClose, showNotice = coop.overlay::showNotice,
                                    captureInput = { panels.captureOverlay(IosGameScreen.Overlay.COOP, it) },
                                )
                            }
                            val asking = currentSettings?.battleRetryAsking == true
                            DisposableEffect(asking) {
                                panels.captureOverlay(IosGameScreen.Overlay.BATTLE_RETRY, asking)
                                onDispose { panels.captureOverlay(IosGameScreen.Overlay.BATTLE_RETRY, false) }
                            }
                            com.pokedaisy.app.companion.ui.BattleRetryAskHost(
                                asking = asking, active = true, roomy = panels.portrait,
                                modifier = Modifier.padding(top = with(LocalDensity.current) {
                                    maxOf(48.dp, clear.first.toDp()) + 4.dp
                                }),
                                onAnswer = { retry ->
                                    if (currentRuntime?.battleRetry?.answer(retry) == true) currentSlots?.loadRetry()
                                })
                        })
                    touch.EditorOverlay()
                }
            } }
        }
    }

    @Composable
    private fun Companion(panels: IosGameScreen, statusBar: (@Composable () -> Unit)?) {
        val snapshot by telemetry.snapshot.collectAsState()
        Box(Modifier.fillMaxSize()) {
            CompanionScreen(snapshot, slots = slots, settings = settings, battleInput = battle, gamePick = telemetry.gamePick,
                back = panels.back, keys = panels.keys, clickSound = ::playGameClick, achievements = IosAchievements.session, statusBar = statusBar, itemUse = items, fly = fly)
            stateError?.let { message ->
                OptionConfirm(tr("SAVE STATES"), message, tr("OK"), rememberGbaTextMetrics(),
                    onConfirm = { stateError = null }, onDismiss = { stateError = null }, cancelLabel = null)
            }
        }
    }

    /** The games added from Files (their paths in the app's Documents/roms), by name. */
    fun games(): List<String> {
        val dir = RomPicker.romsDir
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null)?.filterIsInstance<String>().orEmpty()
        return names.filter { it.substringAfterLast('.', "").lowercase() in RomPicker.EXTENSIONS }
            .sortedBy { it.lowercase() }
            .map { "$dir/$it" }
    }

    private fun appearanceLibrary() = games().mapNotNull { path ->
        runCatching { AppearanceAssets.file(path.toPath()) }.getOrNull()
    }

    /** Files' picker over [from]; [onAdded] gets the games it copied in (none if cancelled). */
    fun addGames(from: UIViewController, onAdded: (List<String>) -> Unit) = RomPicker.pick(from, onAdded)

    /**
     * LOAD SAVE (the Android library's, for the iPad's game list): the save picked in Files at [fromPath] becomes
     * [romPath]'s battery save, the one before kept beside it as `<name>.backup-<yyyyMMdd-HHmmss>.sav`. The game must
     * not be running. Null when it worked, else what went wrong, for the player.
     */
    fun loadSave(romPath: String, fromPath: String): String? {
        val fs = FileSystem.SYSTEM
        val source = fromPath.toPath()
        val size = fs.metadataOrNull(source)?.size ?: return "Can't read that file"
        if (size !in 1L..MAX_SAVE_BYTES.toLong()) return "That isn't a game save"
        val bytes = runCatching { com.pokedaisy.app.GameSaves.readPicked(fs.source(source)) }
            .getOrNull() ?: return "Can't read that file"
        return loadSave(romPath, bytes)
    }

    /** The shared import dialog retains a bounded copy until the player confirms. */
    fun loadSave(romPath: String, bytes: ByteArray): String? {
        if (!recoverIosSaveTrades()) return iosSaveTradeRecoveryError()
        val current = RomPicker.savePathFor(romPath).toPath()
        return when (val result = com.pokedaisy.app.GameSaves.load(current, bytes,
            states = { iosStateFiles(romPath.toPath()) },
            beforeReplace = { StateFiles.backupBattery(current) })) {
            is com.pokedaisy.app.GameSaves.Result.Loaded -> null
            is com.pokedaisy.app.GameSaves.Result.Failed -> result.reason
        }
    }

    /** The game at [romPath] running, its battery save beside the others; closes the one before. [onFailed] hears a
     * game that won't open. */
    private fun createGameController(romPath: String, onFailed: () -> Unit): UIViewController {
        val fresh = restartFresh
        restartFresh = false
        closeGame()
        telemetry.reset()
        val savePath = RomPicker.savePathFor(romPath)
        val files = runCatching {
            check(recoverIosSaveTrades())
            StateFiles.backupBattery(savePath.toPath())
            iosStateFiles(romPath.toPath()).also {
                if (fresh) StateResume.startFresh(it)
            }
        }.getOrElse { onFailed(); return UIViewController() }
        val g = GameViewController(romPath, savePath, onFailed)
        game = g
        val lookRenderer = GamePlayerLook(PkApiCore)
        val ride = GameRide(PkApiCore)
        val currentLook = PlayerLookSession(iosPreferences, appFilesDir(), lookRenderer::select).also { playerLook = it }
        val currentCoop = IosCoopGame(romPath.toPath(), savePath.toPath(), appFilesDir(), currentLook) { g.frameGate = it }
            .also { coopGame = it }
        g.onForeground = currentCoop::resumeGame
        g.rewindAllowed = { !currentCoop.blocksLoad }
        val defaults = platform.Foundation.NSUserDefaults.standardUserDefaults
        var currentRuntime: com.pokedaisy.app.companion.GameRuntime? = null
        val stateSnapshot = { telemetry.forState()?.takeIf { it.hasData && !it.unsupported }?.let(SnapshotCache::encode) }
        val ra = IosAchievements.session
        var restoredProgress: ByteArray? = null
        var flewAnywhere = false
        fun stateLoaded(runtime: com.pokedaisy.app.companion.GameRuntime, state: Path, applyProgress: (ByteArray?) -> Unit) {
            runtime.afterStateLoad(
                restoreProgress = { applyProgress(StateFiles.readAchievementProgress(state)) },
                settleAppearance = { if (currentLook.supported) lookRenderer.settle() },
                reinstallStoryLock = { currentCoop.storyLock?.requestReinstall() },
                companionState = state,
            )
        }
        val resume = StateResume(files,
            write = IosAchievements::writeState, read = { PkApiCore.loadState(it.toString()) },
            matchesSave = { PkApiCore.stateMatchesSave(it.toString()) },
            enabled = { iosPreferences.autoResume },
            snapshot = stateSnapshot, loaded = { state ->
                // The startup state supplies progress to the achievement session once the ROM attaches.
                currentRuntime?.let { runtime -> stateLoaded(runtime, state) { restoredProgress = it } }
            })
        val (code, revision) = (if (romPath.lowercase().endsWith(".gba"))
            com.pokedaisy.app.companion.data.RomIdentityReader.forPath(romPath.toPath()).header() else null) ?: (null to 0)
        g.beforeSuspend = {
            FeatureTips.scheduler?.save()
            val saved = currentCoop.saveForSuspend { g.withState { resume.save() } }
            if (!saved) resumeFailed()
            saved
        }
        settings = IosCompanionSettings(romPath, code, g,
            onClose = { if (game === g) finishClose() },
            onRestart = {
                if (!currentCoop.refuseBattle() && game === g && prepareClose()) { restartFresh = true; onRestartRequested() }
            },
            onDisableResume = {
                runCatching { resume.startFresh(); true }.getOrElse { resumeFailed(); false }
            }, onEditTouch = { if (game === g) touch.edit() }, readTouchStage = { touch.readStage().takeIf { game === g } }, gameScreen = { screen.takeIf { game === g } }, playback = { currentRuntime },
            looks = currentLook, tradeSupported = { game === g && evolution?.supported() == true }, coop = currentCoop)
            .also { s ->
                L10n.apply(s.appLanguage, code)
                g.rewindEnabled = s.rewind
            }
        battle = com.pokedaisy.app.companion.GameBattleInput(telemetry, code, revision,
            changed = { sample() }, silent = { g.audioSilent },
            caller = { fn, a0, a1, a2, a3 ->
                if (PkApiCore.callSafe()) PkApiCore.call(fn, a0, a1, a2, a3) else com.pokedaisy.app.BattleInputController.CALL_NOT_NOW
            }, ballWriter = PkApiCore::write).also { b ->
            g.frameKeys = b::keys
            g.holdPicture = { b.holdPicture }
            g.quietPress = { b.quietPress }
        }
        var fieldTablesCrc = 0L
        playback = com.pokedaisy.app.companion.GameRuntime(
            com.pokedaisy.app.game.GameSession(PkApiCore, PkApiCore::callSafe) { where, error -> com.pokedaisy.app.companion.data.Logs.i("$where: $error") },
            telemetry, iosPreferences, battle!!, PkApiCore, { code != null }, { PkApiCore.configureField(fieldTablesCrc) }, { PkApiCore.configureField(0) },
            applyTint = PkApiCore::setTint).also { p ->
            currentRuntime = p
            p.bindShiny(PkApiCore, kotlin.native.Platform.isDebugBinary)
            val currentSettings = settings!!
            currentSettings.playbackChanged = { p.settingsChanged(currentSettings, currentSettings.ffActive, currentSettings.slowmoHeld) }
            p.settingsChanged(currentSettings, currentSettings.ffActive, currentSettings.slowmoHeld)
            g.frameSpeed = {
                val speed = p.speed().let { if (it.isInfinite()) 0.0 else it.toDouble() }
                if (currentCoop.battleActive) 1.0
                else if (p.flight?.scenicAirborne == true && speed != 0.0) maxOf(speed, 2.0) else speed
            }
            val bKeys = battle!!::keys
            val gameOptions = p.options.also { o ->
                currentSettings.optionsChanged = { o.settingsChanged(currentSettings) }
                o.settingsChanged(currentSettings)
                p.readField = { o.fieldState(PkApiCore::stepField, com.pokedaisy.app.companion.data.GameField.supportsRunIndoors(code.orEmpty())) }
                g.frameKeys = { held ->
                    val builtIn = com.pokedaisy.app.companion.data.activeGame in com.pokedaisy.app.companion.data.GAMES_WITH_OWN_AUTO_RUN
                    p.tipMoments.movement(held, p.fieldState, p.onField, o.autoRun || builtIn)
                    bKeys(o.keys(held, p.fieldState, p.onField, builtIn))
                }
            }
            g.beforeFrame = { work ->
                p.beforeFrame(work) {
                    p.serviceFramePatches(currentCoop.battleActive, { lookRenderer.service() }) {
                        currentCoop.storyLock?.requestReinstall()
                    }
                }
                if (work == com.pokedaisy.app.game.GameSession.FrameWork.ADVANCING) {
                    g.camera.flying = p.flight?.state?.let { it != 0 } == true
                    g.camera.flightZoom = p.flight?.scenicAirborne == true
                    p.gameBoyFields?.keepEncounters(PkApiCore, p.options.wildEncounters == 0)
                }
            }
            g.afterFrame = {
                p.flight?.afterFrame()
                p.tick(wideScene = if (g.expanded) PkApiCore.wideScene else -1)
                steadyMusic?.frame(currentSettings.ffMusicMode, p.requestedSpeedNow() > 1f, currentSettings.clickSound)
                // Native flags identify a clear field even while the callback watcher is learning it.
                val gameOwnsScreen = p.fieldState and (GameField.LOCKED or GameField.BATTLE or GameField.WINDOW) != 0
                val field = !p.session.mapOpen && !gameOwnsScreen &&
                    (p.fieldState and GameField.KNOWN != 0 || p.onField && !FfMenuWatch.battling)
                if (g.playingForward) currentCoop.afterFrame(field)
                if (field) lookRenderer.fieldFrame()
                ride.step(p.riding && !p.session.mapOpen && !gameOwnsScreen, lookRenderer.ride)
                gameOptions.markExp(PkApiCore::read, PkApiCore::write)
            }
        }
        val p = playback!!
        val fields = com.pokedaisy.app.companion.data.FieldItemSupportCache()
        fun live() = game === g && g.isRunning && code != null && !p.blocked && telemetry.snapshot.value.blocked == null
        val readSupport = {
            val snapshot = telemetry.snapshot.value
            if (live()) fields.get(snapshot, code.orEmpty(), revision, telemetry.knownFieldItems()) else null
        }
        p.bindLastBall({ readSupport()?.fns?.removeBagItem ?: 0L }) {
            if (screen?.state?.side?.windowOpen == true) screen?.state?.hide()
        }
        val currentSession = { p.session.takeIf { live() } }
        settings?.canAutoRefill = { readSupport()?.let { fields -> fields.fns.repelItems.any(fields::canUse) } == true }

        val gbFields = p.bindGameBoyFields(
            enabled = { game === g && g.isRunning && code == null && !p.blocked && telemetry.snapshot.value.blocked == null },
            rom = PkApiCore::rom, deliver = { it() }, handedOver = {
                if (screen?.state?.side?.windowOpen == true) screen?.state?.hide()
            })
        p.bindRegisteredItems(readSupport, g::setPress) {
            if (live()) screen?.showQuickNotice(com.pokedaisy.app.companion.ui.itemUseMessage(
                com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW)))
        }
        val verifiedTms = com.pokedaisy.app.companion.FieldTmSupport(PkApiCore::read)
        val sharedItems = com.pokedaisy.app.companion.FieldItemUse(
            support = readSupport, session = currentSession,
            inBattle = { telemetry.snapshot.value.inBattle },
            centerMetadata = { if (live()) p.options.discovery.center else null },
            tmMetadata = { if (live()) verifiedTms.get(telemetry.snapshot.value, code, revision) else null },
            handedOver = { if (screen?.state?.side?.windowOpen == true) screen?.state?.hide() },
            deliver = { result -> result(); if (live()) sample() },
        )
        val flashSupport = com.pokedaisy.app.companion.FieldFlashSupport(PkApiCore::read)
        flash = com.pokedaisy.app.companion.FieldFlashAction(sharedItems,
            { if (readSupport()?.fns?.party == null) null else flashSupport.get(telemetry.snapshot.value, code, revision) },
            { telemetry.snapshot.value }, PkApiCore::read) {
            if (screen?.state?.side?.windowOpen == true) screen?.state?.hide()
        }
        val classicFly = com.pokedaisy.app.companion.ClassicFlyAction(sharedItems, readSupport, currentSession) {
            if (screen?.state?.side?.windowOpen == true) screen?.state?.hide()
        }
        val sharedFly = com.pokedaisy.app.companion.GameFlyAction(
            gameBoy = { gbFields }, classic = classicFly, gbaAvailable = { readSupport()?.canFly == true },
            flight = { p.flight },
            flyUsed = { if (game === g) FeatureTips.used(TipId.FLY) },
        )
        fly = object : com.pokedaisy.app.companion.FlyAction by sharedFly {
            override val actionBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(
                if (gbFields.available) com.pokedaisy.app.companion.NewFeatures.IOS_GB_FIELD_ACTIONS
                else if (canFreeFlight()) com.pokedaisy.app.companion.NewFeatures.IOS_FLIGHT
                else com.pokedaisy.app.companion.NewFeatures.IOS_CLASSIC_FLY)
            override fun canFreeFlight() = p.flight?.support() != null
            override fun flying() = p.flight?.state?.let { it != 0 } == true
            override fun toggleFlight(done: (com.pokedaisy.app.companion.data.FlyResult?) -> Unit) {
                p.flight?.toggle { result, notOnFoot ->
                    if (notOnFoot) screen?.showQuickNotice(tr("You can only take off from land."))
                    else done(result?.takeUnless { it == com.pokedaisy.app.companion.data.FlyResult.OK })
                }
            }
        }
        val partyActions = com.pokedaisy.app.companion.FieldPartyActions(
            sharedItems, currentSession, { telemetry.snapshot.value }, telemetry::knownPartyReorderConfig,
            { p.fieldState }, { result -> result(); if (live()) sample() },
        )
        val tradeSupport = com.pokedaisy.app.companion.FieldTradeSupport(PkApiCore::read)
        val trades = com.pokedaisy.app.companion.FieldTradeEvolution(
            partyActions, sharedItems,
            { if (live()) tradeSupport.get(telemetry.snapshot.value, code, revision) else null },
            { settings?.tradeEvolutions == true }, { p.onField },
            { if (screen?.state?.side?.windowOpen == true) screen?.state?.hide() },
        )
        evolution = trades
        val sharedPc = com.pokedaisy.app.companion.GamePcAction(
            gameBoy = { gbFields }, gba = sharedItems,
            handedOver = { if (game === g && screen?.state?.side?.windowOpen == true) screen?.state?.hide() },
            pcUsed = { if (game === g) FeatureTips.used(TipId.PC) },
        )
        items = object : com.pokedaisy.app.companion.ItemUse by trades {
            override val evolveBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_TRADE_EVOLUTION)
            override val partyBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_PARTY_STORAGE)
            override val relearnBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_MOVE_SCREENS)
            override fun useBadge(itemId: Int) = relearnBadge.takeIf { sharedItems.canTeach(itemId) }
            override val centerBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_PARTY_SERVICES)
            override val actionBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(
                if (gbFields.available) com.pokedaisy.app.companion.NewFeatures.IOS_GB_FIELD_ACTIONS
                else com.pokedaisy.app.companion.NewFeatures.IOS_FIELD_ITEMS)
            override fun canPc() = sharedPc.available()
            override fun openPc(done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) = sharedPc.open(done)
        }
        val currentSettings = settings!!
        val currentCheats = p.bindCheats(
            supported = romPath.substringAfterLast('.').lowercase() == "gba",
            openStore = { com.pokedaisy.app.cheats.CheatStore.forCrc(appFilesDir(),
                com.pokedaisy.app.companion.data.romCrc32(romPath.toPath()).toString(16).padStart(8, '0')) },
            enabled = { currentSettings.cheatsEnabled },
            submit = { text ->
                if (g.isRunning) {
                    val applied = PkApiCore.applyCheats(if (currentCoop.engaged) "" else text)
                    if (applied >= 0) ra.setCheatsActive(applied > 0)
                    else com.pokedaisy.app.companion.data.Logs.i("cheats didn't load")
                }
            })
        val currentBattle = battle!!
        g.beforeRewind = { p.battleRetry.reset(); p.cancelFieldActions(); currentBattle.cancel() }
        val currentOptions = p.options
        var lookScanStarted = false
        g.onCoreStarted = {
            if (game === g) {
                val crc = runCatching { com.pokedaisy.app.companion.data.romCrc32(romPath.toPath()) }.getOrDefault(0L)
                val key = crc.toString(16).padStart(8, '0')
                val blocked = com.pokedaisy.app.companion.data.BlockedGames.forCrc(key) != null
                val tables = if (blocked) 0L else com.pokedaisy.app.companion.data.WideTables(
                    { PkApiCore.wideKnows(it.toLong() and 0xFFFFFFFFL) },
                    { PkApiCore.fieldKnows(it.toLong() and 0xFFFFFFFFL) },
                    { own, base, row -> PkApiCore.addWideDerived(own.toLong() and 0xFFFFFFFFL, base.toLong() and 0xFFFFFFFFL, row) },
                    log = { com.pokedaisy.app.companion.data.Logs.i(it) },
                ).resolve(romPath.toPath(), key).toLongOrNull(16) ?: 0L
                fieldTablesCrc = tables
                currentSettings.cameraRomKey = key
                g.camera.level = iosPreferences.zoomLevelFor(key)
                g.setExpandedTables(tables)
                p.start(romPath.toPath(), code, crc, areaMapCrc = tables)
                if (!blocked) {
                    val click = IosGameClickSound(g, key).also { gameClick = it }
                    steadyMusic = IosSteadyMusic(g, romPath.toPath(), key, code != null, click).also {
                        g.onMusicPause = it::pause
                    }
                }
                ride.reset()
                if (code != null && !blocked) {
                    if (!lookScanStarted) {
                        lookScanStarted = true
                        currentLook.load(romPath.toPath(), code, key, library = ::appearanceLibrary)
                    }
                    if (currentLook.supported) lookRenderer.service(force = true)
                }
                if (code != null) p.bindFollower(PkApiCore, appFilesDir()) { current ->
                    AppearanceAssets(appFilesDir()).prepareFollowers(
                        AppearanceAssets.file(romPath.toPath()), ::appearanceLibrary, current,
                    )
                }
                p.bindFlight {
                    com.pokedaisy.app.companion.GameFlight(p.session, PkApiCore, iosPreferences, readSupport,
                        kind = { telemetry.snapshot.value.game }, retail = telemetry::retailRom,
                        inBattle = { telemetry.snapshot.value.inBattle }, standing = { p.settledOnField && p.fieldState and com.pokedaisy.app.companion.data.GameField.LOCKED == 0 },
                        riding = { p.riding },
                        bike = { com.pokedaisy.app.companion.data.KeyItems.firstIn(telemetry.snapshot.value.items,
                            com.pokedaisy.app.companion.data.KeyItems.BIKES) },
                        useItem = { p.registeredItems?.use(it) },
                        keeper = com.pokedaisy.app.companion.data.FlightArea.Keeper(appFilesDir(), key),
                        deliver = { it() }, tookOff = { scenic ->
                            if (!scenic && game === g) FeatureTips.used(TipId.FLY)
                            if (currentSettings.flightArea != com.pokedaisy.app.companion.FlightAreaMode.SEEN) {
                                flewAnywhere = true
                                ra.setFlightAnywhere(true)
                            }
                            if (screen?.state?.side?.windowOpen == true) screen?.state?.hide()
                        }, turned = { screen?.showQuickNotice(tr("You haven't been there yet.")) },
                        askStyle = { com.pokedaisy.app.companion.ui.FlyStyleAsk.showing = true })
                }
                currentOptions.start(romPath.toPath(), PkApiCore::configureWild,
                    ready = { currentOptions.settingsChanged(currentSettings) })
                currentCheats.start()
                g.withState { resume.restore() }
                currentCoop.start(code, revision) { currentCheats.hasEnabledCheats }
                ra.setShinyOddsActive(p.shiny?.active == true)
                ra.setExpShareActive(currentOptions.expShareActive)
                ra.setFlightAnywhere(false)
                IosAchievements.attach(romPath.toPath(), restoredProgress, track = !blocked)
                restoredProgress = null
            }
        }
        g.beforeStateChange = { p.cancelFieldActions(); currentSettings.releaseRewind(); currentBattle.cancel(); touch.releaseKeys() }
        slots = p.bindStates { StateSlotController(files, onCore = g::withState,
            write = IosAchievements::writeState, read = { PkApiCore.loadState(it.toString()) },
            allowed = { action ->
                when (action) {
                    com.pokedaisy.app.Hotkeys.Action.LOAD_STATE, com.pokedaisy.app.Hotkeys.Action.UNDO_LOAD -> !currentCoop.refuseLoad()
                    else -> !currentCoop.refuseBattle()
                }
            },
            beforeLoad = { StateFiles.backupBattery(savePath.toPath()) },
            thumbnail = { PkApiCore.stateThumbnail() },
            snapshot = stateSnapshot, loaded = { state -> stateLoaded(p, state, ra::loadProgress) },
            failed = { stateError = it },
            initialIndex = defaults.integerForKey("state_slot").toInt(),
            selected = { defaults.setInteger(it.toLong(), "state_slot") }) }
        val previousAfterFrame = g.afterFrame
        g.afterFrame = {
            previousAfterFrame()
            ra.onFrame(g.completedFrameWork) {
                ra.setShinyOddsActive(p.shiny?.active == true)
                ra.setExpShareActive(currentOptions.expShareActive)
                if (flewAnywhere && currentSettings.flightArea == com.pokedaisy.app.companion.FlightAreaMode.SEEN) {
                    flewAnywhere = false
                    ra.setFlightAnywhere(false)
                }
            }
        }
        prefetchArt(romPath)
        p.startSampling(
            gameBoy = !romPath.lowercase().endsWith(".gba"), game = kotlinx.coroutines.Dispatchers.Main,
            current = { game === g && g.isRunning }, after = {
                currentOptions.settingsChanged(currentSettings)
                p.flight?.sync()
                p.battleRetry.observe(it, live() && !currentCoop.blocksLoad) { slots?.captureRetry() == true }
                if (live()) {
                    val steps = sharedItems.repelVar?.let { varId ->
                        it.progress?.variable(varId)?.let(sharedItems::repelSteps)
                    } ?: 0
                    p.autoRefill.observe(it, steps, prepare = { (item, _) ->
                        readSupport()?.takeIf { fields -> fields.canUse(item) }?.let { fields ->
                            { calls, busy -> com.pokedaisy.app.companion.data.refillRepel(fields.fns, item, busy, calls) }
                        }
                    }) { item ->
                        if (live()) {
                            screen?.showItemNotice(item, tr("Another {0} went on", com.pokedaisy.app.companion.data.itemName(item)))
                            sample()
                        }
                    }
                }
                if (it.blocked == null) currentLook.observe(it) else currentLook.close()
                if (it.blocked != null && g.wideSupported) g.setExpandedTables(0)
            }, periodMillis = SAMPLE_MILLIS, onShinyFound = { alert ->
                if (live()) {
                    p.actions?.stopFastForward() ?: currentSettings.setFfToggled(false)
                    touch.releaseKeys()
                    g.releaseManualInput()
                    IosTouchPreferences.feel(IosTouchPreferences.style.vibration)
                    screen?.showItemNotice(0, alert.message(), com.pokedaisy.app.companion.GameShinyAlert.NOTICE_MILLIS)
                }
            }, onNuzlockeNotices = { notices ->
                if (live()) screen?.showItemNotice(0, notices.joinToString("\n"),
                    com.pokedaisy.app.companion.Nuzlocke.NOTICE_MILLIS, ok = false)
            })
        return g
    }

    /** Full-window shared library, setup and application settings. */
    fun libraryViewController(failed: String?, onPlay: (String) -> Unit): UIViewController {
        start()
        return IosLibraryHost(touch, onPlay, failed).controller()
    }

    private fun finishClose() {
        if (prepareClose()) onCloseRequested()
    }

    private fun prepareClose(): Boolean {
        if (game?.pause() != false) return true
        // A background capture may fail too, but an explicit close can keep the running moment available.
        game?.resume()
        return false
    }

    private fun resumeFailed() {
        stateError = tr("Couldn't update resume data. Keep an in-game save too.")
        platform.Foundation.NSLog("PokeDaisy: couldn't update resume data")
    }

    private var playerLook: PlayerLookSession? = null
    private var coopGame: IosCoopGame? = null
    private var steadyMusic: IosSteadyMusic? = null
    private var gameClick: IosGameClickSound? = null

    private fun playGameClick() {
        if (settings?.clickSound == true) gameClick?.play()
    }

    /** Writes the running game's save and closes it; the companion goes back to waiting. */
    fun closeGame() {
        FeatureTips.endGame()
        stateError = null
        steadyMusic?.close()
        steadyMusic = null
        gameClick?.close()
        gameClick = null
        game?.pause()
        coopGame?.close()
        coopGame = null
        playerLook?.close()
        playerLook = null
        playback?.quiesce()
        items = null
        fly = null
        flash = null
        evolution = null
        playback?.close()
        playback = null
        battle = null
        screen?.close()
        screen = null
        touch.close()
        game?.let {
            IosAchievements.session.onCoreStopping()
            it.close()
            IosAchievements.session.onCoreStopped()
            IosAchievements.session.setCheatsActive(false)
        }
        game = null
        slots = null
        settings = null
        telemetry.reset()
    }

    /** The party art, region maps and card pieces the screens draw, rebuilt from the player's ROM the first time it
     * runs (as the Android app does on launch; RomArt / Gen1Art / Gen2Art scan in the background). */
    private fun prefetchArt(romPath: String) {
        val files = appFilesDir()
        val rom = romPath.toPath()
        when (romPath.substringAfterLast('.', "").lowercase()) {
            "gba" -> RomArt.prefetch(files, "${rom.name}-${FileSystem.SYSTEM.metadataOrNull(rom)?.size ?: 0}", rom)
            "gb", "gbc" -> {
                Gen1Art.prefetch(files, rom)
                Gen2Art.prefetch(files, rom)
            }
        }
    }

    private fun sample() { playback?.sample() }
}
