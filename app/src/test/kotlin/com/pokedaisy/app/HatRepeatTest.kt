package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** [HatRepeat] on a hand-run clock (GitHub discussion #922: holding the D-pad on the companion moved once). */
class HatRepeatTest {
    private var now = 0L
    private val queue = mutableListOf<Pair<Long, Runnable>>()
    private val repeat = HatRepeat(post = { r, ms -> queue += (now + ms) to r }, cancel = { r -> queue.removeAll { it.second === r } })
    private val sent = mutableListOf<Int>()

    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = queue.minByOrNull { it.first }?.takeIf { it.first <= end } ?: break
            queue.remove(next)
            now = next.first
            next.second.run()
        }
        now = end
    }

    private val up = MgbaCore.Key.UP
    private val down = MgbaCore.Key.DOWN

    @Test fun aHeldDirectionRepeats() {
        repeat.update(up, up) { sent += it }
        advance(HatRepeat.DELAY_MS - 1)
        assertEquals(emptyList<Int>(), sent)
        advance(1 + 3 * HatRepeat.EVERY_MS)
        assertEquals(listOf(up, up, up, up), sent)
    }

    @Test fun lettingGoStops() {
        repeat.update(up, up) { sent += it }
        advance(HatRepeat.DELAY_MS + HatRepeat.EVERY_MS)
        repeat.update(0, 0) { sent += it }
        advance(10_000)
        assertEquals(2, sent.size)
        assertEquals(0, repeat.held)
    }

    @Test fun aNewDirectionTakesOver() {
        repeat.update(up, up) { sent += it }
        advance(HatRepeat.DELAY_MS)
        repeat.update(down, down) { sent += it }
        advance(HatRepeat.DELAY_MS)
        assertEquals(listOf(up, down), sent)
    }

    @Test fun aButtonIsNotRepeated() {
        repeat.update(MgbaCore.Key.A, MgbaCore.Key.A) { sent += it }
        advance(10_000)
        assertEquals(emptyList<Int>(), sent)
    }
}
