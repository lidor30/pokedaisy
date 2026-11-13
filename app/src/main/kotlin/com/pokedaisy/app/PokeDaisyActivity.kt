package com.pokedaisy.app

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pokedaisy.app.achievements.RetroAchievements
import com.pokedaisy.app.companion.ui.AchievementBadges
import com.pokedaisy.app.companion.BatteryStatus
import com.pokedaisy.app.companion.DeviceBattery
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter
import com.pokedaisy.app.companion.Smoothing
import com.pokedaisy.app.companion.ui.MenuKey
import com.pokedaisy.app.overlay.OverlayChoice
import com.pokedaisy.app.overlay.OverlayStore
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.data.FfMenuWatch
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.TipId
import com.pokedaisy.app.companion.data.hasData
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.RegionMapWatch
import com.pokedaisy.app.companion.data.RomRegionMap
import com.pokedaisy.app.companion.data.displayName
import com.pokedaisy.app.companion.ui.CompanionScreen
import com.pokedaisy.app.companion.ui.GameStatusBar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pokedaisy.app.companion.data.isGen2
import com.pokedaisy.app.companion.data.isGen1

/**
 * Phase 1 shell: full-screen GBA emulation with RetroArch-style savestate slots
 * on configurable hotkeys (see [Hotkeys]). Backgrounding suspends to a state and
 * resumes exactly where you left off.
 *
 * Drop one `.gba` into `Android/data/com.pokedaisy.app/files/roms/`.
 */
class PokeDaisyActivity : Activity() {

    private val input = GbaInput()
    /** The companion's GAME BUTTONS / HOTKEYS rows waiting for keys: every key comes here (the Presentation forwards
     * them), so the capture runs here and [keyCapturePopup] shows it over the game. */
    private val keyCapture = com.pokedaisy.app.companion.KeyCapture().apply { onActiveChanged = ::onKeyCapture }
    private lateinit var keyCapturePopup: ComposeView
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
    /** A Game Boy game's music tables (STEADY FF music), null for GBA / unknown carts. */
    private var gen1Music: com.pokedaisy.app.companion.data.Gen1MusicTables? = null
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
    // The battle state the companion's snapshot was last refreshed for (emu thread; see onBattleInputSample).
    private var lastUiBattler = -1
    private var lastUiBattleState = -1
    /** WIDE's native tables for this ROM ([WideScreen.gameFor]); OFF for a game it doesn't know. */
    private var wideGame = WideScreen.OFF
    /** The running game is one its makers asked PokéDaisy not to read ([com.pokedaisy.app.companion.data.BlockedGames], by
     * CRC): it plays as a plain game, with none of PokéDaisy's game features. Set in [loadRom], before anything else asks. */
    @Volatile private var romBlocked: com.pokedaisy.app.companion.data.BlockedGame? = null
    /** The field reads' game (SCENE SPEEDS' TEXT, AUTO-RUN, RUN INDOORS, WILD ENCOUNTERS): [WideScreen.fieldGameFor]. */
    private var fieldGame = WideScreen.OFF
    /** WILD ENCOUNTERS: the game's own switch, found in the ROM after it loads ([WildEncounters.find]; 0 = none). */
    @Volatile private var wildFlag = 0L
    /** SHINY ODDS: the detected game's foe party and player ID ([com.pokedaisy.app.companion.data.ShinyOdds]), null = a
     * game it doesn't know (the row shows N/A). Set once the game is detected. */
    @Volatile private var shinyTarget: com.pokedaisy.app.companion.data.ShinyOdds.Target? = null

    /** SHINY ODDS as saved, if this build offers it (a debug build's 1 in 4 / 1 in 1 are NORMAL in a release build). */
    private fun shinyOddsPref(): Int = Prefs(this).shinyOdds
        .takeIf { it in com.pokedaisy.app.companion.data.ShinyOdds.choices(BuildConfig.DEBUG) }
        ?: com.pokedaisy.app.companion.data.ShinyOdds.NORMAL

    /** RetroAchievements pause while SHINY ODDS is raised on a game it works in ("catch a shiny" sets). */
    private fun syncShinyAchievements() {
        val raised = shinyTarget != null && shinyOddsPref() < com.pokedaisy.app.companion.data.ShinyOdds.NORMAL
        runCatching { com.pokedaisy.app.achievements.RetroAchievements.setShinyOddsActive(raised) }
    }

    /** REUSABLE TMS: the ROM's spots that use a TM up ([com.pokedaisy.app.companion.data.ReusableTms]; empty = none). */
    private var tmSites: List<com.pokedaisy.app.companion.data.ReusableTms.Site> = emptyList()
    /** INSTANT TEXT: the ROM's patch ([com.pokedaisy.app.companion.data.InstantText]; null = the row shows N/A). */
    private var textPatch: com.pokedaisy.app.companion.data.InstantText.Patch? = null
    /** EXPAND's one-time offer is up on the companion ([CompanionSettings.expandOffer]). */
    private val expandOfferUp = androidx.compose.runtime.mutableStateOf(false)
    private var states: SaveStates? = null

    private val telemetry = TelemetryStore()

