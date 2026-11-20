package com.pokedaisy.app.companion

/**
 * WHICH GAME IS THIS? - the player's answers for a ROM the app doesn't know (BEST EFFORT,
 * [com.pokedaisy.app.companion.data.BestEffort]): [pick] a game by candidate id, [none] (NONE OF
 * THESE: NOT SUPPORTED), [keep] a match its party doubted, and [seen] for the match notice.
 */
class GamePickActions(
    val pick: (String) -> Unit,
    val none: () -> Unit,
    val keep: () -> Unit,
    val seen: () -> Unit,
)
