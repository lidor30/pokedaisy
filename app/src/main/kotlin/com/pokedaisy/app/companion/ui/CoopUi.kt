package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.pokedaisy.app.companion.coop.CoopPair
import com.pokedaisy.app.companion.coop.CoopPairing
import com.pokedaisy.app.companion.coop.CoopOffReason
import com.pokedaisy.app.companion.coop.CoopDropReason
import com.pokedaisy.app.companion.coop.CoopInvite
import com.pokedaisy.app.companion.coop.CoopLobby
import com.pokedaisy.app.companion.coop.CoopLobbyWire
import com.pokedaisy.app.companion.coop.CoopPartner
import com.pokedaisy.app.companion.coop.CoopPeerId
import com.pokedaisy.app.companion.coop.CoopQr
import com.pokedaisy.app.companion.coop.CoopSaveId
import com.pokedaisy.app.companion.coop.CoopState
import com.pokedaisy.app.companion.data.Gen3Text
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.max
import kotlin.math.min

/*
 * CO-OP on the top screen (Settings > CO-OP): the QR code, the ACCEPT THIS PLAYER? prompt, the link's labels, the PARTNERS rows and
 * the pairing page. The page takes its state as one [CoopPairView] (CoopSettingsPages builds it), so the preview can show any step
 * without a network.
 */

/**
 * An invite as a QR code: a white quiet zone of 4 modules around black squares, each a whole number of screen pixels so it stays
 * sharp. Always black on white - a scanner needs it - whatever DARK MODE says. [m] only sizes it (about 130 GBA pixels).
 */
@Composable
fun CoopQrCode(link: String, m: GbaTextMetrics, modifier: Modifier = Modifier) {
    val modules = remember(link) { CoopQr.encode(link) }
    val n = modules.size
    Canvas(modifier.size(m.u * QR_GBA_PIXELS)) {
        val cells = n + 2 * QUIET
        val module = max(1, (min(size.width, size.height) / cells).toInt())
        val side = module * cells
        val left = ((size.width - side) / 2f).toInt().toFloat()
        val top = ((size.height - side) / 2f).toInt().toFloat()
        drawRect(Color.White, Offset(left, top), Size(side.toFloat(), side.toFloat()))
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (!modules[y][x]) { x++; continue }
                var run = x
                while (run < n && modules[y][run]) run++
                drawRect(
                    Color.Black,
                    Offset(left + (x + QUIET) * module, top + (y + QUIET) * module),
                    Size(((run - x) * module).toFloat(), module.toFloat()),
                )
                x = run
            }
        }
    }
}

private const val QUIET = 4
private const val QR_GBA_PIXELS = 130

/** The ACCEPT THIS PLAYER? prompt: the partner's name and 5-digit ID, ACCEPT or DENY (BACK denies). */
@Composable
fun CoopAcceptPrompt(partner: CoopPartner, onAccept: () -> Unit, onDeny: () -> Unit) {
    OptionConfirm(
        title = tk("ACCEPT THIS PLAYER?"),
        message = tr("{0}  ID {1}", partner.name, coopIdText(partner.trainerId)),
        confirmLabel = tk("ACCEPT"),
        m = rememberGbaTextMetrics(),
        onConfirm = onAccept,
        onDismiss = onDeny,
        cancelLabel = tk("DENY"),
    )
}

/** A trainer ID as the game's card shows it. */
fun coopIdText(trainerId: Int): String = "%05d".format(trainerId)

