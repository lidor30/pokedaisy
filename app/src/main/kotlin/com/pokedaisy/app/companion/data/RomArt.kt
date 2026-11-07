package com.pokedaisy.app.companion.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Game art the companion draws with - FireRed's / Emerald's party menu (slot,
 * Poke Ball, status icons, small font and backdrop), region maps and their
 * player icons, and the hacks' own (Heart and Soul's and the CFRU hacks' party
 * menus, region maps, ...) - is rebuilt from the player's own ROM instead of
 * being bundled. The first time
 * a ROM is played, [prefetch] scans it once for each [RomBlob] (found by
 * fingerprint, so retail and hacks that kept the art all work),
 * composes the same PNGs the app used to ship under the same paths
 * (`partyfr/slot_normal.png`, `regionmap/kanto.png`, ...) and keeps them in
 * [DIR] under filesDir for every later launch, whichever game is running.
 *
 * Colours use mGBA's 5->8 bit expansion, like the party-slot generator.
 */
object RomArt {
    const val DIR = "rom-art"

    /** Bump when [OUTPUTS] grows, so ROMs scanned before get scanned again. */
    private const val SCAN_VERSION = 27

    /** Bumped whenever new art lands on disk, so loaders can retry. */
    val updates: StateFlow<Int> get() = _updates
    private val _updates = MutableStateFlow(0)

    @Volatile private var running = false

    /** Tells loaders new art landed in [dir] (see [RomRegionMap]). */
    internal fun announce() {
        _updates.value++
    }

    /** Swappable so tests / previews can keep the art elsewhere (Paparazzi has no filesDir). */
    @Volatile
    var dirOverride: File? = null

    /** Where the art lives: [DIR] under the app's filesDir. */
    fun dir(filesDir: File?): File = dirOverride ?: File(filesDir, DIR)

    /** The cached file for an art [path], or null if no ROM has supplied it yet. */
    fun file(filesDir: File?, path: String): File? = File(dir(filesDir), path).takeIf { it.isFile }

    /**
     * Scans [rom] in the background unless a ROM with this [romKey] was
     * already scanned, or every file is already cached. A few hundred ms, once.
     */
    fun prefetch(filesDir: File, romKey: String, rom: File) {
        val dir = dir(filesDir)
        val marker = File(dir, "scanned/v$SCAN_VERSION-$romKey")
        if (marker.isFile || running || OUTPUTS.all { File(dir, it).isFile }) return
        running = true
        Thread({
            try {
                val t0 = System.nanoTime()
                val wrote = extractTo(readChunked(rom), dir)
                marker.parentFile?.mkdirs()
                marker.writeText(wrote.joinToString("\n"))
                android.util.Log.i("pokedaisy", "RomArt: ${wrote.size} new files from ${rom.name} in ${(System.nanoTime() - t0) / 1_000_000} ms")
                if (wrote.isNotEmpty()) _updates.value++
            } catch (t: Throwable) {
                android.util.Log.w("pokedaisy", "RomArt: scan failed", t)
            } finally {
                running = false
            }
        }, "pokedaisy-rom-art").apply { isDaemon = true; start() }
    }

    /** The whole file, 1 MiB per read: one 32 MiB read holds a JNI critical lock (stalling GC) for ~20 ms. */
    private fun readChunked(f: File): ByteArray {
        val out = ByteArray(f.length().toInt())
        f.inputStream().use { s ->
            var o = 0
            while (o < out.size) {
                val n = s.read(out, o, minOf(1 shl 20, out.size - o))
                if (n < 0) break
                o += n
            }
        }
        return out
    }

    /** Writes every art file [rom] can supply that [dir] doesn't have yet; returns their paths. */
    fun extractTo(rom: ByteArray, dir: File): List<String> {
        val wanted = OUTPUTS.filter { !File(dir, it).isFile }
        if (wanted.isEmpty()) return emptyList()
        val found = find(rom)
        val images = compose(found)
        val raw = raw(found)
        return wanted.mapNotNull { path ->
            val bytes = images[path]?.png() ?: raw[path] ?: return@mapNotNull null
            val f = File(dir, path)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            path
        }
    }

    /** The region map's player icons (16x16 heads) by art name: FireRed's Red / Leaf, Emerald's Brendan / May. */
    private val PLAYER_ICONS = listOf(
        "player_red" to (RomBlob.FR_PLAYER_RED_GFX to RomBlob.FR_PLAYER_PAL),
        "player_leaf" to (RomBlob.FR_PLAYER_LEAF_GFX to RomBlob.FR_PLAYER_PAL),
        "player_brendan" to (RomBlob.EM_PLAYER_BRENDAN_GFX to RomBlob.EM_PLAYER_BRENDAN_PAL),
        "player_may" to (RomBlob.EM_PLAYER_MAY_GFX to RomBlob.EM_PLAYER_MAY_PAL),
        // Super Mariomon's own head for its map, in Brendan's slot (May's is retail's).
        "player_mario" to (RomBlob.MM_PLAYER_MARIO_GFX to RomBlob.MM_PLAYER_MARIO_PAL),
        // Recharged Emerald redrew both heads for its own map.
        "player_brendan_re" to (RomBlob.RE_PLAYER_BRENDAN_GFX to RomBlob.RE_PLAYER_BRENDAN_PAL),
        "player_may_re" to (RomBlob.RE_PLAYER_MAY_GFX to RomBlob.RE_PLAYER_MAY_PAL),
        // Monomyth's map code loads one head, whichever gender the player is.
        "player_monomyth" to (RomBlob.MMY_PLAYER_GFX to RomBlob.MMY_PLAYER_PAL),
    )

    /**
     * The CFRU hacks' (Unbound, Radical Red, Odyssey, Amethyst) status icons, one per hack, by
     * their art dir under `partycfru/`. Their slot frames, backdrop and palettes are shared.
     */
    private val CFRU_STATUS = listOf(
        "ub" to RomBlob.UB_STATUS_GFX, "rr" to RomBlob.RR_STATUS_GFX,
        "od" to RomBlob.OD_STATUS_GFX, "am" to RomBlob.AM_STATUS_GFX,
    )

