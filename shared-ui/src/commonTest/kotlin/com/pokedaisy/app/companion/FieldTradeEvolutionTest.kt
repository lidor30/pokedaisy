package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.test.*

class FieldTradeEvolutionTest {
    private class Harness {
        val party = PARTY_ITEMS_EMERALD.copy(codeCrcs = emptyList())
        val fields = FieldItemSupport(FIELD_ITEMS_EMERALD.copy(codeCrcs = emptyList(), party = party), emptySet())
        val evolution = TRADE_EVO_EMERALD.copy(codeCrcs = emptyList())
        var metadata: TradeEvoFns? = evolution
        val record = ByteArray(100) { (it + 10).toByte() }
        var personality = 0xDEADBEEFL
        var enabled = true
        var onField = true
        var locked = false
        var battle = false
        var egg = false
        var eligible = true
        var count = 1
        var handoffs = 0
        val callsMade = mutableListOf<Pair<Long, List<Int>>>()
        val writes = mutableMapOf<Long, ByteArray>()
        val deliveries = mutableListOf<() -> Unit>()
        val calls = object : GameCalls {
            override fun read(addr: Long, len: Int): ByteArray = when (addr) {
                party.partyCount -> byteArrayOf(count.toByte()).copyOf(len)
                party.party -> record.copyOf(len)
                else -> ByteArray(len)
            }
            override fun write(addr: Long, bytes: ByteArray): Boolean {
                writes[addr] = bytes.copyOf()
                if (addr == party.party) bytes.copyInto(record)
                return true
            }
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long {
                callsMade += fn to listOf(a0, a1, a2, a3)
                return when (fn) {
                    fields.fns.fieldControlsLocked -> if (locked) 1 else 0
                    party.getMonData -> when (a1) {
                        0 -> personality
                        party.isEggField -> if (egg) 1 else 0
                        else -> 0
                    }
                    evolution.getEvolutionTarget -> {
                        // The game's held-item trade check can spend an item: preview must restore all 100 bytes.
                        record[40] = 0
                        if (eligible) 65 else 0
                    }
                    else -> 0
                }
            }
        }
        val game = GameSession(calls, { true }) { _, error -> throw error }
        var current: GameSession? = game
        val items = FieldItemUse({ fields }, { current }, { battle }) { deliveries += it }
        val trades = FieldTradeEvolution(items, items, { metadata }, { enabled }, { onField }, { handoffs++ })
        fun frame() = game.serviceField(true, true)
        fun deliver() { deliveries.toList().also { deliveries.clear() }.forEach { it() } }
        fun evolve(done: (TradeEvoResult) -> Unit) = trades.tradeEvolve(0, 0xDEADBEEFL, done)
    }

    @Test fun disabledOrUnsupportedGamesDoNotPreviewOrStartScenes() {
        for (unsupported in listOf(false, true)) {
            val h = Harness(); if (unsupported) h.metadata = null else h.enabled = false
            assertFalse(h.trades.canTradeEvolve()); var targets: Map<Long, Int>? = null
            h.trades.tradeEvolveTargets { targets = it }; assertEquals(emptyMap(), targets)
            var result: TradeEvoResult? = null; h.evolve { result = it }
            assertEquals(TradeEvoResult.FAILED, result); assertTrue(h.callsMade.isEmpty())
        }
    }

    @Test fun targetPreviewRestoresTheWholeRecordAndWaitsForUiDelivery() {
        val h = Harness(); val before = h.record.copyOf(); var targets: Map<Long, Int>? = null
        h.trades.tradeEvolveTargets { targets = it }; assertTrue(h.callsMade.isEmpty())
        h.frame(); assertNull(targets); assertContentEquals(before, h.record)
        h.deliver(); assertEquals(mapOf(0xDEADBEEFL to 65), targets); assertEquals(0, h.handoffs)
    }

