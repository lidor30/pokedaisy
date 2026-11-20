package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Elysium v2.5.0, both parts: FireRed 1.0 edited in place, run as FIRERED with each part's own names (ActiveTables'
 * fireRedHack, ElysiumGen.kt). Part A on a new game played headless, Part B on retail FireRed's save (the fixtures' READMEs say
 * what the game's own screens show); a scripted wild battle on each.
 */
class ElysiumDecodeTest {
    @After fun tearDown() {
        fireRedHack = null
    }

    private fun use(cfg: NativeConfig) {
        activeGame = GameKind.FIRERED
        fireRedHack = fireRedHackFor(cfg)
    }

    @Test
    fun `part A - the new game's party and bag`() {
        use(NATIVE_ELYSIUM_A)
        val t = decodeNative("elysium_a", NATIVE_ELYSIUM_A)
        assertEquals(
            listOf(Triple("CHARMANDER", 10, 28), Triple("PIKACHU", 8, 25), Triple("MAGIKARP", 5, 17)),
            t.party.map { Triple(speciesName(it.species).uppercase(), it.level, it.maxHp) },
        )
        assertEquals(listOf("POTION" to 5, "REPEL" to 2, "POKE BALL" to 5), t.items.map { itemName(it.itemId).uppercase() to it.quantity })
        assertEquals(3000L, t.money)
        assertEquals("CRYSTA ISLAND", activeMapSecData[t.regionMapSectionId]?.name?.uppercase())
        assertFalse(t.inBattle)
        assertPocketsInRange(t)
    }

    @Test
    fun `part B - retail FireRed's save`() {
        use(NATIVE_ELYSIUM_B)
        val t = decodeNative("elysium_b", NATIVE_ELYSIUM_B)
        // Part B starts its finale with a team of its own: the game's party screen shows these six.
        assertEquals(
            listOf("SYLVEON" to 82, "SWAMPERT" to 82, "BLAZIKEN" to 81, "SALAMENCE" to 82, "ROSERADE" to 82, "MAGNEZONE" to 82),
            t.party.map { speciesName(it.species).uppercase() to it.level },
        )
        assertEquals(287 to 287, t.party[0].hp to t.party[0].maxHp)
        assertEquals(
            listOf("MAX REPEL" to 24, "REVIVE" to 21, "HYPER POTION" to 22, "MAX POTION" to 23, "FULL RESTORE" to 26, "FULL HEAL" to 36),
            t.items.take(6).map { itemName(it.itemId).uppercase() to it.quantity },
        )
        assertEquals("S.S. CHALLENGER", activeMapSecData[t.regionMapSectionId]?.name?.uppercase())
        assertFalse(t.inBattle)
        assertPocketsInRange(t)
    }

    @Test
    fun `a wild battle in each part`() {
        for ((key, cfg) in listOf("elysium_a_battle" to NATIVE_ELYSIUM_A, "elysium_b_battle" to NATIVE_ELYSIUM_B)) {
            use(cfg)
            val ram = FixtureMemoryReader.load(key)
            val t = decodeNative(key, cfg)
            assertTrue(key, t.inBattle)
            val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals(key, "PIKACHU" to 5, speciesName(foe.species).uppercase() to foe.level)
            assertEquals(key, 0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        }
        use(NATIVE_ELYSIUM_B)
        val me = decodeNative("elysium_b_battle", NATIVE_ELYSIUM_B).battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(listOf("MOONBLAST", "CALM MIND", "CHARM", "MOONLIGHT"), me.moves.map { lookupMove(it).name.uppercase() })
        assertEquals("Fairy", typeName(lookupMove(me.moves[0]).type))
    }
}
