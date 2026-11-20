package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveStats
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.TYPE_NONE
import com.pokedaisy.app.companion.data.TypeMatchup
import com.pokedaisy.app.companion.data.activeTypeNames
import com.pokedaisy.app.companion.data.formatMultiplier
import com.pokedaisy.app.companion.data.moveKind
import com.pokedaisy.app.companion.data.moveStats
import com.pokedaisy.app.companion.data.typeIdOf
import com.pokedaisy.app.companion.data.typeMultiplierPct
import com.pokedaisy.app.companion.data.typeName
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing

/**
 * A Pokémon's move on a page of its own (a move tapped in the summary): the four moves down the left, the one on show
 * lit like the cursor row ([selected]; a tap picks another), and on the right a panel in the move's type colour - its
 * type and category, POWER / ACCURACY / PP / PRIORITY, what it does ([moveEffectWords], from the game's move data where
 * the companion has it: [moveStats]) and the types it hits hard, weakly or not at all.
 */
@Composable
internal fun MoveDetailPage(
    mon: MonView,
    selected: Int,
    onSelect: (Int) -> Unit,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    modifier: Modifier = Modifier,
) {
    val c = OptionColors.cells
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
        HeadedBlock(tr("MOVES"), small, Modifier.weight(0.42f).fillMaxHeight(), minBody = m.u * MOVES_BODY_MIN) { headed ->
            for (i in 0 until 4) {
                val mv = mon.moves.getOrNull(i)
                val fill = when {
                    i == selected -> OptionColors.rowSelected
                    c == null -> OptionColors.listFill
                    i % 2 == 0 -> c.a
                    else -> c.b
                }
                MovePick(mv, i == selected, m, small,
                    cappedRow(m.u).fillMaxWidth().cell(fill, c?.line ?: OptionColors.divider, m.u, top = i == 0 && !headed, bottom = i == 3)) { onSelect(i) }
            }
        }
        val mv = mon.moves.getOrNull(selected)
        if (mv != null) MoveFacts(mv, m, small, Modifier.weight(0.58f).fillMaxHeight())
        else Spacer(Modifier.weight(0.58f))
    }
}

/** One of the four moves on [MoveDetailPage]'s left, a cell of their block ([modifier] draws it): the move as everywhere
 * ([MoveCell]), its category under the tag; [picked] has the cursor's ▶. */
@Composable
private fun MovePick(mv: MoveView?, picked: Boolean, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier, onClick: () -> Unit) {
    val u = m.u
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val arrow = OptionColors.style?.arrow ?: OptionColors.label
    MoveCell(
        mv, m, small,
        modifier
            .gbaFocusRing(focused, PixelRoundedShape(u * 3))
            .drawWithContent {
                drawContent()
                if (picked) {
                    // The cursor's ▶ at the right edge, on the name's line.
                    val px = u.toPx()
                    val h = 7 * px
                    val x = size.width - 5 * px
                    val y = size.height * 0.3f - h / 2
                    for (k in 0 until 4) drawRect(arrow, Offset(x + k * px, y + k * px), Size(px, h - 2 * k * px))
                }
            }
            .then(if (mv != null) Modifier.soundClickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier),
    ) { v -> MoveKindIcon(v, m) }
}

/** The right side of [MoveDetailPage]: a heading cell in the move's type colour over its facts, label cells beside value
 * cells (HG's stats page), then what it does and the types it hits, each a block of its own. */
