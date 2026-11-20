package com.pokedaisy.app

import com.pokedaisy.app.companion.coop.CoopLink
import com.pokedaisy.app.companion.coop.CoopMessage
import com.pokedaisy.app.companion.coop.CoopState
import com.pokedaisy.app.companion.coop.lock.CoopLockActions
import com.pokedaisy.app.companion.coop.lock.CoopLockRules
import com.pokedaisy.app.companion.coop.lock.CoopLockStore
import com.pokedaisy.app.companion.coop.lock.CoopLockUi
import com.pokedaisy.app.companion.coop.lock.CoopLockWire
import com.pokedaisy.app.companion.coop.lock.CoopStoryHold
import com.pokedaisy.app.companion.coop.lock.LockMsg
import com.pokedaisy.app.companion.coop.lock.LockRefusal
import com.pokedaisy.app.companion.coop.lock.LockWait
import com.pokedaisy.app.companion.coop.coopLog
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The native half of the lock ([MgbaLockNative] in the app, a fake in tests). Emu thread only. */
interface LockNative {
    fun install(): Boolean
    fun uninstall()
    fun setActive(on: Boolean)
    /** `[id, kind]`, id 0 = nothing held. */
    fun held(): IntArray
    fun go(id: Int): Int
    fun cancel()
}

/** Why the player is told the lock went off (shown by the later UI task). */
enum class LockNotice { PARTNER_TURNED_OFF, NO_STORY_HOLD }

/**
 * PLAY TOGETHER ONLY for one game with a pairing: the lock's messages, its store entry, its guard reasons
 * ([CoopGuards] LOCK_START / LOCK_LINK / StateBlock.LOCK only; plan 4's BATTLE reasons are never touched) and the native story hold.
 *
 * Threads: [onMessage] may come from any thread (it only queues); [onFrame] and the native calls run on the emu thread, once a
 * loop turn (also while the game is held, which runs no frame); the actions come from the UI thread and use the party count
 * [onFrame] last read. Draws nothing: [ui] feeds the overlay and the settings row.
 */
