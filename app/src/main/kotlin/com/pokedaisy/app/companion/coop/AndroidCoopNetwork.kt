package com.pokedaisy.app.companion.coop

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal class AndroidCoopNetwork(
    private val bindLoopback: Boolean,
    private val addresses: () -> List<InetAddress> = {
        if (bindLoopback) listOf(InetAddress.getLoopbackAddress()) else siteLocalAddresses()
    },
) : CoopNetwork {
    private val io = Executors.newCachedThreadPool(daemon("pokedaisy-coop-io"))
    private val worker = Executors.newSingleThreadScheduledExecutor(daemon("pokedaisy-coop-net"))
    private val relayJob = SupervisorJob()
    private val relayWorkers = CoroutineScope(relayJob + Dispatchers.IO)

    override fun tickEvery(intervalMs: Long, tick: () -> Unit) {
        worker.scheduleWithFixedDelay(tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    override fun relayPipe(): CoopRelayPipe = RelayPipeWriter(relayWorkers)

    override fun listen(onAccept: (CoopTcpSocket) -> Unit): CoopTcpListener? = try {
        DirectListener(bindLoopback, io) { onAccept(DirectSocket(it, io)) }
    } catch (_: IOException) {
        null
    }

    override fun ticketAddresses(port: Int): List<CoopAddress> =
        addresses().distinct().map { CoopAddress(it.address, port) }

    override fun connect(address: CoopEndpoint, timeoutMs: Long, connected: (CoopTcpSocket?) -> Unit) {
        val ip = address.address.ip
        val host = if (ip.size == 16 && address.interfaceIndex != 0) {
            Inet6Address.getByAddress(null, ip, address.interfaceIndex)
        } else {
            InetAddress.getByAddress(ip)
        }
        io.execute {
            connected(DirectSocket.connect(InetSocketAddress(host, address.address.port), timeoutMs)?.let { DirectSocket(it, io) })
        }
    }

    override fun close() {
        worker.shutdownNow()
        io.shutdown()
        try {
            worker.awaitTermination(500, TimeUnit.MILLISECONDS)
            val shutdownMs = 1_500L
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownMs)
            // The router already sealed the relay queues. Wait for their CLOSE frames before cancelling.
            runBlocking { withTimeoutOrNull(shutdownMs) { relayJob.children.toList().joinAll() } }
            io.awaitTermination((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } finally {
            relayJob.cancel()
        }
    }

    private companion object {
        fun daemon(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

        /** Site-local IPv4 and unique-local IPv6 addresses of interfaces that are up. */
        fun siteLocalAddresses(): List<InetAddress> = try {
            (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { address ->
                    !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isMulticastAddress &&
                        !address.isAnyLocalAddress && (address is Inet4Address && address.isSiteLocalAddress ||
                        address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)
                }
                .sortedBy { if (it is Inet4Address) 0 else 1 }
        } catch (_: IOException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }
}

internal fun InetSocketAddress.toCoopEndpoint(): CoopEndpoint =
    CoopEndpoint(CoopAddress(address.address, port), (address as? Inet6Address)?.scopeId ?: 0)

internal fun androidCoopTransport(
    connector: CoopRelayConnector = OkHttpRelayConnector(),
    lan: CoopLan = NoCoopLan,
    timings: CoopTimings = CoopTimings(),
    clock: () -> Long = System::currentTimeMillis,
    bindLoopback: Boolean = false,
): RelayCoopTransport = RelayCoopTransport(connector, JvmCoopCrypto, { AndroidCoopNetwork(bindLoopback) }, lan, timings, clock)
