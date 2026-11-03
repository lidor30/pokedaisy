package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pokedaisy.app.companion.data.Gen3Text
import com.pokedaisy.app.companion.data.natureName
import com.pokedaisy.app.companion.data.save.BagPocket
import com.pokedaisy.app.companion.data.save.BoxSlot
import com.pokedaisy.app.companion.data.save.Gen3Legal
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import com.pokedaisy.app.companion.data.save.Gen3SaveData
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.SPECIES_EGG
import com.pokedaisy.app.companion.data.speciesNames
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/**
 * PC BOXES (the library's game menu, top screen): a save's party and its 14 PC boxes, read from
 * the save file while the game is closed. A box Pokémon can be moved to any other slot (swapping
 * with what's there); a Pokémon's held item and moves, the money, coins and the bag can be changed
 * to what the game allows ([com.pokedaisy.app.companion.data.save.Gen3Legal]). SAVE writes it the
 * way the game saves ([Gen3SaveEditor.build]) through the activity's [onSave]. The party is shown,
 * not moved: party records carry stats the PC's don't.
 */
class PcBoxesState(editor: Gen3SaveEditor) {
    var editor = editor
        private set
    /** Bumped on every edit: [data] is re-read from the editor's blocks. */
    var revision by mutableIntStateOf(0)
        private set
    var box by mutableIntStateOf(editor.data.currentBox.coerceIn(0, Gen3SaveData.BOXES - 1))
    var selected by mutableStateOf<MonRef?>(null)
    /** MOVE was pressed on this box slot: the next slot tapped is where it goes. */
    var moving by mutableStateOf<BoxSlot?>(null)
    var confirmSave by mutableStateOf(false)
    var confirmDiscard by mutableStateOf(false)
    var page by mutableStateOf(Page.BOXES)
    /** The BAG page's open pocket. */
    var pocket by mutableStateOf(BagPocket.ITEMS)
    /** A pick-list or amount over the page, if one is open. */
    var editing by mutableStateOf<Editing?>(null)

    enum class Page { BOXES, BAG }

    sealed class Editing {
        object Money : Editing()
        object Coins : Editing()
        /** A stack's amount (stack [index] of [pocket]). */
        data class Stack(val pocket: BagPocket, val index: Int) : Editing()
        /** ADD ITEM's pick-list, then the amount of the [item] picked. */
        data class AddItem(val pocket: BagPocket) : Editing()
        data class NewStack(val pocket: BagPocket, val item: Int) : Editing()
        data class HeldItem(val at: MonRef) : Editing()
        /** Move [slot] (0-3; one past the last move = a new one) of the Pokémon at [at]. */
        data class Move(val at: MonRef, val slot: Int) : Editing()
    }

    /** Runs an edit on the save; one the editor refuses (never offered by the screens) changes nothing. Closes [editing]. */
    fun edit(change: Gen3SaveEditor.() -> Unit) {
        if (runCatching { editor.change() }.isSuccess) revision++
        editing = null
    }

    val data: Gen3SaveData get() { revision; return editor.data }
    val changed: Boolean get() { revision; return editor.changed }

    fun tap(at: BoxSlot) {
        val from = moving
        if (from != null) {
            val target = data.mon(at)
            if (at != from && !(target.isBadEgg && !target.isEmpty)) {
                editor.move(from, at)
                revision++
            }
            moving = null
            selected = MonRef.Pc(at)
            return
        }
        selected = if (data.mon(at).isEmpty) null else MonRef.Pc(at)
    }

    /** Back to the save as read: a fresh editor over the same file. */
    fun undoAll() {
        editor = Gen3SaveEditor(editor.file)
        moving = null
        editing = null
        revision++
    }
}

