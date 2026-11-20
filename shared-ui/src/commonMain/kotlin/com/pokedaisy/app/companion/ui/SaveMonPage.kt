package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.natureName
import com.pokedaisy.app.companion.data.save.SaveMon
import com.pokedaisy.app.companion.data.save.Gen3Gender
import com.pokedaisy.app.companion.data.save.Gen3Legal
import com.pokedaisy.app.companion.data.save.Gen3Species
import com.pokedaisy.app.companion.data.save.Gen3StatCalc
import com.pokedaisy.app.companion.data.save.Gen3StatLimits
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** The stats in the order the games' summary lists them (HP, ATTACK, DEFENSE, SP. ATK, SP. DEF, SPEED), as indexes into
 * the save's order (HP, Attack, Defense, Speed, Sp. Atk, Sp. Def). */
private val SUMMARY_ORDER = intArrayOf(0, 1, 2, 4, 5, 3)

internal fun statLabel(i: Int): String = when (i) {
    0 -> tk("HP"); 1 -> tk("ATTACK"); 2 -> tk("DEFENSE"); 3 -> tk("SPEED"); 4 -> tk("SP. ATK"); else -> tk("SP. DEF")
}

/** A nature with what it does: "ADAMANT  +ATK -SP.ATK" (a neutral one alone). */
internal fun natureLabel(nature: Int): String {
    val up = nature / 5
    val down = nature % 5
    val name = caps(natureName(nature))
    if (up == down) return name
    fun short(i: Int) = when (i) { 0 -> tk("ATK"); 1 -> tk("DEF"); 2 -> tk("SPE"); 3 -> tk("SP.ATK"); else -> tk("SP.DEF") }
    return "$name  +${short(up)} -${short(down)}"
}

/**
 * One Pokémon's page in the SAVE EDITOR, over the boxes: who it is on the left, its INFO / STATS / MOVES on the right.
 * A row that can change has a chevron and opens its picker ([PcEditOverlays]); every value offered is one the game
 * could give it (natures, gender, ability and shininess by the personality, IVs to 31, EVs to 255 and 510 in all, a
 * level from its own up).
 */
@Composable
internal fun MonPage(state: PcBoxesState, at: MonRef, m: GbaTextMetrics, small: GbaTextMetrics, icons: MonIconLoader?, sprites: MonSpriteLoader?, onClose: () -> Unit) {
    val data = state.data
    val found = data.mon(at)
    if (found == null) {
        SideEffect { onClose() }
        return
    }
    val (mon, level) = found
    val editable = editableMon(mon)
    val noRipple = remember { MutableInteractionSource() }
    // A page of its own: taps never reach the boxes under it.
    // Not a D-pad stop: the page's own controls are.
    Box(Modifier.fillMaxSize().focusProperties { canFocus = false }.clickable(interactionSource = noRipple, indication = null) {}) {
        AppBackdrop()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            val where = when (at) {
                is MonRef.Party -> tr("PARTY {0}", at.index + 1)
                is MonRef.Pc -> "${data.boxName(at.at.box).trim().ifEmpty { "BOX ${at.at.box + 1}" }} · ${at.at.slot + 1}"
            }
            OptionTitleWindow(monLabel(mon), m, trailing = where, onBack = onClose)
            Spacer(Modifier.height(m.u * 4))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > maxHeight * 1.1f
                if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                    WhoCard(state, mon, level, at, small, icons, sprites, Modifier.weight(0.34f).fillMaxHeight())
                    TabsPane(state, mon, level, at, editable, m, small, Modifier.weight(0.66f).fillMaxHeight())
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
                    WhoCard(state, mon, level, at, small, icons, sprites, Modifier.fillMaxWidth())
                    TabsPane(state, mon, level, at, editable, m, small, Modifier.weight(1f).fillMaxWidth())
                }
            }
            state.notice?.let {
                Spacer(Modifier.height(m.u * 4))
                GbaText(it, OptionColors.value, OptionColors.valueShadow, small, maxLines = 2)
            }
        }
    }
}

