package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.isSpecified
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_TARGET_SELECT
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import com.pokedaisy.app.companion.data.isGen2
import com.pokedaisy.app.companion.data.isGen1
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource

/**
 * Gen 4 (Platinum) style touch battle controls: party-health ball strips
 * (yours top-left, a trainer's top-right), INFO/SUGGESTIONS centred above
 * a big FIGHT button + three smaller BAG/RUN/
 * POKéMON buttons, or a 2x2 move grid with the effectiveness multiplier
 * shown right on each card — as opposed to [BattleInfoScreen]'s information-dense
 * HP/matchup view (reached via the INFO button here; `CompanionScreen` owns
 * that toggle). See PLAN.md Phase 5; this screen is only offered
 * while it's actually the player's turn.
 */
@Composable
fun BattleControlsScreen(
    activeMon: MonView?,
    battleInput: BattleInput?,
    battleInputState: Int,
    party: List<MonView> = emptyList(),
    modifier: Modifier = Modifier,
    showHints: Boolean = true,
    onShowInfo: () -> Unit = {},
    onShowSuggestions: () -> Unit = {},
    /** A trainer battle's whole opposing party (empty in a wild battle). */
    foeParty: List<MonView> = emptyList(),
    /** The foe on the field, for the POKéMON pane's recommendation. */
    foe: MonView? = null,
    /** The player's battlers on the field (their party cards say OUT). */
    active: List<MonView> = emptyList(),
    /** ONE-TAP MOVES (SETTINGS): the action menu shows the moves where FIGHT is. */
    oneTapMoves: Boolean = false,
    /** BATTLE STYLE (SETTINGS): GEN 5 draws [Gen5BattleControls] instead (never on Yellow, which keeps its own look). */
    style: com.pokedaisy.app.companion.BattleStyle = com.pokedaisy.app.companion.BattleStyle.GEN4,
    /** BATTLE INFO off: no INFO / SUGGESTIONS buttons. */
    battleInfo: Boolean = true,
    /** RUN was picked: the pane stays up until the battle ends, with no "Waiting for your turn" (there's no turn coming). */
    ranAway: Boolean = false,
) {
    if (style == com.pokedaisy.app.companion.BattleStyle.GEN5 && !gen1Buttons) {
        return Gen5BattleControls(
            activeMon, battleInput, battleInputState, party, modifier, showHints,
            onShowInfo, onShowSuggestions, foeParty, foe, active, oneTapMoves, battleInfo, ranAway,
        )
    }
    // battleInput.busy flips the instant a tap queues a synthetic press
    // sequence and back once the game has actually caught up (see
    // BattleInputController) - polled rather than read directly since it's a
    // plain field, not a Compose State, and this is the one place that needs
    // to react to it moment-to-moment (buttons dim + the processing dots
    // below appear) to mask the real lag between a tap and the ROM noticing.
    val busy = rememberBattleInputBusy(battleInput)
    // POKéMON on the action menu opens our own party pane (where the game
    // supports switchTo); the game's party screen only opens once a mon is picked.
    var choosingMon by remember { mutableStateOf(false) }
    // FIGHT opens our move grid at once, from the moves already read - no wait for the game's
    // move screen; a move then picks FIGHT and itself in one go, CANCEL just closes the grid.
    var choosingMove by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(battleInputState) {
        if (battleInputState != BATTLE_INPUT_ACTION_SELECT) choosingMon = false
        if (battleInputState != BATTLE_INPUT_ACTION_SELECT) choosingMove = false
    }
    val movesKnown = activeMon?.moves?.isNotEmpty() == true
    val canSwitch = battleInput?.canSwitch == true
    // The POKéMON pane is the party screen, full height: no strips or INFO / SUGGESTIONS over it.
    val picking = (battleInputState == BATTLE_INPUT_ACTION_SELECT && choosingMon) ||
        (battleInputState == BATTLE_INPUT_PARTY_OPEN && canSwitch)
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // In every other sub-state (action-select, move-select, ...) -
            // matches the in-game reference, where these strips never
            // disappear while the battle menu is up. The foe's only in a
            // trainer battle, mirrored: its first Pokémon at the right edge.
            if (!picking) Row(
                modifier = Modifier.fillMaxWidth().height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Yours bigger than the foe's: it's the one you act on.
                PartyBallRow(party, ball = 34.dp)
                Spacer(Modifier.weight(1f))
                if (foeParty.isNotEmpty()) PartyBallRow(foeParty, ball = 22.dp, mirrored = true)
                // A wild battle has no foe balls there: a shiny foe's plate takes the spot.
                else if (foe?.shiny == true) ShinyPlate()
            }
            if (!picking) Spacer(Modifier.height(6.dp))
            // INFO / SUGGESTIONS centred above FIGHT, in every state but the
            // POKéMON pane: not part of the in-game menu, they only open their
            // own panes (no synthetic input), so they never wait on `busy`.
            if (!picking && battleInfo) Row(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                PlatinumButton(
                    fill = InfoPurple, frame = InfoPurpleDark,
                    modifier = Modifier.width(96.dp).fillMaxHeight(), contentPadding = PaddingValues(0.dp),
                    onClick = onShowInfo,
                ) { ButtonLabel(tr("INFO"), 24.sp) }
                PlatinumButton(
                    fill = SuggestGold, frame = SuggestGoldDark,
                    modifier = Modifier.width(150.dp).fillMaxHeight(), contentPadding = PaddingValues(0.dp),
                    onClick = onShowSuggestions,
                ) { ButtonLabel(tr("SUGGESTIONS"), 22.sp) }
            }
            if (!picking && battleInfo) Spacer(Modifier.height(8.dp))
            AnimatedContent(
                // The action menu and the moves are one pane: the buttons slide away over the moves (below).
                targetState = if (battleInputState == BATTLE_INPUT_MOVE_SELECT) BATTLE_INPUT_ACTION_SELECT else battleInputState,
                // LIGHT COMPANION: a cut, so a slow device draws one pane, not two crossfading for 180 ms.
                transitionSpec = {
                    if (com.pokedaisy.app.companion.LightCompanion.on) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "battle-controls-state",
            ) { state ->
                when (state) {
                    BATTLE_INPUT_ACTION_SELECT -> if (choosingMon) {
                        PartyPicker(party, foe, active, battleInput, busy, showHints, onBack = { choosingMon = false })
                    } else {
                        // FIGHT: the buttons leave by the nearest edge and the moves are there underneath. The game's own
                        // FIGHT (a real button) opens them the same way.
                        val open = choosingMove || battleInputState == BATTLE_INPUT_MOVE_SELECT
                        val opened by androidx.compose.animation.core.animateFloatAsState(
                            if (open) 1f else 0f,
                            if (com.pokedaisy.app.companion.LightCompanion.on || androidx.compose.ui.platform.LocalInspectionMode.current) androidx.compose.animation.core.snap()
                            else tween(MENU_SLIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            label = "battle-menu",
                        )
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            if (open || opened > 0f) MoveGrid(
                                activeMon?.moves.orEmpty(), battleInput, busy, showHints,
                                modifier = Modifier.fillMaxSize().graphicsLayer {
                                    alpha = opened
                                    scaleX = 0.94f + 0.06f * opened
                                    scaleY = 0.94f + 0.06f * opened
                                },
                                onCancel = if (battleInputState == BATTLE_INPUT_ACTION_SELECT) ({ choosingMove = false }) else null,
                            )
                            if (opened < 1f) ActionButtons(
                                activeMon, battleInput, busy, showHints, oneTapMoves,
                                onFight = if (movesKnown) ({ choosingMove = true }) else null,
                                onPokemon = if (canSwitch) ({ choosingMon = true }) else null,
                                modifier = Modifier.fillMaxSize(), out = opened,
                            )
                            // No taps while they slide: a disabled button would dim on its way out.
                            if (opened > 0f && opened < 1f) Box(
                                Modifier.fillMaxSize().pointerInput(Unit) {
                                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                                },
                            )
                        }
                    }
                    // The game's own party screen (opened by a pick here, by the
                    // game after a faint, or from the game itself): pick from ours.
                    BATTLE_INPUT_PARTY_OPEN -> if (canSwitch) {
                        PartyPicker(party, foe, active, battleInput, busy, showHints, onBack = { battleInput?.back() })
                    } else {
                        BackOnlyButton(battleInput, busy)
                    }
                    // The real Bag/Party screen is up (a separate CB2, not part
                    // of this Compose tree) - the only thing to offer here is a
                    // way back, since we have no visibility into what's on that
                    // screen.
                    BATTLE_INPUT_BAG_OPEN -> BackOnlyButton(battleInput, busy)
                    // The controller auto-sends a second A to accept the game's
                    // own default target here (see BattleInputController) -
                    // nothing for the user to tap, just a status so the screen
                    // doesn't look stuck.
                    BATTLE_INPUT_TARGET_SELECT -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(tr("Confirming target…"), color = AppColors.muted, fontSize = 22.sp)
                    }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!ranAway) Text(tr("Waiting for your turn…"), color = AppColors.muted, fontSize = 22.sp)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = busy,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.Center),
        ) { ProcessingBall() }
    }
}