@Composable
fun PcBoxesScreen(
    state: PcBoxesState,
    gameName: String,
    m: GbaTextMetrics,
    onBack: () -> Unit,
    onSave: () -> Unit,
    /** Busy writing: the buttons wait. */
    saving: Boolean = false,
    /** The party icons from the game's ROM ([com.pokedaisy.app.companion.data.save.RomMonIcons]); null = names only. */
    icons: MonIconLoader? = null,
    /** TRADE: pick another game's save to trade with (only while nothing here is unsaved). */
    onTrade: (() -> Unit)? = null,
) {
    val small = rememberGbaTextMetrics(textScale = 1f)
    // The box's cards: a step denser, so ten-letter names fit six across.
    val cell = rememberGbaTextMetrics(textScale = 0.8f)
    val data = state.data
    val leave = { if (state.moving != null) state.moving = null else if (state.changed) state.confirmDiscard = true else onBack() }
    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OptionTitleWindow(tk("SAVE EDITOR"), m, trailing = gameName, onBack = leave)
            Spacer(Modifier.height(m.u * 4))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                for (p in PcBoxesState.Page.entries) {
                    OptionButton(
                        if (p == PcBoxesState.Page.BOXES) tk("BOXES") else tk("BAG"), m,
                        onClick = { state.page = p; state.moving = null }, emphasis = state.page == p,
                    )
                }
            }
            Spacer(Modifier.height(m.u * 4))
            if (state.page == PcBoxesState.Page.BAG) BagPage(state, data, m, Modifier.weight(1f).fillMaxWidth())
            else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > maxHeight * 1.1f
                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                        Column(Modifier.weight(0.32f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                            PartyList(state, data, cell, Modifier.fillMaxWidth())
                            DetailWindow(state, data, small, Modifier.weight(1f).fillMaxWidth())
                        }
                        BoxWindow(state, data, m, cell, icons, Modifier.weight(0.68f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                        BoxWindow(state, data, m, cell, icons, Modifier.fillMaxWidth().weight(0.55f))
                        PartyWindow(state, data, small, icons, Modifier.fillMaxWidth())
                        DetailWindow(state, data, small, Modifier.weight(0.45f).fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(m.u * 4))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), verticalAlignment = Alignment.CenterVertically) {
                // The trainer the save belongs to, unless there's something to say.
                val boxes = state.page == PcBoxesState.Page.BOXES
                val hint = when {
                    state.moving != null -> tr("Tap where it goes - any box")
                    state.changed -> tr("Not saved yet")
                    boxes && state.selected?.let { data.mon(it)?.first?.let(::editableMon) } == true -> tr("Tap its item or a move to change it")
                    else -> "${data.trainerName} · ${tr("ID No.")} ${id5(data.trainerId)} · ¥${data.money} · ${data.playTime}"
                }
                BackdropText(hint, small, Modifier.weight(1f))
                val pick = (state.selected as? MonRef.Pc)?.at?.takeIf { boxes && data.mon(it).let { mon -> !mon.isEmpty && !mon.isBadEgg } }
                if (state.moving != null) {
                    OptionButton(tk("CANCEL"), m, onClick = { state.moving = null })
                } else {
                    if (pick != null) OptionButton(tk("MOVE"), m, onClick = { state.moving = pick }, emphasis = true, enabled = !saving)
                    if (onTrade != null && !state.changed) OptionButton(tk("TRADE"), m, onClick = onTrade, enabled = !saving)
                    OptionButton(tk("UNDO ALL"), m, onClick = { state.undoAll() }, enabled = state.changed && !saving)
                    OptionButton(tk("SAVE"), m, onClick = { state.confirmSave = true }, emphasis = true, enabled = state.changed && !saving)
                }
            }
        }

        if (state.confirmSave) {
            OptionConfirm(
                title = tk("SAVE CHANGES?"),
                message = tr("Written the way the game saves: the save as it is now stays in the file as the game's fallback, and a copy goes to pokedaisy-backups. The game starts from the save next time, not from a resume state."),
                confirmLabel = tk("SAVE"),
                m = m,
                onDismiss = { state.confirmSave = false },
                onConfirm = { state.confirmSave = false; onSave() },
            )
        }
        if (state.confirmDiscard) {
            OptionConfirm(
                title = tk("DISCARD CHANGES?"),
                message = tr("Your changes aren't saved. Leave without saving them?"),
                confirmLabel = tk("DISCARD"),
                m = m,
                onDismiss = { state.confirmDiscard = false },
                onConfirm = { state.confirmDiscard = false; onBack() },
            )
        }
        PcEditOverlays(state, m)
    }
}

