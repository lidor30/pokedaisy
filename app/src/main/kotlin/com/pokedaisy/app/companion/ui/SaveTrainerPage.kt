package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pokedaisy.app.companion.data.save.Gen3Event
import com.pokedaisy.app.companion.data.save.Gen3SaveData
import com.pokedaisy.app.companion.data.save.TrainerBagEditor
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/**
 * The SAVE EDITOR's TRAINER page: the trainer (name, ID, play time, money, Gen 3's coins), the Pokédex (counts, and
 * marking it) and, in Gen 3, the event tickets the games' ports honour ([Gen3Event]), each given the way its distribution
 * gave it.
 */
@Composable
internal fun TrainerPage(state: PcBoxesState, bag: TrainerBagEditor, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val gen3 = state.gen3?.let { state.data3 }
    BoxWithConstraints(modifier) {
        if (maxWidth > maxHeight * 1.1f) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                // Without Gen 3's tickets beside it, the trainer's window is only as tall as its lines.
                TrainerWindow(state, bag, gen3, m, Modifier.weight(0.4f).let { if (gen3 != null) it.fillMaxHeight() else it })
                Column(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                    DexWindow(state, bag, m, small, Modifier.fillMaxWidth())
                    if (gen3 != null) EventsWindow(state, gen3, m, small, Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                TrainerWindow(state, bag, gen3, m, Modifier.fillMaxWidth())
                DexWindow(state, bag, m, small, Modifier.fillMaxWidth())
                if (gen3 != null) EventsWindow(state, gen3, m, small, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TrainerWindow(state: PcBoxesState, bag: TrainerBagEditor, gen3: Gen3SaveData?, m: GbaTextMetrics, modifier: Modifier) {
    val data = state.data
    OptionListWindow(m, modifier) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GbaText(tr("TRAINER"), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m, Modifier.padding(horizontal = m.u * 4))
            OptionLine(tk("NAME"), data.trainerName, selected = false, m, labelWeight = 0.5f, divider = true) {}
            OptionLine(tk("ID No."), id5(data.trainerId), selected = false, m, labelWeight = 0.5f, divider = true) {}
            bag.playTime?.let { OptionLine(tk("TIME"), it, selected = false, m, labelWeight = 0.5f, divider = true) {} }
            OptionLine(tk("MONEY"), "¥${bag.money}", selected = false, m, labelWeight = 0.5f, divider = gen3 != null, chevron = true) {
                state.editing = PcBoxesState.Editing.Money
            }
            if (gen3 != null) OptionLine(tk("COINS"), "${gen3.coins}", selected = false, m, labelWeight = 0.5f, chevron = true) {
                state.editing = PcBoxesState.Editing.Coins
            }
        }
    }
}

@Composable
private fun DexWindow(state: PcBoxesState, bag: TrainerBagEditor, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val c = bag.dex
    OptionListWindow(m, modifier) {
        Column {
            GbaText(tr("POKéDEX"), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m, Modifier.padding(horizontal = m.u * 4))
            c.regionLabel?.let {
                OptionLine(tk(it), tr("{0} SEEN · {1} CAUGHT", c.regionalSeen, c.regionalCaught), selected = false, m, labelWeight = 0.3f, divider = true) {}
            }
            OptionLine(tk("NATIONAL"), tr("{0} SEEN · {1} CAUGHT", c.seen, c.caught), selected = false, m, labelWeight = 0.3f) {}
            Row(Modifier.fillMaxWidth().padding(m.u * 3), horizontalArrangement = Arrangement.spacedBy(m.u * 3)) {
                for ((label, kind) in listOf(
                    tk("ADD MINE") to PcBoxesState.DexAction.REGISTER_OWNED,
                    tk("ALL SEEN") to PcBoxesState.DexAction.ALL_SEEN,
                    tk("ALL CAUGHT") to PcBoxesState.DexAction.ALL_CAUGHT,
                )) {
                    OptionButton(label, small, onClick = { state.editing = PcBoxesState.Editing.Dex(kind) }, modifier = Modifier.weight(1f), sidePadding = 2)
                }
            }
        }
    }
}

@Composable
private fun EventsWindow(state: PcBoxesState, data: Gen3SaveData, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val events = Gen3Event.forFormat(data.format)
    OptionListWindow(m, modifier) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GbaText(tr("EVENT TICKETS"), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m, Modifier.padding(horizontal = m.u * 4))
            events.forEachIndexed { i, ev ->
                val st = state.gen3!!.eventState(ev)
                OptionLine(
                    bagItemLabel(ev.item),
                    when (st) { Gen3Event.State.AVAILABLE -> tk("GET"); Gen3Event.State.HAS -> tk("IN BAG"); Gen3Event.State.DONE -> tk("USED") },
                    selected = false, m, labelWeight = 0.62f, divider = i < events.lastIndex, chevron = st == Gen3Event.State.AVAILABLE,
                    enabled = st == Gen3Event.State.AVAILABLE,
                ) { state.editing = PcBoxesState.Editing.Event(ev) }
            }
            events.firstOrNull()?.let {
                GbaText(it.note(data.format), OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE,
                    modifier = Modifier.padding(horizontal = m.u * 4, vertical = m.u * 2))
            }
        }
    }
}