/** Who it is: its icon, name and marks, dex number, OT and where it was met. */
@Composable
private fun WhoCard(state: PcBoxesState, mon: SaveMon, level: Int, at: MonRef, m: GbaTextMetrics, icons: MonIconLoader?, sprites: MonSpriteLoader?, modifier: Modifier) {
    OptionListWindow(m, modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(m.u * 4), verticalArrangement = Arrangement.spacedBy(m.u * 2)) {
            MonHeader(mon, level, m, icons, Modifier.fillMaxWidth(), iconLines = 3f)
            if (mon.isBadEgg) return@Column
            val dex = mon.game.nationalDex(mon.species)
            if (!mon.isEgg && dex != null) Fact(tr("No."), dex.toString().padStart(3, '0'), m)
            Fact(tk("OT"), "${mon.otName} · ${id5(mon.otTid)}", m)
            Fact(tk("MET IN"), if (mon.metLevel == 0) "${mon.metGameLabel} · ${tr("HATCHED")}" else "${mon.metGameLabel} · Lv${mon.metLevel}", m)
            if (!mon.isEgg && sprites != null) {
                Spacer(Modifier.height(m.u * 2))
                MonFrontPicture(sprites, mon.species, Modifier.fillMaxWidth().height(m.lineHeight * 8f))
            }
            if (!editableMon(mon)) {
                GbaText(tr("Eggs stay as they are until they hatch."), OptionColors.muted, OptionColors.mutedShadow, m, maxLines = Int.MAX_VALUE)
            } else if (state.editor.canWriteNames && !state.editor.isOwn(mon)) {
                GbaText(tr("Traded Pokémon keep their name: only the trainer who caught it can rename it."), OptionColors.muted, OptionColors.mutedShadow, m, maxLines = Int.MAX_VALUE)
            }
        }
    }
}

/** The ROM's 64x64 front picture, whole-pixel scaled and centred by [PixelImage]. Absent = nothing drawn. */
@Composable
private fun MonFrontPicture(sprites: MonSpriteLoader, species: Int, modifier: Modifier) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, sprites, species) {
        value = withContext(Dispatchers.IO) { runCatching { sprites.load(species) }.getOrNull() }
    }
    PixelImage(bmp, 64, modifier)
}

@Composable
private fun Fact(label: String, value: String, m: GbaTextMetrics) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GbaText(label, OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(0.38f))
        GbaText(value, OptionColors.value, OptionColors.valueShadow, m, Modifier.weight(0.62f))
    }
}

@Composable
private fun TabsPane(
    state: PcBoxesState, mon: SaveMon, level: Int, at: MonRef, editable: Boolean, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.u * 4)) {
        if (!mon.isEgg && !mon.isBadEgg) Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
            for (t in PcBoxesState.DetailTab.entries) {
                OptionButton(
                    when (t) { PcBoxesState.DetailTab.INFO -> tk("INFO"); PcBoxesState.DetailTab.STATS -> tk("STATS"); PcBoxesState.DetailTab.MOVES -> tk("MOVES") },
                    m, onClick = { state.detailTab = t; state.notice = null }, emphasis = state.detailTab == t,
                )
            }
        }
        val pane = Modifier.weight(1f).fillMaxWidth()
        when {
            mon.isEgg || mon.isBadEgg -> OptionListWindow(small, pane) {}
            state.detailTab == PcBoxesState.DetailTab.INFO -> InfoTab(state, mon, level, at, editable, small, pane)
            state.detailTab == PcBoxesState.DetailTab.STATS -> StatsTab(state, mon, level, at, editable, small, pane)
            else -> MovesTab(state, mon, at, editable, small, pane)
        }
    }
}

