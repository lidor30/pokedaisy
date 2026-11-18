@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package render

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.ui.CompanionScreen
import java.lang.management.ManagementFactory

/**
 * `gradle stress`: switches the real companion's tabs as fast as a player mashing the tab bar
 * (a tap every 2 frames, so the 220 ms slides pile up) and fails when that costs more memory or
 * time than [Budget] allows. v1.1.0 built a 1 MB font per text on screen, which made each switch
 * allocate tens of MB and crashed the Thor when tabs were switched quickly; nothing caught it,
 * since every other check renders one screen once. A budget is a ceiling well above today's
 * numbers, not a target: a regression of that kind lands far past it.
 *
 * Desktop allocation isn't Android's (Skia, not the platform's text stack), so this catches
 * work that grows with what's on screen - per-text / per-row / per-switch loading and retained
 * leaks - not the exact cost on the device.
 */
private object Budget {
    /** Bytes allocated (every thread) per tab switch, on average. */
    const val ALLOC_PER_SWITCH = 12L shl 20
    /** Heap still in use after the measured rounds, over the warmed-up baseline: a leak. */
    const val RETAINED_GROWTH = 16L shl 20
    /** Wall time per switch (composition + the frames between taps), on average. */
    const val MS_PER_SWITCH = 400.0
    /** UI-thread time for a battle sample that changed nothing on screen. */
    const val MS_PER_BATTLE_SAMPLE = 50.0
}

/** Bar layouts to cycle: the default, then the tabs off it by default. */
private val BARS = System.getProperty("bars")?.takeIf { it.isNotBlank() }?.split(";")?.map { it.split(",") } ?: listOf(
    com.pokedaisy.app.companion.DEFAULT_COMPANION_TABS,
    listOf("PARTY", "CARD", "STATES", "ITEMS", "MAP"),
)

private const val ROUNDS = 8
/** Frames between taps: 2 = a tap every ~33 ms, faster than any slide finishes. */
private val framesPerTap = System.getProperty("framesPerTap")?.toIntOrNull() ?: 2
private val trace = System.getProperty("trace") == "1"
private var desktopDispatchNpes = 0

fun main() {
    // The app sets this at start (PokeDaisyApp): the screens find the art under it.
    installPreviewHosts()
    romArt()
    val game = GameKind.valueOf(System.getProperty("game") ?: "FIRERED")
    activeGame = game
    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    threads.isThreadAllocatedMemoryEnabled = true
    fun allocated(): Long = threads.getThreadAllocatedBytes(threads.allThreadIds).filter { it > 0 }.sum()
    fun usedAfterGc(): Long {
        val rt = Runtime.getRuntime()
        repeat(4) { System.gc(); Thread.sleep(100) }
        return rt.totalMemory() - rt.freeMemory()
    }

    val failures = mutableListOf<String>()
    for (bar in BARS) {
        val name = bar.joinToString("/")
        val sv = snapshot()
        val content: @Composable () -> Unit = {
            CompanionScreen(sv, FakeSlots(), FakeSettings(initialTabs = bar), FakeBattleInput(false), initialTab = bar.first())
        }
        runSkikoComposeUiTest(Size(1240f, 1080f), Density(2.625f)) {
            // Manual clock, as in Main.kt: some tabs animate forever.
            mainClock.autoAdvance = false
            setContent(content)
            mainClock.advanceTimeBy(500)
            // The chips this game actually shows (a tab needs its data, e.g. DEX its tables).
            // MORE (the tabs off the bar) when there are any, then the gear that ends the bar.
            val targets = (bar + "MORE").filter { onAllNodesWithTag("tab-$it").fetchSemanticsNodes().isNotEmpty() } + "SETTINGS"
            println("stress [$name]: switching ${targets.joinToString(", ")}")
            if (targets.size < 3) failures += "[$name] only ${targets.size} tabs on the bar - nothing to switch between"
            fun SkikoComposeUiTest.round() {
                for (tab in targets) {
                    if (tab == "SETTINGS") onNodeWithContentDescription("Settings").performClick()
                    else onNodeWithTag("tab-$tab").performClick()
                    if (trace) println("stress: tap $tab")
                    try {
                        repeat(framesPerTap) { mainClock.advanceTimeByFrame() }
                    } catch (e: NullPointerException) {
                        // Desktop Compose 1.5's OnPositionedDispatcher walks its node list in
                        // place, so a re-entrant layout empties it mid-walk. The app's Compose
                        // (1.6.8) copies the list first; ui-preview can't move to 1.6 here, it
                        // needs Google Maven. Anything else still fails the run.
                        if (e.stackTrace.none { it.className.endsWith("OnPositionedDispatcher") }) throw e
                        desktopDispatchNpes++
                    }
                }
            }
            fun SkikoComposeUiTest.settle() {
                repeat(60) { mainClock.advanceTimeByFrame() }
                Thread.sleep(200) // bitmaps load on Dispatchers.IO
                mainClock.advanceTimeBy(500)
            }
            // Warm up: first loads (art, tables, class loading) aren't what's measured.
            repeat(2) { round() }
            settle()
            val baseline = usedAfterGc()
            val alloc0 = allocated()
            val t0 = System.nanoTime()
            repeat(ROUNDS) { round() }
            val ms = (System.nanoTime() - t0) / 1e6
            val alloc = allocated() - alloc0
            settle()
            val retained = usedAfterGc() - baseline
            val switches = ROUNDS * targets.size
            val perSwitch = alloc / switches
            val msPerSwitch = ms / switches
            println(
                "stress [$name]: %d switches, %.1f MB allocated/switch, %.1f ms/switch, %+.1f MB retained"
                    .format(switches, perSwitch / 1048576.0, msPerSwitch, retained / 1048576.0),
            )
            if (perSwitch > Budget.ALLOC_PER_SWITCH) failures += "[$name] allocates %.1f MB per tab switch (budget %d MB)"
                .format(perSwitch / 1048576.0, Budget.ALLOC_PER_SWITCH shr 20)
            if (retained > Budget.RETAINED_GROWTH) failures += "[$name] kept %.1f MB after switching (budget %d MB): a leak?"
                .format(retained / 1048576.0, Budget.RETAINED_GROWTH shr 20)
            if (msPerSwitch > Budget.MS_PER_SWITCH) failures += "[$name] %.0f ms per tab switch (budget %.0f)"
                .format(msPerSwitch, Budget.MS_PER_SWITCH)
        }
    }
    failures += battleSamples(light = false)
    failures += battleSamples(light = true)
    if (desktopDispatchNpes > 0) println("stress: skipped $desktopDispatchNpes desktop-only OnPositionedDispatcher NPEs (Compose 1.5)")
    failures.forEach { println("stress FAILED: $it") }
    System.exit(if (failures.isEmpty()) 0 else 1)
}