/** The action buttons sliding off over the moves. */
private const val MENU_SLIDE_MS = 280

/** The game's cursor in the battle menu on screen, for a button to show where it is - once the player has moved it (a
 * ring on FIGHT every turn would say nothing). -1 till then. Polled like [rememberBattleInputBusy]. */
@Composable
internal fun rememberBattleCursor(battleInput: BattleInput?): Int {
    var shown by remember(battleInput) { mutableStateOf(-1) }
    androidx.compose.runtime.LaunchedEffect(battleInput) {
        var first = -1
        while (battleInput != null) {
            androidx.compose.runtime.withFrameMillis { }
            val now = battleInput.menuCursor
            if (first < 0) first = now
            if (now >= 0 && (shown >= 0 || now != first)) shown = now
        }
    }
    return shown
}

/** Polls [BattleInput.busy] into Compose state - it's a plain field updated
 * from the emulator thread, not a Flow/State, so this is the one spot that
 * needs to notice it change moment-to-moment. 60fps-ish is plenty for a
 * visual dim/undim; far cheaper than wiring a whole new observable path
 * through EmulatorEngine for what's ultimately a cosmetic affordance. */
@Composable
internal fun rememberBattleInputBusy(battleInput: BattleInput?): Boolean {
    var busy by remember(battleInput) { mutableStateOf(battleInput?.busy ?: false) }
    androidx.compose.runtime.LaunchedEffect(battleInput) {
        // Once per frame (not a delay() timer): paused along with rendering,
        // and it follows a test's frame clock.
        while (battleInput != null) {
            androidx.compose.runtime.withFrameMillis { }
            busy = battleInput.busy
        }
    }
    return busy
}

