package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.Gfx
import com.pokedaisy.app.companion.data.LockedMap
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_RETAIL
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV0
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.TelemetrySampler
import com.pokedaisy.app.companion.data.decodeFrontSprite
import com.pokedaisy.app.companion.data.readFully
import com.pokedaisy.app.companion.data.withRomFile
import okio.FileHandle
import okio.Path

/**
 * Front pictures for the SAVE EDITOR's Pokémon page, read from the player's own
 * ROM *file* - nothing is bundled, and no game is running in the library. The
 * same decoder as the companion's ([decodeFrontSprite]) over the dex tables the
 * companion already knows for that ROM: retail FireRed rev 0 / 1 and Emerald by
 * hash, the other retail games by game code + revision. Anything else (hacks,
 * translations) gets none, and the page falls back to its icon / name.
 */
class RomMonSprites private constructor(private val rom: Path, private val tables: PokedexTables) {

    private val cache = LockedMap<Int, RomArt.Image>()
    private val missing = LockedMap<Int, Unit>()

    /** A sprite already decoded, without touching the file. */
    fun peek(species: Int): RomArt.Image? = cache[species]

    /** [species]' 64x64 front picture, or null. Blocking: off the UI thread. */
    fun get(species: Int): RomArt.Image? {
        if (species <= 0 || species in missing) return null
        cache[species]?.let { return it }
        val img = runCatching { withRomFile(rom) { f -> decodeFrontSprite(tables, species) { a, n -> read(f, a, n) } } }
            .getOrNull()
        if (img == null) missing[species] = Unit else cache[species] = img
        return img
    }

    companion object {
        private const val ROM_BASE = 0x08000000L

        private fun read(f: FileHandle, addr: Long, n: Int): ByteArray = f.readFully(addr - ROM_BASE, n)

        /** The dex tables for a ROM with this whole-file [sha1], header [code] and [revision]; null = not one we know. */
        fun tablesFor(sha1: String?, code: String?, revision: Int?): PokedexTables? = when (sha1) {
            FIRERED_REV0_SHA1 -> NATIVE_FIRERED_REV0.pokedex
            FIRERED_REV1_SHA1 -> NATIVE_FIRERED_REV1.pokedex
            EMERALD_SHA1 -> NATIVE_EMERALD_RETAIL.pokedex
            else -> if (code != null && revision != null && code in TelemetrySampler.OTHER_RETAIL_CODES) {
                TelemetrySampler.otherRetailConfig(code, revision)?.pokedex
            } else null
        }

        /**
         * Sprites for [rom] (a plain ROM file: an archive is unpacked by the caller) with [tables], once a sample of
         * the table checks out against the file - a few species' front-pic pointers land in the ROM.
         */
        fun open(rom: Path, tables: PokedexTables): RomMonSprites? = runCatching {
            withRomFile(rom) { f ->
                val size = f.size()
                fun inFile(p: Long) = p >= ROM_BASE && p - ROM_BASE < size
                // Each pointer must land in the file on an LZ77 stream (0x10): a hack that moved its table elsewhere
                // would otherwise show retail's leftover pictures.
                val ok = listOf(1, 25, 151, 277, 411).all { s ->
                    val ptr = Gfx.u32(read(f, tables.frontPics + s.toLong() * 8L, 8), 0)
                    inFile(ptr) && read(f, ptr, 1)[0].toInt() == 0x10
                }
                if (ok) RomMonSprites(rom, tables) else null
            }
        }.getOrNull()

        // pret's firered.sha1 / firered_rev1.sha1 / emerald.sha1 (TelemetrySampler.RETAIL_SHA1S).
        private const val FIRERED_REV0_SHA1 = "41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc"
        private const val FIRERED_REV1_SHA1 = "dd5945db9b930750cb39d00c84da8571feebf417"
        private const val EMERALD_SHA1 = "f3ae088181bf583e55daf962a92bb46f4f1d07b7"
    }
}
