package com.pokedaisy.app

/**
 * SETTINGS > SOUND > AUDIO EFFECTS ([Prefs.audioEffects], off): the game's sound through the device's audio effects.
 * A low-latency track skips them, so an equalizer (JamesDSP, GitHub #336) never touched the game; on, the tracks are built
 * the way a media player's are ([AudioEffectsTrack]). A track keeps what it was built with, so the game takes this up the
 * next time it starts.
 */
object AudioEffects {
    @Volatile var on = false
        private set

    fun load(prefs: Prefs) {
        on = prefs.audioEffects
    }
}
