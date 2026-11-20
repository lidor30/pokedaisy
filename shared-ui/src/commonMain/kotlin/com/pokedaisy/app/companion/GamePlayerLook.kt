package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.Logs
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.game.PlayerLookCore
import kotlinx.atomicfu.atomic
import okio.Buffer

/** The running game's appearance overlay. Choices can arrive from UI or asset workers. Only the
 * game thread changes the core or handles palette restoration after loading a state. */
class GamePlayerLook(private val core: PlayerLookCore) {
    private val requested = atomic<PlayerLook.Patch?>(null)
    private var attempted: PlayerLook.Patch? = null
    private var installed: PlayerLook.Patch? = null
    private var refreshFrames = 0
    val ride get() = installed?.ride?.takeIf { installed?.blocks?.isNotEmpty() == true }

    fun select(patch: PlayerLook.Patch?) {
        while (true) {
            val previous = requested.value
            if (patch === previous || patch?.sameAs(previous) == true) return
            if (requested.compareAndSet(previous, patch)) return
        }
    }

    fun service(force: Boolean = false): Boolean {
        val patch = requested.value
        if (!force && patch === attempted) return false
        attempted = patch
        val blocks = patch?.blocks.orEmpty()
        val bytes = Buffer()
        blocks.forEach { bytes.write(it.data) }
        val result = core.replaceLookBlocks(
            IntArray(blocks.size) { 0x08000000 + blocks[it].off },
            IntArray(blocks.size) { blocks[it].data.size },
            IntArray(blocks.size) { blocks[it].crc },
            bytes.readByteArray(),
        )
        installed = patch.takeIf { result >= 0 }
        if (result < 0) Logs.i("player look: the ROM didn't match, left as it is")
        settle()
        return true
    }

    fun settle() {
        refreshFrames = 600
        for (palette in installed?.palettes.orEmpty()) {
            val bytes = Buffer()
            palette.from.forEach { bytes.write(it) }
            core.repaletteLook(bytes.readByteArray(), palette.to)
        }
    }

    fun fieldFrame() {
        if (refreshFrames == 0) return
        if (core.refreshLook(installed?.playerPal)) {
            refreshFrames = 0
        } else if (--refreshFrames == 0) {
            Logs.i("player look: the sprite's palette never settled")
        }
    }
}
