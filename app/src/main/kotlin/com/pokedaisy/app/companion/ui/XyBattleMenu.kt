package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.typeLabel
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlin.math.cos
import kotlin.math.sin

/*
 * Pokemon X / Y's battle buttons on one screen: the game asks on its touch screen, so here its own buttons sit in the top
 * screen's bottom-right corner, over the battle with nothing behind them, the player's HP box and the foe left in sight.
 * Drawn after the game's touch screen (the user's call: smooth, in its modern look, not the app's pixel art): FIGHT a red
 * oval in a red ring and a thick silver one, its maroon word white-edged over a dark red burst, the battler's icon under
 * it; BAG / RUN / POKéMON round, each in its colour with a faint picture in its face and its word dark, white-edged; the
 * moves as pill cards, the type's colour light over dark, the type in a chip and "PP 35/35"; the way back a round blue
 * button. The cursor is the game's: a white hollow arrow by the button, bobbing.
 * Run by [DsBattleMenuState] like the DS games' strip (its `xy` keys: FIGHT over the row of three).
 */

/** The corner's size and its margin, in the 3DS top screen's pixels (of its 400 x 240). */
private const val CORNER_W = 124f
private const val CORNER_H = 100f
private const val CORNER_MARGIN = 4f
/** A move card's width; wider with each move's power on it too (BATTLE HINTS: the power and the PP side by side, each
 * at a readable size - squeezed into the plain card's room they were too small, the user's call), the corner with it. */
private const val CARD_W = 60f
private const val CARD_W_POWER = 90f
private const val CARD_GAP = 4f
/** The corner's least width in dp (a small, dense screen's picture, the RG DS's, made it a stamp). */
private const val CORNER_MIN_DP = 170

/** An action's colours (FIGHT 0, BAG 1, POKéMON 2, RUN 3, as [DsBattleMenuState]'s): its face light and dark, the
 * ring round it, its word. Sampled off the game's touch screen. */
internal class XyAction(val word: String, val light: Color, val dark: Color, val ring: Color, val ink: Color)
private val XY_ACTIONS = listOf(
    XyAction(tk("FIGHT"), Color(0xFFF0584A), Color(0xFFD8352A), Color(0xFFC22E24), Color(0xFF5A1610)),
    XyAction(tk("BAG"), Color(0xFFF6BE62), Color(0xFFE79A34), Color(0xFFE0922C), Color(0xFF4A2A0E)),
    XyAction(tk("POKéMON"), Color(0xFF8ED870), Color(0xFF52AE40), Color(0xFF3FA034), Color(0xFF1C3814)),
    XyAction(tk("RUN"), Color(0xFF64B6F0), Color(0xFF2E78D0), Color(0xFF2E7CD6), Color(0xFF14265A)),
)
private val XY_EDGE = Color(0xFF3A3A3A)
private val XY_SILVER_TOP = Color(0xFFFDFDFD)
private val XY_SILVER_BOTTOM = Color(0xFFB4B4B4)
private val XY_NAME = Color(0xFF1E1E1E)
private val XY_BACK_LIGHT = Color(0xFF66AEEE)
private val XY_BACK_DARK = Color(0xFF2560B8)

/** The move cards' type colours, as the game's touch screen has them (Normal a plain grey there). */
private val XY_TYPES = mapOf(
    "NORMAL" to Color(0xFF9C9C9C), "FIRE" to Color(0xFFEA5A3A), "WATER" to Color(0xFF4A8EE4), "GRASS" to Color(0xFF68BE3A),
    "ELECTRIC" to Color(0xFFEFC12E), "ICE" to Color(0xFF62CAD6), "FIGHTING" to Color(0xFFCE6630), "POISON" to Color(0xFF9A50BE),
    "GROUND" to Color(0xFFC89A48), "FLYING" to Color(0xFF86A4E6), "PSYCHIC" to Color(0xFFE85C98), "BUG" to Color(0xFFA0B630),
    "ROCK" to Color(0xFFB49E5A), "GHOST" to Color(0xFF6A5AAA), "DRAGON" to Color(0xFF5A68D6), "DARK" to Color(0xFF5E504C),
    "STEEL" to Color(0xFF8AA2B2), "FAIRY" to Color(0xFFEE98D4),
)

