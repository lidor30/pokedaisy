package com.pokedaisy.app.companion.data.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** COLLECTION: every save's Pokémon gathered, in the orders it offers. */
class SaveCollectionTest {
    private fun sides(): List<TradeSide> {
        val (fr, emerald) = SyntheticSave.demoTrade()
        val hg = Gen4SaveEditor((Gen4SaveFile.parse(SyntheticGen4.save(national = true)) as Gen4SaveFile.Parsed.Ok).save, SyntheticGen4.demoGame())
        return listOf(TradeSide("FR", Gen3SaveEditor(fr), "BPRE"), TradeSide("EM", Gen3SaveEditor(emerald), "BPEE"), TradeSide("HG", hg, "IPKE"))
    }

    @Test
    fun everyPokemonOfEverySaveIsListed() {
        val sides = sides()
        val items = SaveCollection.gather(sides)
        for ((i, side) in sides.withIndex()) {
            val d = side.editor.data
            val count = d.partyMons.size + (0 until d.boxCount).sumOf { b -> d.boxMons(b).count { !it.isEmpty } }
            assertEquals(count, items.count { it.source == i })
        }
        // National numbers, not the GBA games' own species ids: TREECKO is 252 in both GBA saves.
        val treeckos = items.filter { !it.mon.isEgg && it.mon.species == 277 }
        assertTrue(treeckos.isNotEmpty() && treeckos.all { it.national == 252 })
        // FireRed's egg is listed, after every Pokémon in Pokédex order, and isn't a species of its own.
        val dex = SaveCollection.sorted(items, SaveCollection.Sort.DEX)
        assertTrue(dex.last().mon.isEgg)
        val hatched = dex.filter { !it.mon.isEgg }
        assertEquals(hatched.map { it.national }.sorted(), hatched.map { it.national })
        assertEquals(hatched.map { it.national }.toSet().size, SaveCollection.species(items))
        // By level: highest first.
        val byLevel = SaveCollection.sorted(items, SaveCollection.Sort.LEVEL).filter { !it.mon.isEgg }.map { it.level }
        assertEquals(byLevel.sortedDescending(), byLevel)
        // Pal Park takes a GBA game's PC Pokémon only.
        assertTrue(items.filter { it.palParkable }.all { it.source != 2 && it.at is MonRef.Pc })
        assertFalse(items.any { it.palParkable && it.mon.isEgg })
    }
}
