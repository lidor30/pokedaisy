package com.pokedaisy.app.companion.ui

import androidx.compose.ui.graphics.RectangleShape
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_FEMALE
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_MALE
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.TypeMatchup
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing

/**
 * A party member's summary, filling the PARTY tab (opened by tapping a slot):
 * name / level / gender / types / HP top-left, type matchups bottom-left,
 * the four moves on the right. FireRed/LeafGreen use their summary paper and
 * heading; other games keep the app's OPTION-screen windows. Up/down
 * cycle through the party (wrapping), back returns to the slots; the buttons
 * keep pixel-art icons and use flat menu frames in FireRed/LeafGreen.
 *
 * Takes the whole party plus an index (not one [MonView]) so it keeps
 * following live telemetry while open. STATS (when the read has IVs, i.e.
 * the native paths) swaps the moves for [MonStatsSection], and stays swapped
 * while cycling through the party. LEARN ([learn], the original games for now)
 * puts the moves on the left and [CanLearnSection] on the right: a picked option
 * tags the move it would replace FORGET. TAKE ([itemUse], FieldItems.kt) shows when the Pokémon
 * holds an item the game lets it give up: it asks first, then says what happened.
 */
@Composable
fun MonDetailScreen(
    party: List<MonView>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Open on the STATS page (screenshot tests). */
    initialShowStats: Boolean = false,
    /** The move advice's game data; null = no LEARN page. */
    learn: LearnContext? = null,
    /** Open on the LEARN page with this option (a move id) picked (screenshot tests). */
    initialLearnPick: Int? = null,
    /** TAKE (a held item back to the bag); null or a game without it = no button. */
    itemUse: com.pokedaisy.app.companion.ItemUse? = null,
    /** Open with TAKE's question up (screenshot tests). */
    initialTakeAsk: Boolean = false,
    /** A TAKE result popped up first - for screenshot tests. */
    initialNotice: ItemNotice? = null,
) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val gap = m.u * 3
    var showStats by remember { mutableStateOf(initialShowStats) }
    var showLearn by remember { mutableStateOf(initialLearnPick != null) }
    // The picked CAN LEARN option, by move id (0 = none); kept while cycling through the party.
    var learnPick by remember { mutableStateOf(initialLearnPick ?: 0) }
    val canLearn = learn != null && com.pokedaisy.app.companion.data.MoveLearning.available(learn.tables, learn.guide)
    // What each Pokémon holds, asked of the game whenever the party read changes (TAKE).
    val takes = itemUse?.takeIf { it.canTake() }
    var held by remember { mutableStateOf<List<com.pokedaisy.app.companion.data.PartyTarget>>(emptyList()) }
    fun refreshHeld() = takes?.heldItems { t -> held = t.targets }
    remember { refreshHeld(); true } // in the first composition: a screenshot draws one frame
    LaunchedEffect(party) { refreshHeld() }
    // What the Pokémon on show holds (it, not one the read hasn't caught up with), and whether TAKE can have it.
    fun heldOf(i: Int) = held.getOrNull(i)?.takeIf { t ->
        val mon = party.getOrNull(i)
        t.heldItem != 0 && mon != null && (mon.personality == 0L || mon.personality == t.personality)
    }
    val heldHere = heldOf(index)?.takeIf { it.verdict == com.pokedaisy.app.companion.data.SlotVerdict.OK }
    // TAKE's question, then what it did (null = closed).
    var takeAsk by remember { mutableStateOf(initialTakeAsk) }
    var takeResult by remember { mutableStateOf<String?>(null) }
    // A TAKE that went through closes its question and pops up like the bag's USE / GIVE.
    var notice by remember { mutableStateOf(initialNotice) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                // Down the party slides the next one up from below, like a list.
                val down = targetState > initialState
                (fadeIn(tween(180)) + slideInVertically(tween(200)) { h -> if (down) h / 8 else -h / 8 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(160)) { h -> if (down) -h / 8 else h / 8 })
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "mon-detail",
        ) { i ->
            // (No early return in here: a `return@AnimatedContent` breaks
            // Compose's slot table mid-transition.)
            val mon = party.getOrNull(i)
            if (mon != null) {
                // Kanto's summary pages keep their blue/cream heading and striped paper;
                // the companion's extra information still fits the same adaptive columns.
                MonSummaryWindow(m, stats = showStats && mon.stats != null && !showLearn,
                    page = if (showLearn && canLearn) tr("LEARN") else if (showStats && mon.stats != null) tr("STATS") else tr("MOVES"),
                    modifier = Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        if (showLearn && canLearn) {
                            val options = rememberLearnOptions(learn!!, mon)
                            val pick = options?.firstOrNull { it.move == learnPick }
                            val forget = pick?.advice?.forget?.takeIf { learn.showHints } ?: 0
                            MovesSection(mon, m, small, Modifier.weight(0.5f).fillMaxHeight().padding(end = gap), forget = forget)
                            Separator(m, Modifier.fillMaxHeight(), vertical = true)
                            CanLearnSection(options, learnPick, { learnPick = it }, learn.showHints, m, small,
                                Modifier.weight(0.5f).fillMaxHeight().padding(start = gap))
                        } else {
                            val heldItem = heldOf(i)
                            SummaryLeftColumn(
                                mon, m, small, gap, Modifier.weight(0.5f).fillMaxHeight().padding(end = gap),
                                held = heldItem?.let { t ->
                                    {
                                        HeldItemLine(t.heldItem, small, onTake = if (t.verdict == com.pokedaisy.app.companion.data.SlotVerdict.OK) {
                                            { takeResult = null; takeAsk = true }
                                        } else null, bagFull = t.verdict == com.pokedaisy.app.companion.data.SlotVerdict.BAG_FULL)
                                    }
                                },
                            )
                            Separator(m, Modifier.fillMaxHeight(), vertical = true)
                            val right = Modifier.weight(0.5f).fillMaxHeight().padding(start = gap)
                            val stats = mon.stats
                            if (showStats && stats != null) MonStatsSection(stats, m, small, right)
                            else MovesSection(mon, m, small, right)
                        }
                    }
                }
            }
        }

        CompanionBackHandler(onBack = onBack)
        // No up / down arrows (STATS needed the room): the party strip's buttons pick the Pokémon.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (party.getOrNull(index)?.stats != null && !showLearn) {
                TextNavButton(if (showStats) tr("MOVES") else tr("STATS"), StatsPurple, StatsPurpleDark) { showStats = !showStats }
            }
            if (canLearn && party.getOrNull(index)?.isEgg == false) {
                TextNavButton(if (showLearn) tr("MOVES") else tr("LEARN"), SuggestGold, SuggestGoldDark) { showLearn = !showLearn; learnPick = 0 }
            }
            PartyStrip(party, index, onIndexChange, Modifier.weight(1f))
            NavButton(PixelIcons.back, tr("Back"), RunBlue, RunBlueDark, enabled = true, onClick = onBack)
        }
    }
    val asked = heldHere ?: held.getOrNull(index)
    if (takeAsk && takes != null && asked != null) {
        val mon = party.getOrNull(index)
        TakeDialog(
            mon, asked, takeResult, m,
            onTake = {
                takes.take(asked) { o ->
                    val text = takeMessage(o, mon?.name.orEmpty())
                    if (o.result == com.pokedaisy.app.companion.data.ItemUseResult.TAKEN) {
                        takeAsk = false; takeResult = null; notice = ItemNotice(o.swapped, text)
                    } else takeResult = text
                    refreshHeld()
                }
            },
            onClose = { takeAsk = false; takeResult = null },
        )
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        ItemNoticeHost(notice, m, Modifier.align(Alignment.TopCenter).padding(top = m.u * 6, start = m.u * 8, end = m.u * 8)) { notice = null }
    }
}

