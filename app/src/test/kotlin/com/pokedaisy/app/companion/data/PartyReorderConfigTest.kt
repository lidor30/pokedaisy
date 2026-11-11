package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.TelemetryStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The reorder capability after detection. Detection itself calls MgbaCore's JNI entry points;
 * these tests recreate its cached state with reflection, then exercise the public accessor.
 * They do not claim to test ROM hashing or JNI detection.
 */
class PartyReorderConfigTest {
    private val previousConfig = activeNativeConfig
    private val previousGame = activeGame
    private val previousCode = romGameCode
    private val previousLanguage = romLanguage

    @After fun reset() {
        activeNativeConfig = previousConfig
        activeGame = previousGame
        romGameCode = previousCode
        romLanguage = previousLanguage
        resetNativeBagCache()
    }

    private fun TelemetrySampler.detected(
        kind: GameKind = GameKind.UNBOUND,
        hash: String? = TelemetrySampler.UNBOUND_V2_1_1_1_SHA1,
        verified: Boolean = true,
        config: NativeConfig? = null,
    ): TelemetrySampler = apply {
        state("kind", kind)
        state("romSha1", hash)
        state("ramVerified", verified)
        state("nativeCfg", config)
    }

    private fun TelemetrySampler.state(name: String, value: Any?) {
        TelemetrySampler::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    @Test fun verifiedEnglishUnboundGetsItsFallbackWithoutEnablingOtherConfigUsers() {
        activeNativeConfig = null
        val sampler = TelemetrySampler().detected()

        assertSame(NATIVE_UNBOUND_WITH_DEX, sampler.knownPartyReorderConfig)
        assertEquals(0x02024284L, sampler.knownPartyReorderConfig!!.playerParty)
        assertEquals(100, sampler.knownPartyReorderConfig!!.monStride)
        assertNull(activeNativeConfig)
        assertNull(sampler.knownPartyMenu)
        assertNull(sampler.knownFieldItems)
    }

    @Test fun preDetectionDoesNotBorrowThePreviousRomsGlobalConfigOrGame() {
        activeNativeConfig = NATIVE_FIRERED_REV1
        activeGame = GameKind.UNBOUND

        assertNull(TelemetrySampler().knownPartyReorderConfig)
    }

    @Test fun missingUnknownAndFrenchHashesCannotEnableTheEnglishFallback() {
        for (hash in listOf(null, "0000000000000000000000000000000000000000", TelemetrySampler.UNBOUND_V2_1_1_1_FR_SHA1)) {
            assertNull("hash=$hash", TelemetrySampler().detected(hash = hash).knownPartyReorderConfig)
        }
    }

    @Test fun anUnverifiedEnglishHashCannotEnableTheFallback() {
        assertNull(TelemetrySampler().detected(verified = false).knownPartyReorderConfig)
    }

    @Test fun anotherGameCannotBorrowTheEnglishFallback() {
        assertNull(TelemetrySampler().detected(kind = GameKind.FIRERED).knownPartyReorderConfig)
    }

    @Test fun bestEffortStateCannotEnableTheEnglishFallback() {
        val sampler = TelemetrySampler().detected()
        sampler.state("beMatch", BestEffort.Match(BestEffort.candidate("UNBOUND")!!, full = true, off = emptySet(), score = 1))

        assertNull(sampler.knownPartyReorderConfig)
    }

    @Test fun existingNativeConfigsKeepTheirOwnLayoutAndMetadata() {
        // Native configs may have been copied by detection to add verified shiny metadata.
        val french = NATIVE_UNBOUND_FR.copy(shinyBelow = 16)
        val fireRed = NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4)

        assertSame(fireRed, TelemetrySampler().detected(GameKind.FIRERED, hash = null, config = fireRed).knownPartyReorderConfig)
        assertSame(french, TelemetrySampler().detected(hash = TelemetrySampler.UNBOUND_V2_1_1_1_FR_SHA1, config = french).knownPartyReorderConfig)
    }

    @Test fun storeForwardsTheCapabilityAndResetClearsItBeforeTheNextRomIsDetected() {
        val store = TelemetryStore()
        TelemetryStore::class.java.getDeclaredField("sampler").apply { isAccessible = true }
            .set(store, TelemetrySampler().detected())
        assertSame(NATIVE_UNBOUND_WITH_DEX, store.knownPartyReorderConfig())

        activeNativeConfig = NATIVE_UNBOUND_FR
        store.reset()

        assertNull(store.knownPartyReorderConfig())
    }

    @Test fun fallbackLayoutReordersWholePartyRecordsAndStillDecodesThem() {
        activeGame = GameKind.UNBOUND
        romGameCode = ""
        romLanguage = 'E'
        val cfg = TelemetrySampler().detected().knownPartyReorderConfig!!
        // The French capture has three distinct party members and shares English Unbound's RAM layout.
        // Use only a writable in-memory copy of the existing fixture; no ROM or save is changed.
        val fixture = FixtureMemoryReader.load("unbound_fr")
        val ram = fixture.readCoreMemory(0x02000000L, 0x40000)
        val game = object : MemoryReader, GameCalls {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray =
                if (addr >= 0x02000000L && addr + size <= 0x02040000L) {
                    val offset = (addr - 0x02000000L).toInt()
                    ram.copyOfRange(offset, offset + size)
                } else fixture.readCoreMemory(addr, size)

            override fun read(addr: Long, len: Int) = readCoreMemory(addr, len)
            override fun write(addr: Long, bytes: ByteArray): Boolean {
                if (addr < 0x02000000L || addr + bytes.size > 0x02040000L) return false
                bytes.copyInto(ram, (addr - 0x02000000L).toInt())
                return true
            }
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = error("reordering must not call ROM code")
        }
        val before = readNativeTelemetry(game, cfg).party
        assertEquals(listOf(246, 346, 225), before.map { it.species })
        val expectedRam = ram.copyOf()
        val first = (cfg.playerParty - 0x02000000L).toInt()
        val third = first + 2 * cfg.monStride
        ram.copyInto(expectedRam, first, third, third + cfg.monStride)
        ram.copyInto(expectedRam, third, first, first + cfg.monStride)

        assertEquals(PartySwapResult.SWAPPED, swapPartySlots(game, cfg.playerParty, cfg.monStride, before.size, 0, 2))
        val after = readNativeTelemetry(game, cfg).party
        assertEquals(listOf(before[2], before[1], before[0]).map { it.species to it.personality }, after.map { it.species to it.personality })
        assertArrayEquals(before[2].moves, after[0].moves)
        assertArrayEquals(before[0].moves, after[2].moves)
        assertArrayEquals("only the two complete party records change", expectedRam, ram)
    }
}
