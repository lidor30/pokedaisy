package com.pokedaisy.app.companion.data

import java.io.File
import java.security.MessageDigest

/**
 * A Game Boy Pokémon game's own art, rebuilt from the player's ROM on its first
 * launch (like [RomArt] for the GBA games - nothing of the game is bundled) into
 * the same rom-art directory: its font, as a TrueType file the companion sets every
 * text in ([BitmapTtf]), and its party icons, one two-frame sheet per species
 * (16x32, frame 1 over frame 2, like the GBA icon sheets) in the party menu's own
 * GBC colours.
 *
 * Addresses are pret/pokeyellow's symbols ([Gen1ArtTables]); the ROM is checked
 * by SHA1 first, so another cart never gets a font cut from the wrong bytes.
 */
object Gen1Art {
    const val YELLOW_FONT = "gen1/yellow-font.ttf"

    /** The running Yellow's font: the European carts' own letters (à, Ä, Ñ, ...) are in theirs ([romLanguage]). */
    fun yellowFont(language: Char): String = if (language == 'E') YELLOW_FONT else "gen1/yellow-font-${language.lowercaseChar()}.ttf"
    const val YELLOW_ICONS = "gen1/yellow/pokemon"
    /** The player on the town map: Red's standing sprite (MapScreen's head asset). */
    const val YELLOW_PLAYER = "regionmap/player_yellow.png"
    /** The town map, where MapScreen loads region maps from ([activeRegionMapImages] "yellow"). */
    const val YELLOW_TOWN_MAP = "regionmap/yellow.png"
    const val RED_BLUE_FONT = "gen1/red-blue-font.ttf"
    const val RED_BLUE_ICONS = "gen1/red_blue/pokemon"
    /** Red / Blue's town map and player ([activeRegionMapImages] "red_blue"), in their Super Game Boy colours. */
    const val RED_BLUE_TOWN_MAP_NAME = "red_blue"
    const val RED_BLUE_TOWN_MAP = "regionmap/$RED_BLUE_TOWN_MAP_NAME.png"
    const val RED_BLUE_PLAYER = "regionmap/player_red_blue.png"
    /** PureRed / PureBlue: one font, the icons, town map and player in each one's colours ("pure_red" / "pure_blue"). */
    const val PURE_FONT = "gen1/pure-font.ttf"
    fun pureTownMapName(game: GameKind?): String = if (game == GameKind.PURE_BLUE) "pure_blue" else "pure_red"
    fun pureIcons(game: GameKind?): String = "gen1/${pureTownMapName(game)}/pokemon"
    /** Yellow Legacy: its font (two tiles redrawn), icons and town map ("yellow_legacy"), Yellow's colours. */
    const val YELLOW_LEGACY_FONT = "gen1/yellow-legacy-font.ttf"
    const val YELLOW_LEGACY_ICONS = "gen1/yellow_legacy/pokemon"
    const val YELLOW_LEGACY_TOWN_MAP_NAME = "yellow_legacy"
    /** Bump when what [extractTo] writes changes, so a cached ROM's art is rebuilt once. */
    private const val VERSION = 3

