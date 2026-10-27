package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.data.Gen3Text
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pairing two devices: one opens (shows its ticket and its LAN entry), the other joins; both
 * check each other's HELLO and are asked ACCEPT THIS PLAYER?; only when both said yes is the
 * pair stored. No threads: the session calls [onEvent] and [tick].
 *
 * A host sees its joiner as a pending connection, which the native side lets receive only a few
 * small frames and never send; so once the joiner's HELLO checks out the host lifts the caps
 * ([CoopTransport.accept]) and answers with its own HELLO, or the joiner could never show its prompt.
 */
class CoopPairing(
    private val transport: CoopTransport,
    private val pairs: CoopPairs,
    private val ownHello: () -> CoopHello?,
    private val clock: () -> Long,
) {
    sealed interface Step {
        data class Inviting(val ticket: String?, val lan: List<LanEntry>) : Step
        data class Connecting(val viaLan: Boolean) : Step
        data class Confirm(val partner: CoopPartner) : Step
        data class WaitingForPartner(val partner: CoopPartner) : Step
        data class Paired(val pair: CoopPair) : Step
        data class Failed(val reason: CoopPairFail) : Step
    }

    data class LanEntry(val peer: CoopPeerId, val label: String)

    enum class CoopPairFail { VERSION, DIFFERENT_ROM, SAVE_ALREADY_PAIRED, DENIED, NETWORK, TIMEOUT, BAD_INVITE }

    private val _step = MutableStateFlow<Step>(Step.Inviting(null, emptyList()))
    val step: StateFlow<Step> = _step.asStateFlow()

    private var ownSave: CoopSaveId? = null
    private var opened = false
    private var joining = false
    private var peer: CoopPeerId? = null
    private var peerPending = false
    private var theirHello: CoopHello? = null
    private var partner: CoopPartner? = null
    private var iAccepted = false
    private var theyAccepted = false
    private class Candidate(val pending: Boolean, val deadline: Long)
    private val candidates = LinkedHashMap<CoopPeerId, Candidate>()
    private var confirmDeadline = 0L
    private var ticket: String? = null
    private val lan = LinkedHashMap<CoopPeerId, String>()

    /** Starts pairing for [save]: allows every paired peer, opens pairing and announces this device on the LAN. */
    @Synchronized
    fun open(save: CoopSaveId) {
        // A repeat call (TRY AGAIN): the old attempt's connection goes with its state.
        peer?.takeIf { _step.value !is Step.Paired }?.let { transport.disconnect(it) }
        resetAll()
        if (pairs.forSave(save) != null) {
            _step.value = Step.Failed(CoopPairFail.SAVE_ALREADY_PAIRED)
            return
        }
        ownSave = save
        opened = true
        ticket = transport.ticket()
        transport.setPolicy(pairs.allowedPeers(), true, lanUserData(save))
        _step.value = inviting()
    }

    /** Dials the host's invite ([ticket] or a `pokedaisy://coop/` link); [open] came first. */
    @Synchronized
    fun join(ticket: String) {
        val t = CoopInvite.parse(ticket)
        if (t == null) {
            fail(CoopPairFail.BAD_INVITE)
            return
        }
        dial(t, viaLan = false)
    }

    /** Dials a device found on the LAN by its key: a ticket with no addresses, which the LAN lookup fills in. */
    @Synchronized
    fun joinLan(peer: CoopPeerId) {
        dial(peerOnlyTicket(peer), viaLan = true)
    }

    @Synchronized
    fun accept() {
        val p = partner ?: return
        val to = peer ?: return
        if (_step.value !is Step.Confirm) return
        iAccepted = true
        transport.send(to, CoopWire.encode(CoopWire.PAIR_ACCEPT, ByteArray(0)))
        _step.value = Step.WaitingForPartner(p)
        if (theyAccepted) finish()
    }

    @Synchronized
    fun deny() {
        val to = peer
        if (to == null || (_step.value !is Step.Confirm && _step.value !is Step.WaitingForPartner)) return
        transport.send(to, CoopWire.encode(CoopWire.PAIR_DENY, ByteArray(0)))
        transport.deny(to)
        fail(CoopPairFail.DENIED)
    }

    /** Closes pairing: the policy goes back to closed and any unpaired connection is dropped. */
    @Synchronized
    fun close() {
        transport.setPolicy(pairs.allowedPeers(), false, null)
        val p = peer
        if (p != null && _step.value !is Step.Paired) transport.disconnect(p)
        opened = false
        peer = null
    }

    @Synchronized
    fun onEvent(e: CoopEvent) {
        when (e) {
            is CoopEvent.Ready -> {
                ticket = e.ticket
                if (_step.value is Step.Inviting) _step.value = inviting()
            }
            is CoopEvent.LanPeer -> {
                val label = lanLabel(e.userData) ?: return
                if (e.peer == transport.endpointId()) return
                lan[e.peer] = label
                if (_step.value is Step.Inviting) _step.value = inviting()
            }
            is CoopEvent.LanGone -> if (lan.remove(e.peer) != null && _step.value is Step.Inviting) {
                _step.value = inviting()
            }
            is CoopEvent.Connected -> onConnected(e)
            is CoopEvent.Frame -> if (e.peer == peer || e.peer in candidates) onFrame(e.peer, e.bytes)
            is CoopEvent.Disconnected -> if (e.code != DUPLICATE) onDisconnected(e.peer, e.code)
            is CoopEvent.DialFailed -> if (joining && peer == null && active()) fail(failFor(e.code))
            else -> Unit
        }
    }

    /** The HELLO and confirmation timeouts; call every 250 ms. */
    @Synchronized
    fun tick() {
        if (!active()) return
        val now = clock()
        val s = _step.value
        if (s is Step.Confirm || s is Step.WaitingForPartner) {
            if (now >= confirmDeadline) timeOut()
            return
        }
        for ((who, c) in candidates.entries.toList()) {
            if (now < c.deadline) continue
            if (joining) {
                timeOut()
                return
            }
            dropCandidate(who)   // a silent stray: the host keeps waiting for the real joiner
        }
    }

    private fun onConnected(e: CoopEvent.Connected) {
        if (!active()) return
        if (!joining && !e.pending && e.peer in pairs.allowedPeers()) return   // a paired device reconnecting is no joiner
        if (theirHello != null && e.peer != peer) {
            transport.disconnect(e.peer)
            return
        }
        if (joining) {
            if (peer != null && peer != e.peer) {
                transport.disconnect(e.peer)
                return
            }
            peer = e.peer
            if (_step.value is Step.Connecting || _step.value is Step.Inviting) _step.value = Step.Connecting(!joinedByTicket)
        }
        if (theirHello == null) candidates[e.peer] = Candidate(e.pending, clock() + HELLO_TIMEOUT_MS)
        else peerPending = e.pending
        // A new connection: the HELLO goes out again. A pending one can't send until accepted.
        if (!e.pending) sendHello(e.peer)
    }

    private fun dropCandidate(who: CoopPeerId) {
        candidates.remove(who)
        transport.disconnect(who)
    }

    private fun onFrame(from: CoopPeerId, bytes: ByteArray) {
        val msg = CoopWire.decode(bytes) ?: return
        if (!active()) return
        if (msg.type == CoopWire.HELLO) {
            if (theirHello == null && from in candidates) onHello(from, msg.payload)
            return
        }
        if (theirHello == null || from != peer) return
        when (msg.type) {
            CoopWire.PAIR_ACCEPT -> {
                theyAccepted = true
                if (iAccepted) finish()
            }
            CoopWire.PAIR_DENY -> {
                transport.disconnect(from)
                fail(CoopPairFail.DENIED)
            }
        }
    }

    private fun refuseHello(from: CoopPeerId, reason: CoopPairFail) {
        if (joining) {
            transport.disconnect(from)
            fail(reason)
        } else {
            dropCandidate(from)
        }
    }

    private fun onHello(from: CoopPeerId, payload: ByteArray) {
        val hello = CoopHello.decode(payload) ?: return
        val ours = ownHello()?.romSha1
        when {
            hello.protocol != COOP_PROTOCOL -> {
                transport.send(from, CoopWire.encode(CoopWire.BYE, byteArrayOf(VERSION_CODE)))
                refuseHello(from, CoopPairFail.VERSION)
            }
            hello.romSha1 != EMERALD_COOP_SHA1 || (ours != null && hello.romSha1 != ours) ->
                refuseHello(from, CoopPairFail.DIFFERENT_ROM)
            else -> {
                theirHello = hello
                peer = from
                peerPending = candidates.remove(from)?.pending ?: false
                for (other in candidates.keys.toList()) dropCandidate(other)
                val who = CoopPartner(
                    peer = from,
                    name = Gen3Text.decode(hello.save.name, 0, 7),
                    trainerId = hello.save.trainerId,
                    female = hello.female,
                    save = hello.save,
                )
                partner = who
                confirmDeadline = clock() + CONFIRM_TIMEOUT_MS
                if (peerPending) {
                    transport.accept(from)
                    peerPending = false
                    sendHello(from)
                }
                _step.value = Step.Confirm(who)
            }
        }
    }

    private fun onDisconnected(who: CoopPeerId, code: Int) {
        if (!active()) return
        val wasCandidate = candidates.remove(who) != null
        if (who != peer) return
        val s = _step.value
        if (s is Step.Confirm || s is Step.WaitingForPartner || (joining && wasCandidate)) fail(failFor(code))
    }

    private fun finish() {
        val them = partner ?: return
        val own = ownSave ?: return
        val hello = theirHello ?: return
        val pair = CoopPair(
            ownSave = own,
            ownLabel = "Emerald (" + Gen3Text.decode(own.name, 0, 7) + ")",
            partner = them,
            lastTicket = hello.ticket,
            pairedAt = clock(),
        )
        try {
            if (!pairs.add(pair)) {
                transport.disconnect(them.peer)
                fail(CoopPairFail.SAVE_ALREADY_PAIRED)
                return
            }
        } catch (e: IOException) {
            coopLog("couldn't store the pairing: ${e.message}")
            transport.disconnect(them.peer)
            fail(CoopPairFail.NETWORK)
            return
        }
        transport.accept(them.peer)
        _step.value = Step.Paired(pair)
    }

    private fun timeOut() {
        peer?.let { transport.disconnect(it) }
        fail(CoopPairFail.TIMEOUT)
    }

    private fun fail(reason: CoopPairFail) {
        _step.value = Step.Failed(reason)
    }

    private fun failFor(code: Int): CoopPairFail = when (code) {
        1, 7 -> CoopPairFail.TIMEOUT
        2 -> CoopPairFail.DENIED
        3 -> CoopPairFail.VERSION
        else -> CoopPairFail.NETWORK
    }

    private var joinedByTicket = false

    private fun dial(ticket: String, viaLan: Boolean) {
        if (!opened || ownSave == null) {
            fail(CoopPairFail.NETWORK)
            return
        }
        joining = true
        joinedByTicket = !viaLan
        val code = transport.connect(ticket)
        when {
            code == 0 -> _step.value = Step.Connecting(viaLan)
            code == -1 -> fail(CoopPairFail.BAD_INVITE)
            else -> fail(CoopPairFail.NETWORK)
        }
    }

    private fun sendHello(to: CoopPeerId) {
        val hello = ownHello()
        if (hello == null) {
            coopLog("no HELLO to send: no save is up")
            return
        }
        transport.send(to, CoopWire.encode(CoopWire.HELLO, hello.encode()))
    }

    private fun active(): Boolean {
        val s = _step.value
        return opened && s !is Step.Paired && s !is Step.Failed
    }

    private fun inviting() = Step.Inviting(ticket, lan.map { LanEntry(it.key, it.value) })

    private fun resetAll() {
        opened = false
        joining = false
        joinedByTicket = false
        peer = null
        peerPending = false
        candidates.clear()
        theirHello = null
        partner = null
        iAccepted = false
        theyAccepted = false
        ownSave = null
        ticket = null
        lan.clear()
        _step.value = Step.Inviting(null, emptyList())
    }

    private fun lanUserData(save: CoopSaveId) = LAN_PREFIX + toHex(save.name) + ";" + save.trainerId

    /** The name in LAN user data `pokedaisy-coop/1;<name hex>;<trainer id>`, shown through Gen3Text; null for any other text. */
    private fun lanLabel(userData: String): String? {
        val parts = userData.split(';')
        if (parts.size != 3 || parts[0] != LAN_VERSION) return null
        val hex = parts[1]
        if (hex.isEmpty() || hex.length > 16 || hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        if (parts[2].toIntOrNull() == null) return null
        val bytes = ByteArray(8) { 0xFF.toByte() }
        for (i in 0 until hex.length / 2) bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return Gen3Text.decode(bytes, 0, 7)
    }

    internal companion object {
        const val LAN_VERSION = "pokedaisy-coop/1"
        const val LAN_PREFIX = "$LAN_VERSION;"
        const val HELLO_TIMEOUT_MS = 10_000L
        const val CONFIRM_TIMEOUT_MS = 60_000L
        const val DUPLICATE = 8
        const val VERSION_CODE: Byte = 3

        /** The ticket for [peer] with no secret and no addresses. */
        internal fun peerOnlyTicket(peer: CoopPeerId): String = CoopTicket.peerOnly(peer)
    }
}
