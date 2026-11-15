package com.pokedaisy.app.companion.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * The POKéMON CENTER anywhere: the party healed, a move relearned, a move forgotten, a nickname changed - each the game's own
 * special, the one its nurse / Move Tutor / Move Deleter / Name Rater runs, with the Pokémon the companion's summary is on
 * (gSpecialVar_0x8004) instead of the game's party menu. PC anywhere's idea (Pc.kt): a few script commands in free EWRAM
 * ([SCRIPT]), run by ScriptContext_SetupScript between frames, on the player's free field.
 *
 *  - HEAL: `lockall; special HealPlayerParty; playfanfare MUS_HEAL; waitfanfare; releaseall; end`.
 *  - NICKNAME: the game's EventScript_ChangePokemonNickname - `fadescreen FADE_TO_BLACK; special ChangePokemonNickname;
 *    waitstate` - for slot 0x8004. The Name Rater's rules: not an egg, not a Pokémon from a trade (IsMonOTIDNotPlayers).
 *  - FORGET: the Move Deleter's last step, `special MoveDeleterForgetMove` (0x8004 the Pokémon, 0x8005 the move), then its
 *    fanfare. Its rules: not an egg, not the only move; and Emerald's, kept in every game: not the party's last Surf (with
 *    NO HMS NEEDED on, nobody needs it).
 *  - RELEARN: the Move Tutor's screen, opened the way the TM screen opens (TmTeach.kt): the controls locked and a fade to
 *    black, then what TeachMoveRelearnerMove's task does - gFieldCallback = FieldCB_ContinueScriptHandleMusic, SetMainCallback2
 *    = the screen's init - after CleanupOverworldWindowsAndTilemaps (as every other screen from the field) and a
 *    `releaseall; end` for the field to continue with. FireRed's screen draws its words with the message box's palette (15)
 *    and never loads it: the tutor's message before it always has. Right after a save is loaded, the recap ("Previously on
 *    your quest") has left its own dark one there, and the screen came up with black boxes for words, so FireRed's
 *    LoadStdWindowFrameGfx ([Found.windowFrame]) loads the field's first.
 *
 * Found by their code in the game's gSpecials ([find]): HealPlayerParty is its first entry, the others are matched by agbcc's
 * build of them (calls and literals left out), TeachMoveRelearnerMove three after MoveDeleterForgetMove, its task by shape.
 * Retail FireRed / LeafGreen / Emerald / Ruby / Sapphire in every language, and the hacks on that code (each special on its
 * own: CFRU rewrote ChangePokemonNickname). Played headless on FireRed rev 1 and Emerald.
 */
object PokemonCenter {
    /** Where the scripts go: EWRAM no build symbol reaches, before FieldItems' scratch word and PC anywhere's script. */
    const val SCRIPT = 0x0203FFD8L

    /** What [find] found in a ROM. A special is its index in gSpecials (what `special` takes) and its function. */
    class Found(
        val specials: Long,
        val heal: Int,
        val nickname: Int?,
        val forget: Int?,
        val relearn: Relearn?,
        val otIdNotPlayers: Long?,
        /** FireRed's LoadStdWindowFrameGfx (the field's message box frame and palettes), run before RELEARN's screen. */
        val windowFrame: Long?,
        val var8004: Long,
        val varResult: Long,
        /** MUS_HEAL and MUS_MOVE_DELETED: FireRed's 256 / 270, Emerald's (and Ruby / Sapphire's) 368 / 378. */
        val healSong: Int,
        val forgetSong: Int,
    ) {
        val var8005 get() = var8004 + 2
    }

    /** TeachMoveRelearnerMove's task's two writes: the screen's init and the field's way back into the script. */
    class Relearn(val index: Int, val init: Long, val fieldCallback: Long, val continueScript: Long)

    private const val ANY = -1

    private val HEAL = sig("f0b557464e464546e0b481b0002080462a480078804549d229498a466e466420444644435444201c")
    private val NICKNAME = sig(
        "70b556464d46444670b482b02648814600886421884641464143081c234e8019234a0221????????4946088841464143081c80191f498a4602215246????????",
    )
    private val FORGET = sig("70b5464640b416488046008864267043144d4019144c22780021????????41460888704340192178????????2488022c10d80b4801886420")
    private val RELEARN = sig("00b581b0????????07480a21????????012040420021009100221023????????")
    private val RELEARN_TASK = sig("10b50006040e0948c17980200840002808d10748????????064907480860201c????????10bc01bc")
    /** FireRed's LoadStdWindowFrameGfx: its quest log check, then the message box's and the window's frames. */
    private val WINDOW_FRAME = sig(
        "00b50748007802280ed1064a1078042108431070802189000020????????0be0????????????????????????802189000020f022????????852189000020e022????????",
    )
    private val OT_ID = sig("10b5????????041c07480188642048430649401801210022????????844209d103490020")

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
        if (rom.limit() < 0x1000) return null
        val code = asciiString(ByteArray(4) { rom.get(0xAC + it) })
        val fireRedSongs = code.startsWith("BPR") || code.startsWith("BPG") || code.startsWith("PUV")
        // gSpecials: HealPlayerParty first, then a long run of Thumb ROM pointers.
        val tables = ArrayList<Pair<Int, Int>>()
        var o = 0
        while (o + 4 <= rom.limit()) {
            val w = word(rom, o)
            if (isThumbRom(w) && matches(rom, fn(w), HEAL)) {
                var n = 0
                while (n < 0x300 && o + 4 * n + 4 <= rom.limit() && isThumbRom(word(rom, o + 4 * n))) n++
                if (n >= 200) tables += o to n
            }
            o += 4
        }
        val (t, n) = tables.singleOrNull() ?: return null
        val entries = (0 until n).map { word(rom, t + 4 * it) }
        fun only(s: IntArray) = entries.indices.filter { matches(rom, fn(entries[it]), s) }.singleOrNull()
        val forget = only(FORGET) ?: return null
        val f = fn(entries[forget])
        val var8004 = literal(rom, f + 0x06)
        val var8005 = literal(rom, f + 0x14)
        if (var8005 != var8004 + 2) return null
        val nickname = only(NICKNAME)?.takeIf { literal(rom, fn(entries[it]) + 0x0C) == var8004 }
        val otId = only(OT_ID)?.let { i -> fn(entries[i]).takeIf { literal(rom, it + 0x08) == var8004 } }
        val varResult = otId?.let { literal(rom, it + 0x20) } ?: (var8004 + 0x10)
        val relearn = (forget + 3).takeIf { it < n && matches(rom, fn(entries[it]), RELEARN) }?.let { i ->
            val task = literal(rom, fn(entries[i]) + 0x08)
            if (!isThumbRom(task) || !matches(rom, fn(task), RELEARN_TASK)) return@let null
            val tf = fn(task)
            val init = literal(rom, tf + 0x12)
            val cb = literal(rom, tf + 0x18)
            val cont = literal(rom, tf + 0x1A)
            if (!isThumbRom(init) || !isThumbRom(cont) || cb !in 0x03000000L..0x03007FFFL) return@let null
            Relearn(i, init, cb, cont)
        }
        // The FireRed engine's recap leaves its palette where the relearner's words look for the field's: no screen without it.
        val windowFrame = if (fireRedSongs) scan(rom, WINDOW_FRAME).singleOrNull() else null
        val relearnHere = relearn?.takeIf { !fireRedSongs || windowFrame != null }
        return Found(
            0x08000000L + t, 0, nickname, forget, relearnHere, otId?.let { 0x08000000L + it },
            windowFrame?.let { 0x08000000L + it }, var8004, varResult,
            if (fireRedSongs) 256 else 368, if (fireRedSongs) 270 else 378,
        )
    }

    /** Every ROM offset (2-byte steps) [s] matches at. */
    private fun scan(rom: LittleEndianReader, s: IntArray): List<Int> {
        val out = ArrayList<Int>()
        val b0 = s[0].toByte()
        val b1 = s[1].toByte()
        var o = 0
        while (o + s.size <= rom.limit()) {
            if (rom.get(o) == b0 && rom.get(o + 1) == b1 && matches(rom, o, s)) out += o
            o += 2
        }
        return out
    }

    /** Whether [s] matches at ROM offset [at]. */
    private fun matches(rom: LittleEndianReader, at: Int, s: IntArray): Boolean {
        if (at < 0 || at + s.size > rom.limit()) return false
        for (i in s.indices) if (s[i] != ANY && (rom.get(at + i).toInt() and 0xFF) != s[i]) return false
        return true
    }

    private fun isThumbRom(w: Long) = w and 1L == 1L && w in 0x08000001L..0x09FFFFFFL
    private fun fn(w: Long) = ((w and 1L.inv()) - 0x08000000L).toInt()
    private fun word(rom: LittleEndianReader, at: Int): Long = rom.getInt(at).toLong() and 0xFFFFFFFFL

    /** The word an `ldr rN, [pc, #imm]` at ROM offset [at] loads. */
    private fun literal(rom: LittleEndianReader, at: Int): Long {
        val ins = rom.getShort(at).toInt() and 0xFFFF
        return word(rom, ((at + 4) and 3.inv()) + ((ins and 0xFF) shl 2))
    }

    private fun sig(hex: String) = IntArray(hex.length / 2) { i ->
        val p = hex.substring(i * 2, i * 2 + 2)
        if (p == "??") ANY else p.toInt(16)
    }
}

