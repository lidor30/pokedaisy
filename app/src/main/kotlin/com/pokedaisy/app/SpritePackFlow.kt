package com.pokedaisy.app

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.SpritePack
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionOverlay
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import okio.Path.Companion.toOkioPath

/**
 * TOOLS > SPRITE PACK ([SpritePack]): the picked file read as a zip off the UI thread, its graphics checked and kept
 * where a ROM scan keeps them, and what came in shown in [SpritePackWindow]. The next game that offers PLAYER LOOK /
 * TEXT LOOK / FOLLOWER sees them.
 */
class SpritePackFlow(private val activity: androidx.activity.ComponentActivity) {
    sealed class Step {
        object Loading : Step()
        class Done(val result: SpritePack.Result) : Step()
        class Failed(val message: String) : Step()
    }

    /** The window shows while non-null. */
    var step by mutableStateOf<Step?>(null)
    private var run = 0

    fun start(uri: Uri) {
        if (RomIntake.isOwn(activity, uri)) return
        val token = ++run
        step = Step.Loading
        Thread({
            val next = try {
                val files = activity.contentResolver.openInputStream(uri)?.use { read(it) }
                    ?: throw SpritePack.NotAPack("This file couldn't be read. Try picking it again.")
                Step.Done(SpritePack.install(activity.filesDir.toOkioPath(), files))
            } catch (e: SpritePack.NotAPack) {
                Step.Failed(e.message ?: "This file isn't a sprite pack.")
            } catch (t: Throwable) {
                Log.w("pokedaisy", "sprite pack: couldn't load", t)
                Step.Failed("This file couldn't be read. Try picking it again.")
            }
            activity.runOnUiThread { if (token == run) step = next }
        }, "pokedaisy-sprite-pack").apply { isDaemon = true; start() }
    }

    fun dismiss() {
        run++
        step = null
    }

    companion object {
        /**
         * A pack's files by path: the manifest and every `.bin`, within [SpritePack]'s limits (an entry past the size cap is
         * skipped; past the count or total, the read stops: that's no pack of ours). Not a zip = [SpritePack.NotAPack].
         */
        fun read(input: InputStream): Map<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            var total = 0L
            var entries = 0
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++entries > SpritePack.MAX_FILES) throw SpritePack.NotAPack("This file is too big to be a sprite pack.")
                    val name = e.name
                    if (e.isDirectory || !(name == SpritePack.MANIFEST || name.endsWith(".bin"))) continue
                    val bytes = capped(z, SpritePack.MAX_FILE_BYTES) ?: continue
                    total += bytes.size
                    if (total > SpritePack.MAX_TOTAL_BYTES) throw SpritePack.NotAPack("This file is too big to be a sprite pack.")
                    out[name] = bytes
                }
            }
            if (entries == 0) throw SpritePack.NotAPack("This file isn't a sprite pack.")
            return out
        }

        /** The current entry's bytes, or null if it's over [max]. */
        private fun capped(input: InputStream, max: Int): ByteArray? {
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) return buf.toByteArray()
                if (buf.size() + n > max) return null
                buf.write(chunk, 0, n)
            }
        }
    }
}

/** [flow]'s window: LOADING, then what the pack added (or why it couldn't). */
@Composable
fun SpritePackWindow(flow: SpritePackFlow, m: GbaTextMetrics, small: GbaTextMetrics) {
    val step = flow.step ?: return
    val close = { flow.dismiss() }.takeIf { step !is SpritePackFlow.Step.Loading }
    OptionOverlay(onDismiss = { close?.invoke() }, modifier = Modifier.widthIn(max = 640.dp)) {
        Column {
            OptionTitleWindow(tr("SPRITE PACK"), m, onBack = close)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 6, vertical = m.u * 4)) {
                    for (line in spritePackLines(step)) {
                        GbaText(line, OptionColors.label, OptionColors.labelShadow, small, maxLines = 4)
                        Spacer(Modifier.height(m.u * 3))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = m.u * 3), horizontalArrangement = Arrangement.End) {
                        OptionButton(tr("CLOSE"), m, emphasis = true, enabled = close != null, onClick = { close?.invoke() })
                    }
                }
            }
        }
    }
}

/** What [SpritePackWindow] says, translated. */
@Composable
private fun spritePackLines(step: SpritePackFlow.Step): List<String> = when (step) {
    SpritePackFlow.Step.Loading -> listOf(tr("Loading the sprite pack..."))
    is SpritePackFlow.Step.Failed -> listOf(tr(step.message))
    is SpritePackFlow.Step.Done -> {
        val r = step.result
        buildList {
            if (r.nothingNew) add(tr("You already have everything in this sprite pack."))
            else {
                add(tr("Sprite pack loaded."))
                if (r.heroes.isNotEmpty()) add(tr("Heroes: {0}", r.heroes.joinToString(", ") { if (it.engine == com.pokedaisy.app.companion.data.LookEngine.RUBY) "${it.label} (R/S)" else it.label }))
                if (r.textStyles.isNotEmpty()) add(tr("Text looks: {0}", r.textStyles.joinToString(", ") { it.name }))
                if (r.mons > 0) add(tr("Pokémon to follow you or dress up as: {0}", r.mons))
                add(tr("Pick them in a game's SETTINGS > MAKEOVER."))
            }
            if (r.refused > 0) add(tr("{0} files in it aren't graphics PokeDaisy knows, so they were left out.", r.refused))
        }
    }
}
