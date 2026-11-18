package com.pokedaisy.app

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.GEN1_BROWN
import com.pokedaisy.app.companion.data.Gen1PartySwitch
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import com.pokedaisy.app.companion.data.readGen1BattleInput
import com.pokedaisy.app.companion.data.readGen1PartyMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pokémon Brown's battle controls run through the app's own [BattleInputController] against the running game (mgba_dump
 * headless, [GbLiveGame]): FIGHT and a move, RUN, the POKéMON switch from the battle menu and the pick after a faint, each
 * read back from the game's RAM. The controller is driven as the app drives it: the battle state polled every 4 frames,
 * [BattleInputController.tick] every frame, its keys held into the next frame.
 *
 * Opt-in (Docker, the player's ROM and save): POKEDAISY_GB_LIVE=1 make test-quick T='*BrownLiveControlsTest'.
 */
class BrownLiveControlsTest {
    private fun rom(version: String) = File(File(RETAIL_ROM_DIR).parentFile, "gbc/Pokemon Brown (v$version).gb")
    private fun save(version: String) = File(System.getProperty("user.home"), "Documents/RetroArch/saves/mGBA/Pokemon Brown (v$version).sav")
    private val cfg = GEN1_BROWN

    private fun game(level: Int, version: String = "6.1.2"): GbLiveGame {
        val rom = rom(version)
        val save = save(version)
        assumeTrue("POKEDAISY_GB_LIVE not set", System.getenv("POKEDAISY_GB_LIVE") != null)
        assumeTrue("Brown ROM / save not on this machine", rom.isFile && save.isFile)
        val g = GbLiveGame(rom, save, log = { println("  mgba_dump: $it") })
        // native-capture/boot/brown.txt, then the brown_battle fixture's wild battle: CHIKORITA (internal 31) at [level].
        g.run("wait 1600", "press START", "wait 200", "press A", "wait 120", "press A", "wait 300",
            "gbpoke 0 d127 %02x".format(level), "gbpoke 0 d059 1f", "wait 600", "press A", "wait 300")
        assertEquals("the battle menu", BATTLE_INPUT_ACTION_SELECT, readGen1BattleInput(g, cfg).first)
        return g
    }

    private val input = GbaInput()
    private val presses = mutableListOf<String>()

    private fun controller(g: GbLiveGame) = BattleInputController(input, g, log = { println("  controller: $it") }).apply {
        gen1 = true
        switchAddrs = BattleInputController.SwitchAddrs(gb = Gen1PartySwitch(cfg))
        stateNow = { readGen1BattleInput(g, cfg).first }
    }

    /** Runs frames until [done] (at most [max]); returns how many. */
    private fun drive(c: BattleInputController, g: GbLiveGame, max: Int, done: () -> Boolean): Int {
        var last = 0
        for (f in 0 until max) {
            if (done()) return f
            if (f % 4 == 0) readGen1BattleInput(g, cfg).let { (state, cursor) -> c.onState(0, state, cursor) }
            c.tick()
            val now = input.mask
            val new = now and last.inv()
            last = now
            KEYS.filter { (bit, _) -> new and bit != 0 }.forEach { presses += it.second }
            if (System.getenv("POKEDAISY_GB_LIVE") == "trace") println("  frame ${g.frames}: ${readGen1BattleInput(g, cfg)} " +
                "menu ${readGen1PartyMenu(g, cfg)} out ${g.u8(cfg.playerMonNumber.toInt())} keys ${now} busy ${c.isBusy}")
            g.frame(KEYS.filter { (bit, _) -> now and bit != 0 }.joinToString(",") { it.second })
        }
        return max
    }

    private fun pp(g: GbLiveGame) = (0 until 4).map { g.u8(0xD02D + it) and 0x3F }

    @Test fun `FIGHT and the third move - its PP goes down, the others don't`() {
        game(level = 100).use { g ->
            val c = controller(g)
            drive(c, g, 8) { false }
            val before = pp(g)
            c.selectMove(2)                                   // MEGA KICK, from the battle menu: FIGHT, then the move
            val n = drive(c, g, 600) { pp(g)[2] < before[2] }
            println("FIGHT + move 3: $n frames, presses $presses")
            assertEquals(before.toMutableList().also { it[2]-- }, pp(g))
            drive(c, g, 300) { !c.isBusy }
            assertFalse(c.isBusy)
        }
    }

    @Test fun `RUN gets away`() {
        game(level = 3).use { g ->
            val c = controller(g)
            drive(c, g, 8) { false }
            c.selectAction(3)
            val n = drive(c, g, 900) { g.u8(cfg.isInBattle.toInt()) == 0 }
            println("RUN: $n frames, presses $presses")
            assertEquals(0, g.u8(cfg.isInBattle.toInt()))
        }
    }

    @Test fun `the POKEMON switch from the battle menu`() {
        game(level = 100).use { g ->
            val c = controller(g)
            drive(c, g, 8) { false }
            val omanyte = Gen1PartySwitch(cfg).party(g)[2]
            c.switchTo(omanyte.key)
            drive(c, g, 1) { false }
            assertTrue(c.holdFrame)                           // the game's party screen never shows
            val n = drive(c, g, 900) { g.u8(cfg.playerMonNumber.toInt()) == 2 && !c.isBusy }
            println("switch to slot 3: $n frames, presses $presses")
            assertEquals(2, g.u8(cfg.playerMonNumber.toInt()))
            assertFalse(c.holdFrame)
        }
    }

    @Test fun `after a faint the pick sends the Pokemon out`() {
        game(level = 100).use { g ->
            val c = controller(g)
            drive(c, g, 8) { false }
            // The lead down to 1 HP and poisoned (the battler and its party slot), then WATERFALL: the poison at the end of
            // the turn makes it faint.
            g.run("gbpoke 0 d015 00 01", "gbpoke 0 d018 08", "gbpoke 0 d16c 00 01", "gbpoke 0 d16f 08")
            c.selectMove(0)
            // "Use next POKéMON?" waits for the player's YES (A): the app leaves that to them.
            var guard = 0
            while (readGen1PartyMenu(g, cfg)?.forced != true && guard++ < 2000) {
                if (!c.isBusy && guard % 30 == 0) g.frame("A") else drive(c, g, 1) { false }
            }
            assertTrue("the party menu after a faint", readGen1PartyMenu(g, cfg)?.forced == true)
            presses.clear()
            val kabuto = Gen1PartySwitch(cfg).party(g)[3]
            c.switchTo(kabuto.key)
            val n = drive(c, g, 900) { g.u8(cfg.playerMonNumber.toInt()) == 3 && !c.isBusy }
            println("after a faint, slot 4: $n frames, presses $presses")
            assertEquals(3, g.u8(cfg.playerMonNumber.toInt()))
            assertEquals(1, presses.count { it == "A" })
        }
    }

    @Test fun `a move from the move list`() {
        game(level = 100).use { g ->
            val c = controller(g)
            drive(c, g, 8) { false }
            c.selectAction(0)                                 // FIGHT: the move list
            drive(c, g, 300) { readGen1BattleInput(g, cfg).first == BATTLE_INPUT_MOVE_SELECT && !c.isBusy }
            val before = pp(g)
            presses.clear()
            c.selectMove(3)                                   // HYDRO PUMP
            val n = drive(c, g, 600) { pp(g)[3] < before[3] }
            println("move 4 from the list: $n frames, presses $presses")
            assertEquals(before.toMutableList().also { it[3]-- }, pp(g))
        }
    }

    @Test fun `6_0_1 - the switch`() {
        game(level = 100, version = "6.0.1").use { g ->
            val c = controller(g)
            drive(c, g, 8) { false }
            c.switchTo(Gen1PartySwitch(cfg).party(g)[4].key)  // CHIKORITA
            val n = drive(c, g, 900) { g.u8(cfg.playerMonNumber.toInt()) == 4 && !c.isBusy }
            println("6.0.1 switch to slot 5: $n frames, presses $presses")
            assertEquals(4, g.u8(cfg.playerMonNumber.toInt()))
        }
    }

    @Test fun `6_0_1 - FIGHT and a move`() {
        game(level = 100, version = "6.0.1").use { g ->
            val c = controller(g)
            drive(c, g, 8) { false }
            val before = pp(g)
            c.selectMove(1)                                   // CRUNCH
            val n = drive(c, g, 600) { pp(g)[1] < before[1] }
            println("6.0.1 FIGHT + move 2: $n frames, presses $presses")
            assertEquals(before.toMutableList().also { it[1]-- }, pp(g))
        }
    }

    companion object {
        val KEYS = listOf(
            com.pokedaisy.app.companion.data.CoreKey.A to "A", com.pokedaisy.app.companion.data.CoreKey.B to "B", com.pokedaisy.app.companion.data.CoreKey.LEFT to "LEFT",
            com.pokedaisy.app.companion.data.CoreKey.RIGHT to "RIGHT", com.pokedaisy.app.companion.data.CoreKey.UP to "UP", com.pokedaisy.app.companion.data.CoreKey.DOWN to "DOWN",
        )
    }
}
