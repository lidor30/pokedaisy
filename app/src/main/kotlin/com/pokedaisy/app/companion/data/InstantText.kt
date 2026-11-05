package com.pokedaisy.app.companion.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * INSTANT TEXT (SETTINGS > GAMEPLAY, GitHub #111): a text box's words appear at once, whatever the game's own text speed,
 * and nothing else speeds up (TEXT SPEED runs the whole game faster instead, so it stops at what the device can run).
 *
 * The FireRed and Emerald engines print through `RenderFont`, which calls the font's function until it stops answering
 * RENDER_REPEAT, once a frame. [FONT_CODE] is that function rewritten in its own 40 bytes (no free ROM needed, the
 * literal after it and its call kept): it goes on after RENDER_PRINT too, so a frame draws letters up to the next control
 * code, where it answers RENDER_PRINT for the game to copy the window. Line ends, colours, page breaks and pauses stay the
 * game's; only the wait between two letters is gone (the printer's textSpeed set to 0). The game's own instant path
 * (a delay of 0, AddTextPrinter's loop) would lose everything after a page break.
 *
 * Ruby and Sapphire have their own text engine: UpdateWindowText prints a letter, then sets a delay ([RS_DELAY]). A `b`
 * back to its print call there prints on until something else stops it (a wait for a button, a pause, the end).
 *
 * Found by its code in the ROM's first 64 KB, where every build keeps it, all or nothing ([find]): retail in every
 * language and the hacks on agbcc's build of that code. gcc's builds (expansion, Recharged) have another shape: N/A.
 *
 * Patched through [com.pokedaisy.app.MgbaCore.pkRomPatch] in 1-4 byte pieces, the file's bytes kept for the Poller's hash
 * and RetroAchievements' (pk_cheats.c).
 */
object InstantText {
    /** [value] over the ROM's [expect] at bus address [addr]. */
    class Write(val addr: Long, val expect: ByteArray, val value: ByteArray)

    class Patch(val writes: List<Write>)

    /** Where to look: every engine keeps its text code at the ROM's start. */
    private const val SEARCH = 0x10000
    private const val ROM_BASE = 0x08000000L

    private const val ANY = -1
    private const val ANY_BL_HIGH = -2   // the first half of a `bl`: 0xF0-0xF7
    private const val ANY_BL_LOW = -3    // the second half: 0xF8-0xFF

    /** RenderFont as agbcc built it, through its `bx r1`; 40 bytes, the gFonts literal straight after. */
    private val FONT = intArrayOf(
        0x10, 0xB5, 0x04, 0x1C, 0x61, 0x79, 0x08, 0x48, 0x02, 0x68, 0x48, 0x00, 0x40, 0x18, 0x80, 0x00, 0x80, 0x18, 0x01, 0x68,
        0x20, 0x1C, ANY, ANY_BL_HIGH, ANY, ANY_BL_LOW, 0x00, 0x04, 0x00, 0x0C, 0x02, 0x28, 0xF0, 0xD0, 0x10, 0xBC, 0x02, 0xBC,
        0x08, 0x47,
    )

    /** Where RenderFont's call to `_call_via_r1` sits, and where [FONT_CODE] has it. */
    private const val FONT_CALL = 0x16
    private const val NEW_CALL = 0x12

    /**
     * The new RenderFont, Thumb, for a function on a 4-byte boundary (its `ldr` reaches the literal at +0x28):
     *
     *     00 push {r4, lr}            ; 02 adds r4, r0, #0
     *     04 loop: ldrb r1, [r4, #5]  ; the font id
     *     06 movs r0, #12             ; 08 muls r1, r0     ; sizeof(struct FontInfo)
     *     0A ldr r0, =gFonts          ; 0C ldr r0, [r0]    ; 0E ldr r1, [r0, r1]
     *     10 adds r0, r4, #0          ; 12 bl _call_via_r1
     *     16 lsrs r1, r0, #1          ; 18 bcs done        ; RENDER_FINISH 1, RENDER_UPDATE 3: the game's
     *     1A bne loop                 ; RENDER_REPEAT 2, as the original
     *     1C strb r0, [r4, #0x1D]     ; RENDER_PRINT 0: textSpeed = 0, no wait after a letter
     *     1E ldr r1, [r4]             ; 20 ldrb r1, [r1]   ; the next character
     *     22 cmp r1, #0xF7            ; 24 bcc loop        ; a plain one: go on; a control code: the game copies the window
     *     26 done: pop {r4, pc}       ; callers are all Thumb
     */
    private val FONT_CODE = bytes(
        0x10, 0xB5, 0x04, 0x1C, 0x61, 0x79, 0x0C, 0x20, 0x41, 0x43, 0x07, 0x48, 0x00, 0x68, 0x41, 0x58, 0x20, 0x1C, 0, 0, 0, 0,
        0x41, 0x08, 0x05, 0xD2, 0xF3, 0xD1, 0x60, 0x77, 0x21, 0x68, 0x09, 0x78, 0xF7, 0x29, 0xEE, 0xD3, 0x10, 0xBD,
    )

    /** Ruby / Sapphire's UpdateWindowText: its `PrintNextChar(win)` and the switch on the state after it. */
    private val RS_PRINT = intArrayOf(0x20, 0x1C, ANY, ANY_BL_HIGH, ANY, ANY_BL_LOW, 0xE0, 0x8A, 0x0A, 0x28, 0x25, 0xD8, 0x80, 0x00)

    /** Its `state = WIN_STATE_CHAR_DELAY; delayCounter = GetTextDelay(win)` and the `return 0` after it. */
    private val RS_DELAY = intArrayOf(
        0x03, 0x20, 0xE0, 0x82, 0x20, 0x1C, 0x00, 0xF0, ANY, ANY_BL_LOW, 0x60, 0x73, 0x00, 0x20, 0x10, 0xBC, 0x02, 0xBC, 0x08, 0x47,
    )

    /** How far past [RS_PRINT] [RS_DELAY] sits in UpdateWindowText (the jump table between them). */
    private const val RS_GAP = 0x58

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** The patch for [rom] (reads its first 64 KB), null where the code isn't known. */
    fun find(rom: File): Patch? = runCatching {
        rom.inputStream().use { f ->
            val head = ByteArray(minOf(rom.length(), SEARCH.toLong()).toInt())
            var got = 0
            while (got < head.size) {
                val n = f.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
            find(ByteBuffer.wrap(head, 0, got).order(ByteOrder.LITTLE_ENDIAN))
        }
    }.getOrNull()

    fun find(rom: ByteBuffer): Patch? {
        val font = matches(rom, FONT)
        val print = matches(rom, RS_PRINT)
        val delay = matches(rom, RS_DELAY)
        return when {
            font.size == 1 && print.isEmpty() -> fontPatch(rom, font[0])
            font.isEmpty() && print.size == 1 && delay == listOf(print[0] + RS_GAP) -> rubyPatch(rom, print[0], delay[0])
            else -> null
        }
    }

    private fun matches(rom: ByteBuffer, sig: IntArray): List<Int> {
        val out = ArrayList<Int>()
        val end = minOf(rom.limit(), SEARCH) - sig.size - 4
        var o = 0
        while (o <= end) {
            if (sig.indices.all { i ->
                    val b = rom.get(o + i).toInt() and 0xFF
                    when (sig[i]) {
                        ANY -> true
                        ANY_BL_HIGH -> b in 0xF0..0xF7
                        ANY_BL_LOW -> b in 0xF8..0xFF
                        else -> b == sig[i]
                    }
                }
            ) out += o
            o += 2
        }
        return out
    }

    private fun fontPatch(rom: ByteBuffer, at: Int): Patch? {
        if (at % 4 != 0) return null   // FONT_CODE's `ldr` counts on it
        val code = FONT_CODE.copyOf()
        bl(at + NEW_CALL, blTarget(rom, at + FONT_CALL)).copyInto(code, NEW_CALL)
        val writes = (0 until code.size step 4).map { i ->
            Write(ROM_BASE + at + i, ByteArray(4) { rom.get(at + i + it) }, code.copyOfRange(i, i + 4))
        }.filter { !it.expect.contentEquals(it.value) }
        return Patch(writes)
    }

    private fun rubyPatch(rom: ByteBuffer, print: Int, delay: Int): Patch {
        val branch = 0xE000 or (((print - (delay + 4)) shr 1) and 0x7FF)
        return Patch(listOf(Write(ROM_BASE + delay, ByteArray(2) { rom.get(delay + it) }, bytes(branch and 0xFF, branch shr 8))))
    }

    /** Where the `bl` at [at] goes (a ROM offset). */
    private fun blTarget(rom: ByteBuffer, at: Int): Int {
        val h1 = (rom.get(at).toInt() and 0xFF) or ((rom.get(at + 1).toInt() and 0xFF) shl 8)
        val h2 = (rom.get(at + 2).toInt() and 0xFF) or ((rom.get(at + 3).toInt() and 0xFF) shl 8)
        var hi = h1 and 0x7FF
        if (hi and 0x400 != 0) hi -= 0x800
        return at + 4 + (hi shl 12) + ((h2 and 0x7FF) shl 1)
    }

    /** A `bl` at [at] to [target], both ROM offsets. */
    private fun bl(at: Int, target: Int): ByteArray {
        val off = (target - (at + 4)) shr 1
        val h1 = 0xF000 or ((off shr 11) and 0x7FF)
        val h2 = 0xF800 or (off and 0x7FF)
        return bytes(h1 and 0xFF, h1 shr 8, h2 and 0xFF, h2 shr 8)
    }
}
