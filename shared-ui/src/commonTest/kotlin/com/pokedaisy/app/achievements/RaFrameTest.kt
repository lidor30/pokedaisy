package com.pokedaisy.app.achievements

import com.pokedaisy.app.game.GameSession.FrameWork
import com.pokedaisy.app.game.RaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RaFrameTest {
    @Test fun onlyForwardFramesUpdateSuppressionAndRunTheClient() {
        val native = RecordingClient()
        val session = session(native)
        session.onFrame(FrameWork.HELD) { error("A held frame cannot update achievements") }
        session.onFrame(FrameWork.REWINDING) { error("Rewind cannot update achievements") }
        assertEquals(emptyList(), native.events)

        session.onFrame(FrameWork.ADVANCING) {
            native.events += "suppression"
            session.setCheatsActive(true)
        }
        session.onFrame(FrameWork.REWINDING)
        session.onFrame(FrameWork.ADVANCING) { session.setCheatsActive(false) }
        assertEquals(listOf("suppression", "pump", "idle", "pump", "evaluate"), native.events)
    }

    @Test fun allExistingGameplaySuppressionsStillPumpAndIdle() {
        val native = RecordingClient()
        val session = session(native)
        val suppressions = listOf(
            session::setCheatsActive, session::setShinyOddsActive, session::setFlightAnywhere, session::setExpShareActive,
            { active: Boolean -> session.coopBattle = active },
        )
        suppressions.forEach { set ->
            set(true)
            session.onFrame(FrameWork.ADVANCING)
            set(false)
            session.onFrame(FrameWork.ADVANCING)
        }
        assertEquals(List(suppressions.size) { listOf("pump", "idle", "pump", "evaluate") }.flatten(), native.events)
    }

    @Test fun aNativeFrameFailureStillReachesTheHostErrorHandler() {
        val native = RecordingClient()
        val session = session(native)
        native.failOnPump = true
        assertFailsWith<IllegalStateException> { session.onFrame(FrameWork.ADVANCING) }
        assertEquals(listOf("pump"), native.events)
        native.failOnPump = false
        session.onFrame(FrameWork.ADVANCING)
        assertEquals(listOf("pump", "pump", "evaluate"), native.events)
    }

    private fun session(native: RecordingClient) =
        RaSession(native, prefs = { null }, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            init("test")
            onCoreStarted({ null })
        }

    private class RecordingClient : RaClient {
        val events = mutableListOf<String>()
        var failOnPump = false

        override fun initialize(callbacks: RaClient.Callbacks) = true
        override fun raUserAgentClause() = ""
        override fun raQueueResponse(id: Int, status: Int, body: ByteArray?) {}
        override fun raPump() {
            events += "pump"
            check(!failOnPump)
        }
        override fun raLoginWithPassword(user: String, password: String) {}
        override fun raLoginWithToken(user: String, token: String) {}
        override fun raLogout() {}
        override fun raLoadGame(hash: String?) = false
        override fun raUnloadGame() {}
        override fun raDoFrame() { events += "evaluate" }
        override fun raIdle() { events += "idle" }
        override fun raSerializeProgress(): ByteArray? = null
        override fun raDeserializeProgress(data: ByteArray?) {}
        override fun raAchievementList(): ByteArray? = null
        override fun raLeaderboardList(): ByteArray? = null
        override fun raFetchLeaderboard(token: Int, id: Int, aroundUser: Boolean, first: Int, count: Int) {}
        override fun raFetchAllProgress(token: Int, console: Int) {}
        override fun raDebugStatus() = ""
    }
}
