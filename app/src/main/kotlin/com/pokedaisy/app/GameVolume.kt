package com.pokedaisy.app

/**
 * SETTINGS > SOUND: the game's loudness under the device's own volume - [Prefs.gameVolume] (100 = as loud as the device
 * plays it, the default) and [Prefs.gameMuted] (MUTE GAME, also the MUTE hotkey). One gain for everything the game
 * sounds like: the core's audio track ([EmulatorEngine], DS STEADY music included), FF MUSIC's STEADY clips
 * ([FfMusicPlayer]) and the game's own click / fanfare on the companion ([GameClickSound]). Each reads [gain] when it
 * plays or, the engine, every frame; [listeners] hear a change at once (a clip already playing).
 */
object GameVolume {
    /** GAME VOLUME's slider moves in steps of this many percent (a drag, LEFT / RIGHT). */
    const val STEP = 5

    @Volatile var percent: Int = 100
        private set
    @Volatile var muted: Boolean = false
        private set

    /** The amplitude to play at, 0..1: the percent squared (an audio taper, so 50% sounds about half as loud, not
     *  barely quieter), 0 while muted. */
    @Volatile var gain: Float = 1f
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun load(prefs: Prefs) = set(prefs.gameVolume, prefs.gameMuted)

    fun set(percent: Int, muted: Boolean) {
        this.percent = percent.coerceIn(0, 100)
        this.muted = muted
        val p = this.percent / 100f
        gain = if (muted) 0f else p * p
        for (l in listeners) runCatching { l() }
    }
}
