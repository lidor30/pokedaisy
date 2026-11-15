package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.Gen4SaveEditor
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.data.save.SaveCollection
import com.pokedaisy.app.companion.data.save.TradeSide
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/**
 * TOOLS > COLLECTION: every Pokémon in the player's saves in one list, found by name, game or shininess, in Pokédex,
 * level or save order. A tap offers what can be done with it: OPEN its save in the editor on it, TRADE it to another
 * save of its generation, or PAL PARK (a GBA game's PC Pokémon, to a DS game).
 */
class CollectionState {
    /** The saves read (null while they're being read). */
    var sides by mutableStateOf<List<TradeSide>?>(null)
    var items by mutableStateOf<List<SaveCollection.Item>>(emptyList())
    var query by mutableStateOf("")
    /** The save shown (an index into [sides]), or -1 for all. */
    var game by mutableIntStateOf(-1)
    var shinyOnly by mutableStateOf(false)
    var sort by mutableStateOf(SaveCollection.Sort.DEX)
    /** The Pokémon whose OPEN / TRADE / PAL PARK choice is up. */
    var picked by mutableStateOf<SaveCollection.Item?>(null)
    var pickingGame by mutableStateOf(false)
    var pickingSort by mutableStateOf(false)

    fun load(read: List<TradeSide>) {
        sides = read
        items = SaveCollection.gather(read)
        if (game >= read.size) game = -1
    }

    /** What the list shows now. */
    fun shown(): List<SaveCollection.Item> {
        val q = query.trim().lowercase()
        val list = items.filter { item ->
            (game < 0 || item.source == game) &&
                (!shinyOnly || (item.mon.isShiny && !item.mon.isEgg)) &&
                (q.isEmpty() || monLabel(item.mon).lowercase().contains(q) ||
                    (!item.mon.isEgg && speciesLabel(item.mon.species, item.mon.game).lowercase().contains(q)))
        }
        return SaveCollection.sorted(list, sort)
    }

    /** Whether [item] has a save of its own generation to trade with. */
    fun canTrade(item: SaveCollection.Item): Boolean {
        val all = sides ?: return false
        // Black / White saves don't trade here yet.
        if (all[item.source].editor is com.pokedaisy.app.companion.data.save.Gen5SaveEditor) return false
        val ds = all[item.source].editor is Gen4SaveEditor
        return all.withIndex().any { (i, s) ->
            i != item.source && s.editor !is com.pokedaisy.app.companion.data.save.Gen5SaveEditor && (s.editor is Gen4SaveEditor) == ds
        }
    }

    /** Whether [item] could go through Pal Park: a GBA game's PC Pokémon, and a DS game among the saves. */
    fun canPalPark(item: SaveCollection.Item): Boolean =
        item.palParkable && sides.orEmpty().any { it.editor is Gen4SaveEditor } && sides!![item.source].editor is Gen3SaveEditor
}

