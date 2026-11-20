package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.LinkSetupFlow
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

@Composable
fun <T : Any> LinkSetupScreen(
    flow: LinkSetupFlow<T>, m: GbaTextMetrics, starting: Boolean,
    label: (T) -> String, key: (T) -> String,
    onPick: (Int) -> Unit, onStart: () -> Unit, onClose: () -> Unit,
) {
    val slots = flow.slots
    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OptionTitleWindow(tk("LINK PLAY"), m, onBack = onClose.takeUnless { starting })
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column {
                    LinkSlots(
                        slots.indices.map { i -> tr("GAME {0}", i + 1) to flow.rowLabel(i, label, key) },
                        canAdd = slots.size < LinkSetupFlow.MAX_GAMES && !starting, m,
                        onPick = { if (!starting) onPick(it) }, onAdd = { onPick(slots.size) },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    GbaText(
                        tr("Up to four GBA games run at once, joined as if by a link cable: trade, battle and mix records at the games' own Pokémon Centers. Tap a game to play it (L2 / R2 on a controller). The same game twice plays its second copy on a save of its own."),
                        OptionColors.label, OptionColors.labelShadow, rememberGbaTextMetrics(0.8f), maxLines = Int.MAX_VALUE,
                        modifier = Modifier.padding(m.u * 4),
                    )
                }
            }
            Spacer(Modifier.height(m.u * 4))
            OptionButton(
                if (starting) tr("STARTING…") else tr("START LINK"), m,
                modifier = Modifier.align(Alignment.End), emphasis = true,
                enabled = flow.canStart && !starting, onClick = onStart,
            )
        }
        flow.choice?.takeUnless { starting }?.let { pick ->
            val options: List<T?> = if (pick.slot < slots.size) pick.games + listOf(null) else pick.games
            OptionSelector(
                tk("PICK A GAME"), options, slots.getOrNull(pick.slot), { it?.let(label) ?: tk("REMOVE") }, m,
                onPick = { flow.choose(pick, it) }, onDismiss = { flow.dismiss(pick) },
            )
        }
    }
}