    /** PLAYER LOOK for the running game: its patches built off the UI thread, the pick handed to the engine. */
    private var lookPatch: com.pokedaisy.app.companion.data.PlayerLook.Patch? = null
    private val lookRunner by lazy {
        PlayerLookRunner(applicationContext) { p ->
            lookPatch = p
            if (::engine.isInitialized) engine.setPlayerLook(p)
        }
    }
    /** Follows the game's own player gender (a new game picks one) for PLAYER LOOK. */
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
    private lateinit var fieldHud: ComposeView
    /** DS LAYOUT's pieces on the stage ([syncDsStage]): the game's backdrop under the screens, the touch screen beside
     * or inset in the game, and the floating touch window over it ([com.pokedaisy.app.companion.ui.DsTouchWindow]). */
    private lateinit var dsBackdropView: ComposeView
    private lateinit var dsTouchView: FrameLayout
    private lateinit var dsWindowView: ComposeView
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
        q.onRepel = { id -> itemUse.use(id) { o -> showHud(com.pokedaisy.app.companion.ui.itemUseMessage(o)) } }
        // The stick onto another tile: a light tick, like a game's weapon wheel (the D-pad's steps stay silent).
        // The stick still tilted when it closes (the ring is aimed with it): kept from the game until it's let go, or the
        // player rides off - and a bike at full speed ignores the SELECT that BIKE / ROD press.
        q.onClose = { if (quickStickOff) quickStickHold = true }
        q.onAimMove = { if (::fieldHud.isInitialized) fieldHud.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
        q.onFly = { mapsec, targets ->
            flyAction.fly(mapsec, targets) { r ->
                if (r != com.pokedaisy.app.companion.data.FlyResult.OK) showHud(com.pokedaisy.app.companion.ui.flyMessage(r))
            }
        }
    }
    private val quickShown = androidx.compose.runtime.mutableStateOf(com.pokedaisy.app.companion.QuickAction.entries.toSet())
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
    /** The tips' moments, as [tickTips] last saw them: a battle (and whether a trainer's), the TMs, the game's region map,
     *  and the field play time each of the last wild battles ended at. */
    private var tipInBattle = false
    /** Between onResume and onPause: only then does play count. */
    private var tipsResumed = false
    /** Play time for the Library's support window ([com.pokedaisy.app.companion.SupportAsk]): a game running in front,
     *  not asleep, counted every 30 s into [Prefs.playMillis]. */
    private var playClock: kotlinx.coroutines.Job? = null
    private val playScope = kotlinx.coroutines.CoroutineScope(androidx.compose.ui.platform.AndroidUiDispatcher.Main)
    private var playTick = 0L
    private var tipTrainerBattle = false
    private var tipTms: Map<Int, Int>? = null
    private var tipMapOpen = false
    private val tipWildEnds = ArrayDeque<Long>()
    private val hudUpright = androidx.compose.runtime.mutableStateOf(false)
    private val hudClear = androidx.compose.runtime.mutableStateOf(0 to 0)
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
    private lateinit var stage: GameStageLayout

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
        override fun list() = states?.allSlots()?.map {
            com.pokedaisy.app.companion.StateSlots.Slot(
                it.slot, it.present, it.savedAt, it.thumb?.absolutePath, it.thumb?.lastModified() ?: 0L,
            )
        } ?: emptyList()
        override val currentIndex get() = if (::engine.isInitialized) engine.currentSlot else 0
        override fun requestSave(index: Int) { if (::engine.isInitialized && !coopRefuses()) engine.requestSaveState(index) }
        override fun requestLoad(index: Int) { if (::engine.isInitialized && !coopRefuses()) engine.requestLoadState(index) }
        override fun requestDelete(index: Int) {
            val s = states ?: return
            if (index !in SaveStates.SLOT_MIN..SaveStates.SLOT_MAX) return
            val state = s.stateFile(index)
            runCatching {
                state.delete()
                s.thumbFile(index).delete()
                s.bakFile(index).delete()
                s.bakThumbFile(index).delete()
                com.pokedaisy.app.companion.data.SnapshotCache.fileFor(state).delete()
                com.pokedaisy.app.achievements.RetroAchievements.progressFile(state).delete()
            }
        }
        override fun requestUndoSave() { if (::engine.isInitialized && !coopRefuses()) engine.requestUndoSave() }
        override fun requestUndoLoad() { if (::engine.isInitialized && !coopRefuses()) engine.requestUndoLoad() }
    }

    /** Backs the bottom-screen touch battle control (see PLAN.md Phase 5). A pick that hands the turn to the game
     * (a move, a switch, RUN, or BAG / POKéMON opening the game's own screen) closes the one-screen companion's window,
     * so the player sees it play out; FIGHT keeps it open, for the move list in it. */
    private val battleInput = object : com.pokedaisy.app.companion.BattleInput {
        override val menuCursor get() = if (::engine.isInitialized) engine.battleMenuCursor else -1
        override val busy get() = if (::engine.isInitialized) engine.battleInputBusy else false
        override val gameSilent get() = ::engine.isInitialized && engine.gameAudioPaused
        override fun selectAction(actionIndex: Int) {
            if (actionIndex != 0) closeCompanionWindow()
            if (::engine.isInitialized) engine.battleSelectAction(actionIndex)
        }
        override fun selectMove(moveIndex: Int) {
            closeCompanionWindow()
            if (::engine.isInitialized) engine.battleSelectMove(moveIndex)
        }
        override fun back() { if (::engine.isInitialized) engine.battleBack() }
        override val canSwitch get() = ::engine.isInitialized && engine.battleSwitchAddrs != null
        override fun switchTo(personality: Long) {
            closeCompanionWindow()
            if (::engine.isInitialized) engine.battleSwitchTo(personality)
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
    private fun fieldItemsFor(game: com.pokedaisy.app.companion.data.GameKind?): List<com.pokedaisy.app.companion.data.FieldItemFns> =
        com.pokedaisy.app.companion.data.fieldItemsCandidatesForGame(game, romCode, romRev, telemetry.knownFieldItems())

    /** WHICH GAME IS THIS?: the player's answers, taken by the sampler on its next pass (the emulator thread). */
    private val gamePick get() = telemetry.gamePick

    /** The match notice's SHARE - the player's yes to sending exactly what it listed. */
    private val shareBestEffort: (com.pokedaisy.app.companion.data.BestEffortReport) -> Unit = { r ->
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

    /** [fieldItemsFor]'s support on the loaded ROM (its code checked, the holdable items read from it),
     * built once per ROM - the bag's buttons and marks read it while drawing. */
    @Volatile private var fieldSupport: Pair<String, com.pokedaisy.app.companion.data.FieldItemSupport?>? = null

    private fun fieldItemSupport(): com.pokedaisy.app.companion.data.FieldItemSupport? {
        if (romBlocked != null) return null
        val game = telemetry.snapshot.value.game
        // The config's text key and tables too: Emerald Kaizo runs as EMERALD and keeps no classic FLY (fieldItemsForGame);
        // Recharged Emerald shares retail Emerald's code, revision and kind, but not its functions.
        val candidates = fieldItemsFor(game).ifEmpty { return null }
        val key = "$romCode/$romRev/$game/${com.pokedaisy.app.companion.data.romGameCode}/" +
            candidates.joinToString(",") { "${it.varGet}/${it.items}" }
        fieldSupport?.let { (k, v) -> if (k == key) return v }
        if (!::engine.isInitialized || !engine.running) return null
        // ROM bytes only (the core lock keeps a closing game's core safe); a read that fails isn't cached. The first
        // table whose code is in the ROM (SoulGold's builds share one game).
        val support = runCatching {
            candidates.firstNotNullOfOrNull { fns ->
                com.pokedaisy.app.companion.data.fieldItemSupport(fns) { addr, len -> com.pokedaisy.app.companion.data.InProcessReader.readCoreMemory(addr, len) }
            }
        }.getOrElse { return null }
        fieldSupport = key to support
        return support
    }

    /** TRADE EVOLUTIONS' calls for the loaded ROM ([com.pokedaisy.app.companion.data.tradeEvoFor]), its code checked once
     * per ROM; null where the game has none (a hack, another language, a Game Boy or DS game). */
    @Volatile private var tradeEvoCache: Pair<String, com.pokedaisy.app.companion.data.TradeEvoFns?>? = null

    private fun tradeEvoFns(): com.pokedaisy.app.companion.data.TradeEvoFns? {
        if (romIsGameBoy || romIsNds) return null
        val game = telemetry.snapshot.value.game
        val hack = com.pokedaisy.app.companion.data.runningFireRedHack != null || com.pokedaisy.app.companion.data.runningEmeraldHack != null ||
            com.pokedaisy.app.companion.data.emeraldLegacy || com.pokedaisy.app.companion.data.romGameCode == com.pokedaisy.app.companion.data.KAIZO_TEXT
        val evo = com.pokedaisy.app.companion.data.tradeEvoFor(game, romCode, romRev, hack) ?: return null
        val key = "$romCode/$romRev/$game/${com.pokedaisy.app.companion.data.romGameCode}"
        tradeEvoCache?.let { (k, v) -> if (k == key) return v }
        if (!::engine.isInitialized || !engine.running) return null
        val ok = runCatching {
            com.pokedaisy.app.companion.data.tradeEvoCodeMatches(evo) { addr, len -> com.pokedaisy.app.companion.data.InProcessReader.readCoreMemory(addr, len) }
        }.getOrElse { return null }
        Log.i("pokedaisy", "trade evolutions: ${if (ok) "code found" else "code not found"}")
        val v = evo.takeIf { ok }
        tradeEvoCache = key to v
        return v
    }

    /** The TM screen's calls ([com.pokedaisy.app.companion.data.tmFnsFor]) and FLASH's ([com.pokedaisy.app.companion.data.flashFnsFor])
     * for the loaded ROM, each one's code checked once per ROM, the way [tradeEvoFns] is; null where the game has none. */
    @Volatile private var tmCache: Pair<String, com.pokedaisy.app.companion.data.TmFns?>? = null
    @Volatile private var flashCache: Pair<String, com.pokedaisy.app.companion.data.FlashFns?>? = null

    private fun retailFieldKey(): Pair<String, Boolean>? {
        if (romIsGameBoy || romIsNds) return null
        // A rebuild on retail's game code (Recharged Emerald): none of retail's code, its builds' own records (the CRCs pick
        // the build). Emerald Legacy has records of its own; the hacks edited into retail in place (the FireRed ones, Kaizo,
        // the randomized carts...) run its tables. The code CRCs decide.
        val hack = com.pokedaisy.app.companion.data.runningEmeraldHack != null
        return "$romCode/$romRev/${telemetry.snapshot.value.game}/${com.pokedaisy.app.companion.data.romGameCode}" to hack
    }

    private fun tmFns(): com.pokedaisy.app.companion.data.TmFns? {
        val (key, hack) = retailFieldKey() ?: return null
        val tms = com.pokedaisy.app.companion.data.tmCandidatesFor(telemetry.snapshot.value.game, romCode, romRev, hack)
        if (tms.isEmpty()) return null
        tmCache?.let { (k, v) -> if (k == key) return v }
        if (!::engine.isInitialized || !engine.running) return null
        val read = { addr: Long, len: Int -> com.pokedaisy.app.companion.data.InProcessReader.readCoreMemory(addr, len) }
        val found = runCatching { tms.firstOrNull { com.pokedaisy.app.companion.data.tmCodeMatches(it, read) } }.getOrElse { return null }
        Log.i("pokedaisy", "TMs: ${if (found != null) "code found" else "code not found"}")
        return found.also { tmCache = key to it }
    }

    private fun flashFns(): com.pokedaisy.app.companion.data.FlashFns? {
        val (key, hack) = retailFieldKey() ?: return null
        // Rocket Edition / Ash Gray: their own player sprites never finish the field move's pose, so FLASH's cut-in waits
        // forever with the controls locked (headless); their party menu's FLASH row is blank too.
        val hackText = com.pokedaisy.app.companion.data.runningFireRedHack
        if (hackText === com.pokedaisy.app.companion.data.fireRedHackRocketEdition || hackText === com.pokedaisy.app.companion.data.fireRedHackAshGray) return null
        val flashes = com.pokedaisy.app.companion.data.flashCandidatesFor(telemetry.snapshot.value.game, romCode, romRev, hack)
        if (flashes.isEmpty()) return null
        flashCache?.let { (k, v) -> if (k == key) return v }
        if (!::engine.isInitialized || !engine.running) return null
        val read = { addr: Long, len: Int -> com.pokedaisy.app.companion.data.InProcessReader.readCoreMemory(addr, len) }
        val found = runCatching {
            flashes.firstOrNull { com.pokedaisy.app.companion.data.flashCodeMatches(it, read) }
                ?.let { com.pokedaisy.app.companion.data.flashBadgeRule(it, read) }
        }.getOrElse { return null }
        Log.i("pokedaisy", "FLASH: ${when { found == null -> "code not found or badge rule unknown"; found.badgeFn != 0L -> "code found, the hack's badge rule"
            found.badgeFlag < 0 -> "code found, no badge"; else -> "code found" }}")
        return found.also { flashCache = key to it }
    }

    /** USE opens the game's TM screen for [itemId] here: the TM calls and the bag's (CheckBagHasItem, the party). */
    private fun tmSupport(itemId: Int): Boolean =
        tmFns()?.teaches(itemId) == true && fieldItemSupport()?.fns?.party != null && fieldItemSupport()?.fns?.bagActions == true

    /** A Game Boy game's PC / FLY / WILD ENCOUNTERS ([com.pokedaisy.app.companion.data.GbField]): English Yellow and
     * Crystal, their code checked in the cart once per ROM. */
    @Volatile private var gbFieldCache: Pair<String, com.pokedaisy.app.companion.data.GbField?>? = null

    private fun gbField(): com.pokedaisy.app.companion.data.GbField? {
        if (!romIsGameBoy) return null
        val game = telemetry.snapshot.value.game
        val field = com.pokedaisy.app.companion.data.GbField.forGame(game) ?: return null
        val key = "$game/${com.pokedaisy.app.companion.data.romLanguage}/${field.javaClass.simpleName}"
        gbFieldCache?.let { (k, v) -> if (k == key) return v }
        if (!::engine.isInitialized || !engine.running) return null
        val ok = runCatching { field.codeMatches { off, len -> MgbaCore.pkRomRead(off, len) } }.getOrElse { return null }
        Log.i("pokedaisy", "Game Boy field actions (${field.javaClass.simpleName}): ${if (ok) "code found" else "code not found"}")
        val v = field.takeIf { ok }
        gbFieldCache = key to v
        return v
    }

    /** Runs a Game Boy field action ([start], emu thread: true = the game has it, or has it queued) and, [GbField.TAKE_FRAMES]
     * later, [done] (main thread) with whether the game took it - a queued one it didn't take is withdrawn. */
    private fun gbFieldAction(
        field: com.pokedaisy.app.companion.data.GbField,
        start: (com.pokedaisy.app.companion.data.GameCalls) -> Boolean,
        refused: () -> Unit,
        done: (Boolean) -> Unit,
    ) {
        engine.requestFieldItemJob(telemetry.snapshot.value.inBattle) { calls, busy ->
            val started = !busy && runCatching { start(calls) }.getOrElse { Log.e("pokedaisy", "Game Boy field action failed", it); false }
            if (!started) { runOnUiThread(refused); return@requestFieldItemJob }
            engine.requestFieldItemJob(false, com.pokedaisy.app.companion.data.GbField.TAKE_FRAMES) { c, _ ->
                val took = runCatching { field.taken(c) }.getOrDefault(false)
                Log.i("pokedaisy", "Game Boy field action: ${if (took) "taken" else "not taken, withdrawn"}")
                runOnUiThread { done(took) }
            }
        }
    }

    /** A DS game whose Repel steps the reader knows (Platinum, HeartGold / SoulSilver, Black). */
    private fun dsRepelKnown(): Boolean {
        val game = telemetry.snapshot.value.game
        return romIsNds && (com.pokedaisy.app.companion.data.gen4ConfigFor(game)?.repelStepsOff
            ?: com.pokedaisy.app.companion.data.gen5ConfigFor(game)?.repelStepsOff) != null
    }

    private val itemUse = object : com.pokedaisy.app.companion.ItemUse {
        override val repelVar get() = fieldItemSupport()?.fns?.repelVar?.takeIf { it != 0 }   // 0: none mapped (Monomyth: PC only)
        override fun repelSteps(v: Int) = fieldItemSupport()?.fns?.repelSteps(v) ?: v
        /** A DS game's Repels ([com.pokedaisy.app.companion.data.useGen4Repel], Black's [com.pokedaisy.app.companion.data.useGen5Repel]);
         * nothing else yet. */
        private fun dsRepel(itemId: Int) = dsRepelKnown() && com.pokedaisy.app.companion.data.GEN4_REPEL_KINDS.any { it.first == itemId }
        override fun canUse(itemId: Int) = dsRepel(itemId) || tmSupport(itemId) || fieldItemSupport()?.canUse(itemId) == true
        override fun canGive(itemId: Int) = fieldItemSupport()?.canGive(itemId) == true
        override fun marked(itemId: Int) = dsRepel(itemId) || tmSupport(itemId) || fieldItemSupport()?.marked(itemId) == true
        override fun picksMon(itemId: Int) = fieldItemSupport()?.picksMon(itemId) == true
        override fun picksMove(itemId: Int) = fieldItemSupport()?.picksMove(itemId) == true

        /** [work] on the emu thread with the game's functions, [done] with its answer on the main thread;
         * [failed] when there's no game to ask (or the work throws). */
        private fun <T> onGame(failed: T, done: (T) -> Unit, work: (com.pokedaisy.app.companion.data.FieldItemSupport, com.pokedaisy.app.companion.data.GameCalls, Boolean) -> T) {
            val support = fieldItemSupport()
            if (support == null || !::engine.isInitialized || !engine.running) {
                done(failed)
                return
            }
            val inBattle = telemetry.snapshot.value.inBattle
            engine.requestFieldItemJob(inBattle) { calls, busy ->
                val out = runCatching { work(support, calls, busy) }.getOrElse { Log.e("pokedaisy", "item use failed", it); failed }
                Log.i("pokedaisy", "item use: $out")
                runOnUiThread { done(if (inBattle) inBattleInstead(out) else out) }
            }
        }

        /** In a battle, "not now" says why: a battle, not "close the menus and battles". */
        @Suppress("UNCHECKED_CAST")
        private fun <T> inBattleInstead(out: T): T = when {
            out is com.pokedaisy.app.companion.data.ItemUseOutcome && out.result == com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW ->
                out.copy(result = com.pokedaisy.app.companion.data.ItemUseResult.IN_BATTLE) as T
            out is com.pokedaisy.app.companion.data.PartyTargets && out.result == com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW ->
                out.copy(result = com.pokedaisy.app.companion.data.ItemUseResult.IN_BATTLE) as T
            else -> out
        }

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
                    runOnUiThread { done(if (inBattle) inBattleInstead(out) else out) }
                }
                return
            }
            if (tmSupport(itemId)) return openTmScreen(itemId, done)
            onGame(failed, done) { s, calls, busy -> com.pokedaisy.app.companion.data.useFieldItem(s.fns, itemId, busy, calls) }
        }

        override fun targets(itemId: Int, give: Boolean, done: (com.pokedaisy.app.companion.data.PartyTargets) -> Unit) =
            onGame(com.pokedaisy.app.companion.data.PartyTargets(com.pokedaisy.app.companion.data.ItemUseResult.FAILED), done) { s, calls, busy ->
                com.pokedaisy.app.companion.data.partyTargets(s, itemId, give, busy, calls)
            }

        /** A TM / HM: the game's own TM screen (TmTeach.kt), in two steps like FLY - the checks, the controls locked and
         * the fade to black, then once it's black the game's party menu, sent back to the field when it closes. */
        private fun openTmScreen(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {
            val tm = tmFns() ?: return done(failed)
            onGame(failed, { o ->
                // The teaching is the game's to show: a companion window over it goes.
                if (o.result == com.pokedaisy.app.companion.data.ItemUseResult.IN_GAME) closeCompanionWindow()
                done(o)
            }) { s, calls, busy ->
                val r = com.pokedaisy.app.companion.data.tmStart(s.fns, tm, itemId, busy, calls)
                if (r == com.pokedaisy.app.companion.data.ItemUseResult.USED) {
                    engine.requestFieldItemJob(false, com.pokedaisy.app.companion.data.TM_FADE_FRAMES) { c, leftField ->
                        val ok = com.pokedaisy.app.companion.data.tmFinish(tm, itemId, c, leftField)
                        Log.i("pokedaisy", "TM screen for $itemId: ${when {
                            ok -> "open"
                            leftField -> "the game left the field during the fade, not taken"
                            else -> "failed, field handed back"
                        }}")
                    }
                    com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.IN_GAME)
                } else com.pokedaisy.app.companion.data.ItemUseOutcome(r)
            }
        }

        override fun useOn(itemId: Int, target: com.pokedaisy.app.companion.data.PartyTarget, moveIndex: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) =
            onGame(failed, done) { s, calls, busy -> com.pokedaisy.app.companion.data.useOnMon(s, itemId, target, moveIndex, busy, calls) }

        override fun give(itemId: Int, target: com.pokedaisy.app.companion.data.PartyTarget, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) =
            onGame(failed, done) { s, calls, busy -> com.pokedaisy.app.companion.data.giveToMon(s, itemId, target, busy, calls) }

        override fun canTake() = fieldItemSupport()?.canTake == true

        override fun heldItems(done: (com.pokedaisy.app.companion.data.PartyTargets) -> Unit) =
            onGame(com.pokedaisy.app.companion.data.PartyTargets(com.pokedaisy.app.companion.data.ItemUseResult.FAILED), done) { s, calls, _ ->
                com.pokedaisy.app.companion.data.partyHeldItems(s, calls)
            }

        override fun take(target: com.pokedaisy.app.companion.data.PartyTarget, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) =
            onGame(failed, done) { s, calls, busy -> com.pokedaisy.app.companion.data.takeFromMon(s, target, busy, calls) }

        override fun canPc() = dsCalls() || fieldItemSupport()?.canPc == true || gbField() != null

        // The PC is worked with the game's buttons: an open companion window steps aside once it's up.
        override fun openPc(done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {
            com.pokedaisy.app.companion.FeatureTips.used(TipId.PC)
            val opened: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit = { o ->
                if (o.result == com.pokedaisy.app.companion.data.ItemUseResult.USED) closeCompanionWindow()
                done(o)
            }
            if (dsCalls()) return dsAction(MgbaCore.NDS_ACTION_PC, intArrayOf(0, 0, 0, 0), done = opened)
            val gb = gbField()
            if (gb != null && ::engine.isInitialized && engine.running) {
                val notNow = com.pokedaisy.app.companion.data.ItemUseOutcome(
                    if (telemetry.snapshot.value.inBattle) com.pokedaisy.app.companion.data.ItemUseResult.IN_BATTLE
                    else com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW,
                )
                var result = com.pokedaisy.app.companion.data.ItemUseResult.FAILED
                gbFieldAction(gb, { c -> result = gb.openPc(c); result == com.pokedaisy.app.companion.data.ItemUseResult.USED },
                    refused = { done(if (result == com.pokedaisy.app.companion.data.ItemUseResult.FAILED) failed else notNow) }) { took ->
                    if (took) closeCompanionWindow()
                    done(if (took) com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.USED) else notNow)
                }
                return
            }
            onGame(failed, opened) { s, calls, busy ->
                com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.openPc(s.fns, busy, calls))
            }
        }

        override fun canReorderParty() = !romIsNds && !romIsGameBoy && ::engine.isInitialized && engine.running &&
            telemetry.knownPartyReorderConfig() != null
        override fun reorderParty(a: Int, b: Int, done: (com.pokedaisy.app.companion.data.PartySwapResult) -> Unit) {
            val cfg = telemetry.knownPartyReorderConfig()
            val snap = telemetry.snapshot.value
            if (cfg == null || !::engine.isInitialized || !engine.running) return done(com.pokedaisy.app.companion.data.PartySwapResult.FAILED)
            if (snap.inBattle) return done(com.pokedaisy.app.companion.data.PartySwapResult.IN_BATTLE)
            engine.requestFieldItemJob(false) { calls, busy ->
                val r = if (busy || engine.fieldLocked) com.pokedaisy.app.companion.data.PartySwapResult.NOT_NOW
                else com.pokedaisy.app.companion.data.swapPartySlots(calls, cfg.playerParty, cfg.monStride, snap.party.size, a, b)
                runOnUiThread { done(r) }
            }
        }
        override fun canSwapBox() = com.pokedaisy.app.companion.data.boxSwapFor(fieldItemSupport()?.fns, com.pokedaisy.app.companion.data.activeNativeConfig) != null

        override fun swapBox(
            box: Int, slot: Int, boxPersonality: Long, partySlot: Int, partyPersonality: Long,
            done: (com.pokedaisy.app.companion.data.BoxSwapResult) -> Unit,
        ) = onBoxes(done) { fields, fns, calls, busy ->
            com.pokedaisy.app.companion.data.swapWithBox(fields, fns, box, slot, boxPersonality, partySlot, partyPersonality, busy, calls)
        }

        override fun depositToBox(
            partySlot: Int, partyPersonality: Long, box: Int,
            done: (com.pokedaisy.app.companion.data.BoxSwapResult) -> Unit,
        ) = onBoxes(done) { fields, fns, calls, busy ->
            com.pokedaisy.app.companion.data.depositToBox(fields, fns, partySlot, partyPersonality, box, busy, calls)
        }

        override fun withdrawFromBox(
            box: Int, slot: Int, boxPersonality: Long,
            done: (com.pokedaisy.app.companion.data.BoxSwapResult) -> Unit,
        ) = onBoxes(done) { fields, fns, calls, busy ->
            com.pokedaisy.app.companion.data.withdrawFromBox(fields, fns, box, slot, boxPersonality, busy, calls)
        }

        override fun canTradeEvolve() = Prefs(this@PokeDaisyActivity).tradeEvolutions && tradeEvoFns() != null

        override fun tradeEvolveTargets(done: (Map<Long, Int>) -> Unit) {
            val evo = tradeEvoFns()
            if (evo == null || !Prefs(this@PokeDaisyActivity).tradeEvolutions) return done(emptyMap())
            onGame(emptyMap(), done) { s, calls, _ -> com.pokedaisy.app.companion.data.tradeEvolveTargets(s.fns, evo, calls) }
        }

        override fun tradeEvolve(slot: Int, personality: Long, done: (com.pokedaisy.app.companion.data.TradeEvoResult) -> Unit) {
            val evo = tradeEvoFns()
            if (evo == null || !Prefs(this@PokeDaisyActivity).tradeEvolutions) return done(com.pokedaisy.app.companion.data.TradeEvoResult.FAILED)
            // Only from the field the player walks on: the scene takes the screen, then CB2_ReturnToField brings the field back.
            if (!::engine.isInitialized || !engine.onField.value) return done(com.pokedaisy.app.companion.data.TradeEvoResult.NOT_NOW)
            onGame(com.pokedaisy.app.companion.data.TradeEvoResult.FAILED, { r ->
                // The evolution is the game's to show: the window over it goes.
                if (r == com.pokedaisy.app.companion.data.TradeEvoResult.EVOLVING) closeCompanionWindow()
                done(r)
            }) { s, calls, busy -> com.pokedaisy.app.companion.data.tradeEvolve(s.fns, evo, slot, personality, busy, calls) }
        }

        /** A PC move ([move]) on the emu thread, its NOT_NOW told apart from a battle's. */
        private fun onBoxes(
            done: (com.pokedaisy.app.companion.data.BoxSwapResult) -> Unit,
            move: (com.pokedaisy.app.companion.data.FieldItemFns, com.pokedaisy.app.companion.data.BoxSwapFns,
                   com.pokedaisy.app.companion.data.GameCalls, Boolean) -> com.pokedaisy.app.companion.data.BoxSwapResult,
        ) {
            val inBattle = telemetry.snapshot.value.inBattle
            onGame(com.pokedaisy.app.companion.data.BoxSwapResult.FAILED, { r ->
                done(if (inBattle && r == com.pokedaisy.app.companion.data.BoxSwapResult.NOT_NOW) com.pokedaisy.app.companion.data.BoxSwapResult.IN_BATTLE else r)
            }) { s, calls, busy ->
                val fns = com.pokedaisy.app.companion.data.boxSwapFor(s.fns, com.pokedaisy.app.companion.data.activeNativeConfig) ?: return@onGame com.pokedaisy.app.companion.data.BoxSwapResult.FAILED
                move(s.fns, fns, calls, busy)
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
    private val quickActions = object : com.pokedaisy.app.companion.ui.QuickActions {
        override fun offered(action: com.pokedaisy.app.companion.QuickAction) = when (action) {
            com.pokedaisy.app.companion.QuickAction.PC -> itemUse.canPc()
            com.pokedaisy.app.companion.QuickAction.FLY -> flyAction.canFly()
            com.pokedaisy.app.companion.QuickAction.FLIGHT -> flightSupport() != null
            // Only while a party Pokémon knows it (the snapshot's moves): the tile isn't there otherwise.
            com.pokedaisy.app.companion.QuickAction.FLASH -> flashFns() != null && fieldItemSupport()?.fns?.party != null &&
                telemetry.snapshot.value.party.any { m -> !m.isEgg && m.moves.any { it.id == com.pokedaisy.app.companion.ui.FLASH_MOVE } }
            com.pokedaisy.app.companion.QuickAction.REPEL ->
                dsRepelKnown() ||
                    fieldItemSupport()?.let { s -> s.fns.repelItems.any { s.canUse(it) } } == true
            com.pokedaisy.app.companion.QuickAction.BIKE, com.pokedaisy.app.companion.QuickAction.ROD -> keyItemsWork()
            com.pokedaisy.app.companion.QuickAction.WILD -> companionSettings.wildEncountersSupported
            com.pokedaisy.app.companion.QuickAction.SAVE_STATE, com.pokedaisy.app.companion.QuickAction.LOAD_STATE,
            com.pokedaisy.app.companion.QuickAction.FF -> true
            // One screen held sideways: the companion's window or side panel (upright, it's always open).
            com.pokedaisy.app.companion.QuickAction.COMPANION -> hudSingleScreen.value && !hudUpright.value
        }

        override fun run(action: com.pokedaisy.app.companion.QuickAction) = runQuickAction(action)

        override fun flyTo(mapsec: Int, targets: com.pokedaisy.app.companion.data.FlyTargets) = quickMenu.onFly(mapsec, targets)

        override fun repel(itemId: Int) = quickMenu.onRepel(itemId)

        override fun riding() = ::engine.isInitialized && engine.riding

        override fun flying() = ::engine.isInitialized && engine.flightState != MgbaCore.FLIGHT_IDLE

        override fun wildEncounters() = Prefs(this@PokeDaisyActivity).wildEncounters

        override fun flashDark(): Boolean? {
            val flash = flashFns() ?: return null
            val progress = telemetry.snapshot.value.progress
            return com.pokedaisy.app.companion.data.flashDark(flash, { a, n -> com.pokedaisy.app.companion.data.InProcessReader.readCoreMemory(a, n) }) { f ->
                progress?.flag(f)
            }
        }

        override fun repelSteps() = telemetry.snapshot.value.repelSteps
            ?: itemUse.repelVar?.let { telemetry.snapshot.value.progress?.variable(it) }?.let(itemUse::repelSteps) ?: 0
    }

    private fun runQuickAction(action: com.pokedaisy.app.companion.QuickAction) {
        if (!::engine.isInitialized) return
        val snap = telemetry.snapshot.value
        when (action) {
            com.pokedaisy.app.companion.QuickAction.PC -> itemUse.openPc { o ->
                if (o.result != com.pokedaisy.app.companion.data.ItemUseResult.USED) showHud(com.pokedaisy.app.companion.ui.itemUseMessage(o))
            }
            com.pokedaisy.app.companion.QuickAction.FLY -> flyAction.targets { t ->
                if (t.result == com.pokedaisy.app.companion.data.FlyResult.OK) quickMenu.fly = t
                else {
                    quickMenu.close()
                    showHud(com.pokedaisy.app.companion.ui.flyMessage(t.result))
                }
            }
            com.pokedaisy.app.companion.QuickAction.FLASH -> useFlash()
            com.pokedaisy.app.companion.QuickAction.FLIGHT -> toggleFlight()
            // The menu picks the kind (QuickMenuState.onRepel): here only if asked straight, the weakest one held.
            com.pokedaisy.app.companion.QuickAction.REPEL ->
                com.pokedaisy.app.companion.ui.repelKindsIn(snap).firstOrNull()?.let { quickMenu.onRepel(it) }
            // OFF, or back to what it was before (NORMAL if it was never set).
            com.pokedaisy.app.companion.QuickAction.WILD -> {
                val prefs = Prefs(this)
                val now = prefs.wildEncounters
                val next = if (now != 0) { prefs.wildEncountersBeforeOff = now; 0 } else prefs.wildEncountersBeforeOff.takeIf { it > 0 } ?: 100
                companionSettings.setWildEncounters(next)
                showHud(com.pokedaisy.app.companion.i18n.tr("WILD ENCOUNTERS") + ": " +
                    com.pokedaisy.app.companion.i18n.tr(com.pokedaisy.app.companion.ui.wildEncountersLabel(next)))
            }
            com.pokedaisy.app.companion.QuickAction.BIKE ->
                com.pokedaisy.app.companion.data.KeyItems.firstIn(snap.items, com.pokedaisy.app.companion.data.KeyItems.BIKES)?.let(::useKeyItem)
            com.pokedaisy.app.companion.QuickAction.ROD ->
                com.pokedaisy.app.companion.data.KeyItems.firstIn(snap.items, com.pokedaisy.app.companion.data.KeyItems.RODS)?.let(::useKeyItem)
            com.pokedaisy.app.companion.QuickAction.SAVE_STATE -> if (!coopRefuses()) engine.requestSaveState(engine.currentSlot)
            com.pokedaisy.app.companion.QuickAction.LOAD_STATE -> if (!coopRefuses()) engine.requestLoadState(engine.currentSlot)
            com.pokedaisy.app.companion.QuickAction.FF -> engine.toggleFastForward()
            com.pokedaisy.app.companion.QuickAction.COMPANION -> if (::sidePanel.isInitialized) sidePanel.openPanel()
        }
    }

    /** FREE FLIGHT here (Flight.kt): the game's tables (FieldItemFns.flight, retail FireRed rev 1 so far) with FLY's and the
     * flight's code where expected. */
    private fun flightSupport(): com.pokedaisy.app.companion.data.FieldItemSupport? =
        fieldItemSupport()?.takeIf {
            it.canFlight && it.fns.flight != null && !romIsGameBoy && !romIsNds &&
                com.pokedaisy.app.companion.data.flightAllowed(telemetry.snapshot.value.game, telemetry.retailRom(), it.fns.flight)
        }

    /** Keeps pk_flight configured for the running game, every half second (the same words again change nothing): a
     * resume state or save state taken in the air comes back flying, so the flight must know the game from the start. */
    private fun syncFlight() {
        if (!::engine.isInitialized || !engine.running) return
        askFlyStyle()
        val support = flightSupport() ?: return
        val words = support.fns.flight?.words(flightVehicle()) ?: return
        engine.postToEmu { MgbaCore.pkFlightConfigure(words) }
        syncFlightArea(support)
    }

    /** FLIGHT AREA for this game (FlightArea.kt): the save's seen maps, made in [loadRom]. */
    private var flightArea: com.pokedaisy.app.companion.data.FlightArea.Keeper? = null
    /** pk_flight was told "anywhere" since FLIGHT AREA went ANYWHERE. */
    private var flightAreaAnywhereSent = false
    /** A keeper tick is queued on the emu thread (one at a time). */
    @Volatile private var flightAreaQueued = false

    private fun flightAreaSeen() = Prefs(this).flightArea == com.pokedaisy.app.companion.FlightAreaMode.SEEN

    /** Every half second with [syncFlight]: pk_flight told the seen maps (the save checked, the player's map added), or
     * "anywhere" once while FLIGHT AREA says so. */
    private fun syncFlightArea(support: com.pokedaisy.app.companion.data.FieldItemSupport) {
        val keeper = flightArea ?: return
        if (!flightAreaSeen()) {
            if (!flightAreaAnywhereSent) {
                flightAreaAnywhereSent = true
                engine.postToEmu { keeper.resend(); MgbaCore.pkFlightArea(null) }
            }
            return
        }
        flightAreaAnywhereSent = false
        if (flightAreaQueued) return
        flightAreaQueued = true
        val standing = engine.onField.value && !telemetry.snapshot.value.inBattle
        engine.requestFieldItemJob(false) { calls, busy ->
            flightAreaQueued = false
            flightAreaTick(keeper, support, calls, standing && !busy)
            // The bird turned at a map not seen yet: say why, now and then (it can be met many times a second).
            if (MgbaCore.pkFlightAreaTurned()) runOnUiThread {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - flightAreaToldAt > FLIGHT_AREA_TELL_MS) {
                    flightAreaToldAt = now
                    showHud(com.pokedaisy.app.companion.i18n.tr("You haven't been there yet."))
                }
            }
        }
    }

    /** When the HUD last said the bird turned at FLIGHT AREA's edge (uptime ms). */
    private var flightAreaToldAt = 0L

    /** Emu thread, in a field job: [FlightArea.Keeper.tick], its bits to pk_flight when they changed. FLIGHT AREA's
     * ANYWHERE: pk_flight told so. */
    private fun flightAreaTick(
        keeper: com.pokedaisy.app.companion.data.FlightArea.Keeper, support: com.pokedaisy.app.companion.data.FieldItemSupport,
        calls: com.pokedaisy.app.companion.data.GameCalls, standing: Boolean,
    ) {
        if (!flightAreaSeen()) {
            keeper.resend()
            MgbaCore.pkFlightArea(null)
            return
        }
        // Quetzal's FLY flag check is its own region-badge one, not FlagGet: no trainers there.
        val trainerFlags = telemetry.snapshot.value.game != com.pokedaisy.app.companion.data.GameKind.QUETZAL
        runCatching { keeper.tick(support.fns, calls, trainerFlags, standing && MgbaCore.pkFlightState() == MgbaCore.FLIGHT_IDLE) }
            .onSuccess { bits -> bits?.let { MgbaCore.pkFlightArea(it) } }
            .onFailure { Log.w("pokedaisy", "flight area", it) }
    }

    /** A take-off went up: with FLIGHT AREA's ANYWHERE, RetroAchievements pause (UI thread). */
    private fun flightTookOff() {
        if (!flightAreaSeen()) RetroAchievements.setFlightAnywhere(true)
    }

    /** MAKEOVER > FLY ON: what the flight's words ask pk_flight to draw. */
    private fun flightVehicle() = com.pokedaisy.app.companion.data.FlightVehicle.of(Prefs(this).flightVehicleId)

    /** A scenic FLY took off (UI thread): once it has landed, [askFlyStyle] may ask KEEP IT / USE CLASSIC. */
    private var scenicFlown = false

    /** When the landed flight's question goes up (uptime ms), 0 = not waiting. */
    private var flyStyleAskAt = 0L

    /** The first scenic FLY's landing asks whether to keep it (FlyStyleAsk), once ever, a moment after the bird is gone. */
    private fun askFlyStyle() {
        val now = android.os.SystemClock.uptimeMillis()
        if (scenicFlown && !engine.scenicFlight && engine.flightState == MgbaCore.FLIGHT_IDLE) {
            scenicFlown = false
            if (!Prefs(this).flyStyleAsked) flyStyleAskAt = now + FLY_STYLE_ASK_DELAY_MS
        }
        if (flyStyleAskAt != 0L && now >= flyStyleAskAt && engine.onField.value && !engine.fieldLocked) {
            flyStyleAskAt = 0L
            com.pokedaisy.app.companion.ui.FlyStyleAsk.showing = true
        }
    }

    /** FlyStyleAsk's answer: USE CLASSIC switches FLY STYLE; KEEP IT or a bar that ran out keeps SCENIC. Asked either way. */
    private fun answerFlyStyle(style: com.pokedaisy.app.companion.FlyStyle?) {
        val prefs = Prefs(this)
        if (style == com.pokedaisy.app.companion.FlyStyle.CLASSIC) prefs.flyStyleId = style.id else prefs.flyStyleAsked = true
    }

    /** On a bike: off it first (the game's own way, SELECT with the bike: KeyItems), then [then] once the game has the
     * player on foot - a flight takes off from the ground, not from the saddle. At most [OFF_BIKE_WAIT_MS], then [then]
     * all the same (the game said no: a bike lane, a slope). */
    private fun offBikeThen(then: () -> Unit) {
        if (!engine.riding) return then()
        val bike = com.pokedaisy.app.companion.data.KeyItems.firstIn(telemetry.snapshot.value.items, com.pokedaisy.app.companion.data.KeyItems.BIKES)
            ?: return then()
        useKeyItem(bike)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val start = android.os.SystemClock.uptimeMillis()
        var offAt = 0L
        val check = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.uptimeMillis()
                if (!engine.riding && offAt == 0L) offAt = now
                when {
                    offAt != 0L && now - offAt >= OFF_BIKE_SETTLE_MS -> then()   // off, and the game done with it
                    now - start >= OFF_BIKE_WAIT_MS -> then()
                    else -> handler.postDelayed(this, 50)
                }
            }
        }
        handler.postDelayed(check, 50)
    }

    /** TAKE OFF, or LAND while up (the QUICK MENU, the MAP tab, the FREE FLIGHT hotkey). */
    fun toggleFlight() {
        if (!::engine.isInitialized || !engine.running) return
        if (engine.flightState != MgbaCore.FLIGHT_IDLE) {
            engine.postToEmu { MgbaCore.pkFlightLand() }
            return
        }
        if (engine.riding) return offBikeThen { if (!engine.riding) toggleFlight() }
        val support = flightSupport() ?: return showHud(com.pokedaisy.app.companion.ui.flyMessage(com.pokedaisy.app.companion.data.FlyResult.FAILED))
        val words = support.fns.flight!!.words(flightVehicle())
        val inBattle = telemetry.snapshot.value.inBattle
        engine.requestFieldItemJob(inBattle) { calls, busy ->
            val gate = com.pokedaisy.app.companion.data.flightCheck(support.fns, busy, calls)
            var code = MgbaCore.FLIGHT_OK
            if (gate == com.pokedaisy.app.companion.data.FlyResult.OK) {
                MgbaCore.pkFlightConfigure(words)
                flightArea?.let { flightAreaTick(it, support, calls, true) }
                code = MgbaCore.pkFlightTakeOff()
            }
            Log.i("pokedaisy", "free flight: $gate / $code")
            runOnUiThread {
                when {
                    gate == com.pokedaisy.app.companion.data.FlyResult.OK && code == MgbaCore.FLIGHT_OK -> {
                        flightTookOff()
                        com.pokedaisy.app.companion.FeatureTips.used(TipId.FLY)
                        closeCompanionWindow()
                        hudCloseTick.intValue++
                    }
                    gate != com.pokedaisy.app.companion.data.FlyResult.OK -> showHud(com.pokedaisy.app.companion.ui.flyMessage(
                        if (inBattle && gate == com.pokedaisy.app.companion.data.FlyResult.NOT_NOW) com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE else gate,
                    ))
                    code == MgbaCore.FLIGHT_NOT_ON_FOOT -> showHud(com.pokedaisy.app.companion.i18n.tr("You can only take off from land."))
                    else -> showHud(com.pokedaisy.app.companion.ui.flyMessage(com.pokedaisy.app.companion.data.flightResult(code)))
                }
            }
        }
    }

    /**
     * The scenic FLY (FLY STYLE > SCENIC): up on the bird, then over the field to the town's landing spot along a route through
     * the maps the game joins (ScenicFly.kt), EXPAND zoomed out and the game sped up while it's in the air (EmulatorEngine.scenicFlight).
     * A town the player's map isn't joined to (Saffron's gatehouses, another island) gets the classic FLY ([classic]).
     */
    private fun scenicFly(
        support: com.pokedaisy.app.companion.data.FieldItemSupport, mapsec: Int,
        done: (com.pokedaisy.app.companion.data.FlyResult) -> Unit, classic: () -> Unit,
    ) {
        // Off the bike first; still on it (the game kept them on), the game's own FLY, which handles a bike itself.
        if (engine.riding) return offBikeThen { if (engine.riding) classic() else scenicFly(support, mapsec, done, classic) }
        val fns = support.fns
        val f = fns.flight!!
        val fly = fns.fly!!
        val words = f.words(flightVehicle())
        val inBattle = telemetry.snapshot.value.inBattle
        engine.requestFieldItemJob(inBattle) { calls, busy ->
            val gate = com.pokedaisy.app.companion.data.flightCheck(fns, busy, calls)
            if (gate != com.pokedaisy.app.companion.data.FlyResult.OK) {
                val shown = if (inBattle && gate == com.pokedaisy.app.companion.data.FlyResult.NOT_NOW) com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE else gate
                runOnUiThread { done(shown) }
                return@requestFieldItemJob
            }
            // FLIGHT AREA: the route keeps to the seen maps (a town past them flies the classic way).
            val keeper = flightArea
            keeper?.let { flightAreaTick(it, support, calls, true) }
            val allowed = keeper?.takeIf { flightAreaSeen() }?.let { k -> { m: com.pokedaisy.app.companion.data.ScenicFly.MapId -> k.allows(m) } }
            val world = runCatching {
                val here = com.pokedaisy.app.companion.data.ScenicFly.here(f, calls::read) ?: return@runCatching null
                val dest = com.pokedaisy.app.companion.data.ScenicFly.destination(fly, f, mapsec, calls::read) ?: return@runCatching null
                val w = com.pokedaisy.app.companion.data.ScenicFly.read(f, here.first, calls::read, allowed) ?: return@runCatching null
                Triple(w, here, dest).takeIf { w.placed(dest.first) != null }
            }.getOrNull()
            if (world == null) {
                Log.i("pokedaisy", "scenic fly to $mapsec: not joined to here, classic")
                runOnUiThread(classic)
                return@requestFieldItemJob
            }
            // The path is plain arithmetic: off the emu thread.
            Thread({
                val (w, here, dest) = world
                val route = runCatching { w.route(here.first, here.second, here.third, dest.first, dest.second, dest.third) }.getOrNull()
                    ?.takeIf { it.size < com.pokedaisy.app.companion.data.ScenicFly.MAX_STEPS }
                if (route == null) {
                    Log.i("pokedaisy", "scenic fly to $mapsec: no route, classic")
                    runOnUiThread(classic)
                    return@Thread
                }
                engine.requestFieldItemJob(false) { c, busy2 ->
                    val again = com.pokedaisy.app.companion.data.flightCheck(fns, busy2, c)
                    var code = MgbaCore.FLIGHT_NOT_NOW
                    if (again == com.pokedaisy.app.companion.data.FlyResult.OK) {
                        MgbaCore.pkFlightConfigure(words)
                        code = MgbaCore.pkFlightTakeOff()
                        if (code == MgbaCore.FLIGHT_OK) {
                            MgbaCore.pkFlightRoute(route)
                            engine.scenicFlight = true
                        }
                    }
                    Log.i("pokedaisy", "scenic fly to $mapsec: ${route.size} steps, $again / $code")
                    runOnUiThread {
                        when {
                            code == MgbaCore.FLIGHT_OK -> {
                                scenicFlown = true
                                flightTookOff()
                                done(com.pokedaisy.app.companion.data.FlyResult.OK)
                            }
                            // Surfing: the bird can't take off from the water - the game's own FLY can.
                            code == MgbaCore.FLIGHT_NOT_ON_FOOT -> classic()
                            again != com.pokedaisy.app.companion.data.FlyResult.OK -> done(again)
                            else -> done(com.pokedaisy.app.companion.data.flightResult(code))
                        }
                    }
                }
            }, "pokedaisy-scenic-fly").start()
        }
    }

    /** FLASH (Flash.kt): the party menu's checks and the field move on the emu thread; the game does the rest (the cut-in, the
     * light). Anything that kept it from starting says why on the HUD. */
    private fun useFlash() {
        val flash = flashFns()
        val fns = fieldItemSupport()?.fns
        if (flash == null || fns == null || !::engine.isInitialized || !engine.running) {
            return showHud(com.pokedaisy.app.companion.ui.flashMessage(com.pokedaisy.app.companion.data.FlashResult.FAILED))
        }
        val inBattle = telemetry.snapshot.value.inBattle
        engine.requestFieldItemJob(inBattle) { calls, busy ->
            val r = com.pokedaisy.app.companion.data.useFlash(fns, flash, busy, calls).let {
                if (inBattle && it == com.pokedaisy.app.companion.data.FlashResult.NOT_NOW) com.pokedaisy.app.companion.data.FlashResult.IN_BATTLE else it
            }
            Log.i("pokedaisy", "flash: $r")
            runOnUiThread {
                // The field move is the game's to show: a companion window over it goes.
                if (r == com.pokedaisy.app.companion.data.FlashResult.OK) closeCompanionWindow()
                else showHud(com.pokedaisy.app.companion.ui.flashMessage(r))
            }
        }
    }

    /** BIKE / ROD work here: a retail FireRed / Emerald-family game (ROM code the field items recognise) whose
     * SaveBlock1 has the SELECT register where KeyItems expects it. */
    private fun keyItemsWork(): Boolean {
        val game = telemetry.snapshot.value.game
        // SoulGold: its SELECT wheel (KeyItems.selectWheel), checked headless (GitHub #242).
        if (game == com.pokedaisy.app.companion.data.GameKind.SOULGOLD) return fieldItemSupport() != null
        if (game != com.pokedaisy.app.companion.data.GameKind.FIRERED && game != com.pokedaisy.app.companion.data.GameKind.EMERALD) return false
        val cfg = com.pokedaisy.app.companion.data.activeNativeConfig ?: return false
        return (cfg.moneyOff == 0x290L || cfg.moneyOff == 0x490L) && fieldItemSupport() != null
    }

    /**
     * A bike or a rod, used the way SELECT uses a registered key item (KeyItems): written into the SELECT register,
     * SELECT pressed for the game for 2 frames ([GbaInput.setPress]: the battle controls own setScript's channel), the player's own registered item
     * put back 20 frames later. The game does the rest: mounting, the fishing cast, or its own "can't use that here".
     */
    private fun useKeyItem(itemId: Int) {
        val cfg = com.pokedaisy.app.companion.data.activeNativeConfig ?: return
        val fns = fieldItemSupport()?.fns ?: return
        if (!::engine.isInitialized || !engine.running) return
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun u16(b: ByteArray) = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        engine.requestFieldItemJob(false) { calls, busy ->
            val ok = runCatching {
                if (!com.pokedaisy.app.companion.data.fieldItemCodeMatches(fns, calls::read)) return@runCatching false
                val addr = com.pokedaisy.app.companion.data.KeyItems.registerAddr(cfg, calls::read) ?: return@runCatching false
                if (busy || calls.call(fns.fieldControlsLocked) != 0L) return@runCatching false
                val old = u16(calls.read(addr, 2))
                // Not a sane item id: not the register after all.
                if (old > 0x3FF || !calls.write(addr, le16(itemId))) return@runCatching false
                if (com.pokedaisy.app.companion.data.KeyItems.selectWheel) {
                    // SoulGold: SELECT opens its wheel, DOWN uses the slot written above; the player's own put back after.
                    val down = com.pokedaisy.app.companion.data.KeyItems.WHEEL_DOWN_AFTER
                    input.setPress(MgbaCore.Key.SELECT)
                    engine.requestFieldItemJob(false, delayFrames = 2) { _, _ -> input.setPress(0) }
                    engine.requestFieldItemJob(false, delayFrames = down) { _, _ -> input.setPress(MgbaCore.Key.DOWN) }
                    engine.requestFieldItemJob(false, delayFrames = down + 2) { _, _ -> input.setPress(0) }
                    engine.requestFieldItemJob(false, delayFrames = down + 20) { c, _ -> if (u16(c.read(addr, 2)) == itemId) c.write(addr, le16(old)) }
                    return@runCatching true
                }
                pressSelect(fns.fieldControlsLocked, SELECT_TRIES) {
                    engine.requestFieldItemJob(false, delayFrames = 20) { c, _ -> if (u16(c.read(addr, 2)) == itemId) c.write(addr, le16(old)) }
                }
                true
            }.getOrDefault(false)
            if (!ok) runOnUiThread { showHud(com.pokedaisy.app.companion.ui.itemUseMessage(com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW))) }
        }
    }

    /**
     * SELECT for the game for 2 frames; pressed again if it was dropped (a bike rolling at full speed ignores SELECT), at most
     * [tries] times, but only where taking it shows for sure: the riding state flips (BIKE, [EmulatorEngine.riding] - read on
     * this game) or the field controls stay locked (ROD's cast and its messages). The lock alone isn't enough: getting on or
     * off locks and frees them within one frame, which a check between frames misses (and a blind retry toggled the bike 20
     * times). [done] once it took, or gave up.
     */
    private fun pressSelect(locked: Long, tries: Int, done: () -> Unit) {
        val riding = engine.riding
        val sure = engine.fieldMovesKnown
        var took = false
        input.setPress(MgbaCore.Key.SELECT)
        for (f in 1..SELECT_WAIT) engine.requestFieldItemJob(false, delayFrames = f) { c, _ ->
            if (engine.riding != riding || c.call(locked) != 0L) took = true
        }
        engine.requestFieldItemJob(false, delayFrames = 2) { _, _ -> input.setPress(0) }
        engine.requestFieldItemJob(false, delayFrames = SELECT_WAIT + 1) { _, _ ->
            if (took || !sure || tries <= 1) done() else pressSelect(locked, tries - 1, done)
        }
    }

    /** FLY on the MAP tab (Fly.kt): two steps on the emu thread - the checks, controls locked and the fade to black,
     * then once it's black the game's own Fly map choice. */
    private val flyAction = object : com.pokedaisy.app.companion.FlyAction {
        override fun canFly() = dsFly() || gen5Fly() || fieldItemSupport()?.canFly == true || gbField() != null

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
            val gb = gbField()
            if (gb != null && ::engine.isInitialized && engine.running) {
                val inBattle = telemetry.snapshot.value.inBattle
                engine.requestFieldItemJob(inBattle) { calls, busy ->
                    val out = when {
                        busy -> com.pokedaisy.app.companion.data.FlyTargets(
                            if (inBattle) com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE else com.pokedaisy.app.companion.data.FlyResult.NOT_NOW,
                        )
                        else -> runCatching { gb.flyTargets(calls) }
                            .getOrElse { com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.FAILED) }
                    }
                    Log.i("pokedaisy", "fly targets: $out")
                    runOnUiThread { done(out) }
                }
                return
            }
            val support = fieldItemSupport()
            if (support == null || !support.canFly || !::engine.isInitialized || !engine.running) {
                done(com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.FAILED))
                return
            }
            val inBattle = telemetry.snapshot.value.inBattle
            engine.requestFieldItemJob(inBattle) { calls, busy ->
                val out = runCatching { com.pokedaisy.app.companion.data.flyTargets(support.fns, busy, calls) }
                    .getOrElse { com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.FAILED) }
                Log.i("pokedaisy", "fly targets: $out")
                val shown = if (inBattle && out.result == com.pokedaisy.app.companion.data.FlyResult.NOT_NOW) {
                    out.copy(result = com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE)
                } else out
                runOnUiThread { done(shown) }
            }
        }

        override fun fly(mapsec: Int, targets: com.pokedaisy.app.companion.data.FlyTargets, done: (com.pokedaisy.app.companion.data.FlyResult) -> Unit) {
            com.pokedaisy.app.companion.FeatureTips.used(TipId.FLY)
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
                    done(when (o.result) {
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
                    done(when (o.result) {
                        com.pokedaisy.app.companion.data.ItemUseResult.USED -> com.pokedaisy.app.companion.data.FlyResult.OK
                        com.pokedaisy.app.companion.data.ItemUseResult.IN_BATTLE -> com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE
                        com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW -> com.pokedaisy.app.companion.data.FlyResult.NOT_NOW
                        else -> com.pokedaisy.app.companion.data.FlyResult.FAILED
                    })
                }
                return
            }
            val gb = gbField()
            if (gb != null && ::engine.isInitialized && engine.running) {
                var result = com.pokedaisy.app.companion.data.FlyResult.NOT_NOW
                gbFieldAction(gb, { c -> result = gb.fly(mapsec, targets, c); result == com.pokedaisy.app.companion.data.FlyResult.OK },
                    refused = { done(result) }) { took ->
                    Log.i("pokedaisy", "fly to $mapsec: ${if (took) "flying" else "not taken"}")
                    // The flight is the game's to show: the window over it goes.
                    if (took) closeCompanionWindow()
                    done(if (took) com.pokedaisy.app.companion.data.FlyResult.OK else com.pokedaisy.app.companion.data.FlyResult.NOT_NOW)
                }
                return
            }
            val support = fieldItemSupport()
            if (support == null || !support.canFly || !::engine.isInitialized || !engine.running) {
                done(com.pokedaisy.app.companion.data.FlyResult.FAILED)
                return
            }
            // The flight is the game's to show: the window over it goes, and the HUD's panel it was picked from (its map).
            closeCompanionWindow()
            hudCloseTick.intValue++
            // A game without the classic FLY (Kaizo: its warp crashed from the field) always goes over the map.
            if (!support.fns.classicFly) {
                if (flightSupport() == null) return done(com.pokedaisy.app.companion.data.FlyResult.FAILED)
                scenicFly(support, mapsec, done) { done(com.pokedaisy.app.companion.data.FlyResult.NO_ROUTE) }
                return
            }
            if (Prefs(this@PokeDaisyActivity).flyStyle == com.pokedaisy.app.companion.FlyStyle.SCENIC && flightSupport() != null) {
                scenicFly(support, mapsec, done) { classicFly(support, mapsec, targets, done) }
                return
            }
            classicFly(support, mapsec, targets, done)
        }

        /** The game's own FLY: its cut-in, flight and landing (Fly.kt's two steps). */
        private fun classicFly(
            support: com.pokedaisy.app.companion.data.FieldItemSupport, mapsec: Int, targets: com.pokedaisy.app.companion.data.FlyTargets,
            done: (com.pokedaisy.app.companion.data.FlyResult) -> Unit,
        ) {
            val inBattle = telemetry.snapshot.value.inBattle
            engine.requestFieldItemJob(inBattle) { calls, busy ->
                val started = runCatching { com.pokedaisy.app.companion.data.flyStart(support.fns, mapsec, targets, busy, calls) }
                    .getOrElse { com.pokedaisy.app.companion.data.FlyResult.FAILED }
                Log.i("pokedaisy", "fly to $mapsec: $started")
                if (started == com.pokedaisy.app.companion.data.FlyResult.OK) {
                    engine.requestFieldItemJob(false, com.pokedaisy.app.companion.data.FLY_FADE_FRAMES) { c, leftField ->
                        val ok = com.pokedaisy.app.companion.data.flyFinish(support.fns, mapsec, targets, c, leftField)
                        Log.i("pokedaisy", "fly to $mapsec: ${when {
                            ok -> "flying"
                            leftField -> "the game left the field during the fade, not taken"
                            else -> "failed, field handed back"
                        }}")
                    }
                }
                val shown = if (inBattle && started == com.pokedaisy.app.companion.data.FlyResult.NOT_NOW) {
                    com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE
                } else started
                runOnUiThread { done(shown) }
            }
        }
    }

    /** The loaded ROM is a Game Boy / Color cart (no GBA header, no L/R). */
    @Volatile private var romIsGameBoy = false

    /** FOLLOWER: the party lead's national number (null: none / unknown). */
    @Volatile private var followerLead: Int? = null

    /** FOLLOWER works here: a GBA game (not a Game Boy or DS one) that has no following Pokémon of its own. */
    private fun followerSupported() = !romIsGameBoy && !romIsNds && ::engine.isInitialized && fieldGame != WideScreen.OFF &&
        com.pokedaisy.app.companion.data.activeGame !in FOLLOWERS_OF_THEIR_OWN

    /** The engine's follower: the lead's sprite while the row is on. */
    private fun applyFollower() {
        if (!::engine.isInitialized) return
        val on = Prefs(this).follower && followerSupported()
        val n = followerLead
        engine.follower = if (on && n != null) engine.follower?.takeIf { it.national == n }
            ?: com.pokedaisy.app.companion.data.MonArt.load(filesDir, n) else null
    }

    /** DAY AND NIGHT works here: a GBA game (not a Game Boy or DS one) that doesn't change with the time itself. */
    private fun dayNightSupported() = !romIsGameBoy && !romIsNds && romBlocked == null && ::engine.isInitialized &&
        com.pokedaisy.app.companion.data.activeGame !in com.pokedaisy.app.companion.data.DayNight.OWN_DAY_NIGHT
    /** The game running is a DS cart (melonDS): two screens, touch, X / Y ([loadRom]). */
    @Volatile private var romIsNds = false
    /** A DS game on DSperate (DS CORE, companion.DsCore): no EXPAND, DS RESOLUTION, PC / FLY calls or DS CPU BOOST there.
     * Picked as the game loads and again on RESTART GAME (pickDsCore); the core keeps it until then. */
    @Volatile private var dsperate = false
    /** The DS game's generated world (its wild rates for WILD ENCOUNTERS), or null. */
    /** The cart's own encounter rates, by map, for WILD ENCOUNTERS back on (MgbaCore.pkNdsWildRates). */
    @Volatile private var dsWildRates: (() -> IntArray)? = null

    /** The loaded ROM's header code / revision byte, for [switchAddrsFor]. */
    @Volatile private var romCode = ""
    @Volatile private var romRev = -1
    /** The loaded cart is Origin HeartGold's ([com.pokedaisy.app.companion.data.OriginHeartGold]: a Japanese header). */
    @Volatile private var romOrigin = false

    /**
     * Where the battle POKéMON pane's switch finds the party menu: retail
     * FireRed rev 1 / Emerald (gPartyMenu, gPlayerParty from the decomp ELFs,
     * checked against each ROM's literal pools), the language ports and the hacks below. Keyed on the detected game too, so LeafGreen,
     * FireRed rev 0, Ruby/Sapphire and hacks that share a header code (Seaglass)
     * stay out.
     */
    private fun switchAddrsFor(game: com.pokedaisy.app.companion.data.GameKind?): BattleInputController.SwitchAddrs? = when {
        // Yellow and the Gen 2 games: their own party menus, read through GbPartySwitch (no gPartyMenu, no personality).
        game.isGen1 || game.isGen2 ->
            telemetry.gbPartySwitch()?.let { BattleInputController.SwitchAddrs(gb = it) }
        // A hack nothing knows by its hash whose code proves it kept retail's engine (RetailEngine): retail's gPartyMenu and
        // list, read through BEST EFFORT as retail FireRed / Emerald.
        telemetry.retailEnginePartyMenu() != null ->
            telemetry.retailEnginePartyMenu()?.let { (menu, party) -> BattleInputController.SwitchAddrs(partyMenu = menu, party = party) }
        game == com.pokedaisy.app.companion.data.GameKind.FIRERED && romCode == "BPRE" && romRev == 1 ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L)
        // Recharged Emerald runs as Emerald (BPEE) but is a rebuild with its own RAM: its config carries gPartyMenu (Emerald's
        // list; headless on all three builds: a written POKéMON cell, DOWN, A, SHIFT sent party slot 1 in).
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD && com.pokedaisy.app.companion.data.runningEmeraldHack != null ->
            telemetry.knownPartyMenu()?.let { (menu, party) -> BattleInputController.SwitchAddrs(partyMenu = menu, party = party) }
        // Japanese Emerald: its own RAM (NATIVE_EMERALD_JA).
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD && romCode == "BPEJ" ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203CB94L, party = 0x02024190L)
        // Emerald Legacy keeps retail's header but is its own build: gPartyMenu from its config (NATIVE_EMERALD_LEGACY),
        // retail's one-column list. Before retail's BPEE line, which would hand it retail's address.
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD && com.pokedaisy.app.companion.data.emeraldLegacy ->
            telemetry.knownPartyMenu()?.let { (menu, party) -> BattleInputController.SwitchAddrs(partyMenu = menu, party = party) }
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD &&
            (romCode == "BPEE" || romCode in com.pokedaisy.app.companion.data.EMERALD_EUROPEAN_CODES) ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203CEC8L, party = 0x020244ECL)
        // Orange Islands is retail FireRed rev 0's RAM and party menu, edited in place.
        game == com.pokedaisy.app.companion.data.GameKind.ORANGE_ISLANDS ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L)
        // Let's Go Pikachu / Eevee: rev 0's gPartyMenu under a two-column grid (headless in its battle: DOWN 0 -> 2 -> 4,
        // RIGHT 0 -> 1; A, SHIFT on slot 3 sent it in).
        game == com.pokedaisy.app.companion.data.GameKind.FIRERED && com.pokedaisy.app.companion.data.runningLetsGo ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L, grid = true)
        // So are Ultra Violet, Rocket Edition, Ash Gray and Ghost Grey (FireRed 1.0 edited in place, ActiveTables' fireRedHack;
        // their fixtures' READMEs: a written POKéMON cell + DOWN, A, SHIFT sent party slot 1 in).
        game == com.pokedaisy.app.companion.data.GameKind.FIRERED && com.pokedaisy.app.companion.data.runningFireRedHack != null ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L)
        game == com.pokedaisy.app.companion.data.GameKind.TMT2 ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203242CL, party = 0x02032C94L)
        // Elite Redux (both builds): gPartyMenu from its config, a two-column grid (DOWN 0 -> 2, RIGHT 2 -> 3), its packed
        // 0x4C-byte party mons (the personality first, as the switch reads it). Headless: A, SHIFT on slot 2 put it out.
        game == com.pokedaisy.app.companion.data.GameKind.ELITE_REDUX ->
            telemetry.knownPartyMenu()?.let { (menu, party) ->
                BattleInputController.SwitchAddrs(partyMenu = menu, party = party, monStride = 0x4C, grid = true)
            }
        // Team Rocket Edition 2026: Emerald's layout at its own gPartyMenu, from its config (NATIVE_TEAM_ROCKET_V3: the battle's
        // POKéMON menu walked slotId 0 -> 7, CANCEL, on DOWN with one Pokémon). Before v1.5's line, which has v1.5's addresses.
        game == com.pokedaisy.app.companion.data.GameKind.TEAM_ROCKET && com.pokedaisy.app.companion.data.teamRocketV3 ->
            telemetry.knownPartyMenu()?.let { (menu, party) -> BattleInputController.SwitchAddrs(partyMenu = menu, party = party) }
        // Team Rocket Edition: Dragonsden: Emerald's one-column list at its own gPartyMenu (NATIVE_TEAM_ROCKET). Headless: a
        // written POKéMON cell + A opened it, DOWN, A, SHIFT sent slot 1 in (gBattlerPartyIndexes[0] = 1).
        game == com.pokedaisy.app.companion.data.GameKind.TEAM_ROCKET ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203DE64L, party = 0x02025170L)
        // Wishes of Tomorrow: Emerald's list at its own gPartyMenu (NATIVE_WISHES). Headless: a written POKéMON cell + A
        // opened it, DOWN, A, SHIFT sent slot 1 in (gBattlerPartyIndexes[0] = 1).
        game == com.pokedaisy.app.companion.data.GameKind.WISHES ->
            BattleInputController.SwitchAddrs(partyMenu = 0x02031BB0L, party = 0x02031EBCL)
        game == com.pokedaisy.app.companion.data.GameKind.AMETHYST ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L, grid = true)
        // Unbound (EN / FR): FireRed's gPartyMenu / gPlayerParty (CFRU keeps its EWRAM) under CFRU's two-column party
        // grid - slot 0 top-left, 1 top-right, 2 below: RIGHT 0 -> 1, DOWN 0 -> 2, then CANCEL (7). Headless on the
        // user's save with the lead copied into slots 2-3: DOWN, A, SHIFT put party slot 2 into gBattlerPartyIndexes[0].
        game == com.pokedaisy.app.companion.data.GameKind.UNBOUND ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L, grid = true)
        // Radical Red: the same CFRU grid at FireRed's addresses (radical_red_battle's README).
        game == com.pokedaisy.app.companion.data.GameKind.RADICAL_RED ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L, grid = true)
        // Gaia: FireRed's addresses and FireRed's own one-column list (DOWN 0 -> 1; gaia_battle's README).
        game == com.pokedaisy.app.companion.data.GameKind.GAIA ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L)
        // Odyssey, Sword and Shield and GS Chronicles: CFRU's grid at FireRed's addresses, like Unbound (their fixtures' READMEs).
        game == com.pokedaisy.app.companion.data.GameKind.ODYSSEY || game == com.pokedaisy.app.companion.data.GameKind.SWORD_SHIELD ||
            game == com.pokedaisy.app.companion.data.GameKind.GS_CHRONICLES ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L, grid = true)
        // Heart and Soul: gPartyMenu found by its slotId walking 0 -> 7 -> 0 with one Pokémon (CANCEL at 7); a list
        // (DOWN 0 -> 1 with three). Headless: a written POKéMON cell opened it, DOWN, A, SHIFT sent slot 1 in.
        game == com.pokedaisy.app.companion.data.GameKind.HEART_AND_SOUL ->
            BattleInputController.SwitchAddrs(partyMenu = 0x020341FCL, party = 0x02034764L)
        // Celia: gPartyMenu found by its slotId (0 -> 7 -> 0 with one Pokémon), a list; its gPlayerParty is
        // FireRed's moved (NATIVE_CELIA). Headless: DOWN, A, SHIFT with three put slot 1 into gBattlerPartyIndexes[0].
        game == com.pokedaisy.app.companion.data.GameKind.CELIA ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B368L, party = 0x0202433CL)
        game == com.pokedaisy.app.companion.data.GameKind.ROWE ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203E774L, party = 0x02025128L, monStride = 0x4C, grid = true)
        // Quetzal: Emerald's list (DOWN walks slotId 0 -> 1), gPartyMenu by release - Alpha 9's 0x0203E9FC, 0.8.4's
        // 0x0203EA08 (its config; headless on all four 0.8.4 builds: DOWN, A, SHIFT sent slot 1 in).
        game == com.pokedaisy.app.companion.data.GameKind.QUETZAL ->
            telemetry.knownPartyMenu()?.let { (menu, party) -> BattleInputController.SwitchAddrs(partyMenu = menu, party = party, monStride = 0x68) }
        game == com.pokedaisy.app.companion.data.GameKind.IMPERIUM ->
            BattleInputController.SwitchAddrs(partyMenu = 0x020370E0L, party = 0x020375F8L, grid = true)
        // Dreamstone Mysteries: a two-column grid (NATIVE_DREAMSTONE). Headless: a written POKéMON cell + A opened it, DOWN
        // (slotId 0 -> 2), A, SHIFT put slot 2 into gBattlerPartyIndexes[0].
        game == com.pokedaisy.app.companion.data.GameKind.DREAMSTONE ->
            BattleInputController.SwitchAddrs(partyMenu = 0x02031E04L, party = 0x02032314L, grid = true)
        // Monomyth: a two-column grid at its own gPartyMenu (NATIVE_MONOMYTH). Headless: a written POKéMON cell + A opened
        // it, DOWN (slotId 0 -> 2), A, SHIFT put slot 2 into gBattlerPartyIndexes[0].
        game == com.pokedaisy.app.companion.data.GameKind.MONOMYTH ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203D8D0L, party = 0x02025908L, grid = true)
        // Glazed is retail Emerald's RAM and party menu code (NATIVE_GLAZED).
        game == com.pokedaisy.app.companion.data.GameKind.GLAZED ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203CEC8L, party = 0x020244ECL)
        game == com.pokedaisy.app.companion.data.GameKind.LAZARUS ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0201B67CL, party = 0x0201B960L, grid = true)
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD_SEAGLASS ->
            BattleInputController.SwitchAddrs(partyMenu = 0x02019964L, party = 0x02019C20L)
        // Recharged Yellow: FireRed's one-column party menu (DOWN steps gPartyMenu.slotId 0, 1, 2 in battle),
        // at each release's own address - its config carries it.
        game == com.pokedaisy.app.companion.data.GameKind.RECHARGED_YELLOW ->
            telemetry.knownPartyMenu()?.let { (menu, party) -> BattleInputController.SwitchAddrs(partyMenu = menu, party = party) }
        // Super Mariomon: two RAM layouts across its releases (NATIVE_MARIOMON_*); a 2-column grid
        // (DOWN walks 0 -> 2 -> 4 -> 5 -> CANCEL, headless).
        game == com.pokedaisy.app.companion.data.GameKind.MARIOMON ->
            telemetry.knownPartyMenu()?.let { (menu, party) -> BattleInputController.SwitchAddrs(partyMenu = menu, party = party, grid = true) }
        game == com.pokedaisy.app.companion.data.GameKind.SOULGOLD ->
            // Its builds sit at different addresses: their configs carry them. Its shape is the player's OPTIONS > PARTY
            // MENU (the config's partyMenuStyleOff): CUSTOM is one column of six (DOWN wraps 5 -> 0, no CANCEL row), HGSS
            // and BW a two-column grid - steered as a list, the cursor bounced between two slots (GitHub #316). Headless
            // on every build with the real controller: each style, a switch from POKéMON and the pick after a faint.
            telemetry.knownPartyMenu()?.let { (menu, party) ->
                BattleInputController.SwitchAddrs(
                    partyMenu = menu, party = party, monStride = 96,
                    gridOption = telemetry.knownPartyMenuStyle()?.let { (ptr, off) -> BattleInputController.GridOption(ptr, off) },
                )
            }
        // The other-language FireRed / LeafGreen (RETAIL_PORTS): their mapped gPartyMenu, checked by
        // scripts/verify_ports.py's party step. (Ruby / Sapphire have no battle input.)
        game == com.pokedaisy.app.companion.data.GameKind.FIRERED &&
            com.pokedaisy.app.companion.data.RETAIL_PORTS.containsKey("$romCode$romRev") ->
            telemetry.knownPartyMenu()?.let { (menu, party) -> BattleInputController.SwitchAddrs(partyMenu = menu, party = party) }
        else -> null
    }

    /** Backs the bottom-screen "Settings" tab. Unlike SettingsActivity (which
     * only writes Prefs, picked up next launch), this also live-applies to the
     * already-running engine/touch controls since the game keeps running. */
    private val companionSettings = object : com.pokedaisy.app.companion.CompanionSettings {
        override val ffMaxSpeed get() = Prefs(this@PokeDaisyActivity).ffMaxSpeed
        override fun setFfMaxSpeed(v: Float) {
            Prefs(this@PokeDaisyActivity).ffMaxSpeed = v
            if (::engine.isInitialized) engine.ffMaxSpeed = v
        }
        override val gameSpeed get() = if (::engine.isInitialized) engine.gameSpeed else 1f
        override val gameSpeeds get() = if (::engine.isInitialized) engine.gameSpeeds else super.gameSpeeds
        override fun setGameSpeed(v: Float) {
            if (!::engine.isInitialized) return
            engine.setGameSpeed(v)
            Prefs(this@PokeDaisyActivity).setSpeedIndexFor(romKey, engine.speedIndex)
        }
        override val battleSpeed get() = Prefs(this@PokeDaisyActivity).battleSpeed
        override fun setBattleSpeed(v: Float) {
            Prefs(this@PokeDaisyActivity).battleSpeed = v
            if (::engine.isInitialized) engine.battleSpeed = v
        }
        override val textSpeed get() = Prefs(this@PokeDaisyActivity).textSpeed
        override fun setTextSpeed(v: Float) {
            Prefs(this@PokeDaisyActivity).textSpeed = v
            if (::engine.isInitialized) engine.textSpeed = v
        }
        override val menuSpeed get() = Prefs(this@PokeDaisyActivity).menuSpeed
        override fun setMenuSpeed(v: Float) {
            Prefs(this@PokeDaisyActivity).menuSpeed = v
            if (::engine.isInitialized) engine.menuSpeed = v
        }
        override val bikeSpeed get() = Prefs(this@PokeDaisyActivity).bikeSpeed
        override fun setBikeSpeed(v: Float) {
            Prefs(this@PokeDaisyActivity).bikeSpeed = v
            if (::engine.isInitialized) engine.bikeSpeed = v
        }
        override val surfSpeed get() = Prefs(this@PokeDaisyActivity).surfSpeed
        override fun setSurfSpeed(v: Float) {
            Prefs(this@PokeDaisyActivity).surfSpeed = v
            if (::engine.isInitialized) engine.surfSpeed = v
        }
        override val fieldMovesReadable get() = ::engine.isInitialized && engine.fieldMovesKnown
        // A DS game: its own field read, known once it reports the cart (MgbaCore.FIELD_KNOWN).
        override val fieldReadable get() = fieldGame != WideScreen.OFF ||
            romIsNds && ::engine.isInitialized && engine.fieldState and MgbaCore.FIELD_KNOWN != 0
        override val autoRun get() = Prefs(this@PokeDaisyActivity).autoRun
        override fun setAutoRun(on: Boolean) {
            if (on) com.pokedaisy.app.companion.FeatureTips.used(TipId.AUTO_RUN)
            Prefs(this@PokeDaisyActivity).autoRun = on
            if (::engine.isInitialized) engine.autoRun = on
        }
        override val runIndoors get() = Prefs(this@PokeDaisyActivity).runIndoors
        override fun setRunIndoors(on: Boolean) {
            Prefs(this@PokeDaisyActivity).runIndoors = on
            if (::engine.isInitialized) engine.runIndoors = on
        }
        // Ruby / Sapphire tell indoor maps by their type (flash, weather and the region map read it too): not theirs.
        override val runIndoorsSupported get() = fieldGame != WideScreen.OFF && !romCode.startsWith("AXV") && !romCode.startsWith("AXP")
        override val wildEncounters get() = Prefs(this@PokeDaisyActivity).wildEncounters
        override fun setWildEncounters(percent: Int) {
            if (percent == 0) com.pokedaisy.app.companion.FeatureTips.used(TipId.WILD)
            Prefs(this@PokeDaisyActivity).wildEncounters = percent
            if (::engine.isInitialized) engine.wildEncounters = percent
        }
        override val wildEncountersSupported get() = (fieldGame != WideScreen.OFF && wildFlag != 0L) || gbField() != null ||
            romIsNds && ::engine.isInitialized && engine.fieldState and MgbaCore.FIELD_KNOWN != 0
        override val tradeEvolutions get() = Prefs(this@PokeDaisyActivity).tradeEvolutions
        override fun setTradeEvolutions(on: Boolean) { Prefs(this@PokeDaisyActivity).tradeEvolutions = on }
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
        override val reusableTms get() = Prefs(this@PokeDaisyActivity).reusableTms
        override fun setReusableTms(on: Boolean) {
            if (coopRefuses()) return
            if (on) com.pokedaisy.app.companion.FeatureTips.used(TipId.REUSABLE_TMS)
            Prefs(this@PokeDaisyActivity).reusableTms = on
            if (::engine.isInitialized) engine.setReusableTms(tmSites, on)
        }
        override val reusableTmsSupported get() = tmSites.isNotEmpty()
        override val instantText get() = Prefs(this@PokeDaisyActivity).instantText
        override fun setInstantText(on: Boolean) {
            Prefs(this@PokeDaisyActivity).instantText = on
            if (::engine.isInitialized) engine.setInstantText(textPatch, on)
        }
        override val instantTextSupported get() = textPatch != null
        override val dsResolution get() = if (romIsNds) Prefs(this@PokeDaisyActivity).dsResolution else null
        override val dsGpuAvailable get() = GlCaps.gles32(this@PokeDaisyActivity) && MgbaCore.pkNdsGpuSupported() != 0
        override val dsMelonDs get() = !dsperate
        override val dsCore get() = if (romIsNds) Prefs(this@PokeDaisyActivity).dsCore else null
        override fun setDsCore(core: com.pokedaisy.app.companion.DsCore) {
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
        override val nuzlockeSupported get() = telemetry.snapshot.value.let { !it.unsupported && com.pokedaisy.app.companion.Nuzlocke.supports(it.game) }
        override val nuzlocke get() = com.pokedaisy.app.companion.Nuzlocke.on
        override fun setNuzlocke(on: Boolean) = com.pokedaisy.app.companion.Nuzlocke.setOn(on)
        override val shinyOdds get() = shinyOddsPref()
        override fun setShinyOdds(odds: Int) {
            Prefs(this@PokeDaisyActivity).shinyOdds = odds
            if (::engine.isInitialized) engine.shinyOdds = odds
            syncShinyAchievements()
        }
        override val shinyOddsChoices get() = com.pokedaisy.app.companion.data.ShinyOdds.choices(BuildConfig.DEBUG)
        override val shinyOddsSupported get() = shinyTarget != null
        override val playerLookSupported get() = lookRunner.supported.value
        override val playerLooks get() = lookRunner.offered.value.map { it.name } + lookRunner.offeredMons.value.map { "MON:$it" }
        override val playerLook get() = lookRunner.picked.value?.name ?: lookRunner.pickedMon.value?.let { "MON:$it" }
        override fun playerLookLabel(name: String) = com.pokedaisy.app.companion.data.MonArt.monOf(name)?.let { lookRunner.monNames[it] ?: "#$it" }
            ?: com.pokedaisy.app.companion.data.LookChar.byName(name)?.label ?: name
        override val playerLookOwn get() = lookRunner.own.value?.name
        override fun setPlayerLook(look: String?) {
            if (coopRefuses()) return
            Prefs(this@PokeDaisyActivity).setPlayerLookFor(romKey, look)
            lookRunner.apply()
        }
        override val textLooks get() = lookRunner.textOffered.value.map { it.name }
        override val textLook get() = lookRunner.textPicked.value?.name
        override fun setTextLook(style: String?) {
            if (coopRefuses()) return
            Prefs(this@PokeDaisyActivity).setTextLookFor(romKey, style)
            lookRunner.apply()
        }
        override val followerSupported get() = followerSupported()
        override val followerReady get() = com.pokedaisy.app.companion.data.MonArt.available(filesDir).isNotEmpty()
        override val follower get() = Prefs(this@PokeDaisyActivity).follower
        override fun setFollower(on: Boolean) {
            Prefs(this@PokeDaisyActivity).follower = on
            applyFollower()
        }
        override val dayNightSupported get() = dayNightSupported()
        override val dayNight get() = Prefs(this@PokeDaisyActivity).dayNight
        override fun setDayNight(on: Boolean) { Prefs(this@PokeDaisyActivity).dayNight = on }
        override val rivalLookSupported get() = lookRunner.rivalSupported
        override val rideVehicle get() = com.pokedaisy.app.companion.data.RideVehicle.of(Prefs(this@PokeDaisyActivity).rideVehicleId)
        override fun setRideVehicle(v: com.pokedaisy.app.companion.data.RideVehicle) {
            Prefs(this@PokeDaisyActivity).rideVehicleId = v.id
            lookRunner.apply()   // the bike sheets rewritten: the next frame the bike shows is the new ride
        }
        override val rivalLook get() = Prefs(this@PokeDaisyActivity).rivalLook
        override fun setRivalLook(on: Boolean) {
            if (coopRefuses()) return
            Prefs(this@PokeDaisyActivity).rivalLook = on
            lookRunner.apply()
        }
        override val autoRunBuiltIn get() =
            com.pokedaisy.app.companion.data.activeGame in com.pokedaisy.app.companion.data.GAMES_WITH_OWN_AUTO_RUN
        override val reusableTmsBuiltIn get() =
            com.pokedaisy.app.companion.data.activeGame in com.pokedaisy.app.companion.data.ReusableTms.BUILT_IN
        override val ffToggled get() = if (::engine.isInitialized) engine.fastForwardToggled else false
        override fun setFfToggled(on: Boolean) {
            if (::engine.isInitialized) engine.setFastForwardToggled(on)
        }
        override val ffMusicMode get() = Prefs(this@PokeDaisyActivity).ffMusicMode
        override fun setFfMusicMode(mode: FfMusicMode) {
            Prefs(this@PokeDaisyActivity).ffMusicMode = mode
            if (::engine.isInitialized) engine.ffMusicMode = mode
        }
        override val rewind get() = Prefs(this@PokeDaisyActivity).rewind
        override val rewindSupported get() = !romIsNds
        override fun setRewind(on: Boolean) {
            Prefs(this@PokeDaisyActivity).rewind = on
            if (::engine.isInitialized) engine.rewindEntries = rewindEntries(on)
            runOnUiThread { syncTouchExtras() }
        }
        override val ffMode get() = Prefs(this@PokeDaisyActivity).ffMode
        override fun setFfMode(mode: FfMode) {
            Prefs(this@PokeDaisyActivity).ffMode = mode
            if (::engine.isInitialized) engine.ffMode = mode
        }
        override val touchStyle get() = Prefs(this@PokeDaisyActivity).touchControlsStyle
        override fun setTouchStyle(style: com.pokedaisy.app.companion.TouchControlsStyle) {
            Prefs(this@PokeDaisyActivity).touchControlsStyle = style
            runOnUiThread { syncTouchControls() }
        }
        override val touchStage get() = measuredTouchStage()
        override val foldLayout: Boolean?
            get() = Prefs(this@PokeDaisyActivity).let { p -> if (p.foldSeen && secondScreen() == null) p.foldLayout else null }
        override fun setFoldLayout(on: Boolean) {
            Prefs(this@PokeDaisyActivity).foldLayout = on
            runOnUiThread { if (::sidePanel.isInitialized) syncSingleScreen(singleScreen) }
        }
        override val touchSkins get() = skinStore.list()
        override fun touchPreviewShot() = newestStateShot()
        override fun editTouchLayout() = runOnUiThread { openTouchEditor() }
        override val touchRevision get() = touchRev.intValue
        override val touchControlsShown get() = ::touchControls.isInitialized && touchControls.visibility == View.VISIBLE
        override fun gbaControlBindings() = GbaControls.rawBindings(getExternalFilesDir(null) ?: filesDir)
        override val keyCapture get() = this@PokeDaisyActivity.keyCapture
        override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {
            val code = keyCodeForName(keyName) ?: return
            GbaControls.setBinding(getExternalFilesDir(null) ?: filesDir, btn, code)
            input.setControls(GbaControls.load(getExternalFilesDir(null) ?: filesDir))
        }
        override fun clearGbaControlBinding(btn: GbaControls.Btn) {
            GbaControls.clearBinding(getExternalFilesDir(null) ?: filesDir, btn)
            input.setControls(GbaControls.load(getExternalFilesDir(null) ?: filesDir))
        }
        override val buttonGroup get() = if (romIsNds) GbaControls.Group.DS else GbaControls.Group.GBA
        override val hotkeysEnabled get() = hotkeysOn
        override fun setHotkeysEnabled(on: Boolean) {
            Prefs(this@PokeDaisyActivity).hotkeysEnabled = on
            setHotkeysOn(on)
        }
        override fun hotkeyBindings() = Hotkeys.load(getExternalFilesDir(null) ?: filesDir).rawBindings
        override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {
            val dir = getExternalFilesDir(null) ?: filesDir
            if (keyNames.isNullOrEmpty()) Hotkeys.clearBinding(dir, action)
            else Hotkeys.setBindingNamed(dir, action, keyNames, takeFrom)
            hotkeys = Hotkeys.load(getExternalFilesDir(null) ?: filesDir)
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
        override val autoResume get() = Prefs(this@PokeDaisyActivity).autoResume
        override fun setTweak(key: String, on: Boolean) = Prefs(this@PokeDaisyActivity).setTweak(key, on)
        override val gameVolume get() = GameVolume.percent
        override fun setGameVolume(percent: Int) = this@PokeDaisyActivity.setGameVolume(percent, GameVolume.muted)
        override val gameMuted get() = GameVolume.muted
        override fun setGameMuted(on: Boolean) = this@PokeDaisyActivity.setGameVolume(GameVolume.percent, on)
        override val audioEffects get() = Prefs(this@PokeDaisyActivity).audioEffects
        override fun setAudioEffects(on: Boolean) { Prefs(this@PokeDaisyActivity).audioEffects = on }
        override val clickSound get() = clickSoundOn
        override fun setClickSound(on: Boolean) {
            Prefs(this@PokeDaisyActivity).clickSound = on
            clickSoundOn = on
        }
        override val statusBar get() = Prefs(this@PokeDaisyActivity).statusBar
        override fun setStatusBar(on: Boolean) {
            Prefs(this@PokeDaisyActivity).statusBar = on
            runOnUiThread { syncGameScreen() }
        }
        override val fieldHud get() = Prefs(this@PokeDaisyActivity).fieldHud
        override fun setFieldHud(on: Boolean) {
            Prefs(this@PokeDaisyActivity).fieldHud = on
            runOnUiThread { syncGameScreen() }
        }
        override val battleButtons get() = Prefs(this@PokeDaisyActivity).battleButtons
        override fun setBattleButtons(on: Boolean) {
            Prefs(this@PokeDaisyActivity).battleButtons = on
            runOnUiThread { syncGameScreen() }
        }
        override val fieldHudStyle get() = Prefs(this@PokeDaisyActivity).fieldHudStyle
        override fun setFieldHudStyle(style: com.pokedaisy.app.companion.FieldHudStyle) {
            Prefs(this@PokeDaisyActivity).fieldHudStyle = style
            runOnUiThread { syncGameScreen() }
        }
        override val quickMenuActions get() = Prefs(this@PokeDaisyActivity).quickMenuActions
        override fun setQuickMenuActions(actions: Set<com.pokedaisy.app.companion.QuickAction>) {
            Prefs(this@PokeDaisyActivity).quickMenuActions = actions
            quickShown.value = actions
        }
        override val quickMenuStyle get() = Prefs(this@PokeDaisyActivity).quickMenuStyle
        override fun setQuickMenuStyle(style: com.pokedaisy.app.companion.QuickMenuStyle) {
            Prefs(this@PokeDaisyActivity).quickMenuStyle = style
        }
        override val quickMenuLabels get() = Prefs(this@PokeDaisyActivity).quickMenuLabels
        override fun setQuickMenuLabels(on: Boolean) {
            Prefs(this@PokeDaisyActivity).quickMenuLabels = on
        }
        override val showPerformance get() = Prefs(this@PokeDaisyActivity).showPerformance
        override fun setShowPerformance(on: Boolean) {
            Prefs(this@PokeDaisyActivity).showPerformance = on
            runOnUiThread { syncGameScreen() }
        }
        // RetroAchievements reads Prefs at each event, so these apply to the running game at once.
        override val raPopups get() = Prefs(this@PokeDaisyActivity).raPopups
        override fun setRaPopups(on: Boolean) { Prefs(this@PokeDaisyActivity).raPopups = on }
        override val raProgressPopups get() = Prefs(this@PokeDaisyActivity).raProgressPopups
        override fun setRaProgressPopups(on: Boolean) { Prefs(this@PokeDaisyActivity).raProgressPopups = on }
        override val statusBarOnCompanion get() = Prefs(this@PokeDaisyActivity).statusBarOnCompanion
        override fun setStatusBarOnCompanion(on: Boolean) {
            Prefs(this@PokeDaisyActivity).statusBarOnCompanion = on
            runOnUiThread { syncGameScreen() }
        }
        override val gameAspect get() = Prefs(this@PokeDaisyActivity).gameAspect
        override fun setGameAspect(aspect: com.pokedaisy.app.companion.GameAspect) {
            if (aspect == com.pokedaisy.app.companion.GameAspect.WIDE) com.pokedaisy.app.companion.FeatureTips.used(TipId.EXPAND)
            Prefs(this@PokeDaisyActivity).gameAspect = aspect
            runOnUiThread { syncGameScreen() }
        }
        override val wideSupported get() = wideGame != WideScreen.OFF
        override val lookAround get() = Prefs(this@PokeDaisyActivity).lookAround
        override fun setLookAround(on: Boolean) {
            Prefs(this@PokeDaisyActivity).lookAround = on
            view.camera.lookAround = on
        }
        override fun feelVibration(level: Int) = TouchHaptics.buzz(this@PokeDaisyActivity, level)
        override val zoomSteps get() = view.camera.levels().indices.map { view.camera.factor(it) }
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
        override val tips get() = Prefs(this@PokeDaisyActivity).tips
        override fun setTips(on: Boolean) {
            Prefs(this@PokeDaisyActivity).tips = on
            com.pokedaisy.app.companion.FeatureTips.scheduler?.enabled = on
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
        override val gbaColors get() = Prefs(this@PokeDaisyActivity).gbaColors
        override fun setGbaColors(on: Boolean) {
            Prefs(this@PokeDaisyActivity).gbaColors = on
            runOnUiThread { syncGameScreen() }
        }
        override val unlitScreen get() = Prefs(this@PokeDaisyActivity).unlitScreen
        override fun setUnlitScreen(on: Boolean) {
            Prefs(this@PokeDaisyActivity).unlitScreen = on
            runOnUiThread { syncGameScreen() }
        }
        override val screenFilter get() = Prefs(this@PokeDaisyActivity).screenFilter
        override fun setScreenFilter(filter: ScreenFilter) {
            Prefs(this@PokeDaisyActivity).screenFilter = filter
            runOnUiThread { syncGameScreen() }
        }
        override val gridStrength get() = Prefs(this@PokeDaisyActivity).gridStrength
        override fun setGridStrength(strength: GridStrength) {
            Prefs(this@PokeDaisyActivity).gridStrength = strength
            runOnUiThread { syncGameScreen() }
        }
        override val smoothing get() = Prefs(this@PokeDaisyActivity).smoothing
        override fun setSmoothing(mode: Smoothing) {
            Prefs(this@PokeDaisyActivity).smoothing = mode
            runOnUiThread { syncGameScreen() }
        }
        override val smoothingAvailable get() = GlCaps.gles3(this@PokeDaisyActivity)
        override fun setLightCompanion(mode: com.pokedaisy.app.companion.LightCompanionMode) {
            com.pokedaisy.app.companion.LightCompanion.mode = mode
            Prefs(this@PokeDaisyActivity).lightCompanion = mode.id
            runOnUiThread { syncGameScreen() }
        }
        override val companionShaders get() = Prefs(this@PokeDaisyActivity).companionShaders
        override fun setCompanionShaders(on: Boolean) {
            Prefs(this@PokeDaisyActivity).companionShaders = on
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
            runOnUiThread { sidePanel.openTabShown = on }
        }
        override fun setSwapScreens(on: Boolean) {
            Screens.setSwapped(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), on)
            // The companion that asked moves screens: it comes back on SETTINGS, on SWAP SCREENS.
            companionStartTab = "SETTINGS"
            companionStartRow = "SWAP SCREENS"
            runOnUiThread { syncPresentation() }
        }
        override val showHints get() = Prefs(this@PokeDaisyActivity).showHints
        override fun setShowHints(on: Boolean) {
            Prefs(this@PokeDaisyActivity).showHints = on
            battleHints.value = Prefs(this@PokeDaisyActivity).let { it.showHints && it.battleInfo }
        }
        override val appLanguage get() = Prefs(this@PokeDaisyActivity).appLanguage
        override fun setAppLanguage(code: String) {
            Prefs(this@PokeDaisyActivity).appLanguage = code
            com.pokedaisy.app.companion.i18n.L10n.apply(code, romCode)
        }

        override fun setDarkMode(v: Int) { Prefs(this@PokeDaisyActivity).darkMode = v }
        override fun setAppTheme(id: Int) {
            com.pokedaisy.app.companion.ui.theme.pickTheme(this@PokeDaisyActivity, com.pokedaisy.app.companion.ui.theme.themeById(id))
        }
        override val showFoeIvs get() = Prefs(this@PokeDaisyActivity).showFoeIvs
        override fun setShowFoeIvs(on: Boolean) {
            Prefs(this@PokeDaisyActivity).showFoeIvs = on
        }
        override val battleInfo get() = Prefs(this@PokeDaisyActivity).battleInfo
        override fun setBattleInfo(on: Boolean) {
            Prefs(this@PokeDaisyActivity).battleInfo = on
            battleHints.value = Prefs(this@PokeDaisyActivity).let { it.showHints && it.battleInfo }
        }
        override val battleInfoFirst get() = Prefs(this@PokeDaisyActivity).battleInfoFirst
        override fun setBattleInfoFirst(on: Boolean) { Prefs(this@PokeDaisyActivity).battleInfoFirst = on }
        override val raIcons get() = Prefs(this@PokeDaisyActivity).raIcons
        override fun setRaIcons(on: Boolean) {
            Prefs(this@PokeDaisyActivity).raIcons = on
            com.pokedaisy.app.companion.ui.AchievementIcons.shown = on
        }
        override val battleSuggestionsFirst get() = Prefs(this@PokeDaisyActivity).battleSuggestionsFirst
        override fun setBattleSuggestionsFirst(on: Boolean) { Prefs(this@PokeDaisyActivity).battleSuggestionsFirst = on }
        override val oneTapMoves get() = Prefs(this@PokeDaisyActivity).oneTapMoves
        override fun setOneTapMoves(on: Boolean) { Prefs(this@PokeDaisyActivity).oneTapMoves = on }
        override val battleStyle get() = this@PokeDaisyActivity.battleStyle.value
        override fun setBattleStyle(style: com.pokedaisy.app.companion.BattleStyle) {
            Prefs(this@PokeDaisyActivity).battleStyle = style.id
            this@PokeDaisyActivity.battleStyle.value = style
        }
        override val foeDetails get() = Prefs(this@PokeDaisyActivity).foeDetails
        override fun setFoeDetails(on: Boolean) { Prefs(this@PokeDaisyActivity).foeDetails = on }
        override val battleFocus get() = Prefs(this@PokeDaisyActivity).battleFocus
        override fun setBattleFocus(on: Boolean) { Prefs(this@PokeDaisyActivity).battleFocus = on }
        override val cheats get() = cheatStore()?.load().orEmpty()
        override val cheatsEnabled get() = Prefs(this@PokeDaisyActivity).cheatsEnabled
        override fun setCheatsEnabled(on: Boolean) {
            if (coopRefuses()) return
            if (coopCheatsRefuse(on)) return
            Prefs(this@PokeDaisyActivity).cheatsEnabled = on
            syncCheats()
        }
        override fun setCheatEnabled(index: Int, on: Boolean) {
            if (coopRefuses()) return
            if (coopCheatsRefuse(on)) return
            val store = cheatStore() ?: return
            val list = store.load()
            if (index !in list.indices) return
            store.save(list.toMutableList().also { it[index] = it[index].copy(enabled = on) })
            syncCheats()
        }
        override val gameName get() = romBlocked?.title ?: com.pokedaisy.app.companion.data.activeGame.displayName()
        override val gameBlocked get() = romBlocked != null
        override val romFileName get() = rom?.name ?: "(none)"
        override val companionTabs get() = Prefs(this@PokeDaisyActivity).companionTabs
        override fun setCompanionTabs(tabs: List<String>) {
            Prefs(this@PokeDaisyActivity).companionTabs = tabs
        }
        override fun guideNoticeAccepted(game: String) = Prefs(this@PokeDaisyActivity).guideNoticeAccepted(game)
        override fun acceptGuideNotice(game: String) = Prefs(this@PokeDaisyActivity).acceptGuideNotice(game)
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
            prefs.coopRelayOnly = on
            com.pokedaisy.app.companion.coop.CoopSession.restart(this@PokeDaisyActivity, prefs)
        }
        override fun coopUnpair(save: com.pokedaisy.app.companion.coop.CoopSaveId) =
            com.pokedaisy.app.companion.coop.CoopSession.unpair(this@PokeDaisyActivity, save)
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
        AchievementBadges.dir = java.io.File(cacheDir, "ra-badges")
        // A popup goes to the companion the player can see, else over the game (a DS game's touch screen hides it: #500).
        com.pokedaisy.app.companion.ui.AchievementPopupRoute.shownProbe = {
            val v = androidx.compose.ui.platform.LocalView.current
            remember(v) { { v.isShown } }
        }
        lookGender.launch {
            telemetry.snapshot.map { it.playerGender }.distinctUntilChanged().collect { lookRunner.onGender(it) }
        }
        // PLAYER LOOK's Pokémon: the party's species (national numbers) with their names in the game.
        lookGender.launch {
            telemetry.snapshot.map { Triple(it.party.map { m -> m.species }, it.party.firstOrNull { m -> !m.isEgg }?.species, it.pokedex) }
                .distinctUntilChanged().collect { (species, leadSpecies, dex) ->
                val tables = dex?.tables ?: return@collect
                val named = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    species.distinct().mapNotNull { sp ->
                        runCatching { com.pokedaisy.app.companion.data.PokedexSource.nationalOf(tables, sp) }.getOrNull()
                            ?.takeIf { it in 1..com.pokedaisy.app.companion.data.MonArt.LAST }
                            ?.let { it to com.pokedaisy.app.companion.data.speciesName(sp) }
                    }
                }
                lookRunner.onParty(named.map { it.first }.distinct(), named.toMap())
                followerLead = leadSpecies?.let { sp ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { com.pokedaisy.app.companion.data.PokedexSource.nationalOf(tables, sp) }.getOrNull()
                    }
                }?.takeIf { it in 1..com.pokedaisy.app.companion.data.MonArt.LAST }
                applyFollower()
            }
        }
        // DAY AND NIGHT: the tint the engine eases to on the field - the clock's, outdoors, while the row is on.
        lookGender.launch {
            while (true) {
                if (::engine.isInitialized) {
                    val snap = telemetry.snapshot.value
                    engine.dayNightTint = if (Prefs(this@PokeDaisyActivity).dayNight && dayNightSupported() &&
                        com.pokedaisy.app.companion.data.DayNight.outdoors(snap.mapType)
                    ) com.pokedaisy.app.companion.data.DayNight.now() else com.pokedaisy.app.companion.data.DayNight.NONE
                }
                kotlinx.coroutines.delay(500)
            }
        }
        com.pokedaisy.app.companion.FeatureTips.init(Prefs(this))
        com.pokedaisy.app.companion.FeatureTips.available = ::tipAvailable

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
            val (dx, dy) = if (::stage.isInitialized) stage.dsGameOffset else 0 to 0
            // (A DS layout: the game's box, even where it starts at the area's corner - LARGE + SMALL's game is at 0, 0 and
            // the HUD spread over its touch screen.)
            val ds = ::stage.isInitialized && stage.dsArrange != null
            dsHudClear.value = if (ds) stage.dsTouchOverGame else 0f
            hudFrame.value = r?.let { androidx.compose.ui.geometry.Rect(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy) }
                ?: if (ds || dx != 0 || dy != 0) androidx.compose.ui.geometry.Rect(dx.toFloat(), dy.toFloat(), (dx + view.width).toFloat(), (dy + view.height).toFloat()) else null
        }
        // BATTLE BUTTONS keep right of the GBA's own picture (EXPAND's battle included).
        view.onCoreRect = { r ->
            val (dx, dy) = if (::stage.isInitialized) stage.dsGameOffset else 0 to 0
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
        performance = buildPerformanceOverlay()
        fieldHud = buildFieldHud()
        buildDsStageViews()
        keyCapturePopup = ComposeView(this).apply {
            visibility = View.GONE
            setContent { com.pokedaisy.app.companion.ui.KeyCapturePopup(keyCapture) }
        }
        val root = GameStageLayout(this, view, statusBar, listOf(hud, performance), overGame = listOf(fieldHud, keyCapturePopup)).apply {
            setBackgroundColor(Color.BLACK)
            dsBackdrop = dsBackdropView
            dsTouch = dsTouchView
            // Shrink the game view to the left portion (instead of full-screen
            // underneath the mirror) so the whole GBA frame is actually
            // visible, aspect-fit within that narrower space, rather than half
            // of it sitting hidden behind the companion panel.
            val gameParams = if (debugMirror) {
                FrameLayout.LayoutParams(-1, -1).apply { rightMargin = dp(DEBUG_MIRROR_WIDTH_DP) }
            } else {
                FrameLayout.LayoutParams(-1, -1)
            }
            // DS LAYOUT: the game's backdrop under the game, the touch screen over it, the floating window under the
            // touch controls (which stay bright and tappable over its dim).
            addView(dsBackdropView, FrameLayout.LayoutParams(-1, -1))
            addView(view, gameParams)
            addView(dsTouchView, FrameLayout.LayoutParams(-1, -1))
            addView(statusBar, FrameLayout.LayoutParams(-2, -2))
            addView(dsWindowView, FrameLayout.LayoutParams(-1, -1))
            addView(touchControls, FrameLayout.LayoutParams(-1, -1))
            // Over the touch controls: a tap on the HUD is the HUD's, anywhere else falls through to the pad.
            addView(fieldHud, FrameLayout.LayoutParams(-1, -1))
            // The HUD's messages top-right: FPS / CPU has the top-left corner.
            addView(hud, FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(12); rightMargin = dp(12)
            })
            // FPS / CPU: flush in the top-left corner, like other emulators. The view covers the stage (its words drawn at
            // the corner, a touch elsewhere falling through): wrapped round its words, every reading that changed their
            // width laid the whole stage out again, twice a second (most of a weak tablet's idle frames, 2026-11-08).
            addView(performance, FrameLayout.LayoutParams(-1, -1))
            // Over everything on the game: the companion's rebind asking for keys.
            addView(keyCapturePopup, FrameLayout.LayoutParams(-1, -1))
            if (debugMirror) {
                addView(buildDebugCompanionMirror(), debugMirrorLayoutParams())
                addView(
                    TextView(this@PokeDaisyActivity).apply {
                        text = "DEBUG MIRROR"
                        setTextColor(Color.WHITE)
                        setBackgroundColor(0xC0C02020.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        val p = dp(6)
                        setPadding(p, dp(3), p, dp(3))
                    },
                    FrameLayout.LayoutParams(-2, -2).apply {
                        gravity = Gravity.BOTTOM or Gravity.START
                        bottomMargin = dp(8); leftMargin = dp(8)
                    },
                )
            }
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
        val owner = ComposeHostOwner().apply { create(); resume() }
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
        // Its panel and tabs above the FIELD HUD (which sits above the touch controls).
        // A DS game's touch screen is on the stage held sideways (DS LAYOUT), so the panel is the companion alone.
        sidePanel = SidePanel(root, view, touchControls, Prefs(this), panelBack, panelKeys, playClick, under = fieldHud, onWindow = ::onCompanionWindow, companion = {
            val snap by telemetry.snapshot.collectAsState()
            CompanionScreen(snap, stateSlots, companionSettings, battleInput, back = panelBack, keys = panelKeys, clickSound = playClick, achievements = RetroAchievements, statusBar = companionStatusBar, itemUse = itemUse, fly = flyAction, askForSupport = askForSupport, gamePick = gamePick, shareBestEffort = shareBestEffort)
        })
        portraitPanel = PortraitPanel(root, view, touchControls, Prefs(this), portraitBack, playClick) {
            dsOrCompanion {
                val snap by telemetry.snapshot.collectAsState()
                CompanionFocusHost(bottomFocus.value, bottomKeys) {
                    CompanionScreen(snap, stateSlots, companionSettings, battleInput, back = portraitBack, keys = bottomKeys, clickSound = playClick, achievements = RetroAchievements, statusBar = companionStatusBar, itemUse = itemUse, fly = flyAction, askForSupport = askForSupport, gamePick = gamePick, shareBestEffort = shareBestEffort)
                }
            }
        }
        // Two screens: the game stays on the landscape top one. One screen (a phone): it turns
        // with the device (the user's rotation setting), the companion under the game upright.
        syncOrientation(secondScreen() != null)
        syncGameScreen()

        hotkeys = Hotkeys.load(getExternalFilesDir(null) ?: filesDir)
        // L2 / R2 are TOUCH SCREEN / DS LAYOUT's in a DS game on one screen, SPEED CYCLE / FF HOLD's elsewhere.
        Hotkeys.dsActive = { a ->
            when (a) {
                Hotkeys.Action.TOUCH_SCREEN -> dsFloats.value
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
        freshLaunch = true
        // An archive unpacks once (cached after) - ~0.5 s for 32 MiB, on the black screen.
        val r = RomArchive.playable(romFile, cacheDir) ?: romFile
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
        flightArea = com.pokedaisy.app.companion.data.FlightArea.Keeper(filesDir, crc)
        flightAreaAnywhereSent = false
        flightAreaQueued = false
        RetroAchievements.setFlightAnywhere(false)
        states = SaveStates(getExternalFilesDir(null) ?: filesDir, crc)
        val ffMusicCache = FfMusicCache(getExternalFilesDir(null) ?: filesDir, crc, gameBoy = RomIdentity.isGameBoy(r))
        // STEADY FF music: songs rendered on their own from this ROM, in the
        // background, as the game starts them (see FfMusicRenderer).
        // The companion's button click is rendered from this ROM the same way.
        clickSound?.release()
        clickSound = GameClickSound(filesDir, crc)
        clickSoundOn = Prefs(this).clickSound
        com.pokedaisy.app.companion.ui.CompanionTweaks.load { Prefs(this).tweak(it) }
        Prefs(this).let { prefs ->
            com.pokedaisy.app.companion.FavoriteItems.load(prefs.favoriteItemsFor(crc)) { prefs.setFavoriteItemsFor(crc, it) }
            com.pokedaisy.app.companion.Nuzlocke.load(
                prefs.nuzlockeFor(crc), prefs.nuzlockeRunFor(crc),
                saveOn = { prefs.setNuzlockeFor(crc, it) }, saveRun = { prefs.setNuzlockeRunFor(crc, it) },
            )
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
            com.pokedaisy.app.companion.data.OriginHeartGold.matches(r)
        dsWildRates = if (!nds) null else if (romOrigin) {
            // Its tables are read in the background (NdsArt.prefetch); the core may start first, so read them here then.
            { (com.pokedaisy.app.companion.data.OriginHeartGold.data ?: com.pokedaisy.app.companion.data.NdsArt.readOrigin(r))?.wildRates ?: IntArray(0) }
        } else if (ndsCode == com.pokedaisy.app.companion.data.RenegadePlatinum.CODE &&
            com.pokedaisy.app.companion.data.RenegadePlatinum.matches(r)) {
            // Renegade Platinum: retail Platinum's header, its own maps' rates (read from its cart, like Origin's).
            { (com.pokedaisy.app.companion.data.RenegadePlatinum.data ?: com.pokedaisy.app.companion.data.NdsArt.readRenegade(r))?.wildRates ?: IntArray(0) }
        } else if ((ndsCode == com.pokedaisy.app.companion.data.Black2Redux.CODE_BLACK2 ||
                ndsCode == com.pokedaisy.app.companion.data.Black2Redux.CODE_WHITE2) &&
            com.pokedaisy.app.companion.data.Black2Redux.matches(r)) {
            // Blaze Black 2 / Volt White 2 Redux: retail Black 2's / White 2's header, their own zones' rates (from the cart).
            { (com.pokedaisy.app.companion.data.Black2Redux.data ?: com.pokedaisy.app.companion.data.NdsArt.readRedux(r))?.wildRateWords() ?: IntArray(0) }
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
        wildFlag = 0L
        tmSites = emptyList()
        if (::engine.isInitialized) engine.setReusableTms(tmSites, false)
        textPatch = null
        if (::engine.isInitialized) engine.setInstantText(null, false)
        shinyTarget = null
        if (::engine.isInitialized) engine.setShinyTarget(null)
        syncShinyAchievements()
        gbFieldCache = null
        if (::engine.isInitialized) engine.gbField = null
        if (gba && romBlocked == null) Thread({
            val flag = com.pokedaisy.app.companion.data.WildEncounters.find(r)
            Log.i("pokedaisy", "wild encounters: switch %08X".format(flag))
            // REUSABLE TMS: the spots that use a TM up, by their code (none = the row shows N/A).
            val tms = com.pokedaisy.app.companion.data.ReusableTms.find(r)
            Log.i("pokedaisy", "reusable TMs: ${tms.size} site(s)")
            val text = com.pokedaisy.app.companion.data.InstantText.find(r)
            runOnUiThread {
                if (romData != r) return@runOnUiThread
                wildFlag = flag
                if (::engine.isInitialized) engine.wildFlag = flag
                tmSites = tms
                if (::engine.isInitialized) engine.setReusableTms(tms, Prefs(this).reusableTms)
                textPatch = text
                if (::engine.isInitialized) engine.setInstantText(text, Prefs(this).instantText)
            }
        }, "wild-encounters").start()
        Prefs(this).let { p ->
            expandOfferUp.value = wideGame != WideScreen.OFF && !p.expandGameOfferSeen &&
                p.gameAspect != com.pokedaisy.app.companion.GameAspect.WIDE
        }
        // FireRed / Emerald party-menu art and region maps come from the ROM
        // itself, once per ROM (see RomArt) - nothing of the game is bundled.
        if (gba) RomArt.prefetch(filesDir, crc, r) else if (gameBoy) com.pokedaisy.app.companion.data.Gen1Art.prefetch(filesDir, r)
        // A DS cart's party icons, from its own file system (NdsArt).
        if (nds) com.pokedaisy.app.companion.data.NdsArt.prefetch(filesDir, r)
        if (gameBoy) com.pokedaisy.app.companion.data.Gen2Art.prefetch(filesDir, r)   // a Color cart's (Crystal): its own scan
        // A Game Boy cart's POKéDEX pages are read from its bytes (bank-switched: not on the bus whole); a few MB at most.
        val gbRom = if (gameBoy) runCatching { r.readBytes() }.getOrNull() else null
        com.pokedaisy.app.companion.data.Gen1Dex.rom = gbRom
        // STEADY FF music: a Gen 1 cart's (and Crystal's) songs come from its own sound engine (Gen1MusicRenderer).
        gen1Music = gbRom?.let {
            val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(it).joinToString("") { b -> "%02x".format(b) }
            com.pokedaisy.app.companion.data.Gen1Music.forSha1(sha1)
        }
        ffMusicRenderer = when {
            gba -> FfMusicRenderer(r, crc, ffMusicCache, clickSound, unlockSound).also { it.start() }
            gen1Music != null -> Gen1MusicRenderer(r, gen1Music!!, ffMusicCache).also { it.start() }
            else -> null
        }
        // A FireRed-engine hack's own region map (Unbound, Odyssey, ...), read
        // from the ROM on every launch - a few KB of reads (see RomRegionMap).
        if (gba) RomRegionMap.load(filesDir, crc, r) else RomRegionMap.current = null   // not the last GBA game's
        // SMART FF steps aside while the game's region map or a menu is up (see EmulatorEngine).
        if (gba) RegionMapWatch.load(r)
        val (menuField, menuBattle) = Prefs(this).ffMenuCallbacks(crc)
        // The game code and revision: a GBA header's 0xAC / 0xBC, a DS header's 0x0C / 0x1E.
        val header = runCatching {
            java.io.RandomAccessFile(r, "r").use { f -> f.seek(if (nds) 0x0C else 0xAC); ByteArray(0x13).also { f.readFully(it) } }
        }.getOrNull()
        val gameCode = header?.let { String(it, 0, 4, Charsets.US_ASCII) }.orEmpty()
        romCode = gameCode
        // AUTO = the ROM's own language (its game code's last letter; Origin HeartGold's header is Japanese, its text English).
        com.pokedaisy.app.companion.i18n.L10n.apply(Prefs(this).appLanguage, if (romOrigin) "IPKE" else gameCode)
        romRev = header?.let { it[if (nds) 0x12 else 0x10].toInt() and 0xFF } ?: -1
        // A DS cart's EXPAND: the overworld past the top screen's edges, for a cart its field read knows.
        pickDsCore()
        view.camera.ndsMode(nds)
        // DS RESOLUTION's frames come through a SurfaceTexture the view hands the core (EmulatorView.gpuFrames).
        view.gpuFrames = nds
        // The ZOOM picked last time on this game: the camera holds 1x until the field has been up a moment, then eases
        // out to it (WideCamera.reset), so it comes back once the game has loaded rather than over its title screen.
        view.camera.level = Prefs(this).zoomLevelFor(romKey)
        FfMenuWatch.load(gameCode, menuField, menuBattle)
        com.pokedaisy.app.companion.data.ShopWatch.load()
        val appContext = applicationContext
        FfMenuWatch.onLearned = { field, battle -> Prefs(appContext).setFfMenuCallbacks(crc, field, battle) }

        engine = EmulatorEngine(input, states!!, ffMusicCache).apply {
            hudEngine.value = this
            lateFramesSource = { view.lateFrames }
            // The game screen's fastest mode, which no tick that waits for the refresh can outrun (VsyncCheck, GitHub #350).
            displayMaxHz = { view.display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f }
            drawStatsSource = { view.repeatedDraws to view.unshownFrames }
            if (BuildConfig.DEBUG) audioTapDir = getExternalFilesDir("debug")
            this.gba = gba
            this.nds = nds
            ndsGameCode = ndsCode
            wideCamera = view.camera.also {
                it.zoomOnText = Prefs(this@PokeDaisyActivity).expandZoomOnText
                it.lookAround = Prefs(this@PokeDaisyActivity).lookAround
            }
            onWideOff = { runOnUiThread { showHud(com.pokedaisy.app.companion.i18n.tr("EXPAND hit a problem and is off for this game")) } }
            gen1Music = this@PokeDaisyActivity.gen1Music
            onCoreReady = { w, h ->
                // A DS game's bottom screen goes to the bottom display (NdsBottomFrame).
                NdsBottomFrame.bind(if (romIsNds) MgbaCore.pkNdsBottomBuffer() else null)
                // WILD ENCOUNTERS back on: the cart's own rates, whatever a resume state left in RAM.
                if (romIsNds) dsWildRates?.let { MgbaCore.pkNdsWildRates(it()) }
                MgbaCore.pkVideoBuffer()?.let { buf ->
                    // Here on the emu thread, before the first frame (see EmulatorView.bindCore).
                    view.bindCore(buf, MgbaCore.pkWideBuffer(), w, h)
                    runOnUiThread {
                        stage.aspect = w.toFloat() / h   // 3:2, or a Game Boy's 10:9
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
                    Hotkeys.Action.LOAD_STATE -> if (ok) tr("Loaded slot {0}", slot) else if (CoopGuards.statesBlockedBy(CoopGuards.StateBlock.LOCK)) tr("Not while you play together only") else tr("Slot {0}: nothing to load", slot)
                    Hotkeys.Action.UNDO_SAVE -> if (ok) tr("Undid save (slot {0})", slot) else tr("Nothing to undo")
                    Hotkeys.Action.UNDO_LOAD -> if (ok) tr("Undid load") else if (CoopGuards.statesBlockedBy(CoopGuards.StateBlock.LOCK)) tr("Not while you play together only") else tr("Nothing to undo")
                    else -> ""
                }
                if (msg.isNotEmpty()) runOnUiThread { showHud(msg) }
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
                // SMART FF reads the game's own gMain once the game is known (no scan
                // guesswork), and learns the field's main callback from the player moving.
                telemetry.knownGMain()?.let { (addr, inBattleOff) -> FfMenuWatch.useKnownGMain(addr, inBattleOff) }
                if (snap.connected) FfMenuWatch.notePosition(snap.x, snap.y, snap.mapGroup, snap.mapNum, snap.inBattle)
                if (snap.connected) battleSwitchAddrs = switchAddrsFor(snap.game)
                // SHINY ODDS: the foe party of a game it knows (retail FireRed / Emerald), handed to the engine.
                if (snap.connected) telemetry.knownShinyTarget().let { t ->
                    if (t != shinyTarget) {
                        shinyTarget = t
                        engine.setShinyTarget(t)
                        syncShinyAchievements()
                    }
                }
                // The menus' cursors, so a FIGHT / move tap goes straight to its cell (NativeConfig.actionCursor).
                if (snap.connected) battleCursorAddrs = telemetry.knownCursorAddrs()
                    ?.let { (action, move) ->
                        // Elite Redux's own menu: FIGHT / INFO over POKéMON / RUN, valued 0 / 3 over 1 / 2, moves in a column.
                        val menus = if (snap.game == com.pokedaisy.app.companion.data.GameKind.ELITE_REDUX)
                            BattleInputController.MenuCells.ELITE_REDUX else BattleInputController.MenuCells.GEN3
                        BattleInputController.CursorAddrs(action = action, move = move, menus = menus)
                    }
                // WILD ENCOUNTERS on a Game Boy game is held by the engine, frame by frame.
                if (snap.connected && romIsGameBoy) engine.gbField = gbField()
                // A shiny foe: RUN leaves Unbound's "Are you sure you want to run away?" to the player.
                engine.battleRunAsksFirst = snap.battleOpponent.any { it.shiny }
                if (snap.connected) battleGen1Menus = snap.game.isGen1 ||
                    snap.game.isGen2   // the same 2x2 menu and move column
                // party.isNotEmpty(), not just connected: `connected` flips true as
                // soon as the native addresses are found, which can be
                // well before the save's party has actually loaded into RAM (e.g.
                // still on the title/continue screen just after boot) - a dump
                // right then faithfully captures a real but useless "partyCount=0"
                // moment. Bit the first fixture capture this existed for.
                if (snap.connected && (snap.party.isNotEmpty() || forceFixtureDump)) dumpFixtureIfPending()
                // NUZLOCKE: the run follows every sample; its notices go on the game screen.
                com.pokedaisy.app.companion.Nuzlocke.onSample(snap).takeIf { it.isNotEmpty() }?.let { notes ->
                    runOnUiThread { showHud(notes.joinToString("\n"), NUZLOCKE_NOTICE_MS) }
                }
            }
            onSample = { startSample(this, afterSample) }   // runs on the emu thread
            // Every savestate carries the companion's data beside it, read fresh: a launch that
            // resumes from it shows that at once instead of LOADING (TelemetryStore.showCached).
            onStateSaved = { state ->
                telemetry.refresh()
                com.pokedaisy.app.companion.data.SnapshotCache.write(state, telemetry.forState())
            }
            onResumeResult = { loaded -> if (!loaded) { telemetry.dropCached(); runOnUiThread { waitForPlay() } } }
            battleStateNow = { telemetry.refreshBattleInputFast()?.second }   // the emu thread, before a RUN follow-up press
            onBattleInputSample = {   // runs on the emu thread, ~15x/sec
                telemetry.refreshBattleInputFast()?.let { (battler, state) ->
                    setBattleMenuState(battler, state, telemetry.battleMenuCursor())
                    // The companion draws from the full snapshot, sampled once a second: a new battle
                    // state gets one at once (the BATTLE tab at a battle's start, the buttons back each
                    // turn, the move grid after FIGHT), and so does every sample of a battle whose
                    // battlers can't be read yet - not up to a second later.
                    val shown = telemetry.snapshot.value
                    val waiting = state != com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE && !shown.showsBattle
                    if (battler != lastUiBattler || state != lastUiBattleState || waiting) {
                        lastUiBattler = battler
                        lastUiBattleState = state
                        startSample(this, afterSample)
                    }
                }
            }
            ffMaxSpeed = Prefs(this@PokeDaisyActivity).ffMaxSpeed
            ffMusicMode = Prefs(this@PokeDaisyActivity).ffMusicMode
            ffMode = Prefs(this@PokeDaisyActivity).ffMode
            rewindEntries = rewindEntries(Prefs(this@PokeDaisyActivity).rewind)
            restoreFastForwardToggled(Prefs(this@PokeDaisyActivity).ffToggled)
            restoreSpeedIndex(Prefs(this@PokeDaisyActivity).speedIndexFor(romKey))
            applyFieldPrefs(this)
        }
        // PLAYER LOOK: this ROM's scan and patches, in the background; the engine gets the pick once they're in.
        lookPatch = null
        lookRunner.load(r, crc, if (gba && romBlocked == null) gameCode else "", if (gba) SavesLocation.saveFor(this, Prefs(this), romFile) else null)
        setUpCoopPresence(r, gba, gameCode)
    }

    /**
     * Co-op presence (partner on the field): a [CoopPresence] exists only while a link is attached ([attachCoopLink], the co-op
     * session's) and the loaded ROM is retail English Emerald (BPEE with [RETAIL_EMERALD_SHA1]); a player without co-op hashes
     * nothing and runs nothing.
     */
    private var coopPresence: CoopPresence? = null
    private var coopReceive: kotlinx.coroutines.Job? = null
    private var coopRom: ByteArray? = null
    private val coopArt = java.util.concurrent.ConcurrentHashMap<Pair<Boolean, com.pokedaisy.app.companion.data.LookChar?>, ByteArray>()
    private val coopBuilding = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Boolean, com.pokedaisy.app.companion.data.LookChar?>>()

    private var coopCandidate: File? = null     // the loaded ROM when it could be retail Emerald (size and code), else null
    private var coopGeneration = 0             // bumped by every stop / start: a late hash result checks it

    private fun setUpCoopPresence(r: File, gba: Boolean, gameCode: String) {
        stopCoopPresence()
        coopCandidate = if (gba && gameCode == "BPEE" && r.length() == 16L * 1024 * 1024) r else null
        startCoopPresence()
    }

    /** The presence for the loaded ROM, when a link is attached and the ROM may be retail Emerald (the hash decides, off the UI thread). */
    private fun startCoopPresence() {
        val r = coopCandidate ?: return
        if (coopLink == null || coopPresence != null || !::engine.isInitialized) return
        val generation = ++coopGeneration
        // The 16 MB hash is off the UI thread; the presence is made once it matches (and this ROM is still the one loaded).
        val eng = engine
        Thread({
            val sha1 = runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-1")
                r.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
            if (sha1 == RETAIL_EMERALD_SHA1) runOnUiThread {
                if (!isDestroyed && !isFinishing && generation == coopGeneration && ::engine.isInitialized && engine === eng &&
                    romData == r && coopPresence == null && coopLink != null) createCoopPresence(r)
            }
        }, "pokedaisy-coop-sha1").start()
    }

    private fun createCoopPresence(r: File) {
        val dir = filesDir
        // The art is built on a background thread (it may scan the ROM, ~1 s): answered null until it's in, asked again later.
        val presence = CoopPresence(MgbaPartnerCore, android.os.SystemClock::elapsedRealtime) { female, look ->
            val key = female to look
            coopArt[key] ?: run {
                if (coopBuilding.add(key)) Thread({
                    runCatching {
                        val rom = coopRom ?: r.readBytes().also { coopRom = it }
                        com.pokedaisy.app.companion.data.coop.PartnerArt.build(dir, rom, female, look)?.let { coopArt[key] = it }
                    }
                    coopBuilding.remove(key)
                }, "pokedaisy-partner-art").start()
                null
            }
        }
        coopPresence = presence
        engine.coopPresence = presence
        applyCoopLink()
        lookGender.launch {
            while (coopPresence === presence) {
                presence.myLook = lookRunner.worn.value
                presence.myFemale = lookRunner.female ?: false
                kotlinx.coroutines.delay(500)
            }
        }
    }

    /** The co-op session's link: [CoopPresence] is made (and starts sending / drawing) with it, null stops and drops it. No-op off retail Emerald. */
    fun attachCoopLink(link: com.pokedaisy.app.companion.data.coop.CoopPresenceLink?) {
        coopLink = link
        if (link == null) { stopCoopPresence(); return }
        if (coopPresence == null) startCoopPresence() else applyCoopLink()
    }

    /** The kept link is the session's: a ROM switch makes the next [CoopPresence] with it. */
    private var coopLink: com.pokedaisy.app.companion.data.coop.CoopPresenceLink? = null

    private fun applyCoopLink() {
        val p = coopPresence ?: return
        val link = coopLink
        coopReceive?.cancel()
        coopReceive = null
        p.link = link
        if (link == null) { runOnEmu { p.stop() }; return }
        coopReceive = lookGender.launch { link.received.collect { p.onReceived(it) } }
    }

    private fun stopCoopPresence() {
        coopGeneration++
        val p = coopPresence ?: return
        coopReceive?.cancel()
        coopReceive = null
        coopPresence = null
        p.link = null
        if (::engine.isInitialized) engine.coopPresence = null
        coopArt.clear()
        coopRom = null
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
        e.autoRun = p.autoRun
        e.runIndoors = p.runIndoors && companionSettings.runIndoorsSupported
        e.wildFlag = wildFlag
        e.wildEncounters = p.wildEncounters
        e.ndsScale = p.dsResolution
        e.ndsCpuBoost = p.dsCpuBoost
        e.setReusableTms(tmSites, p.reusableTms)
        e.setInstantText(textPatch, p.instantText)
        e.setShinyTarget(shinyTarget)
        e.shinyOdds = shinyOddsPref()
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
                    coopStop()
                    stopEngineWithResume()
                }
                rom = next
                telemetry.reset()
                loadRom(next)
                if (running) startGame()
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
        hotkeys = Hotkeys.load(getExternalFilesDir(null) ?: filesDir)
        hotkeysOn = Prefs(this).hotkeysEnabled
        input.setControls(GbaControls.load(getExternalFilesDir(null) ?: filesDir))
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
        romKey.takeIf { it.isNotEmpty() }?.let { com.pokedaisy.app.cheats.CheatStore.forCrc(filesDir, it) }

    /** Hands the engine this ROM's enabled cheats (none with the master switch off); it loads
     * them before the first frame, or on the next one while the game runs. */
    private var coopCheatNoticed = false

    private fun syncCheats() {
        if (!::engine.isInitialized) return
        val enabled = Prefs(this).cheatsEnabled
        val coopOn = com.pokedaisy.app.companion.coop.CoopSession.gameOn || coopLock != null
        val text = com.pokedaisy.app.companion.coop.CoopSession.cheatsForEngine(enabled, coopOn) {
            cheatStore()?.load()?.let(com.pokedaisy.app.cheats.CheatFiles::coreText).orEmpty()
        }
        if (enabled && coopOn) {
            if (!coopCheatNoticed) { coopCheatNoticed = true; showHud(com.pokedaisy.app.companion.i18n.tr("Not while co-op is on.")) }
        } else coopCheatNoticed = false
        engine.setCheats(text)
    }

    /** Co-op calls queue here, in order: the ROM hash can take a moment, and a stop must never overtake its start. */
    private val coopExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "pokedaisy-coop-start").apply { isDaemon = true } }

    private fun coopStop() {
        // A joined battle's games are put back first (before the engine writes any suspend state), then the hook goes.
        if (!CoopBattles.stopNow()) coopLost = true   // fail closed: a game not confirmed put back writes no state
        CoopBattles.detach(coopApp)
        stopCoopLock()
        coopExec.execute { com.pokedaisy.app.companion.coop.CoopSession.stopGame() }
    }

    // --- co-op joined battles (CoopBattles.kt) ---

    /** The overlays over the game (HUD view): join prompt, waits, notices. */
    private val coopOverlay = com.pokedaisy.app.companion.ui.CoopOverlayState()
    private var coopHat = 0
    @Volatile private var coopSaveId: com.pokedaisy.app.companion.coop.CoopSaveId? = null

    /** A joined battle is running: the action [what] is refused with a notice (RESTART, SAVE / LOAD STATE, REWIND, PLAYER LOOK, ...). */
    private fun coopRefuses(): Boolean {
        if (!CoopBattles.busy) return false
        showHud(com.pokedaisy.app.companion.i18n.tr("Not during a co-op battle."))
        return true
    }

    /** Cheats are refused (with a notice) while co-op is on for this game: the start gate's rule, held mid-session. */
    private fun coopCheatsRefuse(turningOn: Boolean): Boolean {
        val on = com.pokedaisy.app.companion.coop.CoopSession.gameOn || coopLock != null
        if (!com.pokedaisy.app.companion.coop.CoopSession.refusesCheats(turningOn, on)) return false
        showHud(com.pokedaisy.app.companion.i18n.tr("Not while co-op is on."))
        return true
    }

    /** The retail Emerald game has the hook once its SHA-1 is known: hash done off the UI thread by [coopStart]. */
    private fun coopAttach(sha1: String?) {
        if (isDestroyed || isFinishing || !::engine.isInitialized || !engine.running) return
        val link = com.pokedaisy.app.companion.coop.CoopSession.link
        if (!CoopBattles.mayHook(sha1, FeatureFlags.coop(this), link)) return
        attachCoopLink(CoopPresenceChannel(link))
        CoopBattles.attach(coopApp, link, sha1)
    }

    @Volatile private var coopPairKeyCache: Pair<com.pokedaisy.app.companion.coop.CoopSaveId, String>? = null
    @Volatile private var coopPairKeyMissAt = -10_000L

    private val catchFile by lazy { com.pokedaisy.app.companion.data.coop.FileCatchStore(File(File(filesDir, "coop"), "catches.txt")) }

    private val coopApp = object : CoopApp {
        override val coopOn get() = FeatureFlags.coop(this@PokeDaisyActivity)
        override val emu = object : CoopEmu {
            override fun post(job: () -> Unit) { if (::engine.isInitialized) engine.postToEmu(job) }
            override val running get() = ::engine.isInitialized && engine.running
        }
        override val overlay get() = coopOverlay
        override fun core(): com.pokedaisy.app.companion.data.coop.CoopCore =
            MgbaCoopCore()
        override fun setHook(hook: CoopFrameHook?) { if (::engine.isInitialized) engine.coopBattles = hook }
        override val env = object : CoopGlueEnv {
            override val range get() = Prefs(this@PokeDaisyActivity).coopNearbyRange
            override fun mySpot(): com.pokedaisy.app.companion.data.coop.CoopJoinRules.Spot? {
                val s = MgbaPartnerCore.self() ?: return null
                if (s.size < 5 || s[0] == 0) return null
                return com.pokedaisy.app.companion.data.coop.CoopJoinRules.Spot(s[1], s[2], s[3] / 16, s[4] / 16)
            }
            override fun partnerSpot(): com.pokedaisy.app.companion.data.coop.CoopJoinRules.Spot? {
                val u = coopPresence?.lastReceived?.takeIf { it.valid } ?: return null
                return com.pokedaisy.app.companion.data.coop.CoopJoinRules.Spot(u.mapGroup, u.mapNum, u.fineX / 16, u.fineY / 16)
            }
            override fun hostParty(): List<ByteArray> {
                val n = (MgbaCore.pkReadBytes(CoopEmeraldRam.PLAYER_PARTY_COUNT, 1)?.get(0)?.toInt() ?: 0) and 0xFF
                val all = MgbaCore.pkReadBytes(CoopEmeraldRam.PLAYER_PARTY, 6 * CoopEmeraldRam.MON_SIZE) ?: return emptyList()
                return List(n.coerceIn(0, 6)) { all.copyOfRange(it * CoopEmeraldRam.MON_SIZE, (it + 1) * CoopEmeraldRam.MON_SIZE) }
            }
            override fun foeSpecies(): Int = MgbaCore.pkReadBytes(CoopEmeraldRam.ENEMY_PARTY, CoopEmeraldRam.MON_SIZE)
                ?.let { runCatching { com.pokedaisy.app.companion.data.save.Gen3BoxMon.read(it, 0).species }.getOrNull() } ?: 0
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
            private fun saveId(): com.pokedaisy.app.companion.coop.CoopSaveId? {
                coopSaveId?.let { return it }
                val r = rom ?: return null
                return com.pokedaisy.app.companion.coop.CoopSaveId.read(SavesLocation.saveFor(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), r))
                    ?.also { coopSaveId = it }
            }
            override fun identity(): com.pokedaisy.app.companion.data.coop.CoopIdentity {
                val id = saveId() ?: return com.pokedaisy.app.companion.data.coop.CoopIdentity(ByteArray(8) { 0xFF.toByte() }, 0, 0)
                return com.pokedaisy.app.companion.data.coop.CoopIdentity(id.name, if (lookRunner.female == true) 1 else 0, (id.secretId shl 16) or id.trainerId)
            }
            override fun beforeSave(): Boolean {
                val r = rom ?: return false
                val save = SavesLocation.saveFor(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), r)
                return coopBackup(save)
            }
            override fun busyChanged(busy: Boolean) { RetroAchievements.coopBattle = busy }
            override fun storyAt(): com.pokedaisy.app.companion.data.coop.CoopJoinRules.StoryAt {
                // The lock's hold names the event it parks the player at; otherwise the player's own spot decides.
                val held = (coopLock?.ui?.value?.waiting as? com.pokedaisy.app.companion.coop.lock.LockWait.AtStory)?.eventId ?: 0
                val event = if (held != 0) com.pokedaisy.app.companion.data.coop.CoopStory.byId(held)
                else com.pokedaisy.app.companion.data.coop.CoopStorySpots.at(mySpot())
                return com.pokedaisy.app.companion.data.coop.CoopStorySpots.storyAt(event) { id ->
                    com.pokedaisy.app.companion.data.coop.CoopStorySpots.trainerBeaten(id) { addr, n -> MgbaCore.pkReadBytes(addr, n) }
                }
            }
            override fun playTogetherOnly() = coopLock?.ui?.value?.locked == true
            /** Own save + the paired partner's key (never a display name: a rename or a missing name must not orphan catches). Resolved once per save. */
            override fun pairKey(): String {
                val sid = saveId() ?: return ""
                coopPairKeyCache?.let { if (it.first == sid) return it.second }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - coopPairKeyMissAt < 5_000L) return sid.key + ":unpaired"   // the pairs file is not read every tick
                val st = com.pokedaisy.app.companion.coop.CoopSession.link.state.value
                val live = when (st) {
                    is com.pokedaisy.app.companion.coop.CoopState.Connected -> st.partner
                    is com.pokedaisy.app.companion.coop.CoopState.Connecting -> st.partner
                    is com.pokedaisy.app.companion.coop.CoopState.Disconnected -> st.partner
                    else -> null
                }
                val peer = live?.peer?.hex
                    ?: com.pokedaisy.app.companion.coop.CoopSession.pairsList(this@PokeDaisyActivity).firstOrNull { it.ownSave == sid }?.partner?.peer?.hex
                if (peer == null) { coopPairKeyMissAt = now; return sid.key + ":unpaired" }
                return (sid.key + ":" + peer).also { coopPairKeyCache = sid to it }
            }
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

    /** A co-op stop could not be confirmed (the emu thread did not answer): from then on nothing - no resume state, no sleep state -
     * is written from what is in the engine, so the next launch boots from the save file. Cleared when a game starts. */
    @Volatile private var coopLost = false

    /** The engine stopped with its resume state, as a close always did - except after [coopLost]: then with none at all. */
    private fun stopEngineWithResume() {
        if (coopLost) {
            dropResumeState()
            engine.stop()
            return
        }
        states?.let { engine.stopWithSuspend(it.resumeFile) } ?: engine.stop()
    }

    private fun dropResumeState() {
        states?.resumeFile?.let {
            it.delete()
            com.pokedaisy.app.companion.data.SnapshotCache.fileFor(it).delete()
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
            for (e in hotkeys.onKey(code, down)) handleHotkey(e)
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
    private val coopLockGen = java.util.concurrent.atomic.AtomicInteger()

    private fun stopCoopLock() {
        coopLockGen.incrementAndGet()
        coopLockReceive?.cancel()
        coopLockReceive = null
        coopLockState.value = null
        val lock = coopLock ?: return
        coopLock = null
        if (::engine.isInitialized) engine.coopLock = null
        lock.releaseGuards()   // at once: a held game must never outlive its lock, whatever the emu thread is doing
        runOnEmu { lock.close() }
    }

    /** One [CoopLock] for the game just started, when its save has a pairing (off the UI thread: [gen] tells a stop from a late start). */
    private fun startCoopLock(sha1: String?, save: File, cheatsLoaded: Boolean, gen: Int) {
        val id = com.pokedaisy.app.companion.coop.CoopSaveId.read(save) ?: return
        if (com.pokedaisy.app.companion.coop.CoopSession.gate(sha1, id, true, cheatsLoaded) != null) return
        val pair = com.pokedaisy.app.companion.coop.CoopSession.pairsList(this).firstOrNull { it.ownSave.key == id.key } ?: return
        val link = com.pokedaisy.app.companion.coop.CoopSession.link
        val lock = CoopLock(
            link, com.pokedaisy.app.companion.coop.lock.CoopLockStore(File(File(filesDir, "coop"), "locks.json")),
            id.key, pair.partner.save.key, MgbaLockNative,
            {
                val b = MgbaCore.pkReadBytes(com.pokedaisy.app.companion.data.coop.COOP_PLAYER_PARTY_COUNT, 1)
                    ?: throw IllegalStateException("no core")
                b[0].toInt() and 0xFF
            },
            android.os.SystemClock::elapsedRealtime,
            { eventId -> CoopBattles.storyPending(eventId) },
        )
        runOnUiThread {
            if (isDestroyed || coopLockGen.get() != gen || !::engine.isInitialized) { lock.releaseGuards(); return@runOnUiThread }
            coopLock = lock
            coopLockPartner.value = pair.partner.name
            coopLockState.value = lock
            lock.start()   // adopted: only now does it take its guards
            engine.coopLock = lock
            coopLockReceive = lookGender.launch { link.messages.collect { lock.onMessage(it) } }
        }
    }

    /** The co-op session for the game being started: the ROM's SHA-1 (a retail dump counts as retail) off the UI thread. */
    private fun coopStart(r: File, save: File) {
        if (!FeatureFlags.coop(this)) return
        val prefs = Prefs(this)
        val cheatsLoaded = prefs.cheatsEnabled && cheatStore()?.load().orEmpty().any { it.enabled }
        val code = romCode
        val rev = romRev
        stopCoopLock()
        val gen = coopLockGen.get()
        coopExec.execute {
            if (!com.pokedaisy.app.companion.coop.CoopSession.mayBeRetailEmerald(code, RomArchive.romSize(r))) {
                com.pokedaisy.app.companion.coop.CoopSession.startForGame(applicationContext, prefs, null, save, cheatsLoaded)
                return@execute
            }
            val own = RomIdentity.sha1(r)
            val sha1 = if (own == com.pokedaisy.app.companion.coop.EMERALD_COOP_SHA1) own
                else com.pokedaisy.app.companion.data.RetailDumps.matchStream(code, rev, RomArchive.romSize(r)) { RomArchive.open(r) } ?: own
            com.pokedaisy.app.companion.coop.CoopSession.startForGame(applicationContext, prefs, sha1, save, cheatsLoaded)
            runOnUiThread { coopAttach(sha1) }   // joined battles + presence: retail English Emerald only
            startCoopLock(sha1, save, cheatsLoaded, gen)
        }
    }

    /** Starts the loaded ROM's engine, resuming where it was left; false with no ROM. */
    private fun startGame(): Boolean {
        val r = rom ?: return false
        val st = states ?: return false
        AudioEffects.load(Prefs(this))
        AudioEffectsTrack.open(this)
        val save = SavesLocation.saveFor(this, Prefs(this), r)
        engine.ndsCore = if (dsperate) MgbaCore.NDS_CORE_DSPERATE else MgbaCore.NDS_CORE_MELONDS   // DS CORE (pickDsCore)
        // Cheats may have changed in the library's Settings since the game was last up.
        syncCheats()
        // Back from sleep: the game held on its frame (onPause) and goes on from it. The state written as it went to
        // sleep is used up, as a resume's is once loaded.
        if (engine.sleeping) {
            CoopBattles.resume()   // [pauseGame] ended any joined battle before the sleep: battles may start again
            engine.wake()
            st.resumeFile.delete()
            com.pokedaisy.app.companion.data.SnapshotCache.fileFor(st.resumeFile).delete()
            RetroAchievements.progressFile(st.resumeFile).delete()
            return true
        }
        CoopGuards.reset()   // a new game starts with nothing held or blocked by the last one
        coopLost = false
        coopPairKeyCache = null; coopPairKeyMissAt = -10_000L
        coopSaveId = null   // identity() and ownName() follow the save of this game
        coopStart(r, save)
        // Auto-resume from wherever was written most recently: normally that's
        // the auto-suspend snapshot from the last close, but if that session
        // ended in a crash instead of a clean close (stale/missing resumeFile),
        // fall back to the newest manual save slot instead. Stage the winner
        // into resumeFile itself so the one-shot "load then delete" below only
        // ever touches that dedicated file, never a numbered slot.
        // A save file was just loaded from the library: boot from it, once.
        if (st.freshBootFile.exists()) {
            st.freshBootFile.delete()
            st.resumeFile.delete()
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
            st.resumeFile.delete()
            waitForPlay()
            engine.start(romData ?: r, save, null)
            view.postDelayed({ (engine.lastError ?: engine.lastNotice)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }, 1500)
            return true
        }
        // DS CORE changed since a DS game was left: the state its core left waits beside (SaveStates.parkedResume);
        // the core about to run takes its own back when the game has no other resume state.
        if (romIsNds && !st.resumeFile.let { it.isFile && it.length() > 0 }) {
            SaveStates.parkedResume(st.resumeFile, engine.ndsCore).takeIf { it.isFile }?.renameTo(st.resumeFile)
        }
        st.latestResumeSource()?.let { src ->
            if (src != st.resumeFile) runCatching {
                src.copyTo(st.resumeFile, overwrite = true)
                com.pokedaisy.app.companion.data.SnapshotCache.copy(src, st.resumeFile)
            }
        }
        val resume = st.resumeFile.takeIf { it.isFile && it.length() > 0 }
        // The companion's data as that state left it, until the game's own is read (a few KB).
        // Not over live data (back from HOME: the game was never closed).
        if (resume != null && !telemetry.snapshot.value.hasData) {
            com.pokedaisy.app.companion.data.SnapshotCache.read(resume)?.let { telemetry.showCached(it) }
        }
        // Resumed from a state: the player was in the game (a menu included). A boot: the title screen first.
        if (resume != null) nowPlaying() else waitForPlay()
        engine.start(romData ?: r, save, resume)
        if (!engine.running) telemetry.dropCached()
        view.postDelayed({ (engine.lastError ?: engine.lastNotice)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }, 1500)
        return true
    }

    /** The companion's sample decoder: one thread, one sample at a time (see [startSample]). */
    private val samplerThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "pokedaisy-sample").apply { isDaemon = true }
    }
    /** A sample is being decoded: its RAM copy ([sampleBuffers]) is in use. */
    private val sampling = java.util.concurrent.atomic.AtomicBoolean(false)
    /** One was asked for meanwhile (a battle's menu changed): it's taken as soon as that one is in. */
    @Volatile private var sampleAgain = false
    private var sampleBuffers: com.pokedaisy.app.companion.data.RamSnapshot.Buffers? = null

    /**
     * Emu thread: the companion's full sample. A GBA / DS game's RAM is copied here, between frames (a fraction of a
     * millisecond), and read and decoded on [samplerThread]; [after] then runs back on the emu thread. Decoding it here
     * took 3-15 ms between two frames on the RP6 / Thor, and each one past ~8 ms cost the screen a refresh: a stutter
     * once a second, four times a second in a battle, worst on a DS (GitHub #215). A Game Boy game is still read here.
     */
    private fun startSample(e: EmulatorEngine, after: (com.pokedaisy.app.companion.data.SnapshotView) -> Unit) {
        if (romIsGameBoy) {
            after(telemetry.refresh())
            return
        }
        if (!sampling.compareAndSet(false, true)) {
            sampleAgain = true
            return
        }
        val buffers = sampleBuffers?.takeIf { it.nds == romIsNds }
            ?: com.pokedaisy.app.companion.data.RamSnapshot.Buffers(romIsNds).also { sampleBuffers = it }
        val ram = com.pokedaisy.app.companion.data.RamSnapshot.capture(buffers)
        if (ram == null) {
            sampling.set(false)
            return
        }
        samplerThread.execute {
            val snap = try {
                telemetry.refresh(ram)
            } catch (t: Throwable) {
                Log.e("pokedaisy", "sample failed", t)
                null
            } finally {
                sampling.set(false)
            }
            e.postToEmu {
                if (snap != null) after(snap)
                if (sampleAgain) {
                    sampleAgain = false
                    startSample(e, after)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::touchControls.isInitialized) touchControls.releaseInputWindows()
        stopListeningForFold()
        samplerThread.shutdown()
        com.pokedaisy.app.companion.FeatureTips.available = { false }
        Hotkeys.dsActive = { false }
        // Gone while the game slept (onPause held it): stop it now, as a close would have.
        if (::engine.isInitialized && engine.running) {
            view.unbindCoreBlocking()
            coopStop()
            stopEngineWithResume()
        }
        ffMusicRenderer?.stop()
        ffMusicRenderer = null
        clickSound?.release()
        clickSound = null
        RetroAchievements.onUnlockSound = null
        unlockSound?.release()
        unlockSound = null
        stageRecomposer.cancel()
        stageRecomposing.cancel()
        stopCoopPresence()
        lookGender.cancel()
        playScope.cancel()
        com.pokedaisy.app.companion.ui.GameStart.waiting = false
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
        if (::engine.isInitialized && romKey.isNotEmpty()) {
            Prefs(this).setSpeedIndexFor(romKey, engine.speedIndex)
        }
        // The screen went off: the game holds on its frame with the core up and goes on from there on wake
        // (EmulatorEngine.sleep; startGame wakes it). HOME, another app or closing the game stop it as before.
        // A joined battle's games back (the partner's own game first) before any state is written; if that can't be confirmed,
        // nothing is written at all (coopLost: no sleep, no resume state - the next launch boots from the save file).
        if (!CoopBattles.stopNow()) coopLost = true
        val asleep = !(getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        val held = asleep && !coopLost && states?.let { engine.sleep(it.resumeFile) } == true
        if (!held) {
            // Before the engine frees the frame buffer the GL thread draws from.
            view.unbindCoreBlocking()
            coopStop()
            // (as stopEngineWithResume: no resume state after a co-op battle lost the own game)
            if (coopLost) { dropResumeState(); engine.stop() } else states?.let { engine.stopWithSuspend(it.resumeFile) } ?: engine.stop()
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
        if (((companionClosed && !romIsNds) || shownOnDisplay != Display.DEFAULT_DISPLAY) && target != null) {
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
        syncSingleScreen((target == null || romIsNds) && !debugMirror)
        if (swap) showMainCompanion() else showStage()
        if (target == null) return
        presentation = runCatching {
            DualScreenPresentation(this, target) { ctx ->
                if (swap) stage
                // A DS game: its touch screen down here (NdsBottomFrame), or the companion in its place - the player's
                // pick, switched from either (NdsBottomHost); the companion's window over the game above stays too.
                else if (romIsNds) NdsBottomHost(ctx, {
                    DualScreenPresentation.companionView(
                        ctx, telemetry, stateSlots, companionSettings, battleInput, companionBack, playClick, RetroAchievements,
                        initialTab = companionStartTab, initialSettingsRow = companionStartRow, statusBar = companionStatusBar, itemUse = itemUse, fly = flyAction, askForSupport = askForSupport, gamePick = gamePick, shareBestEffort = shareBestEffort,
                        keys = bottomKeys, focus = { bottomFocus.value },
                    )
                }, Prefs(this).dsBottomCompanion, onSwitch = { on -> Prefs(this).dsBottomCompanion = on }, fill = Prefs(this).dsTouchFill).also { dsBottom = it }
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
    private fun foldPosture(): com.pokedaisy.app.companion.FoldPosture? {
        val f = foldFeature ?: return null
        if (!::stage.isInitialized || !Prefs(this).foldLayout || debugMirror) return null
        val at = IntArray(2)
        stage.getLocationInWindow(at)
        val w = stage.width.takeIf { it > 0 } ?: window.decorView.width
        val h = stage.height.takeIf { it > 0 } ?: window.decorView.height
        val b = f.bounds
        return com.pokedaisy.app.companion.FoldPosture.of(
            halfOpened = f.state == androidx.window.layout.FoldingFeature.State.HALF_OPENED,
            separating = f.isSeparating,
            horizontal = f.orientation == androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL,
            left = b.left, top = b.top, right = b.right, bottom = b.bottom,
            stageX = at[0], stageY = at[1], stageW = w, stageH = h,
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
            com.pokedaisy.app.companion.ui.theme.DarkMode.isSystemDark(this)
        if (::sidePanel.isInitialized) syncSingleScreen(singleScreen)
        followDisplay()
    }

    /**
     * Moves this activity to [displayId] (CLOSE COMPANION while swapped, BACK from there). Android delivers that as a
     * new intent, with a pause and a resume around it: the game keeps running through those ([movePause]).
     */
    private fun moveToDisplay(displayId: Int) {
        movingSince = android.os.SystemClock.uptimeMillis()
        if (!Screens.moveToDisplay(this, displayId)) movingSince = 0L
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
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.decorView.post { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
    }

    // A move between two screens of one size changes no configuration; the moved activity gets the focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) followDisplay()
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

    /** The companion as this activity's content (the stage then goes to the second screen); for a DS game its touch
     *  screen with the companion behind the COMPANION toggle ([NdsBottomHost]), as on the bottom display unswapped. */
    private fun showMainCompanion() {
        val shown = mainCompanion
        if (shown != null && (shown is NdsBottomHost) == romIsNds) {
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
                keys = bottomKeys.takeIf { !romIsNds }, focus = { bottomFocus.value },
            )
        }
        val view = if (romIsNds) {
            NdsBottomHost(this, companion, Prefs(this).dsBottomCompanion, onSwitch = { on -> Prefs(this).dsBottomCompanion = on }, fill = Prefs(this).dsTouchFill).also { dsBottom = it }
        } else companion()
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
        performance.visibility = if (prefs.showPerformance) View.VISIBLE else View.GONE
        performanceShown.value = prefs.showPerformance
        hudOn.value = prefs.fieldHud
        hudStyle.value = prefs.fieldHudStyle
        quickShown.value = prefs.quickMenuActions
        battleButtonsOn.value = prefs.battleButtons
        battleHints.value = prefs.showHints && prefs.battleInfo
        battleStyle.value = com.pokedaisy.app.companion.BattleStyle.of(prefs.battleStyle)
        com.pokedaisy.app.companion.ui.AchievementIcons.shown = prefs.raIcons
        hudUpright.value = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        com.pokedaisy.app.companion.ui.CompanionStatusBar.shown = prefs.statusBar && prefs.statusBarOnCompanion
        val stageLayout = statusBar.parent as? GameStageLayout
        // WIDE on a game it doesn't know is ORIGINAL; otherwise the game takes the whole area and
        // the core draws the overworld into the room beside it.
        val aspect = prefs.gameAspect.let { if (it == GameAspect.WIDE && wideGame == WideScreen.OFF) GameAspect.ORIGINAL else it }
        // SCREEN > OVERLAY: not on a phone held upright, where the game is its own shape across the top, nor with
        // EXPAND, whose map fills the screen.
        // Nor on a DS game's layouts (DS LAYOUT), which put the game's own backdrop round it.
        view.overlay = if (stageLayout?.topAligned == true || aspect == GameAspect.WIDE || dsStageOn) null else when (val c = OverlayChoice.of(prefs.overlay)) {
            OverlayChoice.None -> null
            is OverlayChoice.BuiltIn -> EmulatorView.OverlaySource.BuiltIn(c.style)
            is OverlayChoice.Imported -> overlayStore.get(c.id)?.let { EmulatorView.OverlaySource.Imported(it) }
        }
        // With an overlay the view takes all the room and places the game in it itself.
        stageLayout?.stretch = aspect != GameAspect.ORIGINAL || view.overlay != null
        // A phone upright: WIDE fills everything above the companion, the map going on above and below the game.
        stageLayout?.expand = aspect == GameAspect.WIDE
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
            prefs.gbaColors && companionShaders,
            if (companionShaders) prefs.screenFilter else ScreenFilter.NONE,
            ScreenShaders.gridFor(prefs.screenFilter, prefs.gridStrength),
            prefs.gbaColors && !com.pokedaisy.app.companion.LightCompanion.on,
            if (com.pokedaisy.app.companion.LightCompanion.on) ScreenFilter.NONE else prefs.screenFilter,
        )
        view.screenEffect = ScreenShaders.effectFor(prefs.screenFilter)
        view.screenGrid = ScreenShaders.gridFor(prefs.screenFilter, prefs.gridStrength)
        view.smoothing = prefs.smoothing
        syncDsStage(aspect)
    }

    /** The DS layouts are on: a DS game on one screen (or SINGLE SCREEN), held sideways, the game in this activity. */
    private val dsStageOn: Boolean
        get() = romIsNds && secondScreen() == null && !debugMirror && !companionOverGame.value && foldActive == null &&
            resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT

    /**
     * SETTINGS > DS LAYOUT onto the stage ([GameStageLayout.dsArrange]): where the game and its touch screen go, the
     * game's backdrop around them ([aspect] ORIGINAL keeps the game alone at its 4:3; EXPAND / STRETCH fill), and whether
     * the touch screen floats in ([dsFloats]). Off: the stage as for any other game.
     */
    private fun syncDsStage(aspect: GameAspect) {
        if (!::stage.isInitialized || !::dsBackdropView.isInitialized) return
        val on = dsStageOn
        val chosen = Prefs(this).dsLayout.forGame(com.pokedaisy.app.companion.DsLayout.followed(romCode) || romOrigin)
        // A menu screen (the party, the bag, the Pokédex...) has something to say on both screens: side by side then, not
        // the touch screen over the top one (whose 4:3 left bars beside it in EXPAND).
        val layout = if (dsMenuUp && chosen.floats) com.pokedaisy.app.companion.DsLayout.SIDE_BY_SIDE else chosen
        // EXPAND / STRETCH fill the screen only where EXPAND has the scene to widen; elsewhere the backdrop's beside it.
        val fill = aspect != GameAspect.ORIGINAL && (dsWide || aspect == GameAspect.STRETCH)
        stage.dsArrange = if (on) dsArranger(layout, fill) else null
        // PICTURE IN PICTURE's inset stands off the game behind a white edge.
        val pip = layout == com.pokedaisy.app.companion.DsLayout.PICTURE_IN_PICTURE
        val frame = if (on && pip) dp(2) else 0
        stage.dsTouchFrame = frame
        dsTouchView.setPadding(frame, frame, frame, frame)
        dsTouchView.setBackgroundColor(if (pip) Color.WHITE else Color.TRANSPARENT)
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
        dsArrangerFn?.takeIf { dsArrangerKey == key }?.let { return it }
        val fn = { w: Int, h: Int -> com.pokedaisy.app.companion.DsLayouts.arrange(layout, w, h, fill) }
        dsArrangerKey = key
        dsArrangerFn = fn
        return fn
    }

    /** The DS LAYOUT hotkey: the next layout, saved as SETTINGS > DS LAYOUT's, and its name over the game. A game whose
     * touch screen isn't followed skips ONE SCREEN (it would look as PICTURE IN PICTURE does there). */
    private fun nextDsLayout() {
        if (!dsStageOn) return
        val prefs = Prefs(this)
        val followed = com.pokedaisy.app.companion.DsLayout.followed(romCode) || romOrigin
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
        input.setPress(MgbaCore.Key.B)
        window.decorView.postDelayed({ input.setPress(0) }, DS_B_PRESS_MS)
    }

    /** The TOUCH SCREEN hotkey and the HUD's TOUCH: the floating touch screen opened by hand, or closed again. */
    private fun toggleDsTouch() {
        if (!dsFloats.value) return
        dsWindowManual.value = !dsWindowManual.value
    }

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
                    if (menuApp && field != null) com.pokedaisy.app.companion.ui.DsShotBackdrop(field, blurred = false, modal = modal)
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
        dsWindowView = ComposeView(this).apply {
            setContent {
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
                if (romIsNds) androidx.compose.runtime.CompositionLocalProvider(
                    com.pokedaisy.app.companion.ui.LocalClickSound provides playClick,
                    com.pokedaisy.app.companion.ui.LocalGameSilent provides { battleInput.gameSilent },
                ) {
                    val snap by telemetry.snapshot.collectAsState()
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer { translationY = (fieldHud.top - dsWindowView.top).toFloat() },
                    ) {
                        com.pokedaisy.app.companion.ui.DsBattleMenu(
                            snap, dsBattleMenu, battleInput, coreFrame.value, shown = dsStripShown.value, active = dsStripActive.value,
                            gameFills = dsFill.value, hints = battleHints.value,
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
        }
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
    private lateinit var performance: ComposeView

    /** The battery's temperature from its broadcast (°C), for the performance panel. */
    @Volatile private var batteryTempC: Float? = null

    /** FPS / CPU is on: off, the overlay composes nothing (its half-second readings went on behind a hidden view). */
    private val performanceShown = androidx.compose.runtime.mutableStateOf(false)

    private fun buildPerformanceOverlay(): ComposeView = ComposeView(this).apply {
        visibility = View.GONE
        setContent {
            if (!performanceShown.value) return@setContent
            val reading by produceState(com.pokedaisy.app.companion.ui.PerfReading()) {
                val cpu = CpuUsage()
                val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val cores = Runtime.getRuntime().availableProcessors()
                // Cumulative counters, read every half second: each reading is the difference.
                var at = System.nanoTime()
                var frames = 0L; var work = 0L; var shown = 0L; var late = 0L
                var samples = 0L; var checks = 0L
                fun take() {
                    at = System.nanoTime()
                    if (::engine.isInitialized) { frames = engine.framesRun; work = engine.workNanos; engine.takeMaxWorkNanos() }
                    shown = view.framesShown; late = view.lateFrames
                    samples = telemetry.samplesTaken; checks = telemetry.battleChecksTaken
                }
                take()
                withContext(Dispatchers.IO) { cpu.sample() }
                while (true) {
                    delay(500)
                    if (!::engine.isInitialized) continue
                    val e = engine
                    val secs = (System.nanoTime() - at) / 1e9
                    val df = (e.framesRun - frames).coerceAtLeast(0)
                    val dw = (e.workNanos - work).coerceAtLeast(0)
                    val maxWork = e.takeMaxWorkNanos()
                    val cpuPct = withContext(Dispatchers.IO) { cpu.sample() }
                    val rt = Runtime.getRuntime()
                    value = com.pokedaisy.app.companion.ui.PerfReading(
                        fps = (df / secs).toFloat(),
                        shownFps = ((view.framesShown - shown).coerceAtLeast(0) / secs).toFloat(),
                        latePerSecond = ((view.lateFrames - late).coerceAtLeast(0) / secs).toFloat(),
                        frameMs = if (df > 0) (dw / df / 1e6).toFloat() else 0f,
                        frameMaxMs = (maxWork / 1e6).toFloat(),
                        emulationLoad = (dw / 1e9 / secs * 100).toInt().coerceIn(0, 100),
                        cpu = cpuPct, cores = cores,
                        refreshHz = e.refreshHz, refreshesPerFrame = e.refreshesPerFrame, timerFallback = e.timerFallback,
                        speed = e.currentSpeed,
                        audioDropouts = e.audioUnderruns,
                        batteryTempC = batteryTempC,
                        thermal = if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1,
                        memoryMb = ((rt.totalMemory() - rt.freeMemory() + android.os.Debug.getNativeHeapAllocatedSize()) shr 20).toInt(),
                        snapshotsPerSecond = ((telemetry.samplesTaken - samples).coerceAtLeast(0) / secs).toFloat(),
                        battleChecksPerSecond = ((telemetry.battleChecksTaken - checks).coerceAtLeast(0) / secs).toFloat(),
                    )
                    frames = e.framesRun; work = e.workNanos; shown = view.framesShown; late = view.lateFrames
                    samples = telemetry.samplesTaken; checks = telemetry.battleChecksTaken
                    at = System.nanoTime()
                }
            }
            // The view fills the stage, and Compose hands its root that whole size as the least it may take: a Box
            // takes it, so the overlay inside keeps its own size (else its tap, FPS / CPU's open / close, was everywhere).
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                com.pokedaisy.app.companion.ui.PerformanceOverlay(reading)
            }
        }
    }

    /**
     * The FIELD HUD ([com.pokedaisy.app.companion.ui.FieldHud]) over the game: shown while the engine says the
     * player is on the field and the snapshot agrees (a game read, no battle), SETTINGS > HUD is on and the
     * screen isn't held upright. Tracked by CompanionColors like the status bar: it sits on the game.
     */
    private fun buildFieldHud(): ComposeView = CompanionColors.track(ComposeView(this)).apply {
        setContent {
            val snap by telemetry.snapshot.collectAsState()
            val engine by hudEngine.collectAsState()
            val idle = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
            val onField by (engine?.onField ?: idle).collectAsState()
            val shown = hudOn.value && !hudUpright.value && onField && snap.hasData && !snap.unsupported && !snap.inBattle
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
                    sidePanel.tabRaised = dsMenuMode != 0
                }
            }
            // BATTLE BUTTONS: one screen held sideways, a single battle the game's touch input can steer (its state read
            // at least once this battle - it reads NONE in games without it).
            var battleRead by remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(snap.inBattle, snap.battleInputState) {
                battleRead = snap.inBattle && (battleRead || snap.battleInputState != com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE)
            }
            // A DS game: only where DS LAYOUT floats its touch screen (the others, PICTURE IN PICTURE's inset too, show the
            // game's own panel), and out of the way while the floating touch screen is up (a bag, a party, a prompt).
            val dsIdle = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
            val dsWanted by (engine?.dsTouchWanted ?: dsIdle).collectAsState()
            val dsWindowUp = dsFloats.value && (dsWanted || dsWindowManual.value)
            val buttonsWanted = battleButtonsOn.value && hudSingleScreen.value && !hudUpright.value && snap.hasData && !snap.unsupported &&
                snap.inBattle && !snap.isDoubleBattle && (!romIsNds || dsFloats.value && !dsPip.value)
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
                com.pokedaisy.app.companion.ui.FieldHud(
                    snap, shown && !dsWindowUp, hudStyle.value,
                    modifier = androidx.compose.ui.Modifier.graphicsLayer { alpha = hudAlpha },
                    clearTop = hudClear.value.first, clearBottom = hudClear.value.second,
                    itemUse = itemUse, fly = flyAction,
                    onOpenCompanion = if (panelClosed) ({ sidePanel.openPanel() }) else null,
                    onTouchScreen = if (romIsNds && dsFloats.value) ::toggleDsTouch else null,
                    dsCorner = dsCornerTaken.value,
                    dsClearBottom = dsHudClear.value,
                    frame = hudFrame.value ?: hudTouchFrame.value,
                    avoid = hudAvoid.value,
                    closePanels = hudCloseTick.intValue,
                    openPanel = hudOpenPanel.value, openTick = hudOpenTick.intValue,
                    onTap = { com.pokedaisy.app.companion.FeatureTips.used(TipId.HUD) },
                    onPanel = { hudPanelOpen.value = it },
                )
                // A DS game: the game's own buttons over the end of its text box, driven like Gen 3's menus.
                if (romIsNds) {
                    // A DS battle's menu is drawn by the floating window's view, under its dim ([buildDsStageViews]).
                    androidx.compose.runtime.SideEffect {
                        dsStripShown.value = buttonsWanted && battleRead
                        dsStripActive.value = !dsWindowUp
                    }
                } else com.pokedaisy.app.companion.ui.BattleButtons(
                    snap, buttons, battleInput, battleHints.value,
                    modifier = androidx.compose.ui.Modifier.graphicsLayer { alpha = hudAlpha },
                    core = coreFrame.value,
                    clearTop = hudClear.value.first, clearBottom = hudClear.value.second,
                    onWindow = { battlePicking.value = it },
                )
                com.pokedaisy.app.companion.ui.QuickMenu(quickMenu, snap, quickActions, quickShown.value, hudStyle.value.look)
                // Co-op joined battles: the join prompt, the waits, the notices - over the game, HUD on or off.
                com.pokedaisy.app.companion.ui.CoopBattleOverlays(coopOverlay)
                CoopLockLayer()
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
                    if (!companionSeen) {
                        val clearBottom = with(androidx.compose.ui.platform.LocalDensity.current) { hudClear.value.second.toDp() }
                        com.pokedaisy.app.companion.ui.AchievementIndicators(
                            RetroAchievements,
                            androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(end = 8.dp, bottom = clearBottom + 8.dp),
                        )
                    }
                }
            }
            // Tips' clock: field play time, the moments, whether the field is calm ([tickTips]).
            androidx.compose.runtime.LaunchedEffect(Unit) {
                var last = android.os.SystemClock.uptimeMillis()
                while (true) {
                    kotlinx.coroutines.delay(500)
                    val now = android.os.SystemClock.uptimeMillis()
                    runCatching { tickTips((now - last).coerceAtMost(1000)) }.onFailure { Log.w("pokedaisy", "tips", it) }
                    runCatching { syncFlight() }.onFailure { Log.w("pokedaisy", "flight", it) }
                    last = now
                }
            }
        }
    }

    /**
     * PLAY TOGETHER ONLY over the game (in the HUD's ComposeView, so it shows with the HUD off): the waiting screen while the
     * lock holds the game, the partner's proposal (ACCEPT / NO) where the screen over the game can be touched - one screen; with two the
     * SETTINGS row carries it and a HUD line says so - and the lock's notices, each shown once.
     */
    @androidx.compose.runtime.Composable
    private fun CoopLockLayer() {
        val lock = coopLockState.value ?: return
        val ui by lock.ui.collectAsState()
        val notice by lock.notice.collectAsState()
        val partner = coopLockPartner.value
        val oneScreen = secondScreen() == null
        ui.waiting?.let { w -> com.pokedaisy.app.companion.ui.CoopWaitOverlay(w, partner) { exitGame() } }
        var dismissed by remember(ui.partnerProposed) { androidx.compose.runtime.mutableStateOf(false) }
        if (ui.partnerProposed && oneScreen && !dismissed) {
            com.pokedaisy.app.companion.ui.CoopLockProposal(partner, lock) { dismissed = true }
        }
        androidx.compose.runtime.LaunchedEffect(ui.partnerProposed) {
            if (ui.partnerProposed && !oneScreen) showHud(tr("{0} wants to PLAY TOGETHER ONLY.", partner))
        }
        androidx.compose.runtime.LaunchedEffect(notice) {
            val text = when (notice) {
                LockNotice.PARTNER_TURNED_OFF -> tr("Your partner turned off PLAY TOGETHER ONLY.")
                LockNotice.NO_STORY_HOLD -> tr("Story events can't wait for your partner in this game")
                null -> return@LaunchedEffect
            }
            showHud(text)
            lock.clearNotice()
        }
    }

    /** The touch controls' button rows, for the FIELD HUD to keep clear of. */
    private fun syncHudClear() {
        // EDIT LAYOUT hides the controls while their stand-ins move: the HUD stays where it was until DONE (GitHub #493).
        if (touchEditor != null) return
        if (!::touchControls.isInitialized) return
        hudClear.value = touchControls.clearTop to touchControls.clearBottom
        // EXPAND held sideways: the HUD keeps to the game's middle columns, where the clearance is measured, not under
        // the buttons in the margins beside them (GitHub #493). In the HUD's own coordinates.
        if (!::fieldHud.isInitialized) return
        val at = IntArray(2).also(touchControls::getLocationInWindow)
        val hudAt = IntArray(2).also(fieldHud::getLocationInWindow)
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
    private val companionStatusBar: @androidx.compose.runtime.Composable () -> Unit = { StatusBarContent() }

    @androidx.compose.runtime.Composable
    private fun StatusBarContent() {
            val snap by telemetry.snapshot.collectAsState()
            val time by produceState(clockText()) {
                while (true) {
                    delay(60_000L - System.currentTimeMillis() % 60_000L)
                    value = clockText()
                }
            }
            // The Library's name; a ROM the library hasn't named yet (a frontend launch) is
            // recognised here, off the main thread.
            val name by produceKeyedState(rom?.let { GameTitles.label(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), it) }.orEmpty(), rom) {
                val r = rom ?: return@produceKeyedState
                if (withContext(Dispatchers.IO) { GameTitles.identify(this@PokeDaisyActivity, listOf(r)) }) {
                    value = GameTitles.label(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), r)
                }
            }
            GameStatusBar(
                gameName = name,
                location = snap.location.mapSecName.takeIf { snap.connected },
                money = snap.money.takeIf { snap.connected },
                time = time,
            )
    }

    private fun clockText(): String =
        android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date())

    /** Show the on-screen controls per SETTINGS > TOUCH CONTROLS > SHOW (AUTO: only with no controller), in its style. */
    private fun syncTouchControls() {
        if (!::touchControls.isInitialized) return
        val style = Prefs(this).touchControlsStyle
        val show = touchEditor == null && when (style.show) {
            com.pokedaisy.app.companion.TouchShow.ALWAYS -> true
            com.pokedaisy.app.companion.TouchShow.NEVER -> false
            com.pokedaisy.app.companion.TouchShow.AUTO -> !physicalPadUsed && !Controllers.anyConnected()
        }
        touchControls.style = style
        touchControls.portrait = touchUpright
        touchControls.gameAspect = if (::stage.isInitialized) stage.aspect else 1.5f
        if (::engine.isInitialized) touchControls.ffOn = engine.fastForwardToggled
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
        if (romIsNds) off += com.pokedaisy.app.companion.TouchButton.REWIND
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
    private val skinStore by lazy { com.pokedaisy.app.companion.DeltaSkinStore(java.io.File(filesDir, "skins")) }

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
        stage.skinHost = touchControls
        if (skin == null || touchControls.portrait) {
            skinGameFor = null
            stage.skinGame = null
            return
        }
        val fn = skinGameFor?.takeIf { it.first == skin.id + ":" + skin.dir.lastModified() }?.second ?: run {
            val f: (Int, Int) -> androidx.compose.ui.geometry.Rect? = { w, h ->
                com.pokedaisy.app.companion.ui.skinFit(skin, w.toFloat(), h.toFloat(), portrait = false)?.game
            }
            skinGameFor = skin.id + ":" + skin.dir.lastModified() to f
            f
        }
        stage.skinGame = fn
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
        if (touchEditor != null || keyCapture.request != null || quickMenu.open || hudPanelOpen.value || battlePicking.value) return false
        if (::sidePanel.isInitialized && sidePanel.overGame) return false
        if (coopOverlay.takesKeys || coopOverlay.linkWait != null) return false
        if (coopLockState.value?.ui?.value?.let { it.waiting != null || it.partnerProposed } == true) return false
        if (expandOfferUp.value || bottomFocus.value || companionOverGame.value) return false
        if (!tipsOnCompanion.value && com.pokedaisy.app.companion.FeatureTips.scheduler?.current != null) return false
        if (!tipsOnCompanion.value && com.pokedaisy.app.companion.ui.FlyStyleAsk.showing) return false
        return true
    }

    /** When the touch controls' FF went down: a short tap turns FF on / off, a longer press is FF while held. */
    private var touchFfDownAt = 0L

    /** The touch controls' extras: FF, the QUICK MENU, SAVE / LOAD STATE (the hotkeys' own actions). */
    private fun onTouchAction(button: com.pokedaisy.app.companion.TouchButton, down: Boolean) {
        if (!::engine.isInitialized) return
        when (button) {
            com.pokedaisy.app.companion.TouchButton.FF -> {
                if (down) {
                    touchFfDownAt = android.os.SystemClock.uptimeMillis()
                    engine.setFastForwardHeld(true)
                } else {
                    engine.setFastForwardHeld(false)
                    if (android.os.SystemClock.uptimeMillis() - touchFfDownAt < TOUCH_FF_TAP_MS) engine.toggleFastForward()
                }
            }
            com.pokedaisy.app.companion.TouchButton.QUICK_MENU -> if (down) handleHotkey(Hotkeys.Event(Hotkeys.Action.QUICK_MENU, true))
            com.pokedaisy.app.companion.TouchButton.SAVE_STATE -> if (down) handleHotkey(Hotkeys.Event(Hotkeys.Action.SAVE_STATE, true))
            com.pokedaisy.app.companion.TouchButton.LOAD_STATE -> if (down) handleHotkey(Hotkeys.Event(Hotkeys.Action.LOAD_STATE, true))
            com.pokedaisy.app.companion.TouchButton.ZOOM -> if (down) handleHotkey(Hotkeys.Event(Hotkeys.Action.ZOOM, true))
            com.pokedaisy.app.companion.TouchButton.REWIND -> handleHotkey(Hotkeys.Event(Hotkeys.Action.REWIND_HOLD, down))
            com.pokedaisy.app.companion.TouchButton.X -> touchKey(KeyEvent.KEYCODE_BUTTON_X, down)
            com.pokedaisy.app.companion.TouchButton.Y -> touchKey(KeyEvent.KEYCODE_BUTTON_Y, down)
            else -> {}
        }
    }

    /** The game's screen as the touch controls see it: the stage, the game's picture, the controls' area (an upright
     * phone's gap, or beside a docked side panel), the companion under the game upright. Null before it's laid out. */
    private fun measuredTouchStage(): com.pokedaisy.app.companion.TouchStage? {
        if (!::stage.isInitialized || stage.width == 0 || stage.height == 0) return null
        val w = stage.width.toFloat()
        val h = stage.height.toFloat()
        val lp = touchControls.layoutParams as android.widget.FrameLayout.LayoutParams
        val area = androidx.compose.ui.geometry.Rect(lp.leftMargin.toFloat(), lp.topMargin.toFloat(), w - lp.rightMargin, h - lp.bottomMargin)
        val aspect = stage.aspect
        val portrait = touchUpright
        // EXPAND: the map fills the game's whole view.
        val game = if (::portraitPanel.isInitialized && portraitPanel.expand) {
            androidx.compose.ui.geometry.Rect(view.left.toFloat(), view.top.toFloat(), view.right.toFloat(), view.bottom.toFloat())
        } else hudFrame.value?.translate(view.left.toFloat(), view.top.toFloat()) ?: run {
            val gw = minOf(view.width.toFloat(), view.height * aspect)
            val gh = gw / aspect
            val l = view.left + (view.width - gw) / 2
            val t = view.top + if (portrait) 0f else (view.height - gh) / 2
            androidx.compose.ui.geometry.Rect(l, t, l + gw, t + gh)
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
    private var touchEditor: ComposeView? = null
    private val touchRev = androidx.compose.runtime.mutableIntStateOf(0)
    private val touchEditBack = com.pokedaisy.app.companion.ui.CompanionBack()

    private fun openTouchEditor() {
        if (touchEditor != null) return
        val st = measuredTouchStage() ?: return
        if (::sidePanel.isInitialized) sidePanel.closeWindow()
        val editor = ComposeView(this).apply {
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(com.pokedaisy.app.companion.ui.LocalCompanionBack provides touchEditBack) {
                    com.pokedaisy.app.companion.ui.TouchLayoutEditor(
                        st, Prefs(this@PokeDaisyActivity).touchControlsStyle, com.pokedaisy.app.companion.ui.rememberGbaTextMetrics(),
                        onDone = { layout ->
                            val prefs = Prefs(this@PokeDaisyActivity)
                            prefs.touchControlsStyle = prefs.touchControlsStyle.withLayout(st.portrait, layout)
                            closeTouchEditor()
                        },
                    )
                }
            }
        }
        touchEditor = editor
        input.setTouchBits(0)
        stage.addView(editor, android.widget.FrameLayout.LayoutParams(-1, -1))
        syncTouchControls()
    }

    private fun closeTouchEditor() {
        val editor = touchEditor ?: return
        touchEditor = null
        stage.removeView(editor)
        touchRev.intValue++
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
        if (hotkeyTakes(event.keyCode, down)) return true
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
    private fun dsCompanionKeyed() = romIsNds && dsBottom != null && presentation?.isShowing == true && !Prefs(this).swapScreens

    /** TO COMPANION can take the buttons down now: a GBA / Game Boy game on the field with its controls free (no text box, script or
     * START menu, where the field read knows), the companion on the other screen, nothing else holding the buttons. */
    private fun canFocusBottom(): Boolean {
        if (romIsNds || debugMirror || companionClosed || companionOverGame.value) return false
        // The other screen, or the companion under the game on a phone held upright (GitHub discussion #308).
        if (presentation?.isShowing != true && !(::portraitPanel.isInitialized && portraitPanel.enabled)) return false
        if (!::engine.isInitialized || !engine.onField.value || engine.fieldLocked) return false
        if (quickMenu.open || touchEditor != null || keyCapture.request != null) return false
        if (::sidePanel.isInitialized && sidePanel.windowOpen) return false
        val snap = telemetry.snapshot.value
        return snap.hasData && !snap.inBattle
    }

    private fun setBottomFocus(on: Boolean) {
        if (bottomFocus.value == on) return
        bottomFocus.value = on
        // A DS game's second screen goes back to its touch screen with the buttons; a hidden upright companion comes up.
        if (!on && romIsNds) dsBottom?.show(false)
        if (on && ::portraitPanel.isInitialized && portraitPanel.enabled) portraitPanel.reveal()
        // The player's held keys let go in the game (which runs on), and the other way round.
        input.releaseAll()
        bottomHat = 0
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
        if (::hotkeys.isInitialized && hotkeysOn) for (e in hotkeys.onKey(code, down)) if (!e.pressed || e.action == Hotkeys.Action.COMPANION) handleHotkey(e)
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
            bits = (if (hx < -0.5f) MgbaCore.Key.LEFT else 0) or (if (hx > 0.5f) MgbaCore.Key.RIGHT else 0) or
                (if (hy < -0.5f) MgbaCore.Key.UP else 0) or (if (hy > 0.5f) MgbaCore.Key.DOWN else 0)
        }
        val pressed = bits and bottomHat.inv()
        bottomHat = bits
        menuKeyFor(pressed)?.let { bottomKeys.key(it) }
        return true
    }

    // --- the companion's window (one screen, SidePanel) -----------------------------

    /** Keys pressed while the window was open: the game doesn't see them until they're let go (the B that closed it). */
    private val menuHeld = HashSet<Int>()
    /** The joystick's D-pad / stick directions while the window is open, so a new one is a press. */
    private var menuHat = 0

    /** The window came up (the buttons are the companion's: the player's held keys let go in the game, which runs
     * on) or went away (the buttons are the game's again). */
    private fun onCompanionWindow(open: Boolean) {
        input.releaseAll()
        menuHat = 0
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
        if (::hotkeys.isInitialized && hotkeysOn) for (e in hotkeys.onKey(code, down)) if (!e.pressed) handleHotkey(e)
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
            for (e in hotkeys.onKey(code, down)) handleHotkey(e)
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
        bits and MgbaCore.Key.UP != 0 -> MenuKey.UP
        bits and MgbaCore.Key.DOWN != 0 -> MenuKey.DOWN
        bits and MgbaCore.Key.LEFT != 0 -> MenuKey.LEFT
        bits and MgbaCore.Key.RIGHT != 0 -> MenuKey.RIGHT
        bits and MgbaCore.Key.A != 0 -> MenuKey.A
        bits and MgbaCore.Key.B != 0 -> MenuKey.B
        bits and MgbaCore.Key.L != 0 -> MenuKey.L
        bits and MgbaCore.Key.R != 0 -> MenuKey.R
        bits and MgbaCore.Key.START != 0 -> MenuKey.START
        bits and MgbaCore.Key.SELECT != 0 -> MenuKey.SELECT
        bits and MgbaCore.Key.X != 0 -> MenuKey.X
        bits and MgbaCore.Key.Y != 0 -> MenuKey.Y
        else -> null
    }

    /** A joystick's D-pad / stick while the window is open: each direction that comes on is a press. */
    private fun menuMotion(event: MotionEvent): Boolean {
        var bits = GbaInput.motionBits(event) ?: return false
        // A screen that steers with the stick itself (HeartGold / SoulSilver's map) takes it; the hat stays the D-pad.
        if (sidePanel.menuStick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))) {
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            bits = (if (hx < -0.5f) MgbaCore.Key.LEFT else 0) or (if (hx > 0.5f) MgbaCore.Key.RIGHT else 0) or
                (if (hy < -0.5f) MgbaCore.Key.UP else 0) or (if (hy > 0.5f) MgbaCore.Key.DOWN else 0)
        }
        val pressed = bits and menuHat.inv()
        menuHat = bits
        menuKeyFor(pressed)?.let { sidePanel.menuKey(it) }
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
        if (touchEditor != null) { touchEditBack.back(); return }
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
    private fun hotkeyTakes(keyCode: Int, down: Boolean): Boolean {
        if (!::hotkeys.isInitialized || !hotkeysOn) return false
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
        if (quickStickHold) {
            if (GbaInput.motionBits(event)?.let { it != 0 } == true) return true
            quickStickHold = false
        }
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

    /** A companion rebind started / ended: its popup over the game, and nothing left held - the game's keys, a held
     * FF / slow-mo / rewind, a half-pressed chord, the right stick's look-around - since their releases go to it. */
    private fun onKeyCapture(on: Boolean) {
        if (::keyCapturePopup.isInitialized) keyCapturePopup.visibility = if (on) View.VISIBLE else View.GONE
        input.releaseAll()
        rtDown = false; ltDown = false
        if (::view.isInitialized) { view.camera.stickX = 0f; view.camera.stickY = 0f }
        if (::engine.isInitialized) { engine.setFastForwardHeld(false); engine.setSlowmoHeld(false); engine.setRewindHeld(false) }
        hotkeys = Hotkeys.load(getExternalFilesDir(null) ?: filesDir)
    }

    /** Turns the hotkeys on/off from the companion. Off lets go of any held one (FF / slow-mo
     * hold) and forgets the keys held so far, so a chord can't fire half-pressed once back on. */
    private fun setHotkeysOn(on: Boolean) {
        hotkeysOn = on
        if (::engine.isInitialized) { engine.setFastForwardHeld(false); engine.setSlowmoHeld(false) }
        hotkeys = Hotkeys.load(getExternalFilesDir(null) ?: filesDir)
    }

    private fun handleHotkey(e: Hotkeys.Event) {
        if (!::engine.isInitialized) return
        when (e.action) {
            Hotkeys.Action.SAVE_STATE -> if (e.pressed) if (!coopRefuses()) engine.requestSaveState(engine.currentSlot)
            Hotkeys.Action.LOAD_STATE -> if (e.pressed) if (!coopRefuses()) engine.requestLoadState(engine.currentSlot)
            Hotkeys.Action.UNDO_SAVE -> if (e.pressed) if (!coopRefuses()) engine.requestUndoSave()
            Hotkeys.Action.UNDO_LOAD -> if (e.pressed) if (!coopRefuses()) engine.requestUndoLoad()
            Hotkeys.Action.SLOT_NEXT -> if (e.pressed) engine.cycleSlot(+1)
            Hotkeys.Action.SLOT_PREV -> if (e.pressed) engine.cycleSlot(-1)
            Hotkeys.Action.FF_HOLD -> engine.setFastForwardHeld(e.pressed)
            Hotkeys.Action.FF_TOGGLE -> if (e.pressed) engine.toggleFastForward()
            Hotkeys.Action.SPEED_CYCLE -> if (e.pressed) engine.cycleSpeed()
            Hotkeys.Action.SLOWMO_HOLD -> engine.setSlowmoHeld(e.pressed)
            Hotkeys.Action.REWIND_HOLD -> if (romIsNds) {
                // The rewind ring is mGBA's: the hotkey would do nothing at all and look broken.
                if (e.pressed) showHud(com.pokedaisy.app.companion.i18n.tr("Rewind doesn't work in DS games"))
            } else {
                if (e.pressed && !Prefs(this).rewind) showHud(com.pokedaisy.app.companion.i18n.tr("Rewind is off - turn it on in SETTINGS"))
                else if (e.pressed) showHud(com.pokedaisy.app.companion.i18n.tr("Rewinding"))
                engine.setRewindHeld(e.pressed)
            }
            Hotkeys.Action.ZOOM -> if (e.pressed) zoomStep()
            Hotkeys.Action.SOFT_RESET -> if (e.pressed && !coopRefuses()) {
                engine.softReset()
                showHud(com.pokedaisy.app.companion.i18n.tr("Soft reset"))
            }
            Hotkeys.Action.EXIT_GAME -> if (e.pressed) exitGame()
            Hotkeys.Action.RESTART_GAME -> if (e.pressed) restartGame()
            Hotkeys.Action.CLOSE_APP -> if (e.pressed) closeApp()
            Hotkeys.Action.COMPANION -> if (e.pressed) {
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
            Hotkeys.Action.QUICK_MENU -> triggerQuickMenu(e.pressed)
            Hotkeys.Action.FREE_FLIGHT -> if (e.pressed) toggleFlight()
            Hotkeys.Action.MUTE -> if (e.pressed) {
                val muted = !GameVolume.muted
                setGameVolume(GameVolume.percent, muted)
                showHud(com.pokedaisy.app.companion.i18n.tr(if (muted) "Game muted" else "Game sound on"))
            }
            Hotkeys.Action.TOUCH_SCREEN -> if (e.pressed) { com.pokedaisy.app.companion.FeatureTips.used(TipId.DS_TRIGGERS); toggleDsTouch() }
            Hotkeys.Action.DS_LAYOUT -> if (e.pressed) { com.pokedaisy.app.companion.FeatureTips.used(TipId.DS_TRIGGERS); nextDsLayout() }
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
        else zoomTo((view.camera.level + 1) % view.camera.levels().size.coerceAtLeast(1))
    }

    /** EXPAND's zoom step [step] (the hotkey's next one, or SETTINGS > ZOOM's pick), kept for this game. */
    private fun zoomTo(step: Int) {
        if (!view.wide) return
        com.pokedaisy.app.companion.FeatureTips.used(TipId.ZOOM)
        view.camera.level = step.coerceIn(0, (view.camera.levels().size - 1).coerceAtLeast(0))
        if (romKey.isNotEmpty()) Prefs(this).setZoomLevelFor(romKey, view.camera.level)
        showHud(com.pokedaisy.app.companion.i18n.tr("Zoom {0}", com.pokedaisy.app.companion.zoomLabel(view.camera.factor(view.camera.level))))
    }

    /** The QUICK MENU's key went down ([pressed]) or up: it opens or closes. */
    private fun triggerQuickMenu(pressed: Boolean) {
        // A blocked game has no quick menu (its actions are the game's features).
        if (romBlocked != null && !quickMenu.open) return
        val wasOpen = quickMenu.open
        quickMenu.trigger(pressed)
        // Opened: the buttons are the menu's now, so a held direction stops walking.
        if (!wasOpen && quickMenu.open) {
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

    /** Every 500 ms (the HUD's composition, main thread): field play time and calm for [com.pokedaisy.app.companion.TipScheduler],
     *  and the moments that call a tip up - running everywhere, wild battle after wild battle, a TM used up, the game's
     *  own region map closed. */
    private fun tickTips(dt: Long) {
        val tips = com.pokedaisy.app.companion.FeatureTips.scheduler ?: return
        // Asleep (screen off) or in the background, nothing counts as play.
        val e = hudEngine.value?.takeIf {
            it.running && !it.sleeping && tipsResumed
        }
        val snap = telemetry.snapshot.value
        tipsOnCompanion.value = presentation != null || mainCompanion != null
        val field = e != null && playing && e.onField.value && snap.hasData && !snap.unsupported && !snap.inBattle && !hudUpright.value
        val windowOpen = ::sidePanel.isInitialized && sidePanel.windowOpen
        val calm = field && !quickMenu.open && !hudPanelOpen.value && !windowOpen && !expandOfferUp.value && !e!!.holdFrame
        // Over the game, a card goes when the field does (a battle, a menu, the QUICK MENU); on the companion it stays.
        tips.current?.let { if (!tipsOnCompanion.value && (!field || quickMenu.open || windowOpen)) tips.timedOut(it) }
        if (e != null) tipMoments(e, snap, tips)
        tips.tick(dt, field, calm)
    }

    private fun tipMoments(e: EmulatorEngine, snap: com.pokedaisy.app.companion.data.SnapshotView, tips: com.pokedaisy.app.companion.TipScheduler) {
        // AUTO-RUN: a minute of walking where it would run, B held for most of it.
        if (e.walkFrames >= 60 * 60) {
            if (e.runFrames * 10 >= e.walkFrames * 6) tips.moment(TipId.AUTO_RUN)
            e.walkFrames = 0
            e.runFrames = 0
        }
        // WILD ENCOUNTERS: three wild battles within three minutes of walking (a trainer's fills gEnemyParty).
        if (snap.inBattle) {
            if (!tipInBattle) tipTrainerBattle = false
            tipInBattle = true
            if (snap.enemyParty.isNotEmpty()) tipTrainerBattle = true
        } else if (tipInBattle) {
            tipInBattle = false
            if (!tipTrainerBattle) {
                tipWildEnds.addLast(tips.playedMillis)
                while (tipWildEnds.size > 3) tipWildEnds.removeFirst()
                if (tipWildEnds.size == 3 && tipWildEnds.last() - tipWildEnds.first() <= 3 * com.pokedaisy.app.companion.TipScheduler.MINUTE) {
                    tips.moment(TipId.WILD)
                    tipWildEnds.clear()
                }
            }
        }
        // REUSABLE TMS: one TM fewer in the bag (out of battle, nothing else in the pocket changed).
        if (snap.hasData && !snap.inBattle) {
            val tms = snap.items.filter { it.pocket == com.pokedaisy.app.companion.data.POCKET_TM_HM }.associate { it.itemId to it.quantity }
            val before = tipTms
            if (before != null && before.isNotEmpty() && tms.keys.all { it in before } &&
                before.entries.sumOf { (id, q) -> q - (tms[id] ?: 0) } == 1 && before.all { (id, q) -> (tms[id] ?: 0) <= q }
            ) tips.moment(TipId.REUSABLE_TMS)
            tipTms = tms
        }
        // FLY: the game's own region map (a Town Map, its Fly map) just closed.
        val mapOpen = e.regionMapOpen
        if (tipMapOpen && !mapOpen) tips.moment(TipId.FLY)
        tipMapOpen = mapOpen
        // The DS triggers: any time a DS game runs on one screen (the tip's done once answered or a trigger is used).
        if (dsStageOn) tips.moment(TipId.DS_TRIGGERS)
    }

    /** A tip's feature is there to try here, and not on already. */
    private fun tipAvailable(tip: TipId): Boolean {
        if (!::engine.isInitialized || !engine.running) return false
        val p = Prefs(this)
        return when (tip) {
            TipId.ZOOM -> view.wide && tipKey(Hotkeys.Action.ZOOM) != null
            // ExpandIntro has offered it already: this is the one later reminder.
            TipId.EXPAND -> wideGame != WideScreen.OFF && !view.wide && p.expandGameOfferSeen
            TipId.QUICK_MENU -> tipKey(Hotkeys.Action.QUICK_MENU) != null && quickShown.value.any { quickActions.offered(it) }
            TipId.HUD -> hudOn.value && !hudUpright.value
            TipId.PC -> itemUse.canPc()
            TipId.AUTO_RUN -> !p.autoRun && companionSettings.fieldReadable && !companionSettings.autoRunBuiltIn
            TipId.WILD -> companionSettings.wildEncountersSupported && p.wildEncounters != 0
            TipId.REUSABLE_TMS -> companionSettings.reusableTmsSupported && !companionSettings.reusableTmsBuiltIn && !p.reusableTms
            TipId.FLY -> flyAction.canFly() && telemetry.snapshot.value.party.any { m ->
                !m.isEgg && m.moves.any { it.id == com.pokedaisy.app.companion.ui.FLY_MOVE }
            }
            // A DS game on one screen with the triggers on a controller (the HUD's TOUCH stands in without one).
            TipId.DS_TRIGGERS -> dsStageOn && tipKey(Hotkeys.Action.DS_LAYOUT) != null
        }
    }

    /** [action]'s key as a tip names it ("R3", "SELECT + R1"): a controller's binding, none without a controller. */
    private fun tipKey(action: Hotkeys.Action): String? {
        if (!Controllers.anyConnected() && !physicalPadUsed) return null
        if (!::hotkeys.isInitialized) return null
        val chord = hotkeys.rawBindings[action].orEmpty()
            .firstOrNull { c -> c.split('+').all { it.trim().startsWith("BUTTON_") } } ?: return null
        return chord.split('+').joinToString(" + ") { k ->
            when (val name = k.trim()) {
                "BUTTON_THUMBL" -> "L3"
                "BUTTON_THUMBR" -> "R3"
                else -> com.pokedaisy.app.companion.KeyCapture.label(name)
            }
        }
    }

    /** The keys a tip's card names. */
    private fun tipKeys(tip: TipId): List<Pair<String, String>> = when (tip) {
        TipId.ZOOM -> listOfNotNull(
            tipKey(Hotkeys.Action.ZOOM)?.let { it to tr("ZOOM") }, tr("RIGHT STICK") to tr("LOOK AROUND"),
        )
        TipId.QUICK_MENU -> listOfNotNull(tipKey(Hotkeys.Action.QUICK_MENU)?.let { it to tr("QUICK MENU") })
        // TOUCH SCREEN only where the touch screen floats in (ONE SCREEN, PICTURE IN PICTURE).
        TipId.DS_TRIGGERS -> listOfNotNull(
            tipKey(Hotkeys.Action.TOUCH_SCREEN)?.takeIf { dsFloats.value }?.let { it to tr("TOUCH SCREEN") },
            tipKey(Hotkeys.Action.DS_LAYOUT)?.let { it to tr("NEXT LAYOUT") },
        )
        else -> emptyList()
    }

    /** A tip's TRY IT: the feature itself, at once. */
    private fun tryTip(tip: TipId) {
        when (tip) {
            TipId.ZOOM -> zoomStep()
            TipId.EXPAND -> companionSettings.setGameAspect(GameAspect.WIDE)
            TipId.QUICK_MENU -> triggerQuickMenu(true)
            // The lead's summary: what a party icon opens.
            TipId.HUD -> {
                hudOpenPanel.value = com.pokedaisy.app.companion.ui.FieldHudPanel.SUMMARY to 0
                hudOpenTick.intValue++
            }
            TipId.PC -> runQuickAction(com.pokedaisy.app.companion.QuickAction.PC)
            TipId.AUTO_RUN -> {
                companionSettings.setAutoRun(true)
                showHud(tr("AUTO-RUN") + ": " + tr("ON"))
            }
            TipId.WILD -> runQuickAction(com.pokedaisy.app.companion.QuickAction.WILD)
            TipId.REUSABLE_TMS -> {
                companionSettings.setReusableTms(true)
                showHud(tr("REUSABLE TMS") + ": " + tr("ON"))
            }
            // The QUICK MENU's FLY list: the same places the MAP's FLY offers, wherever the player is looking.
            TipId.FLY -> {
                triggerQuickMenu(true)
                runQuickAction(com.pokedaisy.app.companion.QuickAction.FLY)
            }
            // L2's touch screen floated in, else (both screens on show) R2's next layout.
            TipId.DS_TRIGGERS -> if (dsFloats.value) toggleDsTouch() else nextDsLayout()
        }
    }

    // --- helpers -----------------------------------------------------------------

    /** REWIND's buffer: 600 states, one every 2 frames - the last ~20 s of play. */
    private fun rewindEntries(on: Boolean) = if (on && !romIsNds) 600 else 0   // a DS game has no rewind (MB-sized states)

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
        dsperate = romIsNds && Prefs(this).dsCore.runs() == MgbaCore.NDS_CORE_DSPERATE
        if (!romIsNds) return
        wideGame = if (!dsperate && (WideScreen.ndsKnows(romCode, romRev) || romOrigin)) WideScreen.NDS else WideScreen.OFF
        if (::engine.isInitialized) engine.ndsCore = if (dsperate) MgbaCore.NDS_CORE_DSPERATE else MgbaCore.NDS_CORE_MELONDS
    }

    /** RESTART GAME (SETTINGS, and its hotkey): the game booted again from its save, the resume state dropped. */
    private fun restartGame() {
        if (coopRefuses()) return
        val r = rom ?: return
        if (!::engine.isInitialized) return
        // Unbind the GL view from the current framebuffer FIRST and wait for
        // it to take — engine.stop() frees the native buffer the GL thread
        // (continuous render mode, its own clock) may still be reading; skip
        // this and it's a use-after-free race that crashes the process. See
        // EmulatorView.unbindCoreBlocking().
        view.unbindCoreBlocking()
        engine.stop()
        states?.resumeFile?.let {
            if (it.exists()) it.delete()
            com.pokedaisy.app.companion.data.SnapshotCache.fileFor(it).delete()
        }
        val save = SavesLocation.saveFor(this, Prefs(this), r)
        syncCheats()
        waitForPlay()
        // DS CORE changed while the game ran: the restart is on the new one.
        if (romIsNds) {
            val was = dsperate
            pickDsCore()
            if (was != dsperate) syncGameScreen()
        }
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

    /** A touch FF shorter than this turns FF on / off; longer, it was FF while held. */
    private val TOUCH_FF_TAP_MS = 300L

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
        /** Retail English Emerald (BPEE): the only game co-op presence runs on. */
        private const val RETAIL_EMERALD_SHA1 = "f3ae088181bf583e55daf962a92bb46f4f1d07b7"
        /** Games whose Pokémon follow the player already: FOLLOWER isn't offered there. */
        private val FOLLOWERS_OF_THEIR_OWN = setOf(
            com.pokedaisy.app.companion.data.GameKind.HEART_AND_SOUL, com.pokedaisy.app.companion.data.GameKind.SOULGOLD,
            com.pokedaisy.app.companion.data.GameKind.ROWE, com.pokedaisy.app.companion.data.GameKind.EMERALD_SEAGLASS,
            com.pokedaisy.app.companion.data.GameKind.RECHARGED_YELLOW,
        )

        /** BIKE / ROD's SELECT presses before giving up, and the frames each waits to see it take (~3 s in all at 1x). */
        /** How long a tap beside the floating touch screen holds B for the game (a few frames at 1x). */
        private const val DS_B_PRESS_MS = 80L
        /** How often [DsShots] takes the game's picture. */
        private const val DS_SHOT_MS = 1000L
        /** The overworld's picture over the black between a menu and the field fades out over this once the game is back. */
        const val DS_COVER_FADE_MS = 150

        /** How long a floating window kept up through a blank touch screen waits for it ([DsTouchWindow]'s loading),
         * unless both screens are black: past it, the window goes until the touch screen shows something. */
        private const val DS_BLANK_WAIT_MS = 1000L

        /** Tries (a frame apart) at the game's picture as a floating window opens, while another copy is on its way. */
        private const val DS_FREEZE_TRIES = 10
        private const val SELECT_TRIES = 15
        private const val SELECT_WAIT = 10
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
        /** How long a NUZLOCKE notice stays on the game screen: long enough to read in a battle's opening. */
        private const val NUZLOCKE_NOTICE_MS = 3500L

        /** The scenic FLY's question a moment after landing, once the player stands in town. */
        private const val FLY_STYLE_ASK_DELAY_MS = 1500L
        private const val FLIGHT_AREA_TELL_MS = 4000L
        /** offBikeThen: the longest it waits for the game to put the player on foot, and how long after for its dismount. */
        private const val OFF_BIKE_WAIT_MS = 2500L
        private const val OFF_BIKE_SETTLE_MS = 250L
        private const val EXTRA_DUMP_FIXTURE = "dumpFixture"
        private const val EXTRA_DUMP_FIXTURE_FORCE = "dumpFixtureForce"
        private const val EWRAM_BASE = 0x02000000L
        private const val EWRAM_SIZE = 0x40000
        private const val IWRAM_BASE = 0x03000000L
        private const val IWRAM_SIZE = 0x8000
    }
}
