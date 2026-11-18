package com.pokedaisy.app.companion.ui.theme

import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/** Today, for the themes' dates: the device's; ui-preview / tests move it into a limited-time theme's window. */
object ThemeClock {
    var today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
}

/**
 * When a limited-time theme ([ThemeSpec.season]) can be picked: a few days before its holiday to a few after, by the
 * device's own date. Lunar New Year, which moves with the Chinese calendar, comes from a table of its
 * dates (checked against Wikipedia's Chinese calendar table); a year past the table has no window, so the
 * theme stays hidden until the table is extended.
 */
enum class Season {
    LUNAR_NEW_YEAR, VALENTINES, ST_PATRICKS, EASTER, HALLOWEEN, CHRISTMAS, NEW_YEAR;

    /** This season's window around the holiday of [year] (the year the holiday itself falls in), or null. */
    fun window(year: Int): ClosedRange<LocalDate>? = when (this) {
        // Spring Festival: a week of lead-up, through the Lantern Festival (day 15).
        LUNAR_NEW_YEAR -> LUNAR_NEW_YEAR_DAYS[year]?.let { it.minus(DatePeriod(days = 7))..it.plus(DatePeriod(days = 15)) }
        VALENTINES -> LocalDate(year, Month.FEBRUARY, 7)..LocalDate(year, Month.FEBRUARY, 15)
        ST_PATRICKS -> LocalDate(year, Month.MARCH, 10)..LocalDate(year, Month.MARCH, 18)
        EASTER -> easter(year).let { it.minus(DatePeriod(days = 7))..it.plus(DatePeriod(days = 2)) }
        HALLOWEEN -> LocalDate(year, Month.OCTOBER, 17)..LocalDate(year, Month.NOVEMBER, 3)
        CHRISTMAS -> LocalDate(year, Month.DECEMBER, 1)..LocalDate(year, Month.DECEMBER, 27)
        // New Year's Day of [year]: from the last days of the year before.
        NEW_YEAR -> LocalDate(year - 1, Month.DECEMBER, 28)..LocalDate(year, Month.JANUARY, 4)
    }

    /** The window [date] falls in, or null. Windows can cross the new year, so the years around it are checked too. */
    fun windowOn(date: LocalDate): ClosedRange<LocalDate>? =
        (date.year - 1..date.year + 1).firstNotNullOfOrNull { y -> window(y)?.takeIf { date in it } }

    companion object {
        /** Western Easter Sunday of [year] (the anonymous Gregorian computus). */
        fun easter(year: Int): LocalDate {
            val a = year % 19
            val b = year / 100
            val c = year % 100
            val d = b / 4
            val e = b % 4
            val f = (b + 8) / 25
            val g = (b - f + 1) / 3
            val h = (19 * a + b - d - g + 15) % 30
            val i = c / 4
            val k = c % 4
            val l = (32 + 2 * e + 2 * i - h - k) % 7
            val m = (a + 11 * h + 22 * l) / 451
            val month = (h + l - 7 * m + 114) / 31
            val day = (h + l - 7 * m + 114) % 31 + 1
            return LocalDate(year, month, day)
        }

        /** Lunar New Year's Day (the first day of the Chinese calendar's year). */
        private val LUNAR_NEW_YEAR_DAYS = mapOf(
            2026 to LocalDate(2026, 2, 17), 2027 to LocalDate(2027, 2, 6), 2028 to LocalDate(2028, 1, 26),
            2029 to LocalDate(2029, 2, 13), 2030 to LocalDate(2030, 2, 3), 2031 to LocalDate(2031, 1, 23),
            2032 to LocalDate(2032, 2, 11), 2033 to LocalDate(2033, 1, 31), 2034 to LocalDate(2034, 2, 19),
            2035 to LocalDate(2035, 2, 8),
        )
    }
}
