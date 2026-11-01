package com.pokedaisy.app.companion.data

import java.io.File

/**
 * BEST EFFORT for a ROM the companion doesn't know (an unrecognised hash): read the running game
 * through every config the app has for its base game and keep the one it makes sense with. Many
 * hacks keep a supported game's RAM (Orange Islands read as FireRed before it had a config of its
 * own), and a supported version rebuilt or re-patched has a new hash but the same RAM (the SoulGold
 * v1.2 builds players reported as "not supported").
 *
 * Reading the RAM isn't enough: another hack on the same engine reads a real party through a
 * config and then shows the wrong names, sprites and map (a Galar hack read as Emerald). So each
 * config is checked against what the companion would *show* ([Fit]):
 * - its party: 1 to 6 Pokémon, each decoding (encrypted boxes pass their checksum) with a
 *   plausible species, level and HP - needs the player in game with a Pokémon;
 * - its names: the share of its species / move / item / map-section names the ROM's own text holds
 *   ([RomNames]: wherever they sit, so a rebuild with moved tables still passes), and of the ones
 *   it would show right now (the party, its moves, the bag, the current map);
 * - its ROM-side parts ([Part]), each switched off where it doesn't hold (a rebuild's moved icon
 *   table: no sprites rather than wrong ones).
 *
 * A config is SURE ([Fit.sure]) when the party reads and the names hold: that's used without asking.
 * Anything less is the player's call - the companion lists the configs ranked ([Verdict.fits]) and
 * the player picks the game they're playing ([BestEffortStore.Source.PICKED]). Before there's a
 * party, a ROM whose names point at one game only gets it as a guess ([Verdict.guess]), checked
 * again once a party exists. The result is kept per ROM ([BestEffortStore], by SHA-1).
 */
object BestEffort {
    /** A config best effort can try: [id] is what [BestEffortStore] keeps (stable, never renamed). */
    class Candidate(val id: String, val kind: GameKind, val cfg: NativeConfig, val families: Set<String>, val title: String) {
        /** A FireRed-engine hack's map names come from its ROM ([RomRegionMap]), not a bundled table: nothing to check. */
        val mapFromRom get() = families == FIRERED && kind != GameKind.FIRERED
    }

    /** ROM-side pieces checked against the ROM; the RAM side (party, bag, map, battle) comes with the config. */
    enum class Part { DEX, GUIDE, ITEM_TEXT, MON_ICONS, ITEM_ICONS }

    class Match(val candidate: Candidate, val full: Boolean, val off: Set<Part>, val score: Int) {
        /** The config to read with: [off] parts dropped (a partial match gets no trainer card either). */
        val config: NativeConfig get() {
            if (full) return candidate.cfg
            var c = candidate.cfg
            if (Part.DEX in off) c = c.copy(pokedex = null)
            if (Part.GUIDE in off) c = c.copy(guideTables = null, enemyParty = 0)
            if (Part.ITEM_TEXT in off) c = c.copy(itemDescs = null)
            if (Part.MON_ICONS in off) c = c.copy(monIconTable = 0, monIconPaletteTable = 0, monIconPaletteIndices = 0, monIconPalettes = 0)
            if (Part.ITEM_ICONS in off) c = c.copy(itemIconTable = 0, extraItemIconTable = 0)
            return c.copy(trainerCard = null)
        }
    }

    /**
     * How the running ROM reads as [candidate]. [text]: what the ROM's text says about the config
     * (its name tables there). [party]: the score of the party it reads, null when none reads
     * through it. [shown] / [shownOf]: how many of the names it would show now (party, moves, bag,
     * current map) the ROM confirms - at their very ids in its own tables where those were found,
     * else anywhere - null without a party.
     */
    class Fit(val candidate: Candidate, val off: Set<Part>, val text: TextFit, val party: Int?, val shown: Int?, val shownOf: Int?) {
        /** Its names are this ROM's: species at their own ids, the ROM's species (nearly) all its own
         * and no more past its last, and map sections the ROM knows (or read from it). */
        val namesHold get() = text.speciesHold && (text.coversBy.firstOrNull() ?: 0.0) >= SPECIES_COVERS_MIN &&
            (candidate.mapFromRom || text.map != null || (text.mapInRom ?: 0.0) >= MAP_IN_ROM_MIN)
        /** What it shows checks out: at most one in ten names unconfirmed (one, for fewer than 10). */
        val shownHolds get() = shown != null && shownOf != null && shownOf > 0 && shownOf - shown <= maxOf(1, shownOf / 10)
        val sure get() = party != null && namesHold && shownHolds
        fun match() = Match(candidate, off.isEmpty(), off, party ?: 0)
        override fun toString() = "${candidate.id} party=$party shown=$shown/$shownOf off=$off $text"
    }

    /**
     * Every config of the ROM's base game, best first: SURE ones, then the ones that read a party
     * (by how much of what they'd show holds), then by names. [sure]: the one to use without
     * asking - unless another game is as sure (another engine sibling reading just as well: then
     * it's the player's call). [guess]: no party yet, and only one game's names and map-section
     * table are the ROM's - used until a party says more.
     */
    class Verdict(val fits: List<Fit>, val sawParty: Boolean) {
        val sure: Fit? get() {
            val top = fits.firstOrNull { it.sure } ?: return null
            return top.takeUnless { fits.any { it.sure && it.candidate.kind != top.candidate.kind && !beats(top, it) } }
        }