/** "Your tap registered, the game just hasn't caught up yet" - a rocking
 * Poké Ball in a small white window, centred over the (dimmed) controls,
 * shown only while [BattleInput.busy]: the lag between a tap and the ROM
 * reacting (queued synthetic button presses take several real frames to
 * land - see BattleInputController), e.g. between FIGHT and the move list. */
@Composable
internal fun ProcessingBall() {
    val m = rememberGbaTextMetrics()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .drawBehind {
                val px = m.u.toPx()
                drawLayeredBox(OptionColors.titleLayers.inPx(px), OptionColors.titleFill, radius = 4 * px)
            }
            .padding(m.u * 8),
    ) { RockingPokeBall(Modifier.size(m.u * 24)) }
}

/** One pokéball per team member, dim/gray once that member has fainted - the
 * party-health strip in the corner of the in-game battle menu. [mirrored]
 * (the foe's, top-right) runs right to left, so the empty slots sit inwards. Drawn rather than a bitmap asset: cheap, and scales cleanly at the
 * small size this reads at. */
/** The battle screen's SHINY: amber letters between two sparkles on pale gold, the row's height, a gold frame. */
@Composable
private fun ShinyPlate() {
    Row(
        Modifier.fillMaxHeight().clip(PixelRoundedShape(4.dp)).background(ShinyGold).padding(2.dp)
            .clip(PixelRoundedShape(3.dp)).background(ShinyPale).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PixelIcon(PixelIcons.sparkle, ShinyGold, Modifier.size(14.dp))
        Text(tr("SHINY"), color = ShinyAmber, fontSize = 22.sp, fontFamily = pixelFontFamily(), maxLines = 1, softWrap = false)
        PixelIcon(PixelIcons.sparkle, ShinyGold, Modifier.size(14.dp))
    }
}

@Composable
private fun PartyBallRow(party: List<MonView>, ball: Dp, modifier: Modifier = Modifier, mirrored: Boolean = false) {
    // Six slots, like the game: a ball per member, grey discs for the empty ones.
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(ball / 4), verticalAlignment = Alignment.CenterVertically) {
        for (slot in 0 until 6) {
            val i = if (mirrored) 5 - slot else slot
            val mon = party.getOrNull(i)
            if (mon != null) PokeballDot(alive = mon.hp > 0, dotSize = ball)
            else PixelArt(PixelIcons.disc, mapOf('K' to Color(0xFF8A8A8A), 'G' to Color(0xFFA8A8A8)), Modifier.size(ball))
        }
    }
}

/** A pixel-art Poké Ball; a fainted member's is greyed out. */
@Composable
internal fun PokeballDot(alive: Boolean, dotSize: Dp = 26.dp) {
    val palette = if (alive) {
        mapOf('K' to Color(0xFF202020), 'R' to Color(0xFFE83028), 'W' to Color.White, 'H' to Color(0xFFF8A8A0))
    } else {
        mapOf('K' to Color(0xFF585860), 'R' to Color(0xFF9898A0), 'W' to Color(0xFFC8C8D0), 'H' to Color(0xFFB0B0B8))
    }
    PixelArt(PixelIcons.pokeBall, palette, Modifier.size(dotSize))
}

