package com.pokedaisy.app.companion.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BUSY
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.MoveVsFoe
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.spriteAsset
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * BATTLE BUTTONS beside a battle on one 1920x1080 screen (the Retroid Pocket 6's): EXPAND and ORIGINAL, the move and
 * party windows, dark mode, a foe's turn (dimmed), a trainer battle (no RUN), a 20:9 phone. The frame under them is a real
 * EXPAND battle capture when one is on this machine (`app/build/battle-buttons/battle.png`, 284x160, never
 * committed: game art), else a drawn stand-in.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*BattleButtons*'
 */
class BattleButtonsScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val setup = FieldHudScreenshotTest()

    @Before fun game() = setup.game()

    @After fun reset() = setup.reset()

    @Test fun expand() = paparazzi.snapshot { BattleScene() }

    @Test fun original() = paparazzi.snapshot { BattleScene(expand = false) }

    @Test fun moveWindow() = paparazzi.snapshot { BattleScene(window = BattleButtonsWindow.MOVES) }

    @Test fun partyWindow() = paparazzi.snapshot { BattleScene(window = BattleButtonsWindow.PARTY) }

    @Test fun dark() {
        com.pokedaisy.app.companion.ui.theme.DarkMode.override = true
        paparazzi.snapshot { BattleScene(window = BattleButtonsWindow.MOVES) }
    }

    @Test fun foesTurn() = paparazzi.snapshot { BattleScene(snap = battle.copy(battleInputState = BATTLE_INPUT_BUSY)) }

    /** A trainer battle: no RUN. */
    @Test fun trainer() = paparazzi.snapshot { BattleScene(snap = battle.copy(enemyParty = party.take(3))) }

    companion object {
        // Personalities, so the party window can pick them.
        val party = FieldHudScreenshotTest.party.mapIndexed { i, mon -> mon.copy(personality = 1000L + i) }
        private fun vs(label: String, pct: Int) = listOf(MoveVsFoe("PIDGEY", label, pct))
        val lead: MonView = party[0].copy(
            moves = listOf(
                MoveView("THUNDERBOLT", "Electric", 15, 95, vs("2x", 200)),
                MoveView("FLAMETHROWER", "Fire", 0, 95, vs("1x", 100)),
                MoveView("GROWL", "Normal", 40, 0),
                MoveView("METAL CLAW", "Steel", 35, 50, vs("½x", 50)),
            ),
        )
        val battle: SnapshotView = FieldHudScreenshotTest.snapshot.copy(
            inBattle = true,
            // PIKACHU against a bird: SUGGESTIONS' pick (BEST).
            party = listOf(lead, party[1], party[2].copy(types = listOf("Electric"), moves = listOf(MoveView("ThunderShock", "Electric", 30, 40, vs("2x", 200))))) + party.drop(3),
            battlePlayer = listOf(lead),
            battleOpponent = listOf(MonView(16, "PIDGEY", 9, 20, 26, "", listOf("Normal", "Flying"), spriteAsset("pokemon", 16))),
            battleInputState = BATTLE_INPUT_ACTION_SELECT,
        )

        val input = object : BattleInput {
            override val busy = false
            override fun selectAction(actionIndex: Int) {}
            override fun selectMove(moveIndex: Int) {}
            override fun back() {}
            override val canSwitch = true
        }
    }
}

/** [BattleButtonsScreenshotTest]'s scenes cropped at 1:1 to the screen's right 600 px (Paparazzi scales 1920 down). */
class BattleButtonsCloseUpScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 600, screenHeight = 1080, orientation = ScreenOrientation.PORTRAIT),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val setup = FieldHudScreenshotTest()

    @Before fun game() = setup.game()

    @After fun reset() = setup.reset()

    @Test fun expand() = paparazzi.snapshot { Right { BattleScene() } }

    @Test fun moveWindow() = paparazzi.snapshot { Right { BattleScene(window = BattleButtonsWindow.MOVES) } }

    @Test fun partyWindow() = paparazzi.snapshot { Right { BattleScene(window = BattleButtonsWindow.PARTY) } }

    @Composable
    private fun Right(content: @Composable () -> Unit) {
        val px = LocalDensity.current
        Box(Modifier.fillMaxSize().clipToBounds()) {
            Box(
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset(x = with(px) { -1320.toDp() })
                    .requiredSize(with(px) { 1920.toDp() }, with(px) { 1080.toDp() }),
            ) { content() }
        }
    }
}

/** A 20:9 phone (2400x1080): wide margins beside the battle. */
class BattleButtonsPhoneScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 2400, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val setup = FieldHudScreenshotTest()

    @Before fun game() = setup.game()

    @After fun reset() = setup.reset()

    @Test fun original() = paparazzi.snapshot { BattleScene(expand = false, width = 2400) }

    /** Between the touch controls' rows: the words stay whole. */
    @Test fun squeezed() = paparazzi.snapshot { BattleScene(expand = false, width = 2400, clearTop = 131, clearBottom = 549) }
}

@Composable
internal fun BattleScene(
    expand: Boolean = true,
    window: BattleButtonsWindow? = null,
    snap: SnapshotView = BattleButtonsScreenshotTest.battle,
    width: Int = 1920,
    clearTop: Int = 0,
    clearBottom: Int = 0,
) {
    val left = (width - 1620) / 2f
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BattleFrame(expand, left)
        BattleButtons(
            snap, shown = true, BattleButtonsScreenshotTest.input, showHints = true,
            core = if (expand) null else Rect(left, 0f, left + 1620f, 1080f),
            initialWindow = window, clearTop = clearTop, clearBottom = clearBottom,
        )
    }
}

/** The captured battle (284x160: EXPAND's 22 px margins), whole at EXPAND, its middle 240x160 at ORIGINAL. */
@Composable
private fun BattleFrame(expand: Boolean, left: Float) {
    val file = java.io.File("build/battle-buttons/battle.png")
    val image = remember(file) { file.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    val px = LocalDensity.current
    if (image == null) {
        FakeGame(Modifier.padding(start = with(px) { left.toDp() }).width(with(px) { 1620.toDp() }).fillMaxHeight())
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        if (expand) {
            // EXPAND: the wide frame at the screen's height, its middle centred.
            val k = size.height / 160f
            val w = (284 * k).toInt()
            drawImage(
                image, dstOffset = IntOffset(((size.width - w) / 2).toInt(), 0), dstSize = IntSize(w, size.height.toInt()),
                filterQuality = FilterQuality.None,
            )
        } else {
            drawImage(
                image, srcOffset = IntOffset(22, 0), srcSize = IntSize(240, 160),
                dstOffset = IntOffset(left.toInt(), 0), dstSize = IntSize(1620, 1080), filterQuality = FilterQuality.None,
            )
        }
    }
}
