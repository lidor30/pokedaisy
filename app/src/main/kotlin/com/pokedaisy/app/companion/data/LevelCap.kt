package com.pokedaisy.app.companion.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * LEVEL CAP (SETTINGS > GAMEPLAY): no Pokémon goes past the next boss's top level, as the hard-mode hacks do. The cap is
 * the highest level on the team of the first boss NEXT BOSS lists ([next]), so it rises with each badge; with every boss
 * beaten there is none.
 *
 * Gen 3's getexp (battle_script_commands.c's Cmd_getexp) already stops a Pokémon at MAX_LEVEL twice, and the cap is those
 * two checks turned into "the cap or above" ([sites]):
 *  - case 2, before the "gained N EXP" message: a level-100 Pokémon gets nothing, no message, no EVs (`cmp r0, #100; bne`,
 *    or Emerald Legacy's `beq` the other way round) - now one at the cap or past it.
 *  - case 3, before each EXP step: Task_GiveExpToMon gives EXP one level at a time (exactly the next level's EXP, the rest
 *    handed back), and getexp asks again before giving the rest (`cmp r0, #100; beq`). So a Pokémon that reaches the cap
 *    mid-win stops there, with that level's EXP; the rest of the win is dropped.
 * The `cmp`'s 100 becomes the cap and `bne` / `beq` become `bcc` / `bcs`. Items that raise a level and the day care aren't
 * getexp: they still work.
 *
 * Found in agbcc's getexp ([ExpShare.getexp], so only where the game runs it): FireRed / LeafGreen / Emerald / Ruby / Sapphire
 * in every language and the hacks on that code. Patched with the other game options ([com.pokedaisy.app.EmulatorEngine]'s
 * applyTmPatch), again whenever the cap moves. Played headless on FireRed rev 1 and Emerald.
 */
object LevelCap {
    /** A `cmp r0, #100` + its branch: where ([addr]), the 4 bytes now, and whether the branch skips (beq) or goes on (bne)
     * when the level is 100. */
    class Site(val addr: Long, val expect: ByteArray, val skipsOnEqual: Boolean)

    class Found(val sites: List<Site>)

    /** The cap now: [level], from [boss]'s team (its title as the guide has it). */
    data class Cap(val level: Int, val boss: String)

    private const val ROM_BASE = 0x08000000L
    private const val BODY = 0x900
    private const val MAX_LEVEL = 100
    private const val MON_DATA_LEVEL = 0x38

    private val cache = HashMap<String, Found?>()

    private val lock = SynchronizedObject()

    /** What [rom] has, or null. Reads the file (a window at a time): off the UI thread; cached per file. */
    fun find(rom: Path): Found? {
        val key = fileKey(rom)
        synchronized(lock) { if (key in cache) return cache[key] }
        val found = runCatching {
            withRomFile(rom) { find(FileLittleEndian(it)) }
        }.getOrNull()
        synchronized(lock) { cache[key] = found }
        return found
    }

    fun find(rom: LittleEndianReader): Found? {
        val c = ExpShare.getexp(rom) ?: return null
        val end = minOf(rom.limit() - 10, c + BODY)
        // `movs r1, #MON_DATA_LEVEL; bl GetMonData; cmp r0, #100; b<eq|ne>`: the level checks, both calling one GetMonData.
        val hits = ArrayList<Pair<Int, Int>>()
        var i = c
        while (i < end) {
            if ((rom.getShort(i).toInt() and 0xFFFF) == (0x2100 or MON_DATA_LEVEL) &&
                (rom.getShort(i + 6).toInt() and 0xFFFF) == (0x2800 or MAX_LEVEL)
            ) {
                val cond = rom.get(i + 9).toInt() and 0xFF
                val target = blTarget(rom, i + 2)
                if (target != null && (cond == 0xD0 || cond == 0xD1)) hits += (i + 6) to target
            }
            i += 2
        }
        if (hits.size != 2 || hits[0].second != hits[1].second) return null
        val sites = hits.map { (at, _) ->
            Site(ROM_BASE + at, ByteArray(4) { rom.get(at + it) }, (rom.get(at + 3).toInt() and 0xFF) == 0xD0)
        }
        // The second one (case 3) always skips on equal; the first goes on past it (retail) or skips (Emerald Legacy).
        if (!sites[1].skipsOnEqual) return null
        return Found(sites)
    }

    /** [f]'s writes for a cap of [level] (1..99): each `cmp r0, #level` and its branch taken on "at or past it". */
    fun writes(f: Found, level: Int): List<InstantText.Write> {
        require(level in 1 until MAX_LEVEL)
        return f.sites.map { s ->
            val v = s.expect.copyOf()
            v[0] = level.toByte()
            // beq -> bcs (skip when level >= cap); bne -> bcc (go on only when level < cap).
            v[3] = (if (s.skipsOnEqual) 0xD2 else 0xD3).toByte()
            InstantText.Write(s.addr, s.expect, v)
        }
    }

    /**
     * The cap for [snap]'s save: the top level of the first boss its guide still has ahead, or null (no guide with
     * bosses, the teams unknown, every boss beaten, or a top level of 100). A boss whose team the save can't settle (a
     * difficulty) counts its highest. Reads the ROM's trainer table the first time: off the UI thread.
     */
    fun next(snap: SnapshotView): Cap? {
        val p = snap.progress ?: return null
        val live = snap.guideTables ?: return null
        val guide = gameGuide(guideId(snap.game, live)) ?: return null
        val bosses = guide.bossesFor?.invoke(snap.mapGroup) ?: guide.bosses
        val boss = bosses.firstOrNull { !it.isDone(p) } ?: return null
        val top = boss.trainers(p).mapNotNull { id ->
            (guide.world?.party(id, p) ?: GuideRomSource.party(live, id))?.maxOfOrNull { it.level }
        }.maxOrNull() ?: return null
        return if (top in 1 until MAX_LEVEL) Cap(top, boss.title) else null
    }

    /** Whether [snap]'s game has a NEXT BOSS list for the cap to follow (null = not known yet: no save read). */
    fun hasBosses(snap: SnapshotView): Boolean? {
        if (!snap.connected || snap.game == null) return null
        val guide = gameGuide(guideId(snap.game, snap.guideTables)) ?: return false
        return snap.guideTables != null && (guide.bosses.isNotEmpty() || guide.bossesFor != null)
    }

    private fun blTarget(rom: LittleEndianReader, at: Int): Int? {
        val h1 = rom.getShort(at).toInt() and 0xFFFF
        val h2 = rom.getShort(at + 2).toInt() and 0xFFFF
        if (h1 and 0xF800 != 0xF000 || h2 and 0xF800 != 0xF800) return null
        var hi = h1 and 0x7FF
        if (hi and 0x400 != 0) hi -= 0x800
        return at + 4 + (hi shl 12) + ((h2 and 0x7FF) shl 1)
    }
}
