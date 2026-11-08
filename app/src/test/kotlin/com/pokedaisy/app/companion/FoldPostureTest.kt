package com.pokedaisy.app.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FOLD LAYOUT's postures: half open across = TABLETOP, down the middle = BOOK, flat = none (unless the hinge hides
 * pixels), in the stage's own coordinates. */
class FoldPostureTest {
    // A Galaxy Z Fold's inner screen held sideways: 2176 x 1812, the hinge a line across the middle.
    @Test fun tabletopAcrossTheMiddle() {
        val p = FoldPosture.of(true, false, true, 0, 906, 2176, 906, 0, 0, 2176, 1812)
        assertEquals(FoldPosture(FoldPosture.Kind.TABLETOP, 906, 906), p)
    }

    @Test fun bookDownTheMiddle() {
        val p = FoldPosture.of(true, false, false, 906, 0, 906, 2176, 0, 0, 1812, 2176)
        assertEquals(FoldPosture(FoldPosture.Kind.BOOK, 906, 906), p)
    }

    @Test fun flatIsNoPostureButASeparatingHingeIs() {
        assertNull(FoldPosture.of(false, false, true, 0, 906, 2176, 906, 0, 0, 2176, 1812))
        // Two screens with 84 px of hinge between them, spanned flat.
        assertEquals(
            FoldPosture(FoldPosture.Kind.BOOK, 1350, 1434),
            FoldPosture.of(false, true, false, 1350, 0, 1434, 1800, 0, 0, 2784, 1800),
        )
    }

    @Test fun inTheStagesCoordinates() {
        // The stage starts 40 px down the window (a cutout): the hinge moves up with it.
        assertEquals(FoldPosture(FoldPosture.Kind.TABLETOP, 866, 866), FoldPosture.of(true, false, true, 0, 906, 2176, 906, 0, 40, 2176, 1772))
    }

    @Test fun aHingeNearAnEdgeIsIgnored() {
        assertNull(FoldPosture.of(true, false, true, 0, 300, 2176, 300, 0, 0, 2176, 1812))
        assertNull(FoldPosture.of(true, false, true, 0, 906, 2176, 906, 0, 0, 0, 0))
    }
}
