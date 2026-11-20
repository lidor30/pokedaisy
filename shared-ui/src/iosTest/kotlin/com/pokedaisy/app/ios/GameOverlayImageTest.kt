package com.pokedaisy.app.ios

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.pokedaisy.app.companion.ui.drawGameOverlay
import com.pokedaisy.app.overlay.Box
import com.pokedaisy.app.overlay.OverlayPlacement
import kotlin.test.*

class GameOverlayImageTest {
    @Test fun opaqueBackgroundLeavesTheNativePictureUntouched() {
        val source = ImageBitmap(8,4)
        val color = 0xFF642882.toInt()
        Canvas(source).drawRect(Rect(0f,0f,8f,4f), Paint().apply { this.color = Color(color) })
        for (onTop in listOf(false,true)) {
            val result = ImageBitmap(8,4)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(result), Size(8f,4f)) {
                drawGameOverlay(source, OverlayPlacement(Box(0f,0f,8f,4f),Box(2f,1f,4f,2f),onTop))
            }
            val pixels = IntArray(32);result.readPixels(pixels)
            for (y in 0 until 4) for (x in 0 until 8) {
                val want = if (!onTop && x in 2..5 && y in 1..2) 0 else color
                assertEquals(want,pixels[y*8+x],"$onTop pixel $x,$y")
            }
        }
    }
}
