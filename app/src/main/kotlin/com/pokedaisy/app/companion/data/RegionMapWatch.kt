package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile
import okio.Path

/**
 * Whether the game has its region map (Town Map, Fly map, a wall map, the
 * PokéNav's map) open, so fast-forward can step aside while the player moves
 * its cursor. Each map screen keeps its state in an EWRAM pointer that's only
 * set while it's open ([find] locates them per ROM); [isOpen] checks them
 * once a frame.
 *
 * - FireRed / LeafGreen and the hacks built on them: region_map.c's
 *   `sRegionMap` (Town Map, Fly map and wall maps alike), found from the
 *   literal pools that load it next to its struct offset `0x4796`.
 * - Emerald: field_region_map.c's
 *   `sFieldRegionMapHandler`, region_map.c's `sFlyMap`, and - Emerald has no
 *   Town Map item, its map is the PokéNav's HOENN MAP - pokenav.c's
 *   `gPokenavResources->substructPtrs[POKENAV_SUBSTRUCT_REGION_MAP_STATE]`,
 *   set only while that page is up. Emerald hacks move them, so they get
 *   nothing yet.
 */
object RegionMapWatch {
    /** An EWRAM pointer that's non-null while a map is open - or, with [field] >= 0, the pointer at that offset into what it points to. */
    data class Watch(val addr: Long, val field: Int = -1)

    @Volatile
    private var watches = emptyList<Watch>()

    /** Scans [rom] in the background for its map pointers. */
    fun load(rom: Path) {
        watches = emptyList()
        background("pokedaisy-map-watch") {
            try {
                watches = withRomFile(rom) { f -> find(f.readFully(0, minOf(f.size(), SCAN_END.toLong()).toInt())) }
            } catch (t: Throwable) {
                Logs.w("RegionMapWatch: scan failed", t)
            }
        }
    }

    /** True while any of this ROM's map screens is open. */
    fun isOpen(reader: MemoryReader): Boolean {
        for (w in watches) {
            var p = read32(reader, w.addr) ?: continue
            if (w.field >= 0) {
                if (p !in EWRAM) continue
                p = read32(reader, p + w.field) ?: continue
            }
            if (p != 0L) return true
        }
        return false
    }

    private fun read32(reader: MemoryReader, addr: Long): Long? {
        val b = runCatching { reader.readCoreMemory(addr, 4) }.getOrNull() ?: return null
        if (b.size < 4) return null
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    }

    private val EWRAM = 0x02000000L until 0x02040000L
    private const val SCAN_END = 4 shl 20 // the code, where the literal pools are
    private const val FR_REGION_MAP_OFFSET = 0x4796 // a struct RegionMap field the code loads beside sRegionMap
    private const val EM_FIELD_REGION_MAP = 0x0203BCD0L
    private const val EM_FLY_MAP = 0x0203A148L
    private const val EM_POKENAV_RESOURCES = 0x0203CF40L
    // Japanese Emerald's (English's literal pools, matched in its code).
    private const val JA_FIELD_REGION_MAP = 0x0203B99CL
    private const val JA_FLY_MAP = 0x02039E14L
    private const val JA_POKENAV_RESOURCES = 0x0203CC0CL
    // Emerald Legacy's (its own build's symbols): a BPEE header, its own RAM.
    private const val LEGACY_FIELD_REGION_MAP = 0x0203BFF8L
    private const val LEGACY_FLY_MAP = 0x0203A470L
    private const val LEGACY_POKENAV_RESOURCES = 0x0203D268L
    // struct PokenavResources: 4 words of state, then substructPtrs[]; REGION_MAP_STATE is index 3.
    private const val EM_POKENAV_REGION_MAP_STATE = 0x10 + 3 * 4

    /** The map pointers to watch for the ROM whose first bytes are [rom] (at least its code). */
    fun find(rom: ByteArray): List<Watch> {
        if (rom.size < 0x100) return emptyList()
        fun u32(i: Int) = (rom[i].toLong() and 0xFF) or ((rom[i + 1].toLong() and 0xFF) shl 8) or
            ((rom[i + 2].toLong() and 0xFF) shl 16) or ((rom[i + 3].toLong() and 0xFF) shl 24)
        val end = minOf(rom.size, SCAN_END) - 8
        return when (asciiString(rom, 0xAC, 4)) {
            // the other-language FireRed / LeafGreen too: the pattern finds their own pointer (and
            // Ultra Violet's, FireRed 1.0 under its own code)
            "BPRE", "BPGE", "BPRD", "BPRF", "BPRI", "BPRS", "BPGF", "BPGI", "BPGS", "PUVV" -> {
                // Recharged Yellow is a gcc rebuild: its pools put another global between sRegionMap and the
                // offset - that one reads 1 from the first Town Map on, which would hold SMART FF at 1x for good.
                val gap = if (isRechargedYellowRom(rom)) 8 else 4
                val seen = HashMap<Long, Int>()
                for (i in gap..end step 4) {
                    val a = u32(i - gap + 4)
                    if (a in EWRAM && u32(i + 4) == FR_REGION_MAP_OFFSET.toLong()) seen[a] = (seen[a] ?: 0) + 1
                }
                // sRegionMap is loaded that way 3 times; another pointer twice.
                seen.filterValues { it >= 3 }.keys.map { Watch(it) }
            }
            "BPEE", "BPES", "BPED", "BPEF", "BPEI", "BPEJ" -> { // the European Emeralds keep English's RAM
                val ja = asciiString(rom, 0xAC, 4) == "BPEJ"
                // (field map, fly map, PokéNav resources): retail's, or a rebuild's own (Emerald Legacy) - whichever
                // the ROM's literal pools hold.
                val candidates = if (ja) listOf(longArrayOf(JA_FIELD_REGION_MAP, JA_FLY_MAP, JA_POKENAV_RESOURCES))
                else listOf(
                    longArrayOf(EM_FIELD_REGION_MAP, EM_FLY_MAP, EM_POKENAV_RESOURCES),
                    longArrayOf(LEGACY_FIELD_REGION_MAP, LEGACY_FLY_MAP, LEGACY_POKENAV_RESOURCES),
                )
                val seen = candidates.map { IntArray(it.size) }
                for (i in 0..end step 4) {
                    val a = u32(i)
                    candidates.forEachIndexed { c, want -> for (k in want.indices) if (a == want[k]) seen[c][k]++ }
                }
                // Retail loads them 7, 15 and 15 times; a hack that moved them
                // might still use one of the addresses for something else.
                val hit = candidates.indices.firstOrNull { c -> seen[c].all { it >= 5 } }
                if (hit != null) {
                    val (field, fly, pokenav) = candidates[hit]
                    listOf(Watch(field), Watch(fly), Watch(pokenav, EM_POKENAV_REGION_MAP_STATE))
                } else emptyList()
            }
            else -> emptyList()
        }
    }
}
