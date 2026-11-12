package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile

/**
 * A DS game's art, rebuilt from the player's own cart like [RomArt] / [Gen2Art] (nothing of the game is bundled):
 * Platinum's party icons and its Town Map ([townMap]). The cart's file system (header FNT / FAT) gives `/poketool/icongra/pl_poke_icon.narc`;
 * its member 0 is the palette file (three 16-colour palettes), members 7 + species the icons - 4bpp, 8x8 tiles, 4 across,
 * 32x64: the two frames of the party menu's bounce, as the GBA games' sheets. Which palette a species takes is
 * [iconPalettesPlatinum] (the game's sPokemonIconPaletteIndex, generated from the decomp). Written under
 * [PLATINUM_ICONS] in rom-art as `<species>.png` (494 = the egg's), what [activeMonSpriteDir] points at. HeartGold /
 * SoulSilver keep the same archive as `/a/0/2/0` (pokeheartgold's NARC_poketool_icongra_poke_icon), its members in the
 * same order, palettes by [iconPalettesHeartGold], under [HEARTGOLD_ICONS] (one set for both: the same icons).
 */
object NdsArt {
    const val PLATINUM_ICONS = "nds/platinum/pokemon"
    const val HEARTGOLD_ICONS = "nds/heartgold/pokemon"
    /** Item icons (the bag's, 32 x 32), from the cart's item_icon.narc by [itemIconsPlatinum] / [itemIconsHeartGold]. */
    const val PLATINUM_ITEMS = "nds/platinum/items"
    const val HEARTGOLD_ITEMS = "nds/heartgold/items"
    /** Origin HeartGold ([OriginHeartGold]): its party icons by species / form id (1..1440, [ORIGIN_EGG] the egg's) and
     * item icons by item id, from its own archives ([extractOrigin]). */
    const val ORIGIN_ICONS = "nds/originhg/pokemon"
    const val ORIGIN_ITEMS = "nds/originhg/items"
    const val ORIGIN_EGG = 2000
    /** Its DEX pages and front pictures ([dexOrigin]), as [HEARTGOLD_DEX] / [HEARTGOLD_FRONT] are HeartGold's. */
    const val ORIGIN_DEX = "nds/originhg/dex.tsv"
    const val ORIGIN_FRONT = "nds/originhg/front"
    /** Renegade Platinum ([RenegadePlatinum]): Platinum's archives with its own icons (in its own palettes), item icons,
     * DEX pages and pictures, kept apart from a retail Platinum's. */
    const val RENEGADE_ICONS = "nds/renegade/pokemon"
    const val RENEGADE_ITEMS = "nds/renegade/items"
    const val RENEGADE_DEX = "nds/renegade/dex.tsv"
    const val RENEGADE_FRONT = "nds/renegade/front"
    /** Blaze Black 2 / Volt White 2 Redux ([Black2Redux]): Black 2's archives with their own icons (in their own
     * palettes), item icons, DEX pages and pictures, kept apart from a retail Black 2's; both hacks write the same paths
     * (their art and text are the same files), the build's personal records in the marker ([extractTo]). */
    const val REDUX_ICONS = "nds/redux/pokemon"
    const val REDUX_ITEMS = "nds/redux/items"
    const val REDUX_DEX = "nds/redux/dex.tsv"
    const val REDUX_FRONT = "nds/redux/front"
    /** HeartGold / SoulSilver's bag pocket buttons ([bagPockets]): pocket 0-7 in the game's order, open or not. */
    fun heartGoldPocket(pocket: Int, open: Boolean) = "nds/heartgold/bag/pocket_${pocket}_${if (open) "on" else "off"}"
    /** The DEX pages from the cart ([dex]): a line a species, tab-separated (see [Gen4Dex]). */
    const val HEARTGOLD_DEX = "nds/heartgold/dex.tsv"
    const val PLATINUM_DEX = "nds/platinum/dex.tsv"
    /** Each species' front picture (80 x 80, its first frame) under here as `<species>.png`. */
    const val HEARTGOLD_FRONT = "nds/heartgold/front"
    const val PLATINUM_FRONT = "nds/platinum/front"
    /** Pokémon Black (IRBO rev 0, [extractGen5]): party icons (1..649, [BLACK_EGG] the egg's), item icons, the DEX's
     * pages and front pictures (96 x 96). */
    const val BLACK_ICONS = "nds/black/pokemon"
    const val BLACK_ITEMS = "nds/black/items"
    const val BLACK_DEX = "nds/black/dex.tsv"
    const val BLACK_FRONT = "nds/black/front"
    /** Pokémon White (IRAO rev 0): its cart's art is Black's, file for file, so it shares Black's paths; only its DEX
     * descriptions are its own (the text's bank 236, Black's 235), in this file. */
    const val WHITE_DEX = "nds/white/dex.tsv"
    /** The egg's party icon under [BLACK_ICONS] (the icon archive's 650th; 651 is Manaphy's egg). */
    const val BLACK_EGG = 650
    /** Black's Town Map under rom-art/regionmap ([townMapBlack]): the map (256 x 168), the sprite a place of kind n
     * gets once arrived at ([blackTown]), the player's marker and the cursor's two frames. */
    const val BLACK_MAP = "black_unova"
    fun blackTown(kind: Int) = "bw_town_$kind"
    const val BLACK_PLAYER = "bw_player"
    fun blackCursor(frame: Int) = "bw_cursor_$frame"
    /** Pokémon Black 2 (IREO rev 0): its own icons, item icons (the archive renumbered from item 505 on), DEX pages,
     * fronts and Town Map ([GEN5_BLACK2_FILES]); White 2's cart (IRDO) has the same files, written to the same paths. */
    const val BLACK2_ICONS = "nds/black2/pokemon"
    const val BLACK2_ITEMS = "nds/black2/items"
    const val BLACK2_DEX = "nds/black2/dex.tsv"
    const val BLACK2_FRONT = "nds/black2/front"
    const val BLACK2_MAP = "black2_unova"
    fun black2Town(kind: Int) = "b2_town_$kind"
    const val BLACK2_PLAYER = "b2_player"
    fun black2Cursor(frame: Int) = "b2_cursor_$frame"
    /** The Town Map's player marker and cursor frames for a Gen 5 [game] (Black's, or the sequels'). */
    fun gen5Player(game: GameKind?) = if (game.isBw2) BLACK2_PLAYER else BLACK_PLAYER
    fun gen5Cursor(game: GameKind?, frame: Int) = if (game.isBw2) black2Cursor(frame) else blackCursor(frame)
    private const val VERSION = 11
    /** The Town Map's art under rom-art/regionmap: the map, each fly location's block sprite and the player's head. */
    const val PLATINUM_MAP = "platinum_sinnoh"
    fun platinumBlock(shape: Int, variant: Int) = "pt_block_${shape}_$variant"
    const val PLATINUM_HEAD_M = "player_lucas"
    const val PLATINUM_HEAD_F = "player_dawn"
    /** The Town Map's map area on the top screen (left, top, right, bottom): its frame and name bar cropped off. */
    val PLATINUM_MAP_CROP = intArrayOf(20, 0, 236, 168)
    private const val EGG = 494

    /** rom-art's folder, once [prefetch] has run (the DEX reads its pages and pictures from it: [Gen4Dex]). */
    @Volatile var dir: File? = null

    /**
     * The running cart's own names, read from its text on every start ([readNames]; null until then, or for a cart this
     * doesn't know). A translated or renamed cart keeps the US game's header code, so it runs as that game: GitHub #230,
     * a French Sacred Gold (an IPKE HeartGold) showed the English names the app's tables have. Lines by id, as the game's.
     */
    class Names(
        val species: List<String>, val moves: List<String>, val items: List<String>, val places: List<String>,
        /** The game's language by its type names' FIGHTING (COMBAT, KAMPF, ...); 'E' when it's none of them. */
        val language: Char,
        /** Black's (Gen 5): merged over Black's own tables, not Platinum's / HG's. */
        val gen5: Boolean = false,
        /** Black 2's / White 2's: Black's tables plus the key items they added ([itemNamesBlack2Added]). */
        val bw2: Boolean = false,
    ) {
        fun species(id: Int) = species.getOrNull(id)?.takeIf { it.isNotBlank() }
        fun move(id: Int) = moves.getOrNull(id)?.takeIf { it.isNotBlank() }
        fun item(id: Int) = items.getOrNull(id)?.takeIf { it.isNotBlank() }
        fun place(id: Int) = places.getOrNull(id)?.takeIf { it.isNotBlank() }

        // The app's tables with the cart's words over them (ActiveTables), built once.
        val speciesMerged: Map<Int, String> by lazy {
            (if (gen5) speciesNamesBlack else speciesNamesPlatinum) + species.indices.mapNotNull { id -> species(id)?.takeIf { id > 0 }?.let { id to it } }
        }
        val movesMerged: Map<Int, MoveInfo> by lazy { (if (gen5) moveDataBlack else moveDataPlatinum).mapValues { (id, m) -> move(id)?.let { m.copy(name = it) } ?: m } }
        private val itemsHg by lazy { itemNamesHeartGold + items.indices.mapNotNull { id -> item(id)?.takeIf { id > 0 }?.let { id to it } } }
        private val itemsPt by lazy { (if (bw2) itemNamesBlack + itemNamesBlack2Added else if (gen5) itemNamesBlack else itemNamesPlatinum) + items.indices.mapNotNull { id -> item(id)?.takeIf { id > 0 }?.let { id to it } } }
        fun itemsMerged(heartGold: Boolean) = if (heartGold) itemsHg else itemsPt
    }
    @Volatile var names: Names? = null

    /** In the background, once per cart build (a marker keeps its header's game code, revision and [VERSION]). */
    fun prefetch(filesDir: File, rom: File) {
        names = null
        OriginHeartGold.data = null
        RenegadePlatinum.data = null
        Black2Redux.data = null
        Thread({
            // The names first (a few text banks): the companion's first samples use them.
            runCatching { names = readNames(rom) }.onFailure { android.util.Log.w("pokedaisy", "NdsArt: names failed", it) }
            runCatching { OriginHeartGold.data = if (OriginHeartGold.matches(rom)) readOrigin(rom) else null }
                .onFailure { android.util.Log.w("pokedaisy", "NdsArt: Origin HeartGold's tables failed", it) }
            runCatching { RenegadePlatinum.data = if (RenegadePlatinum.matches(rom)) readRenegade(rom) else null }
                .onFailure { android.util.Log.w("pokedaisy", "NdsArt: Renegade Platinum's tables failed", it) }
            runCatching { Black2Redux.data = if (Black2Redux.matches(rom)) readRedux(rom) else null }
                .onFailure { android.util.Log.w("pokedaisy", "NdsArt: the Redux hacks' tables failed", it) }
            names?.let { n ->
                if (activeGame.isDsGame) {
                    romLanguage = n.language
                    com.pokedaisy.app.companion.i18n.L10n.applyGameLanguage(n.language)
                }
            }
            runCatching {
                PokegearMap.dir = RomArt.dir(filesDir)
                dir = RomArt.dir(filesDir)
                if (extractTo(rom, RomArt.dir(filesDir))) RomArt.announce()
            }.onFailure { android.util.Log.w("pokedaisy", "NdsArt: failed", it) }
        }, "pokedaisy-nds-art").apply { isDaemon = true; start() }
    }