@Composable
private fun ActionButtons(
    activeMon: MonView?,
    battleInput: BattleInput?,
    busy: Boolean,
    showHints: Boolean,
    oneTapMoves: Boolean,
    /** FIGHT opens the companion's move grid without the game's; null = the game's FIGHT. */
    onFight: (() -> Unit)? = null,
    /** POKéMON opens the companion's party pane instead of the game's; null = the game's. */
    onPokemon: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 0..1: how far the buttons have slid off, each by its nearest edge (FIGHT opening the moves). */
    out: Float = 0f,
) {
    val enabled = battleInput != null && !busy
    if (gen1Buttons) return Gen1ActionButtons(activeMon, battleInput, enabled, onPokemon, modifier.graphicsLayer { alpha = 1f - out })
    val cursor = rememberBattleCursor(battleInput).takeIf { !busy } ?: -1
    val hasBag = com.pokedaisy.app.companion.data.battleHasBag
    val side = PaddingValues(horizontal = 4.dp)
    @Composable fun Bag(place: Modifier, label: TextUnit = 36.sp) = PlatinumButton(
        fill = HgOrange, frame = HgOrangeLow,
        modifier = place.graphicsLayer { translationX = -out * size.width * 1.4f },
        enabled = enabled, battleButton = true, highlighted = cursor == 1, contentPadding = side, pressesGame = true, onClick = { battleInput?.selectAction(1) },
    ) { ButtonLabel(tr("BAG"), label, outlined = true) }
    @Composable fun Run(place: Modifier, label: TextUnit = 36.sp) = PlatinumButton(
        fill = HgBlue, frame = HgBlueLow,
        modifier = place.graphicsLayer { translationY = out * size.height * 1.5f },
        enabled = enabled, battleButton = true, highlighted = cursor == 3, contentPadding = side, pressesGame = true, onClick = { battleInput?.selectAction(3) },
    ) { ButtonLabel(tr("RUN"), label, outlined = true) }
    @Composable fun Pokemon(place: Modifier, label: TextUnit = 30.sp) = PlatinumButton(
        fill = HgGreen, frame = HgGreenLow,
        modifier = place.graphicsLayer { translationX = out * size.width * 1.4f },
        enabled = enabled, battleButton = true, highlighted = cursor == 2, contentPadding = side, pressesGame = onPokemon == null,
        onClick = { onPokemon?.invoke() ?: battleInput?.selectAction(2) },
    ) { ButtonLabel(tr("POKéMON"), label, outlined = true) }
    if (oneTapMoves) {
        Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    // ONE-TAP MOVES: the moves themselves where FIGHT was - a tap opens FIGHT and picks
    // the move in one go (BattleInput.selectMove from the action menu).
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0..1) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0..1) {
                    val idx = row * 2 + col
                    MoveCard(activeMon?.moves?.getOrNull(idx), battleInput, idx, busy, showHints, modifier = Modifier.focusStart(idx == 0).weight(1f).fillMaxHeight(), compact = true)
                }
            }
        }
            Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (hasBag) Bag(Modifier.weight(1f).fillMaxHeight())
                Run(Modifier.weight(1f).fillMaxHeight())
                Pokemon(Modifier.weight(1f).fillMaxHeight())
            }
        }
        return
    }
    // HeartGold's layout and proportions, a DS pixel being [ds]: FIGHT over the four move places, BAG and POKéMON in the bottom
    // corners, RUN between them a little lower, and not cut off by the screen's edge as the game's is. The height a button can
    // take comes from the width, so a tall pane leaves room rather than stretching them.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val ds = minOf(maxWidth / HG_WIDTH, maxHeight / HG_HEIGHT)
        // Widths go on past HeartGold's where the pane has room for it (a short, wide screen), not as far as they'd stretch.
        val wide = minOf(maxWidth / HG_WIDTH, ds * HG_MAX_WIDEN)
        val runDrop = if (hasBag) ds * 8 else 0.dp
        val bottomRow = ds * 46 + runDrop
        // HeartGold's cursor on FIGHT frames the whole panel above BAG / RUN / POKéMON, edge to edge, not the button.
        val fightInteraction = remember { MutableInteractionSource() }
        val fightFocused by fightInteraction.collectIsFocusedAsState()
        val panelCursor = fightFocused || cursor == 0
        val panelPulse = if (panelCursor) rememberDsCursorPulse(1) else 0f
        androidx.compose.runtime.CompositionLocalProvider(LocalHgPixel provides ds) {
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(maxHeight - bottomRow - ds * 3)
                .drawWithContent { drawContent(); if (panelCursor && out < 0.01f) drawHgCursor(ds.toPx(), panelPulse, insideOf = 1) },
        ) {
            Column(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - out }, verticalArrangement = Arrangement.spacedBy(ds * 2)) {
                repeat(2) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(ds * 2)) {
                        repeat(2) { BattlePlaceholder(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
            // The active mon's mini icon sits to the left of the word, like the game.
            PlatinumButton(
                fill = HgRed, frame = HgRedLow,
                // The D-pad (the companion's window) starts on FIGHT, like the game's cursor.
                // HeartGold's 208 x 88, never wider (the user's call: stretched past it, it read too wide).
                modifier = Modifier.align(Alignment.Center).focusStart(true).width(ds * 208).height(ds * 88)
                    .graphicsLayer { translationY = -out * size.height * 1.8f },
                enabled = enabled, battleButton = true, highlighted = cursor == 0,
                interactionSource = fightInteraction, ownCursor = false,
                pressesGame = onFight == null, onClick = { onFight?.invoke() ?: battleInput?.selectAction(0) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpeciesIcon(activeMon?.iconAsset, size = 48.dp)
                    ButtonLabel(tr("FIGHT"), 52.sp, outlined = true)
                }
            }
        }
        // Without a BAG (Elite Redux) RUN and POKéMON sit level.
        Row(
            Modifier.align(Alignment.BottomCenter).width(wide * 254).height(bottomRow),
            horizontalArrangement = if (hasBag) Arrangement.SpaceBetween else Arrangement.spacedBy(wide * 6, Alignment.CenterHorizontally),
        ) {
            val button = Modifier.width(wide * 82).height(ds * 46)
            // A label narrows to its button (the RG DS's are small, and a word can be longer in another language).
            fun fit(word: String, size: Float) = minOf(size, (wide.value * 82 - HG_LABEL_ROOM) / (word.length * PIXEL_FONT_LETTER)).sp
            if (hasBag) Bag(button.align(Alignment.Top), fit(tr("BAG"), 36f))
            Run(button.align(Alignment.Bottom), fit(tr("RUN"), 36f))
            Pokemon(button.align(Alignment.Top), fit(tr("POKéMON"), 30f))
        }
        }
    }
}

/** A DS pixel of the battle pane (HeartGold's layout), for its buttons' cursor; Unspecified outside it. */
internal val LocalHgPixel = androidx.compose.runtime.staticCompositionLocalOf { androidx.compose.ui.unit.Dp.Unspecified }