        /** [a] is surely the game rather than [b]: more of its address-checked parts hold (this very
         * build), or as many and clearly more of its own names in the ROM (an edit of a base game reads
         * as the base game too, but the base game's names aren't all there). */
        private fun beats(a: Fit, b: Fit) = a.off.size < b.off.size ||
            (a.off.size == b.off.size && a.text.inRom >= b.text.inRom + IN_ROM_MARGIN)
        val guess: Fit? get() {
            if (sawParty) return null
            val likely = fits.filter { it.namesHold && (it.candidate.mapFromRom || it.text.map != null) }
            return likely.firstOrNull()?.takeIf { f -> likely.all { it.candidate.kind == f.candidate.kind } }
        }
        /** Why there's nothing to use: no party to read yet, or none reads as a known game. */
        val miss: Miss? get() = when {
            sure != null -> null
            !sawParty -> Miss.NO_PARTY
            else -> Miss.NO_MATCH
        }
        /** Worth a LIKELY tag in the picker. */
        fun likely(f: Fit) = f.namesHold && (f.party != null || !sawParty)
    }

    /** How much more of its own names in the ROM a config needs to beat an equally good other game's. */
    const val IN_ROM_MARGIN = 0.15
    /** A config's species table must put this share of its names at their own ids in the ROM's. */
    const val SPECIES_AGREES_MIN = 0.95
    /** A config must know this share of the species names the ROM has (a smaller game's can't). */
    const val SPECIES_COVERS_MIN = 0.7
    /** ... and, with no map-section table found, this share of its map names must be in the ROM. */
    const val MAP_IN_ROM_MIN = 0.4

    private val FIRERED = setOf("BPR", "BPG")
    /** Ultra Violet's own code: FireRed 1.0 underneath, but only its own config is worth trying. */
    private val ULTRA_VIOLET = setOf("PUV")

    /** A FireRed-engine game's header code (the companion falls back to FireRed's look). */
    fun isFireRedFamily(gameCode: String?): Boolean = gameCode != null && (FIRERED + ULTRA_VIOLET).any { gameCode.startsWith(it) }
    private val EMERALD = setOf("BPE")
    private val RUBY_SAPPHIRE = setOf("AXV", "AXP")

