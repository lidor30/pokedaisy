package com.pokedaisy.app.companion

/**
 * The one ask for support: after [FIRST_ASK] of play (a game running, not paused or asleep), the Library shows the support
 * window ([com.pokedaisy.app.companion.ui.SupportWindow]) once. REMIND ME LATER asks again [REMIND_AFTER] of play later;
 * NO THANKS or a site picked ends it.
 */
object SupportAsk {
    const val HOUR = 60 * 60 * 1000L
    const val FIRST_ASK = 5 * HOUR
    const val REMIND_AFTER = 5 * HOUR

    /** Where each tile goes: the same pages as the website's footer (website/src/lib/links.js); the names are the sites' own, not translated. */
    enum class Site(val label: String, val url: String, /** A tight tile's name (the RG DS). */ val shortLabel: String = label) {
        COFFEE("BUY ME A COFFEE", "https://buymeacoffee.com/lidor30g", "COFFEE"),
        PATREON("PATREON", "https://www.patreon.com/cw/pokedaisy"),
        KOFI("KO-FI", "https://ko-fi.com/pokedaisy"),
    }

    /** Whether the window is due: [played] has reached [askAt] (Prefs: [FIRST_ASK] until a REMIND ME LATER moves it), never once [done]. */
    fun due(played: Long, askAt: Long, done: Boolean): Boolean = !done && played >= askAt

    /** REMIND ME LATER's next time: [REMIND_AFTER] more of play from now. */
    fun remindAt(played: Long): Long = played + REMIND_AFTER
}
