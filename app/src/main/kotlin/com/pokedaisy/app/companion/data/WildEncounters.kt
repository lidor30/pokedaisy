package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * WILD ENCOUNTERS (SETTINGS > CONTROLS, GitHub #43): the game's own switch for random encounters,
 * `sWildEncountersDisabled` (Ruby / Sapphire: gWildEncountersDisabled) - StandardWildEncounter returns at once while
 * it's 1, so walking in grass, caves and water meets nothing, while fishing, Sweet Scent and scripted battles go on.
 * FireRed's quest log sets it for its replay; nothing else in the retail games touches it.
 *
 * Found in the ROM by StandardWildEncounter's code, no table per game: its first lines load the switch from a literal
 * pool, compare it with 1, then call GetCurrentMapWildMonHeaderId and load HEADER_NONE (0xFFFF). Two shapes, as
 * agbcc built FireRed / LeafGreen and Emerald / Ruby / Sapphire - every language, and the hacks that kept that code
 * (Glazed, Kaizo, Gaia, Odyssey, Orange Islands, Ultra Violet, Celia, ...). The CFRU hacks (Unbound, Radical Red,
 * Amethyst, ...) hook it with their own StandardWildEncounter, which still reads FireRed's switch at 0x020386DC: that
 * read is looked for too. The gcc builds (pokeemerald-expansion's SoulGold, Lazarus, ..., Recharged Yellow's
 * pokefirered) inline GetCurrentMapWildMonHeaderId and order the lines their own way, so they get a looser shape
 * ([gccRead]): the switch's byte loaded and compared with 1, a branch, a call, then 0xFFFF loaded - taken only when
 * every such spot in the ROM names the same switch. Emerald Rogue's isn't found.
 *
 * pk_wide.c's `wildRoll` sets it each frame the player is free on the field (in [EmulatorEngine.wildEncounters]% of
 * frames, so that share of the steps that would have met a Pokémon are let through).
 */
object WildEncounters {
    /** Percent of wild encounters left: NORMAL (the game's), 75 / 50 / 25 %, OFF. */
    val STEPS = listOf(100, 75, 50, 25, 0)

    private const val FIRERED_SWITCH = 0x020386DCL

    // StandardWildEncounter's start; LDR = the switch (EWRAM), FFFF = a load of 0xFFFF, B / BL = any branch / call.
    private const val LDR = -1
    private const val FFFF = -2
    private const val B = -3
    private const val BL1 = -4
    private const val BL2 = -5
    private val FIRERED = intArrayOf(
        0xB5F0, 0x4647, 0xB480, 0x1C06, 0x0409, 0x0C0F, 0x46B8, LDR, 0x7800, 0x2801, 0xD100, B, BL1, BL2, 0x0400, 0x0C04, FFFF,
    )
    private val EMERALD = intArrayOf(
        0xB5F0, 0x464F, 0x4646, 0xB4C0, 0x0400, 0x0C06, 0x46B1, 0x0409, 0x0C0F, 0x46B8, LDR, 0x7800, 0x2801, 0xD100, B,
        BL1, BL2, 0x0400, 0x0C05, FFFF,
    )

    private val cache = HashMap<String, Long>()

