package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 of gamedata/: the NativeConfigs generated from gamedata/gen3/ (NativeConfigsGen.kt) are the
 * ones NativeReader.kt used to write by hand (LegacyNativeConfigs.kt), value for value. Every config of
 * both files is found by reflection, private ones included, so a config dropped or added on one side
 * fails too; the language ports (gamedata/gen3/ports/) against the RETAIL_PORTS port_retail.py used to
 * write (LegacyRetailPorts.kt). Phase 2: the records moved to gamedata/gen3/records/ (GameDataRecordsGen.kt)
 * against the ones written by hand (LegacyRecords.kt). This test and the Legacy*.kt files go once the gate
 * has passed.
 */
class GameDataMigrationTest {
    private fun configs(fileClass: String, prefix: String): Map<String, NativeConfig> =
        Class.forName("com.pokedaisy.app.companion.data.$fileClass").declaredFields
            .filter { it.type == NativeConfig::class.java && it.name.startsWith(prefix) }
            .associate { f ->
                f.isAccessible = true
                f.name.removePrefix(prefix) to f.get(null) as NativeConfig
            }

    private val generated = configs("NativeConfigsGenKt", "NATIVE_")
    private val legacy = configs("LegacyNativeConfigsKt", "LEGACY_NATIVE_")

    /** Games added since the move, written in gamedata/ from the start: they have no hand-written config. */
    private val bornInGamedata = setOf("WISHES", "DREAMSTONE", "SOULGOLD_V1_2_1")

    @Test
    fun `every hand-written config has a generated one and no more`() {
        assertEquals(68, legacy.size)
        assertEquals((legacy.keys + bornInGamedata).sorted(), generated.keys.sorted())
    }

    @Test
    fun `every generated config equals the hand-written one`() {
        val differ = legacy.keys.sorted().filter { legacy[it] != generated[it] }.map { name ->
            val old = legacy.getValue(name).toString().removeSurrounding("NativeConfig(", ")").split(", ")
            val new = generated.getValue(name).toString().removeSurrounding("NativeConfig(", ")").split(", ")
            "$name: ${(old - new.toSet()).joinToString()} -> ${(new - old.toSet()).joinToString()}"
        }
        assertTrue("generated configs that differ:\n" + differ.joinToString("\n"), differ.isEmpty())
    }

    @Test
    fun `every language port equals the one port_retail wrote in Kotlin`() {
        assertEquals(34, LEGACY_RETAIL_PORTS.size)
        assertEquals(LEGACY_RETAIL_PORTS.keys.sorted(), RETAIL_PORTS.keys.sorted())
        val differ = LEGACY_RETAIL_PORTS.keys.sorted().filter { LEGACY_RETAIL_PORTS.getValue(it)() != RETAIL_PORTS.getValue(it)() }
        assertTrue("generated ports that differ: $differ", differ.isEmpty())
    }

    @Test
    fun `every moved record equals the hand-written one`() {
        assertEquals(LEGACY_FIRERED_DEX_FLAGS, FIRERED_DEX_FLAGS)
        assertEquals(LEGACY_EMERALD_DEX_FLAGS, EMERALD_DEX_FLAGS)
        assertEquals(LEGACY_POKEDEX_FIRERED_REV1, POKEDEX_FIRERED_REV1)
        assertEquals(LEGACY_POKEDEX_LEAFGREEN_REV0, POKEDEX_LEAFGREEN_REV0)
        assertEquals(LEGACY_POKEDEX_LEAFGREEN_REV1, POKEDEX_LEAFGREEN_REV1)
        assertEquals(LEGACY_POKEDEX_EMERALD, POKEDEX_EMERALD)
        assertEquals(LEGACY_GUIDE_TABLES_FIRERED_REV1, GUIDE_TABLES_FIRERED_REV1)
        assertEquals(LEGACY_GUIDE_TABLES_LEAFGREEN_REV0, GUIDE_TABLES_LEAFGREEN_REV0)
        assertEquals(LEGACY_GUIDE_TABLES_LEAFGREEN_REV1, GUIDE_TABLES_LEAFGREEN_REV1)
        assertEquals(LEGACY_GUIDE_TABLES_EMERALD, GUIDE_TABLES_EMERALD)
        assertEquals(LEGACY_FIRERED_BAG_POCKET_ORDER, FIRERED_BAG_POCKET_ORDER)
        assertEquals(LEGACY_EMERALD_BAG_POCKET_ORDER, EMERALD_BAG_POCKET_ORDER)
        assertEquals(LEGACY_FLY_FIRERED_REV1, FLY_FIRERED_REV1)
        assertEquals(LEGACY_FLY_LEAFGREEN_REV0, FLY_LEAFGREEN_REV0)
        assertEquals(LEGACY_FLY_LEAFGREEN_REV1, FLY_LEAFGREEN_REV1)
        assertEquals(LEGACY_FLY_EMERALD, FLY_EMERALD)
        assertEquals(LEGACY_FIELD_ITEMS_FIRERED_REV1, FIELD_ITEMS_FIRERED_REV1)
        assertEquals(LEGACY_FIELD_ITEMS_LEAFGREEN_REV0, FIELD_ITEMS_LEAFGREEN_REV0)
        assertEquals(LEGACY_FIELD_ITEMS_LEAFGREEN_REV1, FIELD_ITEMS_LEAFGREEN_REV1)
        assertEquals(LEGACY_FIELD_ITEMS_EMERALD, FIELD_ITEMS_EMERALD)
        assertEquals(LEGACY_PC_FIRERED_REV1, PC_FIRERED_REV1)
        assertEquals(LEGACY_PC_LEAFGREEN_REV0, PC_LEAFGREEN_REV0)
        assertEquals(LEGACY_PC_LEAFGREEN_REV1, PC_LEAFGREEN_REV1)
        assertEquals(LEGACY_PC_EMERALD, PC_EMERALD)
        // A data class compares its arrays by identity: compare those by content, the rest as values.
        for ((old, new) in listOf(LEGACY_TRAINER_CARD_FIRERED to TRAINER_CARD_FIRERED, LEGACY_TRAINER_CARD_EMERALD to TRAINER_CARD_EMERALD)) {
            assertTrue(old.regionalDex.contentEquals(new.regionalDex))
            assertTrue(old.achievementIds.contentEquals(new.achievementIds))
            assertEquals(old.allCaughtStar?.toList(), new.allCaughtStar?.toList())
            val none = IntArray(0)
            assertEquals(old.copy(regionalDex = none, achievementIds = none, allCaughtStar = null),
                new.copy(regionalDex = none, achievementIds = none, allCaughtStar = null))
        }
        // The configs that name them get the generated ones.
        assertTrue(NATIVE_EMERALD_RETAIL.pokedex === POKEDEX_EMERALD && NATIVE_FIRERED_REV1.trainerCard === TRAINER_CARD_FIRERED)
        assertEquals(EMERALD_BAG_POCKET_ORDER, NATIVE_EMERALD.bagPocketOrder)
    }

    @Test
    fun `a copied config is still its own object`() {
        // fireRedHackFor tells Ghost Grey v1.0.1 from v1.0.2+ by identity, not by value.
        assertEquals(NATIVE_GHOST_GREY, NATIVE_GHOST_GREY_V1_0_1)
        assertNotSame(NATIVE_GHOST_GREY, NATIVE_GHOST_GREY_V1_0_1)
    }
}
