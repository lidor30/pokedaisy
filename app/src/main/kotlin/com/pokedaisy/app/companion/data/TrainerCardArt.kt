package com.pokedaisy.app.companion.data

import okio.Path

/**
 * Draws the game's own TRAINER CARD, pixel for pixel, from the player's ROM:
 * the card's tiles, front / back / backdrop tilemaps, the palette for its
 * star count, badges, the player's trainer pic and FONT_NORMAL, all found by
 * [RomArt] and cached as raw blobs ([RomArt.rawPath]). Layers, coordinates
 * and strings follow both decomps' src/trainer_card.c:
 *
 *  - BG2 (bottom): the backdrop tilemap; BG0: the card's front or back.
 *  - BG3: stars (tile 143, palette 4) and badges (from tile 192, palette 3)
 *    on the front, plus the trainer pic window (tile 19,5, palette 8);
 *    FireRed's stickers (from tile 320, palettes 11-14) on the back.
 *  - BG1 (top): the text window at tile 1,1 - every x / y below is relative
 *    to it, exactly as AddTextPrinterParameterized3 gets them.
 *
 * Palettes: the card's three at 0-2 by star count, the female backdrop
 * palette over 1, badges at 3, the star at 4. Text colours are the standard
 * window palette's (dark grey on light grey, red on orange for stats).
 */
object TrainerCardArt {
    private class Blobs(
        val gfx: RomBlob, val front: RomBlob, val back: RomBlob, val bg: RomBlob, val pals: List<RomBlob>,
        val femalePal: RomBlob?, val badgesPal: RomBlob?, val badgesGfx: RomBlob?,
        val picMale: RomBlob, val picMalePal: RomBlob, val picFemale: RomBlob, val picFemalePal: RomBlob,
        val font: RomBlob, val widths: RomBlob,
        /** FireRed's Sticker Man stickers: their tiles, then a palette per brag level. */
        val stickers: List<RomBlob> = emptyList(),
        /** Applied to the card's three palettes; null = as stored. */
        val recolor: ((Int) -> Int)? = null,
    ) {
        val all get() = (listOf(gfx, front, back, bg) + pals + listOfNotNull(
            femalePal, badgesPal, badgesGfx, RomBlob.CARD_STAR_PAL, picMale, picMalePal, picFemale, picFemalePal, font, widths,
        ) + stickers).distinct()
    }

    private val KANTO = Blobs(
        RomBlob.FR_CARD_GFX, RomBlob.FR_CARD_FRONT, RomBlob.FR_CARD_BACK, RomBlob.FR_CARD_BG,
        listOf(RomBlob.FR_CARD_PAL0, RomBlob.FR_CARD_PAL1, RomBlob.FR_CARD_PAL2, RomBlob.FR_CARD_PAL3, RomBlob.FR_CARD_PAL4),
        RomBlob.FR_CARD_FEMALE_PAL, RomBlob.FR_CARD_BADGES_PAL, RomBlob.FR_CARD_BADGES_GFX,
        RomBlob.FR_PIC_RED, RomBlob.FR_PIC_RED_PAL, RomBlob.FR_PIC_LEAF, RomBlob.FR_PIC_LEAF_PAL,
        RomBlob.FR_FONT_NORMAL, RomBlob.FR_FONT_NORMAL_WIDTHS,
        listOf(
            RomBlob.FR_CARD_STICKERS_GFX, RomBlob.FR_CARD_STICKER_PAL1, RomBlob.FR_CARD_STICKER_PAL2,
            RomBlob.FR_CARD_STICKER_PAL3, RomBlob.FR_CARD_STICKER_PAL4,
        ),
    )

    private val HOENN = Blobs(
        RomBlob.EM_CARD_GFX, RomBlob.EM_CARD_FRONT, RomBlob.EM_CARD_BACK, RomBlob.EM_CARD_BG,
        listOf(RomBlob.EM_CARD_PAL0, RomBlob.EM_CARD_PAL1, RomBlob.EM_CARD_PAL2, RomBlob.EM_CARD_PAL3, RomBlob.EM_CARD_PAL4),
        RomBlob.EM_CARD_FEMALE_PAL, RomBlob.EM_CARD_BADGES_PAL, RomBlob.EM_CARD_BADGES_GFX,
        RomBlob.EM_PIC_BRENDAN, RomBlob.EM_PIC_BRENDAN_PAL, RomBlob.EM_PIC_MAY, RomBlob.EM_PIC_MAY_PAL,
        RomBlob.EM_FONT_NORMAL, RomBlob.EM_FONT_NORMAL_WIDTHS,
    )

    /**
     * Unbound has no card of this kind to copy (its own is a different screen),
     * so it gets FireRed's - which its ROM still holds - turned to the purple
     * of its own card ([unboundPurple]), with its player's front pic
     * (Unbound's protagonists replace Red / Leaf at the same pic ids). Its ROM
     * dropped FireRed's 0-star palette, so every star count starts from the
     * 1-star one; its badges aren't FireRed's, so none are drawn.
     */
    private val UNBOUND = Blobs(
        RomBlob.FR_CARD_GFX, RomBlob.FR_CARD_FRONT, RomBlob.FR_CARD_BACK, RomBlob.FR_CARD_BG,
        List(5) { RomBlob.FR_CARD_PAL1 },
        null, null, null,
        RomBlob.UB_PIC_MALE, RomBlob.UB_PIC_MALE_PAL, RomBlob.UB_PIC_FEMALE, RomBlob.UB_PIC_FEMALE_PAL,
        RomBlob.FR_FONT_NORMAL, RomBlob.FR_FONT_NORMAL_WIDTHS,
        recolor = ::unboundPurple,
    )

