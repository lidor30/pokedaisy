package com.pokedaisy.app

import android.content.Context
import java.io.File

/**
 * Features merged but not shown to players yet. Each is off unless one of these turns it on:
 *
 * - **the build**: `./gradlew assembleDebug -Ppokedaisy.saveEditor=true`, or `pokedaisy.saveEditor=true` in the untracked
 *   local.properties (app/build.gradle.kts writes it into [BuildConfig]);
 * - **a marker file on the device**, for an APK already installed (any build, no reinstall):
 *   `adb shell mkdir -p /sdcard/Android/data/com.pokedaisy.app/files/features` then
 *   `adb shell touch /sdcard/Android/data/com.pokedaisy.app/files/features/save_editor`; delete the file to turn it off.
 *   Read each time a screen asks, so it takes effect on the next library menu.
 */
object FeatureFlags {
    /** PC BOXES (the library menu's save editor: boxes, TRADE, the bag, items and moves). */
    fun saveEditor(context: Context): Boolean = BuildConfig.FEATURE_SAVE_EDITOR || marker(context, "save_editor")

    /**
     * ScreenScraper: game media for the library and the launcher, Settings > ScreenScraper, setup's GAME MEDIA step and
     * the update's "set it up?" popup. Off until the app has its own developer login (every call needs one); marker
     * `screenscraper`. Read into [ScreenScraperClient.enabled] when the process and the library start.
     */
    fun screenScraper(context: Context): Boolean = BuildConfig.FEATURE_SCREENSCRAPER || marker(context, "screenscraper")

    /** CO-OP: the link to a paired partner, its Settings group and invite links. Off until it ships; marker `coop`. */
    fun coop(context: Context): Boolean = BuildConfig.FEATURE_COOP || marker(context, "coop")

    private fun marker(context: Context, name: String): Boolean =
        runCatching { File(File(context.getExternalFilesDir(null) ?: context.filesDir, "features"), name).isFile }.getOrDefault(false)
}
