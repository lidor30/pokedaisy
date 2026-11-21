package com.pokedaisy.app.companion.data

import kotlinx.atomicfu.locks.synchronized

/**
 * Crystal's PC boxes, read from the cart's battery RAM (pokecrystal's sram.asm; retail English rev 0, rev 1 and Australia
 * keep them at the same places, the `crystal_boxes` fixture, and German Kristall too, `crystal_de_boxes`):
 * - the box the PC is on (wCurBox) is sBox, SRAM bank 1 at AD10: every deposit and withdrawal writes it at once, so it's
 *   always the game's own;
 * - boxes 1-7 are SRAM bank 2's, 8-14 bank 3's, 0x450 bytes apart from A000. The game copies sBox into its place there
 *   (SaveBox) and loads the next one (LoadBox) only on a box change, which saves the game: the current box's own place is
 *   stale and never read.
 * A box is `box` (macro.asm): a count, 20 species + 0xFF, 20 box_structs (the party_struct's first 32 bytes: no status,
 * HP or stats), 20 OT names and 20 nicknames of 11 bytes. Its name is wBoxNames' (WRAM bank 1, 9 bytes each).
 * Stats are worked out like CalcMonStats from the cart's base stats (the DEX's), the DVs and the stat experience; a box
 * Pokémon has full HP (the game heals it on withdrawal), so HP is its max.
 */
data class Gen2BoxLayout(
    val curBox: Long,       // wCurBox
    val boxNames: Long,     // wBoxNames: NUM_BOXES x BOX_NAME_LENGTH
    val activeBox: Long,    // sBox, as a cartRam address
    val firstBox: Long,     // sBox1
    val boxesPerBank: Int,  // 7: sBox8 opens the next bank
)

/** The cart's battery RAM, bank [bank]'s A000-BFFF, as the companion's readers address it: the native reader
 * (pk_session.c's pk_gb_read_banked) and FixtureMemoryReader take bank (B + 0x10) << 16. */
fun cartRam(bank: Int, addr: Int): Long = ((bank + 0x10).toLong() shl 16) or addr.toLong()

val GEN2_CRYSTAL_BOXES = Gen2BoxLayout(
    curBox = wramx(1, 0xDB72),
    boxNames = wramx(1, 0xDB75),
    activeBox = cartRam(1, 0xAD10),
    firstBox = cartRam(2, 0xA000),
    boxesPerBank = 7,
)

private const val GEN2_NUM_BOXES = 14
private const val GEN2_BOX_SLOTS = 20
private const val GEN2_BOX_SIZE = 0x450
private const val GEN2_BOX_NAME_LEN = 9
private const val GEN2_BOX_MON_SIZE = 32
private const val GEN2_BOX_MONS = 0x16
private const val GEN2_BOX_NICKNAMES = GEN2_BOX_MONS + GEN2_BOX_SLOTS * GEN2_BOX_MON_SIZE + GEN2_BOX_SLOTS * 11
private const val EGG = 0xFD

/** The boxes as [readGen2Boxes] last decoded them, and the bytes they came from: the same bytes, the same object. */
private var lastGen2Raw: ByteArray? = null
private var lastGen2Boxes: PcBoxes? = null

