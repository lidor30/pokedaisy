package com.pokedaisy.app.companion.coop

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

/** Opens the relay's lobby socket (CoopLobby); callbacks never come inline, from inside [openLobby]. */
interface CoopLobbyConnector {
    /** The lobby is `<relayUrl>/v1/lobby`. */
    fun openLobby(relayUrl: String, listener: CoopRelaySocket.Listener): CoopRelaySocket
}
