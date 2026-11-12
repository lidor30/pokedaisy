package com.pokedaisy.app.companion.data

/**
 * Games PokéDaisy won't read, at their makers' request: the companion shows [reason] (and [action], a link) in place of
 * every tab but SETTINGS, and no BEST EFFORT guess or game picker is offered. The game itself still plays.
 *
 * A game is listed by the masked SHA-1 the Poller hashes it by ([Poller]'s `romHash`, the same values as
 * [com.pokedaisy.app.GameTitles.BY_SHA1]), so only a ROM detect() hashes can be listed (every hack past 16 MB, the
 * small BPRE / BPEE checks), and by its file's CRC32 ([crc32s]), which the game screen goes by: with a listed CRC
 * none of PokéDaisy's game features run (EXPAND, the field reads, the HUD, the quick menu's game actions, FLY, the
 * makeover, the game-option patches). The library calls it NOT SUPPORTED and its INFO gives [reason].
 * [reason] and the action's label are English keys `tr()` translates (TrCompanion).
 */
data class BlockedGame(
    val title: String,
    val sha1s: Set<String>,
    val crc32s: Set<String>,
    /** The files' sizes in bytes: the library hashes a ROM for its BLOCKED tag only at one of these. */
    val sizes: Set<Long>,
    val reason: String,
    val action: BlockedAction? = null,
)

/** A blocked game's one button: [label] (an English key) opens [url]. */
data class BlockedAction(val label: String, val url: String)

object BlockedGames {
    /** Ghost Grey's developers asked for it to be removed (2026-11-12). */
    val GHOST_GREY = BlockedGame(
        title = "GHOST GREY",
        sha1s = setOf(
            TelemetrySampler.GHOST_GREY_V1_0_1_SHA1,
            TelemetrySampler.GHOST_GREY_V1_0_2_SHA1,
            TelemetrySampler.GHOST_GREY_V1_0_4_SHA1,
        ),
        crc32s = setOf("be0375e2", "dcac3f32", "737a7a12"),   // v1.0.1, v1.0.2, v1.0.4 (pk_wide_games.h's rows)
        sizes = setOf(0x2000000L),
        reason = "Ghost Grey's developer asked for it to be removed from PokéDaisy. If you'd like it supported again, " +
            "please contact the Ghost Grey developer on their Discord.",
        action = BlockedAction("OPEN DISCORD", "https://discord.com/invite/cvTmRWa98N"),
    )

    val ALL: List<BlockedGame> = listOf(GHOST_GREY)

    private val bySha1: Map<String, BlockedGame> = ALL.flatMap { g -> g.sha1s.map { it.lowercase() to g } }.toMap()

    private val byCrc: Map<String, BlockedGame> = ALL.flatMap { g -> g.crc32s.map { it.lowercase() to g } }.toMap()

    /**
     * Whether the list is kept: false in a debug build and wherever [com.pokedaisy.app.FeatureFlags.allowBlocked] says so,
     * where a listed game plays with everything and the library only tags it BLOCKED ([listedSha1]).
     */
    @Volatile var enforced: Boolean = true

    /** The listed game [sha1] is, while the list is kept ([enforced]); null otherwise. */
    fun forSha1(sha1: String?): BlockedGame? = if (enforced) listedSha1(sha1) else null

    /** By the file's CRC32 as 8 hex digits (the game screen's `romKey`), while the list is kept. */
    fun forCrc(crc: String?): BlockedGame? = if (enforced) crc?.let { byCrc[it.lowercase()] } else null

    /** The listed game [sha1] is, kept or not: the library's BLOCKED tag. */
    fun listedSha1(sha1: String?): BlockedGame? = sha1?.let { bySha1[it.lowercase()] }

    /** A file of [size] bytes could be listed (worth hashing for the tag). */
    fun mayList(size: Long): Boolean = ALL.any { size in it.sizes }

    /** Changes whenever the list or [enforced] does: the library's cached verdicts are read again ([com.pokedaisy.app.RomFolder]). */
    val stamp: String get() = Integer.toHexString(ALL.flatMap { it.sha1s }.sorted().hashCode()) + if (enforced) "" else "-open"
}
