package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Retail Ruby and Sapphire, revs 1 and 2, each with its own save - checked
 * against the games' own screens (see Gen3RetailSupport.kt).
 *
 * Ruby (FEDE, Slateport; the save's newer slot is inconsistent, so the game
 * itself says "The save file is corrupt" and loads the older one - which is
 * what these fixtures hold): RAYQUAZA Lv100 326/339, GROUDON Lv56 160/196,
 * ZAPDOS Lv68 224/224, FRAGOLETTA (BLAZIKEN) Lv100 310/312, CHARIZARD Lv100
 * 279/279, ARTICUNO Lv68 187/207; Items: POTION, MAX REPEL, PP UP, ZINC,
 * ESCAPE ROPE, WATER STONE, REVIVE (x1 each); Poké Balls: ULTRA x17, DIVE x3,
 * NET x2; Key Items start ITEMFINDER, MACH BIKE, SOOT SACK, METEORITE,
 * GO-GOGGLES, BASEMENT KEY, DEVON SCOPE, RED ORB; POKéDEX SEEN 133 OWN 29.
 *
 * Sapphire (AHS, Oldale Town): WAILORD Lv40 195/198, RAYQUAZA, KYOGRE, MEW,
 * CHARIZARD, MEWTWO (all Lv100); Items empty; ULTRA BALL x11; TM20 then HM01-;
 * Key Items start MACH BIKE, CONTEST PASS, OLD ROD, GO-GOGGLES, ITEMFINDER,
 * SUPER ROD, METEORITE, DEVON SCOPE; POKéDEX SEEN 199 OWN 159.
 */
class RubySapphireDecodeTest {
    private fun dexChecks(cfg: NativeConfig, romReader: RomFileReader?) {
        assumeTrue("ROM not on this machine", romReader != null)
        assertTrue(pokedexMatchesRom(romReader!!, cfg.pokedex!!))
        PokedexSource.reader = romReader
        val t = cfg.pokedex!!
        assertEquals(252, PokedexSource.regionalOrder(t)!!.first()) // TREECKO = HOENN No.001
        // Both pages of the dex text, joined.
        val charizard = PokedexSource.entry(t, 6)!!
        assertTrue(charizard.description, charizard.description.endsWith("never turns its fiery breath on any opponent weaker than itself."))
        assertEquals(2, EvolutionSource.table(t)!![1].single().target) // BULBASAUR -> IVYSAUR
    }

    private fun ruby(fixture: String, rom: String) {
        activeGame = GameKind.EMERALD
        val (t, romReader) = decodeRetail(fixture, NATIVE_RUBY, rom)
        assertParty(t, listOf(
            "RAYQUAZA 100 326/339", "GROUDON 56 160/196", "ZAPDOS 68 224/224",
            "BLAZIKEN 100 310/312", "CHARIZARD 100 279/279", "ARTICUNO 68 187/207",
        ))
        assertEquals(133, t.pokedex!!.seen.size)
        assertEquals(29, t.pokedex!!.caught.size)
        assumeTrue("Ruby's bag pockets are a ROM table", romReader != null)
        val names = itemNamesEmeraldGame
        assertEquals(
            listOf("POTION", "MAX REPEL", "PP UP", "ZINC", "ESCAPE ROPE", "WATER STONE", "REVIVE").map { "$it x1" },
            pocket(t, POCKET_ITEMS, names),
        )
        assertEquals(listOf("ULTRA BALL x17", "DIVE BALL x3", "NET BALL x2"), pocket(t, POCKET_POKE_BALLS, names))
        assertEquals(
            listOf("ITEMFINDER", "MACH BIKE", "SOOT SACK", "METEORITE", "GO-GOGGLES", "BASEMENT KEY", "DEVON SCOPE", "RED ORB").map { "$it x1" },
            pocket(t, POCKET_KEY_ITEMS, names).take(8),
        )
        dexChecks(NATIVE_RUBY, romReader)
    }

    private fun sapphire(fixture: String, rom: String) {
        activeGame = GameKind.EMERALD
        val (t, romReader) = decodeRetail(fixture, NATIVE_SAPPHIRE, rom)
        assertParty(t, listOf(
            "WAILORD 40 195/198", "RAYQUAZA 100 339/339", "KYOGRE 100 342/342",
            "MEW 100 353/353", "CHARIZARD 100 296/296", "MEWTWO 100 354/354",
        ))
        assertEquals(199, t.pokedex!!.seen.size)
        assertEquals(159, t.pokedex!!.caught.size)
        assumeTrue("Sapphire's bag pockets are a ROM table", romReader != null)
        val names = itemNamesEmeraldGame
        assertEquals(emptyList<String>(), pocket(t, POCKET_ITEMS, names))
        assertEquals(listOf("ULTRA BALL x11"), pocket(t, POCKET_POKE_BALLS, names))
        assertEquals("TM20 x1", pocket(t, POCKET_TM_HM, names).first())
        assertEquals(
            listOf("MACH BIKE", "CONTEST PASS", "OLD ROD", "GO-GOGGLES", "ITEMFINDER", "SUPER ROD", "METEORITE", "DEVON SCOPE").map { "$it x1" },
            pocket(t, POCKET_KEY_ITEMS, names).take(8),
        )
        dexChecks(NATIVE_SAPPHIRE, romReader)
    }

    @Test fun rubyRev1() = ruby("ruby_rev1", "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba")
    @Test fun rubyRev2() = ruby("ruby_rev2", "Pokemon - Ruby Version (USA, Europe) (Rev 2).gba")
    @Test fun sapphireRev1() = sapphire("sapphire_rev1", "Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba")
    @Test fun sapphireRev2() = sapphire("sapphire_rev2", "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba")
}