    /** Unbound FR's party menu: English Unbound's, with its own slot tiles ("PV"), status icons and FONT_SMALL ("N."). */
    const val UNBOUND_FR_PARTY_DIR = "partycfru_fr"

    /** SoulGold's bag screen backdrop (ItemsScreen's BagPalette.backdropArt). */
    const val BAG_STARS_SOULGOLD = "bagbg/soulgold.png"

    /** Every file [compose] can make. */
    val OUTPUTS: List<String> = run {
        val slots = listOf("normal", "selected", "fainted", "selected_fainted", "nohp_normal", "nohp_selected")
        (listOf("partyfr", "partyem", "partyhns", RE_PARTY_DIR) + listOf("es", "de", "fr", "it").flatMap { listOf(emeraldPartyDir(it), fireRedPartyDir(it)) }).flatMap { d ->
            slots.map { "$d/slot_$it.png" } + listOf("$d/status_icons.png", "$d/font_small.png") +
                listOfNotNull("$d/pokeball.png".takeIf { d == "partyfr" || d == "partyem" || d == "partyhns" })
        } + listOf("partybg/firered.png", "partybg/emerald.png", "partybg/hns.png", RE_BACKDROP, CFRU_BACKDROP) +
            (slots + "empty").map { "partycfru/slot_$it.png" } + CFRU_STATUS.map { "partycfru/${it.first}/status_icons.png" } +
            (slots + "empty").map { "$UNBOUND_FR_PARTY_DIR/slot_$it.png" } +
            listOf("$UNBOUND_FR_PARTY_DIR/status_icons.png", "$UNBOUND_FR_PARTY_DIR/font_small.png") +
            listOf(SWSH_BACKDROP) + (slots + "empty").map { "$SWSH_PARTY_DIR/slot_$it.png" } +
            listOf(LETS_GO_BACKDROP, "$LETS_GO_PARTY_DIR/status_icons.png") + (slots + "empty").map { "$LETS_GO_PARTY_DIR/slot_$it.png" } +
            listOf("kanto", "sevii123", "sevii45", "sevii67", "hoenn", "seaglass", "lazarus", "soulgold", "glazed", "imperium", "hns", "recharged_emerald", "rowe_hoenn", "rowe_kanto", "rowe_sevii",
                "mariomon", "quetzal_kanto", "quetzal_sevii123", "quetzal_sevii45", "quetzal_sevii67", "quetzal_johto", "dreamstone", "wishes", "monomyth",
                "tr3_kanto", "tr3_sevii123", "tr3_sevii45", "tr3_sevii67", "tr3_johto", "tr3_hoenn", "tr3_sinnoh").map { "regionmap/$it.png" } +
            PLAYER_ICONS.map { "regionmap/${it.first}.png" } +
            listOf(BAG_STARS_SOULGOLD, HOLD_ICONS, TYPE_TAGS_FIRERED, TYPE_TAGS_EMERALD) +
            TrainerCardArt.BLOBS.map { rawPath(it) }
    }

    /** Blobs kept as decoded bytes rather than composed ([TrainerCardArt] draws from them). */
    fun rawPath(b: RomBlob) = "trainercard/${b.name.lowercase()}.bin"

    /** The decoded bytes of every [TrainerCardArt.BLOBS] blob [found], by art path. */
    fun raw(found: Map<RomBlob, ByteArray>): Map<String, ByteArray> =
        TrainerCardArt.BLOBS.mapNotNull { b -> found[b]?.let { rawPath(b) to it } }.toMap()


    /** ARGB pixels, 0 = transparent. */
    class Image(val width: Int, val height: Int, val argb: IntArray = IntArray(width * height)) {
        fun png(): ByteArray = Png.encode(this)
    }

    // --- finding the blobs ---

    private fun fmix(k0: Long): Long {
        var k = k0
        k = k xor (k ushr 33); k *= -0xae502812aa7333L // 0xFF51AFD7ED558CCD
        k = k xor (k ushr 33); k *= -0x3b314601e57a13adL // 0xC4CEB9FE1A85EC53
        return k xor (k ushr 33)
    }

    /** The 16-byte window hash the generator stored as [RomBlob.head]. */
    internal fun head(b: ByteBuffer, i: Int): Long = fmix(b.getLong(i) xor fmix(b.getLong(i + 8)))

    /** [RomBlob.pre] of the 8 bytes [a] (little-endian): the scan's per-byte filter. */
    internal fun pre(a: Long): Int = ((a * -0x61c8864680b583ebL) ushr 48).toInt() // 0x9E3779B97F4A7C15

    /**
     * Decoded data of every blob found in [rom], in one pass: a rolling 8-byte
     * window through a 16-bit filter, the full [head] only where that hits.
     */
    fun find(rom: ByteArray): Map<RomBlob, ByteArray> {
        val byHead = RomBlob.entries.groupBy { it.head }
        val filter = LongArray(1 shl 10)
        RomBlob.entries.forEach { filter[it.pre ushr 6] = filter[it.pre ushr 6] or (1L shl it.pre) }
        val buf = ByteBuffer.wrap(rom).order(ByteOrder.LITTLE_ENDIAN)
        val found = HashMap<RomBlob, ByteArray>()
        if (rom.size < 16) return found
        var a = buf.getLong(0) shl 8 // shifted in again below, one byte per offset
        for (i in 0..rom.size - 16) {
            a = (a ushr 8) or ((rom[i + 7].toLong() and 0xFF) shl 56)
            val p = pre(a)
            if (filter[p ushr 6] and (1L shl p) == 0L) continue
            for (blob in byHead[head(buf, i)] ?: continue) {
                if (blob in found) continue
                decode(rom, i - blob.headOff, blob)?.let { found[blob] = it }
            }
            if (found.size == RomBlob.entries.size) break
        }
        return found
    }

