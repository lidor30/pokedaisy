package com.pokedaisy.app

import java.nio.ByteBuffer

/**
 * LINK PLAY's native side (pk_link.c / pk_link_jni.c): two to four GBA games joined by mGBA's own link cable, apart
 * from the player's core ([MgbaCore]). Every call from one thread, [LinkActivity]'s link loop.
 */
object LinkCore {
    init {
        System.loadLibrary("pokedaisy")
    }

    const val MAX_GAMES = 4

    /** Opens and joins the games, in link order (the first is the cable's parent). False if one can't be opened. */
    external fun pkLinkOpen(roms: Array<String>, saves: Array<String>): Boolean
    /** Lets a save in progress finish (at most [maxFrames] frames, still linked), then closes every game. */
    external fun pkLinkClose(maxFrames: Int)
    /** One frame of every game. False on a link fault (every game waiting on another). */
    external fun pkLinkRunFrame(): Boolean
    external fun pkLinkSetKeys(game: Int, mask: Int)
    external fun pkLinkVideoWidth(game: Int): Int
    external fun pkLinkVideoHeight(game: Int): Int
    /** The game's last frame into [dst] (direct, width * height * 4 bytes) as opaque RGBA. */
    external fun pkLinkCopyVideo(game: Int, dst: ByteBuffer): Boolean
    /** The game's audio since it was last read, interleaved stereo; the frames read. The others' is dropped. */
    external fun pkLinkReadAudio(game: Int, out: ShortArray): Int
    external fun pkLinkSetAudioRate(rate: Double)
    external fun pkLinkSampleRate(): Int
    /** A game is writing its save right now. */
    external fun pkLinkSaving(): Boolean
    /** Each game's link mode and the cable's state, for the log. */
    external fun pkLinkStatus(): String
}
