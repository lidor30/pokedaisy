package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.NewFeatures
import com.pokedaisy.app.companion.coop.lock.CoopLockActions
import com.pokedaisy.app.companion.coop.lock.CoopLockUi
import com.pokedaisy.app.companion.coop.lock.LockRefusal
import com.pokedaisy.app.companion.coop.lock.LockWait
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/*
 * PLAY TOGETHER ONLY on screen: its row in the CO-OP group, the partner's proposal and the waiting overlay. Everything takes its
 * state and actions as arguments (the running game's CoopLock feeds them), so the preview can show any state.
 */

/** The row's grey line: why it can't be turned on, or that we're waiting for the partner. Null when it is on or can be turned on. */
fun coopLockSubtitle(ui: CoopLockUi): String? = when {
    ui.locked -> null
    ui.proposalSent -> "Waiting for your partner to accept"
    ui.partnerProposed -> null
    else -> when (ui.refusal) {
        LockRefusal.NOT_PAIRED -> "Pair with a partner first"
        LockRefusal.NOT_CONNECTED -> "Your partner must be connected"
        LockRefusal.OWN_NOT_NEW -> "You already have a POKéMON"
        LockRefusal.PARTNER_NOT_NEW -> "Your partner already has a POKéMON"
        null -> null
    }
}

/** Whether a tap on the row does anything: turning off, answering a proposal, or asking while nothing refuses it. */
fun coopLockTappable(ui: CoopLockUi): Boolean =
    ui.locked || ui.partnerProposed || (ui.refusal == null && !ui.proposalSent)

/** What a tap on the row asks (null = nothing). */
sealed interface CoopLockAsk {
    val title: String
    val message: String
    val confirmLabel: String
    val cancelLabel: String
    val onConfirm: () -> Unit
    val onCancel: () -> Unit
}

private class Ask(
    override val title: String, override val message: String, override val confirmLabel: String, override val cancelLabel: String,
    override val onConfirm: () -> Unit, override val onCancel: () -> Unit = {},
) : CoopLockAsk

/** The confirm a tap on the row opens: the partner's proposal first, then turning off, else turning on. */
fun coopLockAsk(ui: CoopLockUi, actions: CoopLockActions, partnerName: String): CoopLockAsk? = when {
    ui.partnerProposed -> Ask(
        tk("PLAY TOGETHER ONLY"), tr("{0} wants to PLAY TOGETHER ONLY.", partnerName), tk("ACCEPT"), tk("NO"),
        actions::accept, actions::decline,
    )
    ui.locked -> Ask(
        tk("PLAY TOGETHER ONLY"), tr("Turn off PLAY TOGETHER ONLY? It can't be turned on again for these games."), tk("YES"), tk("NO"),
        actions::turnOff,
    )
    !coopLockTappable(ui) -> null
    else -> Ask(
        tk("PLAY TOGETHER ONLY"),
        tr("Turn on PLAY TOGETHER ONLY for both games? You can only play while your partner is connected, and story events wait for both of you."),
        tk("YES"), tk("NO"), actions::propose,
    )
}

/**
 * The CO-OP group's PLAY TOGETHER ONLY row. With no [ui] (a game that has no lock, a preview) it is hidden; a tap hands
 * [ask] the confirm to show (the settings screen draws it).
 */
fun coopLockRow(ui: CoopLockUi?, actions: CoopLockActions?, partnerName: String, ask: (CoopLockAsk) -> Unit): SettingRow? {
    if (ui == null || actions == null) return null
    return SettingRow(
        tk("PLAY TOGETHER ONLY"), if (ui.locked) tk("ON") else tk("OFF"),
        enabled = coopLockTappable(ui), labelBadge = NewFeatures.badge(NewFeatures.COOP), subtitle = coopLockSubtitle(ui), labelWeight = 0.8f,
    ) { coopLockAsk(ui, actions, partnerName)?.let(ask) }
}

/**
 * The partner's proposal as its own prompt (the game's overlay shows it whenever the partner asks): ACCEPT / NO.
 * BACK or a tap outside only calls [onDismiss] (the proposal stays); only NO declines.
 */
@Composable
fun CoopLockProposal(partnerName: String, actions: CoopLockActions, onDismiss: () -> Unit = {}) {
    OptionConfirm(
        title = tk("PLAY TOGETHER ONLY"),
        message = tr("{0} wants to PLAY TOGETHER ONLY.", partnerName),
        confirmLabel = tk("ACCEPT"),
        m = rememberGbaTextMetrics(),
        onConfirm = actions::accept,
        onDismiss = onDismiss,
        cancelLabel = tk("NO"),
        onCancel = actions::decline,
    )
}

/** The overlay's lines by wait: the partner's absence, or an event both must reach. Never names the event. */
fun coopWaitLines(wait: LockWait, partnerName: String): List<String> = when (wait) {
    LockWait.Partner -> listOf(tr("Waiting for your partner..."))
    is LockWait.AtStory -> listOf(tr("Waiting for {0} to get here.", partnerName), tr("Step away to cancel."))
}

/**
 * The waiting screen over the game. Partner: the game is held anyway, so a dim over it and CLOSE GAME. AtStory: a small
 * window at the bottom that takes no touches, so the player can walk away (that is the cancel).
 */
@Composable
fun CoopWaitOverlay(wait: LockWait, partnerName: String, onCloseGame: () -> Unit) {
    val m = rememberGbaTextMetrics()
    val lines = coopWaitLines(wait, partnerName)
    when (wait) {
        LockWait.Partner -> {
            OptionConfirm(
                title = tk("PLAY TOGETHER ONLY"),
                message = lines.first(),
                confirmLabel = tk("CLOSE GAME"),
                m = m,
                onConfirm = onCloseGame,
                onDismiss = {},
                cancelLabel = null,
            )
        }
        is LockWait.AtStory -> Box(Modifier.fillMaxSize().padding(bottom = m.u * 8), contentAlignment = Alignment.BottomCenter) {
            OptionListWindow(m, Modifier.widthIn(max = 480.dp)) {
                Column(Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4), verticalArrangement = Arrangement.spacedBy(m.u * 2)) {
                    GbaText(lines[0], OptionColors.label, OptionColors.labelShadow, m, maxLines = 2)
                    GbaText(lines[1], OptionColors.value, OptionColors.valueShadow, m, maxLines = 1)
                }
            }
        }
    }
}
