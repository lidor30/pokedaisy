package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchLayout
import com.pokedaisy.app.companion.TouchPlace
import com.pokedaisy.app.companion.TouchPosition
import com.pokedaisy.app.companion.TouchPreset
import com.pokedaisy.app.companion.TouchSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TOUCH CONTROLS' layouts: every preset fits its area without overlaps, SIDES stays off the picture, a moved
 * button comes back where it was left, and the D-pad reads the directions it should. */
class TouchGeometryTest {
    private val dp = 2.625f
    private val allExtras = TouchControlsStyle(extras = TouchButton.extras.toSet())

    /** Phones sideways (20:9, 19.5:9, 16:9) and upright, as the game's screen would be laid out. */
    private val screens = listOf(2400f to 1080f, 2340f to 1080f, 1920f to 1080f, 1080f to 2400f, 1080f to 2340f, 1080f to 1920f)

    private fun overlap(a: TouchGeometry.Item, b: TouchGeometry.Item) =
        a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1

    @Test fun presetsFitWithoutOverlaps() {
        for ((w, h) in screens) for (preset in TouchPreset.entries) for (size in TouchSize.entries)
            for (extras in listOf(TouchControlsStyle().extras, allExtras.extras)) for (position in TouchPosition.entries) for (under in listOf(false, true))
            for ((gameBoy, ds) in listOf(false to false, true to false, false to true)) {
            if (w > h && (position != TouchPosition.MIDDLE || under)) continue   // upright-only settings
            val style = TouchControlsStyle(preset = preset, size = size, extras = extras, position = position)
            val stage = simulateTouchStage(w, h, dp, underGame = under)
            val p = TouchGeometry.place(style, stage.area.width, stage.area.height, dp, stage.portrait, gameBoy = gameBoy, ds = ds)
            val what = "$preset $size ${w.toInt()}x${h.toInt()} extras=${extras.size} $position under=$under gb=$gameBoy ds=$ds"
            assertEquals(what, style.shown(gameBoy, ds).toSet(), p.items.map { it.button }.toSet())
            for (item in p.items) {
                assertTrue("$what: ${item.button} off the area", item.left >= -1 && item.top >= -1 && item.right <= p.w + 1 && item.bottom <= p.h + 1)
            }
            // A round button's box is wider than its circle; the D-pad's disc likewise: compare the drawn shapes' boxes.
            for (i in p.items.indices) for (j in i + 1 until p.items.size) {
                val a = p.items[i]; val b = p.items[j]
                assertFalse("$what: ${a.button} overlaps ${b.button}", overlap(a, b))
            }
        }
    }

    @Test fun sidesKeepsOffThePictureOnWidePhones() {
        for (w in listOf(2400f, 2340f)) {
            val stage = simulateTouchStage(w, 1080f, dp)
            val p = TouchGeometry.place(allExtras, stage.area.width, stage.area.height, dp, portrait = false)
            assertEquals(TouchPreset.SIDES, p.preset)
            for (item in p.items) {
                assertTrue("${item.button} over the game at $w", item.right <= stage.game.left + 1 || item.left >= stage.game.right - 1)
            }
        }
        // 16:9's bars are too narrow: CLASSIC instead.
        val narrow = simulateTouchStage(1920f, 1080f, dp)
        assertEquals(TouchPreset.CLASSIC, TouchGeometry.place(TouchControlsStyle(), narrow.area.width, narrow.area.height, dp, false).preset)
    }

    @Test fun positionMovesTheBlockUpright() {
        // UNDER GAME on a tall phone: a big gap at the screen's bottom.
        val stage = simulateTouchStage(1080f, 2400f, dp, underGame = true)
        fun block(position: TouchPosition): Pair<Float, Float> {
            val p = TouchGeometry.place(TouchControlsStyle(position = position), stage.area.width, stage.area.height, dp, true)
            return p.items.minOf { it.top } to p.items.maxOf { it.bottom }
        }
        val edge = TouchGeometry.EDGE * dp
        assertEquals(edge, block(TouchPosition.TOP).first, 1f)
        assertEquals(stage.area.height - edge, block(TouchPosition.BOTTOM).second, 1f)
        val (mt, mb) = block(TouchPosition.MIDDLE)
        assertEquals(stage.area.height / 2, (mt + mb) / 2, 1f)
    }