enum class CenterResult {
    /** Done (HEAL, FORGET), or the game's screen is on its way (NICKNAME, RELEARN). */
    OK,
    NOT_NOW, IN_BATTLE,
    /** An egg: none of these is for one. */
    EGG,
    /** NICKNAME: the Pokémon came from a trade (the Name Rater's rule). */
    TRADED,
    /** FORGET: its only move. */
    LAST_MOVE,
    /** FORGET: the party's last Surf. */
    LAST_SURFER,
    /** The party isn't what the summary showed any more. */
    PARTY_CHANGED,
    FAILED,
}

private const val MON_PERSONALITY = 0
private const val MON_MOVE1 = 13
private const val MON_IS_EGG = 45
private const val MOVE_SURF = 57L

private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
private fun le32(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }

/** The player's free field, the PC's script function known: null, or why not. */
private fun centerGate(fns: FieldItemFns, busy: Boolean, g: GameCalls): CenterResult? = when {
    fns.pc == null || !fieldItemCodeMatches(fns, g::read) || !pcCodeMatches(fns.pc, g::read) -> CenterResult.FAILED
    busy || g.call(fns.fieldControlsLocked) != 0L -> CenterResult.NOT_NOW
    else -> null
}

/** Party slot [slot]'s address if it still holds [personality] (not an egg); else why not. */
private fun centerMon(fns: FieldItemFns, slot: Int, personality: Long, g: GameCalls): Pair<Int?, CenterResult?> {
    val p = fns.party ?: return null to CenterResult.FAILED
    val count = (g.read(p.partyCount, 1)[0].toInt() and 0xFF).coerceAtMost(6)
    if (slot !in 0 until count) return null to CenterResult.PARTY_CHANGED
    val mon = (p.party + slot * p.monStride).toInt()
    if (g.call(p.getMonData, mon, MON_PERSONALITY) != personality) return null to CenterResult.PARTY_CHANGED
    if (g.call(p.getMonData, mon, MON_IS_EGG) != 0L) return null to CenterResult.EGG
    return mon to null
}

