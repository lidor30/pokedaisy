package com.pokedaisy.app.companion.data

/**
 * A move's battle data beyond name / type / power ([MoveStatsGen.kt]): the effect
 * (gBattleMoves' EFFECT_* name without the prefix), accuracy (0 = never misses),
 * PP, the effect's chance and priority.
 */
data class MoveStats(val effect: String, val accuracy: Int, val pp: Int, val effectChance: Int, val priority: Int)

/** Whether the running game's moves are the original games' (FireRed / LeafGreen / Ruby /
 * Sapphire / Emerald, any language): only those have [MoveStats] so far. A FireRed hack that
 * changed moves (Rocket Edition, Ash Gray: [HackText.moves]) isn't, nor Recharged Emerald (its
 * gBattleMoves changed accuracies and effects even where names, types and powers are retail's). */
val movesAreVanilla: Boolean get() =
    (activeGame == GameKind.FIRERED && runningFireRedHack?.moves.isNullOrEmpty()) ||
        (activeGame == GameKind.EMERALD && runningEmeraldHack == null)

/** [id]'s [MoveStats]; null where the running game's moves aren't the original games'. A randomized retail ROM's
 * ([runningRandomized]) are its own, read from it. */
fun moveStats(id: Int): MoveStats? = if (!movesAreVanilla) null
    else runningRandomized?.moveStats?.let { it.getOrNull(id)?.takeIf { id > 0 } }
    // Emerald Legacy rebalanced ~130 moves (accuracy, PP, a few effects): its own table, read from its ROM.
    ?: (if (emeraldLegacy) moveStatsEmeraldLegacy else vanillaMoveStats).getOrNull(id)?.takeIf { id > 0 }

/** The original games' HMs (CUT, FLY, SURF, STRENGTH, FLASH, ROCK SMASH, WATERFALL, DIVE): the
 * game won't let a Pokémon forget one, so the advice never suggests it. */
val VANILLA_HM_MOVES = setOf(15, 19, 57, 70, 148, 249, 127, 291)

/** The original games' TM01..TM50 then HM01..HM08 are these item ids, in gTMHMMoves' order. */
const val VANILLA_ITEM_TM01 = 289
const val VANILLA_ITEM_HM08 = 346

/** What the advice needs of a Pokémon: its types (ids), Attack / Sp. Atk (null = unknown),
 * level and the moves it knows (ids, empty slots left out). */
data class AdviceMon(val types: List<Int>, val attack: Int?, val spAttack: Int?, val level: Int, val moves: List<Int>)

enum class Verdict {
    /** A free slot: the game teaches it without asking. */
    LEARN,
    /** Worth it, in place of [MoveAdvice.forget]. */
    REPLACE,
    /** The moves it has are better. */
    SKIP,
    /** It knows it already. */
    KNOWN,
}

/** Why: an English id the UI translates (see [MoveAdvisor]), [args] filled in, [good] for the Pokémon or not. */
data class AdviceReason(val text: String, val args: List<String> = emptyList(), val good: Boolean)

/** The advice on learning [move]: the [verdict], the move to [forget] (0 = none) and why. */
data class MoveAdvice(val move: Int, val verdict: Verdict, val forget: Int, val reasons: List<AdviceReason>, val gain: Float)

/**
 * Whether a Pokémon should learn a move, and in place of which - worked out from the
 * game's own move data, no guessing about the player's plans. Its moveset is scored as
 *  - attacks: for every defending type, the best hit it has against it (power x accuracy,
 *    x1.5 for its own type, scaled by the stat the move runs on - Gen 3 splits physical /
 *    special by type), averaged over the types, so a second move of a type it already
 *    hits well adds little and a new type it couldn't touch adds a lot;
 *  - status moves: a tier by effect (sleep, paralysis, Toxic, a +2 boost on the stat it
 *    uses, healing...), worth a share of that attack score, less for a second of a kind.
 * The best swap is the one that raises the score most; under [MIN_GAIN] it's a SKIP
 * ([MIN_GAIN_HM] to learn an HM, which can't be forgotten again). HM moves are never
 * offered for forgetting (the game refuses).
 */
