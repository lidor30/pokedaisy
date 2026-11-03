package com.pokedaisy.app.companion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.ui.theme.nightGlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The PokéDaisy theme's backdrop, the website's (website/src/layouts/Base.astro's
 * `.backdrop`): its light page colour, three soft blobs of colour (peach, yellow,
 * blue) drifting over 30 s, a faint pixel dot grid, and with [logos] (the Library)
 * the PokeDaisy mark floating up and down and turning in steps, the site's
 * "petals" at the site's places. A THEME ([com.pokedaisy.app.companion.ui.theme.ThemeSpec]) gives the page, the blobs
 * and the floating picture its own; dark mode turns them into its night page and glows.
 */
@Composable
fun DaisyBackdrop(logos: Boolean = false) {
    val clock = rememberInfiniteTransition(label = "daisy-backdrop")
    // Seconds into a 10-minute loop: every drift and turn below is a whole number of times round it.
    val t by clock.animateFloat(
        0f, LOOP_S, infiniteRepeatable(tween((LOOP_S * 1000).toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "daisy-clock",
    )
    // Dark mode: the same page at night - deep ink, the blobs as dim glows, the dots and logos fainter. A theme with
    // a dark page of its own (HALLOWEEN) keeps it then, a little deeper.
    val theme = com.pokedaisy.app.companion.ui.theme.AppColors.theme
    val night = com.pokedaisy.app.companion.ui.theme.DarkMode.on
    val dark = night || theme.darkPage
    val plain = theme.hue == null && !theme.darkPage
    val page = when {
        theme.darkPage -> if (night) androidx.compose.ui.graphics.lerp(theme.page, NIGHT_PAGE, 0.4f) else theme.page
        night -> if (plain) NIGHT_PAGE else OptionColors.tint(NIGHT_PAGE)
        else -> theme.page
    }
    val colors = when {
        plain -> if (night) NIGHT_BLOBS.map { it.color } else BLOBS.map { it.color }
        night && !theme.darkPage -> theme.blobs.map { it.nightGlow() }
        else -> theme.blobs
    }
    val blobs = colors.mapIndexed { i, c -> BLOB_PLACES[i % BLOB_PLACES.size].let { b -> Blob(b.x, b.y, b.yw, b.r, c, b.delay) } }
    val logo = motifBitmap(theme.motif)
    Canvas(Modifier.fillMaxSize()) {
        drawRect(page)
        // vw / vh like the site's CSS; the blobs size by the longer side so they stay soft on a phone too.
        val vw = size.width / 100f
        val vh = size.height / 100f
        val big = max(size.width, size.height) / 100f
        for (b in blobs) {
            // CSS drift: ease-in-out to translate(6vw, 4vh) scale(1.08) and back, 30 s each way.
            val p = (1 - cos(PI * (((t + b.delay) / 30f) % 2f)).toFloat()) / 2f
            val center = Offset(b.x * vw + 6 * vw * p, b.y * vh + b.yw * big + 4 * vh * p)
            val r = b.r * big * (1 + 0.08f * p)
            // blur(80px) + opacity .55: the colour fading out past the blob's own edge.
            val reach = r + 80.dp.toPx()
            drawCircle(
                Brush.radialGradient(
                    0f to b.color.copy(alpha = 0.55f), r / reach * 0.55f to b.color.copy(alpha = 0.5f),
                    1f to b.color.copy(alpha = 0f), center = center, radius = reach,
                ),
                radius = reach, center = center,
            )
        }
        drawRect(dotBrush(1.dp.toPx().roundToInt().coerceAtLeast(1), 12.dp.toPx().roundToInt().coerceAtLeast(4), dark))
        if (logos) {
            for (petal in PETALS) {
                // The site's float: up 60 px and back while turning once, in 48 steps.
                val phase = floor((((t + petal.delay) / petal.duration) % 1f) * 48f) / 48f
                val lift = -60.dp.toPx() * (1 - abs(2 * phase - 1))
                // A 16-pixel motif at twice the cell, as big as the 32-pixel mark.
                val cell = max(1, (petal.scale * 2.dp.toPx() * 32 / logo.width).roundToInt())
                val side = cell * logo.width
                val x = petal.x / 100f * size.width
                val y = petal.y / 100f * size.height + lift
                translate(x, y) {
                    rotate(phase * 360f, pivot = Offset(side / 2f, side / 2f)) {
                        drawImage(
                            logo, dstOffset = IntOffset.Zero, dstSize = IntSize(side, side),
                            alpha = when {
                                theme.motif != null -> if (dark) MOTIF_NIGHT_ALPHA else MOTIF_ALPHA
                                dark -> NIGHT_LOGO_ALPHA
                                else -> LOGO_ALPHA
                            },
                            filterQuality = FilterQuality.None,
                        )
                    }
                }
            }
        }
    }
}

private const val LOOP_S = 600f

/** The site's page colour (--bg). */
private val PAGE = Color(0xFFF7F6FB)

/** The site's petals stand out a touch more here, on a screen with less going on. */
private const val LOGO_ALPHA = 0.16f

/** Dark mode's page: a blue-black ink, and the petals a little quieter on it. */
private val NIGHT_PAGE = Color(0xFF12141C)
private const val NIGHT_LOGO_ALPHA = 0.11f

/** A blob: centre at [x] vw, [y] vh + [yw] of the longer side; radius [r] of the longer side. */
private class Blob(val x: Float, val y: Float, val yw: Float, val r: Float, val color: Color, val delay: Float)

// The site's b1 / b2 / b3 (46 / 40 / 50 vw circles), as centres and radii.
private val BLOBS = listOf(
    Blob(11f, 0f, 9f, 23f, Color(0xFFFFD2CC), 0f),
    Blob(90f, 18f, 20f, 20f, Color(0xFFFFF0B0), 10f),
    Blob(45f, 100f, 5f, 25f, Color(0xFFD6E2FF), 20f),
)

// The same three blobs at night: plum, amber and indigo glows where the peach, yellow and blue are.
private val NIGHT_BLOBS = listOf(
    Blob(11f, 0f, 9f, 23f, Color(0xFF6A2F45), 0f),
    Blob(90f, 18f, 20f, 20f, Color(0xFF6A4418), 10f),
    Blob(45f, 100f, 5f, 25f, Color(0xFF233A7A), 20f),
)

// Where a theme's blobs go: the site's three, then two more for themes with more colours (RAINBOW, EASTER).
private val BLOB_PLACES = BLOBS + listOf(
    Blob(80f, 85f, 0f, 18f, Color.Transparent, 5f),
    Blob(30f, 45f, 0f, 16f, Color.Transparent, 15f),
)

/** A theme's own picture is clearer than the mark (it's the theme), but still a backdrop. */
private const val MOTIF_ALPHA = 0.32f
private const val MOTIF_NIGHT_ALPHA = 0.22f

/** A logo: at [x] / [y] % of the screen, [scale] (1 or 2), its float [delay] s in, [duration] s round. */
private class Petal(val x: Float, val y: Float, val scale: Int, val delay: Float, val duration: Float)

// The site's petals: [left %, top %, size, delay s, duration s].
private val PETALS = listOf(
    Petal(4f, 22f, 1, 0f, 22f), Petal(91f, 10f, 2, 4f, 26f), Petal(70f, 62f, 1, 9f, 24f),
    Petal(10f, 78f, 2, 2f, 28f), Petal(46f, 92f, 1, 6f, 25f), Petal(95f, 70f, 1, 12f, 30f),
)

/** The mark as a bitmap (one pixel per sprite pixel), drawn scaled with no smoothing. */
private val logoBitmap: ImageBitmap by lazy { pixelBitmap(LOGO_ROWS, LOGO_PALETTE) }

/** [motif] as a bitmap like [logoBitmap] (the mark for none), made once per motif. */
internal fun motifBitmap(motif: com.pokedaisy.app.companion.ui.theme.ThemeMotif?): ImageBitmap =
    if (motif == null) logoBitmap else motifBitmaps.getOrPut(motif) { pixelBitmap(motif.rows, motif.palette) }

private val motifBitmaps = HashMap<com.pokedaisy.app.companion.ui.theme.ThemeMotif, ImageBitmap>()

private fun pixelBitmap(rows: List<String>, palette: Map<Char, Color>): ImageBitmap {
    val n = rows.size
    val px = IntArray(n * n) { i -> palette[rows[i / n].getOrNull(i % n)]?.toArgb() ?: 0 }
    return android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The site's dot grid: a [dot]-px square of its ink at 9% every [step] px, as a repeating tile. */
private fun dotBrush(dot: Int, step: Int, dark: Boolean = false): Brush {
    val key = Triple(dot, step, dark)
    dotBrushes[key]?.let { return it }
    // Dark: white dots at ~6%, as faint on the ink as the site's are on its page.
    val ink = if (dark) 0x10FFFFFF else 0x171D1B26
    val px = IntArray(step * step) { i -> if (i % step < dot && i / step < dot) ink else 0 }
    val tile = android.graphics.Bitmap.createBitmap(px, step, step, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)).also { dotBrushes[key] = it }
}

private val dotBrushes = HashMap<Triple<Int, Int, Boolean>, Brush>()
