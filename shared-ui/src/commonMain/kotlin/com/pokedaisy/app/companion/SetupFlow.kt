package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Navigation for one setup run. Native hosts supply their supported steps and entry/exit effects.
 * Capture [visit] when presenting a page, then pass it back on navigation: callbacks from an old
 * page (including a previous visit to the same step) cannot advance or finish this run.
 */
class SetupFlow<S>(
    steps: List<S>,
    fromSettings: Boolean = false,
    private val entered: (S) -> Unit = {},
    private val exited: (Exit) -> Unit = {},
) {
    enum class Exit { COMPLETE, BACK }
    class Visit<S> internal constructor(val step: S)

    val steps = steps.toList().also {
        require(it.isNotEmpty() && it.distinct().size == it.size) { "Setup needs distinct steps" }
    }
    var visit by mutableStateOf(Visit(this.steps.first()))
        private set
    var active by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
    var fromSettings by mutableStateOf(fromSettings)
    val step get() = visit.step
    val index get() = steps.indexOf(step)
    val canGoBack get() = index > 0 || fromSettings
    val last get() = index == steps.lastIndex

    fun next(page: Visit<S>) {
        if (!accepts(page)) return
        if (last) finish(Exit.COMPLETE) else select(steps[index + 1])
    }

    /** Hardware BACK can leave the first step even when the page has no BACK button. */
    fun back(page: Visit<S>) {
        if (!accepts(page)) return
        if (index == 0) finish(Exit.BACK) else select(steps[index - 1])
    }

    /** Select a supported step, also used by preview fixtures. */
    fun select(step: S) {
        require(step in steps) { "Unsupported setup step" }
        if (!active || busy || step == this.step) return
        visit = Visit(step)
        entered(step)
    }

    /** Retire a replaced/disposed run without completing setup or invoking native exit effects. */
    fun close() { active = false }

    private fun accepts(page: Visit<S>) = active && !busy && page === visit

    private fun finish(reason: Exit) {
        close()
        exited(reason)
    }
}
