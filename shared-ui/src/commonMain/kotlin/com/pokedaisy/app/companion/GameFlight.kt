package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.FlightCore
import com.pokedaisy.app.game.GameSession
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlin.time.TimeSource

/** One loaded game's free/scenic flight: verified gates, seen maps, route work and landing question.
 * Native hosts supply transport and UI effects; the original flight engine advances inside their frame loop. */
class GameFlight(
    private val session: GameSession,
    private val core: FlightCore,
    private val prefs: Prefs,
    private val readSupport: () -> FieldItemSupport?,
    private val kind: () -> GameKind?,
    private val retail: () -> Boolean,
    private val inBattle: () -> Boolean,
    private val standing: () -> Boolean,
    private val riding: () -> Boolean,
    private val bike: () -> Int?,
    private val useItem: (Int) -> Unit,
    private val keeper: FlightArea.Keeper,
    private val deliver: (() -> Unit) -> Unit,
    private val tookOff: (Boolean) -> Unit,
    private val turned: () -> Unit,
    private val askStyle: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val closed = atomic(false)
    private val generation = atomic(0)
    private val areaQueued = atomic<Any?>(null)
    private val configurationQueued = atomic<Any?>(null)
    private val scenicRun = atomic(false)
    private val observedState = atomic(FlightCore.IDLE)
    private var stateRevision by mutableIntStateOf(0)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val clock = TimeSource.Monotonic.markNow()
    private fun now() = clock.elapsedNow().inWholeMilliseconds
    private var anywhereSent = false // game thread
    private var toldAt = -4000L
    private var scenicFlown = false // UI thread
    private var askAt: Long? = null
    private val dismounting = atomic(false)
    val state: Int get() { stateRevision; return observedState.value }
    val scenic get() = scenicRun.value
    val scenicAirborne get() = scenic && state != FlightCore.IDLE && state != FlightCore.DISMOUNT
    val vehicle get() = FlightVehicle.of(prefs.flightVehicleId)
    val style get() = prefs.flyStyle
    val areaMode get() = prefs.flightArea
    fun setStyle(style: FlyStyle) { if (!closed.value) prefs.flyStyleId = style.id }
    fun setArea(mode: FlightAreaMode) { if (!closed.value) { prefs.flightAreaId = mode.id; sync() } }
    fun setVehicle(vehicle: FlightVehicle) { if (!closed.value) { prefs.flightVehicleId = vehicle.id; sync() } }
    private val seen get() = prefs.flightArea == FlightAreaMode.SEEN

    fun support(): FieldItemSupport? = if (closed.value) null else readSupport()?.takeIf {
        it.canFlight && it.fns.flight != null && flightAllowed(kind(), retail(), it.fns.flight)
    }

    private fun live(version: Int) = !closed.value && generation.value == version
    private fun ui(action: () -> Unit) = deliver { if (!closed.value) action() }
    private fun ui(version: Int, action: () -> Unit) = deliver { if (live(version)) action() }

    /** A load/rewind invalidates pending dismounts, queued starts and off-core route answers. */
    fun cancelActions() {
        generation.incrementAndGet()
        scope.coroutineContext.cancelChildren()
        dismounting.value = false
    }

    /** UI sampler; configuration and seen-map work are serialized with the core. */
    fun sync() {
        if (closed.value) return
        val time = now()
        if (scenicFlown && !scenic && state == FlightCore.IDLE) {
            scenicFlown = false
            if (!prefs.flyStyleAsked) askAt = time + 1500
        }
        if (askAt?.let { time >= it } == true && standing()) { askAt = null; askStyle() }
        val fields = support() ?: return
        if (configurationQueued.value == null) {
            val request = Any()
            if (configurationQueued.compareAndSet(null, request)) {
                session.postForward {
                    if (configurationQueued.compareAndSet(request, null)) {
                        support()?.fns?.flight?.let { core.configureFlight(it.words(vehicle)) }
                    }
                }
            }
        }
        val request = Any()
        if (!areaQueued.compareAndSet(null, request)) return
        val onGround = standing() && !inBattle()
        session.requestField(false) { calls, busy ->
            try {
                if (areaQueued.value !== request || closed.value || support() == null) return@requestField
                area(fields, calls, onGround && !busy)
                if (core.flightAreaTurned) ui {
                    val timeNow = now()
                    if (timeNow - toldAt >= 4000) { toldAt = timeNow; turned() }
                }
            } finally { areaQueued.compareAndSet(request, null) }
        }
    }

    private fun area(fields: FieldItemSupport, calls: GameCalls, onGround: Boolean) {
        if (!seen) {
            keeper.resend()
            if (!anywhereSent) { core.setFlightArea(null); anywhereSent = true }
            return
        }
        anywhereSent = false
        runCatching { keeper.tick(fields.fns, calls, kind() != GameKind.QUETZAL,
            onGround && core.flightState == FlightCore.IDLE) }
            .onSuccess { bits -> if (bits != null) core.setFlightArea(bits) }
            .onFailure { Logs.i("flight area: $it") }
    }

    /** A new native core for the same loaded ROM needs its seen-area bits again. */
    fun reset() {
        cancelActions(); keeper.resend(); anywhereSent = false
        configurationQueued.value = null
        areaQueued.value = null
        observedState.value = FlightCore.IDLE; stateRevision++; scenicRun.value = false
    }

    fun afterFrame() {
        if (closed.value) return
        val next = core.flightState
        if (observedState.getAndSet(next) != next) stateRevision++
        if (state == FlightCore.IDLE) scenicRun.value = false
    }

    private fun offBike(then: () -> Unit, refused: () -> Unit) {
        if (closed.value || dismounting.value) return
        if (!riding()) return then()
        val version = generation.value
        val item = bike() ?: return refused()
        useItem(item)
        dismounting.value = true
        scope.launch {
            try {
                val started = now()
                var offAt: Long? = null
                while (now() - started < 2500) {
                    delay(50)
                    if (!riding() && offAt == null) offAt = now()
                    if (offAt?.let { now() - it >= 250 } == true) break
                }
                if (live(version)) { if (riding()) refused() else then() }
            } finally { if (live(version)) dismounting.value = false }
        }
    }

    /** A null result means LAND was requested. [notOnFoot] retains the specific free-flight message. */
    fun toggle(done: (FlyResult?, Boolean) -> Unit) {
        if (closed.value) return
        val version = generation.value
        if (state != FlightCore.IDLE) {
            session.postForward { if (live(version)) core.flightLand() }
            done(null, false)
            return
        }
        offBike({ takeOff(done) }, { done(FlyResult.NOT_NOW, true) })
    }

    private fun takeOff(done: (FlyResult?, Boolean) -> Unit) {
        val version = generation.value
        val fields = support() ?: return done(FlyResult.FAILED, false)
        val battle = inBattle()
        val words = fields.fns.flight!!.words(vehicle)
        session.requestField(battle) { calls, busy ->
            if (!live(version) || support() == null) return@requestField
            val gate = flightCheck(fields.fns, busy, calls)
            var code = FlightCore.NOT_NOW
            if (gate == FlyResult.OK) {
                core.configureFlight(words); area(fields, calls, true); code = core.flightTakeOff()
            }
            ui(version) {
                if (gate == FlyResult.OK && code == FlightCore.OK) tookOff(false)
                done(if (gate != FlyResult.OK) shown(gate, battle) else flightResult(code), code == FlightCore.NOT_ON_FOOT)
            }
        }
    }

    /** Off-core route arithmetic; a delayed answer cannot enter another session or start at a stale position. */
    fun flyTo(mapsec: Int, targets: FlyTargets, classic: FlyAction, done: (FlyResult) -> Unit) {
        if (closed.value) return
        val fields = readSupport() ?: return done(FlyResult.FAILED)
        if (!fields.fns.classicFly) {
            if (support() == null) return done(FlyResult.FAILED)
            fly(mapsec, done) { done(FlyResult.NO_ROUTE) }
        } else if (style == FlyStyle.SCENIC && support() != null) {
            fly(mapsec, done) { classic.fly(mapsec, targets, done) }
        } else classic.fly(mapsec, targets, done)
    }

    fun fly(mapsec: Int, done: (FlyResult) -> Unit, classic: () -> Unit) {
        if (closed.value) return
        offBike({ scenicFly(mapsec, done, classic) }, classic)
    }

    private fun scenicFly(mapsec: Int, done: (FlyResult) -> Unit, classic: () -> Unit) {
        val version = generation.value
        val fields = support() ?: return classic()
        val f = fields.fns.flight!!
        val fly = fields.fns.fly ?: return classic()
        val words = f.words(vehicle)
        val battle = inBattle()
        session.requestField(battle) { calls, busy ->
            if (!live(version) || support() == null) return@requestField
            val gate = flightCheck(fields.fns, busy, calls)
            if (gate != FlyResult.OK) { ui(version) { done(shown(gate, battle)) }; return@requestField }
            area(fields, calls, true)
            val allowed = if (seen) ({ map: ScenicFly.MapId -> keeper.allows(map) }) else null
            val world = runCatching {
                val here = ScenicFly.here(f, calls::read) ?: return@runCatching null
                val dest = ScenicFly.destination(fly, f, mapsec, calls::read) ?: return@runCatching null
                val map = ScenicFly.read(f, here.first, calls::read, allowed) ?: return@runCatching null
                Triple(map, here, dest).takeIf { map.placed(dest.first) != null }
            }.getOrNull()
            if (world == null) { ui(version, classic); return@requestField }
            scope.launch {
                val (map, here, dest) = world
                val route = withContext(Dispatchers.Default) {
                    runCatching { map.route(here.first, here.second, here.third, dest.first, dest.second, dest.third) }
                        .getOrNull()?.takeIf { it.size < ScenicFly.MAX_STEPS }
                }
                if (!live(version)) return@launch
                if (route == null) { ui(version, classic); return@launch }
                session.requestField(false) again@{ next, busyAgain ->
                    if (!live(version) || support() == null) return@again
                    if (ScenicFly.here(f, next::read) != here) { ui(version, classic); return@again }
                    val gateAgain = flightCheck(fields.fns, busyAgain, next)
                    var code = FlightCore.NOT_NOW
                    if (gateAgain == FlyResult.OK) {
                        core.configureFlight(words); code = core.flightTakeOff()
                        if (code == FlightCore.OK) {
                            val routed = core.setFlightRoute(route)
                            if (routed == FlightCore.OK) scenicRun.value = true
                            else { core.flightLand(); code = routed }
                        }
                    }
                    ui(version) {
                        when {
                            code == FlightCore.OK -> { scenicFlown = true; tookOff(true); done(FlyResult.OK) }
                            code == FlightCore.NOT_ON_FOOT -> classic()
                            gateAgain != FlyResult.OK -> done(gateAgain)
                            else -> done(flightResult(code))
                        }
                    }
                }
            }
        }
    }

    fun answerStyle(style: FlyStyle?) {
        if (closed.value) return
        if (style == FlyStyle.CLASSIC) prefs.flyStyleId = style.id else prefs.flyStyleAsked = true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel(); scenicRun.value = false; observedState.value = FlightCore.IDLE; stateRevision++
    }

    private fun shown(result: FlyResult, battle: Boolean) =
        if (battle && result == FlyResult.NOT_NOW) FlyResult.IN_BATTLE else result
}
