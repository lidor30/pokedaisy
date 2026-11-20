package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.FollowerCore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import okio.Path.Companion.toPath
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class GameFollowerTest {
    private class Queue : CoroutineDispatcher() {
        val jobs = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { jobs.addLast(block) }
        fun one() { jobs.removeFirst().run() }
        fun all() { while (jobs.isNotEmpty()) one() }
    }
    private class Harness(worker: CoroutineDispatcher = Dispatchers.Unconfined) {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store()).also { it.follower = true }
        var allowed = true
        var inventory = setOf(1, 2)
        var reads = 0
        val copies = mutableListOf<Int?>()
        val shows = mutableListOf<Boolean>()
        private val sprites = (1..2).associateWith { n -> MonArt.Mon(n, ByteArray(3072) { n.toByte() },
            ByteArray(32), byteArrayOf(), byteArrayOf(), byteArrayOf()) }
        val core = object : FollowerCore {
            override fun setFollower(frames: ByteArray?, palette: ByteArray?) { copies += frames?.first()?.toInt() }
            override fun showFollower(on: Boolean) { shows += on }
        }
        val owner = GameFollower(prefs, core, "unused".toPath(), { allowed },
            available = { inventory }, load = { reads++; sprites[it] }, national = { _, species -> species },
            delivery = Dispatchers.Unconfined, worker = worker)
        fun snapshot(species: Int, eggFirst: Boolean = false) = SnapshotView(connected = true, game = GameKind.EMERALD,
            party = (if (eggFirst) listOf(mon(2).copy(isEgg = true)) else emptyList()) + mon(species),
            pokedex = PokedexState(POKEDEX_EMERALD, emptySet(), emptySet(), false))
        private fun mon(n: Int) = MonView(n, "Test", 5, 10, 10, "", emptyList(), null)
    }

    @Test fun nonEggLeadLoadsOnceAndFramesCopyOnlyChangesWhileVisibilityResendsEverySecond() {
        val h = Harness(); h.owner.observe(h.snapshot(1, true)); assertTrue(h.owner.ready)
        h.owner.step(true); assertEquals(listOf<Int?>(1), h.copies); assertEquals(listOf(true), h.shows)
        repeat(59) { h.owner.step(true) }; assertEquals(1, h.copies.size); assertEquals(1, h.shows.size)
        h.owner.step(true); assertEquals(listOf(true, true), h.shows)
        repeat(3) { h.owner.observe(h.snapshot(1)) }; assertEquals(1, h.reads)
        h.owner.observe(h.snapshot(2)); h.owner.step(true); assertEquals(listOf<Int?>(1, 2), h.copies)
        h.owner.step(false); assertEquals(false, h.shows.last())
        h.owner.reset(); h.owner.step(true); assertEquals(listOf<Int?>(1, 2, 2), h.copies)
        h.owner.close()
    }

    @Test fun disabledMissingAndUnsupportedLeadsClearTheOldSpriteWithoutRepeatedCopies() {
        val h = Harness(); h.owner.observe(h.snapshot(1)); h.owner.step(true)
        h.owner.set(false); assertFalse(h.prefs.follower); repeat(10) { h.owner.step(true) }
        assertEquals(listOf(1, null), h.copies); assertFalse(h.shows.last())
        h.owner.set(true); h.owner.step(true); assertEquals(1, h.copies.last())
        h.owner.observe(h.snapshot(3)); h.owner.step(true); assertNull(h.copies.last())
        h.owner.observe(h.snapshot(1)); h.owner.step(true); h.allowed = false; h.owner.step(true)
        assertNull(h.copies.last()); assertFalse(h.shows.last()); assertFalse(h.owner.supported)
        h.owner.close(); h.allowed = true; h.owner.observe(h.snapshot(2)); h.owner.set(true); h.owner.step(true)
        assertNull(h.copies.last()); assertFalse(h.owner.supported)
    }

    @Test fun delayedLookupCannotPublishThePrecedingLeadOrAClosedGame() {
        val queue = Queue(); val h = Harness(queue); queue.all()
        h.owner.observe(h.snapshot(1)); queue.one() // inventory delivered; first lookup queued
        h.owner.observe(h.snapshot(2)); queue.one() // old lookup completes after the new lead arrived
        h.owner.step(true); assertTrue(h.copies.isEmpty())
        queue.all(); h.owner.step(true); assertEquals(listOf<Int?>(2), h.copies)
        h.owner.observe(h.snapshot(1)); h.owner.close(); queue.all(); h.owner.step(true)
        assertEquals(listOf(2, null), h.copies); assertFalse(h.shows.last())
    }

    @Test fun supportedGamesExcludeBlockedNonGbaUnknownFieldsAndGamesWithTheirOwnFollowers() {
        assertTrue(GameFollower.supported(true, false, true, GameKind.EMERALD))
        assertFalse(GameFollower.supported(false, false, true, GameKind.EMERALD))
        assertFalse(GameFollower.supported(true, true, true, GameKind.EMERALD))
        assertFalse(GameFollower.supported(true, false, false, GameKind.EMERALD))
        for (kind in listOf(GameKind.HEART_AND_SOUL, GameKind.SOULGOLD, GameKind.ROWE, GameKind.EMERALD_SEAGLASS, GameKind.RECHARGED_YELLOW))
            assertFalse(GameFollower.supported(true, false, true, kind))
    }
}
