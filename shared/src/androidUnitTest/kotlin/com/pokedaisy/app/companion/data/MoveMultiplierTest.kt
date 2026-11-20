package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Moves the type chart can't tell (a Seaglass player's report: Freeze-Dry read RESISTED on a Water foe). */
class MoveMultiplierTest {
    private var game = activeGame
    private val ice get() = typeIdOf("Ice")
    private val water get() = typeIdOf("Water")
    private val ground get() = typeIdOf("Ground")
    private val flying get() = typeIdOf("Flying")
    private val fighting get() = typeIdOf("Fighting")
    private val rock get() = typeIdOf("Rock")
    private val normal get() = typeIdOf("Normal")

    @Before fun seaglass() {
        game = activeGame
        activeGame = GameKind.EMERALD_SEAGLASS
    }

    @After fun back() {
        activeGame = game
    }

    @Test fun freezeDryIsSuperEffectiveOnWater() {
        assertEquals(50, typeMultiplierPct(ice, water, TYPE_NONE))   // the chart alone: what the companion showed
        assertEquals(200, moveMultiplierPct("Freeze-Dry", ice, water, TYPE_NONE))
        assertEquals(400, moveMultiplierPct("Freeze-Dry", ice, water, ground))
        assertEquals(100, moveMultiplierPct("Freeze-Dry", ice, water, ice))
    }

    @Test fun freezeDryIsPlainIceOnEverythingElse() {
        assertEquals(typeMultiplierPct(ice, ground, flying), moveMultiplierPct("Freeze-Dry", ice, ground, flying))
    }

    @Test fun everySpellingCounts() {
        for (name in listOf("FREEZE-DRY", "Freeze Dry", "Lyophilisat.", "Liofilización", "Lioflización")) {
            assertEquals(name, 200, moveMultiplierPct(name, ice, water, TYPE_NONE))
        }
    }

    @Test fun flyingPressIsFightingAndFlying() {
        assertEquals(200, moveMultiplierPct("Flying Press", fighting, normal, TYPE_NONE))   // 2x, 1x
        assertEquals(100, moveMultiplierPct("Flying Press", fighting, rock, TYPE_NONE))     // 2x, 0.5x
        assertEquals(100, moveMultiplierPct("Pl. Voladora", fighting, rock, TYPE_NONE))
    }

    @Test fun thousandArrowsHitsFlyingForNeutral() {
        assertEquals(0, typeMultiplierPct(ground, flying, TYPE_NONE))
        assertEquals(100, moveMultiplierPct("Thousand Arrows", ground, flying, TYPE_NONE))
        assertEquals(100, moveMultiplierPct("Mil Flechas", ground, rock, flying))
        assertEquals(200, moveMultiplierPct("Thousand Arrows", ground, rock, TYPE_NONE))
    }

    @Test fun otherMovesFollowTheChart() {
        assertEquals(50, moveMultiplierPct("Ice Beam", ice, water, TYPE_NONE))
        assertEquals(0, moveMultiplierPct("Earthquake", ground, flying, TYPE_NONE))
    }
}
