package com.pokedaisy.app.companion.data

import java.io.File
import java.security.MessageDigest

/**
 * A Game Boy Color (Gen 2) Pokémon game's own art, rebuilt from the player's ROM on its first launch
 * ([Gen1Art]'s twin - nothing of the game is bundled) into the same rom-art directory: its font as a
 * TrueType file ([BitmapTtf]), its party icons (16x32, frame 1 over frame 2) in the party menu's own
 * colours, the party menu's held-item / Mail marks, the POKéGEAR's Johto and Kanto maps (160x144, the
 * screen's own pixels, so a landmark's coordinates land where the game puts its icon) and the player
 * (CHRIS / KRIS) as the map shows them.
 *
 * Addresses are pret/pokecrystal's symbols for Crystal, pret/pokegold's for Gold and Silver, and their moved twins in Crystal Clear and Prism
 * ([Gen2ArtTables]); the ROM is checked by SHA1 first, so another cart never gets art cut from the wrong bytes.
 * Each game writes its own paths; the builds of one game (Crystal Clear's Standard Colors / Color Filter, its
 * two versions) share them, and the art is rebuilt whenever another of its builds starts.
 */
object Gen2Art {
    /** The egg's icon file: Gen 2's EGG species id. */
    const val EGG = 0xFD
    private const val UNOWN = 201

    const val CRYSTAL_FONT = "gen2/crystal-font.ttf"
    const val CRYSTAL_ICONS = "gen2/crystal/pokemon"
    /** The held-item marks, 8x16: an item over Mail (PartyScreen's HeldItemMarker reads RomArt.HOLD_ICONS' layout). */
    const val CRYSTAL_HOLD_ICONS = "gen2/crystal/hold_icons.png"
    /** The town maps, where MapScreen loads region maps from ([activeRegionMapImages]). */
    val CRYSTAL_TOWN_MAPS = arrayOf("crystal_johto", "crystal_kanto")
    /** The player on the town map, by gender (MapScreen's head assets). */
    const val CRYSTAL_PLAYER = "player_chris"
    const val CRYSTAL_PLAYER_F = "player_kris"

    const val GOLD_SILVER_FONT = "gen2/gold-silver-font.ttf"
    const val GOLD_SILVER_ICONS = "gen2/gold_silver/pokemon"
    const val GOLD_SILVER_HOLD_ICONS = "gen2/gold_silver/hold_icons.png"
    val GOLD_SILVER_TOWN_MAPS = arrayOf("gold_silver_johto", "gold_silver_kanto")
    /** The player (the boy: no gender choice before Crystal) on the town map. */
    const val GOLD_SILVER_PLAYER = "player_gold_silver"

    const val POLISHED_FONT = "gen2/polished-crystal-font.ttf"
    const val POLISHED_ICONS = "gen2/polished_crystal/pokemon"
    const val POLISHED_HOLD_ICONS = "gen2/polished_crystal/hold_icons.png"
    val POLISHED_TOWN_MAPS = arrayOf("polished_crystal_johto", "polished_crystal_kanto", "polished_crystal_orange")
    /** The player on its town maps, by the look picked at the start (wPlayerGender 0 / 1 / 2: CHRIS, KRIS, CRYS). */
    val POLISHED_PLAYERS = arrayOf("player_polished_chris", "player_polished_kris", "player_polished_crys")

    const val CRYSTAL_CLEAR_FONT = "gen2/crystal-clear-font.ttf"
    const val CRYSTAL_CLEAR_ICONS = "gen2/crystal_clear/pokemon"
    const val CRYSTAL_CLEAR_HOLD_ICONS = "gen2/crystal_clear/hold_icons.png"
    val CRYSTAL_CLEAR_TOWN_MAPS = arrayOf("crystal_clear_johto", "crystal_clear_kanto")

    const val PRISM_FONT = "gen2/prism-font.ttf"
    const val PRISM_ICONS = "gen2/prism/pokemon"
    const val PRISM_HOLD_ICONS = "gen2/prism/hold_icons.png"
    /** Its town map's six regions, in its region order (RegionCheck's, the MapSecDataPrism regions). */
    val PRISM_TOWN_MAPS = arrayOf("prism_naljo", "prism_rijon", "prism_johto", "prism_kanto", "prism_sevii", "prism_tunod")

