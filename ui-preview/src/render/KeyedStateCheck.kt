package render

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.pokedaisy.app.companion.ui.produceKeyedState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.system.exitProcess

/**
 * `gradle keyedStateCheck`: GitHub #229's sequence on the bare Compose runtime (the app's unit tests can't run it:
 * Android's runtime calls android.os.Trace). The DEX entry page composes with species 0 before its entry loads, AREA's
 * state starts from that species' cached list (empty once the ROM's table is read), then the real species arrives:
 * the "cached, else load" producer must load it. [produceKeyedState] starts over; Compose's produceState kept the
 * empty list - checked too, so this fails if the runtime ever changes under the helper's reason to exist.
 */
fun main() {
    // The app sets this at start (PokeDaisyApp): the screens find the art under it.
    installPreviewHosts()
    val keyed = shownFor { species, show ->
        val v by produceKeyedState(cached(species), species) { if (value == null && species > 0) value = "spots of $species" }
        show(v ?: "reading")
    }
    val plain = shownFor { species, show ->
        val v by produceState(cached(species), species) { if (value == null && species > 0) value = "spots of $species" }
        show(v ?: "reading")
    }
    println("produceKeyedState: $keyed")
    println("produceState:      $plain")
    val ok = keyed.last() == "spots of 25" && plain == listOf("none")
    println(if (ok) "keyed state OK" else "keyed state FAILED")
    exitProcess(if (ok) 0 else 1)
}

// The cache: species 0 has no spots (an empty list, not "unknown"); 25 isn't read yet.
private fun cached(species: Int): String? = if (species == 0) "none" else null

private object Root

private class NoApplier : AbstractApplier<Any>(Root) {
    override fun insertTopDown(index: Int, instance: Any) {}
    override fun insertBottomUp(index: Int, instance: Any) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

/** What [area] shows for species 0, then for 25 once it's set (each distinct value once, in order). */
private fun shownFor(area: @Composable (species: Int, show: (String) -> Unit) -> Unit): List<String> = runBlocking {
    val clock = BroadcastFrameClock()
    val shown = ArrayList<String>()
    var species by mutableStateOf(0)
    launch(clock) {
        val recomposer = Recomposer(coroutineContext)
        launch { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoApplier(), recomposer)
        composition.setContent { area(species) { shown += it } }
        suspend fun settle() = repeat(20) {
            Snapshot.sendApplyNotifications()
            clock.sendFrame(0L)
            yield()
        }
        settle()
        species = 25
        settle()
        composition.dispose()
        recomposer.cancel()
    }.join()
    shown.distinct()
}
