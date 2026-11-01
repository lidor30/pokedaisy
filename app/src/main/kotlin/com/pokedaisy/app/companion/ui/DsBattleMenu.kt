package com.pokedaisy.app.companion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.typeLabel
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/*
 * A DS battle on one screen (HeartGold / SoulSilver; DS LAYOUT's ONE SCREEN and PICTURE IN PICTURE): the game's own battle
 * buttons, run like Gen 3's menus - the user's call. The battle keeps its wide view; a strip across the whole screen takes
 * the text box's rows, in the band's own black: HeartGold's text box at its far left ("What will ... do?", drawn here, the
 * game's own hidden under the strip) and FIGHT / BAG / POKéMON / RUN as a 2 x 2 at its right, the cursor HeartGold's red
 * corners, pulsing. FIGHT turns the strip into the moves, HeartGold's cards 2 x 2, BACK beside them. The D-pad moves the cursor and A picks
 * ([DsBattleMenuState.key], fed by the activity, the keys kept from the game), B goes back from the moves; a tap picks too.
 * A pick presses the game's own touch-screen button ([BattleInput], NdsBattleTaps). The bag and the party are the game's own
 * screens, floating in over the battle.
 */

/** The menu's cursor and what it's showing, shared with the activity's keys. */
class DsBattleMenuState {
    /** 0 nothing up (the keys go to the game), 1 the actions, 2 the moves. */
    var mode by mutableIntStateOf(0)
        internal set
    /** FIGHT 0, BAG 1, POKéMON 2, RUN 3 (2 x 2: FIGHT BAG / POKéMON RUN). */
    var actionCursor by mutableIntStateOf(0)
    var moveCursor by mutableIntStateOf(0)

    internal var actionEnabled: (Int) -> Boolean = { true }
    internal var moveEnabled: (Int) -> Boolean = { true }
    internal var pickAction: (Int) -> Unit = {}
    internal var pickMove: (Int) -> Unit = {}
    internal var back: () -> Unit = {}

    /** A button while the menu is up: true = it's the menu's (kept from the game). */
    fun key(k: MenuKey): Boolean {
        val moves = mode == 2
        if (mode == 0) return false
        val c = if (moves) moveCursor else actionCursor
        fun to(n: Int) {
            if (n == c || moves && !moveEnabled(n)) return
            if (moves) moveCursor = n else actionCursor = n
        }
        when (k) {
            // Gen 3's grids don't wrap: UP / DOWN keep the column, LEFT / RIGHT the row.
            MenuKey.UP -> to(c % 2)
            MenuKey.DOWN -> to(2 + c % 2)
            MenuKey.LEFT -> to(c - c % 2)
            MenuKey.RIGHT -> to(c - c % 2 + 1)
            MenuKey.A -> if (moves) { if (moveEnabled(c)) pickMove(c) } else if (actionEnabled(c)) pickAction(c)
            MenuKey.B -> if (moves) back()
            else -> return false
        }
        return true
    }
}

/** The rows the strip takes on the DS's top screen (of its 192): the battle's black band (from 144) with HeartGold's message
 * window in it (146 - 191; headless). */
private const val BAND_TOP = 144
private const val TEXT_TOP = 146

/**
 * The menu over the stage, for [snapshot]'s battle. [picture]: where the DS's top screen is in this box (px); [shown]
 * false (no battle, BATTLE BUTTONS off, a double battle) = nothing, and [state] lets go of the keys; [active] false (the
 * floating touch screen is up) = the strip stays as it was, the keys the window's.
 */
