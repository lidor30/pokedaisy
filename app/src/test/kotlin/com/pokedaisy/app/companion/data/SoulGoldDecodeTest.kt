package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** NATIVE_SOULGOLD on the user's save (see the soulgold fixture's README). */
class SoulGoldDecodeTest {
    // Several builds' fixtures share this class: start each one with no cached bag.
    @Before fun resetBag() = resetNativeBagCache()

    @Test
    fun `party - its own 96-byte struct`() {
        activeGame = GameKind.SOULGOLD
        val t = decodeNative("soulgold", NATIVE_SOULGOLD)
        assertFalse(t.inBattle)
        val mon = t.party.single()
        assertEquals(155, mon.species)
        assertEquals("Cyndaquil", speciesName(mon.species))
        assertEquals(6, mon.level)
        assertEquals(21 to 21, mon.hp to mon.maxHp)
        assertEquals(listOf("Tackle", "Leer"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals(listOf(35, 30), mon.pp.take(2))
        assertEquals(GENDER_SYMBOL_MALE, mon.genderSymbol) // the party menu's blue symbol
        // heldItem is 10 bits: the word is 0x0400 here, which showed an "Item#1024" tag on its party slot.
        assertEquals(0, mon.heldItem)
    }

    @Test
    fun `party - all six, the egg flagged`() {
        // gPlayerPartyCount is 0x02038DD5: the byte once used for it read 1 here, so only Beedrill showed.
        activeGame = GameKind.SOULGOLD
        val t = decodeNative("soulgold_party", NATIVE_SOULGOLD)
        assertEquals(6, t.party.size)
        assertEquals(listOf("Beedrill", "Tinkatuff", "Noibat", "Misdreavus", "Quilava"), t.party.take(5).map { speciesName(it.species) })
        // Its 12-byte nickname field at +0x08 (the egg's is junk, and the view keeps "Egg" instead).
        assertEquals(listOf("Beedrill", "Tinkatuff", "Noibat", "Misdreavus", "Quilava"), t.party.take(5).map { it.nickname })
        assertEquals(listOf(30, 10, 0, 71, 75), t.party.take(5).map { it.hp }) // poked (see the fixture's README)
        assertEquals(GENDER_SYMBOL_FEMALE, t.party[1].genderSymbol)
        val egg = t.party[5]
        assertTrue(egg.isEgg)
        assertEquals(1578, egg.species) // gSpeciesInfo's unnamed last entry: the egg icon
        assertFalse(t.party[0].isEgg)
        val view = buildSnapshotView(t)
        assertEquals("Egg", view.party[5].name)
        assertTrue(view.party[5].isEgg)
        assertEquals(null, view.party[5].nickname)
        assertEquals("Beedrill", view.party[0].nickname)
    }

    @Test
    fun `location, money and bag`() {
        activeGame = GameKind.SOULGOLD
        val t = decodeNative("soulgold", NATIVE_SOULGOLD)
        assertEquals(0xCD, t.regionMapSectionId) // a u16 mapsec
        assertEquals("Cherrygrove City", lookupLocation(t.regionMapSectionId).mapSecName)
        // MAP_TYPE_CITY, a byte later than vanilla (readMapShape also needs the ROM's layout).
        assertEquals(2, FixtureMemoryReader.load("soulgold").readCoreMemory(NATIVE_SOULGOLD.mapHeader + 0x18, 1)[0].toInt())
        assertEquals(3080L, t.money) // the trainer card
        val potion = t.items.single()
        assertEquals("Potion", itemName(potion.itemId))
        assertEquals(1, potion.quantity)
        assertEquals(POCKET_ITEMS, potion.pocket) // the Medicine pocket
    }

    @Test
    fun `battle - both battlers, the switched-in mon and the action menu`() {
        activeGame = GameKind.SOULGOLD
        val ram = FixtureMemoryReader.load("soulgold_battle")
        val t = readNativeTelemetry(ram, NATIVE_SOULGOLD)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(155 to 6, me.species to me.level)
        assertEquals(9 to 21, me.hp to me.maxHp) // the third Cyndaquil (13 HP), hit as it came in
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(25 to 5, foe.species to foe.level)
        assertEquals(18 to 18, foe.hp to foe.maxHp)
        assertEquals(14 to 14, foe.type1 to foe.type2) // Electric
        assertEquals(listOf(39, 84, 45, 86), foe.moves.toList()) // Tail Whip, Thunder Shock, Growl, Thunder Wave
        assertEquals(3, t.party.size)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_SOULGOLD))
        // gBattlerPartyIndexes[0]: the third slot is out.
        assertEquals(2, u16le(ram.readCoreMemory(NATIVE_SOULGOLD.battlerPartyIndexes, 2), 0))
        // The wild Pikachu sits in gEnemyParty (a wild battle shows no FOE TEAM).
        val foeMon = decodePartyMon(ram.readCoreMemory(NATIVE_SOULGOLD.enemyParty, 96), 0, SOULGOLD_PARTY_MON)!!
        assertEquals(25 to 5, (foeMon.species and 0x7FF) to foeMon.level)
    }