/**
 * [state]'s menu as Pokemon X's buttons in [picture]'s bottom-right corner (px in this box): [mode] 1 the actions, 2 the
 * moves; [idle] = nothing takes a tap now; [pulse] the cursor's bob, 0 .. 1.
 */
@Composable
fun XyBattleCorner(
    state: DsBattleMenuState, picture: Rect, mode: Int, idle: Boolean, moves: List<MoveView>, icon: String?, pulse: Float,
    /** BATTLE HINTS: each move's power on its card too. */
    power: Boolean = false,
) {
    val density = LocalDensity.current
    // A 3DS pixel in screen px, the corner never under [CORNER_MIN_DP] wide nor over half the picture.
    val minP = with(density) { CORNER_MIN_DP.dp.toPx() } / CORNER_W
    val p = maxOf(picture.height / 240f, minP).coerceAtMost(picture.width * 0.5f / CORNER_W)
    fun dp(n: Float): Dp = with(density) { (n * p).toDp() }
    val cardW = if (mode == 2 && power) CARD_W_POWER else CARD_W
    val cornerW = if (mode == 2) 2 * cardW + CARD_GAP else CORNER_W
    val left = picture.right - (cornerW + CORNER_MARGIN) * p
    val top = picture.bottom - (CORNER_H + CORNER_MARGIN) * p
    Box(Modifier.offset(x = with(density) { left.toDp() }, y = with(density) { top.toDp() }).size(dp(cornerW), dp(CORNER_H))) {
        @Composable
        fun Place(x: Float, y: Float, w: Float, h: Float, content: @Composable () -> Unit) {
            Box(Modifier.offset(x = dp(x), y = dp(y)).size(dp(w), dp(h))) { content() }
        }
        if (mode == 1) {
            Place(12f, 0f, 100f, 58f) {
                XyFight(state.actionCursor == 0, state.actionEnabled(0) && !idle, p, pulse, icon) {
                    state.actionCursor = 0; state.pickAction(0)
                }
            }
            // BAG, RUN, POKéMON left to right, RUN a little lower (the game's arc).
            for ((i, x, y) in listOf(Triple(1, 0f, 60f), Triple(3, 43f, 62f), Triple(2, 86f, 60f))) {
                Place(x, y, 38f, 38f) {
                    XyRound(i, state.actionCursor == i, state.actionEnabled(i) && !idle, p, pulse) {
                        state.actionCursor = i; state.pickAction(i)
                    }
                }
            }
        } else {
            for (i in 0..3) {
                Place((i % 2) * (cardW + CARD_GAP), (i / 2) * 34f, cardW, 30f) {
                    val move = moves.getOrNull(i)
                    XyMoveCard(move, state.moveCursor == i, move != null && state.moveEnabled(i) && !idle, p, pulse, power) {
                        state.moveCursor = i; state.pickMove(i)
                    }
                }
            }
            Place(cornerW - 30f, 70f, 30f, 30f) { XyBackButton(!idle, p) { state.back() } }
        }
    }
}

/** FIGHT: the red oval, its word over the burst, the battler's [icon] under it; [on] the arrow by the word. */
@Composable
private fun XyFight(on: Boolean, enabled: Boolean, p: Float, pulse: Float, icon: String?, onClick: () -> Unit) {
    val a = XY_ACTIONS[0]
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.5f)
            .drawBehind {
                val face = drawRoundButton(a, p, ring = 1.7f * p, silver = 2.3f * p)
                drawBurst(Offset(size.width / 2f, size.height * 0.36f), face, p)
            }
            .drawWithContent {
                drawContent()
                if (on) drawXyArrow(Offset(size.width * 0.76f, size.height * 0.44f), p, pulse)
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
    ) {
        XyText(
            tr(a.word).uppercase(), 15f * p, a.ink, Color.White.copy(alpha = 0.9f), 0.6f * p,
            Modifier.align(BiasAlignment(0f, -0.28f)).padding(horizontal = with(density) { (12f * p).toDp() }),
            spacing = 0.08f,
        )
        if (icon != null) {
            val iconPx = 40f * kotlin.math.floor(p * 0.7f).coerceAtLeast(1f)
            SpeciesIcon(icon, with(density) { iconPx.toDp() }, Modifier.align(Alignment.BottomCenter).offset(y = -with(density) { (3f * p).toDp() }))
        }
    }
}

