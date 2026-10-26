package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.PokedexTables
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.TYPE_NONE
import com.pokedaisy.app.companion.data.TypeMatchup
import com.pokedaisy.app.companion.data.formatMultiplier
import com.pokedaisy.app.companion.data.typeIdOf
import com.pokedaisy.app.companion.data.typeMultiplierPct
import com.pokedaisy.app.companion.data.withMovesVs
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.LocalGameFont
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.foundation.interaction.collectIsFocusedAsState

/**
 * The BATTLE tab's INFO pane, laid out around the questions a player has
 * mid-battle, most important first:
 *
 *  1. Who's out, and how healthy? - a compact card per battler across the top
 *     (YOU left, FOE right): icon, name, level, status, types, HP.
 *  2. Which of my moves should I use? - YOUR MOVES, bottom-left, in the
 *     Pokémon summary's move-row look, each with a plain-words verdict against
 *     the foe (SUPER 2x / RESISTED ½x / NEUTRAL 1x / IMMUNE 0x ...).
 *  3. What hits the foe hard, and what does it threaten me with? - bottom-right:
 *     the foe's WEAK TO / RESISTS / IMMUNE TO, then how its own types land on
 *     my Pokémon.
 *
 * Everything fits the window without scrolling in a single battle (doubles
 * scroll). With hints off (SETTINGS), the verdicts and the matchup column
 * are hidden and the moves take the full width. Touch control is
 * [BattleControlsScreen]; [onBack] null = no way back (it isn't the player's
 * turn, so there are no controls to go back to).
 *
 * In a trainer battle the bottom bar carries the FOE TEAM ([foeTeam], the
 * trainer's whole party in order) between SUGGESTIONS and Back, the way the
 * Pokémon summary carries the party strip: Pokémon already sent out or
 * announced ([seenFoes], party indexes) show, the rest are Poké Balls until
 * tapped; fainted ones are faded. The foe the screen is about ([shownFoe],
 * framed in red) is the one out, the one about to come in, or whichever slot
 * was tapped ([onSelectFoe]; tapping it again goes back). [foeHeading] says which.
 * [onShowStats] (SETTINGS > FOE IVS, when the foe's IVs were read) adds STATS
 * beside SUGGESTIONS: [BattleStatsScreen].
 *
 * FOE DETAILS (GitHub #34): under the foe's matchups (scroll down) come its
 * ability, held item, stats with their stages, and its moveset - each move with
 * what it does to the player's battler. [onToggleFoeDetails] adds a button,
 * floating bottom-right, that hands the foe nearly the whole window
 * ([foeDetails], kept across battles): the player's side shrinks to a strip of
 * sprite and HP. [dexTables] names the foe's ability from the ROM.
 */
