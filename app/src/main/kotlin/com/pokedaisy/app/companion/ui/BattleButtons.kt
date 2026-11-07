package com.pokedaisy.app.companion.ui

import androidx.compose.animation.core.animateFloatAsState
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.FieldHudLook
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.AppColors
import kotlin.math.floor

/*
 * BATTLE BUTTONS (SETTINGS, on by default): with one screen and no companion beside the game, FIGHT, POKéMON and RUN
 * at the top of the room right of the game's picture - EXPAND's rebuilt background beside the battle's middle 3:2,
 * ORIGINAL's black bar - reaching a little into the picture's top-right corner where that room is narrow. They're the
 * companion's battle buttons ([PlatinumButton]), and FIGHT / POKéMON open lists down the right side ([BattleMoveList]:
 * the move cards one a row; [BattlePartyList]: FireRed's party list rows): a move is FIGHT + that move in one go
 * ([BattleInput.selectMove]), a Pokémon switches in ([BattleInput.switchTo]); RUN is the action menu's (wild battles
 * only).
 *
 * PokeDaisyActivity shows it in single battles of games with touch battle input, on one screen held sideways.
 * BattleButtonsScreenshotTest renders it.
 */

/** What the battle buttons have open over the game. */
enum class BattleButtonsWindow { MOVES, PARTY }

/**
 * The battle buttons in the room right of [core] (px: where the GBA's own picture is in this box; null = its middle
 * at the box's height, EXPAND's battle). [shown] false fades them out. No room for them (STRETCH, a docked side
 * panel, a narrow screen) = nothing. [clearTop] / [clearBottom] (px): the touch controls' rows, kept clear.
 * [initialWindow]: a window already open (screenshot tests can't tap).
 */