/** BAG, POKéMON or RUN ([i]): round, its picture faint in the face, its word dark and white-edged. */
@Composable
private fun XyRound(i: Int, on: Boolean, enabled: Boolean, p: Float, pulse: Float, onClick: () -> Unit) {
    val a = XY_ACTIONS[i]
    Box(
        Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.5f)
            .drawBehind {
                val face = drawRoundButton(a, p, ring = 1.3f * p, silver = 2f * p)
                clipPath(Path().apply { addOval(face) }) { drawActionPicture(i, face, lerp(a.dark, Color.Black, 0.2f).copy(alpha = 0.32f), p) }
                drawGloss(face, 0.16f, 0.5f)
            }
            .drawWithContent {
                drawContent()
                if (on) drawXyArrow(Offset(size.width * 0.7f, size.height * 0.36f), p, pulse)
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        XyText(tr(a.word).uppercase(), 9f * p, a.ink, Color.White, 0.5f * p, Modifier.padding(horizontal = with(LocalDensity.current) { (2.5f * p).toDp() }))
    }
}

/** A move as the touch screen's card: a pill in a silver rim, the type's colour light over dark, the name on the light
 * half, the type's chip and "PP 35/35" on the dark one; top right its multiplier on the foe; [on] the arrow over it. */
@Composable
private fun XyMoveCard(move: MoveView?, on: Boolean, enabled: Boolean, p: Float, pulse: Float, power: Boolean, onClick: () -> Unit) {
    val type = move?.let { XY_TYPES[it.type.uppercase()] ?: com.pokedaisy.app.companion.ui.theme.AppColors.typeColors[it.type] } ?: Color(0xFF9C9C9C)
    val density = LocalDensity.current
    fun dp(n: Float) = with(density) { (n * p).toDp() }
    Box(
        Modifier.fillMaxSize().alpha(if (move != null && enabled) 1f else 0.45f)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(0f, 1.2f * p), size, r)
                drawRoundRect(XY_EDGE, cornerRadius = r)
                val rim = insetRect(0.6f * p)
                drawRoundRect(Brush.verticalGradient(listOf(XY_SILVER_TOP, XY_SILVER_BOTTOM), rim.top, rim.bottom), rim.topLeft, rim.size, CornerRadius(rim.height / 2f))
                val line = insetRect(2.4f * p)
                drawRoundRect(lerp(type, Color.Black, 0.55f), line.topLeft, line.size, CornerRadius(line.height / 2f))
                val face = insetRect(2.9f * p)
                val fr = CornerRadius(face.height / 2f)
                val split = face.top + face.height * 0.52f
                drawRoundRect(Brush.verticalGradient(listOf(lerp(type, Color.White, 0.72f), lerp(type, Color.White, 0.28f)), face.top, split), face.topLeft, face.size, fr)
                clipRect(top = split) {
                    drawRoundRect(Brush.verticalGradient(listOf(lerp(type, Color.Black, 0.22f), lerp(type, Color.Black, 0.42f)), split, face.bottom), face.topLeft, face.size, fr)
                }
                drawLine(lerp(type, Color.Black, 0.55f), Offset(face.left + fr.x * 0.3f, split), Offset(face.right - fr.x * 0.3f, split), 0.5f * p)
                // The light half's sheen.
                val g = Rect(face.left + face.width * 0.06f, face.top + 0.5f * p, face.right - face.width * 0.06f, face.top + face.height * 0.26f)
                drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), g.top, g.bottom), g.topLeft, g.size, CornerRadius(g.height / 2f))
            }
            .drawWithContent {
                drawContent()
                if (on) drawXyArrow(Offset(size.width * 0.16f, size.height * 0.2f), p, pulse)
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = move != null && enabled, onClick = onClick)
            .padding(start = dp(4.2f), end = dp(5f), top = dp(3f), bottom = dp(3f)),
    ) {
        if (move == null) return@Box
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(0.5f), contentAlignment = Alignment.Center) {
                XyText(move.name, 9.5f * p, XY_NAME, Color.Transparent, 0f, weight = FontWeight.Medium)
            }
            Row(Modifier.fillMaxWidth().weight(0.5f), verticalAlignment = Alignment.CenterVertically) {
                // The type's chip: a white-rimmed pill, the type's dark inside, its word white.
                Box(
                    Modifier.drawBehind {
                        drawRoundRect(Color(0xFFF2F2F2), cornerRadius = CornerRadius(size.height / 2))
                        val i = insetRect(0.6f * p)
                        drawRoundRect(Brush.verticalGradient(listOf(lerp(type, Color.Black, 0.15f), lerp(type, Color.Black, 0.45f)), i.top, i.bottom), i.topLeft, i.size, CornerRadius(i.height / 2))
                    }.padding(horizontal = dp(if (power) 2f else 2.4f), vertical = dp(0.4f)),
                ) { XyText(typeLabel(move.type), (if (power) 4.8f else 5.2f) * p, Color.White, lerp(type, Color.Black, 0.6f), 0.3f * p, spacing = 0.05f) }
                Box(Modifier.weight(1f).padding(start = dp(2f)), contentAlignment = Alignment.CenterEnd) {
                    val pp = if (move.maxPp > 0) "${move.pp}/${move.maxPp}" else "${move.pp}"
                    val ink = lerp(type, Color.Black, 0.65f)
                    // BATTLE HINTS: the power before the PP, on a wider card ([CARD_W_POWER]), a little smaller than the PP alone;
                    // one line, so the two always share a size (made smaller together where it doesn't fit).
                    if (power) {
                        XyText(tr("PWR {0}", powerLabel(move.power)) + "  " + tr("PP {0}", pp), 7.6f * p, Color.White, ink, 0.3f * p, weight = FontWeight.Medium)
                    } else XyText(tr("PP {0}", pp), 8.4f * p, Color.White, ink, 0.3f * p, weight = FontWeight.Medium)
                }
            }
        }
        // The multiplier on the foe (BATTLE HINTS): a pill on the card's top-right rim, clear of the name.
        move.vs.firstOrNull()?.let { v ->
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = dp(3f), y = -dp(5.5f))
                    .drawBehind {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(XY_EDGE, cornerRadius = r)
                        val i = insetRect(0.5f * p)
                        drawRoundRect(Color.White, i.topLeft, i.size, CornerRadius(i.height / 2))
                        val f = insetRect(1.1f * p)
                        drawRoundRect(verdictColor(v.pct), f.topLeft, f.size, CornerRadius(f.height / 2))
                    }
                    .padding(horizontal = dp(2.6f), vertical = dp(0.9f)),
            ) { XyText(v.label, 5.8f * p, Color.White, Color.Black.copy(alpha = 0.45f), 0.4f * p, weight = FontWeight.Medium) }
        }
    }
}