@Composable
internal fun MoveGrid(
    moves: List<MoveView>,
    battleInput: BattleInput?,
    busy: Boolean,
    showHints: Boolean,
    modifier: Modifier = Modifier,
    /** The grid was opened by us (FIGHT not sent yet): CANCEL / BACK just close it; null = press B. */
    onCancel: (() -> Unit)? = null,
    /** ONE-TAP MOVES' two-line cards, names fitted (BATTLE BUTTONS' smaller window). */
    compact: Boolean = false,
) {
    CompanionBackHandler(enabled = battleInput != null && !busy) { onCancel?.invoke() ?: battleInput?.back() }
    if (gen1Buttons) return Gen1MoveList(moves, battleInput, busy, showHints, modifier, onCancel)
    val cursor = rememberBattleCursor(battleInput).takeIf { !busy } ?: -1
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0..1) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (col in 0..1) {
                    val idx = row * 2 + col
                    // The D-pad (the companion's window) starts on the first move.
                    MoveCard(moves.getOrNull(idx), battleInput, idx, busy, showHints, modifier = Modifier.focusStart(idx == 0).weight(1f).fillMaxHeight(), compact = compact, highlighted = cursor == idx)
                }
            }
        }
        // Wide blue CANCEL bar, matching the in-game move-select screen's own
        // cancel button (color + label) rather than a plain generic "BACK" pill.
        PlatinumButton(
            fill = HgBlue, frame = HgBlueLow,
            // A short screen (the RG DS) keeps the room for the cards.
            modifier = Modifier.fillMaxWidth().height(if (LocalShortCompanion.current) 36.dp else 56.dp),
            enabled = battleInput != null && !busy,
            contentPadding = if (LocalShortCompanion.current) PaddingValues(0.dp) else PaddingValues(8.dp),
            pressesGame = onCancel == null, onClick = { onCancel?.invoke() ?: battleInput?.back() },
        ) { ButtonLabel(tr("CANCEL"), if (LocalShortCompanion.current) 24.sp else 34.sp, outlined = true) }
    }
}

/**
 * Who to send in: the PARTY tab's own slots - the game's party screen - with
 * the game's cursor on SUGGESTIONS' top pick against the foe (BATTLE HINTS on,
 * tagged BEST), else on the one already battling (tagged OUT). Tapping a slot
 * has the app pick it in the game's own party menu ([BattleInput.switchTo]);
 * fainted mons, eggs and the one that's out can't be picked. Below, the game's
 * prompt and CANCEL, which runs [onBack].
 */
@Composable
internal fun PartyPicker(
    party: List<MonView>,
    foe: MonView?,
    active: List<MonView>,
    battleInput: BattleInput?,
    busy: Boolean,
    showHints: Boolean,
    onBack: () -> Unit,
) {
    CompanionBackHandler(enabled = !busy, onBack = onBack)
    val best = remember(party, foe, active) { recommendedSwitch(party, foe, active) }?.takeIf { showHints }
    val cursor = party.indexOfFirst { it == best }.takeIf { it >= 0 } ?: party.indexOfFirst { isOut(it, active) }
    val enabled = battleInput != null && !busy
    val m = rememberGbaTextMetrics()
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PartyGrid(
            party, selected = cursor, sound = false,
            onSlotClick = { mon -> if (enabled && canSendIn(mon, active)) battleInput?.switchTo(mon.personality) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { mon ->
            val tag = when {
                mon == best -> tr("BEST") to SuggestGold
                isOut(mon, active) -> tr("OUT") to VerdictGrey
                else -> null
            }
            tag?.let { (text, color) ->
                Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp)) { VerdictPill(text, color) }
            }
        }
        OptionTitleWindow(tr("Choose a POKéMON."), m) {
            OptionButton(tr("CANCEL"), m, onClick = onBack, enabled = !busy)
        }
    }
}

/** Whether [mon] can be sent in: not fainted, not an egg, not already out (and a real mon to find). */
internal fun canSendIn(mon: MonView, active: List<MonView>): Boolean =
    !mon.isEgg && mon.hp > 0 && !isOut(mon, active) && mon.personality != 0L

@Composable
private fun BackOnlyButton(battleInput: BattleInput?, busy: Boolean) {
    CompanionBackHandler(enabled = battleInput != null && !busy) { battleInput?.back() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PlatinumButton(
            fill = AppColors.slotFillDark, frame = AppColors.slotFrame,
            modifier = Modifier.fillMaxWidth(0.5f).height(56.dp),
            enabled = battleInput != null && !busy,
            pressesGame = true, onClick = { battleInput?.back() },
        ) { ButtonLabel(tr("BACK"), 34.sp) }
    }
}