/**
 * The BATTLE tab as a battle feeds it: a new snapshot every sample (4 a second in a battle, each
 * with a new frame counter), and the game's menu flipping between its actions and its moves.
 * Times what each costs the UI thread (recomposition, layout and drawing) - the thread that also
 * takes the device's buttons, so on a slow device a costly sample leaves the game's presses
 * waiting (the RG DS: FIGHT, then no buttons until the moves showed on the companion).
 */
private fun battleSamples(light: Boolean): List<String> {
    val failures = mutableListOf<String>()
    // LIGHT COMPANION: no animations, a cut between the battle's panes.
    com.pokedaisy.app.companion.LightCompanion.mode =
        if (light) com.pokedaisy.app.companion.LightCompanionMode.ON else com.pokedaisy.app.companion.LightCompanionMode.OFF
    val tag = if (light) "BATTLE, LIGHT" else "BATTLE"
    val action = trainerBattle(com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT).withBench()
    val holder = androidx.compose.runtime.mutableStateOf(action)
    runSkikoComposeUiTest(Size(1240f, 1080f), Density(2.625f)) {
        mainClock.autoAdvance = false
        setContent {
            CompanionScreen(holder.value, FakeSlots(), FakeSettings(), FakeBattleInput(false), initialTab = "PARTY")
        }
        fun frames(n: Int): Double {
            val t0 = System.nanoTime()
            repeat(n) { mainClock.advanceTimeByFrame() }
            return (System.nanoTime() - t0) / 1e6
        }
        var frame = 0L
        fun sample(state: Int) {
            frame += 15
            holder.value = (if (state == action.battleInputState) action else action.copy(battleInputState = state)).copy(frameCounter = frame)
        }
        frames(120)
        // Warm up both menus once (class loading, first text layouts).
        repeat(3) {
            sample(com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT); frames(30)
            sample(com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT); frames(30)
        }
        // A sample with nothing to show changed: the one frame it costs.
        val idle = (1..40).map { sample(com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT); frames(1) }
        // The menu flips: the frames until the pane has swapped (its crossfade).
        val flips = (1..10).flatMap {
            listOf(com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT, com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT).map { st ->
                sample(st); frames(15)
            }
        }
        val quiet = (1..20).map { frames(1) }
        println(
            "stress [$tag]: %.2f ms a frame with nothing new, %.2f ms a sample (nothing changed), %.1f ms a menu flip (15 frames)"
                .format(quiet.average(), idle.average(), flips.average()),
        )
        if (idle.average() > Budget.MS_PER_BATTLE_SAMPLE) failures += "[$tag] %.1f ms per unchanged sample (budget %.0f)"
            .format(idle.average(), Budget.MS_PER_BATTLE_SAMPLE)
    }
    return failures
}
