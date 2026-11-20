package com.pokedaisy.app

import com.pokedaisy.app.companion.DsCore
import com.pokedaisy.app.companion.PlayerLookSession
import com.pokedaisy.app.companion.data.AppearanceAssets
import com.pokedaisy.app.companion.data.save.Gen3SaveFile
import com.pokedaisy.app.companion.data.CoreKey
import com.pokedaisy.app.companion.ui.produceKeyedState
import android.app.ActivityManager
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.data.showsBattle
import com.pokedaisy.app.companion.data.isHgss
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.window.layout.FoldingFeature
import com.pokedaisy.app.achievements.RetroAchievements
import com.pokedaisy.app.achievements.downloadBadge
import com.pokedaisy.app.companion.ui.AchievementBadges
import com.pokedaisy.app.companion.BatteryStatus
import com.pokedaisy.app.companion.DeviceBattery
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.ui.MenuKey
import com.pokedaisy.app.overlay.OverlayChoice
import com.pokedaisy.app.overlay.OverlayStore
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.FoldMode
import com.pokedaisy.app.companion.FoldPosture
import com.pokedaisy.app.companion.data.FfMenuWatch
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.GameOptions
import com.pokedaisy.app.companion.PrefsCompanionSettings
import com.pokedaisy.app.companion.TipId
import com.pokedaisy.app.companion.GameTipFrame
import com.pokedaisy.app.companion.GameTipSession
import com.pokedaisy.app.companion.controllerTipShortcut
import com.pokedaisy.app.companion.changeExpandedZoom
import com.pokedaisy.app.companion.zoomFactors
import com.pokedaisy.app.companion.data.hasData
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.RomRegionMap
import com.pokedaisy.app.companion.data.displayName
import com.pokedaisy.app.companion.ui.CompanionScreen
import com.pokedaisy.app.companion.ui.RunningGameStatusBar
import com.pokedaisy.app.companion.ui.PerformanceCounters
import java.io.File
import com.pokedaisy.app.companion.coop.CoopEndpointSession
import com.pokedaisy.app.companion.coop.CoopGameStartup
import com.pokedaisy.app.companion.coop.CoopGameShutdown
import com.pokedaisy.app.companion.coop.CoopGameSettings
import com.pokedaisy.app.companion.coop.CoopPair
import com.pokedaisy.app.companion.coop.CoopSession
import com.pokedaisy.app.companion.coop.coopRetailSha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pokedaisy.app.companion.data.delete
import okio.Path.Companion.toOkioPath
import okio.source
import com.pokedaisy.app.companion.data.lastModified

/**
 * Phase 1 shell: full-screen GBA emulation with RetroArch-style savestate slots
 * on configurable hotkeys (see [Hotkeys]). Backgrounding suspends to a state and
 * resumes exactly where you left off.
 *
 * Drop one `.gba` into `Android/data/com.pokedaisy.app/files/roms/`.
 */

/** HeartGold, SoulSilver, Platinum (every language): their text printer task is DsInstantText's (INSTANT TEXT). */
private val DS_TEXT_CODES = setOf("IPK", "IPG", "CPU")

class PokeDaisyActivity : Activity() {

    private val input = GbaInput()
    /** The companion's GAME BUTTONS / HOTKEYS rows waiting for keys: every key comes here (the Presentation forwards
     * them), so the capture runs here and [keyCaptureShown] shows it over the game. */
    private val keyCapture = com.pokedaisy.app.companion.KeyCapture().apply { onActiveChanged = ::onKeyCapture }
    private var keyCaptureShown by androidx.compose.runtime.mutableStateOf(false)
    private lateinit var hotkeys: Hotkeys
    /** [Prefs.hotkeysEnabled], read on resume; off, every key goes to the game. */
    private var hotkeysOn = true
    private lateinit var engine: EmulatorEngine
    private lateinit var view: EmulatorView
    /** SCREEN > OVERLAY's imports (Settings > OVERLAY on the top screen adds them). */
    private val overlayStore by lazy { OverlayStore(getExternalFilesDir(null) ?: filesDir) }
    private lateinit var hud: TextView
    private lateinit var touchControls: TouchControlsView
    private lateinit var statusBar: ComposeView
    private lateinit var inputManager: android.hardware.input.InputManager
    private val ffMusicPlayer = FfMusicPlayer()
    private var ffMusicRenderer: SongRenderer? = null
    private var clickSound: GameClickSound? = null
    private var unlockSound: GameClickSound? = null
    /** The companion's button click (the game's own; see GameClickSound). */
    private val playClick: () -> Unit = { if (clickSoundOn) clickSound?.play() }
    @Volatile private var clickSoundOn = true

