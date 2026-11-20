package com.pokedaisy.app.companion.data

import okio.Path

/** Party choices and patches for the Pokémon entries in the existing PLAYER LOOK picker. */
object PokemonCostumes {
    fun party(tables: PokedexTables, species: List<Int>): Map<Int, String> = buildMap {
        for (id in species.distinct()) {
            val national = runCatching { PokedexSource.nationalOf(tables, id) }.getOrNull() ?: continue
            if (national in 1..MonArt.LAST) put(national, speciesName(id))
        }
    }

    /** Call on an asset worker. Include the other party palettes so older states can be settled. */
    fun build(
        files: Path,
        scan: PlayerLook.Scan,
        own: LookChar,
        national: Int,
        party: Collection<Int>,
        known: Map<LookChar, Map<LookPart, ByteArray>>,
    ): PlayerLook.Patch? {
        val asset = MonArt.load(files, national) ?: return null
        val stock = { part: LookPart -> scan.data(own, part) }
        val otherPalettes = party.asSequence().filter { it != national }
            .mapNotNull { MonArt.load(files, it) }
            .mapNotNull { PlayerLook.wearMon(own, it, stock) }
            .toList()
        return PlayerLook.buildMon(scan, own, asset, known, otherPalettes)
    }
}
