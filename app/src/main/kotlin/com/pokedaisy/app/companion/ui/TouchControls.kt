package com.pokedaisy.app.companion.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.InstalledSkin
import com.pokedaisy.app.companion.SkinFit
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchColors
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchLook
import com.pokedaisy.app.companion.TouchLayout
import com.pokedaisy.app.companion.TouchStage
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.TouchGeometry.contains
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the touch controls tell the game: the GBA buttons held, the extras pressed and let go, a new press. */
class TouchInput(
    /** The GBA buttons held ([gba]) and TURBO A / B held ([turbo]), whenever either changes. */
    val onKeys: (gba: Int, turbo: Int) -> Unit = { _, _ -> },
    /** FF, the QUICK MENU, SAVE / LOAD STATE: pressed (true) and let go (false). */
    val onAction: (TouchButton, Boolean) -> Unit = { _, _ -> },
    /** Something newly pressed: VIBRATION's tick. */
    val onPress: () -> Unit = {},
)

/**
 * A [w]x[h] game screen at [dp] px per dp, laid out as the game's would be (SETTINGS has no game open): held sideways
 * the game fits in the middle and the controls have the whole screen; upright the game is across the top, the
 * companion right under it ([underGame], the app's default) and the controls along the screen's bottom - never
 * between the game and the companion in a picture (the user's rule), only when a player picks AT BOTTOM.
 */
fun simulateTouchStage(
    w: Float, h: Float, dp: Float, gameAspect: Float = 1.5f, statusBar: Float = 0f,
    portraitRatio: Float = PortraitLayout.DEFAULT_RATIO, underGame: Boolean = true, gameBoy: Boolean = false,
    /** ASPECT > EXPAND: the game fills everything above the companion. */
    expand: Boolean = false,
    /** EXPAND + UNDER GAME: the touch controls' strip along the bottom ([TouchGeometry.naturalHeight]); 0 = none. */
    padStrip: Int = 0,
): TouchStage {
    val portrait = h > w
    if (!portrait) {
        val gw = min(w, (h - statusBar) * gameAspect)
        val gh = gw / gameAspect
        val left = (w - gw) / 2
        val top = (h - statusBar - gh) / 2 + statusBar
        return TouchStage(w, h, dp, false, Rect(left, top, left + gw, top + gh), Rect(0f, 0f, w, h), null, gameAspect, gameBoy)
    }
    val gameH = (w / gameAspect + statusBar).roundToInt()
    val grip = (22 * dp).roundToInt()
    val a = PortraitLayout.arrange(w.roundToInt(), h.roundToInt(), gameH, grip, portraitRatio, underGame, expand, padStrip)
    val gh = min(w / gameAspect, a.gameBottom - statusBar)
    val gw = gh * gameAspect
    val game = if (expand) Rect(0f, statusBar, w, a.gameAreaBottom.toFloat())
        else Rect((w - gw) / 2, statusBar, (w + gw) / 2, statusBar + gh)
    val companion = Rect(0f, a.companionTop.toFloat(), w, (a.companionTop + a.companionHeight).toFloat())
    return TouchStage(w, h, dp, true, game, Rect(0f, a.padTop.toFloat(), w, a.padBottom.toFloat()), companion, gameAspect, gameBoy)
}

// The editor's marks, and FF's lit word (whatever COLORS says).
private val PAD_ON = Color(0xFFFFCF5A)
private val PAD_SELECT = Color(0xFFFFCF5A)

/**
 * LOOK + COLORS turned into what each part is drawn in. GLASS (the default) with DARK is the HUD's GLASS: see-through
 * dark buttons with light pixel text, a lighter edge to find them by.
 */
internal class PadPaint(val look: TouchLook, val colors: TouchColors) {
    constructor(style: TouchControlsStyle) : this(style.look, style.colors)

    private val body = Color(colors.fill)
    val edge = Color(colors.edge)
    val ink = Color(colors.ink)
    val shadow = Color(colors.shadow)
    val outline get() = look == TouchLook.OUTLINE
    val raised get() = look == TouchLook.CONSOLE
    /** MODERN: smooth shapes and a plain font, nothing stepped in pixels. */
    val modern get() = look == TouchLook.MODERN

    /** [b]'s own colour: its accent, else the body - solid but in GLASS. */
    fun base(b: TouchButton): Color {
        val c = colors.accent(b)?.let { Color(it) } ?: body
        return if (look == TouchLook.GLASS || look == TouchLook.MODERN) c else c.copy(alpha = 1f)
    }

    /** [b]'s face, held or not: lighter when held on a dark colour, darker on a light one; OUTLINE fills only held. */
    fun fill(b: TouchButton, on: Boolean): Color {
        val c = base(b)
        if (outline) return if (on) edge.copy(alpha = 0.45f) else Color.Transparent
        if (!on) return c
        val towards = if (c.luminance() < 0.45f) Color.White else Color.Black
        return lerp(c, towards, 0.35f).copy(alpha = max(c.alpha, 0.85f))
    }

    /** The darker shade under a CONSOLE button (its depth). */
    fun depth(b: TouchButton): Color = lerp(base(b), Color.Black, 0.5f).copy(alpha = 1f)

    /** The words on [b]: [ink], or white / near-black to read on an accent colour. */
    fun inkOn(b: TouchButton): Color {
        val a = colors.accent(b) ?: return ink
        return if (Color(a).luminance() > 0.5f) Color(0xFF1E2130) else Color.White
    }
    fun shadowOn(b: TouchButton): Color {
        val a = colors.accent(b) ?: return shadow
        return if (Color(a).luminance() > 0.5f) Color(0x66FFFFFF) else lerp(Color(a), Color.Black, 0.6f)
    }

    /** The D-pad's cross: CONSOLE's is a dark one, as on a handheld. */
    fun cross(on: Boolean): Color {
        if (!raised) return fill(TouchButton.DPAD, on)
        val c = lerp(body.copy(alpha = 1f), Color(0xFF1A1A20), 0.65f)
        return if (on) lerp(c, Color.White, 0.25f) else c
    }