@Composable
internal fun MoveCard(
    mv: MoveView?,
    battleInput: BattleInput?,
    moveIndex: Int,
    busy: Boolean,
    showHints: Boolean,
    modifier: Modifier = Modifier,
    /** Two lines (ONE-TAP MOVES, under the action buttons): the type joins the power / PP line. */
    compact: Boolean = false,
    /** The game's cursor is on this move ([rememberBattleCursor]). */
    highlighted: Boolean = false,
) {
    val enabled = mv != null && mv.pp > 0 && battleInput != null && !busy
    // Whole-card color keyed by move type (fill stays a constant pale card
    // color, only the border + type pill change) - matches the reference
    // Gen 4 move-selector sheet. Reuses the same type palette TypeBadge does
    // elsewhere in the app, so a move's color here matches its badge color
    // everywhere else.
    // HeartGold's card: the type's colour round a panel white over beige. A move the Pokémon doesn't have is an empty place.
    if (mv == null) return BattlePlaceholder(modifier)
    val frame = AppColors.typeColors[mv.type] ?: AppColors.slotFrame
    PlatinumButton(
        fill = frame,
        frame = lerp(frame, Color.Black, 0.5f),
        modifier = modifier,
        enabled = enabled,
        battleButton = true, highlighted = highlighted,
        contentPadding = if (LocalShortCompanion.current) PaddingValues(horizontal = 4.dp, vertical = 0.dp) else PaddingValues(horizontal = 9.dp, vertical = 4.dp),
        pressesGame = true, onClick = { battleInput?.selectMove(moveIndex) },
    ) {
        Box(Modifier.fillMaxSize().hgMovePanel(lerp(frame, Color.White, 0.4f)).padding(horizontal = 6.dp)) {
            // Name on its own line (the game's own casing, full width), the
            // type under it, then power + per-foe multipliers bottom-left and
            // PP bottom-right. Tight line heights: the three rows have to fit
            // a card a quarter of the landscape grid tall.
            // Bold, with a darker-cream drop shadow like the game's move labels.
            val shadowPx = with(LocalDensity.current) { 2.dp.toPx() }
            val tight = TextStyle(
                fontFamily = pixelFontFamily(), lineHeight = 1.em, fontWeight = FontWeight.Bold,
                shadow = Shadow(MoveTextShadow, Offset(shadowPx, shadowPx), 0f),
            )
            // A short screen (the RG DS): the two lines, smaller - its cards are too short for three.
            val short = LocalShortCompanion.current
            val k = if (short) SHORT_MOVE_TEXT else 1f
            if (compact || short) Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                // Name and PP, then type, power and the multipliers.
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    // A long name (METAL CLAW) steps its size down to fit beside PP rather than being cut.
                    FittedText(mv.name, AppColors.text, tight, maxSize = (32 * k).toInt(), minSize = (20 * k).toInt(), modifier = Modifier.weight(1f).padding(end = 10.dp * k))
                    Text(tr("PP {0}", mv.pp), color = AppColors.text, fontSize = 20.sp * k, style = tight, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp * k)) {
                    TypeBadge(mv.type, fontSize = 16.sp * k)
                    Text(tr("PWR {0}", powerLabel(mv.power)), color = AppColors.muted, fontSize = 22.sp * k, style = tight, maxLines = 1)
                    if (showHints) mv.vs.forEach { v -> MultiplierChip(v.label, v.pct, fontSize = 16.sp * k) }
                }
            } else Column(modifier = Modifier.fillMaxSize()) {
                // HeartGold's halves: the name on the white, then type, power and PP on the beige; the verdict by the name.
                Row(Modifier.fillMaxWidth().weight(0.47f), verticalAlignment = Alignment.CenterVertically) {
                    FittedText(mv.name, AppColors.text, tight, maxSize = 32, minSize = 20, modifier = Modifier.weight(1f))
                    if (showHints) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        mv.vs.forEach { v -> MultiplierChip(v.label, v.pct, fontSize = 16.sp) }
                    }
                }
                Row(Modifier.fillMaxWidth().weight(0.53f), verticalAlignment = Alignment.CenterVertically) {
                    TypeBadge(mv.type, fontSize = 16.sp)
                    Text(
                        tr("PWR {0}", powerLabel(mv.power)), color = AppColors.muted, fontSize = 20.sp, style = tight, maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    Text(tr("PP {0}", mv.pp), color = AppColors.text, fontSize = 20.sp, style = tight, maxLines = 1)
                }
            }
        }
    }
}

/** HeartGold's move card's inside (sampled, drawn here): a thin [rim], white over beige with a step between them. */
private fun Modifier.hgMovePanel(rim: Color): Modifier =
    this.background(rim).padding(HG_PX).drawBehind {
        val split = size.height * 0.47f
        val step = HG_PX.toPx() * 2
        drawRect(Color(0xFFFBFBFB), Offset.Zero, Size(size.width, split))
        drawRect(Color(0xFFE3DBC3), Offset(0f, split), Size(size.width, step))
        drawRect(Color(0xFFCBBAA2), Offset(0f, split + step), Size(size.width, size.height - split - step))
    }

/** [MoveCard]'s text on a short screen ([LocalShortCompanion]), against its usual sizes. */
private const val SHORT_MOVE_TEXT = 0.7f

/** A coloured button in HeartGold's battle-menu style (rather than this app's
 * FR/LG chrome - this screen is a distinct "touch controls" mode): pixel-stepped
 * corners, a thick dark outline, a thin white ring and a two-tone face
 * ([drawTwoTone]). Also the summary screens' square nav buttons (MonDetailScreen's SummaryFrame). Taps play
 * the game's click, unless [pressesGame]: a button that presses the game's own
 * buttons (FIGHT, a move, BACK) leaves the sound to the game. */
