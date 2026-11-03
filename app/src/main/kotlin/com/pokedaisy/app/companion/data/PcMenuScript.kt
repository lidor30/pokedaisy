package com.pokedaisy.app.companion.data

/**
 * The game's whole PC menu - "Which PC should be accessed?": the Pokémon storage, the player's own PC with its items and
 * mailbox, the Hall of Fame, the professor's - from anywhere, as a copy of the game's own script in free EWRAM.
 *
 * Running the ROM's script as it is won't do: leaving it runs AnimatePcTurnOff, which writes a switched-off PC's metatile in
 * front of the player, wherever that is. So its scripts are copied here with their jumps moved over and that one command
 * gone. Nothing per game: [find] follows the storage special [PcFns] already knows to the `goto` back to the menu, then
 * [build] walks the menu's jumps through a command length table. A command it doesn't know, or a menu of another shape,
 * means no copy: [openPc] then opens the storage alone, as it always did.
 */
internal object PcMenuScript {
    /** Past every retail build's last EWRAM symbol (FireRed's end around 0x0203FBB0), before FieldItems' scratch word. */
    const val RAM = 0x0203FD00L
    private const val MAX_SIZE = 0x2F0
    private const val ROM = 0x08000000L
    /** Every game's script sits in the first 4 MB: the deepest found is Emerald's, 0x271DAC. */
    private const val SCAN_BYTES = 0x400000

    private const val END = 0x02
    private const val RETURN = 0x03
    private const val GOTO = 0x05
    private const val GOTO_IF = 0x06
    private const val SETVAR = 0x16
    private const val SPECIAL = 0x25
    private const val PLAYSE = 0x2F
    private const val WAITMESSAGE = 0x66
    private const val MESSAGE = 0x67
    private const val LOCKALL = 0x69
    private const val RELEASEALL = 0x6B

    /** Gen 3's script commands these menus use, by opcode: their lengths (the same in every engine). */
    private val LENGTHS = mapOf(
        0x00 to 1, END to 1, RETURN to 1, 0x04 to 5, GOTO to 5, GOTO_IF to 6, 0x07 to 6, 0x09 to 2, 0x0F to 6, SETVAR to 5,
        0x19 to 5, 0x21 to 5, SPECIAL to 3, 0x27 to 1, 0x29 to 3, 0x2A to 3, 0x2B to 3, PLAYSE to 3, WAITMESSAGE to 1,
        MESSAGE to 5, 0x68 to 1, LOCKALL to 1, RELEASEALL to 1,
    )

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
    private fun ByteArray.romOffset(at: Int): Int {
        val v = (0..3).fold(0L) { acc, i -> acc or (u8(at + i).toLong() shl (8 * i)) }
        return if (v in ROM until ROM + size) (v - ROM).toInt() else -1
    }

    private fun isMenu(rom: ByteArray, at: Int) = at in 0 until rom.size - 8 &&
        rom.u8(at) == MESSAGE && rom.u8(at + 5) == WAITMESSAGE && rom.u8(at + 6) == SPECIAL

    /** Where the PC's main menu script is: the `goto` after the storage special [special], or -1. */
    fun find(rom: ByteArray, special: Int): Int {
        val lo = (special and 0xFF).toByte()
        val hi = (special shr 8).toByte()
        for (at in 0 until rom.size - 2) {
            if (rom[at].toInt() != SPECIAL || rom[at + 1] != lo || rom[at + 2] != hi) continue
            var p = at
            repeat(8) {
                val c = rom.u8(p)
                val len = LENGTHS[c] ?: return@repeat
                if (c == GOTO) {
                    val target = rom.romOffset(p + 1)
                    if (isMenu(rom, target)) return target
                    return@repeat
                }
                if (c == END || c == RETURN || p + len >= rom.size - 8) return@repeat
                p += len
            }
        }
        return -1
    }

    /** [at] is the script that turns the PC off: a few commands, then `releaseall; end`. */
    private fun isExit(rom: ByteArray, at: Int): Boolean {
        var p = at
        while (p - at < 24 && p + 1 < rom.size) {
            val c = rom.u8(p)
            if (c == RELEASEALL) return rom.u8(p + 1) == END
            if (c != SETVAR && c != PLAYSE && c != SPECIAL) return false
            p += LENGTHS.getValue(c)
        }
        return false
    }

    /** The menu at [menu] as a script for [ram], or null if it isn't the shape this knows. */
    fun build(rom: ByteArray, menu: Int, ram: Long = RAM): ByteArray? {
        // The blocks reachable by jumps (`call`s stay in the ROM: they come back), each up to where it can't run on.
        val blocks = LinkedHashMap<Int, Int>()
        val exits = LinkedHashSet<Int>()
        val queue = ArrayDeque(listOf(menu))
        while (queue.isNotEmpty()) {
            val start = queue.removeFirst()
            if (start in blocks || start in exits) continue
            if (start !in 0 until rom.size - 8) return null
            if (isExit(rom, start)) {
                exits += start
                continue
            }
            var p = start
            while (true) {
                val c = rom.u8(p)
                val len = LENGTHS[c] ?: return null
                if (c == GOTO || c == GOTO_IF) queue += rom.romOffset(p + if (c == GOTO) 1 else 2)
                p += len
                if (p >= rom.size - 8) return null
                if (c == END || c == RETURN || c == GOTO) break
            }
            blocks[start] = p
        }
        if (exits.size != 1) return null

        // Laid out after a `lockall`, the menu first, the exit last.
        val exit = exits.first()
        var exitEnd = exit
        while (rom.u8(exitEnd) != RELEASEALL) exitEnd += LENGTHS.getValue(rom.u8(exitEnd))
        exitEnd += 2
        val places = HashMap<Int, Int>()
        var size = 1
        for ((start, end) in blocks) {
            places[start] = size
            size += end - start
        }
        places[exit] = size
        size += exitEnd - exit
        if (size > MAX_SIZE) return null

        val out = ByteArray(size)
        out[0] = LOCKALL.toByte()
        for ((start, end) in blocks) {
            val base = places.getValue(start)
            rom.copyInto(out, base, start, end)
            var p = start
            while (p < end) {
                val c = rom.u8(p)
                if (c == GOTO || c == GOTO_IF) {
                    val operand = p + if (c == GOTO) 1 else 2
                    val to = ram + places.getValue(rom.romOffset(operand))
                    for (i in 0..3) out[base + operand - start + i] = (to shr (8 * i)).toByte()
                }
                p += LENGTHS.getValue(c)
            }
        }
        // The exit, less its first special: AnimatePcTurnOff (FireRed's second one puts the help context back).
        val base = places.getValue(exit)
        rom.copyInto(out, base, exit, exitEnd)
        var p = exit
        while (rom.u8(p) != SPECIAL) {
            if (rom.u8(p) == RELEASEALL) return null
            p += LENGTHS.getValue(rom.u8(p))
        }
        out.fill(0, base + p - exit, base + p - exit + 3)
        return out
    }

    private var cachedFor: PcFns? = null
    private var cached: ByteArray? = null

    /** [pc]'s menu script for this ROM, or null: worked out once per game, on the first open. */
    fun forGame(pc: PcFns, read: (addr: Long, len: Int) -> ByteArray): ByteArray? {
        if (cachedFor == pc) return cached
        val rom = ByteArray(SCAN_BYTES)
        for (at in 0 until SCAN_BYTES step 0x10000) read(ROM + at, 0x10000).copyInto(rom, at)
        val menu = find(rom, pc.special)
        cached = if (menu < 0) null else build(rom, menu)
        cachedFor = pc
        return cached
    }
}