    /** Where a Gen 1 game's own art sits in its ROM (bank, address). */
    class Gen1ArtTables(
        val sha1: String,
        val font: Pair<Int, Int>,
        val fontPath: String,
        val iconPointers: Pair<Int, Int>,
        val iconPointerCount: Int,
        val monPartyData: Pair<Int, Int>,
        val dexCount: Int,
        val iconsDir: String,
        /** The party menu's icon colours for pixel values 1-3 (0 is clear). */
        val iconColors: IntArray,
        val townMap: Pair<Int, Int>,
        val townMapTiles: Pair<Int, Int>,
        val townMapPath: String,
        /** The town map's GBC palette (pixel values 0-3). */
        val townMapColors: IntArray,
        /** The player's overworld sprite: its first 16x16 frame (facing down) is the town map's player. */
        val playerSprite: Pair<Int, Int>,
        val playerPath: String,
        val charset: Gen1Charset = Gen1Charset.EN,
        /** The player's colours for pixel values 0-3 when the sprite's palette isn't the map's (null: [townMapColors]). */
        val playerColors: IntArray? = null,
        /** PureRGB's party icons: a sheet of 8 tiles per icon (PartyMonSprites1, 32 px wide: frame 1 on the left, frame 2
         * on the right, no mirroring), [monPartyData] a byte per Dex number from MISSINGNO. at 0 ([icons]). */
        val iconSheet: Pair<Int, Int>? = null,
        /** Yellow Legacy's party icons: an icon of 8 tiles a species, frame 1 then frame 2 (TL, TR, BL, BR each), from [iconSheet]
         * for ids below 0x80 and this sheet above; [monPartyData] an id per Dex number from BULBASAUR at 0. */
        val iconSheet2: Pair<Int, Int>? = null,
    )

