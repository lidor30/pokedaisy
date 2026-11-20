package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.test.*

class FieldHealingTest {
    private class Harness {
        // CRC32 of 32 zero bytes; only the PC entry is nonzero in this simulated ROM.
        val pc = PcFns(0x08001000, 3, 0x08002000, 0x08003001, listOf(420107693L, 420107693L))
        var fields: FieldItemSupport? = FieldItemSupport(FIELD_ITEMS_EMERALD.copy(
            codeCrcs = emptyList(), party = PARTY_ITEMS_EMERALD.copy(codeCrcs = emptyList()), pc = pc), emptySet())
        var metadata: PokemonCenter.Found? = PokemonCenter.Found(pc.specials, 0, null, null, null, null, null,
            0x02000100, 0x02000104, 368, 378)
        var battle = false
        var locked = false
        var validCode = true
        var writeAllowed = true
        val writes = mutableMapOf<Long, ByteArray>()
        val callsMade = mutableListOf<Pair<Long, Int>>()
        val deliveries = mutableListOf<() -> Unit>()
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long {
                callsMade += fn to a0
                return if (fn == fields?.fns?.fieldControlsLocked && locked) 1 else 0
            }
            override fun read(addr: Long, len: Int): ByteArray = if (addr == pc.specials + 4L * pc.special)
                ByteArray(len) { (pc.pcFn shr (8 * it)).toByte() } else ByteArray(len) { if (validCode) 0 else 1 }
            override fun write(addr: Long, bytes: ByteArray): Boolean {
                if (!writeAllowed) return false
                writes[addr] = bytes.copyOf(); return true
            }
        }
        val core = GameSession(calls, { true }) { _, e -> throw e }
        var current: GameSession? = core
        val items = FieldItemUse({ fields }, { current }, { battle }, { metadata }) { deliveries += it }
        fun frame() = core.serviceField(true, true)
        fun deliver() { deliveries.toList().also { deliveries.clear() }.forEach { it() } }
    }

    @Test fun availabilityRequiresDiscoveredHealingAndRecognizedPartyAndScriptSupport() {
        val h = Harness()
        assertEquals(CenterSupport(true, false, false, false), h.items.center())
        h.metadata = null; assertNull(h.items.center())
        val noCenter = Harness(); noCenter.fields = null; assertNull(noCenter.items.center())
        val noPc = Harness(); noPc.fields = FieldItemSupport(noPc.fields!!.fns.copy(pc = null), emptySet())
        assertNull(noPc.items.center())
        val noParty = Harness(); noParty.fields = FieldItemSupport(noParty.fields!!.fns.copy(party = null), emptySet())
        assertNull(noParty.items.center())
    }

    @Test fun theGameRunsItsOwnHealingScriptBetweenFramesAndTheUiGetsTheResultLater() {
        val h = Harness(); var result: CenterResult? = null
        h.items.centerHeal { result = it }
        assertTrue(h.writes.isEmpty()); assertTrue(h.callsMade.isEmpty())
        h.frame(); assertNull(result)
        assertEquals(setOf(PokemonCenter.SCRIPT), h.writes.keys)
        // lockall; special HealPlayerParty; playfanfare; waitfanfare; releaseall; end.
        assertContentEquals(byteArrayOf(0x69, 0x25, 0, 0, 0x31, 0x70, 1, 0x32, 0x6B, 2), h.writes[PokemonCenter.SCRIPT])
        assertEquals(h.pc.setupScript to PokemonCenter.SCRIPT.toInt(), h.callsMade.last())
        assertTrue(h.callsMade.none { it.first == h.fields!!.fns.removeBagItem })
        h.deliver(); assertEquals(CenterResult.OK, result)
    }

    @Test fun aBattleRequestStaysRefusedEvenIfTheBattleEndsBeforeTheQueueRuns() {
        val h = Harness(); h.battle = true; var result: CenterResult? = null
        h.items.centerHeal { result = it }; h.battle = false
        h.frame(); h.deliver()
        assertEquals(CenterResult.IN_BATTLE, result); assertTrue(h.writes.isEmpty())
    }

    @Test fun menusAndScriptLocksDoNotStartHealing() {
        for (menu in listOf(true, false)) {
            val h = Harness(); var result: CenterResult? = null
            if (menu) h.core.observeScreens(false, dsMenu = true) else h.locked = true
            h.items.centerHeal { result = it }; h.frame(); h.deliver()
            assertEquals(CenterResult.NOT_NOW, result); assertTrue(h.writes.isEmpty())
        }
    }

    @Test fun missingMetadataGameOrCodeAndFailedWritesReportFailureWithoutRunningTheScript() {
        val cases = listOf<Harness.() -> Unit>(
            { metadata = null }, { current = null }, { fields = null }, { validCode = false }, { writeAllowed = false },
            { fields = FieldItemSupport(fields!!.fns.copy(pc = null), emptySet()) },
        )
        for (setup in cases) {
            val h = Harness().apply(setup); var result: CenterResult? = null
            h.items.centerHeal { result = it }; h.frame(); h.deliver()
            assertEquals(CenterResult.FAILED, result); assertTrue(h.writes.isEmpty())
            assertTrue(h.callsMade.none { it.first == h.pc.setupScript })
        }
    }

    @Test fun aResultForThePreviousGameCannotOpenANoticeInItsReplacement() {
        val h = Harness(); var delivered = false
        h.items.centerHeal { delivered = true }; h.frame()
        h.core.close(); h.current = null
        h.deliver(); assertFalse(delivered)
    }
}
