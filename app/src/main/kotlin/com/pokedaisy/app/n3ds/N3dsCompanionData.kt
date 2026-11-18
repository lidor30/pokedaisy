package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.GENDER_SYMBOL_UNKNOWN
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.statusLabel

/** A [Gen6Reader.Sample] as the companion's screens take it, names from the cart's own text ([Gen6Text]). */
object N3dsCompanionData {
    /** The party, its types, moves and matchups from the cart ([Gen6Personal], [Gen6Moves], [Gen6Types]) when they're
     * there: the PARTY tab and the HUD's summary. */
    fun party(
        sample: Gen6Reader.Sample, text: Gen6Text?, moves: List<Gen6Moves.Move>? = null, personal: List<IntArray>? = null,
        layout: N3dsLayout = N3dsLayout.XY,
    ): List<MonView> = sample.party.map { m ->
        val species = text?.species?.getOrNull(m.species) ?: "#${m.species}"
        val types = if (m.isEgg) null else personal?.getOrNull(m.species)
        val matchups = types?.let { matchups(it[0], it[1]) }
        MonView(
            species = m.species,
            name = species,
            level = m.level,
            hp = m.hp,
            maxHp = m.maxHp,
            status = if (m.hp == 0) "FNT" else statusLabel(m.status.toLong() and 0xFFFFFFFFL),
            types = types?.distinct()?.mapNotNull { Gen6Moves.TYPES.getOrNull(it) }.orEmpty(),
            iconAsset = layout.iconIndex(m.species, m.form, m.female)?.let(layout::iconAsset),
            genderSymbol = when {
                m.genderless -> com.pokedaisy.app.companion.data.GENDER_SYMBOL_NONE
                m.female -> com.pokedaisy.app.companion.data.GENDER_SYMBOL_FEMALE
                else -> com.pokedaisy.app.companion.data.GENDER_SYMBOL_MALE
            },
            isEgg = m.isEgg,
            moves = if (m.isEgg) emptyList() else m.moves.toList().mapIndexedNotNull { i, id ->
                if (id == 0) return@mapIndexedNotNull null
                val data = moves?.getOrNull(id)
                com.pokedaisy.app.companion.data.MoveView(
                    name = text?.moves?.getOrNull(id) ?: "#$id",
                    type = data?.let { Gen6Moves.TYPES.getOrNull(it.type) } ?: "NORMAL",
                    pp = m.pp.getOrElse(i) { 0 },
                    power = data?.power ?: 0,
                    id = id,
                )
            },
            personality = m.encryptionConstant,
            heldItemId = m.heldItem,
            nickname = m.nickname.takeIf { it.isNotEmpty() },
            shiny = m.shiny,
            weaknesses = matchups?.weaknesses.orEmpty(),
            resistances = matchups?.resistances.orEmpty(),
            immunities = matchups?.immunities.orEmpty(),
        )
    }

    /** Every attacking type against [t1] / [t2] by Gen 6's chart, sorted into the summary's three lists. */
    private fun matchups(t1: Int, t2: Int): com.pokedaisy.app.companion.data.TypeMatchups {
        val weak = mutableListOf<com.pokedaisy.app.companion.data.TypeMatchup>()
        val resist = mutableListOf<com.pokedaisy.app.companion.data.TypeMatchup>()
        val immune = mutableListOf<com.pokedaisy.app.companion.data.TypeMatchup>()
        for ((atk, name) in Gen6Moves.TYPES.withIndex()) {
            val pct = Gen6Types.multiplierPct(atk, t1, t2)
            val m = com.pokedaisy.app.companion.data.TypeMatchup(name, com.pokedaisy.app.companion.data.formatMultiplier(pct), pct)
            when {
                pct == 0 -> immune += m
                pct > 100 -> weak += m
                pct < 100 -> resist += m
            }
        }
        return com.pokedaisy.app.companion.data.TypeMatchups(weak.sortedByDescending { it.pct }, resist.sortedBy { it.pct }, immune)
    }

