package com.pokedaisy.app.companion

import com.pokedaisy.app.BattleInputController
import com.pokedaisy.app.ScriptInput
import com.pokedaisy.app.battleSwitchAddrsFor
import com.pokedaisy.app.companion.data.*

/** Per-game battle wiring. Hosts schedule [poll] and controller ticks between frames; [keys] supplies
 * the frame-based polling/mixing used by a host without its own ScriptInput channel. */
class GameBattleInput(
    private val telemetry: TelemetryStore,
    private val romCode: String?,
    private val romRev: Int,
    input: ScriptInput? = null,
    reader: MemoryReader = InProcessReader,
    writer: ((Long, ByteArray) -> Boolean)? = null,
    /** Calls a game function between frames (LAST BALL: [BattleInputController.throwBall]); null = no throws. */
    caller: ((fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) -> Long)? = null,
    var changed: () -> Unit = {},
    private val silent: () -> Boolean = { false },
    private val retryUnshownBattle: Boolean = false,
) : BattleInput {
    private var script = 0
    private var exclusive = false
    private var frames = 0
    private var lastState: Pair<Int, Int>? = null
    var stateObserved: (Int) -> Unit = {}
    val controller = BattleInputController(ScriptInput { bits, owns ->
        script = bits; exclusive = owns
        input?.setScript(bits, owns)
    }, reader, writer = writer, caller = caller).apply {
        stateNow = { telemetry.refreshBattleInputFast()?.second }
    }

    override val busy get() = controller.isBusy
    override val menuCursor get() = controller.menuCursor
    override val gameSilent get() = silent()
    override val canSwitch get() = controller.switchAddrs != null
    val gameState get() = controller.gameState
    val holdPicture get() = controller.holdFrame
    val quietPress get() = controller.muteGame

    fun poll() {
        telemetry.refreshBattleInputFast()?.let { state ->
            controller.onState(state.first, state.second, telemetry.battleMenuCursor())
            stateObserved(state.second)
            val waiting = retryUnshownBattle && state.second != BATTLE_INPUT_NONE && !telemetry.snapshot.value.showsBattle
            if (state != lastState || waiting) { lastState = state; changed() }
        }
    }

    fun keys(held: Int): Int {
        if (frames++ % 4 == 0) poll()
        controller.tick()
        return if (exclusive) script else held or script
    }

    /** Only detected addresses enable switching; disconnected samples retain the last trusted mapping. */
    fun sampled() {
        val snapshot = telemetry.snapshot.value
        controller.runAsksFirst = snapshot.battleOpponent.any { it.shiny }
        if (!snapshot.connected) return
        controller.gen1 = snapshot.game.isGen1 || snapshot.game.isGen2
        controller.cursorAddrs = telemetry.knownCursorAddrs()?.let { (action, move) ->
            BattleInputController.CursorAddrs(action, move,
                if (snapshot.game == GameKind.ELITE_REDUX) BattleInputController.MenuCells.ELITE_REDUX
                else BattleInputController.MenuCells.GEN3)
        }
        controller.switchAddrs = battleSwitchAddrsFor(telemetry, snapshot.game, romCode, romRev)
    }

    override fun selectAction(actionIndex: Int) = controller.selectAction(actionIndex)
    override fun selectMove(moveIndex: Int) = controller.selectMove(moveIndex)
    override fun back() = controller.back()
    override fun switchTo(personality: Long) = controller.switchTo(personality)
    fun cancel() = controller.cancel()
}
