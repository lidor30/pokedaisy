package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.GuideTables
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.LearnOption
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveAdvice
import com.pokedaisy.app.companion.data.MoveLearning
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.TmFit
import com.pokedaisy.app.companion.data.TmLearner
import com.pokedaisy.app.companion.data.Verdict
import com.pokedaisy.app.companion.data.lookupMove
import com.pokedaisy.app.companion.data.moveStats
import com.pokedaisy.app.companion.data.typeName
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.IO

/**
 * What the move advice needs from the running game: its dex / guide [tables] (learnsets, TMs),
 * the [bag] (which TMs there are), and [showHints] - BATTLE HINTS off leaves the facts (what it
 * can learn, ABLE / NOT ABLE) and drops the verdicts.
 */
data class LearnContext(val tables: PokedexTables, val guide: GuideTables?, val bag: List<ItemView>, val showHints: Boolean)

private val LearnGreen = Color(0xFF389830)
private val ForgetRed = Color(0xFFD04030)
private val LearnedBlue = Color(0xFF4878C8)

/**
 * A value read off the UI thread for [keys]: [cached] at once (something seen before draws
 * immediately), else [load] on IO. A new key starts over from nothing - produceState keeps
 * the last key's value, which once left a TM's party row showing the TM picked before.
 */
@Composable
internal fun <T> rememberLoaded(vararg keys: Any?, cached: () -> T?, load: () -> T?): T? {
    val state = remember(*keys) { mutableStateOf(cached()) }
    LaunchedEffect(*keys) { if (state.value == null) state.value = withContext(Dispatchers.IO) { load() } }
    return state.value
}

/** [mon]'s CAN LEARN options, read off the UI thread. */
@Composable
internal fun rememberLearnOptions(ctx: LearnContext, mon: MonView): List<LearnOption>? = rememberLoaded(
    ctx, mon,
    cached = { MoveLearning.cachedOptions(ctx.tables, ctx.guide, mon, ctx.bag) },
    load = { MoveLearning.options(ctx.tables, ctx.guide, mon, ctx.bag) },
)

/**
 * The summary's CAN LEARN list: the next level-up moves, then the TMs / HMs in the bag it can learn,
 * each with what it is (type, power, accuracy, PP) and - hints on - the verdict: LEARN (a free
 * slot), FORGET <move> (worth it, in that one's place) or SKIP. [selected] (a move id) also lists why;
 * a tap picks a row, another closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CanLearnSection(
    options: List<LearnOption>?,
    selected: Int,
    onSelect: (Int) -> Unit,
    showHints: Boolean,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    modifier: Modifier = Modifier,
) {
    val u = m.u
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(u * 3)) {
        when {
            options == null -> GbaText(tr("LOADING"), optionLook.muted, optionLook.mutedShadow, small)
            options.isEmpty() -> GbaText(tr("NOTHING TO LEARN FOR NOW"), optionLook.muted, optionLook.mutedShadow, small, maxLines = 2)
            else -> {
                val (levels, tms) = options.partition { it.tm.isEmpty() }
                if (levels.isNotEmpty()) GbaText(tr("NEXT LEVELS"), optionLook.value, optionLook.valueShadow, small)
                levels.forEach { LearnRow(it, it.move == selected, onSelect, showHints, m, small) }
                if (tms.isNotEmpty()) GbaText(tr("TMs IN THE BAG"), optionLook.value, optionLook.valueShadow, small)
                tms.forEach { LearnRow(it, it.move == selected, onSelect, showHints, m, small) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LearnRow(o: LearnOption, open: Boolean, onSelect: (Int) -> Unit, showHints: Boolean, m: GbaTextMetrics, small: GbaTextMetrics) {
    val u = m.u
    val info = lookupMove(o.move)
    val type = typeName(info.type)
    val stats = moveStats(o.move)
    val edge = AppColors.typeColors[type] ?: optionLook.muted
    val openFill = optionLook.listFill
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val px = u.toPx()
                if (open) drawPixelRoundRect(openFill, radius = 3 * px, step = px)
                drawPixelRoundRect(edge, Offset(0f, 2 * px), Size(3 * px, size.height - 4 * px), radius = px, step = px)
            }
            .soundClickable { onSelect(if (open) 0 else o.move) }
            .padding(start = u * 8, end = u * 3, top = u * 2, bottom = u * 2),
        verticalArrangement = Arrangement.spacedBy(u * 2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GbaText(if (o.tm.isEmpty()) "${tr("Lv")} ${o.level}" else o.tm, optionLook.muted, optionLook.mutedShadow, small)
            Spacer(Modifier.width(u * 4))
            GbaText(info.name, optionLook.label, optionLook.labelShadow, m, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(type)
            Spacer(Modifier.weight(1f))
            Stat(tr("PWR"), powerLabel(info.power), small)
            if (stats != null) {
                Spacer(Modifier.width(u * 6))
                Stat(tr("ACC"), if (stats.accuracy == 0) "—" else "${stats.accuracy}", small)
                Spacer(Modifier.width(u * 6))
                Stat(tr("PP"), "${stats.pp}", small)
            }
        }
        // The verdict on a line of its own (a long name keeps the first); picked, why.
        if (showHints) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VerdictBadge(o.advice)
            if (open) o.advice.reasons.forEach { r -> VerdictPill(tr(r.text, *r.args.toTypedArray()), if (r.good) LearnGreen else ForgetRed) }
        }
    }
}

/** LEARN / FORGET <move> / SKIP, as a pill. */
@Composable
private fun VerdictBadge(a: MoveAdvice) {
    when (a.verdict) {
        Verdict.LEARN -> VerdictPill(tr("LEARN"), LearnGreen)
        Verdict.REPLACE -> VerdictPill(tr("FORGET {0}", lookupMove(a.forget).name), LearnGreen)
        Verdict.SKIP -> VerdictPill(tr("SKIP"), VerdictGrey)
        Verdict.KNOWN -> VerdictPill(tr("LEARNED"), LearnedBlue)
    }
}