    @Test fun evolutionQueuesTheGamesSceneAndHandsOverOnlyAfterSuccessDelivery() {
        val h = Harness(); var result: TradeEvoResult? = null
        h.evolve { result = it }; assertTrue(h.callsMade.isEmpty())
        h.frame(); assertNull(result); assertEquals(0, h.handoffs)
        assertEquals(listOf(h.party.party.toInt(), 65, 0, 0), h.callsMade.last().second)
        assertEquals(h.evolution.beginEvolutionScene, h.callsMade.last().first)
        val back = h.evolution.returnToField or 1
        assertContentEquals(ByteArray(4) { (back shr (8 * it)).toByte() }, h.writes[h.evolution.afterEvolution])
        h.deliver(); assertEquals(TradeEvoResult.EVOLVING, result); assertEquals(1, h.handoffs)
    }

    @Test fun changedMembersEggsAndIneligibleSpeciesNeverStartEvolution() {
        for (case in 0..3) {
            val h = Harness(); var result: TradeEvoResult? = null
            h.evolve { result = it }
            when (case) { 0 -> h.personality++; 1 -> h.egg = true; 2 -> h.eligible = false; else -> h.count = 0 }
            h.frame(); h.deliver()
            assertEquals(if (case == 0 || case == 3) TradeEvoResult.MOVED else TradeEvoResult.NO_EVOLUTION, result)
            assertTrue(h.callsMade.none { it.first == h.evolution.beginEvolutionScene }); assertEquals(0, h.handoffs)
        }
    }

    @Test fun fieldDepartureBattleMenusAndNativeLocksKeepTheSceneClosed() {
        for (case in 0..3) {
            val h = Harness(); var result: TradeEvoResult? = null
            if (case == 1) h.battle = true
            h.evolve { result = it }
            when (case) {
                0 -> h.onField = false
                2 -> h.locked = true
                3 -> h.game.observeScreens(gba = false, dsMenu = true)
            }
            h.frame(); h.deliver(); assertEquals(TradeEvoResult.NOT_NOW, result)
            assertTrue(h.callsMade.none { it.first == h.evolution.beginEvolutionScene }); assertEquals(0, h.handoffs)
        }
    }

    @Test fun turningTheSettingOffBeforeQueuedWorkRunsPreventsMutation() {
        val h = Harness(); var result: TradeEvoResult? = null
        h.evolve { result = it }; h.enabled = false; h.frame(); h.deliver()
        assertEquals(TradeEvoResult.FAILED, result); assertTrue(h.callsMade.isEmpty())
        h.enabled = true; var targets: Map<Long, Int>? = null
        h.trades.tradeEvolveTargets { targets = it }; h.enabled = false; h.frame(); h.deliver()
        assertEquals(emptyMap(), targets); assertTrue(h.callsMade.isEmpty())
    }

    @Test fun retiredGamesCannotRunQueuedScenesOrCloseTheNewGamesCompanion() {
        val closed = Harness(); var delivered = false
        closed.evolve { delivered = true }; closed.game.close(); closed.frame(); closed.deliver()
        assertFalse(delivered); assertTrue(closed.callsMade.isEmpty())
        val replaced = Harness(); replaced.evolve { delivered = true }; replaced.frame(); replaced.current = null; replaced.deliver()
        assertFalse(delivered); assertEquals(0, replaced.handoffs)
    }

    @Test fun transientSignatureReadsRetryAndVerifiedMismatchesAreCached() {
        var unavailable = true; var reads = 0
        val support = FieldTradeSupport { _, len -> reads++; if (unavailable) error("not ready") else ByteArray(len) }
        val snapshot = SnapshotView(connected = true, game = GameKind.EMERALD,
            party = listOf(MonView(64, "TEST", 36, 81, 81, "", emptyList(), null)))
        assertNull(support.get(snapshot, "BPEE", 0)); val first = reads; assertTrue(first > 0)
        unavailable = false; assertNull(support.get(snapshot, "BPEE", 0)); assertTrue(reads > first)
        val verified = reads; assertNull(support.get(snapshot, "BPEE", 0)); assertEquals(verified, reads)
        assertNull(support.get(snapshot, "BPEZ", 0)); assertEquals(verified, reads)
        assertNull(support.get(snapshot, "BPEJ", 0)); assertTrue(reads > verified)
        val japaneseVerified = reads
        assertNull(support.get(snapshot.copy(game = GameKind.FIRERED), "BPRE", 1))
        assertTrue(reads > japaneseVerified)
    }
}
