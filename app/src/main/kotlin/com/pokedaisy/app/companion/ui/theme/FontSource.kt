package com.pokedaisy.app.companion.ui.theme

import androidx.compose.ui.text.font.FontFamily
import kotlin.concurrent.Volatile
import okio.Path

/**
 * The app's fonts, built by the platform: the font files are the app's own assets, and how a family falls back
 * from one file to the next (Pixel Operator to the Japanese and Korean pixel fonts, glyph by glyph) is the
 * platform's (Android's PixelTypeface.kt). The screens ask through [pixelFontFamily] and friends, never a Context.
 * Each family is built once per process: building one loads a few MB of font files.
 */
interface FontSource {
    /** Pixel Operator with its Japanese and Korean fallbacks: the app's one font. */
    fun pixel(): FontFamily

    /** Atkinson Hyperlegible (regular, bold, italic): the manual's paragraphs. */
    fun reading(): FontFamily

    /** A game's own font [file] (rom-art's, e.g. Yellow's) over Pixel Operator for anything it lacks; null when it can't be read. */
    fun game(file: Path): FontFamily?

    companion object {
        /** Set by the app at start (and by ui-preview / the screenshot tests). */
        @Volatile var current: FontSource? = null

        fun get(): FontSource = checkNotNull(current) { "FontSource.current isn't set" }
    }
}
