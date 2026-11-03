package com.pokedaisy.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.core.content.FileProvider
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionOverlay
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import java.io.File

/**
 * The in-app update, for one activity: check GitHub ([AppUpdater]), offer the
 * newer release, download its APK into the cache and hand it to Android's
 * installer - asking first for "install unknown apps" if it isn't allowed yet,
 * and carrying on in [onResume] once the player comes back from that page.
 */
class AppUpdateFlow(private val activity: ComponentActivity) {
    /** The release on offer; the dialog shows while non-null. */
    var release by mutableStateOf<AppUpdater.Release?>(null)
    /** 0..1 while downloading. */
    var progress by mutableStateOf<Float?>(null)
    var error by mutableStateOf<String?>(null)
    var checking by mutableStateOf(false)
        private set
    private var pendingApk: File? = null

    private val updatesDir get() = File(activity.cacheDir, "updates")

    /** Looks for a newer release off the UI thread; [manual] reports "up to date" / offline too. */
    fun check(manual: Boolean = false) {
        if (checking) return
        checking = true
        Thread({
            val found = AppUpdater.check(BuildConfig.VERSION_NAME)
            // An APK we already offered is spent once it's installed (or abandoned) - but the one for the
            // release still on offer is kept, so UPDATE installs it at once instead of downloading it again.
            updatesDir.listFiles()?.forEach { if (pendingApk != it && (found == null || it.name != apkName(found))) it.delete() }
            activity.runOnUiThread {
                checking = false
                if (found != null) {
                    error = null
                    release = found
                } else if (manual) {
                    Toast.makeText(activity, tr("PokéDaisy {0} is the newest version", BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
                }
            }
        }, "pokedaisy-update-check").apply { isDaemon = true; start() }
    }

    fun dismiss() {
        if (progress != null) return
        release = null
        error = null
    }

    fun openReleasePage() {
        val url = release?.pageUrl ?: AppUpdater.RELEASES_PAGE
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun update() {
        val r = release ?: return
        if (progress != null) return
        error = null
        progress = 0f
        Thread({
            val apk = File(updatesDir, apkName(r))
            // Already here and whole (an earlier try that didn't reach the installer): no second download.
            val ok = (apk.isFile && (r.apkSize <= 0 || apk.length() == r.apkSize)) ||
                AppUpdater.download(r, apk) { p -> activity.runOnUiThread { progress = p } }
            activity.runOnUiThread {
                progress = null
                when {
                    !ok -> error = tr("THE DOWNLOAD FAILED - CHECK THE CONNECTION AND TRY AGAIN")
                    !isOurUpdate(apk) -> {
                        apk.delete()
                        error = tr("THE DOWNLOAD ISN'T A POKéDAISY UPDATE - NOT INSTALLED")
                    }
                    else -> install(apk)
                }
            }
        }, "pokedaisy-update-download").apply { isDaemon = true; start() }
    }

    /** Back from Android's "install unknown apps" page: install the APK it was for. */
    fun onResume() {
        val apk = pendingApk ?: return
        if (canInstall()) {
            pendingApk = null
            launchInstaller(apk)
        }
    }

    /**
     * Only an APK of this app, signed with this app's own key, newer than this build, goes to the
     * installer. Android would refuse a differently-signed update of this app anyway, but an APK
     * with another package name would install as a new app - so a release asset swapped on GitHub
     * (or anything in between) can't pass itself off as an update.
     */
    private fun isOurUpdate(apk: File): Boolean = runCatching {
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_SIGNATURES
        }
        // Some Android 9-12 builds leave signingInfo null for an archive: fall back to the signatures.
        @Suppress("DEPRECATION")
        fun certs(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else null)
                ?.map { it.toCharsString() }?.toSet()
                ?: info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        @Suppress("DEPRECATION")
        fun withSignatures(info: android.content.pm.PackageInfo?, refetch: (Int) -> android.content.pm.PackageInfo?) =
            if (info != null && certs(info).isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                refetch(android.content.pm.PackageManager.GET_SIGNATURES) ?: info
            } else info
        @Suppress("DEPRECATION")
        fun version(info: android.content.pm.PackageInfo) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        val theirs = withSignatures(pm.getPackageArchiveInfo(apk.path, flags)) { f -> pm.getPackageArchiveInfo(apk.path, f) } ?: return false
        val ours = withSignatures(pm.getPackageInfo(activity.packageName, flags)) { f -> pm.getPackageInfo(activity.packageName, f) }!!
        theirs.packageName == activity.packageName &&
            certs(ours).isNotEmpty() && certs(theirs) == certs(ours) &&
            version(theirs) > version(ours)
    }.getOrDefault(false)

    private fun canInstall() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun install(apk: File) {
        if (!canInstall()) {
            // Android's "install unknown apps" page for this app; back in onResume, the installer opens.
            val opened = runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                )
            }.isSuccess
            if (opened) {
                pendingApk = apk
                Toast.makeText(activity, tr("Allow PokéDaisy to install apps, then come back"), Toast.LENGTH_LONG).show()
                return
            }
            // A device without that page (it failed silently once, leaving UPDATE to download again and again):
            // the installer itself asks for the permission on the way.
        }
        launchInstaller(apk)
    }

