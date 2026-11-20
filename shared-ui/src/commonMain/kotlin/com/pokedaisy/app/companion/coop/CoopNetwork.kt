package com.pokedaisy.app.companion.coop

/** OS resources used by one start/close cycle of the shared co-op router. */
interface CoopNetwork {
    fun tickEvery(intervalMs: Long, tick: () -> Unit)
    fun relayPipe(): CoopRelayPipe
    fun listen(onAccept: (CoopTcpSocket) -> Unit): CoopTcpListener?
    fun ticketAddresses(port: Int): List<CoopAddress>
    fun connect(address: CoopEndpoint, timeoutMs: Long, connected: (CoopTcpSocket?) -> Unit)
    fun close()
}

/** A discovered IPv6 endpoint may carry a local interface index that is never put in a ticket. */
data class CoopEndpoint(val address: CoopAddress, val interfaceIndex: Int = 0)

interface CoopPipe {
    val backlog: Int
    fun offer(bytes: ByteArray): Boolean
    fun closeSoon()
    fun closeNow()

    companion object {
        /** Free slots above this app-frame limit carry handshake, pings and CLOSE. */
        const val APP_BACKLOG = 256
        const val CAPACITY = 320
    }
}

/** Accepts queued messages before the asynchronous connector returns its socket. */
interface CoopRelayPipe : CoopPipe {
    fun attach(socket: CoopRelaySocket)
}

interface CoopTcpSocket {
    val writer: CoopPipe
    fun start(onMessage: (ByteArray) -> Unit, onClosed: () -> Unit)
    fun abort()
}

interface CoopTcpListener {
    val port: Int
    fun start()
    fun close()
}

/** LAN discovery supplies addresses. The shared router chooses which ones to dial. */
interface CoopLan {
    interface Listener {
        fun found(peer: CoopPeerId, userData: String, addresses: List<CoopEndpoint>)
        fun lost(peer: CoopPeerId)
    }

    fun start(self: CoopPeerId, port: Int, userData: String?, listener: Listener)
    fun setUserData(userData: String?)
    fun stop()
}

object NoCoopLan : CoopLan {
    override fun start(self: CoopPeerId, port: Int, userData: String?, listener: CoopLan.Listener) {}
    override fun setUserData(userData: String?) {}
    override fun stop() {}
}