    val YELLOW = Gen1ArtTables(
        sha1 = TelemetrySampler.YELLOW_SHA1,
        font = 0x04 to 0x4600,                 // FontGraphics: tiles 0x80-0xFF, 1bpp
        fontPath = YELLOW_FONT,
        iconPointers = 0x1C to 0x584D,         // MonPartySpritePointers: {ptr, count, bank, VRAM dest}
        iconPointerCount = 30,                 // 15 icon loads per animation frame
        monPartyData = 0x1C to 0x59BA,         // icon type per Dex number, 2 per byte, high first
        dexCount = 151,
        iconsDir = YELLOW_ICONS,
        // The GBC party menu (sampled headless): white, yellow, black.
        iconColors = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF181818.toInt()),
        townMap = 0x1C to 0x518A,              // CompressedMap: RLE, a byte = tile (high) x count (low), 0 ends
        townMapTiles = 0x04 to 0x5138,         // WorldMapTileGraphics: the 16 tiles it uses, 2bpp
        townMapPath = YELLOW_TOWN_MAP,
        // CGB PAL_TOWNMAP (pokeyellow data/sgb/sgb_palettes.asm), the whole screen's.
        townMapColors = intArrayOf(0xFFFFFFFF.toInt(), 0xFF00ADFF.toInt(), 0xFF52E700.toInt(), 0xFF080808.toInt()),
        playerSprite = 0x05 to 0x4571,         // RedSprite: 16x16 frames of 4 tiles (TL, TR, BL, BR)
        playerPath = YELLOW_PLAYER,
    )

    /**
     * The German, French, Italian and Spanish Yellows: English's art, some of it moved (scripts/port_gen1.py,
     * checked: their icons, town map and player are English's pixel for pixel - `Gen1ArtTest` - so they share
     * its files), their own font (its accented letters).
     */
    private fun europe(sha1: String, language: Char, icons: Int, monParty: Int, townMap: Int, charset: Gen1Charset) =
        Gen1ArtTables(
            sha1 = sha1, font = YELLOW.font, fontPath = yellowFont(language),
            iconPointers = 0x1C to icons, iconPointerCount = YELLOW.iconPointerCount, monPartyData = 0x1C to monParty,
            dexCount = YELLOW.dexCount, iconsDir = YELLOW.iconsDir, iconColors = YELLOW.iconColors,
            townMap = 0x1C to townMap, townMapTiles = 0x04 to 0x5148, townMapPath = YELLOW.townMapPath,
            townMapColors = YELLOW.townMapColors, playerSprite = YELLOW.playerSprite, playerPath = YELLOW.playerPath,
            charset = charset,
        )
    val YELLOW_DE = europe(TelemetrySampler.YELLOW_DE_SHA1, 'D', 0x582C, 0x5999, 0x5188, Gen1Charset.DF)
    val YELLOW_FR = europe(TelemetrySampler.YELLOW_FR_SHA1, 'F', 0x581E, 0x598B, 0x518D, Gen1Charset.DF)
    val YELLOW_IT = europe(TelemetrySampler.YELLOW_IT_SHA1, 'I', 0x588D, 0x59FA, 0x518B, Gen1Charset.IS)
    val YELLOW_ES = europe(TelemetrySampler.YELLOW_ES_SHA1, 'S', 0x583D, 0x59AA, 0x518E, Gen1Charset.IS)
    /**
     * Red and Blue (pret/pokered's symbols, the same in both builds; their art is the same bytes, so one set of files).
     * A DMG cart with Super Game Boy support runs as a Super Game Boy in mGBA, so the colours are the SGB palettes the
     * game sends (data/sgb/sgb_palettes.asm), as sampled headless: the party menu's PAL_MEWMON through OBP0 (pixel 1
     * white, 2 its second colour, 3 black), the town map's PAL_TOWNMAP - its sprite through OBP0 too, so the player
     * there is white, light blue and black (FLY's map, headless). Their party icons have no PIKACHU shape: 14
     * icon loads a frame.
     */
    private fun redBlue(sha1: String) = Gen1ArtTables(
        sha1 = sha1,
        font = 0x04 to 0x5A80,
        fontPath = RED_BLUE_FONT,
        iconPointers = 0x1C to 0x57C0,
        iconPointerCount = 28,
        monPartyData = 0x1C to 0x590D,
        dexCount = 151,
        iconsDir = RED_BLUE_ICONS,
        iconColors = intArrayOf(0xFFFFEFFF.toInt(), 0xFFF7B58C.toInt(), 0xFF181010.toInt()),
        townMap = 0x1C to 0x5100,
        townMapTiles = 0x04 to 0x65A8,
        townMapPath = RED_BLUE_TOWN_MAP,
        townMapColors = intArrayOf(0xFFFFEFFF.toInt(), 0xFFA5D6FF.toInt(), 0xFF8CBD52.toInt(), 0xFF181010.toInt()),
        playerSprite = 0x05 to 0x4180,
        playerPath = RED_BLUE_PLAYER,
        playerColors = intArrayOf(0, 0xFFFFEFFF.toInt(), 0xFFA5D6FF.toInt(), 0xFF181010.toInt()),
    )
    val RED = redBlue(TelemetrySampler.RED_SHA1)
    val BLUE = redBlue(TelemetrySampler.BLUE_SHA1)

    /**
     * PureRed and PureBlue v2.7.6 (pureRGB's pokered.sym: the same addresses in both). A Color cart, its default COLORS
     * (OG) the Color's own two-tone palettes for the original carts, sampled headless: PureRed's screens white / pink /
     * dark red / black with green sprites, PureBlue's white / light blue / blue / black with pink ones. Its party icons are
     * its own ([Gen1ArtTables.iconSheet]: the ICONS option's OG set, MonPartyData); the font, town map and player are
     * pokered's art at its own addresses.
     */
    private fun pure(sha1: String, name: String, sprite: Int, map: IntArray) = Gen1ArtTables(
        sha1 = sha1,
        font = 0x04 to 0x5F00,
        fontPath = PURE_FONT,
        iconPointers = 0 to 0,
        iconPointerCount = 0,
        monPartyData = 0x35 to 0x5318,
        dexCount = 151,
        iconsDir = "gen1/$name/pokemon",
        iconColors = intArrayOf(0xFFFFFFFF.toInt(), sprite, 0xFF000000.toInt()),
        townMap = 0x1C to 0x4BBD,
        townMapTiles = 0x04 to 0x6A28,
        townMapPath = "regionmap/$name.png",
        townMapColors = map,
        playerSprite = 0x05 to 0x4180,
        playerPath = "regionmap/player_$name.png",
        charset = Gen1Charset.PURE,
        playerColors = intArrayOf(0, 0xFFFFFFFF.toInt(), sprite, 0xFF000000.toInt()),
        iconSheet = 0x34 to 0x4000,
    )
    val PURE_RED = pure(TelemetrySampler.PURE_RED_V2_7_6_SHA1, "pure_red", 0xFF7BFF31.toInt(),
        intArrayOf(0xFFFFFFFF.toInt(), 0xFFFF8484.toInt(), 0xFF943939.toInt(), 0xFF000000.toInt()))
    val PURE_BLUE = pure(TelemetrySampler.PURE_BLUE_V2_7_6_SHA1, "pure_blue", 0xFFFF8484.toInt(),
        intArrayOf(0xFFFFFFFF.toInt(), 0xFF63A5FF.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt()))

    /**
     * Yellow Legacy v1.0.10 (its pokeyellow.sym; a Color cart, its screens Yellow's colours, sampled headless). Its party
     * icons are its own, one per species ([Gen1ArtTables.iconSheet2]: MonIcons / MonIcons2, MonPartyData); its town map is
     * redrawn, Red's sprite Yellow's bytes.
     */
    val YELLOW_LEGACY = Gen1ArtTables(
        sha1 = TelemetrySampler.YELLOW_LEGACY_V1_0_10_SHA1,
        font = 0x04 to 0x4600,
        fontPath = YELLOW_LEGACY_FONT,
        iconPointers = 0 to 0,
        iconPointerCount = 0,
        monPartyData = 0x1C to 0x59D3,
        dexCount = 151,
        iconsDir = YELLOW_LEGACY_ICONS,
        iconColors = YELLOW.iconColors,
        townMap = 0x1C to 0x522A,
        townMapTiles = 0x04 to 0x51C8,
        townMapPath = "regionmap/$YELLOW_LEGACY_TOWN_MAP_NAME.png",
        townMapColors = YELLOW.townMapColors,
        playerSprite = 0x05 to 0x4605,
        playerPath = YELLOW_PLAYER,
        iconSheet = 0x40 to 0x4000,
        iconSheet2 = 0x0E to 0x64BF,
    )

    private val GAMES = listOf(YELLOW, YELLOW_DE, YELLOW_FR, YELLOW_IT, YELLOW_ES, RED, BLUE, PURE_RED, PURE_BLUE, YELLOW_LEGACY)

    private fun off(bank: Int, addr: Int) = if (bank == 0) addr else bank * 0x4000 + (addr - 0x4000)

    /** The font for [t] from [rom]'s FontGraphics, plus an empty space. */
    fun font(rom: ByteArray, t: Gen1ArtTables): ByteArray {
        val base = off(t.font.first, t.font.second)
        val glyphs = HashMap<Char, ByteArray>()
        for ((tile, c) in t.charset.glyphs) {
            val o = base + (tile - 0x80) * 8
            glyphs[c] = rom.copyOfRange(o, o + 8)
        }
        glyphs[' '] = ByteArray(8)
        return BitmapTtf.build("Gen1 Pokemon", glyphs)
    }

    private const val ICON_HELIX = 2      // the one asymmetric icon: 4 tiles, no mirroring
    private const val ICON_OFFSET = 0x40  // VRAM tiles of the second animation frame

    /**
     * Every species' party icon, keyed by Dex number: 16x32, frame 1 over frame 2. Built
     * the way the party menu builds them - MonPartySpritePointers loads the icon shapes'
     * tiles into VRAM, then each 16x16 is a tile column and its mirror image (the helix's
     * 4 tiles are drawn as they are).
     */
    fun icons(rom: ByteArray, t: Gen1ArtTables): Map<Int, RomArt.Image> {
        if (t.iconSheet2 != null) return legacyIcons(rom, t)
        t.iconSheet?.let { return pureIcons(rom, t, it) }
        val vram = HashMap<Int, ByteArray>()
        val ptrBase = off(t.iconPointers.first, t.iconPointers.second)
        for (e in 0 until t.iconPointerCount) {
            val o = ptrBase + 6 * e
            val ptr = (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
            val count = rom[o + 2].toInt() and 0xFF
            val bank = rom[o + 3].toInt() and 0xFF
            val dest = ((rom[o + 4].toInt() and 0xFF) or ((rom[o + 5].toInt() and 0xFF) shl 8)) - 0x8000
            for (k in 0 until count) {
                val src = off(bank, ptr) + 16 * k
                vram[dest / 16 + k] = rom.copyOfRange(src, src + 16)
            }
        }
        fun pixel(slot: Int, x: Int, y: Int): Int {
            val tile = vram[slot] ?: return 0
            val lo = tile[2 * y].toInt() shr (7 - x) and 1
            val hi = tile[2 * y + 1].toInt() shr (7 - x) and 1
            return lo or (hi shl 1)
        }
        fun frame(img: RomArt.Image, base: Int, asymmetric: Boolean, top: Int) {
            for (y in 0 until 16) for (x in 0 until 16) {
                val v = if (asymmetric) {
                    pixel(base + (y / 8) * 2 + x / 8, x % 8, y % 8)
                } else {
                    val slot = base + (y / 8) * 2
                    pixel(slot, if (x < 8) x else 15 - x, y % 8)
                }
                if (v != 0) img.argb[(top + y) * 16 + x] = t.iconColors[v - 1]
            }
        }
        val types = off(t.monPartyData.first, t.monPartyData.second)
        return (1..t.dexCount).associateWith { dex ->
            val b = rom[types + (dex - 1) / 2].toInt() and 0xFF
            val type = if ((dex - 1) % 2 == 0) b shr 4 else b and 0xF
            RomArt.Image(16, 32).also { img ->
                frame(img, type shl 2, type == ICON_HELIX, 0)
                frame(img, ICON_OFFSET + (type shl 2), type == ICON_HELIX, 16)
            }
        }
    }

    /**
     * PureRGB's icons (engine/gfx/mon_icons2.asm): GetPartyMonSpriteID's MonPartyData[Dex number] picks an icon, whose 8
     * tiles are copied as they are - OAM draws tiles 0, 1 over 4, 5 and the second frame two tiles on (2, 3 over 6, 7).
     */
    private fun pureIcons(rom: ByteArray, t: Gen1ArtTables, sheet: Pair<Int, Int>): Map<Int, RomArt.Image> {
        val base = off(sheet.first, sheet.second)
        val ids = off(t.monPartyData.first, t.monPartyData.second)
        return (1..t.dexCount).associateWith { dex ->
            val icon = base + 0x80 * (rom[ids + dex].toInt() and 0xFF)
            RomArt.Image(16, 32).also { img ->
                for (frame in 0 until 2) for (y in 0 until 16) for (x in 0 until 16) {
                    val tile = icon + 16 * ((y / 8) * 4 + frame * 2 + x / 8)
                    val lo = rom[tile + 2 * (y % 8)].toInt() shr (7 - x % 8) and 1
                    val hi = rom[tile + 2 * (y % 8) + 1].toInt() shr (7 - x % 8) and 1
                    val v = lo or (hi shl 1)
                    if (v != 0) img.argb[(frame * 16 + y) * 16 + x] = t.iconColors[v - 1]
                }
            }
        }
    }

    /** Yellow Legacy's icons (engine/gfx/mon_icons.asm): MonPartyData[Dex number - 1] is the icon, its 8 tiles in reading order. */
    private fun legacyIcons(rom: ByteArray, t: Gen1ArtTables): Map<Int, RomArt.Image> {
        val first = off(t.iconSheet!!.first, t.iconSheet.second)
        val second = off(t.iconSheet2!!.first, t.iconSheet2.second)
        val ids = off(t.monPartyData.first, t.monPartyData.second)
        return (1..t.dexCount).associateWith { dex ->
            val id = rom[ids + dex - 1].toInt() and 0xFF
            val icon = if (id < 0x80) first + 0x80 * id else second + 0x80 * (id - 0x80)
            RomArt.Image(16, 32).also { img ->
                for (frame in 0 until 2) for (y in 0 until 16) for (x in 0 until 16) {
                    val tile = icon + 16 * (frame * 4 + (y / 8) * 2 + x / 8)
                    val lo = rom[tile + 2 * (y % 8)].toInt() shr (7 - x % 8) and 1
                    val hi = rom[tile + 2 * (y % 8) + 1].toInt() shr (7 - x % 8) and 1
                    val v = lo or (hi shl 1)
                    if (v != 0) img.argb[(frame * 16 + y) * 16 + x] = t.iconColors[v - 1]
                }
            }
        }
    }

    /** The town map screen (160x144): LoadTownMap's RLE over WorldMapTileGraphics. */
    fun townMap(rom: ByteArray, t: Gen1ArtTables): RomArt.Image {
        val tiles = off(t.townMapTiles.first, t.townMapTiles.second)
        val img = RomArt.Image(160, 144)
        var o = off(t.townMap.first, t.townMap.second)
        var cell = 0
        while (rom[o].toInt() != 0 && cell < 20 * 18) {
            val b = rom[o].toInt() and 0xFF
            repeat(b and 0xF) {
                if (cell < 20 * 18) {
                    val cx = cell % 20
                    val cy = cell / 20
                    val tile = tiles + 16 * (b shr 4)
                    for (y in 0 until 8) for (x in 0 until 8) {
                        val lo = rom[tile + 2 * y].toInt() shr (7 - x) and 1
                        val hi = rom[tile + 2 * y + 1].toInt() shr (7 - x) and 1
                        img.argb[(cy * 8 + y) * 160 + cx * 8 + x] = t.townMapColors[lo or (hi shl 1)]
                    }
                }
                cell++
            }
            o++
        }
        return img
    }

    /** The town map's player: the sprite's first frame in the town map's own colours (0 clear), as the game draws it. */
    fun player(rom: ByteArray, t: Gen1ArtTables): RomArt.Image {
        val base = off(t.playerSprite.first, t.playerSprite.second)
        val img = RomArt.Image(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) {
            val tile = base + 16 * ((y / 8) * 2 + x / 8)
            val lo = rom[tile + 2 * (y % 8)].toInt() shr (7 - x % 8) and 1
            val hi = rom[tile + 2 * (y % 8) + 1].toInt() shr (7 - x % 8) and 1
            val v = lo or (hi shl 1)
            if (v != 0) img.argb[y * 16 + x] = (t.playerColors ?: t.townMapColors)[v]
        }
        return img
    }

    /** Writes [rom]'s art into [dir] if it's a known Gen 1 cart (once per [VERSION]); returns the paths written. */
    fun extractTo(rom: ByteArray, dir: File): List<String> {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(rom).joinToString("") { "%02x".format(it) }
        val t = GAMES.firstOrNull { it.sha1 == sha1 } ?: return emptyList()
        val marker = File(dir, "gen1/scanned-v$VERSION-${sha1.take(12)}")
        if (marker.isFile) return emptyList()
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
        write(t.townMapPath, townMap(rom, t).png())
        write(t.playerPath, player(rom, t).png())
        marker.parentFile?.mkdirs()
        marker.writeText(wrote.size.toString())
        return wrote
    }

    /** In the background, once per ROM file: a Game Boy cart is at most a few MB. */
    fun prefetch(filesDir: File, rom: File) {
        Thread({
            runCatching {
                val wrote = extractTo(rom.readBytes(), RomArt.dir(filesDir))
                if (wrote.isNotEmpty()) RomArt.announce()
            }.onFailure { android.util.Log.w("pokedaisy", "Gen1Art: failed", it) }
        }, "pokedaisy-gen1-art").apply { isDaemon = true; start() }
    }
}