@Composable
fun DsBattleMenu(
    snapshot: SnapshotView, state: DsBattleMenuState, battleInput: BattleInput?, picture: Rect?, shown: Boolean, active: Boolean = true,
) {
    val input = snapshot.battleInputState
    val busy = rememberBattleInputBusy(battleInput)
    val mode = when {
        !shown || !active || picture == null || battleInput == null -> 0
        input == BATTLE_INPUT_ACTION_SELECT -> 1
        input == BATTLE_INPUT_MOVE_SELECT -> 2
        else -> 0
    }
    val moves = snapshot.battlePlayer.getOrNull(0)?.moves.orEmpty().take(4)
    // The game asks by the nickname: the battler's from the party (species, level and HP alike), else its species.
    val battler = snapshot.battlePlayer.getOrNull(0)?.let { b ->
        snapshot.party.firstOrNull { it.species == b.species && it.level == b.level && it.hp == b.hp }?.shownName ?: b.name
    }.orEmpty()
    val wild = snapshot.enemyParty.isEmpty()
    SideEffect {
        if (state.mode != mode) {
            // The moves open on the first; the actions keep their place, as the game's do.
            if (mode == 2) state.moveCursor = 0
            state.mode = mode
        }
        state.actionEnabled = { it != 3 || wild }
        state.moveEnabled = { moves.getOrNull(it)?.let { m -> m.pp > 0 } == true }
        state.pickAction = { if (!busy) battleInput?.selectAction(it) }
        state.pickMove = { if (!busy) battleInput?.selectMove(it) }
        state.back = { if (!busy) battleInput?.back() }
    }
    // What the strip shows: the menu up, or - the keys the game's meanwhile - the last one held while the battle's bag or
    // party floats over it, and for a moment between two menus (FIGHT's panel sliding in), so the game's own text box
    // never flashes up in the middle.
    val held = remember { mutableIntStateOf(0) }
    val holding = input == BATTLE_INPUT_BAG_OPEN || input == BATTLE_INPUT_PARTY_OPEN
    androidx.compose.runtime.LaunchedEffect(mode, holding) {
        if (mode != 0) held.intValue = mode
        else if (!holding) { kotlinx.coroutines.delay(HOLD_MS); held.intValue = 0 }
    }
    val shownMode = if (mode != 0) mode else if (shown && picture != null) held.intValue else 0
    if (shownMode == 0 || picture == null) return
    val idle = busy || mode == 0
    val density = LocalDensity.current
    val m = rememberGbaTextMetrics(TEXT_SCALE)
    val u = rememberGbaTextMetrics().u
    // HeartGold's cursor breathes out and back a pixel at a time.
    val pulse by rememberInfiniteTransition(label = "dsCursor").animateFloat(
        0f, PULSE_PX + 0.99f, infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Reverse), label = "dsCursorPulse",
    )
    val step = kotlin.math.floor(pulse)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = with(density) { maxWidth.toPx() }
        val ds = picture.height / 192f
        val top = picture.top + BAND_TOP * ds
        val bottom = picture.bottom
        val textTop = picture.top + TEXT_TOP * ds
        val gap = with(density) { (u * 3).toPx() }
        // The strip: the band's black across the screen, under everything else here (taps on it are the menu's, not the
        // game's).
        with(density) {
            Box(
                Modifier.offset(y = top.toDp()).size(maxWidth, (bottom - top).toDp()).background(BAND)
                    .clickable(remember { MutableInteractionSource() }, null) {},
            )
        }
        @Composable
        fun Area(l: Float, r: Float, content: @Composable () -> Unit) {
            with(density) {
                Box(Modifier.offset(x = l.toDp(), y = textTop.toDp()).size((r - l).toDp(), (bottom - textTop - ds).toDp())) { content() }
            }
        }
        if (shownMode == 1) {
            // The buttons at the strip's right end, the message window filling the rest from the far left.
            val buttonsW = (picture.width * BUTTONS_WIDTH).coerceAtMost(boxW / 2)
            val buttonsL = boxW - gap - buttonsW
            Area(2 * ds, buttonsL - gap) {
                DsMessageWindow(tr("What will {0} do?", battler), m, ds, silver = snapshot.game == GameKind.SOULSILVER)
            }
            Area(buttonsL, boxW - gap) {
                Grid(state.actionCursor, u, step) { i, on ->
                    val enabled = state.actionEnabled(i) && !idle
                    DsButton(ACTIONS[i].first, ACTIONS[i].second, enabled, m, u, Modifier.fillMaxSize()) {
                        state.actionCursor = i
                        state.pickAction(i)
                    }
                }
            }
        } else {
            // The moves across the strip, as Gen 3's take the text box; HeartGold's cards. BACK for a finger at the end.
            Area(gap, boxW - gap) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Grid(state.moveCursor, u, step) { i, on ->
                            DsMoveCard(moves.getOrNull(i), state.moveEnabled(i) && !idle, ds, Modifier.fillMaxSize()) {
                                state.moveCursor = i
                                state.pickMove(i)
                            }
                        }
                    }
                    // The game's own way back: its blue button with the return arrow, as tall as the two rows of moves.
                    DsBackButton(!idle, ds, Modifier.fillMaxHeight().aspectRatio(BACK_ASPECT)) { state.back() }
                }
            }
        }
    }
}

