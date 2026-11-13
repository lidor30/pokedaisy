package com.pokedaisy.app.companion.data

/**
 * A Game Boy Pokémon game's music, for STEADY FF music (the GB twin of [FfMusicKey] /
 * [M4aSongs]; Crystal's engine too, [GEN2_MUSIC_CRYSTAL]): Gen 1 plays a song through its audio engine's four music channels
 * (wChannelSoundIDs CHAN1-4; CHAN5-8 are sound effects and cries), from one of
 * several audio banks (wAudioROMBank), so a song is its (bank, id) - song ids repeat
 * across banks. The render core starts one by calling the game's own PlayMusic
 * (a = id, c = bank) from a parked main loop: the VBlank handler runs the sound
 * engine every frame by itself.
 *
 * Addresses are pret/pokeyellow's symbols (its build is byte-identical to retail).
 */
data class Gen1MusicTables(
    val sha1: String,
    /** PlayMusic, in bank 0. */
    val playMusic: Int,
    /** A `jr @` (18 FE) in bank 0 that nothing jumps to, for the render core's parked main loop - or two WRAM bytes the
     * parked game never touches, where the park (pkRenderGbPark) writes one. */
    val spin: Int,
    val audioRomBank: Long,       // wAudioROMBank
    val channelSoundIds: Long,    // wChannelSoundIDs: 8 bytes, CHAN1 first
    /** The music channels' state Gen1LoopWatch compares frame to frame: [start, start + length) ranges. */
    val channelState: List<Pair<Long, Int>>,
    /** Every song, as (bank, id): the background pass renders each once. */
    val songs: List<Pair<Int, Int>>,
    /** Crystal's engine (pret/pokecrystal): a song is its id alone (bank 0 in the key), read off the first music channel
     * that's on ([musicChannelFlags]: each one's Flags1, bit 0; the channel's MusicID is the struct's first word) - not
     * `wMusicID`, which a save's map music comes back without (it read 0x08 over ROUTE 29's channels on the Thor) - and
     * PlayMusic takes it in DE (the render core's call puts the id there). */
    val crystal: Boolean = false,
    val musicChannelFlags: List<Long> = emptyList(),
    /** How far before a channel's flags byte its MusicID sits: 3 in Crystal's struct, 7 in Polished Crystal's. */
    val musicIdBack: Int = 3,
)

val GEN1_MUSIC_YELLOW = Gen1MusicTables(
    sha1 = TelemetrySampler.YELLOW_SHA1,
    playMusic = 0x2211,
    spin = 0x1757,                // inside PlaceNextChar's `jp z, $18E3`: its operand's 18 FE
    audioRomBank = 0xC0EFL,
    channelSoundIds = 0xC026L,
    channelState = listOf(
        0xC006L to 8,             // wChannelCommandPointers, CHAN1-4
        0xC016L to 8,             // wChannelReturnAddresses
        0xC026L to 4,             // wChannelSoundIDs
        0xC0B6L to 4,             // wChannelNoteDelayCounters
        0xC0BEL to 4,             // wChannelLoopCounters
    ),
    // constants/music_constants.asm: (address - SFX_Headers_N) / 3 in each song's bank.
    songs = listOf(
        2 to 186, 2 to 189, 2 to 192, 2 to 195, 2 to 199, 2 to 202, 2 to 205, 2 to 208, 2 to 212, 2 to 216,
        2 to 219, 2 to 222, 2 to 225, 2 to 229, 2 to 232, 2 to 235, 2 to 239, 2 to 243, 2 to 247, 2 to 251,
        8 to 234, 8 to 237, 8 to 240, 8 to 243, 8 to 246, 8 to 249, 8 to 252,
        0x1F to 195, 0x1F to 199, 0x1F to 202, 0x1F to 205, 0x1F to 208, 0x1F to 210, 0x1F to 214, 0x1F to 217,
        0x1F to 220, 0x1F to 223, 0x1F to 227, 0x1F to 231, 0x1F to 235, 0x1F to 239, 0x1F to 242, 0x1F to 245,
        0x1F to 248, 0x1F to 251,
        0x20 to 153, 0x20 to 156, 0x20 to 159, 0x20 to 163,
    ),
)

