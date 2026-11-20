package com.pokedaisy.app.achievements

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.AchievementGame
import com.pokedaisy.app.companion.AchievementPopup
import com.pokedaisy.app.companion.AchievementsState
import com.pokedaisy.app.companion.ChallengeIndicator
import com.pokedaisy.app.companion.CompanionAchievements
import com.pokedaisy.app.companion.LeaderboardPage
import com.pokedaisy.app.companion.LeaderboardTracker
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.game.GameSession.FrameWork
import com.pokedaisy.app.game.RaClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Path

/** Sign-in, game frames and progress belong to the client session on both mobile hosts. */
class RaSession(
    private val native: RaClient,
    private val prefs: () -> Prefs?,
    private val scope: CoroutineScope,
    private val debug: Boolean = false,
    private val report: (String) -> Unit = {},
    private val allProgress: (Int, Int, ByteArray?) -> Unit = { _, _, _ -> },
    private val fetch: suspend (String, String, String?, String?) -> RaResponse = ::requestAchievements,
) : CompanionAchievements, RaClient.Callbacks {
    private companion object {
        // RC_* (rc_error.h)
        private const val RC_OK = 0
        private const val RC_NO_GAME_LOADED = -29
        private const val RC_LOGIN_REQUIRED = -28
        private const val RC_ABORTED = -31
        private const val RC_ACCESS_DENIED = -33
        private const val RC_INVALID_CREDENTIALS = -34
        private const val RC_EXPIRED_TOKEN = -35
    }

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

    var userAgent = ""
        private set
    private val requestSlots = Semaphore(2)
    private val lock = SynchronizedObject()
    @Volatile private var ready = false

    /** A core is up on the emu thread: it, not the HTTP thread, delivers server answers. */
    @Volatile private var coreAttached = false
    /** Signed in while a game was already running: load it on the next frame. */
    @Volatile private var loadWanted = false
    @Volatile private var loadInFlight = false
    /** This game's set couldn't load for want of the server: the notice went up once (GitHub #925). */
    @Volatile private var offlineNoticeShown = false
    /** Signed in but the set's download failed (no connection): try it again later. */
    private var reload: Job? = null
    private var reloadDelaySec = 15L
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
    @Volatile private var expShareActive = false
    /** FLIGHT AREA is ANYWHERE and the player took off with it (companion.data.FlightArea): flying past the story could
     * reach a set's places early, so paused the same way until it's VISITED again. */
    @Volatile private var flightActive = false
    /** A co-op battle is running on this device (CoopBattles): the link battle is not the player's own game,
     * so achievements idle as they do with cheats (no popup: the battle is short). Any thread. */
    @Volatile var coopBattle = false

    /** Plays the game's fanfare (set by the game's activity); from the emu thread, on an unlock. */
    @Volatile var onUnlockSound: (() -> Unit)? = null

    /** Popups are on unless the player turned them off in Settings > RetroAchievements. */
    private fun notificationsOn() = prefs()?.raPopups ?: true

    /** [popup] to the hosts, unless the player turned that kind off (progress has its own switch). */
    private fun emitPopup(popup: AchievementPopup) {
        val on = notificationsOn()
        if (!popupAllowed(popup.kind, on, on && (prefs()?.raProgressPopups ?: true))) return
        _popups.tryEmit(popup)
    }

    private var loginCallback: ((ok: Boolean, error: String?) -> Unit)? = null
    private var retry: Job? = null
    private var retryDelaySec = 15L

    /** Idempotent; the first call signs the saved account back in. */
    fun init(userAgentPrefix: String) = synchronized(lock) {
        if (ready) return@synchronized
        val ok = runCatching { native.initialize(this) }.getOrElse {
            report("rc_client unavailable: ${it.message}")
            false
        }
        if (!ok) return@synchronized
        userAgent = "$userAgentPrefix ${native.raUserAgentClause()}"
        ready = true
        tokenLogin()
    }

    private fun tokenLogin() {
        val name = prefs()?.raUsername ?: return
        val token = prefs()?.raToken ?: return
        _state.update { it.copy(user = name) }
        native.raLoginWithToken(name, token)
    }

    /** [onResult] runs on an HTTP or emu thread. */
    fun login(username: String, password: String, onResult: (ok: Boolean, error: String?) -> Unit) {
        if (!ready) return onResult(false, tr("RetroAchievements isn't available on this device"))
        loginCallback = onResult
        _signingIn.value = true
        native.raLoginWithPassword(username.trim(), password)
    }

    fun logout() {
        if (!ready) return
        cancelRetry()
        native.raLogout()
        prefs()?.raUsername = null
        prefs()?.raToken = null
        _user.value = null
        _state.value = AchievementsState()
    }

    /** ui-preview only: show [user] as signed in, without a server. */
    fun previewSignedIn(user: RaUser) {
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
        val token = synchronized(lock) {
            pageHalvesLeft = 2
            ++pageToken
        }
        _page.value = LeaderboardPage(id)
        native.raFetchLeaderboard(token, id, aroundUser = false, first = 1, count = 10)
        native.raFetchLeaderboard(token, id, aroundUser = true, first = 0, count = 5)
    }

    override fun closeLeaderboard() {
        synchronized(lock) { pageToken++ }
        _page.value = null
    }

    override fun onLeaderboardEntries(token: Int, aroundUser: Boolean, result: Int, error: String?, bytes: ByteArray?, total: Int) {
        synchronized(lock) {
            if (token != pageToken) return
            pageHalvesLeft--
        }
        val me = _user.value?.username
        val entries = bytes?.let { parseLeaderboardEntries(it, me) }.orEmpty()
        if (debug) {
            report("leaderboard ${_page.value?.id} ${if (aroundUser) "around me" else "top"}: result=$result ${entries.size} of $total${error?.let { " ($it)" } ?: ""}")
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
    val accountSaved: Boolean get() = prefs()?.raToken != null

    override fun retry() {
        if (!ready) return
        // Signed in, but the game's set didn't download: fetch it now.
        if (_user.value != null) {
            if (coreAttached && !loadInFlight && _state.value.game == null) {
                cancelReload()
                loadWanted = true
            }
            return
        }
        cancelRetry()
        retryDelaySec = 15L
        tokenLogin()
    }

    private fun cancelRetry() = synchronized(lock) {
        retry?.cancel()
        retry = null
    }

    private fun cancelReload() = synchronized(lock) {
        reload?.cancel()
        reload = null
        reloadDelaySec = 15L
    }

    /** The set's download failed while signed in: load it again later, 15 s doubling to 5 min. */
    private fun scheduleReload() = synchronized(lock) {
        if (reload != null) return@synchronized
        val wait = reloadDelaySec
        reloadDelaySec = (reloadDelaySec * 2).coerceAtMost(300L)
        reload = scope.launch {
            delay(wait * 1000)
            synchronized(lock) { reload = null }
            if (coreAttached && !loadInFlight && _state.value.game == null) loadWanted = true
        }
    }

    private fun scheduleRetry() = synchronized(lock) {
        if (retry != null) return@synchronized
        val wait = retryDelaySec
        retryDelaySec = (retryDelaySec * 2).coerceAtMost(300L)
        retry = scope.launch {
            delay(wait * 1000)
            synchronized(lock) { retry = null }
            runCatching { tokenLogin() }.onFailure { report("token retry failed: ${it.message}") }
        }
    }

    // --- emu thread ---

    /** Resolve the original cartridge hash when needed, and restore any snapshot once its set loads. */
    fun onCoreStarted(hash: () -> String?, resume: ByteArray? = null) {
        romHash = hash
        resumeProgress = resume
        resumeProgressDue = false
        if (!ready) return
        coreAttached = true
        loadWanted = false
        offlineNoticeShown = false
        cancelReload()
        _state.update { it.copy(game = null, achievements = emptyList(), challenges = emptyList(), trackers = emptyList(), leaderboards = emptyList()) }
        // Offline since an earlier try: ask again now, so the load below waits for this answer rather than
        // failing at once (and the player hears about it if it's still down).
        if (prefs()?.raToken != null && _user.value == null && _state.value.offline) {
            cancelRetry()
            retryDelaySec = 15L
            tokenLogin()
        }
        // Signed out: nothing to load; signing in later sets loadWanted.
        // A token login still in flight is fine: rc_client holds the load until it answers.
        if (prefs()?.raToken != null) beginLoad()
    }

    @Volatile private var romHash: (() -> String?)? = null

    private fun beginLoad() {
        // The file's hash, never the ROM in the core (a resume writes the cart's GPIO registers into it: GitHub #197).
        loadInFlight = native.raLoadGame(romHash?.invoke())
        // Nothing the native side can hash (no core it knows): say so rather than LOOKING UP THIS ROM for ever.
        if (!loadInFlight) _state.update { it.copy(game = AchievementGame(0, null, null, 0, 0, 0, 0)) }
    }

    /** Only forward gameplay may advance achievement evaluation. */
    fun onFrame(work: FrameWork, updateSuppression: () -> Unit = {}) {
        if (work != FrameWork.ADVANCING) return
        updateSuppression()
        if (!ready || !coreAttached) return
        if (loadWanted) {
            loadWanted = false
            beginLoad()
        }
        native.raPump()
        if (resumeProgressDue) {
            resumeProgressDue = false
            resumeProgress?.let { native.raDeserializeProgress(it) }
            resumeProgress = null
        }
        if (cheatsActive || shinyOddsActive || flightActive || expShareActive || coopBattle) native.raIdle() else native.raDoFrame()
        // Debug builds: what rc_client sees, every ~10 s of emulated time.
        if (debug && ++debugFrames >= 600) {
            debugFrames = 0
            report(native.raDebugStatus())
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
        if ((_state.value.game?.total ?: 0) > 0) emitPopup(cheatsPopup(active))
    }

    fun setShinyOddsActive(active: Boolean) {
        if (active == shinyOddsActive) return
        shinyOddsActive = active
        _state.update { it.copy(shinyOddsPaused = active) }
        if (!ready || !coreAttached) return
        if ((_state.value.game?.total ?: 0) > 0) emitPopup(shinyOddsPopup(active))
    }

    fun setExpShareActive(active: Boolean) {
        if (active == expShareActive) return
        expShareActive = active
        _state.update { it.copy(expSharePaused = active) }
        if (!ready || !coreAttached) return
        if ((_state.value.game?.total ?: 0) > 0) emitPopup(expSharePopup(active))
    }

    private fun expSharePopup(active: Boolean) =
        if (active) AchievementPopup(AchievementPopup.Kind.NOTICE, tr("EXP SHARE") + ": " + tr("ON"), tr("Achievements are paused while EXP SHARE is on."))
        else AchievementPopup(AchievementPopup.Kind.NOTICE, tr("EXP SHARE") + ": " + tr("OFF"), tr("Achievements are back on."))

    fun setFlightAnywhere(active: Boolean) {
        if (active == flightActive) return
        flightActive = active
        _state.update { it.copy(flightPaused = active) }
        if (!ready || !coreAttached) return
        if ((_state.value.game?.total ?: 0) > 0) emitPopup(flightPopup(active))
    }

    private fun flightPopup(active: Boolean) =
        if (active) AchievementPopup(AchievementPopup.Kind.NOTICE, tr("FLIGHT AREA") + ": " + tr("ANYWHERE"), tr("Achievements are paused while FLIGHT AREA is ANYWHERE."))
        else AchievementPopup(AchievementPopup.Kind.NOTICE, tr("FLIGHT AREA") + ": " + tr("VISITED"), tr("Achievements are back on."))

    private fun shinyOddsPopup(active: Boolean) =
        if (active) AchievementPopup(AchievementPopup.Kind.NOTICE, tr("SHINY ODDS RAISED"), tr("Achievements are paused while SHINY ODDS is raised."))
        else AchievementPopup(AchievementPopup.Kind.NOTICE, tr("SHINY ODDS NORMAL"), tr("Achievements are back on."))

    private fun cheatsPopup(active: Boolean) =
        if (active) AchievementPopup(AchievementPopup.Kind.NOTICE, tr("CHEATS ON"), tr("Achievements are paused until every cheat is off."))
        else AchievementPopup(AchievementPopup.Kind.NOTICE, tr("CHEATS OFF"), tr("Achievements are back on."))

    fun onCoreStopping() {
        if (!ready || !coreAttached) return
        native.raUnloadGame()
        closeLeaderboard()
        loadInFlight = false
        _state.update { it.copy(game = null, achievements = emptyList(), challenges = emptyList(), trackers = emptyList(), leaderboards = emptyList()) }
    }

    /** After the core is gone: answers go back to being delivered from the HTTP thread. */
    fun onCoreStopped() {
        if (!ready) return
        coreAttached = false
        romHash = null
        resumeProgress = null
        resumeProgressDue = false
        cancelReload()
        native.raPump()
    }

    val gameAttached: Boolean get() = ready && coreAttached

    fun writeProgressForState(state: Path) {
        StateFiles.writeAchievementProgress(state) {
            if (gameAttached) native.raSerializeProgress() else null
        }
    }

    fun loadProgress(data: ByteArray?) {
        if (!gameAttached) return
        resumeProgressDue = false
        if (state.value.game == null) {
            // A save can be loaded while the server is still finding the game's achievement set.
            resumeProgress = data
        } else {
            resumeProgress = null
            native.raDeserializeProgress(data)
            listDirty = true
        }
    }

    private fun refreshList() {
        val bytes = native.raAchievementList() ?: return
        val list = parseAchievementList(bytes)
        val boards = native.raLeaderboardList()?.let { parseLeaderboardList(it) }.orEmpty()
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

    override fun serverCall(id: Int, url: String, postData: String?, contentType: String?) {
        scope.launch {
            val reply = try {
                requestSlots.withPermit { fetch(userAgent, url, postData, contentType) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                report("request failed: ${e.message}")
                RaResponse(-2, null) // RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR
            }
            runCatching { deliver(id, reply.status, reply.body) }
                .onFailure { report("delivering a response failed: ${it.message}") }
        }
    }

    private fun deliver(id: Int, status: Int, body: ByteArray?) {
        native.raQueueResponse(id, status, body)
        if (!coreAttached) native.raPump()
    }

    override fun onLogin(
        result: Int, error: String?, user: String?, displayName: String?, token: String?,
        avatarUrl: String?, score: Int, softcoreScore: Int,
    ) {
        val cb = loginCallback
        loginCallback = null
        _signingIn.value = false
        if (result == RC_OK && user != null) {
            cancelRetry()
            retryDelaySec = 15L
            prefs()?.raUsername = user
            if (token != null) prefs()?.raToken = token
            _user.value = RaUser(user, displayName ?: user, avatarUrl, score, softcoreScore)
            _state.update { it.copy(user = displayName ?: user, offline = false) }
            report("signed in as $user")
            if (coreAttached && !loadInFlight && _state.value.game == null) loadWanted = true
        } else {
            report("login failed ($result): $error")
            val rejected = result == RC_INVALID_CREDENTIALS || result == RC_EXPIRED_TOKEN || result == RC_ACCESS_DENIED
            when {
                cb != null -> Unit // a password sign-in: Settings shows the error
                // A dead token stays dead: sign out rather than retrying it every launch.
                rejected -> {
                    prefs()?.raToken = null
                    _state.value = AchievementsState()
                    emitPopup(AchievementPopup(AchievementPopup.Kind.NOTICE, tr("SIGNED OUT"), tr("Sign in to RetroAchievements again in Settings.")))
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

    override fun onGameLoaded(
        result: Int, error: String?, gameId: Int, title: String?, badgeUrl: String?, hash: String?,
        achievements: Int, unlocked: Int, points: Int, pointsUnlocked: Int,
    ) {
        loadInFlight = false
        when (result) {
            RC_OK -> {
                val game = AchievementGame(gameId, title, hash, achievements, unlocked, points, pointsUnlocked)
                cancelReload()
                _state.update { it.copy(game = game, offline = false) }
                resumeProgressDue = resumeProgress != null
                listDirty = true
                framesSinceList = 30
                report("game $gameId \"$title\": $unlocked/$achievements unlocked")
                if (achievements > 0) {
                    emitPopup(
                        AchievementPopup(
                            AchievementPopup.Kind.GAME_LOADED, title ?: "RetroAchievements",
                            tr("{0} OF {1} UNLOCKED · SOFTCORE", unlocked, achievements), badgeUrl,
                        ),
                    )
                    // The game started with cheats on: say why nothing will unlock.
                    if (cheatsActive) emitPopup(cheatsPopup(true))
                    if (shinyOddsActive) emitPopup(shinyOddsPopup(true))
                    if (flightActive) emitPopup(flightPopup(true))
                }
            }
            // An unknown hash: rc_client still keeps a placeholder game (id 0).
            RC_NO_GAME_LOADED -> {
                _state.update { it.copy(game = AchievementGame(0, null, hash, 0, 0, 0, 0)) }
                report("no achievement set for this ROM ($hash)")
            }
            RC_ABORTED -> Unit
            else -> {
                report("game load failed ($result): $error")
                // No server (GitHub #925: nothing said so, and a player played on for nothing): say it once a game,
                // then keep trying - a token login that comes back loads the game (onLogin), a set that didn't
                // download is fetched again.
                if (!coreAttached || prefs()?.raToken == null) return
                _state.update { it.copy(offline = true) }
                if (result != RC_LOGIN_REQUIRED) scheduleReload()
                if (!offlineNoticeShown) {
                    offlineNoticeShown = true
                    emitPopup(offlinePopup())
                }
            }
        }
    }

    override fun onEvent(type: Int, id: Int, title: String?, description: String?, badgeUrl: String?, extra: String, points: Int) {
        val e = RaEvent(type, id, title, description, badgeUrl, extra, points)
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
                report("unlocked ${e.id} \"${e.title}\" (${e.points} pts)")
                // Mastery comes with the last unlock, so this one fanfare covers it too.
                if (notificationsOn()) onUnlockSound?.invoke()
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
        popup?.let(::emitPopup)
    }

    override fun onLog(message: String?) { message?.let(report) }

    override fun onAllProgress(token: Int, result: Int, bytes: ByteArray?) = allProgress(token, result, bytes)

    fun fetchAllProgress(token: Int, console: Int) = native.raFetchAllProgress(token, console)
}

data class RaResponse(val status: Int, val body: ByteArray?)

private val raHttp by lazy {
    HttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 30_000
        }
    }
}

private suspend fun requestAchievements(agent: String, url: String, post: String?, type: String?): RaResponse {
    val response = raHttp.request(url) {
        header(HttpHeaders.UserAgent, agent)
        if (post != null) {
            method = HttpMethod.Post
            header(HttpHeaders.ContentType, type ?: "application/x-www-form-urlencoded")
            setBody(post.encodeToByteArray())
        }
    }
    return RaResponse(response.status.value, response.body<ByteArray>())
}