object MoveAdvisor {
    private const val MIN_GAIN = 0.1f
    /** An HM stays for good (only the Move Deleter takes it back): it has to be worth much more. */
    private const val MIN_GAIN_HM = 0.3f
    /** Swaps scoring within this of the best are a tie: the one forgetting a weaker move of the new move's type wins. */
    private const val TIE = 0.01f
    /** A status tier point is worth this share of the attack score. */
    private const val TIER_SHARE = 0.08f

    fun advise(mon: AdviceMon, move: Int, unforgettable: Set<Int> = VANILLA_HM_MOVES): MoveAdvice {
        if (move in mon.moves) return MoveAdvice(move, Verdict.KNOWN, 0, emptyList(), 0f)
        val current = mon.moves.filter { it != 0 }
        val ref = attackScore(mon, current).takeIf { it > 0f } ?: BASELINE
        val now = score(mon, current, ref)
        if (current.size < 4) {
            val gain = (score(mon, current + move, ref) - now) / ref
            return MoveAdvice(move, Verdict.LEARN, 0, listOf(AdviceReason("FREE SLOT", good = true)) + whyNew(mon, move, null, current, ref), gain)
        }
        val options = current.filter { it !in unforgettable }.map { out ->
            out to score(mon, current.map { if (it == out) move else it }, ref)
        }
        val top = options.maxOfOrNull { it.second }
        val best = top?.let { t ->
            val newType = lookupMove(move).type
            options.filter { (t - it.second) / ref <= TIE }.minWithOrNull(
                compareBy<Pair<Int, Float>> { if (!isStatus(it.first) && lookupMove(it.first).type == newType) 0 else 1 }
                    .thenBy { attackValue(mon, it.first) ?: Float.MAX_VALUE },
            )
        }
        val gain = best?.let { (it.second - now) / ref } ?: 0f
        val hm = move in unforgettable
        if (best == null || gain < (if (hm) MIN_GAIN_HM else MIN_GAIN)) {
            val why = whySkip(mon, move, current)
            return MoveAdvice(move, Verdict.SKIP, 0, if (hm && gain >= MIN_GAIN) listOf(AdviceReason("AN HM: IT STAYS FOR GOOD", good = false)) else why, gain)
        }
        return MoveAdvice(move, Verdict.REPLACE, best.first, whyNew(mon, move, best.first, current, ref), gain)
    }

    /** No attacks at all: status moves are rated against a modest attack instead. */
    private const val BASELINE = 30f

    private fun score(mon: AdviceMon, moves: List<Int>, ref: Float): Float = attackScore(mon, moves) + statusScore(mon, moves, ref)

    /** The mean, over the defending types, of the best hit the moveset has on each. */
    internal fun attackScore(mon: AdviceMon, moves: List<Int>): Float {
        val hits = moves.mapNotNull { id -> attackValue(mon, id)?.let { lookupMove(id).type to it } }
        if (hits.isEmpty()) return 0f
        val types = defendingTypes()
        return types.sumOf { def -> hits.maxOf { (t, v) -> v * typeMultiplierPct(t, def, TYPE_NONE) / 100.0 } }.toFloat() / types.size
    }

    private fun defendingTypes(): List<Int> = activeTypeNames.keys.filter { it != TYPE_MYSTERY && it != TYPE_NONE }.sorted()

