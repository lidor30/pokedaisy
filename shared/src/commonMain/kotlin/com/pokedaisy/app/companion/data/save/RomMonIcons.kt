package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.IconTables
import com.pokedaisy.app.companion.data.LockedMap
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_KAIZO
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_RETAIL
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV0
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1
import com.pokedaisy.app.companion.data.NATIVE_ORANGE_ISLANDS
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.TelemetrySampler
import com.pokedaisy.app.companion.data.decodeMonIcon
import com.pokedaisy.app.companion.data.readFully
import com.pokedaisy.app.companion.data.withRomFile
import okio.FileHandle
import okio.Path

/**
 * Party icons for PC BOXES / TRADE, read from the player's own ROM *file* - nothing is bundled, and no game is
 * running in the library. The same decoder as the companion's ([decodeMonIcon]) over the tables the companion
 * already knows for that ROM ([tablesFor]), each checked against the file before it's used ([open]); a ROM whose
 * tables don't check out gets none, and the screens show names. The DS carts' come from NdsArt.monIcons.
 */
class RomMonIcons private constructor(private val rom: Path, private val tables: IconTables) {

    private val cache = LockedMap<Int, RomArt.Image>()
    private val missing = LockedMap<Int, Unit>()

    /** An icon already decoded, without touching the file. */
    fun peek(species: Int): RomArt.Image? = cache[species]

    /** [species]' icon (32x64, both frames), or null. Blocking: off the UI thread. Eggs are [SPECIES_EGG]. */
    fun get(species: Int): RomArt.Image? {
        if (species <= 0 || species in missing) return null
        cache[species]?.let { return it }
        val bmp = runCatching { withRomFile(rom) { f -> decodeMonIcon(tables, species) { a, n -> read(f, a, n) } } }.getOrNull()
        if (bmp == null) missing[species] = Unit else cache[species] = bmp
        return bmp
    }

    companion object {
        private const val ROM_BASE = 0x08000000L

        private fun read(f: FileHandle, addr: Long, n: Int): ByteArray = f.readFully(addr - ROM_BASE, n)

        /**
         * Icons for [rom] (a plain ROM file: an archive is unpacked by the caller) with this whole-file [sha1], header
         * [code] and [revision], or null. In order, the first whose tables check out against the file ([open]):
         * 1. the tables of a ROM known by its hash (retail, the hacks edited in place: Rocket Edition, Ash Gray, Orange
         *    Islands, Kaizo) or of another retail game by code + revision ([knownTables]);
         * 2. for any FireRed / LeafGreen / Emerald build, the ones its own Game Freak ROM header names (0x08000138: the
         *    icon table, palette indices and palettes - a rebuild that moved them, like a QoL patch, still says where);
         * 3. its base game's retail tables (a trimmed or flash-patched dump, a randomized one: the header is retail's too).
         * The save editor only opens saves laid out as retail's, so the species ids are the base game's.
         */
        fun forRom(rom: Path, sha1: String?, code: String?, revision: Int?): RomMonIcons? {
            knownTables(sha1, code, revision)?.let { t -> open(rom, t)?.let { return it } }
            val base = baseTables(code, revision) ?: return null
            headerTables(rom, base)?.takeIf { it != base }?.let { t -> open(rom, t)?.let { return it } }
            return open(rom, base)
        }

        /** [forRom]'s first and last choices together: what a ROM gets before its file is looked at. */
        fun tablesFor(sha1: String?, code: String?, revision: Int?): IconTables? =
            knownTables(sha1, code, revision) ?: baseTables(code, revision)

        /** The tables of a ROM known by its hash, or of another retail game by code + revision; null = not one we know. */
        fun knownTables(sha1: String?, code: String?, revision: Int?): IconTables? = when (sha1) {
            FIRERED_REV0_SHA1 -> NATIVE_FIRERED_REV0.iconTables
            FIRERED_REV1_SHA1 -> NATIVE_FIRERED_REV1.iconTables
            EMERALD_SHA1 -> NATIVE_EMERALD_RETAIL.iconTables
            TelemetrySampler.EMERALD_KAIZO_V2_0_SHA1 -> NATIVE_EMERALD_KAIZO.iconTables
            TelemetrySampler.ORANGE_ISLANDS_UNVERSIONED_SHA1, TelemetrySampler.ORANGE_ISLANDS_V5_7_BETA_SHA1 ->
                NATIVE_ORANGE_ISLANDS.iconTables
            else -> TelemetrySampler.fireRedHackConfig(sha1)?.iconTables
                ?: if (code != null && revision != null && code in TelemetrySampler.OTHER_RETAIL_CODES)
                    TelemetrySampler.otherRetailConfig(code, revision)?.iconTables else null
        }?.takeIf { it.monPresent }

        /** A FireRed / LeafGreen / Emerald build's base game's retail tables (English: other languages are [knownTables]'). */
        private fun baseTables(code: String?, revision: Int?): IconTables? = when {
            code == null || revision == null -> null
            code.startsWith("BPR") -> (if (revision >= 1) NATIVE_FIRERED_REV1 else NATIVE_FIRERED_REV0).iconTables
            code.startsWith("BPG") -> TelemetrySampler.otherRetailConfig("BPGE", revision.coerceIn(0, 1))?.iconTables
            code.startsWith("BPE") -> NATIVE_EMERALD_RETAIL.iconTables
            else -> null
        }?.takeIf { it.monPresent }

        /** [base] at the addresses [rom]'s Game Freak ROM header gives (monIcons, monIconPaletteIds, monIconPalettes at
         * 0x138; the decomps' rom_header_gf.c), or null without one. FireRed / LeafGreen / Emerald only: Ruby / Sapphire's
         * carts have no such header. */
        private fun headerTables(rom: Path, base: IconTables): IconTables? = runCatching {
            withRomFile(rom) { f ->
                val h = read(f, ROM_BASE + 0x138, 12)
                val icons = u32(h, 0); val ids = u32(h, 4); val pals = u32(h, 8)
                if (listOf(icons, ids, pals).all { it >= ROM_BASE && it - ROM_BASE < f.size() })
                    base.copy(monIconTable = icons, monIconPaletteIndices = ids, monIconPaletteTable = pals)
                else null
            }
        }.getOrNull()

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
                    inFile(ptr) && pal in 0..5 && (tables.monIconPalettes != 0L ||
                        inFile(u32(read(f, tables.monIconPaletteTable + pal * 8L, 4), 0)))
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
