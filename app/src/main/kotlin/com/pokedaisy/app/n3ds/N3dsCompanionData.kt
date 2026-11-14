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
            iconAsset = null,
            genderSymbol = GENDER_SYMBOL_UNKNOWN,
            isEgg = m.isEgg,
            personality = m.encryptionConstant,
            heldItemId = m.heldItem,
            nickname = m.nickname.takeIf { it.isNotEmpty() },
            shiny = m.shiny,
        )
    }

    /** Where the player is: the zone's place name ([Gen6Zones]), for the status bars. No region map yet. */
    fun location(sample: Gen6Reader.Sample, text: Gen6Text?, zones: IntArray?): com.pokedaisy.app.companion.data.LocationView =
        com.pokedaisy.app.companion.data.LocationView(Gen6Zones.placeName(zones, text, sample.zone), null, 0, 0, 0, 0)

    fun bag(sample: Gen6Reader.Sample, text: Gen6Text?): List<ItemView> = sample.bag.map { i ->
        ItemView(itemId = i.id, name = text?.items?.getOrNull(i.id) ?: "#${i.id}", quantity = i.count, iconAsset = null, pocket = i.pocket)
    }
}
