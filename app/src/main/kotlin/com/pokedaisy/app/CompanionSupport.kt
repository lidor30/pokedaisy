package com.pokedaisy.app

import com.pokedaisy.app.companion.data.BestEffortStore
import com.pokedaisy.app.companion.data.TelemetrySampler
import java.io.File

/**
 * Whether the second-screen companion can read [rom] - the same call
 * [TelemetrySampler]'s detect() makes live, made from the file at import so
 * the library can warn before adding a ROM the companion will only show a
 * "not supported" notice for. Retail LeafGreen / Ruby / Sapphire (the
 * revisions detect() has addresses for), retail FireRed / Emerald by their SHA1 (or as a trimmed /
 * save-patched dump of one, [com.pokedaisy.app.companion.data.RetailDumps], or randomized by Universal Pokémon
 * Randomizer ZX, [com.pokedaisy.app.companion.data.RandomizedRetail]), and
 * a hack only when its SHA1 is one detect() knows. An archive is judged by the ROM
 * inside it ([RomArchive]). Blocking (hashes up to 32 MB) - never on the UI thread.
 * The website's ROM check (`website/src/lib/compat.js`) is the same rules in JavaScript, over
 * the tables `SiteDataExportTest` exports: change this, change that.
 */
object CompanionSupport {
    /** MATCHED: supported through a FULL best-effort match - kept apart so forgetting it is noticed.
     * TRYABLE: not supported, but a Gen 3 Pokémon base game, so TRY BEST EFFORT may read it. */
    enum class Verdict { SUPPORTED, MATCHED, PARTIAL, TRYABLE, UNSUPPORTED }

    /**
     * [isSupported], then a BEST EFFORT match the player kept for this ROM ([BestEffortStore], by
     * SHA-1): every part holding counts as supported (a supported version under another hash),
     * some off as PARTIAL. Blocking, like [isSupported].
     */
    fun verdict(rom: File): Verdict {
        if (isSupported(rom)) return Verdict.SUPPORTED
        // Best effort only ever matches a Gen 3 base game's code: anything else (another console's ROM in a frontend's
        // folder) is turned away without hashing the whole file.
        if (RomIdentity.gameCode(rom)?.let(com.pokedaisy.app.companion.data.BestEffort::canTry) != true) return Verdict.UNSUPPORTED
        val e = BestEffortStore.load(RomIdentity.sha1(rom)) ?: return Verdict.TRYABLE
        return if (e.full) Verdict.MATCHED else Verdict.PARTIAL
    }

    fun isSupported(rom: File): Boolean {
        // Game Boy / Color: the exact carts the GB reader knows (Pokémon Yellow).
        if (RomIdentity.isGameBoy(rom)) return TelemetrySampler.GAME_BOY_SUPPORT && RomIdentity.sha1(rom) in TelemetrySampler.SUPPORTED_GB_SHA1S
        // A DS cart: by its header's code and revision, as detect() takes it (a 128 MB+ ROM isn't hashed).
        if (RomIdentity.isNds(rom)) return TelemetrySampler.ndsSupported(RomIdentity.ndsGameCode(rom), RomIdentity.ndsRevision(rom))
        val code = RomIdentity.gameCode(rom) ?: return false
        val size = RomArchive.romSize(rom)
        if (code in TelemetrySampler.OTHER_RETAIL_CODES) {
            // Retail only, and only the revisions detect() has addresses for.
            return size <= 0x1000000L && TelemetrySampler.otherRetailConfig(code, RomIdentity.revision(rom) ?: -1) != null
        }
        // A hack under a game code of its own (Ultra Violet's PUVV): only the build detect() knows.
        if (code in TelemetrySampler.HACK_GAME_CODES) return RomIdentity.sha1(rom) in TelemetrySampler.SUPPORTED_HACK_SHA1S
        if (code != "BPRE" && code != "BPEE") return false
        val sha1 = RomIdentity.sha1(rom)
        if (sha1 in TelemetrySampler.SUPPORTED_HACK_SHA1S) return true
        if (size > 0x1000000L) return false
        // Retail, a retail dump with its end trimmed / save code patched (RetailDumps), or retail randomized
        // (RandomizedRetail), as detect() takes them.
        val rev = RomIdentity.revision(rom)
        return sha1 in TelemetrySampler.RETAIL_SHA1S ||
            com.pokedaisy.app.companion.data.RetailDumps.matchStream(code, rev, size) { RomArchive.open(rom) } != null ||
            com.pokedaisy.app.companion.data.RandomizedRetail.matchStream(code, rev, size) { RomArchive.open(rom) } != null
    }
}
