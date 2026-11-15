package com.pokedaisy.app

import android.content.Context
import android.util.Log
import com.pokedaisy.app.companion.data.BestEffortReport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * SHARE on the BEST EFFORT notice (only when the player says yes): one record per match into the
 * `bestEffortReports` collection of PokéDaisy's Firebase project, through Firestore's public REST API
 * - no Firebase SDK, no account, no API key, no device or install id. The record is [BestEffortReport.shareFields], nothing
 * else: what the ROM is (its SHA-1, size, header code and revision) and what it matched as, so the
 * next release can support it outright. `website/firestore.rules` accepts exactly these fields and
 * lets nobody read them back. A record that can't go out now (offline) waits in [queueFile] and is
 * sent on the next start; one the server refuses is dropped.
 */
object BestEffortShare {
    private const val PROJECT = "pokedaisy"
    private const val COLLECTION = "bestEffortReports"
    private const val ENDPOINT = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents/$COLLECTION"

    /** The Firestore document [BestEffortReport.shareFields] become. Every value is checked first (hex, digits, A-Z ids), so nothing else fits in. */
    internal fun body(r: BestEffortReport, appVersion: String): String? {
        if (!r.sha1.matches(Regex("[0-9a-f]{40}")) || !r.gameCode.matches(Regex("[A-Z0-9]{4}")) ||
            !r.matchedAs.matches(Regex("[A-Z0-9_]{1,40}")) || !appVersion.matches(Regex("[0-9A-Za-z.\\-]{1,20}")) ||
            r.size !in 1..(64L shl 20) || r.revision !in 0..255
        ) return null
        val off = r.off.joinToString(",") { """{"stringValue":"${it.name}"}""" }
        return """{"fields":{""" +
            """"sha1":{"stringValue":"${r.sha1}"},""" +
            """"size":{"integerValue":"${r.size}"},""" +
            """"gameCode":{"stringValue":"${r.gameCode}"},""" +
            """"revision":{"integerValue":"${r.revision}"},""" +
            """"matchedAs":{"stringValue":"${r.matchedAs}"},""" +
            """"full":{"booleanValue":${r.full}},""" +
            """"off":{"arrayValue":{${if (off.isEmpty()) "" else """"values":[$off]"""}}},""" +
            """"appVersion":{"stringValue":"$appVersion"}""" +
            "}}"
    }

    private fun queueFile(context: Context) = File(context.filesDir, "best-effort-share-queue.jsonl")

    /** The player said yes: queue [r] and try to send it now (off the UI thread). */
    fun share(context: Context, r: BestEffortReport) {
        val json = body(r, BuildConfig.VERSION_NAME) ?: return
        synchronized(this) { queueFile(context).appendText(json + "\n") }
        flush(context)
    }

    /** Sends what's queued (each activity's start); keeps what couldn't go out for the next time. */
    fun flush(context: Context) {
        val app = context.applicationContext
        if (!queueFile(app).isFile) return
        Thread({
            synchronized(this) {
                val f = queueFile(app)
                val left = f.readLines().filter { it.isNotBlank() }.filter { json -> post(json) == null }
                if (left.isEmpty()) f.delete() else f.writeText(left.joinToString("") { "$it\n" })
            }
        }, "pokedaisy-best-effort-share").apply { isDaemon = true; start() }
    }

    /** null = done with it (sent, or refused for good); else the reason to keep it queued. */
    private fun post(json: String): String? = try {
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        when {
            code in 200..299 -> null
            code in 400..499 -> { Log.w("pokedaisy", "best effort share refused: HTTP $code"); null }
            else -> "HTTP $code"
        }
    } catch (e: Exception) {
        e.message ?: "offline"
    }
}
