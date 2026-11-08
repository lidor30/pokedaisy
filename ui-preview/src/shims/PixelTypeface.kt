package com.pokedaisy.app

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

// Desktop stand-in for the app's PixelTypeface.kt. Skia doesn't fall back glyph
// by glyph inside a FontFamily, so Japanese / Korean render in the system's CJK font here
// (bigger, not pixel art) - Paparazzi shows the real PixelMplusJP / GalmuriKO fallback.
// One per process, like the app's: the Font shim reads the file's bytes on every call.
fun pixelFontFamily(context: android.content.Context): FontFamily = family ?: FontFamily(
    Font("fonts/PixelOperator.ttf", context.assets),
    Font("fonts/PixelMplusJP.ttf", context.assets),
    Font("fonts/GalmuriKO.ttf", context.assets),
).also { family = it }

@Volatile private var family: FontFamily? = null

/** Desktop stand-in for the app's gameFontFamily: the game's font, Pixel Operator after it. */
fun gameFontFamily(context: android.content.Context, file: java.io.File): FontFamily? = runCatching {
    FontFamily(
        androidx.compose.ui.text.platform.Font(file.absolutePath, file.readBytes()),
        Font("fonts/PixelOperator.ttf", context.assets),
    )
}.getOrNull()

/** Desktop stand-in for the app's readingFontFamily: Atkinson Hyperlegible, one per process. */
fun readingFontFamily(context: android.content.Context): FontFamily = reading ?: FontFamily(
    Font("fonts/AtkinsonHyperlegible-Regular.ttf", context.assets),
    Font("fonts/AtkinsonHyperlegible-Bold.ttf", context.assets, androidx.compose.ui.text.font.FontWeight.Bold),
    Font("fonts/AtkinsonHyperlegible-Italic.ttf", context.assets, style = androidx.compose.ui.text.font.FontStyle.Italic),
).also { reading = it }

@Volatile private var reading: FontFamily? = null
