package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DS LAYOUT's transition hold over real traces: HeartGold's and Black 2's X menu > POKéMON and back out, frame by frame
 * from nds_dump's `trace` (master brightness, which screens are one colour), in `resources/ds_transitions/`.
 */
class DsTransitionHoldTest {
    private data class Frame(val fade: Int, val darkTop: Boolean, val darkBottom: Boolean) {
        val state: Int get() = (if (darkTop) MgbaCore.FIELD_DARK_TOP else 0) or (if (darkBottom) MgbaCore.FIELD_DARK_BOTTOM else 0)
    }

    private fun trace(name: String): List<Frame> {
        val text = javaClass.classLoader!!.getResource("ds_transitions/$name.txt")!!.readText()
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.flatMap { line ->
            val (count, fade, flat) = line.trim().split(" ")
            List(count.toInt()) { Frame(fade.toInt(16), 'T' in flat, 'B' in flat) }
        }
    }

    /** The holds a trace makes: (first held frame, the frame it lets go on). */
    private fun holds(frames: List<Frame>, inFlow: Boolean = true, battle: Boolean = false): List<Pair<Int, Int>> {
        val hold = DsTransitionHold()
        val out = mutableListOf<Pair<Int, Int>>()
        var start = -1
        frames.forEachIndexed { i, f ->
            val state = f.state or (if (battle) MgbaCore.FIELD_BATTLE else 0)
            val held = hold.step(f.fade, state, inFlow, on = true)
            if (held && start < 0) start = i
            if (!held && start >= 0) { out += start to i; start = -1 }
        }
        assertTrue("a hold never let go", start < 0)
        return out
    }

    /** Each hold starts on the transition's first faded frame and lets go [DsTransitionHold.LIT_FRAMES] into the next
     * screen's lit touch screen, with no fade left. */
    private fun checkShape(frames: List<Frame>, holds: List<Pair<Int, Int>>) {
        for ((start, end) in holds) {
            assertTrue("starts on a fade", frames[start].fade != 0)
            assertEquals("starts on the fade's first frame", 0, frames[start - 1].fade)
            for (i in end - DsTransitionHold.LIT_FRAMES + 1..end) {
                assertEquals("lets go only after a lit run (frame $i)", 0, frames[i].fade)
                assertFalse("lets go only after a lit run (frame $i)", frames[i].darkBottom)
            }
            val before = frames[end - DsTransitionHold.LIT_FRAMES]
            assertTrue("lets go on the run's 12th frame", before.fade != 0 || before.darkBottom)
            assertTrue("held through a black", (start..end).any { frames[it].darkTop && frames[it].darkBottom })
        }
    }

    @Test fun heartGoldIntoThePartyAndBack() {
        val frames = trace("heartgold")
        val holds = holds(frames)
        assertEquals(listOf(5 to 76, 162 to 277), holds)
        checkShape(frames, holds)
    }

    @Test fun black2IntoThePartyAndBack() {
        // Its party leaves the top screen one colour the whole time: the touch screen decides.
        val frames = trace("black2")
        val holds = holds(frames)
        assertEquals(listOf(32 to 91, 164 to 283), holds)
        checkShape(frames, holds)
    }

    @Test fun nothingOutsideAMenuFlow() {
        // A door, a warp: the field fades too, but nothing of DS LAYOUT's is on its way.
        assertEquals(emptyList<Pair<Int, Int>>(), holds(trace("heartgold"), inFlow = false))
    }

    @Test fun notInABattle() {
        assertEquals(emptyList<Pair<Int, Int>>(), holds(trace("black2"), battle = true))
    }

    @Test fun aShadeThatStaysIsNotAFade() {
        // A page dimmed part way and left so: let go, and not again until it's gone.
        val hold = DsTransitionHold()
        var frames = 0
        while (hold.step(0x0606, 0, inFlow = true, on = true)) frames++
        assertEquals(DsTransitionHold.STILL_FRAMES, frames)
        repeat(100) { assertFalse(hold.step(0x0606, 0, inFlow = true, on = true)) }
        assertFalse(hold.step(0, 0, inFlow = true, on = true))
        assertTrue(hold.step(0x0202, 0, inFlow = true, on = true))
    }

    @Test fun neverLongerThanTheCap() {
        val hold = DsTransitionHold()
        val black = MgbaCore.FIELD_DARK_TOP or MgbaCore.FIELD_DARK_BOTTOM
        var frames = 0
        while (hold.step(0x1010, black, inFlow = true, on = true)) frames++
        assertEquals(DsTransitionHold.MAX_FRAMES, frames)
        // Still black: not straight back in.
        assertFalse(hold.step(0x1010, black, inFlow = true, on = true))
    }

    @Test fun offWithTheStage() {
        val hold = DsTransitionHold()
        assertTrue(hold.step(0x0202, 0, inFlow = true, on = true))
        assertFalse(hold.step(0x0404, 0, inFlow = true, on = false))
    }
}
