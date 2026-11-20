package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.deleteRecursively
import com.pokedaisy.app.companion.data.mkdirs
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchSkinImportTest {
    private fun skin(id: String, system: String) = InstalledSkin(id,
        DeltaSkin(id, id, "com.example.$system", emptyList()), "/unused/$id".toPath())
    private val style = TouchControlsStyle(skinGba = "my-gba", skinGb = "my-gb", useCustom = true, opacity = 75)

    @Test fun singleImportReplacesOnlyItsOwnSystemsChoice() {
        for ((system, expected) in listOf("gba" to style.copy(skinGba = "new"), "gbc" to style.copy(skinGb = "new"))) {
            val result = TouchSkinImport.complete(DeltaSkinStore.ImportResult(listOf(skin("new", system)), emptyList()), style)
            assertEquals(expected, result.style)
            assertTrue(result.notice.second.contains("new"))
        }
    }

    @Test fun collectionImportDoesNotSilentlyReplaceEitherChoice() {
        val result = TouchSkinImport.complete(DeltaSkinStore.ImportResult(listOf(skin("one", "gba"), skin("two", "gbc")), emptyList()), style)
        assertEquals(style, result.style)
        assertTrue(result.notice.second.contains("2"))
    }

    @Test fun deletingOneSkinRetainsTheOtherSystemAndTheCustomLayout() {
        assertEquals(style.copy(skinGba = null), style.withoutSkin("my-gba"))
        assertEquals(style, style.withoutSkin("not-selected"))
        assertEquals(style.copy(skinGba = null, skinGb = null), style.copy(skinGb = "my-gba").withoutSkin("my-gba"))
    }

    @Test fun sessionUsesTheLatestStyleAndOnlyClearsADeletedSkin() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "skin-session-${Random.nextLong().toULong()}"
        try {
            val store = DeltaSkinStore(root)
            var current = style
            val session = TouchSkinSession(store, { current }, { current = it })
            val result = DeltaSkinStore.ImportResult(listOf(skin("new", "gba")), emptyList())
            current = current.copy(opacity = 100)
            assertTrue(session.complete(result).second.contains("new"))
            assertEquals(style.copy(skinGba = "new", opacity = 100), current)

            assertFalse(session.delete("new"))
            assertEquals("new", current.skinGba)
            (root / "new").mkdirs()
            assertTrue(session.delete("new"))
            assertEquals(style.copy(skinGba = null, opacity = 100), current)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusedImportRetainsChoicesAndReportsTheReason() {
        val result = TouchSkinImport.complete(DeltaSkinStore.ImportResult(emptyList(), listOf("invalid archive")), style)
        assertEquals(style, result.style)
        assertTrue(result.notice.second.contains("invalid archive"))
    }

    @Test fun oneAcceptedSkinStillSelectsWhenOtherFilesWereRefused() {
        val result = TouchSkinImport.complete(DeltaSkinStore.ImportResult(listOf(skin("one", "gba")), listOf("bad")), style)
        assertEquals(style.copy(skinGba = "one"), result.style)
    }
}