@Composable
fun BattleInfoScreen(
    player: List<MonView>,
    opponent: List<MonView>,
    isDouble: Boolean,
    showHints: Boolean,
    onBack: (() -> Unit)?,
    onShowSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
    foeTeam: List<MonView> = emptyList(),
    seenFoes: Set<Int> = emptySet(),
    shownFoe: Int = -1,
    foeHeading: String = "FOE",
    onSelectFoe: (Int) -> Unit = {},
    onShowStats: (() -> Unit)? = null,
    foeDetails: Boolean = false,
    onToggleFoeDetails: (() -> Unit)? = null,
    dexTables: PokedexTables? = null,
) {
    // Sized to fit a whole battle: a game font keeps Pixel Operator's metrics here.
    val m = rememberGbaTextMetrics(gameScaled = false)
    val small = rememberGbaTextMetrics(1f, gameScaled = false)
    val gap = m.u * 3
    // The foe's moves, each with what it does to the player's battler(s).
    val foes = remember(opponent, player) { opponent.map { it.withMovesVs(player) } }
    // Room under the scrolling foe column for the floating button, so nothing ends up behind it.
    val buttonRoom = if (onToggleFoeDetails != null) m.rowHeight + m.u * 4 else 0.dp
    SummaryFrame(
        m, onBack, modifier,
        buttons = {
            TextNavButton(tr("SUGGESTIONS"), SuggestGold, SuggestGoldDark, onShowSuggestions)
            if (onShowStats != null) TextNavButton(tr("STATS"), StatsPurple, StatsPurpleDark, onShowStats)
        },
        middle = if (foeTeam.size > 1) ({ FoeTeamStrip(foeTeam, seenFoes, shownFoe, small, onSelectFoe) }) else null,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (foeDetails) Column(Modifier.fillMaxSize()) {
                // The player's side shrinks to a strip: YOU and the sprite level with the
                // foe card's FOE and icon, the HP standing under them.
                val strip = Modifier.width(m.u * (PLAYER_STRIP_ICON + 8))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    PlayerStripSprites(player, m, small, strip.fillMaxHeight().padding(end = gap))
                    Separator(m, Modifier.fillMaxHeight(), vertical = true)
                    BattlerCards(foeHeading, foes, m, small, Modifier.weight(1f).padding(start = gap))
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    PlayerStripHp(player, m, small, strip.fillMaxHeight().padding(end = gap, top = m.u * 4))
                    Separator(m, Modifier.fillMaxHeight(), vertical = true)
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = gap)) {
                        Separator(m, Modifier.fillMaxWidth().padding(vertical = m.u * 2))
                        FoeDetailsColumn(foes, player.size, showHints, matchups = showHints, dexTables, m, small,
                            Modifier.fillMaxWidth().weight(1f), expanded = true, bottomRoom = buttonRoom)
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
                // 1. Who's out.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    BattlerCards(tk("YOU"), player, m, small, Modifier.weight(1f).padding(end = gap),
                        threats = if (showHints) opponent else emptyList())
                    Separator(m, Modifier.fillMaxHeight(), vertical = true)
                    BattlerCards(foeHeading, foes, m, small, Modifier.weight(1f).padding(start = gap))
                }
                Separator(m, Modifier.fillMaxWidth().padding(vertical = m.u * 2))
                // 2 + 3. What to do about it.
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    MovesColumn(player, opponent.size, showHints, m, small, Modifier.weight(1f).fillMaxHeight().padding(end = gap))
                    if (showHints || onToggleFoeDetails != null) {
                        Separator(m, Modifier.fillMaxHeight(), vertical = true)
                        FoeDetailsColumn(foes, player.size, showHints, matchups = showHints, dexTables, m, small,
                            Modifier.weight(1f).fillMaxHeight().padding(start = gap), bottomRoom = buttonRoom)
                    }
                }
            }
            if (onToggleFoeDetails != null) {
                FoeDetailsToggle(foeDetails, m, onToggleFoeDetails, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/** FOE DETAILS' button: the expand / shrink corners of a "full screen" switch. */
@Composable
private fun FoeDetailsToggle(expanded: Boolean, m: GbaTextMetrics, onClick: () -> Unit, modifier: Modifier) {
    OptionButton(
        null, m, onClick, modifier,
        pixelIcon = if (expanded) PixelIcons.shrink else PixelIcons.expand,
        contentDescription = tr(if (expanded) "Hide foe details" else "Show foe details"),
    )
}

private const val PLAYER_STRIP_ICON = 36
private const val PLAYER_STRIP_ICON_DOUBLE = 22

/**
 * FOE DETAILS' player side, top: YOU over each battler's sprite, laid out like
 * [BattlerCard]'s side + icon column, so YOU sits on the foe's name line beside
 * FOE and the sprite level with its icon (the card sizes as
 * [BattlerCard] does: a full icon alone, compact ones in a double battle).
 */
@Composable
private fun PlayerStripSprites(player: List<MonView>, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val u = m.u
    val icon = if (player.size > 1) PLAYER_STRIP_ICON_DOUBLE else PLAYER_STRIP_ICON
    val nameLine = battlerNameLine(player.size > 1, m, small)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(u * 3), horizontalAlignment = Alignment.CenterHorizontally) {
        player.forEachIndexed { i, mon ->
            Column(horizontalAlignment = Alignment.Start) {
                if (i == 0) SideLabel(tk("YOU"), nameLine, small)
                Box(
                    Modifier.size(u * icon).clip(PixelRoundedShape(u * 4)).background(OptionColors.listFill),
                    contentAlignment = Alignment.Center,
                ) { SpeciesIcon(mon.iconAsset, size = u * (icon - 4)) }
            }
        }
    }
}

/** FOE DETAILS' player side, below the sprites: each battler's HP as an upright bar
 * filling the height (side by side in a double battle), its status and numbers under it. */
@Composable
private fun PlayerStripHp(player: List<MonView>, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val u = m.u
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        player.forEach { mon ->
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(u * 2)) {
                UprightHpBar(mon.hp, mon.maxHp, u, Modifier.weight(1f).width(u * 8))
                if (mon.status.isNotEmpty()) StatusBadge(mon.status)
                GbaText("${mon.hp}", OptionColors.label, OptionColors.labelShadow, small, maxLines = 1)
                GbaText("/${mon.maxHp}", OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 1)
            }
        }
    }
}

