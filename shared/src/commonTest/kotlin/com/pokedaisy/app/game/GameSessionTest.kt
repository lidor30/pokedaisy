package com.pokedaisy.app.game

import com.pokedaisy.app.companion.data.*
import kotlin.test.*

class GameSessionTest {
    private val calls = object : GameCalls {
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
        override fun read(addr: Long, len: Int) = ByteArray(len)
        override fun write(addr: Long, bytes: ByteArray) = true
    }
    private val events = mutableListOf<String>()
    private var safe = true
    private fun session() = GameSession(calls, { safe }) { where, _ -> events += where }

    @Test fun postedThenPatchesThenFieldsAndReentrantWorkWaits() {
        val s = session()
        s.post { events += "sample"; s.post { events += "next sample" } }
        s.requestField(false) { g, busy ->
            assertSame(calls, g); assertFalse(busy)
            events += "field"
            s.requestField(false) { _, _ -> events += "next field" }
        }
        s.servicePosted(); events += "patches"; s.serviceField(true, true)
        assertEquals(listOf("sample", "patches", "field"), events)
        s.servicePosted(); s.serviceField(true, true)
        assertEquals(listOf("sample", "patches", "field", "next sample", "next field"), events)
    }

    @Test fun delaysDoNotAdvanceDuringAHoldAndUnsafeCallsWaitAtMost120Frames() {
        val s = session()
        s.requestField(false, 2) { _, _ -> events += "delayed" }
        repeat(20) { s.servicePosted() } // held core: host skips field service
        assertTrue(events.isEmpty())
        repeat(2) { s.serviceField(true, true) }
        assertTrue(events.isEmpty())
        safe = false
        repeat(120) { s.serviceField(true, true) }
        assertTrue(events.isEmpty())
        s.serviceField(true, true)
        assertEquals(listOf("delayed"), events)
    }

    @Test fun becomingSafeRunsOnTheNextFrameAndFailuresDoNotDropOtherJobs() {
        val s = session(); safe = false
        s.post { error("broken sample") }; s.post { events += "sample survived" }
        s.requestField(false) { _, _ -> error("broken field action") }
        s.requestField(false) { _, _ -> events += "field survived" }
        s.servicePosted(); s.serviceField(true, true)
        assertEquals(listOf("emu job failed", "sample survived"), events)
        safe = true; s.serviceField(true, true)
        assertEquals(listOf("emu job failed", "sample survived", "field item job failed", "field survived"), events)
    }

    @Test fun battleRequestsAndGbScenesAreBusyWithoutCallingTheGbaSafetyProbe() {
        val s = GameSession(calls, { error("GB cannot query GBA safety") }) { _, e -> throw e }
        val busy = mutableListOf<Boolean>()
        s.requestField(true) { _, b -> busy += b }
        s.requestField(false) { _, b -> busy += b }
        s.serviceField(false, true)
        s.requestField(false) { _, b -> busy += b }
        s.serviceField(false, false)
        assertEquals(listOf(true, false, true), busy)
    }

    @Test fun dsMenusBlockGbaActionsAndPlainFieldSettlesWithoutFlickeringThroughFades() {
        val s = session()
        s.observeScreens(false, dsMenu = true)
        var wasBusy: Boolean? = null
        s.requestField(false) { _, busy -> wasBusy = busy }
        s.serviceField(true, true)
        assertEquals(true, wasBusy)
        s.observeScreens(false)
        repeat(19) { s.observeField(true, 0, BATTLE_INPUT_NONE, false) }
        assertFalse(s.onField)
        s.observeField(true, 0, BATTLE_INPUT_NONE, false); assertTrue(s.onField)
        repeat(50) { s.observeField(false, 5, BATTLE_INPUT_NONE, false) }
        assertTrue(s.onField)
        s.observeField(false, -1, BATTLE_INPUT_NONE, false); assertFalse(s.onField)
        repeat(20) { s.observeField(true, -1, BATTLE_INPUT_NONE, false) }; assertTrue(s.onField)
        s.observeField(true, -1, BATTLE_INPUT_NONE, true); assertFalse(s.onField)
        repeat(20) { s.observeField(true, 1, BATTLE_INPUT_NONE, false) }; assertFalse(s.onField)
        repeat(20) { s.observeField(true, -1, BATTLE_INPUT_PARTY_OPEN, false) }; assertFalse(s.onField)
    }

    @Test fun closingInsideACallbackDropsTheRestAndResetCannotLeakOldWork() {
        val s = session()
        repeat(20) { s.observeField(true, 0, BATTLE_INPUT_NONE, false) }
        s.post { events += "closing"; s.close() }
        s.post { error("must not run") }
        s.requestField(false, 1) { _, _ -> error("must not run") }
        s.servicePosted(); s.serviceField(true, true)
        assertFalse(s.onField)
        s.post { error("closed") }
        s.requestField(false) { _, _ -> error("closed") }
        s.reset(); s.servicePosted(); repeat(5) { s.serviceField(true, true) }
        s.post { events += "new game" }; s.servicePosted()
        assertEquals(listOf("closing", "new game"), events)
    }
}
