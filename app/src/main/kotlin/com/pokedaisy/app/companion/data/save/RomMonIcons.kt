package com.pokedaisy.app.companion.data.save

import android.graphics.Bitmap
import com.pokedaisy.app.companion.data.IconTables
import com.pokedaisy.app.companion.data.LockedMap
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_RETAIL
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV0
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1
import com.pokedaisy.app.companion.data.TelemetrySampler
import com.pokedaisy.app.companion.data.decodeMonIcon
import com.pokedaisy.app.companion.data.readFully
import com.pokedaisy.app.companion.data.withRomFile
import okio.FileHandle
import okio.Path

/**
 * Party icons for PC BOXES / TRADE, read from the player's own ROM *file* - nothing is bundled, and no game is
 * running in the library. The same decoder as the companion's ([decodeMonIcon]) over the tables the companion
 * already knows for that ROM: retail FireRed rev 0 / 1 and Emerald by hash, the other retail games (LeafGreen,
 * Ruby, Sapphire, other languages) by game code + revision. Anything else (hacks) gets none, and
 * the screens show names instead of guessing at addresses.
 */
class RomMonIcons private constructor(private val rom: Path, private val tables: IconTables) {

    private val cache = LockedMap<Int, Bitmap>()
    private val missing = LockedMap<Int, Unit>()

    /** An icon already decoded, without touching the file. */
    fun peek(species: Int): Bitmap? = cache[species]

    /** [species]' icon (32x64, both frames), or null. Blocking: off the UI thread. Eggs are [SPECIES_EGG]. */
    fun get(species: Int): Bitmap? {
        if (species <= 0 || species in missing) return null
        cache[species]?.let { return it }
        val bmp = runCatching { withRomFile(rom) { f -> decodeMonIcon(tables, species) { a, n -> read(f, a, n) } } }.getOrNull()
        if (bmp == null) missing[species] = Unit else cache[species] = bmp
        return bmp
    }

    companion object {
        private const val ROM_BASE = 0x08000000L

        private fun read(f: FileHandle, addr: Long, n: Int): ByteArray = f.readFully(addr - ROM_BASE, n)

        /** The icon tables for a ROM with this whole-file [sha1], header [code] and [revision]; null = not one we know. */
        fun tablesFor(sha1: String?, code: String?, revision: Int?): IconTables? = when (sha1) {
            FIRERED_REV0_SHA1 -> NATIVE_FIRERED_REV0.iconTables
            FIRERED_REV1_SHA1 -> NATIVE_FIRERED_REV1.iconTables
            EMERALD_SHA1 -> NATIVE_EMERALD_RETAIL.iconTables
            else -> if (code != null && revision != null && code in TelemetrySampler.OTHER_RETAIL_CODES) {
                TelemetrySampler.otherRetailConfig(code, revision)?.iconTables
            } else null
        }?.takeIf { it.monPresent }

        /**
         * Icons for [rom] (a plain ROM file: an archive is unpacked by the caller) with [tables], once a sample of the
         * table checks out against the file - every pointer in the ROM, palette indices within the six palettes.
         */
        fun open(rom: Path, tables: IconTables): RomMonIcons? = runCatching {
            withRomFile(rom) { f ->
                val size = f.size()
                fun inFile(p: Long) = p >= ROM_BASE && p - ROM_BASE < size
                val ok = listOf(1, 25, 151, 277, 411).all { s ->
                    val ptr = u32(read(f, tables.monIconTable + s.toLong() * tables.monIconStride, 4), 0)
                    val pal = read(f, tables.monIconPaletteIndices + s.toLong() * tables.monPalIdxStride, 1)[0].toInt() and tables.monPalIdxMask
                    inFile(ptr) && pal in 0..5
                }
                if (ok) RomMonIcons(rom, tables) else null
            }
        }.getOrNull()

        // pret's firered.sha1 / firered_rev1.sha1 / emerald.sha1 (TelemetrySampler.RETAIL_SHA1S).
        private const val FIRERED_REV0_SHA1 = "41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc"
        private const val FIRERED_REV1_SHA1 = "dd5945db9b930750cb39d00c84da8571feebf417"
        private const val EMERALD_SHA1 = "f3ae088181bf583e55daf962a92bb46f4f1d07b7"
    }
}
