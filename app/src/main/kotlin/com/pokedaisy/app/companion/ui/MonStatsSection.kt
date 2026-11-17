package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.pokedaisy.app.companion.data.MonStats
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.NO_NATURE
import com.pokedaisy.app.companion.data.STAT_ATK
import com.pokedaisy.app.companion.data.STAT_DEF
import com.pokedaisy.app.companion.data.STAT_HP
import com.pokedaisy.app.companion.data.STAT_SPATK
import com.pokedaisy.app.companion.data.STAT_SPDEF
import com.pokedaisy.app.companion.data.STAT_SPEED
import com.pokedaisy.app.companion.data.SUMMARY_STAT_ORDER
import com.pokedaisy.app.companion.data.StatKind
import com.pokedaisy.app.companion.data.gen2HiddenPowerPower
import com.pokedaisy.app.companion.data.gen2HiddenPowerType
import com.pokedaisy.app.companion.data.hasHiddenPower
import com.pokedaisy.app.companion.data.hiddenPowerPower
import com.pokedaisy.app.companion.data.hiddenPowerType
import com.pokedaisy.app.companion.data.natureLowers
import com.pokedaisy.app.companion.data.natureName
import com.pokedaisy.app.companion.data.natureRaises
import com.pokedaisy.app.companion.ui.theme.AppColors

private val statLabels = mapOf(
    STAT_HP to tk("HP"), STAT_ATK to tk("ATTACK"), STAT_DEF to tk("DEFENSE"),
    STAT_SPATK to tk("SP. ATK"), STAT_SPDEF to tk("SP. DEF"), STAT_SPEED to tk("SPEED"),
)

/** The nature's raised / lowered stat marks: later games' red up, blue down. */
private val NatureUp = Color(0xFFE04040)
private val NatureDown = Color(0xFF3984F7)
/** Gen 1's summary: one SPECIAL (kept in the SP. ATK slot). */
private val GEN1_STAT_ORDER = listOf(STAT_HP, STAT_ATK, STAT_DEF, STAT_SPATK, STAT_SPEED)

/** A perfect (31) IV's bar. */
private val IvPerfect = Color(0xFFE8B830)

/**
 * The summary's STATS page: per stat (the game's summary order) its value,
 * IV (a 0-31 bar) and EV, the nature's raised / lowered stats marked; then
 * NATURE, HIDDEN POWER and the totals. [MonStats.stats] are the party
 * struct's, which the game only recomputes on a level-up. The Game Boy games
 * show DVs (0-15) and, in Gen 1 / 2, stat experience ([StatKind]): no nature
 * there, and Gen 1 has one SPECIAL row.
 */
