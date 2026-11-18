package com.pokedaisy.app.companion.ui

import kotlin.concurrent.Volatile

/**
 * Dates and times written the device's way (its locale's order, its 12 / 24-hour clock), which only the platform
 * knows: Android's (and the JVM's) java.text, iOS's date formatter. The app's own short dates, in the app's language,
 * are [shortDate]'s.
 */
fun interface DeviceDates {
    /** [epochMillis] as the device's short date and time ("11/14/26, 3:05 PM"). */
    fun shortDateTime(epochMillis: Long): String

    companion object {
        /** Set by the app at start (and by ui-preview / the screenshot tests). */
        @Volatile var current: DeviceDates? = null

        fun get(): DeviceDates = checkNotNull(current) { "DeviceDates.current isn't set" }
    }
}
