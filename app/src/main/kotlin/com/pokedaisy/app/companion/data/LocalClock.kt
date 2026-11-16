package com.pokedaisy.app.companion.data

import kotlin.time.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The device's date and time now, in its own time zone: what java.time.LocalTime / LocalDate.now() and Calendar gave
 * (kotlinx-datetime, Kotlin Multiplatform: the iPad build has neither). */
fun localNow(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
