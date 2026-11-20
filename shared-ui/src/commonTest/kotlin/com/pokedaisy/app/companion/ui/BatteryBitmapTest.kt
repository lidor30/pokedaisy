package com.pokedaisy.app.companion.ui

import kotlin.test.*

class BatteryBitmapTest {
    /** Filled columns of the battery's middle row. */
    private fun filled(percent: Int) = batteryBitmap(percent)[4].count { it == 'f' }

    @Test fun fillStepsInWholePixels() {
        assertEquals(0, filled(0))
        assertEquals(1, filled(1)) // any charge left shows
        assertEquals(6, filled(50))
        assertEquals(11, filled(100))
        assertEquals(11, filled(140))
    }

    @Test fun sameSizeAtEveryLevel() {
        val sizes = (0..100).map { batteryBitmap(it).let { b -> b.size to b[0].length } }.toSet()
        assertEquals(setOf(10 to 18), sizes)
    }
}
