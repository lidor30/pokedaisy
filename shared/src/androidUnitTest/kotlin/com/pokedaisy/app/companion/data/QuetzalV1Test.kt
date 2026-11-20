package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Quetzal Alpha 9 v1 (English / Spanish): v0 rebuilt, v0's RAM. The v0 saves booted in v1 decode as they do in v0
 * (the quetzal_v1 / quetzal_es_v1 fixtures against quetzal / quetzal_es), and with the ROMs here every table v1's config
 * points at reads what v0's reads: dex pages, evolutions, learnsets, boss teams, wild Pokémon.
 */
class QuetzalV1Test {
    @After fun reset() {
        quetzalNames = QuetzalNames()
        quetzalV1 = false
    }

    /** v1 decoded from its fixture, with the cartridge read from the ROM file (BAG4's item lists are in the ROM); null
     * without the ROM here. */
    private fun v1(key: String, cfg: NativeConfig, romFile: String): Telemetry? {
        resetNativeBagCache()
        val rom = RomFileReader.load(RETAIL_ROM_DIR + romFile) ?: return null
        return readNativeTelemetry(rom.withRam(FixtureMemoryReader.load(key)), cfg)
    }

    private fun same(v0: Telemetry, v1: Telemetry) {
        assertEquals(v0.party.map { listOf(it.species, it.level, it.hp, it.maxHp, it.exp, it.moves.toList(), it.pp.toList(), it.status) },
            v1.party.map { listOf(it.species, it.level, it.hp, it.maxHp, it.exp, it.moves.toList(), it.pp.toList(), it.status) })
        // The same items, quantities and pockets (v1 lists its pockets in its own order).
        assertEquals(v0.items.map { Triple(it.itemId, it.quantity, it.pocket) }.sortedBy { it.toString() },
            v1.items.map { Triple(it.itemId, it.quantity, it.pocket) }.sortedBy { it.toString() })
        assertEquals(v0.money, v1.money)
        assertEquals(v0.mapGroup to v0.mapNum, v1.mapGroup to v1.mapNum)
        assertEquals(v0.regionMapSectionId, v1.regionMapSectionId)
    }

    @Test
    fun `the v0 saves decode in v1 as in v0`() {
        activeGame = GameKind.QUETZAL
        // Without the ROM (BAG4's lists), the party and the rest still read.
        assertEquals(1, decodeNative("quetzal_v1", NATIVE_QUETZAL_V1).party.size)
        val en = v1("quetzal_v1", NATIVE_QUETZAL_V1, "Pokemon Quetzal (Alpha 9v1) (English).gba")
        assumeTrue("Quetzal v1 ROMs not on this machine", en != null)
        en!!
        same(decodeNative("quetzal", NATIVE_QUETZAL), en)
        assertEquals(listOf("Charmander" to 5), en.party.map { speciesNamesQuetzal[it.species] to it.level })
        assertEquals(10 to 19, en.party[0].hp to en.party[0].maxHp)
        assertEquals(7450L, en.money)
        assertEquals("Route 1", mapSecDataQuetzal.getValue(en.regionMapSectionId).name)
        assertEquals(QuetzalNames(), readQuetzalNames(FixtureMemoryReader.load("quetzal_v1"), NATIVE_QUETZAL_V1))

        val es = v1("quetzal_es_v1", NATIVE_QUETZAL_ES_V1, "Pokemon Quetzal (Alpha 9v1) (Spanish).gba")!!
        same(decodeNative("quetzal_es", NATIVE_QUETZAL_ES), es)
        assertEquals(1_048_458L, es.money)
        assertEquals(QuetzalNames(species = 1, moves = 1, items = 1, places = 1),
            readQuetzalNames(FixtureMemoryReader.load("quetzal_es_v1"), NATIVE_QUETZAL_ES_V1))
        assertEquals((1..6).map { 42 }, es.party.map { it.level })
        assertEquals(3403, es.items.first { it.itemId == 4 }.quantity)   // Master Ball
        assertEquals(18, es.items.count { it.pocket == POCKET_KEY_ITEMS })
    }

