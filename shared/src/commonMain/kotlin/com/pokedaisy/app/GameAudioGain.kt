package com.pokedaisy.app

/** Mixer amplitude for the GAME VOLUME slider. Mute preserves the stored percentage. */
fun gameAudioGain(percent: Int, muted: Boolean): Float {
    if (muted) return 0f
    val fraction = percent.coerceIn(0, 100) / 100f
    return fraction * fraction
}
