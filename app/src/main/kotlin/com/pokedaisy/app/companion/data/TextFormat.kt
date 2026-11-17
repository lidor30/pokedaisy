package com.pokedaisy.app.companion.data

/*
 * The few printf-style formats the data code used (`"%08X".format(...)` and co.), in plain Kotlin: String.format is
 * the JDK's, and this code is shared with the iPad app. TextFormatTest checks each against String.format.
 */

/** This value's bits in hex (as `%x` prints an Int, negatives as their unsigned bits), zero-padded to [width]. */
internal fun Int.hex(width: Int = 0, upper: Boolean = false): String = toUInt().toString(16).padStart(width, '0').let { if (upper) it.uppercase() else it }

/** This value's bits in hex (as `%x` prints a Long), zero-padded to [width]. */
internal fun Long.hex(width: Int = 0, upper: Boolean = false): String = toULong().toString(16).padStart(width, '0').let { if (upper) it.uppercase() else it }

/** Two lower-case hex digits a byte, as `joinToString("") { "%02x".format(it) }` gave. */
internal fun ByteArray.hex(upper: Boolean = false): String = joinToString("") { (it.toInt() and 0xFF).hex(2, upper) }

/** This number zero-padded to [width] digits (`%02d`). Public: the screens use it too. */
fun Int.pad(width: Int): String = if (this < 0) "-" + (-toLong()).toString().padStart(width - 1, '0') else toString().padStart(width, '0')

internal fun Long.pad(width: Int): String = if (this < 0) "-" + (-this).toString().padStart(width - 1, '0') else toString().padStart(width, '0')

/** [decimals] digits after the point, rounded half up (`%.2f`), always with a '.'. Public: the screens use it too. */
fun Double.fixed(decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val neg = this < 0
    val units = kotlin.math.floor(kotlin.math.abs(this) * scale + 0.5).toLong()
    val whole = (units / scale).toString()
    val frac = if (decimals == 0) "" else "." + (units % scale).toString().padStart(decimals, '0')
    return (if (neg && units != 0L) "-" else "") + whole + frac
}
