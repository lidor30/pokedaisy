package com.pokedaisy.app.companion.data

/** The native core owns reversible, expected-byte-checked ROM edits. File bytes never change. */
interface RomOptionPatcher {
    fun unpatch()
    fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray): Boolean
}

/** Game options share one native patch set; a failed group must not leave half a feature or erase another. */
object GameOptionPatches {
    data class Result(val tms: Boolean, val text: Boolean, val hm: Boolean = false,
                      val full: Boolean = false, val cap: Boolean = false)
    fun apply(host: RomOptionPatcher, sites: List<ReusableTms.Site>, text: InstantText.Patch?,
              hm: HmFree.Patch? = null, full: ExpShare.Found? = null,
              cap: LevelCap.Found? = null, level: Int = 0): Result {
        fun writes(items: List<InstantText.Write>) = items.all { host.patch(it.addr, it.expect, it.addr, it.value) }
        val enabled = listOf(sites.isNotEmpty(), text != null, hm != null, full != null, cap != null && level in 1..99)
        val groups: List<() -> Boolean> = listOf(
            { sites.all { host.patch(it.at, it.expect, it.branch, ReusableTms.ALWAYS_SKIP) } },
            { writes(text!!.writes) }, { writes(hm!!.writes) }, { writes(full!!.full) },
            { writes(LevelCap.writes(cap!!, level)) },
        )
        val applied = BooleanArray(groups.size)
        host.unpatch()
        for (i in groups.indices) {
            if (!enabled[i]) continue
            if (groups[i]()) { applied[i] = true; continue }
            // Restore successful groups from clean bytes. A restoration can itself fail, so drop that
            // group and retry the remaining ones; at most one group is dropped on each pass.
            while (true) {
                host.unpatch()
                val failed = groups.indices.firstOrNull { applied[it] && !groups[it]() } ?: break
                applied[failed] = false
            }
        }
        return Result(applied[0], applied[1], applied[2], applied[3], applied[4])
    }
}