    /** Every config, by base game - the first three letters of the header's game code. */
    val candidates: List<Candidate> by lazy {
        listOf(
            Candidate("FIRERED_REV1", GameKind.FIRERED, NATIVE_FIRERED_REV1, FIRERED, "Pokémon FireRed"),
            Candidate("FIRERED_REV0", GameKind.FIRERED, NATIVE_FIRERED_REV0, FIRERED, "Pokémon FireRed (rev 0)"),
            Candidate("LEAFGREEN_REV1", GameKind.FIRERED, NATIVE_LEAFGREEN_REV1, FIRERED, "Pokémon LeafGreen"),
            Candidate("LEAFGREEN_REV0", GameKind.FIRERED, NATIVE_LEAFGREEN_REV0, FIRERED, "Pokémon LeafGreen (rev 0)"),
            Candidate("UNBOUND", GameKind.UNBOUND, NATIVE_UNBOUND_WITH_DEX, FIRERED, "Pokémon Unbound"),
            Candidate("UNBOUND_FR", GameKind.UNBOUND, NATIVE_UNBOUND_FR, FIRERED, "Pokémon Unbound (FR)"),
            Candidate("GAIA_V3_2", GameKind.GAIA, NATIVE_GAIA_V3_2, FIRERED, "Pokémon Gaia"),
            Candidate("RADICAL_RED_V4_1", GameKind.RADICAL_RED, NATIVE_RADICAL_RED_V4_1, FIRERED, "Pokémon Radical Red"),
            Candidate("ODYSSEY", GameKind.ODYSSEY, NATIVE_ODYSSEY, FIRERED, "Pokémon Odyssey"),
            Candidate("AMETHYST", GameKind.AMETHYST, NATIVE_AMETHYST, FIRERED, "Pokémon Amethyst v1.3"),
            Candidate("AMETHYST_V1_4_1", GameKind.AMETHYST, NATIVE_AMETHYST_V1_4_1, FIRERED, "Pokémon Amethyst v1.4"),
            Candidate("CELIA", GameKind.CELIA, NATIVE_CELIA, FIRERED, "Celia's Stupid Romhack"),
            Candidate("ORANGE_ISLANDS", GameKind.ORANGE_ISLANDS, NATIVE_ORANGE_ISLANDS, FIRERED, "Pokémon Orange Islands"),
            Candidate("ULTRA_VIOLET", GameKind.FIRERED, NATIVE_ULTRA_VIOLET, ULTRA_VIOLET, "Pokémon Ultra Violet"),
            Candidate("ROCKET_EDITION", GameKind.FIRERED, NATIVE_ROCKET_EDITION, FIRERED, "Pokémon FireRed Rocket Edition"),
            Candidate("ASH_GRAY", GameKind.FIRERED, NATIVE_ASH_GRAY, FIRERED, "Pokémon Ash Gray"),
            Candidate("GHOST_GREY", GameKind.FIRERED, NATIVE_GHOST_GREY, FIRERED, "Pokémon Ghost Grey"),
            Candidate("SWORD_SHIELD", GameKind.SWORD_SHIELD, NATIVE_SWORD_SHIELD, FIRERED, "Sword and Shield Ultimate Plus"),
            Candidate("GS_CHRONICLES", GameKind.GS_CHRONICLES, NATIVE_GS_CHRONICLES, FIRERED, "Pokémon GS Chronicles"),
            Candidate("RECHARGED_YELLOW_V1_8_0", GameKind.RECHARGED_YELLOW, NATIVE_RECHARGED_YELLOW_V1_8_0, FIRERED, "Pokémon Recharged Yellow v1.8"),
            Candidate("RECHARGED_YELLOW_V1_9_0", GameKind.RECHARGED_YELLOW, NATIVE_RECHARGED_YELLOW_V1_9_0, FIRERED, "Pokémon Recharged Yellow v1.9.0"),
            Candidate("RECHARGED_YELLOW_V1_9_4", GameKind.RECHARGED_YELLOW, NATIVE_RECHARGED_YELLOW_V1_9_4, FIRERED, "Pokémon Recharged Yellow v1.9.4"),
            Candidate("RECHARGED_YELLOW_V1_9_7", GameKind.RECHARGED_YELLOW, NATIVE_RECHARGED_YELLOW_V1_9_7, FIRERED, "Pokémon Recharged Yellow v1.9.7"),
            Candidate("EMERALD", GameKind.EMERALD, NATIVE_EMERALD_RETAIL, EMERALD, "Pokémon Emerald"),
            Candidate("EMERALD_ES", GameKind.EMERALD, NATIVE_EMERALD_ES, EMERALD, "Pokémon Esmeralda"),
            Candidate("EMERALD_DE", GameKind.EMERALD, NATIVE_EMERALD_DE, EMERALD, "Pokémon Smaragd"),
            Candidate("EMERALD_FR", GameKind.EMERALD, NATIVE_EMERALD_FR, EMERALD, "Pokémon Émeraude"),
            Candidate("EMERALD_IT", GameKind.EMERALD, NATIVE_EMERALD_IT, EMERALD, "Pokémon Smeraldo"),
            Candidate("EMERALD_JA", GameKind.EMERALD, NATIVE_EMERALD_JA, EMERALD, "Pocket Monsters Emerald"),
            Candidate("HEART_AND_SOUL", GameKind.HEART_AND_SOUL, NATIVE_HEART_AND_SOUL, EMERALD, "Pokémon Heart and Soul"),
            Candidate("LAZARUS", GameKind.LAZARUS, NATIVE_LAZARUS, EMERALD, "Pokémon Lazarus"),
            Candidate("SOULGOLD", GameKind.SOULGOLD, NATIVE_SOULGOLD, EMERALD, "Pokémon SoulGold v1.1"),
            Candidate("SOULGOLD_V1_2", GameKind.SOULGOLD, NATIVE_SOULGOLD_V1_2, EMERALD, "Pokémon SoulGold v1.2"),
            Candidate("SOULGOLD_V1_2B", GameKind.SOULGOLD, NATIVE_SOULGOLD_V1_2B, EMERALD, "Pokémon SoulGold v1.2"),
            Candidate("SOULGOLD_V1_2C", GameKind.SOULGOLD, NATIVE_SOULGOLD_V1_2C, EMERALD, "Pokémon SoulGold v1.2"),
            Candidate("SOULGOLD_V1_2_1", GameKind.SOULGOLD, NATIVE_SOULGOLD_V1_2_1, EMERALD, "Pokémon SoulGold v1.2.1"),
            Candidate("ROWE", GameKind.ROWE, NATIVE_ROWE, EMERALD, "Pokémon R.O.W.E."),
            Candidate("EMERALD_ROGUE", GameKind.EMERALD_ROGUE, NATIVE_EMERALD_ROGUE, EMERALD, "Emerald Rogue"),
            Candidate("EMERALD_SEAGLASS", GameKind.EMERALD_SEAGLASS, NATIVE_EMERALD_SEAGLASS, EMERALD, "Emerald Seaglass"),
            Candidate("TMT2", GameKind.TMT2, NATIVE_TMT2, EMERALD, "Too Many Types 2"),
            Candidate("GLAZED", GameKind.GLAZED, NATIVE_GLAZED, EMERALD, "Pokémon Glazed"),
            Candidate("IMPERIUM", GameKind.IMPERIUM, NATIVE_IMPERIUM, EMERALD, "Emerald Imperium"),
            Candidate("QUETZAL", GameKind.QUETZAL, NATIVE_QUETZAL, EMERALD, "Pokémon Quetzal"),
            Candidate("QUETZAL_ES", GameKind.QUETZAL, NATIVE_QUETZAL_ES, EMERALD, "Pokémon Quetzal (ES)"),
            Candidate("ELITE_REDUX_V2_65_4_1B", GameKind.ELITE_REDUX, NATIVE_ELITE_REDUX_V2_65_4_1B, EMERALD, "Pokémon Elite Redux 2.65.4.1b"),
            Candidate("ELITE_REDUX_V2_65_3B", GameKind.ELITE_REDUX, NATIVE_ELITE_REDUX_V2_65_3B, EMERALD, "Pokémon Elite Redux 2.65.3b"),
            Candidate("TEAM_ROCKET", GameKind.TEAM_ROCKET, NATIVE_TEAM_ROCKET, EMERALD, "Pokémon Team Rocket Edition: Dragonsden"),
            Candidate("WISHES", GameKind.WISHES, NATIVE_WISHES, EMERALD, "Pokémon Wishes of Tomorrow"),
            Candidate("DREAMSTONE", GameKind.DREAMSTONE, NATIVE_DREAMSTONE, EMERALD, "Pokémon Dreamstone Mysteries"),
            Candidate("MARIOMON_V1_5_2", GameKind.MARIOMON, NATIVE_MARIOMON_V1_5_2, EMERALD, "Super Mariomon v1.5.2"),
            Candidate("MARIOMON_V1_5_1", GameKind.MARIOMON, NATIVE_MARIOMON_V1_5_1, EMERALD, "Super Mariomon v1.5.1"),
            Candidate("MARIOMON_V1_3", GameKind.MARIOMON, NATIVE_MARIOMON_V1_3, EMERALD, "Super Mariomon v1.3"),
            Candidate("MARIOMON_V1_2_2", GameKind.MARIOMON, NATIVE_MARIOMON_V1_2_2, EMERALD, "Super Mariomon v1.2.2"),
            Candidate("MARIOMON_V1_2_1", GameKind.MARIOMON, NATIVE_MARIOMON_V1_2_1, EMERALD, "Super Mariomon v1.2.1"),
            Candidate("RUBY", GameKind.EMERALD, NATIVE_RUBY, RUBY_SAPPHIRE, "Pokémon Ruby"),
            Candidate("SAPPHIRE", GameKind.EMERALD, NATIVE_SAPPHIRE, RUBY_SAPPHIRE, "Pokémon Sapphire"),
        )
    }