    /**
     * Glazed: retail Emerald's card, with its own Tunod badges (repointed tiles, the palette
     * edited in place), its own player pics (the front-pic table's 71 / 72 repointed, their
     * palettes edited in place) and FONT_NORMAL edited in place (its word on the back's
     * "POKéBLOCKS" line). The widths are retail's.
     */
    private val GLAZED = Blobs(
        RomBlob.EM_CARD_GFX, RomBlob.EM_CARD_FRONT, RomBlob.EM_CARD_BACK, RomBlob.EM_CARD_BG,
        listOf(RomBlob.EM_CARD_PAL0, RomBlob.EM_CARD_PAL1, RomBlob.EM_CARD_PAL2, RomBlob.EM_CARD_PAL3, RomBlob.EM_CARD_PAL4),
        RomBlob.EM_CARD_FEMALE_PAL, RomBlob.GZ_CARD_BADGES_PAL, RomBlob.GZ_CARD_BADGES_GFX,
        RomBlob.GZ_PIC_MALE, RomBlob.GZ_PIC_MALE_PAL, RomBlob.GZ_PIC_FEMALE, RomBlob.GZ_PIC_FEMALE_PAL,
        RomBlob.GZ_FONT_NORMAL, RomBlob.EM_FONT_NORMAL_WIDTHS,
    )

    /** Emerald Legacy: retail Emerald's card, plus a sixth palette - the black card of a fifth star (catching MEW). */
    private val LEGACY = Blobs(
        RomBlob.EM_CARD_GFX, RomBlob.EM_CARD_FRONT, RomBlob.EM_CARD_BACK, RomBlob.EM_CARD_BG,
        HOENN.pals + RomBlob.LG_CARD_PAL5,
        RomBlob.EM_CARD_FEMALE_PAL, RomBlob.EM_CARD_BADGES_PAL, RomBlob.EM_CARD_BADGES_GFX,
        RomBlob.EM_PIC_BRENDAN, RomBlob.EM_PIC_BRENDAN_PAL, RomBlob.EM_PIC_MAY, RomBlob.EM_PIC_MAY_PAL,
        RomBlob.EM_FONT_NORMAL, RomBlob.EM_FONT_NORMAL_WIDTHS,
    )

    /** Emerald Imperium: retail Emerald's card and pics in its own FONT_NORMAL (and widths). */
    private val IMPERIUM = Blobs(
        RomBlob.EM_CARD_GFX, RomBlob.EM_CARD_FRONT, RomBlob.EM_CARD_BACK, RomBlob.EM_CARD_BG,
        listOf(RomBlob.EM_CARD_PAL0, RomBlob.EM_CARD_PAL1, RomBlob.EM_CARD_PAL2, RomBlob.EM_CARD_PAL3, RomBlob.EM_CARD_PAL4),
        RomBlob.EM_CARD_FEMALE_PAL, RomBlob.EM_CARD_BADGES_PAL, RomBlob.EM_CARD_BADGES_GFX,
        RomBlob.EM_PIC_BRENDAN, RomBlob.EM_PIC_BRENDAN_PAL, RomBlob.EM_PIC_MAY, RomBlob.EM_PIC_MAY_PAL,
        RomBlob.IMP_FONT_NORMAL, RomBlob.IMP_FONT_NORMAL_WIDTHS,
    )

    /**
     * Lazarus: Emerald's card code with its own card tiles, front / back tilemaps, 0- and 1-star palettes, badges, player
     * pics and FONT_NORMAL; the backdrop, the other palettes and the female backdrop palette are retail's bytes.
     */
    private val LAZARUS = Blobs(
        RomBlob.LZ_CARD_GFX, RomBlob.LZ_CARD_FRONT, RomBlob.LZ_CARD_BACK, RomBlob.EM_CARD_BG,
        listOf(RomBlob.LZ_CARD_PAL0, RomBlob.LZ_CARD_PAL1, RomBlob.EM_CARD_PAL2, RomBlob.EM_CARD_PAL3, RomBlob.EM_CARD_PAL4),
        RomBlob.EM_CARD_FEMALE_PAL, RomBlob.LZ_CARD_BADGES_PAL, RomBlob.LZ_CARD_BADGES_GFX,
        RomBlob.LZ_PIC_MALE, RomBlob.LZ_PIC_MALE_PAL, RomBlob.LZ_PIC_FEMALE, RomBlob.LZ_PIC_FEMALE_PAL,
        RomBlob.LZ_FONT_NORMAL, RomBlob.LZ_FONT_NORMAL_WIDTHS,
    )

