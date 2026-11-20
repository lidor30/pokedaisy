@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.coop.CoopAddress
import com.pokedaisy.app.companion.coop.CoopEndpoint
import com.pokedaisy.app.companion.coop.CoopFrames
import com.pokedaisy.app.companion.coop.CoopPipe
import com.pokedaisy.app.companion.coop.CoopTcpSocket
import kotlin.coroutines.resume
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import platform.Foundation.NSData

/** The minimum raw TCP operations provided by Apple's network stack. The shared router owns peer policy. */
interface IosCoopTcpNative {
    fun listen(preferredPort: Int, incoming: (IosCoopTcpConnection) -> Unit): IosCoopTcpListening?
    fun connect(host: String, port: Int, timeoutMs: Int, connected: (IosCoopTcpConnection?) -> Unit)
    fun addresses(): List<IosCoopLocalAddress>
}

class IosCoopLocalAddress(val ip: NSData, val interfaceIndex: Int, val host: String)

interface IosCoopTcpConnection {
    fun start(receive: (NSData) -> Unit, closed: () -> Unit)
    fun send(bytes: NSData, complete: (Boolean) -> Unit)
    fun abort()
}

interface IosCoopTcpListening {
    val port: Int
    fun close()
}

internal fun IosCoopLocalAddress.toEndpoint(port: Int): CoopEndpoint? = ip.copyBytes()
    ?.takeIf { it.size == 4 || it.size == 16 }
    ?.let { address -> CoopEndpoint(CoopAddress(address, port), interfaceIndex) }

internal fun CoopEndpoint.hostName(): String {
    val ip = address.ip
    if (ip.size == 4) return ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
    val host = ip.asList().chunked(2).joinToString(":") {
        ((it[0].toInt() and 0xFF) shl 8 or (it[1].toInt() and 0xFF)).toString(16)
    }
    return if (interfaceIndex == 0) host else "$host%$interfaceIndex"
}

/** Length-prefixed encrypted frames over arbitrary chunks of native TCP bytes. */
internal class IosCoopTcpSocket(
    private val socket: IosCoopTcpConnection,
    scope: CoroutineScope,
) : CoopTcpSocket {
    private val received = Buffer()
    private var size = -1
    private val writerImpl = Writer(scope)
    override val writer: CoopPipe get() = writerImpl

    override fun start(onMessage: (ByteArray) -> Unit, onClosed: () -> Unit) {
        socket.start(
            receive = { chunk ->
                chunk.copyBytes()?.let { received.write(it) }
                while (true) {
                    if (size < 0) {
                        if (received.size < 4) break
                        size = received.readInt()
                        if (size !in 0..CoopFrames.MAX_TCP_FRAME) {
                            abort()
                            break
                        }
                    }
                    if (received.size < size) break
                    val message = received.readByteArray(size.toLong())
                    size = -1
                    onMessage(message)
                }
            },
            closed = {
                writerImpl.closeNow()
                onClosed()
            },
        )
    }

    override fun abort() = writerImpl.closeNow()

    private inner class Writer(scope: CoroutineScope) : CoopPipe {
        private val lock = SynchronizedObject()
        private var pending = 0
        private val frames = Channel<ByteArray>(CoopPipe.CAPACITY)
        private val task = scope.launch {
            try {
                for (frame in frames) {
                    synchronized(lock) { pending-- }
                    val packet = Buffer().also { CoopFrames.writeTcp(it, frame) }.readByteArray()
                    val written = suspendCancellableCoroutine { result ->
                        socket.send(packet.nativeData()) { result.resume(it) }
                    }
                    if (!written) break
                }
            } finally {
                frames.cancel()
                socket.abort()
            }
        }

        override val backlog: Int get() = synchronized(lock) { pending }

        override fun offer(bytes: ByteArray): Boolean = synchronized(lock) {
            if (!frames.trySend(bytes).isSuccess) return@synchronized false
            pending++
            true
        }

        override fun closeSoon() { frames.close() }

        override fun closeNow() {
            frames.cancel()
            task.cancel()
            socket.abort()
        }
    }
}
