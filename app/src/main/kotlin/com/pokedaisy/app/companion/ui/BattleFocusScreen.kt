package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.itemName
import com.pokedaisy.app.companion.data.withMovesVs
import com.pokedaisy.app.companion.i18n.tr

/**
 * Battle INFO when SETTINGS > BATTLE FOCUS is on. One Pokémon per page, sized to fit without scrolling:
 * header, stats, the four moves and the matchups. The button in the corner switches between the foe and
 * your Pokémon. The column on the right is the foe's team, or your party when you're looking at yours,
 * with Back under it.
 */
@Composable
fun BattleFocusScreen(
    foe: MonView?,
    party: List<MonView>,
    battlePlayer: List<MonView>,
    /** The [party] slot of each of [battlePlayer]. */
    playerBattlers: List<Int>,
    showHints: Boolean,
    onBack: (() -> Unit)?,
    foeTeam: List<MonView> = emptyList(),
    seenFoes: Set<Int> = emptySet(),
    shownFoe: Int = -1,
    foeHeading: String = "FOE",
    onSelectFoe: (Int) -> Unit = {},
    dexTables: PokedexTables? = null,
) {
    val m = rememberGbaTextMetrics(gameScaled = false)
    val small = rememberGbaTextMetrics(1f, gameScaled = false)
    // Go back to the foe's side whenever a different foe comes in.
    var yours by key(foe?.species to foe?.level) { remember { mutableStateOf(false) } }
    // Which of your party is shown; null means the first one in battle.
    var ownSlot by remember { mutableStateOf<Int?>(null) }
    val viewedSlot = ownSlot ?: playerBattlers.firstOrNull() ?: -1
    // One that's in battle shows its battle stats, otherwise its party entry.
    val battlerAt = playerBattlers.indexOf(viewedSlot).takeIf { viewedSlot >= 0 }
    val own = (battlerAt?.let { battlePlayer.getOrNull(it) }
        ?: party.getOrNull(viewedSlot)?.let { p -> p.copy(heldItem = p.heldItemId.takeIf { it != 0 }?.let(::itemName) ?: "") }
        ?: battlePlayer.firstOrNull())
        ?.let { if (foe != null) it.withMovesVs(foe) else it }
    val foePage = remember(foe, battlePlayer) { foe?.withMovesVs(battlePlayer) }
    if (onBack != null) CompanionBackHandler(onBack = onBack)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionListWindow(m, Modifier.weight(1f).fillMaxHeight(), fill = OptionColors.titleFill) {
            Box(Modifier.fillMaxSize()) {
                (if (yours) own else foePage)?.let { mon ->
                    val pill = when {
                        !yours -> foeHeading.substringAfter(" · ", "")
                        battlerAt != null || ownSlot == null -> "YOU"
                        mon.hp == 0 -> "FAINTED"
                        else -> "RESERVE"
                    }
                    FocusPage(mon, isFoe = !yours, pill, foeCount = if (yours) 1 else battlePlayer.size, showHints, dexTables, m, small)
                }
                if (foe != null && own != null) OptionButton(
                    null, m, { yours = !yours }, Modifier.align(Alignment.BottomEnd),
                    pixelIcon = PixelIcons.rotate,
                    contentDescription = tr(if (yours) "Show the foe" else "Show your Pokémon"),
                )
            }
        }
        val team = if (yours) party else foeTeam
        if (team.size > 1 || onBack != null) {
            Column(Modifier.width(NavButtonSize).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (team.size > 1) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    val cell: Dp = minOf(NavButtonSize, (maxHeight - 4.dp * (team.size - 1)) / team.size)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        team.forEachIndexed { i, mon ->
                            if (yours) FoeSlot(mon, shown = true, current = i == viewedSlot, small, cell, {
                                // Tapping the one already shown goes back to the one in battle.
                                ownSlot = if (i == viewedSlot && ownSlot != null) null else i
                            })
                            else FoeSlot(mon, i in seenFoes || i == shownFoe, i == shownFoe, small, cell, { onSelectFoe(i) })
                        }
                    }
                } else Spacer(Modifier.weight(1f))
                if (onBack != null) BackNavButton(onBack)
            }
        }
    }
}

