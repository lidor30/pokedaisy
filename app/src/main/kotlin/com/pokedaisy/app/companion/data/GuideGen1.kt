package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.i18n.tr

/**
 * Yellow's, Red's and Blue's guides: HERE and NEXT BOSS from GuideGen1Gen.kt, which scripts/gen_gen1_guide.py reads out of
 * pret/pokeyellow's and pret/pokered's builds (each byte-identical to its cart) through their .sym files. Lines, `|`-separated:
 *  - wild: `G|map|rate|lv:sp x 10` grass / caves and `W|map|rate|lv:sp x 10` surfing (wCurMap ids, national dex numbers,
 *    WildMonEncounterSlotChances' odds for both), `S|map|odds:sp:lv ...` the map's SUPER ROD (odds in percent), and the
 *    OLD / GOOD ROD's catches, the same on every map with water (`OLD|...`, `GOOD|...`);
 *  - bosses: `KEY|TITLE|WHERE|DONE|COND|lv sp item m,m,m,m;...`, DONE = flags of which any marks it beaten, COND = "" or
 *    `0x10000=v`, the team shown while the champion's team var holds v (one line per team, same KEY).
 * The save's side ([readGen1Progress]) maps each kind of flag into a bank of its own, the numbers the generator writes.
 * The European Yellows run English's code with RAM 5 bytes later from wBattleMon up (Gen1Reader.kt), so they share Yellow's
 * data with the addresses moved.
 */

/** The companion's flag ids for the banks past wEventFlags: a missable object (an item ball, gone once picked up), a hidden
 * item, an in-game trade, a badge. [G1_VAR_TEAM] is the byte that picks the champion's team. */
internal const val G1_MISSABLE_FLAG = 0x10000
internal const val G1_HIDDEN_ITEM_FLAG = 0x20000
internal const val G1_TRADE_FLAG = 0x30000
internal const val G1_BADGE_FLAG = 0x40000
internal const val G1_VAR_TEAM = 0x10000

/** Where a cart keeps what the guide reads (GuideGen1Gen.kt's constants). */
class Gen1GuideRam(
    val eventFlags: Int, val eventBytes: Int,
    val missable: Int, val missableBytes: Int,
    val hiddenItems: Int, val hiddenItemBytes: Int,
    val trades: Int, val tradeBytes: Int,
    val badges: Int,
    val teamVar: Int,
) {
    /** The same RAM [by] bytes later from wBattleMon (0xD013) up: the European Yellows. */
    fun shifted(by: Int): Gen1GuideRam {
        fun s(a: Int) = if (a >= 0xD013) a + by else a
        return Gen1GuideRam(s(eventFlags), eventBytes, s(missable), missableBytes, s(hiddenItems), hiddenItemBytes, s(trades), tradeBytes,
            s(badges), s(teamVar))
    }
}

private val YELLOW_GUIDE_RAM = Gen1GuideRam(
    G1Y_EVENT_FLAGS, G1Y_EVENT_FLAG_BYTES, G1Y_MISSABLE_FLAGS, G1Y_MISSABLE_FLAG_BYTES, G1Y_HIDDEN_ITEM_FLAGS, G1Y_HIDDEN_ITEM_FLAG_BYTES,
    G1Y_TRADE_FLAGS, G1Y_TRADE_FLAG_BYTES, G1Y_BADGES, G1Y_TEAM_VAR,
)
private val RED_GUIDE_RAM = Gen1GuideRam(
    G1R_EVENT_FLAGS, G1R_EVENT_FLAG_BYTES, G1R_MISSABLE_FLAGS, G1R_MISSABLE_FLAG_BYTES, G1R_HIDDEN_ITEM_FLAGS, G1R_HIDDEN_ITEM_FLAG_BYTES,
    G1R_TRADE_FLAGS, G1R_TRADE_FLAG_BYTES, G1R_BADGES, G1R_TEAM_VAR,
)
private val BLUE_GUIDE_RAM = Gen1GuideRam(
    G1B_EVENT_FLAGS, G1B_EVENT_FLAG_BYTES, G1B_MISSABLE_FLAGS, G1B_MISSABLE_FLAG_BYTES, G1B_HIDDEN_ITEM_FLAGS, G1B_HIDDEN_ITEM_FLAG_BYTES,
    G1B_TRADE_FLAGS, G1B_TRADE_FLAG_BYTES, G1B_BADGES, G1B_TEAM_VAR,
)

