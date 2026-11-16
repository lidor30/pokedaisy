package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.remember
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.data.save.BagPocket
import com.pokedaisy.app.companion.data.save.BoxSlot
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.SyntheticSave
import com.pokedaisy.app.companion.data.save.TradeSide
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * PC BOXES (the library's game menu) and TRADE on the Thor's 1920x1080 top screen, from the save tests'
 * made-up saves: as opened, a Pokémon picked, one waiting for where its MOVE goes, the BAG page, a move's pick-list
 * and a trade picked.
 * Names only (no ROM here for the icons). ui-preview renders the same states, with stand-in icons
 * (`-Ponly=pc-boxes,trade`). See CompanionScreenshotTest.
 */
class PcBoxesScreenshotTest {
    @Before fun hosts() = installTestHosts(paparazzi.context)
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private fun state() = PcBoxesState(Gen3SaveEditor(SyntheticSave.demoFireRed()))

    @Test
    fun opened() = paparazzi.snapshot {
        PcBoxesScreen(remember { state() }, "Pokémon FireRed", rememberGbaTextMetrics(), onBack = {}, onSave = {})
    }

    @Test
    fun selected() = paparazzi.snapshot {
        val s = remember { state().apply { selected = MonRef.Pc(BoxSlot(0, 10)) } }
        PcBoxesScreen(s, "Pokémon FireRed", rememberGbaTextMetrics(), onBack = {}, onSave = {})
    }

    @Test
    fun moving() = paparazzi.snapshot {
        val s = remember { state().apply { selected = MonRef.Pc(BoxSlot(0, 10)); moving = BoxSlot(0, 10) } }
        PcBoxesScreen(s, "Pokémon FireRed", rememberGbaTextMetrics(), onBack = {}, onSave = {})
    }

    @Test
    fun bag() = paparazzi.snapshot {
        val s = remember { state().apply { page = PcBoxesState.Page.BAG; pocket = BagPocket.TMHM } }
        PcBoxesScreen(s, "Pokémon FireRed", rememberGbaTextMetrics(), onBack = {}, onSave = {})
    }

    @Test
    fun movePicker() = paparazzi.snapshot {
        val s = remember { state().apply { selected = MonRef.Party(0); editing = PcBoxesState.Editing.Move(MonRef.Party(0), 1) } }
        PcBoxesScreen(s, "Pokémon FireRed", rememberGbaTextMetrics(), onBack = {}, onSave = {})
    }

    @Test
    fun tradePicked() = paparazzi.snapshot {
        val s = remember {
            val (fr, e) = SyntheticSave.demoTrade()
            TradeState(TradeSide("Pokémon FireRed", Gen3SaveEditor(fr), "BPRE"), TradeSide("Pokémon Emerald", Gen3SaveEditor(e), "BPEE"))
                .apply { selA = BoxSlot(0, 21); selB = BoxSlot(0, 3) }
        }
        TradeScreen(s, rememberGbaTextMetrics(), onBack = {}, onSave = {})
    }
}
