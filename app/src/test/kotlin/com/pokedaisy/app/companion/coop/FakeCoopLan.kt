package com.pokedaisy.app.companion.coop

import java.net.InetSocketAddress

/** An in-memory "Wi-Fi" for [FakeCoopLan]s: whoever is started sees everyone else started. */
class FakeLanNetwork {
    internal val lans = mutableListOf<FakeCoopLan>()
}

/** A [CoopLan] on a [FakeLanNetwork], for JVM tests; every address is 127.0.0.1 and the advertised port. */
class FakeCoopLan(private val network: FakeLanNetwork = FakeLanNetwork()) : CoopLan {
    private var self: CoopPeerId? = null
    private var port = 0
    private var userData = ""
    private var listener: CoopLan.Listener? = null

    val isStarted: Boolean get() = listener != null

    @Synchronized override fun start(self: CoopPeerId, port: Int, userData: String?, listener: CoopLan.Listener) {
        stop()
        this.self = self
        this.port = port
        this.userData = userData ?: ""
        this.listener = listener
        val others = synchronized(network) { network.lans.toList().also { network.lans.add(this) } }
        for (o in others) {
            o.advertisement()?.let { (p, u, a) -> listener.found(p, u, a) }
            advertisement()?.let { (p, u, a) -> o.listener?.found(p, u, a) }
        }
    }

    @Synchronized override fun setUserData(userData: String?) {
        this.userData = userData ?: ""
        if (listener == null) return
        val ad = advertisement() ?: return
        for (o in synchronized(network) { network.lans.toList() }) if (o !== this) o.listener?.found(ad.first, ad.second, ad.third)
    }

    @Synchronized override fun stop() {
        val me = self
        val had = listener != null
        listener = null
        self = null
        if (!had || me == null) return
        for (o in synchronized(network) { network.lans.remove(this); network.lans.toList() }) o.listener?.lost(me)
    }

    private fun advertisement(): Triple<CoopPeerId, String, List<InetSocketAddress>>? {
        val me = self ?: return null
        return Triple(me, userData, listOf(InetSocketAddress("127.0.0.1", port)))
    }
}