    /** The faint disc under the cross. */
    val disc: Color get() = when (look) {
        TouchLook.GLASS, TouchLook.MODERN -> body.copy(alpha = body.alpha * 0.35f)
        TouchLook.OUTLINE -> Color.Transparent
        else -> lerp(body.copy(alpha = 1f), Color.Black, 0.18f).copy(alpha = 0.55f)
    }

    /** The arrows on the cross. */
    val arrow: Color get() = if (raised) Color(0xFFD8D8DE) else ink
}

/** How long without a touch before FADE WHEN IDLE fades them, and how faint they get. */
private const val IDLE_MS = 4000L
private const val IDLE_ALPHA = 0.18f

/** The on-screen state a draw needs: what's held, the D-pad's directions. Written from any thread (the touch input
 * windows' own, com.pokedaisy.app.TouchInputWindows): Compose state, so the drawn buttons follow when they can. */
class TouchPressed {
    var buttons by mutableIntStateOf(0)
    var dpad by mutableIntStateOf(0)
    /** A skin's items held, by index. */
    var skinItems by mutableStateOf(0L)
    fun has(b: TouchButton) = buttons and (1 shl b.ordinal) != 0
}

/** What the fingers on the controls hold: [TouchButton] bits ([held]), the D-pad's directions, a skin's items. */
class TouchHits(val held: Int, val dpad: Int, val skin: Long) {
    companion object { val NONE = TouchHits(0, 0, 0L) }
}

/** Where the buttons are, for a hit test off the UI thread: the drawn ones ([placed]) or a skin's ([fit]). */
class TouchHitModel(val placed: TouchGeometry.Placed, val fit: SkinFit?, val eightWay: Boolean)

/** The [n] fingers at ([xs], [ys]) (the controls' own pixels) on [model]'s buttons. */
fun touchHits(model: TouchHitModel, xs: FloatArray, ys: FloatArray, n: Int): TouchHits {
    val p = model.placed
    val slop = 10 * p.dp
    var held = 0
    var dpad = 0
    var skinHeld = 0L
    val sf = model.fit
    for (k in 0 until n) {
        val x = xs[k]
        val y = ys[k]
        if (sf != null) {
            val (b, d) = sf.touch(x, y, model.eightWay)
            held = held or b
            dpad = dpad or d
            sf.items.forEachIndexed { i, it ->
                if (i < 64 && x >= it.touch.left && x <= it.touch.right && y >= it.touch.top && y <= it.touch.bottom) skinHeld = skinHeld or (1L shl i)
            }
            continue
        }
        for (item in TouchGeometry.hits(p, x, y, slop)) {
            held = held or (1 shl item.button.ordinal)
            if (item.button == TouchButton.DPAD) {
                dpad = dpad or TouchGeometry.dpadBits(x - item.cx, y - item.cy, item.w / 2, model.eightWay)
            }
        }
    }
    return TouchHits(held, dpad, skinHeld)
}

/**
 * The fingers' presses turned into the game's: every source's hits ([SOURCE_DRAWN], the Compose canvas; [SOURCE_WINDOWS],
 * TouchInputWindows) held together, a new press a tick ([TouchInput.onPress]), the extras pressed and let go, the GBA
 * buttons whenever they change. Called from either source's thread; the callbacks run on the caller's.
 */
class TouchPressTracker(val pressed: TouchPressed) {
    @Volatile var input = TouchInput()
    /** X / Y are the device's X / Y keys (a GBA / Game Boy game, [TouchButton.X]): pressed and let go through
     * [TouchInput.onAction], never the game's own buttons. */
    @Volatile var xyKeys = false
    private val bySource = arrayOf(TouchHits.NONE, TouchHits.NONE)
    private var gbaWas = 0
    private var turboWas = 0
    private var heldWas = 0
    private var dpadWas = 0

    fun update(source: Int, hits: TouchHits) = synchronized(this) {
        bySource[source] = hits
        val held = bySource[0].held or bySource[1].held
        val dpad = bySource[0].dpad or bySource[1].dpad
        val skin = bySource[0].skin or bySource[1].skin
        val input = input
        // Newly pressed: a tick; the extras' presses / let-gos.
        if (held and heldWas.inv() != 0 || dpad and dpadWas.inv() != 0) input.onPress()
        val xy = xyKeys
        for (b in TouchButton.entries) {
            val bit = 1 shl b.ordinal
            val action = b.extra && b != TouchButton.TURBO_A && b != TouchButton.TURBO_B || xy && (b == TouchButton.X || b == TouchButton.Y)
            if (action && (held xor heldWas) and bit != 0) input.onAction(b, held and bit != 0)
        }
        heldWas = held
        dpadWas = dpad
        if (pressed.buttons != held) pressed.buttons = held
        if (pressed.dpad != dpad) pressed.dpad = dpad
        if (pressed.skinItems != skin) pressed.skinItems = skin
        var gba = dpad
        var turbo = 0
        for (b in TouchButton.entries) if (held and (1 shl b.ordinal) != 0) {
            if (b == TouchButton.TURBO_A || b == TouchButton.TURBO_B) turbo = turbo or b.gba
            else if (!(xy && (b == TouchButton.X || b == TouchButton.Y))) gba = gba or b.gba
        }
        if (gba != gbaWas || turbo != turboWas) {
            gbaWas = gba; turboWas = turbo
            input.onKeys(gba, turbo)
        }
    }

    companion object {
        const val SOURCE_DRAWN = 0
        const val SOURCE_WINDOWS = 1
    }
}

/**
 * The touch controls over the game, filling their area ([com.pokedaisy.app.TouchControlsView]). Every finger counts
 * on every move, so a thumb slides from one direction to the next, or from B onto A, without lifting. [onPlaced]
 * reports where everything went (the HUD keeps clear of it).
 */