/** [SummaryBar]'s HP bar stood on end: it empties from the top. */
@Composable
private fun UprightHpBar(hp: Int, maxHp: Int, u: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val frac = if (maxHp > 0) (hp.toFloat() / maxHp).coerceIn(0f, 1f) else 0f
    val color = AppColors.hpColor(hp, maxHp)
    Box(modifier.drawBehind {
        val px = u.toPx()
        drawPixelRoundRect(OptionColors.frameDark, radius = 2 * px, step = px)
        val inner = Size(size.width - 2 * px, size.height - 2 * px)
        drawPixelRoundRect(Color(0xFF506858), Offset(px, px), inner, radius = px, step = px)
        val fill = kotlin.math.floor(inner.height * frac / px) * px
        if (fill > 0) drawPixelRoundRect(color, Offset(px, px + inner.height - fill), Size(inner.width, fill), radius = px, step = px)
    })
}

/**
 * What the foe brings beyond its types: ABILITY and HELD ITEM, then its stats as
 * the battle has them, a stage pill (+2 green, -1 red) on any that's been raised
 * or lowered, and accuracy / evasion when they've moved. A Pokémon the trainer
 * hasn't sent out has no battle stats yet: its party stats stand in. Not [wide]
 * (INFO's half-width column): a line each for ability and item, stats three across.
 * [abilityAndItem] off leaves out the ability and item (BATTLE FOCUS shows them in its header).
 */
