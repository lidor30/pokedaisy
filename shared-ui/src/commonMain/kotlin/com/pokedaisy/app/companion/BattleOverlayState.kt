package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.hasData

/** Keep the battle overlay available between command reads, but never carry readiness into a new battle. */
class BattleOverlayState {
    var read by mutableStateOf(false)
        private set

    fun observe(inBattle: Boolean, inputState: Int) {
        read = inBattle && (read || inputState != BATTLE_INPUT_NONE)
    }
}

fun battleButtonsWanted(enabled: Boolean, singleScreen: Boolean, portrait: Boolean, snapshot: SnapshotView) =
    enabled && singleScreen && !portrait && snapshot.hasData && !snapshot.unsupported && snapshot.blocked == null &&
        snapshot.inBattle && !snapshot.isDoubleBattle

/** Rightmost lane in the supplied vertical band that leaves touch controls reachable. */
fun battleButtonsLane(box: Rect, width: Float, avoid: List<Rect>): Rect? {
    val obstacles = avoid.filter { it.bottom > box.top && it.top < box.bottom && it.right > box.left && it.left < box.right }
    for (right in (listOf(box.right) + obstacles.map { it.left }).distinct().sortedDescending()) {
        val left = right - width
        if (left >= box.left && obstacles.none { it.right > left && it.left < right })
            return Rect(left, box.top, right, box.bottom)
    }
    return null
}

/** The first clear band for a compact row anchored to the right edge. All dimensions are in stage pixels. */
fun battleButtonsRow(box: Rect, width: Float, height: Float, avoid: List<Rect>): Rect? {
    val left = box.right - width
    if (left < box.left || height > box.height) return null
    val obstacles = avoid.filter { it.left < box.right && it.right > left && it.top < box.bottom && it.bottom > box.top }
    val tops = (listOf(box.top) + obstacles.map { it.bottom }).sorted()
    for (top in tops) {
        val bottom = top + height
        if (bottom <= box.bottom && obstacles.none { it.top < bottom && it.bottom > top }) {
            return Rect(left, top, box.right, bottom)
        }
    }
    return null
}
