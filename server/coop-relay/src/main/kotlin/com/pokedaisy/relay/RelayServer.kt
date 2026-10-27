package com.pokedaisy.relay

import com.sun.net.httpserver.HttpServer
import org.java_websocket.WebSocket
import org.java_websocket.WebSocketImpl
import org.java_websocket.exceptions.WebsocketNotConnectedException
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.extensions.DefaultExtension
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The co-op relay: `GET /v1/rooms/<32 lowercase hex>` upgraded to a WebSocket pairs two peers and forwards their
 * binary DATA messages unchanged (see the protocol in README.md). It never looks inside a message and logs counts
 * and close codes only. [tap] sees each forwarded body (tests only).
 */
class RelayServer(private val config: RelayConfig, private val tap: ((ByteArray) -> Unit)? = null) {
    private val rooms = RelayRooms(config) { System.currentTimeMillis() }
    private val sockets = ConcurrentHashMap<Long, WebSocket>()
    private val ids = AtomicLong()
    private val messages = AtomicLong()
    private val bytes = AtomicLong()
    private val sendFailures = AtomicLong()
    private val closeCodes =ConcurrentHashMap<Int, AtomicLong>()

    private val clientIps = ClientIp(config)
    private val ws = Ws()
    private var health: HttpServer? = null
    private var scheduler: ScheduledExecutorService? = null

    /** The WebSocket port (the real one once started, also for port 0). */
    val port: Int get() = ws.port

    /** The health port (the real one once started). */
    val healthPort: Int get() = health?.address?.port ?: config.healthPort

