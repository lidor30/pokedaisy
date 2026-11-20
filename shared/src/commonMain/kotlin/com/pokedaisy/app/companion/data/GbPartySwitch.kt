package com.pokedaisy.app.companion.data

/**
 * The battle party menu of a Game Boy game (Yellow, Crystal and its hacks), as the battle POKéMON switch
 * (BattleInputController.switchTo) reads it: the menu waiting for a press and where its cursor is, the party, and
 * which slot is out. Gen 1 / Gen 2 Pokémon have no personality value, so a party slot is told by [gbMonKey] (species,
 * OT ID and DVs - what a party read's [Mon.personality] carries for these games): the switch looks its target up by
 * that key every step, so a party that changed under it is refused rather than a wrong slot sent in.
 *
 * Neither game reorders the party in battle (unlike Gen 3's battle order): the menu lists the party as it is, slot 0
 * first, and the one out is a slot number (Yellow's wPlayerMonNumber, Crystal's wCurBattleMon).
 *
 * Both menus were read off pret/pokeyellow and pret/pokecrystal and checked headless (yellow / crystal battles):
 * - Yellow: POKéMON opens DisplayPartyMenu, HandleMenuInput with wTopMenuItemY 1, X 0, wCurrentMenuItem = the slot,
 *   wMaxMenuItem = count - 1, the ▶ two rows a slot, wrapping, no CANCEL row; A on a slot opens SWITCH / STATS /
 *   CANCEL (wTopMenuItemY / X 12, item 0 first every time). After a faint (wPartyMenuTypeOrMessageID
 *   BATTLE_PARTY_MENU) A on a slot sends it out at once.
 * - Crystal: a _2DMenu from (1, 0), a row per Pokémon plus CANCEL, two rows apart, wMenuCursorY 1-based; A opens
 *   BattleMonMenu's SWITCH / STATS / CANCEL, a 2D menu from (12, 12) (Kristall: 10, 12), SWITCH first. After a faint
 *   (wPartyMenuActionText PARTYMENUACTION_SWITCH) A sends the slot out; CANCEL only buzzes.
 * Either menu only counts as waiting while its cursor cell holds the filled ▶: a pick turns it hollow at once.
 */
interface GbPartySwitch {
    /** The party menu or its SWITCH / STATS / CANCEL menu, while one waits for a press; null otherwise. */
    fun menu(r: MemoryReader): GbPartyMenu?

    /** The party in its own order: each Pokémon's [gbMonKey] and HP. */
    fun party(r: MemoryReader): List<GbPartyMon>

    /** The party slot of the Pokémon in battle. */
    fun outSlot(r: MemoryReader): Int

    /** The list ends in a CANCEL row ([GB_PARTY_CANCEL]), which UP / DOWN wrap through (Crystal); else the slots alone wrap (Yellow). */
    val hasCancel: Boolean

    /** The battle menu waiting for a press and its cursor, as the battle-input poll reads it ([readGen1BattleInput] /
     * [readGen2BattleInput]): the controls' FIGHT / BAG / RUN / move picks steer on it, read fresh every frame. */
    fun battleMenu(r: MemoryReader): Pair<Int, Int>
}

/**
 * A waiting battle party menu: the list ([submenu] false: [cursor] the slot, or [GB_PARTY_CANCEL] on Crystal's CANCEL)
 * or the SWITCH / STATS / CANCEL menu over it ([submenu]: [cursor] 0 SWITCH, 1 STATS, 2 CANCEL). [forced]: the pick
 * after a faint - A on a slot sends it out, no SWITCH menu follows.
 */
data class GbPartyMenu(val submenu: Boolean, val cursor: Int, val forced: Boolean = false) {
    /** What the battle-input poll reports as its cursor: the slot, or [GB_SUBMENU_CURSOR] + the item. */
    val pollCursor: Int get() = if (submenu) GB_SUBMENU_CURSOR + cursor else cursor
}

data class GbPartyMon(val key: Long, val hp: Int)