    @Test fun expandUnderGameGivesTheControlsAStripOfTheirOwn() {
        for ((w, h) in listOf(1080f to 2400f, 1080f to 2220f, 1080f to 1920f)) for (size in TouchSize.entries)
            for (extras in listOf(TouchControlsStyle().extras, allExtras.extras)) for ((gameBoy, ds) in listOf(false to false, true to false, false to true)) {
            val style = TouchControlsStyle(size = size, extras = extras)
            val strip = TouchGeometry.naturalHeight(style, w, dp, gameBoy, ds)
            val stage = simulateTouchStage(w, h, dp, underGame = true, expand = true, padStrip = strip, gameBoy = gameBoy)
            val what = "${w.toInt()}x${h.toInt()} $size extras=${extras.size} gb=$gameBoy ds=$ds"
            // The strip along the bottom, the companion right above it, the game above that.
            assertEquals(what, h, stage.area.bottom, 0.5f)
            assertEquals(what, stage.area.top, stage.companion!!.bottom, 0.5f)
            assertTrue(what, stage.game.bottom <= stage.companion!!.top)
            // Everything fits in the strip, nothing overlaps.
            val p = TouchGeometry.place(style, stage.area.width, stage.area.height, dp, true, gameBoy = gameBoy, ds = ds)
            for (item in p.items) assertTrue("$what: ${item.button} off the strip", item.top >= -1 && item.bottom <= p.h + 1)
            for (i in p.items.indices) for (j in i + 1 until p.items.size) assertFalse("$what: ${p.items[i].button} overlaps ${p.items[j].button} in ${p.h}", overlap(p.items[i], p.items[j]))
        }
        // Without touch controls (no strip) UNDER GAME under EXPAND is the same as along the bottom.
        val none = simulateTouchStage(1080f, 2400f, dp, underGame = true, expand = true, padStrip = 0)
        val bottom = simulateTouchStage(1080f, 2400f, dp, underGame = false, expand = true)
        assertEquals(bottom.companion, none.companion)
    }

    /** X / Y around A / B as on the DS: X over B, Y left of A - a DS game's, and a GBA game's with X AND Y on; off, none. */
    @Test fun dsHasXAndYInItsDiamond() {
        val off = TouchGeometry.place(TouchControlsStyle(xyButtons = false), 2400f, 1080f, dp, false)
        assertTrue(off.items.none { it.button == TouchButton.X || it.button == TouchButton.Y })
        val gba = TouchGeometry.place(TouchControlsStyle(), 2400f, 1080f, dp, false)
        val gx = gba.item(TouchButton.X)!!; val gb = gba.item(TouchButton.B)!!
        assertEquals(gx.cx, gb.cx, 0.5f); assertTrue(gx.cy < gb.cy)
        val p = TouchGeometry.place(TouchControlsStyle(), 2400f, 1080f, dp, false, gameAspect = 4f / 3f, ds = true)
        val x = p.item(TouchButton.X)!!; val y = p.item(TouchButton.Y)!!; val a = p.item(TouchButton.A)!!; val b = p.item(TouchButton.B)!!
        assertEquals(x.cx, b.cx, 0.5f); assertTrue(x.cy < b.cy)
        assertEquals(y.cy, a.cy, 0.5f); assertTrue(y.cx < a.cx)
    }

    /** A DS game sideways (its screens fill the width): the D-pad and the diamond in the bottom corners, SELECT / START
     * along the bottom a little in from them, the extras over the top screen (the left half), whatever SIDES would do. */
    @Test fun dsSidewaysKeepsTheBottomCorners() {
        val p = TouchGeometry.place(TouchControlsStyle(), 2220f, 1080f, dp, false, gameAspect = 4f / 3f, ds = true)
        assertEquals(TouchPreset.CLASSIC, p.preset)
        val dpad = p.item(TouchButton.DPAD)!!; val select = p.item(TouchButton.SELECT)!!
        val start = p.item(TouchButton.START)!!; val y = p.item(TouchButton.Y)!!; val b = p.item(TouchButton.B)!!
        assertTrue(dpad.bottom > p.h * 0.9f && b.bottom > p.h * 0.9f)
        assertTrue(select.left > dpad.right && select.right < p.w / 2)
        assertTrue(start.right < y.left && start.left > p.w / 2)
        assertTrue(select.bottom > p.h * 0.9f && start.bottom > p.h * 0.9f)
        assertTrue(select.left - dpad.right > 20 * dp && y.left - start.right > 20 * dp)
        assertTrue(p.items.filter { it.button.extra }.all { it.right < p.w / 2 })
    }

    /** MIRRORED: the D-pad and A / B swap sides, A stays right of B, L / R and SELECT / START keep their own sides. */
    @Test fun mirroredSwapsTheHands() {
        for ((w, h) in listOf(2400f to 1080f, 1920f to 1080f, 1080f to 2400f)) {
            val stage = simulateTouchStage(w, h, dp)
            val p = TouchGeometry.place(TouchControlsStyle(preset = TouchPreset.MIRRORED), stage.area.width, stage.area.height, dp, stage.portrait)
            assertEquals(TouchPreset.MIRRORED, p.preset)
            val dpad = p.item(TouchButton.DPAD)!!; val a = p.item(TouchButton.A)!!; val b = p.item(TouchButton.B)!!
            assertTrue("$w: D-pad on the right", dpad.cx > p.w / 2)
            assertTrue("$w: A / B on the left", a.cx < p.w / 2 && b.cx < p.w / 2)
            assertTrue(a.cx > b.cx && a.cy < b.cy)
            assertTrue(p.item(TouchButton.L)!!.cx < p.item(TouchButton.R)!!.cx)
            assertTrue(p.item(TouchButton.SELECT)!!.cx < p.item(TouchButton.START)!!.cx)
        }
    }

