package com.pokedaisy.app

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionOverlay
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PATCH A ROM, for the library: a picked `.bps` read ([BpsPatch]), its base ROM looked for among every ROM the app
 * knows ([RomPatcher.findBase]), then the details and APPLY PATCH; the result is written beside the player's other
 * games of its kind ([RomPatcher.outputDir]) and joins the library if the companion can read it. The work runs off
 * the UI thread; [step] is what [PatchWindow] shows.
 */
class RomPatchFlow(
    private val activity: androidx.activity.ComponentActivity,
    /** A patched ROM was written: rescan / refresh the library. */
    private val onAdded: (File) -> Unit,
) {
    /** What the patch says about itself, for the window's rows. */
    class PatchInfo(
        val fileName: String, val sourceSize: Long, val sourceCrc: Long, val targetSize: Long, val metadata: String,
    ) {
        /** The base ROM by name, when it's one of [RomPatcher.KNOWN_BASES]. */
        val knownBase: String? get() = RomPatcher.KNOWN_BASES[sourceCrc]
    }

    /** Where the patched ROM will go, worked out once the base ROM is found. */
    class Plan(val base: File, val baseLabel: String, val dir: File, val name: String)

    sealed class Step {
        /** Reading the patch, then looking for its base ROM ([progress] 0..1 over the ROMs read). */
        class Looking(val info: PatchInfo?, val progress: Float) : Step()
        /** None of the player's ROMs is the one the patch is for. */
        class NotFound(val info: PatchInfo) : Step()
        /** Found: the details and APPLY PATCH. */
        class Ready(val info: PatchInfo, val plan: Plan) : Step()
        class Patching(val info: PatchInfo, val plan: Plan, val progress: Float) : Step()
        /** Written ([existed]: the same patched ROM was there already). [verdict]: whether it's in the library now. */
        class Done(
            val info: PatchInfo, val plan: Plan, val file: File, val existed: Boolean,
            val verdict: CompanionSupport.Verdict, val label: String,
        ) : Step()
        class Failed(val message: String) : Step()
    }

    /** The window shows while non-null. */
    var step by mutableStateOf<Step?>(null)

    private var patch: BpsPatch? = null
    /** Bumped by every start and CLOSE: a search still running for a closed window never opens it again. */
    private var run = 0
    private val prefs by lazy { Prefs(activity) }
    /** The app's `roms/` (imported games): where a patched ROM goes when no linked folder fits. */
    private val importedDir by lazy { File(activity.getExternalFilesDir(null), "roms") }
    private val crcs by lazy { RomCrcs.cache(activity) }

    /** A patch file picked (the + menu's PATCH A ROM, a `.bps` opened with the app or added as a ROM file). */
    fun start(uri: Uri, name: String? = null) {
        if (RomIntake.isOwn(activity, uri)) return
        val fileName = name ?: RomUris.displayName(activity, uri) ?: "patch.bps"
        val token = ++run
        step = Step.Looking(null, 0f)
        fun post(block: () -> Unit) = postFor(token, block)
        Thread({
            val bytes = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    ByteArrayOutputStream().also { RomIntake.copyCapped(input, it) }.toByteArray()
                }
            }.getOrNull()
            if (bytes == null) {
                post { step = Step.Failed(tr("The patch file couldn't be read. Try picking it again.")) }
                return@Thread
            }
            look(bytes, fileName, token)
        }, "pokedaisy-patch-read").apply { isDaemon = true; start() }
    }

    /** [bytes] read as a patch and its base ROM looked for. Blocking. */
    private fun look(bytes: ByteArray, fileName: String, token: Int) {
        fun post(block: () -> Unit) = postFor(token, block)
        val p = try {
            BpsPatch.parse(bytes)
        } catch (e: BpsPatch.Error) {
            post { step = Step.Failed(messageFor(e.reason)) }
            return
        }
        patch = p
        val info = PatchInfo(fileName, p.sourceSize, p.sourceCrc, p.targetSize, p.metadata)
        post { step = Step.Looking(info, 0f) }
        var last = 0
        val base = RomPatcher.findBase(RomFolder.allRomFiles(activity, prefs), p.sourceSize, p.sourceCrc, crcs) { f ->
            val pct = (f * 100).toInt()
            if (pct != last) {
                last = pct
                post { step = Step.Looking(info, f) }
            }
        }
        if (base == null) {
            post { step = Step.NotFound(info) }
            return
        }
        val platform = RomPatcher.platformOf(base)
        val plan = Plan(
            base = base,
            baseLabel = GameTitles.label(activity, prefs, base),
            dir = RomPatcher.outputDir(platform, base, prefs.romsFolders, importedDir),
            name = RomPatcher.outputName(fileName, RomPatcher.extensionFor(base, platform)),
        )
        post { step = Step.Ready(info, plan) }
    }

    /** APPLY PATCH. */
    fun apply() {
        val ready = step as? Step.Ready ?: return
        val p = patch ?: return
        val info = ready.info
        val plan = ready.plan
        step = Step.Patching(info, plan, 0f)
        val token = run
        fun post(block: () -> Unit) = postFor(token, block)
        Thread({
            var last = -1
            val result = try {
                RomPatcher.apply(p, plan.base, plan.dir, plan.name) { f ->
                    val pct = (f * 100).toInt()
                    if (pct != last) {
                        last = pct
                        post { if (step is Step.Patching) step = Step.Patching(info, plan, f) }
                    }
                }
            } catch (e: BpsPatch.Error) {
                post { step = Step.Failed(messageFor(e.reason)) }
                return@Thread
            } catch (t: Throwable) {
                post { step = Step.Failed(tr("The patched ROM couldn't be saved. Check there's free space and try again.")) }
                return@Thread
            }
            val file = result.file
            val verdict = runCatching { CompanionSupport.verdict(file) }.getOrDefault(CompanionSupport.Verdict.UNSUPPORTED)
            val imported = file.absoluteFile.parentFile == importedDir.absoluteFile
            // The library lists every imported ROM: one the companion can't read stays out (HIDDEN GAMES can show it).
            if (imported && verdict == CompanionSupport.Verdict.UNSUPPORTED && !result.existed) {
                prefs.hiddenRoms = prefs.hiddenRoms + file.absolutePath
            }
            RomAdded.record(activity, listOf(file), initial = false)
            if (imported) RomFolder.checkImported(activity) else RomFolder.scan(activity, prefs)
            GameTitles.identify(activity, listOf(file))
            val label = GameTitles.label(activity, prefs, file)
            post {
                step = Step.Done(info, plan, file, result.existed, verdict, label)
                onAdded(file)
            }
        }, "pokedaisy-patch-apply").apply { isDaemon = true; start() }
    }

    /** CLOSE (never while it's writing). */
    fun dismiss() {
        if (step is Step.Patching) return
        step = null
        patch = null
        run++
    }

    /** [block] on the UI thread, unless the window it was for has been closed (or another patch started) since. */
    private fun postFor(token: Int, block: () -> Unit) = activity.runOnUiThread { if (token == run) block() }

    companion object {
        fun messageFor(reason: BpsPatch.Reason): String = when (reason) {
            BpsPatch.Reason.NOT_BPS -> tr("This isn't a BPS patch. PATCH A ROM takes .bps files.")
            BpsPatch.Reason.DAMAGED -> tr("This patch file is damaged or cut short. Download it again and try once more.")
            BpsPatch.Reason.TOO_BIG -> tr("This patch is too big to apply on this device.")
            BpsPatch.Reason.WRONG_SOURCE, BpsPatch.Reason.BAD_RESULT ->
                tr("The patched ROM didn't come out right, so nothing was saved. The patch may be damaged: download it again.")
        }

        /** "DD88761C". */
        fun crcText(crc: Long) = "%08X".format(crc)

        /** "16 MB", "1 MB", "512 KB". */
        fun sizeText(bytes: Long): String =
            if (bytes >= 1 shl 20 && bytes % (1 shl 20) == 0L) "${bytes shr 20} MB"
            else if (bytes >= 1 shl 20) "%.1f MB".format(bytes / 1048576.0)
            else "${(bytes + 1023) shr 10} KB"
    }
}

