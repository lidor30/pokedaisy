package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.data.Gen3Text
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import okio.IOException
import okio.Path

data class CoopEndpointOptions(val relay: String?, val relayOnly: Boolean)

/** Owns the active endpoint, pairing and lobby for both game and library screens. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoopEndpointSession(
    createTransport: () -> CoopTransport,
    private val directory: () -> Path,
    private val version: () -> String,
    private val secretKey: () -> ByteArray,
    private val relayConnector: () -> CoopLobbyConnector,
    private val options: () -> CoopEndpointOptions,
    private val localNetworkActive: (Boolean) -> Unit = {},
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    settingsDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val transport by lazy(createTransport)
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsScope = CoroutineScope(SupervisorJob() + settingsDispatcher)
    private var gameSettings: CoopGameSettings? = null

    fun bindGameSettings(settings: CoopGameSettings) {
        gameSettings = settings
    }

    fun unbindGameSettings(settings: CoopGameSettings) {
        if (gameSettings === settings) gameSettings = null
    }
    private val pairs by lazy { CoopPairs(directory() / "pairs.json") }

    // What the HELLO says: the game's, else the invite page's save.
    private class Own(val save: CoopSaveId, val female: Boolean, val romSha1: String)
    @Volatile private var gameOwn: Own? = null
    @Volatile private var pageOwn: Own? = null

    private val ownHello: () -> CoopHello? = {
        (gameOwn ?: pageOwn)?.let { o ->
            runCatching {
                CoopHello(COOP_PROTOCOL, version(), o.romSha1, o.save, o.female, transport.ticket())
            }.getOrNull()
        }
    }

    @Volatile private var activeLink: CoopLinkImpl? = null
    private val impl by lazy {
        CoopLinkImpl(transport, pairs, ownHello, clock, CoopBackoff()).also { link ->
            activeLink = link
            scope.launch(start = CoroutineStart.UNDISPATCHED) { link.state.collect { _state.value = override ?: it } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { link.messages.collect { _messages.emit(it) } }
        }
    }
    private val pairing = lazy { CoopPairing(transport, pairs, ownHello, clock) }
    private val pairingImpl: CoopPairing? get() = if (pairing.isInitialized()) pairing.value else null

    private val _state = MutableStateFlow<CoopState>(CoopState.Off())
    private val _messages = MutableSharedFlow<CoopMessage>(extraBufferCapacity = 256)
    @Volatile private var override: CoopState? = null
    private val _relay = MutableStateFlow(0)

    /** 0 none, 1 custom (from [CoopEvent.Relay]). */
    val relay: StateFlow<Int> = _relay.asStateFlow()

    // ROOMS: the relay's lobby, up with the endpoint when it has a relay (CoopLobby).
    @Volatile private var lobbyImpl: CoopLobby? = null
    private val _lobby = MutableStateFlow(CoopLobby.State())

    /** This device's room code, the listed rooms and what went wrong; the empty state while there's no lobby. */
    val lobby: StateFlow<CoopLobby.State> = _lobby.asStateFlow()

    /** Whether ROOMS can work now: the endpoint runs with a relay. */
    val lobbyAvailable: Boolean get() = lobbyImpl != null

    /** LIST MY ROOM: off again on every invite page (a listed room shows the trainer's name to strangers). */
    @Volatile var roomListed = false

    /** Looks a room code up; [onResult] (any thread) gets the host's invite, which [CoopPairing.join] dials. */
    fun findRoom(code: String, onResult: (CoopLobby.FindResult) -> Unit) {
        val l = lobbyImpl ?: return onResult(CoopLobby.FindResult.Offline)
        l.find(code, onResult)
    }

    /** Asks the lobby for the listed rooms of the invite page's ROM (they arrive in [lobby]). */
    fun refreshRooms() {
        val own = pageOwn ?: return
        val l = lobbyImpl ?: return
        roomsAt = clock()
        l.refresh(CoopLobbyWire.gameKey(own.romSha1))
    }

    /** When OPEN ROOMS was last asked for: [syncLobby] asks again every [ROOMS_REFRESH_MS] while the page waits. */
    @Volatile private var roomsAt = 0L

    /** The link to the paired partner; [CoopState.Off] while nothing holds the endpoint. */
    val link: CoopLink = object : CoopLink {
        override val state: StateFlow<CoopState> = _state.asStateFlow()
        override val messages: SharedFlow<CoopMessage> = _messages.asSharedFlow()
        override fun send(type: Int, payload: ByteArray): Boolean {
            require(type in CoopWire.FIRST_APP_TYPE..0xFFFF) { "message type out of range: $type" }
            require(payload.size <= CoopWire.MAX_PAYLOAD) { "payload too large: ${payload.size}" }
            return activeLink?.send(type, payload) ?: false
        }
    }

    private var holds = 0
    @Volatile private var gameHold = false

    /** A co-op session is running for this game (the endpoint is held for it). */
    val gameOn: Boolean get() = gameHold

    @Volatile private var pairingHold = false
    private var running = false
    private var localNetworkIsActive = false
    private var gameSave: CoopSaveId? = null
    private var nextTick = 0L

    init {
        scope.launch {
            while (isActive) {
                val command = if (running) select<(() -> Unit)?> {
                    commands.onReceive { it }
                    onTimeout(POLL_SLEEP_MS) { null }
                } else commands.receive()
                command?.let { guarded("control", it) }
                if (running) poll()
            }
        }
    }

    /** Takes a hold for the invite pages; release it with [releasePairing]. */
    fun pairing(): CoopPairing {
        val value = pairing.value
        post {
            if (!pairingHold) {
                pairingHold = true
                holds++
            }
            ensureRunning()
        }
        return value
    }

    fun releasePairing() = post {
        if (pairingHold) {
            pairingHold = false
            roomListed = false
            pairingImpl?.close()
            release()
        }
    }

    /** The save the invite page pairs: what the HELLO names while no game is up. */
    fun usePairingSave(saveFile: Path, romSha1: String?) = post { pageOwn = ownFor(saveFile, romSha1) }

    /** Reads the save and its pairing once, before either host adopts its game hooks. */
    suspend fun lookupGame(romSha1: String?, saveFile: Path): CoopGameSelection = onWorker { resolveGame(romSha1, saveFile) }

    fun startUnsupportedGame() = startForGame(CoopGameSelection(null, null, null, false).decide(false))

    private fun resolveGame(romSha1: String?, saveFile: Path): CoopGameSelection {
        val own = if (romSha1 == EMERALD_COOP_SHA1) ownFor(saveFile, romSha1) else null
        val paired = own?.let {
            try {
                pairs.forSave(it.save)
            } catch (e: IOException) {
                coopLog("pairs.json unreadable: ${e.message}")
                null
            }
        }
        return CoopGameSelection(romSha1, own?.save, paired, own?.female ?: false)
    }

    fun startForGame(decision: CoopGameDecision) {
        post {
            val reason = decision.reason
            if (reason != null) {
                endGame()
                setOverride(CoopState.Off(reason))
                return@post
            }
            val selection = decision.selection
            val save = checkNotNull(selection.save)
            val hash = checkNotNull(selection.romSha1)
            setOverride(null)
            gameOwn = Own(save, selection.female, hash)
            gameSave = save
            if (!gameHold) { gameHold = true; holds++ }
            ensureRunning()
            if (running) impl.startFor(save)
        }
    }

    fun stopGame() = post { endGame() }

    /** Checks the running game before applying either relay setting and restarting the endpoint. */
    fun changeRelay(change: () -> Unit): Boolean {
        if (gameSettings?.canChangeRelay() == false) return false
        change()
        post {
            if (holds == 0) return@post
            stopEndpoint()
            startEndpoint()
            gameSave?.let { if (running) impl.startFor(it) }
        }
        return true
    }

    /** Edits the pairing on the endpoint worker. Only confirmed removal of the current pair detaches its game. */
    fun unpair(save: CoopSaveId, onResult: (Boolean) -> Unit = {}) {
        settingsScope.launch {
            // A lookup may already have read this pairing. Let the host adopt it before checking the guard.
            var owner: CoopGameSettings?
            do {
                owner = gameSettings
                owner?.startup?.awaitLookup()
            } while (gameSettings !== owner)
            if (owner?.canRemove(save) == false) {
                onResult(false)
                return@launch
            }
            val current = owner?.takeIf { it.ownSave() == save }
            if (current != null) {
                if (current.removingCurrent || !current.pauseBattles()) {
                    onResult(false)
                    return@launch
                }
                current.removingCurrent = true
            }
            val removed = onWorker {
                try {
                    pairs.remove(save)
                } catch (e: IOException) {
                    coopLog("couldn't unpair: ${e.message}")
                    return@onWorker false
                }
                if (gameSave == save) {
                    endGame()
                    setOverride(CoopState.Off(CoopOffReason.NOT_PAIRED))
                }
                if (running && gameSave == null) {
                    guarded("refreshing the pairing policy") { transport.setPolicy(pairs.allowedPeers(), pairingHold, null) }
                }
                true
            }
            if (current != null) {
                current.removingCurrent = false
                if (gameSettings === current) {
                    if (removed) current.detach() else current.resumeBattles()
                }
            }
            onResult(removed)
        }
    }

    private suspend fun <T> onWorker(operation: () -> T): T {
        val result = CompletableDeferred<Result<T>>()
        post {
            if (result.isActive) {
                result.complete(runCatching(operation))
            }
        }
        return try {
            result.await().getOrThrow()
        } finally {
            result.cancel()
        }
    }

    /** Runs [block] on the session worker, after everything posted before it: after [pairing]'s endpoint start, so the native side is up. */
    fun afterStart(block: () -> Unit) = post(block)

    /** The pairings, [] while pairs.json is unreadable. */
    fun pairsList(): List<CoopPair> = try {
        pairs.all()
    } catch (e: IOException) {
        coopLog("pairs.json unreadable: ${e.message}")
        emptyList()
    }

    /** ui-preview / Paparazzi only: shows [state] as the link's. */
    fun previewState(state: CoopState) = setOverride(state)

    private fun setOverride(state: CoopState?) {
        override = state
        _state.value = state ?: activeLink?.state?.value ?: CoopState.Off()
    }

    private fun ownFor(saveFile: Path, romSha1: String?): Own? {
        val sha = romSha1 ?: return null
        val (id, female) = CoopSaveId.readIdentity(saveFile) ?: return null
        return Own(id, female, sha)
    }

    private fun post(block: () -> Unit) {
        commands.trySend(block)
    }

    private inline fun guarded(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coopLog("co-op $operation failed: ${e::class.simpleName}")
        }
    }

    private fun endGame() {
        if (gameHold) {
            gameHold = false
            if (running) impl.stop()
            gameOwn = null
            gameSave = null
            setOverride(null)
            release()
        } else {
            setOverride(null)
        }
    }

    /** Starts the endpoint if something holds it and it isn't running (a failed start is retried by the next request). */
    private fun ensureRunning() {
        if (holds > 0 && !running) startEndpoint()
    }

    private fun release() {
        if (holds > 0) holds--
        if (holds == 0) stopEndpoint()
    }

    private fun startEndpoint() {
        val key = try {
            secretKey()
        } catch (e: IOException) {
            coopLog("no device key: ${e::class.simpleName}")
            setOverride(CoopState.Off(CoopOffReason.NETWORK_ERROR))
            return
        }
        val (relay, relayOnly) = options()
        val code = transport.start(key, relay, relayOnly)
        if (code != 0 && code != -4) {
            coopLog("co-op endpoint didn't start ($code)")
            setOverride(CoopState.Off(CoopOffReason.NETWORK_ERROR))
            return
        }
        running = true
        if (relay != null) lobbyImpl = CoopLobby(relayConnector(), relay, clock, out = _lobby)
        if (override == CoopState.Off(CoopOffReason.NETWORK_ERROR)) setOverride(null)
        if (!relayOnly) {
            localNetworkIsActive = true
            localNetworkActive(true)
        }
        nextTick = 0L
    }

    private fun stopEndpoint() {
        if (!running) return
        running = false
        guarded("lobby close") { lobbyImpl?.close() }
        lobbyImpl = null
        _lobby.value = CoopLobby.State()
        if (localNetworkIsActive) {
            localNetworkIsActive = false
            guarded("local network close") { localNetworkActive(false) }
        }
        guarded("close") { transport.close() }
        _relay.value = 0
        setOverride(override)
    }

    private fun poll() {
        try {
            for (i in 0 until MAX_EVENTS_PER_POLL) {
                val e = transport.poll() ?: break
                if (e is CoopEvent.Relay) _relay.value = e.kind
                guarded("event") {
                    impl.onEvent(e)
                    pairingImpl?.onEvent(e)
                }
            }
            val now = clock()
            if (now >= nextTick) {
                nextTick = now + TICK_MS
                guarded("tick") {
                    impl.tick()
                    pairingImpl?.tick()
                    syncLobby()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coopLog("co-op polling failed: ${e::class.simpleName}")
            stopEndpoint()
            setOverride(CoopState.Off(CoopOffReason.NETWORK_ERROR))
        }
    }

    /**
     * The lobby hosts this device's room while the invite page waits for a partner (its ticket known); once a partner
     * dials in, or the page goes, the room goes too.
     */
    private fun syncLobby() {
        val l = lobbyImpl ?: return
        val own = pageOwn
        val ticket = (pairingImpl?.step?.value as? CoopPairing.Step.Inviting)?.ticket
        val invite = if (pairingHold && own != null && ticket != null) CoopLobbyWire.lobbyInvite(ticket) else null
        l.setHost(invite?.let {
            val name = CoopLobbyWire.cleanName(Gen3Text.decode(own!!.save.name, 0, 7), "TRAINER")
            CoopLobby.Host(it, CoopLobbyWire.gameKey(own.romSha1), name, roomListed)
        })
        if (invite != null && clock() - roomsAt >= ROOMS_REFRESH_MS) refreshRooms()
        l.tick()
    }

    companion object {
        private const val TICK_MS = 250L
        private const val ROOMS_REFRESH_MS = 20_000L
        private const val POLL_SLEEP_MS = 16L
        private const val MAX_EVENTS_PER_POLL = 256

        /** Only retail US Emerald (including a trimmed dump) can run the link battle patch. */
        fun mayBeRetailEmerald(code: String?, size: Long): Boolean = code == "BPEE" && size <= 0x1000000L

        fun gate(romSha1: String?, save: CoopSaveId?, paired: Boolean, cheatsLoaded: Boolean): CoopOffReason? = when {
            romSha1 != EMERALD_COOP_SHA1 -> CoopOffReason.NOT_EMERALD
            save == null -> CoopOffReason.NO_SAVE
            !paired -> CoopOffReason.NOT_PAIRED
            cheatsLoaded -> CoopOffReason.CHEATS_ON
            else -> null
        }

        fun refusesCheats(turningOn: Boolean, coopOn: Boolean): Boolean = turningOn && coopOn

        fun cheatsForEngine(enabled: Boolean, coopOn: Boolean, text: () -> String): String =
            if (!enabled || coopOn) "" else text()
    }
}
