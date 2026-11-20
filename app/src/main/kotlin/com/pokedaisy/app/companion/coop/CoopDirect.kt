package com.pokedaisy.app.companion.coop

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okio.buffer
import okio.sink
import okio.source

/**
 * The single writer of one pipe (a relay socket or a TCP socket): [offer] only enqueues, a pool thread
 * does [write]. False from [offer] means the queue is full or the pipe is closed. [closeSoon] lets what is
 * queued go first, then runs [onEnd]; [closeNow] drops it. [ready] holds the first write back until a
 * relay socket handed over by an asynchronous open exists.
 */
internal class PipeWriter(
    private val pool: Executor,
    private val ready: CountDownLatch? = null,
    private val write: (ByteArray) -> Boolean,
    private val onEnd: () -> Unit,
) : CoopPipe {
    private val queue = LinkedBlockingQueue<ByteArray>(CoopPipe.CAPACITY)

    @Volatile private var closed = false

    /** Messages waiting to be written. */
    override val backlog: Int get() = queue.size

    fun start() = pool.execute(::run)

    override fun offer(bytes: ByteArray): Boolean = !closed && queue.offer(bytes)

    override fun closeSoon() {
        closed = true
        if (!queue.offer(END)) closeNow()
    }

    override fun closeNow() {
        closed = true
        queue.clear()
        queue.offer(END)
    }

    private fun run() {
        try {
            if (ready != null && !ready.await(READY_WAIT_MS, TimeUnit.MILLISECONDS)) closed = true
            while (!closed || queue.isNotEmpty()) {
                val next = queue.poll(1, TimeUnit.SECONDS) ?: continue
                if (next === END) break
                if (!write(next)) {
                    closed = true
                    break
                }
            }
        } catch (_: InterruptedException) {
            closed = true
        } finally {
            closed = true
            try {
                onEnd()
            } catch (e: RuntimeException) {
                coopLog("co-op pipe end threw ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val READY_WAIT_MS = 5_000L
        private val END = ByteArray(0)
    }
}

/** A connected TCP socket with a reader thread (whole frames to [onMessage]) and a [writer]. [onClosed] fires once when the socket ends. */
internal class DirectSocket(
    private val socket: Socket,
    private val pool: Executor,
) : CoopTcpSocket {
    private val output = socket.sink().buffer()
    private val input = socket.source().buffer()

    override val writer = PipeWriter(
        pool,
        write = { bytes ->
            try {
                CoopFrames.writeTcp(output, bytes)
                true
            } catch (_: IOException) {
                false
            }
        },
        onEnd = { closeSocket() },
    )

    override fun start(onMessage: (ByteArray) -> Unit, onClosed: () -> Unit) {
        writer.start()
        pool.execute {
            try {
                while (true) onMessage(CoopFrames.readTcp(input))
            } catch (_: IOException) {
            } catch (e: RuntimeException) {
                coopLog("co-op tcp reader threw ${e.javaClass.simpleName}")
            } finally {
                writer.closeNow()
                closeSocket()
                onClosed()
            }
        }
    }

    /** Closes the socket now, ending a write blocked on a peer that stopped reading; the writer and reader then end. */
    override fun abort() {
        writer.closeNow()
        closeSocket()
    }

    private fun closeSocket() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** A TCP connection to [address], or null when it can't be made within [timeoutMs]. */
        fun connect(address: InetSocketAddress, timeoutMs: Long): Socket? {
            val s = Socket()
            return try {
                s.tcpNoDelay = true
                s.connect(address, timeoutMs.toInt().coerceAtLeast(1))
                s
            } catch (_: IOException) {
                try {
                    s.close()
                } catch (_: IOException) {
                }
                null
            }
        }
    }
}

/** The TCP listener: an accept thread that hands each socket to [onAccept]. Binds loopback only when asked (tests). */
internal class DirectListener(bindLoopback: Boolean, private val pool: Executor, private val onAccept: (Socket) -> Unit) : CoopTcpListener {
    private val server = ServerSocket(0, 16, if (bindLoopback) InetAddress.getLoopbackAddress() else null)

    override val port: Int get() = server.localPort

    override fun start() {
        pool.execute {
            try {
                while (true) {
                    val s = server.accept()
                    try {
                        s.tcpNoDelay = true
                        onAccept(s)
                    } catch (e: RuntimeException) {
                        coopLog("co-op accept threw ${e.javaClass.simpleName}")
                        try {
                            s.close()
                        } catch (_: IOException) {
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }
    }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
    }
}
