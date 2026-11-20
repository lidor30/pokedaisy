package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.coop.CoopLan
import com.pokedaisy.app.companion.coop.CoopLanRecord
import com.pokedaisy.app.companion.coop.CoopPeerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import platform.Foundation.NSData

interface IosCoopDiscovery {
    fun start(name: String, type: String, port: Int, attributes: Map<String, String>, observer: IosCoopDiscoveryObserver)
    fun setAttributes(attributes: Map<String, String>)
    fun stop()
}

interface IosCoopDiscoveryObserver {
    fun found(service: String, port: Int, attributes: Map<String, NSData>, addresses: List<IosCoopLocalAddress>)
    fun lost(service: String)
}

internal class IosCoopLan(private val discovery: IosCoopDiscovery) : CoopLan {
    private val lock = SynchronizedObject()
    private var generation = 0
    private var self: CoopPeerId? = null

    override fun start(self: CoopPeerId, port: Int, userData: String?, listener: CoopLan.Listener) {
        val opened = synchronized(lock) {
            this.self = self
            ++generation
        }
        val byService = mutableMapOf<String, CoopPeerId>() // discovery callbacks run on the main thread
        fun current() = synchronized(lock) { opened == generation }
        discovery.start(CoopLanRecord.nameFor(self), CoopLanRecord.SERVICE_TYPE, port, CoopLanRecord.encode(self, userData),
            object : IosCoopDiscoveryObserver {
                override fun found(service: String, port: Int, attributes: Map<String, NSData>, addresses: List<IosCoopLocalAddress>) {
                    if (!current() || port !in 1..65_535) return
                    val (peer, label) = CoopLanRecord.decode(attributes.mapValues { it.value.copyBytes() }) ?: return
                    if (peer == self) return
                    val endpoints = addresses.mapNotNull { it.toEndpoint(port) }.distinct()
                    if (endpoints.isEmpty()) return
                    val previous = byService.put(service, peer)
                    if (previous != null && previous != peer && previous !in byService.values) listener.lost(previous)
                    listener.found(peer, label, endpoints)
                }

                override fun lost(service: String) {
                    if (!current()) return
                    val peer = byService.remove(service) ?: return
                    if (peer !in byService.values) listener.lost(peer)
                }
            },
        )
    }

    override fun setUserData(userData: String?) {
        val key = synchronized(lock) { self } ?: return
        discovery.setAttributes(CoopLanRecord.encode(key, userData))
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            self = null
        }
        discovery.stop()
    }
}
