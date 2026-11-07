package com.pokedaisy.app.companion.coop

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [CoopTransport] over the player's relay and the LAN, pure Kotlin. Every path (a relay room, a TCP socket)
 * carries a [CoopChannel] (Noise IK, ping, CLOSE); this class decides who dials, who answers, which
 * connection a peer keeps and what the link hears:
 *
 * - Rooms: a socket on the pair room of every allowed peer, on our own invite room while pairing is open and
 *   on a ticket's invite room while a dial uses it. Invite room joiner and TCP dialer initiate; in a pair room
 *   the smaller key initiates when the relay says PEER_JOINED. The room's sockets reconnect with a backoff.
 * - Responder policy: a pair room only takes its partner; the invite room and TCP take an allowed peer, or an
 *   unknown one as *pending* while pairing is open (at most 8 frames of 2,048 bytes, 60 s), else NOT_ALLOWED.
 * - One connection per peer: a new one replaces the old unless the old is younger than the duplicate window,
 *   when the direct one wins, then the one whose initiator has the smaller key (both sides settle alike). The
 *   loser is closed DUPLICATE and reports nothing. A repeated [CoopEvent.Connected] is a new connection.
 * - Every channel that pushed `Connected` ends in one `Disconnected`, except a replaced one. A relay socket
 *   that closes, or the relay's PEER_LEFT, ends the channel with ERROR at once.
 *
 * Threads: `pokedaisy-coop-net` ticks every 250 ms; a pool does socket I/O, one writer per pipe; relay and
 * LAN callbacks arrive on their own threads. Everything shared sits under [lock]; a [CoopChannel] is entered
 * with [lock] held or free (its listener and `decide` run outside the channel's own lock), and its pipes only
 * enqueue. Only codes and counts are logged: never keys, tickets, room ids or payloads.
 */
class RelayCoopTransport(
    private val connector: CoopRelayConnector = OkHttpRelayConnector(),
    private val lan: CoopLan = NoCoopLan,
    private val timings: CoopTimings = CoopTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val bindLoopback: Boolean = false,
) : CoopTransport {
    /** The addresses a ticket carries (tests inject them). */
    internal var addresses: () -> List<InetAddress> =
        { if (bindLoopback) listOf(InetAddress.getLoopbackAddress()) else siteLocalAddresses() }

    private enum class RoomKind { PAIR, HOST, JOIN }

    /** One try at a relay socket: the socket arrives after the open returns, writes wait for it. */
    private class Attempt {
        val socket = AtomicReference<CoopRelaySocket?>()
        val ready = CountDownLatch(1)
        lateinit var writer: PipeWriter
    }

    private class Room(val id: String, val kind: RoomKind, val partner: CoopPeerId?, val backoff: RoomBackoff) {
        val kickBackoff = RoomBackoff(backoff.start, backoff.max, backoff.rnd)
        var rekick = false      // its channel died (timeout / error) with the partner still in the room: start a new handshake
        var nextKickAt = 0L
        var current: Attempt? = null
        var connecting = false
        var open = false
        var peerPresent = false
        var openedAt = 0L
        var nextAttemptAt = 0L
        var channel: Chan? = null
    }

    private class Chan(val kind: Int, val initiator: Boolean, val room: Room?, val target: CoopPeerId?) {
        lateinit var channel: CoopChannel
        lateinit var outbox: PipeWriter
        var tcp: DirectSocket? = null
        var peer: CoopPeerId? = null
        var pending = false
        var pendingFrames = 0
        var pendingSince = 0L
        var registered = false
        var retired = false
        var dyingSince = 0L
        val direct: Boolean get() = kind == CoopChannel.PATH_TCP
    }

    private class Dial(val peer: CoopPeerId, val deadline: Long, val room: String?) {
        val tried = HashSet<InetSocketAddress>()
        var tcpPending = 0
        var cancelled = false   // ended without a connection (resolved dials keep their TCP tries: the direct path may still win)
    }

    private val lock = Any()
    internal var random = Random()
    private var running = false
    private var secret = ByteArray(0)
    private var ownId: CoopPeerId? = null
    private var inviteSecret = ByteArray(0)
    private var relayUrl: String? = null
    private var relayOnly = false
    private var relayAnnounced = false
    private var listener: DirectListener? = null
    private var io: ExecutorService? = null
    private var worker: ScheduledExecutorService? = null
    private var ticket: String? = null
    private var lastAddressCheck = 0L
    private var allowed: Set<CoopPeerId> = emptySet()
    private var pairingOpen = false
    private val accepted = HashSet<CoopPeerId>()   // peers accept() took in: a new connection of theirs is never pending
    private val rooms = HashMap<String, Room>()
    private val chans = LinkedHashSet<Chan>()
    private val conns = HashMap<CoopPeerId, Chan>()
    private val dials = HashMap<CoopPeerId, Dial>()
    private val lanAddresses = HashMap<CoopPeerId, List<InetSocketAddress>>()

    private val events = ConcurrentLinkedQueue<CoopEvent>()
    private var frameCount = 0   // both counters are guarded by [events]
    private var otherCount = 0

    override fun start(secretKey: ByteArray, relayUrl: String?, relayOnly: Boolean): Int {
        if (secretKey.size != 32) return -1
        val url = relayUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (url != null && !RELAY_URL.matches(url)) return -2
        val self: CoopPeerId
        val port: Int
        synchronized(lock) {
            if (running) return -4
            val pool = Executors.newCachedThreadPool(daemon("pokedaisy-coop-io"))
            var direct: DirectListener? = null
            if (!relayOnly) {
                try {
                    direct = DirectListener(bindLoopback, pool, ::incoming)
                } catch (e: IOException) {
                    pool.shutdownNow()
                    return -3
                }
            }
            io = pool
            listener = direct
            secret = secretKey.copyOf()
            self = CoopPeerId.of(CoopKeys.publicKey(secret))
            ownId = self
            inviteSecret = ByteArray(16).also { SecureRandom().nextBytes(it) }
            this.relayUrl = url
            this.relayOnly = relayOnly
            relayAnnounced = false
            allowed = emptySet()
            pairingOpen = false
            running = true
            lastAddressCheck = clock()
            if (url == null) push(CoopEvent.Relay(0, ""))
            refreshTicket()
            direct?.start()
            val tick = minOf(TICK_MS, timings.backoffStartMs).coerceAtLeast(20L)
            worker = Executors.newSingleThreadScheduledExecutor(daemon("pokedaisy-coop-net")).also {
                it.scheduleWithFixedDelay(::tickSafely, tick, tick, TimeUnit.MILLISECONDS)
            }
            port = direct?.port ?: 0
        }
        if (!relayOnly) lan.start(self, port, null, lanListener)
        return 0
    }

    override fun endpointId(): CoopPeerId? = synchronized(lock) { ownId.takeIf { running } }

    override fun ticket(): String? = synchronized(lock) { ticket.takeIf { running } }

    override fun setPolicy(allowed: Set<CoopPeerId>, pairingOpen: Boolean, lanUserData: String?) {
        synchronized(lock) {
            if (!running) return
            this.allowed = allowed.toSet()
            this.pairingOpen = pairingOpen
            reconcile()
        }
        if (!relayOnly) lan.setUserData(lanUserData)
        openDue()
    }

    override fun connect(ticket: String): Int {
        val t = CoopTicket.decode(ticket) ?: return -1
        synchronized(lock) {
            if (!running) return -2
            if (t.peer == ownId) return -1
            val inviteRoom = t.inviteSecret?.let { CoopKeys.inviteRoom(it) }
            val room = if (t.peer !in allowed && relayUrl != null) inviteRoom else null
            val dial = Dial(t.peer, clock() + timings.dialMs, room)
            dials.put(t.peer, dial)?.cancelled = true
            reconcile()
            kickPair(t.peer)
            if (!relayOnly) startTcp(dial, t.addresses + (lanAddresses[t.peer] ?: emptyList()))
        }
        openDue()
        return 0
    }

    override fun accept(peer: CoopPeerId) {
        synchronized(lock) {
            val c = conns[peer] ?: return
            c.pending = false
            accepted.add(peer)
        }
    }

    override fun deny(peer: CoopPeerId) {
        synchronized(lock) {
            accepted.remove(peer)
            conns[peer]?.let { quiet(it); endChan(it, CoopFrames.DENIED, report = true) }
        }
    }

    override fun disconnect(peer: CoopPeerId) {
        synchronized(lock) {
            dials.remove(peer)?.cancelled = true
            accepted.remove(peer)
            conns[peer]?.let { quiet(it); endChan(it, CoopFrames.CLOSED, report = true) }
            for (c in chans.toList()) if (c.target == peer && !c.registered) retire(c, CoopFrames.CLOSED)
            reconcile()
        }
    }

    override fun send(peer: CoopPeerId, frame: ByteArray): Boolean {
        if (frame.isEmpty() || frame.size > CoopFrames.MAX_APP_FRAME) return false
        val chan = synchronized(lock) {
            val c = conns[peer] ?: return false
            if (c.pending || c.retired || c.outbox.backlog >= PipeWriter.BACKLOG) return false
            c
        }
        return chan.channel.send(frame)
    }

    override fun poll(): CoopEvent? {
        synchronized(events) {
            val e = events.poll() ?: return null
            if (e is CoopEvent.Frame) frameCount-- else otherCount--
            return e
        }
    }

    override fun close() {
        val pool: ExecutorService?
        val ticker: ScheduledExecutorService?
        synchronized(lock) {
            if (!running) return
            running = false
            accepted.clear()
            for (c in chans.toList()) {
                c.retired = true
                c.channel.close(CoopFrames.CLOSED)
                c.tcp?.abort()
            }
            for (r in rooms.values) r.current?.let {
                it.writer.closeSoon()
                it.ready.countDown()   // a writer still waiting for its socket stops at once
            }
            rooms.clear()
            chans.clear()
            conns.clear()
            dials.clear()
            lanAddresses.clear()
            listener?.close()
            listener = null
            ticker = worker
            worker = null
            pool = io
            io = null
            synchronized(events) {
                events.clear()
                frameCount = 0
                otherCount = 0
            }
        }
        ticker?.shutdownNow()
        pool?.shutdown()
        lan.stop()
        // nothing of ours keeps running past close() (bounded: a TCP connect attempt may take its 1.5 s)
        ticker?.awaitTermination(500, TimeUnit.MILLISECONDS)
        pool?.awaitTermination(1_500, TimeUnit.MILLISECONDS)
    }

    /** The handshake hash of the connection kept for [peer] (both sides see the same bytes). */
    internal fun channelHashFor(peer: CoopPeerId): ByteArray? = synchronized(lock) { conns[peer] }?.channel?.handshakeHash

    // ---------------------------------------------------------------- events

    private fun push(e: CoopEvent) {
        if (!running) return
        if (e is CoopEvent.Disconnected && conns[e.peer] == null) accepted.remove(e.peer)   // its last connection is gone
        synchronized(events) {
            if (otherCount >= OTHER_SLOTS) {
                coopLog("co-op event queue full: dropped a ${e.javaClass.simpleName}")
                return
            }
            otherCount++
            events.add(e)
        }
    }

    /** False when the frame slots are full (the caller closes that peer). */
    private fun pushFrame(peer: CoopPeerId, bytes: ByteArray): Boolean {
        if (!running) return true
        synchronized(events) {
            if (frameCount >= FRAME_SLOTS) return false
            frameCount++
            events.add(CoopEvent.Frame(peer, bytes))
        }
        return true
    }

    // ---------------------------------------------------------------- the ticket

    /** Rebuilds the ticket from the address list and pushes Ready when it changed. Lock held. */
    private fun refreshTicket() {
        val me = ownId ?: return
        val port = listener?.port ?: 0
        val list = if (relayOnly || port == 0) emptyList() else {
            val found = try {
                addresses()
            } catch (e: RuntimeException) {
                emptyList()
            }
            found.distinct().take(MAX_ADDRESSES).map { InetSocketAddress(it, port) }
        }
        val t = CoopTicket(me, inviteSecret, list).encode()
        if (t != ticket) {
            ticket = t
            push(CoopEvent.Ready(t))
        }
    }

    // ---------------------------------------------------------------- the relay rooms

    private fun wanted(r: Room): Boolean = relayUrl != null && when (r.kind) {
        RoomKind.PAIR -> r.partner in allowed
        RoomKind.HOST -> pairingOpen
        RoomKind.JOIN -> dials.values.any { it.room == r.id && it.peer !in allowed }
    }

    /** Creates the rooms policy and dials want and drops the idle ones nobody wants. Lock held. */
    private fun reconcile() {
        if (relayUrl == null) return
        val me = ownId ?: return
        fun ensure(id: String, kind: RoomKind, partner: CoopPeerId?) {
            if (rooms[id] == null) rooms[id] = Room(id, kind, partner, RoomBackoff(timings.backoffStartMs, timings.backoffMaxMs) { random })
        }
        for (p in allowed) CoopKeys.pairRoom(secret, me, p)?.let { ensure(it, RoomKind.PAIR, p) }
        if (pairingOpen) ensure(CoopKeys.inviteRoom(inviteSecret), RoomKind.HOST, null)
        for (d in dials.values) if (d.room != null && d.peer !in allowed) ensure(d.room, RoomKind.JOIN, d.peer)
        for (r in rooms.values.toList()) dropIfIdle(r)
    }

    private fun dropIfIdle(r: Room) {
        if (wanted(r) || r.channel != null) return
        rooms.remove(r.id)
        r.current?.writer?.closeSoon()
        r.current = null
    }

    /** Opens a socket for every wanted room that has none and is due, with the lock released around the connector. */
    private fun openDue() {
        class Start(val room: Room, val attempt: Attempt, val url: String)
        val starts = ArrayList<Start>()
        synchronized(lock) {
            val url = relayUrl ?: return
            val pool = io ?: return
            if (!running) return
            val now = clock()
            for (r in rooms.values) {
                if (r.current != null || r.connecting || now < r.nextAttemptAt || !wanted(r)) continue
                val a = Attempt()
                a.writer = PipeWriter(
                    pool, a.ready,
                    write = { bytes -> a.socket.get()?.send(bytes) ?: false },
                    onEnd = { a.socket.get()?.close() },
                )
                a.writer.start()
                r.current = a
                r.connecting = true
                r.open = false
                starts += Start(r, a, url)
            }
        }
        for (s in starts) {
            val socket = try {
                connector.open(s.url, s.room.id, RoomListener(s.room, s.attempt))
            } catch (e: RuntimeException) {
                coopLog("co-op relay open threw ${e.javaClass.simpleName}")
                s.attempt.ready.countDown()
                roomClosed(s.room, s.attempt, 1006)
                continue
            }
            s.attempt.socket.set(socket)
            s.attempt.ready.countDown()
        }
    }

    private inner class RoomListener(private val room: Room, private val attempt: Attempt) : CoopRelaySocket.Listener {
        override fun onOpen() {
            synchronized(lock) {
                if (!current()) return
                room.open = true
                room.connecting = false
                room.openedAt = clock()
                if (!relayAnnounced) {
                    relayAnnounced = true
                    push(CoopEvent.Relay(1, relayUrl ?: ""))
                }
            }
        }

        override fun onMessage(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            var deliver: Chan? = null
            synchronized(lock) {
                if (!current()) return
                when (bytes[0].toInt() and 0xFF) {
                    CoopFrames.PEER_JOINED -> peerJoined(room)
                    CoopFrames.PEER_LEFT -> {
                        room.peerPresent = false
                        room.channel?.channel?.pipeClosed()
                    }
                    CoopFrames.RELAY_DATA -> deliver = room.channel ?: lazyResponder(room)
                    else -> Unit
                }
            }
            deliver?.channel?.receive(bytes.copyOfRange(1, bytes.size))
        }

        override fun onClosed(code: Int) = roomClosed(room, attempt, code)

        private fun current() = running && rooms[room.id] === room && room.current === attempt
    }

    /** The relay says the other peer is in the room: a stale channel is gone, and the initiator starts a new one. Lock held. */
    private fun peerJoined(room: Room) {
        room.peerPresent = true
        room.channel?.channel?.pipeClosed()
        relayChan(room, initiatesIn(room))?.channel?.begin()
    }

    private fun initiatesIn(room: Room): Boolean = when (room.kind) {
        RoomKind.JOIN -> true
        RoomKind.HOST -> false
        RoomKind.PAIR -> ownId!!.hex < room.partner!!.hex
    }

    /** DATA with no channel yet in a room we answer in (the partner redialed without a new PEER_JOINED). Lock held. */
    private fun lazyResponder(room: Room): Chan? = if (initiatesIn(room)) null else relayChan(room, false)

    private fun relayChan(room: Room, initiator: Boolean): Chan? {
        val attempt = room.current ?: return null
        if (!room.open) return null
        val kind = if (room.kind == RoomKind.PAIR) CoopChannel.PATH_PAIR else CoopChannel.PATH_INVITE
        val chan = Chan(kind, initiator, room, if (initiator) room.partner else null)
        val writer = attempt.writer
        chan.outbox = writer
        chan.channel = CoopChannel(
            initiator, secret, if (initiator) room.partner!!.bytes() else null, CoopChannel.prologue(kind, room.id),
            { writer.offer(CoopFrames.relayData(it)) }, clock, timings, { decide(chan, it) }, ChanListener(chan),
        )
        chans.add(chan)
        room.channel = chan
        return chan
    }

    /** A relay socket ended: its channel with it, and the room tries again after the backoff. */
    private fun roomClosed(room: Room, attempt: Attempt, code: Int) {
        synchronized(lock) {
            if (!running || rooms[room.id] !== room || room.current !== attempt) return
            val now = clock()
            if (room.open && now - room.openedAt >= timings.backoffResetMs) room.backoff.reset()
            room.current = null
            room.connecting = false
            room.open = false
            room.peerPresent = false
            attempt.writer.closeNow()
            room.nextAttemptAt = now + room.backoff.next()
            room.channel?.channel?.pipeClosed()
            if (code == CLOSE_ROOM_FULL && room.kind == RoomKind.JOIN) {
                for (d in dials.values.filter { it.room == room.id }) {
                    dials.remove(d.peer)?.cancelled = true
                    push(CoopEvent.DialFailed(CoopFrames.ERROR, "room full"))
                }
            }
            dropIfIdle(room)
        }
    }

    // ---------------------------------------------------------------- channels

    /** A responder's policy, asked with the channel's lock released. */
    private fun decide(chan: Chan, remote: CoopPeerId): Int = synchronized(lock) {
        when {
            !running -> CoopFrames.ERROR
            chan.kind == CoopChannel.PATH_PAIR -> if (remote == chan.room?.partner) 0 else CoopFrames.NOT_ALLOWED
            remote in allowed || remote in accepted -> 0
            pairingOpen -> {
                chan.pending = true
                0
            }
            else -> CoopFrames.NOT_ALLOWED
        }
    }

    private inner class ChanListener(private val chan: Chan) : CoopChannel.Listener {
        override fun opened(channel: CoopChannel, remote: CoopPeerId) {
            synchronized(lock) {
                if (!running || chan.retired) return
                chan.peer = remote
                val old = conns[remote]
                if (old != null && old !== chan) {
                    if (!keepsNew(old, chan)) {
                        retire(chan, CoopFrames.DUPLICATE)
                        dials.remove(remote)?.cancelled = true   // the peer is connected: that dial has nothing left to do
                        return
                    }
                    retire(old, CoopFrames.DUPLICATE)
                }
                register(chan, remote)
            }
        }

        override fun frame(channel: CoopChannel, bytes: ByteArray) {
            synchronized(lock) {
                val peer = chan.peer ?: return
                if (!running || chan.retired || conns[peer] !== chan) return
                if (chan.pending && (++chan.pendingFrames > PENDING_FRAMES || bytes.size > PENDING_BYTES)) {
                    endChan(chan, CoopFrames.OVERFLOW, report = true)
                } else if (!pushFrame(peer, bytes)) {
                    endChan(chan, CoopFrames.OVERFLOW, report = true)
                }
            }
        }

        override fun closed(channel: CoopChannel, code: Int, beforeOpen: Boolean) {
            synchronized(lock) {
                if (!running || chan.retired) return
                chan.retired = true
                chan.room?.takeIf { it.kind == RoomKind.PAIR && (code == CoopFrames.TIMEOUT || code == CoopFrames.ERROR) }?.rekick = true
                release(chan, drain = code != CoopFrames.TIMEOUT && code != CoopFrames.ERROR) // a silent or failed pipe is cut; a CLOSE we owe the peer drains
                val peer = chan.peer
                if (chan.registered && peer != null) {
                    if (conns[peer] !== chan) return
                    if (code == CoopFrames.DUPLICATE) {
                        chan.dyingSince = clock()   // the peer settled differently: gone unless another connection replaces it
                    } else {
                        conns.remove(peer)
                        push(CoopEvent.Disconnected(peer, code))
                    }
                } else if (chan.initiator && beforeOpen) {
                    dialFailed(chan, code)
                }
            }
        }
    }

    /** The rule both sides apply alike: age, then direct, then the smaller initiator key; a tie takes the newer. */
    private fun keepsNew(old: Chan, new: Chan): Boolean {
        return keepsNewConnection(
            old.retired, clock() - old.channel.openedAt, timings.duplicateWindowMs,
            old.direct, new.direct, initiatorKey(old), initiatorKey(new),
        )
    }

    /** Ends a pair-room channel by our own hand: no automatic new handshake follows. */
    private fun quiet(chan: Chan) {
        chan.room?.rekick = false
    }

    private fun initiatorKey(c: Chan): String = if (c.initiator) ownId!!.hex else c.peer!!.hex

    private fun register(chan: Chan, remote: CoopPeerId) {
        conns[remote] = chan
        chan.registered = true
        if (remote in accepted) chan.pending = false   // accepted once: a replacing connection never asks again
        if (chan.pending) chan.pendingSince = clock()
        chan.room?.let { it.rekick = false; it.kickBackoff.reset() }
        dials.remove(remote)
        push(CoopEvent.Connected(remote, chan.pending, chan.initiator, chan.direct))
        reconcile()
    }

    /** Ends a channel without telling the link; [code] goes to the peer. */
    private fun retire(chan: Chan, code: Int) {
        if (chan.retired) return
        chan.retired = true
        chan.channel.close(code)
        release(chan, drain = true)
        val peer = chan.peer
        if (peer != null && conns[peer] === chan) conns.remove(peer)
    }

    /** [retire] and, for a connection the link knows, its one `Disconnected`. */
    private fun endChan(chan: Chan, code: Int, report: Boolean) {
        val peer = chan.peer
        val known = chan.registered && peer != null && conns[peer] === chan
        retire(chan, code)
        if (known && report) push(CoopEvent.Disconnected(peer!!, code))
    }

    /** Lets go of a channel's pipe: a TCP socket closes after what's queued when [drain] (else at once: a write blocked on a stalled peer is never interrupted), a relay room may be dropped. Lock held. */
    private fun release(chan: Chan, drain: Boolean) {
        chans.remove(chan)
        if (drain) chan.tcp?.writer?.closeSoon() else chan.tcp?.abort()
        val room = chan.room ?: return
        if (room.channel === chan) room.channel = null
        dropIfIdle(room)
    }

    /** A handshake we started ended before it opened: a refusal ends the dial, so does the last path failing. */
    private fun dialFailed(chan: Chan, code: Int) {
        val dial = dials[chan.target ?: return] ?: return
        val refusal = code == CoopFrames.NOT_ALLOWED || code == CoopFrames.DENIED || code == CoopFrames.VERSION || code == CoopFrames.OVERFLOW
        val others = dial.tcpPending > 0 || chans.any { it !== chan && it.target == dial.peer && !it.retired }
        if (!refusal && others) return
        dials.remove(dial.peer)?.cancelled = true
        push(CoopEvent.DialFailed(code, if (refusal) "refused" else "no connection"))
        reconcile()
    }

    /** connect() to a peer whose pair room we are already in: the smaller key starts the handshake now. Lock held. */
    private fun kickPair(peer: CoopPeerId) {
        if (peer !in allowed) return
        val id = CoopKeys.pairRoom(secret, ownId!!, peer) ?: return
        val room = rooms[id] ?: return
        if (room.open && room.peerPresent && room.channel == null && initiatesIn(room)) relayChan(room, true)?.channel?.begin()
    }

    // ---------------------------------------------------------------- TCP

    private fun incoming(socket: Socket) {
        synchronized(lock) {
            val pool = io
            if (!running || pool == null || chans.count { it.tcp != null } >= MAX_TCP) {
                socket.close()
                return
            }
            attach(Chan(CoopChannel.PATH_TCP, false, null, null), socket, pool, null)
        }
    }

    /** Wraps a connected socket as [chan]'s pipe and starts it; a dialer ([target]) sends its first message. Lock held. */
    private fun attach(chan: Chan, socket: Socket, pool: ExecutorService, target: CoopPeerId?) {
        val ds = DirectSocket(socket, pool, { chan.channel.receive(it) }, { chan.channel.pipeClosed() })
        chan.tcp = ds
        chan.outbox = ds.writer
        chan.channel = CoopChannel(
            target != null, secret, target?.bytes(), CoopChannel.prologue(CoopChannel.PATH_TCP, null),
            { ds.writer.offer(it) }, clock, timings, { decide(chan, it) }, ChanListener(chan),
        )
        chans.add(chan)
        ds.start()
        if (target != null) chan.channel.begin()
    }

    /** Direct TCP dials of [dial]'s peer to every address not tried yet. Lock held. */
    private fun startTcp(dial: Dial, addresses: List<InetSocketAddress>) {
        val pool = io ?: return
        for (a in addresses.distinct()) {
            if (!dial.tried.add(a)) continue
            dial.tcpPending++
            pool.execute {
                val socket = DirectSocket.connect(a, timings.directConnectMs)
                synchronized(lock) {
                    dial.tcpPending--
                    if (socket == null) return@execute
                    if (!running || dial.cancelled) {
                        socket.close()
                        return@execute
                    }
                    attach(Chan(CoopChannel.PATH_TCP, true, null, dial.peer), socket, pool, dial.peer)
                }
            }
        }
    }

    // ---------------------------------------------------------------- LAN

    private val lanListener = object : CoopLan.Listener {
        override fun found(peer: CoopPeerId, userData: String, addresses: List<InetSocketAddress>) {
            synchronized(lock) {
                if (!running || peer == ownId) return
                lanAddresses[peer] = addresses.take(MAX_ADDRESSES)
                push(CoopEvent.LanPeer(peer, userData))
                val dial = dials[peer]
                if (dial != null && !relayOnly) startTcp(dial, addresses)
            }
        }

        override fun lost(peer: CoopPeerId) {
            synchronized(lock) {
                if (!running || peer == ownId) return
                lanAddresses.remove(peer)
                push(CoopEvent.LanGone(peer))
            }
        }
    }

    // ---------------------------------------------------------------- the worker

    private fun tickSafely() {
        try {
            tick()
        } catch (e: RuntimeException) {
            coopLog("co-op tick threw ${e.javaClass.simpleName}")
        }
    }

    private fun tick() {
        val live: List<Chan>
        synchronized(lock) {
            if (!running) return
            val now = clock()
            for (d in dials.values.filter { now >= it.deadline }) {
                dials.remove(d.peer)?.cancelled = true
                val connected = conns[d.peer]?.let { it.registered && !it.retired } == true
                if (!connected) push(CoopEvent.DialFailed(CoopFrames.TIMEOUT, "timed out"))
            }
            for (r in rooms.values.toList()) {
                if (r.rekick && r.kind == RoomKind.PAIR && r.open && r.peerPresent && r.channel == null && now >= r.nextKickAt && wanted(r) && initiatesIn(r)) {
                    r.rekick = false
                    r.nextKickAt = now + r.kickBackoff.next()
                    relayChan(r, true)?.channel?.begin()
                }
            }
            for (c in conns.values.toList()) {
                val peer = c.peer ?: continue
                if (c.pending && c.registered && !c.retired && now - c.pendingSince >= timings.pendingMs) {
                    endChan(c, CoopFrames.PENDING_EXPIRED, report = true)
                } else if (c.dyingSince != 0L && now - c.dyingSince >= timings.duplicateWindowMs + DUPLICATE_GRACE_MS) {
                    conns.remove(peer)
                    push(CoopEvent.Disconnected(peer, CoopFrames.ERROR))
                }
            }
            if (now - lastAddressCheck >= timings.addressRecheckMs) {
                lastAddressCheck = now
                refreshTicket()
            }
            reconcile()
            live = chans.toList()
        }
        openDue()
        for (c in live) c.channel.tick()
    }

    private fun daemon(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

    private companion object {
        const val TICK_MS = 250L
        const val FRAME_SLOTS = 1_024
        const val OTHER_SLOTS = 64
        const val PENDING_FRAMES = 8
        const val PENDING_BYTES = 2_048
        const val MAX_ADDRESSES = 4
        const val MAX_TCP = 16
        const val CLOSE_ROOM_FULL = 4409
        const val DUPLICATE_GRACE_MS = 1_000L
        val RELAY_URL = Regex("^https?://[^/\\s?#@]+$")

        /** Up to the site-local IPv4 and unique-local IPv6 addresses of the interfaces that are up. */
        fun siteLocalAddresses(): List<InetAddress> = try {
            (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { a ->
                    !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isMulticastAddress && !a.isAnyLocalAddress &&
                        (a is Inet4Address && a.isSiteLocalAddress || a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC)
                }
                .sortedBy { if (it is Inet4Address) 0 else 1 }
        } catch (e: IOException) {
            emptyList()
        } catch (e: RuntimeException) {
            emptyList()
        }
    }
}

/** Reconnect delays: [start] doubling to [max], each with up to 20% jitter either way ([rnd] is read per call, tests inject it). */
internal class RoomBackoff(val start: Long, val max: Long, val rnd: () -> java.util.Random) {
    private var base = start

    fun next(): Long {
        val delay = (base * (1.0 + (rnd().nextDouble() * 2 - 1) * 0.2)).toLong()
        base = minOf(base * 2, max)
        return delay
    }

    fun reset() {
        base = start
    }
}

/**
 * The one-connection-per-peer rule, the same on both sides: the old connection goes when it is dead or
 * older than the window; inside it the direct one stays, then the one whose initiator key is smaller; a
 * tie (the same side dialed twice) takes the newer. True = keep the new one.
 */
internal fun keepsNewConnection(
    oldDead: Boolean, oldAgeMs: Long, windowMs: Long,
    oldDirect: Boolean, newDirect: Boolean, oldInitiatorKey: String, newInitiatorKey: String,
): Boolean {
    if (oldDead || oldAgeMs >= windowMs) return true
    if (oldDirect != newDirect) return newDirect
    return oldInitiatorKey >= newInitiatorKey
}
