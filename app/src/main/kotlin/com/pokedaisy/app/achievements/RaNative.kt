package com.pokedaisy.app.achievements

import com.pokedaisy.app.game.RaClient

/**
 * JNI surface of `pokedaisy_ra.c` (rcheevos' rc_client). [RetroAchievements] is
 * the only caller; the `@JvmStatic` functions are called back from native code.
 *
 * Threads: [raDoFrame], [raLoadGame], [raUnloadGame], [raSerializeProgress] and
 * [raDeserializeProgress] read the player's core, so they run on the emu thread
 * only. [raPump] hands queued server answers to rc_client - the emu thread while a
 * game runs, the HTTP thread otherwise (see [RaSession]).
 */
internal object RaNative : RaClient {
    private lateinit var events: RaClient.Callbacks

    override fun initialize(callbacks: RaClient.Callbacks): Boolean {
        System.loadLibrary("pokedaisy")
        events = callbacks
        return raInit()
    }

    private external fun raInit(): Boolean
    /** `rcheevos/12.5`, for the end of the User-Agent. */
    external override fun raUserAgentClause(): String
    external override fun raQueueResponse(id: Int, status: Int, body: ByteArray?)
    external override fun raPump()

    external override fun raLoginWithPassword(user: String, password: String)
    external override fun raLoginWithToken(user: String, token: String)
    external override fun raLogout()

    /** Loads the set of the game whose RetroAchievements hash is [hash] ([com.pokedaisy.app.RomIdentity.raHash]). */
    external override fun raLoadGame(hash: String?): Boolean
    external override fun raUnloadGame()
    external override fun raDoFrame()
    external override fun raIdle()
    external override fun raSerializeProgress(): ByteArray?
    external override fun raDeserializeProgress(data: ByteArray?)
    /** The loaded game's achievements, see [parseAchievementList]; null = no game / none. */
    external override fun raAchievementList(): ByteArray?
    /** The loaded game's leaderboards, see [parseLeaderboardList]; null = none. */
    external override fun raLeaderboardList(): ByteArray?
    /** Leaderboard [id]'s entries: [count] from rank [first], or around the player; answers in [onLeaderboardEntries]. */
    external override fun raFetchLeaderboard(token: Int, id: Int, aroundUser: Boolean, first: Int, count: Int)
    /** The player's unlock counts for every game of RA console [console] (5 = GBA); answers in [onAllProgress]. */
    external override fun raFetchAllProgress(token: Int, console: Int)
    /** Debug builds: one line on what rc_client sees (see the native side). */
    external override fun raDebugStatus(): String

    // --- called from native ---

    @JvmStatic fun serverCall(id: Int, url: String, postData: String?, contentType: String?) =
        events.serverCall(id, url, postData, contentType)

    @JvmStatic fun onLogin(
        result: Int, error: String?, user: String?, displayName: String?, token: String?,
        avatarUrl: String?, score: Int, softcoreScore: Int,
    ) = events.onLogin(result, error, user, displayName, token, avatarUrl, score, softcoreScore)

    @JvmStatic fun onGameLoaded(
        result: Int, error: String?, gameId: Int, title: String?, badgeUrl: String?, hash: String?,
        achievements: Int, unlocked: Int, points: Int, pointsUnlocked: Int,
    ) = events.onGameLoaded(result, error, gameId, title, badgeUrl, hash, achievements, unlocked, points, pointsUnlocked)

    @JvmStatic fun onEvent(
        type: Int, id: Int, title: String?, description: String?, badgeUrl: String?, extra: String, points: Int,
    ) = events.onEvent(type, id, title, description, badgeUrl, extra, points)

    @JvmStatic fun onLog(message: String?) = events.onLog(message)

    @JvmStatic fun onLeaderboardEntries(
        token: Int, aroundUser: Boolean, result: Int, error: String?, entries: ByteArray?, total: Int, userIndex: Int,
    ) = events.onLeaderboardEntries(token, aroundUser, result, error, entries, total)

    @JvmStatic fun onAllProgress(token: Int, result: Int, entries: ByteArray?) =
        events.onAllProgress(token, result, entries)
}
