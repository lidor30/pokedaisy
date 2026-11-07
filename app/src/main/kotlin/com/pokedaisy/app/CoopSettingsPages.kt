package com.pokedaisy.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pokedaisy.app.companion.coop.CoopInvite
import com.pokedaisy.app.companion.coop.CoopIpHelloClient
import com.pokedaisy.app.companion.coop.CoopIpHelloServer
import com.pokedaisy.app.companion.coop.CoopLobby
import com.pokedaisy.app.companion.coop.CoopPair
import com.pokedaisy.app.companion.coop.CoopPairing
import com.pokedaisy.app.companion.coop.CoopRelay
import com.pokedaisy.app.companion.coop.CoopSaveId
import com.pokedaisy.app.companion.coop.CoopSession
import com.pokedaisy.app.companion.coop.EMERALD_COOP_SHA1
import com.pokedaisy.app.companion.coop.parseHostPort
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CoopPairActions
import com.pokedaisy.app.companion.ui.CoopPairBody
import com.pokedaisy.app.companion.ui.CoopPairView
import com.pokedaisy.app.companion.ui.CoopRoomsUi
import com.pokedaisy.app.companion.ui.CoopSaveChoice
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.GroupedRows
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionTextField
import com.pokedaisy.app.companion.ui.PageHint
import com.pokedaisy.app.companion.ui.SettingRow
import com.pokedaisy.app.companion.ui.coopFindLine
import com.pokedaisy.app.companion.ui.coopPartnerRows
import com.pokedaisy.app.companion.ui.coopWifiAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > CO-OP's pages on the top screen - PAIR (a save, then its panes), PARTNERS (UNPAIR) and RELAY - with their state and
 * what they do, so SettingsActivity only routes to them. [prefs] is read late (the activity sets its prefs in onCreate, after
 * ui-preview has set [preview]); [changed] tells the activity a row's value changed (its `revision`).
 */