    /** A hit's worth to this Pokémon, before the type chart; null = a status move. */
    internal fun attackValue(mon: AdviceMon, id: Int): Float? {
        val info = lookupMove(id)
        val st = moveStats(id) ?: return null
        if (info.power == 0) return null
        var p = info.power.toFloat()
        var ownType = true // fixed-damage moves ignore both the type bonus and the stats
        when (st.effect) {
            "MULTI_HIT" -> p *= 3f // 2-5 hits, 3 on average
            "DOUBLE_HIT", "TWINEEDLE" -> p *= 2f
            "TRIPLE_KICK" -> p = 50f
            "OHKO" -> { p = 90f; ownType = false }
            "LEVEL_DAMAGE" -> { p = mon.level.coerceIn(10, 100).toFloat(); ownType = false }
            "SONICBOOM", "DRAGON_RAGE" -> { p = 35f; ownType = false }
            "SUPER_FANG", "PSYWAVE", "ENDEAVOR" -> { p = 40f; ownType = false }
            "COUNTER", "MIRROR_COAT", "BIDE" -> { p = 40f; ownType = false }
            "RETURN", "FRUSTRATION" -> p = 70f
            "FLAIL", "LOW_KICK", "MAGNITUDE", "WEATHER_BALL", "SPIT_UP", "BEAT_UP", "PRESENT" -> p = 55f
            "HIDDEN_POWER" -> p = 50f
            "ERUPTION" -> p *= 0.7f
            "RECHARGE", "RAZOR_WIND", "SKULL_BASH", "SKY_ATTACK", "SOLAR_BEAM", "FOCUS_PUNCH" -> p *= 0.6f
            "SEMI_INVULNERABLE", "RAMPAGE" -> p *= 0.85f
            "RECOIL", "DOUBLE_EDGE", "RECOIL_IF_MISS" -> p *= 0.9f
            "OVERHEAT", "SUPERPOWER" -> p *= 0.8f
            "EXPLOSION" -> p *= 0.25f
            "FAKE_OUT", "SNORE", "DREAM_EATER" -> p *= 0.4f
            "FALSE_SWIPE", "FUTURE_SIGHT" -> p *= 0.75f
            "ROLLOUT", "FURY_CUTTER" -> p *= 1.5f
        }
        val acc = if (st.accuracy == 0) 1f else st.accuracy / 100f
        if (!ownType) return p * acc
        val stab = if (info.type in mon.types) 1.5f else 1f
        return p * acc * stab * statShare(mon, info.type) * (if (st.priority > 0) 1.1f else 1f)
    }

    /** The stat [type]'s moves run on, against the Pokémon's better one (1 = its best). */
    private fun statShare(mon: AdviceMon, type: Int): Float {
        val atk = mon.attack ?: return 1f
        val spa = mon.spAttack ?: return 1f
        val best = maxOf(atk, spa).takeIf { it > 0 } ?: return 1f
        return ((if (isPhysical(type)) atk else spa).toFloat() / best).coerceAtLeast(0.4f)
    }

    /** Gen 3: Normal ... Steel are physical, Fire ... Dark special. */
    fun isPhysical(type: Int): Boolean = type < TYPE_MYSTERY

    // ---- status moves -----------------------------------------------------------

    private enum class Group { INFLICT, SETUP_ATK, SETUP_SPA, SETUP_OTHER, HEAL, SHIELD, DEBUFF, WEATHER, OTHER }

    private fun statusTier(effect: String): Pair<Group, Float> = when (effect) {
        "SLEEP", "PARALYZE", "TOXIC" -> Group.INFLICT to 3f
        "WILL_O_WISP", "POISON", "YAWN", "LEECH_SEED" -> Group.INFLICT to 2f
        "CONFUSE", "ATTRACT", "SWAGGER", "FLATTER", "TEETER_DANCE" -> Group.INFLICT to 1f
        "ATTACK_UP_2", "DRAGON_DANCE", "BULK_UP", "BELLY_DRUM", "CURSE" -> Group.SETUP_ATK to 3f
        "ATTACK_UP" -> Group.SETUP_ATK to 1.5f
        "CALM_MIND", "SPECIAL_ATTACK_UP_2" -> Group.SETUP_SPA to 3f
        "SPECIAL_ATTACK_UP" -> Group.SETUP_SPA to 1.5f
        "SPEED_UP_2", "COSMIC_POWER", "DEFENSE_UP_2", "SPECIAL_DEFENSE_UP_2", "EVASION_UP", "MINIMIZE" -> Group.SETUP_OTHER to 2f
        "DEFENSE_UP", "DEFENSE_CURL", "FOCUS_ENERGY", "CHARGE", "STOCKPILE" -> Group.SETUP_OTHER to 1f
        "RESTORE_HP", "SOFTBOILED", "MOONLIGHT", "MORNING_SUN", "SYNTHESIS", "WISH" -> Group.HEAL to 3f
        "PAIN_SPLIT" -> Group.HEAL to 2f
        "REST" -> Group.HEAL to 1.5f
        "INGRAIN", "SWALLOW" -> Group.HEAL to 1f
        "SUBSTITUTE", "REFLECT", "LIGHT_SCREEN" -> Group.SHIELD to 2f
        "PROTECT" -> Group.SHIELD to 1.5f
        "ENDURE", "SAFEGUARD", "MIST" -> Group.SHIELD to 0.7f
        "ATTACK_DOWN_2", "DEFENSE_DOWN_2", "SPEED_DOWN_2", "SPECIAL_DEFENSE_DOWN_2" -> Group.DEBUFF to 1.5f
        "ACCURACY_DOWN" -> Group.DEBUFF to 1f
        "ATTACK_DOWN", "DEFENSE_DOWN", "SPEED_DOWN", "EVASION_DOWN", "TICKLE" -> Group.DEBUFF to 0.5f
        "RAIN_DANCE", "SUNNY_DAY", "SANDSTORM", "HAIL" -> Group.WEATHER to 1f
        "HEAL_BELL", "BATON_PASS", "TAUNT", "ENCORE", "DISABLE", "SPIKES", "HAZE", "PERISH_SONG", "DESTINY_BOND" -> Group.OTHER to 1f
        "SPLASH", "TELEPORT", "WATER_SPORT", "MUD_SPORT", "CAMOUFLAGE", "CONVERSION", "CONVERSION_2", "SKETCH",
        "FOLLOW_ME", "HELPING_HAND", "GRUDGE", "SNATCH", "IMPRISON", "RECYCLE", "ROLE_PLAY", "NIGHTMARE" -> Group.OTHER to 0f
        else -> Group.OTHER to 0.5f
    }

