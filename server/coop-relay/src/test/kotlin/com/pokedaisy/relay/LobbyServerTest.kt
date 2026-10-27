package com.pokedaisy.relay

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** `/v1/lobby` over real sockets: the same server as the rooms, one answer per request. */
class LobbyServerTest {
    private val servers = mutableListOf<RelayServer>()
    private val clients = mutableListOf<Client>()
    private val game = "f3ae088181bf583e"
    private val invite = "pdco" + "b".repeat(82)

    private class Client(uri: URI) : WebSocketClient(uri) {
        val messages = LinkedBlockingQueue<ByteArray>()
        val closes = LinkedBlockingQueue<Int>()
        override fun onOpen(handshakedata: ServerHandshake?) {}
        override fun onMessage(message: String?) {}
        override fun onMessage(bytes: ByteBuffer) {
            messages.add(ByteArray(bytes.remaining()).also { bytes.get(it) })
        }
        override fun onClose(code: Int, reason: String?, remote: Boolean) { closes.add(code) }
        override fun onError(ex: Exception?) {}
        fun next(): ByteArray? = messages.poll(3, TimeUnit.SECONDS)
        fun closeCode(): Int? = closes.poll(3, TimeUnit.SECONDS)
    }

    private fun server(config: RelayConfig = RelayConfig()): RelayServer =
        RelayServer(config.copy(port = 0, healthPort = 0)).also { it.start(); servers.add(it) }

    private fun lobby(s: RelayServer): Client {
        val c = Client(URI("ws://127.0.0.1:${s.port}/v1/lobby"))
        clients.add(c)
        assertTrue(c.connectBlocking(10, TimeUnit.SECONDS))
        return c
    }

    private fun msg(vararg parts: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) when (p) {
            is Int -> out.write(p)
            is Byte -> out.write(p.toInt())
            is String -> { val raw = p.toByteArray(); out.write(raw.size); out.write(raw) }
        }
        return out.toByteArray()
    }

    private fun stringAt(b: ByteArray, at: Int) = String(b, at + 1, b[at].toInt(), Charsets.UTF_8)

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.closeBlocking() } }
        servers.forEach { it.stop() }
    }

    @Test
    fun hostFindAndList() {
        val s = server()
        val host = lobby(s)
        val guest = lobby(s)
        host.send(msg(LobbyWire.HOST, LobbyWire.FLAG_LISTED, invite, game, "MAY"))
        val hosted = host.next()!!
        assertEquals(LobbyWire.HOSTED, hosted[0])
        val code = stringAt(hosted, 1)

        guest.send(msg(LobbyWire.LIST, game))
        val rooms = guest.next()!!
        assertEquals(LobbyWire.ROOMS, rooms[0])
        assertEquals(1, rooms[1].toInt())
        assertEquals(code, stringAt(rooms, 2))
        assertEquals("MAY", stringAt(rooms, 3 + code.length))

        guest.send(msg(LobbyWire.FIND, code.substring(0, 3) + "-" + code.substring(3)))
        val found = guest.next()!!
        assertEquals(LobbyWire.FOUND, found[0])
        assertEquals(invite, stringAt(found, 1))

        // The host leaves: its room goes with its socket.
        host.closeBlocking()
        Thread.sleep(200)
        guest.send(msg(LobbyWire.FIND, code))
        assertArrayEquals(byteArrayOf(LobbyWire.ERROR, LobbyWire.FIND, LobbyWire.NOT_FOUND.toByte()), guest.next())
    }

    @Test
    fun roomsStillWorkBesideTheLobby() {
        val s = server()
        val room = "0123456789abcdef0123456789abcdef"
        val a = Client(URI("ws://127.0.0.1:${s.port}/v1/rooms/$room")).also { clients.add(it) }
        val b = Client(URI("ws://127.0.0.1:${s.port}/v1/rooms/$room")).also { clients.add(it) }
        assertTrue(a.connectBlocking(10, TimeUnit.SECONDS))
        lobby(s)
        assertTrue(b.connectBlocking(10, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(0x10), a.next())
        b.send(byteArrayOf(1, 42))
        assertArrayEquals(byteArrayOf(1, 42), a.next())
    }

    @Test
    fun junkAndTextCloseTheLobbySocket() {
        val s = server()
        val c = lobby(s)
        c.send(byteArrayOf(0x7F))
        assertEquals(4400, c.closeCode())
        val t = lobby(s)
        t.send("hello")
        assertEquals(4400, t.closeCode())
    }

    @Test
    fun aLimitedAddressIsRefusedAtTheHandshake() {
        val s = server(RelayConfig(lobbyMaxSocketsPerIp = 1))
        lobby(s)
        val second = Client(URI("ws://127.0.0.1:${s.port}/v1/lobby")).also { clients.add(it) }
        assertTrue(!second.connectBlocking(5, TimeUnit.SECONDS) || second.closeCode() != null)
    }

    @Test
    fun badRequestsGetAnErrorNotAClose() {
        val s = server()
        val c = lobby(s)
        c.send(msg(LobbyWire.LIST, "NOT-A-GAME"))
        assertArrayEquals(byteArrayOf(LobbyWire.ERROR, LobbyWire.LIST, LobbyWire.BAD_REQUEST.toByte()), c.next())
        c.send(msg(LobbyWire.HOST, 0, invite, game, ""))
        assertNotNull(c.next()?.takeIf { it[0] == LobbyWire.HOSTED })
    }
}
