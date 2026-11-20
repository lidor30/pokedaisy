package com.pokedaisy.app.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosRaClientTest {
    @Test
    fun nativeRequestAndLoginCallbackRoundTripWithoutNetwork() {
        var submitted = false
        var login: String? = null
        val client = IosRaClient
        assertTrue(client.initialize(object : RaClient.Callbacks {
            override fun serverCall(id: Int, url: String, postData: String?, contentType: String?) {
                submitted = true
                assertTrue(url.startsWith("https://"))
                assertTrue(assertNotNull(postData).contains("r=login2"))
                assertTrue(postData.contains("&u=Fixture"))
                assertNotNull(contentType)
                client.raQueueResponse(id, 200,
                    """{"Success":true,"User":"Fixture","Token":"FixtureToken","Score":7,"SoftcoreScore":2}""".encodeToByteArray())
            }

            override fun onLogin(result: Int, error: String?, user: String?, displayName: String?,
                                 token: String?, avatarUrl: String?, score: Int, softcoreScore: Int) {
                assertEquals(0, result)
                assertNull(error)
                assertEquals("FixtureToken", token)
                assertEquals(2, softcoreScore)
                login = user
            }

            override fun onGameLoaded(result: Int, error: String?, gameId: Int, title: String?, badgeUrl: String?,
                                      hash: String?, achievements: Int, unlocked: Int, points: Int, pointsUnlocked: Int) = Unit

            override fun onEvent(type: Int, id: Int, title: String?, description: String?, badgeUrl: String?,
                                 extra: String, points: Int) = Unit

            override fun onLog(message: String?) = Unit

            override fun onLeaderboardEntries(token: Int, aroundUser: Boolean, result: Int, error: String?,
                                              bytes: ByteArray?, total: Int) = Unit

            override fun onAllProgress(token: Int, result: Int, bytes: ByteArray?) = Unit
        }))
        assertTrue(client.raUserAgentClause().contains("rcheevos"))
        client.raLoginWithPassword("Fixture", "LocalOnly")
        assertTrue(submitted)
        assertNull(login)
        client.raPump()
        assertEquals("Fixture", login)
        assertNull(client.raSerializeProgress())
        client.raLogout()
    }
}
