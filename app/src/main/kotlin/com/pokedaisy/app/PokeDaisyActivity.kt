package com.pokedaisy.app

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pokedaisy.app.achievements.RetroAchievements
import com.pokedaisy.app.companion.ui.AchievementBadges
import com.pokedaisy.app.companion.BatteryStatus
import com.pokedaisy.app.companion.DeviceBattery
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter
import com.pokedaisy.app.overlay.OverlayChoice
import com.pokedaisy.app.overlay.OverlayStore
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.data.FfMenuWatch
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.RegionMapWatch
import com.pokedaisy.app.companion.data.RomRegionMap
import com.pokedaisy.app.companion.data.displayName
import com.pokedaisy.app.companion.data.TELEMETRY_SIZE
import com.pokedaisy.app.companion.data.TelemetryDecodeException
import com.pokedaisy.app.companion.data.decodeTelemetry
import com.pokedaisy.app.companion.ui.CompanionScreen
import com.pokedaisy.app.companion.ui.GameStatusBar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Phase 1 shell: full-screen GBA emulation with RetroArch-style savestate slots
 * on configurable hotkeys (see [Hotkeys]). Backgrounding suspends to a state and
 * resumes exactly where you left off.
 *
 * Drop one `.gba` into `Android/data/com.pokedaisy.app/files/roms/`.
 */
class PokeDaisyActivity : Activity() {

