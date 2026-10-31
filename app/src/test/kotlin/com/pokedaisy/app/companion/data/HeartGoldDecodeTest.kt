package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pokémon HeartGold / SoulSilver (Nintendo DS) on the player's own saves - see the heartgold / soulsilver fixtures'
 * READMEs. Slot 1 sits in RAM with its blocks in plaintext and its flags clear in both (the game holds it that way on
 * the field), so it's read the other way round. */
class HeartGoldDecodeTest {
    private fun read(game: GameKind, fixture: String, cfg: Gen4Config): Telemetry {
        activeGame = game
        romLanguage = 'E'
        romGameCode = ""
        return readGen4Telemetry(FixtureMemoryReader.load(fixture), cfg)
    }

    private fun Telemetry.team() = party.map { Triple(speciesName(it.species), it.level, it.hp to it.maxHp) }

    @Test
    fun `HeartGold - a full team, slot 1 held decrypted`() {
        val t = read(GameKind.HEARTGOLD, "heartgold", GEN4_HEARTGOLD_US)
        assertFalse(t.inBattle)
        assertEquals(
            listOf(
                Triple("MEGANIUM", 100, 321 to 321), Triple("AMPHAROS", 100, 320 to 320),
                Triple("UMBREON", 100, 341 to 341), Triple("NOCTOWL", 100, 351 to 351),
                Triple("QUAGSIRE", 100, 326 to 326), Triple("RATICATE", 100, 257 to 257),
            ),
            t.team(),
        )
        assertEquals(listOf("Discharge", "Signal Beam", "Power Gem", "Flash"), t.party[1].moves.map { lookupMove(it).name })
        assertEquals(0x83DE92E4L, t.party[1].personality)
        // The nicknames, as the game's party screen and battle print them.
        assertEquals(listOf("HDChipCard", "FlashDrive", "USBCharger", "Micro Chip", "MemoryCard", "Micro Card"), t.party.map { it.nickname })
    }

    @Test
    fun `HeartGold - money, the place and every pocket`() {
        val t = read(GameKind.HEARTGOLD, "heartgold", GEN4_HEARTGOLD_US)
        assertEquals(593506L, t.money)
        assertEquals(0, t.playerGender)
        assertEquals(76, t.mapNum)
        assertEquals("Goldenrod City", t.mapSecName)
        fun first(pocket: Int) = t.items.firstOrNull { it.pocket == pocket }?.let { itemName(it.itemId) to it.quantity }
        assertEquals("Miracle Seed" to 1, first(POCKET_ITEMS))
        assertEquals("Apricorn Box" to 1, first(POCKET_KEY_ITEMS))
        assertEquals("TM01" to 1, first(POCKET_TM_HM))
        assertEquals("Pecha Berry" to 3, first(POCKET_BERRIES))
        assertEquals("Master Ball" to 1, first(POCKET_POKE_BALLS))
        // The game's own pockets, each where the save keeps it: MEDICINE apart from ITEMS
        assertEquals("Potion" to 1, first(POCKET_MEDICINE))
        assertEquals(POCKET_ITEMS, basePocket(POCKET_MEDICINE))
        assertEquals(0, t.repelSteps)
    }

    @Test
    fun `SoulSilver - its own team and place, the same addresses`() {
        val t = read(GameKind.SOULSILVER, "soulsilver", GEN4_SOULSILVER_US)
        assertEquals(
            listOf("TYPHLOSION", "ESPEON", "PIDGEOT", "POLIWRATH", "FURRET", "RATICATE"),
            t.party.map { speciesName(it.species) },
        )
        assertEquals(Triple("ESPEON", 100, 276 to 276), t.team()[1])
        assertEquals(575648L, t.money)
        assertEquals(56, t.mapNum)
        assertEquals("Fuchsia City", t.mapSecName)
        assertEquals("Old Rod", t.items.filter { it.pocket == POCKET_KEY_ITEMS }.map { itemName(it.itemId) }[2])
    }

    @Test
    fun `DEX - the save's seen and caught flags`() {
        val dex = read(GameKind.HEARTGOLD, "heartgold", GEN4_HEARTGOLD_US).pokedex!!
        assertEquals(191 to 21, dex.seen.size to dex.caught.size)
        // the player's team (MEGANIUM, AMPHAROS, UMBREON, NOCTOWL, QUAGSIRE, RATICATE): caught, every one
        assertEquals(true, listOf(154, 181, 197, 164, 195, 20).all { it in dex.caught })
        assertEquals(true, dex.caught.all { it in dex.seen })
    }

    private fun reader(fixture: String): (Long, Int) -> ByteArray =
        FixtureMemoryReader.load(fixture).let { r -> { a, n -> r.readCoreMemory(a, n) } }

    @Test
    fun `FLY - the unlocked flypoints in the player's region, as the Pokegear offers them`() {
        // HeartGold in Goldenrod (Johto): Johto's towns and Indigo Plateau, no Kanto town
        val hg = reader("heartgold")
        assertEquals(11 to 11, gen4PokegearBlock(hg, GEN4_HEARTGOLD_US))
        val hgOpen = gen4OpenFlySpots(hg, GEN4_HEARTGOLD_US).map { GEN4_HEARTGOLD_US.flySpotName(it) }
        assertEquals(true, "Cherrygrove City" in hgOpen && "Indigo Plateau" in hgOpen)
        assertEquals(false, "Pallet Town" in hgOpen || "Fuchsia City" in hgOpen)
        // SoulSilver in Fuchsia (Kanto): the other way round
        val ss = reader("soulsilver")
        assertEquals(37 to 13, gen4PokegearBlock(ss, GEN4_SOULSILVER_US))
        val ssOpen = gen4OpenFlySpots(ss, GEN4_SOULSILVER_US).map { GEN4_SOULSILVER_US.flySpotName(it) }
        assertEquals(true, "Pallet Town" in ssOpen && "Indigo Plateau" in ssOpen)
        assertEquals(false, "Cherrygrove City" in ssOpen)
        // the spot flown to: Cherrygrove's block middle, as the headless flight landed it
        val cherrygrove = GEN4_HEARTGOLD_US.flySpots.first { GEN4_HEARTGOLD_US.mapPlaces[it.nameMap] == "Cherrygrove City" }
        assertEquals(Triple(67, 528, 400), Triple(cherrygrove.mapHeader, cherrygrove.x, cherrygrove.z))
    }

    @Test
    fun `MAP - the player's Pokegear cell and place, the flypoints and the Pokegear's state`() {
        val hg = read(GameKind.HEARTGOLD, "heartgold", GEN4_HEARTGOLD_US)
        assertEquals(11 to 13, hg.regionMapTile)   // block (11, 11), two rows down
        assertEquals("Goldenrod City", placeNamesHeartGold[hg.regionMapSectionId])
        assertEquals(2, hg.pokegearState)          // skin 0, all of Kanto unlocked
        // Pallet Town's flypoint flag is set on this post-game save
        assertEquals(true, PokegearMap.flypointSet(hg.townsVisited!!, PokegearMap.FIRST_FLYPOINT_FLAG + 0))
        val ss = read(GameKind.SOULSILVER, "soulsilver", GEN4_SOULSILVER_US)
        assertEquals("Fuchsia City", placeNamesHeartGold[ss.regionMapSectionId])
    }
}
