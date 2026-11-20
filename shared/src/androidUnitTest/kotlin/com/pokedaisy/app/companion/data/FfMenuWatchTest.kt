package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SMART fast-forward's menu detection ([FfMenuWatch]). */
class FfMenuWatchTest {
    private fun iwram(name: String) = javaClass.classLoader!!.getResourceAsStream("fixtures/ff_menu_gmain/$name.bin")!!.readBytes()

    @Test fun findsGMainInEveryGame() {
        // Emerald and Rogue count vblankCounter1; FireRed keeps a pointer there and counts counter2.
        assertEquals(0x030022C0L, FfMenuWatch.locate(iwram("emerald_a"), iwram("emerald_b"), 16))
        assertEquals(0x030030F0L, FfMenuWatch.locate(iwram("firered_a"), iwram("firered_b"), 16))
        assertEquals(0x030014B4L, FfMenuWatch.locate(iwram("rogue_a"), iwram("rogue_b"), 60))
        // Frames that don't match: nothing, rather than a guess.
        assertEquals(-1L, FfMenuWatch.locate(iwram("emerald_a"), iwram("emerald_b"), 15))
    }

    /** A game whose gMain is at [G], driven frame by frame (Emerald Rogue's callbacks). */
    private class FakeGame : MemoryReader {
        val iwram = ByteArray(0x8000)
        var cb2 = FIELD
        var inBattle = false

        fun frame() {
            put(G - 0x03000000 + 0x20, get(G - 0x03000000 + 0x20) + 1)
            put(G - 0x03000000 + 0x24, get(G - 0x03000000 + 0x24) + 1)
            put(G - 0x03000000 + 4, cb2)
            put(G - 0x03000000 + 0xC, VBLANK_CB)
            iwram[(G - 0x03000000 + 0x439).toInt()] = if (inBattle) 2 else 0
        }

        private fun get(o: Long) = Gfx.u32(iwram, o.toInt())
        private fun put(o: Long, v: Long) { for (k in 0 until 4) iwram[o.toInt() + k] = (v shr (8 * k)).toByte() }

        override fun readCoreMemory(addr: Long, size: Int): ByteArray {
            val o = (addr - 0x03000000).toInt()
            return iwram.copyOfRange(o, o + size)
        }
    }

    @Test fun learnsTheFieldAndBattleAndFlagsEverythingElse() {
        val g = FakeGame()
        FfMenuWatch.load("BPEE", 0L, 0L)
        fun run(n: Int): Boolean { var menu = false; repeat(n) { g.frame(); menu = FfMenuWatch.tick(g) }; return menu }
        assertFalse(run(200)) // gMain found; nothing learned yet, so never a menu
        // The player walks on the field: that callback is the field's.
        for (x in 0 until 4) { FfMenuWatch.notePosition(x, 5, 0, 1, false); run(60) }
        assertFalse(run(1))
        g.cb2 = PARTY
        assertTrue(run(1))
        g.cb2 = FIELD
        assertFalse(run(1))
        // A battle: its main callback is learned, its bag is a menu.
        g.inBattle = true
        g.cb2 = BATTLE
        run(200)
        assertFalse(run(1))
        g.cb2 = BAG
        assertTrue(run(1))
        g.cb2 = BATTLE
        assertFalse(run(1))
    }

    @Test fun rememberedCallbacksWorkFromTheFirstFrame() {
        val g = FakeGame()
        FfMenuWatch.load("BPEE", FIELD, BATTLE)
        g.cb2 = PARTY
        var menu = false
        repeat(140) { g.frame(); menu = FfMenuWatch.tick(g) }
        assertTrue(menu)
    }

    /**
     * FireRed keeps a pointer in vblankCounter1 and counts counter2, so its gMain
     * only fits the second rule - and a task slot whose data counts frames fits
     * the first one exactly (func at +4, data[10] at +0x20). That once made the
     * watch read a task's function as "callback2": menus never slowed down and
     * the field sometimes did. The VBlank callback at +0xC tells them apart.
     */
    @Test fun aFrameCountingTaskIsNotGMain() {
        fun snapshot(frame: Int): ByteArray {
            val m = ByteArray(0x8000)
            fun put(o: Int, v: Long) { for (k in 0 until 4) m[o + k] = (v shr (8 * k)).toByte() }
            val g = (FR_GMAIN - 0x03000000).toInt()
            put(g + 4, 0x080565C9L)           // CB2_Overworld
            put(g + 0xC, 0x08056A29L)         // its VBlank callback
            put(g + 0x20, 0L)                 // vblankCounter1: a NULL pointer on the field
            put(g + 0x24, 0x327L + frame)     // vblankCounter2 counts
            val t = (TASK - 0x03000000).toInt()
            put(t, 0x0807FB55L)               // the task's func
            put(t + 0x1C, 100L + frame)       // data[10..11]: a frame counter
            return m
        }
        assertEquals(FR_GMAIN, FfMenuWatch.locate(snapshot(0), snapshot(16), 16))
    }

    /** gMain is re-checked: an address whose counters stop is dropped with what was learned there, and found again. */
    @Test fun aStoppedCounterMeansWrongGMain() {
        val g = FakeGame()
        FfMenuWatch.load("BPEE", FIELD, BATTLE)
        FfMenuWatch.useKnownGMain(WRONG, 0x439) // say a hack's config pointed somewhere else
        fun run(n: Int): Boolean { var menu = false; repeat(n) { g.frame(); menu = FfMenuWatch.tick(g) }; return menu }
        g.cb2 = PARTY
        assertFalse(run(2000)) // dropped, and what it "knew" with it: no menu claims meanwhile
        // Found again by the scan; walking re-learns the field, and the party is a menu once more.
        g.cb2 = FIELD
        for (x in 0 until 4) { FfMenuWatch.notePosition(x, 5, 0, 1, false); run(60) }
        assertFalse(run(1))
        g.cb2 = PARTY
        assertTrue(run(1))
    }

    @Test fun theGamesOwnGMainNeedsNoScan() {
        val g = FakeGame()
        FfMenuWatch.load("BPEE", FIELD, BATTLE)
        FfMenuWatch.useKnownGMain(G, 0x439)
        g.cb2 = PARTY
        g.frame()
        assertTrue(FfMenuWatch.tick(g)) // from the very first frame, no scan
    }

    private companion object {
        const val G = 0x030014B4L
        const val WRONG = 0x03002000L
        const val FR_GMAIN = 0x030030F0L
        const val TASK = 0x03005000L
        const val VBLANK_CB = 0x08159A21L
        const val FIELD = 0x081597ADL
        const val PARTY = 0x0815DF99L
        const val BAG = 0x0812DAC9L
        const val BATTLE = 0x0807FCADL
    }
}