    /** SoulGold: Emerald's card code with its Johto art, smol-compressed: its own everything but the star's palette. */
    private val SOULGOLD = Blobs(
        RomBlob.SG_CARD_GFX, RomBlob.SG_CARD_FRONT, RomBlob.SG_CARD_BACK, RomBlob.SG_CARD_BG,
        listOf(RomBlob.SG_CARD_PAL0, RomBlob.SG_CARD_PAL1, RomBlob.SG_CARD_PAL2, RomBlob.SG_CARD_PAL3, RomBlob.SG_CARD_PAL4),
        RomBlob.SG_CARD_FEMALE_PAL, RomBlob.SG_CARD_BADGES_PAL, RomBlob.SG_CARD_BADGES_GFX,
        RomBlob.SG_PIC_MALE, RomBlob.SG_PIC_MALE_PAL, RomBlob.SG_PIC_FEMALE, RomBlob.SG_PIC_FEMALE_PAL,
        RomBlob.SG_FONT_NORMAL, RomBlob.SG_FONT_NORMAL_WIDTHS,
    )

    /**
     * Recharged Yellow: FireRed's card byte for byte with its own player pics and FONT_NORMAL. Its stickers' palettes are
     * FireRed's first three, the fourth brag level reusing the third.
     */
    private val RECHARGED_YELLOW = Blobs(
        RomBlob.FR_CARD_GFX, RomBlob.FR_CARD_FRONT, RomBlob.FR_CARD_BACK, RomBlob.FR_CARD_BG,
        listOf(RomBlob.FR_CARD_PAL0, RomBlob.FR_CARD_PAL1, RomBlob.FR_CARD_PAL2, RomBlob.FR_CARD_PAL3, RomBlob.FR_CARD_PAL4),
        RomBlob.FR_CARD_FEMALE_PAL, RomBlob.FR_CARD_BADGES_PAL, RomBlob.FR_CARD_BADGES_GFX,
        RomBlob.RY_PIC_MALE, RomBlob.RY_PIC_MALE_PAL, RomBlob.RY_PIC_FEMALE, RomBlob.RY_PIC_FEMALE_PAL,
        RomBlob.RY_FONT_NORMAL, RomBlob.RY_FONT_NORMAL_WIDTHS,
        listOf(
            RomBlob.FR_CARD_STICKERS_GFX, RomBlob.FR_CARD_STICKER_PAL1, RomBlob.FR_CARD_STICKER_PAL2,
            RomBlob.FR_CARD_STICKER_PAL3, RomBlob.FR_CARD_STICKER_PAL3,
        ),
    )

    private fun blobs(style: CardStyle) = when (style) {
        CardStyle.KANTO -> KANTO
        CardStyle.HOENN -> HOENN
        CardStyle.UNBOUND -> UNBOUND
        CardStyle.GLAZED -> GLAZED
        CardStyle.IMPERIUM -> IMPERIUM
        CardStyle.LEGACY -> LEGACY
        CardStyle.LAZARUS -> LAZARUS
        CardStyle.SOULGOLD -> SOULGOLD
        CardStyle.RECHARGED_YELLOW, CardStyle.RECHARGED_YELLOW_V1_9 -> RECHARGED_YELLOW
    }

    /** Every blob a card needs, every style. */
    val BLOBS: List<RomBlob> = (KANTO.all + HOENN.all + UNBOUND.all + GLAZED.all + IMPERIUM.all + LEGACY.all + LAZARUS.all + SOULGOLD.all + RECHARGED_YELLOW.all).distinct()

    /** One style's decoded blobs. */
    class Art internal constructor(val style: CardStyle, internal val d: Map<RomBlob, ByteArray>)

    /** [style]'s card from [get], or null unless every blob is there. */
    fun art(style: CardStyle, get: (RomBlob) -> ByteArray?): Art? {
        val d = blobs(style).all.associateWith { get(it) ?: return null }
        return Art(style, d)
    }

    private val loaded = LockedMap<CardStyle, Art>()

    /** [style]'s card from the cached blobs under [dir] ([RomArt.dir]); kept for [cached]. */
    fun load(dir: Path, style: CardStyle): Art? =
        art(style) { b -> (dir / RomArt.rawPath(b)).takeIf { it.isFile }?.readBytes() }?.also { loaded[style] = it }

    /** The last [load]ed card of [style], so a screen can start drawing it without waiting. */
    fun cached(style: CardStyle): Art? = loaded[style]

    private const val TEXT = 0xFF636363.toInt()
    private const val TEXT_SHADOW = 0xFFD6D6CE.toInt()
    private const val STAT = 0xFFE70808.toInt()
    private const val STAT_SHADOW = 0xFFFFBD73.toInt()

    // Per style: the text window's width, FONT_NORMAL's letter spacing and
    // glyph height (FireRed 14 rows, Emerald 16; RenderText adds the spacing
    // only to Japanese text, so it is 0 for both), the digit pad of
    // STR_CONV_MODE_RIGHT_ALIGN (FireRed a space, Emerald CHAR_SPACER), the
    // stars' / badges' tile rows and the trainer pic's offset in its window; [cols]: which of a glyph's 16 columns are
    // drawn; [starPal]: the stars' BG palette; [text] / [shadow]: the window palette's text colours; [marks]: the back's
    // little stat markers.
    private class Layout(
        val textW: Int, val spacing: Int, val glyphH: Int, val pad: Int, val starY: Int, val badgeY: Int, val picX: Int, val picY: Int,
        val cols: GlyphCols = GlyphCols.ALL, val starPal: Int = 4, val text: Int = TEXT, val shadow: Int = TEXT_SHADOW,
        val marks: Boolean = true,
    )

