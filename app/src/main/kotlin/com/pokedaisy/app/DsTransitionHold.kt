package com.pokedaisy.app

/**
 * DS LAYOUT's transition hold (one screen; the players' "loading takes too long", 2026-11-09): from the moment a DS game
 * starts going from one screen to the next on the way into or out of a menu (the X menu to the party, the party back to
 * the field) until the new screen has been fully lit for [LIT_FRAMES], both screens keep the last picture from before
 * the fade and the game runs flat out behind them (EmulatorEngine.dsHold). Nothing in between is worth watching: a fade
 * to black, black while the game loads, the new screen fading or sliding in.
 *
 * HeartGold's, headless (nds_dump `trace`, Goldenrod City, X menu > POKéMON and back): into the party ~12 frames of a
 * master-brightness fade, ~32 of black, ~16 of a blank touch screen, then ~12 of its rows sliding in; back out ~17 frames
 * of its own, a ~12 frame fade, ~78 of black, a ~15 frame fade in. Black 2's (Humilau City, the same): a 4 frame fade,
 * ~32 of black, ~12 fading in (its party's top screen stays one colour); back out ~100 of black. DsTransitionHoldTest
 * replays both.
 *
 * Fed once a frame, right after it ran and before its picture is shown, so the frame the fade starts on is never seen.
 */
class DsTransitionHold {
    /** Both screens are held. */
    var holding = false
        private set

    /** Frames run since the hold began. */
    var frames = 0
        private set

    private var lit = 0
    private var halfLit = 0
    private var lastFade = 0
    private var stillFrames = 0
    // Released without the game coming back (a shade that stayed, the cap): not again until the screens are lit.
    private var blocked = false

    /**
     * One frame: [fade] is MgbaCore.pkNdsFade's, [state] the field read (its DARK_TOP / DARK_BOTTOM / BATTLE bits),
     * [inFlow] whether the game wants its touch screen or a menu screen is up (EmulatorEngine's flows, which hold through
     * the black), [on] whether one screen's DS stage is up. Returns [holding].
     */
    fun step(fade: Int, state: Int, inFlow: Boolean, on: Boolean): Boolean {
        val darkTop = state and MgbaCore.FIELD_DARK_TOP != 0
        val darkBottom = state and MgbaCore.FIELD_DARK_BOTTOM != 0
        val black = darkTop && darkBottom
        val fading = fade != 0
        if (!fading && !black) blocked = false
        if (!on) return release()
        if (!holding) {
            // A battle's own fades (its bag, its party, a move's flash) are its business: DS LAYOUT's battle rules.
            if (blocked || !inFlow || state and MgbaCore.FIELD_BATTLE != 0 || !(fading || black)) return false
            holding = true
            frames = 0
            lit = 0
            halfLit = 0
            lastFade = fade
            stillFrames = 0
            return true
        }
        frames++
        // A shade that holds still part way down (a page's own, not a fade on its way): not a transition.
        stillFrames = if (fading && fade == lastFade && partial(fade)) stillFrames + 1 else 0
        lastFade = fade
        if (stillFrames >= STILL_FRAMES || frames >= MAX_FRAMES) {
            blocked = true
            return release()
        }
        // The touch screen lit, no fade left on either: what's next is up (a page may leave its top screen one colour -
        // Black 2's party does, the whole time it's up).
        lit = if (!fading && !darkBottom) lit + 1 else 0
        // Only the top screen lit (a page with nothing on its touch screen): lit enough after a while.
        halfLit = if (!fading && !black) halfLit + 1 else 0
        if (lit >= LIT_FRAMES || halfLit >= HALF_LIT_FRAMES) return release()
        return true
    }

    /** Ends a hold now (the game closed, the stage changed). */
    fun release(): Boolean {
        holding = false
        return false
    }

    private fun partial(fade: Int): Boolean = (fade and 0xFF) in 1..15 || (fade shr 8 and 0xFF) in 1..15

    companion object {
        /** Frames the touch screen must be lit (and no fade running) before the hold lets go: HeartGold's party slides its
         * rows in for ~12 after its first lit frame. */
        const val LIT_FRAMES = 12

        /** ...or this many with only the top screen lit. */
        const val HALF_LIT_FRAMES = 45

        /** Frames a fade may stay at one step part way before it counts as a page's shade, not a fade. */
        const val STILL_FRAMES = 20

        /** The most a hold runs (~10 s of game time). */
        const val MAX_FRAMES = 600

        /** While held, only every this many frames is drawn (the rest aren't seen): a DS frame's drawing is much of its cost. */
        const val DRAW_EVERY = 4
    }
}
