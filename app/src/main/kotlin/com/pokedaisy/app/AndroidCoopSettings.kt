package com.pokedaisy.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.pokedaisy.app.companion.coop.CoopIpHelloClient
import com.pokedaisy.app.companion.coop.CoopIpHelloServer
import com.pokedaisy.app.companion.coop.CoopPair
import com.pokedaisy.app.companion.coop.CoopSaveId
import com.pokedaisy.app.companion.coop.CoopSession
import com.pokedaisy.app.companion.coop.EMERALD_COOP_SHA1
import com.pokedaisy.app.companion.coop.parseHostPort
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CoopSaveChoice
import java.net.Inet4Address
import java.net.NetworkInterface
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.Dispatchers

internal fun androidCoopSettings(
    activity: ComponentActivity,
    prefs: () -> Prefs,
    changed: () -> Unit,
): CoopSettingsPages = CoopSettingsPages(
    session = { CoopSession.bind(activity, prefs()) },
    host = AndroidCoopPairingHost(activity, prefs),
    changed = changed,
    background = Dispatchers.IO,
)

private class AndroidCoopPairingHost(
    private val activity: ComponentActivity,
    private val prefs: () -> Prefs,
) : CoopPairingHost {
    override val relayOnly get() = prefs().coopRelayOnly
    override val defaultRelay get() = BuildConfig.COOP_RELAY_URL
    override var relayOverride: String?
        get() = prefs().coopRelayUrl
        set(value) { prefs().coopRelayUrl = value }

    @Volatile private var currentLink: (() -> String?)? = null
    private val ipServer = CoopIpHelloServer { currentLink?.invoke() }
    @Volatile private var ipGeneration = 0

    override fun saveChoices(pairs: List<CoopPair>): List<CoopSaveChoice> {
        val prefs = prefs()
        return RomFolder.libraryRoms(activity, prefs)
            .filter { it.length() == SettingsActivity.EMERALD_ROM_BYTES && RomIdentity.gameCode(it) == "BPEE" && RomIdentity.sha1(it) == EMERALD_COOP_SHA1 }
            .map { rom ->
                val save = SavesLocation.saveFor(activity, prefs, rom)
                val id = if (save.isFile) CoopSaveId.read(save.toOkioPath()) else null
                val pair = id?.let { i -> pairs.firstOrNull { it.ownSave == i } }
                CoopSaveChoice(save.toOkioPath(), GameTitles.label(activity, prefs, rom), id, pair?.partner?.name)
            }
    }

    override fun startAddressHost(link: () -> String?, address: (String?) -> Unit) {
        currentLink = link
        val gen = ++ipGeneration
        Thread {
            val port = ipServer.start()
            if (gen != ipGeneration) {
                if (currentLink == null) ipServer.stop()
                return@Thread
            }
            address(coopWifiAddress()?.let { "$it:$port" })
        }.start()
    }

    override fun stopAddressHost() {
        ipGeneration++
        currentLink = null
        ipServer.stop()
    }

    override fun fetchInvite(address: String, result: (Result<String>) -> Unit) {
        val target = parseHostPort(address)
        if (target == null) {
            result(Result.failure(IllegalArgumentException()))
            return
        }
        Thread {
            val ticket = CoopIpHelloClient.fetch(target.first, target.second)
            result(if (ticket == null) Result.failure(java.io.IOException()) else Result.success(ticket))
        }.start()
    }

    override fun copyLink(link: String) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("invite", link))
        Toast.makeText(activity, tr("Link copied"), Toast.LENGTH_SHORT).show()
    }

    override fun shareLink(link: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tr("Play Emerald with me in PokeDaisy: {0}", link))
        runCatching { activity.startActivity(Intent.createChooser(send, null)) }
    }
}

/** This device's IPv4 address on the Wi-Fi network (else any private one), or null. Reads interfaces: not on the UI thread. */
private fun coopWifiAddress(): String? = try {
    val own = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic to it } }
        .filter { !it.second.isLoopbackAddress && !it.second.isLinkLocalAddress }
    (own.firstOrNull { it.first.name.startsWith("wlan") } ?: own.firstOrNull { it.second.isSiteLocalAddress })?.second?.hostAddress
} catch (e: java.io.IOException) {
    null
}