@Composable
private fun InfoTab(state: PcBoxesState, mon: SaveMon, level: Int, at: MonRef, editable: Boolean, m: GbaTextMetrics, modifier: Modifier) {
    val e = state.editor
    OptionListWindow(m, modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            @Composable
            fun row(label: String, value: String, open: (() -> Unit)?) = OptionLine(
                label, value, selected = false, m, labelWeight = 0.36f, divider = true, chevron = editable && open != null,
                onClick = { if (editable) open?.invoke() },
            )
            val rename = e.canWriteNames && e.isOwn(mon) && (mon as? com.pokedaisy.app.companion.data.save.Gen3BoxMon)?.language != 1
            row(tk("NICKNAME"), monLabel(mon), if (rename) ({ state.editing = PcBoxesState.Editing.Nickname(at) }) else null)
            row(tk("LEVEL"), "$level", if (level < 100 || e.levelAsRead(at) < level) ({ state.editing = PcBoxesState.Editing.Level(at) }) else null)
            row(tk("NATURE"), natureLabel(mon.nature), { state.editing = PcBoxesState.Editing.Nature(at) })
            val two = mon.game.abilities(mon.species).size == 2
            row(tk("ABILITY"), mon.game.abilityName(mon.ability), if (two) ({ state.editing = PcBoxesState.Editing.Ability(at) }) else null)
            val gender = when (mon.gender) { Gen3Gender.MALE -> tk("MALE"); Gen3Gender.FEMALE -> tk("FEMALE"); Gen3Gender.GENDERLESS -> tk("NONE") }
            row(tk("GENDER"), gender, if (mon.game.hasBothGenders(mon.species)) ({
                val other = if (mon.gender == Gen3Gender.MALE) Gen3Gender.FEMALE else Gen3Gender.MALE
                state.edit { reroll(at, gender = other) }
            }) else null)
            row(tk("SHINY"), if (mon.isShiny) tk("YES") else tk("NO"), { state.edit { reroll(at, shiny = !mon.isShiny) } })
            row(tk("ITEM"), if (mon.heldItem == 0) tk("NONE") else itemLabel(mon.game, mon.heldItem),
                if (e.canEditItems && !mon.game.isMail(mon.heldItem)) ({ state.editing = PcBoxesState.Editing.HeldItem(at) }) else null)
            row(tk("FRIENDSHIP"), "${mon.friendship}", { state.editing = PcBoxesState.Editing.Friendship(at) })
            row(tk("POKéRUS"), when {
                mon.pokerus == 0 -> tk("NONE")
                mon.pokerus and 0xF == 0 -> tk("CURED")
                else -> tk("INFECTED")
            }, null)
        }
    }
}

