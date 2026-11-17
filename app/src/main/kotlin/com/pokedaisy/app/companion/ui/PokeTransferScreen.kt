package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.save.BoxSlot
import com.pokedaisy.app.companion.data.save.Gen4SaveEditor
import com.pokedaisy.app.companion.data.save.Gen5SaveEditor
import com.pokedaisy.app.companion.data.save.PokeTransfer
import com.pokedaisy.app.companion.data.save.SaveEditRefused
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.data.localNow
import kotlinx.datetime.LocalDate

/**
 * POKé TRANSFER: a HeartGold / SoulSilver / Platinum save's PC beside a Black / White (or sequel) one. Six Pokémon picked
 * in the Gen 4 game's boxes go over together (TRANSFER), by the Poké Transfer Lab's rules ([PokeTransfer]); where they
 * land is framed on the Gen 5 side. Like PAL PARK, it piles up in both saves until SAVE writes the two together.
 */
class PokeTransferState(
    val gen4Label: String, gen4: Gen4SaveEditor,
    val gen5Label: String, gen5: Gen5SaveEditor,
) {
    var gen4 = gen4
        private set
    var gen5 = gen5
        private set
    var revision by mutableIntStateOf(0)
        private set
    var boxGen4 by mutableIntStateOf(gen4.data.currentBox.coerceIn(0, gen4.data.boxCount - 1))
    var boxGen5 by mutableIntStateOf(gen5.data.currentBox)
    /** The Gen 4 PC slots picked, in the order they were picked. */
    val picks = mutableStateListOf<BoxSlot>()
    /** Where the last six landed in the Gen 5 PC. */
    var arrived by mutableStateOf<List<BoxSlot>>(emptyList())
    var notice by mutableStateOf<String?>(null)
    var confirmSave by mutableStateOf(false)
    var confirmDiscard by mutableStateOf(false)
    /** The day the Pokémon are met at Poké Transfer (the device's). */
    var today: () -> LocalDate = { localNow().date }

    val changed: Boolean get() { revision; return gen4.changed || gen5.changed }
    val linkBlocker: String? get() { revision; return PokeTransfer.linkBlocker(gen4, gen4Label, gen5, gen5Label) }

    fun toggle(at: BoxSlot) {
        notice = null
        if (at in picks) { picks.remove(at); return }
        val mon = gen4.data.mon(at)
        if (mon.isEmpty) return
        PokeTransfer.blocker(mon, gen4.game)?.let { notice = it; return }
        if (picks.size >= PokeTransfer.COUNT) { notice = tr("Six are picked: tap one to put it back"); return }
        picks += at
    }

    fun transfer() {
        notice = try {
            val names = picks.map { monLabel(gen4.data.mon(it)) }
            arrived = PokeTransfer.transfer(gen4, gen4Label, picks.toList(), gen5, gen5Label, today())
            picks.clear()
            arrived.firstOrNull()?.let { boxGen5 = it.box }
            tr("In {0}'s PC now: {1}", gen5Label, names.joinToString(", "))
        } catch (e: SaveEditRefused) {
            e.message
        }
        revision++
    }

    fun undoAll() {
        gen4 = gen4.fresh() as Gen4SaveEditor
        gen5 = gen5.fresh()
        picks.clear()
        arrived = emptyList()
        notice = null
        revision++
    }
}

@Composable
fun PokeTransferScreen(
    state: PokeTransferState,
    m: GbaTextMetrics,
    onBack: () -> Unit,
    onSave: () -> Unit,
    saving: Boolean = false,
    iconsGen4: MonIconLoader? = null,
    iconsGen5: MonIconLoader? = null,
) {
    val small = rememberGbaTextMetrics(textScale = 1f)
    val cell = rememberGbaTextMetrics(textScale = 0.8f)
    val leave = { if (state.changed) state.confirmDiscard = true else onBack() }
    val link = state.linkBlocker
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OptionTitleWindow(tk("POKé TRANSFER"), m, trailing = "${state.gen4Label} · ${state.gen5Label}", onBack = leave)
            Spacer(Modifier.height(m.u * 4))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > maxHeight * 1.1f
                val from: @Composable (Modifier) -> Unit = { mod ->
                    BoxGrid(
                        state.gen4.data, state.boxGen4, { state.boxGen4 = it }, m, cell, iconsGen4, mod,
                        title = tr("{0} · PC", state.gen4Label),
                        selected = { it in state.picks }, target = { false }, onTap = { state.toggle(it) },
                    )
                }
                val to: @Composable (Modifier) -> Unit = { mod ->
                    BoxGrid(
                        state.gen5.data, state.boxGen5, { state.boxGen5 = it }, m, cell, iconsGen5, mod,
                        title = tr("{0} · PC", state.gen5Label),
                        selected = { it in state.arrived }, target = { false }, onTap = {},
                    )
                }
                if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                    from(Modifier.weight(1f).fillMaxHeight())
                    to(Modifier.weight(1f).fillMaxHeight())
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                    from(Modifier.weight(1f).fillMaxWidth())
                    to(Modifier.weight(1f).fillMaxWidth())
                }
            }
            Spacer(Modifier.height(m.u * 4))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), verticalAlignment = Alignment.CenterVertically) {
                val n = state.picks.size
                val hint = link ?: state.notice ?: when {
                    n == PokeTransfer.COUNT -> tr("Six picked: TRANSFER sends them to {0}", state.gen5Label)
                    n > 0 -> tr("{0} of 6 picked", n)
                    state.changed -> tr("Not saved yet")
                    else -> tr("Pick six Pokémon in {0}'s PC to send to {1}", state.gen4Label, state.gen5Label)
                }
                val warn = link != null || (state.notice != null && state.arrived.isEmpty())
                GbaText(hint, if (warn) OptionColors.value else OptionColors.onBackdrop,
                    if (warn) OptionColors.valueShadow else OptionColors.onBackdropShadow, small, Modifier.weight(1f), maxLines = 2)
                OptionButton(tk("TRANSFER"), m, onClick = { state.transfer() }, emphasis = true,
                    enabled = link == null && n == PokeTransfer.COUNT && !saving)
                OptionButton(tk("UNDO ALL"), m, onClick = { state.undoAll() }, enabled = state.changed && !saving)
                OptionButton(tk("SAVE"), m, onClick = { state.confirmSave = true }, emphasis = true, enabled = state.changed && !saving)
            }
        }
        if (state.confirmSave) {
            OptionConfirm(
                title = tk("SAVE BOTH?"),
                message = tr("Both saves are written the way the game saves, each backed up to pokedaisy-backups first. If the second can't be written, the first is put back, so no Pokémon is lost or copied."),
                confirmLabel = tk("SAVE"),
                m = m,
                onDismiss = { state.confirmSave = false },
                onConfirm = { state.confirmSave = false; onSave() },
            )
        }
        if (state.confirmDiscard) {
            OptionConfirm(
                title = tk("DISCARD CHANGES?"),
                message = tr("The Pokémon you sent over aren't saved. Leave without saving?"),
                confirmLabel = tk("DISCARD"),
                m = m,
                onDismiss = { state.confirmDiscard = false },
                onConfirm = { state.confirmDiscard = false; onBack() },
            )
        }
    }
}
