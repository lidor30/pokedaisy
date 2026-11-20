package com.pokedaisy.app.game

import com.pokedaisy.app.companion.data.DayNight
import com.pokedaisy.app.companion.data.GameKind
import kotlin.test.*

class DayNightTest {
    @Test fun clockWrapsAndKeepsTheOriginalDawnDayDuskNightColors() {
        assertContentEquals(intArrayOf(135, 150, 225), DayNight.at(0f))
        assertContentEquals(intArrayOf(245, 220, 220), DayNight.at(6f))
        assertContentEquals(DayNight.NONE, DayNight.at(12f))
        assertContentEquals(intArrayOf(255, 200, 165), DayNight.at(18f))
        assertContentEquals(DayNight.at(23f), DayNight.at(-1f))
        assertContentEquals(DayNight.at(6f), DayNight.at(30f))
        assertContentEquals(intArrayOf(190, 185, 222), DayNight.at(5.25f))
    }

    @Test fun blockedNonGbaAndGamesWithTheirOwnClockNeverRequestTint() {
        for (game in GameKind.entries) {
            assertFalse(DayNight.supported(false, false, game))
            assertFalse(DayNight.supported(true, true, game))
            assertEquals(game !in DayNight.OWN_DAY_NIGHT, DayNight.supported(true, false, game))
        }
        for (type in listOf(0, 4, 5, 7, 8, 9)) {
            assertContentEquals(DayNight.NONE, DayNight.desired(true, true, type) { error("Indoor clock read") })
        }
        assertContentEquals(DayNight.NONE, DayNight.desired(false, true, 1) { error("Disabled clock read") })
        assertContentEquals(DayNight.NONE, DayNight.desired(true, false, 1) { error("Unsupported clock read") })
        for (type in listOf(-1, 1, 2, 3, 6)) {
            assertContentEquals(DayNight.at(0f), DayNight.desired(true, true, type) { DayNight.at(0f) })
        }
    }

    @Test fun fieldTransitionsEaseWithoutOvershootAndRestoreTheOriginalPicture() {
        val transition = DayNight.Transition()
        var last = DayNight.NONE.copyOf()
        var sent = 0
        val apply: (Int, Int, Int) -> Unit = { r, g, b ->
            val next = intArrayOf(r, g, b)
            for (c in 0..2) assertTrue(kotlin.math.abs(next[c] - last[c]) <= 4)
            last = next; sent++
        }
        val night = DayNight.at(0f)
        repeat(40) { transition.step(night, true, apply) }
        assertContentEquals(night, last)
        val before = sent
        repeat(60) { transition.step(night, true, apply) }
        assertTrue(sent > before, "Active tint must rebind to a restarted native renderer")
        repeat(40) { transition.step(night, false, apply) }
        assertContentEquals(DayNight.NONE, last)
        val restored = sent
        repeat(120) { transition.step(night, false, apply) }
        assertEquals(restored, sent, "An untinted picture needs no repeated native writes")
        transition.reset(); last = DayNight.NONE.copyOf()
        transition.step(night, true, apply)
        assertContentEquals(intArrayOf(252, 252, 252), last)
    }
}