/** TAKE's question and then its answer, over the summary. */
@Composable
private fun TakeDialog(
    mon: MonView?, target: com.pokedaisy.app.companion.data.PartyTarget, result: String?, m: GbaTextMetrics,
    onTake: () -> Unit, onClose: () -> Unit,
) {
    val item = bagItemName(target.heldItem)
    if (result == null) {
        OptionConfirm(tk("TAKE"), tr("{0} is holding {1}. Take it?", mon?.name.orEmpty(), item), tk("TAKE"), m, onConfirm = onTake, onDismiss = onClose)
    } else {
        OptionConfirm(tk("TAKE"), result, tk("OK"), m, onConfirm = onClose, onDismiss = onClose, cancelLabel = null)
    }
}

/** What TAKE did, in the companion's words. */
internal fun takeMessage(o: com.pokedaisy.app.companion.data.ItemUseOutcome, mon: String): String = when (o.result) {
    com.pokedaisy.app.companion.data.ItemUseResult.TAKEN -> tr("Received {0} from {1}.", bagItemName(o.swapped), mon)
    com.pokedaisy.app.companion.data.ItemUseResult.BAG_FULL -> tr("The bag is full: {0} can't go back in it.", bagItemName(o.swapped))
    com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW -> tr("Not now: close the game's menus and battles first.")
    com.pokedaisy.app.companion.data.ItemUseResult.IN_BATTLE -> tr("You can't do that during a battle.")
    com.pokedaisy.app.companion.data.ItemUseResult.PARTY_CHANGED -> tr("The party has changed. Try again.")
    else -> tr("It can't be used here.")
}