@Composable
fun BattleButtons(
    snapshot: SnapshotView,
    shown: Boolean,
    battleInput: BattleInput?,
    showHints: Boolean,
    modifier: Modifier = Modifier,
    core: Rect? = null,
    clearTop: Int = 0,
    clearBottom: Int = 0,
    /** A window opened / closed (one screen: the side panel's edge tab steps aside for it). */
    onWindow: (Boolean) -> Unit = {},
    initialWindow: BattleButtonsWindow? = null,
) {
    var window by remember { mutableStateOf(initialWindow) }
    val state = snapshot.battleInputState
    // The buttons slide off to the right while they're not wanted - no battle, FIGHT's / POKéMON's list open, the game's
    // own party or bag up - and back in after; the lists slide in from the right and back out. Tests start settled.
    val gameMenu = state == BATTLE_INPUT_PARTY_OPEN || state == BATTLE_INPUT_BAG_OPEN
    val out = !shown || window != null || gameMenu
    val slide by animateFloatAsState(
        if (out) 1f else 0f, if (LocalInspectionMode.current) snap() else tween(SLIDE_MS, easing = FastOutSlowInEasing), label = "battle-buttons",
    )
    val movesIn by animateFloatAsState(
        if (window == BattleButtonsWindow.MOVES) 1f else 0f, if (LocalInspectionMode.current) snap() else tween(SLIDE_MS, easing = FastOutSlowInEasing), label = "moves-list",
    )
    val partyIn by animateFloatAsState(
        if (window == BattleButtonsWindow.PARTY) 1f else 0f, if (LocalInspectionMode.current) snap() else tween(SLIDE_MS, easing = FastOutSlowInEasing), label = "party-list",
    )
    // A window closes with the action menu (a pick ran, the turn moved on) and with the buttons.
    LaunchedEffect(shown, state) {
        if (!shown || state != BATTLE_INPUT_ACTION_SELECT && !(window == BattleButtonsWindow.MOVES && state == BATTLE_INPUT_MOVE_SELECT)) window = null
    }
    LaunchedEffect(window) { onWindow(window != null) }
    if (!shown && slide == 1f && movesIn == 0f && partyIn == 0f) return
    val busy = rememberBattleInputBusy(battleInput)
    val m = rememberGbaTextMetrics(1f)
    val u = m.u
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val right = core?.right ?: ((boxW + boxH * 1.5f) / 2)
        val room = with(density) { (boxW - right).toDp() }
        if (room < u * MIN_ROOM) return@BoxWithConstraints
        val gap = u * 5
        val top = with(density) { clearTop.toDp() } + u * 3
        val actions = state == BATTLE_INPUT_ACTION_SELECT
        val enabled = battleInput != null && !busy
        val active = snapshot.battlePlayer.getOrNull(0)
        val moves = active?.moves.orEmpty().take(4)
        val wild = snapshot.enemyParty.isEmpty()
        val columnW = maxOf(u * MIN_WIDTH, minOf(room, u * MAX_WIDTH))
        BoxWithConstraints(
            // At least MIN_WIDTH: past a 16:9 screen's narrow margin into the picture's top-right corner, where a
            // battle has only its background (the user's call: larger buttons over smaller room).
            // Out of sight while FIGHT's / POKéMON's list is over them (a greyed-out row would show them through).
            // Running off the screen's right edge ([EDGE] GBA pixels past it): no right border or corners, a tab coming out
            // of the side.
            Modifier.align(Alignment.TopEnd).offset(x = u * EDGE).width(columnW + u * EDGE).fillMaxHeight()
                .graphicsLayer { translationX = slide * size.width }
                .padding(top = top, bottom = with(density) { clearBottom.toDp() } + u * 3)
                .padding(start = u * 2),
        ) {
            val h = minOf(u * MAX_BUTTON, (maxHeight - gap * 2) / 3)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                // FIGHT, with the battling Pokémon's icon (like the companion's): our move grid from the moves already
                // read - none yet (a launch's first moments) = it waits, rather than pressing the game's FIGHT blind.
                // Short buttons (a wide phone's, between the touch controls' rows): its icon the size of the others'.
                val fightIcon = if (h >= u * (24 + WORD_ROOM)) u * 24 else u * 16
                ActionButton(
                    tr("FIGHT"), fightIcon, { HudSpecies(active?.iconAsset, fightIcon) }, FightRed, FightRedDark, m, h,
                    enabled = enabled && moves.isNotEmpty() && (actions || state == BATTLE_INPUT_MOVE_SELECT), pressesGame = false, edge = u * EDGE,
                ) { window = BattleButtonsWindow.MOVES }
                ActionButton(
                    tr("POKéMON"), u * 16, { PixelArt(PixelIcons.pokeBall, POKE_BALL, Modifier.size(u * 16)) }, MonGreen, MonGreenDark, m, h,
                    enabled = enabled && actions && battleInput?.canSwitch == true && snapshot.party.size > 1,
                    pressesGame = false, edge = u * EDGE,
                ) { window = BattleButtonsWindow.PARTY }
                ActionButton(
                    tr("RUN"), u * 16, { PixelArt(RUN_ICON, RUN_COLORS, Modifier.size(u * 16)) }, RunBlue, RunBlueDark, m, h,
                    enabled = enabled && actions && wild, edge = u * EDGE,
                ) { battleInput?.selectAction(3) }
            }
        }
        val close = { window = null }
        // A list down the right side, over the dimmed game (no window behind it), as big as the room allows.
        if (movesIn > 0f) BattleList(movesIn, window == BattleButtonsWindow.MOVES, top, close, MOVE_LIST_W, 4 * MOVE_ROW + 3 * MOVE_GAP + MOVE_ROW * 0.7f) { u2 ->
            BattleMoveList(moves, battleInput, busy, showHints, u2 * MOVE_LIST_W, u2 * MOVE_ROW, u2 * MOVE_GAP, u2 * EDGE, onCancel = close)
        }
        if (partyIn > 0f) {
            val n = snapshot.party.size.coerceAtLeast(1)
            BattleList(partyIn, window == BattleButtonsWindow.PARTY, top, close, PARTY_LIST_W, n * PARTY_ROW + n * PARTY_GAP + PARTY_ROW * 0.7f) { u2 ->
                BattlePartyList(
                    snapshot.party, snapshot.battleOpponent.getOrNull(0), snapshot.battlePlayer, battleInput, busy, showHints,
                    u2 * PARTY_LIST_W, u2 * PARTY_ROW, u2 * PARTY_GAP, u2, u2 * EDGE, onBack = close,
                )
            }
        }
    }
}

