package com.pokedaisy.app.companion

import kotlinx.coroutines.flow.MutableStateFlow

/** A battery reading: [percent] 0..100, [charging] while plugged in and filling (or full). */
data class BatteryStatus(val percent: Int, val charging: Boolean)

/**
 * The device's battery, for the companion's SETTINGS title and the top
 * screen's status bar. PokeDaisyActivity
 * feeds it from the sticky ACTION_BATTERY_CHANGED broadcast; null until the
 * first reading (previews set their own).
 */
object DeviceBattery {
    val status = MutableStateFlow<BatteryStatus?>(null)
}