    @Test
    fun `battles - both battlers, and the action menu read by v1's handler`() {
        activeGame = GameKind.QUETZAL
        for ((key, cfg) in listOf("quetzal_v1_battle" to NATIVE_QUETZAL_V1, "quetzal_es_v1_battle" to NATIVE_QUETZAL_ES_V1)) {
            val ram = FixtureMemoryReader.load(key)
            val t = readNativeTelemetry(ram, cfg)
            assertTrue(key, t.inBattle)
            assertEquals(key, "Pidgey" to 3, t.battleMons[BATTLE_POS_OPPONENT_LEFT].let { speciesNamesQuetzal[it.species] to it.level })
            assertEquals(key, 0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
            // v0's handler there isn't the running one: the menu would go unread.
            val v0 = if (cfg === NATIVE_QUETZAL_V1) NATIVE_QUETZAL else NATIVE_QUETZAL_ES
            assertTrue(key, readNativeBattleInputFast(ram, v0)?.second != BATTLE_INPUT_ACTION_SELECT)
        }
        assertEquals("Charmander", speciesNamesQuetzal[readNativeTelemetry(FixtureMemoryReader.load("quetzal_v1_battle"), NATIVE_QUETZAL_V1)
            .battleMons[BATTLE_POS_PLAYER_LEFT].species])
    }

    @Test
    fun `v1's Latin American names of Johto's key items`() {
        activeGame = GameKind.QUETZAL
        quetzalNames = QuetzalNames(items = 2)
        assertEquals("Ovo Misterioso", itemName(889))   // v0: Portuguese
        quetzalV1 = true
        assertEquals("Huevo Misterioso", itemName(889))
        assertEquals("Pokébola GS", itemName(879))
    }

    private fun rom(file: String): RomFileReader? = RomFileReader.load(RETAIL_ROM_DIR + file)

    /** v1's tables read what v0's do, in each language - every species' page, family and learnset, every boss team, HERE. */
    @Test
    fun `every table reads what v0's reads`() {
        val details = ArrayList<Any?>()
        for ((v0File, v1File, cfgs) in listOf(
            Triple("Pokemon Quetzal (Alpha 9.0) (English).gba", "Pokemon Quetzal (Alpha 9v1) (English).gba", NATIVE_QUETZAL to NATIVE_QUETZAL_V1),
            Triple("Pokemon Quetzal (Alpha 9.0) (Spanish).gba", "Pokemon Quetzal (Alpha 9v1) (Spanish).gba", NATIVE_QUETZAL_ES to NATIVE_QUETZAL_ES_V1),
        )) {
            val r0 = rom(v0File)
            val r1 = rom(v1File)
            assumeTrue("Quetzal ROMs not on this machine", r0 != null && r1 != null)
            activeGame = GameKind.QUETZAL
            fun read(r: RomFileReader, cfg: NativeConfig): List<Any?> {
                PokedexSource.reader = r
                val t = cfg.pokedex!!
                val g = cfg.guideTables!!
                assertTrue(v1File, guideTablesMatchRom(r, g))
                val dex = (1..t.nationalCount).map { n ->
                    val s = PokedexSource.speciesFor(t, n)
                    listOf(s, PokedexSource.entry(t, n)?.let { listOf(it.category, it.heightDm, it.weightHg, it.description, it.abilities, it.hiddenAbility) })
                }
                val details = (1..t.speciesCount step 7).map { s -> DexDetails.family(t, s) to DexDetails.levelUp(t, g, s) }
                val bosses = GUIDE_QUETZAL.bosses.flatMap { b -> b.teams(SaveProgress(ByteArray(0x120), ByteArray(0x200))).map { (_, id) -> GuideRomSource.party(g, id) } }
                val here = listOf(0x25 to 0x4C, 24 to 13).map { (grp, num) -> GuideRomSource.encounters(g, grp, num)?.grass }
                assertTrue(v1File, dex.count { (it as List<*>)[1] != null } > 1000)
                assertTrue(v1File, bosses.count { !it.isNullOrEmpty() } > 20)
                return listOf(dex, details, bosses, here)
            }
            val v0 = read(r0!!, cfgs.first)
            val v1 = read(r1!!, cfgs.second)
            assertEquals(v1File, v0, v1)
            assertTrue(v1File, (v1[1] as List<*>).count { (it as Pair<*, *>).first != null } > 50)
            details += v0[1]
        }
        // Evolutions and learnsets are the same in both languages: the Spanish v0 config reads its own copies now.
        assertEquals(details[0], details[1])
    }
}
