package com.pokedaisy.app.companion.data

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Party / bag icons pulled straight out of the running FireRed/Emerald ROM,
 * so nothing has to be bundled. Uses the icon-table addresses its config
 * knows ([IconTables]) — set [tables] once per poll. Sibling of [UnboundIconSource]; the difference is the decomp layout:
 *
 *  - mon icons: `gMonIconTable[species]` -> 1024 bytes of **uncompressed** 4bpp
 *    tiles (32x64, two frames — crop to the first). Palette is a separate
 *    indirection: `gMonIconPaletteIndices[species]` picks a
 *    `gMonIconPaletteTable[]` entry (`{u32 data; u16 tag}`, 8 bytes) whose
 *    `data` points at 32 bytes of BGR555.
 *  - item icons: `gItemIconTable[item][2]` = `{LZ77 tiles, LZ77 palette}`,
 *    24x24 — identical to Unbound. Newer expansion uses smol tiles ([Smol]).
 *
 * pokeemerald-expansion hacks keep the same data but inside each
 * gSpeciesInfo/gItemsInfo entry instead - [IconTables]' stride/mask fields
 * cover that without a separate code path.
 *
 * Reads go through [InProcessReader], so call from the emulator thread (which
 * the icon composables don't — accepted small race for cosmetic sprites, same
 * as [UnboundIconSource]).
 */
object DecompIconSource {

    /** The running game's icon tables. The caches are keyed by id alone, so a
     * different game's tables drop them (else its icons show the last game's). */
    @Volatile
    var tables: IconTables = IconTables()
        set(value) {
            val changed = value != field
            if (changed) {
                monCache.clear()
                itemCache.clear()
            }
            field = value
            if (changed) iconsChanged()
        }

    /** Bumped when icons may read differently now (new tables, or the core came up under a
     * launch's saved companion data): icon composables key their loads on it. */
    val generation = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun iconsChanged() { generation.value++ }

    /** Swappable so tests / previews can read a ROM file instead of the core. */
    @Volatile
    var reader: MemoryReader = InProcessReader


    private val monCache = ConcurrentHashMap<Int, Bitmap>()
    private val itemCache = ConcurrentHashMap<Int, Bitmap>()
    private val missing = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val lock = Any()
    @Volatile private var loggedOnce = false

    /** An already-decoded icon (no ROM read), or null. */
    fun peek(species: Int): Bitmap? = if (tables.monPresent) monCache[species] else null

    fun get(species: Int): Bitmap? = cached(monCache, species, tables.monPresent) { fetchMon(species) }

    /** [species]' icon in its shiny palette (SoulGold keeps one per species, beside the normal one); null where the
     * tables have no per-species palettes. */
    fun getShiny(species: Int): Bitmap? = if (tables.monIconPalettes == 0L && tables.battlePalettes == 0L) null
        else cached(monCache, SHINY_KEY + species, tables.monPresent) { decodeMonIcon(tables, species, shiny = true, rd = ::rd) }
    fun peekShiny(species: Int): Bitmap? = if (tables.monPresent) monCache[SHINY_KEY + species] else null

    /** An already-decoded item icon (no ROM read), or null. */
    fun peekItem(itemId: Int): Bitmap? = if (tables.itemPresent) itemCache[1_000_000 + itemId] else null
    fun getItem(itemId: Int): Bitmap? = cached(itemCache, 1_000_000 + itemId, tables.itemPresent) { fetchItem(itemId) }

    private inline fun cached(cache: ConcurrentHashMap<Int, Bitmap>, key: Int, gate: Boolean, fetch: () -> Bitmap?): Bitmap? {
        if (key <= 0 || !gate) return null
        cache[key]?.let { return it }
        val sig = tables.monIconTable * 31 + key
        if (sig in missing) return null
        synchronized(lock) {
            cache[key]?.let { return it }
            // A read that failed (the core not up yet) isn't a missing icon: the next try may work.
            val bmp = try { fetch() } catch (e: Exception) {
                android.util.Log.w("pokedaisy", "DecompIcon fetch $key failed", e); return null
            }
            if (bmp == null) { missing.add(sig); return null }
            if (!loggedOnce) {
                loggedOnce = true
                android.util.Log.i("pokedaisy", "DecompIcon: first sprite decoded ${bmp.width}x${bmp.height} from tables=$tables")
            }
            cache[key] = bmp
            return bmp
        }
    }

    private fun rd(addr: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val chunk = minOf(512, n - off)
            reader.readCoreMemory(addr + off, chunk).copyInto(out, off, 0, chunk)
            off += chunk
        }
        return out
    }

    private fun fetchMon(species: Int): Bitmap? = decodeMonIcon(tables, species, rd = ::rd)

    private fun fetchItem(itemId: Int): Bitmap? {
        val t = tables
        val extra = itemId - t.extraItemFirst
        val entry = if (t.extraItemIconTable != 0L && extra in 0 until t.extraItemCount) {
            rd(t.extraItemIconTable + extra.toLong() * t.extraItemStride, 8)
        } else {
            rd(t.itemIconTable + itemId.toLong() * t.itemIconStride, 8)
        }
        val tilesPtr = Gfx.u32(entry, 0)
        val palPtr = Gfx.u32(entry, 4)
        if (!Gfx.inRom(tilesPtr) || !Gfx.inRom(palPtr)) return null
        // Newer expansion (Heart and Soul) smol-compresses these instead of LZ77.
        val raw = rd(tilesPtr, 640)
        val tiles = if (Smol.isSmol(raw)) Smol.decompress(raw) else Gfx.lz77(raw)
        val pal = if (t.itemPalCompressed) Gfx.lz77(rd(palPtr, 96)) else rd(palPtr, PAL_BYTES)
        if (tiles.size < 3 * 3 * 32 || pal.size < PAL_BYTES) return null
        return Gfx.decode4bpp(tiles, 0, pal, 0, 3, 3)
    }
}