@Composable
internal fun PlatinumButton(
    fill: Color,
    frame: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The game's own cursor is on this button: the D-pad's ring. */
    highlighted: Boolean = false,
    pressesGame: Boolean = false,
    /** One of the battle menu's buttons (FIGHT, BAG, POKéMON, RUN, a move): its cursor is HeartGold's own. */
    battleButton: Boolean = false,
    /** The button's interactions, from outside (FIGHT's: its cursor is drawn round the whole panel, as the game's is). */
    interactionSource: MutableInteractionSource? = null,
    /** False: the caller draws the cursor (FIGHT's panel). */
    ownCursor: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = PixelRoundedShape(5.dp)
    val sound = LocalClickSound.current
    val gameSilent = LocalGameSilent.current
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // A battle button's cursor is HeartGold's own: red corner brackets in a white rim, breathing out and back (the user's
    // call, over the app's orange ring); every other button keeps the ring.
    val hgCursor = (battleButton || pressesGame) && !gen1Buttons
    val pulse = if (hgCursor && ownCursor && (focused || highlighted)) rememberDsCursorPulse(1) else 0f
    val hgPixel = LocalHgPixel.current
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            // The D-pad's focus (the companion's window), over the bevel and the content.
            .then(
                if (hgCursor) Modifier.drawWithContent {
                    drawContent()
                    if (ownCursor && (focused || highlighted)) drawHgCursor(if (hgPixel.isSpecified) hgPixel.toPx() else 1.5f * gbaPixelPx(), pulse, insideOf = 1)
                } else Modifier.gbaFocusRing(focused || highlighted, shape),
            )
            .then(
                // Gen 1 (Yellow): a button is its text box - white inside the double line.
                if (gen1Buttons) Modifier.drawBehind {
                    drawLayeredBox(OptionColors.listLayers.inPx(gbaPixelPx()), OptionColors.listFill, radius = 3 * gbaPixelPx())
                } else Modifier
                    // HeartGold's: a thick dark outline, a thin white ring, then [fill] over [frame].
                    // Each layer drawn in the shape, nothing clipped (a path clip is a CPU mask on older renderers: pixelClip).
                    .background(HgOutline, shape).padding(HG_PX * 2)
                    .background(Color(0xFFFBFBFB), shape).padding(HG_PX)
                    .background(frame, shape).padding(HG_PX)
                    .drawBehind { drawTwoTone(fill, frame, HG_PX.toPx() * 2) { color -> drawRoundedFill(color, shape) } },
            )
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = {
                        onClick()
                        // The game sounds its own button - unless it can't be heard (FF's STEADY music mutes it).
                        if (!pressesGame || gameSilent()) sound()
                    })
                } else Modifier,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** [shape] filled in [color]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedFill(color: Color, shape: androidx.compose.ui.graphics.Shape) {
    drawOutline(shape.createOutline(size, layoutDirection, this), color)
}

@Composable
internal fun ButtonLabel(
    text: String, fontSize: TextUnit, bold: Boolean = false,
    /** HeartGold's lettering: cream with a dark line all round. */
    outlined: Boolean = false,
) {
    val density = LocalDensity.current
    val shadow = with(density) { (fontSize.toPx() / 14f).coerceAtLeast(2f) }
    // Gen 1: the game's font, condensed like every GbaText, and bold (a copy half a font pixel right).
    val fontPixel = with(density) { fontSize.toPx() / 16f }
    val scaleX = if (gen1Buttons) gameFontScaleX(fontPixel) else 1f
    val style = TextStyle(
        // (An explicit style replaces the theme's, font included - so name the pixel font.)
        fontFamily = pixelFontFamily(),
        shadow = if (gen1Buttons) null else Shadow(Color(0xFF383838), Offset(shadow, shadow), 0f),
        textGeometricTransform = if (scaleX == 1f) null else androidx.compose.ui.text.style.TextGeometricTransform(scaleX = scaleX),
    )
    val weight = if (bold && !gen1Buttons) FontWeight.Bold else FontWeight.Normal
    if (outlined && !gen1Buttons) {
        val edge = kotlin.math.round(fontPixel).toInt().coerceAtLeast(1)
        val plain = style.copy(shadow = null)
        return Box {
            for ((x, y) in HG_TEXT_EDGES) Text(
                text, color = HgOutline, fontSize = fontSize, maxLines = 1, style = plain,
                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(x * edge, y * edge) },
            )
            Text(text, color = Color(0xFFFBF3E3), fontSize = fontSize, maxLines = 1, style = plain)
        }
    }
    Box {
        if (gen1Buttons) {
            val b = boldOffsetPx(fontPixel * scaleX)
            Text(text, color = buttonContent, fontSize = fontSize, maxLines = 1, fontWeight = weight, style = style, modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(b, 0) })
        }
        Text(text, color = buttonContent, fontSize = fontSize, maxLines = 1, fontWeight = weight, style = style)
    }
}

/**
 * Gen 1's battle menu as the game lays it out: FIGHT and PkMn over ITEM and RUN, four
 * text boxes (the game's own words; [BattleInput]'s indices stay Gen 3's: 1 = ITEM / BAG,
 * 2 = PkMn / POKEMON). The active mon's icon sits by FIGHT, as on the other games.
 */
