package com.pokedaisy.app.companion

import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.atomicfu.atomic
import okio.FileSystem
import okio.Path.Companion.toPath

class GameRuntimeTest {
    @Test fun samplerAndUiBindingsAlwaysReturnTheSameLoadedGameFieldOwners() = runBlocking {
        repeat(20) {
            withPlayback { runtime, _, _ ->
                val owners = runBlocking {
                    val ready = atomic(0); val go = atomic(false)
                    val requests = List(16) { async(Dispatchers.Default) {
                        ready.incrementAndGet()
                        while (!go.value) yield()
                        runtime.bindGameBoyFields({ false }, { _, _ -> null }, { it() }, {}) to
                            runtime.bindRegisteredItems({ null }, {}, {})
                    } }
                    while (ready.value != requests.size) yield()
                    go.value = true
                    requests.awaitAll()
                }
                for ((gb, registered) in owners) {
                    assertSame(runtime.gameBoyFields, gb)
                    assertSame(runtime.registeredItems, registered)
                }
            }
        }
    }

    private fun withPlayback(patcher: RomOptionPatcher = object : RomOptionPatcher {
        override fun unpatch() {}
        override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray) = true
    }, configureField: (Long) -> Boolean = { false }, telemetry: TelemetryStore = TelemetryStore(),
        prefs: Prefs = Prefs(PrefsCompanionSettingsTest.Store()), test: (GameRuntime, GameSession, GameBattleInput) -> Unit) {
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
            override fun read(addr: Long, len: Int) = ByteArray(len)
            override fun write(addr: Long, bytes: ByteArray) = false
        }
        val session = GameSession(calls, { true }) { _, _ -> }
        val battle = GameBattleInput(telemetry, null, 0)
        val playback = GameRuntime(session, telemetry, prefs, battle, patcher, { true }, configureField = configureField)
        try { test(playback, session, battle) } finally { playback.close() }
    }

    @Test fun gameBoyAndDsRuntimesContinueExistingNuzlockeRuns() = preservingTelemetryGame {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        val cases = listOf(GameKind.CRYSTAL to "crystal.gbc", GameKind.PLATINUM to "platinum.nds")
        for ((index, case) in cases.withIndex()) {
            val (game, filename) = case
            val crc = index.toLong() + 1
            val key = crc.toString(16).padStart(8, '0')
            val initial = NuzlockeMon(123L, 25, "Pikachu", 5)
            prefs.setNuzlockeFor(key, true)
            prefs.setNuzlockeRunFor(key, NuzlockeRun(mons = listOf(initial)).encode())
            val telemetry = TelemetryStore()
            withPlayback(telemetry = telemetry, prefs = prefs) { runtime, _, _ ->
                runtime.start(filename.toPath(), null, crc, gba = false)
                val lead = MonView(species = 25, name = "Pikachu", level = 6, hp = 10, maxHp = 10,
                    status = "", types = emptyList(), iconAsset = null, personality = initial.personality)
                val live = SnapshotView(connected = true, game = game, party = listOf(lead))
                telemetry.publish(live)
                assertTrue(runtime.nuzlockeSupported, "Existing Nuzlocke run for $game must be available")
                assertTrue(Nuzlocke.on)
                runtime.observeNuzlocke(live)
                assertEquals(listOf(initial.copy(level = 6)), NuzlockeRun.decode(prefs.nuzlockeRunFor(key)).mons)

                val unsupported = live.copy(unsupported = true, party = listOf(lead.copy(level = 7)))
                telemetry.publish(unsupported)
                assertFalse(runtime.nuzlockeSupported)
                runtime.observeNuzlocke(unsupported)
                assertEquals(6, NuzlockeRun.decode(prefs.nuzlockeRunFor(key)).mons.single().level)

                val nextKey = "00000003"
                prefs.setNuzlockeFor(nextKey, true)
                withPlayback(prefs = prefs) { next, _, _ ->
                    next.start("emerald.gba".toPath(), "BPEE", 3)
                    runtime.observeNuzlocke(live.copy(party = listOf(lead.copy(level = 8))))
                    runtime.close()
                    assertTrue(Nuzlocke.on)
                    assertTrue(Nuzlocke.run.mons.isEmpty())
                    assertEquals(6, NuzlockeRun.decode(prefs.nuzlockeRunFor(key)).mons.single().level)
                    assertNull(prefs.nuzlockeRunFor(nextKey))
                }
            }
        }
    }

    @Test fun blockedRomDoesNotStartANuzlockeRun() = preservingTelemetryGame {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        val blocked = BlockedGames.GHOST_GREY
        val key = blocked.crc32s.first()
        prefs.setNuzlockeFor(key, true)
        val telemetry = TelemetryStore()
        val enforced = BlockedGames.enforced
        BlockedGames.enforced = true
        try {
            withPlayback(telemetry = telemetry, prefs = prefs) { runtime, _, _ ->
                runtime.start("blocked.gba".toPath(), "BPEE", key.toLong(16))
                telemetry.publish(SnapshotView(connected = true, game = GameKind.EMERALD))
                assertFalse(runtime.nuzlockeSupported)
                assertTrue(prefs.nuzlockeFor(key))
            }
        } finally {
            BlockedGames.enforced = enforced
        }
    }

    private fun preservingTelemetryGame(test: () -> Unit) {
        val originalGame = activeGame
        val originalIcons = DecompIconSource.tables
        try {
            test()
        } finally {
            activeGame = originalGame
            DecompIconSource.tables = originalIcons
        }
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

    @Test fun originalFieldWindowsHideTheHudEvenWhenTheFieldCallbackDoesNotChange() {
        val main = 0x03001000L
        val field = 0x08001235L
        val reader = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int) =
                ByteArray(size) { i -> if (addr == main + 4) (field shr (8 * i)).toByte() else 0 }
        }
        withPlayback { runtime, session, _ ->
            runtime.start("missing-diagnostic.gba".toPath(), "BPEE", 0)
            FfMenuWatch.load("BPEE", field, 0)
            FfMenuWatch.useKnownGMain(main, 0x439)
            repeat(20) { runtime.tick(reader) }
            assertTrue(runtime.settledOnField)
            for (overlay in listOf(GameField.LOCKED or GameField.TEXT, GameField.LOCKED, GameField.WINDOW, GameField.BATTLE)) {
                runtime.fieldChanged(GameField.KNOWN or GameField.AVATAR or overlay)
                assertFalse(runtime.settledOnField, "Native field overlay $overlay must hide the HUD")
                assertTrue(session.onField, "Presentation must not change field-job ownership")
            }
            runtime.fieldChanged(GameField.KNOWN or GameField.AVATAR)
            assertTrue(runtime.settledOnField)
            runtime.close(); runtime.fieldChanged(GameField.KNOWN or GameField.AVATAR)
            assertFalse(runtime.settledOnField)
        }
    }

    @Test fun unknownFieldBitsAndOtherPlatformsRetainTheirExistingFieldSignal() {
        withPlayback { runtime, session, _ ->
            runtime.start("missing-diagnostic.gb".toPath(), null, 0)
            repeat(20) { session.observeField(true, com.pokedaisy.app.WideCamera.SCENE_FIELD, 0, false) }
            runtime.fieldChanged(0)
            assertTrue(runtime.settledOnField)
            // Non-GBA hosts already derive their own field readiness. Do not reinterpret their native bitmaps here.
            runtime.fieldChanged(GameField.KNOWN or GameField.LOCKED or GameField.WINDOW)
            assertTrue(runtime.settledOnField)
            session.close(); runtime.fieldChanged(0)
            assertFalse(runtime.settledOnField)
        }
    }

    @Test fun expandedTextAndMenuScenesHideTheHudEvenWhileTheFieldCallbackRemainsActive() {
        val main = 0x03001000L
        val field = 0x08001235L
        val reader = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int) =
                ByteArray(size) { i -> if (addr == main + 4) (field shr (8 * i)).toByte() else 0 }
        }
        withPlayback { runtime, _, _ ->
            runtime.start("missing-diagnostic.gba".toPath(), "BPEE", 0)
            FfMenuWatch.load("BPEE", field, 0)
            FfMenuWatch.useKnownGMain(main, 0x439)
            repeat(20) { runtime.tick(reader, com.pokedaisy.app.WideCamera.SCENE_FIELD) }
            assertTrue(runtime.settledOnField)
            runtime.tick(reader, com.pokedaisy.app.WideCamera.SCENE_FADE)
            assertTrue(runtime.settledOnField)
            runtime.tick(reader, com.pokedaisy.app.WideCamera.SCENE_TEXT)
            assertFalse(runtime.settledOnField)
            runtime.tick(reader, com.pokedaisy.app.WideCamera.SCENE_FADE)
            assertFalse(runtime.settledOnField)
            repeat(19) { runtime.tick(reader, com.pokedaisy.app.WideCamera.SCENE_FIELD); assertFalse(runtime.settledOnField) }
            runtime.tick(reader, com.pokedaisy.app.WideCamera.SCENE_FIELD)
            assertTrue(runtime.settledOnField)
            runtime.tick(reader, com.pokedaisy.app.WideCamera.SCENE_MENU)
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

    @Test fun framePatchesWaitForCoopAndRewindThenReinstallTheStoryLockInOrder() {
        val steps = mutableListOf<String>()
        val patcher = object : RomOptionPatcher {
            override fun unpatch() { steps += "unpatch options" }
            override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray): Boolean {
                steps += "patch options"
                return true
            }
        }
        withPlayback(patcher) { runtime, _, _ ->
            runtime.options.setReusableTms(listOf(ReusableTms.Site(0x08001000L, ByteArray(16))), true)
            var appearanceChanged = true
            fun frame(work: GameSession.FrameWork, coopBattleActive: Boolean = false) = runtime.beforeFrame(work) {
                runtime.serviceFramePatches(coopBattleActive, {
                    steps += "appearance"
                    appearanceChanged.also { appearanceChanged = false }
                }) { steps += "story lock" }
            }

            frame(GameSession.FrameWork.ADVANCING, coopBattleActive = true)
            frame(GameSession.FrameWork.REWINDING)
            assertTrue(steps.isEmpty())
            assertTrue(runtime.options.dirty)

            frame(GameSession.FrameWork.HELD)
            assertEquals(listOf("unpatch options", "patch options", "story lock", "appearance", "story lock"), steps)
            assertFalse(runtime.options.dirty)

            steps.clear()
            frame(GameSession.FrameWork.ADVANCING)
            assertEquals(listOf("appearance"), steps)
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

    @Test fun acceptedStateLoadsRestoreEffectsInOrderAndRejectedLoadsKeepTheCurrentSnapshot() {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-load-effects-${Random.nextLong().toULong()}"
        val telemetry = TelemetryStore()
        val originalGame = activeGame
        val originalCode = romGameCode
        val originalLanguage = romLanguage
        val originalHeader = romHeaderCode
        val originalIcons = DecompIconSource.tables
        try {
            withPlayback(telemetry = telemetry) { runtime, _, _ ->
                val files = StateFiles(directory)
                val state = files.stateFile(3)
                state.writeText("state")
                SnapshotCache.fileFor(state).writeBytes(SnapshotCache.encode(SnapshotView(connected = true, game = GameKind.EMERALD, x = 3)))
                val live = SnapshotView(connected = true, game = GameKind.EMERALD, x = 9)
                telemetry.publish(live)
                val retry = runtime.battleRetry
                retry.set(true)
                val fainted = MonView(species = 25, name = "A", level = 5, hp = 0, maxHp = 10,
                    status = "", types = emptyList(), iconAsset = null)
                retry.observe(live.copy(inBattle = true, isTrainerBattle = true, party = listOf(fainted)), true) { true }
                retry.observe(live, true) { error("The battle ended") }
                assertTrue(retry.asking)
                val steps = mutableListOf<String>()
                val results = mutableListOf<Pair<Hotkeys.Action, Boolean>>()
                var accept = false
                val slots = runtime.bindStates {
                    StateSlotController(files, onCore = { it() }, write = { it.writeText("undo"); true },
                        read = { steps += "read"; accept },
                        beforeLoad = {},
                        loaded = { path ->
                            runtime.afterStateLoad(
                                restoreProgress = {
                                    assertTrue(retry.asking)
                                    steps += "progress"
                                },
                                settleAppearance = {
                                    assertFalse(retry.asking)
                                    steps += "appearance"
                                },
                                reinstallStoryLock = {
                                    assertSame(live, telemetry.snapshot.value)
                                    steps += "story lock"
                                },
                                companionState = path,
                            )
                        },
                        completed = { action, _, ok -> results += action to ok })
                }

                slots.requestLoad(3)
                slots.requestUndoLoad()
                assertEquals(listOf("read", "read"), steps)
                assertSame(live, telemetry.snapshot.value)
                assertTrue(retry.asking)
                assertEquals(listOf(Hotkeys.Action.LOAD_STATE to false, Hotkeys.Action.UNDO_LOAD to false), results)

                steps.clear()
                accept = true
                slots.requestLoad(3)
                assertEquals(listOf("read", "progress", "appearance", "story lock"), steps)
                assertEquals(3, telemetry.snapshot.value.x)
                assertEquals(Hotkeys.Action.LOAD_STATE to true, results.last())
            }
        } finally {
            directory.deleteRecursively()
            activeGame = originalGame
            romGameCode = originalCode
            romLanguage = originalLanguage
            romHeaderCode = originalHeader
            DecompIconSource.tables = originalIcons
        }
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