@Composable
internal fun FoeFacts(
    foe: MonView, dexTables: PokedexTables?, m: GbaTextMetrics, small: GbaTextMetrics, wide: Boolean,
    abilityAndItem: Boolean = true,
) {
    val u = m.u
    val ability = rememberFoeAbility(foe, dexTables).takeIf { abilityAndItem }
    // Attack, Defense, Speed, Sp. Atk, Sp. Def - the battle's, else the party struct's.
    val stats = foe.battleStats ?: foe.stats?.stats?.drop(1)
    val item = foe.heldItem?.ifEmpty { tr("NONE") }?.takeIf { abilityAndItem }
    if (ability == null && item == null && stats == null) return
    Column(verticalArrangement = Arrangement.spacedBy(u * 2)) {
        if (abilityAndItem) {
            if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
                ability?.let { FactPair(tr("ABILITY"), it, small, Modifier.weight(1f)) }
                item?.let { FactPair(tr("HELD ITEM"), it, small, Modifier.weight(1f)) }
            } else {
                ability?.let { FactPair(tr("ABILITY"), it, small, Modifier.fillMaxWidth()) }
                item?.let { FactPair(tr("HELD ITEM"), it, small, Modifier.fillMaxWidth()) }
            }
        }
        if (stats != null && stats.size == 5) {
            // The summary's order: Attack, Defense, Sp. Atk, Sp. Def, Speed.
            val order = listOf(0 to "ATTACK", 1 to "DEFENSE", 3 to "SP. ATK", 4 to "SP. DEF", 2 to "SPEED")
            val across = if (wide) 5 else 3
            order.chunked(across).forEach { line ->
                Row(Modifier.fillMaxWidth()) {
                    for (k in 0 until across) Column(Modifier.weight(1f)) {
                        val (i, label) = line.getOrNull(k) ?: return@Column
                        GbaText(tr(label), OptionColors.label, OptionColors.labelShadow, small, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GbaText("${stats[i]}", OptionColors.value, OptionColors.valueShadow, m, maxLines = 1)
                            foe.statStages?.getOrNull(i)?.takeIf { it != 0 }?.let { StagePill(it, Modifier.padding(start = u * 2)) }
                        }
                    }
                }
            }
            val others = listOf("ACCURACY" to foe.statStages?.getOrNull(5), "EVASION" to foe.statStages?.getOrNull(6))
                .filter { (it.second ?: 0) != 0 }
            if (others.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(u * 6), verticalAlignment = Alignment.CenterVertically) {
                others.forEach { (label, stage) ->
                    GbaText(tr(label), OptionColors.label, OptionColors.labelShadow, small, maxLines = 1)
                    StagePill(stage!!, Modifier.padding(start = u * 2))
                }
            }
        }
    }
}

/** The ability's name, read from the ROM if the battle didn't give one. */
@Composable
internal fun rememberFoeAbility(foe: MonView, dexTables: PokedexTables?): String? = rememberLoaded(
    foe.abilityName, dexTables, foe.abilityId,
    cached = { foe.abilityName ?: dexTables?.let { PokedexSource.cachedAbilityName(it, foe.abilityId) } },
    load = { dexTables?.let { PokedexSource.abilityName(it, foe.abilityId) } },
)

@Composable
private fun FactPair(label: String, value: String, small: GbaTextMetrics, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GbaText(label, OptionColors.label, OptionColors.labelShadow, small, maxLines = 1)
        Spacer(Modifier.width(small.u * 4))
        GbaText(value, OptionColors.value, OptionColors.valueShadow, small, Modifier.weight(1f, fill = false), maxLines = 1)
    }
}

/** A stat stage: "+2" on the foe is bad news for the player (red), "-1" good (green). */
@Composable
private fun StagePill(stage: Int, modifier: Modifier = Modifier) {
    Box(modifier) { VerdictPill(if (stage > 0) "+$stage" else "$stage", if (stage > 0) verdictColor(50) else verdictColor(200)) }
}

/**
 * A card per battler (two in a double battle). [heading] is "SIDE" or
 * "SIDE · STATE" (English ids, translated here): the side goes above the
 * first icon, the state (e.g. NEXT) in a pill beside the name - so neither
 * costs a line.
 */
@Composable
private fun BattlerCards(
    heading: String,
    mons: List<MonView>,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    modifier: Modifier,
    /** The other side's Pokémon, for the [ThreatPill] (player side, hints on). */
    threats: List<MonView> = emptyList(),
) {
    val side = heading.substringBefore(" · ")
    val state = heading.substringAfter(" · ", "")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.u * 3)) {
        if (mons.isEmpty()) GbaText(tr(side), OptionColors.value, OptionColors.valueShadow, small)
        mons.forEachIndexed { i, mon ->
            BattlerCard(mon, compact = mons.size > 1, if (i == 0) side else null, state, m, small, threats)
        }
    }
}

/** [side] over the icon, on the name's line; name / gender / status, then types / level / a pill, then HP.
 * [side] and [state] are English ids ("FOE", "1ST", "NEXT", "OUT"), translated here. */
