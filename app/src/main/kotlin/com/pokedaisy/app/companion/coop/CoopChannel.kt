package com.pokedaisy.app.companion.coop

/** Every co-op timing in milliseconds; tests shorten them. */
data class CoopTimings(
    val dialMs: Long = 10_000,
    val directConnectMs: Long = 1_500,
    val handshakeMs: Long = 5_000,
    val pingMs: Long = 5_000,
    val idleMs: Long = 20_000,
    val pendingMs: Long = 60_000,
    val duplicateWindowMs: Long = 5_000,
    val backoffStartMs: Long = 1_000,
    val backoffMaxMs: Long = 30_000,
    val backoffResetMs: Long = 30_000,
    val addressRecheckMs: Long = 10_000,
)

/**
 * One end-to-end session: a Noise IK handshake and then framed, encrypted traffic over an abstract
 * pipe (a relay room or a TCP socket; the pipe delivers whole messages). Poll-driven like the rest
 * of the transport: the owner calls [receive] for each message that arrives and [tick] now and then;
 * [clock] is any millisecond clock. The listener is never called while the channel's lock is held.
 *
 * [pipe] runs under the channel's lock (it keeps messages in order), so it must only enqueue and
 * never call back into any CoopChannel synchronously. Whoever owns the pipe calls [pipeClosed] when
 * it dies; a failed write is otherwise only seen by the timeouts.
 *
 * After any close every method is a no-op and [send] returns false. Our own [close] sends CLOSE to
 * the peer (once open) and never calls the listener back.
 */
