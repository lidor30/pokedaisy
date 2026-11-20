package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The move advice on the original games' moves (MoveStatsGen.kt), no ROM needed. */
class MoveAdviceTest {
    @Before
    fun game() {
        activeGame = GameKind.FIRERED
        romGameCode = ""
    }

    // A Charmeleon-ish Fire type, a little better at Sp. Atk.
    private val charmander = AdviceMon(types = listOf(FIRE), attack = 30, spAttack = 33, level = 20, moves = listOf(SCRATCH, GROWL, EMBER, METAL_CLAW))

    @Test
    fun `the generated table is the games' own`() {
        assertEquals(355, vanillaMoveStats.size)
        assertEquals(MoveStats("BURN_HIT", 100, 15, 10, 0), moveStats(FLAMETHROWER))
        assertEquals(MoveStats("THUNDER", 70, 10, 30, 0), moveStats(THUNDER))
        assertEquals(MoveStats("SLEEP", 60, 20, 0, 0), moveStats(HYPNOSIS))
        assertEquals(1, moveStats(QUICK_ATTACK)!!.priority)
        // Only the original games' moves have it.
        activeGame = GameKind.UNBOUND
        assertNull(moveStats(FLAMETHROWER))
    }

    @Test
    fun `a stronger move of the same type replaces the weaker one, not the status move`() {
        val a = MoveAdvisor.advise(charmander, FLAMETHROWER)
        assertEquals(Verdict.REPLACE, a.verdict)
        assertEquals(EMBER, a.forget)
        assertTrue(a.reasons.any { it.text == "SAME TYPE, {0} vs {1} POWER" && it.args == listOf("95", "40") })
        assertTrue(a.reasons.any { it.text == "YOUR TYPE: x1.5" })
    }

    @Test
    fun `a weak status move isn't worth a slot`() {
        val a = MoveAdvisor.advise(charmander, LEER)
        assertEquals(Verdict.SKIP, a.verdict)
        assertEquals(0, a.forget)
    }

    @Test
    fun `new coverage is worth more than a duplicate`() {
        // Thunder Punch hits Water 2x, which nothing it knows does - and lands harder than Scratch
        // on everything Scratch was best against, so Scratch is the dead weight.
        val a = MoveAdvisor.advise(charmander, THUNDER_PUNCH)
        assertEquals(Verdict.REPLACE, a.verdict)
        assertEquals(SCRATCH, a.forget)
        assertTrue(a.reasons.any { it.text == "{0} DOES LITTLE" })
        assertTrue(a.reasons.any { it.text == "NEW 2x ON {0}" && "WATER" in it.args.single() })
    }

    @Test
    fun `free slots learn, known moves are known`() {
        val young = charmander.copy(moves = listOf(SCRATCH, GROWL))
        assertEquals(Verdict.LEARN, MoveAdvisor.advise(young, EMBER).verdict)
        assertEquals(Verdict.KNOWN, MoveAdvisor.advise(charmander, EMBER).verdict)
    }

    @Test
    fun `an HM is never forgotten`() {
        // CUT is its weakest move, but the game won't let it go.
        val cutter = charmander.copy(moves = listOf(CUT, EMBER, METAL_CLAW, SCRATCH))
        val a = MoveAdvisor.advise(cutter, FLAMETHROWER)
        assertEquals(Verdict.REPLACE, a.verdict)
        assertNotEquals(CUT, a.forget)
    }

    @Test
    fun `a boost to the stat it doesn't use is worth little`() {
        val special = charmander.copy(attack = 20, spAttack = 60)
        val physical = charmander.copy(attack = 45, spAttack = 33)
        assertEquals(Verdict.SKIP, MoveAdvisor.advise(special, SWORDS_DANCE).verdict)
        assertEquals(Verdict.REPLACE, MoveAdvisor.advise(physical, SWORDS_DANCE).verdict)
        // Ember and Metal Claw out-hit Scratch on every type, so Scratch is the one to go.
        assertEquals(SCRATCH, MoveAdvisor.advise(physical, SWORDS_DANCE).forget)
    }

    @Test
    fun `accuracy counts`() {
        val pikachu = AdviceMon(listOf(ELECTRIC), attack = 30, spAttack = 30, level = 30, moves = listOf(THUNDERBOLT, QUICK_ATTACK, THUNDER_WAVE, GROWL))
        // Thunder: more power, 70% accurate - not better than Thunderbolt.
        val a = MoveAdvisor.advise(pikachu, THUNDER)
        assertEquals(Verdict.SKIP, a.verdict)
    }

    private companion object {
        const val FIRE = 10
        const val ELECTRIC = 13
        const val SWORDS_DANCE = 14
        const val CUT = 15
        const val SCRATCH = 10
        const val LEER = 43
        const val GROWL = 45
        const val EMBER = 52
        const val FLAMETHROWER = 53
        const val THUNDER_PUNCH = 9
        const val THUNDERBOLT = 85
        const val THUNDER_WAVE = 86
        const val THUNDER = 87
        const val HYPNOSIS = 95
        const val QUICK_ATTACK = 98
        const val METAL_CLAW = 232
    }
}