/** The party list's cursor on Crystal's CANCEL row (Gen 3's gPartyMenu.slotId for CANCEL too). */
const val GB_PARTY_CANCEL = 7

/** [GbPartyMenu.pollCursor] of the SWITCH / STATS / CANCEL menu's first item. */
const val GB_SUBMENU_CURSOR = 0x10

/**
 * A Gen 1 / Gen 2 party_struct's identity, for the switch (no personality value in these games): its species byte,
 * OT ID and DVs - none of which change in a battle (level, HP and experience do). Never 0 for a real Pokémon.
 */
fun gbMonKey(species: Int, otId: Int, dvs: Int): Long =
    ((species.toLong() and 0xFF) shl 32) or ((otId.toLong() and 0xFFFF) shl 16) or (dvs.toLong() and 0xFFFF)

private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
private fun be16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)

/** Yellow's party_struct (44 bytes): species +0, HP +1, OT ID +12, DVs +27. */
fun gen1MonKey(raw: ByteArray, off: Int): Long = gbMonKey(u8(raw, off), be16(raw, off + 12), be16(raw, off + 27))

/** Crystal's party_struct (48 bytes): species +0, OT ID +6, DVs +21, HP +34. */
fun gen2MonKey(raw: ByteArray, off: Int): Long = gbMonKey(u8(raw, off), be16(raw, off + 6), be16(raw, off + 21))

private const val TILE_CURSOR = 0xED   // ▶, a menu waiting for input
private const val GEN1_NORMAL_PARTY_MENU = 0
private const val GEN1_SUBMENU_TOP = 0x0C      // SWITCH / STATS / CANCEL: wTopMenuItemY = wTopMenuItemX = $c
private const val GEN2_CHOOSE_POKEMON = 0      // PARTYMENUACTION_CHOOSE_POKEMON (BattleMenu_PKMN)
private const val GEN2_SWITCH = 2              // PARTYMENUACTION_SWITCH (the pick after a faint)
private const val GEN2_SUBMENU_TOP = 0x0C      // BattleMonMenu: from (12, 12)
private const val POLISHED_SUBMENU_LEFT = 0x0B  // Polished Crystal's SWITCH / SUMMARY / CANCEL: from (11, 12)

class Gen1PartySwitch(private val cfg: Gen1Config) : GbPartySwitch {
    override val hasCancel = false

    override fun menu(r: MemoryReader): GbPartyMenu? = readGen1PartyMenu(r, cfg)

    override fun battleMenu(r: MemoryReader): Pair<Int, Int> = readGen1BattleInput(r, cfg)

    override fun party(r: MemoryReader): List<GbPartyMon> {
        val n = u8(r.readCoreMemory(cfg.partyCount, 1), 0).coerceAtMost(PARTY_SIZE)
        val raw = r.readCoreMemory(cfg.partyMons, PARTY_SIZE * GEN1_PARTY_MON_SIZE)
        return (0 until n).map { GbPartyMon(gen1MonKey(raw, it * GEN1_PARTY_MON_SIZE), be16(raw, it * GEN1_PARTY_MON_SIZE + 1)) }
    }

    override fun outSlot(r: MemoryReader): Int = u8(r.readCoreMemory(cfg.playerMonNumber, 1), 0)
}

class Gen2PartySwitch(private val cfg: Gen2Config) : GbPartySwitch {
    override val hasCancel = true

    override fun menu(r: MemoryReader): GbPartyMenu? = readGen2PartyMenu(r, cfg)

    override fun battleMenu(r: MemoryReader): Pair<Int, Int> = readGen2BattleInput(r, cfg)

    override fun party(r: MemoryReader): List<GbPartyMon> {
        val n = u8(r.readCoreMemory(cfg.partyCount, 1), 0).coerceAtMost(PARTY_SIZE)
        val raw = r.readCoreMemory(cfg.partyMons, PARTY_SIZE * GEN2_PARTY_MON_SIZE)
        return (0 until n).map {
            val o = it * GEN2_PARTY_MON_SIZE
            // The key the party read hands the UI as each one's personality (Polished Crystal's: its own struct).
            val key = if (cfg.polished) decodePolishedPartyMon(raw, o, cfg)?.personality ?: 0L else gen2MonKey(raw, o)
            GbPartyMon(key, be16(raw, o + 34))
        }
    }

