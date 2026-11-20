package com.pokedaisy.app.companion.data

import org.junit.Assert.assertTrue

/**
 * Shared helpers for the per-game Native*DecodeTest classes. Each game gets
 * its OWN test class (not shared test methods in one class) deliberately:
 * readNativeTelemetry's bag-read throttle (bagTick/bagValid/lastBag in
 * NativeReader.kt) is file-level mutable state, shared process-wide - fine in
 * the real app (one NativeConfig per running process) but it silently leaks
 * a PREVIOUS test's item list into the next one if multiple games' tests run
 * in the same JVM. build.gradle.kts sets `forkEvery = 1` on the test task so
 * Gradle restarts the JVM between classes, resetting that state - splitting
 * by class is what actually makes that isolation apply per-game.
 */
fun decodeNative(key: String, cfg: NativeConfig): Telemetry {
    // Several fixtures share a class (SoulGold's, Radical Red's): start each decode clean.
    resetNativeBagCache()
    return readNativeTelemetry(FixtureMemoryReader.load(key), cfg)
}

fun assertPocketsInRange(t: Telemetry) {
    t.items.forEach {
        assertTrue("pocket ${it.pocket} out of the valid 0..4 range", it.pocket in 0..4)
    }
}
