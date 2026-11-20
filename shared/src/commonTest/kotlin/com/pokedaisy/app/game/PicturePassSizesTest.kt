package com.pokedaisy.app.game

import kotlin.test.*

class PicturePassSizesTest {
    @Test fun integerFiltersAndReflectivePassesRetainWholeSourcePixels() {
        assertEquals(PicturePassSizes(6,6,1440,960,1440,960),picturePassSizes(240,160,1620f,1080f,true))
        assertEquals(PicturePassSizes(6,6,1440,960,1620,1080),picturePassSizes(240,160,1620f,1080f,false))
        assertEquals(PicturePassSizes(2,3,320,432,320,432),picturePassSizes(160,144,400f,500f,true))
        assertEquals(PicturePassSizes(1,1,800,800,200,300),picturePassSizes(800,800,200f,300f,false))
    }
    @Test fun presetsSizeAgainstTheirOriginalFrameOrPreviousPass() {
        val sizes = picturePassSizes(240, 160, 1620f, 1080f, false)
        assertEquals(1440 to 960, picturePassSize(PictureScale.Whole, 240, 160, sizes))
        assertEquals(1620 to 1080, picturePassSize(PictureScale.View, 240, 160, sizes))
        assertEquals(720 to 480, picturePassSize(PictureScale.Source(3), 240, 160, sizes))
        assertEquals(1440 to 960, picturePassSize(PictureScale.View, 240, 160,
            picturePassSizes(240, 160, 1620f, 1080f, true)))
    }

}