    /** RAISED, held sideways: the D-pad and A / B higher than CLASSIC's, L over the D-pad and R over A / B. */
    @Test fun raisedLiftsTheThumbs() {
        val classic = TouchGeometry.place(TouchControlsStyle(preset = TouchPreset.CLASSIC), 2400f, 1080f, dp, false)
        val p = TouchGeometry.place(TouchControlsStyle(preset = TouchPreset.RAISED), 2400f, 1080f, dp, false)
        val dpad = p.item(TouchButton.DPAD)!!
        assertTrue(dpad.cy < classic.item(TouchButton.DPAD)!!.cy - 50)
        val l = p.item(TouchButton.L)!!; val r = p.item(TouchButton.R)!!
        assertEquals(dpad.cx, l.cx, 0.5f)
        assertTrue(l.bottom <= dpad.top && r.bottom <= p.item(TouchButton.A)!!.top)
        assertTrue(r.cx > p.w / 2)
    }

    @Test fun gameBoyHasNoShoulders() {
        val p = TouchGeometry.place(TouchControlsStyle(), 2400f, 1080f, dp, false, gameAspect = 10f / 9f, gameBoy = true)
        assertTrue(p.items.none { it.button == TouchButton.L || it.button == TouchButton.R })
    }

    @Test fun movedButtonStaysWhereItWasLeft() {
        val style = TouchControlsStyle()
        val p = TouchGeometry.place(style, 2400f, 1080f, dp, false)
        val a = p.item(TouchButton.A)!!
        val moved = a.copy(cx = 500f, cy = 300f)
        val place = TouchGeometry.placeOf(moved, p.w, p.h, dp, scale = 1.5f)
        val custom = style.withLayout(false, TouchLayout(mapOf(TouchButton.A to place)))
        val again = TouchGeometry.place(custom, 2400f, 1080f, dp, false).item(TouchButton.A)!!
        assertEquals(500f, again.cx, 0.5f)
        assertEquals(300f, again.cy, 0.5f)
        assertEquals(a.w * 1.5f, again.w, 0.5f)
        // The same distance from the left edge on a wider phone (the middle third keeps its offset from the middle);
        // a hidden button is gone.
        val wider = TouchGeometry.place(custom, 2600f, 1080f, dp, false).item(TouchButton.A)!!
        assertEquals(500f, wider.cx, 0.5f)
        val middle = style.withLayout(false, TouchLayout(mapOf(TouchButton.A to TouchGeometry.placeOf(a.copy(cx = 900f), p.w, p.h, dp, 1f))))
        assertEquals(1000f, TouchGeometry.place(middle, 2600f, 1080f, dp, false).item(TouchButton.A)!!.cx, 0.5f)
        val hidden = style.withLayout(false, TouchLayout(mapOf(TouchButton.B to place.copy(hidden = true))))
        assertTrue(TouchGeometry.place(hidden, 2400f, 1080f, dp, false).items.none { it.button == TouchButton.B })
        // A preset picked again sets CUSTOM aside (kept for later).
        val preset = custom.copy(useCustom = false)
        assertEquals(a.cx, TouchGeometry.place(preset, 2400f, 1080f, dp, false).item(TouchButton.A)!!.cx, 0.5f)
        assertTrue(preset.hasCustom)
    }

    @Test fun layoutEncodesAndDecodes() {
        val layout = TouchLayout(mapOf(
            TouchButton.A to TouchPlace(TouchPlace.Anchor.END, 52.5f, TouchPlace.Anchor.END, 80f, 1.25f),
            TouchButton.SELECT to TouchPlace(TouchPlace.Anchor.CENTER, -40f, TouchPlace.Anchor.START, 14f, 1f, hidden = true),
        ))
        assertEquals(layout, TouchLayout.decode(layout.encode()))
        assertTrue(TouchLayout.decode("junk;A:9,x").isEmpty)
        assertTrue(TouchLayout.decode(null).isEmpty)
    }

    @Test fun dpadDirections() {
        val r = 100f
        assertEquals(TouchGeometry.KEY_UP, TouchGeometry.dpadBits(0f, -80f, r, eightWay = true))
        assertEquals(TouchGeometry.KEY_UP or TouchGeometry.KEY_RIGHT, TouchGeometry.dpadBits(60f, -60f, r, eightWay = true))
        // 4-WAY: one direction, the stronger one.
        assertEquals(TouchGeometry.KEY_RIGHT, TouchGeometry.dpadBits(62f, -60f, r, eightWay = false))
        // The middle and far outside: nothing.
        assertEquals(0, TouchGeometry.dpadBits(5f, 5f, r, eightWay = true))
        assertEquals(0, TouchGeometry.dpadBits(200f, 0f, r, eightWay = true))
    }

    @Test fun betweenAAndBPressesBoth() {
        val p = TouchGeometry.place(TouchControlsStyle(preset = TouchPreset.CLASSIC, xyButtons = false), 2400f, 1080f, dp, false)
        val a = p.item(TouchButton.A)!!
        val b = p.item(TouchButton.B)!!
        val hits = TouchGeometry.hits(p, (a.cx + b.cx) / 2, (a.cy + b.cy) / 2, 10 * dp).map { it.button }.toSet()
        assertEquals(setOf(TouchButton.A, TouchButton.B), hits)
    }
}
