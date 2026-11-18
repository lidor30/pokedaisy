package com.pokedaisy.app.companion

import kotlin.concurrent.Volatile
import com.pokedaisy.app.companion.i18n.tk

/** DS RESOLUTION's highest scale ([com.pokedaisy.app.Prefs.dsResolution]). */
const val DS_RESOLUTION_MAX = 8

/**
 * SETTINGS > DS CORE ([com.pokedaisy.app.Prefs.dsCore]): which emulator runs a DS game. melonDS (nds/pk_nds.cpp) has
 * every DS feature; DSperate (nds/pk_nds_dsp.cpp) runs the same games on far less (its recompilers - one for 32-bit
 * ARM, where melonDS has none - fast memory, the 3D on worker threads), without EXPAND, DS RESOLUTION, PC / FLY from
 * the companion, STEADY music or DS CPU BOOST yet. AUTO picks DSperate on a weak device ([deviceWeak]) or a 32-bit
 * Android ([is64Bit]), melonDS elsewhere. BANANADS (bananads/pk_nds_bnd.cpp) is BananaOS's DS emulator (melonDS's core
 * with BananaDS's patches, its 2D engine and Gengis Engine for the 3D), with DSperate's limits but DS CPU BOOST; never AUTO's pick. Taken when a DS game starts; states don't cross cores (a resume state of the
 * other core is set aside for it, and the game starts from its save file).
 */
enum class DsCore(val id: Int, val label: String) {
    AUTO(0, tk("AUTO")),
    MELONDS(1, "MELONDS"),
    DSPERATE(2, "DSPERATE"),
    BANANADS(3, "BANANADS"),
    ;

    /** The core this choice runs here ([CORE_MELONDS] / [CORE_DSPERATE]). */
    fun runs(weak: Boolean = deviceWeak, sixtyFour: Boolean = is64Bit): Int = when (this) {
        MELONDS -> DsCore.CORE_MELONDS
        DSPERATE -> DsCore.CORE_DSPERATE
        // Where it can't start (not arm64, Android 9 or older: [bananaRuns]) a saved BANANADS runs melonDS.
        BANANADS -> if (bananaRuns) DsCore.CORE_BANANADS else DsCore.CORE_MELONDS
        AUTO -> if (weak || !sixtyFour) DsCore.CORE_DSPERATE else DsCore.CORE_MELONDS
    }

    /** What the row shows: AUTO says which it picked ("AUTO: DSPERATE"). */
    fun valueLabel(): String = if (this == AUTO) label + ": " + labelOf(runs()) else label

    companion object {
        /** melonDS (nds/pk_nds.cpp): every DS feature. The number MgbaCore.pkNdsSetCore / pkNdsCore pass. */
        const val CORE_MELONDS = 0
        /** DSperate (nds/pk_nds_dsp.cpp): for weak devices; no EXPAND, DS RESOLUTION, PC / FLY calls, STEADY or DS CPU BOOST. */
        const val CORE_DSPERATE = 1
        /** BananaDS (bananads/pk_nds_bnd.cpp, libpkbananads.so): BananaOS's DS emulator, melonDS's software 3D under its own
         *  2D engine; no DS RESOLUTION, PC / FLY calls or STEADY. AArch64 and Android 10+ only ([bananaRuns]). */
        const val CORE_BANANADS = 2

        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: AUTO

        /** The name of the core [core] (CORE_*) is. */
        fun labelOf(core: Int): String = when (core) {
            CORE_DSPERATE -> DSPERATE.label
            CORE_BANANADS -> BANANADS.label
            else -> MELONDS.label
        }

        /** A melonDS-only row's subtitle on another core (DS RESOLUTION, DS CPU BOOST). */
        fun unavailableOn(core: Int): String =
            if (core == CORE_BANANADS) "Unavailable on BANANADS" else "Unavailable on DSPERATE"

        /** DS RESOLUTION runs on this core: melonDS's OpenGL renderer. BananaDS's own hires is its Gengis Engine, which
         *  isn't built here (it carries code ported from DraStic's), so BananaDS draws at 1x. */
        fun hasResolution(core: Int) = core == CORE_MELONDS

        /** EXPAND runs on this core: melonDS, or BananaDS (pk_nds_wide.cpp built against its melonDS). */
        fun hasExpand(core: Int) = core == CORE_MELONDS || core == CORE_BANANADS

        /** DS CPU BOOST runs on this core (melonDS's and BananaDS's melonDS, not DSperate). */
        fun boosts(core: Int) = core != CORE_DSPERATE

        /** This device looks weak (DevicePerformance.looksWeak), set as the app starts (PokeDaisyApp). */
        @Volatile var deviceWeak = false
        /** A 64-bit process (android.os.Process.is64Bit): a 32-bit Android runs melonDS without its JIT. */
        @Volatile var is64Bit = true
        /** BananaDS can start here: an arm64 process on Android 10+ (its library is AArch64 only and uses native TLS). */
        @Volatile var bananaRuns = false
        /** The choices the DS CORE pick-list offers here: BANANADS only where it can start. */
        fun offered(): List<DsCore> = entries.filter { it != BANANADS || bananaRuns }
    }
}
