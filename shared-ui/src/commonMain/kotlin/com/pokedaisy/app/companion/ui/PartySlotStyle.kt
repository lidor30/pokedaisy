package com.pokedaisy.app.companion.ui

/**
 * Per-game look of the in-game party slot, generated from the game's own data:
 * [FireRedPartyStyle]/[EmeraldPartyStyle] (the MAIN slot, from each decomp by
 * scripts/gen_party_assets.py - PartySlotStylesGen.kt) and the CFRU hacks'
 * shared slot ([UnboundPartyStyle], [RadicalRedPartyStyle], [OdysseyPartyStyle],
 * [AmethystPartyStyle], from the ROMs by scripts/gen_cfru_party_assets.py -
 * CfruPartyStylesGen.kt). Coordinates are slot-window pixels, as the game's
 * sPartyBoxInfoRects / sPartyMenuSpriteCoords give them.
 */
data class PartySlotStyle(
    /** assets/<dir>/slot_*.png. */
    val frameDir: String,
    /** FONT_SMALL atlas (red = text, blue = shadow). */
    val fontAsset: String,
    /** One row of 32x8 icons: PSN, PAR, SLP, FRZ, BRN, PKRS, FNT. */
    val statusAsset: String,
    /** 32x64 closed/open Poke Ball, or null where the game's ball sprite is blank. */
    val pokeballAsset: String?,
    /** FONT_SMALL advance widths by Gen3 char code (+0x100 for 0xF9 symbols). */
    val glyphWidths: IntArray,
    /** The "Lv" glyph: FireRed {LV_2} = F9 05 (0x105), Emerald {LV} = 0x34. */
    val lvGlyph: Int,
    /** What right-aligned numbers pad with: FireRed CHAR_SPACE, Emerald CHAR_SPACER. */
    val padGlyph: Int,
    val text: Long, val textShadow: Long,
    val male: Long, val maleShadow: Long,
    val female: Long, val femaleShadow: Long,
    val hpGreenTop: Long, val hpGreen: Long,
    val hpYellowTop: Long, val hpYellow: Long,
    val hpRedTop: Long, val hpRed: Long,
    val hpEmptyTop: Long, val hpEmpty: Long,
    /** The slot window. */
    val slotW: Int, val slotH: Int,
    // Text origins (sPartyBoxInfoRects).
    val nameX: Int, val nameY: Int,
    val levelX: Int, val levelY: Int,
    val genderX: Int, val genderY: Int,
    val hpX: Int, val maxHpX: Int, val hpY: Int,
    val barX: Int, val barY: Int, val barW: Int,
    /** Mon icon top-left while selected (it bounces from here). */
    val iconX: Int, val iconY: Int,
    /** AnimateSelectedPartyIcon's offset while NOT selected. */
    val iconRestDx: Int, val iconRestDy: Int,
    val ballX: Int, val ballY: Int,
    /** The ball moves down with the icon and name when the box grows taller
     * (it sits inside the frame, overlapping the icon) instead of staying
     * pinned to the frame's corner (FireRed/Emerald, where it overhangs it). */
    val ballFollowsIcon: Boolean = false,
    /** Status icon top-left (drawn in place of the level). */
    val statusX: Int, val statusY: Int,
    /** Frame columns identical to their neighbour in every state - repeated to widen the box. */
    val leftCol: Int, val midCol: Int,
    /**
     * Same for rows, when [stretchRows]. With a [band], these are plain rows
     * (side borders + fill) to insert copies of instead - its diagonals get
     * redrawn at the new height.
     */
    val topRow: Int, val midRow: Int, val bottomRow: Int,
    val stretchRows: Boolean,
    /** The frame's fill is split by two straight diagonals (the CFRU slot). */
    val band: DiagonalBand? = null,
    /** The box's visible extent inside its window. */
    val visibleW: Int, val visibleH: Int,
    /** Top-left of what's drawn, window-relative: covers sprites overhanging the box. */
    val regionX0: Int, val regionY0: Int,
    /** An egg's nickname as the game prints it. */
    val eggName: String,
    /** FireRed/Emerald print species names in caps. */
    val upperCaseNames: Boolean,
    /** The game draws a proper empty-slot box (slot_empty.png) for missing party members. */
    val hasEmptySlot: Boolean,
    /**
     * The game alpha-blends the slot windows over its backdrop (BLDCNT
     * BG0 -> BG1 at EVA 16 / EVB 8): what shows is box + backdrop / 2, so the
     * backdrop's grid lines show through faintly. Sprites aren't blended.
     */
    val blendOverBackdrop: Boolean = false,
)

/**
 * A frame whose fill is light | edge | dark band | edge | light, split by two
 * straight single-pixel diagonals from ([leftTopX], [top]) to
 * ([leftBottomX], [bottom]) and ([rightTopX], [top]) to ([rightBottomX],
 * [bottom]). No row of it repeats, so a taller frame gets plain rows inserted
 * and the diagonals redrawn between the same end points (steeper, still
 * straight) - scripts/gen_cfru_party_assets.py checks this rule redraws the
 * game's frames exactly at their own height. The empty slot is alternating
 * stripe/blank rows instead and grows by whole pairs at [emptyStripeRow].
 */
class DiagonalBand(
    val top: Int, val bottom: Int,
    val leftTopX: Int, val leftBottomX: Int,
    val rightTopX: Int, val rightBottomX: Int,
    val emptyStripeRow: Int,
)

/** Party-slot numbers every supported game shares (vanilla party_menu.c). */
object PartySlotLayout {
    const val GLYPH_H = 13 // DecompressGlyph_Small: height
    // First row of a FONT_SMALL cell capitals are drawn in: text follows the
    // frame's stretch rows by where its ink starts, not its cell origin.
    const val TEXT_TOP = 4
    // SpriteCB_BouncePartyMonIcon: the selected icon bobs y2 = -3 / +1 with its frame flips.
    const val ICON_BOUNCE_UP = -3; const val ICON_BOUNCE_DOWN = 1
    // sMonIconAnims frame durations (60Hz) by GetHPBarLevel: full, green,
    // yellow, red; fainted = still. On screen a pose lasts longer: partyIconPoseMs.
    val ICON_FRAME_TICKS = intArrayOf(6, 8, 14, 22)
}
