package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.test.*

class RegisteredItemUseTest {
    private var previousGame = GameKind.EMERALD
    @BeforeTest fun rememberGame() { previousGame = activeGame; activeGame = GameKind.EMERALD }
    @AfterTest fun restoreGame() { activeGame = previousGame }

    private class Harness {
        var register = 262
        var locked = false
        var riding = false
        var known = true
        var battling = false
        var pressed = 0
        var rejected = 0
        var writes = 0
        var writeAllowed = true
        var kind = GameKind.EMERALD
        var cfg = NATIVE_EMERALD.copy(saveBlock1Ptr = 0x02001000L, staticSaveBlocks = true)
        var fields: FieldItemSupport? = FieldItemSupport(
            FieldItemFns(0, 0, 0, 0, 0x08000100, 0, 0, listOf(420107693L)), emptySet())
        val presses = mutableListOf<Int>()
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = if (locked) 1L else 0L
            override fun read(addr: Long, len: Int): ByteArray = if (len == 2)
                byteArrayOf(register.toByte(), (register shr 8).toByte()) else ByteArray(len)
            override fun write(addr: Long, bytes: ByteArray): Boolean {
                if (!writeAllowed) return false
                register = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
                writes++
                return true
            }
        }
        val session = GameSession(calls, { true }) { _, e -> throw e }
        val use = RegisteredItemUse(session, { fields }, { cfg }, { kind }, { battling }, { riding }, { known },
            { pressed = it; presses += it }, { rejected++ })
        fun frames(n: Int = 1) = repeat(n) { session.serviceField(true, true) }
    }

    @Test fun startsBetweenFramesAndRestoresThePlayersRegistrationAfterTheGameAcceptsSelect() {
        val h = Harness()
        h.use.use(259)
        assertEquals(262, h.register); assertEquals(0, h.pressed)
        h.frames(); assertEquals(259, h.register); assertEquals(CoreKey.SELECT, h.pressed)
        h.riding = true
        h.frames(50)
        assertEquals(262, h.register); assertEquals(0, h.pressed); assertEquals(0, h.rejected)
        assertEquals(listOf(CoreKey.SELECT, 0, 0), h.presses)
    }

    @Test fun droppedSelectRetriesButNeverTogglesAgainAfterRidingChanges() {
        val h = Harness(); h.use.use(259); h.frames(14)
        assertEquals(2, h.presses.count { it == CoreKey.SELECT })
        h.riding = true; h.frames(60)
        assertEquals(2, h.presses.count { it == CoreKey.SELECT })
        assertEquals(262, h.register)
    }

    @Test fun fishingLockAndUnknownAvatarAvoidBlindSelectRetries() {
        val rod = Harness(); rod.use.use(264); rod.frames(); rod.locked = true; rod.frames(60)
        assertEquals(1, rod.presses.count { it == CoreKey.SELECT }); assertEquals(262, rod.register)
        val unknown = Harness(); unknown.known = false; unknown.use.use(259); unknown.frames(60)
        assertEquals(1, unknown.presses.count { it == CoreKey.SELECT }); assertEquals(262, unknown.register)
    }

    @Test fun retriesAreBoundedAndRestoreEvenWhenTheGameNeverAcceptsThePress() {
        val h = Harness(); h.use.use(259); h.frames(300)
        assertEquals(15, h.presses.count { it == CoreKey.SELECT })
        assertEquals(262, h.register); assertEquals(0, h.pressed)
    }

    @Test fun cancellationBeforeStateOperationsRestoresImmediatelyAndRetiresAllDelayedPresses() {
        val h = Harness(); h.use.use(259); h.frames()
        h.use.cancel()
        assertEquals(262, h.register); assertEquals(0, h.pressed)
        val presses = h.presses.toList(); val writes = h.writes
        // A state load has replaced the core memory. Old jobs must not touch it.
        h.register = 263; h.frames(300)
        assertEquals(263, h.register); assertEquals(presses, h.presses); assertEquals(writes, h.writes)
    }

    @Test fun cancellationRetiresAnUnstartedRequestAndDoesNotOverwriteANewerRegistration() {
        val queued = Harness(); queued.use.use(259); queued.use.cancel(); queued.frames(300)
        assertEquals(0, queued.writes); assertTrue(queued.presses.isEmpty())
        val changed = Harness(); changed.use.use(259); changed.frames(); changed.register = 264
        changed.use.cancel(); changed.frames(300)
        assertEquals(264, changed.register); assertEquals(1, changed.writes)
    }

    @Test fun unsupportedBusyInvalidAndFailedWritesDoNotTouchTheGame() {
        val cases = listOf<Harness.() -> Unit>(
            { kind = GameKind.RED }, { cfg = cfg.copy(moneyOff = 0x123) }, { fields = null },
            { locked = true }, { battling = true }, { register = 0xFFFF }, { writeAllowed = false },
            { fields = FieldItemSupport(fields!!.fns.copy(codeCrcs = listOf(1)), emptySet()) },
        )
        for (setup in cases) {
            val h = Harness().apply(setup); val register = h.register
            h.use.use(259); h.frames(300)
            assertEquals(register, h.register); assertEquals(0, h.writes)
            assertTrue(h.presses.isEmpty()); assertEquals(1, h.rejected)
        }
    }

    @Test fun aSecondRequestCannotReplaceTheFirstTransactionsRestoreValue() {
        val h = Harness(); h.use.use(259); h.use.use(264); h.frames(); h.riding = true; h.frames(60)
        assertEquals(1, h.rejected); assertEquals(262, h.register)
    }

    @Test fun soulGoldUsesSelectThenDownAndCancellationRetiresTheWheelSteps() {
        activeGame = GameKind.SOULGOLD
        val h = Harness(); h.kind = GameKind.SOULGOLD; h.use.use(709); h.frames(35)
        assertEquals(listOf(CoreKey.SELECT, 0, CoreKey.DOWN, 0, 0), h.presses)
        assertEquals(262, h.register)
        val cancelled = Harness(); cancelled.kind = GameKind.SOULGOLD
        cancelled.use.use(709); cancelled.frames(); cancelled.use.cancel(); cancelled.frames(50)
        assertFalse(CoreKey.DOWN in cancelled.presses); assertEquals(262, cancelled.register)
    }

    @Test fun closingTheRuntimeRestoresBeforeRetiringTheCoreQueue() {
        val h = Harness()
        val previousConfig = activeNativeConfig
        activeNativeConfig = h.cfg
        val telemetry = TelemetryStore().apply { showCached(SnapshotView(connected = true, game = GameKind.EMERALD)) }
        val runtime = GameRuntime(h.session, telemetry,
            com.pokedaisy.app.Prefs(PrefsCompanionSettingsTest.Store()), GameBattleInput(telemetry, null, 0),
            object : RomOptionPatcher {
                override fun unpatch() {}
                override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray) = true
            }, { true })
        try {
            val registered = runtime.bindRegisteredItems({ h.fields }, { h.pressed = it }, { fail("refused") })
            registered.use(259); h.frames()
            assertEquals(259, h.register); assertEquals(CoreKey.SELECT, h.pressed)
            runtime.close()
            assertEquals(262, h.register); assertEquals(0, h.pressed)
            h.frames(300); assertEquals(262, h.register)
        } finally { runtime.close(); activeNativeConfig = previousConfig }
    }
}
