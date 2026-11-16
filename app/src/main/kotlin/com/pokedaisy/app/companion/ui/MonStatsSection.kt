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
import androidx.compose.foundation.layout.wrapContentWidth
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
import com.pokedaisy.app.companion.data.MAX_IV
import com.pokedaisy.app.companion.data.MonStats
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.STAT_ATK
import com.pokedaisy.app.companion.data.STAT_DEF
import com.pokedaisy.app.companion.data.STAT_HP
import com.pokedaisy.app.companion.data.STAT_SPATK
import com.pokedaisy.app.companion.data.STAT_SPDEF
import com.pokedaisy.app.companion.data.STAT_SPEED
import com.pokedaisy.app.companion.data.SUMMARY_STAT_ORDER
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
/** A perfect (31) IV's bar. */
private val IvPerfect = Color(0xFFE8B830)

/**
 * The summary's STATS page: per stat (the game's summary order) its value,
 * IV (a 0-31 bar) and EV, the nature's raised / lowered stats marked; then
 * NATURE, HIDDEN POWER and the totals. [MonStats.stats] are the party
 * struct's, which the game only recomputes on a level-up.
 */
@Composable
internal fun MonStatsSection(
    stats: MonStats,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    modifier: Modifier = Modifier,
    /** The individual's ability, when the summary read the data for it; null = no row. */
    ability: String? = null,
) {
    val u = m.u
    val up = natureRaises(stats.nature)
    val down = natureLowers(stats.nature)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(u * 4)) {
        StatRow(
            { },
            // Wider than its numbers: spills left into the label column rather than truncating.
            { Muted(tr("STAT"), small, Modifier.wrapContentWidth(Alignment.End, unbounded = true)) },
            { Muted(tr("IV"), small, Modifier.padding(start = u * 6)) },
            { Muted(tr("EV"), small, Modifier.wrapContentWidth(Alignment.End, unbounded = true)) },
        )
        for (s in SUMMARY_STAT_ORDER) {
            val iv = stats.ivs[s]
            StatRow(
                {
                    GbaText(tr(statLabels.getValue(s)), optionLook.label, optionLook.labelShadow, small)
                    when (s) {
                        up -> PixelIcon(PixelIcons.arrowUp, NatureUp, Modifier.padding(start = u * 2).size(small.lineHeight * 0.5f))
                        down -> PixelIcon(PixelIcons.arrowDown, NatureDown, Modifier.padding(start = u * 2).size(small.lineHeight * 0.5f))
                    }
                },
                { GbaText("${stats.stats[s]}", optionLook.label, optionLook.labelShadow, small) },
                { IvCell(iv, MAX_IV, small) },
                { GbaText("${stats.evs[s]}", optionLook.value, optionLook.valueShadow, small) },
            )
        }
        // The totals under their columns.
        StatRow(
            { GbaText(tr("TOTAL"), optionLook.label, optionLook.labelShadow, small) },
            { },
            { IvCell(stats.ivs.sum(), MAX_IV * 6, small) },
            { GbaText("${stats.evs.sum()}", optionLook.value, optionLook.valueShadow, small) },
        )
        Separator(m, Modifier.fillMaxWidth())
        InfoRow(tr("NATURE"), small) {
            GbaText(natureName(stats.nature), optionLook.value, optionLook.valueShadow, small)
        }
        ability?.let {
            InfoRow(tr("ABILITY"), small) {
                GbaText(it, optionLook.value, optionLook.valueShadow, small)
            }
        }
        InfoRow(tr("HIDDEN POWER"), small) {
            TypeBadge(hiddenPowerType(stats.ivs))
            hiddenPowerPower(stats.ivs)?.let { Spacer(Modifier.width(u * 4)); Stat(tr("PWR"), "$it", small) }
        }
    }
}

/** An IV's bar and number (or the IV total's, out of 186). */
@Composable
private fun RowScope.IvCell(value: Int, max: Int, small: GbaTextMetrics) {
    val u = small.u
    IvBar(value, max, small, Modifier.weight(1f).padding(start = u * 2))
    GbaText("$value", optionLook.value, optionLook.valueShadow, small, modifier = Modifier.padding(start = u * 3).width(small.lineHeight * 1.6f))
}

/** label | stat | IV bar + number | EV, at fixed shares of the width. */
@Composable
private fun StatRow(
    label: @Composable RowScope.() -> Unit,
    stat: @Composable RowScope.() -> Unit,
    iv: @Composable RowScope.() -> Unit,
    ev: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(0.32f), verticalAlignment = Alignment.CenterVertically, content = label)
        Row(Modifier.weight(0.14f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = stat)
        Row(Modifier.weight(0.4f), verticalAlignment = Alignment.CenterVertically, content = iv)
        Row(Modifier.weight(0.14f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = ev)
    }
}

@Composable
private fun InfoRow(label: String, small: GbaTextMetrics, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GbaText(label, optionLook.label, optionLook.labelShadow, small, modifier = Modifier.weight(0.46f))
        Row(Modifier.weight(0.54f), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun Muted(text: String, small: GbaTextMetrics, modifier: Modifier = Modifier) =
    GbaText(text, optionLook.muted, optionLook.mutedShadow, small, modifier = modifier)

/** An IV out of [max], in the HP bar's frame: gold when perfect, else the HP colours by share. */
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
                MonStatsSection(stats, m, small, if (single) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(small.lineHeight * 15))
            }
            else GbaText(tr("NO STATS FOR THIS ONE"), optionLook.muted, optionLook.mutedShadow, small)
        }
    }
}
