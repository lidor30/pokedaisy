package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.pokedaisy.app.companion.data.save.PocketKind
import com.pokedaisy.app.companion.data.save.SavePocket
import com.pokedaisy.app.companion.data.save.TrainerBagEditor
import com.pokedaisy.app.companion.data.save.Gen3Legal
import com.pokedaisy.app.companion.data.save.Gen3SaveData
import com.pokedaisy.app.companion.data.save.Gen3SaveFormat
import com.pokedaisy.app.companion.data.save.BoxSlot
import com.pokedaisy.app.companion.data.save.Gen3Names
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.Gen3Species
import com.pokedaisy.app.companion.data.save.Gen3StatLimits
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.data.save.BoxSort
import com.pokedaisy.app.companion.data.save.Gen3GameData
import com.pokedaisy.app.companion.data.save.SaveGameData
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

internal fun pocketLabel(p: PocketKind): String = when (p) {
    PocketKind.ITEMS -> tk("ITEMS")
    PocketKind.MEDICINE -> tk("MEDICINE")
    PocketKind.BALLS -> tk("POKé BALLS")
    PocketKind.TMHM -> tk("TMs & HMs")
    PocketKind.BERRIES -> tk("BERRIES")
    PocketKind.MAIL -> tk("MAIL")
    PocketKind.BATTLE -> tk("BATTLE ITEMS")
    PocketKind.KEY -> tk("KEY ITEMS")
}

/** An item's name in capitals; a TM / HM with the move it teaches ("TM06 TOXIC"), in [game]'s names. */
internal fun bagItemLabel(id: Int, game: SaveGameData = Gen3GameData, tmMove: (Int) -> Int? = Gen3Legal::tmHmMove): String {
    val name = game.itemName(id)?.let(::caps) ?: "#$id"
    return tmMove(id)?.let { "$name ${moveLabel(it, game)}" } ?: name
}

/** [bagItemLabel] for the save being edited. */
internal fun PcBoxesState.itemText(id: Int): String = bagItemLabel(id, data.game, bag::tmMove)

internal fun moveLabel(id: Int, game: SaveGameData = Gen3GameData): String = game.moveName(id)?.let(::caps) ?: "#$id"

/**
 * The SAVE EDITOR's BAG page: the pockets on one side, the open pocket's items on the other (one above the
 * other on a narrow screen). A tap opens its amount; ADD ITEM lists what the game files in that pocket.
 */