@Composable
private fun FocusPage(
    mon: MonView, isFoe: Boolean, pill: String, foeCount: Int, showHints: Boolean, dexTables: PokedexTables?,
    m: GbaTextMetrics, small: GbaTextMetrics,
) {
    val u = m.u
    // Room for the corner button, so nothing ends up under it.
    val buttonRoom = m.rowHeight + u * 4
    val groups = if (!showHints) emptyList() else listOf(
        tr("WEAK TO") to mon.weaknesses.sortedByDescending { it.pct },
        tr("RESISTS") to mon.resistances.sortedBy { it.pct },
        tr("IMMUNE TO") to mon.immunities,
    ).filter { it.second.isNotEmpty() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(u * 3)) {
        FocusHeader(mon, pill, dexTables, m, small)
        FoeFacts(mon, dexTables, m, small, wide = true, abilityAndItem = false)
        Column(Modifier.weight(1f).fillMaxWidth().padding(bottom = if (groups.isEmpty()) buttonRoom else 0.dp)) {
            if (mon.moves.isEmpty()) GbaText(tr("NO MOVE DATA"), OptionColors.muted, OptionColors.mutedShadow, small)
            else for (pair in 0 until 2) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 6)) {
                for (j in 0 until 2) BattleMoveRow(
                    mon.moves.getOrNull(pair * 2 + j), showHints, foeCount, m, small,
                    Modifier.weight(1f).fillMaxHeight(), threat = isFoe, shortVerdict = true,
                )
            }
        }
        if (groups.isNotEmpty()) {
            Separator(m, Modifier.fillMaxWidth())
            Column(Modifier.padding(end = buttonRoom + u * 6), verticalArrangement = Arrangement.spacedBy(u * 2)) {
                groups.forEach { (label, list) -> MatchupGroup(label, list, small, inline = true) }
            }
        }
    }
}

/** Sprite, then name / types on the left and ability / held item on the right. No HP or level. */
@Composable
private fun FocusHeader(mon: MonView, pill: String, dexTables: PokedexTables?, m: GbaTextMetrics, small: GbaTextMetrics) {
    val u = m.u
    val ability = rememberFoeAbility(mon, dexTables)
    val item = mon.heldItem?.ifEmpty { tr("NONE") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(u * 36).clip(PixelRoundedShape(u * 4)).background(OptionColors.listFill),
            contentAlignment = Alignment.Center,
        ) { SpeciesIcon(mon.iconAsset, size = u * 32) }
        Spacer(Modifier.width(u * 5))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 3)) {
            Row(Modifier.heightIn(min = m.lineHeight), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
                    GbaText(mon.name, OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f, fill = false), maxLines = 1)
                    GenderMark(mon.genderSymbol, m, Modifier.padding(start = u * 2))
                    if (mon.status.isNotEmpty()) StatusBadge(mon.status, Modifier.padding(start = u * 4))
                }
                FocusFact(tr("ABILITY"), ability ?: "-", small, Modifier.weight(1.8f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    mon.types.filter { it.isNotEmpty() }.distinct().forEach { TypeBadge(it) }
                    // The pill goes here because the name line has no room for it.
                    if (pill.isNotEmpty()) VerdictPill(tr(pill), VerdictGrey)
                }
                FocusFact(tr("HELD ITEM"), item ?: "-", small, Modifier.weight(1.8f))
            }
        }
    }
}

@Composable
private fun FocusFact(label: String, value: String, small: GbaTextMetrics, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GbaText(label, OptionColors.label, OptionColors.labelShadow, small, maxLines = 1)
        Spacer(Modifier.width(small.u * 4))
        GbaText(value, OptionColors.value, OptionColors.valueShadow, small, Modifier.weight(1f), maxLines = 1)
    }
}
