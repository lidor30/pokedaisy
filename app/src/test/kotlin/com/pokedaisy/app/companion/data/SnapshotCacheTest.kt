package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import okio.Path.Companion.toOkioPath

/** The companion's data saved beside a savestate, shown on a launch that resumes from it. */
class SnapshotCacheTest {
    private fun live(): SnapshotView {
        activeGame = GameKind.FIRERED
        return buildSnapshotView(decodeNative("firered_vanilla", NATIVE_FIRERED_REV1)).copy(game = GameKind.FIRERED)
    }

    @Test
    fun `what the first tabs show comes back as it was`() {
        val v = live()
        assertTrue(v.hasData)
        val bytes = SnapshotCache.encode(v)
        // A few KB beside every state, never more than the reader takes.
        assertTrue("${bytes.size} bytes", bytes.size < 64 * 1024)
        val back = SnapshotCache.decode(bytes)!!
        assertTrue(back.hasData)
        assertEquals(v.game, back.game)
        assertEquals(v.party, back.party)
        assertEquals(v.items, back.items)
        assertEquals(v.location, back.location)
        assertEquals(v.money, back.money)
        assertEquals(listOf(v.x, v.y, v.mapGroup, v.mapNum, v.regionMapSectionId, v.mapWidth, v.mapHeight, v.mapType, v.playerGender),
            listOf(back.x, back.y, back.mapGroup, back.mapNum, back.regionMapSectionId, back.mapWidth, back.mapHeight, back.mapType, back.playerGender))
        assertEquals(v.frameCounter, back.frameCounter)
        assertFalse(back.inBattle)
        // FireRed always has a guide, so its tab is kept (waiting on LOADING).
        assertTrue("GUIDE" in back.cachedTabs!!)
    }

    @Test
    fun `a localized game's names come back in its language`() {
        // Unbound FR: the held-item tags look names up as they draw, before the game is detected (#227).
        val v = live().copy(game = GameKind.UNBOUND, textCode = UNBOUND_FR_TEXT, textLanguage = 'F')
        val back = SnapshotCache.decode(SnapshotCache.encode(v))!!
        assertEquals(UNBOUND_FR_TEXT, back.textCode)
        assertEquals('F', back.textLanguage)
    }

    @Test
    fun `beside the state, and none for a game with no data yet`() {
        val dir = Files.createTempDirectory("snapcache").toFile()
        try {
            val state = dir.toOkioPath() / "resume"
            SnapshotCache.write(state, live())
            assertTrue(SnapshotCache.fileFor(state).isFile)
            assertNotNull(SnapshotCache.read(state))

            val slot = dir.toOkioPath() / "ss3"
            SnapshotCache.copy(state, slot)
            assertEquals(SnapshotCache.read(state), SnapshotCache.read(slot))

            // The title screen: the old copy would be stale, so it goes.
            SnapshotCache.write(state, SnapshotView(connected = true, game = GameKind.FIRERED))
            assertFalse(SnapshotCache.fileFor(state).isFile)
            assertNull(SnapshotCache.read(state))
            SnapshotCache.copy(state, slot)
            assertNull(SnapshotCache.read(slot))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `another version's file is ignored`() {
        val bytes = SnapshotCache.encode(live())
        bytes[7] = 99   // the version
        assertNull(SnapshotCache.decode(bytes))
        assertNull(runCatching { SnapshotCache.decode(bytes.copyOf(bytes.size / 2)) }.getOrNull())
    }
}
