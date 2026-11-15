package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.i18n.tk
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * EXP SHARE (SETTINGS > GAMEPLAY): the whole party gains EXP from a win, not only the Pokémon that fought.
 *
 * Gen 3's getexp (battle_script_commands.c's Cmd_getexp) pays the Pokémon whose bit is set in gSentPokesToOpponent (a byte per
 * foe flank, set as each one is sent out, cleared for a new foe), the EXP divided by how many there are; nothing else in the
 * games reads those bits. So:
 *  - SPLIT ([Mode.SPLIT]): every party Pokémon that isn't an egg counts as having fought ([mark], each frame: the game's own
 *    switch-ins only ever add bits) - the EXP shared out across the party, as the first games' Exp. All did.
 *  - FULL: the same, and its two divisions by that count (`bl __divsi3` after `adds r1, r5, #0`: with and without an
 *    Exp. Share held) are `nop`s ([Found.full]), so each one gets what one Pokémon alone would have. A held Exp. Share still
 *    adds its half, a Lucky Egg its boost, a traded Pokémon its own.
 * Eggs are left out (the game never counts one, its sent-out bit is never set). Link battles and the Battle Frontier give no
 * EXP anyway.
 *
 * Found by agbcc's build of Cmd_getexp ([find]): FireRed / LeafGreen / Emerald in every language and the hacks on that code.
 * Ruby / Sapphire build it another way, and hacks that rewrote it (Odyssey, Gaia) or run their own (CFRU's: Radical Red,
 * Unbound, Amethyst, ... - [getexp]) show N/A. Played headless on
 * FireRed rev 1 (a wild battle won three ways, every Pokémon's EXP read before and after).
 */
object ExpShare {
    enum class Mode(val id: Int, val label: String) {
        OFF(0, tk("OFF")),
        SPLIT(1, tk("SPLIT")),
        FULL(2, tk("FULL EXP"));

        companion object {
            fun of(id: Int) = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    /** gSentPokesToOpponent (2 bytes), and FULL's two `nop`s. A DS game ([ds]) has neither here: its native side
     * (pk_nds.cpp's expShareTick) writes the battle's masks and patches the battle overlay itself. */
    class Found(val sentPokes: Long, val full: List<InstantText.Write>, val ds: Boolean = false)

    /**
     * The DS games EXP SHARE knows: Gen 4's US carts (Platinum rev 1, HeartGold, SoulSilver: pret's BtlCmd_CalcExpGain)
     * and Gen 5's (Black, White, Black 2, White 2: their battle overlay's EXP routine), at the addresses pk_nds_field.cpp
     * patches, checked headless; on melonDS only - DSperate's recompiler wouldn't see a code patch. [hack]: Renegade
     * Platinum or a Redux build, which keep retail's header (their battle code not checked).
     */
    fun dsFind(code: String, rev: Int, dsperate: Boolean, hack: Boolean): Found? = when {
        dsperate || hack -> null
        code == "CPUE" && rev == 1 || rev == 0 && code in setOf("IPKE", "IPGE", "IRBO", "IRAO", "IREO", "IRDO") -> Found(0, emptyList(), ds = true)
        else -> null
    }

    private const val ROM_BASE = 0x08000000L
    private const val ANY = -1

    /** Cmd_getexp's start, through gSentPokesToOpponent's load (its call and literals left out). */
    private val GETEXP = sig("f0b54f464646c0b400260f480068503081460e4800684078????????0c4908700c4a022101400906490e891809788846")
    private const val SENT_POKES_LDR = 0x20
    private const val BODY = 0x300
    private val NOP2 = byteArrayOf(0xC0.toByte(), 0x46, 0xC0.toByte(), 0x46)

    private val cache = HashMap<String, Found?>()

    private val lock = SynchronizedObject()

    /** What [rom] has, or null. Reads the file (a window at a time): off the UI thread; cached per file. */
    fun find(rom: Path): Found? {
        val key = fileKey(rom)
        synchronized(lock) { if (key in cache) return cache[key] }
        val found = runCatching {
            withRomFile(rom) { find(FileLittleEndian(it)) }
        }.getOrNull()
        synchronized(lock) { cache[key] = found }
        return found
    }

    fun find(rom: LittleEndianReader): Found? {
        val c = getexp(rom) ?: return null
        if (c + BODY > rom.limit()) return null
        val sent = literal(rom, c + SENT_POKES_LDR)
        if (sent !in 0x02000000L..0x0203FFFFL) return null
        // The division: the call after `movs r1, #7` (the yield * level / 7).
        val div = (0 until BODY - 6 step 2).firstNotNullOfOrNull { i ->
            if ((rom.getShort(c + i).toInt() and 0xFFFF) == 0x2107) blTarget(rom, c + i + 2) else null
        } ?: return null
        // `adds r0, rN, #0; adds r1, r5, #0; bl __divsi3`: the two divisions by how many fought.
        val sites = (0 until BODY - 8 step 2).filter { i ->
            (rom.get(c + i + 1).toInt() and 0xFF) == 0x1C && (rom.getShort(c + i + 2).toInt() and 0xFFFF) == 0x1C29 &&
                blTarget(rom, c + i + 4) == div
        }
        if (sites.size != 2) return null
        val full = sites.map { i ->
            val at = c + i + 4
            InstantText.Write(ROM_BASE + at, ByteArray(4) { rom.get(at + it) }, NOP2)
        }
        return Found(sent, full)
    }

    /**
     * Where Cmd_getexp starts in [rom] (a ROM offset), only if the game runs it: its address is entry 0x23 (getexp) of a
     * battle script command table the code loads. CFRU's hacks (Radical Red, Unbound, Amethyst, Sword and Shield, GS
     * Chronicles) keep the old function but run their own from a new table, and the old table is no longer loaded by
     * anything: null there. Scans the whole ROM twice (about 50 ms for 32 MB).
     */
    internal fun getexp(rom: LittleEndianReader): Int? {
        val hits = ArrayList<Int>()
        var o = 0
        while (o + GETEXP.size <= rom.limit()) {
            if (rom.get(o) == 0xF0.toByte() && rom.get(o + 1) == 0xB5.toByte() && matches(rom, o, GETEXP)) hits += o
            o += 4
        }
        val c = hits.singleOrNull() ?: return null
        val fn = (ROM_BASE + c + 1).toInt()
        val tables = HashSet<Int>()
        o = 0
        while (o + 4 <= rom.limit()) {
            if (rom.getInt(o) == fn && o >= CMD_GETEXP * 4) tables += (ROM_BASE + o - CMD_GETEXP * 4).toInt()
            o += 4
        }
        if (tables.isEmpty()) return null
        o = 0
        while (o + 4 <= rom.limit()) {
            if (rom.getInt(o) in tables) return c
            o += 4
        }
        return null
    }

    /** getexp's command number (B_SCR_OP_GETEXP) in every Gen 3 game. */
    private const val CMD_GETEXP = 0x23

    /**
     * SPLIT / FULL's every frame: both flanks' bits set to [mask] (the party's slots that aren't eggs) if they aren't already.
     * Emu thread.
     */
    fun mark(f: Found, mask: Int, read: (Long, Int) -> ByteArray, write: (Long, ByteArray) -> Boolean) {
        val now = read(f.sentPokes, 2)
        val m = mask.toByte()
        if (now.size == 2 && now[0] == m && now[1] == m) return
        write(f.sentPokes, byteArrayOf(m, m))
    }

    /** The party slots that aren't eggs, as getexp's bits. */
    fun maskOf(party: List<MonView>): Int =
        party.take(6).withIndex().filter { !it.value.isEgg && it.value.species > 0 }.fold(0) { m, (i, _) -> m or (1 shl i) }

    private fun matches(rom: LittleEndianReader, at: Int, s: IntArray): Boolean {
        if (at < 0 || at + s.size > rom.limit()) return false
        for (i in s.indices) if (s[i] != ANY && (rom.get(at + i).toInt() and 0xFF) != s[i]) return false
        return true
    }

    private fun literal(rom: LittleEndianReader, at: Int): Long {
        val ins = rom.getShort(at).toInt() and 0xFFFF
        return rom.getInt(((at + 4) and 3.inv()) + ((ins and 0xFF) shl 2)).toLong() and 0xFFFFFFFFL
    }

    private fun blTarget(rom: LittleEndianReader, at: Int): Int? {
        val h1 = rom.getShort(at).toInt() and 0xFFFF
        val h2 = rom.getShort(at + 2).toInt() and 0xFFFF
        if (h1 and 0xF800 != 0xF000 || h2 and 0xF800 != 0xF800) return null
        var hi = h1 and 0x7FF
        if (hi and 0x400 != 0) hi -= 0x800
        return at + 4 + (hi shl 12) + ((h2 and 0x7FF) shl 1)
    }

    private fun sig(hex: String) = IntArray(hex.length / 2) { i ->
        val p = hex.substring(i * 2, i * 2 + 2)
        if (p == "??") ANY else p.toInt(16)
    }
}