@Composable
private fun Gen1ActionButtons(
    activeMon: MonView?,
    battleInput: BattleInput?,
    enabled: Boolean,
    onPokemon: (() -> Unit)?,
    modifier: Modifier,
) {
    val m = rememberGbaTextMetrics(2f)
    @Composable
    fun cell(label: String, index: Int, mod: Modifier, icon: Boolean = false) = PlatinumButton(
        fill = Color.White, frame = Color.White, modifier = mod.focusStart(index == 0), enabled = enabled,
        pressesGame = index != 2 || onPokemon == null,
        onClick = { if (index == 2 && onPokemon != null) onPokemon() else battleInput?.selectAction(index) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.u * 6)) {
            if (icon) SpeciesIcon(activeMon?.iconAsset, size = m.u * 32)
            GbaText(label, buttonContent, Color.Transparent, m, bold = true)
        }
    }
    // Prism's menu is mixed case (Fight / PkMn / Pack / Run), Polished Crystal's too (its bag a Bag); Yellow's in each
    // language's own words.
    val words = when (com.pokedaisy.app.companion.data.activeGame) {
        com.pokedaisy.app.companion.data.GameKind.PRISM -> listOf("Fight", "PkMn", "Pack", "Run")
        com.pokedaisy.app.companion.data.GameKind.POLISHED_CRYSTAL -> listOf("Fight", "PkMn", "Bag", "Run")
        else -> com.pokedaisy.app.companion.data.gen1BattleMenuWords(com.pokedaisy.app.companion.data.romLanguage)
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cell(words[0], 0, Modifier.weight(1f).fillMaxHeight(), icon = true)
            cell(words[1], 2, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Gen 2 calls the bag PACK (German Crystal: BEUTEL).
            val gen2Bag = if (com.pokedaisy.app.companion.data.romLanguage == 'D') "BEUTEL" else "PACK"
            cell(if (com.pokedaisy.app.companion.data.activeGame.isGen2 && words[2] != "Pack" && words[2] != "Bag") gen2Bag else words[2], 1,
                Modifier.weight(1f).fillMaxHeight())
            cell(words[3], 3, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * Gen 1's moves in the other games' 2x2 grid (the game itself lists them one under another;
 * [BattleInput.selectMove] steers its cursor either way): each a text box with the name over
 * its type, how it fares and its PP, then CANCEL (B).
 */
@Composable
private fun Gen1MoveList(
    moves: List<MoveView>,
    battleInput: BattleInput?,
    busy: Boolean,
    showHints: Boolean,
    modifier: Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    // A short screen (the RG DS): tighter boxes and smaller pills, or the type line was cut off.
    val short = LocalShortCompanion.current
    val pill = if (short) 12.sp else 16.sp
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0..1) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    val mv = moves.getOrNull(i)
                    PlatinumButton(
                        fill = Color.White, frame = Color.White,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        enabled = mv != null && mv.pp > 0 && battleInput != null && !busy,
                        contentPadding = PaddingValues(horizontal = m.u * 8, vertical = if (short) m.u else m.u * 4),
                        pressesGame = true, onClick = { battleInput?.selectMove(i) },
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceEvenly,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            GbaText(mv?.name ?: "-", buttonContent, Color.Transparent, m, bold = true)
                            if (mv != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TypeBadge(mv.type, fontSize = pill)
                                    if (showHints && mv.power > 0) mv.vs.firstOrNull()?.let { v ->   // status moves ignore the chart
                                        Spacer(Modifier.width(m.u * 4))
                                        MultiplierChip(v.label, v.pct, fontSize = pill)
                                    }
                                    Spacer(Modifier.width(m.u * 8))
                                    GbaText(tr("PP {0}", mv.pp), buttonContent, Color.Transparent, small)
                                }
                            }
                        }
                    }
                }
            }
        }
        PlatinumButton(
            fill = Color.White, frame = Color.White,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = battleInput != null && !busy,
            pressesGame = onCancel == null, onClick = { onCancel?.invoke() ?: battleInput?.back() },
        ) { ButtonLabel(tr("CANCEL"), 34.sp) }
    }
}

/** A Game Boy game's buttons are plain text boxes ([PlatinumButton]). */
internal val gen1Buttons get() = com.pokedaisy.app.companion.data.activeGame.isGen1 ||
    com.pokedaisy.app.companion.data.activeGame.isGen2   // Gen 2's menu is laid out the same

/** [text] on one line at the largest whole size from [maxSize] down to [minSize] (sp) that fits the
 * width it's given; still too long at [minSize], it ends in an ellipsis. */
@Composable
internal fun FittedText(text: String, color: Color, style: TextStyle, maxSize: Int, minSize: Int, modifier: Modifier = Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val size = (maxSize downTo minSize step 2).firstOrNull { s ->
            measurer.measure(text, style.copy(fontSize = s.sp), maxLines = 1, softWrap = false).size.width <= width
        } ?: minSize
        Text(
            text, color = color, fontSize = size.sp, style = style,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
/** What's drawn on a [PlatinumButton]: white, or Gen 1's black on its white box. */
internal val buttonContent: Color get() = if (gen1Buttons) OptionColors.titleText else Color.White

// HeartGold's battle buttons, sampled headless (the colours; everything is drawn here): one of its pixels on the Thor, the dark
// outline, each button's upper and lower colour, and its bottom screen less the rows above the buttons, RUN's lower place kept.
private val HG_PX = 1.5.dp
private val HgOutline = Color(0xFF414141)
private val HgRed = Color(0xFFEB3838)
private val HgRedLow = Color(0xFF8A4141)
private val HgOrange = Color(0xFFE39A28)
private val HgOrangeLow = Color(0xFF8A6938)
private val HgBlue = Color(0xFF2892CB)
private val HgBlueLow = Color(0xFF415971)
private val HgGreen = Color(0xFF59B228)
private val HgGreenLow = Color(0xFF517141)
private const val HG_WIDTH = 256f
private const val HG_HEIGHT = 160f
private const val HG_MAX_WIDEN = 1.3f
/** A button's width its label can't have, in dp (outline, ring, padding), and a pixel-font letter's width against its size. */
private const val HG_LABEL_ROOM = 20f
private const val PIXEL_FONT_LETTER = 0.46f
private val HG_TEXT_EDGES = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)
internal val FightRed = Color(0xFFE8483C)
internal val FightRedDark = Color(0xFFA82820)
private val BagOrange = Color(0xFFE0A030)
private val BagOrangeDark = Color(0xFFA87018)
internal val RunBlue = Color(0xFF4890D8)
internal val RunBlueDark = Color(0xFF2860A0)
internal val MonGreen = Color(0xFF58A840)
internal val MonGreenDark = Color(0xFF307020)
internal val MoveCardFill = Color(0xFFE8E8B0)
internal val MoveTextShadow = Color(0xFFC0C088)
internal val InfoPurple = Color(0xFF9068D0)
internal val InfoPurpleDark = Color(0xFF5C3C98)
internal val SuggestGold = Color(0xFFE8B830)
internal val SuggestGoldDark = Color(0xFFA87C10)
