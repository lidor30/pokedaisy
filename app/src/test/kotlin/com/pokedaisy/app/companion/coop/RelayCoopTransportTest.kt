package com.pokedaisy.app.companion.coop

import java.net.InetAddress
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelayCoopTransportTest {
    private val relay = FakeRelayConnector()
    private val network = FakeLanNetwork()
    private val nodes = mutableListOf<Node>()

    private val fast = CoopTimings(
        dialMs = 3_000, directConnectMs = 500, handshakeMs = 1_500, pingMs = 150, idleMs = 1_500,
        pendingMs = 5_000, duplicateWindowMs = 5_000, backoffStartMs = 30, backoffMaxMs = 120,
        backoffResetMs = 10_000, addressRecheckMs = 10_000,
    )

    private inner class Node(
        timings: CoopTimings = fast,
        url: String? = RELAY_URL,
        relayOnly: Boolean = true,
        autoStart: Boolean = true,
        clock: () -> Long = System::currentTimeMillis,
    ) {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val id = CoopPeerId.of(CoopKeys.publicKey(secret))
        val lan = FakeCoopLan(network)
        val transport = RelayCoopTransport(relay, lan, timings, clock, bindLoopback = true)
        val seen = CopyOnWriteArrayList<CoopEvent>()
        private val url = url
        private val relayOnly = relayOnly

        init {
            nodes += this
            if (autoStart) assertEquals(0, start())
        }

        fun start(): Int = transport.start(secret, url, relayOnly)
        val ticket: String get() = transport.ticket()!!

        fun drain() {
            while (true) seen += transport.poll() ?: return
        }

        /** The first event after [from] that [pred] likes, polling until the deadline. */
        fun waitFor(from: Int = 0, what: String, pred: (CoopEvent) -> Boolean): CoopEvent {
            val end = System.currentTimeMillis() + 5_000
            while (true) {
                drain()
                seen.drop(from).firstOrNull(pred)?.let { return it }
                if (System.currentTimeMillis() > end) fail("no event: $what; saw ${seen.drop(from)}")
                Thread.sleep(5)
            }
        }

        fun connected(peer: CoopPeerId, from: Int = 0, direct: Boolean? = null): CoopEvent.Connected =
            waitFor(from, "Connected") {
                it is CoopEvent.Connected && it.peer == peer && (direct == null || it.direct == direct)
            } as CoopEvent.Connected

        fun disconnected(peer: CoopPeerId, from: Int = 0, code: Int? = null): CoopEvent.Disconnected =
            waitFor(from, "Disconnected") {
                it is CoopEvent.Disconnected && it.peer == peer && (code == null || it.code == code)
            } as CoopEvent.Disconnected

        fun frame(peer: CoopPeerId, from: Int = 0): CoopEvent.Frame =
            waitFor(from, "Frame") { it is CoopEvent.Frame && it.peer == peer } as CoopEvent.Frame

        fun mark(): Int {
            drain()
            return seen.size
        }

        fun has(pred: (CoopEvent) -> Boolean): Boolean {
            drain()
            return seen.any(pred)
        }
    }

    @After fun stop() {
        nodes.forEach { it.transport.close() }
    }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            Thread.sleep(5)
        }
    }

    /** [cond] holds for the whole [ms] (a negative check with a deadline: nothing it guards is ever waited for blindly). */
    private fun assertStays(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        do {
            assertTrue("stopped holding: $what", cond())
            Thread.sleep(10)
        } while (System.currentTimeMillis() < end)
    }

    private fun pairUp(a: Node, b: Node) {
        a.transport.setPolicy(setOf(b.id), false, null)
        b.transport.setPolicy(setOf(a.id), false, null)
    }

    // ---------------------------------------------------------------- pairing over the invite room

    @Test fun invitePairingPendingThenAccepted() {
        val host = Node()
        val joiner = Node()
        host.transport.setPolicy(emptySet(), true, "host")
        assertEquals(0, joiner.transport.connect(host.ticket))
        val h = host.connected(joiner.id)
        val j = joiner.connected(host.id)
        assertTrue(h.pending); assertFalse(h.outgoing); assertFalse(h.direct)
        assertFalse(j.pending); assertTrue(j.outgoing); assertFalse(j.direct)

        assertFalse(host.transport.send(joiner.id, byteArrayOf(1)))
        host.transport.accept(joiner.id)
        assertTrue(host.transport.send(joiner.id, byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), joiner.frame(host.id).bytes)
        assertTrue(joiner.transport.send(host.id, byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(9), host.frame(joiner.id).bytes)
    }

    @Test fun pendingCapsOverflow() {
        val host = Node()
        val joiner = Node()
        host.transport.setPolicy(emptySet(), true, null)
        joiner.transport.connect(host.ticket)
        host.connected(joiner.id)
        joiner.connected(host.id)
        repeat(9) { assertTrue(joiner.transport.send(host.id, byteArrayOf(it.toByte()))) }
        host.disconnected(joiner.id, code = CoopFrames.OVERFLOW)
        joiner.disconnected(host.id, code = CoopFrames.OVERFLOW)
        assertTrue(host.seen.count { it is CoopEvent.Frame } <= 8)
    }

    @Test fun pendingOversizedFrameOverflows() {
        val host = Node()
        val joiner = Node()
        host.transport.setPolicy(emptySet(), true, null)
        joiner.transport.connect(host.ticket)
        host.connected(joiner.id)
        joiner.connected(host.id)
        assertTrue(joiner.transport.send(host.id, ByteArray(2_049) { 1 }))
        host.disconnected(joiner.id, code = CoopFrames.OVERFLOW)
        assertFalse(host.has { it is CoopEvent.Frame })
    }

    @Test fun pendingExpires() {
        val host = Node(fast.copy(pendingMs = 400))
        val joiner = Node()
        host.transport.setPolicy(emptySet(), true, null)
        joiner.transport.connect(host.ticket)
        assertTrue(host.connected(joiner.id).pending)
        host.disconnected(joiner.id, code = CoopFrames.PENDING_EXPIRED)
        joiner.disconnected(host.id, code = CoopFrames.PENDING_EXPIRED)
    }

    @Test fun closedPairingRefusesNotAllowed() {
        val host = Node(relayOnly = false)
        val joiner = Node(relayOnly = false)
        host.transport.setPolicy(emptySet(), false, null)
        assertEquals(0, joiner.transport.connect(host.ticket))
        val failed = joiner.waitFor(0, "DialFailed") { it is CoopEvent.DialFailed } as CoopEvent.DialFailed
        assertEquals(CoopFrames.NOT_ALLOWED, failed.code)
        assertFalse(joiner.has { it is CoopEvent.Connected })
        assertFalse(host.has { it is CoopEvent.Connected })
    }

    @Test fun impostorHostNeverConnects() {
        val host = Node(fast.copy(dialMs = 1_500))
        val joiner = Node(fast.copy(dialMs = 1_500))
        val room = CoopKeys.inviteRoom(CoopTicket.decode(host.ticket)!!.inviteSecret!!)
        // someone who knows the room but not the host's key sits in it first and answers with noise
        val holder = arrayOfNulls<CoopRelaySocket>(1)
        holder[0] = relay.open(
            RELAY_URL, room,
            object : CoopRelaySocket.Listener {
                override fun onOpen() {}
                override fun onMessage(bytes: ByteArray) {
                    if (bytes[0].toInt() == CoopFrames.RELAY_DATA) holder[0]?.send(CoopFrames.relayData(ByteArray(48) { 7 }))
                }
                override fun onClosed(code: Int) {}
            },
        )
        await("impostor in the room") { relay.socketsIn(room) == 1 }
        host.transport.setPolicy(emptySet(), true, null)
        assertEquals(0, joiner.transport.connect(host.ticket))
        joiner.waitFor(0, "DialFailed") { it is CoopEvent.DialFailed }
        assertFalse(joiner.has { it is CoopEvent.Connected })
        assertFalse(host.has { it is CoopEvent.Connected })
        holder[0]?.close()
    }

    // ---------------------------------------------------------------- pair rooms

    @Test fun pairRoomConnectsFromPolicyAlone() {
        val a = Node()
        val b = Node()
        pairUp(a, b)
        val ca = a.connected(b.id)
        val cb = b.connected(a.id)
        assertFalse(ca.pending); assertFalse(cb.pending)
        assertFalse(ca.direct); assertFalse(cb.direct)
        assertTrue(ca.outgoing != cb.outgoing)
        assertEquals(a.id.hex < b.id.hex, ca.outgoing)
        assertTrue(a.transport.send(b.id, byteArrayOf(5)))
        assertArrayEquals(byteArrayOf(5), b.frame(a.id).bytes)
    }

    @Test fun relaySocketDropReconnects() {
        val a = Node()
        val b = Node()
        pairUp(a, b)
        a.connected(b.id); b.connected(a.id)
        val ma = a.mark(); val mb = b.mark()
        relay.dropAll()
        a.disconnected(b.id, ma, CoopFrames.ERROR)
        b.disconnected(a.id, mb, CoopFrames.ERROR)
        a.connected(b.id, ma); b.connected(a.id, mb)
        assertTrue(a.transport.send(b.id, byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(1), b.frame(a.id, mb).bytes)
    }

    @Test fun peerLeftReportsAtOnce() {
        val a = Node(fast.copy(idleMs = 30_000, pingMs = 10_000))
        val b = Node(fast.copy(idleMs = 30_000, pingMs = 10_000))
        pairUp(a, b)
        a.connected(b.id); b.connected(a.id)
        val room = CoopKeys.pairRoom(a.secret, a.id, b.id)!!
        val started = System.currentTimeMillis()
        val ma = a.mark(); val mb = b.mark()
        relay.dropOne(room)   // no CLOSE frame, no idle timeout in reach: only PEER_LEFT can tell the other side
        a.disconnected(b.id, ma, CoopFrames.ERROR)
        b.disconnected(a.id, mb, CoopFrames.ERROR)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 1_000)
    }

    @Test fun closeTellsThePartner() {
        val a = Node()
        val b = Node()
        pairUp(a, b)
        a.connected(b.id); b.connected(a.id)
        val mb = b.mark()
        a.transport.close()
        b.disconnected(a.id, mb)
        assertNull(a.transport.poll())
    }

    @Test fun disconnectAndDenyEcho() {
        val a = Node()
        val b = Node()
        pairUp(a, b)
        a.connected(b.id); b.connected(a.id)
        val ma = a.mark(); val mb = b.mark()
        a.transport.disconnect(b.id)
        assertEquals(CoopFrames.CLOSED, a.disconnected(b.id, ma).code)
        assertEquals(CoopFrames.CLOSED, b.disconnected(a.id, mb).code)
        assertFalse(a.transport.send(b.id, byteArrayOf(1)))

        val host = Node()
        val joiner = Node()
        host.transport.setPolicy(emptySet(), true, null)
        joiner.transport.connect(host.ticket)
        host.connected(joiner.id); joiner.connected(host.id)
        host.transport.deny(joiner.id)
        assertEquals(CoopFrames.DENIED, host.disconnected(joiner.id).code)
        assertEquals(CoopFrames.DENIED, joiner.disconnected(host.id).code)
        assertEquals(1, host.seen.count { it is CoopEvent.Disconnected })
    }

    // ---------------------------------------------------------------- direct paths

    @Test fun directTcpPreferred() {
        val a = Node(relayOnly = false)
        val b = Node(relayOnly = false)
        pairUp(a, b)
        assertEquals(0, a.transport.connect(b.ticket))
        a.connected(b.id, direct = true)
        b.connected(a.id, direct = true)
        await("both keep the same direct connection") {
            val ha = a.transport.channelHashFor(b.id)
            val hb = b.transport.channelHashFor(a.id)
            ha != null && hb != null && ha.contentEquals(hb)
        }
    }

    @Test fun simultaneousDialsSettleOnOne() {
        val a = Node(relayOnly = false)
        val b = Node(relayOnly = false)
        pairUp(a, b)
        val ta = a.ticket; val tb = b.ticket
        assertEquals(0, a.transport.connect(tb))
        assertEquals(0, b.transport.connect(ta))
        a.connected(b.id, direct = true)
        b.connected(a.id, direct = true)
        await("one shared connection") {
            val ha = a.transport.channelHashFor(b.id)
            val hb = b.transport.channelHashFor(a.id)
            ha != null && hb != null && ha.contentEquals(hb)
        }
        assertStays(1_000, "same connection") {
            val ha = a.transport.channelHashFor(b.id)
            ha != null && ha.contentEquals(b.transport.channelHashFor(a.id))
        }
        assertTrue(a.transport.send(b.id, byteArrayOf(4)))
        assertArrayEquals(byteArrayOf(4), b.frame(a.id).bytes)
    }

    @Test fun relayOnlyNeverListensOrAdvertises() {
        val a = Node(relayOnly = true)
        val t = CoopTicket.decode(a.ticket)!!
        assertTrue(t.addresses.isEmpty())
        assertNotNull(t.inviteSecret)
        assertFalse(a.lan.isStarted)
        val b = Node(relayOnly = false)
        assertTrue(b.lan.isStarted)
        assertEquals(1, CoopTicket.decode(b.ticket)!!.addresses.size)
        a.drain(); b.drain()
        assertFalse(a.has { it is CoopEvent.LanPeer })
    }

    @Test fun lanPeersAreReportedNeverOurOwn() {
        val a = Node(relayOnly = false)
        val b = Node(relayOnly = false)
        b.transport.setPolicy(emptySet(), false, "label")
        val found = a.waitFor(0, "LanPeer") { it is CoopEvent.LanPeer } as CoopEvent.LanPeer
        assertEquals(b.id, found.peer)
        assertFalse(a.has { it is CoopEvent.LanPeer && it.peer == a.id })
        b.transport.close()
        a.waitFor(0, "LanGone") { it is CoopEvent.LanGone && it.peer == b.id }
    }

    // ---------------------------------------------------------------- dials, codes, queues, events

    @Test fun dialTimesOutWithoutAnyPath() {
        relay.down = true
        val joiner = Node(fast.copy(dialMs = 400))
        val ghost = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ticket = CoopTicket(CoopPeerId.of(CoopKeys.publicKey(ghost)), ByteArray(16) { 3 }, emptyList()).encode()
        assertEquals(0, joiner.transport.connect(ticket))
        val failed = joiner.waitFor(0, "DialFailed") { it is CoopEvent.DialFailed } as CoopEvent.DialFailed
        assertEquals(CoopFrames.TIMEOUT, failed.code)
    }

    @Test fun badTicketAndNotRunningCodes() {
        val n = Node(autoStart = false)
        val peer = CoopPeerId.of(CoopKeys.publicKey(ByteArray(32) { 1 }))
        assertEquals(-2, n.transport.connect(CoopTicket.peerOnly(peer)))
        assertEquals(-1, n.transport.start(ByteArray(31), RELAY_URL, true))
        assertEquals(-2, n.transport.start(n.secret, "ftp://relay.test", true))
        assertEquals(-2, n.transport.start(n.secret, "relay.test", true))
        assertEquals(0, n.transport.start(n.secret, RELAY_URL, true))
        assertEquals(-4, n.transport.start(n.secret, RELAY_URL, true))
        assertEquals(-1, n.transport.connect("garbage"))
        assertEquals(-1, n.transport.connect(CoopTicket.peerOnly(n.id)))
        assertEquals(n.id, n.transport.endpointId())
        n.transport.close()
        assertNull(n.transport.endpointId())
        assertEquals(-2, n.transport.connect(CoopTicket.peerOnly(peer)))
        assertEquals(0, n.transport.start(n.secret, null, true))   // restarts after a close
    }

    @Test fun queueCapClosesFloodingPeer() {
        val a = Node()
        val b = Node()
        pairUp(a, b)
        a.connected(b.id); b.connected(a.id)
        val frame = ByteArray(16) { 2 }
        val end = System.currentTimeMillis() + 10_000
        var sent = 0
        while (sent < 1_300 && a.transport.channelHashFor(b.id) != null) {
            if (a.transport.send(b.id, frame)) sent++ else Thread.sleep(1)
            if (System.currentTimeMillis() > end) fail("flood stalled at $sent")
        }
        // b never polled while the flood ran: its queue held 1,024 frames, the next one closed the peer
        b.disconnected(a.id, code = CoopFrames.OVERFLOW)
        a.disconnected(b.id, code = CoopFrames.OVERFLOW)
        assertTrue("frames: ${b.seen.count { it is CoopEvent.Frame }}", b.seen.count { it is CoopEvent.Frame } <= 1_024)
        assertTrue(sent > 1_024)
    }

    @Test fun noRelayPushesRelayKindZero() {
        val a = Node(url = null)
        a.waitFor(0, "Relay(0)") { it == CoopEvent.Relay(0, "") }
        assertFalse(a.has { it is CoopEvent.Relay && it.kind == 1 })
        assertTrue(CoopTicket.decode(a.ticket)!!.inviteSecret != null)   // a ticket always carries its secret
    }

    @Test fun relayKindOneWhenTheFirstSocketOpens() {
        val a = Node()
        assertFalse(a.has { it is CoopEvent.Relay })
        a.transport.setPolicy(emptySet(), true, null)
        a.waitFor(0, "Relay(1)") { it == CoopEvent.Relay(1, RELAY_URL) }
        assertEquals(1, a.seen.count { it is CoopEvent.Relay })
    }

    @Test fun readyAgainWhenAddressesChange() {
        val timings = fast.copy(addressRecheckMs = 100)
        val a = Node(timings, relayOnly = false, autoStart = false)
        val first = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5))
        val second = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 6))
        a.transport.addresses = { listOf(first) }
        assertEquals(0, a.start())
        val one = (a.waitFor(0, "Ready") { it is CoopEvent.Ready } as CoopEvent.Ready).ticket
        assertEquals(first, CoopTicket.decode(one)!!.addresses.single().address)
        a.transport.addresses = { listOf(first, second) }
        val two = a.waitFor(0, "Ready with two") { it is CoopEvent.Ready && it.ticket != one } as CoopEvent.Ready
        assertEquals(2, CoopTicket.decode(two.ticket)!!.addresses.size)
        assertEquals(two.ticket, a.transport.ticket())
        // nothing changed: no third Ready
        assertStays(300, "no third Ready") { a.drain(); a.seen.count { it is CoopEvent.Ready } == 2 }
    }

    // ---------------------------------------------------------------- the fix round

    @Test fun acceptSurvivesAReplacingConnection() {
        relay.down = true   // TCP only: two dials by the joiner, the second replaces the first (same initiator, a tie takes the newer)
        val host = Node(relayOnly = false)
        val joiner = Node(relayOnly = false)
        host.transport.setPolicy(emptySet(), true, null)
        assertEquals(0, joiner.transport.connect(host.ticket))
        assertTrue(host.connected(joiner.id).pending)
        joiner.connected(host.id)
        host.transport.accept(joiner.id)
        val mh = host.mark(); val mj = joiner.mark()
        assertEquals(0, joiner.transport.connect(host.ticket))
        val again = host.connected(joiner.id, mh)
        assertFalse("the replacement must not ask again", again.pending)
        joiner.connected(host.id, mj)
        assertTrue(host.transport.send(joiner.id, byteArrayOf(1)))   // a PAIR_ACCEPT-style frame goes out
        assertArrayEquals(byteArrayOf(1), joiner.frame(host.id, mj).bytes)
        assertTrue(joiner.transport.send(host.id, byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(2), host.frame(joiner.id, mh).bytes)
        // a denied peer is pending again
        host.transport.deny(joiner.id)
        host.disconnected(joiner.id, mh, CoopFrames.DENIED)
        joiner.disconnected(host.id, mj, CoopFrames.DENIED)
        val mh2 = host.mark()
        joiner.transport.connect(host.ticket)
        assertTrue(host.connected(joiner.id, mh2).pending)
    }

    @Test fun aConnectedPeerNeverGetsDialFailed() {
        relay.down = true
        val t = fast.copy(dialMs = 600)
        val a = Node(t, relayOnly = false)
        val b = Node(t, relayOnly = false)
        pairUp(a, b)
        assertEquals(0, a.transport.connect(b.ticket))
        a.connected(b.id, direct = true); b.connected(a.id, direct = true)
        relay.down = false
        val room = CoopKeys.pairRoom(a.secret, a.id, b.id)!!
        await("both in the pair room") { relay.socketsIn(room) == 2 }
        val m = a.mark()
        assertEquals(0, a.transport.connect(b.ticket))   // the new connection loses (or never starts) while a direct one lives
        assertStays(1_500, "no DialFailed while connected") { a.drain(); a.seen.drop(m).none { it is CoopEvent.DialFailed } }
        assertTrue(a.transport.send(b.id, byteArrayOf(1)))
    }

    @Test fun pairRoomStartsAgainAfterTheChannelDies() {
        val t = fast.copy(pingMs = 60_000, idleMs = 500)   // nobody pings: both sessions time out, the sockets stay up
        val a = Node(t)
        val b = Node(t)
        pairUp(a, b)
        a.connected(b.id); b.connected(a.id)
        for (n in listOf(a, b)) {
            val peer = if (n === a) b else a
            val down = n.disconnected(peer.id, code = CoopFrames.TIMEOUT)
            val from = n.seen.indexOf(down) + 1
            n.connected(peer.id, from)
        }
    }

    @Test fun reconnectBackoffDoublesAndResets() {
        val now = AtomicLong(1_000_000)
        val t = CoopTimings(backoffStartMs = 1_000, backoffMaxMs = 30_000, backoffResetMs = 30_000)
        val n = Node(t, clock = { now.get() })
        n.transport.random = object : Random() { override fun nextDouble() = 0.5 }   // no jitter
        n.transport.setPolicy(emptySet(), true, null)   // the invite room: one socket, no partner
        await("socket up") { relay.openSockets() == 1 }
        fun dropAndExpect(delayMs: Long) {
            assertStays(150, "settled") { true }
            relay.dropAll()
            await("socket gone") { relay.openSockets() == 0 }
            assertStays(150, "the transport has seen the close") { true }
            now.addAndGet(delayMs - 1)
            assertStays(400, "not before ${delayMs} ms") { relay.openSockets() == 0 }
            now.addAndGet(2)
            await("back after ${delayMs} ms") { relay.openSockets() == 1 }
        }
        dropAndExpect(1_000)
        dropAndExpect(2_000)
        dropAndExpect(4_000)
        assertStays(150, "settled") { true }
        now.addAndGet(30_000)   // the socket stayed up for the reset time
        dropAndExpect(1_000)
    }

    @Test fun backoffDelaysStayWithinJitterAndCap() {
        val rnd = object : Random() { var v = 0.5; override fun nextDouble() = v }
        val b = RoomBackoff(1_000, 30_000) { rnd }
        val base = listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000)
        for (expected in base) assertEquals(expected, b.next())
        b.reset()
        rnd.v = 0.0
        assertEquals(800L, b.next())
        rnd.v = 1.0
        assertEquals(2_400L, b.next())   // 2,000 + 20%
        rnd.v = 0.99
        for (i in 0 until 10) assertTrue(b.next() in 1..36_000L)
    }

    @Test fun duplicateRuleBranches() {
        // dead or older than the window: the new one replaces, whatever else
        assertTrue(keepsNewConnection(true, 0, 5_000, true, false, "a", "b"))
        assertTrue(keepsNewConnection(false, 5_000, 5_000, true, false, "a", "b"))
        // inside the window: direct beats relay, both ways
        assertTrue(keepsNewConnection(false, 100, 5_000, false, true, "a", "b"))
        assertFalse(keepsNewConnection(false, 100, 5_000, true, false, "b", "a"))
        // same kind: the smaller initiator key stays, the same initiator takes the newer
        assertTrue(keepsNewConnection(false, 100, 5_000, true, true, "b", "a"))
        assertFalse(keepsNewConnection(false, 100, 5_000, true, true, "a", "b"))
        assertTrue(keepsNewConnection(false, 100, 5_000, false, false, "a", "a"))
    }

    private companion object {
        const val RELAY_URL = "https://relay.test"
    }
}
