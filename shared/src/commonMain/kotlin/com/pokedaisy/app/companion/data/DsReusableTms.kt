package com.pokedaisy.app.companion.data

/**
 * REUSABLE TMS on the Gen 4 DS games (2026-11-18): HeartGold / SoulSilver, Platinum and their language carts.
 *
 * Teaching a TM ends in the party menu's LearnMoveToSlot (pokeheartgold's PartyMenu_LearnMoveToSlot, pokeplatinum's party
 * menu callbacks): the move set, then `if (!MoveIsHM(move)) Bag_TakeItem(bag, item, 1, HEAP_ID_PARTY_MENU)`. Compiled:
 * `bl MoveIsHM; cmp r0, #0; bne skip; ldr; movs r2, #1; ldr; movs r3, #12; ldr; ldrh; bl Bag_TakeItem`. The `bne` made a
 * `b` to the same place keeps every TM (HMs never left the bag); the friendship and mood the move brings stay.
 *
 * In the static ARM9 of every Gen 4 cart, so always in RAM ([find]: one place each - HG / SS 0x020825A4 in English, French,
 * German and Europe rev 10, Japanese 0x02081FFC, Korean 0x020826A8; Platinum 0x020865E8, French / Spanish 0x02086688).
 * Written like INSTANT TEXT's call (pk_nds_code_write, the JIT told), checked twice a second.
 */
object DsReusableTms {
    /** The `bne` ([site]) and its two bytes before / after. */
    class Found(val site: Long, val original: ByteArray, val patched: ByteArray)

    private const val ARM9 = 0x02000000L
    private const val SCAN = 0x100000
    private const val ANY = -1

    /** After the `bl MoveIsHM`: `cmp r0, #0; bne; ldr r0, [pc]; movs r2, #1; ldr r1, [r5, r0]; movs r3, #12; ldr r0, [r1, #4]; ldrh`. */
    private val SIG = intArrayOf(0x00, 0x28, ANY, 0xD1, ANY, 0x48, 0x01, 0x22, ANY, 0x58, 0x0C, 0x23, ANY, 0x68, ANY, ANY)

    fun find(read: (Long, Int) -> ByteArray): Found? {
        val ram = runCatching { read(ARM9, SCAN) }.getOrNull() ?: return null
        if (ram.size < SCAN) return null
        val hits = ArrayList<Int>()
        var i = 4
        while (i + SIG.size + 4 <= ram.size && hits.size < 2) {
            if (matches(ram, i)) hits += i
            i += 2
        }
        val at = hits.singleOrNull() ?: return null
        val off = ram[at + 2].toInt()   // the bne's own offset, signed
        val b = 0xE000 or (off and 0x7FF)
        return Found(ARM9 + at + 2, byteArrayOf(ram[at + 2], ram[at + 3]), byteArrayOf(b.toByte(), (b shr 8).toByte()))
    }

    private fun matches(b: ByteArray, at: Int): Boolean {
        for (k in SIG.indices) if (SIG[k] != ANY && (b[at + k].toInt() and 0xFF) != SIG[k]) return false
        // The ldrh's top byte (0x88-0x8F), a `bl` before (MoveIsHM) and after (Bag_TakeItem).
        return (b[at + 15].toInt() and 0xF8) == 0x88 && isBl(b, at - 4) && isBl(b, at + SIG.size)
    }

    private fun isBl(b: ByteArray, at: Int) =
        (b[at + 1].toInt() and 0xF8) == 0xF0 && (b[at + 3].toInt() and 0xF8) == 0xF8

    /** On: the `b` where it's still the game's `bne`; off: the `bne` back where it's ours. Emu thread. */
    fun sync(f: Found, on: Boolean, read: (Long, Int) -> ByteArray, write: (Long, ByteArray) -> Boolean) {
        val now = runCatching { read(f.site, 2) }.getOrNull() ?: return
        if (on && now.contentEquals(f.original)) write(f.site, f.patched)
        else if (!on && now.contentEquals(f.patched)) write(f.site, f.original)
    }
}
