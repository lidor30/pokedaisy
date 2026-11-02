package com.pokedaisy.app.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Delta skins: info.json read the way Delta reads it, laid on our screens, touches read, and imports kept safe. */
class DeltaSkinTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A GBA skin in Delta's shape: an edgeToEdge portrait and a standard landscape, both forms of `inputs`. */
    private val info = """
        {
          "name": "Test Skin", "identifier": "com.example.test", "gameTypeIdentifier": "com.rileytestut.delta.game.gba",
          "debug": false,
          "representations": { "iphone": {
            "edgeToEdge": { "portrait": {
              "assets": { "resizable": "portrait.pdf" },
              "items": [
                { "inputs": { "up": "up", "down": "down", "left": "left", "right": "right" },
                  "frame": { "x": 20, "y": 600, "width": 120, "height": 120 }, "extendedEdges": { "left": 20 } },
                { "inputs": ["a"], "frame": { "x": 330, "y": 600, "width": 60, "height": 60 } },
                { "inputs": ["b"], "frame": { "x": 260, "y": 640, "width": 60, "height": 60 } },
                { "inputs": ["a", "b"], "frame": { "x": 300, "y": 700, "width": 40, "height": 40 }, "extendedEdges": { "top": 0, "bottom": 0, "left": 0, "right": 0 } },
                { "inputs": ["menu"], "frame": { "x": 190, "y": 820, "width": 40, "height": 30 } },
                { "inputs": { "x": "touchScreenX", "y": "touchScreenY" }, "frame": { "x": 0, "y": 0, "width": 10, "height": 10 } },
                { "inputs": ["someUnknownThing"], "frame": { "x": 0, "y": 0, "width": 10, "height": 10 } }
              ],
              "screens": [ { "inputFrame": { "x": 0, "y": 0, "width": 240, "height": 160 },
                             "outputFrame": { "x": 0, "y": 60, "width": 414, "height": 276 } } ],
              "mappingSize": { "width": 414, "height": 896 },
              "extendedEdges": { "top": 7, "bottom": 7, "left": 7, "right": 7 },
              "translucent": true
            } },
            "standard": { "landscape": {
              "assets": { "small": "l1.png", "medium": "l2.png", "large": "l3.png" },
              "items": [ { "inputs": ["start"], "frame": { "x": 10, "y": 10, "width": 40, "height": 20 } } ],
              "gameScreenFrame": { "x": 133, "y": 0, "width": 400, "height": 266 },
              "mappingSize": { "width": 667, "height": 375 }
            } }
          } }
        }
    """.trimIndent()

    @Test fun readsDeltasInfoJson() {
        val skin = DeltaSkin.parse(info)
        assertEquals("Test Skin", skin.name)
        assertEquals(SkinSystem.GBA, skin.system)
        assertEquals(2, skin.representations.size)
        val p = skin.representations.first { it.portrait }
        assertEquals("portrait.pdf", p.asset)
        assertTrue(p.translucent)
        // The touch screen and the unknown input aren't buttons.
        assertEquals(5, p.items.size)
        assertTrue(p.items[0].dpad)
        assertEquals(SkinEdges(7f, 7f, 20f, 7f), p.items[0].edges)
        assertEquals(setOf(TouchButton.A, TouchButton.B), p.items[3].buttons)
        assertEquals(SkinEdges(), p.items[3].edges)
        assertEquals(setOf(TouchButton.QUICK_MENU), p.items[4].buttons)
        assertEquals(SkinRect(0f, 60f, 414f, 276f), p.screen)
        val l = skin.representations.first { !it.portrait }
        assertEquals("l3.png", l.asset)   // the largest PNG
        assertFalse(l.translucent)
        assertEquals(SkinRect(133f, 0f, 400f, 266f), l.screen)   // the older gameScreenFrame
    }

    @Test fun systemsByGameType() {
        assertEquals(SkinSystem.GBA, SkinSystem.of("com.rileytestut.delta.game.gba"))
        assertEquals(SkinSystem.GB, SkinSystem.of("com.rileytestut.Delta.game.gbc"))
        assertEquals(SkinSystem.DS, SkinSystem.of("com.rileytestut.delta.game.ds"))
        assertNull(SkinSystem.of("com.rileytestut.delta.game.snes"))
    }

    @Test fun picksTheClosestShape() {
        val skin = DeltaSkin.parse(info)
        assertTrue(skin.pick(1080f, 2400f, portrait = true)!!.portrait)
        assertFalse(skin.pick(2400f, 1080f, portrait = false)!!.portrait)
        val portraitOnly = skin.copy(representations = skin.representations.filter { it.portrait })
        assertNull(portraitOnly.pick(2400f, 1080f, portrait = false))
    }

    @Test fun sidewaysFillsTheAreaAndPlacesTheGame() {
        val rep = DeltaSkin.parse(info).representations.first { !it.portrait }
        // 667x375 is 16:9: the Thor's top screen takes it whole, the game in its frame.
        val fit = SkinFit(rep, 1920f, 1080f, cropToControls = false)
        assertEquals(0f, fit.image.left, 1f); assertEquals(1920f, fit.image.right, 1f)
        val game = fit.game!!
        assertEquals(133f * 1920 / 667, game.left, 1f)
        assertEquals(266f * 1080 / 375, game.bottom, 1f)
        // A 20:9 phone is too far from 16:9 to stretch: as tall as the screen, centred.
        val wide = SkinFit(rep, 2400f, 1080f, cropToControls = false)
        assertEquals(1080f, wide.image.height, 1f)
        assertEquals(2400f / 2, wide.image.center.x, 1f)
        assertEquals(667f * 1080 / 375, wide.image.width, 1f)
    }

    @Test fun uprightShowsOnlyTheButtonsBelowTheScreen() {
        val rep = DeltaSkin.parse(info).representations.first { it.portrait }
        val fit = SkinFit(rep, 1080f, 1000f, cropToControls = true)
        assertNull(fit.game)
        // The part from the topmost button's touch area (y 593) down fills the area's bottom.
        assertEquals(1000f, fit.clip.bottom, 1f)
        assertEquals((896f - 593f) * fit.clip.width / 414f, fit.clip.height, 1f)
        assertEquals(fit.clip.top, fit.toPx(SkinRect(0f, 593f, 1f, 1f)).top, 1f)
        // Never above the screen's bottom.
        val high = rep.copy(items = rep.items + SkinItem(SkinRect(10f, 100f, 10f, 10f), SkinEdges(), setOf(TouchButton.L)))
        assertEquals(SkinFit(high, 1080f, 1000f, true).toPx(SkinRect(0f, 336f, 1f, 1f)).top, SkinFit(high, 1080f, 1000f, true).clip.top, 1f)
    }

    @Test fun touchesPressWhatTheSkinSays() {
        val rep = DeltaSkin.parse(info).representations.first { it.portrait }
        val fit = SkinFit(rep, 414f, 896f, cropToControls = false)   // 1:1 points
        fun at(x: Float, y: Float, eight: Boolean = true) = fit.touch(x, y, eight)
        val a = 1 shl TouchButton.A.ordinal
        val b = 1 shl TouchButton.B.ordinal
        assertEquals(a to 0, at(360f, 610f))
        // The A+B button presses both; outside every touch area, nothing.
        assertEquals((a or b) to 0, at(320f, 720f))
        assertEquals(0 to 0, at(200f, 400f))
        // The D-pad: thirds of its frame; its middle is none; up-left a diagonal (one direction on 4-WAY).
        assertEquals(0 to TouchDirs.UP, at(80f, 605f))
        assertEquals(0 to 0, at(80f, 660f))
        assertEquals(0 to (TouchDirs.UP or TouchDirs.LEFT), at(25f, 605f))
        assertEquals(0 to TouchDirs.UP, at(30f, 601f, eight = false))
        // Its extended edge reaches 20 points to the left.
        assertEquals(0 to TouchDirs.LEFT, at(5f, 660f))
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in files) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun importsKeepTheSkinAndItsPictures() {
        val store = DeltaSkinStore(tmp.newFolder("skins"))
        val skin = zip("info.json" to info.toByteArray(), "portrait.pdf" to byteArrayOf(1), "l3.png" to byteArrayOf(2), "__MACOSX/._info.json" to byteArrayOf(3))
        val r = store.import(ByteArrayInputStream(skin))
        assertEquals(r.refused.toString(), 1, r.added.size)
        val installed = store.list().single()
        assertEquals("com.example.test", installed.id)
        assertNotNull(installed.file("portrait.pdf"))
        assertFalse(File(installed.dir, "__MACOSX").exists())
        // The same identifier again replaces it; a zip of skins brings each.
        store.import(ByteArrayInputStream(skin))
        assertEquals(1, store.list().size)
        val other = info.replace("com.example.test", "com.example.two")
        val pack = zip("a.deltaskin" to skin, "b.deltaskin" to zip("info.json" to other.toByteArray(), "portrait.pdf" to byteArrayOf(1), "l3.png" to byteArrayOf(2)))
        assertEquals(2, store.import(ByteArrayInputStream(pack)).added.size)
        assertEquals(2, store.list().size)
        assertTrue(store.delete("com.example.two"))
        assertEquals(1, store.list().size)
    }

    @Test fun refusesWhatIsntASkinWeCanUse() {
        val store = DeltaSkinStore(tmp.newFolder("skins"))
        fun refused(vararg files: Pair<String, ByteArray>) = store.import(ByteArrayInputStream(zip(*files))).let { it.added.isEmpty() && it.refused.isNotEmpty() }
        assertTrue(refused("readme.txt" to byteArrayOf(1)))
        assertTrue(refused("info.json" to "{ nope".toByteArray()))
        assertTrue(refused("info.json" to info.replace(".gba\"", ".snes\"").toByteArray(), "portrait.pdf" to byteArrayOf(1), "l3.png" to byteArrayOf(1)))
        // A picture it names isn't in the zip.
        assertTrue(refused("info.json" to info.toByteArray(), "portrait.pdf" to byteArrayOf(1)))
        assertTrue(store.list().isEmpty())
    }

    @Test fun namesCantLeaveTheSkinsFolder() {
        assertNull(DeltaSkinStore.safeName("../evil.png"))
        assertNull(DeltaSkinStore.safeName("a/../../evil.png"))
        assertNull(DeltaSkinStore.safeName("C:/x.png"))
        assertNull(DeltaSkinStore.safeName(".hidden"))
        assertEquals("pdfs${File.separator}a.pdf", DeltaSkinStore.safeName("pdfs/a.pdf"))
        val store = DeltaSkinStore(tmp.newFolder("skins"))
        val evil = zip("info.json" to info.toByteArray(), "portrait.pdf" to byteArrayOf(1), "l3.png" to byteArrayOf(2), "../../escape.txt" to byteArrayOf(9))
        store.import(ByteArrayInputStream(evil))
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertFalse(File(store.root.parentFile, "escape.txt").exists())
    }
}
