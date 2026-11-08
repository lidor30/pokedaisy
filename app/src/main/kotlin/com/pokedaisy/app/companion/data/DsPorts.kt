package com.pokedaisy.app.companion.data

/**
 * A DS cart in another language (or build) of a supported US / Europe game: its header's code and revision, the game it
 * runs as, its own title and SHA-1, and the globals the readers need ([saveData]: Gen 4's SaveData pointer / Gen 5's
 * SaveControl; [system]: the FieldSystem / GameSystem pointer; [battleTemplate]: Gen 4's battle overlay template).
 * [followed]: its touch-screen moments are followed (DsLayout.followed). The list is [NDS_PORTS], written by
 * scripts/gen_nds_ports.py from gamedata/nds/ports.toml, which also writes the native side's tables (pk_nds_ports_gen.h).
 */
data class NdsPort(
    val code: String,
    val revision: Int,
    val kind: GameKind,
    val title: String,
    val sha1: String,
    val saveData: Long,
    val system: Long,
    val battleTemplate: Long = 0,
    val followed: Boolean,
)

/** [NDS_PORTS]' lookups and the configs they run with: the US cart's, with the port's globals. */
object DsPorts {
    /** The port with this header, or null. */
    fun of(code: String?, revision: Int?): NdsPort? = NDS_PORTS.firstOrNull { it.code == code && it.revision == revision }

    /** The first port with this code, or null: a game code's revisions share their globals (Europe rev 10 HeartGold is the
     * US cart's code), so a reader that only knows the code takes any. */
    fun byCode(code: String?): NdsPort? = NDS_PORTS.firstOrNull { it.code == code }

    /** The game codes the ports add. */
    val codes: Set<String> by lazy { NDS_PORTS.mapTo(LinkedHashSet()) { it.code } }

    fun gen4Config(p: NdsPort): Gen4Config? {
        if (p.kind == GameKind.PLATINUM) {
            val us = GEN4_PLATINUM_US
            return us.copy(
                saveDataPtr = p.saveData,
                battle = us.battle!!.copy(fieldSystemPtr = p.system, battleTemplate = p.battleTemplate),
                townMap = us.townMap!!.copy(fieldSystemPtr = p.system),
            )
        }
        val us = when (p.kind) {
            GameKind.HEARTGOLD -> GEN4_HEARTGOLD_US
            GameKind.SOULSILVER -> GEN4_SOULSILVER_US
            else -> return null
        }
        // A build at the US cart's globals (Europe's rev 10) runs on the US config itself.
        val battle = us.battle!!
        if (p.saveData == us.saveDataPtr && p.system == battle.fieldSystemPtr && p.battleTemplate == battle.battleTemplate) return us
        return gen4HeartGoldFamily(saveDataPtr = p.saveData, fieldSystemPtr = p.system, battleTemplate = p.battleTemplate)
    }

    fun gen5Config(p: NdsPort): Gen5Config? {
        val base = when (p.kind) {
            GameKind.BLACK -> GEN5_BLACK
            GameKind.WHITE -> GEN5_WHITE
            GameKind.BLACK2 -> GEN5_BLACK2
            GameKind.WHITE2 -> GEN5_WHITE2
            else -> return null
        }
        return base.copy(saveControlPtr = p.saveData, gameSystemPtr = p.system, battle = base.battle?.copy(gameSystemPtr = p.system))
    }
}