/** Runs [script] (its last bytes `releaseall; end`) on the field now. */
private fun runScript(fns: FieldItemFns, script: ByteArray, g: GameCalls): CenterResult {
    if (!g.write(PokemonCenter.SCRIPT, script)) return CenterResult.FAILED
    g.call(fns.pc!!.setupScript, PokemonCenter.SCRIPT.toInt())
    return CenterResult.OK
}

/** HEAL: the nurse's special and her jingle. Emu thread. */
fun centerHeal(fns: FieldItemFns, c: PokemonCenter.Found, busy: Boolean, g: GameCalls): CenterResult = runCatching {
    centerGate(fns, busy, g)?.let { return it }
    // lockall; special HealPlayerParty; playfanfare MUS_HEAL; waitfanfare; releaseall; end
    runScript(fns, byteArrayOf(0x69, 0x25) + le16(c.heal) + byteArrayOf(0x31) + le16(c.healSong) + byteArrayOf(0x32, 0x6B, 0x02), g)
}.getOrElse { CenterResult.FAILED }

/** NICKNAME: the game's naming screen for slot [slot]. Emu thread. */
fun centerNickname(fns: FieldItemFns, c: PokemonCenter.Found, slot: Int, personality: Long, busy: Boolean, g: GameCalls): CenterResult = runCatching {
    val special = c.nickname ?: return CenterResult.FAILED
    centerGate(fns, busy, g)?.let { return it }
    centerMon(fns, slot, personality, g).second?.let { return it }
    c.otIdNotPlayers?.let { fn ->
        g.write(c.var8004, le16(slot))
        g.call(fn)
        if ((g.read(c.varResult, 1)[0].toInt() and 0xFF) != 0) return CenterResult.TRADED
    }
    // lockall; setvar 0x8004, slot; fadescreen FADE_TO_BLACK; special ChangePokemonNickname; waitstate; releaseall; end
    runScript(fns, byteArrayOf(0x69, 0x16, 0x04, 0x80.toByte()) + le16(slot) + byteArrayOf(0x97.toByte(), 0x01, 0x25) + le16(special) +
        byteArrayOf(0x27, 0x6B, 0x02), g)
}.getOrElse { CenterResult.FAILED }