/** The summary's FORGET tag, on the move a picked CAN LEARN option would replace. */
@Composable
internal fun ForgetTag() = VerdictPill(tr("FORGET"), ForgetRed)

/** Who in [party] TM / HM [item] suits, read off the UI thread; null = not a TM (or not read). */
@Composable
internal fun rememberTmLearners(ctx: LearnContext, party: List<MonView>, item: ItemView): List<TmLearner>? = rememberLoaded(
    ctx, party, item.itemId,
    cached = { MoveLearning.cachedLearners(ctx.tables, ctx.guide, party, item.itemId) },
    load = { MoveLearning.learners(ctx.tables, ctx.guide, party, item.itemId) },
)

/**
 * A TM's party row on ITEMS, like the game's own party screen when it's used: each Pokémon's icon over
 * ABLE / NOT ABLE / LEARNED, and - hints on - BEST on the one it suits most, with what that one should
 * forget. Drawn inside the item's description bar, in its [text] colours.
 */
@Composable
fun TmLearnersRow(ctx: LearnContext, party: List<MonView>, item: ItemView, text: Color, shadow: Color, m: GbaTextMetrics) {
    if (!MoveLearning.isTm(item.itemId) || party.isEmpty()) return
    val learners = rememberTmLearners(ctx, party, item) ?: return
    val u = m.u
    val small = rememberGbaTextMetrics(1f)
    val best = if (ctx.showHints) MoveLearning.best(learners) else null
    Column(Modifier.padding(top = u * 4), verticalArrangement = Arrangement.spacedBy(u * 3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            learners.forEach { l ->
                val mon = party[l.index]
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(u * 1)) {
                    SpeciesIcon(mon.iconAsset, size = u * 40)
                    when {
                        l.index == best -> VerdictPill(tr("BEST"), LearnGreen)
                        l.fit == TmFit.ABLE -> VerdictPill(tr("ABLE"), LearnedBlue)
                        l.fit == TmFit.LEARNED -> VerdictPill(tr("LEARNED"), LearnedBlue)
                        else -> VerdictPill(tr("NOT ABLE"), VerdictGrey)
                    }
                }
            }
        }
        if (best != null) {
            val a = learners.first { it.index == best }.advice!!
            val name = party[best].name
            GbaText(
                if (a.verdict == Verdict.REPLACE) tr("BEST FOR {0}: FORGET {1}", name, lookupMove(a.forget).name) else tr("BEST FOR {0}", name),
                text, shadow, small, maxLines = 2,
            )
        }
    }
}
