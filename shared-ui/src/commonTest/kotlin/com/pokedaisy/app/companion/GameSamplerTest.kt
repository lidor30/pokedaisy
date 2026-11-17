package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

class GameSamplerTest {
    private class Queue : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun next() = tasks.removeFirst().run()
        fun drain() { while (tasks.isNotEmpty()) next() }
    }

    @Test fun aBurstUsesOneRamImageUntilDecodeEndsThenOneFollowUp() {
        val worker = Queue(); val game = Queue()
        var captures = 0; var decodes = 0; var onGameThread = true
        val received = mutableListOf<String>()
        val sampler = GameSampler(prepare = {
            assertTrue(onGameThread); val id = ++captures;
            { assertFalse(onGameThread); decodes++; SnapshotView(connected = true, error = "$id") }
        }, current = { assertTrue(onGameThread); true }, game = game,
            after = { assertTrue(onGameThread); received += it.error.orEmpty() }, worker = worker)
        try {
            repeat(500) { sampler.request() }
            assertEquals(1, captures); assertEquals(0, decodes)
            onGameThread = false; worker.next(); onGameThread = true
            assertTrue(received.isEmpty()); game.next()
            assertEquals(listOf("1"), received); assertEquals(2, captures)
            onGameThread = false; worker.next(); onGameThread = true; game.next()
            assertEquals(listOf("1", "2"), received); assertEquals(2, captures)
        } finally { sampler.close(); game.drain(); worker.drain() }
    }

    @Test fun closingBeforeWorkerOrDeliveryDiscardsTheOldGame() {
        for (decodeFirst in listOf(false, true)) {
            val worker = Queue(); val game = Queue(); var decoded = 0; var delivered = 0
            val sampler = GameSampler(prepare = { { decoded++; SnapshotView(connected = true) } }, current = { true },
                game = game, after = { delivered++ }, worker = worker)
            sampler.request()
            if (decodeFirst) worker.next()
            sampler.close(); worker.drain(); game.drain()
            assertEquals(if (decodeFirst) 1 else 0, decoded); assertEquals(0, delivered)
        }
    }

    @Test fun synchronousReadsStayOnTheCallerAndFailedCaptureCanRecover() {
        val worker = Queue(); val game = Queue(); var capture = false; var delivered = 0
        val sampler = GameSampler(prepare = { if (!capture) null else { { SnapshotView(connected = true) } } },
            current = { true }, game = game, after = { delivered++ }, asynchronous = false, worker = worker)
        try {
            sampler.request(); assertEquals(0, delivered)
            capture = true; sampler.request(); assertEquals(1, delivered)
        } finally { sampler.close(); worker.drain(); game.drain() }
    }

    @Test fun aQueuedImageIsRejectedAfterTelemetryResetBeforeItReadsAnything() {
        val telemetry = TelemetryStore(); var reads = 0
        val oldImage = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray { reads++; error("old RAM reached the new decoder") }
        }
        val work = telemetry.deferredRefresh(oldImage)
        telemetry.reset()
        assertNull(work()); assertEquals(0, reads); assertEquals(0L, telemetry.samplesTaken)
        assertFalse(telemetry.snapshot.value.connected)
    }
}
