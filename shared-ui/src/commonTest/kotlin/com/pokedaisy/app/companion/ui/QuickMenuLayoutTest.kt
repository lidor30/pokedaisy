package com.pokedaisy.app.companion.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class QuickMenuLayoutTest {
    @Test fun sevenTilesRefitTheirGapWhenBothPortraitLimitsClampTheOval() {
        for ((width, height) in listOf(377f to 251f, 470f to 310f)) {
            val w = 82f; val h = 66f
            val places = ringPlaces(7, w, h, 24f, width, height)
            assertTrue(2 * places.rx + w <= width + 1f)
            assertTrue(2 * places.ry + h <= height + 1f)
            for (a in 0 until 7) for (b in a + 1 until 7) {
                assertTrue(abs(places.x[a] - places.x[b]) >= w - .5f || abs(places.y[a] - places.y[b]) >= h - .5f,
                    "Tiles $a and $b overlap in $width x $height")
            }
        }
    }
}
