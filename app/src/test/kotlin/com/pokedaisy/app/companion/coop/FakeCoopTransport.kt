package com.pokedaisy.app.companion.coop

/** Fakes wired to each other in memory; [pump] hands every queued event to its node's [FakeCoopTransport.sink]. */
class FakeNet {
    val nodes = mutableListOf<FakeCoopTransport>()
    val byTicket = HashMap<String, FakeCoopTransport>()

    fun node(idByte: Int, ticket: String): FakeCoopTransport =
        FakeCoopTransport(this, CoopPeerId.of(ByteArray(32) { idByte.toByte() }), ticket).also {
            nodes += it
            byTicket[ticket] = it
        }

    fun find(id: CoopPeerId) = nodes.lastOrNull { it.id == id }

    fun pump(maxRounds: Int = 500) {
        repeat(maxRounds) {
            var any = false
            for (n in nodes) {
                while (true) {
                    val e = n.poll() ?: break
                    n.sink?.invoke(e)
                    any = true
                }
            }
            if (!any) return
        }
        error("events never settled")
    }
}

data class FakePolicy(val allowed: Set<CoopPeerId>, val pairingOpen: Boolean, val lanUserData: String?)

class FakeCoopTransport(private val net: FakeNet, val id: CoopPeerId, private val ownTicket: String) : CoopTransport {
    var sink: ((CoopEvent) -> Unit)? = null
    private val inbox = ArrayDeque<CoopEvent>()

    /** peer -> pending at this side (a pending peer can't be sent to). */
    private val conns = HashMap<CoopPeerId, Boolean>()

    var allowed: Set<CoopPeerId> = emptySet()
        private set
    var pairingOpen = false
        private set
    val policies = mutableListOf<FakePolicy>()
    val accepted = mutableListOf<CoopPeerId>()
    val denied = mutableListOf<CoopPeerId>()
    val disconnected = mutableListOf<CoopPeerId>()
    val sent = mutableListOf<Pair<CoopPeerId, CoopMessage>>()

    fun inject(e: CoopEvent) {
        inbox.addLast(e)
    }

    override fun start(secretKey: ByteArray, relayUrl: String?, relayOnly: Boolean) = 0
    override fun endpointId(): CoopPeerId = id
    override fun ticket(): String = ownTicket

    override fun setPolicy(allowed: Set<CoopPeerId>, pairingOpen: Boolean, lanUserData: String?) {
        this.allowed = allowed
        this.pairingOpen = pairingOpen
        policies += FakePolicy(allowed, pairingOpen, lanUserData)
    }

    override fun connect(ticket: String): Int {
        val target = net.byTicket[ticket] ?: return 0
        if (id !in target.allowed && !target.pairingOpen) {
            inbox.addLast(CoopEvent.DialFailed(4, "not allowed"))
            return 0
        }
        conns[target.id] = false
        val pending = id !in target.allowed
        target.conns[id] = pending
        inbox.addLast(CoopEvent.Connected(target.id, pending = false, outgoing = true, direct = true))
        target.inbox.addLast(CoopEvent.Connected(id, pending = pending, outgoing = false, direct = true))
        return 0
    }

    override fun accept(peer: CoopPeerId) {
        accepted += peer
        if (peer in conns) conns[peer] = false
    }

    override fun deny(peer: CoopPeerId) {
        denied += peer
        close(peer, 2)
    }

    override fun disconnect(peer: CoopPeerId) {
        disconnected += peer
        close(peer, 0)
    }

    private fun close(peer: CoopPeerId, code: Int) {
        if (conns.remove(peer) == null) return
        inbox.addLast(CoopEvent.Disconnected(peer, code))   // native close_peer(report = true): a local close is reported to us too
        net.find(peer)?.let {
            it.conns.remove(id)
            it.inbox.addLast(CoopEvent.Disconnected(id, code))
        }
    }

    override fun send(peer: CoopPeerId, frame: ByteArray): Boolean {
        val pending = conns[peer] ?: return false
        if (pending) return false
        val target = net.find(peer) ?: return false
        if (id !in target.conns) return false
        target.inbox.addLast(CoopEvent.Frame(id, frame))
        CoopWire.decode(frame)?.let { sent += peer to it }
        return true
    }

    override fun poll(): CoopEvent? = inbox.removeFirstOrNull()

    override fun close() = Unit
}

/** A real peer-only ticket string for a key of [b] bytes, for fixtures that need a valid one. */
internal fun ticketOf(b: Int): String = CoopTicket.peerOnly(CoopPeerId.of(ByteArray(32) { b.toByte() }))
