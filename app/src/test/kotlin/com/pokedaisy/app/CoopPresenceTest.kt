package com.pokedaisy.app

import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.coop.CoopPresenceLink
import com.pokedaisy.app.companion.data.coop.PartnerPlacement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopPresenceTest {
    private class FakeCore : PartnerCore {
        var self: IntArray? = null
        val places = ArrayList<PartnerPlacement?>()
        val arts = ArrayList<ByteArray?>()
        override fun self() = self
        override fun setArt(art: ByteArray?) { arts.add(art) }
        override fun place(p: PartnerPlacement?) { places.add(p) }
        fun lastPlace() = places.lastOrNull()
    }

    private class Link : CoopPresenceLink {
        var peer: CoopPresence? = null
        var sends = 0
        override fun send(bytes: ByteArray) { sends++; peer?.onReceived(bytes) }
        override val received: Flow<ByteArray> = emptyFlow()
    }

    private var now = 0L
    private val clock = { now }

    private class Side(val core: FakeCore, val presence: CoopPresence, val link: Link)

    private fun side(art: (Boolean, LookChar?) -> ByteArray? = { _, _ -> ByteArray(1) }): Side {
        val core = FakeCore()
        return Side(core, CoopPresence(core, clock, art), Link())
    }

    private fun pair(artB: (Boolean, LookChar?) -> ByteArray? = { _, _ -> ByteArray(1) }): Pair<Side, Side> {
        val a = side()
        val b = side(artB)
        a.link.peer = b.presence
        b.link.peer = a.presence
        a.presence.link = a.link
        b.presence.link = b.link
        return a to b
    }

    private fun self(group: Int, num: Int, fx: Int, fy: Int = 160, facing: Int = 1, mode: Int = 0, step: Int = 0) =
        intArrayOf(1, group, num, fx, fy, facing, mode, step)

    /** [frames] frames at 60 Hz of wall clock, [each] before every pair of onFrame calls. */
    private fun run(a: Side, b: Side, frames: Int, fieldA: Boolean = true, fieldB: Boolean = true, each: (Int) -> Unit = {}) {
        val start = now
        for (i in 0 until frames) {
            now = start + i * 1000L / 60
            each(i)
            a.presence.onFrame(fieldA)
            b.presence.onFrame(fieldB)
        }
    }

    @Test fun sendsTenTimesASecond() {
        val s = side()
        s.core.self = self(0, 16, 160)
        s.presence.link = s.link
        for (i in 0 until 60) { now = i * 1000L / 60; s.presence.onFrame(true) }
        assertTrue("sends ${s.link.sends}", s.link.sends in 10..11)
    }

    @Test fun partnerWalksOnMyScreen() {
        val (a, b) = pair()
        b.core.self = self(0, 16, 100)
        val startMs = 300L
        run(a, b, 90) { _ ->
            val t = now - startMs
            val fx = if (t <= 0) 160 else if (t >= 266) 176 else 160 + (16 * t / 266).toInt()
            a.core.self = self(0, 16, fx, mode = 1, facing = 4)
        }
        val xs = b.core.places.filterNotNull().map { it.fineX }
        assertTrue(xs.isNotEmpty())
        assertEquals(176, xs.last())
        assertTrue("monotonic $xs", xs.zipWithNext().all { (p, q) -> q >= p })
        assertTrue("walked through, not jumped: ${xs.distinct()}", xs.distinct().size > 4)
        assertTrue(b.core.lastPlace()!!.hflip)
    }

    @Test fun otherMapHides() {
        val (a, b) = pair()
        a.core.self = self(0, 16, 160)
        b.core.self = self(0, 17, 160)
        run(a, b, 60)
        assertNull(b.core.lastPlace())
        assertTrue(b.core.places.none { it != null })
    }

    @Test fun offFieldKeepsPartnerStanding() {
        val (a, b) = pair()
        a.core.self = self(0, 16, 160, mode = 1, facing = 4)
        b.core.self = self(0, 16, 100)
        run(a, b, 60)
        assertNotNull(b.core.lastPlace())
        // A in a battle: self() now reads other things, but A keeps sending its last field place, standing; its own place is null.
        a.core.self = self(0, 16, 999, fy = 5, facing = 2, mode = 4, step = 9)
        val before = b.core.places.size
        run(a, b, 90, fieldA = false)
        assertNull(a.core.lastPlace())
        val during = b.core.places.drop(before)
        assertTrue(during.all { it != null })
        val p = during.last()!!
        assertEquals(160, p.fineX)
        assertEquals(160, p.fineY)
        assertEquals(2, p.frame)   // walking sheet, standing facing west (east flipped)
        assertTrue(p.hflip)
        run(a, b, 30)
        assertNotNull(b.core.lastPlace())
    }

    @Test fun unknownLookAsksForOwnHero() {
        val asked = ArrayList<Pair<Boolean, LookChar?>>()
        val (a, b) = pair { f, l -> asked.add(f to l); if (l == LookChar.RED) null else ByteArray(1) }
        a.presence.myLook = LookChar.RED
        a.presence.myFemale = true
        a.core.self = self(0, 16, 160)
        b.core.self = self(0, 16, 100)
        run(a, b, 30)
        assertEquals(listOf(true to LookChar.RED, true to null), asked.take(2))
        assertEquals(1, b.core.arts.filterNotNull().size)
    }

    @Test fun linkGoneClears() {
        val (a, b) = pair()
        a.core.self = self(0, 16, 160)
        b.core.self = self(0, 16, 100)
        run(a, b, 60)
        assertNotNull(b.core.lastPlace())
        b.presence.stop()
        assertNull(b.core.lastPlace())
        assertNull(b.core.arts.last())
    }

    @Test fun firstFrameWithALinkPlacesNullOnce() {
        // The native request may still hold a previous game's partner (its old presence could not clear it): say none first.
        val s = side()
        s.core.self = self(0, 16, 160)
        s.presence.link = s.link
        for (i in 0 until 5) { now = i * 16L; s.presence.onFrame(true) }
        assertEquals(listOf<PartnerPlacement?>(null), s.core.places)
    }

    @Test fun noLinkDoesNothing() {
        val s = side()
        s.core.self = self(0, 16, 160)
        for (i in 0 until 10) { now = i * 16L; s.presence.onFrame(true) }
        assertEquals(1, s.core.places.size)
        assertNull(s.core.lastPlace())
        assertEquals(0, s.link.sends)
    }

    @Test fun outOfRangeSelfSendsInvalidInsteadOfCrashing() {
        val s = side()
        s.core.self = self(0, 16, 40000)
        s.presence.link = s.link
        s.presence.onFrame(true)
        assertEquals(1, s.link.sends)
    }
}
