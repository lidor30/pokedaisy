package com.pokedaisy.app.game

/** Transport only; callers use the game thread between frames. The native frame loop owns flight ticks. */
interface FlightCore {
    fun configureFlight(words: IntArray?)
    fun flightTakeOff(): Int
    fun flightLand()
    fun setFlightRoute(directions: ByteArray): Int
    fun setFlightArea(bits: ByteArray?)
    val flightAreaTurned: Boolean
    val flightState: Int

    companion object {
        const val IDLE = 0
        const val DISMOUNT = 4
        const val OK = 0
        const val NOT_NOW = -3
        const val NOT_ON_FOOT = -4
    }
}
