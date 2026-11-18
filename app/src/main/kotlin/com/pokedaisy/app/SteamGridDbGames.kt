package com.pokedaisy.app

import com.pokedaisy.app.companion.data.EMERALD_LOCALIZED_CODES
import com.pokedaisy.app.companion.data.RETAIL_PORT_CODE_TITLES

/**
 * Which SteamGridDB "game" entry each supported ROM hack corresponds to, so
 * [SteamGridDbClient] knows what to ask for once a ROM is identified by
 * [RomIdentity.sha1]. IDs found via SteamGridDB's `/search/autocomplete`
 * endpoint and spot-checked against their actual grid art (2026-09-24).
 *
 * Keyed by the exact same whole-file SHA1 values `Poller.kt` already uses to
 * tell these hacks apart live on-device (see its own big comment block for
 * how/when each was verified) — duplicated here rather than shared, since
 * that file's constants are private to its own companion-telemetry concern
 * and these two features (telemetry address selection vs. library cover
 * art) are otherwise unrelated. If a hash there ever needs correcting, mirror
 * the fix here too.
 *
 * Only a plain lookup table — no gameplay-relevant data, unlike the telemetry
 * side. A ROM whose SHA1 isn't here (a hack not yet in SteamGridDB's
 * database or an unrecognized big hack) gets no automatic cover - the
 * placeholder, CHANGE COVER's search and the manual "Set Cover" picker cover
 * it. Retail FireRed/Emerald and small hacks of them are matched by game code
 * in [forRom].
 */
object SteamGridDbGames {
    data class Game(val id: Int, val displayName: String)

    val BY_SHA1: Map<String, Game> = mapOf(
        // Pokémon Unbound v2.1.1.1 — SHA1 per Poller.kt's UNBOUND_V2_1_1_1_SHA1.
        "b4776b82a4c7915d0fadeaa27e013523f99dfd94" to Game(5274554, "Pokémon Unbound"),
        // Its French translation (UNBOUND_V2_1_1_1_FR_SHA1): the same game's art.
        "0ce2a880aa097f1dce4e1db8ee513d0e82d15859" to Game(5274554, "Pokémon Unbound"),
        // And its Italian one (UNBOUND_V2_1_1_1_IT_SHA1).
        "8114e0f81eb3581f699c74a5891c7b75a130269d" to Game(5274554, "Pokémon Unbound"),
        // Pokémon Gaia v3.2 — SHA1 per Poller.kt's GAIA_V3_2_SHA1.
        "d5b1e77975fcda831e0e9a7b527906bf3f40ecd0" to Game(5345283, "Pokémon Gaia"),
        // Pokémon Radical Red v4.1 — SHA1 per Poller.kt's RADICAL_RED_V4_1_SHA1.
        "964f951a0fdaf209e4ea1344883ef0d557bb3a80" to Game(5274514, "Pokémon Radical Red"),
        // Pokémon Odyssey v4.1.1 — SHA1 per Poller.kt's ODYSSEY_V4_1_1_SHA1.
        "8745ddbdbfadf6abaf66de4e9055923b62eb4668" to Game(5445119, "Pokémon Odyssey"),
        // Pokémon Heart and Soul v2.0.6 — SHA1 per Poller.kt's HEART_AND_SOUL_V2_0_6_SHA1.
        "79ee6df0869c1773c8c6a5f764afc1f8d833d8bb" to Game(5503721, "Pokémon Heart & Soul"),
        // Pokémon Lazarus v2.0 — SHA1 per Poller.kt's LAZARUS_V2_0_SHA1.
        "7dcdc7e280bc4631487e13dd37e6e0cea04adea6" to Game(5505418, "Pokémon Lazarus"),
        // Pokémon R.O.W.E. v2.1.9.1 Experimental — SHA1 per Poller.kt's ROWE_V2_1_9_1_SHA1.
        "81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77" to Game(5355865, "Pokémon R.O.W.E."),
        // Pokémon Emerald Rogue v2.2.1-EX — SHA1 per Poller.kt's EMERALD_ROGUE_V2_2_1_EX_SHA1.
        "7600af1fe08444c850c3c1227fd7dfd81336ae8e" to Game(5361485, "Pokémon Emerald Rogue"),
        // Pokémon Emerald Seaglass v3.0 — SHA1 per Poller.kt's EMERALD_SEAGLASS_V3_0_SHA1
        // (16 MB like retail, so the hash has to win over the game-code fallback below).
        "b9f4d332d30fc88c379f9e037f9eae3b2755ead4" to Game(5462842, "Pokémon Emerald Seaglass"),
        // Pokémon Amethyst v1.3.0 is NOT here — not catalogued on SteamGridDB
        // as of 2026-09-24 (searched "Pokemon Amethyst" and variants, no
        // match). Re-check if this ever needs revisiting.
    )

    val FIRERED = Game(33987, "Pokémon FireRed")
    val EMERALD = Game(34971, "Pokémon Emerald")

    /**
     * A known hack by exact hash; otherwise a 16 MB-or-smaller FireRed/Emerald
     * ROM by its header's game code - retail and small hacks get the base
     * game's art.
     */
    fun forRom(rom: java.io.File): Game? {
        RomIdentity.sha1(rom)?.let { BY_SHA1[it] }?.let { return it }
        if (rom.length() > 0x1000000L) return null
        return when (RomIdentity.gameCode(rom)) {
            "BPRE" -> FIRERED
            "BPEE", in EMERALD_LOCALIZED_CODES -> EMERALD
            in RETAIL_PORT_CODE_TITLES.keys.filter { it.startsWith("BPR") } -> FIRERED
            else -> null
        }
    }
}
