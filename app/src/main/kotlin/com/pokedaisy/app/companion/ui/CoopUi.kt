package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * the pairing pages' bodies. The pieces take their state as arguments (SettingsActivity holds it), so the preview can show any step
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
)

/** The line a failed pairing shows. */
fun coopFailLine(reason: CoopPairing.CoopPairFail): String = when (reason) {
    CoopPairing.CoopPairFail.VERSION -> "Your partner's PokeDaisy is a different version. Update both to the same one."
    CoopPairing.CoopPairFail.DIFFERENT_ROM -> "Your partner plays a different Emerald ROM."
    CoopPairing.CoopPairFail.SAVE_ALREADY_PAIRED -> "This save is already paired. Unpair it in PARTNERS first."
    CoopPairing.CoopPairFail.DENIED -> "The other player said no."
    CoopPairing.CoopPairFail.NETWORK -> "Couldn't reach your partner. Check the internet and try again."
    CoopPairing.CoopPairFail.TIMEOUT -> "No answer. Try again."
    CoopPairing.CoopPairFail.BAD_INVITE -> "That invite link isn't valid"
}

/**
 * The pairing page below the title: the save list (nothing picked yet), then the invite and what follows it. [choices] null = still
 * looking; [ticketLink] is the `pokedaisy://coop/` link once the endpoint has a ticket; [wifi] this device's Wi-Fi address and port
 * for BY IP (null: none found); [error] a line under the fields.
 */
@Composable
fun CoopPairBody(
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    choices: List<CoopSaveChoice>?,
    picked: CoopSaveChoice?,
    step: CoopPairing.Step,
    ticketLink: String?,
    relayOnly: Boolean,
    wifi: String?,
    error: String?,
    actions: CoopPairActions,
) {
    if (picked == null) {
        CoopSavePick(m, small, choices, actions.onPick)
        return
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when (step) {
            is CoopPairing.Step.Inviting -> Invite(m, small, step, ticketLink, relayOnly, wifi, error, actions)
            is CoopPairing.Step.Connecting -> StatusWindow(m, tk("CONNECTING…"), null, tk("CANCEL"), actions.onCancel)
            is CoopPairing.Step.Confirm -> StatusWindow(m, tk("WAITING FOR YOUR PARTNER…"), null, null) {}
            is CoopPairing.Step.WaitingForPartner -> StatusWindow(m, tk("WAITING FOR YOUR PARTNER…"), null, null) {}
            is CoopPairing.Step.Paired -> StatusWindow(m, tr("PAIRED WITH {0}", step.pair.partner.name), null, tk("DONE"), actions.onDone)
            is CoopPairing.Step.Failed -> StatusWindow(m, null, tr(coopFailLine(step.reason)), tk("TRY AGAIN"), actions.onTryAgain)
        }
    }
    // Over the whole page, not inside its scrolling column: the prompt's window needs the page's own height.
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

@Composable
private fun Invite(
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    step: CoopPairing.Step.Inviting,
    link: String?,
    relayOnly: Boolean,
    wifi: String?,
    error: String?,
    a: CoopPairActions,
) {
    var pasted by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    OptionListWindow(m, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = m.u * 2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 6), verticalAlignment = Alignment.Top) {
                if (link != null) CoopQrCode(link, m)
                else GbaText(tr("CONNECTING…"), OptionColors.muted, OptionColors.mutedShadow, m, Modifier.padding(vertical = m.u * 4))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                    OptionButton(tk("COPY LINK"), m, enabled = link != null, onClick = a.onCopy, modifier = Modifier.fillMaxWidth())
                    OptionButton(tk("SHARE LINK"), m, enabled = link != null, onClick = a.onShare, modifier = Modifier.fillMaxWidth())
                }
            }
            Section(tk("JOIN WITH A LINK"), m)
            OptionTextField(pasted, { pasted = it }, small, placeholder = tr("Paste an invite link"))
            Spacer(Modifier.height(m.u * 3))
            Row { OptionButton(tk("CONNECT"), m, enabled = pasted.isNotBlank(), onClick = { a.onJoinLink(pasted) }) }
            if (!relayOnly) {
                Section(tk("SAME WI-FI"), m)
                if (step.lan.isEmpty()) {
                    GbaText(tr("SEARCHING…"), OptionColors.muted, OptionColors.mutedShadow, small, Modifier.padding(vertical = m.u * 2))
                } else {
                    step.lan.forEach { e ->
                        OptionLine(e.label, null, selected = false, m, chevron = true, onClick = { a.onJoinLan(e.peer) })
                    }
                }
                Section(tk("BY IP"), m)
                if (wifi != null) {
                    GbaText(
                        tr("YOUR ADDRESS") + "  " + wifi, OptionColors.label, OptionColors.labelShadow, small,
                        Modifier.padding(vertical = m.u * 2),
                    )
                } else {
                    GbaText(tr("No Wi-Fi address found"), OptionColors.muted, OptionColors.mutedShadow, small, Modifier.padding(vertical = m.u * 2))
                }
                OptionTextField(address, { address = it }, small, placeholder = tr("PARTNER'S ADDRESS"))
                Spacer(Modifier.height(m.u * 3))
                Row { OptionButton(tk("CONNECT"), m, enabled = address.isNotBlank(), onClick = { a.onJoinIp(address) }) }
            }
            error?.let {
                GbaText(tr(it), OptionColors.value, OptionColors.valueShadow, small, Modifier.padding(top = m.u * 4), maxLines = 4)
            }
            Spacer(Modifier.height(m.u * 2))
        }
    }
}

@Composable
private fun Section(title: String, m: GbaTextMetrics) {
    GbaText(tr(title), OptionColors.value, OptionColors.valueShadow, m, Modifier.padding(top = m.u * 6, bottom = m.u * 2))
}

/** This device's IPv4 address on the Wi-Fi network (else any private one), or null. Reads interfaces: not on the UI thread. */
fun coopWifiAddress(): String? = try {
    val own = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic to it } }
        .filter { !it.second.isLoopbackAddress && !it.second.isLinkLocalAddress }
    (own.firstOrNull { it.first.name.startsWith("wlan") } ?: own.firstOrNull { it.second.isSiteLocalAddress })?.second?.hostAddress
} catch (e: java.io.IOException) {
    null
}
