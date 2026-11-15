package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * NO HMS NEEDED (SETTINGS > GAMEPLAY): the field moves work without a Pokémon that knows them, once the game's badge
 * for them is in - the most loved change of the ROM hacks (no "HM slave" in the party). The game's own way stays: press
 * A at a tree, a rock, a boulder, the water, a waterfall or a dive spot, the game asks, the first Pokémon that isn't an
 * egg does the cut-in. One that knows the move still goes first.
 *
 * Every field move script runs `checkpartymove MOVE_X` after its badge check, and gives up when it answers PARTY_SIZE.
 * Its command, ScrCmd_checkpartymove, is rewritten in its own 124 bytes ([CHECK_CODE], scripts/hm_free.s): nobody
 * knowing the move, it answers the first Pokémon that isn't an egg. Surf has one more check before its script: the A
 * press on water asks PartyHasMonWithSurf (field_control_avatar.c), whose `beq` past "this one knows it" turns into a
 * `nop` ([SURF_NOP]), so any Pokémon will do there. Emerald's secret bases also ask `checkpartymove`, for a TM
 * (MOVE_SECRET_POWER): that one keeps the game's rule. Nothing else in the games asks it.
 *
 * Found in the ROM by agbcc's build of both functions ([find]), all or nothing: retail FireRed / LeafGreen / Emerald /
 * Ruby / Sapphire in every language and revision, and the hacks on that code. The two must call the same GetMonData and
 * MonKnowsMove and read the same gPlayerParty, and gSpecialVar_Result must sit 0x10 past gSpecialVar_0x8004 (the
 * rewrite reaches both through one word). gcc builds (expansion, Recharged) have other shapes: N/A.
 *
 * FLY, FREE FLIGHT and FLASH from the companion ask for the move themselves (Fly.kt, Flash.kt): while this is on they
 * take the same Pokémon ([active]).
 *
 * Patched through [com.pokedaisy.app.MgbaCore.pkRomPatch] in 4-byte pieces with REUSABLE TMS and INSTANT TEXT, the
 * file's bytes kept for the Poller's hash and RetroAchievements' (pk_cheats.c). Played headless with `rompatch` on
 * FireRed rev 1 (Cut with nobody knowing it, Surf with and without Lapras knowing it) and Emerald (Rock Smash).
 */
object HmFree {
    class Patch(val writes: List<InstantText.Write>)

    /** The option is on and the running ROM took the patch: FLY / FREE FLIGHT / FLASH don't ask for the move either. Set
     * by the activity (any thread reads it). */
    @Volatile var active = false

    private const val ROM_BASE = 0x08000000L
    private const val ANY = -1

    /** ScrCmd_checkpartymove as agbcc built it, 124 bytes with its literals: calls and literals left out. */
    private val CHECK = sig(
        "f0b5 ????????0004070c0249062008800026 1ce0 ????????" +
            "201c2d21 ???????? 002811d1201c391c ???????? 0006000e012809d1024806800248058017e00000 ????????????????" +
            "701c0006060e052e0dd86420311c4143 0748 0c18201c0b210022 ???????? 0004050c002dd4d10020f0bc02bc08470000 ????????",
    )
    private const val CHECK_LEN = 124
    private const val C_READ_HALFWORD = 0x02
    private const val C_GET_EGG = 0x1C
    private const val C_KNOWS = 0x28
    private const val C_GET_SPECIES = 0x62
    private const val C_RESULT = 0x14
    private const val C_RESULT2 = 0x40
    private const val C_VAR_8004 = 0x44
    private const val C_PARTY = 0x78

    /** PartyHasMonWithSurf, 0x50 bytes with its literal. */
    private val SURF = sig(
        "30b50820 ???????? 000600281ad100250be0201c3921 ???????? 0006002801d0012010e0681c0006050e052d0ad8" +
            "6420291c4143 0548 0c18201c0b21 ???????? 0028e6d1002030bc02bc0847 ????????",
    )
    private const val S_KNOWS = 0x16
    private const val S_GET_SPECIES = 0x3C
    private const val S_PARTY = 0x4C

    /** Where its `beq` (01 D0: past `return TRUE` when the Pokémon doesn't know Surf) sits, and the `nop` (mov r8, r8). */
    private const val S_BEQ = 0x1E
    private val SURF_NOP = byteArrayOf(0xC0.toByte(), 0x46)

    /**
     * scripts/hm_free.s assembled, the calls and the two literals zero ([CHECK_CODE]'s blanks, filled per ROM):
     * ScriptReadHalfword at 0x02, GetMonData at 0x18 / 0x24 / 0x64, MonKnowsMove at 0x36; gPlayerParty at 0x74,
     * gSpecialVar_0x8004 at 0x78.
     */
    private val CHECK_CODE = bytes(
        0xF0, 0xB5, 0xFF, 0xF7, 0xFE, 0xFF, 0x00, 0x04, 0x07, 0x0C, 0x06, 0x25, 0x00, 0x26, 0x19, 0x4C, 0x05, 0x2E, 0x17, 0xD8,
        0x20, 0x1C, 0x0B, 0x21, 0xFF, 0xF7, 0xFE, 0xFF, 0x00, 0x28, 0x11, 0xD0, 0x20, 0x1C, 0x2D, 0x21, 0xFF, 0xF7, 0xFE, 0xFF,
        0x00, 0x28, 0x08, 0xD1, 0x06, 0x2D, 0x00, 0xD1, 0x35, 0x1C, 0x20, 0x1C, 0x39, 0x1C, 0xFF, 0xF7, 0xFE, 0xFF, 0x00, 0x28,
        0x08, 0xD1, 0x76, 0x1C, 0x64, 0x34, 0xE5, 0xE7, 0x91, 0x20, 0x40, 0x00, 0x87, 0x42, 0x02, 0xD1, 0x06, 0x25, 0x00, 0xE0,
        0x35, 0x1C, 0x09, 0x49, 0x0D, 0x82, 0x06, 0x2D, 0x08, 0xD0, 0x64, 0x20, 0x68, 0x43, 0x05, 0x49, 0x40, 0x18, 0x0B, 0x21,
        0xFF, 0xF7, 0xFE, 0xFF, 0x03, 0x49, 0x08, 0x80, 0x00, 0x20, 0xF0, 0xBC, 0x02, 0xBC, 0x08, 0x47, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )
    private const val N_READ_HALFWORD = 0x02
    private val N_GET = listOf(0x18, 0x24, 0x64)
    private const val N_KNOWS = 0x36
    private const val N_PARTY = 0x74
    private const val N_VAR_8004 = 0x78

