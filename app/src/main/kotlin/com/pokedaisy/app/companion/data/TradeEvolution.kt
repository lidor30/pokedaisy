package com.pokedaisy.app.companion.data

/**
 * TRADE EVOLUTIONS (a GAMEPLAY setting, off by default): a party Pokémon that evolves by trading - KADABRA, MACHOKE,
 * GRAVELER, HAUNTER, or one holding its item (ONIX with a METAL COAT, ...) - evolves from its summary, through the
 * game's own code, as trade.c does after a link trade (CB2_TryLinkTradeEvolution):
 * GetEvolutionTargetSpecies(mon, EVO_MODE_TRADE, 0) says what it becomes (and takes the held item a held-item
 * evolution uses up; an Everstone says no), then BeginEvolutionScene(mon, target, FALSE, slot) plays the game's
 * evolution - its Pokédex entry, the rename of a Pokémon still called by its species name in the game's language,
 * the move learned on evolving. gCB2_AfterEvolution, where the scene goes when it's done, is CB2_ReturnToField: the
 * field comes back as after a menu. The player's controls are locked first (LockPlayerFieldControls), as a menu
 * leaves them. Checked headless on the user's FireRed and Emerald saves: KADABRA to ALAKAZAM from the field, the
 * field back after, START free, the stats recalculated.
 *
 * Which Pokémon get the button is the same function asked on a copy: each slot's 100 bytes put back after the call
 * ([tradeEvolveTargets]), so a held item isn't spent by asking.
 */
data class TradeEvoFns(
    /** GetEvolutionTargetSpecies(mon, mode, evolutionItem): EVO_MODE_TRADE is 1. */
    val getEvolutionTarget: Long,
    /** BeginEvolutionScene(mon, postEvoSpecies, canStopEvo, partyId). */
    val beginEvolutionScene: Long,
    /** gCB2_AfterEvolution (IWRAM): the main callback the scene ends on. */
    val afterEvolution: Long,
    /** CB2_ReturnToField. */
    val returnToField: Long,
    /** LockPlayerFieldControls. */
    val lockControls: Long,
    /** CRC32 of each function's first 32 bytes: getEvolutionTarget, beginEvolutionScene, returnToField, lockControls. */
    val codeCrcs: List<Long>,
) {
    val functions get() = listOf(getEvolutionTarget, beginEvolutionScene, returnToField, lockControls)
}

// TRADE_EVO_FIRERED_REV1, _LEAFGREEN_REV0 / _REV1 and TRADE_EVO_EMERALD are records in gamedata/gen3/records/
// (GameDataRecordsGen.kt).

/** The trade evolution calls for a ROM, by what the companion detected it as: retail English FireRed rev 1, LeafGreen
 * rev 0 / 1 and Emerald only (not Emerald Kaizo: its map reload after a CB2_ReturnToField from the live field crashed
 * FLY there; not the rebuilds - Legacy, Recharged - whose code is elsewhere). The code CRCs still decide. */
fun tradeEvoFor(kind: GameKind?, gameCode: String, revision: Int, hack: Boolean): TradeEvoFns? = when {
    hack -> null
    kind == GameKind.FIRERED && gameCode == "BPRE" && revision == 1 -> TRADE_EVO_FIRERED_REV1
    kind == GameKind.FIRERED && gameCode == "BPGE" && revision == 0 -> TRADE_EVO_LEAFGREEN_REV0
    kind == GameKind.FIRERED && gameCode == "BPGE" && revision == 1 -> TRADE_EVO_LEAFGREEN_REV1
    kind == GameKind.EMERALD && gameCode == "BPEE" -> TRADE_EVO_EMERALD
    else -> null
}

/** Whether the running ROM has [evo]'s functions where they're expected. */
fun tradeEvoCodeMatches(evo: TradeEvoFns, read: (addr: Long, len: Int) -> ByteArray): Boolean =
    evo.functions.zip(evo.codeCrcs).all { (fn, crc) -> java.util.zip.CRC32().apply { update(read(fn, 32)) }.value == crc }

