package com.pokedaisy.app.companion.data

/**
 * Where a ROM's m4a sound engine keeps its songs, so FfMusicRenderer can play
 * any one of them on its own on a second core (no sound effects, no cries):
 * [songNumStart] is `m4aSongNumStart(u16 n)`, [songTable] is `gSongTable`
 * (8-byte `{SongHeader*, u16 ms, u16 me}` entries).
 *
 * Found by code, not address: `m4aSongNumStart` is the same agbcc-compiled
 * library code in every decomp-built game and binary hack (FireRed, LeafGreen,
 * Ruby, Sapphire, Emerald, CFRU hacks, ...), and its literal
 * pool holds `gSongTable`. Emerald Rogue builds it with modern gcc - same
 * logic, other instruction order - which [SIGNATURES]' second entry matches
 * (literal offsets and the `bl` wildcarded). Newer pokeemerald-expansion
 * (Lazarus, SoulGold) only starts the song if the player's `ident` is
 * ID_NUMBER ("Smsh") - the third entry. Heart and Soul's adds an alternate
 * soundtrack: `m4aSongNumStart(n, alt)` plays song n's entry in a second table
 * (12-byte `{u32 n, SongHeader*, u16 ms, u16 me}`, n = -1 ends it) when `alt`
 * is set and n has one there - the fourth entry, which finds both tables. Its
 * songs get ids with [ALT] set ([number] / [alt] split them for the call).
 * Monomyth's is agbcc's with the table's song headers stored top bit flipped
 * (`eor` 0x80000000 before MPlayStart) - the fifth entry.
 */
