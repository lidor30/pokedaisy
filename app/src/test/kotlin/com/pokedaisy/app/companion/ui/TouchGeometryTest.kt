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

    /** The old rule (every control over the picture), for comparing: what the HUD kept clear of before GitHub #493. */
    private fun everyControlClear(p: TouchGeometry.Placed, l: Float, r: Float): Pair<Int, Int> {
        val over = p.items.filter { it.right > l && it.left < r }
        val top = over.filter { it.cy < p.h / 2 }.maxOfOrNull { it.bottom } ?: 0f
        val bottom = over.filter { it.cy >= p.h / 2 }.minOfOrNull { it.top }?.let { p.h - it } ?: 0f
        return top.toInt() to bottom.toInt().coerceAtLeast(0)
    }

    /** The HUD keeps clear of the rows along the edges only: never more than of every control over the picture, and the
     * same as before wherever the presets' rows are all there is (SIDES, CLASSIC at NORMAL). */
    @Test fun hudClearKeepsToTheEdgeRows() {
        for ((w, h) in screens.filter { it.first > it.second }) for (preset in TouchPreset.entries) for (size in TouchSize.entries)
            for (extras in listOf(TouchControlsStyle().extras, allExtras.extras)) for ((gameBoy, ds) in listOf(false to false, true to false, false to true)) {
            val style = TouchControlsStyle(preset = preset, size = size, extras = extras)
            val p = TouchGeometry.place(style, w, h, dp, false, gameBoy = gameBoy, ds = ds)
            val gw = minOf(w, h * 1.5f); val l = (w - gw) / 2
            val old = everyControlClear(p, l, l + gw); val new = TouchGeometry.hudClear(p, l, l + gw)
            val what = "$preset $size ${w.toInt()}x${h.toInt()} extras=${extras.size} gb=$gameBoy ds=$ds"
            assertTrue("$what: $new over $old", new.first <= old.first && new.second <= old.second)
            if (p.preset == TouchPreset.SIDES || (p.preset == TouchPreset.CLASSIC && size == TouchSize.NORMAL && !ds)) assertEquals(what, old, new)
        }
    }

    /** GitHub #493's layout (2460x1080): FF and A / B halfway up the right side no longer squeeze the HUD into a strip. */
    @Test fun hudClearLeavesTheReportedLayoutRoom() {
        val w = 2460f; val h = 1080f
        val style = TouchControlsStyle(size = TouchSize.LARGE, extras = setOf(TouchButton.FF, TouchButton.QUICK_MENU, TouchButton.SAVE_STATE, TouchButton.LOAD_STATE))
        val at = mapOf(
            TouchButton.L to (530f to 75f), TouchButton.R to (1860f to 95f), TouchButton.QUICK_MENU to (210f to 197f),
            TouchButton.SAVE_STATE to (210f to 297f), TouchButton.LOAD_STATE to (210f to 395f), TouchButton.DPAD to (360f to 665f),
            TouchButton.FF to (2010f to 512f), TouchButton.B to (2050f to 685f), TouchButton.A to (2240f to 610f),
            TouchButton.SELECT to (1555f to 990f), TouchButton.START to (1820f to 990f),
        )
        val preset = TouchGeometry.place(style, w, h, dp, false)
        val layout = TouchLayout(preset.items.mapNotNull { i -> at[i.button]?.let { (x, y) -> i.button to TouchGeometry.placeOf(i.copy(cx = x, cy = y), w, h, dp, 1f) } }.toMap())
        val p = TouchGeometry.place(style.withLayout(false, layout), w, h, dp, false)
        val gw = h * 1.5f; val l = (w - gw) / 2
        val old = everyControlClear(p, l, l + gw)
        assertTrue("the old rule squeezed it: $old", h - old.first - old.second < 100)
        val (top, bottom) = TouchGeometry.hudClear(p, l, l + gw)
        assertEquals("down to the bottom of R", p.item(TouchButton.R)!!.bottom.toInt(), top)
        assertEquals("up to the top of SELECT / START", (h - p.item(TouchButton.SELECT)!!.top).toInt(), bottom)
        assertTrue("room left: ${h - top - bottom}", h - top - bottom > 700)
    }

    /** The HUD's own room: clearances that would leave less are cut back by the same share. */
    @Test fun hudKeepsItsRoom() {
        // Enough room: untouched (past the picture's own insets).
        assertEquals(100f to 200f, hudClears(150f, 230f, 50f, 30f, 1080f, 500f))
        // A big pad near both edges (115 / 610 of 1080) with a 506 px HUD: 574 px between them, in the same proportion.
        val (t, b) = hudClears(115f, 610f, 0f, 0f, 1080f, 506f)
        assertEquals(574f, t + b, 0.5f)
        assertEquals(115f / 610f, t / b, 0.001f)
        // A picture smaller than the room: no clearance at all.
        assertEquals(0f to 0f, hudClears(100f, 100f, 0f, 0f, 400f, 506f))
    }

    /** CORNERS' parts step off the buttons the least way that's clear, and stay in their corners otherwise. */
    @Test fun hudPartsStepOffButtons() {
        val box = androidx.compose.ui.geometry.Rect(0f, 0f, 1000f, 500f)
        fun spot(vararg buttons: androidx.compose.ui.geometry.Rect, taken: List<androidx.compose.ui.geometry.Rect> = emptyList()) =
            hudCornerSpot(200f, 100f, right = false, bottom = true, box, buttons.toList(), taken, gap = 4f)
        // Nothing there: the bottom left corner.
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 400f, 200f, 500f), spot())
        // A D-pad over the corner, low and narrow: slid right along the edge, past it.
        assertEquals(androidx.compose.ui.geometry.Rect(154f, 400f, 354f, 500f), spot(androidx.compose.ui.geometry.Rect(0f, 300f, 150f, 450f)))
        // A wide one along the bottom: lifted over it.
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 246f, 200f, 346f), spot(androidx.compose.ui.geometry.Rect(0f, 350f, 900f, 500f)))
        // Another part in the way counts too.
        assertEquals(154f, spot(taken = listOf(androidx.compose.ui.geometry.Rect(0f, 300f, 150f, 450f)))!!.left)
        // Nowhere clear: none.
        assertEquals(null, spot(androidx.compose.ui.geometry.Rect(0f, 0f, 1000f, 500f)))
        // Two parts wanting the one clear place beside a button: whichever order leaves both clear.
        val items = listOf(HudCornerItem(200f, 100f, right = true, bottom = true), HudCornerItem(200f, 100f, right = true, bottom = false))
        val wall = androidx.compose.ui.geometry.Rect(700f, 0f, 1000f, 500f)
        val places = hudCornerPlaces(items, box, listOf(wall), gap = 4f)
        assertTrue(places.toString(), places.none { it.right > 696f })
        assertFalse(places.toString(), places[0].overlaps(places[1]))
        // Nothing in the way: their corners.
        assertEquals(listOf(androidx.compose.ui.geometry.Rect(800f, 400f, 1000f, 500f), androidx.compose.ui.geometry.Rect(800f, 0f, 1000f, 100f)),
            hudCornerPlaces(items, box, emptyList(), gap = 4f))
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