@Composable
internal fun MonStatsSection(
    stats: MonStats,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    /** The individual's ability, when the summary read the data for it; null = no row. */
    ability: String? = null,
) {
    val u = m.u
    // A table sized to its lines: the game font at its own size, not set larger (Gen 1's 9/7 crushed the DV bars), like battle INFO.
    val small = rememberGbaTextMetrics(1f, gameScaled = false)
    val kind = stats.kind
    val up = natureRaises(stats.nature)
    val down = natureLowers(stats.nature)
    val rows = buildList {
        add(StatCells(
            label = { },
            stat = { Muted(tr("STAT"), small) },
            // Over the IV / DV numbers (the bars' ends), so STAT and DV don't read as one title.
            iv = {
                Spacer(Modifier.weight(1f))
                Muted(if (kind.dvs) tr("DV") else tr("IV"), small, Modifier.padding(start = small.u * 3).width(small.lineHeight * 1.6f))
            },
            // Stat experience: EXP, not STAT EXP - one STAT column title is enough.
            ev = { Muted(if (kind.statExp) tr("EXP") else tr("EV"), small) },
        ))
        for (s in if (kind == StatKind.GEN1) GEN1_STAT_ORDER else SUMMARY_STAT_ORDER) add(StatCells(
            label = {
                val label = if (kind == StatKind.GEN1 && s == STAT_SPATK) tk("SPECIAL") else statLabels.getValue(s)
                GbaText(tr(label), optionLook.label, optionLook.labelShadow, small)
                when (s) {
                    up -> PixelIcon(PixelIcons.arrowUp, NatureUp, Modifier.padding(start = u * 2).size(small.lineHeight * 0.5f))
                    down -> PixelIcon(PixelIcons.arrowDown, NatureDown, Modifier.padding(start = u * 2).size(small.lineHeight * 0.5f))
                }
            },
            stat = { GbaText("${stats.stats[s]}", optionLook.label, optionLook.labelShadow, small) },
            iv = { IvCell(stats.ivs[s], kind.ivMax, small) },
            ev = { GbaText("${stats.evs[s]}", optionLook.value, optionLook.valueShadow, small) },
        ))
        // The totals under their columns; Gen 1 / 2's Special counted once, stat experience has no total to reach.
        val counted = kind.distinctStats
        add(StatCells(
            label = { GbaText(tr("TOTAL"), optionLook.label, optionLook.labelShadow, small) },
            stat = { },
            iv = { IvCell(counted.sumOf { stats.ivs[it] }, kind.ivMax * counted.size, small) },
            ev = { if (!kind.statExp) GbaText("${stats.evs.sum()}", optionLook.value, optionLook.valueShadow, small) },
        ))
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(u * 4)) {
        StatTable(rows, gap = u * 4)
        val hiddenPower = hasHiddenPower(stats)
        if (stats.nature != NO_NATURE || ability != null || hiddenPower) Separator(m, Modifier.fillMaxWidth())
        // NATURE / HIDDEN POWER: labels as wide as the longer one, the values after them.
        val info = buildList<List<@Composable RowScope.() -> Unit>> {
            if (stats.nature != NO_NATURE) add(listOf(
                { GbaText(tr("NATURE"), optionLook.label, optionLook.labelShadow, small) },
                { GbaText(natureName(stats.nature), optionLook.value, optionLook.valueShadow, small) },
            ))
            ability?.let { a ->
                add(listOf(
                    { GbaText(tr("ABILITY"), optionLook.label, optionLook.labelShadow, small) },
                    { GbaText(a, optionLook.value, optionLook.valueShadow, small) },
                ))
            }
            if (hiddenPower) add(listOf(
                { GbaText(tr("HIDDEN POWER"), optionLook.label, optionLook.labelShadow, small) },
                {
                    val gen2 = kind == StatKind.GEN2
                    TypeBadge(if (gen2) gen2HiddenPowerType(stats.ivs) else hiddenPowerType(stats.ivs))
                    (if (gen2) gen2HiddenPowerPower(stats.ivs) else hiddenPowerPower(stats.ivs))
                        ?.let { Spacer(Modifier.width(u * 4)); Stat(tr("PWR"), "$it", small) }
                },
            ))
        }
        if (info.isNotEmpty()) CellTable(info, flex = 1, endAligned = emptySet(), header = false, gap = u * 4)
    }
}

/** An IV's bar and number (or the IV total's, out of 186; a DV's out of 15): the bar takes what the number leaves. */
@Composable
private fun RowScope.IvCell(value: Int, max: Int, small: GbaTextMetrics) {
    val u = small.u
    IvBar(value, max, small, Modifier.weight(1f))
    GbaText("$value", optionLook.value, optionLook.valueShadow, small, modifier = Modifier.padding(start = u * 3).width(small.lineHeight * 1.6f))
}

/** One row of the STATS table: its label, stat, IV (bar + number) and EV cells. */
private class StatCells(
    val label: @Composable RowScope.() -> Unit,
    val stat: @Composable RowScope.() -> Unit,
    val iv: @Composable RowScope.() -> Unit,
    val ev: @Composable RowScope.() -> Unit,
) {
    val cells get() = listOf(label, stat, iv, ev)
}

/** The STATS table ([CellTable]): the IV bars take what the labels, stats and EVs leave; stats and EVs right-aligned. */
@Composable
private fun StatTable(rows: List<StatCells>, gap: Dp) =
    CellTable(rows.map { it.cells }, flex = 2, endAligned = setOf(1, 3), header = true, gap = gap)

/**
 * Rows of cells in columns, each column as wide as its widest cell - any font, language or label (a Game Boy game's
 * larger font cut DEFENSE short in fixed shares) - but column [flex], which takes the width left. A [header] row doesn't
 * widen its column: its cells sit at the column's edge (right for [endAligned] columns) and may spill over the space
 * beside them, as the column titles always did. Each row is as tall as its tallest cell, [gap] between rows and columns.
 */