    /** ALL 16; LEFT_IF_NARROW: only the left 8 of a glyph no wider than 8 (expansion's DecompressGlyph); WIDTH: its width. */
    private enum class GlyphCols { ALL, LEFT_IF_NARROW, WIDTH }

    private val KANTO_LAYOUT = Layout(textW = 27 * 8, spacing = 0, glyphH = 14, pad = 0x00, starY = 7, badgeY = 16, picX = 13, picY = 4)
    private val HOENN_LAYOUT = Layout(textW = 28 * 8, spacing = 0, glyphH = 16, pad = 0x77, starY = 7, badgeY = 15, picX = 1, picY = 0)
    private val LAZARUS_LAYOUT = Layout(textW = 28 * 8, spacing = 0, glyphH = 15, pad = 0x77, starY = 7, badgeY = 15, picX = 1, picY = 0,
        cols = GlyphCols.LEFT_IF_NARROW, starPal = 1)
    // Recharged Yellow: FireRed's frame, its own text window (27 tiles, 14-row glyphs drawn to their width); v1.9's red is
    // lighter (its window palette's colour 4).
    private val RECHARGED_YELLOW_LAYOUT = Layout(textW = 27 * 8, spacing = 0, glyphH = 14, pad = 0x00, starY = 7, badgeY = 16, picX = 13,
        picY = 4, cols = GlyphCols.WIDTH)
    private const val RY19_STAT = 0xFFE75252.toInt()

    private val SOULGOLD_LAYOUT = Layout(textW = 28 * 8, spacing = 0, glyphH = 15, pad = 0x77, starY = 7, badgeY = 15, picX = 1, picY = 0,
        cols = GlyphCols.WIDTH, text = 0xFF181818.toInt(), shadow = TEXT_SHADOW, marks = false)

    /**
     * What the Emerald-style card prints, per style: its labels (in the game's encoding) and how many digits money and the
     * POKéDEX get. Imperium and Lazarus reword some labels, Glazed and SoulGold print 7 money digits (their cap is
     * 9,999,999), Lazarus and SoulGold 4 POKéDEX digits (their National Dex passes 999). SoulGold's [white] headings (its
     * ID once the card has a star, the back's title) are white over dark grey.
     */
    private class Words(
        val name: String = "NAME: ", val money: String = "MONEY", val dex: String = "POKéDEX", val time: String = "TIME",
        val cardOf: String = "'s TRAINER CARD", val hof: String = "HALL OF FAME DEBUT  ", val link: String = "LINK BATTLES",
        val trades: String = "POKéMON TRADES", val blocks: String = "S W/FRIENDS", val contests: String = "WON CONTESTS W/FRIENDS",
        val points: String = "BATTLE POINTS WON", val moneyDigits: Int = 6, val dexDigits: Int = 3, val white: Boolean = false,
    )

    private fun words(style: CardStyle) = when (style) {
        CardStyle.GLAZED -> Words(moneyDigits = 7)
        CardStyle.IMPERIUM -> Words(dex = "Pokédex", time = "Time", trades = "Pokémon TRADES")
        CardStyle.LAZARUS -> Words(dex = "Pokédex", hof = "Hall of Fame DEBUT  ", trades = "Pokémon TRADES", dexDigits = 4)
        CardStyle.SOULGOLD -> Words(
            name = "Name: ", money = "Money", dex = "Pokédex", time = "Time", cardOf = "'s Trainer Card", hof = "Hall of Fame Debut  ",
            link = "Link Battles", trades = "Pokémon Trades", blocks = "S W/Friends", contests = "Won Contests W/Friends",
            points = "Battle Points Won", moneyDigits = 7, dexDigits = 4, white = true,
        )
        else -> Words()
    }

