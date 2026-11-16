package com.pokedaisy.app

import com.pokedaisy.app.companion.ui.DeviceDates
import java.text.DateFormat
import java.util.Date

/** [DeviceDates] by java.text in the device's locale (PokeDaisyApp sets it; ui-preview and the screenshot tests too). */
object JvmDeviceDates : DeviceDates {
    private val shortDateTime: DateFormat by lazy { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    // A DateFormat isn't thread-safe; the screens call this from more than one composition.
    override fun shortDateTime(epochMillis: Long): String = synchronized(shortDateTime) { shortDateTime.format(Date(epochMillis)) }
}