    private val inputDeviceListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) {
            InputDevice.getDevice(id)?.takeIf(::isGamepad)?.let {
                pads[id] = it.name
                showHud(tr("Controller connected: {0}", it.name))
            }
            syncTouchControls()
        }
        override fun onInputDeviceRemoved(id: Int) {
            pads.remove(id)?.let { showHud(tr("Controller disconnected: {0}", it)) }
            // A keyboard-mode controller (see physicalPadUsed) may have been the one that went.
            physicalPadUsed = false
            syncTouchControls()
        }
        override fun onInputDeviceChanged(id: Int) = syncTouchControls()
    }

    /** Connected controllers by device id, with their names (for the disconnect notice). */
    private val pads = HashMap<Int, String>()

    /**
     * A real button played the game: AUTO hides the touch controls from then on, even for a
     * controller that doesn't call itself a gamepad (some 8BitDo / GameSir modes show
     * up as a keyboard), until a device goes away.
     */
    private var physicalPadUsed = false

    private fun isGamepad(d: InputDevice) = Controllers.isGamepad(d)

    private var rom: File? = null
    /** What the emulator and the ROM readers load for [rom]: the file itself, or an
     * archive's ROM extracted into the cache ([RomArchive.playable]). Saves stay
     * keyed on [rom]'s name. */
    private var romData: File? = null
    /** Opened by a frontend through [LaunchActivity] (see [exitGame]). */
    private var fromFrontend = false
    private var romKey: String = ""      // ROM CRC32, for per-ROM prefs
    /** WIDE's native tables for this ROM ([WideScreen.gameFor]); OFF for a game it doesn't know. */
    private var wideGame = WideScreen.OFF
    /** The running game is one its makers asked PokéDaisy not to read ([com.pokedaisy.app.companion.data.BlockedGames], by
     * CRC): it plays as a plain game, with none of PokéDaisy's game features. Set in [loadRom], before anything else asks. */
    @Volatile private var romBlocked: com.pokedaisy.app.companion.data.BlockedGame? = null
    /** The field reads' game (SCENE SPEEDS' TEXT, AUTO-RUN, RUN INDOORS, WILD ENCOUNTERS): [WideScreen.fieldGameFor]. */
    private var fieldGame = WideScreen.OFF
    /** RetroAchievements pause while SHINY ODDS is raised on a game it works in ("catch a shiny" sets). */
    private fun syncShinyAchievements() {
        val raised = ::engine.isInitialized && engine.shiny.active
        runCatching { com.pokedaisy.app.achievements.RetroAchievements.setShinyOddsActive(raised) }
    }

    private fun currentGameOptions() = if (::engine.isInitialized) engine.gameOptions else null

    private fun applyGameOptions(options: GameOptions, host: EmulatorEngine? = if (::engine.isInitialized) engine else null) {
        // Android has always honored discovered patch sites, even on games the UI labels built-in.
        options.settingsChanged(companionSettings, runIndoorsSupported = companionSettings.runIndoorsSupported, reusableTmsBuiltIn = false)
        if (host != null) {
            val dsText = dsInstantTextWorks(host)
            host.dsInstantText = dsText && companionSettings.instantText
            host.dsReusableTms = dsText && companionSettings.reusableTms
        }
        syncExpShareAchievements(options.expShareActive)
    }

    private fun syncExpShareAchievements(active: Boolean) {
        runCatching { RetroAchievements.setExpShareActive(active) }
    }

    private fun answerBattleRetry(retry: Boolean) {
        if (!::engine.isInitialized) return
        val current = engine
        if (current.playback.battleRetry.answer(retry) && !coopRefuses()) current.requestRetryLoad()
    }

    /** Whether this game can take a REPEL from the companion: what AUTO REFILL needs (the quick menu's REPEL rule). */
    private fun repelRefillSupported(): Boolean =
        dsRepelKnown() || fieldItemSupport()?.let { s -> s.fns.repelItems.any { s.canUse(it) } } == true

    private fun updateRepelRefill(snap: com.pokedaisy.app.companion.data.SnapshotView) {
        if (!snap.connected || !::engine.isInitialized) return
        val current = engine
        val steps = snap.repelSteps ?: itemUse.repelVar?.let { snap.progress?.variable(it) }?.let(itemUse::repelSteps) ?: 0
        current.playback.autoRefill.observe(snap, steps, prepare = { (item, added) ->
            val ds = if (dsRepelKnown()) snap.game else null
            val support = if (ds == null) fieldItemSupport()?.takeIf { it.canUse(item) } else null
            when {
                ds != null -> { calls, _ ->
                    val f = current.fieldState
                    val free = f and MgbaCore.FIELD_SCREEN != 0 &&
                        f and (MgbaCore.FIELD_LOCKED or MgbaCore.FIELD_MENU or MgbaCore.FIELD_BATTLE) == 0
                    com.pokedaisy.app.companion.data.gen5ConfigFor(ds)?.let {
                        com.pokedaisy.app.companion.data.useGen5Repel(it, calls, item, added, free, topUp = true)
                    } ?: com.pokedaisy.app.companion.data.useGen4Repel(
                        com.pokedaisy.app.companion.data.gen4ConfigFor(ds)!!, calls, item, added, free, topUp = true)
                }
                support != null -> { calls, busy -> com.pokedaisy.app.companion.data.refillRepel(support.fns, item, busy, calls) }
                else -> null
            }
        }) { item ->
            val name = com.pokedaisy.app.companion.data.itemName(item)
            runOnUiThread { if (engine === current) showHud(com.pokedaisy.app.companion.i18n.tr("Another {0} went on", name)) }
        }
    }

    /** SETTINGS > COMPANION BUTTON: the buttons on the game screen that open the companion - the HUD's COMPANION and the white
     * edge tab ([SidePanel.openTabShown]) - shown at all (Compose state: the HUD reads it). */
    private val companionButtonShown = androidx.compose.runtime.mutableStateOf(true)

    /** EXPAND's one-time offer is up on the companion ([CompanionSettings.expandOffer]). */
    private val expandOfferUp = androidx.compose.runtime.mutableStateOf(false)
    private var states: SaveStates? = null

    private val telemetry = TelemetryStore()

    /** PLAYER LOOK for the running game: its patches built off the UI thread, the pick handed to the engine. */
    private val lookSession by lazy {
        PlayerLookSession(Prefs(applicationContext), filesDir.toOkioPath()) { patch ->
            if (::engine.isInitialized) engine.setPlayerLook(patch)
        }
    }
    private val lookGender = kotlinx.coroutines.CoroutineScope(androidx.compose.ui.platform.AndroidUiDispatcher.Main)
    private lateinit var displayManager: DisplayManager
    private var presentation: DualScreenPresentation? = null
    // BACK for the companion: one per companion copy (bottom screen, debug mirror).
    private val companionBack = com.pokedaisy.app.companion.ui.CompanionBack()
    private val mirrorBack = com.pokedaisy.app.companion.ui.CompanionBack()
    private val panelBack = com.pokedaisy.app.companion.ui.CompanionBack()
    /** The game's buttons for the one-screen companion while it's the window over the game (SidePanel). */
    private val panelKeys = com.pokedaisy.app.companion.ui.CompanionKeys()
    private val portraitBack = com.pokedaisy.app.companion.ui.CompanionBack()
    /** BACK for the FIELD HUD's panels (map, bag, wild, summary): the first thing a BACK tap closes. */
    private val hudBack = com.pokedaisy.app.companion.ui.CompanionBack()
    /** The FIELD HUD over the game (see [buildFieldHud]) and what it follows: SETTINGS > HUD, the phone's
     * orientation (none upright, for now), the touch controls' rows it keeps clear of, the engine's [EmulatorEngine.onField]. */
    private var fieldHudBounds by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    /** DS LAYOUT's pieces on the stage ([syncDsStage]): the game's backdrop under the screens, the touch screen beside
     * or inset in the game, and the floating touch window over it ([com.pokedaisy.app.companion.ui.DsTouchWindow]). */
    private lateinit var dsBackdropView: ComposeView
    private lateinit var dsTouchView: FrameLayout

    /** Pictures of the game for behind DS LAYOUT's screens ([DsShots]). */
    private lateinit var dsShots: DsShots
    /** The DS layouts are on (a DS game, one screen, held sideways) and the current one floats the touch screen in. */
    private val dsFloats = androidx.compose.runtime.mutableStateOf(false)
    /** The floating touch screen opened by hand (the TOUCH SCREEN hotkey, the HUD's TOUCH): up until its X closes it. */
    private val dsWindowManual = androidx.compose.runtime.mutableStateOf(false)
    private val hudOn = androidx.compose.runtime.mutableStateOf(true)
    private val hudStyle = androidx.compose.runtime.mutableStateOf(com.pokedaisy.app.companion.FieldHudStyle())
    /** The QUICK MENU (companion.ui.QuickMenu, the QUICK MENU hotkey) and the actions it shows (SETTINGS > QUICK MENU). */
    private val quickMenu = com.pokedaisy.app.companion.ui.QuickMenuState().also { q ->
        q.onRun = { runQuickAction(it) }
        q.onRepel = { id -> quickActions.repel(id) }
        // The stick onto another tile: a light tick, like a game's weapon wheel (the D-pad's steps stay silent).
        // The stick still tilted when it closes (the ring is aimed with it): kept from the game until it's let go, or the
        // player rides off - and a bike at full speed ignores the SELECT that BIKE / ROD press.
        q.onClose = { if (quickStickOff) quickStickHold = true }
        q.onAimMove = { if (::view.isInitialized) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
        q.onFly = { mapsec, targets -> quickActions.flyTo(mapsec, targets) }
    }
    private val quickShown = androidx.compose.runtime.mutableStateOf(com.pokedaisy.app.companion.QuickAction.entries.toSet())
    /** The QUICK MENU's HEAL done: its animation over the game until it fades ([com.pokedaisy.app.companion.ui.HealedAnimation]). */
    private val healShow = androidx.compose.runtime.mutableStateOf<com.pokedaisy.app.companion.ui.HealShow?>(null)
    /** The joystick's directions while the QUICK MENU is open, so a new one is a press. */
    private var quickHat = 0
    /** The stick / hat off centre at the QUICK MENU's last motion event; then, once it closed, held from the game until centred. */
    private var quickStickOff = false
    private var quickStickHold = false
    /** Bumped as the QUICK MENU opens: the HUD closes its open panel (FieldHud's closePanels). */
    private val hudCloseTick = androidx.compose.runtime.mutableIntStateOf(0)
    /** A tip's SHOW ME opening a HUD panel (FieldHud's openPanel / openTick), and whether one is open (no tip over it). */
    private val hudOpenPanel = androidx.compose.runtime.mutableStateOf<Pair<com.pokedaisy.app.companion.ui.FieldHudPanel, Int>?>(null)
    private val hudOpenTick = androidx.compose.runtime.mutableIntStateOf(0)
    private val hudPanelOpen = androidx.compose.runtime.mutableStateOf(false)
    /** FEATURE TIPS' card goes on the companion when it has a screen of its own (two screens), else over the game. */
    private val tipsOnCompanion = androidx.compose.runtime.mutableStateOf(false)
    /** Between onResume and onPause: only then does play count. */
    private var tipsResumed = false
    /** Play time for the Library's support window ([com.pokedaisy.app.companion.SupportAsk]): a game running in front,
     *  not asleep, counted every 30 s into [Prefs.playMillis]. */
    private var playClock: kotlinx.coroutines.Job? = null
    private val playScope = kotlinx.coroutines.CoroutineScope(androidx.compose.ui.platform.AndroidUiDispatcher.Main)
    private var playTick = 0L
    private val hudUpright = androidx.compose.runtime.mutableStateOf(false)
    private val hudClear = androidx.compose.runtime.mutableStateOf(0 to 0)
    /** Bumped when a savestate loads: the item toast starts its bag baseline over. */
    private val itemToastReset = androidx.compose.runtime.mutableIntStateOf(0)
    /** ITEM POPUPS (SETTINGS > COMPANION). */
    private val itemToastOn = androidx.compose.runtime.mutableStateOf(true)
    private val hudFrame = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** Where the HUD keeps to while touch controls show held sideways, when the game's own picture gives it no frame
     * (EXPAND): the game's middle columns ([syncHudClear]). */
    private val hudTouchFrame = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private val hudAvoid = androidx.compose.runtime.mutableStateOf<List<androidx.compose.ui.geometry.Rect>>(emptyList())
    private val hudEngine = kotlinx.coroutines.flow.MutableStateFlow<EmulatorEngine?>(null)
    /** BATTLE BUTTONS (same view as the HUD): SETTINGS' switch, BATTLE HINTS, one screen (no second one), and where the
     * GBA's own picture is (EXPAND: the battle's middle, which [hudFrame] leaves out). */
    private val battleButtonsOn = androidx.compose.runtime.mutableStateOf(true)
    private val battleHints = androidx.compose.runtime.mutableStateOf(true)
    /** SETTINGS > BATTLE STYLE as Compose state: the BATTLE tab redraws when it changes (read from Prefs alone, a pick
     *  mid-game left the old look up until something else redrew the pane). */
    private val battleStyle = androidx.compose.runtime.mutableStateOf(com.pokedaisy.app.companion.BattleStyle.GEN4)
    private val hudSingleScreen = androidx.compose.runtime.mutableStateOf(false)
    private val coreFrame = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private val battlePicking = androidx.compose.runtime.mutableStateOf(false)
    /** The companion beside the game when there's no second screen (see [SidePanel]). */
    private lateinit var sidePanel: SidePanel
    /** ... and under it, with the screen held upright (see [PortraitPanel]). */
    private lateinit var portraitPanel: PortraitPanel
    /** No second screen for the companion: it's in [sidePanel] or [portraitPanel], by the screen's orientation. */
    private var singleScreen = false
    private var debugMirror = false
    /** The game's stage (game, status bar, touch controls, HUD, side panel): the activity's
     * content, or the second screen's with SWAP SCREENS (see [syncPresentation]). */
    private lateinit var stage: GameScreenView
    private var stageOwner: ComposeHostOwner? = null

    /**
     * The recomposer of every Compose view on [stage] (the FIELD HUD, the touch controls, BATTLE BUTTONS, the status
     * bar, the panels), for this activity's life. Compose otherwise uses the window's, and a ComposeView keeps the
     * coroutine scope of the first one it met: with SWAP SCREENS the stage changes windows (the second screen's is
     * made again after HOME, sleep, the lid), that first one is cancelled with its window, and every tap handler on
     * the stage started in a dead scope - the views still took the touches, but nothing happened.
     */
    private val stageRecomposer = androidx.compose.runtime.Recomposer(androidx.compose.ui.platform.AndroidUiDispatcher.Main)
    private val stageRecomposing = kotlinx.coroutines.CoroutineScope(androidx.compose.ui.platform.AndroidUiDispatcher.Main)
        .launch { stageRecomposer.runRecomposeAndApplyChanges() }
    /**
     * The player is in the game (GameStart): the field or a battle has shown, or they walked (three positions - a game
     * whose field screen isn't learned yet), or the game resumed from a state. Before that (the title screen, the intro,
     * CONTINUE loading the save) the companion's PARTY / BAG / MAP / CARD wait. Emu thread writes, UI reads.
     */
    @Volatile private var playing = true
    private val playPositions = HashSet<Long>()

    /** A boot that starts at the title screen: the save tabs wait until the player is in the game. */
    private fun waitForPlay() {
        playing = false
        synchronized(playPositions) { playPositions.clear() }
        com.pokedaisy.app.companion.ui.GameStart.waiting = true
    }

    /** In the game (or resumed into it): the save tabs show. */
    private fun nowPlaying() {
        playing = true
        runOnUiThread { com.pokedaisy.app.companion.ui.GameStart.waiting = false }
    }

    /** The companion as the activity's content while the screens are swapped, with its owner. */
    private var mainCompanion: View? = null
    private var mainCompanionOwner: ComposeHostOwner? = null
    /** The companion a swap opens on: SETTINGS when the swap came from there. */
    private var companionStartTab = "PARTY"
    /** ...and SETTINGS' row it opens on (SWAP SCREENS after a swap), the same one time. */
    private var companionStartRow: String? = null

    // scripts/capture_fixture.sh support: if the EXTRA_DUMP_FIXTURE extra is a
    // directory path, the next successful (connected) telemetry sample dumps
    // raw EWRAM+IWRAM there for app/src/test's FakeMemoryReader fixtures - see
    // dumpFixtureIfPending()'s comment for why full RAM regions, not just the
    // fields a given decoder happens to read.
    private var pendingFixtureDump: File? = null
    // EXTRA_DUMP_FIXTURE_FORCE bypasses the party.isNotEmpty() readiness gate
    // below - for diagnosing a hack whose party/bag addresses are THEMSELVES
    // wrong (the exact case this exists for: party never populates because
    // the addresses are broken, so the normal gate can never fire either -
    // a chicken-and-egg problem the normal gate can't solve for itself).
    private var forceFixtureDump = false

    /** Backs the bottom-screen "States" tab. */
    private val stateSlots = object : com.pokedaisy.app.companion.StateSlots {
        override fun list() = if (::engine.isInitialized) engine.stateSlotList() else emptyList()
        override val currentIndex get() = if (::engine.isInitialized) engine.currentSlot else 0
        override fun requestSave(index: Int) { if (n3ds != null) return n3dsSaveSlot(index); if (::engine.isInitialized && !coopRefuses()) engine.requestSaveState(index) }
        override fun requestLoad(index: Int) { if (n3ds != null) return n3dsLoadSlot(index); if (::engine.isInitialized && !coopRefuses()) engine.requestLoadState(index) }
        override fun requestDelete(index: Int) { if (n3ds != null) {
            val s = states ?: return
            if (index !in SaveStates.SLOT_MIN..SaveStates.SLOT_MAX) return
            val state = s.stateFile(index)
            runCatching {
                state.delete()
                s.thumbFile(index).delete()
                s.bakFile(index).delete()
                s.bakThumbFile(index).delete()
                com.pokedaisy.app.companion.data.SnapshotCache.fileFor(state.toOkioPath()).delete()
                com.pokedaisy.app.achievements.RetroAchievements.progressFile(state).delete()
            }
        } else if (::engine.isInitialized) engine.requestDeleteState(index) }
        override fun requestUndoSave() { if (n3ds == null && ::engine.isInitialized && !coopRefuses()) engine.requestUndoSave() }
        override fun requestUndoLoad() { if (n3ds == null && ::engine.isInitialized && !coopRefuses()) engine.requestUndoLoad() }
    }

    /** Backs the bottom-screen touch battle control (see PLAN.md Phase 5). A pick that hands the turn to the game
     * (a move, a switch, RUN, or BAG / POKéMON opening the game's own screen) closes the one-screen companion's window,
     * so the player sees it play out; FIGHT keeps it open, for the move list in it. */
    private val battleInput = object : com.pokedaisy.app.companion.BattleInput {
        override val menuCursor get() = if (::engine.isInitialized) engine.battleMenuCursor else -1
        override val menuOnCompanion get() = ::engine.isInitialized && engine.battleMenuHide != 0
        override val busy get() = n3ds?.battleTaps?.busy ?: if (::engine.isInitialized) engine.battleInputBusy else false
        override val gameSilent get() = ::engine.isInitialized && engine.gameAudioPaused
        // A 3DS game: its own touch-screen buttons pressed (N3dsBattleTaps).
        override fun selectAction(actionIndex: Int) {
            if (actionIndex != 0) closeCompanionWindow()
            n3ds?.let { it.battleTaps.selectAction(actionIndex); return }
            if (::engine.isInitialized) engine.battleSelectAction(actionIndex)
        }
        override fun selectMove(moveIndex: Int) {
            closeCompanionWindow()
            n3ds?.let { it.battleTaps.selectMove(moveIndex); return }
            if (::engine.isInitialized) engine.battleSelectMove(moveIndex)
        }
        override fun back() {
            n3ds?.let { it.battleTaps.back(); return }
            if (::engine.isInitialized) engine.battleBack()
        }
        override val canSwitch get() = ::engine.isInitialized && (engine.battleSwitchAddrs != null || dsSwitchWorks())
        override val lastBall get() = if (::engine.isInitialized) engine.playback.lastBall?.current else null
        override fun throwBall() { if (::engine.isInitialized) engine.playback.lastBall?.throwBall() }
        override fun switchTo(personality: Long) {
            closeCompanionWindow()
            if (!::engine.isInitialized) return
            if (romIsNds) {
                val b = dsSwitchBattle() ?: return
                val place = runCatching {
                    com.pokedaisy.app.companion.data.gen4SwitchSlot(com.pokedaisy.app.companion.data.InProcessReader, b, personality)
                }.getOrNull() ?: return
                engine.battleDsSwitchTo(place)
            } else engine.battleSwitchTo(personality)
        }
    }

    /** The one-screen companion's window goes away (a battle pick, FLY): the game's to see now. Any thread. */
    private fun closeCompanionWindow() {
        runOnUiThread {
            if (::sidePanel.isInitialized) sidePanel.closeWindow()
            // The buttons on the other screen's companion (X): the game's again, it's the game's turn.
            setBottomFocus(false)
        }
    }

    /** USE / GIVE / TAKE, the way the game does them (FieldItems.kt): the retail games in every language mapped so
     * far, the hacks that are retail code edited in place and the rebuilds whose config carries its own (Recharged
     * Emerald); the code CRCs decide the rest. */

    /** WHICH GAME IS THIS?: the player's answers, taken by the sampler on its next pass (the emulator thread). */
    private val gamePick get() = telemetry.gamePick

    /** The match notice's SHARE - the player's yes to sending exactly what it listed. */
    private val shareBestEffort = com.pokedaisy.app.companion.data.BestEffortSharing(BuildConfig.VERSION_NAME) { r ->
        BestEffortShare.share(this, r)
    }

    /** NOT SUPPORTED's ASK FOR SUPPORT: the ROM's SHA-1 off the UI thread, then the request form in a browser. */
    private val askForSupport: () -> Unit = {
        rom?.let { r ->
            Thread({
                val url = RomSupportRequest.url(r)
                runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            }, "pokedaisy-support-request").apply { isDaemon = true; start() }
        }
    }

    private val fieldSupport = com.pokedaisy.app.companion.data.FieldItemSupportCache()

    private fun fieldItemSupport(): com.pokedaisy.app.companion.data.FieldItemSupport? {
        val snapshot = telemetry.snapshot.value
        if (romBlocked != null) { com.pokedaisy.app.companion.data.FlyWithoutMoveGame.active = false; return null }
        if (!::engine.isInitialized || !engine.running) return null
        return fieldSupport.get(snapshot, romCode, romRev, telemetry.knownFieldItems())
    }

    private fun centerFound() = if (::engine.isInitialized && engine.running) engine.gameOptions.discovery.center else null

    /** TRADE EVOLUTIONS' calls for the loaded ROM ([com.pokedaisy.app.companion.data.tradeEvoFor]), its code checked once
     * per ROM; null where the game has none (a hack, another language, a Game Boy or DS game). */
    private val verifiedTradeEvolutions by lazy {
        com.pokedaisy.app.companion.FieldTradeSupport(com.pokedaisy.app.companion.data.InProcessReader::readCoreMemory)
    }

    private fun tradeEvoFns(): com.pokedaisy.app.companion.data.TradeEvoFns? {
        if (romIsGameBoy || romIsNds || !::engine.isInitialized || !engine.running) return null
        return verifiedTradeEvolutions.get(telemetry.snapshot.value, romCode, romRev)
    }

    private fun retailFieldKey(): Pair<String, Boolean>? {
        if (romIsGameBoy || romIsNds) return null
        // A rebuild on retail's game code (Recharged Emerald): none of retail's code, its builds' own records (the CRCs pick
        // the build). Emerald Legacy has records of its own; the hacks edited into retail in place (the FireRed ones, Kaizo,
        // the randomized carts...) run its tables. The code CRCs decide.
        val hack = com.pokedaisy.app.companion.data.runningEmeraldHack != null
        return "$romCode/$romRev/${telemetry.snapshot.value.game}/${com.pokedaisy.app.companion.data.romGameCode}" to hack
    }

    private val verifiedTms by lazy {
        com.pokedaisy.app.companion.FieldTmSupport(com.pokedaisy.app.companion.data.InProcessReader::readCoreMemory)
    }

    private fun tmFns(): com.pokedaisy.app.companion.data.TmFns? {
        if (retailFieldKey() == null || !::engine.isInitialized || !engine.running) return null
        return verifiedTms.get(telemetry.snapshot.value, romCode, romRev)
    }

    private val fieldFlash by lazy {
        val verified = com.pokedaisy.app.companion.FieldFlashSupport(
            com.pokedaisy.app.companion.data.InProcessReader::readCoreMemory)
        com.pokedaisy.app.companion.FieldFlashAction(sharedItemUse,
            { if (romIsGameBoy || romIsNds || fieldItemSupport()?.fns?.party == null) null
              else verified.get(telemetry.snapshot.value, romCode, romRev) },
            { telemetry.snapshot.value }, com.pokedaisy.app.companion.data.InProcessReader::readCoreMemory,
            ::closeCompanionWindow)
    }

    /** The shared GB coordinator verifies ROM code and owns queued PC / FLY results. */
    private fun gameBoyFields(): com.pokedaisy.app.companion.GameBoyFieldActions? {
        if (!::engine.isInitialized) return null
        val current = engine
        return current.playback.bindGameBoyFields(
            enabled = { romIsGameBoy && engine === current && current.running && romBlocked == null },
            rom = { off, len -> MgbaCore.pkRomRead(off, len) },
            deliver = { result -> runOnUiThread(result) }, handedOver = ::closeCompanionWindow)
    }
    private fun gbField() = gameBoyFields()?.field

    /** A DS game whose Repel steps the reader knows (Platinum, HeartGold / SoulSilver, Black). */
    private fun dsRepelKnown(): Boolean {
        val game = telemetry.snapshot.value.game
        return romIsNds && (com.pokedaisy.app.companion.data.gen4ConfigFor(game)?.repelStepsOff
            ?: com.pokedaisy.app.companion.data.gen5ConfigFor(game)?.repelStepsOff) != null
    }

    private val sharedItemUse = com.pokedaisy.app.companion.FieldItemUse(
        support = ::fieldItemSupport,
        session = { if (::engine.isInitialized && engine.running) engine.gameSession else null },
        inBattle = { telemetry.snapshot.value.inBattle },
        centerMetadata = ::centerFound,
        tmMetadata = ::tmFns,
        handedOver = ::closeCompanionWindow,
        deliver = { result -> runOnUiThread(result) },
    )

    private val sharedClassicFly = com.pokedaisy.app.companion.ClassicFlyAction(
        sharedItemUse, ::fieldItemSupport,
        session = { if (::engine.isInitialized && engine.running) engine.gameSession else null },
    )

    private val partyActions = com.pokedaisy.app.companion.FieldPartyActions(
        sharedItemUse, { if (::engine.isInitialized && engine.running) engine.gameSession else null },
        { telemetry.snapshot.value },
        { if (romIsNds || romIsGameBoy) null else telemetry.knownPartyReorderConfig() },
        { if (::engine.isInitialized) engine.fieldState else 0 },
        { result -> runOnUiThread(result) },
    )

    private val tradeEvolutions = com.pokedaisy.app.companion.FieldTradeEvolution(
        partyActions, sharedItemUse, ::tradeEvoFns,
        { Prefs(this).tradeEvolutions }, { ::engine.isInitialized && engine.onField.value }, ::closeCompanionWindow,
    )

    private val sharedPc = com.pokedaisy.app.companion.GamePcAction(
        gameBoy = ::gameBoyFields, gba = sharedItemUse, handedOver = ::closeCompanionWindow,
        pcUsed = { com.pokedaisy.app.companion.FeatureTips.used(TipId.PC) },
    )

    private val itemUse = object : com.pokedaisy.app.companion.ItemUse by tradeEvolutions {
        /** A DS game's Repels ([com.pokedaisy.app.companion.data.useGen4Repel], Black's [com.pokedaisy.app.companion.data.useGen5Repel]);
         * nothing else yet. */
        private fun dsRepel(itemId: Int) = dsRepelKnown() && com.pokedaisy.app.companion.data.GEN4_REPEL_KINDS.any { it.first == itemId }
        override fun canUse(itemId: Int) = dsRepel(itemId) || sharedItemUse.canUse(itemId)
        override fun marked(itemId: Int) = dsRepel(itemId) || sharedItemUse.marked(itemId)

        private val failed = com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.FAILED)

        override fun use(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {
            if (dsRepel(itemId)) {
                // Plain RAM writes on the emu thread, with the field free by its own read (MgbaCore.FIELD_SCREEN, not locked).
                if (!::engine.isInitialized || !engine.running) return done(failed)
                val inBattle = telemetry.snapshot.value.inBattle
                val steps = com.pokedaisy.app.companion.data.GEN4_REPEL_KINDS.first { it.first == itemId }.second
                engine.requestFieldItemJob(inBattle) { calls, _ ->
                    val f = engine.fieldState
                    val free = f and MgbaCore.FIELD_SCREEN != 0 &&
                        f and (MgbaCore.FIELD_LOCKED or MgbaCore.FIELD_MENU or MgbaCore.FIELD_BATTLE) == 0
                    val game = telemetry.snapshot.value.game
                    val out = runCatching {
                        com.pokedaisy.app.companion.data.gen5ConfigFor(game)?.let { com.pokedaisy.app.companion.data.useGen5Repel(it, calls, itemId, steps, free) }
                            ?: com.pokedaisy.app.companion.data.useGen4Repel(
                                com.pokedaisy.app.companion.data.gen4ConfigFor(game)!!, calls, itemId, steps, free)
                    }.getOrElse { Log.e("pokedaisy", "repel failed", it); failed }
                    Log.i("pokedaisy", "item use (DS): $out")
                    runOnUiThread { done(if (inBattle) com.pokedaisy.app.companion.fieldActionBattleResult(out) else out) }
                }
                return
            }
            sharedItemUse.use(itemId, done)
        }

        // The summary's moves: SetMonData on the party record, so the game re-encrypts it and its checksum follows.
        override fun canReorderMoves() = !romIsNds && !romIsGameBoy && ::engine.isInitialized && engine.running &&
            !telemetry.snapshot.value.inBattle && fieldItemSupport()?.fns?.let { it.party != null && it.bagActions } == true
        override fun reorderMoves(
            slot: Int, personality: Long, from: Int, to: Int,
            done: (com.pokedaisy.app.companion.data.MoveOrderResult) -> Unit,
        ) = sharedItemUse.onGame(com.pokedaisy.app.companion.data.MoveOrderResult.FAILED, done) { s, calls, busy ->
            com.pokedaisy.app.companion.data.reorderMoves(s.fns, slot, personality, from, to, busy, calls)
        }

        override fun canSortBoxes() = partyActions.canSwapBox()

        override fun sortBoxes(done: (com.pokedaisy.app.companion.data.BoxSortResult) -> Unit) {
            val inBattle = telemetry.snapshot.value.inBattle
            // Sorted by Pokédex number: the ROM's species -> dex table, read here (off the emu thread) before the sort needs it.
            val dex = com.pokedaisy.app.companion.data.activeNativeConfig?.pokedex
            Thread {
                val dexOf: (Int) -> Int = dex?.let { t -> { s: Int -> com.pokedaisy.app.companion.data.PokedexSource.nationalOf(t, s) } } ?: { 0 }
                dexOf(1)
                sharedItemUse.onGame(com.pokedaisy.app.companion.data.BoxSortResult.FAILED, { r ->
                    done(if (inBattle && r == com.pokedaisy.app.companion.data.BoxSortResult.NOT_NOW) com.pokedaisy.app.companion.data.BoxSortResult.IN_BATTLE else r)
                }) { s, calls, busy ->
                    val fns = com.pokedaisy.app.companion.data.boxSwapFor(s.fns, com.pokedaisy.app.companion.data.activeNativeConfig)
                        ?: return@onGame com.pokedaisy.app.companion.data.BoxSortResult.FAILED
                    com.pokedaisy.app.companion.data.sortBoxes(s.fns, fns, busy, calls, dexOf)
                }
            }.start()
        }

        override fun canPc() = dsCalls() || sharedPc.available()

        override fun openPc(done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {
            if (!dsCalls()) return sharedPc.open(done)
            dsAction(MgbaCore.NDS_ACTION_PC, intArrayOf(0, 0, 0, 0)) { result ->
                if (result.result == com.pokedaisy.app.companion.data.ItemUseResult.USED) {
                    closeCompanionWindow()
                    com.pokedaisy.app.companion.FeatureTips.used(TipId.PC)
                }
                done(result)
            }
        }

        /** A DS game whose PC pk_nds_call knows (Platinum, HeartGold / SoulSilver, Black). */
        private fun dsCalls() = romIsNds && !dsperate && telemetry.snapshot.value.game.let {
            com.pokedaisy.app.companion.data.gen4ConfigFor(it) != null || com.pokedaisy.app.companion.data.gen5ConfigFor(it) != null
        }
    }

    /** Posts DS [action] (MgbaCore.NDS_ACTION_*) on the emu thread, then waits a few frames for the game to run it:
     * USED once it has (or what [ran] makes of its results), NOT_NOW when the field isn't free (a text box, a menu, a
     * battle) or it never ran. */
    private fun dsAction(
        action: Int, args: IntArray,
        ran: ((IntArray?) -> com.pokedaisy.app.companion.data.ItemUseResult)? = null,
        done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit,
    ) {
        if (!::engine.isInitialized || !engine.running) return done(com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.FAILED))
        val inBattle = telemetry.snapshot.value.inBattle
        fun finish(r: com.pokedaisy.app.companion.data.ItemUseResult) {
            val out = com.pokedaisy.app.companion.data.ItemUseOutcome(r)
            Log.i("pokedaisy", "DS action $action: $out")
            runOnUiThread {
                done(if (inBattle && out.result == com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW) out.copy(result = com.pokedaisy.app.companion.data.ItemUseResult.IN_BATTLE) else out)
            }
        }
        fun poll(seq: Int, left: Int) {
            engine.requestFieldItemJob(inBattle) { _, _ ->
                when (MgbaCore.pkNdsActionStatus(seq)) {
                    1 -> finish(ran?.invoke(MgbaCore.pkNdsActionResults(seq)) ?: com.pokedaisy.app.companion.data.ItemUseResult.USED)
                    0 -> if (left > 0) poll(seq, left - 1) else finish(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW)
                    else -> finish(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW)
                }
            }
        }
        engine.requestFieldItemJob(inBattle) { _, _ ->
            val seq = MgbaCore.pkNdsAction(action, args[0], args[1], args[2], args[3])
            if (seq == 0) finish(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW) else poll(seq, 30)
        }
    }

    /** What the QUICK MENU's tiles do here ([quickMenu]). */
    private val quickActions: com.pokedaisy.app.companion.ui.GameQuickActions by lazy {
        com.pokedaisy.app.companion.ui.GameQuickActions(
            quickMenu, Prefs(this), companionSettings, { telemetry.snapshot.value }, { itemUse }, { flyAction },
            ::handleHotkey, { showHud(it) }, active = { ::engine.isInitialized && !isDestroyed },
            // HEAL: the party's bars filling up (HealedAnimation), not a notice.
            healed = { before -> healShow.value = com.pokedaisy.app.companion.ui.HealShow(before) },
            registeredItems = ::registeredItems, flash = { fieldFlash },
            extra = object : com.pokedaisy.app.companion.ui.QuickActions {
                override fun offered(action: com.pokedaisy.app.companion.QuickAction) = when (action) {
                    com.pokedaisy.app.companion.QuickAction.FLIGHT -> flightSupport() != null
                    com.pokedaisy.app.companion.QuickAction.REPEL -> dsRepelKnown() ||
                        fieldItemSupport()?.let { s -> s.fns.repelItems.any { s.canUse(it) } } == true
                    com.pokedaisy.app.companion.QuickAction.BIKE, com.pokedaisy.app.companion.QuickAction.ROD ->
                        romIsNds && dsKeyItemsWork()
                    com.pokedaisy.app.companion.QuickAction.TURBO -> true
                    // A DS / 3DS game whose touch screen floats in on this layout (the TOUCH SCREEN hotkey's rule).
                    com.pokedaisy.app.companion.QuickAction.TOUCH_SCREEN -> dsFloats.value || n3dsFloats
                    // One screen held sideways: the companion's window or side panel (upright, it's always open).
                    com.pokedaisy.app.companion.QuickAction.COMPANION -> hudSingleScreen.value && !hudUpright.value
                    else -> false
                }

                override fun run(action: com.pokedaisy.app.companion.QuickAction) = runPlatformQuickAction(action)

                override fun riding() = ::engine.isInitialized && engine.riding

                override fun flying() = ::engine.isInitialized && engine.flightState != MgbaCore.FLIGHT_IDLE


                override fun turbo() = input.turboLatch != 0
        })
    }

    private fun runQuickAction(action: com.pokedaisy.app.companion.QuickAction) = quickActions.run(action)

    private fun runPlatformQuickAction(action: com.pokedaisy.app.companion.QuickAction) {
        if (!::engine.isInitialized) return
        when (action) {
            com.pokedaisy.app.companion.QuickAction.BIKE, com.pokedaisy.app.companion.QuickAction.ROD -> {
                val items = if (action == com.pokedaisy.app.companion.QuickAction.BIKE)
                    com.pokedaisy.app.companion.data.KeyItems.BIKES else com.pokedaisy.app.companion.data.KeyItems.RODS
                com.pokedaisy.app.companion.data.KeyItems.firstIn(telemetry.snapshot.value.items, items)?.let(::useKeyItem)
            }
            com.pokedaisy.app.companion.QuickAction.FLIGHT -> toggleFlight()
            com.pokedaisy.app.companion.QuickAction.TURBO -> {
                input.turboLatch = if (input.turboLatch != 0) 0 else com.pokedaisy.app.companion.data.CoreKey.A
                showHud(com.pokedaisy.app.companion.i18n.tr("TURBO A") + ": " +
                    com.pokedaisy.app.companion.i18n.tr(if (input.turboLatch != 0) "ON" else "OFF"))
            }
            com.pokedaisy.app.companion.QuickAction.TOUCH_SCREEN -> toggleDsTouch()
            com.pokedaisy.app.companion.QuickAction.COMPANION -> if (::sidePanel.isInitialized) sidePanel.openPanel()
            else -> Unit
        }
    }

    /** One shared loaded-game owner; Android supplies only native transport and UI effects. */
    private fun gameFlight(): com.pokedaisy.app.companion.GameFlight? {
        if (!::engine.isInitialized || !engine.running) return null
        val current = engine
        return current.playback.bindFlight {
            com.pokedaisy.app.companion.GameFlight(
                current.gameSession, current.flightCore, Prefs(this),
                readSupport = { if (engine === current && current.running && !romIsGameBoy && !romIsNds) fieldItemSupport() else null },
                kind = { telemetry.snapshot.value.game }, retail = telemetry::retailRom,
                inBattle = { telemetry.snapshot.value.inBattle },
                standing = { current.onField.value && !current.fieldLocked }, riding = { current.riding },
                bike = { com.pokedaisy.app.companion.data.KeyItems.firstIn(telemetry.snapshot.value.items,
                    com.pokedaisy.app.companion.data.KeyItems.BIKES) }, useItem = ::useKeyItem,
                keeper = com.pokedaisy.app.companion.data.FlightArea.Keeper(filesDir.toOkioPath(), romKey),
                deliver = { runOnUiThread(it) },
                tookOff = { scenic ->
                    if (Prefs(this).flightArea != com.pokedaisy.app.companion.FlightAreaMode.SEEN) RetroAchievements.setFlightAnywhere(true)
                    if (!scenic) {
                        com.pokedaisy.app.companion.FeatureTips.used(TipId.FLY)
                        closeCompanionWindow()
                        hudCloseTick.intValue++
                    }
                }, turned = { showHud(com.pokedaisy.app.companion.i18n.tr("You haven't been there yet.")) },
                askStyle = { com.pokedaisy.app.companion.ui.FlyStyleAsk.showing = true },
            )
        }
    }

    private fun flightSupport() = gameFlight()?.support()
    private fun syncFlight() { gameFlight()?.sync() }
    private fun flightVehicle() = com.pokedaisy.app.companion.data.FlightVehicle.of(Prefs(this).flightVehicleId)
    private fun answerFlyStyle(style: com.pokedaisy.app.companion.FlyStyle?) { gameFlight()?.answerStyle(style) }

    fun toggleFlight() {
        gameFlight()?.toggle { result, notOnFoot ->
            if (notOnFoot) showHud(com.pokedaisy.app.companion.i18n.tr("You can only take off from land."))
            else if (result != null && result != com.pokedaisy.app.companion.data.FlyResult.OK)
                showHud(com.pokedaisy.app.companion.ui.flyMessage(result))
        }
    }

    /** Registered-item transactions and restoration are shared with iOS. */
    private fun registeredItems() = if (!::engine.isInitialized) null else engine.playback.bindRegisteredItems(
        ::fieldItemSupport, input::setPress,
    ) { runOnUiThread { showHud(com.pokedaisy.app.companion.ui.itemUseMessage(
        com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW))) } }

    /** The DS battle the POKéMON switch reads: HeartGold / SoulSilver and their ports (their party screen played headless;
     * Platinum's, likely the same, not seen yet; not Origin HeartGold, whose battles are its own engine). */
    private fun dsSwitchBattle(): com.pokedaisy.app.companion.data.Gen4Battle? {
        val game = telemetry.snapshot.value.game
        if (!game.isHgss) return null
        return com.pokedaisy.app.companion.data.gen4ConfigFor(game)?.battle
            ?.takeIf { it.offBattleInput != 0 && !com.pokedaisy.app.companion.data.originHeartGold }
    }

    private fun dsSwitchWorks(): Boolean =
        romIsNds && ::engine.isInitialized && engine.dsSwitchMapped && dsSwitchBattle() != null

    /** INSTANT TEXT and REUSABLE TMS on a DS game: the Gen 4 carts on melonDS (DsInstantText / DsReusableTms: code of ours,
     * none on DSperate). */
    private fun dsInstantTextWorks(host: EmulatorEngine? = if (::engine.isInitialized) engine else null): Boolean =
        romIsNds && romBlocked == null && !dsperate && host != null && host.ndsGameCode.take(3) in DS_TEXT_CODES


    private fun dsKeyItemsWork(): Boolean = romBlocked == null &&
        !com.pokedaisy.app.companion.data.originHeartGold && !com.pokedaisy.app.companion.data.renegadePlatinum &&
        com.pokedaisy.app.companion.data.gen4ConfigFor(telemetry.snapshot.value.game) != null

    /** A DS game's BIKE / ROD: the item written into the Bag's register (HG / SS: the first slot, the second cleared so Y uses it
     * at once), Y held 2 frames, the player's own registration put back after. The field free by its own read, as the Repel's. */
    private fun useDsKeyItem(itemId: Int) {
        val cfg = com.pokedaisy.app.companion.data.gen4ConfigFor(telemetry.snapshot.value.game) ?: return
        if (!::engine.isInitialized || !engine.running) return
        fun le32(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }
        fun u32(b: ByteArray) = (0 until 4).fold(0L) { a, i -> a or ((b[i].toLong() and 0xFF) shl (8 * i)) }
        engine.requestFieldItemJob(false) { calls, _ ->
            val ok = runCatching {
                val f = engine.fieldState
                if (f and MgbaCore.FIELD_SCREEN == 0 || f and (MgbaCore.FIELD_LOCKED or MgbaCore.FIELD_MENU or MgbaCore.FIELD_BATTLE) != 0) return@runCatching false
                val addr = com.pokedaisy.app.companion.data.gen4RegisterAddr(cfg, calls::read) ?: return@runCatching false
                val old = u32(calls.read(addr, 4))
                // Item ids (Platinum's one u32, or HG / SS's two u16): anything else isn't the register after all.
                if ((old and 0xFFFF) > 0x3FF || (old ushr 16) > 0x3FF || !calls.write(addr, le32(itemId.toLong()))) return@runCatching false
                input.setPress(CoreKey.Y)
                engine.requestFieldItemJob(false, delayFrames = 2) { _, _ -> input.setPress(0) }
                engine.requestFieldItemJob(false, delayFrames = 30) { c, _ -> if (u32(c.read(addr, 4)) == itemId.toLong()) c.write(addr, le32(old)) }
                true
            }.getOrDefault(false)
            if (!ok) runOnUiThread { showHud(com.pokedaisy.app.companion.ui.itemUseMessage(com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW))) }
        }
    }

    private fun useKeyItem(itemId: Int) {
        if (romIsNds) {
            if (dsKeyItemsWork()) useDsKeyItem(itemId)
        } else if (::engine.isInitialized && engine.running) registeredItems()?.use(itemId)
    }

    private val sharedFly = com.pokedaisy.app.companion.GameFlyAction(
        gameBoy = ::gameBoyFields, classic = sharedClassicFly,
        gbaAvailable = { ::engine.isInitialized && engine.running && fieldItemSupport()?.canFly == true },
        flight = ::gameFlight,
        beforeGbaFly = { closeCompanionWindow(); hudCloseTick.intValue++ },
        flyUsed = { com.pokedaisy.app.companion.FeatureTips.used(TipId.FLY) },
    )

    private val flyAction = object : com.pokedaisy.app.companion.FlyAction by sharedFly {
        override fun canFly() = dsFly() || gen5Fly() || sharedFly.canFly()

        override fun canFreeFlight() = flightSupport() != null

        override fun flying() = ::engine.isInitialized && engine.flightState != MgbaCore.FLIGHT_IDLE

        override fun toggleFlight(done: (com.pokedaisy.app.companion.data.FlyResult?) -> Unit) {
            this@PokeDaisyActivity.toggleFlight()
            done(null)
        }

        /** A DS game whose FLY pk_nds_call knows (Platinum, HeartGold / SoulSilver): its fly spots are the destinations
         * (spawn ids). */
        private fun dsConfig() = com.pokedaisy.app.companion.data.gen4ConfigFor(telemetry.snapshot.value.game)
        private fun dsFly() = romIsNds && !dsperate && dsConfig()?.flySpots?.isNotEmpty() == true

        /** Black: pk_nds_call's FLY the party menu's way; its destinations are the towns' places (Gen5Field.kt). */
        private fun gen5Config() = com.pokedaisy.app.companion.data.gen5ConfigFor(telemetry.snapshot.value.game)
        private fun gen5Spots() = com.pokedaisy.app.companion.data.gen5FlySpots(telemetry.snapshot.value.game)
        private fun gen5Fly() = romIsNds && !dsperate && gen5Config() != null && gen5Spots().isNotEmpty()

        /** Black's FLY check (the skill's own test of this place), then the towns whose arrival flag is set. */
        private fun gen5Targets(done: (com.pokedaisy.app.companion.data.FlyTargets) -> Unit) {
            val flyer = dsFlyer() ?: return done(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.NO_FLYER))
            val inBattle = telemetry.snapshot.value.inBattle
            val cfg = gen5Config() ?: return done(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.FAILED))
            val spots = gen5Spots()
            fun finish(t: com.pokedaisy.app.companion.data.FlyTargets) {
                Log.i("pokedaisy", "fly targets (Gen 5): $t")
                val shown = if (inBattle && t.result == com.pokedaisy.app.companion.data.FlyResult.NOT_NOW) t.copy(result = com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE) else t
                runOnUiThread { done(shown) }
            }
            fun poll(seq: Int, left: Int) {
                engine.requestFieldItemJob(inBattle) { calls, _ ->
                    val st = MgbaCore.pkNdsActionStatus(seq)
                    val res = if (st == 1) MgbaCore.pkNdsActionResults(seq) else null
                    when {
                        res != null -> {
                            val result = com.pokedaisy.app.companion.data.gen5FlyResult(res[1])
                            val open = com.pokedaisy.app.companion.data.gen5OpenFlySpots({ a, n -> calls.read(a, n) }, cfg, spots)
                            finish(com.pokedaisy.app.companion.data.FlyTargets(result, if (result == com.pokedaisy.app.companion.data.FlyResult.OK) open else emptyList(), flyer.first, flyer.second))
                        }
                        st == 0 && left > 0 -> poll(seq, left - 1)
                        else -> finish(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.NOT_NOW))
                    }
                }
            }
            engine.requestFieldItemJob(inBattle) { _, _ ->
                val seq = MgbaCore.pkNdsAction(MgbaCore.NDS_ACTION_FLY_CHECK, 0, 0, 0, 0)
                if (seq == 0) finish(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.NOT_NOW)) else poll(seq, 30)
            }
        }

        /** The first party Pokémon that knows FLY (not an egg): its slot and the snapshot's personality for it. */
        private fun dsFlyer(): Pair<Int, Long>? = telemetry.snapshot.value.party.withIndex().firstOrNull { (_, m) ->
            !m.isEgg && m.moves.any { it.id == com.pokedaisy.app.companion.data.GEN4_MOVE_FLY }
        }?.let { (i, m) -> i to m.personality }

        /** The game's own FLY check (the party menu's), then which spots are unlocked, as [targets] answers it. */
        private fun dsTargets(done: (com.pokedaisy.app.companion.data.FlyTargets) -> Unit) {
            val flyer = dsFlyer() ?: return done(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.NO_FLYER))
            val inBattle = telemetry.snapshot.value.inBattle
            val cfg = dsConfig() ?: return done(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.FAILED))
            val spots = cfg.flySpots
            fun finish(t: com.pokedaisy.app.companion.data.FlyTargets) {
                Log.i("pokedaisy", "fly targets (DS): $t")
                val shown = if (inBattle && t.result == com.pokedaisy.app.companion.data.FlyResult.NOT_NOW) t.copy(result = com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE) else t
                runOnUiThread { done(shown) }
            }
            fun poll(seq: Int, left: Int) {
                engine.requestFieldItemJob(inBattle) { calls, _ ->
                    val st = MgbaCore.pkNdsActionStatus(seq)
                    val res = if (st == 1) MgbaCore.pkNdsActionResults(seq) else null
                    when {
                        res != null -> {
                            val result = com.pokedaisy.app.companion.data.gen4FlyResult(res[1])
                            // HeartGold / SoulSilver: the flypoint flags, read here; Platinum: the game's own answers
                            val open = if (cfg.flagsOff >= 0) com.pokedaisy.app.companion.data.gen4OpenFlySpots({ a, n -> calls.read(a, n) }, cfg)
                                else spots.indices.filter { res[2 + it] != 0 }.map { it + 1 }
                            finish(com.pokedaisy.app.companion.data.FlyTargets(result, if (result == com.pokedaisy.app.companion.data.FlyResult.OK) open else emptyList(), flyer.first, flyer.second))
                        }
                        st == 0 && left > 0 -> poll(seq, left - 1)
                        else -> finish(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.NOT_NOW))
                    }
                }
            }
            engine.requestFieldItemJob(inBattle) { _, _ ->
                val seq = MgbaCore.pkNdsAction(MgbaCore.NDS_ACTION_FLY_CHECK, spots.size, 0, 0, 0)
                if (seq == 0) finish(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.NOT_NOW)) else poll(seq, 30)
            }
        }

        override fun targets(done: (com.pokedaisy.app.companion.data.FlyTargets) -> Unit) {
            if (gen5Fly()) {
                if (!::engine.isInitialized || !engine.running) return done(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.FAILED))
                return gen5Targets(done)
            }
            if (dsFly()) {
                if (!::engine.isInitialized || !engine.running) return done(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.FAILED))
                return dsTargets(done)
            }
            sharedFly.targets(done)
        }

        override fun fly(mapsec: Int, targets: com.pokedaisy.app.companion.data.FlyTargets, done: (com.pokedaisy.app.companion.data.FlyResult) -> Unit) {
            val current = if (::engine.isInitialized) engine else null
            fun completed(result: com.pokedaisy.app.companion.data.FlyResult) {
                if (result == com.pokedaisy.app.companion.data.FlyResult.OK && current != null && engine === current && current.running) {
                    com.pokedaisy.app.companion.FeatureTips.used(TipId.FLY)
                }
                done(result)
            }
            if (gen5Fly()) {
                // [mapsec] is a town's place here (gen5OpenFlySpots); the flyer must still be in its slot.
                val cfg = gen5Config()
                val spot = cfg?.let { com.pokedaisy.app.companion.data.gen5FlySpotAt(it, mapsec, gen5Spots()) }
                val flyer = dsFlyer()
                if (spot == null || !::engine.isInitialized || !engine.running) return done(com.pokedaisy.app.companion.data.FlyResult.FAILED)
                if (flyer == null || flyer.first != targets.flyerSlot || flyer.second != targets.flyerPersonality) {
                    return done(com.pokedaisy.app.companion.data.FlyResult.NO_FLYER)
                }
                closeCompanionWindow()
                // The job's result 1 is the flight's event: none when the game's own check said no (walked indoors since).
                var refused = false
                dsAction(MgbaCore.NDS_ACTION_FLY, intArrayOf(flyer.first, spot.mapHeader, 0, 0), ran = { res ->
                    if (res != null && res[1] == 0) {
                        refused = true
                        com.pokedaisy.app.companion.data.ItemUseResult.FAILED
                    } else com.pokedaisy.app.companion.data.ItemUseResult.USED
                }) { o ->
                    completed(when (o.result) {
                        com.pokedaisy.app.companion.data.ItemUseResult.USED -> com.pokedaisy.app.companion.data.FlyResult.OK
                        com.pokedaisy.app.companion.data.ItemUseResult.IN_BATTLE -> com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE
                        com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW -> com.pokedaisy.app.companion.data.FlyResult.NOT_NOW
                        else -> if (refused) com.pokedaisy.app.companion.data.FlyResult.CANT_FLY_HERE else com.pokedaisy.app.companion.data.FlyResult.FAILED
                    })
                }
                return
            }
            if (dsFly()) {
                // [mapsec] is a fly spot's spawn id here; the flyer must still be in its slot (the party can change).
                val spot = dsConfig()?.flySpots?.getOrNull(mapsec - 1)
                val flyer = dsFlyer()
                if (spot == null || !::engine.isInitialized || !engine.running) return done(com.pokedaisy.app.companion.data.FlyResult.FAILED)
                if (flyer == null || flyer.first != targets.flyerSlot || flyer.second != targets.flyerPersonality) {
                    return done(com.pokedaisy.app.companion.data.FlyResult.NO_FLYER)
                }
                closeCompanionWindow()
                dsAction(MgbaCore.NDS_ACTION_FLY, intArrayOf(flyer.first, spot.mapHeader, spot.x, spot.z)) { o ->
                    completed(when (o.result) {
                        com.pokedaisy.app.companion.data.ItemUseResult.USED -> com.pokedaisy.app.companion.data.FlyResult.OK
                        com.pokedaisy.app.companion.data.ItemUseResult.IN_BATTLE -> com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE
                        com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW -> com.pokedaisy.app.companion.data.FlyResult.NOT_NOW
                        else -> com.pokedaisy.app.companion.data.FlyResult.FAILED
                    })
                }
                return
            }
            sharedFly.fly(mapsec, targets, done)
        }


    }

    /** The loaded ROM is a Game Boy / Color cart (no GBA header, no L/R). */
    @Volatile private var romIsGameBoy = false

    private fun followerSupported() = ::engine.isInitialized && engine.playback.followerSupported

    /** DAY AND NIGHT works here: a GBA game (not a Game Boy or DS one) that doesn't change with the time itself. */
    /** HIDE BATTLE MENU works in this game: FireRed, LeafGreen, Emerald, Ruby or Sapphire's engine (their text box, pk_wide.c's
     * menuHideBegin; Ruby / Sapphire's cursor sprites hidden with it, the sign drawn in the hidden menu's own tiles) whose
     * battles the companion's buttons can steer (the battle input read). */
    private fun battleMenuHideSupported() = !romIsGameBoy && !romIsNds && romBlocked == null && ::engine.isInitialized &&
        listOf("BPR", "BPG", "BPE", "AXV", "AXP").any { romCode.startsWith(it) } &&
        com.pokedaisy.app.companion.data.activeGame.let {
            it == com.pokedaisy.app.companion.data.GameKind.FIRERED || it == com.pokedaisy.app.companion.data.GameKind.EMERALD
        } && com.pokedaisy.app.companion.data.activeNativeConfig?.hasBattleInputAddrs == true &&
        // The D-pad steers the hidden menu by its cursor (battleSteerTakes): without the cursor's address it would move an
        // unseen 2 x 2 under the companion's differently shaped buttons.
        engine.battleCursorAddrs?.menus == BattleInputController.MenuCells.GEN3

    /** HIDE BATTLE MENU to the engine: only with the companion on the other screen, where its battle buttons are. The sign
     * lights the bottom screen: SWAP SCREENS is there to put the game on top (a device whose main display is its bottom one). */
    private fun syncBattleMenuHide() {
        if (!::engine.isInitialized) return
        val on = Prefs(this).hideBattleMenu && battleMenuHideSupported() && !debugMirror && !companionClosed &&
            !companionOverGame.value && presentation?.isShowing == true
        engine.battleTypeFlagsAddr = com.pokedaisy.app.companion.data.activeNativeConfig?.battleTypeFlags ?: 0L
        engine.battleMenuHide = if (on) 1 else 0
    }

    private fun dayNightSupported() = ::engine.isInitialized && engine.playback.dayNightSupported
    /** The game running is a DS cart (melonDS): two screens, touch, X / Y ([loadRom]). */
    @Volatile private var romIsNds = false
    /** The game running is a 3DS cart (Azahar, [n3ds]): no EmulatorEngine game (the engine stays idle), DS LAYOUT for
     * its two screens ([load3ds], docs/3DS_GAMES_PLAN.md). */
    @Volatile private var romIs3ds = false
    /** The 3DS game: Azahar's thread, its two surfaces, input, states, the companion's snapshot. */
    private var n3ds: com.pokedaisy.app.n3ds.N3dsSession? = null
    /** ONE SCREEN's 3DS touch screen is up, over a dim of the game (L2, the TOUCH button). */
    private val n3dsFloating = androidx.compose.runtime.mutableStateOf(false)
    private lateinit var n3dsDim: View
    private lateinit var n3dsTouchButton: android.widget.TextView
    /** A DS game on DSperate (DS CORE, companion.DsCore): no EXPAND, DS RESOLUTION, PC / FLY calls or DS CPU BOOST there.
     * Picked as the game loads and again on RESTART GAME (pickDsCore); the core keeps it until then. */
    @Volatile private var dsperate = false
    /** The DS core picked for the loaded DS game (DsCore.CORE_*; [dsperate] = it isn't melonDS). */
    @Volatile private var dsCoreId = DsCore.CORE_MELONDS
    /** The DS game's generated world (its wild rates for WILD ENCOUNTERS), or null. */
    /** The cart's own encounter rates, by map, for WILD ENCOUNTERS back on (MgbaCore.pkNdsWildRates). */
    @Volatile private var dsWildRates: (() -> IntArray)? = null

    /** The loaded ROM's header code / revision byte, for battle input and language selection. */
    @Volatile private var romCode = ""
    @Volatile private var romRev = -1
    /** The loaded cart is Origin HeartGold's ([com.pokedaisy.app.companion.data.OriginHeartGold]: a Japanese header). */
    @Volatile private var romOrigin = false

    /** Backs the bottom-screen "Settings" tab. Unlike SettingsActivity (which
     * only writes Prefs, picked up next launch), this also live-applies to the
     * already-running engine/touch controls since the game keeps running. */
    private val companionSettings by lazy { createCompanionSettings() }
    private fun createCompanionSettings(): com.pokedaisy.app.companion.CompanionSettings {
        val stored = PrefsCompanionSettings(Prefs(this), languageRomCode = { romCode }).apply {
            changed = { change ->
                when (change) {
                    PrefsCompanionSettings.Change.PICTURE -> runOnUiThread { syncGameScreen() }
                    PrefsCompanionSettings.Change.FIELD_OPTIONS -> currentGameOptions()?.let { applyGameOptions(it) }
                    PrefsCompanionSettings.Change.PLAYBACK -> Unit
                }
            }
        }
        return object : com.pokedaisy.app.companion.CompanionSettings,
            com.pokedaisy.app.companion.StoredCompanionSettings by stored {
        override fun setFfMaxSpeed(v: Float) {
            stored.setFfMaxSpeed(v)
            if (::engine.isInitialized) engine.ffMaxSpeed = ffMaxSpeed
        }
        override val gameSpeed get() = if (::engine.isInitialized) engine.gameSpeed else 1f
        override val gameSpeeds get() = if (::engine.isInitialized) engine.gameSpeeds else super<com.pokedaisy.app.companion.CompanionSettings>.gameSpeeds
        override fun setGameSpeed(v: Float) {
            if (!::engine.isInitialized) return
            engine.setGameSpeed(v)
            Prefs(this@PokeDaisyActivity).setSpeedIndexFor(romKey, engine.speedIndex)
        }
        override fun setBattleSpeed(v: Float) {
            stored.setBattleSpeed(v)
            if (::engine.isInitialized) engine.battleSpeed = battleSpeed
        }
        override fun setTextSpeed(v: Float) {
            stored.setTextSpeed(v)
            if (::engine.isInitialized) engine.textSpeed = textSpeed
        }
        override fun setMenuSpeed(v: Float) {
            stored.setMenuSpeed(v)
            if (::engine.isInitialized) engine.menuSpeed = menuSpeed
        }
        override fun setBikeSpeed(v: Float) {
            stored.setBikeSpeed(v)
            if (::engine.isInitialized) engine.bikeSpeed = bikeSpeed
        }
        override fun setSurfSpeed(v: Float) {
            stored.setSurfSpeed(v)
            if (::engine.isInitialized) engine.surfSpeed = surfSpeed
        }
        override val fieldMovesReadable get() = ::engine.isInitialized && engine.fieldMovesKnown
        // A DS game: its own field read, known once it reports the cart (MgbaCore.FIELD_KNOWN).
        override val fieldReadable get() = fieldGame != WideScreen.OFF ||
            romIsNds && ::engine.isInitialized && engine.fieldState and MgbaCore.FIELD_KNOWN != 0
        // Ruby / Sapphire tell indoor maps by their type, so they do not expose RUN INDOORS.
        override val runIndoorsSupported get() = fieldGame != WideScreen.OFF && com.pokedaisy.app.companion.data.GameField.supportsRunIndoors(romCode)
        override val wildEncountersSupported get() = (fieldGame != WideScreen.OFF && currentGameOptions()?.wildFlagFound == true) ||
            gbField() != null || romIsNds && ::engine.isInitialized && engine.fieldState and MgbaCore.FIELD_KNOWN != 0
        override val tradeEvolutionsSupported get() = tradeEvoFns() != null
        override val flightArea get() = Prefs(this@PokeDaisyActivity).flightArea
        override fun setFlightArea(mode: com.pokedaisy.app.companion.FlightAreaMode) {
            Prefs(this@PokeDaisyActivity).flightAreaId = mode.id
            // Back to the seen maps: what a flight anywhere reached is behind the player, achievements go on.
            if (mode == com.pokedaisy.app.companion.FlightAreaMode.SEEN) RetroAchievements.setFlightAnywhere(false)
            syncFlight()
        }
        override val flyStyle get() = Prefs(this@PokeDaisyActivity).flyStyle
        override fun setFlyStyle(style: com.pokedaisy.app.companion.FlyStyle) { Prefs(this@PokeDaisyActivity).flyStyleId = style.id }
        override val flightVehicle get() = this@PokeDaisyActivity.flightVehicle()
        override fun setFlightVehicle(v: com.pokedaisy.app.companion.data.FlightVehicle) {
            Prefs(this@PokeDaisyActivity).flightVehicleId = v.id
            syncFlight()   // the next frame is drawn in it, mid-flight too
        }
        override val flyStyleSupported get() = flightSupport() != null
        override fun answerFlyStyle(style: com.pokedaisy.app.companion.FlyStyle?) = this@PokeDaisyActivity.answerFlyStyle(style)
        override fun setReusableTms(on: Boolean) {
            if (!coopRefuses()) stored.setReusableTms(on)
        }
        override val reusableTmsSupported get() = currentGameOptions()?.reusableTmsSupported == true || dsInstantTextWorks()
        override val instantTextSupported get() = currentGameOptions()?.instantTextSupported == true || dsInstantTextWorks()
        override fun setHmFree(on: Boolean) {
            if (!coopRefuses()) stored.setHmFree(on)
        }
        override val hmFreeSupported get() = currentGameOptions()?.hmFreeSupported == true
        override fun setLowHpBeep(on: Boolean) {
            if (!coopRefuses()) stored.setLowHpBeep(on)
        }
        override val lowHpBeepSupported get() = currentGameOptions()?.lowHpBeepSupported == true
        override fun setFieldPoison(on: Boolean) {
            if (!coopRefuses()) stored.setFieldPoison(on)
        }
        override val fieldPoisonSupported get() = currentGameOptions()?.fieldPoisonSupported == true
        override fun setRunningShoes(on: Boolean) {
            if (!coopRefuses()) stored.setRunningShoes(on)
        }
        override val runningShoesSupported get() = currentGameOptions()?.runningShoesSupported == true
        override fun setExpShare(mode: com.pokedaisy.app.companion.data.ExpShare.Mode) {
            if (!coopRefuses()) stored.setExpShare(mode)
        }
        override val expShareSupported get() = currentGameOptions()?.expShareSupported == true
        override fun setLevelCap(on: Boolean) {
            if (!coopRefuses()) stored.setLevelCap(on)
        }
        override val levelCapSupported get() = currentGameOptions()?.levelCapSupported == true
        override val levelCapLevel get() = currentGameOptions()?.levelCapNow?.level
        override val repelRefill get() = Prefs(this@PokeDaisyActivity).repelRefill
        override fun setRepelRefill(on: Boolean) {
            if (::engine.isInitialized) engine.playback.autoRefill.set(on)
            else Prefs(this@PokeDaisyActivity).repelRefill = on
        }
        override val repelRefillSupported get() = repelRefillSupported()
        override val shinyAlert get() = Prefs(this@PokeDaisyActivity).shinyAlert
        override fun setShinyAlert(on: Boolean) {
            if (::engine.isInitialized) engine.playback.shinyAlert.set(on)
            else Prefs(this@PokeDaisyActivity).shinyAlert = on
        }
        override val shinyHuntCount get() = if (::engine.isInitialized) engine.playback.shinyAlert.count else 0
        override val shinyAlertSupported get() = ::engine.isInitialized && engine.playback.shinyAlertSupported(telemetry.snapshot.value.game)
        override val battleRetryAsking get() = hudEngine.value?.playback?.battleRetry?.asking == true
        override val battleRetry get() = Prefs(this@PokeDaisyActivity).battleRetry
        override fun setBattleRetry(on: Boolean) {
            if (::engine.isInitialized) engine.playback.battleRetry.set(on)
            else Prefs(this@PokeDaisyActivity).battleRetry = on
        }
        override fun answerBattleRetry(retry: Boolean) = this@PokeDaisyActivity.answerBattleRetry(retry)
        override val itemPopups get() = Prefs(this@PokeDaisyActivity).itemPopups
        override fun setItemPopups(on: Boolean) {
            Prefs(this@PokeDaisyActivity).itemPopups = on
            itemToastOn.value = on
        }
        override val lastBallButton get() = Prefs(this@PokeDaisyActivity).lastBallButton
        override fun setLastBallButton(on: Boolean) {
            val ball = if (::engine.isInitialized) engine.playback.lastBall else null
            if (ball != null) ball.set(on) else Prefs(this@PokeDaisyActivity).lastBallButton = on
        }
        override val dsResolution get() = if (romIsNds) Prefs(this@PokeDaisyActivity).dsResolution else null
        override val dsGpuAvailable get() = GlCaps.gles32(this@PokeDaisyActivity) && MgbaCore.pkNdsGpuSupported() != 0
        override val dsMelonDs get() = !dsperate
        override val dsCoreRunning get() = dsCoreId
        override val dsCore get() = if (romIsNds) Prefs(this@PokeDaisyActivity).dsCore else null
        override fun setDsCore(core: DsCore) {
            Prefs(this@PokeDaisyActivity).dsCore = core
        }
        override fun setDsResolution(scale: Int) {
            Prefs(this@PokeDaisyActivity).dsResolution = scale
            if (::engine.isInitialized) engine.ndsScale = scale
        }
        override val dsCpuBoost get() = if (romIsNds) Prefs(this@PokeDaisyActivity).dsCpuBoost else null
        override fun setDsCpuBoost(on: Boolean) {
            Prefs(this@PokeDaisyActivity).dsCpuBoost = on
            if (::engine.isInitialized) engine.ndsCpuBoost = on
        }
        override val dsTouchFill get() = if (romIsNds && secondScreen() != null) Prefs(this@PokeDaisyActivity).dsTouchFill else null
        override fun setDsTouchFill(on: Boolean) {
            Prefs(this@PokeDaisyActivity).dsTouchFill = on
            dsBottom?.fill = on
        }
        override val dsLayout get() = if (romIsNds && secondScreen() == null) Prefs(this@PokeDaisyActivity).dsLayout else null
        override fun setDsLayout(layout: com.pokedaisy.app.companion.DsLayout) {
            Prefs(this@PokeDaisyActivity).dsLayout = layout
            runOnUiThread { syncGameScreen() }
        }
        override val nuzlockeSupported get() = ::engine.isInitialized && engine.playback.nuzlockeSupported
        override val nuzlocke get() = com.pokedaisy.app.companion.Nuzlocke.on
        override fun setNuzlocke(on: Boolean) = com.pokedaisy.app.companion.Nuzlocke.setOn(on)
        override val shinyOdds get() = if (::engine.isInitialized) engine.shiny.odds else com.pokedaisy.app.companion.data.ShinyOdds.NORMAL
        override fun setShinyOdds(odds: Int) {
            if (::engine.isInitialized) engine.shiny.setOdds(odds)
        }
        override val shinyOddsChoices get() = com.pokedaisy.app.companion.data.ShinyOdds.choices(BuildConfig.DEBUG)
        override val shinyOddsSupported get() = ::engine.isInitialized && engine.shiny.supported
        override val playerLookSupported get() = lookSession.supported
        override val playerLooks get() = lookSession.choices
        override val playerLook get() = lookSession.selected
        override fun playerLookLabel(name: String) = lookSession.costumeLabel(name)
            ?: com.pokedaisy.app.companion.data.LookChar.byName(name)?.label ?: name
        override val playerLookOwn get() = lookSession.own
        override fun setPlayerLook(look: String?) {
            if (coopRefuses()) return
            lookSession.select(look)
        }
        override val textLooks get() = lookSession.textChoices
        override val textLook get() = lookSession.textSelected
        override fun setTextLook(style: String?) {
            if (coopRefuses()) return
            lookSession.selectText(style)
        }
        override val followerSupported get() = followerSupported()
        override val followerReady get() = ::engine.isInitialized && engine.playback.follower?.ready == true
        override fun setFollower(on: Boolean) {
            if (::engine.isInitialized) engine.playback.follower?.set(on)
        }
        override val dayNightSupported get() = dayNightSupported()
        override val rivalLookSupported get() = lookSession.rivalSupported
        override val rideVehicle get() = lookSession.vehicle
        override fun setRideVehicle(v: com.pokedaisy.app.companion.data.RideVehicle) {
            lookSession.selectRide(v)
        }
        override fun setRivalLook(on: Boolean) {
            if (coopRefuses()) return
            stored.setRivalLook(on)
            lookSession.apply()
        }
        override val autoRunBuiltIn get() =
            com.pokedaisy.app.companion.data.activeGame in com.pokedaisy.app.companion.data.GAMES_WITH_OWN_AUTO_RUN
        override val reusableTmsBuiltIn get() =
            com.pokedaisy.app.companion.data.activeGame in com.pokedaisy.app.companion.data.ReusableTms.BUILT_IN
        override val ffToggled get() = if (romIs3ds) n3dsFfOn else if (::engine.isInitialized) engine.fastForwardToggled else false
        override fun setFfToggled(on: Boolean) {
            if (romIs3ds) {
                n3dsFfOn = on
                n3ds?.setSpeed(if (on) N3DS_FF_PERCENT else 100)
                if (::touchControls.isInitialized) touchControls.ffOn = on
            } else if (::engine.isInitialized) engine.setFastForwardToggled(on)
        }
        override fun setFfMusicMode(mode: FfMusicMode) {
            stored.setFfMusicMode(mode)
            if (::engine.isInitialized) engine.ffMusicMode = mode
        }
        override val rewindSupported get() = !romIsNds
        override fun setRewind(on: Boolean) {
            stored.setRewind(on)
            if (::engine.isInitialized) {
                gameActions.release(Hotkeys.Action.REWIND_HOLD)
                engine.rewindEntries = rewindEntries(on)
            }
            runOnUiThread { syncTouchExtras() }
        }
        override fun setFfMode(mode: FfMode) {
            stored.setFfMode(mode)
            if (::engine.isInitialized) engine.ffMode = mode
        }
        override val touchStyle get() = Prefs(this@PokeDaisyActivity).touchControlsStyle
        override fun setTouchStyle(style: com.pokedaisy.app.companion.TouchControlsStyle) {
            Prefs(this@PokeDaisyActivity).touchControlsStyle = style
            runOnUiThread { syncTouchControls() }
        }
        override val touchStage get() = measuredTouchStage()
        override val foldMode: com.pokedaisy.app.companion.FoldMode?
            get() = Prefs(this@PokeDaisyActivity).let { p -> if (p.foldSeen && secondScreen() == null) p.foldMode else null }
        override fun setFoldMode(mode: com.pokedaisy.app.companion.FoldMode) {
            Prefs(this@PokeDaisyActivity).foldMode = mode
            runOnUiThread { if (::sidePanel.isInitialized) syncSingleScreen(singleScreen) }
        }
        override val tabletopControlsBottom: Boolean?
            get() = Prefs(this@PokeDaisyActivity).let { p -> if (p.foldSeen && secondScreen() == null && p.foldLayout) p.tabletopControlsBottom else null }
        override fun setTabletopControlsBottom(on: Boolean) {
            Prefs(this@PokeDaisyActivity).tabletopControlsBottom = on
            runOnUiThread { if (::sidePanel.isInitialized) syncSingleScreen(singleScreen) }
        }
        override val touchSkins get() = skinStore.list()
        override fun touchPreviewShot() = newestStateShot()
        override fun editTouchLayout() = runOnUiThread { openTouchEditor() }
        override val touchRevision get() = touchRev.intValue
        override val touchControlsShown get() = ::touchControls.isInitialized && touchControls.visibility == View.VISIBLE
        override fun gbaControlBindings() = gameBindings.gameButtons()
        override val keyCapture get() = this@PokeDaisyActivity.keyCapture
        override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {
            gameBindings.setGameButton(btn, keyName)
        }
        override fun clearGbaControlBinding(btn: GbaControls.Btn) {
            gameBindings.clearGameButton(btn)
        }
        override fun resetGbaControls() {
            GbaControls.resetBindings(controlsDir)
            input.setControls(GbaControls.load(controlsDir))
        }
        override fun resetHotkeys() {
            Hotkeys.resetBindings(controlsDir)
            hotkeys = Hotkeys.load(controlsDir)
        }
        override val buttonGroup get() = if (romIsNds) GbaControls.Group.DS else GbaControls.Group.GBA
        override val hotkeysEnabled get() = hotkeysOn
        override fun setHotkeysEnabled(on: Boolean) {
            stored.setHotkeysEnabled(on)
            setHotkeysOn(on)
        }
        override fun hotkeyBindings() = gameBindings.hotkeys()
        override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {
            gameBindings.setHotkey(action, keyNames, takeFrom)
        }
        override fun restartGame() = this@PokeDaisyActivity.restartGame()
        override fun closeGame() {
            exitGame()   // drives the normal onPause()/onStop() lifecycle, which already suspends+saves
        }
        override fun openDiscord() {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.pokedaisy.app.companion.Discord.URL))) }
        }
        override fun openUrl(url: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        override val canCloseCompanion get() = secondScreen() != null
        override fun closeCompanion() {
            runOnUiThread {
                val target = secondScreen()
                val swap = target != null && Screens.swapped(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), target) && !debugMirror && !romIsNds
                this@PokeDaisyActivity.companionClosed = true
                syncPresentation()
                // Swapped, the companion is this activity's screen (the main one): the game moves to the second
                // screen as this activity's own content, its touch and keys its own, and the main screen is free.
                if (swap && target != null) moveToDisplay(target.displayId)
                showHud(com.pokedaisy.app.companion.i18n.tr("Companion closed. Press BACK to bring it back."))
            }
        }
        override val companionClosed get() = companionOverGame.value
        override fun openCompanion() = runOnUiThread { reopenCompanion() }
        override fun setTweak(key: String, on: Boolean) = Prefs(this@PokeDaisyActivity).setTweak(key, on)
        override val gameVolume get() = GameVolume.percent
        override fun setGameVolume(percent: Int) = this@PokeDaisyActivity.setGameVolume(percent, GameVolume.muted)
        override val gameMuted get() = GameVolume.muted
        override fun setGameMuted(on: Boolean) = this@PokeDaisyActivity.setGameVolume(GameVolume.percent, on)
        override val clickSound get() = clickSoundOn
        override fun setClickSound(on: Boolean) {
            stored.setClickSound(on)
            clickSoundOn = on
        }
        override fun setStatusBar(on: Boolean) {
            stored.setStatusBar(on)
            runOnUiThread { syncGameScreen() }
        }
        override val fieldHud get() = Prefs(this@PokeDaisyActivity).fieldHud
        override fun setFieldHud(on: Boolean) {
            stored.setFieldHud(on)
            runOnUiThread { syncGameScreen() }
        }
        override val hideBattleMenu get() = if (secondScreen() != null && battleMenuHideSupported()) Prefs(this@PokeDaisyActivity).hideBattleMenu else null
        override fun setHideBattleMenu(on: Boolean) {
            Prefs(this@PokeDaisyActivity).hideBattleMenu = on
            syncBattleMenuHide()
        }
        override val battleButtons get() = Prefs(this@PokeDaisyActivity).battleButtons
        override fun setBattleButtons(on: Boolean) {
            stored.setBattleButtons(on)
            runOnUiThread { syncGameScreen() }
        }
        override val fieldHudStyle get() = Prefs(this@PokeDaisyActivity).fieldHudStyle
        override fun setFieldHudStyle(style: com.pokedaisy.app.companion.FieldHudStyle) {
            stored.setFieldHudStyle(style)
            runOnUiThread { syncGameScreen() }
        }
        override fun setQuickMenuActions(actions: Set<com.pokedaisy.app.companion.QuickAction>) {
            stored.setQuickMenuActions(actions)
            quickShown.value = actions
        }
        override fun setShowPerformance(on: Boolean) {
            stored.setShowPerformance(on)
            runOnUiThread { syncGameScreen() }
        }
        // RetroAchievements reads Prefs at each event, so these apply to the running game at once.
        override val raPopups get() = Prefs(this@PokeDaisyActivity).raPopups
        override fun setRaPopups(on: Boolean) { Prefs(this@PokeDaisyActivity).raPopups = on }
        override val raProgressPopups get() = Prefs(this@PokeDaisyActivity).raProgressPopups
        override fun setRaProgressPopups(on: Boolean) { Prefs(this@PokeDaisyActivity).raProgressPopups = on }
        override fun setStatusBarOnCompanion(on: Boolean) {
            stored.setStatusBarOnCompanion(on)
            runOnUiThread { syncGameScreen() }
        }
        override fun setGameAspect(aspect: com.pokedaisy.app.companion.GameAspect) {
            if (aspect == com.pokedaisy.app.companion.GameAspect.WIDE) com.pokedaisy.app.companion.FeatureTips.used(TipId.EXPAND)
            stored.setGameAspect(aspect)
        }
        override val wideSupported get() = wideGame != WideScreen.OFF
        // SETTINGS > QUICK MENU lists only what this game's menu can show (GitHub #1086: HEAL switched on in Emerald
        // Seaglass, which has no POKéMON CENTER call, and no tile). FLASH comes and goes with the party, so it stays.
        override val quickMenuSupportedActions: Set<com.pokedaisy.app.companion.QuickAction>
            get() = com.pokedaisy.app.companion.QuickAction.entries.filterTo(LinkedHashSet()) {
                it == com.pokedaisy.app.companion.QuickAction.FLASH || quickActions.offered(it)
            }
        override val lookAround get() = Prefs(this@PokeDaisyActivity).lookAround
        override fun setLookAround(on: Boolean) {
            Prefs(this@PokeDaisyActivity).lookAround = on
            view.camera.lookAround = on
        }
        override fun feelVibration(level: Int) = TouchHaptics.buzz(this@PokeDaisyActivity, level)
        override val zoomSteps get() = view.camera.zoomFactors()
        override val zoomLevel get() = view.camera.level
        override fun setZoomLevel(level: Int) { runOnUiThread { zoomTo(level) } }
        override val expandZoomOnText get() = Prefs(this@PokeDaisyActivity).expandZoomOnText
        override fun setExpandZoomOnText(on: Boolean) {
            Prefs(this@PokeDaisyActivity).expandZoomOnText = on
            view.camera.zoomOnText = on
        }
        override val expandOffer get() = expandOfferUp.value
        override fun answerExpandOffer(turnOn: Boolean) {
            val prefs = Prefs(this@PokeDaisyActivity)
            // Either answer settles both offers: the Library's too (never asked twice).
            prefs.expandGameOfferSeen = true
            prefs.expandIntroSeen = true
            expandOfferUp.value = false
            if (turnOn) setGameAspect(com.pokedaisy.app.companion.GameAspect.WIDE)
        }
        override val showsTips get() = tipsOnCompanion.value
        override fun tipKeys(tip: TipId) = this@PokeDaisyActivity.tipKeys(tip)
        override fun tryTip(tip: TipId) = this@PokeDaisyActivity.tryTip(tip)
        override val overlayChoices get() = com.pokedaisy.app.overlay.overlayChoices(overlayStore).map { (c, label) -> c.key to label }
        override val overlay get() = Prefs(this@PokeDaisyActivity).overlay
        override fun setOverlay(key: String) {
            Prefs(this@PokeDaisyActivity).overlay = key
            runOnUiThread { syncGameScreen() }
        }
        override val smoothingAvailable get() = GlCaps.gles3(this@PokeDaisyActivity)
        override fun setLightCompanion(mode: com.pokedaisy.app.companion.LightCompanionMode) {
            com.pokedaisy.app.companion.LightCompanion.mode = mode
            Prefs(this@PokeDaisyActivity).lightCompanion = mode.id
            runOnUiThread { syncGameScreen() }
        }
        // The display itself, not `presentation != null`: a swap composes the companion
        // here before the new presentation is assigned, and the remembered rows then lost
        // SWAP SCREENS until the page was rebuilt.
        override val hasSecondScreen get() = Screens.second(this@PokeDaisyActivity) != null
        override val singleScreen get() = Prefs(this@PokeDaisyActivity).singleScreen
        override fun setSingleScreen(on: Boolean) {
            Prefs(this@PokeDaisyActivity).singleScreen = on
            // Off again: the companion that comes back on the second screen opens on SETTINGS, on this row.
            companionStartTab = "SETTINGS"
            companionStartRow = "SINGLE SCREEN"
            runOnUiThread {
                syncPresentation()
                // On: the companion leaves the second screen; it opens over the game, so it doesn't just vanish.
                if (on && ::sidePanel.isInitialized && sidePanel.enabled) sidePanel.openPanel()
            }
        }
        override val swapScreens get() = Screens.swapped(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity))
        override val portraitUnderGame get() =
            if (::portraitPanel.isInitialized && portraitPanel.enabled && !portraitPanel.tabletop) Prefs(this@PokeDaisyActivity).portraitCompanionUnderGame else null
        override val uprightUnderGame get() =
            if (secondScreen() == null) Prefs(this@PokeDaisyActivity).portraitCompanionUnderGame else null
        override fun setPortraitUnderGame(on: Boolean) {
            Prefs(this@PokeDaisyActivity).portraitCompanionUnderGame = on
            runOnUiThread { portraitPanel.refresh(); syncTouchControls() }
        }
        override val companionWindow get() =
            if (::sidePanel.isInitialized && sidePanel.enabled) Prefs(this@PokeDaisyActivity).companionWindow else null
        override fun setCompanionWindow(on: Boolean) {
            Prefs(this@PokeDaisyActivity).companionWindow = on
            runOnUiThread { sidePanel.setWindow(on) }
        }
        override val companionTab get() =
            if (::sidePanel.isInitialized && sidePanel.enabled) Prefs(this@PokeDaisyActivity).companionTab else null
        override fun setCompanionTab(on: Boolean) {
            Prefs(this@PokeDaisyActivity).companionTab = on
            runOnUiThread { sidePanel.openTabShown = on; companionButtonShown.value = on }
        }
        override fun setSwapScreens(on: Boolean) {
            Screens.setSwapped(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), on)
            // The companion that asked moves screens: it comes back on SETTINGS, on SWAP SCREENS.
            companionStartTab = "SETTINGS"
            companionStartRow = "SWAP SCREENS"
            runOnUiThread { syncPresentation() }
        }
        override fun setShowHints(on: Boolean) {
            stored.setShowHints(on)
            battleHints.value = Prefs(this@PokeDaisyActivity).let { it.showHints && it.battleInfo }
        }

        override fun setDarkMode(v: Int) { Prefs(this@PokeDaisyActivity).darkMode = v }
        override fun setAppFont(v: Int) { Prefs(this@PokeDaisyActivity).appFont = v }
        override fun setAppTheme(id: Int) {
            com.pokedaisy.app.companion.ui.theme.pickTheme(com.pokedaisy.app.companion.ui.theme.themeById(id))
        }
        override fun setBattleInfo(on: Boolean) {
            stored.setBattleInfo(on)
            battleHints.value = Prefs(this@PokeDaisyActivity).let { it.showHints && it.battleInfo }
        }
        override fun setRaIcons(on: Boolean) {
            stored.setRaIcons(on)
            com.pokedaisy.app.companion.ui.AchievementIcons.shown = on
        }
        override val battleStyle get() = this@PokeDaisyActivity.battleStyle.value
        override fun setBattleStyle(style: com.pokedaisy.app.companion.BattleStyle) {
            stored.setBattleStyle(style)
            this@PokeDaisyActivity.battleStyle.value = style
        }
        override val cheats get() = gameCheats()?.list.orEmpty()
        override fun setCheatsEnabled(on: Boolean) {
            if (coopRefuses()) return
            if (coopCheatsRefuse(on)) return
            stored.setCheatsEnabled(on)
            syncCheats()
        }
        override fun setCheatEnabled(index: Int, on: Boolean) {
            if (coopRefuses()) return
            if (coopCheatsRefuse(on)) return
            gameCheats()?.setEnabled(index, on)
        }
        override val gameName get() = romBlocked?.title ?: com.pokedaisy.app.companion.data.activeGame.displayName()
        override val gameBlocked get() = romBlocked != null
        override val romFileName get() = rom?.name ?: "(none)"
        override val coopEnabled get() = coopOn
        override val coopState get() = com.pokedaisy.app.companion.coop.CoopSession.link.state.value
        override val coopLock get() = coopLockState.value?.ui?.value
        override val coopLockActions get() = coopLockState.value
        override val coopProposalHere get() = secondScreen() != null
        override val coopPairs get() = com.pokedaisy.app.companion.coop.CoopSession.pairsList(this@PokeDaisyActivity)
        override val coopRelayValue get() = Prefs(this@PokeDaisyActivity).let {
            com.pokedaisy.app.companion.coop.CoopRelay.rowValue(it.coopRelayUrl, BuildConfig.COOP_RELAY_URL)
        }
        override val coopRelayOnly get() = Prefs(this@PokeDaisyActivity).coopRelayOnly
        override var coopNearbyRange: Int
            get() = Prefs(this@PokeDaisyActivity).coopNearbyRange
            set(v) { Prefs(this@PokeDaisyActivity).coopNearbyRange = v }
        override fun setCoopRelayOnly(on: Boolean) {
            val prefs = Prefs(this@PokeDaisyActivity)
            CoopSession.bind(this@PokeDaisyActivity, prefs).changeRelay { prefs.coopRelayOnly = on }
        }
        override fun coopUnpair(save: com.pokedaisy.app.companion.coop.CoopSaveId) =
            com.pokedaisy.app.companion.coop.CoopSession.unpair(this@PokeDaisyActivity, save)
        }
    }

    /** Reverses [Hotkeys.keyName] — "BUTTON_A"/"Z"/etc. back to a keyCode, for the companion's GAME BUTTONS
     * (its capture hands over key names, see [CompanionSettings]). */
    private fun keyCodeForName(name: String): Int? {
        val code = KeyEvent.keyCodeFromString("KEYCODE_$name")
        return code.takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
    }

    private val hideHud = Runnable { hud.visibility = View.GONE }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncPresentation()
        override fun onDisplayRemoved(displayId: Int) = syncPresentation()
        override fun onDisplayChanged(displayId: Int) = syncPresentation()
    }
    /** FeatureFlags.coop, read at create (the settings screen asks for it often). */
    private var coopOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coopOn = FeatureFlags.coop(this)
        RomFolder.useBestEffortStore(this)
        BestEffortShare.flush(this)   // a shared match that couldn't go out last time
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()
        RetroAchievements.init(this)
        RetroAchievements.onUnlockSound = { unlockSound?.play() }
        AchievementBadges.dir = cacheDir.toOkioPath() / "ra-badges"
        AchievementBadges.download = ::downloadBadge
        // A popup goes to the companion the player can see, else over the game (a DS game's touch screen hides it: #500).
        com.pokedaisy.app.companion.ui.AchievementPopupRoute.shownProbe = {
            val v = androidx.compose.ui.platform.LocalView.current
            remember(v) { { v.isShown } }
        }
        lookGender.launch { telemetry.snapshot.collect { lookSession.observe(it) } }
        // HIDE BATTLE MENU follows the game being detected, the companion and the second screen.
        lookGender.launch {
            while (true) {
                // HIDE BATTLE MENU follows the game being detected, the companion closed / reopened, a second screen.
                syncBattleMenuHide()
                kotlinx.coroutines.delay(500)
            }
        }
        Prefs(this).let { com.pokedaisy.app.companion.FeatureTips.init(it.tipStore, it.tips) }
        gameTips.attach()

        // Debug-only mirror of the companion UI onto the main display, for
        // Claude's own testing use (screenshotting/tapping over adb): the
        // Thor's second screen (where this normally lives, via
        // DualScreenPresentation) can't be screencap'd - FLAG_SECURE is set at
        // the hardware/display-device level for that panel specifically,
        // confirmed via `dumpsys display` (not something this app requests;
        // there is no FLAG_SECURE call anywhere in this codebase). The main
        // display doesn't have that restriction, so mirroring here makes the
        // companion UI screenshot-able.
        //
        // Gated on a marker file, not just BuildConfig.DEBUG, so a debug build
        // installed for actual play still looks like a normal single-screen
        // app - `adb shell touch <files-dir>/debug_mirror` turns it on,
        // deleting that file turns it back off; nothing the user has to do
        // either way. Never present in a release build regardless.
        debugMirror = isDebugMirrorEnabled()

        view = EmulatorView(this)
        // Focusable, so a DS battle's menu can give it the focus ([dsBattleTakes]: no D-pad press lost to touch mode).
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        // Out of touch mode (a controller's buttons) Android draws its default focus highlight over a focused view: the
        // whole game washed out by a ~30% white (black read 77 grey).
        view.defaultFocusHighlightEnabled = false
        view.holdFrame = { ::engine.isInitialized && engine.holdFrame }
        // 1x frames follow the game screen's refresh (EmulatorEngine.onVsync).
        view.onVsync = { if (::engine.isInitialized) engine.onVsync() else 0L }
        // SHADERS on the companion: its grid at the game's own pixel size, so both screens match.
        view.onGamePixel = CompanionColors::setCell
        // The HUD keeps to the game's picture (ORIGINAL / STRETCH / an overlay); EXPAND = the whole view.
        // DS LAYOUT lays the HUD over the whole area, the game's box somewhere in it: its rects move with the box.
        view.onGameRect = { r ->
            val (dx, dy) = if (::stage.isInitialized) stage.viewport.dsGameOffset else 0 to 0
            // (A DS layout: the game's box, even where it starts at the area's corner - LARGE + SMALL's game is at 0, 0 and
            // the HUD spread over its touch screen.)
            val ds = ::stage.isInitialized && stage.viewport.dsArrange != null
            dsHudClear.value = if (ds) stage.viewport.dsTouchOverGame else 0f
            hudFrame.value = r?.let { androidx.compose.ui.geometry.Rect(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy) }
                ?: if (ds || dx != 0 || dy != 0) androidx.compose.ui.geometry.Rect(dx.toFloat(), dy.toFloat(), (dx + view.width).toFloat(), (dy + view.height).toFloat()) else null
        }
        // BATTLE BUTTONS keep right of the GBA's own picture (EXPAND's battle included).
        view.onCoreRect = { r ->
            val (dx, dy) = if (::stage.isInitialized) stage.viewport.dsGameOffset else 0 to 0
            coreFrame.value = androidx.compose.ui.geometry.Rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
        }
        if (debugMirror) view.setZOrderMediaOverlay(true) // see EmulatorView's z-order note
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xA0000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val p = dp(10)
            setPadding(p, dp(6), p, dp(6))
            visibility = View.GONE
        }
        touchControls = TouchControlsView(this).apply {
            onMask = ::onTouchKeysAnyThread
            onAction = ::onTouchAction
            inputAllowed = ::touchWindowsAllowed
            onPlaced = { syncHudClear() }
            visibility = View.GONE
        }
        statusBar = buildStatusBar()
        buildDsStageViews()
        n3dsDim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { if (!n3dsBattleScreenUp) setN3dsFloating(false) }
        }
        val root = GameScreenView(this, view, statusBar).apply {
            setBackgroundColor(Color.BLACK)
            viewport.gameDim = n3dsDim
            viewport.addView(n3dsDim, FrameLayout.LayoutParams(-1, -1))
        }
        stage = root
        // Compose on the stage runs on its own recomposer, for this activity's life (see stageRecomposer).
        root.compositionContext = stageRecomposer
        setContentView(root)
        // Compose's window-level recomposer looks for a ViewTreeLifecycleOwner
        // starting from the window's root view, not just the individual
        // ComposeViews added above (the status bar, the debug mirror) -
        // DualScreenPresentation doesn't need this because its ComposeView
        // *is* the Presentation's own content root; here it's nested inside
        // this Activity's own (plain, non-Compose) root, so the root itself
        // needs the owner too.
        val owner = ComposeHostOwner().apply { create(); resume() }.also { stageOwner = it }
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
        // Its panel and tabs above the FIELD HUD (which sits above the touch controls).
        // A DS game's touch screen is on the stage held sideways (DS LAYOUT), so the panel is the companion alone.
        sidePanel = SidePanel(root, Prefs(this), panelBack, panelKeys, playClick, onWindow = ::onCompanionWindow, companion = {
            val snap by telemetry.snapshot.collectAsState()
            CompanionScreen(snap, stateSlots, companionSettings, battleInput, back = panelBack, keys = panelKeys, clickSound = playClick, achievements = RetroAchievements, statusBar = companionStatusBar, itemUse = itemUse, fly = flyAction, askForSupport = askForSupport, gamePick = gamePick, shareBestEffort = shareBestEffort)
        })
        portraitPanel = PortraitPanel(root, Prefs(this), portraitBack) {
            dsOrCompanion {
                val snap by telemetry.snapshot.collectAsState()
                CompanionFocusHost(bottomFocus.value, bottomKeys) {
                    CompanionScreen(snap, stateSlots, companionSettings, battleInput, back = portraitBack, keys = bottomKeys, clickSound = playClick, achievements = RetroAchievements, statusBar = companionStatusBar, itemUse = itemUse, fly = flyAction, askForSupport = askForSupport, gamePick = gamePick, shareBestEffort = shareBestEffort)
                }
            }
        }
        root.onCompanionHidden = { setBottomFocus(false) }
        root.install(touchControls, dsBackdropView, dsTouchView, playClick,
            underlay = { DsWindowContent() }, gameOverlay = { FieldHudContent() },
            screenOverlay = { PerformanceContent() },
            editorOverlay = { touchEditor.Content() },
            pictureOverlay = { if (keyCaptureShown) com.pokedaisy.app.companion.ui.KeyCapturePopup(keyCapture) },
            message = hud,
            onPicture = { fieldHudBounds = it })
        touchControls.inputExclusions = root::inputExclusions
        if (debugMirror) {
            root.reserveRendererRight(dp(DEBUG_MIRROR_WIDTH_DP))
            root.addView(buildDebugCompanionMirror(), debugMirrorLayoutParams())
        }
        // Two screens: the game stays on the landscape top one. One screen (a phone): it turns
        // with the device (the user's rotation setting), the companion under the game upright.
        syncOrientation(secondScreen() != null)
        syncGameScreen()

        reloadHotkeys()
        // L2 / R2 are TOUCH SCREEN / DS LAYOUT's in a DS game on one screen, SPEED CYCLE / FF HOLD's elsewhere.
        Hotkeys.dsActive = { a ->
            when (a) {
                Hotkeys.Action.TOUCH_SCREEN -> dsFloats.value || n3dsFloats
                Hotkeys.Action.DS_LAYOUT -> dsStageOn
                else -> false
            }
        }
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        inputManager = getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager

        rom = resolveRom(intent)
        val r = rom
        if (r == null) {
            toastNoRom()
            return
        }
        fromFrontend = intent?.getBooleanExtra(EXTRA_FROM_FRONTEND, false) ?: false
        pendingFixtureDump = intent?.getStringExtra(EXTRA_DUMP_FIXTURE)?.let { File(it).apply { mkdirs() } }
        forceFixtureDump = intent?.getBooleanExtra(EXTRA_DUMP_FIXTURE_FORCE, false) ?: false
        loadRom(r)
    }

    /**
     * Everything tied to the loaded ROM: its savestates, FF music, art, menu
     * watchers and the emulator engine. From onCreate, and again from
     * onNewIntent when a frontend opens a different game while this one is
     * alive - the activity is always paused before a new intent, so the old
     * engine has already stopped (and suspended its game) by then.
     */
    /** The next [startGame] is this ROM's launch (not a return from HOME): RESUME GAMES decides. */
    private var freshLaunch = true

    private fun loadRom(romFile: File) {
        if (::engine.isInitialized) engine.playback.close()
        endN3ds()
        if (com.pokedaisy.app.n3ds.N3dsCart.EXTENSIONS.contains(romFile.extension.lowercase()) && FeatureFlags.n3ds()) {
            lookSession.clear()
            load3ds(romFile)
            return
        }
        if (!recoverSaveTrade()) { finish(); return }
        freshLaunch = true
        // An archive unpacks once (cached after) - ~0.5 s for 32 MiB, on the black screen. One with no game in it (a PC
        // fan game's zip) is said so, not played: read as the ROM it threw on its CRC and closed the app (Crashlytics,
        // 1.6.0: "No GBA ROM in Pokemon Uranium.zip").
        val r = RomArchive.playable(romFile, cacheDir) ?: run {
            Log.w("pokedaisy", "no game in ${romFile.name}")
            Toast.makeText(this, tr("No GBA ROM in {0}", romFile.name), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        romData = r
        CrashReports.gameStarted(
            this, romFile, r,
            intent?.getStringExtra(LibraryActivity.EXTRA_LAUNCHED_FROM) ?: if (fromFrontend) "frontend" else null,
        )
        // Known already: the library / frontend launch worked it out off the UI thread (RomCrcs.prepare) - a 512 MB DS
        // cart read here froze the window past Android's 5 s. Keyed by the file played, an archive's by the archive.
        val crc = "%08x".format(RomCrcs.of(this, romFile, r))
        romKey = crc
        FeatureFlags.applyBlocked(this)
        romBlocked = com.pokedaisy.app.companion.data.BlockedGames.forCrc(crc)
        RetroAchievements.setFlightAnywhere(false)
        states = SaveStates(getExternalFilesDir(null) ?: filesDir, crc)
        val ffMusicCache = androidFfMusicCache(getExternalFilesDir(null) ?: filesDir, crc, gameBoy = RomIdentity.isGameBoy(r))
        // STEADY FF music: songs rendered on their own from this ROM, in the
        // background, as the game starts them (see FfMusicRenderer).
        // The companion's button click is rendered from this ROM the same way.
        clickSound?.release()
        clickSound = GameClickSound(filesDir, crc)
        clickSoundOn = Prefs(this).clickSound
        com.pokedaisy.app.companion.ui.CompanionTweaks.load { Prefs(this).tweak(it) }
        Prefs(this).let { prefs ->
            com.pokedaisy.app.companion.FavoriteItems.load(prefs.favoriteItemsFor(crc)) { prefs.setFavoriteItemsFor(crc, it) }
        }
        // RetroAchievements unlocks play the game's level-up fanfare, rendered the same way.
        unlockSound?.release()
        unlockSound = GameClickSound(filesDir, crc, GameClickSound.FANFARE)
        ffMusicRenderer?.stop()
        // A Game Boy / Color game: none of the GBA ROM scans below apply (m4a music,
        // GBA art fingerprints, FireRed's region_map.c); its click is borrowed.
        val gameBoy = RomIdentity.isGameBoy(r)
        romIsGameBoy = gameBoy
        // A DS cart (melonDS, nds/pk_nds.cpp): none of the GBA scans either, and no Game Boy ones.
        val nds = RomIdentity.isNds(r)
        romIsNds = nds
        if (::portraitPanel.isInitialized) portraitPanel.ds = nds
        val ndsCode = if (nds) RomIdentity.ndsGameCode(r).orEmpty() else ""
        romOrigin = nds && ndsCode == com.pokedaisy.app.companion.data.OriginHeartGold.CODE &&
            com.pokedaisy.app.companion.data.OriginHeartGold.matches(r.toOkioPath())
        dsWildRates = if (!nds) null else if (romOrigin) {
            // Its tables are read in the background (NdsArt.prefetch); the core may start first, so read them here then.
            { (com.pokedaisy.app.companion.data.OriginHeartGold.data ?: com.pokedaisy.app.companion.data.NdsArt.readOrigin(r.toOkioPath()))?.wildRates ?: IntArray(0) }
        } else if (ndsCode == com.pokedaisy.app.companion.data.RenegadePlatinum.CODE &&
            com.pokedaisy.app.companion.data.RenegadePlatinum.matches(r.toOkioPath())) {
            // Renegade Platinum: retail Platinum's header, its own maps' rates (read from its cart, like Origin's).
            { (com.pokedaisy.app.companion.data.RenegadePlatinum.data ?: com.pokedaisy.app.companion.data.NdsArt.readRenegade(r.toOkioPath()))?.wildRates ?: IntArray(0) }
        } else if ((ndsCode == com.pokedaisy.app.companion.data.Black2Redux.CODE_BLACK2 ||
                ndsCode == com.pokedaisy.app.companion.data.Black2Redux.CODE_WHITE2) &&
            com.pokedaisy.app.companion.data.Black2Redux.matches(r.toOkioPath())) {
            // Blaze Black 2 / Volt White 2 Redux: retail Black 2's / White 2's header, their own zones' rates (from the cart).
            { (com.pokedaisy.app.companion.data.Black2Redux.data ?: com.pokedaisy.app.companion.data.NdsArt.readRedux(r.toOkioPath()))?.wildRateWords() ?: IntArray(0) }
        } else if (ndsCode == com.pokedaisy.app.companion.data.HeartfeltGold.CODE &&
            com.pokedaisy.app.companion.data.HeartfeltGold.matches(r.toOkioPath())) {
            // Heartfelt Gold: the US HeartGold's header, its own maps' rates (from its cart).
            { (com.pokedaisy.app.companion.data.HeartfeltGold.data ?: com.pokedaisy.app.companion.data.NdsArt.readHeartfelt(r.toOkioPath()))?.wildRates ?: IntArray(0) }
        } else if ((ndsCode == com.pokedaisy.app.companion.data.SacredGold.CODE_SACRED_GOLD ||
                ndsCode == com.pokedaisy.app.companion.data.SacredGold.CODE_STORM_SILVER) &&
            com.pokedaisy.app.companion.data.SacredGold.matches(r.toOkioPath())) {
            // Sacred Gold / Storm Silver: the retail headers, their own maps' rates (from their cart).
            { (com.pokedaisy.app.companion.data.SacredGold.data ?: com.pokedaisy.app.companion.data.NdsArt.readSacredGold(r.toOkioPath()))?.wildRates ?: IntArray(0) }
        } else when (ndsCode) {
            "CPUE", "CPUF" -> com.pokedaisy.app.companion.data.PLATINUM_WORLD::wildRates
            "IPKE", "IPKD", "IPKF" -> com.pokedaisy.app.companion.data.HEARTGOLD_WORLD::wildRates
            "IPGE", "IPGD", "IPGF" -> com.pokedaisy.app.companion.data.SOULSILVER_WORLD::wildRates
            "IRBO", "IRBF" -> { { com.pokedaisy.app.companion.data.BLACK_WORLD.wildRateWords() } }
            "IRAO", "IRAF" -> { { com.pokedaisy.app.companion.data.WHITE_WORLD.wildRateWords() } }
            "IREO" -> { { com.pokedaisy.app.companion.data.BLACK2_WORLD.wildRateWords() } }
            "IRDO" -> { { com.pokedaisy.app.companion.data.WHITE2_WORLD.wildRateWords() } }
            // A language port: its game's world (the same maps and encounter tables).
            else -> when (com.pokedaisy.app.companion.data.DsPorts.byCode(ndsCode)?.kind) {
                com.pokedaisy.app.companion.data.GameKind.PLATINUM -> com.pokedaisy.app.companion.data.PLATINUM_WORLD::wildRates
                com.pokedaisy.app.companion.data.GameKind.HEARTGOLD ->com.pokedaisy.app.companion.data.HEARTGOLD_WORLD::wildRates
                com.pokedaisy.app.companion.data.GameKind.SOULSILVER -> com.pokedaisy.app.companion.data.SOULSILVER_WORLD::wildRates
                com.pokedaisy.app.companion.data.GameKind.BLACK -> { { com.pokedaisy.app.companion.data.BLACK_WORLD.wildRateWords() } }
                com.pokedaisy.app.companion.data.GameKind.WHITE -> { { com.pokedaisy.app.companion.data.WHITE_WORLD.wildRateWords() } }
                com.pokedaisy.app.companion.data.GameKind.BLACK2 -> { { com.pokedaisy.app.companion.data.BLACK2_WORLD.wildRateWords() } }
                com.pokedaisy.app.companion.data.GameKind.WHITE2 -> { { com.pokedaisy.app.companion.data.WHITE2_WORLD.wildRateWords() } }
                else -> null
            }
        }
        input.ds = nds
        val gba = !gameBoy && !nds
        // EXPAND: the GBA games pk_wide.c knows, and Game Boy ones pk_wide_gb.c does (Yellow, Crystal); the field reads are GBA only.
        // A randomized retail cart (RandomizedRetail) goes by retail's CRC here: retail's code, maps and sprites.
        val tablesCrc = if (gba) WideScreen.tablesCrc(r, crc) else crc
        wideGame = if (nds || romBlocked != null) WideScreen.OFF else WideScreen.gameFor(tablesCrc)
        fieldGame = if (!gba || romBlocked != null) WideScreen.OFF else WideScreen.fieldGameFor(tablesCrc)
        currentGameOptions()?.resetMetadata()
        syncExpShareAchievements(false)
        if (::engine.isInitialized) engine.shiny.observe(null)
        syncShinyAchievements()
        Prefs(this).let { p ->
            expandOfferUp.value = wideGame != WideScreen.OFF && !p.expandGameOfferSeen &&
                p.gameAspect != com.pokedaisy.app.companion.GameAspect.WIDE
        }
        // FireRed / Emerald party-menu art and region maps come from the ROM
        // itself, once per ROM (see RomArt) - nothing of the game is bundled.
        if (gba) RomArt.prefetch(filesDir.toOkioPath(), crc, r.toOkioPath()) else if (gameBoy) com.pokedaisy.app.companion.data.Gen1Art.prefetch(filesDir.toOkioPath(), r.toOkioPath())
        // A DS cart's party icons, from its own file system (NdsArt).
        if (nds) com.pokedaisy.app.companion.data.NdsArt.prefetch(filesDir.toOkioPath(), r.toOkioPath())
        if (gameBoy) com.pokedaisy.app.companion.data.Gen2Art.prefetch(filesDir.toOkioPath(), r.toOkioPath())   // a Color cart's (Crystal): its own scan
        // A Game Boy cart's POKéDEX pages are read from its bytes (bank-switched: not on the bus whole); a few MB at most.
        val gbRom = if (gameBoy) runCatching { r.readBytes() }.getOrNull() else null
        com.pokedaisy.app.companion.data.Gen1Dex.rom = gbRom
        val musicSource = SteadyMusicSource.forCartridge(gba) {
            gbRom?.let { java.security.MessageDigest.getInstance("SHA-1").digest(it).joinToString("") { b -> "%02x".format(b) } }
        }
        ffMusicRenderer = musicSource?.renderer(r.toOkioPath(), crc, ffMusicCache, androidMusicRenderHost,
            click = clickSound, fanfare = unlockSound)?.also { it.start() }
        // A FireRed-engine hack's own region map (Unbound, Odyssey, ...), read
        // from the ROM on every launch - a few KB of reads (see RomRegionMap).
        if (gba) RomRegionMap.load(filesDir.toOkioPath(), crc, r.toOkioPath()) else RomRegionMap.current = null   // not the last GBA game's
        // SMART FF steps aside while the game's region map or a menu is up (see EmulatorEngine).
        // The game code and revision: a GBA header's 0xAC / 0xBC, a DS header's 0x0C / 0x1E.
        val header = RomIdentity.header(r, nds)
        val gameCode = header?.first.orEmpty()
        romCode = gameCode
        // AUTO = the ROM's own language (its game code's last letter; Origin HeartGold's header is Japanese, its text English).
        com.pokedaisy.app.companion.i18n.L10n.apply(Prefs(this).appLanguage, if (romOrigin) "IPKE" else gameCode)
        romRev = header?.second ?: -1
        // A DS cart's EXPAND: the overworld past the top screen's edges, for a cart its field read knows.
        pickDsCore()
        view.camera.ndsMode(nds)
        // DS RESOLUTION's frames come through a SurfaceTexture the view hands the core (EmulatorView.gpuFrames).
        view.gpuFrames = nds
        // The ZOOM picked last time on this game: the camera holds 1x until the field has been up a moment, then eases
        // out to it (WideCamera.reset), so it comes back once the game has loaded rather than over its title screen.
        view.camera.level = Prefs(this).zoomLevelFor(romKey)
        val battleSession = com.pokedaisy.app.companion.GameBattleInput(telemetry, gameCode, romRev,
            input = input, writer = { a, b -> MgbaCore.pkWriteBytes(a, b) },
            caller = { fn, a0, a1, a2, a3 -> if (MgbaCore.pkCallSafe()) MgbaCore.pkCall(fn, a0, a1, a2, a3) else BattleInputController.CALL_NOT_NOW },
            retryUnshownBattle = true)
        engine = EmulatorEngine(input, states!!, ffMusicCache, battleSession, Prefs(this), telemetry, musicSource).apply {
            onShinyChanged = ::syncShinyAchievements
            shiny
            hudEngine.value = this
            lateFramesSource = { view.lateFrames }
            // The game screen's fastest mode, which no tick that waits for the refresh can outrun (VsyncCheck, GitHub #350).
            displayMaxHz = { view.display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f }
            drawStatsSource = { view.repeatedDraws to view.unshownFrames }
            if (BuildConfig.DEBUG) audioTapDir = getExternalFilesDir("debug")
            this.gba = gba
            if (gba) playback.bindLastBall(
                { fieldItemSupport()?.fns?.removeBagItem ?: 0L }, ::closeCompanionWindow)
            playback.bindFollower(followerCore, this@PokeDaisyActivity.filesDir.toOkioPath())
            this.nds = nds
            ndsGameCode = ndsCode
            wideCamera = view.camera.also {
                it.zoomOnText = Prefs(this@PokeDaisyActivity).expandZoomOnText
                it.lookAround = Prefs(this@PokeDaisyActivity).lookAround
            }
            onWideOff = { runOnUiThread { showHud(com.pokedaisy.app.companion.i18n.tr("EXPAND hit a problem and is off for this game")) } }
            onCoreReady = { w, h ->
                // A DS game's bottom screen goes to the bottom display (NdsBottomFrame).
                NdsBottomFrame.bind(if (romIsNds) MgbaCore.pkNdsBottomBuffer() else null)
                // WILD ENCOUNTERS back on: the cart's own rates, whatever a resume state left in RAM.
                if (romIsNds) dsWildRates?.let { MgbaCore.pkNdsWildRates(it()) }
                MgbaCore.pkVideoBuffer()?.let { buf ->
                    // Here on the emu thread, before the first frame (see EmulatorView.bindCore).
                    view.bindCore(buf, MgbaCore.pkWideBuffer(), w, h)
                    runOnUiThread {
                        stage.viewport.aspect = w.toFloat() / h   // 3:2, or a Game Boy's 10:9
                        touchControls.shoulders = !gameBoy
                        touchControls.dsButtons = romIsNds
                        syncTouchControls()   // a Game Boy's controls (no L / R) are shorter: EXPAND's strip follows
                    }
                }
            }
            // The emu thread is about to free the frame buffer (pause, close, a crashed
            // loop): the GL thread lets go of it first. unbind takes the renderer's lock,
            // so it's safe from this thread and returns only once no upload is reading it.
            onCoreStopping = {
                NdsBottomFrame.unbind()
                view.unbindCoreBlocking()
            }
            // The screen shows finished frames only (a copy), never the one being drawn.
            onFrame = {
                view.publishFrame()
                if (romIsNds) NdsBottomFrame.publish()
            }
            onStateResult = { action, slot, ok ->
                val msg = when (action) {
                    Hotkeys.Action.SAVE_STATE -> if (ok) tr("Saved slot {0}", slot) else tr("Slot {0}: save failed", slot)
                    // BOSS RETRY's state (slot -1): the battle's start.
                    Hotkeys.Action.LOAD_STATE -> if (slot < 0) { if (ok) tr("Back to the battle's start") else tr("Can't go back to that battle") }
                        else if (ok) tr("Loaded slot {0}", slot) else if (CoopGuards.statesBlockedBy(CoopGuards.StateBlock.LOCK)) tr("Not while you play together only") else tr("Slot {0}: nothing to load", slot)
                    Hotkeys.Action.UNDO_SAVE -> if (ok) tr("Undid save (slot {0})", slot) else tr("Nothing to undo")
                    Hotkeys.Action.UNDO_LOAD -> if (ok) tr("Undid load") else if (CoopGuards.statesBlockedBy(CoopGuards.StateBlock.LOCK)) tr("Not while you play together only") else tr("Nothing to undo")
                    else -> ""
                }
                val reloaded = ok && (action == Hotkeys.Action.LOAD_STATE || action == Hotkeys.Action.UNDO_LOAD)
                if (msg.isNotEmpty() || reloaded) runOnUiThread {
                    if (reloaded) itemToastReset.intValue++
                    if (msg.isNotEmpty()) showHud(msg)
                }
            }
            onSlotChanged = { slot ->
                val text = if (states?.exists(slot) == true) tr("Slot {0} (used)", slot) else tr("Slot {0} (empty)", slot)
                runOnUiThread { showHud(text) }
            }
            onSpeedChanged = { label -> runOnUiThread { showHud(label) } }
            onFastForwardToggledChanged = { on ->
                Prefs(this@PokeDaisyActivity).ffToggled = on
                runOnUiThread { if (::touchControls.isInitialized) touchControls.ffOn = on }
            }
            onFfMusicChanged = { clip -> runOnUiThread { ffMusicPlayer.setClip(clip) } }
            onFfMusicWanted = { key -> ffMusicRenderer?.request(key) }
            ffMusicWillRender = { key -> ffMusicRenderer?.willRender(key) == true }
            // What a fresh sample feeds besides the companion: on the emu thread, once the sample is in (startSample).
            val afterSample: (com.pokedaisy.app.companion.data.SnapshotView) -> Unit = { snap ->
                if (!playing && snap.connected) {
                    val moved = synchronized(playPositions) {
                        playPositions.add((snap.mapGroup.toLong() shl 48) or (snap.mapNum.toLong() shl 32) or (snap.x.toLong() shl 16) or (snap.y.toLong() and 0xFFFF))
                        playPositions.size >= PLAY_POSITIONS
                    }
                    if (onField.value || snap.inBattle || moved) {
                        Log.i("pokedaisy", "playing: field=${onField.value} battle=${snap.inBattle} positions=${synchronized(playPositions) { playPositions.size }} at ${snap.mapGroup}.${snap.mapNum} (${snap.x},${snap.y})")
                        nowPlaying()
                    }
                }
                // AUTO REFILL: another REPEL on before the one working runs out.
                updateRepelRefill(snap)
                playback.battleRetry.observe(snap, !CoopGuards.battleActive(), ::captureRetry)
                // WILD ENCOUNTERS on a Game Boy game is held by the engine, frame by frame.
                if (snap.connected && romIsGameBoy) gameBoyFields()
                // party.isNotEmpty(), not just connected: `connected` flips true as
                // soon as the native addresses are found, which can be
                // well before the save's party has actually loaded into RAM (e.g.
                // still on the title/continue screen just after boot) - a dump
                // right then faithfully captures a real but useless "partyCount=0"
                // moment. Bit the first fixture capture this existed for.
                if (snap.connected && (snap.party.isNotEmpty() || forceFixtureDump)) dumpFixtureIfPending()
            }
            playback.startSampling(romIsGameBoy, romIsNds,
                game = object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = postToEmu(block::run)
                }, current = { ::engine.isInitialized && engine === this && running }, after = afterSample,
                onShinyFound = { alert ->
                    val current = this
                    runOnUiThread {
                        if (!::engine.isInitialized || engine !== current || !current.running) return@runOnUiThread
                        gameActions.stopFastForward()
                        input.releaseAll()
                        unlockSound?.play()
                        showHud(alert.message(), com.pokedaisy.app.companion.GameShinyAlert.NOTICE_MILLIS)
                    }
                }, onNuzlockeNotices = { notices ->
                    val current = this
                    runOnUiThread {
                        if (::engine.isInitialized && engine === current && current.running)
                            showHud(notices.joinToString("\n"), com.pokedaisy.app.companion.Nuzlocke.NOTICE_MILLIS)
                    }
                })
            onSample = { startSample(this) }   // runs on the emu thread
            onResumeResult = { loaded -> if (!loaded) { telemetry.dropCached(); runOnUiThread { waitForPlay() } } }
            ffMaxSpeed = Prefs(this@PokeDaisyActivity).ffMaxSpeed
            ffMusicMode = Prefs(this@PokeDaisyActivity).ffMusicMode
            ffMode = Prefs(this@PokeDaisyActivity).ffMode
            rewindEntries = rewindEntries(Prefs(this@PokeDaisyActivity).rewind)
            restoreFastForwardToggled(Prefs(this@PokeDaisyActivity).ffToggled)
            restoreSpeedIndex(Prefs(this@PokeDaisyActivity).speedIndexFor(romKey))
            applyFieldPrefs(this)
        }
        engine.playback.start(r.toOkioPath(), gameCode, crc.toLong(16), gba, tablesCrc.toLongOrNull(16) ?: 0L)
        if (nds) syncDsExpShare()
        if ((gba || gameBoy) && romBlocked == null) {
            val current = engine
            current.gameOptions.start(r.toOkioPath(), gameBoy = gameBoy, configureWild = { flag, _ ->
                if (romData == r && engine === current) {
                    current.wildFlag = flag
                    flag != 0L
                } else false
            }, ready = {
                if (romData == r && engine === current) applyGameOptions(current.gameOptions)
            })
        }
        // PLAYER LOOK: this ROM's scan and patches, in the background; the engine gets the pick once they're in.
        val initialFemale = if (gba) {
            val save = SavesLocation.saveFor(this, Prefs(this), romFile)
            runCatching { Gen3SaveFile.playerFemale(save.readBytes()) }.getOrNull()
        } else null
        lookSession.load(r.toOkioPath(), if (gba && romBlocked == null) gameCode else "", crc,
            initialFemale = initialFemale, prepareFollowers = true, library = ::appearanceLibrary)
    }

    /** The library may contain archives. Read each source through the same ROM entry used for play. */
    private fun appearanceLibrary(): List<AppearanceAssets.Source> {
        val context = applicationContext
        return runCatching { RomFolder.libraryRoms(context, Prefs(context)) }.getOrDefault(emptyList()).map { file ->
            AppearanceAssets.Source(file.canonicalPath, "${file.absolutePath}|${file.length()}|${file.lastModified()}", file.name,
                { RomIdentity.gameCode(file) }) { RomArchive.open(file).use { it.readBytes() } }
        }
    }

    // The confirmed paired game owns this presence and its link receiver.
    private var coopPresence: CoopPresence? = null
    private var coopReceive: kotlinx.coroutines.Job? = null
    private fun createCoopPresence(r: File, link: CoopPresenceChannel) {
        val artRom = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val coopArt = java.util.concurrent.ConcurrentHashMap<Pair<Boolean, com.pokedaisy.app.companion.data.LookChar?>, ByteArray>()
        val coopBuilding = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Boolean, com.pokedaisy.app.companion.data.LookChar?>>()
        val dir = filesDir
        // The art is built on a background thread (it may scan the ROM, ~1 s): answered null until it's in, asked again later.
        val presence = CoopPresence(MgbaPartnerCore, android.os.SystemClock::elapsedRealtime) { female, look ->
            val key = female to look
            coopArt[key] ?: run {
                if (coopBuilding.add(key)) Thread({
                    runCatching {
                        val rom = artRom.get() ?: r.readBytes().also { artRom.set(it) }
                        com.pokedaisy.app.companion.data.coop.PartnerArt.build(dir.toOkioPath(), rom, female, look)?.let { coopArt[key] = it }
                    }
                    coopBuilding.remove(key)
                }, "pokedaisy-partner-art").start()
                null
            }
        }
        coopPresence = presence
        engine.coopPresence = presence
        presence.link = link
        coopReceive = lookGender.launch { link.received.collect(presence::onReceived) }
        lookGender.launch {
            while (coopPresence === presence) {
                presence.myLook = lookSession.wornHero
                presence.myFemale = lookSession.isFemale
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun stopCoopPresence() {
        val p = coopPresence ?: return
        coopReceive?.cancel()
        coopReceive = null
        coopPresence = null
        p.link = null
        if (::engine.isInitialized) engine.coopPresence = null
        runOnEmu { p.stop() }
    }

    /** [job] on the emu thread while the engine runs (the core is its alone), else here (the native calls need no core). */
    private fun runOnEmu(job: () -> Unit) {
        if (::engine.isInitialized && engine.running) engine.postToEmu(job) else job()
    }

    /** SCENE SPEEDS, AUTO-RUN, RUN INDOORS, WILD ENCOUNTERS and REUSABLE TMS onto [e], and the game whose field they read (EXPAND on or off). */
    private fun applyFieldPrefs(e: EmulatorEngine) {
        val p = Prefs(this)
        e.fieldGame = fieldGame
        e.battleSpeed = p.battleSpeed
        e.textSpeed = p.textSpeed
        e.menuSpeed = p.menuSpeed
        e.bikeSpeed = p.bikeSpeed
        e.surfSpeed = p.surfSpeed
        e.ndsScale = p.dsResolution
        e.ndsCpuBoost = p.dsCpuBoost
        applyGameOptions(e.gameOptions, e)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Lets scripts/capture_fixture.sh dump an ALREADY-RUNNING, already-past-
        // the-title-screen session (singleTask means this fires instead of a
        // fresh onCreate when the activity is retargeted while alive) instead of
        // force-stopping + cold-relaunching, which loses party/save state and
        // re-lands on the title screen - fine for a ROM that auto-skips straight
        // to the overworld, but stalls capture indefinitely for one that doesn't.
        // pendingFixtureDump/forceFixtureDump are plain instance vars read fresh
        // every onSample tick (see dumpFixtureIfPending()), so setting them here
        // is picked up by the already-running emu thread on its next tick.
        intent.getStringExtra(EXTRA_DUMP_FIXTURE)?.let { pendingFixtureDump = File(it).apply { mkdirs() } }
        if (intent.hasExtra(EXTRA_DUMP_FIXTURE_FORCE)) {
            forceFixtureDump = intent.getBooleanExtra(EXTRA_DUMP_FIXTURE_FORCE, false)
        }
        // A frontend (LaunchActivity) or the library asking for a game while
        // one is open: the same ROM just comes back to the front; a different
        // one replaces it. Normally we're paused by now, so the old game is
        // already suspended and onResume starts the new one; delivered while
        // resumed (Android 10+ may), suspend and start here instead.
        if (intent.hasExtra(LibraryActivity.EXTRA_ROM)) {
            fromFrontend = intent.getBooleanExtra(EXTRA_FROM_FRONTEND, false)
            val next = intent.getStringExtra(LibraryActivity.EXTRA_ROM)?.let(::File)?.takeIf { it.isFile }
            if (next != null && next.absolutePath != rom?.absolutePath) {
                val running = ::engine.isInitialized && engine.running
                if (running) {
                    view.unbindCoreBlocking()   // see restartGame
                    stopEngineWithResume()
                }
                rom = next
                telemetry.reset()
                loadRom(next)
                // (An archive with no game closed the screen: nothing to start.)
                if (running && !isFinishing) startGame()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // COMPANION BUTTON may have changed in the top screen's Settings.
        Prefs(this).companionTab.let { on ->
            companionButtonShown.value = on
            if (::sidePanel.isInitialized && sidePanel.openTabShown != on) sidePanel.openTabShown = on
        }
        presentation?.let {
            SecondScreenHolder.open(this, it.display)
            it.watchBars()
        }
        listenForFold()
        tipsResumed = true
        coopOn = FeatureFlags.coop(this)
        if (movePause) {
            // The move's pause: the game never stopped. Only the screens follow (their configuration change may have
            // come before the pause).
            movePause = false
            followDisplay()
            return
        }
        startPlayClock()
        // The game's task brought up on the bottom screen (the app's icon tapped there): back to the top one, so the
        // companion can have the bottom (a Presentation can't share the game's display).
        // Not while the companion is closed: then the game is on the second screen on purpose (SWAP SCREENS).
        if (!companionClosed) Screens.bringToMainDisplay(this)
        com.pokedaisy.app.companion.ui.OptionColors.inGame = true
        goImmersive()
        view.onResume()
        // Pick up any Settings changes made since launch.
        if (romCode.isNotEmpty()) com.pokedaisy.app.companion.i18n.L10n.apply(Prefs(this).appLanguage, romCode)
        reloadHotkeys()
        hotkeysOn = Prefs(this).hotkeysEnabled
        input.setControls(GbaControls.load(controlsDir))
        if (::engine.isInitialized) {
            engine.ffMaxSpeed = Prefs(this).ffMaxSpeed
            engine.ffMusicMode = Prefs(this).ffMusicMode
            engine.ffMode = Prefs(this).ffMode
            engine.rewindEntries = rewindEntries(Prefs(this).rewind)
            applyFieldPrefs(engine)
        }
        if (!startGame()) return
        displayManager.registerDisplayListener(displayListener, null)
        syncPresentation()
        // SETTINGS > COMPANION may have changed in the top-screen Settings.
        portraitPanel.refresh()
        inputManager.registerInputDeviceListener(inputDeviceListener, null)
        for (id in InputDevice.getDeviceIds()) InputDevice.getDevice(id)?.takeIf(::isGamepad)?.let { pads[id] = it.name }
        syncTouchControls()
        syncGameScreen()
        // Sticky: the current reading arrives right away, then every change.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::onBattery)
    }

    /** This ROM's cheats (by CRC, like its states); null before a ROM is loaded. */
    private fun cheatStore(): com.pokedaisy.app.cheats.CheatStore? =
        romKey.takeIf { it.isNotEmpty() }?.let { com.pokedaisy.app.cheats.CheatStore.forCrc(filesDir.toOkioPath(), it) }

    private fun gameCheats(): com.pokedaisy.app.companion.GameCheats? {
        if (romKey.isEmpty() || !::engine.isInitialized) return null
        return engine.playback.cheats ?: engine.playback.bindCheats(true, ::cheatStore,
            enabled = { Prefs(this).cheatsEnabled }, submit = ::submitCheats)
    }

    /** Hands the engine this ROM's enabled cheats (none with the master switch off); it loads
     * them before the first frame, or on the next one while the game runs. */
    private var coopCheatNoticed = false

    /** [reread]: the file read again (a game starting: the top screen's Settings may have changed it); a switch flipped
     * mid-game only hands the list already read back to the engine - reading an imported file again on the UI thread for
     * each flip held it for seconds (GitHub #786). */
    private fun syncCheats(reread: Boolean = false) {
        if (!::engine.isInitialized) return
        val existing = engine.playback.cheats
        val cheats = existing ?: gameCheats() ?: return
        if (existing != null && (reread || cheats.list.isEmpty())) cheats.refresh()
        cheats.apply()
    }

    private fun submitCheats(cheatText: String) {
        if (!::engine.isInitialized) return
        val enabled = Prefs(this).cheatsEnabled
        val coopOn = currentCoopPair != null
        val text = com.pokedaisy.app.companion.coop.CoopSession.cheatsForEngine(enabled, coopOn) {
            cheatText
        }
        if (enabled && coopOn) {
            if (!coopCheatNoticed) { coopCheatNoticed = true; showHud(com.pokedaisy.app.companion.i18n.tr("Not while co-op is on.")) }
        } else coopCheatNoticed = false
        engine.setCheats(text)
    }

    private val coopStartup = CoopGameStartup { pending ->
        if (::engine.isInitialized) engine.coopStartupPending = pending
    }

    private var coopSettings: CoopGameSettings? = null
    private var coopShutdown = newCoopShutdown()

    private fun newCoopShutdown() = CoopGameShutdown(
        restoreBattle = { CoopBattles.stopNow() },
        resumeBattles = { CoopBattles.resume() },
        stopEndpoint = { coopEndpoint?.stopGame() },
        detachGame = ::detachCoop,
    )

    private fun detachCoop() {
        CoopBattles.detach(coopApp)
        stopCoopPresence()
        stopCoopLock()
        currentCoopPair = null
        coopSettings?.let { coopEndpoint?.unbindGameSettings(it) }
        coopSettings = null
        coopEndpoint = null
        coopStartup.cancel()
    }

    private var coopEndpoint: CoopEndpointSession? = null

    // --- co-op joined battles (CoopBattles.kt) ---

    /** The overlays over the game (HUD view): join prompt, waits, notices. */
    private val coopOverlay = com.pokedaisy.app.companion.ui.CoopOverlayState()
    private var coopHat = 0
    @Volatile private var currentCoopPair: CoopPair? = null

    /** A joined battle is running: the action [what] is refused with a notice (RESTART, SAVE / LOAD STATE, REWIND, PLAYER LOOK, ...). */
    private fun coopRefuses(): Boolean {
        if (!CoopBattles.busy) return false
        showHud(com.pokedaisy.app.companion.i18n.tr("Not during a co-op battle."))
        return true
    }

    /** Cheats are refused (with a notice) while co-op is on for this game: the start gate's rule, held mid-session. */
    private fun coopCheatsRefuse(turningOn: Boolean): Boolean {
        val on = currentCoopPair != null
        if (!com.pokedaisy.app.companion.coop.CoopSession.refusesCheats(turningOn, on)) return false
        showHud(com.pokedaisy.app.companion.i18n.tr("Not while co-op is on."))
        return true
    }

    private val catchFile by lazy { com.pokedaisy.app.companion.data.coop.FileCatchStore(filesDir.toOkioPath() / "coop" / "catches.txt") }

    private val coopBattleField = EmeraldBattleField(
        MgbaCore::pkReadBytes, MgbaPartnerCore::self, { coopPresence?.lastReceived },
        { (coopLock?.ui?.value?.waiting as? com.pokedaisy.app.companion.coop.lock.LockWait.AtStory)?.eventId },
    )

    private val coopApp = object : CoopApp {
        override val coopOn get() = FeatureFlags.coop(this@PokeDaisyActivity)
        override val emu = object : CoopEmu {
            override fun post(job: () -> Unit) { if (::engine.isInitialized) engine.postToEmu(job) }
            override fun runBetweenFrames(waitMs: Long, job: () -> Unit): Boolean {
                if (::engine.isInitialized && engine.running) return awaitCoopFrame(waitMs, ::post, job)
                job()
                return true
            }
        }
        override val overlay get() = coopOverlay
        override fun core(): com.pokedaisy.app.companion.data.coop.CoopCore =
            MgbaCoopCore()
        override fun setHook(hook: CoopFrameHook?) { if (::engine.isInitialized) engine.coopBattles = hook }
        override val env = object : CoopGlueEnv, CoopBattleField by coopBattleField {
            override val range get() = Prefs(this@PokeDaisyActivity).coopNearbyRange
            override fun partnerName(): String {
                val st = com.pokedaisy.app.companion.coop.CoopSession.link.state.value
                val p = when (st) {
                    is com.pokedaisy.app.companion.coop.CoopState.Connected -> st.partner
                    is com.pokedaisy.app.companion.coop.CoopState.Connecting -> st.partner
                    is com.pokedaisy.app.companion.coop.CoopState.Disconnected -> st.partner
                    else -> null
                }
                return p?.name ?: com.pokedaisy.app.companion.i18n.tr("PARTNER")
            }
            override fun identity(): com.pokedaisy.app.companion.data.coop.CoopIdentity {
                val id = currentCoopPair?.ownSave ?: return com.pokedaisy.app.companion.data.coop.CoopIdentity(ByteArray(8) { 0xFF.toByte() }, 0, 0)
                return com.pokedaisy.app.companion.data.coop.CoopIdentity(id.name, if (lookSession.isFemale) 1 else 0, (id.secretId shl 16) or id.trainerId)
            }
            override fun beforeSave(): Boolean {
                val r = rom ?: return false
                val save = SavesLocation.saveFor(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), r)
                return coopBackup(save)
            }
            override fun busyChanged(busy: Boolean) { RetroAchievements.coopBattle = busy }
            override fun playTogetherOnly() = coopLock?.ui?.value?.locked == true
            override fun pairKey() = currentCoopPair?.let { it.ownSave.key + ":" + it.partner.peer.hex }.orEmpty()
            override fun catchStore() = catchFile
            override fun log(text: String) { Log.i("pokedaisy/coop", text) }
        }
    }

    /** The running save copied into the backups folder before co-op writes to the game: true when a copy (or an equal one) is there. */
    private fun coopBackup(save: File): Boolean {
        val newest = SaveBackups.list(save).firstOrNull()
        if (SaveBackups.snapshot(save) != null) return true
        return runCatching { newest != null && SaveBackups.sameSave(newest.readBytes(), save.readBytes()) }.getOrDefault(false)
    }

    /** Stop with a suspend state if the co-op battle was restored. Otherwise boot from the battery save next time. */
    private fun stopEngineWithResume() {
        if (!coopShutdown.terminate()) {
            dropResumeState()
            engine.stop()
            return
        }
        states?.let { engine.stopWithSuspend(it.resumeFile) } ?: engine.stop()
    }

    private fun dropResumeState() {
        states?.resumeFile?.let {
            it.delete()
            com.pokedaisy.app.companion.data.SnapshotCache.fileFor(it.toOkioPath()).delete()
            RetroAchievements.progressFile(it).delete()
        }
    }

    /** A game button while a co-op overlay is up: the prompt's or wait's, kept from the game (like the QUICK MENU's). */
    private fun coopOverlayTakes(event: KeyEvent): Boolean? {
        if (!coopOverlay.takesKeys) return null
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_BACK) return null
        if (::hotkeys.isInitialized && hotkeysOn) {
            if (!down || event.repeatCount == 0) for (e in hotkeys.onKey(code, down)) handleHotkey(e)
            if (hotkeys.consumes(code)) return true
        }
        val key = menuKeyFor(input.buttonFor(code) ?: 0) ?: return null
        if (!down) {
            menuHeld.remove(code)
            return true
        }
        menuHeld.add(code)
        CoopBattles.takeKey(key)
        return true
    }

    /** A joystick's D-pad / stick while a co-op overlay is up: each direction that comes on is a press. */
    private fun coopMotion(event: MotionEvent): Boolean {
        val bits = GbaInput.motionBits(event) ?: return false
        val pressed = bits and coopHat.inv()
        coopHat = bits
        menuKeyFor(pressed)?.let { CoopBattles.takeKey(it) }
        return true
    }

    /** The co-op lock of the running game (PLAY TOGETHER ONLY), null without a pairing / off retail Emerald. The later UI reads `ui` and the actions from it. */
    @Volatile var coopLock: CoopLock? = null
        private set
    /** The lock as Compose state (the overlay and the settings row follow it) and the partner it plays with. */
    private val coopLockState = androidx.compose.runtime.mutableStateOf<CoopLock?>(null)
    private val coopLockPartner = androidx.compose.runtime.mutableStateOf("")
    private var coopLockReceive: kotlinx.coroutines.Job? = null

    private fun stopCoopLock() {
        coopLockReceive?.cancel()
        coopLockReceive = null
        coopLockState.value = null
        val lock = coopLock ?: return
        coopLock = null
        if (::engine.isInitialized) engine.coopLock = null
        lock.releaseGuards()   // at once: a held game must never outlive its lock, whatever the emu thread is doing
        runOnEmu { lock.close() }
    }

    private fun makeCoopLock(pair: CoopPair) = CoopLock(
        CoopSession.link, com.pokedaisy.app.companion.coop.lock.CoopLockStore(filesDir.toOkioPath() / "coop" / "locks.json"),
        pair.ownSave.key, pair.partner.save.key, MgbaLockNative,
        { coopBattleField.partySize() ?: error("no core") },
        android.os.SystemClock::elapsedRealtime,
        { eventId -> CoopBattles.storyPending(eventId) },
    )

    private fun prepareCoopStart(r: File, save: File) {
        CoopGuards.reset()
        val shutdown = newCoopShutdown().also { coopShutdown = it }
        if (!FeatureFlags.coop(this)) return
        val prefs = Prefs(this)
        val code = romCode
        val rev = romRev
        val endpoint = CoopSession.bind(applicationContext, prefs)
        coopEndpoint = endpoint
        val settings = CoopGameSettings(
            startup = coopStartup,
            ownSave = { currentCoopPair?.ownSave },
            refuseBattle = ::coopRefuses,
            pauseBattles = shutdown::stopBattle,
            resumeBattles = { if (engine.running && !engine.sleeping) shutdown.resume() },
            detach = shutdown::detachAfterEndpointStop,
        )
        coopSettings = settings
        endpoint.bindGameSettings(settings)
        if (!CoopEndpointSession.mayBeRetailEmerald(code, r.takeUnless(RomArchive::isArchive)?.length() ?: 0L)) {
            endpoint.startUnsupportedGame()
            return
        }
        val artRom = romData ?: r
        coopStartup.start(lookGender, resolve = {
            withContext(Dispatchers.IO) {
                val sha = coopRetailSha1(code, rev, RomArchive.romSize(r), { RomIdentity.sha1(r) }) { RomArchive.open(r).source() }
                val selection = endpoint.lookupGame(sha, save.toOkioPath())
                selection to selection.decide(false).pair?.let(::makeCoopLock)
            }
        }) { (selection, lock) ->
            if (isDestroyed || isFinishing || !engine.running) return@start
            val decision = selection.decide(gameCheats()?.hasEnabledCheats == true)
            val pair = decision.pair
            if (pair != null && lock != null) {
                currentCoopPair = pair
                coopLock = lock
                coopLockPartner.value = pair.partner.name
                coopLockState.value = lock
                lock.start()
                engine.coopLock = lock
                coopLockReceive = lookGender.launch { endpoint.link.messages.collect(lock::onMessage) }
                createCoopPresence(artRom, CoopPresenceChannel(endpoint.link))
                CoopBattles.attach(coopApp, endpoint.link, selection.romSha1)
            }
            endpoint.startForGame(decision)
        }
    }

    private fun recoverSaveTrade(): Boolean {
        if (SaveFileEdits.recover(this)) return true
        Toast.makeText(this, ClosedSaveEdits.recoveryError(), Toast.LENGTH_LONG).show()
        return false
    }

    /** Starts the loaded ROM's engine, resuming where it was left; false with no ROM. */
    private fun startGame(): Boolean {
        val r = rom ?: return false
        n3ds?.let { it.start(); return true }
        val st = states ?: return false
        if (isFinishing || !engine.sleeping && !recoverSaveTrade()) {
            finish()
            return false
        }
        AudioEffects.load(Prefs(this))
        AudioEffectsTrack.open(this)
        val save = SavesLocation.saveFor(this, Prefs(this), r)
        engine.ndsCore = dsCoreId   // DS CORE (pickDsCore)
        // Cheats may have changed in the library's Settings since the game was last up.
        syncCheats(reread = true)
        // Back from sleep: the game held on its frame (onPause) and goes on from it. The state written as it went to
        // sleep is used up, as a resume's is once loaded.
        if (engine.sleeping) {
            coopShutdown.resume()   // [pauseGame] ended any joined battle before the sleep: battles may start again
            engine.wake()
            st.files.removeState(st.files.resumeFile)
            return true
        }
        prepareCoopStart(r, save)
        // Auto-resume from wherever was written most recently: normally that's
        // the auto-suspend snapshot from the last close, but if that session
        // ended in a crash instead of a clean close (stale/missing resumeFile),
        // fall back to the newest manual save slot instead. Stage the winner
        // into resumeFile itself so the one-shot "load then delete" below only
        // ever touches that dedicated file, never a numbered slot.
        // A fresh boot stays fresh across crashes until a new automatic snapshot commits.
        if (st.freshBootFile.exists()) {
            st.files.removeState(st.files.resumeFile)
            waitForPlay()
            engine.start(romData ?: r, save, null)
            view.postDelayed({ (engine.lastError ?: engine.lastNotice)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }, 1500)
            return true
        }
        // RESUME GAMES off: a launch boots from the save (the title screen); only coming back to
        // this still-open game (after HOME / another app) picks up its suspend state.
        val launch = freshLaunch
        freshLaunch = false
        if (launch && !Prefs(this).autoResume) {
            com.pokedaisy.app.companion.data.StateResume.startFresh(st.files)
            waitForPlay()
            engine.start(romData ?: r, save, null)
            view.postDelayed({ (engine.lastError ?: engine.lastNotice)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }, 1500)
            return true
        }
        // DS CORE changed since a DS game was left: the state its core left waits beside (SaveStates.parkedResume);
        // the core about to run takes its own back when the game has no other resume state.
        if (romIsNds && !st.resumeFile.let { it.isFile && it.length() > 0 }) {
            SaveStates.parkedResume(st.resumeFile, engine.ndsCore).takeIf { it.isFile }?.let {
                runCatching { st.files.moveState(it.toOkioPath(), st.files.resumeFile) }
            }
        }
        val resume = runCatching { com.pokedaisy.app.companion.data.StateResume.stage(st.files) }
            .getOrNull()?.let { File(it.toString()) }
        // The companion's data as that state left it, until the game's own is read (a few KB).
        // Not over live data (back from HOME: the game was never closed).
        if (resume != null && !telemetry.snapshot.value.hasData) {
            com.pokedaisy.app.companion.data.SnapshotCache.read(resume.toOkioPath())?.let { telemetry.showCached(it) }
        }
        // Resumed from a state: the player was in the game (a menu included). A boot: the title screen first.
        if (resume != null) nowPlaying() else waitForPlay()
        engine.start(romData ?: r, save, resume)
        if (!engine.running) telemetry.dropCached()
        view.postDelayed({ (engine.lastError ?: engine.lastNotice)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }, 1500)
        return true
    }

    /** Requests originate between frames; common sampling owns capture, decode, coalescing and delivery. */
    private fun startSample(e: EmulatorEngine) {
        if (::engine.isInitialized && engine === e) e.playback.sample()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.playback.quiesce()
        if (::touchControls.isInitialized) touchControls.releaseInputWindows()
        stopListeningForFold()
        gameTips.detach()
        Hotkeys.dsActive = { false }
        endN3ds()
        // Gone while the game slept (onPause held it): stop it now, as a close would have.
        if (::engine.isInitialized && engine.running) {
            view.unbindCoreBlocking()
            stopEngineWithResume()
        } else if (coopEndpoint != null) coopShutdown.terminate()
        ffMusicRenderer?.stop()
        ffMusicRenderer = null
        clickSound?.release()
        clickSound = null
        RetroAchievements.onUnlockSound = null
        unlockSound?.release()
        unlockSound = null
        stageOwner?.destroy()
        stageOwner = null
        stageRecomposer.cancel()
        stageRecomposing.cancel()
        stopCoopPresence()
        lookGender.cancel()
        lookSession.close()
        playScope.cancel()
        com.pokedaisy.app.companion.ui.GameStart.waiting = false
        if (::engine.isInitialized) engine.playback.close()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        tipsResumed = false
        com.pokedaisy.app.companion.FeatureTips.scheduler?.save()
        // Android's pause around moving this activity to the other screen (moveToDisplay): the game runs on.
        val moving = movingSince != 0L && android.os.SystemClock.uptimeMillis() - movingSince < MOVE_PAUSE_MS
        movingSince = 0L
        if (moving) { movePause = true; return }
        setBottomFocus(false)
        pauseGame()
        // An app covered on the second screen comes back while the game isn't up.
        SecondScreenHolder.uncoverApp()
    }

    override fun onStop() {
        super.onStop()
        // The move's pause turned out to be a real one: stop the game as onPause would have.
        if (movePause) { movePause = false; pauseGame() }
    }

    private fun startPlayClock() {
        playClock?.cancel()
        playTick = android.os.SystemClock.uptimeMillis()
        playClock = playScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                countPlay()
            }
        }
    }

    private fun stopPlayClock() {
        playClock?.cancel()
        playClock = null
        countPlay()
    }

    /** Adds the time since the last count, if the game ran meanwhile; a step is at most a minute (a stall adds no lump). */
    private fun countPlay() {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = (now - playTick).coerceIn(0L, 60_000L)
        playTick = now
        if (::engine.isInitialized && engine.running && !engine.sleeping) {
            val prefs = Prefs(this)
            prefs.playMillis += dt
        }
    }

    /** Everything onPause does: the game stops (or holds, asleep), its listeners and the second screen go. */
    private fun pauseGame() {
        if (::engine.isInitialized) {
            gameActions.cancelTouch()
            engine.rewindHold.revoke()
        }
        stopPlayClock()
        AudioEffectsTrack.close(this)
        keyCapture.cancel()
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { inputManager.unregisterInputDeviceListener(inputDeviceListener) }
        runCatching { unregisterReceiver(batteryReceiver) }
        presentation?.let { it.dismiss(); it.releaseContent() }
        presentation = null
        SecondScreenHolder.close()
        ffMusicPlayer.release()
        // A 3DS game holds between two frames (its own thread, its surfaces may go meanwhile); it stops in onDestroy.
        n3ds?.let { it.setPaused(true); view.onPause(); return }
        if (::engine.isInitialized && romKey.isNotEmpty()) {
            Prefs(this).setSpeedIndexFor(romKey, engine.speedIndex)
        }
        // The screen went off: the game holds on its frame with the core up and goes on from there on wake
        // (EmulatorEngine.sleep; startGame wakes it). HOME, another app or closing the game stop it as before.
        // Restore a joined battle before sleeping, or before tearing down a game that won't stay asleep.
        val held = coopShutdown.saveIfRestored {
            val asleep = !(getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
            asleep && states?.let { engine.sleep(it.resumeFile) } == true
        }
        if (!held) {
            // Before the engine frees the frame buffer the GL thread draws from.
            view.unbindCoreBlocking()
            stopEngineWithResume()
        }
        view.onPause()
    }

    /** Battery level for the companion's SETTINGS title and the status bar (see DeviceBattery). */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        DeviceBattery.status.value = BatteryStatus(level * 100 / scale, charging)
        intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { batteryTempC = it / 10f }
    }

    /**
     * Puts the game and the companion on the screens: with a second screen the
     * game here and the companion there - or the other way round with SWAP
     * SCREENS (a device whose main display is its bottom screen); with one, the
     * game here and the companion in its side panel. The screens swap their
     * contents, not the activity: the stage (game view included - a
     * GLSurfaceView makes a new GL thread when it's attached again, see
     * EmulatorView's bindCore) moves into the second screen's window and a
     * companion becomes this activity's content.
     */
    /**
     * The player closed the companion (SETTINGS > CLOSE COMPANION): its screen is the device's until BACK. With SWAP
     * SCREENS that's the main one, so this activity moves to the second screen meanwhile (the game its content).
     */
    private var companionClosed = false
    /** A DS game's bottom display (touch screen or companion), while it's up. */
    private var dsBottom: NdsBottomHost? = null

    /**
     * Closed while swapped: the game has the second screen to itself and the companion opens over it, as on one screen
     * (the side panel's tab, the HUD's COMPANION), its SETTINGS offering OPEN COMPANION. Compose state for that button.
     */
    private val companionOverGame = androidx.compose.runtime.mutableStateOf(false)

    /** The companion back on its own screen (the game back to the main screen first if it moved off it; the screens are
     *  arranged again once it's there, onConfigurationChanged). */
    private fun reopenCompanion() {
        if (!companionClosed) return
        companionClosed = false
        if (!Screens.isOnMainDisplay(this)) moveToDisplay(Display.DEFAULT_DISPLAY)
        syncPresentation()
    }

    /** A DS game with no second screen for its touch screen (GitHub #244): held upright it goes where the companion would
     * ([DsTouchPane], the dock under the game), the companion behind its COMPANION switch; sideways DS LAYOUT places it
     * ([syncDsStage]). Compose state, set by [syncPresentation]. */
    private val dsOneScreen = androidx.compose.runtime.mutableStateOf(false)

    @androidx.compose.runtime.Composable
    private fun dsOrCompanion(companion: @androidx.compose.runtime.Composable () -> Unit) {
        if (dsOneScreen.value) DsTouchPane(Prefs(this).dsBottomCompanion, { on -> Prefs(this).dsBottomCompanion = on }, companion)
        else companion()
    }

    /** The display this activity was on at the last [syncPresentation]: a move to another one re-arranges the screens. */
    private var shownOnDisplay = Display.DEFAULT_DISPLAY

    private fun syncPresentation() {
        if (!::engine.isInitialized) return
        val target: Display? = secondScreen()
        // A DS game's touch screen stays up whatever CLOSE COMPANION says: the companion is on the game's screen, as on
        // one-screen devices. SWAP SCREENS trades its two screens like a GBA game's: the top one (the stage) goes to
        // the second display, the touch screen with its COMPANION toggle (NdsBottomHost) to this one.
        val swap = target != null && Screens.swapped(this, Prefs(this), target) && !debugMirror
        shownOnDisplay = Screens.displayOf(this)
        // The companion closed, or this activity off the main screen (the companion closed while swapped, or on its
        // way back from there): the game alone, here. A Presentation can't share this activity's display.
        if (((companionClosed && !twoScreenGame) || shownOnDisplay != Display.DEFAULT_DISPLAY) && target != null) {
            setBottomFocus(false)
            presentation?.let { it.dismiss(); it.releaseContent() }
            presentation = null
            SecondScreenHolder.close()
            showStage()
            // Closed while swapped, the game on the second screen: the companion opens over it there, as on one screen.
            // It fills that screen (the companion's own, on a device like the Thor), whatever COMPANION VIEW says.
            val over = companionClosed && shownOnDisplay != Display.DEFAULT_DISPLAY && !debugMirror
            companionOverGame.value = over
            sidePanel.setFullScreen(over)
            syncSingleScreen(over)
            return
        }
        companionOverGame.value = false
        sidePanel.setFullScreen(false)
        val current = presentation
        if (current != null && target != null && current.display.displayId == target.displayId &&
            current.isShowing && swap == (mainCompanion != null)
        ) return
        setBottomFocus(false)
        current?.let { it.dismiss(); it.releaseContent() }
        presentation = null
        SecondScreenHolder.close()
        dsBottom = null
        syncOrientation(target != null)
        // One screen and a DS game: its touch screen takes the companion's place, the side panel locked beside the game.
        dsOneScreen.value = romIsNds && target == null && !debugMirror
        // One screen: the companion goes in a panel beside / under the game instead
        // (not with the debug mirror, which already shows it there).
        syncSingleScreen((target == null || twoScreenGame) && !debugMirror)
        if (swap) showMainCompanion() else showStage()
        if (target == null) return
        presentation = runCatching {
            DualScreenPresentation(this, target) { ctx ->
                if (swap) stage
                // A DS game: its touch screen down here (NdsBottomFrame), or the companion in its place - the player's
                // pick, switched from either (NdsBottomHost); the companion's window over the game above stays too.
                else if (twoScreenGame) dsBottomHost(ctx) {
                    DualScreenPresentation.companionView(
                        ctx, telemetry, stateSlots, companionSettings, battleInput, companionBack, playClick, RetroAchievements,
                        initialTab = companionStartTab, initialSettingsRow = companionStartRow, statusBar = companionStatusBar, itemUse = itemUse, fly = flyAction, askForSupport = askForSupport, gamePick = gamePick, shareBestEffort = shareBestEffort,
                        keys = bottomKeys, focus = { bottomFocus.value },
                    )
                }
                else DualScreenPresentation.companionView(
                    ctx, telemetry, stateSlots, companionSettings, battleInput, companionBack, playClick, RetroAchievements,
                    initialTab = companionStartTab, initialSettingsRow = companionStartRow, statusBar = companionStatusBar, itemUse = itemUse, fly = flyAction, askForSupport = askForSupport, gamePick = gamePick, shareBestEffort = shareBestEffort,
                    keys = bottomKeys, focus = { bottomFocus.value },
                )
            }.also { p ->
                p.onBarsShowing = { SecondScreenHolder.coverApp(this, target) }
                p.show()
            }
        }.onFailure { Log.w("pokedaisy", "presentation failed", it) }.getOrNull()
        if (presentation != null) SecondScreenHolder.open(this, target)
        companionStartTab = "PARTY"
        companionStartRow = null
        if (presentation == null && swap) {
            // The game has to be somewhere: back here, the companion beside it.
            showStage()
            syncSingleScreen(!debugMirror)
        }
    }

    /** The second display the companion goes on, or null: none, or SINGLE SCREEN keeps the game to this one. */
    private fun secondScreen(): Display? {
        // The display service's answer, kept half a second: SETTINGS' getters ask it row by row while the page composes.
        val now = android.os.SystemClock.uptimeMillis()
        val second = secondScreenAsked.takeIf { now - it.first < 500 }?.second ?: Screens.second(this).also { secondScreenAsked = now to it }
        return second?.takeIf { !Prefs(this).singleScreen }
    }
    private var secondScreenAsked: Pair<Long, Display?> = 0L to null

    /** The companion on the game's screen: beside the game held sideways, under it upright - and on a foldable half
     * open, on the other side of the hinge (FOLD LAYOUT: below it in TABLETOP, on the right page in BOOK). */
    private fun syncSingleScreen(on: Boolean) {
        singleScreen = on
        hudSingleScreen.value = on
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val fold = foldPosture().takeIf { on }
        foldActive = fold
        val tabletop = fold?.kind == com.pokedaisy.app.companion.FoldPosture.Kind.TABLETOP
        val book = fold?.kind == com.pokedaisy.app.companion.FoldPosture.Kind.BOOK
        // TABLETOP is the upright panel at the hinge whichever way the phone is held; BOOK the side panel at the hinge.
        val under = tabletop || (portrait && !book)
        portraitPanel.hinge = if (tabletop) fold!!.start..fold.end else null
        sidePanel.book = if (book) fold!!.start..fold.end else null
        // Off first, so the two never hold the game's margins at once.
        if (under) sidePanel.setEnabled(false) else portraitPanel.setEnabled(false)
        sidePanel.setEnabled(on && !under)
        portraitPanel.setEnabled(on && under)
        // Upright, the game drops its overlay (and gets it back sideways); a DS game's screens are arranged for the way
        // it's held (syncGameScreen's syncDsStage).
        syncGameScreen()
        // The touch controls' own layout for the way the phone is held.
        syncTouchControls()
    }

    // --- FOLD LAYOUT ---------------------------------------------------------------

    /** The hinge Jetpack WindowManager last reported for this window (null: none - not a foldable, or folded shut). */
    private var foldFeature: androidx.window.layout.FoldingFeature? = null

    /** The fold layout in use since the last [syncSingleScreen] (null: the usual one-screen layouts). */
    private var foldActive: com.pokedaisy.app.companion.FoldPosture? = null

    private val foldTracker by lazy {
        androidx.window.java.layout.WindowInfoTrackerCallbackAdapter(androidx.window.layout.WindowInfoTracker.getOrCreate(this))
    }
    private val foldListener = androidx.core.util.Consumer<androidx.window.layout.WindowLayoutInfo> { info -> onWindowLayout(info) }
    private var foldListening = false

    /** The stage resized with a hinge about (unfolding, a rotation): the hinge is placed again in its new size. */
    private val foldRelayout = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (foldFeature != null && (r - l != or - ol || b - t != ob - ot)) stage.post { syncSingleScreen(singleScreen) }
    }

    /** Starts listening for the hinge (onResume; it reports the current one at once). */
    private fun listenForFold() {
        if (foldListening) return
        if (::stage.isInitialized) {
            stage.removeOnLayoutChangeListener(foldRelayout)
            stage.addOnLayoutChangeListener(foldRelayout)
        }
        foldListening = runCatching {
            foldTracker.addWindowLayoutInfoListener(this, androidx.core.content.ContextCompat.getMainExecutor(this), foldListener)
        }.onFailure { Log.w("pokedaisy", "no window layout info", it) }.isSuccess
    }

    private fun stopListeningForFold() {
        if (!foldListening) return
        foldListening = false
        runCatching { foldTracker.removeWindowLayoutInfoListener(foldListener) }
    }

    /** The hinge moved, opened, closed or went flat: lay the screen out again (on the UI thread). */
    private fun onWindowLayout(info: androidx.window.layout.WindowLayoutInfo) {
        val f = info.displayFeatures.filterIsInstance<androidx.window.layout.FoldingFeature>().firstOrNull()
        if (f != null) {
            val prefs = Prefs(this)
            if (!prefs.foldSeen) prefs.foldSeen = true
        }
        if (f == foldFeature) return
        foldFeature = f
        if (::sidePanel.isInitialized && ::portraitPanel.isInitialized) syncSingleScreen(singleScreen)
    }

    /** FOLD LAYOUT's posture now, in the stage's coordinates; null = off, no hinge, or not one to lay out around. */
    private fun foldPosture(): FoldPosture? {
        val prefs = Prefs(this)
        val mode = prefs.foldMode
        if (!::stage.isInitialized || mode == FoldMode.OFF || debugMirror) return null
        val f = foldFeature ?: return null
        val at = IntArray(2)
        stage.getLocationInWindow(at)
        val w = stage.width.takeIf { it > 0 } ?: window.decorView.width
        val h = stage.height.takeIf { it > 0 } ?: window.decorView.height
        val b = f.bounds
        return FoldPosture.of(
            halfOpened = f.state == FoldingFeature.State.HALF_OPENED,
            separating = f.isSeparating,
            horizontal = f.orientation == FoldingFeature.Orientation.HORIZONTAL,
            left = b.left, top = b.top, right = b.right, bottom = b.bottom,
            stageX = at[0], stageY = at[1], stageW = w, stageH = h,
            foldMode = mode,
        )
    }

    /** The touch controls' upright layout: a phone held upright with the companion under the game - not a foldable
     * in TABLETOP held sideways, whose game half is landscape. */
    private val touchUpright: Boolean
        get() = ::portraitPanel.isInitialized && portraitPanel.enabled &&
            (!portraitPanel.tabletop || resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)

    /** Locked to landscape with a second screen (the Thor's top one); else the device's rotation decides. */
    private fun syncOrientation(twoScreens: Boolean) {
        val want = if (twoScreens || debugMirror) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
        if (requestedOrientation != want) requestedOrientation = want
    }

    // A rotation (configChanges keeps the activity, and the game, running through it).
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        // The touch controls' input windows keep frozen resources: another thread's windows can't take a configuration
        // update from this one (Samsung's Android makes one, a crash on rotations in 1.5.0: TouchInputWindows).
        if (::touchControls.isInitialized) touchControls.inputWindowsConfigurationChanged()
        super.onConfigurationChanged(newConfig)
        // AUTO dark mode follows the device's (uiMode is in configChanges: the game keeps running).
        com.pokedaisy.app.companion.ui.theme.DarkMode.systemDark =
            newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (::sidePanel.isInitialized) syncSingleScreen(singleScreen)
        followDisplay()
    }

    /**
     * Moves this activity to [displayId] (CLOSE COMPANION while swapped, BACK from there). Android delivers that as a
     * new intent, with a pause and a resume around it: the game keeps running through those ([movePause]).
     */
    private fun moveToDisplay(displayId: Int) {
        movingSince = android.os.SystemClock.uptimeMillis()
        val moved = Screens.moveToDisplay(this, displayId)
        if (!moved) movingSince = 0L
        CrashReports.breadcrumb("move to display $displayId: ${if (moved) "asked" else "refused"}")
    }

    /** When [moveToDisplay] asked Android to move this activity (0 = not moving). */
    private var movingSince = 0L

    /** onPause let the game run on: it was [moveToDisplay]'s pause, which onResume (or a real stop, onStop) ends. */
    private var movePause = false

    /** Moved to the other screen (CLOSE COMPANION while swapped, BACK from there): the screens' contents follow. */
    private fun followDisplay() {
        if (Screens.displayOf(this) == shownOnDisplay) return
        syncPresentation()
        val decor = window.decorView
        decor.removeCallbacks(refocus)
        decor.postDelayed(refocus, REFOCUS_DELAY_MS)
    }

    /**
     * Android can lose this window's focus on the way to another display: the Thor's input dispatcher asked to focus it
     * while its input info still named the old display ("Found window ... in display 0, but it should belong to display
     * 4"), dropped the focus, and never asked again - the game took no buttons until it was closed. Flipping the window
     * unfocusable and back makes the window manager ask again.
     */
    private val refocus = Runnable {
        if (isFinishing || hasWindowFocus()) return@Runnable
        Log.i("pokedaisy", "no focus after a display move: asking again")
        CrashReports.breadcrumb("no focus after a display move: asking again")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.decorView.post { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
    }

    // A move between two screens of one size changes no configuration; the moved activity gets the focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        CrashReports.breadcrumb("game window focus: $hasFocus (display ${Screens.displayOf(this)})")
        if (hasFocus) followDisplay()
        else if (::engine.isInitialized) {
            cancelRewindForOverlay()
            // The OS may deliver UP to another window. Reset the old parser's owners before focus returns.
            if (::hotkeys.isInitialized) hotkeys.releaseAll().forEach(::handleHotkey)
            gameActions.cancelHardware()
        }
    }

    /** The game's stage as this activity's content (dropping the swapped companion). */
    private fun showStage() {
        if (stage.parent != null && mainCompanion == null) return
        mainCompanion = null
        mainCompanionOwner?.destroy()
        mainCompanionOwner = null
        (stage.parent as? android.view.ViewGroup)?.removeView(stage)
        setContentView(stage)
    }

    /** A game with a touch screen of its own (DS, 3DS): on two screens that screen goes where a GBA game's companion
     * would, the companion behind its COMPANION switch ([NdsBottomHost]). */
    private val twoScreenGame get() = romIsNds || romIs3ds

    /** [NdsBottomHost] for the running game: a 3DS game's touch surface moved into it (out of the stage's DS window). */
    private fun dsBottomHost(ctx: Context, companion: () -> View): NdsBottomHost {
        val screen = n3ds?.touchView?.also { v ->
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            // Alone on its display: under the window's views (the FILL tab over it), not over them as when it floats.
            v.setZOrderMediaOverlay(false)
            n3dsTouchOnSecond = true
            v.post { n3ds?.surfacesChanged() }
        }
        return NdsBottomHost(
            ctx, companion, Prefs(this).dsBottomCompanion, onSwitch = { on -> Prefs(this).dsBottomCompanion = on },
            fill = Prefs(this).dsTouchFill, screen = screen, releaseTouch = { n3ds?.releaseTouch() ?: MgbaCore.pkNdsTouch(false, 0, 0) },
        ).also { dsBottom = it }
    }

    /** The companion as this activity's content (the stage then goes to the second screen); for a DS game its touch
     *  screen with the companion behind the COMPANION toggle ([NdsBottomHost]), as on the bottom display unswapped. */
    private fun showMainCompanion() {
        val shown = mainCompanion
        if (shown != null && (shown is NdsBottomHost) == twoScreenGame) {
            dsBottom = shown as? NdsBottomHost
            return
        }
        if (shown != null) {
            mainCompanionOwner?.destroy()
            mainCompanionOwner = null
            mainCompanion = null
        }
        val owner = ComposeHostOwner().apply { create(); resume() }
        val companion = {
            DualScreenPresentation.companionView(
                this, telemetry, stateSlots, companionSettings, battleInput, companionBack, playClick, RetroAchievements,
                initialTab = companionStartTab, initialSettingsRow = companionStartRow, statusBar = companionStatusBar, itemUse = itemUse, fly = flyAction, askForSupport = askForSupport, gamePick = gamePick, shareBestEffort = shareBestEffort,
                keys = bottomKeys.takeIf { !twoScreenGame }, focus = { bottomFocus.value },
            )
        }
        val view = if (twoScreenGame) dsBottomHost(this, companion) else companion()
        view.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
        }
        mainCompanionOwner = owner
        mainCompanion = view
        setContentView(view)
    }

    /** Show the status bar above the game per SETTINGS > STATUS BAR, fit, stretch or widen
     * the game per SETTINGS > ASPECT, and draw it through SETTINGS > SHADERS. */
    private fun syncGameScreen() {
        if (!::statusBar.isInitialized) return
        val prefs = Prefs(this)
        // STATUS BAR > COMPANION: the strip goes over the companion's tabs instead.
        statusBar.visibility = if (prefs.statusBar && !prefs.statusBarOnCompanion) View.VISIBLE else View.GONE
        performanceShown.value = prefs.showPerformance
        hudOn.value = prefs.fieldHud
        hudStyle.value = prefs.fieldHudStyle
        itemToastOn.value = prefs.itemPopups
        quickShown.value = prefs.quickMenuActions
        battleButtonsOn.value = prefs.battleButtons
        battleHints.value = prefs.showHints && prefs.battleInfo
        battleStyle.value = com.pokedaisy.app.companion.BattleStyle.of(prefs.battleStyle)
        com.pokedaisy.app.companion.ui.AchievementIcons.shown = prefs.raIcons
        hudUpright.value = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        companionStatusBarVisible = prefs.statusBar && prefs.statusBarOnCompanion
        val stageLayout = stage.viewport
        // WIDE on a game it doesn't know is ORIGINAL; otherwise the game takes the whole area and
        // the core draws the overworld into the room beside it.
        val aspect = prefs.gameAspect.let { if (it == GameAspect.WIDE && wideGame == WideScreen.OFF) GameAspect.ORIGINAL else it }
        // SCREEN > OVERLAY: not on a phone held upright, where the game is its own shape across the top, nor with
        // EXPAND, whose map fills the screen.
        // Nor on a DS game's layouts (DS LAYOUT), which put the game's own backdrop round it.
        view.overlay = if (stageLayout.topAligned == true || aspect == GameAspect.WIDE || dsStageOn) null else when (val c = OverlayChoice.of(prefs.overlay)) {
            OverlayChoice.None -> null
            is OverlayChoice.BuiltIn -> EmulatorView.OverlaySource.BuiltIn(c.style)
            is OverlayChoice.Imported -> overlayStore.get(c.id)?.let { EmulatorView.OverlaySource.Imported(it) }
        }
        // With an overlay the view takes all the room and places the game in it itself.
        stageLayout.stretch = aspect != GameAspect.ORIGINAL || view.overlay != null
        // A phone upright: WIDE fills everything above the companion, the map going on above and below the game.
        stageLayout.expand = aspect == GameAspect.WIDE
        if (::portraitPanel.isInitialized) portraitPanel.expand = aspect == GameAspect.WIDE
        // EXPAND decides whether UNDER GAME gives the touch controls a strip of their own.
        syncTouchControls()
        view.stretch = aspect == GameAspect.STRETCH
        view.wide = aspect == GameAspect.WIDE
        syncTouchExtras()
        if (::engine.isInitialized) {
            engine.wideGame = if (aspect == GameAspect.WIDE) wideGame else WideScreen.OFF
            // A Game Boy game's field read for the HUD is pk_wide_gb.c's scene, EXPAND or not.
            engine.gbSceneGame = if (romIsGameBoy) wideGame else WideScreen.OFF
        }
        view.gbaColors = prefs.gbaColors
        view.unlit = prefs.unlitScreen
        // LIGHT COMPANION: no shader over the companion's views (one more pass over each, every frame).
        val companionShaders = prefs.companionShaders && !com.pokedaisy.app.companion.LightCompanion.on
        CompanionColors.set(
            CompanionShaders.settings(prefs.gbaColors, prefs.screenFilter, prefs.gridStrength, companionShaders),
            CompanionShaders.settings(prefs.gbaColors, prefs.screenFilter, prefs.gridStrength,
                !com.pokedaisy.app.companion.LightCompanion.on),
        )
        view.screenEffect = ScreenShaders.effectFor(prefs.screenFilter)
        view.screenGrid = ScreenShaders.gridFor(prefs.screenFilter, prefs.gridStrength)
        view.smoothing = prefs.smoothing
        syncDsStage(aspect)
    }

    /** The DS layouts are on: a DS game on one screen (or SINGLE SCREEN), held sideways, the game in this activity. */
    private val dsStageOn: Boolean
        get() = (romIsNds || romIs3ds) && secondScreen() == null && !debugMirror && !companionOverGame.value && foldActive == null &&
            resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT

    /**
     * SETTINGS > DS LAYOUT onto the stage ([GameScreenView.dsArrange]): where the game and its touch screen go, the
     * game's backdrop around them ([aspect] ORIGINAL keeps the game alone at its 4:3; EXPAND / STRETCH fill), and whether
     * the touch screen floats in ([dsFloats]). Off: the stage as for any other game.
     */
    private fun syncDsStage(aspect: GameAspect) {
        if (!::stage.isInitialized || !::dsBackdropView.isInitialized) return
        val on = dsStageOn
        val chosen = Prefs(this).dsLayout.forGame(com.pokedaisy.app.companion.DsLayout.followed(romCode) || romOrigin || romIs3ds)
        // A menu screen (the party, the bag, the Pokédex...) has something to say on both screens: side by side then, not
        // the touch screen over the top one (whose 4:3 left bars beside it in EXPAND).
        val layout = if (dsMenuUp && chosen.floats) com.pokedaisy.app.companion.DsLayout.SIDE_BY_SIDE else chosen
        // EXPAND / STRETCH fill the screen only where EXPAND has the scene to widen; elsewhere the backdrop's beside it.
        val fill = aspect != GameAspect.ORIGINAL && (dsWide || aspect == GameAspect.STRETCH)
        stage.viewport.dsArrange = if (on) dsArranger(layout, fill) else null
        if (romIs3ds) { syncN3dsStage(on, layout); return }
        // PICTURE IN PICTURE's inset stands off the game behind a white edge.
        val pip = layout == com.pokedaisy.app.companion.DsLayout.PICTURE_IN_PICTURE
        val frame = if (on && pip) dp(2) else 0
        stage.viewport.dsTouchFrame = frame
        dsTouchView.setPadding(frame, frame, frame, frame)
        dsTouchView.setBackgroundColor(if (pip) Color.WHITE else Color.TRANSPARENT)
        dsTouchView.isClickable = false
        dsBackdropView.visibility = if (on) View.VISIBLE else View.GONE
        dsTouchView.visibility = if (on && layout != com.pokedaisy.app.companion.DsLayout.ONE_SCREEN) View.VISIBLE else View.GONE
        dsFloats.value = on && layout.floats
        dsPip.value = on && pip
        dsFill.value = on && fill
        // The X menu floats in: it comes back after a menu screen however that was left (the game closes it after touch).
        if (::engine.isInitialized) engine.dsKeepStartMenu = on && chosen.floats
        // The black between a menu screen and the field goes by flat out (the overworld's picture covers it).
        if (::engine.isInitialized) engine.dsQuickLoads = on
        dsCornerTaken.value = on && (layout == com.pokedaisy.app.companion.DsLayout.LARGE_SMALL || layout == com.pokedaisy.app.companion.DsLayout.PICTURE_IN_PICTURE)
        // Over the touch screen (beside the game, inset in it, floating over it): a tap on no button reaches it.
        if (::touchControls.isInitialized) touchControls.passMisses = on
        if (!dsFloats.value) dsWindowManual.value = false
        // No stylus left down on a touch screen that went away.
        if (!on) runCatching { MgbaCore.pkNdsTouch(false, 0, 0) }
        // The game's box may have moved without changing size: the HUD's rects follow it after the layout.
        view.post { view.reportGamePixel() }
    }

    /** One arranger per (layout, fill): the stage lays out again only when it changes. */
    private var dsArrangerKey: Pair<com.pokedaisy.app.companion.DsLayout, Boolean>? = null
    private var dsArrangerFn: ((Int, Int) -> com.pokedaisy.app.companion.DsArrangement)? = null
    private fun dsArranger(layout: com.pokedaisy.app.companion.DsLayout, fill: Boolean): (Int, Int) -> com.pokedaisy.app.companion.DsArrangement {
        val key = layout to fill
        // (A 3DS game's floating touch screen moves the arrangement: no reuse there.)
        if (!romIs3ds) dsArrangerFn?.takeIf { dsArrangerKey == key }?.let { return it }
        val fn: (Int, Int) -> com.pokedaisy.app.companion.DsArrangement = if (romIs3ds) { w: Int, h: Int ->
            n3dsArrange(layout, w, h, fill)
        } else { w: Int, h: Int -> com.pokedaisy.app.companion.DsLayouts.arrange(layout, w, h, fill) }
        dsArrangerKey = key
        dsArrangerFn = fn
        return fn
    }

    /** The DS LAYOUT hotkey: the next layout, saved as SETTINGS > DS LAYOUT's, and its name over the game. A game whose
     * touch screen isn't followed skips ONE SCREEN (it would look as PICTURE IN PICTURE does there). */
    private fun nextDsLayout() {
        if (!dsStageOn) return
        val prefs = Prefs(this)
        val followed = com.pokedaisy.app.companion.DsLayout.followed(romCode) || romOrigin || romIs3ds
        val all = com.pokedaisy.app.companion.DsLayout.entries.filter { followed || it != com.pokedaisy.app.companion.DsLayout.ONE_SCREEN }
        val now = prefs.dsLayout.forGame(followed)
        val next = all[(all.indexOf(now) + 1).mod(all.size)]
        prefs.dsLayout = next
        syncGameScreen()
        showHud(tr("DS LAYOUT") + ": " + tr(next.label))
    }

    /** DS LAYOUT puts the touch screen in the screen's bottom right corner (LARGE + SMALL, PICTURE IN PICTURE): the HUD
     * keeps clear of it (FieldHud's dsCorner). */
    private val dsCornerTaken = androidx.compose.runtime.mutableStateOf(false)

    /** DS LAYOUT is PICTURE IN PICTURE now: the game's own command panel is in its inset, so a battle gets no BATTLE
     * BUTTONS / DS battle menu over it, and that panel doesn't float in either. */
    private val dsPip = androidx.compose.runtime.mutableStateOf(false)

    /** DS LAYOUT fills the screen with the game (EXPAND, STRETCH): a battle's text box runs across it, not just the 4:3. */
    private val dsFill = androidx.compose.runtime.mutableStateOf(false)

    /** A DS battle's menu shows / takes its keys ([com.pokedaisy.app.companion.ui.DsBattleMenu]'s shown / active): set
     * by the HUD, drawn by the floating window's view. */
    private val dsStripShown = androidx.compose.runtime.mutableStateOf(false)
    private val dsStripActive = androidx.compose.runtime.mutableStateOf(true)

    /** The share of the game's height, from the bottom, a touch screen over it covers (PICTURE IN PICTURE): the HUD's
     * party keeps above it. */
    private val dsHudClear = androidx.compose.runtime.mutableStateOf(0f)

    /** A DS game's menu screen is up ([EmulatorEngine.dsMenuScreen]): [syncDsStage] shows both screens side by side. */
    private var dsMenuUp = false

    /** A DS scene EXPAND widens ([EmulatorEngine.dsWideScene]); off, [syncDsStage] keeps the game at its 4:3. */
    private var dsWide = true

    /** A DS battle's menus ([com.pokedaisy.app.companion.ui.DsBattleMenu]): their cursor takes the D-pad ([dsBattleTakes]). */
    private val dsBattleMenu = com.pokedaisy.app.companion.ui.DsBattleMenuState()
    /** The hat's directions last seen by [dsBattleMotion] (each new one is a press). */
    private var dsBattleHat = 0

    /** A tap on the dim beside the floating touch screen: one opened by hand closes; the game's own (its X menu, a menu
     * screen) gets B, its own way back - not in a battle, where B can answer a question. */
    private fun dismissDsTouch() {
        if (dsWindowManual.value) { dsWindowManual.value = false; return }
        if (telemetry.snapshot.value.inBattle) return
        input.setPress(CoreKey.B)
        window.decorView.postDelayed({ input.setPress(0) }, DS_B_PRESS_MS)
    }

    /** The TOUCH SCREEN hotkey and the HUD's TOUCH: the floating touch screen opened by hand, or closed again. */
    private fun toggleDsTouch() {
        if (romIs3ds) { if (!(n3dsFloating.value && n3dsBattleScreenUp)) setN3dsFloating(!n3dsFloating.value); return }
        if (!dsFloats.value) return
        dsWindowManual.value = !dsWindowManual.value
    }

    // ---- 3DS games (n3ds/N3dsSession, docs/3DS_GAMES_PLAN.md) --------------------------------------------------------

    /**
     * A 3DS cart in place of [loadRom]'s GBA / DS set-up: none of their ROM scans (the cart is gigabytes, never read
     * whole), keyed by its title id. The engine is made but never started (the activity's guards and settings rows keep
     * working, idle); Azahar draws into [com.pokedaisy.app.n3ds.N3dsSession]'s two surfaces: the top screen laid exactly
     * over the game's box ([GameViewportView.gameCover]), the touch screen in DS LAYOUT's touch box ([dsTouchView]).
     */
    private fun load3ds(romFile: File) {
        val header = com.pokedaisy.app.n3ds.N3dsCart.header(romFile)
        if (header == null || !header.decrypted || !com.pokedaisy.app.n3ds.N3dsNative.available) {
            showHud(com.pokedaisy.app.companion.i18n.tr("This 3DS game can't start here."))
            return
        }
        when (val ready = com.pokedaisy.app.n3ds.N3dsSaves.prepare(this, header.titleId)) {
            is com.pokedaisy.app.n3ds.N3dsSaves.Ready.Refused -> { showHud(ready.reason); return }
            com.pokedaisy.app.n3ds.N3dsSaves.Ready.Ok -> Unit
        }
        romData = romFile
        romIs3ds = true
        romIsNds = false
        romIsGameBoy = false
        romCode = ""
        romBlocked = null
        input.ds = true
        if (::portraitPanel.isInitialized) portraitPanel.ds = false
        val key = "%016x".format(header.titleId)
        romKey = key
        // AUTO language: the title id names none (its 4th character is a digit); the session sets the save's.
        com.pokedaisy.app.companion.i18n.L10n.apply(Prefs(this).appLanguage, key)
        states = SaveStates(getExternalFilesDir(null) ?: filesDir, key)
        engine = EmulatorEngine(input, states!!, androidFfMusicCache(getExternalFilesDir(null) ?: filesDir, key), com.pokedaisy.app.companion.GameBattleInput(telemetry, null, 0, input = input), Prefs(this), telemetry)
        val session = com.pokedaisy.app.n3ds.N3dsSession(
            this, romFile, header.titleId, input,
            onSnapshot = { telemetry.publish(it) },
            onEnded = { status -> if (status != 0 && n3ds != null) showHud(com.pokedaisy.app.companion.i18n.tr("The 3DS game stopped.")) },
            // The touch screen's surface is laid out where DS LAYOUT places it or on the second display; elsewhere
            // (upright phones, for now) Azahar draws both screens on the one surface.
            wantsTouchSurface = { dsStageOn || n3dsTouchOnSecond },
            onScene = { onN3dsScene(it) },
            onBattle = { onN3dsBattle(it) },
            onFieldShot = { n3dsFieldShot.value = it.asImageBitmap() },
            onFieldHeld = { n3dsFieldHeld.value = it },
        )
        n3ds = session
        // The idle engine's GL view stays laid out (the stage measures it) but draws nothing: its surface goes.
        view.visibility = View.INVISIBLE
        stage.viewport.addView(session.topView, stage.viewport.indexOfChild(view) + 1, FrameLayout.LayoutParams(-1, -1))
        // The battle strip's picture: the top screen's box in the HUD's coordinates (the stage's area under the bar).
        session.topView.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val dx = fieldHudBounds.left - (stage.frames?.game?.left ?: 0f)
            val dy = fieldHudBounds.top - (stage.frames?.game?.top ?: 0f)
            n3dsTopRect.value = androidx.compose.ui.geometry.Rect((l - dx).toFloat(), (t - dy).toFloat(), (r - dx).toFloat(), (b - dy).toFloat())
        }
        stage.viewport.gameCover = session.topView
        dsTouchView.getChildAt(0)?.visibility = View.GONE
        dsTouchView.addView(session.touchView, FrameLayout.LayoutParams(-1, -1))
        stage.viewport.aspect = com.pokedaisy.app.companion.DsLayouts.N3DS_TOP_ASPECT
        if (::touchControls.isInitialized) {
            touchControls.shoulders = true
            touchControls.dsButtons = true
            syncTouchControls()
        }
        telemetry.reset()
        n3dsZoomStep = Prefs(this).zoomLevelFor(key).coerceIn(0, N3DS_CAMERA_PULLS.size - 1)
        session.cameraZoom = N3DS_CAMERA_PULLS[n3dsZoomStep]
        syncGameScreen()
    }

    /** The 3DS game stopped and its views gone (another game, the activity leaving). */
    private fun endN3ds() {
        val session = n3ds ?: return
        n3ds = null
        romIs3ds = false
        n3dsTouchOnSecond = false
        n3dsFloating.value = false
        n3dsAutoFloat = false
        n3dsFieldShot.value = null
        n3dsBattle.value = false
        n3dsOnField.value = false
        n3dsFieldHeld.value = false
        n3dsTopRect.value = null
        session.stop()
        (session.topView.parent as? android.view.ViewGroup)?.removeView(session.topView)
        (session.touchView.parent as? android.view.ViewGroup)?.removeView(session.touchView)
        if (::stage.isInitialized) stage.viewport.gameCover = null
        if (::dsTouchView.isInitialized) dsTouchView.getChildAt(0)?.visibility = View.VISIBLE
        view.visibility = View.VISIBLE
        if (::n3dsDim.isInitialized) n3dsDim.visibility = View.GONE
    }

    /** The 3DS game's touch surface is on the second display ([dsBottomHost]), not in the stage. */
    private var n3dsTouchOnSecond = false

    /** ONE SCREEN's floating touch screen can open now (a 3DS game on one screen, sideways, DS LAYOUT floating it; not
     * while a screen of the game's own shows both screens side by side). */
    private val n3dsFloats: Boolean
        get() = romIs3ds && dsStageOn && Prefs(this).dsLayout.floats && !n3dsBothScreens

    /** The 3DS game's field a moment ago: behind its own screens' two screens side by side ([n3dsBothScreens]). */
    private val n3dsFieldShot = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    /** The floating touch screen came in for the game's X menu ([onN3dsScene]), so it goes when the menu does; one the
     * player opened stays until they close it. */
    private var n3dsAutoFloat = false

    /** A 3DS game's screen of its own (party, bag, a battle: [com.pokedaisy.app.n3ds.Gen6Reader.Scene.SCREEN]) on ONE
     * SCREEN: both screens side by side, the top one's text and the touch screen's lists both needed. */
    private val n3dsBothScreens: Boolean
        get() = n3ds?.scene == com.pokedaisy.app.n3ds.Gen6Reader.Scene.SCREEN &&
            Prefs(this).dsLayout == com.pokedaisy.app.companion.DsLayout.ONE_SCREEN

    /** Where the game is ([com.pokedaisy.app.n3ds.N3dsSession.scene]) decides ONE SCREEN's touch screen: in for the X
     * menu, beside the top one for a screen of the game's own, away again on the field (if it came in by itself). */
    private fun onN3dsScene(scene: com.pokedaisy.app.n3ds.Gen6Reader.Scene) {
        if (n3ds == null) return
        n3dsOnField.value = scene == com.pokedaisy.app.n3ds.Gen6Reader.Scene.FIELD
        when (scene) {
            com.pokedaisy.app.n3ds.Gen6Reader.Scene.MENU -> if (!n3dsFloating.value && n3dsFloats) {
                setN3dsFloating(true)
                n3dsAutoFloat = true
                return
            }
            com.pokedaisy.app.n3ds.Gen6Reader.Scene.FIELD, com.pokedaisy.app.n3ds.Gen6Reader.Scene.SCREEN,
            com.pokedaisy.app.n3ds.Gen6Reader.Scene.BATTLE -> if (n3dsAutoFloat || scene == com.pokedaisy.app.n3ds.Gen6Reader.Scene.SCREEN) {
                n3dsAutoFloat = false
                if (n3dsFloating.value) { n3dsFloating.value = false; n3ds?.releaseTouch() }
            }
        }
        // A battle on ONE SCREEN: full size, with the app's buttons for its commands (DsBattleMenu) where the touch
        // screen would float. Not in PICTURE IN PICTURE: the touch screen is in that corner already (as for a DS game).
        n3dsBattle.value = scene == com.pokedaisy.app.n3ds.Gen6Reader.Scene.BATTLE && n3dsFloats &&
            Prefs(this).dsLayout == com.pokedaisy.app.companion.DsLayout.ONE_SCREEN
        syncGameScreen()
    }

    /** 3DS ZOOM (the ZOOM hotkey): the field camera pulled back, its distance times these ([N3dsSession.cameraZoom]: the
     * game's own camera, so more of the map shows, not a cropped picture); the step is kept per game
     * ([Prefs.zoomLevelFor]). Shown as GBA EXPAND's zoom is, the view's size: 1x, 0.7x, 0.6x. */
    private var n3dsZoomStep = 0

    private fun n3dsZoomNext() {
        val session = n3ds ?: return
        n3dsZoomStep = (n3dsZoomStep + 1) % N3DS_CAMERA_PULLS.size
        if (romKey.isNotEmpty()) Prefs(this).setZoomLevelFor(romKey, n3dsZoomStep)
        com.pokedaisy.app.companion.FeatureTips.used(TipId.ZOOM)
        session.cameraZoom = N3DS_CAMERA_PULLS[n3dsZoomStep]
        showHud(com.pokedaisy.app.companion.i18n.tr("Zoom {0}", com.pokedaisy.app.companion.zoomLabel(1f / N3DS_CAMERA_PULLS[n3dsZoomStep])))
    }

    /** A 3DS game holds the player on its field (text, a scene, a door): the HUD's bottom row steps away. */
    private val n3dsFieldHeld = androidx.compose.runtime.mutableStateOf(false)

    /** A 3DS game is on its field ([onN3dsScene]): the HUD shows (Compose state). */
    private val n3dsOnField = androidx.compose.runtime.mutableStateOf(false)

    /** A 3DS battle on ONE SCREEN ([onN3dsScene]): the strip shows ([DsBattleMenu]); Compose state. */
    private val n3dsBattle = androidx.compose.runtime.mutableStateOf(false)

    /** The 3DS top screen's box in the HUD's coordinates: the battle strip's picture. */
    private val n3dsTopRect = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)

    /** A 3DS battle's bag or party is open: those are on the touch screen alone, so its window stays until the game
     * closes them (its return arrow, B) - closed by itself (a tap beside it: a second tap on POKéMON before it was up),
     * the game sat on its party with neither the window nor the battle's buttons to get back (the user's report). */
    private val n3dsBattleScreenUp: Boolean
        get() = n3dsBattle.value && n3ds?.battle?.input.let {
            it == com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN || it == com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
        }

    /** The battle's menu changed ([com.pokedaisy.app.n3ds.N3dsSession.battle]): its bag and party are the game's own
     * screens, so the touch screen floats in for them (and goes once they close); the strip stands for the rest. */
    private fun onN3dsBattle(battle: com.pokedaisy.app.n3ds.Gen6Reader.Battle?) {
        if (n3ds == null || !n3dsBattle.value) return
        val input = battle?.input ?: com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
        val screen = input == com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN || input == com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
        if (screen && !n3dsFloating.value) {
            setN3dsFloating(true)
            n3dsAutoFloat = true
        } else if (!screen && n3dsAutoFloat && n3dsFloating.value) {
            setN3dsFloating(false)
        }
    }

    /**
     * DS LAYOUT for a 3DS game's two screens (a 5:3 top one, a 4:3 touch screen). ONE SCREEN keeps the touch screen's
     * surface laid out at all times - Azahar draws into it every frame - over the game's middle at 82% of the height;
     * [syncN3dsStage] moves it off the screen while it's hidden (translationX: a SurfaceView laid out off the screen
     * from the start never has its surface placed, and showed at the stage's top left).
     */
    private fun n3dsArrange(layout: com.pokedaisy.app.companion.DsLayout, w: Int, h: Int, fill: Boolean): com.pokedaisy.app.companion.DsArrangement {
        val top = com.pokedaisy.app.companion.DsLayouts.N3DS_TOP_ASPECT
        val touch = com.pokedaisy.app.companion.DsLayouts.ASPECT
        if (layout != com.pokedaisy.app.companion.DsLayout.ONE_SCREEN) {
            return com.pokedaisy.app.companion.DsLayouts.arrange(layout, w, h, fill, top, touch)
        }
        if (n3dsBothScreens) {
            return com.pokedaisy.app.companion.DsLayouts.arrange(com.pokedaisy.app.companion.DsLayout.SIDE_BY_SIDE, w, h, fill, top, touch)
        }
        val alone = com.pokedaisy.app.companion.DsLayouts.arrange(layout, w, h, fill, top, touch).top
        // At most the area's width too (a locked side panel leaves the game a narrow area: the window went under it).
        // (Inside its frame: N3dsWindowFrame, [syncN3dsStage].)
        val frame = (com.pokedaisy.app.n3ds.N3dsWindowFrame.FRAME_DP * resources.displayMetrics.density).toInt() * 2
        val th = minOf(h * N3DS_FLOAT_HEIGHT, (w * N3DS_FLOAT_WIDTH - frame) / touch).toInt()
        val tw = (th * touch).toInt()
        val x = (w - tw) / 2
        val y = (h - th) / 2
        return com.pokedaisy.app.companion.DsArrangement(alone, com.pokedaisy.app.companion.DsBox(x, y, x + tw, y + th))
    }

    /** [syncDsStage]'s part for a 3DS game: its touch screen's view always laid out (see [n3dsArrange]), no DS window. */
    private fun syncN3dsStage(on: Boolean, layout: com.pokedaisy.app.companion.DsLayout) {
        // ONE SCREEN's floating touch screen in a frame of the game's smooth look (side by side and the other layouts: none).
        val floating = on && layout == com.pokedaisy.app.companion.DsLayout.ONE_SCREEN && !n3dsBothScreens
        val frame = if (floating) (com.pokedaisy.app.n3ds.N3dsWindowFrame.FRAME_DP * resources.displayMetrics.density).toInt() else 0
        stage.viewport.dsTouchFrame = frame
        dsTouchView.setPadding(frame, frame, frame, frame)
        if (floating) {
            if (dsTouchView.background !is com.pokedaisy.app.n3ds.N3dsWindowFrame) {
                dsTouchView.background = com.pokedaisy.app.n3ds.N3dsWindowFrame(resources.displayMetrics.density)
            }
        } else {
            dsTouchView.setBackgroundColor(Color.TRANSPARENT)
        }
        // A tap on the frame stays the window's (else it reached the dim under it and closed the window).
        dsTouchView.isClickable = floating
        // A screen of the game's own: its two screens framed over the field it came from, as a DS game's menus.
        dsBackdropView.visibility = if (on && n3dsBothScreens) View.VISIBLE else View.GONE
        // Back from the second display (SINGLE SCREEN, the display gone): the touch surface into the stage again.
        n3ds?.touchView?.let { v ->
            if (on && v.parent !== dsTouchView) {
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                v.setZOrderMediaOverlay(true)
                dsTouchView.addView(v, FrameLayout.LayoutParams(-1, -1))
                n3dsTouchOnSecond = false
            }
        }
        dsTouchView.visibility = if (on) View.VISIBLE else View.GONE
        n3ds?.surfacesChanged()
        dsFloats.value = false
        dsPip.value = false
        dsFill.value = false
        dsCornerTaken.value = on && (layout == com.pokedaisy.app.companion.DsLayout.LARGE_SMALL || layout == com.pokedaisy.app.companion.DsLayout.PICTURE_IN_PICTURE)
        if (::touchControls.isInitialized) touchControls.passMisses = on
        if (!on || layout != com.pokedaisy.app.companion.DsLayout.ONE_SCREEN) n3dsFloating.value = false
        // The battle's buttons follow a layout changed mid-battle (DS LAYOUT's hotkey) too.
        n3dsBattle.value = on && layout == com.pokedaisy.app.companion.DsLayout.ONE_SCREEN &&
            n3ds?.scene == com.pokedaisy.app.n3ds.Gen6Reader.Scene.BATTLE
        n3dsDim.visibility = if (on && n3dsFloating.value) View.VISIBLE else View.GONE
        val hidden = on && layout == com.pokedaisy.app.companion.DsLayout.ONE_SCREEN && !n3dsFloating.value && !n3dsBothScreens
        dsTouchView.translationX = if (hidden) (stage.width.coerceAtLeast(resources.displayMetrics.widthPixels) * 2).toFloat() else 0f
        stage.requestLayout()
    }

    private fun setN3dsFloating(open: Boolean) {
        if (open && !n3dsFloats) return
        if (n3dsFloating.value == open) return
        n3dsFloating.value = open
        n3dsAutoFloat = false
        if (!open) n3ds?.releaseTouch()
        syncGameScreen()
    }

    /** The hotkeys a 3DS game answers its own way (Azahar's speed and states); true = taken. */
    /** A 3DS game's state into / out of [slot] (Azahar's own state, between two frames): the hotkeys and STATES. */
    private fun n3dsSaveSlot(slot: Int) {
        val session = n3ds ?: return
        val st = states ?: return
        engine.cycleSlot(slot - engine.currentSlot)
        session.saveState(st.stateFile(slot)) { ok ->
            if (ok) session.writeThumb(st.thumbFile(slot))
            showHud(if (ok) tr("Saved slot {0}", slot) else tr("Slot {0}: save failed", slot))
        }
    }

    private fun n3dsLoadSlot(slot: Int) {
        val session = n3ds ?: return
        val st = states ?: return
        engine.cycleSlot(slot - engine.currentSlot)
        session.loadState(st.stateFile(slot)) { ok ->
            showHud(if (ok) tr("Loaded slot {0}", slot) else tr("Slot {0}: nothing to load", slot))
        }
    }

    private fun n3dsHotkey(e: Hotkeys.Event): Boolean {
        val session = n3ds ?: return false
        if (states == null) return false
        when (e.action) {
            Hotkeys.Action.SAVE_STATE -> if (e.pressed) n3dsSaveSlot(engine.currentSlot)
            Hotkeys.Action.LOAD_STATE -> if (e.pressed) n3dsLoadSlot(engine.currentSlot)
            // Let go: back to the toggle's speed.
            Hotkeys.Action.FF_HOLD -> session.setSpeed(if (e.pressed || n3dsFfOn) N3DS_FF_PERCENT else 100)
            Hotkeys.Action.FF_TOGGLE -> if (e.pressed) {
                n3dsFfOn = !n3dsFfOn
                session.setSpeed(if (n3dsFfOn) N3DS_FF_PERCENT else 100)
                if (::touchControls.isInitialized) touchControls.ffOn = n3dsFfOn
            }
            Hotkeys.Action.ZOOM -> if (e.pressed) n3dsZoomNext()
            Hotkeys.Action.REWIND_HOLD, Hotkeys.Action.SOFT_RESET, Hotkeys.Action.UNDO_SAVE, Hotkeys.Action.UNDO_LOAD,
            Hotkeys.Action.SPEED_CYCLE, Hotkeys.Action.SLOWMO_HOLD -> Unit
            else -> return false
        }
        return true
    }
    private var n3dsFfOn = false

    /** The views [syncDsStage] places: the game's backdrop, the touch screen (beside / inset), the floating window. */
    private fun buildDsStageViews() {
        dsShots = DsShots(view)
        dsBackdropView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                val engine by hudEngine.collectAsState()
                val idle = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
                val menuApp by (engine?.dsMenuApp ?: idle).collectAsState()
                val menuScreen by (engine?.dsMenuScreen ?: idle).collectAsState()
                val field by dsShots.field
                val current by dsShots.latest
                // A menu screen: the overworld it came from, its two screens framed over it like a modal. Anything else
                // with room round the game (the title, a layout beside it): the game itself, blurred.
                androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val modal = if (menuScreen) com.pokedaisy.app.companion.DsLayouts.arrange(
                        com.pokedaisy.app.companion.DsLayout.SIDE_BY_SIDE, w, h, false,
                    ).let { a ->
                        val t = a.top
                        val b = a.touch ?: t
                        androidx.compose.ui.geometry.Rect(t.left.toFloat(), minOf(t.top, b.top).toFloat(), b.right.toFloat(), maxOf(t.bottom, b.bottom).toFloat())
                    } else null
                    if (romIs3ds) {
                        val a = com.pokedaisy.app.companion.DsLayouts.arrange(
                            com.pokedaisy.app.companion.DsLayout.SIDE_BY_SIDE, w, h, false,
                            com.pokedaisy.app.companion.DsLayouts.N3DS_TOP_ASPECT, com.pokedaisy.app.companion.DsLayouts.ASPECT,
                        )
                        val t = a.top
                        val b = a.touch ?: t
                        com.pokedaisy.app.companion.ui.DsShotBackdrop(
                            n3dsFieldShot.value, blurred = false,
                            modal = androidx.compose.ui.geometry.Rect(t.left.toFloat(), minOf(t.top, b.top).toFloat(), b.right.toFloat(), maxOf(t.bottom, b.bottom).toFloat()),
                        )
                    } else if (menuApp && field != null) com.pokedaisy.app.companion.ui.DsShotBackdrop(field, blurred = false, modal = modal)
                    else com.pokedaisy.app.companion.ui.DsShotBackdrop(current, blurred = true, modal = modal)
                }
                // About once a second: the overworld while it's up and widened, else the game as it is (only while
                // something shows it).
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(DS_SHOT_MS)
                        val e = engine ?: continue
                        if (!dsStageOn || !e.running) continue
                        val overworld = dsWide && e.onField.value && !e.dsTouchWanted.value && !dsWindowManual.value
                        if (overworld) dsShots.capture(intoField = true)
                        else if (!dsWide || !Prefs(this@PokeDaisyActivity).dsLayout.floats) dsShots.capture(intoField = false)
                    }
                }
            }
        }
        dsTouchView = FrameLayout(this).apply {
            visibility = View.GONE
            addView(NdsTouchScreenView(this@PokeDaisyActivity), FrameLayout.LayoutParams(-1, -1))
        }
    }

    @androidx.compose.runtime.Composable
    private fun DsWindowContent() {
        val engine by hudEngine.collectAsState()
        val idle = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
        val wantedLive by (engine?.dsTouchWanted ?: idle).collectAsState()
        val manual = dsWindowManual.value
        val floats = dsFloats.value
        // The game done with a window the player opened: it stays (their X closes it); the game wanting it
        // again while it's up by hand: the game's now (no X - it goes when the game is done).
        val menuIdle = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
        val menuLive by (engine?.dsMenuScreen ?: menuIdle).collectAsState()
        val menuAppLive by (engine?.dsMenuApp ?: menuIdle).collectAsState()
        val wideIdle = remember { kotlinx.coroutines.flow.MutableStateFlow(true) }
        val wideLive by (engine?.dsWideScene ?: wideIdle).collectAsState()
        // A blank touch screen (a fade, the black between two screens) never shows: the window waits it out.
        val blankLive by (engine?.dsTouchBlank ?: idle).collectAsState()
        val darkLive by (engine?.dsDark ?: idle).collectAsState()
        // A menu transition held (EmulatorEngine.dsHold): both screens keep their picture from before it, so the
        // stage keeps its arrangement too - the window, the layout - until the next screen is up, then all at once.
        val holding by (engine?.dsHolding ?: idle).collectAsState()
        val latched = remember { arrayOfNulls<BooleanArray>(1) }
        if (!holding || latched[0] == null) latched[0] = booleanArrayOf(wantedLive, menuLive, menuAppLive, wideLive, blankLive, darkLive)
        val (wanted, menu, menuApp, wideScene) = latched[0]!!
        val blank = latched[0]!![4]
        val dark = latched[0]!![5]
        androidx.compose.runtime.LaunchedEffect(menu, wideScene) {
            if (dsMenuUp != menu || dsWide != wideScene) { dsMenuUp = menu; dsWide = wideScene; syncGameScreen() }
        }
        // The held touch screen's picture: drawn over the live one as the hold ends, fading out (a crossfade).
        val frozen = remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        androidx.compose.runtime.LaunchedEffect(holding) {
            if (holding) frozen.value = runCatching { NdsBottomFrame.snapshot().asImageBitmap() }.getOrNull()
        }
        // (A page for both screens goes side by side, not in the window: at once, before the layout follows.)
        val wantedNow = floats && (wanted || manual) && !menu
        // A window already up when its touch screen goes black (the X menu into the party, the party back out) stays
        // up with a loader in it - one modal loading its next page, not one closing and another opening a moment
        // later. Only through the black between two screens, or a short blank: a touch screen left one colour
        // longer (a picture of its own) isn't a wait.
        val wasOpen = remember { booleanArrayOf(false) }
        var blankLong by remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(blank) {
            blankLong = false
            if (blank) { kotlinx.coroutines.delay(DS_BLANK_WAIT_MS); blankLong = true }
        }
        val loading = wantedNow && blank && wasOpen[0] && (dark || !blankLong)
        val open = wantedNow && !blank || loading
        androidx.compose.runtime.SideEffect { wasOpen[0] = open }
        // PICTURE IN PICTURE: its inset goes while the touch screen is up large (the same screen twice otherwise).
        androidx.compose.runtime.SideEffect { dsTouchView.alpha = if (open) 0f else 1f }
        // A DS battle's menu (the HUD decides when: [dsStripShown]): kept on show behind the floating window - the
        // bag, the party - under its dim, the keys the window's meanwhile. In the HUD's coordinates (the stage's
        // area under the status bar).
        if (romIsNds || romIs3ds) androidx.compose.runtime.CompositionLocalProvider(
            com.pokedaisy.app.companion.ui.LocalClickSound provides playClick,
            com.pokedaisy.app.companion.ui.LocalGameSilent provides { battleInput.gameSilent },
        ) {
            val snap by telemetry.snapshot.collectAsState()
            // SHADERS' FILTER / GBA COLORS as the game gets them: the strip stands in for the game's own text box rows,
            // and kept no grid while the game around it had one (Discord, 2026-11-17).
            val fxVersion = CompanionColors.version.intValue
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val gameFx = remember(fxVersion, density) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                    CompanionColors.gameEffect(density)?.asComposeRenderEffect() else null
            }
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer {
                    translationY = fieldHudBounds.top - (stage.frames?.game?.top ?: 0f)
                    renderEffect = gameFx
                },
            ) {
                com.pokedaisy.app.companion.ui.DsBattleMenu(
                    snap, dsBattleMenu, battleInput, if (romIs3ds) n3dsTopRect.value else coreFrame.value, shown = dsStripShown.value, active = dsStripActive.value,
                    gameFills = !romIs3ds && dsFill.value, hints = battleHints.value,
                    // FIGHT / BAG / RUN under the touch controls on a phone: the buttons over them step aside (Discord 2026-11-10).
                    onTapArea = { if (::touchControls.isInitialized) touchControls.coverInWindow(it) },
                )
            }
            // The menu's D-pad: with no plain view focused, Android takes the first press after a touch to leave
            // touch mode (a ComposeView gets the focus) and the menu never sees it - so the game view holds it.
            val menuUp = dsBattleMenu.mode != 0
            androidx.compose.runtime.LaunchedEffect(menuUp) { if (menuUp && !view.isFocused) view.requestFocus() }
        }
        // The black between a menu and the overworld (or back to the X menu): the overworld's picture over it all,
        // as the modal's backdrop draws it, going once the game shows something again - never empty frames. Under
        // a window kept up through it (its loader is in the window then).
        val field = dsShots.field.value
        val cover = dark && field != null && (menu || menuApp || wanted || manual)
        val coverAlpha by androidx.compose.animation.core.animateFloatAsState(
            if (cover) 1f else 0f,
            if (cover) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(DS_COVER_FADE_MS),
            label = "dsCover",
        )
        val lastField = remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        if (cover) lastField.value = field
        // (On at once, the same frame the window goes: an animation's first frame would be 0, a black flash.)
        val coverShown = if (cover) 1f else coverAlpha
        if (coverShown > 0f) lastField.value?.let { shot ->
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer { alpha = coverShown }) {
                com.pokedaisy.app.companion.ui.DsShotBackdrop(shot, blurred = false, modal = null)
                // The game loading the next screen: something moving on the greyed-out picture.
                if (cover && !open) com.pokedaisy.app.companion.ui.DsLoader(ball = false)
            }
        }
        // Behind a window the game opened (not in a battle, whose own picture and buttons stay on show): the game as
        // it was when the menus began, until the game is back on the field with nothing up - not the stage changing
        // underneath as the game moves between its pages (EXPAND's field, the 4:3 of a menu page, a page on both
        // screens and back: the user's report). Taken once, fresh, as the first window opens (full size, also the
        // overworld the two-screen pages sit on), live until it's in; never again on the way back from a two-screen
        // page, where the stage is still changing shape (a copy then caught it half resized, black round it).
        val inBattle by remember { androidx.compose.runtime.derivedStateOf { telemetry.snapshot.value.inBattle } }
        val sessionShot = remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        val inSession = (open || wanted || menu || menuApp) && !inBattle
        val freezeBehind = open && (wanted || menuApp) && !inBattle
        androidx.compose.runtime.LaunchedEffect(inSession) { if (!inSession) sessionShot.value = null }
        androidx.compose.runtime.LaunchedEffect(freezeBehind) {
            if (!freezeBehind || sessionShot.value != null) return@LaunchedEffect
            // (The companion's battle flag can be a sample behind: the field read's is this frame's.)
            if ((engine?.fieldState ?: 0) and MgbaCore.FIELD_BATTLE != 0) return@LaunchedEffect
            // A menu page already: the overworld it was opened from. Else the game now (the field under the X menu).
            if (menuApp) { sessionShot.value = dsShots.field.value; return@LaunchedEffect }
            repeat(DS_FREEZE_TRIES) {
                if (dsShots.capture(intoField = true, full = true) { if (sessionShot.value == null) sessionShot.value = it }) return@LaunchedEffect
                kotlinx.coroutines.delay(16)
            }
        }
        val frozenBehind = if (freezeBehind) sessionShot.value else null
        com.pokedaisy.app.companion.ui.DsTouchWindow(
            open = open,
            onClose = if (manual && !wanted) ({ dsWindowManual.value = false }) else null,
            onDismiss = ::dismissDsTouch,
            behind = frozenBehind ?: if (menuApp) dsShots.field.value else null,
            loading = loading,
            // Over the cover: it's already the dimmed overworld.
            backdrop = !cover,
            held = holding,
            frozen = frozen.value,
            // (hudClear: re-read when the touch controls move.)
            clearFor = { l, r -> hudClear.value.let { touchControls.clearAcross(l, r) } },
        ) { modifier ->
            androidx.compose.ui.viewinterop.AndroidView({ NdsTouchScreenView(it) }, modifier)
        }
        // Held with no window up (a page side by side, on its way back to the field): the same shade and dots
        // over the stage's held pictures.
        com.pokedaisy.app.companion.ui.DsHeldShade(holding && !open)
    }

    /**
     * The status bar ([com.pokedaisy.app.companion.ui.GameStatusBar]):
     * the ROM's name as the Library shows it, the map section and money from
     * telemetry, the clock (the system's 12/24-hour setting) and the battery.
     */
    // Tracked by CompanionColors: it sits on the game, so GBA COLORS recolours it with it.
    private fun buildStatusBar(): ComposeView = CompanionColors.track(ComposeView(this)).apply {
        setContent { StatusBarContent() }
    }

    /** SETTINGS > FPS / CPU: frames per second and CPU in the corner; a tap opens the rest (PerfReading). */

    /** The battery's temperature from its broadcast (°C), for the performance panel. */
    @Volatile private var batteryTempC: Float? = null

    /** FPS / CPU is on: off, the overlay composes nothing (its half-second readings went on behind a hidden view). */
    private val performanceShown = androidx.compose.runtime.mutableStateOf(false)

    @androidx.compose.runtime.Composable
    private fun PerformanceContent() {
        if (!performanceShown.value) return
        val reading by produceState(com.pokedaisy.app.companion.ui.PerfReading()) {
            val cpu = CpuUsage()
            val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val cores = Runtime.getRuntime().availableProcessors()
            fun take(e: EmulatorEngine?) = PerformanceCounters(System.nanoTime(), e?.framesRun ?: 0, view.framesShown,
                e?.workNanos ?: 0, telemetry.samplesTaken, telemetry.battleChecksTaken)
            val initial = if (::engine.isInitialized) engine else null
            var previous = take(initial)
            var late = view.lateFrames
            initial?.takeMaxWorkNanos()
            withContext(Dispatchers.IO) { cpu.sample() }
            while (true) {
                delay(500)
                if (!::engine.isInitialized) continue
                val e = engine
                val current = take(e)
                val currentLate = view.lateFrames
                val seconds = ((current.atNanos - previous.atNanos) / 1e9).coerceAtLeast(0.001)
                val frameReading = current.since(previous, e.takeMaxWorkNanos())
                val cpuPct = withContext(Dispatchers.IO) { cpu.sample() }
                val rt = Runtime.getRuntime()
                value = frameReading.copy(
                    latePerSecond = ((currentLate - late).coerceAtLeast(0) / seconds).toFloat(),
                    cpu = cpuPct, cores = cores,
                    refreshHz = e.refreshHz, refreshesPerFrame = e.refreshesPerFrame, timerFallback = e.timerFallback,
                    speed = e.currentSpeed,
                    audioDropouts = e.audioUnderruns,
                    batteryTempC = batteryTempC,
                    thermal = if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1,
                    memoryMb = ((rt.totalMemory() - rt.freeMemory() + android.os.Debug.getNativeHeapAllocatedSize()) shr 20).toInt(),
                )
                previous = current
                late = currentLate
            }
        }
        // The view fills the stage, and Compose hands its root that whole size as the least it may take: a Box
        // takes it, so the overlay inside keeps its own size (else its tap, FPS / CPU's open / close, was everywhere).
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            com.pokedaisy.app.companion.ui.PerformanceOverlay(reading)
        }
    }

    /**
     * The FIELD HUD ([com.pokedaisy.app.companion.ui.FieldHud]) over the game: shown while the engine says the
     * player is on the field and the snapshot agrees (a game read, no battle), SETTINGS > HUD is on and the
     * screen isn't held upright. Tracked by CompanionColors like the status bar: it sits on the game.
     */
    @androidx.compose.runtime.Composable
    private fun FieldHudContent() {
        val snap by telemetry.snapshot.collectAsState()
        val engine by hudEngine.collectAsState()
        val idle = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
        val onField by (engine?.onField ?: idle).collectAsState()
        val field = if (romIs3ds) n3dsOnField.value && !n3dsFloating.value
            else engine?.playback?.settledOnField == true
        val shown = com.pokedaisy.app.companion.ui.fieldHudShown(hudOn.value, hudUpright.value, field, snap)
        // One screen, the companion's panel closed: its open button joins the HUD's, and the edge tab steps aside
        // (for BATTLE BUTTONS' party window too, which sits in that corner). As the window it stays put while the
        // window is up too (under its dim): it doesn't vanish from the HUD the player sees around it.
        val panelClosed = ::sidePanel.isInitialized && (sidePanel.closed.value || sidePanel.enabled && sidePanel.windowMode)
        // Read here, so the tab steps aside when the DS battle menu comes up (a read in the SideEffect wouldn't redraw).
        val dsMenuMode = dsBattleMenu.mode
        androidx.compose.runtime.SideEffect {
            // A DS battle's menu sits in that corner too: the tab goes to the top right meanwhile (GitHub #595).
            if (::sidePanel.isInitialized) {
                sidePanel.tabInHud = shown && panelClosed || battlePicking.value
                sidePanel.tabRaised = dsMenuMode != 0 || dsCornerTaken.value
            }
        }
        // BATTLE BUTTONS: one screen held sideways, a single battle the game's touch input can steer (its state read
        // at least once this battle - it reads NONE in games without it).
        val battleRead = com.pokedaisy.app.companion.ui.rememberBattleButtonsRead(snap)
        // A DS game: only where DS LAYOUT floats its touch screen (the others, PICTURE IN PICTURE's inset too, show the
        // game's own panel), and out of the way while the floating touch screen is up (a bag, a party, a prompt).
        val dsIdle = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
        val dsWanted by (engine?.dsTouchWanted ?: dsIdle).collectAsState()
        val dsWindowUp = dsFloats.value && (dsWanted || dsWindowManual.value)
        val buttonsWanted = com.pokedaisy.app.companion.battleButtonsWanted(battleButtonsOn.value, hudSingleScreen.value, hudUpright.value, snap) && (!romIsNds || dsFloats.value && !dsPip.value)
        val buttons = buttonsWanted && battleRead && !dsWindowUp
        // Where BATTLE BUTTONS can't stand in for a DS battle's command panel, that panel floats in too.
        androidx.compose.runtime.SideEffect { engine?.dsBattleMenuFloats = dsFloats.value && !dsPip.value && !buttonsWanted }
        androidx.compose.runtime.CompositionLocalProvider(
            com.pokedaisy.app.companion.ui.LocalCompanionBack provides hudBack,
            com.pokedaisy.app.companion.ui.LocalClickSound provides playClick,
            com.pokedaisy.app.companion.ui.LocalGameSilent provides { battleInput.gameSilent },
        ) {
            // The QUICK MENU up: the HUD fades well back, so the menu is what stands out.
            // The buttons on the other screen's companion (X): the HUD steps back too, under the strip pointing there.
            val hudAlpha by androidx.compose.animation.core.animateFloatAsState(if (quickMenu.open || bottomFocus.value) 0.25f else 1f, label = "hudAlpha")
            // A 3DS game: its own HUD, in the game's smooth look (party, WILD, TOUCH, COMPANION).
            val hudPrefs = androidx.compose.runtime.remember { Prefs(this@PokeDaisyActivity) }
            if (romIs3ds) com.pokedaisy.app.companion.ui.XyFieldHud(
                snap, shown, n3dsTopRect.value,
                modifier = androidx.compose.ui.Modifier.graphicsLayer { alpha = hudAlpha },
                wild = n3ds?.wildHere.orEmpty(),
                map = n3ds?.mapHere,
                bottomHidden = n3dsFieldHeld.value,
                itemUse = itemUse,
                onTouchScreen = if (n3dsFloats) ::toggleDsTouch else null,
                onOpenCompanion = if (panelClosed && companionButtonShown.value) ({ sidePanel.openPanel() }) else null,
                onTap = { com.pokedaisy.app.companion.FeatureTips.used(TipId.HUD) },
                onPanel = { hudPanelOpen.value = it },
            ) else androidx.compose.runtime.CompositionLocalProvider(
                // POKéMON PICTURES (Crystal): the HUD's party shows the front pictures too.
                com.pokedaisy.app.companion.ui.LocalPokemonPictures provides snap.pokedex?.tables?.takeIf {
                    // The switch's version first: Prefs isn't Compose state, and a flip should show at once.
                    com.pokedaisy.app.companion.PokemonPicturesSwitch.version >= 0 &&
                        hudPrefs.pokemonPictures && com.pokedaisy.app.companion.ui.pokemonPicturesOffered(snap.game)
                },
            ) { com.pokedaisy.app.companion.ui.FieldHud(
                snap, shown && !dsWindowUp, hudStyle.value,
                modifier = androidx.compose.ui.Modifier.graphicsLayer { alpha = hudAlpha },
                clearTop = hudClear.value.first, clearBottom = hudClear.value.second,
                itemUse = itemUse, fly = flyAction,
                onOpenCompanion = if (panelClosed && companionButtonShown.value) ({ sidePanel.openPanel() }) else null,
                onTouchScreen = if (romIsNds && dsFloats.value) ::toggleDsTouch else null,
                dsCorner = dsCornerTaken.value,
                dsClearBottom = dsHudClear.value,
                frame = hudFrame.value ?: hudTouchFrame.value,
                avoid = hudAvoid.value,
                closePanels = hudCloseTick.intValue,
                openPanel = hudOpenPanel.value, openTick = hudOpenTick.intValue,
                onTap = { com.pokedaisy.app.companion.FeatureTips.used(TipId.HUD) },
                onPanel = { hudPanelOpen.value = it },
            ) }
            // MOVE ADVICE: the card over the game while its summary asks which move to forget (also in a battle or a menu,
            // where the HUD itself is hidden).
            if (hudOn.value && !hudUpright.value && snap.moveLearning != null && hudStyle.value.shows(com.pokedaisy.app.companion.FieldHudPart.MOVE_ADVICE)) {
                com.pokedaisy.app.companion.ui.MoveLearningHudCard(snap, hudStyle.value)
            }
            // A DS game: the game's own buttons over the end of its text box, driven like Gen 3's menus.
            if (romIsNds) {
                // A DS battle's menu is drawn by the floating window's view, under its dim ([buildDsStageViews]).
                androidx.compose.runtime.SideEffect {
                    dsStripShown.value = buttonsWanted && battleRead
                    dsStripActive.value = !dsWindowUp
                }
            } else if (romIs3ds) {
                androidx.compose.runtime.SideEffect {
                    dsStripShown.value = battleButtonsOn.value && n3dsBattle.value && snap.inBattle
                    dsStripActive.value = !n3dsFloating.value
                }
            } else com.pokedaisy.app.companion.ui.BattleButtons(
                snap, buttons, battleInput, battleHints.value,
                modifier = androidx.compose.ui.Modifier.graphicsLayer { alpha = hudAlpha },
                core = coreFrame.value,
                clearTop = hudClear.value.first, clearBottom = hudClear.value.second,
                onWindow = { battlePicking.value = it }, avoid = hudAvoid.value,
                style = battleStyle.value,
            )
            com.pokedaisy.app.companion.ui.QuickMenu(quickMenu, snap, quickActions, quickShown.value, hudStyle.value.look)
            com.pokedaisy.app.companion.ui.HealedAnimation(healShow.value, hudStyle.value.look) { healShow.value = null }
            // Co-op joined battles: the join prompt, the waits, the notices - over the game, HUD on or off.
            com.pokedaisy.app.companion.ui.CoopGameOverlays(
                coopOverlay, coopLockState.value, coopLockPartner.value, proposalOnGameScreen = secondScreen() == null,
                closeGame = { exitGame() }, showNotice = { showHud(it) },
            )
            // X sent the buttons to the other screen's companion: the strip pointing there; the game leaving the
            // field (a battle, its PC opened from the companion) wants them back.
            val focused = bottomFocus.value
            androidx.compose.runtime.LaunchedEffect(focused, onField, snap.inBattle) {
                if (focused && !romIsNds && (!onField || snap.inBattle)) setBottomFocus(false)
            }
            CompanionFocusStripe(
                focused, hudFrame.value, if (engine?.gba == false && engine?.nds == false) GB_ROWS else GBA_ROWS,
                onClose = { setBottomFocus(false) },
            )
            // A feature tip over the game, when the companion has no screen of its own: top centre, clear of the
            // minimap and the area's name.
            androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
                val narrow = maxWidth < 600.dp
                val clearTop = with(androidx.compose.ui.platform.LocalDensity.current) { hudClear.value.first.toDp() }
                com.pokedaisy.app.companion.ui.FeatureTipHost(
                    active = !tipsOnCompanion.value, roomy = false,
                    modifier = androidx.compose.ui.Modifier.padding(top = clearTop + 8.dp),
                    cardModifier = androidx.compose.ui.Modifier.width(maxWidth * if (narrow) 0.94f else 0.54f),
                    maxWidth = maxWidth, keys = ::tipKeys, onTry = ::tryTip,
                )
                com.pokedaisy.app.companion.ui.FlyStyleAskHost(
                    active = !tipsOnCompanion.value, roomy = false,
                    modifier = androidx.compose.ui.Modifier.padding(top = clearTop + 8.dp),
                    cardModifier = androidx.compose.ui.Modifier.width(maxWidth * if (narrow) 0.94f else 0.54f),
                    maxWidth = maxWidth, onAnswer = ::answerFlyStyle,
                )
                com.pokedaisy.app.companion.ui.BattleRetryAskHost(
                    asking = companionSettings.battleRetryAsking, active = !tipsOnCompanion.value, roomy = false,
                    modifier = androidx.compose.ui.Modifier.padding(top = clearTop + 8.dp),
                    cardModifier = androidx.compose.ui.Modifier.width(maxWidth * if (narrow) 0.94f else 0.54f),
                    maxWidth = maxWidth, onAnswer = ::answerBattleRetry,
                )
                // RetroAchievements' popups and challenge / tracker indicators, while no companion is on screen (a DS
                // game's touch screen in its place, one screen's closed window): GitHub #500.
                com.pokedaisy.app.companion.ui.AchievementPopupHost(
                    RetroAchievements, androidx.compose.ui.Modifier.padding(top = clearTop + 8.dp), overGame = true,
                )
                val companionSeen by androidx.compose.runtime.produceState(true) {
                    while (true) {
                        value = com.pokedaisy.app.companion.ui.AchievementPopupRoute.companionSeen()
                        kotlinx.coroutines.delay(500)
                    }
                }
                // ITEM POPUPS: a bag gain, a small card top centre (clear of the HUD's corners and the area's name).
                if (itemToastOn.value) com.pokedaisy.app.companion.ui.ItemToastHost(
                    snap.items, snap.connected, snap.hasData, itemToastReset.intValue, snap.frameCounter,
                    androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = clearTop + 8.dp),
                )
                if (!companionSeen) {
                    val clearBottom = with(androidx.compose.ui.platform.LocalDensity.current) { hudClear.value.second.toDp() }
                    com.pokedaisy.app.companion.ui.AchievementIndicators(
                        RetroAchievements,
                        androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(end = 8.dp, bottom = clearBottom + 8.dp),
                    )
                }
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            gameTips.run(
                nowMillis = android.os.SystemClock::uptimeMillis,
                onFailure = { Log.w("pokedaisy", "tips", it) },
                afterTick = { runCatching { syncFlight() }.onFailure { Log.w("pokedaisy", "flight", it) } },
            )
        }
    }

    /** The touch controls' button rows, for the FIELD HUD to keep clear of. */
    private fun syncHudClear() {
        // EDIT LAYOUT hides the controls while their stand-ins move: the HUD stays where it was until DONE (GitHub #493).
        if (touchEditor.isOpen) return
        if (!::touchControls.isInitialized) return
        hudClear.value = touchControls.clearTop to touchControls.clearBottom
        // EXPAND held sideways: the HUD keeps to the game's middle columns, where the clearance is measured, not under
        // the buttons in the margins beside them (GitHub #493). In the HUD's own coordinates.
        if (!::stage.isInitialized) return
        val at = IntArray(2).also(touchControls::getLocationInWindow)
        val hudAt = IntArray(2).also(stage::getLocationInWindow).also {
            it[0] += fieldHudBounds.left.toInt(); it[1] += fieldHudBounds.top.toInt()
        }
        val dx = (at[0] - hudAt[0]).toFloat()
        val dy = (at[1] - hudAt[1]).toFloat()
        hudTouchFrame.value = touchControls.hudColumns()?.let { (l, r) ->
            // Top to bottom: only its sides matter (the HUD's box may not be measured yet).
            androidx.compose.ui.geometry.Rect(l + dx, 0f, r + dx, Float.MAX_VALUE)
        }
        // And each button, for the HUD's parts to step off (a CUSTOM layout's, RAISED's, a big D-pad on 16:9).
        hudAvoid.value = touchControls.buttonRects().map { androidx.compose.ui.geometry.Rect(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy) }
    }

    /** The same status bar over the companion's tabs (SETTINGS > STATUS BAR > COMPANION). */
    private var companionStatusBarVisible by androidx.compose.runtime.mutableStateOf(false)
    private val statusBarContent: @androidx.compose.runtime.Composable () -> Unit = { StatusBarContent() }
    private val companionStatusBar get() = statusBarContent.takeIf { companionStatusBarVisible }

    @androidx.compose.runtime.Composable
    private fun StatusBarContent() {
        val snap by telemetry.snapshot.collectAsState()
        // The Library's name; a ROM the library hasn't named yet (a frontend launch) is
        // recognised here, off the main thread.
        val name by produceKeyedState(rom?.let { GameTitles.label(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), it) }.orEmpty(), rom) {
            val r = rom ?: return@produceKeyedState
            if (withContext(Dispatchers.IO) { GameTitles.identify(this@PokeDaisyActivity, listOf(r)) }) {
                value = GameTitles.label(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), r)
            }
        }
        RunningGameStatusBar(name, snap) { millis ->
            android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(millis))
        }
    }

    /** Show the on-screen controls per SETTINGS > TOUCH CONTROLS > SHOW (AUTO: only with no controller), in its style. */
    private fun syncTouchControls() {
        if (!::touchControls.isInitialized) return
        val style = Prefs(this).touchControlsStyle
        val show = !touchEditor.isOpen && when (style.show) {
            com.pokedaisy.app.companion.TouchShow.ALWAYS -> true
            com.pokedaisy.app.companion.TouchShow.NEVER -> false
            com.pokedaisy.app.companion.TouchShow.AUTO -> !physicalPadUsed && !Controllers.anyConnected()
        }
        touchControls.style = style
        touchControls.portrait = touchUpright
        touchControls.gameAspect = if (::stage.isInitialized) stage.viewport.aspect else 1.5f
        if (romIs3ds) touchControls.ffOn = n3dsFfOn
        else if (::engine.isInitialized) touchControls.ffOn = engine.fastForwardToggled
        touchControls.visibility = if (show) View.VISIBLE else View.GONE
        syncTouchSkin(style, show)
        // UNDER GAME upright: the controls' own strip under the companion (none while they're hidden), so they never lie
        // over the game - with EXPAND, or where nothing is left under the companion (a tablet, a DS game's 4:3).
        if (::portraitPanel.isInitialized) {
            val prefs = Prefs(this)
            val w = stage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            portraitPanel.padStrip = if (show && portraitPanel.enabled && prefs.portraitCompanionUnderGame)
                com.pokedaisy.app.companion.ui.TouchGeometry.naturalHeight(style, w.toFloat(), resources.displayMetrics.density, !touchControls.shoulders, touchControls.dsButtons)
            else 0
        }
        syncTouchExtras()
        syncHudClear()
        if (!show) { input.setTouchBits(0); touchHeld = 0 }
    }

    /** The touch controls' ZOOM and REWIND drawn only where they can do something: ZOOM with EXPAND on, REWIND in a GBA /
     * Game Boy game (mGBA's rewind ring; with REWIND off a press says where to turn it on). */
    private fun syncTouchExtras() {
        if (!::touchControls.isInitialized || !::view.isInitialized) return
        val off = HashSet<com.pokedaisy.app.companion.TouchButton>()
        if (!view.wide) off += com.pokedaisy.app.companion.TouchButton.ZOOM
        if (romIsNds || romIs3ds) off += com.pokedaisy.app.companion.TouchButton.REWIND
        if (romBlocked != null) off += com.pokedaisy.app.companion.TouchButton.QUICK_MENU
        if (touchControls.unavailable != off) touchControls.unavailable = off
    }

    /** The touch controls' X / Y in a GBA / Game Boy game: the device's X / Y keys, through the same way as theirs (GAME
     * BUTTONS' START 2 / SELECT 2 / TO COMPANION, HOTKEYS, a rebind waiting for a key). A virtual device's, so they
     * never count as a controller (which would hide AUTO's touch controls). */
    private fun touchKey(keyCode: Int, down: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(
            now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0, 0,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD,
        ))
    }

    /** TOUCH CONTROLS > SKIN: the imported Delta skins (files/skins/). */
    private val skinStore by lazy { com.pokedaisy.app.companion.DeltaSkinStore(filesDir.toOkioPath() / "skins") }

    /** The skin's game placement handed to the stage, kept while it's the same skin (a new lambda relays it out). */
    private var skinGameFor: Pair<String, (Int, Int) -> androidx.compose.ui.geometry.Rect?>? = null

    /** SKIN's pick for this game's system on the touch controls, and (held sideways) the game in the skin's screen. */
    private fun syncTouchSkin(style: com.pokedaisy.app.companion.TouchControlsStyle, show: Boolean) {
        val system = when {
            touchControls.dsButtons -> com.pokedaisy.app.companion.SkinSystem.DS
            !touchControls.shoulders -> com.pokedaisy.app.companion.SkinSystem.GB
            else -> com.pokedaisy.app.companion.SkinSystem.GBA
        }
        val skin = if (show) style.skinFor(system)?.let { skinStore.load(it) } else null
        touchControls.skin = skin
        if (!::stage.isInitialized) return
        stage.viewport.skinHost = touchControls
        if (skin == null || touchControls.portrait) {
            skinGameFor = null
            stage.viewport.skinGame = null
            return
        }
        val fn = skinGameFor?.takeIf { it.first == skin.id + ":" + skin.dir.lastModified }?.second ?: run {
            val f: (Int, Int) -> androidx.compose.ui.geometry.Rect? = { w, h ->
                com.pokedaisy.app.companion.ui.skinFit(skin, w.toFloat(), h.toFloat(), portrait = false)?.game
            }
            skinGameFor = skin.id + ":" + skin.dir.lastModified to f
            f
        }
        stage.viewport.skinGame = fn
    }

    /** The touch controls' buttons: the game's, or the QUICK MENU's while it's open (each newly held one a press). Its
     * scrim takes new touches then, so that's a finger already down (the MENU button's) sliding on. */
    private var touchHeld = 0
    private fun onTouchKeys(gba: Int, turbo: Int) {
        val newly = gba and touchHeld.inv()
        touchHeld = gba
        if (quickMenu.open) {
            input.setTouchBits(0)
            menuKeyFor(newly)?.let { quickMenu.key(it) }
            return
        }
        // A co-op overlay (join prompt, wait, the partner's battle menu) takes the touch pad's buttons the same way.
        if (coopOverlay.takesKeys) {
            input.setTouchBits(0)
            menuKeyFor(newly)?.let { CoopBattles.takeKey(it) }
            return
        }
        input.setTouchBits(gba, turbo)
    }

    /** The touch controls' buttons from either thread: the input windows' own (TouchInputWindows) set the game's at once;
     * anything a menu over the game takes (the QUICK MENU, a co-op prompt) goes through the UI thread as before. */
    private fun onTouchKeysAnyThread(gba: Int, turbo: Int) {
        if (android.os.Looper.myLooper() == mainLooper) return onTouchKeys(gba, turbo)
        if (quickMenu.open || coopOverlay.takesKeys) { runOnUiThread { onTouchKeys(gba, turbo) }; return }
        touchHeld = gba
        input.setTouchBits(gba, turbo)
    }

    /** Nothing a touch could be meant for is open over the game (UI thread): the touch controls' input windows may take
     * the fingers on the buttons ([TouchInputWindows]); else the drawn pad does, under whatever is open. */
    private fun touchWindowsAllowed(): Boolean {
        if (touchEditor.isOpen || keyCapture.request != null || quickMenu.open || hudPanelOpen.value || battlePicking.value) return false
        if (::sidePanel.isInitialized && sidePanel.overGame) return false
        if (coopOverlay.takesKeys || coopOverlay.linkWait != null) return false
        if (coopLockState.value?.ui?.value?.let { it.waiting != null || it.partnerProposed } == true) return false
        if (expandOfferUp.value || bottomFocus.value || companionOverGame.value) return false
        if (!tipsOnCompanion.value && com.pokedaisy.app.companion.FeatureTips.scheduler?.current != null) return false
        if (!tipsOnCompanion.value && com.pokedaisy.app.companion.ui.FlyStyleAsk.showing) return false
        if (!tipsOnCompanion.value && companionSettings.battleRetryAsking) return false
        return true
    }

    /** Touch extras and hotkeys share their action policy. Android retains the extra menu and native key delivery. */
    private fun onTouchAction(button: com.pokedaisy.app.companion.TouchButton, down: Boolean) {
        if (down && touchEditor.isOpen) return
        if (!::engine.isInitialized || gameActions.touch(button, down)) return
        when (button) {
            com.pokedaisy.app.companion.TouchButton.QUICK_MENU -> if (down) handleHotkey(Hotkeys.Event(Hotkeys.Action.QUICK_MENU, true))
            com.pokedaisy.app.companion.TouchButton.ZOOM -> if (down) handleHotkey(Hotkeys.Event(Hotkeys.Action.ZOOM, true))
            com.pokedaisy.app.companion.TouchButton.X -> touchKey(KeyEvent.KEYCODE_BUTTON_X, down)
            com.pokedaisy.app.companion.TouchButton.Y -> touchKey(KeyEvent.KEYCODE_BUTTON_Y, down)
            else -> Unit
        }
    }

    /** The game's screen as the touch controls see it: the stage, the game's picture, the controls' area (an upright
     * phone's gap, or beside a docked side panel), the companion under the game upright. Null before it's laid out. */
    private fun measuredTouchStage(): com.pokedaisy.app.companion.TouchStage? {
        if (!::stage.isInitialized || stage.width == 0 || stage.height == 0) return null
        val w = stage.width.toFloat()
        val h = stage.height.toFloat()
        val area = stage.frames?.controls ?: androidx.compose.ui.geometry.Rect(0f, 0f, w, h)
        val picture = stage.gameBounds()
        val aspect = stage.viewport.aspect
        val portrait = touchUpright
        // EXPAND: the map fills the game's whole view.
        val game = if (::portraitPanel.isInitialized && portraitPanel.expand) {
            picture
        } else hudFrame.value?.translate(fieldHudBounds.left, fieldHudBounds.top) ?: run {
            com.pokedaisy.app.companion.ui.fittedGamePicture(
                view.width.toFloat(), view.height.toFloat(), aspect, topAligned = portrait)
                .translate(picture.left, picture.top)
        }
        return com.pokedaisy.app.companion.TouchStage(
            w, h, resources.displayMetrics.density, portrait, game, area,
            companion = if (portrait) portraitPanel.companionBounds() else null,
            gameAspect = aspect, gameBoy = !touchControls.shoulders, ds = touchControls.dsButtons,
        )
    }

    /** The newest savestate's picture of this game, for TOUCH CONTROLS' preview; null = none. */
    private fun newestStateShot(): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        states?.allSlots()?.mapNotNull { it.thumb }?.maxByOrNull { it.lastModified() }
            ?.let { android.graphics.BitmapFactory.decodeFile(it.path) }?.asImageBitmap()
    }.getOrNull()

    /** EDIT LAYOUT over the running game (TOUCH CONTROLS' row on the companion): the companion's window steps aside,
     * the controls themselves hide while their stand-ins are moved, and DONE / BACK keeps the layout. */
    private val touchRev = androidx.compose.runtime.mutableIntStateOf(0)
    private val touchEditor = com.pokedaisy.app.companion.ui.TouchEditorState(
        read = { Prefs(this).touchControlsStyle }, write = { Prefs(this).touchControlsStyle = it },
        closed = { touchRev.intValue++; syncTouchControls() })

    private fun openTouchEditor() {
        if (touchEditor.isOpen) return
        val measured = measuredTouchStage() ?: return
        if (::engine.isInitialized) {
            gameActions.cancelTouch()
            engine.rewindHold.revoke()
        }
        if (::sidePanel.isInitialized) sidePanel.closeWindow()
        input.setTouchBits(0)
        touchEditor.open(measured)
        syncTouchControls()
    }

    // --- input ---------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        noteKeyDelay(event)
        // The companion's rebind waiting for keys: they're its alone - not the game's, the hotkeys' or BACK's.
        if (keyCapture.onKey(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        bottomFocusTakes(event)?.let { return it }
        // The co-op prompt / waits / battle menu first: with the single-screen companion window open it would otherwise own every key
        // and the join prompt would time out unanswered.
        coopOverlayTakes(event)?.let { return it }
        menuKeyTakes(event)?.let { return it }
        quickMenuTakes(event)?.let { return it }
        dsBattleTakes(event)?.let { return it }
        if (hotkeyTakes(event.keyCode, down, repeat = down && event.repeatCount > 0)) return true
        battleSteerTakes(event)?.let { return it }
        if (input.onKey(event.keyCode, down)) {
            if (down && !physicalPadUsed && event.device?.isVirtual == false) {
                physicalPadUsed = true
                syncTouchControls()
            }
            return true
        }
        // A controller's HOME / guide button (unbound): a BACK tap - the companion's back, or the side panel.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (!down && !event.isCanceled) companionBackTap()
            return true
        }
        if (down && event.repeatCount == 0 && event.keyCode != KeyEvent.KEYCODE_BACK) {
            // Helps discover a device's real keycodes (e.g. L2/R2) for rebinding.
            Log.i("pokedaisy", "unmapped key ${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
        }
        return super.dispatchKeyEvent(event)
    }

    // --- TO COMPANION (X by default): the buttons on the other screen's companion (two screens, a GBA / Game Boy game's field) ---

    /**
     * The game's buttons steer the companion on the other screen, as a DS game's X opens its menu on the touch screen:
     * GAME BUTTONS' TO COMPANION (X by default) on the field ([canFocusBottom]) sends them down there, the game gets none
     * ([bottomFocusTakes]) and a silver strip on its picture points down ([CompanionFocusStripe]). It again, START, B (or BACK) with nothing
     * left to go back from, a tap on the strip, or the game leaving the field bring them back. Compose state: the
     * companion ([CompanionFocusHost]) and the HUD read it.
     */
    private val bottomFocus = androidx.compose.runtime.mutableStateOf(false)
    /** The companion's keys while [bottomFocus] (L / R step the tabs; the D-pad and A move its focus). */
    private val bottomKeys = com.pokedaisy.app.companion.ui.CompanionKeys()
    /** The joystick's D-pad / stick directions while [bottomFocus], so a new one is a press. */
    private var bottomHat = 0

    /** A DS game with the companion on the second screen's switch, where the game's buttons can reach it (not SWAP SCREENS'). */
    private fun dsCompanionKeyed() = twoScreenGame && dsBottom != null && presentation?.isShowing == true && !Prefs(this).swapScreens

    /** TO COMPANION can take the buttons down now: a GBA / Game Boy game on the field with its controls free (no text box, script or
     * START menu, where the field read knows), the companion on the other screen, nothing else holding the buttons. */
    private fun canFocusBottom(): Boolean {
        if (twoScreenGame || debugMirror || companionClosed || companionOverGame.value) return false
        // The other screen, or the companion under the game on a phone held upright (GitHub discussion #308).
        if (presentation?.isShowing != true && !(::portraitPanel.isInitialized && portraitPanel.enabled)) return false
        if (!::engine.isInitialized || !engine.onField.value || engine.fieldLocked) return false
        if (quickMenu.open || touchEditor.isOpen || keyCapture.request != null) return false
        if (::sidePanel.isInitialized && sidePanel.windowOpen) return false
        val snap = telemetry.snapshot.value
        return snap.hasData && !snap.inBattle
    }

    private fun setBottomFocus(on: Boolean) {
        if (bottomFocus.value == on) return
        if (on && ::engine.isInitialized) cancelRewindForOverlay()
        bottomFocus.value = on
        // A DS game's second screen goes back to its touch screen with the buttons; a hidden upright companion comes up.
        if (!on && twoScreenGame) dsBottom?.show(false)
        if (on && ::portraitPanel.isInitialized && portraitPanel.enabled) portraitPanel.reveal()
        // The player's held keys let go in the game (which runs on), and the other way round.
        input.releaseAll()
        bottomHat = 0
        hatRepeat.stop()
        if (on && ::view.isInitialized) view.camera.let { it.stickX = 0f; it.stickY = 0f }
    }

    /**
     * A key with the buttons on the other screen's companion, or TO COMPANION's taking them there - null = not its business. Keys
     * pressed there stay away from the game until let go ([menuHeld]); the hotkeys only hear releases (a hold let go).
     */
    private fun bottomFocusTakes(event: KeyEvent): Boolean? {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_BACK) return null
        if (!bottomFocus.value) {
            if (!down || event.repeatCount != 0 || !input.isCompanion(code) || !canFocusBottom()) return null
            setBottomFocus(true)
            menuHeld.add(code)
            return true
        }
        if (::hotkeys.isInitialized && hotkeysOn && (!down || event.repeatCount == 0)) {
            for (e in hotkeys.onKey(code, down)) if (!e.pressed || e.action == Hotkeys.Action.COMPANION) handleHotkey(e)
        }
        if (code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (!down && !event.isCanceled) companionBackTap()
            return true
        }
        val x = input.isCompanion(code)
        // TO COMPANION's key goes back whatever it presses in the game (START 2, by default).
        val key = if (x) MenuKey.X else menuKeyFor(input.buttonFor(code) ?: 0) ?: return super.dispatchKeyEvent(event)
        if (!down) {
            menuHeld.remove(code)
            return true
        }
        val first = event.repeatCount == 0
        // A key held from before: its repeats aren't presses.
        if (first) menuHeld.add(code) else if (code !in menuHeld) return true
        when (key) {
            MenuKey.X, MenuKey.START -> if (first) setBottomFocus(false)
            MenuKey.B -> if (first && !companionBack.back()) setBottomFocus(false)
            else -> bottomKeys.key(key, repeat = !first)
        }
        return true
    }

    /** A joystick's D-pad / stick while the buttons are on the other screen's companion: each direction that comes on
     * is a press (a screen that steers with the stick itself takes it; the hat stays the D-pad). */
    private fun bottomMotion(event: MotionEvent): Boolean {
        var bits = GbaInput.motionBits(event) ?: return false
        if (bottomKeys.stick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))) {
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            bits = (if (hx < -0.5f) CoreKey.LEFT else 0) or (if (hx > 0.5f) CoreKey.RIGHT else 0) or
                (if (hy < -0.5f) CoreKey.UP else 0) or (if (hy > 0.5f) CoreKey.DOWN else 0)
        }
        val pressed = bits and bottomHat.inv()
        bottomHat = bits
        menuKeyFor(pressed)?.let { bottomKeys.key(it) }
        // Held: it repeats, as a held key does (a hat's axes never do by themselves).
        hatRepeat.update(bits, pressed) { bit -> if (bottomFocus.value) menuKeyFor(bit)?.let { bottomKeys.key(it, repeat = true) } else hatRepeat.stop() }
        return true
    }

    /** A hat / stick direction held while the buttons steer the companion (the window or the other screen's), repeated. */
    private val hatRepeat = HatRepeat()

    // --- the companion's window (one screen, SidePanel) -----------------------------

    /** Keys pressed while the window was open: the game doesn't see them until they're let go (the B that closed it). */
    private val menuHeld = HashSet<Int>()
    /** The joystick's D-pad / stick directions while the window is open, so a new one is a press. */
    private var menuHat = 0

    /** The window came up (the buttons are the companion's: the player's held keys let go in the game, which runs
     * on) or went away (the buttons are the game's again). */
    private fun onCompanionWindow(open: Boolean) {
        if (open && ::engine.isInitialized) cancelRewindForOverlay()
        input.releaseAll()
        menuHat = 0
        hatRepeat.stop()
        if (open) view.camera.let { it.stickX = 0f; it.stickY = 0f }
    }

    /**
     * A key while the companion's window is open: the game's buttons (as the player bound them) steer it - null = not
     * the window's business (it's closed and the key wasn't pressed in it, BACK, or a key that's no button, like
     * the volume). The hotkeys still hear every key, but only to let go of a hold (FF / slow-mo held as it opened).
     */
    /**
     * How long a button press waited before it reached here, on the UI thread that also draws the companion: logged when
     * late, and LIGHT COMPANION's AUTO counts the late ones (an RG DS's buttons did nothing after FIGHT until the
     * companion had drawn the move list). A device it finds slow is remembered, and starts light from then on.
     */
    private fun noteKeyDelay(event: KeyEvent) {
        // System keys don't count: Android holds the volume keys back ~150 ms itself (it waits for a volume + power
        // chord), which once made the Thor's AUTO turn LIGHT COMPANION on.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || event.isSystem) return
        val now = android.os.SystemClock.uptimeMillis()
        val delay = now - event.eventTime
        if (delay < com.pokedaisy.app.companion.LightCompanion.LATE_KEY_MS) return
        Log.w("pokedaisy/perf", "key ${KeyEvent.keyCodeToString(event.keyCode)} reached the game $delay ms late")
        if (com.pokedaisy.app.companion.LightCompanion.noteKeyDelay(delay, now)) {
            Log.w("pokedaisy/perf", "buttons keep coming late: LIGHT COMPANION's AUTO turns it on")
            Prefs(this).lightCompanionMeasured = true
            if (com.pokedaisy.app.companion.LightCompanion.on) syncGameScreen()
        }
    }

    private fun menuKeyTakes(event: KeyEvent): Boolean? {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_BACK) return null
        if (!::sidePanel.isInitialized || !sidePanel.windowOpen) {
            if (code !in menuHeld) return null
            if (!down) {
                menuHeld.remove(code)
                // The hotkeys heard it go down (in the window, the QUICK MENU or with X's companion focus): they hear it
                // let go too, else it stays held for them and a later key alone completes its chord (GitHub #264: X
                // that ended the focus, then SELECT minutes later, ran SELECT + X's EXIT GAME).
                if (::hotkeys.isInitialized) for (e in hotkeys.onKey(code, false)) if (!e.pressed) handleHotkey(e)
            }
            return true
        }
        if (::hotkeys.isInitialized && hotkeysOn && (!down || event.repeatCount == 0)) {
            for (e in hotkeys.onKey(code, down)) if (!e.pressed) handleHotkey(e)
        }
        if (code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (!down && !event.isCanceled) companionBackTap()
            return true
        }
        val key = menuKeyFor(input.buttonFor(code) ?: 0) ?: return super.dispatchKeyEvent(event)
        if (!down) {
            menuHeld.remove(code)
            return true
        }
        val first = event.repeatCount == 0
        // A key held from before the window opened: its repeats aren't presses.
        if (first) menuHeld.add(code) else if (code !in menuHeld) return true
        sidePanel.menuKey(key, repeat = !first)
        return true
    }

    /**
     * A key while the QUICK MENU is open: the game's buttons (as bound) steer it and stay away from the game until let go
     * ([menuHeld], like the companion window's); the hotkeys hear everything (its own button letting go runs a held pick).
     * Null = not its business (closed, BACK, a key that's no button).
     */
    private fun quickMenuTakes(event: KeyEvent): Boolean? {
        if (!quickMenu.open) return null
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_BACK) return null
        if (::hotkeys.isInitialized && hotkeysOn) {
            if (!down || event.repeatCount == 0) for (e in hotkeys.onKey(code, down)) handleHotkey(e)
            if (hotkeys.consumes(code)) return true
        }
        val key = menuKeyFor(input.buttonFor(code) ?: 0) ?: return null
        if (!down) {
            menuHeld.remove(code)
            return true
        }
        menuHeld.add(code)
        if (quickMenu.open) quickMenu.key(key)
        return true
    }

    // --- HIDE BATTLE MENU: the D-pad on the companion's battle buttons ---------------------------------

    /** D-pad keys a press of which [battleSteerTakes] took: their releases stay away from the game too. */
    private val steerHeld = HashSet<Int>()
    /** The stick / hat directions while it steers ([steerMotion]), so a new one is a press. */
    private var steerHat = 0

    /**
     * HIDE BATTLE MENU with the game on its action menu (off its screen, the companion's buttons standing in): the D-pad
     * moves the cursor round those buttons as the bottom screen lays them out, FIGHT over BAG / RUN / POKéMON
     * ([EmulatorEngine.steerBattleMenu]), not round the game's unseen 2 x 2. A and B stay the game's own, so they act on the
     * button the companion lights. Null = not its business (another key, or the menu isn't hidden there).
     */
    private fun battleSteerTakes(event: KeyEvent): Boolean? {
        val code = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN) return if (steerHeld.remove(code)) true else null
        val dir = (input.buttonFor(code) ?: return null) and DPAD_KEYS
        if (dir == 0) return null
        // A held direction's repeats: no more steps (the game moves its cursor once a press too).
        if (event.repeatCount > 0) return if (code in steerHeld) true else null
        if (!steerPress(dir)) return null
        steerHeld.add(code)
        return true
    }

    /** A joystick's D-pad hat / stick while the hidden action menu is up: each direction that comes on is a press
     * ([battleSteerTakes]); centred, it's let go in the game too, so nothing stays held there. */
    private fun steerMotion(event: MotionEvent): Boolean {
        val bits = (GbaInput.motionBits(event) ?: return false) and DPAD_KEYS
        if (!::engine.isInitialized || engine.battleMenuHidden != BATTLE_MENU_ACTIONS) {
            steerHat = 0
            return false
        }
        val pressed = bits and steerHat.inv()
        steerHat = bits
        if (bits == 0) {
            input.onMotion(event)
            return true
        }
        if (pressed != 0) steerPress(pressed)
        return true
    }

    /** One D-pad press for the hidden action menu: taken (with the companion's click when the cursor moves - the game
     * hears no press, so plays no sound of its own) or not. */
    private fun steerPress(dir: Int): Boolean {
        if (!::engine.isInitialized) return false
        val from = engine.battleMenuCursor
        if (!engine.steerBattleMenu(dir)) return false
        if (from >= 0 && HiddenMenuSteer.next(from, dir) != from) playClick()
        return true
    }

    /** A DS battle's Gen 3 menu is up ([dsBattleMenu]): the D-pad, A and B are its, kept from the game until let go. */
    private fun dsBattleTakes(event: KeyEvent): Boolean? {
        val code = event.keyCode
        if (dsBattleMenu.mode == 0 && code !in menuHeld) return null
        if (code == KeyEvent.KEYCODE_BACK) return null
        val key = menuKeyFor(input.buttonFor(code) ?: 0) ?: return null
        if (event.action != KeyEvent.ACTION_DOWN) return if (menuHeld.remove(code)) true else null
        if (dsBattleMenu.mode == 0) return null
        if (event.repeatCount == 0 && !dsBattleMenu.key(key)) return null
        menuHeld.add(code)
        return true
    }

    /** A joystick's D-pad hat while a DS battle's menu is up: each direction that comes on is a press. */
    private fun dsBattleMotion(event: MotionEvent): Boolean {
        val bits = GbaInput.motionBits(event) ?: return false
        val pressed = bits and dsBattleHat.inv()
        dsBattleHat = bits
        menuKeyFor(pressed)?.let { dsBattleMenu.key(it) }
        return true
    }

    /** A joystick's D-pad / stick while the QUICK MENU is open: each direction that comes on is a press. */
    private fun quickMotion(event: MotionEvent): Boolean {
        quickStickOff = GbaInput.motionBits(event)?.let { it != 0 } == true
        // The ring: the left stick points at a tile (its D-pad hat still steps, below).
        if (quickMenu.ring && event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            kotlin.math.abs(event.getAxisValue(MotionEvent.AXIS_HAT_X)) < 0.5f && kotlin.math.abs(event.getAxisValue(MotionEvent.AXIS_HAT_Y)) < 0.5f &&
            quickMenu.aim(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        ) {
            quickHat = 0
            return true
        }
        val bits = GbaInput.motionBits(event) ?: return false
        val pressed = bits and quickHat.inv()
        quickHat = bits
        menuKeyFor(pressed)?.let { quickMenu.key(it) }
        return true
    }

    /** The window's key for a GBA button bit (a diagonal counts as its up / down). */
    private fun menuKeyFor(bits: Int): MenuKey? = when {
        bits and CoreKey.UP != 0 -> MenuKey.UP
        bits and CoreKey.DOWN != 0 -> MenuKey.DOWN
        bits and CoreKey.LEFT != 0 -> MenuKey.LEFT
        bits and CoreKey.RIGHT != 0 -> MenuKey.RIGHT
        bits and CoreKey.A != 0 -> MenuKey.A
        bits and CoreKey.B != 0 -> MenuKey.B
        bits and CoreKey.L != 0 -> MenuKey.L
        bits and CoreKey.R != 0 -> MenuKey.R
        bits and CoreKey.START != 0 -> MenuKey.START
        bits and CoreKey.SELECT != 0 -> MenuKey.SELECT
        bits and CoreKey.X != 0 -> MenuKey.X
        bits and CoreKey.Y != 0 -> MenuKey.Y
        else -> null
    }

    /** A joystick's D-pad / stick while the window is open: each direction that comes on is a press. */
    private fun menuMotion(event: MotionEvent): Boolean {
        var bits = GbaInput.motionBits(event) ?: return false
        // A screen that steers with the stick itself (HeartGold / SoulSilver's map) takes it; the hat stays the D-pad.
        if (sidePanel.menuStick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))) {
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            bits = (if (hx < -0.5f) CoreKey.LEFT else 0) or (if (hx > 0.5f) CoreKey.RIGHT else 0) or
                (if (hy < -0.5f) CoreKey.UP else 0) or (if (hy > 0.5f) CoreKey.DOWN else 0)
        }
        val pressed = bits and menuHat.inv()
        menuHat = bits
        menuKeyFor(pressed)?.let { sidePanel.menuKey(it) }
        hatRepeat.update(bits, pressed) { bit -> if (sidePanel.windowOpen) menuKeyFor(bit)?.let { sidePanel.menuKey(it, repeat = true) } else hatRepeat.stop() }
        return true
    }

    // BACK: a tap is the companion's back (closes what's open there, else
    // nothing - no accidental exit mid-game); hold to return to the ROM library.
    // With one screen a tap also opens / closes the side panel (SidePanel.onBack).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { event.startTracking(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { exitGame(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Not after a hold (that's canceled) - the long press already left.
            if (event.isTracking && !event.isCanceled) companionBackTap()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun companionBackTap() {
        // EDIT LAYOUT over the game: BACK is its DONE.
        if (touchEditor.isOpen) { touchEditor.back.back(); return }
        // DS LAYOUT's touch screen opened by hand (and no companion window over it): BACK closes it first.
        if (dsWindowManual.value && !sidePanel.windowOpen) { dsWindowManual.value = false; return }
        // The companion was closed: BACK brings it back. Not while it opens over the game instead (closed while swapped):
        // then BACK is that companion's, as on one screen, and its OPEN COMPANION puts it back on its own screen.
        if (companionClosed && !companionOverGame.value) {
            reopenCompanion()
        } else if (dsBottom?.showingCompanion == true && !sidePanel.windowOpen) {
            // A DS game's companion on the bottom display (and none open over the game): BACK is its own, and with
            // nothing left to go back from it goes back to the touch screen.
            if (!hudBack.back() && !companionBack.back()) dsBottom?.show(false)
        } else if (bottomFocus.value) {
            // The buttons on the other screen's companion (X): BACK is its own, and with nothing left there they go back.
            if (!companionBack.back()) setBottomFocus(false)
        } else if (!hudBack.back() && !sidePanel.onBack() && !portraitPanel.onBack()) { companionBack.back(); mirrorBack.back() }
    }

    /** Feeds a key change to the hotkeys (only while they're on); true when the game mustn't see it
     *  ([Hotkeys.gameKey]: completing a chord, or SELECT / START of one held back until let go). */
    private fun hotkeyTakes(keyCode: Int, down: Boolean, repeat: Boolean = false): Boolean {
        if (!::hotkeys.isInitialized || !hotkeysOn) return false
        if (repeat) return hotkeys.consumes(keyCode)
        return hotkeys.gameKey(keyCode, down, input, ::handleHotkey)
    }

    /** Another game button: SELECT / START held back for a chord go to the game first, held. */
    private fun letHeldBackThrough() {
        if (::hotkeys.isInitialized) for (k in hotkeys.letThrough()) input.onKey(k, true)
    }

    // Many Android handhelds (Retroid's) report L2/R2 as analog axes, not BUTTON_L2/R2 keys: a trigger
    // crossing half way is that key, through the hotkeys (their switch and bindings, issue #54) and
    // then the game's controls. Hard-wired to FF / slow-mo holds, they used to ignore both.
    private var rtDown = false
    private var ltDown = false

    // A controller's stick / D-pad / triggers, before the views: with the companion in this
    // window (side panel, phone upright) a focused ComposeView would see them first.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && keyCapture.onTriggers(
                maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
                maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS), event.getAxisValue(MotionEvent.AXIS_THROTTLE)),
            )
        ) return true
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && onGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (::engine.isInitialized) {
            val rt = maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS),
                event.getAxisValue(MotionEvent.AXIS_THROTTLE),
            )
            val lt = maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE),
            )
            if ((rt > 0.5f) != rtDown) { rtDown = rt > 0.5f; triggerKey(KeyEvent.KEYCODE_BUTTON_R2, rtDown) }
            if ((lt > 0.5f) != ltDown) { ltDown = lt > 0.5f; triggerKey(KeyEvent.KEYCODE_BUTTON_L2, ltDown) }
        }
        if (::sidePanel.isInitialized && sidePanel.windowOpen && menuMotion(event)) return true
        if (bottomFocus.value && bottomMotion(event)) return true
        if (quickMenu.open && quickMotion(event)) return true
        if (dsBattleMenu.mode != 0 && dsBattleMotion(event)) return true
        if (coopOverlay.takesKeys && coopMotion(event)) return true
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            // The right stick: WIDE's look-around (WideCamera). Most pads report it as Z / RZ, some as RX / RY.
            fun axis(a: Int, b: Int) = event.getAxisValue(a).let { v -> if (kotlin.math.abs(v) > 0.05f) v else event.getAxisValue(b) }
            view.camera.stickX = axis(MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
            view.camera.stickY = axis(MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
            if (view.wide && maxOf(kotlin.math.abs(view.camera.stickX), kotlin.math.abs(view.camera.stickY)) > 0.6f) {
                com.pokedaisy.app.companion.FeatureTips.used(TipId.ZOOM)
            }
        }
        // A 3DS game: the left stick is the circle pad, the hat the D-pad (N3dsSession), not the app's stick-as-D-pad.
        n3ds?.let { if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) { it.onMotion(event); return true } }
        if (quickStickHold) {
            if (GbaInput.motionBits(event)?.let { it != 0 } == true) return true
            quickStickHold = false
        }
        if (steerMotion(event)) return true
        if (GbaInput.motionBits(event)?.let { it != 0 } == true) letHeldBackThrough()
        if (input.onMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }

    /** An analog trigger as its key. A pad that also sends the key itself is harmless: the hotkeys
     *  fire a chord once while it's held, and the game's button is just set twice. */
    private fun triggerKey(keyCode: Int, down: Boolean) {
        // The window over the game: a trigger only lets go of a hold (FF held on it as the window opened).
        if (::sidePanel.isInitialized && sidePanel.windowOpen) {
            if (!down) hotkeyTakes(keyCode, false)
            return
        }
        if (!hotkeyTakes(keyCode, down)) input.onKey(keyCode, down)
    }

    private val gameBindings by lazy {
        com.pokedaisy.app.companion.GameBindings(controlsDir, Hotkeys.Action.entries) {
            input.setControls(GbaControls.load(controlsDir))
            reloadHotkeys()
        }
    }

    /** Replacing the parser cannot retain an action owned by its old chords. Touch extras keep their own holds. */
    private fun reloadHotkeys() {
        if (::engine.isInitialized) gameActions.cancelHardware()
        hotkeys = Hotkeys.load(controlsDir)
    }

    /** A companion rebind started / ended: its popup over the game, and nothing left held - the game's keys, a held
     * FF / slow-mo / rewind, a half-pressed chord, the right stick's look-around - since their releases go to it. */
    private fun onKeyCapture(on: Boolean) {
        if (::engine.isInitialized) gameActions.cancelTouch()
        keyCaptureShown = on
        input.releaseAll()
        rtDown = false; ltDown = false
        if (::view.isInitialized) { view.camera.stickX = 0f; view.camera.stickY = 0f }
        if (::engine.isInitialized) gameActions.clear()
        reloadHotkeys()
    }

    /** Turns the hotkeys on/off from the companion. Off lets go of any held one (FF / slow-mo
     * hold) and forgets the keys held so far, so a chord can't fire half-pressed once back on. */
    private fun setHotkeysOn(on: Boolean) {
        hotkeysOn = on
        reloadHotkeys()
    }

    private val gameActions get() = engine.playback.actions ?:
        engine.playback.bindActions(companionSettings, stateSlots,
            selectSlot = { index -> engine.cycleSlot(index - engine.currentSlot) },
            hold = ::holdGameAction, companion = ::toggleGameCompanion,
            rewindHold = engine.rewindHold,
            canRewind = {
                !romIsNds && !romIs3ds && engine.running && engine.canRewind &&
                    !quickMenu.open && !touchEditor.isOpen && keyCapture.request == null && !bottomFocus.value &&
                    !coopOverlay.takesKeys && !(::sidePanel.isInitialized && sidePanel.windowOpen)
            },
            rewindDenied = ::showRewindDenied)

    private fun holdGameAction(action: Hotkeys.Action, down: Boolean) {
        if (romIs3ds) { n3dsHotkey(Hotkeys.Event(action, down)); return }
        when (action) {
            Hotkeys.Action.FF_HOLD -> engine.setFastForwardHeld(down)
            Hotkeys.Action.SLOWMO_HOLD -> engine.setSlowmoHeld(down)
            Hotkeys.Action.REWIND_HOLD -> if (down) showHud(com.pokedaisy.app.companion.i18n.tr("Rewinding"))
            else -> Unit
        }
    }

    private fun cancelRewindForOverlay() {
        engine.rewindHold.revoke()
        engine.rewindHold.update(com.pokedaisy.app.game.RewindHold.Source.TOUCH, false)
    }

    private fun showRewindDenied() {
        if (romIs3ds) return
        val message = when {
            romIsNds -> com.pokedaisy.app.companion.i18n.tr("Rewind doesn't work in DS games")
            coopRefuses() -> return
            CoopGuards.statesBlockedBy(CoopGuards.StateBlock.LOCK) -> com.pokedaisy.app.companion.i18n.tr("Not while you play together only")
            !Prefs(this).rewind -> com.pokedaisy.app.companion.i18n.tr("Rewind is off - turn it on in SETTINGS")
            else -> return
        }
        showHud(message)
    }

    private fun toggleGameCompanion() {
        // One screen: COMPANION BUTTON can hide the edge tab, so this is the way in. Closed on a device with a
        // second screen (CLOSE COMPANION), it comes back to its own.
        if (companionClosed) reopenCompanion()
        else if (dsCompanionKeyed()) {
            // A DS game's second screen: the game's touch screen or the companion, the buttons going with it.
            val on = dsBottom?.showingCompanion != true || !bottomFocus.value
            dsBottom?.show(on)
            setBottomFocus(on)
        }
        else if (::sidePanel.isInitialized && sidePanel.enabled) sidePanel.openPanel()
        else if (::portraitPanel.isInitialized && portraitPanel.enabled && !romIsNds) {
            portraitPanel.toggleHidden()
            if (portraitPanel.hidden) setBottomFocus(false)
        }
    }

    private fun handleHotkey(e: Hotkeys.Event) {
        if (!::engine.isInitialized) return
        if (romIs3ds && e.action != Hotkeys.Action.FF_HOLD && e.action != Hotkeys.Action.FF_TOGGLE && n3dsHotkey(e)) return
        if (gameActions.hotkey(e)) {
            if (e.action == Hotkeys.Action.MUTE && e.pressed)
                showHud(com.pokedaisy.app.companion.i18n.tr(if (GameVolume.muted) "Game muted" else "Game sound on"))
            return
        }
        when (e.action) {
            Hotkeys.Action.ZOOM -> if (e.pressed) zoomStep()
            Hotkeys.Action.SOFT_RESET -> if (e.pressed && !coopRefuses()) {
                engine.softReset()
                showHud(com.pokedaisy.app.companion.i18n.tr("Soft reset"))
            }
            Hotkeys.Action.CLOSE_APP -> if (e.pressed) closeApp()
            Hotkeys.Action.QUICK_MENU -> triggerQuickMenu(e.pressed)
            Hotkeys.Action.FREE_FLIGHT -> if (e.pressed) toggleFlight()
            Hotkeys.Action.TOUCH_SCREEN -> if (e.pressed) { com.pokedaisy.app.companion.FeatureTips.used(TipId.DS_TRIGGERS); toggleDsTouch() }
            Hotkeys.Action.DS_LAYOUT -> if (e.pressed) { com.pokedaisy.app.companion.FeatureTips.used(TipId.DS_TRIGGERS); nextDsLayout() }
            else -> Unit
        }
    }

    /** SOUND > GAME VOLUME / MUTE GAME, and the MUTE hotkey: kept, and heard at once (GameVolume). */
    private fun setGameVolume(percent: Int, muted: Boolean) {
        val prefs = Prefs(this)
        prefs.gameVolume = percent
        prefs.gameMuted = muted
        GameVolume.set(percent, muted)
    }

    /** ZOOM (its hotkey, or a tip's SHOW ME): EXPAND's next zoom step. */
    private fun zoomStep() = runCatching {
        if (!view.wide) showHud(com.pokedaisy.app.companion.i18n.tr("Zoom needs ASPECT > EXPAND"))
        else zoomTo()
    }

    /** EXPAND's zoom step [step] (the hotkey's next one, or SETTINGS > ZOOM's pick), kept for this game. */
    private fun zoomTo(step: Int? = null) {
        if (!view.wide) return
        val factor = view.camera.changeExpandedZoom(Prefs(this), romKey, step)
        showHud(tr("Zoom {0}", com.pokedaisy.app.companion.zoomLabel(factor)))
    }

    /** The QUICK MENU's key went down ([pressed]) or up: it opens or closes. */
    private fun triggerQuickMenu(pressed: Boolean) {
        // A blocked game has no quick menu (its actions are the game's features).
        if (romBlocked != null && !quickMenu.open) return
        val wasOpen = quickMenu.open
        quickMenu.trigger(pressed)
        // Opened: the buttons are the menu's now, so a held direction stops walking.
        if (!wasOpen && quickMenu.open) {
            if (::engine.isInitialized) cancelRewindForOverlay()
            com.pokedaisy.app.companion.FeatureTips.used(TipId.QUICK_MENU)
            input.releaseAll()
            quickHat = 0
            quickStickHold = false
            hudCloseTick.intValue++
            quickMenu.labels = Prefs(this).quickMenuLabels
            quickMenu.ring = Prefs(this).quickMenuStyle == com.pokedaisy.app.companion.QuickMenuStyle.RING
        }
    }

    // --- feature tips ------------------------------------------------------------

    private fun tipFrame(): GameTipFrame {
        // Screen off or activity in the background: time and events stop counting.
        val e = hudEngine.value?.takeIf { it.running && !it.sleeping && tipsResumed }
        val onCompanion = presentation != null || mainCompanion != null
        tipsOnCompanion.value = onCompanion
        val windowOpen = ::sidePanel.isInitialized && sidePanel.windowOpen
        val dismissForOverlay = quickMenu.open || windowOpen
        return GameTipFrame(
            snapshot = telemetry.snapshot.value,
            settledOnField = e != null && playing && e.onField.value && !hudUpright.value,
            blocked = dismissForOverlay || hudPanelOpen.value || expandOfferUp.value || e?.holdFrame == true,
            dismissForOverlay = dismissForOverlay,
            cardOnCompanion = onCompanion,
            moments = e?.playback?.tipMoments,
            mapOpen = e?.regionMapOpen == true,
            dsTriggers = e != null && dsStageOn,
        )
    }

    private val gameTips by lazy {
        GameTipSession(
            settings = companionSettings,
            snapshot = { telemetry.snapshot.value },
            items = { itemUse },
            fly = { flyAction },
            openQuickMenu = { triggerQuickMenu(true) },
            quickAction = ::runQuickAction,
            notice = { showHud(it) },
            frame = ::tipFrame,
            running = { ::engine.isInitialized && engine.running },
            expanded = { view.wide },
            // ExpandIntro offered it already. This is the later reminder.
            expandOfferSeen = { Prefs(this).expandGameOfferSeen },
            shortcut = ::tipKey,
            quickMenuHasActions = { quickShown.value.any(quickActions::offered) },
            hudAvailable = { hudOn.value && !hudUpright.value },
            showLookAroundKey = true,
            dsTriggers = { dsStageOn },
            dsTouchFloats = { dsFloats.value },
            cycleZoom = { zoomStep() },
            openSummary = {
                hudOpenPanel.value = com.pokedaisy.app.companion.ui.FieldHudPanel.SUMMARY to 0
                hudOpenTick.intValue++
            },
            toggleDsTouch = { toggleDsTouch() },
            nextDsLayout = { nextDsLayout() },
        )
    }

    /** [action]'s key as a tip names it ("R3", "SELECT + R1"): a controller's binding, none without a controller. */
    private fun tipKey(action: Hotkeys.Action): String? {
        if (!Controllers.anyConnected() && !physicalPadUsed) return null
        if (!::hotkeys.isInitialized) return null
        return controllerTipShortcut(hotkeys.rawBindings[action].orEmpty()) { name ->
            when (name) {
                "BUTTON_THUMBL" -> "L3"
                "BUTTON_THUMBR" -> "R3"
                else -> com.pokedaisy.app.companion.KeyCapture.label(name)
            }
        }
    }

    private fun tipKeys(tip: TipId) = gameTips.keys(tip)

    private fun tryTip(tip: TipId) = gameTips.tryIt(tip)

    // --- helpers -----------------------------------------------------------------

    /** REWIND's buffer: 600 states, one every 2 frames - the last ~20 s of play. */
    private fun rewindEntries(on: Boolean) = com.pokedaisy.app.companion.data.RewindPolicy.capacity(on, !romIsNds)

    private fun showHud(text: String, millis: Long = 1800) {
        hud.text = text
        hud.visibility = View.VISIBLE
        hud.removeCallbacks(hideHud)
        hud.postDelayed(hideHud, millis)
    }

    private fun resolveRom(intent: Intent?): File? {
        // 1. Explicit choice from LibraryActivity.
        intent?.getStringExtra(LibraryActivity.EXTRA_ROM)?.let { p ->
            File(p).takeIf { it.isFile }?.let { return it }
        }
        // 2. VIEW intent (open a .gba with the app).
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { uri ->
            copyIncoming(uri)?.let { return it }
        }
        // 3. Last played.
        Prefs(this).lastRomPath?.let { p -> File(p).takeIf { it.isFile }?.let { return it } }
        // 4. First ROM in the folder.
        val romsDir = File(getExternalFilesDir(null), "roms").apply { mkdirs() }
        return romsDir.listFiles { f ->
            f.isFile && f.name.substringAfterLast('.', "").lowercase() in GBA_EXT
        }?.sortedBy { it.name.lowercase() }?.firstOrNull()
    }

    /**
     * scripts/capture_fixture.sh's counterpart: dumps the ENTIRE EWRAM+IWRAM
     * address space (not just whatever fields the current decoder happens to
     * read) to [pendingFixtureDump], once. Full-region rather than a
     * per-field capture so app/src/test's FakeMemoryReader can serve ANY
     * address a decoder asks for - including pointer chases (gSaveBlock1Ptr
     * -> bagPocket_X, gBagPockets[p].itemSlots, ...) that land at a
     * per-boot-randomized offset (SetSaveBlocksPointers' ASLR-style offset -
     * see the FireRed rev0/rev1 gSaveBlock2Ptr bug write-up in this project's
     * memory) a fixed small capture could easily miss. Runs on the emu
     * thread (called from onSample) so the bus reads are safe.
     */
    private fun dumpFixtureIfPending() {
        val dir = pendingFixtureDump ?: return
        try {
            val ewram = MgbaCore.pkReadBytes(EWRAM_BASE, EWRAM_SIZE)
            val iwram = MgbaCore.pkReadBytes(IWRAM_BASE, IWRAM_SIZE)
            if (ewram == null || iwram == null) {
                Log.e("pokedaisy", "fixture dump: pkReadBytes returned null")
                pendingFixtureDump = null
                return
            }
            pendingFixtureDump = null // one-shot
            File(dir, "ewram.bin").writeBytes(ewram)
            File(dir, "iwram.bin").writeBytes(iwram)
            Log.i("pokedaisy", "fixture dump: wrote ${ewram.size}B EWRAM + ${iwram.size}B IWRAM to $dir")
        } catch (t: Throwable) {
            Log.e("pokedaisy", "fixture dump failed", t)
            pendingFixtureDump = null
        }
    }

    /** [uri]'s ROM copied into the cache (unpacked, if it's a .zip / .7z). */
    private fun copyIncoming(uri: Uri): File? = try {
        val out = File(cacheDir, "incoming.gba")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        RomArchive.sniff(out)?.let { format ->
            val packed = File(cacheDir, "incoming.archive")
            out.renameTo(packed)
            RomArchive.extract(packed, out, format).also { packed.delete() } ?: out.delete()
        }
        out.takeIf { it.length() > 0 }
    } catch (t: Throwable) {
        Log.e("pokedaisy", "copyIncoming failed", t)
        null
    }

    /** DS CORE for the DS game that's loaded ([dsperate]), and what follows from it: EXPAND only on melonDS (a cart its
     * field read knows). The engine starts the game on it ([startGame], [restartGame]). */
    private fun pickDsCore() {
        dsCoreId = if (romIsNds) Prefs(this).dsCore.runs() else DsCore.CORE_MELONDS
        dsperate = dsCoreId != DsCore.CORE_MELONDS
        if (!romIsNds) return
        wideGame = if (DsCore.hasExpand(dsCoreId) && (WideScreen.ndsKnows(romCode, romRev) || romOrigin)) WideScreen.NDS else WideScreen.OFF
        if (::engine.isInitialized) engine.ndsCore = dsCoreId
    }

    /** EXP SHARE on the DS: Gen 4's and Gen 5's US carts on melonDS (ExpShare.dsFind; none = N/A), into this game's
     * options; again when DS CORE changes. */
    private fun syncDsExpShare() {
        val options = currentGameOptions() ?: return
        if (!romIsNds || romBlocked != null) return
        // Hacks that keep retail's header: their battle code isn't checked.
        val hack = runCatching {
            val path = romData!!.toOkioPath()
            romCode == com.pokedaisy.app.companion.data.RenegadePlatinum.CODE && com.pokedaisy.app.companion.data.RenegadePlatinum.matches(path) ||
                (romCode == com.pokedaisy.app.companion.data.Black2Redux.CODE_BLACK2 || romCode == com.pokedaisy.app.companion.data.Black2Redux.CODE_WHITE2) &&
                com.pokedaisy.app.companion.data.Black2Redux.matches(path) ||
                // hg-engine's Heartfelt Gold and Sacred Gold / Storm Silver keep the US HeartGold / SoulSilver header (rev 0):
                // a battle engine of their own, where SPLIT would write HeartGold's BattleContext.
                romCode == com.pokedaisy.app.companion.data.HeartfeltGold.CODE && com.pokedaisy.app.companion.data.HeartfeltGold.matches(path) ||
                (romCode == com.pokedaisy.app.companion.data.SacredGold.CODE_SACRED_GOLD || romCode == com.pokedaisy.app.companion.data.SacredGold.CODE_STORM_SILVER) &&
                com.pokedaisy.app.companion.data.SacredGold.matches(path)
        }.getOrDefault(true)
        val found = com.pokedaisy.app.companion.data.ExpShare.dsFind(romCode, romRev, dsperate, hack)
        options.setExpFound(found)
        Log.i("pokedaisy", "exp share: ${if (found != null) "found (DS)" else "not found"}")
        applyGameOptions(options)
    }

    /** RESTART GAME (SETTINGS, and its hotkey): the game booted again from its save, the resume state dropped. */
    private fun restartGame() {
        if (coopRefuses()) return
        val r = rom ?: return
        if (romIs3ds) { endN3ds(); load3ds(r); startGame(); return }
        if (!::engine.isInitialized || !recoverSaveTrade()) return
        // Unbind the GL view from the current framebuffer FIRST and wait for
        // it to take — engine.stop() frees the native buffer the GL thread
        // (continuous render mode, its own clock) may still be reading; skip
        // this and it's a use-after-free race that crashes the process. See
        // EmulatorView.unbindCoreBlocking().
        view.unbindCoreBlocking()
        if (coopEndpoint != null) coopShutdown.terminate()
        engine.stop()
        states?.let { com.pokedaisy.app.companion.data.StateResume.startFresh(it.files) }
        val save = SavesLocation.saveFor(this, Prefs(this), r)
        syncCheats(reread = true)
        waitForPlay()
        // DS CORE changed while the game ran: the restart is on the new one.
        if (romIsNds) {
            val was = dsCoreId
            pickDsCore()
            syncDsExpShare()
            if (was != dsCoreId) syncGameScreen()
        }
        prepareCoopStart(r, save)
        engine.start(romData ?: r, save, null)
        showHud(com.pokedaisy.app.companion.i18n.tr("Game restarted"))
    }

    /**
     * Leaves the game. Opened by a frontend, the whole task goes, so the
     * frontend comes back rather than a library left underneath from earlier.
     */
    private fun exitGame() {
        if (fromFrontend) finishAndRemoveTask() else finish()
    }

    /**
     * CLOSE APP (a hotkey): the game left the normal way - onPause writes the resume state once a save the game is
     * writing has finished, and the core lets go of the save file - then every task of the app goes, the library
     * under the game included, so nothing is left in recents to be swiped away mid-way.
     */
    private fun closeApp() {
        val am = getSystemService(ActivityManager::class.java)
        runCatching { am.appTasks.forEach { it.finishAndRemoveTask() } }
            .onFailure { finishAndRemoveTask() }
    }

    private fun toastNoRom() {
        Toast.makeText(this, tr("No ROM found. Put a .gba (or .zip / .7z) in {0}", "Android/data/$packageName/files/roms/"), Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** See the debug-mirror comment at the `addView` call site. The
     * ViewTreeLifecycleOwner this needs is set on the root content view in
     * onCreate, not here - Compose's window recomposer looks it up starting
     * from the window's root, and one set on the root is inherited by every
     * descendant (including this ComposeView) via the normal parent walk. */
    private fun buildDebugCompanionMirror(): ComposeView {
        return ComposeView(this).apply {
            setContent {
                val snap by telemetry.snapshot.collectAsState()
                CompanionScreen(snap, stateSlots, companionSettings, battleInput, back = mirrorBack, clickSound = playClick, achievements = RetroAchievements, statusBar = companionStatusBar, itemUse = itemUse, fly = flyAction, askForSupport = askForSupport, gamePick = gamePick, shareBestEffort = shareBestEffort)
            }
        }
    }

    private fun debugMirrorLayoutParams() = FrameLayout.LayoutParams(dp(DEBUG_MIRROR_WIDTH_DP), -1).apply {
        gravity = Gravity.END
    }

    /** `adb shell touch <files-dir>/debug_mirror` (any content, or none) turns
     * this on for the next launch; `adb shell rm <files-dir>/debug_mirror`
     * turns it back off. Always false in a release build regardless. */
    private fun isDebugMirrorEnabled(): Boolean =
        BuildConfig.DEBUG && File(getExternalFilesDir(null) ?: filesDir, "debug_mirror").isFile

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    companion object {
        /** The GBA D-pad's key bits. */
        private const val DPAD_KEYS = CoreKey.UP or CoreKey.DOWN or CoreKey.LEFT or CoreKey.RIGHT
        /** [EmulatorEngine.battleMenuHidden] with the action menu (FIGHT / BAG / POKéMON / RUN) off the screen. */
        private const val BATTLE_MENU_ACTIONS = 160

        /** Retail English Emerald (BPEE): the only game co-op presence runs on. */
        /** BIKE / ROD's SELECT presses before giving up, and the frames each waits to see it take (~3 s in all at 1x). */
        /** How long a tap beside the floating touch screen holds B for the game (a few frames at 1x). */
        private const val DS_B_PRESS_MS = 80L
        /** How often [DsShots] takes the game's picture. */
        private const val DS_SHOT_MS = 1000L
        /** ONE SCREEN: a 3DS game's floating touch screen, as a share of the screen's height. */
        /** 3DS ZOOM's camera distances, times the game's own ([n3dsZoomNext]). */
        private val N3DS_CAMERA_PULLS = floatArrayOf(1f, 1.35f, 1.75f)
        private const val N3DS_FLOAT_HEIGHT = 0.82f
        /** ... and at most this share of its width. */
        private const val N3DS_FLOAT_WIDTH = 0.94f
        /** FF in a 3DS game: Azahar's frame limit, percent. */
        private const val N3DS_FF_PERCENT = 300
        /** The overworld's picture over the black between a menu and the field fades out over this once the game is back. */
        const val DS_COVER_FADE_MS = 150

        /** How long a floating window kept up through a blank touch screen waits for it ([DsTouchWindow]'s loading),
         * unless both screens are black: past it, the window goes until the touch screen shows something. */
        private const val DS_BLANK_WAIT_MS = 1000L

        /** Tries (a frame apart) at the game's picture as a floating window opens, while another copy is on its way. */
        private const val DS_FREEZE_TRIES = 10
        /** Set by [LaunchActivity]: leaving the game returns to the frontend. */
        const val EXTRA_FROM_FRONTEND = "fromFrontend"
        private val GBA_EXT = RomArchive.ROM_EXTENSIONS + RomArchive.EXTENSIONS
        private const val DEBUG_MIRROR_WIDTH_DP = 420
        /** The game's picture in its own pixel rows (X's strip is sized in them): a GBA's, a Game Boy's. */
        private const val GBA_ROWS = 160
        private const val GB_ROWS = 144
        /** How long after asking to move to the other screen a pause is that move's (Android delivers it at once). */
        private const val MOVE_PAUSE_MS = 2_000L
        private const val REFOCUS_DELAY_MS = 600L
        /** Places the player has stood on before a game whose field isn't known counts as being played: the title and
         *  CONTINUE already change it two or three times (Too Many Types 2 fills it in on the title), so a couple of
         *  steps more. */
        private const val PLAY_POSITIONS = 5
        /** LEVEL CAP: how often a sample may work the cap out again (a badge moves it). */

        private const val EXTRA_DUMP_FIXTURE = "dumpFixture"
        private const val EXTRA_DUMP_FIXTURE_FORCE = "dumpFixtureForce"
        private const val EWRAM_BASE = 0x02000000L
        private const val EWRAM_SIZE = 0x40000
        private const val IWRAM_BASE = 0x03000000L
        private const val IWRAM_SIZE = 0x8000
    }
}
