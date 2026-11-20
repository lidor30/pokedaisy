package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pokedaisy.app.overlay.BuiltInFrames
import com.pokedaisy.app.overlay.Frame
import com.pokedaisy.app.overlay.Sprite
import kotlin.math.roundToInt

/** Bake static border art once per size/style, rather than doing pixel work on game frames. */
internal fun borderImage(frame: Frame): ImageBitmap {
    val image = ImageBitmap(frame.w, frame.h)
    val canvas = Canvas(image)
    val paint = Paint().apply { isAntiAlias = false }
    for (y in 0 until frame.h) {
        var x = 0
        while (x < frame.w) {
            val color = frame.argb[y * frame.w + x]
            val start = x++
            while (x < frame.w && frame.argb[y * frame.w + x] == color) x++
            if (color ushr 24 != 0) {
                paint.color = Color(color)
                canvas.drawRect(Rect(start.toFloat(), y.toFloat(), x.toFloat(), y + 1f), paint)
            }
        }
    }
    return image
}

/** Shared art around a native picture. The host places its picture/HUD with [BuiltInFrames.placement]. */
@Composable
fun GameBorder(style: BuiltInFrames.Style, width: Int, height: Int, gameBoy: Boolean, modifier: Modifier = Modifier) {
    val gameW = if (gameBoy) 160 else 240
    val gameH = if (gameBoy) 144 else 160
    val frame = remember(style, width, height, gameBoy) {
        BuiltInFrames.render(style, width, height, gameW, gameH, Sprite(LOGO_ROWS, LOGO_PALETTE.mapValues { it.value.toArgb() }))
    }
    val image = remember(frame) { borderImage(frame) }
    val r = BuiltInFrames.placement(width, height, gameW, gameH).overlay
    Canvas(modifier) {
        drawImage(image, dstOffset = IntOffset(r.x.roundToInt(), r.y.roundToInt()),
            dstSize = IntSize(r.w.roundToInt(), r.h.roundToInt()), filterQuality = FilterQuality.None)
    }
}
