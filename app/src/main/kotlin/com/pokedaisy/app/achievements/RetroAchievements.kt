package com.pokedaisy.app.achievements

import android.content.Context
import android.os.Build
import android.util.Log
import com.pokedaisy.app.BuildConfig
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.Achievement
import com.pokedaisy.app.companion.AchievementGame
import com.pokedaisy.app.companion.AchievementPopup
import com.pokedaisy.app.companion.AchievementsState
import com.pokedaisy.app.companion.ChallengeIndicator
import com.pokedaisy.app.companion.LeaderboardTracker
import com.pokedaisy.app.companion.CompanionAchievements
import com.pokedaisy.app.companion.Leaderboard
import com.pokedaisy.app.companion.LeaderboardEntry
import com.pokedaisy.app.companion.LeaderboardPage
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The signed-in RetroAchievements account. */
data class RaUser(
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val score: Int,
    val softcoreScore: Int,
)

/** One rc_client event; which fields are set depends on [type] (see the native side's raEvent). */
data class RaEvent(
    val type: Int,
    val id: Int,
    val title: String?,
    val description: String?,
    val badgeUrl: String?,
    /** Measured progress, a leaderboard tracker / submitted score, ... */
    val extra: String,
    /** Achievement points, or the new rank for [SCOREBOARD]. */
    val points: Int,
) {
    companion object {
        // RC_CLIENT_EVENT_* (rc_client.h)
        const val ACHIEVEMENT_TRIGGERED = 1
        const val LEADERBOARD_STARTED = 2
        const val LEADERBOARD_FAILED = 3
        const val LEADERBOARD_SUBMITTED = 4
        const val CHALLENGE_SHOW = 5
        const val CHALLENGE_HIDE = 6
        const val PROGRESS_SHOW = 7
        const val PROGRESS_UPDATE = 9
        const val TRACKER_SHOW = 10
        const val TRACKER_HIDE = 11
        const val TRACKER_UPDATE = 12
        const val SCOREBOARD = 13
        const val GAME_COMPLETED = 15
        const val SERVER_ERROR = 16
        const val DISCONNECTED = 17
        const val RECONNECTED = 18
    }
}

/**
 * RetroAchievements for the player's game, through rcheevos' rc_client
 * (`pokedaisy_ra.c`). Softcore only for now: hardcore needs PokeDaisy to be a
 * recognised client and to block state loads / the automated battle input.
 *
 * Signed in = on. The password is only ever passed to rc_client for the login
 * call; what's kept (in [Prefs]) is the username + the token the server returns.
 * A token login that can't reach the server is retried with backoff.
 *
 * [EmulatorEngine][com.pokedaisy.app.EmulatorEngine] drives it from the emu
 * thread: [onCoreStarted] after the core loads, [onFrame] after every emulated
 * frame, [onCoreStopping] / [onCoreStopped] around teardown, and the savestate
 * hooks. rc_client reads game memory while finishing a game load, so server
 * answers are queued natively and handed over by [onFrame] while a core runs.
 * The companion reads it as [CompanionAchievements].
 */
object RetroAchievements : CompanionAchievements {
    private const val TAG = "pokedaisy/ra"

    // RC_* (rc_error.h)
    private const val RC_OK = 0
    private const val RC_NO_GAME_LOADED = -29
    private const val RC_ABORTED = -31
    private const val RC_ACCESS_DENIED = -33
    private const val RC_INVALID_CREDENTIALS = -34
    private const val RC_EXPIRED_TOKEN = -35

    private val _user = MutableStateFlow<RaUser?>(null)
    /** Null while signed out (or the token login hasn't answered yet). */
    val user: StateFlow<RaUser?> = _user

    private val _state = MutableStateFlow(AchievementsState())
    override val state: StateFlow<AchievementsState> = _state

    private val _popups = MutableSharedFlow<AchievementPopup>(extraBufferCapacity = 16)
    override val popups: SharedFlow<AchievementPopup> = _popups

    /** True while a password sign-in is waiting for the server (Settings shows it). */
    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn

