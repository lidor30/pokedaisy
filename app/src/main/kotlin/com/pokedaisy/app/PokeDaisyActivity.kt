package com.pokedaisy.app

import com.pokedaisy.app.companion.data.isHgss
import com.pokedaisy.app.companion.DsCore
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
import com.pokedaisy.app.companion.data.isGen2
import com.pokedaisy.app.companion.data.isDsGame
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
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter
import com.pokedaisy.app.companion.Smoothing
import com.pokedaisy.app.companion.ui.MenuKey
import com.pokedaisy.app.overlay.OverlayChoice
import com.pokedaisy.app.overlay.OverlayStore
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.FoldMode
import com.pokedaisy.app.companion.FoldPosture
import com.pokedaisy.app.companion.data.FfMenuWatch
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.TipId
import com.pokedaisy.app.companion.data.hasData
import com.pokedaisy.app.companion.data.RomArt
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
    /** The POKéMON CENTER's specials in this ROM ([com.pokedaisy.app.companion.data.PokemonCenter]; null = none). */
    @Volatile private var centerFound: com.pokedaisy.app.companion.data.PokemonCenter.Found? = null
    private fun currentGameOptions() = if (::engine.isInitialized) engine.gameOptions else null
    private val hmPatch get() = currentGameOptions()?.discovery?.hm
    private val expShareFound get() = currentGameOptions()?.discovery?.exp
    private val levelCapFound get() = currentGameOptions()?.discovery?.cap
    private val levelCapNow get() = currentGameOptions()?.levelCapNow
    private fun syncExpShare() {
        val options = currentGameOptions()
        options?.setExpShare(expShareFound, com.pokedaisy.app.companion.data.ExpShare.Mode.of(Prefs(this).expShare))
        runCatching { com.pokedaisy.app.achievements.RetroAchievements.setExpShareActive(options?.expShareActive == true) }
    }

    /** BOSS RETRY: the option (cached for the emu thread); this battle's state taken / a loss seen; the battle before. */
    @Volatile private var battleRetryOn = false
    private var retryTaken = false
    private var retryLost = false
    private var retryInBattle = false

    /** Each sample (emu thread): a state as a trainer battle begins, the party all fainted noted, and after a loss the card. */
    private fun updateBattleRetry(snap: com.pokedaisy.app.companion.data.SnapshotView) {
        if (!snap.connected || n3ds != null) return
        if (snap.inBattle) {
            if (!retryTaken && battleRetryOn && snap.isTrainerBattle == true) {
                retryTaken = true
                engine.requestRetrySave()
            }
            if (snap.party.isNotEmpty() && snap.party.all { it.isEgg || it.hp <= 0 }) retryLost = true
        } else if (retryInBattle) {
            if (retryTaken && retryLost && battleRetryOn) {
                Log.i("pokedaisy", "boss retry: a trainer battle lost, asking")
                runOnUiThread { com.pokedaisy.app.companion.ui.BattleRetryAsk.showing = true }
            }
            retryTaken = false
            retryLost = false
        }
        retryInBattle = snap.inBattle
    }

    /** BattleRetryAsk's answer: RETRY loads the battle's start; no leaves the game as it is. */
    private fun answerBattleRetry(retry: Boolean) {
        if (retry && ::engine.isInitialized && !coopRefuses()) engine.requestRetryLoad()
    }

    /** LAST BALL ([com.pokedaisy.app.companion.data.BattleBall]): the game's functions (null = none, or not found yet), the
     * config they were looked up for, the option (cached), the ball thrown last (per ROM) and the bag of the last sample. */
    private val ballThread by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "last-ball") } }
    @Volatile private var ballFns: com.pokedaisy.app.companion.data.BattleBall.Fns? = null
    private var ballFnsFor: com.pokedaisy.app.companion.data.NativeConfig? = null
    @Volatile private var lastBallOn = true
    @Volatile private var lastBallId: Int? = null
    private var ballItemsBefore: List<com.pokedaisy.app.companion.data.ItemView> = emptyList()

    /** Each sample (emu thread): the throw's functions looked up once the game's config is known, and the ball a battle used. */
    private fun updateLastBall(snap: com.pokedaisy.app.companion.data.SnapshotView) {
        if (!snap.connected) return
        val cfg = com.pokedaisy.app.companion.data.activeNativeConfig
        val rom = romData
        if (cfg != null && cfg !== ballFnsFor && rom != null && romBlocked == null) {
            ballFnsFor = cfg
            ballThread.execute {
                val f = com.pokedaisy.app.companion.data.BattleBall.find(rom.toOkioPath(), cfg.completeWhenChoseItem, cfg.battlerControllerFuncs)
                Log.i("pokedaisy", "last ball: ${if (f != null) "found" else "not found"}")
                if (romData == rom) ballFns = f
            }
        }
        if (snap.inBattle) com.pokedaisy.app.companion.data.BattleBall.thrown(ballItemsBefore, snap.items)?.let { id ->
            if (id != lastBallId) { lastBallId = id; Prefs(this).setLastBallFor(romKey, id) }
        }
        ballItemsBefore = snap.items
    }

    /** The ball the BATTLE tab's button throws now, or null for no button ([com.pokedaisy.app.companion.data.BattleBall.canThrow]). */
    private fun lastBallNow(): com.pokedaisy.app.companion.data.ItemView? {
        if (!lastBallOn || ballFns == null || !::engine.isInitialized || n3ds != null) return null
        if ((fieldItemSupport()?.fns?.removeBagItem ?: 0L) == 0L) return null
        val snap = telemetry.snapshot.value
        if (!com.pokedaisy.app.companion.data.BattleBall.canThrow(snap)) return null
        return com.pokedaisy.app.companion.data.BattleBall.pick(snap.items, lastBallId)
    }

    /** SHINY ALERT ([com.pokedaisy.app.companion.data.ShinyHunt]): the option (cached for the emu thread), the battle
     * follower and this game's count of wild battles (Compose state: the row shows it). */
    @Volatile private var shinyAlertOn = false
    private val shinyHunt = com.pokedaisy.app.companion.data.ShinyHunt()
    private val shinyHuntState = androidx.compose.runtime.mutableIntStateOf(0)

    /** Each sample (emu thread): a wild battle counted, and a shiny foe announced, with FF and turbo stopped. */
    /** SHINY ALERT works here: a GBA game SHINY ODDS knows (its battlers' shiny read), or a Game Boy Color / DS game whose
     * reader works shininess out the game's way - Gen 2's DVs (not Polished Crystal's own shiny bit), Gen 4 / 5's IDs. */
    private fun shinyAlertWorks(game: com.pokedaisy.app.companion.data.GameKind? = telemetry.snapshot.value.game): Boolean =
        shinyTarget != null || romBlocked == null && ((game.isGen2 && game != com.pokedaisy.app.companion.data.GameKind.POLISHED_CRYSTAL) || game.isDsGame)

    private fun updateShinyHunt(snap: com.pokedaisy.app.companion.data.SnapshotView) {
        if (!snap.connected || !shinyAlertOn || !shinyAlertWorks(snap.game)) return
        val step = shinyHunt.sample(snap.inBattle, com.pokedaisy.app.companion.data.ShinyHunt.wild(snap), snap.battleOpponent)
        val key = romKey
        if (step.counted) {
            val n = shinyHuntState.intValue + 1
            shinyHuntState.intValue = n
            Prefs(this).setShinyHuntFor(key, n)
        }
        val shiny = step.shiny ?: return
        val after = shinyHuntState.intValue
        Log.i("pokedaisy", "shiny alert: ${shiny.name} after $after wild battles")
        shinyHuntState.intValue = 0
        Prefs(this).setShinyHuntFor(key, 0)
        runOnUiThread {
            if (::engine.isInitialized) engine.setFastForwardToggled(false)
            input.releaseAll()
            unlockSound?.play()
            showHud(com.pokedaisy.app.companion.i18n.tr("SHINY {0}! After {1} wild battles", shiny.shownName, after), SHINY_ALERT_MS)
        }
    }

    /** AUTO REFILL ([com.pokedaisy.app.companion.data.RepelRefill]): the option (cached for the emu thread), the count the last
     * sample read, the kind working (null = not known) and the one refill in flight. */
    @Volatile private var repelRefillOn = false
    private var repelPrev = 0
    @Volatile private var repelKind: Int? = null
    private val repelRefillBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether this game can take a REPEL from the companion: what AUTO REFILL needs (the quick menu's REPEL rule). */
    private fun repelRefillSupported(): Boolean =
        dsRepelKnown() || fieldItemSupport()?.let { s -> s.fns.repelItems.any { s.canUse(it) } } == true

    /** Each sample (emu thread): the kind working followed, and one more of it put on while 1..REFILL_AT steps are left. */
    private fun updateRepelRefill(snap: com.pokedaisy.app.companion.data.SnapshotView) {
        if (!snap.connected) return
        val steps = snap.repelSteps ?: itemUse.repelVar?.let { snap.progress?.variable(it) }?.let(itemUse::repelSteps) ?: 0
        val kinds = com.pokedaisy.app.companion.REPEL_KINDS
        if (steps == 0) repelKind = null
        else if (repelPrev == 0) repelKind = com.pokedaisy.app.companion.data.RepelRefill.kindOf(kinds, steps)
        repelPrev = steps
        if (!repelRefillOn || snap.inBattle || steps !in 1..com.pokedaisy.app.companion.data.RepelRefill.REFILL_AT) return
        val kind = com.pokedaisy.app.companion.data.RepelRefill.pick(kinds, { id -> snap.items.any { it.itemId == id && it.quantity > 0 } }, repelKind)
            ?: return
        if (!::engine.isInitialized || !repelRefillBusy.compareAndSet(false, true)) return
        val dsCfg = if (dsRepelKnown()) snap.game else null
        val support = if (dsCfg == null) fieldItemSupport()?.takeIf { it.canUse(kind.first) } else null
        if (dsCfg == null && support == null) { repelRefillBusy.set(false); return }
        engine.requestFieldItemJob(false) { calls, busy ->
            val out = runCatching {
                if (dsCfg != null) {
                    val f = engine.fieldState
                    val free = f and MgbaCore.FIELD_SCREEN != 0 && f and (MgbaCore.FIELD_LOCKED or MgbaCore.FIELD_MENU or MgbaCore.FIELD_BATTLE) == 0
                    com.pokedaisy.app.companion.data.gen5ConfigFor(dsCfg)?.let { com.pokedaisy.app.companion.data.useGen5Repel(it, calls, kind.first, kind.second, free, topUp = true) }
                        ?: com.pokedaisy.app.companion.data.useGen4Repel(com.pokedaisy.app.companion.data.gen4ConfigFor(dsCfg)!!, calls, kind.first, kind.second, free, topUp = true)
                } else com.pokedaisy.app.companion.data.refillRepel(support!!.fns, kind.first, busy, calls)
            }.getOrElse { Log.e("pokedaisy", "repel refill failed", it); null }
            Log.i("pokedaisy", "repel refill: ${kind.first} -> $out")
            if (out?.result == com.pokedaisy.app.companion.data.ItemUseResult.USED) {
                repelKind = kind.first
                val name = com.pokedaisy.app.companion.data.itemName(kind.first)
                runOnUiThread { showHud(com.pokedaisy.app.companion.i18n.tr("Another {0} went on", name)) }
            }
            repelRefillBusy.set(false)
        }
    }

    /** SETTINGS > COMPANION BUTTON: the buttons on the game screen that open the companion - the HUD's COMPANION and the white
     * edge tab ([SidePanel.openTabShown]) - shown at all (Compose state: the HUD reads it). */
    private val companionButtonShown = androidx.compose.runtime.mutableStateOf(true)

    /** LOW HP BEEP / FIELD POISON: the ROM's patches ([com.pokedaisy.app.companion.data.QuietPatches]; null = both N/A). */
    private var quietFound: com.pokedaisy.app.companion.data.QuietPatches.Found? = null

    private fun syncQuietPatches() {
        val p = Prefs(this)
        if (::engine.isInitialized) engine.setQuietPatches(quietFound, !p.lowHpBeep, !p.fieldPoison)
    }
    private fun syncLevelCap() { currentGameOptions()?.setLevelCapEnabled(Prefs(this).levelCap) }
    private fun syncHmFree(on: Boolean = Prefs(this).hmFree) { currentGameOptions()?.setHmFree(hmPatch, on) }
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
        override val lastBall get() = lastBallNow()
        override fun throwBall() {
            val ball = lastBallNow() ?: return
            val fns = ballFns ?: return
            val remove = fieldItemSupport()?.fns?.removeBagItem?.takeIf { it != 0L } ?: return
            closeCompanionWindow()
            lastBallId = ball.itemId
            engine.battleThrowBall(fns, ball.itemId, remove)
        }
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

    private val sharedItemUse = com.pokedaisy.app.companion.FieldItemUse(
        support = ::fieldItemSupport,
        session = { if (::engine.isInitialized && engine.running) engine.gameSession else null },
        inBattle = { telemetry.snapshot.value.inBattle },
        deliver = { result -> runOnUiThread(result) },
    )

    private val sharedClassicFly = com.pokedaisy.app.companion.ClassicFlyAction(
        sharedItemUse, ::fieldItemSupport,
        session = { if (::engine.isInitialized && engine.running) engine.gameSession else null },
    )

    private val itemUse = object : com.pokedaisy.app.companion.ItemUse by sharedItemUse {
        /** A DS game's Repels ([com.pokedaisy.app.companion.data.useGen4Repel], Black's [com.pokedaisy.app.companion.data.useGen5Repel]);
         * nothing else yet. */
        private fun dsRepel(itemId: Int) = dsRepelKnown() && com.pokedaisy.app.companion.data.GEN4_REPEL_KINDS.any { it.first == itemId }
        override fun canUse(itemId: Int) = dsRepel(itemId) || tmSupport(itemId) || fieldItemSupport()?.canUse(itemId) == true
        override fun marked(itemId: Int) = dsRepel(itemId) || tmSupport(itemId) || sharedItemUse.marked(itemId)

        /** [work] on the emu thread with the game's functions, [done] with its answer on the main thread;
         * [failed] when there's no game to ask (or the work throws). */
        private fun <T> onGame(failed: T, done: (T) -> Unit, work: (com.pokedaisy.app.companion.data.FieldItemSupport, com.pokedaisy.app.companion.data.GameCalls, Boolean) -> T) =
            sharedItemUse.onGame(failed, done, work)

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
            if (tmSupport(itemId)) return openTmScreen(itemId, done)
            sharedItemUse.use(itemId, done)
        }

        /** Ruby / Sapphire's TM party menu: its way back to the bag turned into the field (tmBagReturnWatch), a frame at a
         * time while it runs (20 minutes at most). */
        private fun watchTmBagReturn(tm: com.pokedaisy.app.companion.data.TmFns, frames: Int) {
            if (frames > 20 * 60 * 60) return
            engine.requestFieldItemJob(false, 1) { c, _ ->
                if (runCatching { com.pokedaisy.app.companion.data.tmBagReturnWatch(tm, c) }.getOrDefault(false)) watchTmBagReturn(tm, frames + 1)
            }
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
                        if (ok && tm.bagReturn != 0L) watchTmBagReturn(tm, 0)
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
            sharedItemUse.openPc(opened)
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

        override fun center(): com.pokedaisy.app.companion.CenterSupport? {
            val c = centerFound ?: return null
            val fns = fieldItemSupport()?.fns?.takeIf { it.pc != null && it.party != null } ?: return null
            return com.pokedaisy.app.companion.CenterSupport(
                heal = true, nickname = c.nickname != null, forget = c.forget != null,
                relearn = (c.relearn != null && tmFns() != null || c.relearnSpecial != null) && fns.party != null,
            )
        }

        private fun onCenter(
            done: (com.pokedaisy.app.companion.data.CenterResult) -> Unit, screen: Boolean = false,
            work: (com.pokedaisy.app.companion.data.FieldItemFns, com.pokedaisy.app.companion.data.PokemonCenter.Found,
                   com.pokedaisy.app.companion.data.GameCalls, Boolean) -> com.pokedaisy.app.companion.data.CenterResult,
        ) {
            val c = centerFound ?: return done(com.pokedaisy.app.companion.data.CenterResult.FAILED)
            val inBattle = telemetry.snapshot.value.inBattle
            onGame(com.pokedaisy.app.companion.data.CenterResult.FAILED, { r ->
                // A screen of the game's (naming, the Move Tutor's) is the game's to show: the window over it goes.
                if (screen && r == com.pokedaisy.app.companion.data.CenterResult.OK) closeCompanionWindow()
                done(if (inBattle && r == com.pokedaisy.app.companion.data.CenterResult.NOT_NOW) com.pokedaisy.app.companion.data.CenterResult.IN_BATTLE else r)
            }) { s, calls, busy -> work(s.fns, c, calls, busy) }
        }

        override fun centerHeal(done: (com.pokedaisy.app.companion.data.CenterResult) -> Unit) =
            onCenter(done) { fns, c, calls, busy -> com.pokedaisy.app.companion.data.centerHeal(fns, c, busy, calls) }

        override fun centerNickname(slot: Int, personality: Long, done: (com.pokedaisy.app.companion.data.CenterResult) -> Unit) =
            onCenter(done, screen = true) { fns, c, calls, busy -> com.pokedaisy.app.companion.data.centerNickname(fns, c, slot, personality, busy, calls) }

        override fun centerForget(slot: Int, personality: Long, moveIndex: Int, move: Int, done: (com.pokedaisy.app.companion.data.CenterResult) -> Unit) =
            onCenter(done) { fns, c, calls, busy -> com.pokedaisy.app.companion.data.centerForget(fns, c, slot, personality, moveIndex, move, busy, calls) }

        /** The Move Tutor's screen in two steps, like the TM screen's: the checks and the fade, then once it's black the screen. */
        override fun centerRelearn(slot: Int, personality: Long, done: (com.pokedaisy.app.companion.data.CenterResult) -> Unit) {
            // Ruby / Sapphire: one step, the Fallarbor tutor's own script.
            // Ruby / Sapphire (their TM screen the bag's way, no gFieldCallback route): the Fallarbor tutor's own script.
            if ((tmFns()?.bagReturn ?: 0L).let { tmFns() == null || it != 0L } && centerFound?.relearnSpecial != null)
                return onCenter(done, screen = true) { fns, c, calls, busy ->
                    com.pokedaisy.app.companion.data.centerRelearnScript(fns, c, slot, personality, busy, calls)
                }
            val tm = tmFns() ?: return done(com.pokedaisy.app.companion.data.CenterResult.FAILED)
            onCenter(done, screen = true) { fns, c, calls, busy ->
                val r = com.pokedaisy.app.companion.data.centerRelearnStart(fns, c, tm, slot, personality, busy, calls)
                if (r == com.pokedaisy.app.companion.data.CenterResult.OK) {
                    engine.requestFieldItemJob(false, com.pokedaisy.app.companion.data.TM_FADE_FRAMES) { g, leftField ->
                        val ok = com.pokedaisy.app.companion.data.centerRelearnFinish(fns, c, tm, slot, personality, g, leftField)
                        Log.i("pokedaisy", "move relearner for slot $slot: ${when {
                            ok -> "open"
                            leftField -> "the game left the field during the fade, not taken"
                            else -> "failed, field handed back"
                        }}")
                    }
                }
                r
            }
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
    private val quickActions: com.pokedaisy.app.companion.ui.GameQuickActions by lazy {
        com.pokedaisy.app.companion.ui.GameQuickActions(
            quickMenu, Prefs(this), companionSettings, { telemetry.snapshot.value }, { itemUse }, { flyAction },
            ::handleHotkey, { showHud(it) }, active = { ::engine.isInitialized && !isDestroyed },
            // HEAL: the party's bars filling up (HealedAnimation), not a notice.
            healed = { before -> healShow.value = com.pokedaisy.app.companion.ui.HealShow(before) },
            extra = object : com.pokedaisy.app.companion.ui.QuickActions {
                override fun offered(action: com.pokedaisy.app.companion.QuickAction) = when (action) {
                    com.pokedaisy.app.companion.QuickAction.FLIGHT -> flightSupport() != null
                    // Only while a party Pokémon knows it (the snapshot's moves), or anyone with NO HMS NEEDED: the tile isn't there otherwise.
                    com.pokedaisy.app.companion.QuickAction.FLASH -> flashFns() != null && fieldItemSupport()?.fns?.party != null &&
                        (com.pokedaisy.app.companion.data.HmFree.active ||
                            telemetry.snapshot.value.party.any { m -> !m.isEgg && m.moves.any { it.id == com.pokedaisy.app.companion.ui.FLASH_MOVE } })
                    com.pokedaisy.app.companion.QuickAction.REPEL -> dsRepelKnown() ||
                        fieldItemSupport()?.let { s -> s.fns.repelItems.any { s.canUse(it) } } == true
                    com.pokedaisy.app.companion.QuickAction.BIKE, com.pokedaisy.app.companion.QuickAction.ROD -> keyItemsWork()
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

                override fun flashDark(): Boolean? {
                    val flash = flashFns() ?: return null
                    val progress = telemetry.snapshot.value.progress
                    return com.pokedaisy.app.companion.data.flashDark(flash, { a, n -> com.pokedaisy.app.companion.data.InProcessReader.readCoreMemory(a, n) }) { f ->
                        progress?.flag(f)
                    }
                }

                override fun turbo() = input.turboLatch != 0
        })
    }

    private fun runQuickAction(action: com.pokedaisy.app.companion.QuickAction) = quickActions.run(action)

    private fun runPlatformQuickAction(action: com.pokedaisy.app.companion.QuickAction) {
        if (!::engine.isInitialized) return
        val snap = telemetry.snapshot.value
        when (action) {
            com.pokedaisy.app.companion.QuickAction.FLASH -> useFlash()
            com.pokedaisy.app.companion.QuickAction.FLIGHT -> toggleFlight()
            com.pokedaisy.app.companion.QuickAction.BIKE ->
                com.pokedaisy.app.companion.data.KeyItems.firstIn(snap.items, com.pokedaisy.app.companion.data.KeyItems.BIKES)?.let(::useKeyItem)
            com.pokedaisy.app.companion.QuickAction.ROD ->
                com.pokedaisy.app.companion.data.KeyItems.firstIn(snap.items, com.pokedaisy.app.companion.data.KeyItems.RODS)?.let(::useKeyItem)
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
        // Platinum / HeartGold / SoulSilver (and the hacks on their retail RAM): the Bag's register and Y (gen4RegisterAddr).
        if (romIsNds) return dsKeyItemsWork()
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
    private fun dsInstantTextWorks(): Boolean = romIsNds && romBlocked == null && !dsperate && ::engine.isInitialized &&
        engine.ndsGameCode.take(3) in DS_TEXT_CODES


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
        if (romIsNds) return useDsKeyItem(itemId)
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
                    input.setPress(CoreKey.SELECT)
                    engine.requestFieldItemJob(false, delayFrames = 2) { _, _ -> input.setPress(0) }
                    engine.requestFieldItemJob(false, delayFrames = down) { _, _ -> input.setPress(CoreKey.DOWN) }
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
        input.setPress(CoreKey.SELECT)
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
            sharedClassicFly.targets(done)
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
                scenicFly(support, mapsec, done) { sharedClassicFly.fly(mapsec, targets, done) }
                return
            }
            sharedClassicFly.fly(mapsec, targets, done)
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
            ?: com.pokedaisy.app.companion.data.MonArt.load(filesDir.toOkioPath(), n) else null
    }

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

    private fun dayNightSupported() = !romIsGameBoy && !romIsNds && romBlocked == null && ::engine.isInitialized &&
        com.pokedaisy.app.companion.data.activeGame !in com.pokedaisy.app.companion.data.DayNight.OWN_DAY_NIGHT
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
        val stored = com.pokedaisy.app.companion.PrefsCompanionSettings(Prefs(this), languageRomCode = { romCode })
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
        override fun setAutoRun(on: Boolean) {
            if (on) com.pokedaisy.app.companion.FeatureTips.used(TipId.AUTO_RUN)
            stored.setAutoRun(on)
            if (::engine.isInitialized) engine.autoRun = on
        }
        override fun setRunIndoors(on: Boolean) {
            stored.setRunIndoors(on)
            if (::engine.isInitialized) engine.runIndoors = on
        }
        // Ruby / Sapphire tell indoor maps by their type (flash, weather and the region map read it too): not theirs.
        override val runIndoorsSupported get() = fieldGame != WideScreen.OFF && com.pokedaisy.app.companion.data.GameField.supportsRunIndoors(romCode)
        override fun setWildEncounters(percent: Int) {
            if (percent == 0) com.pokedaisy.app.companion.FeatureTips.used(TipId.WILD)
            stored.setWildEncounters(percent)
            if (::engine.isInitialized) engine.wildEncounters = percent
        }
        override val wildEncountersSupported get() = (fieldGame != WideScreen.OFF && wildFlag != 0L) || gbField() != null ||
            romIsNds && ::engine.isInitialized && engine.fieldState and MgbaCore.FIELD_KNOWN != 0
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
            if (coopRefuses()) return
            if (on) com.pokedaisy.app.companion.FeatureTips.used(TipId.REUSABLE_TMS)
            stored.setReusableTms(on)
            if (::engine.isInitialized) engine.setReusableTms(tmSites, on)
            if (::engine.isInitialized) engine.dsReusableTms = on && dsInstantTextWorks()
        }
        override val reusableTmsSupported get() = tmSites.isNotEmpty() || dsInstantTextWorks()
        override fun setInstantText(on: Boolean) {
            stored.setInstantText(on)
            if (::engine.isInitialized) engine.setInstantText(textPatch, on)
            if (::engine.isInitialized) engine.dsInstantText = on && dsInstantTextWorks()
        }
        override val instantTextSupported get() = textPatch != null || dsInstantTextWorks()
        override val hmFree get() = Prefs(this@PokeDaisyActivity).hmFree
        override fun setHmFree(on: Boolean) {
            if (coopRefuses()) return
            stored.setHmFree(on)
            syncHmFree(on)
        }
        override val hmFreeSupported get() = hmPatch != null
        override val lowHpBeep get() = Prefs(this@PokeDaisyActivity).lowHpBeep
        override fun setLowHpBeep(on: Boolean) {
            if (coopRefuses()) return
            Prefs(this@PokeDaisyActivity).lowHpBeep = on
            syncQuietPatches()
        }
        override val lowHpBeepSupported get() = quietFound?.beep != null
        override val fieldPoison get() = Prefs(this@PokeDaisyActivity).fieldPoison
        override fun setFieldPoison(on: Boolean) {
            if (coopRefuses()) return
            Prefs(this@PokeDaisyActivity).fieldPoison = on
            syncQuietPatches()
        }
        override val fieldPoisonSupported get() = quietFound?.poison != null
        override val expShare get() = com.pokedaisy.app.companion.data.ExpShare.Mode.of(Prefs(this@PokeDaisyActivity).expShare)
        override fun setExpShare(mode: com.pokedaisy.app.companion.data.ExpShare.Mode) {
            if (coopRefuses()) return
            stored.setExpShare(mode)
            syncExpShare()
        }
        override val expShareSupported get() = expShareFound != null
        override val levelCap get() = Prefs(this@PokeDaisyActivity).levelCap
        override fun setLevelCap(on: Boolean) {
            if (coopRefuses()) return
            stored.setLevelCap(on)
                syncLevelCap()
        }
        override val levelCapSupported get() = currentGameOptions()?.levelCapSupported == true
        override val levelCapLevel get() = levelCapNow?.level
        override val repelRefill get() = Prefs(this@PokeDaisyActivity).repelRefill
        override fun setRepelRefill(on: Boolean) {
            Prefs(this@PokeDaisyActivity).repelRefill = on
            repelRefillOn = on
        }
        override val repelRefillSupported get() = repelRefillSupported()
        override val shinyAlert get() = Prefs(this@PokeDaisyActivity).shinyAlert
        override fun setShinyAlert(on: Boolean) {
            val p = Prefs(this@PokeDaisyActivity)
            p.shinyAlert = on
            // Turning it on starts a hunt: the count from 0.
            if (on) { p.setShinyHuntFor(romKey, 0); shinyHuntState.intValue = 0 }
            shinyAlertOn = on
        }
        override val shinyHuntCount get() = shinyHuntState.intValue
        override val shinyAlertSupported get() = shinyAlertWorks()
        override val battleRetry get() = Prefs(this@PokeDaisyActivity).battleRetry
        override fun setBattleRetry(on: Boolean) {
            Prefs(this@PokeDaisyActivity).battleRetry = on
            battleRetryOn = on
        }
        override fun answerBattleRetry(retry: Boolean) = this@PokeDaisyActivity.answerBattleRetry(retry)
        override val lastBallButton get() = Prefs(this@PokeDaisyActivity).lastBallButton
        override fun setLastBallButton(on: Boolean) {
            Prefs(this@PokeDaisyActivity).lastBallButton = on
            lastBallOn = on
        }
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
        override val followerReady get() = com.pokedaisy.app.companion.data.MonArt.available(filesDir.toOkioPath()).isNotEmpty()
        override fun setFollower(on: Boolean) {
            stored.setFollower(on)
            applyFollower()
        }
        override val dayNightSupported get() = dayNightSupported()
        override val rivalLookSupported get() = lookRunner.rivalSupported
        override val rideVehicle get() = com.pokedaisy.app.companion.data.RideVehicle.of(Prefs(this@PokeDaisyActivity).rideVehicleId)
        override fun setRideVehicle(v: com.pokedaisy.app.companion.data.RideVehicle) {
            Prefs(this@PokeDaisyActivity).rideVehicleId = v.id
            lookRunner.apply()   // the bike sheets rewritten: the next frame the bike shows is the new ride
        }
        override fun setRivalLook(on: Boolean) {
            if (coopRefuses()) return
            stored.setRivalLook(on)
            lookRunner.apply()
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
            Prefs(this@PokeDaisyActivity).battleButtons = on
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
        override fun setGameAspect(aspect: com.pokedaisy.app.companion.GameAspect) {
            if (aspect == com.pokedaisy.app.companion.GameAspect.WIDE) com.pokedaisy.app.companion.FeatureTips.used(TipId.EXPAND)
            stored.setGameAspect(aspect)
            runOnUiThread { syncGameScreen() }
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
        override fun setTips(on: Boolean) {
            stored.setTips(on)
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
        override fun setGbaColors(on: Boolean) {
            stored.setGbaColors(on)
            runOnUiThread { syncGameScreen() }
        }
        override fun setUnlitScreen(on: Boolean) {
            stored.setUnlitScreen(on)
            runOnUiThread { syncGameScreen() }
        }
        override fun setScreenFilter(filter: ScreenFilter) {
            stored.setScreenFilter(filter)
            runOnUiThread { syncGameScreen() }
        }
        override fun setGridStrength(strength: GridStrength) {
            stored.setGridStrength(strength)
            runOnUiThread { syncGameScreen() }
        }
        override fun setSmoothing(mode: Smoothing) {
            stored.setSmoothing(mode)
            runOnUiThread { syncGameScreen() }
        }
        override val smoothingAvailable get() = GlCaps.gles3(this@PokeDaisyActivity)
        override fun setLightCompanion(mode: com.pokedaisy.app.companion.LightCompanionMode) {
            com.pokedaisy.app.companion.LightCompanion.mode = mode
            Prefs(this@PokeDaisyActivity).lightCompanion = mode.id
            runOnUiThread { syncGameScreen() }
        }
        override fun setCompanionShaders(on: Boolean) {
            stored.setCompanionShaders(on)
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
            prefs.coopRelayOnly = on
            com.pokedaisy.app.companion.coop.CoopSession.restart(this@PokeDaisyActivity, prefs)
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
                // HIDE BATTLE MENU follows the game being detected, the companion closed / reopened, a second screen.
                syncBattleMenuHide()
                if (::engine.isInitialized) {
                    val snap = telemetry.snapshot.value
                    engine.dayNightTint = if (Prefs(this@PokeDaisyActivity).dayNight && dayNightSupported() &&
                        com.pokedaisy.app.companion.data.DayNight.outdoors(snap.mapType)
                    ) com.pokedaisy.app.companion.data.DayNight.now() else com.pokedaisy.app.companion.data.DayNight.NONE
                }
                kotlinx.coroutines.delay(500)
            }
        }
        Prefs(this).let { com.pokedaisy.app.companion.FeatureTips.init(it.tipStore, it.tips) }
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
            load3ds(romFile)
            return
        }
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
        flightArea = com.pokedaisy.app.companion.data.FlightArea.Keeper(filesDir.toOkioPath(), crc)
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
        wildFlag = 0L
        currentGameOptions()?.resetMetadata()
        tmSites = emptyList()
        if (::engine.isInitialized) engine.setReusableTms(tmSites, false)
        textPatch = null
        if (::engine.isInitialized) engine.setInstantText(null, false)
        syncHmFree(false)
        centerFound = null
        syncExpShare()
        quietFound = null
        syncQuietPatches()
        syncLevelCap()
        repelRefillOn = Prefs(this).repelRefill
        shinyAlertOn = Prefs(this).shinyAlert
        lastBallOn = Prefs(this).lastBallButton
        battleRetryOn = Prefs(this).battleRetry
        retryTaken = false
        retryLost = false
        retryInBattle = false
        lastBallId = Prefs(this).lastBallFor(crc)
        ballFns = null
        ballFnsFor = null
        ballItemsBefore = emptyList()
        shinyHuntState.intValue = Prefs(this).shinyHuntFor(crc)
        repelPrev = 0
        repelKind = null
        shinyTarget = null
        if (::engine.isInitialized) engine.setShinyTarget(null)
        syncShinyAchievements()
        gbFieldCache = null
        if (::engine.isInitialized) engine.gbField = null
        // LOW HP BEEP / FIELD POISON: the beep's call and the field poison's, by their code (the other options are
        // GameOptions' discovery).
        if (gba && romBlocked == null) Thread({
            val quiet = runCatching { com.pokedaisy.app.companion.data.QuietPatches.find(r.toOkioPath()) }.getOrNull()
            Log.i("pokedaisy", "quiet patches: beep ${quiet?.beep != null}, poison ${quiet?.poison != null}")
            runOnUiThread {
                if (romData != r) return@runOnUiThread
                quietFound = quiet
                syncQuietPatches()
            }
        }, "quiet-patches").start()
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
        engine = EmulatorEngine(input, states!!, ffMusicCache, battleSession, Prefs(this), telemetry).apply {
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
                // AUTO REFILL: another REPEL on before the one working runs out.
                updateRepelRefill(snap)
                // SHINY ALERT: the hunt's count, and a shiny foe said out loud.
                updateShinyHunt(snap)
                // LAST BALL: its functions once the game is known, and the ball a battle used.
                updateLastBall(snap)
                // BOSS RETRY: a state as a trainer battle starts, and the question after a loss.
                updateBattleRetry(snap)
                // SHINY ODDS: the foe party of a game it knows (retail FireRed / Emerald), handed to the engine.
                if (snap.connected) telemetry.knownShinyTarget().let { t ->
                    if (t != shinyTarget) {
                        shinyTarget = t
                        engine.setShinyTarget(t)
                        syncShinyAchievements()
                    }
                }
                // WILD ENCOUNTERS on a Game Boy game is held by the engine, frame by frame.
                if (snap.connected && romIsGameBoy) engine.gbField = gbField()
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
            playback.startSampling(romIsGameBoy, romIsNds,
                game = object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = postToEmu(block::run)
                }, current = { ::engine.isInitialized && engine === this && running }, after = afterSample)
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
        if ((gba || gameBoy) && romBlocked == null) {
            val current = engine
            current.gameOptions.start(r.toOkioPath(), gameBoy = gameBoy, configureWild = { flag, _ ->
                if (romData == r && engine === current) {
                    wildFlag = flag; current.wildFlag = flag
                    flag != 0L
                } else false
            }, ready = { found ->
                if (romData == r && engine === current) {
                    tmSites = found.sites; textPatch = found.text
                    centerFound = found.center
                    syncHmFree(); syncExpShare(); syncLevelCap()
                    current.setReusableTms(tmSites, Prefs(this).reusableTms)
                    current.setInstantText(textPatch, Prefs(this).instantText)
                    // A Game Boy cart's LOW HP BEEP (GbGamePatches): its patch where the GBA's QuietPatches would put it.
                    if (gameBoy) { quietFound = com.pokedaisy.app.companion.data.QuietPatches.Found(found.gbBeep, found.gbPoison); syncQuietPatches() }
                }
            })
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
                        com.pokedaisy.app.companion.data.coop.PartnerArt.build(dir.toOkioPath(), rom, female, look)?.let { coopArt[key] = it }
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
        e.dsInstantText = p.instantText && dsInstantTextWorks()
        e.dsReusableTms = p.reusableTms && dsInstantTextWorks()
        e.setHmFree(hmPatch, p.hmFree)
        e.setExpShare(expShareFound, if (expShareFound != null) com.pokedaisy.app.companion.data.ExpShare.Mode.of(p.expShare) else com.pokedaisy.app.companion.data.ExpShare.Mode.OFF)
        e.setLevelCap(levelCapFound, if (levelCapFound != null && p.levelCap) levelCapNow?.level ?: 0 else 0)
        e.setQuietPatches(quietFound, !p.lowHpBeep, !p.fieldPoison)
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

    /** This ROM's cheats as last read, by [romKey]. The companion asks for them while it draws its SETTINGS, and parsing an
     * imported file each time (RetroArch's Platinum one has 2321 cheats) held the UI thread until the app stopped
     * answering (GitHub #786). Read again when a game starts: the top screen's Settings may have changed them. */
    private var cheatCache: Pair<String, List<com.pokedaisy.app.cheats.Cheat>>? = null

    private fun cheats(): List<com.pokedaisy.app.cheats.Cheat> {
        val key = romKey
        cheatCache?.takeIf { it.first == key }?.let { return it.second }
        return cheatStore()?.load().orEmpty().also { cheatCache = key to it }
    }

    /** Hands the engine this ROM's enabled cheats (none with the master switch off); it loads
     * them before the first frame, or on the next one while the game runs. */
    private var coopCheatNoticed = false

    /** [reread]: the file read again (a game starting: the top screen's Settings may have changed it); a switch flipped
     * mid-game only hands the list already read back to the engine - reading an imported file again on the UI thread for
     * each flip held it for seconds (GitHub #786). */
    private fun syncCheats(reread: Boolean = false) {
        if (!::engine.isInitialized) return
        gameCheats()?.let { if (reread || it.list.isEmpty()) it.refresh(); it.apply() }
    }

    private fun submitCheats(cheatText: String) {
        if (!::engine.isInitialized) return
        val enabled = Prefs(this).cheatsEnabled
        val coopOn = com.pokedaisy.app.companion.coop.CoopSession.gameOn || coopLock != null
        val text = com.pokedaisy.app.companion.coop.CoopSession.cheatsForEngine(enabled, coopOn) {
            cheatText
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

    private val catchFile by lazy { com.pokedaisy.app.companion.data.coop.FileCatchStore(filesDir.toOkioPath() / "coop" / "catches.txt") }

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
                return com.pokedaisy.app.companion.coop.CoopSaveId.read(SavesLocation.saveFor(this@PokeDaisyActivity, Prefs(this@PokeDaisyActivity), r).toOkioPath())
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
        val id = com.pokedaisy.app.companion.coop.CoopSaveId.read(save.toOkioPath()) ?: return
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
        val cheatsLoaded = prefs.cheatsEnabled && cheats().any { it.enabled }
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
                else com.pokedaisy.app.companion.data.RetailDumps.matchStream(code, rev, RomArchive.romSize(r)) { RomArchive.open(r).source() } ?: own
            com.pokedaisy.app.companion.coop.CoopSession.startForGame(applicationContext, prefs, sha1, save, cheatsLoaded)
            runOnUiThread { coopAttach(sha1) }   // joined battles + presence: retail English Emerald only
            startCoopLock(sha1, save, cheatsLoaded, gen)
        }
    }

    /** Starts the loaded ROM's engine, resuming where it was left; false with no ROM. */
    private fun startGame(): Boolean {
        val r = rom ?: return false
        n3ds?.let { it.start(); return true }
        val st = states ?: return false
        AudioEffects.load(Prefs(this))
        AudioEffectsTrack.open(this)
        val save = SavesLocation.saveFor(this, Prefs(this), r)
        engine.ndsCore = if (dsperate) DsCore.CORE_DSPERATE else DsCore.CORE_MELONDS   // DS CORE (pickDsCore)
        // Cheats may have changed in the library's Settings since the game was last up.
        cheatCache = null
        syncCheats(reread = true)
        // Back from sleep: the game held on its frame (onPause) and goes on from it. The state written as it went to
        // sleep is used up, as a resume's is once loaded.
        if (engine.sleeping) {
            CoopBattles.resume()   // [pauseGame] ended any joined battle before the sleep: battles may start again
            engine.wake()
            st.files.removeState(st.files.resumeFile)
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
        com.pokedaisy.app.companion.FeatureTips.available = { false }
        Hotkeys.dsActive = { false }
        endN3ds()
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
        stageOwner?.destroy()
        stageOwner = null
        stageRecomposer.cancel()
        stageRecomposing.cancel()
        stopCoopPresence()
        lookGender.cancel()
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
        if (::engine.isInitialized) gameActions.cancelTouch()
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
        quickShown.value = prefs.quickMenuActions
        battleButtonsOn.value = prefs.battleButtons
        battleHints.value = prefs.showHints && prefs.battleInfo
        battleStyle.value = com.pokedaisy.app.companion.BattleStyle.of(prefs.battleStyle)
        com.pokedaisy.app.companion.ui.AchievementIcons.shown = prefs.raIcons
        hudUpright.value = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        com.pokedaisy.app.companion.ui.CompanionStatusBar.shown = prefs.statusBar && prefs.statusBarOnCompanion
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
        engine = EmulatorEngine(input, states!!, FfMusicCache(getExternalFilesDir(null) ?: filesDir, key), com.pokedaisy.app.companion.GameBattleInput(telemetry, null, 0, input = input), Prefs(this), telemetry)
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
        val field = if (romIs3ds) n3dsOnField.value && !n3dsFloating.value else onField
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
                    com.pokedaisy.app.companion.ui.PokemonPicturesSwitch.version >= 0 &&
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
                onWindow = { battlePicking.value = it },
                style = battleStyle.value,
            )
            com.pokedaisy.app.companion.ui.QuickMenu(quickMenu, snap, quickActions, quickShown.value, hudStyle.value.look)
            com.pokedaisy.app.companion.ui.HealedAnimation(healShow.value, hudStyle.value.look) { healShow.value = null }
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
                com.pokedaisy.app.companion.ui.BattleRetryAskHost(
                    active = !tipsOnCompanion.value, roomy = false,
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
        if (!tipsOnCompanion.value && com.pokedaisy.app.companion.ui.BattleRetryAsk.showing) return false
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
        if (::engine.isInitialized) gameActions.cancelTouch()
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
        if (hotkeyTakes(event.keyCode, down)) return true
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
            hold = ::holdGameAction, companion = ::toggleGameCompanion)

    private fun holdGameAction(action: Hotkeys.Action, down: Boolean) {
        if (romIs3ds) { n3dsHotkey(Hotkeys.Event(action, down)); return }
        when (action) {
            Hotkeys.Action.FF_HOLD -> engine.setFastForwardHeld(down)
            Hotkeys.Action.SLOWMO_HOLD -> engine.setSlowmoHeld(down)
            Hotkeys.Action.REWIND_HOLD -> if (romIsNds) {
                // The rewind ring is mGBA's: the hotkey would do nothing at all and look broken.
                if (down) showHud(com.pokedaisy.app.companion.i18n.tr("Rewind doesn't work in DS games"))
            } else {
                if (down && !Prefs(this).rewind) showHud(com.pokedaisy.app.companion.i18n.tr("Rewind is off - turn it on in SETTINGS"))
                else if (down) showHud(com.pokedaisy.app.companion.i18n.tr("Rewinding"))
                engine.setRewindHeld(down)
            }
            else -> Unit
        }
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
            TipId.FLY -> flyAction.canFly() && (com.pokedaisy.app.companion.data.FlyWithoutMoveGame.active ||
                telemetry.snapshot.value.party.any { m -> !m.isEgg && m.moves.any { it.id == com.pokedaisy.app.companion.ui.FLY_MOVE } })
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
        dsperate = romIsNds && Prefs(this).dsCore.runs() == DsCore.CORE_DSPERATE
        if (!romIsNds) return
        wideGame = if (!dsperate && (WideScreen.ndsKnows(romCode, romRev) || romOrigin)) WideScreen.NDS else WideScreen.OFF
        if (::engine.isInitialized) engine.ndsCore = if (dsperate) DsCore.CORE_DSPERATE else DsCore.CORE_MELONDS
    }

    /** RESTART GAME (SETTINGS, and its hotkey): the game booted again from its save, the resume state dropped. */
    private fun restartGame() {
        if (coopRefuses()) return
        val r = rom ?: return
        if (romIs3ds) { endN3ds(); load3ds(r); startGame(); return }
        if (!::engine.isInitialized) return
        // Unbind the GL view from the current framebuffer FIRST and wait for
        // it to take — engine.stop() frees the native buffer the GL thread
        // (continuous render mode, its own clock) may still be reading; skip
        // this and it's a use-after-free race that crashes the process. See
        // EmulatorView.unbindCoreBlocking().
        view.unbindCoreBlocking()
        engine.stop()
        states?.let { com.pokedaisy.app.companion.data.StateResume.startFresh(it.files) }
        val save = SavesLocation.saveFor(this, Prefs(this), r)
        syncCheats(reread = true)
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
        /** LEVEL CAP: how often a sample may work the cap out again (a badge moves it). */
        /** How long SHINY ALERT's line stays on the game screen. */
        private const val SHINY_ALERT_MS = 8000L
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
