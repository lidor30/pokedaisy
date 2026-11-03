package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quetzal Alpha 0.8.4 (English / Spanish) and its Moemon Hotfix builds, each booted with Spanish Alpha 9's save (no 0.8.4
 * save exists): the party, money, map and dex as that save left them; the bag is that save's BAG3 stream read as 0.8.4's
 * slots - the game's own bag screen shows the same (Great Ball x9999 three times, x9590, ...). Every IDIOMA option on the
 * save is Spanish, so species / moves / items read in Spanish in all four; only the map names follow the build.
 */
class QuetzalV084DecodeTest {
    private class Build(val key: String, val cfg: NativeConfig, val spanish: Boolean)

    private val builds = listOf(
        Build("quetzal_084", NATIVE_QUETZAL_V084, false),
        Build("quetzal_es_084", NATIVE_QUETZAL_V084_ES, true),
        Build("quetzal_moemon_084", NATIVE_QUETZAL_MOEMON_V084, false),
        Build("quetzal_moemon_es_084", NATIVE_QUETZAL_MOEMON_V084_ES, true),
    )

    @After fun reset() {
        quetzalNames = QuetzalNames()
        quetzalV084 = false
    }

    private fun select(key: String, cfg: NativeConfig) {
        activeGame = GameKind.QUETZAL
        quetzalV084 = cfg.quetzalV084
        quetzalNames = readQuetzalNames(FixtureMemoryReader.load(key), cfg)
    }

    @Test
    fun `the IDIOMA options - map names by build`() {
        for (b in builds) {
            // 0.8.4 has no LUGARES option: its byte isn't read.
            assertEquals(b.key, QuetzalNames(species = 1, moves = 1, items = 1, places = if (b.spanish) 1 else 0),
                readQuetzalNames(FixtureMemoryReader.load(b.key), b.cfg))
        }
    }

    @Test
    fun `party, bag, location and money decode in every build`() {
        for (b in builds) {
            select(b.key, b.cfg)
            val t = decodeNative(b.key, b.cfg)
            assertEquals(b.key, listOf("Gyarados", "Charizard", "Primeape", "Pidgeot", "Rapidash", "Kadabra"), t.party.map { speciesName(it.species) })
            assertEquals(listOf(145, 132, 117, 135, 122, 99), t.party.map { it.maxHp })
            assertTrue(t.party.all { it.level == 42 && it.hp == it.maxHp && !it.isEgg })
            // The summary's BATTLE MOVES, in Spanish as the save asks (TRITURAR 24/24, SURF, MORDISCO 39/40, CASCADA).
            assertEquals(listOf("Triturar", "Surf", "Mordisco", "Cascada"), t.party[0].moves.map { lookupMove(it).name })
            assertEquals(listOf(24, 24, 39, 24), t.party[0].pp.toList())
            // The bag as 0.8.4's bag screen shows it (Spanish names by the save's option).
            val balls = t.items.filter { it.pocket == POCKET_POKE_BALLS }.map { itemName(it.itemId) to it.quantity }
            assertEquals(
                listOf("Super Ball" to 9999, "Super Ball" to 9999, "Super Ball" to 9999, "Super Ball" to 9590,
                    "Buceo Ball" to 9999, "Buceo Ball" to 9999, "Buceo Ball" to 5952,
                    "Radiante Ball" to 9999, "Radiante Ball" to 9999, "Radiante Ball" to 5952),
                balls,
            )
            assertEquals(listOf(841 to 9999, 841 to 4321), t.items.filter { it.itemId == 841 }.map { it.itemId to it.quantity })
            assertTrue(t.items.none { it.pocket == POCKET_KEY_ITEMS })
            assertPocketsInRange(t)
            assertEquals(24 to 13, t.mapGroup to t.mapNum)
            assertEquals(0x4C, t.regionMapSectionId)
            val sec = activeMapSecData.getValue(t.regionMapSectionId)
            assertEquals(if (b.spanish) "Desfiladero" else "Jagged Pass", sec.name)
            // On Emerald's Hoenn map, Emerald's rect.
            assertEquals(MapSecInfo(sec.name, 0, 7, 5, 1, 1), sec)
            assertEquals("Jagged Pass", englishMapSecName(t.regionMapSectionId, sec.name))
            assertEquals(1_048_458L, t.money)
            assertFalse(t.inBattle)
        }
    }

    @Test
    fun `its own move powers, Alpha 9's elsewhere`() {
        activeGame = GameKind.QUETZAL
        quetzalV084 = true
        fun move(id: Int) = lookupMove(id).let { MoveInfo(it.name, it.type, it.power) }
        assertEquals(MoveInfo("Slash", 0, 70), move(163))
        assertEquals(MoveInfo("Snap Trap", 12, 35), move(707))
        assertEquals(moveDataQuetzal.getValue(33), move(33))
        quetzalNames = QuetzalNames(moves = 1)
        assertEquals(MoveInfo("Cuchillada", 0, 70), move(163))
        quetzalNames = QuetzalNames()
        quetzalV084 = false
        assertEquals(80, lookupMove(163).power)
        assertEquals(activeRegionMapImages.toList(), listOf("quetzal_kanto", "quetzal_sevii123", "quetzal_sevii45", "quetzal_sevii67", "hoenn", "quetzal_johto"))
        quetzalV084 = true
        assertEquals(listOf("hoenn"), activeRegionMapImages.toList())
    }

    @Test
    fun `a scripted wild battle - each build's battle handlers`() {
        for (b in builds) {
            val key = b.key + "_battle"
            select(key, b.cfg)
            val ram = FixtureMemoryReader.load(key)
            resetNativeBagCache()
            val t = readNativeTelemetry(ram, b.cfg)
            assertTrue(key, t.inBattle)
            assertFalse(t.isDoubleBattle)
            assertEquals("Gyarados" to 42, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { speciesName(it.species) to it.level })
            assertEquals(145 to 145, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { it.hp to it.maxHp })
            assertEquals("Pidgey" to 3, t.battleMons[BATTLE_POS_OPPONENT_LEFT].let { speciesName(it.species) to it.level })
            assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, b.cfg))
            val foe = decodePartyMon(ram.readCoreMemory(b.cfg.enemyParty, 0x68), 0, QUETZAL_PARTY_MON)!!
            assertEquals("Pidgey" to 3, speciesName(foe.species) to foe.level)
            assertEquals(0, u16le(ram.readCoreMemory(b.cfg.battlerPartyIndexes, 2), 0))
            // The other language's handlers don't match this build's code.
            val other = if (b.spanish) NATIVE_QUETZAL_V084 else NATIVE_QUETZAL_V084_ES
            assertEquals(null, readNativeBattleInputFast(ram, other)?.takeIf { it.second == BATTLE_INPUT_ACTION_SELECT })
        }
    }
}