    private fun decode(rom: ByteArray, off: Int, blob: RomBlob): ByteArray? {
        if (off < 0) return null
        val data = try {
            when {
                blob.smol -> Smol.decompressAny(rom.copyOfRange(off, minOf(rom.size, off + 0x10000))).takeIf { it.size == blob.size }
                blob.lz -> lz77(rom, off, blob.size)
                else -> rom.copyOfRange(off, off + blob.size)
            }
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: IllegalArgumentException) {
            null // not a smol blob after all
        } ?: return null
        val crc = CRC32().apply { update(data) }.value
        return data.takeIf { crc == blob.crc }
    }

    /** GBA LZ77 (type 0x10) at [off], or null if it isn't one of [size] bytes (any size up to 64 KiB if -1). */
    internal fun lz77(rom: ByteArray, off: Int, size: Int): ByteArray? {
        if (rom[off].toInt() != 0x10) return null
        val n = (rom[off + 1].toInt() and 0xFF) or ((rom[off + 2].toInt() and 0xFF) shl 8) or ((rom[off + 3].toInt() and 0xFF) shl 16)
        if (if (size < 0) n == 0 || n > 0x10000 else n != size) return null
        val out = ByteArray(n)
        var o = 0
        var i = off + 4
        while (o < n) {
            val flags = rom[i++].toInt()
            for (b in 7 downTo 0) {
                if (o >= n) break
                if (flags and (1 shl b) != 0) {
                    val d = ((rom[i].toInt() and 0xFF) shl 8) or (rom[i + 1].toInt() and 0xFF)
                    i += 2
                    val disp = (d and 0xFFF) + 1
                    if (disp > o) return null
                    repeat((d ushr 12) + 3) {
                        if (o < n) { out[o] = out[o - disp]; o++ }
                    }
                } else {
                    out[o++] = rom[i++]
                }
            }
        }
        return out
    }

    // --- composing the images (same layouts as scripts/gen_party_assets.py) ---

    private fun expand(v: Int) = (v shl 3) or (v shr 2)

    /** BGR555 -> opaque ARGB. */
    internal fun palette(b: ByteArray): IntArray = IntArray(b.size / 2) {
        val c = (b[it * 2].toInt() and 0xFF) or ((b[it * 2 + 1].toInt() and 0xFF) shl 8)
        (0xFF shl 24) or (expand(c and 31) shl 16) or (expand((c shr 5) and 31) shl 8) or expand((c shr 10) and 31)
    }

    /** Colour index of pixel ([x],[y]) of 4bpp tile [t]. */
    private fun px4(tiles: ByteArray, t: Int, x: Int, y: Int): Int {
        // A hack's tilemap can point past its tiles (at whatever else is in VRAM).
        val b = tiles.getOrElse(t * 32 + y * 4 + x / 2) { 0 }.toInt()
        return if (x and 1 != 0) (b shr 4) and 15 else b and 15
    }

    private fun u16(b: ByteArray, i: Int) = (b[i * 2].toInt() and 0xFF) or ((b[i * 2 + 1].toInt() and 0xFF) shl 8)

    /** A screen of 4bpp BG tiles through a u16 tilemap ([mapW] entries per row), opaque. */
    internal fun screen(
        tiles: ByteArray, pal: IntArray, map: ByteArray, mapW: Int, tilesW: Int = 30, tilesH: Int = 20,
        edit: (Int, Int, Int) -> Int = { _, _, e -> e },
    ): Image {
        val img = Image(tilesW * 8, tilesH * 8)
        for (ty in 0 until tilesH) for (tx in 0 until tilesW) {
            val e = edit(tx, ty, u16(map, ty * mapW + tx))
            val t = e and 0x3FF
            val pn = e shr 12
            for (y in 0 until 8) for (x in 0 until 8) {
                val v = px4(tiles, t, if (e and 0x400 != 0) 7 - x else x, if (e and 0x800 != 0) 7 - y else y)
                img.argb[(ty * 8 + y) * img.width + tx * 8 + x] = pal.getOrElse(pn * 16 + v) { pal[0] }
            }
        }
        return img
    }

    /** A sprite of [w]x[h] from consecutive 4bpp tiles, index 0 transparent. */
    internal fun sprite(tiles: ByteArray, pal: IntArray, w: Int, h: Int, tileOf: (Int, Int) -> Int = { tx, ty -> ty * (w / 8) + tx }): Image {
        val img = Image(w, h)
        for (ty in 0 until h / 8) for (tx in 0 until w / 8) for (y in 0 until 8) for (x in 0 until 8) {
            val v = px4(tiles, tileOf(tx, ty), x, y)
            if (v != 0) img.argb[(ty * 8 + y) * w + tx * 8 + x] = pal[v]
        }
        return img
    }

    private class Party(
        val dir: String, val backdrop: String,
        val bgGfx: RomBlob, val bgPal: RomBlob, val bgMap: RomBlob, val slotMain: RomBlob, val slotNoHp: RomBlob,
        val ball: RomBlob?, val ballPal: RomBlob, val status: RomBlob, val statusPal: RomBlob,
        val font: RomBlob, val halfWidthFont: Boolean,
        /** Backdrop tiles swapped for the plain panel tile 0x100E: the CANCEL button (the app has none). */
        val blank: (Int, Int) -> Boolean = { tx, ty -> tx >= 23 && ty in 17..18 },
    )

    /** Recharged Emerald's party menu: Emerald's party_menu.c and slot tilemaps under its own art (its backdrop, the
     * palettes, its FONT_SMALL; the status icons Emerald's, recompressed; the Poké Ball Emerald's bytes). */
    const val RE_PARTY_DIR = "partyre"
    const val RE_BACKDROP = "partybg/recharged_emerald.png"

    /** One 32x32 cell of the CFRU hacks' party-menu grid, tiled by the app (GameBackdrop). */
    const val CFRU_BACKDROP = "partybg/cfru_tile.png"

    /** Sword and Shield Ultimate Plus' party menu: CFRU's tiles and tilemaps in its own palette (its status icons are Odyssey's). */
    const val SWSH_PARTY_DIR = "partyswsh"
    const val SWSH_BACKDROP = "partybg/swsh_tile.png"

