package com.pokedaisy.app.companion

import com.pokedaisy.app.MgbaCore
import com.pokedaisy.app.companion.i18n.tk

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

    /** The core this choice runs here (MgbaCore.NDS_CORE_*). */
    fun runs(weak: Boolean = deviceWeak, sixtyFour: Boolean = is64Bit): Int = when (this) {
        MELONDS -> MgbaCore.NDS_CORE_MELONDS
        DSPERATE -> MgbaCore.NDS_CORE_DSPERATE
        BANANADS -> MgbaCore.NDS_CORE_BANANADS
        AUTO -> if (weak || !sixtyFour) MgbaCore.NDS_CORE_DSPERATE else MgbaCore.NDS_CORE_MELONDS
    }

    /** What the row shows: AUTO says which it picked ("AUTO: DSPERATE"). */
    fun valueLabel(): String = if (this == AUTO) label + ": " + labelOf(runs()) else label

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: AUTO

        /** The name of the core MgbaCore.NDS_CORE_* [core] is. */
        fun labelOf(core: Int): String = when (core) {
            MgbaCore.NDS_CORE_DSPERATE -> DSPERATE.label
            MgbaCore.NDS_CORE_BANANADS -> BANANADS.label
            else -> MELONDS.label
        }

        /** A melonDS-only row's subtitle on another core (DS RESOLUTION, DS CPU BOOST). */
        fun unavailableOn(core: Int): String =
            if (core == MgbaCore.NDS_CORE_BANANADS) "Unavailable on BANANADS" else "Unavailable on DSPERATE"

        /** DS RESOLUTION runs on this core: melonDS's OpenGL renderer, or BananaDS's own hires (up to 4x). */
        fun hasResolution(core: Int) = core == MgbaCore.NDS_CORE_MELONDS || core == MgbaCore.NDS_CORE_BANANADS

        /** EXPAND runs on this core: melonDS, or BananaDS (pk_nds_wide.cpp built against its melonDS). */
        fun hasExpand(core: Int) = core == MgbaCore.NDS_CORE_MELONDS || core == MgbaCore.NDS_CORE_BANANADS

        /** DS CPU BOOST runs on this core (melonDS's and BananaDS's melonDS, not DSperate). */
        fun boosts(core: Int) = core != MgbaCore.NDS_CORE_DSPERATE

        /** This device looks weak (DevicePerformance.looksWeak), set as the app starts (PokeDaisyApp). */
        @Volatile var deviceWeak = false
        /** A 64-bit process (android.os.Process.is64Bit): a 32-bit Android runs melonDS without its JIT. */
        @Volatile var is64Bit = true
    }
}
