package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.coop.CoopAddress
import com.pokedaisy.app.companion.coop.CoopEndpoint
import com.pokedaisy.app.companion.coop.CoopNetwork
import com.pokedaisy.app.companion.coop.CoopRelayPipe
import com.pokedaisy.app.companion.coop.CoopTcpSocket
import com.pokedaisy.app.companion.coop.CoopTcpListener
import com.pokedaisy.app.companion.coop.RelayPipeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Schedules relay routing away from the iOS UI and emulator threads. */
internal class IosCoopNetwork(private val tcp: IosCoopTcpNative?) : CoopNetwork {
    private val relayWorkers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    override fun tickEvery(intervalMs: Long, tick: () -> Unit) {
        ticker = relayWorkers.launch {
            while (isActive) {
                delay(intervalMs)
                tick()
            }
        }
    }

    override fun relayPipe(): CoopRelayPipe = RelayPipeWriter(relayWorkers)
    override fun listen(onAccept: (CoopTcpSocket) -> Unit): CoopTcpListener? {
        val native = tcp?.listen(0) { onAccept(IosCoopTcpSocket(it, relayWorkers)) } ?: return null
        return object : CoopTcpListener {
            override val port get() = native.port
            override fun start() {}
            override fun close() = native.close()
        }
    }

    override fun ticketAddresses(port: Int): List<CoopAddress> =
        tcp?.addresses()?.mapNotNull { it.toEndpoint(port)?.address }.orEmpty()

    override fun connect(address: CoopEndpoint, timeoutMs: Long, connected: (CoopTcpSocket?) -> Unit) {
        val host = tcp ?: return connected(null)
        host.connect(address.hostName(), address.address.port, timeoutMs.toInt()) { socket ->
            connected(socket?.let { IosCoopTcpSocket(it, relayWorkers) })
        }
    }

    override fun close() {
        ticker?.cancel()
        ticker = null
        // The router has sealed its relay writers. Let pending CLOSE frames drain.
    }
}
