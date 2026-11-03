package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.MoveVsFoe
import com.pokedaisy.app.companion.data.TYPE_NONE
import com.pokedaisy.app.companion.data.formatMultiplier
import com.pokedaisy.app.companion.data.typeMultiplierPct
import com.pokedaisy.app.companion.data.activeTypeIdByName
import com.pokedaisy.app.companion.ui.theme.AppColors

/**
 * "Who should I use, and with what move?" - ranks the player's whole living
 * party (not just whoever's currently sent out) against the current foe by a
 * heuristic combining offense (the party mon's single hardest-hitting usable
 * move, by power × type multiplier against the foe) and defense (the worst
 * multiplier the foe's own type(s) could land on that party mon), and shows
 * the top three.
 *
 * Built from BATTLE INFO's pieces so the two panes read as one: a line about
 * the foe on top, then per pick the same 50/50 split as INFO - the Pokémon's
 * card on the left ([BattlerCard]: 1ST/2ND/3RD over its icon, an OUT pill on
 * the one already in battle, the foe-types threat pill) and its best move on
 * the right ([BattleMoveRow], with its SUPER / RESISTED / NEUTRAL verdict).
 * The FOE TEAM strip sits in the bottom bar here too, so tapping another of
 * the trainer's Pokémon re-ranks against it.
 *
 * Pure client-side heuristic over data the telemetry already exports for the
 * whole party - [MonView.moves] already carries each move's own type/power,
 * and [MonView.weaknesses]/[resistances]/[immunities] already carry that
 * mon's full defensive chart (see `buildSnapshotView` in SnapshotView.kt) -
 * so this needed no ROM/protocol changes, just the same type-chart math
 * [TypeChartUtil] already does elsewhere, run against a different pairing.
 */