/** The CO-OP row's state of the link, in the game's capitals. */
fun coopLinkLabel(state: CoopState): String = when (state) {
    is CoopState.Off -> when (state.reason) {
        CoopOffReason.NOT_EMERALD -> tk("NOT EMERALD")
        CoopOffReason.NOT_PAIRED -> tk("NOT PAIRED")
        CoopOffReason.CHEATS_ON -> tk("CHEATS ON")
        CoopOffReason.NO_SAVE, CoopOffReason.NETWORK_ERROR, null -> tk("OFF")
    }
    is CoopState.Connecting -> tk("CONNECTING")
    is CoopState.Connected -> tk("CONNECTED")
    is CoopState.Disconnected -> when (state.reason) {
        CoopDropReason.CLOSED, CoopDropReason.NETWORK -> tk("RECONNECTING")
        else -> tk("NOT RECOGNISED")
    }
}

/** One row per pair: the partner's name and the save it is paired with; a tap asks UNPAIR. */
fun coopPartnerRows(pairs: List<CoopPair>, onUnpair: (CoopPair) -> Unit): List<SettingRow> =
    pairs.map { p -> SettingRow(p.partner.name, p.ownLabel) { onUnpair(p) } }

/** A save the pairing page offers: [id] null = no save made yet; [pairedWith] = the partner it already has. */
class CoopSaveChoice(val rom: File, val save: File, val gameLabel: String, val id: CoopSaveId?, val pairedWith: String?) {
    val usable: Boolean get() = id != null && pairedWith == null

    /** The trainer's name as the game writes it. */
    val trainer: String? get() = id?.let { Gen3Text.decode(it.name, 0, 7) }
}

/** The save list's rows: trainer and ID, or the game greyed with why not. */
fun coopSaveRows(choices: List<CoopSaveChoice>): List<SettingRow> = choices.map { c ->
    when {
        c.usable -> SettingRow(c.trainer.orEmpty(), "ID " + coopIdText(c.id!!.trainerId), subtitle = c.gameLabel) {}
        c.pairedWith != null -> SettingRow(c.gameLabel, null, enabled = false, subtitle = tr("Paired with {0}", c.pairedWith)) {}
        else -> SettingRow(c.gameLabel, null, enabled = false, subtitle = "Save in the game once first") {}
    }
}

/** The pairing page's actions, so the body is the same in the app and the preview. */
class CoopPairActions(
    val onPick: (CoopSaveChoice) -> Unit = {},
    val onCopy: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onJoinLink: (String) -> Unit = {},
    val onJoinLan: (CoopPeerId) -> Unit = {},
    val onJoinIp: (String) -> Unit = {},
    val onAccept: () -> Unit = {},
    val onDeny: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onTryAgain: () -> Unit = {},
    val onCancel: () -> Unit = {},
    /** LIST MY ROOM flipped. */
    val onListRoom: (Boolean) -> Unit = {},
    /** A room code to join: typed (the page checks and looks it up) or a listed room's. */
    val onJoinCode: (String) -> Unit = {},
    val onRefreshRooms: () -> Unit = {},
)

/** PAIR's panes, down its left edge: this device's room and link, joining someone else's, and the local ways in. */
enum class CoopPairTab(val label: String) { ROOM("YOUR ROOM"), JOIN("JOIN"), NEARBY("NEARBY") }

/** ROOMS: [available] = a relay to hold them; [lobby] the lobby's state (CoopSession.lobby). */
class CoopRoomsUi(val available: Boolean, val lobby: CoopLobby.State)

/**
 * Everything the pairing page shows, in one place: SettingsActivity's [com.pokedaisy.app.CoopSettingsPages] builds it from the
 * live session, ui-preview from invented data. [choices] null = still looking for saves; [picked] null = the save list; [wifi]
 * this device's address and port for BY IP; [joinError] a line under JOIN's code (a code that found nothing), [nearbyError]
 * one under BY IP's address; [tab] the pane PAIR opens on.
 */