    private var prefs: Prefs? = null
    private var userAgent = ""
    private val http = Executors.newFixedThreadPool(2) { r -> Thread(r, "pokedaisy-ra-http").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pokedaisy-ra-retry").apply { isDaemon = true } }
    @Volatile private var ready = false

    /** A core is up on the emu thread: it, not the HTTP thread, delivers server answers. */
    @Volatile private var coreAttached = false
    /** Signed in while a game was already running: load it on the next frame. */
    @Volatile private var loadWanted = false
    @Volatile private var loadInFlight = false
    /** Rebuild [state]'s achievement list on the emu thread (it reads rc_client's game). */
    @Volatile private var listDirty = false
    private var framesSinceList = 0
    private var debugFrames = 0
    /** The resumed state's progress, put back on the first frame after the set loads. */
    private var resumeProgress: ByteArray? = null
    @Volatile private var resumeProgressDue = false

    /** A cheat is loaded into the game (EmulatorEngine.applyCheats): achievements pause -
     * rc_client_idle instead of rc_client_do_frame, so nothing is checked or unlocked -
     * until every cheat is off again, like other emulators do. Emu thread. */
    @Volatile private var cheatsActive = false

    /** SHINY ODDS is raised on a game it works in (ShinyOdds.kt): paused the same way, since sets reward catching a shiny. */
    @Volatile private var shinyOddsActive = false
    /** A co-op battle is running on this device (CoopBattles): the link battle is not the player's own game,
     * so achievements idle as they do with cheats (no popup: the battle is short). Any thread. */
    @Volatile var coopBattle = false

    /** Plays the game's fanfare (set by the game's activity); from the emu thread, on an unlock. */
    @Volatile var onUnlockSound: (() -> Unit)? = null

    private var loginCallback: ((ok: Boolean, error: String?) -> Unit)? = null
    private var retry: ScheduledFuture<*>? = null
    private var retryDelaySec = 15L

    /** Idempotent; the first call signs the saved account back in. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val p = Prefs(context) // keeps only the SharedPreferences, not the context
        prefs = p
        // Throwable: a missing native library is an Error, and RetroAchievements
        // is never worth taking the app down for.
        val ok = runCatching { RaNative.raInit() }.getOrElse { Log.e(TAG, "rc_client unavailable", it); false }
        if (!ok) return
        userAgent = "PokeDaisy/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}) ${RaNative.raUserAgentClause()}"
        ready = true
        tokenLogin()
    }

    private fun tokenLogin() {
        val name = prefs?.raUsername ?: return
        val token = prefs?.raToken ?: return
        _state.update { it.copy(user = name) }
        RaNative.raLoginWithToken(name, token)
    }

    /** [onResult] runs on an HTTP or emu thread. */
    fun login(username: String, password: String, onResult: (ok: Boolean, error: String?) -> Unit) {
        if (!ready) return onResult(false, tr("RetroAchievements isn't available on this device"))
        loginCallback = onResult
        _signingIn.value = true
        RaNative.raLoginWithPassword(username.trim(), password)
    }

    fun logout() {
        if (!ready) return
        cancelRetry()
        RaNative.raLogout()
        prefs?.raUsername = null
        prefs?.raToken = null
        _user.value = null
        _state.value = AchievementsState()
    }

    /** ui-preview only: show [user] as signed in, without a server. */
    internal fun previewSignedIn(user: RaUser) {
        _user.value = user
        _state.update { it.copy(user = user.displayName) }
    }

    private val _page = MutableStateFlow<LeaderboardPage?>(null)
    override val leaderboardPage: StateFlow<LeaderboardPage?> = _page
    /** Which open request answers belong to; bumped per open, so a late answer for another board is dropped. */
    @Volatile private var pageToken = 0
    private var pageHalvesLeft = 0

    override fun openLeaderboard(id: Int) {
        if (!ready) return
        val token = synchronized(this) {
            pageHalvesLeft = 2
            ++pageToken
        }
        _page.value = LeaderboardPage(id)
        RaNative.raFetchLeaderboard(token, id, aroundUser = false, first = 1, count = 10)
        RaNative.raFetchLeaderboard(token, id, aroundUser = true, first = 0, count = 5)
    }

    override fun closeLeaderboard() {
        synchronized(this) { pageToken++ }
        _page.value = null
    }

    internal fun onLeaderboardEntries(token: Int, aroundUser: Boolean, result: Int, error: String?, bytes: ByteArray?, total: Int) {
        synchronized(this) {
            if (token != pageToken) return
            pageHalvesLeft--
        }
        val me = _user.value?.username
        val entries = bytes?.let { parseLeaderboardEntries(it, me) }.orEmpty()
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "leaderboard ${_page.value?.id} ${if (aroundUser) "around me" else "top"}: result=$result ${entries.size} of $total${error?.let { " ($it)" } ?: ""}")
        }
        _page.update { p ->
            p ?: return@update null
            when {
                result != RC_OK -> p.copy(loading = pageHalvesLeft > 0, error = p.error ?: error ?: tr("Couldn't load the leaderboard"))
                aroundUser -> p.copy(loading = pageHalvesLeft > 0, nearMe = entries.takeIf { list -> list.any { it.isMe } }.orEmpty(), total = maxOf(p.total, total))
                else -> p.copy(loading = pageHalvesLeft > 0, top = entries, total = maxOf(p.total, total))
            }
        }
    }

    // --- the library's INFO (blocking, off the UI thread) ---

    /** False when rc_client couldn't start (no native library: previews). */
    val available: Boolean get() = ready

    /** An account is saved (its token login may still be under way). */
    val accountSaved: Boolean get() = prefs?.raToken != null

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
        if (!ready) return null
        val key = java.net.URLEncoder.encode(webApiKey.trim(), "UTF-8")
        val path = runCatching {
            val conn = (URL("https://retroachievements.org/API/API_GetGame.php?i=$id&y=$key").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", userAgent)
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
        if (!ready) return null
        return runCatching {
            val conn = (URL("https://retroachievements.org/dorequest.php").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", userAgent)
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
        if (!ready || !accountSaved) return null
        progressCache[console]?.let { (at, map) -> if (System.currentTimeMillis() - at < 60_000) return map }
        // Just opened: the saved account's token login may still be on its way.
        val deadline = System.currentTimeMillis() + 10_000
        while (_user.value == null && !_state.value.offline && System.currentTimeMillis() < deadline) Thread.sleep(100)
        if (_user.value == null) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Map<Int, GameProgress>? = null
        val token = progressTokens.incrementAndGet()
        progressWaiters[token] = { out = it; latch.countDown() }
        RaNative.raFetchAllProgress(token, console)
        latch.await(20, TimeUnit.SECONDS)
        progressWaiters.remove(token)
        out?.let { progressCache[console] = System.currentTimeMillis() to it }
        return out
    }

    internal fun onAllProgress(token: Int, result: Int, bytes: ByteArray?) {
        val map = if (result == RC_OK && bytes != null) {
            String(bytes, Charsets.UTF_8).split('\u001E').mapNotNull { rec ->
                val f = rec.split('\u001F').map { it.toIntOrNull() }
                if (f.size < 4 || f.any { it == null }) null
                else f[0]!! to GameProgress(f[1]!!, f[2]!!, f[3]!!)
            }.toMap()
        } else null
        progressWaiters.remove(token)?.invoke(map)
    }

    override fun retry() {
        if (!ready || _user.value != null) return
        cancelRetry()
        retryDelaySec = 15L
        tokenLogin()
    }

    @Synchronized
    private fun cancelRetry() {
        retry?.cancel(false)
        retry = null
    }

    @Synchronized
    private fun scheduleRetry() {
        if (retry != null) return
        val delay = retryDelaySec
        retryDelaySec = (retryDelaySec * 2).coerceAtMost(300L)
        retry = timer.schedule({
            synchronized(this) { retry = null }
            runCatching { tokenLogin() }.onFailure { Log.e(TAG, "token retry failed", it) }
        }, delay, TimeUnit.SECONDS)
    }

    // --- emu thread ---

    /** [rom]: the file the core loaded (its hash names the game). [resumedFrom]: the suspend state the core just
     * loaded, if any (its progress file is used up here). */
    fun onCoreStarted(rom: File?, resumedFrom: File? = null) {
        romFile = rom
        resumeProgress = resumedFrom?.let { progressFile(it) }?.let { f ->
            runCatching { f.readBytes() }.getOrNull().also { f.delete() }
        }
        resumeProgressDue = false
        if (!ready) return
        coreAttached = true
        loadWanted = false
        _state.update { it.copy(game = null, achievements = emptyList(), challenges = emptyList(), trackers = emptyList()) }
        // Signed out: nothing to load; signing in later sets loadWanted.
        // A token login still in flight is fine: rc_client holds the load until it answers.
        if (prefs?.raToken != null) beginLoad()
    }

    /** The game the core runs, for [beginLoad]'s hash. */
    @Volatile private var romFile: File? = null
    /** [romHash]'s last answer: a file is hashed once, not again on every resume or sign-in. */
    @Volatile private var hashed: Triple<String, Long, String?>? = null

    /** RetroAchievements' hash of [rom]'s file, cached by path, size and time. */
    private fun romHash(rom: File): String? {
        val key = "${rom.absolutePath}|${rom.lastModified()}"
        hashed?.let { (k, size, h) -> if (k == key && size == rom.length()) return h }
        return com.pokedaisy.app.RomIdentity.raHash(rom).also { hashed = Triple(key, rom.length(), it) }
    }

    private fun beginLoad() {
        // The file's hash, never the ROM in the core (a resume writes the cart's GPIO registers into it: GitHub #197).
        loadInFlight = RaNative.raLoadGame(romFile?.let(::romHash))
        // Nothing the native side can hash (no core it knows): say so rather than LOOKING UP THIS ROM for ever.
        if (!loadInFlight) _state.update { it.copy(game = AchievementGame(0, null, null, 0, 0, 0, 0)) }
    }

    fun onFrame() {
        if (!ready || !coreAttached) return
        if (loadWanted) {
            loadWanted = false
            beginLoad()
        }
        RaNative.raPump()
        if (resumeProgressDue) {
            resumeProgressDue = false
            resumeProgress?.let { RaNative.raDeserializeProgress(it) }
            resumeProgress = null
        }
        if (cheatsActive || shinyOddsActive || coopBattle) RaNative.raIdle() else RaNative.raDoFrame()
        // Debug builds: what rc_client sees, every ~10 s of emulated time.
        if (BuildConfig.DEBUG && ++debugFrames >= 600) {
            debugFrames = 0
            Log.i(TAG, RaNative.raDebugStatus())
        }
        if (listDirty && ++framesSinceList >= 30) {
            listDirty = false
            framesSinceList = 0
            refreshList()
        }
    }

    /** Cheats went in or out of the game (emu thread): pauses / resumes, and says so
     * when the game has a set. */
    fun setCheatsActive(active: Boolean) {
        if (active == cheatsActive) return
        cheatsActive = active
        _state.update { it.copy(cheatsPaused = active) }
        if (!ready || !coreAttached) return
        if ((_state.value.game?.total ?: 0) > 0) _popups.tryEmit(cheatsPopup(active))
    }

    fun setShinyOddsActive(active: Boolean) {
        if (active == shinyOddsActive) return
        shinyOddsActive = active
        _state.update { it.copy(shinyOddsPaused = active) }
        if (!ready || !coreAttached) return
        if ((_state.value.game?.total ?: 0) > 0) _popups.tryEmit(shinyOddsPopup(active))
    }

    private fun shinyOddsPopup(active: Boolean) =
        if (active) AchievementPopup(AchievementPopup.Kind.NOTICE, tr("SHINY ODDS RAISED"), tr("Achievements are paused while SHINY ODDS is raised."))
        else AchievementPopup(AchievementPopup.Kind.NOTICE, tr("SHINY ODDS NORMAL"), tr("Achievements are back on."))

    private fun cheatsPopup(active: Boolean) =
        if (active) AchievementPopup(AchievementPopup.Kind.NOTICE, tr("CHEATS ON"), tr("Achievements are paused until every cheat is off."))
        else AchievementPopup(AchievementPopup.Kind.NOTICE, tr("CHEATS OFF"), tr("Achievements are back on."))

    fun onCoreStopping() {
        if (!ready || !coreAttached) return
        RaNative.raUnloadGame()
        loadInFlight = false
        _state.update { it.copy(game = null, achievements = emptyList(), challenges = emptyList(), trackers = emptyList()) }
    }

    /** After the core is gone: answers go back to being delivered from the HTTP thread. */
    fun onCoreStopped() {
        if (!ready) return
        coreAttached = false
        RaNative.raPump()
    }

    /** Where a savestate's achievement progress is kept. */
    fun progressFile(state: File) = File(state.path + ".ra")

    fun afterSaveState(state: File) {
        if (!ready || !coreAttached) return
        val f = progressFile(state)
        val data = RaNative.raSerializeProgress()
        runCatching { if (data != null) f.writeBytes(data) else f.delete() }
    }

    fun afterLoadState(state: File) {
        if (!ready || !coreAttached) return
        val f = progressFile(state)
        RaNative.raDeserializeProgress(if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null)
        listDirty = true
    }

    private fun refreshList() {
        val bytes = RaNative.raAchievementList() ?: return
        val list = parseAchievementList(bytes)
        val boards = RaNative.raLeaderboardList()?.let { parseLeaderboardList(it) }.orEmpty()
        _state.update { s ->
            s.copy(
                achievements = list,
                leaderboards = boards,
                game = s.game?.copy(
                    unlocked = list.count { it.unlocked },
                    pointsUnlocked = list.filter { it.unlocked }.sumOf { it.points },
                ),
            )
        }
    }

    // --- from native ---

    internal fun serverCall(id: Int, url: String, postData: String?, contentType: String?) {
        http.execute {
            var status: Int
            var body: ByteArray? = null
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", userAgent)
                    if (postData != null) {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
                        outputStream.use { it.write(postData.toByteArray()) }
                    }
                }
                status = conn.responseCode
                body = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
                conn.disconnect()
            } catch (e: Exception) {
                // Not just IOException: anything thrown on this pool thread kills the
                // process, and an undelivered id leaks rc_client's request slot.
                Log.w(TAG, "request failed: ${e.message}")
                status = -2 // RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR
                body = null
            }
            try {
                deliver(id, status, body)
            } catch (t: Exception) {
                Log.e(TAG, "delivering a response failed", t)
            }
        }
    }

    private fun deliver(id: Int, status: Int, body: ByteArray?) {
        RaNative.raQueueResponse(id, status, body)
        if (!coreAttached) RaNative.raPump()
    }

    internal fun onLogin(
        result: Int, error: String?, user: String?, displayName: String?, token: String?,
        avatarUrl: String?, score: Int, softcoreScore: Int,
    ) {
        val cb = loginCallback
        loginCallback = null
        _signingIn.value = false
        if (result == RC_OK && user != null) {
            cancelRetry()
            retryDelaySec = 15L
            prefs?.raUsername = user
            if (token != null) prefs?.raToken = token
            _user.value = RaUser(user, displayName ?: user, avatarUrl, score, softcoreScore)
            _state.update { it.copy(user = displayName ?: user, offline = false) }
            Log.i(TAG, "signed in as $user")
            if (coreAttached && !loadInFlight && _state.value.game == null) loadWanted = true
        } else {
            Log.w(TAG, "login failed ($result): $error")
            val rejected = result == RC_INVALID_CREDENTIALS || result == RC_EXPIRED_TOKEN || result == RC_ACCESS_DENIED
            when {
                cb != null -> Unit // a password sign-in: Settings shows the error
                // A dead token stays dead: sign out rather than retrying it every launch.
                rejected -> {
                    prefs?.raToken = null
                    _state.value = AchievementsState()
                    _popups.tryEmit(AchievementPopup(AchievementPopup.Kind.NOTICE, tr("SIGNED OUT"), tr("Sign in to RetroAchievements again in Settings.")))
                }
                // Couldn't reach the server: keep the account, try again later.
                else -> {
                    _state.update { it.copy(offline = true) }
                    scheduleRetry()
                }
            }
        }
        cb?.invoke(result == RC_OK, error)
    }

    internal fun onGameLoaded(
        result: Int, error: String?, gameId: Int, title: String?, badgeUrl: String?, hash: String?,
        achievements: Int, unlocked: Int, points: Int, pointsUnlocked: Int,
    ) {
        loadInFlight = false
        when (result) {
            RC_OK -> {
                val game = AchievementGame(gameId, title, hash, achievements, unlocked, points, pointsUnlocked)
                _state.update { it.copy(game = game) }
                resumeProgressDue = resumeProgress != null
                listDirty = true
                framesSinceList = 30
                Log.i(TAG, "game $gameId \"$title\": $unlocked/$achievements unlocked")
                if (achievements > 0) {
                    _popups.tryEmit(
                        AchievementPopup(
                            AchievementPopup.Kind.GAME_LOADED, title ?: "RetroAchievements",
                            tr("{0} OF {1} UNLOCKED · SOFTCORE", unlocked, achievements), badgeUrl,
                        ),
                    )
                    // The game started with cheats on: say why nothing will unlock.
                    if (cheatsActive) _popups.tryEmit(cheatsPopup(true))
                    if (shinyOddsActive) _popups.tryEmit(shinyOddsPopup(true))
                }
            }
            // An unknown hash: rc_client still keeps a placeholder game (id 0).
            RC_NO_GAME_LOADED -> {
                _state.update { it.copy(game = AchievementGame(0, null, hash, 0, 0, 0, 0)) }
                Log.i(TAG, "no achievement set for this ROM ($hash)")
            }
            RC_ABORTED -> Unit
            else -> Log.w(TAG, "game load failed ($result): $error")
        }
    }

    internal fun onEvent(e: RaEvent) {
        when (e.type) {
            RaEvent.CHALLENGE_SHOW -> _state.update { s ->
                s.copy(challenges = s.challenges.filter { it.id != e.id } + ChallengeIndicator(e.id, e.title.orEmpty(), e.badgeUrl))
            }
            RaEvent.CHALLENGE_HIDE -> _state.update { s -> s.copy(challenges = s.challenges.filter { it.id != e.id }) }
            RaEvent.TRACKER_SHOW -> _state.update { s ->
                s.copy(trackers = s.trackers.filter { it.id != e.id } + LeaderboardTracker(e.id, e.extra))
            }
            RaEvent.TRACKER_UPDATE -> _state.update { s ->
                s.copy(trackers = s.trackers.map { if (it.id == e.id) it.copy(display = e.extra) else it })
            }
            RaEvent.TRACKER_HIDE -> _state.update { s -> s.copy(trackers = s.trackers.filter { it.id != e.id }) }
        }
        val popup = when (e.type) {
            RaEvent.ACHIEVEMENT_TRIGGERED -> {
                Log.i(TAG, "unlocked ${e.id} \"${e.title}\" (${e.points} pts)")
                // Mastery comes with the last unlock, so this one fanfare covers it too.
                onUnlockSound?.invoke()
                listDirty = true
                framesSinceList = 30
                AchievementPopup(AchievementPopup.Kind.UNLOCKED, e.title.orEmpty(), e.description.orEmpty(), e.badgeUrl, e.points, achievementId = e.id)
            }
            RaEvent.GAME_COMPLETED ->
                AchievementPopup(AchievementPopup.Kind.MASTERED, e.title ?: tr("GAME COMPLETE"), tr("EVERY ACHIEVEMENT UNLOCKED"), e.badgeUrl)
            RaEvent.PROGRESS_SHOW, RaEvent.PROGRESS_UPDATE -> {
                listDirty = true
                AchievementPopup(AchievementPopup.Kind.PROGRESS, e.title.orEmpty(), e.extra, e.badgeUrl, achievementId = e.id)
            }
            RaEvent.LEADERBOARD_STARTED -> AchievementPopup(AchievementPopup.Kind.LEADERBOARD, e.title.orEmpty(), tr("LEADERBOARD ATTEMPT STARTED"))
            RaEvent.LEADERBOARD_FAILED -> AchievementPopup(AchievementPopup.Kind.LEADERBOARD, e.title.orEmpty(), tr("LEADERBOARD ATTEMPT FAILED"))
            RaEvent.LEADERBOARD_SUBMITTED ->
                AchievementPopup(AchievementPopup.Kind.LEADERBOARD, e.title.orEmpty(), tr("SUBMITTED {0}", e.extra).trim())
            RaEvent.SCOREBOARD -> AchievementPopup(AchievementPopup.Kind.LEADERBOARD, tr("LEADERBOARD"), tr("{0} · RANK {1}", e.extra, e.points))
            RaEvent.DISCONNECTED ->
                AchievementPopup(AchievementPopup.Kind.NOTICE, tr("OFFLINE"), tr("Unlocks are kept and sent once RetroAchievements can be reached."))
            RaEvent.RECONNECTED -> AchievementPopup(AchievementPopup.Kind.NOTICE, tr("BACK ONLINE"), tr("Waiting unlocks were sent."))
            RaEvent.SERVER_ERROR -> AchievementPopup(AchievementPopup.Kind.NOTICE, tr("SERVER ERROR"), e.description.orEmpty())
            else -> null
        }
        popup?.let { _popups.tryEmit(it) }
    }

    internal fun onLog(message: String?) {
        if (message != null) Log.i(TAG, message)
    }
}

