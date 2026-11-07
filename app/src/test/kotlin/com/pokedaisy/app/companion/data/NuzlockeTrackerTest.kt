package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NuzlockeTrackerTest {
    private val starter = NuzlockePartyMon(personality = 0x1111, species = 4, name = "STARTER", level = 5, hp = 20, maxHp = 20)
    private val catch = NuzlockePartyMon(personality = 0x2222, species = 16, name = "BIRD", level = 3, hp = 12, maxHp = 12)

    private fun field(vararg party: NuzlockePartyMon, area: Int = 101, caught: Set<Int>? = setOf(4)) =
        NuzlockeSample(false, null, area, "ROUTE $area", 0, "", party.toList(), caught)

    private fun wild(vararg party: NuzlockePartyMon, foe: Int = 16, area: Int = 101, caught: Set<Int>? = setOf(4), trainer: Boolean? = false) =
        NuzlockeSample(true, trainer, area, "ROUTE $area", foe, "FOE$foe", party.toList(), caught)

    /** A battle's first samples: the last battle's foe still in memory, the battle's menu not up yet. */
    private fun opening(vararg party: NuzlockePartyMon, staleFoe: Int, area: Int = 101, caught: Set<Int>? = setOf(4)) =
        wild(*party, foe = staleFoe, area = area, caught = caught).copy(menuUp = false)

    @Test
    fun theLastBattlesFoeIsNotThisBattlesEncounter() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        val events = t.feed(opening(starter, staleFoe = 23))
        assertTrue(events.isEmpty())
        assertEquals(null, t.run.encounterAt(101))
        t.feed(wild(starter, foe = 16))
        assertEquals(16, t.run.encounterAt(101)?.species)
        // The catch is seen, so it isn't marked missed (the report: every encounter MISSED).
        t.feed(wild(starter, catch, foe = 16))
        assertEquals(NuzlockeOutcome.CAUGHT, t.run.encounterAt(101)?.outcome)
    }

    @Test
    fun aStaleFoeDoesNotCallTheDupesClause() {
        val t = NuzlockeTracker(NuzlockeRun(rules = NuzlockeRules(dupes = true)))
        t.feed(field(starter, caught = setOf(4, 23)))
        assertTrue(t.feed(opening(starter, staleFoe = 23, caught = setOf(4, 23))).isEmpty())
        val events = t.feed(wild(starter, foe = 16, caught = setOf(4, 23)))
        assertFalse(events.any { it is NuzlockeEvent.Dupe })
        assertEquals(16, t.run.encounterAt(101)?.species)
    }

    @Test
    fun aGameWithNoBattleMenuReadStillGetsItsEncounter() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        repeat(NuzlockeTracker.FOE_SAMPLES) { t.feed(opening(starter, staleFoe = 16)) }
        assertEquals(null, t.run.encounterAt(101))
        t.feed(opening(starter, staleFoe = 16))
        assertEquals(16, t.run.encounterAt(101)?.species)
    }

    /** Past Gen 2 the game's species ids and the Pokédex's numbers differ: a catch sent to the PC is found by its number. */
    @Test
    fun aHoennCatchSentToThePcCountsByItsDexNumber() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        t.feed(wild(starter, foe = 277).copy(foeDex = 252))
        t.feed(wild(starter, foe = 277, caught = setOf(4, 252)).copy(foeDex = 252))
        assertEquals(NuzlockeOutcome.CAUGHT, t.run.encounterAt(101)?.outcome)
    }

    @Test
    fun theStartingPartyJoinsFromTheStart() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        assertEquals(listOf(""), t.run.mons.map { it.areaName })
    }

    @Test
    fun aCatchSettlesTheArea() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        val first = t.feed(wild(starter))
        assertTrue(first.single() is NuzlockeEvent.FirstEncounter)
        assertEquals(NuzlockeOutcome.NOW, t.run.encounterAt(101)!!.outcome)
        val caught = t.feed(wild(starter, catch, caught = setOf(4, 16)))
        assertEquals(NuzlockeEvent.Caught("BIRD", "ROUTE 101"), caught.single())
        assertEquals(NuzlockeOutcome.CAUGHT, t.run.encounterAt(101)!!.outcome)
        assertEquals("ROUTE 101", t.run.monOf(0x2222)!!.areaName)
    }

    @Test
    fun aCatchSentToThePcCountsByTheDex() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        t.feed(wild(starter))
        t.feed(field(starter, caught = setOf(4, 16)))
        assertEquals(NuzlockeOutcome.CAUGHT, t.run.encounterAt(101)!!.outcome)
    }

    @Test
    fun aBattleWithoutACatchIsMissedOnceSettled() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        t.feed(wild(starter))
        repeat(NuzlockeTracker.SETTLE_SAMPLES - 1) { t.feed(field(starter)) }
        assertEquals(NuzlockeOutcome.NOW, t.run.encounterAt(101)!!.outcome)
        t.feed(field(starter))
        assertEquals(NuzlockeOutcome.MISSED, t.run.encounterAt(101)!!.outcome)
    }

    @Test
    fun aSecondWildBattleInTheAreaWarns() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        t.feed(wild(starter))
        repeat(NuzlockeTracker.SETTLE_SAMPLES) { t.feed(field(starter)) }
        val events = t.feed(wild(starter, foe = 19))
        assertEquals(NuzlockeEvent.AreaUsed("ROUTE 101"), events.single())
        assertEquals(16, t.run.encounterAt(101)!!.species)
    }

    @Test
    fun trainerBattlesAndUnreadBattlesUseNothing() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        assertTrue(t.feed(wild(starter, trainer = true)).isEmpty())
        t.feed(field(starter))
        assertTrue(t.feed(wild(starter, trainer = null)).isEmpty())
        assertTrue(t.run.encounters.isEmpty())
    }

    @Test
    fun theDupesClauseSkipsACaughtSpecies() {
        val t = NuzlockeTracker(NuzlockeRun(rules = NuzlockeRules(dupes = true)))
        t.feed(field(starter))
        val events = t.feed(wild(starter, foe = 4))
        assertEquals(NuzlockeEvent.Dupe("FOE4"), events.single())
        assertTrue(t.run.encounters.isEmpty())
    }

    @Test
    fun aFaintIsFinalAfterTwoSamples() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        val down = starter.copy(hp = 0)
        assertTrue(t.feed(field(down)).isEmpty())
        assertEquals(NuzlockeEvent.Died("STARTER"), t.feed(field(down)).single())
        assertTrue(t.run.isDead(0x1111))
        // Healed at a Pokémon Center: still out, with a reminder.
        assertEquals(NuzlockeEvent.DeadInParty("STARTER"), t.feed(field(starter)).single())
        assertTrue(t.run.isDead(0x1111))
    }

    @Test
    fun aTornReadDoesNotKill() {
        val t = NuzlockeTracker()
        t.feed(field(starter))
        t.feed(field(starter.copy(hp = 0)))
        t.feed(field(starter))
        t.feed(field(starter.copy(hp = 0)))
        assertFalse(t.run.isDead(0x1111))
    }

    @Test
    fun theTitleScreenIsIgnored() {
        val t = NuzlockeTracker()
        assertTrue(t.feed(field()).isEmpty())
        assertTrue(t.run.mons.isEmpty())
    }

    @Test
    fun aRunSurvivesItsText() {
        val run = NuzlockeRun(
            encounters = listOf(NuzlockeEncounter(101, "ROUTE\t101", 16, NuzlockeOutcome.CAUGHT), NuzlockeEncounter(102, "ROUTE 102", 19, NuzlockeOutcome.NOW)),
            mons = listOf(NuzlockeMon(0xFFFFFFFFL, 4, "STARTER", 9, "", dead = true, diedIn = "ROUTE 102", diedLevel = 8)),
            rules = NuzlockeRules(dupes = true, warnings = false),
        )
        val back = NuzlockeRun.decode(run.encode())
        assertEquals("ROUTE 101", back.encounters[0].areaName)
        // A battle the game was closed in reads as missed.
        assertEquals(NuzlockeOutcome.MISSED, back.encounters[1].outcome)
        assertEquals(run.mons, back.mons)
        assertEquals(run.rules, back.rules)
        assertEquals(NuzlockeRun(), NuzlockeRun.decode(null))
    }
}