    /**
     * The card as the game shows it: 240x160, front or [back]; [colon] = the
     * play time's blinking colon. [backdrop] false leaves out the screen
     * behind the card (BG2), transparent instead - see [cardBounds]. [pic]: another 64x64 front pic and its palette in
     * place of the player's (PLAYER LOOK's hero, as the game itself then draws it).
     */
    fun render(art: Art, card: TrainerCardInfo, back: Boolean, colon: Boolean = true, backdrop: Boolean = true,
               pic: Pair<ByteArray, ByteArray>? = null): RomArt.Image {
        val b = blobs(art.style)
        val kanto = !art.style.hoenn
        val ry = art.style == CardStyle.RECHARGED_YELLOW || art.style == CardStyle.RECHARGED_YELLOW_V1_9
        val lay = when {
            ry -> RECHARGED_YELLOW_LAYOUT
            kanto -> KANTO_LAYOUT
            art.style == CardStyle.LAZARUS -> LAZARUS_LAYOUT
            art.style == CardStyle.SOULGOLD -> SOULGOLD_LAYOUT
            else -> HOENN_LAYOUT
        }
        fun d(r: RomBlob) = art.d.getValue(r)
        val pal = IntArray(256)
        RomArt.palette(d(b.pals[card.stars.coerceIn(0, b.pals.size - 1)])).copyInto(pal, 0)
        if (card.female) b.femalePal?.let { RomArt.palette(d(it)).copyInto(pal, 16) }
        b.recolor?.let { f -> for (i in 0 until 48) pal[i] = f(pal[i]) }
        b.badgesPal?.let { RomArt.palette(d(it)).copyInto(pal, 48) }
        RomArt.palette(d(RomBlob.CARD_STAR_PAL)).copyInto(pal, 64)
        val tiles = d(b.gfx)
        val img = RomArt.Image(240, 160)

        // BG2 then BG0: colour 0 is see-through, down to the backdrop (palette 0's first colour).
        if (backdrop) img.argb.fill(pal[0])
        for (map in listOfNotNull(d(b.bg).takeIf { backdrop }, d(if (back) b.back else b.front))) {
            for (ty in 0 until 20) for (tx in 0 until 30) tile(img, tiles, pal, u16(map, ty * 30 + tx), tx, ty)
        }
        // BG3.
        val bg3 = ByteArray(0x1800 + 0x400).also { tiles.copyInto(it); b.badgesGfx?.let { g -> d(g).copyInto(it, 192 * 32) } }
        if (!back) {
            for (i in 0 until card.drawnStars.coerceIn(0, b.pals.size - 1)) tile(img, bg3, pal, (lay.starPal shl 12) or 143, 15 + i, lay.starY)
            for (i in 0 until 8) if (b.badgesGfx != null && card.badges and (1 shl i) != 0) {
                val t = 192 + 2 * i
                val x = 4 + 3 * i
                tile(img, bg3, pal, (3 shl 12) or t, x, lay.badgeY)
                tile(img, bg3, pal, (3 shl 12) or (t + 1), x + 1, lay.badgeY)
                tile(img, bg3, pal, (3 shl 12) or (t + 16), x, lay.badgeY + 1)
                tile(img, bg3, pal, (3 shl 12) or (t + 17), x + 1, lay.badgeY + 1)
            }
            if (pic != null) pic(img, pic.first, RomArt.palette(pic.second), lay)
            else pic(img, d(if (card.female) b.picFemale else b.picMale), RomArt.palette(d(if (card.female) b.picFemalePal else b.picMalePal)), lay)
        } else if (kanto) {
            // DrawCardBackStats: a little marker beside each FireRed stat line that has a count.
            fun mark(x: Int, y: Int) {
                tile(img, bg3, pal, (1 shl 12) or 141, x, y)
                tile(img, bg3, pal, (1 shl 12) or 157, x, y + 1)
            }
            if (card.trades != 0) mark(if (ry) 27 else 26, 9)
            if (card.berryCrush != 0) mark(21, 13)
            if (card.unionRoom != 0) mark(27, 11)
            // PrintStickersOnCard: sticker i (HoF, eggs, link wins) at tile (2 + 3i, 2), coloured by its brag level.
            val stickerTiles = b.stickers.firstOrNull()?.let(::d)
            card.stickers.forEachIndexed { i, level ->
                if (level !in 1..4 || stickerTiles == null) return@forEachIndexed
                val sp = IntArray(256).also { RomArt.palette(d(b.stickers[level])).copyInto(it) }
                for (k in 0 until 4) tile(img, stickerTiles, sp, i * 4 + k, 2 + 3 * i + k % 2, 2 + k / 2)
            }
            // Recharged Yellow's PrintPokemonIconsOnCard: icon i, 4x4 tiles at (3 + 4i, 15), in its own colours.
            card.backIcons.forEachIndexed { i, icon ->
                if (icon == null) return@forEachIndexed
                for (k in 0 until 16) tile(img, icon.tiles, icon.palette, k, 3 + 4 * i + k % 4, 15 + k / 4)
            }
        } else {
            // Emerald's DrawCardBackStats (palette 0).
            fun mark(x: Int, y: Int) {
                tile(img, bg3, pal, 141, x, y)
                tile(img, bg3, pal, 157, x, y + 1)
            }
            if (lay.marks && card.trades != 0) mark(27, 9)
            if (lay.marks && card.linkContests != 0) mark(27, 13)
        }
        // BG1.
        val t = Text(img, d(b.font), d(b.widths), lay)
        when {
            ry -> rechargedYellowText(t, card, back, colon, if (art.style == CardStyle.RECHARGED_YELLOW_V1_9) RY19_STAT else STAT)
            kanto -> kantoText(t, card, back, colon)
            else -> hoennText(t, card, back, colon, art.style)
        }
        return img
    }

