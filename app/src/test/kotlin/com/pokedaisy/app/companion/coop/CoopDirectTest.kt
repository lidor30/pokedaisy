package com.pokedaisy.app.companion.coop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopDirectTest {
    @Test
    fun abortEndsASocketWhoseWriteIsBlockedOnAPeerThatNeverReads() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val pool = Executors.newCachedThreadPool()
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        client.sendBufferSize = 4096
        val silent = server.accept() // never read from
        val closed = CountDownLatch(1)
        val ds = DirectSocket(client, pool, {}, { closed.countDown() })
        ds.start()
        val big = ByteArray(60_000)
        repeat(200) { ds.writer.offer(big) } // far more than the socket buffers hold: the writer blocks
        Thread.sleep(300)
        ds.writer.closeSoon() // only queues END behind the blocked write
        assertTrue("a drained close must not finish while the write is stuck", !closed.await(300, TimeUnit.MILLISECONDS))
        ds.abort()
        assertTrue("abort closes the socket and the reader ends", closed.await(5, TimeUnit.SECONDS))
        silent.close()
        server.close()
        pool.shutdownNow()
    }
}