    /** A status move's tier for this Pokémon: a boost to the stat it doesn't attack with is worth little. */
    private fun tierFor(mon: AdviceMon, effect: String): Pair<Group, Float> {
        val (g, t) = statusTier(effect)
        val atk = mon.attack
        val spa = mon.spAttack
        if (atk == null || spa == null) return g to t
        return when {
            g == Group.SETUP_ATK && atk < spa * 0.8f -> g to 0.5f
            g == Group.SETUP_SPA && spa < atk * 0.8f -> g to 0.5f
            else -> g to t
        }
    }

    /** What a hit does besides damage (Body Slam's paralysis, Ice Beam's freeze), at the full effect's tier;
     * the move's chance scales it. */
    private fun sideEffect(effect: String): Pair<Group, Float>? = when (effect) {
        "PARALYZE_HIT", "THUNDER", "FREEZE_HIT", "POISON_FANG" -> Group.INFLICT to 3f
        "BURN_HIT", "BLAZE_KICK", "POISON_HIT", "POISON_TAIL", "TRI_ATTACK" -> Group.INFLICT to 2f
        "CONFUSE_HIT" -> Group.INFLICT to 1f
        "FLINCH_HIT", "FLINCH_MINIMIZE_HIT" -> Group.OTHER to 1f
        "ATTACK_DOWN_HIT", "DEFENSE_DOWN_HIT", "SPEED_DOWN_HIT", "SPECIAL_ATTACK_DOWN_HIT", "SPECIAL_DEFENSE_DOWN_HIT", "ACCURACY_DOWN_HIT" -> Group.DEBUFF to 1f
        "ATTACK_UP_HIT", "DEFENSE_UP_HIT", "ALL_STATS_UP_HIT" -> Group.SETUP_OTHER to 1f
        else -> null
    }

    /** A move's effect tier for this Pokémon: a status move's own, or a hit's side effect times its chance. */
    private fun effectTier(mon: AdviceMon, id: Int): Pair<Group, Float>? {
        val st = moveStats(id) ?: return null
        if (lookupMove(id).power == 0) return tierFor(mon, st.effect)
        val (g, t) = sideEffect(st.effect) ?: return null
        return g to t * st.effectChance.coerceIn(0, 100) / 100f
    }

    private fun statusScore(mon: AdviceMon, moves: List<Int>, ref: Float): Float {
        val seen = HashMap<Group, Int>()
        return moves.mapNotNull { id -> effectTier(mon, id) }
            .sortedByDescending { it.second }
            .sumOf { (g, t) ->
                val n = seen.merge(g, 1, Int::plus)!!
                // One way to put a foe to sleep is plenty: a second of a kind counts for little.
                (t * if (n == 1) 1f else 0.3f).toDouble()
            }.toFloat() * TIER_SHARE * ref
    }