    /** Bump when what [extractTo] writes changes, so a cached ROM's art is rebuilt once. */
    private const val VERSION = 4

    /** Where a Gen 2 game's own art sits in its ROM (bank, address). */
    class Gen2ArtTables(
        val name: String,                     // the art's family: [extractTo] keeps which build it holds
        val sha1s: Set<String>,
        val font: Pair<Int, Int>,             // Font: tiles 0x80-0xFF, 1bpp
        val fontPath: String,
        val icons: Pair<Int, Int>,            // IconPointers: a word per icon id (the icons' bank), 8 2bpp tiles each
        val monMenuIcons: Pair<Int, Int>?,    // MonMenuIcons: an icon id per Dex number; null = [icons] is by Dex number
        val eggIcon: Int,                     // ICON_EGG (or EGG's own entry in [icons])
        val heldItemIcons: Pair<Int, Int>,    // HeldItemIcons: Mail's tile, then the item's
        val partyMenuObPals: Pair<Int, Int>,  // PartyMenuOBPals: the icons' palette (its first)
        val iconsDir: String,
        val holdIconsPath: String,
        val townMapGfx: Pair<Int, Int>,       // TownMapGFX: LZ, the maps' 48 tiles
        val townMaps: List<Pair<Int, Int>>,   // JohtoMap, KantoMap: 20x18 tile ids, 0xFF ends
        val townMapPalMap: Pair<Int, Int>,    // TownMapPals.PalMap: a palette nybble per tile id (low first)
        val pokegearPals: Pair<Int, Int>,     // MalePokegearPals: 6 BG palettes
        val townMapPaths: List<String>,
        val playerSprites: List<Pair<Int, Int>>,   // ChrisSpriteGFX, KrisSpriteGFX: the standing frame is the first 4 tiles
        val objectPals: Pair<Int, Int>,       // MapObjectPals: morn's 8, then day's (red for CHRIS, blue for KRIS)
        val playerPaths: List<String>,
        // Crystal Clear's own icons: LZ-compressed, one per species, in the bank [iconBanks] gives (below 0x80 / from
        // it); UNOWN's by letter ([unownIcons]: its table, then the bank); each in its species' two colours (8-byte
        // entries from Dex number 0: light, dark, then the shiny pair) over black.
        val iconBanks: Pair<Int, Int>? = null,
        val unownIcons: Pair<Pair<Int, Int>, Int>? = null,
        val iconPalettes: Pair<Int, Int>? = null,
        // Prism's own icons: LZ, one per species id, from a table of {bank, address} ([iconFarPointers], by species from
        // 1, EGG's at 253), each in one of 8 OBJ palettes ([iconObjPals]) its byte in [iconPalIndex] picks (the high
        // nybble; the low one is the shiny's).
        val iconFarPointers: Pair<Int, Int>? = null,
        val iconPalIndex: Pair<Int, Int>? = null,
        val iconObjPals: Pair<Int, Int>? = null,
        val speciesCount: Int = 251,
        // Prism's town maps: LZ tilemaps of 20 x 17 tiles drawn from the screen's second row (the first is the place's
        // name), its tile palettes 8 BG palettes ([townMapPalCount]).
        val townMapsLz: Boolean = false,
        val townMapPalCount: Int = 6,
        // Polished Crystal: its own charmap for the font (FontNormal from tile 'A', 0x80, as Crystal's); party minis
        // ([minis]: MiniIconPointers, 7 bytes each - bank, mini, mask, icon - by cosmetic index, 8 LZ tiles) in each
        // species' own two colours ([monPalettes]: PokemonPalettes, by index + 1); town map bytes yxTTTTTT (flips:
        // TownMapFlips), tiles from [townMapPal0From] on in palette 0.
        val chars: Map<Int, Char>? = null,
        val minis: Pair<Int, Int>? = null,
        val monPalettes: Pair<Int, Int>? = null,
        val townMapFlips: Boolean = false,
        val townMapPal0From: Int = 0x60,
        // The player's sprite is LZ-compressed (Polished's) and its colours are [objectPals]' palette [playerPalFrom] + its
        // index (Crystal: day's red / blue from 8; Polished's PokegearOBPals: red, blue, green from 0).
        val playerLz: Boolean = false,
        val playerPalFrom: Int = 8,
    )

