package com.pokedaisy.app

import com.pokedaisy.app.companion.ui.fittedGamePicture
import org.junit.Assert.*
import org.junit.Test

class GamePictureTest {
    @Test fun nativePicturesFitPhonesTabletsAndZeroSizedTransitions() {
        for ((w,h) in listOf(0f to 0f, 400f to 800f, 800f to 400f, 744f to 1134f, 1134f to 744f)) {
            for (aspect in listOf(1.5f, 160f/144)) for (top in listOf(false,true)) {
                val r = fittedGamePicture(w,h,aspect,top)
                assertTrue(r.left >= 0 && r.top >= 0 && r.right <= w && r.bottom <= h)
                if (r.height > 0) assertEquals(aspect,r.width/r.height,.0001f)
                assertEquals(w/2,r.center.x,.001f)
                assertEquals(if (top) 0f else (h-r.height)/2,r.top,.001f)
            }
        }
    }
    @Test fun portraitKeepsThePictureAtTheTopOfTheSpaceAboveTheCompanion() {
        val upright = fittedGamePicture(400f,500f,1.5f,true)
        val sideways = fittedGamePicture(400f,500f,1.5f)
        assertEquals(0f,upright.top,0f)
        assertTrue(sideways.top > upright.top)
        assertEquals(upright.width,sideways.width,.001f)
        assertEquals(upright.height,sideways.height,.001f)
    }
}