/**
 * The German, French, Italian and Spanish Yellows: English's sound engine, songs and audio RAM; their own
 * bank-0 code moved PlayMusic, and French PlaceNextChar's `jp z` (scripts/port_gen1.py, the same instruction
 * bytes in each).
 */
val GEN1_MUSIC_YELLOW_DE = GEN1_MUSIC_YELLOW.copy(sha1 = TelemetrySampler.YELLOW_DE_SHA1, playMusic = 0x2216)
val GEN1_MUSIC_YELLOW_FR = GEN1_MUSIC_YELLOW.copy(sha1 = TelemetrySampler.YELLOW_FR_SHA1, playMusic = 0x220D, spin = 0x1754)
val GEN1_MUSIC_YELLOW_IT = GEN1_MUSIC_YELLOW.copy(sha1 = TelemetrySampler.YELLOW_IT_SHA1)
val GEN1_MUSIC_YELLOW_ES = GEN1_MUSIC_YELLOW.copy(sha1 = TelemetrySampler.YELLOW_ES_SHA1, playMusic = 0x2210)

/**
 * Red and Blue (pret/pokered: the same addresses and songs in both): Yellow's audio RAM, their own PlayMusic and songs
 * (bank 0x1F's ids from INTRO_BATTLE on are one lower than Yellow's; no bank 0x20). Their bank 0 holds no `18 FE` at
 * all, so the render core parks on WRAM: wUnusedSimulatedJoypadStatesMask (written by the main loop, never read) and
 * the byte after it. Checked headless (mgba_dump gbpark / gbcall / gbloop): ROUTES1 loops at 22.9 s, the wild battle
 * at 31.3 s after a 13.1 s intro - Yellow's numbers.
 */
val GEN1_MUSIC_RED = GEN1_MUSIC_YELLOW.copy(
    sha1 = TelemetrySampler.RED_SHA1,
    playMusic = 0x23A1,
    spin = 0xCD39,
    songs = listOf(
        2 to 186, 2 to 189, 2 to 192, 2 to 195, 2 to 199, 2 to 202, 2 to 205, 2 to 208, 2 to 212, 2 to 216,
        2 to 219, 2 to 222, 2 to 225, 2 to 229, 2 to 232, 2 to 235, 2 to 239, 2 to 243, 2 to 247, 2 to 251,
        8 to 234, 8 to 237, 8 to 240, 8 to 243, 8 to 246, 8 to 249, 8 to 252,
        0x1F to 195, 0x1F to 199, 0x1F to 202, 0x1F to 205, 0x1F to 208, 0x1F to 210, 0x1F to 214, 0x1F to 217,
        0x1F to 220, 0x1F to 224, 0x1F to 228, 0x1F to 232, 0x1F to 236, 0x1F to 240, 0x1F to 243, 0x1F to 246,
        0x1F to 249, 0x1F to 252,
    ),
)
val GEN1_MUSIC_BLUE = GEN1_MUSIC_RED.copy(sha1 = TelemetrySampler.BLUE_SHA1)

/**
 * PureRed / PureBlue v2.7.6 (pureRGB's pokered.sym, the same in both): Red's sound engine and audio RAM, its own PlayMusic
 * (0x21D1), and its songs: Red's, but EVOLUTION where Red has SAFARI ZONE and bank 0x08's / 0x1F's ids from the gym battle
 * / CINNABAR MANSION on shifted (constants/music_constants.asm). Its added songs (bank 0x36's RONALD and the others it starts
 * its own way) aren't in the list: heard live, they don't start from PlayMusic and play SPED-UP. The park: 0x0DAE, the
 * `18 FE` inside `jr .x` / `cp $ff` in bank 0 (the jr's operand: nothing jumps there). Checked headless (mgba_dump gbpark /
 * gbcall / gbloop): ROUTES1 loops at 22.9 s, the wild battle at 31.3 s after a 13.1 s intro - Red's numbers.
 */
