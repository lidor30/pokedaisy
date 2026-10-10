package com.pokedaisy.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OverlaysTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- .cfg -----------------------------------------------------------------

    @Test
    fun parsesRetroArchBorderCfg() {
        // libretro/common-overlays' borders/gba-4k.cfg, as it is.
        val layers = OverlayCfg.parse(
            """
            overlays = 1
            overlay0_overlay = img/gba-4k.png
            overlay0_full_screen = true
            overlay0_descs = 0
            overlay0_viewport = "0.25,0.203,0.5,0.5935"
            overlay0_viewport_fill = true
            """.trimIndent(),
        )
        assertEquals(1, layers.size)
        assertEquals("img/gba-4k.png", layers[0].image)
        assertEquals(Viewport(0.25f, 0.203f, 0.5f, 0.5935f), layers[0].viewport)
    }

    @Test
    fun parsesQuotesCommentsAndMissingCount() {
        val layers = OverlayCfg.parse(
            "﻿# a comment\r\noverlay0_overlay = \"Some Bezel #1.png\"\r\noverlay0_name = \"landscape\"\r\n" +
                "overlay1_overlay = portrait.png # trailing comment\r\noverlay1_viewport = \"2,0,1,1\"\r\n",
        )
        assertEquals(listOf("Some Bezel #1.png", "portrait.png"), layers.map { it.image })
        assertEquals("landscape", layers[0].name)
        // Outside the image: ignored, the window is found from the PNG instead.
        assertNull(layers[1].viewport)
    }

    @Test
    fun writtenCfgReadsBack() {
        val layers = listOf(OverlayLayer("0.png", Viewport(0.1f, 0.2f, 0.5f, 0.6f), "landscape"), OverlayLayer("1.png"))
        val back = OverlayCfg.parse(OverlayCfg.write("MY \"BEZEL\"", layers))
        assertEquals(layers, back)
    }

    @Test
    fun viewportRejectsNonsense() {
        assertNull(Viewport.parse("0.5,0.5,0.6,0.1"))
        assertNull(Viewport.parse("0,0,0,1"))
        assertNull(Viewport.parse("a,b,c,d"))
        assertNull(Viewport.parse("0,0,1"))
        assertNotNull(Viewport.parse(" 0 , 0 , 1 , 1 "))
    }

    // ---- window ---------------------------------------------------------------

    private fun image(w: Int, h: Int, clear: (Int, Int) -> Boolean) =
        IntArray(w * h) { i -> if (clear(i % w, i / w)) 0x00000000 else 0xFF336699.toInt() }

    @Test
    fun findsTheSeeThroughWindow() {
        val px = image(200, 100) { x, y -> x in 50..149 && y in 20..79 }
        assertEquals(Viewport(0.25f, 0.2f, 0.5f, 0.6f), OverlayWindow.find(px, 200, 100))
    }

    @Test
    fun findsTheWindowAroundALogoInItsMiddle() {
        val px = image(200, 100) { x, y -> x in 50..149 && y in 20..79 && !(x in 95..104 && y in 45..54) }
        assertEquals(Viewport(0.25f, 0.2f, 0.5f, 0.6f), OverlayWindow.find(px, 200, 100))
    }

    @Test
    fun noWindowInAnOpaqueImageOrAPinhole() {
        assertNull(OverlayWindow.find(image(200, 100) { _, _ -> false }, 200, 100))
        assertNull(OverlayWindow.find(image(200, 100) { x, y -> x in 99..101 && y in 49..51 }, 200, 100))
    }

    // ---- placement ------------------------------------------------------------

    @Test
    fun gameSitsInTheWindowAtItsOwnShape() {
        val p = OverlayGeometry.layout(1920, 1080, 3840, 2160, Viewport(0.25f, 0.203f, 0.5f, 0.5935f), 240, 160, stretch = false)
        assertEquals(Box(0f, 0f, 1920f, 1080f), p.overlay)
        assertEquals(Box(480f, 220f, 960f, 640f), p.game)
        assertTrue(p.onTop)
    }

    @Test
    fun stretchFillsTheWindow() {
        val p = OverlayGeometry.layout(1920, 1080, 1920, 1080, Viewport(0.25f, 0.25f, 0.5f, 0.5f), 160, 144, stretch = true)
        assertEquals(Box(480f, 270f, 960f, 540f), p.game)
    }

    @Test
    fun anOverlayOfAnotherShapeIsFittedAndCentred() {
        // A 16:9 border on a 4:3 screen: letterboxed, the window with it.
        val p = OverlayGeometry.layout(1024, 768, 1920, 1080, Viewport(0.25f, 0.25f, 0.5f, 0.5f), 240, 160, stretch = false)
        assertEquals(Box(0f, 96f, 1024f, 576f), p.overlay)
        assertEquals(Box(296f, 240f, 432f, 288f), p.game)
    }

    @Test
    fun noWindowMeansABackground() {
        val p = OverlayGeometry.layout(1920, 1080, 1920, 1080, null, 240, 160, stretch = false)
        assertEquals(false, p.onTop)
        assertEquals(Box(150f, 0f, 1620f, 1080f), p.game)
    }

    // ---- built-in frames ------------------------------------------------------

    @Test
    fun builtInFrameKeepsAWholeScaleOnTheThor() {
        val f = BuiltInFrames.render(BuiltInFrames.Style.DAISY, 1920, 1080, 240, 160)
        assertEquals(6, f.scale)
        assertEquals(320, f.w)
        assertEquals(180, f.h)
        val p = OverlayGeometry.layout(1920, 1080, f.w, f.h, f.window, 240, 160, stretch = false, scale = f.scale)
        assertEquals(Box(240f, 60f, 1440f, 960f), p.game)
        // The game's rectangle is see-through, the frame right around it isn't.
        assertEquals(0, f.argb[(10 + 80) * f.w + 40 + 120] ushr 24)
        assertEquals(0xFF, f.argb[(10 + 80) * f.w + 39] ushr 24)
    }

    @Test
    fun builtInFrameFitsAGameBoyAndSmallScreens() {
        val gb = BuiltInFrames.render(BuiltInFrames.Style.BEZEL, 1920, 1080, 160, 144)
        assertEquals(7, gb.scale)
        val small = BuiltInFrames.render(BuiltInFrames.Style.DAISY, 300, 200, 240, 160)
        assertEquals(1, small.scale)
        assertTrue(small.w >= 242 && small.h >= 162)
        for (style in BuiltInFrames.Style.entries) {
            val f = BuiltInFrames.render(style, 960, 1080, 240, 160)
            val p = OverlayGeometry.layout(960, 1080, f.w, f.h, f.window, 240, 160, stretch = false, scale = f.scale)
            assertEquals(240f * f.scale, p.game.w)
            assertEquals(160f * f.scale, p.game.h)
        }
    }

    // ---- import ---------------------------------------------------------------

    /** Just enough of a PNG for PngInfo (the store never decodes). */
    private fun png(w: Int, h: Int): ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13) +
        "IHDR".toByteArray() + int(w) + int(h) + byteArrayOf(8, 6, 0, 0, 0)

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private val gbaCfg = "overlays = 1\noverlay0_overlay = img/gba-4k.png\noverlay0_viewport = \"0.25,0.203,0.5,0.5935\"\n"

    @Test
    fun importsAZipPackWithItsImageInAFolder() {
        val store = OverlayStore(tmp.root)
        val r = store.import(listOf(OverlayStore.Picked("borders.zip", zip(
            "borders/gba-4k.cfg" to gbaCfg.toByteArray(),
            "borders/img/gba-4k.png" to png(3840, 2160),
            "__MACOSX/borders/._gba-4k.cfg" to byteArrayOf(0),
        ))))
        assertEquals(OverlayStore.Result.Imported(listOf("gba-4k"), 0), r)
        val o = store.get("gba-4k")!!
        assertEquals("GBA-4K", o.name)
        assertEquals(Viewport(0.25f, 0.203f, 0.5f, 0.5935f), o.layers.single().viewport)
        assertEquals(3840, o.layers.single().w)
        assertEquals(listOf(OverlayChoice.None, OverlayChoice.BuiltIn(BuiltInFrames.Style.DAISY), OverlayChoice.BuiltIn(BuiltInFrames.Style.BEZEL), OverlayChoice.Imported("gba-4k")), OverlayChoice.all(store))
    }

    @Test
    fun aCfgAloneAsksForItsImageThenTakesIt() {
        val store = OverlayStore(tmp.root)
        val cfg = OverlayStore.Picked("gba-4k.cfg", gbaCfg.toByteArray())
        assertEquals(OverlayStore.Result.NeedsImage("gba-4k.cfg", "gba-4k.png"), store.import(listOf(cfg)))
        val r = store.import(listOf(cfg, OverlayStore.Picked("gba-4k.png", png(1920, 1080))))
        assertEquals(OverlayStore.Result.Imported(listOf("gba-4k"), 0), r)
        // The image picked under another name still goes with its .cfg, not as an overlay of its own.
        val other = store.import(listOf(cfg, OverlayStore.Picked("renamed.png", png(1920, 1080))))
        assertEquals(OverlayStore.Result.Imported(listOf("gba-4k-2"), 0), other)
        assertEquals(Viewport(0.25f, 0.203f, 0.5f, 0.5935f), store.get("gba-4k-2")!!.layers.single().viewport)
    }

    @Test
    fun aCfgReadsItsImageFromBesideItsRealPath() {
        val src = File(tmp.root, "sd/overlays").apply { mkdirs() }
        File(src, "img").mkdirs()
        File(src, "img/gba-4k.png").writeBytes(png(1920, 1080))
        val store = OverlayStore(File(tmp.root, "app"))
        val r = store.import(listOf(OverlayStore.Picked("gba-4k.cfg", gbaCfg.toByteArray(), File(src, "gba-4k.cfg").path)))
        assertEquals(OverlayStore.Result.Imported(listOf("gba-4k"), 0), r)
    }

    @Test
    fun barePngsAreOverlaysAndNamesStayUnique() {
        val store = OverlayStore(tmp.root)
        store.import(listOf(OverlayStore.Picked("My Frame.png", png(1920, 1080))))
        store.import(listOf(OverlayStore.Picked("my frame.png", png(1920, 1080))))
        assertEquals(listOf("my-frame", "my-frame-2"), store.list().map { it.id }.sorted())
        assertEquals(listOf("MY FRAME", "MY FRAME"), store.list().map { it.name })
        // The pick-lists match by label: the second one gets a number.
        assertEquals(listOf("MY FRAME", "MY FRAME 2"), overlayLabelsOf(store))
        assertTrue(store.delete("my-frame"))
        assertEquals(listOf("my-frame-2"), store.list().map { it.id })
    }

    /** overlayChoices' de-duplication without its tk() (OverlayLabels.kt needs the app's text tables). */
    private fun overlayLabelsOf(store: OverlayStore): List<String> {
        val seen = HashSet<String>()
        return store.list().map { o -> var l = o.name; var n = 2; while (!seen.add(l)) l = "${o.name} ${n++}"; l }
    }

    @Test
    fun junkIsRefused() {
        val store = OverlayStore(tmp.root)
        assertEquals(
            OverlayStore.Result.Failed(OverlayStore.Reason.NOTHING_USABLE),
            store.import(listOf(OverlayStore.Picked("notes.txt", "hello".toByteArray()), OverlayStore.Picked("fake.png", "not a png".toByteArray()))),
        )
        assertTrue(store.list().isEmpty())
        // Ids are folder names: nothing outside the store.
        assertEquals(false, store.delete(".."))
        assertNull(store.get("../app"))
    }

    @Test
    fun choiceKeysRoundTrip() {
        for (c in listOf(OverlayChoice.None, OverlayChoice.BuiltIn(BuiltInFrames.Style.BEZEL), OverlayChoice.Imported("gba-4k"))) {
            assertEquals(c, OverlayChoice.of(c.key))
        }
        assertEquals(OverlayChoice.None, OverlayChoice.of("builtin:GONE"))
        assertEquals(OverlayChoice.None, OverlayChoice.of(null))
    }
}
