package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Let's Go Pikachu / Eevee (and their Translation Only builds): retail FireRed rev 0's RAM, Rocket Edition's
 * repointed moves and its species grown to 987 ids (ActiveTables' fireRedHack, one table set per version), its Pokédex
 * flags in EWRAM. One new game's save loads in all four builds (the fixtures' READMEs, with what the game's own screens
 * show): its party, bag, money and dex; a scripted wild battle.
 */
class LetsGoDecodeTest {
    private fun use(cfg: NativeConfig) {
        activeGame = GameKind.FIRERED
        fireRedHack = fireRedHackFor(cfg)
    }

    @After fun tearDown() {
        fireRedHack = null
    }

    private val builds = listOf("lets_go_pikachu" to NATIVE_LETS_GO_PIKACHU, "lets_go_eevee" to NATIVE_LETS_GO_EEVEE)

    @Test
    fun `the save - both versions, as the game's own screens show it`() {
        for ((key, cfg) in builds) {
            use(cfg)
            val t = decodeNative(key, cfg)
            assertFalse(t.inBattle)
            // Its party screen: PIKACHU Lv10 34/34, EEVEE Lv9 31/31, FALINKS Lv12 41/41 (holding a POTION), SNOM Lv7
            // 21/21, FUECOCO Lv11 38/38 - species past FireRed's 411 (FALINKS 413) and in its Hoenn slots (SNOM 386).
            assertEquals(listOf("Pikachu", "Eevee", "Falinks", "Snom", "Fuecoco"), t.party.map { speciesName(it.species) })
            assertEquals(listOf(25, 133, 413, 386, 411), t.party.map { it.species })
            assertEquals(listOf(10, 9, 12, 7, 11), t.party.map { it.level })
            assertEquals(listOf(34 to 34, 31 to 31, 41 to 41, 21 to 21, 38 to 38), t.party.map { it.hp to it.maxHp })
            assertEquals(13, t.party[2].heldItem)
            assertEquals(listOf("ThunderShock", "Tail Whip", "Double Kick", "Thunder Wave"), t.party[0].moves.map { lookupMove(it).name })
            assertEquals(listOf("Powder Snow", "Struggle Bug"), t.party[3].moves.filter { it != 0 }.map { lookupMove(it).name })
            assertPocketsInRange(t)
            val bag = t.items.map { itemName(it.itemId) to it.quantity }.toSet()
            assertTrue(bag.toString(), bag.containsAll(listOf("Potion" to 5, "Poké Ball" to 10, "Ultra Ball" to 2, "Rare Candy" to 3, "TM01" to 1)))
            assertEquals(6000L, t.money)
            assertEquals("Pallet Town", activeMapSecData[t.regionMapSectionId]?.name)
        }
    }

    @Test
    fun `the dex - its own numbers in two EWRAM arrays`() {
        use(NATIVE_LETS_GO_PIKACHU)
        val dex = readPokedexState(FixtureMemoryReader.load("lets_go_pikachu"), NATIVE_LETS_GO_PIKACHU, POKEDEX_LETS_GO)!!
        // PIKACHU 25, EEVEE 133, SNOM its No. 313, FUECOCO its No. 358 (FALINKS has no number: no bit).
        assertEquals(setOf(25, 133, 313, 358), dex.caught)
        assertEquals(setOf(25, 133, 313, 358), dex.seen)
        // The battle against a WOOLOO (its No. 300) sets its seen bit alone.
        val battle = readPokedexState(FixtureMemoryReader.load("lets_go_pikachu_battle"), NATIVE_LETS_GO_PIKACHU, POKEDEX_LETS_GO)!!
        assertEquals(setOf(25, 133, 300, 313, 358), battle.seen)
        assertEquals(setOf(25, 133, 313, 358), battle.caught)
    }

    @Test
    fun `a scripted wild battle - both versions`() {
        for ((key, cfg) in listOf("lets_go_pikachu_battle" to NATIVE_LETS_GO_PIKACHU, "lets_go_eevee_battle" to NATIVE_LETS_GO_EEVEE)) {
            use(cfg)
            val ram = FixtureMemoryReader.load(key)
            val t = decodeNative(key, cfg)
            assertTrue(t.inBattle)
            val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
            assertEquals(25 to 10, me.species to me.level)
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals("Wooloo" to 5, speciesName(foe.species) to foe.level)
            assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
            assertEquals(listOf("ThunderShock", "Tail Whip", "Double Kick", "Thunder Wave"), me.moves.map { lookupMove(it).name })
        }
    }

    @Test
    fun `its own names, types and matchups, each version's exclusives`() {
        use(NATIVE_LETS_GO_PIKACHU)
        assertEquals("Pikachu", speciesName(25))
        assertEquals("Urshifu", speciesName(986))
        assertEquals(SpeciesTypes(23, 23), activeSpeciesTypeData[35])   // CLEFAIRY: FAIRY
        assertEquals("Fairy", activeTypeNames[23])
        assertEquals(0, activeTypeEffectiveness[1623])   // DRAGON can't touch FAIRY
        assertEquals(MoveInfo("Play Rough", 23, 90), activeMoveData[463])
        assertEquals("P-Wooper", speciesName(194))
        assertEquals(MoveInfo("Wicked Blow", 17, 80), activeMoveData[510])
        assertFalse(movesAreVanilla)
        assertNotNull(expProgress(986, 50, 1_000_000L))   // a growth rate past FireRed's 411 species
        use(NATIVE_LETS_GO_EEVEE)
        assertEquals("Wooper", speciesName(194))
        assertEquals(MoveInfo("SurgingStrks", 11, 25), activeMoveData[510])
    }

    @Test
    fun `detected by hash, each version its own table set`() {
        assertTrue(TelemetrySampler.fireRedHackConfig(TelemetrySampler.LETS_GO_PIKACHU_UNVERSIONED_SHA1) === NATIVE_LETS_GO_PIKACHU)
        assertTrue(TelemetrySampler.fireRedHackConfig(TelemetrySampler.LETS_GO_PIKACHU_TRANSLATION_ONLY_UNVERSIONED_SHA1) === NATIVE_LETS_GO_PIKACHU)
        assertTrue(TelemetrySampler.fireRedHackConfig(TelemetrySampler.LETS_GO_EEVEE_UNVERSIONED_SHA1) === NATIVE_LETS_GO_EEVEE)
        assertTrue(TelemetrySampler.fireRedHackConfig(TelemetrySampler.LETS_GO_EEVEE_TRANSLATION_ONLY_UNVERSIONED_SHA1) === NATIVE_LETS_GO_EEVEE)
        assertTrue(fireRedHackFor(NATIVE_LETS_GO_EEVEE) === fireRedHackLetsGoEevee)
        for (sha in listOf(TelemetrySampler.LETS_GO_PIKACHU_UNVERSIONED_SHA1, TelemetrySampler.LETS_GO_EEVEE_TRANSLATION_ONLY_UNVERSIONED_SHA1)) {
            assertTrue(sha in TelemetrySampler.SUPPORTED_HACK_SHA1S)
        }
    }
}
