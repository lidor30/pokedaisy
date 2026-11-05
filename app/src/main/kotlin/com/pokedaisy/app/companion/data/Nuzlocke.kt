package com.pokedaisy.app.companion.data

/**
 * The NUZLOCKE tab's run (companion.Nuzlocke holds it per ROM): a tracker only, it never touches the game or the save.
 * The rules it follows: the first wild Pokémon met in each map section is that area's one encounter (caught, or missed
 * for good), and a Pokémon that faints is out of the run, even revived. Everything here comes from the samples the
 * companion already takes ([NuzlockeSample.of]): the map section, the battle's foe and type, the party by personality,
 * and the Pokédex's caught flags (a catch sent to the PC leaves the party as it was).
 */

/** What became of an area's encounter. NOW = the battle is still on (or just ended and the catch isn't in yet). */
enum class NuzlockeOutcome { NOW, CAUGHT, MISSED }

/** An area's encounter: [area] = the map section id, [areaName] as the game names it, [species] the foe met. */
data class NuzlockeEncounter(
    val area: Int,
    val areaName: String,
    val species: Int,
    val outcome: NuzlockeOutcome,
)

/**
 * A Pokémon of the run, by [personality] (the party read's id): [name] its nickname or species name, [areaName] where it
 * joined (empty: in the party when the run started), [dead] once it fainted, with where and at which level.
 */
data class NuzlockeMon(
    val personality: Long,
    val species: Int,
    val name: String,
    val level: Int,
    val areaName: String = "",
    val dead: Boolean = false,
    val diedIn: String = "",
    val diedLevel: Int = 0,
)

/** The run's rules: [dupes] = a species already caught doesn't use up an area; [warnings] = notices on the game screen. */
data class NuzlockeRules(val dupes: Boolean = false, val warnings: Boolean = true)

data class NuzlockeRun(
    val encounters: List<NuzlockeEncounter> = emptyList(),
    val mons: List<NuzlockeMon> = emptyList(),
    val rules: NuzlockeRules = NuzlockeRules(),
) {
    fun encounterAt(area: Int): NuzlockeEncounter? = encounters.firstOrNull { it.area == area }
    fun monOf(personality: Long): NuzlockeMon? = if (personality == 0L) null else mons.firstOrNull { it.personality == personality }
    fun isDead(personality: Long): Boolean = monOf(personality)?.dead == true
    val alive: List<NuzlockeMon> get() = mons.filter { !it.dead }
    val dead: List<NuzlockeMon> get() = mons.filter { it.dead }

    /** One line per field, tab-separated (names lose their tabs): what Prefs keeps per ROM. */
    fun encode(): String = buildString {
        append("v1\n")
        append("rules\t${rules.dupes.bit}\t${rules.warnings.bit}\n")
        encounters.forEach { append("enc\t${it.area}\t${it.species}\t${it.outcome.name}\t${clean(it.areaName)}\n") }
        mons.forEach {
            append("mon\t${it.personality}\t${it.species}\t${it.level}\t${it.dead.bit}\t${it.diedLevel}\t")
            append("${clean(it.name)}\t${clean(it.areaName)}\t${clean(it.diedIn)}\n")
        }
    }

    companion object {
        private val Boolean.bit get() = if (this) "1" else "0"
        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

        /** [encode]'s text back; a line that doesn't parse is skipped. */
        fun decode(text: String?): NuzlockeRun {
            if (text.isNullOrBlank()) return NuzlockeRun()
            var rules = NuzlockeRules()
            val encounters = mutableListOf<NuzlockeEncounter>()
            val mons = mutableListOf<NuzlockeMon>()
            for (line in text.lines()) {
                val f = line.split('\t')
                runCatching {
                    when (f[0]) {
                        "rules" -> rules = NuzlockeRules(dupes = f[1] == "1", warnings = f[2] == "1")
                        "enc" -> encounters += NuzlockeEncounter(
                            f[1].toInt(), f.getOrElse(4) { "" }, f[2].toInt(),
                            // A battle cut short by closing the game: missed, as it would have been.
                            NuzlockeOutcome.valueOf(f[3]).let { if (it == NuzlockeOutcome.NOW) NuzlockeOutcome.MISSED else it },
                        )
                        "mon" -> mons += NuzlockeMon(
                            personality = f[1].toLong(), species = f[2].toInt(), level = f[3].toInt(), dead = f[4] == "1",
                            diedLevel = f[5].toInt(), name = f[6], areaName = f.getOrElse(7) { "" }, diedIn = f.getOrElse(8) { "" },
                        )
                    }
                }
            }
            return NuzlockeRun(encounters, mons, rules)
        }
    }
}

/** A party Pokémon as the tracker needs it. */
data class NuzlockePartyMon(
    val personality: Long,
    val species: Int,
    val name: String,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val isEgg: Boolean = false,
)