/** The summary's ITEM line: what the Pokémon holds, its icon and name, and TAKE where it can be taken ([onTake]) -
 * or BAG FULL where TAKE would be, when the bag has no room for it ([bagFull]). */
@Composable
private fun HeldItemLine(itemId: Int, small: GbaTextMetrics, onTake: (() -> Unit)?, bagFull: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = small.u * 3)) {
        GbaText(tr("ITEM"), optionLook.value, optionLook.valueShadow, small)
        Spacer(Modifier.width(small.u * 6))
        ItemIcon(com.pokedaisy.app.companion.data.spriteAsset("items", itemId), size = small.lineHeight)
        Spacer(Modifier.width(small.u * 3))
        GbaText(bagItemName(itemId), optionLook.label, optionLook.labelShadow, small, modifier = Modifier.weight(1f))
        if (onTake != null) OptionButton(tk("TAKE"), small, onClick = onTake, modifier = Modifier.padding(start = small.u * 4))
        else if (bagFull) GbaText(tr("BAG FULL"), optionLook.label, optionLook.labelShadow, small, modifier = Modifier.padding(start = small.u * 4))
    }
}

/** The square buttons under a full-tab view (BACK, a summary's party strip, the battle panes' bar); smaller on a short
 * screen ([LocalShortCompanion]), where 60 dp was a fifth of the RG DS's height. */
internal val NavButtonSize: androidx.compose.ui.unit.Dp
    @Composable get() = if (LocalShortCompanion.current) 44.dp else 60.dp

@Composable
internal fun NavButton(
    icon: List<String>,
    description: String,
    fill: Color,
    frame: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (usesKantoMenu) {
        val m = rememberGbaTextMetrics(1f)
        OptionButton(
            label = null, m = m, onClick = onClick, modifier = Modifier.size(NavButtonSize),
            pixelIcon = if (icon == PixelIcons.back) PixelIcons.arrowBack else icon,
            contentDescription = description, enabled = enabled, sidePadding = 0,
            frameLayers = KantoMenuColors.layers,
        )
        return
    }
    PlatinumButton(
        fill = fill, frame = frame,
        modifier = Modifier.size(NavButtonSize).semantics { contentDescription = description },
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
    ) { PixelIcon(icon, buttonContent, Modifier.size(26.dp)) }
}

/** The party as a row of raised buttons, one per Pokémon: the one shown is
 * lit white, a tap on another shows it. As big as the space between the
 * other buttons allows (up to their own height). */
@Composable
private fun PartyStrip(party: List<MonView>, index: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val gap = 6.dp
        val n = party.size.coerceAtLeast(1)
        val cell = minOf(NavButtonSize, (maxWidth - gap * (n - 1)) / n)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            party.forEachIndexed { i, mon ->
                val on = i == index
                if (usesKantoMenu) {
                    KantoPartyButton(mon, on, cell) { onPick(i) }
                } else {
                    PlatinumButton(
                        fill = if (on) optionLook.stripLit else optionLook.stripIdle, frame = if (on) optionLook.stripLitFrame else optionLook.stripIdleFrame,
                        modifier = Modifier.size(cell).semantics { contentDescription = mon.name },
                        contentPadding = PaddingValues(0.dp),
                        onClick = { onPick(i) },
                    ) { SpeciesIcon(mon.iconAsset, size = cell * 0.8f) }
                }
            }
        }
    }
}

