package com.pokedaisy.app

import com.pokedaisy.app.companion.coop.CoopLink
import com.pokedaisy.app.companion.coop.CoopMessage
import com.pokedaisy.app.companion.coop.CoopState
import com.pokedaisy.app.companion.coop.EMERALD_COOP_SHA1
import com.pokedaisy.app.companion.data.FixtureMemoryReader
import com.pokedaisy.app.companion.data.coop.CoopBattleChannel
import com.pokedaisy.app.companion.data.coop.CoopBattleMsg
import com.pokedaisy.app.companion.data.coop.CoopBattleWire
import com.pokedaisy.app.companion.data.coop.CoopCancel
import com.pokedaisy.app.companion.data.coop.CoopCore
import com.pokedaisy.app.companion.data.coop.CoopIdentity
import com.pokedaisy.app.companion.data.coop.CoopJoinRules
import com.pokedaisy.app.companion.data.coop.FakeLinkEngine
import com.pokedaisy.app.companion.ui.CoopOverlayState
import com.pokedaisy.app.companion.ui.MenuKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One device's join rules and after-battle calls, answers set by the test. */
private class FakeCore : CoopCore {
    var kind = 1
    var free = true
    override fun joinKind() = kind
    override fun joinTrainers() = intArrayOf(0, 0)
    override fun partnerFree() = free
    override fun giveMon(mon: ByteArray) = 0
    override fun removeItem(item: Int, count: Int) = 1
    override fun setTrainerFlag(trainerId: Int) = 1
    override fun prize(trainerId: Int, single: Boolean) = 500
    override fun addMoney(amount: Int) = 1
    override fun writePartyMon(slot: Int, mon: ByteArray) = 1
    override fun whiteOut() = 1
    override fun evolve(slot: Int) = 0
    override fun saveGame() = 1
    override fun onFreeField() = true
}

private class FakeChannel : CoopBattleChannel {
    var up = true
    val sent = ArrayList<CoopBattleMsg>()
    override val connected get() = up
    override fun send(type: Int, payload: ByteArray): Boolean {
        if (!up) return false
        sent += CoopBattleWire.decode(type, payload) ?: throw AssertionError("undecodable $type")
        return true
    }
    override val received = emptyFlow<Pair<Int, ByteArray>>()
}

/** Jobs wait for [drain] (the emu thread's turn); with [inline] they run at once, as stopNow's wait needs. */
private class FakeEmu : CoopEmu {
    val queue = ArrayDeque<() -> Unit>()
    var inline = false
    override var running = true
    override fun post(job: () -> Unit) { if (inline) job() else queue.add(job) }
    fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
}

private class FakeEnv(val party: List<ByteArray>) : CoopGlueEnv {
    val busyChanges = ArrayList<Boolean>()
    val logs = ArrayList<String>()
    override val range = 8
    override fun mySpot() = CoopJoinRules.Spot(0, 9, 5, 5)
    override fun partnerSpot() = CoopJoinRules.Spot(0, 9, 7, 5)
    override fun hostParty() = party
    override fun foeSpecies() = 25
    override fun partnerName() = "ASH"
    override fun identity() = CoopIdentity(ByteArray(8) { 0xFF.toByte() }, 0, 1234)
    override fun beforeSave() = true
    override fun busyChanged(busy: Boolean) { busyChanges += busy }
    override fun log(text: String) { logs += text }
}

class CoopBattlesTest {
    private val party: List<ByteArray> = run {
        val mem = FixtureMemoryReader.load("emerald_vanilla")
        val n = mem.readCoreMemory(0x02000000L + 0x244E9, 1)[0].toInt() and 0xFF
        val all = mem.readCoreMemory(0x02000000L + 0x244EC, 6 * 100)
        List(n) { all.copyOfRange(it * 100, (it + 1) * 100) }
    }

    @Before @After fun clean() { CoopGuards.reset() }