    /** Let's Go Pikachu / Eevee's party menu: CFRU's code and slot tilemaps with its own tiles, palette, backdrop and status icons. */
    const val LETS_GO_PARTY_DIR = "partylg"
    const val LETS_GO_BACKDROP = "partybg/letsgo_tile.png"

    /** The European FireReds' (LeafGreen's are the same bytes): scripts/port_retail.py found them. */
    private val FIRERED_LANGUAGE_PARTIES = listOf(
        Triple("es", RomBlob.FR_ES_PARTY_BG_GFX, RomBlob.FR_ES_STATUS_GFX to RomBlob.FR_ES_FONT_SMALL),
        Triple("de", RomBlob.FR_DE_PARTY_BG_GFX, RomBlob.FR_DE_STATUS_GFX to RomBlob.FR_DE_FONT_SMALL),
        Triple("fr", RomBlob.FR_FR_PARTY_BG_GFX, RomBlob.FR_FR_STATUS_GFX to RomBlob.FR_FR_FONT_SMALL),
        Triple("it", RomBlob.FR_IT_PARTY_BG_GFX, RomBlob.FR_IT_STATUS_GFX to RomBlob.FR_IT_FONT_SMALL),
    )

    private val PARTIES = listOf(
        // Heart and Soul: Emerald's party_menu.c and slot tilemaps under its own art (smol), its
        // own Poke Ball / status sheets and FONT_SMALL. Its backdrop also loses the SEL-ORDER hint.
        Party("partyhns", "partybg/hns.png", RomBlob.HNS_PARTY_BG_GFX, RomBlob.HNS_PARTY_BG_PAL, RomBlob.HNS_PARTY_BG_MAP,
            RomBlob.EM_SLOT_MAIN, RomBlob.EM_SLOT_NO_HP, RomBlob.HNS_BALL_GFX, RomBlob.HNS_BALL_PAL,
            RomBlob.HNS_STATUS_GFX, RomBlob.HNS_STATUS_PAL, RomBlob.HNS_FONT_SMALL, halfWidthFont = false,
            blank = { tx, ty -> (ty == 15 && tx in 3..7) || (tx >= 23 && ty in 17..18) }),
        Party("partyfr", "partybg/firered.png", RomBlob.FR_PARTY_BG_GFX, RomBlob.FR_PARTY_BG_PAL, RomBlob.FR_PARTY_BG_MAP,
            RomBlob.FR_SLOT_MAIN, RomBlob.FR_SLOT_NO_HP, RomBlob.FR_BALL_GFX, RomBlob.FR_BALL_PAL,
            RomBlob.FR_STATUS_GFX, RomBlob.FR_STATUS_PAL, RomBlob.FR_FONT_SMALL, halfWidthFont = true),
        Party("partyem", "partybg/emerald.png", RomBlob.EM_PARTY_BG_GFX, RomBlob.EM_PARTY_BG_PAL, RomBlob.EM_PARTY_BG_MAP,
            RomBlob.EM_SLOT_MAIN, RomBlob.EM_SLOT_NO_HP, RomBlob.EM_BALL_GFX, RomBlob.EM_BALL_PAL,
            RomBlob.EM_STATUS_GFX, RomBlob.EM_STATUS_PAL, RomBlob.EM_FONT_SMALL, halfWidthFont = false),
        Party(RE_PARTY_DIR, RE_BACKDROP, RomBlob.RE_PARTY_BG_GFX, RomBlob.RE_PARTY_BG_PAL, RomBlob.RE_PARTY_BG_MAP,
            // Its Poké Ball is Emerald's bytes: partyem/pokeball.png, from the same scan.
            RomBlob.EM_SLOT_MAIN, RomBlob.EM_SLOT_NO_HP, null, RomBlob.EM_BALL_PAL,
            RomBlob.RE_STATUS_GFX, RomBlob.RE_STATUS_PAL, RomBlob.RE_FONT_SMALL, halfWidthFont = false),
    ) + listOf(
        // The European Emeralds: their own slot tiles (the HP label), status icons and font;
        // the backdrop comes out pixel for pixel English's (only CANCEL's tiles differ).
        Triple("es", RomBlob.EM_ES_PARTY_BG_GFX, RomBlob.EM_ES_STATUS_GFX to RomBlob.EM_ES_FONT_SMALL),
        Triple("de", RomBlob.EM_DE_PARTY_BG_GFX, RomBlob.EM_DE_STATUS_GFX to RomBlob.EM_DE_FONT_SMALL),
        Triple("fr", RomBlob.EM_FR_PARTY_BG_GFX, RomBlob.EM_FR_STATUS_GFX to RomBlob.EM_FR_FONT_SMALL),
        Triple("it", RomBlob.EM_IT_PARTY_BG_GFX, RomBlob.EM_IT_STATUS_GFX to RomBlob.EM_IT_FONT_SMALL),
    ).map { (lang, gfx, statusFont) ->
        Party(emeraldPartyDir(lang), "partybg/emerald.png", gfx, RomBlob.EM_PARTY_BG_PAL, RomBlob.EM_PARTY_BG_MAP,
            // English's Poke Ball, byte for byte: partyem/pokeball.png serves them all.
            RomBlob.EM_SLOT_MAIN, RomBlob.EM_SLOT_NO_HP, null, RomBlob.EM_BALL_PAL,
            statusFont.first, RomBlob.EM_STATUS_PAL, statusFont.second, halfWidthFont = false)
    } + FIRERED_LANGUAGE_PARTIES.map { (lang, gfx, statusFont) ->
        Party(fireRedPartyDir(lang), "partybg/firered.png", gfx, RomBlob.FR_PARTY_BG_PAL, RomBlob.FR_PARTY_BG_MAP,
            RomBlob.FR_SLOT_MAIN, RomBlob.FR_SLOT_NO_HP, null, RomBlob.FR_BALL_PAL,
            statusFont.first, RomBlob.FR_STATUS_PAL, statusFont.second, halfWidthFont = true)
    }