@Composable
fun TouchControls(
    style: TouchControlsStyle,
    portrait: Boolean,
    input: TouchInput,
    modifier: Modifier = Modifier,
    gameAspect: Float = 1.5f,
    gameBoy: Boolean = false,
    ffOn: Boolean = false,
    /** A DS game: X and Y join A and B. */
    ds: Boolean = false,
    /** A Delta skin in place of the drawn buttons (SKIN), when it has a picture for the way the phone is held. */
    skin: InstalledSkin? = null,
    onPlaced: (TouchGeometry.Placed) -> Unit = {},
    /** The presses, shared with another source of fingers (com.pokedaisy.app.TouchInputWindows); null = its own. */
    tracker: TouchPressTracker? = null,
    /** Where the buttons are now, whenever that changes (the input windows' hit test). */
    onHitModel: (TouchHitModel) -> Unit = {},
    /** Extras switched on that do nothing in this game now: not drawn ([TouchControlsStyle.shown]). */
    unavailable: Set<TouchButton> = emptySet(),
) {
    val density = LocalDensity.current
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(skin, portrait, measured) { skinFit(skin, measured.width.toFloat(), measured.height.toFloat(), portrait) }
    val placed = remember(style, portrait, measured, gameAspect, gameBoy, ds, density.density, fit, unavailable) {
        fit?.let { skinPlaced(it, density.density) }
            ?: TouchGeometry.place(style, measured.width.toFloat(), measured.height.toFloat(), density.density, portrait, gameAspect, gameBoy, ds = ds, unavailable = unavailable)
    }
    LaunchedEffect(placed) { onPlaced(placed) }
    LaunchedEffect(placed, fit, style.eightWay) { onHitModel(TouchHitModel(placed, fit, style.eightWay)) }
    val skinImage by rememberSkinImage(skin, fit)
    val presses = tracker ?: remember { TouchPressTracker(TouchPressed()) }
    presses.input = input
    val pressed = presses.pressed
    val currentFit by rememberUpdatedState(fit)
    val currentPlaced by rememberUpdatedState(placed)
    val currentStyle by rememberUpdatedState(style)
    // FADE WHEN IDLE: counts touches down; a few seconds after the last let-go, faint.
    var touches by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(style.fadeIdle, touches, pressed.buttons == 0 && pressed.dpad == 0) {
        idle = false
        if (style.fadeIdle && pressed.buttons == 0 && pressed.dpad == 0) {
            delay(IDLE_MS)
            idle = true
        }
    }
    val fade by animateFloatAsState(if (idle) IDLE_ALPHA else 1f, tween(if (idle) 600 else 120), label = "touchFade")
    val measurer = rememberTextMeasurer(24)
    val font = pixelFontFamily()
    Canvas(
        modifier
            .onSizeChanged { measured = it }
            // A solid skin is drawn whole (the game shows where its screen is); a translucent one takes OPACITY.
            // A layer of its own, kept by the renderer until a press or a change redraws it: its pixel-stepped paths were
            // drawn again on every frame anything else on screen drew (a CPU job on older renderers: Android 11, 2026-11-08).
            .graphicsLayer {
                alpha = (if (fit != null && !fit.rep.translucent) 1f else style.opacity / 100f) * fade
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    val xs = FloatArray(16)
                    val ys = FloatArray(16)
                    while (true) {
                        val event = awaitPointerEvent()
                        var n = 0
                        for (c in event.changes) if (c.pressed && n < xs.size) { xs[n] = c.position.x; ys[n] = c.position.y; n++ }
                        if (event.changes.any { it.pressed && !it.previousPressed }) touches++
                        event.changes.forEach { it.consume() }
                        presses.update(
                            TouchPressTracker.SOURCE_DRAWN,
                            touchHits(TouchHitModel(currentPlaced, currentFit, currentStyle.eightWay), xs, ys, n),
                        )
                    }
                }
            },
    ) {
        if (fit != null) {
            // The first frame draws before onSizeChanged's size has come round: nothing until it has.
            if (fit.w == size.width && fit.h == size.height) drawSkin(fit, skinImage, pressed.skinItems)
            return@Canvas
        }
        if (skin != null && measured == IntSize.Zero) return@Canvas
        // The first frame draws before onSizeChanged's size has come round: place from the canvas's own.
        val p = if (placed.w == size.width && placed.h == size.height) placed
        else TouchGeometry.place(style, size.width, size.height, density.density, portrait, gameAspect, gameBoy, ds = ds, unavailable = unavailable)
        drawTouchControls(p, PadPaint(style), pressed, ffOn, measurer, font)
    }
}

/** [skin] laid on a [w]x[h] area: held sideways the whole picture (the game in its screen), upright only its
 * buttons; null with no skin, or none for this way of holding the phone (the drawn buttons then). */
fun skinFit(skin: InstalledSkin?, w: Float, h: Float, portrait: Boolean): SkinFit? {
    if (skin == null || w <= 0f || h <= 0f) return null
    val rep = skin.skin.pick(w, h, portrait) ?: return null
    return SkinFit(rep, w, h, cropToControls = portrait)
}

