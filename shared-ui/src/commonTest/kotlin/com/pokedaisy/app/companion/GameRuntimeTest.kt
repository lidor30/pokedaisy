package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath

class GameRuntimeTest {
    private fun withPlayback(patcher: RomOptionPatcher = object : RomOptionPatcher {
        override fun unpatch() {}
        override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray) = true
    }, configureField: (Long) -> Boolean = { false }, test: (GameRuntime, GameSession, GameBattleInput) -> Unit) {
        val telemetry = TelemetryStore()
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
            override fun read(addr: Long, len: Int) = ByteArray(len)
            override fun write(addr: Long, bytes: ByteArray) = false
        }
        val session = GameSession(calls, { true }) { _, _ -> }
        val battle = GameBattleInput(telemetry, null, 0)
        val playback = GameRuntime(session, telemetry, Prefs(PrefsCompanionSettingsTest.Store()), battle, patcher, { true }, configureField = configureField)
        try { test(playback, session, battle) } finally { playback.close() }
    }

    @Test fun hudWaitsForSettledFieldAndDropsImmediatelyForMenusAndTeardown() {
        val main = 0x03001000L
        val field = 0x08001235L
        var callback = field
        val reader = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int) =
                ByteArray(size) { i -> if (addr == main + 4) (callback shr (8 * i)).toByte() else 0 }
        }
        withPlayback(configureField = { true }) { runtime, session, _ ->
            runtime.start("missing-diagnostic.gba".toPath(), "BPEE", 0)
            FfMenuWatch.load("BPEE", field, 0)
            FfMenuWatch.useKnownGMain(main, 0x439)
            repeat(19) { runtime.tick(reader); assertFalse(runtime.settledOnField) }
            runtime.tick(reader); assertTrue(runtime.settledOnField)
            for (overlay in listOf(GameField.LOCKED, GameField.WINDOW, GameField.BATTLE)) {
                runtime.readField = { GameField.KNOWN or overlay }
                runtime.tick(reader)
                assertFalse(runtime.settledOnField)
                assertTrue(session.onField)
            }
            runtime.readField = { GameField.KNOWN or GameField.AVATAR }
            runtime.tick(reader); assertTrue(runtime.settledOnField)
            callback = field + 0x100
            runtime.tick(reader); assertFalse(runtime.settledOnField)
            callback = field
            repeat(20) { runtime.tick(reader) }
            assertTrue(runtime.settledOnField)
            runtime.quiesce(); assertFalse(runtime.settledOnField)
            runtime.start("missing-diagnostic.gb".toPath(), null, 0)
            assertFalse(runtime.settledOnField)
        }
    }

    @Test fun liveConfigurationDrivesScenesAndSmartMenusWithoutLosingTheRequestedSpeed() = withPlayback { p, session, _ ->
        p.baseSpeed = 1.5f; p.bikeSpeed = 3f
        p.fieldChanged(GameField.AVATAR or GameField.BIKE)
        assertEquals(3f, p.speed()); assertTrue(p.fieldMovesReadable)
        p.fastForward = true; p.ffMaxSpeed = 4f
        session.observeScreens(gba = false, dsMenu = true)
        assertEquals(4f, p.requestedSpeed(PlaybackPolicy.Scene.MENU))
        assertEquals(1f, p.speed())
        p.ffMode = FfMode.NORMAL; assertEquals(4f, p.speed())
        p.fastForward = false; p.slowMotion = true; assertEquals(.5f, p.speed())
        p.fastForward = true; p.ffMaxSpeed = 0f
        assertTrue(p.speed().isInfinite())
        p.resetFieldMoves(); assertFalse(p.fieldMovesReadable)
    }

    @Test fun startingAndClosingResetQueuedFieldWorkAndUnsupportedFieldReadiness() = withPlayback { p, session, _ ->
        var ran = false
        session.post { ran = true }
        p.fieldChanged(GameField.AVATAR)
        p.start("missing-diagnostic.gb".toPath(), null, 0)
        p.beforeFrame()
        assertFalse(ran); assertFalse(p.fieldReadable); assertFalse(p.fieldMovesReadable)
        session.post { ran = true }; p.close(); session.servicePosted()
        assertFalse(ran); assertNull(FfMenuWatch.onLearned)
    }

    @Test fun lateCloseCannotRemoveTheNextGamesLearnedMenuCallback() = withPlayback { first, _, _ ->
        first.start("first-diagnostic.gb".toPath(), null, 1)
        withPlayback { second, _, _ ->
            second.start("second-diagnostic.gb".toPath(), null, 2)
            val callback = FfMenuWatch.onLearned
            first.close()
            assertSame(callback, FfMenuWatch.onLearned)
            second.close(); assertNull(FfMenuWatch.onLearned)
        }
    }

    @Test fun aClosedOwnerCannotRestartWatchersOrSampleAReplacementGame() = withPlayback { first, session, battle ->
        var checks = 0
        first.startSampling(gameBoy = true, game = Dispatchers.Unconfined,
            current = { checks++; false })
        val delayedBattleTrigger = battle.changed
        delayedBattleTrigger()
        assertEquals(1, checks)
        first.close(); first.close()
        withPlayback { next, _, _ ->
            next.start("replacement-diagnostic.gb".toPath(), null, 2)
            val callback = FfMenuWatch.onLearned
            first.start("old-diagnostic.gb".toPath(), null, 1)
            first.startSampling(gameBoy = true, game = Dispatchers.Unconfined, current = { error("closed owner") })
            delayedBattleTrigger(); first.sample(); battle.changed()
            assertEquals(1, checks)
            assertSame(callback, FfMenuWatch.onLearned)
            var ran = false
            session.post { ran = true }; session.servicePosted()
            assertFalse(ran)
        }
    }

    @Test fun closeRetiresOptionsSoLateSettingsCannotRepatchTheCore() = withPlayback { runtime, _, _ ->
        val options = runtime.options
        options.setReusableTms(listOf(ReusableTms.Site(0x08001000L, ByteArray(16))), true)
        assertEquals(GameOptionPatches.Result(true, false), options.tick())
        runtime.close()
        options.setReusableTms(listOf(ReusableTms.Site(0x08002000L, ByteArray(16))), true)
        assertNull(options.tick())
        assertFalse(options.dirty)
        assertEquals(0, options.fieldState({ _, _ -> error("closed core") }))
    }

    @Test fun aFailedPatchCleanupStillClosesSessionWorkAndWatchers() {
        var fail = false
        val core = object : RomOptionPatcher {
            override fun unpatch() { if (fail) error("patch cleanup failed") }
            override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray) = true
        }
        withPlayback(core) { runtime, session, _ ->
            runtime.start("diagnostic.gb".toPath(), null, 3)
            runtime.options.setReusableTms(listOf(ReusableTms.Site(0x08001000L, ByteArray(16))), true)
            runtime.options.tick()
            var ran = false
            session.post { ran = true }
            fail = true
            assertFailsWith<IllegalStateException> { runtime.close() }
            session.servicePosted()
            assertFalse(ran)
            assertNull(FfMenuWatch.onLearned)
            runtime.close()
        }
    }

    @Test fun runtimeOwnsBoundControllersAndClosedBindingDoesNotOpenStores() = withPlayback { runtime, _, _ ->
        var opened = 0
        var submitted = 0
        val cheats = runtime.bindCheats(true, { opened++; null }, { true }, { submitted++ })
        assertSame(cheats, runtime.bindCheats(false, { error("replaced binding") }, { false }, {}))
        assertEquals(1, opened)
        runtime.close()
        cheats.start(); cheats.apply(); cheats.refresh()
        assertEquals(1, opened); assertEquals(0, submitted)
        withPlayback { retired, _, _ ->
            retired.close()
            retired.bindCheats(true, { error("closed store opened") }, { true }, { error("closed core used") }).start()
        }
    }

    @Test fun runtimeCloseReleasesBoundHoldsAndRejectsDelayedSlotActions() = withPlayback { runtime, _, _ ->
        val settings = PrefsCompanionSettingsTest.Settings(Prefs(PrefsCompanionSettingsTest.Store()))
        val slots = object : StateSlots {
            override val currentIndex = 0
            override fun list() = emptyList<StateSlots.Slot>()
            override fun requestSave(index: Int) { error("retired save") }
            override fun requestLoad(index: Int) { error("retired load") }
            override fun requestDelete(index: Int) { error("unexpected delete") }
            override fun requestUndoSave() { error("retired undo") }
            override fun requestUndoLoad() { error("retired undo") }
        }
        val held = mutableListOf<Boolean>()
        val actions = runtime.bindActions(settings, slots, {}, { _, down -> held += down }, {})
        assertSame(actions, runtime.bindActions(settings, slots, {}, { _, _ -> error("replaced input") }, {}))
        actions.touch(TouchButton.FF, true)
        runtime.close()
        actions.touch(TouchButton.FF, false)
        actions.touch(TouchButton.SAVE_STATE, true)
        actions.hotkey(com.pokedaisy.app.Hotkeys.Event(com.pokedaisy.app.Hotkeys.Action.LOAD_STATE, true))
        assertEquals(listOf(true, false), held)
        assertFalse(settings.ffToggled)
    }

    @Test fun ownedStateControllersAreRetiredEvenWhenBoundAfterClose() {
        for (bindFirst in listOf(true, false)) withPlayback { runtime, _, _ ->
            fun bind() = runtime.bindStates {
                StateSlotController(StateFiles("unused-retired-state-directory".toPath()), onCore = { error("retired core") },
                    write = { error("retired write") }, read = { error("retired read") }, beforeLoad = {},
                    selected = { error("retired selection") })
            }
            if (bindFirst) bind()
            runtime.close()
            val controller = bind()
            assertSame(controller, runtime.bindStates { error("duplicate state controller") })
            assertFalse(controller.select(0))
            assertTrue(controller.list().isEmpty())
            controller.requestSave(0); controller.requestLoad(0)
        }
    }
    @Test fun areaMapsFollowTheSharedGameLifetimeAndNormalizedHostCrc() = withPlayback { first, _, _ ->
        try {
            first.start("missing-diagnostic.gba".toPath(), null, 1, gba = true, areaMapCrc = 2)
            assertEquals(2L, activeAreaMapCrc)
            first.close()
            withPlayback { next, _, _ ->
                next.start("replacement.gb".toPath(), null, 3, gba = false)
                assertEquals(0L, activeAreaMapCrc)
                first.start("retired.gba".toPath(), null, 1, gba = true, areaMapCrc = 2)
                assertEquals(0L, activeAreaMapCrc)
            }
        } finally { activeAreaMapCrc = 0; AreaMaps.romChanged() }
    }

}
