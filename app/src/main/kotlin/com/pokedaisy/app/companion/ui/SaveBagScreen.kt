package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.data.itemName
import com.pokedaisy.app.companion.data.lookupMove
import com.pokedaisy.app.companion.i18n.AppLanguage
import com.pokedaisy.app.companion.i18n.L10n

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.itemNames
import com.pokedaisy.app.companion.data.moveData
import com.pokedaisy.app.companion.data.save.BagPocket
import com.pokedaisy.app.companion.data.save.Gen3Legal
import com.pokedaisy.app.companion.data.save.Gen3SaveData
import com.pokedaisy.app.companion.data.save.Gen3SaveFormat
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/** The pockets in the order Emerald's bag shows them. */
private val POCKET_ORDER = listOf(BagPocket.ITEMS, BagPocket.BALLS, BagPocket.TMHM, BagPocket.BERRIES, BagPocket.KEY)

internal fun pocketLabel(p: BagPocket): String = when (p) {
    BagPocket.ITEMS -> tk("ITEMS")
    BagPocket.BALLS -> tk("POKé BALLS")
    BagPocket.TMHM -> tk("TMs & HMs")
    BagPocket.BERRIES -> tk("BERRIES")
    BagPocket.KEY -> tk("KEY ITEMS")
}

/** An item's name in capitals; a TM / HM with the move it teaches ("TM06 TOXIC"). */
internal fun bagItemLabel(id: Int): String {
    val name = if (L10n.language == AppLanguage.ZH) itemName(id) else itemNames[id]?.let(::caps) ?: "#$id"
    return Gen3Legal.tmHmMove(id)?.let { "$name ${moveLabel(it)}" } ?: name
}

internal fun moveLabel(id: Int): String =
    if (L10n.language == AppLanguage.ZH) lookupMove(id).name else moveData[id]?.name?.let(::caps) ?: "#$id"

/** A stack the player can't change: key items and HMs are handed out once and the game needs them. */
private fun fixedStack(p: BagPocket, item: Int) = p == BagPocket.KEY || (p == BagPocket.TMHM && item >= Gen3SaveFormat.FIRST_HM)

/**
 * PC BOXES' BAG page: MONEY, COINS and the pockets on one side, the open pocket's items on the other (one above the
 * other on a narrow screen). A tap opens its amount; ADD ITEM lists what the game files in that pocket.
 */
