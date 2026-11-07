package com.pokedaisy.app.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_TARGET_SELECT
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_FEMALE
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_MALE
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.typeLabel
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * BATTLE STYLE > GEN 5: the battle controls laid out and coloured like Black / White's own battle touch screen
 * (measured from the player's cart headless: colours sampled, shapes and proportions taken from the frames), drawn
 * here in our own pieces - nothing of the game's is bundled. The tab is the game's bottom screen, edge to edge
 * (CompanionScreen drops its margin and docks the tab bar under it, [Gen5Dock]): a Poké Ball, red over pale lilac,
 * the dark band across it swelling into the FIGHT! lens, BAG / RUN / POKéMON as round plates rising from the bottom
 * edge (BAG and POKéMON joined to the sides), the party's balls between. FIGHT grows the lens into the dark field of
 * the four hexagon move plates, with the U-turn plate bottom right; POKéMON is the game's party screen (green-rimmed
 * slots, "Choose a POKéMON." box); between turns the closed ball in the dark. Lengths are in the game's own pixels
 * ([G5]): placed by the tab's width and height, sized by the smaller scale, every edge stepped in whole screen
 * pixels of one game pixel ([G5.d]). The D-pad's focus is the game's own key cursor, magenta quarter rings at the corners. Same
 * inputs and taps as [BattleControlsScreen] (which calls this). Its colours are the game's art: no dark-mode twin.
 */
