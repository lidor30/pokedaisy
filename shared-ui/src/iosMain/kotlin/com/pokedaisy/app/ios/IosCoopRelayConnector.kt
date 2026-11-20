@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.coop.CoopFrames
import com.pokedaisy.app.companion.coop.CoopLobbyConnector
import com.pokedaisy.app.companion.coop.CoopRelayConnector
import com.pokedaisy.app.companion.coop.CoopRelaySocket
import com.pokedaisy.app.companion.coop.coopRelayEndpoint
import kotlin.coroutines.resume
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketCloseCodeInvalid
import platform.Foundation.NSURLSessionWebSocketCloseCodeNormalClosure
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketTask
import platform.darwin.NSObject

/** Foundation supplies the binary WebSocket, TLS validation and remote close reason. */
internal class IosCoopRelayConnector : CoopRelayConnector, CoopLobbyConnector {
    private val lock = SynchronizedObject()
    private val sockets = HashMap<ULong, Socket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val delegate = object : NSObject(), NSURLSessionWebSocketDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            webSocketTask: NSURLSessionWebSocketTask,
            didOpenWithProtocol: String?,
        ) {
            synchronized(lock) { sockets[webSocketTask.taskIdentifier] }?.opened?.complete(true)
        }

        override fun URLSession(
            session: NSURLSession,
            webSocketTask: NSURLSessionWebSocketTask,
            didCloseWithCode: NSURLSessionWebSocketCloseCode,
            reason: NSData?,
        ) {
            synchronized(lock) { sockets[webSocketTask.taskIdentifier] }?.ended(didCloseWithCode.toInt())
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val code = (task as? NSURLSessionWebSocketTask)?.closeCode
            synchronized(lock) { sockets[task.taskIdentifier] }?.ended(
                if (code == null || code == NSURLSessionWebSocketCloseCodeInvalid) 1006 else code.toInt(),
            )
        }
    }
    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration, delegate, null,
    )

    override fun open(relayUrl: String, room: String, listener: CoopRelaySocket.Listener): CoopRelaySocket =
        connect(coopRelayEndpoint(relayUrl, "/v1/rooms/$room"), listener)

    override fun openLobby(relayUrl: String, listener: CoopRelaySocket.Listener): CoopRelaySocket =
        connect(coopRelayEndpoint(relayUrl, "/v1/lobby"), listener)

    fun close() { session.invalidateAndCancel() }

    private fun connect(endpoint: String, listener: CoopRelaySocket.Listener): CoopRelaySocket {
        val url = requireNotNull(NSURL.URLWithString(endpoint)) { "invalid relay endpoint" }
        val task = session.webSocketTaskWithURL(url)
        task.maximumMessageSize = CoopFrames.MAX_TCP_FRAME.toLong()
        return Socket(task, listener).also { socket ->
            synchronized(lock) { sockets[task.taskIdentifier] = socket }
            task.resume()
            socket.start()
        }
    }

    private inner class Socket(
        private val task: NSURLSessionWebSocketTask,
        private val listener: CoopRelaySocket.Listener,
    ) : CoopRelaySocket {
        val opened = CompletableDeferred<Boolean>()
        private val terminal = CompletableDeferred<Int>()
        private val outbound = Channel<ByteArray>(320)

        fun start() {
            scope.launch {
                var code = 1006
                try {
                    val ready = withTimeoutOrNull(10_000) { opened.await() }
                    if (ready == true) coroutineScope {
                        listener.onOpen()
                        launch { write() }
                        launch { read() }
                        launch { ping() }
                        code = terminal.await()
                        coroutineContext.cancelChildren()
                    } else if (ready == false) {
                        code = terminal.await()
                    }
                } finally {
                    synchronized(lock) { sockets.remove(task.taskIdentifier) }
                    outbound.cancel()
                    task.cancel()
                    listener.onClosed(code)
                }
            }
        }

        fun ended(code: Int) {
            terminal.complete(code)
            opened.complete(false)
        }

        override fun send(bytes: ByteArray): Boolean {
            if (bytes.size > CoopFrames.MAX_TCP_FRAME || terminal.isCompleted) return false
            val result = outbound.trySend(bytes.copyOf())
            if (!result.isSuccess && !result.isClosed) task.cancel()
            return result.isSuccess
        }

        override fun close() {
            if (!outbound.close()) return
            scope.launch {
                if (withTimeoutOrNull(2_000) { terminal.await() } == null) task.cancel()
            }
        }

        private suspend fun write() {
            for (bytes in outbound) {
                val sent = suspendCancellableCoroutine { result ->
                    task.sendMessage(NSURLSessionWebSocketMessage(bytes.nativeData())) { error ->
                        result.resume(error == null)
                    }
                }
                if (!sent) {
                    task.cancel()
                    return
                }
            }
            task.cancelWithCloseCode(NSURLSessionWebSocketCloseCodeNormalClosure, null)
        }

        private suspend fun read() {
            while (!terminal.isCompleted) {
                val message = suspendCancellableCoroutine<NSURLSessionWebSocketMessage?> { result ->
                    task.receiveMessageWithCompletionHandler { value, _ -> result.resume(value) }
                } ?: return
                message.data()?.copyBytes()?.let(listener::onMessage)
            }
        }

        private suspend fun ping() {
            while (!terminal.isCompleted) {
                delay(20_000)
                val answered = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine { result ->
                        task.sendPingWithPongReceiveHandler { error -> result.resume(error == null) }
                    }
                }
                if (answered != true) {
                    task.cancel()
                    return
                }
            }
        }
    }
}
