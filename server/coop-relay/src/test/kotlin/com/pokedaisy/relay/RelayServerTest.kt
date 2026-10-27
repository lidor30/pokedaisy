package com.pokedaisy.relay

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayServerTest {
    private val room = "0123456789abcdef0123456789abcdef"
    private val servers = mutableListOf<RelayServer>()
    private val clients = mutableListOf<TestClient>()

    private class TestClient(uri: URI, headers: Map<String, String> = emptyMap()) : WebSocketClient(uri, headers) {
        val messages = LinkedBlockingQueue<ByteArray>()
        val closes = LinkedBlockingQueue<Int>()
        override fun onOpen(handshakedata: ServerHandshake?) {}
        override fun onMessage(message: String?) {}
        override fun onMessage(bytes: ByteBuffer) {
            val out = ByteArray(bytes.remaining())
            bytes.get(out)
            messages.add(out)
        }
        override fun onClose(code: Int, reason: String?, remote: Boolean) { closes.add(code) }
        override fun onError(ex: Exception?) {}
        fun next(): ByteArray? = messages.poll(3, TimeUnit.SECONDS)
        fun closeCode(): Int? = closes.poll(3, TimeUnit.SECONDS)
    }

    private fun server(config: RelayConfig = RelayConfig(), tap: ((ByteArray) -> Unit)? = null): RelayServer {
        val s = RelayServer(config.copy(port = 0, healthPort = 0), tap)
        s.start()
        servers.add(s)
        return s
    }

    private fun client(s: RelayServer, path: String = "/v1/rooms/$room", headers: Map<String, String> = emptyMap()): TestClient {
        val c = TestClient(URI("ws://127.0.0.1:${s.port}$path"), headers)
        clients.add(c)
        return c
    }

    private fun open(s: RelayServer, path: String = "/v1/rooms/$room", headers: Map<String, String> = emptyMap()): TestClient {
        val c = client(s, path, headers)
        assertTrue("connect $path", c.connectBlocking(10, TimeUnit.SECONDS))
        return c
    }

    private fun refused(s: RelayServer, path: String, headers: Map<String, String> = emptyMap()): Boolean =
        !client(s, path, headers).connectBlocking(10, TimeUnit.SECONDS)

    private fun pair(s: RelayServer): Pair<TestClient, TestClient> {
        val a = open(s)
        val b = open(s)
        assertArrayEquals(byteArrayOf(0x10), a.next())
        assertArrayEquals(byteArrayOf(0x10), b.next())
        return a to b
    }

    private fun roomPath(c: Char) = "/v1/rooms/" + c.toString().repeat(32)

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.closeBlocking() } }
        servers.forEach { runCatching { it.stop() } }
    }

    @Test
    fun forwardsDataUnchanged() {
        val (a, b) = pair(server())
        a.send(byteArrayOf(0x01, 5, 6, 7))
        assertArrayEquals(byteArrayOf(0x01, 5, 6, 7), b.next())
        b.send(byteArrayOf(0x01, 9))
        assertArrayEquals(byteArrayOf(0x01, 9), a.next())
    }

    @Test
    fun thirdPeerClosed4409() {
        val s = server()
        pair(s)
        val c = open(s)
        assertEquals(4409, c.closeCode())
    }

    @Test
    fun peerLeftIsSentAtOnce() {
        val (a, b) = pair(server())
        b.closeBlocking()
        assertArrayEquals(byteArrayOf(0x11), a.next())
    }

    @Test
    fun oversizeMessageClosed4413() {
        val (a, _) = pair(server(RelayConfig(maxMessageBytes = 1000)))
        a.send(ByteArray(1001).also { it[0] = 1 })
        assertEquals(4413, a.closeCode())
    }

    @Test
    fun messageStormClosed4429() {
        val (a, _) = pair(server(RelayConfig(maxMessagesPerSec = 5)))
        repeat(40) { if (a.isOpen) a.send(byteArrayOf(1, 2)) }
        assertEquals(4429, a.closeCode())
    }

    @Test
    fun textMessageClosed4400() {
        val (a, _) = pair(server())
        a.send("hello")
        assertEquals(4400, a.closeCode())
    }

    @Test
    fun unknownKindClosed4400() {
        val (a, _) = pair(server())
        a.send(byteArrayOf(0x55, 1))
        assertEquals(4400, a.closeCode())
    }

    @Test
    fun badPathRefused() {
        val s = server()
        assertTrue(refused(s, "/v1/rooms/XYZ"))
        assertTrue(refused(s, "/"))
        assertTrue(refused(s, "/v1/rooms/" + room.uppercase()))
    }

    @Test
    fun perIpSocketLimit() {
        val s = server(RelayConfig(maxSocketsPerIp = 2))
        open(s, roomPath('a'))
        open(s, roomPath('b'))
        assertTrue(refused(s, roomPath('c')))
    }

    @Test
    fun forwardedForOnlyWithTrustProxy() {
        val plain = server(RelayConfig(maxSocketsPerIp = 1))
        open(plain, roomPath('a'), mapOf("X-Forwarded-For" to "10.0.0.1", "CF-Connecting-IP" to "10.1.0.1"))
        assertTrue(refused(plain, roomPath('b'), mapOf("X-Forwarded-For" to "10.0.0.2", "CF-Connecting-IP" to "10.1.0.2")))

        val proxied = server(RelayConfig(maxSocketsPerIp = 1, proxyMode = ProxyMode.FORWARDED))
        // the last X-Forwarded-For entry is the one the proxy appended
        open(proxied, roomPath('a'), mapOf("X-Forwarded-For" to "6.6.6.6, 10.0.0.1"))
        open(proxied, roomPath('b'), mapOf("X-Forwarded-For" to "6.6.6.6, 10.0.0.2"))
        assertTrue(refused(proxied, roomPath('c'), mapOf("X-Forwarded-For" to "10.0.0.2")))
        // X-Real-IP wins over X-Forwarded-For; CF-Connecting-IP means nothing in this mode
        open(proxied, roomPath('d'), mapOf("X-Real-IP" to "10.1.0.1", "X-Forwarded-For" to "10.0.0.9"))
        assertTrue(refused(proxied, roomPath('e'), mapOf("X-Real-IP" to "10.1.0.1", "CF-Connecting-IP" to "10.8.8.8")))
        // a garbage header falls back to the socket address, a different client from the ones above
        open(proxied, roomPath('f'), mapOf("X-Forwarded-For" to "garbage!"))

        // a peer outside TRUSTED_PROXIES: its headers are ignored whatever the mode
        val untrusted = server(RelayConfig(maxSocketsPerIp = 1, proxyMode = ProxyMode.FORWARDED, trustedProxies = listOf("10.0.0.0/8")))
        open(untrusted, roomPath('a'), mapOf("X-Forwarded-For" to "10.0.0.1"))
        assertTrue(refused(untrusted, roomPath('b'), mapOf("X-Forwarded-For" to "10.0.0.2")))

        // cloudflare mode: a forged CF-Connecting-IP from a proxy that is not Cloudflare is ignored
        val cf = server(RelayConfig(maxSocketsPerIp = 1, proxyMode = ProxyMode.CLOUDFLARE, cloudflareRanges = listOf("104.16.0.0/13")))
        open(cf, roomPath('a'), mapOf("X-Real-IP" to "10.0.0.1", "CF-Connecting-IP" to "9.9.9.1"))
        assertTrue(refused(cf, roomPath('b'), mapOf("X-Real-IP" to "10.0.0.1", "CF-Connecting-IP" to "9.9.9.2")))
    }

    @Test
    fun healthzAnswersOk() {
        val s = server()
        val conn = URL("http://127.0.0.1:${s.healthPort}/healthz").openConnection() as HttpURLConnection
        assertEquals(200, conn.responseCode)
        assertEquals("ok", conn.inputStream.readBytes().decodeToString().trim())
        val missing = URL("http://127.0.0.1:${s.healthPort}/nope").openConnection() as HttpURLConnection
        assertEquals(404, missing.responseCode)
    }

    @Test
    fun tapSeesForwardedBytes() {
        val seen = CopyOnWriteArrayList<ByteArray>()
        val (a, b) = pair(server(tap = { seen.add(it) }))
        a.send(byteArrayOf(0x01, 42, 43))
        assertNotNull(b.next())
        assertEquals(1, seen.size)
        assertArrayEquals(byteArrayOf(42, 43), seen[0])
    }

    @Test
    fun restartsOnTheSamePort() {
        val s = RelayServer(RelayConfig(port = 0, healthPort = 0))
        s.start()
        val port = s.port
        s.stop()
        val again = RelayServer(RelayConfig(port = port, healthPort = 0))
        again.start()
        servers.add(again)
        assertEquals(port, again.port)
        assertFalse(again.healthPort == 0)
    }
}
