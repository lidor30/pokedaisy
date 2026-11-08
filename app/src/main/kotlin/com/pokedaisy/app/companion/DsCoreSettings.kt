package com.pokedaisy.app.companion

import com.pokedaisy.app.MgbaCore
import com.pokedaisy.app.companion.i18n.tk

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

    /** The core this choice runs here (MgbaCore.NDS_CORE_*). */
    fun runs(weak: Boolean = deviceWeak, sixtyFour: Boolean = is64Bit): Int = when (this) {
        MELONDS -> MgbaCore.NDS_CORE_MELONDS
        DSPERATE -> MgbaCore.NDS_CORE_DSPERATE
        AUTO -> if (weak || !sixtyFour) MgbaCore.NDS_CORE_DSPERATE else MgbaCore.NDS_CORE_MELONDS
    }

    /** What the row shows: AUTO says which it picked ("AUTO: DSPERATE"). */
    fun valueLabel(): String = if (this == AUTO) label + ": " + (if (runs() == MgbaCore.NDS_CORE_DSPERATE) DSPERATE.label else MELONDS.label) else label

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: AUTO

        /** This device looks weak (DevicePerformance.looksWeak), set as the app starts (PokeDaisyApp). */
        @Volatile var deviceWeak = false
        /** A 64-bit process (android.os.Process.is64Bit): a 32-bit Android runs melonDS without its JIT. */
        @Volatile var is64Bit = true
    }
}