class M4aSongs private constructor(
    val songNumStart: Long,
    val songTable: Long,
    /** A Thumb `b .` in the ROM (any even address holding 0xE7FE), to park the render core's main loop on. */
    val idleLoop: Long?,
    private val ids: Map<Long, Int>,
    /** Every song the table plays on the BGM player (`ms` 0), once per song, in table order. */
    val bgmSongIds: List<Int>,
) {

    /** The song id playing [key] (an [FfMusicKey]; [ALT] set for an alternate song), or null if it isn't in the tables. */
    fun songId(key: String): Int? = key.removePrefix("song_").toLongOrNull(16)?.let { ids[it] }

    companion object {
        /**
         * m4aSongNumStart's code ("." = any nibble) and where in it the
         * `ldr rX, [pc, #imm]` that loads gSongTable sits. agbcc: its first 32
         * bytes, up to the `bl MPlayStart` (relative, so it matches too), table
         * load at +6. gcc (Emerald Rogue): the whole function, table load at +2.
         * Newer expansion (gcc, with the ident check): the whole function, +2.
         * Heart and Soul (the ident check + its alternate table): the whole function,
         * +0x38, and the alternate table's load at +0x0A. Monomyth (agbcc, every
         * header XORed with 0x80000000): up to the `bl`, +6.
         */
        private val SIGNATURES = listOf(
            Signature("00b50004074a0849400b401883885900c918890089180a680168101c00f0a2fb", 6),
            Signature("00b5..4b0004400bc018828853009b18..4a9b000168d058........01bc0047", 2),
            Signature("00b5..4a03045b0b9b1899884a005218..4992008858..4a416b914202d11968........01bc0047", 2),
            Signature(
                "000430b5000c002916d0..4d2b685a1c12d02a0000210c3206e00c3213000c3b1b6801315c1c07d08342f6d1" +
                    "4b005b189b000433eb1802e0..4bc000c31899884a005218..4992008858..4a416b914202d11968........30bc01bc0047",
                0x38, altLdr = 0x0A,
            ),
            Signature("00b50004..4a..49400b401883885900c918890089180a680168802000064140101c", 6, headerXor = 0x80000000L),
        )
        private class Signature(val pattern: String, val tableLdr: Int, val altLdr: Int = -1, val headerXor: Long = 0)
        private const val MAX_SONGS = 4096

        /** Set on an alternate song's id: m4aSongNumStart's `alt` argument (Heart and Soul's second table). */
        const val ALT = 1 shl 16

        /** The song number to pass m4aSongNumStart for [id]. */
        fun number(id: Int): Int = id and 0xFFFF

        /** m4aSongNumStart's second argument for [id]: 1 for an alternate song, else 0. */
        fun alt(id: Int): Int = if (id and ALT != 0) 1 else 0

        /** [id] for the log: "559", or "559 (alt)". */
        fun label(id: Int): String = if (alt(id) == 1) "${number(id)} (alt)" else "$id"

        fun locate(rom: ByteArray): M4aSongs? {
            val (at, sig) = SIGNATURES.firstNotNullOfOrNull { sig ->
                indexOf(rom, sig.pattern, step = 2).takeIf { it >= 0 }?.let { it to sig }
            } ?: return null
            val table = literal(rom, at, sig.tableLdr) ?: return null
            val t = (table - ROM).toInt()
            if (t < 0 || t >= rom.size) return null
            val ids = HashMap<Long, Int>()
            val bgm = ArrayList<Int>()
            var n = 0
            while (n < MAX_SONGS && t + n * 8 + 8 <= rom.size) {
                val header = u32(rom, t + n * 8) xor sig.headerXor
                if (header !in ROM until ROM + rom.size) break
                val ms = u16(rom, t + n * 8 + 4)
                if (ids.putIfAbsent(header - ROM, n) == null && ms == MUSIC_PLAYER_BGM) bgm += n
                n++
            }
            if (sig.altLdr >= 0) literal(rom, at, sig.altLdr)?.let { addAlternates(rom, it, ids, bgm) }
            val spin = indexOf(rom, byteArrayOf(0xFE.toByte(), 0xE7.toByte()), step = 2).takeIf { it >= 0 }?.let { ROM + it }
            return if (n > 0) M4aSongs(ROM + at + 1, table, spin, ids, bgm) else null // +1: Thumb
        }

        /** The alternate table's songs, by header, as [ALT] ids (a header the main table has keeps its id there). */
        private fun addAlternates(rom: ByteArray, table: Long, ids: HashMap<Long, Int>, bgm: ArrayList<Int>) {
            var e = (table - ROM).toInt()
            if (e < 0) return
            var count = 0
            while (e + 12 <= rom.size && count++ < MAX_SONGS) {
                val n = u32(rom, e)
                if (n == 0xFFFFFFFFL || n > 0xFFFF) break
                val header = u32(rom, e + 4)
                if (header !in ROM until ROM + rom.size) break
                val id = n.toInt() or ALT
                if (ids.putIfAbsent(header - ROM, id) == null && u16(rom, e + 8) == MUSIC_PLAYER_BGM) bgm += id
                e += 12
            }
        }

        /** The word a Thumb `ldr rX, [pc, #imm]` at [fn]+[off] loads. */
        private fun literal(rom: ByteArray, fn: Int, off: Int): Long? {
            val ins = (rom[fn + off].toInt() and 0xFF) or ((rom[fn + off + 1].toInt() and 0xFF) shl 8)
            if (ins ushr 11 != 0b01001) return null
            val addr = ((fn + off + 4) and 3.inv()) + (ins and 0xFF) * 4
            return if (addr + 4 <= rom.size) u32(rom, addr) else null
        }

        private fun indexOf(hay: ByteArray, needle: ByteArray, step: Int): Int {
            var i = 0
            outer@ while (i <= hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) { i += step; continue@outer }
                return i
            }
            return -1
        }

        /** [indexOf] for a hex [pattern] whose "." nibbles match anything. */
        private fun indexOf(hay: ByteArray, pattern: String, step: Int): Int {
            val n = pattern.length / 2
            val value = IntArray(n) { pattern.substring(it * 2, it * 2 + 2).replace('.', '0').toInt(16) }
            val mask = IntArray(n) { k ->
                (if (pattern[k * 2] == '.') 0 else 0xF0) or (if (pattern[k * 2 + 1] == '.') 0 else 0x0F)
            }
            var i = 0
            outer@ while (i <= hay.size - n) {
                for (j in 0 until n) if ((hay[i + j].toInt() and mask[j]) != value[j]) { i += step; continue@outer }
                return i
            }
            return -1
        }

        private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, i: Int): Long =
            (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)


        private const val ROM = 0x08000000L
        private const val MUSIC_PLAYER_BGM = 0
    }
}