@Composable
internal fun BattlerCard(
    mon: MonView,
    compact: Boolean,
    side: String?,
    state: String,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    threats: List<MonView>,
) {
    val u = m.u
    val icon = if (compact) 22 else 36
    val nameLine = battlerNameLine(compact, m, small)
    Row(verticalAlignment = Alignment.Top) {
        // At least the icon's width; wider when the side's word is (GEGNER, ENEMIGO).
        Column(Modifier.widthIn(min = u * icon)) {
            if (side != null) SideLabel(side, nameLine, small)
            Box(
                Modifier.size(u * icon).clip(PixelRoundedShape(u * 4)).background(OptionColors.listFill),
                contentAlignment = Alignment.Center,
            ) { SpeciesIcon(mon.iconAsset, size = u * (icon - 4)) }
        }
        Spacer(Modifier.width(u * 5))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 2)) {
            Row(Modifier.heightIn(min = nameLine), verticalAlignment = Alignment.CenterVertically) {
                // The name takes what the state pill leaves.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    GbaText(mon.name, OptionColors.label, OptionColors.labelShadow, if (compact) small else m, modifier = Modifier.weight(1f, fill = false))
                    GenderMark(mon.genderSymbol, if (compact) small else m, Modifier.padding(start = u * 2))
                    if (compact) {
                        Spacer(Modifier.width(u * 4))
                        Stat(tr("Lv"), "${mon.level}", small)
                    }
                    if (mon.status.isNotEmpty()) StatusBadge(mon.status, Modifier.padding(start = u * 4))
                }
                if (!compact && state.isNotEmpty()) VerdictPill(tr(state), VerdictGrey)
            }
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    mon.types.filter { it.isNotEmpty() }.distinct().forEach { TypeBadge(it) }
                    Spacer(Modifier.width(u * 2))
                    Stat(tr("Lv"), "${mon.level}", small)
                    Spacer(Modifier.weight(1f))
                    ThreatPill(mon, threats)
                }
            }
            SummaryBar(tr("HP"), "${mon.hp}/${mon.maxHp}", mon.hp, mon.maxHp, AppColors.hpColor(mon.hp, mon.maxHp), small)
        }
    }
}

/** The height of [BattlerCard]'s name line, which its side label shares. */
private fun battlerNameLine(compact: Boolean, m: GbaTextMetrics, small: GbaTextMetrics) =
    (if (compact) small else m).lineHeight

/** YOU / FOE / 1ST, centred on a name line [height] tall. */
@Composable
private fun SideLabel(side: String, height: androidx.compose.ui.unit.Dp, small: GbaTextMetrics) {
    Box(Modifier.height(height), contentAlignment = Alignment.CenterStart) {
        GbaText(tr(side), OptionColors.value, OptionColors.valueShadow, small, maxLines = 1)
    }
}

/** YOUR MOVES: the summary's move rows, one per move, sharing out the height. */
@Composable
private fun MovesColumn(player: List<MonView>, foeCount: Int, showHints: Boolean, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier) {
    val single = player.size <= 1
    Column(if (single) modifier else modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (single) 0.dp else m.u * 2)) {
        if (player.isEmpty()) GbaText("-", OptionColors.muted, OptionColors.mutedShadow, small)
        player.forEach { mon ->
            // (A single battle's moves need no heading - the verdicts say whose they are.)
            if (!single) GbaText(tr("{0}'S MOVES", mon.name), OptionColors.value, OptionColors.valueShadow, small)
            if (mon.moves.isEmpty()) GbaText(tr("NO MOVE DATA"), OptionColors.muted, OptionColors.mutedShadow, small)
            val rowMod = if (single) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(m.u * 56)
            for (i in 0 until if (single) 4 else mon.moves.size) {
                BattleMoveRow(mon.moves.getOrNull(i), showHints, foeCount, m, small, rowMod)
            }
        }
    }
}