/** The way back: the touch screen's round blue button in its silver rim, the white return arrow. */
@Composable
private fun XyBackButton(enabled: Boolean, p: Float, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.5f)
            .drawBehind {
                val face = drawRoundButton(XyAction("", XY_BACK_LIGHT, XY_BACK_DARK, Color(0xFF2A6CC8), Color.White), p, ring = 1f * p, silver = 1.8f * p)
                drawGloss(face, 0.16f, 0.5f)
                // The return arrow: from the top right down and round to the left, its head pointing left.
                val w = size.width
                val h = size.height
                val line = Stroke(w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val path = Path().apply {
                    moveTo(w * 0.64f, h * 0.30f)
                    lineTo(w * 0.64f, h * 0.46f)
                    quadraticBezierTo(w * 0.64f, h * 0.60f, w * 0.50f, h * 0.60f)
                    lineTo(w * 0.40f, h * 0.60f)
                }
                val head = Path().apply {
                    moveTo(w * 0.24f, h * 0.60f)
                    lineTo(w * 0.43f, h * 0.45f)
                    lineTo(w * 0.43f, h * 0.75f)
                    close()
                }
                for ((c, dy) in listOf(Color(0xFF173E80) to 0.9f * p, Color.White to 0f)) translate(0f, dy) {
                    drawPath(path, c, style = line)
                    drawPath(head, c)
                    drawPath(head, c, style = Stroke(w * 0.04f, join = StrokeJoin.Round))
                }
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick),
    )
}

/** A round button's layers, as the game's: a soft shadow, a dark edge, a ring in [a]'s colour [ring] px wide, a silver
 * one [silver] px wide (light at the top), a dark line, then the face light over dark with a sheen; its face's oval back. */