    private fun apkName(r: AppUpdater.Release) = "PokeDaisy-${r.version}.apk"

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Straight to Android's installer: the library's VIEW filter (any type, see the
        // manifest) otherwise put PokeDaisy itself next to it in an "Open with" prompt.
        // The system's installer first: other apps can claim APKs too (on the Thor, another
        // emulator front-end's own VIEW filter answered before it; ArmadaOS ships its store and
        // launcher as system apps, so "a system app" alone isn't enough - GitHub #345).
        @Suppress("DEPRECATION")
        val candidates = activity.packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != activity.packageName }
        val system = { c: android.content.pm.ResolveInfo ->
            c.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }
        val installer = { c: android.content.pm.ResolveInfo -> "packageinstaller" in c.activityInfo.packageName }
        (candidates.firstOrNull { system(it) && installer(it) }
            ?: candidates.firstOrNull(installer)
            ?: candidates.firstOrNull(system)
            ?: candidates.firstOrNull())
            ?.let { intent.setClassName(it.activityInfo.packageName, it.activityInfo.name) }
        runCatching { activity.startActivity(intent) }
            .onSuccess { release = null }
            .onFailure { error = tr("ANDROID'S INSTALLER DIDN'T OPEN - GET THE APK FROM THE RELEASE PAGE") }
    }
}

/** [flow]'s offer: what's new, then LATER / RELEASE PAGE / UPDATE (a bar while it downloads). */
@Composable
fun UpdateDialog(flow: AppUpdateFlow, m: GbaTextMetrics, small: GbaTextMetrics) {
    val r = flow.release ?: return
    val downloading = flow.progress
    OptionOverlay(onDismiss = { flow.dismiss() }, modifier = Modifier.widthIn(max = 720.dp)) {
        Column {
            OptionTitleWindow(tr("UPDATE AVAILABLE"), m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 6, vertical = m.u * 2)) {
                    // On a small screen everything above the buttons scrolls, so the buttons stay on screen.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        GbaText(
                            tr("PokéDaisy {0} is out - you have {1}.", r.version, BuildConfig.VERSION_NAME),
                            OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE,
                        )
                        if (r.notes.isNotEmpty()) {
                            Spacer(Modifier.height(m.u * 4))
                            GbaText(r.notes, OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE)
                        }
                        flow.error?.let {
                            Spacer(Modifier.height(m.u * 4))
                            GbaText(it, OptionColors.value, OptionColors.valueShadow, small, maxLines = Int.MAX_VALUE)
                        }
                        if (downloading != null) {
                            Spacer(Modifier.height(m.u * 4))
                            GbaText(tr("DOWNLOADING… {0}%", (downloading * 100).toInt()), OptionColors.label, OptionColors.labelShadow, m)
                            Spacer(Modifier.height(m.u * 3))
                            SyncProgressBar(downloading, m)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = m.u * 6, bottom = m.u * 2),
                        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                    ) {
                        OptionButton(tr("LATER"), m, enabled = downloading == null, modifier = Modifier.weight(1f), onClick = { flow.dismiss() })
                        OptionButton(tr("RELEASE PAGE"), m, modifier = Modifier.weight(1f), onClick = { flow.openReleasePage() })
                        OptionButton(
                            tr("UPDATE"), m, emphasis = true, enabled = downloading == null,
                            modifier = Modifier.weight(1f), onClick = { flow.update() },
                        )
                    }
                }
            }
        }
    }
}
