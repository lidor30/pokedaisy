package com.pokedaisy.app.companion.coop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.pokedaisy.app.BuildConfig
import com.pokedaisy.app.Prefs
import java.io.File
import okio.Path.Companion.toOkioPath

/** Android app context, preferences and multicast support for the shared co-op session. */
object CoopSession {
    private data class Environment(val app: Context, val prefs: Prefs)

    @Volatile private var initialized: Environment? = null
    private val environment get() = checkNotNull(initialized)
    private var multicast: WifiManager.MulticastLock? = null

    private fun directory() = File(environment.app.filesDir, "coop").toOkioPath()

    private val session = CoopEndpointSession(
        createTransport = { androidCoopTransport(lan = NsdCoopLan(environment.app)) },
        directory = ::directory,
        version = { BuildConfig.VERSION_NAME },
        secretKey = { CoopIdentity.secretKey(directory(), JvmCoopCrypto) },
        relayConnector = { OkHttpRelayConnector() },
        options = {
            val prefs = environment.prefs
            CoopEndpointOptions(CoopRelay.effective(prefs.coopRelayUrl, BuildConfig.COOP_RELAY_URL), prefs.coopRelayOnly)
        },
        localNetworkActive = { if (it) takeMulticast() else releaseMulticast() },
    )

    private fun configure(context: Context, prefs: Prefs = Prefs(context.applicationContext)) {
        initialized = Environment(context.applicationContext, prefs)
    }

    fun bind(context: Context, prefs: Prefs): CoopEndpointSession {
        configure(context, prefs)
        return session
    }

    val link get() = session.link
    val relay get() = session.relay
    val lobby get() = session.lobby
    val lobbyAvailable get() = session.lobbyAvailable
    val gameOn get() = session.gameOn
    var roomListed: Boolean
        get() = session.roomListed
        set(value) { session.roomListed = value }

    fun pairing(context: Context): CoopPairing {
        configure(context)
        return session.pairing()
    }

    fun releasePairing() = session.releasePairing()
    fun usePairingSave(saveFile: File, romSha1: String?) = session.usePairingSave(saveFile.toOkioPath(), romSha1)
    fun afterStart(block: () -> Unit) = session.afterStart(block)
    fun findRoom(code: String, onResult: (CoopLobby.FindResult) -> Unit) = session.findRoom(code, onResult)
    fun refreshRooms() = session.refreshRooms()

    fun unpair(context: Context, save: CoopSaveId) {
        configure(context)
        session.unpair(save)
    }

    fun pairsList(context: Context): List<CoopPair> {
        configure(context)
        return session.pairsList()
    }

    internal fun previewState(state: CoopState) = session.previewState(state)

    internal fun mayBeRetailEmerald(code: String?, size: Long) = CoopEndpointSession.mayBeRetailEmerald(code, size)
    fun gate(romSha1: String?, save: CoopSaveId?, paired: Boolean, cheatsLoaded: Boolean) =
        CoopEndpointSession.gate(romSha1, save, paired, cheatsLoaded)

    fun refusesCheats(turningOn: Boolean, coopOn: Boolean) = CoopEndpointSession.refusesCheats(turningOn, coopOn)
    fun cheatsForEngine(enabled: Boolean, coopOn: Boolean, text: () -> String) =
        CoopEndpointSession.cheatsForEngine(enabled, coopOn, text)

    private fun takeMulticast() {
        val app = environment.app
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicast = wifi.createMulticastLock("pokedaisy-coop").apply { setReferenceCounted(false); acquire() }
        } catch (e: RuntimeException) {
            coopLog("no multicast lock: ${e::class.simpleName}")
        }
    }

    private fun releaseMulticast() {
        try {
            multicast?.takeIf { it.isHeld }?.release()
        } catch (_: RuntimeException) {}
        multicast = null
    }
}
