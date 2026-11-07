package com.pokedaisy.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.drawLayeredBox
import com.pokedaisy.app.companion.ui.drawPixelRoundRect
import com.pokedaisy.app.companion.ui.inPx
import java.io.File

/**
 * One pass of [CoverArtSync.fetchOne] over the whole library (ScreenScraper's media, then
 * covers), with progress Compose can watch - Settings > Cover Art's SAVE / REPLACE ALL,
 * Settings > ScreenScraper, first-time setup's steps and the update's ScreenScraper popup.
 * Runs off the UI thread (hashing + network I/O).
 */
class CoverSync {
    var running by mutableStateOf(false)
        private set
    var replace by mutableStateOf(false)
        private set
    var index by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    /** Newest first: each ROM's label and outcome. */
    val log = mutableStateListOf<Pair<String, CoverArtSync.Status>>()
    /** Why ScreenScraper stopped partway (wrong password, the day's quota...); null = it didn't. */
    var screenScraperError by mutableStateOf<String?>(null)
        private set

    /** [replace]: re-fetch every ROM's cover, manual ones included; one with no
     * SteamGridDB match (or a failed download) keeps what it has. */
    fun start(activity: ComponentActivity, prefs: Prefs, replace: Boolean, onFinished: () -> Unit = {}) {
        if (running) return
        log.clear()
        index = 0
        total = 0
        this.replace = replace
        screenScraperError = null
        running = true
        Thread({
            val coversDir = File(activity.getExternalFilesDir(null) ?: activity.filesDir, "covers").apply { mkdirs() }
            val roms = RomFolder.libraryRoms(activity, prefs)
            val ss = CoverArtSync.ScreenScraperPass.of(prefs)
            roms.forEachIndexed { i, rom ->
                // The game's own name to search by, whatever GAME NAMES shows.
                val label = prefs.romDisplayName(rom) ?: GameTitles.defaultLabel(activity, rom)
                val status = CoverArtSync.fetchOne(
                    rom, File(coversDir, "${rom.name}.png"), prefs, replace, ss = ss,
                    mediaRoot = GameMedia.root(activity), label = label,
                )
                val stopped = ss.stopped?.message
                // A replaced manual cover is an auto one now (RESET COVER no longer applies).
                if (status == CoverArtSync.Status.REPLACED) prefs.setRomCoverManual(rom, false)
                activity.runOnUiThread {
                    index = i + 1
                    total = roms.size
                    screenScraperError = stopped
                    log.add(0, label to status)
                }
            }
            activity.runOnUiThread {
                running = false
                onFinished()
            }
        }, "pokedaisy-cover-sync").apply { isDaemon = true; start() }
    }
}

/** [sync]'s progress window: a count, a bar and each ROM's outcome. Nothing until it has run. */
@Composable
fun CoverSyncPanel(sync: CoverSync, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier = Modifier) {
    if (!sync.running && sync.log.isEmpty()) return
    OptionListWindow(m, modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = m.u * 4)) {
            CoverSyncStatus(sync, m)
            Spacer(Modifier.height(m.u * 3))
            sync.log.forEach { (label, status) -> SyncLogRow(label, status, small) }
        }
    }
}

/** [sync]'s count line and bar alone, without the per-ROM log. */
@Composable
fun CoverSyncStatus(sync: CoverSync, m: GbaTextMetrics) {
    val fetched = sync.log.count { it.second == CoverArtSync.Status.REPLACED || it.second == CoverArtSync.Status.FETCHED }
    GbaText(
        when {
            sync.running && sync.replace -> tr("REPLACING COVERS… {0} / {1}", sync.index, sync.total)
            sync.running -> tr("FETCHING COVERS… {0} / {1}", sync.index, sync.total)
            sync.replace -> tr("DONE — {0} NEW, {1} UNCHANGED", fetched, sync.total - fetched)
            else -> tr("DONE — {0} ROM(S) CHECKED, {1} NEW COVER(S)", sync.total, fetched)
        },
        OptionColors.label, OptionColors.labelShadow, m,
    )
    Spacer(Modifier.height(m.u * 3))
    SyncProgressBar(fraction = if (sync.total > 0) sync.index.toFloat() / sync.total else 0f, m = m)
    sync.screenScraperError?.let {
        Spacer(Modifier.height(m.u * 3))
        GbaText(tr("ScreenScraper stopped: {0}", it).uppercase(), OptionColors.value, OptionColors.valueShadow, m, maxLines = 2)
    }
}

/** A white framed bar filled in the value red, like the game's HP / EXP bars. */
@Composable
fun SyncProgressBar(fraction: Float, m: GbaTextMetrics) {
    val u = m.u
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The 3u frame on each side leaves a 4u-tall fill.
            .height(u * 10)
            .drawBehind {
                val px = u.toPx()
                drawLayeredBox(OptionColors.titleLayers.inPx(px), OptionColors.titleFill, radius = 3 * px)
                val inset = 3 * px
                val w = (size.width - 2 * inset) * fraction.coerceIn(0f, 1f)
                if (w > 0f) {
                    drawPixelRoundRect(
                        OptionColors.value, Offset(inset, inset), Size(w, size.height - 2 * inset),
                        radius = px,
                    )
                }
            },
    )
}

@Composable
private fun SyncLogRow(label: String, status: CoverArtSync.Status, small: GbaTextMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = small.u),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GbaText(label, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f))
        GbaText(status.label(), status.tint(), OptionColors.labelShadow, small)
    }
}

private fun CoverArtSync.Status.label(): String = when (this) {
    CoverArtSync.Status.FETCHED -> tr("FETCHED")
    CoverArtSync.Status.REPLACED -> tr("REPLACED")
    CoverArtSync.Status.KEPT -> tr("KEPT (NO NEW ART)")
    CoverArtSync.Status.ALREADY_CACHED -> tr("CACHED")
    CoverArtSync.Status.MANUAL_COVER -> tr("MANUAL")
    CoverArtSync.Status.UNKNOWN_GAME -> tr("NOT SUPPORTED")
    CoverArtSync.Status.FETCH_FAILED -> tr("NO ICON FOUND")
    CoverArtSync.Status.NO_API_KEY -> tr("NO API KEY")
}

private fun CoverArtSync.Status.tint(): Color = when (this) {
    CoverArtSync.Status.FETCHED, CoverArtSync.Status.REPLACED -> Color(0xFF208038)
    CoverArtSync.Status.FETCH_FAILED, CoverArtSync.Status.NO_API_KEY -> OptionColors.value
    else -> OptionColors.muted
}
