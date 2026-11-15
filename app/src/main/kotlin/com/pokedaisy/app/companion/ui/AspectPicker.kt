package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlin.math.min
import kotlin.math.roundToInt

/** SETTINGS > ASPECT's value ([com.pokedaisy.app.Prefs.gameAspect]), untranslated (the rows translate it). */
fun aspectLabel(aspect: GameAspect) = aspect.label

/** DS RESOLUTION's value: ORIGINAL (the DS's own, [scale] 1) or how many times it ("4x"). */
fun dsResolutionLabel(scale: Int) = if (scale <= 1) tk("ORIGINAL") else "${scale}x"

/** DS RESOLUTION's choices, scale 1 first ([com.pokedaisy.app.companion.DS_RESOLUTION_MAX] of them). */
fun dsResolutionLabels() = (1..com.pokedaisy.app.companion.DS_RESOLUTION_MAX).map(::dsResolutionLabel)

/**
 * The top-screen Settings' ASPECT picker: ORIGINAL, STRETCH and WIDE side by
 * side, each a small picture of the top screen ([screenAspect] = width / height)
 * with the game as it would sit there - [shot] (the newest savestate
 * thumbnail) or, with none, a drawn stand-in whose round sun shows the
 * stretch. WIDE is always the stand-in, drawn wider (a thumbnail has only the
 * GBA's 240 px). [statusBar] adds the STATUS BAR strip. Tapping one picks it.
 */
