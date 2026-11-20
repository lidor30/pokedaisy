package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A few of scripts/port_retail.py's generated configs (RetailPortsGen.kt) on
 * dumps scripts/verify_ports.py made of them, against its dumps of the English
 * ROMs they were ported from (port_en_*: the same English save and boot - the
 * other languages load it as is), so a regenerated config that stops reading
 * them fails here without the ROMs. (verify_ports.py checks all of them.)
 */
class PortedRomsTest {
    private class Port(
        val key: String, val kind: GameKind, val english: String, val englishCfg: NativeConfig,
        val leader: String, val place: String,
    )

    private val ports = listOf(
        Port("BPRD0", GameKind.FIRERED, "port_en_bpre0", NATIVE_FIRERED_REV0, "BLITZA", "ROUTE 20"),
        Port("BPGS0", GameKind.FIRERED, "port_en_bpge1", NATIVE_LEAFGREEN_REV1, "ARTICUNO", "ISLA CANELA"),
        Port("AXPF1", GameKind.EMERALD, "port_en_axpe1", NATIVE_SAPPHIRE, "WAILORD", "ROSYERES"),
        Port("AXVJ1", GameKind.EMERALD, "port_en_axve1", NATIVE_RUBY, "レックウザ", "カイナシティ"),
    )

    private fun select(kind: GameKind, cfg: NativeConfig) {
        activeGame = kind
        romLanguage = cfg.language
        romGameCode = cfg.gameCode
        resetNativeBagCache()
    }

    @After fun english() {
        romLanguage = 'E'
        romGameCode = ""
    }

    @Test fun `the english save reads the same, in the game's own names`() = ports.forEach { p ->
        val cfg = RETAIL_PORTS.getValue(p.key)()
        select(p.kind, p.englishCfg)
        val en = readNativeTelemetry(FixtureMemoryReader.load(p.english), p.englishCfg)
        select(p.kind, cfg)
        val t = readNativeTelemetry(FixtureMemoryReader.load("port_" + p.key.lowercase()), cfg)
        assertEquals(p.key, en.party.map { it.species to it.level }, t.party.map { it.species to it.level })
        assertEquals(p.key, en.money, t.money)
        assertEquals(p.key, en.regionMapSectionId, t.regionMapSectionId)
        assertEquals(p.key, p.leader, speciesName(t.party.first().species))
        assertEquals(p.key, p.place, lookupLocation(t.regionMapSectionId).mapSecName)
        if (p.kind == GameKind.FIRERED) { // Ruby / Sapphire's pocket table is in ROM
            assertEquals(p.key, en.items.map { it.itemId to it.quantity }.toSet(), t.items.map { it.itemId to it.quantity }.toSet())
        }
    }

    /** Japanese FireRed can't read the English saves: its own, against what its trainer card shows. */
    @Test fun `japanese firered reads like its trainer card`() {
        val cfg = RETAIL_PORTS.getValue("BPRJ1")()
        select(GameKind.FIRERED, cfg)
        val t = readNativeTelemetry(FixtureMemoryReader.load("port_bprj1"), cfg)
        assertEquals(100171L, t.money)
        assertEquals("キングドラ", speciesName(t.party.first().species))
        assertEquals("ハナダシティ", lookupLocation(t.regionMapSectionId).mapSecName)
        assertTrue(t.items.isNotEmpty() && t.items.none { '#' in itemName(it.itemId) })
        val b = FixtureMemoryReader.load("port_bprj1/battle")
        assertEquals(25 to 5, readNativeTelemetry(b, cfg).battleMons[BATTLE_POS_OPPONENT_LEFT].let { it.species to it.level })
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(b, cfg))
    }

    @Test fun `a wild battle at the action menu`() = ports.forEach { p ->
        val cfg = RETAIL_PORTS.getValue(p.key)()
        select(p.kind, cfg)
        val ram = FixtureMemoryReader.load("port_" + p.key.lowercase() + "/battle")
        val t = readNativeTelemetry(ram, cfg)
        assertTrue(p.key, t.inBattle)
        assertEquals(p.key, 25 to 5, t.battleMons[BATTLE_POS_OPPONENT_LEFT].let { it.species to it.level })
        if (cfg.hasBattleInputAddrs) assertEquals(p.key, 0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
    }

    @Test fun `every game code gets its config and names`() {
        RETAIL_PORTS.forEach { (key, make) ->
            val cfg = make()
            val code = key.dropLast(1)
            assertEquals(key, cfg, TelemetrySampler.otherRetailConfig(code, key.last().digitToInt()))
            assertTrue(key, code in TelemetrySampler.OTHER_RETAIL_CODES)
            if (cfg.language != 'E') {
                val text = gameText(cfg.gameCode)!!
                assertEquals(key, 386, text.species.size)
                assertEquals(key, 25, text.natures.size)
            }
        }
    }
}
