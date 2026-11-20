package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.test.*

class FieldPartyActionsTest {
    private class Harness {
        val config = NATIVE_EMERALD
        var layout: NativeConfig? = config
        val ram = mutableMapOf<Long, Byte>()
        val first = ByteArray(config.monStride) { (it + 30).toByte() }.also { putIdentity(it, 11) }
        val second = ByteArray(config.monStride) { (it + 90).toByte() }.also { putIdentity(it, 22) }
        var writes = 0
        var failWrite = -1
        var failReads = false
        var field = GameField.KNOWN
        var snapshot = SnapshotView(connected = true, game = GameKind.EMERALD,
            party = listOf(MonView(64, "FIRST", 36, 81, 81, "", emptyList(), null, personality = 11),
                MonView(64, "SECOND", 36, 81, 81, "", emptyList(), null, personality = 22)))
        val deliveries = mutableListOf<() -> Unit>()
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
            override fun read(addr: Long, len: Int): ByteArray {
                if (failReads) error("unavailable memory")
                return ByteArray(len) { ram[addr + it] ?: 0 }
            }
            override fun write(addr: Long, bytes: ByteArray): Boolean {
                writes++
                if (writes == failWrite) return false
                store(addr, bytes); return true
            }
        }
        val game = GameSession(calls, { true }) { _, error -> throw error }
        var current: GameSession? = game
        val items = FieldItemUse({ null }, { current }, { snapshot.inBattle }) { deliveries += it }
        val actions = FieldPartyActions(items, { current }, { snapshot }, { layout }, { field }) { deliveries += it }
        init {
            store(config.playerParty, first); store(config.playerParty + config.monStride, second)
            store(config.playerPartyCount, byteArrayOf(2))
        }
        fun store(addr: Long, bytes: ByteArray) { bytes.forEachIndexed { i, byte -> ram[addr + i] = byte } }
        fun frame() = game.serviceField(true, true)
        fun deliver() { deliveries.toList().also { deliveries.clear() }.forEach { it() } }
        fun swap(done: (PartySwapResult) -> Unit) = actions.reorderParty(0, 1, done)
        companion object {
            fun putIdentity(bytes: ByteArray, value: Long) { repeat(4) { bytes[it] = (value shr (8 * it)).toByte() } }
        }
    }

    @Test fun reorderMovesWholeRecordsOnTheGameQueueAndDeliversOnTheUiQueue() {
        val h = Harness(); var result: PartySwapResult? = null
        assertTrue(h.actions.canReorderParty()) // Reordering does not require a bag-action table.
        h.swap { result = it }; assertEquals(0, h.writes); assertNull(result)
        h.frame(); assertNull(result)
        assertContentEquals(h.second, h.calls.read(h.config.playerParty, h.config.monStride))
        assertContentEquals(h.first, h.calls.read(h.config.playerParty + h.config.monStride, h.config.monStride))
        h.deliver(); assertEquals(PartySwapResult.SWAPPED, result)
    }

    @Test fun staleSelectedMembersOrPartyCountNeverWriteAnything() {
        for (count in listOf(false, true)) {
            val h = Harness(); var result: PartySwapResult? = null
            h.swap { result = it }
            if (count) h.store(h.config.playerPartyCount, byteArrayOf(3))
            else h.store(h.config.playerParty, byteArrayOf(12, 0, 0, 0))
            h.frame(); h.deliver(); assertEquals(PartySwapResult.FAILED, result); assertEquals(0, h.writes)
        }
    }

    @Test fun battleFieldLocksAndMenusKeepPartyRecordsUntouched() {
        val battle = Harness(); var result: PartySwapResult? = null
        battle.snapshot = battle.snapshot.copy(inBattle = true); battle.swap { result = it }
        assertEquals(PartySwapResult.IN_BATTLE, result); assertEquals(0, battle.writes)
        for (menu in listOf(false, true)) {
            val h = Harness(); h.swap { result = it }
            if (menu) h.game.observeScreens(gba = false, dsMenu = true) else h.field = GameField.KNOWN or GameField.LOCKED
            h.frame(); h.deliver(); assertEquals(PartySwapResult.NOT_NOW, result); assertEquals(0, h.writes)
        }
    }

    @Test fun invalidIndicesAndChangedLayoutsDoNotSwapRecords() {
        val h = Harness(); var result: PartySwapResult? = null
        h.actions.reorderParty(0, 0) { result = it }; assertEquals(PartySwapResult.FAILED, result)
        h.actions.reorderParty(0, 2) { result = it }; assertEquals(PartySwapResult.FAILED, result)
        h.swap { result = it }; h.layout = null; h.frame(); h.deliver()
        assertEquals(PartySwapResult.FAILED, result); assertEquals(0, h.writes)
    }

    @Test fun aPartialWriteUsesTheExistingWholeRecordRollback() {
        val h = Harness(); h.failWrite = 2; var result: PartySwapResult? = null
        h.swap { result = it }; h.frame(); h.deliver()
        assertEquals(PartySwapResult.FAILED, result); assertEquals(4, h.writes)
        assertContentEquals(h.first, h.calls.read(h.config.playerParty, h.config.monStride))
        assertContentEquals(h.second, h.calls.read(h.config.playerParty + h.config.monStride, h.config.monStride))
    }

    @Test fun readFailureReportsFailureWithoutLeavingTheUiWaiting() {
        val h = Harness(); h.failReads = true; var result: PartySwapResult? = null
        h.swap { result = it }; h.frame(); h.deliver()
        assertEquals(PartySwapResult.FAILED, result); assertEquals(0, h.writes)
    }

    @Test fun retiredGamesCannotPerformQueuedReordersOrDeliverOldResults() {
        val before = Harness(); var called = false
        before.swap { called = true }; before.current = null; before.frame(); before.deliver()
        assertFalse(called); assertEquals(0, before.writes)
        val after = Harness(); after.swap { called = true }; after.frame(); after.current = null; after.deliver()
        assertFalse(called)
        val closed = Harness(); closed.swap { called = true }; closed.game.close(); closed.frame(); closed.deliver()
        assertFalse(called); assertEquals(0, closed.writes)
    }

    @Test fun unavailableStorageHasNoTransferActionsAndKeepsBattleRefusalSpecific() {
        val h = Harness(); var result: BoxSwapResult? = null
        assertFalse(h.actions.canSwapBox())
        h.actions.withdrawFromBox(0, 0, 11) { result = it }
        assertEquals(BoxSwapResult.FAILED, result); assertEquals(0, h.writes)
        assertEquals(BoxSwapResult.IN_BATTLE, fieldActionBattleResult(BoxSwapResult.NOT_NOW))
    }
    @Test fun onlyRecognizedVanillaStorageOffersTransfers() {
        val old = activeNativeConfig
        try {
            val h = Harness()
            val items = FieldItemUse({ FieldItemSupport(FIELD_ITEMS_EMERALD, emptySet()) }, { h.current }, { false }) {}
            val actions = FieldPartyActions(items, { h.current }, { h.snapshot }, { h.layout }, { h.field }) {}
            val config = NATIVE_EMERALD_RETAIL.copy(pokemonStoragePtr = BOX_SWAP_EMERALD.storagePtr)
            activeNativeConfig = config; assertTrue(actions.canSwapBox())
            activeNativeConfig = config.copy(pcStorage = UNBOUND_PC_STORAGE); assertFalse(actions.canSwapBox())
            activeNativeConfig = config.copy(pokemonStoragePtr = 0); assertFalse(actions.canSwapBox())
        } finally { activeNativeConfig = old }
    }

    @Test fun aBattleStartingBeforeTheQueuedSwapRunsStillRefusesIt() {
        for (nativeFlag in listOf(false, true)) {
            val h = Harness(); var result: PartySwapResult? = null
            h.swap { result = it }
            if (nativeFlag) h.field = GameField.KNOWN or GameField.BATTLE
            else h.snapshot = h.snapshot.copy(inBattle = true)
            h.frame(); h.deliver(); assertEquals(PartySwapResult.IN_BATTLE, result); assertEquals(0, h.writes)
        }
    }

}