private val YELLOW_LEGACY_GUIDE_RAM = Gen1GuideRam(
    G1L_EVENT_FLAGS, G1L_EVENT_FLAG_BYTES, G1L_MISSABLE_FLAGS, G1L_MISSABLE_FLAG_BYTES, G1L_HIDDEN_ITEM_FLAGS, G1L_HIDDEN_ITEM_FLAG_BYTES,
    G1L_TRADE_FLAGS, G1L_TRADE_FLAG_BYTES, G1L_BADGES, G1L_TEAM_VAR,
)

/** [cfg]'s guide RAM: Yellow in all five languages, Red, Blue, Yellow Legacy; null for PureRGB (it moved things). */
fun gen1GuideRam(cfg: Gen1Config): Gen1GuideRam? = when {
    cfg === GEN1_YELLOW -> YELLOW_GUIDE_RAM
    cfg === GEN1_YELLOW_LEGACY -> YELLOW_LEGACY_GUIDE_RAM
    cfg === GEN1_YELLOW_DE || cfg === GEN1_YELLOW_FR || cfg === GEN1_YELLOW_IT || cfg === GEN1_YELLOW_ES ->
        YELLOW_GUIDE_RAM.shifted((cfg.badges - GEN1_YELLOW.badges).toInt())
    cfg === GEN1_RED -> RED_GUIDE_RAM
    cfg === GEN1_BLUE -> BLUE_GUIDE_RAM
    cfg === GEN1_RED_DE -> RED_GUIDE_RAM.shifted((cfg.badges - GEN1_RED.badges).toInt())
    cfg === GEN1_BLUE_DE -> BLUE_GUIDE_RAM.shifted((cfg.badges - GEN1_BLUE.badges).toInt())
    else -> null
}

/** The save's event flags, item balls, hidden items, trades, badges and the champion's team var. */
fun readGen1Progress(r: MemoryReader, ram: Gen1GuideRam): SaveProgress {
    fun read(addr: Int, n: Int) = r.readCoreMemory(addr.toLong(), n)
    return SaveProgress(
        read(ram.eventFlags, ram.eventBytes), ByteArray(0),
        flagBanks = listOf(
            SaveBank(G1_MISSABLE_FLAG, 0, ram.missableBytes * 8) to read(ram.missable, ram.missableBytes),
            SaveBank(G1_HIDDEN_ITEM_FLAG, 0, ram.hiddenItemBytes * 8) to read(ram.hiddenItems, ram.hiddenItemBytes),
            SaveBank(G1_TRADE_FLAG, 0, ram.tradeBytes * 8) to read(ram.trades, ram.tradeBytes),
            SaveBank(G1_BADGE_FLAG, 0, 8) to read(ram.badges, 1),
        ),
        varBanks = listOf(SaveBank(G1_VAR_TEAM, 0, 1) to byteArrayOf(read(ram.teamVar, 1)[0], 0)),
    )
}

/**
 * One Gen 1 game's wild Pokémon and bosses. A map's grass and water both roll WildMonEncounterSlotChances; the OLD and
 * GOOD ROD bite the same way wherever there's water, the SUPER ROD by map. Trainers are keyed by their line's index.
 */
class Gen1World(wildRaw: List<String>, bossRaw: List<String>) : GuideWorld {
    private class Slot(val level: Int, val species: Int, val odds: Double)
    private class Line(val key: String, val title: String, val where: String, val done: List<Int>, val team: Int?, val mons: List<TrainerMon>)

    private val grass = HashMap<Int, List<Slot>>()
    private val water = HashMap<Int, List<Slot>>()
    private val superRod = HashMap<Int, List<Slot>>()
    private var oldRod = emptyList<Slot>()
    private var goodRod = emptyList<Slot>()

    init {
        fun slots(s: String) = s.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, p ->
            p.split(':').let { Slot(it[0].toInt(), it[1].toInt(), SLOT_ODDS[i] * 100.0 / 256) }
        }
        fun rows(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { p ->
            p.split(':').let { Slot(level = it[2].toInt(), species = it[1].toInt(), odds = it[0].toDouble()) }
        }
        wildRaw.asSequence().flatMap { it.lineSequence() }.filter { it.isNotBlank() }.forEach { line ->
            val f = line.split('|')
            when (f[0]) {
                "G" -> if (f[2].toInt() > 0) grass[f[1].toInt()] = slots(f[3])
                "W" -> if (f[2].toInt() > 0) water[f[1].toInt()] = slots(f[3])
                "S" -> superRod[f[1].toInt()] = rows(f[2])
                "OLD" -> oldRod = rows(f[1])
                "GOOD" -> goodRod = rows(f[1])
            }
        }
    }

