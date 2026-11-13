package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.FieldHudLook
import com.pokedaisy.app.companion.FieldHudStyle
import com.pokedaisy.app.companion.QuickAction
import com.pokedaisy.app.companion.data.FlyResult
import com.pokedaisy.app.companion.data.FlyTargets
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The QUICK MENU (the QUICK MENU hotkey, L3) over the HUD's game frame on a 1920x1080 screen: every action (FLY dimmed -
 * nobody in the sample party knows it), a highlighted one with its hint, WHITE GLASS, and FLY's destination list.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*QuickMenuScreenshotTest'
 */
class QuickMenuScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val setup = FieldHudScreenshotTest()

    @Before fun game() = setup.game()

    @After fun reset() = setup.reset()

    private class Actions(val onBike: Boolean = false, val wild: Int = 100, val up: Boolean = false) : QuickActions {
        override fun offered(action: QuickAction) = true
        override fun run(action: QuickAction) {}
        override fun flyTo(mapsec: Int, targets: FlyTargets) {}
        override fun riding() = onBike
        override fun flying() = up
        override fun wildEncounters() = wild
    }

    private fun shot(
        look: FieldHudLook = FieldHudLook.GLASS, moves: Int = 0, fly: FlyTargets? = null, ring: Boolean = false, labels: Boolean = true,
        actions: QuickActions = Actions(), repels: List<Int>? = null, shown: Set<QuickAction> = QuickAction.entries.toSet(),
        snapshot: com.pokedaisy.app.companion.data.SnapshotView = FieldHudScreenshotTest.snapshot,
    ) = paparazzi.snapshot {
        val state = QuickMenuState().apply {
            trigger(true)
            this.ring = ring
            this.labels = labels
            // The tiles the menu draws (set as it composes; the keys come before that here).
            items = QuickAction.entries
            repeat(moves) { key(MenuKey.RIGHT) }
            if (fly != null) this.fly = fly
            if (repels != null) this.repels = repels
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FieldHudScene(FieldHudStyle(look = look), FieldHudScreenshotTest.wild)
            QuickMenu(state, snapshot, actions, shown, look)
        }
    }

    /** Just opened: the first tile (PC) highlighted. */
    @Test fun open() = shot()

    /** On FLY, which nobody knows: dimmed, saying why. */
    @Test fun dimmed() = shot(moves = 1)

    /** On FLASH, which nobody knows: dimmed, saying why. */
    @Test fun flash() = shot(moves = 3)

    /** On FLASH in a dark cave, a party Pokémon knowing it (the tile only shows then). */
    @Test fun flashDark() = shot(moves = 3, actions = object : QuickActions by Actions() {
        override fun flashDark() = true
    }, snapshot = FieldHudScreenshotTest.snapshot.let { s ->
        s.copy(party = s.party.mapIndexed { i, m -> if (i == 1) m.copy(moves = m.moves + com.pokedaisy.app.companion.data.MoveView("FLASH", "Normal", 20, 0, id = 148)) else m })
    })

    /** On TAKE OFF (FREE FLIGHT), which nobody can do here (no one knows FLY): dimmed, saying why. */
    @Test fun takeOff() = shot(moves = 2)

    /** In the air: TAKE OFF says LAND, the wings' arrow down. */
    @Test fun land() = shot(moves = 2, actions = Actions(up = true))

    /** On the bike: the bag's own icon. */
    @Test fun bike() = shot(moves = 5)

    @Test fun whiteGlass() = shot(FieldHudLook.WHITE_GLASS, moves = 4)

    @Test fun window() = shot(FieldHudLook.WINDOW, moves = 5)

    /** On a bike: BIKE says GET OFF. */
    @Test fun riding() = shot(moves = 3, actions = Actions(onBike = true))

    /** WILD ENCOUNTERS switched off: its tile tagged OFF. */
    @Test fun wildOff() = shot(moves = 5, actions = Actions(wild = 0))

    /** STYLE RING (a device with a left stick), LABELS ON: the named tiles round the ring, each the same gap from the next. */
    @Test fun ring() = shot(ring = true, moves = 2)

    /** LABELS OFF: icons alone, the highlighted one named in the middle. */
    @Test fun ringIcons() = shot(ring = true, labels = false, moves = 2)

    @Test fun ringWhiteGlass() = shot(FieldHudLook.WHITE_GLASS, ring = true, moves = 6)

    @Test fun ringIconsWindow() = shot(FieldHudLook.WINDOW, ring = true, labels = false, moves = 5, actions = Actions(wild = 0))

    /** GRID, LABELS OFF: the hint line names the highlighted one. */
    @Test fun gridIcons() = shot(labels = false, moves = 3)

    /** The ring with only some actions picked in SETTINGS > QUICK MENU: the first [n], the first highlighted. */
    private fun ringOf(n: Int, labels: Boolean) = shot(ring = true, labels = labels, shown = QuickAction.entries.take(n).toSet())

    @Test fun ring4() = ringOf(4, true)
    @Test fun ring5() = ringOf(5, true)
    @Test fun ring6() = ringOf(6, true)
    @Test fun ring7() = ringOf(7, true)
    @Test fun ring8() = ringOf(8, true)
    @Test fun ring9() = ringOf(9, true)
    @Test fun ring4Icons() = ringOf(4, false)
    @Test fun ring5Icons() = ringOf(5, false)
    @Test fun ring6Icons() = ringOf(6, false)
    @Test fun ring7Icons() = ringOf(7, false)
    @Test fun ring8Icons() = ringOf(8, false)
    @Test fun ring9Icons() = ringOf(9, false)

    /** Three kinds of REPEL in the bag: which one. */
    @Test fun repelList() = shot(repels = listOf(86, 83, 84))

    /** FLY's places (Kanto's towns). */
    @Test fun flyList() = shot(fly = FlyTargets(FlyResult.OK, listOf(88, 89, 90, 91, 92, 93, 94, 95, 96, 97)))
}