/**
 * Species [species]' party icon (32x64, both frames) from [t]'s tables, read through [rd] - the running core
 * ([DecompIconSource]) or a ROM file (`RomMonIcons`, the save editor's). Null if a pointer leaves the ROM.
 */
internal fun decodeMonIcon(t: IconTables, species: Int, shiny: Boolean = false, rd: (Long, Int) -> ByteArray): Bitmap? {
    val tilesPtr = Gfx.u32(rd(t.monIconTable + species.toLong() * t.monIconStride, 4), 0)
    if (!Gfx.inRom(tilesPtr)) {
        android.util.Log.w("pokedaisy", "DecompIcon mon $species: tilesPtr %08x not in ROM".format(tilesPtr)); return null
    }
    val palIdx = rd(t.monIconPaletteIndices + species.toLong() * t.monPalIdxStride, 1)[0].toInt() and t.monPalIdxMask
    val palDataPtr = if (t.monIconPalettes != 0L) {
        // The shiny palette's pointer follows the normal one's (+0x94 / +0x98 in SoulGold's gSpeciesInfo).
        Gfx.u32(rd(t.monIconPalettes + species.toLong() * t.monIconStride + (if (shiny) 4 else 0), 4), 0)
    } else {
        Gfx.u32(rd(t.monIconPaletteTable + palIdx.toLong() * MON_PAL_ENTRY_BYTES, MON_PAL_ENTRY_BYTES), 0)
    }
    if (!Gfx.inRom(palDataPtr)) {
        android.util.Log.w("pokedaisy", "DecompIcon mon $species: palPtr %08x not in ROM (idx=$palIdx)".format(palDataPtr)); return null
    }
    val tiles = if (t.monIconPacked) MonomythIcons.unpack(rd, tilesPtr) ?: return null else rd(tilesPtr, MON_TILE_BYTES)
    var pal = rd(palDataPtr, PAL_BYTES)
    if (shiny && t.monIconPalettes == 0L) pal = shinyIconPalette(t, species, pal, rd) ?: return null
    // 32x64 = 4x8 tiles; UI crops to the first 32x32 frame.
    return Gfx.decode4bpp(tiles, 0, pal, 0, 4, 8)
}

/**
 * A shared-palette icon (FireRed / Emerald: six icon palettes, none shiny) in [species]' shiny colours: each icon colour
 * swapped for the shiny battle palette's colour where its nearest normal battle colour sits. The game never shows shiny
 * icons; these follow the shiny Pokémon's own colours, from the ROM. Null without the tables (or a tag that doesn't match).
 */
internal fun shinyIconPalette(t: IconTables, species: Int, iconPal: ByteArray, rd: (Long, Int) -> ByteArray): ByteArray? {
    if (t.battlePalettes == 0L) return null
    fun battlePal(table: Long, tag: Int): IntArray? {
        val entry = rd(table + species * 8L, 8)
        if (u16le(entry, 4) != tag) return null
        val ptr = Gfx.u32(entry, 0)
        if (!Gfx.inRom(ptr)) return null
        // 16 colours LZ77-packed (header + flags + up to 32 literal bytes; read a margin).
        val raw = runCatching { Gfx.lz77(rd(ptr, 128)) }.getOrNull() ?: return null
        if (raw.size < PAL_BYTES) return null
        return IntArray(16) { u16le(raw, it * 2) }
    }
    val normal = battlePal(t.battlePalettes, species) ?: return null
    val shiny = battlePal(t.battlePalettes + SHINY_PALETTE_TABLE_OFFSET, species + SHINY_PALETTE_TAG) ?: return null
    fun dist(a: Int, b: Int): Int {
        val r = (a and 31) - (b and 31); val g = (a shr 5 and 31) - (b shr 5 and 31); val bl = (a shr 10 and 31) - (b shr 10 and 31)
        return r * r + g * g + bl * bl
    }
    val out = iconPal.copyOf()
    // Colour 0 is transparent in both.
    for (i in 1 until 16) {
        val c = u16le(iconPal, i * 2)
        val k = (1 until 16).minBy { dist(c, normal[it]) }
        out[i * 2] = (shiny[k] and 0xFF).toByte()
        out[i * 2 + 1] = (shiny[k] shr 8 and 0xFF).toByte()
    }
    return out
}

/** gMonShinyPaletteTable's place after gMonPaletteTable: 440 entries of 8 bytes (pokefirered rev 1 and pokeemerald's maps),
 * checked per entry by its tag, the species + [SHINY_PALETTE_TAG]. */
private const val SHINY_PALETTE_TABLE_OFFSET = 0xDC0L
private const val SHINY_PALETTE_TAG = 500

private const val MON_TILE_BYTES = 1024
/** [DecompIconSource]'s cache key offset for shiny icons (species ids stay under it; items are 1_000_000+). */
private const val SHINY_KEY = 500_000
private const val PAL_BYTES = 32
private const val MON_PAL_ENTRY_BYTES = 8
