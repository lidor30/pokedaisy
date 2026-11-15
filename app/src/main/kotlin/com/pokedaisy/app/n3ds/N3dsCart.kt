package com.pokedaisy.app.n3ds

import java.io.File
import java.io.RandomAccessFile

/**
 * A 3DS cart image (`.3ds` / `.cci`: NCSD), read from its header alone, without the native library: whether it is one,
 * its title id and whether its contents are decrypted (only those play: PokeDaisy never has the console's keys).
 */
object N3dsCart {
    /** The file extensions a 3DS cart image goes by. */
    val EXTENSIONS = setOf("3ds", "cci")

    /** Pokemon X (USA, every language on the cart). */
    const val POKEMON_X_USA = 0x0004000000055D00L

    /** Pokemon Y (USA): X's code and memory, its own version's Pokemon (2026-11-15: every address X's, headless). */
    const val POKEMON_Y_USA = 0x0004000000055E00L

    /** The carts the app plays so far: Pokemon X first, Y with it (docs/3DS_GAMES_PLAN.md). */
    val SUPPORTED = setOf(POKEMON_X_USA, POKEMON_Y_USA)

    class Header(val titleId: Long, val decrypted: Boolean)

    fun isCart(f: File): Boolean = f.extension.lowercase() in EXTENSIONS && header(f) != null

    /**
     * The NCSD header: "NCSD" at 0x100, the media (title) id at 0x108, the partition table at 0x120 (offset, size in
     * 0x200-byte units). Decrypted = the first partition's NCCH has the NoCrypto flag (flags[7] bit 2, NCCH +0x18F).
     */
    fun header(f: File): Header? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(0x200)
            raf.readFully(h)
            if (String(h, 0x100, 4, Charsets.US_ASCII) != "NCSD") return null
            val titleId = le64(h, 0x108)
            val part0 = le32(h, 0x120).toLong() and 0xFFFFFFFFL
            val ncch = ByteArray(0x200)
            raf.seek(part0 * 0x200)
            raf.readFully(ncch)
            if (String(ncch, 0x100, 4, Charsets.US_ASCII) != "NCCH") return null
            Header(titleId, decrypted = (ncch[0x18F].toInt() and 0x04) != 0)
        }
    }.getOrNull()

    fun supported(f: File): Boolean = header(f)?.let { it.decrypted && it.titleId in SUPPORTED } == true

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int) = (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)
}
