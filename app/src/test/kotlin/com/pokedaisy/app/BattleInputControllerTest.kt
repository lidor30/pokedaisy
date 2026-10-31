package com.pokedaisy.app

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BUSY
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_TARGET_SELECT
import com.pokedaisy.app.companion.data.MemoryReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BattleInputController]'s switch and RUN sequences against a simulated game:
 * a party menu whose cursor moves one slot per DOWN (wrapping through CANCEL,
 * like FireRed's / Emerald's), checked headless on both games first.
 */
class BattleInputControllerTest {
    private class Game : MemoryReader {
        val party = ByteArray(6 * 100)
        var slot = 0
        val presses = mutableListOf<String>()
        // gActionSelectionCursor / gMoveSelectionCursor, a cell (column + 2 x row) per battler.
        val actionCursor = IntArray(4)
        val moveCursor = IntArray(4)

        fun pid(i: Int, v: Long) { for (k in 0 until 4) party[i * 100 + k] = (v shr (8 * k)).toByte() }

        override fun readCoreMemory(addr: Long, size: Int): ByteArray = when (addr) {
            MENU + 9 -> byteArrayOf(slot.toByte())
            PARTY -> party.copyOf(size)
            in ACTION_CURSOR until ACTION_CURSOR + 4 -> byteArrayOf(actionCursor[(addr - ACTION_CURSOR).toInt()].toByte())
            in MOVE_CURSOR until MOVE_CURSOR + 4 -> byteArrayOf(moveCursor[(addr - MOVE_CURSOR).toInt()].toByte())
            else -> ByteArray(size)
        }

        /** A 2x2 menu's cursor after [key], as the games move it: a press toward an edge it's on does nothing. */
        fun step(cursor: IntArray, battler: Int, key: String) {
            val c = cursor[battler]
            cursor[battler] = when (key) {
                "RIGHT" -> if (c and 1 == 0) c + 1 else c
                "LEFT" -> if (c and 1 == 1) c - 1 else c
                "DOWN" -> if (c < 2) c + 2 else c
                "UP" -> if (c >= 2) c - 2 else c
                else -> c
            }
        }
    }

    private val input = GbaInput()
    private val game = Game().apply { for (i in 0 until 6) pid(i, 0x1000L + i) }
    private val c = BattleInputController(input, game, log = {}).apply {
        switchAddrs = BattleInputController.SwitchAddrs(partyMenu = MENU, party = PARTY)
    }

    /** Runs [frames] frames, the simulated game reacting to each new press. */
    private fun run(frames: Int, onPress: (String) -> Unit = {}) {
        var last = 0
        repeat(frames) {
            c.tick()
            val now = input.mask
            val new = now and last.inv()
            last = now
            for ((bit, name) in KEYS) if (new and bit != 0) { game.presses += name; onPress(name) }
        }
    }

    @Test fun switchPicksPokemonThenSteersTheCursorToTheMonAndShifts() {
        c.onState(0, BATTLE_INPUT_ACTION_SELECT)
        c.switchTo(0x1003L) // the mon in slot 3
        var aPresses = 0
        run(600) { key ->
            when (key) {
                "DOWN" -> if (aPresses >= 1) game.slot = (game.slot + 1) % 7 // the party menu's list, after POKéMON
                "A" -> {
                    aPresses++
                    if (aPresses == 1) c.onState(0, BATTLE_INPUT_PARTY_OPEN) // POKéMON opened the party menu
                    if (aPresses == 3) c.onState(0, BATTLE_INPUT_BUSY)       // SHIFT taken
                }
            }
        }
        // LEFT/UP/DOWN/A to POKéMON, then three DOWNs to slot 3, A on it, A on SHIFT.
        assertEquals(listOf("LEFT", "UP", "DOWN", "A", "DOWN", "DOWN", "DOWN", "A", "A"), game.presses)
        assertEquals(3, game.slot)
        assertFalse(c.isBusy)
    }