/** FORGET: move [moveIndex] (0-3) of slot [slot], which must still be [move]. Emu thread. */
fun centerForget(
    fns: FieldItemFns, c: PokemonCenter.Found, slot: Int, personality: Long, moveIndex: Int, move: Int, busy: Boolean, g: GameCalls,
): CenterResult = runCatching {
    val special = c.forget ?: return CenterResult.FAILED
    val p = fns.party ?: return CenterResult.FAILED
    centerGate(fns, busy, g)?.let { return it }
    val (mon, why) = centerMon(fns, slot, personality, g)
    why?.let { return it }
    val moves = (0 until 4).map { g.call(p.getMonData, mon!!, MON_MOVE1 + it) }
    if (moveIndex !in 0 until 4 || moves[moveIndex] != move.toLong() || move == 0) return CenterResult.PARTY_CHANGED
    if (moves.count { it != 0L } <= 1) return CenterResult.LAST_MOVE
    if (move.toLong() == MOVE_SURF && !HmFree.active) {
        val count = (g.read(p.partyCount, 1)[0].toInt() and 0xFF).coerceAtMost(6)
        val others = (0 until count).filter { it != slot }.any { s ->
            val m = (p.party + s * p.monStride).toInt()
            g.call(p.getMonData, m, MON_IS_EGG) == 0L && (0 until 4).any { g.call(p.getMonData, m, MON_MOVE1 + it) == MOVE_SURF }
        }
        if (!others) return CenterResult.LAST_SURFER
    }
    // lockall; setvar 0x8004, slot; setvar 0x8005, move; special MoveDeleterForgetMove; playfanfare MUS_MOVE_DELETED;
    // waitfanfare; releaseall; end
    runScript(fns, byteArrayOf(0x69, 0x16, 0x04, 0x80.toByte()) + le16(slot) + byteArrayOf(0x16, 0x05, 0x80.toByte()) + le16(moveIndex) +
        byteArrayOf(0x25) + le16(special) + byteArrayOf(0x31) + le16(c.forgetSong) + byteArrayOf(0x32, 0x6B, 0x02), g)
}.getOrElse { CenterResult.FAILED }

/** RELEARN's first step: the checks, then the controls locked and the screen fading to black; [centerRelearnFinish] follows
 * once it's black ([TM_FADE_FRAMES]). Emu thread. */
fun centerRelearnStart(
    fns: FieldItemFns, c: PokemonCenter.Found, tm: TmFns, slot: Int, personality: Long, busy: Boolean, g: GameCalls,
): CenterResult = runCatching {
    if (c.relearn == null || !tmCodeMatches(tm, g::read)) return CenterResult.FAILED
    centerGate(fns, busy, g)?.let { return it }
    centerMon(fns, slot, personality, g).second?.let { return it }
    g.call(tm.lockControls)
    g.call(tm.fadeScreen, 1, 0)
    CenterResult.OK
}.getOrElse { CenterResult.FAILED }

/** RELEARN's second step, the screen black: the Move Tutor's screen for slot [slot], back to the field when it closes. If it
 * can't start, the field comes back as it was; [leftField] (something else took the game off the field during the fade):
 * nothing at all, as FLY does. Emu thread. */
fun centerRelearnFinish(
    fns: FieldItemFns, c: PokemonCenter.Found, tm: TmFns, slot: Int, personality: Long, g: GameCalls, leftField: Boolean = false,
): Boolean {
    if (leftField) return false
    val r = c.relearn ?: return false
    val ok = runCatching {
        if (centerMon(fns, slot, personality, g).second != null) return@runCatching false
        if (!g.write(c.var8004, le16(slot))) return@runCatching false
        // The field's way back: FieldCB_ContinueScriptHandleMusic fades in and runs this, which hands the controls back.
        if (runScript(fns, byteArrayOf(0x6B, 0x02), g) != CenterResult.OK) return@runCatching false
        c.windowFrame?.let { g.call(it) }
        g.call(tm.cleanupOverworld)
        g.write(r.fieldCallback, le32(r.continueScript))
        g.call(tm.setMainCallback2, r.init.toInt())
        true
    }.getOrDefault(false)
    if (!ok) runCatching { g.call(tm.fadeScreen, 0, 0); g.call(tm.unlockControls) }
    return ok
}

/** The level-up moves [species] could relearn at [level] that it doesn't [know] (GetMoveRelearnerMoves' rule): what the
 * Move Tutor's list would show. Null where this game's learnsets aren't located. */
fun relearnableMoves(t: PokedexTables, g: GuideTables?, species: Int, level: Int, know: Collection<Int>): List<Int>? =
    DexDetails.levelUp(t, g, species)?.filter { it.level <= level && it.move != 0 && it.move !in know }?.map { it.move }?.distinct()