/** Every control in [placed], at its place, as [paint] says; [pressed] lit. */
private fun DrawScope.drawTouchControls(
    placed: TouchGeometry.Placed,
    paint: PadPaint,
    pressed: TouchPressed?,
    ffOn: Boolean,
    measurer: TextMeasurer,
    font: FontFamily,
    selected: TouchButton? = null,
    editing: Boolean = false,
    /** The whole set's opacity, drawn in one layer (the game's view takes it from its graphicsLayer instead). */
    alpha: Float = 1f,
) {
    if (alpha < 1f) {
        val layer = androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha }
        drawContext.canvas.saveLayer(Rect(Offset.Zero, Size(placed.w, placed.h)), layer)
        drawTouchControls(placed, paint, pressed, ffOn, measurer, font, selected, editing)
        drawContext.canvas.restore()
        return
    }
    // The controls' pixel: the companion's GBA pixel at this density (a whole screen pixel or more, but in a preview).
    val step = max(1f, (placed.dp * 1.25f).roundToInt().toFloat())
    // Pills of one size share one word size, the largest every one of their words fits (SELECT and START alike).
    val pillSize = placed.items.filter { it.shape == TouchGeometry.Shape.PILL }
        .groupBy { it.w.roundToInt() to it.h.roundToInt() }
        .mapValues { (_, group) -> group.minOf { labelSize(it, measurer, font, paint.modern) } }
    fun sizeFor(item: TouchGeometry.Item) =
        if (item.shape == TouchGeometry.Shape.PILL) pillSize[item.w.roundToInt() to item.h.roundToInt()] else null
    if (paint.modern) {
        for (item in placed.items) {
            val on = pressed?.has(item.button) == true
            if (item.shape == TouchGeometry.Shape.CROSS) drawSmoothCross(item, paint, placed.dp, pressed?.dpad ?: 0)
            else {
                val b = item.button
                val stroke = max(1f, 1.5f * placed.dp)
                if (item.shape == TouchGeometry.Shape.ROUND) {
                    val r = min(item.w, item.h) / 2
                    drawCircle(paint.fill(b, on), r, Offset(item.cx, item.cy))
                    drawCircle(paint.edge, r - stroke / 2, Offset(item.cx, item.cy), style = Stroke(stroke))
                } else {
                    val corner = CornerRadius(item.h / 2, item.h / 2)
                    drawRoundRect(paint.fill(b, on), Offset(item.left, item.top), Size(item.w, item.h), corner)
                    drawRoundRect(
                        paint.edge, Offset(item.left + stroke / 2, item.top + stroke / 2), Size(item.w - stroke, item.h - stroke),
                        CornerRadius(item.h / 2 - stroke / 2, item.h / 2 - stroke / 2), style = Stroke(stroke),
                    )
                }
                val lit = b == TouchButton.FF && ffOn
                drawLabel(item, measurer, font, if (lit) PAD_ON else paint.inkOn(b), paint.shadowOn(b), step, modern = true, size = sizeFor(item))
            }
            drawEditMark(item, selected, editing, step)
        }
        return
    }
    for (item in placed.items) {
        val on = pressed?.has(item.button) == true
        when (item.shape) {
            TouchGeometry.Shape.CROSS -> drawCross(item, paint, step, pressed?.dpad ?: 0)
            TouchGeometry.Shape.ROUND, TouchGeometry.Shape.PILL -> {
                val r = min(item.w, item.h) / 2
                val b = item.button
                // CONSOLE: a darker shade under the button, the face sinking onto it when pressed.
                val depth = if (paint.raised) (2 * step) else 0f
                val sink = if (paint.raised && on) depth else 0f
                val sz = Size(item.w, item.h - depth)
                if (depth > 0f) drawPixelRoundRect(paint.depth(b), Offset(item.left, item.top + depth), sz, r, step)
                val tl = Offset(item.left, item.top + sink)
                drawPixelRoundRect(paint.fill(b, on), tl, sz, r, step)
                if (paint.raised) {
                    // A highlight along the top edge, a shade line round it.
                    drawPixelRoundFrame(paint.depth(b).copy(alpha = 0.6f), tl, sz, r, step, step)
                } else {
                    // OUTLINE: the edge thicker, with the words' shadow round it so it reads on a bright game.
                    if (paint.outline) drawPixelRoundFrame(paint.shadow.copy(alpha = 0.7f), Offset(tl.x - step, tl.y - step), Size(sz.width + 2 * step, sz.height + 2 * step), r + step, step, step)
                    drawPixelRoundFrame(paint.edge, tl, sz, r, if (paint.outline) 2 * step else step, step)
                }
                val lit = b == TouchButton.FF && ffOn
                drawLabel(item.copy(cy = item.cy - (depth - sink) / 2), measurer, font, if (lit) PAD_ON else paint.inkOn(b), paint.shadowOn(b), step, size = sizeFor(item))
            }
        }
        drawEditMark(item, selected, editing, step)
    }
}

/** EDIT LAYOUT's dashed frame round [item] (brighter round the one picked). */
private fun DrawScope.drawEditMark(item: TouchGeometry.Item, selected: TouchButton?, editing: Boolean, step: Float) {
    if (!editing) return
    val frame = if (item.button == selected) PAD_SELECT else Color(0x99FFFFFF)
    val pad = 3 * step
    drawRect(
        frame, Offset(item.left - pad, item.top - pad), Size(item.w + 2 * pad, item.h + 2 * pad),
        style = Stroke(width = if (item.button == selected) 2 * step else step, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * step, 2 * step))),
    )
}