    /** The party menu's held-item marker: an item (top 8x8), Mail (bottom). */
    const val HOLD_ICONS = "partyfr/hold_icons.png"
    /** The games' type tags: the 128x128 sheet their move lists draw 32x12 tags from (TypeBadge knows where each is). */
    const val TYPE_TAGS_FIRERED = "typetags/firered.png"
    const val TYPE_TAGS_EMERALD = "typetags/emerald.png"

    /** Where a European Emerald's party art goes: partyem_<es|de|fr|it>. */
    fun emeraldPartyDir(lang: String) = "partyem_$lang"

    /** Where a European FireRed / LeafGreen's party art goes: partyfr_<es|de|fr|it>. */
    fun fireRedPartyDir(lang: String) = "partyfr_$lang"

    // Window palette 3 with each state's LoadPartyBoxPalette overrides:
    // (idx 4,5,6 <- ids1), (idx 1,7,8 <- ids2) - both decomps' party_menu.c.
    private val SLOT_STATES = listOf(
        "normal" to (intArrayOf(52, 53, 54) to intArrayOf(49, 55, 56)),
        "selected" to (intArrayOf(116, 117, 118) to intArrayOf(97, 103, 104)),
        "fainted" to (intArrayOf(84, 85, 86) to intArrayOf(81, 87, 88)),
        "selected_fainted" to (intArrayOf(148, 149, 150) to intArrayOf(97, 103, 104)),
    )

