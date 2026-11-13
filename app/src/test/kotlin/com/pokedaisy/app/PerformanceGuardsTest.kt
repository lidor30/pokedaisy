package com.pokedaisy.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guards for the crash / CPU / memory regressions of v1.1.0, which no
 * screenshot test could see (they render one screen once). The runtime side is
 * ui-preview's `gradle stress`, which switches tabs rapidly against memory budgets.
 */
class PerformanceGuardsTest {
    private val src = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
    private val appDir = File(src, "com/pokedaisy/app")
    private fun read(path: String) = File(appDir, path).readText()
    private fun kotlinFiles() = src.walkTopDown().filter { it.isFile && it.extension == "kt" }

    @Test
    fun `fonts are only built by PixelTypeface, once per process`() {
        // Building one loads the 1 MB PixelMplusJP and 1.5 MB GalmuriKO; v1.1.0 built one per GbaText,
        // tens of MB per tab switch, and crashed when tabs were switched quickly.
        val builders = listOf("Font.Builder(", "CustomFallbackBuilder(", "Typeface.createFrom", "ComposeFont(", "Font(\"fonts/")
        val offenders = kotlinFiles().filter { it.name != "PixelTypeface.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) -> builders.any { it in line } }.map { (i, _) -> "${f.name}:${i + 1}" }
        }.toList()
        assertTrue("build fonts through pixelFontFamily / gameFontFamily / readingFontFamily (PixelTypeface.kt), not at $offenders", offenders.isEmpty())
        val typeface = read("PixelTypeface.kt")
        assertTrue("pixelFontFamily must return its cached family", "pixelFamily?.let { return it }" in typeface)
        assertTrue("gameFontFamily must reuse a family it already built", "gameFamilies[stamp]?.let { return it }" in typeface)
        assertTrue("readingFontFamily must return its cached family", "readingFamily?.let { return it }" in typeface)
    }

    @Test
    fun `the GL thread lets go of the frame buffer before the core frees it`() {
        // pkDeinit frees the buffer EmulatorView's GL thread uploads from (a native
        // crash with no Java stack when the order is wrong).
        val engine = read("EmulatorEngine.kt")
        val finally = engine.substring(engine.indexOf("} finally {", engine.indexOf("emu loop crashed")))
        assertTrue(
            "EmulatorEngine must run onCoreStopping (the view's unbind) before MgbaCore.pkDeinit",
            finally.indexOf("onCoreStopping?.invoke()") in 0 until finally.indexOf("MgbaCore.pkDeinit()"),
        )
        val activity = read("PokeDaisyActivity.kt")
        // onPause's work (pauseGame: onPause skips it only for the pause around a move to the other screen).
        val onPause = activity.substring(activity.indexOf("private fun pauseGame()")).substringBefore("\n    }\n")
        assertTrue(
            "onPause must unbind the view before stopping the engine",
            onPause.indexOf("view.unbindCoreBlocking()") in 0 until onPause.indexOf("engine.stop"),
        )
        assertTrue("the activity must wire onCoreStopping to the view's unbind", "view.unbindCoreBlocking()" in lambda(activity, "onCoreStopping = {"))
    }

    @Test
    fun `the screen only shows finished frames`() {
        // The core draws into its buffer line by line during pkRunFrame: a GL upload from it
        // then was half one frame, half the next (a tear across the screen while scrolling).
        val view = read("EmulatorView.kt")
        val draw = view.substring(view.indexOf("private fun draw()")).substringBefore("\n        }\n")
        assertTrue("draw() must upload the published copy (ready), not the core's buffer", "val buf = ready" in draw)
        val engine = read("EmulatorEngine.kt")
        assertTrue(
            "EmulatorEngine must publish right after each pkRunFrame",
            // (DS LAYOUT's transition hold decides first whether that frame is shown: DsTransitionHold.)
            "MgbaCore.pkRunFrame()\n                onFrame?.invoke()" in engine ||
                // (DsFrameSkip takes the time the frame ended: a clock read, nothing that delays the publish.)
                Regex("""MgbaCore\.pkRunFrame\(\)\n(\s*val coreEnd = System\.nanoTime\(\)\n)?(\s*//[^\n]*\n)*\s*if \(nds\) stepDsHold\(\)\n\s*onFrame\?\.invoke\(\)""").containsMatchIn(engine),
        )
        assertTrue("the activity must wire onFrame to the view's publish", "view.publishFrame()" in lambda(read("PokeDaisyActivity.kt"), "onFrame = {"))
    }

    @Test
    fun `nothing on the emu loop's per-frame path can end the game by throwing`() {
        val engine = read("EmulatorEngine.kt")
        val frame = engine.substring(engine.indexOf("MgbaCore.pkRunFrame()")).take(600)
        assertTrue("RetroAchievements.onFrame must be inside a try", Regex("""try \{\s+RetroAchievements\.onFrame\(\)""") in frame)
        assertTrue("the AudioTrack is built inside runCatching", "runCatching { buildAudioTrack(" in engine)
    }

    @Test
    fun `main-core reads are guarded against teardown`() {
        // The companion's ROM readers call pkReadBytes from IO threads.
        val jni = File(src.parentFile, "cpp/pokedaisy_jni.c").readText()
        val readAny = jni.substring(jni.indexOf("static bool pk_read_any(")).substringBefore("\n}\n")
        assertTrue("pk_read_any must hold pk_coreLock", "pthread_rwlock_rdlock(&pk_coreLock)" in readAny)
        for (fn in listOf("pkReadBytes", "pkReadInto", "pkRomRead")) {
            val body = jni.substring(jni.indexOf("MgbaCore_$fn(")).substringBefore("\n}\n")
            assertTrue("$fn must hold pk_coreLock", "pthread_rwlock_rdlock(&pk_coreLock)" in body || "pk_read_any(" in body)
        }
        val teardown = jni.substring(jni.indexOf("static void pkTeardown(void) {")).substringBefore("\n}\n")
        assertTrue("pkTeardown must take pk_coreLock for writing", "pthread_rwlock_wrlock(&pk_coreLock)" in teardown)
    }

    /** The body of the lambda [start] opens in [src] (a DS game also hands its bottom screen over in these). */
    private fun lambda(src: String, start: String): String {
        val from = src.indexOf(start)
        if (from < 0) return ""
        var depth = 0
        for (i in from until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(from, i + 1)
            }
        }
        return ""
    }
}