val GEN1_MUSIC_PURE_RED = GEN1_MUSIC_RED.copy(
    sha1 = TelemetrySampler.PURE_RED_V2_7_6_SHA1,
    playMusic = 0x21D1,
    spin = 0x0DAE,
    songs = listOf(
        2 to 186, 2 to 189, 2 to 192, 2 to 195, 2 to 199, 2 to 202, 2 to 205, 2 to 208, 2 to 212, 2 to 216,
        2 to 219, 2 to 222, 2 to 225, 2 to 229, 2 to 232, 2 to 235, 2 to 239, 2 to 243, 2 to 247, 2 to 251,
        8 to 236, 8 to 239, 8 to 242, 8 to 245, 8 to 248, 8 to 251, 8 to 254,
        0x1F to 195, 0x1F to 199, 0x1F to 202, 0x1F to 205, 0x1F to 208, 0x1F to 210, 0x1F to 214, 0x1F to 217,
        0x1F to 220, 0x1F to 224, 0x1F to 228, 0x1F to 232, 0x1F to 235, 0x1F to 239, 0x1F to 242, 0x1F to 245,
        0x1F to 248, 0x1F to 251,
    ),
)
val GEN1_MUSIC_PURE_BLUE = GEN1_MUSIC_PURE_RED.copy(sha1 = TelemetrySampler.PURE_BLUE_V2_7_6_SHA1)

/** Yellow Legacy v1.0.10 (its pokeyellow.sym): Yellow's sound engine, songs and audio RAM; bank 0 moved PlayMusic and the park
 * (PlaceNextChar's `jp z`, the same instruction 12 bytes on). */
val GEN1_MUSIC_YELLOW_LEGACY = GEN1_MUSIC_YELLOW.copy(sha1 = TelemetrySampler.YELLOW_LEGACY_V1_0_10_SHA1, playMusic = 0x21AE, spin = 0x1763)

/**
 * Crystal (rev 0, rev 1, Australia: pret/pokecrystal, the same addresses in all three). Gen 2's sound engine: songs are ids
 * 1..0x66 (MUSIC_TITLE .. MUSIC_MOBILE_CENTER) with their banks in its own pointer table; PlayMusic (00:3B97) takes the id in
 * DE. Its channel structs are 0x32 bytes from wChannel1 (C101): Flags1 +3, MusicAddress / LastMusicAddress +6, NoteDuration
 * +0x15, LoopCount +0x18. The park: 0x28B2, the `18 FE` inside SaveScreen's `jr z, .down` / `cp UP` (nothing jumps there).
 * Checked headless (mgba_dump gbpark / gbcall / `gbloop N crystal`): NEW BARK TOWN loops at 37.6 s, CHERRYGROVE CITY 22.9 s,
 * the Johto wild battle 36.6 s after a 15.7 s intro, ROUTE 29 62.3 s; HEAL ends by itself - once the render core starts each
 * song from silence (Gen1MusicRenderer: _PlayMusic leaves the channels a song doesn't use playing the last one's).
 */
private fun crystalMusic(
    sha1: String,
    playMusic: Int = 0x3B97,
    spin: Int = 0x28B2,
    channel1: Long = 0xC101L,
    lastSong: Int = 0x66,
) = Gen1MusicTables(
    sha1 = sha1,
    playMusic = playMusic,
    spin = spin,
    audioRomBank = 0L,
    channelSoundIds = channel1,   // wChannel1MusicID (unused: current() reads each channel's)
    channelState = (0 until 4).flatMap { ch ->
        val b = channel1 + 0x32L * ch
        listOf(b + 3 to 1, b + 6 to 4, b + 0x15 to 1, b + 0x18 to 1)
    },
    songs = (1..lastSong).map { 0 to it },
    crystal = true,
    musicChannelFlags = (0 until 4).map { channel1 + 3 + 0x32L * it },
)

