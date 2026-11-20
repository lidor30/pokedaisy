package com.pokedaisy.app.companion.data.save

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SaveLibraryTest {
    @Test fun unreadableSavesDoNotShiftSourceIndices() {
        val candidates = listOf("First", "Unreadable", "Last")
        val saves = mapOf(
            "First" to side("First", species = 1),
            "Last" to side("Last", species = 4),
        )
        val collection = SaveLibrary.collection(candidates, { saves[it] }, { it })
        assertEquals(listOf("First", "Last"), collection.sources.map { it.label })
        assertEquals(listOf(0, 1), collection.items.map { it.source })
        assertEquals(listOf(1, 4), collection.items.map { it.national })

        val missing = SaveLibrary.missing(side("Target"), candidates, { saves[it] }, { it })
        assertEquals(collection.sources, missing.sources)
        assertEquals(collection.sides, missing.sides)
        assertTrue(missing.entries.single { it.species == 4 }.sources.any { it.source == 1 })
    }

    private fun side(label: String, species: Int = 0): TradeSide {
        val format = Gen3SaveFormat.FIRERED_LEAFGREEN
        val bytes = ByteArray(Gen3SaveFile.MIN_SIZE)
        for (id in 0 until Gen3SaveFile.SECTORS_PER_SLOT) {
            val base = id * Gen3SaveFile.SECTOR
            putU16(bytes, base + Gen3SaveFile.ID_OFF, id)
            putU32(bytes, base + Gen3SaveFile.SIGNATURE_OFF, Gen3SaveFile.SIGNATURE)
        }
        if (species != 0) {
            // The first 80-byte record starts after the storage block's current-box word.
            // Zero personality and OT ID make the first substructure Growth with no XOR key.
            val base = 5 * Gen3SaveFile.SECTOR
            val mon = base + 4
            bytes[mon + 19] = 2
            putU16(bytes, mon + 32, species)
            putU16(bytes, mon + 28, species)
            val checksum = Gen3SaveFile.checksum(bytes, base, format.chunkSize(5))
            putU16(bytes, base + Gen3SaveFile.CHECKSUM_OFF, checksum)
        }
        val save = assertIs<Gen3SaveFile.Parsed.Ok>(Gen3SaveFile.parse(bytes, format)).save
        return TradeSide(label, Gen3SaveEditor(save), "BPRE")
    }
}
