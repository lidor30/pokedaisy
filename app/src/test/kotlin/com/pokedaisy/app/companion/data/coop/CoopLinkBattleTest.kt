package com.pokedaisy.app.companion.data.coop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopLinkBattleTest {
    private val engine = FakeLinkEngine()
    private val channel = FakeLinkChannel()
    private val ui = FakeLinkUi()
    private var t = 1_000L
    private val battle = CoopLinkBattle(engine, channel, ui) { t }
    private val id = 77
    private val ends = ArrayList<CoopLinkEnd>()

    init { battle.onEnded = { ends += it } }

    private fun begin(story: Boolean = false) = battle.start(id, story) { 1 } == 1

    /** The CANCEL messages this side sent, by reason. */
    private fun cancelsSent() = channel.sent.filter { it.first == CoopBattleWire.CANCEL }.map { (CoopBattleWire.decode(it.first, it.second) as CoopBattleMsg.Cancel).let { c -> c.battleId to c.reason } }

    private fun incoming(seq: Int, battleId: Int = id, kind: Int = 3) =
        battle.onMessage(CoopBattleMsg.LinkBlock(battleId, seq, linkBlock(kind, seq)))

    @Test fun blocksFlowBothWaysInOrder() {
        assertTrue(begin())
        engine.outgoing += linkBlock(1, 0); engine.outgoing += linkBlock(2, 1); engine.outgoing += linkBlock(3, 2)
        incoming(0); incoming(1); incoming(2)
        battle.afterFrame()
        assertEquals(3, channel.sent.size)
        for ((i, s) in channel.sent.withIndex()) {
            assertEquals(CoopBattleWire.LINK_BLOCK, s.first)
            val m = CoopBattleWire.decode(s.first, s.second) as CoopBattleMsg.LinkBlock
            assertEquals(i, m.seq); assertEquals(id, m.battleId)
        }
        assertEquals(listOf(0, 1, 2), engine.given.map { it[1].toInt() })
    }

    @Test fun staleBlocksAreDropped() {
        begin()
        incoming(0, battleId = 99)          // another battle
        incoming(5); incoming(5); incoming(4)   // a repeat and an older one
        incoming(6)
        battle.afterFrame()
        assertEquals(listOf(5, 6), engine.given.map { it[1].toInt() })
    }

    @Test fun seqWrapsAround() {
        begin()
        incoming(254); incoming(255); incoming(0); incoming(255)
        battle.afterFrame()
        assertEquals(listOf(254, 255, 0), engine.given.map { it[1].toInt() and 0xFF })
    }

    @Test fun malformedShapeIsNeverGiven() {
        begin()
        val bad = linkBlock(1, 3, 4).also { it[2] = 9 }   // size field says 9, 4 bytes follow
        battle.onMessage(CoopBattleMsg.LinkBlock(id, 3, bad))
        battle.onMessage(CoopBattleMsg.LinkBlock(id, 4, linkBlock(1, 3)))   // seq differs from the block's own
        battle.afterFrame()
        assertTrue(engine.given.isEmpty())
    }

    @Test fun refusedBlockIsRetriedInOrder() {
        begin()
        engine.acceptGive = false
        incoming(0); incoming(1)
        battle.afterFrame()
        assertTrue(engine.given.isEmpty())
        engine.acceptGive = true
        battle.afterFrame()
        assertEquals(listOf(0, 1), engine.given.map { it[1].toInt() })
        assertEquals(1, battle.retried)
    }

    @Test fun dropWaitsThenLeaves() {
        begin()
        battle.linkDown()
        t += 59_000
        battle.afterFrame()
        assertTrue(engine.leaves.isEmpty())
        assertEquals(listOf(1), ui.waits)
        t += 1_000
        battle.afterFrame()
        assertEquals(listOf(false), engine.leaves)
        assertFalse(battle.active)
        assertEquals(1, ends.size)
        assertTrue(ends[0].left)
        assertEquals("best effort: the other side is told the 60 s drop ended it", listOf(id to CoopCancel.CLOSED), cancelsSent())
    }

    @Test fun backWithinTheMinuteCarriesOn() {
        begin()
        battle.linkDown(); t += 30_000; battle.afterFrame()
        battle.linkUp(); t += 120_000; battle.afterFrame()
        assertTrue(engine.leaves.isEmpty()); assertTrue(battle.active)
    }

    @Test fun storyLockDropBlacksOut() {
        begin(story = true)
        battle.linkDown(); t += 60_000; battle.afterFrame()
        assertEquals(listOf(true), engine.leaves)
    }

    @Test fun slowPlayerNeverTimesOut() {
        begin()
        repeat(600) { t += 1_000; battle.afterFrame() }
        assertTrue(engine.leaves.isEmpty()); assertTrue(battle.active)
    }

    @Test fun blocksWaitOutALinkDropAndGoOnReturn() {
        begin()
        channel.connected = false
        battle.linkDown()
        engine.outgoing += linkBlock(1, 0)
        t += 5_000; battle.afterFrame()
        assertTrue(channel.sent.isEmpty())
        channel.connected = true; battle.linkUp(); battle.afterFrame()
        assertEquals(1, channel.sent.size)
    }

    @Test fun hostStopEndsCleanly() {
        begin()
        battle.stop()
        assertEquals(listOf(false), engine.leaves)
        assertEquals(CoopBattleWire.CANCEL, channel.sent.single().first)
        assertFalse(battle.active)
        engine.outgoing += linkBlock(1, 0)
        battle.afterFrame()
        assertEquals(1, channel.sent.size)   // nothing sent after the stop
    }

    @Test fun partnerGetsTheDropRuleWhenHostCancels() {
        begin()
        battle.onMessage(CoopBattleMsg.Cancel(id, CoopCancel.CLOSED))
        battle.afterFrame(); t += 60_000; battle.afterFrame()
        assertEquals(listOf(false), engine.leaves)
    }

    @Test fun abandonLeavesAtOnceAndSaysNothing() {
        begin()
        battle.abandon()
        assertEquals(listOf(false), engine.leaves)
        assertTrue(channel.sent.isEmpty())
        assertFalse(battle.active)
    }

    @Test fun endedWaitsForTheResultAndPumpsMeanwhile() {
        begin()
        engine.state = CoopLinkEngine.STATE_ENDED
        val frames = 5
        repeat(frames) { battle.afterFrame() }
        assertTrue("still active: the result isn't ready", battle.active && ends.isEmpty())
        assertEquals("the bridge's state is polled every frame", frames, engine.stateCalls)
        engine.outgoing += linkBlock(1, 0)       // the game's last block after ENDED still goes out
        engine.result = linkResult(1)
        battle.afterFrame()
        assertEquals(1, channel.sent.size)
        assertEquals(1, ends.size)
        assertEquals(1, ends[0].result!![1].toInt())
        assertFalse(ends[0].left)
        assertEquals(1, engine.resets)
        assertFalse(battle.active); assertFalse(battle.pumping)
        battle.afterFrame()
        assertEquals("no more polling once the result is read", frames + 1, engine.stateCalls)
    }

    @Test fun endedReportsTheResultOnce() {
        begin()
        engine.state = CoopLinkEngine.STATE_ENDED; engine.result = linkResult(5)
        battle.afterFrame(); battle.afterFrame()
        assertEquals(1, ends.size); assertEquals(5, ends[0].result!![1].toInt())
    }


    @Test fun theCatchIsReadBeforeTheBridgeGoes() {
        begin()
        engine.caught = byteArrayOf(2) + ByteArray(100) { 7 }
        engine.state = CoopLinkEngine.STATE_ENDED; engine.result = linkResult(7)
        battle.afterFrame()
        val e = ends.single()
        assertEquals(2, e.caughtBy); assertEquals(100, e.caught!!.size); assertEquals(7, e.caught!![0].toInt())
    }

    @Test fun theCatchIsReadBeforeALeave() {
        begin()
        engine.caught = byteArrayOf(1) + ByteArray(100) { 3 }
        battle.linkDown(); t += 60_000; battle.afterFrame()
        assertEquals(1, ends.single().caughtBy)
    }

    @Test fun trailingDoneAfterTheEndIsDroppedNotRetried() {
        begin()
        engine.state = CoopLinkEngine.STATE_ENDED
        engine.acceptGive = false              // the ended game refuses the other player's trailing IS_DONE
        incoming(0)
        battle.afterFrame()
        assertEquals(1, battle.dropped)
        engine.acceptGive = true
        battle.afterFrame()
        assertTrue("never retried", engine.given.isEmpty())
        engine.result = linkResult(1)
        battle.afterFrame()
        assertEquals(1, ends.size)
    }

    @Test fun blocksRefusedBeforeTheBattleRunsAreRetriedNotDropped() {
        begin()
        engine.state = CoopLinkEngine.STATE_SETUP
        engine.acceptGive = false
        incoming(0)
        repeat(30) { battle.afterFrame() }
        assertEquals(0, battle.dropped)
        engine.acceptGive = true
        battle.afterFrame()
        assertEquals(1, engine.given.size)
    }

    @Test fun pumpGoesOnAfterALeaveUntilTheFieldIsBack() {
        begin()
        battle.linkDown(); t += 60_000; battle.afterFrame()
        assertFalse(battle.active); assertTrue("the tidy-up still needs the pump", battle.pumping)
        val calls = engine.stateCalls
        repeat(10) { battle.afterFrame() }
        assertEquals(calls + 10, engine.stateCalls)
        engine.settled = true
        battle.afterFrame()
        assertFalse(battle.pumping)
        val after = engine.stateCalls
        battle.afterFrame()
        assertEquals(after, engine.stateCalls)
    }

    @Test fun repeatedRefusalEndsLoudly() {
        begin()
        engine.acceptGive = false
        incoming(0)
        repeat(CoopLinkBattle.MAX_REFUSALS) { battle.afterFrame() }
        assertEquals(listOf(false), engine.leaves)
        assertFalse(battle.active)
        assertEquals(1, ui.notices.size)
        assertEquals("the other game must not wait for a game that left", listOf(id to CoopCancel.ENGINE), cancelsSent())
    }

    @Test fun fullOutboxEndsLoudlyNeverDropsOldest() {
        begin()
        channel.connected = false
        repeat(CoopLinkBattle.MAX_OUTBOX) { engine.outgoing += linkBlock(1, it and 0xFF) }
        battle.afterFrame()
        assertTrue(battle.active)
        engine.outgoing += linkBlock(1, 0)
        channel.connected = true
        battle.afterFrame()
        assertEquals(listOf(false), engine.leaves)
        assertEquals(1, ui.notices.size)
        assertEquals(listOf(id to CoopCancel.ENGINE), cancelsSent())
    }

    @Test fun cancelledBattleIsNotResumedByLinkUp() {
        begin()
        battle.onMessage(CoopBattleMsg.Cancel(id, CoopCancel.CLOSED))
        battle.afterFrame()
        battle.linkUp(); battle.afterFrame()
        t += 60_000; battle.afterFrame()
        assertEquals(listOf(false), engine.leaves)
    }

    @Test fun bridgeErrorEndsLoudly() {
        begin()
        engine.state = CoopLinkEngine.STATE_ERROR
        battle.afterFrame()
        assertEquals(listOf(false), engine.leaves)
        assertEquals(1, ui.notices.size)
        assertEquals(listOf(id to CoopCancel.ENGINE), cancelsSent())
    }

    @Test fun bridgeErrorLogsTheRefusedBlocksAsHex() {
        begin()
        // one record: check 944, give, kind 5, 4 bytes
        engine.bad = byteArrayOf(0xB0.toByte(), 0x03, 1, 5, 4, 0, 0x05, 0x88.toByte(), 0x14, 0x00)
        engine.state = CoopLinkEngine.STATE_ERROR
        battle.afterFrame()
        assertEquals(1, engine.badCalls)
        assertEquals(listOf("check=944 give kind=5 size=4 bytes=05881400"), battle.badBlocksLog(engine.bad))
        assertEquals(emptyList<String>(), battle.badBlocksLog(null))
    }

    @Test fun blocksRefusedBeforeTheOtherBeginAreRetriedWithinTheCap() {
        begin()
        engine.acceptGive = false
        incoming(0)
        repeat(CoopLinkBattle.MAX_REFUSALS - 1) { battle.afterFrame() }
        engine.acceptGive = true
        battle.afterFrame()
        assertEquals(1, engine.given.size)
        assertTrue(battle.active)
    }

    @Test fun secondStartWhileActiveIsRefused() {
        begin()
        var called = false
        assertEquals(0, battle.start(id + 1, false) { called = true; 1 })
        assertFalse(called)
        assertTrue(battle.active)
    }

    @Test fun startAnswersAreReturnedAndOnlyOneStartsIt() {
        assertEquals(-1, battle.start(id, false) { -1 }); assertFalse(battle.active)
        assertEquals(0, battle.start(id, false) { 0 }); assertFalse(battle.active)
        assertEquals(1, battle.start(id, false) { 1 }); assertTrue(battle.active)
    }

    @Test fun blockWhileInactiveIsDropped() {
        incoming(0)
        begin()
        battle.afterFrame()
        assertTrue(engine.given.isEmpty())
    }

    @Test fun theOtherPlayersFirstBlocksWaitForOurStart() {
        battle.expect(id)                       // the partner began first: its blocks are kept
        incoming(0); incoming(1); incoming(2, battleId = 5)
        assertTrue(begin())
        battle.afterFrame()
        assertEquals(listOf(0, 1), engine.given.map { it[1].toInt() })
    }

    @Test fun forgetDropsWhatWasKept() {
        battle.expect(id)
        incoming(0)
        battle.forget()
        begin(); battle.afterFrame()
        assertTrue(engine.given.isEmpty())
    }

    @Test fun startFailureLeavesWhatWasKept() {
        battle.expect(id)
        incoming(0)
        assertEquals(-1, battle.start(id, false) { -1 })
        assertEquals(1, battle.start(id, false) { 1 })
        battle.afterFrame()
        assertEquals(1, engine.given.size)
    }

    @Test fun aGameStillInItsOwnEndIsNeverCutShort() {
        begin()
        engine.state = CoopLinkEngine.STATE_ENDED            // an evolution scene / a prompt waits for the player
        engine.settled = false
        repeat(CoopLinkBattle.RESULT_FRAMES * 3) { battle.afterFrame() }
        assertTrue(ends.isEmpty()); assertTrue(battle.active)
        assertEquals(0, engine.resets); assertTrue(engine.leaves.isEmpty())
    }

    @Test fun aStuckBridgeOnAFreeFieldIsLeftThenReset() {
        begin()
        engine.state = CoopLinkEngine.STATE_ENDED
        engine.settled = true                                // back on the field, still no result
        repeat(CoopLinkBattle.RESULT_FRAMES) { battle.afterFrame() }
        assertEquals(1, ends.size); assertNull(ends[0].result); assertTrue(ends[0].left)
        assertEquals(listOf(false), engine.leaves)
        assertEquals(1, engine.resets)
        assertEquals(listOf(id to CoopCancel.ENGINE), cancelsSent())
    }

    @Test fun theInboxIsCappedInBytes() {
        battle.expect(id)
        repeat(100) { battle.onMessage(CoopBattleMsg.LinkBlock(id, it, linkBlock(1, it, 0x4000))) }   // 1.6 MB offered
        assertTrue(begin())
        repeat(5) { battle.afterFrame() }
        assertTrue("at most 1 MB kept: ${engine.given.size}", engine.given.size in 1..64)
    }
}