@Composable
private fun MoveFacts(mv: MoveView, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val u = m.u
    val type = AppColors.typeColors[mv.type] ?: OptionColors.value
    val stats = remember(mv.id) { moveStats(mv.id) }
    val kind = moveKind(mv)
    val hits = remember(mv.type) { attackChart(mv.type) }
    val c = OptionColors.cells ?: com.pokedaisy.app.companion.ui.theme.CellColors(
        OptionColors.divider, OptionColors.listFill, OptionColors.well, OptionColors.value, Color.White, OptionColors.valueShadow,
    )
    val maxPp = maxOf(stats?.pp ?: 0, mv.pp)
    val rows = buildList<Pair<String, @Composable () -> Unit>> {
        add(tr("TYPE") to {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(mv.type)
                if (kind != null) {
                    MoveKindIcon(mv, m)
                    // A short screen's narrow cell: the icon alone.
                    if (!LocalShortCompanion.current) {
                        Spacer(Modifier.width(u * 3))
                        GbaText(tr(kind.label), OptionColors.label, OptionColors.labelShadow, small, maxLines = 1)
                    }
                }
            }
        })
        add(tr("POWER") to { FactValue(powerLabel(mv.power), m) })
        add(tr("ACCURACY") to { FactValue(stats?.accuracy?.takeIf { it > 0 }?.let { "$it" } ?: "—", m) })
        add(tr("PP") to { FactValue(if (maxPp > 0) "${mv.pp}/$maxPp" else "${mv.pp}", m) })
        stats?.priority?.takeIf { it != 0 }?.let { p -> add(tr("PRIORITY") to { FactValue(if (p > 0) "+$p" else "$p", m) }) }
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(u * 4)) {
        Column {
            HeadCell(mv.name, c, m, fill = type, shadow = lerp(type, Color.Black, 0.55f))
            rows.forEachIndexed { i, (label, value) ->
                val last = i == rows.lastIndex
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.weight(0.42f).fillMaxHeight().cell(c.b, c.line, u, top = false, bottom = last, right = false)
                        .padding(horizontal = u * 5, vertical = u * 2), contentAlignment = Alignment.CenterStart) {
                        GbaText(label, OptionColors.label, OptionColors.labelShadow, small, maxLines = 1, modifier = Modifier.narrowToFit(small))
                    }
                    Box(Modifier.weight(0.58f).fillMaxHeight().cell(c.a, c.line, u, top = false, bottom = last, left = false)
                        .padding(horizontal = u * 5, vertical = u * 2), contentAlignment = Alignment.CenterStart) { value() }
                }
            }
        }
        stats?.let { s -> moveEffectWords(s, mv.power) }?.let { words ->
            Column {
                HeadCell(tr("EFFECT"), c, small)
                Box(Modifier.fillMaxWidth().cell(c.a, c.line, u, top = false, bottom = true).padding(horizontal = u * 5, vertical = u * 3)) {
                    GbaText(words, OptionColors.label, OptionColors.labelShadow, small, maxLines = 3)
                }
            }
        }
        val groups = listOf(
            tr("SUPER EFFECTIVE ON") to hits.strong,
            tr("NOT VERY EFFECTIVE ON") to hits.weak,
            tr("NO EFFECT ON") to hits.none,
        ).filter { it.second.isNotEmpty() }
        if (mv.power > 0 && groups.isNotEmpty()) Column {
            HeadCell(tr("AGAINST TYPES"), c, small)
            groups.forEachIndexed { g, (label, list) ->
                Box(Modifier.fillMaxWidth().cell(if (g % 2 == 0) c.a else c.b, c.line, u, top = false, bottom = g == groups.lastIndex)
                    .padding(horizontal = u * 5, vertical = u * 3)) {
                    MatchupGroup(label, list, small)
                }
            }
        }
    }
}

@Composable
private fun FactValue(value: String, m: GbaTextMetrics) = GbaText(value, OptionColors.value, OptionColors.valueShadow, m, maxLines = 1)

private class AttackChart(val strong: List<TypeMatchup>, val weak: List<TypeMatchup>, val none: List<TypeMatchup>)

/** What a move of [typeLabel] does to each single-typed defender. */
private fun attackChart(typeLabel: String): AttackChart {
    val atk = typeIdOf(typeLabel)
    if (atk == TYPE_NONE) return AttackChart(emptyList(), emptyList(), emptyList())
    val sup = mutableListOf<TypeMatchup>(); val weak = mutableListOf<TypeMatchup>(); val none = mutableListOf<TypeMatchup>()
    val named = HashSet<String>()
    for (def in activeTypeNames.keys.sorted()) {
        val name = activeTypeNames[def] ?: continue
        if (name == "???" || !named.add(name)) continue
        val pct = typeMultiplierPct(atk, def, TYPE_NONE)
        val m = TypeMatchup(typeName(def), formatMultiplier(pct), pct)
        when {
            pct == 0 -> none += m
            pct > 100 -> sup += m
            pct < 100 -> weak += m
        }
    }
    return AttackChart(sup, weak, none)
}

