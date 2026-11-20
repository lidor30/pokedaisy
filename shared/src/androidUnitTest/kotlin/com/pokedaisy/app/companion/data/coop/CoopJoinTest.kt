package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.coop.CoopJoinRules.Spot
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class CoopJoinTest {
    private var t = 1000L
    private val offer = CoopBattleMsg.Offer(7, 1, 0, 0, 25)

    private val roxanne = CoopJoinRules.StoryAt(166, 265, false)

    @Test fun storyOfferedOnlyAtSameSpot() {
        assertTrue(CoopJoin.storyOffer(roxanne, roxanne, true))
        assertFalse(CoopJoin.storyOffer(roxanne, CoopJoinRules.StoryAt(6, 266, false), true))
        assertFalse("same event, other trainer", CoopJoin.storyOffer(roxanne, CoopJoinRules.StoryAt(166, 266, false), true))
        assertFalse(CoopJoin.storyOffer(roxanne, CoopJoinRules.StoryAt.NONE, true))
        assertFalse(CoopJoin.storyOffer(CoopJoinRules.StoryAt.NONE, CoopJoinRules.StoryAt.NONE, true))
    }

    @Test fun storyNotOfferedWhenBeaten() {
        assertFalse(CoopJoin.storyOffer(roxanne.copy(beaten = true), roxanne, true))
        assertFalse(CoopJoin.storyOffer(roxanne, roxanne.copy(beaten = true), true))
    }

    @Test fun storyNotOfferedWhenBusy() {
        assertFalse(CoopJoin.storyOffer(roxanne, roxanne, false))
    }

    @Test fun nearbyByManhattanTiles() {
        val me = Spot(0, 9, 10, 10)
        assertTrue(CoopJoinRules.nearby(me, Spot(0, 9, 15, 13), 8))
        assertFalse(CoopJoinRules.nearby(me, Spot(0, 9, 15, 14), 8))
        assertFalse(CoopJoinRules.nearby(me, Spot(0, 8, 10, 10), 8))
        assertFalse(CoopJoinRules.nearby(me, Spot(1, 9, 10, 10), 8))
        assertFalse(CoopJoinRules.nearby(me, null, 8))
        assertFalse(CoopJoinRules.nearby(null, me, 8))
    }

    @Test fun offerNeedsEverything() {
        assertTrue(CoopJoinRules.mayOffer(1, true, true, true, true, true))
        assertTrue(CoopJoinRules.mayOffer(3, true, true, true, true, true))
        assertFalse(CoopJoinRules.mayOffer(0, true, true, true, true, true))
        assertFalse(CoopJoinRules.mayOffer(1, false, true, true, true, true))
        assertFalse(CoopJoinRules.mayOffer(1, true, false, true, true, true))
        assertFalse(CoopJoinRules.mayOffer(1, true, true, false, true, true))
        assertFalse(CoopJoinRules.mayOffer(1, true, true, true, false, true))
        assertFalse(CoopJoinRules.mayOffer(1, true, true, true, true, false))
    }

    @Test fun hostTimesOutAfterThirtySeconds() {
        val h = CoopHostJoin { t }
        h.battleStarting(7, true)
        assertTrue(h.holdGame)
        t += 29_999; h.tick()
        assertTrue(h.state is CoopHostJoin.Asking)
        t += 1; h.tick()
        assertEquals(CoopHostJoin.Single, h.state)
        assertFalse(h.holdGame)
    }

    @Test fun hostIgnoresOtherBattlesAnswer() {
        val h = CoopHostJoin { t }
        h.battleStarting(7, true)
        h.onAnswer(CoopBattleMsg.Answer(8, false))
        assertTrue(h.state is CoopHostJoin.Asking)
        h.onCancel(8)
        assertTrue(h.state is CoopHostJoin.Asking)
    }

    @Test fun acceptArms() {
        val h = CoopHostJoin { t }
        h.battleStarting(7, true)
        val a = CoopBattleMsg.Answer(7, true, count = 1)
        h.onAnswer(a)
        assertEquals(CoopHostJoin.Accepted(a), h.state)
        assertTrue(h.incomingOffer())
    }

    @Test fun noOfferIsSingle() {
        val h = CoopHostJoin { t }
        h.battleStarting(7, false)
        assertEquals(CoopHostJoin.Single, h.state)
    }

    @Test fun declineMeansSingle() {
        val h = CoopHostJoin { t }
        h.battleStarting(7, true)
        h.onAnswer(CoopBattleMsg.Answer(7, false))
        assertEquals(CoopHostJoin.Single, h.state)
    }

    @Test fun partnerBusyIsCancelled() {
        val p = CoopPartnerJoin { t }
        assertEquals(CoopBattleMsg.Cancel(7, CoopCancel.BUSY), p.onOffer(offer, free = false, ready = true))
        assertEquals(CoopPartnerJoin.Idle, p.state)
        assertEquals(CoopBattleMsg.Cancel(7, CoopCancel.BUSY), p.onOffer(offer, free = true, ready = false))
        assertNull(p.onOffer(offer, free = true, ready = true))
        assertEquals(CoopBattleMsg.Cancel(9, CoopCancel.BUSY), p.onOffer(offer.copy(battleId = 9), true, true))
    }

    @Test fun partnerPromptCountsDown() {
        val p = CoopPartnerJoin { t }
        assertNull(p.onOffer(offer, true, true))
        assertEquals(30, p.secondsLeft)
        t += 29_001
        assertEquals(1, p.secondsLeft)
        t += 999; p.tick()
        assertEquals(CoopPartnerJoin.Declined, p.state)
        assertEquals(0, p.secondsLeft)
    }

    @Test fun partnerAcceptDeclineAndCancel() {
        val p = CoopPartnerJoin { t }
        assertNull(p.accept()); assertNull(p.decline())
        p.onOffer(offer, true, true)
        assertEquals(7, p.accept())
        assertEquals(CoopPartnerJoin.Joining(7), p.state)
        p.onCancel(3)
        assertEquals(CoopPartnerJoin.Joining(7), p.state)
        p.onCancel(7)
        assertEquals(CoopPartnerJoin.Declined, p.state)
        p.onOffer(offer, true, true)
        assertEquals(7, p.decline())
        assertEquals(CoopPartnerJoin.Declined, p.state)
    }

    @Test fun crossedOffersBothDecline() {
        val h = CoopHostJoin { t }
        h.battleStarting(7, true)
        assertTrue(h.incomingOffer())
        h.onCancel(7)   // the other device's BUSY reaches us
        assertEquals(CoopHostJoin.Single, h.state)
    }

    @Test fun linkLostWhileAskingIsSingle() {
        val h = CoopHostJoin { t }
        h.battleStarting(7, true)
        h.linkLost()
        assertEquals(CoopHostJoin.Single, h.state)
    }
}
