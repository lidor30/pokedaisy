package com.pokedaisy.app

import androidx.compose.ui.unit.IntRect
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.ui.TouchGeometry
import com.pokedaisy.app.companion.ui.TouchHitModel
import com.pokedaisy.app.companion.ui.TouchInput
import com.pokedaisy.app.companion.ui.TouchPressed
import com.pokedaisy.app.companion.ui.TouchPressTracker
import com.pokedaisy.app.companion.ui.excludeTouchRegions
import com.pokedaisy.app.companion.ui.touchHits
import org.junit.Assert.*
import org.junit.Test

class TouchInputRegionsTest {
    private fun IntRect.has(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom

    private fun verify(regions: List<IntRect>, reserved: List<IntRect>) {
        val actual = excludeTouchRegions(regions, reserved)
        assertTrue(actual.all { it.width > 0 && it.height > 0 })
        for (y in -10..40) for (x in -10..40) {
            val expected = regions.any { it.has(x, y) } && reserved.none { it.has(x, y) }
            val count = actual.count { it.has(x, y) }
            assertEquals("$x,$y in $actual", if (expected) 1 else 0, count)
        }
    }

    @Test fun centerHoleLeavesOnlyGameInputOutsideTheUi() = verify(
        listOf(IntRect(0, 0, 30, 30)), listOf(IntRect(10, 10, 20, 20)),
    )

    @Test fun gripPartlyOutsideAButtonClusterIsClippedAtItsEdge() = verify(
        listOf(IntRect(0, 0, 30, 20)), listOf(IntRect(20, -5, 40, 10)),
    )

    @Test fun overlappingUiRegionsNeverAddBackAReservedPoint() = verify(
        listOf(IntRect(0, 0, 30, 30)), listOf(IntRect(5, 5, 20, 20), IntRect(10, 10, 25, 25)),
    )

    @Test fun independentClustersKeepTheirGap() = verify(
        listOf(IntRect(0, 0, 10, 20), IntRect(20, 0, 30, 20)), listOf(IntRect(5, 5, 25, 15)),
    )

    @Test fun disjointAndEmptyReservationsLeaveControlsUsable() {
        val region = listOf(IntRect(0, 0, 20, 20))
        verify(region, emptyList())
        verify(region, listOf(IntRect(20, 0, 30, 20), IntRect(5, 5, 5, 15)))
    }

    @Test fun uiCoveringTheWholeClusterLeavesNoInputWindow() {
        assertTrue(excludeTouchRegions(listOf(IntRect(0, 0, 10, 10)), listOf(IntRect(-1, -1, 11, 11))).isEmpty())
    }

    @Test fun heldButtonReleasesWhenItsFingerMovesIntoTheGrip() {
        val placed = TouchGeometry.Placed(100f, 100f, listOf(
            TouchGeometry.Item(TouchButton.R, 50f, 50f, 80f, 40f, TouchGeometry.Shape.PILL),
        ), com.pokedaisy.app.companion.TouchPreset.CLASSIC, 1f)
        val model = TouchHitModel(placed, null, false, listOf(IntRect(50, 20, 90, 80)))
        val masks = mutableListOf<Int>()
        val tracker = TouchPressTracker(TouchPressed()).apply {
            input = TouchInput(onKeys = { keys, _ -> masks += keys })
        }
        tracker.update(TouchPressTracker.SOURCE_WINDOWS, touchHits(model, floatArrayOf(30f), floatArrayOf(50f), 1))
        assertEquals(com.pokedaisy.app.companion.data.CoreKey.R, masks.last())
        tracker.update(TouchPressTracker.SOURCE_WINDOWS, touchHits(model, floatArrayOf(60f), floatArrayOf(50f), 1))
        assertEquals(0, masks.last())
        // Another finger outside the UI region still operates the game.
        assertTrue(touchHits(model, floatArrayOf(30f, 60f), floatArrayOf(50f, 50f), 2).held != 0)
    }
}
