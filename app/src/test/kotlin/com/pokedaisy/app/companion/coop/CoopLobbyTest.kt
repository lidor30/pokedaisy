package com.pokedaisy.app.companion.coop

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopLobbyTest {
    private var now = 1_000_000L
    private val game = "f3ae088181bf583e"
    private val invite = "pdco" + "a".repeat(82)

    /** One socket at a time; the test opens it, answers and closes it by hand (never inline from openLobby). */
    private class FakeLobby : CoopLobbyConnector {
        var opened = 0
        var listener: CoopRelaySocket.Listener? = null
        val sent = mutableListOf<ByteArray>()
        var closedByClient = false

        override fun openLobby(relayUrl: String, listener: CoopRelaySocket.Listener): CoopRelaySocket {
            opened++
            this.listener = listener
            closedByClient = false
            sent.clear()
            return object : CoopRelaySocket {
                override fun send(bytes: ByteArray): Boolean { sent.add(bytes); return true }
                override fun close() { closedByClient = true }
            }
        }

        fun open() = listener!!.onOpen()
        fun answer(bytes: ByteArray) = listener!!.onMessage(bytes)
        fun drop(code: Int = 1006) = listener!!.onClosed(code)
        fun kinds(): List<Byte> = sent.map { it[0] }
    }

    private fun lobby(fake: FakeLobby) =
        CoopLobby(fake, "https://relay.example.com", { now }, CoopBackoff(java.util.Random(1)))

    private fun msg(vararg parts: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) when (p) {
            is Int -> out.write(p)
            is Byte -> out.write(p.toInt())
            is String -> { val raw = p.toByteArray(); out.write(raw.size); out.write(raw) }
        }
        return out.toByteArray()
    }

    private fun hosted(code: String) = msg(CoopLobbyWire.HOSTED, code)
    private fun host(listed: Boolean = false) = CoopLobby.Host(invite, game, "MAY", listed)

    @Test
    fun hostingGetsACodeAndListingKeepsIt() {
        val fake = FakeLobby()
        val l = lobby(fake)
        l.setHost(host())
        assertEquals(1, fake.opened)
        assertTrue(fake.sent.isEmpty())   // nothing goes before the socket is open
        fake.open()
        assertArrayEquals(CoopLobbyWire.host(false, invite, game, "MAY"), fake.sent.single())
        fake.answer(hosted("K7Q4MX"))
        assertEquals("K7Q4MX", l.state.value.code)
        assertEquals(false, l.state.value.listed)

        l.setHost(host(listed = true))
        assertArrayEquals(CoopLobbyWire.host(true, invite, game, "MAY"), fake.sent.last())
        fake.answer(hosted("K7Q4MX"))
        assertEquals(true, l.state.value.listed)

        l.setHost(host(listed = true))      // no change: nothing sent
        assertEquals(2, fake.sent.size)
        l.setHost(null)
        assertEquals(CoopLobbyWire.UNHOST, fake.sent.last()[0])
        assertNull(l.state.value.code)
    }

    @Test
    fun answersMatchRequestsInOrder() {
        val fake = FakeLobby()
        val l = lobby(fake)
        var found: CoopLobby.FindResult? = null
        l.setHost(host())
        l.find("k7q-4mx") { found = it }
        l.refresh(game)
        assertTrue(l.state.value.loadingRooms)
        fake.open()
        assertEquals(listOf(CoopLobbyWire.HOST, CoopLobbyWire.FIND, CoopLobbyWire.LIST), fake.kinds())
        assertArrayEquals(CoopLobbyWire.find("K7Q4MX"), fake.sent[1])
        fake.answer(hosted("ABCDEF"))
        fake.answer(msg(CoopLobbyWire.FOUND, invite))
        fake.answer(msg(CoopLobbyWire.ROOMS, 1, "ZZZ222", "BEN", 0, 3))
        assertEquals(CoopLobby.FindResult.Found(invite), found)
        assertEquals(listOf(CoopLobbyWire.Listing("ZZZ222", "BEN", 3)), l.state.value.rooms)
        assertEquals(false, l.state.value.loadingRooms)
    }

    @Test
    fun findErrorsAndBadCodes() {
        val fake = FakeLobby()
        val l = lobby(fake)
        val results = mutableListOf<CoopLobby.FindResult>()
        l.find("K7Q4M0") { results.add(it) }   // 0 isn't in the alphabet: no socket needed
        assertEquals(listOf<CoopLobby.FindResult>(CoopLobby.FindResult.BadCode), results)
        assertEquals(0, fake.opened)
        l.find("K7Q4MX") { results.add(it) }
        l.find("K7Q4MY") { results.add(it) }
        fake.open()
        fake.answer(msg(CoopLobbyWire.ERROR, CoopLobbyWire.FIND, CoopLobbyWire.NOT_FOUND))
        fake.answer(msg(CoopLobbyWire.ERROR, CoopLobbyWire.FIND, CoopLobbyWire.LIMIT))
        assertEquals(listOf(CoopLobby.FindResult.BadCode, CoopLobby.FindResult.NotFound, CoopLobby.FindResult.Limit), results)
    }

    @Test
    fun aDroppedSocketTakesTheRoomAndHostsAgain() {
        val fake = FakeLobby()
        val l = lobby(fake)
        var found: CoopLobby.FindResult? = null
        l.setHost(host())
        fake.open()
        fake.answer(hosted("K7Q4MX"))
        l.find("ABCDEF") { found = it }
        fake.drop()
        assertNull(l.state.value.code)
        assertEquals(CoopLobby.Problem.OFFLINE, l.state.value.problem)
        assertEquals(CoopLobby.FindResult.Offline, found)

        l.tick()
        assertEquals(1, fake.opened)        // the backoff's wait first
        now += 2_000
        l.tick()
        assertEquals(2, fake.opened)
        fake.open()
        assertEquals(listOf(CoopLobbyWire.HOST), fake.kinds())
        assertNull(l.state.value.problem)
        fake.answer(hosted("NEWNEW"))
        assertEquals("NEWNEW", l.state.value.code)
    }

    @Test
    fun aLimitedCloseSaysSo() {
        val fake = FakeLobby()
        val l = lobby(fake)
        l.setHost(host())
        fake.drop(4429)
        assertEquals(CoopLobby.Problem.LIMIT, l.state.value.problem)
    }

    @Test
    fun aHostRefusalIsAProblemNotACode() {
        val fake = FakeLobby()
        val l = lobby(fake)
        l.setHost(host())
        fake.open()
        fake.answer(msg(CoopLobbyWire.ERROR, CoopLobbyWire.HOST, CoopLobbyWire.FULL))
        assertNull(l.state.value.code)
        assertEquals(CoopLobby.Problem.FULL, l.state.value.problem)
    }

    @Test
    fun aMismatchedAnswerReconnects() {
        val fake = FakeLobby()
        val l = lobby(fake)
        var found: CoopLobby.FindResult? = null
        l.find("ABCDEF") { found = it }
        fake.open()
        fake.answer(hosted("K7Q4MX"))   // a HOSTED for a FIND
        assertTrue(fake.closedByClient)
        assertEquals(CoopLobby.FindResult.Offline, found)
    }

    @Test
    fun theRoomIsRefreshedAndAnUnusedSocketLetGo() {
        val fake = FakeLobby()
        val l = lobby(fake)
        l.setHost(host())
        fake.open()
        fake.answer(hosted("K7Q4MX"))
        now += 10 * 60_000
        l.tick()
        assertEquals(listOf(CoopLobbyWire.HOST, CoopLobbyWire.HOST), fake.kinds())
        fake.answer(hosted("K7Q4MX"))
        l.setHost(null)
        now += 59_000
        l.tick()
        assertTrue(!fake.closedByClient)
        now += 1_000
        l.tick()
        assertTrue(fake.closedByClient)
    }

    @Test
    fun closeAnswersWaitingLookups() {
        val fake = FakeLobby()
        val l = lobby(fake)
        var found: CoopLobby.FindResult? = null
        l.find("ABCDEF") { found = it }
        l.close()
        assertEquals(CoopLobby.FindResult.Offline, found)
        l.find("ABCDEF") { found = null }
        assertNull(found)   // answered Offline at once: the callback ran and cleared it
    }

    @Test
    fun theLobbysInviteHasNoAddresses() {
        val peer = CoopPeerId("ab".repeat(32))
        val secret = ByteArray(16) { it.toByte() }
        val full = CoopTicket(peer, secret, listOf(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)), 47321))).encode()
        val shared = CoopLobbyWire.lobbyInvite(full)
        assertNotNull(shared)
        val back = CoopTicket.decode(shared!!)!!
        assertEquals(peer, back.peer)
        assertArrayEquals(secret, back.inviteSecret)
        assertTrue(back.addresses.isEmpty())
        assertTrue(CoopInvite.isTicket(shared))
        // No invite secret: nothing a relay join could use.
        assertNull(CoopLobbyWire.lobbyInvite(CoopTicket.peerOnly(peer)))
        assertNull(CoopLobbyWire.lobbyInvite("junk"))
    }

    @Test
    fun codesNamesAndGameKeys() {
        assertEquals("K7Q4MX", CoopLobbyWire.normalizeCode(" k7q-4mx "))
        assertNull(CoopLobbyWire.normalizeCode("K7Q4MO"))
        assertNull(CoopLobbyWire.normalizeCode("K7Q4MXY"))
        assertEquals("K7Q-4MX", CoopLobbyWire.showCode("K7Q4MX"))
        assertEquals("MAY", CoopLobbyWire.cleanName("MAY", "TRAINER"))
        assertEquals("Ann B.", CoopLobbyWire.cleanName(" Ann  B.… ", "TRAINER"))
        assertEquals("TRAINER", CoopLobbyWire.cleanName("…", "TRAINER"))
        assertEquals("f3ae088181bf583e", CoopLobbyWire.gameKey(EMERALD_COOP_SHA1.uppercase()))
    }

    @Test
    fun wireDecodesAnswersAndRefusesJunk() {
        assertEquals(CoopLobbyWire.Answer.Hosted("K7Q4MX"), CoopLobbyWire.decode(hosted("K7Q4MX")))
        assertEquals(CoopLobbyWire.Answer.Error(CoopLobbyWire.LIST, 3), CoopLobbyWire.decode(msg(CoopLobbyWire.ERROR, CoopLobbyWire.LIST, 3)))
        assertEquals(CoopLobbyWire.Answer.Rooms(emptyList()), CoopLobbyWire.decode(msg(CoopLobbyWire.ROOMS, 0)))
        assertNull(CoopLobbyWire.decode(msg(CoopLobbyWire.ROOMS, 1, "ABCDEF")))   // cut short
        assertNull(CoopLobbyWire.decode(msg(CoopLobbyWire.HOSTED, "ABCDEF", 0)))  // bytes left over
        assertNull(CoopLobbyWire.decode(msg(CoopLobbyWire.HOST, "ABCDEF")))       // a client kind
        assertNull(CoopLobbyWire.decode(ByteArray(0)))
    }
}