    val CRYSTAL = Gen2ArtTables(
        name = "crystal",
        sha1s = setOf(TelemetrySampler.CRYSTAL_SHA1, TelemetrySampler.CRYSTAL_REV1_SHA1, TelemetrySampler.CRYSTAL_AU_SHA1),
        font = 0x3E to 0x4200,
        fontPath = CRYSTAL_FONT,
        icons = 0x23 to 0x6BBF,
        monMenuIcons = 0x23 to 0x6AC4,
        eggIcon = 0x1C,
        heldItemIcons = 0x23 to 0x69F7,
        partyMenuObPals = 0x02 to 0x7681,
        iconsDir = CRYSTAL_ICONS,
        holdIconsPath = CRYSTAL_HOLD_ICONS,
        townMapGfx = 0x3E to 0x4BA0,
        townMaps = listOf(0x24 to 0x5FFF, 0x24 to 0x6168),
        townMapPalMap = 0x24 to 0x5F4B,
        pokegearPals = 0x02 to 0x7729,
        townMapPaths = CRYSTAL_TOWN_MAPS.map { "regionmap/$it.png" },
        playerSprites = listOf(0x30 to 0x4000, 0x31 to 0x7A40),
        objectPals = 0x02 to 0x7469,
        playerPaths = listOf("regionmap/$CRYSTAL_PLAYER.png", "regionmap/$CRYSTAL_PLAYER_F.png"),
    )

    /**
     * Gold (USA, Europe): pret/pokegold's symbols. Its art is Silver's byte for byte (one family: the paths are shared)
     * and only the party icons' tables sit elsewhere in Silver; the icons, town maps and player are Crystal's pixels,
     * the font its own. The player is the boy alone, in the day palettes' red as Crystal draws CHRIS.
     */
    val GOLD = Gen2ArtTables(
        name = "gold_silver",
        sha1s = setOf(TelemetrySampler.GOLD_SHA1),
        font = 0x3E to 0x42F2,
        fontPath = GOLD_SILVER_FONT,
        icons = 0x23 to 0x6A70,
        monMenuIcons = 0x23 to 0x6975,
        eggIcon = 0x1C,
        heldItemIcons = 0x23 to 0x68DB,
        partyMenuObPals = 0x02 to 0x7AC6,
        iconsDir = GOLD_SILVER_ICONS,
        holdIconsPath = GOLD_SILVER_HOLD_ICONS,
        townMapGfx = 0x3E to 0x4C92,
        townMaps = listOf(0x24 to 0x5F52, 0x24 to 0x60BB),
        townMapPalMap = 0x24 to 0x5EAC,
        pokegearPals = 0x02 to 0x7B6E,
        townMapPaths = GOLD_SILVER_TOWN_MAPS.map { "regionmap/$it.png" },
        playerSprites = listOf(0x30 to 0x4000),
        objectPals = 0x02 to 0x78AE,
        playerPaths = listOf("regionmap/$GOLD_SILVER_PLAYER.png"),
    )

    /** Silver: Gold's art, the party icons' tables 0x1A bytes earlier. */
    val SILVER = GOLD.let {
        Gen2ArtTables(
            it.name, setOf(TelemetrySampler.SILVER_SHA1), it.font, it.fontPath, 0x23 to 0x6A56, 0x23 to 0x695B, it.eggIcon,
            0x23 to 0x68C1, it.partyMenuObPals, it.iconsDir, it.holdIconsPath, it.townMapGfx, it.townMaps, it.townMapPalMap,
            it.pokegearPals, it.townMapPaths, it.playerSprites, it.objectPals, it.playerPaths,
        )
    }

