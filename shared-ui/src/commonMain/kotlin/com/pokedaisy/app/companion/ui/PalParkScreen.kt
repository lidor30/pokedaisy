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
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.Gen4SaveEditor
import com.pokedaisy.app.companion.data.save.MonSave
import com.pokedaisy.app.companion.data.save.PalPark
import com.pokedaisy.app.companion.data.save.SaveEditRefused
import com.pokedaisy.app.companion.data.save.TradeSide
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/**
 * PAL PARK: a GBA game's PC beside a HeartGold / SoulSilver / Platinum one. Six Pokémon picked in the GBA game's boxes
 * go over together (MIGRATE), with the DS games' own rules ([PalPark]); where they land is framed on the DS side. Like
 * TRADE, it piles up in both saves until SAVE writes the two together.
 */
class PalParkState(gba: TradeSide, ds: TradeSide) {
    var gba = gba
        private set
    var ds = ds
        private set
    var revision by mutableIntStateOf(0)
        private set
    var boxGba by mutableIntStateOf(gba.editor.data.currentBox.coerceIn(0, gba.editor.data.boxCount - 1))
    var boxDs by mutableIntStateOf(ds.editor.data.currentBox.coerceIn(0, ds.editor.data.boxCount - 1))
    /** The GBA PC slots picked, in the order they were picked. */
    val picks = mutableStateListOf<BoxSlot>()
    /** Where the last six landed in the DS game's PC. */
    var arrived by mutableStateOf<List<BoxSlot>>(emptyList())
    /** Why the last pick or MIGRATE didn't happen; or what the last MIGRATE did. */
    var notice by mutableStateOf<String?>(null)
    var confirmSave by mutableStateOf(false)
    var confirmDiscard by mutableStateOf(false)

    private val ed3: Gen3SaveEditor get() = gba.editor as Gen3SaveEditor
    private val ed4: Gen4SaveEditor get() = ds.editor as Gen4SaveEditor

    val dataGba: MonSave get() { revision; return gba.editor.data }
    val dataDs: MonSave get() { revision; return ds.editor.data }
    val changed: Boolean get() { revision; return gba.editor.changed || ds.editor.changed }

    /** Why the two games can't use Pal Park at all (the National Pokédex, the language, six in the PC). */
    val linkBlocker: String? get() { revision; return PalPark.linkBlocker(ed3, gba.gameCode, gba.label, ed4, ds.label) }

    /** A GBA slot tapped: picked, or unpicked; refused with the game's reason. */
    fun toggle(at: BoxSlot) {
        notice = null
        if (at in picks) { picks.remove(at); return }
        val mon = ed3.data.mon(at)
        if (mon.isEmpty) return
        PalPark.blocker(mon)?.let { notice = it; return }
        if (picks.size >= PalPark.COUNT) { notice = tr("Six are picked: tap one to put it back"); return }
        picks += at
    }

    fun migrate() {
        notice = try {
            val names = picks.map { monLabel(ed3.data.mon(it)) }
            arrived = PalPark.migrate(ed3, gba.gameCode, gba.label, picks.toList(), ed4, ds.label)
            picks.clear()
            arrived.firstOrNull()?.let { boxDs = it.box }
            tr("In {0}'s PC now: {1}", ds.label, names.joinToString(", "))
        } catch (e: SaveEditRefused) {
            e.message
        }
        revision++
    }

    fun undoAll() {
        gba = TradeSide(gba.label, gba.editor.fresh(), gba.gameCode)
        ds = TradeSide(ds.label, ds.editor.fresh(), ds.gameCode)
        picks.clear()
        arrived = emptyList()
        notice = null
        revision++
    }
}

@Composable
fun PalParkScreen(
    state: PalParkState,
    m: GbaTextMetrics,
    onBack: () -> Unit,
    onSave: () -> Unit,
    saving: Boolean = false,
    iconsGba: MonIconLoader? = null,
    iconsDs: MonIconLoader? = null,
) {
    val small = rememberGbaTextMetrics(textScale = 1f)
    val cell = rememberGbaTextMetrics(textScale = 0.8f)
    val leave = { if (state.changed) state.confirmDiscard = true else onBack() }
    val link = state.linkBlocker
    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OptionTitleWindow(tk("PAL PARK"), m, trailing = "${state.gba.label} · ${state.ds.label}", onBack = leave)
            Spacer(Modifier.height(m.u * 4))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > maxHeight * 1.1f
                val from: @Composable (Modifier) -> Unit = { mod ->
                    BoxGrid(
                        state.dataGba, state.boxGba, { state.boxGba = it }, m, cell, iconsGba, mod,
                        title = tr("{0} · PC", state.gba.label),
                        selected = { it in state.picks }, target = { false }, onTap = { state.toggle(it) },
                    )
                }
                val to: @Composable (Modifier) -> Unit = { mod ->
                    BoxGrid(
                        state.dataDs, state.boxDs, { state.boxDs = it }, m, cell, iconsDs, mod,
                        title = tr("{0} · PC", state.ds.label),
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
                    n == PalPark.COUNT -> tr("Six picked: MIGRATE brings them to {0}", state.ds.label)
                    n > 0 -> tr("{0} of 6 picked", n)
                    state.changed -> tr("Not saved yet")
                    else -> tr("Pick six Pokémon in {0}'s PC to bring to {1}", state.gba.label, state.ds.label)
                }
                val warn = link != null || (state.notice != null && state.arrived.isEmpty())
                GbaText(hint, if (warn) OptionColors.value else OptionColors.onBackdrop,
                    if (warn) OptionColors.valueShadow else OptionColors.onBackdropShadow, small, Modifier.weight(1f), maxLines = 2)
                OptionButton(tk("MIGRATE"), m, onClick = { state.migrate() }, emphasis = true,
                    enabled = link == null && n == PalPark.COUNT && !saving)
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
                message = tr("The Pokémon you brought over aren't saved. Leave without saving?"),
                confirmLabel = tk("DISCARD"),
                m = m,
                onDismiss = { state.confirmDiscard = false },
                onConfirm = { state.confirmDiscard = false; onBack() },
            )
        }
    }
}
