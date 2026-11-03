package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.data.coop.CoopBattleMsg
import com.pokedaisy.app.companion.data.coop.CoopLinkBattle
import com.pokedaisy.app.companion.data.coop.FakeLinkChannel
import com.pokedaisy.app.companion.data.coop.FakeLinkEngine
import com.pokedaisy.app.companion.data.coop.FakeLinkUi
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link blocks from the partner are game data: the generic shape is checked before the bridge's own per-kind checks. */
class CoopLinkSafetyTest {
    @Test fun malformedBlockNeverGiven() {
        val eng = FakeLinkEngine()
        val b = CoopLinkBattle(eng, FakeLinkChannel(), FakeLinkUi()) { 0L }
        b.start(5, false) { 1 }
        fun msg(blk: ByteArray) = CoopBattleMsg.LinkBlock(5, blk.getOrElse(1) { 0 }.toInt() and 0xFF, blk)
        b.onMessage(msg(ByteArray(0)))
        b.onMessage(msg(byteArrayOf(1, 1, 5, 0, 1)))                      // size field 5, one byte follows
        b.onMessage(msg(byteArrayOf(1, 2, 0, 0x7F) + ByteArray(0x7F00)))  // over the cap
        b.afterFrame()
        assertTrue(eng.given.isEmpty())
    }

    @Test fun linkDriverNeverTouchesGameMemory() {
        for (name in listOf("CoopLinkBattle.kt", "CoopLinkFlow.kt")) {
            val text = File("src/main/kotlin/com/pokedaisy/app/companion/data/coop/$name").readText()
            for (word in listOf("MgbaCore", "pkWrite", "pkCall", "EmulatorEngine")) assertTrue("$name mentions $word", !text.contains(word))
        }
    }

    /** A block the other game sent before this game's own battle began is kept only for the join in flight, never for another id. */
    @Test fun blocksOfAnotherBattleAreNeverKept() {
        val eng = FakeLinkEngine()
        val b = CoopLinkBattle(eng, FakeLinkChannel(), FakeLinkUi()) { 0L }
        b.expect(5)
        val blk = byteArrayOf(1, 0, 4, 0, 1, 2, 3, 4)
        b.onMessage(CoopBattleMsg.LinkBlock(6, 0, blk))
        b.start(5, false) { 1 }
        b.afterFrame()
        assertTrue(eng.given.isEmpty())
    }
}
