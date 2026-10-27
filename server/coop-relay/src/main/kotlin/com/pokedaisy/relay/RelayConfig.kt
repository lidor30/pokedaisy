package com.pokedaisy.relay

import java.io.File

/** Where the client address comes from (TRUST_PROXY). */
enum class ProxyMode {
    /** The socket's address only. */
    NONE,

    /** X-Real-IP, else the last X-Forwarded-For entry, from a trusted proxy. */
    FORWARDED,

    /** As FORWARDED, then CF-Connecting-IP when that address is one of Cloudflare's. */
    CLOUDFLARE,
}

/** The relay's settings; every field has an environment variable (see [fromEnv]). */
data class RelayConfig(
    val port: Int = 8080,
    val healthPort: Int = 8081,
    val maxRooms: Int = 10_000,
    val maxSocketsPerIp: Int = 16,
    val maxNewSocketsPerIpPerMin: Int = 30,
    val maxMessageBytes: Int = 70_000,
    val maxMessagesPerSec: Int = 500,
    val maxBytesPerSec: Int = 524_288,
    val loneTimeoutSec: Int = 1800,
    val silentTimeoutSec: Int = 120,
    /** A socket with no pong for this long (x2 at worst) is dead: its room is freed for a rejoin. */
    val connectionLostTimeoutSec: Int = 15,
    /** A peer whose unsent outbound data is over this is a reader that stopped: closed 4429, its partner's message dropped. */
    val maxBufferedBytes: Int = 1_048_576,
    val proxyMode: ProxyMode = ProxyMode.NONE,
    /** Proxy headers are read only from a socket whose peer address is inside one of these CIDRs. */
    val trustedProxies: List<String> = DEFAULT_TRUSTED_PROXIES,
    /** Cloudflare's published ranges (CIDRs), for [ProxyMode.CLOUDFLARE]. */
    val cloudflareRanges: List<String> = emptyList(),
    // The lobby (`/v1/lobby`: short codes and the listed rooms, RelayLobby).
    val lobbyMaxRooms: Int = 10_000,
    val lobbyMaxSocketsPerIp: Int = 8,
    val lobbyMaxNewSocketsPerIpPerMin: Int = 30,
    val lobbyMaxRoomsPerIp: Int = 4,
    val lobbyMaxHostsPerIpPerMin: Int = 20,
    /** Code lookups, right or wrong: what keeps guessing codes slow. */
    val lobbyMaxFindsPerIpPerMin: Int = 10,
    val lobbyMaxListsPerIpPerMin: Int = 30,
    /** The most rooms one LIST answer carries (newest first). */
    val lobbyMaxListed: Int = 50,
    /** A room not refreshed by a HOST this long is dropped. */
    val lobbyRoomTimeoutSec: Int = 1800,
    /** A lobby socket with no room, silent this long, is closed. */
    val lobbyIdleTimeoutSec: Int = 120,
) {
    companion object {
        val DEFAULT_TRUSTED_PROXIES = listOf(
            "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7",
        )

        /** none / 0 / empty, forwarded / 1, cloudflare; anything else is NONE. */
        fun modeOf(text: String?): ProxyMode = when (text?.trim()?.lowercase()) {
            "forwarded", "1" -> ProxyMode.FORWARDED
            "cloudflare" -> ProxyMode.CLOUDFLARE
            else -> ProxyMode.NONE
        }

        fun fromEnv(env: Map<String, String> = System.getenv()): RelayConfig {
            val d = RelayConfig()
            fun int(name: String, default: Int) = env[name]?.trim()?.toIntOrNull() ?: default
            val mode = modeOf(env["TRUST_PROXY"])
            val ranges = if (mode == ProxyMode.CLOUDFLARE) {
                val path = env["CLOUDFLARE_RANGES_FILE"] ?: "cloudflare-ips.txt"
                runCatching { File(path).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") } }
                    .getOrElse {
                        System.err.println("relay: cannot read the Cloudflare ranges file $path; CF-Connecting-IP will be ignored")
                        emptyList()
                    }
            } else {
                emptyList()
            }
            return RelayConfig(
                port = int("PORT", d.port),
                healthPort = int("HEALTH_PORT", d.healthPort),
                maxRooms = int("MAX_ROOMS", d.maxRooms),
                maxSocketsPerIp = int("MAX_SOCKETS_PER_IP", d.maxSocketsPerIp),
                maxNewSocketsPerIpPerMin = int("MAX_NEW_SOCKETS_PER_IP_PER_MIN", d.maxNewSocketsPerIpPerMin),
                maxMessageBytes = int("MAX_MESSAGE_BYTES", d.maxMessageBytes),
                maxMessagesPerSec = int("MAX_MESSAGES_PER_SEC", d.maxMessagesPerSec),
                connectionLostTimeoutSec = int("CONNECTION_LOST_TIMEOUT_SEC", d.connectionLostTimeoutSec),
                maxBytesPerSec = int("MAX_BYTES_PER_SEC", d.maxBytesPerSec),
                loneTimeoutSec = int("LONE_TIMEOUT_SEC", d.loneTimeoutSec),
                silentTimeoutSec = int("SILENT_TIMEOUT_SEC", d.silentTimeoutSec),
                maxBufferedBytes = int("MAX_BUFFERED_BYTES", d.maxBufferedBytes),
                proxyMode = mode,
                trustedProxies = env["TRUSTED_PROXIES"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: d.trustedProxies,
                cloudflareRanges = ranges,
                lobbyMaxRooms = int("LOBBY_MAX_ROOMS", d.lobbyMaxRooms),
                lobbyMaxSocketsPerIp = int("LOBBY_MAX_SOCKETS_PER_IP", d.lobbyMaxSocketsPerIp),
                lobbyMaxNewSocketsPerIpPerMin = int("LOBBY_MAX_NEW_SOCKETS_PER_IP_PER_MIN", d.lobbyMaxNewSocketsPerIpPerMin),
                lobbyMaxRoomsPerIp = int("LOBBY_MAX_ROOMS_PER_IP", d.lobbyMaxRoomsPerIp),
                lobbyMaxHostsPerIpPerMin = int("LOBBY_MAX_HOSTS_PER_IP_PER_MIN", d.lobbyMaxHostsPerIpPerMin),
                lobbyMaxFindsPerIpPerMin = int("LOBBY_MAX_FINDS_PER_IP_PER_MIN", d.lobbyMaxFindsPerIpPerMin),
                lobbyMaxListsPerIpPerMin = int("LOBBY_MAX_LISTS_PER_IP_PER_MIN", d.lobbyMaxListsPerIpPerMin),
                lobbyMaxListed = int("LOBBY_MAX_LISTED", d.lobbyMaxListed),
                lobbyRoomTimeoutSec = int("LOBBY_ROOM_TIMEOUT_SEC", d.lobbyRoomTimeoutSec),
                lobbyIdleTimeoutSec = int("LOBBY_IDLE_TIMEOUT_SEC", d.lobbyIdleTimeoutSec),
            )
        }
    }
}
