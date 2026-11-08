package com.pokedaisy.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily as AndroidFontFamily
import android.os.Build
import androidx.compose.ui.text.font.Font as ComposeFont
import androidx.compose.ui.text.font.FontFamily

/**
 * The app's one font: Pixel Operator, with PixelMplusJP (scripts/gen_jp_font.py:
 * the same pixel grid) filling in the kana / kanji it lacks and GalmuriKO
 * (scripts/gen_ko_font.py, likewise) the Hangul, glyph by glyph - so Japanese
 * and Korean stay pixel art and Latin text keeps Pixel Operator. Android 10+
 * (Typeface.CustomFallbackBuilder); older ones fall back to the system's CJK font.
 *
 * Built once per process and shared by every text on both screens: building it
 * loads the 1 MB PixelMplusJP and 1.5 MB GalmuriKO, and a family per call site (every GbaText) cost
 * ~1 MB and several ms of main thread per text - tens of MB per tab switch,
 * which ran the app out of memory when tabs were switched quickly (v1.1.0).
 */
fun pixelFontFamily(context: Context): FontFamily {
    pixelFamily?.let { return it }
    return synchronized(fontLock) {
        pixelFamily ?: buildPixelFamily(context.applicationContext).also { pixelFamily = it }
    }
}

/** A game's own font file ([file], e.g. Yellow's from Gen1Art) over Pixel Operator for anything it lacks. */
fun gameFontFamily(context: Context, file: java.io.File): FontFamily? {
    val stamp = file.absolutePath + ":" + file.length() + ":" + file.lastModified()
    synchronized(fontLock) { gameFamilies[stamp]?.let { return it } }
    val family = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching FontFamily(ComposeFont(file))
        val assets = assetFamilies(context.applicationContext)
        val typeface = Typeface.CustomFallbackBuilder(AndroidFontFamily.Builder(Font.Builder(file).build()).build())
            .addCustomFallback(assets.pixel)
            .addCustomFallback(assets.japanese)
            .addCustomFallback(assets.korean)
            .setSystemFallback("sans-serif")
            .build()
        FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
    }.getOrNull() ?: return null
    synchronized(fontLock) { gameFamilies[stamp] = family }
    return family
}

private val fontLock = Any()
@Volatile private var pixelFamily: FontFamily? = null
private var assetFonts: AssetFonts? = null
// A game font per file version (a rescan rewrites it); a handful at most.
private val gameFamilies = HashMap<String, FontFamily>()

private class AssetFonts(val pixel: AndroidFontFamily, val japanese: AndroidFontFamily, val korean: AndroidFontFamily)

/** Pixel Operator, PixelMplusJP and GalmuriKO as platform families, loaded once (Android 10+ only). */
private fun assetFamilies(context: Context): AssetFonts = synchronized(fontLock) {
    assetFonts ?: run {
        fun family(path: String) = AndroidFontFamily.Builder(Font.Builder(context.assets, path).build()).build()
        AssetFonts(family("fonts/PixelOperator.ttf"), family("fonts/PixelMplusJP.ttf"), family("fonts/GalmuriKO.ttf"))
            .also { assetFonts = it }
    }
}

private fun buildPixelFamily(context: Context): FontFamily {
    val plain = { FontFamily(ComposeFont("fonts/PixelOperator.ttf", context.assets)) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return plain()
    return runCatching {
        val assets = assetFamilies(context)
        val typeface = Typeface.CustomFallbackBuilder(assets.pixel)
            .addCustomFallback(assets.japanese)
            .addCustomFallback(assets.korean)
            .setSystemFallback("sans-serif")
            .build()
        FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
    }.getOrElse { plain() }
}

/**
 * The docs reader's text font: Atkinson Hyperlegible (regular, bold, italic). Long reading in Pixel Operator
 * is tiring, so the manual's paragraphs use this; its headings and the app's own labels stay pixel art.
 * Built once per process, like [pixelFontFamily].
 */
fun readingFontFamily(context: Context): FontFamily {
    readingFamily?.let { return it }
    return synchronized(fontLock) {
        readingFamily ?: run {
            val assets = context.applicationContext.assets
            FontFamily(
                ComposeFont("fonts/AtkinsonHyperlegible-Regular.ttf", assets),
                ComposeFont("fonts/AtkinsonHyperlegible-Bold.ttf", assets, androidx.compose.ui.text.font.FontWeight.Bold),
                ComposeFont("fonts/AtkinsonHyperlegible-Italic.ttf", assets, style = androidx.compose.ui.text.font.FontStyle.Italic),
            ).also { readingFamily = it }
        }
    }
}

@Volatile private var readingFamily: FontFamily? = null
