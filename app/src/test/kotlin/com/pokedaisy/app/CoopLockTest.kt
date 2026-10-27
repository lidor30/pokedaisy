package com.pokedaisy.app

import com.pokedaisy.app.companion.coop.CoopLink
import com.pokedaisy.app.companion.coop.CoopMessage
import com.pokedaisy.app.companion.coop.CoopPartner
import com.pokedaisy.app.companion.coop.CoopPeerId
import com.pokedaisy.app.companion.coop.CoopSaveId
import com.pokedaisy.app.companion.coop.CoopState
import com.pokedaisy.app.companion.coop.lock.CoopLockStore
import com.pokedaisy.app.companion.coop.lock.CoopLockWire
import com.pokedaisy.app.companion.coop.lock.LockMsg
import com.pokedaisy.app.companion.coop.lock.LockRefusal
import com.pokedaisy.app.companion.coop.lock.LockWait
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoopLockTest {
    private val partner = CoopPartner(CoopPeerId("0".repeat(64)), "P", 1, false, CoopSaveId(1, 2, ByteArray(8) { 1 }))

    private class FakeLink : CoopLink {
        val st = MutableStateFlow<CoopState>(CoopState.Off())
        val sent = mutableListOf<LockMsg>()
        override val state: StateFlow<CoopState> = st
        override val messages: SharedFlow<CoopMessage> = MutableSharedFlow()
        override fun send(type: Int, payload: ByteArray): Boolean {
            if (st.value !is CoopState.Connected) return false
            CoopLockWire.decode(type, payload) { true }?.let { sent += it }
            return true
        }
    }

    private class FakeNative : LockNative {
        var installOk = true
        var on = false
        var held = 0
        val goes = mutableListOf<Int>()
        var goResult = 1
        var hookLost = false
        override fun install(): Boolean {
            if (!installOk) return false
            if (hookLost) { hookLost = false; on = false }   // a fresh install starts inactive
            return true
        }
        override fun uninstall() { on = false }
        override fun setActive(on: Boolean) { this.on = on }
        override fun held() = intArrayOf(if (on) held else 0, 0)
        override fun go(id: Int): Int { goes += id; return goResult }
        override fun cancel() { held = 0 }
    }

    private lateinit var dir: File
    private var now = 0L
    private var party = 0
    private val link = FakeLink()
    private val native = FakeNative()

    @Before fun setUp() {
        CoopGuards.reset(); CoopGuards.battleActive = { false }
        dir = File(System.getProperty("java.io.tmpdir"), "coop-lock-test-" + System.nanoTime()).also { it.mkdirs() }
    }

    @After fun tearDown() { CoopGuards.reset(); CoopGuards.battleActive = { false }; dir.deleteRecursively() }

    private fun lock(store: CoopLockStore = CoopLockStore(File(dir, "locks.json"))) =
        CoopLock(link, store, "own", "partner", native, { party }, { now })

    private fun connect() { link.st.value = CoopState.Connected(partner, true) }
    private fun CoopLock.msg(m: LockMsg) { val (t, p) = CoopLockWire.encode(m); onMessage(CoopMessage(t, p)) }

    @Test fun proposeAndAcceptLocksBoth() {
        val a = lock(CoopLockStore(File(dir, "a.json"))); val b = lock(CoopLockStore(File(dir, "b.json")))
        connect(); a.onFrame(); b.onFrame()
        a.propose()
        assertTrue(a.ui.value.proposalSent)
        val propose = link.sent.last(); assertEquals(LockMsg.Propose(0), propose)
        b.msg(propose); b.onFrame()
        assertTrue(b.ui.value.partnerProposed)
        link.sent.clear()
        b.accept()
        assertTrue(b.ui.value.locked)
        val answer = link.sent.last(); assertEquals(LockMsg.Answer(true, 0), answer)
        a.msg(answer); a.onFrame()
        assertTrue(a.ui.value.locked)
        assertTrue(CoopGuards.statesBlocked)
    }

    @Test fun acceptRefusedWhenPartnerNotNew() {
        val l = lock(); connect(); l.onFrame()
        l.msg(LockMsg.Propose(2)); l.onFrame()
        link.sent.clear()
        l.accept()
        assertFalse(l.ui.value.locked)
        assertEquals(listOf<LockMsg>(LockMsg.Answer(false, 0)), link.sent)
        assertFalse(CoopGuards.statesBlocked)
    }

    @Test fun proposeRefusedWhenOwnPartyNotEmpty() {
        party = 1
        val l = lock(); connect(); l.onFrame()
        assertEquals(LockRefusal.OWN_NOT_NEW, l.ui.value.refusal)
        link.sent.clear(); l.propose()
        assertTrue(link.sent.isEmpty())
    }

    private fun lockedLock(): CoopLock {
        val store = CoopLockStore(File(dir, "locks.json")); store.lock("own", "partner")
        return lock(store)
    }

    @Test fun noticesQueueInsteadOfReplacing() {
        native.installOk = false
        val l = lockedLock(); connect()
        l.onFrame()
        l.msg(LockMsg.Off); l.onFrame()
        assertEquals(LockNotice.NO_STORY_HOLD, l.notice.value)
        l.clearNotice()
        assertEquals(LockNotice.PARTNER_TURNED_OFF, l.notice.value)
        l.clearNotice()
        assertNull(l.notice.value)
    }

    @Test fun partnerOffUnlocks() {
        val l = lockedLock(); connect(); l.onFrame()
        assertTrue(l.ui.value.locked)
        l.msg(LockMsg.Off); l.onFrame()
        assertFalse(l.ui.value.locked)
        assertEquals(LockNotice.PARTNER_TURNED_OFF, l.notice.value)
        assertFalse(CoopGuards.statesBlocked)
        l.onFrame(); assertFalse(native.on)
    }

    @Test fun statusMismatchUnlocks() {
        val l = lockedLock(); connect(); l.onFrame()
        assertEquals(LockMsg.Status(true), link.sent.first())
        l.msg(LockMsg.Status(false)); l.onFrame()
        assertFalse(l.ui.value.locked)
        assertFalse(CoopStoryHeldGuard.any())
    }

    @Test fun launchHeldUntilConnected() {
        val l = lockedLock(); l.onFrame()
        assertTrue(CoopGuards.held); assertEquals(LockWait.Partner, l.ui.value.waiting)
        connect(); l.onFrame()
        assertFalse(CoopGuards.held); assertNull(l.ui.value.waiting)
    }

    @Test fun dropHoldsAfterGraceOnly() {
        val l = lockedLock(); connect(); l.onFrame()
        link.st.value = CoopState.Off(); now += 1000; l.onFrame()
        assertFalse(CoopGuards.held)
        now += 3100; l.onFrame()
        assertTrue(CoopGuards.held); assertEquals(LockWait.Partner, l.ui.value.waiting)
        connect(); l.onFrame()
        assertFalse(CoopGuards.held)
    }

    @Test fun noLinkHoldDuringCoopBattle() {
        val l = lockedLock(); connect(); l.onFrame()
        CoopGuards.battleActive = { true }
        link.st.value = CoopState.Off(); now += 10_000; l.onFrame()
        assertFalse(CoopGuards.held)
        assertFalse(native.on)
    }

    @Test fun storyGoWhenBothHold() {
        val l = lockedLock(); connect(); l.onFrame()
        assertTrue(native.on)
        native.held = 5; l.onFrame()
        assertEquals(LockWait.AtStory(5), l.ui.value.waiting)
        assertEquals(LockMsg.Hold(5), link.sent.last())
        assertTrue(native.goes.isEmpty())
        l.msg(LockMsg.Hold(5)); l.onFrame()
        assertEquals(listOf(5), native.goes)
    }

    @Test fun goRetriedWhileNotNow() {
        val l = lockedLock(); connect(); l.onFrame()
        native.held = 5; native.goResult = -1
        l.msg(LockMsg.Hold(5)); l.onFrame()
        l.onFrame(); l.onFrame()
        assertEquals(listOf(5, 5, 5), native.goes)
        native.goResult = 1; l.onFrame()
        assertEquals(4, native.goes.size)
        l.onFrame()
        assertEquals(4, native.goes.size)
    }

    @Test fun closeReleasesEveryGuard() {
        val l = lockedLock(); l.onFrame()
        CoopGuards.hold(CoopGuards.Hold.BATTLE, true)   // plan 4's own reason stays
        l.close()
        assertTrue(CoopGuards.held)
        CoopGuards.hold(CoopGuards.Hold.BATTLE, false)
        assertFalse(CoopGuards.held); assertFalse(CoopGuards.statesBlocked)
        assertFalse(native.on)
    }

    @Test fun answerYesWhenWeCannotLockSendsOff() {
        val l = lock(); connect(); l.onFrame()
        l.propose()
        party = 1
        repeat(15) { l.onFrame() }; l.msg(LockMsg.Answer(true, 0))
        link.sent.clear(); l.onFrame()
        assertFalse(l.ui.value.locked)
        assertEquals(listOf<LockMsg>(LockMsg.Off), link.sent.filter { it is LockMsg.Off })
    }

    @Test fun aLateFrameAfterTheReleaseTakesNoGuard() {
        val l = lockedLock(); connect(); l.onFrame()
        assertTrue(CoopGuards.statesBlocked)
        l.releaseGuards()               // the game closed (UI thread) ...
        assertFalse(CoopGuards.held); assertFalse(CoopGuards.statesBlocked)
        link.st.value = CoopState.Off()
        l.onFrame(); l.onFrame()        // ... and a tick already on its way runs after it
        assertFalse(CoopGuards.held); assertFalse(CoopGuards.statesBlocked)
        l.close()                       // the native side still goes out after the release
        assertFalse(native.on)
        l.onFrame()
        assertFalse(CoopGuards.held)
    }

    @Test fun releaseThenCloseStillUninstallsTheHook() {
        val l = lockedLock(); connect(); l.onFrame()
        assertTrue(native.on)
        l.releaseGuards(); l.close()
        assertFalse(native.on)
    }

    @Test fun reinstallRequestPutsTheHookBackAtOnce() {
        val l = lockedLock(); connect(); l.onFrame()
        native.hookLost = true; native.on = false
        l.requestReinstall(); l.onFrame()
        assertFalse(native.hookLost)
        assertTrue(native.on)
    }

    @Test fun reinstallWaitsForTheBattleToEnd() {
        var battle = true
        CoopGuards.battleActive = { battle }
        val l = lockedLock(); connect(); l.onFrame()
        native.hookLost = true
        l.requestReinstall(); l.onFrame()
        assertTrue(native.hookLost)     // not during a co-op battle
        battle = false
        l.onFrame()
        assertFalse(native.hookLost)
    }

    @Test fun staleLockNeverTouchesTheGuards() {
        val store = CoopLockStore(File(dir, "locks.json")); store.lock("own", "partner")
        val newer = lock(store); newer.start()
        assertTrue(CoopGuards.held); assertTrue(CoopGuards.statesBlocked)
        val stale = lock(store)
        stale.releaseGuards(); stale.close()
        assertTrue(CoopGuards.held); assertTrue(CoopGuards.statesBlocked)
        val neverStarted = lock(store)
        assertTrue(CoopGuards.held)
        neverStarted.releaseGuards()
        assertTrue(CoopGuards.statesBlocked)
    }

    @Test fun statusAgainOnFirstPartnerMessageAndOneReplyPerStatus() {
        val l = lockedLock(); connect(); l.onFrame()
        link.sent.clear()
        l.msg(LockMsg.Hold(0)); l.onFrame()
        assertEquals(listOf<LockMsg>(LockMsg.Status(true)), link.sent.filter { it is LockMsg.Status })
        link.sent.clear()
        l.msg(LockMsg.Hold(0)); l.onFrame()
        assertTrue(link.sent.none { it is LockMsg.Status })
        // a disagreeing Status (they are locked, we are not) is answered once
        val u = lock(CoopLockStore(File(dir, "u.json"))); u.onFrame(); link.sent.clear()
        u.msg(LockMsg.Status(true)); u.onFrame()
        assertEquals(listOf<LockMsg>(LockMsg.Status(false)), link.sent.filter { it is LockMsg.Status })
    }

    @Test fun statusRepliesStopWhenTheStoreCannotUnlock() {
        val f = File(dir, "locks.json")
        val store = CoopLockStore(f); store.lock("own", "partner")
        val l = lock(store); connect(); l.onFrame()
        File(dir, "locks.json.tmp").mkdirs()   // the write of the unlock now fails
        link.sent.clear()
        repeat(5) { l.msg(LockMsg.Status(false)) }
        l.onFrame()
        assertTrue(l.ui.value.locked)
        assertEquals(1, link.sent.count { it is LockMsg.Status })
    }

    @Test fun failingInstallIsRetriedSlowly() {
        native.installOk = false
        val l = lockedLock(); connect()
        repeat(299) { l.onFrame() }
        native.installOk = true
        l.onFrame()
        assertFalse(native.on)   // not retried yet
        repeat(2) { l.onFrame() }
        assertTrue(native.on)
    }

    @Test fun hookTakenOutByPatchesIsPutBack() {
        val l = lockedLock(); connect(); l.onFrame()
        assertTrue(native.on)
        native.hookLost = true; native.on = false   // the app's ROM patches came out: uninstalled
        repeat(300) { l.onFrame() }
        assertFalse(native.hookLost)   // installed again
        assertTrue(native.on)          // and active again
    }

    @Test fun failedInstallTellsTheLockedPlayerOnce() {
        native.installOk = false
        val l = lockedLock(); connect()
        l.onFrame()
        assertEquals(LockNotice.NO_STORY_HOLD, l.notice.value)
        l.clearNotice(); repeat(700) { l.onFrame() }
        assertNull(l.notice.value)
    }

    @Test fun nativeFailureNeverReachesTheLoop() {
        val bad = object : LockNative by native {
            override fun held(): IntArray = throw IllegalStateException("boom")
            override fun setActive(on: Boolean) { throw IllegalStateException("boom") }
        }
        val l = CoopLock(link, CoopLockStore(File(dir, "n.json")).also { it.lock("own", "partner") }, "own", "partner", bad, { 0 }, { now })
        connect(); l.onFrame(); l.onFrame(); l.close()
    }

    @Test fun unlockedSaveTouchesNoGuard() {
        val l = lock(); l.onFrame()
        link.st.value = CoopState.Off(); now += 60_000; l.onFrame()
        connect(); l.onFrame()
        assertFalse(CoopGuards.held); assertFalse(CoopGuards.statesBlocked); assertFalse(native.on)
    }
}

private object CoopStoryHeldGuard { fun any() = CoopGuards.held || CoopGuards.statesBlocked }
