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

/** [CoopRelayConnector] and [CoopLobbyConnector] over OkHttp: `https` goes out as `wss`, `http` as `ws`, binary messages only. */
class OkHttpRelayConnector(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build(),
) : CoopRelayConnector, CoopLobbyConnector {
    override fun open(relayUrl: String, room: String, listener: CoopRelaySocket.Listener): CoopRelaySocket =
        connect(relayUrl, "/v1/rooms/$room", listener)

    override fun openLobby(relayUrl: String, listener: CoopRelaySocket.Listener): CoopRelaySocket =
        connect(relayUrl, "/v1/lobby", listener)

    private fun connect(relayUrl: String, path: String, listener: CoopRelaySocket.Listener): CoopRelaySocket {
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
            Request.Builder().url("$ws$path").build(),
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
