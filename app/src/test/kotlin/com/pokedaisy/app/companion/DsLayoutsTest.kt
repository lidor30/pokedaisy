package com.pokedaisy.app.companion

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS LAYOUT's geometry on the screens it meets: inside the area, at 4:3, the two-screen ones never overlapping. */
class DsLayoutsTest {

    /** Sideways screens: the RP6 / Thor's 16:9, a 20:9 phone, the RG DS's 4:3, a 3:2 tablet, and a tiny miniature. */
    private val areas = listOf(1920 to 1080, 2400 to 1080, 640 to 480, 1800 to 1200, 46 to 25)

    private fun near43(b: DsBox) = abs(b.width * 3 - b.height * 4) <= 8   // each edge rounded to a whole pixel

    private fun inside(b: DsBox, w: Int, h: Int) = b.left >= 0 && b.top >= 0 && b.right <= w && b.bottom <= h

    private fun overlap(a: DsBox, b: DsBox) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    @Test
    fun everyLayoutFitsAndKeepsItsShape() {
        for ((w, h) in areas) for (layout in DsLayout.entries) for (fill in listOf(false, true)) {
            val a = DsLayouts.arrange(layout, w, h, fill)
            val what = "$layout ${w}x$h fill=$fill"
            assertTrue("$what: game outside", inside(a.top, w, h))
            a.touch?.let { assertTrue("$what: touch screen outside", inside(it, w, h)) }
            val gameAlone = layout == DsLayout.ONE_SCREEN || layout == DsLayout.PICTURE_IN_PICTURE
            if (!gameAlone || !fill) assertTrue("$what: game not 4:3 (${a.top})", near43(a.top))
            a.touch?.let { assertTrue("$what: touch screen not 4:3 ($it)", near43(it)) }
            if (!gameAlone) assertTrue("$what: the screens overlap", !overlap(a.top, a.touch!!))
        }
    }

    @Test
    fun oneScreenFillsOrFitsAndFloatsTheTouchScreen() {
        val full = DsLayouts.arrange(DsLayout.ONE_SCREEN, 1920, 1080, fill = true)
        assertEquals(DsBox(0, 0, 1920, 1080), full.top)
        assertNull(full.touch)
        // ORIGINAL: the DS's 4:3 at the full height, centred - the backdrop beside it.
        assertEquals(DsBox(240, 0, 1680, 1080), DsLayouts.arrange(DsLayout.ONE_SCREEN, 1920, 1080, fill = false).top)
        assertTrue(DsLayout.ONE_SCREEN.floats && DsLayout.PICTURE_IN_PICTURE.floats)
        assertTrue(DsLayout.entries.filter { it.floats }.size == 2)
    }

    @Test
    fun largeAndSmallSitAsADs() {
        // 16:9: the game at the full height, the touch screen a third of its width beside it, level with its bottom.
        val a = DsLayouts.arrange(DsLayout.LARGE_SMALL, 1920, 1080, fill = false)
        assertEquals(1080, a.top.height)
        val t = a.touch!!
        assertEquals(a.top.right, t.left)
        assertEquals(a.top.bottom, t.bottom)
        assertEquals(480, t.width)
    }

    @Test
    fun sideBySideMeetsAndPipSitsBottomRight() {
        // Edge to edge (the user's call: no gap between them).
        val s = DsLayouts.arrange(DsLayout.SIDE_BY_SIDE, 1920, 1080, fill = false)
        assertEquals(s.top.right, s.touch!!.left)
        // The games put a new area's name top left: the inset is bottom right, a margin in.
        val p = DsLayouts.arrange(DsLayout.PICTURE_IN_PICTURE, 1920, 1080, fill = true).touch!!
        assertEquals(1920 - p.right, 1080 - p.bottom)
        assertTrue(p.left > 960 && p.top > 540)
    }

    @Test
    fun unfollowedGamesKeepTheTouchScreenInSight() {
        assertEquals(DsLayout.PICTURE_IN_PICTURE, DsLayout.ONE_SCREEN.forGame(followed = false))
        assertEquals(DsLayout.ONE_SCREEN, DsLayout.ONE_SCREEN.forGame(followed = true))
        assertEquals(DsLayout.STACKED, DsLayout.STACKED.forGame(followed = false))
        for (code in listOf("IPKE", "IPGE", "CPUE", "IRBO", "IRAO", "IREO", "IRDO", "IPKF", "IPGF", "CPUF", "IRBF", "IRAF")) {
            assertTrue(code, DsLayout.followed(code))
        }
        // German HeartGold / SoulSilver are followed since their menu functions were found (2026-11-07).
        for (code in listOf("IPKD", "IPGD")) assertTrue(code, DsLayout.followed(code))
    }
}