@Composable
internal fun Gen5BattleControls(
    activeMon: MonView?,
    battleInput: BattleInput?,
    battleInputState: Int,
    party: List<MonView>,
    modifier: Modifier,
    showHints: Boolean,
    onShowInfo: () -> Unit,
    onShowSuggestions: () -> Unit,
    foeParty: List<MonView>,
    foe: MonView?,
    active: List<MonView>,
    oneTapMoves: Boolean,
    /** BATTLE INFO off: no INFO / SUGGESTIONS plates. */
    battleInfo: Boolean = true,
    /** RUN was picked: no "Waiting for your turn" band while the battle ends ([BattleControlsScreen]'s). */
    ranAway: Boolean = false,
) {
    val busy = rememberBattleInputBusy(battleInput)
    var choosingMon by remember { mutableStateOf(false) }
    var choosingMove by remember { mutableStateOf(false) }
    LaunchedEffect(battleInputState) {
        if (battleInputState != BATTLE_INPUT_ACTION_SELECT) { choosingMon = false; choosingMove = false }
    }
    val movesKnown = activeMon?.moves?.isNotEmpty() == true
    val canSwitch = battleInput?.canSwitch == true
    val picking = (battleInputState == BATTLE_INPUT_ACTION_SELECT && choosingMon) ||
        (battleInputState == BATTLE_INPUT_PARTY_OPEN && canSwitch)
    val page = when {
        picking -> Gen5Page.PARTY
        battleInputState == BATTLE_INPUT_MOVE_SELECT -> Gen5Page.MOVES
        battleInputState == BATTLE_INPUT_ACTION_SELECT && choosingMove -> Gen5Page.MOVES
        battleInputState == BATTLE_INPUT_ACTION_SELECT && oneTapMoves -> Gen5Page.ONE_TAP
        battleInputState == BATTLE_INPUT_ACTION_SELECT -> Gen5Page.ACTIONS
        battleInputState == BATTLE_INPUT_BAG_OPEN || battleInputState == BATTLE_INPUT_PARTY_OPEN -> Gen5Page.BACK
        else -> Gen5Page.WAIT
    }
    val live = battleInput != null
    val enabled = live && !busy
    // FIGHT grows the band's lens into the move plates' field, as the game does.
    val open by animateFloatAsState(if (page == Gen5Page.MOVES || page == Gen5Page.ONE_TAP) 1f else 0f, tween(200), label = "gen5-open")
    val dark by animateFloatAsState(if (page == Gen5Page.WAIT || page == Gen5Page.BACK) 1f else 0f, tween(240), label = "gen5-dark")
    val partyShown by animateFloatAsState(if (page == Gen5Page.PARTY) 1f else 0f, tween(200), label = "gen5-party")
    var fightLit by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val g = remember(constraints.maxWidth, constraints.maxHeight, density) {
            G5.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        }
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawBallScreen(g, open, if (fightLit) 1f else 0f)
                if (partyShown > 0f) drawPartyScreen(g, partyShown)
                if (dark > 0f) drawClosedBall(g, dark)
            },
        )
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
            modifier = Modifier.fillMaxSize(),
            label = "gen5-page",
        ) { p ->
            Box(Modifier.fillMaxSize()) {
                when (p) {
                    Gen5Page.ACTIONS, Gen5Page.ONE_TAP -> {
                        if (p == Gen5Page.ACTIONS) {
                            if (foeParty.isNotEmpty()) PartyBalls(g, foeParty, centreY = 31f, small = true)
                            // FIGHT!: the lens itself is the button (its hit box the lens's middle).
                            Gen5Button(
                                g, Rect(g.x(48f), g.lensTop(0.5f) , g.w - g.x(48f), g.lensBottom(0.5f)),
                                enabled = enabled, pressesGame = !movesKnown, focusFirst = true,
                                // Black's cursor on FIGHT! frames the panel above the plates, edge to edge (headless).
                                cursor = Rect(g.x(2f), g.y(9f), g.x(253f), g.y(127f)),
                                onPress = { fightLit = it },
                                onClick = { if (movesKnown) choosingMove = true else battleInput?.selectAction(0) },
                            ) {
                                Gen5Label(tr("FIGHT") + "!", G5FightRed, G5FightRed, Color.Black, g.fightFont)
                            }
                            PartyBalls(g, party, centreY = 119f, small = false)
                        } else {
                            MovePlates(g, activeMon, battleInput, busy, showHints, top = 3f, height = 96f)
                            PartyBalls(g, party, centreY = 108f, small = false)
                        }
                        ActionDiscs(
                            g, live, enabled, canSwitch,
                            onPokemon = { if (canSwitch) choosingMon = true else battleInput?.selectAction(2) },
                            pokemonPressesGame = !canSwitch,
                            onBag = { battleInput?.selectAction(1) },
                            onRun = { battleInput?.selectAction(3) },
                        )
                        if (battleInfo) InfoPlates(g, onShowInfo, onShowSuggestions, oneTap = p == Gen5Page.ONE_TAP)
                    }
                    Gen5Page.MOVES -> {
                        val cancel: (() -> Unit)? = if (battleInputState == BATTLE_INPUT_ACTION_SELECT) ({ choosingMove = false }) else null
                        CompanionBackHandler(enabled = enabled) { cancel?.invoke() ?: battleInput?.back() }
                        MovePlates(g, activeMon, battleInput, busy, showHints, top = 5f, height = 112f)
                        PartyBalls(g, party, centreY = 126f, small = false)
                        BackDisc(g, enabled, pressesGame = cancel == null) { cancel?.invoke() ?: battleInput?.back() }
                    }
                    Gen5Page.PARTY -> Gen5PartyPicker(
                        g, party, foe, active, battleInput, busy, showHints,
                        onBack = { if (battleInputState == BATTLE_INPUT_PARTY_OPEN) battleInput?.back() else choosingMon = false },
                    )
                    Gen5Page.WAIT, Gen5Page.BACK -> {
                        val message = when {
                            p == Gen5Page.BACK -> null
                            battleInputState == BATTLE_INPUT_TARGET_SELECT -> tr("Confirming target…")
                            ranAway -> null
                            else -> tr("Waiting for your turn…")
                        }
                        if (message != null) MessageBand(g, message)
                        if (p == Gen5Page.BACK && battleInput != null) {
                            CompanionBackHandler(enabled = enabled) { battleInput.back() }
                            BackDisc(g, enabled, pressesGame = true) { battleInput.back() }
                        }
                        if (battleInfo && p == Gen5Page.WAIT) InfoPlates(g, onShowInfo, onShowSuggestions, oneTap = false)
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

/** The dark strip the tab bar sits on under GEN 5's edge-to-edge screen (CompanionScreen): the party screen's band. */
internal val Gen5Dock = Color(0xFF181818)

private enum class Gen5Page { ACTIONS, ONE_TAP, MOVES, PARTY, WAIT, BACK }

// ---- Geometry -----------------------------------------------------------------------------------------------------

/**
 * The game's bottom screen over the tab: 256 game pixels across, 176 down (its 192 less the status strip it keeps
 * for itself). [x] / [y] place a game coordinate by the tab's own width / height (so the picture fills any shape);
 * [s] sizes things that must stay their shape (the discs, the balls); [d] is one game pixel as whole screen pixels,
 * the step of every edge and the width of every line.
 */
private class G5(val w: Float, val h: Float) {
    val sx = w / 256f
    val sy = h / 176f
    val s = minOf(sx, sy)
    val d = s.roundToInt().coerceAtLeast(1).toFloat()

    fun snap(v: Float) = (v / d).roundToInt() * d
    fun x(v: Float) = snap(v * sx)
    fun y(v: Float) = snap(v * sy)
    fun len(v: Float) = snap(v * s)

    /** FIGHT!'s letters: a font pixel 1.3 game pixels (the game's are 11 rows to the font's 9, and heavier). */
    val fightFont = (s * 1.3f).roundToInt().coerceAtLeast(1)

    /** The band's middle (y 87.5 on the game's screen). */
    val bandY = 71.5f * sy

    /** The lens's half height at [u] (0 = the middle, 1 = either side), in game pixels: the band's 9 rows out to a
     * quarter in from the sides, a steep shoulder, then a long gentle rise to 26.5 in the middle (the game's frame). */
    fun lensHalf(u: Float): Float {
        val dx = u * 128f
        return when {
            dx <= 4f -> 26.5f
            dx <= 64f -> 26.5f - (dx - 4f) / 8f
            dx <= 80f -> 19f - (dx - 64f) / 16f * 10f
            else -> 9f
        }
    }

    /** The move plates' field (the lens grown): its top and bottom edge at [u], in game pixels of [h]. */
    fun fieldTop(u: Float) = -4f + 12f * u * u
    fun fieldBottom(u: Float) = 135f - 22f * u * u

    fun lensTop(u: Float) = snap(bandY - lensHalf(u) * sy)
    fun lensBottom(u: Float) = snap(bandY + lensHalf(u) * sy)

    companion object {
        fun of(w: Float, h: Float) = G5(w, h)
    }
}

/** A shape filled row by row ([step] tall rows, edges snapped to [step]): [span] gives a row's left / right edge at
 * its middle (null = nothing on that row). Pixel art at any size, no smooth edge anywhere. */
private fun rowsPath(top: Float, bottom: Float, step: Float, span: (Float) -> Pair<Float, Float>?): Path = Path().apply {
    var y = top
    while (y < bottom - 0.01f) {
        val y2 = minOf(y + step, bottom)
        span((y + y2) / 2f)?.let { (l, r) ->
            val ls = (l / step).roundToInt() * step
            val rs = (r / step).roundToInt() * step
            if (rs > ls) addRect(Rect(ls, y, rs, y2))
        }
        y = y2
    }
}

/** Piecewise linear through [pts] (x ascending). */
private fun profile(pts: List<Pair<Float, Float>>, t: Float): Float {
    if (t <= pts.first().first) return pts.first().second
    for (i in 1 until pts.size) {
        val (x0, y0) = pts[i - 1]
        val (x1, y1) = pts[i]
        if (t <= x1) return y0 + (y1 - y0) * (t - x0) / (x1 - x0)
    }
    return pts.last().second
}

/** The round plates' outline (BAG, RUN, POKéMON, the U-turn): measured off the game's, a 16-sided near circle with a
 * short flat top - (depth from the top, half width), both in radii. */
private val DISC = listOf(0f to 0.107f, 0.32f to 0.72f, 0.93f to 1f, 1.07f to 1f, 1.68f to 0.72f, 2f to 0.107f)

/** The move plates' outline: a shallow roof, then the sides out to a point halfway down - (share of the height,
 * share of the half width). */
private val PLATE = listOf(0f to 0.143f, 0.104f to 0.825f, 0.5f to 1f, 0.896f to 0.825f, 1f to 0.143f)

/** A disc of radius [r] round ([cx], [cy]); [bar] -1 / +1 joins it to the left / right edge (0..[w]) from 0.63 radii
 * down, like the game's BAG and POKéMON. */
private fun discPath(cx: Float, cy: Float, r: Float, step: Float, bar: Int, w: Float, barTop: Float, bottom: Float): Path =
    rowsPath(cy - r, bottom, step) { y ->
        val hw = profile(DISC, (y - (cy - r)) / r) * r
        var l = cx - hw
        var rr = cx + hw
        if (y >= barTop) { if (bar < 0) l = 0f; if (bar > 0) rr = w }
        l to rr
    }

private fun platePath(l: Float, t: Float, w: Float, h: Float, step: Float): Path =
    rowsPath(t, t + h, step) { y ->
        val hw = profile(PLATE, (y - t) / h) * w / 2
        (l + w / 2 - hw) to (l + w / 2 + hw)
    }

/** The party slots' outline: a box with its corners cut [c] at 45°. */
private fun slotPath(l: Float, t: Float, w: Float, h: Float, c: Float, step: Float): Path =
    rowsPath(t, t + h, step) { y ->
        val k = minOf(y - t, t + h - y)
        val cut = (c - k).coerceAtLeast(0f)
        (l + cut) to (l + w - cut)
    }

// ---- Backdrops ----------------------------------------------------------------------------------------------------

/**
 * The ball: red above (two faint rings toward the top corners, round the ball's middle at the bottom edge), pale
 * lilac below (lilac toward the bottom corners), the dark band between swelling into FIGHT!'s lens. [open] (0..1)
 * grows the lens into the move plates' warm grey field; [lit] lights the lens while FIGHT! is held.
 */
private fun DrawScope.drawBallScreen(g: G5, open: Float, lit: Float) {
    val d = g.d
    val w = size.width
    val h = size.height
    drawRect(Red)
    // The rings: circles round the ball's middle at the bottom edge (game: centre (128, 192), radii 193 / 197).
    fun ring(r: Float, c: Color) {
        val cx = w / 2
        val cy = 176f * g.sy
        val rx = r * g.sx
        val ry = r * g.sy
        var x = 0f
        while (x < w) {
            val u = ((x + d / 2 - cx) / rx)
            if (kotlin.math.abs(u) < 1f) {
                val yb = g.snap(cy - ry * sqrt(1 - u * u))
                if (yb > 0f) drawRect(c, Offset(x, 0f), Size(d, yb))
            } else drawRect(c, Offset(x, 0f), Size(d, h / 2))
            x += d
        }
    }
    ring(193f, RedRing)
    ring(197f, RedRingOuter)
    // Pale below the band, lilac toward the bottom corners (circles round a point above the screen's middle).
    drawRect(Pale, Offset(0f, g.bandY), Size(w, h - g.bandY))
    fun lilac(r: Float, c: Color) {
        val cx = w / 2
        val cy = -30f * g.sy
        val rx = r * g.sx
        val ry = r * g.sy
        var x = 0f
        while (x < w) {
            val u = ((x + d / 2 - cx) / rx)
            val yb = if (kotlin.math.abs(u) < 1f) g.snap(cy + ry * sqrt(1 - u * u)) else g.bandY
            if (yb < h) drawRect(c, Offset(x, maxOf(yb, g.bandY)), Size(d, h - maxOf(yb, g.bandY)))
            x += d
        }
    }
    lilac(189f, PaleLilac)
    lilac(192f, PaleLilacDeep)
    // The band and its lens, column by column: top and bottom edges, then the layers inward.
    fun edges(x: Float): Pair<Float, Float> {
        val u = kotlin.math.abs(x + d / 2 - w / 2) / (w / 2)
        val lt = g.bandY - g.lensHalf(u) * g.sy
        val lb = g.bandY + g.lensHalf(u) * g.sy
        val ft = g.fieldTop(u) * g.sy
        val fb = g.fieldBottom(u) * g.sy
        return g.snap(lt + (ft - lt) * open) to g.snap(lb + (fb - lb) * open)
    }
    fun layer(inTop: Float, inBottom: Float, c: Color) {
        var x = 0f
        while (x < w) {
            val (t, b) = edges(x)
            val top = t + inTop * d
            val bot = b - inBottom * d
            if (bot > top) drawRect(c, Offset(x, top), Size(d, bot - top))
            x += d
        }
    }
    // Shade: red darkening onto the band above, the pale greying under it.
    layer(-3f, 0f, lerp(BandShadeHi, FieldShadeHi, open))
    layer(-2f, 0f, lerp(BandShade, FieldShade, open))
    layer(0f, -4f, lerp(PaleShade3, FieldPaleShade2, open))
    layer(0f, -3f, lerp(PaleShade2, FieldPaleShade2, open))
    layer(0f, -1f, lerp(PaleShade1, FieldPaleShade1, open))
    layer(0f, 0f, lerp(Color.Black, FieldOutline, open))
    layer(3f, 3f, lerp(BandEdge, FieldEdge, open))
    layer(5f, 5f, lerp(BandFill, FieldFill, open))
    if (lit > 0f) layer(5f, 5f, Color.White.copy(alpha = 0.12f * lit))
}

/** The battle's party screen: the ball again, darkened - deep red, a wide dark band, grey lilac under it. */
private fun DrawScope.drawPartyScreen(g: G5, alpha: Float) {
    val w = size.width
    val h = size.height
    val d = g.d
    val bandTop = g.snap(h * 0.34f)
    val bandBottom = g.snap(h * 0.62f)
    drawRect(PartyRed, alpha = alpha)
    drawRect(PartyRedDark, size = Size(w, 2 * d), alpha = alpha)
    drawRect(PartyRedDeep, Offset(0f, bandTop - 5 * d), Size(w, 3 * d), alpha = alpha)
    drawRect(PartyRedDeeper, Offset(0f, bandTop - 2 * d), Size(w, 2 * d), alpha = alpha)
    drawRect(PartyBand, Offset(0f, bandTop), Size(w, bandBottom - bandTop), alpha = alpha)
    drawRect(PartyBandEdge, Offset(0f, bandTop), Size(w, 2 * d), alpha = alpha)
    drawRect(PartyBandEdge, Offset(0f, bandBottom - 2 * d), Size(w, 2 * d), alpha = alpha)
    drawRect(Brush.verticalGradient(listOf(PartyLilac, PartyLilacDeep), bandBottom, h), Offset(0f, bandBottom), Size(w, h - bandBottom), alpha = alpha)
}

/**
 * Between turns: the dark screen and the ball shut - dark red over grey, each half lighter inside its rim, the gap
 * between them the band's lens again (a sliver at the sides, open in the middle, the dark showing through).
 */
/**
 * BATTLE STYLE > GEN 5's background behind INFO / SUGGESTIONS (and the tab bar): Black's dark screen with its closed Poké
 * Ball, as its controls show between turns, so leaving the controls for a pane doesn't drop back to HeartGold's mint.
 */
@Composable
internal fun Gen5BattleBackdrop(modifier: Modifier = Modifier) {
    // Already the night's colours: no DARK MODE scrim, like the controls themselves.
    androidx.compose.foundation.Canvas(modifier) { drawClosedBall(G5.of(size.width, size.height), 1f) }
}

private fun DrawScope.drawClosedBall(g: G5, alpha: Float) {
    val d = g.d
    drawRect(Night, alpha = alpha)
    val r = g.len(70f)
    val cx = g.snap(size.width / 2)
    val cy = g.snap(size.height * 0.5f)
    fun disc(rad: Float, c: Color, from: Float, to: Float) =
        drawPath(rowsPath(maxOf(from, cy - rad), minOf(to, cy + rad), d) { y ->
            val dy = (y - cy) / rad
            if (kotlin.math.abs(dy) >= 1f) null else (rad * sqrt(1 - dy * dy)).let { (cx - it) to (cx + it) }
        }, c, alpha = alpha)
    disc(r, BallOutline, cy - r, cy + r)
    disc(r - d, BallTopRim, cy - r, cy)
    disc(r - 5 * d, BallTop, cy - r, cy)
    disc(r - d, BallBottomRim, cy, cy + r)
    disc(r - 5 * d, BallBottom, cy, cy + r)
    // The gap, column by column: 1.5 game pixels each way at the rim, 8.5 in the middle, outlined.
    var x = cx - r
    while (x < cx + r) {
        val u = kotlin.math.abs(x + d / 2 - cx) / r
        val k = (1f - u * u).coerceAtLeast(0f)
        val half = g.snap((1.5f + 7f * k * k) * g.s)
        drawRect(BallOutline, Offset(x, cy - half - d), Size(d, 2 * half + 2 * d), alpha = alpha)
        drawRect(Night, Offset(x, cy - half), Size(d, 2 * half), alpha = alpha)
        x += d
    }
}

// ---- The plates ---------------------------------------------------------------------------------------------------

/**
 * A pressable area of the screen ([rect], in the tab's pixels) with the game's key cursor - a magenta quarter ring at
 * each corner, white outside it ([drawUnovaCursor]) - on D-pad focus. [onPress] hears the finger go down / up (FIGHT! lights its lens). Clicks like [PlatinumButton]:
 * [pressesGame] leaves the sound to the game's own select.
 */
@Composable
private fun Gen5Button(
    g: G5,
    rect: Rect,
    enabled: Boolean,
    pressesGame: Boolean,
    focusFirst: Boolean = false,
    /** Where the game's cursor sits, in the tab's pixels, when it isn't round [rect] (FIGHT!'s frames the whole panel). */
    cursor: Rect? = null,
    onPress: (Boolean) -> Unit = {},
    draw: (DrawScope.(pressed: Boolean) -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val sound = LocalClickSound.current
    val gameSilent = LocalGameSilent.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(pressed) { onPress(pressed) }
    // Black / White's own key cursor, breathing out and back like the game's.
    val pulse = if (focused) rememberDsCursorPulse(2) else 0f
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .focusStart(focusFirst)
            .drawWithContent {
                draw?.invoke(this, pressed)
                drawContent()
                if (focused) {
                    val c = cursor ?: rect
                    translate(c.left - rect.left, c.top - rect.top) { drawUnovaCursor(g.d, pulse, c.size) }
                }
            }
            .then(
                if (enabled) Modifier.clickable(interactionSource = interaction, indication = null) {
                    onClick()
                    if (!pressesGame || gameSilent()) sound()
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** The plates' face: charcoal dotted lighter (a 2 x 2 dot every 5 game pixels, a softer pixel round it), darker in
 * steps toward the bottom. [dim] is the game's unavailable plate (BAG in the Battle Subway), near black. */
private fun DrawScope.drawFace(path: Path, top: Float, bottom: Float, d: Float, dim: Boolean, lit: Boolean) {
    val bands = if (dim) DimFace else Face
    val n = bands.size
    val bh = (bottom - top) / n
    clipPath(path) {
        for ((i, c) in bands.withIndex()) drawRect(c, Offset(0f, g5snap(top + i * bh, d)), Size(size.width, g5snap(bh, d) + d))
        var y = g5snap(top, d) + d
        var row = 0
        while (y < bottom) {
            val band = (((y - top) / bh).toInt()).coerceIn(0, n - 1)
            val dot = lerp(bands[band], Color.White, 0.07f)
            val soft = lerp(bands[band], Color.White, 0.035f)
            var x = d
            while (x < size.width) {
                drawRect(soft, Offset(x - d, y), Size(4 * d, 2 * d))
                drawRect(soft, Offset(x, y - d), Size(2 * d, 4 * d))
                drawRect(dot, Offset(x, y), Size(2 * d, 2 * d))
                x += 5 * d
            }
            y += 5 * d
            row++
        }
        if (lit) drawRect(Color.White.copy(alpha = 0.14f))
    }
}

private fun g5snap(v: Float, d: Float) = (v / d).roundToInt() * d

/** A round plate's rim, outside in: grey, pale, three white, a dark line (the game's 1 / 1 / 3 / 1); [dim] all greys. */
private val RimBands get() = listOf(RimOuterGrey to 1f, RimPale to 1f, RimWhite to 3f, RimInner to 1f)
private val DimRimBands get() = listOf(DimRim1 to 1f, DimRim2 to 1f, DimRim3 to 3f, DimRim1 to 1f)

/**
 * BAG, RUN, POKéMON: round plates rising from the bottom edge where the game has them (BAG and POKéMON joined to the
 * sides, RUN lower in the middle), their words in the game's colours, lit on top.
 */
@Composable
private fun ActionDiscs(
    g: G5,
    live: Boolean,
    enabled: Boolean,
    canSwitch: Boolean,
    onPokemon: () -> Unit,
    pokemonPressesGame: Boolean,
    onBag: () -> Unit,
    onRun: () -> Unit,
) {
    Disc(g, cxDs = 39.5f, cyDs = 154f, bar = -1, label = tr("BAG"), colors = BagColors, available = live, enabled = enabled, pressesGame = true, onClick = onBag)
    Disc(g, cxDs = 127.5f, cyDs = 170f, bar = 0, label = tr("RUN"), colors = RunColors, available = live, enabled = enabled, pressesGame = true, cursorTop = 138f, onClick = onRun)
    Disc(
        g, cxDs = 215.5f, cyDs = 154f, bar = 1, label = tr("POKéMON"), colors = MonColors,
        available = live, enabled = enabled, pressesGame = pokemonPressesGame, onClick = onPokemon,
    )
}

private class LabelColors(val top: Color, val bottom: Color)

private val BagColors = LabelColors(Color(0xFFFBD25A), Color(0xFFFBB228))
private val RunColors = LabelColors(Color(0xFFA2DBFB), Color(0xFF28BAF3))
private val MonColors = LabelColors(Color(0xFF71FB51), Color(0xFF51CB28))
private val InfoColors = LabelColors(Color(0xFFE3CBFB), Color(0xFFB28AF3))
private val SuggestColors = LabelColors(Color(0xFFFBEB8A), Color(0xFFFBC330))

/** One round plate: its centre at ([cxDs], [cyDs]) on the game's screen, 38 game pixels round; [bar] joins a side. */
@Composable
private fun Disc(
    g: G5,
    cxDs: Float,
    cyDs: Float,
    bar: Int,
    label: String?,
    colors: LabelColors?,
    available: Boolean,
    enabled: Boolean,
    pressesGame: Boolean,
    radius: Float = 38f,
    /** The game's cursor box's top (this screen's game pixels): Black's sits 24 above a side plate's centre, RUN's 32. */
    cursorTop: Float = cyDs - 24f,
    content: (@Composable BoxScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val d = g.d
    val r = g.len(radius)
    // Bar plates hug their edge; the middle one is placed by the width.
    val cx = when (bar) { -1 -> g.len(cxDs); 1 -> g.w - g.len(256f - cxDs); else -> g.x(cxDs) }
    // Placed by its top edge (how much of it shows above the bottom is the game's), sized to stay round.
    val top = g.y(cyDs - radius)
    val cy = top + r
    val left = if (bar < 0) 0f else cx - r
    val right = if (bar > 0) g.w else cx + r
    val rect = Rect(left, top, right, g.h)
    val barTop = top + g.snap(0.63f * r)
    Gen5Button(
        g, rect, enabled = enabled && available, pressesGame = pressesGame,
        // The game's cursor box: 36.5 left of the centre to 37.5 right, down to 2 above the screen's bottom.
        cursor = Rect(cx - g.len(radius - 1.5f), g.y(cursorTop), cx + g.len(radius - 0.5f), g.y(174f)),
        draw = { pressed ->
            val bands = if (available) RimBands else DimRimBands
            // Drawn in the button's own box: shift the screen's coordinates by its corner.
            translate(-rect.left, -rect.top) {
                var inset = 0f
                for ((c, n) in bands) {
                    drawPath(discPath(cx, cy, r - inset, d, bar, g.w, barTop + inset, g.h), c)
                    inset += n * d
                }
                val face = discPath(cx, cy, r - inset, d, bar, g.w, barTop + inset, g.h)
                drawFace(face, top + inset, g.h, d, dim = !available, lit = pressed)
            }
        },
        onClick = onClick,
    ) {
        // The words centred where the game's are: 0.92 radii under the top (RUN, lower, 0.76), on the disc's middle.
        val labelY = (if (bar == 0) 0.76f else 0.92f) * r
        val dx = cx - rect.left
        Box(
            Modifier.fillMaxSize().layout { measurable, constraints ->
                val p = measurable.measure(androidx.compose.ui.unit.Constraints(maxWidth = (r * 1.85f).roundToInt()))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    p.place((dx - p.width / 2f).roundToInt(), (labelY - p.height / 2f).roundToInt())
                }
            },
        ) {
            if (content != null) Box(content = content)
            else if (label != null && colors != null) {
                val c = if (available) colors else LabelColors(lerp(colors.top, Color.Black, 0.5f), lerp(colors.bottom, Color.Black, 0.5f))
                Gen5Label(label, c.top, c.bottom, LabelOutline, d.roundToInt(), shrink = true)
            }
        }
    }
}

/** The way back: the round plate bottom right with the game's blue U-turn. */
@Composable
private fun BackDisc(g: G5, enabled: Boolean, pressesGame: Boolean, radius: Float = 38f, cyDs: Float = 154f, onClick: () -> Unit) {
    Disc(
        g, cxDs = 256f - 40.5f * radius / 38f, cyDs = cyDs, bar = 1, label = null, colors = null, available = true,
        enabled = enabled, pressesGame = pressesGame, radius = radius,
        content = {
            val size = with(LocalDensity.current) { (g.d * 36f * radius / 38f).toDp() }
            PixelArt(UTurn, mapOf('B' to UTurnBlue, 'L' to UTurnLight, 'D' to UTurnDeep, 'K' to UTurnOutline), Modifier.size(size))
        },
        onClick = onClick,
    )
}

/** INFO and SUGGESTIONS: small plates in the red corners above the band (ours; the game has its clock there). */
@Composable
private fun InfoPlates(g: G5, onInfo: () -> Unit, onSuggestions: () -> Unit, oneTap: Boolean) {
    if (oneTap) return
    SmallPlate(g, Rect(g.len(3f), g.len(3f), g.len(3f) + g.x(70f), g.len(3f) + g.len(21f)), tr("INFO"), InfoColors, onInfo)
    SmallPlate(g, Rect(g.w - g.len(3f) - g.x(110f), g.len(3f), g.w - g.len(3f), g.len(3f) + g.len(21f)), tr("SUGGESTIONS"), SuggestColors, onSuggestions)
}

@Composable
private fun SmallPlate(g: G5, rect: Rect, label: String, colors: LabelColors, onClick: () -> Unit) {
    val d = g.d
    Gen5Button(
        g, rect, enabled = true, pressesGame = false,
        draw = { pressed ->
            var inset = 0f
            for ((c, n) in listOf(RimOuterGrey to 1f, RimWhite to 2f, RimInner to 1f)) {
                drawPath(platePath(inset, inset, size.width - 2 * inset, size.height - 2 * inset, d), c)
                inset += n * d
            }
            val face = platePath(inset, inset, size.width - 2 * inset, size.height - 2 * inset, d)
            drawFace(face, inset, size.height - inset, d, dim = false, lit = pressed)
        },
        onClick = onClick,
    ) { Box(Modifier.padding(horizontal = with(LocalDensity.current) { (rect.height * 0.5f).toDp() })) { Gen5Label(label, colors.top, colors.bottom, LabelOutline, d.roundToInt(), shrink = true) } }
}

/** The party's balls in a row of six: a ball per member (greyed once fainted), a dot per empty slot - yours from the
 * left ([small] false, 13 game pixels each), the foe's from the right in the red (7 each). */
@Composable
private fun PartyBalls(g: G5, party: List<MonView>, centreY: Float, small: Boolean) {
    val density = LocalDensity.current
    val cell = if (small) 8f else 16f
    val ball = if (small) BallSmall else BallBig
    val n = ball.size
    val px = g.d * (if (small) 1f else 1f)
    val total = 6 * cell * g.s
    val left = g.snap(g.w / 2 - total / 2 + if (small) 3f * g.s else 0f)
    val top = g.snap(g.y(centreY) - n * px / 2)
    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { total.toDp() }, with(density) { (n * px).toDp() })
            .drawBehind {
                for (slot in 0 until 6) {
                    // The foe's fill from the right: its empty dots come first.
                    val i = if (small) slot - (6 - party.size.coerceAtMost(6)) else slot
                    val mon = party.getOrNull(i).takeIf { i >= 0 }
                    val x0 = g5snap(slot * cell * g.s, px)
                    if (mon == null) {
                        val dot = if (small) 2 else 4
                        drawRect(BallLine, Offset(x0 + g5snap((n - dot) / 2f * px, px), g5snap((n - dot) / 2f * px, px)), Size(dot * px, dot * px))
                    } else {
                        val pal = if (mon.hp > 0 && !mon.isEgg) BallColors else BallColorsFainted
                        ball.forEachIndexed { row, bits ->
                            bits.forEachIndexed { col, ch ->
                                pal[ch]?.let { drawRect(it, Offset(x0 + col * px, row * px), Size(px, px)) }
                            }
                        }
                    }
                }
            },
    )
}

/** The four move plates, two by two from game row [top] (the field's), each its type's colour round the charcoal:
 * the name, the type and PP, then (ours) the power and, with BATTLE HINTS, the verdict. */
@Composable
private fun MovePlates(g: G5, activeMon: MonView?, battleInput: BattleInput?, busy: Boolean, showHints: Boolean, top: Float, height: Float) {
    val plateW = (g.w - 3 * g.len(1.5f)) / 2
    val rowH = (g.y(top + height) - g.y(top)) / 2
    for (i in 0 until 4) {
        val col = i % 2
        val row = i / 2
        val l = g.snap(g.len(1.5f) + col * (plateW + g.len(1.5f)))
        val t = g.snap(g.y(top) + row * rowH)
        MovePlate(g, Rect(l, t, l + g.snap(plateW), t + g.snap(rowH)), activeMon?.moves?.getOrNull(i), battleInput, i, busy, showHints)
    }
}

@Composable
private fun MovePlate(g: G5, rect: Rect, mv: MoveView?, battleInput: BattleInput?, index: Int, busy: Boolean, showHints: Boolean) {
    val d = g.d
    val usable = mv != null && mv.pp > 0
    val type = mv?.let { AppColors.typeColors[it.type] } ?: EmptyRim
    val m = g5Metrics(g, 1f)
    val small = g5Metrics(g, 0.75f)
    Box(Modifier.fillMaxSize().alpha(if (mv == null || usable) 1f else 0.5f)) {
        Gen5Button(
            g, rect, enabled = usable && battleInput != null && !busy, pressesGame = true, focusFirst = index == 0,
            draw = { pressed ->
                val w = size.width
                val h = size.height
                drawPath(platePath(0f, 0f, w, h, d), Color.Black)
                // The type's colour, lit over the top half and a shade darker under it (the game's bevel).
                val rim = platePath(d, d, w - 2 * d, h - 2 * d, d)
                clipRect(bottom = h / 2) { drawPath(rim, type) }
                clipRect(top = h / 2) { drawPath(rim, lerp(type, Color.Black, 0.22f)) }
                val face = platePath(4 * d, 3 * d, w - 8 * d, h - 6 * d, d)
                drawFace(face, 3 * d, h - 3 * d, d, dim = false, lit = pressed)
            },
            onClick = { battleInput?.selectMove(index) },
        ) {
            if (mv == null) {
                GbaText("-", EmptyRim, Color.Transparent, m)
            } else Column(
                Modifier.fillMaxSize().padding(horizontal = with(LocalDensity.current) { (rect.width * 0.1f).toDp() }),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FitText(g, mv.name, PlateText, PlateTextShadow, 1f)
                Spacer(Modifier.size(with(LocalDensity.current) { (d * 2).toDp() }))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeTag(mv.type, small, d)
                    Spacer(Modifier.width(with(LocalDensity.current) { (d * 4).toDp() }))
                    GbaText(tr("PP {0}", mv.pp), PlateText, PlateTextShadow, m)
                }
                Spacer(Modifier.size(with(LocalDensity.current) { (d * 2).toDp() }))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(with(LocalDensity.current) { (d * 3).toDp() })) {
                    GbaText(tr("PWR {0}", powerLabel(mv.power)), PlateMuted, PlateTextShadow, small)
                    if (showHints) mv.vs.forEach { v -> MultiplierChip(v.label, v.pct, fontSize = 16.sp) }
                }
            }
        }
    }
}

/** The game's type tag: the type's colour in a darker line, its name white over a dark shadow. */
@Composable
private fun TypeTag(type: String, m: GbaTextMetrics, d: Float) {
    val c = AppColors.typeColors[type] ?: EmptyRim
    val density = LocalDensity.current
    Box(
        Modifier.drawBehind {
            drawRect(lerp(c, Color.Black, 0.45f))
            drawRect(c, Offset(d, d), Size(size.width - 2 * d, size.height - 2 * d))
        }.padding(horizontal = with(density) { (d * 3).toDp() }, vertical = with(density) { d.toDp() }),
    ) { GbaText(typeLabel(type), Color.White, TagShadow, m) }
}

/** A font pixel [k] game pixels across ([G5.d] screen pixels each). */
@Composable
private fun g5Metrics(g: G5, k: Float): GbaTextMetrics {
    val density = LocalDensity.current
    return remember(density, g.d, k) {
        with(density) {
            val u = (20.dp.toPx() / 16f).roundToInt().coerceAtLeast(2)
            val per = (g.d * k).roundToInt().coerceAtLeast(1)
            val fontPx = 16f * per
            GbaTextMetrics(u, u.toDp(), fontPx.toSp(), per.toFloat(), fontPx.toDp(), (fontPx * 1.25f).toDp())
        }
    }
}

/** Not your turn: the game's message band along the bottom (dark, a line along its top), the words in white. */
@Composable
private fun MessageBand(g: G5, text: String) {
    val d = g.d
    val m = g5Metrics(g, 1f)
    val density = LocalDensity.current
    val bandH = g.len(30f)
    Box(
        Modifier.fillMaxSize().padding(top = with(density) { (g.h - bandH).toDp() })
            .drawBehind {
                drawRect(MsgBand)
                drawRect(MsgLine, size = Size(size.width, d))
                drawRect(MsgLineShadow, Offset(0f, d), Size(size.width, d))
            }
            .padding(start = with(density) { (g.len(8f)).toDp() }),
        contentAlignment = Alignment.CenterStart,
    ) { GbaText(text, Color.White, MsgShadow, m) }
}

// ---- The party screen ---------------------------------------------------------------------------------------------

/**
 * The game's battle party: six slots in two columns, each a charcoal plate in a green rim (yellow / red as its HP
 * drops, grey once fainted, dark when empty) - the icon, the name, a pale strip with the HP bar, the level and HP
 * under it - then "Choose a POKéMON." in the game's white box and the U-turn plate. The recommendation (BATTLE
 * HINTS) gets BEST and the cursor, the one battling OUT. A tap switches, like [PartyPicker].
 */
@Composable
private fun Gen5PartyPicker(
    g: G5,
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
    val d = g.d
    val m = g5Metrics(g, 1f)
    val gap = g.len(2f)
    val bottom = g.len(34f)
    val slotW = g.snap((g.w - 3 * gap) / 2)
    val slotH = g.snap((g.h - bottom - 4 * gap) / 3)
    for (i in 0 until 6) {
        val l = g.snap(gap + (i % 2) * (slotW + gap))
        val t = g.snap(gap + (i / 2) * (slotH + gap))
        PartySlot(g, Rect(l, t, l + slotW, t + slotH), party.getOrNull(i), i == cursor, best, active, enabled) { mon ->
            battleInput?.switchTo(mon.personality)
        }
    }
    // "Choose a POKéMON." in the game's white box, left of the U-turn.
    val density = LocalDensity.current
    val boxH = g.len(24f)
    val boxL = g.len(6f)
    val boxR = g.w - g.len(70f)
    Box(
        Modifier.offset { IntOffset(boxL.roundToInt(), (g.h - g.len(5f) - boxH).roundToInt()) }
            .size(with(density) { (boxR - boxL).toDp() }, with(density) { boxH.toDp() })
            .drawBehind {
                drawRect(Color.Black)
                drawRect(MsgBoxLine, Offset(d, d), Size(size.width - 2 * d, size.height - 2 * d))
                drawRect(MsgBoxFill, Offset(2 * d, 2 * d), Size(size.width - 4 * d, size.height - 4 * d))
            }
            .padding(start = with(density) { (d * 6).toDp() }),
        contentAlignment = Alignment.CenterStart,
    ) { GbaText(tr("Choose a POKéMON."), MsgBoxText, MsgBoxShadow, m) }
    BackDisc(g, !busy, pressesGame = false, radius = 30f, cyDs = 170f, onClick = onBack)
}

@Composable
private fun PartySlot(
    g: G5, rect: Rect, mon: MonView?, selected: Boolean, best: MonView?, active: List<MonView>, enabled: Boolean, onPick: (MonView) -> Unit,
) {
    val d = g.d
    val m = g5Metrics(g, 1f)
    val density = LocalDensity.current
    val fainted = mon != null && (mon.hp <= 0 && !mon.isEgg)
    val ratio = if (mon != null && mon.maxHp > 0) mon.hp.toFloat() / mon.maxHp else 0f
    val rim = when {
        mon == null -> EmptySlotRim
        fainted -> FaintRim
        else -> GreenRim
    }
    val canPick = mon != null && canSendIn(mon, active)
    Gen5Button(
        g, rect, enabled = enabled && canPick, pressesGame = true, focusFirst = selected,
        draw = { pressed ->
            val w = size.width
            val h = size.height
            val c = 5 * d
            drawPath(slotPath(0f, 0f, w, h, c, d), SlotOutline)
            drawPath(slotPath(d, d, w - 2 * d, h - 2 * d, c, d), rim[0])
            clipRect(top = h * 0.55f) { drawPath(slotPath(d, d, w - 2 * d, h - 2 * d, c, d), rim[1]) }
            drawPath(slotPath(3 * d, 3 * d, w - 6 * d, h - 6 * d, c - d, d), rim[2])
            val face = slotPath(4 * d, 4 * d, w - 8 * d, h - 8 * d, c - 2 * d, d)
            if (mon == null) {
                drawPath(face, EmptySlotFace)
                drawRect(EmptySlotStrip, Offset(5 * d, g5snap(h * 0.55f, d)), Size(w - 10 * d, 2 * d))
            } else {
                drawFace(face, 4 * d, h - 4 * d, d, dim = false, lit = pressed || selected)
                // The pale strip the HP bar sits in.
                val sy = g5snap(h * 0.52f, d)
                drawRect(StripLine, Offset(5 * d, sy), Size(w - 10 * d, 7 * d))
                drawRect(Strip, Offset(5 * d, sy + d), Size(w - 10 * d, 5 * d))
                if (!mon.isEgg) {
                    val barL = g5snap(w * 0.46f, d)
                    val barR = w - 10 * d
                    drawRect(BarFrame, Offset(barL, sy + 2 * d), Size(barR - barL, 3 * d))
                    val fill = when { ratio > 0.5f -> HpGreen; ratio > 0.2f -> HpYellow; else -> HpRed }
                    drawRect(fill, Offset(barL + d, sy + 3 * d), Size(g5snap((barR - barL - 2 * d) * ratio, d), d))
                }
            }
            if (fainted) drawPath(face, Color.Black.copy(alpha = 0.35f))
        },
        onClick = { mon?.let(onPick) },
    ) {
        if (mon != null) SlotWords(g, rect, mon, best, active)
    }
}

/** A slot's icon and words, where the game puts them: the name beside the icon, HP in the strip, level and HP under. */
@Composable
private fun SlotWords(g: G5, rect: Rect, mon: MonView, best: MonView?, active: List<MonView>) {
    val d = g.d
    val m = g5Metrics(g, 1f)
    val density = LocalDensity.current
    fun dp(px: Float) = with(density) { px.toDp() }
    val pad = dp(d * 7)
    val iconSize = rect.height * 0.55f
    Box(Modifier.fillMaxSize()) {
        SpeciesIcon(mon.iconAsset, size = dp(iconSize), modifier = Modifier.offset(x = dp(d * 2), y = dp(-d)))
        Row(
            Modifier.align(Alignment.TopStart).padding(start = dp(iconSize + d * 3), top = pad, end = pad).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { FitText(g, if (mon.isEgg) tr("EGG") else mon.name, SlotText, SlotTextShadow, 1f) }
            when (mon.genderSymbol) {
                GENDER_SYMBOL_MALE -> GbaText("♂", MaleBlue, Color.Transparent, m)
                GENDER_SYMBOL_FEMALE -> GbaText("♀", FemaleRed, Color.Transparent, m)
            }
        }
        // Ours: BEST (BATTLE HINTS' pick) / OUT on the rim's top right.
        val tag = when {
            mon == best -> tr("BEST") to SuggestGold
            isOut(mon, active) -> tr("OUT") to VerdictGrey
            else -> null
        }
        tag?.let { (text, color) -> Box(Modifier.align(Alignment.TopEnd).offset(x = dp(-d * 10), y = dp(-d))) { VerdictPill(text, color) } }
        if (!mon.isEgg) {
            // "HP" in green just left of the bar, on the strip.
            val stripY = g5snap(rect.height * 0.52f, d)
            val small = g5Metrics(g, 0.6f)
            Box(
                Modifier.offset { IntOffset((g5snap(rect.width * 0.46f, d) - 11 * d).roundToInt(), (stripY - d).roundToInt()) },
            ) { GbaText(tr("HP"), HpLabel, HpLabelShadow, small, bold = true) }
            // The level and HP under the strip: the line's top just under it (the font's capitals sit low in the line).
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = pad, end = pad, top = dp(stripY + 5 * d)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GbaText(tr("Lv.{0}", mon.level), SlotText, SlotTextShadow, m, bold = true)
                if (mon.status.isNotEmpty()) { Spacer(Modifier.width(pad)); StatusBadge(mon.status) }
                Spacer(Modifier.weight(1f))
                GbaText("${mon.hp}/${mon.maxHp}", SlotText, SlotTextShadow, m, bold = true)
            }
        }
    }
}

// ---- Text ---------------------------------------------------------------------------------------------------------

/** One line at a font pixel of [k] game pixels, a screen pixel smaller at a time until it fits the width (never
 * narrowed: the measurers disagree on narrowed glyphs), then cut. */
@Composable
private fun FitText(g: G5, text: String, color: Color, shadow: Color, k: Float) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val font = pixelFontFamily()
    BoxWithConstraints {
        val maxW = constraints.maxWidth
        val start = (g.d * k).roundToInt().coerceAtLeast(1)
        val per = remember(text, maxW, start, density, font) {
            (start downTo 1).firstOrNull { p ->
                val style = TextStyle(fontFamily = font, fontSize = with(density) { (16f * p).toSp() })
                measurer.measure(text, style, maxLines = 1, softWrap = false).size.width + p <= maxW
            } ?: 1
        }
        val m = remember(per, density) {
            with(density) {
                val u = (20.dp.toPx() / 16f).roundToInt().coerceAtLeast(2)
                val fontPx = 16f * per
                GbaTextMetrics(u, u.toDp(), fontPx.toSp(), per.toFloat(), fontPx.toDp(), (fontPx * 1.25f).toDp())
            }
        }
        GbaText(text, color, shadow, m, scaleX = 1f)
    }
}

/**
 * A label the way the game letters its plates: the pixel font in pixel bold (each glyph twice, a font pixel apart),
 * outlined a font pixel all round in [outline], [top] over the upper half of the letters and [bottom] under it (the
 * game's two-tone words; FIGHT! is one colour). A font pixel is [fp] screen pixels. [shrink]: a font pixel smaller
 * at a time until it fits. Its semantics carry the text, so tests find it like any Text.
 */
@Composable
private fun Gen5Label(text: String, top: Color, bottom: Color, outline: Color, fp: Int, shrink: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val family = pixelFontFamily()
    BoxWithConstraints(Modifier.semantics { this.text = AnnotatedString(text) }) {
        val maxW = constraints.maxWidth
        val (layout, k) = remember(text, maxW, fp, density, family, shrink) {
            // Spaced a font pixel wider (the bold copy fills the font's own gap), then unspaced, then a size down.
            val tries = (fp downTo 1).flatMap { k -> listOf(k to k, k to 0) }
            var pick: Pair<TextLayoutResult, Int>? = null
            for ((k, spacing) in tries) {
                val style = with(density) { TextStyle(fontFamily = family, fontSize = (16f * k).toSp(), letterSpacing = spacing.toFloat().toSp()) }
                pick = measurer.measure(text, style, maxLines = 1, softWrap = false) to k
                if (!shrink || pick.first.size.width + 3 * k <= maxW) break
            }
            pick!!
        }
        val kf = k.toFloat()
        val w = layout.size.width + 3 * kf
        val hgt = layout.size.height + 2 * kf
        Box(
            Modifier.size(with(density) { w.toDp() }, with(density) { hgt.toDp() }).drawBehind {
                for (dx in -1..2) for (dy in -1..1) {
                    if (dx in 0..1 && dy == 0) continue
                    drawText(layout, outline, Offset(kf + dx * kf, kf + dy * kf))
                }
                // The capitals' middle: Pixel Operator's caps stand 9 font pixels over the baseline.
                val baseline = layout.firstBaseline + kf
                val mid = baseline - 4.5f * kf
                val brush = if (top == bottom) androidx.compose.ui.graphics.SolidColor(top)
                else Brush.verticalGradient(0f to top, 0.5f to top, 0.5f to bottom, 1f to bottom, startY = mid - 4.5f * kf, endY = mid + 4.5f * kf)
                drawText(layout, brush, Offset(kf, kf))
                drawText(layout, brush, Offset(2 * kf, kf))
            },
        )
    }
}

/** The game's back arrow, a U-turn: K its outline, B its blue, L the lit edge, D the shade. */
private val UTurn = listOf(
    "...K..........",
    "..KLK.........",
    ".KLBBK........",
    "KLBBBBK.......",
    "KKKBBKKK.KKKK.",
    "..KLBK...KLDK.",
    "..KLBK...KLDK.",
    "..KLBK...KLDK.",
    "..KLBBK.KLBDK.",
    "...KLBBKLBDK..",
    "....KLBBBDK...",
    ".....KKKKK....",
)

/** Your party's balls (13 game pixels, as the game's) and the foe's (7): our own drawing in the game's colours -
 * o the line, r red, s the shade, w the white half. */
private val BallBig = listOf(
    "....ooooo....",
    "..oorrrrroo..",
    ".orrrrrrrrro.",
    ".orrrrrrrrso.",
    "orrrrooorrsso",
    "osssowwwossso",
    "ooooowwwooooo",
    "owwwowwwowwso",
    "owwwwooowwwso",
    ".owwwwwwwwso.",
    ".owwwwwwwsso.",
    "..oowwwwsoo..",
    "....ooooo....",
)

private val BallSmall = listOf(
    "..ooo..",
    ".orrro.",
    "orrrrso",
    "ooowooo",
    "owwwwso",
    ".owwso.",
    "..ooo..",
)

private val BallColors = mapOf('o' to Color(0xFF595959), 'r' to Color(0xFFFB6982), 's' to Color(0xFFA26969), 'w' to Color(0xFFEBE3F3))
private val BallColorsFainted = mapOf('o' to Color(0xFF595959), 'r' to Color(0xFF9A9AA2), 's' to Color(0xFF79797F), 'w' to Color(0xFFC3C3CB))

// The game's colours, sampled from its frames.
private val Red = Color(0xFFB20028)
private val RedRing = Color(0xFFA20028)
private val RedRingOuter = Color(0xFF920020)
private val BandShadeHi = Color(0xFF9A0020)
private val BandShade = Color(0xFF820018)
private val BandEdge = Color(0xFF282020)
private val BandFill = Color(0xFF413030)
private val Pale = Color(0xFFDBDBE3)
private val PaleLilac = Color(0xFFD3D3EB)
private val PaleLilacDeep = Color(0xFFCBC3EB)
private val PaleShade1 = Color(0xFF9A92AA)
private val PaleShade2 = Color(0xFFBABACB)
private val PaleShade3 = Color(0xFFCBCBE3)
private val FieldFill = Color(0xFF514949)
private val FieldEdge = Color(0xFF413838)
private val FieldOutline = Color(0xFF181818)
private val FieldShadeHi = Color(0xFF8A1830)
private val FieldShade = Color(0xFF8A1830)
private val FieldPaleShade1 = Color(0xFFC3C3CB)
private val FieldPaleShade2 = Color(0xFFCBCBE3)
private val G5FightRed = Color(0xFFCB0038)
private val LabelOutline = Color(0xFF202020)
private val RimOuterGrey = Color(0xFF717171)
private val RimPale = Color(0xFFCBCBCB)
private val RimWhite = Color(0xFFFBFBFB)
private val RimInner = Color(0xFF595959)
private val DimRim1 = Color(0xFF383838)
private val DimRim2 = Color(0xFF616161)
private val DimRim3 = Color(0xFF797979)
private val Face = listOf(Color(0xFF383838), Color(0xFF353535), Color(0xFF303030), Color(0xFF2A2A2A), Color(0xFF242424))
private val DimFace = listOf(Color(0xFF202020), Color(0xFF1C1C1C), Color(0xFF181818), Color(0xFF141414))
private val PlateText = Color(0xFFFBFBFB)
private val PlateTextShadow = Color(0xFF717171)
private val PlateMuted = Color(0xFFC3C3CB)
private val TagShadow = Color(0xFF515151)
private val EmptyRim = Color(0xFF828282)
private val UTurnBlue = Color(0xFF28BAF3)
private val UTurnLight = Color(0xFFA2DBFB)
private val UTurnDeep = Color(0xFF3882AA)
private val UTurnOutline = Color(0xFF202020)
private val BallLine = Color(0xFF595959)
private val Night = Color(0xFF181818)
private val BallOutline = Color(0xFF080808)
private val BallTopRim = Color(0xFF300810)
private val BallTop = Color(0xFF380810)
private val BallBottomRim = Color(0xFF383838)
private val BallBottom = Color(0xFF414141)
private val MsgBand = Color(0xF0202024)
private val MsgLine = Color(0xFFB20028)
private val MsgLineShadow = Color(0xFF590010)
private val MsgShadow = Color(0xFF515159)
private val PartyRed = Color(0xFF710018)
private val PartyRedDark = Color(0xFF590010)
private val PartyRedDeep = Color(0xFF590010)
private val PartyRedDeeper = Color(0xFF490000)
private val PartyBand = Color(0xFF281818)
private val PartyBandEdge = Color(0xFF201010)
private val PartyLilac = Color(0xFF797982)
private val PartyLilacDeep = Color(0xFF616171)
private val SlotOutline = Color(0xFF181818)
private val GreenRim = listOf(Color(0xFF8AD318), Color(0xFF619A28), Color(0xFF385930))
private val FaintRim = listOf(Color(0xFF8A8A8A), Color(0xFF616161), Color(0xFF383838))
private val EmptySlotRim = listOf(Color(0xFF304910), Color(0xFF203010), Color(0xFF101810))
private val EmptySlotFace = Color(0xFF080808)
private val EmptySlotStrip = Color(0xFF203010)
private val Strip = Color(0xFFCBCBCB)
private val StripLine = Color(0xFF828282)
private val BarFrame = Color(0xFF303030)
private val HpGreen = Color(0xFF18C320)
private val HpYellow = Color(0xFFF3B300)
private val HpRed = Color(0xFFFB3030)
private val HpLabel = Color(0xFF00FB49)
private val HpLabelShadow = Color(0xFF183018)
private val SlotText = Color(0xFFEBEBEB)
private val SlotTextShadow = Color(0xFF828282)
private val MaleBlue = Color(0xFF2849FB)
private val FemaleRed = Color(0xFFFB4949)
private val MsgBoxFill = Color(0xFFFBFBFB)
private val MsgBoxLine = Color(0xFF515151)
private val MsgBoxText = Color(0xFF595151)
private val MsgBoxShadow = Color(0xFFA2A2AA)
