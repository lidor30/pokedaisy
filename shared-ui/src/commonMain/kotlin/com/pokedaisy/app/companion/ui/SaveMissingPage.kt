package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.save.MissingDex
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.data.save.TradeSide
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/** MISSING's page as the SAVE EDITOR shows it: null entries while the other saves are being read. */
class MissingView(val entries: List<MissingDex.Entry>?, val sources: List<TradeSide>)

/** Which species MISSING lists: all the dex hasn't caught, or only those another save can send. */
enum class MissingFilter { ALL, CAN_COME }

/**
 * MISSING: the Pokémon this save's Pokédex hasn't caught (its regional dex until the National one), each with the one in
 * the player's other saves to bring first - a tap opens TRADE (or PAL PARK, a GBA game's PC into a DS game) with it
 * picked. One that can't come says why: the games' own trade and Pal Park rules.
 */
@Composable
internal fun MissingPage(
    state: PcBoxesState, view: MissingView, m: GbaTextMetrics, small: GbaTextMetrics,
    onGet: (MissingDex.Owned) -> Unit, onClose: () -> Unit,
) {
    val entries = view.entries
    val subtitleLine = subtitleMetrics(m).lineHeight + m.u * 2
    val shown = entries?.let { list -> if (state.missingFilter == MissingFilter.CAN_COME) list.filter { it.best?.reachable == true } else list }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OptionTitleWindow(
            tk("MISSING"), m, onBack = onClose,
            trailing = entries?.let { tr("{0} NOT CAUGHT", it.size) },
        )
        Spacer(Modifier.height(m.u * 4))
        Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), verticalAlignment = Alignment.CenterVertically) {
            for (f in MissingFilter.entries) {
                OptionButton(
                    when (f) { MissingFilter.ALL -> tk("ALL"); MissingFilter.CAN_COME -> tk("IN MY SAVES") },
                    m, onClick = { state.missingFilter = f }, emphasis = state.missingFilter == f,
                    modifier = Modifier.focusStart(state.missingFilter == f),
                )
            }
            Spacer(Modifier.weight(1f))
            if (entries != null) BackdropText(tr("{0} CAN COME", entries.count { it.best?.reachable == true }), small)
        }
        Spacer(Modifier.height(m.u * 4))
        OptionListWindow(m, Modifier.weight(1f).fillMaxWidth()) {
            when {
                shown == null -> Message(tr("Reading your other saves…"), m)
                entries.isEmpty() -> Message(tr("Every Pokémon this Pokédex lists is caught."), m)
                shown.isEmpty() -> Message(tr("None of your other saves can send one of them yet."), m)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    // Room for the line under each row (its level and the way it comes, or why it can't).
                    val rowHeight = m.rowHeight + subtitleLine
                    items(shown, key = { it.species }) { e ->
                        val best = e.best
                        OptionLine(
                            "No.${e.number.toString().padStart(3, '0')} ${MissingDex.speciesName(state.editor, e.species)?.let(::caps) ?: "#${e.species}"}",
                            best?.let { whereIn(it, state, view.sources) } ?: tk("NONE"),
                            selected = false, m, height = if (best != null) rowHeight else m.rowHeight, labelWeight = 0.55f, divider = true,
                            // Which game it's in, then its level and the way it comes, or why it can't yet.
                            subtitle = best?.let { wayLine(it, view.sources) },
                            enabled = best?.reachable == true, chevron = best?.reachable == true,
                        ) { best?.takeIf { it.reachable }?.let(onGet) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, m: GbaTextMetrics) {
    Box(Modifier.fillMaxWidth().padding(m.u * 4)) {
        GbaText(text, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE)
    }
}

/**
 * The line under a row: which game it's in, its level and how it comes - or, when it's one that evolves on the way,
 * which Pokémon makes the trip - or why it can't yet.
 */
private fun wayLine(o: MissingDex.Owned, sources: List<TradeSide>): String {
    val game = sources[o.source].label
    val how = when (o.way) {
        MissingDex.Way.TRADE -> tr("{0} · Lv{1} · TRADE", game, o.level)
        MissingDex.Way.PAL_PARK -> tr("{0} · Lv{1} · PAL PARK", game, o.level)
        MissingDex.Way.EVOLVES -> tr("{0} · {1} Lv{2} evolves in the trade", game, monLabel(o.mon), o.level)
        MissingDex.Way.TRADE_BACK -> tr("Your {0} Lv{1} · trade it to {2} and back", monLabel(o.mon), o.level, game)
        MissingDex.Way.POKE_TRANSFER -> tr("{0} · Lv{1} · POKé TRANSFER", game, o.level)
    }
    return o.blocker?.let { why -> "$game: $why" } ?: how
}

/** Where in its save it is: its box's name, or PARTY (a trade-back's in this save). */
private fun whereIn(o: MissingDex.Owned, state: PcBoxesState, sources: List<TradeSide>): String = when (val at = o.at) {
    is MonRef.Party -> tr("PARTY")
    is MonRef.Pc -> {
        val data = if (o.way == MissingDex.Way.TRADE_BACK) state.editor.data else sources[o.source].editor.data
        data.boxName(at.at.box).ifBlank { "BOX ${at.at.box + 1}" }
    }
}