/** Flat, pixel-framed party markers, with the party menu's orange selection outline. */
@Composable
private fun KantoPartyButton(mon: MonView, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val m = rememberGbaTextMetrics(1f)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.size(size)
            .semantics { contentDescription = mon.name }
            .gbaFocusRing(focused, PixelRoundedShape(m.u * 2))
            .drawBehind {
                val frame = if (selected) listOf(KantoSummaryColors.selection to 2, OptionColors.titleFill to 1)
                    else KantoMenuColors.layers
                drawLayeredBox(frame.inPx(m.u.toPx()), if (selected) OptionColors.titleFill else KantoSummaryColors.identityPaper,
                    radius = m.u.toPx() * 2)
            }
            .soundClickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SpeciesIcon(mon.iconAsset, size = size * 0.8f) }
}

/** FireRed/LeafGreen's summary-paper colours (graphics/summary_screen/bg.png in pret/pokefirered),
 * expanded from BGR555 like the ROM renderer; dark mode keeps the same page cues. */
private object KantoSummaryColors {
    private fun c(light: Long, dark: Long) = Color(if (OptionColors.dark) dark else light)
    val heading get() = c(0xFFE7DE9C, 0xFF514C2D)
    val identityFrame get() = c(0xFFB58CD6, 0xFF74608E)
    val identityPaper get() = c(0xFFE7CEFF, 0xFF302B3C)
    val identityStripe get() = c(0xFFCEADEF, 0xFF3D334D)
    val portraitPaper get() = c(0xFFE7E7E7, 0xFF292D35)
    val portraitStripe get() = c(0xFFF7F7F7, 0xFF323740)
    val movesPaper get() = c(0xFFADF7DE, 0xFF263D36)
    val movesStripe get() = c(0xFF9CEFD6, 0xFF1F332D)
    val statsPaper get() = c(0xFFFFEF7B, 0xFF443C27)
    val statsStripe get() = c(0xFFFFD65A, 0xFF352F21)
    val moveFrame get() = c(0xFF6BC69C, 0xFF4A7567)
    val selection get() = c(0xFFFF7331, 0xFFFF9C63)
}

/** Native-sized stripes, drawn as whole pixels rather than scaling a background bitmap. */
private fun Modifier.summaryPaper(m: GbaTextMetrics, paper: Color, stripe: Color) = drawBehind {
    val px = m.u.toPx()
    drawRect(paper)
    var y = px
    while (y < size.height) {
        drawRect(stripe, Offset(0f, y), Size(size.width, px))
        y += px * 2
    }
}

@Composable
private fun MonSummaryWindow(
    m: GbaTextMetrics, stats: Boolean, page: String, modifier: Modifier = Modifier, content: @Composable () -> Unit,
) {
    if (!usesKantoMenu) {
        OptionListWindow(m, modifier, fill = optionLook.titleFill, content = content)
        return
    }
    val title = rememberGbaTextMetrics(1f)
    Column(modifier.background(OptionColors.frameDark).padding(m.u * 2)) {
        Row(Modifier.fillMaxWidth().height(title.lineHeight + m.u * 6).background(OptionColors.hintBar),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight().background(KantoSummaryColors.heading)
                .padding(horizontal = m.u * 6), contentAlignment = Alignment.CenterStart) {
                GbaText(tr("INFO"), OptionColors.label, OptionColors.labelShadow, title)
            }
            Box(Modifier.weight(1f).padding(horizontal = m.u * 6), contentAlignment = Alignment.CenterStart) {
                GbaText(page, OptionColors.hintText, OptionColors.hintShadow, title)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().summaryPaper(m,
            if (stats) KantoSummaryColors.statsPaper else KantoSummaryColors.movesPaper,
            if (stats) KantoSummaryColors.statsStripe else KantoSummaryColors.movesStripe).padding(m.u * 3)) {
            content()
        }
    }
}


/** A 1-GBA-pixel rule between the summary's sections, in the window frame's light grey. */
@Composable
internal fun Separator(m: GbaTextMetrics, modifier: Modifier = Modifier, vertical: Boolean = false) {
    val look = optionLook
    Box(
        modifier
            .then(if (vertical) Modifier.width(m.u * 2) else Modifier.height(m.u * 2))
            .drawBehind {
                val px = m.u.toPx()
                // Dark line + light line, like the window's own inner frame.
                if (vertical) {
                    drawRect(look.separatorDark, Offset.Zero, Size(px, size.height))
                    drawRect(look.separatorLight, Offset(px, 0f), Size(px, size.height))
                } else {
                    drawRect(look.separatorDark, Offset.Zero, Size(size.width, px))
                    drawRect(look.separatorLight, Offset(0f, px), Size(size.width, px))
                }
            },
    )
}