    /** The switch's EWRAM address in [rom], or 0 if its code isn't one of the shapes above. Reads the file (mapped,
     * ~100 ms for 32 MB): call it off the UI thread; cached per file. */
    fun find(rom: File): Long {
        val key = "${rom.path}:${rom.length()}:${rom.lastModified()}"
        synchronized(cache) { cache[key]?.let { return it } }
        val found = runCatching {
            RandomAccessFile(rom, "r").use { f ->
                find(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()).order(ByteOrder.LITTLE_ENDIAN))
            }
        }.getOrDefault(0L)
        synchronized(cache) { cache[key] = found }
        return found
    }

    fun find(rom: ByteBuffer): Long {
        val b = rom.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val size = b.limit()
        val bpre = size >= 0xB0 && String(ByteArray(3) { b.get(0xAC + it) }, Charsets.ISO_8859_1) == "BPR"
        var cfru = false
        val gcc = HashSet<Long>()
        var o = 0
        while (o + 6 <= size) {
            // ldrb r0, [r0]; cmp r0, #1; bne: the switch read in both agbcc shapes.
            if (u16(b, o) == 0x7800 && u16(b, o + 2) == 0x2801 && u16(b, o + 4) == 0xD100) {
                matchAt(b, o - 2 * 8, FIRERED)?.let { return it }
                matchAt(b, o - 2 * 11, EMERALD)?.let { return it }
            }
            // CFRU's own (gcc): ldr rN, =0x020386DC; ldrb rN, [rN]; ...; cmp rN, #1.
            if (bpre && !cfru && u16(b, o) shr 11 == 0b01001 && pool(b, o) == FIRERED_SWITCH) cfru = cfruRead(b, o)
            if (gcc.size < 2 && u16(b, o) shr 11 == 0b01001) gccRead(b, o)?.let { gcc += it }
            o += 2
        }
        return when {
            cfru -> FIRERED_SWITCH
            gcc.size == 1 -> gcc.first()
            else -> 0L
        }
    }

    /**
     * gcc's StandardWildEncounter at [o]: `ldr rA, =switch; ldrb rB, [rA]`, up to six lines of argument shuffling,
     * `cmp rB, #1` and its branch, the header lookup's call within the next eleven lines (Elite Redux 2.65.4.1b reads the
     * player's coordinates first: nine lines), 0xFFFF (HEADER_NONE) loaded within three after it and compared with the
     * result. The switch, or null.
     */
    private fun gccRead(b: ByteBuffer, o: Int): Long? {
        val switch = pool(b, o) ?: return null
        if (switch !in 0x02000000L until 0x02040000L || o + 2 * 28 > b.limit()) return null
        val ra = (u16(b, o) shr 8) and 7
        val ld = u16(b, o + 2)
        if (ld and 0xFFC0 != 0x7800 || (ld shr 3) and 7 != ra) return null
        val rb = ld and 7
        val cmp = (2..8).firstOrNull { u16(b, o + 2 * it) == 0x2800 or (rb shl 8) or 1 } ?: return null
        if (u16(b, o + 2 * cmp + 2) shr 12 != 0xD) return null
        val bl = (cmp + 2..cmp + 12).firstOrNull { u16(b, o + 2 * it) shr 11 == 0b11110 && u16(b, o + 2 * it + 2) shr 11 == 0b11111 }
            ?: return null
        // ... and compared with the lookup's result right after (Monomyth has a function of this shape that passes
        // 0xFFFF on as an argument instead: its byte is no switch).
        val ffff = (bl + 2..bl + 4).firstOrNull { u16(b, o + 2 * it) shr 11 == 0b01001 && pool(b, o + 2 * it) == 0xFFFFL }
            ?: return null
        val cmp2 = (ffff + 1..ffff + 2).any { val h = u16(b, o + 2 * it); h and 0xFFC0 == 0x4280 || h and 0xFF00 == 0x4500 }
        return if (cmp2) switch else null
    }

    private fun cfruRead(b: ByteBuffer, o: Int): Boolean {
        val r = (u16(b, o) shr 8) and 7
        if (o + 10 > b.limit()) return false
        val next = IntArray(4) { u16(b, o + 2 + 2 * it) }
        val ld = next.indexOfFirst { it and 0xFFC0 == 0x7800 && (it shr 3) and 7 == r }
        if (ld < 0) return false
        val d = next[ld] and 7
        return (ld + 1 until next.size).any { next[it] == 0x2800 or (d shl 8) or 1 }
    }

    private fun matchAt(b: ByteBuffer, start: Int, shape: IntArray): Long? {
        if (start < 0 || start + 2 * shape.size > b.limit()) return null
        var switch = 0L
        for ((i, want) in shape.withIndex()) {
            val o = start + 2 * i
            val h = u16(b, o)
            when (want) {
                LDR -> {
                    if (h shr 8 != 0x48) return null
                    switch = pool(b, o) ?: return null
                    if (switch !in 0x02000000L until 0x02040000L) return null
                }
                FFFF -> if (h shr 8 != 0x48 || pool(b, o) != 0xFFFFL) return null
                B -> if (h shr 11 != 0b11100) return null
                BL1 -> if (h shr 11 != 0b11110) return null
                BL2 -> if (h shr 11 != 0b11111) return null
                else -> if (h != want) return null
            }
        }
        return switch
    }

    private fun u16(b: ByteBuffer, o: Int) = b.getShort(o).toInt() and 0xFFFF

    /** The word an `ldr rX, [pc, #imm]` at [o] loads. */
    private fun pool(b: ByteBuffer, o: Int): Long? {
        val at = ((o + 4) and 3.inv()) + (u16(b, o) and 0xFF) * 4
        return if (at + 4 <= b.limit()) b.getInt(at).toLong() and 0xFFFFFFFFL else null
    }
}
