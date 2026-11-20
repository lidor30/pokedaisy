package com.pokedaisy.app.companion.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

/**
 * SETTINGS > FONT on both screens (Prefs.appFont, by [ordinal]: new entries go at the end): the app's text in a pixel
 * font (PIXEL SMALL, the default since the SUMMARY MINT look, or Pixel Operator; crisp pixel art, a Game Boy game's own
 * font while one runs) or a smooth font for easier reading. [capHeight] is the font's
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
    SYSTEM("SYSTEM", 0.711f),
    /** The Linux / 3DS HUD's small pixel font (assets/fonts/PixelSmall.ttf, scripts/gen_small_font.py): capitals 8 tall
     * on Pixel Operator's grid, letters 5 wide; what it lacks comes from Pixel Operator. */
    PIXEL_SMALL("PIXEL SMALL", 0.5f);

    /** Drawn at whole screen pixels per font pixel (GbaTextMetrics), a game's own font in its place. */
    val pixel: Boolean get() = this == PIXEL || this == PIXEL_SMALL

    companion object {
        /** The default: a player who never picked one gets it (Prefs.appFont's default). */
        val DEFAULT = PIXEL_SMALL

        /** The pick-list's order (not the stored ordinals'): the pixel fonts first, the default on top. */
        val menu: List<AppFont> = listOf(PIXEL_SMALL, PIXEL, SMOOTH, SMOOTH_BOLD, SYSTEM)
    }

    /** Font size against Pixel Operator's: its capital height, 0.9 of it for a smooth font, whose letters run wider than
     * the pixel font's (columns sized to Pixel Operator, DEFENSE beside its number, still fit). */
    val sizeScale: Float get() = if (pixel) 1f else 0.9f * PIXEL.capHeight / capHeight

    fun family(): FontFamily = when (this) {
        PIXEL -> FontSource.get().pixel()
        PIXEL_SMALL -> FontSource.get().pixelSmall()
        SMOOTH -> FontSource.get().smooth(false)
        SMOOTH_BOLD -> FontSource.get().smooth(true)
        SYSTEM -> FontFamily.SansSerif
    }
}

object AppFonts {
    var setting by mutableStateOf(AppFont.DEFAULT)

    /** ui-preview / screenshot tests: this font whatever the setting. */
    var override: AppFont? = null

    val current: AppFont get() = override ?: setting

    fun load() {
        setting = runCatching { AppFont.entries[ThemeStore.current?.appFont ?: AppFont.DEFAULT.ordinal] }.getOrDefault(AppFont.DEFAULT)
    }

    fun set(font: AppFont) {
        ThemeStore.current?.appFont = font.ordinal
        setting = font
    }
}