    /** What [find] found: both functions' places and what the rewrite calls / reads. */
    class Found(
        val check: Long,
        val surf: Long,
        val getMonData: Long,
        val monKnowsMove: Long,
        val party: Long,
        val var8004: Long,
        val patch: Patch,
    )

    private val cache = HashMap<String, Found?>()

    private val lock = SynchronizedObject()

    /** The patch for [rom], or null. Reads the file (a window at a time, ~150 ms for 32 MB): off the UI thread; cached per file. */
    fun find(rom: Path): Patch? = locate(rom)?.patch

    fun locate(rom: Path): Found? {
        val key = fileKey(rom)
        synchronized(lock) { if (key in cache) return cache[key] }
        val found = runCatching {
            withRomFile(rom) { locate(FileLittleEndian(it)) }
        }.getOrNull()
        synchronized(lock) { cache[key] = found }
        return found
    }

    fun locate(rom: LittleEndianReader): Found? {
        val checks = matches(rom, CHECK)
        val surfs = matches(rom, SURF)
        if (checks.size != 1 || surfs.size != 1) return null
        val c = checks[0]
        val s = surfs[0]
        if (c % 4 != 0) return null   // CHECK_CODE's `ldr`s count on it
        val readHalfword = blTarget(rom, c + C_READ_HALFWORD)
        val getMonData = blTarget(rom, c + C_GET_EGG)
        val knows = blTarget(rom, c + C_KNOWS)
        if (readHalfword == null || getMonData == null || knows == null) return null
        if (blTarget(rom, c + C_GET_SPECIES) != getMonData) return null
        if (blTarget(rom, s + S_KNOWS) != knows || blTarget(rom, s + S_GET_SPECIES) != getMonData) return null
        val result = word(rom, c + C_RESULT)
        val var8004 = word(rom, c + C_VAR_8004)
        val party = word(rom, c + C_PARTY)
        if (word(rom, c + C_RESULT2) != result || result != var8004 + 0x10 || word(rom, s + S_PARTY) != party) return null

        val code = CHECK_CODE.copyOf()
        bl(c + N_READ_HALFWORD, readHalfword).copyInto(code, N_READ_HALFWORD)
        N_GET.forEach { bl(c + it, getMonData).copyInto(code, it) }
        bl(c + N_KNOWS, knows).copyInto(code, N_KNOWS)
        le32(party).copyInto(code, N_PARTY)
        le32(var8004).copyInto(code, N_VAR_8004)
        val writes = (0 until CHECK_LEN step 4).map { i ->
            InstantText.Write(ROM_BASE + c + i, ByteArray(4) { rom.get(c + i + it) }, code.copyOfRange(i, i + 4))
        }.filter { !it.expect.contentEquals(it.value) } +
            InstantText.Write(ROM_BASE + s + S_BEQ, ByteArray(2) { rom.get(s + S_BEQ + it) }, SURF_NOP)
        return Found(
            ROM_BASE + c, ROM_BASE + s, ROM_BASE + getMonData, ROM_BASE + knows, party, var8004, Patch(writes),
        )
    }

    /** The places [sig] matches in [rom], on 2-byte steps. */
    private fun matches(rom: LittleEndianReader, sig: IntArray): List<Int> {
        val out = ArrayList<Int>()
        val b0 = sig[0].toByte()
        val b1 = sig[1].toByte()
        val end = rom.limit() - sig.size
        var o = 0
        while (o <= end) {
            if (rom.get(o) == b0 && rom.get(o + 1) == b1 &&
                sig.indices.all { i -> sig[i] == ANY || (rom.get(o + i).toInt() and 0xFF) == sig[i] }
            ) out += o
            o += 2
        }
        return out
    }

    /** Hex with `??` for a byte that may be anything (spaces ignored). */
    private fun sig(hex: String): IntArray {
        val h = hex.replace(" ", "")
        return IntArray(h.length / 2) { i ->
            val p = h.substring(i * 2, i * 2 + 2)
            if (p == "??") ANY else p.toInt(16)
        }
    }

    private fun word(rom: LittleEndianReader, at: Int): Long = rom.getInt(at).toLong() and 0xFFFFFFFFL

    private fun le32(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }

    /** Where the `bl` at [at] goes (a ROM offset), or null if it isn't one. */
    private fun blTarget(rom: LittleEndianReader, at: Int): Int? {
        val h1 = rom.getShort(at).toInt() and 0xFFFF
        val h2 = rom.getShort(at + 2).toInt() and 0xFFFF
        if (h1 and 0xF800 != 0xF000 || h2 and 0xF800 != 0xF800) return null
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

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
