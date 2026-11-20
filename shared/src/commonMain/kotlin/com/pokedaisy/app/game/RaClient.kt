package com.pokedaisy.app.game

/** Transport and events from one rcheevos client. Hosts choose the thread that pumps callbacks and reads the core. */
interface RaClient {
    interface Callbacks {
        fun serverCall(id: Int, url: String, postData: String?, contentType: String?)
        fun onLogin(
            result: Int, error: String?, user: String?, displayName: String?, token: String?,
            avatarUrl: String?, score: Int, softcoreScore: Int,
        )
        fun onGameLoaded(
            result: Int, error: String?, gameId: Int, title: String?, badgeUrl: String?, hash: String?,
            achievements: Int, unlocked: Int, points: Int, pointsUnlocked: Int,
        )
        fun onEvent(type: Int, id: Int, title: String?, description: String?, badgeUrl: String?, extra: String, points: Int)
        fun onLog(message: String?)
        fun onLeaderboardEntries(
            token: Int, aroundUser: Boolean, result: Int, error: String?, bytes: ByteArray?, total: Int,
        )
        fun onAllProgress(token: Int, result: Int, bytes: ByteArray?)
    }

    fun initialize(callbacks: Callbacks): Boolean
    fun raUserAgentClause(): String
    fun raQueueResponse(id: Int, status: Int, body: ByteArray?)
    fun raPump()
    fun raLoginWithPassword(user: String, password: String)
    fun raLoginWithToken(user: String, token: String)
    fun raLogout()
    fun raLoadGame(hash: String?): Boolean
    fun raUnloadGame()
    fun raDoFrame()
    fun raIdle()
    fun raSerializeProgress(): ByteArray?
    fun raDeserializeProgress(data: ByteArray?)
    fun raAchievementList(): ByteArray?
    fun raLeaderboardList(): ByteArray?
    fun raFetchLeaderboard(token: Int, id: Int, aroundUser: Boolean, first: Int, count: Int)
    fun raFetchAllProgress(token: Int, console: Int)
    fun raDebugStatus(): String
}
