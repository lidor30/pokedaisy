package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * HeartGold / SoulSilver's Pokégear map on the MAP tab, put together the way the game does (overlay_101_021E9270.c's
 * ov101_021EAF40) from the cart's own pieces ([NdsArt] saves them): the 47 x 20 cells of the map's 64 x 64 tilemap, then
 * - Kanto covered until it's unlocked (the Pokégear's mapUnlockLevel: 0 Johto only, 1 + Routes 26-28, Victory Road and
 *   Indigo Plateau, 2 everything), from columns 48 / 54 of the tilemap;
 * - a cover over every town not visited yet (its flypoint flag clear, [pokegearCoversHeartGold]); Johto's only once all of
 *   Kanto is unlocked, as the game does;
 * - the Safari Zone's corner without Goldenrod's flypoint.
 * A cell is 8 x 8 pixels and a main-matrix block: block (x, y) is cell (x, y + 2). The picture for each state is written
 * once into rom-art's regionmap/ ([image]); the Sinjoh Ruins / S.S. Aqua pieces (shown only while there) are left out.
 */
object PokegearMap {
    private val lock = SynchronizedObject()
    const val TILES = "nds/heartgold/pgmap_tiles.bin"
    const val TILEMAP = "nds/heartgold/pgmap_map.bin"
    fun palette(skin: Int) = "nds/heartgold/pgmap_pal_$skin.bin"
    const val SKINS = 6
    const val HEAD_M = "player_ethan"
    const val HEAD_F = "player_lyra"
    /** The map cursor's two frames under regionmap/ (16 x 16, the red square centred: 14 / 16 pixels across). */
    fun cursor(frame: Int) = "hgss_cursor_$frame"
    const val W = 47
    const val H = 20
    private const val SRC_W = 64

    /** rom-art's folder ([NdsArt.prefetch] sets it). */
    @Volatile var dir: Path? = null

    /** The Pokégear's state from the save: [Telemetry.pokegearState] (skin << 2 | unlock level) and the flypoint flags
     * ([Telemetry.townsVisited], bit n = flypoint n). Set with each snapshot. */
    @Volatile var state: Pair<Int, Long>? = null

    /** The picture's name under regionmap/ for the current [state], written first if it isn't there; null without the
     * cart's pieces or a state yet. */
    fun image(): String? {
        synchronized(lock) {
            val d = dir ?: return null
            val (packed, flags) = state ?: return null
            val skin = (packed shr 2).coerceIn(0, SKINS - 1)
            val unlock = packed and 3
            val name = "hgss_pokegear_${skin}_${unlock}_${flags.toULong().toString(16)}"
            val out = d / "regionmap/$name.png"
            if (out.isFile) return name
            val tiles = (d / TILES).takeIf { it.isFile }?.readBytes() ?: return null
            val tilemap = (d / TILEMAP).takeIf { it.isFile }?.readBytes() ?: return null
            val pal = (d / palette(skin)).takeIf { it.isFile }?.readBytes() ?: return null
            val img = compose(tiles, tilemap, pal, unlock) { flag -> flypointSet(flags, flag) }
            out.writeAtomically(img.png())
            return name
        }
    }

    /** Flag [flag] (an event flag number from FLAG_SYS_FLYPOINT_PALLET on) in the flypoint bits [flags]. */
    fun flypointSet(flags: Long, flag: Int): Boolean {
        val bit = flag - FIRST_FLYPOINT_FLAG
        return bit in 0 until 64 && (flags ushr bit) and 1L != 0L
    }

    /** ov101_021EAF40 over the raw pieces: [tilemap] the 64 x 64 u16 cells, [tiles] 8bpp, [pal] BGR555. */
    fun compose(tiles: ByteArray, tilemap: ByteArray, pal: ByteArray, unlock: Int, visited: (Int) -> Boolean): RomArt.Image {
        val cells = IntArray(W * H)
        fun copy(dx: Int, dy: Int, w: Int, h: Int, sx: Int, sy: Int) {
            for (y in 0 until h) for (x in 0 until w) {
                val tx = dx + x
                val ty = dy + y
                if (tx !in 0 until W || ty !in 0 until H) continue
                val o = ((sy + y) * SRC_W + sx + x) * 2
                if (o + 1 < tilemap.size) cells[ty * W + tx] = (tilemap[o].toInt() and 0xFF) or ((tilemap[o + 1].toInt() and 0xFF) shl 8)
            }
        }
        copy(0, 0, W, H, 0, 0)
        when (unlock) {
            0 -> copy(22, 0, 6, 20, 48, 0)
            1 -> copy(29, 0, 3, 20, 54, 0)
        }
        for (c in pokegearCoversHeartGold) {
            val (flag, dx, dy, w) = c
            if (unlock < 2 && c[7] == 0) continue
            if (visited(flag)) continue
            copy(dx, dy, w, c[4], c[5], c[6])
        }
        // canSeeSafariZone is always set; without Goldenrod's flypoint its corner has a cover too.
        if (!visited(FIRST_FLYPOINT_FLAG + FLYPOINT_GOLDENROD)) copy(1, 9, 5, 6, 48, 27)
        val colours = IntArray(pal.size / 2) { i ->
            val v = (pal[i * 2].toInt() and 0xFF) or ((pal[i * 2 + 1].toInt() and 0xFF) shl 8)
            (0xFF shl 24) or (((v and 31) * 255 / 31) shl 16) or ((((v shr 5) and 31) * 255 / 31) shl 8) or (((v shr 10) and 31) * 255 / 31)
        }
        val img = RomArt.Image(W * 8, H * 8)
        for (cy in 0 until H) for (cx in 0 until W) {
            val e = cells[cy * W + cx]
            val tile = e and 0x3FF
            val hf = e and 0x400 != 0
            val vf = e and 0x800 != 0
            for (py in 0 until 8) for (px in 0 until 8) {
                val sx = if (hf) 7 - px else px
                val sy = if (vf) 7 - py else py
                val i = tile * 64 + sy * 8 + sx
                val c = if (i < tiles.size) tiles[i].toInt() and 0xFF else 0
                img.argb[(cy * 8 + py) * img.width + cx * 8 + px] = colours.getOrElse(c) { 0xFF000000.toInt() }
            }
        }
        return img
    }

    /** The MAP tab's model: one picture, the cells' places, 8-pixel cells from the corner. */
    fun model(): RegionMapModel? {
        val name = image() ?: return null
        return RegionMapModel(
            images = arrayOf(name),
            sections = placeNamesHeartGold.mapValues { (id, n) -> MapSecInfo(NdsArt.names?.place(id) ?: n, 0, 0, 0, 0, 0) },
            layouts = listOf(RegionLayout(0, 0, W, H, 0xFFFF, listOf(hexBytes(POKEGEAR_GRID_HEARTGOLD)), cellBytes = 2)),
        )
    }

    /** The place at cell ([cx], [cy]), or -1. */
    fun placeAt(cx: Int, cy: Int): Int {
        if (cx !in 0 until W || cy !in 0 until H) return -1
        val i = (cy * W + cx) * 4
        val v = POKEGEAR_GRID_HEARTGOLD.substring(i, i + 4).let { it.substring(2, 4) + it.substring(0, 2) }.toInt(16)
        return if (v == 0xFFFF) -1 else v
    }

    /** FLAG_SYS_FLYPOINT_PALLET: flypoint n's flag is this + n. */
    const val FIRST_FLYPOINT_FLAG = 0x9B0
    private const val FLYPOINT_GOLDENROD = 15
}
