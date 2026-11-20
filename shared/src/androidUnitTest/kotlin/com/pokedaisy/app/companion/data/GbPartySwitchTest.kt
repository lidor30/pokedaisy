package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battle POKéMON switch's reads on Yellow and Crystal ([GbPartySwitch]): their party menus opened from the battle
 * menu, the SWITCH / STATS / CANCEL menu over them, and the pick after a faint - see the yellow_battle_party /
 * _switch_menu / _faint and crystal_battle_party / _switch_menu / _faint fixtures' READMEs.
 */
class GbPartySwitchTest {
    private fun yellow(fixture: String) = FixtureMemoryReader.load(fixture)
    private fun crystal(fixture: String) = FixtureMemoryReader.load(fixture)

    @Test
    fun `Yellow - the party menu, its SWITCH menu and the pick after a faint`() {
        assertEquals(GbPartyMenu(submenu = false, cursor = 2), readGen1PartyMenu(yellow("yellow_battle_party"), GEN1_YELLOW))
        assertEquals(BATTLE_INPUT_PARTY_OPEN to 2, readGen1BattleInput(yellow("yellow_battle_party"), GEN1_YELLOW))
        assertEquals(GbPartyMenu(submenu = true, cursor = 1), readGen1PartyMenu(yellow("yellow_battle_switch_menu"), GEN1_YELLOW))
        assertEquals(BATTLE_INPUT_PARTY_OPEN to GB_SUBMENU_CURSOR + 1, readGen1BattleInput(yellow("yellow_battle_switch_menu"), GEN1_YELLOW))
        assertEquals(GbPartyMenu(submenu = false, cursor = 1, forced = true), readGen1PartyMenu(yellow("yellow_battle_faint"), GEN1_YELLOW))
        // Not a party menu: the battle menu, the move list, the field.
        for (f in listOf("yellow_battle_menu", "yellow_battle", "yellow")) assertNull(f, readGen1PartyMenu(yellow(f), GEN1_YELLOW))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen1BattleInput(yellow("yellow_battle_menu"), GEN1_YELLOW))
    }

    @Test
    fun `Yellow - the party by key, its HP and the one out`() {
        val r = yellow("yellow_battle_party")
        val s = Gen1PartySwitch(GEN1_YELLOW)
        val party = s.party(r)
        assertEquals(6, party.size)
        // The keys are what the party read hands the UI as each Pokémon's personality, and tell all six apart.
        assertEquals(readGen1Telemetry(r, GEN1_YELLOW).party.map { it.personality }, party.map { it.key })
        assertEquals(6, party.map { it.key }.toSet().size)
        assertTrue(party.all { it.key != 0L })
        assertEquals(listOf(182, 274, 450, 382, 300, 236), party.map { it.hp })
        assertEquals(0, s.outSlot(r))
        // After the faint: Joseph (slot 0, still the one out) at 0 HP.
        val f = yellow("yellow_battle_faint")
        assertEquals(0, s.party(f)[0].hp)
        assertEquals(0, s.outSlot(f))
    }

    @Test
    fun `Crystal - the party menu with CANCEL, its SWITCH menu and the pick after a faint`() {
        assertEquals(GbPartyMenu(submenu = false, cursor = GB_PARTY_CANCEL), readGen2PartyMenu(crystal("crystal_battle_party"), GEN2_CRYSTAL))
        assertEquals(BATTLE_INPUT_PARTY_OPEN to GB_PARTY_CANCEL, readGen2BattleInput(crystal("crystal_battle_party"), GEN2_CRYSTAL))
        assertEquals(GbPartyMenu(submenu = true, cursor = 1), readGen2PartyMenu(crystal("crystal_battle_switch_menu"), GEN2_CRYSTAL))
        assertEquals(GbPartyMenu(submenu = false, cursor = 1, forced = true), readGen2PartyMenu(crystal("crystal_battle_faint"), GEN2_CRYSTAL))
        for (f in listOf("crystal_battle_menu", "crystal_battle", "crystal")) assertNull(f, readGen2PartyMenu(crystal(f), GEN2_CRYSTAL))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen2BattleInput(crystal("crystal_battle_menu"), GEN2_CRYSTAL))
    }

    @Test
    fun `Crystal - the party by key, its HP and the one out`() {
        activeGame = GameKind.CRYSTAL
        val r = crystal("crystal_battle_party")
        val s = Gen2PartySwitch(GEN2_CRYSTAL)
        val party = s.party(r)
        assertEquals(readGen2Telemetry(r, GEN2_CRYSTAL).party.map { it.personality }, party.map { it.key })
        assertEquals(listOf(19, 14), party.map { it.hp })   // CHIKORITA, PIDGEY
        assertEquals(0, s.outSlot(r))
        assertEquals(listOf(0, 14), s.party(crystal("crystal_battle_faint")).map { it.hp })
    }

    @Test
    fun `the key is species, OT ID and DVs`() {
        assertEquals(0x98_EC78_626AL, gbMonKey(0x98, 0xEC78, 0x626A))
        assertEquals(gbMonKey(152, 0xEC78, 0x626A), Gen2PartySwitch(GEN2_CRYSTAL).party(crystal("crystal_battle_party"))[0].key)
    }
}