/** The summary screen's EXP bar blue. */
private val ExpBlue = Color(0xFF40B8F8)

/**
 * The summary's left column: [InfoSection] over the matchups. Where that doesn't fit (a 4:3 screen,
 * the Retroid Pocket Duo Lite's 1280x960, has ~120 px less height than the Thor) the types move up
 * beside the icon, a row less - measured, so a screen it fits keeps the full layout.
 */
@Composable
private fun SummaryLeftColumn(
    mon: MonView, m: GbaTextMetrics, small: GbaTextMetrics, gap: Dp, modifier: Modifier,
    /** The held item's line (TAKE), under the info; null = none. */
    held: (@Composable () -> Unit)? = null,
) {
    val sections = listOf(mon.weaknesses, mon.resistances, mon.immunities)
    if (usesKantoMenu && LocalShortCompanion.current) {
        // On a 240-GBA-pixel screen the identity + held item can consume the entire column.
        // Scroll the whole column so matchups and TAKE remain reachable, rather than giving
        // only the matchups a near-zero-height viewport.
        val scroll = rememberScrollState()
        Column(modifier.testTag("summary-details-scroll")
            .dpadScroll(scroll, RectangleShape, small.lineHeight * 3).verticalScroll(scroll)) {
            SummaryIdentity(mon, m, small, compact = true)
            held?.invoke()
            Separator(m, Modifier.fillMaxWidth().padding(vertical = gap))
            MatchupColumn(
                listOf(tr("WEAK TO"), tr("RESISTS"), tr("IMMUNE TO")).zip(sections).filter { it.second.isNotEmpty() },
                small, inline = true, scroll = false,
            )
        }
        return
    }
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { c ->
        val fits = !c.hasBoundedHeight || run {
            val loose = c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
            val probe = subcompose("probe") {
                Column(Modifier.fillMaxWidth()) {
                    SummaryIdentity(mon, m, small, compact = false)
                    held?.invoke()
                    Separator(m, Modifier.fillMaxWidth().padding(vertical = gap))
                    MatchupColumn(
                        listOf(tr("WEAK TO"), tr("RESISTS"), tr("IMMUNE TO")).zip(sections).filter { it.second.isNotEmpty() },
                        small, inline = false, scroll = false,
                    )
                }
            }
            probe.sumOf { it.measure(loose).height } <= c.maxHeight
        }
        val shown = subcompose(if (fits) "full" else "compact") {
            Column(Modifier.fillMaxSize()) {
                SummaryIdentity(mon, m, small, compact = !fits)
                held?.invoke()
                Separator(m, Modifier.fillMaxWidth().padding(vertical = gap))
                MatchupSection(mon, small, Modifier.fillMaxWidth().weight(1f))
            }
        }.map { it.measure(c) }
        layout(c.maxWidth, c.maxHeight) { shown.forEach { it.place(0, 0) } }
    }
}

@Composable
private fun SummaryIdentity(mon: MonView, m: GbaTextMetrics, small: GbaTextMetrics, compact: Boolean) {
    val frame = if (usesKantoMenu) Modifier
        .background(KantoSummaryColors.identityFrame).padding(m.u * 2)
        .summaryPaper(m, KantoSummaryColors.portraitPaper, KantoSummaryColors.portraitStripe).padding(m.u * 3)
        else Modifier
    InfoSection(mon, m, small, Modifier.fillMaxWidth().then(frame), typesBesideIcon = compact, kantoIdentity = usesKantoMenu)
}

/** Name + gender, icon, level, types, status, HP - the summary's top-left block. [typesBesideIcon]:
 * the types under the level, next to the icon, instead of a row of their own (a short screen). */