    /** Binds both servers and returns once they are listening. */
    fun start() {
        ws.isReuseAddr = true
        ws.connectionLostTimeout = config.connectionLostTimeoutSec
        ws.start()
        if (!ws.started.await(10, TimeUnit.SECONDS)) throw IllegalStateException("relay did not start")
        ws.failure?.let { throw it }
        val http = HttpServer.create(InetSocketAddress(config.healthPort), 0)
        http.createContext("/healthz") { ex ->
            val ok = ex.requestMethod == "GET" && ex.requestURI.path == "/healthz"
            val body = if (ok) "ok".toByteArray() else ByteArray(0)
            ex.sendResponseHeaders(if (ok) 200 else 404, if (ok) body.size.toLong() else -1)
            if (ok) ex.responseBody.use { it.write(body) }
            ex.close()
        }
        http.createContext("/") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        http.start()
        health = http
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay-sweep").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ sweep() }, 5, 5, TimeUnit.SECONDS)
            it.scheduleWithFixedDelay({ logStats() }, 60, 60, TimeUnit.SECONDS)
        }
    }

    /** Closes every socket and both servers; returns when done. */
    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        health?.stop(0)
        health = null
        ws.stop(2000)
    }

    private fun sweep() {
        for (id in rooms.expired()) closeWith(id, 4408)
    }

    private fun closeWith(id: Long, code: Int) {
        closeCodes.getOrPut(code) { AtomicLong() }.incrementAndGet()
        sockets[id]?.close(code)
    }

    private fun logStats() {
        val codes = closeCodes.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }
        println("relay rooms=${rooms.roomCount} sockets=${rooms.socketCount} messages=${messages.get()} bytes=${bytes.get()} sendFailures=${sendFailures.get()} closes={$codes}")
    }

    /** `/v1/rooms/<id>` -> the id, else null. */
    private fun roomOf(resource: String): String? {
        val path = resource.substringBefore('?')
        if (!path.startsWith(PATH_PREFIX)) return null
        return path.substring(PATH_PREFIX.length).takeIf { RelayRooms.isRoomId(it) }
    }

    /** The client address: the socket's, or what a trusted proxy says (see [ClientIp]). */
    private fun clientIp(conn: WebSocket, handshake: ClientHandshake): String {
        val socketIp = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
        return clientIps.resolve(socketIp) { handshake.getFieldValue(it) }
    }

    /** A send that a closing socket may refuse; false (counted) when it did. */
    private fun trySend(conn: WebSocket, data: ByteArray): Boolean = try {
        conn.send(data)
        true
    } catch (e: WebsocketNotConnectedException) {
        sendFailures.incrementAndGet()
        false
    }

    private fun isOverBuffer(conn: WebSocket, cap: Int): Boolean {
        val queue = (conn as? WebSocketImpl)?.outQueue ?: return false
        return overBuffer(queue, cap)
    }

    private fun body(message: ByteBuffer): ByteArray = ByteArray(message.remaining()).also { message.get(it) }

    private inner class Ws : WebSocketServer(
        InetSocketAddress(config.port),
        listOf<Draft>(Draft_6455(listOf(DefaultExtension()), listOf(Protocol("")), config.maxMessageBytes * 4)),
    ) {
        val started = CountDownLatch(1)
        @Volatile var failure: Exception? = null

        override fun onStart() { started.countDown() }

        override fun onWebsocketHandshakeReceivedAsServer(conn: WebSocket, draft: Draft, request: ClientHandshake): ServerHandshakeBuilder {
            if (roomOf(request.resourceDescriptor) == null) throw InvalidDataException(4400, "bad path")
            if (!rooms.canAdmit(clientIp(conn, request))) throw InvalidDataException(4429, "limit")
            return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val room = roomOf(handshake.resourceDescriptor)
            if (room == null) { conn.close(4400); return }
            val id = ids.incrementAndGet()
            when (val joined = rooms.join(id, clientIp(conn, handshake), room)) {
                is RelayRooms.Join.Refused -> {
                    closeCodes.getOrPut(joined.code) { AtomicLong() }.incrementAndGet()
                    conn.close(joined.code)
                }
                is RelayRooms.Join.Ok -> {
                    conn.setAttachment(id)
                    sockets[id] = conn
                    val other = joined.other
                    if (other != null) {
                        val joinedMsg = byteArrayOf(KIND_PEER_JOINED)
                        trySend(conn, joinedMsg)
                        sockets[other]?.let { trySend(it, joinedMsg) }
                    }
                }
            }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            val id = conn.getAttachment<Long?>() ?: return
            sockets.remove(id)
            val left = rooms.leave(id)
            if (left != null) sockets[left]?.let { trySend(it, byteArrayOf(KIND_PEER_LEFT)) }
        }

        override fun onMessage(conn: WebSocket, message: String?) {
            val id = conn.getAttachment<Long?>() ?: return
            closeWith(id, 4400)
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            val id = conn.getAttachment<Long?>() ?: return
            val size = message.remaining()
            if (size > config.maxMessageBytes) return closeWith(id, 4413)
            if (!rooms.allow(id, size)) return closeWith(id, 4429)
            if (size == 0 || message.get(message.position()) != KIND_DATA) return closeWith(id, 4400)
            rooms.touch(id)
            messages.incrementAndGet()
            bytes.addAndGet(size.toLong())
            val otherId = rooms.peerOf(id) ?: return
            val other = sockets[otherId] ?: return
            if (isOverBuffer(other, config.maxBufferedBytes)) {
                // The partner stopped reading: close it, drop this message.
                closeWith(otherId, 4429)
                return
            }
            val content = body(message)
            if (!trySend(other, content)) return
            tap?.invoke(content.copyOfRange(1, content.size))
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                failure = ex
                started.countDown()
            }
        }
    }

    private companion object {
        const val PATH_PREFIX = "/v1/rooms/"
        const val KIND_DATA: Byte = 0x01
        const val KIND_PEER_JOINED: Byte = 0x10
        const val KIND_PEER_LEFT: Byte = 0x11
    }
}

/** Whether the unsent data in a socket's out-queue is over [cap] bytes. */
internal fun overBuffer(queue: Collection<ByteBuffer>, cap: Int): Boolean {
    var total = 0L
    for (b in queue) {
        total += b.remaining()
        if (total > cap) return true
    }
    return false
}
