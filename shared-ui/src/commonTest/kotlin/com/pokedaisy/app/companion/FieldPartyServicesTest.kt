package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.test.*

class FieldPartyServicesTest {
    private class Harness {
        val pc = PcFns(0x08001000, 3, 0x08002000, 0x08003001, listOf(420107693L, 420107693L))
        val party = PARTY_ITEMS_EMERALD.copy(codeCrcs = emptyList())
        var fields: FieldItemSupport? = FieldItemSupport(FIELD_ITEMS_EMERALD.copy(codeCrcs = emptyList(), party = party, pc = pc), emptySet())
        var metadata: PokemonCenter.Found? = found()
        fun found(nickname: Int? = 161, forget: Int? = 224) = PokemonCenter.Found(pc.specials, 0, nickname, forget,
            null, 0x08005001, null, 0x02000100, 0x02000104, 368, 378)
        var personality = 0x12345678L
        val expectedPersonality = personality
        var egg = false
        var traded = false
        var locked = false
        var validCode = true
        var battle = false
        var moves = intArrayOf(19, 93, 50, 60)
        val writes = linkedMapOf<Long, ByteArray>()
        val callsMade = mutableListOf<Pair<Long, Int>>()
        val deliveries = mutableListOf<() -> Unit>()
        var handoffs = 0
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long {
                callsMade += fn to a0
                return when (fn) {
                    fields?.fns?.fieldControlsLocked -> if (locked) 1 else 0
                    party.getMonData -> when (a1) {
                        0 -> personality // MON_PERSONALITY
                        45 -> if (egg) 1 else 0 // MON_IS_EGG
                        in 13..16 -> moves[a1 - 13].toLong() // MON_DATA_MOVE1..4
                        else -> 0
                    }
                    else -> 0
                }
            }
            override fun read(addr: Long, len: Int): ByteArray = when (addr) {
                pc.specials + 4L * pc.special -> ByteArray(len) { (pc.pcFn shr (8 * it)).toByte() }
                party.partyCount -> byteArrayOf(1).copyOf(len)
                0x02000104L -> byteArrayOf(if (traded) 1 else 0).copyOf(len)
                else -> ByteArray(len) { if (validCode) 0 else 1 }
            }
            override fun write(addr: Long, bytes: ByteArray): Boolean {
                writes[addr] = bytes.copyOf(); return true
            }
        }
        val core = GameSession(calls, { true }) { _, error -> throw error }
        var current: GameSession? = core
        val items = FieldItemUse({ fields }, { current }, { battle }, { metadata }, handedOver = { handoffs++ }) { deliveries += it }
        fun frame() = core.serviceField(true, true)
        fun deliver() { deliveries.toList().also { deliveries.clear() }.forEach { it() } }
        fun nickname(done: (CenterResult) -> Unit) = items.centerNickname(0, expectedPersonality, done)
        fun forget(done: (CenterResult) -> Unit) = items.centerForget(0, expectedPersonality, 2, 50, done)
    }

    @Test fun servicesAreOfferedOnlyWhenTheirVerifiedSpecialExists() {
        val h = Harness()
        assertEquals(CenterSupport(true, true, true, false), h.items.center())
        h.metadata = h.found(nickname = null)
        assertEquals(CenterSupport(true, false, true, false), h.items.center())
        h.metadata = h.found(forget = null)
        assertEquals(CenterSupport(true, true, false, false), h.items.center())
        h.metadata = null; assertNull(h.items.center())
    }

    @Test fun nicknameRunsOnTheGameQueueAndResultWaitsForUiDelivery() {
        val h = Harness(); var result: CenterResult? = null
        h.nickname { result = it }
        assertNull(result); assertTrue(h.writes.isEmpty())
        h.frame(); assertNull(result); assertEquals(0, h.handoffs)
        assertTrue(PokemonCenter.SCRIPT in h.writes)
        assertEquals(h.pc.setupScript to PokemonCenter.SCRIPT.toInt(), h.callsMade.last())
        h.deliver(); assertEquals(CenterResult.OK, result); assertEquals(1, h.handoffs)
    }

    @Test fun forgetKeepsTheRequestedIdentityAndMoveIndex() {
        val h = Harness(); var result: CenterResult? = null
        h.forget { result = it }; assertTrue(h.writes.isEmpty())
        h.frame(); h.deliver(); assertEquals(CenterResult.OK, result)
        val script = assertNotNull(h.writes[PokemonCenter.SCRIPT])
        assertContentEquals(byteArrayOf(0x69, 0x16, 4, 0x80.toByte(), 0, 0, 0x16, 5, 0x80.toByte(), 2, 0), script.copyOfRange(0, 11))
        assertEquals(setOf(PokemonCenter.SCRIPT), h.writes.keys); assertEquals(0, h.handoffs)
    }

    @Test fun aChangedPartyMemberOrMoveNeverRunsAScript() {
        for (nickname in listOf(true, false)) {
            val h = Harness(); h.personality++; var result: CenterResult? = null
            if (nickname) h.nickname { result = it } else h.forget { result = it }
            h.frame(); h.deliver(); assertEquals(CenterResult.PARTY_CHANGED, result)
            assertTrue(h.writes.isEmpty())
        }
        val h = Harness(); h.moves[2] = 51; var result: CenterResult? = null
        h.forget { result = it }; h.frame(); h.deliver()
        assertEquals(CenterResult.PARTY_CHANGED, result); assertTrue(h.writes.isEmpty())
    }

    @Test fun eggsTradedNamesAndLastMovesRetainTheGamesOwnRefusals() {
        val egg = Harness(); egg.egg = true; var result: CenterResult? = null
        egg.nickname { result = it }; egg.frame(); egg.deliver()
        assertEquals(CenterResult.EGG, result); assertTrue(egg.writes.isEmpty())
        val traded = Harness(); traded.traded = true
        traded.nickname { result = it }; traded.frame(); traded.deliver()
        assertEquals(CenterResult.TRADED, result); assertFalse(PokemonCenter.SCRIPT in traded.writes)
        val last = Harness(); last.moves = intArrayOf(0, 0, 50, 0)
        last.forget { result = it }; last.frame(); last.deliver()
        assertEquals(CenterResult.LAST_MOVE, result); assertTrue(last.writes.isEmpty())
    }

    @Test fun battlesLocksAndUnverifiedCodeStayRefused() {
        val cases = listOf<Harness.() -> Unit>({ battle = true }, { locked = true }, { validCode = false })
        for ((setup, expected) in cases.zip(listOf(CenterResult.IN_BATTLE, CenterResult.NOT_NOW, CenterResult.FAILED))) {
            val h = Harness().apply(setup); var result: CenterResult? = null
            h.nickname { result = it }; h.battle = false
            h.frame(); h.deliver(); assertEquals(expected, result)
            assertFalse(PokemonCenter.SCRIPT in h.writes)
        }
    }

    @Test fun closingTheGameCancelsQueuedServicesAndReplacementDropsOldUiResults() {
        val closed = Harness(); var called = false
        closed.forget { called = true }; closed.core.close(); closed.frame(); closed.deliver()
        assertFalse(called); assertTrue(closed.writes.isEmpty())
        val replaced = Harness()
        replaced.nickname { called = true }; replaced.frame(); replaced.current = null; replaced.deliver()
        assertFalse(called); assertEquals(0, replaced.handoffs)
    }
}
