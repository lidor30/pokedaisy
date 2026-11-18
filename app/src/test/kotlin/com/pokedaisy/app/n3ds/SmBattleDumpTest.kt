package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sun / Moon's battle, read by [Gen6Reader.POKEMON_SM_USA] out of whole-memory dumps of real battles (n3ds_dump's
 * `dump`, one `<base>.bin` a region): the chained menu bytes and the battlers' records, as N3dsSession reads them.
 * Opt-in: POKEDAISY_SM_DUMPS names a folder of dump folders, each named `<state>-<anything>` (action, moves, bag, party,
 * turn); the players' saves' battles, never committed.
 */
class SmBattleDumpTest {
    private val c = Gen6Reader.POKEMON_SM_USA

    private class Dump(dir: File) {
        private val regions = dir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty()
            .map { it.name.removeSuffix(".bin").toLong(16).toInt() to it }.sortedBy { it.first }
        private val loaded = HashMap<Int, ByteArray>()
        fun read(addr: Int, n: Int): ByteArray? {
            for ((base, f) in regions) {
                val off = (addr.toLong() and 0xFFFFFFFFL) - (base.toLong() and 0xFFFFFFFFL)
                if (off >= 0 && off + n <= f.length()) {
                    val d = loaded.getOrPut(base) { f.readBytes() }
                    return d.copyOfRange(off.toInt(), off.toInt() + n)
                }
            }
            return null
        }
        fun word(addr: Int): Int? = read(addr, 4)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt(0) }
    }

    private fun input(d: Dump): Int {
        val menu = Gen6Reader.chained(c.battleMenu, c.battleMenuPath, d::word) ?: return -1
        val sub = Gen6Reader.chained(c.battleSubmenu, c.battleSubmenuPath, d::word) ?: return -1
        return Gen6Reader.battleInputSm(d.read(menu, 1)!![0].toInt() and 0xFF, d.read(sub, 1)!![0].toInt() and 0xFF)
    }

    private fun mon(d: Dump, at: Int): Gen6Reader.BattleMon? {
        val rec = d.word(at)?.takeIf(Gen6Reader::inHeap) ?: return null
        return Gen6Reader.battleMon(d.read(rec, Gen6Reader.battleMonBytes(c)), c.battleMovesAt)
    }

    @Test fun readsEveryDumpedBattle() {
        val root = System.getenv("POKEDAISY_SM_DUMPS")?.let(::File)
        assumeTrue(root != null && root.isDirectory)
        val want = mapOf(
            "action" to BATTLE_INPUT_ACTION_SELECT, "moves" to BATTLE_INPUT_MOVE_SELECT, "bag" to BATTLE_INPUT_BAG_OPEN,
            "party" to BATTLE_INPUT_PARTY_OPEN, "turn" to BATTLE_INPUT_NONE,
        )
        val dirs = root!!.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        assumeTrue(dirs.isNotEmpty())
        for (dir in dirs) {
            val state = dir.name.substringBefore('-')
            val d = Dump(dir)
            assertEquals(dir.name, want.getValue(state), input(d))
            val player = mon(d, c.battlerPlayer)
            val foe = mon(d, c.battlerFoe)
            assertNotNull(dir.name, player)
            assertNotNull(dir.name, foe)
            println("${dir.name}: player ${player!!.species} Lv${player.level} ${player.hp}/${player.maxHp} ${player.moves.map { it.id }}, " +
                "foe ${foe!!.species} Lv${foe.level} ${foe.hp}/${foe.maxHp} ${foe.moves.map { it.id }}")
        }
    }
}
