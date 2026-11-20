package com.pokedaisy.app

import kotlin.test.*

/** Native and JVM use the same camera. Android's existing WideCameraTest keeps the detailed animation coverage. */
class WideCameraPortabilityTest {
    private fun settle(camera: WideCamera, scene: Int = WideCamera.SCENE_FIELD, start: Long = 1): WideCamera.Window {
        var window: WideCamera.Window? = null
        repeat(120) { window = camera.frame(scene, start + it * 16_666_667L) }
        return assertNotNull(window)
    }

    @Test fun gbaAndGameBoyWindowsRemainBoundedAcrossPhoneAndTabletOrientations() {
        for ((fw, fh) in listOf(240 to 160, 160 to 144)) {
            for ((w, h) in listOf(2622 to 1146, 1146 to 1600, 2316 to 1472, 1472 to 1900)) {
                val c = WideCamera().apply { frameW=fw;frameH=fh;viewW=w;viewH=h;level=100 }
                val out=settle(c)
                assertTrue(out.w in fw..WideCamera.MAX_W);assertTrue(out.h in fh..WideCamera.MAX_H)
                assertTrue(out.scale.isFinite() && out.scale>0)
                assertTrue(out.x<=fw/2 && out.x+out.w>=fw/2)
                assertTrue(out.y<=fh/2 && out.y+out.h>=fh/2)
            }
        }
    }

    @Test fun battleAndMenuCloseupsReturnToTheChosenZoomAfterTheFieldReturns() {
        val c=WideCamera().apply { viewW=1920;viewH=1080;level=3 }
        assertEquals(3f,settle(c).scale,0.001f)
        assertEquals(6.75f,settle(c,WideCamera.SCENE_BATTLE_START,3_000_000_000).scale,0.001f)
        assertEquals(6.75f,settle(c,WideCamera.SCENE_MENU,6_000_000_000).scale,0.001f)
        assertEquals(3f,settle(c,start=9_000_000_000).scale,0.001f)
    }

    @Test fun resizingAndResettingRetireThePreviousCameraOffset() {
        val c=WideCamera().apply { viewW=1920;viewH=1080;stickX=1f }
        settle(c);c.stickX=0f
        c.viewW=1080;c.viewH=1400;c.reset()
        val portrait=settle(c,start=3_000_000_000)
        assertEquals(0,portrait.x);assertTrue(portrait.y<0)
        assertEquals(4.5f,portrait.scale,0.001f)
    }

    @Test fun losingTheViewDoesNotPublishAnOldWindowAndRecoveryKeepsFiniteGeometry() {
        val c=WideCamera().apply { viewW=1920;viewH=1080 }
        settle(c);c.viewW=0
        assertNull(c.frame(WideCamera.SCENE_FIELD,3_000_000_000));assertNull(c.current)
        c.viewW=1920
        assertTrue(settle(c,start=4_000_000_000).scale.isFinite())
    }

    @Test fun theDefaultClockWorksWithoutJvmSystemApis() {
        val c=WideCamera().apply { viewW=1920;viewH=1080 }
        repeat(3) { assertTrue(assertNotNull(c.frame(WideCamera.SCENE_FIELD)).scale>0) }
    }
}