@Composable
internal fun InfoSection(mon: MonView, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier = Modifier, typesBesideIcon: Boolean = false, kantoIdentity: Boolean = false) {
    val u = m.u
    Box(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(u * 3)) {
            Row(
                modifier = if (kantoIdentity) Modifier.fillMaxWidth().background(KantoSummaryColors.identityStripe) else Modifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GbaText(mon.shownName, optionLook.label, optionLook.labelShadow, m, modifier = Modifier.weight(1f, fill = false))
                GenderMark(mon.genderSymbol, m, Modifier.padding(start = u * 3))
                if (mon.shiny) ShinySparkle(m, Modifier.padding(start = u * 3))
                // A nickname of the player's own: the species after it, like the game's summary.
                if (mon.renamed) GbaText("/" + mon.name, optionLook.label, optionLook.labelShadow, small, Modifier.padding(start = u * 3))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The 32px icon at a whole multiple of the frame's GBA pixel, so it stays crisp.
                // A shiny one: in a gold frame, as on battle INFO.
                val compactPortrait = kantoIdentity && typesBesideIcon
                Box(Modifier.monIconBox(u * if (compactPortrait) 28 else 36, u, mon.shiny, optionLook.listFill), contentAlignment = Alignment.Center) {
                    SpeciesIcon(mon.iconAsset, size = u * if (compactPortrait) 24 else 32)
                }
                Spacer(Modifier.width(u * 5))
                if (!mon.isEgg) {
                    Column(verticalArrangement = Arrangement.spacedBy(u * 3)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            GbaText(tr("Lv"), optionLook.label, optionLook.labelShadow, small)
                            GbaText("${mon.level}", optionLook.value, optionLook.valueShadow, m, modifier = Modifier.padding(start = u * 2))
                        }
                        if (mon.status.isNotEmpty()) StatusBadge(mon.status)
                        if (typesBesideIcon) Types(mon)
                    }
                }
            }
            // Own row, side by side: the block keeps one height for single and dual types.
            if (!typesBesideIcon) Types(mon)
            if (!mon.isEgg) {
                SummaryBar(tr("HP"), "${mon.hp}/${mon.maxHp}", mon.hp, mon.maxHp, AppColors.hpColor(mon.hp, mon.maxHp), small)
                // Progress through the current level; the text is what's left to the next.
                mon.exp?.let { xp ->
                    val span = (xp.nextLevel - xp.levelStart).coerceAtLeast(1)
                    SummaryBar(
                        tr("EXP"), if (mon.level >= 100) tr("MAX") else tr("NEXT {0}", xp.toNext),
                        (xp.fraction * span).toInt(), span.toInt(), ExpBlue, small,
                    )
                }
            }
        }
    }
}

@Composable
private fun Types(mon: MonView) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        mon.types.filter { it.isNotEmpty() }.distinct().forEach { TypeBadge(it) }
    }
}

@Composable
internal fun GenderMark(symbol: Int, m: GbaTextMetrics, modifier: Modifier = Modifier) {
    val mark = when (symbol) {
        GENDER_SYMBOL_MALE -> PixelIcons.male to Color(0xFF3984F7)
        GENDER_SYMBOL_FEMALE -> PixelIcons.female to Color(0xFFF75A5A)
        else -> null
    }
    // About the pixel font's cap height, so it sits beside the name.
    if (mark != null) PixelIcon(mark.first, mark.second, modifier.size(m.lineHeight * 0.55f))
}

/** "HP [=====    ] 30/35" - the summary screen's labelled bar, drawn at GBA-pixel steps. */
@Composable
internal fun SummaryBar(label: String, text: String, value: Int, max: Int, color: Color, small: GbaTextMetrics) {
    val frac = if (max > 0) (value.toFloat() / max).coerceIn(0f, 1f) else 0f
    val u = small.u
    val frameDark = optionLook.frameDark
    Row(verticalAlignment = Alignment.CenterVertically) {
        GbaText(label, optionLook.value, optionLook.valueShadow, small)
        Spacer(Modifier.width(u * 3))
        Box(
            Modifier
                .weight(1f)
                .height(u * 6)
                .drawBehind {
                    val px = u.toPx()
                    drawPixelRoundRect(frameDark, radius = 2 * px, step = px)
                    val inner = Size(size.width - 2 * px, size.height - 2 * px)
                    drawPixelRoundRect(Color(0xFF506858), Offset(px, px), inner, radius = px, step = px)
                    val fill = kotlin.math.floor(inner.width * frac / px) * px
                    if (fill > 0) drawPixelRoundRect(color, Offset(px, px), Size(fill, inner.height), radius = px, step = px)
                },
        )
        Spacer(Modifier.width(u * 3))
        GbaText(text, optionLook.label, optionLook.labelShadow, small)
    }
}