fun readGen2Boxes(r: MemoryReader, cfg: Gen2Config, layout: Gen2BoxLayout): PcBoxes? = runCatching { readGen2BoxesRaw(r, cfg, layout) }.getOrNull()
private fun readGen2BoxesRaw(r: MemoryReader, cfg: Gen2Config, layout: Gen2BoxLayout): PcBoxes? = run {
    val current = (r.readCoreMemory(layout.curBox, 1)[0].toInt() and 0xFF).takeIf { it < GEN2_NUM_BOXES } ?: return null
    val names = r.readCoreMemory(layout.boxNames, GEN2_NUM_BOXES * GEN2_BOX_NAME_LEN)
    val raw = ByteArray(GEN2_NUM_BOXES * GEN2_BOX_SIZE)
    for (b in 0 until GEN2_NUM_BOXES) {
        val at = if (b == current) layout.activeBox else {
            val bank = (layout.firstBox shr 16).toInt() - 0x10 + b / layout.boxesPerBank
            cartRam(bank, (layout.firstBox and 0xFFFF).toInt() + (b % layout.boxesPerBank) * GEN2_BOX_SIZE)
        }
        r.readCoreMemory(at, GEN2_BOX_SIZE).copyInto(raw, b * GEN2_BOX_SIZE)
    }
    val key = byteArrayOf(current.toByte()) + names + raw
    synchronized(pcBoxesLock) { lastGen2Boxes.takeIf { key.contentEquals(lastGen2Raw) } }?.let { return it }
    val chars = Gen2Art.chars(false)
    PcBoxes(
        currentBox = current,
        boxes = List(GEN2_NUM_BOXES) { b ->
            val o = b * GEN2_BOX_SIZE
            // A box the game hasn't set up yet (a new game's, before its first save) holds anything: empty.
            val count = (raw[o].toInt() and 0xFF).takeIf { it <= GEN2_BOX_SLOTS } ?: 0
            PcBox(
                name = buildString {
                    for (i in 0 until GEN2_BOX_NAME_LEN) {
                        val c = names[b * GEN2_BOX_NAME_LEN + i].toInt() and 0xFF
                        if (c == 0x50 || c == 0x00) break
                        chars[c]?.let { append(it) }
                    }
                }.trim().ifEmpty { "BOX${b + 1}" },
                wallpaper = -1,
                mons = List(GEN2_BOX_SLOTS) { s ->
                    if (s >= count) null else decodeGen2BoxMon(raw, o + GEN2_BOX_MONS + s * GEN2_BOX_MON_SIZE, cfg, egg = (raw[o + 1 + s].toInt() and 0xFF) == EGG)
                        ?.copy(nickname = gen2Nickname(raw, o + GEN2_BOX_NICKNAMES + s * 11, cfg.polished))
                },
            )
        },
    ).also { b ->
        // Kept for the next sample only once every stat could be worked out: the cart (the base stats) can come in later.
        if (b.boxes.all { box -> box.mons.all { it == null || it.isEgg || it.stats != null } }) {
            synchronized(pcBoxesLock) { lastGen2Raw = key; lastGen2Boxes = b }
        }
    }
}

/** A box_struct at [off]: [decodeGen2PartyMon]'s fields, its stats worked out ([gen2BoxStats]) and full HP. */
fun decodeGen2BoxMon(raw: ByteArray, off: Int, cfg: Gen2Config, egg: Boolean): Mon? {
    // The party decoder reads past the box struct only for status, HP and stats: give it a party struct with those blank.
    val party = ByteArray(48).also { raw.copyInto(it, 0, off, off + GEN2_BOX_MON_SIZE) }
    val mon = decodeGen2PartyMon(party, 0, cfg, egg) ?: return null
    if (egg) return mon.copy(hp = 0, maxHp = 0, stats = null)
    val stats = gen2BoxStats(cfg, mon.species, mon.level, mon.stats?.ivs ?: return mon, mon.stats.evs)
        ?: return mon.copy(stats = null)
    return mon.copy(hp = stats[STAT_HP], maxHp = stats[STAT_HP], stats = mon.stats.copy(stats = stats))
}

/**
 * CalcMonStats with stat experience: ((base + DV) * 2 + ceil(sqrt(stat exp)) / 4) * level / 100 + 5, HP + level + 10
 * instead of 5, at most 999; in [MonStats.stats]' order (HP, Attack, Defense, Speed, Sp. Atk, Sp. Def). Base stats from
 * the cart (the DEX's entry); null while it isn't loaded.
 */
internal fun gen2BoxStats(cfg: Gen2Config, species: Int, level: Int, dvs: List<Int>, statExp: List<Int>): List<Int>? {
    val tables = cfg.pokedex ?: return null
    // The DEX page's order (HP, ATK, DEF, SP. ATK, SP. DEF, SPEED) back to BaseData's.
    val page = PokedexSource.entry(tables, species)?.baseStats?.takeIf { it.size == 6 } ?: return null
    val base = listOf(page[0], page[1], page[2], page[5], page[3], page[4])
    return List(6) { i ->
        val exp = kotlin.math.ceil(kotlin.math.sqrt(statExp[i].toDouble())).toInt() / 4
        val core = ((base[i] + dvs[i]) * 2 + exp) * level / 100
        (if (i == STAT_HP) core + level + 10 else core + 5).coerceAtMost(999)
    }
}
