package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Whether the game has a menu screen up - party, bag, summary, PC, Pokédex,
 * options... - so SMART fast-forward ([com.pokedaisy.app.companion.FfMode])
 * can drop to 1x there, in battle too (its bag and party screens).
 *
 * Every Gen 3 game, decomp or binary hack, runs the field and battles each on
 * one fixed `gMain.callback2` (CB2_Overworld, BattleMainCB2), while each of
 * those screens switches to its own - even when opened from a battle
 * (checked headless on Emerald Rogue: field, party, bag, battle all differ;
 * the START menu and dialogue are overlays that keep the field's). So no
 * per-game menu table is needed, only two addresses per ROM, which are
 * learned, not looked up:
 *  - `gMain` itself: the detected game's own address when it has a native
 *    config ([useKnownGMain]); otherwise the IWRAM word pair at +0x20/+0x24
 *    (vblankCounter1/2) that advances exactly with the frames between two
 *    snapshots, beside ROM pointers at +4 (callback2) and +0xC (the VBlank
 *    callback) ([locate]). Either way it is re-checked every [VERIFY_GAP]
 *    frames: a counter that stops moving means the address is wrong (a task's
 *    frame counter once passed for FireRed's, and its slot's function pointer
 *    then read as "a menu" on the field and as "the field" in real menus), so
 *    it's dropped with whatever was learned through it and found again.
 *  - the field's callback2: the one running whenever the player has moved
 *    ([notePosition]); the battle's: the one running most while `inBattle`.
 * Both are remembered per ROM ([load] / [onLearned]), so a menu is caught
 * from the first frame on the next launch.
 */
object FfMenuWatch {
    private val lock = SynchronizedObject()
    /** Called (emu thread) when the learned field / battle callback2 changes: persist them. */
    @Volatile
    var onLearned: ((field: Long, battle: Long) -> Unit)? = null

    private var gMain = -1L
    /** The detected game's own gMain, once known ([useKnownGMain]); scanning stops then. */
    private var knownGMain = -1L
    private var inBattleOff = 0x439L
    private var verifyFrame = 0
    private var verifyPrimed = false
    private var verifyC1 = 0L
    private var verifyC2 = 0L
    private var frame = 0
    private var snapshot: ByteArray? = null
    private var scans = 0

    private val fieldVotes = HashMap<Long, Int>()
    private val battleFrames = HashMap<Long, Int>()
    private var field = 0L
    private var battle = 0L
    private var cb2 = 0L
    private var inBattle = false
    private var lastPos: List<Int>? = null

    /** A ROM was loaded ([gameCode] = its header code): forget the last one, start from what was learned before. */
    fun load(gameCode: String, learnedField: Long, learnedBattle: Long) {
        synchronized(lock) {
            gMain = -1L
            knownGMain = -1L
            verifyFrame = 0
            verifyPrimed = false
            // Ruby/Sapphire's Main has 4 more bytes - but not the Japanese ones' (their Main is FireRed's size).
            inBattleOff = if ((gameCode.startsWith("AXV") || gameCode.startsWith("AXP")) && !gameCode.endsWith("J")) 0x43DL else 0x439L
            frame = 0
            snapshot = null
            scans = 0
            fieldVotes.clear()
            battleFrames.clear()
            field = learnedField
            battle = learnedBattle
            if (field != 0L) fieldVotes[field] = SEED_VOTES
            if (battle != 0L) battleFrames[battle] = SEED_FRAMES
            cb2 = 0L
            inBattle = false
            lastPos = null
        }
    }

    /**
     * The detected game's verified gMain (its native config) - used instead of
     * the scan. A different address than the scan found means the scan was
     * wrong, so what was learned through it goes too.
     */
    fun useKnownGMain(addr: Long, inBattleOffset: Long) {
        synchronized(lock) {
            if (addr == knownGMain || addr <= 0L || knownGMain == Long.MIN_VALUE) return
            knownGMain = addr
            inBattleOff = inBattleOffset
            if (gMain >= 0 && gMain != addr) forgetLearned()
            gMain = addr
            verifyFrame = 0
            verifyPrimed = false
        }
    }

    /** gMain.inBattle as of the last [tick] (false until gMain is found). */
    val battling: Boolean
        get() = synchronized(lock) { gMain >= 0 && inBattle }

    /** The field's own callback2 is running (learned and not in a battle): the player's field, no menu screen over it.
     * False until the field has been learned (a few seconds of walking the first time a game runs). */
    val onField: Boolean get() = onFieldNow()

    // (A function: in a property's getter `field` would be its own backing field, not the learned callback.)
    private fun onFieldNow() = synchronized(lock) { gMain >= 0 && field != 0L && !inBattle && cb2 == field }

    /** Emu thread, once a frame: true while a menu screen is up. */
    fun tick(r: MemoryReader): Boolean {
        synchronized(lock) {
            if (gMain < 0) {
                findGMain(r)
                return false
            }
            if (!stillGMain(r)) return false
            val b = runCatching { r.readCoreMemory(gMain + 4, 4) }.getOrNull() ?: return false
            cb2 = Gfx.u32(b, 0)
            inBattle = runCatching { (r.readCoreMemory(gMain + inBattleOff, 1)[0].toInt() shr 1) and 1 == 1 }.getOrDefault(false)
            if (inBattle && Gfx.inRom(cb2)) {
                val n = (battleFrames[cb2] ?: 0) + 1
                battleFrames[cb2] = n
                if (cb2 != battle && n > (battleFrames[battle] ?: 0) && n >= MIN_BATTLE_FRAMES) {
                    battle = cb2
                    learned()
                }
            }
            val home = if (inBattle) battle else field
            return home != 0L && Gfx.inRom(cb2) && cb2 != home
        }
    }

    /** From each snapshot (~1 Hz): the player moving on the field votes for the callback2 running. */
    fun notePosition(x: Int, y: Int, mapGroup: Int, mapNum: Int, battling: Boolean) {
        synchronized(lock) {
            val pos = listOf(x, y, mapGroup, mapNum)
            val moved = lastPos != null && pos != lastPos
            lastPos = pos
            if (!moved || battling || inBattle || !Gfx.inRom(cb2)) return
            val n = (fieldVotes[cb2] ?: 0) + 1
            fieldVotes[cb2] = n
            if (cb2 != field && n > (fieldVotes[field] ?: 0) && n >= MIN_FIELD_VOTES) {
                field = cb2
                learned()
            }
        }
    }

    /**
     * Every [VERIFY_GAP] frames: did either frame counter move? Off while it
     * hasn't been checked yet. Not moving at all for that long means [gMain]
     * isn't the game's (a busy game can drop VBlanks, never every one of them):
     * drop it and everything learned through it, and scan again.
     */
    private fun stillGMain(r: MemoryReader): Boolean {
        if (++verifyFrame < VERIFY_GAP) return true
        verifyFrame = 0
        val b = runCatching { r.readCoreMemory(gMain + 0x20, 8) }.getOrNull() ?: return true
        val c1 = Gfx.u32(b, 0)
        val c2 = Gfx.u32(b, 4)
        val moved = c1 != verifyC1 || c2 != verifyC2
        verifyC1 = c1
        verifyC2 = c2
        if (!verifyPrimed) {
            verifyPrimed = true
            return true
        }
        if (moved) return true
        // A known address that doesn't count frames isn't this game's after all (an
        // unknown hack running on a retail config): scan instead, from now on.
        knownGMain = if (gMain == knownGMain) Long.MIN_VALUE else knownGMain
        gMain = -1L
        verifyPrimed = false
        frame = 0
        scans = 0
        forgetLearned()
        return false
    }

    /** What was learned through a wrong gMain is wrong too: start over (and say so, so it isn't kept). */
    private fun forgetLearned() {
        fieldVotes.clear()
        battleFrames.clear()
        field = 0L
        battle = 0L
        cb2 = 0L
        inBattle = false
        learned()
    }

    private fun learned() {
        val f = field
        val b = battle
        runCatching { onLearned?.invoke(f, b) }
    }

    /** Two IWRAM snapshots [SCAN_GAP] frames apart; gMain is where both frame counters moved exactly that much. */
    private fun findGMain(r: MemoryReader) {
        frame++
        if (frame == SCAN_START) {
            snapshot = runCatching { r.readCoreMemory(IWRAM, IWRAM_SIZE) }.getOrNull()
        } else if (frame == SCAN_START + SCAN_GAP) {
            val a = snapshot ?: return retry()
            val b = runCatching { r.readCoreMemory(IWRAM, IWRAM_SIZE) }.getOrNull() ?: return retry()
            gMain = locate(a, b, SCAN_GAP)
            snapshot = null
            if (gMain < 0) retry()
        }
    }

    private fun retry() {
        snapshot = null
        // Booting (interrupts still off) can hide it: try again a little later, a few times.
        frame = if (++scans < MAX_SCANS) SCAN_START - SCAN_RETRY else Int.MIN_VALUE
    }

    /**
     * gMain's address from IWRAM snapshots [a] then [b], [frames] frames apart,
     * or -1 unless exactly one place fits: callback2 (+4) and the VBlank
     * callback (+0xC) ROM pointers (a task's slot, whose data can count frames
     * too, has its own data there) and vblankCounter1 (+0x20) moved exactly
     * [frames], vblankCounter2 (+0x24) no more. FireRed's vblankCounter1 is a pointer instead (0 on the field), so
     * only when nothing fits that way: counter2 moved exactly [frames] and
     * +0x20 held still. (That second rule alone also fits 4 bytes early on
     * Emerald, hence the order.)
     */
    fun locate(a: ByteArray, b: ByteArray, frames: Int): Long {
        val n = frames.toLong()
        fun find(fits: (Long, Long, Long, Long) -> Boolean): Long {
            var found = -1L
            var o = 0
            while (o + 0x28 <= minOf(a.size, b.size)) {
                if (Gfx.inRom(Gfx.u32(b, o + 4)) && Gfx.inRom(Gfx.u32(b, o + 0xC))) {
                    val c1a = Gfx.u32(a, o + 0x20)
                    val c1b = Gfx.u32(b, o + 0x20)
                    if (fits(c1a, c1b, Gfx.u32(a, o + 0x24), Gfx.u32(b, o + 0x24))) {
                        if (found >= 0) return -2L
                        found = IWRAM + o
                    }
                }
                o += 4
            }
            return found
        }
        val strict = find { c1a, c1b, c2a, c2b -> c1b - c1a == n && c2b - c2a in 0..n }
        if (strict != -1L) return strict.coerceAtLeast(-1L)
        return find { c1a, c1b, c2a, c2b -> c2b - c2a == n && c1a == c1b }.coerceAtLeast(-1L)
    }

    private const val IWRAM = 0x03000000L
    private const val IWRAM_SIZE = 0x8000
    private const val SCAN_START = 120 // ~2 s in: past the boot, VBlank running
    private const val SCAN_GAP = 16
    private const val SCAN_RETRY = 60
    private const val MAX_SCANS = 20
    private const val MIN_FIELD_VOTES = 2
    private const val MIN_BATTLE_FRAMES = 120
    private const val SEED_VOTES = 3
    private const val SEED_FRAMES = 600
    private const val VERIFY_GAP = 300 // ~5 s at 1x
}
