package com.pokedaisy.app.companion.data

/**
 * The FF-music cache key: the song the game's background-music player is
 * really playing, read from the m4a sound engine every Gen 3 game and hack
 * shares - so wild / trainer / gym battles, surfing, cutscenes all get their
 * own clip, for any game, with no per-game song table to get wrong (a
 * hand-picked "battle song" once made FireRed play Emerald's trainer theme).
 *
 * `SOUND_INFO_PTR` (0x03007FF0) -> SoundInfo; its `musicPlayerHead` chains
 * every MusicPlayerInfo through `musicPlayerNext`, newest first. m4aSoundInit
 * opens the BGM player first, so it's the tail. Its `songHeader` - a ROM
 * pointer - is the key (clips are cached per ROM, so it's stable).
 */
object FfMusicKey {
    private const val SOUND_INFO_PTR = 0x03007FF0L
    private const val MUSIC_PLAYER_HEAD = 0x24 // SoundInfo.musicPlayerHead
    private const val SONG_HEADER = 0x00       // MusicPlayerInfo.songHeader
    private const val STATUS = 0x04            // MusicPlayerInfo.status: low 16 bits = tracks playing
    private const val NEXT = 0x3C              // MusicPlayerInfo.musicPlayerNext
    private const val MAX_PLAYERS = 12         // BGM, 3 SE, 2 cries in the decomps

    /** The key for what [r]'s BGM player is playing, or null (nothing playing / unreadable). */
    fun current(r: MemoryReader): String? = runCatching {
        val bgm = bgmPlayer(r) ?: return null
        if (u32(r, bgm + STATUS) and 0xFFFF == 0L) return null
        val header = u32(r, bgm + SONG_HEADER)
        if (!Gfx.inRom(header)) return null
        "song_" + (header - 0x08000000L).hex(7)
    }.getOrNull()

    /** The BGM player's MusicPlayerInfo address in [r] (the chain's tail), or null. */
    fun bgmPlayer(r: MemoryReader): Long? = runCatching {
        val info = u32(r, SOUND_INFO_PTR)
        if (!inRam(info)) return null
        var p = u32(r, info + MUSIC_PLAYER_HEAD)
        var bgm = 0L
        var n = 0
        while (p != 0L) {
            if (!inRam(p) || ++n > MAX_PLAYERS) return null
            bgm = p
            p = u32(r, p + NEXT)
        }
        bgm.takeIf { it != 0L }
    }.getOrNull()

    private fun u32(r: MemoryReader, addr: Long): Long = Gfx.u32(r.readCoreMemory(addr, 4), 0)

    fun inRam(a: Long) = a in 0x02000000L until 0x02040000L || a in 0x03000000L until 0x03008000L
}