    fun candidate(id: String): Candidate? = candidates.firstOrNull { it.id == id }

    /** Whether best effort can try a ROM with this header game code at all (a Gen 3 Pokémon base game). */
    fun canTry(gameCode: String): Boolean = candidates.any { c -> c.families.any { gameCode.startsWith(it) } }

    /** The configs for a ROM with this header game code, in [candidates]' order. */
    fun candidatesFor(gameCode: String): List<Candidate> = candidates.filter { c -> c.families.any { gameCode.startsWith(it) } }

    /** Why [match] found nothing, for the companion's notice. */
    enum class Miss { NO_PARTY, NO_MATCH }

    /**
     * What a ROM's text says about one config ([romText]). [coversBy]: per table (species, moves,
     * items, map sections), the share of the ROM's names it knows. [inRom]: the share of its own
     * names the ROM holds (its worst table) - how much of it is this very game. [mapInRom]: the share of its
     * map-section names the ROM holds (null: its map is read from the ROM). [species] / [moves] /
     * [items]: where its name tables are in this ROM, null when they can't be found there.
     * [moreSpecies]: the ROM's species table goes on past the config's last species (a bigger game).
     */
    class TextFit(
        val coversBy: List<Double?>, val inRom: Double, val mapInRom: Double?,
        val species: RomNameTable?, val moves: RomNameTable?, val items: RomNameTable?, val map: RomNameTable?, val moreSpecies: Boolean,
    ) {
        val covers get() = coversBy.filterNotNull().minOrNull() ?: 0.0
        /** Its species sit at their own ids in the ROM, and the ROM has none past its own. */
        val speciesHold get() = species != null && species.agrees >= SPECIES_AGREES_MIN && !moreSpecies
        override fun toString() = "inRom=${"%.2f".format(inRom)} covers=${coversBy.map { it?.let { "%.2f".format(it) } }} map=${mapInRom?.let { "%.2f".format(it) }} sp=$species mv=$moves it=$items ms=$map more=$moreSpecies"
    }

    /** [names] answers single names, [of] each config's [TextFit]. */
    class RomText(val names: RomNames, private val fits: Map<String, TextFit>) {
        fun of(c: Candidate) = fits[c.id] ?: TextFit(emptyList(), 0.0, null, null, null, null, null, false)
    }

    /**
     * Every name [gameCode]'s configs could show, looked for in the ROM in one pass ([RomNames]),
     * and each config's name tables located in it ([RomNameTables]) - once per ROM (well under a
     * second for 32 MB), before [evaluate]. [size]: the ROM's.
     */
    fun romText(reader: MemoryReader, gameCode: String, size: Long): RomText {
        val tries = candidatesFor(gameCode)
        val saved = Globals.save()
        // Per config, per table, its names (id -> key; each config's own language).
        val keyed = try {
            tries.map { c ->
                Globals.set(c)
                tableEntries(c).map { t -> t.mapNotNull { (id, n) -> RomNames.key(n)?.let { id to it } }.toMap() }
            }
        } finally {
            Globals.restore(saved)
        }
        val names = RomNames.scan(reader, size, keyed.flatten().flatMap { it.values }, gameCode.getOrElse(3) { 'E' })
        // The ROM's names per table: every config's keys of that table the ROM holds.
        val found = (0 until TABLES).map { t -> keyed.flatMapTo(HashSet()) { k -> k.getOrNull(t)?.values.orEmpty().filter(names::holds) } }

        // Name tables: inline first, then one pass for pointer tables over every table not found.
        val present = keyed.map { k -> (0 until TABLES).map { t -> RomNameTables.present(names, k.getOrNull(t).orEmpty()) } }
        val located = HashMap<List<Pair<Int, String>>, RomNameTable?>()
        for (p in present.flatten()) located.getOrPut(p) { RomNameTables.locate(reader, names, size, p) }
        val missing = located.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            val pointers = RomNameTables.pointersTo(reader, size, missing.flatMapTo(HashSet()) { RomNameTables.anchorOffsets(names, it) })
            for (p in missing) located[p] = RomNameTables.locate(reader, names, size, p, pointers)
        }

