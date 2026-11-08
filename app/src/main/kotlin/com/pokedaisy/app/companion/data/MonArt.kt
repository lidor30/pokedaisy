package com.pokedaisy.app.companion.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PLAYER LOOK's Pokémon and the FOLLOWER: each species' overworld sprite (a following Pokémon's: 32x32, two frames each
 * facing south, north and west) and its battle pictures, from the player's own ROM of a hack with following Pokémon
 * ([Source]: Heart and Soul, R.O.W.E., Recharged Yellow, Emerald Seaglass), kept decoded in `rom-art/look/mon/<source>/<national dex
 * number>.bin` like [LookArt]; nothing bundled. Only the first 386 (the ones any Gen 3 game has). A 64x64 sprite whose
 * drawing fits the bottom middle 32x32 of its frames (most of the big legends') is kept cropped to that.
 *
 * With more than one of those ROMs, each Pokémon comes from one source only: the first of [Source]'s order that has it.
 */
object MonArt {
    /** One species, everything [PlayerLook.buildMon] puts in, and where it came from. */
    class Mon(
        val national: Int, val frames: ByteArray, val owPal: ByteArray, val front: ByteArray, val back: ByteArray, val pal: ByteArray,
        val source: Source = Source.HEART_AND_SOUL,
    )

    /** The hacks whose following Pokémon are kept, in the order one is picked when several have a species; [tag] is the
     * short name the picker shows under a tile (and the folder). */
    enum class Source(val tag: String) {
        HEART_AND_SOUL("HNS"), ROWE("ROWE"), RECHARGED_YELLOW("RY"), SEAGLASS("SGL"),
    }

    const val LAST = 386
    private const val FRAMES = 6 * 512
    private const val PIC = 0x800
    private const val SIZE = FRAMES + 32 + PIC * 2 + 32

    /** A PLAYER LOOK pick that's a Pokémon ("MON:<national number>"), or null. */
    fun monOf(pick: String?): Int? = pick?.takeIf { it.startsWith("MON:") }?.removePrefix("MON:")?.toIntOrNull()

    fun dir(filesDir: File?): File = File(LookArt.dir(filesDir), "mon")
    private fun dir(filesDir: File?, source: Source) = File(dir(filesDir), source.tag)
    private fun file(filesDir: File?, source: Source, national: Int) = File(dir(filesDir, source), "$national.bin")

    /** Before the other sources, Heart and Soul's were kept straight in `mon/`: into its own folder. */
    private fun migrate(filesDir: File?) {
        val old = dir(filesDir).listFiles { f -> f.isFile && f.name.endsWith(".bin") }.orEmpty()
        if (old.isEmpty()) return
        val to = dir(filesDir, Source.HEART_AND_SOUL).apply { mkdirs() }
        old.forEach { f -> if (!f.renameTo(File(to, f.name))) f.delete() }
    }

    /** One species' pieces as a source reads them: [frames] six frames of 32x32 or 64x64 (S, S', N, N', W, W'). */
    private class Raw(val frames: ByteArray, val width: Int, val owPal: ByteArray, val front: ByteArray, val back: ByteArray, val pal: ByteArray)

    /** A ROM's reader for one source: [mon] gives a species' pieces, null where it has none. */
    private class Reader(val source: Source, val mon: (Int) -> Raw?)

    private class Rom(val b: ByteArray) {
        val buf: ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        fun ok(o: Int, n: Int = 4) = o >= 0 && o + n <= b.size
        fun u16(o: Int) = if (ok(o, 2)) buf.getShort(o).toInt() and 0xFFFF else -1
        fun s16(o: Int) = if (ok(o, 2)) buf.getShort(o).toInt() else -1
        fun u32(o: Int) = if (ok(o)) buf.getInt(o) else 0
        fun ptr(o: Int): Int? = (u32(o) - 0x08000000).takeIf { it in 0 until b.size }
        fun lz(at: Int?, size: Int): ByteArray? = at?.let { runCatching { RomArt.lz77(b, it, size) }.getOrNull() }
        fun smol(at: Int?, size: Int): ByteArray? = at?.let {
            runCatching { Smol.decompressAny(b.copyOfRange(it, minOf(b.size, it + 0x10000))) }.getOrNull()?.takeIf { d -> d.size >= size }?.copyOf(size)
        }
        /** A 32-byte palette: LZ77 when its header says 32 bytes (a raw one can start with 0x10 too: TOGETIC's), else raw. */
        fun pal(at: Int?): ByteArray? = at?.takeIf { ok(it, 32) }?.let {
            if (b[it] == 0x10.toByte() && b[it + 1] == 0x20.toByte() && b[it + 2] == 0.toByte() && b[it + 3] == 0.toByte()) lz(it, 32)
            else b.copyOfRange(it, it + 32)
        }
        /** A front / back picture: its first 64x64 frame (front pictures carry two). */
        fun pic(at: Int?): ByteArray? = lz(at, -1)?.takeIf { it.size >= PIC }?.copyOf(PIC)
    }

    /**
     * Heart and Soul v2.0.6 (pokeemerald-expansion): gSpeciesInfo (species ids are national numbers, stride 0x10C), each
     * entry's overworldData (an ObjectEventGraphicsInfo, +0xB0; images +0x1C: one smol entry of every frame), its
     * overworldPalette (+0xF8, raw), frontPic / backPic (+0x58 / +0x5C, smol) and palette (+0x60, raw or LZ77). Found
     * by PIKACHU's overworld record checking out.
     */
    private fun heartAndSoul(r: Rom): Reader? {
        val base = 0x7E6544; val stride = 0x10C; val ow = 0xB0
        val pika = base + 25 * stride + ow
        if (!(r.u16(pika) == 0xFFFF && r.u16(pika + 6) == 512 && r.u16(pika + 8) == 32 && r.u16(pika + 10) == 32)) return null
        return Reader(Source.HEART_AND_SOUL) { n ->
            val rec = base + n * stride
            val w = r.u16(rec + ow + 8)
            if (w != r.u16(rec + ow + 10) || (w != 32 && w != 64)) return@Reader null
            val frames = r.smol(r.ptr(r.ptr(rec + ow + 0x1C) ?: return@Reader null), 6 * w * w / 2) ?: return@Reader null
            val owPal = r.ptr(rec + 0xF8)?.takeIf { r.ok(it, 32) }?.let { r.b.copyOfRange(it, it + 32) } ?: return@Reader null
            val front = r.smol(r.ptr(rec + 0x58), PIC) ?: return@Reader null
            val back = r.smol(r.ptr(rec + 0x5C), PIC) ?: return@Reader null
            Raw(frames, w, owPal, front, back, r.pal(r.ptr(rec + 0x60)) ?: return@Reader null)
        }
    }

    /**
     * Emerald Seaglass v3.0 (pokeemerald-expansion): gSpeciesInfo at 0x8F0780 (stride 0xD0, national ids), overworldData
     * inline at +0xA4 whose one image is an LZ77 stream of all six frames (its size field says 0x200 even for 64x64:
     * frames are sized by width x height), overworld palette +0xC8 (LZ77), frontPic +0x58 / backPic +0x60 / palette +0x68
     * (LZ77). Checked on PIKACHU's record and its sheet's header; its sprites all match the game's VRAM.
     */
    private fun seaglass(r: Rom): Reader? {
        val base = 0x8F0780; val stride = 0xD0; val ow = 0xA4
        val pika = base + 25 * stride + ow
        if (!(r.u16(pika) == 0xFFFF && r.u16(pika + 6) == 512 && r.u16(pika + 8) == 32 && r.u16(pika + 10) == 32)) return null
        val sheet = r.ptr(r.ptr(pika + 0x1C) ?: return null) ?: return null
        if (r.b[sheet] != 0x10.toByte() || (r.u32(sheet) ushr 8) != FRAMES) return null
        return Reader(Source.SEAGLASS) { n ->
            val rec = base + n * stride
            val w = r.u16(rec + ow + 8)
            if (r.u16(rec + ow) != 0xFFFF || w != r.u16(rec + ow + 10) || (w != 32 && w != 64)) return@Reader null
            val frames = r.lz(r.ptr(r.ptr(rec + ow + 0x1C) ?: return@Reader null), 6 * w * w / 2) ?: return@Reader null
            Raw(
                frames, w, r.pal(r.ptr(rec + 0xC8)) ?: return@Reader null,
                r.pic(r.ptr(rec + 0x58)) ?: return@Reader null, r.pic(r.ptr(rec + 0x60)) ?: return@Reader null,
                r.pal(r.ptr(rec + 0x68)) ?: return@Reader null,
            )
        }
    }

    /**
     * R.O.W.E. v2.x (its own pokeemerald fork): the followers are graphics ids 600 + species in
     * gObjectEventGraphicsInfoPointers - a table of records whose paletteTag is 0x1800 + species (found by that, no
     * symbol names it), each with nine raw frame images (3..8 = S, S', N, N', W, W'; 0..2 copies), its palette by tag in
     * sObjectEventSpritePalettes ({data, tag}, NPCs' 0x1103 first, ending at 0x11FF). Battle pictures from the tables
     * its ROM's own symbol table names (gMonFrontPicTable & co., LZ77). Species ids are national numbers.
     */
    private fun rowe(r: Rom): Reader? {
        // Its symbol table: {name*, address} pairs at 0x08FB0670.
        val syms = HashMap<String, Int>()
        val symAt = 0xFB0670
        for (i in 0 until 600) {
            val name = r.ptr(symAt + i * 8) ?: break
            val end = (name until minOf(r.b.size, name + 64)).firstOrNull { r.b[it] == 0.toByte() } ?: break
            syms[String(r.b, name, end - name, Charsets.US_ASCII)] = r.u32(symAt + i * 8 + 4) - 0x08000000
        }
        val fronts = syms["gMonFrontPicTable"] ?: return null
        val backs = syms["gMonBackPicTable"] ?: return null
        val pals = syms["gMonPaletteTable"] ?: return null
        fun recOk(t: Int, s: Int) = r.ptr(t + 4 * s)?.let { p -> r.ok(p, 0x24) && r.u16(p) == 0xFFFF && r.u16(p + 2) == 0x1800 + s } == true
        var table = -1
        var t = 0
        while (t + 4 * 1026 <= r.b.size) {
            if ((r.u32(t + 4) ushr 25) == 4 && listOf(1, 2, 25, 151, 252, 386).all { recOk(t, it) }) { table = t; break }
            t += 4
        }
        if (table < 0) return null
        var list = -1
        t = 0
        while (t + 16 <= r.b.size) {
            if (r.u16(t + 4) == 0x1103 && r.u16(t + 12) == 0x1104 && r.ptr(t) != null && r.ptr(t + 8) != null) { list = t; break }
            t += 4
        }
        if (list < 0) return null
        val palByTag = HashMap<Int, Int>()
        var o = list
        while (r.ok(o, 8) && r.u16(o + 4) != 0x11FF) { palByTag[r.u16(o + 4)] = r.ptr(o) ?: break; o += 8 }
        return Reader(Source.ROWE) { n ->
            val rec = r.ptr(table + 4 * n)?.takeIf { recOk(table, n) } ?: return@Reader null
            val w = r.u16(rec + 8)
            if (w != r.u16(rec + 10) || (w != 32 && w != 64)) return@Reader null
            val size = w * w / 2
            val images = r.ptr(rec + 0x1C) ?: return@Reader null
            val frames = ByteArray(6 * size)
            for (i in 0 until 6) {
                val at = r.ptr(images + 8 * (3 + i))?.takeIf { r.u16(images + 8 * (3 + i) + 4) == size && r.ok(it, size) } ?: return@Reader null
                System.arraycopy(r.b, at, frames, i * size, size)
            }
            val owPal = palByTag[r.u16(rec + 2)]?.takeIf { r.ok(it, 32) }?.let { r.b.copyOfRange(it, it + 32) } ?: return@Reader null
            Raw(
                frames, w, owPal, r.pic(r.ptr(fronts + 8 * n)) ?: return@Reader null, r.pic(r.ptr(backs + 8 * n)) ?: return@Reader null,
                r.pal(r.ptr(pals + 8 * n)) ?: return@Reader null,
            )
        }
    }

    /**
     * Recharged Yellow (pokefirered rebuilt with gcc; every release): a flat array of ObjectEventGraphicsInfo records
     * (0x24 bytes) indexed by FireRed's species ids, its base loaded by the function that picks a follower's record (its
     * first instructions below, then the first `ldr rX, [pc, #]`) and checked by shape (Unown's old slots 252-276 empty).
     * Each record's six frames are one LZ77 stream (S, S', N, N', W, W'); the overworld palette is the battle one
     * (gMonPaletteTable, the GF header's +0x30), pictures from gMonFrontPicTable / gMonBackPicTable (+0x28 / +0x2C), all
     * LZ77. FireRed's ids go to national numbers through sSpeciesToNationalPokedexNum, found by its contents.
     */
    private fun rechargedYellow(r: Rom): Reader? {
        val sig = byteArrayOf(0x03, 0x04, 0x09, 0x06, 0x30, 0xB5.toByte(), 0x1B, 0x0C, 0x08, 0x0E, 0xC9.toByte(), 0x2B)
        val at = indexOf(r.b, sig, 0).takeIf { it >= 0 && indexOf(r.b, sig, it + 2) < 0 } ?: return null
        val ldr = (at until at + 0x30 step 2).firstOrNull { (r.u16(it) shr 11) == 0b01001 } ?: return null
        val base = r.ptr(((ldr + 4) and 3.inv()) + (r.u16(ldr) and 0xFF) * 4) ?: return null
        val stride = 0x24
        fun recOk(s: Int) = base + (s + 1) * stride <= r.b.size && r.u16(base + s * stride) == 0xFFFF &&
            r.u16(base + s * stride + 6) == 512 && r.u16(base + s * stride + 8) == 32 && r.u16(base + s * stride + 10) == 32
        if (!listOf(0, 1, 25, 151, 251, 277, 411).all(::recOk)) return null
        if ((252 * stride until 277 * stride).any { r.b[base + it] != 0.toByte() }) return null
        // sSpeciesToNationalPokedexNum: species 1-251 are 1-251, then the old Unown slots 387-411, then Hoenn.
        val firsts = ByteArray(251 * 2).also { a -> for (i in 0 until 251) { a[i * 2] = (i + 1).toByte(); a[i * 2 + 1] = ((i + 1) shr 8).toByte() } }
        var s2n = -1
        var o = indexOf(r.b, firsts, 0)
        while (o >= 0) {
            if ((251 until 276).all { r.u16(o + it * 2) == 387 + it - 251 } && (276 until 411).map { r.u16(o + it * 2) }.sorted() == (252..386).toList()) { s2n = o; break }
            o = indexOf(r.b, firsts, o + 2)
        }
        if (s2n < 0) return null
        val speciesOf = HashMap<Int, Int>()
        for (sp in 1..411) speciesOf.putIfAbsent(r.u16(s2n + (sp - 1) * 2), sp)
        val fronts = r.ptr(0x128) ?: return null
        val backs = r.ptr(0x12C) ?: return null
        val pals = r.ptr(0x130) ?: return null
        return Reader(Source.RECHARGED_YELLOW) { n ->
            val sp = speciesOf[n] ?: return@Reader null
            if (!recOk(sp)) return@Reader null
            val images = r.ptr(base + sp * stride + 0x1C) ?: return@Reader null
            val frames = r.lz(r.ptr(images), FRAMES) ?: return@Reader null
            val pal = r.pal(r.ptr(pals + 8 * sp)) ?: return@Reader null
            Raw(frames, 32, pal, r.pic(r.ptr(fronts + 8 * sp)) ?: return@Reader null, r.pic(r.ptr(backs + 8 * sp)) ?: return@Reader null, pal)
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from
        val last = hay.size - needle.size
        outer@ while (i <= last) {
            if (hay[i] != needle[0]) { i++; continue }
            for (k in 1 until needle.size) if (hay[i + k] != needle[k]) { i++; continue@outer }
            return i
        }
        return -1
    }

    private fun readerOf(rom: ByteArray): Reader? {
        val r = Rom(rom)
        return heartAndSoul(r) ?: seaglass(r) ?: rechargedYellow(r) ?: rowe(r)
    }

    /**
     * Six 64x64 frames as 32x32 ones: tile columns 2-5 and rows 4-7 (the bottom middle, where a follower's feet are) -
     * only when every other tile of every frame is empty, so nothing of the drawing is lost. Null otherwise.
     */
    private fun crop64(frames: ByteArray): ByteArray? {
        val out = ByteArray(FRAMES)
        for (f in 0 until 6) for (ty in 0 until 8) for (tx in 0 until 8) {
            val src = f * 2048 + (ty * 8 + tx) * 32
            if (tx in 2..5 && ty in 4..7) System.arraycopy(frames, src, out, f * 512 + ((ty - 4) * 4 + tx - 2) * 32, 32)
            else if ((0 until 32).any { frames[src + it] != 0.toByte() }) return null
        }
        return out
    }

    /** Whether [rom] is one of the [Source]s' ROMs. */
    fun find(rom: ByteArray): Boolean = readerOf(rom) != null

    /** Every species of [rom] (one of the [Source]s') not kept yet; how many were kept. */
    fun keep(filesDir: File?, rom: ByteArray): Int {
        migrate(filesDir)
        val reader = readerOf(rom) ?: return 0
        val dir = dir(filesDir, reader.source).apply { mkdirs() }
        var kept = 0
        for (n in 1..LAST) {
            val f = File(dir, "$n.bin")
            if (f.isFile) continue
            val raw = runCatching { reader.mon(n) }.getOrNull() ?: continue
            val frames = if (raw.width == 32) raw.frames.copyOf(FRAMES) else crop64(raw.frames) ?: continue
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(frames + raw.owPal + raw.front + raw.back + raw.pal)
            tmp.renameTo(f)
            kept++
        }
        if (kept > 0) availableCache = null
        return kept
    }

    /** [national]'s art from the first [Source] that has it. */
    fun load(filesDir: File?, national: Int): Mon? {
        migrate(filesDir)
        for (source in Source.entries) {
            val d = file(filesDir, source, national).takeIf { it.isFile }?.readBytes() ?: continue
            if (d.size != SIZE) continue
            var o = 0
            fun take(n: Int) = d.copyOfRange(o, o + n).also { o += n }
            return Mon(national, take(FRAMES), take(32), take(PIC), take(PIC), take(32), source)
        }
        return null
    }

    /** The national numbers kept, from any source. */
    fun available(filesDir: File?): Set<Int> {
        availableCache?.takeIf { it.first == filesDir?.path }?.let { return it.second }
        migrate(filesDir)
        val found = Source.entries.flatMap { s -> dir(filesDir, s).list()?.mapNotNull { it.removeSuffix(".bin").toIntOrNull() }.orEmpty() }.toSet()
        availableCache = filesDir?.path to found
        return found
    }

    /** [available]'s last answer: SETTINGS asks it while it composes (FOLLOWER's row), and listing the folders there held a
     * weak tablet's tab switch up. Forgotten whenever a scan keeps something new. */
    @Volatile private var availableCache: Pair<String?, Set<Int>>? = null

    /** The sources with something kept. */
    fun sources(filesDir: File?): Set<Source> = Source.entries.filter { dir(filesDir, it).list()?.any { n -> n.endsWith(".bin") } == true }.toSet()

    /**
     * [back] (a 64x64 back picture) moved to stand where the battle draws a Pokémon's back. The player's back picture is
     * drawn centred at y 80 (`(8 - gTrainerBackPicCoords[].size) * 4 + 80`, size 8 for every player), the player's
     * Pokémon at 80 (sBattlerCoords) plus its gMonBackPicCoords y_offset - which is the empty rows under its drawing (382
     * of Emerald's 384). So the drawing goes down by those rows: its bottom on the picture's last row.
     */
    fun asTrainerBack(back: ByteArray): ByteArray {
        fun px(x: Int, y: Int): Int {
            val b = back[((y / 8) * 8 + x / 8) * 32 + (y % 8) * 4 + (x % 8) / 2].toInt()
            return if (x % 2 == 0) b and 15 else (b shr 4) and 15
        }
        val rows = (0 until 64).filter { y -> (0 until 64).any { px(it, y) != 0 } }
        if (rows.isEmpty()) return back
        val shift = 63 - rows.last()
        if (shift == 0) return back
        val out = ByteArray(back.size)
        for (y in 0 until 64) for (x in 0 until 64) {
            val sy = y - shift
            val v = if (sy in 0 until 64) px(x, sy) else 0
            val i = ((y / 8) * 8 + x / 8) * 32 + (y % 8) * 4 + (x % 8) / 2
            out[i] = (if (x % 2 == 0) (out[i].toInt() and 0xF0) or v else (out[i].toInt() and 0x0F) or (v shl 4)).toByte()
        }
        return out
    }

    /** Its picker frames: standing and stepping south, cropped to the pixels either uses. */
    fun walk(filesDir: File?, national: Int): List<RomArt.Image>? {
        val m = load(filesDir, national) ?: return null
        // The picker's walk: stand, step, stand, step (its frames 0 / 1 / 2 as a hero's stand / step / step).
        val frames = listOf(0, 1, 1).map { RomArt.sprite(m.frames.copyOfRange(it * 512, it * 512 + 512), RomArt.palette(m.owPal), 32, 32) }
        fun used(x: Int, y: Int) = frames.any { it.argb[y * 32 + x] != 0 }
        val top = (0 until 32).firstOrNull { y -> (0 until 32).any { used(it, y) } } ?: return frames
        val bottom = (31 downTo 0).first { y -> (0 until 32).any { used(it, y) } }
        val left = (0 until 32).first { x -> (top..bottom).any { used(x, it) } }
        val right = (31 downTo 0).first { x -> (top..bottom).any { used(x, it) } }
        return frames.map { f ->
            RomArt.Image(right - left + 1, bottom - top + 1).also { c ->
                for (y in top..bottom) for (x in left..right) c.argb[(y - top) * c.width + x - left] = f.argb[y * 32 + x]
            }
        }
    }
}