    /** Carts on Platinum's files: USA rev 1, France rev 0, the Platinum language ports. */
    private fun isPlatinum(code: String, rev: Int) =
        code == "CPUE" && rev == 1 || code == "CPUF" && rev == 0 || portIs(code, rev, GameKind.PLATINUM)

    /** Carts on HeartGold / SoulSilver's files: USA, Germany and France, rev 0. */
    private fun isHgss(code: String, rev: Int) =
        code in setOf("IPKE", "IPKD", "IPKF", "IPGE", "IPGD", "IPGF") && rev == 0 || DsPorts.of(code, rev)?.kind.isHgss

    /** A language port running as [kind] ([NDS_PORTS]). */
    private fun portIs(code: String, rev: Int, vararg kind: GameKind) = DsPorts.of(code, rev)?.kind in kind

    /** Writes [rom]'s icons into [dir] if it's a cart this knows and they aren't there yet; true when it wrote any. */
    fun extractTo(rom: File, dir: File): Boolean = RandomAccessFile(rom, "r").use { f ->
        val header = f.read(0, 0x200)
        val code = String(header, 0x0C, 4, Charsets.US_ASCII)
        val rev = header[0x1E].toInt() and 0xFF
        val platinum = isPlatinum(code, rev)
        val heartGold = isHgss(code, rev)
        val white = (code == "IRAO" || code == "IRAF") && rev == 0 || portIs(code, rev, GameKind.WHITE)
        val black = (code == "IRBO" || code == "IRBF") && rev == 0 || portIs(code, rev, GameKind.BLACK) || white
        val white2 = code == "IRDO" && rev == 0 || portIs(code, rev, GameKind.WHITE2)
        val black2 = code == "IREO" && rev == 0 || portIs(code, rev, GameKind.BLACK2) || white2
        val origin = OriginHeartGold.matches { off, len -> f.read(off, len) }
        val renegade = platinum && RenegadePlatinum.matches { off, len -> f.read(off, len) }
        val redux = black2 && Black2Redux.matches { off, len -> f.read(off, len) }
        if (!platinum && !heartGold && !black && !black2 && !origin) return false
        val marker = File(dir, when {
            origin -> "nds/originhg-built"
            renegade -> "nds/renegade-built"
            redux -> "nds/redux-built"
            white2 -> "nds/white2-built"
            black2 -> "nds/black2-built"
            white -> "nds/white-built"
            black -> "nds/black-built"
            platinum -> "nds/platinum-built"
            else -> "nds/heartgold-built"
        })
        // The Redux builds share a code and revision but not their species' records (Classic, EVless): the DEX's types
        // and stats are theirs, so the marker keeps a CRC of those too.
        val stamp = "v$VERSION $code $rev" + if (redux) " " + (file(f, header, "/a/0/1/6")?.let { java.util.zip.CRC32().apply { update(it) }.value } ?: 0L) else ""
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return false
        if (origin) {
            if (!extractOrigin(f, header, dir)) return false
            marker.parentFile?.mkdirs()
            marker.writeText(stamp)
            return true
        }
        if (black || black2) {
            val files = (if (redux) reduxFiles(f, code == Black2Redux.CODE_WHITE2) ?: return false
                else if (black2) GEN5_BLACK2_FILES else if (white) GEN5_WHITE_FILES else GEN5_BLACK_FILES)
                // Japanese Black / White: one bank fewer before the moves, so the categories are 257 (the rest as the US carts').
                .let { if (!black2 && code[3] == 'J') it.copy(category = 257) else it }
            if (!extractGen5(f, header, dir, files)) return false
            marker.parentFile?.mkdirs()
            marker.writeText(stamp)
            return true
        }
        val iconPath = if (platinum) "/poketool/icongra/pl_poke_icon.narc" else "/a/0/2/0"
        // Renegade Platinum's icons take palettes of their own (its ARM9's table, [RenegadePlatinum.ICON_PALETTES_OFFSET]).
        val iconPalettes = if (renegade) f.read(RenegadePlatinum.ICON_PALETTES_OFFSET, RenegadePlatinum.ICON_PALETTES_SIZE)
            else if (platinum) iconPalettesPlatinum else iconPalettesHeartGold
        val iconDir = if (renegade) RENEGADE_ICONS else if (platinum) PLATINUM_ICONS else HEARTGOLD_ICONS
        val narc = Narc(file(f, header, iconPath) ?: return false)
        val palettes = palettes(narc.member(0))
        for (species in 1..EGG) {
            val pal = palettes.getOrNull(iconPalettes.getOrNull(species)?.toInt() ?: 0) ?: continue
            val icon = icon(narc.member(7 + species), pal) ?: continue
            write(dir, "$iconDir/$species.png", icon.png())
        }
        // The bag's item icons: each its own NCGR (32 x 32) and NCLR (one 16-colour palette).
        file(f, header, if (platinum) "/itemtool/itemdata/item_icon.narc" else "/a/0/1/8")?.let { Narc(it) }?.let { items ->
            val itemDir = if (renegade) RENEGADE_ITEMS else if (platinum) PLATINUM_ITEMS else HEARTGOLD_ITEMS
            for ((item, packed) in if (platinum) itemIconsPlatinum else itemIconsHeartGold) {
                val pal = palettes(items.member(packed and 0xFFFF)).firstOrNull() ?: continue
                val icon = icon(items.member(packed ushr 16), pal, height = 32) ?: continue
                write(dir, "$itemDir/$item.png", icon.png())
            }
        }
        if (platinum) {
            file(f, header, "/graphic/tmap_gra.narc")?.let { townMap(Narc(it), dir) }
            dex(f, header, dir, if (renegade) DEX_RENEGADE else DEX_PLATINUM)
        } else {
            file(f, header, "/a/1/4/4")?.let { pokegear(Narc(it), dir) }
            file(f, header, "/a/0/1/5")?.let { bagPockets(Narc(it), dir) }
            val soulSilver = code.startsWith("IPG")
            dex(f, header, dir, when (code[3]) {
                'K' -> if (soulSilver) DEX_SOULSILVER_KO else DEX_HEARTGOLD_KO
                'J' -> if (soulSilver) DEX_SOULSILVER_JA else DEX_HEARTGOLD_JA
                else -> if (soulSilver) DEX_SOULSILVER else DEX_HEARTGOLD
            })
        }
        marker.parentFile?.mkdirs()
        marker.writeText(stamp)
        true
    }

    /**
     * Origin HeartGold's art ([OriginHeartGold]): its party icons in its own archive (`/data/pokeicon.narc`, member 0 the
     * palettes, 7 + 2 x species / form id each icon - GetMonIconNaixEx's sum - and 0xB49 the egg's) in the palette its ARM9's
     * table gives ([OriginHeartGold.ICON_PALETTES_OFFSET]); its item icons (`/a/0/1/8`, 2 x item + 2 the icon, + 3 its
     * palette: no index table, checked on its own items); HeartGold's Pokégear and bag buttons, which it kept. No DEX.
     */
    private fun extractOrigin(f: RandomAccessFile, header: ByteArray, dir: File): Boolean {
        val narc = Narc(file(f, header, "/data/pokeicon.narc") ?: return false)
        val palettes = palettes(narc.member(0))
        val iconPalettes = f.read(OriginHeartGold.ICON_PALETTES_OFFSET, OriginHeartGold.LAST_SPECIES + 1)
        for (species in 1..OriginHeartGold.LAST_SPECIES) {
            val pal = palettes.getOrNull(u8(iconPalettes, species)) ?: continue
            val icon = icon(narc.member(7 + 2 * species), pal) ?: continue
            write(dir, "$ORIGIN_ICONS/$species.png", icon.png())
        }
        palettes.firstOrNull()?.let { pal -> icon(narc.member(0xB49), pal)?.let { write(dir, "$ORIGIN_ICONS/$ORIGIN_EGG.png", it.png()) } }
        file(f, header, "/a/0/1/8")?.let { Narc(it) }?.let { items ->
            for (item in 1 until (items.size - 2) / 2) {
                val pal = palettes(items.member(2 * item + 3)).firstOrNull() ?: continue
                val icon = icon(items.member(2 * item + 2), pal, height = 32) ?: continue
                write(dir, "$ORIGIN_ITEMS/$item.png", icon.png())
            }
        }
        file(f, header, "/a/1/4/4")?.let { pokegear(Narc(it), dir) }
        file(f, header, "/a/0/1/5")?.let { bagPockets(Narc(it), dir) }
        dexOrigin(f, header, dir)
        return true
    }

