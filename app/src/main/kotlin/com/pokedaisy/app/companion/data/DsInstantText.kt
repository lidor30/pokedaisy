package com.pokedaisy.app.companion.data

/**
 * INSTANT TEXT on the Gen 4 DS games (2026-11-18): HeartGold / SoulSilver, Platinum and their language carts.
 *
 * Their text printer task (text.c's RunTextPrinter) renders one step a frame: `switch (RenderFont(printer))`, PRINT (a letter
 * drawn) copies the window to VRAM and goes on to the callback, FINISH destroys the printer, UPDATE (a delay, a wait for a
 * button, a line scrolling) runs the callback. Its one `bl RenderFont` is pointed at a routine of ours ([CODE], in the spare
 * ITCM at [ROUTINE]): RenderFont again after each letter, the printer's letter delay (+0x2A) zeroed, until it stops printing -
 * a wait, a page break, a scroll, a pause or the end - then PRINT back to the caller (which copies the window and runs its
 * callback); if the text ended there it copies the window itself and returns FINISH. So a page comes up whole, at any
 * TEXT SPEED, and every wait for A / B, scroll and page break still happens. At most 255 letters a frame.
 *
 * Found in the ARM9 as it runs ([find]: the call and the switch's compares around it, one place in every Gen 4 cart: HG / SS
 * 0x02020310 in English, French, German, Spanish, Italian and Europe rev 10, Japanese 0x0201FFB0, Korean 0x020202A8;
 * Platinum 0x0201D9A0, French / Spanish 0x0201DA10), RenderFont and CopyWindowToVram from that code's own calls. Played
 * headless on HeartGold: a wild battle's "Wild ... appeared!" and "Go! ...!" came up whole in one frame, the battle went on.
 */
object DsInstantText {
    /** Where the routine goes: the top 4 KB of the spare ITCM the call hook uses (pk_nds_call_abi.h), nothing else there. */
    const val ROUTINE = 0x01FFF000L

    /** The routine (`scripts/ds_instant_text.s`, assembled with llvm-mc for thumbv5te); its two calls are filled per cart. */
    private val CODE = hex("70b5041c0025201cfff7faff002807d10135ff2d0dd20026211c2a310e70f2e7002d07d0012804d16068fff7e9ff012000e0002070bd")
    private const val CALL_RENDER = 0x08
    private const val CALL_COPY = 0x2A

    /** What [find] found: the `bl RenderFont` to redirect ([site]: its address; [original]: its 4 bytes) and the routine. */
    class Found(val site: Long, val original: ByteArray, val patched: ByteArray, val routine: ByteArray)

    private const val ARM9 = 0x02000000L
    private const val SCAN = 0x100000
    private const val ANY = -1

    /** `adds r0, r4, #0; bl RenderFont; cmp r0, #0; beq; cmp r0, #1; beq; cmp r0, #3; beq; pop {r4, pc}; ldr r0, [r4, #4]`. */
    private val SIG = intArrayOf(
        0x20, 0x1C, ANY, ANY, ANY, ANY, 0x00, 0x28, 0x04, 0xD0, 0x01, 0x28, 0x0E, 0xD0, 0x03, 0x28, 0x03, 0xD0, 0x10, 0xBD, 0x60, 0x68,
    )

    /** The running game's printer task in its ARM9 (main RAM from 0x02000000), or null: not a Gen 4 printer, not found once. */
    fun find(read: (Long, Int) -> ByteArray): Found? {
        val ram = runCatching { read(ARM9, SCAN) }.getOrNull() ?: return null
        if (ram.size < SCAN) return null
        val hits = ArrayList<Int>()
        var i = 0
        while (i + SIG.size + 6 <= ram.size && hits.size < 2) {
            if (matches(ram, i)) hits += i
            i += 2
        }
        val at = hits.singleOrNull() ?: return null
        val site = ARM9 + at + 2
        val render = blTarget(ram, at + 2) ?: return null
        val copy = blTarget(ram, at + SIG.size) ?: return null
        if (render !in ARM9 until ARM9 + SCAN || copy !in ARM9 until ARM9 + SCAN) return null
        val routine = CODE.copyOf()
        bl(ROUTINE + CALL_RENDER, render).copyInto(routine, CALL_RENDER)
        bl(ROUTINE + CALL_COPY, copy).copyInto(routine, CALL_COPY)
        return Found(site, ram.copyOfRange(at + 2, at + 6), bl(site, ROUTINE), routine)
    }

    private fun matches(b: ByteArray, at: Int): Boolean {
        for (k in SIG.indices) if (SIG[k] != ANY && (b[at + k].toInt() and 0xFF) != SIG[k]) return false
        // The call is a `bl` (its two halves' tops F0-F7 / F8-FF); the one after `ldr r0, [r4, #4]` too.
        return isBl(b, at + 2) && isBl(b, at + SIG.size)
    }

    private fun isBl(b: ByteArray, at: Int) =
        (b[at + 1].toInt() and 0xF8) == 0xF0 && (b[at + 3].toInt() and 0xF8) == 0xF8

    private fun blTarget(b: ByteArray, at: Int): Long? {
        if (!isBl(b, at)) return null
        val h1 = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        val h2 = (b[at + 2].toInt() and 0xFF) or ((b[at + 3].toInt() and 0xFF) shl 8)
        var off = ((h1 and 0x7FF) shl 12) or ((h2 and 0x7FF) shl 1)
        if (off and 0x400000 != 0) off -= 0x800000
        return ARM9 + at + 4 + off
    }

    /** A Thumb `bl` at [at] to [target]. */
    fun bl(at: Long, target: Long): ByteArray {
        val off = (target - (at + 4)).toInt()
        val h1 = 0xF000 or ((off shr 12) and 0x7FF)
        val h2 = 0xF800 or ((off shr 1) and 0x7FF)
        return byteArrayOf(h1.toByte(), (h1 shr 8).toByte(), h2.toByte(), (h2 shr 8).toByte())
    }

    /**
     * Twice a second on the emu thread: on, the call pointed at the routine (written first) where it's still the game's own;
     * off, put back where it's ours. A state loaded from the other setting is set right the same way.
     */
    fun sync(f: Found, on: Boolean, read: (Long, Int) -> ByteArray, write: (Long, ByteArray) -> Boolean) {
        val now = runCatching { read(f.site, 4) }.getOrNull() ?: return
        if (on && now.contentEquals(f.original)) {
            if (write(ROUTINE, f.routine)) write(f.site, f.patched)
        } else if (!on && now.contentEquals(f.patched)) {
            write(f.site, f.original)
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