    /** A battler ([Gen6Reader.battleMon]) as the battle strip and BATTLE take it: its types and its moves with their
     * PP, types and power from the cart ([Gen6Moves], [Gen6Personal]); each damaging move's multiplier on [foe] when
     * one is given (the strip's verdicts: BATTLE HINTS decides whether they show). */
    fun battleMon(
        mon: Gen6Reader.BattleMon, text: Gen6Text?, moves: List<Gen6Moves.Move>?, personal: List<IntArray>?,
        foe: Gen6Reader.BattleMon? = null, layout: N3dsLayout = N3dsLayout.XY,
    ): MonView {
        val types = personal?.getOrNull(mon.species)
        val foeTypes = foe?.let { personal?.getOrNull(it.species) }
        val foeName = foe?.let { text?.species?.getOrNull(it.species) ?: "#${it.species}" }.orEmpty()
        return MonView(
            species = mon.species,
            name = text?.species?.getOrNull(mon.species) ?: "#${mon.species}",
            level = mon.level,
            hp = mon.hp,
            maxHp = mon.maxHp,
            status = if (mon.hp == 0) "FNT" else "",
            types = types?.distinct()?.mapNotNull { Gen6Moves.TYPES.getOrNull(it) }.orEmpty(),
            iconAsset = layout.iconIndex(mon.species, 0, false)?.let(layout::iconAsset),
            moves = mon.moves.map { m ->
                val data = moves?.getOrNull(m.id)
                // A status move (category 2, no power) has no multiplier, as on the other games' cards.
                val vs = if (data == null || foeTypes == null || data.category == 2 || data.power == 0) emptyList() else {
                    val pct = Gen6Types.multiplierPct(data.type, foeTypes[0], foeTypes[1])
                    listOf(com.pokedaisy.app.companion.data.MoveVsFoe(foeName, com.pokedaisy.app.companion.data.formatMultiplier(pct), pct))
                }
                com.pokedaisy.app.companion.data.MoveView(
                    name = text?.moves?.getOrNull(m.id) ?: "#${m.id}",
                    type = data?.let { Gen6Moves.TYPES.getOrNull(it.type) } ?: "NORMAL",
                    pp = m.pp,
                    power = data?.power ?: 0,
                    vs = vs,
                    id = m.id,
                    maxPp = m.maxPp,
                )
            },
        )
    }

    /** A zone's wild Pokemon ([Gen6Wild]) for the HUD: per way of meeting them, each species once (its slots' chances
     * added, its levels' range), most common first. */
    fun wild(tables: List<Gen6Wild.Table>, text: Gen6Text?, layout: N3dsLayout = N3dsLayout.XY): List<com.pokedaisy.app.companion.ui.XyWildRow> = tables.flatMap { t ->
        t.slots.groupBy { it.species }.map { (species, slots) ->
            // Forms 30 / 31 are "any" (Scatterbug's patterns): the plain icon.
            val form = slots.first().form.takeIf { it < 30 } ?: 0
            com.pokedaisy.app.companion.ui.XyWildRow(
                method = t.method,
                name = text?.species?.getOrNull(species) ?: "#$species",
                iconAsset = layout.iconIndex(species, form, false)?.let(layout::iconAsset),
                minLevel = slots.minOf { it.minLevel },
                maxLevel = slots.maxOf { it.maxLevel },
                percent = slots.sumOf { it.percent },
            )
        }.sortedByDescending { it.percent }
    }

    /** Where the player is: the zone's place name ([Gen6Zones]), for the status bars. No region map yet. */
    fun location(sample: Gen6Reader.Sample, text: Gen6Text?, zones: IntArray?): com.pokedaisy.app.companion.data.LocationView =
        com.pokedaisy.app.companion.data.LocationView(Gen6Zones.placeName(zones, text, sample.zone), null, 0, 0, 0, 0)

    fun bag(sample: Gen6Reader.Sample, text: Gen6Text?): List<ItemView> = sample.bag.map { i ->
        ItemView(itemId = i.id, name = text?.items?.getOrNull(i.id) ?: "#${i.id}", quantity = i.count, iconAsset = null, pocket = i.pocket)
    }
}
