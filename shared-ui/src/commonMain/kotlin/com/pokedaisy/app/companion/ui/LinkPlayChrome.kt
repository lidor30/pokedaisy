package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

fun linkGridShape(count: Int, wide: Boolean): Pair<Int, Int> = when {
    count <= 1 -> 1 to 1
    count == 2 -> if (wide) 2 to 1 else 1 to 2
    else -> 2 to 2
}

@Composable
private fun linkPlayHint(): Boolean {
    val visible by produceState(true) {
        delay(6000)
        value = false
    }
    return visible
}

@Composable
fun BoxScope.LinkPlayChrome(
    m: GbaTextMetrics,
    hint: Boolean = linkPlayHint(),
    fast: Boolean,
    onFast: () -> Unit,
    requestLeave: (() -> Unit)? = null,
) {
    if (hint) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 12.dp, end = 12.dp)) {
            OptionListWindow(m) {
                GbaText(
                    tr(if (requestLeave == null) "Tap a game to play it, or L2 / R2. BACK leaves." else "Tap a game to play it, or L2 / R2."),
                    OptionColors.label, OptionColors.labelShadow, m,
                    modifier = Modifier.padding(horizontal = m.u * 6, vertical = m.u * 3),
                )
            }
        }
    }
    requestLeave?.let {
        OptionButton(tk("BACK"), m, onClick = it, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
    OptionButton(tk("FF"), m, onClick = onFast, emphasis = fast,
        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
}

@Composable
fun LinkLeaveConfirmation(m: GbaTextMetrics, onLeave: () -> Unit, onDismiss: () -> Unit) {
    OptionConfirm(
        tk("LEAVE LINK PLAY?"), tr("Save in each game first: what you did since a game's last save is lost."),
        tk("LEAVE"), m, onConfirm = onLeave, onDismiss = onDismiss,
    )
}

@Composable
fun LinkPlayMessage(text: String, m: GbaTextMetrics) {
    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OptionTitleWindow(tk("LINK PLAY"), m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                GbaText(text, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE,
                    modifier = Modifier.padding(m.u * 4))
            }
        }
    }
}
