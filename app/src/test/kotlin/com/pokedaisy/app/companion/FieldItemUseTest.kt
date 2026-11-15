package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import org.junit.Assert.*
import org.junit.Test

class FieldItemUseTest {
    private val calls = object : GameCalls {
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
        override fun read(addr: Long, len: Int) = ByteArray(len)
        override fun write(addr: Long, bytes: ByteArray) = true
    }
    private val fields = FieldItemSupport(FieldItemFns(0, 0, 0, 0, 0, 0, 0, emptyList()), emptySet(), canPc = true)
    private var current: GameSession? = GameSession(calls, { true }) { _, e -> throw e }
    private var battle = false
    private val deliveries = mutableListOf<() -> Unit>()
    private fun use(support: () -> FieldItemSupport? = { fields }) = FieldItemUse(support, { current }, { battle }) { deliveries += it }

    @Test fun noGameOrSupportFailsImmediatelyWithoutQueueing() {
        val result = mutableListOf<ItemUseOutcome>()
        use { null }.openPc(result::add)
        current = null
        use().openPc(result::add)
        assertEquals(listOf(ItemUseResult.FAILED, ItemUseResult.FAILED), result.map { it.result })
        assertTrue(deliveries.isEmpty())
    }

    @Test fun workUsesTheGameQueueAndResultUsesTheHostDispatcher() {
        var worked = false
        var answer: Int? = null
        use().onGame(-1, { answer = it }) { s, g, busy ->
            assertSame(fields, s); assertSame(calls, g); assertFalse(busy)
            worked = true; 42
        }
        assertFalse(worked); assertNull(answer)
        current!!.serviceField(true, true)
        assertTrue(worked); assertNull(answer)
        deliveries.single()()
        assertEquals(42, answer)
    }

    @Test fun battleIsCapturedAtRequestAndReportsWhyForBothResultShapes() {
        battle = true
        val use = use()
        var item: ItemUseOutcome? = null
        var party: PartyTargets? = null
        use.onGame(ItemUseOutcome(ItemUseResult.FAILED), { item = it }) { _, _, busy ->
            assertTrue(busy); ItemUseOutcome(ItemUseResult.NOT_NOW)
        }
        use.onGame(PartyTargets(ItemUseResult.FAILED), { party = it }) { _, _, _ -> PartyTargets(ItemUseResult.NOT_NOW) }
        battle = false
        current!!.serviceField(true, true)
        deliveries.forEach { it() }
        assertEquals(ItemUseResult.IN_BATTLE, item?.result)
        assertEquals(ItemUseResult.IN_BATTLE, party?.result)
    }

    @Test fun failedWorkReturnsTheFallbackAndDoesNotLoseTheFollowingAction() {
        val answers = mutableListOf<Int>()
        val use = use()
        use.onGame(-1, answers::add) { _, _, _ -> error("failed action") }
        use.onGame(-1, answers::add) { _, _, _ -> 7 }
        current!!.serviceField(true, true)
        deliveries.forEach { it() }
        assertEquals(listOf(-1, 7), answers)
    }

    @Test fun aResultForAReplacedGameIsDiscarded() {
        val answers = mutableListOf<Int>()
        use().onGame(-1, answers::add) { _, _, _ -> 42 }
        current!!.serviceField(true, true)
        current!!.close()
        current = GameSession(calls, { true }) { _, e -> throw e }
        deliveries.forEach { it() }
        assertTrue(answers.isEmpty())
    }
}
