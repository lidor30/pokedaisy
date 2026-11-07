package com.pokedaisy.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class RelayLobbyTest {
    private var now = 1_000_000L
    private val game = "f3ae088181bf583e"
    private val other = "0123456789abcdef"
    private val invite = "pdco" + "a".repeat(82)

    private fun lobby(config: RelayConfig = RelayConfig()) = RelayLobby(config, { now }, java.util.Random(7))

    private fun host(listed: Boolean = false, name: String = "MAY", g: String = game, inv: String = invite) =
        LobbyWire.Request.Host(listed, inv, g, name)

    private fun codeOf(answer: ByteArray?): String {
        assertEquals(LobbyWire.HOSTED, answer!![0])
        return String(answer, 2, answer[1].toInt(), Charsets.UTF_8)
    }

    private fun assertError(answer: ByteArray?, request: Byte, reason: Int) {
        assertArrayEquals(byteArrayOf(LobbyWire.ERROR, request, reason.toByte()), answer)
    }

    /** ROOMS' entries as (code, name, minutes). */
    private fun roomsOf(answer: ByteArray?): List<Triple<String, String, Int>> {
        assertEquals(LobbyWire.ROOMS, answer!![0])
        var i = 1
        fun str(): String { val n = answer[i++].toInt() and 0xFF; return String(answer, i, n, Charsets.UTF_8).also { i += n } }
        val n = answer[i++].toInt() and 0xFF
        val out = List(n) {
            val code = str()
            val name = str()
            val minutes = ((answer[i].toInt() and 0xFF) shl 8) or (answer[i + 1].toInt() and 0xFF)
            i += 2
            Triple(code, name, minutes)
        }
        assertEquals(answer.size, i)
        return out
    }

    @Test
    fun aCodeFindsTheHostsInvite() {
        val l = lobby()
        assertTrue(l.open(1, "1.1.1.1"))
        assertTrue(l.open(2, "2.2.2.2"))
        val code = codeOf(l.handle(1, host()))
        assertEquals(6, code.length)
        assertTrue(code.all { it in RelayLobby.CODE_ALPHABET })
        val found = l.handle(2, LobbyWire.Request.Find(code.lowercase().chunked(3).joinToString("-")))!!
        assertEquals(LobbyWire.FOUND, found[0])
        assertEquals(invite, String(found, 2, found[1].toInt(), Charsets.UTF_8))
    }

    @Test
    fun hostingAgainKeepsTheCodeAndUpdatesTheRoom() {
        val l = lobby()
        l.open(1, "1.1.1.1")
        l.open(2, "2.2.2.2")
        val code = codeOf(l.handle(1, host()))
        assertTrue(roomsOf(l.handle(2, LobbyWire.Request.List(game))).isEmpty())
        assertEquals(code, codeOf(l.handle(1, host(listed = true, name = "  May   B "))))
        assertEquals(listOf(Triple(code, "May B", 0)), roomsOf(l.handle(2, LobbyWire.Request.List(game))))
        assertEquals(1, l.roomCount)
    }

    @Test
    fun theHostDoesNotFindOrListItsOwnRoom() {
        val l = lobby()
        l.open(1, "1.1.1.1")
        val code = codeOf(l.handle(1, host(listed = true)))
        assertError(l.handle(1, LobbyWire.Request.Find(code)), LobbyWire.FIND, LobbyWire.NOT_FOUND)
        assertTrue(roomsOf(l.handle(1, LobbyWire.Request.List(game))).isEmpty())
    }

    @Test
    fun listShowsOnlyListedRoomsOfThatGameNewestFirst() {
        val l = lobby()
        for (id in 1L..5L) l.open(id, "10.0.0.$id")
        val a = codeOf(l.handle(1, host(listed = true, name = "ANNA")))
        now += 3 * 60_000
        val b = codeOf(l.handle(2, host(listed = true, name = "BEN")))
        codeOf(l.handle(3, host(listed = false, name = "HIDDEN")))
        codeOf(l.handle(4, host(listed = true, name = "ELSE", g = other)))
        assertEquals(
            listOf(Triple(b, "BEN", 0), Triple(a, "ANNA", 3)),
            roomsOf(l.handle(5, LobbyWire.Request.List(game))),
        )
    }

    @Test
    fun listIsCapped() {
        val l = lobby(RelayConfig(lobbyMaxListed = 2, lobbyMaxRoomsPerIp = 10))
        for (id in 1L..4L) {
            l.open(id, "10.0.0.$id")
            l.handle(id, host(listed = true, name = "P$id"))
            now += 1000
        }
        l.open(9, "9.9.9.9")
        assertEquals(listOf("P4", "P3"), roomsOf(l.handle(9, LobbyWire.Request.List(game))).map { it.second })
    }

    @Test
    fun unhostAndClosingDropTheRoom() {
        val l = lobby()
        l.open(1, "1.1.1.1")
        l.open(2, "2.2.2.2")
        val code = codeOf(l.handle(1, host()))
        assertNull(l.handle(1, LobbyWire.Request.Unhost))
        assertError(l.handle(2, LobbyWire.Request.Find(code)), LobbyWire.FIND, LobbyWire.NOT_FOUND)
        val again = codeOf(l.handle(1, host()))
        l.close(1)
        assertError(l.handle(2, LobbyWire.Request.Find(again)), LobbyWire.FIND, LobbyWire.NOT_FOUND)
        assertEquals(0, l.roomCount)
        assertEquals(1, l.socketCount)
    }

    @Test
    fun badRequestsAreRefused() {
        val l = lobby()
        l.open(1, "1.1.1.1")
        assertError(l.handle(1, host(g = "F3AE088181BF583E")), LobbyWire.HOST, LobbyWire.BAD_REQUEST)
        assertError(l.handle(1, host(inv = "has space in it")), LobbyWire.HOST, LobbyWire.BAD_REQUEST)
        assertError(l.handle(1, host(inv = "short")), LobbyWire.HOST, LobbyWire.BAD_REQUEST)
        // A listed room needs a fit name; an unlisted one shows none.
        assertError(l.handle(1, host(listed = true, name = "")), LobbyWire.HOST, LobbyWire.BAD_REQUEST)
        assertError(l.handle(1, host(listed = true, name = "<script>")), LobbyWire.HOST, LobbyWire.BAD_REQUEST)
        assertError(l.handle(1, host(listed = true, name = "A".repeat(17))), LobbyWire.HOST, LobbyWire.BAD_REQUEST)
        codeOf(l.handle(1, host(listed = false, name = "")))
        assertError(l.handle(1, LobbyWire.Request.Find("K7Q4M")), LobbyWire.FIND, LobbyWire.BAD_REQUEST)
        assertError(l.handle(1, LobbyWire.Request.Find("K7Q4M0")), LobbyWire.FIND, LobbyWire.BAD_REQUEST)
        assertError(l.handle(1, LobbyWire.Request.List("nope")), LobbyWire.LIST, LobbyWire.BAD_REQUEST)
    }

    @Test
    fun namesKeepLettersOfAnyLanguage() {
        assertEquals("Émilie ♀", RelayLobby.cleanName(" Émilie  ♀ "))
        assertEquals("ユウキ", RelayLobby.cleanName("ユウキ"))
        assertNull(RelayLobby.cleanName("a\u0000b"))
        assertNull(RelayLobby.cleanName("http://x"))
    }

    @Test
    fun findsAreLimitedPerAddressRightOrWrong() {
        val l = lobby(RelayConfig(lobbyMaxFindsPerIpPerMin = 3))
        l.open(1, "1.1.1.1")
        l.open(2, "2.2.2.2")
        l.open(3, "2.2.2.2")
        val code = codeOf(l.handle(1, host()))
        repeat(2) { assertError(l.handle(2, LobbyWire.Request.Find("222222")), LobbyWire.FIND, LobbyWire.NOT_FOUND) }
        assertEquals(LobbyWire.FOUND, l.handle(3, LobbyWire.Request.Find(code))!![0])
        // A second socket from the same address shares the budget.
        assertError(l.handle(2, LobbyWire.Request.Find(code)), LobbyWire.FIND, LobbyWire.LIMIT)
        now += 60_000
        assertEquals(LobbyWire.FOUND, l.handle(3, LobbyWire.Request.Find(code))!![0])
    }

    @Test
    fun roomsAndSocketsAreLimitedPerAddress() {
        val l = lobby(RelayConfig(lobbyMaxRoomsPerIp = 2, lobbyMaxSocketsPerIp = 3, lobbyMaxNewSocketsPerIpPerMin = 100))
        for (id in 1L..3L) assertTrue(l.open(id, "1.1.1.1"))
        assertFalse(l.canAdmit("1.1.1.1"))
        assertFalse(l.open(4, "1.1.1.1"))
        assertTrue(l.canAdmit("2.2.2.2"))
        codeOf(l.handle(1, host()))
        codeOf(l.handle(2, host()))
        assertError(l.handle(3, host()), LobbyWire.HOST, LobbyWire.LIMIT)
        l.close(1)
        codeOf(l.handle(3, host()))
        assertTrue(l.open(4, "1.1.1.1"))
    }

    @Test
    fun theLobbyCanFillUp() {
        val l = lobby(RelayConfig(lobbyMaxRooms = 1))
        l.open(1, "1.1.1.1")
        l.open(2, "2.2.2.2")
        codeOf(l.handle(1, host()))
        assertError(l.handle(2, host()), LobbyWire.HOST, LobbyWire.FULL)
    }

    @Test
    fun staleRoomsGoAndIdleSocketsClose() {
        val l = lobby(RelayConfig(lobbyRoomTimeoutSec = 600, lobbyIdleTimeoutSec = 60))
        l.open(1, "1.1.1.1")
        l.open(2, "2.2.2.2")
        val code = codeOf(l.handle(1, host()))
        now += 59_000
        assertTrue(l.expired().isEmpty())
        now += 1_000
        assertEquals(listOf(2L), l.expired())   // the host's socket is busy with its room
        now += 540_000
        l.handle(1, host())                     // a refresh
        now += 599_000
        assertTrue(1L !in l.expired())
        now += 1_000
        l.expired()
        assertEquals(0, l.roomCount)
        l.open(3, "3.3.3.3")
        assertError(l.handle(3, LobbyWire.Request.Find(code)), LobbyWire.FIND, LobbyWire.NOT_FOUND)
        now += 60_000
        assertTrue(1L in l.expired())
    }

    @Test
    fun codesAreUnique() {
        val l = lobby(RelayConfig(lobbyMaxRoomsPerIp = 1000, lobbyMaxHostsPerIpPerMin = 1000, lobbyMaxSocketsPerIp = 1000, lobbyMaxNewSocketsPerIpPerMin = 1000))
        val codes = (1L..500L).map { l.open(it, "1.1.1.1"); codeOf(l.handle(it, host())) }
        assertEquals(500, codes.toSet().size)
        assertNotEquals(codes[0], codes[1])
    }

    @Test
    fun normalizeCode() {
        assertEquals("K7Q4MX", RelayLobby.normalizeCode("k7q-4mx"))
        assertEquals("K7Q4MX", RelayLobby.normalizeCode(" K7Q 4MX "))
        assertNull(RelayLobby.normalizeCode("K7Q4MO"))   // O isn't in the alphabet
        assertNull(RelayLobby.normalizeCode("K7Q4MXX"))
    }

    @Test
    fun wireDecodesRequestsAndRefusesJunk() {
        fun bytes(vararg parts: Any): ByteArray {
            val out = ByteArrayOutputStream()
            for (p in parts) when (p) {
                is Int -> out.write(p)
                is Byte -> out.write(p.toInt())
                is String -> { val raw = p.toByteArray(); out.write(raw.size); out.write(raw) }
            }
            return out.toByteArray()
        }
        assertEquals(host(listed = true), LobbyWire.decode(bytes(LobbyWire.HOST, 1, invite, game, "MAY")))
        assertEquals(LobbyWire.Request.Unhost, LobbyWire.decode(bytes(LobbyWire.UNHOST)))
        assertEquals(LobbyWire.Request.Find("K7Q4MX"), LobbyWire.decode(bytes(LobbyWire.FIND, "K7Q4MX")))
        assertEquals(LobbyWire.Request.List(game), LobbyWire.decode(bytes(LobbyWire.LIST, game)))
        assertNull(LobbyWire.decode(bytes(LobbyWire.HOST, 2, invite, game, "MAY")))   // unknown flag
        assertNull(LobbyWire.decode(bytes(LobbyWire.FIND, "K7Q4MX", 0)))             // bytes left over
        assertNull(LobbyWire.decode(bytes(LobbyWire.FIND) + byteArrayOf(9, 65)))     // a string past the end
        assertNull(LobbyWire.decode(bytes(LobbyWire.HOSTED, "K7Q4MX")))              // a server kind
        assertNull(LobbyWire.decode(ByteArray(0)))
    }
}
