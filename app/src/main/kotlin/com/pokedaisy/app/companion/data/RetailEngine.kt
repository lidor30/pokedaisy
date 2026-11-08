package com.pokedaisy.app.companion.data

/**
 * A ROM hack nothing knows by its hash that kept retail FireRed's or Emerald's engine: a hack made by editing retail
 * in place (a map editor, a hex editor, a patch tool) keeps retail's code, and with it retail's RAM. That is checked
 * address by address against fingerprints of the retail code that uses each one (RetailEngineGen.kt, from
 * scripts/gen_retail_engine.py): every global's loading sites, their code and their pool words. A rebuild of the
 * game (pokeemerald-expansion, a gcc build, ...) moves that code and fails at once; an in-place hack passes.
 *
 * What a proof gives (docs/DEVELOPMENT.md's "Retail engine"):
 *  - [Proof.ramProven]: every RAM global below is retail's, so the battle menus' cursors (the one-press picks) and the
 *    POKéMON switch's gPartyMenu are safe to use with a BEST EFFORT read of the hack;
 *  - [Proof.wideRow]: EXPAND's and the field reads' row for the hack (pk_wide_games.h's retail row with the ROM tables
 *    the hack repointed followed to where they are now), or null when one it needs can't be proven.
 */
object RetailEngine {
    class Proof(
        val build: RetailEngineBuild,
        val ramProven: Boolean,
        /** EXPAND's row for this ROM ([RetailEngineBuild.wideRow]'s layout), or null. */
        val wideRow: IntArray?,
        /** What failed, for the log. */
        val why: List<String>,
    )

    /** At least this share of an address's sites must hold (all of them when it has one or two). */
    private fun needed(n: Int): Int = if (n <= 2) n else maxOf(2, (3 * n + 3) / 4)

    /**
     * Checks a ROM of this [code] and [rev] against its retail build's fingerprints, reading the ROM's bytes through
     * [read] (offset from the ROM's start, length; null past its end). Null when no retail build has that code and
     * revision. Pure: nothing is remembered.
     */
    fun check(code: String, rev: Int, read: (Int, Int) -> ByteArray?): Proof? {
        val build = RETAIL_ENGINE_BUILDS.firstOrNull { it.code == code && it.rev == rev } ?: return null
        val why = ArrayList<String>()
        var ramOk = true
        for ((addr, sites) in build.ramSites) {
            val votes = votes(sites, read)
            val top = votes.maxByOrNull { it.value }
            if (top == null || top.value < needed(sites.size) || top.key != addr.toInt()) {
                ramOk = false
                why += "RAM %08X: %d of %d".format(addr, votes[addr.toInt()] ?: 0, sites.size)
            }
        }
        if (!ramOk) return Proof(build, ramProven = false, wideRow = null, why = why)
        val row = build.row.copyOf()
        var rowOk = true
        for ((slot, sites) in build.romSites) {
            val votes = votes(sites, read)
            val matched = votes.values.sum()
            val top = votes.maxByOrNull { it.value }
            val proven = top != null && top.value >= needed(sites.size) && top.value * 10 >= matched * 9 &&
                (top.key.toLong() and 0xFFFFFFFFL) in 0x08000000L until 0x0A000000L
            when {
                proven -> row[slot] = top!!.key
                slot in build.optionalSlots -> row[slot] = 0
                else -> {
                    rowOk = false
                    why += "ROM slot %d (%08X): %d of %d".format(slot, build.row[slot], top?.value ?: 0, sites.size)
                }
            }
        }
        return Proof(build, ramProven = true, wideRow = row.takeIf { rowOk }, why = why)
    }

    /** The pool words at the [sites] whose code window still matches retail's: value -> how many. */
    private fun votes(sites: List<RetailEngineBuild.Site>, read: (Int, Int) -> ByteArray?): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (s in sites) {
            val w = read(s.user - RetailEngineBuild.BEFORE, RetailEngineBuild.BEFORE + RetailEngineBuild.AFTER) ?: continue
            if (w.size < RetailEngineBuild.BEFORE + RetailEngineBuild.AFTER) continue
            val p = read(s.pool, 4)?.takeIf { it.size == 4 } ?: continue
            // The pool word blanked where it falls inside the window, as the generator did.
            for (k in 0 until 4) {
                val i = s.pool + k - (s.user - RetailEngineBuild.BEFORE)
                if (i in w.indices) w[i] = 0
            }
            if (crc32(w) != s.crc) continue
            val v = (p[0].toInt() and 0xFF) or (p[1].toInt() and 0xFF shl 8) or (p[2].toInt() and 0xFF shl 16) or
                (p[3].toInt() and 0xFF shl 24)
            out[v] = (out[v] ?: 0) + 1
        }
        return out
    }

    private fun crc32(b: ByteArray): Int = java.util.zip.CRC32().apply { update(b) }.value.toInt()
}

/** One retail build's fingerprints (RetailEngineGen.kt). */
class RetailEngineBuild(
    val id: String,
    val code: String,
    val rev: Int,
    /** The retail ROM's CRC32: its row in pk_wide_games.h. */
    val retailCrc: Int,
    val actionCursor: Long,
    val moveCursor: Long,
    /** The POKéMON switch's gPartyMenu and gPlayerParty (PokeDaisyActivity.switchAddrsFor's retail lines). */
    val partyMenu: Long,
    val playerParty: Long,
    wideRow: String,
    /** EXPAND slots a hack may lose (the far-off people's tables). */
    val optionalSlots: Set<Int>,
    ram: String,
    rom: String,
) {
    class Site(val user: Int, val pool: Int, val crc: Int)

    val row: IntArray = wideRow.split(',').map { it.toLong(16).toInt() }.toIntArray()
    val ramSites: List<Pair<Long, List<Site>>> by lazy { parse(ram).map { (k, v) -> k to v } }
    val romSites: List<Pair<Int, List<Site>>> by lazy { parse(rom).map { (k, v) -> k.toInt() to v } }

    private fun parse(s: String): List<Pair<Long, List<Site>>> = s.split(';').filter { it.isNotEmpty() }.map { e ->
        val key = e.substringBefore('=').toLong(16)
        key to e.substringAfter('=').split(',').filter { it.isNotEmpty() }.map {
            val f = it.split(':')
            Site(f[0].toInt(16), f[1].toInt(16), f[2].toLong(16).toInt())
        }
    }

    companion object {
        /** The window around a loading instruction (scripts/gen_retail_engine.py's BEFORE / AFTER). */
        const val BEFORE = 12
        const val AFTER = 14
    }
}
