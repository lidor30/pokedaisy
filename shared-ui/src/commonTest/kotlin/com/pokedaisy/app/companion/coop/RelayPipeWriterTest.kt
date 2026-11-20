package com.pokedaisy.app.companion.coop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class RelayPipeWriterTest {
    @Test fun fullQueueDrainsInOrderBeforeSocketCloses() = runBlocking {
        val pipe = RelayPipeWriter(this)
        val socket = RecordingSocket()
        for (index in 0 until CoopPipe.CAPACITY) {
            assertTrue(pipe.offer(message(index)))
        }
        assertEquals(CoopPipe.CAPACITY, pipe.backlog)
        assertFalse(pipe.offer(message(CoopPipe.CAPACITY)))
        pipe.attach(socket)
        pipe.closeSoon()
        assertFalse(pipe.offer(message(CoopPipe.CAPACITY)))
        withTimeout(2_000) { pipe.join() }

        assertEquals((0 until CoopPipe.CAPACITY).toList(), socket.sent)
        assertEquals(1, socket.closed)
        assertEquals(0, pipe.backlog)
    }

    @Test fun closeNowDiscardsQueuedFramesAndClosesTheSocket() = runBlocking {
        val pipe = RelayPipeWriter(this)
        val socket = RecordingSocket()
        assertTrue(pipe.offer(message(1)))
        pipe.attach(socket)
        pipe.closeNow()
        withTimeout(2_000) { pipe.join() }

        assertTrue(socket.sent.isEmpty())
        assertEquals(1, socket.closed)
        assertEquals(0, pipe.backlog)
    }

    @Test fun lateSocketIsClosedWhenWriterSealsBeforeAttachment() = runBlocking {
        val pipe = RelayPipeWriter(this)
        assertTrue(pipe.offer(message(1)))
        pipe.closeSoon()
        // The connector can return immediately after close, before the worker has seen the seal.
        val socket = RecordingSocket()
        pipe.attach(socket)
        withTimeout(2_000) { pipe.join() }

        assertTrue(socket.sent.isEmpty())
        assertEquals(1, socket.closed)
        assertEquals(0, pipe.backlog)
    }

    @Test fun immediateCloseBeforeAttachmentRejectsTheLateSocket() = runBlocking {
        val pipe = RelayPipeWriter(this)
        assertTrue(pipe.offer(message(1)))
        pipe.closeNow()
        val socket = RecordingSocket()
        pipe.attach(socket)
        withTimeout(2_000) { pipe.join() }

        assertTrue(socket.sent.isEmpty())
        assertEquals(1, socket.closed)
        assertEquals(0, pipe.backlog)
    }

    @Test fun attachmentDeadlineClosesTheQueueAndRejectsLateSocket() = runBlocking {
        val pipe = RelayPipeWriter(this, attachTimeoutMs = 1)
        assertTrue(pipe.offer(message(1)))
        withTimeout(2_000) { pipe.join() }
        val socket = RecordingSocket()
        pipe.attach(socket)

        assertFalse(pipe.offer(message(2)))
        assertEquals(0, pipe.backlog)
        assertTrue(socket.sent.isEmpty())
        assertEquals(1, socket.closed)
    }

    private fun message(index: Int) = byteArrayOf((index shr 8).toByte(), index.toByte())

    private class RecordingSocket : CoopRelaySocket {
        val sent = mutableListOf<Int>()
        var closed = 0
        override fun send(bytes: ByteArray): Boolean {
            sent += (bytes[0].toInt() and 255) * 256 + (bytes[1].toInt() and 255)
            return true
        }
        override fun close() {
            closed++
        }
    }
}