    private inner class Rig {
        val core = FakeCore()
        val engine = FakeLinkEngine()
        val channel = FakeChannel()
        val emu = FakeEmu()
        val env = FakeEnv(party)
        val ui = CoopOverlayState()
        var t = 1_000_000L
        val glue = CoopBattleGlue(core, channel, emu, { t }, ui, env, translate = { it }, stopWaitMs = 50, linkEngine = engine)

        fun deliver(m: CoopBattleMsg) {
            val (type, payload) = CoopBattleWire.encode(m)
            glue.onReceived(type, payload)
            emu.drain()
        }

        fun tick(keys: Int = 0): Int? { t += 17; return glue.beforeFrame(keys) }

        /** The host's game held at a battle start with the partner free and ready: the offer goes out and the game waits for the answer. */
        fun hostOffers(): CoopBattleMsg.Offer {
            engine.hold = true
            deliver(CoopBattleMsg.Status(free = true, ready = true))
            tick()                                    // the install probe, then the offer
            tick()
            return channel.sent.filterIsInstance<CoopBattleMsg.Offer>().single()
        }

        /** The partner side up to its answered prompt: ready, offered, joined. */
        fun partnerJoins() {
            tick()                                    // the install probe: ready
            deliver(CoopBattleMsg.Offer(77, 1, 0, 0, 25))
            tick()                                    // the prompt shows
            assertNotNull(ui.join)
            ui.onJoin(); emu.drain()
            assertTrue(channel.sent.any { it is CoopBattleMsg.Answer && it.accept })
        }
    }

    @Test fun hostOfferHoldsTheGameAndStopUndoesIt() {
        val r = Rig()
        val offer = r.hostOffers()
        assertEquals(1, offer.kind)
        assertTrue("the game is held while the partner is asked", CoopGuards.held)
        assertTrue(r.glue.busy)
        assertTrue(CoopGuards.statesBlocked)
        assertTrue(r.glue.loadStateBlocked())
        r.emu.inline = true
        assertTrue(r.glue.stopNow())
        assertTrue(r.channel.sent.any { it is CoopBattleMsg.Cancel && it.reason == CoopCancel.CLOSED })
        assertFalse(r.glue.busy)
        assertFalse(CoopGuards.statesBlocked)
        assertFalse(CoopGuards.held)
        // nothing more runs after the stop: the player's keys, no offers
        assertEquals(5, r.tick(5))
    }

    @Test fun partnerStopCancelsTheJoinedBattle() {
        val r = Rig()
        r.partnerJoins()
        assertTrue(r.glue.busy)
        r.emu.inline = true
        assertTrue(r.glue.stopNow())
        assertTrue(r.channel.sent.any { it is CoopBattleMsg.Cancel && it.reason == CoopCancel.CLOSED })
        assertFalse(r.glue.busy)
        assertEquals(5, r.tick(5))
    }

    @Test fun stopTimeoutFailsClosed() {
        val r = Rig()
        r.hostOffers()
        assertFalse("the emu thread never answers: the caller must write no state", r.glue.stopNow())
    }

    @Test fun aDoesNotCancelTheHostsWait() {
        val r = Rig()
        var cancels = 0
        r.ui.onCancelAsk = { cancels++ }
        r.ui.cancelGraceNanos = 0L
        r.ui.hostWait = "ASH" to 20
        r.ui.key(MenuKey.A)
        assertEquals(0, cancels)
        r.ui.key(MenuKey.B)
        assertEquals(1, cancels)
    }

    @Test fun aBHeldAsTheWaitOpensDoesNotWithdrawTheOffer() {
        val r = Rig()
        var cancels = 0
        r.ui.onCancelAsk = { cancels++ }
        r.ui.hostWait = "ASH" to 20
        r.ui.key(MenuKey.B)
        assertEquals(0, cancels)
    }

    @Test fun busyBlocksLoadState() {
        val r = Rig()
        assertFalse(r.glue.loadStateBlocked())
        r.hostOffers()
        assertTrue(r.glue.busy)
        assertTrue(r.glue.loadStateBlocked())
        assertTrue(CoopGuards.statesBlocked)
        assertEquals(listOf(true), r.env.busyChanges)
    }