internal class CoopSettingsPages(
    private val activity: ComponentActivity,
    private val prefs: () -> Prefs,
    private val changed: () -> Unit,
) {
    /** What PAIR shows instead of the live session (ui-preview: no network). */
    var preview by mutableStateOf<CoopPairView?>(null)

    /** The pairings, for PARTNERS and the CO-OP group's count ([loadPairs]). */
    var pairs by mutableStateOf<List<CoopPair>>(emptyList())
        private set

    private var choices by mutableStateOf<List<CoopSaveChoice>?>(null)
    private var picked by mutableStateOf<CoopSaveChoice?>(null)
    private var pairing by mutableStateOf<CoopPairing?>(null)
    private var ticketIn: String? = null
    private var joinError by mutableStateOf<String?>(null)
    private var nearbyError by mutableStateOf<String?>(null)
    private var wifi by mutableStateOf<String?>(null)
    private var unpairing by mutableStateOf<CoopPair?>(null)
    private val ipServer = CoopIpHelloServer { currentLink() }
    /** Bumped when the invite is left, so a BY IP listener still starting up stops itself. */
    @Volatile private var ipGeneration = 0

    /** A save is picked: BACK goes to the save list before it leaves PAIR. */
    val picking: Boolean get() = picked != null

    /** PAIR opened, from the CO-OP group ([ticket] null) or an invite link (dialled once a save is picked). */
    fun open(ticket: String?) {
        ticketIn = ticket
        choices = null
        picked = null
        joinError = null
        nearbyError = null
    }

    /** Leaves the invite: the endpoint's hold and the BY IP listener go, the save list comes back. */
    fun leave() {
        ipGeneration++
        ipServer.stop()
        if (pairing != null) CoopSession.releasePairing()
        pairing = null
        picked = null
        choices = null
        joinError = null
        nearbyError = null
        wifi = null
    }

    suspend fun loadPairs() {
        pairs = withContext(Dispatchers.IO) { CoopSession.pairsList(activity) }
    }

    /** The library's retail English Emerald ROMs, each with its save's trainer (off the UI thread). */
    private fun loadChoices(): List<CoopSaveChoice> {
        val prefs = prefs()
        val pairs = CoopSession.pairsList(activity)
        return RomFolder.libraryRoms(activity, prefs)
            .filter { it.length() == SettingsActivity.EMERALD_ROM_BYTES && RomIdentity.gameCode(it) == "BPEE" && RomIdentity.sha1(it) == EMERALD_COOP_SHA1 }
            .map { rom ->
                val save = SavesLocation.saveFor(activity, prefs, rom)
                val id = if (save.isFile) CoopSaveId.read(save) else null
                val pair = id?.let { i -> pairs.firstOrNull { it.ownSave == i } }
                CoopSaveChoice(rom, save, GameTitles.label(activity, prefs, rom), id, pair?.partner?.name)
            }
    }

    /** A save picked: the invite opens (and, from a link, dials it). The endpoint is up before [CoopPairing.open] runs. */
    private fun pick(choice: CoopSaveChoice) {
        val id = choice.id ?: return
        picked = choice
        joinError = null
        nearbyError = null
        CoopSession.usePairingSave(choice.save, EMERALD_COOP_SHA1)
        val p = CoopSession.pairing(activity)
        pairing = p
        val ticket = ticketIn
        CoopSession.afterStart {
            p.open(id)
            if (ticket != null) p.join(ticket)
            CoopSession.refreshRooms()
        }
        val gen = ipGeneration
        if (!prefs().coopRelayOnly) Thread {
            val port = ipServer.start()
            if (gen != ipGeneration) { ipServer.stop(); return@Thread }
            val ip = coopWifiAddress()
            activity.runOnUiThread { wifi = ip?.let { "$it:$port" } }
        }.start()
    }

    private fun currentLink(): String? =
        (pairing?.step?.value as? CoopPairing.Step.Inviting)?.ticket?.let { runCatching { CoopInvite.link(it) }.getOrNull() }

    /** A typed or listed room code, looked up on the relay's lobby, then joined like an invite link. */
    private fun joinRoom(code: String) {
        joinError = null
        CoopSession.findRoom(code) { r ->
            activity.runOnUiThread {
                if (r is CoopLobby.FindResult.Found) pairing?.join(r.invite)
                else joinError = coopFindLine(r)
            }
        }
    }

    /** BY IP: asks the address for its invite (off the UI thread), then joins it. */
    private fun joinByIp(text: String) {
        val target = parseHostPort(text)
        if (target == null) {
            nearbyError = "That isn't a valid address"
            return
        }
        nearbyError = null
        Thread {
            val ticket = CoopIpHelloClient.fetch(target.first, target.second)
            activity.runOnUiThread {
                if (ticket == null) nearbyError = "Couldn't reach your partner. Check the internet and try again."
                else pairing?.join(ticket)
            }
        }.start()
    }

    private fun copyLink() {
        val link = currentLink() ?: return
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("invite", link))
        Toast.makeText(activity, tr("Link copied"), Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val link = currentLink() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tr("Play Emerald with me in PokeDaisy: {0}", link))
        runCatching { activity.startActivity(Intent.createChooser(send, null)) }
    }

    /** PAIR: [onDone] leaves it for the hub (DONE once paired). */
    @Composable
    fun PairPage(m: GbaTextMetrics, small: GbaTextMetrics, onDone: () -> Unit) {
        val shown = preview
        LaunchedEffect(choices == null) {
            if (shown == null && choices == null) choices = withContext(Dispatchers.IO) { loadChoices() }
        }
        val p = pairing
        val step = remember(p) {
            p?.step ?: kotlinx.coroutines.flow.MutableStateFlow<CoopPairing.Step>(CoopPairing.Step.Inviting(null, emptyList()))
        }.collectAsState().value
        val lobby = CoopSession.lobby.collectAsState().value
        val prefs = prefs()
        val view = shown ?: CoopPairView(
            choices = choices, picked = picked, step = step, relayOnly = prefs.coopRelayOnly, wifi = wifi,
            rooms = CoopRoomsUi(CoopRelay.effective(prefs.coopRelayUrl, BuildConfig.COOP_RELAY_URL) != null, lobby),
            joinError = joinError, nearbyError = nearbyError,
        )
        val actions = CoopPairActions(
            onPick = ::pick,
            onCopy = ::copyLink,
            onShare = ::shareLink,
            onJoinLink = { text -> pairing?.join(text) },
            onJoinLan = { peer -> pairing?.joinLan(peer) },
            onJoinIp = ::joinByIp,
            onAccept = { pairing?.accept() },
            onDeny = { pairing?.deny() },
            onDone = { leave(); onDone() },
            onTryAgain = { picked?.let(::pick) },
            onCancel = ::leave,
            onListRoom = { on -> CoopSession.roomListed = on },
            onJoinCode = ::joinRoom,
            onRefreshRooms = { CoopSession.refreshRooms() },
        )
        CoopPairBody(view, actions, m, small)
    }

    /** PARTNERS: the pairings, one row each; a tap asks UNPAIR ([UnpairConfirm]). [revision] reloads them. */
    @Composable
    fun PartnersPage(m: GbaTextMetrics, revision: Int) {
        LaunchedEffect(revision) { loadPairs() }
        val rows = if (pairs.isEmpty()) listOf(SettingRow(tk("NO PARTNERS YET"), null, enabled = false) {})
        else coopPartnerRows(pairs) { unpairing = it }
        Column(Modifier.fillMaxWidth()) {
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() })
            }
        }
    }

    /** UNPAIR's confirmation over PARTNERS, while a pairing's row was tapped. */
    @Composable
    fun UnpairConfirm(m: GbaTextMetrics) {
        val p = unpairing ?: return
        OptionConfirm(
            title = tk("UNPAIR"),
            message = tr("Unpair {0}? You'll need a new invite to play together again.", p.partner.name),
            confirmLabel = tk("UNPAIR"),
            m = m,
            onDismiss = { unpairing = null },
            onConfirm = {
                unpairing = null
                CoopSession.unpair(activity, p.ownSave)
                pairs = pairs - p
                changed()
            },
        )
    }

    /** RELAY: the address of a relay of your own (https://), or the built-in one. */
    @Composable
    fun RelayPage(m: GbaTextMetrics, small: GbaTextMetrics) {
        val prefs = prefs()
        var text by remember { mutableStateOf(prefs.coopRelayUrl.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        fun use(url: String?) {
            prefs.coopRelayUrl = url
            text = url.orEmpty()
            error = null
            CoopSession.restart(activity, prefs)
            changed()
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            PageHint(tr("A relay helps two devices find each other. Keep the built-in one unless a friend runs their own."), m, small)
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 4)) {
                    GbaText(tr("RELAY"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(vertical = m.u * 2))
                    OptionTextField(text, { text = it; error = null }, small, placeholder = "https://relay.example.com")
                    error?.let {
                        GbaText(tr(it), OptionColors.value, OptionColors.valueShadow, small, Modifier.padding(top = m.u * 4), maxLines = 4)
                    }
                    Row(Modifier.padding(vertical = m.u * 4), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                        OptionButton(tk("SAVE"), m, emphasis = true, onClick = {
                            val url = CoopRelay.normalize(text)
                            if (url == null) error = "That isn't an https:// address" else use(url)
                        })
                        OptionButton(tk("USE BUILT-IN"), m, onClick = { use(null) })
                    }
                }
            }
        }
    }
}