    /**
     * Origin HeartGold's DEX ([ORIGIN_DEX], [Gen4Dex]'s columns): HeartGold's archives grown to the National Dex's 1025 -
     * the text's banks renumbered (791 descriptions, 803 categories, 711 abilities), heights / weights in `/a/0/7/4`
     * (members 0 / 1), its 52-byte personal records (abilities u16 at 0x16 / 0x18, a hidden one at 0x1A), evolutions of up
     * to ten 6-byte entries (`/a/0/3/4`, [Gen4DexTables.origin]'s methods), level-up moves as { u16 level, u16 move } to
     * 0xFFFF (`/a/0/3/3`). A machine fits as its own rule has it (its party menu's check): its move in the species' list in
     * `/data/tutor_moves.narc` or its level-up moves, RETURN anyone, MEW every one; the bits TM01 .. TM130 then HM01 ..
     * HM08 ([OriginHeartGold.Data.dexMachines]). Its one dex is the national one (no regional number). Fronts: its own
     * `/data/pokepic_m.narc`, eight members a species, the front first (160 x 80, linear, unscrambled: both frames) and its
     * palette next.
     */
    private fun dexOrigin(f: RandomAccessFile, header: ByteArray, dir: File) {
        val msg = Narc(file(f, header, "/a/0/2/7") ?: return)
        val text = msg.member(791)?.let { Gen4Text.lines(it) } ?: return
        val category = msg.member(803)?.let { Gen4Text.lines(it) } ?: return
        val abilities = msg.member(711)?.let { Gen4Text.lines(it) } ?: emptyList()
        val zukan = Narc(file(f, header, "/a/0/7/4") ?: return)
        val heights = zukan.member(0) ?: return
        val weights = zukan.member(1) ?: return
        val personal = Narc(file(f, header, "/a/0/0/2") ?: return)
        val learnsets = Narc(file(f, header, "/a/0/3/3") ?: return)
        val evolutions = Narc(file(f, header, "/a/0/3/4") ?: return)
        val tutor = Narc(file(f, header, "/data/tutor_moves.narc") ?: return)
        val machineMoves = OriginHeartGold.dexMachines(readMachines(f))
        val sb = StringBuilder()
        for (species in 1..OriginHeartGold.LAST_NATIONAL) {
            val p = personal.member(species) ?: continue
            if (p.size < 0x1C) continue
            fun b(o: Int) = u8(p, o)
            fun w(arr: ByteArray, i: Int) = if (i * 4 + 4 <= arr.size) u32(arr, i * 4).toInt() else 0
            fun ability(o: Int) = u16(p, o).let { if (it != 0) abilities.getOrElse(it) { "" } else "" }
            val levelUp = learnsets.member(species)?.let { l ->
                (0 until l.size / 4).asSequence().map { u16(l, it * 4) to u16(l, it * 4 + 2) }.takeWhile { it.first != 0xFFFF }.toList()
            }.orEmpty()
            val teachable = HashSet<Int>()
            tutor.member(species)?.let { t -> for (i in 0 until t.size / 2) u16(t, i * 2).takeIf { it != 0xFFFF }?.let(teachable::add) }
            levelUp.forEach { teachable += it.second }
            val bits = ByteArray((machineMoves.size + 7) / 8)
            for ((i, move) in machineMoves.withIndex()) {
                if (move != 0 && (species == MEW || move == MOVE_RETURN || move in teachable)) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
            val cells = listOf(
                species.toString(),
                category.getOrElse(species) { "" },
                text.getOrElse(species) { "" }.replace("\n", "\\n"),
                w(heights, species).toString(),
                w(weights, species).toString(),
                b(0).toString(), b(1).toString(), b(2).toString(), b(4).toString(), b(5).toString(), b(3).toString(),
                b(6).toString(), b(7).toString(), b(8).toString(), b(0x10).toString(), b(0x14).toString(), b(0x15).toString(),
                ability(0x16), ability(0x18),
                species.toString(),
                evolutions.member(species)?.let { e ->
                    (0 until e.size / 6).mapNotNull { i ->
                        val m = u16(e, i * 6)
                        if (m == 0) null else "$m,${u16(e, i * 6 + 2)},${u16(e, i * 6 + 4)}"
                    }.joinToString(";")
                } ?: "",
                levelUp.joinToString(",") { "${it.first}:${it.second}" },
                bits.joinToString("") { "%02x".format(it) },
                ability(0x1A),
            )
            sb.append(cells.joinToString("\t") { it.replace("\t", " ") }).append('\n')
        }
        write(dir, ORIGIN_DEX, sb.toString().toByteArray())
        val pics = Narc(file(f, header, "/data/pokepic_m.narc") ?: return)
        for (species in 1..OriginHeartGold.LAST_NATIONAL) {
            val pal = palettes(pics.member(species * 8 + 1)).firstOrNull() ?: continue
            val px = ncgrData(pics.member(species * 8))?.takeIf { it.size >= 6400 } ?: continue
            val img = RomArt.Image(80, 80)
            for (y in 0 until 80) for (x in 0 until 80) {
                val v = u8(px, y * 80 + x / 2).let { if (x and 1 == 1) it shr 4 else it and 15 }
                if (v != 0) img.argb[y * 80 + x] = pal[v]
            }
            write(dir, "$ORIGIN_FRONT/$species.png", img.png())
        }
    }

    /** Origin HeartGold's machine moves from its ARM9 ([OriginHeartGold.Data.machines]' order). */
    private fun readMachines(f: RandomAccessFile): IntArray {
        val tm93 = f.read(OriginHeartGold.TM93_MOVES_OFFSET, 38 * 2)
        val tm01 = f.read(OriginHeartGold.TM_MOVES_OFFSET, 100 * 2)
        return IntArray(138) { if (it < 100) u16(tm01, it * 2) else u16(tm93, (it - 100) * 2) }
    }

    private const val MEW = 151
    private const val MOVE_RETURN = 216

    /**
     * Origin HeartGold's tables from its cart ([OriginHeartGold.Data]): each species / form's types (its 52-byte personal
     * records, `/a/0/0/2`, types at 6 and 7 as HeartGold's), each move's type and power (its own `/extra/new_move_data.narc`,
     * 40 bytes a move: type 0, category 2, power 3) under the cart's move names, the type chart, the forms and the machines.
     */
    fun readOrigin(rom: File): OriginHeartGold.Data? = RandomAccessFile(rom, "r").use { f ->
        val header = f.read(0, 0x200)
        val personal = Narc(file(f, header, "/a/0/0/2") ?: return null)
        val types = HashMap<Int, SpeciesTypes>()
        for (id in 1..OriginHeartGold.LAST_SPECIES) {
            val p = personal.member(id) ?: continue
            if (p.size >= 8) types[id] = SpeciesTypes(u8(p, 6), u8(p, 7))
        }
        val moveNames = readNames(rom)?.moves.orEmpty()
        val moveRecords = Narc(file(f, header, "/extra/new_move_data.narc") ?: return null)
        val moves = HashMap<Int, MoveInfo>()
        for (id in 1 until moveRecords.size) {
            val m = moveRecords.member(id) ?: continue
            val name = moveNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: continue
            if (m.size >= 4) moves[id] = MoveInfo(name, u8(m, 0), u8(m, 3))
        }
        val chart = HashMap<Int, Int>()
        val c = f.read(OriginHeartGold.TYPE_CHART_OFFSET, 18 * 18)
        for (atk in 0 until 18) for (def in 0 until 18) {
            val pct = u8(c, atk * 18 + def) * 25
            if (pct != 100) chart[atk * 100 + def] = pct
        }
        val forms = HashMap<Long, Int>()
        val t = f.read(OriginHeartGold.FORMS_OFFSET, OriginHeartGold.FORM_COUNT * 6)
        for (i in 0 until OriginHeartGold.FORM_COUNT) {
            val species = u16(t, i * 6)
            val target = u16(t, i * 6 + 2)
            val form = u8(t, i * 6 + 4)
            if (species != 0 && target != 0) forms[(species.toLong() shl 8) or form.toLong()] = target
        }
        val machines = readMachines(f)
        // Each map header's encounter bank (0xFF = none) into HeartGold's encounter archive: its first two bytes the
        // walking and surfing rates (what the field read holds at 0 for WILD ENCOUNTERS OFF).
        val headers = f.read(OriginHeartGold.MAP_HEADERS_OFFSET, OriginHeartGold.MAP_HEADER_COUNT * 24)
        val encounters = file(f, header, "/a/0/3/7")?.let { Narc(it) }
        val rates = ArrayList<Int>()
        for (map in 0 until OriginHeartGold.MAP_HEADER_COUNT) {
            val bank = u8(headers, map * 24)
            val e = if (bank == 0xFF) null else encounters?.member(bank)
            if (e != null && e.size >= 2) rates += listOf(map, u8(e, 0), u8(e, 1))
        }
        OriginHeartGold.Data(types, moves, chart, forms, machines, rates.toIntArray())
    }

    /**
     * Renegade Platinum's tables from its cart ([RenegadePlatinum.Data]): each species' types (Platinum's 44-byte personal
     * records, types at 6 and 7), each move's category, power and type (`pl_waza_tbl.narc`, 16 bytes a move: 2, 3, 4) under
     * the cart's move names, its type chart (overlay 21's triples), its machines, its icon palettes and each map's wild
     * encounter rates (the map header's set in `pl_enc_data.narc`: the walking rate a u32 at 0, the surfing rate at 0xCC).
     */
    fun readRenegade(rom: File): RenegadePlatinum.Data? = RandomAccessFile(rom, "r").use { f ->
        val header = f.read(0, 0x200)
        val personal = Narc(file(f, header, "/poketool/personal/pl_personal.narc") ?: return null)
        val types = HashMap<Int, SpeciesTypes>()
        for (id in 1 until personal.size) {
            val p = personal.member(id) ?: continue
            if (p.size >= 8) types[id] = SpeciesTypes(u8(p, 6), u8(p, 7))
        }
        val moveNames = readNames(rom)?.moves.orEmpty()
        val moveRecords = Narc(file(f, header, "/poketool/waza/pl_waza_tbl.narc") ?: return null)
        val moves = HashMap<Int, MoveInfo>()
        val categories = HashMap<Int, Int>()
        for (id in 1 until moveRecords.size) {
            val m = moveRecords.member(id) ?: continue
            if (m.size < 5) continue
            val name = moveNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: moveDataPlatinum[id]?.name ?: continue
            moves[id] = MoveInfo(name, u8(m, 4), u8(m, 3))
            categories[id] = u8(m, 2)
        }
        val chart = HashMap<Int, Int>()
        val c = f.read(RenegadePlatinum.TYPE_CHART_OFFSET, 0x400)
        var i = 0
        while (i + 3 <= c.size && u8(c, i) != 0xFF) {
            val atk = u8(c, i)
            val def = u8(c, i + 1)
            val pct = u8(c, i + 2) * 10
            if (atk != 0xFE && pct != 100) chart[atk * 100 + def] = pct
            i += 3
        }
        val tms = f.read(RenegadePlatinum.TM_MOVES_OFFSET, 100 * 2)
        val machines = IntArray(100) { u16(tms, it * 2) }
        val iconPalettes = f.read(RenegadePlatinum.ICON_PALETTES_OFFSET, RenegadePlatinum.ICON_PALETTES_SIZE)
        val headers = f.read(RenegadePlatinum.MAP_HEADERS_OFFSET, RenegadePlatinum.MAP_HEADER_COUNT * 24)
        val encounters = file(f, header, "/fielddata/encountdata/pl_enc_data.narc")?.let { Narc(it) }
        val rates = ArrayList<Int>()
        for (map in 0 until RenegadePlatinum.MAP_HEADER_COUNT) {
            val set = u16(headers, map * 24 + 0x0E)
            val e = if (set == 0xFFFF) null else encounters?.member(set)
            if (e != null && e.size >= 0xD0) rates += listOf(map, u32(e, 0).toInt(), u32(e, 0xCC).toInt())
        }
        RenegadePlatinum.Data(types, moves, categories, chart, machines, iconPalettes, rates.toIntArray())
    }

    /**
     * The Redux hacks' tables from their cart ([Black2Redux.Data]): each species' types (Black 2's 76-byte personal
     * records, types at 6 and 7), each move's type, category and power (`/a/0/2/1`, 36 bytes a move: 0, 2, 3) under the
     * cart's move names, the type chart (overlay 167's first 18 x 18 bytes), the machines, the party icon palettes, the
     * item icons and the zones' wild encounter rates (a zone's set, bits 0-12 of its +0x14, in `/a/1/2/7`: one 0xE8-byte
     * block, or four for the seasons, its first 7 bytes the rates).
     */
    fun readRedux(rom: File): Black2Redux.Data? = RandomAccessFile(rom, "r").use { f ->
        val header = f.read(0, 0x200)
        val white2 = Black2Redux.which { off, len -> f.read(off, len) }?.let { it == Black2Redux.CODE_WHITE2 } ?: return null
        val personal = Narc(file(f, header, "/a/0/1/6") ?: return null)
        val types = HashMap<Int, SpeciesTypes>()
        for (id in 1..BLACK_EGG - 1) {
            val p = personal.member(id) ?: continue
            if (p.size >= 8) types[id] = SpeciesTypes(Black2Redux.type(u8(p, 6)), Black2Redux.type(u8(p, 7)))
        }
        val moveNames = readNames(rom)?.moves.orEmpty()
        val moveRecords = Narc(file(f, header, "/a/0/2/1") ?: return null)
        val moves = HashMap<Int, MoveInfo>()
        val categories = HashMap<Int, Int>()
        for (id in 1 until moveRecords.size) {
            val m = moveRecords.member(id) ?: continue
            if (m.size < 4) continue
            val name = moveNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: moveDataBlack[id]?.name ?: continue
            moves[id] = MoveInfo(name, Black2Redux.type(u8(m, 0)), u8(m, 3))
            // Gen 5 keeps 0 status, 1 physical, 2 special; the app's (Gen 4's) order is physical, special, status.
            categories[id] = when (u8(m, 2)) { 1 -> 0; 2 -> 1; else -> 2 }
        }
        val overlay167 = overlay(f, header, 167) ?: return null
        val n = Black2Redux.CHART_TYPES
        if (overlay167.size < n * n) return null
        val chart = HashMap<Int, Int>()
        for (atk in 0 until n) for (def in 0 until n) {
            val pct = u8(overlay167, atk * n + def) * 25
            if (pct != 100) chart[Black2Redux.type(atk) * 100 + Black2Redux.type(def)] = pct
        }
        val tms = f.read(Black2Redux.tmMovesOffset(white2), Black2Redux.TM_MOVE_COUNT * 2)
        val machines = IntArray(Black2Redux.TM_MOVE_COUNT) { u16(tms, it * 2) }
        val iconPalettes = readReduxIconPalettes(f, white2)
        val itemIcons = readReduxItemIcons(f, white2)
        val zones = file(f, header, "/a/0/1/2")?.let { Narc(it).member(0) } ?: ByteArray(0)
        val encounters = file(f, header, "/a/1/2/7")?.let { Narc(it) }
        val seasons = List(4) { ArrayList<Int>() }
        for (zone in 0 until zones.size / 48) {
            val set = u16(zones, zone * 48 + 0x14) and 0x1FFF
            val e = if (set == 0x1FFF) null else encounters?.member(set) ?: continue
            if (e == null || e.size < 0xE8) continue
            for (season in 0 until 4) {
                val b = if (e.size >= 0xE8 * 4) season * 0xE8 else 0
                fun r(i: Int) = u8(e, b + i)
                seasons[season] += listOf(zone, r(0) or (r(1) shl 8) or (r(2) shl 16) or (r(3) shl 24), r(4) or (r(5) shl 8) or (r(6) shl 16))
            }
        }
        Black2Redux.Data(types, moves, categories, chart, machines, iconPalettes, itemIcons, seasons.map { it.toIntArray() })
    }

    /** ARM9 overlay [id] of the cart, unpacked (the overlay table at the header's 0x50; its file in the FAT). */
    private fun overlay(f: RandomAccessFile, header: ByteArray, id: Int): ByteArray? {
        val table = u32(header, 0x50)
        if (id * 32 + 32 > u32(header, 0x54)) return null
        val entry = f.read(table + id * 32L, 32)
        val fileId = u32(entry, 0x18)
        val packed = u8(entry, 0x1F) and 1 != 0
        val fat = f.read(u32(header, 0x48) + fileId * 8, 8)
        val start = u32(fat, 0)
        val end = u32(fat, 4)
        if (end <= start || end - start > 4L shl 20) return null
        val raw = f.read(start, (end - start).toInt())
        return if (packed) Gen5Lz.blz(raw) else raw
    }

    /**
     * Platinum's Town Map (town_map/main.c, graphics.c), as its top screen draws it: the region background and the map
     * (two tilemaps over one 4bpp tile set and palette) into regionmap/[PLATINUM_MAP] (cropped to [PLATINUM_MAP_CROP]),
     * each fly location's 16x16 block sprite in its three palettes (5 not visited yet, 6 a town's blue, 7 a city's red:
     * fly_locations.c's `5 + palette + isUnlocked`) and the player's two heads (Lucas, Dawn), each in its own palette.
     */
    private fun townMap(narc: Narc, dir: File) {
        val mapPal = palettes(narc.member(0)).flatMap { it.toList() }.toIntArray()
        val sprPal = palettes(narc.member(2))
        val tiles = ncgrData(narc.member(19)) ?: return
        val img = RomArt.Image(256, 192)
        img.argb.fill(mapPal.getOrElse(0) { 0xFF000000.toInt() })
        for (screen in intArrayOf(22, 24)) drawScreen(img, narc.member(screen) ?: return, tiles, mapPal)   // MAIN_3, then MAIN_2
        val (l, t, r, b) = PLATINUM_MAP_CROP.toList()
        val crop = RomArt.Image(r - l, b - t)
        for (y in 0 until crop.height) System.arraycopy(img.argb, (t + y) * 256 + l, crop.argb, y * crop.width, crop.width)
        write(dir, "regionmap/$PLATINUM_MAP.png", crop.png())
        val blockTiles = ncgrData(narc.member(16)) ?: return
        val blocks = ncerCells(narc.member(17) ?: return)
        for ((shape, cell) in blocks.withIndex()) {
            for ((variant, pal) in intArrayOf(5, 6, 7).withIndex()) {
                val sprite = RomArt.Image(16, 16)
                drawCell(sprite, 8, 8, cell, blockTiles, sprPal.getOrNull(pal) ?: continue)
                write(dir, "regionmap/${platinumBlock(shape, variant)}.png", sprite.png())
            }
        }
        val headTiles = ncgrData(narc.member(10)) ?: return
        val heads = ncerCells(narc.member(11) ?: return)
        for ((i, name) in listOf(PLATINUM_HEAD_M, PLATINUM_HEAD_F).withIndex()) {
            val cell = heads.getOrNull(i) ?: continue
            val sprite = RomArt.Image(16, 16)
            drawCell(sprite, 8, 8, cell, headTiles, sprPal.getOrNull(cell.firstOrNull()?.pal ?: 0) ?: continue)
            write(dir, "regionmap/$name.png", sprite.png())
        }
    }

    /**
     * Where a Gen 5 cart keeps what [extractGen5] reads, and where it goes. Black and White share their art (White writes
     * Black's paths) but for the DEX's descriptions; Black 2 and White 2 share theirs whole.
     */
    private data class Gen5Files(
        val icons: String, val items: String, val dex: String, val front: String,
        val iconPalettes: ByteArray, val itemIcons: Map<Int, Int>,
        /** The Town Map's NARC; its picture, the sprite each kind of place gets ([townCells]: kind -> cells), the player
         * and the cursor's frames, under rom-art/regionmap. */
        val townMap: String, val map: String, val town: (Int) -> String, val player: String, val cursor: (Int) -> String,
        val townCells: List<Pair<Int, IntArray>>,
        /** Text banks (a/0/0/2): the DEX descriptions, the categories, the abilities; the regional dex (a/0/1/6's member). */
        val description: Int, val category: Int, val abilities: Int, val regional: Int,
        /** The party icon archive's first icon (a/0/0/7: Black's 7, after its palette and three cell / animation pairs;
         * Black 2's 8, after a second palette). */
        val iconBase: Int = 7,
    )

    private val GEN5_BLACK_FILES = Gen5Files(
        BLACK_ICONS, BLACK_ITEMS, BLACK_DEX, BLACK_FRONT, iconPalettesBlack, itemIconsBlack,
        "/a/0/8/5", BLACK_MAP, ::blackTown, BLACK_PLAYER, ::blackCursor,
        listOf(0 to intArrayOf(4), 3 to intArrayOf(19), 4 to intArrayOf(20), 5 to intArrayOf(23, 19)),
        235, 260, 182, 668,
    )

    /** White's cart: Black's art, its own descriptions (bank 236; both carts hold both). */
    private val GEN5_WHITE_FILES = Gen5Files(
        BLACK_ICONS, BLACK_ITEMS, WHITE_DEX, BLACK_FRONT, iconPalettesBlack, itemIconsBlack,
        "/a/0/8/5", BLACK_MAP, ::blackTown, BLACK_PLAYER, ::blackCursor,
        listOf(0 to intArrayOf(4), 3 to intArrayOf(19), 4 to intArrayOf(20), 5 to intArrayOf(23, 19)),
        236, 260, 182, 668,
    )

    /** Black 2's (and White 2's) cart: the same archives renumbered - the Town Map a/0/8/4, the text banks 442 / 464 /
     * 374, the Unova Dex a/0/1/6 member 709. */
    private val GEN5_BLACK2_FILES = Gen5Files(
        BLACK2_ICONS, BLACK2_ITEMS, BLACK2_DEX, BLACK2_FRONT, iconPalettesBlack2, itemIconsBlack2,
        "/a/0/8/4", BLACK2_MAP, ::black2Town, BLACK2_PLAYER, ::black2Cursor,
        // the new places FLY reaches (kind 3) get the towns' dot, a place the story shows (kind 4) the green mark
        listOf(0 to intArrayOf(4), 3 to intArrayOf(4), 4 to intArrayOf(20), 5 to intArrayOf(23, 19)),
        442, 464, 374, 709,
        iconBase = 8,
    )

    /** The Redux hacks' ([Black2Redux]): Black 2's archives, their own icon palettes and item icon table (read from
     * the cart's ARM9), written apart from a retail Black 2's. */
    private fun reduxFiles(f: RandomAccessFile, white2: Boolean): Gen5Files? {
        val palettes = readReduxIconPalettes(f, white2)
        return GEN5_BLACK2_FILES.copy(
            icons = REDUX_ICONS, items = REDUX_ITEMS, dex = REDUX_DEX, front = REDUX_FRONT,
            iconPalettes = palettes, itemIcons = readReduxItemIcons(f, white2),
        )
    }

    /** The Redux hacks' party icon palettes: their ARM9's byte an icon entry, its low nibble the icon's own (the high
     * nibble its female icon's), as [iconPalettesBlack2] keeps them. */
    private fun readReduxIconPalettes(f: RandomAccessFile, white2: Boolean): ByteArray =
        f.read(Black2Redux.iconPalettesOffset(white2), Black2Redux.ICON_PALETTES_SIZE).map { (it.toInt() and 0xF).toByte() }.toByteArray()

    /** The Redux hacks' item icons: their ARM9's (graphics, palette) pairs for the items Black 2 has icons for. */
    private fun readReduxItemIcons(f: RandomAccessFile, white2: Boolean): Map<Int, Int> {
        val last = itemIconsBlack2.keys.max()
        val t = f.read(Black2Redux.itemIconsOffset(white2), (last + 1) * 4)
        return itemIconsBlack2.keys.associateWith { (u16(t, it * 4) shl 16) or u16(t, it * 4 + 2) }
    }

    /**
     * A Gen 5 cart's art and DEX ([files]). The party icons are a/0/0/7: member 0 the palette file (the same three
     * 16-colour palettes as Gen 4's), member 7 + 2 x n icon n (32 x 64, two frames, as Gen 4's; 650 the egg's), 8 + 2 x n
     * its female one (empty where there's none); which palette is [Gen5Files.iconPalettes] (the cart's ARM9 table, a byte
     * an icon: low nibble the icon's, high nibble its female icon's). The bag's icons are a/0/2/5's NCGR / NCLR members by
     * [Gen5Files.itemIcons] (the ARM9's pairs by item id). Both generated from the cart, NdsArtBlackTest checks them
     * against it. Then the Town Map ([townMapBlack]) and the DEX ([dexBlack]).
     */
    private fun extractGen5(f: RandomAccessFile, header: ByteArray, dir: File, files: Gen5Files): Boolean {
        val narc = Narc(file(f, header, "/a/0/0/7") ?: return false)
        val palettes = palettes(narc.member(0))
        for (species in 1..BLACK_EGG) {
            val pal = palettes.getOrNull(files.iconPalettes.getOrNull(species)?.toInt() ?: 0) ?: continue
            val icon = icon(narc.member(files.iconBase + 2 * species), pal) ?: continue
            write(dir, "${files.icons}/$species.png", icon.png())
        }
        file(f, header, "/a/0/2/5")?.let { Narc(it) }?.let { items ->
            for ((item, packed) in files.itemIcons) {
                val pal = palettes(items.member(packed and 0xFFFF)).firstOrNull() ?: continue
                val icon = icon(items.member(packed ushr 16), pal, height = 32) ?: continue
                write(dir, "${files.items}/$item.png", icon.png())
            }
        }
        file(f, header, files.townMap)?.let { townMapBlack(Narc(it), dir, files) }
        dexBlack(f, header, dir, files)
        return true
    }

    /**
     * Black's Town Map (the key item's, on the touch screen), from a/0/8/5. The map is two 8bpp BGs over one palette file
     * (member 0: 16 extended palettes of 256 colours, the tilemaps' palette bits pick one): the aerial picture (screen 16
     * over tiles 7) and the routes and places drawn on it (screen 17 over tiles 5), 256 x 168 (the bar under it cut off).
     * Its sprites are member 12's cells over tiles 8 (4bpp, 128-byte tile numbers) in member 2's palettes, each centred
     * on a place's point: a town arrived at gets cell 4 (its blue middle) over the BG's grey one, Victory Road cell 19
     * (the small one), Unity Tower its ring (23) and middle (19), Liberty Garden its island's dot (20); the player is the
     * figure (8) with the red arrow (7) on top, the cursor ring the pulse's cells 11 and 12. Checked against the game's
     * own Town Map headless (the user's save, arrival flags poked off and on): the picture pixel for pixel, each sprite
     * where the game puts it.
     */
    private fun townMapBlack(narc: Narc, dir: File, files: Gen5Files) {
        val bgPal = palettes(narc.member(0)).flatMap { it.toList() }.toIntArray()
        val img = RomArt.Image(256, 168)
        img.argb.fill(bgPal.getOrElse(0) { 0xFF000000.toInt() })
        for ((screen, tiles) in listOf(16 to 7, 17 to 5)) {
            drawScreen8(img, narc.member(screen) ?: return, ncgrData(narc.member(tiles)) ?: return, bgPal)
        }
        write(dir, "regionmap/${files.map}.png", img.png())
        val tiles = ncgrData(narc.member(8)) ?: return
        val cells = ncerCells(narc.member(12) ?: return, useMapping = true)
        val pals = palettes(narc.member(2))
        fun sprite(size: Int, vararg cellIds: Int): RomArt.Image? {
            val out = RomArt.Image(size, size)
            for (id in cellIds) {
                val cell = cells.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: return null
                drawCell(out, size / 2, size / 2, cell, tiles, pals.getOrNull(cell.first().pal) ?: return null)
            }
            return out
        }
        for ((kind, ids) in files.townCells) {
            sprite(16, *ids)?.let { write(dir, "regionmap/${files.town(kind)}.png", it.png()) }
        }
        sprite(48, 8, 7)?.let { write(dir, "regionmap/${files.player}.png", it.png()) }
        for ((frame, id) in listOf(0 to 11, 1 to 12)) sprite(32, id)?.let { write(dir, "regionmap/${files.cursor(frame)}.png", it.png()) }
    }

    /** An 8bpp NSCR over [tiles] (64 bytes a tile), the entry's palette bits picking a 256-colour palette of [pal];
     * colour 0 clear. */
    private fun drawScreen8(img: RomArt.Image, nscr: ByteArray, tiles: ByteArray, pal: IntArray) {
        val o = indexOf(nscr, "NRCS") ?: return
        val cols = u16(nscr, o + 8) / 8
        val rows = u16(nscr, o + 0xA) / 8
        for (ty in 0 until minOf(rows, (img.height + 7) / 8)) for (tx in 0 until minOf(cols, img.width / 8)) {
            val e = u16(nscr, o + 0x14 + (ty * cols + tx) * 2)
            val tile = e and 0x3FF
            val hf = e and 0x400 != 0
            val vf = e and 0x800 != 0
            val p = e shr 12
            for (y in 0 until 8) for (x in 0 until 8) {
                val py = ty * 8 + y
                if (py >= img.height) continue
                val i = tile * 64 + (if (vf) 7 - y else y) * 8 + (if (hf) 7 - x else x)
                val v = if (i in tiles.indices) u8(tiles, i) else 0
                if (v == 0) continue
                pal.getOrNull(p * 256 + v)?.let { img.argb[py * img.width + tx * 8 + x] = it }
            }
        }
    }

    /**
     * Black's DEX pages ([BLACK_DEX], the columns [dex] writes, plus a 24th: the hidden ability) and front pictures
     * ([BLACK_FRONT]), all from the cart ([files]: White's pages [WHITE_DEX] from its own descriptions, Black 2's and
     * White 2's [BLACK2_DEX] / [BLACK2_FRONT] from their banks and members):
     * - text (a/0/0/2, [Gen5Text]): 235 the descriptions (White's are 236; both carts hold both), 260 categories, 182
     *   ability names (Black 2 / White 2: 442, 464, 374);
     * - the personal record (a/0/1/6 by species, 0x3C bytes; Black 2's 0x4C, the same up to 0x3C): base stats (HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF),
     *   types (Gen 5's ids: no ??? type, FIRE is 9), catch rate, gender ratio 0x12, egg groups 0x16, abilities 0x18-0x1A
     *   (the third the hidden one), height (dm) 0x24 and weight (hg) 0x26, and the TM / HM bits from 0x28 (TM01 .. TM95,
     *   HM01 .. HM06, low bit first);
     * - the Unova Dex: a/0/1/6's member 668 (Black 2's 709), a u16 per species, its number there (VICTINI 0) or 999; written as its
     *   place in the list (VICTINI 1), 0 when it's not in it;
     * - evolutions (a/0/1/9 by species: 7 x {method, param, target}, Gen 5's methods - [Gen4Dex] reads them) and level-up
     *   moves (a/0/1/8: {move, level} u16 pairs, ending 0xFFFF). Method 7 (a trade with another Pokémon) keeps 0 as its
     *   param in the cart; the two species that use it trade with each other, so each is written as the other's;
     * - the front picture: a/0/0/4 member species x 20 (+ 1 the female one where there's no other), LZ11-packed, 96 x 96
     *   in 8 x 8 tiles laid out as four OAM blocks (64 x 64, 32 x 64 right of it, 64 x 32 and 32 x 32 under them); its
     *   palette member species x 20 + 18 (+ 19 the shiny one).
     */
    private fun dexBlack(f: RandomAccessFile, header: ByteArray, dir: File, files: Gen5Files) {
        val msg = Narc(file(f, header, "/a/0/0/2") ?: return)
        val text = msg.member(files.description)?.let { Gen5Text.lines(it) } ?: return
        val category = msg.member(files.category)?.let { Gen5Text.lines(it) } ?: return
        val abilities = msg.member(files.abilities)?.let { Gen5Text.lines(it) } ?: emptyList()
        val personal = Narc(file(f, header, "/a/0/1/6") ?: return)
        val unova = personal.member(files.regional)?.takeIf { it.size >= 650 * 2 } ?: return
        val learnsets = Narc(file(f, header, "/a/0/1/8") ?: return)
        val evolutions = Narc(file(f, header, "/a/0/1/9") ?: return)
        val evos = (0..649).map { species ->
            val e = evolutions.member(species) ?: return@map emptyList<IntArray>()
            (0 until 7).mapNotNull { i ->
                if (i * 6 + 6 > e.size) return@mapNotNull null
                val m = u16(e, i * 6)
                if (m == 0) null else intArrayOf(m, u16(e, i * 6 + 2), u16(e, i * 6 + 4))
            }
        }
        val tradePair = evos.indices.filter { s -> evos[s].any { it[0] == 7 } }.takeIf { it.size == 2 }
        val sb = StringBuilder()
        for (species in 1..649) {
            val p = personal.member(species) ?: continue
            if (p.size < 0x38) continue
            fun b(o: Int) = u8(p, o)
            fun ability(o: Int) = if (b(o) != 0) abilities.getOrElse(b(o)) { "" } else ""
            val regional = u16(unova, species * 2).let { if (it == 999) 0 else it + 1 }
            val cells = listOf(
                species.toString(),
                category.getOrElse(species) { "" },
                text.getOrElse(species) { "" }.replace("\n", "\\n"),
                u16(p, 0x24).toString(),
                u16(p, 0x26).toString(),
                b(0).toString(), b(1).toString(), b(2).toString(), b(4).toString(), b(5).toString(), b(3).toString(),
                b(6).toString(), b(7).toString(), b(8).toString(), b(0x12).toString(), b(0x16).toString(), b(0x17).toString(),
                ability(0x18), ability(0x19),
                regional.toString(),
                evos[species].joinToString(";") { (m, param, to) ->
                    val partner = if (m == 7 && tradePair != null) tradePair.first { it != species } else param
                    "$m,$partner,$to"
                },
                learnsets.member(species)?.let { l ->
                    (0 until l.size / 4).asSequence().map { u16(l, it * 4) to u16(l, it * 4 + 2) }
                        .takeWhile { it.first != 0xFFFF }.joinToString(",") { (move, level) -> "$level:$move" }
                } ?: "",
                (0x28 until 0x38).joinToString("") { "%02x".format(b(it)) },
                ability(0x1A),
            )
            sb.append(cells.joinToString("\t") { it.replace("\t", " ") }).append('\n')
        }
        write(dir, files.dex, sb.toString().toByteArray())
        val pics = Narc(file(f, header, "/a/0/0/4") ?: return)
        for (species in 1..649) {
            val pal = palettes(pics.member(species * 20 + 18)).firstOrNull() ?: continue
            val chars = (0..1).firstNotNullOfOrNull { k ->
                pics.member(species * 20 + k)?.takeIf { it.isNotEmpty() }?.let(Gen5Lz::decompress)?.let(::ncgrData)
                    ?.takeIf { it.size >= 96 * 96 / 2 }
            } ?: continue
            val img = RomArt.Image(96, 96)
            var tile = 0
            for ((bx, by, bw, bh) in BLACK_FRONT_BLOCKS) {
                val across = bw / 8
                for (t in 0 until bw * bh / 64) {
                    for (y in 0 until 8) for (x in 0 until 8) {
                        val v = tilePixel(chars, tile + t, x, y)
                        if (v != 0) img.argb[(by + (t / across) * 8 + y) * 96 + bx + (t % across) * 8 + x] = pal[v]
                    }
                }
                tile += bw * bh / 64
            }
            write(dir, "${files.front}/$species.png", img.png())
        }
    }

    /** A Gen 5 front picture's OAM blocks in the order its tiles come: x, y, width, height. */
    private val BLACK_FRONT_BLOCKS = listOf(listOf(0, 0, 64, 64), listOf(64, 0, 32, 64), listOf(0, 64, 64, 32), listOf(64, 64, 32, 32))

    /** An NCGR's tile bytes (its RAHC block's data). */
    private fun ncgrData(ncgr: ByteArray?): ByteArray? {
        ncgr ?: return null
        val o = indexOf(ncgr, "RAHC") ?: return null
        val size = u32(ncgr, o + 0x18).toInt()
        val off = o + 8 + u32(ncgr, o + 0x1C).toInt()
        return ncgr.copyOfRange(off, minOf(ncgr.size, off + size))
    }

    /** One 4bpp 8x8 tile's pixel ([x], [y]) colour index. */
    private fun tilePixel(tiles: ByteArray, tile: Int, x: Int, y: Int): Int {
        val i = tile * 32 + y * 4 + x / 2
        if (i !in tiles.indices) return 0
        val b = u8(tiles, i)
        return if (x and 1 == 1) b shr 4 else b and 15
    }

    /** An NSCR (text BG: tile, h / v flip, palette per entry) over [tiles], colour 0 clear, onto the top-left of [img]. */
    private fun drawScreen(img: RomArt.Image, nscr: ByteArray, tiles: ByteArray, pal: IntArray) {
        val o = indexOf(nscr, "NRCS") ?: return
        val cols = u16(nscr, o + 8) / 8
        val rows = u16(nscr, o + 0xA) / 8
        for (ty in 0 until minOf(rows, img.height / 8)) for (tx in 0 until minOf(cols, img.width / 8)) {
            val e = u16(nscr, o + 0x14 + (ty * cols + tx) * 2)
            val tile = e and 0x3FF
            val hf = e and 0x400 != 0
            val vf = e and 0x800 != 0
            val p = e shr 12
            for (y in 0 until 8) for (x in 0 until 8) {
                val v = tilePixel(tiles, tile, if (hf) 7 - x else x, if (vf) 7 - y else y)
                if (v == 0) continue
                pal.getOrNull(p * 16 + v)?.let { img.argb[(ty * 8 + y) * img.width + tx * 8 + x] = it }
            }
        }
    }

    /** One OAM of an NCER cell: its offset from the sprite's centre, size, first tile, palette and flips. */
    private class Oam(val x: Int, val y: Int, val w: Int, val h: Int, val tile: Int, val pal: Int, val hf: Boolean, val vf: Boolean)

    /** An NCER's cells (KBEC), each a list of OAMs; tile numbers in 32-byte units (1D mapping, the boundary these use),
     * or with [useMapping] in the units its mapping mode says (KBEC +0x10: 0 32 bytes, 1 64, 2 128, 3 256). */
    private fun ncerCells(ncer: ByteArray, useMapping: Boolean = false): List<List<Oam>> {
        val o = indexOf(ncer, "KBEC") ?: return emptyList()
        val shift = if (useMapping) (u32(ncer, o + 0x10).toInt() and 3) else 0
        val count = u16(ncer, o + 8)
        val extended = u16(ncer, o + 0xA) == 1
        val data = o + 8 + u32(ncer, o + 0xC).toInt()
        val entry = if (extended) 16 else 8
        val oamBase = data + count * entry
        val sizes = arrayOf(
            arrayOf(8 to 8, 16 to 16, 32 to 32, 64 to 64), arrayOf(16 to 8, 32 to 8, 32 to 16, 64 to 32), arrayOf(8 to 16, 8 to 32, 16 to 32, 32 to 64),
        )
        return (0 until count).map { i ->
            val e = data + i * entry
            val n = u16(ncer, e)
            val off = u32(ncer, e + 4).toInt()
            (0 until n).mapNotNull { k ->
                val a = oamBase + off + k * 6
                if (a + 6 > ncer.size) return@mapNotNull null
                val a0 = u16(ncer, a)
                val a1 = u16(ncer, a + 2)
                val a2 = u16(ncer, a + 4)
                val shape = (a0 shr 14) and 3
                if (shape == 3) return@mapNotNull null
                val (w, h) = sizes[shape][(a1 shr 14) and 3]
                val y = (a0 and 0xFF).let { if (it >= 128) it - 256 else it }
                val x = (a1 and 0x1FF).let { if (it >= 256) it - 512 else it }
                Oam(x, y, w, h, (a2 and 0x3FF) shl shift, a2 shr 12, a1 and 0x1000 != 0, a1 and 0x2000 != 0)
            }
        }
    }

    /** [cell]'s OAMs, 4bpp, centred on ([cx], [cy]) in [img], in palette [pal]; the first on top, as the DS draws them. */
    private fun drawCell(img: RomArt.Image, cx: Int, cy: Int, cell: List<Oam>, tiles: ByteArray, pal: IntArray) {
        for (oam in cell.asReversed()) {
            val across = oam.w / 8
            for (py in 0 until oam.h) for (px in 0 until oam.w) {
                val sx = if (oam.hf) oam.w - 1 - px else px
                val sy = if (oam.vf) oam.h - 1 - py else py
                val v = tilePixel(tiles, oam.tile + (sy / 8) * across + sx / 8, sx % 8, sy % 8)
                if (v == 0) continue
                val x = cx + oam.x + px
                val y = cy + oam.y + py
                if (x in 0 until img.width && y in 0 until img.height) img.argb[y * img.width + x] = pal[v]
            }
        }
    }

    private fun write(dir: File, path: String, bytes: ByteArray) {
        val out = File(dir, path)
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(out)
    }

    private fun RandomAccessFile.read(off: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        seek(off)
        readFully(b)
        return b
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16))

