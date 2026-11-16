package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile

/**
 * The QUICK MENU's BIKE and ROD: the key items the games let the player register to SELECT, used the way SELECT uses
 * them - the item written into SaveBlock1.registeredItem, SELECT pressed for the game, the player's own registered item
 * put back. The game then does everything itself (mounting, the "can't use that here" line, the fishing cast), so no
 * per-game function table is needed, only where the register is: right after money (u32) and coins (u16) in every
 * retail layout (pokefirered 0x296, pokeemerald / pokeruby 0x496, = [NativeConfig.moneyOff] + 6).
 */
object KeyItems {
    /** FireRed / LeafGreen's BICYCLE; Ruby / Sapphire / Emerald's MACH and ACRO BIKE (the ids are shared); SoulGold's own. */
    val BIKES: List<Int> get() = if (selectWheel) listOf(709, 710) else listOf(360, 259, 272)

    /** SUPER, GOOD, OLD ROD: the best first. */
    val RODS: List<Int> get() = if (selectWheel) listOf(713, 712, 711) else listOf(264, 263, 262)

    /**
     * SoulGold's SELECT opens a wheel of four registered key items (GitHub #242) - SaveBlock1 +0x3484: up, right, down,
     * left (an empty up shows the Pokégear) - and a direction uses one; the single register after the coins only keeps the
     * last item registered. So there the item goes into the wheel's DOWN slot and DOWN follows SELECT ([WHEEL_DOWN_AFTER]
     * frames later: the wheel takes it from its 6th frame, headless on v1.2 with a player's save).
     */
    val selectWheel: Boolean get() = activeGame == GameKind.SOULGOLD
    const val WHEEL_DOWN_SLOT = 0x3488L
    const val WHEEL_DOWN_AFTER = 8

    /** The money offsets of the layouts the register is known to follow (FireRed's, Emerald's / Ruby's). */
    private val RETAIL_MONEY_OFFS = setOf(0x290L, 0x490L)

    /** The first of [ids] the bag holds, or null. */
    fun firstIn(items: List<ItemView>, ids: List<Int>): Int? = ids.firstOrNull { id -> items.any { it.itemId == id && it.quantity > 0 } }

    /** SaveBlock1.registeredItem's address for [cfg]; null where its layout isn't a retail one. Emu thread. */
    fun registerAddr(cfg: NativeConfig, read: (addr: Long, len: Int) -> ByteArray): Long? {
        if (cfg.moneyOff !in RETAIL_MONEY_OFFS && !selectWheel) return null
        val sb1 = if (cfg.staticSaveBlocks) cfg.saveBlock1Ptr else u32le(read(cfg.saveBlock1Ptr, 4), 0)
        if (sb1 !in 0x02000000L until 0x02040000L) return null
        return sb1 + if (selectWheel) WHEEL_DOWN_SLOT else cfg.moneyOff + 6
    }
}

/** The running GBA game's addresses (the Poller's), for the few uses outside a sample (the QUICK MENU's BIKE / ROD);
 * null when none (Game Boy, an unrecognised ROM). */
@Volatile
var activeNativeConfig: NativeConfig? = null
