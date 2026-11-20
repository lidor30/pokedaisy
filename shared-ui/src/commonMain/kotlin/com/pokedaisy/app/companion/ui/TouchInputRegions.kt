package com.pokedaisy.app.companion.ui

import androidx.compose.ui.unit.IntRect

/** Subtract UI chrome from disjoint pad windows, preserving a single window per finger. */
fun excludeTouchRegions(regions: List<IntRect>, reserved: List<IntRect>): List<IntRect> {
    var remaining = regions
    for (cut in reserved) {
        remaining = remaining.flatMap { r ->
            val l = maxOf(r.left, cut.left)
            val t = maxOf(r.top, cut.top)
            val right = minOf(r.right, cut.right)
            val bottom = minOf(r.bottom, cut.bottom)
            if (l >= right || t >= bottom) listOf(r)
            else buildList {
                if (r.top < t) add(IntRect(r.left, r.top, r.right, t))
                if (bottom < r.bottom) add(IntRect(r.left, bottom, r.right, r.bottom))
                if (r.left < l) add(IntRect(r.left, t, l, bottom))
                if (right < r.right) add(IntRect(right, t, r.right, bottom))
            }
        }
    }
    return remaining
}
