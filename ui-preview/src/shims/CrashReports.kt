package com.pokedaisy.app

import android.content.Context

// Desktop stand-in for the app's CrashReports.kt (Firebase can't load here): the
// Settings row and the Library's one-time ask show as in a build that can send reports.
object CrashReports {
    val available: Boolean get() = true
    fun shouldAsk(context: Context, prefs: Prefs, now: Long = System.currentTimeMillis()): Boolean = false
    fun setEnabled(prefs: Prefs, on: Boolean) { prefs.crashReportsEnabled = on }
    fun gameStarted(context: Context, rom: java.io.File, romData: java.io.File, launchedFrom: String?) {}
    fun gameIdentified(kind: String, how: String? = null) {}
    fun breadcrumb(text: String) {}
    const val LAUNCHED_FROM_LIBRARY = "library"
}