/** [RaNative.raAchievementList]'s records (0x1E) of fields (0x1F) -> [Achievement]s. */
internal fun parseAchievementList(bytes: ByteArray): List<Achievement> =
    String(bytes, Charsets.UTF_8).split('\u001E').mapNotNull { rec ->
        val f = rec.split('\u001F')
        if (f.size < 13) return@mapNotNull null
        Achievement(
            id = f[2].toIntOrNull() ?: return@mapNotNull null,
            title = f[3],
            description = f[4],
            points = f[5].toIntOrNull() ?: 0,
            unlocked = (f[6].toIntOrNull() ?: 0) != 0,
            section = f[1].uppercase(),
            badgeUrl = f[7].ifEmpty { null },
            lockedBadgeUrl = f[8].ifEmpty { null },
            progress = f[9],
            percent = f[10].toFloatOrNull() ?: 0f,
            rarity = f[11].toFloatOrNull() ?: 0f,
        )
    }

/** [RaNative.raLeaderboardList]'s records -> [Leaderboard]s. */
internal fun parseLeaderboardList(bytes: ByteArray): List<Leaderboard> =
    String(bytes, Charsets.UTF_8).split('\u001E').mapNotNull { rec ->
        val f = rec.split('\u001F')
        if (f.size < 5) return@mapNotNull null
        Leaderboard(
            id = f[1].toIntOrNull() ?: return@mapNotNull null,
            title = f[2],
            description = f[3],
            section = f[0].uppercase(),
            lowerIsBetter = f[4] == "1",
        )
    }

/** A leaderboard fetch's records (rank, user, score) -> entries, [me]'s marked. */
internal fun parseLeaderboardEntries(bytes: ByteArray, me: String?): List<LeaderboardEntry> =
    String(bytes, Charsets.UTF_8).split('\u001E').mapNotNull { rec ->
        val f = rec.split('\u001F')
        if (f.size < 3) return@mapNotNull null
        LeaderboardEntry(
            rank = f[0].toIntOrNull() ?: return@mapNotNull null,
            user = f[1],
            score = f[2],
            isMe = me != null && f[1].equals(me, ignoreCase = true),
        )
    }
