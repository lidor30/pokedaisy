package com.pokedaisy.app

import android.content.Context

/** The app's DevicePerformance reads ActivityManager and /sys: the previews' desktop is never a weak device. */
object DevicePerformance {
    fun looksWeak(context: Context): Boolean = false
}