class CoopPairView(
    val choices: List<CoopSaveChoice>? = null,
    val picked: CoopSaveChoice? = null,
    val step: CoopPairing.Step = CoopPairing.Step.Inviting(null, emptyList()),
    val relayOnly: Boolean = false,
    val wifi: String? = null,
    val rooms: CoopRoomsUi = CoopRoomsUi(available = false, lobby = CoopLobby.State()),
    val joinError: String? = null,
    val nearbyError: String? = null,
    val tab: CoopPairTab = CoopPairTab.ROOM,
) {
    /** The `pokedaisy://coop/` link once the endpoint has a ticket. */
    val link: String? get() = (step as? CoopPairing.Step.Inviting)?.ticket?.let { runCatching { CoopInvite.link(it) }.getOrNull() }

    /** NEARBY needs the devices to see each other's addresses, which RELAY ONLY hides. */
    val tabs: List<CoopPairTab> get() = if (relayOnly) listOf(CoopPairTab.ROOM, CoopPairTab.JOIN) else CoopPairTab.values().toList()
}

/** The line ROOMS shows for a lobby problem. */
fun coopLobbyProblemLine(p: CoopLobby.Problem): String = when (p) {
    CoopLobby.Problem.OFFLINE -> "Couldn't reach the relay. Trying again."
    CoopLobby.Problem.LIMIT -> "Too many tries. Wait a minute."
    CoopLobby.Problem.FULL -> "The relay is full. Try again later."
}

/** The line a code lookup that didn't find a room shows, or null for one that did. */
fun coopFindLine(r: CoopLobby.FindResult): String? = when (r) {
    is CoopLobby.FindResult.Found -> null
    CoopLobby.FindResult.NotFound -> "No open room has that code"
    CoopLobby.FindResult.BadCode -> "That isn't a room code"
    CoopLobby.FindResult.Limit -> "Too many tries. Wait a minute."
    CoopLobby.FindResult.Offline -> "Couldn't reach the relay. Try again."
}

/** The line a failed pairing shows. */
fun coopFailLine(reason: CoopPairing.CoopPairFail): String = when (reason) {
    CoopPairing.CoopPairFail.VERSION -> "Your partner's PokeDaisy is a different version. Update both to the same one."
    CoopPairing.CoopPairFail.DIFFERENT_ROM -> "Your partner plays a different Emerald ROM."
    CoopPairing.CoopPairFail.SAME_SAVE -> "That's a copy of this same save. Co-op needs two different players."
    CoopPairing.CoopPairFail.SAVE_ALREADY_PAIRED -> "This save is already paired. Unpair it in PARTNERS first."
    CoopPairing.CoopPairFail.DENIED -> "The other player said no."
    CoopPairing.CoopPairFail.NETWORK -> "Couldn't reach your partner. Check the internet and try again."
    CoopPairing.CoopPairFail.TIMEOUT -> "No answer. Try again."
    CoopPairing.CoopPairFail.BAD_INVITE -> "That invite link isn't valid"
}

/**
 * The pairing page below the title: the save list (nothing picked yet), then, while it waits for a partner, its panes on a tab
 * rail ([PairPanes]); once someone dials in, a window saying where pairing is (and ACCEPT THIS PLAYER? over it).
 */
@Composable
fun CoopPairBody(view: CoopPairView, actions: CoopPairActions, m: GbaTextMetrics, small: GbaTextMetrics) {
    if (view.picked == null) {
        CoopSavePick(m, small, view.choices, actions.onPick)
        return
    }
    val step = view.step
    if (step is CoopPairing.Step.Inviting) {
        PairPanes(view, step, actions, m, small)
        return
    }
    Box(Modifier.fillMaxSize()) {
        when (step) {
            is CoopPairing.Step.Connecting -> StatusWindow(m, tk("CONNECTING…"), null, tk("CANCEL"), actions.onCancel)
            is CoopPairing.Step.Confirm, is CoopPairing.Step.WaitingForPartner -> StatusWindow(m, tk("WAITING FOR YOUR PARTNER…"), null, null) {}
            is CoopPairing.Step.Paired -> StatusWindow(m, tr("PAIRED WITH {0}", step.pair.partner.name), null, tk("DONE"), actions.onDone)
            is CoopPairing.Step.Failed -> StatusWindow(m, null, tr(coopFailLine(step.reason)), tk("TRY AGAIN"), actions.onTryAgain)
            is CoopPairing.Step.Inviting -> Unit
        }
        // Over the whole page: the prompt's window needs the page's own height.
        if (step is CoopPairing.Step.Confirm) CoopAcceptPrompt(step.partner, actions.onAccept, actions.onDeny)
    }
}

