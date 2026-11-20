package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.coop.COOP_IP_PORT
import com.pokedaisy.app.companion.coop.CoopInvite
import com.pokedaisy.app.companion.coop.CoopManualInvite
import com.pokedaisy.app.companion.coop.parseHostPort
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import okio.IOException

/** The same small manual-IP exchange Android uses, over the platform TCP adapter. */
internal class IosCoopIp(private val tcp: () -> IosCoopTcpNative?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private var generation = 0
    private var host: IosCoopTcpListening? = null
    private var serving: IosCoopTcpConnection? = null
    private var lastAnswer = TimeSource.Monotonic.markNow() - 1.seconds

    fun start(link: () -> String?, onAddress: (String?) -> Unit) {
        stop()
        val run = synchronized(lock) { generation }
        scope.launch {
            val native = tcp() ?: return@launch onAddress(null)
            val incoming: (IosCoopTcpConnection) -> Unit = { serve(run, it, link) }
            val listening = native.listen(COOP_IP_PORT, incoming) ?: native.listen(0, incoming)
            synchronized(lock) {
                if (run != generation) {
                    listening?.close()
                    return@launch
                }
                host = listening
            }
            val address = native.addresses().firstOrNull()?.host?.let { if (':' in it) "[$it]" else it }
            onAddress(if (address != null && listening != null) "$address:${listening.port}" else null)
        }
    }

    fun stop() {
        val (listener, connection) = synchronized(lock) {
            generation++
            val active = host to serving
            host = null
            serving = null
            active
        }
        listener?.close()
        connection?.abort()
    }

    private fun serve(run: Int, socket: IosCoopTcpConnection, link: () -> String?) {
        val accepted = synchronized(lock) {
            if (run != generation || serving != null) false
            else {
                serving = socket
                true
            }
        }
        if (!accepted) {
            socket.abort()
            return
        }
        scope.launch {
            try {
                withTimeoutOrNull(3_000) {
                    val request = line(socket, CoopManualInvite.MAX_GREETING).await() ?: return@withTimeoutOrNull
                    if (!request.contentEquals(CoopManualInvite.greeting)) return@withTimeoutOrNull
                    val answer = synchronized(lock) {
                        if (run != generation || lastAnswer.elapsedNow().inWholeMilliseconds < 500) null
                        else link()?.also { lastAnswer = TimeSource.Monotonic.markNow() }
                    } ?: return@withTimeoutOrNull
                    send(socket, (answer + "\n").encodeToByteArray())
                }
            } finally {
                synchronized(lock) { if (serving === socket) serving = null }
                socket.abort()
            }
        }
    }

    fun fetch(text: String, result: (Result<String>) -> Unit) {
        val (host, port) = parseHostPort(text) ?: return result(Result.failure(IllegalArgumentException()))
        scope.launch {
            val invite = withTimeoutOrNull(5_000) {
                val native = tcp() ?: return@withTimeoutOrNull null
                val socket = suspendCancellableCoroutine<IosCoopTcpConnection?> { waiting ->
                    native.connect(host, port, 5_000) { connection ->
                        if (waiting.isActive) waiting.resume(connection) else connection?.abort()
                    }
                } ?: return@withTimeoutOrNull null
                try {
                    val reply = line(socket, CoopManualInvite.MAX_REPLY)
                    if (!send(socket, CoopManualInvite.greeting)) return@withTimeoutOrNull null
                    reply.await()?.decodeToString()?.let(CoopInvite::parse)
                } finally {
                    socket.abort()
                }
            }
            result(if (invite == null) Result.failure(IOException()) else Result.success(invite))
        }
    }

    private fun line(socket: IosCoopTcpConnection, cap: Int): CompletableDeferred<ByteArray?> {
        val answer = CompletableDeferred<ByteArray?>()
        val buffer = Buffer()
        socket.start(
            receive = { chunk ->
                val bytes = chunk.copyBytes()
                if (bytes != null && !answer.isCompleted) {
                    val end = bytes.indexOf('\n'.code.toByte())
                    val count = if (end >= 0) end + 1 else bytes.size
                    if (buffer.size + count > cap) answer.complete(null)
                    else {
                        buffer.write(bytes, 0, count)
                        if (end >= 0) answer.complete(buffer.readByteArray())
                    }
                }
            },
            closed = { answer.complete(null) },
        )
        return answer
    }

    private suspend fun send(socket: IosCoopTcpConnection, data: ByteArray): Boolean =
        suspendCancellableCoroutine { reply -> socket.send(data.nativeData()) { reply.resume(it) } }
}
