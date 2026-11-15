@file:OptIn(ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioRingTest {
    @Test
    fun keepsOrderDropsOverflowAndPadsWithSilence() {
        val ring = AudioRing(8)
        try {
            val src = ShortArray(20) { (it + 1).toShort() }
            assertEquals(5, ring.write(src, 10))
            // Three more fit: the rest of the 7 is dropped.
            assertEquals(3, ring.write(ShortArray(14) { (100 + it).toShort() }, 14))
            assertEquals(8, ring.available)
            memScoped {
                val out = allocArray<ShortVar>(24)
                assertEquals(8, ring.read(out, 12))
                assertEquals(listOf<Short>(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 100, 101, 102, 103, 104, 105),
                    (0 until 16).map { out[it] })
                assertEquals(List(8) { 0.toShort() }, (16 until 24).map { out[it] })
            }
            assertEquals(0, ring.available)
        } finally {
            ring.free()
        }
    }

    @Test
    fun wrapsRoundTheEnd() {
        val ring = AudioRing(4)
        try {
            memScoped {
                val out = allocArray<ShortVar>(6)
                var next: Short = 0
                repeat(50) {
                    val src = ShortArray(6) { next++ }
                    assertEquals(3, ring.write(src, 6))
                    assertEquals(3, ring.read(out, 3))
                    assertEquals(src.toList(), (0 until 6).map { out[it] })
                }
            }
        } finally {
            ring.free()
        }
    }

    @Test
    fun wantsAPowerOfTwo() {
        assertFailsWith<IllegalArgumentException> { AudioRing(6) }
    }
}
