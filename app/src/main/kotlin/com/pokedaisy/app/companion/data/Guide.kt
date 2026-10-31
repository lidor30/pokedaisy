package com.pokedaisy.app.companion.data

/**
 * The GUIDE tab's content: a few short pages per game (how the game works,
 * where the useful things are, what to do when stuck) plus pages built live
 * from the ROM and the save (HERE, NEXT BOSS, EVOLUTIONS). Every entry is
 * spoiler-safe until tapped - its [GuideEntry.title] is always shown, the
 * [GuideEntry.hint] after one tap and the [GuideEntry.answer] after another
 * (or straight away when there's no hint).
 *
 * Hand-written content is in our own words, from each game's own data (for
 * FireRed / Emerald, the decomp's map scripts), never copied from community
 * docs. It's AI-assisted, so the tab says so before it's first read
 * ([GameGuide.verified] false adds that it hasn't been checked in-game).
 */
data class GuideEntry(
    val title: String,
    val answer: String,
    val hint: String? = null,
    /** How to tell the player already has this (WHERE IS); null = nothing to have. */
    val have: Have? = null,
    /** Short text right of the title, always shown (levels / odds on HERE). */
    val detail: String? = null,
    /** Species whose icon leads the row; 0 = none. */
    val icon: Int = 0,
    /** Resolved [have] (pages built from the save set it directly). */
    val owned: Boolean = false,
    /** Area names (as the game's map shows them) this is about - HERE lists it there under TO DO. */
    val areas: List<String> = emptyList(),
    /** Whether it holds on a randomized game ([gameRandomized]). */
    val randomized: Randomized = Randomized.ANY,
) {
    /** Taps until the answer shows: one straight to it, two via the hint; 0 = nothing hidden. */
    val steps: Int get() = when {
        answer.isEmpty() -> 0
        hint == null -> 1
        else -> 2
    }
}

/** Whether a hand-written entry or section holds on a randomized game ([gameRandomized], [RandomizedRetail]). */
enum class Randomized {
    /** Either way: the story, key items, HMs. */
    ANY,
    /** Only unrandomized: it names a Pokémon or an item a randomizer moves (a gift, a legendary, an item ball). */
    HIDE,
    /** Only randomized: about the randomized game itself. */
    ONLY,
}

/** [note]: a line under the heading (a boss's ace level vs. yours). */
data class GuideSection(
    val heading: String,
    val entries: List<GuideEntry>,
    val note: String? = null,
    val randomized: Randomized = Randomized.ANY,
)

data class GuidePage(val title: String, val sections: List<GuideSection>) {
    val size: Int get() = sections.sumOf { it.entries.size }
}

/** [g]'s pages as they read on a game that is ([randomized]) or isn't randomized: what doesn't hold there left out. */
private fun shownPages(g: GameGuide, randomized: Boolean): List<GuidePage> {
    fun shows(r: Randomized) = r == Randomized.ANY || (r == Randomized.ONLY) == randomized
    return g.pages.map { p ->
        p.copy(sections = p.sections.filter { shows(it.randomized) }.map { s -> s.copy(entries = s.entries.filter { shows(it.randomized) }) }
            .filter { it.entries.isNotEmpty() })
    }.filter { it.sections.isNotEmpty() }
}

data class GameGuide(
    val pages: List<GuidePage>,
    val verified: Boolean = true,
    /** The boss order NEXT BOSS walks through; empty = no such page. */
    val bosses: List<Boss> = emptyList(),
    /** [bosses] in the order for the map group the player is on (Quetzal: its three regions are
     * separate campaigns, the one you're in first); null = [bosses]. */
    val bossesFor: ((mapGroup: Int) -> List<Boss>)? = null,
    /** HERE's wild Pokémon and NEXT BOSS's teams from tables of the game's own instead of the GBA ROM tables
     * [GuideTables] reads (a Game Boy game); null = those. */
    val world: GuideWorld? = null,
) {
    /** These pages as an unrandomized ([plain]) and a randomized ([randomizedView]) game reads them ([Randomized]); this
     * guide itself where it has no lines of either kind (most games). */
    val plain: GameGuide by lazy { if (marked) copy(pages = shownPages(this, randomized = false)) else this }
    val randomizedView: GameGuide by lazy { if (marked) copy(pages = shownPages(this, randomized = true)) else this }
    private val marked: Boolean
        get() = pages.any { p -> p.sections.any { s -> s.randomized != Randomized.ANY || s.entries.any { it.randomized != Randomized.ANY } } }
}