    /** Every image whose blobs were all [found], by art path. */
    fun compose(found: Map<RomBlob, ByteArray>): Map<String, Image> {
        val out = HashMap<String, Image>()
        fun has(vararg b: RomBlob) = b.all { it in found }
        fun d(b: RomBlob) = found.getValue(b)
        // Each file needs only its own blobs: CFRU hacks keep FireRed's font alone.
        for (p in PARTIES) {
            if (has(p.bgGfx, p.bgPal, p.bgMap)) {
                // The whole screen minus the CANCEL button (the app has none).
                out[p.backdrop] = screen(d(p.bgGfx), palette(d(p.bgPal)), d(p.bgMap), 32) { tx, ty, e ->
                    if (p.blank(tx, ty)) 0x100E else e
                }
            }
            if (has(p.bgGfx, p.bgPal, p.slotMain, p.slotNoHp)) {
                slots(d(p.bgGfx), palette(d(p.bgPal)), d(p.slotMain), d(p.slotNoHp)).forEach { (k, v) -> out["${p.dir}/$k"] = v }
            }
            if (p.ball != null && has(p.ball, p.ballPal)) out["${p.dir}/pokeball.png"] = sprite(d(p.ball), palette(d(p.ballPal)), 32, 64)
            if (has(p.status, p.statusPal)) {
                // 32x8 icons, one after another -> one row.
                val status = d(p.status)
                out["${p.dir}/status_icons.png"] = sprite(status, palette(d(p.statusPal)), status.size / 32 * 8, 8) { tx, _ -> tx }
            }
            if (has(p.font)) out["${p.dir}/font_small.png"] = font(d(p.font), p.halfWidthFont)
        }
        cfru(found, out)
        // The held-item marker (an item over Mail, 8x8 each): FireRed's bytes, Emerald's the same; every slot style uses it.
        if (has(RomBlob.FR_TYPE_TAGS_GFX, RomBlob.FR_TYPE_TAGS_PAL)) out[TYPE_TAGS_FIRERED] = sprite(d(RomBlob.FR_TYPE_TAGS_GFX), palette(d(RomBlob.FR_TYPE_TAGS_PAL)), 128, 128)
        if (has(RomBlob.EM_TYPE_TAGS_GFX, RomBlob.EM_TYPE_TAGS_PAL)) out[TYPE_TAGS_EMERALD] = sprite(d(RomBlob.EM_TYPE_TAGS_GFX), palette(d(RomBlob.EM_TYPE_TAGS_PAL)), 128, 128)
        if (has(RomBlob.FR_HOLD_GFX, RomBlob.FR_HOLD_PAL)) out[HOLD_ICONS] = sprite(d(RomBlob.FR_HOLD_GFX), palette(d(RomBlob.FR_HOLD_PAL)), 8, 16)
        if (has(RomBlob.FR_REGION_GFX, RomBlob.FR_REGION_PAL)) {
            val tiles = d(RomBlob.FR_REGION_GFX)
            val pal = palette(d(RomBlob.FR_REGION_PAL))
            listOf("kanto" to RomBlob.FR_KANTO_MAP, "sevii123" to RomBlob.FR_SEVII123_MAP,
                "sevii45" to RomBlob.FR_SEVII45_MAP, "sevii67" to RomBlob.FR_SEVII67_MAP).forEach { (name, map) ->
                if (has(map)) out["regionmap/$name.png"] = screen(tiles, pal, d(map), 30)
            }
        }
        // Dreamstone Mysteries' Cormoria TOWN MAP: FireRed's kind of screen (4bpp tiles, a 32-wide u16 tilemap, BG palette 0).
        if (has(RomBlob.DSM_REGION_GFX, RomBlob.DSM_REGION_PAL, RomBlob.DSM_REGION_MAP)) {
            out["regionmap/dreamstone.png"] = screen(d(RomBlob.DSM_REGION_GFX), palette(d(RomBlob.DSM_REGION_PAL)), d(RomBlob.DSM_REGION_MAP), 32)
        }
        // Monomyth's ATHANTO MAP (its PokeNav's): a mode-0 text screen of its own.
        if (has(RomBlob.MMY_REGION_GFX, RomBlob.MMY_REGION_PAL, RomBlob.MMY_REGION_MAP)) {
            out["regionmap/monomyth.png"] = monomythRegionMap(d(RomBlob.MMY_REGION_GFX), palette(d(RomBlob.MMY_REGION_PAL)), d(RomBlob.MMY_REGION_MAP))
        }
        // Emerald's region map, and Seaglass's / Lazarus's / SoulGold's / Glazed's / Imperium's own (same region_map.c, their own art).
        listOf(
            "hoenn" to Triple(RomBlob.EM_REGION_GFX, RomBlob.EM_REGION_PAL, RomBlob.EM_REGION_MAP),
            // Seaglass redrew the tiles, keeping Emerald's tilemap and palette.
            "seaglass" to Triple(RomBlob.SGL_REGION_GFX, RomBlob.EM_REGION_PAL, RomBlob.EM_REGION_MAP),
            "lazarus" to Triple(RomBlob.LZ_REGION_GFX, RomBlob.LZ_REGION_PAL, RomBlob.LZ_REGION_MAP),
            "soulgold" to Triple(RomBlob.SG_REGION_GFX, RomBlob.SG_REGION_PAL, RomBlob.SG_REGION_MAP),
            // Glazed's Tunod and Johto (a 64x32 tilemap: only its first 20 rows show anyway).
            "glazed" to Triple(RomBlob.GZ_REGION_GFX, RomBlob.GZ_REGION_PAL, RomBlob.GZ_REGION_MAP),
            // R.O.W.E.'s three maps (Emerald's code, picked by the map's region), one palette.
            "rowe_hoenn" to Triple(RomBlob.RW_REGION_GFX, RomBlob.RW_REGION_PAL, RomBlob.RW_REGION_MAP),
            "rowe_kanto" to Triple(RomBlob.RW_KANTO_GFX, RomBlob.RW_REGION_PAL, RomBlob.RW_KANTO_MAP),
            "rowe_sevii" to Triple(RomBlob.RW_SEVII_GFX, RomBlob.RW_REGION_PAL, RomBlob.RW_SEVII_MAP),
            // Heart and Soul's Johto + Kanto map (smol, like SoulGold's), on Emerald's palette.
            "hns" to Triple(RomBlob.HNS_REGION_GFX, RomBlob.EM_REGION_PAL, RomBlob.HNS_REGION_MAP),
            // Recharged Emerald redrew Hoenn (its own tiles, tilemap and palette) on Emerald's layout.
            "recharged_emerald" to Triple(RomBlob.RE_REGION_GFX, RomBlob.RE_REGION_PAL, RomBlob.RE_REGION_MAP),
            // Imperium added markers for its new places, on Emerald's palette.
            "imperium" to Triple(RomBlob.IMP_REGION_GFX, RomBlob.EM_REGION_PAL, RomBlob.IMP_REGION_MAP),
            // Super Mariomon's world map: its own tiles, tilemap and palette.
            "mariomon" to Triple(RomBlob.MM_REGION_GFX, RomBlob.MM_REGION_PAL, RomBlob.MM_REGION_MAP),
            // Wishes of Tomorrow's JOKAIM map: its own tiles and tilemap (smol) and a 48-colour palette.
            "wishes" to Triple(RomBlob.WSH_REGION_GFX, RomBlob.WSH_REGION_PAL, RomBlob.WSH_REGION_MAP),
            // Quetzal: FireRed's Kanto and Sevii maps, redrawn for this code (MapSecDataQuetzal's note).
            "quetzal_kanto" to Triple(RomBlob.QTZ_KANTO_GFX, RomBlob.QTZ_REGION_PAL, RomBlob.QTZ_KANTO_MAP),
            "quetzal_sevii123" to Triple(RomBlob.QTZ_SEVII123_GFX, RomBlob.QTZ_REGION_PAL, RomBlob.QTZ_SEVII123_MAP),
            "quetzal_sevii45" to Triple(RomBlob.QTZ_SEVII45_GFX, RomBlob.QTZ_REGION_PAL, RomBlob.QTZ_SEVII45_MAP),
            "quetzal_sevii67" to Triple(RomBlob.QTZ_SEVII67_GFX, RomBlob.QTZ_REGION_PAL, RomBlob.QTZ_SEVII67_MAP),
            // Its Johto, on Emerald's palette (its Hoenn is Emerald's map, byte for byte: "emerald" below).
            "quetzal_johto" to Triple(RomBlob.QTZ_JOHTO_GFX, RomBlob.EM_REGION_PAL, RomBlob.QTZ_JOHTO_MAP),
            // Team Rocket Edition 2026's Kanto, Hoenn and Sinnoh (smol, Wishes' kind of screen).
            "tr3_kanto" to Triple(RomBlob.TR3_KANTO_GFX, RomBlob.TR3_KANTO_PAL, RomBlob.TR3_KANTO_MAP),
            "tr3_hoenn" to Triple(RomBlob.TR3_HOENN_GFX, RomBlob.TR3_HOENN_PAL, RomBlob.TR3_HOENN_MAP),
            "tr3_sinnoh" to Triple(RomBlob.TR3_SINNOH_GFX, RomBlob.TR3_SINNOH_PAL, RomBlob.TR3_SINNOH_MAP),
        ).forEach { (name, blobs) ->
            if (has(blobs.first, blobs.second, blobs.third)) {
                out["regionmap/$name.png"] = emeraldRegionMap(d(blobs.first), palette(d(blobs.second)), d(blobs.third))
            }
        }
        // Its Johto and Sevii maps: the same screen, their palette loaded at BG palette 0 (colours 0-15 of their tiles).
        listOf(
            "tr3_johto" to Triple(RomBlob.TR3_JOHTO_GFX, RomBlob.TR3_JOHTO_PAL, RomBlob.TR3_JOHTO_MAP),
            "tr3_sevii123" to Triple(RomBlob.TR3_SEVII123_GFX, RomBlob.TR3_SEVII123_PAL, RomBlob.TR3_SEVII123_MAP),
            "tr3_sevii45" to Triple(RomBlob.TR3_SEVII45_GFX, RomBlob.TR3_SEVII45_PAL, RomBlob.TR3_SEVII45_MAP),
            "tr3_sevii67" to Triple(RomBlob.TR3_SEVII67_GFX, RomBlob.TR3_SEVII67_PAL, RomBlob.TR3_SEVII67_MAP),
        ).forEach { (name, blobs) ->
            if (has(blobs.first, blobs.second, blobs.third)) {
                out["regionmap/$name.png"] = emeraldRegionMap(d(blobs.first), palette(d(blobs.second)), d(blobs.third), firstColour = 0)
            }
        }
        for ((name, blobs) in PLAYER_ICONS) {
            if (has(blobs.first, blobs.second)) out["regionmap/$name.png"] = sprite(d(blobs.first), palette(d(blobs.second)), 16, 16)
        }
        // SoulGold's bag backdrop: its night sky, the whole 32x32-tile layer (the game scrolls it; this is one still frame).
        if (has(RomBlob.SG_BAG_STARS_GFX, RomBlob.SG_BAG_STARS_MAP, RomBlob.SG_BAG_STARS_PAL)) {
            out[BAG_STARS_SOULGOLD] = screen(
                d(RomBlob.SG_BAG_STARS_GFX), palette(d(RomBlob.SG_BAG_STARS_PAL)), d(RomBlob.SG_BAG_STARS_MAP), 32, tilesW = 32, tilesH = 32,
            )
        }
        return out
    }