@Composable
internal fun BagPage(state: PcBoxesState, bag: TrainerBagEditor, m: GbaTextMetrics, modifier: Modifier) {
    // Read through the edit count: [bag] is the same editor after an edit, so this page was skipped and a changed amount
    // (or an added item) showed only once the editor was opened again (Discord, 1.6.1).
    val pockets = state.revision.let { bag.pockets }
    // Gen 3 has no MEDICINE pocket: the open one falls back to the first.
    val open = pockets.firstOrNull { it.kind == state.pocket } ?: pockets.first()
    BoxWithConstraints(modifier) {
        if (maxWidth > maxHeight * 1.1f) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                PocketList(state, pockets, open.kind, m, Modifier.weight(0.36f).fillMaxHeight())
                StackList(state, bag, open, m, Modifier.weight(0.64f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                PocketList(state, pockets, open.kind, m, Modifier.fillMaxWidth())
                StackList(state, bag, open, m, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PocketList(state: PcBoxesState, pockets: List<SavePocket>, open: PocketKind, m: GbaTextMetrics, modifier: Modifier) {
    OptionListWindow(m, modifier) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (p in pockets) {
                OptionLine(pocketLabel(p.kind), "${p.stacks.size}/${p.slots}", selected = open == p.kind, m, labelWeight = 0.68f) {
                    state.pocket = p.kind
                }
            }
        }
    }
}

@Composable
private fun StackList(state: PcBoxesState, bag: TrainerBagEditor, p: SavePocket, m: GbaTextMetrics, modifier: Modifier) {
    val addable = remember(state.revision, p.kind) { bag.addable(p.kind) }
    OptionListWindow(m, modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GbaText(pocketLabel(p.kind), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m, Modifier.padding(horizontal = m.u * 4))
            p.stacks.forEachIndexed { i, st ->
                OptionLine(
                    state.itemText(st.item), "×${st.quantity}", selected = false, m, labelWeight = 0.8f, divider = true,
                    enabled = !bag.fixedStack(p.kind, st.item),
                ) { state.editing = PcBoxesState.Editing.Stack(p.kind, i) }
            }
            val have = p.stacks.map { it.item }.toSet()
            if (p.stacks.size < p.slots && addable.any { it !in have }) {
                OptionLine(tk("ADD ITEM"), null, selected = false, m) { state.editing = PcBoxesState.Editing.AddItem(p.kind) }
            }
            if (p.kind == PocketKind.KEY || p.kind == PocketKind.TMHM) {
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
    val close = { state.editing = null }
    when (e) {
        PcBoxesState.Editing.Money -> AmountPicker(tk("MONEY"), state.bag.money, 0, state.bag.maxMoney, m, close) { v ->
            state.edit { (this as TrainerBagEditor).setMoney(v) }
        }
        PcBoxesState.Editing.Coins -> AmountPicker(tk("COINS"), state.data3.coins.toLong(), 0, Gen3SaveData.MAX_COINS.toLong(), m, close) { v ->
            state.edit3 { setCoins(v.toInt()) }
        }
        is PcBoxesState.Editing.Stack -> {
            val bag = state.bag
            val st = bag.pocket(e.pocket)?.stacks?.getOrNull(e.index) ?: return
            AmountPicker(
                state.itemText(st.item), st.quantity.toLong(), 1, bag.stackMax(e.pocket, st.item).toLong(), m, close,
                onRemove = { state.edit { (this as TrainerBagEditor).setQuantity(e.pocket, e.index, 0) } },
            ) { v -> state.edit { (this as TrainerBagEditor).setQuantity(e.pocket, e.index, v.toInt()) } }
        }
        is PcBoxesState.Editing.AddItem -> {
            val bag = state.bag
            val have = bag.pocket(e.pocket)?.stacks.orEmpty().map { it.item }.toSet()
            val options = remember(state.revision, e.pocket) { bag.addable(e.pocket).filter { it !in have }.sortedBy { state.itemText(it) } }
            OptionSelector(
                tk("ADD ITEM"), options, -1, { state.itemText(it) }, m,
                onPick = { state.editing = PcBoxesState.Editing.NewStack(e.pocket, it) }, onDismiss = close,
            )
        }
        is PcBoxesState.Editing.NewStack -> AmountPicker(state.itemText(e.item), 1, 1, state.bag.stackMax(e.pocket, e.item).toLong(), m, close) { v ->
            state.edit { (this as TrainerBagEditor).addItem(e.pocket, e.item, v.toInt()) }
        }
        is PcBoxesState.Editing.HeldItem -> {
            val mon = data.mon(e.at)?.first ?: return
            // What it holds first (to keep it), then NONE and the rest.
            val options = remember(state.revision, mon.heldItem) { (listOf(mon.heldItem, 0) + state.editor.holdable()).distinct() }
            OptionSelector(
                tk("ITEM"), options, mon.heldItem, { if (it == 0) tk("NONE") else itemLabel(mon.game, it) }, m,
                onPick = { item -> state.edit { setHeldItem(e.at, item) } }, onDismiss = close,
            )
        }
        is PcBoxesState.Editing.Move -> {
            val (mon, level) = data.mon(e.at) ?: return
            val known = mon.moves.filter { it != 0 }
            val current = known.getOrNull(e.slot)
            val learnable = remember(state.revision, mon.species, level) { state.editor.learnable(e.at) }
            // The move it has (to keep it), FORGET while another move is left, then every move it may know that it
            // doesn't already, by name.
            val options = listOfNotNull(current) + (if (current != null && known.size > 1) listOf(FORGET) else emptyList()) +
                learnable.filter { it !in known }.sortedBy { moveLabel(it, mon.game) }
            OptionSelector(
                monLabel(mon), options, current ?: -1, { if (it == FORGET) tk("FORGET") else moveLabel(it, mon.game) }, m,
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
        else -> MoreEditOverlays(state, e, m)
    }
}

/** The SAVE EDITOR's other pickers: a Pokémon's page, the box tools, the Pokédex and the event tickets. */
@Composable
private fun MoreEditOverlays(state: PcBoxesState, e: PcBoxesState.Editing, m: GbaTextMetrics) {
    val data = state.data
    val close = { state.editing = null }
    when (e) {
        is PcBoxesState.Editing.Nature -> {
            val mon = data.mon(e.at)?.first ?: return
            OptionSelector(
                tk("NATURE"), (0 until 25).toList(), mon.nature, ::natureLabel, m,
                onPick = { n -> state.edit { reroll(e.at, nature = n) } }, onDismiss = close,
            )
        }
        is PcBoxesState.Editing.Ability -> {
            val mon = data.mon(e.at)?.first ?: return
            val abilities = mon.game.abilities(mon.species)
            OptionSelector(
                tk("ABILITY"), abilities.indices.toList(), mon.abilityNum, { mon.game.abilityName(abilities[it]) }, m,
                onPick = { n -> state.edit { reroll(e.at, abilityNum = n) } }, onDismiss = close,
            )
        }
        is PcBoxesState.Editing.Level -> {
            val level = data.mon(e.at)?.second ?: return
            AmountPicker(tk("LEVEL"), level.toLong(), state.editor.levelAsRead(e.at).toLong(), 100, m, close) { v ->
                state.edit { setLevel(e.at, v.toInt()) }
            }
        }
        is PcBoxesState.Editing.Friendship -> {
            val mon = data.mon(e.at)?.first ?: return
            AmountPicker(tk("FRIENDSHIP"), mon.friendship.toLong(), 0, 255, m, close) { v -> state.edit { setFriendship(e.at, v.toInt()) } }
        }
        is PcBoxesState.Editing.Stat -> {
            val mon = data.mon(e.at)?.first ?: return
            val values = if (e.ev) mon.evs else mon.ivs
            val max = if (e.ev) minOf(Gen3StatLimits.EV_MAX, Gen3StatLimits.EV_TOTAL - (values.sum() - values[e.stat])) else Gen3StatLimits.IV_MAX
            AmountPicker(
                "${statLabel(e.stat)} ${if (e.ev) tk("EV") else tk("IV")}", values[e.stat].toLong(), 0, max.toLong(), m, close,
            ) { v ->
                val next = values.copyOf().also { it[e.stat] = v.toInt() }
                state.edit { if (e.ev) setEvs(e.at, next) else setIvs(e.at, next) }
            }
        }
        is PcBoxesState.Editing.Nickname -> {
            val mon = data.mon(e.at)?.first ?: return
            NamePicker(tk("NICKNAME"), monLabel(mon), state.editor.nicknameMax(e.at), m, close, state.editor::canWrite) { name -> state.edit { setNickname(e.at, name) } }
        }
        is PcBoxesState.Editing.RenameBox -> {
            NamePicker(tk("BOX NAME"), data.boxName(e.box).trim(), state.editor.boxNameMax, m, close, state.editor::canWrite) { name -> state.edit { renameBox(e.box, name) } }
        }
        is PcBoxesState.Editing.SortBox -> {
            val sorts = BoxSort.entries
            OptionSelector(
                tk("SORT THIS BOX"), sorts.indices.toList(), -1,
                { when (sorts[it]) { BoxSort.DEX -> tk("BY DEX No."); BoxSort.LEVEL -> tk("BY LEVEL"); BoxSort.NAME -> tk("BY NAME") } },
                m, onPick = { i -> state.edit { sortBox(e.box, sorts[i], ::monLabel) }; state.selected = null }, onDismiss = close,
            )
        }
        is PcBoxesState.Editing.Release -> {
            val mon = data.mon(MonRef.Pc(e.at))?.first ?: return
            OptionConfirm(
                title = tk("RELEASE?"),
                message = tr("{0} goes back to the wild and leaves the box. You can still UNDO ALL before you save.", monLabel(mon)),
                confirmLabel = tk("RELEASE"), m = m, onDismiss = close,
                onConfirm = { state.edit(done = tr("Bye-bye, {0}!", monLabel(mon))) { release(e.at) }; state.selected = null },
            )
        }
        PcBoxesState.Editing.Search -> SearchOverlay(state, m, close)
        is PcBoxesState.Editing.Dex -> {
            val (title, message) = when (e.kind) {
                PcBoxesState.DexAction.REGISTER_OWNED -> tk("ADD MINE?") to tr("Every Pokémon in your party and boxes is marked as caught, like the game does when you get one.")
                PcBoxesState.DexAction.ALL_SEEN -> tk("ALL SEEN?") to tr("Every Pokémon of the game is marked as seen.")
                PcBoxesState.DexAction.ALL_CAUGHT -> tk("ALL CAUGHT?") to tr("Every Pokémon of the game is marked as caught, even ones you don't have.")
            }
            OptionConfirm(
                title = title, message = message, confirmLabel = tk("OK"), m = m, onDismiss = close,
                onConfirm = {
                    state.edit {
                        val bag = this as TrainerBagEditor
                        when (e.kind) {
                            PcBoxesState.DexAction.REGISTER_OWNED -> bag.registerOwned()
                            PcBoxesState.DexAction.ALL_SEEN -> bag.markAllDex(caught = false)
                            PcBoxesState.DexAction.ALL_CAUGHT -> bag.markAllDex(caught = true)
                        }
                    }
                },
            )
        }
        is PcBoxesState.Editing.Event -> OptionConfirm(
            title = bagItemLabel(e.event.item),
            message = tr("It goes in your key items pocket, the way the event handed it out. {0}.", e.event.note(state.data3.format)),
            confirmLabel = tk("GET"), m = m, onDismiss = close,
            onConfirm = { state.edit3(done = tr("{0} is in your key items pocket", bagItemLabel(e.event.item))) { giveEvent(e.event) } },
        )
        else -> {}
    }
}

/**
 * A name typed in: [max] characters at most, only what the games can write (letters, digits and their punctuation).
 * OK while it's one they would take.
 */
@Composable
internal fun NamePicker(
    title: String, value: String, max: Int, m: GbaTextMetrics, onDismiss: () -> Unit,
    /** Whether the game can write these characters. */
    encodable: (String) -> Boolean = Gen3Names::encodable,
    onDone: (String) -> Unit,
) {
    var text by remember(title, value) { mutableStateOf(value) }
    val ok = text.isNotBlank() && text.length <= max && encodable(text)
    val u = m.u
    OptionOverlay(onDismiss, Modifier.widthIn(max = 520.dp)) {
        Column {
            OptionTitleWindow(title, m, onBack = onDismiss)
            Spacer(Modifier.height(u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(u * 4), verticalArrangement = Arrangement.spacedBy(u * 4)) {
                    OptionTextField(text, { t -> text = t.take(max) }, m)
                    GbaText(
                        if (!encodable(text)) tr("Letters, digits and . - ! ? only") else tr("{0} of {1} letters", text.length, max),
                        if (encodable(text)) OptionColors.muted else OptionColors.value,
                        if (encodable(text)) OptionColors.mutedShadow else OptionColors.valueShadow, m,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(u * 4)) {
                        OptionButton(tk("CANCEL"), m, onClick = onDismiss, modifier = Modifier.weight(1f))
                        OptionButton(tk("OK"), m, onClick = { onDone(text.trim()) }, emphasis = true, enabled = ok, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** SEARCH: every Pokémon in the party and the boxes whose name or species has the words typed; a pick goes to it. */
@Composable
private fun SearchOverlay(state: PcBoxesState, m: GbaTextMetrics, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val data = state.data
    val hits = remember(query, state.revision) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) emptyList() else buildList {
            data.partyMons.forEachIndexed { i, p -> add(MonRef.Party(i) as MonRef to p.mon) }
            for (b in 0 until data.boxCount) data.boxMons(b).forEachIndexed { s, mon -> if (!mon.isEmpty) add(MonRef.Pc(BoxSlot(b, s)) as MonRef to mon) }
        }.filter { (_, mon) -> monLabel(mon).lowercase().contains(q) || (!mon.isEgg && speciesLabel(mon.species, mon.game).lowercase().contains(q)) }
    }
    val u = m.u
    OptionOverlay(onDismiss, Modifier.widthIn(max = 640.dp)) {
        Column {
            OptionTitleWindow(tk("SEARCH"), m, onBack = onDismiss)
            Spacer(Modifier.height(u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(u * 4)) {
                    OptionTextField(query, { query = it.take(12) }, m, placeholder = tr("A name or a species"), leadingIcon = PixelIcons.search)
                    Spacer(Modifier.height(u * 2))
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        if (query.isNotBlank() && hits.isEmpty()) GbaText(tr("No Pokémon by that name"), OptionColors.muted, OptionColors.mutedShadow, m)
                        hits.take(60).forEach { (at, mon) ->
                            val where = when (at) {
                                is MonRef.Party -> tr("PARTY")
                                is MonRef.Pc -> data.boxName(at.at.box).trim().ifEmpty { "BOX ${at.at.box + 1}" }
                            }
                            val level = if (mon.isEgg) "" else " Lv${data.mon(at)?.second ?: mon.level}"
                            OptionLine("${monLabel(mon)}$level", where, selected = false, m, labelWeight = 0.6f, divider = true) {
                                if (at is MonRef.Pc) state.box = at.at.box
                                state.selected = at
                                state.page = PcBoxesState.Page.POKEMON
                                onDismiss()
                            }
                        }
                    }
                }
            }
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
