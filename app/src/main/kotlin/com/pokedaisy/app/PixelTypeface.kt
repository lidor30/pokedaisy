package com.pokedaisy.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily as AndroidFontFamily
import android.os.Build
import androidx.compose.ui.text.font.Font as ComposeFont
import androidx.compose.ui.text.font.FontFamily
import com.pokedaisy.app.companion.i18n.AppLanguage
import com.pokedaisy.app.companion.i18n.L10n

/**
 * The app's one font: Pixel Operator, with PixelMplusJP (scripts/gen_jp_font.py: the same pixel
 * grid) filling in the kana / kanji it lacks, GalmuriKO (scripts/gen_ko_font.py, likewise) the
 * Hangul, and PixelCJK (scripts/gen_zh_font.py: Fusion Pixel 12px zh_hans, re-em'd to Pixel
 * Operator's 1600 and subset to GB2312) the hanzi - glyph by glyph, so Japanese, Korean and
 * Chinese stay pixel art and Latin text keeps Pixel Operator. Android 10+
 * (Typeface.CustomFallbackBuilder); older ones fall back to the system's CJK font.
 *
 * Japanese and Korean don't overlap, so their order doesn't matter. Chinese does: the same hanzi
 * has a different glyph in Japanese and in Simplified Chinese (直, 骨, 化 ...), and a fixed order
 * would bleed one language's variants into the other. So the chain is built in the language's
 * order, and both orders are cached - Chinese first for Chinese text, Japanese first for the rest.
 *
 * Built once per process and shared by every text on both screens: building it loads the 1 MB
 * PixelMplusJP, 1.5 MB GalmuriKO and 1.25 MB PixelCJK, and a family per call site (every GbaText)
 * cost ~1 MB and several ms of main thread per text - tens of MB per tab switch, which ran the
 * app out of memory when tabs were switched quickly (v1.1.0).
 */
fun pixelFontFamily(context: Context): FontFamily {
    val chinese = L10n.language == AppLanguage.ZH
    synchronized(fontLock) { (if (chinese) pixelFamilyZh else pixelFamily)?.let { return it } }
    return synchronized(fontLock) {
        (if (chinese) pixelFamilyZh else pixelFamily) ?: buildPixelFamily(context.applicationContext, chinese).also {
            if (chinese) pixelFamilyZh = it else pixelFamily = it
        }
    }
}

/** A game's own font file ([file], e.g. Yellow's from Gen1Art) over Pixel Operator for anything it lacks. */
fun gameFontFamily(context: Context, file: java.io.File): FontFamily? {
    val chinese = L10n.language == AppLanguage.ZH
    val stamp = file.absolutePath + ":" + file.length() + ":" + file.lastModified() + ":" + chinese
    synchronized(fontLock) { gameFamilies[stamp]?.let { return it } }
    val family = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching FontFamily(ComposeFont(file))
        val assets = assetFamilies(context.applicationContext)
        val builder = Typeface.CustomFallbackBuilder(AndroidFontFamily.Builder(Font.Builder(file).build()).build())
        chain(assets, chinese).forEach { builder.addCustomFallback(it) }
        FontFamily(androidx.compose.ui.text.font.Typeface(builder.setSystemFallback("sans-serif").build()))
    }.getOrNull() ?: return null
    synchronized(fontLock) { gameFamilies[stamp] = family }
    return family
}

private val fontLock = Any()
@Volatile private var pixelFamily: FontFamily? = null
@Volatile private var pixelFamilyZh: FontFamily? = null
private var assetFonts: AssetFonts? = null
// A game font per file version (a rescan rewrites it); a handful at most.
private val gameFamilies = HashMap<String, FontFamily>()

private class AssetFonts(
    val pixel: AndroidFontFamily,
    val japanese: AndroidFontFamily,
    val korean: AndroidFontFamily,
    val chinese: AndroidFontFamily,
)

/** The fallbacks after Pixel Operator, in the order the language on screen wants them. */
private fun chain(assets: AssetFonts, chineseFirst: Boolean): List<AndroidFontFamily> =
    if (chineseFirst) listOf(assets.chinese, assets.japanese, assets.korean)
    else listOf(assets.japanese, assets.korean, assets.chinese)

/** Pixel Operator, PixelMplusJP, GalmuriKO and PixelCJK as platform families, loaded once (Android 10+ only). */
private fun assetFamilies(context: Context): AssetFonts = synchronized(fontLock) {
    assetFonts ?: run {
        fun family(path: String) = AndroidFontFamily.Builder(Font.Builder(context.assets, path).build()).build()
        AssetFonts(
            family("fonts/PixelOperator.ttf"),
            family("fonts/PixelMplusJP.ttf"),
            family("fonts/GalmuriKO.ttf"),
            family("fonts/PixelCJK.ttf"),
        ).also { assetFonts = it }
    }
}

private fun buildPixelFamily(context: Context, chineseFirst: Boolean = false): FontFamily {
    val plain = { FontFamily(ComposeFont("fonts/PixelOperator.ttf", context.assets)) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return plain()
    return runCatching {
        val assets = assetFamilies(context)
        val builder = Typeface.CustomFallbackBuilder(assets.pixel)
        chain(assets, chineseFirst).forEach { builder.addCustomFallback(it) }
        FontFamily(androidx.compose.ui.text.font.Typeface(builder.setSystemFallback("sans-serif").build()))
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