val GEN2_MUSIC_CRYSTAL = crystalMusic(TelemetrySampler.CRYSTAL_SHA1)
val GEN2_MUSIC_CRYSTAL_REV1 = crystalMusic(TelemetrySampler.CRYSTAL_REV1_SHA1)
val GEN2_MUSIC_CRYSTAL_AU = crystalMusic(TelemetrySampler.CRYSTAL_AU_SHA1)
/** German Crystal (Kristall): Crystal's sound engine and songs; bank 0 moved PlayMusic and SaveScreen's park. */
val GEN2_MUSIC_CRYSTAL_DE = crystalMusic(TelemetrySampler.CRYSTAL_DE_SHA1, playMusic = 0x3B81, spin = 0x289C)
/** Crystal Legacy v1.3.1: Crystal's sound engine, songs and channels; bank 0 moved PlayMusic and SaveScreen's park. */
val GEN2_MUSIC_CRYSTAL_LEGACY = crystalMusic(TelemetrySampler.CRYSTAL_LEGACY_V1_3_1_SHA1, playMusic = 0x3B62, spin = 0x287B)

/**
 * Crystal Clear (v2.5.11, v2.6.0, each with its Color Filter build: the same bank 0): Crystal's engine and channels, its bank 0
 * moved. PlayMusic is 00:3CDC, a wrapper that can swap the song for a map's (D9FB-D9FD, zero on the parked title screen);
 * the park is 0x28C8, the `18 FE` inside the same `jr z` / `cp` as Crystal's. Checked headless on both versions (gbpark / gbcall /
 * `gbloop N crystal`): song 2 loops at 24.1 s after 5.2 s.
 */
private fun crystalClearMusic(sha1: String) = crystalMusic(sha1, playMusic = 0x3CDC, spin = 0x28C8)
private val GEN2_MUSIC_CRYSTAL_CLEAR = listOf(
    TelemetrySampler.CRYSTAL_CLEAR_V2_5_11_SHA1, TelemetrySampler.CRYSTAL_CLEAR_V2_5_11_COLOR_FILTER_SHA1,
    TelemetrySampler.CRYSTAL_CLEAR_V2_6_0_SHA1, TelemetrySampler.CRYSTAL_CLEAR_V2_6_0_COLOR_FILTER_SHA1,
).map(::crystalClearMusic)

/**
 * Gold / Silver (pret/pokegold's .sym, the same addresses in both): Crystal's engine and structs, wChannel1 at C001, PlayMusic
 * 00:3D98, 92 songs; the park is 0x29A3, the only `18 FE` in bank 0 (a `jr z`'s operand). Checked headless on Gold (`gbloop N
 * gold`): song 2 loops at 27.7 s after 9.5 s.
 */
private fun goldSilverMusic(sha1: String) = crystalMusic(sha1, playMusic = 0x3D98, spin = 0x29A3, channel1 = 0xC001L, lastSong = 0x5C)
private val GEN2_MUSIC_GOLD_SILVER = listOf(TelemetrySampler.GOLD_SHA1, TelemetrySampler.SILVER_SHA1).map(::goldSilverMusic)
/** German Gold / Silver: English's sound engine and songs; bank 0 moved PlayMusic and the park. */
private val GEN2_MUSIC_GOLD_SILVER_DE = listOf(TelemetrySampler.GOLD_DE_SHA1, TelemetrySampler.SILVER_DE_SHA1)
    .map { crystalMusic(it, playMusic = 0x3D8F, spin = 0x29D0, channel1 = 0xC001L, lastSong = 0x5C) }

/**
 * Polished Crystal 3.2.0 - 3.2.3 (each release's .sym): its own channel_struct, 0x24 bytes from wChannel1 (CB7F) - MusicAddress /
 * LastMusicAddress +3, Flags +7, NoteDuration +0x12, LoopCount +0x14 - and 196 songs. PlayMusic moved between releases; the normal
 * / debug builds share it, the faithful ones sit 0xE lower. The park is the `18 FE` inside a `jr z` / `cp 1` in bank 0. Checked
 * headless on 3.2.3 (`gbloop N polished`): NEW BARK TOWN loops at 37.6 s, the Johto wild battle at 37.6 s after 16.1 s.
 */