        val fits = HashMap<String, TextFit>()
        tries.forEachIndexed { i, c ->
            val own = keyed[i]
            val covers = (0 until TABLES).map { t ->
                own.getOrNull(t)?.takeIf { found[t].size >= 10 }?.let { o -> found[t].count { it in o.values }.toDouble() / found[t].size }
            }
            val inRom = own.filter { it.size >= 10 }.minOfOrNull { t -> t.values.count(names::holds).toDouble() / t.size } ?: 0.0
            val map = own.getOrNull(3)?.values?.takeIf { it.size >= 10 }?.let { m -> m.count(names::holds).toDouble() / m.size }
            val (sp, mv, it, ms) = (0 until TABLES).map { t -> located[present[i][t]] }
            val last = own[0].keys.maxOrNull() ?: 0
            // Past its last species, names it doesn't have (forms reuse their species' names).
            val ownSpecies = own[0].values.toHashSet()
            val more = sp != null && (last + 1..last + 6).count { id ->
                sp.stringAt(reader, id, size).let { at -> at >= 0 && names.keyAt(at.toInt())?.let { it !in ownSpecies } == true }
            } >= 3
            fits[c.id] = TextFit(covers, inRom, map, sp, mv, it, ms, more)
        }
        return RomText(names, fits)
    }

    private const val TABLES = 4

    /** The names [c] would show, by table and id (species, moves, items, map sections - not for a
     * hack whose map is read from its ROM) - with [Globals] set to it. */
    private fun tableEntries(c: Candidate): List<Map<Int, String>> = listOfNotNull(
        activeSpeciesNames.filterKeys { it > 0 }, activeMoveData.filterKeys { it > 0 }.mapValues { it.value.name },
        activeItemNames.filterKeys { it > 0 },
        activeMapSecData.mapValues { it.value.name }.takeUnless { c.mapFromRom },
    )


    /**
     * How the running game reads through each of [gameCode]'s configs. Reads only (party, bag, a
     * few ROM words per config), on the emulator thread like the sampler; the [activeGame] family
     * of globals the decoders consult is set per try and put back after. [size]: the ROM's.
     */
    fun evaluate(
        reader: MemoryReader, gameCode: String, size: Long, text: RomText,
        tries: List<Candidate> = candidatesFor(gameCode),
    ): Verdict {
        val saved = Globals.save()
        val fits = try {
            tries.map { c ->
                Globals.set(c)
                runCatching { fit(reader, c, size, text) }.getOrElse { Fit(c, Part.entries.toSet(), text.of(c), null, null, null) }
            }
        } finally {
            Globals.restore(saved)
            resetNativeBagCache()
        }
        val ranked = fits.sortedWith(
            compareByDescending<Fit> { it.sure }
                .thenByDescending { it.party != null && it.namesHold }
                // Among those, the address-checked parts holding say "this very build".
                .thenBy { if (it.party != null && it.namesHold) it.off.size else 0 }
                // A party read whose every shown name the ROM confirms beats name shares alone: Recharged
                // Yellow's ROM also carries other languages' name tables (French species after its own,
                // Portuguese moves), so no config's species "cover" it - its own still reads its party name for name.
                .thenByDescending { it.party != null && it.shownHolds }
                .thenByDescending { it.text.coversBy.firstOrNull() ?: 0.0 }
                .thenByDescending { if (it.shownOf == null || it.shownOf == 0) -1.0 else it.shown!!.toDouble() / it.shownOf }
                .thenByDescending { it.namesHold }
                .thenBy { it.off.size }
                .thenByDescending { it.party ?: -1 }
                .thenByDescending { it.text.covers + (it.text.mapInRom ?: 0.0) }
                // A later config is a newer build of the same game (SoulGold v1.2 after v1.1).
                .thenByDescending { tries.indexOf(it.candidate) },
        )
        return Verdict(ranked, sawParty = fits.any { it.party != null })
    }

    /** Debugging aid: what [c] would show for the running game that the ROM doesn't hold, and ids that don't read. */
    internal fun explainShown(reader: MemoryReader, c: Candidate, size: Long, text: RomText): String {
        val saved = Globals.save()
        try {
            Globals.set(c)
            val t = readParty(reader, c) ?: return "no party"
            val tf = text.of(c)
            val mons = t.party.take(t.partyCount).filter { !it.isEgg }
            val miss = shownCheck(reader, c, t, tf, text.names, size)
            fun bad(table: RomNameTable?, ids: List<Int>, name: (Int) -> String?) = if (table == null) "-" else
                ids.filter { id -> name(id)?.let(RomNames::key)?.let { !table.reads(reader, text.names, id, it, size) } ?: false }.map { "$it=${name(it)}" }.toString()
            return "      missing: $miss\n      bad species: ${bad(tf.species, mons.map { it.species }) { activeSpeciesNames[it] }}" +
                " moves: ${bad(tf.moves, mons.flatMap { m -> m.moves.filter { it != 0 } }.distinct()) { activeMoveData[it]?.name }}" +
                " items: ${bad(tf.items, t.items.map { it.itemId }.filter { it > 0 }.distinct()) { activeItemNames[it] }}"
        } finally {
            Globals.restore(saved)
            resetNativeBagCache()
        }
    }

    /** Debugging aid: [c]'s tables' shares and a few names the ROM doesn't hold, per table. */
    internal fun explain(c: Candidate, names: RomNames): String {
        val share = { list: Collection<String?> -> share(list, names) }
        val saved = Globals.save()
        try {
            Globals.set(c)
            return listOf("species", "moves", "items", "map").zip(tableEntries(c).map { it.values } + listOf(emptyList<String>())).joinToString("\n") { (label, list) ->
                val (sh, n) = share(list)
                "      $label ${"%.2f".format(sh)} of $n, missing: " + list.filter { names.has(it) == false }.take(12).joinToString(" | ")
            }
        } finally {
            Globals.restore(saved)
        }
    }

    /** The SURE config, or why there's none - [evaluate] for callers that only want that. */
    fun match(reader: MemoryReader, gameCode: String, size: Long, text: RomText = romText(reader, gameCode, size)): Pair<Match?, Miss?> {
        val v = evaluate(reader, gameCode, size, text)
        return v.sure?.match() to v.miss
    }

    private fun fit(reader: MemoryReader, c: Candidate, size: Long, text: RomText): Fit {
        val t = runCatching { readParty(reader, c) }.getOrNull()
        val species = t?.party?.take(t.partyCount)?.filter { !it.isEgg }?.map { it.species }.orEmpty().ifEmpty { listOf(1, 4, 7, 25) }
        val items = t?.items?.map { it.itemId }?.filter { it > 0 }?.take(20).orEmpty() + listOf(1, 2, 3, 4)

        val off = mutableSetOf<Part>()
        fun part(p: Part, present: Boolean, holds: () -> Boolean) {
            if (present && !runCatching(holds).getOrDefault(false)) off += p
        }
        part(Part.DEX, c.cfg.pokedex != null) { dexHolds(reader, c.cfg.pokedex!!) }
        part(Part.GUIDE, c.cfg.guideTables != null) { guideTablesMatchRom(reader, c.cfg.guideTables!!) }
        part(Part.ITEM_TEXT, c.cfg.itemDescs != null) { RomItemText.matchesRom(reader, c.cfg.itemDescs!!) }
        part(Part.MON_ICONS, c.cfg.monIconTable != 0L) { species.all { monIconHolds(reader, c.cfg.iconTables, it, size) } }
        part(Part.ITEM_ICONS, c.cfg.itemIconTable != 0L) { items.distinct().all { itemIconHolds(reader, c.cfg.iconTables, it, size) } }

        val tf = text.of(c)
        if (t == null) return Fit(c, off, tf, null, null, null)
        var score = t.partyCount * 10
        t.money?.let { if (it in 0..c.cfg.maxMoney) score += 2 }
        if (t.items.isNotEmpty() && t.items.all { it.itemId > 0 && it.quantity in 1..999 }) score += 3
        score += 4 * (Part.entries.count { it !in off })
        val (good, of) = shownCheck(reader, c, t, tf, text.names, size)
        return Fit(c, off, tf, score, good, of)
    }

    /**
     * How many of what [c] would show for [t] the ROM confirms, of how many: each name at its own id
     * in the ROM's table when [tf] found one, else anywhere in the ROM. A name the config's tables
     * don't have is a miss (it would show "Species#1234"); one that can't be compared isn't counted.
     */
    private fun shownCheck(reader: MemoryReader, c: Candidate, t: Telemetry, tf: TextFit, names: RomNames, size: Long): Pair<Int, Int> {
        var good = 0
        var of = 0
        fun check(table: RomNameTable?, id: Int, name: String?) {
            if (name == null) { of++; return }
            val key = RomNames.key(name) ?: return
            of++
            if (if (table != null) table.reads(reader, names, id, key, size) else names.holds(key)) good++
        }
        val mons = t.party.take(t.partyCount).filter { !it.isEgg && !isVanillaEgg(it.species) }
        mons.forEach { check(tf.species, it.species, activeSpeciesNames[it.species]) }
        mons.flatMap { m -> m.moves.filter { it != 0 } }.distinct().forEach { check(tf.moves, it, activeMoveData[it]?.name) }
        t.items.map { it.itemId }.filter { it > 0 }.distinct().take(30).forEach { check(tf.items, it, activeItemNames[it]) }
        if (!c.mapFromRom && t.regionMapSectionId in activeMapSecData) check(tf.map, t.regionMapSectionId, activeMapSecData[t.regionMapSectionId]?.name)
        return good to of
    }

    /** The real party read through [c] (1-6 plausible Pokémon), or null. */
    private fun readParty(reader: MemoryReader, c: Candidate): Telemetry? {
        resetNativeBagCache()
        val t = readNativeTelemetry(reader, c.cfg)
        val n = t.partyCount
        if (n !in 1..PARTY_SIZE || t.party.size < n) return null
        val mons = t.party.take(n)
        if (!mons.all { it.species in 1..MAX_SPECIES && it.level in 1..100 && it.maxHp in 1..999 && it.hp in 0..it.maxHp }) return null
        return t
    }

    /** The share of [list] the ROM holds and how many could be checked (a null name counts as a miss). */
    private fun share(list: Collection<String?>, names: RomNames): Pair<Double, Int> {
        var found = 0
        var checked = 0
        for (n in list) {
            val has = if (n == null) false else names.has(n) ?: continue
            checked++
            if (has) found++
        }
        return (if (checked == 0) 0.0 else found.toDouble() / checked) to checked
    }

    /**
     * [species]' party icon is real through [t]: its tiles and palette pointers land in the ROM,
     * the palette is BGR555 (bit 15 clear) and the first 32x32 frame is part transparent, part not
     * (what code or other data under a wrong pointer almost never looks like).
     */
    private fun monIconHolds(reader: MemoryReader, t: IconTables, species: Int, size: Long): Boolean {
        val tiles = u32(reader.readCoreMemory(t.monIconTable + species.toLong() * t.monIconStride, 4))
        val pal = if (t.monIconPalettes != 0L) {
            u32(reader.readCoreMemory(t.monIconPalettes + species.toLong() * t.monIconStride, 4))
        } else {
            val idx = (reader.readCoreMemory(t.monIconPaletteIndices + species.toLong() * t.monPalIdxStride, 1)[0].toInt() and t.monPalIdxMask)
            u32(reader.readCoreMemory(t.monIconPaletteTable + idx * 8L, 4))
        }
        if (!inRom(tiles, 512, size) || !inRom(pal, 32, size)) return false
        val p = reader.readCoreMemory(pal, 32)
        if ((0 until 16).any { p[it * 2 + 1].toInt() and 0x80 != 0 }) return false
        val frame = reader.readCoreMemory(tiles, 512)
        var clear = 0
        for (b in frame) {
            if (b.toInt() and 0x0F == 0) clear++
            if (b.toInt() and 0xF0 == 0) clear++
        }
        return clear in 256 until 1024
    }

    /** [item]'s bag icon is real through [t]: tiles (and a compressed palette) start as LZ77 / smol data in the ROM. */
    private fun itemIconHolds(reader: MemoryReader, t: IconTables, item: Int, size: Long): Boolean {
        val extra = item - t.extraItemFirst
        val entry = if (t.extraItemIconTable != 0L && extra in 0 until t.extraItemCount) {
            reader.readCoreMemory(t.extraItemIconTable + extra.toLong() * t.extraItemStride, 8)
        } else {
            reader.readCoreMemory(t.itemIconTable + item.toLong() * t.itemIconStride, 8)
        }
        val tiles = u32(entry, 0)
        val pal = u32(entry, 4)
        if (!inRom(tiles, 8, size) || !inRom(pal, 8, size)) return false
        val head = reader.readCoreMemory(tiles, 8)
        if (head[0].toInt() != 0x10 && !Smol.isSmol(head)) return false
        return !t.itemPalCompressed || reader.readCoreMemory(pal, 1)[0].toInt() == 0x10
    }

    private fun u32(b: ByteArray, off: Int = 0): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun inRom(ptr: Long, len: Int, size: Long) = ptr >= 0x08000000L && ptr + len <= 0x08000000L + size

    /** [pokedexMatchesRom] for the dex-entry tables; an expansion game's gSpeciesInfo: BULBASAUR's
     * national number, height and weight where [t] says they are. */
    private fun dexHolds(reader: MemoryReader, t: PokedexTables): Boolean {
        val x = t.expansion ?: return pokedexMatchesRom(reader, t)
        val e = reader.readCoreMemory(t.speciesInfo + x.stride + x.natDexOff, 6)
        return u16le(e, 0) == 1 && u16le(e, 2) == 7 && u16le(e, 4) == 69
    }

    /** Above every species id the configs know (Elite Redux's egg is 2901). */
    private const val MAX_SPECIES = 4095

    /** The globals the decoders read the running game's tables by ([activeGame] and friends). */
    private object Globals {
        class Saved(
            val game: GameKind, val lang: Char, val code: String, val sg12: Boolean, val am141: Boolean, val mm13: Boolean, val frHack: HackText?,
            val q084: Boolean, val emHack: HackText?, val er3: Boolean,
        )

        fun save() = Saved(activeGame, romLanguage, romGameCode, soulGoldV12, amethystV141, mariomonV13, fireRedHack, quetzalV084, emeraldHack, eliteRedux265_3)
        fun restore(s: Saved) {
            activeGame = s.game; romLanguage = s.lang; romGameCode = s.code; soulGoldV12 = s.sg12; amethystV141 = s.am141
            mariomonV13 = s.mm13; fireRedHack = s.frHack; quetzalV084 = s.q084; emeraldHack = s.emHack; eliteRedux265_3 = s.er3
        }
        fun set(c: Candidate) {
            activeGame = c.kind
            romLanguage = c.cfg.language
            romGameCode = c.cfg.gameCode
            soulGoldV12 = c.cfg === NATIVE_SOULGOLD_V1_2 || c.cfg === NATIVE_SOULGOLD_V1_2B || c.cfg === NATIVE_SOULGOLD_V1_2C ||
                c.cfg === NATIVE_SOULGOLD_V1_2_1
            amethystV141 = c.cfg === NATIVE_AMETHYST_V1_4_1
            fireRedHack = fireRedHackFor(c.cfg)
            emeraldHack = emeraldHackFor(c.cfg)
            mariomonV13 = c.cfg === NATIVE_MARIOMON_V1_3 || c.cfg === NATIVE_MARIOMON_V1_2_2 || c.cfg === NATIVE_MARIOMON_V1_2_1
            quetzalV084 = c.cfg.quetzalV084
            eliteRedux265_3 = c.cfg === NATIVE_ELITE_REDUX_V2_65_3B
        }
    }
}

