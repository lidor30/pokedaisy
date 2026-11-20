package com.pokedaisy.app

import com.pokedaisy.app.companion.coop.CoopFriendState
import com.pokedaisy.app.companion.coop.CoopPair
import com.pokedaisy.app.companion.coop.CoopPartner
import com.pokedaisy.app.companion.coop.CoopPeerId
import com.pokedaisy.app.companion.coop.CoopSaveId
import com.pokedaisy.app.companion.coop.CoopState
import com.pokedaisy.app.companion.data.coop.CoopBattleMsg
import com.pokedaisy.app.companion.data.coop.CoopBattleWire
import com.pokedaisy.app.companion.data.coop.CoopJoinRules.Spot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class CoopBattleTargetTest {
    private fun peer(b: Int) = CoopPeerId.of(ByteArray(32) { b.toByte() })
    private val a = peer(1)
    private val b = peer(2)
    private val c = peer(3)
    private val own = CoopSaveId(9, 9, ByteArray(8))

    private fun friend(p: CoopPeerId, up: Boolean = true): CoopFriendState {
        val partner = CoopPartner(p, "F", 1, false, CoopSaveId(p.bytes()[0].toInt() and 0xFF, 1, ByteArray(8)))
        return CoopFriendState(CoopPair(own, "E", partner, null, 1L),
            if (up) CoopState.Connected(partner, true) else CoopState.Connecting(partner))
    }

    private var friends = listOf(friend(a), friend(b), friend(c))
    private val spots = HashMap<CoopPeerId, Spot>()
    private val sent = ArrayList<Pair<CoopPeerId, Int>>()
    private var now = 100_000L
    private val t = CoopBattleTarget({ friends }, { p, type, _ -> sent += p to type; true }, { spots[it] }, { now })
    private val heard = ArrayList<Pair<Int, ByteArray>>()
    private val job: Job = GlobalScope.launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { t.received.collect { heard += it } }

    @After fun stop() = job.cancel()

    private val me = Spot(0, 16, 10, 10)
    private fun status(free: Boolean, ready: Boolean) = CoopBattleWire.encode(CoopBattleMsg.Status(free, ready)).second
    private fun heardStatus(i: Int) = CoopBattleWire.decode(heard[i].first, heard[i].second) as CoopBattleMsg.Status

    @Test fun statusGoesToEveryConnectedFriendTheRestToThePartnerOnly() {
        friends = listOf(friend(a), friend(b, up = false), friend(c))
        assertFalse(t.send(CoopBattleWire.OFFER, ByteArray(4)))   // no partner yet
        assertTrue(t.send(CoopBattleWire.STATUS, status(true, true)))
        assertEquals(listOf(a to CoopBattleWire.STATUS, c to CoopBattleWire.STATUS), sent)
        sent.clear()
        t.choose(me, 8)   // nobody's spot known: the first connected friend
        assertEquals(a, t.target)
        assertTrue(t.send(CoopBattleWire.OFFER, ByteArray(4)))
        assertEquals(listOf(a to CoopBattleWire.OFFER), sent)
        assertFalse(t.send(0x0100, ByteArray(4)))   // not a battle message
    }

    @Test fun thePartnerIsTheNearestFreeFriendInRange() {
        spots[a] = Spot(0, 16, 11, 10)      // nearest, but busy
        spots[b] = Spot(0, 16, 14, 10)      // free, in range
        spots[c] = Spot(0, 17, 10, 10)      // another map
        t.onMessage(a, CoopBattleWire.STATUS, status(free = false, ready = true))
        t.onMessage(b, CoopBattleWire.STATUS, status(free = true, ready = true))
        t.onMessage(c, CoopBattleWire.STATUS, status(free = true, ready = true))
        assertTrue(t.choose(me, 8))
        assertEquals(b, t.target)
        // Out of range: the nearest on the map wins instead (the flow then finds them too far to offer).
        assertEquals(a, t.pick(me, 2))
        // Nobody on this map: the partner stays while connected.
        spots.clear()
        assertEquals(b, t.pick(me, 8))
    }

    @Test fun switchingReplaysTheNewPartnersStatus() {
        spots[a] = Spot(0, 16, 11, 10)
        t.onMessage(a, CoopBattleWire.STATUS, status(free = true, ready = true))
        assertTrue(heard.isEmpty())   // not the partner yet: kept, not heard
        t.choose(me, 8)
        assertEquals(a, t.target)
        assertTrue(heardStatus(0).free)
        // A friend never heard from: the flow is told "not free, not ready" rather than keep the last partner's.
        spots.clear(); spots[b] = Spot(0, 16, 10, 11)
        t.choose(me, 8)
        assertEquals(b, t.target)
        assertFalse(heardStatus(1).free)
        assertFalse(heardStatus(1).ready)
    }

    @Test fun onlyThePartnersMessagesAreHeard() {
        spots[a] = Spot(0, 16, 11, 10)
        t.choose(me, 8)
        heard.clear()
        t.onMessage(a, CoopBattleWire.ANSWER, byteArrayOf(1))
        t.onMessage(b, CoopBattleWire.ANSWER, byteArrayOf(2))
        t.onMessage(b, CoopBattleWire.LINK_BLOCK, byteArrayOf(3))
        assertEquals(listOf(1), heard.map { it.second[0].toInt() })
    }

    @Test fun anOfferFromAnotherFriendMakesThemThePartnerWhileIdle() {
        spots[a] = Spot(0, 16, 11, 10)
        spots[b] = Spot(0, 16, 13, 10)
        t.choose(me, 8)
        assertEquals(a, t.target)
        heard.clear()
        t.onMessage(b, CoopBattleWire.STATUS, status(free = true, ready = true))
        t.onMessage(b, CoopBattleWire.OFFER, byteArrayOf(7))
        assertEquals(b, t.target)
        assertEquals(listOf(CoopBattleWire.STATUS, CoopBattleWire.OFFER), heard.map { it.first })
        assertTrue(heardStatus(0).free)
        // The pick holds while the flow answers: the nearest-friend rule doesn't take it back...
        t.idle = true
        assertFalse(t.choose(me, 8))
        assertEquals(b, t.target)
        // ...until the hold is over: then the nearest free friend again (a, once it says it is free).
        t.onMessage(a, CoopBattleWire.STATUS, status(free = true, ready = true))
        assertFalse(t.choose(me, 8))
        now += CoopBattleTarget.OFFER_HOLD_MS
        assertTrue(t.choose(me, 8))
        assertEquals(a, t.target)
    }

    @Test fun anOfferIsDroppedWhileABattleIsUnderWay() {
        spots[a] = Spot(0, 16, 11, 10)
        t.choose(me, 8)
        t.idle = false
        heard.clear()
        t.onMessage(b, CoopBattleWire.OFFER, byteArrayOf(7))
        assertEquals(a, t.target)
        assertTrue(heard.isEmpty())
        assertFalse(t.choose(me, 8))   // and the partner never changes mid-battle
    }

    // The glue reports the flow's idle only every 250 ms (SPOTS_MS): what goes on with the partner in between must hold
    // the pick at once, or another friend's OFFER takes the partner mid-offer.
    @Test fun ourOwnOfferHoldsThePartnerBeforeTheGluesNextReport() {
        spots[a] = Spot(0, 16, 11, 10)
        t.choose(me, 8)   // the glue's report: idle, partner a
        assertEquals(a, t.target)
        assertTrue(t.send(CoopBattleWire.OFFER, ByteArray(4)))   // this frame the flow offers to a
        heard.clear()
        t.onMessage(c, CoopBattleWire.OFFER, byteArrayOf(7))     // c's offer arrives before the next report
        assertEquals(a, t.target)
        assertTrue(heard.isEmpty())
    }

    @Test fun thePartnersOfferHoldsThePartnerBeforeTheGluesNextReport() {
        spots[a] = Spot(0, 16, 11, 10)
        t.choose(me, 8)
        t.onMessage(a, CoopBattleWire.OFFER, byteArrayOf(1))     // a offers: the flow is about to prompt
        heard.clear()
        t.onMessage(c, CoopBattleWire.OFFER, byteArrayOf(7))     // c's offer in the same window
        assertEquals(a, t.target)
        assertTrue(heard.isEmpty())
    }

    @Test fun theGluesNextIdleReportFreesThePickAgain() {
        spots[a] = Spot(0, 16, 11, 10)
        t.choose(me, 8)
        t.send(CoopBattleWire.OFFER, ByteArray(4))
        t.idle = true   // the flow came back to idle (declined, timed out): the glue says so
        t.onMessage(c, CoopBattleWire.OFFER, byteArrayOf(7))
        assertEquals(c, t.target)
    }

    @Test fun statusNeverHoldsThePick() {
        spots[a] = Spot(0, 16, 11, 10)
        t.choose(me, 8)
        t.send(CoopBattleWire.STATUS, status(free = true, ready = true))   // once a second, to every friend
        t.onMessage(a, CoopBattleWire.STATUS, status(free = true, ready = true))
        assertTrue(t.idle)
        t.onMessage(c, CoopBattleWire.OFFER, byteArrayOf(7))
        assertEquals(c, t.target)
    }

    @Test fun aPartnerWhoIsNoFriendAnyMoreGoes() {
        spots[a] = Spot(0, 16, 11, 10)
        t.choose(me, 8)
        assertTrue(t.connectedState.value)
        friends = listOf(friend(b), friend(c))
        t.onFriendsChanged()
        assertNull(t.target)
        assertFalse(t.connectedState.value)
        assertFalse(t.connected)
    }

    @Test fun connectedFollowsThePartnersLink() {
        spots[a] = Spot(0, 16, 11, 10)
        t.choose(me, 8)
        assertTrue(t.connected)
        friends = listOf(friend(a, up = false), friend(b), friend(c))
        t.onFriendsChanged()
        assertFalse(t.connectedState.value)
        assertEquals(a, t.target)   // kept until the flow is idle and picks again
    }
}