    override fun outSlot(r: MemoryReader): Int = u8(r.readCoreMemory(cfg.curBattleMon, 1), 0)
}

/** Yellow's battle party menu or its SWITCH / STATS / CANCEL menu, while one waits for a press (in a battle only). */
fun readGen1PartyMenu(r: MemoryReader, cfg: Gen1Config): GbPartyMenu? {
    if (cfg.topMenuItemY == 0L || cfg.partyMenuType == 0L) return null
    val battle = u8(r.readCoreMemory(cfg.isInBattle, 1), 0)
    if (battle != 1 && battle != 2) return null
    val menu = r.readCoreMemory(cfg.topMenuItemY, 5)
    val top = u8(menu, 0)
    val x = u8(menu, 1)
    val item = u8(menu, 2)
    val max = u8(menu, 4)
    fun cursorShown(row: Int) = row in 0 until 18 && x < 20 && u8(r.readCoreMemory(cfg.tileMap + row * 20 + x, 1), 0) == TILE_CURSOR
    if (top == GEN1_SUBMENU_TOP && x == GEN1_SUBMENU_TOP && max == 2 && item <= 2 && cursorShown(top + 2 * item)) {
        return GbPartyMenu(submenu = true, cursor = item)
    }
    if (top != 1 || x != 0 || item > max) return null
    val count = u8(r.readCoreMemory(cfg.partyCount, 1), 0)
    val type = u8(r.readCoreMemory(cfg.partyMenuType, 1), 0)
    if (count !in 1..PARTY_SIZE || max != count - 1) return null
    if (type != GEN1_NORMAL_PARTY_MENU && type != cfg.battlePartyMenu) return null   // e.g. a Potion's "use on which?"
    if (!cursorShown(top + 2 * item)) return null
    return GbPartyMenu(submenu = false, cursor = item, forced = type == cfg.battlePartyMenu)
}

/** Crystal's battle party menu or its SWITCH / STATS / CANCEL menu, while one waits for a press (in a battle only). */
fun readGen2PartyMenu(r: MemoryReader, cfg: Gen2Config): GbPartyMenu? {
    val battle = u8(r.readCoreMemory(cfg.battleMode, 1), 0)
    if (battle != 1 && battle != 2) return null
    val m = r.readCoreMemory(cfg.menu2D, 7)
    val top = u8(m, 0)
    val left = u8(m, 1)
    val rows = u8(m, 2)
    val cols = u8(m, 3)
    val spacing = u8(m, 6)
    val y = u8(r.readCoreMemory(cfg.menuCursor, 1), 0)
    if (cols != 1 || spacing != 0x20 || y !in 1..rows) return null
    val ty = top + 2 * (y - 1)
    if (ty >= 18 || left >= 20 || u8(r.readCoreMemory(cfg.tileMap + ty * 20 + left, 1), 0) != cfg.cursorTile) return null
    if (top == GEN2_SUBMENU_TOP && left == (if (cfg.polished) POLISHED_SUBMENU_LEFT else cfg.switchMenuLeft) && rows == 3) return GbPartyMenu(submenu = true, cursor = y - 1)
    if (top != 1 || left != 0) return null
    val count = u8(r.readCoreMemory(cfg.partyCount, 1), 0)
    val action = u8(r.readCoreMemory(cfg.partyMenuAction, 1), 0)
    if (count !in 1..PARTY_SIZE || rows != count + 1) return null
    if (action != GEN2_CHOOSE_POKEMON && action != GEN2_SWITCH) return null   // e.g. a Potion's "use on which?"
    return GbPartyMenu(submenu = false, cursor = if (y == rows) GB_PARTY_CANCEL else y - 1, forced = action == GEN2_SWITCH)
}
