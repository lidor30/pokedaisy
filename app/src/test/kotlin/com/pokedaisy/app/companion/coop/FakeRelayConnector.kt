package com.pokedaisy.app.companion.coop

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * An in-memory relay with the server's room semantics: two sockets a room (PEER_JOINED to both at two,
 * PEER_LEFT to the one left), a third refused with 4409, [down] refuses every new socket (1006) and
 * [dropAll] closes every socket with 1006. Each socket delivers its callbacks on its own single thread, in
 * order, never inline (a channel's pipe is called under its lock).
 */
class FakeRelayConnector : CoopRelayConnector {
    @Volatile var down = false

    private val lock = Any()
    private val rooms = HashMap<String, MutableList<FakeSocket>>()
    private val all = mutableListOf<FakeSocket>()

    /** Sockets currently in [room], in join order. */
    fun socketsIn(room: String): Int = synchronized(lock) { rooms[room]?.size ?: 0 }

    /** Every socket ever opened and still open. */
    fun openSockets(): Int = synchronized(lock) { all.count { !it.closed } }

    override fun open(relayUrl: String, room: String, listener: CoopRelaySocket.Listener): CoopRelaySocket {
        val socket = FakeSocket(room, listener)
        socket.run {
            if (down) return@run socket.end(1006)
            var others: List<FakeSocket>? = null
            synchronized(lock) {
                val members = rooms.getOrPut(room) { mutableListOf() }
                if (members.size >= 2) return@synchronized
                members.add(socket)
                all.add(socket)
                others = members.filter { it !== socket }
            }
            val joined = others ?: return@run socket.end(4409)
            listener.onOpen()
            if (joined.isNotEmpty()) {
                socket.deliver(byteArrayOf(CoopFrames.PEER_JOINED.toByte()))
                joined.forEach { it.deliver(byteArrayOf(CoopFrames.PEER_JOINED.toByte())) }
            }
        }
        return socket
    }

    /** Closes the oldest socket of [room] as a crash would: it sees 1006, the other peer PEER_LEFT. */
    fun dropOne(room: String) {
        val victim = synchronized(lock) { rooms[room]?.firstOrNull() } ?: return
        victim.leave(1006)
    }

    /** Every socket closes with 1006 (a relay restart). */
    fun dropAll() {
        val everyone = synchronized(lock) { all.filter { !it.closed } }
        for (s in everyone) {
            synchronized(lock) { rooms[s.room]?.remove(s) }
            s.end(1006)
        }
    }

    private inner class FakeSocket(val room: String, private val listener: CoopRelaySocket.Listener) : CoopRelaySocket {
        private val thread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-relay-socket").apply { isDaemon = true } }

        @Volatile var closed = false

        private fun post(r: Runnable) {
            try { thread.execute(r) } catch (_: java.util.concurrent.RejectedExecutionException) {}
        }

        fun run(block: () -> Unit) {
            post { try { block() } catch (e: RuntimeException) { e.printStackTrace() } }
        }

        fun deliver(bytes: ByteArray) {
            post { if (!closed) listener.onMessage(bytes) }
        }

        fun end(code: Int) {
            post {
                if (closed) return@post
                closed = true
                listener.onClosed(code)
            }
            thread.shutdown()
        }

        /** Out of the room: the other peer hears PEER_LEFT, this one [code]. */
        fun leave(code: Int) {
            synchronized(lock) {
                val members = rooms[room] ?: return@synchronized
                if (!members.remove(this)) return@synchronized
                members.forEach { it.deliver(byteArrayOf(CoopFrames.PEER_LEFT.toByte())) }
            }
            end(code)
        }

        override fun send(bytes: ByteArray): Boolean {
            if (closed || bytes.isEmpty() || bytes[0].toInt() != CoopFrames.RELAY_DATA) return false
            synchronized(lock) {
                val members = rooms[room] ?: return true
                if (!members.contains(this)) return false
                members.firstOrNull { it !== this }?.deliver(bytes)
            }
            return true
        }

        override fun close() = leave(1000)
    }
}
