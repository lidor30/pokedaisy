package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.i18n.tr

/** Main-thread state for the link screen. The host signals completion after its core and saves are closed. */
class LinkPlayFlow<T : Any> {
    enum class Phase { SETUP, STARTING, PLAYING, CLOSING }

    val setup = LinkSetupFlow<T>()
    var phase by mutableStateOf(Phase.SETUP)
        private set
    var confirmingLeave by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
    private var exitAfterClose = false

    fun beginStart(): List<T>? {
        if (phase != Phase.SETUP || !setup.canStart) return null
        setup.clearPicker()
        phase = Phase.STARTING
        return setup.slots.toList()
    }

    fun opened() {
        if (phase == Phase.STARTING) phase = Phase.PLAYING
    }

    fun startFailed(
        message: String = tr("The games couldn't be started. Check that each one is a GBA game that opens on its own."),
        close: (() -> Unit)? = null,
    ) {
        if (phase != Phase.STARTING) return
        notice = message
        phase = if (close == null) Phase.SETUP else Phase.CLOSING
        close?.invoke()
    }

    fun fault() {
        if (phase == Phase.PLAYING) notice = tr("The link stopped. Leave and start it again; your saves are safe.")
    }

    fun back(exit: () -> Unit) {
        when (phase) {
            Phase.SETUP -> exit()
            Phase.PLAYING -> confirmingLeave = !confirmingLeave
            Phase.STARTING, Phase.CLOSING -> Unit
        }
    }

    fun requestLeave() {
        if (phase == Phase.PLAYING) confirmingLeave = true
    }

    fun dismissLeave() { confirmingLeave = false }

    fun leave(close: () -> Unit) {
        if (phase != Phase.PLAYING || !confirmingLeave) return
        confirmingLeave = false
        exitAfterClose = true
        phase = Phase.CLOSING
        close()
    }

    /** Returns whether the host should leave the screen after refreshing its save list. */
    fun closed(savesFinished: Boolean): Boolean {
        if (phase != Phase.CLOSING) return false
        val shouldExit = exitAfterClose
        exitAfterClose = false
        phase = Phase.SETUP
        if (!savesFinished) notice = tr("Couldn't finish updating the games' resume files.")
        return savesFinished && shouldExit
    }
}