/**
 * A game whose wild Pokémon and boss teams the guide has as data of its own (Polished Crystal's, generated from its
 * release ROMs): what the save would meet, from its [SaveProgress] (the time of day, the badges held).
 */
interface GuideWorld {
    /** Map [group].[num]'s wild Pokémon now (empty lists when it has none). */
    fun encounters(group: Int, num: Int, progress: SaveProgress?): MapEncounters
    /** Trainer [trainer]'s team, as [Boss.trainer] names it. */
    fun party(trainer: Int, progress: SaveProgress?): List<TrainerMon>
    /** The time of day HERE's lists are for (an English word: MORNING, DAY, EVENING, NIGHT); null = no clock. */
    fun clockNote(progress: SaveProgress?): String? = null
}

/** Something the save can show the player already has. */
sealed interface Have {
    /** An event flag the game sets when it's handed over (key items that aren't bag items, like the RUNNING SHOES). */
    data class Flag(val flag: Int) : Have
    /** An item in the bag (HMs and key items can't be tossed). */
    data class Item(val item: Int) : Have
    /** Any of these national dex numbers caught. */
    data class Caught(val national: List<Int>) : Have {
        constructor(vararg n: Int) : this(n.toList())
    }
}

/** What the save says the player has, for [Have] checks; any part may be missing (null = unknown). */
class GuideContext(val progress: SaveProgress?, val bag: Set<Int>?, val caught: Set<Int>?) {
    fun has(h: Have?): Boolean = when (h) {
        null -> false
        is Have.Flag -> progress?.flag(h.flag) == true
        is Have.Item -> bag?.contains(h.item) == true
        is Have.Caught -> caught != null && h.national.any { it in caught }
    }
}

/**
 * A boss fight NEXT BOSS can point at. [done] = the flag set once it's won
 * (a badge, or an Elite Four room's own flag); [trainer] picks the trainer
 * id from the save (the champion depends on the starter, the Elite Four on
 * whether rematches are on).
 */
data class Boss(
    val title: String,
    val where: String,
    val done: Int,
    /** Teams the game picks between at run time that the save can't settle
     * (Unbound's difficulty): each shown under its label. Empty = [trainer]. */
    val variants: List<Pair<String, Int>> = emptyList(),
    /** [variants] that follow the save (Amethyst's RAINE brings a team for
     * the badges you hold, in each difficulty); null = [variants]. */
    val variantsFor: ((SaveProgress) -> List<Pair<String, Int>>)? = null,
    /** When beating it isn't a single flag (Heart and Soul's Elite Four count
     * up a var during a League run); null = [done]. */
    val doneIf: ((SaveProgress) -> Boolean)? = null,
    val trainer: (SaveProgress) -> Int,
) {
    fun isDone(p: SaveProgress): Boolean = doneIf?.invoke(p) ?: p.flag(done)

    /** The teams to show: (label or null, trainer id). */
    fun teams(p: SaveProgress): List<Pair<String?, Int>> =
        (variantsFor?.invoke(p) ?: variants).ifEmpty { null }?.map { it.first to it.second } ?: listOf(null to trainer(p))

    /** Every trainer id this boss may be, for loading their teams. */
    fun trainers(p: SaveProgress): List<Int> = teams(p).map { it.second }
}

/**
 * Which guide (hand-written text and HERE's area data) a game gets. LeafGreen
 * runs as FireRed and Ruby / Sapphire as Emerald - same species, items and
 * map sections - but their guides differ, so each game's [GuideTables] names
 * its own.
 */
