package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/**
 * The GUIDE's live tables read from the retail FireRed ROM (skipped when it
 * isn't on this machine), checked against the pokefirered decomp's own data
 * (wild_encounters.json, trainer_parties.h).
 */
class FireRedGuideRomTest {
    private val t = GUIDE_TABLES_FIRERED_REV1

    @Before
    fun load() {
        val rom = RomFileReader.load(RomFileReader.FIRERED_REV1_PATH)
        assumeNotNull(rom)
        activeGame = GameKind.FIRERED
        PokedexSource.reader = rom!!
        assertTrue(guideTablesMatchRom(rom, t))
    }

    /** sRoute24_FireRed: repeated species folded together, odds summed from src/wild_encounter.c's slot rates. */
    @Test fun route24() {
        val e = GuideRomSource.encounters(t, 3, 43)!!
        assertEquals(
            listOf(
                EncounterSlot(43, 12, 14, 25), EncounterSlot(13, 7, 7, 20), EncounterSlot(10, 7, 7, 20),
                EncounterSlot(16, 11, 13, 15), EncounterSlot(63, 8, 12, 15), EncounterSlot(14, 8, 8, 4),
                EncounterSlot(11, 8, 8, 1),
            ),
            e.grass,
        )
        assertEquals(listOf(EncounterSlot(72, 5, 40, 100)), e.water)
        assertTrue(e.rockSmash.isEmpty())
    }

    @Test fun noWildPokemon() = assertTrue(GuideRomSource.encounters(t, 0x7F, 0x7F)!!.isEmpty)

    /** BROCK: custom moves (TACKLE, DEFENSE CURL / TACKLE, BIND, ROCK TOMB). */
    @Test fun brock() = assertEquals(
        listOf(TrainerMon(74, 12, 0, listOf(33, 111)), TrainerMon(95, 14, 0, listOf(33, 20, 317))),
        GuideRomSource.party(t, 414),
    )

    /** BUG CATCHER RICK's default moves come from the level-up learnsets. */
    @Test fun defaultMoves() = assertEquals(
        listOf(TrainerMon(13, 6, 0, listOf(40, 81)), TrainerMon(10, 6, 0, listOf(33, 81))),
        GuideRomSource.party(t, 102),
    )

    /** The champion answers the player's starter; the League switches to rematch teams after the SEVII story. */
    @Test fun champion() {
        val champ = GUIDE_FIRERED.bosses.last()
        fun progress(starter: Int, rematch: Boolean) = SaveProgress(
            ByteArray(0x120).also { if (rematch) it[0x844 / 8] = (1 shl (0x844 % 8)).toByte() },
            ByteArray(0x200).also { it[0x31 * 2] = starter.toByte() },
        )
        assertEquals(438, champ.trainer(progress(2, false))) // CHARMANDER -> rival's SQUIRTLE
        assertEquals(440, champ.trainer(progress(0, false))) // BULBASAUR -> rival's CHARMANDER
        assertEquals(740, champ.trainer(progress(1, true)))
    }
}
