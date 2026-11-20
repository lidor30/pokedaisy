package com.pokedaisy.app

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
import com.pokedaisy.app.companion.coop.CoopEndpointSession
import com.pokedaisy.app.companion.coop.CoopInvite
import com.pokedaisy.app.companion.coop.CoopLobby
import com.pokedaisy.app.companion.coop.CoopPair
import com.pokedaisy.app.companion.coop.CoopPairing
import com.pokedaisy.app.companion.coop.CoopRelay
import com.pokedaisy.app.companion.coop.EMERALD_COOP_SHA1
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Platform integrations for the shared pairing pages, accessed only when needed. */
interface CoopPairingHost {
    val relayOnly: Boolean
    val defaultRelay: String
    var relayOverride: String?

    fun saveChoices(pairs: List<CoopPair>): List<CoopSaveChoice>
    fun copyLink(link: String)
    fun shareLink(link: String)
    fun startAddressHost(link: () -> String?, address: (String?) -> Unit)
    fun stopAddressHost()
    fun fetchInvite(address: String, result: (Result<String>) -> Unit)
}

/** Shared PAIR, PARTNERS, and RELAY pages. The caller owns the surrounding menu navigation. */
class CoopSettingsPages(
    private val session: () -> CoopEndpointSession,
    private val host: CoopPairingHost,
    private val changed: () -> Unit,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    private val ui = CoroutineScope(Dispatchers.Main.immediate)
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
    // Ignore a lookup or address advertisement that started on a page already left.
    private var generation = 0

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
        generation++
        host.stopAddressHost()
        if (pairing != null) session().releasePairing()
        pairing = null
        picked = null
        choices = null
        joinError = null
        nearbyError = null
        wifi = null
    }

    suspend fun loadPairs() {
        pairs = withContext(background) { session().pairsList() }
    }

    /** A save picked: the invite opens (and, from a link, dials it). The endpoint is up before [CoopPairing.open] runs. */
    private fun pick(choice: CoopSaveChoice) {
        val id = choice.id ?: return
        picked = choice
        joinError = null
        nearbyError = null
        val endpoint = session()
        endpoint.usePairingSave(choice.save, EMERALD_COOP_SHA1)
        val p = endpoint.pairing()
        pairing = p
        val ticket = ticketIn
        endpoint.afterStart {
            p.open(id)
            if (ticket != null) p.join(ticket)
            endpoint.refreshRooms()
        }
        val opened = ++generation
        if (!host.relayOnly) host.startAddressHost({ linkFor(p) }) { address ->
            ui.launch { if (opened == generation) wifi = address }
        }
    }

    private fun linkFor(pairing: CoopPairing?): String? =
        (pairing?.step?.value as? CoopPairing.Step.Inviting)?.ticket?.let { runCatching { CoopInvite.link(it) }.getOrNull() }

    /** A typed or listed room code, looked up on the relay's lobby, then joined like an invite link. */
    private fun joinRoom(code: String) {
        joinError = null
        val opened = generation
        session().findRoom(code) { result ->
            ui.launch {
                if (opened != generation) return@launch
                if (result is CoopLobby.FindResult.Found) pairing?.join(result.invite)
                else joinError = coopFindLine(result)
            }
        }
    }

    private fun joinByIp(text: String) {
        nearbyError = null
        val opened = generation
        host.fetchInvite(text) { result ->
            ui.launch {
                if (opened != generation) return@launch
                result.fold(
                    onSuccess = { pairing?.join(it) },
                    onFailure = {
                        nearbyError = if (it is IllegalArgumentException) "That isn't a valid address"
                        else "Couldn't reach your partner. Check the internet and try again."
                    },
                )
            }
        }
    }

    private fun copyLink() = linkFor(pairing)?.let(host::copyLink)
    private fun shareLink() = linkFor(pairing)?.let(host::shareLink)

    /** PAIR: [onDone] leaves it for the hub (DONE once paired). */
    @Composable
    fun PairPage(m: GbaTextMetrics, small: GbaTextMetrics, onDone: () -> Unit) {
        val shown = preview
        LaunchedEffect(choices == null) {
            if (shown == null && choices == null) choices = withContext(background) { host.saveChoices(session().pairsList()) }
        }
        val p = pairing
        val step = remember(p) {
            p?.step ?: kotlinx.coroutines.flow.MutableStateFlow<CoopPairing.Step>(CoopPairing.Step.Inviting(null, emptyList()))
        }.collectAsState().value
        val view = if (shown != null) shown else {
            val lobby = session().lobby.collectAsState().value
            CoopPairView(
                choices = choices, picked = picked, step = step, relayOnly = host.relayOnly, wifi = wifi,
                rooms = CoopRoomsUi(CoopRelay.effective(host.relayOverride, host.defaultRelay) != null, lobby),
                joinError = joinError, nearbyError = nearbyError,
            )
        }
        val actions = CoopPairActions(
            onPick = ::pick,
            onCopy = { copyLink() },
            onShare = { shareLink() },
            onJoinLink = { text -> pairing?.join(text) },
            onJoinLan = { peer -> pairing?.joinLan(peer) },
            onJoinIp = ::joinByIp,
            onAccept = { pairing?.accept() },
            onDeny = { pairing?.deny() },
            onDone = { leave(); onDone() },
            onTryAgain = { picked?.let(::pick) },
            onCancel = ::leave,
            onListRoom = { on -> session().roomListed = on },
            onJoinCode = ::joinRoom,
            onRefreshRooms = { session().refreshRooms() },
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
                session().unpair(p.ownSave) { removed ->
                    if (removed) {
                        pairs = pairs - p
                        changed()
                    }
                }
            },
        )
    }

    /** RELAY: the address of a relay of your own (https://), or the built-in one. */
    @Composable
    fun RelayPage(m: GbaTextMetrics, small: GbaTextMetrics) {
        var text by remember { mutableStateOf(host.relayOverride.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        fun use(url: String?) {
            if (!session().changeRelay { host.relayOverride = url }) return
            text = url.orEmpty()
            error = null
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
