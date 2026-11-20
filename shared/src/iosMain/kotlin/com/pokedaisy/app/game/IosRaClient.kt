@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.core.pkapi.*
import kotlinx.cinterop.*
import platform.posix.free

/** The C side owns one client for the life of the app. Kotlin copies each borrowed callback payload. */
object IosRaClient : RaClient {
    private var events: RaClient.Callbacks? = null

    override fun initialize(callbacks: RaClient.Callbacks): Boolean {
        if (pk_ra_ready()) return events === callbacks
        events = callbacks
        return memScoped {
            val handlers = alloc<pk_ra_callbacks>()
            handlers.request = staticCFunction {
                id: Int, url: CPointer<ByteVar>?, post: CPointer<ByteVar>?, type: CPointer<ByteVar>? ->
                IosRaClient.receiveRequest(id, url, post, type)
            }
            handlers.login = staticCFunction { result: Int, error: CPointer<ByteVar>?, user: CPointer<pk_ra_user>? ->
                IosRaClient.receiveLogin(result, error, user)
            }
            handlers.game_loaded = staticCFunction { result: Int, error: CPointer<ByteVar>?, game: CPointer<pk_ra_game>? ->
                IosRaClient.receiveGame(result, error, game)
            }
            handlers.event = staticCFunction { event: CPointer<pk_ra_event>? -> IosRaClient.receiveEvent(event) }
            handlers.log = staticCFunction { message: CPointer<ByteVar>? -> IosRaClient.receiveLog(message) }
            handlers.leaderboard = staticCFunction {
                token: Int, around: Boolean, result: Int, error: CPointer<ByteVar>?,
                data: CPointer<UByteVar>?, length: ULong, total: Int, _: Int ->
                IosRaClient.receiveLeaderboard(token, around, result, error, data, length, total)
            }
            handlers.all_progress = staticCFunction {
                token: Int, result: Int, data: CPointer<UByteVar>?, length: ULong ->
                IosRaClient.receiveProgress(token, result, data, length)
            }
            handlers.read_memory = null
            pk_ra_init(handlers.ptr)
        }.also { if (!it) events = null }
    }

    override fun raUserAgentClause(): String = memScoped {
        val out = allocArray<ByteVar>(64)
        pk_ra_user_agent(out, 64u)
        out.toKString()
    }

    override fun raQueueResponse(id: Int, status: Int, body: ByteArray?) {
        if (body == null || body.isEmpty()) pk_ra_queue_response(id, status, null, 0u)
        else body.usePinned { pk_ra_queue_response(id, status, it.addressOf(0).reinterpret(), body.size.convert()) }
    }

    override fun raPump() = pk_ra_pump()
    override fun raLoginWithPassword(user: String, password: String) = pk_ra_login_password(user, password)
    override fun raLoginWithToken(user: String, token: String) = pk_ra_login_token(user, token)
    override fun raLogout() = pk_ra_logout()
    override fun raLoadGame(hash: String?) = pk_ra_load_game(hash)
    override fun raUnloadGame() = pk_ra_unload_game()
    override fun raDoFrame() = pk_ra_frame()
    override fun raIdle() = pk_ra_idle()

    override fun raSerializeProgress() = allocated { pk_ra_serialize(it) }
    override fun raAchievementList() = allocated { pk_ra_achievement_list(it) }
    override fun raLeaderboardList() = allocated { pk_ra_leaderboard_list(it) }
    override fun raDeserializeProgress(data: ByteArray?) {
        if (data == null || data.isEmpty()) pk_ra_deserialize(null, 0u)
        else data.usePinned { pk_ra_deserialize(it.addressOf(0).reinterpret(), data.size.convert()) }
    }

    override fun raFetchLeaderboard(token: Int, id: Int, aroundUser: Boolean, first: Int, count: Int) =
        pk_ra_fetch_leaderboard(token, id.toUInt(), aroundUser, first.toUInt(), count.toUInt())

    override fun raFetchAllProgress(token: Int, console: Int) = pk_ra_fetch_all_progress(token, console.toUInt())

    override fun raDebugStatus(): String = memScoped {
        val out = allocArray<ByteVar>(2048)
        pk_ra_debug_status(out, 2048u)
        out.toKString()
    }

    private inline fun allocated(read: (CPointer<ULongVar>) -> CPointer<UByteVar>?): ByteArray? = memScoped {
        val length = alloc<ULongVar>()
        val data = read(length.ptr) ?: return@memScoped null
        try { data.bytes(length.value) } finally { free(data) }
    }

    private fun receiveRequest(id: Int, url: CPointer<ByteVar>?, post: CPointer<ByteVar>?, contentType: CPointer<ByteVar>?) {
        if (url == null) raQueueResponse(id, -2, null)
        else events?.serverCall(id, url.toKString(), post?.toKString(), contentType?.toKString())
    }

    private fun receiveLogin(result: Int, error: CPointer<ByteVar>?, native: CPointer<pk_ra_user>?) {
        val user = native?.pointed
        events?.onLogin(
            result, error?.toKString(), user?.username?.toKString(), user?.display_name?.toKString(),
            user?.token?.toKString(), user?.avatar_url?.toKString(),
            user?.score?.toInt() ?: 0, user?.softcore_score?.toInt() ?: 0,
        )
    }

    private fun receiveGame(result: Int, error: CPointer<ByteVar>?, native: CPointer<pk_ra_game>?) {
        val game = native?.pointed ?: return
        events?.onGameLoaded(
            result, error?.toKString(), game.id.toInt(), game.title?.toKString(),
            game.badge_url?.toKString(), game.hash?.toKString(), game.achievements.toInt(),
            game.unlocked.toInt(), game.points.toInt(), game.points_unlocked.toInt(),
        )
    }

    private fun receiveEvent(native: CPointer<pk_ra_event>?) {
        val event = native?.pointed ?: return
        events?.onEvent(
            event.type, event.id.toInt(), event.title?.toKString(), event.description?.toKString(),
            event.badge_url?.toKString(), event.extra?.toKString().orEmpty(), event.points.toInt(),
        )
    }

    private fun receiveLog(message: CPointer<ByteVar>?) { events?.onLog(message?.toKString()) }

    private fun receiveLeaderboard(
        token: Int, around: Boolean, result: Int, error: CPointer<ByteVar>?,
        data: CPointer<UByteVar>?, length: ULong, total: Int,
    ) {
        events?.onLeaderboardEntries(token, around, result, error?.toKString(), data.bytes(length), total)
    }

    private fun receiveProgress(token: Int, result: Int, data: CPointer<UByteVar>?, length: ULong) {
        events?.onAllProgress(token, result, data.bytes(length))
    }

    private fun CPointer<UByteVar>?.bytes(length: ULong): ByteArray? =
        this?.takeIf { length <= Int.MAX_VALUE.toULong() }?.reinterpret<ByteVar>()?.readBytes(length.toInt())
}