/**
 * A match the player may choose to share (BestEffortShare, after the notice asks): about the ROM
 * only - nothing about the player, the device or the file's name.
 */
data class BestEffortReport(
    val sha1: String,
    val size: Long,
    val gameCode: String,
    val revision: Int,
    /** [BestEffort.Candidate.id]. */
    val matchedAs: String,
    val full: Boolean,
    val off: Set<BestEffort.Part>,
)

/**
 * What best effort found for each ROM, by the ROM's SHA-1 (an archive: the ROM inside):
 * `<sha1>\t<candidate id>\t<FULL|PARTIAL>\t<parts off, comma-separated>\t<source>\t<1 = verified>`
 * in `filesDir/best-effort.tsv`. A 4-column row predates [Source]: AUTO, not verified, so the
 * stricter checks look at it again. [dir] is set by the activities before anything reads it.
 */
object BestEffortStore {
    /** AUTO: best effort chose it. PICKED: the player did (WHICH GAME IS THIS?). NONE: the player said
     * none of them - the ROM stays NOT SUPPORTED, no picker until they ask for it. */
    enum class Source { AUTO, PICKED, NONE }

    /** [verified]: a party read through it and the names held, or the player kept it over a doubt. */
    class Entry(val sha1: String, val id: String, val full: Boolean, val off: Set<BestEffort.Part>, val source: Source = Source.AUTO, val verified: Boolean = true)