private fun polishedCrystalMusic(sha1: String, playMusic: Int, spin: Int) = Gen1MusicTables(
    sha1 = sha1,
    playMusic = playMusic,
    spin = spin,
    audioRomBank = 0L,
    channelSoundIds = 0xCB7FL,
    channelState = (0 until 4).flatMap { ch ->
        val b = 0xCB7FL + 0x24L * ch
        listOf(b + 7 to 1, b + 3 to 4, b + 0x12 to 1, b + 0x14 to 1)
    },
    songs = (1..0xC4).map { 0 to it },
    crystal = true,
    musicChannelFlags = (0 until 4).map { 0xCB86L + 0x24L * it },
    musicIdBack = 7,
)
private val GEN2_MUSIC_POLISHED_CRYSTAL = with(TelemetrySampler) {
    listOf(
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_0_SHA1, 0x394F, 0x220A),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_1_SHA1, 0x394F, 0x220A),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_2_SHA1, 0x3967, 0x220A),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_3_SHA1, 0x3967, 0x220A),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_0_Debug_SHA1, 0x394F, 0x220A),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_1_Debug_SHA1, 0x394F, 0x220A),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_2_Debug_SHA1, 0x3967, 0x220A),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_3_Debug_SHA1, 0x3967, 0x220A),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_0_Faithful_SHA1, 0x3941, 0x21FC),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_1_Faithful_SHA1, 0x3941, 0x21FC),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_2_Faithful_SHA1, 0x3959, 0x21FC),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_3_Faithful_SHA1, 0x3959, 0x21FC),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_0_Faithful_Debug_SHA1, 0x3941, 0x21FC),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_1_Faithful_Debug_SHA1, 0x3941, 0x21FC),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_2_Faithful_Debug_SHA1, 0x3959, 0x21FC),
        polishedCrystalMusic(POLISHED_CRYSTAL_V3_2_3_Faithful_Debug_SHA1, 0x3959, 0x21FC),
    )
}

object Gen1Music {
    private val GAMES = listOf(GEN1_MUSIC_YELLOW, GEN1_MUSIC_YELLOW_DE, GEN1_MUSIC_YELLOW_FR, GEN1_MUSIC_YELLOW_IT, GEN1_MUSIC_YELLOW_ES,
        GEN1_MUSIC_RED, GEN1_MUSIC_BLUE, GEN1_MUSIC_PURE_RED, GEN1_MUSIC_PURE_BLUE, GEN1_MUSIC_YELLOW_LEGACY, GEN2_MUSIC_CRYSTAL, GEN2_MUSIC_CRYSTAL_REV1,
        GEN2_MUSIC_CRYSTAL_AU, GEN2_MUSIC_CRYSTAL_DE, GEN2_MUSIC_CRYSTAL_LEGACY) +
        GEN2_MUSIC_CRYSTAL_CLEAR + GEN2_MUSIC_GOLD_SILVER + GEN2_MUSIC_GOLD_SILVER_DE + GEN2_MUSIC_POLISHED_CRYSTAL

    fun forSha1(sha1: String): Gen1MusicTables? = GAMES.firstOrNull { it.sha1 == sha1 }

    /** The cache key for a song: its bank and id. */
    fun key(bank: Int, id: Int) = "gb_%02x_%02x".format(bank, id)

    /** The (bank, id) a [key] names, or null. */
    fun parse(key: String): Pair<Int, Int>? {
        val m = Regex("gb_([0-9a-f]{2})_([0-9a-f]{2})").matchEntire(key) ?: return null
        return m.groupValues[1].toInt(16) to m.groupValues[2].toInt(16)
    }

    /** What [r]'s music channels are playing, or null (silence / unreadable). */
    fun current(r: MemoryReader, t: Gen1MusicTables): String? = runCatching {
        if (t.crystal) {
            // The first music channel that's on: its own MusicID (the struct's first word, before its flags byte).
            val flags = t.musicChannelFlags.firstOrNull { (r.readCoreMemory(it, 1)[0].toInt() and 1) != 0 } ?: return null
            val b = r.readCoreMemory(flags - t.musicIdBack, 2)
            val id = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
            return if (id in 1..0xFF) key(0, id) else null
        }
        val ids = r.readCoreMemory(t.channelSoundIds, 4)
        val id = ids.map { it.toInt() and 0xFF }.firstOrNull { it != 0 } ?: return null
        key(r.readCoreMemory(t.audioRomBank, 1)[0].toInt() and 0xFF, id)
    }.getOrNull()
}