internal fun DrawScope.drawRoundButton(a: XyAction, p: Float, ring: Float, silver: Float): Rect {
    drawOval(Color.Black.copy(alpha = 0.35f), Offset(0f, 1.3f * p), size)
    drawOval(XY_EDGE)
    var r = insetRect(0.6f * p)
    drawOval(Brush.verticalGradient(listOf(lerp(a.ring, Color.White, 0.15f), lerp(a.ring, Color.Black, 0.25f)), r.top, r.bottom), r.topLeft, r.size)
    r = r.deflate(ring)
    drawOval(Brush.verticalGradient(listOf(XY_SILVER_TOP, XY_SILVER_BOTTOM), r.top, r.bottom), r.topLeft, r.size)
    r = r.deflate(silver)
    drawOval(lerp(a.dark, Color.Black, 0.5f), r.topLeft, r.size)
    val face = r.deflate(0.5f * p)
    drawOval(Brush.verticalGradient(listOf(a.light, a.dark), face.top, face.bottom), face.topLeft, face.size)
    // The face's edge a shade darker all round (its inner shadow), then the sheen over its top half.
    drawOval(lerp(a.dark, Color.Black, 0.2f).copy(alpha = 0.5f), face.topLeft, face.size, style = Stroke(1.2f * p))
    drawGloss(face, 0.1f, 0.42f)
    return face
}

/** A white sheen over [face]'s top half, [side] of its width in from each side, fading down from [alpha]. */
internal fun DrawScope.drawGloss(face: Rect, side: Float, alpha: Float) {
    val g = Rect(face.left + face.width * side, face.top + face.height * 0.03f, face.right - face.width * side, face.top + face.height * 0.5f)
    drawOval(Brush.verticalGradient(listOf(Color.White.copy(alpha = alpha), Color.White.copy(alpha = 0f)), g.top, g.bottom), g.topLeft, g.size)
}

/** FIGHT's burst behind its word: dark red spikes out to both sides from [c], a light glow at its middle; inside [face]. */
private fun DrawScope.drawBurst(c: Offset, face: Rect, p: Float) {
    clipPath(Path().apply { addOval(face) }) {
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0f)), c, face.width * 0.32f),
            Offset(c.x - face.width * 0.4f, c.y - face.height * 0.3f), Size(face.width * 0.8f, face.height * 0.6f),
        )
        val spike = Color(0xFF8E1A12).copy(alpha = 0.55f)
        // Angle (degrees from the horizontal), length as a share of the face's width, base width in 3DS pixels.
        val spikes = listOf(Triple(0f, 0.46f, 2.2f), Triple(-14f, 0.34f, 1.6f), Triple(12f, 0.38f, 1.6f), Triple(-30f, 0.24f, 1.2f), Triple(28f, 0.26f, 1.2f), Triple(-6f, 0.40f, 1f), Triple(6f, 0.3f, 1f))
        for (side in listOf(1f, -1f)) for ((deg, len, base) in spikes) {
            val t = deg.toDouble() * kotlin.math.PI / 180.0
            val dx = (cos(t) * side).toFloat()
            val dy = sin(t).toFloat()
            val l = face.width * len
            val nx = -dy * base * p
            val ny = dx * base * p
            drawPath(Path().apply {
                moveTo(c.x + nx, c.y + ny)
                lineTo(c.x + dx * l, c.y + dy * l)
                lineTo(c.x - nx, c.y - ny)
                close()
            }, spike)
        }
    }
}