@Composable
fun CollectionScreen(
    state: CollectionState,
    m: GbaTextMetrics,
    onBack: () -> Unit,
    icons: (Int) -> MonIconLoader?,
    onOpen: (SaveCollection.Item) -> Unit,
    onTrade: (SaveCollection.Item) -> Unit,
    onPalPark: (SaveCollection.Item) -> Unit,
) {
    val small = rememberGbaTextMetrics(textScale = 1f)
    val sides = state.sides
    val shown = state.shown()
    // Room for the line under each row (its game and where it is).
    val rowHeight = m.rowHeight + subtitleMetrics(m).lineHeight + m.u * 2
    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OptionTitleWindow(
                tk("COLLECTION"), m, onBack = onBack,
                trailing = sides?.let { tr("{0} POKéMON · {1} KINDS", state.items.size, SaveCollection.species(state.items)) },
            )
            Spacer(Modifier.height(m.u * 4))
            // The search box beside the buttons; on a narrow screen (a phone held upright) above them.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 640.dp
                val search: @Composable (Modifier) -> Unit = { mod ->
                    OptionTextField(
                        state.query, { state.query = it.take(12) }, m, mod,
                        placeholder = tr("A name or a species"), leadingIcon = PixelIcons.search,
                    )
                }
                val buttons: @Composable () -> Unit = {
                    OptionButton(
                        if (state.game < 0) tk("ALL GAMES") else sides?.getOrNull(state.game)?.label ?: tk("ALL GAMES"), m,
                        onClick = { state.pickingGame = true }, enabled = sides != null,
                    )
                    OptionButton(tk("SHINY"), m, onClick = { state.shinyOnly = !state.shinyOnly }, emphasis = state.shinyOnly)
                    OptionButton(sortLabel(state.sort), m, onClick = { state.pickingSort = true })
                }
                if (narrow) Column(verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                    search(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), verticalAlignment = Alignment.CenterVertically) { buttons() }
                } else Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), verticalAlignment = Alignment.CenterVertically) {
                    search(Modifier.weight(1f))
                    buttons()
                }
            }
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.weight(1f).fillMaxWidth()) {
                when {
                    sides == null -> CollectionMessage(tr("Reading your saves…"), m)
                    sides.isEmpty() -> CollectionMessage(tr("No game here has a save the save editor can read yet."), m)
                    shown.isEmpty() -> CollectionMessage(tr("No Pokémon like that in your saves."), m)
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { "${it.source}:${it.at}" }) { item ->
                            val side = sides[item.source]
                            val mon = item.mon
                            val name = monLabel(mon)
                            val species = if (mon.isEgg) null else speciesLabel(mon.species, mon.game)
                            val label = buildString {
                                if (item.national > 0 && !mon.isEgg) append("No.${item.national.toString().padStart(3, '0')} ")
                                append(name)
                                if (species != null && species != name) append(" ($species)")
                            }
                            OptionLine(
                                label, if (mon.isEgg) null else tr("Lv{0}", item.level), selected = false, m,
                                height = rowHeight, labelWeight = 0.75f, divider = true,
                                valueBadge = if (mon.isShiny && !mon.isEgg) tk("SHINY") else null,
                                subtitle = "${side.label} · ${whereIn(item, side)}",
                                leading = icons(item.source)?.let { loader -> @Composable { BoxMonIcon(loader, mon, m.rowHeight) } },
                            ) { state.picked = item }
                        }
                    }
                }
            }
            if (sides != null) {
                Spacer(Modifier.height(m.u * 3))
                GbaText(tr("{0} shown · tap one to open its save, trade it or send it to Pal Park", shown.size),
                    OptionColors.onBackdrop, OptionColors.onBackdropShadow, small, maxLines = 2)
            }
        }

        state.picked?.let { item ->
            val mon = item.mon
            OptionOverlay({ state.picked = null }, Modifier.widthIn(max = 520.dp)) {
                Column {
                    OptionTitleWindow(monLabel(mon), m, trailing = sides?.getOrNull(item.source)?.label)
                    Spacer(Modifier.height(m.u * 4))
                    OptionListWindow(m, Modifier.fillMaxWidth()) {
                        Column {
                            OptionLine(tk("OPEN"), null, selected = false, m, height = rowHeight, divider = true, chevron = true,
                                subtitle = tr("Its save in the editor, on this Pokémon")) { state.picked = null; onOpen(item) }
                            OptionLine(tk("TRADE"), null, selected = false, m, height = rowHeight, divider = true, chevron = true, enabled = state.canTrade(item),
                                subtitle = tr("To another of your saves")) { state.picked = null; onTrade(item) }
                            if (state.canPalPark(item)) {
                                OptionLine(tk("PAL PARK"), null, selected = false, m, height = rowHeight, divider = true, chevron = true,
                                    subtitle = tr("To HeartGold, SoulSilver or Platinum")) { state.picked = null; onPalPark(item) }
                            }
                            OptionLine(tk("CANCEL"), null, selected = false, m) { state.picked = null }
                        }
                    }
                }
            }
        }
        if (state.pickingGame && sides != null) {
            OptionSelector(
                tk("GAME"), listOf(-1) + sides.indices.toList(), state.game, { if (it < 0) tk("ALL GAMES") else sides[it].label }, m,
                onPick = { state.game = it; state.pickingGame = false }, onDismiss = { state.pickingGame = false },
            )
        }
        if (state.pickingSort) {
            OptionSelector(
                tk("SORT"), SaveCollection.Sort.entries, state.sort, ::sortLabel, m,
                onPick = { state.sort = it; state.pickingSort = false }, onDismiss = { state.pickingSort = false },
            )
        }
    }
}

private fun sortLabel(s: SaveCollection.Sort): String = when (s) {
    SaveCollection.Sort.DEX -> tk("BY NUMBER")
    SaveCollection.Sort.LEVEL -> tk("BY LEVEL")
    SaveCollection.Sort.GAME -> tk("BY GAME")
}

/** Where in its save it is: PARTY, or its box's name. */
private fun whereIn(item: SaveCollection.Item, side: TradeSide): String = when (val at = item.at) {
    is MonRef.Party -> tr("PARTY")
    is MonRef.Pc -> side.editor.data.boxName(at.at.box).trim().ifEmpty { "BOX ${at.at.box + 1}" }
}

@Composable
private fun CollectionMessage(text: String, m: GbaTextMetrics) {
    Box(Modifier.fillMaxWidth().padding(m.u * 4)) {
        GbaText(text, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE)
    }
}
