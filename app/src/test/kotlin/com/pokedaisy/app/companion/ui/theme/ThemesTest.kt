package com.pokedaisy.app.companion.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** THEME: the limited-time themes' dates, their popup once per window, an ended one giving way, and the tint. */
class ThemesTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun named(label: String) = (APP_THEMES + SEASONAL_THEMES).single { it.label == label }

    @Test fun idsAreUniqueAndNeverTheOldOnes() {
        val ids = (APP_THEMES + SEASONAL_THEMES).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // 0-7 were FireRed, LeafGreen... (Prefs reads them as the default now).
        assertTrue(ids.none { it in 0..7 })
        assertEquals(DAISY_THEME_ID, APP_THEMES.first().id)
        assertEquals(9, APP_THEMES.size)
        assertTrue(SEASONAL_THEMES.all { it.season != null } && APP_THEMES.all { it.season == null })
    }

    @Test fun easter() {
        assertEquals(d("2024-03-31"), Season.easter(2024))
        assertEquals(d("2025-04-20"), Season.easter(2025))
        assertEquals(d("2026-04-05"), Season.easter(2026))
        assertEquals(d("2027-03-28"), Season.easter(2027))
    }

    @Test fun windows() {
        assertEquals(listOf("HALLOWEEN"), SEASONAL_THEMES.filter { it.windowOn(d("2026-11-01")) != null }.map { it.label })
        assertNull(named("HALLOWEEN").windowOn(d("2026-11-04")))
        assertNull(named("HALLOWEEN").windowOn(d("2026-10-16")))
        assertEquals(listOf("CHRISTMAS"), SEASONAL_THEMES.filter { it.windowOn(d("2026-12-03")) != null }.map { it.label })
        // New Year crosses the year: Dec 28 to Jan 4, keyed by its first day.
        assertEquals(d("2026-12-28"), named("NEW YEAR").windowOn(d("2027-01-03"))!!.start)
        assertEquals(d("2026-12-28"), named("NEW YEAR").windowOn(d("2026-12-30"))!!.start)
        // Lunar New Year 2027 (Feb 6): a week before through the Lantern Festival; Valentine's inside it.
        assertEquals(d("2027-01-30")..d("2027-02-21"), named("LUNAR NEW YEAR").windowOn(d("2027-02-10")))
        assertTrue(named("VALENTINE'S DAY").windowOn(d("2027-02-14")) != null)
        assertTrue(named("ST. PATRICK'S DAY").windowOn(d("2027-03-17")) != null)
        assertTrue(named("EASTER").windowOn(d("2027-03-25")) != null)
        // A quiet day: only the themes that are always there.
        assertEquals(APP_THEMES, availableThemes(d("2027-06-15")))
        assertEquals(APP_THEMES + named("HALLOWEEN"), availableThemes(d("2026-10-31")))
        // Past the tables: no window, not offered.
        assertNull(named("LUNAR NEW YEAR").windowOn(d("2040-02-12")))
    }

    @Test fun announcedOncePerWindow() {
        val day = d("2026-11-01")
        val halloween = named("HALLOWEEN")
        assertEquals(halloween, seasonalThemeToAnnounce(day, emptySet(), DAISY_THEME_ID))
        val shown = setOf(announceKey(halloween, halloween.windowOn(day)!!))
        assertNull(seasonalThemeToAnnounce(day, shown, DAISY_THEME_ID))
        // Already wearing it: nothing to tell.
        assertNull(seasonalThemeToAnnounce(day, emptySet(), halloween.id))
        // Next year's window is a new one.
        assertEquals(halloween, seasonalThemeToAnnounce(d("2027-10-20"), shown, DAISY_THEME_ID))
        // Two at once: the one that began first, then the other (Lunar New Year 2027 began Jan 30, Valentine's Feb 7).
        val feb = d("2027-02-10")
        val first = seasonalThemeToAnnounce(feb, emptySet(), DAISY_THEME_ID)!!
        assertEquals("LUNAR NEW YEAR", first.label)
        val shownFirst = setOf(announceKey(first, first.windowOn(feb)!!))
        assertEquals("VALENTINE'S DAY", seasonalThemeToAnnounce(feb, shownFirst, DAISY_THEME_ID)!!.label)
    }

    @Test fun anEndedThemeGivesWay() {
        val cherry = named("CHERRY")
        val halloween = named("HALLOWEEN")
        assertEquals(halloween, themeToWear(halloween.id, cherry.id, d("2026-11-02")))
        assertEquals(cherry, themeToWear(halloween.id, cherry.id, d("2026-11-10")))
        // Nothing (or another limited one) to go back to: the default.
        assertEquals(DAISY_THEME_ID, themeToWear(halloween.id, named("CHRISTMAS").id, d("2026-11-10")).id)
        assertEquals(cherry, themeToWear(cherry.id, DAISY_THEME_ID, d("2026-11-10")))
        // An unknown id (or an old one, 0-7, which Prefs already turns into the default): the default.
        assertEquals(DAISY_THEME_ID, themeToWear(3, DAISY_THEME_ID, d("2026-11-10")).id)
    }

    @Test fun tintKeepsLightnessAndRealColours() {
        val green = Color(0xFF209C08)
        assertEquals(green, green.themed(345f, 0.5f))
        val grey = Color(0xFFE0DFDF).themed(345f, 0.5f)
        assertNotEquals(Color(0xFFE0DFDF), grey)
        assertTrue(grey.red > grey.green && grey.red > grey.blue - 0.01f)
        // White stays almost white, black almost black.
        val white = Color.White.themed(200f, 0.6f)
        assertTrue(white.red > 0.93f && white.green > 0.93f && white.blue > 0.93f)
        val ink = Color(0xFF101010).themed(200f, 0.6f)
        assertTrue(ink.red < 0.12f && ink.green < 0.12f && ink.blue < 0.12f)
        assertFalse(Color.Transparent.themed(200f, 0.6f).alpha > 0f)
    }
}
