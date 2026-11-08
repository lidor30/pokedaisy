package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import kotlin.math.roundToInt

/**
 * Frames/spacing are sized in "GBA pixels" ([u], a whole number of physical
 * pixels so 1-2u lines stay crisp), following the real bag screen's 2px frame
 * lines. Text is a bit larger than 1:1 with the frame (the game's 16px em
 * would be ~20dp here, which read too small on the handheld): ~1.25x that,
 * rounded so each font pixel is still a whole number of screen pixels (the
 * pixel font's strokes go uneven otherwise); [fontPixel] is one of the font's own pixels (for the GBA drop
 * shadow and the drawn cursor) and [lineHeight] one line of text. Shared by
 * the Items tab (the bag screen) and the Settings tab (the OPTION screen).
 */
class GbaTextMetrics(
    val px: Int,
    val u: Dp,
    val fontSize: TextUnit,
    val fontPixel: Float,
    val lineHeight: Dp,
    val rowHeight: Dp,
)

/**
 * The companion's text a step smaller (-1): a one-screen device with a tall screen (SidePanel: the RP6's, the RP Nova's), where the
 * companion lays out like the Thor's bottom screen and its main text read large. Only text bigger than the 1x secondary
 * text shrinks - one font pixel, never below that size - so the pixel font stays crisp; GBA pixels (paddings) don't change.
 */
val LocalTextStep = androidx.compose.runtime.staticCompositionLocalOf { 0 }

/**
 * The top screen's activities (Library, Settings, setup) on a small window - a phone held upright (narrower than
 * [COMPACT_WINDOW_WIDTH_DP]) or sideways (shorter than [COMPACT_WINDOW_HEIGHT_DP]): their main text a step smaller
 * ([LocalTextStep]), so paragraphs wrap less and a pop-up's buttons stay on screen. The Thor's top screen is neither.
 */
@Composable
fun CompactTextOnSmallWindows(content: @Composable () -> Unit) {
    // The room the activity's content gets (its window, less the system bars), measured: ui-preview has no Configuration.
    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
        val small = maxWidth < COMPACT_WINDOW_WIDTH_DP.dp || maxHeight < COMPACT_WINDOW_HEIGHT_DP.dp
        androidx.compose.runtime.CompositionLocalProvider(LocalTextStep provides if (small) -1 else LocalTextStep.current, content = content)
    }
}

/**
 * A list that should show more where the screen has room (the Thor's companion: SETTINGS, GUIDE, DEX): the main text a font
 * pixel smaller ([LocalTextStep] -1) where that still leaves three screen pixels a font pixel - so not on a side panel's two,
 * nor where the step is already taken (a short screen, the one-screen window). [LocalDenseText] keeps the subtitles from
 * going below the main text's new size ([subtitleMetrics]).
 */
@Composable
fun DenseText(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val k = with(density) { (20.dp.toPx() / 16f).roundToInt().coerceAtLeast(2) }
    val apply = LocalTextStep.current == 0 && k >= 3
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTextStep provides if (apply) -1 else LocalTextStep.current,
        LocalDenseText provides apply,
        content = content,
    )
}

/** Inside a [DenseText] that took the step. */
val LocalDenseText = androidx.compose.runtime.staticCompositionLocalOf { false }

const val COMPACT_WINDOW_WIDTH_DP = 500
const val COMPACT_WINDOW_HEIGHT_DP = 380

/** [textScale]: text size relative to the frame's GBA pixel - 1.25 for the
 * main lists, 1 for denser secondary text (timestamps, paths, captions).
 * [wholePixels] false allows sizes between the whole-pixel steps (at the cost
 * of slightly uneven strokes) - the steps are 33% apart at typical densities. */
@Composable
fun rememberGbaTextMetrics(textScale: Float = 1.25f, wholePixels: Boolean = true, gameScaled: Boolean = true): GbaTextMetrics {
    val density = LocalDensity.current
    // A game font with smaller letters than Pixel Operator's (Gen 1's 7px caps vs 9) is set larger -
    // except where a layout is sized to the line ([gameScaled] false: the tab chips, battle INFO).
    val gameScale = if (gameScaled) com.pokedaisy.app.companion.ui.theme.LocalGameTextScale.current else 1f
    val step = LocalTextStep.current
    return remember(density, textScale, wholePixels, gameScale, step) {
        with(density) {
            val k = (20.dp.toPx() / 16f).roundToInt().coerceAtLeast(2)
            val scale = textScale * gameScale
            // ~textScale x, normally rounded to a whole number of screen pixels per font pixel.
            var perPixel = if (wholePixels) (k * scale).roundToInt().coerceAtLeast(1).toFloat() else k * scale
            // A big one-screen device ([LocalTextStep]): the bigger-than-1x text a font pixel down, not below 1x's.
            if (step < 0 && scale > 1f) perPixel = maxOf(perPixel + step, k.toFloat())
            val fontPx = if (wholePixels) 16f * perPixel else (16f * perPixel).roundToInt().toFloat()
            GbaTextMetrics(k, k.toDp(), fontPx.toSp(), fontPx / 16f, fontPx.toDp(), (fontPx * 1.25f).toDp())
        }
    }
}

