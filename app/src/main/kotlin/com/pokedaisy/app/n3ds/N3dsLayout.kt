package com.pokedaisy.app.n3ds

/**
 * Where a 3DS Pokemon cart keeps what the companion reads from it (its RomFS paths, text file numbers, record shapes),
 * per engine: X / Y, Omega Ruby / Alpha Sapphire (X's formats, its own paths), Sun / Moon (Gen 7). Found on the US carts
 * (2026-11-17, docs/3DS_GAMES_PLAN.md "Omega Ruby / Alpha Sapphire" and "Sun / Moon"): each path decoded and checked
 * against known game facts (Bulbasaur GRASS / POISON, Tackle's power, Route 101's / Route 1's wild Pokemon).
 */
class N3dsLayout(
    val family: Family,
    /** Where the cart's pictures go in rom-art (icons, the Town Map). */
    val artDir: String,
    /** The save's language (MyStatus) to the text archive. */
    private val textArchives: Map<Int, Int>,
    private val textPrefix: String,
    val speciesFile: Int,
    val movesFile: Int,
    val itemsFile: Int,
    val abilitiesFile: Int,
    val naturesFile: Int,
    val typesFile: Int,
    val placesFile: Int,
    /** The moves' records: one GARC file each (X), or one packed file of [moveRecord]-byte records (ORAS, SM). */
    val movesPath: String,
    val movesPacked: Boolean,
    /** The personal records (types at +6 / +7). */
    val personalPath: String,
    /** The zones' records: [zonesPath]'s file [zoneRecordsFile] (negative = from the end), [zoneRecord] bytes each, the
     * place's line at +0x1C under [zonePlaceMask]. */
    val zonesPath: String,
    val zoneRecordsFile: Int,
    val zoneRecord: Int,
    val zonePlaceMask: Int,
    /** The party icons' archive and their container (X / ORAS: CLIM; SM: a rotated BFLIM). */
    val iconPath: String,
    val iconsGen7: Boolean,
    /** The highest species number the game has. */
    val maxSpecies: Int,
) {
    enum class Family { XY, ORAS, SM }

    fun textArchive(language: Int): String = textPrefix + (textArchives[language] ?: textArchives.getValue(2))

    /** Which icon of [iconPath] the game draws for a species / form / gender. */
    fun iconIndex(species: Int, form: Int, female: Boolean): Int? = when (family) {
        Family.XY -> Gen6IconTable.index(species, form, female)
        Family.ORAS -> OrasIconTable.index(species, form, female)
        Family.SM -> SmIconTable.index(species, form, female)
    }

    /** [index]'s icon as a party-slot sheet's asset path in rom-art. */
    fun iconAsset(index: Int): String = "$artDir/icons/$index.png"

    companion object {
        /** Pokemon X / Y (2026-11-14 / 15: the first findings). */
        val XY = N3dsLayout(
            family = Family.XY,
            artDir = "n3ds/pokemonx",
            textArchives = mapOf(1 to 3, 2 to 4, 3 to 5, 4 to 6, 5 to 7, 7 to 8, 8 to 9),
            textPrefix = "a/0/7/",
            speciesFile = 80, movesFile = 13, itemsFile = 96, abilitiesFile = 34, naturesFile = 47, typesFile = 17, placesFile = 72,
            movesPath = "a/2/1/2", movesPacked = false,
            personalPath = "a/2/1/8",
            zonesPath = "a/0/1/2", zoneRecordsFile = -1, zoneRecord = 0x38, zonePlaceMask = 0x1FF,
            iconPath = "a/0/9/3", iconsGen7 = false,
            maxSpecies = 721,
        )

        /** Omega Ruby / Alpha Sapphire: X's formats; the moves packed in one file, the zones' records the archive's
         * second-to-last file (its last is something else), Japanese kanji 2 and English 3. */
        val ORAS = N3dsLayout(
            family = Family.ORAS,
            artDir = "n3ds/oras",
            textArchives = mapOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 7 to 7, 8 to 8),
            textPrefix = "a/0/7/",
            speciesFile = 98, movesFile = 14, itemsFile = 114, abilitiesFile = 37, naturesFile = 51, typesFile = 18, placesFile = 90,
            movesPath = "a/1/8/9", movesPacked = true,
            personalPath = "a/1/9/5",
            zonesPath = "a/0/1/3", zoneRecordsFile = -2, zoneRecord = 0x38, zonePlaceMask = 0x1FF,
            iconPath = "a/0/9/1", iconsGen7 = false,
            maxSpecies = 721,
        )

        /** Sun / Moon (Gen 7): text a/0/3/<n> (Chinese 8 / 9: its species names need the game font's own characters,
         * so English stands in), 0x54-byte zone records with a u16 place line, BFLIM icons. */
        val SM = N3dsLayout(
            family = Family.SM,
            artDir = "n3ds/sm",
            textArchives = mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 7 to 6, 8 to 7),
            textPrefix = "a/0/3/",
            speciesFile = 55, movesFile = 113, itemsFile = 36, abilitiesFile = 96, naturesFile = 87, typesFile = 107, placesFile = 67,
            movesPath = "a/0/1/1", movesPacked = true,
            personalPath = "a/0/1/7",
            zonesPath = "a/0/7/7", zoneRecordsFile = 0, zoneRecord = 0x54, zonePlaceMask = 0xFFFF,
            iconPath = "a/0/6/2", iconsGen7 = true,
            maxSpecies = 807,
        )

        fun forTitle(titleId: Long): N3dsLayout = when (titleId) {
            N3dsCart.POKEMON_OR_USA, N3dsCart.POKEMON_AS_USA -> ORAS
            N3dsCart.POKEMON_SUN_USA, N3dsCart.POKEMON_MOON_USA -> SM
            else -> XY
        }
    }
}
