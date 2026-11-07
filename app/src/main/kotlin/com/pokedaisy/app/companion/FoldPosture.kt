package com.pokedaisy.app.companion

/**
 * How a foldable phone is held, as SETTINGS > FOLD LAYOUT uses it (PokeDaisyActivity reads it from Jetpack
 * WindowManager's FoldingFeature):
 * - [Kind.TABLETOP]: half open with the hinge across the screen, like a laptop or a DS. The game goes above the hinge,
 *   the companion below it (PortraitPanel's tabletop mode) - the two-screen handhelds' layout on one folding screen.
 * - [Kind.BOOK]: half open with the hinge down the middle, like a book. The game on the left page, the companion on the
 *   right (SidePanel's book mode).
 * Flat open or closed there is no posture: the usual one-screen layouts apply. A hinge that hides pixels between two
 * screens (a Surface Duo spanned) counts even flat: nothing may sit under it.
 *
 * [start] / [end] are where the hinge begins and ends along the split (px in the game stage's own coordinates: y for
 * TABLETOP, x for BOOK); a hinge that's only a line has start == end.
 */
data class FoldPosture(val kind: Kind, val start: Int, val end: Int) {
    enum class Kind { TABLETOP, BOOK }

    companion object {
        /** Neither side of the hinge may be smaller than this share of the screen (a hinge near an edge isn't a fold to
         * lay the game out around). */
        const val MIN_SIDE = 0.25f

        /**
         * The posture for a FoldingFeature reported in window coordinates ([left]..[bottom]), the stage at
         * ([stageX], [stageY]) in the window and [stageW] x [stageH] big; null = lay out as usual.
         */
        fun of(
            halfOpened: Boolean, separating: Boolean, horizontal: Boolean,
            left: Int, top: Int, right: Int, bottom: Int,
            stageX: Int, stageY: Int, stageW: Int, stageH: Int,
        ): FoldPosture? {
            if (!halfOpened && !separating) return null
            if (stageW <= 0 || stageH <= 0) return null
            val kind = if (horizontal) Kind.TABLETOP else Kind.BOOK
            val size = if (horizontal) stageH else stageW
            val start = (if (horizontal) top - stageY else left - stageX).coerceIn(0, size)
            val end = (if (horizontal) bottom - stageY else right - stageX).coerceIn(start, size)
            if (start < size * MIN_SIDE || size - end < size * MIN_SIDE) return null
            return FoldPosture(kind, start, end)
        }
    }
}