/** MODERN's D-pad: a smooth rounded cross over a faint disc, smooth arrows, a held arm lit. */
private fun DrawScope.drawSmoothCross(item: TouchGeometry.Item, paint: PadPaint, dp: Float, dirs: Int) {
    val d = item.w
    val arm = d * 0.36f
    val cx = item.cx; val cy = item.cy
    val corner = arm * 0.22f
    drawCircle(paint.disc, d / 2, Offset(cx, cy))
    fun rr(l: Float, t: Float, w: Float, h: Float) = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, l + w, t + h, CornerRadius(corner, corner)))
    }
    val cross = Path().apply { op(rr(cx - arm / 2, item.top, arm, d), rr(item.left, cy - arm / 2, d, arm), PathOperation.Union) }
    drawPath(cross, paint.cross(false))
    val half = d / 2 - arm / 2
    fun lit(bit: Int, l: Float, t: Float, w: Float, h: Float) {
        if (dirs and bit != 0) drawPath(Path().apply { op(cross, Path().apply { addRect(Rect(l, t, l + w, t + h)) }, PathOperation.Intersect) }, paint.cross(true))
    }
    lit(TouchGeometry.KEY_UP, cx - arm / 2, item.top, arm, half + arm / 2)
    lit(TouchGeometry.KEY_DOWN, cx - arm / 2, cy, arm, half + arm / 2)
    lit(TouchGeometry.KEY_LEFT, item.left, cy - arm / 2, half + arm / 2, arm)
    lit(TouchGeometry.KEY_RIGHT, cx, cy - arm / 2, half + arm / 2, arm)
    drawPath(cross, paint.edge, style = Stroke(max(1f, 1.5f * dp)))
    // Arrows: a small smooth triangle near each arm's end, pointing out.
    val s = arm * 0.2f
    val reach = d / 2 - arm * 0.42f
    fun arrow(dx: Int, dy: Int) {
        val tx = cx + dx * reach
        val ty = cy + dy * reach
        val path = Path().apply {
            if (dx != 0) { moveTo(tx, ty); lineTo(tx - dx * s, ty - s); lineTo(tx - dx * s, ty + s) }
            else { moveTo(tx, ty); lineTo(tx - s, ty - dy * s); lineTo(tx + s, ty - dy * s) }
            close()
        }
        drawPath(path, paint.arrow)
    }
    arrow(0, -1); arrow(0, 1); arrow(-1, 0); arrow(1, 0)
}

/** The plain font MODERN's words are in. */
private val MODERN_TEXT = TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, textAlign = TextAlign.Center)

/** [item]'s word size on its own: whole font pixels (the pixel font), or px for MODERN's plain one - as big as the
 * button allows, smaller until the word fits. */
private fun DrawScope.labelSize(item: TouchGeometry.Item, measurer: TextMeasurer, font: FontFamily, modern: Boolean): Int {
    val text = tr(item.button.face)
    if (text.isEmpty()) return Int.MAX_VALUE
    val round = item.shape == TouchGeometry.Shape.ROUND
    val room = item.w * (if (round) 0.78f else 0.86f)
    val lines = text.count { it == '\n' } + 1
    if (modern) {
        var px = (if (lines > 1) item.h * 0.55f / lines else item.h * (if (round) 0.42f else 0.5f)).roundToInt()
        while (px > 6 && measurer.measure(text, MODERN_TEXT.copy(fontSize = px.toSp(), lineHeight = (px * 1.05f).toSp())).size.width > room) px--
        return px
    }
    var fp = max(1, if (lines > 1) (item.h * 0.5f / lines / 9f).toInt() else ((item.h * if (round) 0.34f else 0.44f) / 9f).roundToInt())
    fun width() = measurer.measure(text, TextStyle(fontFamily = font, fontSize = (16f * fp).toSp(), lineHeight = (11f * fp).toSp(), textAlign = TextAlign.Center)).size.width
    while (fp > 1 && width() > room) fp--
    return fp
}

/** The D-pad: a pixel cross over a faint disc, an arrow on each arm, a held arm lit. */
private fun DrawScope.drawCross(item: TouchGeometry.Item, paint: PadPaint, step: Float, dirs: Int) {
    val d = item.w
    val arm = (d * 0.36f / step).roundToInt() * step
    val cx = item.cx; val cy = item.cy
    val r = 2 * step
    drawPixelRoundRect(paint.disc, Offset(item.left, item.top), Size(d, d), d / 2, step)
    fun rect(l: Float, t: Float, w: Float, h: Float, inset: Float) =
        pixelRoundRectPath(Size(w - 2 * inset, h - 2 * inset), max(0f, r - inset), step, Offset(l + inset, t + inset))
    fun cross(inset: Float) = Path().apply {
        op(rect(cx - arm / 2, item.top, arm, d, inset), rect(item.left, cy - arm / 2, d, arm, inset), PathOperation.Union)
    }
    val outer = cross(0f)
    val inner = cross(step)
    if (paint.raised) {
        // Its depth: the cross again, a little lower and darker.
        translate(0f, 2 * step) { drawPath(outer, lerp(paint.cross(false), Color.Black, 0.55f)) }
    }
    drawPath(inner, paint.cross(false))
    // A held direction: its arm, from the middle out.
    val half = d / 2 - arm / 2
    fun lit(bit: Int, l: Float, t: Float, w: Float, h: Float) {
        if (dirs and bit != 0) drawPath(Path().apply { op(inner, Path().apply { addRect(Rect(l, t, l + w, t + h)) }, PathOperation.Intersect) }, paint.cross(true))
    }
    lit(TouchGeometry.KEY_UP, cx - arm / 2, item.top, arm, half + arm / 2)
    lit(TouchGeometry.KEY_DOWN, cx - arm / 2, cy, arm, half + arm / 2)
    lit(TouchGeometry.KEY_LEFT, item.left, cy - arm / 2, half + arm / 2, arm)
    lit(TouchGeometry.KEY_RIGHT, cx, cy - arm / 2, half + arm / 2, arm)
    val ring = Path().apply { op(outer, inner, PathOperation.Difference) }
    if (paint.outline) {
        drawPath(Path().apply { op(cross(-step), outer, PathOperation.Difference) }, paint.shadow.copy(alpha = 0.7f))
        drawPath(Path().apply { op(outer, cross(2 * step), PathOperation.Difference) }, paint.edge)
    } else drawPath(ring, if (paint.raised) lerp(paint.cross(false), Color.Black, 0.4f) else paint.edge)
    // Arrows: rows of 5, 3, 1 cells, pointing out.
    val a = max(step, (arm * 0.1f / step).roundToInt() * step)
    val reach = d / 2 - arm * 0.42f
    fun arrow(dx: Int, dy: Int) {
        val tipX = cx + dx * reach
        val tipY = cy + dy * reach
        for (row in 0 until 3) {
            val n = 1 + 2 * row
            val along = row * a
            if (dx != 0) {
                val x = tipX - dx * (along + a)
                drawRect(paint.arrow, Offset(min(x, x + dx * a), tipY - n * a / 2), Size(a, n * a))
            } else {
                val y = tipY - dy * (along + a)
                drawRect(paint.arrow, Offset(tipX - n * a / 2, min(y, y + dy * a)), Size(n * a, a))
            }
        }
    }
    arrow(0, -1); arrow(0, 1); arrow(-1, 0); arrow(1, 0)
}

