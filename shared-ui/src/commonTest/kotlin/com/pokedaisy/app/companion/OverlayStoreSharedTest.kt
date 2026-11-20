package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.overlay.*
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.*

class OverlayStoreSharedTest {
    private fun withStore(test: (OverlayStore) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "overlay-test-${Random.nextLong().toULong()}"
        try { test(OverlayStore(root)) } finally { root.deleteRecursively() }
    }
    // Python zipfile, DEFLATED: two header-only synthetic PNG layers, a cfg, a preview and Finder metadata.
    private val pack get() = kotlin.io.encoding.Base64.decode("UEsDBBQAAAAIAACwcV1dBGG+RgAAAHMAAAAPAAAAcGFjay93aW5kb3cuY2Znyy9LLcpJrCxWsFUw4sqHcAzioQygYGZuun5OYl6KXkFeOkK+LDO1vCC/qASoQMlAz0jHQM8QiM2A2EIJpsoQzRSQerApAFBLAwQUAAAACAAAsHFdZ+tNlh4AAAAdAAAAEQAAAHBhY2svaW1nL2xhbmQucG5n6wzwc+flkuJiYGDg9fRwCWJgYNnAwMCswMEGFAEAUEsDBBQAAAAIAACwcV3AXhymHgAAAB0AAAARAAAAcGFjay9pbWcvcG9ydC5wbmfrDPBz5+WS4mJgYOD19HAJYmBgVmBgYNnAwQYUAQBQSwMEFAAAAAgAALBxXaH9Bj8aAAAAHQAAABAAAABwYWNrL3ByZXZpZXcucG5n6wzwc+flkuJiYGDg9fRwCQLSckAswsEGJAFQSwMEFAAAAAgAALBxXYMW3IwDAAAAAQAAABIAAABfX01BQ09TWC8uX2JhZC5jZmerAABQSwECFAMUAAAACAAAsHFdXQRhvkYAAABzAAAADwAAAAAAAAAAAAAAgAEAAAAAcGFjay93aW5kb3cuY2ZnUEsBAhQDFAAAAAgAALBxXWfrTZYeAAAAHQAAABEAAAAAAAAAAAAAAIABcwAAAHBhY2svaW1nL2xhbmQucG5nUEsBAhQDFAAAAAgAALBxXcBeHKYeAAAAHQAAABEAAAAAAAAAAAAAAIABwAAAAHBhY2svaW1nL3BvcnQucG5nUEsBAhQDFAAAAAgAALBxXaH9Bj8aAAAAHQAAABAAAAAAAAAAAAAAAIABDQEAAHBhY2svcHJldmlldy5wbmdQSwECFAMUAAAACAAAsHFdgxbcjAMAAAABAAAAEgAAAAAAAAAAAAAAgAFVAQAAX19NQUNPU1gvLl9iYWQuY2ZnUEsFBgAAAAAFAAUAOQEAAIgBAAAAAA==")
    private val png get() = byteArrayOf(-119,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,20,0,0,0,10,8,6,0,0,0)

    @Test fun deflatedPackUsesItsCfgLayersAndReopensFromDisk() = withStore { store ->
        val result = store.import(listOf(OverlayStore.Picked("proof.zip", pack)))
        assertEquals(OverlayStore.Result.Imported(listOf("window"), 0), result)
        val reopened = OverlayStore(store.dir.parent!!)
        assertEquals(listOf("window"), reopened.list().map { it.id })
        val overlay = assertNotNull(reopened.get("window"))
        assertEquals(2, overlay.layers.size)
        assertEquals(Viewport(0.2f,0.1f,0.6f,0.8f), overlay.layerFor(1.5f)?.viewport)
        assertEquals(1200, overlay.layerFor(1.5f)?.w)
        assertEquals(800, overlay.layerFor(2f/3)?.w)
    }

