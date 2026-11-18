package com.pokedaisy.app.companion.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFontTest {
    /** Prefs.appFont stores ordinals: the old ones keep their meaning, and its default (a literal) is PIXEL SMALL's. */
    @Test fun ordinalsAreStable() {
        assertEquals(listOf("PIXEL", "SMOOTH", "SMOOTH_BOLD", "SYSTEM", "PIXEL_SMALL"), AppFont.entries.map { it.name })
        assertEquals(4, AppFont.DEFAULT.ordinal)
        assertEquals(AppFont.PIXEL_SMALL, AppFont.DEFAULT)
    }

    @Test fun menuListsEveryFontOnceDefaultFirst() {
        assertEquals(AppFont.entries.toSet(), AppFont.menu.toSet())
        assertEquals(AppFont.entries.size, AppFont.menu.size)
        assertEquals(AppFont.DEFAULT, AppFont.menu.first())
    }

    @Test fun bothPixelFontsDrawAtWholePixels() {
        assertTrue(AppFont.PIXEL.pixel && AppFont.PIXEL_SMALL.pixel)
        assertEquals(1f, AppFont.PIXEL_SMALL.sizeScale)
    }
}