/** HeartGold's battle message window (sampled headless): a dark outline, a gold line lit top-left, a slate frame, white
 * inside, its words dark grey over a light shadow; SoulSilver's ([silver]) is the same with that line silver-blue.
 * [ds]: screen px a DS pixel. */
@Composable
private fun DsMessageWindow(text: String, m: GbaTextMetrics, ds: Float, silver: Boolean = false) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val w = size.width
            val h = size.height
            fun rect(c: Color, inset: Float, inB: Float = inset) =
                drawRect(c, androidx.compose.ui.geometry.Offset(inset * ds, inset * ds), androidx.compose.ui.geometry.Size(w - (inset + inB) * ds, h - (inset + inB) * ds))
            drawPixelRoundRect(MSG_OUTLINE, radius = 2 * ds, step = ds)
            rect(if (silver) MSG_SILVER_DARK else MSG_GOLD_DARK, 2f)
            rect(if (silver) MSG_SILVER else MSG_GOLD, 2f, 3f)
            rect(MSG_SLATE, 3f)
            drawRect(MSG_FILL, androidx.compose.ui.geometry.Offset(8 * ds, 4 * ds), androidx.compose.ui.geometry.Size(w - 16 * ds, h - 9 * ds))
        }.padding(start = with(LocalDensity.current) { (14 * ds).toDp() }, end = with(LocalDensity.current) { (10 * ds).toDp() },
            top = with(LocalDensity.current) { (MSG_TEXT_TOP * ds).toDp() }),
        contentAlignment = Alignment.TopStart,
    ) { GbaText(text, MSG_TEXT, MSG_SHADOW, dsTextMetrics(ds, 1f), maxLines = 2) }
}

/** Text at a DS font's size: Pixel Operator's capitals are 9 font pixels, as the DS games' are 9 DS pixels, so a font pixel is
 * [scale] DS pixels, rounded to whole screen pixels ([ds]: screen px a DS pixel). */
@Composable
private fun dsTextMetrics(ds: Float, scale: Float): GbaTextMetrics {
    val density = LocalDensity.current
    return remember(density, ds, scale) {
        with(density) {
            val k = kotlin.math.round(20.dp.toPx() / 16f).toInt().coerceAtLeast(2)
            val per = kotlin.math.round(ds * scale).toInt().coerceAtLeast(1)
            val fontPx = 16f * per
            GbaTextMetrics(k, k.toDp(), fontPx.toSp(), per.toFloat(), fontPx.toDp(), (fontPx * 1.25f).toDp())
        }
    }
}

/** The message's first line: its letters' tops 9 DS pixels under the window's (the game's, headless), less the font's own
 * room above its capitals. */
private const val MSG_TEXT_TOP = 5

/** The menus' text, against the companion's. */
private const val TEXT_SCALE = 1.3f

/** The buttons' share of the DS top screen's width (at most half the strip). */
private const val BUTTONS_WIDTH = 0.46f

/** How long the strip stays after its menu goes (not to the bag / party): FIGHT's panel slides in meanwhile. */
private const val HOLD_MS = 300L

/** The cursor's pulse: out this many GBA pixels and back over [PULSE_MS] each way. */
private const val PULSE_PX = 2
private const val PULSE_MS = 420

