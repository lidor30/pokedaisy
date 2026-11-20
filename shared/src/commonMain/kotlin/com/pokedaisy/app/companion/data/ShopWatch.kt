package com.pokedaisy.app.companion.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Whether a shop is open - a Poké Mart's BUY / SELL / QUIT menu, its buy screen, its sell bag - so
 * SMART fast-forward stays fast there (GitHub #41: the clerk's lines are printed inside the buy
 * screen, a menu screen to [FfMenuWatch], so they crawled at 1x).
 *
 * No per-game table: every Gen 3 game, decomp or binary hack, opens its shops from a map script's
 * `pokemart` / `pokemartdecoration` / `pokemartdecoration2` command (0x86-0x88 + the item list's
 * pointer), which stops the script right after itself until the shop closes. So a shop is up while
 * a script context's scriptPtr sits just past one of those. The contexts (struct ScriptContext: the
 * global and the immediate one, IWRAM in every game) are found by their shape ([find]) once per ROM.
 * Checked headless on retail Emerald and FireRed (`fixtures/ff_shop`): the menu, the buy screen and
 * the sell bag all point past the clerk's `pokemart`, and the pointer is 0 once the clerk is done.
 */
object ShopWatch {
    private val lock = SynchronizedObject()
    private var contexts = LongArray(0)
    private var retryIn = 0

    /** A ROM was loaded: find its script contexts again. */
    fun load() {
        synchronized(lock) {
            contexts = LongArray(0)
            retryIn = 0
        }
    }

    /** Emu thread: true while a shop is open. Asked only while a menu screen is up. */
    fun isOpen(r: MemoryReader): Boolean {
        synchronized(lock) {
            if (contexts.isEmpty()) {
                // Not set up yet (booting) or not where this game keeps them: look again now and then.
                if (retryIn-- > 0) return false
                retryIn = RETRY_FRAMES
                contexts = runCatching { find(r.readCoreMemory(IWRAM, IWRAM_SIZE)) }.getOrDefault(LongArray(0))
            }
            return contexts.any { ctx -> runCatching { pastShopCommand(r, ctx) }.getOrDefault(false) }
        }
    }

    /** The scriptPtr of the context at [ctx] (+8) is just past a shop command and its ROM pointer. */
    private fun pastShopCommand(r: MemoryReader, ctx: Long): Boolean {
        val p = Gfx.u32(r.readCoreMemory(ctx + 8, 4), 0)
        if (!Gfx.inRom(p) && p !in EWRAM) return false
        val cmd = r.readCoreMemory(p - 5, 5)
        return (cmd[0].toInt() and 0xFF) in SHOP_COMMANDS && Gfx.inRom(Gfx.u32(cmd, 1))
    }

    /**
     * The script contexts in an IWRAM snapshot: a word-aligned ROM command table and its end at
     * +0x5C / +0x60 (0x80-0x400 commands), a stack depth of 20 at most, a mode of 0-2 (stopped /
     * bytecode / native). Anything else that happens to fit has a scriptPtr that won't sit past a
     * shop command.
     */
    fun find(iwram: ByteArray): LongArray {
        val out = ArrayList<Long>()
        var o = 0
        while (o + CONTEXT_SIZE <= iwram.size) {
            val start = Gfx.u32(iwram, o + 0x5C)
            val end = Gfx.u32(iwram, o + 0x60)
            val commands = (end - start) / 4
            if (Gfx.inRom(start) && Gfx.inRom(end) && start % 4 == 0L && (end - start) % 4 == 0L &&
                commands in 0x80L..0x400L && (iwram[o].toInt() and 0xFF) <= 20 && (iwram[o + 1].toInt() and 0xFF) <= 2
            ) out += IWRAM + o
            o += 4
        }
        return out.toLongArray()
    }

    private const val IWRAM = 0x03000000L
    private const val IWRAM_SIZE = 0x8000
    private val EWRAM = 0x02000000L until 0x02040000L
    private val SHOP_COMMANDS = 0x86..0x88
    private const val CONTEXT_SIZE = 0x74
    private const val RETRY_FRAMES = 300
}