enum class GuideId {
    FIRERED, LEAFGREEN, EMERALD, RUBY, SAPPHIRE, HEART_AND_SOUL, UNBOUND, RADICAL_RED, ODYSSEY, GAIA, AMETHYST, AMETHYST_V141, CELIA, GLAZED,
    IMPERIUM, QUETZAL, LAZARUS, SEAGLASS, TMT2, SOULGOLD, ORANGE_ISLANDS,
    // Recharged Yellow v1.9.7, and its earlier releases with their own area data (same guide text).
    RECHARGED_YELLOW, RECHARGED_YELLOW_V180, RECHARGED_YELLOW_V190, RECHARGED_YELLOW_V194,
    MARIOMON,
    ULTRA_VIOLET, SWORD_SHIELD, ROCKET_EDITION, ASH_GRAY, GHOST_GREY, ELITE_REDUX, TEAM_ROCKET, WISHES, DREAMSTONE,
    // Quetzal Alpha 0.8.4: Hoenn alone (Alpha 9 added Johto and Kanto).
    QUETZAL_V084,
    GS_CHRONICLES,
    RECHARGED_EMERALD,
    EMERALD_LEGACY,
    POLISHED_CRYSTAL,
    // The DS games: HeartGold and SoulSilver share their text, not their wild Pokémon.
    PLATINUM, HEARTGOLD, SOULSILVER,
    // The Game Boy games: Yellow (all five languages), Red, Blue, retail Crystal.
    YELLOW, RED, BLUE, CRYSTAL,
    // Gen 5 (Gen5World, from the cart).
    BLACK, WHITE, BLACK2, WHITE2,
}

/** [live]'s guide; else by [game] (a config whose tables didn't check out has no live tables). */
fun guideId(game: GameKind?, live: GuideTables?): GuideId? = live?.guide ?: when (game) {
    GameKind.FIRERED -> GuideId.FIRERED
    GameKind.EMERALD -> GuideId.EMERALD
    // A Game Boy game: no GBA tables; its guide carries its own (GameGuide.world).
    GameKind.POLISHED_CRYSTAL -> GuideId.POLISHED_CRYSTAL
    GameKind.YELLOW -> GuideId.YELLOW
    GameKind.RED -> GuideId.RED
    GameKind.BLUE -> GuideId.BLUE
    GameKind.CRYSTAL -> GuideId.CRYSTAL
    // A DS game: its guide carries its own tables too (Gen4World).
    GameKind.PLATINUM -> GuideId.PLATINUM
    GameKind.HEARTGOLD -> GuideId.HEARTGOLD
    GameKind.SOULSILVER -> GuideId.SOULSILVER
    GameKind.BLACK -> GuideId.BLACK
    GameKind.WHITE -> GuideId.WHITE
    GameKind.BLACK2 -> GuideId.BLACK2
    GameKind.WHITE2 -> GuideId.WHITE2
    else -> null
}

/** The hand-written guide [id] has, if one has been written, as it reads on a game that is ([randomized]: the running
 * one by default, [gameRandomized]) or isn't randomized; null = every line, whichever ([Randomized]). */
fun gameGuide(id: GuideId?, randomized: Boolean? = gameRandomized): GameGuide? = wholeGuide(id)?.let {
    when (randomized) {
        null -> it
        true -> it.randomizedView
        false -> it.plain
    }
}