/** A DS battle button's two halves (HeartGold / SoulSilver's battle panel, sampled headless: light over dark). */
private class DsButtonColors(val top: Color, val bottom: Color)
private val ACTIONS = listOf(
    tk("FIGHT") to DsButtonColors(Color(0xFFEB3838), Color(0xFF8A4141)),
    tk("BAG") to DsButtonColors(Color(0xFFE39A28), Color(0xFF8A6938)),
    tk("POKéMON") to DsButtonColors(Color(0xFF59B228), Color(0xFF517141)),
    tk("RUN") to DsButtonColors(Color(0xFF2892CB), Color(0xFF415971)),
)
/** Their outline, the white rim inside it, the labels' cream and the cursor's red (the battle bag's corners). */
private val OUTLINE = Color(0xFF414141)
private val RIM = Color(0xFFFBFBFB)
private val LABEL = Color(0xFFFBF3E3)
private val CURSOR = Color(0xFFE83020)
private val CARD_FILL = Color(0xFFFBFBFB)
private val CARD_TEXT = Color(0xFF383838)
private val CARD_TEXT_SHADOW = Color(0xFF8A8A8A)
private val CARD_NAME = Color(0xFF797979)
private val CARD_NAME_SHADOW = Color(0xFFE3E3E3)
private val CARD_OUTLINE = Color(0xFF303030)
private val CARD_RIM = Color(0xFFFBFBFB)
private val CARD_TAN = Color(0xFFCBBAA2)
private val CARD_TAN_LINE = Color(0xFFE3DBC3)
/** The return button's colours. */
private val BACK_OUTLINE = Color(0xFF414141)
private val BACK_RIM = Color(0xFFFBF3E3)
private val BACK_EDGE = Color(0xFF1869B2)
private val BACK_LIGHT = Color(0xFF49AAFB)
private val BACK_MID = Color(0xFF3892E3)
private val BACK_LOW = Color(0xFF2882CB)
/** The back button's width against its height (a whole square read too big beside the moves). */
private const val BACK_ASPECT = 0.72f

/** A move button's name and its small print (type, PP, multiplier), in DS pixels a font pixel. */
private const val NAME_SCALE = 0.75f
private const val SMALL_SCALE = 0.5f
/** The battle's band and its message window's colours. */
private val BAND = Color(0xFF202020)
private val MSG_OUTLINE = Color(0xFF494141)
private val MSG_GOLD = Color(0xFFFBDB69)
private val MSG_GOLD_DARK = Color(0xFFEBA238)
private val MSG_SILVER = Color(0xFFDBEBFB)
private val MSG_SILVER_DARK = Color(0xFFAABADB)
private val MSG_SLATE = Color(0xFF616169)
private val MSG_FILL = Color(0xFFFBFBFB)
private val MSG_TEXT = Color(0xFF515159)
private val MSG_SHADOW = Color(0xFFA2A2AA)

/** Two rows of two, a little apart; [cell] draws cell i (0 1 / 2 3), [cursor]'s with the red corners [pulse] px out. */
@Composable
private fun Grid(cursor: Int, u: Dp, pulse: Float, cell: @Composable (Int, Boolean) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(u * 3)) {
        for (row in 0..1) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
            for (col in 0..1) {
                val i = row * 2 + col
                Box(Modifier.weight(1f).fillMaxHeight().zIndex(if (i == cursor) 1f else 0f).cursorCorners(i == cursor, u, pulse)) {
                    cell(i, i == cursor)
                }
            }
        }
    }
}