    @Test fun gen1ActionMenuGoesFromTheReadCursor() {
        // Yellow's FIGHT / PkMn over ITEM / RUN, cursor on ITEM (cell 2): RUN is RIGHT, then A.
        c.gen1 = true
        c.onState(0, BATTLE_INPUT_ACTION_SELECT, cursor = 2)
        c.selectAction(3)
        run(200)
        assertEquals("RUN", listOf("RIGHT", "A"), game.presses.take(2))
        // ...and PkMn (Gen 3's POKEMON, index 2) from FIGHT is just RIGHT, A.
        game.presses.clear()
        c.onState(0, BATTLE_INPUT_ACTION_SELECT, cursor = 0)
        c.selectAction(2)
        run(200)
        assertEquals(listOf("RIGHT", "A"), game.presses.filter { it != "B" })
    }

    @Test fun gen1MovesAreOneList() {
        // The move list with the cursor on move 1: the 4th move is DOWN, DOWN, A - no LEFT / UP first.
        c.gen1 = true
        c.onState(0, BATTLE_INPUT_MOVE_SELECT, cursor = 1)
        c.selectMove(3)
        run(200)
        assertEquals(listOf("DOWN", "DOWN", "A"), game.presses)
    }

    @Test fun switchFollowsTheBattleOrderWhileTheMenuIsOpen() {
        // The game moved the mon to slot 1 while the menu is open (battle order): go there.
        game.pid(1, 0x1004L)
        game.pid(4, 0x1001L)
        c.onState(0, BATTLE_INPUT_PARTY_OPEN) // e.g. after a faint
        c.switchTo(0x1004L)
        run(400) { if (it == "DOWN") game.slot = (game.slot + 1) % 7 }
        assertEquals(listOf("DOWN", "A", "A"), game.presses)
    }

    @Test fun aTwoColumnPartyMenuIsSteeredAcrossAndDown() {
        // Lazarus: 0 2 4 | 1 3 5, DOWN / UP within a column, LEFT / RIGHT across (checked headless).
        c.switchAddrs = BattleInputController.SwitchAddrs(partyMenu = MENU, party = PARTY, grid = true)
        c.onState(0, BATTLE_INPUT_PARTY_OPEN)
        c.switchTo(0x1005L)
        run(400) { key ->
            when (key) {
                "RIGHT" -> if (game.slot % 2 == 0) game.slot++
                "LEFT" -> if (game.slot % 2 == 1) game.slot--
                "DOWN" -> if (game.slot + 2 < 6) game.slot += 2
                "UP" -> if (game.slot - 2 >= 0) game.slot -= 2
            }
        }
        assertEquals(listOf("RIGHT", "DOWN", "DOWN", "A", "A"), game.presses)
        assertEquals(5, game.slot)
    }

    @Test fun theScreenIsHeldThroughASwitchAndLetGoAfter() {
        c.onState(0, BATTLE_INPUT_PARTY_OPEN)
        c.switchTo(0x1002L)
        c.tick()
        assertTrue(c.holdFrame) // the game's party menu is never shown
        var aPresses = 0
        run(400) { key ->
            if (key == "DOWN") game.slot = (game.slot + 1) % 7
            if (key == "A" && ++aPresses == 2) c.onState(0, BATTLE_INPUT_BUSY) // SHIFT taken, back in battle
        }
        assertFalse(c.holdFrame)
    }

    @Test fun userKeysAreBlockedOnlyWhileASequenceRuns() {
        input.setTouchBits(MgbaCore.Key.R) // the player holding R on the touch pad
        c.onState(0, BATTLE_INPUT_ACTION_SELECT)
        c.selectAction(0) // FIGHT
        c.tick()
        assertTrue(c.isBusy)
        assertEquals(0, input.mask and MgbaCore.Key.R)
        run(200)
        assertFalse(c.isBusy)
        assertEquals(MgbaCore.Key.R, input.mask and MgbaCore.Key.R)
    }

    @Test fun playerKeysComeBackWhileTheTurnPlaysOut() {
        // A move, then the turn stops on a message that waits for the player's A
        // (a level up, "learn a new move?") - their keys must reach the game.
        c.onState(0, BATTLE_INPUT_MOVE_SELECT)
        c.selectMove(1)
        run(200) { if (it == "A") c.onState(0, BATTLE_INPUT_BUSY) }
        assertEquals(listOf("LEFT", "UP", "RIGHT", "A"), game.presses)
        input.setTouchBits(MgbaCore.Key.A)
        c.tick()
        assertEquals(MgbaCore.Key.A, input.mask and MgbaCore.Key.A)
    }