@Composable
fun SuggestionsScreen(
    party: List<MonView>,
    foe: MonView?,
    onBack: () -> Unit,
    onShowInfo: () -> Unit,
    modifier: Modifier = Modifier,
    /** The player's battlers, to mark the pick that's already out. */
    active: List<MonView> = emptyList(),
    foeHeading: String = "FOE",
    foeTeam: List<MonView> = emptyList(),
    seenFoes: Set<Int> = emptySet(),
    shownFoe: Int = -1,
    onSelectFoe: (Int) -> Unit = {},
) {
    // Sized to fit a whole battle: a game font keeps Pixel Operator's metrics here.
    val m = rememberGbaTextMetrics(gameScaled = false)
    val small = rememberGbaTextMetrics(1f, gameScaled = false)
    val gap = m.u * 3
    SummaryFrame(
        m, onBack, modifier,
        buttons = { TextNavButton(tr("INFO"), InfoPurple, InfoPurpleDark, onClick = onShowInfo) },
        middle = if (foeTeam.size > 1) ({ FoeTeamStrip(foeTeam, seenFoes, shownFoe, small, onSelectFoe) }) else null,
    ) {
        Column(Modifier.fillMaxSize()) {
            if (foe == null) {
                GbaText(tr("NO FOE ON THE FIELD"), OptionColors.muted, OptionColors.mutedShadow, m)
                return@Column
            }
            FoeLine(foe, foeHeading.substringAfter(" · ", ""), m, small)
            Separator(m, Modifier.fillMaxWidth().padding(vertical = m.u * 2))
            val ranked = remember(party, foe) { rankSuggestions(party, foe) }
            if (ranked.isEmpty()) {
                GbaText(tr("NO USABLE DAMAGING MOVES IN YOUR PARTY"), OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 2)
                return@Column
            }
            // Up to three picks sharing the window's height, ruled apart.
            val picks = ranked.take(3)
            picks.forEachIndexed { i, s ->
                if (i > 0) Separator(m, Modifier.fillMaxWidth().padding(vertical = m.u * 2))
                val out = isOut(s.mon, active)
                SuggestionRow(i, s, out, foe, gap, m, small, Modifier.weight(1f).fillMaxWidth())
            }
            repeat(3 - picks.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** "VS", then the foe on one line: icon, name, level, status, types, state and HP. */
@Composable
private fun FoeLine(foe: MonView, state: String, m: GbaTextMetrics, small: GbaTextMetrics) {
    val u = m.u
    Row(verticalAlignment = Alignment.CenterVertically) {
        GbaText(tr("VS"), OptionColors.value, OptionColors.valueShadow, small)
        Spacer(Modifier.width(u * 4))
        Box(
            Modifier.size(u * 24).clip(PixelRoundedShape(u * 4)).background(OptionColors.listFill),
            contentAlignment = Alignment.Center,
        ) { SpeciesIcon(foe.iconAsset, size = u * 22) }
        Spacer(Modifier.width(u * 4))
        GbaText(foe.name, OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f, fill = false))
        GenderMark(foe.genderSymbol, m, Modifier.padding(start = u * 2))
        Spacer(Modifier.width(u * 5))
        Stat(tr("Lv"), "${foe.level}", small)
        if (foe.status.isNotEmpty()) StatusBadge(foe.status, Modifier.padding(start = u * 4))
        Spacer(Modifier.width(u * 5))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            foe.types.filter { it.isNotEmpty() }.distinct().forEach { TypeBadge(it) }
        }
        if (state.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            VerdictPill(tr(state), VerdictGrey)
        }
        Spacer(Modifier.width(u * 6))
        Box(Modifier.weight(1f)) {
            SummaryBar(tr("HP"), "${foe.hp}/${foe.maxHp}", foe.hp, foe.maxHp, AppColors.hpColor(foe.hp, foe.maxHp), small)
        }
    }
}

/** One pick, split like BATTLE INFO: the Pokémon's card, then its best move vs the foe (no rule between). */
@Composable
private fun SuggestionRow(
    index: Int,
    s: Suggestion,
    out: Boolean,
    foe: MonView,
    gap: androidx.compose.ui.unit.Dp,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    modifier: Modifier,
) {
    val move = s.bestMove.copy(vs = listOf(MoveVsFoe(foe.name, formatMultiplier(s.movePct), s.movePct)))
    Row(modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(end = gap)) {
            BattlerCard(s.mon, compact = false, side = RankLabels[index], state = if (out) tk("OUT") else "", m, small, threats = listOf(foe))
        }
        BattleMoveRow(move, showHints = true, foeCount = 1, m, small, Modifier.weight(1f).fillMaxHeight().padding(start = gap))
    }
}

/** English ids; [BattlerCard] translates them. */
private val RankLabels = listOf(tk("1ST"), tk("2ND"), tk("3RD"))

/** Whether party mon [mon] is one of the player's battlers on the field (gBattleMons carries no personality to match on). */
internal fun isOut(mon: MonView, active: List<MonView>) =
    active.any { it.species == mon.species && it.level == mon.level && it.maxHp == mon.maxHp }

/** The best Pokémon to switch to against [foe] - SUGGESTIONS' top pick that isn't already out - or null. */
internal fun recommendedSwitch(party: List<MonView>, foe: MonView?, active: List<MonView>): MonView? =
    foe?.let { f -> rankSuggestions(party, f).firstOrNull { !isOut(it.mon, active) && !it.mon.isEgg }?.mon }

private data class Suggestion(
    val mon: MonView,
    val bestMove: MoveView,
    val movePct: Int,
    val effectivePower: Int,
    val defenseRiskPct: Int,
)

/** Ranks living party mons by (best usable move's effective power against
 * the foe) minus (the foe's own worst-case multiplier against that mon) -
 * favoring a hard-hitting attacker that doesn't just faint to a resisted
 * counter-hit. Mons with no usable damaging move (status-only sets, or every
 * damaging move at 0 PP) are left out - there's nothing to suggest for them. */
private fun rankSuggestions(party: List<MonView>, foe: MonView): List<Suggestion> {
    val foeTypes = foe.types.filter { it.isNotEmpty() }
    return party.mapNotNull { mon ->
        if (mon.hp <= 0) return@mapNotNull null
        val best = mon.moves
            .filter { it.power > 0 && it.pp > 0 }
            .maxByOrNull { mv -> mv.power * matchupPct(mv.type, foeTypes) }
            ?: return@mapNotNull null
        val pct = matchupPct(best.type, foeTypes)
        val risk = foeTypes.maxOfOrNull { ft -> defenseRiskFor(mon, ft) } ?: 100
        Suggestion(mon, best, pct, best.power * pct / 100, risk)
    }.sortedByDescending { it.effectivePower - it.defenseRiskPct }
}

/** Attacking-move type vs a (possibly dual-typed) defender, both given as the
 * display names this app already carries end to end (MonView.types,
 * MoveView.type) - resolved back to the int type ids [typeMultiplierPct]
 * wants via [activeTypeNames]' reverse lookup. */
private fun matchupPct(moveTypeName: String, defTypeNames: List<String>): Int {
    val atk = typeIdByName[moveTypeName] ?: return 100
    val def1 = defTypeNames.getOrNull(0)?.let { typeIdByName[it] } ?: TYPE_NONE
    val def2 = defTypeNames.getOrNull(1)?.let { typeIdByName[it] } ?: TYPE_NONE
    return typeMultiplierPct(atk, def1, def2)
}

/** How hard `attackerTypeName` (one of the foe's own types) could hit `mon` -
 * read straight off the per-mon defensive chart [SnapshotView.buildSnapshotView]
 * already computed for every party member, rather than re-deriving it. A
 * multiplier of exactly 100 (neutral) is never listed in any of the three
 * buckets, hence the 100 fallback. */
private fun defenseRiskFor(mon: MonView, attackerTypeName: String): Int =
    (mon.weaknesses + mon.resistances + mon.immunities).find { it.type == attackerTypeName }?.pct ?: 100

// Per active game, not a one-time lazy over vanilla typeNames: hacks renumber
// types (Too Many Types 2: Fire = 11, 60 custom types) or add them (Unbound's
// Fairy = 23), and a vanilla-only map silently mis-resolved or dropped those.
private val typeIdByName: Map<String, Int> get() = activeTypeIdByName()
