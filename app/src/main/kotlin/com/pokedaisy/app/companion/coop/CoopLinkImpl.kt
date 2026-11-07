package com.pokedaisy.app.companion.coop

import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The link to one paired partner: dials its last ticket, checks its HELLO, delivers its app
 * messages and redials with a backoff when it drops. No threads: the session's poll loop calls
 * [onEvent] and [tick]. A repeated [CoopEvent.Connected] is a new connection, so the HELLO goes
 * out again each time; the state stays as it is.
 */
class CoopLinkImpl(
    private val transport: CoopTransport,
    private val pairs: CoopPairs,
    private val ownHello: () -> CoopHello?,
    private val clock: () -> Long,
    private val backoff: CoopBackoff,
) : CoopLink {
    private val _state = MutableStateFlow<CoopState>(CoopState.Off())
    override val state: StateFlow<CoopState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<CoopMessage>(extraBufferCapacity = 256)
    override val messages: SharedFlow<CoopMessage> = _messages.asSharedFlow()

    private var pair: CoopPair? = null
    private var live = false
    private var helloOk = false
    private var helloDeadline = 0L
    private var dialing = false
    private var direct = false
    private var notAllowed = 0
    private var ignoreOwnClose = false   // the Disconnected our own restart disconnect echoes back

    /** Looks up the pair of [save], allows only its partner and dials the last ticket. */
    @Synchronized
    fun startFor(save: CoopSaveId) {
        val hadConnection = live || helloOk
        resetConnection()
        val p = pairs.forSave(save)
        pair = p
        if (p == null) {
            _state.value = CoopState.Off(CoopOffReason.NOT_PAIRED)
            return
        }
        backoff.reset()
        notAllowed = 0
        transport.setPolicy(setOf(p.partner.peer), false, null)
        if (hadConnection) { ignoreOwnClose = true; transport.disconnect(p.partner.peer) }   // a restart never trusts a connection it knew of
        _state.value = CoopState.Connecting(p.partner)
        dial()
    }

    /** BYE if connected, disconnect, state Off. */
    @Synchronized
    fun stop() {
        val p = pair
        if (p != null) {
            if (_state.value is CoopState.Connected) {
                transport.send(p.partner.peer, CoopWire.encode(CoopWire.BYE, byteArrayOf(0)))
            }
            transport.disconnect(p.partner.peer)
        }
        pair = null
        resetConnection()
        _state.value = CoopState.Off()
    }

    @Synchronized
    override fun send(type: Int, payload: ByteArray): Boolean {
        require(type in CoopWire.FIRST_APP_TYPE..0xFFFF) { "message type out of range: $type" }
        require(payload.size <= CoopWire.MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val p = pair ?: return false
        if (_state.value !is CoopState.Connected || !helloOk) return false
        return transport.send(p.partner.peer, CoopWire.encode(type, payload))
    }

    @Synchronized
    fun onEvent(e: CoopEvent) {
        val p = pair ?: return
        val partner = p.partner.peer
        when (e) {
            is CoopEvent.Connected -> {
                if (e.peer != partner || e.pending) {
                    transport.disconnect(e.peer)
                    return
                }
                ignoreOwnClose = false
                live = true
                dialing = false
                helloOk = false
                helloDeadline = clock() + HELLO_TIMEOUT_MS
                direct = e.direct
                val s = _state.value
                if (s is CoopState.Off || s is CoopState.Disconnected) _state.value = CoopState.Connecting(p.partner)
                sendHello(partner)
            }
            is CoopEvent.Path -> if (e.peer == partner) {
                direct = e.direct
                if (_state.value is CoopState.Connected) _state.value = CoopState.Connected(p.partner, direct)
            }
            is CoopEvent.Frame -> if (e.peer == partner) onFrame(p, e.bytes)
            is CoopEvent.Disconnected -> {
                if (e.peer != partner || e.code == DUPLICATE) return
                if (ignoreOwnClose) {
                    ignoreOwnClose = false
                    return
                }
                if (_state.value is CoopState.Disconnected) return
                dropFromCode(e.code)
            }
            is CoopEvent.DialFailed -> if (dialing && !live) {
                ignoreOwnClose = false
                dialing = false
                dropFromCode(e.code)
            }
            else -> Unit
        }
    }

    /** Due retries and the HELLO timeout; call every 250 ms. */
    @Synchronized
    fun tick() {
        val p = pair ?: return
        val now = clock()
        val s = _state.value
        if (s is CoopState.Disconnected && now >= s.retryAtMs) {
            dial()
        } else if (live && !helloOk && now >= helloDeadline) {
            transport.disconnect(p.partner.peer)
            drop(CoopDropReason.NETWORK)
        }
    }

    private fun onFrame(p: CoopPair, bytes: ByteArray) {
        val msg = CoopWire.decode(bytes) ?: return
        if (!helloOk) {
            if (msg.type == CoopWire.HELLO) onHello(p, msg.payload)
            return
        }
        when {
            msg.type == CoopWire.BYE -> {
                transport.disconnect(p.partner.peer)
                drop(CoopDropReason.CLOSED)
            }
            msg.type < CoopWire.FIRST_APP_TYPE || msg.type > MAX_APP_TYPE -> Unit
            !_messages.tryEmit(CoopMessage(msg.type, msg.payload)) -> {
                coopLog("co-op message queue full: closing the link")
                transport.disconnect(p.partner.peer)
                drop(CoopDropReason.NETWORK)
            }
        }
    }

    private fun onHello(p: CoopPair, payload: ByteArray) {
        val peer = p.partner.peer
        val hello = CoopHello.decode(payload) ?: return
        val ours = ownHello()?.romSha1
        when {
            hello.protocol != COOP_PROTOCOL -> {
                transport.send(peer, CoopWire.encode(CoopWire.BYE, byteArrayOf(VERSION_CODE)))
                refuse(peer, CoopDropReason.VERSION)
            }
            hello.romSha1 != EMERALD_COOP_SHA1 || (ours != null && hello.romSha1 != ours) ->
                refuse(peer, CoopDropReason.DIFFERENT_ROM)
            hello.save != p.partner.save -> refuse(peer, CoopDropReason.DIFFERENT_SAVE)
            else -> {
                helloOk = true
                backoff.reset()
                notAllowed = 0
                hello.ticket?.let { refreshTicket(p, it) }
                val current = pair ?: p
                val s = _state.value
                if (s !is CoopState.Connected || s.direct != direct) {
                    _state.value = CoopState.Connected(current.partner, direct)
                }
            }
        }
    }

    private fun refreshTicket(p: CoopPair, ticket: String) {
        if (p.lastTicket == ticket) return
        try {
            pairs.updateTicket(p.ownSave, ticket)
            pair = p.copy(lastTicket = ticket)
        } catch (e: IOException) {
            coopLog("couldn't keep the partner's new ticket: ${e.message}")
        }
    }

    private fun refuse(peer: CoopPeerId, reason: CoopDropReason) {
        transport.disconnect(peer)
        drop(reason)
    }

    private fun dropFromCode(code: Int) {
        if (code == NOT_ALLOWED) {
            notAllowed++
            if (notAllowed >= NOT_ALLOWED_LIMIT) {
                drop(CoopDropReason.NOT_RECOGNISED, NOT_RECOGNISED_DELAY_MS)
            } else {
                drop(CoopDropReason.NETWORK)
            }
            return
        }
        notAllowed = 0
        drop(
            when (code) {
                0, 2 -> CoopDropReason.CLOSED
                3 -> CoopDropReason.VERSION
                else -> CoopDropReason.NETWORK   // 1, 5, 6, 7 and anything outside the table
            },
        )
    }

    private fun drop(reason: CoopDropReason, delayMs: Long? = null) {
        val p = pair ?: return
        live = false
        helloOk = false
        dialing = false
        _state.value = CoopState.Disconnected(p.partner, reason, clock() + (delayMs ?: backoff.next()))
    }

    private fun dial() {
        val p = pair ?: return
        _state.value = CoopState.Connecting(p.partner)
        val ticket = p.lastTicket ?: return   // none known: wait for the partner to dial in
        dialing = true
        val code = transport.connect(ticket)
        if (code != 0) {
            coopLog("couldn't dial the partner (code $code)")
            dialing = false
            drop(CoopDropReason.NETWORK)
        }
    }

    private fun sendHello(peer: CoopPeerId) {
        val hello = ownHello()
        if (hello == null) {
            coopLog("no HELLO to send: no save is up")
            return
        }
        transport.send(peer, CoopWire.encode(CoopWire.HELLO, hello.encode()))
    }

    private fun resetConnection() {
        ignoreOwnClose = false
        live = false
        helloOk = false
        dialing = false
    }

    private companion object {
        const val HELLO_TIMEOUT_MS = 10_000L
        const val MAX_APP_TYPE = 0x03FF
        const val NOT_ALLOWED = 4
        const val DUPLICATE = 8
        const val VERSION_CODE: Byte = 3
        const val NOT_ALLOWED_LIMIT = 3
        const val NOT_RECOGNISED_DELAY_MS = 60_000L
    }
}
