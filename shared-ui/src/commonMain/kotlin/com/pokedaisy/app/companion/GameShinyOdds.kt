package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.GameCalls
import com.pokedaisy.app.companion.data.Logs
import com.pokedaisy.app.companion.data.ShinyOdds
import kotlinx.atomicfu.atomic

/** One loaded game's verified target and original wild-foe roller. Observe/tick/close run between frames;
 * settings only update preferences and the desired odds. Hosts retain achievements and native frame scheduling. */
class GameShinyOdds(
    private val prefs: Prefs,
    private val core: GameCalls,
    debug: Boolean,
    private val allowed: () -> Boolean,
    private val changed: () -> Unit = {},
) {
    val choices = ShinyOdds.choices(debug)
    private val closed = atomic(false)
    private val target = atomic<ShinyOdds.FoeTarget?>(null)
    private val selected = atomic(valid(prefs.shinyOdds))
    private var roller: ShinyOdds.FoeRoller? = null
    private var revision by mutableIntStateOf(0)
    private fun valid(odds: Int) = odds.takeIf { it in choices } ?: ShinyOdds.NORMAL
    val odds: Int get() { revision; return selected.value }
    val supported: Boolean get() { revision; return !closed.value && allowed() && target.value != null }
    val active get() = supported && selected.value < ShinyOdds.NORMAL

    fun setOdds(odds: Int) {
        if (closed.value || odds !in choices) return
        prefs.shinyOdds = odds
        if (selected.getAndSet(odds) != odds) { revision++; changed() }
    }

    fun observe(next: ShinyOdds.FoeTarget?) {
        if (closed.value) return
        val preference = valid(prefs.shinyOdds)
        var updated = selected.getAndSet(preference) != preference
        val verified = next.takeIf { allowed() }
        if (target.value != verified) {
            target.value = verified
            roller = verified?.roller()
            updated = true
        }
        if (updated) { revision++; changed() }
    }

    fun tick(): Int {
        if (!active) return 0
        return runCatching { roller?.tick(selected.value, core::read, core::write) ?: 0 }
            .onFailure { Logs.i("shiny odds failed: $it") }
            .getOrDefault(0).also { n -> if (n > 0) Logs.i("shiny odds: foe made shiny (1 in ${selected.value})") }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val hadTarget = target.getAndSet(null) != null
        roller = null
        revision++
        if (hadTarget) changed()
    }
}
