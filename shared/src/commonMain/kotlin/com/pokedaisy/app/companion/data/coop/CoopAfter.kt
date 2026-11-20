package com.pokedaisy.app.companion.data.coop

/** One checked change to a game after a joined battle, run in this order by the app ([CoopOpQueue]'s CoopCore calls). */
sealed interface CoopOp {
    data class WriteMon(val slot: Int, val mon: ByteArray) : CoopOp
    data class GiveMon(val mon: ByteArray) : CoopOp
    data class RemoveItem(val item: Int, val count: Int) : CoopOp
    data class TrainerFlag(val trainerId: Int) : CoopOp
    /** The money comes from CoopCore.prize on the receiving game; [single] = a one-trainer battle's (what the game pays for that trainer alone). */
    data class Prize(val trainerId: Int, val single: Boolean = false) : CoopOp
    object WhiteOut : CoopOp
    data class Evolve(val slot: Int) : CoopOp
    object Save : CoopOp
}

/** gBattleOutcome's values, as the link battle's result carries them. */
object CoopAfter {
    const val WON = 1; const val LOST = 2; const val RAN = 4; const val CAUGHT = 7
}