/** How far (GBA pixels) the buttons and the lists' rows run past the screen's right edge: their right border, corners and
 * bevel band out of sight. */
private const val EDGE = 13

// FIGHT's / POKéMON's lists, in GBA pixels at the scale [BattleList] picks: list width (with [EDGE]), row height, gap.
private const val MOVE_LIST_W = 196
private const val MOVE_ROW = 34
private const val MOVE_GAP = 3
private const val PARTY_LIST_W = 192
private const val PARTY_ROW = 34
private const val PARTY_GAP = 3

/** Below this much room (GBA pixels) right of the picture, no buttons. */
private const val MIN_ROOM = 40
/** What a button needs beside its icon (GBA pixels): its word, the bevel and the padding. */
private const val WORD_ROOM = 18

/** The narrowest the column gets (GBA pixels), however little room is beside the picture: POKéMON at full width. */
private const val MIN_WIDTH = 64

/** The widest the column gets (GBA pixels), by the screen's edge: a 20:9 phone has twice a 16:9 screen's room. */
private const val MAX_WIDTH = 76

/** The tallest a button gets (GBA pixels). */
private const val MAX_BUTTON = 56

/** FIGHT / POKéMON / RUN: the companion's battle button in [fill] / [frame], an icon over the word. */
@Composable
private fun ActionButton(
    label: String, iconH: Dp, icon: @Composable () -> Unit, fill: Color, frame: Color, m: GbaTextMetrics, h: Dp,
    enabled: Boolean, pressesGame: Boolean = true,
    /** How far it runs past the screen's right edge: its content keeps clear of that part. */
    edge: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val u = m.u
    PlatinumButton(
        fill = fill, frame = frame, modifier = Modifier.fillMaxWidth().height(h),
        enabled = enabled, pressesGame = pressesGame,
        // Clear of the bevel's side bands.
        contentPadding = PaddingValues(start = u * 3, end = u * 3 + edge, top = u * 2, bottom = u * 2), onClick = onClick,
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            // Squeezed between the touch controls' rows (a wide phone): the word alone, not a word cut in half.
            if (h >= iconH + u * WORD_ROOM) icon()
            FittedGbaText(label, buttonContent, if (buttonContent == Color.White) ButtonShadow else Color.Transparent, m)
        }
    }
}

private val ButtonShadow = Color(0xFF383838)

/**
 * FIGHT's / POKéMON's list, top right from the buttons' top edge down, over the dimmed game; a tap outside closes it, as
 * BACK does. Drawn at the largest whole GBA pixel at which [listW] x [listH] (GBA pixels) fits under [top] and in 55% of
 * the screen's width (its own [Density], as the HUD's SIZE does), so it uses the room and its pixels stay square;
 * [content] gets that pixel. It runs [EDGE] pixels off the right edge. [shown] 0..1: how far it has slid in from the right
 * (the dim fades with it); [open]: it's the one open (taps and BACK reach it only then, not while it slides out).
 */
