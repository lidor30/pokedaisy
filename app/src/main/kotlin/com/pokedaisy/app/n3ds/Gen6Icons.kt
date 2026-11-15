package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.Gen5Lz
import com.pokedaisy.app.companion.data.RomArt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pokemon X / Y's party icons, rebuilt from the player's own cart (never bundled): RomFS `a/0/9/3`, a GARC of 945
 * LZ11-compressed icons. Each one: u16 ?, u16 colour count, that many RGBA5551 colours (bit 0 the alpha), then the
 * pixels in 8 x 8 tiles of Morton (Z) order over a 64 x 32 texture - 4 bits each, high nibble first, or a byte each
 * when there are more than 16 colours (8 of them: Pidgeot's...); the icon is its top-left 40 x 30. Written into
 * rom-art as `<index>.png` under [DIR], each a 40 x 80 sheet (the party slots' two square frames, the icon in both).
 * Which icon a Pokemon has is [Gen6IconTable]'s.
 */
object Gen6Icons {
    const val DIR = "n3ds/pokemonx/icons"
    private const val VERSION = 1
    private const val W = 40
    private const val H = 30

    /** [index]'s icon as a party-slot sheet's asset path. */
    fun asset(index: Int): String = "$DIR/$index.png"

    /** Writes [cart]'s icons into [dir] once (a marker keeps the cart's size and [VERSION]); true when it wrote them. */
    fun extractTo(cart: File, dir: File): Boolean {
        val marker = File(dir, "$DIR-built")
        val stamp = "v$VERSION ${cart.length()}"
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return false
        val files = RomFs(cart).use { fs -> fs.read("a/0/9/3") }?.let { Gen6Text.garcFiles(it) } ?: return false
        var wrote = 0
        files.forEachIndexed { i, f ->
            val img = decode(f) ?: return@forEachIndexed
            write(dir, asset(i), sheet(img).png())
            wrote++
        }
        if (wrote == 0) return false
        marker.parentFile?.mkdirs()
        marker.writeText(stamp)
        return true
    }

    /** One icon file (compressed or not) as 40 x 30 ARGB; null when it isn't one. */
    fun decode(file: ByteArray): RomArt.Image? {
        val d = if (file.isNotEmpty() && file[0].toInt() == 0x11) Gen5Lz.decompress(file) ?: return null else file
        if (d.size < 4) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getShort(2).toInt() and 0xFFFF
        if (n == 0 || n > 256 || d.size < 4 + 2 * n) return null
        val wide = n > 16
        val pal = IntArray(n) { i ->
            val v = b.getShort(4 + 2 * i).toInt() and 0xFFFF
            if (v and 1 == 0) 0
            else (0xFF shl 24) or (c5(v shr 11) shl 16) or (c5(v shr 6) shl 8) or c5(v shr 1)
        }
        val base = 4 + 2 * n
        val img = RomArt.Image(W, H)
        for (t in 0 until 32) {
            val tx = (t % 8) * 8
            val ty = (t / 8) * 8
            for (i in 0 until 64) {
                val k = if (wide) base + t * 64 + i else base + t * 32 + i / 2
                val byte = if (k < d.size) d[k].toInt() and 0xFF else 0
                val idx = if (wide) byte else if (i and 1 == 1) byte and 0xF else byte shr 4
                val x = tx + ((i and 1) or ((i shr 1) and 2) or ((i shr 2) and 4))
                val y = ty + (((i shr 1) and 1) or ((i shr 2) and 2) or ((i shr 3) and 4))
                if (x < W && y < H && idx < n) img.argb[y * W + x] = pal[idx]
            }
        }
        return img
    }

    private fun c5(v: Int) = (v and 31) * 255 / 31

    /** The party slots' sheet: two square frames, the icon at the bottom of each (as a GBA icon stands). */
    private fun sheet(icon: RomArt.Image): RomArt.Image {
        val out = RomArt.Image(W, W * 2)
        for (frame in 0 until 2) for (y in 0 until H) {
            System.arraycopy(icon.argb, y * W, out.argb, (frame * W + (W - H) + y) * W, W)
        }
        return out
    }

    private fun write(dir: File, path: String, bytes: ByteArray) {
        val out = File(dir, path)
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(out)
    }
}
