package com.pokedaisy.app.companion.coop

import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopEndpointSessionTest {
    @Test fun pairingHoldKeepsOneEndpointAndRestartUsesNewSettings() {
        val underlying = FakeNet().node(1, "ticket")
        val starts = ArrayList<CoopEndpointOptions>()
        var closes = 0
        var options = CoopEndpointOptions(null, false)
        val localNetwork = ArrayList<Boolean>()
        val transport = object : CoopTransport by underlying {
            override fun start(secretKey: ByteArray, relayUrl: String?, relayOnly: Boolean): Int {
                starts.add(CoopEndpointOptions(relayUrl, relayOnly))
                return 0
            }

            override fun close() { closes++ }
        }
        val session = CoopEndpointSession(
            createTransport = { transport },
            directory = { Files.createTempDirectory("pokedaisy-coop-session").toFile().toOkioPath() },
            version = { "test" },
            secretKey = { ByteArray(32) },
            relayConnector = { error("this test has no lobby") },
            options = { options },
            localNetworkActive = { localNetwork.add(it) },
        )
        fun checkpoint() {
            val completed = CountDownLatch(1)
            session.afterStart { completed.countDown() }
            assertTrue("session command timed out", completed.await(5, TimeUnit.SECONDS))
        }

        val initial = options
        session.pairing()
        session.pairing()
        checkpoint()
        assertEquals(listOf(initial), starts)

        session.changeRelay { options = options.copy(relayOnly = true) }
        checkpoint()
        assertEquals(listOf(initial, options), starts)
        assertEquals(1, closes)

        session.releasePairing()
        checkpoint()
        assertEquals(2, closes)
        assertEquals(listOf(true, false), localNetwork)
    }

    @Test fun currentPairAndRelayUseTheGameGuardWhileOtherPairsRemainEditable() = runBlocking {
        val session = CoopEndpointSession(
            createTransport = { FakeNet().node(1, "ticket") },
            directory = { Files.createTempDirectory("pokedaisy-coop-settings").toFile().toOkioPath() },
            version = { "test" },
            secretKey = { ByteArray(32) },
            relayConnector = { error("this test has no lobby") },
            options = { CoopEndpointOptions(null, false) },
            settingsDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        val current = CoopSaveId(1, 2, ByteArray(8))
        val other = CoopSaveId(3, 4, ByteArray(8))
        val startup = CoopGameStartup {}
        val lookup = CompletableDeferred<CoopSaveId>()
        var currentSave: CoopSaveId? = null
        var inBattle = true
        var refused = 0
        var paused = 0
        var detached = 0
        session.bindGameSettings(CoopGameSettings(
            startup = startup,
            ownSave = { currentSave },
            refuseBattle = { inBattle.also { if (it) refused++ } },
            pauseBattles = { paused++; true },
            resumeBattles = { error("successful unpair should detach") },
            detach = { detached++ },
        ))
        startup.start(this, resolve = { lookup.await() }) { currentSave = it }
        val delayed = CompletableDeferred<Boolean>()
        session.unpair(current) { delayed.complete(it) }
        yield()
        assertFalse(delayed.isCompleted)
        lookup.complete(current)
        assertFalse(withTimeout(5_000) { delayed.await() })

        assertFalse(session.changeRelay { error("blocked relay change cannot write preferences") })
        val removeOther = CompletableDeferred<Boolean>()
        session.unpair(other) { removeOther.complete(it) }
        assertTrue(withTimeout(5_000) { removeOther.await() })
        assertEquals(2, refused)
        assertEquals(0, detached)

        inBattle = false
        var changed = false
        assertTrue(session.changeRelay { changed = true })
        assertTrue(changed)
        assertEquals(0, detached)
        val removeCurrent = CompletableDeferred<Boolean>()
        session.unpair(current) { removeCurrent.complete(it) }
        assertTrue(withTimeout(5_000) { removeCurrent.await() })
        assertEquals(1, paused)
        assertEquals(1, detached)
    }
}
