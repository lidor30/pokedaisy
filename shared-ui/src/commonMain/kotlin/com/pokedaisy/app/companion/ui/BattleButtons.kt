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
import com.pokedaisy.app.companion.battleButtonsLane
import com.pokedaisy.app.companion.battleButtonsRow
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
 * BATTLE STYLE's GEN 5 draws them all in Black / White's look instead (the pieces in BattleControlsGen5.kt).
 *
 * PokeDaisyActivity shows it in single battles of games with touch battle input, on one screen held sideways.
 * BattleButtonsScreenshotTest renders it.
 */

/** What the battle buttons have open over the game. */
enum class BattleButtonsWindow { MOVES, PARTY }

@Composable
fun rememberBattleButtonsRead(snapshot: SnapshotView): Boolean {
    val state = remember { com.pokedaisy.app.companion.BattleOverlayState() }
    LaunchedEffect(snapshot.inBattle, snapshot.battleInputState) {
        state.observe(snapshot.inBattle, snapshot.battleInputState)
    }
    return state.read
}

/**
 * The battle buttons in the room right of [core] (px: where the GBA's own picture is in this box; null = its middle
 * at the box's height, EXPAND's battle). [shown] false fades them out. [allowPictureOverlap] keeps the action column
 * inset from the edge and visible when the picture leaves little room beside it.
 * [clearTop] / [clearBottom] (px) reserve touch-control rows.
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
    /** BATTLE STYLE: GEN 5 draws the buttons and lists in Black / White's look (Gen 1 / 2 keep their own). */
    style: com.pokedaisy.app.companion.BattleStyle = com.pokedaisy.app.companion.BattleStyle.GEN4,
    /** Touch-control bounds in this overlay's pixels; only controls beside the button rows affect placement. */
    avoid: List<Rect> = emptyList(),
    allowPictureOverlap: Boolean = false,
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
    OverlayPresence(window != null, takesFocus = true, changed = onWindow)
    if (!shown && slide == 1f && movesIn == 0f && partyIn == 0f) return
    val busy = rememberBattleInputBusy(battleInput)
    val m = rememberGbaTextMetrics(1f)
    val u = m.u
    val density = LocalDensity.current
    val gen5 = style == com.pokedaisy.app.companion.BattleStyle.GEN5 && !gen1Buttons
    BoxWithConstraints(modifier.fillMaxSize()) {
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val right = core?.right ?: ((boxW + boxH * 1.5f) / 2)
        val room = with(density) { (boxW - right).toDp() }
        val columnW = battleButtonsColumnWidth(room, u, allowPictureOverlap) ?: return@BoxWithConstraints
        val gap = u * 5
        val top = with(density) { clearTop.toDp() } + u * 3
        val bottom = with(density) { clearBottom.toDp() } + u * 3
        val buttonHeight = with(density) {
            val available = constraints.maxHeight - top.roundToPx() - bottom.roundToPx() - 2 * gap.roundToPx()
            minOf((u * MAX_BUTTON).roundToPx(), (available / 3).coerceAtLeast(0)).toDp()
        }
        val actions = state == BATTLE_INPUT_ACTION_SELECT
        val enabled = battleInput != null && !busy
        val active = snapshot.battlePlayer.getOrNull(0)
        val moves = active?.moves.orEmpty().take(4)
        val wild = snapshot.enemyParty.isEmpty()
        // Use a full button frame when the column can sit directly over the picture.
        val edge = if (allowPictureOverlap) 0.dp else u * EDGE
        val inset = if (allowPictureOverlap) 8.dp else 0.dp
        val insetPx = with(density) { inset.roundToPx().toFloat() }
        val columnWidth = columnW + edge
        val columnPx = with(density) { columnWidth.roundToPx().toFloat() }
        val columnRight = boxW + with(density) { edge.roundToPx() } - insetPx
        val rows = with(density) {
            val topPx = top.roundToPx().toFloat()
            Rect(0f, topPx, columnRight, topPx + 3 * buttonHeight.roundToPx() + 2 * gap.roundToPx())
        }
        val lane = battleButtonsLane(rows, columnPx, avoid)
        val compactWidth = minOf(columnW, 88.dp)
        val compactHeight = 42.dp
        val row = if (allowPictureOverlap && maxHeight < 500.dp && lane?.right != columnRight) with(density) {
            val area = Rect(0f, rows.top, columnRight, boxH - bottom.roundToPx())
            val spacing = u.toPx()
            battleButtonsRow(area, (3 * compactWidth.roundToPx() + 2 * gap.roundToPx()).toFloat(),
                compactHeight.roundToPx().toFloat(), avoid.map { it.inflate(spacing) })
        } else null
        val placement = row ?: lane
        val height = if (row == null) buttonHeight else compactHeight
        if (placement != null && height > 0.dp) {
            val width = if (row == null) columnWidth - u * 2 else compactWidth
            val x = with(density) { (placement.right - boxW).toDp() }
            val y = with(density) { placement.top.toDp() }
            val groupWidth = with(density) { placement.width.toDp() }
            val groupHeight = with(density) { placement.height.toDp() }
            val buttonPosition: (Int) -> Modifier = { index ->
                Modifier.offset(
                    x = if (row == null) u * 2 else (width + gap) * index,
                    y = if (row == null) (height + gap) * index else 0.dp,
                ).size(width, height)
            }
            Box(Modifier.align(Alignment.TopEnd).offset(x, y).size(groupWidth, groupHeight)
                .graphicsLayer { translationX = slide * (size.width + boxW - placement.right) }) {
                // FIGHT waits for the move read. On short buttons its icon is the same size as the others.
                val fightIcon = if (height >= u * (24 + WORD_ROOM)) u * 24 else u * 16
                Box(buttonPosition(0)) {
                    ActionButton(
                        tr("FIGHT"), fightIcon, { HudSpecies(active?.iconAsset, fightIcon) },
                        FightRed, FightRedDark, m, height, gen5, Gen5Word.FIGHT,
                        enabled = enabled && moves.isNotEmpty() && (actions || state == BATTLE_INPUT_MOVE_SELECT),
                        pressesGame = false, edge = edge,
                    ) { window = BattleButtonsWindow.MOVES }
                }
                Box(buttonPosition(1)) {
                    ActionButton(
                        tr("POKéMON"), u * 16, { PixelArt(PixelIcons.pokeBall, POKE_BALL, Modifier.size(u * 16)) },
                        MonGreen, MonGreenDark, m, height, gen5, Gen5Word.POKEMON,
                        enabled = enabled && actions && battleInput?.canSwitch == true && snapshot.party.size > 1,
                        pressesGame = false, edge = edge,
                    ) { window = BattleButtonsWindow.PARTY }
                }
                Box(buttonPosition(2)) {
                    ActionButton(
                        tr("RUN"), u * 16, { PixelArt(RUN_ICON, RUN_COLORS, Modifier.size(u * 16)) },
                        RunBlue, RunBlueDark, m, height, gen5, Gen5Word.RUN,
                        enabled = enabled && actions && wild, edge = edge,
                    ) { battleInput?.selectAction(3) }
                }
            }
        }
        val close = { window = null }
        // A list down the right side, over the dimmed game (no window behind it), as big as the room allows.
        if (movesIn > 0f) {
            val grid = gen1Buttons
            val height = if (grid) MOVE_GRID_H.toFloat() else 4 * MOVE_ROW + 4 * MOVE_GAP + MOVE_ROW * 0.7f
            val edge = if (grid) 0 else EDGE
            BattleList(movesIn, window == BattleButtonsWindow.MOVES, top, close, MOVE_LIST_W, height, edge) { u2 ->
                if (grid) {
                    MoveGrid(moves, battleInput, busy, showHints,
                        Modifier.width(u2 * MOVE_LIST_W).height(u2 * MOVE_GRID_H), onCancel = close, compact = true)
                } else {
                    BattleMoveList(moves, battleInput, busy, showHints, u2 * MOVE_LIST_W, u2 * MOVE_ROW, u2 * MOVE_GAP,
                        u2 * EDGE, onCancel = close, gen5 = gen5)
                }
            }
        }
        if (partyIn > 0f) {
            val n = snapshot.party.size.coerceAtLeast(1)
            BattleList(partyIn, window == BattleButtonsWindow.PARTY, top, close, PARTY_LIST_W, n * PARTY_ROW + n * PARTY_GAP + PARTY_ROW * 0.7f) { u2 ->
                BattlePartyList(
                    snapshot.party, snapshot.battleOpponent.getOrNull(0), snapshot.battlePlayer, battleInput, busy, showHints,
                    u2 * PARTY_LIST_W, u2 * PARTY_ROW, u2 * PARTY_GAP, u2, u2 * EDGE, onBack = close, gen5 = gen5,
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
// A move row holds two lines (16 + 12) inside PlatinumButton's HeartGold frame (four 1.5 dp layers: ~4.8 a side here):
// at 34 the second line (type, power, verdict) was cut off.
private const val MOVE_ROW = 38
private const val MOVE_GAP = 3
// The Game Boy grid has a full-width CANCEL button and borders at both edges.
private const val MOVE_GRID_H = 5 * MOVE_ROW + 4 * MOVE_GAP
private const val PARTY_LIST_W = 192
private const val PARTY_ROW = 34
private const val PARTY_GAP = 3

internal fun battleButtonsColumnWidth(room: Dp, unit: Dp, allowPictureOverlap: Boolean): Dp? {
    if (room < unit * MIN_ROOM && !allowPictureOverlap) return null
    return room.coerceIn(unit * MIN_WIDTH, unit * MAX_WIDTH)
}

/** The usual minimum room (GBA pixels) beside the picture. */
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
    /** BATTLE STYLE GEN 5: Black / White's plate, the word in [word]'s colours. */
    gen5: Boolean, word: Gen5Word,
    enabled: Boolean, pressesGame: Boolean = true,
    /** How far it runs past the screen's right edge: its content keeps clear of that part. */
    edge: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val u = m.u
    if (gen5) {
        Gen5ActionButton(word, label, iconH, icon, u, h, WORD_ROOM, enabled, pressesGame, edge, onClick = onClick)
        return
    }
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
 * [content] gets that pixel. List rows can extend [edge] pixels past the right edge. [shown] 0..1: how far it has slid in from the right
 * (the dim fades with it); [open]: it's the one open (taps and BACK reach it only then, not while it slides out).
 */
@Composable
private fun BattleList(
    shown: Float, open: Boolean, top: Dp, onClose: () -> Unit, listW: Int, listH: Float,
    edge: Int = EDGE, content: @Composable (u: Dp) -> Unit,
) {
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
                    .offset(x = u * edge).graphicsLayer { translationX = (1f - shown) * size.width }.then(keep),
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
