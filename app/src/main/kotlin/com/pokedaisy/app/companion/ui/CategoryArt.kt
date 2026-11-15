package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import com.pokedaisy.app.companion.data.RomCategory

/**
 * The library's cover for a game with no art: its kind ([RomCategory]) as its initials in a 3 x 5 pixel font over
 * diagonal stripes, in the category's colour - GB grey, GBC yellow, GBA purple, NDS turquoise, HACK red. All on one
 * grid, so every block is the same whole number of screen pixels at any size.
 */
@Composable
fun CategoryArt(category: RomCategory, modifier: Modifier = Modifier) {
    val look = CategoryLooks.of(category)
    Canvas(modifier = modifier) { clipRect {
        val cell = kotlin.math.floor(minOf(size.width, size.height) / GRID).coerceAtLeast(1f)
        val left = kotlin.math.floor((size.width - cell * GRID) / 2f)
        val top = kotlin.math.floor((size.height - cell * GRID) / 2f)
        // The backdrop: diagonal stripes in two shades, over the whole box (not just the grid).
        drawRect(look.back)
        val cols = kotlin.math.ceil(size.width / cell).toInt()
        val rows = kotlin.math.ceil(size.height / cell).toInt()
        for (y in 0 until rows) for (x in 0 until cols) {
            if ((x + y) % 4 < 1) drawRect(look.stripe, Offset(x * cell, y * cell), Size(cell, cell))
        }
        fun plot(gx: Int, gy: Int, c: Color) = drawRect(c, Offset(left + gx * cell, top + gy * cell), Size(cell, cell))
        // The initials, centred, a shadow one cell below (a diagonal one tangled with the one-cell strokes).
        val glyphs = category.label.map { PixelFont.glyph(it) }
        val width = glyphs.sumOf { it.first().length } + glyphs.size - 1
        val y0 = (GRID - PixelFont.HEIGHT) / 2
        for (pass in 0..1) {
            var x = (GRID - width) / 2
            for (g in glyphs) {
                g.forEachIndexed { r, line ->
                    line.forEachIndexed { c, ch ->
                        if (ch == '1') {
                            if (pass == 0) plot(x + c, y0 + r + 1, look.shadow) else plot(x + c, y0 + r, look.text)
                        }
                    }
                }
                x += g.first().length + 1
            }
        }
    } }
}

// Wide enough for the widest name (HACK: 15 cells) with a cell or more to spare each side.
private const val GRID = 18

/** A category's colours: the backdrop and its stripes, the letters and their shadow. */
class CategoryLook(val back: Color, val stripe: Color, val text: Color, val shadow: Color)

object CategoryLooks {
    private fun c(rgb: Long) = Color(0xFF000000 or rgb)

    private val GB = CategoryLook(c(0x8A8A94), c(0x7A7A84), c(0xFFFFFF), c(0x3A3A42))
    private val GBC = CategoryLook(c(0xF5C524), c(0xE6B312), c(0xFFFFFF), c(0x8A5A00))
    private val GBA = CategoryLook(c(0x6A35A8), c(0x5A2A94), c(0xFFFFFF), c(0x24093F))
    private val NDS = CategoryLook(c(0x1FB8AA), c(0x17A396), c(0xFFFFFF), c(0x0A4C46))
    private val HACK = CategoryLook(c(0xD9302E), c(0xC22422), c(0xFFFFFF), c(0x5C0A0A))

    fun of(category: RomCategory): CategoryLook = when (category) {
        RomCategory.GB -> GB
        RomCategory.GBC -> GBC
        RomCategory.GBA -> GBA
        RomCategory.NDS -> NDS
        RomCategory.HACK -> HACK
    }
}

/** The 3 x 5 pixel letters the category names need (N is 4 wide). */
private object PixelFont {
    const val HEIGHT = 5

    private val GLYPHS = mapOf(
        'A' to listOf("010", "101", "111", "101", "101"),
        'B' to listOf("110", "101", "110", "101", "110"),
        'C' to listOf("011", "100", "100", "100", "011"),
        'D' to listOf("110", "101", "101", "101", "110"),
        'G' to listOf("011", "100", "101", "101", "011"),
        'H' to listOf("101", "101", "111", "101", "101"),
        'K' to listOf("101", "101", "110", "101", "101"),
        'N' to listOf("1001", "1101", "1011", "1001", "1001"),
        'S' to listOf("011", "100", "010", "001", "110"),
    )

    fun glyph(c: Char): List<String> = GLYPHS[c] ?: listOf("000", "000", "000", "000", "000")
}