/** A type-colored stripe, the name with its verdict vs the foe, then type / power / PP. */
@Composable
internal fun BattleMoveRow(
    mv: MoveView?, showHints: Boolean, foeCount: Int, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier,
    /** A foe's move: its verdicts are what it does to the player, so super effective reads red. */
    threat: Boolean = false,
    /** Just the multiplier beside the name (FOE DETAILS' half-width grid). */
    shortVerdict: Boolean = false,
) {
    val u = m.u
    val edge = mv?.let { AppColors.typeColors[it.type] } ?: OptionColors.muted
    // Out of PP: still listed, but faded - it can't be picked.
    Box(
        modifier
            .alpha(if (mv != null && mv.pp == 0) 0.45f else 1f)
            .drawBehind {
                val px = u.toPx()
                if (mv != null) drawPixelRoundRect(edge, Offset(0f, 2 * px), Size(3 * px, size.height - 4 * px), radius = px, step = px)
            }
            .padding(start = u * 8),
    ) {
        if (mv == null) {
            GbaText("—", OptionColors.muted, OptionColors.mutedShadow, small, modifier = Modifier.align(Alignment.CenterStart))
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            // A fixed-width game font (Gen 1's 8px letters: THUNDERBOLT is 11 of them) gets
            // the whole line for the name; the verdict moves down beside the type.
            val wideFont = LocalGameFont.current != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                GbaText(mv.name, OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f))
                if (!wideFont && showHints && foeCount <= 1) MoveVerdict(mv, foeCount, short = shortVerdict, threat = threat)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(mv.type)
                if (wideFont && showHints && foeCount <= 1) {
                    Spacer(Modifier.width(u * 4))
                    MoveVerdict(mv, foeCount, short = true, threat = threat)
                }
                Spacer(Modifier.weight(1f))
                Stat(tr("PWR"), powerLabel(mv.power), small)
                Spacer(Modifier.width(u * 6))
                Stat(tr("PP"), "${mv.pp}", small)
            }
            // Doubles: a chip per foe needs a line of its own.
            if (showHints && foeCount > 1) Row { Spacer(Modifier.weight(1f)); MoveVerdict(mv, foeCount, threat = threat) }
        }
    }
}

/** How [mv] fares: one plain-words pill against a single foe, a "NAME 2x"
 * chip per foe in a double battle. Status moves ignore the type chart. */
@Composable
private fun MoveVerdict(mv: MoveView, foeCount: Int, short: Boolean = false, threat: Boolean = false) {
    // A foe's move against the player: what's good for it is bad news.
    fun color(pct: Int) = if (threat) threatColor(pct) else verdictColor(pct)
    when {
        mv.pp == 0 -> VerdictPill(tr("NO PP"), VerdictGrey)
        mv.power == 0 -> if (!short) VerdictPill(tr("STATUS"), VerdictGrey)   // short: its "PWR -" says it
        mv.vs.isEmpty() -> {}
        foeCount > 1 || mv.vs.size > 1 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            mv.vs.forEach { v -> VerdictPill("${v.vsName} ${v.label}", color(v.pct)) }
        }
        // Just the multiplier, where the line is short (its colour still says which).
        short -> mv.vs[0].let { v -> VerdictPill(v.label, color(v.pct)) }
        else -> mv.vs[0].let { v ->
            val words = when {
                v.pct == 0 -> tr("IMMUNE {0}", v.label)
                v.pct > 100 -> tr("SUPER {0}", v.label)
                v.pct < 100 -> tr("RESISTED {0}", v.label)
                else -> tr("NEUTRAL {0}", v.label)
            }
            VerdictPill(words, color(v.pct))
        }
    }
}

@Composable
internal fun VerdictPill(text: String, color: Color) {
    Box(Modifier.clip(PixelCornerShape(3.dp)).background(color).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp, fontFamily = pixelFontFamily(), maxLines = 1, softWrap = false)
    }
}

