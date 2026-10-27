package com.pokedaisy.app.companion.coop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.pokedaisy.app.BuildConfig
import com.pokedaisy.app.FeatureFlags
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.save.Gen3SaveData
import com.pokedaisy.app.companion.data.save.Gen3SaveFile
import com.pokedaisy.app.companion.data.save.Gen3SaveFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one co-op endpoint of the app: runs while at least one hold exists (the invite pages, a game), polls the
 * transport on `pokedaisy-coop` and feeds [link] and the pairing. Transport start / close / poll never run on the
 * UI thread: every change goes through one control thread, the polling through its own.
 */
object CoopSession {
    // Built the first time a context is there (the LAN discovery needs one).
    @Volatile private var transportStore: CoopTransport? = null
    private val transport: CoopTransport get() = transportStore ?: error("the co-op transport is built with the first context")

    @Synchronized
    private fun transportOf(app: Context): CoopTransport =
        transportStore ?: RelayCoopTransport(lan = NsdCoopLan(app)).also { transportStore = it }

    private val clock: () -> Long = { System.currentTimeMillis() }

    // What the HELLO says: the game's, else the invite page's save.
    private class Own(val save: CoopSaveId, val female: Boolean, val romSha1: String)
    @Volatile private var gameOwn: Own? = null
    @Volatile private var pageOwn: Own? = null

    private val ownHello: () -> CoopHello? = {
        (gameOwn ?: pageOwn)?.let { o ->
            runCatching {
                CoopHello(COOP_PROTOCOL, BuildConfig.VERSION_NAME, o.romSha1, o.save, o.female, transport.ticket())
            }.getOrNull()
        }
    }

    // Built on first use (they need a context for the pairs file).
    @Volatile private var pairsStore: CoopPairs? = null
    @Volatile private var impl: CoopLinkImpl? = null
    @Volatile private var pairingImpl: CoopPairing? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val _state = MutableStateFlow<CoopState>(CoopState.Off())
    private val _messages = MutableSharedFlow<CoopMessage>(extraBufferCapacity = 256)
    @Volatile private var override: CoopState? = null
    private val _relay = MutableStateFlow(0)

    /** 0 none, 1 custom (from [CoopEvent.Relay]). */
    val relay: StateFlow<Int> = _relay.asStateFlow()