    private val input = GbaInput()
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
     * A real button played the game: AUTO hides the touch pad from then on, even for a
     * controller that doesn't call itself a gamepad (some 8BitDo / GameSir modes show
     * up as a keyboard), until a device goes away.
     */
    private var physicalPadUsed = false

    private fun isGamepad(d: InputDevice) = !d.isVirtual &&
        d.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0

    private var rom: File? = null
    /** What the emulator and the ROM readers load for [rom]: the file itself, or an
     * archive's ROM extracted into the cache ([RomArchive.playable]). Saves stay
     * keyed on [rom]'s name. */
    private var romData: File? = null
    /** Opened by a frontend through [LaunchActivity] (see [exitGame]). */
    private var fromFrontend = false
    private var romKey: String = ""      // ROM CRC32, for per-ROM prefs
    private var states: SaveStates? = null

    private val telemetry = TelemetryStore()
    private lateinit var displayManager: DisplayManager
    private var presentation: DualScreenPresentation? = null
    // BACK for the companion: one per companion copy (bottom screen, debug mirror).
    private val companionBack = com.pokedaisy.app.companion.ui.CompanionBack()
    private val mirrorBack = com.pokedaisy.app.companion.ui.CompanionBack()
    private val panelBack = com.pokedaisy.app.companion.ui.CompanionBack()
    private val portraitBack = com.pokedaisy.app.companion.ui.CompanionBack()
    /** The companion beside the game when there's no second screen (see [SidePanel]). */
    private lateinit var sidePanel: SidePanel
    /** ... and under it, with the screen held upright (see [PortraitPanel]). */
    private lateinit var portraitPanel: PortraitPanel
    /** No second screen for the companion: it's in [sidePanel] or [portraitPanel], by the screen's orientation. */
    private var singleScreen = false
    private var debugMirror = false
    /** The game's stage (game, status bar, touch pad, HUD, side panel): the activity's
     * content, or the second screen's with SWAP SCREENS (see [syncPresentation]). */
    private lateinit var stage: GameStageLayout
    /** The companion as the activity's content while the screens are swapped, with its owner. */
    private var mainCompanion: View? = null
    private var mainCompanionOwner: ComposeHostOwner? = null
    /** The companion a swap opens on: SETTINGS when the swap came from there. */
    private var companionStartTab = "PARTY"

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
        override fun requestSave(index: Int) { if (::engine.isInitialized) engine.requestSaveState(index) }
        override fun requestLoad(index: Int) { if (::engine.isInitialized) engine.requestLoadState(index) }
        override fun requestUndoSave() { if (::engine.isInitialized) engine.requestUndoSave() }
        override fun requestUndoLoad() { if (::engine.isInitialized) engine.requestUndoLoad() }
    }

    /** Backs the bottom-screen touch battle control (see PLAN.md
     * Phase 5) — Tier A: firered-qol/emerald-qol only. */
    private val battleInput = object : com.pokedaisy.app.companion.BattleInput {
        override val busy get() = if (::engine.isInitialized) engine.battleInputBusy else false
        override fun selectAction(actionIndex: Int) { if (::engine.isInitialized) engine.battleSelectAction(actionIndex) }
        override fun selectMove(moveIndex: Int) { if (::engine.isInitialized) engine.battleSelectMove(moveIndex) }
        override fun back() { if (::engine.isInitialized) engine.battleBack() }
        override val canSwitch get() = ::engine.isInitialized && engine.battleSwitchAddrs != null
        override fun switchTo(personality: Long) { if (::engine.isInitialized) engine.battleSwitchTo(personality) }
    }

    /** USE on the ITEMS tab: a Repel used the way the game does (FieldItems.kt), retail FireRed rev 1 / Emerald. */
    private fun fieldItemsFor(game: com.pokedaisy.app.companion.data.GameKind?): com.pokedaisy.app.companion.data.FieldItemFns? = when {
        game == com.pokedaisy.app.companion.data.GameKind.FIRERED && romCode == "BPRE" && romRev == 1 ->
            com.pokedaisy.app.companion.data.FIELD_ITEMS_FIRERED_REV1
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD && romCode == "BPEE" ->
            com.pokedaisy.app.companion.data.FIELD_ITEMS_EMERALD
        else -> null
    }

    /** NOT SUPPORTED's TRY BEST EFFORT: the sampler tries it on its next pass (the emulator thread). */
    private val tryBestEffort: () -> Unit = { telemetry.requestBestEffort() }

    /** The match notice's SHARE - the player's yes to sending exactly what it listed. */
    private val shareBestEffort: (com.pokedaisy.app.companion.data.BestEffortReport) -> Unit = { r ->
        BestEffortShare.share(this, r)
    }

    /** NOT SUPPORTED's ASK FOR SUPPORT: the ROM's SHA-1 off the UI thread, then the issue in a browser. */
    private val askForSupport: () -> Unit = {
        rom?.let { r ->
            Thread({
                val url = RomSupportRequest.url(r)
                runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            }, "pokedaisy-support-request").apply { isDaemon = true; start() }
        }
    }

    private val itemUse = object : com.pokedaisy.app.companion.ItemUse {
        override fun canUse(itemId: Int) =
            itemId in com.pokedaisy.app.companion.data.REPEL_ITEMS && fieldItemsFor(telemetry.snapshot.value.game) != null

        override fun use(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {
            val snap = telemetry.snapshot.value
            val fns = fieldItemsFor(snap.game)
            if (fns == null || !::engine.isInitialized || !engine.running) {
                done(com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.FAILED))
                return
            }
            engine.requestItemUse(fns, itemId, snap.inBattle) { out -> runOnUiThread { done(out) } }
        }
    }

    /** The loaded ROM is a Game Boy / Color cart (no GBA header, no L/R). */
    @Volatile private var romIsGameBoy = false

    /** The loaded ROM's header code / revision byte, for [switchAddrsFor]. */
    @Volatile private var romCode = ""
    @Volatile private var romRev = -1

    /**
     * Where the battle POKéMON pane's switch finds the party menu: retail
     * FireRed rev 1 / Emerald and their QoL builds only (same EWRAM addresses
     * in both - gPartyMenu, gPlayerParty from the decomp ELFs, checked against
     * each ROM's literal pools). Keyed on the detected game too, so LeafGreen,
     * FireRed rev 0, Ruby/Sapphire and hacks that share a header code (Seaglass)
     * stay out.
     */
    private fun switchAddrsFor(game: com.pokedaisy.app.companion.data.GameKind?): BattleInputController.SwitchAddrs? = when {
        game == com.pokedaisy.app.companion.data.GameKind.FIRERED && romCode == "BPRE" && romRev == 1 ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L)
        // Japanese Emerald: its own RAM (NATIVE_EMERALD_JA).
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD && romCode == "BPEJ" ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203CB94L, party = 0x02024190L)
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD &&
            (romCode == "BPEE" || romCode in com.pokedaisy.app.companion.data.EMERALD_EUROPEAN_CODES) ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203CEC8L, party = 0x020244ECL)
        // Orange Islands is retail FireRed rev 0's RAM and party menu, edited in place.
        game == com.pokedaisy.app.companion.data.GameKind.ORANGE_ISLANDS ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L)
        game == com.pokedaisy.app.companion.data.GameKind.TMT2 ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203242CL, party = 0x02032C94L)
        game == com.pokedaisy.app.companion.data.GameKind.AMETHYST ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203B0A0L, party = 0x02024284L, grid = true)
        game == com.pokedaisy.app.companion.data.GameKind.ROWE ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203E774L, party = 0x02025128L, monStride = 0x4C, grid = true)
        game == com.pokedaisy.app.companion.data.GameKind.QUETZAL ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203E9FCL, party = 0x020235CCL, monStride = 0x68)
        game == com.pokedaisy.app.companion.data.GameKind.IMPERIUM ->
            BattleInputController.SwitchAddrs(partyMenu = 0x020370E0L, party = 0x020375F8L, grid = true)
        // Glazed is retail Emerald's RAM and party menu code (NATIVE_GLAZED).
        game == com.pokedaisy.app.companion.data.GameKind.GLAZED ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0203CEC8L, party = 0x020244ECL)
        game == com.pokedaisy.app.companion.data.GameKind.LAZARUS ->
            BattleInputController.SwitchAddrs(partyMenu = 0x0201B67CL, party = 0x0201B960L, grid = true)
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD_SEAGLASS ->
            BattleInputController.SwitchAddrs(partyMenu = 0x02019964L, party = 0x02019C20L)
        game == com.pokedaisy.app.companion.data.GameKind.SOULGOLD ->
            // Two releases at different addresses: their configs carry them.
            telemetry.knownPartyMenu()?.let { (menu, party) ->
                BattleInputController.SwitchAddrs(partyMenu = menu, party = party, monStride = 96)
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
        override fun setRewind(on: Boolean) {
            Prefs(this@PokeDaisyActivity).rewind = on
            if (::engine.isInitialized) engine.rewindEntries = rewindEntries(on)
        }
        override val ffMode get() = Prefs(this@PokeDaisyActivity).ffMode
        override fun setFfMode(mode: FfMode) {
            Prefs(this@PokeDaisyActivity).ffMode = mode
            if (::engine.isInitialized) engine.ffMode = mode
        }
        override val touchControlsMode get() = Prefs(this@PokeDaisyActivity).touchControlsMode
        override fun setTouchControlsMode(v: Int) {
            Prefs(this@PokeDaisyActivity).touchControlsMode = v
            syncTouchControls()
        }
        override fun gbaControlBindings() = GbaControls.rawBindings(getExternalFilesDir(null) ?: filesDir)
        override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {
            val code = keyCodeForName(keyName) ?: return
            GbaControls.setBinding(getExternalFilesDir(null) ?: filesDir, btn, code)
            input.setControls(GbaControls.load(getExternalFilesDir(null) ?: filesDir))
        }
        override val hotkeysEnabled get() = hotkeysOn
        override fun setHotkeysEnabled(on: Boolean) {
            Prefs(this@PokeDaisyActivity).hotkeysEnabled = on
            setHotkeysOn(on)
        }
        override fun hotkeyBindings() = Hotkeys.load(getExternalFilesDir(null) ?: filesDir).rawBindings
        override fun setHotkeyBinding(action: Hotkeys.Action, keyName: String?, takeFrom: List<Hotkeys.Action>) {
            val dir = getExternalFilesDir(null) ?: filesDir
            if (keyName == null) Hotkeys.clearBinding(dir, action)
            else Hotkeys.setBinding(dir, action, listOf(keyCodeForName(keyName) ?: return), takeFrom)
            hotkeys = Hotkeys.load(getExternalFilesDir(null) ?: filesDir)
        }
        override fun restartGame() {
            val r = rom ?: return
            if (!::engine.isInitialized) return
            // Unbind the GL view from the current framebuffer FIRST and wait for
            // it to take — engine.stop() frees the native buffer the GL thread
            // (continuous render mode, its own clock) may still be reading; skip
            // this and it's a use-after-free race that crashes the process. See
            // EmulatorView.unbindCoreBlocking().
            view.unbindCoreBlocking()
            engine.stop()
            states?.resumeFile?.let { if (it.exists()) it.delete() }
            val save = SavesLocation.saveFor(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), r)
            syncCheats()
            engine.start(romData ?: r, save, null)
            showHud(com.pokedaisy.app.companion.i18n.tr("Game restarted"))
        }
        override fun closeGame() {
            exitGame()   // drives the normal onPause()/onStop() lifecycle, which already suspends+saves
        }
        override val canCloseCompanion get() = Screens.second(this@PokeDaisyActivity) != null && !Prefs(this@PokeDaisyActivity).swapScreens
        override fun closeCompanion() {
            runOnUiThread {
                companionClosed = true
                syncPresentation()
                showHud(com.pokedaisy.app.companion.i18n.tr("Companion closed. Press BACK to bring it back."))
            }
        }
        override val autoResume get() = Prefs(this@PokeDaisyActivity).autoResume
        override fun setTweak(key: String, on: Boolean) = Prefs(this@PokeDaisyActivity).setTweak(key, on)
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
        override val statusBarOnCompanion get() = Prefs(this@PokeDaisyActivity).statusBarOnCompanion
        override fun setStatusBarOnCompanion(on: Boolean) {
            Prefs(this@PokeDaisyActivity).statusBarOnCompanion = on
            runOnUiThread { syncGameScreen() }
        }
        override val stretchGame get() = Prefs(this@PokeDaisyActivity).stretchGame
        override fun setStretchGame(on: Boolean) {
            Prefs(this@PokeDaisyActivity).stretchGame = on
            runOnUiThread { syncGameScreen() }
        }
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
        override val companionShaders get() = Prefs(this@PokeDaisyActivity).companionShaders
        override fun setCompanionShaders(on: Boolean) {
            Prefs(this@PokeDaisyActivity).companionShaders = on
            runOnUiThread { syncGameScreen() }
        }
        // The display itself, not `presentation != null`: a swap composes the companion
        // here before the new presentation is assigned, and the remembered rows then lost
        // SWAP SCREENS until the page was rebuilt.
        override val hasSecondScreen get() = Screens.second(this@PokeDaisyActivity) != null
        override val swapScreens get() = Prefs(this@PokeDaisyActivity).swapScreens
        override val portraitUnderGame get() =
            if (::portraitPanel.isInitialized && portraitPanel.enabled) Prefs(this@PokeDaisyActivity).portraitCompanionUnderGame else null
        override fun setPortraitUnderGame(on: Boolean) {
            Prefs(this@PokeDaisyActivity).portraitCompanionUnderGame = on
            runOnUiThread { portraitPanel.refresh() }
        }
        override fun setSwapScreens(on: Boolean) {
            Prefs(this@PokeDaisyActivity).swapScreens = on
            // The companion that asked moves screens: it comes back on SETTINGS.
            companionStartTab = "SETTINGS"
            runOnUiThread { syncPresentation() }
        }
        override val showHints get() = Prefs(this@PokeDaisyActivity).showHints
        override fun setShowHints(on: Boolean) {
            Prefs(this@PokeDaisyActivity).showHints = on
        }
        override val appLanguage get() = Prefs(this@PokeDaisyActivity).appLanguage
        override fun setAppLanguage(code: String) {
            Prefs(this@PokeDaisyActivity).appLanguage = code
            com.pokedaisy.app.companion.i18n.L10n.apply(code, romCode)
        }
        override val showFoeIvs get() = Prefs(this@PokeDaisyActivity).showFoeIvs
        override fun setShowFoeIvs(on: Boolean) {
            Prefs(this@PokeDaisyActivity).showFoeIvs = on
        }
        override val cheats get() = cheatStore()?.load().orEmpty()
        override val cheatsEnabled get() = Prefs(this@PokeDaisyActivity).cheatsEnabled
        override fun setCheatsEnabled(on: Boolean) {
            Prefs(this@PokeDaisyActivity).cheatsEnabled = on
            syncCheats()
        }
        override fun setCheatEnabled(index: Int, on: Boolean) {
            val store = cheatStore() ?: return
            val list = store.load()
            if (index !in list.indices) return
            store.save(list.toMutableList().also { it[index] = it[index].copy(enabled = on) })
            syncCheats()
        }
        override val gameName get() = com.pokedaisy.app.companion.data.activeGame.displayName()
        override val romFileName get() = rom?.name ?: "(none)"
        override val companionTabs get() = Prefs(this@PokeDaisyActivity).companionTabs
        override fun setCompanionTabs(tabs: List<String>) {
            Prefs(this@PokeDaisyActivity).companionTabs = tabs
        }
        override fun guideNoticeAccepted(game: String) = Prefs(this@PokeDaisyActivity).guideNoticeAccepted(game)
        override fun acceptGuideNotice(game: String) = Prefs(this@PokeDaisyActivity).acceptGuideNotice(game)
    }

    /** Reverses [Hotkeys.keyName] — "BUTTON_A"/"Z"/etc. back to a keyCode, for the
     * companion's tap-a-name rebind picker (see [CompanionSettings]). */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RomFolder.useBestEffortStore(this)
        BestEffortShare.flush(this)   // a shared match that couldn't go out last time
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()
        RetroAchievements.init(this)
        RetroAchievements.onUnlockSound = { unlockSound?.play() }
        AchievementBadges.dir = java.io.File(cacheDir, "ra-badges")

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
        view.holdFrame = { ::engine.isInitialized && engine.holdFrame }
        // 1x frames follow the game screen's refresh (EmulatorEngine.onVsync).
        view.onVsync = { if (::engine.isInitialized) engine.onVsync() }
        // SHADERS on the companion: its grid at the game's own pixel size, so both screens match.
        view.onGamePixel = CompanionColors::setCell
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
            onMask = { bits -> input.setTouchBits(bits) }
            visibility = View.GONE
        }
        statusBar = buildStatusBar()
        val root = GameStageLayout(this, view, statusBar, hud).apply {
            setBackgroundColor(Color.BLACK)
            // Shrink the game view to the left portion (instead of full-screen
            // underneath the mirror) so the whole GBA frame is actually
            // visible, aspect-fit within that narrower space, rather than half
            // of it sitting hidden behind the companion panel.
            val gameParams = if (debugMirror) {
                FrameLayout.LayoutParams(-1, -1).apply { rightMargin = dp(DEBUG_MIRROR_WIDTH_DP) }
            } else {
                FrameLayout.LayoutParams(-1, -1)
            }
            addView(view, gameParams)
            addView(statusBar, FrameLayout.LayoutParams(-2, -2))
            addView(touchControls, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(12); leftMargin = dp(12)
            })
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
        sidePanel = SidePanel(root, view, touchControls, Prefs(this), panelBack, playClick) {
            val snap by telemetry.snapshot.collectAsState()
            CompanionScreen(snap, stateSlots, companionSettings, battleInput, back = panelBack, clickSound = playClick, achievements = RetroAchievements, statusBar = companionStatusBar, itemUse = itemUse, askForSupport = askForSupport, tryBestEffort = tryBestEffort, shareBestEffort = shareBestEffort)
        }
        portraitPanel = PortraitPanel(root, view, touchControls, Prefs(this), portraitBack, playClick) {
            val snap by telemetry.snapshot.collectAsState()
            CompanionScreen(snap, stateSlots, companionSettings, battleInput, back = portraitBack, clickSound = playClick, achievements = RetroAchievements, statusBar = companionStatusBar, itemUse = itemUse, askForSupport = askForSupport, tryBestEffort = tryBestEffort, shareBestEffort = shareBestEffort)
        }
        // Two screens: the game stays on the landscape top one. One screen (a phone): it turns
        // with the device (the user's rotation setting), the companion under the game upright.
        syncOrientation(Screens.hasSecond(this))
        syncGameScreen()

        hotkeys = Hotkeys.load(getExternalFilesDir(null) ?: filesDir)
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
        // ~50-150ms for a 16-32 MiB ROM; one-time, at launch, on the black screen.
        val crc = SaveStates.crc32(r)
        romKey = crc
        states = SaveStates(getExternalFilesDir(null) ?: filesDir, crc)
        val ffMusicCache = FfMusicCache(getExternalFilesDir(null) ?: filesDir, crc)
        // STEADY FF music: songs rendered on their own from this ROM, in the
        // background, as the game starts them (see FfMusicRenderer).
        // The companion's button click is rendered from this ROM the same way.
        clickSound?.release()
        clickSound = GameClickSound(filesDir, crc)
        clickSoundOn = Prefs(this).clickSound
        com.pokedaisy.app.companion.ui.CompanionTweaks.load { Prefs(this).tweak(it) }
        // RetroAchievements unlocks play the game's level-up fanfare, rendered the same way.
        unlockSound?.release()
        unlockSound = GameClickSound(filesDir, crc, GameClickSound.FANFARE)
        ffMusicRenderer?.stop()
        // A Game Boy / Color game: none of the GBA ROM scans below apply (m4a music,
        // GBA art fingerprints, FireRed's region_map.c); its click is borrowed.
        val gameBoy = RomIdentity.isGameBoy(r)
        romIsGameBoy = gameBoy
        // FireRed / Emerald party-menu art and region maps come from the ROM
        // itself, once per ROM (see RomArt) - nothing of the game is bundled.
        if (!gameBoy) RomArt.prefetch(filesDir, crc, r) else com.pokedaisy.app.companion.data.Gen1Art.prefetch(filesDir, r)
        // A Game Boy cart's POKéDEX pages are read from its bytes (bank-switched: not on the bus whole); a few MB at most.
        val gbRom = if (gameBoy) runCatching { r.readBytes() }.getOrNull() else null
        com.pokedaisy.app.companion.data.Gen1Dex.rom = gbRom
        // STEADY FF music: a Gen 1 cart's songs come from its own sound engine (Gen1MusicRenderer).
        gen1Music = gbRom?.let {
            val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(it).joinToString("") { b -> "%02x".format(b) }
            com.pokedaisy.app.companion.data.Gen1Music.forSha1(sha1)
        }
        ffMusicRenderer = when {
            !gameBoy -> FfMusicRenderer(r, crc, ffMusicCache, clickSound, unlockSound).also { it.start() }
            gen1Music != null -> Gen1MusicRenderer(r, gen1Music!!, ffMusicCache).also { it.start() }
            else -> null
        }
        // A FireRed-engine hack's own region map (Unbound, Odyssey, ...), read
        // from the ROM on every launch - a few KB of reads (see RomRegionMap).
        if (!gameBoy) RomRegionMap.load(filesDir, crc, r)
        // SMART FF steps aside while the game's region map or a menu is up (see EmulatorEngine).
        RegionMapWatch.load(r)
        val (menuField, menuBattle) = Prefs(this).ffMenuCallbacks(crc)
        val header = runCatching { java.io.RandomAccessFile(r, "r").use { f -> f.seek(0xAC); ByteArray(0x11).also { f.readFully(it) } } }.getOrNull()
        val gameCode = header?.let { String(it, 0, 4, Charsets.US_ASCII) }.orEmpty()
        romCode = gameCode
        // AUTO = the ROM's own language (its game code's last letter).
        com.pokedaisy.app.companion.i18n.L10n.apply(Prefs(this).appLanguage, gameCode)
        romRev = header?.let { it[0x10].toInt() and 0xFF } ?: -1
        FfMenuWatch.load(gameCode, menuField, menuBattle)
        val appContext = applicationContext
        FfMenuWatch.onLearned = { field, battle -> Prefs(appContext).setFfMenuCallbacks(crc, field, battle) }

        engine = EmulatorEngine(input, states!!, ffMusicCache).apply {
            gba = !gameBoy
            gen1Music = this@PokeDaisyActivity.gen1Music
            onCoreReady = { w, h ->
                MgbaCore.pkVideoBuffer()?.let { buf ->
                    runOnUiThread {
                        stage.aspect = w.toFloat() / h   // 3:2, or a Game Boy's 10:9
                        touchControls.shoulders = !gameBoy
                        view.bindCore(buf, w, h)
                    }
                }
            }
            // The emu thread is about to free the frame buffer (pause, close, a crashed
            // loop): the GL thread lets go of it first. unbind takes the renderer's lock,
            // so it's safe from this thread and returns only once no upload is reading it.
            onCoreStopping = { view.unbindCoreBlocking() }
            // The screen shows finished frames only (a copy), never the one being drawn.
            onFrame = { view.publishFrame() }
            onStateResult = { action, slot, ok ->
                val msg = when (action) {
                    Hotkeys.Action.SAVE_STATE -> if (ok) tr("Saved slot {0}", slot) else tr("Slot {0}: save failed", slot)
                    Hotkeys.Action.LOAD_STATE -> if (ok) tr("Loaded slot {0}", slot) else tr("Slot {0}: nothing to load", slot)
                    Hotkeys.Action.UNDO_SAVE -> if (ok) tr("Undid save (slot {0})", slot) else tr("Nothing to undo")
                    Hotkeys.Action.UNDO_LOAD -> if (ok) tr("Undid load") else tr("Nothing to undo")
                    else -> ""
                }
                if (msg.isNotEmpty()) runOnUiThread { showHud(msg) }
            }
            onSlotChanged = { slot ->
                val text = if (states?.exists(slot) == true) tr("Slot {0} (used)", slot) else tr("Slot {0} (empty)", slot)
                runOnUiThread { showHud(text) }
            }
            onSpeedChanged = { label -> runOnUiThread { showHud(label) } }
            onFastForwardToggledChanged = { on -> Prefs(this@PokeDaisyActivity).ffToggled = on }
            onFfMusicChanged = { clip -> runOnUiThread { ffMusicPlayer.setClip(clip) } }
            onFfMusicWanted = { key -> ffMusicRenderer?.request(key) }
            onSample = {   // runs on the emu thread
                val snap = telemetry.refresh()
                // SMART FF reads the game's own gMain once the game is known (no scan
                // guesswork), and learns the field's main callback from the player moving.
                telemetry.knownGMain()?.let { (addr, inBattleOff) -> FfMenuWatch.useKnownGMain(addr, inBattleOff) }
                if (snap.connected) FfMenuWatch.notePosition(snap.x, snap.y, snap.mapGroup, snap.mapNum, snap.inBattle)
                if (snap.connected) battleSwitchAddrs = switchAddrsFor(snap.game)
                if (snap.connected) battleGen1Menus = snap.game == com.pokedaisy.app.companion.data.GameKind.YELLOW
                // party.isNotEmpty(), not just connected: `connected` flips true as
                // soon as the QOLT struct/native addresses are found, which can be
                // well before the save's party has actually loaded into RAM (e.g.
                // still on the title/continue screen just after boot) - a dump
                // right then faithfully captures a real but useless "partyCount=0"
                // moment. Bit the first fixture capture this existed for.
                if (snap.connected && (snap.party.isNotEmpty() || forceFixtureDump)) dumpFixtureIfPending()
            }
            onBattleInputSample = {   // runs on the emu thread, ~15x/sec
                telemetry.refreshBattleInputFast()?.let { (battler, state) ->
                    setBattleMenuState(battler, state, telemetry.battleMenuCursor())
                }
            }
            ffMaxSpeed = Prefs(this@PokeDaisyActivity).ffMaxSpeed
            ffMusicMode = Prefs(this@PokeDaisyActivity).ffMusicMode
            ffMode = Prefs(this@PokeDaisyActivity).ffMode
            rewindEntries = rewindEntries(Prefs(this@PokeDaisyActivity).rewind)
            restoreFastForwardToggled(Prefs(this@PokeDaisyActivity).ffToggled)
            restoreSpeedIndex(Prefs(this@PokeDaisyActivity).speedIndexFor(romKey))
        }
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
                    states?.let { engine.stopWithSuspend(it.resumeFile) } ?: engine.stop()
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
    private fun syncCheats() {
        if (!::engine.isInitialized) return
        val text = if (Prefs(this).cheatsEnabled) cheatStore()?.load()?.let(com.pokedaisy.app.cheats.CheatFiles::coreText).orEmpty() else ""
        engine.setCheats(text)
    }

    /** Starts the loaded ROM's engine, resuming where it was left; false with no ROM. */
    private fun startGame(): Boolean {
        val r = rom ?: return false
        val st = states ?: return false
        val save = SavesLocation.saveFor(this, Prefs(this), r)
        // Cheats may have changed in the library's Settings since the game was last up.
        syncCheats()
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
            engine.start(romData ?: r, save, null)
            view.postDelayed({ (engine.lastError ?: engine.lastNotice)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }, 1500)
            return true
        }
        st.latestResumeSource()?.let { src -> if (src != st.resumeFile) runCatching { src.copyTo(st.resumeFile, overwrite = true) } }
        engine.start(romData ?: r, save, st.resumeFile.takeIf { it.isFile && it.length() > 0 })
        view.postDelayed({ (engine.lastError ?: engine.lastNotice)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }, 1500)
        return true
    }

    override fun onDestroy() {
        ffMusicRenderer?.stop()
        ffMusicRenderer = null
        clickSound?.release()
        clickSound = null
        RetroAchievements.onUnlockSound = null
        unlockSound?.release()
        unlockSound = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { inputManager.unregisterInputDeviceListener(inputDeviceListener) }
        runCatching { unregisterReceiver(batteryReceiver) }
        presentation?.let { it.dismiss(); it.releaseContent() }
        presentation = null
        ffMusicPlayer.release()
        if (::engine.isInitialized && romKey.isNotEmpty()) {
            Prefs(this).setSpeedIndexFor(romKey, engine.speedIndex)
        }
        // Before the engine frees the frame buffer the GL thread draws from.
        view.unbindCoreBlocking()
        states?.let { engine.stopWithSuspend(it.resumeFile) } ?: engine.stop()
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
    /** The player closed the companion (SETTINGS > CLOSE COMPANION): its screen is the device's until BACK. */
    private var companionClosed = false

    private fun syncPresentation() {
        if (!::engine.isInitialized) return
        val target: Display? = Screens.second(this)
        val swap = target != null && Prefs(this).swapScreens && !debugMirror
        if (companionClosed && target != null && !swap) {
            presentation?.let { it.dismiss(); it.releaseContent() }
            presentation = null
            showStage()
            return
        }
        val current = presentation
        if (current != null && target != null && current.display.displayId == target.displayId &&
            current.isShowing && swap == (mainCompanion != null)
        ) return
        current?.let { it.dismiss(); it.releaseContent() }
        presentation = null
        syncOrientation(target != null)
        // One screen: the companion goes in a panel beside / under the game instead
        // (not with the debug mirror, which already shows it there).
        syncSingleScreen(target == null && !debugMirror)
        if (swap) showMainCompanion() else showStage()
        if (target == null) return
        presentation = runCatching {
            DualScreenPresentation(this, target) { ctx ->
                if (swap) stage
                else DualScreenPresentation.companionView(
                    ctx, telemetry, stateSlots, companionSettings, battleInput, companionBack, playClick, RetroAchievements,
                    initialTab = companionStartTab, statusBar = companionStatusBar, itemUse = itemUse, askForSupport = askForSupport, tryBestEffort = tryBestEffort, shareBestEffort = shareBestEffort,
                )
            }.also { it.show() }
        }.onFailure { Log.w("pokedaisy", "presentation failed", it) }.getOrNull()
        companionStartTab = "PARTY"
        if (presentation == null && swap) {
            // The game has to be somewhere: back here, the companion beside it.
            showStage()
            syncSingleScreen(!debugMirror)
        }
    }

    /** The companion on the game's screen: beside the game held sideways, under it upright. */
    private fun syncSingleScreen(on: Boolean) {
        singleScreen = on
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        // Off first, so the two never hold the game's margins at once.
        if (portrait) sidePanel.setEnabled(false) else portraitPanel.setEnabled(false)
        sidePanel.setEnabled(on && !portrait)
        portraitPanel.setEnabled(on && portrait)
        // Upright, the game drops its overlay (and gets it back sideways).
        syncGameScreen()
    }

    /** Locked to landscape with a second screen (the Thor's top one); else the device's rotation decides. */
    private fun syncOrientation(twoScreens: Boolean) {
        val want = if (twoScreens || debugMirror) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
        if (requestedOrientation != want) requestedOrientation = want
    }

    // A rotation (configChanges keeps the activity, and the game, running through it).
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::sidePanel.isInitialized) syncSingleScreen(singleScreen)
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

    /** The companion as this activity's content (the stage then goes to the second screen). */
    private fun showMainCompanion() {
        if (mainCompanion != null) return
        val owner = ComposeHostOwner().apply { create(); resume() }
        val view = DualScreenPresentation.companionView(
            this, telemetry, stateSlots, companionSettings, battleInput, companionBack, playClick, RetroAchievements,
            initialTab = companionStartTab, statusBar = companionStatusBar, itemUse = itemUse, askForSupport = askForSupport, tryBestEffort = tryBestEffort, shareBestEffort = shareBestEffort,
        ).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
        }
        mainCompanionOwner = owner
        mainCompanion = view
        setContentView(view)
    }

    /** Show the status bar above the game per SETTINGS > STATUS BAR, fit or
     * stretch the game per SETTINGS > ASPECT, and draw it through SETTINGS > SHADERS. */
    private fun syncGameScreen() {
        if (!::statusBar.isInitialized) return
        val prefs = Prefs(this)
        // STATUS BAR > COMPANION: the strip goes over the companion's tabs instead.
        statusBar.visibility = if (prefs.statusBar && !prefs.statusBarOnCompanion) View.VISIBLE else View.GONE
        com.pokedaisy.app.companion.ui.CompanionStatusBar.shown = prefs.statusBar && prefs.statusBarOnCompanion
        val stageLayout = statusBar.parent as? GameStageLayout
        // SCREEN > OVERLAY: not on a phone held upright, where the game is its own shape across the top.
        view.overlay = if (stageLayout?.topAligned == true) null else when (val c = OverlayChoice.of(prefs.overlay)) {
            OverlayChoice.None -> null
            is OverlayChoice.BuiltIn -> EmulatorView.OverlaySource.BuiltIn(c.style)
            is OverlayChoice.Imported -> overlayStore.get(c.id)?.let { EmulatorView.OverlaySource.Imported(it) }
        }
        // With an overlay the view takes all the room and places the game in it itself.
        stageLayout?.stretch = prefs.stretchGame || view.overlay != null
        view.stretch = prefs.stretchGame
        view.gbaColors = prefs.gbaColors
        CompanionColors.set(
            prefs.gbaColors && prefs.companionShaders,
            if (prefs.companionShaders) prefs.screenFilter else ScreenFilter.NONE,
            ScreenShaders.gridFor(prefs.screenFilter, prefs.gridStrength),
        )
        view.screenEffect = ScreenShaders.effectFor(prefs.screenFilter)
        view.screenGrid = ScreenShaders.gridFor(prefs.screenFilter, prefs.gridStrength)
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
            val name by produceState(rom?.let { GameTitles.label(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), it) }.orEmpty(), rom) {
                val r = rom ?: return@produceState
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

    /** Show the on-screen controls per the Settings mode (default: only with no gamepad). */
    private fun syncTouchControls() {
        if (!::touchControls.isInitialized) return
        val show = when (Prefs(this).touchControlsMode) {
            1 -> true
            2 -> false
            else -> !physicalPadUsed && InputDevice.getDeviceIds().none { id ->
                InputDevice.getDevice(id)?.let(::isGamepad) == true
            }
        }
        touchControls.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) input.setTouchBits(0)
    }

    // --- input ---------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        if (::hotkeys.isInitialized && hotkeysOn) {
            for (e in hotkeys.onKey(event.keyCode, down)) handleHotkey(e)
            if (hotkeys.consumes(event.keyCode)) return true   // key is completing a chord
        }
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
        if (companionClosed) {
            // The companion was closed: BACK brings it back.
            companionClosed = false
            syncPresentation()
        } else if (!sidePanel.onBack() && !portraitPanel.onBack()) { companionBack.back(); mirrorBack.back() }
    }

    // Many Android handhelds report L2/R2 as analog axes, not BUTTON_L2/R2 keys.
    // RetroArch-style: right trigger = hold fast-forward, left trigger = hold slow-mo.
    private var rtDown = false
    private var ltDown = false

    // A controller's stick / D-pad / triggers, before the views: with the companion in this
    // window (side panel, phone upright) a focused ComposeView would see them first.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
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
            if ((rt > 0.5f) != rtDown) { rtDown = rt > 0.5f; engine.setFastForwardHeld(rtDown) }
            if ((lt > 0.5f) != ltDown) { ltDown = lt > 0.5f; engine.setSlowmoHeld(ltDown) }
        }
        if (input.onMotion(event)) return true
        return super.onGenericMotionEvent(event)
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
            Hotkeys.Action.SAVE_STATE -> if (e.pressed) engine.requestSaveState(engine.currentSlot)
            Hotkeys.Action.LOAD_STATE -> if (e.pressed) engine.requestLoadState(engine.currentSlot)
            Hotkeys.Action.UNDO_SAVE -> if (e.pressed) engine.requestUndoSave()
            Hotkeys.Action.UNDO_LOAD -> if (e.pressed) engine.requestUndoLoad()
            Hotkeys.Action.SLOT_NEXT -> if (e.pressed) engine.cycleSlot(+1)
            Hotkeys.Action.SLOT_PREV -> if (e.pressed) engine.cycleSlot(-1)
            Hotkeys.Action.FF_HOLD -> engine.setFastForwardHeld(e.pressed)
            Hotkeys.Action.FF_TOGGLE -> if (e.pressed) engine.toggleFastForward()
            Hotkeys.Action.SPEED_CYCLE -> if (e.pressed) engine.cycleSpeed()
            Hotkeys.Action.SLOWMO_HOLD -> engine.setSlowmoHeld(e.pressed)
            Hotkeys.Action.REWIND_HOLD -> {
                if (e.pressed && !Prefs(this).rewind) showHud(com.pokedaisy.app.companion.i18n.tr("Rewind is off - turn it on in SETTINGS"))
                else if (e.pressed) showHud(com.pokedaisy.app.companion.i18n.tr("Rewinding"))
                engine.setRewindHeld(e.pressed)
            }
            Hotkeys.Action.EXIT_GAME -> if (e.pressed) exitGame()
        }
    }

    // --- helpers -----------------------------------------------------------------

    /** REWIND's buffer: 600 states, one every 2 frames - the last ~20 s of play. */
    private fun rewindEntries(on: Boolean) = if (on) 600 else 0

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
        hud.removeCallbacks(hideHud)
        hud.postDelayed(hideHud, 1800)
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
            // QolTelemetry_Update() rewrites the WHOLE struct roughly once a
            // second (struct-path games only); if this sample's two separate
            // reads land mid-rewrite, the result is a torn snapshot - not
            // corruption, just unlucky timing. Retry next tick instead of
            // freezing a garbage fixture. Bit real capture attempts before
            // this check existed (item slots past itemCount weren't actually
            // all-zero - see the emerald_qol capture writeup this guards
            // against). Native-RAM games have no "QOLT" magic, so they always
            // pass through untouched.
            if (!isStructSnapshotConsistent(iwram) || !isStructSnapshotConsistent(ewram)) {
                Log.w("pokedaisy", "fixture dump: torn struct read, retrying next tick")
                return
            }
            pendingFixtureDump = null // one-shot, only once a snapshot is actually accepted
            File(dir, "ewram.bin").writeBytes(ewram)
            File(dir, "iwram.bin").writeBytes(iwram)
            Log.i("pokedaisy", "fixture dump: wrote ${ewram.size}B EWRAM + ${iwram.size}B IWRAM to $dir")
        } catch (t: Throwable) {
            Log.e("pokedaisy", "fixture dump failed", t)
            pendingFixtureDump = null
        }
    }

    /** True if [buf] has no "QOLT" magic (a native-RAM game - nothing to
     * check) or if it does and the struct there decodes cleanly with every
     * item slot from itemCount onward genuinely zeroed, matching
     * QolTelemetry_Update()'s own zero-fill loop invariant. False means a
     * torn read caught mid-rewrite. */
    private fun isStructSnapshotConsistent(buf: ByteArray): Boolean {
        val magic = "QOLT".toByteArray(Charsets.US_ASCII)
        var idx = -1
        outer@ for (i in 0..buf.size - magic.size) {
            for (j in magic.indices) {
                if (buf[i + j] != magic[j]) continue@outer
            }
            idx = i
            break
        }
        if (idx < 0) return true // no struct here - native-RAM path, nothing to validate
        if (idx + TELEMETRY_SIZE > buf.size) return false
        return try {
            val t = decodeTelemetry(buf.copyOfRange(idx, idx + TELEMETRY_SIZE))
            var seenPadding = false
            for (item in t.items) {
                val isZero = item.itemId == 0 && item.quantity == 0 && item.pocket == 0
                if (seenPadding && !isZero) return false // non-zero after padding started
                if (isZero) seenPadding = true
            }
            true
        } catch (e: TelemetryDecodeException) {
            false
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

    /**
     * Leaves the game. Opened by a frontend, the whole task goes, so the
     * frontend comes back rather than a library left underneath from earlier.
     */
    private fun exitGame() {
        if (fromFrontend) finishAndRemoveTask() else finish()
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
                CompanionScreen(snap, stateSlots, companionSettings, battleInput, back = mirrorBack, clickSound = playClick, achievements = RetroAchievements, statusBar = companionStatusBar, itemUse = itemUse, askForSupport = askForSupport, tryBestEffort = tryBestEffort, shareBestEffort = shareBestEffort)
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
        /** Set by [LaunchActivity]: leaving the game returns to the frontend. */
        const val EXTRA_FROM_FRONTEND = "fromFrontend"
        private val GBA_EXT = RomArchive.ROM_EXTENSIONS + RomArchive.EXTENSIONS
        private const val DEBUG_MIRROR_WIDTH_DP = 420
        private const val EXTRA_DUMP_FIXTURE = "dumpFixture"
        private const val EXTRA_DUMP_FIXTURE_FORCE = "dumpFixtureForce"
        private const val EWRAM_BASE = 0x02000000L
        private const val EWRAM_SIZE = 0x40000
        private const val IWRAM_BASE = 0x03000000L
        private const val IWRAM_SIZE = 0x8000
    }
}