private fun wholeGuide(id: GuideId?): GameGuide? = when (id) {
    GuideId.FIRERED -> GUIDE_FIRERED
    GuideId.LEAFGREEN -> GUIDE_LEAFGREEN
    GuideId.EMERALD -> GUIDE_EMERALD
    GuideId.RUBY -> GUIDE_RUBY
    GuideId.SAPPHIRE -> GUIDE_SAPPHIRE
    GuideId.HEART_AND_SOUL -> GUIDE_HEART_AND_SOUL
    GuideId.UNBOUND -> GUIDE_UNBOUND
    GuideId.RADICAL_RED -> GUIDE_RADICAL_RED
    GuideId.ODYSSEY -> GUIDE_ODYSSEY
    GuideId.GAIA -> GUIDE_GAIA
    GuideId.AMETHYST, GuideId.AMETHYST_V141 -> GUIDE_AMETHYST
    GuideId.CELIA -> GUIDE_CELIA
    GuideId.GLAZED -> GUIDE_GLAZED
    GuideId.IMPERIUM -> GUIDE_IMPERIUM
    GuideId.QUETZAL -> GUIDE_QUETZAL
    GuideId.QUETZAL_V084 -> GUIDE_QUETZAL_V084
    GuideId.LAZARUS -> GUIDE_LAZARUS
    GuideId.SEAGLASS -> GUIDE_SEAGLASS
    GuideId.TMT2 -> GUIDE_TMT2
    GuideId.SOULGOLD -> GUIDE_SOULGOLD
    GuideId.ORANGE_ISLANDS -> GUIDE_ORANGE_ISLANDS
    GuideId.ULTRA_VIOLET -> GUIDE_ULTRA_VIOLET
    GuideId.ROCKET_EDITION -> GUIDE_ROCKET_EDITION
    GuideId.ASH_GRAY -> GUIDE_ASH_GRAY
    GuideId.GHOST_GREY -> GUIDE_GHOST_GREY
    GuideId.ELITE_REDUX -> GUIDE_ELITE_REDUX
    GuideId.TEAM_ROCKET -> GUIDE_TEAM_ROCKET
    GuideId.WISHES -> GUIDE_WISHES
    GuideId.DREAMSTONE -> GUIDE_DREAMSTONE
    GuideId.SWORD_SHIELD -> GUIDE_SWORD_SHIELD
    GuideId.GS_CHRONICLES -> GUIDE_GS_CHRONICLES
    GuideId.RECHARGED_YELLOW, GuideId.RECHARGED_YELLOW_V180, GuideId.RECHARGED_YELLOW_V190,
    GuideId.RECHARGED_YELLOW_V194 -> GUIDE_RECHARGED_YELLOW
    GuideId.MARIOMON -> GUIDE_MARIOMON
    GuideId.RECHARGED_EMERALD -> GUIDE_RECHARGED_EMERALD
    GuideId.EMERALD_LEGACY -> GUIDE_EMERALD_LEGACY
    GuideId.POLISHED_CRYSTAL -> GUIDE_POLISHED_CRYSTAL
    GuideId.PLATINUM -> GUIDE_PLATINUM
    GuideId.HEARTGOLD -> GUIDE_HEARTGOLD
    GuideId.SOULSILVER -> GUIDE_SOULSILVER
    GuideId.BLACK -> GUIDE_BLACK
    GuideId.WHITE -> GUIDE_WHITE
    GuideId.BLACK2 -> GUIDE_BLACK2
    GuideId.WHITE2 -> GUIDE_WHITE2
    GuideId.YELLOW -> GUIDE_YELLOW
    GuideId.RED -> GUIDE_RED
    GuideId.BLUE -> GUIDE_BLUE
    GuideId.CRYSTAL -> GUIDE_CRYSTAL
    null -> null
}

// Small builders so the per-game files read like the content they hold.
internal fun page(title: String, vararg sections: GuideSection) = GuidePage(title, sections.toList())
internal fun section(heading: String, vararg entries: GuideEntry) = GuideSection(heading, entries.toList())
/** A section that holds only on an unrandomized game, or only on a randomized one ([Randomized]). */
internal fun section(heading: String, randomized: Randomized, vararg entries: GuideEntry) =
    GuideSection(heading, entries.toList(), randomized = randomized)
internal fun entry(
    title: String, answer: String, hint: String? = null, have: Have? = null, areas: List<String> = emptyList(),
    randomized: Randomized = Randomized.ANY,
) = GuideEntry(title, answer, hint, have, areas = areas, randomized = randomized)
