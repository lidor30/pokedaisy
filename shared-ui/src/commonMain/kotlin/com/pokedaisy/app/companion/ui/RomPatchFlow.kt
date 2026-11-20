package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.pokedaisy.app.BpsPatch
import com.pokedaisy.app.RomPatchFiles
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import okio.Source

/** Shared library patch transaction. The hosts supply the candidate ROMs and library bookkeeping. */
class RomPatchFlow(
    private val find: (BpsPatch, String, (Float) -> Unit) -> Plan?,
    private val write: (BpsPatch, Plan, (Float) -> Unit) -> Result,
    private val onAdded: (Result) -> Unit,
) {
    class PatchInfo(
        val fileName: String, val sourceSize: Long, val sourceCrc: Long, val targetSize: Long, val metadata: String,
    ) {
        val knownBase: String? get() = RomPatchFiles.KNOWN_BASES[sourceCrc]
    }

    class Plan(val base: Path, val baseLabel: String, val dir: Path, val name: String)
    enum class Support { LISTED, PARTIAL, TRYABLE, UNLISTED }
    class Result(val file: Path, val existed: Boolean, val support: Support, val label: String)

    sealed class Step {
        class Looking(val info: PatchInfo?, val progress: Float) : Step()
        class NotFound(val info: PatchInfo) : Step()
        class Ready(val info: PatchInfo, val plan: Plan) : Step()
        class Patching(val info: PatchInfo, val plan: Plan, val progress: Float) : Step()
        class Done(val result: Result) : Step()
        class Failed(val message: String) : Step()
    }

    var step by mutableStateOf<Step?>(null)
        internal set

    private val ui = CoroutineScope(Dispatchers.Main)
    private var work: Job? = null
    private var patch: BpsPatch? = null
    private var run = 0

    fun start(fileName: String, read: () -> Source) {
        if (step is Step.Patching) return
        dismiss()
        val token = run
        step = Step.Looking(null, 0f)
        work = ui.launch {
            step = try {
                val parsed = withContext(Dispatchers.IO) { BpsPatch.parse(RomPatchFiles.readPatch(read())) }
                val info = PatchInfo(fileName, parsed.sourceSize, parsed.sourceCrc, parsed.targetSize, parsed.metadata)
                step = Step.Looking(info, 0f)
                val progress = progress(token) {
                    if (step is Step.Looking) step = Step.Looking(info, it)
                }
                val plan = withContext(Dispatchers.IO) { find(parsed, fileName, progress) }
                if (plan == null) Step.NotFound(info) else {
                    patch = parsed
                    Step.Ready(info, plan)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: BpsPatch.Error) {
                Step.Failed(messageFor(error.reason))
            } catch (_: Exception) {
                Step.Failed(tr("The patch file couldn't be read. Try picking it again."))
            }
        }
    }

    fun apply() {
        val ready = step as? Step.Ready ?: return
        val parsed = patch ?: return
        val token = run
        step = Step.Patching(ready.info, ready.plan, 0f)
        val progress = progress(token) {
            if (step is Step.Patching) step = Step.Patching(ready.info, ready.plan, it)
        }
        work = ui.launch {
            val result = try {
                withContext(Dispatchers.IO) { write(parsed, ready.plan, progress) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: BpsPatch.Error) {
                step = Step.Failed(messageFor(error.reason))
                return@launch
            } catch (_: Exception) {
                step = Step.Failed(tr("The patched ROM couldn't be saved. Check there's free space and try again."))
                return@launch
            } finally {
                if (run == token) patch = null
            }
            step = Step.Done(result)
            onAdded(result)
        }
    }

    fun dismiss() {
        if (step !is Step.Patching) close()
    }

    /** Discard UI callbacks during host teardown. A write that already started publishes atomically. */
    fun close() {
        run++
        work?.cancel()
        work = null
        step = null
        patch = null
    }

    private fun progress(token: Int, show: (Float) -> Unit): (Float) -> Unit {
        var last = -1
        return { value ->
            val percent = (value * 100).toInt()
            if (percent != last) {
                last = percent
                ui.launch { if (run == token) show(value) }
            }
        }
    }

    companion object {
        fun messageFor(reason: BpsPatch.Reason): String = when (reason) {
            BpsPatch.Reason.NOT_BPS -> tr("This isn't a BPS patch. PATCH A ROM takes .bps files.")
            BpsPatch.Reason.DAMAGED -> tr("This patch file is damaged or cut short. Download it again and try once more.")
            BpsPatch.Reason.TOO_BIG -> tr("This patch is too big to apply on this device.")
            BpsPatch.Reason.WRONG_SOURCE, BpsPatch.Reason.BAD_RESULT ->
                tr("The patched ROM didn't come out right, so nothing was saved. The patch may be damaged: download it again.")
        }

        fun crcText(crc: Long) = crc.toString(16).uppercase().padStart(8, '0')

        fun sizeText(bytes: Long): String {
            val mb = 1L shl 20
            return when {
                bytes < mb -> "${(bytes + 1023) shr 10} KB"
                bytes % mb == 0L -> "${bytes / mb} MB"
                else -> {
                    val tenths = (bytes * (10.0 / mb)).roundToInt()
                    "${tenths / 10}.${tenths % 10} MB"
                }
            }
        }
    }
}

/** [flow]'s window: the patch, its base ROM and where the result goes, then APPLY PATCH (a bar while it runs). */
@Composable
fun PatchWindow(
    flow: RomPatchFlow, m: GbaTextMetrics, small: GbaTextMetrics,
    /** PLAY on a patched game the library has; null = none (Settings: the library is a BACK away). */
    onPlay: ((Path) -> Unit)?, onAskSupport: ((Path) -> Unit)? = null,
    describePath: (Path) -> String = Path::toString,
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
                        PatchBody(step, m, small, describePath)
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
                                val listed = step.result.support != RomPatchFlow.Support.UNLISTED
                                if (!listed && onAskSupport != null) {
                                    OptionButton(tr("ASK FOR SUPPORT"), m, modifier = Modifier.weight(1f), onClick = { onAskSupport(step.result.file) })
                                }
                                OptionButton(tr("CLOSE"), m, emphasis = !listed || onPlay == null, modifier = Modifier.weight(1f), onClick = { flow.dismiss() })
                                if (listed && onPlay != null) {
                                    OptionButton(tr("PLAY"), m, emphasis = true, modifier = Modifier.weight(1f), onClick = { flow.dismiss(); onPlay(step.result.file) })
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
private fun PatchBody(
    step: RomPatchFlow.Step, m: GbaTextMetrics, small: GbaTextMetrics, describePath: (Path) -> String,
) {
    fun patchRows(info: RomPatchFlow.PatchInfo) = listOfNotNull(
        tr("FILE") to info.fileName,
        tr("MADE FOR") to (info.knownBase ?: tr("A ROM with CRC32 {0}", RomPatchFlow.crcText(info.sourceCrc))),
        // The patch's own notes (an author, a version), when it has any.
        info.metadata.takeIf { it.isNotEmpty() }?.let { tr("NOTES") to it.take(200) },
    )

    fun baseRows(plan: RomPatchFlow.Plan, info: RomPatchFlow.PatchInfo) = listOf(
        tr("GAME") to plan.baseLabel,
        tr("FILE") to describePath(plan.base),
        "CRC32" to tr("{0} (matches)", RomPatchFlow.crcText(info.sourceCrc)),
    )

    fun outRows(plan: RomPatchFlow.Plan, info: RomPatchFlow.PatchInfo) = listOf(
        tr("NAME") to plan.name,
        tr("SAVED TO") to describePath(plan.dir),
        tr("SIZE") to RomPatchFlow.sizeText(info.targetSize),
    )

    when (step) {
        is RomPatchFlow.Step.Looking -> {
            step.info?.let { InfoRows(tr("PATCH"), patchRows(it), first = true, m, small) }
            Spacer(Modifier.height(m.u * 6))
            Message(if (step.info == null) tr("Reading the patch…") else tr("Looking for its base ROM… {0}%", (step.progress * 100).toInt()), m)
            Spacer(Modifier.height(m.u * 3))
            SetupProgressBar(step.progress, m)
        }
        is RomPatchFlow.Step.NotFound -> {
            InfoRows(tr("PATCH"), patchRows(step.info), first = true, m, small)
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
            val patchDetails: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    InfoRows(tr("PATCH"), patchRows(step.info).filter { it.first != tr("MADE FOR") }, first = true, m, small)
                    InfoRows(tr("BASE ROM"), baseRows(step.plan, step.info), first = false, m, small)
                }
            }
            val outputDetails: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    InfoRows(tr("PATCHED ROM"), outRows(step.plan, step.info), first = true, m, small)
                    Spacer(Modifier.height(m.u * 6))
                    Message(tr("Your base ROM stays as it is: the patched game is saved as a new file."), small)
                }
            }
            BoxWithConstraints {
                if (maxWidth < 460.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(m.u * 6)) {
                        patchDetails(Modifier.fillMaxWidth())
                        outputDetails(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(m.u * 12)) {
                        patchDetails(Modifier.weight(1f))
                        outputDetails(Modifier.weight(1f))
                    }
                }
            }
        }
        is RomPatchFlow.Step.Patching -> {
            InfoRows(tr("PATCHED ROM"), outRows(step.plan, step.info), first = true, m, small)
            Spacer(Modifier.height(m.u * 6))
            Message(tr("Patching… {0}%", (step.progress * 100).toInt()), m)
            Spacer(Modifier.height(m.u * 3))
            SetupProgressBar(step.progress, m)
        }
        is RomPatchFlow.Step.Done -> {
            Message(if (step.result.existed) tr("ALREADY PATCHED") else tr("PATCHED!"), m, value = true)
            Spacer(Modifier.height(m.u * 2))
            Message(
                if (step.result.existed) tr("This patched ROM was already in {0}, so it was kept as it is.", step.result.file.parent?.let(describePath).orEmpty())
                else tr("{0} is saved in {1}.", step.result.file.name, step.result.file.parent?.let(describePath).orEmpty()),
                small,
            )
            Spacer(Modifier.height(m.u * 2))
            Message(
                when (step.result.support) {
                    RomPatchFlow.Support.LISTED -> tr("{0} is in your library.", step.result.label)
                    RomPatchFlow.Support.PARTIAL -> tr("{0} is in your library. The second screen shows part of it.", step.result.label)
                    RomPatchFlow.Support.TRYABLE ->
                        tr("{0} is in your library. The second screen doesn't know this game yet, but it can try to read it as the game it's built on.", step.result.label)
                    RomPatchFlow.Support.UNLISTED ->
                        tr("The second screen doesn't support {0}, so it isn't in your library.", step.result.label)
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