@Composable
private fun CoopSavePick(m: GbaTextMetrics, small: GbaTextMetrics, choices: List<CoopSaveChoice>?, onPick: (CoopSaveChoice) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
            when {
                choices == null -> GbaText(
                    tr("LOADING…"), OptionColors.muted, OptionColors.mutedShadow, m,
                    modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4),
                )
                choices.isEmpty() -> Column(Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4)) {
                    GbaText(tr("NO EMERALD SAVES"), OptionColors.muted, OptionColors.mutedShadow, m)
                    Spacer(Modifier.height(m.u * 4))
                    GbaText(
                        tr("Co-op needs English Pokémon Emerald."), OptionColors.label, OptionColors.labelShadow, small,
                        maxLines = Int.MAX_VALUE,
                    )
                }
                else -> {
                    val rows = remember(choices) { coopSaveRows(choices) }
                    GroupedRows(
                        listOf(groupTitle(tk("PICK A SAVE"))) + rows, m, cursor = -1,
                        onClick = { i -> if (i > 0 && choices[i - 1].usable) onPick(choices[i - 1]) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusWindow(m: GbaTextMetrics, title: String?, line: String?, button: String?, onButton: () -> Unit) {
    OptionListWindow(m, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = m.u * 4, vertical = m.u * 2)) {
            title?.let { GbaText(tr(it), OptionColors.value, OptionColors.valueShadow, m, maxLines = Int.MAX_VALUE) }
            line?.let { GbaText(tr(it), OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE) }
            button?.let {
                Spacer(Modifier.height(m.u * 4))
                Row { OptionButton(it, m, emphasis = true, onClick = onButton) }
            }
        }
    }
}

/**
 * The invite's panes: [CoopPairTab]s on a [TabRail], one pane at a time, each short enough for one screen. What's typed into a
 * field stays while the panes change.
 */
@Composable
private fun PairPanes(view: CoopPairView, step: CoopPairing.Step.Inviting, a: CoopPairActions, m: GbaTextMetrics, small: GbaTextMetrics) {
    val tabs = view.tabs
    var tab by remember(tabs) { mutableStateOf(view.tab.takeIf { it in tabs } ?: tabs.first()) }
    val typed = remember { mutableStateOf("") }
    val pasted = remember { mutableStateOf("") }
    val address = remember { mutableStateOf("") }
    OptionListWindow(m, Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            TabRail(tabs.map { tr(it.label) }, tabs.indexOf(tab), m) { tab = tabs[it] }
            key(tab) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = m.u * 6, end = m.u * 4, bottom = m.u * 4),
                ) {
                    when (tab) {
                        CoopPairTab.ROOM -> RoomPane(view, a, m, small)
                        CoopPairTab.JOIN -> JoinPane(view, typed, pasted, a, m, small)
                        CoopPairTab.NEARBY -> NearbyPane(view, step, address, a, m, small)
                    }
                }
            }
        }
    }
}

/** YOUR ROOM: the room's code and LIST MY ROOM, beside the same invite as a QR code and a link (below it when narrow). */
@Composable
private fun RoomPane(view: CoopPairView, a: CoopPairActions, m: GbaTextMetrics, small: GbaTextMetrics) {
    BoxWithConstraints {
        if (maxWidth >= m.u * WIDE_ROOM_PANE) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 8)) {
                Column(Modifier.weight(1f)) { RoomCode(view.rooms, a, m, small) }
                InviteLink(view.link, a, m)
            }
        } else {
            Column {
                RoomCode(view.rooms, a, m, small)
                InviteLink(view.link, a, m)
            }
        }
    }
}

