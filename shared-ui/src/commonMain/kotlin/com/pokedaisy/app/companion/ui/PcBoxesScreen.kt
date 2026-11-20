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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pokedaisy.app.companion.data.Gen3Text
import com.pokedaisy.app.companion.data.natureName
import com.pokedaisy.app.companion.data.save.PocketKind
import com.pokedaisy.app.companion.data.save.TrainerBagEditor
import com.pokedaisy.app.companion.data.save.BoxSlot
import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import com.pokedaisy.app.companion.data.save.Gen3Event
import com.pokedaisy.app.companion.data.save.Gen3Gender
import com.pokedaisy.app.companion.data.save.Gen3Legal
import com.pokedaisy.app.companion.data.save.Gen3SaveData
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.Gen3Species
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.data.save.Gen3GameData
import com.pokedaisy.app.companion.data.save.MonSave
import com.pokedaisy.app.companion.data.save.MonSaveEditor
import com.pokedaisy.app.companion.data.save.SaveGameData
import com.pokedaisy.app.companion.data.save.SaveMon
import com.pokedaisy.app.companion.data.save.SaveEditRefused
import com.pokedaisy.app.companion.data.speciesNames
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.IO
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing

/**
 * The SAVE EDITOR (a game's library menu, or TOOLS): a save file read while the game is closed, in three pages -
 * POKéMON (the party and the 14 PC boxes: move any Pokémon between them, sort / rename / search the boxes, release, and
 * a Pokémon's own page: [MonPage]), BAG and TRAINER (money, coins, the Pokédex and the event tickets). Every value
 * offered is one the game itself could hold ([com.pokedaisy.app.companion.data.save.Gen3Legal], [Gen3SaveEditor]); an
 * edit the game wouldn't make shows its reason as [PcBoxesState.notice]. SAVE writes it the way the game saves
 * ([Gen3SaveEditor.build]) through the activity's [onSave].
 */
class PcBoxesState(editor: MonSaveEditor) {
    var editor = editor
        private set
    /** The Gen 3 editor behind it (coins and the event tickets are Gen 3's), or null for a DS save. */
    val gen3: Gen3SaveEditor? get() = editor as? Gen3SaveEditor
    /** BAG and TRAINER's side of the editor, read again on every edit. */
    val bag: TrainerBagEditor get() { revision; return editor as TrainerBagEditor }
    /** Bumped on every edit: [data] is re-read from the editor's blocks. */
    var revision by mutableIntStateOf(0)
        private set
    var box by mutableIntStateOf(editor.data.currentBox.coerceIn(0, editor.data.boxCount - 1))
    var selected by mutableStateOf<MonRef?>(null)
    /** MOVE was pressed on this Pokémon: the next slot tapped (a box slot, or a party slot) is where it goes. */
    var moving by mutableStateOf<MonRef?>(null)
    /** The Pokémon whose page is open. */
    var detail by mutableStateOf<MonRef?>(null)
    var detailTab by mutableStateOf(DetailTab.INFO)
    /** Why the last edit didn't happen (the game's rule), or what it did; cleared by the next tap. */
    var notice by mutableStateOf<String?>(null)
    var confirmSave by mutableStateOf(false)
    var confirmDiscard by mutableStateOf(false)
    var page by mutableStateOf(Page.POKEMON)
    /** The BAG page's open pocket. */
    var pocket by mutableStateOf(PocketKind.ITEMS)
    /** A pick-list, amount, name or confirm over the page, if one is open. */
    var editing by mutableStateOf<Editing?>(null)

    enum class Page { POKEMON, BAG, TRAINER }
    enum class DetailTab { INFO, STATS, MOVES }

    sealed class Editing {
        object Money : Editing()
        object Coins : Editing()
        /** A stack's amount (stack [index] of [pocket]). */
        data class Stack(val pocket: PocketKind, val index: Int) : Editing()
        /** ADD ITEM's pick-list, then the amount of the [item] picked. */
        data class AddItem(val pocket: PocketKind) : Editing()
        data class NewStack(val pocket: PocketKind, val item: Int) : Editing()
        data class HeldItem(val at: MonRef) : Editing()
        /** Move [slot] (0-3; one past the last move = a new one) of the Pokémon at [at]. */
        data class Move(val at: MonRef, val slot: Int) : Editing()
        data class Nature(val at: MonRef) : Editing()
        data class Ability(val at: MonRef) : Editing()
        data class Level(val at: MonRef) : Editing()
        data class Friendship(val at: MonRef) : Editing()
        data class Nickname(val at: MonRef) : Editing()
        /** IV ([ev] false) or EV of stat [stat] (0-5, the game's order). */
        data class Stat(val at: MonRef, val stat: Int, val ev: Boolean) : Editing()
        data class Release(val at: BoxSlot) : Editing()
        data class RenameBox(val box: Int) : Editing()
        data class SortBox(val box: Int) : Editing()
        object Search : Editing()
        data class Dex(val kind: DexAction) : Editing()
        data class Event(val event: Gen3Event) : Editing()
    }