class CoopLock(
    private val link: CoopLink,
    private val store: CoopLockStore,
    private val own: String,
    private val partner: String,
    private val native: LockNative,
    private val partyCount: () -> Int,
    private val clock: () -> Long,
    /** The story hold at this event waits while a joined story battle for it is being decided ([CoopBattles.storyPending]). */
    private val storyWait: (Int) -> Boolean = { false },
) : CoopLockActions {
    private val lock = SynchronizedObject()
    private val inboxLock = SynchronizedObject()
    private val inbox = ArrayDeque<CoopMessage>()
    private val hold = CoopStoryHold(clock)
    private val _ui = MutableStateFlow(CoopLockUi())
    private val _notice = MutableStateFlow<LockNotice?>(null)
    val ui: StateFlow<CoopLockUi> = _ui.asStateFlow()
    val notice: StateFlow<LockNotice?> = _notice.asStateFlow()

    private var locked = runCatching { store.isLocked(own, partner) }.getOrDefault(false)
    private var installed = false
    private var closed = false   // guards released: no guard is ever taken again
    private var shut = false     // close() ran (the native side is out)
    private var ownParty = 0
    private var partnerParty: Int? = null
    private var partnerProposed = false
    private var proposalSent = false
    private var wasConnected = false
    private var lostSince = clock()
    private var nativeOn = false
    private var pendingGo = 0
    private var heldId = 0
    private var frames = 0
    private var startHold = false
    private var linkHold = false

    private var started = false
    private var heard = false
    private var replied = false
    private val installFailed = HashSet<String>()
    private var installTried = false
    private var holdNoticed = false
    @Volatile private var reinstallNow = false

    /** The app took the ROM patches out (co-op battle end, REUSABLE TMS / PLAYER LOOK applied): put the hook back on the next frame (any thread). */
    fun requestReinstall() { reinstallNow = true }

    init { publish() }

    /** Takes the guards for a locked save: called once the lock is adopted (a stale one is dropped without it), else by the first frame. */
    fun start(): Unit = synchronized(lock) {
        if (started || closed) return
        started = true
        if (locked) {
            CoopGuards.blockStates(CoopGuards.StateBlock.LOCK, true)
            CoopGuards.hold(CoopGuards.Hold.LOCK_START, true)
            startHold = true
        }
        publish()
    }

    fun onMessage(m: CoopMessage) {
        if (m.type in 0x0300..0x03FF) synchronized(inboxLock) { inbox.addLast(m) }
    }

    /** Notices queue: a new one never replaces one still showing. */
    private val noticesLock = SynchronizedObject()
    private val pendingNotices = ArrayDeque<LockNotice>()
    private fun post(n: LockNotice) = synchronized(noticesLock) {
        if (n !in pendingNotices) pendingNotices.add(n)
        if (_notice.value == null) _notice.value = pendingNotices.removeFirstOrNull()
    }
    fun clearNotice() = synchronized(noticesLock) { _notice.value = pendingNotices.removeFirstOrNull() }

    fun onFrame(): Unit = synchronized(lock) {
        if (closed) return
        start()
        val now = reinstallNow && !CoopGuards.battleActive()   // after a ROM-patch change took the hook out: at once, not in 5 s
        if (now) reinstallNow = false
        if (frames % INSTALL_EVERY == 0 || now) {
            // Also once installed: the app's ROM-patch changes (REUSABLE TMS, PLAYER LOOK...) take the hook out natively. install() is
            // idempotent, so this puts it back; either way setActive is applied again (a fresh install starts inactive).
            val wasInstalled = installed
            var kind = "refused"
            installed = try { native.install() } catch (e: Throwable) { kind = e::class.simpleName ?: "error"; false }
            if (wasInstalled) nativeOn = false
            if (!installed && installFailed.add(kind)) log("the story hook didn't go in ($kind): events stay unheld")
            if (!installed) installTried = true
        }
        // Locked but the hook is not in: story events go unheld, so the player is told once.
        if (!installed && installTried && locked && !holdNoticed) {
            holdNoticed = true
            post(LockNotice.NO_STORY_HOLD)
        }
        if (frames++ % PARTY_EVERY == 0) ownParty = runCatching(partyCount).getOrDefault(ownParty)
        val connected = link.state.value is CoopState.Connected
        if (connected && !wasConnected) {
            heard = false
            replied = false
            send(LockMsg.Status(locked))
            CoopGuards.hold(CoopGuards.Hold.LOCK_START, false)
            startHold = false
        }
        if (!connected && wasConnected) lostSince = clock()
        wasConnected = connected
        while (true) {
            val m = synchronized(inboxLock) { inbox.removeFirstOrNull() } ?: break
            val msg = CoopLockWire.decode(m.type, m.payload) ?: continue
            // The first word from the partner after a Connected: they may not have been listening for our Status yet.
            if (!heard) { heard = true; if (msg !is LockMsg.Status) send(LockMsg.Status(locked)) }
            handle(msg)
        }
        val battle = CoopGuards.battleActive()
        if (locked) {
            linkHold = CoopLockRules.holdForLink(true, connected, battle, clock() - lostSince)
            CoopGuards.hold(CoopGuards.Hold.LOCK_LINK, linkHold)
        }
        val wantNative = locked && installed && connected && !battle
        if (wantNative != nativeOn && installed && nat("setActive") { native.setActive(wantNative); true } == true) nativeOn = wantNative
        heldId = if (nativeOn) nat("held") { native.held().getOrElse(0) { 0 } } ?: 0 else 0
        if (locked) storyStep(connected, battle)
        publish()
    }

    private fun storyStep(connected: Boolean, battle: Boolean) {
        val step = hold.step(heldId, connected)
        step.send?.let { send(LockMsg.Hold(it)) }
        step.go?.let { pendingGo = it }
        if (pendingGo != 0) {
            if (heldId != pendingGo || battle) pendingGo = 0
            else if (storyWait(pendingGo)) { /* a joined story battle is being offered: the hold stays until it is decided */ }
            else if ((nat("go") { native.go(pendingGo) } ?: 0) != -1) pendingGo = 0  // -1 = not now: asked again next frame
        }
    }

    private fun handle(m: LockMsg) {
        when (m) {
            is LockMsg.Propose -> { partnerParty = m.partyCount; partnerProposed = true }
            is LockMsg.Answer -> if (proposalSent) {
                proposalSent = false
                partnerParty = m.partyCount
                // They locked on our proposal: if we can't, tell them, or they stay locked alone.
                if (m.accepted && !(refusal(m.partyCount) == null && setLocked(true))) send(LockMsg.Off)
            }
            LockMsg.Off -> if (locked) { setLocked(false); post(LockNotice.PARTNER_TURNED_OFF) }
            is LockMsg.Status -> {
                if (!m.locked && locked) { setLocked(false); post(LockNotice.PARTNER_TURNED_OFF) }
                if (m.locked != locked && !replied) { replied = true; send(LockMsg.Status(locked)) }   // one reply per Connected, whatever the stores do
            }
            is LockMsg.Hold -> hold.onPartner(m.id)
        }
    }

    private fun refusal(partnerCount: Int?): LockRefusal? =
        CoopLockRules.refusal(true, link.state.value is CoopState.Connected, ownParty, partnerCount)

    override fun propose(): Unit = synchronized(lock) {
        if (closed || locked || refusal(0) != null) return
        proposalSent = true
        send(LockMsg.Propose(ownParty))
        publish()
    }

    override fun accept(): Unit = synchronized(lock) {
        if (closed || !partnerProposed) return
        partnerProposed = false
        if (refusal(partnerParty) != null) {
            send(LockMsg.Answer(false, ownParty))
        } else if (setLocked(true)) {
            send(LockMsg.Answer(true, ownParty))
        }
        publish()
    }

    override fun decline(): Unit = synchronized(lock) {
        if (closed || !partnerProposed) return
        partnerProposed = false
        send(LockMsg.Answer(false, ownParty))
        publish()
    }

    override fun turnOff(): Unit = synchronized(lock) {
        if (closed || !locked) return
        setLocked(false)
        send(LockMsg.Off)
        publish()
    }

    /** Stores the new state and moves the guards; false when the store refused (nothing changed). */
    private fun setLocked(on: Boolean): Boolean {
        try {
            if (on) store.lock(own, partner) else store.unlock(own)
        } catch (e: IOException) {
            log("the locks file couldn't be written: ${e.message}")
            return false
        }
        start()
        locked = on
        proposalSent = false
        pendingGo = 0
        if (closed) return true
        CoopGuards.blockStates(CoopGuards.StateBlock.LOCK, on)
        if (!on) {
            CoopGuards.hold(CoopGuards.Hold.LOCK_START, false)
            startHold = false
            CoopGuards.hold(CoopGuards.Hold.LOCK_LINK, false)
            linkHold = false
        }
        return true
    }

    /** A native call that never throws into the emu loop. */
    private fun <T> nat(what: String, block: () -> T): T? =
        try { block() } catch (e: Throwable) { log("$what failed: $e"); null }

    private fun send(m: LockMsg) {
        val (type, payload) = CoopLockWire.encode(m)
        link.send(type, payload)
    }

    private fun publish() {
        val waiting: LockWait? = when {
            !locked -> null
            startHold || linkHold -> LockWait.Partner
            heldId != 0 -> LockWait.AtStory(heldId)
            else -> null
        }
        _ui.value = CoopLockUi(
            locked = locked,
            refusal = if (locked) null else refusal(0),
            partnerProposed = partnerProposed,
            proposalSent = proposalSent,
            waiting = waiting,
        )
    }

    /** The game closed: this lock's guard reasons off at once (any thread). Plan 4's BATTLE reasons stay. */
    fun releaseGuards(): Unit = synchronized(lock) {
        closed = true   // a released lock never takes a guard again: a frame tick already inside onFrame sees it closed
        if (!started) return   // a stale lock never took a guard, so it never lets one go
        CoopGuards.hold(CoopGuards.Hold.LOCK_START, false)
        CoopGuards.hold(CoopGuards.Hold.LOCK_LINK, false)
        CoopGuards.blockStates(CoopGuards.StateBlock.LOCK, false)
    }

    /** Guards off and the native side inactive and out (emu thread; idempotent). */
    fun close(): Unit = synchronized(lock) {
        if (shut) return
        shut = true
        releaseGuards()
        if (installed) {
            nat("setActive") { native.setActive(false) }
            nat("uninstall") { native.uninstall() }
        }
        nativeOn = false
        synchronized(inboxLock) { inbox.clear() }
    }

    private fun log(msg: String) = coopLog("lock: $msg")

    private companion object { const val PARTY_EVERY = 15; const val INSTALL_EVERY = 300 }
}
