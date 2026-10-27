package com.pokedaisy.app.companion.coop

import com.pokedaisy.relay.RelayConfig
import com.pokedaisy.relay.RelayServer
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Two (or three) real transports through the real relay server on loopback; relay only, so every byte crosses it. */
class CoopRelayIntegrationTest {
    private val tapped = CopyOnWriteArrayList<ByteArray>()
    private val servers = mutableListOf<RelayServer>()
    private val nodes = mutableListOf<Node>()
    private val server = startServer(0)
    private val url = "http://127.0.0.1:${server.port}"

    private val fast = CoopTimings(
        dialMs = 5_000, directConnectMs = 500, handshakeMs = 2_000, pingMs = 500, idleMs = 5_000,
        pendingMs = 10_000, duplicateWindowMs = 5_000, backoffStartMs = 100, backoffMaxMs = 1_000,
        backoffResetMs = 10_000, addressRecheckMs = 10_000,
    )

    // maxMessagesPerSec is raised: the tests burst 200 frames at once, which the default 100/s limit rightly cuts (4429).
    private fun startServer(port: Int): RelayServer =
        RelayServer(RelayConfig(port = port, healthPort = 0, maxMessagesPerSec = 1_000)) { tapped += it }.also { it.start(); servers += it }

    private inner class Node {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val id = CoopPeerId.of(CoopKeys.publicKey(secret))
        val transport = RelayCoopTransport(OkHttpRelayConnector(), NoCoopLan, fast, System::currentTimeMillis, bindLoopback = true)
        val seen = CopyOnWriteArrayList<CoopEvent>()

        init {
            nodes += this
            assertEquals(0, transport.start(secret, url, true))
        }

        val ticket: String get() = transport.ticket()!!

        private fun drain() {
            while (true) seen += transport.poll() ?: return
        }

        fun mark(): Int { drain(); return seen.size }

        fun waitFor(from: Int = 0, ms: Long = 10_000, what: String, pred: (CoopEvent) -> Boolean): CoopEvent {
            val end = System.currentTimeMillis() + ms
            while (true) {
                drain()
                seen.drop(from).firstOrNull(pred)?.let { return it }
                if (System.currentTimeMillis() > end) fail("no event: $what; saw ${seen.drop(from)}")
                Thread.sleep(5)
            }
        }

        fun connected(peer: CoopPeerId, from: Int = 0, ms: Long = 10_000) =
            waitFor(from, ms, "Connected") { it is CoopEvent.Connected && it.peer == peer } as CoopEvent.Connected

        fun disconnected(peer: CoopPeerId, from: Int = 0, ms: Long = 10_000) =
            waitFor(from, ms, "Disconnected") { it is CoopEvent.Disconnected && it.peer == peer }

        /** The next [n] frames from [peer], in arrival order. */
        fun frames(peer: CoopPeerId, n: Int): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            val end = System.currentTimeMillis() + 15_000
            while (out.size < n) {
                val e = transport.poll()
                if (e is CoopEvent.Frame && e.peer == peer) out += e.bytes
                else if (e != null) seen += e
                else {
                    if (System.currentTimeMillis() > end) fail("got ${out.size} of $n frames")
                    Thread.sleep(2)
                }
            }
            return out
        }
    }

    @After fun stop() {
        nodes.forEach { it.transport.close() }
        servers.forEach { runCatching { it.stop() } }
    }

    private fun allowEachOther(a: Node, b: Node) {
        a.transport.setPolicy(setOf(b.id), false, null)
        b.transport.setPolicy(setOf(a.id), false, null)
    }

    private fun pairedThroughInvite(host: Node, joiner: Node) {
        host.transport.setPolicy(emptySet(), true, "host")
        assertEquals(0, joiner.transport.connect(host.ticket))
        host.connected(joiner.id)
        joiner.connected(host.id)
        host.transport.accept(joiner.id)
    }

    /** Sends a 60,000-byte frame then 200 small ones from [from] to [to] and checks them in order. */
    private fun exchange(from: Node, to: Node) {
        val big = ByteArray(60_000) { (it * 7).toByte() }
        assertTrue(from.transport.send(to.id, big))
        repeat(200) { assertTrue(from.transport.send(to.id, byteArrayOf(1, (it shr 8).toByte(), it.toByte()))) }
        val got = to.frames(from.id, 201)
        assertArrayEquals(big, got[0])
        repeat(200) { assertArrayEquals(byteArrayOf(1, (it shr 8).toByte(), it.toByte()), got[it + 1]) }
    }

    @Test fun pairsThroughTheRelay() {
        val host = Node(); val joiner = Node()
        pairedThroughInvite(host, joiner)
        exchange(host, joiner)
        exchange(joiner, host)
    }

    @Test fun relaySeesOnlyCiphertext() {
        val host = Node(); val joiner = Node()
        pairedThroughInvite(host, joiner)
        val marker = "MARKER-pokedaisy-secret-payload-0123456789".toByteArray()
        assertTrue(host.transport.send(joiner.id, marker))
        assertArrayEquals(marker, joiner.frames(host.id, 1)[0])
        assertTrue("the relay forwarded something", tapped.isNotEmpty())
        tapped.forEach { body ->
            val hay = String(body, Charsets.ISO_8859_1)
            assertFalse("plaintext crossed the relay", hay.contains("MARKER-pokedaisy"))
        }
    }

    @Test fun serverRestartReconnects() {
        val a = Node(); val b = Node()
        allowEachOther(a, b)
        a.connected(b.id); b.connected(a.id)
        val port = server.port
        val ma = a.mark(); val mb = b.mark()
        server.stop()
        a.disconnected(b.id, ma, 3_000); b.disconnected(a.id, mb, 3_000)
        val ma2 = a.mark(); val mb2 = b.mark()
        startServer(port)
        a.connected(b.id, ma2, 10_000); b.connected(a.id, mb2, 10_000)
        exchange(a, b)
    }

    @Test fun partnerLeavingIsSeenAtOnce() {
        val a = Node(); val b = Node()
        allowEachOther(a, b)
        a.connected(b.id); b.connected(a.id)
        val mb = b.mark()
        a.transport.close()
        b.disconnected(a.id, mb, 2_000)
    }

    @Test fun relayRejectsAThirdDevice() {
        val host = Node(); val joiner = Node(); val third = Node()
        pairedThroughInvite(host, joiner)
        host.connected(joiner.id)
        assertEquals(0, third.transport.connect(host.ticket))
        third.waitFor(0, 10_000, "DialFailed") { it is CoopEvent.DialFailed }
        assertFalse(third.seen.any { it is CoopEvent.Connected })
        exchange(host, joiner)
    }
}