    enum class DexAction { REGISTER_OWNED, ALL_SEEN, ALL_CAUGHT }

    /** The TRAINER page's MISSING list, while it's open. */
    var missing by mutableStateOf<MissingView?>(null)
    var missingFilter by mutableStateOf(MissingFilter.ALL)

    /**
     * Runs an edit on the save. One the game wouldn't make changes nothing and says why in [notice] (a check the screens
     * already make silently never gets there). Closes [editing].
     */
    fun edit(done: String? = null, change: MonSaveEditor.() -> Unit) {
        notice = try {
            editor.change()
            revision++
            done
        } catch (e: SaveEditRefused) {
            e.message
        } catch (e: IllegalArgumentException) {
            null
        }
        editing = null
    }

    val data: MonSave get() { revision; return editor.data }
    /** The Gen 3 save (BAG, TRAINER). */
    val data3: Gen3SaveData get() { revision; return gen3!!.data }

    /** [edit] on the Gen 3 editor (BAG, TRAINER). */
    fun edit3(done: String? = null, change: Gen3SaveEditor.() -> Unit) = edit(done) { (this as Gen3SaveEditor).change() }
    val changed: Boolean get() { revision; return editor.changed }

    /** A tap on a slot: the end of a MOVE, or the Pokémon there selected (a party slot past the last is empty). */
    fun tap(at: MonRef) {
        notice = null
        val from = moving
        if (from != null) {
            moving = null
            if (at == from) return
            val before = revision
            edit { move(from, at) }
            if (revision != before) {
                // Where it went: a deposit closes the party up, a withdraw lands at its end.
                selected = when {
                    at is MonRef.Party -> MonRef.Party(at.index.coerceAtMost(data.partyCount - 1))
                    else -> at
                }
            }
            return
        }
        selected = if (editor.monAt(at) == null) null else at
    }

    /** Back to the save as read: a fresh editor over the same file. */
    fun undoAll() {
        editor = editor.fresh()
        moving = null
        editing = null
        detail = null
        notice = null
        revision++
    }
}

/**
 * The SAVE EDITOR's and TRADE SAVES' text size: the main text a font pixel smaller than the library's on any window
 * ([LocalTextStep] -1), so more of a save fits on screen. Wrap the screens (and the [GbaTextMetrics] passed in) in it.
 */
