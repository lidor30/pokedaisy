package com.pokedaisy.app.companion.data

/** What [swapPartySlots] did. */
enum class PartySwapResult { SWAPPED, NOT_NOW, IN_BATTLE, FAILED }

/**
 * Party slots [a] and [b] traded, the way the game's own party menu trades them (SwapPartyPokemon: the two records
 * exchanged whole, nothing else - a Pokémon's mail and everything about it live in its record), in a game whose party is
 * [count] records of [stride] bytes at [party]. Read back, both put back if the write didn't take. Emu thread.
 */
fun swapPartySlots(g: GameCalls, party: Long, stride: Int, count: Int, a: Int, b: Int): PartySwapResult {
    if (a == b || a !in 0 until count || b !in 0 until count) return PartySwapResult.FAILED
    val atA = party + a.toLong() * stride
    val atB = party + b.toLong() * stride
    val first = g.read(atA, stride)
    val second = g.read(atB, stride)
    if (first.size != stride || second.size != stride) return PartySwapResult.FAILED
    val done = g.write(atA, second) && g.write(atB, first) &&
        g.read(atA, stride).contentEquals(second) && g.read(atB, stride).contentEquals(first)
    if (done) return PartySwapResult.SWAPPED
    g.write(atA, first)
    g.write(atB, second)
    return PartySwapResult.FAILED
}
