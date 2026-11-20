package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.test.*

class FieldMoveScreensTest {
    private class Harness {
        val pc = PcFns(0x08001000, 3, 0x08002000, 0x08003001, listOf(420107693L, 420107693L))
        val party = PARTY_ITEMS_EMERALD.copy(codeCrcs = emptyList())
        val fns = FIELD_ITEMS_EMERALD.copy(codeCrcs = emptyList(), party = party, pc = pc)
        var fields: FieldItemSupport? = FieldItemSupport(fns, emptySet())
        val tm = TM_EMERALD.copy(codeCrcs = emptyList())
        var metadata: TmFns? = tm
        val relearn = PokemonCenter.Relearn(1, 0x08007001, 0x02000200, 0x08008001)
        val center = PokemonCenter.Found(pc.specials, 0, null, null, relearn, null, null, 0x02000100, 0x02000104, 368, 378)
        var personality = 123L
        var battle = false
        var locked = false
        var ownsTm = true
        var rejectWrites = false
        var handoffs = 0
        val deliveries = mutableListOf<() -> Unit>()
        val called = mutableListOf<Long>()
        val written = mutableMapOf<Long, ByteArray>()
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long {
                called += fn
                return when (fn) {
                    fns.fieldControlsLocked -> if (locked) 1 else 0
                    fns.checkBagHasItem -> if (ownsTm) 1 else 0
                    party.getMonData -> if (a1 == 0) personality else 0
                    else -> 0
                }
            }
            override fun read(addr: Long, len: Int): ByteArray = when (addr) {
                pc.specials + 4L * pc.special -> ByteArray(len) { (pc.pcFn shr (8 * it)).toByte() }
                party.partyCount -> byteArrayOf(1).copyOf(len)
                else -> ByteArray(len)
            }
            override fun write(addr: Long, bytes: ByteArray): Boolean {
                if (rejectWrites) return false
                written[addr] = bytes.copyOf(); return true
            }
        }
        val game = GameSession(calls, { true }) { _, error -> throw error }
        var current: GameSession? = game
        val items = FieldItemUse({ fields }, { current }, { battle }, { center }, { metadata }, { handoffs++ }) { deliveries += it }
        fun frame() = game.serviceField(true, true)
        fun fade() { repeat(TM_FADE_FRAMES + 1) { frame() } }
        fun deliver() { deliveries.toList().also { deliveries.clear() }.forEach { it() } }
    }

    @Test fun tmWaitsForTheFadeAndReturnsToTheFieldInsteadOfAnUnopenedBag() {
        val h = Harness(); var result: ItemUseOutcome? = null
        assertTrue(h.items.canUse(339)); assertTrue(h.items.marked(339))
        h.items.use(339) { result = it }; assertTrue(h.called.isEmpty())
        h.frame(); assertNull(result); assertTrue(h.tm.fadeScreen in h.called)
        assertFalse(h.tm.cleanupOverworld in h.called); assertEquals(0, h.handoffs)
        h.deliver(); assertEquals(ItemUseResult.IN_GAME, result?.result); assertEquals(1, h.handoffs)
        h.fade(); assertTrue(h.tm.showPartyMenuForItemUse in h.called)
        assertContentEquals(byteArrayOf(83, 1), h.written[h.tm.specialVarItemId])
        assertTrue(h.tm.setMainCallback2 in h.called)
    }

    @Test fun missingItemsAndBattleRequestsNeverFadeOrHandOver() {
        val missing = Harness(); missing.ownsTm = false; var result: ItemUseOutcome? = null
        missing.items.use(339) { result = it }; missing.frame(); missing.deliver()
        assertEquals(ItemUseResult.NONE_LEFT, result?.result); assertEquals(0, missing.handoffs)
        assertFalse(missing.tm.fadeScreen in missing.called)
        val battle = Harness(); battle.battle = true
        battle.items.use(339) { result = it }; battle.frame(); battle.deliver()
        assertEquals(ItemUseResult.IN_BATTLE, result?.result); assertFalse(battle.tm.fadeScreen in battle.called)
    }

    @Test fun leavingTheFieldOrClosingTheSessionDuringTheFadeNeverStartsTheScreen() {
        for (close in listOf(false, true)) {
            val h = Harness(); h.items.use(339) {}; h.frame()
            if (close) h.game.close() else h.game.observeScreens(gba = false, dsMenu = true)
            h.fade(); assertFalse(h.tm.cleanupOverworld in h.called)
        }
    }

    @Test fun relearnerChecksTheSelectedIdentityAgainAfterFading() {
        val h = Harness(); var result: CenterResult? = null
        assertEquals(true, h.items.center()?.relearn)
        h.items.centerRelearn(0, 123) { result = it }; h.frame(); h.deliver()
        assertEquals(CenterResult.OK, result); assertEquals(1, h.handoffs)
        h.personality++; h.fade()
        assertFalse(h.tm.cleanupOverworld in h.called)
        assertTrue(h.tm.unlockControls in h.called)
    }

    @Test fun relearnerUsesTheNativeScreenAndItsScriptReturnCallback() {
        val h = Harness()
        h.items.centerRelearn(0, 123) {}; h.frame(); h.fade()
        assertTrue(h.tm.cleanupOverworld in h.called)
        assertTrue(h.tm.setMainCallback2 in h.called)
        assertTrue(PokemonCenter.SCRIPT in h.written)
        assertTrue(h.relearn.fieldCallback in h.written)
    }

    @Test fun replacedGameDropsScreenHandoffAndUnavailableMetadataHidesRelearning() {
        val h = Harness(); var delivered = false
        h.items.use(339) { delivered = true }; h.frame(); h.current = null; h.deliver()
        assertFalse(delivered); assertEquals(0, h.handoffs)
        h.metadata = null; assertEquals(false, h.items.center()?.relearn)
    }
    @Test fun transientRomReadFailuresRetryButVerifiedMismatchesAreCached() {
        var unavailable = true; var reads = 0
        val support = FieldTmSupport { _, len -> reads++; if (unavailable) error("not ready") else ByteArray(len) }
        val snapshot = SnapshotView(connected = true, game = GameKind.FIRERED,
            party = listOf(MonView(1, "TEST", 5, 10, 10, "", emptyList(), null)))
        assertNull(support.get(snapshot, "BPRE", 1)); val first = reads; assertTrue(first > 0)
        unavailable = false
        assertNull(support.get(snapshot, "BPRE", 1)); assertTrue(reads > first)
        val verified = reads
        assertNull(support.get(snapshot, "BPRE", 1)); assertEquals(verified, reads)
        assertNull(support.get(snapshot, "BPRE", 0)); assertTrue(reads > verified)
    }

    @Test fun missingGameIdentityDoesNotReadRomOrOfferTeaching() {
        var reads = 0
        val support = FieldTmSupport { _, len -> reads++; ByteArray(len) }
        assertNull(support.get(SnapshotView(connected = false), null, 0)); assertEquals(0, reads)
        val h = Harness(); h.fields = null
        assertFalse(h.items.canTeach(339)); assertFalse(h.items.canUse(339))
    }

}