    @Test fun missingImageRetryAndImportNoticesPreserveSelectionRules() = withStore { store ->
        val cfg = OverlayStore.Picked("my.cfg", "overlay0_overlay = missing.png".encodeToByteArray())
        val session = OverlayImportSession(store)
        val waiting = session.import(listOf(cfg))
        val need = session.complete(waiting, "builtin:BEZEL")
        assertEquals("builtin:BEZEL", need.selection)
        assertEquals("missing.png", need.needsImage?.image)
        assertTrue(store.list().isEmpty())
        val continuation = session.takePendingImage()
        assertEquals(listOf(cfg), continuation)
        assertTrue(session.takePendingImage().isEmpty())
        val added = session.import(continuation + OverlayStore.Picked("renamed.png", png))
        val single = session.complete(added, "builtin:BEZEL")
        assertEquals("user:my", single.selection)
        assertNotNull(single.notice)
        assertNull(single.needsImage)
        val collection = OverlayImport.complete(OverlayStore.Result.Imported(listOf("one","two"),1), single.selection, overlayChoices(store))
        assertEquals(single.selection, collection.selection)
        assertTrue(collection.notice!!.second.contains("2"))
        assertTrue(collection.notice!!.second.contains("1"))
    }

    @Test fun cancellingMissingImageDropsOnlyThePendingConfig() = withStore { store ->
        val session = OverlayImportSession(store)
        val cfg = OverlayStore.Picked("a.cfg", "overlay0_overlay = absent.png".encodeToByteArray())
        assertNotNull(session.complete(session.import(listOf(cfg)), "").needsImage)
        session.cancel()
        assertTrue(session.takePendingImage().isEmpty())
        val fresh = session.import(listOf(OverlayStore.Picked("fresh.png", png)))
        assertEquals("user:fresh", session.complete(fresh, "builtin:BEZEL").selection)
    }

    @Test fun removalUpdatesTheAvailableChoicesAndClearsOnlyAffectedSelections() = withStore { store ->
        val session = OverlayImportSession(store)
        session.import(listOf(OverlayStore.Picked("one.png", png), OverlayStore.Picked("two.png", png)))
        val first = session.delete("one")
        assertEquals(listOf("user:two"), first.choices.map { it.first.key }.filter { it.startsWith("user:") })
        assertEquals("", first.selectionAfter("user:one"))
        assertEquals("user:two", first.selectionAfter("user:two"))
        assertEquals("builtin:BEZEL", first.selectionAfter("builtin:BEZEL"))
        val all = session.deleteAll()
        assertTrue(all.choices.none { it.first is OverlayChoice.Imported })
        assertEquals("", all.selectionAfter("user:two"))
        assertEquals("builtin:BEZEL", all.selectionAfter("builtin:BEZEL"))
    }

    @Test fun nativePicksAreReadFromFilesAndJunkCannotReplaceTheSelection() = withStore { store ->
        val path = store.dir.parent!! / "proof.png"
        path.writeBytes(png)
        val picked = assertNotNull(OverlayStore.readPicked(path))
        assertEquals("proof.png", picked.name)
        assertContentEquals(png, picked.bytes)
        assertTrue(store.import(listOf(picked)) is OverlayStore.Result.Imported)
        val failed = store.import(listOf(OverlayStore.Picked("bad.zip", byteArrayOf(1,2,3))))
        assertTrue(failed is OverlayStore.Result.Failed)
        assertEquals("user:proof", OverlayImport.complete(failed,"user:proof",overlayChoices(store)).selection)
        assertNull(store.get("../proof"))
        assertFalse(store.delete(".."))
        assertEquals(1, store.deleteAll())
        assertTrue(store.list().isEmpty())
    }

    @Test fun portableWindowSearchCrossesPackedBitsetWords() {
        val w=130; val h=90
        val pixels=IntArray(w*h) { i -> if (i%w in 20..109 && i/w in 10..79) 0 else -1 }
        assertEquals(Viewport(20f/w,10f/h,90f/w,70f/h), OverlayWindow.find(pixels,w,h))
        assertNull(OverlayWindow.find(IntArray(w*h) { -1 },w,h))
    }
}
