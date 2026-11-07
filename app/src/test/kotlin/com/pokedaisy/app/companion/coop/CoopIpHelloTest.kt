package com.pokedaisy.app.companion.coop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class CoopIpHelloTest {
    private val ticket = ticketOf(0xB2)

    @Test fun handsOutTheTicket() {
        val s = CoopIpHelloServer { CoopInvite.link(ticket) }
        val port = s.start()
        try { assertEquals(ticket, CoopIpHelloClient.fetch("127.0.0.1", port)) } finally { s.stop() }
    }

    @Test fun wrongGreetingGetsNothing() {
        val s = CoopIpHelloServer { CoopInvite.link(ticket) }
        val port = s.start()
        try {
            Socket("127.0.0.1", port).use { c ->
                c.soTimeout = 10_000
                c.getOutputStream().apply { write("GET / HTTP/1.1\r\n".toByteArray()); flush() }
                assertEquals(-1, c.getInputStream().read())
            }
        } finally { s.stop() }
    }

    @Test fun silentClientTimesOut() {
        val s = CoopIpHelloServer { CoopInvite.link(ticket) }
        val port = s.start()
        try {
            Socket("127.0.0.1", port).use { c ->
                c.soTimeout = 10_000
                val t0 = System.nanoTime()
                assertEquals(-1, c.getInputStream().read())
                assertTrue((System.nanoTime() - t0) / 1_000_000 < 4_500)
            }
            assertEquals(ticket, CoopIpHelloClient.fetch("127.0.0.1", port))
        } finally { s.stop() }
    }

    @Test fun answersAtMostTwiceASecond() {
        var now = 1_000_000_000L // a fake clock: no wall-clock timing in this test
        val s = CoopIpHelloServer({ now }) { CoopInvite.link(ticket) }
        val port = s.start()
        try {
            assertEquals(ticket, CoopIpHelloClient.fetch("127.0.0.1", port))
            now += 499_000_000L
            assertNull(CoopIpHelloClient.fetch("127.0.0.1", port))
            now += 1_000_000L
            assertEquals(ticket, CoopIpHelloClient.fetch("127.0.0.1", port))
        } finally { s.stop() }
    }

    @Test fun parsesHostPort() {
        assertEquals("192.168.1.20" to 47321, parseHostPort("192.168.1.20:47321"))
        assertEquals("fe80::1" to 47321, parseHostPort("[fe80::1]:47321"))
        assertNull(parseHostPort("192.168.1.20"))
        assertNull(parseHostPort("host:70000"))
        assertNull(parseHostPort("host:0"))
        assertNull(parseHostPort("fe80::1:47321"))
    }
}