/** BAG's bag, RUN's runner, POKéMON's speech bubble and ball: the faint pictures in the game's faces. */
private fun DrawScope.drawActionPicture(i: Int, face: Rect, c: Color, p: Float) {
    val w = face.width
    val h = face.height
    fun at(x: Float, y: Float) = Offset(face.left + w * x, face.top + h * y)
    val line = Stroke(2.2f * p, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (i) {
        1 -> { // a shoulder bag at the left
            drawRoundRect(c, at(0.02f, 0.42f), Size(w * 0.42f, h * 0.42f), CornerRadius(w * 0.08f), style = line)
            drawArc(c, 180f, 180f, false, at(0.08f, 0.26f), Size(w * 0.3f, h * 0.34f), style = line)
            drawCircle(c, w * 0.06f, at(0.84f, 0.62f), style = Stroke(1.6f * p))
        }
        3 -> { // a runner at the left, leaning in
            drawCircle(c, w * 0.07f, at(0.3f, 0.3f))
            val body = Path().apply {
                moveTo(face.left + w * 0.28f, face.top + h * 0.4f); lineTo(face.left + w * 0.22f, face.top + h * 0.6f)
                moveTo(face.left + w * 0.22f, face.top + h * 0.6f); lineTo(face.left + w * 0.32f, face.top + h * 0.78f)
                moveTo(face.left + w * 0.22f, face.top + h * 0.6f); lineTo(face.left + w * 0.1f, face.top + h * 0.72f)
                moveTo(face.left + w * 0.27f, face.top + h * 0.45f); lineTo(face.left + w * 0.38f, face.top + h * 0.52f)
                moveTo(face.left + w * 0.27f, face.top + h * 0.45f); lineTo(face.left + w * 0.14f, face.top + h * 0.46f)
            }
            drawPath(body, c, style = line)
            drawLine(c, at(0.42f, 0.36f), at(0.62f, 0.36f), 1.4f * p, StrokeCap.Round)
        }
        2 -> { // a speech bubble and a ball at the right
            drawRoundRect(c, at(0.6f, 0.16f), Size(w * 0.3f, h * 0.18f), CornerRadius(w * 0.04f))
            drawCircle(c, w * 0.15f, at(0.8f, 0.58f), style = Stroke(1.8f * p))
            drawLine(c, at(0.65f, 0.58f), at(0.95f, 0.58f), 1.6f * p)
            drawCircle(c, w * 0.04f, at(0.8f, 0.58f))
        }
    }
}

/** The game's cursor: a white hollow arrow, its tip down and to the left at [tip], bobbing towards it with [pulse]. */
private fun DrawScope.drawXyArrow(tip: Offset, p: Float, pulse: Float) {
    val s = 11f * p
    val bob = (1f - pulse) * 2f * p
    val o = Offset(tip.x + bob, tip.y - s - bob)
    val path = Path().apply {
        moveTo(o.x + 0.06f * s, o.y + 0.98f * s)
        lineTo(o.x + 0.22f * s, o.y + 0.02f * s)
        lineTo(o.x + 0.98f * s, o.y + 0.44f * s)
        close()
    }
    drawPath(path, Color.White.copy(alpha = 0.25f))
    drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(2.6f * p, join = StrokeJoin.Round))
    drawPath(path, Color.White, style = Stroke(1.3f * p, join = StrokeJoin.Round))
}

internal fun DrawScope.insetRect(by: Float) = Rect(by, by, size.width - by, size.height - by)

/** [text] in the system's sans-serif, [size] px, filled [fill] inside a [outline] edge [edge] px wide, made smaller until
 * it fits the width it's given ([spacing]: letter spacing, em). */
@Composable
internal fun XyText(
    text: String, size: Float, fill: Color, outline: Color, edge: Float, modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Bold, spacing: Float = 0f,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val max = constraints.maxWidth.toFloat() - 2 * edge
        // SETTINGS > FONT's smooth fonts here too (the HUD, the battle buttons); PIXEL keeps the game's sans-serif.
        val appFont = com.pokedaisy.app.companion.ui.theme.AppFonts.current
        val family = remember(appFont) { if (appFont.pixel) FontFamily.SansSerif else appFont.family() }
        fun style(px: Float) = TextStyle(
            fontSize = with(density) { px.toSp() }, fontFamily = family, fontWeight = weight, letterSpacing = spacing.em,
        )
        val layout = remember(text, size, weight, spacing, max) {
            val first = measurer.measure(text, style(size), maxLines = 1, softWrap = false)
            if (first.size.width <= max || first.size.width == 0) first
            else measurer.measure(text, style(size * max / first.size.width * 0.98f), maxLines = 1, softWrap = false)
        }
        Spacer(
            Modifier.size(with(density) { (layout.size.width + 2 * edge).toDp() }, with(density) { (layout.size.height + 2 * edge).toDp() })
                .drawBehind {
                    if (edge > 0f && outline.alpha > 0f) {
                        drawText(layout, outline, Offset(edge, edge), drawStyle = Stroke(2 * edge, join = StrokeJoin.Round))
                    }
                    // (Fill said outright: the stroke pass's style stays on the layout's paint otherwise.)
                    drawText(layout, fill, Offset(edge, edge), drawStyle = androidx.compose.ui.graphics.drawscope.Fill)
                },
        )
    }
}
