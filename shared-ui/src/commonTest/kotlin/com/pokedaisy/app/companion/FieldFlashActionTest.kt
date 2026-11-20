package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.ui.*
import com.pokedaisy.app.game.GameSession
import com.pokedaisy.app.Prefs
import kotlin.test.*

class FieldFlashActionTest {
    @AfterTest fun clearHmRule() { HmFree.active = false }

    private class Harness {
        val flash = FLASH_EMERALD.copy(codeCrcs = FLASH_EMERALD.codeCrcs.map { 420107693L })
        val fns = FIELD_ITEMS_EMERALD.copy(codeCrcs = emptyList(),
            party = FIELD_ITEMS_EMERALD.party!!.copy(codeCrcs = emptyList()))
        var fields: FieldItemSupport? = FieldItemSupport(fns, emptySet())
        var metadata: FlashFns? = flash
        var snapshot = SnapshotView(connected = true, game = GameKind.EMERALD,
            party = listOf(MonView(1, "Test", 5, 10, 10, "", emptyList(), null,
                moves = listOf(MoveView("FLASH", "Normal", 20, 0, id = 148)))))
        var battle = false
        var locked = false
        var badge = true
        var knowsMove = true
        var cave = true
        var lit = false
        var badCode = false
        var post = flash.fieldCallbacks[0] or 1
        var handedOver = 0
        var setups = 0
        val ram = mutableMapOf<Long, Byte>()
        val deliveries = mutableListOf<() -> Unit>()
        val calls = object : GameCalls {
            override fun read(addr: Long, len: Int): ByteArray = when {
                addr == fns.party!!.partyCount -> byteArrayOf(1)
                addr == flash.mapHeader + flash.caveOff -> byteArrayOf(if (cave) 1 else 0)
                addr >= 0x08000000 -> ByteArray(len) { if (badCode) 1 else 0 }
                else -> ByteArray(len) { ram[addr + it] ?: 0 }
            }
            override fun write(addr: Long, bytes: ByteArray): Boolean {
                bytes.forEachIndexed { i, b -> ram[addr+i] = b }; return true
            }
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = when(fn) {
                fns.fieldControlsLocked -> if (locked) 1 else 0
                fns.party!!.getMonData -> if (a1 == flash.monMoveField && knowsMove) flash.flashMove.toLong() else 0
                flash.flagGet -> if ((a0 == flash.badgeFlag && badge) || (a0 == flash.litFlag && lit)) 1 else 0
                flash.setUpFieldMoveFlash -> {
                    setups++
                    if (cave && !lit) { write(flash.postMenuFieldCallback, word(post)); 1 } else 0
                }
                flash.fieldCallbacks[0] or 1 -> { lit = true; 0 }
                else -> 0
            }
        }
        val game = GameSession(calls, { true }) { _, e -> throw e }
        var current: GameSession? = game
        val items = FieldItemUse({ fields }, { current }, { battle }) { deliveries += it }
        val action = FieldFlashAction(items, { metadata }, { snapshot }, calls::read) { handedOver++ }
        fun frame() = game.serviceField(true, true)
        fun deliver() { deliveries.toList().also { deliveries.clear() }.forEach { it() } }
        fun run(): FlashResult? { var r: FlashResult? = null; action.use { r = it }; frame(); deliver(); return r }
        companion object {
            fun word(n: Long) = ByteArray(4) { (n shr (it*8)).toByte() }
        }
    }

    @Test fun availabilityTracksThePartyAndHmRuleRatherThanAStaleMenuChoice() {
        val h = Harness(); assertTrue(h.action.available())
        h.snapshot = h.snapshot.copy(party = h.snapshot.party.map { it.copy(moves = emptyList()) })
        assertFalse(h.action.available()); HmFree.active = true; assertTrue(h.action.available())
        h.metadata = null; assertFalse(h.action.available()); assertNull(h.action.dark())
    }

    @Test fun theGameLightsTheCaveAndHandoffWaitsForCurrentGameDelivery() {
        val h = Harness(); var result: FlashResult? = null
        h.calls.write(h.flash.menuFieldCallback, Harness.word(0x12345678))
        h.calls.write(h.flash.postMenuFieldCallback, Harness.word(0x87654321))
        h.action.use { result = it }; assertEquals(0, h.setups)
        h.frame(); assertTrue(h.lit); assertNull(result); assertEquals(0, h.handedOver)
        assertContentEquals(Harness.word(0x12345678), h.calls.read(h.flash.menuFieldCallback,4))
        assertContentEquals(Harness.word(0x87654321), h.calls.read(h.flash.postMenuFieldCallback,4))
        h.deliver(); assertEquals(FlashResult.OK,result); assertEquals(1,h.handedOver)
    }

