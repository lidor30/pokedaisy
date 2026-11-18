package com.pokedaisy.app

import java.io.File

/**
 * STEADY's clip as last told to the activity's [FfMusicPlayer] ([EmulatorEngine.onFfMusicChanged] fires only when it
 * changes). [forget] after the player let go of it behind the engine's back: the activity releases the player as the
 * screen goes off (pauseGame) while the engine only holds (EmulatorEngine.sleep), so on wake the same clip has to be
 * told again - else FF stayed silent after the AYN Thor's lid was opened.
 */
internal class SteadyClipNotice {
    private var last: File? = null

    /** True when [clip] differs from the one last told, which it now is. */
    fun changed(clip: File?): Boolean {
        if (clip == last) return false
        last = clip
        return true
    }

    fun forget() {
        last = null
    }
}