@Composable
private fun RoomCode(rooms: CoopRoomsUi, a: CoopPairActions, m: GbaTextMetrics, small: GbaTextMetrics) {
    Section(tk("ROOM CODE"), m)
    if (!rooms.available) {
        Note(tr("Rooms need a relay: CO-OP > RELAY"), m, small)
        return
    }
    val lobby = rooms.lobby
    val code = lobby.code
    if (code != null) GbaText(CoopLobbyWire.showCode(code), OptionColors.value, OptionColors.valueShadow, rememberGbaTextMetrics(2f))
    else GbaText(tr("CONNECTING…"), OptionColors.muted, OptionColors.mutedShadow, m)
    GbaText(
        tr("Your partner types this code under JOIN."), OptionColors.label, OptionColors.labelShadow, small,
        Modifier.padding(vertical = m.u * 2), maxLines = 3,
    )
    OptionLine(
        tk("LIST MY ROOM"), tk(if (lobby.listed) "ON" else "OFF"), selected = false, m, height = subtitledRow(m),
        subtitle = tr("Players of the same game see your trainer's name"),
        onClick = { a.onListRoom(!lobby.listed) },
    )
    lobby.problem?.let { Problem(tr(coopLobbyProblemLine(it)), m, small) }
}

/** The invite as a QR code (for the camera) and a link (to send): it works without a room code, on the same Wi-Fi too. */
@Composable
private fun InviteLink(link: String?, a: CoopPairActions, m: GbaTextMetrics) {
    Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(m.u * 3)) {
        Section(tk("INVITE LINK"), m)
        if (link != null) CoopQrCode(link, m)
        else GbaText(tr("CONNECTING…"), OptionColors.muted, OptionColors.mutedShadow, m)
        OptionButton(tk("COPY LINK"), m, enabled = link != null, onClick = a.onCopy, modifier = Modifier.fillMaxWidth())
        OptionButton(tk("SHARE LINK"), m, enabled = link != null, onClick = a.onShare, modifier = Modifier.fillMaxWidth())
    }
}

/** JOIN: a room code, the open rooms of the same game, or a pasted link. */
@Composable
private fun JoinPane(
    view: CoopPairView, typed: MutableState<String>, pasted: MutableState<String>, a: CoopPairActions, m: GbaTextMetrics, small: GbaTextMetrics,
) {
    val rooms = view.rooms
    Section(tk("JOIN A ROOM"), m)
    if (rooms.available) {
        FieldAndButton(typed, { it.take(12) }, tr("ROOM CODE"), tk("JOIN"), CoopLobbyWire.normalizeCode(typed.value) != null, m, small, code = true) {
            a.onJoinCode(typed.value)
        }
        view.joinError?.let { Problem(tr(it), m, small) }
        OpenRooms(rooms.lobby, a, m, small)
    } else {
        Note(tr("Rooms need a relay: CO-OP > RELAY"), m, small)
    }
    Section(tk("JOIN WITH A LINK"), m)
    FieldAndButton(pasted, { it }, tr("Paste an invite link"), tk("CONNECT"), pasted.value.isNotBlank(), m, small) { a.onJoinLink(pasted.value) }
}

/** OPEN ROOMS: listed rooms of the same game, newest first; REFRESH beside the title (the list refreshes itself too). */
@Composable
private fun OpenRooms(lobby: CoopLobby.State, a: CoopPairActions, m: GbaTextMetrics, small: GbaTextMetrics) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Section(tk("OPEN ROOMS"), m) }
        OptionButton(tk("REFRESH"), m, enabled = !lobby.loadingRooms, onClick = a.onRefreshRooms, modifier = Modifier.padding(top = m.u * 4))
    }
    val list = lobby.rooms
    when {
        lobby.loadingRooms && list == null -> Note(tr("SEARCHING…"), m, small)
        list == null -> Unit
        list.isEmpty() -> Note(tr("No open rooms for this game"), m, small)
        else -> list.forEach { r ->
            OptionLine(
                r.name, CoopLobbyWire.showCode(r.code), selected = false, m, chevron = true, height = subtitledRow(m),
                subtitle = if (r.waitingMinutes < 1) tr("Just opened") else tr("Waiting {0} min", r.waitingMinutes),
                onClick = { a.onJoinCode(r.code) },
            )
        }
    }
}

