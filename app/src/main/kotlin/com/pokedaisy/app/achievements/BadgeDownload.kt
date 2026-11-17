package com.pokedaisy.app.achievements

import java.net.HttpURLConnection
import java.net.URL

/** An achievement badge's bytes from RetroAchievements' media server (companion.ui.AchievementBadges.download). */
fun downloadBadge(url: String): ByteArray? = runCatching {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000
        readTimeout = 15000
    }
    try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) null else conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}.getOrNull()