/** Darker than [AppColors.multiplierColor]'s bar colors, so white text reads on them. */
internal fun verdictColor(pct: Int): Color = when {
    pct == 0 -> Color(0xFF585048)
    pct > 100 -> Color(0xFF389830)
    pct < 100 -> Color(0xFFD04030)
    else -> VerdictGrey
}

/** [verdictColor] from the player's side: a foe's super-effective hit is red, a resisted one green. */
private fun threatColor(pct: Int): Color = when {
    pct == 0 || pct < 100 -> verdictColor(200)
    pct > 100 -> verdictColor(50)
    else -> VerdictGrey
}

internal val VerdictGrey = Color(0xFF8C8C94)
/** Battle INFO's STATS button. */
internal val StatsPurple = Color(0xFF9070D0)
internal val StatsPurpleDark = Color(0xFF5C4490)

/** The foe's defensive chart - what to hit it with (strongest first), and what
 * not to - then [FoeFacts] and its moves with what each does to the player's
 * battler(s) ([playerCount]). Scrolls when it's long, with a fade at the bottom
 * edge while there's more below; a single battle's matchups fit above the fold,
 * as they did before the rest was added. [matchups] off (hints off) leaves them
 * out. [expanded] (FOE DETAILS) puts the facts and moves first, the moves two by
 * two, and the matchups after, a line a group. [bottomRoom] keeps the end clear
 * of the floating button. */
@Composable
private fun FoeDetailsColumn(
    opponent: List<MonView>, playerCount: Int, showHints: Boolean, matchups: Boolean, dexTables: PokedexTables?,
    m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier,
    expanded: Boolean = false, bottomRoom: androidx.compose.ui.unit.Dp = 0.dp,
) {
    // A new foe starts at the top.
    val scroll = key(opponent.map { it.species to it.level }) { rememberScrollState() }
    // A double battle's verdicts take a line of their own (a pill per target).
    val moveRow = m.u * if (showHints && playerCount > 1) 64 else 48
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = bottomRoom), verticalArrangement = Arrangement.spacedBy(m.u * 3)) {
            opponent.forEachIndexed { i, foe ->
                if (i > 0) Separator(m, Modifier.fillMaxWidth())
                val groups = listOf(
                    tr("WEAK TO") to foe.weaknesses.sortedByDescending { it.pct },
                    tr("RESISTS") to foe.resistances.sortedBy { it.pct },
                    tr("IMMUNE TO") to foe.immunities,
                ).filter { it.second.isNotEmpty() }
                if (expanded) {
                    if (opponent.size > 1) GbaText(foe.name, OptionColors.value, OptionColors.valueShadow, small)
                    FoeFacts(foe, dexTables, m, small, wide = true)
                    if (foe.moves.isEmpty()) GbaText(tr("NO MOVE DATA"), OptionColors.muted, OptionColors.mutedShadow, small)
                    else foe.moves.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.u * 6)) {
                            for (j in 0 until 2) BattleMoveRow(pair.getOrNull(j), showHints, playerCount, m, small,
                                Modifier.weight(1f).height(moveRow), threat = true, shortVerdict = true)
                        }
                    }
                    if (matchups && groups.isNotEmpty()) {
                        Separator(m, Modifier.fillMaxWidth())
                        groups.forEach { (label, list) -> MatchupGroup(label, list, small, inline = true) }
                    }
                    return@forEachIndexed
                }
                if (matchups) {
                    GbaText(tr("{0} IS", foe.name), OptionColors.value, OptionColors.valueShadow, small)
                    if (groups.isEmpty()) GbaText(tr("NO MATCHUP DATA"), OptionColors.muted, OptionColors.mutedShadow, small)
                    groups.forEach { (label, list) -> MatchupGroup(label, list, small) }
                    Separator(m, Modifier.fillMaxWidth())
                }
                FoeFacts(foe, dexTables, m, small, wide = false)
                if (foe.moves.isNotEmpty()) {
                    GbaText(tr("{0}'S MOVES", foe.name), OptionColors.value, OptionColors.valueShadow, small)
                    foe.moves.forEach { mv ->
                        BattleMoveRow(mv, showHints, playerCount, m, small, Modifier.fillMaxWidth().height(moveRow), threat = true)
                    }
                }
            }
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(m.u * 12)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, OptionColors.titleFill))),
            )
        }
    }
}

