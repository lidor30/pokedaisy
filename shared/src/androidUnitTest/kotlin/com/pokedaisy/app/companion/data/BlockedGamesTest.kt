package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedGamesTest {
    @After fun reset() { BlockedGames.enforced = true }

    @Test
    fun `ghost grey is listed by every build's hash and CRC`() {
        val g = BlockedGames.GHOST_GREY
        assertEquals(3, g.sha1s.size)
        assertEquals(3, g.crc32s.size)
        g.sha1s.forEach { assertEquals(g, BlockedGames.forSha1(it)); assertEquals(g, BlockedGames.forSha1(it.uppercase())) }
        g.crc32s.forEach { assertEquals(g, BlockedGames.forCrc(it)) }
        assertTrue(BlockedGames.mayList(0x2000000L))
        assertNull(BlockedGames.forSha1(TelemetrySampler.UNBOUND_V2_1_1_1_SHA1))
    }

    @Test
    fun `allowed, a listed game plays and is only tagged`() {
        BlockedGames.enforced = false
        val sha1 = TelemetrySampler.GHOST_GREY_V1_0_4_SHA1
        assertNull(BlockedGames.forSha1(sha1))
        assertNull(BlockedGames.forCrc("737a7a12"))
        assertEquals(BlockedGames.GHOST_GREY, BlockedGames.listedSha1(sha1))
    }

    @Test
    fun `the library's stamp changes with the switch`() {
        val kept = BlockedGames.stamp
        BlockedGames.enforced = false
        assertTrue(kept != BlockedGames.stamp)
    }
}
