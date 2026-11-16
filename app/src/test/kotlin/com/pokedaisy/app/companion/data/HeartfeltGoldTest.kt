package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Heartfelt Gold (HeartfeltGold): HeartGold's reader over its grown save, on the player's own save (the heartfelt_gold
 * fixtures' READMEs), and its tables read from the cart (skips without the ROM: `-DromDir=`'s ../nds/).
 */
class HeartfeltGoldTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon Heartfelt Gold (v1.0.15).nds")
    private val zoomed = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon Heartfelt Gold (v1.0.15) (Zoomed In).nds")

    private fun read(fixture: String): Telemetry {
        activeGame = GameKind.HEARTGOLD
        heartfeltGold = true
        romLanguage = 'E'
        romGameCode = HeartfeltGold.CODE
        return readGen4Telemetry(FixtureMemoryReader.load(fixture), GEN4_HEARTFELT_GOLD)
    }

    private fun sha1(f: File): String = java.security.MessageDigest.getInstance("SHA-1").let { md ->
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    @After
    fun reset() {
        heartfeltGold = false
        HeartfeltGold.data = null
        NdsArt.names = null
        romGameCode = ""
    }

    @Test
    fun `save - the party, its grown bag, money and the place`() {
        val t = read("heartfelt_gold")
        assertFalse(t.inBattle)
        assertEquals(listOf(255 to 7), t.party.map { it.species to it.level })
        assertEquals(23 to 23, t.party[0].hp to t.party[0].maxHp)
        assertEquals(listOf(10, 45, 52, 98), t.party[0].moves.toList())
        assertEquals(3000L, t.money)
        assertEquals(68, t.mapNum)
        assertEquals(4 to 7, t.x to t.y)
        fun pocket(p: Int) = t.items.filter { it.pocket == p }.map { it.itemId to it.quantity }
        // Items 245 slots, Key Items 92, Balls 26: its items past HeartGold's ids sit where its bag screen shows them.
        assertEquals(listOf(216 to 1, 231 to 1, 664 to 1), pocket(POCKET_ITEMS))
        assertEquals(listOf(1587 to 1, 468 to 1, 696 to 1), pocket(POCKET_KEY_ITEMS))
        assertEquals(listOf(17 to 5), pocket(POCKET_MEDICINE))
        assertEquals(listOf(4 to 5), pocket(POCKET_POKE_BALLS))
        assertNull(t.pokedex)
    }

    @Test
    fun `battle - HeartGold's battle system, a wild one`() {
        val t = read("heartfelt_gold_battle")
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(255 to 7, me.species to me.level)
        assertEquals(23 to 23, me.hp to me.maxHp)
        assertEquals(16 to 5, foe.species to foe.level)
        assertEquals(0 to 2, foe.type1 to foe.type2)   // NORMAL / FLYING
        assertEquals(listOf(16 to 5), t.battleFoes.map { it.species to it.level })
    }

    @Test
    fun `the cart - detection, names and its tables`() {
        if (!rom.isFile) return
        assertTrue(HeartfeltGold.matches(rom.toOkioPath()))
        assertEquals(HeartfeltGold.V1_0_15_SHA1, sha1(rom))
        if (zoomed.isFile) {
            assertTrue(HeartfeltGold.matches(zoomed.toOkioPath()))
            assertEquals(HeartfeltGold.V1_0_15_ZOOMED_IN_SHA1, sha1(zoomed))
        }
        // Retail HeartGold's cart isn't it.
        val retail = File(rom.parentFile, "Pokemon - HeartGold Version (USA).nds")
        if (retail.isFile) assertFalse(HeartfeltGold.matches(retail.toOkioPath()))
        val names = NdsArt.readNames(rom.toOkioPath())!!
        assertEquals("Torchic", names.species[255])
        assertEquals("Chespin", names.species[700])
        assertEquals("Supercell Slam", names.moves[919])
        assertEquals("Blazikenite", names.items[664])
        val data = NdsArt.readHeartfelt(rom.toOkioPath())!!
        assertEquals(SpeciesTypes(9, 9), data.speciesTypes[35])        // CLEFAIRY: Fairy
        assertEquals(SpeciesTypes(10, 10), data.speciesTypes[255])      // TORCHIC: Fire
        assertEquals("Pound" to 40, data.moveData.getValue(1).let { it.name to it.power })
        assertEquals(200, data.typeChart[9 * 100 + 16])                 // Fairy beats Dragon
        assertEquals(0, data.typeChart[16 * 100 + 9])                   // Dragon can't touch it
        assertEquals(2, data.moveCategories[45])                        // GROWL: status
    }
}
