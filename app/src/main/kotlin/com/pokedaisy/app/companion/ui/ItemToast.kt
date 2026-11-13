package com.pokedaisy.app.companion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** A bag item's totals by id: the same item can sit in two pockets (a TM's case), and only the total matters. */
internal fun itemTotals(items: List<ItemView>): Map<Int, Int> =
    items.filter { it.itemId > 0 }.groupBy { it.itemId }.mapValues { (_, same) -> same.sumOf { it.quantity } }

/** The items that went up from [before] to [after], with how many more of each. */
internal fun itemGains(before: Map<Int, Int>, after: Map<Int, Int>): Map<Int, Int> =
    after.mapNotNull { (id, q) ->
        val was = before[id] ?: 0
        if (q > was) id to (q - was) else null
    }.toMap()

private class ItemGain(val item: ItemView, val count: Int)

/**
 * A small "OBTAINED" card at the bottom left when the bag gains an item (a pickup, a purchase, a PC withdrawal, a held
 * item taken back), fading in, resting, and fading out. One at a time; a snapshot that brings several queues them.
 * [reset] bumped on a savestate load starts the baseline over, so a load isn't read as a pile of gains.
 */
@Composable
fun ItemToastHost(
    items: List<ItemView>,
    connected: Boolean,
    hasData: Boolean,
    reset: Int,
    modifier: Modifier = Modifier,
) {
    val m = rememberGbaTextMetrics(1f)
    val small = rememberGbaTextMetrics(0.8f)
    val queue = remember { Channel<ItemGain>(Channel.UNLIMITED) }
    var current by remember { mutableStateOf<ItemGain?>(null) }
    var visible by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf<Map<Int, Int>?>(null) }
    var ready by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(items)
    LaunchedEffect(connected, hasData, reset) {
        ready = false
        previous = null
        current = null
        visible = false
        while (queue.tryReceive().isSuccess) {}
        if (!connected || !hasData) return@LaunchedEffect
        // The game's own bag load, or a savestate's, is not an acquisition: let it settle, then take it as the baseline.
        delay(900)
        previous = itemTotals(latest)
        ready = true
    }
    LaunchedEffect(items, connected, hasData) {
        if (!connected || !hasData || !ready) return@LaunchedEffect
        // A bag write can touch the array in a few steps (a purchase): compare the settled bag, not the half-written one.
        delay(160)
        val now = itemTotals(items)
        val before = previous
        previous = now
        if (before == null) return@LaunchedEffect
        for ((id, count) in itemGains(before, now)) {
            latest.firstOrNull { it.itemId == id }?.let { queue.trySend(ItemGain(it, count)) }
        }
    }
    LaunchedEffect(Unit) {
        for (gain in queue) {
            current = gain
            visible = true
            delay(2200)
            visible = false
            delay(600)
        }
    }
    Box(modifier) {
        AnimatedVisibility(
            visible = visible && current != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { it / 3 },
            exit = fadeOut(tween(600)) + slideOutVertically(tween(600)) { it / 3 },
        ) {
            current?.let { ItemGainCard(it, m, small) }
        }
    }
}

@Composable
private fun ItemGainCard(gain: ItemGain, m: GbaTextMetrics, small: GbaTextMetrics) {
    val u = m.u
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 210.dp)
            // A thin title-window frame: the list frame's seven pixels would eat into a card this small.
            .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 2 * u.toPx()) }
            .padding(start = u * 6, end = u * 6, top = u * 4, bottom = u * 5),
    ) {
        ItemIcon(gain.item.iconAsset, size = m.lineHeight * 1.35f, pixelated = true)
        Spacer(Modifier.width(u * 5))
        Column(Modifier.weight(1f, fill = false)) {
            GbaText(tr("OBTAINED"), OptionColors.muted, OptionColors.mutedShadow, small)
            GbaText(gain.item.name, OptionColors.label, OptionColors.labelShadow, m, maxLines = 1)
        }
        if (gain.count > 1) {
            Spacer(Modifier.width(u * 6))
            GbaText("×${gain.count}", OptionColors.value, OptionColors.valueShadow, small)
        }
    }
}