@Composable
private fun BattleList(shown: Float, open: Boolean, top: Dp, onClose: () -> Unit, listW: Int, listH: Float, content: @Composable (u: Dp) -> Unit) {
    val base = LocalDensity.current
    if (open) CompanionBackHandler(onBack = onClose)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0x66000000).copy(alpha = 0.4f * shown))
            .then(
                if (open) Modifier.soundClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                else Modifier,
            ),
    ) {
        val topPx = with(base) { top.toPx() }
        val hPx = with(base) { maxHeight.toPx() } - topPx - with(base) { 6.dp.toPx() }
        val wPx = with(base) { maxWidth.toPx() } * 0.55f
        val k = floor(minOf(hPx / listH, wPx / listW)).toInt().coerceAtLeast(1)
        val scaled = remember(k, base.fontScale) { Density(k * 16f / 20f, base.fontScale) }
        // A tap on the list's empty parts does nothing (the dim around it closes it).
        val keep = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = open, onClick = {})
        CompositionLocalProvider(LocalDensity provides scaled) {
            val u = rememberGbaTextMetrics(1f).u
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = with(scaled) { topPx.toDp() })
                    .offset(x = u * EDGE).graphicsLayer { translationX = (1f - shown) * size.width }.then(keep),
            ) { content(u) }
        }
    }
}

/** How long the buttons and the lists take to slide (ms). */
private const val SLIDE_MS = 260

/**
 * [text] on one line at [m]'s height, its letters narrowed as far as it needs (3 screen pixels a font pixel wide, then
 * 2, 1.5, 1 - the height stays, so every label reads as one size), then the smaller size, then cut. A 16:9 screen's
 * margin is ~22 GBA pixels: the longest move names need the narrowest.
 */
@Composable
internal fun FittedGbaText(text: String, color: Color, shadow: Color, m: GbaTextMetrics, modifier: Modifier = Modifier, bold: Boolean = false) {
    val small = rememberGbaTextMetrics(0.75f)
    val measurer = rememberTextMeasurer()
    val font = com.pokedaisy.app.companion.ui.theme.pixelFontFamily()
    val game = gameFontScaleX(m)
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        fun widths(mm: GbaTextMetrics) = listOf(mm.fontPixel, mm.fontPixel - 1f, 1.5f, 1f).filter { it in 1f..mm.fontPixel }.distinct().sortedDescending()
        val candidates = listOf(m, small).flatMap { mm -> widths(mm).map { w -> mm to (w / mm.fontPixel) * game } }
        // Both ways: layoutlib's measurer leaves scaleX out of the width, a device's rounds glyphs its own way - and
        // a font pixel spare, or the device's layout cuts it with "...".
        val (metrics, scaleX) = candidates.firstOrNull { (mm, sx) ->
            val plain = measurer.measure(text, gbaTextStyle(font, mm), maxLines = 1, softWrap = false).size.width * sx
            val scaled = measurer.measure(text, gbaTextStyle(font, mm, sx), maxLines = 1, softWrap = false).size.width.toFloat()
            maxOf(plain, scaled) + mm.fontPixel <= width
        } ?: candidates.last()
        GbaText(text, color, shadow, metrics, scaleX = scaleX, bold = bold)
    }
}

// ---- Pixel art ----------------------------------------------------------------------------------

private val POKE_BALL = mapOf('K' to Color(0xFF202020), 'R' to Color(0xFFE83028), 'W' to Color.White, 'H' to Color(0xFFF8A8A0))

/** A running figure: K outline, B its body. */
private val RUN_ICON = listOf(
    "........KKK.....",
    ".......KBBBK....",
    ".......KBBBK....",
    "........KKK.....",
    "......KKBBK.....",
    "....KKBBBBBKK...",
    "...KBK.KBBK.KBK.",
    "...KK..KBBK..KK.",
    ".......KBBBK....",
    "......KBBKBBK...",
    ".....KBBK.KBBK..",
    "....KBBK...KBK..",
    "..KKBBK....KBK..",
    "..KBBK.....KBBK.",
    "...KK.......KK..",
    "................",
)
private val RUN_COLORS = mapOf('K' to Color(0xFF1C2A44), 'B' to Color.White)