    /**
     * HeartGold / SoulSilver's Pokégear map (pgmap_gra, a/1/4/4): its pieces raw, for [PokegearMap] to put together for the
     * save's state - the 8bpp tiles (member 10), the 64 x 64 tilemap the game copies the map and its covers from (11) and
     * the six skins' palettes (20-25) - the player's head on it (cells 2 Ethan / 3 Lyra of member 2 over 1, palette 0) and its
     * cursor (cells 0 / 1).
     */
    private fun pokegear(narc: Narc, dir: File) {
        val tiles = ncgrData(narc.member(10)) ?: return
        val nscr = narc.member(11) ?: return
        val o = indexOf(nscr, "NRCS") ?: return
        write(dir, PokegearMap.TILES, tiles)
        write(dir, PokegearMap.TILEMAP, nscr.copyOfRange(o + 0x14, nscr.size))
        for (skin in 0 until PokegearMap.SKINS) {
            val nclr = narc.member(20 + skin) ?: continue
            val t = indexOf(nclr, "TTLP") ?: continue
            write(dir, PokegearMap.palette(skin), nclr.copyOfRange(t + 0x18, nclr.size))
        }
        val headTiles = ncgrData(narc.member(1)) ?: return
        val cells = ncerCells(narc.member(2) ?: return)
        val pals = palettes(narc.member(0))
        // The map cursor's two frames (cells 0 / 1, 14 and 16 pixels: the sprite pulses between them), centred on 16 x 16.
        for (frame in 0..1) {
            val cell = cells.getOrNull(frame) ?: continue
            val sprite = RomArt.Image(16, 16)
            drawCell(sprite, 8, 8, cell, headTiles, pals.getOrNull(cell.firstOrNull()?.pal ?: 0) ?: continue)
            write(dir, "regionmap/${PokegearMap.cursor(frame)}.png", sprite.png())
        }
        for ((cellIndex, name) in listOf(2 to PokegearMap.HEAD_M, 3 to PokegearMap.HEAD_F)) {
            val cell = cells.getOrNull(cellIndex) ?: continue
            val sprite = RomArt.Image(16, 16)
            drawCell(sprite, 8, 8, cell, headTiles, pals.getOrNull(cell.firstOrNull()?.pal ?: 0) ?: continue)
            write(dir, "regionmap/$name.png", sprite.png())
        }
    }

