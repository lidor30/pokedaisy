package com.pokedaisy.app.companion.coop

import java.io.File
import java.util.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class CoopLinkImplTest {
    private val tmp: File = java.nio.file.Files.createTempDirectory("coop").toFile()

    @org.junit.After fun removeTmp() { tmp.deleteRecursively() }

    private val net = FakeNet()
    private var now = 1_000_000L
    private val jobs = mutableListOf<Job>()

    private val saveA = CoopSaveId(1, 11, byteArrayOf(0xBB.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0))
    private val saveB = CoopSaveId(2, 22, byteArrayOf(0xC7.toByte(), 0xBB.toByte(), 0xD3.toByte(), 0xFF.toByte(), 0, 0, 0, 0))
    private val tickA = ticketOf(0xA1)
    private val tickB = ticketOf(0xB1)
    private val tA = net.node(0xA1, tickA)
    private val tB = net.node(0xB2, tickB)

    private fun hello(save: CoopSaveId, ticket: String?, protocol: Int = 1, sha: String = EMERALD_COOP_SHA1) =
        CoopHello(protocol, "1.3.1", sha, save, false, ticket)

    private fun partner(peer: CoopPeerId, save: CoopSaveId) = CoopPartner(peer, "X", save.trainerId, false, save)

    private fun pairsWith(own: CoopSaveId, other: CoopSaveId, otherPeer: CoopPeerId, ticket: String?, name: String): CoopPairs {
        val p = CoopPairs(File(tmp, "$name/pairs.json"))
        assertTrue(p.add(CoopPair(own, "Emerald", partner(otherPeer, other), ticket, 1L)))
        return p
    }

    private val pairsA = pairsWith(saveA, saveB, tB.id, tickB, "a")
    private val pairsB = pairsWith(saveB, saveA, tA.id, tickA, "b")

    private var helloB: () -> CoopHello? = { hello(saveB, tickB) }

    private fun link(t: FakeCoopTransport, pairs: CoopPairs, hello: () -> CoopHello?, seed: Long = 1) =
        CoopLinkImpl(t, pairs, hello, { now }, CoopBackoff(Random(seed))).also { l -> t.sink = { l.onEvent(it) } }

    private val linkA = link(tA, pairsA, { hello(saveA, tickA) })
    private val linkB by lazy { link(tB, pairsB, { helloB() }) }

    private fun <T> collect(flow: Flow<T>): MutableList<T> {
        val out = mutableListOf<T>()
        jobs += GlobalScope.launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { flow.collect { out += it } }
        return out
    }

    private fun both() {
        tA.setPolicy(setOf(tB.id), false, null)   // the other node is already up and allows us
        tB.setPolicy(setOf(tA.id), false, null)
        linkA.startFor(saveA)
        linkB.startFor(saveB)
        net.pump()
    }

    private fun frame(from: CoopPeerId, type: Int, payload: ByteArray = ByteArray(0)) =
        CoopEvent.Frame(from, CoopWire.encode(type, payload))

    @org.junit.After fun stopJobs() = jobs.forEach { it.cancel() }

    @Test fun connectsAndDeliversAppMessages() {
        val got = collect(linkB.messages)
        both()
        assertTrue(linkA.state.value is CoopState.Connected)
        assertTrue(linkB.state.value is CoopState.Connected)
        assertTrue(linkA.send(0x0101, byteArrayOf(7)))
        net.pump()
        assertEquals(1, got.size)
        assertEquals(0x0101, got[0].type)
        assertEquals(listOf<Byte>(7), got[0].payload.toList())
    }

    @Test fun linkTypesAndUnknownTypesNeverReachMessages() {
        val got = collect(linkB.messages)
        both()
        for (type in listOf(2, 0x00FF, 0x0400)) linkB.onEvent(frame(tA.id, type, byteArrayOf(1)))
        assertTrue(got.isEmpty())
        assertTrue(linkB.state.value is CoopState.Connected)
    }

    @Test fun messagesBeforeHelloAreIgnored() {
        val got = collect(linkB.messages)
        linkB.startFor(saveB)
        linkB.onEvent(CoopEvent.Connected(tA.id, pending = false, outgoing = true, direct = true))
        linkB.onEvent(frame(tA.id, 0x0101, byteArrayOf(1)))
        assertTrue(got.isEmpty())
        assertFalse(linkB.state.value is CoopState.Connected)
    }

    @Test fun sendRefusesLinkRangeAndOffState() {
        assertThrows(IllegalArgumentException::class.java) { linkA.send(4, byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { linkA.send(0x10000, byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { linkA.send(0x0101, ByteArray(CoopWire.MAX_PAYLOAD + 1)) }
        assertFalse(linkA.send(0x0101, byteArrayOf(1)))
        linkA.startFor(saveA)
        assertFalse(linkA.send(0x0101, byteArrayOf(1)))
    }

    @Test fun differentRomRefused() {
        val got = collect(linkA.messages)
        helloB = { hello(saveB, tickB, sha = "0".repeat(40)) }
        both()
        val s = linkA.state.value as CoopState.Disconnected
        assertEquals(CoopDropReason.DIFFERENT_ROM, s.reason)
        assertTrue(got.isEmpty())
    }

    @Test fun differentSaveRefused() {
        val other = CoopSaveId(3, 33, saveB.name)
        helloB = { hello(other, tickB) }
        both()
        assertEquals(CoopDropReason.DIFFERENT_SAVE, (linkA.state.value as CoopState.Disconnected).reason)
    }

    @Test fun versionRefused() {
        helloB = { hello(saveB, tickB, protocol = 2) }
        both()
        assertEquals(CoopDropReason.VERSION, (linkA.state.value as CoopState.Disconnected).reason)
        assertTrue(tA.sent.any { it.second.type == CoopWire.BYE })
    }

    @Test fun reconnectBacksOffAndResets() {
        linkA.startFor(saveA)
        val gaps = mutableListOf<Long>()
        repeat(3) {
            linkA.onEvent(CoopEvent.DialFailed(6, "x"))
            val s = linkA.state.value as CoopState.Disconnected
            gaps += s.retryAtMs - now
            linkA.tick()
            assertTrue(linkA.state.value is CoopState.Disconnected)   // not due yet
            now = s.retryAtMs
            linkA.tick()
            assertTrue(linkA.state.value is CoopState.Connecting)
        }
        assertTrue(gaps[0] in 800..1200)
        assertTrue(gaps[1] in 1600..2400)
        assertTrue(gaps[2] in 3200..4800)
        linkA.onEvent(CoopEvent.Connected(tB.id, pending = false, outgoing = true, direct = true))
        linkA.onEvent(frame(tB.id, CoopWire.HELLO, hello(saveB, tickB).encode()))
        assertTrue(linkA.state.value is CoopState.Connected)
        linkA.onEvent(CoopEvent.Disconnected(tB.id, 6))
        val gap = (linkA.state.value as CoopState.Disconnected).retryAtMs - now
        assertTrue(gap in 800..1200)
    }

    @Test fun partnerWithNewKeyIsNotRecognised() {
        // B restarted with a new key but answers at the old ticket: A's dials are refused as NOT_ALLOWED.
        val newB = net.node(0xC3, tickB)
        assertNotNull(newB)
        linkA.startFor(saveA)
        net.pump()
        assertEquals(CoopDropReason.NETWORK, (linkA.state.value as CoopState.Disconnected).reason)
        for (i in 1..2) {
            now = (linkA.state.value as CoopState.Disconnected).retryAtMs
            linkA.tick()
            net.pump()
        }
        val s = linkA.state.value as CoopState.Disconnected
        assertEquals(CoopDropReason.NOT_RECOGNISED, s.reason)
        assertEquals(60_000L, s.retryAtMs - now)
        assertTrue(tA.policies.none { it.pairingOpen })
    }

    @Test fun ticketRefreshedFromHello() {
        helloB = { hello(saveB, ticketOf(0x77)) }
        both()
        assertEquals(ticketOf(0x77), pairsA.forSave(saveA)!!.lastTicket)
    }

    @Test fun simultaneousConnectsOneState() {
        val states = collect(linkA.state)
        val statesB = collect(linkB.state)
        both()
        linkA.onEvent(CoopEvent.Disconnected(tB.id, 8))
        linkB.onEvent(CoopEvent.Disconnected(tA.id, 8))
        assertEquals(1, states.count { it is CoopState.Connected })
        assertEquals(1, statesB.count { it is CoopState.Connected })
        assertTrue(states.none { it is CoopState.Disconnected })
        assertTrue(statesB.none { it is CoopState.Disconnected })
        assertTrue(linkA.state.value is CoopState.Connected)
    }

    @Test fun helloIsSentAgainOnEveryConnected() {
        both()
        val before = tA.sent.count { it.second.type == CoopWire.HELLO }
        linkA.onEvent(CoopEvent.Connected(tB.id, pending = false, outgoing = false, direct = true))
        assertEquals(before + 1, tA.sent.count { it.second.type == CoopWire.HELLO })
        assertTrue(linkA.state.value is CoopState.Connected)
    }

    @Test fun noHelloInTenSecondsClosesTheConnection() {
        linkA.startFor(saveA)
        linkA.onEvent(CoopEvent.Connected(tB.id, pending = false, outgoing = true, direct = true))
        now += 9_999
        linkA.tick()
        assertTrue(linkA.state.value is CoopState.Connecting)
        now += 1
        linkA.tick()
        assertTrue(linkA.state.value is CoopState.Disconnected)
        assertTrue(tB.id in tA.disconnected)
    }

    @Test fun byeFromPartnerDrops() {
        both()
        linkA.onEvent(frame(tB.id, CoopWire.BYE, byteArrayOf(0)))
        assertEquals(CoopDropReason.CLOSED, (linkA.state.value as CoopState.Disconnected).reason)
    }

    @Test fun unknownDisconnectCodesAreNetwork() {
        both()
        linkA.onEvent(CoopEvent.Disconnected(tB.id, 200))
        assertEquals(CoopDropReason.NETWORK, (linkA.state.value as CoopState.Disconnected).reason)
    }

    @Test fun stopSaysByeAndGoesOff() {
        both()
        linkA.stop()
        assertEquals(CoopState.Off(), linkA.state.value)
        assertTrue(tA.sent.any { it.second.type == CoopWire.BYE })
        assertTrue(tB.id in tA.disconnected)
    }

    @Test fun notPairedIsOff() {
        val lonely = CoopLinkImpl(tA, CoopPairs(File(tmp, "none/pairs.json")), { null }, { now }, CoopBackoff())
        lonely.startFor(saveA)
        assertEquals(CoopState.Off(CoopOffReason.NOT_PAIRED), lonely.state.value)
    }
    @Test fun restartingWhileConnectedDropsTheOldConnectionAndRedials() {
        both()
        assertTrue(linkA.state.value is CoopState.Connected)
        tA.disconnected.clear()
        linkA.startFor(saveA)
        assertTrue(tB.id in tA.disconnected)
        net.pump()
        assertTrue(linkA.state.value is CoopState.Connected)
    }

    @Test fun restartingWhileConnectedNeverShowsDisconnected() {
        both()
        val states = collect(linkA.state)
        linkA.startFor(saveA)
        net.pump()
        assertTrue(states.toString(), states.none { it is CoopState.Disconnected })
        assertTrue(linkA.state.value is CoopState.Connected)
    }

    @Test fun unreadablePairsFileNeverCrashesATicketRefresh() {
        var failReads = false
        val pairs = CoopPairs(File(tmp, "c/pairs.json"), read = { if (failReads) throw java.io.IOException("gone") else it.readText() })
        assertTrue(pairs.add(CoopPair(saveA, "Emerald", partner(tB.id, saveB), tickB, 1L)))
        val l = CoopLinkImpl(tA, pairs, { hello(saveA, tickA) }, { now }, CoopBackoff(Random(1)))
        tA.sink = { l.onEvent(it) }
        helloB = { hello(saveB, ticketOf(0x77)) }
        l.startFor(saveA)
        failReads = true
        linkB.startFor(saveB)
        net.pump()
        assertTrue(l.state.value is CoopState.Connected)
    }
}
