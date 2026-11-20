package com.pokedaisy.app.achievements

import android.content.Context
import android.os.Build
import android.util.Log
import com.pokedaisy.app.BuildConfig
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.CompanionAchievements
import com.pokedaisy.app.companion.data.StateFiles
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toOkioPath

@Volatile private var androidPrefs: Prefs? = null
private val androidRa = RaSession(
    RaNative, { androidPrefs }, CoroutineScope(SupervisorJob() + Dispatchers.Default), debug = BuildConfig.DEBUG,
    report = { Log.i("pokedaisy/ra", it) },
    allProgress = { token, result, bytes -> RetroAchievements.receiveAllProgress(token, result, bytes) },
)

/** Android's cartridge, savestate and library adapters for the shared RetroAchievements session. */
object RetroAchievements : CompanionAchievements by androidRa {
    private const val RC_OK = 0
    internal val account get() = androidRa
    val user get() = androidRa.user
    val signingIn get() = androidRa.signingIn
    val available get() = androidRa.available
    val accountSaved get() = androidRa.accountSaved
    var coopBattle: Boolean
        get() = androidRa.coopBattle
        set(value) { androidRa.coopBattle = value }
    var onUnlockSound: (() -> Unit)?
        get() = androidRa.onUnlockSound
        set(value) { androidRa.onUnlockSound = value }

    @Synchronized
    fun init(context: Context) {
        if (androidPrefs == null) androidPrefs = Prefs(context)
        androidRa.init("PokeDaisy/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
    }

    fun login(username: String, password: String, onResult: (Boolean, String?) -> Unit) =
        androidRa.login(username, password, onResult)
    fun logout() = androidRa.logout()
    internal fun previewSignedIn(user: RaUser) = androidRa.previewSignedIn(user)
    fun setCheatsActive(active: Boolean) = androidRa.setCheatsActive(active)
    fun setShinyOddsActive(active: Boolean) = androidRa.setShinyOddsActive(active)
    fun setExpShareActive(active: Boolean) = androidRa.setExpShareActive(active)
    fun setFlightAnywhere(active: Boolean) = androidRa.setFlightAnywhere(active)
    fun onFrame(work: com.pokedaisy.app.game.GameSession.FrameWork) = androidRa.onFrame(work)
    fun onCoreStopping() = androidRa.onCoreStopping()
    fun onCoreStopped() = androidRa.onCoreStopped()

    fun onCoreStarted(rom: File?, resumedFrom: File? = null) {
        val resume = resumedFrom?.let { state ->
            StateFiles.readAchievementProgress(state.toOkioPath()).also { progressFile(state).delete() }
        }
        androidRa.onCoreStarted({ rom?.let(::romHash) }, resume)
    }

    @Volatile private var hashed: Triple<String, Long, String?>? = null
    private fun romHash(rom: File): String? {
        val key = "${rom.absolutePath}|${rom.lastModified()}"
        hashed?.let { (k, size, h) -> if (k == key && size == rom.length()) return h }
        return com.pokedaisy.app.RomIdentity.raHash(rom).also { hashed = Triple(key, rom.length(), it) }
    }

    fun progressFile(state: File) = File(StateFiles.achievementProgressFile(state.toOkioPath()).toString())

    fun afterSaveState(state: File) {
        if (!androidRa.gameAttached) return
        androidRa.writeProgressForState(state.toOkioPath())
    }

    fun afterLoadState(state: File) {
        if (!androidRa.gameAttached) return
        androidRa.loadProgress(StateFiles.readAchievementProgress(state.toOkioPath()))
    }

    private val gameIds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** RetroAchievements' game id for a ROM's [md5] (0 = no set for it), or null if the server couldn't be asked. */
    fun lookupGameId(md5: String): Int? {
        gameIds[md5]?.let { return it }
        val id = dorequest("r=gameid&m=$md5")?.optInt("GameID", -1)?.takeIf { it >= 0 } ?: return null
        gameIds[md5] = id
        return id
    }

    private val boxArts = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /**
     * The game's box art on RetroAchievements (a square PNG on its public media host,
     * e.g. FireRed's 320x320 `001918.png`) for a ROM's [md5] - a library cover. Only
     * RA's Web API has it, so it takes the player's own Web API key ([webApiKey],
     * retroachievements.org > Settings). Null = no set / no box art for it, or no answer.
     */
    fun gameBoxArtUrl(md5: String, webApiKey: String): String? {
        val id = lookupGameId(md5)?.takeIf { it > 0 } ?: return null
        boxArts[id]?.let { return it }
        if (!available) return null
        val key = java.net.URLEncoder.encode(webApiKey.trim(), "UTF-8")
        val path = runCatching {
            val conn = (URL("https://retroachievements.org/API/API_GetGame.php?i=$id&y=$key").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", androidRa.userAgent)
            }
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            org.json.JSONObject(text).optString("ImageBoxArt")
        }.getOrNull() ?: return null
        // "/Images/001918.png"; RA's no-box-art placeholder is 000002.
        if (!path.startsWith("/Images/") || path.endsWith("/000002.png")) return null
        return "https://media.retroachievements.org$path".also { boxArts[id] = it }
    }

    /** One of RA's public (no sign-in) dorequest.php calls; null when the server couldn't be asked. */
    private fun dorequest(body: String): org.json.JSONObject? {
        if (!available) return null
        return runCatching {
            val conn = (URL("https://retroachievements.org/dorequest.php").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", androidRa.userAgent)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(body.toByteArray()) }
            }
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            org.json.JSONObject(text)
        }.getOrNull()
    }

    /** Unlock counts for one of the player's games: [total] achievements, [unlocked] softcore. */
    data class GameProgress(val total: Int, val unlocked: Int, val unlockedHardcore: Int)

    private val progressCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Map<Int, GameProgress>>>()
    private val progressWaiters = java.util.concurrent.ConcurrentHashMap<Int, (Map<Int, GameProgress>?) -> Unit>()
    private val progressTokens = java.util.concurrent.atomic.AtomicInteger()

    /** The signed-in player's progress in every game of [console] (an RA console id, [RomIdentity.raConsole]) they've
     * played, by game id (a minute's cache per console); null = signed out / offline. */
    fun progress(console: Int): Map<Int, GameProgress>? {
        if (!available || !accountSaved) return null
        progressCache[console]?.let { (at, map) -> if (System.currentTimeMillis() - at < 60_000) return map }
        // Just opened: the saved account's token login may still be on its way.
        val deadline = System.currentTimeMillis() + 10_000
        while (androidRa.user.value == null && !androidRa.state.value.offline && System.currentTimeMillis() < deadline) Thread.sleep(100)
        if (androidRa.user.value == null) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Map<Int, GameProgress>? = null
        val token = progressTokens.incrementAndGet()
        progressWaiters[token] = { out = it; latch.countDown() }
        androidRa.fetchAllProgress(token, console)
        latch.await(20, TimeUnit.SECONDS)
        progressWaiters.remove(token)
        out?.let { progressCache[console] = System.currentTimeMillis() to it }
        return out
    }

    internal fun receiveAllProgress(token: Int, result: Int, bytes: ByteArray?) {
        val map = if (result == RC_OK && bytes != null) {
            String(bytes, Charsets.UTF_8).split('\u001E').mapNotNull { rec ->
                val f = rec.split('\u001F').map { it.toIntOrNull() }
                if (f.size < 4 || f.any { it == null }) null
                else f[0]!! to GameProgress(f[1]!!, f[2]!!, f[3]!!)
            }.toMap()
        } else null
        progressWaiters.remove(token)?.invoke(map)
    }
}
