package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** [LongHeap] hands out the same order as the java.util.PriorityQueue it replaced, ties included. */
class LongHeapTest {
    @Test fun sameOrderAsPriorityQueue() {
        val rnd = kotlin.random.Random(7)
        repeat(50) {
            val jdk = java.util.PriorityQueue<Long>(compareBy { it shr 32 })
            val ours = LongHeap()
            val got = ArrayList<Long>()
            val want = ArrayList<Long>()
            repeat(500) { i ->
                if (rnd.nextInt(3) != 0 || !ours.isNotEmpty()) {
                    val v = (rnd.nextInt(20).toLong() shl 32) or i.toLong() // few keys: many ties
                    jdk += v; ours += v
                } else {
                    want += jdk.poll(); got += ours.poll()
                }
            }
            while (ours.isNotEmpty()) { want += jdk.poll(); got += ours.poll() }
            assertEquals(want, got)
        }
    }
}
