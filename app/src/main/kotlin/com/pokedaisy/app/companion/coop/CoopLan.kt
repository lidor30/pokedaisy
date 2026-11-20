package com.pokedaisy.app.companion.coop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * [CoopLan] over Android's NSD: service type `_pdcoop._tcp`, name `pdco-` + the first 12 hex
 * of the key, TXT `k` = the key (64 hex) and `u` = the label (up to 200 UTF-8 bytes).
 * Resolves one service at a time (before API 34 a second resolve fails ALREADY_ACTIVE).
 * Any throwing NSD call is logged and leaves LAN off.
 */
class NsdCoopLan(context: Context) : CoopLan {
    private val manager: NsdManager? = try {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (e: RuntimeException) {
        coopLog("lan: no NSD: ${e.javaClass.simpleName}")
        null
    }
    private val lock = Any()
    private var generation = 0
    private var self: CoopPeerId? = null
    private var port = 0
    private var userData: String? = null
    private var listener: CoopLan.Listener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var resolvingName: String? = null
    private var resolvingLost = false
    private var resolveId = 0
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pokedaisy-coop-lan").apply { isDaemon = true }
    }
    private val keyByName = HashMap<String, CoopPeerId>()

    override fun start(self: CoopPeerId, port: Int, userData: String?, listener: CoopLan.Listener) {
        stop()
        val gen: Int
        synchronized(lock) {
            gen = ++generation
            this.self = self; this.port = port; this.userData = userData; this.listener = listener
        }
        register(gen)
        browse(gen)
    }

    override fun setUserData(userData: String?) {
        val gen: Int
        synchronized(lock) {
            if (listener == null) return
            this.userData = userData
            gen = generation
        }
        unregister()
        register(gen)
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            self = null; listener = null
            queue.clear(); resolving = false; keyByName.clear()
        }
        unregister()
        val d = synchronized(lock) { discovery.also { discovery = null } }
        if (d != null) guard("stop discovery") { manager?.stopServiceDiscovery(d) }
    }

    private fun unregister() {
        val r = synchronized(lock) { registration.also { registration = null } } ?: return
        guard("unregister") { manager?.unregisterService(r) }
    }

    private fun register(gen: Int) {
        val nsd = manager ?: return
        val (me, p, u) = synchronized(lock) {
            if (gen != generation) return
            Triple(self ?: return, port, userData)
        }
        guard("register") {
            val info = NsdServiceInfo().apply {
                serviceName = CoopLanRecord.nameFor(me)
                serviceType = CoopLanRecord.SERVICE_TYPE
                setPort(p)
                for ((k, v) in CoopLanRecord.encode(me, u)) setAttribute(k, v)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = coopLog("lan: register failed $errorCode")
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = coopLog("lan: unregister failed $errorCode")
            }
            synchronized(lock) { if (gen != generation) return; registration = l }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        }
    }

    private fun browse(gen: Int) {
        val nsd = manager ?: return
        guard("browse") {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = coopLog("lan: discovery failed $errorCode")
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = coopLog("lan: stop discovery failed $errorCode")
                override fun onServiceFound(info: NsdServiceInfo) {
                    guard("service found") { enqueue(gen, info) }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    guard("service lost") {
                        val lost = synchronized(lock) {
                            if (gen != generation) return
                            queue.removeAll { it.serviceName == info.serviceName }
                            if (resolving && resolvingName == info.serviceName) resolvingLost = true
                            val peer = keyByName.remove(info.serviceName)
                            val target = listener
                            if (peer != null && target != null) peer to target else null
                        }
                        lost?.let { (peer, target) -> notify("lost") { target.lost(peer) } }
                    }
                }
            }
            synchronized(lock) { if (gen != generation) return; discovery = l }
            nsd.discoverServices(CoopLanRecord.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        }
    }

    private fun enqueue(gen: Int, info: NsdServiceInfo) {
        synchronized(lock) {
            if (gen != generation) return
            val own = self?.let { CoopLanRecord.nameFor(it) }
            if (info.serviceName == own || !info.serviceName.startsWith(CoopLanRecord.NAME_PREFIX)) return
            queue.add(info)
        }
        next(gen)
    }

    /** Starts the next resolve when none runs. */
    private fun next(gen: Int) {
        val nsd = manager ?: return
        val info: NsdServiceInfo
        val id: Int
        synchronized(lock) {
            if (gen != generation || resolving) return
            info = queue.poll() ?: return
            resolving = true
            resolvingName = info.serviceName
            resolvingLost = false
            id = ++resolveId
        }
        watchdog.schedule({ done(gen, id, "timed out") }, RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val started = guard("resolve") {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    done(gen, id, "failed $errorCode")
                }

                @Suppress("DEPRECATION")
                override fun onServiceResolved(info: NsdServiceInfo) {
                    guard("resolved") {
                        val parsed = CoopLanRecord.decode(info.attributes ?: emptyMap())
                        val host = info.host
                        var target: CoopLan.Listener? = null
                        synchronized(lock) {
                            // a stale (timed out) answer or a service lost meanwhile is dropped
                            if (gen == generation && id == resolveId && !resolvingLost) target = listener
                        }
                        val t = target
                        if (parsed != null && host != null && info.port in 1..65_535 && t != null) {
                            val (peer, u) = parsed
                            if (peer != synchronized(lock) { self }) {
                                synchronized(lock) { keyByName[info.serviceName] = peer }
                                notify("found") { t.found(peer, u, listOf(InetSocketAddress(host, info.port).toCoopEndpoint())) }
                            }
                        }
                    }
                    done(gen, id, null)
                }
            })
        }
        if (!started) done(gen, id, null)
    }

    private fun done(gen: Int, id: Int, problem: String?) {
        synchronized(lock) {
            if (gen != generation || id != resolveId || !resolving) return
            resolving = false
        }
        if (problem != null) coopLog("lan: resolve $problem")
        next(gen)
    }

    /** Calls the listener; its exceptions are logged and never turn LAN off. */
    private inline fun notify(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            coopLog("lan: listener $what threw: ${e.javaClass.simpleName}")
        }
    }

    /** Runs an NSD [block]; a throw is logged, LAN goes off (except while stopping), and false comes back. */
    private inline fun guard(what: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (e: RuntimeException) {
        coopLog("lan: $what failed: ${e.javaClass.simpleName}")
        if (what != "stop discovery" && what != "unregister") {
            synchronized(lock) { generation++; self = null; listener = null; queue.clear(); resolving = false }
        }
        false
    }

    private companion object {
        private const val RESOLVE_TIMEOUT_MS = 10_000L
    }
}