/**
 * One companion sample, cut down to what the tracker reads: [trainer] null = the battle's type wasn't read (no
 * encounter is counted then), [foeSpecies] 0 = no foe read yet, [caught] null = no Pokédex flags.
 */
data class NuzlockeSample(
    val inBattle: Boolean,
    val trainer: Boolean?,
    val area: Int,
    val areaName: String,
    val foeSpecies: Int,
    val foeName: String,
    val party: List<NuzlockePartyMon>,
    val caught: Set<Int>?,
    /** The battle's menu is up, so the foe read is this battle's: as a battle opens, its memory still holds the last one's. */
    val menuUp: Boolean = true,
    /** The foe's number in [caught], which goes by national dex number, not the game's own species id (they part at Hoenn's). */
    val foeDex: Int = foeSpecies,
) {
    companion object {
        fun of(s: SnapshotView): NuzlockeSample {
            // A foe the game's tables can't name, or at no real level, is a battle read wrong (Celia's battlers, a
            // scripted battle's Lv0 foe): no foe, so no encounter, rather than one with a made-up Pokémon.
            val foe = s.battleOpponent.firstOrNull { it.species != 0 && it.level in 1..100 && activeSpeciesNames[it.species] != null }
            return NuzlockeSample(
                inBattle = s.inBattle,
                trainer = s.isTrainerBattle,
                area = s.regionMapSectionId,
                areaName = s.location.mapSecName,
                foeSpecies = foe?.species ?: 0,
                foeName = foe?.name.orEmpty(),
                party = s.party.map { NuzlockePartyMon(it.personality, it.species, it.shownName, it.level, it.hp, it.maxHp, it.isEgg) },
                caught = s.pokedex?.caught,
                menuUp = s.battleInputState == BATTLE_INPUT_ACTION_SELECT || s.battleInputState == BATTLE_INPUT_MOVE_SELECT ||
                    s.battleInputState == BATTLE_INPUT_BAG_OPEN || s.battleInputState == BATTLE_INPUT_PARTY_OPEN,
                foeDex = foe?.species?.let { species -> s.pokedex?.let { PokedexSource.nationalOf(it.tables, species) } } ?: foe?.species ?: 0,
            )
        }
    }
}

/** What a sample changed that's worth a notice on the game screen (the companion words them). */
sealed class NuzlockeEvent {
    data class FirstEncounter(val areaName: String, val foe: String) : NuzlockeEvent()
    data class AreaUsed(val areaName: String) : NuzlockeEvent()
    data class Dupe(val foe: String) : NuzlockeEvent()
    data class Caught(val name: String, val areaName: String) : NuzlockeEvent()
    data class Died(val name: String) : NuzlockeEvent()
    /** A dead Pokémon brought back to health (a Pokémon Center, a revive) or taken into a battle. */
    data class DeadInParty(val name: String) : NuzlockeEvent()
}

/**
 * Follows a run sample by sample ([feed]); [run] is the state to keep. Fainting needs [FAINT_SAMPLES] samples in a row
 * at 0 HP (one torn read mustn't end a Pokémon's run), and an encounter is settled [SETTLE_SAMPLES] samples after its
 * battle ends (the catch reaches the party / Pokédex during the battle, but a sample can land in between).
 */
class NuzlockeTracker(run: NuzlockeRun = NuzlockeRun()) {
    var run: NuzlockeRun = run
        private set

    /** Replaces the run (a change made on the tab): the battle being followed is kept. */
    fun set(r: NuzlockeRun) { run = r }

    /** Another ROM's run (or the first): nothing of the last battle carries over. */
    fun reset(r: NuzlockeRun) {
        run = r
        wasInBattle = false
        battleJudged = false
        pendingArea = null
        zeroHp.clear()
        lastHp.clear()
    }

    private var wasInBattle = false
    // This battle's foe was looked at (once a foe was read): one encounter at most per battle.
    private var battleJudged = false
    // The encounter this battle decides: its area, and the party / caught flags from before it.
    private var pendingArea: Int? = null
    private var pendingSpecies = 0
    private var pendingDex = 0
    private var pendingFoe = ""
    private var partyBefore: Set<Long> = emptySet()
    private var caughtBefore: Set<Int>? = null
    private var settleLeft = 0
    // Samples of this battle so far, for a game whose battle menu isn't read.
    private var battleSamples = 0
    private val zeroHp = mutableMapOf<Long, Int>()
    private val lastHp = mutableMapOf<Long, Int>()

