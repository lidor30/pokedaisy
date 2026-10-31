package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.i18n.trGuide
import java.util.concurrent.ConcurrentHashMap

/**
 * What's around an area, for the GUIDE's HERE page: its items and what its
 * people hand out, generated from each game's decomp by
 * scripts/gen_guide_areas.py (the GuideAreas*Gen.kt files) - item balls and
 * hidden items from the maps' events, gifts and trades from their scripts.
 * An area is a region map section, so a city's houses and marts count with
 * the city. [flag] is the event flag the game sets once it's picked up /
 * received (0 = none known). KEY is a GIFT of a key item - only tagged for
 * games whose WHERE IS page is generated from this data ([generatedWhereIs]).
 */
enum class AreaKind { ITEM, HIDDEN, GIFT, KEY, MON, EGG, TRADE }

data class AreaThing(
    val kind: AreaKind,
    /** Item id (ITEM, HIDDEN, GIFT) or species (MON, EGG, TRADE: the one you get). */
    val id: Int,
    val flag: Int,
    val qty: Int,
    /** TRADE: the species they want for it. */
    val wants: Int,
    /** The sub-map it's on, e.g. "DEVON CORP 3F"; "" = the area's main map. */
    val where: String,
)

private val areaCache = ConcurrentHashMap<GuideId, Map<Int, List<AreaThing>>>()

private fun rawAreas(guide: GuideId): List<String> = when (guide) {
    GuideId.FIRERED -> GUIDE_AREAS_FIRERED_RAW
    GuideId.LEAFGREEN -> GUIDE_AREAS_LEAFGREEN_RAW
    GuideId.EMERALD -> GUIDE_AREAS_EMERALD_RAW
    GuideId.EMERALD_LEGACY -> GUIDE_AREAS_EMERALD_LEGACY_RAW
    // Keyed by landmark (the map section the companion names: gen2Landmark); trades' flags are wTradeFlags bits.
    GuideId.POLISHED_CRYSTAL -> GUIDE_AREAS_POLISHED_CRYSTAL_RAW
    // Ruby and Sapphire share their maps (pokeruby builds both from one tree).
    GuideId.RUBY, GuideId.SAPPHIRE -> GUIDE_AREAS_RUBY_SAPPHIRE_RAW
    GuideId.HEART_AND_SOUL -> GUIDE_AREAS_HEART_AND_SOUL_RAW
    GuideId.UNBOUND -> GUIDE_AREAS_UNBOUND_RAW
    GuideId.RADICAL_RED -> GUIDE_AREAS_RADICAL_RED_RAW
    GuideId.ODYSSEY -> GUIDE_AREAS_ODYSSEY_RAW
    GuideId.GAIA -> GUIDE_AREAS_GAIA_RAW
    GuideId.AMETHYST -> GUIDE_AREAS_AMETHYST_RAW
    GuideId.AMETHYST_V141 -> GUIDE_AREAS_AMETHYST_V141_RAW
    GuideId.CELIA -> GUIDE_AREAS_CELIA_RAW
    GuideId.ORANGE_ISLANDS -> GUIDE_AREAS_ORANGE_ISLANDS_RAW
    GuideId.ULTRA_VIOLET -> GUIDE_AREAS_ULTRA_VIOLET_RAW
    GuideId.ROCKET_EDITION -> GUIDE_AREAS_ROCKET_EDITION_RAW
    GuideId.ASH_GRAY -> GUIDE_AREAS_ASH_GRAY_RAW
    GuideId.GHOST_GREY -> GUIDE_AREAS_GHOST_GREY_RAW
    GuideId.SWORD_SHIELD -> GUIDE_AREAS_SWORD_SHIELD_RAW
    GuideId.GS_CHRONICLES -> GUIDE_AREAS_GS_CHRONICLES_RAW
    // Each Recharged Yellow release moved items and gifts around: its own data.
    GuideId.RECHARGED_YELLOW -> GUIDE_AREAS_RECHARGED_YELLOW_RAW
    GuideId.RECHARGED_YELLOW_V180 -> GUIDE_AREAS_RECHARGED_YELLOW_V180_RAW
    GuideId.RECHARGED_YELLOW_V190 -> GUIDE_AREAS_RECHARGED_YELLOW_V190_RAW
    GuideId.RECHARGED_YELLOW_V194 -> GUIDE_AREAS_RECHARGED_YELLOW_V194_RAW
    // No area data yet: HERE lists the wild Pokémon alone.
    GuideId.GLAZED, GuideId.IMPERIUM, GuideId.QUETZAL, GuideId.QUETZAL_V084, GuideId.LAZARUS, GuideId.SEAGLASS, GuideId.TMT2, GuideId.SOULGOLD,
    GuideId.MARIOMON, GuideId.RECHARGED_EMERALD, GuideId.ELITE_REDUX, GuideId.TEAM_ROCKET,
    GuideId.WISHES -> emptyList()
    GuideId.DREAMSTONE -> emptyList()
    // The DS games: item balls and hidden items (scripts/gen_gen4_guide.py), keyed by place - see [areaSection].
    GuideId.PLATINUM -> PT_AREAS_RAW
    GuideId.HEARTGOLD, GuideId.SOULSILVER -> HG_AREAS_RAW
    // Black's and White's item balls (scripts/gen_gen5_guide.py), keyed by the zone's place.
    GuideId.BLACK -> BLACK_AREAS_RAW
    GuideId.WHITE -> WHITE_AREAS_RAW
    // Black 2's and White 2's (the same item balls; their places are mapPlaceIdsBlack2's).
    GuideId.BLACK2 -> BLACK2_AREAS_RAW
    GuideId.WHITE2 -> WHITE2_AREAS_RAW
    // The Game Boy games (scripts/gen_gen1_guide.py, gen_crystal_guide.py): Gen 1's keyed by wCurMap, regrouped by
    // [gen1Area]; Crystal's by landmark, like Polished Crystal's.
    GuideId.YELLOW -> G1Y_AREAS_RAW
    GuideId.RED -> G1R_AREAS_RAW
    GuideId.BLUE -> G1B_AREAS_RAW
    GuideId.CRYSTAL -> GUIDE_AREAS_CRYSTAL_RAW
}