    // ---- reasons ------------------------------------------------------------------

    private fun isStatus(id: Int) = lookupMove(id).power == 0

    /** The types [moves] hit super effectively (2x or more), by type id. */
    private fun superTypes(mon: AdviceMon, moves: List<Int>): Set<Int> {
        val attacks = moves.filter { attackValue(mon, it) != null }.map { lookupMove(it).type }
        return defendingTypes().filter { def -> attacks.any { typeMultiplierPct(it, def, TYPE_NONE) > 100 } }.toSet()
    }

    private fun typeList(ids: Collection<Int>) = ids.take(3).joinToString(", ") { typeLabel(typeName(it)) }

    /** Why learning [move] (in place of [out], if any) is worth it. */
    private fun whyNew(mon: AdviceMon, move: Int, out: Int?, current: List<Int>, ref: Float): List<AdviceReason> {
        val r = mutableListOf<AdviceReason>()
        val info = lookupMove(move)
        val st = moveStats(move)
        val after = if (out == null) current + move else current.map { if (it == out) move else it }
        if (!isStatus(move)) {
            val gained = superTypes(mon, after) - superTypes(mon, current)
            if (gained.isNotEmpty()) r += AdviceReason("NEW 2x ON {0}", listOf(typeList(gained)), good = true)
            if (info.type in mon.types) r += AdviceReason("YOUR TYPE: x1.5", good = true)
            if (out != null && !isStatus(out) && lookupMove(out).type == info.type && info.power > lookupMove(out).power) {
                r += AdviceReason("SAME TYPE, {0} vs {1} POWER", listOf("${info.power}", "${lookupMove(out).power}"), good = true)
            }
            if (statShare(mon, info.type) < 0.8f) r += AdviceReason(if (isPhysical(info.type)) "RUNS ON ATTACK, ITS WEAKER STAT" else "RUNS ON SP. ATK, ITS WEAKER STAT", good = false)
            if (st != null && st.accuracy in 1..84) r += AdviceReason("ACCURACY {0}%", listOf("${st.accuracy}"), good = false)
        } else if (st != null && tierFor(mon, st.effect).second >= 2f) {
            r += AdviceReason("STRONG EFFECT", good = true)
        }
        if (out != null) {
            val lost = superTypes(mon, current) - superTypes(mon, after)
            if (lost.isNotEmpty()) r += AdviceReason("LOSES 2x ON {0}", listOf(typeList(lost)), good = false)
            // What the forgotten move adds to the rest: a weak effect, or hits the others already land harder.
            val adds = (score(mon, current, ref) - score(mon, current - out, ref)) / ref
            if (adds < MIN_GAIN) r += AdviceReason("{0} DOES LITTLE", listOf(lookupMove(out).name), good = true)
        }
        return r.sortedByDescending { it.good }.take(3)
    }

    /** Why [move] isn't worth a slot. */
    private fun whySkip(mon: AdviceMon, move: Int, current: List<Int>): List<AdviceReason> {
        val r = mutableListOf<AdviceReason>()
        val info = lookupMove(move)
        val st = moveStats(move)
        if (!isStatus(move)) {
            val mine = attackValue(mon, move) ?: 0f
            current.filter { !isStatus(it) && lookupMove(it).type == info.type && (attackValue(mon, it) ?: 0f) >= mine }
                .maxByOrNull { attackValue(mon, it) ?: 0f }
                ?.let { r += AdviceReason("{0} IS BETTER", listOf(lookupMove(it).name), good = false) }
            if (statShare(mon, info.type) < 0.8f) r += AdviceReason(if (isPhysical(info.type)) "RUNS ON ATTACK, ITS WEAKER STAT" else "RUNS ON SP. ATK, ITS WEAKER STAT", good = false)
            if (st != null && st.accuracy in 1..84) r += AdviceReason("ACCURACY {0}%", listOf("${st.accuracy}"), good = false)
        } else if (st != null && tierFor(mon, st.effect).second <= 1f) {
            r += AdviceReason("WEAK EFFECT", good = false)
        }
        if (r.isEmpty()) r += AdviceReason("ITS MOVES ARE BETTER", good = false)
        return r.take(2)
    }
}

private const val TYPE_MYSTERY = 9