    private val lines: List<Line> = bossRaw.asSequence().flatMap { it.lineSequence() }.filter { it.isNotBlank() }.map { l ->
        val f = l.split('|')
        val mons = f[5].split(';').filter { it.isNotBlank() }.map { m ->
            val p = m.split(' ')
            TrainerMon(p[1].toInt(), p[0].toInt(), p[2].toInt(), p.getOrElse(3) { "" }.split(',').filter { it.isNotEmpty() }.map { it.toInt() })
        }
        val team = f[4].takeIf { it.isNotEmpty() }?.substringAfter('=')?.toInt()
        Line(f[0], f[1], f[2], f[3].split('/').filter { it.isNotEmpty() }.map { Integer.decode(it) }, team, mons)
    }.toList()

    override fun encounters(group: Int, num: Int, progress: SaveProgress?): MapEncounters {
        fun merge(slots: List<Slot>) = slots.groupBy { it.species }.map { (species, g) ->
            EncounterSlot(species, g.minOf { it.level }, g.maxOf { it.level }, Math.round(g.sumOf { it.odds }).toInt())
        }.sortedByDescending { it.percent }
        val fishes = num in water || num in superRod
        return MapEncounters(
            grass = merge(grass[num].orEmpty()),
            water = merge(water[num].orEmpty()),
            rockSmash = emptyList(),
            oldRod = if (fishes) merge(oldRod) else emptyList(),
            goodRod = if (fishes) merge(goodRod) else emptyList(),
            superRod = merge(superRod[num].orEmpty()),
        )
    }

    override fun party(trainer: Int, progress: SaveProgress?): List<TrainerMon> = lines.getOrNull(trainer)?.mons.orEmpty()

    /** NEXT BOSS's list, in the game's order. A boss with a team per value of the champion's var shows the one the save
     * holds; before the var is set (or on a value none of them has), every team, each under its ace's name. */
    val bosses: List<Boss> = lines.withIndex().groupBy { it.value.key }.values.map { group ->
        val first = group.first().value
        val teams = group.filter { it.value.team != null }
        Boss(
            title = first.title,
            where = first.where,
            done = 0,
            variantsFor = if (teams.isEmpty()) null else { p ->
                val v = p.variable(G1_VAR_TEAM)
                if (teams.any { it.value.team == v }) emptyList()
                else teams.map { t -> tr("WITH {0}", speciesName(t.value.mons.maxBy { it.level }.species)) to t.index }
            },
            doneIf = { p -> first.done.any { p.flag(it) } },
        ) { p -> (teams.firstOrNull { it.value.team == p.variable(G1_VAR_TEAM) } ?: group.first()).index }
    }

    private companion object {
        // WildMonEncounterSlotChances (data/wild/probabilities.asm): counts of 256 per slot.
        val SLOT_ODDS = intArrayOf(51, 51, 39, 25, 25, 25, 13, 13, 11, 3)
    }
}

internal val YELLOW_WORLD = Gen1World(G1Y_WILD_RAW, G1Y_BOSSES_RAW)
internal val RED_WORLD = Gen1World(G1R_WILD_RAW, G1R_BOSSES_RAW)
internal val BLUE_WORLD = Gen1World(G1B_WILD_RAW, G1B_BOSSES_RAW)
internal val YELLOW_LEGACY_WORLD = Gen1World(G1L_WILD_RAW, G1L_BOSSES_RAW)

private fun gen1Guide(world: Gen1World) = GameGuide(verified = false, bosses = world.bosses, world = world, pages = emptyList())

internal val GUIDE_YELLOW = gen1Guide(YELLOW_WORLD)
internal val GUIDE_RED = gen1Guide(RED_WORLD)
internal val GUIDE_BLUE = gen1Guide(BLUE_WORLD)
internal val GUIDE_YELLOW_LEGACY = gen1Guide(YELLOW_LEGACY_WORLD)

/**
 * The area HERE lists a Gen 1 map's things under: the first map with its town map name (wCurMap ids, [mapSecDataYellow]),
 * so a town's houses and marts count with the town, a cave's floors with the cave.
 */
fun gen1Area(map: Int): Int {
    val name = mapSecDataYellow[map]?.name ?: return map
    return gen1AreaFirst[name] ?: map
}

private val gen1AreaFirst: Map<String, Int> by lazy {
    mapSecDataYellow.entries.sortedBy { it.key }.groupBy({ it.value.name }, { it.key }).mapValues { it.value.first() }
}