    /**
     * Emerald's region_map.c screen: 8bpp tiles through a 64-wide one-byte
     * affine map; the palette is loaded at BG palette 7, so colour index 112
     * is its first entry. A cell past the tiles the ROM has (Super Mariomon's
     * map names 21 of them in its bottom-right corner - whatever VRAM holds
     * there, under the game's own text box) stays transparent. [firstColour]: where the game loads the palette (Team
     * Rocket Edition 2026's Johto and Sevii maps load theirs at BG palette 0).
     */
    private fun emeraldRegionMap(tiles: ByteArray, pal: IntArray, map: ByteArray, firstColour: Int = 112): Image {
        val img = Image(240, 160)
        for (ty in 0 until 20) for (tx in 0 until 30) {
            val t = map[ty * 64 + tx].toInt() and 0xFF
            if ((t + 1) * 64 > tiles.size) continue
            for (y in 0 until 8) for (x in 0 until 8) {
                val v = (tiles[t * 64 + y * 8 + x].toInt() and 0xFF) - firstColour
                img.argb[(ty * 8 + y) * 240 + tx * 8 + x] = pal.getOrElse(v) { pal[0] }
            }
        }
        return img
    }

    /**
     * Monomyth's ATHANTO MAP: 8bpp tiles loaded from tile 0x60 through a 32x32 u16 text tilemap (flips allowed), shown
     * from row 2 (BG2's vertical scroll of 16), its 48-colour palette at BG palette 7 like Emerald's. Pixels outside that
     * palette (the blank tile's, under the game's CANCEL bar) stay transparent.
     */
    private fun monomythRegionMap(tiles: ByteArray, pal: IntArray, map: ByteArray): Image {
        val img = Image(240, 160)
        for (ty in 0 until 20) for (tx in 0 until 30) {
            val o = ((ty + 2) * 32 + tx) * 2
            val e = (map[o].toInt() and 0xFF) or ((map[o + 1].toInt() and 0xFF) shl 8)
            val t = (e and 0x3FF) - 0x60
            if (t < 0 || (t + 1) * 64 > tiles.size) continue
            for (y in 0 until 8) for (x in 0 until 8) {
                val sx = if (e and 0x400 != 0) 7 - x else x
                val sy = if (e and 0x800 != 0) 7 - y else y
                val v = (tiles[t * 64 + sy * 8 + sx].toInt() and 0xFF) - 112
                if (v in pal.indices) img.argb[(ty * 8 + y) * 240 + tx * 8 + x] = pal[v]
            }
        }
        return img
    }

    /**
     * The CFRU hacks' party menu (scripts/gen_cfru_party_assets.py has the layout): seven
     * 112x40 slot frames (an empty slot too), one clean 32x32 cell of the grid backdrop and
     * each hack's status icons (FireRed's palette; 7 icons, PSN..FNT).
     */
    private fun cfru(found: Map<RomBlob, ByteArray>, out: MutableMap<String, Image>) {
        // CFRU's own palette, and Sword and Shield Ultimate Plus' over the same tiles and tilemaps.
        cfruMenu(found, found[RomBlob.CFRU_PARTY_BG_PAL], "partycfru", CFRU_BACKDROP, out)
        cfruMenu(found, found[RomBlob.SS_PARTY_BG_PAL], SWSH_PARTY_DIR, SWSH_BACKDROP, out)
        // Unbound FR: its own tiles under CFRU's palette and tilemaps (the backdrop is the same: no cell of its own).
        cfruMenu(found, found[RomBlob.CFRU_PARTY_BG_PAL], UNBOUND_FR_PARTY_DIR, null, out, gfxBlob = RomBlob.UBFR_PARTY_BG_GFX)
        // Let's Go: CFRU's slot tilemaps over its own tiles, palette and backdrop tilemap.
        cfruMenu(found, found[RomBlob.LGO_PARTY_BG_PAL], LETS_GO_PARTY_DIR, LETS_GO_BACKDROP, out,
            gfxBlob = RomBlob.LGO_PARTY_BG_GFX, mapBlob = RomBlob.LGO_PARTY_BG_MAP)
        found[RomBlob.UBFR_FONT_SMALL]?.let { out["$UNBOUND_FR_PARTY_DIR/font_small.png"] = font(it, halfWidth = true) }
        val statusPal = found[RomBlob.FR_STATUS_PAL]?.let { palette(it) } ?: return
        for ((dir, blob) in CFRU_STATUS) {
            found[blob]?.let { out["partycfru/$dir/status_icons.png"] = sprite(it, statusPal, 7 * 32, 8) { tx, _ -> tx } }
        }
        found[RomBlob.UBFR_STATUS_GFX]?.let { out["$UNBOUND_FR_PARTY_DIR/status_icons.png"] = sprite(it, statusPal, 7 * 32, 8) { tx, _ -> tx } }
        found[RomBlob.LGO_STATUS_GFX]?.let { out["$LETS_GO_PARTY_DIR/status_icons.png"] = sprite(it, statusPal, 7 * 32, 8) { tx, _ -> tx } }
    }