    /**
     * Crystal Clear v2.6.0, both builds: Crystal's art moved (the town maps' and font's bytes where Crystal has them,
     * found by their contents; the icons and their palettes by the party menu's code, 23:6F3C). Its POKéGEAR map is the
     * RED gear's, the default. No player: the hack's player is customised (model, skin, colours), so it's left off.
     */
    val CRYSTAL_CLEAR_V2_6_0 = Gen2ArtTables(
        name = "crystal_clear",
        sha1s = setOf(TelemetrySampler.CRYSTAL_CLEAR_V2_6_0_SHA1, TelemetrySampler.CRYSTAL_CLEAR_V2_6_0_COLOR_FILTER_SHA1),
        font = 0x3E to 0x4200,
        fontPath = CRYSTAL_CLEAR_FONT,
        icons = 0x23 to 0x7019,
        monMenuIcons = null,
        eggIcon = EGG,
        heldItemIcons = 0x38 to 0x7459,
        partyMenuObPals = 0x02 to 0x7853,
        iconsDir = CRYSTAL_CLEAR_ICONS,
        holdIconsPath = CRYSTAL_CLEAR_HOLD_ICONS,
        townMapGfx = 0x3E to 0x53A0,
        townMaps = listOf(0x24 to 0x60A5, 0x24 to 0x620E),
        townMapPalMap = 0x24 to 0x602D,
        pokegearPals = 0x02 to 0x799B,
        townMapPaths = CRYSTAL_CLEAR_TOWN_MAPS.map { "regionmap/$it.png" },
        playerSprites = emptyList(),
        objectPals = 0x02 to 0x7469,
        playerPaths = emptyList(),
        iconBanks = 0x1B to 0x1D,
        unownIcons = (0x23 to 0x7215) to 0x47,
        iconPalettes = 0x02 to 0x622C,
    )

    /** Crystal Clear v2.5.11, both builds: v2.6.0's tables, its icons in other banks. */
    val CRYSTAL_CLEAR_V2_5_11 = CRYSTAL_CLEAR_V2_6_0.let {
        Gen2ArtTables(
            it.name, setOf(TelemetrySampler.CRYSTAL_CLEAR_V2_5_11_SHA1, TelemetrySampler.CRYSTAL_CLEAR_V2_5_11_COLOR_FILTER_SHA1),
            it.font, it.fontPath, it.icons, it.monMenuIcons, it.eggIcon, it.heldItemIcons, it.partyMenuObPals, it.iconsDir,
            it.holdIconsPath, it.townMapGfx, it.townMaps, it.townMapPalMap, it.pokegearPals, it.townMapPaths,
            it.playerSprites, it.objectPals, it.playerPaths,
            iconBanks = 0x1C to 0x1E, unownIcons = (0x23 to 0x7215) to 0x41, iconPalettes = it.iconPalettes,
        )
    }
    /**
     * Prism v0.95 build 254: its own art, found by the code that draws it - the party icons (23:7EA1 -> 1C:77BE, their
     * palette by 23:7D42), the font as the text screens load it, the town map (23:4F28's tilemaps, 23:4F4A's tiles,
     * 23:511B's palette map; the BG palettes the map screen shows, 02:6129). No player: six models to pick from.
     */
    val PRISM = Gen2ArtTables(
        name = "prism",
        sha1s = setOf(TelemetrySampler.PRISM_V0_95_254_SHA1),
        font = 0x3D to 0x720D,
        fontPath = PRISM_FONT,
        icons = 0x1C to 0x77CE,
        monMenuIcons = null,
        eggIcon = EGG,
        heldItemIcons = 0x23 to 0x7EBA,
        partyMenuObPals = 0x02 to 0x6079,
        iconsDir = PRISM_ICONS,
        holdIconsPath = PRISM_HOLD_ICONS,
        townMapGfx = 0x41 to 0x4210,
        townMaps = listOf(0x23 to 0x5181, 0x23 to 0x5252, 0x23 to 0x5340, 0x23 to 0x541F, 0x23 to 0x54F1, 0x23 to 0x5539),
        townMapPalMap = 0x23 to 0x5151,
        pokegearPals = 0x02 to 0x6129,
        townMapPaths = PRISM_TOWN_MAPS.map { "regionmap/$it.png" },
        playerSprites = emptyList(),
        objectPals = 0x02 to 0x6079,
        playerPaths = emptyList(),
        iconFarPointers = 0x1C to 0x77CE,
        iconPalIndex = 0x23 to 0x7BEB,
        iconObjPals = 0x02 to 0x6079,
        speciesCount = 254,
        townMapsLz = true,
        townMapPalCount = 8,
    )
    /** Polished Crystal's charmap (constants/charmap.asm) for the font's tiles - its own codes from 0x9A on. */
    private val POLISHED_CHARS: Map<Int, Char> = buildMap {
        for (i in 0 until 26) { put(0x80 + i, 'A' + i); put(0xA0 + i, 'a' + i) }
        for (i in 0 until 10) put(0xE0 + i, '0' + i)
        "().,?!".forEachIndexed { i, c -> put(0x9A + i, c) }
        "“”-:♂♀'".forEachIndexed { i, c -> put(0xBA + i, c) }
        "éÉáêíó¿¡".forEachIndexed { i, c -> put(0xC8 + i, c) }
        putAll(mapOf(0xD5 to '№', 0xD8 to '&', 0xD9 to '♪', 0xDA to '♥', 0xDB to '×', 0xDC to '/', 0xDD to '%', 0xDE to '+',
            0xEA to '¥', 0xEB to '…', 0xEC to '★', 0xED to '▼', 0xEE to '▲', 0xEF to '◀', 0xF0 to '▶'))
    }