@Composable
private fun StatsTab(state: PcBoxesState, mon: SaveMon, level: Int, at: MonRef, editable: Boolean, m: GbaTextMetrics, modifier: Modifier) {
    // CalculateMonStats' numbers (a party record holds the same once the game has recalculated them).
    val stats = mon.game.stats(mon, level)
    val base = mon.game.baseStats(mon.species)
    val ivs = mon.ivs
    val evs = mon.evs
    val up = 1 + mon.nature / 5
    val down = 1 + mon.nature % 5
    OptionListWindow(m, modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = m.u * 4, vertical = m.u * 2)) {
            Row(Modifier.fillMaxWidth().height(m.rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(0.34f))
                for (h in listOf(tk("BASE"), tk("IV"), tk("EV"), tk("STAT"))) {
                    Box(Modifier.weight(0.165f), contentAlignment = Alignment.Center) {
                        GbaText(h, OptionColors.groupTitleText, OptionColors.groupTitleShadow, m)
                    }
                }
            }
            for (i in SUMMARY_ORDER) {
                Row(Modifier.fillMaxWidth().height(m.rowHeight), verticalAlignment = Alignment.CenterVertically) {
                    val mark = if (up == down) "" else if (i == up) " +" else if (i == down) " -" else ""
                    GbaText(statLabel(i) + mark, if (mark.isNotEmpty()) OptionColors.value else OptionColors.label,
                        if (mark.isNotEmpty()) OptionColors.valueShadow else OptionColors.labelShadow, m, Modifier.weight(0.34f))
                    StatCell("${base[i]}", m, null)
                    StatCell("${ivs[i]}", m, if (editable) ({ state.editing = PcBoxesState.Editing.Stat(at, i, ev = false) }) else null)
                    StatCell("${evs[i]}", m, if (editable) ({ state.editing = PcBoxesState.Editing.Stat(at, i, ev = true) }) else null)
                    StatCell("${stats[i]}", m, null, strong = true)
                }
            }
            Spacer(Modifier.height(m.u * 2))
            GbaText(tr("EVs {0} of {1}", evs.sum(), Gen3StatLimits.EV_TOTAL), OptionColors.label, OptionColors.labelShadow, m)
            if (editable) {
                Spacer(Modifier.height(m.u * 2))
                Row(horizontalArrangement = Arrangement.spacedBy(m.u * 3)) {
                    OptionButton(tk("MAX IVs"), m, onClick = { state.edit { setIvs(at, IntArray(6) { Gen3StatLimits.IV_MAX }) } },
                        enabled = ivs.any { it < Gen3StatLimits.IV_MAX }, modifier = Modifier.weight(1f))
                    OptionButton(tk("CLEAR EVs"), m, onClick = { state.edit { setEvs(at, IntArray(6)) } },
                        enabled = evs.any { it > 0 }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** A number in the stats table; one that can change is framed like a key and opens its amount. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.StatCell(text: String, m: GbaTextMetrics, onClick: (() -> Unit)?, strong: Boolean = false) {
    Box(Modifier.weight(0.165f).fillMaxHeight().padding(m.u), contentAlignment = Alignment.Center) {
        if (onClick != null) {
            // A white key with the number: tap to change it.
            val u = m.u
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Box(
                Modifier.fillMaxSize()
                    .gbaFocusRing(focused, PixelRoundedShape(u * 2))
                    .drawBehind {
                        val r = 2 * u.toPx()
                        drawPixelRoundRect(OptionColors.titleFill, radius = r)
                        drawPixelRoundFrame(OptionColors.frameDark, androidx.compose.ui.geometry.Offset.Zero, size, r, u.toPx())
                    }
                    .soundClickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { GbaText(text, OptionColors.value, OptionColors.valueShadow, m) }
        } else {
            GbaText(text, if (strong) OptionColors.value else OptionColors.label, if (strong) OptionColors.valueShadow else OptionColors.labelShadow, m)
        }
    }
}

@Composable
private fun MovesTab(state: PcBoxesState, mon: SaveMon, at: MonRef, editable: Boolean, m: GbaTextMetrics, modifier: Modifier) {
    OptionListWindow(m, modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val moves = mon.moves.filter { it != 0 }
            moves.forEachIndexed { i, id ->
                val ups = (mon.ppBonuses shr (i * 2)) and 3
                OptionLine(
                    moveLabel(id, mon.game), "PP ${mon.pp[i]}/${mon.game.ppWithBonus(id, ups)}", selected = false, m, labelWeight = 0.62f, divider = true,
                    chevron = editable, onClick = { if (editable) state.editing = PcBoxesState.Editing.Move(at, i) },
                )
            }
            if (editable && moves.size < 4) {
                OptionLine(tr("ADD MOVE"), null, selected = false, m, chevron = true) { state.editing = PcBoxesState.Editing.Move(at, moves.size) }
            }
            if (editable) {
                GbaText(
                    tr("Only moves it could learn by now are offered: by level, TM, HM or a move tutor."),
                    OptionColors.muted, OptionColors.mutedShadow, m, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(m.u * 4),
                )
            }
        }
    }
}
