package com.pokedaisy.app.companion

import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.ui.*
import kotlin.test.*

class GameQuickActionsTest {
    private val prefs = Prefs(PrefsCompanionSettingsTest.Store())
    private val settings = object : CompanionSettings by PrefsCompanionSettingsTest.Settings(prefs) {
        override val wildEncountersSupported = true
    }
    private val menu = QuickMenuState()
    private var live = true
    private var hasPc = true
    private var hasFly = true
    private var hasHeal = false
    private var healing: ((CenterResult) -> Unit)? = null
    private val used = mutableListOf<Int>()
    private var openedPc = 0
    private var itemResult: ((ItemUseOutcome) -> Unit)? = null
    private val item = object : ItemUse {
        override fun canPc() = hasPc
        override fun canUse(itemId: Int) = itemId == 86
        override fun use(itemId: Int, done: (ItemUseOutcome) -> Unit) { used += itemId; itemResult = done }
        override fun openPc(done: (ItemUseOutcome) -> Unit) { openedPc++; done(ItemUseOutcome(ItemUseResult.USED)) }
        override fun center() = if (hasHeal) CenterSupport(true, false, false, false) else null
        override fun centerHeal(done: (CenterResult) -> Unit) { healing = done }
    }
    private val destinations = mutableListOf<(FlyTargets) -> Unit>()
    private val flights = mutableListOf<Int>()
    private val fly = object : FlyAction {
        override fun canFly() = hasFly
        override fun targets(done: (FlyTargets) -> Unit) { destinations += done }
        override fun fly(mapsec: Int, targets: FlyTargets, done: (FlyResult) -> Unit) { flights += mapsec; done(FlyResult.OK) }
    }
    private val events = mutableListOf<Hotkeys.Event>()
    private val notices = mutableListOf<String>()
    private val native = mutableListOf<QuickAction>()
    private val actions = GameQuickActions(menu, prefs, settings, { SnapshotView(connected = true) },
        { item }, { fly }, { events += it }, { notices += it }, { live },
        object : QuickActions {
            override fun offered(action: QuickAction) = action == QuickAction.TOUCH_SCREEN
            override fun run(action: QuickAction) { native += action }
        })

    @Test fun availabilityComesFromTheSameLiveAdaptersOnBothHosts() {
        assertTrue(actions.offered(QuickAction.PC)); assertTrue(actions.offered(QuickAction.FLY))
        assertTrue(actions.offered(QuickAction.REPEL)); assertTrue(actions.offered(QuickAction.WILD))
        assertTrue(actions.offered(QuickAction.TOUCH_SCREEN)); assertFalse(actions.offered(QuickAction.FLIGHT))
        assertFalse(actions.offered(QuickAction.BIKE)); assertFalse(actions.offered(QuickAction.ROD))
        assertFalse(actions.offered(QuickAction.HEAL))
        hasPc = false; hasFly = false
        assertFalse(actions.offered(QuickAction.PC)); assertFalse(actions.offered(QuickAction.FLY))
    }

    @Test fun menuSelectionClosesBeforeDispatchAndNativeActionsRemainWithTheirHost() {
        menu.items = listOf(QuickAction.PC, QuickAction.TOUCH_SCREEN)
        menu.trigger(true); menu.key(MenuKey.A)
        assertFalse(menu.open); assertEquals(1, openedPc); assertTrue(notices.isEmpty())
        menu.trigger(true); menu.key(MenuKey.RIGHT); menu.key(MenuKey.A)
        assertEquals(listOf(QuickAction.TOUCH_SCREEN), native)
        actions.run(QuickAction.SAVE_STATE); actions.run(QuickAction.LOAD_STATE); actions.run(QuickAction.FF)
        assertEquals(listOf(Hotkeys.Action.SAVE_STATE, Hotkeys.Action.LOAD_STATE, Hotkeys.Action.FF_TOGGLE), events.map { it.action })
        assertTrue(events.all { it.pressed })
    }

    @Test fun closedOrReopenedMenusRejectLateFlyDestinations() {
        menu.trigger(true); actions.run(QuickAction.FLY)
        menu.close(); menu.trigger(true); actions.run(QuickAction.FLY)
        val targets = FlyTargets(FlyResult.OK, listOf(3), 0, 12)
        destinations[0](targets); assertNull(menu.fly)
        destinations[1](targets); assertSame(targets, menu.fly)
        menu.back(); assertNull(menu.fly)
        actions.run(QuickAction.FLY)
        menu.back(); destinations[2](targets); assertNull(menu.fly)
        actions.flyTo(3, targets); assertEquals(listOf(3), flights)
    }

