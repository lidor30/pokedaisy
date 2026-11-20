package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Gold and Silver (Game Boy Color) on a new game played headless in Gold, booted in both - see the gold* /
 * silver* fixtures' READMEs. Crystal's reader and structs at their own RAM ([GEN2_GOLD]), their own items and landmarks.
 */
class GoldSilverDecodeTest {
    private fun read(fixture: String, game: GameKind = GameKind.GOLD): Telemetry {
        activeGame = game
        romLanguage = 'E'
        romGameCode = ""
        return readGen2Telemetry(FixtureMemoryReader.load(fixture), if (game == GameKind.GOLD) GEN2_GOLD else GEN2_SILVER)
    }

    @Test
    fun `party, money, bag, place and dex - in Gold and in Silver`() {
        for ((fixture, game) in listOf("gold" to GameKind.GOLD, "silver" to GameKind.SILVER)) {
            val t = read(fixture, game)
            assertFalse(fixture, t.inBattle)
            assertEquals(fixture, listOf("CYNDAQUIL", "PIDGEY"), t.party.map { speciesName(it.species) })
            val cyndaquil = t.party[0]
            assertEquals(155 to 5, cyndaquil.species to cyndaquil.level)
            assertEquals(17 to 20, cyndaquil.hp to cyndaquil.maxHp)
            assertEquals(listOf("TACKLE", "LEER", "-", "-"), cyndaquil.moves.map { lookupMove(it).name })
            assertEquals("BERRY", itemName(cyndaquil.heldItem))
            assertEquals(16 to 2, t.party[1].species to t.party[1].level)
            assertEquals(13 to 13, t.party[1].hp to t.party[1].maxHp)
            assertEquals(3000L, t.money)
            assertEquals(listOf("POTION" to 1, "POKé BALL" to 3), t.items.map { itemName(it.itemId) to it.quantity })
            assertEquals(listOf(POCKET_ITEMS, POCKET_POKE_BALLS), t.items.map { it.pocket })
            assertEquals(24 to 3, t.mapGroup to t.mapNum)
            assertEquals("ROUTE 29", lookupLocation(t.regionMapSectionId).mapSecName)
            assertEquals(45 to 11, t.x to t.y)
            assertEquals(0, t.playerGender)   // no choice before Crystal: the boy
            assertEquals(setOf(16, 155), t.pokedex!!.caught)
            assertEquals(setOf(16, 155), t.pokedex!!.seen)
        }
    }

    @Test
    fun `battles - both battlers, and a trainer's six`() {
        val t = read("gold_battle")
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(155 to 5, me.species to me.level)
        assertEquals("FIRE", typeName(me.type1))
        assertEquals("BERRY", itemName(me.item))
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("PIDGEY" to 2, speciesName(foe.species) to foe.level)
        assertTrue(t.enemyParty.isEmpty())
        assertEquals(false, t.isTrainerBattle)
        val s = read("silver_battle", GameKind.SILVER).battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("SENTRET" to 2, speciesName(s.species) to s.level)
        assertEquals(13 to 13, s.hp to s.maxHp)
        val trainer = read("gold_trainer")
        assertEquals(true, trainer.isTrainerBattle)
        assertEquals(null, read("gold").isTrainerBattle)
        assertEquals(listOf("GYARADOS", "DRAGONITE", "DRAGONITE", "AERODACTYL", "CHARIZARD", "DRAGONITE"),
            trainer.enemyParty.map { speciesName(it.species) })
        assertEquals("GYARADOS" to 44, trainer.battleMons[BATTLE_POS_OPPONENT_LEFT].let { speciesName(it.species) to it.level })
    }

    @Test
    fun `battle menus - which one waits, and its cursor`() {
        fun input(f: String) = readGen2BattleInput(FixtureMemoryReader.load(f), GEN2_GOLD)
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, input("gold_battle"))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, input("gold_battle_menu"))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 0, input("gold_trainer"))
        assertEquals(BATTLE_INPUT_NONE to -1, input("gold"))
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen2BattleInput(FixtureMemoryReader.load("silver_battle"), GEN2_SILVER))
    }

    @Test
    fun `the POKéMON switch - its menus, the party by key and the one out`() {
        fun r(f: String) = FixtureMemoryReader.load(f)
        assertEquals(GbPartyMenu(submenu = false, cursor = GB_PARTY_CANCEL), readGen2PartyMenu(r("gold_battle_party"), GEN2_GOLD))
        assertEquals(GbPartyMenu(submenu = true, cursor = 1), readGen2PartyMenu(r("gold_battle_switch_menu"), GEN2_GOLD))
        assertEquals(GbPartyMenu(submenu = false, cursor = 1, forced = true), readGen2PartyMenu(r("gold_battle_faint"), GEN2_GOLD))
        for (f in listOf("gold_battle_menu", "gold_battle", "gold")) assertNull(f, readGen2PartyMenu(r(f), GEN2_GOLD))
        activeGame = GameKind.GOLD
        val s = Gen2PartySwitch(GEN2_GOLD)
        val party = r("gold_battle_party")
        assertEquals(readGen2Telemetry(party, GEN2_GOLD).party.map { it.personality }, s.party(party).map { it.key })
        assertEquals(0, s.outSlot(party))
        assertEquals(0, s.party(r("gold_battle_faint"))[0].hp)
    }
}