    @Volatile var dir: File? = null
    /** Called (any thread) after [save] / [decline] / [forget] with the SHA-1 changed: the library's verdict cache drops it.
     * Always outside this store's lock: it takes RomFolder's, whose [RomFolder.scan] reads this store under it. */
    @Volatile var onChanged: ((String) -> Unit)? = null

    private fun file() = dir?.let { File(it, "best-effort.tsv") }

    @Synchronized
    private fun rows(): Map<String, Entry> = runCatching {
        file()?.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) return@mapNotNull null
            val source = p.getOrNull(4)?.let { n -> Source.entries.firstOrNull { it.name == n } } ?: Source.AUTO
            if (source != Source.NONE && BestEffort.candidate(p[1]) == null) return@mapNotNull null
            val off = p.getOrNull(3).orEmpty().split(',').mapNotNull { n -> BestEffort.Part.entries.firstOrNull { it.name == n } }.toSet()
            p[0] to Entry(p[0], p[1], p[2] == "FULL", off, source, verified = p.size >= 6 && p[5] == "1")
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Every ROM's kept match (not the ones the player said NONE OF THESE to). */
    fun all(): Map<String, Entry> = rows().filterValues { it.source != Source.NONE }

    fun load(sha1: String?): Entry? = sha1?.let { all()[it.lowercase()] }