/** A button's word, with the games' three-way shadow in the pixel font (MODERN: the plain font, a soft one-way shadow),
 * at [size] ([labelSize]'s; null = its own). Caps are 9 font pixels of a 16 em: about a third of a round button's
 * height, nearly half a pill's; two lines (TURBO over its letter) share half the height. */
private fun DrawScope.drawLabel(
    item: TouchGeometry.Item, measurer: TextMeasurer, font: FontFamily, ink: Color, shadow: Color, step: Float,
    modern: Boolean = false, size: Int? = null,
) {
    val text = tr(item.button.face)
    if (text.isEmpty()) return
    val n = size ?: labelSize(item, measurer, font, modern)
    if (modern) {
        val layout = measurer.measure(text, MODERN_TEXT.copy(color = ink, fontSize = n.toSp(), lineHeight = (n * 1.05f).toSp()))
        val tl = Offset(item.cx - layout.size.width / 2f, item.cy - layout.size.height / 2f)
        drawText(layout, color = shadow.copy(alpha = 0.5f), topLeft = tl + Offset(0f, max(1f, n / 14f)))
        drawText(layout, color = ink, topLeft = tl)
        return
    }
    val fp = n
    val layout = measurer.measure(
        text,
        TextStyle(fontFamily = font, fontSize = (16f * fp).toSp(), color = ink, lineHeight = (11f * fp).toSp(), textAlign = TextAlign.Center),
    )
    val x = (item.cx - layout.size.width / 2f).roundToInt().toFloat()
    val y = (item.cy - layout.size.height / 2f).roundToInt().toFloat()
    val k = fp.toFloat()
    for ((dx, dy) in listOf(k to 0f, 0f to k, k to k)) drawText(layout, color = shadow, topLeft = Offset(x + dx, y + dy))
    drawText(layout, color = ink, topLeft = Offset(x, y))
}

// ---- SETTINGS' preview ---------------------------------------------------------------------------------------------

/**
 * The game's screen in miniature with the touch controls on it, as [style] lays them out: the newest savestate's
 * picture ([shot]) or a drawn stand-in for the game, the companion's place on an upright phone, then the controls
 * drawn by the same code the game uses. It follows every change on the page at once. Upright the whole screen would
 * come out small in a short box, so it shows the controls' part of it as wide as the box allows: their area with a
 * slice of what's above and below, the screen cut off where it doesn't fit (the user's call).
 */
@Composable
fun TouchControlsPreview(
    stage: TouchStage, style: TouchControlsStyle, shot: ImageBitmap?, modifier: Modifier = Modifier,
    /** SKIN's pick for this stage's game, drawn in place of the buttons (the game moved into its screen sideways). */
    skin: InstalledSkin? = null,
) {
    val measurer = rememberTextMeasurer(24)
    val font = pixelFontFamily()
    val fit = remember(stage, skin) { skinFit(skin, stage.area.width, stage.area.height, stage.portrait) }
    val skinImage by rememberSkinImage(skin, fit)
    val placed = remember(stage, style) {
        TouchGeometry.place(style, stage.area.width, stage.area.height, stage.dp, stage.portrait, stage.gameAspect, stage.gameBoy, ds = stage.ds)
    }
    Canvas(modifier) {
        val px = gbaPixelPx()
        val body = 3 * px
        // The part of the screen shown: all of it sideways; upright, the controls' area and a slice around it.
        var top = 0f
        var bottom = stage.h
        if (stage.portrait) {
            val slice = stage.w * 0.12f
            top = (stage.area.top - slice).coerceAtLeast(0f)
            bottom = (stage.area.bottom + slice).coerceAtMost(stage.h)
        }
        val k = min((size.width - 2 * body) / stage.w, (size.height - 2 * body) / (bottom - top))
        if (stage.portrait) {
            // Room to spare in the box: show more, around the controls, as far as the screen goes.
            val extra = (size.height - 2 * body) / k - (bottom - top)
            if (extra > 0f) {
                top -= extra / 2
                bottom += extra / 2
                if (top < 0f) { bottom -= top; top = 0f }
                if (bottom > stage.h) { top -= bottom - stage.h; bottom = stage.h }
                top = top.coerceAtLeast(0f)
            }
        }
        val shownH = (bottom - top) * k
        val ox = (size.width - stage.w * k) / 2
        val oy = (size.height - shownH) / 2
        // The phone: a dark body around what's shown, open at an edge where the screen is cut off.
        val cutTop = top > 0f
        val cutBottom = bottom < stage.h
        drawPixelRoundRect(
            Color(0xFF1C1C22),
            Offset(ox - body, oy - if (cutTop) 0f else body),
            Size(stage.w * k + 2 * body, shownH + (if (cutTop) 0f else body) + (if (cutBottom) 0f else body)),
            if (cutTop || cutBottom) px else 5 * px, px,
        )
        clipRect(ox, oy, ox + stage.w * k, oy + shownH) {
            drawRect(Color.Black, Offset(ox, oy), Size(stage.w * k, shownH))
            withTransform({ translate(ox, oy - top * k); scale(k, k, Offset.Zero) }) {
                if (fit != null) {
                    // The game where the skin's screen puts it (sideways), the skin over it.
                    val g = fit.game?.translate(stage.area.topLeft)
                    drawStage(if (g != null) stage.copy(game = g) else stage, shot)
                    translate(stage.area.left, stage.area.top) {
                        if (fit.rep.translucent) {
                            val layer = androidx.compose.ui.graphics.Paint().apply { alpha = style.opacity / 100f }
                            drawContext.canvas.saveLayer(Rect(Offset.Zero, Size(fit.w, fit.h)), layer)
                            drawSkin(fit, skinImage)
                            drawContext.canvas.restore()
                        } else drawSkin(fit, skinImage)
                    }
                    return@withTransform
                }
                drawStage(stage, shot)
                translate(stage.area.left, stage.area.top) {
                    drawTouchControls(placed, PadPaint(style), null, false, measurer, font, alpha = style.opacity / 100f)
                }
            }
        }
    }
}

