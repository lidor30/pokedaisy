package com.pokedaisy.app

import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.coop.CoopPresenceLink
import com.pokedaisy.app.companion.data.coop.PartnerPlacement
import com.pokedaisy.app.companion.data.coop.PresenceCodec
import com.pokedaisy.app.companion.data.coop.PresenceMode
import com.pokedaisy.app.companion.data.coop.PresenceUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Co-op presence with several friends: one smoother each, up to [PartnerCore.slots] drawn, the nearest first. */
class CoopPresenceFriendsTest {
    private class SlotCore(override val slots: Int = 4) : PartnerCore {
        var self: IntArray? = null
        val placed = HashMap<Int, PartnerPlacement?>()
        val arts = HashMap<Int, ByteArray?>()
        val artCalls = ArrayList<Int>()
        override fun self() = self
        override fun setArt(art: ByteArray?) = setArt(0, art)
        override fun place(p: PartnerPlacement?) = place(0, p)
        override fun setArt(slot: Int, art: ByteArray?) { arts[slot] = art; artCalls += slot }
        override fun place(slot: Int, p: PartnerPlacement?) { placed[slot] = p }
        fun shown() = placed.filterValues { it != null }
    }

    private class Link : CoopPresenceLink {
        var sends = 0
        override fun send(bytes: ByteArray) { sends++ }
        override val received: Flow<ByteArray> = emptyFlow()
    }

    private var now = 10_000L
    private val core = SlotCore()
    private val link = Link()
    private val looks = ArrayList<LookChar?>()
    private val presence = CoopPresence(core, { now }) { _, look -> looks += look; byteArrayOf(look?.ordinal?.toByte() ?: -1) }
        .also { it.link = link }

    private fun self(group: Int, num: Int, fx: Int, fy: Int = 160) = intArrayOf(1, group, num, fx, fy, 1, 0, 0)

    /** [key] standing at ([fx], [fy]) on map 0.[num], sent at our [now] (their clock = ours). */
    private fun hear(key: String, fx: Int, fy: Int = 160, num: Int = 16, look: Int = 0) =
        presence.onReceivedFrom(key, PresenceCodec.encode(
            PresenceUpdate(now and 0xFFFFFFFFL, true, false, 0, num, fx, fy, 1, PresenceMode.WALK, 0, look)))

    /** [ms] of frames at 60 Hz; each friend in [standing] sends its place every 100 ms. */
    private fun run(ms: Long, standing: Map<String, Pair<Int, Int>>, num: Map<String, Int> = emptyMap()) {
        val end = now + ms
        var nextSend = now
        while (now < end) {
            if (now >= nextSend) {
                for ((k, at) in standing) hear(k, at.first, at.second, num[k] ?: 16)
                nextSend += 100
            }
            presence.onFrame(true)
            now += 16
        }
    }

    @Test fun twoFriendsAreDrawnInTwoSlots() {
        core.self = self(0, 16, 160)
        run(1_000, mapOf("b" to (176 to 160), "c" to (144 to 160)))
        val shown = core.shown()
        assertEquals(2, shown.size)
        assertEquals(setOf(144, 176), shown.values.map { it!!.fineX }.toSet())
        assertEquals(shown.keys, core.arts.filterValues { it != null }.keys)   // each drawn slot has art
    }

    @Test fun aFriendOnAnotherMapIsNotDrawn() {
        core.self = self(0, 16, 160)
        run(1_000, mapOf("b" to (176 to 160), "c" to (144 to 160)), num = mapOf("c" to 17))
        assertEquals(listOf(176), core.shown().values.map { it!!.fineX })
    }

    @Test fun theNearestFourOfFiveAreDrawnAndTheFifthTakesAFreedSlot() {
        core.self = self(0, 16, 160)
        val at = mapOf("b" to (176 to 160), "c" to (144 to 160), "d" to (160 to 176), "e" to (160 to 144), "far" to (400 to 160))
        run(1_000, at)
        assertEquals(4, core.shown().size)
        assertTrue(core.shown().values.none { it!!.fineX == 400 })
        // c's link drops: its slot goes to the one left out.
        presence.forget("c")
        run(1_000, at - "c")
        assertEquals(4, core.shown().size)
        assertTrue(core.shown().values.any { it!!.fineX == 400 })
        assertTrue(core.shown().values.none { it!!.fineX == 144 })
    }

    @Test fun aShownFriendKeepsItsSlot() {
        core.self = self(0, 16, 160)
        run(500, mapOf("b" to (200 to 160)))
        val slotOfB = core.shown().keys.single()
        // A nearer friend arrives: b stays where it is, the newcomer takes another slot.
        run(500, mapOf("b" to (200 to 160), "c" to (161 to 160)))
        assertEquals(200, core.placed[slotOfB]!!.fineX)
        assertEquals(2, core.shown().size)
    }

    @Test fun aSilentFriendIsForgotten() {
        core.self = self(0, 16, 160)
        run(500, mapOf("b" to (176 to 160)))
        assertNotNull(presence.lastReceivedOf("b"))
        run(CoopPresence.STALE_MS + 500, emptyMap())
        assertTrue(core.shown().isEmpty())
        assertNull(presence.lastReceivedOf("b"))
    }

    @Test fun eachSlotWearsItsFriendsLook() {
        core.self = self(0, 16, 160)
        val end = now + 500
        while (now < end) {
            presence.onReceivedFrom("b", PresenceCodec.encode(PresenceUpdate(now, true, false, 0, 16, 176, 160, 1, 0, 0, 1)))   // RED
            presence.onReceivedFrom("c", PresenceCodec.encode(PresenceUpdate(now, true, false, 0, 16, 144, 160, 1, 0, 0, 7)))   // GOLD
            presence.onFrame(true)
            now += 16
        }
        assertTrue(LookChar.RED in looks)
        assertTrue(LookChar.GOLD in looks)
        assertEquals(2, core.arts.values.filterNotNull().map { it[0] }.toSet().size)
    }

    @Test fun lastReceivedOfFeedsTheBattlesPerFriend() {
        core.self = self(0, 16, 160)
        hear("b", 176)
        hear("c", 144)
        assertEquals(176, presence.lastReceivedOf("b")!!.fineX)
        assertEquals(144, presence.lastReceivedOf("c")!!.fineX)
        assertNull(presence.lastReceived)   // the single partner's: nobody here
        assertEquals(setOf("b", "c"), presence.lastReceivedAll().keys)
    }

    @Test fun stopClearsEverySlot() {
        core.self = self(0, 16, 160)
        run(500, mapOf("b" to (176 to 160), "c" to (144 to 160)))
        presence.stop()
        assertTrue(core.shown().isEmpty())
        assertTrue(core.arts.values.all { it == null })
        assertEquals(4, core.placed.size)
    }
}
