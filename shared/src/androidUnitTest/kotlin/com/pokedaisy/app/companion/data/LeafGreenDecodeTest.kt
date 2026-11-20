package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Retail LeafGreen rev 0 and rev 1 with the same save (FEDE, Cinnabar Island).
 * The game's own screens: party ARTICUNO Lv53 161/161, MOLTRES Lv55 168/168,
 * MASTER (MEWTWO) Lv72 243/243, CIARME (CHARIZARD) Lv100 298/298, HAUNTER Lv25
 * 63/63, TOTTI (BLASTOISE) Lv68 195/195; ITEMS starts MOON STONE x2, LUCKY
 * PUNCH, SUN STONE, METAL COAT, AWAKENING, ICE HEAL; KEY ITEMS starts TEACHY
 * TV, TM CASE, FAME CHECKER, S.S. TICKET, VS SEEKER, BERRY POUCH; POKé BALLS
 * = POKé BALL x62; POKéDEX seen KANTO 142 / NATIONAL 180, owned 75 / 86.
 */
class LeafGreenDecodeTest {
    private val party = listOf(
        "ARTICUNO 53 161/161", "MOLTRES 55 168/168", "MEWTWO 72 243/243",
        "CHARIZARD 100 298/298", "HAUNTER 25 63/63", "BLASTOISE 68 195/195",
    )

    private fun check(fixture: String, cfg: NativeConfig, rom: String) {
        activeGame = GameKind.FIRERED
        val (t, romReader) = decodeRetail(fixture, cfg, rom)
        assertParty(t, party)
        val names = itemNamesFireRedGame
        assertEquals(
            listOf("MOON STONE x2", "LUCKY PUNCH x1", "SUN STONE x1", "METAL COAT x1", "AWAKENING x1", "ICE HEAL x1"),
            pocket(t, POCKET_ITEMS, names).take(6),
        )
        assertEquals(
            listOf("TEACHY TV", "TM CASE", "FAME CHECKER", "S.S. TICKET", "VS SEEKER", "BERRY POUCH").map { "$it x1" },
            pocket(t, POCKET_KEY_ITEMS, names).take(6),
        )
        assertEquals(listOf("POKé BALL x62"), pocket(t, POCKET_POKE_BALLS, names))
        val dex = t.pokedex!!
        assertEquals(180, dex.seen.size); assertEquals(142, dex.seen.count { it <= 151 })
        assertEquals(86, dex.caught.size); assertEquals(75, dex.caught.count { it <= 151 })
        assumeTrue("LeafGreen ROM not on this machine", romReader != null)
        assertTrue(pokedexMatchesRom(romReader!!, cfg.pokedex!!))
        PokedexSource.reader = romReader
        // LeafGreen's own dex text, not FireRed's.
        assertTrue(PokedexSource.entry(cfg.pokedex!!, 1)!!.description.startsWith("A strange seed was planted on its back at birth."))
        // The GUIDE's trainer / wild tables and the evolution table are LeafGreen's own too.
        assertTrue(guideTablesMatchRom(romReader, cfg.guideTables!!))
        assertEquals(2, EvolutionSource.table(cfg.pokedex!!)!![1].single().target) // BULBASAUR -> IVYSAUR
    }

    @Test fun rev0() = check("leafgreen_rev0", NATIVE_LEAFGREEN_REV0, "Pokemon - LeafGreen Version (USA).gba")
    @Test fun rev1() = check("leafgreen_rev1", NATIVE_LEAFGREEN_REV1, "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba")
}