/** The party as two columns of three rows (wide screens): name and level, the selected one the white cursor row. */
@Composable
private fun PartyList(state: PcBoxesState, data: Gen3SaveData, m: GbaTextMetrics, modifier: Modifier) {
    val party = data.party
    OptionListWindow(m, modifier) {
        Column {
            GbaText(tr("PARTY"), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m, Modifier.padding(horizontal = m.u * 4))
            for (row in 0 until 3) Row {
                for (col in 0 until 2) {
                    val i = row * 2 + col
                    val p = party.getOrNull(i)
                    val selected = state.selected == MonRef.Party(i)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).height(m.lineHeight * 1.2f)
                            .background(if (selected) OptionColors.rowSelected else androidx.compose.ui.graphics.Color.Transparent)
                            .soundClickable { if (p != null && state.moving == null) state.selected = MonRef.Party(i) }
                            .padding(horizontal = m.u * 4),
                    ) {
                        if (p != null) {
                            GbaText(monLabel(p.mon), OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f))
                            if (!p.mon.isEgg && !p.mon.isBadEgg) GbaText("Lv${p.level}", OptionColors.value, OptionColors.valueShadow, m)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartyWindow(state: PcBoxesState, data: Gen3SaveData, m: GbaTextMetrics, icons: MonIconLoader?, modifier: Modifier) {
    OptionListWindow(m, modifier) {
        Column {
            GbaText(tr("PARTY"), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m, Modifier.padding(horizontal = m.u * 4))
            val party = data.party
            for (row in 0 until 2) Row {
                for (col in 0 until 3) {
                    val i = row * 3 + col
                    val p = party.getOrNull(i)
                    MonCell(
                        p?.mon, p?.level, m, Modifier.weight(1f), icons,
                        selected = state.selected == MonRef.Party(i),
                        onClick = { if (p != null && state.moving == null) state.selected = MonRef.Party(i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxWindow(state: PcBoxesState, data: Gen3SaveData, m: GbaTextMetrics, cell: GbaTextMetrics, icons: MonIconLoader?, modifier: Modifier) {
    BoxGrid(
        data, state.box, { state.box = it }, m, cell, icons, modifier,
        selected = { state.selected == MonRef.Pc(it) || state.moving == it },
        target = { state.moving != null && state.moving != it },
        onTap = { state.tap(it) },
    )
}

/** Party icons, decoded on demand (blocking: called off the UI thread) and cached by whoever loads them. */
fun interface MonIconLoader {
    fun load(species: Int): android.graphics.Bitmap?
}

/**
 * One PC box of [data] as the game lays it out (6 x 5), its name between box arrows ([onBox] gets the new box); a
 * [title] over it (whose save, on the trade screen). Shared by PC BOXES and TRADE.
 */
@Composable
internal fun BoxGrid(
    data: Gen3SaveData, box: Int, onBox: (Int) -> Unit, m: GbaTextMetrics, cell: GbaTextMetrics, icons: MonIconLoader?,
    modifier: Modifier, title: String? = null,
    selected: (BoxSlot) -> Boolean, target: (BoxSlot) -> Boolean, onTap: (BoxSlot) -> Unit,
) {
    val b = data.boxes[box]
    OptionListWindow(cell, modifier) {
        Column(Modifier.fillMaxSize()) {
            title?.let { GbaText(it, OptionColors.groupTitleText, OptionColors.groupTitleShadow, cell, Modifier.padding(horizontal = m.u * 4)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                OptionButton(null, m, onClick = { onBox((box + Gen3SaveData.BOXES - 1) % Gen3SaveData.BOXES) },
                    pixelIcon = PixelIcons.cursorLeft, modifier = Modifier.semantics { contentDescription = tr("Previous box") })
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    GbaText(b.name.ifBlank { "BOX ${box + 1}" }, OptionColors.label, OptionColors.labelShadow, m)
                }
                OptionButton(null, m, onClick = { onBox((box + 1) % Gen3SaveData.BOXES) },
                    pixelIcon = PixelIcons.cursorRight, modifier = Modifier.semantics { contentDescription = tr("Next box") })
            }
            Spacer(Modifier.height(cell.u * 2))
            Column(Modifier.weight(1f).fillMaxWidth()) {
                for (row in 0 until 5) Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0 until 6) {
                        val at = BoxSlot(box, row * 6 + col)
                        MonCell(
                            b.mons[at.slot].takeIf { !it.isEmpty }, null, cell, Modifier.weight(1f).fillMaxHeight(), icons,
                            selected = selected(at), target = target(at), onClick = { onTap(at) },
                        )
                    }
                }
            }
        }
    }
}

/** A Pokémon's party icon from [loader], first frame, pixel-scaled to [size]. An egg is the egg's icon. */
@Composable
internal fun BoxMonIcon(loader: MonIconLoader, mon: Gen3BoxMon, size: Dp) {
    val species = if (mon.isEgg) SPECIES_EGG else mon.species
    val bmp by produceState<ImageBitmap?>(null, loader, species) {
        value = withContext(Dispatchers.IO) { runCatching { loader.load(species) }.getOrNull()?.let { cropToFirstFrame(it).asImageBitmap() } }
    }
    Box(Modifier.size(size)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.size(size), filterQuality = FilterQuality.None) }
    }
}

/**
 * One slot: a Pokémon's card - its icon over its name when the ROM gave icons, else its name and level - with the
 * selected one framed in red; an empty slot is an outline (red while a MOVE / trade is looking for a place).
 */
@Composable
internal fun MonCell(
    mon: Gen3BoxMon?, partyLevel: Int?, m: GbaTextMetrics, modifier: Modifier, icons: MonIconLoader?,
    selected: Boolean, target: Boolean = false, onClick: () -> Unit,
) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    val withIcon = icons != null && mon != null && !mon.isBadEgg
    Box(
        modifier
            .padding(u * 0.5f)
            .drawBehind {
                val r = 2 * u.toPx()
                when {
                    mon != null -> drawPixelRoundRect(OptionColors.titleFill, radius = r)
                    target -> drawPixelRoundFrame(OptionColors.value.copy(alpha = 0.6f), Offset.Zero, size, r, u.toPx())
                    else -> drawPixelRoundFrame(OptionColors.divider, Offset.Zero, size, r, u.toPx())
                }
                if (selected) drawPixelRoundFrame(OptionColors.value, Offset.Zero, size, r, 1.5f * u.toPx())
            }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick)
            .padding(horizontal = u * 2, vertical = u * 2),
        contentAlignment = if (withIcon) Alignment.Center else Alignment.CenterStart,
    ) {
        if (mon == null) return@Box
        val level = if (mon.isBadEgg || mon.isEgg) "" else "Lv${partyLevel ?: mon.level}"
        if (withIcon) BoxWithConstraints {
            // The icon with its level beside it, the name under both; the icon takes what the name's line leaves
            // (up to 2 lines' height: 2x on the Thor).
            val iconSize = (maxHeight - m.lineHeight).coerceIn(m.lineHeight, m.lineHeight * 2)
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    BoxMonIcon(icons!!, mon, iconSize)
                    Spacer(Modifier.weight(1f))
                    if (level.isNotEmpty()) GbaText(level, OptionColors.value, OptionColors.valueShadow, m)
                }
                GbaText(monLabel(mon), OptionColors.label, OptionColors.labelShadow, m)
            }
        } else Column {
            GbaText(monLabel(mon), if (mon.isBadEgg) OptionColors.muted else OptionColors.label, OptionColors.labelShadow, m)
            if (level.isNotEmpty()) GbaText(level, OptionColors.value, OptionColors.valueShadow, m)
        }
    }
}

@Composable
private fun DetailWindow(state: PcBoxesState, data: Gen3SaveData, m: GbaTextMetrics, modifier: Modifier) {
    val sel = state.selected
    val found = sel?.let { data.mon(it) }
    val editable = sel != null && found != null && editableMon(found.first) && state.moving == null
    MonDetail(
        found?.first, found?.second, m, modifier, tr("Tap a Pokémon to see it, then MOVE to put it in another slot."),
        onItem = if (editable && !Gen3Legal.isMail(found!!.first.heldItem)) ({ state.editing = PcBoxesState.Editing.HeldItem(sel!!) }) else null,
        onMove = if (editable) ({ slot -> state.editing = PcBoxesState.Editing.Move(sel!!, slot) }) else null,
    )
}

/** What PC BOXES lets you change: not an egg, not a Bad Egg. */
internal fun editableMon(mon: Gen3BoxMon): Boolean = !mon.isEmpty && !mon.isBadEgg && !mon.isEgg

/**
 * A Pokémon's details as `LABEL  VALUE` rows ([empty] when there's none). Shared by PC BOXES and TRADE. With [onItem] /
 * [onMove] the ITEM row and the moves (and an ADD MOVE row after them) can be tapped.
 */
@Composable
internal fun MonDetail(
    mon: Gen3BoxMon?, level: Int?, m: GbaTextMetrics, modifier: Modifier, empty: String,
    onItem: (() -> Unit)? = null, onMove: ((Int) -> Unit)? = null,
) {
    OptionListWindow(m, modifier) {
        if (mon == null) {
            GbaText(empty, OptionColors.muted, OptionColors.mutedShadow, m, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(m.u * 4))
            return@OptionListWindow
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val rows = buildList<Triple<String, String, (() -> Unit)?>> {
                fun row(label: String, value: String, onClick: (() -> Unit)? = null) = add(Triple(label, value, onClick))
                row(tk("NAME"), monLabel(mon))
                if (mon.isBadEgg) {
                    row(tk("STATE"), tk("BAD EGG"))
                } else {
                    // The species when a nickname hides it.
                    if (!mon.isEgg && monLabel(mon) != speciesLabel(mon.species)) row(tk("SPECIES"), speciesLabel(mon.species))
                    if (!mon.isEgg) {
                        row(tk("LEVEL"), "${level ?: mon.level}")
                        row(tk("NATURE"), caps(natureName(mon.nature)))
                        row(tk("ITEM"), if (mon.heldItem == 0) tk("NONE") else bagItemLabel(mon.heldItem), onItem)
                        val moves = mon.moves.filter { it != 0 }
                        moves.forEachIndexed { i, id -> row(if (i == 0) tk("MOVES") else "", moveLabel(id), onMove?.let { { it(i) } }) }
                        if (onMove != null && moves.size < 4) row(if (moves.isEmpty()) tk("MOVES") else "", tr("ADD MOVE"), { onMove(moves.size) })
                    }
                    row(tk("OT"), "${otName(mon)} · ${id5((mon.otId and 0xFFFF).toInt())}")
                    row(tk("FROM"), gameLabel(mon.metGame))
                }
            }
            rows.forEach { (label, value, onClick) ->
                OptionLine(label, value, selected = false, m, height = m.lineHeight * 1.1f, labelWeight = 0.3f, onClick = onClick ?: {})
            }
        }
    }
}

/** A trainer ID as the game prints it, five digits. (Not String.format around names: the games' charset has a %.) */
private fun id5(id: Int): String = id.toString().padStart(5, '0')

internal fun caps(s: String): String = buildString(s.length) { for (c in s) append(if (c == 'é') c else c.uppercaseChar()) }

internal fun speciesLabel(id: Int): String = speciesNames[id]?.let(::caps) ?: "#$id"

/** The nickname as the game shows it; a Japanese game's (language 1) is in kana the Western table can't read, so its species. */
internal fun monLabel(mon: Gen3BoxMon): String = when {
    mon.isBadEgg -> tk("BAD EGG")
    mon.isEgg -> tk("EGG")
    mon.language == 1 -> speciesLabel(mon.species)
    else -> Gen3Text.decode(mon.nicknameBytes, 0, 10).trim().ifEmpty { speciesLabel(mon.species) }
}

private fun otName(mon: Gen3BoxMon): String = if (mon.language == 1) "-" else Gen3Text.decode(mon.otNameBytes, 0, 7).trim()

private fun gameLabel(game: Int): String = when (game) {
    1 -> "SAPPHIRE"; 2 -> "RUBY"; 3 -> "EMERALD"; 4 -> "FIRERED"; 5 -> "LEAFGREEN"; 15 -> "COLOSSEUM / XD"
    else -> "?"
}
