package com.pokedaisy.relay

import java.security.SecureRandom

/**
 * The lobby's state with no sockets in it: rooms behind short codes, the listed ones by game, and per-address limits.
 * A room belongs to the lobby socket that hosted it and goes with it (or with UNHOST, or after [RelayConfig.lobbyRoomTimeoutSec]
 * without a HOST refreshing it). The lobby only hands an invite from the host to whoever has the code; it never sees the
 * connection that follows. Sockets are plain ids the server hands out. Every call is synchronized; [clock] is in ms.
 */
class RelayLobby(
    private val config: RelayConfig,
    private val clock: () -> Long,
    private val random: java.util.Random = SecureRandom(),
) {
    private class Room(val code: String, val socket: Long, val ip: String) {
        var invite = ""
        var game = ""
        var name = ""
        var listed = false
        var createdAt = 0L
        var refreshedAt = 0L
    }

    private class Socket(val id: Long, val ip: String, var lastSeen: Long) {
        var room: Room? = null
    }

    private val sockets = HashMap<Long, Socket>()
    private val rooms = HashMap<String, Room>()
    private val ipSockets = HashMap<String, Int>()
    private val ipRooms = HashMap<String, Int>()
    /** Recent requests per (address, kind), for the per-minute limits. */
    private val recent = HashMap<String, ArrayDeque<Long>>()

    val roomCount: Int @Synchronized get() = rooms.size
    val socketCount: Int @Synchronized get() = sockets.size
    val listedCount: Int @Synchronized get() = rooms.values.count { it.listed }

    /** Whether [ip] may open another lobby socket now (the handshake's check; [open] checks again). */
    @Synchronized
    fun canAdmit(ip: String): Boolean = (ipSockets[ip] ?: 0) < config.lobbyMaxSocketsPerIp &&
        under(ip, "open", config.lobbyMaxNewSocketsPerIpPerMin, clock(), spend = false)

    @Synchronized
    fun open(id: Long, ip: String): Boolean {
        val now = clock()
        if ((ipSockets[ip] ?: 0) >= config.lobbyMaxSocketsPerIp) return false
        if (!under(ip, "open", config.lobbyMaxNewSocketsPerIpPerMin, now, spend = true)) return false
        sockets[id] = Socket(id, ip, now)
        ipSockets[ip] = (ipSockets[ip] ?: 0) + 1
        return true
    }

    @Synchronized
    fun close(id: Long) {
        val s = sockets.remove(id) ?: return
        dropRoom(s)
        decrement(ipSockets, s.ip)
    }

    /** The answer to one request from socket [id] (a [LobbyWire] message), or null for none (UNHOST). */
    @Synchronized
    fun handle(id: Long, request: LobbyWire.Request): ByteArray? {
        val s = sockets[id] ?: return null
        val now = clock()
        s.lastSeen = now
        return when (request) {
            is LobbyWire.Request.Host -> host(s, request, now)
            LobbyWire.Request.Unhost -> { dropRoom(s); null }
            is LobbyWire.Request.Find -> find(s, request.code, now)
            is LobbyWire.Request.List -> list(s, request.game, now)
        }
    }

    private fun host(s: Socket, r: LobbyWire.Request.Host, now: Long): ByteArray {
        val name = cleanName(r.name)
        if (!isInvite(r.invite) || !isGame(r.game) || (r.listed && name == null)) return LobbyWire.error(LobbyWire.HOST, LobbyWire.BAD_REQUEST)
        if (!under(s.ip, "host", config.lobbyMaxHostsPerIpPerMin, now, spend = true)) return LobbyWire.error(LobbyWire.HOST, LobbyWire.LIMIT)
        var room = s.room
        if (room == null) {
            if (rooms.size >= config.lobbyMaxRooms) return LobbyWire.error(LobbyWire.HOST, LobbyWire.FULL)
            if ((ipRooms[s.ip] ?: 0) >= config.lobbyMaxRoomsPerIp) return LobbyWire.error(LobbyWire.HOST, LobbyWire.LIMIT)
            room = Room(newCode(), s.id, s.ip).also { it.createdAt = now }
            rooms[room.code] = room
            ipRooms[s.ip] = (ipRooms[s.ip] ?: 0) + 1
            s.room = room
        }
        room.invite = r.invite
        room.game = r.game
        room.name = name.orEmpty()
        room.listed = r.listed
        room.refreshedAt = now
        return LobbyWire.hosted(room.code)
    }

    private fun find(s: Socket, text: String, now: Long): ByteArray {
        // A wrong code costs as much as a right one: guessing codes stays slow.
        if (!under(s.ip, "find", config.lobbyMaxFindsPerIpPerMin, now, spend = true)) return LobbyWire.error(LobbyWire.FIND, LobbyWire.LIMIT)
        val code = normalizeCode(text) ?: return LobbyWire.error(LobbyWire.FIND, LobbyWire.BAD_REQUEST)
        val room = rooms[code]?.takeIf { it.socket != s.id } ?: return LobbyWire.error(LobbyWire.FIND, LobbyWire.NOT_FOUND)
        return LobbyWire.found(room.invite)
    }

    private fun list(s: Socket, game: String, now: Long): ByteArray {
        if (!isGame(game)) return LobbyWire.error(LobbyWire.LIST, LobbyWire.BAD_REQUEST)
        if (!under(s.ip, "list", config.lobbyMaxListsPerIpPerMin, now, spend = true)) return LobbyWire.error(LobbyWire.LIST, LobbyWire.LIMIT)
        val out = rooms.values.asSequence()
            .filter { it.listed && it.game == game && it.socket != s.id }
            .sortedByDescending { it.createdAt }
            .take(config.lobbyMaxListed)
            .map { LobbyWire.Listing(it.code, it.name, ((now - it.createdAt) / 60_000).toInt()) }
            .toList()
        return LobbyWire.rooms(out)
    }

    /** Sockets to close: one idle too long with no room. Rooms not refreshed in time are dropped (their socket stays). */
    @Synchronized
    fun expired(): List<Long> {
        val now = clock()
        for (key in recent.keys.toList()) prune(key, recent.getValue(key), now)
        val out = ArrayList<Long>()
        for (s in sockets.values) {
            val room = s.room
            if (room != null && now - room.refreshedAt >= config.lobbyRoomTimeoutSec * 1000L) dropRoom(s)
            if (s.room == null && now - s.lastSeen >= config.lobbyIdleTimeoutSec * 1000L) out.add(s.id)
        }
        return out
    }

    private fun dropRoom(s: Socket) {
        val room = s.room ?: return
        s.room = null
        rooms.remove(room.code)
        decrement(ipRooms, room.ip)
    }

    private fun newCode(): String {
        while (true) {
            val code = String(CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })
            if (code !in rooms) return code
        }
    }

    /** Whether [ip] made fewer than [max] requests of [kind] in the last minute; [spend] counts this one. */
    private fun under(ip: String, kind: String, max: Int, now: Long, spend: Boolean): Boolean {
        val key = "$kind $ip"
        val q = recent[key]
        if (q != null) prune(key, q, now)
        val count = recent[key]?.size ?: 0
        if (count >= max) return false
        if (spend) recent.getOrPut(key) { ArrayDeque() }.addLast(now)
        return true
    }

    private fun prune(key: String, q: ArrayDeque<Long>, now: Long) {
        while (q.isNotEmpty() && now - q.first() >= 60_000) q.removeFirst()
        if (q.isEmpty()) recent.remove(key)
    }

    private fun decrement(map: HashMap<String, Int>, ip: String) {
        val left = (map[ip] ?: 1) - 1
        if (left <= 0) map.remove(ip) else map[ip] = left
    }

    companion object {
        /** No 0 / O, 1 / I / L, or U: nothing to misread, and fewer words spelled by chance. 30^6 = 729 million codes. */
        const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val CODE_LENGTH = 6

        private val GAME = Regex("^[0-9a-f]{16}$")
        /** Opaque to the relay: the app's invite ticket today. Printable ASCII, no spaces. */
        private val INVITE = Regex("^[\\x21-\\x7E]{8,255}$")
        private val NAME_CHARS = Regex("^[\\p{L}\\p{N} .'!?&♂♀-]+$")
        private const val MAX_NAME = 16

        fun isGame(s: String): Boolean = GAME.matches(s)
        fun isInvite(s: String): Boolean = INVITE.matches(s)

        /** A listed room's name: trimmed, 1 to 16 characters of letters, digits and a little punctuation; else null. */
        fun cleanName(s: String): String? {
            val t = s.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty() || t.codePointCount(0, t.length) > MAX_NAME || !NAME_CHARS.matches(t)) return null
            return t
        }

        /** What a player typed ("k7q 4mx", "K7Q-4MX") as the stored code, or null. */
        fun normalizeCode(s: String): String? {
            if (s.length > 32) return null
            val t = s.uppercase().filter { it != ' ' && it != '-' }
            if (t.length != CODE_LENGTH || t.any { it !in CODE_ALPHABET }) return null
            return t
        }
    }
}