/** The screen behind the controls: the game ([shot] or the stand-in) and, upright, the companion's block. */
private fun DrawScope.drawStage(stage: TouchStage, shot: ImageBitmap?) {
    val g = stage.game
    if (shot != null) {
        drawImage(
            shot, dstOffset = IntOffset(g.left.roundToInt(), g.top.roundToInt()),
            dstSize = IntSize(g.width.roundToInt(), g.height.roundToInt()), filterQuality = FilterQuality.None,
        )
    } else {
        clipRect(g.left, g.top, g.right, g.bottom) { drawStandIn(g) }
    }
    stage.companion?.let { c ->
        // The companion: its backdrop, a title window and a list, roughly.
        drawRect(Color(0xFF6D8FC7), c.topLeft, c.size)
        val u = c.width / 120f
        drawRect(OptionColors.titleFill, Offset(c.left + 4 * u, c.top + 4 * u), Size(c.width - 8 * u, 12 * u))
        drawRect(OptionColors.listFill, Offset(c.left + 4 * u, c.top + 20 * u), Size(c.width - 8 * u, c.height - 24 * u))
        for (i in 0 until ((c.height - 30 * u) / (10 * u)).toInt().coerceAtMost(8)) {
            drawRect(OptionColors.muted, Offset(c.left + 10 * u, c.top + 26 * u + i * 10 * u), Size(c.width * 0.35f, 3 * u))
        }
    }
}

// ---- EDIT LAYOUT ---------------------------------------------------------------------------------------------------

/**
 * EDIT LAYOUT: the controls at their real size over the game (in game: the game itself shows through; in SETTINGS:
 * [shot] or the stand-in, drawn as the game's screen would be). Drag a control to move it, pinch it or use its
 * bar's - and + to resize it, HIDE takes it off (HIDDEN brings it back); SNAP lines them up on an 8 dp grid. DONE
 * (or BACK) keeps the layout for the way the phone is held ([TouchStage.portrait]); RESET goes back to the preset.
 */