/** [flow]'s window: the patch, its base ROM and where the result goes, then APPLY PATCH (a bar while it runs). */
@Composable
fun PatchWindow(
    flow: RomPatchFlow, m: GbaTextMetrics, small: GbaTextMetrics,
    /** PLAY on a patched game the library has; null = none (Settings: the library is a BACK away). */
    onPlay: ((File) -> Unit)?, onAskSupport: (File) -> Unit,
) {
    val step = flow.step ?: return
    OptionOverlay(onDismiss = { flow.dismiss() }, modifier = Modifier.widthIn(max = 900.dp)) {
        Column {
            OptionTitleWindow(tr("PATCH A ROM"), m, onBack = { flow.dismiss() }.takeIf { step !is RomPatchFlow.Step.Patching })
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 6, vertical = m.u * 2)) {
                    // On a small screen everything above the buttons scrolls, so the buttons stay on screen.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        PatchBody(step, m, small)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = m.u * 6, bottom = m.u * 2),
                        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                    ) {
                        when (step) {
                            is RomPatchFlow.Step.Ready -> {
                                OptionButton(tr("CANCEL"), m, modifier = Modifier.weight(1f), onClick = { flow.dismiss() })
                                OptionButton(tr("APPLY PATCH"), m, emphasis = true, modifier = Modifier.weight(1f), onClick = { flow.apply() })
                            }
                            is RomPatchFlow.Step.Patching ->
                                OptionButton(tr("APPLY PATCH"), m, emphasis = true, enabled = false, modifier = Modifier.weight(1f), onClick = {})
                            is RomPatchFlow.Step.Done -> {
                                val listed = step.verdict != CompanionSupport.Verdict.UNSUPPORTED
                                if (!listed) {
                                    OptionButton(tr("ASK FOR SUPPORT"), m, modifier = Modifier.weight(1f), onClick = { onAskSupport(step.file) })
                                }
                                OptionButton(tr("CLOSE"), m, emphasis = !listed || onPlay == null, modifier = Modifier.weight(1f), onClick = { flow.dismiss() })
                                if (listed && onPlay != null) {
                                    OptionButton(tr("PLAY"), m, emphasis = true, modifier = Modifier.weight(1f), onClick = { flow.dismiss(); onPlay(step.file) })
                                }
                            }
                            else -> OptionButton(tr("CLOSE"), m, modifier = Modifier.weight(1f), onClick = { flow.dismiss() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchBody(step: RomPatchFlow.Step, m: GbaTextMetrics, small: GbaTextMetrics) {
    fun patchRows(info: RomPatchFlow.PatchInfo) = listOfNotNull(
        tr("FILE") to info.fileName,
        tr("MADE FOR") to (info.knownBase ?: tr("A ROM with CRC32 {0}", RomPatchFlow.crcText(info.sourceCrc))),
        // The patch's own notes (an author, a version), when it has any.
        info.metadata.takeIf { it.isNotEmpty() }?.let { tr("NOTES") to it.take(200) },
    )

    fun baseRows(plan: RomPatchFlow.Plan, info: RomPatchFlow.PatchInfo) = listOf(
        tr("GAME") to plan.baseLabel,
        tr("FILE") to plan.base.absolutePath,
        "CRC32" to tr("{0} (matches)", RomPatchFlow.crcText(info.sourceCrc)),
    )

    fun outRows(plan: RomPatchFlow.Plan, info: RomPatchFlow.PatchInfo, file: File? = null) = listOf(
        tr("NAME") to (file?.name ?: plan.name),
        tr("SAVED TO") to (file?.parent ?: plan.dir.absolutePath),
        tr("SIZE") to RomPatchFlow.sizeText(info.targetSize),
    )

    when (step) {
        is RomPatchFlow.Step.Looking -> {
            step.info?.let { InfoSection(GameInfo.Section(tr("PATCH"), patchRows(it)), first = true, m, small) }
            Spacer(Modifier.height(m.u * 6))
            Message(if (step.info == null) tr("Reading the patch…") else tr("Looking for its base ROM… {0}%", (step.progress * 100).toInt()), m)
            Spacer(Modifier.height(m.u * 3))
            SyncProgressBar(step.progress, m)
        }
        is RomPatchFlow.Step.NotFound -> {
            InfoSection(GameInfo.Section(tr("PATCH"), patchRows(step.info)), first = true, m, small)
            Spacer(Modifier.height(m.u * 6))
            Message(tr("BASE ROM NOT FOUND"), m, value = true)
            Spacer(Modifier.height(m.u * 2))
            Message(
                step.info.knownBase?.let {
                    tr("This patch is for {0} (CRC32 {1}), and no ROM in your library has that checksum. Add that ROM to your library or a ROMs folder, then try again.", it, RomPatchFlow.crcText(step.info.sourceCrc))
                } ?: tr("This patch is for a {0} ROM with CRC32 {1}, and no ROM in your library has that checksum. Add that ROM to your library or a ROMs folder, then try again.", RomPatchFlow.sizeText(step.info.sourceSize), RomPatchFlow.crcText(step.info.sourceCrc)),
                small,
            )
        }
        is RomPatchFlow.Step.Ready -> {
            // Two columns, like INFO: where the patched game goes is in view without scrolling on a landscape screen.
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 12)) {
                Column(Modifier.weight(1f)) {
                    // MADE FOR is the base ROM below.
                    InfoSection(GameInfo.Section(tr("PATCH"), patchRows(step.info).filter { it.first != tr("MADE FOR") }), first = true, m, small)
                    InfoSection(GameInfo.Section(tr("BASE ROM"), baseRows(step.plan, step.info)), first = false, m, small)
                }
                Column(Modifier.weight(1f)) {
                    InfoSection(GameInfo.Section(tr("PATCHED ROM"), outRows(step.plan, step.info)), first = true, m, small)
                    Spacer(Modifier.height(m.u * 6))
                    Message(tr("Your base ROM stays as it is: the patched game is saved as a new file."), small)
                }
            }
        }
        is RomPatchFlow.Step.Patching -> {
            InfoSection(GameInfo.Section(tr("PATCHED ROM"), outRows(step.plan, step.info)), first = true, m, small)
            Spacer(Modifier.height(m.u * 6))
            Message(tr("Patching… {0}%", (step.progress * 100).toInt()), m)
            Spacer(Modifier.height(m.u * 3))
            SyncProgressBar(step.progress, m)
        }
        is RomPatchFlow.Step.Done -> {
            Message(if (step.existed) tr("ALREADY PATCHED") else tr("PATCHED!"), m, value = true)
            Spacer(Modifier.height(m.u * 2))
            Message(
                if (step.existed) tr("This patched ROM was already in {0}, so it was kept as it is.", step.file.parent ?: "")
                else tr("{0} is saved in {1}.", step.file.name, step.file.parent ?: ""),
                small,
            )
            Spacer(Modifier.height(m.u * 2))
            Message(
                when (step.verdict) {
                    CompanionSupport.Verdict.SUPPORTED, CompanionSupport.Verdict.MATCHED -> tr("{0} is in your library.", step.label)
                    CompanionSupport.Verdict.PARTIAL -> tr("{0} is in your library. The second screen shows part of it.", step.label)
                    CompanionSupport.Verdict.TRYABLE ->
                        tr("{0} is in your library. The second screen doesn't know this game yet, but it can try to read it as the game it's built on.", step.label)
                    CompanionSupport.Verdict.UNSUPPORTED ->
                        tr("The second screen doesn't support {0}, so it isn't in your library.", step.label)
                },
                small,
            )
        }
        is RomPatchFlow.Step.Failed -> {
            Message(tr("PATCH FAILED"), m, value = true)
            Spacer(Modifier.height(m.u * 2))
            Message(step.message, small)
        }
    }
}

@Composable
private fun Message(text: String, m: GbaTextMetrics, value: Boolean = false) {
    if (value) GbaText(text, OptionColors.value, OptionColors.valueShadow, m, maxLines = Int.MAX_VALUE)
    else GbaText(text, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE)
}