    @Test fun aTargetScreenAfterAMoveIsStillConfirmed() {
        c.onState(0, BATTLE_INPUT_MOVE_SELECT)
        c.selectMove(0)
        run(100)
        c.onState(0, BATTLE_INPUT_TARGET_SELECT) // the move's "who does this hit" screen
        run(50)
        assertEquals(listOf("LEFT", "UP", "A", "A"), game.presses)
        assertFalse(c.isBusy)
    }

    @Test fun theTargetWatchExpires() {
        c.onState(0, BATTLE_INPUT_MOVE_SELECT)
        c.selectMove(0)
        run(100) { if (it == "A") c.onState(0, BATTLE_INPUT_BUSY) }
        assertTrue(c.isBusy) // still watching for a target screen
        run(400)
        assertFalse(c.isBusy)
        // A target screen much later (the player's own move) is left to the player.
        c.onState(0, BATTLE_INPUT_TARGET_SELECT)
        game.presses.clear()
        run(100)
        assertEquals(emptyList<String>(), game.presses)
    }

    @Test fun runClosesTheGotAwayMessageWithB() {
        // No fresh state read: B, which a menu coming back shrugs off.
        c.onState(0, BATTLE_INPUT_ACTION_SELECT)
        c.selectAction(3)
        var b = 0
        run(400) { key ->
            if (key == "A") c.onState(0, BATTLE_INPUT_BUSY)   // "Got away safely!"
            if (key == "B" && ++b == 2) c.onState(0, BATTLE_INPUT_NONE) // the battle ended
        }
        assertEquals(listOf("LEFT", "UP", "RIGHT", "DOWN", "A", "B", "B"), game.presses)
        assertFalse(c.isBusy)
    }

    @Test fun runStopsWhenTheMenuComesBack() {
        // "Can't escape!" (or a trainer battle): one B, then the action menu again.
        c.onState(0, BATTLE_INPUT_ACTION_SELECT)
        c.selectAction(3)
        run(400) { key ->
            if (key == "A") c.onState(0, BATTLE_INPUT_BUSY)
            if (key == "B") c.onState(0, BATTLE_INPUT_ACTION_SELECT)
        }
        assertEquals(1, game.presses.count { it == "B" })
        assertFalse(c.isBusy)
    }

    @Test fun runSaysYesToAreYouSureThenClosesTheMessage() {
        // Unbound before a shiny: "Are you sure you want to run away?" YES / NO (YES first), then "Got away safely!".
        var state = BATTLE_INPUT_ACTION_SELECT
        c.stateNow = { state }
        c.onState(0, state)
        c.selectAction(3)
        var a = 0
        run(400) { key ->
            if (key != "A") return@run
            state = when (++a) {
                1 -> BATTLE_INPUT_BUSY  // RUN taken: the question, then YES / NO
                2 -> BATTLE_INPUT_BUSY  // YES: "Got away safely!"
                else -> BATTLE_INPUT_NONE // closed: the battle's over
            }
            c.onState(0, state)
        }
        assertEquals(listOf("LEFT", "UP", "RIGHT", "DOWN", "A", "A", "A"), game.presses)
        assertFalse(c.isBusy)
    }

    @Test fun runNeverPressesAOnAMenuTheLastPollMissed() {
        // "Can't escape!" closes and the action menu is back before the ~4-frame poll has seen it: the fresh read has.
        var state = BATTLE_INPUT_ACTION_SELECT
        c.stateNow = { state }
        c.onState(0, state)
        c.selectAction(3)
        var a = 0
        run(400) { key ->
            if (key != "A") return@run
            if (++a == 1) { state = BATTLE_INPUT_BUSY; c.onState(0, state) }
            else state = BATTLE_INPUT_ACTION_SELECT   // the poll still says BUSY
        }
        assertEquals(2, game.presses.count { it == "A" }) // RUN, then the one that closed the message
        assertFalse(c.isBusy)
    }

    /** The real party menu's list: slots 0..5, then CANCEL (7); DOWN and UP both wrap. */
    private fun partyStep(slot: Int, key: String): Int {
        val ring = listOf(0, 1, 2, 3, 4, 5, 7)
        val i = ring.indexOf(slot)
        return when (key) {
            "DOWN" -> ring[(i + 1) % ring.size]
            "UP" -> ring[(i + ring.size - 1) % ring.size]
            else -> slot
        }
    }