private val hgHeaderPlaces: Map<Int, Int> by lazy {
    HG_HEADER_PLACES.split(',').associate { p -> p.split(':').let { it[0].toInt() to it[1].toInt() } }
}

/**
 * The area HERE lists things for: the region map section the reader names ([regionSec]), except on a DS game, whose
 * area data goes by the place of the map header the player is on ([mapNum]: Platinum's map label, HG / SS's mapsec) -
 * the map's own, where the Town Map / Pokégear block can be another place's (a cave under a route).
 */
fun areaSection(game: GameKind?, mapNum: Int, regionSec: Int): Int = when (game) {
    GameKind.PLATINUM -> mapPlaceIdsPlatinum[mapNum] ?: regionSec
    GameKind.HEARTGOLD, GameKind.SOULSILVER -> hgHeaderPlaces[mapNum] ?: regionSec
    GameKind.BLACK, GameKind.WHITE -> mapPlaceIdsBlack[mapNum] ?: regionSec
    GameKind.BLACK2, GameKind.WHITE2 -> mapPlaceIdsBlack2[mapNum] ?: regionSec
    GameKind.YELLOW, GameKind.RED, GameKind.BLUE -> gen1Area(mapNum)
    else -> regionSec
}

private val GEN1_GUIDES = setOf(GuideId.YELLOW, GuideId.RED, GuideId.BLUE)

/** Whether [guide] has area data at all (then HERE shows even without the ROM tables). */
fun hasAreaGuide(guide: GuideId?): Boolean = guide != null

/** Everything known to be in area [mapsec] (a region map section id) of [guide]'s game. */
fun areaThings(guide: GuideId?, mapsec: Int): List<AreaThing> =
    if (guide == null) emptyList() else allAreaThings(guide)[mapsec].orEmpty()

/** Every area of [guide]'s game: region map section -> what's in it. On a randomized game ([runningRandomized]) the
 * randomizer's items, trades and gift Pokémon ([randomizedAreas]). */
fun allAreaThings(guide: GuideId): Map<Int, List<AreaThing>> {
    val r = runningRandomized?.randomizedGuide ?: return retailAreaThings(guide)
    randomizedCache?.let { (g, id, m) -> if (g === r && id == guide) return m }
    return randomizedAreas(retailAreaThings(guide), r).also { randomizedCache = Triple(r, guide, it) }
}

@Volatile private var randomizedCache: Triple<RandomizedGuide, GuideId, Map<Int, List<AreaThing>>>? = null

/**
 * [areas] as the randomized ROM [r] has them: an item ball's or hidden item's item read where the game keeps it (its
 * place and flag are retail's), a trade's two species and a gift Pokémon's from the ROM. What [r] can't place is left
 * out rather than shown as retail's; NPC item gifts and key items are kept (no randomizer touches them).
 */