    fun feed(s: NuzlockeSample): List<NuzlockeEvent> {
        // The title screen, a save not loaded yet: nothing to follow.
        if (s.party.isEmpty()) return emptyList()
        val events = mutableListOf<NuzlockeEvent>()
        val party = s.party.filter { it.personality != 0L && it.species != 0 && !it.isEgg }

        // New faces join the run where they're first seen; the living keep their name and level up to date.
        var mons = run.mons
        // The party the run starts with has been along from the start (no area).
        val first = mons.isEmpty()
        for (p in party) {
            val known = mons.indexOfFirst { it.personality == p.personality }
            if (known < 0) {
                mons = mons + NuzlockeMon(p.personality, p.species, p.name, p.level, areaName = if (first) "" else s.areaName)
            } else if (!mons[known].dead && (mons[known].name != p.name || mons[known].level != p.level || mons[known].species != p.species)) {
                mons = mons.toMutableList().also { it[known] = it[known].copy(name = p.name, level = p.level, species = p.species) }
            }
        }
        if (mons !== run.mons) run = run.copy(mons = mons)

        if (s.inBattle && !wasInBattle) { battleJudged = false; battleSamples = 0 }
        if (s.inBattle) battleSamples++
        // A wild battle's first foe: the area's encounter, if it hasn't had one. Not on the battle's first samples: it opens
        // with the last battle's foe still in memory, and an encounter judged on that named the wrong Pokémon and then
        // never saw it caught (a player's report: every encounter MISSED). So at its menu, or after a few samples without one.
        val foeIsThisBattles = s.menuUp || battleSamples > FOE_SAMPLES
        if (s.inBattle && !battleJudged && foeIsThisBattles && s.foeSpecies != 0 && s.trainer != null) {
            battleJudged = true
            party.filter { run.isDead(it.personality) && it.hp > 0 }.forEach { events += NuzlockeEvent.DeadInParty(it.name) }
            if (s.trainer == false) {
                when {
                    run.encounterAt(s.area) != null -> events += NuzlockeEvent.AreaUsed(s.areaName)
                    run.rules.dupes && s.caught?.contains(s.foeDex) == true -> events += NuzlockeEvent.Dupe(s.foeName)
                    else -> {
                        run = run.copy(encounters = run.encounters + NuzlockeEncounter(s.area, s.areaName, s.foeSpecies, NuzlockeOutcome.NOW))
                        pendingArea = s.area
                        pendingSpecies = s.foeSpecies
                        pendingDex = s.foeDex
                        pendingFoe = s.foeName
                        partyBefore = s.party.map { it.personality }.toSet()
                        caughtBefore = s.caught
                        settleLeft = SETTLE_SAMPLES
                        events += NuzlockeEvent.FirstEncounter(s.areaName, s.foeName)
                    }
                }
            }
        }

        pendingArea?.let { area -> settle(area, s, events) }

        // Fainting: out of the run for good. A dead Pokémon healed again gets a reminder, once each time.
        for (p in party) {
            val mon = run.monOf(p.personality) ?: continue
            if (p.hp == 0 && p.maxHp > 0) {
                val n = (zeroHp[p.personality] ?: 0) + 1
                zeroHp[p.personality] = n
                if (!mon.dead && n >= FAINT_SAMPLES) {
                    run = run.copy(mons = run.mons.map {
                        if (it.personality == p.personality) it.copy(dead = true, diedIn = s.areaName, diedLevel = p.level) else it
                    })
                    events += NuzlockeEvent.Died(mon.name)
                }
            } else {
                zeroHp.remove(p.personality)
                if (mon.dead && p.hp > 0 && lastHp[p.personality] == 0 && !s.inBattle) events += NuzlockeEvent.DeadInParty(mon.name)
            }
            lastHp[p.personality] = p.hp
        }
        wasInBattle = s.inBattle
        return events
    }

    private fun settle(area: Int, s: NuzlockeSample, events: MutableList<NuzlockeEvent>) {
        val joined = s.party.firstOrNull { it.personality != 0L && it.personality !in partyBefore && it.species == pendingSpecies }
        val toPc = caughtBefore?.contains(pendingDex) == false && s.caught?.contains(pendingDex) == true
        val outcome = when {
            joined != null || toPc -> NuzlockeOutcome.CAUGHT
            s.inBattle -> return
            --settleLeft > 0 -> return
            else -> NuzlockeOutcome.MISSED
        }
        pendingArea = null
        // Changed on the tab meanwhile (cleared or set by hand): the player's word stands.
        val e = run.encounterAt(area)?.takeIf { it.outcome == NuzlockeOutcome.NOW } ?: return
        run = run.copy(encounters = run.encounters.map { if (it.area == area) e.copy(outcome = outcome) else it })
        if (outcome == NuzlockeOutcome.CAUGHT) {
            events += NuzlockeEvent.Caught(joined?.name ?: pendingFoe, e.areaName)
            // The catch joined where it was met (the roster above saw it first in this sample).
            if (joined != null) run = run.copy(mons = run.mons.map { if (it.personality == joined.personality) it.copy(areaName = e.areaName) else it })
        }
    }

    companion object {
        const val FAINT_SAMPLES = 2
        const val SETTLE_SAMPLES = 4
        /** A battle's samples before its foe is trusted with no menu read. */
        const val FOE_SAMPLES = 4
    }
}
