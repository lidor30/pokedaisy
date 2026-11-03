package com.pokedaisy.app.companion.coop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ROOMS on the relay's lobby (`/v1/lobby`): while the invite page is open this device hosts a room - a short code the
 * relay hands out, and, if the player lists it, a row other players of the same ROM see - and any other device can FIND a
 * code or LIST the open rooms. All the lobby gives back is the host's invite (a [CoopTicket] without its LAN addresses),
 * which [CoopPairing.join] dials as if it came from a link: the relay never learns more than it does from a link, and the
 * ACCEPT THIS PLAYER? prompt still decides.
 *
 * One socket, opened when something needs it and closed a minute after nothing does; requests are answered in order, so
 * answers are matched to them by position. A dropped socket takes the room (and its code) with it: it is opened again
 * after [CoopBackoff]'s delay and the room hosted anew. No threads: the owner calls [tick] every 250 ms or so; the socket's
 * callbacks may come on any thread. [found] callbacks run outside the lock.
 */
class CoopLobby(
    private val connector: CoopLobbyConnector,
    private val relayUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: CoopBackoff = CoopBackoff(),
    private val out: MutableStateFlow<State> = MutableStateFlow(State()),
) {
    /** The room this device should host: [invite] for [game] (a [CoopLobbyWire.gameKey]), listed under [name] when [listed]. */
    data class Host(val invite: String, val game: String, val name: String, val listed: Boolean)

    enum class Problem { OFFLINE, LIMIT, FULL }

    data class State(
        /** This device's room code while it hosts one. */
        val code: String? = null,
        val listed: Boolean = false,
        /** The last LIST's rooms, newest first; null before one came. */
        val rooms: List<CoopLobbyWire.Listing>? = null,
        val loadingRooms: Boolean = false,
        val problem: Problem? = null,
    )

    sealed interface FindResult {
        data class Found(val invite: String) : FindResult
        data object NotFound : FindResult
        data object BadCode : FindResult
        data object Limit : FindResult
        data object Offline : FindResult
    }

    val state: StateFlow<State> = out.asStateFlow()

    private class Pending(val kind: Byte, val bytes: ByteArray, val host: Host? = null, val onFind: ((FindResult) -> Unit)? = null)

    private val lock = Any()
    private var socket: CoopRelaySocket? = null
    private var open = false
    private var generation = 0
    private var retryAt = 0L
    private var wanted: Host? = null
    private var hostedAt = 0L
    private var lastUse = 0L
    private var closed = false
    private val queued = ArrayDeque<Pending>()
    private val awaiting = ArrayDeque<Pending>()

    /** The room to host, or null for none: a change re-hosts (the same code while the socket lasts). */
    fun setHost(host: Host?) {
        synchronized(lock) {
            if (closed || host == wanted) return
            wanted = host
            queued.removeAll { it.kind == CoopLobbyWire.HOST || it.kind == CoopLobbyWire.UNHOST }
            if (host == null) {
                if (out.value.code != null && open) send(Pending(CoopLobbyWire.UNHOST, CoopLobbyWire.unhost()))
                out.value = out.value.copy(code = null, listed = false)
            } else {
                request(hostRequest(host))
            }
        }
    }

    /** Looks [code] up; [onResult] gets the host's invite or why not (once, on any thread). */
    fun find(code: String, onResult: (FindResult) -> Unit) {
        val c = CoopLobbyWire.normalizeCode(code)
        val immediate = synchronized(lock) {
            when {
                c == null -> FindResult.BadCode
                closed -> FindResult.Offline
                else -> { request(Pending(CoopLobbyWire.FIND, CoopLobbyWire.find(c), onFind = onResult)); null }
            }
        }
        immediate?.let(onResult)
    }

    /** Asks for the listed rooms of [game]; they arrive in [state]. */
    fun refresh(game: String) = synchronized(lock) {
        if (closed || queued.any { it.kind == CoopLobbyWire.LIST }) return@synchronized
        out.value = out.value.copy(loadingRooms = true)
        request(Pending(CoopLobbyWire.LIST, CoopLobbyWire.list(game)))
    }

    /** Connects when something waits, keeps the hosted room fresh, lets an unused socket go. */
    fun tick() = synchronized(lock) {
        if (closed) return@synchronized
        val now = clock()
        val needed = wanted != null || queued.isNotEmpty() || awaiting.isNotEmpty()
        val s = socket
        if (s == null) {
            if (needed && now >= retryAt) connect(now)
            return@synchronized
        }
        if (!open) return@synchronized
        val w = wanted
        if (w != null && now - hostedAt >= REHOST_MS && queued.none { it.kind == CoopLobbyWire.HOST }) request(hostRequest(w))
        if (!needed && now - lastUse >= IDLE_MS) dropSocket()
    }

    /** Stops for good: the socket closes (its room with it) and waiting lookups answer [FindResult.Offline]. */
    fun close() {
        val failed = synchronized(lock) {
            if (closed) return
            closed = true
            wanted = null
            dropSocket()
            out.value = State()
            takeFinds()
        }
        failed.forEach { it(FindResult.Offline) }
    }

    private fun hostRequest(h: Host) = Pending(CoopLobbyWire.HOST, CoopLobbyWire.host(h.listed, h.invite, h.game, h.name), host = h)

    // Under the lock.
    private fun request(p: Pending) {
        lastUse = clock()
        if (open) send(p) else queued.addLast(p)
        if (socket == null && clock() >= retryAt) connect(clock())
    }

    private fun send(p: Pending) {
        if (p.kind != CoopLobbyWire.UNHOST) awaiting.addLast(p)
        if (p.kind == CoopLobbyWire.HOST) hostedAt = clock()
        socket?.send(p.bytes)   // a refused send means the socket is going: onClosed follows
    }

    private fun connect(now: Long) {
        val gen = ++generation
        lastUse = now
        socket = connector.openLobby(relayUrl, object : CoopRelaySocket.Listener {
            override fun onOpen() = opened(gen)
            override fun onMessage(bytes: ByteArray) = answered(gen, bytes)
            override fun onClosed(code: Int) = ended(gen, code)
        })
    }

    private fun dropSocket() {
        generation++
        socket?.close()
        socket = null
        open = false
        queued.removeAll { it.kind == CoopLobbyWire.LIST }
        awaiting.removeAll { it.kind != CoopLobbyWire.FIND }
        out.value = out.value.copy(code = null, loadingRooms = false)
    }

    private fun takeFinds(): List<(FindResult) -> Unit> {
        val finds = (awaiting + queued).mapNotNull { it.onFind }
        awaiting.clear()
        queued.clear()
        return finds
    }

    private fun opened(gen: Int) = synchronized(lock) {
        if (gen != generation) return@synchronized
        open = true
        backoff.reset()
        out.value = out.value.copy(problem = null)
        // A new socket has no room: host it again (unless a HOST already waits).
        val w = wanted
        if (w != null && queued.none { it.kind == CoopLobbyWire.HOST }) queued.addFirst(hostRequest(w))
        while (queued.isNotEmpty()) send(queued.removeFirst())
    }

    private fun answered(gen: Int, bytes: ByteArray) {
        var callback: (() -> Unit)? = null
        synchronized(lock) {
            if (gen != generation) return
            val answer = CoopLobbyWire.decode(bytes)
            val p = awaiting.removeFirstOrNull()
            if (answer == null || p == null || !matches(p.kind, answer)) {
                coopLog("lobby: unexpected answer, reconnecting")
                val failed = listOfNotNull(p?.onFind) + takeFindsAwaiting()
                dropSocket()
                retryAt = clock() + backoff.next()
                callback = { failed.forEach { it(FindResult.Offline) } }
                return@synchronized
            }
            lastUse = clock()
            when (answer) {
                is CoopLobbyWire.Answer.Hosted -> {
                    // A HOST answered after the room was given up (setHost(null) raced it): let it go again.
                    if (wanted == null) send(Pending(CoopLobbyWire.UNHOST, CoopLobbyWire.unhost()))
                    else out.value = out.value.copy(code = answer.code, listed = p.host?.listed == true, problem = null)
                }
                is CoopLobbyWire.Answer.Found -> callback = { p.onFind?.invoke(FindResult.Found(answer.invite)) }
                is CoopLobbyWire.Answer.Rooms -> out.value = out.value.copy(rooms = answer.rooms, loadingRooms = false)
                is CoopLobbyWire.Answer.Error -> when (p.kind) {
                    CoopLobbyWire.FIND -> {
                        val r = when (answer.reason) {
                            CoopLobbyWire.NOT_FOUND -> FindResult.NotFound
                            CoopLobbyWire.LIMIT -> FindResult.Limit
                            else -> FindResult.BadCode
                        }
                        callback = { p.onFind?.invoke(r) }
                    }
                    CoopLobbyWire.HOST -> out.value = out.value.copy(
                        code = null,
                        problem = if (answer.reason == CoopLobbyWire.FULL) Problem.FULL else Problem.LIMIT,
                    )
                    else -> out.value = out.value.copy(loadingRooms = false, problem = Problem.LIMIT)
                }
            }
        }
        callback?.invoke()
    }

    private fun takeFindsAwaiting(): List<(FindResult) -> Unit> {
        val finds = awaiting.mapNotNull { it.onFind }
        awaiting.removeAll { it.onFind != null }
        return finds
    }

    private fun ended(gen: Int, code: Int) {
        val failed = synchronized(lock) {
            if (gen != generation) return
            socket = null
            open = false
            val finds = takeFinds().also { queued.clear() }
            out.value = out.value.copy(
                code = null, loadingRooms = false,
                problem = if (wanted == null) out.value.problem else if (code == LIMITED) Problem.LIMIT else Problem.OFFLINE,
            )
            retryAt = clock() + backoff.next()
            finds
        }
        failed.forEach { it(FindResult.Offline) }
    }

    private fun matches(kind: Byte, a: CoopLobbyWire.Answer): Boolean = when (a) {
        is CoopLobbyWire.Answer.Hosted -> kind == CoopLobbyWire.HOST
        is CoopLobbyWire.Answer.Found -> kind == CoopLobbyWire.FIND
        is CoopLobbyWire.Answer.Rooms -> kind == CoopLobbyWire.LIST
        is CoopLobbyWire.Answer.Error -> kind == a.request
    }

    private companion object {
        /** The relay drops a room not refreshed for 30 minutes. */
        const val REHOST_MS = 10 * 60_000L
        const val IDLE_MS = 60_000L
        /** The relay's close code for a per-address limit. */
        const val LIMITED = 4429
    }
}
