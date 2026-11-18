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
    optionsAllowed: () -> Boolean,
    private val configureField: (Long) -> Boolean = { false },
    private val disableField: () -> Unit = {},
    private val restoreOptionPatchesOnClose: Boolean = true,
) {
    private val closed = atomic(false)
    private var sampler: GameSampler? = null
    val options = GameOptions(optionPatcher, { !closed.value && !blocked &&
        telemetry.snapshot.value.blocked == null && optionsAllowed() })

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

    var actions: GameActions? = null
        private set
    var cheats: GameCheats? = null
        private set

    fun bindActions(settings: CompanionSettings, slots: StateSlots, selectSlot: (Int) -> Unit,
                    hold: (Hotkeys.Action, Boolean) -> Unit, companion: () -> Unit): GameActions =
        actions ?: GameActions(settings, slots, selectSlot, hold, companion).also {
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
                      current: () -> Boolean, after: (SnapshotView) -> Unit = {}, periodMillis: Long? = null) {
        if (closed.value) return
        check(sampler == null) { "Sampling is already configured for this game" }
        require(periodMillis == null || periodMillis > 0)
        sampler = GameSampler.forGame(telemetry, gameBoy, nds, game,
            current = { !closed.value && current() }, after = { snapshot ->
                sampled()
                battle.sampled()
                after(snapshot)
            }).also { if (periodMillis != null) it.startPeriodic(periodMillis) }
        battle.changed = ::sample
    }

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
        session.reset()
        settledOnField = false
        val romKey = crc.toString(16).padStart(8, '0')
        blocked = BlockedGames.forCrc(romKey) != null
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
        if (snapshot.blocked != null) {
            blocked = true
            disableField()
            fieldReadable = false
            fieldMovesReadable = false
            return
        }
        // EXP SHARE's party in every game (a DS game's native side marks it too); the rest is the GBA's.
        options.observe(snapshot)
        if (!gba) return
        telemetry.knownGMain()?.let { (addr, offset) -> FfMenuWatch.useKnownGMain(addr, offset) }
        if (snapshot.connected) FfMenuWatch.notePosition(snapshot.x, snapshot.y, snapshot.mapGroup, snapshot.mapNum, snapshot.inBattle)
    }

    fun beforeFrame() {
        session.servicePosted()
        // As Android with an unknown GB scene: each GB helper validates its own field RAM.
        session.serviceField(gba, gbOnField = true)
    }

    fun tick(reader: MemoryReader = InProcessReader) {
        val readable = gba && !blocked && telemetry.snapshot.value.blocked == null
        session.observeScreens(readable, reader = reader)
        session.observeField(readable && FfMenuWatch.onField && !map, -1, battle.gameState, battle.holdPicture)
        val bits = if (readable && fieldReadable) readField() else 0
        // Native windows can own the controls while the core still reports the overworld callback.
        val fieldWindow = bits and (GameField.LOCKED or GameField.BATTLE or GameField.WINDOW) != 0
        settledOnField = readable && session.onField && !fieldWindow
        fieldChanged(bits)
    }

    fun fieldChanged(bits: Int) {
        field = bits
        if (!fieldMovesReadable && bits and GameField.AVATAR != 0) fieldMovesReadable = true
    }
    fun resetFieldMoves() { fieldMovesReadable = false }

    fun requestedSpeed(scene: PlaybackPolicy.Scene): Float = PlaybackPolicy.requestedSpeed(fastForward, ffMaxSpeed, slowMotion,
        PlaybackPolicy.sceneSpeed(scene, baseSpeed, battleSpeed, textSpeed, menuSpeed, bikeSpeed, surfSpeed))

    fun smartSpeed(requested: Float, battleState: Int, menu: Boolean, map: Boolean, waiting: Boolean = false): Float =
        PlaybackPolicy.smartSpeed(requested, ffMode == FfMode.SMART, battleState, menu, map, waiting)

    /** Common uncapped value is infinity; each renderer converts its own pacing sentinel. */
    fun speed(): Float {
        if (battle.holdPicture) return Float.POSITIVE_INFINITY
        val scene = PlaybackPolicy.scene(battle.gameState, menu, map, gba && FfMenuWatch.battling, field)
        return smartSpeed(requestedSpeed(scene), battle.gameState, menu, map)
    }

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
        battle.changed = {}
        release { actions?.close() }
        release { cheats?.close() }
        release { stateSlots.value?.close() }
        release { options.close(restoreOptionPatchesOnClose) }
        release { battle.cancel() }
        release { session.close() }
        release {
            if (learnedCallback != null && FfMenuWatch.onLearned === learnedCallback) {
                FfMenuWatch.onLearned = null
                RegionMapWatch.load(null)
            }
        }
        failure?.let { throw it }
    }
}