/** What a move does, in plain words, from its battle effect ([MoveStats.effect], the games' own effect names); null where
 * there's nothing to say beyond its damage figures. [power] tells a damaging move's side effect from a status move's. */
internal fun moveEffectWords(s: MoveStats, power: Int): String? {
    // A side effect that may happen: its chance where the game gives one (1-99), else it always does.
    val chance = s.effectChance.takeIf { it in 1..99 }
    fun side(maybe: String, always: String) = if (chance != null) maybe else always
    return when (s.effect) {
        "HIT" -> if (power > 0) tr("Plain damage, nothing more.") else null
        "POISON_HIT" -> side(tr("{0}% chance to poison the target.", chance ?: 0), tr("Poisons the target."))
        "BURN_HIT" -> side(tr("{0}% chance to burn the target.", chance ?: 0), tr("Burns the target."))
        "FREEZE_HIT" -> side(tr("{0}% chance to freeze the target.", chance ?: 0), tr("Freezes the target."))
        "PARALYZE_HIT" -> side(tr("{0}% chance to paralyze the target.", chance ?: 0), tr("Paralyzes the target."))
        "FLINCH_HIT" -> side(tr("{0}% chance to make the target flinch.", chance ?: 0), tr("Makes the target flinch."))
        "CONFUSE_HIT" -> side(tr("{0}% chance to confuse the target.", chance ?: 0), tr("Confuses the target."))
        "SPEED_DOWN_HIT" -> side(tr("{0}% chance to lower the target's Speed.", chance ?: 0), tr("Lowers the target's Speed."))
        "ATTACK_DOWN_HIT" -> side(tr("{0}% chance to lower the target's Attack.", chance ?: 0), tr("Lowers the target's Attack."))
        "DEFENSE_DOWN_HIT" -> side(tr("{0}% chance to lower the target's Defense.", chance ?: 0), tr("Lowers the target's Defense."))
        "SPECIAL_DEFENSE_DOWN_HIT" -> side(tr("{0}% chance to lower the target's Sp. Def.", chance ?: 0), tr("Lowers the target's Sp. Def."))
        "ACCURACY_DOWN_HIT" -> side(tr("{0}% chance to lower the target's accuracy.", chance ?: 0), tr("Lowers the target's accuracy."))
        "SLEEP" -> tr("Puts the target to sleep.")
        "POISON" -> tr("Poisons the target.")
        "TOXIC" -> tr("Badly poisons the target: the damage grows each turn.")
        "PARALYZE" -> tr("Paralyzes the target.")
        "CONFUSE" -> tr("Confuses the target.")
        "ATTACK_UP" -> tr("Raises the user's Attack.")
        "DEFENSE_UP" -> tr("Raises the user's Defense.")
        "SPEED_UP_2" -> tr("Sharply raises the user's Speed.")
        "ATTACK_UP_2" -> tr("Sharply raises the user's Attack.")
        "DEFENSE_UP_2" -> tr("Sharply raises the user's Defense.")
        "ATTACK_DOWN" -> tr("Lowers the target's Attack.")
        "DEFENSE_DOWN" -> tr("Lowers the target's Defense.")
        "SPEED_DOWN" -> tr("Lowers the target's Speed.")
        "ACCURACY_DOWN" -> tr("Lowers the target's accuracy.")
        "EVASION_UP" -> tr("Raises the user's evasion.")
        "HIGH_CRITICAL" -> tr("Lands a critical hit more often.")
        "MULTI_HIT" -> tr("Hits two to five times in a row.")
        "DOUBLE_HIT" -> tr("Hits twice in a row.")
        "RECOIL" -> tr("The user is hurt by some of the damage it deals.")
        "ABSORB" -> tr("Restores the user's HP by half the damage dealt.")
        "RESTORE_HP" -> tr("Restores half of the user's HP.")
        "QUICK_ATTACK" -> tr("Strikes first.")
        "ALWAYS_HIT" -> tr("Never misses.")
        "OHKO" -> tr("Knocks the target out in one hit, if it lands.")
        "TRAP" -> tr("Traps the target and hurts it for a few turns.")
        "PROTECT" -> tr("Protects the user from moves this turn.")
        "REST" -> tr("The user sleeps for two turns and recovers fully.")
        "LEVEL_DAMAGE" -> tr("Deals damage equal to the user's level.")
        else -> null
    }
}
