package com.pokedaisy.app.companion.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * LAST BALL (the BATTLE tab's ball button): a Poké Ball thrown in one tap, without the bag. In Gen 3 the player's battle
 * controller picks BAG, the engine asks for an item (PlayerHandleChooseItem: a fade to black, then
 * `OpenBagAndChooseItem`, which opens the bag and leaves `CompleteWhenChoseItem` waiting), and the bag hands
 * `gSpecialVar_ItemId` back. The throw catches the controller at `OpenBagAndChooseItem`, before the bag opens: the ball
 * goes into `gSpecialVar_ItemId`, the controller is put on `CompleteWhenChoseItem` and the fade is turned round
 * (`BeginNormalPaletteFade(all, 0, 0, 0)`: a fade to nothing, the palettes as they were). The game then throws the ball
 * itself, with its own animation, text and catch.
 *
 * The bag checks the game makes before a throw ([canThrow]) are the companion's then: a wild battle, one foe, room for the
 * catch (a party under six or a box with a free slot), a ball in the bag.
 *
 * [find] reads the functions from the ROM, from the config's `completeWhenChoseItem` / `battlerControllerFuncs`: agbcc's
 * shape in FireRed / LeafGreen / Emerald (OpenBagAndChooseItem right before CompleteWhenChoseItem, PlayerHandleChooseItem the
 * one function holding it in its pool, its first call BeginNormalPaletteFade); Ruby / Sapphire's the same, OpenBagAndChooseItem
 * 4 bytes shorter. Played headless on FireRed rev 1, Emerald, Japanese Emerald, German FireRed and Ruby rev 1.
 */
object BattleBall {
    class Fns(
        val controllerFuncs: Long,
        val openBag: Long,
        val completeWhenChose: Long,
        val itemIdVar: Long,
        val paletteFade: Long,
        val beginNormalPaletteFade: Long,
    )

    private const val ROM_BASE = 0x08000000L
    /** OpenBagAndChooseItem's distance before CompleteWhenChoseItem in agbcc's builds: FireRed / LeafGreen / Emerald's, then
     * Ruby / Sapphire's (its function 4 bytes shorter). */
    private val OPEN_BAG_BEFORE = listOf(0x3C, 0x38)

    private val cache = HashMap<String, Fns?>()
    private val lock = SynchronizedObject()

    /** The functions in [rom] for a config with [completeWhenChose] / [controllerFuncs], or null. Reads the file (a window
     * at a time): off the UI thread; cached per file. */
    fun find(rom: Path, completeWhenChose: Long, controllerFuncs: Long): Fns? {
        if (completeWhenChose == 0L || controllerFuncs == 0L) return null
        val key = fileKey(rom) + ":" + completeWhenChose.toString(16)
        synchronized(lock) { if (key in cache) return cache[key] }
        val found = runCatching {
            withRomFile(rom) { find(FileLittleEndian(it), completeWhenChose, controllerFuncs) }
        }.getOrNull()
        synchronized(lock) { cache[key] = found }
        return found
    }

    fun find(rom: LittleEndianReader, completeWhenChose: Long, controllerFuncs: Long): Fns? {
        val c = ((completeWhenChose and 0xFFFFFFFEL) - ROM_BASE).toInt()
        if (c < 0x3C || c + 0x40 > rom.limit()) return null
        // CompleteWhenChoseItem: push {lr}; ldr gMain; ldr r1,[r0,#4]; ldr BattleMainCB2; cmp; bne; ldr gPaletteFade; ...
        // ldr gSpecialVar_ItemId; ldrh r1,[r0].
        if (half(rom, c) != 0xB500 || half(rom, c + 0x1A) != 0x8801) return null
        val paletteFade = literal(rom, c + 0x0C) ?: return null
        val itemId = literal(rom, c + 0x18) ?: return null
        if (!ewram(paletteFade) || !ewram(itemId)) return null
        // OpenBagAndChooseItem: push {lr}; ldr gPaletteFade; ... ldr gBattlerControllerFuncs; ... ldr CompleteWhenChoseItem|1.
        // 0x3C bytes before CompleteWhenChoseItem in FireRed / LeafGreen / Emerald, 0x38 in Ruby / Sapphire.
        val o = OPEN_BAG_BEFORE.map { c - it }.firstOrNull { o ->
            half(rom, o) == 0xB500 && literal(rom, o + 2) == paletteFade && literal(rom, o + 0x0E) == (controllerFuncs and 0xFFFFFFFFL) &&
                literal(rom, o + 0x18) == ROM_BASE + c + 1
        } ?: return null
        // PlayerHandleChooseItem: the one pool word OpenBagAndChooseItem|1; the function's first call is the fade.
        val want = (ROM_BASE + o + 1).toInt()
        var at = -1
        var i = 0
        while (i + 4 <= rom.limit()) {
            if (rom.getInt(i) == want) { if (at >= 0) return null; at = i }
            i += 4
        }
        if (at < 0) return null
        val start = (at - 2 downTo maxOf(0, at - 0x60) step 2).firstOrNull { half(rom, it) == 0xB530 } ?: return null
        // movs r0,#1; rsbs r0,r0,#0; movs r1,#0; str r1,[sp]; movs r2,#0; movs r3,#0x10; bl BeginNormalPaletteFade
        val call = start + 0x10
        if (half(rom, start + 4) != 0x2001 || half(rom, start + 0x0E) != 0x2310) return null
        val fade = blTarget(rom, call) ?: return null
        return Fns(controllerFuncs and 0xFFFFFFFFL, ROM_BASE + o, ROM_BASE + c, itemId, paletteFade, ROM_BASE + fade)
    }

    /** The bag's balls, in its order (the POKé BALLS pocket, any held). */
    fun balls(items: List<ItemView>): List<ItemView> = items.filter { it.pocket == POCKET_POKE_BALLS && it.quantity > 0 }

    /** The ball the button throws: [last] if the bag has it, else the bag's first ball; null = none. */
    fun pick(items: List<ItemView>, last: Int?): ItemView? = balls(items).let { b -> b.firstOrNull { it.itemId == last } ?: b.firstOrNull() }

    /** The ball a battle's sample shows thrown: one whose count dropped since [before] (null = none). */
    fun thrown(before: List<ItemView>, now: List<ItemView>): Int? {
        val had = balls(before).associate { it.itemId to it.quantity }
        val has = balls(now).associate { it.itemId to it.quantity }
        return had.entries.firstOrNull { (id, n) -> (has[id] ?: 0) < n }?.key
    }

    /** Whether the game would let [snap]'s player throw a ball now (the bag's own checks): a wild battle with one foe and
     * room for the catch. Unknown boxes with a full party = no. */
    fun canThrow(snap: SnapshotView): Boolean {
        if (!snap.inBattle || !ShinyHunt.wild(snap) || snap.battleOpponent.size != 1) return false
        if (snap.party.size < PARTY_SIZE) return true
        val boxes = snap.pcBoxes ?: return false
        return boxes.boxes.any { b -> b.mons.size < BOX_SLOTS || b.mons.any { it == null } }
    }

    private const val BOX_SLOTS = 30

    private fun ewram(a: Long) = a in 0x02000000L until 0x02040000L

    private fun half(rom: LittleEndianReader, at: Int) = rom.getShort(at).toInt() and 0xFFFF

    /** The word an `ldr rX, [pc, #n]` at [at] loads; null if it isn't one. */
    private fun literal(rom: LittleEndianReader, at: Int): Long? {
        val ins = half(rom, at)
        if (ins and 0xF800 != 0x4800) return null
        val addr = ((at + 4) and 3.inv()) + ((ins and 0xFF) shl 2)
        return rom.getInt(addr).toLong() and 0xFFFFFFFFL
    }

    private fun blTarget(rom: LittleEndianReader, at: Int): Int? {
        val h1 = half(rom, at)
        val h2 = half(rom, at + 2)
        if (h1 and 0xF800 != 0xF000 || h2 and 0xF800 != 0xF800) return null
        var hi = h1 and 0x7FF
        if (hi and 0x400 != 0) hi -= 0x800
        return at + 4 + (hi shl 12) + ((h2 and 0x7FF) shl 1)
    }
}
