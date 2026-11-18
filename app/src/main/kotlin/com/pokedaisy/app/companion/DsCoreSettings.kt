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
 * Android ([is64Bit]), melonDS elsewhere. Taken when a DS game starts; states don't cross cores (a resume state of the
 * other core is set aside for it, and the game starts from its save file).
 */
enum class DsCore(val id: Int, val label: String) {
    AUTO(0, tk("AUTO")),
    MELONDS(1, "MELONDS"),
    DSPERATE(2, "DSPERATE"),
    ;

    /** The core this choice runs here ([CORE_MELONDS] / [CORE_DSPERATE]). */
    fun runs(weak: Boolean = deviceWeak, sixtyFour: Boolean = is64Bit): Int = when (this) {
        MELONDS -> DsCore.CORE_MELONDS
        DSPERATE -> DsCore.CORE_DSPERATE
        AUTO -> if (weak || !sixtyFour) DsCore.CORE_DSPERATE else DsCore.CORE_MELONDS
    }

    /** What the row shows: AUTO says which it picked ("AUTO: DSPERATE"). */
    fun valueLabel(): String = if (this == AUTO) label + ": " + (if (runs() == DsCore.CORE_DSPERATE) DSPERATE.label else MELONDS.label) else label

    companion object {
        /** melonDS (nds/pk_nds.cpp): every DS feature. The number MgbaCore.pkNdsSetCore / pkNdsCore pass. */
        const val CORE_MELONDS = 0
        /** DSperate (nds/pk_nds_dsp.cpp): for weak devices; no EXPAND, DS RESOLUTION, PC / FLY calls, STEADY or DS CPU BOOST. */
        const val CORE_DSPERATE = 1

        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: AUTO

        /** This device looks weak (DevicePerformance.looksWeak), set as the app starts (PokeDaisyApp). */
        @Volatile var deviceWeak = false
        /** A 64-bit process (android.os.Process.is64Bit): a 32-bit Android runs melonDS without its JIT. */
        @Volatile var is64Bit = true
    }
}
