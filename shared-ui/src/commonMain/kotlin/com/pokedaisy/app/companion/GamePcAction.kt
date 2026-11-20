package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.ItemUseOutcome
import com.pokedaisy.app.companion.data.ItemUseResult

/** Routes Game Boy and GBA PC access. Game Boy already hands over the screen when its script takes over. */
class GamePcAction(
    private val gameBoy: () -> GameBoyFieldActions?,
    private val gba: ItemUse,
    private val handedOver: () -> Unit,
    private val pcUsed: () -> Unit,
) {
    fun available() = gameBoy()?.available == true || gba.canPc()

    fun open(done: (ItemUseOutcome) -> Unit) {
        val gb = gameBoy()?.takeIf { it.available }
        val completed: (ItemUseOutcome) -> Unit = { result ->
            if (result.result == ItemUseResult.USED) {
                if (gb == null) handedOver()
                pcUsed()
            }
            done(result)
        }
        if (gb != null) gb.openPc(completed) else gba.openPc(completed)
    }
}
