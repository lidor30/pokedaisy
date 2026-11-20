package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.i18n.tr

/*
 * Emerald co-op battles: the join prompt, the waits and the notices, in the OPTION look. A joined battle runs the game's own
 * link battle on both devices, so the battle itself (menus, party, bag) is always the game's. The glue fills the state holders,
 * nothing here talks to the game.
 */

/** The partner's join prompt: [kind] 1 = a wild battle, 2 = two trainers, 3 = one trainer. */
class CoopJoinPromptState {
    var hostName by mutableStateOf("")
    var kind by mutableStateOf(1)
    var secondsLeft by mutableStateOf(30)
    var cursorJoin by mutableStateOf(true)
}

private val game get() = activeGame ?: GameKind.EMERALD

/** The backdrop and a column of windows, for every co-op screen. */
@Composable
private fun CoopFrame(title: String, trailing: String? = null, onBack: (() -> Unit)? = null, content: @Composable (GbaTextMetrics) -> Unit) {
    val m = rememberGbaTextMetrics(1f)
    Box(Modifier.fillMaxSize()) {
        GameBackdrop(game)
        Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).fillMaxWidth().padding(m.u * 8)) {
            OptionTitleWindow(title, m, trailing = trailing, onBack = onBack, titleMaxLines = 2)
            Spacer(Modifier.height(m.u * 4))
            content(m)
        }
    }
}

@Composable
private fun CoopMessage(text: String, m: GbaTextMetrics) {
    OptionListWindow(m, Modifier.fillMaxWidth()) {
        GbaText(text, OptionColors.label, OptionColors.labelShadow, m, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(horizontal = m.u * 8, vertical = m.u * 6))
    }
}

@Composable
fun CoopJoinPrompt(state: CoopJoinPromptState, onJoin: () -> Unit, onDecline: () -> Unit) {
    CompanionBackHandler(onBack = onDecline)
    CoopFrame(tr("{0} STARTED A BATTLE NEARBY.", state.hostName), trailing = "${state.secondsLeft}") { m ->
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            Column {
                OptionLine(tr("BATTLE"), when (state.kind) { 1 -> tr("WILD"); 3 -> tr("TRAINER"); else -> tr("TRAINERS") }, selected = false, m, divider = true, labelWeight = 0.5f, onClick = {})
                OptionLine(tr("JOIN"), null, selected = state.cursorJoin, m, divider = true, onClick = onJoin)
                OptionLine(tr("NOT NOW"), null, selected = !state.cursorJoin, m, onClick = onDecline)
            }
        }
    }
}

@Composable
fun CoopHostWaiting(partnerName: String, secondsLeft: Int, onCancel: () -> Unit) {
    CompanionBackHandler(onBack = onCancel)
    CoopFrame(tr("ASKING {0} TO JOIN...", partnerName), trailing = "$secondsLeft") { m ->
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            OptionLine(tr("CANCEL"), null, selected = true, m, onClick = onCancel)
        }
    }
}

@Composable
fun CoopLinkWait(partnerName: String, secondsLeft: Int) {
    CoopFrame(tr("WAITING FOR {0}...", partnerName), trailing = "$secondsLeft") { m ->
        CoopMessage(tr("The link dropped. The battle goes on when it is back."), m)
    }
}

@Composable
fun CoopNotice(text: String) {
    val m = rememberGbaTextMetrics(1f)
    Box(Modifier.fillMaxSize()) {
        GameBackdrop(game)
        Box(Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(m.u * 8)) {
            OptionTitleWindow(text, m, titleMaxLines = 4)
        }
    }
}
