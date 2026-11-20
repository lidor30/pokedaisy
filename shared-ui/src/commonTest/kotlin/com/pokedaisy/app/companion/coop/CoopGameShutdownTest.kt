package com.pokedaisy.app.companion.coop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoopGameShutdownTest {
    private class Game {
        val actions = mutableListOf<String>()
        var battleRestores = true
        val shutdown = CoopGameShutdown(
            restoreBattle = { actions += "restore"; battleRestores },
            resumeBattles = { actions += "resume" },
            stopEndpoint = { actions += "stop endpoint" },
            detachGame = { actions += "detach" },
        )

        fun save(succeeds: Boolean = true): Boolean = shutdown.saveIfRestored {
            actions += "save"
            succeeds
        }
    }

    @Test fun suspendKeepsTheSessionAttachedUntilTermination() {
        val game = Game()
        assertTrue(game.save())
        assertFalse(game.shutdown.detached)
        assertEquals(listOf("restore", "save"), game.actions)

        assertTrue(game.shutdown.terminate())
        assertTrue(game.shutdown.terminate())
        assertTrue(game.shutdown.detached)
        assertEquals(listOf("restore", "save", "stop endpoint", "detach"), game.actions)
    }

    @Test fun unconfirmedRestoreSkipsPersistenceEvenWhenTerminationFollows() {
        val game = Game()
        game.battleRestores = false
        assertFalse(game.save())
        assertFalse(game.shutdown.detached)
        game.battleRestores = true
        assertFalse(game.shutdown.terminate())
        assertEquals(listOf("restore", "stop endpoint", "detach"), game.actions)
    }

    @Test fun failedWriteCanResumeBeforeTryingToSuspendAgain() {
        val game = Game()
        assertFalse(game.save(succeeds = false))
        assertFalse(game.shutdown.detached)
        game.shutdown.resume()
        assertTrue(game.save())
        assertEquals(listOf("restore", "save", "resume", "restore", "save"), game.actions)
    }

    @Test fun resumingAfterFailedRestoreAllowsTheNextSuspendToTryAgain() {
        val game = Game()
        game.battleRestores = false
        assertFalse(game.save())
        game.shutdown.resume()

        game.battleRestores = true
        assertTrue(game.save())
        assertEquals(listOf("restore", "resume", "restore", "save"), game.actions)
    }

    @Test fun confirmedUnpairOnlyDetachesLocallyAndStillAllowsSoloResumeData() {
        val game = Game()
        assertTrue(game.shutdown.stopBattle())
        game.shutdown.detachAfterEndpointStop()
        game.shutdown.detachAfterEndpointStop()
        game.shutdown.resume()
        assertTrue(game.save())
        assertTrue(game.shutdown.terminate())
        assertEquals(listOf("restore", "detach", "save"), game.actions)
    }
}
