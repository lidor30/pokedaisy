package com.pokedaisy.app.companion

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.FlightCore
import com.pokedaisy.app.game.GameSession
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import kotlin.test.*

class GameFlightTest {
    @Test fun mapAndQuickMenuObserveLandingEvenWhenTheTelemetrySnapshotDoesNotChange() {
        val h = Harness(); var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { _: Any -> invalidations++ }) { assertEquals(0, h.owner.state) }
            h.nativeState = 2; h.owner.afterFrame(); Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
            observer.observeReads(Any(), { _: Any -> invalidations++ }) { assertEquals(2, h.owner.state) }
            h.nativeState = 0; h.owner.afterFrame(); Snapshot.sendApplyNotifications()
            assertTrue(invalidations >= 2)
        } finally { observer.stop(); h.owner.close() }
    }

    private class Harness {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store()).also { it.flightAreaId = FlightAreaMode.ANYWHERE.id }
        val fns = FIELD_ITEMS_EMERALD.copy(codeCrcs = emptyList(),
            party = FIELD_ITEMS_EMERALD.party!!.copy(codeCrcs = emptyList()),
            fly = FIELD_ITEMS_EMERALD.fly!!.copy(codeCrcs = emptyList()))
        var fields: FieldItemSupport? = FieldItemSupport(fns, emptySet(), canFly = true, canFlight = true)
        var kind: GameKind? = GameKind.EMERALD
        var retail = true
        var battle = false
        var locked = false
        var badge = true
        var outside = true
        var flyer = true
        var takeOffCode = 0
        var takesOff = 0
        var lands = 0
        var configured = 0
        var configuration: IntArray? = null
        var areaWrites = 0
        var nativeState = 0
        var turned = false
        var turnPolls = 0
        var notices = 0
        var handoffs = 0
        val deliveries = mutableListOf<() -> Unit>()
        val calls = object : GameCalls {
            override fun read(addr: Long, len: Int) = ByteArray(len) { i ->
                if (addr == fns.party!!.partyCount && i == 0) 1 else 0
            }
            override fun write(addr: Long, bytes: ByteArray) = error("Flight policy must not write memory itself")
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = when (fn) {
                fns.fieldControlsLocked -> if (locked) 1 else 0
                fns.fly!!.flagGet -> if (badge) 1 else 0
                fns.fly!!.setUpFieldMoveFly -> if (outside) 1 else 0
                fns.party!!.getMonData -> if (a1 == fns.fly!!.monMoveField && flyer) fns.fly!!.flyMove.toLong() else 0
                else -> 0
            }
        }
        val core = object : FlightCore {
            override fun configureFlight(words: IntArray?) { configured++; configuration = words }
            override fun flightTakeOff(): Int { takesOff++; if (takeOffCode == 0) nativeState = 1; return takeOffCode }
            override fun flightLand() { lands++ }
            override fun setFlightRoute(directions: ByteArray) = 0
            override fun setFlightArea(bits: ByteArray?) { areaWrites++ }
            override val flightAreaTurned get() = turned.also { turned = false; turnPolls++ }
            override val flightState get() = nativeState
        }
        val session = GameSession(calls, { true }) { _, e -> throw e }
        val owner = GameFlight(session, core, prefs, { fields }, { kind }, { retail }, { battle },
            { true }, { false }, { null }, {}, FlightArea.Keeper("unused-flight-test".toPath(), "test"),
            { deliveries += it }, { handoffs++ }, { notices++ }, {}, Dispatchers.Unconfined)
        fun frame() { session.servicePosted(); session.serviceField(true, true) }
        fun deliver() { deliveries.toList().also { deliveries.clear() }.forEach { it() } }
    }

    @Test fun freeFlightUsesTheGameQueueAndLandingUsesTheObservedNativeState() {
        val h = Harness(); var result: FlyResult? = null
        h.owner.toggle { r, _ -> result = r }
        assertEquals(0, h.takesOff); assertNull(result)
        h.frame(); assertEquals(1, h.takesOff); assertEquals(0, h.handoffs)
        h.deliver(); assertEquals(FlyResult.OK, result); assertEquals(1, h.handoffs)
        h.owner.afterFrame(); assertEquals(1, h.owner.state); assertFalse(h.owner.scenicAirborne)
        h.owner.toggle { r, _ -> result = r }; assertNull(result); assertEquals(0, h.lands)
        h.frame(); assertEquals(1, h.lands)
        h.owner.close()
    }

    @Test fun gatesPreserveBattleBadgeMapAndPartyFailuresWithoutTakingOff() {
        val cases = listOf<Pair<(Harness) -> Unit, FlyResult>>(
            { h: Harness -> h.battle = true } to FlyResult.IN_BATTLE,
            { h: Harness -> h.locked = true } to FlyResult.NOT_NOW,
            { h: Harness -> h.badge = false } to FlyResult.NO_BADGE,
            { h: Harness -> h.outside = false } to FlyResult.CANT_FLY_HERE,
            { h: Harness -> h.flyer = false } to FlyResult.NO_FLYER,
        )
        for ((change, expected) in cases) {
            val h = Harness(); change(h); var result: FlyResult? = null
            h.owner.toggle { r, _ -> result = r }; h.frame(); h.deliver()
            assertEquals(expected, result); assertEquals(0, h.takesOff); assertEquals(0, h.configured)
            h.owner.close()
        }
    }

    @Test fun unsupportedAndRetiredRunsCannotConfigureOrDeliverToAnotherGame() {
        val h = Harness(); h.retail = false; assertNull(h.owner.support())
        h.retail = true; h.fields = null; assertNull(h.owner.support())
        h.fields = FieldItemSupport(h.fns, emptySet(), canFlight = true)
        var delivered = 0
        h.owner.toggle { _, _ -> delivered++ }; h.owner.close(); h.frame(); h.deliver()
        assertEquals(0, h.takesOff); assertEquals(0, delivered)
        val pending = Harness()
        pending.owner.toggle { _, _ -> delivered++ }; pending.frame(); pending.owner.close(); pending.deliver()
        assertEquals(0, delivered); assertEquals(0, pending.handoffs)
    }

    @Test fun configurationCoalescesDuringAHoldAndReadsTheLatestVehicle() {
        val h = Harness()
        repeat(20) {
            h.owner.sync()
            h.session.servicePending(GameSession.FrameWork.HELD, true, { true })
        }
        h.owner.setVehicle(FlightVehicle.BALLOON)
        h.owner.cancelActions()
        h.session.servicePending(GameSession.FrameWork.REWINDING, true, { true })
        assertEquals(0, h.configured)
        h.frame()
        assertEquals(1, h.configured)
        assertContentEquals(h.fns.flight!!.words(FlightVehicle.BALLOON), h.configuration)

        h.owner.sync()
        h.owner.reset()
        h.owner.setVehicle(FlightVehicle.BIRD)
        h.frame()
        assertEquals(2, h.configured)
        assertContentEquals(h.fns.flight!!.words(FlightVehicle.BIRD), h.configuration)
        h.owner.close()
    }

    @Test fun transportRejectsWaterAndAreaSamplingIsCoalescedAndConsumesTheTurnNotice() {
        val h = Harness(); h.takeOffCode = FlightCore.NOT_ON_FOOT
        var notOnFoot = false
        h.owner.toggle { result, water -> assertEquals(FlyResult.NOT_NOW, result); notOnFoot = water }
        h.frame(); h.deliver(); assertTrue(notOnFoot); assertEquals(0, h.handoffs)
        h.turned = true
        repeat(8) { h.owner.sync() }; h.frame(); h.deliver()
        assertEquals(1, h.areaWrites); assertEquals(1, h.notices); assertFalse(h.turned)
        h.owner.sync(); h.frame(); h.deliver(); assertEquals(1, h.areaWrites)
        h.owner.reset(); h.owner.sync(); h.frame(); h.deliver(); assertEquals(2, h.areaWrites)
        h.owner.close()
    }

    @Test fun restartDiscardsPendingAreaWorkAndResendsToTheNewCore() {
        val h = Harness()
        try {
            h.owner.sync()
            h.owner.reset()
            h.session.reset()
            h.owner.sync()
            h.frame()

            assertEquals(1, h.configured)
            assertEquals(1, h.areaWrites)
        } finally {
            h.owner.close()
        }
    }

    @Test fun staleAreaCallbackCannotClearTheNewCoreRequest() {
        val h = Harness()
        try {
            h.owner.sync()
            h.session.requestField(false) { _, _ -> h.owner.sync() }
            h.owner.reset()
            h.owner.sync()
            h.frame()
            h.frame()

            assertEquals(1, h.areaWrites)
            assertEquals(1, h.turnPolls)
        } finally {
            h.owner.close()
        }
    }

    @Test fun stateChangesInvalidateQueuedStartsAndPendingUiHandoffs() {
        val h = Harness(); var delivered = 0
        h.owner.toggle { _, _ -> delivered++ }; h.owner.cancelActions(); h.frame(); h.deliver()
        assertEquals(0, h.takesOff); assertEquals(0, delivered)
        h.owner.toggle { _, _ -> delivered++ }; h.frame(); h.owner.cancelActions(); h.deliver()
        assertEquals(1, h.takesOff); assertEquals(0, delivered); assertEquals(0, h.handoffs)
        h.owner.close()
    }

    @Test fun classicPreferenceAndUnjoinedWorldUseTheOriginalFlyButNoClassicGameReportsNoRoute() {
        val h = Harness(); var classicCalls = 0; var result: FlyResult? = null
        val classic = object : FlyAction {
            override fun canFly() = true
            override fun targets(done: (FlyTargets) -> Unit) = done(FlyTargets(FlyResult.OK))
            override fun fly(mapsec: Int, targets: FlyTargets, done: (FlyResult) -> Unit) {
                classicCalls++; done(FlyResult.OK)
            }
        }
        h.owner.setStyle(FlyStyle.CLASSIC)
        h.owner.flyTo(0, FlyTargets(FlyResult.OK), classic) { result = it }
        assertEquals(1, classicCalls); assertEquals(FlyResult.OK, result)
        h.owner.setStyle(FlyStyle.SCENIC); result = null
        h.owner.flyTo(0, FlyTargets(FlyResult.OK), classic) { result = it }
        h.frame(); assertNull(result); h.deliver()
        assertEquals(2, classicCalls); assertEquals(FlyResult.OK, result)
        h.fields = FieldItemSupport(h.fns.copy(classicFly = false), emptySet(), canFlight = true)
        result = null
        h.owner.flyTo(0, FlyTargets(FlyResult.OK), classic) { result = it }
        h.frame(); h.deliver()
        assertEquals(FlyResult.NO_ROUTE, result); assertEquals(2, classicCalls); assertEquals(0, h.takesOff)
        h.owner.close()
    }
}