enum class TradeEvoResult {
    /** The game's evolution is playing. */
    EVOLVING,
    /** Not on the free field right now (a menu, a script, a battle). */
    NOT_NOW,
    /** It doesn't evolve by trading now (another species, an Everstone, its item missing, an egg). */
    NO_EVOLUTION,
    /** The party changed under the summary (the Pokémon isn't in that slot any more). */
    MOVED,
    FAILED,
}

private const val MON_SIZE = 100
private const val EVO_MODE_TRADE = 1
private const val MON_DATA_PERSONALITY = 0

/**
 * The party Pokémon that would evolve by trade right now, by personality: what each becomes. The game's own answer
 * on each slot, its bytes put back after (a held-item evolution's item stays). Eggs are skipped. Emu thread.
 */
fun tradeEvolveTargets(fns: FieldItemFns, evo: TradeEvoFns, g: GameCalls): Map<Long, Int> {
    val p = fns.party ?: return emptyMap()
    if (!tradeEvoCodeMatches(evo, g::read) || !fieldItemCodeMatches(fns, g::read)) return emptyMap()
    val count = (g.read(p.partyCount, 1)[0].toInt() and 0xFF).coerceAtMost(6)
    val out = LinkedHashMap<Long, Int>()
    for (slot in 0 until count) {
        val mon = p.party + slot * MON_SIZE
        if (g.call(p.getMonData, mon.toInt(), p.isEggField) != 0L) continue
        val saved = g.read(mon, MON_SIZE)
        val target = runCatching { g.call(evo.getEvolutionTarget, mon.toInt(), EVO_MODE_TRADE, 0).toInt() and 0xFFFF }.getOrDefault(0)
        g.write(mon, saved)
        if (target != 0) out[g.call(p.getMonData, mon.toInt(), MON_DATA_PERSONALITY) and 0xFFFFFFFFL] = target
    }
    return out
}

/**
 * Evolves party slot [slot] ([personality]) as a trade would: the game's checks again, then its evolution scene, which
 * hands the field back when it ends. [busy]: the engine says the game isn't between field frames. Emu thread.
 */
fun tradeEvolve(fns: FieldItemFns, evo: TradeEvoFns, slot: Int, personality: Long, busy: Boolean, g: GameCalls): TradeEvoResult =
    runCatching {
        val p = fns.party ?: return TradeEvoResult.FAILED
        if (!tradeEvoCodeMatches(evo, g::read) || !fieldItemCodeMatches(fns, g::read)) return TradeEvoResult.FAILED
        if (busy || g.call(fns.fieldControlsLocked) != 0L) return TradeEvoResult.NOT_NOW
        val count = g.read(p.partyCount, 1)[0].toInt() and 0xFF
        if (slot !in 0 until count.coerceAtMost(6)) return TradeEvoResult.MOVED
        val mon = (p.party + slot * MON_SIZE).toInt()
        if (g.call(p.getMonData, mon, MON_DATA_PERSONALITY) and 0xFFFFFFFFL != personality) return TradeEvoResult.MOVED
        if (g.call(p.getMonData, mon, p.isEggField) != 0L) return TradeEvoResult.NO_EVOLUTION
        // The real thing from here: a held-item evolution's item is used up, as in a trade.
        val target = g.call(evo.getEvolutionTarget, mon, EVO_MODE_TRADE, 0).toInt() and 0xFFFF
        if (target == 0) return TradeEvoResult.NO_EVOLUTION
        val back = evo.returnToField or 1L
        if (!g.write(evo.afterEvolution, ByteArray(4) { (back shr (8 * it)).toByte() })) return TradeEvoResult.FAILED
        g.call(evo.lockControls)
        g.call(evo.beginEvolutionScene, mon, target, 0, slot)
        TradeEvoResult.EVOLVING
    }.getOrDefault(TradeEvoResult.FAILED)
