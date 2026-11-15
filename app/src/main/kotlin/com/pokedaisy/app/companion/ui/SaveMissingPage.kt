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
                            best?.let { whereIn(it, view.sources) } ?: tk("NONE"),
                            selected = false, m, height = if (best != null) rowHeight else m.rowHeight, labelWeight = 0.55f, divider = true,
                            // Which game it's in, then its level and the way it comes, or why it can't yet.
                            subtitle = best?.let {
                                val game = view.sources[it.source].label
                                it.blocker?.let { why -> "$game: $why" }
                                    ?: "$game · " + tr("Lv{0} · {1}", it.level, if (it.palPark) tk("PAL PARK") else tk("TRADE"))
                            },
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

/** Where in its save it is: its box's name, or PARTY. */
private fun whereIn(o: MissingDex.Owned, sources: List<TradeSide>): String = when (val at = o.at) {
    is MonRef.Party -> tr("PARTY")
    is MonRef.Pc -> sources[o.source].editor.data.boxName(at.at.box).ifBlank { "BOX ${at.at.box + 1}" }
}