    /** The player said NONE OF THESE for this ROM. */
    fun declined(sha1: String?): Boolean = sha1 != null && rows()[sha1.lowercase()]?.source == Source.NONE

    fun save(sha1: String, m: BestEffort.Match, source: Source = Source.AUTO, verified: Boolean = true) {
        val k = sha1.lowercase()
        synchronized(this) { write(rows() + (k to Entry(k, m.candidate.id, m.full, m.off, source, verified))) }
        onChanged?.invoke(sha1)
    }

    /** NONE OF THESE: no match, and no asking again until the player opens the picker. */
    fun decline(sha1: String) {
        val k = sha1.lowercase()
        synchronized(this) { write(rows() + (k to Entry(k, "-", false, emptySet(), Source.NONE, true))) }
        onChanged?.invoke(sha1)
    }

    fun forget(sha1: String) {
        synchronized(this) { write(rows() - sha1.lowercase()) }
        onChanged?.invoke(sha1)
    }

    private fun write(entries: Map<String, Entry>) {
        val f = file() ?: return
        runCatching {
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(entries.values.joinToString("") { e ->
                "${e.sha1}\t${e.id}\t${if (e.full) "FULL" else "PARTIAL"}\t${e.off.joinToString(",") { it.name }}\t${e.source.name}\t${if (e.verified) 1 else 0}\n"
            })
            tmp.renameTo(f)
        }
    }

    /** [entry]'s config as a [BestEffort.Match] would read with it. */
    fun matchOf(entry: Entry): BestEffort.Match? =
        BestEffort.candidate(entry.id)?.let { BestEffort.Match(it, entry.full, entry.off, 0) }
}
