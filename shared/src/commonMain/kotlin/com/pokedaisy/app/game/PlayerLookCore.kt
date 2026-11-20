package com.pokedaisy.app.game

/** Game-thread operations for applying appearance patches to the running GBA cart. */
interface PlayerLookCore {
    fun replaceLookBlocks(addresses: IntArray, lengths: IntArray, crcs: IntArray, data: ByteArray): Int
    fun repaletteLook(from: ByteArray, to: ByteArray): Int
    fun refreshLook(palette: ByteArray?): Boolean
}
