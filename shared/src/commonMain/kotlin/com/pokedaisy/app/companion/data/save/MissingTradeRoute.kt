package com.pokedaisy.app.companion.data.save

/** The editor action for selecting a source on the Missing page. */
sealed interface MissingTradeRoute<out Source> {
    data object SaveChangesFirst : MissingTradeRoute<Nothing>
    data class Trade<Source>(
        val partner: Source,
        val firstPick: MonRef? = null,
        val secondPick: MonRef? = null,
    ) : MissingTradeRoute<Source>

    data class PalPark<Source>(val source: Source, val pick: BoxSlot) : MissingTradeRoute<Source>
    data class PokeTransfer<Source>(val source: Source, val pick: BoxSlot) : MissingTradeRoute<Source>

    companion object {
        /** [sources] is in the same order as the saves used to build the Missing page. */
        fun <Source : Any> resolve(
            selected: MissingDex.Owned,
            sources: List<Source>,
            changed: Boolean,
            supportsTransfers: Boolean,
        ): MissingTradeRoute<Source>? {
            if (!selected.reachable) return null
            if (changed) return SaveChangesFirst
            val source = sources.getOrNull(selected.source) ?: return null
            return when (selected.way) {
                MissingDex.Way.TRADE, MissingDex.Way.EVOLVES -> Trade(source, secondPick = selected.at)
                MissingDex.Way.TRADE_BACK -> Trade(source, firstPick = selected.at)
                MissingDex.Way.PAL_PARK, MissingDex.Way.POKE_TRANSFER -> {
                    if (!supportsTransfers) return null
                    val slot = (selected.at as? MonRef.Pc)?.at ?: return null
                    if (selected.way == MissingDex.Way.PAL_PARK) PalPark(source, slot) else PokeTransfer(source, slot)
                }
            }
        }
    }
}
