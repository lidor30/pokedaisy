package com.pokedaisy.app.companion.data

// Gen 5 (Black, White, Black 2, White 2): PC anywhere and FLY. The game does both itself (nds/pk_nds_call.cpp's Gen5Fns: the PC's own script, and
// FLY the party menu's way - its check of the place, then its flight); this is what the app decides around them: which
// towns FLY offers (the game's Town Map offers a town once its arrival flag is set) and what they're called.

/** The towns FLY can reach on [kind] (a Gen 5 game), in the Town Map's order; empty for any other game. */
fun gen5FlySpots(kind: GameKind?): List<FlySpot> = when (kind) {
    GameKind.BLACK -> flySpotsBlack
    GameKind.WHITE -> flySpotsWhite   // White Forest's zone where Black City's is
    GameKind.BLACK2 -> flySpotsBlack2
    GameKind.WHITE2 -> flySpotsWhite2   // White Forest again, where Black 2 has Black City
    else -> emptyList()
}

/**
 * A Gen 5 FLY destination's id ([FlyTargets.destinations]): its town's place (the line of the cart's place names), which is
 * also how the MAP tab's Town Map names its places. -1 if the zone has none.
 */
fun gen5FlyPlace(cfg: Gen5Config, spot: FlySpot): Int = cfg.zonePlaceIds[spot.mapHeader] ?: -1

/** The fly spot whose town is place [place], or null. */
fun gen5FlySpotAt(cfg: Gen5Config, place: Int, spots: List<FlySpot>): FlySpot? = spots.firstOrNull { gen5FlyPlace(cfg, it) == place }

/** The FLY check's answer (the skill's own test: 0 = may fly from here, 1 = not here - indoors, a cave) as FLY's. */
fun gen5FlyResult(error: Int): FlyResult = when (error) {
    0 -> FlyResult.OK
    1 -> FlyResult.CANT_FLY_HERE
    else -> FlyResult.NOT_NOW
}

/** The towns FLY offers now ([gen5FlyPlace] ids): those whose arrival flag the save has set, in [spots]' order. */
fun gen5OpenFlySpots(r: (Long, Int) -> ByteArray, cfg: Gen5Config, spots: List<FlySpot>): List<Int> {
    val img = gen5Image(r, cfg)
    if (img < 0) return emptyList()
    val flags = r(img + cfg.varsFlagsOff + cfg.numVars * 2, cfg.numFlags / 8)
    if (flags.size < cfg.numFlags / 8) return emptyList()
    return spots.filter { s ->
        s.flag in 0 until cfg.numFlags && (flags[s.flag / 8].toInt() shr (s.flag % 8)) and 1 != 0
    }.map { gen5FlyPlace(cfg, it) }.filter { it >= 0 }
}

/** A DS game's FLY destination [id] by name: Gen 4's are spawn ids ([flySpotName]), Gen 5's places ([gen5FlyPlace]). */
fun dsFlySpotName(kind: GameKind?, id: Int): String? {
    gen4ConfigFor(kind)?.let { return it.flySpotName(id) }
    val cfg = gen5ConfigFor(kind) ?: return null
    return gen5FlySpotAt(cfg, id, gen5FlySpots(kind))?.let { cfg.placeName(it.mapHeader) }
}