/** NEARBY: players on the same Wi-Fi with their PAIR page open, and BY IP for a network that hides them. */
@Composable
private fun NearbyPane(
    view: CoopPairView, step: CoopPairing.Step.Inviting, address: MutableState<String>, a: CoopPairActions, m: GbaTextMetrics, small: GbaTextMetrics,
) {
    Section(tk("SAME WI-FI"), m)
    if (step.lan.isEmpty()) Note(tr("SEARCHING…"), m, small)
    else step.lan.forEach { e -> OptionLine(e.label, null, selected = false, m, chevron = true, onClick = { a.onJoinLan(e.peer) }) }
    Section(tk("BY IP"), m)
    Note(view.wifi?.let { tr("YOUR ADDRESS") + "  " + it } ?: tr("No Wi-Fi address found"), m, small)
    FieldAndButton(address, { it }, tr("PARTNER'S ADDRESS"), tk("CONNECT"), address.value.isNotBlank(), m, small) { a.onJoinIp(address.value) }
    view.nearbyError?.let { Problem(tr(it), m, small) }
}

/** A field with its button beside it, the way every one of PAIR's inputs goes. */
@Composable
private fun FieldAndButton(
    text: MutableState<String>, clean: (String) -> String, placeholder: String, button: String, enabled: Boolean,
    m: GbaTextMetrics, small: GbaTextMetrics, code: Boolean = false, onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
        OptionTextField(text.value, { text.value = clean(it) }, small, Modifier.weight(1f), placeholder = placeholder, code = code)
        OptionButton(button, m, enabled = enabled, onClick = onClick)
    }
}

@Composable
private fun Section(title: String, m: GbaTextMetrics) {
    GbaText(tr(title), OptionColors.value, OptionColors.valueShadow, m, Modifier.padding(top = m.u * 6, bottom = m.u * 2))
}

/** A grey line under a section: what's there (SEARCHING…, none yet) or why not. */
@Composable
private fun Note(text: String, m: GbaTextMetrics, small: GbaTextMetrics) {
    GbaText(text, OptionColors.muted, OptionColors.mutedShadow, small, Modifier.padding(vertical = m.u * 2), maxLines = 3)
}

/** A red line under what went wrong. */
@Composable
private fun Problem(text: String, m: GbaTextMetrics, small: GbaTextMetrics) {
    GbaText(text, OptionColors.value, OptionColors.valueShadow, small, Modifier.padding(top = m.u * 3), maxLines = 4)
}

/** A row with a subtitle gets a line more, as GroupedRows gives it. */
private fun subtitledRow(m: GbaTextMetrics) = m.rowHeight * 1.2f + m.lineHeight

/** YOUR ROOM puts the link beside the code from this width (GBA pixels): the QR code is 130 of them. */
private const val WIDE_ROOM_PANE = 300

/** This device's IPv4 address on the Wi-Fi network (else any private one), or null. Reads interfaces: not on the UI thread. */
fun coopWifiAddress(): String? = try {
    val own = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic to it } }
        .filter { !it.second.isLoopbackAddress && !it.second.isLinkLocalAddress }
    (own.firstOrNull { it.first.name.startsWith("wlan") } ?: own.firstOrNull { it.second.isSiteLocalAddress })?.second?.hostAddress
} catch (e: java.io.IOException) {
    null
}