    @Test fun pressesTheMenuDropsWhileFadingInArePressedAgain() {
        // The menu ignores presses for ~50 frames after it opens (FireRed / Emerald, headless).
        c.onState(0, BATTLE_INPUT_PARTY_OPEN) // the game's own party menu (after a faint)
        c.switchTo(0x1002L)
        var frame = 0
        var a = 0
        var last = 0
        repeat(400) {
            c.tick()
            frame++
            val now = input.mask
            val new = now and last.inv()
            last = now
            for ((bit, name) in KEYS) if (new and bit != 0) {
                game.presses += name
                if (frame > 50) game.slot = partyStep(game.slot, name)
                if (name == "A" && ++a == 2) c.onState(0, BATTLE_INPUT_BUSY) // SHIFT taken
            }
        }
        assertEquals(2, game.slot)
        assertEquals(2, a)
        assertTrue("dropped presses were pressed again", game.presses.count { it == "DOWN" } > 2)
        assertFalse(c.isBusy)
    }

    @Test fun theShorterWayRoundGoesUpThroughCancel() {
        // Slot 5 from slot 0 of six: UP, UP (0 -> CANCEL -> 5), not five DOWNs.
        c.onState(0, BATTLE_INPUT_PARTY_OPEN)
        c.switchTo(0x1005L)
        run(300) { key ->
            game.slot = partyStep(game.slot, key)
            if (key == "A" && game.presses.count { it == "A" } == 2) c.onState(0, BATTLE_INPUT_BUSY)
        }
        assertEquals(listOf("UP", "UP", "A", "A"), game.presses)
        assertEquals(5, game.slot)
    }

    // ---- picks that read the cursor (FireRed / Emerald: cursorAddrs) ----

    private val fast = BattleInputController(input, game, log = {}).apply {
        cursorAddrs = BattleInputController.CursorAddrs(action = ACTION_CURSOR, move = MOVE_CURSOR)
    }

    /** [run] for [fast], the simulated menu that's open moving its cursor with each press. */
    private fun runFast(frames: Int, menu: IntArray, battler: Int = 0, dropFirst: String? = null, onPress: (String) -> Unit = {}) {
        var last = 0
        var dropped = false
        repeat(frames) {
            fast.tick()
            val now = input.mask
            val new = now and last.inv()
            last = now
            for ((bit, name) in KEYS) if (new and bit != 0) {
                game.presses += name
                // A game still fading in ignores a press: the loop has to notice and press again.
                if (name == dropFirst && !dropped) { dropped = true; continue }
                game.step(menu, battler, name)
                onPress(name)
            }
        }
    }

    @Test fun theHighlightedChoiceIsOnePress() {
        game.actionCursor[0] = 0
        fast.onState(0, BATTLE_INPUT_ACTION_SELECT)
        fast.selectAction(0) // FIGHT, already highlighted
        runFast(60, game.actionCursor)
        assertEquals(listOf("A"), game.presses)
        assertFalse(fast.isBusy)
    }

    @Test fun aMoveIsReachedFromWhereTheCursorIs() {
        // Move 1 highlighted (top right), move 2 wanted (bottom left): LEFT, DOWN, A - no UP.
        game.moveCursor[0] = 1
        fast.onState(0, BATTLE_INPUT_MOVE_SELECT)
        fast.selectMove(2)
        runFast(60, game.moveCursor) { if (it == "A") fast.onState(0, BATTLE_INPUT_BUSY) }
        assertEquals(listOf("LEFT", "DOWN", "A"), game.presses)
        assertEquals(2, game.moveCursor[0])
    }

    /** [fast] with the game's RAM writable: a pick sets the cursor and presses A once. */
    private val direct = BattleInputController(input, game, log = {}, writer = { addr, b ->
        when (addr) {
            in ACTION_CURSOR until ACTION_CURSOR + 4 -> { game.actionCursor[(addr - ACTION_CURSOR).toInt()] = b[0].toInt(); true }
            in MOVE_CURSOR until MOVE_CURSOR + 4 -> { game.moveCursor[(addr - MOVE_CURSOR).toInt()] = b[0].toInt(); true }
            else -> false
        }
    }).apply { cursorAddrs = BattleInputController.CursorAddrs(action = ACTION_CURSOR, move = MOVE_CURSOR) }

