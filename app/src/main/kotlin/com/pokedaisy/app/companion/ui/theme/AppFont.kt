package com.pokedaisy.app.companion.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

/**
 * SETTINGS > FONT on both screens (Prefs.appFont, by [ordinal]): the app's text in Pixel Operator (the default, crisp
 * pixel art, a Game Boy game's own font while one runs) or a smooth font for easier reading. [capHeight] is the font's
 * capitals as a share of its em, so text comes out with Pixel Operator's capital height ([sizeScale]) and every layout
 * sized to the pixel font still fits.
 */
enum class AppFont(val label: String, private val capHeight: Float) {
    PIXEL("PIXEL", 0.5625f),
    /** Atkinson Hyperlegible (OFL, already the manual's reading font). */
    SMOOTH("SMOOTH", 0.668f),
    /** Atkinson Hyperlegible Bold: closer to the games' heavy menu letters. */
    SMOOTH_BOLD("SMOOTH BOLD", 0.668f),
    /** The device's own sans-serif (Roboto on most Android devices). */
    SYSTEM("SYSTEM", 0.711f);

    val pixel: Boolean get() = this == PIXEL

    /** Font size against Pixel Operator's: its capital height, 0.9 of it for a smooth font, whose letters run wider than
     * the pixel font's (columns sized to Pixel Operator, DEFENSE beside its number, still fit). */
    val sizeScale: Float get() = if (pixel) 1f else 0.9f * PIXEL.capHeight / capHeight

    fun family(context: Context): FontFamily = when (this) {
        PIXEL -> com.pokedaisy.app.pixelFontFamily(context)
        SMOOTH -> com.pokedaisy.app.atkinsonFamily(context, bold = false)
        SMOOTH_BOLD -> com.pokedaisy.app.atkinsonFamily(context, bold = true)
        SYSTEM -> FontFamily.SansSerif
    }
}

object AppFonts {
    var setting by mutableStateOf(AppFont.PIXEL)

    /** ui-preview / screenshot tests: this font whatever the setting. */
    var override: AppFont? = null

    val current: AppFont get() = override ?: setting

    fun load() {
        setting = runCatching { AppFont.entries[ThemeStore.current?.appFont ?: 0] }.getOrDefault(AppFont.PIXEL)
    }

    fun set(font: AppFont) {
        ThemeStore.current?.appFont = font.ordinal
        setting = font
    }
}