    /**
     * HeartGold / SoulSilver's bag pocket buttons along the touch screen's top (bag_app_sprite.c's POCKET_ICON_1..8): the
     * bag graphics' (a/0/1/5) animation n of member 50 is pocket n's cell in member 49 over the tiles of 51. Their palettes
     * are member 48's (BagApp_LoadObjPaletteByPocket), button n in palette n: the second eight for every pocket, the open
     * pocket's own from the first eight. Each written in both, the same size, cropped to what any of them covers.
     */
    private fun bagPockets(narc: Narc, dir: File) {
        val tiles = ncgrData(narc.member(51)) ?: return
        val cells = ncerCells(narc.member(49) ?: return)
        val nanr = narc.member(50) ?: return
        val pals = palettes(narc.member(48))
        if (pals.size < 16) return
        val drawn = (0 until 8).flatMap { pocket ->
            val cell = cells.getOrNull(firstCell(nanr, pocket) ?: return) ?: return
            // Button n takes OBJ palette n: the pocket's own colour, lit while open.
            listOf(true, false).map { open ->
                val img = RomArt.Image(64, 64)
                drawCell(img, 32, 32, cell, tiles, pals[if (open) pocket else 8 + pocket])
                Triple(pocket, open, img)
            }
        }
        var l = 64; var t = 64; var r = 0; var b = 0
        for ((_, _, img) in drawn) for (y in 0 until 64) for (x in 0 until 64) if (img.argb[y * 64 + x] != 0) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x + 1); b = maxOf(b, y + 1)
        }
        if (r <= l || b <= t) return
        for ((pocket, open, img) in drawn) {
            val crop = RomArt.Image(r - l, b - t)
            for (y in 0 until crop.height) System.arraycopy(img.argb, (t + y) * 64 + l, crop.argb, y * crop.width, crop.width)
            write(dir, "${heartGoldPocket(pocket, open)}.png", crop.png())
        }
    }

    /** The cart's name banks (pret/pokeplatinum's text_banks.h, pret/pokeheartgold's msgdata): species, moves, items,
     * places, types. */
    private class NameBanks(val msg: String, val species: Int, val moves: Int, val items: Int, val places: Int, val types: Int)
    private val NAMES_PLATINUM = NameBanks("/msgdata/pl_msg.narc", 412, 647, 392, 433, 624)
    private val NAMES_HEARTGOLD = NameBanks("/a/0/2/7", 237, 750, 222, 279, 735)
    // The Korean HeartGold / SoulSilver's: renumbered (one species bank, not the Western carts' two, and banks fewer before
    // the rest: 233 species, 743 moves, 220 items, 274 places, 728 types; found by their line counts, read off the carts).
    private val NAMES_HEARTGOLD_KO = NameBanks("/a/0/2/7", 233, 743, 220, 274, 728)
    // The Japanese ones: Origin HeartGold's numbering (it's built on the Japanese cart): 232 species, 739 moves, 219 items,
    // 272 places, 724 types.
    private val NAMES_HEARTGOLD_JA = NameBanks("/a/0/2/7", 232, 739, 219, 272, 724)
    // Origin HeartGold's (a Japanese HeartGold's archive, renumbered, its banks grown: 232 species and forms, 739 moves,
    // 219 items, 272 places, 724 types).
    private val NAMES_ORIGIN = NameBanks("/a/0/2/7", 232, 739, 219, 272, 724)
    // Black's a/0/0/2 (Gen 5 text, [Gen5Text]): 70 the species as the game prints them (Bulbasaur; 284 is them in
    // capitals), 203 moves, 54 items, 89 places, 199 types.
    private val NAMES_BLACK = NameBanks("/a/0/0/2", 70, 203, 54, 89, 199)
    // Black 2's and White 2's: the same message archive, renumbered (90 species, 403 moves, 64 items, 109 places, 398 types).
    private val NAMES_BLACK2 = NameBanks("/a/0/0/2", 90, 403, 64, 109, 398)
    // Japanese Black / White's: one bank fewer before the moves (204: 203 is their descriptions there).
    private val NAMES_BLACK_JA = NameBanks("/a/0/0/2", 70, 204, 54, 89, 199)
    /** Each language's FIGHTING (the type names' line 1), as the games print it. */
    private val FIGHTING = mapOf(
        "FIGHTING" to 'E', "COMBAT" to 'F', "KAMPF" to 'D', "LOTTA" to 'I', "LUCHA" to 'S', "かくとう" to 'J', "격투" to 'K',
    )

    /** [rom]'s own names ([Names]), or null for a cart this doesn't read. */
    fun readNames(rom: File): Names? = RandomAccessFile(rom, "r").use { f ->
        val header = f.read(0, 0x200)
        val code = String(header, 0x0C, 4, Charsets.US_ASCII)
        val rev = header[0x1E].toInt() and 0xFF
        val sequel = (code == "IREO" || code == "IRDO") && rev == 0 || portIs(code, rev, GameKind.BLACK2, GameKind.WHITE2)
        if (code in setOf("IRBO", "IRAO", "IRBF", "IRAF") && rev == 0 || portIs(code, rev, GameKind.BLACK, GameKind.WHITE) || sequel) {
            val banks = if (sequel) NAMES_BLACK2 else if (code[3] == 'J') NAMES_BLACK_JA else NAMES_BLACK
            val msg = Narc(file(f, header, banks.msg) ?: return null)
            fun lines(bank: Int) = msg.member(bank)?.let { Gen5Text.lines(it) }.orEmpty()
            val types = lines(banks.types)
            return Names(lines(banks.species), lines(banks.moves), lines(banks.items),
                lines(banks.places), FIGHTING[types.getOrNull(1)?.trim()?.uppercase()] ?: 'E', gen5 = true, bw2 = sequel)
        }
        val banks = when {
            code == OriginHeartGold.CODE && OriginHeartGold.matches { off, len -> f.read(off, len) } -> NAMES_ORIGIN
            isPlatinum(code, rev) -> NAMES_PLATINUM
            isHgss(code, rev) -> when (code[3]) { 'K' -> NAMES_HEARTGOLD_KO; 'J' -> NAMES_HEARTGOLD_JA; else -> NAMES_HEARTGOLD }
            else -> return null
        }
        val msg = Narc(file(f, header, banks.msg) ?: return null)
        fun lines(bank: Int) = msg.member(bank)?.let { Gen4Text.lines(it) }.orEmpty()
        val types = lines(banks.types)
        Names(lines(banks.species), lines(banks.moves), lines(banks.items), lines(banks.places),
            FIGHTING[types.getOrNull(1)?.trim()?.uppercase()] ?: 'E')
    }

    /** Where one cart keeps what [dex] reads: file paths and text banks. */
    private class DexFiles(
        val msg: String, val description: Int, val category: Int, val abilities: Int,
        val zukan: String, val personal: String, val regional: String, val learnsets: String, val evolutions: String,
        val pics: String, val pages: String, val fronts: String,
    )

    // pret/pokeheartgold: msgdata a/0/2/7 (803 HeartGold's descriptions, 804 SoulSilver's, 816 categories, 720
    // abilities), the dex data a/0/7/4, personal a/0/0/2, the Johto numbers a/1/3/8, wotbl a/0/3/3, evo a/0/3/4, pokegra a/0/0/4.
    private val DEX_HEARTGOLD = DexFiles("/a/0/2/7", 803, 816, 720, "/a/0/7/4", "/a/0/0/2", "/a/1/3/8", "/a/0/3/3",
        "/a/0/3/4", "/a/0/0/4", HEARTGOLD_DEX, HEARTGOLD_FRONT)
    private val DEX_SOULSILVER = DexFiles("/a/0/2/7", 804, 816, 720, "/a/0/7/4", "/a/0/0/2", "/a/1/3/8", "/a/0/3/3",
        "/a/0/3/4", "/a/0/0/4", HEARTGOLD_DEX, HEARTGOLD_FRONT)
    // The Korean carts: the same archive with seven banks fewer before these (796 / 797 descriptions, 809 categories, 715 abilities).
    private val DEX_HEARTGOLD_KO = DexFiles("/a/0/2/7", 796, 809, 715, "/a/0/7/4", "/a/0/0/2", "/a/1/3/8", "/a/0/3/3",
        "/a/0/3/4", "/a/0/0/4", HEARTGOLD_DEX, HEARTGOLD_FRONT)
    // The Japanese ones: 791 / 792 descriptions, 803 categories (804 is the species in English), 711 abilities.
    private val DEX_HEARTGOLD_JA = DexFiles("/a/0/2/7", 791, 803, 711, "/a/0/7/4", "/a/0/0/2", "/a/1/3/8", "/a/0/3/3",
        "/a/0/3/4", "/a/0/0/4", HEARTGOLD_DEX, HEARTGOLD_FRONT)
    private val DEX_SOULSILVER_JA = DexFiles("/a/0/2/7", 792, 803, 711, "/a/0/7/4", "/a/0/0/2", "/a/1/3/8", "/a/0/3/3",
        "/a/0/3/4", "/a/0/0/4", HEARTGOLD_DEX, HEARTGOLD_FRONT)
    private val DEX_SOULSILVER_KO = DexFiles("/a/0/2/7", 797, 809, 715, "/a/0/7/4", "/a/0/0/2", "/a/1/3/8", "/a/0/3/3",
        "/a/0/3/4", "/a/0/0/4", HEARTGOLD_DEX, HEARTGOLD_FRONT)
    // pret/pokeplatinum (platinum.us/filesys.csv, generated/text_banks.h): pl_msg's 706 descriptions (the US entries),
    // 711 categories, 610 abilities; zukan_data_gira (Giratina's Altered Forme, the dex's default); the Sinnoh numbers
    // in pl_pokezukan (national_to_regional).
    private val DEX_PLATINUM = DexFiles("/msgdata/pl_msg.narc", 706, 711, 610,
        "/application/zukanlist/zkn_data/zukan_data_gira.narc", "/poketool/personal/pl_personal.narc",
        "/poketool/pl_pokezukan.narc", "/poketool/personal/wotbl.narc", "/poketool/personal/evo.narc",
        "/poketool/pokegra/pl_pokegra.narc", PLATINUM_DEX, PLATINUM_FRONT)

    /** Renegade Platinum's: Platinum's files, its own pages and pictures under rom-art. */
    private val DEX_RENEGADE = DexFiles("/msgdata/pl_msg.narc", 706, 711, 610,
        "/application/zukanlist/zkn_data/zukan_data_gira.narc", "/poketool/personal/pl_personal.narc",
        "/poketool/pl_pokezukan.narc", "/poketool/personal/wotbl.narc", "/poketool/personal/evo.narc",
        "/poketool/pokegra/pl_pokegra.narc", RENEGADE_DEX, RENEGADE_FRONT)

    /**
     * The DEX pages ([DexFiles.pages]) and front pictures ([DexFiles.fronts]), all from the cart ([DexFiles] has where):
     * - text (msgdata, by bank): the description (HeartGold and SoulSilver each their own), the category, ability names -
     *   [Gen4Text];
     * - height (dm) and weight (hg): the dex data's members 0 / 1, a u32 per species;
     * - the personal record (by species, 0x2C bytes): base stats, types, catch rate, gender ratio, egg groups,
     *   abilities, and the TM / HM bits (0x1C-0x2B);
     * - the regional Dex number (member 0, a u16 per species), the evolutions (by species: 7 x {method, param,
     *   target}) and the level-up moves (by species: level << 9 | move, ending 0xFFFF);
     * - the front picture: pokegra member species x 6 + 3 (male; + 2 female where there's no male one), its pixels
     *   unscrambled as the game does (pokepic.c's UnscanPokepic_PtHGSS: each u16 XORed with an LCG seeded by the
     *   first), 160 x 80 linear 4bpp, two frames side by side; the palette species x 6 + 4.
     */
    private fun dex(f: RandomAccessFile, header: ByteArray, dir: File, d: DexFiles) {
        val msg = Narc(file(f, header, d.msg) ?: return)
        val text = msg.member(d.description)?.let { Gen4Text.lines(it) } ?: return
        val category = msg.member(d.category)?.let { Gen4Text.lines(it) } ?: return
        val abilities = msg.member(d.abilities)?.let { Gen4Text.lines(it) } ?: emptyList()
        val zukan = Narc(file(f, header, d.zukan) ?: return)
        val heights = zukan.member(0) ?: return
        val weights = zukan.member(1) ?: return
        val personal = Narc(file(f, header, d.personal) ?: return)
        val johto = Narc(file(f, header, d.regional) ?: return).member(0) ?: return
        val learnsets = Narc(file(f, header, d.learnsets) ?: return)
        val evolutions = Narc(file(f, header, d.evolutions) ?: return)
        val sb = StringBuilder()
        for (species in 1..493) {
            val p = personal.member(species) ?: continue
            if (p.size < 0x18) continue
            fun b(o: Int) = u8(p, o)
            fun w(arr: ByteArray, i: Int) = if (i * 4 + 4 <= arr.size) u32(arr, i * 4).toInt() else 0
            val cells = listOf(
                species.toString(),
                category.getOrElse(species) { "" },
                text.getOrElse(species) { "" }.replace("\n", "\\n"),
                w(heights, species).toString(),
                w(weights, species).toString(),
                // HP, ATTACK, DEFENSE, SP. ATK, SP. DEF, SPEED (the record keeps SPEED fourth)
                b(0).toString(), b(1).toString(), b(2).toString(), b(4).toString(), b(5).toString(), b(3).toString(),
                b(6).toString(), b(7).toString(), b(8).toString(), b(0x10).toString(), b(0x14).toString(), b(0x15).toString(),
                abilities.getOrElse(b(0x16)) { "" }, if (b(0x17) != 0) abilities.getOrElse(b(0x17)) { "" } else "",
                // Its regional Dex number (0: not in it), its evolutions (method,param,target;...), its level-up moves
                // (level:move,...) and its TM / HM bits (TM01 .. TM92, HM01 .. HM08, low bit first) as hex.
                if (species * 2 + 2 <= johto.size) u16(johto, species * 2).toString() else "0",
                evolutions.member(species)?.let { e ->
                    (0 until 7).mapNotNull { i ->
                        if (i * 6 + 6 > e.size) return@mapNotNull null
                        val m = u16(e, i * 6)
                        if (m == 0) null else "$m,${u16(e, i * 6 + 2)},${u16(e, i * 6 + 4)}"
                    }.joinToString(";")
                } ?: "",
                learnsets.member(species)?.let { l ->
                    (0 until l.size / 2).asSequence().map { u16(l, it * 2) }.takeWhile { it != 0xFFFF }
                        .joinToString(",") { "${it shr 9}:${it and 0x1FF}" }
                } ?: "",
                if (p.size >= 0x2C) (0x1C until 0x2C).joinToString("") { "%02x".format(b(it)) } else "",
            )
            sb.append(cells.joinToString("\t") { it.replace("\t", " ") }).append('\n')
        }
        write(dir, d.pages, sb.toString().toByteArray())
        val pics = Narc(file(f, header, d.pics) ?: return)
        for (species in 1..493) {
            val pal = palettes(pics.member(species * 6 + 4)).firstOrNull() ?: continue
            val chars = ncgrData(pics.member(species * 6 + 3))?.takeIf { it.size >= 6400 }
                ?: ncgrData(pics.member(species * 6 + 2))?.takeIf { it.size >= 6400 } ?: continue
            val px = unscanPokepic(chars)
            val img = RomArt.Image(80, 80)
            for (y in 0 until 80) for (x in 0 until 80) {
                val v = u8(px, y * 80 + x / 2).let { if (x and 1 == 1) it shr 4 else it and 15 }
                if (v != 0) img.argb[y * 80 + x] = pal[v]
            }
            write(dir, "${d.fronts}/$species.png", img.png())
        }
    }

    /** pokepic.c's UnscanPokepic_PtHGSS: the first 3200 u16s XORed in turn with an LCG (x 1103515245 + 24691) seeded by
     * the first one's own value, its low half each time. */
    private fun unscanPokepic(data: ByteArray): ByteArray {
        val out = data.copyOf()
        var seed = u16(data, 0).toLong()
        for (i in 0 until 3200) {
            val v = u16(out, i * 2) xor (seed and 0xFFFF).toInt()
            out[i * 2] = v.toByte()
            out[i * 2 + 1] = (v shr 8).toByte()
            seed = (seed * 1103515245L + 24691L) and 0xFFFFFFFFL
        }
        return out
    }

    /** An NANR's animation [anim]: its first frame's cell (KNBA: the animations, their frames, the frames' cell data). */
    private fun firstCell(nanr: ByteArray, anim: Int): Int? {
        val o = indexOf(nanr, "KNBA") ?: return null
        val base = o + 8
        if (anim >= u16(nanr, base)) return null
        val anims = base + u32(nanr, base + 4).toInt()
        val frames = base + u32(nanr, base + 8).toInt()
        val positions = base + u32(nanr, base + 12).toInt()
        val frame = frames + u32(nanr, anims + anim * 16 + 12).toInt()
        val pos = positions + u32(nanr, frame).toInt()
        return if (pos + 2 <= nanr.size) u16(nanr, pos) else null
    }

    /** A file in the cart's file system by its path: the FNT walked a directory at a time, the FAT for its bytes. */
    private fun file(f: RandomAccessFile, header: ByteArray, path: String): ByteArray? {
        val fnt = f.read(u32(header, 0x40), u32(header, 0x44).toInt())
        val fatOff = u32(header, 0x48)
        var id = 0xF000
        for (name in path.trim('/').split('/')) {
            val entry = (id and 0xFFF) * 8
            var p = u32(fnt, entry).toInt()
            var fileId = u16(fnt, entry + 4)
            var found = -1
            while (true) {
                val len = u8(fnt, p++)
                if (len == 0) break
                val isDir = len and 0x80 != 0
                val n = len and 0x7F
                val entryName = String(fnt, p, n, Charsets.US_ASCII)
                p += n
                if (isDir) {
                    val sub = u16(fnt, p)
                    p += 2
                    if (entryName == name) { found = sub; break }
                } else {
                    if (entryName == name) { found = fileId; break }
                    fileId++
                }
            }
            if (found < 0) return null
            id = found
        }
        val fat = f.read(fatOff + id * 8L, 8)
        val start = u32(fat, 0)
        val end = u32(fat, 4)
        // (Origin HeartGold's front pictures are one 37.6 MB archive.)
        if (end <= start || end - start > 48L shl 20) return null
        return f.read(start, (end - start).toInt())
    }

    /** A NARC's members: BTAF (start / end offsets), BTNF, then GMIF holding the bytes. */
    private class Narc(val d: ByteArray) {
        private val count: Int
        private val fat: Int
        private val base: Int

        init {
            require(String(d, 0, 4, Charsets.US_ASCII) == "NARC") { "not a NARC" }
            var o = u16(d, 0x0C)
            fat = o + 12
            count = u16(d, o + 8)
            o += u32(d, o + 4).toInt()
            o += u32(d, o + 4).toInt()
            base = o + 8
        }

        val size: Int get() = count

        fun member(i: Int): ByteArray? {
            if (i !in 0 until count) return null
            val s = u32(d, fat + i * 8).toInt()
            val e = u32(d, fat + i * 8 + 4).toInt()
            return d.copyOfRange(base + s, base + e)
        }
    }

    /** An NCLR's palettes: TTLP's 16-colour BGR555 rows, as ARGB. */
    private fun palettes(nclr: ByteArray?): List<IntArray> {
        nclr ?: return emptyList()
        val o = indexOf(nclr, "TTLP") ?: return emptyList()
        val data = o + 0x18
        val n = (nclr.size - data) / 32
        return (0 until n).map { p ->
            IntArray(16) { c ->
                val v = u16(nclr, data + (p * 16 + c) * 2)
                val r = (v and 31) * 255 / 31
                val g = ((v shr 5) and 31) * 255 / 31
                val b = ((v shr 10) and 31) * 255 / 31
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** An NCGR icon: 4bpp 8x8 tiles, 4 across, 32 wide and [height] tall (a party icon's two frames: 64; an item: 32);
     * colour 0 clear. */
    private fun icon(ncgr: ByteArray?, pal: IntArray, height: Int = 64): RomArt.Image? {
        ncgr ?: return null
        val o = indexOf(ncgr, "RAHC") ?: return null
        val px = o + 0x20
        if (ncgr.size < px + 32 * height / 2) return null
        val img = RomArt.Image(32, height)
        for (i in 0 until 32 * height) {
            val b = u8(ncgr, px + i / 2)
            val v = if (i and 1 == 1) b shr 4 else b and 15
            if (v == 0) continue
            val tile = i / 64
            val within = i % 64
            val x = (tile % 4) * 8 + within % 8
            val y = (tile / 4) * 8 + within / 8
            img.argb[y * 32 + x] = pal[v]
        }
        return img
    }

    private fun indexOf(b: ByteArray, magic: String): Int? {
        val m = magic.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..b.size - m.size) {
            for (j in m.indices) if (b[i + j] != m[j]) continue@outer
            return i
        }
        return null
    }
}
