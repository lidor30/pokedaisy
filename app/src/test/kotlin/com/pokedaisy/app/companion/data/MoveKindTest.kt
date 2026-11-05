package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The move category icon's rule: by type up to Gen 3, the DS games' own table after that, nothing where it isn't known. */
class MoveKindTest {
    private val before = activeGame
    @After fun restore() { activeGame = before }

    private fun mv(type: Int, power: Int, id: Int = 1) = MoveView("", typeName(type), 10, power, id = id)

    @Test fun `Gen 3 goes by type`() {
        activeGame = GameKind.FIRERED
        assertEquals(MoveKind.PHYSICAL, moveKind(mv(0, 40)))     // NORMAL
        assertEquals(MoveKind.PHYSICAL, moveKind(mv(7, 40)))     // GHOST
        assertEquals(MoveKind.SPECIAL, moveKind(mv(10, 40)))     // FIRE
        assertEquals(MoveKind.SPECIAL, moveKind(mv(17, 40)))     // DARK
        assertEquals(MoveKind.STATUS, moveKind(mv(0, 0)))
    }

    @Test fun `the DS games use the move's own category`() {
        activeGame = GameKind.PLATINUM
        assertEquals(MoveKind.PHYSICAL, moveKind(MoveView("", "Normal", 10, 40, id = 1)))     // POUND
        assertEquals(MoveKind.SPECIAL, moveKind(MoveView("", "Electric", 10, 95, id = 85)))   // THUNDERBOLT
        assertEquals(MoveKind.STATUS, moveKind(MoveView("", "Normal", 10, 0, id = 45)))       // GROWL
        // Fire punch by type would be special: after the split it's physical.
        assertEquals(MoveKind.PHYSICAL, moveKind(MoveView("", "Fire", 10, 75, id = 7)))
    }

    @Test fun `a game with moves of its own shows nothing`() {
        activeGame = GameKind.UNBOUND
        assertNull(moveKind(mv(0, 40)))
    }
}