    @Test fun encounterTogglePreservesTheCurrentBinaryPreferenceAndDefaultsToNormal() {
        prefs.wildEncounters = 50
        assertEquals(100, prefs.wildEncounters) // Legacy rates now read as ON on both platforms.
        actions.run(QuickAction.WILD); assertEquals(0, prefs.wildEncounters)
        actions.run(QuickAction.WILD); assertEquals(100, prefs.wildEncounters)
        prefs.wildEncounters = 0; prefs.wildEncountersBeforeOff = 0
        actions.run(QuickAction.WILD); assertEquals(100, prefs.wildEncounters)
        assertEquals(3, notices.size)
    }

    @Test fun retiringTheHostDropsLateResultsAndNewActions() {
        actions.repel(86); assertEquals(listOf(86), used)
        menu.trigger(true); actions.run(QuickAction.FLY)
        live = false
        itemResult!!(ItemUseOutcome(ItemUseResult.FAILED))
        destinations[0](FlyTargets(FlyResult.OK))
        assertTrue(notices.isEmpty()); assertNull(menu.fly)
        actions.run(QuickAction.FF); actions.run(QuickAction.PC); actions.repel(86)
        assertTrue(events.isEmpty()); assertEquals(0, openedPc); assertEquals(listOf(86), used)
    }

    @Test fun quickMenuChoicesUseTheExistingPreferenceContract() {
        val first = PrefsCompanionSettingsTest.Settings(prefs)
        first.setQuickMenuActions(setOf(QuickAction.PC, QuickAction.FF))
        first.setQuickMenuStyle(QuickMenuStyle.GRID); first.setQuickMenuLabels(false)
        val reopened = PrefsCompanionSettingsTest.Settings(prefs)
        assertEquals(setOf(QuickAction.PC, QuickAction.FF), reopened.quickMenuActions)
        assertEquals(QuickMenuStyle.GRID, reopened.quickMenuStyle); assertFalse(reopened.quickMenuLabels)
    }

    @Test fun healUsesTheVerifiedItemAdapterAndCannotShowALateNoticeAfterTheGameCloses() {
        assertFalse(actions.offered(QuickAction.HEAL))
        hasHeal = true; assertTrue(actions.offered(QuickAction.HEAL))
        actions.run(QuickAction.HEAL); assertTrue(notices.isEmpty())
        assertNotNull(healing)(CenterResult.OK); assertEquals(1, notices.size)
        actions.run(QuickAction.HEAL); live = false
        assertNotNull(healing)(CenterResult.FAILED); assertEquals(1, notices.size)
        hasHeal = false; assertFalse(actions.offered(QuickAction.HEAL))
    }

    @Test fun bikeAndBestRodDispatchThroughTheSharedQueuedTransactionInsteadOfTheHost() {
        val previous = activeGame
        activeGame = GameKind.EMERALD
        try {
            var register = 262
            var kind: GameKind? = GameKind.EMERALD
            val calls = object : GameCalls {
                override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
                override fun read(addr: Long, len: Int) = byteArrayOf(register.toByte(), (register shr 8).toByte())
                override fun write(addr: Long, bytes: ByteArray): Boolean {
                    register = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
                    return true
                }
            }
            val session = com.pokedaisy.app.game.GameSession(calls, { true }) { _, e -> throw e }
            val fields = FieldItemSupport(FieldItemFns(0, 0, 0, 0, 0, 0, 0, emptyList()), emptySet())
            val registered = RegisteredItemUse(session, { fields },
                { NATIVE_EMERALD.copy(staticSaveBlocks = true, saveBlock1Ptr = 0x02001000) },
                { kind }, { false }, { false }, { false }, {}, { fail("unexpected refusal") })
            var inventory = listOf(259, 262, 263, 264).map { ItemView(it, "ITEM", 1, null) }
            val shared = GameQuickActions(menu, prefs, settings, { SnapshotView(connected = true, items = inventory) },
                { null }, { null }, {}, {}, { live }, object : QuickActions {
                    override fun offered(action: QuickAction) = false
                    override fun run(action: QuickAction) { fail("Registered items reached the native host") }
                }, registeredItems = { registered })
            assertTrue(shared.offered(QuickAction.BIKE)); assertTrue(shared.offered(QuickAction.ROD))
            shared.run(QuickAction.BIKE); assertEquals(262, register)
            session.serviceField(true, true); assertEquals(259, register)
            registered.cancel(); assertEquals(262, register)
            shared.run(QuickAction.ROD); session.serviceField(true, true); assertEquals(264, register)
            registered.cancel()
            inventory = inventory.filter { it.itemId != 264 }
            shared.run(QuickAction.ROD); session.serviceField(true, true); assertEquals(263, register)
            registered.cancel()
            inventory = emptyList()
            shared.run(QuickAction.BIKE); session.serviceField(true, true); assertEquals(262, register)
            kind = GameKind.RED
            assertFalse(shared.offered(QuickAction.BIKE)); assertFalse(shared.offered(QuickAction.ROD))
        } finally { activeGame = previous }
    }
}