/**
 * WEAK TO / RESISTS / IMMUNE TO, each a heading over its type chips - or, where that doesn't fit
 * the column (a 4:3 screen, the Retroid Pocket Duo Lite's 1280x960, has ~120 px less than the
 * Thor), each heading on the line of its chips, a line less a group. Scrolls if even that won't fit.
 */
@Composable
internal fun MatchupSection(mon: MonView, small: GbaTextMetrics, modifier: Modifier = Modifier) {
    val sections = listOf(tr("WEAK TO") to mon.weaknesses, tr("RESISTS") to mon.resistances, tr("IMMUNE TO") to mon.immunities)
        .filter { it.second.isNotEmpty() }
    // Measured, not guessed: the full layout first, the inline one only when that runs over. Inline, a game font set larger
    // (Gen 1's) goes back to the base size: its headings ate the row and pushed the last group off the column.
    val compact = rememberGbaTextMetrics(1f, gameScaled = false)
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { c ->
        val loose = c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val full = subcompose("full") { MatchupColumn(sections, small, inline = false, scroll = false) }.map { it.measure(loose) }
        val shown = if (c.hasBoundedHeight && full.sumOf { it.height } > c.maxHeight) {
            subcompose("inline") { MatchupColumn(sections, compact, inline = true, scroll = true) }.map { it.measure(c.copy(minHeight = 0)) }
        } else full
        layout(c.maxWidth, if (c.hasBoundedHeight) c.maxHeight else shown.sumOf { it.height }) { shown.forEach { it.place(0, 0) } }
    }
}

@Composable
private fun MatchupColumn(sections: List<Pair<String, List<TypeMatchup>>>, small: GbaTextMetrics, inline: Boolean, scroll: Boolean) {
    Column(
        Modifier.fillMaxWidth().then(
            if (!scroll) Modifier
            // Nothing to tap: one D-pad stop that scrolls (the companion's window).
            else rememberScrollState().let { s -> Modifier.dpadScroll(s, RectangleShape, small.lineHeight * 3).verticalScroll(s) },
        ),
        verticalArrangement = Arrangement.spacedBy(small.u * 3),
    ) {
        if (sections.isEmpty()) {
            GbaText(tr("NO MATCHUP DATA"), optionLook.muted, optionLook.mutedShadow, small)
        }
        sections.forEach { (heading, list) -> MatchupGroup(heading, list, small, inline) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MatchupGroup(heading: String, list: List<TypeMatchup>, small: GbaTextMetrics, inline: Boolean = false) {
    if (inline) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // At least the chip's own height (a 12 sp line + its 3 dp padding), so the heading sits level with them - and
            // as tall as its own line where that's taller (Gen 1's larger font: a chip-tall box clipped it), the chips
            // centred on it.
            Box(Modifier.heightIn(min = with(LocalDensity.current) { 12.sp.toDp() } + 6.dp).padding(end = 2.dp)
                .align(Alignment.CenterVertically), contentAlignment = Alignment.CenterStart) {
                GbaText(heading, optionLook.label, optionLook.labelShadow, small)
            }
            list.forEach { Box(Modifier.align(Alignment.CenterVertically)) { TypeBadgeWithLabel(it) } }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(small.u * 2)) {
        GbaText(heading, optionLook.label, optionLook.labelShadow, small)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            list.forEach { TypeBadgeWithLabel(it) }
        }
    }
}

/** The four moves, sharing out the column's height, separated by rules. */
@Composable
private fun MovesSection(mon: MonView, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier = Modifier, forget: Int = 0) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (usesKantoMenu) m.u * 3 else 0.dp)) {
        for (i in 0 until 4) {
            if (i > 0 && !usesKantoMenu) Separator(m, Modifier.fillMaxWidth())
            val mv = mon.moves.getOrNull(i)
            val paper = if (usesKantoMenu) Modifier.background(KantoSummaryColors.moveFrame)
                .padding(end = m.u, bottom = m.u).background(OptionColors.titleFill).padding(horizontal = m.u * 2)
                else Modifier
            MoveRow(mv, m, small, Modifier.weight(1f).fillMaxWidth().then(paper), forget = forget != 0 && mv?.id == forget)
        }
    }
}