    @Test fun notRetailEmeraldNoHook() {
        val link = object : CoopLink {
            override val state = MutableStateFlow<CoopState>(CoopState.Off())
            override val messages = MutableSharedFlow<CoopMessage>()
            override fun send(type: Int, payload: ByteArray) = false
        }
        assertTrue(CoopBattles.mayHook(EMERALD_COOP_SHA1, true, link))
        assertFalse(CoopBattles.mayHook("0000000000000000000000000000000000000000", true, link))
        assertFalse(CoopBattles.mayHook(null, true, link))
        assertFalse(CoopBattles.mayHook(EMERALD_COOP_SHA1, false, link))
        assertFalse(CoopBattles.mayHook(EMERALD_COOP_SHA1, true, null))
        var hooked: CoopFrameHook? = null
        val app = object : CoopApp {
            override val coopOn = true
            override val emu: CoopEmu = FakeEmu()
            override val overlay = CoopOverlayState()
            override val env: CoopGlueEnv = FakeEnv(party)
            override fun core(): CoopCore = FakeCore()
            override fun setHook(hook: CoopFrameHook?) { hooked = hook }
        }
        assertFalse(CoopBattles.attach(app, link, "1111111111111111111111111111111111111111"))
        assertNull(hooked)
        assertFalse(CoopBattles.busy)
    }

    @Test fun messagesHopToEmuThread() {
        val r = Rig()
        r.tick()
        val (type, payload) = CoopBattleWire.encode(CoopBattleMsg.Offer(77, 1, 0, 0, 25))
        r.glue.onReceived(type, payload)
        assertEquals("queued for the emu thread, not handled here", 1, r.emu.queue.size)
        r.tick()
        assertNull("not handled until the job ran", r.ui.join)
        r.emu.drain()
        r.tick()
        assertNotNull("handled in the posted job", r.ui.join)
        // a message that doesn't decode is dropped before anything is posted
        r.glue.onReceived(0x0210, ByteArray(3))
        assertTrue(r.emu.queue.isEmpty())
    }

    @Test fun removedReplicaMessagesAreRefused() {
        // 0x0204-0x020E were the lockstep replica's messages: nothing decodes them any more.
        for (type in 0x0204..0x020E) assertNull("type $type", CoopBattleWire.decode(type, ByteArray(12)))
    }

    @Test fun linkBlocksAreHandedOverWithoutTheEmuQueue() {
        val r = Rig()
        r.tick()
        val blk = byteArrayOf(5, 0, 4, 0, 1, 2, 3, 4)
        val (type, payload) = CoopBattleWire.encode(CoopBattleMsg.LinkBlock(77, 0, blk))
        r.glue.onReceived(type, payload)
        assertTrue("a link block goes straight to the flow's thread-safe queue", r.emu.queue.isEmpty())
    }

    @Test fun statusIsSentOnChangeAndEverySecond() {
        val r = Rig()
        r.tick()
        assertEquals(1, r.channel.sent.filterIsInstance<CoopBattleMsg.Status>().size)
        repeat(10) { r.tick() }
        assertEquals("nothing changed, under a second: no more", 1, r.channel.sent.filterIsInstance<CoopBattleMsg.Status>().size)
        r.core.free = false
        r.tick()
        assertEquals(2, r.channel.sent.filterIsInstance<CoopBattleMsg.Status>().size)
        r.t += 1_100
        r.tick()
        assertEquals(3, r.channel.sent.filterIsInstance<CoopBattleMsg.Status>().size)
        assertTrue(r.channel.sent.filterIsInstance<CoopBattleMsg.Status>().first().ready)
    }
}

class CoopBattlesResumeTest {
    @Test fun resumeReplaysTheLinkStateSeenWhileStopping() {
        val emu = FakeEmu()
        val glue = CoopBattleGlue(FakeCore(), FakeChannel(), emu, { 1_000_000L }, CoopOverlayState(), FakeEnv(emptyList()), translate = { it }, stopWaitMs = 50, linkEngine = FakeLinkEngine())
        emu.inline = true
        glue.stopNow()
        emu.inline = false
        glue.onLinkState(false)          // dropped by the stop ...
        assertTrue(emu.queue.size == 1)
        emu.drain()
        glue.resume()                    // ... so resume asks again with the current state
        assertTrue("resume re-sends the link state", emu.queue.size == 1)
        emu.drain()
    }
}
