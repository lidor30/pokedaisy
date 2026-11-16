package com.pokedaisy.app.companion.data

/** The existing diff ring samples every two frames: 600 entries keep about 20 seconds at normal speed. */
object RewindPolicy {
    fun capacity(enabled: Boolean, supported: Boolean = true): Int = if (enabled && supported) 600 else 0
}
