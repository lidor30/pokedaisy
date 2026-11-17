package com.pokedaisy.app.companion.data

/**
 * Pokémon Epsilon Emerald (v1.0.0; pokemerrp's demake of the 3DS Hoenn remakes, closed): a gcc pokeemerald on Emerald's
 * species and item ids, with an older pokeemerald-expansion battle engine. It runs as EMERALD with its own RAM
 * ([NATIVE_EPSILON_EMERALD], gamedata/gen3/hacks/epsilon_emerald.toml) and its own tables laid over Emerald's
 * ([emeraldHack] = [hackTextEpsilonEmerald], EpsilonEmeraldGen.kt). Retail Emerald's save loads in it.
 */
object EpsilonEmerald {
    const val V1_0_0_SHA1 = TelemetrySampler.EPSILON_EMERALD_V1_0_0_SHA1
}

/** Its bag ([PackedBagLayout]): 6-byte pocket entries, the TM pocket (index 2) a bit per machine. */
val PACKED_BAG_EPSILON_EMERALD: PackedBagLayout by lazy {
    PackedBagLayout(stride = 6, flagPocket = 2, flagItems = TM_ITEMS_EPSILON_EMERALD, itemPockets = ITEM_POCKETS_EPSILON_EMERALD)
}