    @Test fun aWritableCursorIsSetAndTheMoveTakesOneA() {
        game.moveCursor[0] = 1
        direct.onState(0, BATTLE_INPUT_MOVE_SELECT)
        direct.selectMove(2)
        var last = 0
        repeat(60) {
            direct.tick()
            val now = input.mask
            val new = now and last.inv()
            last = now
            for ((bit, name) in KEYS) if (new and bit != 0) { game.presses += name; if (name == "A") direct.onState(0, BATTLE_INPUT_BUSY) }
        }
        assertEquals(listOf("A"), game.presses)
        assertEquals(2, game.moveCursor[0])
    }

    // ---- Elite Redux: FIGHT / INFO over POKéMON / RUN valued 0 / 3 over 1 / 2, no BAG, a column of moves ----

    private val elite = BattleInputController(input, game, log = {}, writer = { addr, b ->
        when (addr) {
            in ACTION_CURSOR until ACTION_CURSOR + 4 -> { game.actionCursor[(addr - ACTION_CURSOR).toInt()] = b[0].toInt(); true }
            in MOVE_CURSOR until MOVE_CURSOR + 4 -> { game.moveCursor[(addr - MOVE_CURSOR).toInt()] = b[0].toInt(); true }
            else -> false
        }
    }).apply {
        cursorAddrs = BattleInputController.CursorAddrs(ACTION_CURSOR, MOVE_CURSOR, BattleInputController.MenuCells.ELITE_REDUX)
    }

    private fun runElite(frames: Int) {
        var last = 0
        repeat(frames) {
            elite.tick()
            val now = input.mask
            val new = now and last.inv()
            last = now
            for ((bit, name) in KEYS) if (new and bit != 0) { game.presses += name; if (name == "A") elite.onState(0, BATTLE_INPUT_BUSY) }
        }
    }

    @Test fun eliteReduxRunAndPokemonAreItsOwnCells() {
        elite.onState(0, BATTLE_INPUT_ACTION_SELECT)
        elite.selectAction(2) // POKéMON
        runElite(30)
        assertEquals(1, game.actionCursor[0])
        elite.onState(0, BATTLE_INPUT_ACTION_SELECT)
        elite.selectAction(3) // RUN
        runElite(30)
        assertEquals(2, game.actionCursor[0])
        assertEquals("A", game.presses.first())
    }

    @Test fun eliteReduxHasNoBag() {
        game.actionCursor[0] = 0
        elite.onState(0, BATTLE_INPUT_ACTION_SELECT)
        elite.selectAction(1) // BAG: not on its menu - nothing is pressed
        runElite(30)
        assertEquals(emptyList<String>(), game.presses)
        assertEquals(0, game.actionCursor[0])
    }

    @Test fun eliteReduxMovesAreAColumn() {
        // Unwritable: the cursor is walked, DOWN through the column (never RIGHT).
        val walk = BattleInputController(input, game, log = {}).apply {
            cursorAddrs = BattleInputController.CursorAddrs(ACTION_CURSOR, MOVE_CURSOR, BattleInputController.MenuCells.ELITE_REDUX)
        }
        game.moveCursor[0] = 0
        walk.onState(0, BATTLE_INPUT_MOVE_SELECT)
        walk.selectMove(2)
        var last = 0
        repeat(60) {
            walk.tick()
            val now = input.mask
            val new = now and last.inv()
            last = now
            for ((bit, name) in KEYS) if (new and bit != 0) {
                game.presses += name
                when (name) {
                    "DOWN" -> game.moveCursor[0] = minOf(3, game.moveCursor[0] + 1)
                    "UP" -> game.moveCursor[0] = maxOf(0, game.moveCursor[0] - 1)
                    "A" -> walk.onState(0, BATTLE_INPUT_BUSY)
                }
            }
        }
        assertEquals(listOf("DOWN", "DOWN", "A"), game.presses)
        assertEquals(2, game.moveCursor[0])
    }