    @Test
    fun `v1_2 - the same save at its own addresses`() {
        activeGame = GameKind.SOULGOLD
        val t = decodeNative("soulgold_v12", NATIVE_SOULGOLD_V1_2)
        val mon = t.party.single()
        assertEquals(155 to 6, mon.species to mon.level)
        assertEquals(21 to 21, mon.hp to mon.maxHp)
        assertEquals(0, mon.heldItem) // nothing held (the word is 0x0400)
        assertEquals(listOf("Tackle", "Leer"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals("Cherrygrove City", lookupLocation(t.regionMapSectionId).mapSecName)
        assertEquals(3080L, t.money)
        assertEquals("Potion", itemName(t.items.single().itemId))
    }

    @Test
    fun `held item - the low 10 bits, the bits SoulGold sets above them left out`() {
        activeGame = GameKind.SOULGOLD
        val ram = FixtureMemoryReader.load("soulgold_v12")
        val raw = ram.readCoreMemory(NATIVE_SOULGOLD_V1_2.playerParty, 96)
        assertEquals(0x0400, u16le(raw, 0x22)) // the Cyndaquil's growth +2: nothing held
        raw[0x22] = (516 and 0xFF).toByte() // a Pecha Berry put in, bit 10 kept
        raw[0x23] = (0x0400 or 516 shr 8).toByte()
        val mon = decodePartyMon(raw, 0, SOULGOLD_PARTY_MON)!!
        assertEquals("Pecha Berry", itemName(mon.heldItem))
    }

    @Test
    fun `v1_2 battle - after the switch, at the action menu`() {
        activeGame = GameKind.SOULGOLD
        val ram = FixtureMemoryReader.load("soulgold_v12_battle")
        val cfg = NATIVE_SOULGOLD_V1_2
        val t = readNativeTelemetry(ram, cfg)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(155 to 6, me.species to me.level)
        assertEquals(13 to 21, me.hp to me.maxHp) // the third Cyndaquil
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(25 to 5, foe.species to foe.level)
        assertEquals(3, t.party.size)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        assertEquals(2, u16le(ram.readCoreMemory(cfg.battlerPartyIndexes, 2), 0))
        assertEquals(2, ram.readCoreMemory(cfg.partyMenu + 9, 1)[0].toInt()) // gPartyMenu.slotId, left on the switch
        val foeMon = decodePartyMon(ram.readCoreMemory(cfg.enemyParty, 96), 0, SOULGOLD_PARTY_MON)!!
        assertEquals(25 to 5, (foeMon.species and 0x7FF) to foeMon.level)
    }

    @Test
    fun `v1_2 second build - the same save at the same RAM addresses`() {
        activeGame = GameKind.SOULGOLD
        val t = decodeNative("soulgold_v12b", NATIVE_SOULGOLD_V1_2B)
        val mon = t.party.single()
        assertEquals(155 to 6, mon.species to mon.level)
        assertEquals(21 to 21, mon.hp to mon.maxHp)
        assertEquals(listOf("Tackle", "Leer"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals("Cherrygrove City", lookupLocation(t.regionMapSectionId).mapSecName)
        assertEquals(3080L, t.money)
        assertEquals("Potion", itemName(t.items.single().itemId))
    }

    @Test
    fun `v1_2 second build battle - at the action menu`() {
        activeGame = GameKind.SOULGOLD
        val ram = FixtureMemoryReader.load("soulgold_v12b_battle")
        val cfg = NATIVE_SOULGOLD_V1_2B
        val t = readNativeTelemetry(ram, cfg)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(155 to 6, me.species to me.level)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(25 to 5, foe.species to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        val foeMon = decodePartyMon(ram.readCoreMemory(cfg.enemyParty, 96), 0, SOULGOLD_PARTY_MON)!!
        assertEquals(25 to 5, (foeMon.species and 0x7FF) to foeMon.level)
    }

    @Test
    fun `v1_2 third build - the same save at the same RAM addresses`() {
        activeGame = GameKind.SOULGOLD
        val t = decodeNative("soulgold_v12c", NATIVE_SOULGOLD_V1_2C)
        val mon = t.party.single()
        assertEquals(155 to 6, mon.species to mon.level)
        assertEquals(21 to 21, mon.hp to mon.maxHp)
        assertEquals(listOf("Tackle", "Leer"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals("Cherrygrove City", lookupLocation(t.regionMapSectionId).mapSecName)
        assertEquals(3080L, t.money)
        assertEquals("Potion", itemName(t.items.single().itemId))
    }

    @Test
    fun `v1_2 third build battle - at the action menu`() {
        activeGame = GameKind.SOULGOLD
        val ram = FixtureMemoryReader.load("soulgold_v12c_battle")
        val cfg = NATIVE_SOULGOLD_V1_2C
        val t = readNativeTelemetry(ram, cfg)
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(155 to 6, me.species to me.level)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(25 to 5, foe.species to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        val foeMon = decodePartyMon(ram.readCoreMemory(cfg.enemyParty, 96), 0, SOULGOLD_PARTY_MON)!!
        assertEquals(25 to 5, (foeMon.species and 0x7FF) to foeMon.level)
    }

    @Test
    fun `v1_2 TM75 teaches Agility`() {
        activeGame = GameKind.SOULGOLD
        try {
            assertEquals("TM75 Swords Dance", itemName(SOULGOLD_V12_TM75))
            soulGoldV12 = true
            assertEquals("TM75 Agility", itemName(SOULGOLD_V12_TM75))
            assertEquals("Potion", itemName(28))
        } finally {
            soulGoldV12 = false
        }
    }
}
