package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.cheats.CheatStore
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import com.pokedaisy.app.game.GameSession
import okio.Path

/** Owns per-game playback, option discovery/patches, battle sample delivery and the sampler lifetime.
 * Core frame scheduling, exceptional modes and rendering remain native. Closing ends the loaded-game lifetime;
 * stopping/restarting a native core for the same ROM does not close this owner. */
class GameRuntime(
    val session: GameSession,
    private val telemetry: TelemetryStore,
    private val prefs: Prefs,
    private val battle: GameBattleInput,
    optionPatcher: RomOptionPatcher,
    private val optionsAllowed: () -> Boolean,
    private val configureField: (Long) -> Boolean = { false },
    private val disableField: () -> Unit = {},
    private val restoreOptionPatchesOnClose: Boolean = true,
    private val applyTint: (Int, Int, Int) -> Unit = { _, _, _ -> },
) {
    private val closed = atomic(false)
    private val tint = DayNight.Transition()
    private var desiredTint = DayNight.NONE
    val dayNightSupported get() = !closed.value && DayNight.supported(gba,
        blocked || telemetry.snapshot.value.blocked != null, activeGame)

    /** The native core starts without a tint, even when this game-run owner is reused. */
    fun resetTint() { tint.reset(); desiredTint = DayNight.NONE }

    /** Both hosts call between frames, after the shared screen observers have run. */
    fun stepTint() {
        if (closed.value || !gba) return
        tint.step(if (dayNightSupported) desiredTint else DayNight.NONE,
            onField && !map && !FfMenuWatch.battling && battle.gameState == BATTLE_INPUT_NONE, applyTint)
    }
    private var sampler: GameSampler? = null
    private val shinyOwner = atomic<GameShinyOdds?>(null)
    val shiny get() = shinyOwner.value
    private val flightOwner = atomic<GameFlight?>(null)
    val flight get() = flightOwner.value
    private val followerOwner = atomic<GameFollower?>(null)
    val follower get() = followerOwner.value
    val followerSupported get() = GameFollower.supported(gba,
        blocked || telemetry.snapshot.value.blocked != null, fieldReadable, activeGame)

    fun bindFollower(core: com.pokedaisy.app.game.FollowerCore, files: Path,
                     prepareAssets: ((() -> Boolean) -> Unit) = {}): GameFollower {
        followerOwner.value?.let { return it }
        val owner = GameFollower(prefs, core, files, { followerSupported }, prepareAssets)
        if (!followerOwner.compareAndSet(null, owner)) { owner.close(); return followerOwner.value!! }
        if (closed.value) owner.close()
        return owner
    }

    fun stepFollower() {
        follower?.step(onField && !map && !FfMenuWatch.battling && battle.gameState == BATTLE_INPUT_NONE &&
            fieldState and GameField.SURF == 0)
    }

    fun bindFlight(create: () -> GameFlight): GameFlight {
        flightOwner.value?.let { return it }
        val owner = create()
        if (!flightOwner.compareAndSet(null, owner)) { owner.close(); return flightOwner.value!! }
        if (closed.value) owner.close()
        return owner
    }

    fun bindShiny(core: GameCalls, debug: Boolean, changed: () -> Unit = {}): GameShinyOdds {
        shinyOwner.value?.let { return it }
        val owner = GameShinyOdds(prefs, core, debug,
            { !closed.value && optionsAllowed() && !blocked && telemetry.snapshot.value.blocked == null }, changed)
        if (!shinyOwner.compareAndSet(null, owner)) { owner.close(); return shinyOwner.value!! }
        if (closed.value) owner.close()
        return owner
    }
    val options = GameOptions(optionPatcher, { !closed.value && !blocked &&
        telemetry.snapshot.value.blocked == null && optionsAllowed() })
    val tipMoments = GameTipMoments()

    private val stateSlots = atomic<StateSlotController?>(null)

    /** Native state transports differ; their controller has the same loaded-game lifetime. */
    fun bindStates(create: () -> StateSlotController): StateSlotController {
        stateSlots.value?.let { return it }
        val controller = create()
        if (!stateSlots.compareAndSet(null, controller)) {
            controller.close()
            return stateSlots.value!!
        }
        if (closed.value) controller.close()
        return controller
    }

    /** Restore state-dependent effects after the native core accepted a load, while still between frames.
     * Some adapters clear retry at the start of an attempt or already stage telemetry before boot. */
    fun afterStateLoad(
        restoreProgress: () -> Unit,
        settleAppearance: () -> Unit,
        reinstallStoryLock: () -> Unit = {},
        companionState: Path? = null,
        retryAlreadyReset: Boolean = false,
    ) {
        if (closed.value) return
        restoreProgress()
        if (!retryAlreadyReset) battleRetry.reset()
        settleAppearance()
        reinstallStoryLock()
        if (companionState != null) {
            telemetry.reset()
            SnapshotCache.read(companionState)?.let(telemetry::showCached)
            sample()
        }
    }

    var actions: GameActions? = null
        private set
    var cheats: GameCheats? = null
        private set
    private val registeredItemOwner = atomic<RegisteredItemUse?>(null)
    val registeredItems get() = registeredItemOwner.value
    private val gameBoyFieldOwner = atomic<GameBoyFieldActions?>(null)
    val gameBoyFields get() = gameBoyFieldOwner.value
    private val gameBoySupport = GameBoyFieldSupport()

    fun bindGameBoyFields(enabled: () -> Boolean, rom: (Long, Int) -> ByteArray?,
                          deliver: (() -> Unit) -> Unit, handedOver: () -> Unit): GameBoyFieldActions {
        gameBoyFieldOwner.value?.let { return it }
        val owner = GameBoyFieldActions(session,
            {
                val snapshot = telemetry.snapshot.value
                if (!closed.value && enabled() && snapshot.connected && snapshot.cachedTabs == null)
                    gameBoySupport.get(snapshot.game, rom) else null
            },
            { telemetry.snapshot.value.inBattle }, deliver, handedOver,
        )
        if (!gameBoyFieldOwner.compareAndSet(null, owner)) { owner.close(); return gameBoyFieldOwner.value!! }
        if (closed.value) owner.close()
        return owner
    }

    fun bindRegisteredItems(support: () -> FieldItemSupport?, press: (Int) -> Unit,
                            rejected: () -> Unit): RegisteredItemUse {
        registeredItemOwner.value?.let { return it }
        val owner = RegisteredItemUse(
            session, support, { activeNativeConfig }, { telemetry.snapshot.value.game },
            { telemetry.snapshot.value.inBattle }, { riding }, { fieldMovesReadable },
            press, { if (!closed.value) rejected() },
        )
        if (!registeredItemOwner.compareAndSet(null, owner)) { owner.cancel(); return registeredItemOwner.value!! }
        if (closed.value) owner.cancel()
        return owner
    }

    val autoRefill = GameAutoRefill(prefs, session)
    val shinyAlert = GameShinyAlert(prefs)

    val battleRetry = GameBattleRetry(prefs)
    @Volatile private var nuzlockeGame: Int? = null
    val nuzlockeSupported get() = nuzlockeGame != null && telemetry.snapshot.value.let {
        !it.unsupported && Nuzlocke.supports(it.game)
    }
    val lastBall get() = battle.ball

    fun bindLastBall(removeBagItem: () -> Long, handOver: () -> Unit) {
        if (closed.value || battle.ball != null) return
        battle.ball = GameLastBall(prefs, { telemetry.snapshot.value }, removeBagItem, handOver, battle.controller::throwBall)
    }

    /** Between frames, while the old state still exists: native save/load/rewind adapters share this cleanup. */
    fun cancelFieldActions() {
        autoRefill.reset()
        battle.ball?.resetSample()
        flight?.cancelActions()
        try { registeredItems?.cancel() } finally { gameBoyFields?.cancel() }
    }

    fun bindActions(settings: CompanionSettings, slots: StateSlots, selectSlot: (Int) -> Unit,
                    hold: (Hotkeys.Action, Boolean) -> Unit, companion: () -> Unit,
                    rewindHold: com.pokedaisy.app.game.RewindHold = com.pokedaisy.app.game.RewindHold(),
                    canRewind: () -> Boolean = { true }, rewindDenied: () -> Unit = {}): GameActions =
        actions ?: GameActions(settings, slots, selectSlot, hold, companion,
            rewindHold = rewindHold, canRewind = canRewind, rewindDenied = rewindDenied).also {
            actions = it
            if (closed.value) it.close()
        }

    fun bindCheats(supported: Boolean, openStore: () -> CheatStore?, enabled: () -> Boolean,
                   submit: (String) -> Unit): GameCheats =
        cheats ?: GameCheats(supported, openStore, enabled, submit).also {
            cheats = it
            if (closed.value) it.close() else it.refresh()
        }

    /** Configure once per loaded game. Capture/delivery use [game]; decode remains on the sampler worker. */
    fun startSampling(gameBoy: Boolean, nds: Boolean = false, game: CoroutineDispatcher,
                      current: () -> Boolean, after: (SnapshotView) -> Unit = {}, periodMillis: Long? = null,
                      onShinyFound: (GameShinyAlert.Alert) -> Unit = {},
                      onNuzlockeNotices: (List<String>) -> Unit = {}) {
        if (closed.value) return
        check(sampler == null) { "Sampling is already configured for this game" }
        require(periodMillis == null || periodMillis > 0)
        sampler = GameSampler.forGame(telemetry, gameBoy, nds, game,
            current = { !closed.value && current() }, after = { snapshot ->
                sampled()
                battle.sampled()
                shinyAlert.observe(snapshot, shinyAlertSupported(snapshot.game))?.let(onShinyFound)
                after(snapshot)
                val notices = observeNuzlocke(snapshot)
                if (notices.isNotEmpty()) onNuzlockeNotices(notices)
            }).also { if (periodMillis != null) it.startPeriodic(periodMillis) }
        battle.changed = ::sample
    }

    internal fun observeNuzlocke(snapshot: SnapshotView): List<String> =
        nuzlockeGame?.let { Nuzlocke.onSample(it, snapshot) }.orEmpty()

    fun shinyAlertSupported(kind: GameKind?): Boolean =
        !closed.value && optionsAllowed() && !blocked && telemetry.snapshot.value.blocked == null &&
            (shiny?.supported == true || kind.isDsGame || kind.isGen2 && kind != GameKind.POLISHED_CRYSTAL)

    fun sample() { if (!closed.value) sampler?.request() }

    /** Stop producers before native host teardown; safe before or after sampling was configured. */
    fun quiesce() {
        settledOnField = false
        sampler?.close()
        sampler = null
        options.cancelDiscovery()
    }

    private var gba = false
    private var learnedCallback: ((Long, Long) -> Unit)? = null
    private val menu get() = session.menuOpen
    private val map get() = session.mapOpen
    private var field = 0
    val fieldState get() = field
    val riding get() = field and (GameField.AVATAR or GameField.BIKE) == (GameField.AVATAR or GameField.BIKE)
    var settledOnField by mutableStateOf(false)
        private set
    val onField get() = gba && !blocked && FfMenuWatch.onField
    var readField: () -> Int = { 0 }
    var blocked = false
        private set
    @Volatile var baseSpeed = 1f
    @Volatile var fastForward = false
    @Volatile var slowMotion = false
    @Volatile var ffMaxSpeed = 2f
    @Volatile var ffMode = FfMode.SMART
    @Volatile var battleSpeed = 0f
    @Volatile var textSpeed = 0f
    @Volatile var menuSpeed = 0f
    @Volatile var bikeSpeed = 0f
    @Volatile var surfSpeed = 0f
    var fieldReadable by mutableStateOf(false)
        private set
    var fieldMovesReadable by mutableStateOf(false)
        private set

    fun settingsChanged(s: CompanionSettings, fastForward: Boolean, slowMotion: Boolean) {
        this.slowMotion = slowMotion
        baseSpeed = s.gameSpeed; this.fastForward = fastForward; ffMaxSpeed = s.ffMaxSpeed; ffMode = s.ffMode
        battleSpeed = s.battleSpeed; textSpeed = s.textSpeed; menuSpeed = s.menuSpeed
        bikeSpeed = s.bikeSpeed; surfSpeed = s.surfSpeed
    }

    fun start(romPath: Path, code: String?, crc: Long, gba: Boolean = code != null, areaMapCrc: Long = crc) {
        if (closed.value) return
        this.gba = gba
        shiny?.observe(null)
        resetTint()
        follower?.reset()
        autoRefill.reset()
        battleRetry.reset()
        session.reset()
        flight?.reset()
        settledOnField = false
        val romKey = crc.toString(16).padStart(8, '0')
        shinyAlert.start(romKey)
        blocked = BlockedGames.forCrc(romKey) != null
        nuzlockeGame?.let(Nuzlocke::close)
        nuzlockeGame = if (!blocked) Nuzlocke.start(prefs, romKey) else null
        if (gba && !blocked) battle.ball?.start(romPath, romKey)
        activeAreaMapCrc = if (gba && !blocked) areaMapCrc else 0L
        AreaMaps.romChanged()
        val learned = prefs.ffMenuCallbacks(romKey)
        FfMenuWatch.load(code.orEmpty(), learned.first, learned.second)
        val preferences = prefs
        learnedCallback = { field, battle -> preferences.setFfMenuCallbacks(romKey, field, battle) }
        FfMenuWatch.onLearned = learnedCallback
        ShopWatch.load()
        RegionMapWatch.load(if (gba && !blocked) romPath else null)
        fieldReadable = gba && !blocked && configureField(crc)
        fieldMovesReadable = false
    }

    private fun sampled() {
        val snapshot = telemetry.snapshot.value
        follower?.observe(snapshot)
        if (snapshot.blocked != null) {
            blocked = true
            disableField()
            fieldReadable = false
            fieldMovesReadable = false
            shiny?.observe(null)
            return
        }
        if (snapshot.connected) shiny?.observe(telemetry.knownShinyTarget())
        options.observe(snapshot)
        if (!gba) return
        battle.ball?.observe(snapshot, activeNativeConfig)
        desiredTint = DayNight.desired(prefs.dayNight, dayNightSupported, snapshot.mapType)
        telemetry.knownGMain()?.let { (addr, offset) -> FfMenuWatch.useKnownGMain(addr, offset) }
        if (snapshot.connected) FfMenuWatch.notePosition(snapshot.x, snapshot.y, snapshot.mapGroup, snapshot.mapNum, snapshot.inBattle)
    }

    /** Option and appearance patches can both remove native co-op hooks. Restore the story lock
     * after each operation and defer both while a joined battle owns the hooks. */
    inline fun serviceFramePatches(
        coopBattleActive: Boolean,
        serviceAppearance: () -> Boolean,
        reinstallStoryLock: () -> Unit,
    ) {
        if (coopBattleActive) return
        if (options.dirty) {
            options.tick()
            reinstallStoryLock()
        }
        if (serviceAppearance()) reinstallStoryLock()
    }

    fun beforeFrame(work: GameSession.FrameWork = GameSession.FrameWork.ADVANCING, applyPatches: () -> Unit = {}) {
        // As Android with an unknown GB scene: each GB helper validates its own field RAM.
        session.servicePending(work, gba, { true }, applyPatches)
    }

    fun tick(reader: MemoryReader = InProcessReader, wideScene: Int = -1) {
        val readable = gba && !blocked && telemetry.snapshot.value.blocked == null
        session.observeScreens(readable, reader = reader)
        session.observeField(readable && FfMenuWatch.onField && !map, wideScene, battle.gameState, battle.holdPicture)
        fieldChanged(if (readable && fieldReadable) readField() else 0)
        shiny?.tick()
        stepFollower()
        stepTint()
    }

    fun fieldChanged(bits: Int) {
        if (closed.value) return
        field = bits
        // START, dialogue and cutscenes retain the overworld callback. Native field reads know when the game
        // owns its controls or text layer; presentation can step aside without changing queued field-job ownership.
        val fieldWindow = gba && bits and (GameField.LOCKED or GameField.BATTLE or GameField.WINDOW) != 0
        settledOnField = !blocked && telemetry.snapshot.value.blocked == null && session.onField && !fieldWindow
        if (!fieldMovesReadable && bits and GameField.AVATAR != 0) fieldMovesReadable = true
    }
    fun resetFieldMoves() { fieldMovesReadable = false }

    fun requestedSpeed(scene: PlaybackPolicy.Scene): Float = PlaybackPolicy.requestedSpeed(fastForward, ffMaxSpeed, slowMotion,
        PlaybackPolicy.sceneSpeed(scene, baseSpeed, battleSpeed, textSpeed, menuSpeed, bikeSpeed, surfSpeed))

    fun smartSpeed(requested: Float, battleState: Int, menu: Boolean, map: Boolean, waiting: Boolean = false): Float =
        PlaybackPolicy.smartSpeed(requested, ffMode == FfMode.SMART, battleState, menu, map, waiting)

    fun requestedSpeedNow(): Float =
        requestedSpeed(PlaybackPolicy.scene(battle.gameState, menu, map, gba && FfMenuWatch.battling, field))

    /** Common uncapped value is infinity; each renderer converts its own pacing sentinel. */
    fun speed(): Float = if (battle.holdPicture) Float.POSITIVE_INFINITY else
        smartSpeed(requestedSpeedNow(), battle.gameState, menu, map)

    /** Native hosts call after stopping the core or between frames; patch cleanup may touch the core. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        release(::quiesce)
        release { registeredItems?.cancel() }
        release { gameBoyFields?.close() }
        battle.changed = {}
        release { actions?.close() }
        release { cheats?.close() }
        release { shiny?.close() }
        release { flight?.close() }
        release { follower?.close() }
        release { battle.ball?.close() }
        release { stateSlots.value?.close() }
        release { options.close(restoreOptionPatchesOnClose) }
        release { battle.cancel() }
        battleRetry.reset()
        nuzlockeGame?.let(Nuzlocke::close)
        release { autoRefill.reset(); session.close() }
        release {
            if (learnedCallback != null && FfMenuWatch.onLearned === learnedCallback) {
                FfMenuWatch.onLearned = null
                RegionMapWatch.load(null)
            }
        }
        failure?.let { throw it }
    }
}