@Composable
fun SaveEditorText(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalTextStep provides -1, content = content)
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
    /** Front pictures from the ROM ([com.pokedaisy.app.companion.data.save.RomMonSprites]); null = icon only. */
    sprites: MonSpriteLoader? = null,
    /** TRADE: pick another game's save to trade with (only while nothing here is unsaved). */
    onTrade: (() -> Unit)? = null,
    /** TRAINER's MISSING: read the other saves for what this Pokédex lacks ([PcBoxesState.missing]). */
    onMissing: (() -> Unit)? = null,
    /** A MISSING row tapped: open the trade or Pal Park that brings that Pokémon. */
    onGet: ((com.pokedaisy.app.companion.data.save.MissingDex.Owned) -> Unit)? = null,
) {
    val small = rememberGbaTextMetrics(textScale = 1f)
    // The box's cards: a step denser, so ten-letter names fit six across.
    val cell = rememberGbaTextMetrics(textScale = 0.8f)
    val data = state.data
    val leave = {
        when {
            state.missing != null -> state.missing = null
            state.detail != null -> state.detail = null
            state.moving != null -> state.moving = null
            state.changed -> state.confirmDiscard = true
            else -> onBack()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        // A Pokémon's page takes the screen; the boxes come back as they were.
        state.detail?.let { at -> MonPage(state, at, m, small, icons, sprites, onClose = { state.detail = null }) }
        state.missing?.let { v -> if (state.detail == null) MissingPage(state, v, m, small, onGet = { onGet?.invoke(it) }, onClose = { state.missing = null }) }
        if (state.detail == null && state.missing == null) Column(Modifier.fillMaxSize().padding(16.dp)) {
            OptionTitleWindow(tk("SAVE EDITOR"), m, trailing = gameName, onBack = leave)
            Spacer(Modifier.height(m.u * 4))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), verticalAlignment = Alignment.CenterVertically) {
                // A DS save has its POKéMON page only (its bag and trainer come later).
                for (p in PcBoxesState.Page.entries.filter { state.editor is TrainerBagEditor || it == PcBoxesState.Page.POKEMON }) {
                    OptionButton(
                        when (p) {
                            PcBoxesState.Page.POKEMON -> tk("POKéMON")
                            PcBoxesState.Page.BAG -> tk("BAG")
                            PcBoxesState.Page.TRAINER -> tk("TRAINER")
                        },
                        m, onClick = { state.page = p; state.moving = null; state.notice = null }, emphasis = state.page == p,
                        // The D-pad's first press lands here when no slot is selected.
                        modifier = Modifier.focusStart(state.page == p),
                    )
                }
                Spacer(Modifier.weight(1f))
                // Whose save it is, always in sight.
                BackdropText("${data.trainerName} · ${tr("ID No.")} ${id5(data.trainerId)}", small)
            }
            Spacer(Modifier.height(m.u * 4))
            val content = Modifier.weight(1f).fillMaxWidth()
            when (state.page) {
                PcBoxesState.Page.BAG -> BagPage(state, state.bag, m, content)
                PcBoxesState.Page.TRAINER -> TrainerPage(state, state.bag, m, small, content, onMissing)
                PcBoxesState.Page.POKEMON -> BoxWithConstraints(content) {
                    val wide = maxWidth > maxHeight * 1.1f
                    if (wide) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                            Column(Modifier.weight(0.34f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                                PartyWindow(state, data, cell, icons, Modifier.fillMaxWidth(), columns = 2)
                                PreviewCard(state, data, small, icons, Modifier.weight(1f).fillMaxWidth())
                            }
                            BoxWindow(state, data, m, cell, icons, Modifier.weight(0.66f).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                            BoxWindow(state, data, m, cell, icons, Modifier.fillMaxWidth().weight(0.55f))
                            PartyWindow(state, data, cell, icons, Modifier.fillMaxWidth(), columns = 3)
                            PreviewCard(state, data, small, icons, Modifier.weight(0.45f).fillMaxWidth())
                        }
                    }
                }
            }
            Spacer(Modifier.height(m.u * 4))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), verticalAlignment = Alignment.CenterVertically) {
                val hint = when {
                    state.notice != null -> state.notice!!
                    state.moving != null -> tr("Tap where it goes: a box slot or a party slot")
                    state.changed -> tr("Not saved yet")
                    state.page == PcBoxesState.Page.POKEMON && state.selected == null -> tr("Tap a Pokémon to see it, change it or move it")
                    else -> ""
                }
                GbaText(
                    hint, if (state.notice != null) OptionColors.value else OptionColors.onBackdrop,
                    if (state.notice != null) OptionColors.valueShadow else OptionColors.onBackdropShadow, small, Modifier.weight(1f), maxLines = 2,
                )
                if (state.moving != null) {
                    OptionButton(tk("CANCEL"), m, onClick = { state.moving = null })
                } else {
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

/** The party: six slots ([columns] across), the selected one framed; while a MOVE waits, the empty ones take it too. */
@Composable
private fun PartyWindow(
    state: PcBoxesState, data: MonSave, m: GbaTextMetrics, icons: MonIconLoader?, modifier: Modifier, columns: Int,
) {
    PartyStrip(
        data, m, icons, modifier, columns = columns,
        selected = { state.selected == it || state.moving == it },
        target = { state.moving != null && state.moving != it },
        onTap = { state.tap(it) },
    )
}

/**
 * A save's party as slots under a PARTY title: [columns] across (2 = three rows, 3 = two, 6 = one), the Pokémon's
 * levels from the party records. Shared by the editor and TRADE. An empty slot is [MonRef.Party] past the last one.
 */
@Composable
internal fun PartyStrip(
    data: MonSave, m: GbaTextMetrics, icons: MonIconLoader?, modifier: Modifier, columns: Int, title: String? = tr("PARTY"),
    selected: (MonRef) -> Boolean, target: (MonRef) -> Boolean, onTap: (MonRef) -> Unit,
) {
    val party = data.partyMons
    OptionListWindow(m, modifier) {
        Column(Modifier.fillMaxWidth()) {
            title?.let { GbaText(it, OptionColors.groupTitleText, OptionColors.groupTitleShadow, m, Modifier.padding(horizontal = m.u * 4)) }
            val rows = (Gen3SaveData.PARTY_SIZE + columns - 1) / columns
            for (row in 0 until rows) Row(Modifier.fillMaxWidth().height(if (icons != null) m.lineHeight * 2.6f else m.lineHeight * 2 + m.u * 6)) {
                for (col in 0 until columns) {
                    val i = row * columns + col
                    val p = party.getOrNull(i)
                    // An empty slot stands for the end of the party.
                    val ref = MonRef.Party(if (p == null) party.size else i)
                    MonCell(
                        p?.mon, p?.level, m, Modifier.weight(1f).fillMaxHeight(), icons,
                        selected = if (p == null) i == party.size && selected(ref) else selected(ref),
                        target = if (p == null) i == party.size && target(ref) else target(ref),
                        onClick = { onTap(ref) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxWindow(state: PcBoxesState, data: MonSave, m: GbaTextMetrics, cell: GbaTextMetrics, icons: MonIconLoader?, modifier: Modifier) {
    BoxGrid(
        data, state.box, { state.box = it; state.notice = null }, m, cell, icons, modifier,
        selected = { state.selected == MonRef.Pc(it) || state.moving == MonRef.Pc(it) },
        target = { state.moving != null && state.moving != MonRef.Pc(it) },
        onTap = { state.tap(MonRef.Pc(it)) },
        tools = {
            val t = rememberGbaTextMetrics(textScale = 1f)
            OptionButton(null, t, onClick = { state.editing = PcBoxesState.Editing.Search }, pixelIcon = PixelIcons.search,
                sidePadding = 4, modifier = Modifier.semantics { contentDescription = tr("Search the boxes") })
            OptionButton(tk("SORT"), t, onClick = { state.editing = PcBoxesState.Editing.SortBox(state.box) }, sidePadding = 4)
            if (state.editor.canWriteNames) {
                OptionButton(tk("RENAME"), t, onClick = { state.editing = PcBoxesState.Editing.RenameBox(state.box) }, sidePadding = 4)
            }
        },
    )
}

/**
 * The selected Pokémon at a glance - its icon, name, gender and shiny star, level, nature, ability and item - with what
 * can be done with it: SEE / CHANGE (its page), MOVE and, for a box Pokémon, RELEASE.
 */
@Composable
private fun PreviewCard(state: PcBoxesState, data: MonSave, m: GbaTextMetrics, icons: MonIconLoader?, modifier: Modifier) {
    val sel = state.selected
    val found = sel?.let { data.mon(it) }
    OptionListWindow(m, modifier) {
        if (sel == null || found == null) {
            GbaText(
                tr("Pick a Pokémon from your party or a box."), OptionColors.muted, OptionColors.mutedShadow, m,
                maxLines = Int.MAX_VALUE, modifier = Modifier.padding(m.u * 4),
            )
            return@OptionListWindow
        }
        val (mon, level) = found
        Column(Modifier.fillMaxSize().padding(horizontal = m.u * 4, vertical = m.u * 2)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                MonHeader(mon, level, m, icons, Modifier.fillMaxWidth())
                if (!mon.isEgg && !mon.isBadEgg) {
                    val f = rememberGbaTextMetrics(textScale = 0.8f)
                    Spacer(Modifier.height(m.u * 2))
                    InfoPair(tk("NATURE"), caps(natureName(mon.nature)), f)
                    InfoPair(tk("ABILITY"), mon.game.abilityName(mon.ability), f)
                    InfoPair(tk("ITEM"), if (mon.heldItem == 0) tk("NONE") else itemLabel(mon.game, mon.heldItem), f)
                }
            }
            Spacer(Modifier.height(m.u * 2))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 3)) {
                val movable = !mon.isBadEgg
                OptionButton(
                    if (editableMon(mon)) tk("CHANGE") else tk("SEE"), m, onClick = { state.detail = sel; state.detailTab = PcBoxesState.DetailTab.INFO },
                    emphasis = true, enabled = !mon.isBadEgg, modifier = Modifier.weight(1f), sidePadding = 2,
                )
                OptionButton(tk("MOVE"), m, onClick = { state.moving = sel; state.notice = null }, enabled = movable, modifier = Modifier.weight(1f), sidePadding = 2)
                if (sel is MonRef.Pc) {
                    OptionButton(tk("RELEASE"), m, onClick = { state.editing = PcBoxesState.Editing.Release(sel.at) },
                        enabled = !mon.isEgg && !mon.isBadEgg, modifier = Modifier.weight(1f), sidePadding = 2)
                }
            }
        }
    }
}

/** A Pokémon's icon (when the ROM gave icons) beside its name with gender and shiny marks, and "Lv52 · CHARIZARD" under it. */
@Composable
internal fun MonHeader(mon: SaveMon, level: Int, m: GbaTextMetrics, icons: MonIconLoader?, modifier: Modifier, iconLines: Float = 2.4f) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icons != null && !mon.isBadEgg) {
            BoxMonIcon(icons, mon, m.lineHeight * iconLines)
            Spacer(Modifier.width(m.u * 3))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GbaText(monLabel(mon), OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f, fill = false))
                if (!mon.isEgg && !mon.isBadEgg) GenderAndShiny(mon, m)
            }
            if (!mon.isEgg && !mon.isBadEgg) {
                GbaText("Lv$level · ${speciesLabel(mon.species, mon.game)}", OptionColors.value, OptionColors.valueShadow, m)
            }
        }
    }
}

/** ♂ / ♀ (none for a genderless species) and a gold star for a shiny one. */
@Composable
internal fun GenderAndShiny(mon: SaveMon, m: GbaTextMetrics) {
    val icon = m.lineHeight * 0.8f
    when (mon.gender) {
        Gen3Gender.MALE -> { Spacer(Modifier.width(m.u * 2)); PixelIcon(PixelIcons.male, GENDER_BLUE, Modifier.size(icon)) }
        Gen3Gender.FEMALE -> { Spacer(Modifier.width(m.u * 2)); PixelIcon(PixelIcons.female, GENDER_RED, Modifier.size(icon)) }
        Gen3Gender.GENDERLESS -> {}
    }
    if (mon.isShiny) { Spacer(Modifier.width(m.u * 2)); PixelIcon(PixelIcons.sparkle, OptionColors.favorite, Modifier.size(icon)) }
}

private val GENDER_BLUE = androidx.compose.ui.graphics.Color(0xFF3060D8)
private val GENDER_RED = androidx.compose.ui.graphics.Color(0xFFD83838)

/** A grey label over a value, one line each half: the preview card's facts. */
@Composable
private fun InfoPair(label: String, value: String, m: GbaTextMetrics) {
    Row(Modifier.fillMaxWidth().padding(vertical = m.u * 0.5f), verticalAlignment = Alignment.CenterVertically) {
        GbaText(label, OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(0.42f))
        GbaText(value, OptionColors.value, OptionColors.valueShadow, m, Modifier.weight(0.58f))
    }
}

/** Party icons, decoded on demand (blocking: called off the UI thread) and cached by whoever loads them. [species] is the
 * save's own id; an egg is [egg] (its species is the Pokémon inside, and each game keeps its egg's icon apart). */
fun interface MonIconLoader {
    fun load(species: Int, egg: Boolean): ImageBitmap?
}

/** 64x64 front pictures for the SAVE EDITOR's Pokémon page, from the player's own ROM (null = no known dex tables). */
fun interface MonSpriteLoader {
    fun load(species: Int): ImageBitmap?
}

/**
 * One PC box of [data] as the game lays it out (6 x 5), its name between box arrows ([onBox] gets the new box); a
 * [title] over it (whose save, on the trade screen). Shared by PC BOXES and TRADE.
 */
@Composable
internal fun BoxGrid(
    data: MonSave, box: Int, onBox: (Int) -> Unit, m: GbaTextMetrics, cell: GbaTextMetrics, icons: MonIconLoader?,
    modifier: Modifier, title: String? = null,
    selected: (BoxSlot) -> Boolean, target: (BoxSlot) -> Boolean, onTap: (BoxSlot) -> Unit,
    /** Buttons after the box's name (the editor's SEARCH / SORT / RENAME). */
    tools: (@Composable () -> Unit)? = null,
) {
    val boxMons = data.boxMons(box)
    val boxName = data.boxName(box)
    OptionListWindow(cell, modifier) {
        Column(Modifier.fillMaxSize()) {
            title?.let { GbaText(it, OptionColors.groupTitleText, OptionColors.groupTitleShadow, cell, Modifier.padding(horizontal = m.u * 4)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                OptionButton(null, m, onClick = { onBox((box + data.boxCount - 1) % data.boxCount) },
                    pixelIcon = PixelIcons.cursorLeft, modifier = Modifier.semantics { contentDescription = tr("Previous box") })
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    GbaText(boxName.ifBlank { "BOX ${box + 1}" }, OptionColors.label, OptionColors.labelShadow, m)
                }
                OptionButton(null, m, onClick = { onBox((box + 1) % data.boxCount) },
                    pixelIcon = PixelIcons.cursorRight, modifier = Modifier.semantics { contentDescription = tr("Next box") })
                tools?.invoke()
            }
            Spacer(Modifier.height(cell.u * 2))
            Column(Modifier.weight(1f).fillMaxWidth()) {
                for (row in 0 until 5) Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0 until 6) {
                        val at = BoxSlot(box, row * 6 + col)
                        MonCell(
                            boxMons[at.slot].takeIf { !it.isEmpty }, null, cell, Modifier.weight(1f).fillMaxHeight(), icons,
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
internal fun BoxMonIcon(loader: MonIconLoader, mon: SaveMon, size: Dp) {
    val species = mon.species
    val egg = mon.isEgg
    val bmp by produceState<ImageBitmap?>(null, loader, species, egg) {
        value = withContext(Dispatchers.IO) { runCatching { loader.load(species, egg) }.getOrNull()?.let { cropToFirstFrame(it) } }
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
    mon: SaveMon?, partyLevel: Int?, m: GbaTextMetrics, modifier: Modifier, icons: MonIconLoader?,
    selected: Boolean, target: Boolean = false, onClick: () -> Unit,
) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    val focused by noRipple.collectIsFocusedAsState()
    val withIcon = icons != null && mon != null && !mon.isBadEgg
    Box(
        modifier
            .padding(u * 0.5f)
            // The D-pad's cursor: the orange ring, and where it starts (the selected slot) when nothing has it yet.
            .focusStart(selected)
            .gbaFocusRing(focused, PixelRoundedShape(u * 2))
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
                    Column(horizontalAlignment = Alignment.End) {
                        if (level.isNotEmpty()) GbaText(level, OptionColors.value, OptionColors.valueShadow, m)
                        if (mon.isShiny && !mon.isEgg) PixelIcon(PixelIcons.sparkle, OptionColors.favorite, Modifier.size(m.lineHeight * 0.7f))
                    }
                }
                GbaText(monLabel(mon), OptionColors.label, OptionColors.labelShadow, m)
            }
        } else Column {
            GbaText(monLabel(mon), if (mon.isBadEgg) OptionColors.muted else OptionColors.label, OptionColors.labelShadow, m)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (level.isNotEmpty()) GbaText(level, OptionColors.value, OptionColors.valueShadow, m)
                if (mon.isShiny && !mon.isEgg && !mon.isBadEgg) PixelIcon(PixelIcons.sparkle, OptionColors.favorite, Modifier.size(m.lineHeight * 0.7f))
            }
        }
    }
}

/** What PC BOXES lets you change: not an egg, not a Bad Egg. */
internal fun editableMon(mon: SaveMon): Boolean = !mon.isEmpty && !mon.isBadEgg && !mon.isEgg

/** A trainer ID as the game prints it, five digits. (Not String.format around names: the games' charset has a %.) */
internal fun id5(id: Int): String = id.toString().padStart(5, '0')

internal fun caps(s: String): String = buildString(s.length) { for (c in s) append(if (c == 'é') c else c.uppercaseChar()) }

internal fun speciesLabel(id: Int, game: SaveGameData = Gen3GameData): String = game.speciesName(id)?.let(::caps) ?: "#$id"

/** The nickname as the game shows it, else the species' name (a Japanese Gen 3 game's kana the Western table can't read). */
internal fun monLabel(mon: SaveMon): String = when {
    mon.isBadEgg -> tk("BAD EGG")
    mon.isEgg -> tk("EGG")
    else -> mon.nickname ?: speciesLabel(mon.species, mon.game)
}

/** An item's name in [game]'s words, in capitals. */
internal fun itemLabel(game: SaveGameData, id: Int): String = game.itemName(id)?.let(::caps) ?: "#$id"
