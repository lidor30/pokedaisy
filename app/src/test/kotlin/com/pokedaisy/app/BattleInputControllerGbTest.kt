package com.pokedaisy.app

import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.CoreKey
import com.pokedaisy.app.companion.data.GEN1_YELLOW
import com.pokedaisy.app.companion.data.GEN2_CRYSTAL
import com.pokedaisy.app.companion.data.Gen1PartySwitch
import com.pokedaisy.app.companion.data.Gen2PartySwitch
import com.pokedaisy.app.companion.data.MemoryReader
import com.pokedaisy.app.companion.data.gbMonKey
import com.pokedaisy.app.companion.data.readGen1BattleInput
import com.pokedaisy.app.companion.data.readGen2BattleInput
import com.pokedaisy.app.companion.data.wramx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BattleInputController]'s switch on a Game Boy game ([BattleInputController.SwitchAddrs.gb]) against simulated
 * Yellow and Crystal battles: their RAM laid out at the real addresses (GEN1_YELLOW / GEN2_CRYSTAL) and read through
 * the real readers, the menus behaving as they did headless (see the yellow_battle_party / crystal_battle_party
 * fixtures' READMEs) - the party listed in its own order, a ▶ drawn only while a menu waits, presses dropped for a
 * few frames after one is drawn (Yellow's Delay3; Crystal's SWITCH menu for ~30), the SWITCH / STATS / CANCEL menu
 * after a pick except after a faint, Crystal's CANCEL row. The battle menus too: the action menu and Crystal's move list,
 * a cursor step or a menu just drawn taking no press for a few frames (Polished Crystal, headless).
 */
class BattleInputControllerGbTest {
    private enum class Mode { ACTION, OPENING, LIST, SUB, MOVES, BUSY }

    /** A Yellow ([gen] 1) or Crystal ([gen] 2) battle: the RAM the readers see, and the menus reacting to presses. */
    private class Game(val gen: Int, val count: Int) : MemoryReader {
        val ram = ByteArray(0x20000)
        val presses = mutableListOf<String>()
        var mode = Mode.ACTION
        var actionCell = 0           // FIGHT 0, PkMn 1, ITEM 2, RUN 3
        var cursor = 0               // the list's slot (Crystal: count = CANCEL), or the SWITCH menu's item
        var out = 0
        var forced = false
        var refuseSwitch = false     // the game says no after SWITCH (Crystal: "can't be recalled")
        private var frame = 0
        private var ignoreUntil = 0  // presses dropped until this frame
        private var openAt = 0

        override fun readCoreMemory(addr: Long, size: Int): ByteArray = ram.copyOfRange(addr.toInt(), addr.toInt() + size)

        private fun put(addr: Long, vararg b: Int) { for ((i, v) in b.withIndex()) ram[addr.toInt() + i] = v.toByte() }

        fun mon(slot: Int, species: Int, hp: Int, otId: Int = 0x1234, dvs: Int = 0x5000 + slot) {
            if (gen == 1) {
                val o = GEN1_YELLOW.partyMons + slot * 44
                put(o, species, hp shr 8, hp and 0xFF)
                put(o + 12, otId shr 8, otId and 0xFF)
                put(o + 27, dvs shr 8, dvs and 0xFF)
                put(o + 33, 50)
            } else {
                val o = GEN2_CRYSTAL.partyMons + slot * 48
                put(o, species)
                put(o + 6, otId shr 8, otId and 0xFF)
                put(o + 21, dvs shr 8, dvs and 0xFF)
                put(o + 31, 50)
                put(o + 34, hp shr 8, hp and 0xFF)
            }
        }

        fun key(slot: Int): Long = if (gen == 1) Gen1PartySwitch(GEN1_YELLOW).party(this)[slot].key
            else Gen2PartySwitch(GEN2_CRYSTAL).party(this)[slot].key

        init {
            for (i in 0 until count) mon(i, species = 0x20 + i, hp = 40)
            if (gen == 1) { put(GEN1_YELLOW.partyCount, count); put(GEN1_YELLOW.isInBattle, 1) }
            else { put(GEN2_CRYSTAL.partyCount, count); put(GEN2_CRYSTAL.battleMode, 1) }
            draw()
        }

        /** Opens the party menu at once (the player's, or the one after a faint). */
        fun openList(afterFaint: Boolean) {
            forced = afterFaint
            mode = Mode.LIST
            cursor = 0
            draw()
        }

        /** The menu variables and the screen's ▶ for the current mode (Gen 1's HandleMenuInput / Gen 2's _2DMenu). */
        fun draw() {
            val tiles = if (gen == 1) GEN1_YELLOW.tileMap else GEN2_CRYSTAL.tileMap
            for (i in 0 until 360) ram[tiles.toInt() + i] = 0x7F
            fun arrow(x: Int, y: Int) = put(tiles + y * 20 + x, 0xED)
            if (gen == 1) {
                val m = GEN1_YELLOW.topMenuItemY
                put(GEN1_YELLOW.partyMenuType, if (forced) 2 else 0)
                put(GEN1_YELLOW.playerMonNumber, out)
                when (mode) {
                    Mode.ACTION -> { put(m, 14, if (actionCell and 1 == 1) 15 else 9, actionCell shr 1, 0, 1); arrow(if (actionCell and 1 == 1) 15 else 9, 14 + 2 * (actionCell shr 1)) }
                    Mode.LIST -> { put(m, 1, 0, cursor, 0, count - 1); arrow(0, 1 + 2 * cursor) }
                    Mode.SUB -> { put(m, 12, 12, cursor, 0, 2); arrow(12, 12 + 2 * cursor) }
                    Mode.OPENING, Mode.MOVES, Mode.BUSY -> Unit
                }
            } else {
                val m = GEN2_CRYSTAL.menu2D
                put(GEN2_CRYSTAL.partyMenuAction, if (forced) 2 else 0)
                put(GEN2_CRYSTAL.curBattleMon, out)
                when (mode) {
                    Mode.ACTION -> { put(m, 14, 9, 2, 2, 0, 0, 0x26); put(GEN2_CRYSTAL.menuCursor, 1 + (actionCell shr 1), 1 + (actionCell and 1)); arrow(9 + 6 * (actionCell and 1), 14 + 2 * (actionCell shr 1)) }
                    Mode.LIST -> { put(m, 1, 0, count + 1, 1, 0, 0, 0x20); put(GEN2_CRYSTAL.menuCursor, cursor + 1, 1); arrow(0, 1 + 2 * cursor) }
                    Mode.SUB -> { put(m, 12, 12, 3, 1, 0, 0, 0x20); put(GEN2_CRYSTAL.menuCursor, cursor + 1, 1); arrow(12, 12 + 2 * cursor) }
                    // MoveSelectionScreen: a column from (5, 13), a row per move.
                    Mode.MOVES -> { put(m, 13, 5, moves, 1, 0, 0, 0x10); put(GEN2_CRYSTAL.menuCursor, cursor + 1, 1); arrow(5, 13 + cursor) }
                    Mode.OPENING, Mode.BUSY -> Unit
                }
            }
        }

        private fun show(next: Mode, dropFrames: Int) {
            mode = next
            draw()
            ignoreUntil = frame + dropFrames
        }

        /** Draws the action menu as a new turn does: its first [dropFrames] frames take no press (Polished Crystal, headless). */
        fun showActionMenu(dropFrames: Int) = show(Mode.ACTION, dropFrames)

        var moves = 4
        var moveListDrop = 3         // frames the move list takes no press after it shows
        var usedMove = -1            // the move A was pressed on
        private var opensTo = Mode.LIST

        /** One frame: [new] = the keys newly pressed this frame. */
        fun step(new: List<String>) {
            frame++
            if (mode == Mode.OPENING && frame >= openAt) { cursor = 0; show(opensTo, if (opensTo == Mode.MOVES) moveListDrop else 3) }
            for (key in new) {
                presses += key
                if (frame <= ignoreUntil) continue                 // dropped
                val rows = if (gen == 2) count + 1 else count       // Crystal's CANCEL row
                when (mode) {
                    // A cursor step takes no press for 3 frames (Polished Crystal, headless).
                    Mode.ACTION -> when (key) {
                        "RIGHT" -> { actionCell = actionCell or 1; show(Mode.ACTION, 3) }
                        "LEFT" -> { actionCell = actionCell and 2; show(Mode.ACTION, 3) }
                        "DOWN" -> { actionCell = actionCell or 2; show(Mode.ACTION, 3) }
                        "UP" -> { actionCell = actionCell and 1; show(Mode.ACTION, 3) }
                        "A" -> when (actionCell) {
                            1 -> { mode = Mode.OPENING; opensTo = Mode.LIST; openAt = frame + 28; draw() }
                            0 -> { mode = Mode.OPENING; opensTo = Mode.MOVES; openAt = frame + 4; draw() }
                        }
                    }
                    Mode.MOVES -> when (key) {
                        "DOWN" -> { cursor = (cursor + 1) % moves; show(Mode.MOVES, 3) }
                        "UP" -> { cursor = (cursor + moves - 1) % moves; show(Mode.MOVES, 3) }
                        "A" -> { usedMove = cursor; show(Mode.BUSY, 0) }
                    }
                    Mode.LIST -> when (key) {
                        "DOWN" -> { cursor = (cursor + 1) % rows; show(Mode.LIST, 3) }
                        "UP" -> { cursor = (cursor + rows - 1) % rows; show(Mode.LIST, 3) }
                        "A" -> when {
                            cursor >= count -> Unit                     // CANCEL
                            forced -> { out = cursor; show(Mode.BUSY, 0) }
                            else -> { picked = cursor; cursor = 0; show(Mode.SUB, if (gen == 2) 30 else 3) }
                        }
                        "B" -> if (!forced) { mode = Mode.ACTION; draw() }
                    }
                    Mode.SUB -> when (key) {
                        "DOWN" -> { cursor = (cursor + 1) % 3; show(Mode.SUB, 3) }
                        "UP" -> { cursor = (cursor + 2) % 3; show(Mode.SUB, 3) }
                        "A" -> if (cursor == 0) {
                            if (refuseSwitch || picked == out) { cursor = picked; show(Mode.LIST, 3) } else { out = picked; show(Mode.BUSY, 0) }
                        }
                        "B" -> { cursor = picked; show(Mode.LIST, 3) }
                    }
                    Mode.OPENING, Mode.BUSY -> Unit
                }
            }
        }

        private var picked = 0   // the slot whose SWITCH menu is open
    }

    private val input = GbaInput()

    private fun controller(game: Game) = BattleInputController(input, game, log = {}).apply {
        gen1 = true
        switchAddrs = BattleInputController.SwitchAddrs(
            gb = if (game.gen == 1) Gen1PartySwitch(GEN1_YELLOW) else Gen2PartySwitch(GEN2_CRYSTAL),
        )
    }

    /** Runs [frames] frames: the controller's keys into the game, and the game's state polled every 4 frames, as the app does. */
    private fun run(c: BattleInputController, game: Game, frames: Int) {
        var last = 0
        repeat(frames) { f ->
            if (f % 4 == 0) {
                val (state, cursor) = if (game.gen == 1) readGen1BattleInput(game, GEN1_YELLOW) else readGen2BattleInput(game, GEN2_CRYSTAL)
                c.onState(0, state, cursor)
            }
            c.tick()
            val now = input.mask
            val new = now and last.inv()
            last = now
            game.step(KEYS.filter { (bit, _) -> new and bit != 0 }.map { it.second })
        }
    }

    @Test fun yellowSwitchesFromTheBattleMenu() {
        val game = Game(gen = 1, count = 6)
        val c = controller(game)
        run(c, game, 8)
        c.switchTo(game.key(2))
        run(c, game, 1)
        assertTrue(c.holdFrame)                       // the game's party screen never shows
        run(c, game, 400)
        assertEquals(2, game.out)
        // PkMn from FIGHT (RIGHT, A), the list's cursor down to slot 2, A on it, A on SWITCH - a DOWN the menu dropped
        // while it drew its cursor pressed again.
        assertEquals(listOf("RIGHT", "A"), game.presses.take(2))
        assertEquals(listOf("A", "A"), game.presses.takeLast(2))
        assertEquals(listOf("DOWN", "DOWN", "DOWN"), game.presses.drop(2).dropLast(2))
        assertFalse(c.isBusy)
        assertFalse(c.holdFrame)
    }

    @Test fun yellowAfterAFaintSendsTheMonOutWithNoSwitchMenu() {
        val game = Game(gen = 1, count = 6)
        game.mon(0, species = 0x20, hp = 0)          // fainted, still slot "out"
        game.openList(afterFaint = true)
        val c = controller(game)
        run(c, game, 8)
        c.switchTo(game.key(5))
        run(c, game, 400)
        assertEquals(5, game.out)
        // The shorter way round six slots: UP from 0 wraps to 5. One A, no SWITCH menu.
        assertEquals(listOf("UP", "A"), game.presses.distinct())
        assertEquals(1, game.presses.count { it == "A" })
        assertFalse(c.isBusy)
    }

    @Test fun aMonThatCantGoInIsRefusedBeforeAnyPress() {
        val game = Game(gen = 1, count = 3)
        game.mon(1, species = 0x21, hp = 0)
        val c = controller(game)
        run(c, game, 8)
        c.switchTo(game.key(0))                       // already out
        c.switchTo(game.key(1))                       // fainted
        c.switchTo(gbMonKey(0x99, 0x1234, 0x0001))    // not in the party (it changed under the pick)
        assertFalse(c.isBusy)
        run(c, game, 200)
        assertEquals(emptyList<String>(), game.presses)
        assertEquals(0, game.out)
    }

    @Test fun crystalGoesRoundThroughCancelAndPressesSwitchUntilItTakes() {
        val game = Game(gen = 2, count = 4)
        game.openList(afterFaint = false)              // the player opened PKMN
        val c = controller(game)
        run(c, game, 8)
        c.switchTo(game.key(3))
        run(c, game, 600)
        assertEquals(3, game.out)
        // Slot 3 of four is two UPs away (through CANCEL), three DOWNs: UP. Then A on it, and A on SWITCH until the
        // menu stops dropping presses (~30 frames) - more than one A, none after it took.
        val ups = game.presses.takeWhile { it == "UP" }
        assertTrue(ups.toString(), ups.size >= 2)
        assertTrue(game.presses.drop(ups.size).all { it == "A" })
        assertTrue(game.presses.count { it == "A" } > 2)
        assertFalse(c.isBusy)
        assertFalse(c.holdFrame)
    }

    @Test fun crystalStopsWhenTheGameRefusesTheSwitch() {
        // "can't be recalled": after SWITCH the game goes back to its list - the switch ends there, no more presses.
        val game = Game(gen = 2, count = 2)
        game.refuseSwitch = true
        game.openList(afterFaint = false)
        val c = controller(game)
        run(c, game, 8)
        c.switchTo(game.key(1))
        run(c, game, 600)
        assertEquals(0, game.out)
        assertEquals(BATTLE_INPUT_PARTY_OPEN, readGen2BattleInput(game, GEN2_CRYSTAL).first)
        val afterLastA = game.presses.takeLastWhile { it != "A" }
        assertEquals(emptyList<String>(), afterLastA)
        assertFalse(c.isBusy)
        assertFalse(c.holdFrame)
    }

    @Test fun crystalMovePickSteersOnTheListTheGameShows() {
        // Polished Crystal, live: FIGHT, then a DOWN sent from the poll's cursor as the move list opened was dropped (its
        // first frames take no press), and the A that followed used the first move. The pick reads the list's own
        // cursor every frame and presses again until it sits on the move: the move tapped is the one used.
        val game = Game(gen = 2, count = 2)
        game.moves = 2
        game.moveListDrop = 4                         // the 4-frame poll always sees the list inside the window
        val c = controller(game)
        run(c, game, 8)
        c.selectMove(1)                               // from the action menu: FIGHT + the move, one tap
        run(c, game, 200)
        assertEquals(1, game.usedMove)
        assertEquals(2, game.presses.count { it == "A" })   // FIGHT's and the move's, none after it took
        assertTrue(game.presses.toString(), game.presses.count { it == "DOWN" } >= 2)
        assertFalse(c.isBusy)
    }

    @Test fun crystalActionPickPressesAgainAnADroppedAsTheMenuOpened() {
        // Polished Crystal, live: a tap on FIGHT right as a new turn's menu appeared - its A was dropped, the menu stayed
        // up and the tap did nothing. A menu still waiting on the cell after the A never took it: A again.
        val game = Game(gen = 2, count = 2)
        val c = controller(game)
        run(c, game, 8)
        game.showActionMenu(dropFrames = 3)
        c.selectAction(0)
        run(c, game, 100)
        assertEquals(Mode.MOVES, game.mode)
        assertEquals(listOf("A", "A"), game.presses)
        assertFalse(c.isBusy)
    }

    private companion object {
        val KEYS = listOf(
            CoreKey.A to "A", CoreKey.B to "B", CoreKey.LEFT to "LEFT",
            CoreKey.RIGHT to "RIGHT", CoreKey.UP to "UP", CoreKey.DOWN to "DOWN",
        )
    }
}
