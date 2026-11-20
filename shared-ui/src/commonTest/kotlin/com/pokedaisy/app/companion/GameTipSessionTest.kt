package com.pokedaisy.app.companion

import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.LocationView
import com.pokedaisy.app.companion.data.SnapshotView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameTipSessionTest {
    @Test fun blockedFieldCountsPlayButTheGameCardClosesOnlyForItsDismissConditions() {
        val host = Host()
        val scheduler = scheduler(host.session)
        host.showZoom(scheduler)
        val played = scheduler.playedMillis

        // Android's HUD panel blocks new cards and leaves the current card visible.
        host.frame = host.frame.copy(blocked = true)
        host.session.tick(scheduler, 10_000)
        assertEquals(played + 1_000, scheduler.playedMillis)
        assertEquals(TipId.ZOOM, scheduler.current)

        // A companion card survives battle. A card over the game is dismissed.
        host.frame = host.frame.copy(snapshot = host.frame.snapshot.copy(inBattle = true), cardOnCompanion = true)
        host.session.tick(scheduler, 500)
        assertEquals(played + 1_000, scheduler.playedMillis)
        assertEquals(TipId.ZOOM, scheduler.current)

        host.frame = host.frame.copy(cardOnCompanion = false)
        host.session.tick(scheduler, 500)
        assertNull(scheduler.current)
    }

    @Test fun tryOnTheGameScreenRechecksTheCurrentOverlayAndControl() {
        val host = Host(tryOnlyWhenCalm = true)
        val scheduler = scheduler(host.session)
        host.showZoom(scheduler)
        host.frame = host.frame.copy(blocked = true, dismissForOverlay = true)
        host.session.tryIt(TipId.ZOOM)
        assertEquals(0, host.zooms)
        host.session.tick(scheduler, 500)
        assertNull(scheduler.current)

        host.frame = host.frame.copy(blocked = false, dismissForOverlay = false)
        host.touch = false
        host.session.tryIt(TipId.ZOOM)
        assertEquals(0, host.zooms)
        host.touch = true
        host.session.tryIt(TipId.ZOOM)
        assertEquals(1, host.zooms)
    }

    @Test fun controllerOnlyHostsWaitForTheExpandOfferAndKeepTheDsLayoutAction() {
        val host = Host()
        host.touch = false
        assertFalse(host.session.available(TipId.ZOOM))
        host.binding = "L3"
        assertTrue(host.session.available(TipId.ZOOM))
        assertTrue(host.session.available(TipId.QUICK_MENU))
        host.quickActions = false
        assertFalse(host.session.available(TipId.QUICK_MENU))

        host.expanded = false
        host.expandOfferSeen = false
        assertFalse(host.session.available(TipId.EXPAND))
        host.expandOfferSeen = true
        assertTrue(host.session.available(TipId.EXPAND))
        host.session.tryIt(TipId.EXPAND)
        assertEquals(GameAspect.WIDE, host.settings.gameAspect)

        assertFalse(host.session.available(TipId.DS_TRIGGERS))
        host.frame = host.frame.copy(dsTriggers = true)
        assertTrue(host.session.available(TipId.DS_TRIGGERS))
        host.session.tryIt(TipId.DS_TRIGGERS)
        assertEquals(listOf("layout"), host.dsActions)
        host.floatingTouch = true
        host.session.tryIt(TipId.DS_TRIGGERS)
        assertEquals(listOf("layout", "touch"), host.dsActions)
    }

    @Test fun theClockRaisesTheEarlyDsMomentWithItsShorterSettleTime() {
        val host = Host()
        host.expanded = false
        host.expandOfferSeen = false
        host.binding = "R2"
        host.frame = host.frame.copy(dsTriggers = true)
        val scheduler = scheduler(host.session, played = 0)
        repeat(9) { host.session.tick(scheduler, 500) }
        assertNull(scheduler.current)
        host.session.tick(scheduler, 500)
        assertEquals(TipId.DS_TRIGGERS, scheduler.current)
    }

    @Test fun theControllerHintUsesTheFirstPureControllerChordAndTheHostsLabels() {
        val options = listOf("A", "BUTTON_L1 + B", " BUTTON_SELECT + BUTTON_THUMBR ", "BUTTON_R1")
        assertEquals("SELECT + THUMBR", controllerTipShortcut(options))
        assertEquals("SELECT + R3", controllerTipShortcut(options) {
            if (it == "BUTTON_THUMBR") "R3" else KeyCapture.label(it)
        })
        assertNull(controllerTipShortcut(options.take(2)))
    }

    private class Host(tryOnlyWhenCalm: Boolean = false) {
        private val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        val settings = object : CompanionSettings by PrefsCompanionSettingsTest.Settings(prefs) {
            override val wideSupported = true
            override val gameAspect get() = prefs.gameAspect
            override fun setGameAspect(aspect: GameAspect) { prefs.gameAspect = aspect }
        }
        var frame = GameTipFrame(
            snapshot = SnapshotView(connected = true, game = GameKind.EMERALD,
                location = LocationView("Test field", null, 0, 0, 0, 0)),
            settledOnField = true,
            blocked = false,
            dismissForOverlay = false,
        )
        var touch = true
        var expanded = true
        var expandOfferSeen = true
        var quickActions = true
        var binding: String? = null
        var floatingTouch = false
        var zooms = 0
        val dsActions = mutableListOf<String>()
        val session = GameTipSession(
            settings = settings,
            snapshot = { frame.snapshot },
            items = { null },
            fly = { null },
            openQuickMenu = {},
            quickAction = {},
            notice = {},
            frame = { frame },
            expanded = { expanded },
            expandOfferSeen = { expandOfferSeen },
            shortcut = { binding },
            touchShortcut = { touch && it == Hotkeys.Action.ZOOM },
            quickMenuHasActions = { quickActions },
            hudAvailable = { false },
            dsTriggers = { frame.dsTriggers },
            dsTouchFloats = { floatingTouch },
            tryOnlyWhenCalm = tryOnlyWhenCalm,
            cycleZoom = { zooms++ },
            openSummary = {},
            toggleDsTouch = { dsActions += "touch" },
            nextDsLayout = { dsActions += "layout" },
        )

        fun showZoom(scheduler: TipScheduler) {
            scheduler.tick(TipScheduler.SESSION_SETTLE, onField = true, calm = false)
            repeat(6) { session.tick(scheduler, 500) }
            assertEquals(TipId.ZOOM, scheduler.current)
        }
    }

    private fun scheduler(session: GameTipSession, played: Long = TipScheduler.FIRST_TIP_AFTER) = TipScheduler(object : TipStore {
        override var fieldMillis = played
        override var lastClosedAt = -1L
        override var records = emptyMap<TipId, TipRecord>()
    }, session::available)
}