@Composable
fun TouchLayoutEditor(
    stage: TouchStage,
    style: TouchControlsStyle,
    m: GbaTextMetrics,
    onDone: (TouchLayout) -> Unit,
    shot: ImageBitmap? = null,
    /** Draw the game's screen behind (SETTINGS); false = see-through, over the running game. */
    drawBackdrop: Boolean = false,
) {
    val portrait = stage.portrait
    val area = stage.area
    // The layout being edited: every shown control's place, frozen from the current layout on the first change.
    var layout by remember { mutableStateOf(style.layoutFor(portrait)) }
    var selected by remember { mutableStateOf<TouchButton?>(null) }
    var snap by remember { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }
    var hiddenPicker by remember { mutableStateOf(false) }
    // A finger moving a button: the bar stays where it is meanwhile (it would hop out of the button's way otherwise).
    var dragging by remember { mutableStateOf(false) }
    val editStyle = style.withLayout(portrait, layout)
    val placed = remember(editStyle, stage) {
        TouchGeometry.place(editStyle, area.width, area.height, stage.dp, portrait, stage.gameAspect, stage.gameBoy, ds = stage.ds)
    }
    val currentPlaced by rememberUpdatedState(placed)
    val hidden = layout.places.filterValues { it.hidden }.keys.toList()
    val done = { onDone(layout) }
    CompanionBackHandler(onBack = done)

    /** Freezes every shown control where it is now, then applies [change] to [b]'s place. */
    fun edit(b: TouchButton, change: (com.pokedaisy.app.companion.TouchPlace) -> com.pokedaisy.app.companion.TouchPlace) {
        val p = currentPlaced
        val full = p.items.associate { it.button to TouchGeometry.placeOf(it, p.w, p.h, p.dp, layout.places[it.button]?.scale ?: 1f) } +
            layout.places.filterValues { it.hidden }
        val now = full[b] ?: return
        layout = TouchLayout(full + (b to change(now)))
    }

    fun moveTo(b: TouchButton, cx: Float, cy: Float) {
        val p = currentPlaced
        val item = p.item(b) ?: return
        val grid = 8 * p.dp
        val x = if (snap) (cx / grid).roundToInt() * grid else cx
        val y = if (snap) (cy / grid).roundToInt() * grid else cy
        val moved = item.copy(
            cx = x.coerceIn(item.w / 2, max(item.w / 2, p.w - item.w / 2)),
            cy = y.coerceIn(item.h / 2, max(item.h / 2, p.h - item.h / 2)),
        )
        edit(b) { old -> TouchGeometry.placeOf(moved, p.w, p.h, p.dp, old.scale) }
    }

    fun resize(b: TouchButton, factor: Float) = edit(b) { old ->
        old.copy(scale = (old.scale * factor).coerceIn(TouchLayout.MIN_SCALE, TouchLayout.MAX_SCALE))
    }

    val measurer = rememberTextMeasurer(24)
    val font = pixelFontFamily()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Where the bar goes: the top, else the bottom, else the middle - the first with no button under it (the
        // fewest when none is clear). Upright with the buttons over the game, the top row is theirs.
        val frozen = remember(dragging) { placed }
        val barPlaced = if (dragging) frozen else placed
        val density = LocalDensity.current
        val barY = with(density) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val barW = min(560.dp.toPx(), w)
            val barH = 170.dp.toPx()
            val margin = 8.dp.toPx()
            val slots = listOf(margin, h - barH - margin, (h - barH) / 2)
            fun covered(y: Float) = barPlaced.items.sumOf { it ->
                val l = maxOf(it.left + area.left, (w - barW) / 2)
                val r = minOf(it.right + area.left, (w + barW) / 2)
                val t = maxOf(it.top + area.top, y)
                val b = minOf(it.bottom + area.top, y + barH)
                if (r > l && b > t) ((r - l) * (b - t)).toDouble() else 0.0
            }
            (slots.firstOrNull { covered(it) == 0.0 } ?: slots.minBy { covered(it) }).roundToInt()
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(stage) {
                awaitPointerEventScope {
                    while (true) {
                        // A finger down: the control under it (or nothing).
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                        val at = down.position - area.topLeft
                        val p = currentPlaced
                        val hit = p.items.lastOrNull { it.contains(at.x, at.y, 6 * p.dp) }
                        selected = hit?.button
                        if (hit == null) continue
                        val grab = Offset(at.x - hit.cx, at.y - hit.cy)
                        var pinch = 0f
                        dragging = true
                        while (true) {
                            val ev = awaitPointerEvent()
                            val fingers = ev.changes.filter { it.pressed }
                            if (fingers.isEmpty()) { dragging = false; break }
                            ev.changes.forEach { it.consume() }
                            if (fingers.size >= 2) {
                                // Two fingers: the gap between them sizes the control.
                                val gap = (fingers[0].position - fingers[1].position).getDistance()
                                if (pinch > 0f && abs(gap / pinch - 1f) > 0.04f) { resize(hit.button, gap / pinch); pinch = gap }
                                else if (pinch == 0f) pinch = gap
                            } else if (pinch == 0f) {
                                val f = fingers[0].position - area.topLeft
                                moveTo(hit.button, f.x - grab.x, f.y - grab.y)
                            }
                        }
                    }
                }
            },
        ) {
            if (drawBackdrop) {
                drawRect(Color.Black)
                drawStage(stage, shot)
            }
            // The controls' area, marked; the rest dimmed a little so the controls stand out.
            drawRect(Color(0x55000000))
            drawRect(Color(0x66FFFFFF), area.topLeft, area.size, style = Stroke(width = gbaPixelPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            translate(area.left, area.top) {
                drawTouchControls(placed, PadPaint(style), null, false, measurer, font, selected, editing = true)
            }
        }
        // The bar, away from the buttons ([barY]).
        Column(
            Modifier.align(Alignment.TopCenter).offset { IntOffset(0, barY) }.widthIn(max = 560.dp).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OptionTitleWindow(
                if (portrait) tr("EDIT LAYOUT: UPRIGHT") else tr("EDIT LAYOUT: SIDEWAYS"), m,
                actions = { OptionButton(tk("DONE"), m, onClick = done, emphasis = true, sidePadding = 6) },
            )
            Spacer(Modifier.height(m.u * 3))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 3)) {
                OptionButton(if (snap) tk("SNAP ON") else tk("SNAP OFF"), m, onClick = { snap = !snap }, sidePadding = 6)
                if (hidden.isNotEmpty()) OptionButton(tr("HIDDEN ({0})", hidden.size), m, onClick = { hiddenPicker = true }, sidePadding = 6)
                OptionButton(tk("RESET"), m, onClick = { confirmReset = true }, sidePadding = 6)
            }
            val sel = selected?.takeIf { s -> placed.item(s) != null }
            if (sel != null) {
                Spacer(Modifier.height(m.u * 3))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.u * 3),
                    modifier = Modifier
                        .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(m.u.toPx()), OptionColors.titleFill, radius = 2 * m.u.toPx()) }
                        .padding(horizontal = m.u * 8, vertical = m.u * 4),
                ) {
                    GbaText(tr(sel.label), OptionColors.titleText, OptionColors.titleShadow, m)
                    Spacer(Modifier.width(m.u * 4))
                    OptionButton("-", m, onClick = { resize(sel, 1 / 1.1f) }, sidePadding = 6)
                    val pct = ((layout.places[sel]?.scale ?: 1f) * 100).roundToInt()
                    GbaText("$pct%", OptionColors.value, OptionColors.valueShadow, m)
                    OptionButton("+", m, onClick = { resize(sel, 1.1f) }, sidePadding = 6)
                    OptionButton(tk("HIDE"), m, onClick = { edit(sel) { it.copy(hidden = true) }; selected = null }, sidePadding = 6)
                }
            } else {
                // In a window of its own: over a bright game, text on the backdrop can't be read.
                Spacer(Modifier.height(m.u * 3))
                val small = rememberGbaTextMetrics(1f, gameScaled = false)
                Box(
                    Modifier
                        .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(m.u.toPx()), OptionColors.titleFill, radius = 2 * m.u.toPx()) }
                        .padding(horizontal = m.u * 8, vertical = m.u * 4),
                ) {
                    GbaText(tr("Drag to move. Tap to resize or hide."), OptionColors.titleText, OptionColors.titleShadow, small)
                }
            }
        }
        if (hiddenPicker) {
            OptionSelector(
                tk("SHOW AGAIN"), hidden, hidden.first(), { tr(it.label) }, m,
                onPick = { b -> layout = TouchLayout(layout.places - b); hiddenPicker = false },
                onDismiss = { hiddenPicker = false },
            )
        }
        if (confirmReset) {
            OptionConfirm(
                tk("RESET LAYOUT?"),
                if (portrait) tr("Every button goes back to where {0} puts it, upright.", tr(style.preset.label))
                else tr("Every button goes back to where {0} puts it, sideways.", tr(style.preset.label)),
                tk("RESET"), m,
                onConfirm = { layout = TouchLayout(); selected = null; confirmReset = false },
                onDismiss = { confirmReset = false },
            )
        }
    }
}
