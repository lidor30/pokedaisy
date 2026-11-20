package com.pokedaisy.app

import android.net.Uri
import androidx.activity.ComponentActivity
import com.pokedaisy.app.companion.ui.RomPatchFlow
import java.io.File
import okio.Path.Companion.toOkioPath
import okio.source

/** Android document URIs, linked folders, archives, and library indexing for the shared patch flow. */
internal class AndroidRomPatches(activity: ComponentActivity, onAdded: (File) -> Unit) {
    private val owner = activity
    private val prefs by lazy { Prefs(activity) }
    private val importedDir by lazy { File(activity.getExternalFilesDir(null), "roms") }
    private val crcs by lazy { RomCrcs.cache(activity) }
    val flow = RomPatchFlow(
        find = { patch, patchName, progress ->
            val base = RomPatcher.findBase(RomFolder.allRomFiles(activity, prefs), patch.sourceSize, patch.sourceCrc, crcs, progress)
            base?.let {
                val platform = RomPatcher.platformOf(it)
                RomPatchFlow.Plan(
                    it.absoluteFile.toOkioPath(),
                    GameTitles.label(activity, prefs, it),
                    RomPatcher.outputDir(platform, it, prefs.romsFolders, importedDir).absoluteFile.toOkioPath(),
                    RomPatcher.outputName(patchName, RomPatcher.extensionFor(it, platform)),
                )
            }
        },
        write = { patch, plan, progress ->
            val result = RomPatcher.apply(patch, plan.base.toFile(), plan.dir.toFile(), plan.name, progress)
            val file = result.file
            val verdict = runCatching { CompanionSupport.verdict(file) }.getOrDefault(CompanionSupport.Verdict.UNSUPPORTED)
            val imported = file.absoluteFile.parentFile == importedDir.absoluteFile
            // An imported ROM the companion can't read stays out of the main library (HIDDEN GAMES can show it).
            if (imported && verdict == CompanionSupport.Verdict.UNSUPPORTED && !result.existed) {
                prefs.hiddenRoms = prefs.hiddenRoms + file.absolutePath
            }
            RomAdded.record(activity, listOf(file), initial = false)
            if (imported) RomFolder.checkImported(activity) else RomFolder.scan(activity, prefs)
            GameTitles.identify(activity, listOf(file))
            val support = when (verdict) {
                CompanionSupport.Verdict.SUPPORTED, CompanionSupport.Verdict.MATCHED -> RomPatchFlow.Support.LISTED
                CompanionSupport.Verdict.PARTIAL -> RomPatchFlow.Support.PARTIAL
                CompanionSupport.Verdict.TRYABLE -> RomPatchFlow.Support.TRYABLE
                CompanionSupport.Verdict.UNSUPPORTED -> RomPatchFlow.Support.UNLISTED
            }
            RomPatchFlow.Result(file.toOkioPath(), result.existed, support, GameTitles.label(activity, prefs, file))
        },
        onAdded = { onAdded(it.file.toFile()) },
    )

    fun start(uri: Uri, name: String? = null) {
        if (RomIntake.isOwn(owner, uri)) return
        val fileName = name ?: RomUris.displayName(owner, uri) ?: "patch.bps"
        flow.start(fileName) {
            checkNotNull(owner.contentResolver.openInputStream(uri)).source()
        }
    }
}
