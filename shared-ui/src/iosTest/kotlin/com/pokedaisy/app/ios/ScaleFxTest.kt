@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.WideCamera
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.MetalScreenEffects
import com.pokedaisy.app.companion.ScreenFilter
import com.pokedaisy.app.companion.Smoothing
import com.pokedaisy.app.game.GameRenderer
import com.pokedaisy.app.game.PictureEffects
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ScaleFxTest {
    private val palette = arrayOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255), intArrayOf(200, 40, 70), intArrayOf(10, 180, 90))
    private fun renderer(): GameRenderer? = GameRenderer.create()?.apply { stretch = true }.also {
        if (it == null) println("ScaleFxTest: no Metal device; GPU checks not run")
    }
    private fun source(w: Int, h: Int, mixed: Boolean) = ByteArray(w * h * 4) { i ->
        val x = i / 4 % w; val y = i / 4 / w
        val index = if (mixed) (x * 3 + y * 5 + x * y) % 4 else if (x == y || x == y + 1) 1 else 0
        if (i % 4 == 3) 0 else palette[index][i % 4].toByte()
    }
    private fun upload(r: GameRenderer, w: Int, h: Int, mixed: Boolean, expanded: Boolean = false) {
        source(w, h, mixed).usePinned { assertTrue(r.uploadFrame(it.addressOf(0), w, h,
            if (expanded) WideCamera.Window(0, 0, w, h, 0f, 0f, 8f) else null)) }
    }
    private fun golden(mixed: Boolean): ByteArray {
        val rows = if (mixed) mixedReference else diagonalReference
        return ByteArray(18 * 18 * 4) { i ->
            val code = rows[i / 4 / 18][i / 4 % 18]
            val color = if (mixed) code.digitToInt() else if (code == '#') 1 else 0
            if (i % 4 == 3) (-1).toByte() else palette[color][2 - i % 4].toByte()
        }
    }

    @Test fun originalFivePassOutputMatchesGlReferenceAndSurvivesFormatSwitches() = runBlocking {
        val r = renderer() ?: return@runBlocking
        val passes = smoothingPrograms(Smoothing.SCALEFX)
        for (mixed in listOf(false, true)) {
            upload(r, 6, 6, mixed)
            val sharp = renderGamePixels(r, 18, 18)
            val expected = golden(mixed)
            assertFalse(sharp.contentEquals(expected), "Reference must exercise edge interpolation")
            assertTrue(r.configureEffects(PictureEffects(smoothing = passes)))
            assertContentEquals(expected, renderGamePixels(r, 18, 18), "Original GLSL reference, mixed=$mixed")
            for (mode in listOf(Smoothing.XBRZ, Smoothing.XBR)) {
                assertTrue(r.configureEffects(PictureEffects(smoothing = smoothingPrograms(mode))))
                renderGamePixels(r, 18, 18)
            }
            assertTrue(r.configureEffects(PictureEffects(smoothing = passes)))
            assertContentEquals(expected, renderGamePixels(r, 18, 18), "Half-float targets must be restored after mode changes")
            assertTrue(r.configureEffects(PictureEffects()))
            assertContentEquals(sharp, renderGamePixels(r, 18, 18))
        }
        println("ScaleFxTest: both original GL reference images match Metal, including target-format switches and OFF")
    }

    @Test fun everyFilterCombinesWithScaleFxColorsAndUnlit() = runBlocking {
        val r = renderer() ?: return@runBlocking
        upload(r, 6, 6, true)
        val passes = smoothingPrograms(Smoothing.SCALEFX)
        for (filter in ScreenFilter.entries) {
            assertTrue(r.configureEffects(MetalScreenEffects.picture(filter, GridStrength.MEDIUM, true, true).copy(smoothing = passes)))
            val pixels = renderGamePixels(r, 53, 47)
            assertTrue(pixels.indices.filter { it % 4 != 3 }.any { pixels[it] != 0.toByte() }, filter.name)
            assertTrue(pixels.indices.filter { it % 4 == 3 }.all { pixels[it] == (-1).toByte() }, filter.name)
        }
    }

    @Test fun resizedExpandedCapacityKeepsBothHistoryInputsInsideTheirLogicalBounds() = runBlocking {
        val reused = renderer() ?: return@runBlocking
        val settings = MetalScreenEffects.picture(ScreenFilter.CRT, GridStrength.MEDIUM, true, true)
            .copy(smoothing = smoothingPrograms(Smoothing.SCALEFX))
        assertTrue(reused.configureEffects(settings))
        for (w in listOf(2, 3, 2)) {
            val fresh = assertNotNull(renderer()); assertTrue(fresh.configureEffects(settings))
            upload(reused, w, 3, true, true); upload(fresh, w, 3, true)
            assertContentEquals(renderGamePixels(fresh, w * 8, 24), renderGamePixels(reused, w * 8, 24), "logical width $w")
        }
    }

    @Test fun missingHistoryNeverDisplaysIntermediateMetricData() = runBlocking {
        val r = renderer() ?: return@runBlocking
        upload(r, 6, 6, true)
        val sharp = renderGamePixels(r, 18, 18)
        val broken = smoothingPrograms(Smoothing.SCALEFX).toMutableList()
        broken[4] = broken[4].copy(history = 99)
        r.configureEffects(PictureEffects(smoothing = broken))
        assertContentEquals(sharp, renderGamePixels(r, 18, 18))
    }

    // Original five-pass GLSL output at 3x for the procedural sources above; game pictures are opaque.
    private val diagonalReference = """
######............
#######...........
#######...........
.#######..........
...######.........
....######........
.....######.......
......######......
.......######.....
........######....
.........######...
..........#######.
...........#######
............######
.............#####
..............####
...............###
...............###
""".trimIndent().lines()
    private val mixedReference = """
000333222111000333
000333222111000333
000333221111100333
111111111111111111
111111111111111111
111111111111111111
222333001111122333
222333000111222333
223333300111223333
233111333111333311
333111333111333111
333111333111333311
003333322111003333
000333222111000333
000333221111100333
111111111111111111
111111111111111111
111111111111111111
""".trimIndent().lines()
}
