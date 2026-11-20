package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pokedaisy.app.overlay.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Border decoding, placement and drawing around a native game picture. No per-frame file or pixel work. */
class GameOverlays(private val store: OverlayStore) {
    var key by mutableStateOf("")
    private data class Art(val key: String, val image: ImageBitmap, val window: Viewport?)
    private data class Loaded(val key: String, val art: Art?)
    private var decoded by mutableStateOf<Art?>(null)

    fun placement(w: Int, h: Int, gameBoy: Boolean, stretch: Boolean = false): OverlayPlacement? {
        val gw = if (gameBoy) 160 else 240
        val gh = if (gameBoy) 144 else 160
        return when (OverlayChoice.of(key)) {
            is OverlayChoice.BuiltIn -> BuiltInFrames.placement(w, h, gw, gh, stretch)
            is OverlayChoice.Imported -> decoded?.takeIf { it.key == key }?.let {
                OverlayGeometry.layout(w, h, it.image.width, it.image.height, it.window, gw, gh, stretch)
            }
            OverlayChoice.None -> null
        }
    }

    fun picture(w: Int, h: Int, gameBoy: Boolean, stretch: Boolean): Rect? = placement(w, h, gameBoy, stretch)?.game?.let {
        Rect(it.x, it.y, it.x + it.w, it.y + it.h)
    }

    /** Loads before drawing (also in portrait, so touch-layout previews know the selected window). */
    @Composable
    fun Prepare(w: Int, h: Int, revision: Int) {
        val wanted = key
        val choice = OverlayChoice.of(wanted) as? OverlayChoice.Imported
        val loaded by produceState<Loaded?>(null, wanted, w, h, revision) {
            value = if (choice == null || w <= 0 || h <= 0) Loaded(wanted, null) else withContext(Dispatchers.IO) {
                val art = runCatching {
                    val layer = store.get(choice.id)?.layerFor(w.toFloat() / h) ?: return@runCatching null
                    val image = SkinImages.load(layer.file, w, h) ?: return@runCatching null
                    val window = layer.viewport ?: IntArray(image.width * image.height).let { pixels ->
                        image.readPixels(pixels)
                        OverlayWindow.find(pixels, image.width, image.height)
                    }
                    Art(wanted, image, window)
                }.getOrNull()
                Loaded(wanted, art)
            }
        }
        // Picture and art observe one snapshot. Late results from a previous selection cannot move the game.
        val ready = loaded
        SideEffect { ready?.takeIf { it.key == key }?.let { decoded = it.art } }
    }

    @Composable
    fun Artwork(w: Int, h: Int, gameBoy: Boolean, stretch: Boolean = false, modifier: Modifier = Modifier) {
        val choice = OverlayChoice.of(key)
        if (choice is OverlayChoice.BuiltIn) {
            GameBorder(choice.style, w, h, gameBoy, modifier)
        } else decoded?.takeIf { it.key == key }?.let { art ->
            val placed = placement(w, h, gameBoy, stretch) ?: return@let
            Canvas(modifier) { drawGameOverlay(art.image, placed) }
        }
    }
}

/** Native interop clears what was drawn behind its surface. Paint backgrounds around that surface instead. */
internal fun DrawScope.drawGameOverlay(image: ImageBitmap, placed: OverlayPlacement) {
    val r = placed.overlay
    val draw: DrawScope.() -> Unit = {
        drawImage(image, dstOffset = IntOffset(r.x.roundToInt(), r.y.roundToInt()),
            dstSize = IntSize(r.w.roundToInt(), r.h.roundToInt()), filterQuality = FilterQuality.Medium)
    }
    if (placed.onTop) draw() else placed.game.let { game ->
        clipRect(game.x, game.y, game.x + game.w, game.y + game.h, ClipOp.Difference, draw)
    }
}