    /**
     * Polished Crystal 3.2.x, each release build (normal, faithful, debug): its addresses from its .sym
     * ([polishedCrystalAddrs], gen_polished_crystal_tables.py); one family of paths, rebuilt when another build starts.
     */
    private val POLISHED_CRYSTAL: List<Gen2ArtTables> = polishedCrystalAddrs.map { (sha1, a) ->
        fun at(i: Int) = (a[i] shr 16) to (a[i] and 0xFFFF)
        Gen2ArtTables(
            name = "polished_crystal",
            sha1s = setOf(sha1),
            font = at(0),
            fontPath = POLISHED_FONT,
            icons = at(1),
            monMenuIcons = null,
            eggIcon = 0,
            heldItemIcons = at(3),
            partyMenuObPals = at(4),
            iconsDir = POLISHED_ICONS,
            holdIconsPath = POLISHED_HOLD_ICONS,
            townMapGfx = at(5),
            townMaps = listOf(at(6), at(7), at(8)),
            townMapPalMap = at(9),
            pokegearPals = at(10),
            townMapPaths = POLISHED_TOWN_MAPS.map { "regionmap/$it.png" },
            playerSprites = listOf(at(17), at(18), at(19)),
            objectPals = at(16),
            playerPaths = POLISHED_PLAYERS.map { "regionmap/$it.png" },
            townMapPalCount = 8,
            chars = POLISHED_CHARS,
            minis = at(1),
            monPalettes = at(2),
            townMapFlips = true,
            townMapPal0From = 0x40,
            playerLz = true,
            playerPalFrom = 0,
        )
    }

    private val GAMES = listOf(CRYSTAL, GOLD, SILVER, CRYSTAL_CLEAR_V2_6_0, CRYSTAL_CLEAR_V2_5_11, PRISM) + POLISHED_CRYSTAL

    /** The game's text characters for its font tiles 0x80-0xFF (constants/charmap.asm) the companion prints. */
    private val CHARS: Map<Int, Char> = buildMap {
        for (i in 0 until 26) { put(0x80 + i, 'A' + i); put(0xA0 + i, 'a' + i) }
        for (i in 0 until 10) put(0xF6 + i, '0' + i)
        putAll(mapOf(
            0x9A to '(', 0x9B to ')', 0x9C to ':', 0x9D to ';', 0x9E to '[', 0x9F to ']', 0xC0 to 'Ä', 0xC1 to 'Ö',
            0xC2 to 'Ü', 0xC3 to 'ä', 0xC4 to 'ö', 0xC5 to 'ü', 0xE0 to '\'', 0xE3 to '-', 0xE6 to '?', 0xE7 to '!',
            0xE8 to '.', 0xE9 to '&', 0xEA to 'é', 0xEB to '→', 0xEC to '▷', 0xED to '▶', 0xEE to '▼', 0xEF to '♂',
            0xF0 to '¥', 0xF1 to '×', 0xF3 to '/', 0xF4 to ',', 0xF5 to '♀',
        ))
    }

