package com.pokedaisy.app.companion.coop

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** An ordered, bounded writer for a relay socket that can attach after the first offer.
 * The scope dispatches sends and socket close on a worker, since either can call a host API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayPipeWriter internal constructor(scope: CoroutineScope, attachTimeoutMs: Long) : CoopRelayPipe {
    constructor(scope: CoroutineScope) : this(scope, 5_000)

    private val lock = SynchronizedObject()
    private val ready = CompletableDeferred<CoopRelaySocket?>()
    private val queue = Channel<ByteArray>(CoopPipe.CAPACITY)
    private var socket: CoopRelaySocket? = null
    private var pending = 0
    private var sealed = false

    // The cleanup must run even if the host cancels the worker before it first runs.
    private val worker = scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            val target = withTimeoutOrNull(attachTimeoutMs) { ready.await() } ?: return@launch
            for (message in queue) {
                synchronized(lock) { pending-- }
                ensureActive()
                if (!target.send(message)) break
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            coopLog("co-op relay send threw ${error::class.simpleName}")
        } finally {
            val target = synchronized(lock) {
                sealed = true
                pending = 0
                socket.also { socket = null }
            }
            queue.cancel()
            try {
                target?.close()
            } catch (error: RuntimeException) {
                coopLog("co-op relay close threw ${error::class.simpleName}")
            }
        }
    }

    override val backlog: Int get() = synchronized(lock) { pending }

    override fun offer(bytes: ByteArray): Boolean = synchronized(lock) {
        if (sealed || !queue.trySend(bytes).isSuccess) return@synchronized false
        pending++
        true
    }

    override fun attach(socket: CoopRelaySocket) {
        val accepted = synchronized(lock) {
            if (sealed || this.socket != null) false else {
                this.socket = socket
                ready.complete(socket)
                true
            }
        }
        if (!accepted) socket.close()
    }

    override fun closeSoon() {
        synchronized(lock) {
            sealed = true
            if (socket == null) ready.complete(null)
            queue.close()
        }
    }

    override fun closeNow() {
        closeSoon()
        worker.cancel()
    }

    internal suspend fun join() = worker.join()
}
