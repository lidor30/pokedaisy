package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.QuickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The QUICK MENU's button: a press opens it for the D-pad + A; letting go never runs anything. */
class QuickMenuStateTest {
    private fun menu(ran: MutableList<QuickAction>) = QuickMenuState().apply {
        items = listOf(QuickAction.PC, QuickAction.BIKE, QuickAction.ROD)
        onRun = { ran += it }
    }

    @Test fun holdMoveRelease_runsNothing() {
        val ran = mutableListOf<QuickAction>()
        val m = menu(ran)
        m.trigger(true)
        m.key(MenuKey.RIGHT)
        m.trigger(false)
        assertEquals(emptyList<QuickAction>(), ran)
        assertTrue(m.open)
    }

    @Test fun tap_staysOpen_thenA() {
        val ran = mutableListOf<QuickAction>()
        val m = menu(ran)
        m.trigger(true)
        m.trigger(false)
        assertTrue(m.open)
        assertEquals(emptyList<QuickAction>(), ran)
        m.key(MenuKey.LEFT)
        m.key(MenuKey.A)
        assertEquals(listOf(QuickAction.ROD), ran)
        assertFalse(m.open)
    }

    @Test fun buttonAgainOrB_closes() {
        val m = menu(mutableListOf())
        m.trigger(true); m.trigger(false)
        m.trigger(true)
        assertFalse(m.open)
        m.trigger(false)
        m.trigger(true); m.trigger(false)
        m.key(MenuKey.B)
        assertFalse(m.open)
    }

    @Test fun dimmedTile_staysOpen() {
        val ran = mutableListOf<QuickAction>()
        val m = menu(ran)
        m.blocked = { if (it == QuickAction.PC) "Not during a battle" else null }
        m.trigger(true); m.trigger(false)
        m.key(MenuKey.A)
        assertTrue(m.open)
        assertEquals(emptyList<QuickAction>(), ran)
    }

    @Test fun ring_stickPointsAtATile_thenA() {
        val ran = mutableListOf<QuickAction>()
        val m = menu(ran)
        m.ring = true
        m.trigger(true)
        // Three tiles: PC at the top, BIKE lower right, ROD lower left.
        m.aim(-0.8f, 0.6f)
        assertEquals(2, m.index)
        m.trigger(false)
        assertEquals(emptyList<QuickAction>(), ran)
        m.key(MenuKey.A)
        assertEquals(listOf(QuickAction.ROD), ran)
    }

    @Test fun repel_oneKindUsedAtOnce_severalAsked() {
        val used = mutableListOf<Int>()
        val m = QuickMenuState().apply {
            items = listOf(QuickAction.REPEL)
            onRepel = { used += it }
        }
        m.repelKinds = listOf(84)
        m.trigger(true); m.trigger(false)
        m.key(MenuKey.A)
        assertEquals(listOf(84), used)
        assertFalse(m.open)
        m.repelKinds = listOf(86, 83, 84)
        m.trigger(true); m.trigger(false)
        m.key(MenuKey.A)
        assertTrue(m.open)
        assertEquals(listOf(86, 83, 84), m.repels)
        m.key(MenuKey.DOWN)
        m.key(MenuKey.A)
        assertEquals(listOf(84, 83), used)
        assertFalse(m.open)
    }

    /** A pick from a list closes it at once ([QuickMenuState.gone]: no tiles flashing back before the flight); B doesn't. */
    @Test fun listPick_closesAtOnce() {
        val used = mutableListOf<Int>()
        val m = QuickMenuState().apply {
            items = listOf(QuickAction.REPEL)
            onRepel = { used += it }
        }
        m.repelKinds = listOf(86, 83)
        m.trigger(true)
        m.key(MenuKey.A)
        m.key(MenuKey.A)
        assertEquals(listOf(86), used)
        assertTrue(m.gone)
        m.trigger(true)
        assertFalse(m.gone)
        m.key(MenuKey.B)
        assertFalse(m.open)
        assertFalse(m.gone)
    }

    /** HEAL asks first: NO goes back to the tiles, YES heals and closes at once. */
    @Test fun heal_asksFirst() {
        val ran = mutableListOf<QuickAction>()
        val m = QuickMenuState().apply {
            items = listOf(QuickAction.HEAL)
            onRun = { ran += it }
        }
        m.trigger(true)
        m.key(MenuKey.A)
        assertEquals(QuickAction.HEAL, m.confirm)
        assertTrue(ran.isEmpty())
        m.key(MenuKey.RIGHT)
        m.key(MenuKey.A)
        assertTrue(m.open)
        assertEquals(null, m.confirm)
        assertTrue(ran.isEmpty())
        m.key(MenuKey.A)
        m.key(MenuKey.A)
        assertEquals(listOf(QuickAction.HEAL), ran)
        assertFalse(m.open)
        assertTrue(m.gone)
    }
}