    /** [CHARS] or [POLISHED_CHARS]: the game's text characters for its font tiles 0x80-0xFF. */
    internal fun chars(polished: Boolean): Map<Int, Char> = if (polished) POLISHED_CHARS else CHARS

    private fun off(bank: Int, addr: Int) = if (bank == 0) addr else bank * 0x4000 + (addr - 0x4000)
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)

    /** GBC RGB555 at [o] as ARGB, the way mGBA shows it (5 bits widened by their top 3). */
    private fun rgb(rom: ByteArray, o: Int): Int {
        val c = u16(rom, o)
        fun ch(v: Int) = ((v and 31) shl 3) or ((v and 31) shr 2)
        return (0xFF shl 24) or (ch(c) shl 16) or (ch(c shr 5) shl 8) or ch(c shr 10)
    }

    private fun palette(rom: ByteArray, at: Pair<Int, Int>, index: Int = 0): IntArray {
        val o = off(at.first, at.second) + 8 * index
        return IntArray(4) { rgb(rom, o + 2 * it) }
    }

    /** 2bpp tile pixel (0-3). */
    private fun px(tiles: ByteArray, o: Int, x: Int, y: Int): Int {
        val lo = u8(tiles, o + 2 * y) shr (7 - x) and 1
        val hi = u8(tiles, o + 2 * y + 1) shr (7 - x) and 1
        return lo or (hi shl 1)
    }

    /** The font for [t] from [rom]'s Font, plus an empty space. */
    fun font(rom: ByteArray, t: Gen2ArtTables): ByteArray {
        val base = off(t.font.first, t.font.second)
        val glyphs = HashMap<Char, ByteArray>()
        for ((tile, c) in t.chars ?: CHARS) {
            val o = base + (tile - 0x80) * 8
            glyphs[c] = rom.copyOfRange(o, o + 8)
        }
        glyphs[' '] = ByteArray(8)
        return BitmapTtf.build("Gen2 Pokemon", glyphs)
    }

    /** Every species' party icon (and the egg's, as 253 - EGG), keyed by Dex number: 16x32, frame 1 over frame 2. */
    fun icons(rom: ByteArray, t: Gen2ArtTables): Map<Int, RomArt.Image> {
        val menuPal = palette(rom, t.partyMenuObPals)
        // A tile set [tiles] from [o] (8 tiles: TL, TR, BL, BR of frame 1, then frame 2) in [pal].
        fun draw(tiles: ByteArray, o: Int, pal: IntArray): RomArt.Image {
            val img = RomArt.Image(16, 32)
            for (tile in 0 until 8) for (y in 0 until 8) for (x in 0 until 8) {
                val v = px(tiles, o + 16 * tile, x, y)
                if (v != 0) img.argb[((tile / 4) * 16 + ((tile % 4) / 2) * 8 + y) * 16 + (tile % 2) * 8 + x] = pal[v]
            }
            return img
        }
        val minis = t.minis
        if (minis != null) {
            // Polished Crystal: by the companion's species id (POLISHED: its species' 9-bit value, then the variant forms;
            // 255 the egg), its mini at the cosmetic index (variant forms after the 0x38 cosmetic ones), in the colours of
            // its PokemonPalettes entry (index + 1): white, its two, black - InitPartyMenuOBPals, without the DV shading.
            val out = HashMap<Int, RomArt.Image>()
            val pals = off(t.monPalettes!!.first, t.monPalettes.second)
            for (id in speciesNamesPolishedCrystal.keys) {
                val mini = if (id > POLISHED_NUM_SPECIES) POLISHED_NUM_SPECIES + POLISHED_NUM_COSMETIC_FORMS + (id - POLISHED_NUM_SPECIES - 1) else id - 1
                val e = off(minis.first, minis.second) + 7 * mini
                val tiles = Gen2Lz.decompress(rom, off(u8(rom, e), u16(rom, e + 1)), polished = true)?.takeIf { it.size >= 128 } ?: continue
                val p = pals + 8 * id
                out[id] = draw(tiles, 0, intArrayOf(0, rgb(rom, p), rgb(rom, p + 2), 0xFF000000.toInt()))
            }
            return out
        }
        val far = t.iconFarPointers
        if (far != null) {
            // Prism: by species id, LZ, each in the OBJ palette its index byte names.
            val out = HashMap<Int, RomArt.Image>()
            val idx = off(t.iconPalIndex!!.first, t.iconPalIndex.second)
            for (species in 1..t.speciesCount) {
                val e = off(far.first, far.second) + 3 * (species - 1)
                val tiles = Gen2Lz.decompress(rom, off(u8(rom, e), u16(rom, e + 1)))?.takeIf { it.size >= 128 } ?: continue
                val pal = palette(rom, t.iconObjPals!!, u8(rom, idx + species - 1) shr 4 and 7)
                out[species] = draw(tiles, 0, intArrayOf(0, pal[1], pal[2], pal[3]))
            }
            return out
        }
        val banks = t.iconBanks
        if (banks != null) {
            // Crystal Clear: by Dex number, LZ, each in its own colours.
            val pals = t.iconPalettes!!
            fun pal(dex: Int): IntArray {
                val o = off(pals.first, pals.second) + 8 * dex
                return intArrayOf(0, rgb(rom, o), rgb(rom, o + 2), 0xFF000000.toInt())
            }
            fun lz(bank: Int, table: Pair<Int, Int>, i: Int): ByteArray? =
                Gen2Lz.decompress(rom, off(bank, u16(rom, off(table.first, table.second) + 2 * i)))?.takeIf { it.size >= 128 }
            val out = HashMap<Int, RomArt.Image>()
            for (dex in (1..251) + t.eggIcon) {
                val tiles = if (dex == UNOWN && t.unownIcons != null) lz(t.unownIcons.second, t.unownIcons.first, 0)
                else lz(if (dex < 0x80) banks.first else banks.second, t.icons, dex)
                tiles?.let { out[dex] = draw(it, 0, pal(dex)) }
            }
            return out
        }
        fun icon(id: Int): RomArt.Image = draw(rom, off(t.icons.first, u16(rom, off(t.icons.first, t.icons.second) + 2 * id)), menuPal)
        val menuIcons = off(t.monMenuIcons!!.first, t.monMenuIcons.second)
        val cache = HashMap<Int, RomArt.Image>()
        return (1..251).associateWith { dex -> cache.getOrPut(u8(rom, menuIcons + dex - 1)) { icon(u8(rom, menuIcons + dex - 1)) } } +
            (EGG to icon(t.eggIcon))
    }

    /** The held-item marks, item over Mail (8x16), in the icons' colours. */
    fun holdIcons(rom: ByteArray, t: Gen2ArtTables): RomArt.Image {
        val pal = palette(rom, t.partyMenuObPals)
        val o = off(t.heldItemIcons.first, t.heldItemIcons.second)
        val img = RomArt.Image(8, 16)
        for ((row, tile) in listOf(1, 0).withIndex()) for (y in 0 until 8) for (x in 0 until 8) {
            val v = px(rom, o + 16 * tile, x, y)
            if (v != 0) img.argb[(row * 8 + y) * 8 + x] = pal[v]
        }
        return img
    }

    /** Town map [i] (160x144): its tilemap over TownMapGFX, each tile in the palette TownMapPals gives it. */
    fun townMap(rom: ByteArray, t: Gen2ArtTables, i: Int): RomArt.Image? {
        val tiles = Gen2Lz.decompress(rom, off(t.townMapGfx.first, t.townMapGfx.second), polished = t.minis != null) ?: return null
        val pals = (0 until t.townMapPalCount).map { palette(rom, t.pokegearPals, it) }
        val palMap = off(t.townMapPalMap.first, t.townMapPalMap.second)
        val img = RomArt.Image(160, 144)
        // Prism's maps are LZ and start on the second row: the first (the place's name) stays the first palette's white.
        val lz = if (t.townMapsLz) Gen2Lz.decompress(rom, off(t.townMaps[i].first, t.townMaps[i].second))?.takeIf { it.size == 20 * 17 } ?: return null
            else null
        val first = if (lz != null) 20 else 0
        if (lz != null) for (p in 0 until 160 * 8) img.argb[p] = pals[0][0]
        val map = off(t.townMaps[i].first, t.townMaps[i].second)
        for (cell in first until 20 * 18) {
            val byte = if (lz != null) u8(lz, cell - 20) else u8(rom, map + cell)
            if (byte == 0xFF) return null
            // Polished Crystal: yxTTTTTT, the flips TownMapFlips puts in the attributes.
            val tile = if (t.townMapFlips) byte and 0x3F else byte
            val xFlip = t.townMapFlips && byte and 0x40 != 0
            val yFlip = t.townMapFlips && byte and 0x80 != 0
            if (tile * 16 + 16 > tiles.size) return null
            val pal = if (tile >= t.townMapPal0From) 0 else (u8(rom, palMap + tile / 2) shr (if (tile % 2 == 0) 0 else 4)) and 7
            val colors = pals.getOrNull(pal) ?: return null
            for (y in 0 until 8) for (x in 0 until 8) {
                val v = px(tiles, tile * 16, if (xFlip) 7 - x else x, if (yFlip) 7 - y else y)
                img.argb[((cell / 20) * 8 + y) * 160 + (cell % 20) * 8 + x] = colors[v]
            }
        }
        return img
    }

    /** The player [g] (0 CHRIS, 1 KRIS; Polished's 2 CRYS) on the town map: the walking sprite's first frame in day's red /
     * blue (Polished: the Pokégear's red / blue / green). */
    fun player(rom: ByteArray, t: Gen2ArtTables, g: Int): RomArt.Image {
        val (bank, addr) = t.playerSprites[g]
        val (tiles, base) = if (t.playerLz) (Gen2Lz.decompress(rom, off(bank, addr), polished = true) ?: ByteArray(64)) to 0
            else rom to off(bank, addr)
        val pal = palette(rom, t.objectPals, t.playerPalFrom + g)
        val img = RomArt.Image(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) {
            val v = px(tiles, base + 16 * ((y / 8) * 2 + x / 8), x % 8, y % 8)
            if (v != 0) img.argb[y * 16 + x] = pal[v]
        }
        return img
    }

    /**
     * Writes [rom]'s art into [dir] if it's a known Gen 2 cart, unless its family's paths already hold this very
     * build's (at this [VERSION]); returns the paths written. A family's builds (Crystal's releases, Crystal Clear's
     * versions and colour builds) share their paths, so starting another of them rebuilds them.
     */
    fun extractTo(rom: ByteArray, dir: File): List<String> {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(rom).joinToString("") { "%02x".format(it) }
        val t = GAMES.firstOrNull { sha1 in it.sha1s } ?: return emptyList()
        val marker = File(dir, "gen2/${t.name}-built")
        val stamp = "v$VERSION $sha1"
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return emptyList()
        val wrote = ArrayList<String>()
        fun write(path: String, bytes: ByteArray) {
            val out = File(dir, path)
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(out)
            wrote += path
        }
        write(t.fontPath, font(rom, t))
        icons(rom, t).forEach { (dex, img) -> write("${t.iconsDir}/$dex.png", img.png()) }
        write(t.holdIconsPath, holdIcons(rom, t).png())
        t.townMapPaths.forEachIndexed { i, path -> townMap(rom, t, i)?.let { write(path, it.png()) } }
        t.playerPaths.forEachIndexed { g, path -> write(path, player(rom, t, g).png()) }
        marker.parentFile?.mkdirs()
        marker.writeText(stamp)
        return wrote
    }

    /** In the background, once per ROM file: a Game Boy Color cart is 2 MB. */
    fun prefetch(filesDir: File, rom: File) {
        Thread({
            runCatching {
                val wrote = extractTo(rom.readBytes(), RomArt.dir(filesDir))
                if (wrote.isNotEmpty()) RomArt.announce()
            }.onFailure { android.util.Log.w("pokedaisy", "Gen2Art: failed", it) }
        }, "pokedaisy-gen2-art").apply { isDaemon = true; start() }
    }

}