fun gbaTextStyle(font: FontFamily, m: GbaTextMetrics, scaleX: Float = 1f) = TextStyle(
    fontFamily = font,
    fontSize = m.fontSize,
    textGeometricTransform = if (scaleX == 1f) null else TextGeometricTransform(scaleX = scaleX),
    lineHeight = m.fontSize,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/**
 * GBA-style text: the game draws its shadow one pixel right, down AND
 * diagonally (a thick bottom-right edge), which a single Compose Shadow can't
 * do - so three offset copies sit under the text. They're drawn from the text's own
 * layout ([drawBehind]): three more Text nodes laid the same string out three more times,
 * most of a weak tablet's time opening a tab (2026-11-08).
 */
@Composable
fun GbaText(
    text: String,
    color: Color,
    shadow: Color,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    /** Drawn twice, half a font pixel apart ([boldOffsetPx]): heavier strokes for a thin game font. */
    bold: Boolean = false,
    /** Letters this much narrower (null: the game font's own, [gameFontScaleX]). */
    scaleX: Float? = null,
) {
    val scaleX = scaleX ?: gameFontScaleX(m)
    val style = gbaTextStyle(pixelFontFamily(), m, scaleX)
    val k = m.fontPixel.roundToInt()
    val b = if (bold) boldOffsetPx(m.fontPixel * scaleX) else 0
    // The layout the text was given, for its copies; set before each draw (layout comes first), read only there.
    val laid = remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
    Box(modifier) {
        Text(
            text, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { laid[0] = it },
            modifier = Modifier.drawBehind {
                val layout = laid[0] ?: return@drawBehind
                if (shadow.alpha > 0f) for ((dx, dy) in listOf(k to 0, 0 to k, k to k)) {
                    drawText(layout, color = shadow, topLeft = Offset((dx + b).toFloat(), dy.toFloat()))
                }
                if (b > 0) drawText(layout, color = color, topLeft = Offset(b.toFloat(), 0f))
            },
        )
    }
}

/**
 * [GbaText] in more than one colour, wrapping as one text: each part is (text, colour, its shadow) - WHAT'S NEW's
 * red lead-in before the grey rest of the line.
 */
@Composable
fun GbaRichText(
    parts: List<Triple<String, Color, Color>>,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val style = gbaTextStyle(pixelFontFamily(), m, gameFontScaleX(m))
    val k = m.fontPixel.roundToInt()
    fun build(shadow: Boolean) = androidx.compose.ui.text.buildAnnotatedString {
        for ((text, color, shade) in parts) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = if (shadow) shade else color))
            append(text)
            pop()
        }
    }
    val shadow = remember(parts) { build(true) }
    val main = remember(parts) { build(false) }
    Box(modifier) {
        for ((dx, dy) in listOf(k to 0, 0 to k, k to k)) {
            Text(shadow, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.offset { IntOffset(dx, dy) })
        }
        Text(main, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** How far right [GbaText]'s bold copy goes: half a font pixel ([pixelWidthPx] screen pixels wide), at least one. */
fun boldOffsetPx(pixelWidthPx: Float): Int = (pixelWidthPx * 0.5f).roundToInt().coerceAtLeast(1)

/** Labels on tabs and buttons are bold in a thin game font (Gen 1's 1px strokes). */
@Composable
fun gameBoldLabels(): Boolean = com.pokedaisy.app.companion.ui.theme.LocalGameFont.current != null

/**
 * [LocalGameFontWidth] at [m]'s size: on whole-pixel text, rounded so a font pixel is a whole
 * number of screen pixels wide too (4 tall -> 3 wide, 5 -> 4, 3 -> 2).
 */
@Composable
fun gameFontScaleX(m: GbaTextMetrics): Float = gameFontScaleX(m.fontPixel)

/** [gameFontScaleX] for text whose font pixel is [p] screen pixels tall. */
@Composable
fun gameFontScaleX(p: Float): Float {
    val w = com.pokedaisy.app.companion.ui.theme.LocalGameFontWidth.current
    if (w >= 1f) return 1f
    val whole = p.roundToInt()
    if (whole < 1 || kotlin.math.abs(p - whole) > 0.01f) return w
    return (whole * w).roundToInt().coerceAtLeast(1) / whole.toFloat()
}

fun List<Pair<Color, Int>>.inPx(px: Float) = map { (color, u) -> color to u * px }

/**
 * Nested pixel-cornered rects, outermost layer first, then [fill]. [openRight] runs
 * the shape past the right edge and clips it, dropping the right-hand frame
 * (a folder tab that continues into the window beside it); [openBottom],
 * [openTop] and [openLeft] do the same downwards / upwards / leftwards.
 */
fun DrawScope.drawLayeredBox(
    layers: List<Pair<Color, Float>>,
    fill: Color,
    radius: Float,
    openRight: Boolean = false,
    openBottom: Boolean = false,
    openTop: Boolean = false,
    openLeft: Boolean = false,
    /** A button's two-tone face ([drawTwoTone]) instead of plain [fill]. */
    twoTone: Boolean = false,
    /** The two-tone face's lower colour, if not [twoToneLow]'s. */
    low: Color? = null,
) {
    val run = radius + layers.sumOf { it.second.toDouble() }.toFloat() + 1f
    val left = if (openLeft) run else 0f
    val extraW = (if (openRight) run else 0f) + left
    val top = if (openTop) run else 0f
    val extraH = (if (openBottom) run else 0f) + top
    clipRect {
        var inset = 0f
        var r = radius
        for ((color, width) in layers) {
            drawPixelRoundRect(
                color, Offset(inset - left, inset - top), Size(size.width + extraW - 2 * inset, size.height + extraH - 2 * inset),
                r.coerceAtLeast(0f),
            )
            inset += width
            r -= width
        }
        val at = Offset(inset - left, inset - top)
        val box = Size(size.width + extraW - 2 * inset, size.height + extraH - 2 * inset)
        if (twoTone) {
            drawTwoTone(fill, low ?: twoToneLow(fill), layers.lastOrNull()?.second ?: 1f) { color -> drawPixelRoundRect(color, at, box, r.coerceAtLeast(0f)) }
        } else drawPixelRoundRect(fill, at, box, r.coerceAtLeast(0f))
    }
}

/** A two-tone button's lower colour, for a window-coloured one: [fill] a step down, on a light or a dark look alike. */
fun twoToneLow(fill: Color): Color = androidx.compose.ui.graphics.lerp(fill, Color(0xFF40485A), 0.3f)

/**
 * HeartGold's button face, every companion button's: [top] down to just under half way, two lines stepping between the
 * colours ([line] tall each) so the edge looks soft, then [low]. [paint] fills the button's shape in a colour, so the rounded
 * corners stay the shape's.
 */
fun DrawScope.drawTwoTone(top: Color, low: Color, line: Float, paint: DrawScope.(Color) -> Unit) {
    val split = size.height * 0.47f
    paint(low)
    clipRect(bottom = split) { paint(top) }
    clipRect(top = split, bottom = split + line) { paint(androidx.compose.ui.graphics.lerp(top, low, 0.35f)) }
    clipRect(top = split + line, bottom = split + line * 2) { paint(androidx.compose.ui.graphics.lerp(top, low, 0.7f)) }
}

/**
 * A one-line label wider than its room drawn narrower instead of cut short: its letters lose whole font pixels of width
 * (4 wide -> 3 -> 2 -> 1, the height kept, like [FittedGbaText]), centred. A plain layout, so a row measuring its
 * children's intrinsic sizes (the tab bar) can hold it.
 */
internal fun Modifier.narrowToFit(m: GbaTextMetrics): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    val room = constraints.maxWidth
    val scale = if (p.width <= room) 1f else {
        val steps = (m.fontPixel.toInt() - 1 downTo 1).map { it / m.fontPixel }
        steps.firstOrNull { p.width * it <= room } ?: (room.toFloat() / p.width)
    }
    val w = minOf(p.width, room).coerceAtLeast(constraints.minWidth)
    layout(w, p.height) {
        p.placeWithLayer((w - p.width) / 2, 0) { scaleX = scale }
    }
}