@Composable
internal fun BagPage(state: PcBoxesState, data: Gen3SaveData, m: GbaTextMetrics, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        if (maxWidth > maxHeight * 1.1f) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                PocketList(state, data, m, Modifier.weight(0.32f).fillMaxHeight())
                StackList(state, data, m, Modifier.weight(0.68f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                PocketList(state, data, m, Modifier.fillMaxWidth())
                StackList(state, data, m, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PocketList(state: PcBoxesState, data: Gen3SaveData, m: GbaTextMetrics, modifier: Modifier) {
    OptionListWindow(m, modifier) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OptionLine(tk("MONEY"), "¥${data.money}", selected = false, m, labelWeight = 0.64f, divider = true) { state.editing = PcBoxesState.Editing.Money }
            OptionLine(tk("COINS"), "${data.coins}", selected = false, m, labelWeight = 0.64f, divider = true) { state.editing = PcBoxesState.Editing.Coins }
            for (p in POCKET_ORDER) {
                OptionLine(pocketLabel(p), "${data.pocket(p).size}/${data.format.pocket(p).slots}", selected = state.pocket == p, m, labelWeight = 0.64f) {
                    state.pocket = p
                }
            }
        }
    }
}

@Composable
private fun StackList(state: PcBoxesState, data: Gen3SaveData, m: GbaTextMetrics, modifier: Modifier) {
    val p = state.pocket
    val stacks = data.pocket(p)
    OptionListWindow(m, modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GbaText(pocketLabel(p), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m, Modifier.padding(horizontal = m.u * 4))
            stacks.forEachIndexed { i, st ->
                OptionLine(
                    bagItemLabel(st.item), "×${st.quantity}", selected = false, m, labelWeight = 0.8f, divider = true,
                    enabled = !fixedStack(p, st.item),
                ) { state.editing = PcBoxesState.Editing.Stack(p, i) }
            }
            val have = stacks.map { it.item }.toSet()
            if (p != BagPocket.KEY && stacks.size < data.format.pocket(p).slots && Gen3Legal.bagItems(data.format, p).any { it !in have }) {
                OptionLine(tk("ADD ITEM"), null, selected = false, m) { state.editing = PcBoxesState.Editing.AddItem(p) }
            }
            if (p == BagPocket.KEY || p == BagPocket.TMHM) {
                GbaText(
                    tr("Key items and HMs stay as the game gave them"), OptionColors.muted, OptionColors.mutedShadow, m,
                    maxLines = Int.MAX_VALUE, modifier = Modifier.padding(m.u * 4),
                )
            }
        }
    }
}

/** What PC BOXES has open over its page: an amount, or a pick-list of items / moves, each only what the game allows. */
@Composable
internal fun PcEditOverlays(state: PcBoxesState, m: GbaTextMetrics) {
    val e = state.editing ?: return
    val data = state.data
    val f = data.format
    val close = { state.editing = null }
    when (e) {
        PcBoxesState.Editing.Money -> AmountPicker(tk("MONEY"), data.money, 0, Gen3SaveData.MAX_MONEY, m, close) { v ->
            state.edit { setMoney(v) }
        }
        PcBoxesState.Editing.Coins -> AmountPicker(tk("COINS"), data.coins.toLong(), 0, Gen3SaveData.MAX_COINS.toLong(), m, close) { v ->
            state.edit { setCoins(v.toInt()) }
        }
        is PcBoxesState.Editing.Stack -> {
            val st = data.pocket(e.pocket).getOrNull(e.index) ?: return
            AmountPicker(
                bagItemLabel(st.item), st.quantity.toLong(), 1, f.stackMax(e.pocket, st.item).toLong(), m, close,
                onRemove = { state.edit { setQuantity(e.pocket, e.index, 0) } },
            ) { v -> state.edit { setQuantity(e.pocket, e.index, v.toInt()) } }
        }
        is PcBoxesState.Editing.AddItem -> {
            val have = data.pocket(e.pocket).map { it.item }.toSet()
            OptionSelector(
                tk("ADD ITEM"), Gen3Legal.bagItems(f, e.pocket).filter { it !in have }, -1, ::bagItemLabel, m,
                onPick = { state.editing = PcBoxesState.Editing.NewStack(e.pocket, it) }, onDismiss = close,
            )
        }
        is PcBoxesState.Editing.NewStack -> AmountPicker(bagItemLabel(e.item), 1, 1, f.stackMax(e.pocket, e.item).toLong(), m, close) { v ->
            state.edit { addItem(e.pocket, e.item, v.toInt()) }
        }
        is PcBoxesState.Editing.HeldItem -> {
            val mon = data.mon(e.at)?.first ?: return
            // What it holds first (to keep it), then NONE and the rest.
            val options = remember(f, mon.heldItem) { (listOf(mon.heldItem, 0) + Gen3Legal.holdable(f)).distinct() }
            OptionSelector(
                tk("ITEM"), options, mon.heldItem, { if (it == 0) tk("NONE") else bagItemLabel(it) }, m,
                onPick = { item -> state.edit { setHeldItem(e.at, item) } }, onDismiss = close,
            )
        }
        is PcBoxesState.Editing.Move -> {
            val (mon, level) = data.mon(e.at) ?: return
            val known = mon.moves.filter { it != 0 }
            val current = known.getOrNull(e.slot)
            val learnable = remember(f, mon.species, level) { Gen3Legal.learnable(f, mon.species, level) }
            // The move it has (to keep it), FORGET while another move is left, then every move it may know that it
            // doesn't already, by name.
            val options = listOfNotNull(current) + (if (current != null && known.size > 1) listOf(FORGET) else emptyList()) +
                learnable.filter { it !in known }.sortedBy(::moveLabel)
            OptionSelector(
                monLabel(mon), options, current ?: -1, { if (it == FORGET) tk("FORGET") else moveLabel(it) }, m,
                onPick = { pick ->
                    val moves = known.toMutableList()
                    when {
                        pick == FORGET -> moves.removeAt(e.slot)
                        e.slot < moves.size -> moves[e.slot] = pick
                        else -> moves += pick
                    }
                    state.edit { setMoves(e.at, moves) }
                },
                onDismiss = close,
            )
        }
    }
}

/** The move picker's FORGET row (no move has this id). */
private const val FORGET = -2

/**
 * An amount from [min] to [max]: typed, or stepped with the buttons; OK takes it. [onRemove] adds REMOVE (a bag stack
 * taken out).
 */
@Composable
internal fun AmountPicker(
    title: String, value: Long, min: Long, max: Long, m: GbaTextMetrics, onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null, onDone: (Long) -> Unit,
) {
    var text by remember(title, value) { mutableStateOf(value.toString()) }
    val n = text.toLongOrNull()
    val ok = n != null && n in min..max
    fun step(by: Long) { text = ((n ?: value) + by).coerceIn(min, max).toString() }
    // Three steps that fit the range: 1 / 10 / 100 for 999, 100 / 1,000 / 10,000 for money.
    val steps = listOf(1L, 10L, 100L, 1_000L, 10_000L).filter { it * 10 <= max }.takeLast(3).ifEmpty { listOf(1L) }
    val u = m.u
    OptionOverlay(onDismiss, Modifier.widthIn(max = 480.dp)) {
        Column {
            OptionTitleWindow(title, m, onBack = onDismiss)
            Spacer(Modifier.height(u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(u * 4), verticalArrangement = Arrangement.spacedBy(u * 4)) {
                    OptionTextField(text, { t -> text = t.filter(Char::isDigit).take(max.toString().length) }, m, number = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(u * 4)) {
                        OptionButton(tk("MIN"), m, onClick = { text = min.toString() }, modifier = Modifier.weight(1f))
                        for (s in steps.reversed()) OptionButton("-$s", m, onClick = { step(-s) }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(u * 4)) {
                        for (s in steps) OptionButton("+$s", m, onClick = { step(s) }, modifier = Modifier.weight(1f))
                        OptionButton(tk("MAX"), m, onClick = { text = max.toString() }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(u * 4)) {
                        onRemove?.let { OptionButton(tk("REMOVE"), m, onClick = it, modifier = Modifier.weight(1f)) }
                        OptionButton(tk("CANCEL"), m, onClick = onDismiss, modifier = Modifier.weight(1f))
                        OptionButton(tk("OK"), m, onClick = { n?.let(onDone) }, emphasis = true, enabled = ok, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