class CoopChannel(
    val initiator: Boolean,
    private val ownSecret: ByteArray,
    remoteStatic: ByteArray?,
    prologue: ByteArray,
    /** Delivers one message to the peer; only enqueues, never re-enters a channel (see the class notes). */
    private val pipe: (ByteArray) -> Boolean,
    private val clock: () -> Long,
    private val timings: CoopTimings,
    /** Responder only: 0 to go on, else the reason code to refuse the initiator with. */
    private val decide: (CoopPeerId) -> Int,
    private val listener: Listener,
) {
    interface Listener {
        fun opened(channel: CoopChannel, remote: CoopPeerId)
        fun frame(channel: CoopChannel, bytes: ByteArray)

        /** [beforeOpen]: the handshake never finished (the initiator reports DialFailed). Not called for our own close(). */
        fun closed(channel: CoopChannel, code: Int, beforeOpen: Boolean)
    }

    private val lock = Any()
    private val noise: CoopNoise =
        if (initiator) CoopNoise.initiator(ownSecret, remoteStatic!!, prologue) else CoopNoise.responder(ownSecret, prologue)
    private val startedAt = clock()
    private var send: CoopCipher? = null
    private var receive: CoopCipher? = null
    private var done = false
    private var began = false
    private var decidePending = false
    private var lastReceived = 0L
    private var lastPing = 0L
    private var peer: CoopPeerId? = null
    private var hash: ByteArray? = null
    private var openedAtMs = 0L

    val remote: CoopPeerId? get() = synchronized(lock) { peer }
    val isOpen: Boolean get() = synchronized(lock) { send != null && !done }
    val openedAt: Long get() = synchronized(lock) { openedAtMs }

    /** The handshake hash, null until open; both sides see the same bytes. */
    internal val handshakeHash: ByteArray? get() = synchronized(lock) { hash?.copyOf() }

    /** The initiator sends message 1 (a second call does nothing); the responder just waits for it. */
    fun begin() {
        synchronized(lock) {
            if (done || !initiator || began) return
            began = true
            pipe(noise.writeMessage(byteArrayOf(0, TRANSPORT_VERSION.toByte())))
        }
    }

    /** One message from the pipe. Never throws: input is untrusted. */
    fun receive(message: ByteArray) {
        val calls = Calls()
        var deciding: CoopPeerId? = null
        synchronized(lock) {
            if (done) return
            when {
                send != null -> session(message, calls)
                initiator -> if (began) initiatorHandshake(message, calls)
                !decidePending -> deciding = responderFirst(message, calls)
            }
        }
        deciding?.let { answer(it, calls) }
        calls.run()
    }

    /** True when [frame] (1..65,536 bytes) went to the pipe; false unless open. */
    fun send(frame: ByteArray): Boolean {
        require(frame.size in 1..CoopFrames.MAX_APP_FRAME) { "frame: 1..${CoopFrames.MAX_APP_FRAME} bytes, got ${frame.size}" }
        synchronized(lock) {
            if (done) return false
            val cipher = send ?: return false
            return pipe(cipher.encrypt(byteArrayOf(CoopFrames.DATA.toByte()) + frame))
        }
    }

    /** Closes with [code], telling the peer once open. No callback. */
    fun close(code: Int) {
        synchronized(lock) {
            if (done) return
            tellPeer(code)
            done = true
        }
    }

    /** Pings, and times out a silent session or a handshake that drags on. */
    fun tick() {
        val calls = Calls()
        synchronized(lock) {
            if (done) return
            val now = clock()
            val cipher = send
            if (cipher == null) {
                if (now - startedAt >= timings.handshakeMs) fail(CoopFrames.TIMEOUT, true, calls)
            } else if (now - lastReceived >= timings.idleMs) {
                fail(CoopFrames.TIMEOUT, false, calls)
            } else if (now - lastPing >= timings.pingMs) {
                lastPing = now
                pipe(cipher.encrypt(byteArrayOf(CoopFrames.PING.toByte())))
            }
        }
        calls.run()
    }

    /** The pipe went away under us. */
    fun pipeClosed() {
        val calls = Calls()
        synchronized(lock) {
            if (done) return
            fail(CoopFrames.ERROR, send == null, calls, tell = false)
        }
        calls.run()
    }

    private fun initiatorHandshake(message: ByteArray, calls: Calls) {
        val payload = noise.readMessage(message)
        if (payload == null) return fail(CoopFrames.ERROR, true, calls, tell = false)
        val status = if (payload.size == 1) payload[0].toInt() and 0xFF else CoopFrames.ERROR
        if (status != 0) return fail(status, true, calls, tell = false)
        finish(calls)
    }

    /**
     * Reads message 1 under the lock. Returns the peer to ask [decide] about (outside the lock, see
     * [answer]), or null when the handshake already ended (bad message, wrong version: answered here).
     */
    private fun responderFirst(message: ByteArray, calls: Calls): CoopPeerId? {
        val payload = noise.readMessage(message)
        if (payload == null) {
            fail(CoopFrames.ERROR, true, calls, tell = false)
            return null
        }
        val who = CoopPeerId.of(noise.remoteStatic!!)
        val version = if (payload.size == 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else -1
        if (version != TRANSPORT_VERSION) {
            refuse(CoopFrames.VERSION, calls)
            return null
        }
        decidePending = true
        return who
    }

    /** Asks [decide] with the lock released (it may take the owner's lock), then answers message 1. */
    private fun answer(who: CoopPeerId, calls: Calls) {
        val status = try {
            decide(who)
        } catch (e: RuntimeException) {
            coopLog("co-op decide failed: ${e.javaClass.simpleName}")
            CoopFrames.ERROR
        }
        synchronized(lock) {
            if (done) return
            if (status != 0) refuse(status, calls) else {
                pipe(noise.writeMessage(byteArrayOf(0)))
                finish(calls)
            }
        }
    }

    /** Message 2 with a refusal [status], then the channel ends. */
    private fun refuse(status: Int, calls: Calls) {
        pipe(noise.writeMessage(byteArrayOf(status.toByte())))
        fail(status, true, calls, tell = false)
    }

    private fun finish(calls: Calls) {
        val (out, input) = noise.split()
        send = out
        receive = input
        hash = noise.handshakeHash
        val now = clock()
        lastReceived = now
        lastPing = now
        openedAtMs = now
        val who = CoopPeerId.of(noise.remoteStatic!!)
        peer = who
        calls.add { listener.opened(this, who) }
    }

    private fun session(message: ByteArray, calls: Calls) {
        val plain = receive!!.decrypt(message)
        if (plain == null || plain.isEmpty()) return fail(CoopFrames.ERROR, false, calls)
        lastReceived = clock()
        when (plain[0].toInt() and 0xFF) {
            CoopFrames.DATA -> {
                if (plain.size - 1 > CoopFrames.MAX_APP_FRAME) return fail(CoopFrames.OVERFLOW, false, calls)
                if (plain.size == 1) return fail(CoopFrames.ERROR, false, calls)
                val body = plain.copyOfRange(1, plain.size)
                calls.add { listener.frame(this, body) }
            }
            CoopFrames.PING -> if (plain.size != 1) fail(CoopFrames.ERROR, false, calls)
            CoopFrames.CLOSE ->
                if (plain.size == 2) fail(plain[1].toInt() and 0xFF, false, calls, tell = false) else fail(CoopFrames.ERROR, false, calls)
            else -> fail(CoopFrames.ERROR, false, calls)
        }
    }

    private fun tellPeer(code: Int) {
        val cipher = send ?: return
        pipe(cipher.encrypt(byteArrayOf(CoopFrames.CLOSE.toByte(), code.toByte())))
    }

    /** Ends the channel and queues the listener's `closed`; [tell] also sends CLOSE to the peer. */
    private fun fail(code: Int, beforeOpen: Boolean, calls: Calls, tell: Boolean = true) {
        if (tell) tellPeer(code)
        done = true
        calls.add { listener.closed(this, code, beforeOpen) }
    }

    /** Listener calls collected under the lock, run after it is released. */
    private class Calls {
        private val list = ArrayList<() -> Unit>(2)
        fun add(call: () -> Unit) { list += call }
        fun run() = list.forEach {
            try {
                it()
            } catch (e: RuntimeException) {
                coopLog("co-op listener threw ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val PATH_INVITE = 0
        const val PATH_PAIR = 1
        const val PATH_TCP = 2
        private const val TRANSPORT_VERSION = 1
        private val PROLOGUE_TEXT = "PokeDaisy co-op 1".toByteArray(Charsets.US_ASCII)

        /** The handshake prologue: the label, the path byte and (relay rooms) the room id's 32 ASCII characters. */
        fun prologue(path: Int, room: String?): ByteArray {
            require(path in PATH_INVITE..PATH_TCP) { "path: 0..2" }
            if (path == PATH_TCP) require(room == null) { "no room on TCP" }
            else require(room != null && room.length == 32) { "a relay path needs a 32 character room" }
            return PROLOGUE_TEXT + byteArrayOf(path.toByte()) + (room?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0))
        }
    }
}
