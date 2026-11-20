@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.CoopPairingHost
import com.pokedaisy.app.companion.coop.CoopEndpointOptions
import com.pokedaisy.app.companion.coop.CoopEndpointSession
import com.pokedaisy.app.companion.coop.CoopIdentity
import com.pokedaisy.app.companion.coop.NoCoopLan
import com.pokedaisy.app.companion.coop.CoopInvite
import com.pokedaisy.app.companion.coop.CoopPair
import com.pokedaisy.app.companion.coop.CoopRelay
import com.pokedaisy.app.companion.coop.CoopSaveId
import com.pokedaisy.app.companion.coop.EMERALD_COOP_SHA1
import com.pokedaisy.app.companion.coop.RelayCoopTransport
import com.pokedaisy.app.companion.data.RomIdentityReader
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CoopSaveChoice
import com.pokedaisy.app.game.RomPicker
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.*

/** Account-free co-op pairing through the same relay protocol used by Android. */
internal object IosCoopHost {
    val defaultRelay: String
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("PokeDaisyCoopRelayURL") as? String)
            ?.let(CoopRelay::normalize).orEmpty()

    fun directory() = NSFileManager.defaultManager
        .URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
        .filterIsInstance<NSURL>().firstNotNullOf { it.path }.toPath() / "coop"

    private data class NativeNetwork(val tcp: IosCoopTcpNative, val discovery: IosCoopDiscovery)
    @Volatile private var native: NativeNetwork? = null
    private val manual = IosCoopIp { native?.tcp }

    fun install(tcp: IosCoopTcpNative, discovery: IosCoopDiscovery) { native = NativeNetwork(tcp, discovery) }

    val relayOnly: Boolean get() = native == null || iosPreferences.coopRelayOnly
    private val connector by lazy { IosCoopRelayConnector() }

    val session by lazy {
        CoopEndpointSession(
            createTransport = {
                val host = native
                RelayCoopTransport(
                    connector, IosCoopCrypto, { IosCoopNetwork(host?.tcp) },
                    lan = host?.discovery?.let(::IosCoopLan) ?: NoCoopLan,
                )
            },
            directory = ::directory,
            version = { NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "" },
            secretKey = { CoopIdentity.secretKey(directory(), IosCoopCrypto) },
            relayConnector = { connector },
            options = { CoopEndpointOptions(CoopRelay.effective(iosPreferences.coopRelayUrl, defaultRelay), relayOnly = relayOnly) },
        )
    }

    private val pending = MutableStateFlow<String?>(null)
    val invite = pending.asStateFlow()

    fun receiveInvite(link: String) { CoopInvite.parse(link)?.let { pending.value = it } }
    fun consumeInvite(ticket: String) { pending.compareAndSet(ticket, null) }

    fun pairingHost(games: () -> List<String>, title: (String) -> String, presentFrom: () -> UIViewController?) =
        object : CoopPairingHost {
            override val relayOnly get() = IosCoopHost.relayOnly
            override val defaultRelay get() = IosCoopHost.defaultRelay
            override var relayOverride: String?
                get() = iosPreferences.coopRelayUrl
                set(value) { iosPreferences.coopRelayUrl = value }

            override fun saveChoices(pairs: List<CoopPair>): List<CoopSaveChoice> =
                games().mapNotNull { path ->
                    val size = FileSystem.SYSTEM.metadataOrNull(path.toPath())?.size ?: return@mapNotNull null
                    if (size != 0x1000000L) return@mapNotNull null
                    val reader = RomIdentityReader.forPath(path.toPath())
                    if (reader.gameCode() != "BPEE" || reader.sha1() != EMERALD_COOP_SHA1) return@mapNotNull null
                    val save = RomPicker.savePathFor(path).toPath()
                    val id = CoopSaveId.read(save)
                    CoopSaveChoice(save, title(path), id, pairs.firstOrNull { it.ownSave == id }?.partner?.name)
                }

            override fun copyLink(link: String) { UIPasteboard.generalPasteboard.string = link }

            override fun shareLink(link: String) {
                val from = presentFrom() ?: return
                val share = UIActivityViewController(listOf(tr("Play Emerald with me in PokeDaisy: {0}", link)), null)
                share.popoverPresentationController?.let {
                    it.sourceView = from.view
                    it.sourceRect = from.view.bounds
                }
                from.presentViewController(share, animated = true, completion = null)
            }

            override fun startAddressHost(link: () -> String?, address: (String?) -> Unit) = manual.start(link, address)
            override fun stopAddressHost() = manual.stop()
            override fun fetchInvite(address: String, result: (Result<String>) -> Unit) = manual.fetch(address, result)
        }
}