    @Test fun onlyTheLastPressOfASequenceIsHeard() {
        // Move 1 highlighted, move 2 wanted: LEFT and DOWN muted (FF mutes the game while muteGame), the A heard.
        game.moveCursor[0] = 1
        fast.onState(0, BATTLE_INPUT_MOVE_SELECT)
        fast.selectMove(2)
        val muted = mutableMapOf<String, Boolean>()
        var last = 0
        repeat(60) {
            fast.tick()
            val now = input.mask
            val new = now and last.inv()
            last = now
            for ((bit, name) in KEYS) if (new and bit != 0) {
                muted[name] = fast.muteGame
                game.step(game.moveCursor, 0, name)
                if (name == "A") fast.onState(0, BATTLE_INPUT_BUSY)
            }
        }
        assertEquals(mapOf("LEFT" to true, "DOWN" to true, "A" to false), muted)
        assertFalse(fast.muteGame)
    }

    @Test fun aDroppedPressIsPressedAgain() {
        game.actionCursor[0] = 0
        fast.onState(0, BATTLE_INPUT_ACTION_SELECT)
        fast.selectAction(3) // RUN: RIGHT (dropped), RIGHT, DOWN, A
        runFast(60, game.actionCursor, dropFirst = "RIGHT") { if (it == "A") fast.onState(0, BATTLE_INPUT_BUSY) }
        assertEquals(listOf("RIGHT", "RIGHT", "DOWN", "A"), game.presses.take(4))
        assertEquals(3, game.actionCursor[0])
    }

    @Test fun theChoosingBattlersCursorIsTheOneRead() {
        // A double battle's second battler: its own byte, not battler 0's.
        game.actionCursor[0] = 3
        game.actionCursor[1] = 1
        fast.onState(1, BATTLE_INPUT_ACTION_SELECT)
        fast.selectAction(1) // BAG, already highlighted for battler 1
        runFast(60, game.actionCursor, battler = 1)
        assertEquals(listOf("A"), game.presses)
    }

    @Test fun anUnreadableCursorFallsBackToTheBlindWalk() {
        game.actionCursor[0] = 9 // not a cell: the wrong address
        fast.onState(0, BATTLE_INPUT_ACTION_SELECT)
        fast.selectAction(3)
        runFast(200, IntArray(4)) { if (it == "A") fast.onState(0, BATTLE_INPUT_BUSY) }
        assertEquals(listOf("LEFT", "UP", "RIGHT", "DOWN", "A"), game.presses.take(5))
    }

    @Test fun aMoveTappedOnTheActionMenuOpensFightThenPicksIt() {
        game.actionCursor[0] = 1
        game.moveCursor[0] = 0
        fast.onState(0, BATTLE_INPUT_ACTION_SELECT)
        fast.selectMove(3)
        runFast(120, game.actionCursor)
        // LEFT to FIGHT, A; the next state poll sees the move grid, read from then on.
        assertEquals(listOf("LEFT", "A"), game.presses)
        game.presses.clear()
        fast.onState(0, BATTLE_INPUT_MOVE_SELECT)
        runFast(120, game.moveCursor) { if (it == "A") fast.onState(0, BATTLE_INPUT_BUSY) }
        assertEquals(listOf("RIGHT", "DOWN", "A"), game.presses)
    }

    @Test fun runClosesTheMessageOnlyAfterItsPick() {
        game.actionCursor[0] = 0
        fast.onState(0, BATTLE_INPUT_ACTION_SELECT)
        fast.selectAction(3)
        var b = 0
        runFast(400, game.actionCursor) { key ->
            if (key == "A") fast.onState(0, BATTLE_INPUT_BUSY)
            if (key == "B" && ++b == 2) fast.onState(0, BATTLE_INPUT_NONE)
        }
        assertEquals(listOf("RIGHT", "DOWN", "A", "B", "B"), game.presses)
        assertFalse(fast.isBusy)
    }

    private companion object {
        const val MENU = 0x0203B0A0L
        const val PARTY = 0x02024284L
        const val ACTION_CURSOR = 0x02023FF8L
        const val MOVE_CURSOR = 0x02023FFCL
        val KEYS = listOf(
            MgbaCore.Key.A to "A", MgbaCore.Key.B to "B", MgbaCore.Key.LEFT to "LEFT",
            MgbaCore.Key.RIGHT to "RIGHT", MgbaCore.Key.UP to "UP", MgbaCore.Key.DOWN to "DOWN",
        )
    }
}
