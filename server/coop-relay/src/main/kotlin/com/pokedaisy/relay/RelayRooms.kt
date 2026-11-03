package com.pokedaisy.relay

import java.net.InetAddress

/**
 * The relay's state with no sockets in it: rooms of at most two peers, per-address counts and rates, a token
 * bucket per peer. Peers are plain ids the server hands out. Every call is synchronized; [clock] is in ms.
 */
class RelayRooms(private val config: RelayConfig, private val clock: () -> Long) {
    sealed interface Join {
        /** Joined; [other] is the peer already there (it and the newcomer are now a pair), else null. */
        data class Ok(val other: Long?) : Join
        data class Refused(val code: Int) : Join
    }

    private class Peer(val id: Long, val ip: String, val room: String, var bucketAt: Long, var msgs: Double, var bytes: Double)

    private class Room(val id: String) {
        val peers = ArrayList<Peer>(2)
        /** When the lone peer arrived, or the pair last spoke (or formed). */
        var since = 0L
    }

    private val rooms = HashMap<String, Room>()
    private val peers = HashMap<Long, Peer>()
    private val ipCounts = HashMap<String, Int>()
    private val ipOpens = HashMap<String, ArrayDeque<Long>>()

    val roomCount: Int @Synchronized get() = rooms.size
    val socketCount: Int @Synchronized get() = peers.size

    /** Whether [ip] may open another socket now (the handshake's check; [join] checks again). */
    @Synchronized
    fun canAdmit(ip: String): Boolean = admit(ip, clock())

    private fun admit(ip: String, now: Long): Boolean {
        if ((ipCounts[ip] ?: 0) >= config.maxSocketsPerIp) return false
        val opens = ipOpens[ip] ?: return true
        prune(ip, opens, now)
        return opens.size < config.maxNewSocketsPerIpPerMin
    }

    private fun prune(ip: String, opens: ArrayDeque<Long>, now: Long) {
        while (opens.isNotEmpty() && now - opens.first() >= 60_000) opens.removeFirst()
        if (opens.isEmpty()) ipOpens.remove(ip)
    }

    @Synchronized
    fun join(id: Long, ip: String, roomId: String): Join {
        val now = clock()
        if (!admit(ip, now)) return Join.Refused(4429)
        var room = rooms[roomId]
        if (room == null) {
            if (rooms.size >= config.maxRooms) return Join.Refused(4429)
            room = Room(roomId).also { rooms[roomId] = it }
        } else if (room.peers.size >= 2) {
            return Join.Refused(4409)
        }
        val peer = Peer(id, ip, roomId, now, config.maxMessagesPerSec.toDouble(), config.maxBytesPerSec.toDouble())
        val other = room.peers.firstOrNull()
        room.peers.add(peer)
        room.since = now
        peers[id] = peer
        ipCounts[ip] = (ipCounts[ip] ?: 0) + 1
        ipOpens.getOrPut(ip) { ArrayDeque() }.addLast(now)
        return Join.Ok(other?.id)
    }

    /** Removes a peer; returns the peer left behind in its room, if any (it is a lone peer again, from now). */
    @Synchronized
    fun leave(id: Long): Long? {
        val peer = peers.remove(id) ?: return null
        val left = (ipCounts[peer.ip] ?: 1) - 1
        if (left <= 0) ipCounts.remove(peer.ip) else ipCounts[peer.ip] = left
        val room = rooms[peer.room] ?: return null
        room.peers.remove(peer)
        if (room.peers.isEmpty()) {
            rooms.remove(peer.room)
            return null
        }
        room.since = clock()
        return room.peers[0].id
    }

    @Synchronized
    fun peerOf(id: Long): Long? {
        val peer = peers[id] ?: return null
        return rooms[peer.room]?.peers?.firstOrNull { it.id != id }?.id
    }

    /** Spends one message of [bytes] from the peer's buckets (refilled per second); false = over the rate. */
    @Synchronized
    fun allow(id: Long, bytes: Int): Boolean {
        val peer = peers[id] ?: return false
        val now = clock()
        val seconds = (now - peer.bucketAt).coerceAtLeast(0) / 1000.0
        peer.bucketAt = now
        peer.msgs = minOf(config.maxMessagesPerSec.toDouble(), peer.msgs + seconds * config.maxMessagesPerSec)
        peer.bytes = minOf(config.maxBytesPerSec.toDouble(), peer.bytes + seconds * config.maxBytesPerSec)
        if (peer.msgs < 1.0 || peer.bytes < bytes) return false
        peer.msgs -= 1.0
        peer.bytes -= bytes
        return true
    }

    /** A valid message from the peer: its pair is not silent. */
    @Synchronized
    fun touch(id: Long) {
        val peer = peers[id] ?: return
        val room = rooms[peer.room] ?: return
        if (room.peers.size == 2) room.since = clock()
    }

    /** Peers to close with 4408: a lone one waiting too long, both of a pair that has been silent too long. */
    @Synchronized
    fun expired(): List<Long> {
        val now = clock()
        for (ip in ipOpens.keys.toList()) prune(ip, ipOpens.getValue(ip), now)
        val out = ArrayList<Long>()
        for (room in rooms.values) {
            val limit = if (room.peers.size == 2) config.silentTimeoutSec else config.loneTimeoutSec
            if (now - room.since >= limit * 1000L) room.peers.forEach { out.add(it.id) }
        }
        return out
    }

    companion object {
        private val ROOM_ID = Regex("^[0-9a-f]{32}$")
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun isRoomId(s: String): Boolean = ROOM_ID.matches(s)

        /** IPv4 to its /24, IPv6 to its /48: all a log line may carry of an address. */
        fun maskIp(ip: String): String {
            if (IPV4.matches(ip)) {
                val parts = ip.split('.')
                return "${parts[0]}.${parts[1]}.${parts[2]}.0/24"
            }
            if (ip.contains(':') && ip.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                // A literal only: no name lookup happens.
                val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull()
                if (bytes != null && bytes.size == 16) {
                    val groups = (0 until 3).map {
                        (((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF)).toString(16)
                    }
                    return groups.joinToString(":") + "::/48"
                }
            }
            return "unknown"
        }
    }
}
