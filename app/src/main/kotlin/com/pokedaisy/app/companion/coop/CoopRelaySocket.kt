package com.pokedaisy.app.companion.coop

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** One WebSocket to a relay room. [send] queues a message and says false once the socket can't take it. */
interface CoopRelaySocket {
    fun send(bytes: ByteArray): Boolean

    /** Closes it; [Listener.onClosed] may still follow (the owner ignores it). */
    fun close()

    interface Listener {
        fun onOpen()

        /** One whole binary message (byte 0 is the relay's kind). */
        fun onMessage(bytes: ByteArray)

        /** Once, when the socket is gone: the close code, or 1006 for a failure. */
        fun onClosed(code: Int)
    }
}

/** Opens sockets to a relay; the tests' fake is an in-memory relay. */
interface CoopRelayConnector {
    /** [relayUrl] is `https://host[:port]` (or `http://` in tests); the room is `<relayUrl>/v1/rooms/<room>`. */
    fun open(relayUrl: String, room: String, listener: CoopRelaySocket.Listener): CoopRelaySocket
}

/** [CoopRelayConnector] over OkHttp: `https` goes out as `wss`, `http` as `ws`, binary messages only. */
class OkHttpRelayConnector(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build(),
) : CoopRelayConnector {
    override fun open(relayUrl: String, room: String, listener: CoopRelaySocket.Listener): CoopRelaySocket {
        val base = relayUrl.trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> throw IllegalArgumentException("relay url: http(s)://")
        }
        val ended = AtomicBoolean(false)
        fun end(code: Int) {
            if (ended.compareAndSet(false, true)) listener.onClosed(code)
        }
        val socket = client.newWebSocket(
            Request.Builder().url("$ws/v1/rooms/$room").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(bytes.toByteArray())
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    end(code)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(code)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end(1006)
            },
        )
        return object : CoopRelaySocket {
            override fun send(bytes: ByteArray): Boolean = socket.send(bytes.toByteString())
            override fun close() {
                if (!socket.close(1000, null)) socket.cancel()
            }
        }
    }
}