    @Test fun thePartyMoveBadgeLightAndCodeRemainTheGamesChecks() {
        fun case(result: FlashResult, setup: Harness.() -> Unit) = setup to result
        for ((setup, expected) in listOf(
            case(FlashResult.NO_FLASHER) { knowsMove=false },
            case(FlashResult.NO_BADGE) { badge=false },
            case(FlashResult.CANT_USE_HERE) { cave=false },
            case(FlashResult.ALREADY_LIT) { lit=true },
            case(FlashResult.FAILED) { badCode=true },
            case(FlashResult.FAILED) { post=0x08012345 },
            case(FlashResult.FAILED) { metadata=null },
        )) {
            val h=Harness().apply(setup); assertEquals(expected,h.run()); assertEquals(0,h.handedOver)
        }
    }

    @Test fun battleAtRequestTimeAndMenusOrLocksRefuseBeforeTheFieldMove() {
        for (mode in 0..2) {
            val h=Harness()
            if (mode==0) h.battle=true else if(mode==1) h.game.observeScreens(false,dsMenu=true) else h.locked=true
            var r:FlashResult?=null; h.action.use { r=it }; h.battle=false
            h.frame();h.deliver()
            assertEquals(if(mode==0) FlashResult.IN_BATTLE else FlashResult.NOT_NOW,r)
            assertEquals(0,h.setups);assertEquals(0,h.handedOver)
        }
    }

    @Test fun replacingTheGameDropsQueuedWorkAndLateHandoff() {
        val before=Harness(); before.action.use { fail("retired game result") }; before.game.close();before.current=null
        before.frame();before.deliver();assertFalse(before.lit);assertEquals(0,before.handedOver)
        val after=Harness(); after.action.use { fail("late game result") };after.frame()
        after.game.close();after.current=null;after.deliver();assertEquals(0,after.handedOver)
    }

    @Test fun quickMenuUsesSharedAvailabilityRefusalAndSilentGameHandoff() {
        val h=Harness();val notices=mutableListOf<String>();var live=true
        val prefs=Prefs(PrefsCompanionSettingsTest.Store())
        val actions=GameQuickActions(QuickMenuState(),prefs,PrefsCompanionSettingsTest.Settings(prefs),{h.snapshot},
            {h.items},{null},{},{notices+=it},{live},object:QuickActions {
                override fun offered(action:QuickAction)=false
                override fun run(action:QuickAction) { fail("FLASH must use the shared owner") }
            },flash={h.action})
        assertTrue(actions.offered(QuickAction.FLASH))
        h.badge=false;actions.run(QuickAction.FLASH);h.frame();h.deliver();assertEquals(1,notices.size)
        h.badge=true;actions.run(QuickAction.FLASH);h.frame();h.deliver();assertEquals(1,notices.size);assertEquals(1,h.handedOver)
        h.lit=false;actions.run(QuickAction.FLASH);h.frame();live=false;h.deliver();assertEquals(1,notices.size)
    }

    @Test fun signatureCacheRejectsNonLiveAndUnknownGamesAndRetriesFailedReads() {
        val h=Harness();var reads=0;var throwRead=true
        val cache=FieldFlashSupport { _,n -> reads++;check(!throwRead);ByteArray(n) }
        assertNull(cache.get(h.snapshot,null,0));assertNull(cache.get(h.snapshot.copy(connected=false),"BPEE",0))
        assertNull(cache.get(h.snapshot.copy(party=emptyList()),"BPEE",0));assertEquals(0,reads)
        assertNull(cache.get(h.snapshot,"UNKNOWN",0));assertEquals(0,reads)
        assertNull(cache.get(h.snapshot,"BPEE",0));val failed=reads
        assertNull(cache.get(h.snapshot,"BPEE",0));assertTrue(reads>failed)
        throwRead=false;assertNull(cache.get(h.snapshot,"BPEE",0));val checked=reads
        assertNull(cache.get(h.snapshot,"BPEE",0));assertEquals(checked,reads)
        assertNull(cache.get(h.snapshot,"BPEE",1));assertTrue(reads>checked)
    }

    @Test fun knownSpriteHacksAreExcludedBeforeReadingRomCode() {
        val saved=fireRedHack;val previousGame=activeGame
        try {
            val h=Harness();val cache=FieldFlashSupport { _,_ -> fail("unsafe hack must not be scanned") }
            for (hack in listOf(fireRedHackRocketEdition,fireRedHackAshGray)) {
                activeGame=GameKind.FIRERED;fireRedHack=hack;assertNull(cache.get(h.snapshot,"BPRE",0))
            }
        } finally { fireRedHack=saved;activeGame=previousGame }
    }
}