@Composable
fun AspectPicker(
    aspect: GameAspect,
    screenAspect: Float,
    statusBar: Boolean,
    shot: ImageBitmap?,
    m: GbaTextMetrics,
    onPick: (GameAspect) -> Unit,
    onDismiss: () -> Unit,
) {
    val small = rememberGbaTextMetrics(textScale = 1f)
    OptionOverlay(onDismiss, Modifier.widthIn(max = 840.dp)) {
        Column {
            OptionTitleWindow(tk("ASPECT"), m, onBack = onDismiss)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(m.u * 2),
                ) {
                    GameAspect.entries.forEach { opt ->
                        AspectChoice(
                            opt, selected = opt == aspect, screenAspect, statusBar, shot, m, small,
                            Modifier.weight(1f).fillMaxHeight(),
                        ) { onPick(opt) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectChoice(
    aspect: GameAspect,
    selected: Boolean,
    screenAspect: Float,
    statusBar: Boolean,
    shot: ImageBitmap?,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val u = m.u
    Column(
        modifier
            .drawBehind { if (selected) drawPixelRoundRect(OptionColors.rowSelected, radius = 3 * u.toPx()) }
            .soundClickable(onClick = onClick)
            .padding(u * 6),
    ) {
        ScreenPreview(aspect, statusBar, shot, Modifier.fillMaxWidth().aspectRatio(screenAspect))
        Spacer(Modifier.height(u * 4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) GbaText(tr(aspectLabel(aspect)), OptionColors.value, OptionColors.valueShadow, m)
            else GbaText(tr(aspectLabel(aspect)), OptionColors.label, OptionColors.labelShadow, m)
            // EXPAND is still not perfect (the user's call): BETA beside its name, as the settings rows have it.
            if (aspect == GameAspect.WIDE) OptionBadge(tk("BETA"), m, Modifier.padding(start = u * 6))
        }
        GbaText(
            when (aspect) {
                GameAspect.ORIGINAL -> tr("The GBA's 3:2, bars at the sides")
                GameAspect.STRETCH -> tr("Fills the screen, drawn wider")
                GameAspect.WIDE -> tr("More of the map around the game (not every game yet)")
            },
            OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 3,
        )
    }
}

/** The top screen in miniature: black, the game placed as [com.pokedaisy.app.GameStageLayout] places it. */
@Composable
internal fun ScreenPreview(aspect: GameAspect, statusBar: Boolean, shot: ImageBitmap?, modifier: Modifier) {
    val stretch = aspect != GameAspect.ORIGINAL   // WIDE takes the whole screen too, with more game in it
    Canvas(modifier) {
        val px = gbaPixelPx()
        drawPixelRoundRect(Color.Black, radius = 3 * px)
        val frame = 2 * px
        val area = Rect(frame, frame, size.width - frame, size.height - frame)
        val barH = if (statusBar) area.height / 12f else 0f
        val gameW = if (stretch) area.width else min(area.width, (area.height - barH) * GBA_ASPECT)
        val gameH = if (stretch) area.height - barH else gameW / GBA_ASPECT
        val left = area.left + (area.width - gameW) / 2
        val top = area.top + (area.height - barH - gameH) / 2
        if (statusBar) {
            drawRect(OptionColors.titleFill, Offset(left, top), Size(gameW, barH))
            val dash = barH / 3
            listOf(0.04f to 0.3f, 0.62f to 0.12f, 0.8f to 0.16f).forEach { (x, w) ->
                drawRect(OptionColors.muted, Offset(left + gameW * x, top + dash), Size(gameW * w, dash))
            }
        }
        val game = Rect(left, top + barH, left + gameW, top + barH + gameH)
        if (aspect == GameAspect.WIDE) {
            clipRect(game.left, game.top, game.right, game.bottom) { drawStandIn(game, wide = true) }
        } else if (shot != null) {
            drawImage(
                shot,
                dstOffset = IntOffset(game.left.roundToInt(), game.top.roundToInt()),
                dstSize = IntSize(game.width.roundToInt(), game.height.roundToInt()),
                filterQuality = FilterQuality.None,
            )
        } else {
            clipRect(game.left, game.top, game.right, game.bottom) { drawStandIn(game) }
        }
        drawLayeredFrame(listOf(OptionColors.frameDark to px, OptionColors.frameLight to px), radius = 3 * px)
    }
}

/**
 * A plain GBA-like scene in 240x160 game pixels, scaled into [r]: sky, sun, a house, grass, a text box.
 * [wide] draws it at [r]'s own shape with square game pixels, as WIDE does: the scene runs on past
 * the GBA's 240 px (a tree, a second house), the text box stays in the middle 240.
 */
internal fun DrawScope.drawStandIn(r: Rect, wide: Boolean = false) {
    val s = r.height / 160f
    val w = if (wide) r.width / s else 240f   // the scene's width in game pixels
    val ox = (w - 240f) / 2                    // where the GBA's own 240 px start
    translate(r.left, r.top) {
        scale(if (wide) s else r.width / 240f, s, pivot = Offset.Zero) {
            fun box(x: Float, y: Int, bw: Float, h: Int, c: Long) =
                drawRect(Color(c), Offset(x, y.toFloat()), Size(bw, h.toFloat()))
            fun box(x: Int, y: Int, bw: Int, h: Int, c: Long) = box(ox + x, y, bw.toFloat(), h, c)
            box(0f, 0, w, 96, 0xFF78C8F0)
            drawCircle(Color(0xFFF8E070), radius = 16f, center = Offset(ox + 196f, 30f))
            box(0f, 80, w, 16, 0xFF387838)
            box(0f, 96, w, 64, 0xFF88D070)
            box(104, 96, 32, 64, 0xFFE8D8A0)
            box(26, 44, 68, 18, 0xFFC84848)
            box(32, 62, 56, 34, 0xFFF8F0E0)
            box(54, 76, 12, 20, 0xFF8C5A30)
            if (wide) {
                // Past the GBA's edges: a tree on the left, a second house on the right.
                drawCircle(Color(0xFF2F7F3A), radius = 14f, center = Offset(ox - 20f, 70f))
                box(-24, 80, 8, 18, 0xFF8C5A30)
                box(252, 52, 56, 14, 0xFF4878C8)
                box(256, 66, 48, 30, 0xFFF8F0E0)
                box(274, 78, 12, 18, 0xFF8C5A30)
            }
            box(4, 116, 232, 40, 0xFF5070A0)
            box(7, 119, 226, 34, 0xFFFFFFFF)
            box(16, 126, 150, 7, 0xFF8C8C94)
            box(16, 139, 104, 7, 0xFF8C8C94)
        }
    }
}

private const val GBA_ASPECT = 240f / 160f
