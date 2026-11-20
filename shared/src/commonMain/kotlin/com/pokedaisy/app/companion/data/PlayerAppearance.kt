package com.pokedaisy.app.companion.data

import okio.Path

/** Selects the running game's appearance on the UI thread. Only costume preparation needs an asset worker. */
class PlayerAppearance(private val catalog: PlayerLookAssets.Catalog) {
    class CostumeRequest internal constructor(
        internal val own: LookChar,
        internal val national: Int,
        internal val party: Set<Int>,
    )

    class Selection(
        val heroes: List<LookChar>,
        val own: LookChar?,
        val pickedHero: LookChar?,
        val pickedCostume: Int?,
        val wornHero: LookChar?,
        val textStyle: LookEngine?,
        val patch: PlayerLook.Patch?,
        val costumeToPrepare: CostumeRequest?,
    )

    val textStyles = catalog.text.keys.toList()
    val rivalSupported get() = PlayerLook.hasRival(catalog.engine)

    private var costume: CostumeRequest? = null
    private var costumeReady = false
    private var costumePatch: PlayerLook.Patch? = null
    private var previousOwn: LookChar? = null
    private var appearance: PlayerLook.Patch? = null
    private var wornHero: LookChar? = null

    fun select(
        female: Boolean?,
        savedLook: String?,
        partyCostumes: Collection<Int>,
        dressRival: Boolean,
        vehicle: RideVehicle,
        savedText: String?,
    ): Selection {
        val own = female?.let { LookChar.of(catalog.engine, it) }
        val heroes = catalog.availableHeroes(female)
        val hero = LookChar.byName(savedLook)?.takeIf { it in heroes }
        val national = MonArt.monOf(savedLook)?.takeIf { own != null && it in partyCostumes }
        val party = if (national == null) emptySet() else partyCostumes.toSet()
        val current = costume
        var toPrepare: CostumeRequest? = null
        if (current?.national != national || (current != null && (current.own != own || current.party != party))) {
            toPrepare = if (own != null && national != null) CostumeRequest(own, national, party) else null
            costume = toPrepare
            costumeReady = false
            costumePatch = null
        }

        // Keep the last sprite for this hero while costume art is prepared. Text can change independently.
        val pending = costume != null && !costumeReady
        if (!pending || own != previousOwn) {
            val rival = dressRival && rivalSupported
            val heroPatch = female?.let {
                catalog.heroes[Triple(it, hero, rival)] ?: catalog.heroes[Triple(it, hero, false)]
            }
            appearance = costumePatch ?: own?.let { RideOn.withAppearance(catalog.scan, it, heroPatch, vehicle) }
            wornHero = hero.takeIf { heroPatch?.blocks?.isNotEmpty() == true }
            previousOwn = own
        }
        val text = catalog.text.keys.firstOrNull { it.name == savedText }
        return Selection(
            heroes, own, hero, national.takeIf { pending || costumePatch != null }, wornHero, text,
            TextLook.withAppearance(appearance, text?.let { catalog.text[it] }.orEmpty()), toPrepare,
        )
    }

    fun prepareCostume(files: Path, request: CostumeRequest): PlayerLook.Patch? =
        PokemonCostumes.build(files, catalog.scan, request.own, request.national, request.party, catalog.known)

    /** Ignore a result for a selection or party that changed while the worker ran. */
    fun completeCostume(request: CostumeRequest, patch: PlayerLook.Patch?): Boolean {
        if (costume !== request) return false
        costumePatch = patch
        costumeReady = true
        return true
    }
}