/** One move: a type-colored stripe, then name, type badge, power and PP; [forget] tags it FORGET (LEARN's pick). */
@Composable
private fun MoveRow(mv: MoveView?, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier = Modifier, forget: Boolean = false) {
    val u = m.u
    val edge = mv?.let { AppColors.typeColors[it.type] } ?: optionLook.muted
    Box(
        modifier
            .drawBehind {
                val px = u.toPx()
                if (mv != null) {
                    // The type stripe down the left.
                    drawPixelRoundRect(edge, Offset(0f, 4 * px), Size(3 * px, size.height - 8 * px), radius = px, step = px)
                }
            }
            .padding(start = u * 8, top = u * 3, bottom = u * 3),
    ) {
        if (mv == null) {
            GbaText("—", optionLook.muted, optionLook.mutedShadow, m, modifier = Modifier.align(Alignment.CenterStart))
        } else if (mv.vs.isEmpty() && LocalShortCompanion.current) {
            // A short screen (the RG DS): no room for a second line - power and PP beside the name, the type left to the
            // stripe's colour.
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    GbaText(mv.name, optionLook.label, optionLook.labelShadow, m, Modifier.weight(1f, fill = false))
                    if (forget) { Spacer(Modifier.width(u * 4)); ForgetTag() }
                }
                Spacer(Modifier.width(u * 6))
                Stat(tr("PWR"), powerLabel(mv.power), small)
                Spacer(Modifier.width(u * 6))
                Stat(tr("PP"), "${mv.pp}", small)
            }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GbaText(mv.name, optionLook.label, optionLook.labelShadow, m, Modifier.weight(1f, fill = false))
                if (forget) { Spacer(Modifier.width(u * 4)); ForgetTag() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(mv.type)
                Spacer(Modifier.weight(1f))
                Stat(tr("PWR"), powerLabel(mv.power), small)
                Spacer(Modifier.width(u * 6))
                Stat(tr("PP"), "${mv.pp}", small)
            }
            // In battle: how the move fares against each foe.
            if (mv.vs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    mv.vs.forEach { MultiplierChip(it.label, it.pct) }
                }
            }
        }
    }
}

@Composable
internal fun Stat(label: String, value: String, small: GbaTextMetrics) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GbaText(label, optionLook.label, optionLook.labelShadow, small)
        Spacer(Modifier.width(small.u * 3))
        GbaText(value, optionLook.value, optionLook.valueShadow, small)
    }
}

/** A labelled button for the summary screens' bottom row, as tall as [NavButton]. */
@Composable
internal fun TextNavButton(label: String, fill: Color, frame: Color, onClick: () -> Unit) {
    if (usesKantoMenu) {
        OptionButton(label, rememberGbaTextMetrics(1f), onClick = onClick, modifier = Modifier.height(NavButtonSize),
            sidePadding = 6, frameLayers = KantoMenuColors.layers)
        return
    }
    PlatinumButton(
        fill = fill, frame = frame,
        modifier = Modifier.height(NavButtonSize),
        contentPadding = PaddingValues(horizontal = 20.dp),
        onClick = onClick,
    ) { ButtonLabel(label, 24.sp, bold = true) }
}

/** The summary screens' Back button: bottom-right, blue, a U-turn arrow. */
@Composable
internal fun BackNavButton(onBack: () -> Unit) {
    NavButton(PixelIcons.back, tr("Back"), RunBlue, RunBlueDark, enabled = true, onClick = onBack)
}

/**
 * The summary screens' frame (Pokémon details, battle INFO, SUGGESTIONS):
 * one white OPTION window filling the tab, over a bottom row of square
 * battle-style buttons - [buttons] on the left, Back (when [onBack] is set)
 * on the right.
 */
@Composable
internal fun SummaryFrame(
    m: GbaTextMetrics,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    buttons: @Composable RowScope.() -> Unit = {},
    /** Fills the space between [buttons] and Back (e.g. a strip of icons). */
    middle: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onBack != null) CompanionBackHandler(onBack = onBack)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionListWindow(m, Modifier.weight(1f).fillMaxWidth(), fill = optionLook.titleFill) { content() }
        Row(
            Modifier.fillMaxWidth().height(NavButtonSize),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buttons()
            if (middle != null) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { middle() }
            else Spacer(Modifier.weight(1f))
            if (onBack != null) BackNavButton(onBack)
        }
    }
}