    /** The CFRU party menu's slot frames into [dir] and its backdrop cell as [backdrop], in [palBytes]. */
    private fun cfruMenu(
        found: Map<RomBlob, ByteArray>, palBytes: ByteArray?, dir: String, backdrop: String?, out: MutableMap<String, Image>,
        gfxBlob: RomBlob = RomBlob.CFRU_PARTY_BG_GFX, mapBlob: RomBlob = RomBlob.CFRU_PARTY_BG_MAP,
    ) {
        val gfx = found[gfxBlob] ?: return
        val pal = palBytes?.let { palette(it) } ?: return
        val main = found[RomBlob.CFRU_SLOT_MAIN]
        val noHp = found[RomBlob.CFRU_SLOT_NO_HP]
        val empty = found[RomBlob.CFRU_SLOT_EMPTY]
        if (main != null && noHp != null && empty != null) {
            slots(gfx, pal, main, noHp, empty, tilesW = 14, tilesH = 5).forEach { (k, v) -> out["$dir/$k"] = v }
        }
        if (backdrop != null) found[mapBlob]?.let { map ->
            // The cell at (32, 32): clear of the zig-zags between the slot columns, in the
            // screen's own phase, so tiling it from (0, 0) redraws the grid.
            val full = screen(gfx, pal, map, 32, tilesW = 32, tilesH = 32)
            out[backdrop] = Image(32, 32, IntArray(32 * 32) { full.argb[(32 + it / 32) * full.width + 32 + it % 32] })
        }
    }

    /**
     * The MAIN slot's frame per state ([tilesW]x[tilesH] tiles: FireRed's / Emerald's 80x56),
     * from the slot tilemaps (the no-HP one is what an EGG uses; [empty], a slot without a mon).
     */
    private fun slots(
        tiles: ByteArray, pal: IntArray, main: ByteArray, noHp: ByteArray, empty: ByteArray? = null, tilesW: Int = 10, tilesH: Int = 7,
    ): Map<String, Image> {
        val states = SLOT_STATES + listOf("nohp_normal" to SLOT_STATES[0].second, "nohp_selected" to SLOT_STATES[1].second) +
            // PARTY_PAL_NO_MON falls through to the default ids.
            listOfNotNull(empty?.let { "empty" to SLOT_STATES[0].second })
        return states.associate { (name, ids) ->
            val cols = pal.copyOfRange(48, 64)
            intArrayOf(4, 5, 6).forEachIndexed { k, idx -> cols[idx] = pal[ids.first[k]] }
            intArrayOf(1, 7, 8).forEachIndexed { k, idx -> cols[idx] = pal[ids.second[k]] }
            val map = when {
                name == "empty" -> empty!!
                name.startsWith("nohp") -> noHp
                else -> main
            }
            "slot_$name.png" to sprite(tiles, cols, tilesW * 8, tilesH * 8) { tx, ty -> map[ty * tilesW + tx].toInt() and 0xFF }
        }
    }

    /**
     * FONT_SMALL as 8x16 cells, 32 per row, by glyph id: 2bpp rows of 8
     * pixels (a u16, first pixel in the top bits); text (1) red, shadow (2)
     * blue, for the party slot's runtime tinting. FireRed stores each glyph
     * as two 8x8 tiles, Emerald as four (16x16) of which the left two count.
     */
    private fun font(f: ByteArray, halfWidth: Boolean): Image {
        val img = Image(256, 256)
        val glyphBytes = if (halfWidth) 32 else 64
        val bottomTile = if (halfWidth) 16 else 32
        for (g in 0 until minOf(512, f.size / glyphBytes)) {
            val dx = (g % 32) * 8
            val dy = (g / 32) * 16
            for (half in 0 until 2) for (y in 0 until 8) {
                val base = g * glyphBytes + half * bottomTile + y * 2
                val row = (f[base].toInt() and 0xFF) or ((f[base + 1].toInt() and 0xFF) shl 8)
                for (x in 0 until 8) {
                    val c = when ((row shr (14 - 2 * x)) and 3) {
                        1 -> 0xFFFF0000.toInt()
                        2 -> 0xFF0000FF.toInt()
                        else -> continue
                    }
                    img.argb[(dy + half * 8 + y) * 256 + dx + x] = c
                }
            }
        }
        return img
    }

    /** Just enough PNG (8-bit RGBA, no filtering) to cache [Image]s without android.graphics. */
    private object Png {
        fun encode(img: Image): ByteArray {
            val raw = ByteArray(img.height * (1 + img.width * 4))
            var o = 0
            for (y in 0 until img.height) {
                raw[o++] = 0
                for (x in 0 until img.width) {
                    val c = img.argb[y * img.width + x]
                    raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte(); raw[o++] = (c ushr 24).toByte()
                }
            }
            // Fed 16 KiB at a time: each deflate() holds a JNI critical lock
            // (stalling GC while the game runs) for as long as its input lasts.
            val z = ByteArrayOutputStream()
            val def = Deflater()
            val chunk = ByteArray(8192)
            var at = 0
            while (!def.finished()) {
                if (def.needsInput()) {
                    if (at < raw.size) {
                        val n = minOf(16 shl 10, raw.size - at)
                        def.setInput(raw, at, n)
                        at += n
                    } else {
                        def.finish()
                    }
                }
                z.write(chunk, 0, def.deflate(chunk))
            }
            def.end()
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            // 8-bit depth, colour type 6 (RGBA), deflate, no filter, no interlace.
            val ihdr = ByteBuffer.allocate(13).putInt(img.width).putInt(img.height).put(byteArrayOf(8, 6, 0, 0, 0)).array()
            chunk(out, "IHDR", ihdr)
            chunk(out, "IDAT", z.toByteArray())
            chunk(out, "IEND", ByteArray(0))
            return out.toByteArray()
        }

        private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            val t = type.toByteArray(Charsets.US_ASCII)
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            out.write(t); out.write(data)
            val crc = CRC32().apply { update(t); update(data) }.value
            out.write(ByteBuffer.allocate(4).putInt(crc.toInt()).array())
        }
    }
}
