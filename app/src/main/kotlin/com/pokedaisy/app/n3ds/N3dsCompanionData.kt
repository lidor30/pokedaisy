package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.GENDER_SYMBOL_UNKNOWN
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.statusLabel

/** A [Gen6Reader.Sample] as the companion's screens take it, names from the cart's own text ([Gen6Text]). */
object N3dsCompanionData {
    fun party(sample: Gen6Reader.Sample, text: Gen6Text?): List<MonView> = sample.party.map { m ->
        val species = text?.species?.getOrNull(m.species) ?: "#${m.species}"
        MonView(
            species = m.species,
            name = species,
            level = m.level,
            hp = m.hp,
            maxHp = m.maxHp,
            status = if (m.hp == 0) "FNT" else statusLabel(m.status.toLong() and 0xFFFFFFFFL),
            types = emptyList(),
            iconAsset = Gen6IconTable.index(m.species, m.form, m.female)?.let(Gen6Icons::asset),
            genderSymbol = GENDER_SYMBOL_UNKNOWN,
            isEgg = m.isEgg,
            personality = m.encryptionConstant,
            heldItemId = m.heldItem,
            nickname = m.nickname.takeIf { it.isNotEmpty() },
            shiny = m.shiny,
        )
    }

    /** A battler ([Gen6Reader.battleMon]) as the battle strip and BATTLE take it: its moves with their PP, types and
     * power from the cart ([Gen6Moves]). */
    fun battleMon(mon: Gen6Reader.BattleMon, text: Gen6Text?, moves: List<Gen6Moves.Move>?): MonView = MonView(
        species = mon.species,
        name = text?.species?.getOrNull(mon.species) ?: "#${mon.species}",
        level = mon.level,
        hp = mon.hp,
        maxHp = mon.maxHp,
        status = if (mon.hp == 0) "FNT" else "",
        types = emptyList(),
        iconAsset = Gen6IconTable.index(mon.species, 0, false)?.let(Gen6Icons::asset),
        moves = mon.moves.map { m ->
            val data = moves?.getOrNull(m.id)
            com.pokedaisy.app.companion.data.MoveView(
                name = text?.moves?.getOrNull(m.id) ?: "#${m.id}",
                type = data?.let { Gen6Moves.TYPES.getOrNull(it.type) } ?: "NORMAL",
                pp = m.pp,
                power = data?.power ?: 0,
                id = m.id,
                maxPp = m.maxPp,
            )
        },
    )

    /** Where the player is: the zone's place name ([Gen6Zones]), for the status bars. No region map yet. */
    fun location(sample: Gen6Reader.Sample, text: Gen6Text?, zones: IntArray?): com.pokedaisy.app.companion.data.LocationView =
        com.pokedaisy.app.companion.data.LocationView(Gen6Zones.placeName(zones, text, sample.zone), null, 0, 0, 0, 0)

    fun bag(sample: Gen6Reader.Sample, text: Gen6Text?): List<ItemView> = sample.bag.map { i ->
        ItemView(itemId = i.id, name = text?.items?.getOrNull(i.id) ?: "#${i.id}", quantity = i.count, iconAsset = null, pocket = i.pocket)
    }
}