    /** The link to the paired partner; [CoopState.Off] while nothing holds the endpoint. */
    val link: CoopLink = object : CoopLink {
        override val state: StateFlow<CoopState> = _state.asStateFlow()
        override val messages: SharedFlow<CoopMessage> = _messages.asSharedFlow()
        override fun send(type: Int, payload: ByteArray): Boolean {
            require(type in CoopWire.FIRST_APP_TYPE..0xFFFF) { "message type out of range: $type" }
            require(payload.size <= CoopWire.MAX_PAYLOAD) { "payload too large: ${payload.size}" }
            return impl?.send(type, payload) ?: false
        }
    }

    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "pokedaisy-coop-ctl").apply { isDaemon = true } }
    private var holds = 0
    @Volatile private var gameHold = false

    /** A co-op session is running for this game (the endpoint is held for it). */
    val gameOn: Boolean get() = gameHold

    /** Pure: cheats may not be switched on while co-op runs (the start gate refuses a game with cheats loaded; this is the same rule mid-session). */
    fun refusesCheats(turningOn: Boolean, coopOn: Boolean): Boolean = turningOn && coopOn

    /** Pure: the cheat text that reaches the engine: nothing while co-op runs. */
    fun cheatsForEngine(enabled: Boolean, coopOn: Boolean, text: () -> String): String = if (!enabled || coopOn) "" else text()
    private var pairingHold = false
    private var running = false
    @Volatile private var pollThread: Thread? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var gameSave: CoopSaveId? = null

    /** Only a "BPEE" ROM of at most 16 MB (retail Emerald or a trimmed dump of it) is worth hashing for co-op. */
    internal fun mayBeRetailEmerald(code: String?, size: Long): Boolean = code == "BPEE" && size <= 0x1000000L

    /** Pure: why co-op stays off for this game, or null. The feature flag is checked by the callers. */
    fun gate(romSha1: String?, save: CoopSaveId?, paired: Boolean, cheatsLoaded: Boolean): CoopOffReason? = when {
        romSha1 != EMERALD_COOP_SHA1 -> CoopOffReason.NOT_EMERALD
        save == null -> CoopOffReason.NO_SAVE
        !paired -> CoopOffReason.NOT_PAIRED
        cheatsLoaded -> CoopOffReason.CHEATS_ON
        else -> null
    }

    /** Takes a hold for the invite pages; release with [releasePairing]. The endpoint starts on the control thread. */
    @Synchronized
    fun pairing(context: Context): CoopPairing {
        val app = context.applicationContext
        val p = pairingImpl ?: CoopPairing(transportOf(app), pairsOf(app), ownHello, clock).also { pairingImpl = it }
        post { if (!pairingHold) { pairingHold = true; holds++ }; ensureRunning(app, Prefs(app)) }
        return p
    }

    fun releasePairing() = post {
        if (pairingHold) {
            pairingHold = false
            pairingImpl?.close()
            release()
        }
    }

    /** The save the invite page pairs: what the HELLO names while no game is up. */
    fun usePairingSave(saveFile: File, romSha1: String?) = post { pageOwn = ownFor(saveFile, romSha1) }

    fun startForGame(context: Context, prefs: Prefs, romSha1: String?, saveFile: File, cheatsLoaded: Boolean) {
        val app = context.applicationContext
        if (!FeatureFlags.coop(app)) return
        post {
            val id = if (romSha1 == EMERALD_COOP_SHA1) CoopSaveId.read(saveFile) else null
            val paired = id != null && try {
                pairsOf(app).forSave(id) != null
            } catch (e: IOException) {
                coopLog("pairs.json unreadable: ${e.message}")
                false
            }
            val reason = gate(romSha1, id, paired, cheatsLoaded)
            if (reason != null) {
                endGame()
                setOverride(CoopState.Off(reason))
                return@post
            }
            setOverride(null)
            gameOwn = ownFor(saveFile, romSha1)
            gameSave = id
            if (!gameHold) { gameHold = true; holds++ }
            ensureRunning(app, prefs)
            if (running) impl?.startFor(id!!)
        }
    }

    fun stopGame() = post { endGame() }

    /** RELAY / RELAY ONLY changed: closes the endpoint and starts it again with the new settings, if anything holds it. */
    fun restart(context: Context, prefs: Prefs) {
        val app = context.applicationContext
        post {
            if (holds == 0) return@post
            stopEndpoint()
            startEndpoint(app, prefs)
            gameSave?.let { if (running) impl?.startFor(it) }
        }
    }

    /** BYE if connected to them, the pairing removed, the policy refreshed. */
    fun unpair(context: Context, save: CoopSaveId) {
        val app = context.applicationContext
        post {
            val mine = gameSave == save && running
            if (mine) impl?.stop()
            try { pairsOf(app).remove(save) } catch (e: IOException) { coopLog("couldn't unpair: ${e.message}") }
            if (mine) impl?.startFor(save)
            else if (running) try {
                transport.setPolicy(pairsOf(app).allowedPeers(), pairingHold, null)
            } catch (e: IOException) { coopLog("couldn't refresh the policy: ${e.message}") }
        }
    }

    /** Runs [block] on the control thread, after everything posted before it: after [pairing]'s endpoint start, so the native side is up. */
    fun afterStart(block: () -> Unit) = post(block)

    /** The pairings, [] while pairs.json is unreadable. */
    fun pairsList(context: Context): List<CoopPair> = try {
        pairsOf(context.applicationContext).all()
    } catch (e: IOException) {
        coopLog("pairs.json unreadable: ${e.message}")
        emptyList()
    }

    /** ui-preview / Paparazzi only: shows [state] as the link's. */
    internal fun previewState(state: CoopState) = setOverride(state)

    private fun setOverride(state: CoopState?) {
        override = state
        _state.value = state ?: impl?.state?.value ?: CoopState.Off()
    }

    @Synchronized
    private fun pairsOf(app: Context): CoopPairs {
        pairsStore?.let { return it }
        val tr = transportOf(app)
        val p = CoopPairs(File(File(app.filesDir, "coop"), "pairs.json"))
        pairsStore = p
        val l = CoopLinkImpl(tr, p, ownHello, clock, CoopBackoff())
        impl = l
        scope.launch { l.state.collect { _state.value = override ?: it } }
        scope.launch { l.messages.collect { _messages.emit(it) } }
        return p
    }

    private fun ownFor(saveFile: File, romSha1: String?): Own? {
        val id = CoopSaveId.read(saveFile) ?: return null
        val sha = romSha1 ?: return null
        return Own(id, readFemale(saveFile), sha)
    }

    /** SaveBlock2's gender byte, read the way [CoopSaveId.read] reads the rest. */
    private fun readFemale(saveFile: File): Boolean = try {
        val format = Gen3SaveFormat.forGameCode("BPEE")!!
        when (val parsed = Gen3SaveFile.parse(saveFile.readBytes(), format)) {
            is Gen3SaveFile.Parsed.Ok -> Gen3SaveData(parsed.save).isFemale
            else -> false
        }
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    private fun post(block: () -> Unit) {
        control.execute { try { block() } catch (e: Throwable) { coopLog("co-op control failed: $e") } }
    }

    private fun endGame() {
        if (gameHold) {
            gameHold = false
            if (running) impl?.stop()
            gameOwn = null
            gameSave = null
            setOverride(null)
            release()
        } else {
            setOverride(null)
        }
    }

    /** Starts the endpoint if something holds it and it isn't running (a failed start is retried by the next request). */
    private fun ensureRunning(app: Context, prefs: Prefs) {
        pairsOf(app)
        if (holds > 0 && !running) startEndpoint(app, prefs)
    }

    private fun release() {
        if (holds > 0) holds--
        if (holds == 0) stopEndpoint()
    }

    private fun startEndpoint(app: Context, prefs: Prefs) {
        val key = try {
            CoopIdentity.secretKey(File(app.filesDir, "coop"))
        } catch (e: IOException) {
            coopLog("no device key: ${e.message}")
            setOverride(CoopState.Off(CoopOffReason.NETWORK_ERROR))
            return
        }
        val relayOnly = prefs.coopRelayOnly
        val relay = CoopRelay.effective(prefs.coopRelayUrl, BuildConfig.COOP_RELAY_URL)
        val code = transport.start(key, relay, relayOnly)
        if (code != 0 && code != -4) {
            coopLog("co-op endpoint didn't start ($code)")
            setOverride(CoopState.Off(CoopOffReason.NETWORK_ERROR))
            return
        }
        running = true
        if (override == CoopState.Off(CoopOffReason.NETWORK_ERROR)) setOverride(null)
        if (!relayOnly) takeMulticast(app)
        val t = Thread({ pollGuarded() }, "pokedaisy-coop").apply { isDaemon = true }
        pollThread = t
        t.start()
    }

    private fun stopEndpoint() {
        val t = pollThread
        pollThread = null
        running = false
        try { t?.join(2_000) } catch (_: InterruptedException) {}
        releaseMulticast()
        try { transport.close() } catch (e: Throwable) { coopLog("close failed: $e") }
        _relay.value = 0
        setOverride(override)   // the game's gate reason stays; only endGame clears it
    }

    /** Runs [pollLoop]; if anything kills it while the endpoint should be up, the endpoint is stopped and NETWORK_ERROR shown. */
    private fun pollGuarded() {
        val me = Thread.currentThread()
        try {
            pollLoop()
        } catch (e: Throwable) {
            coopLog("co-op poll thread died: $e")
            post {
                if (pollThread === me) {
                    stopEndpoint()
                    setOverride(CoopState.Off(CoopOffReason.NETWORK_ERROR))
                }
            }
        }
    }

    private fun pollLoop() {
        val me = Thread.currentThread()
        var nextTick = 0L
        while (pollThread === me) {
            var any = false
            while (pollThread === me) {
                val e = transport.poll() ?: break
                any = true
                if (e is CoopEvent.Relay) _relay.value = e.kind
                try {
                    impl?.onEvent(e)
                    pairingImpl?.onEvent(e)
                } catch (x: Throwable) {
                    coopLog("event failed: $x")
                }
            }
            val now = clock()
            if (now >= nextTick) {
                nextTick = now + TICK_MS
                try {
                    impl?.tick()
                    pairingImpl?.tick()
                } catch (x: Throwable) {
                    coopLog("tick failed: $x")
                }
            }
            if (!any) try { Thread.sleep(POLL_SLEEP_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun takeMulticast(app: Context) {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicast = wifi.createMulticastLock("pokedaisy-coop").apply { setReferenceCounted(false); acquire() }
        } catch (e: RuntimeException) {
            coopLog("no multicast lock: $e")
        }
    }

    private fun releaseMulticast() {
        try { multicast?.takeIf { it.isHeld }?.release() } catch (_: RuntimeException) {}
        multicast = null
    }

    private const val TICK_MS = 250L
    private const val POLL_SLEEP_MS = 16L
}
