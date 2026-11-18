package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okio.FileSystem
import okio.SYSTEM
import okio.use

class GameOptionsTest {
    private val sites = listOf(ReusableTms.Site(0x08001000L, ByteArray(16)))
    private val text = InstantText.Patch(listOf(InstantText.Write(0x08002000L, byteArrayOf(1), byteArrayOf(2))))
    private class Core : RomOptionPatcher {
        val active = mutableSetOf<Long>()
        var resets = 0
        var duringPatch: () -> Unit = {}
        override fun unpatch() { resets++; active.clear() }
        override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray): Boolean {
            active += at; duringPatch(); return true
        }
    }

    @Test fun uiChangesWaitForTheFrameAndInputSettingsNeverRepatch() {
        val core = Core()
        val options = GameOptions(core, { true })
        try {
            options.setReusableTms(sites, true); options.setInstantText(text, true)
            assertTrue(core.active.isEmpty())
            assertEquals(GameOptionPatches.Result(true, true), options.tick())
            assertEquals(setOf(sites.single().branch, text.writes.single().addr), core.active)
            options.autoRun = true; options.runIndoors = true; options.wildEncounters = 0
            assertFalse(options.dirty); assertNull(options.tick()); assertEquals(1, core.resets)
            var indoor = false; var keep = -1
            val step = { run: Boolean, wild: Int -> indoor = run; keep = wild; GameField.AVATAR }
            assertEquals(GameField.AVATAR, options.fieldState(step))
            assertTrue(indoor); assertEquals(100, keep) // Unknown wild switch must retain the game's encounters.
            options.fieldState(step, indoorsSupported = false, wildKnown = true)
            assertFalse(indoor); assertEquals(0, keep)
            options.wildEncounters = 17; options.fieldState(step, wildKnown = true)
            assertEquals(100, keep)
        } finally { options.close() }
    }

    @Test fun disablingAndClosingRestoreTheCoreOnlyOnceAndRestartReapplies() {
        val core = Core(); var allowed = false
        val options = GameOptions(core, { allowed })
        options.setReusableTms(sites, true)
        assertNull(options.tick()); assertTrue(core.active.isEmpty())
        allowed = true; options.tick(); assertTrue(core.active.isNotEmpty())
        allowed = false; options.tick(); assertTrue(core.active.isEmpty())
        val resets = core.resets
        assertNull(options.tick()); assertEquals(resets, core.resets)
        allowed = true; options.tick()
        core.active.clear(); options.coreStarted(); options.tick()
        assertEquals(setOf(sites.single().branch), core.active)
        options.close(); val closedResets = core.resets
        options.setInstantText(text, true); options.close()
        assertNull(options.tick()); assertEquals(closedResets, core.resets)
        assertEquals(0, options.fieldState({ _, _ -> error("closed core") }))
    }

    @Test fun anUpdateDuringPatchingRemainsPendingForTheNextFrame() {
        val core = Core(); val options = GameOptions(core, { true })
        try {
            options.setReusableTms(sites, true)
            core.duringPatch = { options.setInstantText(text, true) }
            assertEquals(GameOptionPatches.Result(true, false), options.tick())
            assertTrue(options.dirty)
            core.duringPatch = {}
            assertEquals(GameOptionPatches.Result(true, true), options.tick())
            assertFalse(options.dirty)
        } finally { options.close() }
    }

    @Test fun cancelledDiscoveryCannotPublishIntoAClosedGame() = runBlocking {
        val queue = Channel<Runnable>(Channel.UNLIMITED)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { check(queue.trySend(block).isSuccess) }
        }
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-options-${Random.nextLong().toULong()}.gba"
        FileSystem.SYSTEM.sink(file).use { it.write(okio.Buffer().write(ByteArray(65536)), 65536) }
        val core = Core(); val options = GameOptions(core, { true }, dispatcher)
        try {
            var published = false
            options.start(file, { _, _ -> published = true; true }, { published = true })
            val publication = withTimeout(10_000) { queue.receive() }
            options.close()
            publication.run()
            assertFalse(published); assertTrue(core.active.isEmpty())
            assertEquals(GameOptions.Discovery(), options.discovery)
        } finally { options.close(); FileSystem.SYSTEM.delete(file) }
    }

    @Test fun retiringForAnEngineOwnedCoreNeverUnpatchesOnTheClosingThread() {
        val core = Core()
        val options = GameOptions(core, { true })
        options.setReusableTms(sites, true); options.tick()
        val resets = core.resets
        options.close(restorePatches = false)
        assertEquals(resets, core.resets)
        assertEquals(setOf(sites.single().branch), core.active)
        assertNull(options.tick())
        assertFalse(options.dirty)
    }
    @Test fun upstreamOptionsShareFrameBoundaryAndDoNotEraseExistingPatches() {
        val core = Core(); val options = GameOptions(core, { true })
        val hm = HmFree.Patch(listOf(InstantText.Write(0x08003000, ByteArray(4), ByteArray(4))))
        val full = ExpShare.Found(0x02000000, listOf(InstantText.Write(0x08004000, ByteArray(4), ByteArray(4))))
        val cap = LevelCap.Found(listOf(LevelCap.Site(0x08005000, ByteArray(4), true)))
        try {
            options.setReusableTms(sites, true); options.setInstantText(text, true)
            options.setHmFree(hm, true); options.setFullExp(full, true); options.setLevelCap(cap, 20)
            assertTrue(core.active.isEmpty())
            assertEquals(GameOptionPatches.Result(true, true, true, true, true), options.tick())
            options.setInstantText(text, false); options.setHmFree(hm, false)
            assertTrue(0x08003000L in core.active) // UI writes wait for the next frame.
            assertEquals(GameOptionPatches.Result(true, false, false, true, true), options.tick())
            assertEquals(setOf(sites.single().branch, 0x08004000L, 0x08005000L), core.active)
            options.setLevelCap(cap, 100)
            assertEquals(GameOptionPatches.Result(true, false, false, true, false), options.tick())
            options.close(); assertTrue(core.active.isEmpty())
        } finally { options.close() }
    }

    @Test fun expPartyPolicyIsSharedAndRetiredReadsCannotTouchTheCore() {
        val core = Core(); val options = GameOptions(core, { true })
        val metadata = ExpShare.Found(0x02001000, emptyList())
        val mon = MonView(1, "test", 5, 10, 10, "", emptyList(), null)
        var reads = 0; val writes = mutableListOf<ByteArray>()
        val read = { _: Long, _: Int -> reads++; byteArrayOf(0, 0) }
        val write = { address: Long, bytes: ByteArray -> assertEquals(metadata.sentPokes, address); writes += bytes; true }
        try {
            options.setExpShare(metadata, ExpShare.Mode.SPLIT)
            options.observe(SnapshotView(connected = true, party = listOf(mon, mon.copy(isEgg = true), mon)))
            options.markExp(read, write)
            assertEquals(1, reads); assertContentEquals(byteArrayOf(5, 5), writes.single())
            options.observe(SnapshotView(connected = false)); options.markExp(read, write)
            assertEquals(1, reads)
            options.close(); options.markExp(read, write); assertEquals(1, reads)
        } finally { options.close() }
    }

    @Test fun aDsGamesExpShareComesFromTheHostAndItsNativeSideMarksIt() {
        val core = Core(); val options = GameOptions(core, { true })
        val ds = ExpShare.Found(0, emptyList(), ds = true)
        val mon = MonView(1, "test", 5, 10, 10, "", emptyList(), null)
        var reads = 0
        val read = { _: Long, _: Int -> reads++; byteArrayOf(0, 0) }
        try {
            options.setExpFound(ds)
            assertSame(ds, options.discovery.exp)
            options.setExpShare(options.discovery.exp, ExpShare.Mode.FULL)
            options.observe(SnapshotView(connected = true, party = listOf(mon, mon.copy(isEgg = true), mon)))
            assertEquals(ExpShare.Mode.FULL.id, options.dsExpShareMode); assertEquals(5, options.expSharePartyMask)
            options.markExp(read) { _, _ -> fail("a DS game's party is marked natively") }
            assertEquals(0, reads)
            options.setExpShare(ds, ExpShare.Mode.OFF); assertEquals(0, options.dsExpShareMode)
            // A GBA game's (or none) never reaches the native DS call.
            options.setExpShare(ExpShare.Found(0x02001000, emptyList()), ExpShare.Mode.SPLIT); assertEquals(0, options.dsExpShareMode)
        } finally { options.close() }
    }

    @Test fun lateLevelCapWorkerCannotPublishAfterRetirement() = runBlocking {
        val queue = Channel<Runnable>(Channel.UNLIMITED)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { check(queue.trySend(block).isSuccess) }
        }
        val core = Core()
        val options = GameOptions(core, { true }, dispatcher,
            capLookup = { LevelCap.Cap(20, "boss") }, bossesLookup = { true })
        val cap = LevelCap.Found(listOf(LevelCap.Site(0x08005000, ByteArray(4), true)))
        try {
            options.setLevelCapEnabled(true); options.setLevelCap(cap, 20)
            options.observe(SnapshotView(connected = true))
            val publication = withTimeout(10_000) { queue.receive() }
            options.close(); publication.run()
            assertNull(options.levelCapNow); assertNull(options.tick()); assertTrue(core.active.isEmpty())
        } finally { options.close() }
    }

    @Test fun currentLevelCapWorkerPublishesAndDisablingRemovesItsPatch() = runBlocking {
        val queue = Channel<Runnable>(Channel.UNLIMITED)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { check(queue.trySend(block).isSuccess) }
        }
        val core = Core()
        val options = GameOptions(core, { true }, dispatcher,
            capLookup = { LevelCap.Cap(30, "boss") }, bossesLookup = { true })
        val cap = LevelCap.Found(listOf(LevelCap.Site(0x08005000, ByteArray(4), true)))
        try {
            options.setLevelCapEnabled(true); options.setLevelCap(cap, 20)
            options.observe(SnapshotView(connected = true))
            withTimeout(10_000) { queue.receive() }.run()
            assertEquals(LevelCap.Cap(30, "boss"), options.levelCapNow)
            assertEquals(GameOptionPatches.Result(false, false, false, false, true), options.tick())
            assertEquals(setOf(0x08005000L), core.active)
            options.setLevelCapEnabled(false); options.tick(); assertTrue(core.active.isEmpty())
        } finally { options.close() }
    }

    @Test fun retiredOptionsCannotClearTheCurrentGamesHmPolicy() {
        val old = GameOptions(Core(), { true }); old.close()
        val current = GameOptions(Core(), { true })
        val hm = HmFree.Patch(listOf(InstantText.Write(0x08003000, ByteArray(4), ByteArray(4))))
        try {
            current.setHmFree(hm, true); assertTrue(HmFree.active)
            old.setHmFree(hm, true); old.resetMetadata(); old.close()
            assertTrue(HmFree.active)
        } finally { current.close() }
        assertFalse(HmFree.active)
    }

}