/**
 * What the foe's own types do to [mon] - its types (its likely same-type
 * attacks) are the guess the card can fit; its real moves are under its matchups: "ROCK 2x" in
 * red when one lands super effectively, "RESISTS" in green when
 * every one is resisted, nothing when it's neutral.
 */
@Composable
internal fun ThreatPill(mon: MonView, threats: List<MonView>) {
    val hits = remember(mon.types, threats) { threats.flatMap { typesVs(it.types, mon.types) } }
    if (hits.isEmpty()) return
    val worst = hits.maxBy { it.pct }
    when {
        worst.pct > 100 -> VerdictPill("${com.pokedaisy.app.companion.data.typeLabel(worst.type)} ${worst.label}", verdictColor(50))
        worst.pct < 100 -> VerdictPill(tr("RESISTS"), verdictColor(200))
    }
}

/** Each of [attackers]' types against a Pokémon of [defenders]' types. */
private fun typesVs(attackers: List<String>, defenders: List<String>): List<TypeMatchup> {
    val d = defenders.map(::typeIdOf)
    val d1 = d.getOrElse(0) { TYPE_NONE }
    if (d1 == TYPE_NONE) return emptyList()
    val d2 = d.getOrElse(1) { TYPE_NONE }
    return attackers.filter { it.isNotEmpty() }.distinct().mapNotNull { name ->
        val atk = typeIdOf(name).takeIf { it != TYPE_NONE } ?: return@mapNotNull null
        val pct = typeMultiplierPct(atk, d1, d2)
        TypeMatchup(name, formatMultiplier(pct), pct)
    }
}

/**
 * The foe's party in the bottom bar, as big as the space between the buttons
 * allows (up to the buttons' own height) - like the summary's party strip.
 */
@Composable
internal fun FoeTeamStrip(
    team: List<MonView>,
    seen: Set<Int>,
    shown: Int,
    small: GbaTextMetrics,
    onSelect: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val cell = minOf(NavButtonSize, (maxWidth - 4.dp * (team.size - 1)) / team.size)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            team.forEachIndexed { i, mon ->
                FoeSlot(mon, i in seen || i == shown, i == shown, small, cell, { onSelect(i) })
            }
        }
    }
}

/** One of the foe's party: its icon and level once seen (faded when fainted), else a
 * Poké Ball; [current] (the one the screen shows) gets a red frame. */
@Composable
internal fun FoeSlot(mon: MonView, shown: Boolean, current: Boolean, small: GbaTextMetrics, cell: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val u = small.u
    val noRipple = remember { MutableInteractionSource() }
    val focused by noRipple.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(cell)
            // The D-pad's focus (the companion's window), over the slot's own frame.
            .gbaFocusRing(focused, PixelRoundedShape(u * 3))
            .drawBehind {
                val px = u.toPx()
                val frame = if (current) listOf(OptionColors.value to 2 * px, OptionColors.valueShadow to px) else OptionColors.titleLayers.inPx(px)
                drawLayeredBox(frame, if (shown) OptionColors.titleFill else OptionColors.listFill, radius = 3 * px)
            }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick)
            .semantics { contentDescription = if (shown) tr("{0} Lv {1}", mon.name, mon.level) else tr("Unknown foe Pokémon") },
    ) {
        if (shown) {
            SpeciesIcon(mon.iconAsset, size = cell * 0.85f, modifier = Modifier.alpha(if (mon.hp == 0) 0.35f else 1f))
        } else {
            PixelArt(PixelIcons.pokeBall, caughtBall, Modifier.size(cell * 0.5f))
        }
    }
}