    /**
     * FireRed's card colour turned to Unbound's card: its purple hue (266),
     * the pale greens and teals onto Unbound's lavender ramp (sampled headless:
     * CEADF7 / AD7BE7 / 7B52B5 - about 1.8x the saturation, a little brighter).
     * Greys and the gold accents (hue 25-65) stay.
     */
    private fun unboundPurple(argb: Int): Int {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val bl = (argb and 0xFF) / 255f
        val max = maxOf(r, g, bl)
        val min = minOf(r, g, bl)
        val d = max - min
        val s = if (max == 0f) 0f else d / max
        if (s < 0.08f) return argb
        val hue = when {
            max == r -> 60f * (((g - bl) / d) % 6f)
            max == g -> 60f * ((bl - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        if (hue in 25f..65f) return argb
        return hsv(266f, minOf(s * 1.8f, 0.6f), minOf(max * 1.15f + 0.05f, 1f), argb ushr 24)
    }

    private fun hsv(hue: Float, s: Float, v: Float, alpha: Int): Int {
        val h = hue / 60f
        val c = v * s
        val x = c * (1 - kotlin.math.abs(h % 2 - 1))
        val (r1, g1, b1) = when (h.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }


    private fun kantoText(t: Text, c: TrainerCardInfo, back: Boolean, colon: Boolean) {
        if (!back) {
            t.print(20, 29, enc("NAME: ") + c.name)
            t.print(142, 10, enc("IDNo.") + digits(c.trainerId, 5, LEADING_ZEROS, 0))
            val money = enc("¥") + digits(c.money.toInt(), 6, LEFT, 0)
            t.print(20, 56, enc("MONEY"))
            t.print((134 - 6 * money.size) and 0xFF, 56, money)
            c.dexCaught?.let { n ->
                val s = digits(n, 3, LEFT, 0)
                t.print(20, 72, enc("POKéDEX"))
                t.print((136 - 6 * s.size) and 0xFF, 72, s)
            }
            t.print(20, 88, enc("TIME"))
            t.print(101, 88, digits(c.hours, 3, RIGHT, 0x00))
            if (colon) t.print(119, 88, enc(":"))
            t.print(124, 88, digits(c.minutes, 2, LEADING_ZEROS, 0))
            return
        }
        t.print(138, 11, c.name)
        if (c.hofDebut != 0) {
            t.print(10, 35, enc("HALL OF FAME DEBUT  "))
            t.print(164, 35, hofTime(c.hofDebut, 0x00), STAT, STAT_SHADOW)
        }
        if (c.linkWins != 0 || c.linkLosses != 0) {
            t.print(10, 51, enc("LINK BATTLES"))
            t.print(130, 51, enc("W:"))
            t.print(130 + 0x30, 51, enc("L:"))
            t.print(144, 51, digits(c.linkWins, 4, RIGHT, 0x00), STAT, STAT_SHADOW)
            t.print(192, 51, digits(c.linkLosses, 4, RIGHT, 0x00), STAT, STAT_SHADOW)
        }
        if (c.trades != 0) {
            t.print(10, 67, enc("POKéMON TRADES"))
            t.print(186, 67, digits(c.trades, 5, RIGHT, 0x00), STAT, STAT_SHADOW)
        }
        if (c.unionRoom != 0) {
            t.print(10, 83, enc("UNION TRADES & BATTLES"))
            t.print(186, 83, digits(c.unionRoom, 5, RIGHT, 0x00), STAT, STAT_SHADOW)
        }
        if (c.berryCrush != 0) {
            t.print(10, 99, enc("BERRY CRUSH"))
            t.print(186, 99, digits(c.berryCrush, 5, RIGHT, 0x00), STAT, STAT_SHADOW)
        }
    }

    /**
     * Recharged Yellow's own card text (its strings, read off its ROM: "Lik battles" is the game's own): FireRed's window,
     * Emerald-style arithmetic - the ID centred in 80 px from 132, money / POKéDEX / time right-aligned to 144, the back's
     * labels at x 8 and values right-aligned to 216. Each x as the game computes it (a u8, never below 0).
     */
    private fun rechargedYellowText(t: Text, c: TrainerCardInfo, back: Boolean, colon: Boolean, red: Int) {
        fun right(edge: Int, s: List<Int>) = maxOf(0, edge - t.width(s)) and 0xFF
        if (!back) {
            t.print(20, 28, enc("Name: ") + c.name)
            val id = enc("IDNo.") + digits(c.trainerId, 5, LEADING_ZEROS, 0)
            t.print((maxOf(0, (80 - t.width(id)) / 2) + 132) and 0xFF, 9, id)
            t.print(20, 56, enc("Money"))
            val money = enc("¥") + digits(c.money.toInt(), 7, LEFT, 0)
            t.print(right(144, money), 56, money)
            c.dexCaught?.let { n ->
                t.print(20, 72, enc("Pokédex"))
                val s = digits(n, 3, LEFT, 0)
                t.print(right(144, s), 72, s)
            }
            t.print(20, 88, enc("Time"))
            val colonW = t.width(enc(":"))
            val x = 144 - (colonW + 30)
            t.print(x and 0xFF, 88, digits(c.hours, 3, RIGHT, 0x00))
            if (colon) t.print((x + 18) and 0xFF, 88, enc(":"))
            t.print((x + 18 + colonW) and 0xFF, 88, digits(c.minutes, 2, LEADING_ZEROS, 0))
            return
        }
        t.print(136, 9, c.name)
        fun stat(row: Int, label: List<Int>, value: List<Int>) {
            t.print(8, row * 16 + 33, label)
            t.print(right(216, value), row * 16 + 33, value, red, STAT_SHADOW)
        }
        if (c.hofDebut != 0) stat(0, enc("Hall of Fame debut  "), hofTime(c.hofDebut, 0x00))
        if (c.linkWins != 0 || c.linkLosses != 0) {
            // "W:{red}wins{grey}  L:{red}losses".
            val parts = listOf(enc("W:") to false, digits(c.linkWins, 4, LEFT, 0) to true, enc("  L:") to false,
                digits(c.linkLosses, 4, LEFT, 0) to true)
            t.print(8, 49, enc("Lik battles"))
            var x = right(216, parts.flatMap { it.first })
            for ((part, isRed) in parts) {
                t.print(x, 49, part, if (isRed) red else TEXT, if (isRed) STAT_SHADOW else TEXT_SHADOW)
                x += t.width(part)
            }
        }
        if (c.trades != 0) stat(2, enc("Pokémon Trades"), digits(c.trades, 5, RIGHT, 0x00))
    }

    private fun hoennText(t: Text, c: TrainerCardInfo, back: Boolean, colon: Boolean, style: CardStyle = CardStyle.HOENN) {
        val pad = 0x77
        val w = words(style)
        val text = t.lay.text
        val shadow = t.lay.shadow
        // SoulGold's white headings: colour 1 (white) over 2 (its text colour).
        val white = 0xFFFFFFFF.toInt()
        if (!back) {
            t.print(16, 33, enc(w.name) + c.name)
            val id = enc("IDNo.") + digits(c.trainerId, 5, LEADING_ZEROS, pad)
            if (w.white && c.stars > 0) t.print((96 - t.width(id)) / 2 + 120, 9, id, white, text)
            else t.print((96 - t.width(id)) / 2 + 120, 9, id)
            val money = enc("¥") + digits(c.money.toInt(), w.moneyDigits, LEFT, pad)
            t.print(16, 57, enc(w.money))
            t.print(128 - t.width(money), 57, money)
            c.dexCaught?.let { n ->
                val s = digits(n, w.dexDigits, LEFT, pad)
                t.print(16, 73, enc(w.dex))
                t.print(128 - t.width(s), 73, s)
            }
            t.print(16, 89, enc(w.time))
            val colonW = t.width(enc(":"))
            val x = 128 - (colonW + 30)
            t.print(x, 89, digits(c.hours, 3, RIGHT, pad))
            if (colon) t.print(x + 18, 89, enc(":"))
            t.print(x + 18 + colonW, 89, digits(c.minutes, 2, LEADING_ZEROS, pad))
            return
        }
        val name = c.name + enc(w.cardOf)
        if (w.white) t.print(216 - t.width(name), 9, name, white, text) else t.print(216 - t.width(name), 9, name)
        // PrintStatOnBackOfCard: the label at x 16, the value right-aligned to 216, a row every 16 px.
        fun stat(row: Int, label: List<Int>, value: List<Int>, red: Boolean = true) {
            t.print(16, row * 16 + 33, label)
            t.print(216 - t.width(value), row * 16 + 33, value, if (red) STAT else text, if (red) STAT_SHADOW else shadow)
        }
        if (c.hofDebut != 0) stat(0, enc(w.hof), hofTime(c.hofDebut, pad))
        if (c.linkWins != 0 || c.linkLosses != 0) {
            // "W:{red}wins{grey}  L:{red}losses": one string, colours switching inside it.
            val wl = enc("W:")
            val wins = digits(c.linkWins, 4, LEFT, pad)
            val l = enc("  L:")
            val losses = digits(c.linkLosses, 4, LEFT, pad)
            t.print(16, 49, enc(w.link))
            var x = 216 - t.width(wl + wins + l + losses)
            for ((part, red) in listOf(wl to false, wins to true, l to false, losses to true)) {
                t.print(x, 49, part, if (red) STAT else text, if (red) STAT_SHADOW else shadow)
                x += t.width(part)
            }
        }
        if (c.trades != 0) stat(2, enc(w.trades), digits(c.trades, 5, RIGHT, pad))
        if (c.linkPokeblocks != 0) stat(3, listOf(0x55, 0x56, 0x57, 0x58, 0x59) + enc(w.blocks), digits(c.linkPokeblocks, 5, RIGHT, pad))
        if (c.linkContests != 0) stat(4, enc(w.contests), digits(c.linkContests, 5, RIGHT, pad))
        if (c.battlePoints != 0) {
            val n = digits(c.battlePoints, 5, RIGHT, pad)
            val bp = enc("BP")
            val x = 216 - t.width(n + bp)
            t.print(16, 5 * 16 + 33, enc(w.points))
            t.print(x, 5 * 16 + 33, n, STAT, STAT_SHADOW)
            t.print(x + t.width(n), 5 * 16 + 33, bp)
        }
    }

    /** The card's own pixels in a [render] without the backdrop: left, top, right, bottom (exclusive). */
    fun cardBounds(img: RomArt.Image): IntArray {
        var l = img.width; var t = img.height; var r = 0; var b = 0
        for (y in 0 until img.height) for (x in 0 until img.width) if (img.argb[y * img.width + x] != 0) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x + 1); b = maxOf(b, y + 1)
        }
        return if (r > l) intArrayOf(l, t, r, b) else intArrayOf(0, 0, img.width, img.height)
    }

    private fun hofTime(hof: Int, pad: Int) =
        digits(hof ushr 16, 3, RIGHT, pad) + enc(":") + digits((hof shr 8) and 0xFF, 2, LEADING_ZEROS, pad) + enc(":") + digits(hof and 0xFF, 2, LEADING_ZEROS, pad)

    private const val LEFT = 0
    private const val RIGHT = 1
    private const val LEADING_ZEROS = 2

    /** ConvertIntToDecimalStringN: [n] digits at most; RIGHT pads with [pad], LEFT doesn't. */
    private fun digits(value: Int, n: Int, mode: Int, pad: Int): List<Int> {
        val s = value.coerceAtLeast(0).toString().takeLast(n)
        val out = s.map { 0xA1 + (it - '0') }
        return when (mode) {
            LEADING_ZEROS -> List(n - out.size) { 0xA1 } + out
            RIGHT -> List(n - out.size) { pad } + out
            else -> out
        }
    }

    /** ASCII (and é / ¥) in the games' character encoding. */
    fun enc(s: String): List<Int> = s.map { c ->
        when (c) {
            in 'A'..'Z' -> 0xBB + (c - 'A')
            in 'a'..'z' -> 0xD5 + (c - 'a')
            in '0'..'9' -> 0xA1 + (c - '0')
            ' ' -> 0x00
            'é' -> 0x1B
            '¥' -> 0xB7
            ':' -> 0xF0
            '.' -> 0xAD
            '\'' -> 0xB4
            '&' -> 0x2D
            '/' -> 0xBA
            '-' -> 0xAE
            else -> 0xAC // '?'
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i * 2].toInt() and 0xFF) or ((b[i * 2 + 1].toInt() and 0xFF) shl 8)

    /** One 8x8 4bpp BG tile through tilemap entry [e] at tile ([tx],[ty]); colour 0 left alone. */
    private fun tile(img: RomArt.Image, tiles: ByteArray, pal: IntArray, e: Int, tx: Int, ty: Int) {
        if (tx !in 0 until 30 || ty !in 0 until 20) return
        val t = e and 0x3FF
        val pn = e shr 12
        for (y in 0 until 8) for (x in 0 until 8) {
            val sx = if (e and 0x400 != 0) 7 - x else x
            val sy = if (e and 0x800 != 0) 7 - y else y
            val byte = tiles.getOrElse(t * 32 + sy * 4 + sx / 2) { 0 }.toInt()
            val v = if (sx and 1 != 0) (byte shr 4) and 15 else byte and 15
            if (v != 0) img.argb[(ty * 8 + y) * 240 + tx * 8 + x] = pal[pn * 16 + v]
        }
    }

    /** The 64x64 front pic, blitted into its 72x80 window at tile (19,5), clipped to it. */
    private fun pic(img: RomArt.Image, tiles: ByteArray, pal: IntArray, lay: Layout) {
        val wx = 19 * 8
        val wy = 5 * 8
        for (y in 0 until 64) for (x in 0 until 64) {
            val px = lay.picX + x
            val py = lay.picY + y
            if (px >= 72 || py >= 80) continue
            val byte = tiles[((y / 8) * 8 + x / 8) * 32 + (y % 8) * 4 + (x % 8) / 2].toInt()
            val v = if (x and 1 != 0) (byte shr 4) and 15 else byte and 15
            if (v != 0) img.argb[(wy + py) * 240 + wx + px] = pal[v]
        }
    }

    /** FONT_NORMAL into the text window (tile 1,1): 16x16 2bpp glyphs, 1 = text, 2 = shadow. */
    private class Text(val img: RomArt.Image, val font: ByteArray, val widths: ByteArray, val lay: Layout) {
        fun width(s: List<Int>) = s.sumOf { (widths[it].toInt() and 0xFF) + lay.spacing }

        fun print(x0: Int, y: Int, s: List<Int>, fg: Int = lay.text, shadow: Int = lay.shadow) {
            var x = x0
            for (g in s) {
                val w = widths[g].toInt() and 0xFF
                val cols = when (lay.cols) {
                    GlyphCols.ALL -> 16
                    GlyphCols.LEFT_IF_NARROW -> if (w <= 8) 8 else 16
                    GlyphCols.WIDTH -> minOf(w, 16)
                }
                for (py in 0 until lay.glyphH) for (px in 0 until cols) {
                    val wx = x + px
                    val wy = y + py
                    if (wx !in 0 until lay.textW || wy !in 0 until 144) continue
                    val base = g * 64 + ((py / 8) * 2 + px / 8) * 16 + (py % 8) * 2
                    val row = (font[base].toInt() and 0xFF) or ((font[base + 1].toInt() and 0xFF) shl 8)
                    val c = when ((row shr (14 - 2 * (px % 8))) and 3) {
                        1 -> fg
                        2 -> shadow
                        else -> continue
                    }
                    img.argb[(8 + wy) * 240 + 8 + wx] = c
                }
                x += (widths[g].toInt() and 0xFF) + lay.spacing
            }
        }
    }
}