/** HeartGold's red corner brackets round the cursor's button, thick, in a white rim, [pulse] GBA pixels out from its edges. */
private fun Modifier.cursorCorners(on: Boolean, u: Dp, pulse: Float) = if (!on) this else drawWithContent {
    drawContent()
    val px = u.toPx()
    val out = (1 + pulse) * px
    val t = 3 * px
    val l = 9 * px
    val rim = px
    val w = size.width
    val h = size.height
    for ((cx, cy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        // The corner's outer point, then its two arms running in along the edges.
        val x = if (cx < 0) -out else w + out
        val y = if (cy < 0) -out else h + out
        val armX = androidx.compose.ui.geometry.Rect(if (cx < 0) x else x - l, if (cy < 0) y else y - t, if (cx < 0) x + l else x, if (cy < 0) y + t else y)
        val armY = androidx.compose.ui.geometry.Rect(if (cx < 0) x else x - t, if (cy < 0) y else y - l, if (cx < 0) x + t else x, if (cy < 0) y + l else y)
        for (a in listOf(armX, armY)) drawRect(RIM, a.topLeft - androidx.compose.ui.geometry.Offset(rim, rim), androidx.compose.ui.geometry.Size(a.width + 2 * rim, a.height + 2 * rim))
        for (a in listOf(armX, armY)) drawRect(CURSOR, a.topLeft, a.size)
    }
}

/** A HeartGold battle button: dark outline with pixel corners, a white rim, its colour light over dark, the label in cream. */
@Composable
private fun DsButton(
    label: String, colors: DsButtonColors, enabled: Boolean, m: GbaTextMetrics, u: Dp, modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f)
            .drawBehind { drawDsButton(colors.top, colors.bottom, u.toPx()) }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = u * 4),
        contentAlignment = Alignment.Center,
    ) { FittedGbaText(tr(label), LABEL, OUTLINE, m) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDsButton(top: Color, bottom: Color, px: Float) {
    drawPixelRoundRect(OUTLINE, radius = 5 * px, step = px)
    drawPixelRoundRect(RIM, androidx.compose.ui.geometry.Offset(2 * px, 2 * px), androidx.compose.ui.geometry.Size(size.width - 4 * px, size.height - 4 * px), radius = 4 * px, step = px)
    val inner = androidx.compose.ui.geometry.Size(size.width - 6 * px, size.height - 6 * px)
    drawPixelRoundRect(bottom, androidx.compose.ui.geometry.Offset(3 * px, 3 * px), inner, radius = 3 * px, step = px)
    val half = kotlin.math.floor(inner.height / 2 / px) * px
    drawPixelRoundRect(top, androidx.compose.ui.geometry.Offset(3 * px, 3 * px), androidx.compose.ui.geometry.Size(inner.width, half + 3 * px), radius = 3 * px, step = px)
    drawRect(bottom, androidx.compose.ui.geometry.Offset(3 * px, 3 * px + half), androidx.compose.ui.geometry.Size(inner.width, inner.height - half))
}

/** HeartGold's move button (sampled headless), at the strip's size: a dark outline and white rim round a thick frame in the
 * move's type colour - bright over darker, its inner edge lit - a white top with the name in the middle, a tan bottom with
 * the type and PP; [ds] screen px a DS pixel, every part a whole number of them. Top right, its multiplier on the foe. */
@Composable
private fun DsMoveCard(move: MoveView?, enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val type = move?.let { com.pokedaisy.app.companion.ui.theme.AppColors.typeColors[it.type] } ?: Color(0xFF8A8A8A)
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    val density = LocalDensity.current
    fun dp(n: Float) = with(density) { (n * d).toDp() }
    val nameM = dsTextMetrics(ds, NAME_SCALE)
    val smallM = dsTextMetrics(ds, SMALL_SCALE)
    Box(
        modifier.alpha(if (move != null && enabled) 1f else 0.45f)
            .drawBehind {
                val w = size.width
                val h = size.height
                fun box(c: Color, l: Float, t: Float, r: Float, b: Float, radius: Float = 0f) =
                    if (radius > 0f) drawPixelRoundRect(c, Offset(l * d, t * d), Size(w - (l + r) * d, h - (t + b) * d), radius = radius * d, step = d)
                    else drawRect(c, Offset(l * d, t * d), Size(w - (l + r) * d, h - (t + b) * d))
                // (In DS pixels from the card's edge, as the game's at the sides: outline, white rim, the frame's dark line,
                // its colour 4 wide, its lit inner edge, the inside from 8 / 7. Top and bottom a pixel of each: a button here
                // is 21 DS pixels tall, the game's 52, and the name and the small print need the room.)
                val split = kotlin.math.floor((h / d) * 0.56f)
                box(CARD_OUTLINE, 0f, 0f, 0f, 0f, 3f)
                box(CARD_RIM, 1f, 1f, 1f, 1f, 2f)
                box(lerp(type, Color.Black, 0.55f), 2f, 1f, 2f, 1f, 1f)
                box(type, 3f, 1f, 3f, 1f)
                drawRect(lerp(type, Color.Black, 0.3f), Offset(3 * d, split * d), Size(w - 6 * d, h - split * d - d))
                box(lerp(type, Color.White, 0.45f), 7f, 2f, 7f, 2f)
                box(CARD_FILL, 8f, 3f, 7f, 2f)
                drawRect(CARD_TAN_LINE, Offset(8 * d, split * d), Size(w - 15 * d, d))
                drawRect(CARD_TAN, Offset(8 * d, (split + 1) * d), Size(w - 15 * d, h - (split + 3) * d))
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = move != null && enabled, onClick = onClick)
            .padding(start = dp(9f), end = dp(8f), top = dp(3f), bottom = dp(2f)),
    ) {
        if (move == null) return@Box
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(0.56f), contentAlignment = Alignment.Center) {
                // At its whole line's height, centred on the panel: the line (16 font pixels) is taller than the panel, and
                // text squeezed into less is clipped - the letters (9) fit, the rest of the line is empty. Bold, as the
                // game's heavy letters.
                // (Up 2 DS pixels: the font's capitals sit low in its line, so centred they met the tan.)
                FittedGbaText(
                    move.name, CARD_NAME, CARD_NAME_SHADOW, nameM,
                    Modifier.wrapContentHeight(unbounded = true).offset(y = -dp(2f)), bold = true,
                )
                // The multiplier on the foe, as a number alone (x2, x0.5...): coloured as battle INFO's verdicts.
                move.vs.firstOrNull()?.let { v ->
                    Box(Modifier.align(Alignment.TopEnd).background(verdictColor(v.pct), PixelRoundedShape(dp(1f))).padding(horizontal = dp(1f))) {
                        GbaText(v.label, Color.White, Color.Transparent, smallM)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().weight(0.44f), verticalAlignment = Alignment.CenterVertically) {
                // The type, as the game's chip: its colour, lit on top, white letters.
                Box(
                    Modifier.background(type, PixelRoundedShape(dp(1f))).drawBehind {
                        drawRect(lerp(type, Color.White, 0.45f), Offset(0f, 0f), Size(size.width, d))
                    }.padding(horizontal = dp(2f)),
                ) { GbaText(typeLabel(move.type), Color.White, lerp(type, Color.Black, 0.45f), smallM) }
                Spacer(Modifier.weight(1f))
                GbaText(tr("PP {0}", move.pp), CARD_TEXT, CARD_TEXT_SHADOW, smallM)
            }
        }
    }
}

/** The game's square way back (its battle bag's, sampled headless): a dark outline, a cream rim, blue shaded light to dark
 * downwards, the cream return arrow in the middle. */
@Composable
private fun DsBackButton(enabled: Boolean, ds: Float, modifier: Modifier, onClick: () -> Unit) {
    val d = kotlin.math.floor(ds).coerceAtLeast(1f)
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f)
            .drawBehind {
                val w = size.width
                val h = size.height
                drawPixelRoundRect(BACK_OUTLINE, radius = 4 * d, step = d)
                drawPixelRoundRect(BACK_RIM, Offset(2 * d, 2 * d), Size(w - 4 * d, h - 4 * d), radius = 3 * d, step = d)
                drawPixelRoundRect(BACK_EDGE, Offset(3 * d, 3 * d), Size(w - 6 * d, h - 6 * d), radius = 2 * d, step = d)
                // The blue: light over the top half, then three darker bands to the edge's own blue.
                val innerTop = 4 * d
                val innerH = h - 8 * d
                val half = kotlin.math.floor(innerH / d / 2f) * d
                drawRect(BACK_LIGHT, Offset(4 * d, innerTop), Size(w - 8 * d, half))
                drawRect(BACK_MID, Offset(4 * d, innerTop + half), Size(w - 8 * d, 2 * d))
                drawRect(BACK_LOW, Offset(4 * d, innerTop + half + 2 * d), Size(w - 8 * d, 2 * d))
                // The arrow, a pixel of its drawing to a DS pixel, as big as fits.
                val cell = kotlin.math.floor(minOf(w, h) * 0.5f / BACK_ARROW[0].length / d).coerceAtLeast(1f) * d
                val aw = BACK_ARROW[0].length * cell
                val ah = BACK_ARROW.size * cell
                val x0 = kotlin.math.floor((w - aw) / 2f / d) * d
                val y0 = kotlin.math.floor((h - ah) / 2f / d) * d
                BACK_ARROW.forEachIndexed { row, bits ->
                    bits.forEachIndexed { col, c -> if (c == '#') drawRect(BACK_RIM, Offset(x0 + col * cell, y0 + row * cell), Size(cell, cell)) }
                }
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
    )
}

/** The battle bag's return arrow, cream on blue (headless, x 226 - 244, y 164 - 181 of its touch screen). */
private val BACK_ARROW = listOf(
    "...............####",
    "...............####",
    "...............####",
    "...............####",
    "...............####",
    "...............####",
    "....##.........####",
    "....###........####",
    "...####........####",
    "..#####........####",
    ".##################",
    "###################",
    "##################.",
    ".################..",
    "..#####............",
    "...####............",
    "....###............",
    ".....##............",
)