internal fun randomizedAreas(areas: Map<Int, List<AreaThing>>, r: RandomizedGuide): Map<Int, List<AreaThing>> =
    areas.mapValues { (_, things) ->
        things.mapNotNull { t ->
            when (t.kind) {
                AreaKind.ITEM, AreaKind.HIDDEN -> r.items[t.flag]?.let { t.copy(id = it) }
                AreaKind.TRADE -> r.trades["${t.id}/${t.wants}"]?.let { (given, wanted) -> t.copy(id = given, wants = wanted) }
                AreaKind.MON, AreaKind.EGG -> r.statics[t.id]?.let { t.copy(id = it) }
                AreaKind.GIFT, AreaKind.KEY -> t
            }
        }
    }.filterValues { it.isNotEmpty() }

private fun retailAreaThings(guide: GuideId): Map<Int, List<AreaThing>> {
    val raw = rawAreas(guide)
    return areaCache.getOrPut(guide) {
        raw.asSequence().flatMap { it.lineSequence() }.filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('|')
            if (f.size < 7) return@mapNotNull null
            val key = f[0].toInt().let { if (guide in GEN1_GUIDES) gen1Area(it) else it }
            key to AreaThing(AreaKind.valueOf(f[1]), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6])
        }.groupBy({ it.first }, { it.second })
    }
}

private val PLACEHOLDER_NAME = Regex("[0-9a-fA-F]{1,4}")

/** An HM by its name in the game's language: HM01, CS01 (French), VM01 (German), MN01 (Italian), MO01 (Spanish), ひでんマシン. */
private fun isHm(name: String): Boolean =
    Regex("^(HM|CS|VM|MN|MO)\\s?\\d", RegexOption.IGNORE_CASE).containsMatchIn(name) || name.startsWith("ひでん")

/** An area name reduced for matching: FireRed's table says "Pokemon Tower", Emerald's "Pokémon Tower". */
fun areaKey(name: String): String =
    name.uppercase().replace('É', 'E').filter { it.isLetterOrDigit() }

/**
 * WHERE IS for a game with no hand-written one (the ROM hacks), built from its
 * area data: every HM, key item and gift POKéMON someone hands out, with the
 * area it's in (the hint) and the building (the answer). [areaName] names a
 * region map section; [done] says whether the save already has it.
 */
fun generatedWhereIs(guide: GuideId, areaName: (Int) -> String, done: (AreaThing) -> Boolean): GuidePage? {
    val all = allAreaThings(guide).flatMap { (sec, things) -> things.map { sec to it } }
    fun entries(pick: (AreaThing) -> Boolean, title: (AreaThing) -> String): List<GuideEntry> =
        all.filter { pick(it.second) }.sortedBy { it.first }.distinctBy { it.second.kind to it.second.id }.map { (sec, t) ->
            val area = areaName(sec)
            GuideEntry(
                title = title(t),
                hint = tr("In {0}.", area),
                answer = when {
                    t.kind == AreaKind.ITEM && t.where.isEmpty() -> tr("Lying around {0}.", area)
                    t.kind == AreaKind.ITEM -> tr("Lying in the {0} ({1}).", trGuide(t.where), area)
                    t.where.isEmpty() -> tr("Someone around {0}.", area)
                    else -> tr("Someone in the {0} ({1}).", trGuide(t.where), area)
                },
                owned = done(t),
            )
        }.filter { !it.title.startsWith("Item#") && !it.title.startsWith("Species#") } // no name to show
            .filter { !PLACEHOLDER_NAME.matches(it.title) } // a hack's unused slot ("03c" in Gaia)
            .distinctBy { it.title } // handed out in one place, lying in another: the first area
            .sortedBy { it.title }
    val gifts = setOf(AreaKind.GIFT, AreaKind.KEY)
    // Headings and the page title stay English (GuideUiState keys on them); GuideScreen translates them where drawn.
    val sections = listOf(
        // An HM may be handed out or lie in an item ball (Heart and Soul's HM07).
        GuideSection(tk("HMs"), entries({ (it.kind in gifts || it.kind == AreaKind.ITEM) && isHm(itemName(it.id)) }) { itemName(it.id) }),
        GuideSection(tk("KEY ITEMS"), entries({ it.kind == AreaKind.KEY && !isHm(itemName(it.id)) }) { itemName(it.id) }),
        GuideSection(tk("GIFT POKéMON"), entries({ it.kind == AreaKind.MON || it.kind == AreaKind.EGG }) { t ->
            speciesName(t.id) + if (t.kind == AreaKind.EGG) " " + tr("(egg)") else ""
        }),
    ).filter { it.entries.isNotEmpty() }
    return if (sections.isEmpty()) null else GuidePage(tk("WHERE IS"), sections)
}