@Composable
private fun CellTable(rows: List<List<@Composable RowScope.() -> Unit>>, flex: Int, endAligned: Set<Int>, header: Boolean, gap: Dp) {
    val cols = rows.firstOrNull()?.size ?: return
    Layout(
        content = {
            for (row in rows) for ((c, cell) in row.withIndex()) Row(
                horizontalArrangement = if (c in endAligned) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                content = cell,
            )
        },
    ) { measurables, constraints ->
        val g = gap.roundToPx()
        fun cell(r: Int, c: Int) = measurables[r * cols + c]
        val body = (if (header) 1 else 0) until rows.size
        // The last column's title counts too: spilling left, a wider title (stat experience's EXP over 0s) ran into the
        // flex column's own title (DV) on a narrow screen.
        val widths = IntArray(cols) { c ->
            val counted = if (header && c == cols - 1) rows.indices else body
            if (c == flex) 0 else counted.maxOfOrNull { cell(it, c).maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        }
        widths[flex] = (constraints.maxWidth - widths.sum() - g * (cols - 1)).coerceAtLeast(0)
        val starts = IntArray(cols).also { for (c in 1 until cols) it[c] = it[c - 1] + widths[c - 1] + g }
        val placeables = rows.indices.map { r ->
            List(cols) { c ->
                val m = cell(r, c)
                if (header && r == 0 && c != flex) m.measure(Constraints(maxWidth = starts[c] + widths[c]))   // may spill left
                else m.measure(Constraints.fixedWidth(widths[c]))
            }
        }
        val heights = placeables.map { row -> row.maxOf { it.height } }
        val total = heights.sum() + g * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, total) {
            var y = 0
            for ((r, row) in placeables.withIndex()) {
                for ((c, p) in row.withIndex()) {
                    val x = if (c in endAligned || p.width > widths[c]) starts[c] + widths[c] - p.width else starts[c]
                    p.place(x, y + (heights[r] - p.height) / 2)
                }
                y += heights[r] + g
            }
        }
    }
}

@Composable
private fun Muted(text: String, small: GbaTextMetrics, modifier: Modifier = Modifier) =
    GbaText(text, optionLook.muted, optionLook.mutedShadow, small, modifier = modifier)

/** An IV (or DV) out of [max], in the HP bar's frame: gold when perfect, else the HP colours by share. */
@Composable
private fun IvBar(iv: Int, max: Int, small: GbaTextMetrics, modifier: Modifier) {
    val u = small.u
    val color = if (iv >= max) IvPerfect else AppColors.hpColor(iv, max)
    val frameDark = optionLook.frameDark
    Box(
        modifier
            .height(u * 6)
            .drawBehind {
                val px = u.toPx()
                drawPixelRoundRect(frameDark, radius = 2 * px, step = px)
                val inner = Size(size.width - 2 * px, size.height - 2 * px)
                drawPixelRoundRect(Color(0xFF506858), Offset(px, px), inner, radius = px, step = px)
                val fill = kotlin.math.floor(inner.width * iv / max / px) * px
                if (fill > 0) drawPixelRoundRect(color, Offset(px, px), Size(fill, inner.height), radius = px, step = px)
            },
    )
}

/**
 * Battle INFO's STATS page: your battler(s) left, the foe(s) right, each a
 * name line over its [MonStatsSection] - "?" where a battler's stats weren't
 * found (no party struct matched it).
 */
@Composable
fun BattleStatsScreen(player: List<MonView>, opponent: List<MonView>, foeHeading: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val gap = m.u * 3
    SummaryFrame(m, onBack, modifier) {
        Row(Modifier.fillMaxSize()) {
            StatsColumn(tk("YOU"), player, m, small, Modifier.weight(1f).fillMaxHeight().padding(end = gap))
            Separator(m, Modifier.fillMaxHeight(), vertical = true)
            StatsColumn(foeHeading.substringBefore(" · "), opponent, m, small, Modifier.weight(1f).fillMaxHeight().padding(start = gap))
        }
    }
}

@Composable
private fun StatsColumn(side: String, mons: List<MonView>, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val u = m.u
    // One battler fills the column (its table scrolls if it must); a double's two scroll together.
    val single = mons.size <= 1
    Column(if (single) modifier else modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(u * 3)) {
        if (mons.isEmpty()) GbaText(tr(side), optionLook.value, optionLook.valueShadow, small)
        mons.forEachIndexed { i, mon ->
            if (i > 0) Separator(m, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(u * 22).clip(PixelRoundedShape(u * 4)).background(optionLook.listFill),
                    contentAlignment = Alignment.Center,
                ) { SpeciesIcon(mon.iconAsset, size = u * 18) }
                Spacer(Modifier.width(u * 4))
                Column(Modifier.weight(1f)) {
                    GbaText(tr(side), optionLook.value, optionLook.valueShadow, small)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GbaText(mon.name, optionLook.label, optionLook.labelShadow, m, modifier = Modifier.weight(1f, fill = false))
                        GenderMark(mon.genderSymbol, m, Modifier.padding(start = u * 2))
                        Spacer(Modifier.width(u * 4))
                        Stat(tr("Lv"), "${mon.level}", small)
                    }
                }
            }
            val stats = mon.stats
            if (stats != null) {
                MonStatsSection(stats, m, if (single) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(small.lineHeight * 15))
            }
            else GbaText(tr("NO STATS FOR THIS ONE"), optionLook.muted, optionLook.mutedShadow, small)
        }
    }
}
